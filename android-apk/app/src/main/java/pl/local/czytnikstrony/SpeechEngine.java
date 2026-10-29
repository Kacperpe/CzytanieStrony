package pl.local.czytnikstrony;

import java.util.List;

/**
 * Wspólny interfejs silników mowy (systemowy TTS Androida i neuronowy Piper).
 * Wszystkie metody wołane są z wątku głównego, a callbacki Listenera
 * również trafiają na wątek główny.
 */
interface SpeechEngine {

    interface Listener {
        /** Zaczął się fragment o danym indeksie. */
        void onChunkStart(int index);
        /** Przeczytano wszystkie fragmenty. */
        void onFinished();
        /** Błąd — czytanie zatrzymane. */
        void onError(String message);
        /** Komunikat pomocniczy (np. „Ładuję głos…”). */
        void onStatus(String message);
    }

    /** Rozpoczyna czytanie listy fragmentów od startIndex. Przerywa poprzednią sesję. */
    void start(List<String> chunks, int startIndex, float rate, Listener listener);

    /** Pauza. Jeśli silnik nie umie pauzować w miejscu, zatrzymuje bieżący fragment. */
    void pause();

    /** Wznowienie po pauzie. */
    void resume();

    /** true — resume() kontynuuje dokładnie od miejsca pauzy. */
    boolean canResumeInPlace();

    /** Zatrzymuje sesję (callbacki starej sesji są ignorowane). */
    void stop();

    /** Zwalnia zasoby. */
    void release();
}
