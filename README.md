# Czytanie Strony

Aplikacja do czytania stron internetowych na głos. Składa się z dwóch części:

- **Android APK** — aplikacja mobilna z czytnikiem TTS i powiadomieniem Spotify-style
- **Rozszerzenie Chrome** — czytnik stron bezpośrednio w przeglądarce z tłumaczeniem na polski

Aplikacja czyta na głos strony WWW, wklejony tekst, a także **całe pliki**
wczytane z telefonu — `txt`, `md`, `pdf`, `docx`, `odt`, `rtf`, `html`
i inne pliki tekstowe (przycisk „📄 Wczytaj plik").

Wczytane pliki są **zapamiętywane na urządzeniu** wraz z miejscem, w którym
skończyłeś słuchać — możesz w każdej chwili wrócić i wznowić od tego momentu
(sekcja „Ostatnie pliki"). W ustawieniach wybierasz, jak długo pliki są
przechowywane (1 / 2 / 3 / 7 dni) oraz limit pamięci (0,5 / 1 / 2 / 5 GB) —
starsze są usuwane automatycznie.

---

## Pobieranie APK na Androida

### Metoda 1 — bezpośrednio z telefonu (najłatwiej)

1. Otwórz telefon i wejdź na:
   ```
   https://github.com/Kacperpe/CzytanieStrony/releases/latest
   ```
2. Kliknij plik `app-debug.apk` (lub `CzytanieStrony.apk`) — zostanie pobrany
3. Otwórz pobrany plik z powiadomień lub z folderu Pobrane
4. Jeśli pojawi się komunikat „Nieznane źródło":
   - Wejdź w **Ustawienia → Aplikacje → Specjalny dostęp → Instalacja nieznanych aplikacji**
   - Zezwól swojej przeglądarce na instalację
5. Kliknij **Zainstaluj**

### Metoda 2 — z komputera przez USB (ADB)

```bash
adb install app-debug.apk
```

---

## Jak zbudować APK samodzielnie

Wymagania: Android Studio, JDK 17+

```bash
git clone https://github.com/Kacperpe/CzytanieStrony.git
cd CzytanieStrony/android-apk
./gradlew assembleDebug
# APK znajdziesz w: app/build/outputs/apk/debug/app-debug.apk
```

---

## Rozszerzenie Chrome

1. Pobierz lub sklonuj to repozytorium
2. W Chrome otwórz `chrome://extensions`
3. Włącz **Tryb dewelopera** (przełącznik w prawym górnym rogu)
4. Kliknij **Załaduj rozpakowane** i wskaż folder `czytnik-strony/`
