package pl.local.czytnikstrony;

import android.content.Context;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;

/**
 * Biblioteka artykułów: historia czytania i kolejka „Czytaj później”.
 * Metadane w library.json, treść każdego artykułu w osobnym pliku.
 */
public final class Library {

    public static final class Article {
        String id;
        String title = "";
        String url = "";
        int index = 0;          // ostatni czytany fragment
        int count = 0;          // liczba fragmentów (0 = nieznana)
        int chars = 0;
        boolean queued = false; // w kolejce „Czytaj później”
        boolean finished = false;
        long addedAt;
        long lastReadAt;

        String domain() { return Library.domainOf(url); }

        float progress() {
            if (finished) return 1f;
            if (count <= 1) return 0f;
            return Math.max(0f, Math.min(1f, index / (float) (count - 1)));
        }

        String displayTitle() {
            if (title != null && !title.isEmpty()) return title;
            if (url != null && !url.isEmpty()) return url;
            return "Tekst bez tytułu";
        }
    }

    public interface Listener { void onLibraryChanged(); }

    private static Library instance;

    static synchronized Library get(Context ctx) {
        if (instance == null) instance = new Library(ctx.getApplicationContext());
        return instance;
    }

    private final Context app;
    private final File dir;
    private final List<Article> items = new ArrayList<>();
    private final List<Listener> listeners = new ArrayList<>();
    private final Handler main = new Handler(Looper.getMainLooper());
    private int version = 0;

    private Library(Context app) {
        this.app = app;
        dir = new File(app.getFilesDir(), "library");
        //noinspection ResultOfMethodCallIgnored
        dir.mkdirs();
        load();
    }

    void addListener(Listener l)    { if (!listeners.contains(l)) listeners.add(l); }
    void removeListener(Listener l) { listeners.remove(l); }
    int version()                   { return version; }

    private void changed() {
        version++;
        save();
        main.post(() -> { for (Listener l : new ArrayList<>(listeners)) l.onLibraryChanged(); });
    }

    // ── Zapytania ────────────────────────────────────────────────────────

    /** Wszystkie, ostatnio czytane na górze. */
    List<Article> history() {
        List<Article> out = new ArrayList<>();
        for (Article a : items) if (!a.queued) out.add(a);
        out.sort((a, b) -> Long.compare(b.lastReadAt, a.lastReadAt));
        return out;
    }

    /** Kolejka w kolejności dodania. */
    List<Article> queue() {
        List<Article> out = new ArrayList<>();
        for (Article a : items) if (a.queued) out.add(a);
        out.sort((a, b) -> Long.compare(a.addedAt, b.addedAt));
        return out;
    }

    Article get(String id) {
        if (id == null) return null;
        for (Article a : items) if (a.id.equals(id)) return a;
        return null;
    }

    Article findByUrl(String url) {
        if (url == null || url.isEmpty()) return null;
        for (Article a : items) if (url.equals(a.url)) return a;
        return null;
    }

    /** Ostatni nieskończony artykuł (do „Kontynuuj”). */
    Article lastUnfinished() {
        for (Article a : history()) if (!a.finished && hasText(a.id)) return a;
        return null;
    }

    String text(String id) {
        File f = new File(dir, id + ".txt");
        if (!f.exists()) return null;
        try (InputStream in = new FileInputStream(f)) {
            byte[] b = new byte[(int) f.length()];
            int off = 0, n;
            while (off < b.length && (n = in.read(b, off, b.length - off)) > 0) off += n;
            return new String(b, 0, off, StandardCharsets.UTF_8);
        } catch (Exception e) { return null; }
    }

    boolean hasText(String id) { return new File(dir, id + ".txt").exists(); }

    // ── Zmiany ───────────────────────────────────────────────────────────

    /** Dodaje lub aktualizuje artykuł (po adresie) i zapisuje treść. */
    Article upsert(String title, String url, String text, boolean queued) {
        Article a = findByUrl(url);
        if (a == null || url == null || url.isEmpty()) {
            a = new Article();
            a.id = UUID.randomUUID().toString().substring(0, 12);
            a.addedAt = System.currentTimeMillis();
            items.add(a);
        }
        a.title = title != null ? title : "";
        a.url = url != null ? url : "";
        a.queued = queued;
        if (text != null) {
            a.chars = text.length();
            writeText(a.id, text);
        }
        if (!queued) a.lastReadAt = System.currentTimeMillis();
        changed();
        return a;
    }

    void markReading(String id, int count) {
        Article a = get(id);
        if (a == null) return;
        a.queued = false;
        a.finished = false;
        a.count = count;
        a.lastReadAt = System.currentTimeMillis();
        changed();
    }

    void setProgress(String id, int index, int count) {
        Article a = get(id);
        if (a == null) return;
        a.index = index;
        a.count = count;
        a.lastReadAt = System.currentTimeMillis();
        changed();
    }

    void markFinished(String id) {
        Article a = get(id);
        if (a == null) return;
        a.finished = true;
        a.index = 0;
        changed();
    }

    void setQueued(String id, boolean q) {
        Article a = get(id);
        if (a == null) return;
        a.queued = q;
        if (q) a.addedAt = System.currentTimeMillis();
        changed();
    }

    void remove(String id) {
        Article a = get(id);
        if (a == null) return;
        items.remove(a);
        //noinspection ResultOfMethodCallIgnored
        new File(dir, id + ".txt").delete();
        changed();
    }

    /** Następny w kolejce z wczytaną treścią. */
    Article nextQueued() {
        for (Article a : queue()) if (hasText(a.id)) return a;
        return null;
    }

    // ── Pliki ────────────────────────────────────────────────────────────

    private void writeText(String id, String text) {
        try (OutputStream os = new FileOutputStream(new File(dir, id + ".txt"))) {
            os.write(text.getBytes(StandardCharsets.UTF_8));
        } catch (Exception ignored) {}
    }

    private void load() {
        File f = new File(dir, "library.json");
        if (!f.exists()) return;
        try (InputStream in = new FileInputStream(f)) {
            byte[] b = new byte[(int) f.length()];
            int off = 0, n;
            while (off < b.length && (n = in.read(b, off, b.length - off)) > 0) off += n;
            JSONArray arr = new JSONArray(new String(b, 0, off, StandardCharsets.UTF_8));
            for (int i = 0; i < arr.length(); i++) {
                JSONObject o = arr.getJSONObject(i);
                Article a = new Article();
                a.id = o.getString("id");
                a.title = o.optString("title", "");
                a.url = o.optString("url", "");
                a.index = o.optInt("index", 0);
                a.count = o.optInt("count", 0);
                a.chars = o.optInt("chars", 0);
                a.queued = o.optBoolean("queued", false);
                a.finished = o.optBoolean("finished", false);
                a.addedAt = o.optLong("addedAt", 0);
                a.lastReadAt = o.optLong("lastReadAt", 0);
                items.add(a);
            }
        } catch (Exception ignored) {}
    }

    private void save() {
        try {
            JSONArray arr = new JSONArray();
            for (Article a : items) {
                JSONObject o = new JSONObject();
                o.put("id", a.id);
                o.put("title", a.title);
                o.put("url", a.url);
                o.put("index", a.index);
                o.put("count", a.count);
                o.put("chars", a.chars);
                o.put("queued", a.queued);
                o.put("finished", a.finished);
                o.put("addedAt", a.addedAt);
                o.put("lastReadAt", a.lastReadAt);
                arr.put(o);
            }
            File tmp = new File(dir, "library.json.tmp");
            try (OutputStream os = new FileOutputStream(tmp)) {
                os.write(arr.toString().getBytes(StandardCharsets.UTF_8));
            }
            //noinspection ResultOfMethodCallIgnored
            tmp.renameTo(new File(dir, "library.json"));
        } catch (Exception ignored) {}
    }

    static String domainOf(String url) {
        if (url == null || url.isEmpty()) return "";
        try {
            String h = Uri.parse(url).getHost();
            if (h == null) return "";
            return h.startsWith("www.") ? h.substring(4) : h;
        } catch (Exception e) { return ""; }
    }

    static List<Article> empty() { return Collections.emptyList(); }
}
