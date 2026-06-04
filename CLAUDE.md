# CzytanieStrony — instrukcje dla agenta

Aplikacja Android (czytnik stron / TTS) w katalogu `android-apk/`.
Repo GitHub: **Kacperpe/CzytanieStrony**. Główny branch lokalny: `master`.

---

## Jak działa wydawanie nowej wersji (release na GitHubie)

Release **nie jest** budowany lokalnie ani wrzucany ręcznie. Mechanizm:

1. Wypchnięcie taga `v*` (np. `v1.0.4`) uruchamia GitHub Actions
   (`.github/workflows/android-release.yml`).
2. Workflow buduje **debug APK** na Linuksie i publikuje GitHub Release
   z plikiem `CzytanieStrony-v<TAG>-debug.apk`.
3. Aplikacja sama wykrywa nowszą wersję (porównuje swój `versionName`
   z `tag_name` najnowszego release) i pokazuje przycisk „Pobierz <tag>".
   Logika: `MainActivity.java` → `checkForUpdate` / `isNewerVersion` /
   `findApkAssetUrl` (bierze pierwszy asset kończący się na `.apk`).

> Dlatego **tag musi być wersją wyższą** niż `versionName` zbudowanego APK,
> inaczej zainstalowana apka nie pokaże aktualizacji.

---

## ✅ CHECKLISTA WYDANIA — odhaczaj po kolei

Przy każdej prośbie typu „zmień X i wrzuć nową wersję na GitHub do ściągnięcia":

- [ ] **1. Wprowadź zmiany** (kod / kolory / funkcje).
- [ ] **2. Podbij wersję** w `android-apk/app/build.gradle`:
      - `versionName` → nowy numer (np. `1.0.4`)
      - `versionCode` → +1 (liczba całkowita, musi rosnąć)
- [ ] **3. Zbuduj lokalnie i potwierdź `BUILD SUCCESSFUL`** (patrz „Build lokalny").
      Nie wydawaj, jeśli build nie przechodzi.
- [ ] **4. Commit + push na `master`** (tylko śledzone pliki — patrz „Czego NIE commitować").
- [ ] **5. Utwórz i wypchnij tag** zgodny z nowym `versionName`:
      `git tag v1.0.4 && git push origin v1.0.4`
- [ ] **6. Poczekaj ~2-3 min i ZWERYFIKUJ release przez API** (patrz „Weryfikacja").
      Potwierdź, że `tag_name` się zgadza i że jest asset `*.apk` o sensownym rozmiarze.
- [ ] **7. Zgłoś użytkownikowi wynik** z linkiem do
      https://github.com/Kacperpe/CzytanieStrony/releases

Jeśli build CI padnie — sprawdź workflow, popraw, i ponów od kroku 4/5
(nowym tagiem, np. patch wyżej; nie reużywaj wydanego taga).

---

## Build lokalny (Windows)

`gh` CLI **nie jest** zainstalowane. JDK i SDK nie są w PATH — ustaw je ręcznie:

```powershell
$env:JAVA_HOME = "C:\Program Files\Android\Android Studio\jbr"   # JBR z Android Studio
$env:ANDROID_HOME = "$env:LOCALAPPDATA\Android\Sdk"
$env:ANDROID_SDK_ROOT = $env:ANDROID_HOME
$env:Path = "$env:JAVA_HOME\bin;$env:Path"
Set-Location "C:\Users\Kacper\OneDrive\.STARTUP\Czytanie strony\android-apk"
.\gradlew.bat --no-daemon assembleDebug
```

> Uwaga: `gradlew.bat` z `2>&1` w PowerShell bywa raportowane jako „błąd"
> mimo sukcesu — liczy się linia `BUILD SUCCESSFUL`.

Wynik buildu trafia POZA drzewo projektu (żeby nie kolidować z OneDrive):
`%USERPROFILE%\.czytnik-strony-build\app\outputs\apk\debug\app-debug.apk`
(ścieżkę ustawia `android-apk/build.gradle`; na CI Linux to `$HOME/.czytnik-strony-build`).

---

## Weryfikacja release (bez gh CLI — przez REST API)

```powershell
$r = Invoke-RestMethod -Uri "https://api.github.com/repos/Kacperpe/CzytanieStrony/releases/latest" -Headers @{ "User-Agent" = "ps" }
"latest: $($r.tag_name)"
$r.assets | ForEach-Object { "$($_.name) | $([math]::Round($_.size/1MB,2)) MB" }
```

Lista wszystkich release / sprawdzenie czy istnieje konkretny:
`.../repos/Kacperpe/CzytanieStrony/releases`

---

## Zmiana kolorystyki (paleta)

Paleta jest zdefiniowana **w 5 miejscach** — przy zmianie trzymaj je spójne:

1. `android-apk/.../MainActivity.java` → `initColors()` (blok dark + light)
2. `android-apk/.../FloatingReaderActivity.java` → `initColors()`
3. `android-apk/.../PlayerService.java` → stałe `COLOR_*` (kolory powiadomienia)
4. `android-apk/app/src/main/res/values/styles.xml` (tryb jasny: tło, akcent, status bar)
5. `android-apk/app/src/main/res/values-night/styles.xml` (tryb ciemny)

Aktualna paleta: **Tonal Ochre** — krem `#F7F2E8`, beż `#EDE8DC`,
białe karty `#FFFFFF`, ochra (akcent) `#B8820A` / w ciemnym `#D4A020`,
tekst `#1C1810`, taupe `#8E8878`, baza ciemna `#1C1810`,
danger (spalony pomarańcz) `#C04E00`.

---

## Tłumaczenie offline

Realizowane przez **Google ML Kit (on-device)** — zależność
`com.google.mlkit:translate` w `android-apk/app/build.gradle`.
Logika w `MainActivity.java`: `maybeTranslateAndSpeak` / `translateChunked` /
`splitForTranslation`. Modele (~30 MB/parę języków) pobierają się raz,
potem działa offline.

---

## Czego NIE commitować

- Dużych binarek APK z katalogu roboczego (np. `CzytanieStrony-v1.0.apk`).
- Plików roboczych palety/QA (np. `* Tonal Ochre.html`, `LOCAL_BUGS_REPORT.md`).
- Używaj `git add -u` (tylko śledzone), nie `git add .`.
