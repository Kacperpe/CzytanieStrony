# Czytanie Strony

Aplikacja do czytania stron internetowych na głos. Składa się z dwóch części:

- **Android APK** — czytnik z naturalnymi głosami neuronowymi (Piper, offline), czytaniem w tle i sterowaniem z powiadomienia / słuchawek
- **Rozszerzenie Chrome** — czytnik stron bezpośrednio w przeglądarce z tłumaczeniem na polski

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

## Naturalne głosy (offline)

W aplikacji: **Język i głos → Zmień** → pobierz np. *Gosia*, *Darkman* albo *MC Speech* (~65 MB).
Głosy to modele Piper uruchamiane przez [sherpa-onnx](https://github.com/k2-fsa/sherpa-onnx) — działają na telefonie bez internetu.

**Własny głos:** wytrenuj/dotrenuj model Piper na komputerze i zaimportuj pliki `.onnx` + `.onnx.json`
(„Importuj własny głos” w oknie wyboru głosu). Wymaga wcześniej pobranego dowolnego głosu z listy (pliki wymowy espeak-ng).

## Nowa wersja APK

Każdy push buduje APK w GitHub Actions (zakładka *Actions* → artefakt `CzytanieStrony-apk`).
Opublikowanie release'u (np. tag `v0.2.0`) automatycznie dołącza `CzytanieStrony.apk`.
APK jest podpisany stałym kluczem z `android-apk/app/debug.keystore`, więc kolejne wersje instalują się na wierzch.

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
