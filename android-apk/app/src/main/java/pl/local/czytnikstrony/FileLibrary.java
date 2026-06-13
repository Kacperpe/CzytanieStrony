package pl.local.czytnikstrony;

import android.content.Context;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Lokalna biblioteka wczytanych plików: zapisuje wyodrębniony tekst na
 * urządzeniu (pamięć wewnętrzna aplikacji) razem z metadanymi i pozycją
 * wznowienia (na którym fragmencie skończyło się słuchanie). Sprząta wpisy
 * starsze niż ustawiony czas przechowywania oraz przekraczające limit pamięci.
 */
class FileLibrary {

    /** Specjalna wartość retencji: przechowuj plik bez limitu czasu. */
    static final int RETENTION_FOREVER = 0;

    /** Pojedynczy zapamiętany plik. */
    static class Item {
        String id;
        String title;
        long   savedAt;
        long   lastOpenedAt;
        long   sizeBytes;
        int    resumeChunk;
        int    totalChunks;
        int    retentionDays = 3;   // 0 = na zawsze (RETENTION_FOREVER)

        boolean isForever() { return retentionDays <= RETENTION_FOREVER; }

        /** Postęp 0–100% (na podstawie zapamiętanego fragmentu). */
        int percent() {
            if (totalChunks <= 1) return 0;
            int p = Math.round(resumeChunk * 100f / totalChunks);
            return Math.max(0, Math.min(100, p));
        }
    }

    private static File dir(Context ctx) {
        File d = new File(ctx.getFilesDir(), "library");
        if (!d.exists()) d.mkdirs();
        return d;
    }

    private static File indexFile(Context ctx) { return new File(dir(ctx), "index.json"); }
    private static File textFile(Context ctx, String id) { return new File(dir(ctx), id + ".txt"); }

    /** Stałe id wyprowadzone z nazwy pliku — ten sam plik = ten sam wpis. */
    static String idFor(String title) {
        String t = title == null ? "" : title.toLowerCase();
        return Integer.toHexString(t.hashCode()) + "-" + Math.abs(t.length());
    }

    /** Lista wpisów, najświeższe (ostatnio otwierane) na górze. */
    static List<Item> list(Context ctx) {
        List<Item> items = new ArrayList<>();
        File f = indexFile(ctx);
        if (!f.exists()) return items;
        try {
            JSONArray arr = new JSONArray(readFile(f));
            for (int i = 0; i < arr.length(); i++) {
                JSONObject o = arr.getJSONObject(i);
                Item it = new Item();
                it.id           = o.getString("id");
                it.title        = o.optString("title", it.id);
                it.savedAt      = o.optLong("savedAt");
                it.lastOpenedAt = o.optLong("lastOpenedAt", it.savedAt);
                it.sizeBytes    = o.optLong("sizeBytes");
                it.resumeChunk  = o.optInt("resumeChunk");
                it.totalChunks  = o.optInt("totalChunks");
                it.retentionDays = o.optInt("retentionDays", 3);
                if (textFile(ctx, it.id).exists()) items.add(it);
            }
        } catch (Exception ignored) {}
        Collections.sort(items, (a, b) -> Long.compare(b.lastOpenedAt, a.lastOpenedAt));
        return items;
    }

    private static void writeIndex(Context ctx, List<Item> items) {
        JSONArray arr = new JSONArray();
        for (Item it : items) {
            try {
                JSONObject o = new JSONObject();
                o.put("id", it.id);
                o.put("title", it.title);
                o.put("savedAt", it.savedAt);
                o.put("lastOpenedAt", it.lastOpenedAt);
                o.put("sizeBytes", it.sizeBytes);
                o.put("resumeChunk", it.resumeChunk);
                o.put("totalChunks", it.totalChunks);
                o.put("retentionDays", it.retentionDays);
                arr.put(o);
            } catch (Exception ignored) {}
        }
        try (FileOutputStream fos = new FileOutputStream(indexFile(ctx))) {
            fos.write(arr.toString().getBytes(StandardCharsets.UTF_8));
        } catch (Exception ignored) {}
    }

    /**
     * Zapisuje (lub nadpisuje) tekst pliku i zwraca jego wpis. Nowemu plikowi
     * nadaje domyślną retencję {@code defaultRetentionDays}; istniejący zachowuje
     * swoją (decyzja per plik nie jest nadpisywana przy ponownym wczytaniu).
     */
    static Item save(Context ctx, String title, String text, int defaultRetentionDays) {
        String id = idFor(title);
        byte[] bytes = text.getBytes(StandardCharsets.UTF_8);
        try (FileOutputStream fos = new FileOutputStream(textFile(ctx, id))) {
            fos.write(bytes);
        } catch (Exception e) {
            return null;
        }
        List<Item> items = list(ctx);
        Item found = null;
        for (Item it : items) if (it.id.equals(id)) { found = it; break; }
        long now = System.currentTimeMillis();
        if (found == null) {
            found = new Item();
            found.id = id;
            found.savedAt = now;
            found.resumeChunk = 0;
            found.retentionDays = defaultRetentionDays;
            items.add(found);
        }
        found.title = title;
        found.lastOpenedAt = now;
        found.sizeBytes = bytes.length;
        writeIndex(ctx, items);
        return found;
    }

    /** Ustawia retencję pojedynczego pliku (0 = na zawsze). */
    static void setRetention(Context ctx, String id, int days) {
        List<Item> items = list(ctx);
        for (Item it : items) {
            if (it.id.equals(id)) { it.retentionDays = days; writeIndex(ctx, items); return; }
        }
    }

    static String readText(Context ctx, String id) {
        File f = textFile(ctx, id);
        if (!f.exists()) return null;
        try { return readFile(f); } catch (Exception e) { return null; }
    }

    /** Zapamiętuje, na którym fragmencie skończyło się słuchanie. */
    static void updateProgress(Context ctx, String id, int resumeChunk, int totalChunks) {
        List<Item> items = list(ctx);
        boolean changed = false;
        for (Item it : items) {
            if (it.id.equals(id)) {
                it.resumeChunk  = resumeChunk;
                it.totalChunks  = totalChunks;
                it.lastOpenedAt = System.currentTimeMillis();
                changed = true;
                break;
            }
        }
        if (changed) writeIndex(ctx, items);
    }

    static void remove(Context ctx, String id) {
        List<Item> keep = new ArrayList<>();
        for (Item it : list(ctx)) {
            if (it.id.equals(id)) textFile(ctx, it.id).delete();
            else keep.add(it);
        }
        writeIndex(ctx, keep);
    }

    static void clearAll(Context ctx) {
        for (Item it : list(ctx)) textFile(ctx, it.id).delete();
        indexFile(ctx).delete();
    }

    static long totalBytes(Context ctx) {
        long sum = 0;
        for (Item it : list(ctx)) sum += it.sizeBytes;
        return sum;
    }

    /**
     * Sprząta bibliotekę: usuwa pliki, którym minął ich własny czas
     * przechowywania (poza oznaczonymi „na zawsze"), a gdy łączny rozmiar
     * przekracza {@code capBytes} — eksmituje najstarsze NIE-oznaczone „na
     * zawsze" (te są chronione i pozostają mimo limitu).
     */
    static void enforce(Context ctx, long capBytes) {
        long now = System.currentTimeMillis();

        List<Item> fresh = new ArrayList<>();
        for (Item it : list(ctx)) {            // już posortowane: najświeższe pierwsze
            if (!it.isForever()) {
                long maxAge = it.retentionDays * 24L * 3600_000L;
                if (now - it.lastOpenedAt > maxAge) { textFile(ctx, it.id).delete(); continue; }
            }
            fresh.add(it);
        }

        // Limit pamięci: pliki „na zawsze" zostają zawsze; resztę tniemy od najstarszych.
        long sum = 0;
        List<Item> kept = new ArrayList<>();
        for (Item it : fresh) if (it.isForever()) { kept.add(it); sum += it.sizeBytes; }
        for (Item it : fresh) {
            if (it.isForever()) continue;
            if (!kept.isEmpty() && sum + it.sizeBytes > capBytes) {
                textFile(ctx, it.id).delete();
            } else {
                sum += it.sizeBytes;
                kept.add(it);
            }
        }
        writeIndex(ctx, kept);
    }

    private static String readFile(File f) throws Exception {
        byte[] buf = new byte[(int) f.length()];
        try (FileInputStream fis = new FileInputStream(f)) {
            int read = 0;
            while (read < buf.length) {
                int r = fis.read(buf, read, buf.length - read);
                if (r < 0) break;
                read += r;
            }
        }
        return new String(buf, StandardCharsets.UTF_8);
    }
}
