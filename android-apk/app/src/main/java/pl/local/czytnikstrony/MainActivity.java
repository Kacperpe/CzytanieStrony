package pl.local.czytnikstrony;

import android.app.Activity;
import android.app.DownloadManager;
import android.app.StatusBarManager;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.res.Configuration;
import android.database.Cursor;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.Icon;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.provider.Settings;
import android.speech.tts.TextToSpeech;
import android.speech.tts.UtteranceProgressListener;
import android.speech.tts.Voice;
import android.text.Editable;
import android.text.InputType;
import android.text.Spannable;
import android.text.TextUtils;
import android.text.style.BackgroundColorSpan;
import android.view.Gravity;
import android.view.View;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.SeekBar;
import android.widget.Spinner;
import android.widget.TextView;

import com.google.mlkit.nl.translate.TranslateLanguage;
import com.google.mlkit.nl.translate.Translation;
import com.google.mlkit.nl.translate.Translator;
import com.google.mlkit.nl.translate.TranslatorOptions;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

public class MainActivity extends Activity implements TextToSpeech.OnInitListener {

    private static final int   MAX_CHUNK = 260;
    private static final int   MAX_EXTRACTED_TEXT = 120000;
    private static final float DEF_RATE  = 0.92f;

    private static final String GITHUB_OWNER = "Kacperpe";
    private static final String GITHUB_REPO  = "CzytanieStrony";

    // ── Paleta kolorów ───────────────────────────────────────────────────────
    private int C_BG, C_SURFACE, C_SURFACE2, C_PRIMARY, C_PRIMARY_DIM, C_ON_PRIMARY;
    private int C_TEXT, C_MUTED, C_BORDER, C_DANGER, C_DANGER_BG;

    // ── Views ────────────────────────────────────────────────────────────────
    private EditText     urlInput;
    private EditText     textInput;
    private TextView     statusText;
    private TextView     rateText;
    private TextView     progressText;
    private TextView     nowPlayingTitle;
    private TextView     nowPlayingPreview;
    private WebView      webView;
    private ProgressBar  loadingBar;
    private SeekBar      rateSeekBar;
    private SeekBar      progressSeekBar;
    private Spinner      languageSpinner;
    private Spinner      voiceSpinner;
    private Spinner      translateSpinner;
    private LinearLayout settingsPanel;
    private ScrollView   readerScroll;
    private ScrollView   settingsScroll;
    private Button       navReaderBtn;
    private Button       navSettingsBtn;
    private Button       playPauseButton;
    private Button       updateButton;
    private long         updateDownloadId = -1L;
    private String       latestReleaseUrl = "";

    // ── Stan TTS ─────────────────────────────────────────────────────────────
    private TextToSpeech         tts;
    private final List<Voice>    allVoices   = new ArrayList<>();
    private final List<Voice>    voices      = new ArrayList<>();
    private final List<String>   voiceLabels = new ArrayList<>();
    private String  selectedLanguageCode = "auto";
    private String  selectedVoiceName    = "";
    private float   speechRate           = DEF_RATE;
    private boolean ttsReady       = false;
    private boolean readingQueue    = false;
    private boolean paused          = false;
    private List<String>       currentChunks      = new ArrayList<>();
    private final List<Integer> currentChunkStarts = new ArrayList<>();
    private final List<Integer> currentChunkEnds   = new ArrayList<>();
    private int     currentChunkIndex = 0;
    private boolean settingsVisible   = false;
    private String  currentTitle      = "";
    private BackgroundColorSpan currentChunkSpan;

    // ── Tłumaczenie ML Kit ───────────────────────────────────────────────────
    private boolean    translateEnabled    = false;
    private String     translateTargetLang = "pl";
    private Translator mlTranslator;

    private static final String[] TRANSLATE_LANGS = {
        "Wyłączone", "Polski", "Angielski", "Niemiecki", "Francuski",
        "Hiszpański", "Włoski", "Rosyjski", "Ukraiński", "Portugalski"
    };
    private static final String[] TRANSLATE_CODES = {
        "", "pl", "en", "de", "fr", "es", "it", "ru", "uk", "pt"
    };

    private static final String PREFS          = "czytnik_prefs";
    private static final String PREF_TRANSLATE = "translate_idx";

    private final BroadcastReceiver controlReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(android.content.Context context, Intent intent) {
            String action = intent.getStringExtra(PlayerService.KEY_NOTIF_ACTION);
            if (action != null) handleNotifAction(action);
        }
    };

    private final BroadcastReceiver updateDownloadReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            if (intent == null || !DownloadManager.ACTION_DOWNLOAD_COMPLETE.equals(intent.getAction())) return;
            long downloadId = intent.getLongExtra(DownloadManager.EXTRA_DOWNLOAD_ID, -1L);
            if (downloadId != updateDownloadId) return;
            handleDownloadedUpdate(downloadId);
        }
    };

    // ════════════════════════════════════════════════════════════════════════
    //  Lifecycle
    // ════════════════════════════════════════════════════════════════════════

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        initColors();
        buildUi();
        restorePrefs();
        configureWebView();
        tts = new TextToSpeech(this, this);
        handleIncomingIntent(getIntent());
        checkForUpdate();
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            requestPermissions(new String[]{android.Manifest.permission.POST_NOTIFICATIONS}, 0);
        }
        IntentFilter filter = new IntentFilter(PlayerService.ACTION_CONTROL_EVENT);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(controlReceiver, filter, RECEIVER_NOT_EXPORTED);
            registerReceiver(updateDownloadReceiver,
                new IntentFilter(DownloadManager.ACTION_DOWNLOAD_COMPLETE), RECEIVER_NOT_EXPORTED);
        } else {
            registerReceiver(controlReceiver, filter, PlayerService.INTERNAL_BROADCAST_PERMISSION, null);
            registerReceiver(updateDownloadReceiver,
                new IntentFilter(DownloadManager.ACTION_DOWNLOAD_COMPLETE));
        }
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        if (intent == null) return;
        String notifAction = intent.getStringExtra(PlayerService.KEY_NOTIF_ACTION);
        if (notifAction != null) { handleNotifAction(notifAction); return; }
        handleIncomingIntent(intent);
    }

    private void handleNotifAction(String action) {
        switch (action) {
            case PlayerService.NOTIF_ACTION_PLAY_PAUSE: handlePlayPause(); break;
            case PlayerService.NOTIF_ACTION_PREV:       goToPrevChunk();   break;
            case PlayerService.NOTIF_ACTION_NEXT:       goToNextChunk();   break;
            case PlayerService.NOTIF_ACTION_STOP:       stopReading();     break;
        }
    }

    @Override
    protected void onDestroy() {
        try { unregisterReceiver(controlReceiver); } catch (Exception ignored) {}
        try { unregisterReceiver(updateDownloadReceiver); } catch (Exception ignored) {}
        if (tts != null) { tts.stop(); tts.shutdown(); }
        if (mlTranslator != null) { mlTranslator.close(); mlTranslator = null; }
        PlayerService.hide(this);
        if (webView != null) {
            webView.stopLoading();
            webView.setWebChromeClient(null);
            webView.setWebViewClient(null);
            webView.destroy();
            webView = null;
        }
        super.onDestroy();
    }

    // ════════════════════════════════════════════════════════════════════════
    //  Paleta kolorów — Tonal Ochre (ciepła ochra / taupe / krem)
    // ════════════════════════════════════════════════════════════════════════

    private boolean isDarkMode() {
        int mask = getResources().getConfiguration().uiMode & Configuration.UI_MODE_NIGHT_MASK;
        return mask == Configuration.UI_MODE_NIGHT_YES;
    }

    private void initColors() {
        if (isDarkMode()) {
            C_BG          = 0xFF1C1810;
            C_SURFACE     = 0xFF2A2418;
            C_SURFACE2    = 0xFF241E16;
            C_PRIMARY     = 0xFFD4A020;
            C_PRIMARY_DIM = 0xFF3A2E10;
            C_ON_PRIMARY  = 0xFF1C1810;
            C_TEXT        = 0xFFF7F2E8;
            C_MUTED       = 0xFFA89F8C;
            C_BORDER      = 0xFF4E4840;
            C_DANGER      = 0xFFE05A00;
            C_DANGER_BG   = 0xFF2A1008;
        } else {
            C_BG          = 0xFFF7F2E8;
            C_SURFACE     = 0xFFFFFFFF;
            C_SURFACE2    = 0xFFEDE8DC;
            C_PRIMARY     = 0xFFB8820A;
            C_PRIMARY_DIM = 0xFFF5E8C0;
            C_ON_PRIMARY  = 0xFFFFFFFF;
            C_TEXT        = 0xFF1C1810;
            C_MUTED       = 0xFF8E8878;
            C_BORDER      = 0xFFDDD8CC;
            C_DANGER      = 0xFFC04E00;
            C_DANGER_BG   = 0xFFF7F2E8;
        }
    }

    // ════════════════════════════════════════════════════════════════════════
    //  TTS init
    // ════════════════════════════════════════════════════════════════════════

    @Override
    public void onInit(int status) {
        ttsReady = (status == TextToSpeech.SUCCESS);
        if (!ttsReady) { setStatus("TTS niedostępny na tym urządzeniu."); return; }

        tts.setOnUtteranceProgressListener(new UtteranceProgressListener() {
            @Override public void onStart(String id) {}

            @Override
            public void onDone(String id) {
                runOnUiThread(() -> {
                    if (readingQueue && !paused) {
                        currentChunkIndex++;
                        updateProgress();
                        speakNextChunk();
                    }
                });
            }

            @Override
            public void onError(String id) {
                runOnUiThread(() -> {
                    readingQueue = false;
                    setStatus("Błąd czytania.");
                    updatePlayPauseBtn();
                });
            }

            @Override public void onStop(String id, boolean interrupted) {}
        });

        loadVoices();
        setStatus("Gotowy");
    }

    // ════════════════════════════════════════════════════════════════════════
    //  Budowanie UI
    // ════════════════════════════════════════════════════════════════════════

    private void buildUi() {
        LinearLayout rootCol = new LinearLayout(this);
        rootCol.setOrientation(LinearLayout.VERTICAL);
        rootCol.setBackgroundColor(C_BG);

        // Kontener na ekrany (przełączane dolnym paskiem)
        FrameLayout content = new FrameLayout(this);

        readerScroll   = buildReaderScreen();
        settingsScroll = buildSettingsScreen();
        settingsScroll.setVisibility(View.GONE);

        content.addView(readerScroll,   new FrameLayout.LayoutParams(-1, -1));
        content.addView(settingsScroll, new FrameLayout.LayoutParams(-1, -1));

        rootCol.addView(content,         new LinearLayout.LayoutParams(-1, 0, 1f));
        rootCol.addView(buildBottomNav(), new LinearLayout.LayoutParams(-1, -2));

        setContentView(rootCol);

        // Pasek systemu
        getWindow().setStatusBarColor(C_BG);
        getWindow().setNavigationBarColor(C_SURFACE);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            android.view.WindowInsetsController wic = getWindow().getInsetsController();
            if (wic != null) {
                int flag = android.view.WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS;
                int navFlag = android.view.WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS;
                int bothFlags = flag | navFlag;
                wic.setSystemBarsAppearance(isDarkMode() ? 0 : bothFlags, bothFlags);
            }
        }
    }

    // ── Ekran „Czytanie" ───────────────────────────────────────────────────
    private ScrollView buildReaderScreen() {
        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        scroll.setBackgroundColor(C_BG);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(18), dp(20), dp(18), dp(20));
        scroll.addView(root, new ScrollView.LayoutParams(-1, -2));

        // ── Header ──
        LinearLayout header = new LinearLayout(this);
        header.setOrientation(LinearLayout.HORIZONTAL);
        header.setGravity(Gravity.CENTER_VERTICAL);

        LinearLayout titleCol = new LinearLayout(this);
        titleCol.setOrientation(LinearLayout.VERTICAL);

        TextView appName = new TextView(this);
        appName.setText("Czytnik");
        appName.setTextColor(C_TEXT);
        appName.setTextSize(28);
        appName.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            appName.setLetterSpacing(-0.02f);
        }
        titleCol.addView(appName);

        statusText = new TextView(this);
        statusText.setText("Startuje…");
        statusText.setTextColor(C_PRIMARY);
        statusText.setTextSize(12);
        statusText.setTypeface(Typeface.create("sans-serif", Typeface.NORMAL));
        LinearLayout.LayoutParams stlp = new LinearLayout.LayoutParams(-1, -2);
        stlp.setMargins(0, dp(1), 0, 0);
        titleCol.addView(statusText, stlp);

        header.addView(titleCol, new LinearLayout.LayoutParams(0, -2, 1f));

        // Baner aktualizacji (pill w nagłówku)
        updateButton = new Button(this);
        updateButton.setText("↑ Aktualizacja");
        updateButton.setAllCaps(false);
        updateButton.setTextSize(11);
        updateButton.setTypeface(Typeface.DEFAULT_BOLD);
        updateButton.setTextColor(C_ON_PRIMARY);
        updateButton.setBackground(mkRound(C_PRIMARY, 0, 16));
        updateButton.setPadding(dp(12), dp(6), dp(12), dp(6));
        updateButton.setVisibility(View.GONE);
        LinearLayout.LayoutParams ulp = new LinearLayout.LayoutParams(-2, -2);
        ulp.setMargins(dp(8), 0, 0, 0);
        ulp.gravity = Gravity.CENTER_VERTICAL;
        header.addView(updateButton, ulp);

        root.addView(header, mbottom(dp(20)));

        // ── Karta URL ──
        root.addView(buildUrlCard(), mbottom(dp(10)));

        // WebView ukryty (1×1 px)
        webView = new WebView(this);
        webView.setVisibility(View.GONE);
        root.addView(webView, new LinearLayout.LayoutParams(1, 1));

        // ── Karta tekstu ──
        root.addView(buildTextCard(), mbottom(dp(10)));

        // ── Player (hero) ──
        root.addView(buildPlayer(), mbottom(dp(10)));

        return scroll;
    }

    // ── Ekran „Ustawienia" ──────────────────────────────────────────────────
    private ScrollView buildSettingsScreen() {
        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        scroll.setBackgroundColor(C_BG);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(18), dp(20), dp(18), dp(20));
        scroll.addView(root, new ScrollView.LayoutParams(-1, -2));

        TextView title = new TextView(this);
        title.setText("Ustawienia");
        title.setTextColor(C_TEXT);
        title.setTextSize(28);
        title.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            title.setLetterSpacing(-0.02f);
        }
        root.addView(title, mbottom(dp(18)));

        settingsPanel = buildSettingsPanel();
        root.addView(settingsPanel, mbottom(dp(14)));

        // ── Link do kafelka szybkich ustawień ──
        TextView tileLink = new TextView(this);
        tileLink.setText("+ Dodaj kafelek szybkich ustawień");
        tileLink.setTextColor(C_MUTED);
        tileLink.setTextSize(12);
        tileLink.setPadding(dp(4), dp(8), dp(4), dp(4));
        tileLink.setClickable(true);
        tileLink.setOnClickListener(v -> requestTile());
        root.addView(tileLink);

        return scroll;
    }

    // ── Dolny pasek nawigacji ─────────────────────────────────────────────────
    private LinearLayout buildBottomNav() {
        LinearLayout bar = new LinearLayout(this);
        bar.setOrientation(LinearLayout.HORIZONTAL);
        bar.setBackgroundColor(C_SURFACE);
        bar.setPadding(dp(8), dp(8), dp(8), dp(10));

        navReaderBtn   = navButton("📖", "Czytanie");
        navSettingsBtn = navButton("⚙", "Ustawienia");

        navReaderBtn.setOnClickListener(v -> showScreen(true));
        navSettingsBtn.setOnClickListener(v -> showScreen(false));

        LinearLayout.LayoutParams half = new LinearLayout.LayoutParams(0, -2, 1f);
        bar.addView(navReaderBtn,   half);
        bar.addView(navSettingsBtn, half);

        showScreen(true);
        return bar;
    }

    private Button navButton(String glyph, String label) {
        Button b = new Button(this);
        b.setText(glyph + "\n" + label);
        b.setAllCaps(false);
        b.setTextSize(11);
        b.setLineSpacing(dp(2), 1f);
        b.setGravity(Gravity.CENTER);
        b.setBackground(null);
        b.setPadding(0, dp(4), 0, dp(2));
        return b;
    }

    private void showScreen(boolean reader) {
        if (readerScroll != null)   readerScroll.setVisibility(reader ? View.VISIBLE : View.GONE);
        if (settingsScroll != null) settingsScroll.setVisibility(reader ? View.GONE : View.VISIBLE);
        if (navReaderBtn != null) {
            navReaderBtn.setTextColor(reader ? C_PRIMARY : C_MUTED);
            navReaderBtn.setTypeface(reader ? Typeface.DEFAULT_BOLD : Typeface.DEFAULT);
        }
        if (navSettingsBtn != null) {
            navSettingsBtn.setTextColor(reader ? C_MUTED : C_PRIMARY);
            navSettingsBtn.setTypeface(reader ? Typeface.DEFAULT : Typeface.DEFAULT_BOLD);
        }
    }

    /** Przywraca zapamiętane ustawienia (np. wybrany język tłumaczenia). */
    private void restorePrefs() {
        int tIdx = getSharedPreferences(PREFS, MODE_PRIVATE).getInt(PREF_TRANSLATE, 0);
        if (translateSpinner != null && tIdx > 0 && tIdx < TRANSLATE_CODES.length) {
            translateSpinner.setSelection(tIdx);  // wywoła listener i ustawi stan
        }
    }

    // ── Karta URL ────────────────────────────────────────────────────────────

    private LinearLayout buildUrlCard() {
        LinearLayout card = surfaceCard();

        TextView label = sectionLabel("ADRES STRONY");
        root_add_with_bottom(card, label, dp(8));

        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);

        urlInput = new EditText(this);
        urlInput.setSingleLine(true);
        urlInput.setHint("https://...");
        urlInput.setTextColor(C_TEXT);
        urlInput.setHintTextColor(C_MUTED);
        urlInput.setTextSize(14);
        urlInput.setPadding(dp(14), dp(11), dp(14), dp(11));
        urlInput.setBackground(mkRound(C_SURFACE2, C_BORDER, 12));
        urlInput.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI);
        LinearLayout.LayoutParams inputLp = new LinearLayout.LayoutParams(0, dp(46), 1f);
        inputLp.setMargins(0, 0, dp(8), 0);
        row.addView(urlInput, inputLp);

        Button readBtn = primaryBtn("Czytaj");
        row.addView(readBtn, actionBtnLp());
        card.addView(row);

        loadingBar = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
        loadingBar.setMax(100);
        loadingBar.setVisibility(View.GONE);
        LinearLayout.LayoutParams lbp = new LinearLayout.LayoutParams(-1, dp(2));
        lbp.setMargins(0, dp(8), 0, 0);
        card.addView(loadingBar, lbp);

        readBtn.setOnClickListener(v -> extractAndRead());
        urlInput.setOnEditorActionListener((v, actionId, event) -> { extractAndRead(); return true; });

        return card;
    }

    // ── Karta tekstu ─────────────────────────────────────────────────────────

    private LinearLayout buildTextCard() {
        LinearLayout card = surfaceCard();

        LinearLayout topRow = new LinearLayout(this);
        topRow.setOrientation(LinearLayout.HORIZONTAL);
        topRow.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout.LayoutParams trp = new LinearLayout.LayoutParams(-1, -2);
        trp.setMargins(0, 0, 0, dp(8));

        topRow.addView(sectionLabel("TEKST"), new LinearLayout.LayoutParams(0, -2, 1f));

        Button clearBtn = ghostBtn("Wyczyść");
        topRow.addView(clearBtn);
        card.addView(topRow, trp);

        textInput = new EditText(this);
        textInput.setGravity(Gravity.TOP);
        textInput.setMinLines(3);
        textInput.setHint("Tekst pojawi się po kliknięciu Czytaj\nlub wklej własny…");
        textInput.setTextColor(C_TEXT);
        textInput.setHintTextColor(C_MUTED);
        textInput.setTextSize(14);
        textInput.setLineSpacing(dp(3), 1f);
        textInput.setPadding(dp(14), dp(11), dp(14), dp(11));
        textInput.setBackground(mkRound(C_SURFACE2, C_BORDER, 12));
        card.addView(textInput, new LinearLayout.LayoutParams(-1, dp(148)));

        LinearLayout btns = new LinearLayout(this);
        btns.setOrientation(LinearLayout.HORIZONTAL);
        btns.setGravity(Gravity.END);
        LinearLayout.LayoutParams blp = new LinearLayout.LayoutParams(-1, -2);
        blp.setMargins(0, dp(8), 0, 0);

        Button cursorBtn  = secondaryBtn("Od kursora");
        Button readTxtBtn = primaryBtn("Czytaj tekst");
        btns.addView(cursorBtn,  actionBtnLp());
        btns.addView(readTxtBtn, actionBtnLp());
        card.addView(btns, blp);

        clearBtn.setOnClickListener(v -> { textInput.setText(""); stopReading(); });
        readTxtBtn.setOnClickListener(v -> maybeTranslateAndSpeak(textInput.getText().toString()));
        cursorBtn.setOnClickListener(v -> speakFromCursor());

        return card;
    }

    // ── Player — hero card ───────────────────────────────────────────────────

    private LinearLayout buildPlayer() {
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setPadding(dp(18), dp(18), dp(18), dp(16));
        card.setBackground(mkRound(C_PRIMARY_DIM, isDarkMode() ? C_BORDER : 0, 22));

        // Now-playing title
        nowPlayingTitle = new TextView(this);
        nowPlayingTitle.setText("Gotowy do czytania");
        nowPlayingTitle.setTextColor(C_PRIMARY);
        nowPlayingTitle.setTextSize(13);
        nowPlayingTitle.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        nowPlayingTitle.setSingleLine(true);
        nowPlayingTitle.setEllipsize(TextUtils.TruncateAt.END);
        card.addView(nowPlayingTitle);

        // Fragment preview (italic, muted)
        nowPlayingPreview = new TextView(this);
        nowPlayingPreview.setText("");
        nowPlayingPreview.setTextColor(C_MUTED);
        nowPlayingPreview.setTextSize(12);
        nowPlayingPreview.setTypeface(Typeface.create(Typeface.DEFAULT, Typeface.ITALIC));
        nowPlayingPreview.setMaxLines(2);
        nowPlayingPreview.setEllipsize(TextUtils.TruncateAt.END);
        nowPlayingPreview.setLineSpacing(dp(2), 1f);
        LinearLayout.LayoutParams pvlp = new LinearLayout.LayoutParams(-1, -2);
        pvlp.setMargins(0, dp(3), 0, dp(14));
        card.addView(nowPlayingPreview, pvlp);

        // Pasek postępu + licznik
        LinearLayout progRow = new LinearLayout(this);
        progRow.setOrientation(LinearLayout.HORIZONTAL);
        progRow.setGravity(Gravity.CENTER_VERTICAL);

        progressSeekBar = new SeekBar(this);
        progressSeekBar.setMax(100);
        progRow.addView(progressSeekBar, new LinearLayout.LayoutParams(0, dp(28), 1f));

        progressText = new TextView(this);
        progressText.setText("—");
        progressText.setTextColor(C_MUTED);
        progressText.setTextSize(11);
        progressText.setTypeface(Typeface.DEFAULT_BOLD);
        LinearLayout.LayoutParams ptlp = new LinearLayout.LayoutParams(-2, -2);
        ptlp.setMargins(dp(10), 0, 0, 0);
        progRow.addView(progressText, ptlp);
        card.addView(progRow);

        // Przyciski sterowania — cztery jednakowe, równo rozłożone (symetryczne)
        LinearLayout controls = new LinearLayout(this);
        controls.setOrientation(LinearLayout.HORIZONTAL);
        controls.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams clp = new LinearLayout.LayoutParams(-1, -2);
        clp.setMargins(0, dp(16), 0, 0);

        Button prevBtn = controlBtn("⏮");
        playPauseButton = playPauseBtn();
        Button nextBtn = controlBtn("⏭");
        Button stopBtn = stopButton();

        // Każdy przycisk w komórce z wagą 1 → idealnie równe odstępy i pozycje
        controls.addView(ctrlCell(prevBtn));
        controls.addView(ctrlCell(playPauseButton));
        controls.addView(ctrlCell(nextBtn));
        controls.addView(ctrlCell(stopBtn));
        card.addView(controls, clp);

        // Tempo
        LinearLayout rateRow = new LinearLayout(this);
        rateRow.setOrientation(LinearLayout.HORIZONTAL);
        rateRow.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout.LayoutParams rlp = new LinearLayout.LayoutParams(-1, -2);
        rlp.setMargins(0, dp(14), 0, 0);

        TextView rateLabel = new TextView(this);
        rateLabel.setText("Tempo");
        rateLabel.setTextColor(C_MUTED);
        rateLabel.setTextSize(11);
        rateLabel.setPadding(0, 0, dp(10), 0);
        rateRow.addView(rateLabel);

        rateSeekBar = new SeekBar(this);
        rateSeekBar.setMax(120);
        rateSeekBar.setProgress(32);
        rateRow.addView(rateSeekBar, new LinearLayout.LayoutParams(0, -2, 1f));

        rateText = new TextView(this);
        rateText.setText(String.format(Locale.US, "%.2fx", DEF_RATE));
        rateText.setTextColor(C_MUTED);
        rateText.setTextSize(11);
        rateText.setTypeface(Typeface.DEFAULT_BOLD);
        LinearLayout.LayoutParams rtlp = new LinearLayout.LayoutParams(-2, -2);
        rtlp.setMargins(dp(10), 0, 0, 0);
        rateRow.addView(rateText, rtlp);
        card.addView(rateRow, rlp);

        // Zdarzenia
        prevBtn.setOnClickListener(v -> goToPrevChunk());
        playPauseButton.setOnClickListener(v -> handlePlayPause());
        nextBtn.setOnClickListener(v -> goToNextChunk());
        stopBtn.setOnClickListener(v -> stopReading());

        progressSeekBar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar sb, int p, boolean fromUser) {}
            @Override public void onStartTrackingTouch(SeekBar sb) {}
            @Override
            public void onStopTrackingTouch(SeekBar sb) {
                if (!currentChunks.isEmpty()) {
                    int target = (int)(sb.getProgress() / 100f * (currentChunks.size() - 1));
                    jumpToChunk(Math.max(0, Math.min(target, currentChunks.size() - 1)));
                }
            }
        });

        rateSeekBar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar sb, int progress, boolean fromUser) {
                speechRate = 0.6f + (progress / 100f);
                rateText.setText(String.format(Locale.US, "%.2fx", speechRate));
                applySpeechRate();
            }
            @Override public void onStartTrackingTouch(SeekBar sb) {}
            @Override public void onStopTrackingTouch(SeekBar sb) { restartCurrentChunkWithNewRate(); }
        });

        return card;
    }

    // ── Sekcja ustawień (zwijana) ─────────────────────────────────────────

    private Button buildSettingsToggle() {
        Button toggle = new Button(this);
        toggle.setText("Głos i język  ▾");
        toggle.setAllCaps(false);
        toggle.setTextSize(12);
        toggle.setTypeface(Typeface.DEFAULT_BOLD);
        toggle.setTextColor(C_MUTED);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            toggle.setLetterSpacing(0.04f);
        }
        toggle.setBackground(null);
        toggle.setGravity(Gravity.START | Gravity.CENTER_VERTICAL);
        toggle.setPadding(dp(2), dp(8), dp(4), dp(8));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
        lp.setMargins(0, 0, 0, dp(4));
        toggle.setLayoutParams(lp);
        toggle.setOnClickListener(v -> {
            settingsVisible = !settingsVisible;
            settingsPanel.setVisibility(settingsVisible ? View.VISIBLE : View.GONE);
            toggle.setText(settingsVisible ? "Głos i język  ▴" : "Głos i język  ▾");
        });
        return toggle;
    }

    private LinearLayout buildSettingsPanel() {
        LinearLayout panel = new LinearLayout(this);
        panel.setOrientation(LinearLayout.VERTICAL);
        panel.setPadding(dp(16), dp(14), dp(16), dp(16));
        panel.setBackground(mkRound(C_SURFACE, C_BORDER, 20));

        LinearLayout.LayoutParams spinnerLp = new LinearLayout.LayoutParams(-1, dp(50));
        LinearLayout.LayoutParams spacedLp  = new LinearLayout.LayoutParams(-1, -2);
        spacedLp.setMargins(0, dp(12), 0, 0);

        panel.addView(sectionLabel("JĘZYK"));
        languageSpinner = styledSpinner(new String[]{"Automatycznie", "Polski", "English"});
        panel.addView(languageSpinner, spinnerLp);

        panel.addView(sectionLabel("GŁOS"), spacedLp);
        voiceSpinner = styledSpinner(new String[]{});
        panel.addView(voiceSpinner, spinnerLp);

        LinearLayout.LayoutParams spacedLp2 = new LinearLayout.LayoutParams(-1, -2);
        spacedLp2.setMargins(0, dp(12), 0, 0);
        panel.addView(sectionLabel("TŁUMACZENIE"), spacedLp2);
        translateSpinner = styledSpinner(TRANSLATE_LANGS);
        panel.addView(translateSpinner, spinnerLp);

        TextView translateHint = new TextView(this);
        translateHint.setText("Przy pierwszym użyciu pobierany jest model (~30 MB na parę języków).");
        translateHint.setTextColor(C_MUTED);
        translateHint.setTextSize(10);
        translateHint.setPadding(dp(2), dp(4), 0, 0);
        panel.addView(translateHint);

        languageSpinner.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(AdapterView<?> p, View v, int pos, long id) {
                String newCode = pos == 1 ? "pl" : pos == 2 ? "en" : "auto";
                if (newCode.equals(selectedLanguageCode)) return;
                selectedLanguageCode = newCode;
                selectedVoiceName = "";
                if (!allVoices.isEmpty()) refreshVoiceSpinner();
            }
            @Override public void onNothingSelected(AdapterView<?> p) { selectedLanguageCode = "auto"; }
        });

        translateSpinner.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(AdapterView<?> p, View v, int pos, long id) {
                translateEnabled = pos > 0;
                translateTargetLang = pos > 0 ? TRANSLATE_CODES[pos] : "pl";
                if (mlTranslator != null) { mlTranslator.close(); mlTranslator = null; }
                getSharedPreferences(PREFS, MODE_PRIVATE).edit().putInt(PREF_TRANSLATE, pos).apply();
            }
            @Override public void onNothingSelected(AdapterView<?> p) { translateEnabled = false; }
        });

        return panel;
    }

    // ════════════════════════════════════════════════════════════════════════
    //  Fabryki widgetów
    // ════════════════════════════════════════════════════════════════════════

    private Button primaryBtn(String text) {
        Button btn = new Button(this);
        btn.setText(text);
        btn.setAllCaps(false);
        btn.setTextSize(13);
        btn.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        btn.setTextColor(C_ON_PRIMARY);
        btn.setBackground(mkRound(C_PRIMARY, 0, 12));
        btn.setPadding(dp(14), 0, dp(14), 0);
        return btn;
    }

    private Button secondaryBtn(String text) {
        Button btn = new Button(this);
        btn.setText(text);
        btn.setAllCaps(false);
        btn.setTextSize(13);
        btn.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        btn.setTextColor(C_TEXT);
        btn.setBackground(mkRound(C_SURFACE2, C_BORDER, 12));
        btn.setPadding(dp(14), 0, dp(14), 0);
        return btn;
    }

    private Button ghostBtn(String text) {
        Button btn = new Button(this);
        btn.setText(text);
        btn.setAllCaps(false);
        btn.setTextSize(12);
        btn.setTextColor(C_MUTED);
        btn.setBackground(null);
        btn.setMinHeight(0);
        btn.setMinimumHeight(0);
        btn.setPadding(dp(8), 0, 0, 0);
        return btn;
    }

    private LinearLayout.LayoutParams actionBtnLp() {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-2, dp(46));
        lp.setMargins(dp(8), 0, 0, 0);
        return lp;
    }

    /** Rozmiar (średnica) każdego przycisku sterowania — jednakowy dla wszystkich. */
    private static final int CTRL_SIZE = 60;

    /** Owija przycisk w komórkę z wagą 1, wyśrodkowując go — równe odstępy i symetria. */
    private FrameLayout ctrlCell(Button btn) {
        FrameLayout cell = new FrameLayout(this);
        cell.setLayoutParams(new LinearLayout.LayoutParams(0, -2, 1f));
        FrameLayout.LayoutParams blp = new FrameLayout.LayoutParams(dp(CTRL_SIZE), dp(CTRL_SIZE));
        blp.gravity = Gravity.CENTER;
        cell.addView(btn, blp);
        return cell;
    }

    private Button controlBtn(String text) {
        Button btn = new Button(this);
        btn.setText(text);
        btn.setTextSize(22);
        btn.setAllCaps(false);
        btn.setTextColor(C_PRIMARY);
        btn.setBackground(mkRound(isDarkMode() ? C_SURFACE : C_BG, C_BORDER, CTRL_SIZE / 2));
        btn.setPadding(0, 0, 0, 0);
        btn.setStateListAnimator(null);
        return btn;
    }

    private Button playPauseBtn() {
        Button btn = new Button(this);
        btn.setText("▶");
        btn.setTextSize(26);
        btn.setAllCaps(false);
        btn.setTextColor(C_ON_PRIMARY);
        btn.setBackground(mkRound(C_PRIMARY, 0, CTRL_SIZE / 2));
        btn.setPadding(0, 0, 0, 0);
        btn.setStateListAnimator(null);
        return btn;
    }

    private Button stopButton() {
        Button btn = new Button(this);
        btn.setText("⏹");
        btn.setTextSize(20);
        btn.setAllCaps(false);
        btn.setTextColor(C_DANGER);
        btn.setBackground(mkRound(C_DANGER_BG, C_DANGER, CTRL_SIZE / 2));
        btn.setPadding(0, 0, 0, 0);
        btn.setStateListAnimator(null);
        return btn;
    }

    private Spinner styledSpinner(String[] items) {
        Spinner spinner = new Spinner(this);
        spinner.setBackground(mkRound(C_SURFACE2, C_BORDER, 12));
        spinner.setPadding(dp(12), 0, dp(12), 0);
        if (items.length > 0) {
            ArrayAdapter<String> adapter = new ArrayAdapter<>(this,
                android.R.layout.simple_spinner_item, items);
            adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
            spinner.setAdapter(adapter);
        }
        return spinner;
    }

    private TextView sectionLabel(String text) {
        TextView tv = new TextView(this);
        tv.setText(text);
        tv.setTextColor(C_MUTED);
        tv.setTextSize(10);
        tv.setTypeface(Typeface.DEFAULT_BOLD);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            tv.setLetterSpacing(0.12f);
        }
        tv.setPadding(0, 0, 0, dp(4));
        return tv;
    }

    // ════════════════════════════════════════════════════════════════════════
    //  Helpers UI
    // ════════════════════════════════════════════════════════════════════════

    private LinearLayout surfaceCard() {
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setPadding(dp(16), dp(14), dp(16), dp(14));
        card.setBackground(mkRound(C_SURFACE, C_BORDER, 20));
        return card;
    }

    private GradientDrawable mkRound(int fill, int stroke, int radiusDp) {
        GradientDrawable d = new GradientDrawable();
        d.setColor(fill);
        d.setCornerRadius(dp(radiusDp));
        if (stroke != 0) d.setStroke(dp(1), stroke);
        return d;
    }

    private LinearLayout.LayoutParams mbottom(int bottom) {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
        lp.setMargins(0, 0, 0, bottom);
        return lp;
    }

    private void root_add_with_bottom(LinearLayout parent, View child, int bottom) {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
        lp.setMargins(0, 0, 0, bottom);
        parent.addView(child, lp);
    }

    private int dp(int v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }

    // ════════════════════════════════════════════════════════════════════════
    //  WebView
    // ════════════════════════════════════════════════════════════════════════

    private void configureWebView() {
        WebSettings s = webView.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        webView.setWebViewClient(new WebViewClient());
        webView.setWebChromeClient(new android.webkit.WebChromeClient() {
            @Override
            public void onProgressChanged(WebView view, int newProgress) {
                loadingBar.setProgress(newProgress);
                loadingBar.setVisibility(newProgress >= 100 ? View.GONE : View.VISIBLE);
            }
        });
    }

    // ════════════════════════════════════════════════════════════════════════
    //  URL i Reader Mode
    // ════════════════════════════════════════════════════════════════════════

    private void loadUrlFromInput() {
        String url = normalizeUrl(urlInput.getText().toString());
        if (url == null) { setStatus("Wpisz adres strony."); return; }
        webView.loadUrl(url);
        setStatus("Ładuję stronę…");
    }

    private void extractAndRead() {
        String url = normalizeUrl(urlInput.getText().toString());
        if (url == null) { setStatus("Wpisz adres strony."); return; }
        urlInput.setText(url);
        setStatus("Ładuję stronę…");
        webView.setWebViewClient(new WebViewClient() {
            @Override
            public void onPageFinished(WebView view, String u) {
                applyReaderMode();
                webView.setWebViewClient(new WebViewClient());
            }
        });
        webView.loadUrl(url);
    }

    private void applyReaderMode() {
        setStatus("Ekstrahuję artykuł…");
        String js = "(function(){"
            + "var sel=['article','[role=\"main\"]','main','.article-body','.article-content',"
            + "'.post-content','.entry-content','.story-body','#article-body','#content-body','.body-copy'];"
            + "for(var i=0;i<sel.length;i++){"
            + "  var el=document.querySelector(sel[i]);"
            + "  if(el){var t=el.innerText.trim();if(t.length>300)return t;}"
            + "}"
            + "var best=null,bestScore=0;"
            + "document.querySelectorAll('div,section').forEach(function(el){"
            + "  var text=el.innerText.trim();"
            + "  if(text.length<200)return;"
            + "  var cls=(el.className+' '+el.id).toLowerCase();"
            + "  if(/comment|sidebar|nav|footer|header|\\bad\\b|banner|widget|menu|cookie|popup|modal|overlay/.test(cls))return;"
            + "  var score=text.length/60+el.querySelectorAll('p').length*3;"
            + "  if(score>bestScore){bestScore=score;best=el;}"
            + "});"
            + "return best?best.innerText.trim():document.body.innerText.trim();"
            + "})()";
        webView.evaluateJavascript(js, value -> {
            String text = decodeJson(value);
            if (text.isEmpty()) {
                setStatus("Nie znaleziono tekstu. Strona może wymagać logowania.");
                return;
            }
            int originalLength = text.length();
            if (originalLength > MAX_EXTRACTED_TEXT) {
                text = text.substring(0, MAX_EXTRACTED_TEXT).trim();
                setStatus("Artykuł jest bardzo długi. Wczytano skróconą wersję.");
            }
            textInput.setText(text);
            if (originalLength <= MAX_EXTRACTED_TEXT) {
                setStatus("Artykuł gotowy.");
            }
            maybeTranslateAndSpeak(text);
        });
    }

    // ════════════════════════════════════════════════════════════════════════
    //  Intent
    // ════════════════════════════════════════════════════════════════════════

    private void handleIncomingIntent(Intent intent) {
        if (intent == null) return;
        String action = intent.getAction();
        CharSequence shared = null;
        if (Intent.ACTION_SEND.equals(action))
            shared = intent.getCharSequenceExtra(Intent.EXTRA_TEXT);
        else if (Intent.ACTION_PROCESS_TEXT.equals(action))
            shared = intent.getCharSequenceExtra(Intent.EXTRA_PROCESS_TEXT);
        if (shared == null) return;
        String value = shared.toString().trim();
        if (looksLikeUrl(value)) {
            urlInput.setText(value);
            extractAndRead();
        } else {
            textInput.setText(value);
            setStatus("Wczytano udostępniony tekst.");
            maybeTranslateAndSpeak(value);
        }
    }

    // ════════════════════════════════════════════════════════════════════════
    //  Odtwarzanie
    // ════════════════════════════════════════════════════════════════════════

    private void handlePlayPause() {
        if (currentChunks.isEmpty()) speak(textInput.getText().toString());
        else pauseOrResume();
    }

    private void goToPrevChunk() {
        if (currentChunks.isEmpty()) return;
        currentChunkIndex = Math.max(0, currentChunkIndex - 1);
        if (readingQueue && !paused) { tts.stop(); speakNextChunk(); }
        updateProgress();
    }

    private void goToNextChunk() {
        if (currentChunks.isEmpty()) return;
        currentChunkIndex = Math.min(currentChunks.size() - 1, currentChunkIndex + 1);
        if (readingQueue && !paused) { tts.stop(); speakNextChunk(); }
        updateProgress();
    }

    private void jumpToChunk(int index) {
        currentChunkIndex = index;
        if (readingQueue && !paused) { tts.stop(); speakNextChunk(); }
        else if (paused) tts.stop();
        updateProgress();
    }

    private void speakFromCursor() {
        String text = textInput.getText().toString();
        int start = Math.max(0, textInput.getSelectionStart());
        if (start >= text.length()) { setStatus("Ustaw kursor w tekście."); return; }
        speak(text.substring(start));
    }

    private void speak(String rawText) {
        String text = normalize(rawText);
        if (text.isEmpty()) { setStatus("Brak tekstu do czytania."); return; }
        if (!ttsReady)      { setStatus("TTS nie jest jeszcze gotowy."); return; }
        stopReading();
        textInput.setText(text);
        Locale locale = getSelectedLocale(text);
        Voice  voice  = getBestVoice(locale);
        if (voice != null) { tts.setVoice(voice); locale = voice.getLocale(); }
        else { tts.setLanguage(locale); }
        applySpeechRate();
        currentChunks = splitIntoChunks(text);
        currentChunkIndex = 0;
        readingQueue = true;
        paused = false;
        currentTitle = text.substring(0, Math.min(60, text.length())).replace("\n", " ").trim();
        updateProgress();
        speakNextChunk();
        PlayerService.update(this, currentTitle, getCurrentChunkPreview(), true, currentChunkIndex, currentChunks.size());
        setStatus("Czytam  •  " + locale.toLanguageTag()
            + "  •  " + String.format(Locale.US, "%.2fx", speechRate));
    }

    private void speakNextChunk() {
        if (!readingQueue || paused || currentChunkIndex >= currentChunks.size()) {
            if (currentChunkIndex >= currentChunks.size()) {
                readingQueue = false;
                setStatus("Koniec.");
                updatePlayPauseBtn();
                if (nowPlayingTitle   != null) nowPlayingTitle.setText("Koniec czytania");
                if (nowPlayingPreview != null) nowPlayingPreview.setText("");
                PlayerService.hide(this);
            }
            return;
        }
        applyVoiceAndRate();
        tts.speak(currentChunks.get(currentChunkIndex),
            TextToSpeech.QUEUE_FLUSH, null, "c-" + currentChunkIndex);
        PlayerService.update(this, currentTitle, getCurrentChunkPreview(), true, currentChunkIndex, currentChunks.size());
    }

    private void applyVoiceAndRate() {
        if (!ttsReady || tts == null) return;
        String text = (!currentChunks.isEmpty() && currentChunkIndex < currentChunks.size())
            ? currentChunks.get(currentChunkIndex)
            : (textInput != null ? textInput.getText().toString() : "");
        Locale locale = getSelectedLocale(text);
        Voice  voice  = getBestVoice(locale);
        if (voice != null) tts.setVoice(voice);
        else               tts.setLanguage(locale);
        tts.setSpeechRate(speechRate);
    }

    private void applySpeechRate() {
        if (ttsReady && tts != null) tts.setSpeechRate(speechRate);
    }

    private void restartCurrentChunkWithNewRate() {
        if (!ttsReady || !readingQueue || paused || currentChunks.isEmpty()) return;
        applySpeechRate();
        tts.stop();
        speakNextChunk();
    }

    private void pauseOrResume() {
        if (paused) {
            paused = false;
            readingQueue = true;
            speakNextChunk();
            setStatus("Wznowiono.");
        } else {
            paused = true;
            tts.stop();
            setStatus("Pauza.");
        }
        updatePlayPauseBtn();
        PlayerService.update(this, currentTitle, getCurrentChunkPreview(), !paused, currentChunkIndex, currentChunks.size());
    }

    private void stopReading() {
        readingQueue = false;
        paused = false;
        currentChunks = new ArrayList<>();
        currentChunkStarts.clear();
        currentChunkEnds.clear();
        currentChunkIndex = 0;
        if (tts != null) tts.stop();
        clearChunkHighlight();
        updateProgress();
        PlayerService.hide(this);
    }

    private void updateProgress() {
        if (progressText == null || progressSeekBar == null) return;
        if (currentChunks.isEmpty()) {
            progressText.setText("—");
            progressSeekBar.setProgress(0);
            clearChunkHighlight();
            if (nowPlayingTitle   != null) nowPlayingTitle.setText("Gotowy do czytania");
            if (nowPlayingPreview != null) nowPlayingPreview.setText("");
        } else {
            int total = currentChunks.size();
            progressText.setText((currentChunkIndex + 1) + " / " + total);
            int pct = total <= 1 ? 0 : (int)(currentChunkIndex * 100f / (total - 1));
            progressSeekBar.setProgress(pct);
            applyCurrentChunkHighlight();
            if (nowPlayingTitle != null) {
                nowPlayingTitle.setText(currentTitle.isEmpty() ? "Czyta…" : currentTitle);
            }
            if (nowPlayingPreview != null) {
                nowPlayingPreview.setText(getCurrentChunkPreview());
            }
        }
        updatePlayPauseBtn();
    }

    private void updatePlayPauseBtn() {
        if (playPauseButton != null)
            playPauseButton.setText(readingQueue && !paused ? "⏸" : "▶");
    }

    private String getCurrentChunkPreview() {
        if (currentChunks.isEmpty() || currentChunkIndex < 0 || currentChunkIndex >= currentChunks.size())
            return "";
        String chunk = currentChunks.get(currentChunkIndex).replace("\n", " ").trim();
        return chunk.length() <= 90 ? chunk : chunk.substring(0, 90).trim() + "…";
    }

    private void clearChunkHighlight() {
        Editable e = textInput != null ? textInput.getText() : null;
        if (e == null) return;
        if (currentChunkSpan != null) e.removeSpan(currentChunkSpan);
        currentChunkSpan = null;
    }

    private void applyCurrentChunkHighlight() {
        Editable e = textInput != null ? textInput.getText() : null;
        if (e == null) return;
        clearChunkHighlight();
        if (currentChunkIndex < 0 || currentChunkIndex >= currentChunkStarts.size()) return;
        int start = Math.max(0, Math.min(currentChunkStarts.get(currentChunkIndex), e.length()));
        int end   = Math.max(start, Math.min(currentChunkEnds.get(currentChunkIndex), e.length()));
        if (end <= start) return;
        int bg = isDarkMode() ? 0xAAD4A020 : 0x66B8820A;
        currentChunkSpan = new BackgroundColorSpan(bg);
        e.setSpan(currentChunkSpan, start, end, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE);
        textInput.setSelection(end);
    }

    // ════════════════════════════════════════════════════════════════════════
    //  Głosy i locale
    // ════════════════════════════════════════════════════════════════════════

    private void loadVoices() {
        Set<Voice> available = tts.getVoices();
        allVoices.clear();
        if (available != null) {
            List<Voice> sorted = new ArrayList<>(available);
            sorted.sort(Comparator.comparing(v -> v.getLocale().toLanguageTag() + v.getName()));
            for (Voice v : sorted) {
                if (isSupportedLang(v)) allVoices.add(v);
            }
        }
        refreshVoiceSpinner();
    }

    private void refreshVoiceSpinner() {
        voices.clear();
        voiceLabels.clear();
        voiceLabels.add("Automatycznie");
        for (Voice v : allVoices) {
            String lang = v.getLocale().getLanguage();
            boolean include = "auto".equals(selectedLanguageCode)
                || ("pl".equals(selectedLanguageCode) && "pl".equalsIgnoreCase(lang))
                || ("en".equals(selectedLanguageCode) && "en".equalsIgnoreCase(lang));
            if (include) {
                voices.add(v);
                voiceLabels.add(v.getName() + " (" + v.getLocale().toLanguageTag() + ")");
            }
        }
        ArrayAdapter<String> adapter = new ArrayAdapter<>(this,
            android.R.layout.simple_spinner_item, voiceLabels);
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        if (voiceSpinner == null) return;
        voiceSpinner.setAdapter(adapter);
        voiceSpinner.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(AdapterView<?> p, View v, int pos, long id) {
                Voice picked = pos > 0 ? voices.get(pos - 1) : null;
                String newName = picked != null ? picked.getName() : "";
                if (newName.equals(selectedVoiceName)) return;
                selectedVoiceName = newName;
                if (ttsReady && tts != null) {
                    if (picked != null) tts.setVoice(picked);
                    else tts.setLanguage(detectLocale(
                        textInput != null ? textInput.getText().toString() : ""));
                    if (!currentChunks.isEmpty()) {
                        tts.stop();
                        paused = false;
                        readingQueue = true;
                        speakNextChunk();
                        updatePlayPauseBtn();
                    }
                }
            }
            @Override public void onNothingSelected(AdapterView<?> p) { selectedVoiceName = ""; }
        });
    }

    private boolean isSupportedLang(Voice v) {
        String lang = v.getLocale().getLanguage();
        return "pl".equalsIgnoreCase(lang) || "en".equalsIgnoreCase(lang);
    }

    private Voice getBestVoice(Locale locale) {
        for (Voice v : voices) if (v.getName().equals(selectedVoiceName)) return v;
        for (Voice v : voices) if (v.getLocale().toLanguageTag().equalsIgnoreCase(locale.toLanguageTag())) return v;
        for (Voice v : voices) if (v.getLocale().getLanguage().equalsIgnoreCase(locale.getLanguage())) return v;
        return null;
    }

    private Locale getSelectedLocale(String text) {
        // Konkretny głos wybrany — użyj jego locale zamiast wykrywać
        if (!selectedVoiceName.isEmpty()) {
            for (Voice v : voices) {
                if (v.getName().equals(selectedVoiceName)) return v.getLocale();
            }
        }
        if ("pl".equals(selectedLanguageCode)) return new Locale("pl", "PL");
        if ("en".equals(selectedLanguageCode)) return Locale.US;
        return detectLocale(text);
    }

    private Locale detectLocale(String text) {
        String sample = " " + text.substring(0, Math.min(6000, text.length())).toLowerCase(Locale.ROOT) + " ";
        int pl = countMarkers(sample, new String[]{
            " ze ", " nie ", " jest ", " się ", " na ", " do ", " oraz ", " który ",
            "ą", "ć", "ę", "ł", "ń", "ó", "ś", "ź", "ż"});
        int en = countMarkers(sample, new String[]{
            " the ", " and ", " is ", " are ", " with ", " from ", " that ", " this ", " you ", " for "});
        return en > pl ? Locale.US : new Locale("pl", "PL");
    }

    private int countMarkers(String sample, String[] markers) {
        int score = 0;
        for (String m : markers) if (sample.contains(m)) score++;
        return score;
    }

    // ════════════════════════════════════════════════════════════════════════
    //  Przetwarzanie tekstu
    // ════════════════════════════════════════════════════════════════════════

    private List<String> splitIntoChunks(String text) {
        List<String> chunks = new ArrayList<>();
        currentChunkStarts.clear();
        currentChunkEnds.clear();
        String[] parts = text.split("(?<=[.!?;:])\\s+");
        StringBuilder current = new StringBuilder();
        int currentStart = -1, currentEnd = -1, searchFrom = 0;
        for (String part : parts) {
            String t = part.trim();
            if (t.isEmpty()) continue;
            int partStart = text.indexOf(t, searchFrom);
            if (partStart < 0) partStart = searchFrom;
            int partEnd = Math.min(text.length(), partStart + t.length());
            searchFrom = partEnd;
            if (current.length() > 0 && current.length() + t.length() + 1 > MAX_CHUNK) {
                chunks.add(current.toString());
                currentChunkStarts.add(currentStart);
                currentChunkEnds.add(currentEnd);
                current.setLength(0);
                currentStart = -1;
            }
            if (current.length() == 0) currentStart = partStart;
            if (current.length() > 0) current.append(' ');
            current.append(t);
            currentEnd = partEnd;
        }
        if (current.length() > 0) {
            chunks.add(current.toString());
            currentChunkStarts.add(currentStart);
            currentChunkEnds.add(currentEnd);
        }
        return chunks;
    }

    private String normalize(String text) {
        return text == null ? "" : text.replaceAll("\\s+", " ").trim();
    }

    private String decodeJson(String value) {
        if (value == null || value.equals("null")) return "";
        try {
            return new JSONObject("{\"v\":" + value + "}").getString("v").trim();
        } catch (Exception e) {
            return value.replace("\\n", "\n").replace("\\\"", "\"").trim();
        }
    }

    private String normalizeUrl(String raw) {
        if (raw == null) return null;
        String url = raw.trim();
        if (url.isEmpty()) return null;
        if (!url.startsWith("http://") && !url.startsWith("https://")) url = "https://" + url;
        return url;
    }

    private boolean looksLikeUrl(String value) {
        return value.startsWith("http://") || value.startsWith("https://")
            || Uri.parse(value).getHost() != null;
    }

    private void setStatus(String msg) {
        if (statusText != null) statusText.setText(msg);
    }

    // ════════════════════════════════════════════════════════════════════════
    //  Sprawdzanie aktualizacji z GitHub
    // ════════════════════════════════════════════════════════════════════════

    private void checkForUpdate() {
        if (GITHUB_OWNER.isEmpty() || GITHUB_REPO.isEmpty()) return;
        new Thread(() -> {
            try {
                java.net.URL url = new java.net.URL(
                    "https://api.github.com/repos/" + GITHUB_OWNER + "/" + GITHUB_REPO + "/releases/latest");
                java.net.HttpURLConnection conn = (java.net.HttpURLConnection) url.openConnection();
                conn.setConnectTimeout(6000);
                conn.setReadTimeout(6000);
                conn.setRequestProperty("Accept", "application/vnd.github.v3+json");
                if (conn.getResponseCode() != 200) { conn.disconnect(); return; }
                StringBuilder sb = new StringBuilder();
                try (java.io.BufferedReader r = new java.io.BufferedReader(
                        new java.io.InputStreamReader(conn.getInputStream()))) {
                    String line;
                    while ((line = r.readLine()) != null) sb.append(line);
                }
                conn.disconnect();
                JSONObject json   = new JSONObject(sb.toString());
                String latestTag  = json.optString("tag_name", "").replaceAll("[^0-9.]", "");
                String releaseUrl = json.optString("html_url", "");
                String apkUrl     = findApkAssetUrl(json);
                String currentVer = getPackageManager().getPackageInfo(getPackageName(), 0).versionName;
                if (!latestTag.isEmpty() && isNewerVersion(latestTag, currentVer)) {
                    runOnUiThread(() -> {
                        updateButton.setText("↑ Nowa wersja " + latestTag);
                        latestReleaseUrl = releaseUrl;
                        updateButton.setText("Pobierz " + latestTag);
                        updateButton.setVisibility(View.VISIBLE);
                        updateButton.setOnClickListener(v -> startUpdate(apkUrl, releaseUrl, latestTag));
                    });
                }
            } catch (Exception ignored) {}
        }).start();
    }

    private String findApkAssetUrl(JSONObject releaseJson) {
        JSONArray assets = releaseJson.optJSONArray("assets");
        if (assets == null) return "";
        for (int i = 0; i < assets.length(); i++) {
            JSONObject asset = assets.optJSONObject(i);
            if (asset == null) continue;
            String name = asset.optString("name", "");
            if (!name.toLowerCase(Locale.US).endsWith(".apk")) continue;
            return asset.optString("browser_download_url", "");
        }
        return "";
    }

    private void startUpdate(String apkUrl, String releaseUrl, String version) {
        if (apkUrl == null || apkUrl.isEmpty()) {
            if (releaseUrl != null && !releaseUrl.isEmpty()) {
                startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(releaseUrl)));
            }
            return;
        }
        try {
            DownloadManager dm = (DownloadManager) getSystemService(DOWNLOAD_SERVICE);
            if (dm == null) {
                startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(releaseUrl)));
                return;
            }
            String fileName = "CzytanieStrony-v" + version + ".apk";
            DownloadManager.Request request = new DownloadManager.Request(Uri.parse(apkUrl));
            request.setNotificationVisibility(
                DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED);
            request.setTitle("Czytnik strony " + version);
            request.setDescription("Pobieranie aktualizacji");
            request.setMimeType("application/vnd.android.package-archive");
            request.setDestinationInExternalFilesDir(this, Environment.DIRECTORY_DOWNLOADS, fileName);
            updateDownloadId = dm.enqueue(request);
            setStatus("Pobieram aktualizacje " + version + "...");
        } catch (Exception e) {
            if (releaseUrl != null && !releaseUrl.isEmpty()) {
                startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(releaseUrl)));
            }
        }
    }

    private void handleDownloadedUpdate(long downloadId) {
        DownloadManager dm = (DownloadManager) getSystemService(DOWNLOAD_SERVICE);
        if (dm == null) return;
        DownloadManager.Query query = new DownloadManager.Query().setFilterById(downloadId);
        try (Cursor cursor = dm.query(query)) {
            if (cursor == null || !cursor.moveToFirst()) return;
            int status = cursor.getInt(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS));
            if (status == DownloadManager.STATUS_SUCCESSFUL) {
                installDownloadedUpdate(dm, downloadId);
                return;
            }
            if (status == DownloadManager.STATUS_FAILED) {
                setStatus("Nie udalo sie pobrac aktualizacji.");
                if (!latestReleaseUrl.isEmpty()) {
                    startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(latestReleaseUrl)));
                }
            }
        } catch (Exception ignored) {}
    }

    private void installDownloadedUpdate(DownloadManager dm, long downloadId) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
            && !getPackageManager().canRequestPackageInstalls()) {
            Intent settingsIntent = new Intent(
                Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                Uri.parse("package:" + getPackageName()));
            startActivity(settingsIntent);
            setStatus("Zezwol na instalacje z tej aplikacji i kliknij aktualizacje ponownie.");
            return;
        }
        Uri apkUri = dm.getUriForDownloadedFile(downloadId);
        if (apkUri == null) {
            if (!latestReleaseUrl.isEmpty()) {
                startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(latestReleaseUrl)));
            }
            return;
        }
        Intent installIntent = new Intent(Intent.ACTION_VIEW);
        installIntent.setDataAndType(apkUri, "application/vnd.android.package-archive");
        installIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_GRANT_READ_URI_PERMISSION);
        startActivity(installIntent);
        setStatus("Aktualizacja pobrana. Otwieram instalator.");
    }

    private boolean isNewerVersion(String latest, String current) {
        try {
            int[] l = parseVer(latest), c = parseVer(current);
            for (int i = 0; i < Math.min(l.length, c.length); i++) {
                if (l[i] > c[i]) return true;
                if (l[i] < c[i]) return false;
            }
            return l.length > c.length;
        } catch (Exception e) { return false; }
    }

    private int[] parseVer(String v) {
        String[] parts = v.split("\\.");
        int[] r = new int[parts.length];
        for (int i = 0; i < parts.length; i++) r[i] = Integer.parseInt(parts[i]);
        return r;
    }

    // ════════════════════════════════════════════════════════════════════════
    //  Kafelek szybkich ustawień
    // ════════════════════════════════════════════════════════════════════════

    // ════════════════════════════════════════════════════════════════════════
    //  Tłumaczenie ML Kit
    // ════════════════════════════════════════════════════════════════════════

    private void maybeTranslateAndSpeak(String text) {
        if (!translateEnabled) { speak(text); return; }

        Locale detected  = detectLocale(text);
        String sourceLang = "pl".equalsIgnoreCase(detected.getLanguage())
            ? TranslateLanguage.POLISH : TranslateLanguage.ENGLISH;

        if (sourceLang.equals(translateTargetLang)) { speak(text); return; }

        TranslatorOptions options = new TranslatorOptions.Builder()
            .setSourceLanguage(sourceLang)
            .setTargetLanguage(translateTargetLang)
            .build();

        if (mlTranslator != null) { mlTranslator.close(); }
        mlTranslator = Translation.getClient(options);

        setStatus("Sprawdzam model tłumaczenia…");
        mlTranslator.downloadModelIfNeeded()
            .addOnSuccessListener(unused -> {
                setStatus("Tłumaczę…");
                translateChunked(text, mlTranslator);
            })
            .addOnFailureListener(e -> {
                setStatus("Błąd pobierania modelu. Czytam bez tłumaczenia.");
                speak(text);
            });
    }

    private void translateChunked(String original, Translator translator) {
        List<String> parts   = splitForTranslation(original);
        List<String> results = new ArrayList<>(Collections.nCopies(parts.size(), ""));
        AtomicInteger done   = new AtomicInteger(0);
        AtomicBoolean failed = new AtomicBoolean(false);

        for (int i = 0; i < parts.size(); i++) {
            final int idx = i;
            translator.translate(parts.get(i))
                .addOnSuccessListener(translated -> {
                    if (failed.get()) return;
                    results.set(idx, translated);
                    if (done.incrementAndGet() == parts.size()) {
                        String joined = TextUtils.join("\n\n", results);
                        runOnUiThread(() -> {
                            textInput.setText(joined);
                            speak(joined);
                        });
                    }
                })
                .addOnFailureListener(e -> {
                    if (failed.compareAndSet(false, true)) {
                        runOnUiThread(() -> {
                            setStatus("Błąd tłumaczenia. Czytam oryginał.");
                            speak(original);
                        });
                    }
                });
        }
    }

    private List<String> splitForTranslation(String text) {
        final int MAX_CHUNK_SIZE = 4000;
        List<String> chunks = new ArrayList<>();
        String[] paragraphs = text.split("\n\n+");
        StringBuilder current = new StringBuilder();

        for (String para : paragraphs) {
            if (current.length() > 0 && current.length() + para.length() + 2 > MAX_CHUNK_SIZE) {
                chunks.add(current.toString().trim());
                current.setLength(0);
            }
            if (current.length() > 0) current.append("\n\n");
            current.append(para);

            while (current.length() > MAX_CHUNK_SIZE) {
                int cut = current.lastIndexOf(". ", MAX_CHUNK_SIZE);
                if (cut < 100) cut = MAX_CHUNK_SIZE;
                chunks.add(current.substring(0, cut + 1).trim());
                current.delete(0, cut + 1);
                if (current.length() > 0 && current.charAt(0) == ' ')
                    current.deleteCharAt(0);
            }
        }
        if (current.length() > 0) chunks.add(current.toString().trim());
        return chunks.isEmpty() ? Collections.singletonList(text) : chunks;
    }

    private void requestTile() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
            setStatus("Dodaj kafelek ręcznie w edycji panelu.");
            return;
        }
        StatusBarManager sbm = getSystemService(StatusBarManager.class);
        if (sbm == null) { setStatus("Nie można otworzyć panelu kafelek."); return; }
        ComponentName cn   = new ComponentName(this, ReaderTileService.class);
        Icon          icon = Icon.createWithResource(this, R.drawable.ic_reader_tile);
        sbm.requestAddTileService(cn, "Czytnik", icon, getMainExecutor(), result -> {
            if (result == StatusBarManager.TILE_ADD_REQUEST_RESULT_TILE_ADDED)
                setStatus("Kafelek dodany.");
            else if (result == StatusBarManager.TILE_ADD_REQUEST_RESULT_TILE_ALREADY_ADDED)
                setStatus("Kafelek już jest w panelu.");
            else
                setStatus("Dodaj kafelek ręcznie w edycji panelu.");
        });
    }
}
