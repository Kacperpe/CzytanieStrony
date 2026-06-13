# Czytnik strony Android

Lokalna aplikacja Android do czytania stron i tekstu glosami systemowymi.

## Funkcje

- ladowanie strony w WebView,
- kafelek szybkich ustawien Androida do otwierania malego panelu nad aktualna aplikacja,
- szybki panel z kafelka: czytanie schowka, stop, zamkniecie i przejscie do pelnego czytnika,
- czytanie zaznaczenia na stronie albo glownego tekstu strony,
- pole tekstowe do wklejania lub odbierania tekstu z opcji Udostepnij,
- czytanie od kursora w polu tekstowym,
- wybor jezyka: automatycznie, polski albo angielski,
- wybor tylko polskich i angielskich glosow dostepnych na telefonie,
- automatyczny dobor jezyka tylko miedzy polskim i angielskim,
- regulacja tempa od 0.60x do 1.80x,
- pauza/wznowienie i stop.

## Budowanie APK

1. Otworz folder `android-apk` w Android Studio.
2. Poczekaj az Gradle pobierze Android Gradle Plugin.
3. Wybierz `Build` -> `Build Bundle(s) / APK(s)` -> `Build APK(s)`.
4. APK bedzie w `android-apk/app/build/outputs/apk/debug/app-debug.apk`.

Na tej maszynie moze byc potrzebna instalacja Android Studio albo Android SDK/JDK, jesli nie sa jeszcze zainstalowane.

## Dodanie kafelka w telefonie

1. Zainstaluj APK na telefonie.
2. W aplikacji dotknij `Dodaj kafelek do panelu`, jesli telefon ma Androida 13 lub nowszego.
3. Na starszym Androidzie przeciagnij panel z gory ekranu dwa razy.
4. Wejdz w edycje kafelkow (`Edit`, ikona olowka albo `Edytuj`).
5. Znajdz kafelek `Czytnik` / `Czytnik strony`.
6. Przeciagnij go do aktywnych kafelkow.

Po dotknieciu kafelka pojawi sie maly panel nad aktualna aplikacja.
Panel moze czytac tekst skopiowany do schowka albo otworzyc pelny czytnik z tekstem/adresem ze schowka.

Android nie pozwala zwyklej aplikacji samodzielnie odczytac tresci strony z innej aplikacji bez udostepnienia tekstu/adresu albo uslugi dostepnosci. Najprostszy przeplyw: skopiuj tekst lub adres strony, dotknij kafelka, potem `Czytaj schowek` albo `Otworz pelny czytnik`.

## Gdy Gradle nie moze usunac katalogu build

Projekt jest w OneDrive, a OneDrive czasem moze blokowac pliki z `app/build/intermediates`.

Jesli problem dalej wystapi:

1. Zamknij Android Studio.
2. W Menedzerze zadan zakoncz procesy `java.exe`, `gradle.exe` i `aapt2.exe`, jesli zostaly.
3. Usun katalog `android-apk/app/build`.
4. Otworz projekt ponownie i uruchom `:app:assembleDebug`.
