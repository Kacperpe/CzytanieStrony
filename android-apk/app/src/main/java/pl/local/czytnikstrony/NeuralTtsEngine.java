package pl.local.czytnikstrony;

import android.content.Context;
import android.media.AudioAttributes;
import android.media.AudioFormat;
import android.media.AudioManager;
import android.media.AudioTrack;
import android.os.Handler;
import android.os.Looper;

import com.k2fsa.sherpa.onnx.OfflineTts;
import com.k2fsa.sherpa.onnx.OfflineTtsConfig;
import com.k2fsa.sherpa.onnx.OfflineTtsModelConfig;
import com.k2fsa.sherpa.onnx.OfflineTtsVitsModelConfig;

import java.io.File;

import kotlin.jvm.functions.Function1;
import java.util.List;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.TimeUnit;

/**
 * Naturalny głos neuronowy (Piper przez sherpa-onnx), w pełni offline.
 *
 * Dwa wątki na sesję: generator syntezuje fragmenty z wyprzedzeniem (strumieniowo,
 * zdanie po zdaniu), odtwarzacz zapisuje próbki do AudioTrack. Dzięki temu
 * pierwsze słowa słychać szybko, a między fragmentami nie ma przerw.
 */
final class NeuralTtsEngine implements SpeechEngine {

    /** Element kolejki audio. samples == null oznacza koniec fragmentu. */
    private static final class Segment {
        final int chunk; final float[] samples; final boolean endOfAll;
        Segment(int chunk, float[] samples, boolean endOfAll) {
            this.chunk = chunk; this.samples = samples; this.endOfAll = endOfAll;
        }
    }

    private final Context ctx;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final Object lock = new Object();

    private OfflineTts tts;          // wołane tylko z wątku generatora
    private String loadedId = null;
    private String modelId;

    private Session session;
    private volatile boolean paused;

    NeuralTtsEngine(Context ctx) { this.ctx = ctx.getApplicationContext(); }

    void setModel(String id) { this.modelId = id; }
    String getModel()        { return modelId; }

    // ── SpeechEngine ─────────────────────────────────────────────────────

    @Override
    public void start(List<String> chunks, int startIndex, float rate, Listener listener) {
        stop();
        paused = false;
        Session s = new Session(chunks, startIndex, rate, listener, modelId);
        session = s;
        s.begin();
    }

    @Override
    public void pause() {
        paused = true;
        Session s = session;
        if (s != null) s.pauseTrack();
    }

    @Override
    public void resume() {
        paused = false;
        Session s = session;
        if (s != null) s.resumeTrack();
    }

    @Override
    public boolean canResumeInPlace() { return true; }

    @Override
    public void stop() {
        Session s = session;
        session = null;
        if (s != null) s.cancel();
    }

    @Override
    public void release() {
        stop();
        new Thread(() -> {
            synchronized (lock) {
                if (tts != null) { try { tts.release(); } catch (Throwable ignored) {} }
                tts = null;
                loadedId = null;
            }
        }, "tts-release").start();
    }

    // ── Ładowanie modelu (wątek generatora, pod lock) ────────────────────

    private OfflineTts ensureModel(String id) {
        if (id == null) throw new IllegalStateException("Nie wybrano głosu");
        if (tts != null && id.equals(loadedId)) return tts;
        if (tts != null) { try { tts.release(); } catch (Throwable ignored) {} tts = null; loadedId = null; }

        File model = VoiceCatalog.modelFile(ctx, id);
        File tokens = new File(VoiceCatalog.dir(ctx, id), "tokens.txt");
        File espeak = VoiceCatalog.espeakDataFor(ctx, id);
        if (model == null || !tokens.exists()) throw new IllegalStateException("Głos nie jest pobrany");
        if (espeak == null) throw new IllegalStateException("Brak plików wymowy — pobierz dowolny głos z listy");

        OfflineTtsVitsModelConfig vits = new OfflineTtsVitsModelConfig();
        vits.setModel(model.getAbsolutePath());
        vits.setTokens(tokens.getAbsolutePath());
        vits.setDataDir(espeak.getAbsolutePath());

        OfflineTtsModelConfig mc = new OfflineTtsModelConfig();
        mc.setVits(vits);
        int cores = Runtime.getRuntime().availableProcessors();
        mc.setNumThreads(Math.max(1, Math.min(4, cores / 2)));
        mc.setDebug(false);
        mc.setProvider("cpu");

        OfflineTtsConfig cfg = new OfflineTtsConfig();
        cfg.setModel(mc);
        cfg.setMaxNumSentences(1);

        tts = new OfflineTts(null, cfg);
        loadedId = id;
        return tts;
    }

    // ── Callback z próbkami ──────────────────────────────────────────────

    /**
     * Callback wołany z C++ po każdym zdaniu. MUSI być nazwaną klasą z metodą
     * {@code Integer invoke(float[])}: JNI sherpa-onnx szuka dokładnie sygnatury
     * ([F)Ljava/lang/Integer; — lambda Javy ma tylko invoke(Object) i kończy się
     * natywnym crashem całej aplikacji.
     */
    private static final class SamplesCallback implements Function1<float[], Integer> {
        private final Session session;
        private final int chunk;

        SamplesCallback(Session session, int chunk) { this.session = session; this.chunk = chunk; }

        @Override
        public Integer invoke(float[] samples) {
            try {
                if (session.cancelled || samples == null) return 0;
                Segment seg = new Segment(chunk, samples.clone(), false);
                while (!session.cancelled && !session.queue.offer(seg, 200, TimeUnit.MILLISECONDS)) { /* czekaj */ }
                return session.cancelled ? 0 : 1;
            } catch (Throwable e) {
                return 0;
            }
        }
    }

    // ── Sesja ────────────────────────────────────────────────────────────

    private final class Session {
        final List<String> chunks;
        final int startIndex;
        final float rate;
        final Listener listener;
        final String id;
        final BlockingQueue<Segment> queue = new ArrayBlockingQueue<>(48);
        volatile boolean cancelled = false;
        volatile AudioTrack track;
        Thread generator, player;

        Session(List<String> chunks, int startIndex, float rate, Listener listener, String id) {
            this.chunks = chunks; this.startIndex = startIndex; this.rate = rate;
            this.listener = listener; this.id = id;
        }

        void post(Runnable r) {
            main.post(() -> { if (!cancelled && session == this) r.run(); });
        }

        void begin() {
            generator = new Thread(this::generate, "tts-gen");
            generator.start();
        }

        void cancel() {
            cancelled = true;
            AudioTrack t = track;
            if (t != null) {
                try { t.pause(); t.flush(); } catch (Exception ignored) {}
            }
            queue.clear();
            if (player != null) player.interrupt();
            // generator przerwie się sam przez callback zwracający 0
        }

        void pauseTrack()  { AudioTrack t = track; if (t != null) try { t.pause(); } catch (Exception ignored) {} }
        void resumeTrack() { AudioTrack t = track; if (t != null) try { t.play(); } catch (Exception ignored) {} }

        // ── Generator ──
        private void generate() {
            try {
                generateLocked();
            } catch (Throwable e) {
                String msg = e.getMessage() != null ? e.getMessage() : e.toString();
                post(() -> listener.onError("Błąd głosu: " + msg));
            }
        }

        private void generateLocked() {
            synchronized (lock) {
                OfflineTts engine;
                try {
                    if (!id.equals(loadedId)) post(() -> listener.onStatus("Ładuję głos…"));
                    engine = ensureModel(id);
                } catch (Throwable e) {
                    String msg = e.getMessage() != null ? e.getMessage() : e.toString();
                    post(() -> listener.onError("Nie udało się wczytać głosu: " + msg));
                    return;
                }
                if (cancelled) return;
                int sampleRate = engine.sampleRate();
                if (!startPlayer(sampleRate)) {
                    post(() -> listener.onError("Nie udało się uruchomić odtwarzania."));
                    return;
                }
                for (int i = startIndex; i < chunks.size() && !cancelled; i++) {
                    final int idx = i;
                    String text = chunks.get(i);
                    try {
                        engine.generateWithCallback(text, 0, rate, new SamplesCallback(this, idx));
                    } catch (Throwable e) {
                        String msg = e.getMessage() != null ? e.getMessage() : e.toString();
                        post(() -> listener.onError("Błąd syntezy: " + msg));
                        cancelled = true;
                        return;
                    }
                    // znacznik końca fragmentu (pozwala zgłosić fragment nawet przy pustym audio)
                    putQuiet(new Segment(idx, null, false));
                }
                if (!cancelled) putQuiet(new Segment(-1, null, true));
            }
        }

        private void putQuiet(Segment s) {
            try {
                while (!cancelled && !queue.offer(s, 200, TimeUnit.MILLISECONDS)) { /* czekaj */ }
            } catch (InterruptedException ignored) {}
        }

        private boolean startPlayer(int sampleRate) {
            try {
                int min = AudioTrack.getMinBufferSize(sampleRate, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_FLOAT);
                AudioTrack t = new AudioTrack(
                    new AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                        .build(),
                    new AudioFormat.Builder()
                        .setEncoding(AudioFormat.ENCODING_PCM_FLOAT)
                        .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                        .setSampleRate(sampleRate)
                        .build(),
                    Math.max(min * 2, sampleRate / 2 * 4),
                    AudioTrack.MODE_STREAM,
                    AudioManager.AUDIO_SESSION_ID_GENERATE);
                track = t;
                if (!paused) t.play();
                player = new Thread(() -> play(t), "tts-play");
                player.start();
                return true;
            } catch (Exception e) {
                return false;
            }
        }

        // ── Odtwarzacz ──
        private void play(AudioTrack t) {
            int lastChunk = -1;
            long written = 0;
            try {
                while (!cancelled) {
                    Segment s = queue.poll(200, TimeUnit.MILLISECONDS);
                    if (s == null) continue;
                    if (s.endOfAll) {
                        // poczekaj aż bufor się odegra
                        long deadline = System.currentTimeMillis() + 5000;
                        while (!cancelled && t.getPlaybackHeadPosition() < written
                            && (paused || System.currentTimeMillis() < deadline)) {
                            Thread.sleep(50);
                            if (paused) deadline = System.currentTimeMillis() + 5000;
                        }
                        if (!cancelled) post(listener::onFinished);
                        break;
                    }
                    if (s.chunk != lastChunk) {
                        lastChunk = s.chunk;
                        final int c = s.chunk;
                        post(() -> listener.onChunkStart(c));
                    }
                    if (s.samples == null) continue;
                    int off = 0;
                    while (off < s.samples.length && !cancelled) {
                        int n = Math.min(4096, s.samples.length - off);
                        int w = t.write(s.samples, off, n, AudioTrack.WRITE_BLOCKING);
                        if (w <= 0) { if (w < 0) break; Thread.sleep(20); continue; }
                        off += w;
                        written += w;
                    }
                }
            } catch (InterruptedException ignored) {
            } catch (Throwable e) {
                String msg = e.getMessage() != null ? e.getMessage() : e.toString();
                post(() -> listener.onError("Błąd odtwarzania: " + msg));
            } finally {
                try { t.stop(); } catch (Exception ignored) {}
                try { t.release(); } catch (Throwable ignored) {}
                if (track == t) track = null;
            }
        }
    }
}
