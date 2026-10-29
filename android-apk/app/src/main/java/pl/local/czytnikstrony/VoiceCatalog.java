package pl.local.czytnikstrony;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;

import org.apache.commons.compress.archivers.tar.TarArchiveEntry;
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream;
import org.apache.commons.compress.compressors.bzip2.BZip2CompressorInputStream;

import android.net.Uri;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedInputStream;
import java.io.FileInputStream;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.Properties;
import java.io.File;
import java.io.FileOutputStream;
import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Katalog naturalnych (neuronowych) głosów Piper w formacie sherpa-onnx
 * oraz ich pobieranie/rozpakowywanie do pamięci aplikacji.
 */
public final class VoiceCatalog {

    static final String BASE_URL =
        "https://github.com/k2-fsa/sherpa-onnx/releases/download/tts-models/";

    public static final class Model {
        final String id;       // np. vits-piper-pl_PL-gosia-medium
        final String lang;     // "pl" / "en"
        final String name;     // nazwa wyświetlana
        final String desc;     // płeć / akcent
        final int    approxMb; // przybliżony rozmiar pobrania

        Model(String id, String lang, String name, String desc, int approxMb) {
            this.id = id; this.lang = lang; this.name = name; this.desc = desc; this.approxMb = approxMb;
        }

        String url() { return BASE_URL + id + ".tar.bz2"; }
        boolean high() { return id.endsWith("-high"); }
    }

    static final List<Model> MODELS = Arrays.asList(
        new Model("vits-piper-pl_PL-gosia-medium",          "pl", "Gosia",        "kobieta",                 64),
        new Model("vits-piper-pl_PL-darkman-medium",        "pl", "Darkman",      "mężczyzna",               64),
        new Model("vits-piper-pl_PL-mc_speech-medium",      "pl", "MC Speech",    "kobieta",                 64),
        new Model("vits-piper-pl_PL-bass-high",             "pl", "Bass",         "mężczyzna · wysoka jakość", 115),
        new Model("vits-piper-pl_PL-zenski_wg_glos-medium", "pl", "Żeński (WG)",  "kobieta",                 64),
        new Model("vits-piper-pl_PL-meski_wg_glos-medium",  "pl", "Męski (WG)",   "mężczyzna",               64),
        new Model("vits-piper-pl_PL-justyna_wg_glos-medium","pl", "Justyna (WG)", "kobieta",                 64),
        new Model("vits-piper-pl_PL-jarvis_wg_glos-medium", "pl", "Jarvis (WG)",  "mężczyzna",               64),

        new Model("vits-piper-en_US-amy-medium",            "en", "Amy",          "kobieta · US",            64),
        new Model("vits-piper-en_US-lessac-medium",         "en", "Lessac",       "kobieta · US",            64),
        new Model("vits-piper-en_US-joe-medium",            "en", "Joe",          "mężczyzna · US",          64),
        new Model("vits-piper-en_US-kristin-medium",        "en", "Kristin",      "kobieta · US",            64),
        new Model("vits-piper-en_US-norman-medium",         "en", "Norman",       "mężczyzna · US",          64),
        new Model("vits-piper-en_GB-alba-medium",           "en", "Alba",         "kobieta · UK",            64),
        new Model("vits-piper-en_GB-cori-medium",           "en", "Cori",         "kobieta · UK",            64)
    );

    static final String CUSTOM_PREFIX = "custom-";

    static boolean isCustom(String id) { return id != null && id.startsWith(CUSTOM_PREFIX); }

    static Model find(Context ctx, String id) {
        for (Model m : MODELS) if (m.id.equals(id)) return m;
        for (Model m : customModels(ctx)) if (m.id.equals(id)) return m;
        return null;
    }

    static List<Model> forLang(Context ctx, String lang) {
        List<Model> out = new ArrayList<>();
        for (Model m : customModels(ctx)) if (m.lang.equals(lang)) out.add(m);
        for (Model m : MODELS) if (m.lang.equals(lang)) out.add(m);
        return out;
    }

    /** Własne (zaimportowane) głosy zapisane w pamięci aplikacji. */
    static List<Model> customModels(Context ctx) {
        List<Model> out = new ArrayList<>();
        File[] dirs = root(ctx).listFiles(f -> f.isDirectory() && f.getName().startsWith(CUSTOM_PREFIX));
        if (dirs == null) return out;
        Arrays.sort(dirs);
        for (File d : dirs) {
            if (!new File(d, ".ok").exists()) continue;
            Properties p = new Properties();
            try (InputStream in = new FileInputStream(new File(d, "voice.properties"))) {
                p.load(new java.io.InputStreamReader(in, StandardCharsets.UTF_8));
            } catch (IOException e) { continue; }
            out.add(new Model(d.getName(), p.getProperty("lang", "pl"),
                p.getProperty("name", d.getName()), "własny głos", 0));
        }
        return out;
    }

    /**
     * Katalog espeak-ng-data potrzebny Piperowi do wymowy. Własne głosy go nie mają,
     * więc pożyczamy go z dowolnego pobranego głosu z katalogu.
     */
    static File espeakDataFor(Context ctx, String id) {
        File own = new File(dir(ctx, id), "espeak-ng-data");
        if (own.isDirectory()) return own;
        for (Model m : MODELS) {
            File d = new File(dir(ctx, m.id), "espeak-ng-data");
            if (isInstalled(ctx, m.id) && d.isDirectory()) return d;
        }
        return null;
    }

    static boolean hasAnyEspeakData(Context ctx) { return espeakDataFor(ctx, "-") != null; }

    // ── Import własnego głosu Piper (.onnx + .onnx.json) ─────────────────

    public interface ImportListener { void onDone(String idOrNull, String errorOrNull); }

    static void importCustom(Context ctx, Uri onnx, Uri json, ImportListener l) {
        Context app = ctx.getApplicationContext();
        new Thread(() -> {
            String id = null, err = null;
            File tmp = null;
            try {
                JSONObject cfg = new JSONObject(readText(app, json));
                JSONObject map = cfg.getJSONObject("phoneme_id_map");
                int sampleRate = cfg.getJSONObject("audio").optInt("sample_rate", 22050);
                if (sampleRate == 22500) sampleRate = 22050;
                String voice = cfg.has("espeak") ? cfg.getJSONObject("espeak").optString("voice", "pl") : "pl";
                int speakers = cfg.optInt("num_speakers", 1);
                String lang = voice.toLowerCase(Locale.ROOT).startsWith("en") ? "en" : "pl";

                String base = displayName(app, onnx).replaceAll("(?i)\\.onnx$", "");
                String slug = base.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9_-]+", "_");
                if (slug.isEmpty()) slug = "glos";
                id = CUSTOM_PREFIX + slug + "-" + (System.currentTimeMillis() % 100000);
                tmp = new File(root(app), id + ".part");
                rm(tmp);
                //noinspection ResultOfMethodCallIgnored
                tmp.mkdirs();

                // tokens.txt z phoneme_id_map (tak jak skrypt sherpa-onnx)
                StringBuilder tok = new StringBuilder();
                java.util.Iterator<String> keys = map.keys();
                while (keys.hasNext()) {
                    String sym = keys.next();
                    if (sym.equals("\n")) continue;
                    Object v = map.get(sym);
                    int tid = v instanceof JSONArray ? ((JSONArray) v).getInt(0) : map.getInt(sym);
                    tok.append(sym).append(' ').append(tid).append('\n');
                }
                try (OutputStream os = new FileOutputStream(new File(tmp, "tokens.txt"))) {
                    os.write(tok.toString().getBytes(StandardCharsets.UTF_8));
                }

                // Kopia modelu + dopisanie metadanych ONNX wymaganych przez sherpa-onnx.
                File model = new File(tmp, "model.onnx");
                try (InputStream in = app.getContentResolver().openInputStream(onnx);
                     OutputStream os = new FileOutputStream(model)) {
                    if (in == null) throw new IOException("Nie można otworzyć pliku .onnx");
                    byte[] buf = new byte[1 << 16];
                    int n;
                    while ((n = in.read(buf)) > 0) os.write(buf, 0, n);
                    Map<String, String> meta = new java.util.LinkedHashMap<>();
                    meta.put("model_type", "vits");
                    meta.put("comment", "piper");
                    meta.put("language", lang.equals("en") ? "English" : "Polish");
                    meta.put("voice", voice);
                    meta.put("version", "1");
                    meta.put("has_espeak", "1");
                    meta.put("has_g2pw", "0");
                    meta.put("n_speakers", String.valueOf(speakers));
                    meta.put("sample_rate", String.valueOf(sampleRate));
                    for (Map.Entry<String, String> e : meta.entrySet()) {
                        os.write(metadataProp(e.getKey(), e.getValue()));
                    }
                }
                if (model.length() < 1_000_000) throw new IOException("Plik .onnx jest za mały — to na pewno model Piper?");

                Properties p = new Properties();
                p.setProperty("name", base);
                p.setProperty("lang", lang);
                try (OutputStream os = new FileOutputStream(new File(tmp, "voice.properties"))) {
                    p.store(new java.io.OutputStreamWriter(os, StandardCharsets.UTF_8), null);
                }
                File target = dir(app, id);
                if (!tmp.renameTo(target)) throw new IOException("Nie udało się zapisać głosu");
                //noinspection ResultOfMethodCallIgnored
                new File(target, ".ok").createNewFile();
            } catch (Throwable e) {
                err = e.getMessage() != null ? e.getMessage() : e.toString();
                if (tmp != null) rm(tmp);
                id = null;
            }
            final String fid = id, ferr = err;
            MAIN.post(() -> l.onDone(fid, ferr));
        }, "voice-import").start();
    }

    /**
     * Zakodowane pole ModelProto.metadata_props (nr 14) z wpisem key/value.
     * Protobuf pozwala dopisać pole na końcu pliku — onnxruntime scali je z resztą modelu.
     */
    private static byte[] metadataProp(String key, String value) {
        byte[] k = key.getBytes(StandardCharsets.UTF_8);
        byte[] v = value.getBytes(StandardCharsets.UTF_8);
        java.io.ByteArrayOutputStream entry = new java.io.ByteArrayOutputStream();
        entry.write(0x0A); varint(entry, k.length); entry.write(k, 0, k.length);
        entry.write(0x12); varint(entry, v.length); entry.write(v, 0, v.length);
        byte[] e = entry.toByteArray();
        java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
        out.write((14 << 3) | 2); varint(out, e.length); out.write(e, 0, e.length);
        return out.toByteArray();
    }

    private static void varint(java.io.ByteArrayOutputStream o, int v) {
        while ((v & ~0x7F) != 0) { o.write((v & 0x7F) | 0x80); v >>>= 7; }
        o.write(v);
    }

    private static String readText(Context ctx, Uri uri) throws IOException {
        try (InputStream in = ctx.getContentResolver().openInputStream(uri)) {
            if (in == null) throw new IOException("Nie można otworzyć pliku .json");
            java.io.ByteArrayOutputStream b = new java.io.ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) > 0) b.write(buf, 0, n);
            return b.toString("UTF-8");
        }
    }

    static String displayName(Context ctx, Uri uri) {
        String name = null;
        try (android.database.Cursor c = ctx.getContentResolver().query(uri,
                new String[]{android.provider.OpenableColumns.DISPLAY_NAME}, null, null, null)) {
            if (c != null && c.moveToFirst()) name = c.getString(0);
        } catch (Exception ignored) {}
        if (name == null) name = uri.getLastPathSegment();
        return name == null ? "glos" : name;
    }

    // ── Pliki ────────────────────────────────────────────────────────────

    static File root(Context ctx) {
        File d = new File(ctx.getFilesDir(), "voices");
        //noinspection ResultOfMethodCallIgnored
        d.mkdirs();
        return d;
    }

    static File dir(Context ctx, String id) { return new File(root(ctx), id); }

    static boolean isInstalled(Context ctx, String id) {
        return new File(dir(ctx, id), ".ok").exists();
    }

    static File modelFile(Context ctx, String id) {
        File[] files = dir(ctx, id).listFiles((d, n) -> n.endsWith(".onnx"));
        if (files == null || files.length == 0) return null;
        // Preferuj pełny model (nie int8) jeśli są oba
        Arrays.sort(files, (a, b) -> Boolean.compare(a.getName().contains("int8"), b.getName().contains("int8")));
        return files[0];
    }

    static long sizeOnDisk(Context ctx, String id) { return du(dir(ctx, id)); }

    private static long du(File f) {
        if (f.isFile()) return f.length();
        long s = 0;
        File[] k = f.listFiles();
        if (k != null) for (File c : k) s += du(c);
        return s;
    }

    static void delete(Context ctx, String id) { rm(dir(ctx, id)); }

    private static void rm(File f) {
        File[] k = f.listFiles();
        if (k != null) for (File c : k) rm(c);
        //noinspection ResultOfMethodCallIgnored
        f.delete();
    }

    // ── Pobieranie ───────────────────────────────────────────────────────

    public interface DownloadListener {
        void onProgress(String id, long done, long total);
        void onDone(String id, String errorOrNull);
    }

    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    private static final Map<String, long[]> progress = new HashMap<>();   // id -> {done,total}
    private static final Map<String, boolean[]> cancel = new HashMap<>();
    private static final List<DownloadListener> listeners = new ArrayList<>();

    static void addListener(DownloadListener l)    { listeners.add(l); }
    static void removeListener(DownloadListener l) { listeners.remove(l); }

    static boolean isDownloading(String id) { return progress.containsKey(id); }

    /** {pobrano, całość} albo null. */
    static long[] progressOf(String id) { return progress.get(id); }

    static void cancel(String id) {
        boolean[] c = cancel.get(id);
        if (c != null) c[0] = true;
    }

    static void download(Context ctx, Model m) {
        if (progress.containsKey(m.id) || isInstalled(ctx, m.id)) return;
        Context app = ctx.getApplicationContext();
        boolean[] cancelled = {false};
        progress.put(m.id, new long[]{0, (long) m.approxMb * 1024 * 1024});
        cancel.put(m.id, cancelled);
        notifyProgress(m.id, 0, -1);

        new Thread(() -> {
            String error = null;
            File target = dir(app, m.id);
            File tmp = new File(root(app), m.id + ".part");
            try {
                rm(target);
                rm(tmp);
                //noinspection ResultOfMethodCallIgnored
                tmp.mkdirs();
                fetchAndExtract(m, tmp, cancelled);
                if (cancelled[0]) throw new IOException("Anulowano");
                if (!tmp.renameTo(target)) throw new IOException("Nie udało się zapisać głosu");
                if (modelFile(app, m.id) == null || !new File(target, "tokens.txt").exists())
                    throw new IOException("Niepełne pliki głosu");
                //noinspection ResultOfMethodCallIgnored
                new File(target, ".ok").createNewFile();
            } catch (Throwable e) {
                error = cancelled[0] ? "Anulowano" : (e.getMessage() != null ? e.getMessage() : e.toString());
                rm(tmp);
                rm(target);
            }
            final String err = error;
            MAIN.post(() -> {
                progress.remove(m.id);
                cancel.remove(m.id);
                for (DownloadListener l : new ArrayList<>(listeners)) l.onDone(m.id, err);
            });
        }, "voice-dl-" + m.id).start();
    }

    private static void notifyProgress(String id, long done, long total) {
        MAIN.post(() -> {
            long[] p = progress.get(id);
            if (p == null) return;
            p[0] = done;
            if (total > 0) p[1] = total;
            for (DownloadListener l : new ArrayList<>(listeners)) l.onProgress(id, p[0], p[1]);
        });
    }

    private static void fetchAndExtract(Model m, File outDir, boolean[] cancelled) throws IOException {
        HttpURLConnection conn = open(m.url());
        try {
            int code = conn.getResponseCode();
            if (code != 200) throw new IOException("Serwer zwrócił " + code);
            final long total = conn.getContentLengthLong();
            final long[] read = {0};
            final long[] lastNotify = {0};

            InputStream counting = new FilterInputStream(new BufferedInputStream(conn.getInputStream(), 1 << 16)) {
                @Override public int read() throws IOException {
                    int b = super.read();
                    if (b >= 0) tick(1);
                    return b;
                }
                @Override public int read(byte[] buf, int off, int len) throws IOException {
                    if (cancelled[0]) throw new IOException("Anulowano");
                    int n = super.read(buf, off, len);
                    if (n > 0) tick(n);
                    return n;
                }
                private void tick(int n) {
                    read[0] += n;
                    long now = System.currentTimeMillis();
                    if (now - lastNotify[0] > 250) {
                        lastNotify[0] = now;
                        notifyProgress(m.id, read[0], total);
                    }
                }
            };

            String canonicalOut = outDir.getCanonicalPath() + File.separator;
            try (TarArchiveInputStream tar = new TarArchiveInputStream(new BZip2CompressorInputStream(counting))) {
                TarArchiveEntry e;
                byte[] buf = new byte[1 << 16];
                while ((e = tar.getNextTarEntry()) != null) {
                    if (cancelled[0]) throw new IOException("Anulowano");
                    String name = e.getName();
                    // Usuń katalog główny archiwum (np. "vits-piper-pl_PL-gosia-medium/")
                    int slash = name.indexOf('/');
                    String rel = slash >= 0 ? name.substring(slash + 1) : name;
                    if (rel.isEmpty()) continue;
                    // Pomijamy zbędne pliki (MODEL_CARD, przykłady audio)
                    if (rel.endsWith(".wav") || rel.endsWith(".mp3")) continue;
                    File out = new File(outDir, rel);
                    if (!out.getCanonicalPath().startsWith(canonicalOut)) continue; // ochrona przed ../
                    if (e.isDirectory()) {
                        //noinspection ResultOfMethodCallIgnored
                        out.mkdirs();
                        continue;
                    }
                    if (!e.isFile()) continue;
                    File parent = out.getParentFile();
                    //noinspection ResultOfMethodCallIgnored
                    if (parent != null) parent.mkdirs();
                    try (OutputStream os = new FileOutputStream(out)) {
                        int n;
                        while ((n = tar.read(buf)) > 0) os.write(buf, 0, n);
                    }
                }
            }
            notifyProgress(m.id, read[0], total);
        } finally {
            conn.disconnect();
        }
    }

    private static HttpURLConnection open(String url) throws IOException {
        // Ręczne podążanie za przekierowaniami (GitHub → serwer plików)
        for (int i = 0; i < 6; i++) {
            HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
            c.setInstanceFollowRedirects(false);
            c.setConnectTimeout(15000);
            c.setReadTimeout(30000);
            c.setRequestProperty("User-Agent", "CzytnikStrony");
            int code = c.getResponseCode();
            if (code >= 300 && code < 400) {
                String loc = c.getHeaderField("Location");
                c.disconnect();
                if (loc == null) throw new IOException("Błędne przekierowanie");
                url = new URL(new URL(url), loc).toString();
                continue;
            }
            return c;
        }
        throw new IOException("Za dużo przekierowań");
    }
}
