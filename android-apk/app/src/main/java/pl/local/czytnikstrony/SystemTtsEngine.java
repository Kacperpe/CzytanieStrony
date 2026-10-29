package pl.local.czytnikstrony;

import android.content.Context;
import android.media.AudioAttributes;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.speech.tts.TextToSpeech;
import android.speech.tts.UtteranceProgressListener;
import android.speech.tts.Voice;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Silnik oparty o systemowy TextToSpeech. Kolejkuje kilka fragmentów naprzód
 * (QUEUE_ADD), dzięki czemu między zdaniami nie ma przerw.
 */
final class SystemTtsEngine implements SpeechEngine {

    private static final int LOOKAHEAD = 2;

    private final Handler main = new Handler(Looper.getMainLooper());
    private final TextToSpeech tts;
    private boolean ready = false;
    private boolean failed = false;
    private final List<Runnable> onReady = new ArrayList<>();

    private Voice  voice;
    private Locale locale = new Locale("pl", "PL");

    private List<String> chunks = Collections.emptyList();
    private Listener listener;
    private float rate = 1f;
    private int session = 0;
    private int currentIndex = 0;
    private int queuedUpTo = -1;

    SystemTtsEngine(Context ctx) {
        tts = new TextToSpeech(ctx.getApplicationContext(), status -> main.post(() -> {
            ready = status == TextToSpeech.SUCCESS;
            failed = !ready;
            if (ready) {
                tts.setAudioAttributes(new AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build());
                tts.setOnUtteranceProgressListener(progressListener);
            }
            List<Runnable> copy = new ArrayList<>(onReady);
            onReady.clear();
            for (Runnable r : copy) r.run();
        }));
    }

    // ── Głosy ────────────────────────────────────────────────────────────

    boolean isReady()  { return ready; }
    boolean isFailed() { return failed; }

    /** Uruchamia akcję po inicjalizacji TTS (od razu, jeśli już gotowy). */
    void whenReady(Runnable r) {
        if (ready || failed) r.run(); else onReady.add(r);
    }

    /** Głosy systemowe dla języka ("pl" / "en"), najlepsze jakościowo na początku. */
    List<Voice> voicesFor(String lang) {
        List<Voice> out = new ArrayList<>();
        if (!ready) return out;
        Set<Voice> all;
        try { all = tts.getVoices(); } catch (Exception e) { all = null; }
        if (all == null) return out;
        for (Voice v : all) {
            if (v.getLocale() == null) continue;
            if (!lang.equalsIgnoreCase(v.getLocale().getLanguage())) continue;
            Set<String> f = v.getFeatures();
            if (f != null && f.contains(TextToSpeech.Engine.KEY_FEATURE_NOT_INSTALLED)) continue;
            out.add(v);
        }
        out.sort((a, b) -> {
            int q = Integer.compare(b.getQuality(), a.getQuality());
            if (q != 0) return q;
            int n = Boolean.compare(a.isNetworkConnectionRequired(), b.isNetworkConnectionRequired());
            if (n != 0) return n;
            return a.getName().compareTo(b.getName());
        });
        return out;
    }

    Voice findVoice(String name) {
        if (!ready || name == null || name.isEmpty()) return null;
        try {
            for (Voice v : tts.getVoices()) if (v.getName().equals(name)) return v;
        } catch (Exception ignored) {}
        return null;
    }

    /** Ustawia głos (lub null = najlepszy dla locale). */
    void configure(Voice v, Locale loc) {
        voice = v;
        locale = loc != null ? loc : locale;
    }

    // ── SpeechEngine ─────────────────────────────────────────────────────

    @Override
    public void start(List<String> chunks, int startIndex, float rate, Listener listener) {
        this.chunks = chunks;
        this.listener = listener;
        this.rate = rate;
        final int mySession = ++session;
        whenReady(() -> {
            if (mySession != session) return;
            if (!ready) { listener.onError("Systemowy TTS niedostępny na tym urządzeniu."); return; }
            applyVoice();
            tts.setSpeechRate(this.rate);
            currentIndex = startIndex;
            queuedUpTo = startIndex - 1;
            if (startIndex >= chunks.size()) { listener.onFinished(); return; }
            enqueueUpTo(startIndex + LOOKAHEAD, true);
        });
    }

    private void applyVoice() {
        int res = TextToSpeech.ERROR;
        if (voice != null) {
            try { res = tts.setVoice(voice); } catch (Exception ignored) {}
        }
        if (res != TextToSpeech.SUCCESS) {
            tts.setLanguage(locale);
            // Wybierz najlepszy zainstalowany głos dla języka
            List<Voice> best = voicesFor(locale.getLanguage());
            for (Voice v : best) {
                if (!v.isNetworkConnectionRequired()) { tts.setVoice(v); break; }
            }
        }
    }

    private void enqueueUpTo(int last, boolean flushFirst) {
        int end = Math.min(last, chunks.size() - 1);
        for (int i = queuedUpTo + 1; i <= end; i++) {
            Bundle params = new Bundle();
            int mode = (flushFirst && i == queuedUpTo + 1) ? TextToSpeech.QUEUE_FLUSH : TextToSpeech.QUEUE_ADD;
            tts.speak(chunks.get(i), mode, params, "s" + session + ":" + i);
            flushFirst = false;
        }
        queuedUpTo = Math.max(queuedUpTo, end);
    }

    @Override
    public void pause() {
        session++;
        if (ready) tts.stop();
    }

    @Override
    public void resume() {
        if (listener != null) start(chunks, currentIndex, rate, listener);
    }

    @Override
    public boolean canResumeInPlace() { return false; }

    @Override
    public void stop() {
        session++;
        if (ready) tts.stop();
    }

    @Override
    public void release() {
        session++;
        try { tts.stop(); tts.shutdown(); } catch (Exception ignored) {}
    }

    // ── Postęp wypowiedzi ────────────────────────────────────────────────

    private final UtteranceProgressListener progressListener = new UtteranceProgressListener() {
        @Override
        public void onStart(String id) {
            main.post(() -> {
                int[] p = parse(id);
                if (p == null || p[0] != session) return;
                currentIndex = p[1];
                if (listener != null) listener.onChunkStart(p[1]);
                enqueueUpTo(p[1] + LOOKAHEAD, false);
            });
        }

        @Override
        public void onDone(String id) {
            main.post(() -> {
                int[] p = parse(id);
                if (p == null || p[0] != session) return;
                if (p[1] >= chunks.size() - 1) {
                    session++;
                    if (listener != null) listener.onFinished();
                }
            });
        }

        @Override
        public void onError(String id) {
            main.post(() -> {
                int[] p = parse(id);
                if (p == null || p[0] != session) return;
                session++;
                if (listener != null) listener.onError("Błąd systemowego TTS.");
            });
        }

        @Override
        public void onError(String id, int errorCode) {
            main.post(() -> {
                int[] p = parse(id);
                if (p == null || p[0] != session) return;
                session++;
                String msg = errorCode == TextToSpeech.ERROR_NETWORK
                    || errorCode == TextToSpeech.ERROR_NETWORK_TIMEOUT
                    ? "Głos wymaga internetu — wybierz głos offline."
                    : "Błąd systemowego TTS (" + errorCode + ").";
                if (listener != null) listener.onError(msg);
            });
        }
    };

    private static int[] parse(String id) {
        if (id == null || !id.startsWith("s")) return null;
        int c = id.indexOf(':');
        if (c < 0) return null;
        try {
            return new int[]{Integer.parseInt(id.substring(1, c)), Integer.parseInt(id.substring(c + 1))};
        } catch (NumberFormatException e) { return null; }
    }
}
