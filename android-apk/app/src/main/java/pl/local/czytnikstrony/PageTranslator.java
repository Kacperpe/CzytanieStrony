package pl.local.czytnikstrony;

import android.text.TextUtils;

import com.google.mlkit.nl.languageid.LanguageIdentification;
import com.google.mlkit.nl.languageid.LanguageIdentifier;
import com.google.mlkit.nl.translate.TranslateLanguage;
import com.google.mlkit.nl.translate.Translation;
import com.google.mlkit.nl.translate.Translator;
import com.google.mlkit.nl.translate.TranslatorOptions;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * Tłumaczenie tekstu offline (Google ML Kit) akapit po akapicie.
 *
 * Każdy gotowy akapit trafia od razu do callbacku, więc czytanie startuje po
 * pierwszym akapicie, a reszta tłumaczy się w tle. Każde zlecenie ma własny
 * numer i własnego klienta tłumacza — cancel() unieważnia stare zlecenie,
 * więc jego spóźnione callbacki są ignorowane.
 *
 * Wszystkie callbacki przychodzą na wątku głównym (zadania ML Kit).
 */
final class PageTranslator {

    interface Callback {
        /** Język źródłowy = docelowy (albo nie da się tłumaczyć) — czytaj oryginał. */
        void onSkip(String reason);
        /** Przetłumaczony kolejny akapit (w kolejności). */
        void onParagraph(String translated);
        /** Wszystkie akapity gotowe. */
        void onDone();
        /** Błąd. rest = akapity jeszcze nieprzetłumaczone (do przeczytania w oryginale). */
        void onError(String message, List<String> rest);
        /** Komunikat dla użytkownika. */
        void onStatus(String message);
    }

    /** Docelowa paczka dla tłumacza — ML Kit najlepiej radzi sobie z krótkimi, pełnymi zdaniami. */
    private static final int SEGMENT = 700;

    private int job = 0;
    private Translator client;

    /** Unieważnia bieżące zlecenie (jeśli jest). */
    void cancel() {
        job++;
        closeClient();
    }

    /**
     * Startuje tłumaczenie. Zwraca numer zlecenia.
     * @param target kod ML Kit języka docelowego, np. "pl" albo "en".
     */
    int start(String text, String target, Callback cb) {
        cancel();
        final int id = job;
        final List<String> paragraphs = paragraphs(clean(text));
        if (paragraphs.isEmpty()) { cb.onSkip("Brak tekstu do tłumaczenia"); return id; }

        cb.onStatus("Wykrywam język…");
        String all = TextUtils.join("\n", paragraphs);
        String sample = all.length() > 4000 ? all.substring(0, 4000) : all;
        LanguageIdentifier li = LanguageIdentification.getClient();
        li.identifyLanguage(sample)
            .addOnSuccessListener(tag -> { li.close(); if (id == job) begin(id, paragraphs, tag, target, cb); })
            .addOnFailureListener(e -> { li.close(); if (id == job) begin(id, paragraphs, null, target, cb); });
        return id;
    }

    private void begin(int id, List<String> paragraphs, String tag, String target, Callback cb) {
        String src = null;
        if (tag != null && !"und".equals(tag)) src = TranslateLanguage.fromLanguageTag(tag);
        if (src == null) src = "pl".equals(Reader.detectLang(TextUtils.join("\n", paragraphs)))
            ? TranslateLanguage.POLISH : TranslateLanguage.ENGLISH;
        if (src.equals(target)) { cb.onSkip(""); return; }

        closeClient();
        client = Translation.getClient(new TranslatorOptions.Builder()
            .setSourceLanguage(src)
            .setTargetLanguage(target)
            .build());
        final Translator t = client;

        cb.onStatus("Pobieram model tłumaczenia (tylko za pierwszym razem)…");
        t.downloadModelIfNeeded()
            .addOnSuccessListener(u -> {
                if (id != job) return;
                cb.onStatus("Tłumaczę…");
                translateParagraph(id, t, paragraphs, 0, cb);
            })
            .addOnFailureListener(e -> {
                if (id != job) return;
                closeClient();
                cb.onError("Nie udało się pobrać modelu tłumaczenia", paragraphs);
            });
    }

    /** Akapity tłumaczone po kolei — żadnego zalewania tłumacza setkami zadań naraz. */
    private void translateParagraph(int id, Translator t, List<String> paragraphs, int p, Callback cb) {
        if (id != job) return;
        if (p >= paragraphs.size()) { closeClient(); cb.onDone(); return; }
        List<String> segs = segments(paragraphs.get(p));
        StringBuilder out = new StringBuilder();
        translateSegment(id, t, segs, 0, out, () -> {
            cb.onParagraph(out.toString().trim());
            translateParagraph(id, t, paragraphs, p + 1, cb);
        }, () -> {
            closeClient();
            cb.onError("Błąd tłumaczenia — dalej czytam oryginał",
                new ArrayList<>(paragraphs.subList(p, paragraphs.size())));
        });
    }

    private void translateSegment(int id, Translator t, List<String> segs, int s, StringBuilder out,
                                  Runnable done, Runnable failed) {
        if (id != job) return;
        if (s >= segs.size()) { done.run(); return; }
        t.translate(segs.get(s))
            .addOnSuccessListener(tr -> {
                if (id != job) return;
                if (out.length() > 0) out.append(' ');
                out.append(tr.trim());
                translateSegment(id, t, segs, s + 1, out, done, failed);
            })
            .addOnFailureListener(e -> { if (id == job) failed.run(); });
    }

    private void closeClient() {
        if (client != null) {
            try { client.close(); } catch (Exception ignored) {}
            client = null;
        }
    }

    // ── Przygotowanie tekstu ─────────────────────────────────────────────

    /** Akapity = niepuste linie (tak samo dzieli Reader). */
    static List<String> paragraphs(String text) {
        List<String> out = new ArrayList<>();
        for (String line : text.split("\n")) {
            String l = line.trim();
            if (!l.isEmpty()) out.add(l);
        }
        return out;
    }

    /** Dzieli akapit po granicach zdań na paczki do ~700 znaków (nigdy w środku zdania). */
    static List<String> segments(String para) {
        if (para.length() <= SEGMENT) return Collections.singletonList(para);
        List<String> chunks = new ArrayList<>();
        StringBuilder cur = new StringBuilder();
        for (String sentence : para.split("(?<=[.!?…])\\s+")) {
            String s = sentence.trim();
            if (s.isEmpty()) continue;
            if (cur.length() > 0 && cur.length() + s.length() + 1 > SEGMENT) {
                chunks.add(cur.toString());
                cur.setLength(0);
            }
            if (cur.length() > 0) cur.append(' ');
            cur.append(s);
        }
        if (cur.length() > 0) chunks.add(cur.toString());
        return chunks.isEmpty() ? Collections.singletonList(para) : chunks;
    }

    // ── Czyszczenie śmieci (menu, cookie, „udostępnij”) ──────────────────

    /** Całe linie, które na pewno nie są treścią. */
    private static final String[] JUNK_LINES = {
        "menu", "share", "share this", "udostępnij", "skip to content", "skip to main content",
        "przejdź do treści", "zaloguj", "zaloguj się", "sign in", "log in", "login", "subscribe",
        "subskrybuj", "newsletter", "reklama", "advertisement", "ad", "accept", "accept all",
        "akceptuj", "akceptuję", "akceptuj wszystkie", "akceptuj wszystkie pliki cookie",
        "accept all cookies", "accept cookies", "polityka prywatności", "privacy policy",
        "all rights reserved", "wszelkie prawa zastrzeżone", "cookie settings", "ustawienia cookies",
        "czytaj także", "czytaj też", "read more", "zobacz też", "komentarze", "comments"
    };

    /** Krótkie linie (≤3 słowa) zaczynające się od takiego słowa to też zwykle przyciski/nawigacja. */
    private static final Pattern JUNK_START = Pattern.compile(
        "^(udostępnij|share|zaloguj|sign in|log in|subskrybuj|subscribe|akceptuj|accept|reklama|advertisement|newsletter|cookie|cookies)\\b.*",
        Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE);

    /**
     * Usuwa tylko oczywiste śmieci. Zasady są celowo ostrożne: linia musi być
     * w całości znanym przyciskiem/napisem albo bardzo krótka i zaczynać się od
     * słowa-przycisku, bez kropki na końcu. Pierwsza linia (tytuł) zostaje zawsze.
     */
    static String clean(String text) {
        if (text == null) return "";
        StringBuilder sb = new StringBuilder();
        boolean first = true;
        for (String raw : text.split("\n")) {
            String line = raw.trim();
            if (line.isEmpty()) { sb.append('\n'); continue; }
            if (!first && isJunk(line)) continue;
            first = false;
            sb.append(line).append('\n');
        }
        return sb.toString().replaceAll("\\n{3,}", "\n\n").trim();
    }

    static boolean isJunk(String line) {
        String norm = line.toLowerCase(Locale.ROOT).replaceAll("[\\p{Punct}…»«„”\"'·|]+", " ").trim()
            .replaceAll("\\s+", " ");
        if (norm.isEmpty()) return true;                                  // same znaki
        for (String j : JUNK_LINES) if (norm.equals(j)) return true;
        if (line.startsWith("©")) return true;
        int words = norm.split(" ").length;
        boolean sentenceEnd = line.matches(".*[.!?…]$");
        if (words <= 3 && !sentenceEnd && JUNK_START.matcher(norm).matches()) return true;
        // linie złożone tylko z liczb/symboli (np. „12 / 34”)
        return words <= 3 && line.replaceAll("[\\d\\s\\p{Punct}]", "").isEmpty();
    }
}
