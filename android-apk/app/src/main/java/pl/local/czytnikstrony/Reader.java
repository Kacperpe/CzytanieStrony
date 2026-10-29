package pl.local.czytnikstrony;

import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.media.AudioAttributes;
import android.media.AudioFocusRequest;
import android.media.AudioManager;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.os.PowerManager;
import android.speech.tts.Voice;

import java.text.BreakIterator;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

/**
 * Jeden, wspólny dla całej aplikacji kontroler czytania. Trzyma tekst, podział na
 * fragmenty, stan odtwarzania i ustawienia. Ekran główny, panel z kafelka
 * i powiadomienie tylko nim sterują — dzięki temu czytanie trwa w tle.
 */
public final class Reader {

    public interface StateListener { void onReaderState(); }

    static final String VOICE_SYSTEM_AUTO = "system";
    static final String VOICE_SYSTEM_PREFIX = "system:";
    static final String VOICE_NEURAL_PREFIX = "piper:";

    private static final int MAX_CHUNK = 260;
    private static final int FIRST_CHUNK = 140;   // krótszy pierwszy fragment = szybszy start

    private static Reader instance;

    static synchronized Reader get(Context ctx) {
        if (instance == null) instance = new Reader(ctx.getApplicationContext());
        return instance;
    }

    private final Context app;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final SharedPreferences prefs;
    private final List<StateListener> listeners = new ArrayList<>();

    final SystemTtsEngine systemEngine;
    private final NeuralTtsEngine neuralEngine;
    private SpeechEngine activeEngine;

    // ── Stan ─────────────────────────────────────────────────────────────
    private String text = "";
    private String title = "";
    private List<String> chunks = Collections.emptyList();
    private final List<Integer> starts = new ArrayList<>();
    private final List<Integer> ends = new ArrayList<>();
    private int index = 0;
    private boolean playing = false;   // sesja aktywna i nie w pauzie
    private boolean paused = false;
    private boolean loading = false;
    private String status = "";
    private String activeLang = "pl";
    private boolean previewMode = false;
    private String url = "";
    private String articleId = null;
    private int version = 0;

    // ── Timer snu ────────────────────────────────────────────────────────
    private long sleepAt = 0;              // 0 = wyłączony
    private boolean sleepEndOfArticle = false;
    private final Runnable sleepRunnable = () -> {
        sleepAt = 0;
        if (playing) pause();
        setStatus("Timer snu — pauza");
    };

    // ── Ustawienia ───────────────────────────────────────────────────────
    private float rate;
    private String langMode;

    // ── Audio focus / wake lock ──────────────────────────────────────────
    private final AudioManager audio;
    private AudioFocusRequest focusRequest;
    private boolean resumeOnFocusGain = false;
    private PowerManager.WakeLock wakeLock;

    private Reader(Context app) {
        this.app = app;
        prefs = app.getSharedPreferences("reader", Context.MODE_PRIVATE);
        rate = prefs.getFloat("rate", 1.0f);
        langMode = prefs.getString("lang_mode", "auto");
        audio = (AudioManager) app.getSystemService(Context.AUDIO_SERVICE);
        systemEngine = new SystemTtsEngine(app);
        neuralEngine = new NeuralTtsEngine(app);
        systemEngine.whenReady(() -> {
            if (status.isEmpty() || status.startsWith("Startuj")) setStatus(systemEngine.isReady() ? "Gotowy" : "Systemowy TTS niedostępny");
        });
        status = "Startuję…";

        // Przywróć ostatni tekst (bez odtwarzania)
        String last = prefs.getString("last_text", "");
        if (!last.isEmpty()) {
            text = last;
            title = prefs.getString("last_title", "");
            url = prefs.getString("last_url", "");
            articleId = prefs.getString("last_article", null);
            rebuildChunks(resolveLang(text));
            index = Math.max(0, Math.min(prefs.getInt("last_index", 0), Math.max(0, chunks.size() - 1)));
        }
    }

    // ── Słuchacze ────────────────────────────────────────────────────────

    void addListener(StateListener l)    { if (!listeners.contains(l)) listeners.add(l); }
    void removeListener(StateListener l) { listeners.remove(l); }

    private void notifyState() {
        version++;
        for (StateListener l : new ArrayList<>(listeners)) l.onReaderState();
        ReaderWidget.update(app);
    }

    /** Licznik zmian stanu (do odświeżania UI w Compose). */
    int version() { return version; }

    private void setStatus(String s) { status = s; notifyState(); }

    // ── Gettery ──────────────────────────────────────────────────────────

    String getText()        { return text; }
    String getTitle()       { return title; }
    String getUrl()         { return url; }
    String getDomain()      { return Library.domainOf(url); }
    String getArticleId()   { return articleId; }
    String chunk(int i)     { return i >= 0 && i < chunks.size() ? chunks.get(i) : ""; }

    // ── Czas (szacowany: ~15 znaków/s przy 1.0x) ─────────────────────────
    private static final float CHARS_PER_SEC = 15f;

    int durationSeconds() {
        if (chunks.isEmpty()) return 0;
        return Math.round(charsBefore(chunks.size()) / (CHARS_PER_SEC * rate));
    }

    int positionSeconds() { return Math.round(charsBefore(index) / (CHARS_PER_SEC * rate)); }

    private int charsBefore(int i) {
        int c = 0;
        for (int k = 0; k < Math.min(i, chunks.size()); k++) c += chunks.get(k).length() + 1;
        return c;
    }

    /** Przewija o deltaSec sekund (±15 s itp.). */
    void seekSeconds(int deltaSec) {
        if (chunks.isEmpty()) return;
        int target = charsBefore(index) + Math.round(deltaSec * CHARS_PER_SEC * rate);
        int i = 0, c = 0;
        while (i < chunks.size() - 1 && c + chunks.get(i).length() + 1 <= target) { c += chunks.get(i).length() + 1; i++; }
        if (deltaSec < 0 && i == index && index > 0) i = index - 1;
        if (deltaSec > 0 && i == index && index < chunks.size() - 1) i = index + 1;
        seek(i);
    }

    /** Ułamek 0..1 → fragment. */
    void seekFraction(float f) {
        if (chunks.isEmpty()) return;
        int target = Math.round(f * charsBefore(chunks.size()));
        int i = 0, c = 0;
        while (i < chunks.size() - 1 && c + chunks.get(i).length() + 1 <= target) { c += chunks.get(i).length() + 1; i++; }
        seek(i);
    }

    // ── Timer snu ────────────────────────────────────────────────────────

    /** minutes > 0 = za tyle minut; 0 = wyłącz; -1 = koniec artykułu. */
    void setSleepTimer(int minutes) {
        main.removeCallbacks(sleepRunnable);
        sleepAt = 0;
        sleepEndOfArticle = false;
        if (minutes > 0) {
            sleepAt = System.currentTimeMillis() + minutes * 60_000L;
            main.postDelayed(sleepRunnable, minutes * 60_000L);
        } else if (minutes < 0) {
            sleepEndOfArticle = true;
        }
        notifyState();
    }

    boolean isSleepEndOfArticle() { return sleepEndOfArticle; }

    /** Pozostałe sekundy timera (0 = wyłączony). */
    int sleepRemainingSeconds() {
        if (sleepAt == 0) return 0;
        return (int) Math.max(0, (sleepAt - System.currentTimeMillis()) / 1000);
    }
    String getStatus()      { return status; }
    int getIndex()          { return index; }
    int getChunkCount()     { return chunks.size(); }
    boolean isPlaying()     { return playing; }
    boolean isPaused()      { return paused; }
    boolean isLoading()     { return loading; }
    boolean isActive()      { return playing || paused; }
    boolean hasContent()    { return !chunks.isEmpty(); }
    float getRate()         { return rate; }
    String getLangMode()    { return langMode; }
    String getActiveLang()  { return activeLang; }

    int chunkStart(int i) { return i >= 0 && i < starts.size() ? starts.get(i) : -1; }
    int chunkEnd(int i)   { return i >= 0 && i < ends.size() ? ends.get(i) : -1; }

    String currentChunkPreview() {
        if (index < 0 || index >= chunks.size()) return "";
        String c = chunks.get(index);
        return c.length() <= 110 ? c : c.substring(0, 110).trim() + "…";
    }

    String displayTitle() {
        if (!title.isEmpty()) return title;
        String t = text.replace('\n', ' ').trim();
        return t.length() <= 60 ? t : t.substring(0, 60).trim() + "…";
    }

    /** Szacowany pozostały czas w sekundach (~15 znaków/s przy 1.0x). */
    int remainingSeconds() {
        if (chunks.isEmpty()) return 0;
        int chars = 0;
        for (int i = index; i < chunks.size(); i++) chars += chunks.get(i).length();
        return Math.round(chars / (15f * rate));
    }

    // ── Ustawienia ───────────────────────────────────────────────────────

    void setRate(float r) {
        rate = Math.max(0.5f, Math.min(2.5f, r));
        prefs.edit().putFloat("rate", rate).apply();
        if (playing) restartAt(index); else notifyState();
    }

    void setLangMode(String mode) {
        langMode = mode;
        prefs.edit().putString("lang_mode", mode).apply();
        if (!text.isEmpty() && !isActive()) rebuildChunks(resolveLang(text));
        if (playing) { stopEngine(); startAt(index, resolveLang(text)); } else notifyState();
    }

    /** Wybrany głos dla języka: "system", "system:<nazwa>" albo "piper:<id>". */
    String getVoice(String lang) {
        String v = prefs.getString("voice_" + lang, VOICE_SYSTEM_AUTO);
        if (v.startsWith(VOICE_NEURAL_PREFIX)
            && !VoiceCatalog.isInstalled(app, v.substring(VOICE_NEURAL_PREFIX.length()))) {
            return VOICE_SYSTEM_AUTO;
        }
        return v;
    }

    void setVoice(String lang, String spec) {
        prefs.edit().putString("voice_" + lang, spec).apply();
        if (playing && lang.equals(activeLang) && !previewMode) restartAt(index); else notifyState();
    }

    String voiceLabel(String lang) {
        String v = getVoice(lang);
        if (v.startsWith(VOICE_NEURAL_PREFIX)) {
            VoiceCatalog.Model m = VoiceCatalog.find(app, v.substring(VOICE_NEURAL_PREFIX.length()));
            return m != null ? m.name + " · naturalny" : "Naturalny";
        }
        if (v.startsWith(VOICE_SYSTEM_PREFIX)) return prettyVoiceName(v.substring(VOICE_SYSTEM_PREFIX.length())) + " · systemowy";
        return "Systemowy (automatycznie)";
    }

    static String prettyVoiceName(String raw) {
        // pl-pl-x-oda-local → oda (lokalny)
        String n = raw;
        String suffix = "";
        if (n.endsWith("-local"))   { n = n.substring(0, n.length() - 6); suffix = " (offline)"; }
        if (n.endsWith("-network")) { n = n.substring(0, n.length() - 8); suffix = " (sieć)"; }
        int x = n.indexOf("-x-");
        if (x >= 0) n = n.substring(x + 3);
        if (n.isEmpty()) n = raw;
        return Character.toUpperCase(n.charAt(0)) + n.substring(1) + suffix;
    }

    // ── Wczytanie tekstu i czytanie ──────────────────────────────────────

    /** Czyta nowy tekst od znaku startOffset (0 = od początku). */
    void play(String rawText, String newTitle, int startOffset) {
        play(rawText, newTitle, "", startOffset);
    }

    /** Czyta tekst i zapisuje go w bibliotece (historia, postęp). */
    void play(String rawText, String newTitle, String newUrl, int startOffset) {
        String clean = cleanText(rawText);
        if (clean.isEmpty()) { setStatus("Brak tekstu do czytania."); return; }
        Library.Article a = Library.get(app).upsert(newTitle, newUrl, clean, false);
        load(a.id, clean, newTitle, newUrl);
        index = indexForOffset(startOffset);
        Library.get(app).markReading(a.id, chunks.size());
        persistText();
        startAt(index, activeLang);
    }

    /** Odtwarza artykuł z biblioteki od zapisanego miejsca. */
    boolean playArticle(String id) {
        Library lib = Library.get(app);
        Library.Article a = lib.get(id);
        String t = a != null ? lib.text(id) : null;
        if (t == null) return false;
        if (id.equals(articleId) && hasContent()) {
            if (!isActive()) startAt(index >= chunks.size() ? 0 : index, activeLang);
            else if (paused) resume();
            return true;
        }
        load(id, t, a.title, a.url);
        index = a.finished ? 0 : Math.max(0, Math.min(a.index, chunks.size() - 1));
        lib.markReading(id, chunks.size());
        persistText();
        startAt(index, activeLang);
        return true;
    }

    private void load(String id, String clean, String newTitle, String newUrl) {
        stopEngine();
        previewMode = false;
        articleId = id;
        text = clean;
        title = newTitle != null ? newTitle.trim() : "";
        url = newUrl != null ? newUrl : "";
        rebuildChunks(resolveLang(text));
    }

    /** Wznawia lub zaczyna czytanie aktualnego tekstu. */
    void togglePlayPause() {
        if (playing) pause();
        else if (paused) resume();
        else if (!chunks.isEmpty()) startAt(index >= chunks.size() ? 0 : index, resolveLang(text));
        else setStatus("Wklej tekst albo adres strony.");
    }

    void pause() {
        if (!playing || activeEngine == null) return;
        activeEngine.pause();
        playing = false;
        paused = true;
        releaseWakeLock();
        setStatus("Pauza");
        persistIndex();
    }

    void resume() {
        if (!paused || activeEngine == null) { togglePlayPause(); return; }
        if (!requestFocus()) { setStatus("Inna aplikacja zajmuje dźwięk."); return; }
        paused = false;
        playing = true;
        acquireWakeLock();
        if (activeEngine.canResumeInPlace()) activeEngine.resume();
        else activeEngine.start(chunks, index, rate, engineListener());
        setStatus(statusReading());
        ensureService();
    }

    void stop() {
        stopEngine();
        playing = false;
        paused = false;
        loading = false;
        previewMode = false;
        abandonFocus();
        releaseWakeLock();
        persistIndex();
        setStatus("Zatrzymano");
    }

    void next() { seek(index + 1); }
    void prev() { seek(index - 1); }

    void seek(int i) {
        if (chunks.isEmpty()) return;
        int target = Math.max(0, Math.min(chunks.size() - 1, i));
        if (playing) restartAt(target);
        else {
            if (paused) { stopEngine(); paused = false; }
            index = target;
            persistIndex();
            notifyState();
        }
    }

    void seekToOffset(int charOffset) { seek(indexForOffset(charOffset)); }

    /** Odsłuch próbki danego głosu bez zmiany ustawień. */
    void preview(String spec, String lang) {
        stopEngine();
        previewMode = true;
        String sample = "pl".equals(lang)
            ? "Dzień dobry! Tak brzmi ten głos podczas czytania artykułu."
            : "Hello! This is how this voice sounds when reading an article.";
        List<String> one = Collections.singletonList(sample);
        SpeechEngine e = engineFor(spec, lang);
        activeEngine = e;
        playing = true;
        paused = false;
        requestFocus();
        e.start(one, 0, rate, new SpeechEngine.Listener() {
            @Override public void onChunkStart(int i) { loading = false; notifyState(); }
            @Override public void onFinished()        { endPreview(); }
            @Override public void onError(String m)   { endPreview(); setStatus(m); }
            @Override public void onStatus(String m)  { loading = true; setStatus(m); }
        });
        notifyState();
    }

    private void endPreview() {
        previewMode = false;
        playing = false;
        paused = false;
        loading = false;
        activeEngine = null;
        abandonFocus();
        notifyState();
    }

    boolean isPreviewing() { return previewMode; }

    // ── Wewnętrzne ───────────────────────────────────────────────────────

    private void restartAt(int i) {
        stopEngine();
        startAt(i, activeLang);
    }

    private void startAt(int i, String lang) {
        if (chunks.isEmpty()) return;
        if (!requestFocus()) { setStatus("Inna aplikacja zajmuje dźwięk."); return; }
        index = Math.max(0, Math.min(i, chunks.size() - 1));
        activeLang = lang;
        activeEngine = engineFor(getVoice(lang), lang);
        playing = true;
        paused = false;
        loading = true;
        acquireWakeLock();
        ensureService();
        setStatus(statusReading());
        activeEngine.start(chunks, index, rate, engineListener());
    }

    private SpeechEngine.Listener engineListener() {
        final SpeechEngine engine = activeEngine;
        return new SpeechEngine.Listener() {
            @Override public void onChunkStart(int i) {
                if (engine != activeEngine) return;
                loading = false;
                index = i;
                if (i % 3 == 0) persistIndex();
                setStatus(statusReading());
            }
            @Override public void onFinished() {
                if (engine != activeEngine) return;
                playing = false;
                paused = false;
                loading = false;
                activeEngine = null;
                if (articleId != null) Library.get(app).markFinished(articleId);
                index = 0;
                persistIndex();
                if (sleepEndOfArticle) {
                    sleepEndOfArticle = false;
                    abandonFocus();
                    releaseWakeLock();
                    setStatus("Timer snu — koniec artykułu");
                    return;
                }
                Library.Article next = Library.get(app).nextQueued();
                if (next != null && playArticle(next.id)) {
                    setStatus("Następny z kolejki");
                    return;
                }
                abandonFocus();
                releaseWakeLock();
                setStatus("Koniec czytania");
            }
            @Override public void onError(String m) {
                if (engine != activeEngine) return;
                playing = false;
                paused = false;
                loading = false;
                activeEngine = null;
                abandonFocus();
                releaseWakeLock();
                setStatus(m);
            }
            @Override public void onStatus(String m) {
                if (engine != activeEngine) return;
                setStatus(m);
            }
        };
    }

    private String statusReading() {
        String lang = "pl".equals(activeLang) ? "PL" : "EN";
        return "Czytam · " + lang + " · " + String.format(Locale.US, "%.2fx", rate);
    }

    private SpeechEngine engineFor(String spec, String lang) {
        Locale loc = "en".equals(lang) ? Locale.US : new Locale("pl", "PL");
        if (spec.startsWith(VOICE_NEURAL_PREFIX)) {
            neuralEngine.setModel(spec.substring(VOICE_NEURAL_PREFIX.length()));
            return neuralEngine;
        }
        Voice v = spec.startsWith(VOICE_SYSTEM_PREFIX)
            ? systemEngine.findVoice(spec.substring(VOICE_SYSTEM_PREFIX.length())) : null;
        systemEngine.configure(v, loc);
        return systemEngine;
    }

    private void stopEngine() {
        if (activeEngine != null) activeEngine.stop();
        activeEngine = null;
        loading = false;
    }

    private void ensureService() {
        Intent i = new Intent(app, PlayerService.class);
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) app.startForegroundService(i);
            else app.startService(i);
        } catch (Exception ignored) {
            // np. start z tła na Androidzie 12+ — powiadomienie pojawi się przy następnej akcji z UI
        }
    }

    // ── Język ────────────────────────────────────────────────────────────

    private String resolveLang(String t) {
        if ("pl".equals(langMode) || "en".equals(langMode)) return langMode;
        return detectLang(t);
    }

    static String detectLang(String text) {
        String sample = " " + text.substring(0, Math.min(6000, text.length())).toLowerCase(Locale.ROOT) + " ";
        int pl = 0, en = 0;
        for (String m : new String[]{" że ", " nie ", " jest ", " się ", " na ", " do ", " oraz ", " który ", " to ", " w "})
            pl += countOf(sample, m);
        for (String m : new String[]{" the ", " and ", " is ", " are ", " with ", " from ", " that ", " this ", " you ", " of "})
            en += countOf(sample, m);
        for (char c : "ąćęłńóśźż".toCharArray()) pl += countOf(sample, String.valueOf(c));
        return en > pl ? "en" : "pl";
    }

    private static int countOf(String s, String m) {
        int c = 0, i = 0;
        while ((i = s.indexOf(m, i)) >= 0) { c++; i += m.length(); }
        return c;
    }

    // ── Tekst i podział na fragmenty ─────────────────────────────────────

    static String cleanText(String raw) {
        if (raw == null) return "";
        String t = raw.replace("\r\n", "\n").replace('\r', '\n').replace(' ', ' ');
        t = t.replaceAll("[ \\t\\x0B\\f]+", " ");
        t = t.replaceAll(" *\\n *", "\n");
        t = t.replaceAll("\\n{3,}", "\n\n");
        return t.trim();
    }

    private void rebuildChunks(String lang) {
        activeLang = lang;
        List<String> out = new ArrayList<>();
        starts.clear();
        ends.clear();
        Locale loc = "en".equals(lang) ? Locale.US : new Locale("pl", "PL");
        BreakIterator it = BreakIterator.getSentenceInstance(loc);

        // Dziel po akapitach, a w akapicie po zdaniach
        int pos = 0;
        while (pos < text.length()) {
            int nl = text.indexOf('\n', pos);
            int paraEnd = nl < 0 ? text.length() : nl;
            if (paraEnd > pos) splitParagraph(pos, paraEnd, it, out);
            pos = paraEnd + 1;
        }
        chunks = out;
    }

    private void splitParagraph(int from, int to, BreakIterator it, List<String> out) {
        it.setText(text.substring(from, to));
        int curStart = -1, curEnd = -1;
        int s = it.first();
        for (int e = it.next(); e != BreakIterator.DONE; s = e, e = it.next()) {
            int a = from + s, b = from + e;
            // Za długie zdanie — tnij po przecinkach / spacjach
            for (int[] piece : hardSplit(a, b)) {
                int limit = out.isEmpty() ? FIRST_CHUNK : MAX_CHUNK;
                if (curStart >= 0 && piece[1] - curStart > limit) {
                    addChunk(curStart, curEnd, out);
                    curStart = -1;
                }
                if (curStart < 0) curStart = piece[0];
                curEnd = piece[1];
            }
        }
        if (curStart >= 0) addChunk(curStart, curEnd, out);
    }

    private List<int[]> hardSplit(int a, int b) {
        List<int[]> parts = new ArrayList<>();
        while (b - a > MAX_CHUNK) {
            int cut = -1;
            for (int k = a + MAX_CHUNK; k > a + MAX_CHUNK / 2; k--) {
                char c = text.charAt(k);
                if (c == ',' || c == ';' || c == ':' || c == '—' || c == '–') { cut = k + 1; break; }
            }
            if (cut < 0) {
                for (int k = a + MAX_CHUNK; k > a + MAX_CHUNK / 2; k--) {
                    if (text.charAt(k) == ' ') { cut = k + 1; break; }
                }
            }
            if (cut < 0) cut = a + MAX_CHUNK;
            parts.add(new int[]{a, cut});
            a = cut;
        }
        parts.add(new int[]{a, b});
        return parts;
    }

    private void addChunk(int a, int b, List<String> out) {
        String c = text.substring(a, b).replace('\n', ' ').trim();
        if (c.isEmpty()) return;
        // obetnij białe znaki z zakresu podświetlenia
        while (a < b && Character.isWhitespace(text.charAt(a))) a++;
        while (b > a && Character.isWhitespace(text.charAt(b - 1))) b--;
        out.add(c);
        starts.add(a);
        ends.add(b);
    }

    private int indexForOffset(int offset) {
        if (offset <= 0 || starts.isEmpty()) return 0;
        for (int i = 0; i < ends.size(); i++) if (ends.get(i) > offset) return i;
        return ends.size() - 1;
    }

    // ── Trwałość ─────────────────────────────────────────────────────────

    private void persistText() {
        String keep = text.length() > 300_000 ? "" : text;
        prefs.edit().putString("last_text", keep).putString("last_title", title).putInt("last_index", index)
            .putString("last_url", url).putString("last_article", articleId).apply();
    }

    private void persistIndex() {
        prefs.edit().putInt("last_index", index).apply();
        if (articleId != null && !previewMode) Library.get(app).setProgress(articleId, index, chunks.size());
    }

    /** Czyści zapamiętany tekst (przycisk „Wyczyść”). */
    void clear() {
        stop();
        text = "";
        title = "";
        url = "";
        articleId = null;
        chunks = Collections.emptyList();
        starts.clear();
        ends.clear();
        index = 0;
        prefs.edit().remove("last_text").remove("last_title").remove("last_index")
            .remove("last_url").remove("last_article").apply();
        setStatus("Gotowy");
    }

    // ── Audio focus ──────────────────────────────────────────────────────

    private final AudioManager.OnAudioFocusChangeListener focusListener = change -> main.post(() -> {
        switch (change) {
            case AudioManager.AUDIOFOCUS_LOSS:
                resumeOnFocusGain = false;
                pause();
                break;
            case AudioManager.AUDIOFOCUS_LOSS_TRANSIENT:
                if (playing) { resumeOnFocusGain = true; pause(); }
                break;
            case AudioManager.AUDIOFOCUS_GAIN:
                if (resumeOnFocusGain && paused) { resumeOnFocusGain = false; resume(); }
                break;
            default: break;
        }
    });

    private boolean requestFocus() {
        if (audio == null) return true;
        int res;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            if (focusRequest == null) {
                focusRequest = new AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
                    .setAudioAttributes(new AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                        .build())
                    .setOnAudioFocusChangeListener(focusListener, main)
                    .build();
            }
            res = audio.requestAudioFocus(focusRequest);
        } else {
            res = audio.requestAudioFocus(focusListener, AudioManager.STREAM_MUSIC, AudioManager.AUDIOFOCUS_GAIN);
        }
        return res != AudioManager.AUDIOFOCUS_REQUEST_FAILED;
    }

    private void abandonFocus() {
        if (audio == null) return;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            if (focusRequest != null) audio.abandonAudioFocusRequest(focusRequest);
        } else {
            audio.abandonAudioFocus(focusListener);
        }
    }

    // ── Wake lock (synteza w tle przy zgaszonym ekranie) ─────────────────

    private void acquireWakeLock() {
        try {
            if (wakeLock == null) {
                PowerManager pm = (PowerManager) app.getSystemService(Context.POWER_SERVICE);
                wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "czytnik:reading");
                wakeLock.setReferenceCounted(false);
            }
            wakeLock.acquire(3 * 60 * 60 * 1000L);
        } catch (Exception ignored) {}
    }

    private void releaseWakeLock() {
        try { if (wakeLock != null && wakeLock.isHeld()) wakeLock.release(); } catch (Exception ignored) {}
    }
}
