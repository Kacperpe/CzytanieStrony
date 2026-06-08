package pl.local.czytnikstrony;

import android.app.Activity;
import android.app.AlertDialog;
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
import android.provider.OpenableColumns;
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

import com.google.mlkit.common.model.DownloadConditions;
import com.google.mlkit.common.model.RemoteModelManager;
import com.google.mlkit.nl.languageid.LanguageIdentification;
import com.google.mlkit.nl.languageid.LanguageIdentifier;
import com.google.mlkit.nl.translate.TranslateLanguage;
import com.google.mlkit.nl.translate.TranslateRemoteModel;
import com.google.mlkit.nl.translate.Translation;
import com.google.mlkit.nl.translate.Translator;
import com.google.mlkit.nl.translate.TranslatorOptions;

import com.tom_roush.pdfbox.android.PDFBoxResourceLoader;
import com.tom_roush.pdfbox.pdmodel.PDDocument;
import com.tom_roush.pdfbox.text.PDFTextStripper;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedInputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

public class MainActivity extends Activity implements TextToSpeech.OnInitListener {

    private static final int   MAX_CHUNK = 260;
    private static final int   MAX_EXTRACTED_TEXT = 120000;
    private static final int   REQ_PICK_FILE = 4711;
    private static final int   MAX_FILE_BYTES = 32 * 1024 * 1024;   // 32 MB limit na plik
    private static final float DEF_RATE      = 1.00f;
    private static final float RATE_MIN      = 0.50f;
    private static final float RATE_STEP     = 0.25f;
    private static final int   RATE_STEPS    = 6;   // 0.50 · 0.75 · 1.00 · 1.25 · 1.50 · 1.75 · 2.00

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
    private TextView     settingsStatus;
    private Button       favVoiceBtn;
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
    private final java.util.Map<String,String> voiceDisplayNames = new java.util.HashMap<>();
    private final Set<String>    favoriteVoices = new java.util.HashSet<>();
    private String  selectedLanguageCode = "auto";
    private String  selectedVoiceName    = "";

    // Pule wymyślonych imion lektorów — przydzielane kolejnym realnym głosom.
    private static final String[] PL_NAMES = {
        "Ola", "Marek", "Kasia", "Piotr", "Zofia", "Jakub", "Ania", "Tomek", "Ewa", "Bartek"
    };
    private static final String[] EN_NAMES = {
        "Emma", "James", "Olivia", "Liam", "Sophie", "Noah", "Grace", "Oliver", "Mia", "Henry"
    };
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
    private static final String PREF_FAVORITES = "fav_voices";

    // ── Biblioteka plików (pamięć podręczna z pozycją wznowienia) ─────────────
    private static final String PREF_RETENTION   = "retention_idx";
    private static final String PREF_STORAGE_CAP = "storage_cap_idx";
    private static final int[]    RETENTION_DAYS   = { 1, 2, 3, 7, FileLibrary.RETENTION_FOREVER };
    private static final String[] RETENTION_LABELS = { "1 dzień", "2 dni", "3 dni", "7 dni", "Na zawsze" };
    private static final long[]   STORAGE_CAPS     = {
        512L * 1024 * 1024, 1024L * 1024 * 1024, 2L * 1024 * 1024 * 1024, 5L * 1024 * 1024 * 1024
    };
    private static final String[] STORAGE_LABELS   = { "0,5 GB", "1 GB", "2 GB", "5 GB" };

    private LinearLayout libraryCard;
    private LinearLayout libraryList;
    private LinearLayout storageFileList;
    private TextView     storageUsageText;
    private Spinner      retentionSpinner;
    private Spinner      storageCapSpinner;

    // Bieżący plik z biblioteki + przekazywanie stanu do speak()
    private String  currentLibraryId    = "";
    private String  pendingLibraryTitle = null;   // import → zapis do biblioteki
    private String  pendingResumeId     = null;   // wznowienie z biblioteki (bez ponownego zapisu)
    private String  pendingResumeTitle  = null;
    private int     pendingStartChunk   = 0;
    private int     lastPersistedChunk  = -1;

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
        FileLibrary.enforce(this, storageCapBytes());
        refreshLibraryUi();
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
    protected void onResume() {
        super.onResume();
        refreshLibraryUi();
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
        setStatus(voiceCountSummary());
    }

    private String voiceCountSummary() {
        int pl = 0, en = 0;
        for (Voice v : allVoices) {
            String lang = v.getLocale().getLanguage();
            if ("pl".equalsIgnoreCase(lang)) pl++;
            else if ("en".equalsIgnoreCase(lang)) en++;
        }
        return "Gotowy  •  głosy PL: " + pl + ", EN: " + en;
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

        // ── Biblioteka ostatnich plików ──
        root.addView(buildLibraryCard(), mbottom(dp(10)));

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

        // ── Panel pamięci plików ──
        root.addView(buildStoragePanel(), mbottom(dp(14)));

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
        android.content.SharedPreferences sp = getSharedPreferences(PREFS, MODE_PRIVATE);
        int tIdx = sp.getInt(PREF_TRANSLATE, 0);
        if (translateSpinner != null && tIdx > 0 && tIdx < TRANSLATE_CODES.length) {
            translateSpinner.setSelection(tIdx);  // wywoła listener i ustawi stan
        }
        Set<String> fav = sp.getStringSet(PREF_FAVORITES, null);
        if (fav != null) { favoriteVoices.clear(); favoriteVoices.addAll(fav); }

        if (retentionSpinner != null)
            retentionSpinner.setSelection(sp.getInt(PREF_RETENTION, 2));     // domyślnie 3 dni
        if (storageCapSpinner != null)
            storageCapSpinner.setSelection(sp.getInt(PREF_STORAGE_CAP, 1));  // domyślnie 1 GB
    }

    private int retentionDays() {
        int idx = getSharedPreferences(PREFS, MODE_PRIVATE).getInt(PREF_RETENTION, 2);
        return RETENTION_DAYS[Math.max(0, Math.min(idx, RETENTION_DAYS.length - 1))];
    }

    private long storageCapBytes() {
        int idx = getSharedPreferences(PREFS, MODE_PRIVATE).getInt(PREF_STORAGE_CAP, 1);
        return STORAGE_CAPS[Math.max(0, Math.min(idx, STORAGE_CAPS.length - 1))];
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

        readBtn.setOnClickListener(v -> readSmart());
        urlInput.setOnEditorActionListener((v, actionId, event) -> { readSmart(); return true; });

        return card;
    }

    /**
     * Jeden przycisk „Czytaj": jeśli w polu adresu jest URL — pobiera stronę,
     * wyciąga artykuł i zaczyna czytać; jeśli pole adresu jest puste — czyta
     * tekst z pola tekstowego.
     */
    private void readSmart() {
        String url = urlInput != null ? urlInput.getText().toString().trim() : "";
        if (!url.isEmpty()) {
            extractAndRead();
        } else {
            maybeTranslateAndSpeak(textInput != null ? textInput.getText().toString() : "");
        }
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

        Button fileBtn    = secondaryBtn("📄 Wczytaj plik");
        btns.addView(fileBtn, actionBtnLp());
        Button cursorBtn  = secondaryBtn("Od kursora");
        btns.addView(cursorBtn, actionBtnLp());
        card.addView(btns, blp);

        clearBtn.setOnClickListener(v -> { textInput.setText(""); stopReading(); });
        fileBtn.setOnClickListener(v -> pickFile());
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

        // Zwarta grupa wyśrodkowana na środku panelu, z równymi odstępami
        controls.addView(prevBtn,         ctrlLp());
        controls.addView(playPauseButton, ctrlLp());
        controls.addView(nextBtn,         ctrlLp());
        controls.addView(stopBtn,         ctrlLp());
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
        rateSeekBar.setMax(RATE_STEPS);
        rateSeekBar.setProgress(rateToProgress(DEF_RATE));
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
                speechRate = progressToRate(progress);
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

        favVoiceBtn = secondaryBtn("☆ Oznacz jako ulubiony");
        LinearLayout.LayoutParams favLp = new LinearLayout.LayoutParams(-1, dp(44));
        favLp.setMargins(0, dp(8), 0, 0);
        panel.addView(favVoiceBtn, favLp);
        favVoiceBtn.setOnClickListener(v -> toggleFavoriteVoice());

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

        // Wstępne pobranie modelu offline
        Button downloadModelBtn = secondaryBtn("⬇  Pobierz model offline");
        LinearLayout.LayoutParams dmLp = new LinearLayout.LayoutParams(-1, dp(46));
        dmLp.setMargins(0, dp(10), 0, 0);
        panel.addView(downloadModelBtn, dmLp);

        settingsStatus = new TextView(this);
        settingsStatus.setText("");
        settingsStatus.setTextColor(C_PRIMARY);
        settingsStatus.setTextSize(11);
        settingsStatus.setPadding(dp(2), dp(6), 0, 0);
        panel.addView(settingsStatus);

        downloadModelBtn.setOnClickListener(v -> downloadSelectedModel(downloadModelBtn));

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

    /** Jednakowy rozmiar każdego guzika sterowania + równe boczne odstępy (grupa wyśrodkowana). */
    private LinearLayout.LayoutParams ctrlLp() {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(dp(CTRL_SIZE), dp(CTRL_SIZE));
        lp.setMargins(dp(8), 0, dp(8), 0);
        return lp;
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
    //  Wczytywanie plików (txt, md, pdf, docx, odt, rtf, html…)
    // ════════════════════════════════════════════════════════════════════════

    /** Otwiera systemowy wybór pliku z filtrem na obsługiwane typy dokumentów. */
    private void pickFile() {
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType("*/*");
        String[] mimes = {
            "text/*",
            "application/pdf",
            "application/msword",
            "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
            "application/vnd.oasis.opendocument.text",
            "application/rtf",
            "application/json",
            "application/xml"
        };
        intent.putExtra(Intent.EXTRA_MIME_TYPES, mimes);
        try {
            startActivityForResult(intent, REQ_PICK_FILE);
        } catch (Exception e) {
            setStatus("Brak aplikacji do wyboru pliku.");
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode != REQ_PICK_FILE) return;
        if (resultCode != RESULT_OK || data == null || data.getData() == null) return;
        loadFile(data.getData());
    }

    /** Wczytuje plik w tle, wyciąga z niego tekst i zaczyna czytać. */
    private void loadFile(Uri uri) {
        String name = queryDisplayName(uri);
        final String fileName = (name != null && !name.isEmpty()) ? name : "plik";
        setStatus("Wczytuję „" + fileName + "”…");
        new Thread(() -> {
            String text;
            try {
                text = extractTextFromUri(uri, fileName);
            } catch (Throwable t) {
                runOnUiThread(() -> setStatus("Nie udało się wczytać pliku."));
                return;
            }
            final String extracted = text == null ? "" : text.trim();
            runOnUiThread(() -> {
                if (extracted.isEmpty()) {
                    setStatus("Nie znaleziono tekstu w pliku „" + fileName + "”.");
                    return;
                }
                String t = extracted;
                if (t.length() > MAX_EXTRACTED_TEXT) {
                    t = t.substring(0, MAX_EXTRACTED_TEXT).trim();
                    setStatus("Plik jest bardzo długi — wczytano skróconą wersję.");
                } else {
                    setStatus("Wczytano: " + fileName);
                }
                textInput.setText(t);
                pendingLibraryTitle = fileName;   // speak() zapisze do biblioteki
                maybeTranslateAndSpeak(t);
            });
        }).start();
    }

    /** Pobiera czytelną nazwę pliku z dostawcy treści (do tytułu/typowania). */
    private String queryDisplayName(Uri uri) {
        try (Cursor c = getContentResolver().query(uri, null, null, null, null)) {
            if (c != null && c.moveToFirst()) {
                int idx = c.getColumnIndex(OpenableColumns.DISPLAY_NAME);
                if (idx >= 0) return c.getString(idx);
            }
        } catch (Exception ignored) {}
        String last = uri.getLastPathSegment();
        return last != null ? last : "";
    }

    /** Dobiera ekstraktor po rozszerzeniu / typie MIME. */
    private String extractTextFromUri(Uri uri, String fileName) throws Exception {
        String lower = fileName.toLowerCase(Locale.US);
        String mime  = getContentResolver().getType(uri);
        mime = mime == null ? "" : mime.toLowerCase(Locale.US);

        if (lower.endsWith(".pdf") || mime.equals("application/pdf"))
            return extractPdf(uri);
        if (lower.endsWith(".docx")
                || mime.contains("wordprocessingml"))
            return ooxmlToText(readZipEntry(uri, "word/document.xml"));
        if (lower.endsWith(".odt") || mime.contains("opendocument.text"))
            return odfToText(readZipEntry(uri, "content.xml"));
        if (lower.endsWith(".doc") || mime.equals("application/msword"))
            return extractLegacyDoc(uri);
        if (lower.endsWith(".rtf") || mime.contains("rtf"))
            return stripRtf(readPlainText(uri));
        if (lower.endsWith(".html") || lower.endsWith(".htm")
                || lower.endsWith(".xml") || mime.contains("html") || mime.contains("xml"))
            return stripTags(readPlainText(uri));

        // Domyślnie: zwykły tekst (txt, md, csv, json, log, kod…)
        return readPlainText(uri);
    }

    // ── PDF (PdfBox-Android) ──────────────────────────────────────────────────

    private String extractPdf(Uri uri) throws Exception {
        PDFBoxResourceLoader.init(getApplicationContext());
        try (InputStream in = getContentResolver().openInputStream(uri);
             PDDocument doc = PDDocument.load(in)) {
            PDFTextStripper stripper = new PDFTextStripper();
            stripper.setSortByPosition(true);
            String text = stripper.getText(doc);
            return text == null ? "" : text.replaceAll("\\n{3,}", "\n\n").trim();
        }
    }

    // ── Czytanie surowych bajtów ──────────────────────────────────────────────

    private byte[] readBytes(Uri uri) throws Exception {
        try (InputStream in = getContentResolver().openInputStream(uri)) {
            if (in == null) throw new Exception("brak strumienia");
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int n, total = 0;
            while ((n = in.read(buf)) != -1) {
                total += n;
                if (total > MAX_FILE_BYTES) { bos.write(buf, 0, n); break; }
                bos.write(buf, 0, n);
            }
            return bos.toByteArray();
        }
    }

    private String readPlainText(Uri uri) throws Exception {
        byte[] bytes = readBytes(uri);
        int off = 0;   // pomiń BOM UTF-8
        if (bytes.length >= 3 && (bytes[0] & 0xFF) == 0xEF
                && (bytes[1] & 0xFF) == 0xBB && (bytes[2] & 0xFF) == 0xBF) off = 3;
        return new String(bytes, off, bytes.length - off, StandardCharsets.UTF_8);
    }

    // ── DOCX / ODT (rozpakowanie ZIP, bez bibliotek) ──────────────────────────

    /** Wyciąga z pliku ZIP (docx/odt) zawartość wskazanego wpisu jako tekst XML. */
    private String readZipEntry(Uri uri, String entryName) throws Exception {
        try (InputStream in = getContentResolver().openInputStream(uri);
             ZipInputStream zis = new ZipInputStream(new BufferedInputStream(in))) {
            ZipEntry e;
            while ((e = zis.getNextEntry()) != null) {
                if (entryName.equals(e.getName())) {
                    ByteArrayOutputStream bos = new ByteArrayOutputStream();
                    byte[] buf = new byte[8192];
                    int n;
                    while ((n = zis.read(buf)) != -1) bos.write(buf, 0, n);
                    return new String(bos.toByteArray(), StandardCharsets.UTF_8);
                }
            }
        }
        throw new Exception("Brak wpisu " + entryName);
    }

    /** Zamienia XML z Worda (OOXML) na czysty tekst, zachowując akapity. */
    private String ooxmlToText(String xml) {
        String s = xml;
        s = s.replaceAll("(?i)</w:p>", "\n");
        s = s.replaceAll("(?i)<w:tab\\b[^>]*/?>", "\t");
        s = s.replaceAll("(?i)<w:br\\b[^>]*/?>", "\n");
        s = s.replaceAll("<[^>]+>", "");
        return unescapeXml(s).replaceAll("\\n{3,}", "\n\n").trim();
    }

    /** Zamienia XML z OpenDocument (odt) na czysty tekst, zachowując akapity. */
    private String odfToText(String xml) {
        String s = xml;
        s = s.replaceAll("(?i)</text:p>", "\n");
        s = s.replaceAll("(?i)</text:h>", "\n");
        s = s.replaceAll("(?i)<text:tab\\b[^>]*/?>", "\t");
        s = s.replaceAll("(?i)<text:line-break\\b[^>]*/?>", "\n");
        s = s.replaceAll("<[^>]+>", "");
        return unescapeXml(s).replaceAll("\\n{3,}", "\n\n").trim();
    }

    // ── RTF / HTML / stary DOC ────────────────────────────────────────────────

    /** Bardzo proste odzyskanie tekstu z RTF (usuwa grupy i słowa kontrolne). */
    private String stripRtf(String rtf) {
        String s = rtf;
        s = s.replaceAll("\\\\par[d]?\\b", "\n");
        s = s.replaceAll("\\\\'[0-9a-fA-F]{2}", "");        // znaki w kodowaniu hex
        s = s.replaceAll("\\\\[a-zA-Z]+-?[0-9]*\\s?", "");  // słowa kontrolne
        s = s.replaceAll("[{}]", "");
        return s.replaceAll("\\n{3,}", "\n\n").trim();
    }

    /** Usuwa tagi HTML/XML (skrypty, style) i odkodowuje encje. */
    private String stripTags(String html) {
        String s = html;
        s = s.replaceAll("(?is)<script\\b.*?</script>", " ");
        s = s.replaceAll("(?is)<style\\b.*?</style>", " ");
        s = s.replaceAll("(?i)</p>|<br\\b[^>]*>|</div>|</li>|</h[1-6]>", "\n");
        s = s.replaceAll("<[^>]+>", "");
        s = unescapeXml(s).replace("&nbsp;", " ");
        return s.replaceAll("[ \\t]{2,}", " ").replaceAll("\\n{3,}", "\n\n").trim();
    }

    /**
     * Stary binarny .doc — odzysk best-effort: wyciąga czytelne ciągi znaków.
     * Pełna obsługa wymaga ciężkiej biblioteki; dla .doc zalecany format .docx.
     */
    private String extractLegacyDoc(Uri uri) throws Exception {
        byte[] bytes = readBytes(uri);
        StringBuilder out = new StringBuilder();
        StringBuilder run = new StringBuilder();
        for (byte b : bytes) {
            int c = b & 0xFF;
            boolean printable = c == '\t' || c == '\n' || c == '\r'
                || (c >= 0x20 && c <= 0x7E) || (c >= 0xA1 && c <= 0xFF);
            if (printable) {
                run.append((char) c);
            } else {
                if (run.length() >= 4) out.append(run).append(' ');
                run.setLength(0);
            }
        }
        if (run.length() >= 4) out.append(run);
        String s = out.toString().replaceAll("[ \\t]{2,}", " ").replaceAll("\\n{3,}", "\n\n").trim();
        if (s.isEmpty())
            throw new Exception("Nie udało się odczytać .doc — zapisz jako .docx lub .pdf.");
        return s;
    }

    /** Odkodowuje podstawowe encje XML/HTML (w tym liczbowe). */
    private String unescapeXml(String s) {
        if (s.indexOf('&') < 0) return s;
        StringBuilder sb = new StringBuilder(s.length());
        int i = 0;
        while (i < s.length()) {
            char ch = s.charAt(i);
            if (ch == '&') {
                int semi = s.indexOf(';', i);
                if (semi > i && semi - i <= 10) {
                    String ent = s.substring(i + 1, semi);
                    String rep = decodeEntity(ent);
                    if (rep != null) { sb.append(rep); i = semi + 1; continue; }
                }
            }
            sb.append(ch);
            i++;
        }
        return sb.toString();
    }

    private String decodeEntity(String ent) {
        switch (ent) {
            case "amp":  return "&";
            case "lt":   return "<";
            case "gt":   return ">";
            case "quot": return "\"";
            case "apos": return "'";
            case "nbsp": return " ";
        }
        try {
            if (ent.startsWith("#x") || ent.startsWith("#X"))
                return String.valueOf((char) Integer.parseInt(ent.substring(2), 16));
            if (ent.startsWith("#"))
                return String.valueOf((char) Integer.parseInt(ent.substring(1)));
        } catch (Exception ignored) {}
        return null;
    }

    // ════════════════════════════════════════════════════════════════════════
    //  Biblioteka plików — UI (ekran Czytanie) i panel pamięci (Ustawienia)
    // ════════════════════════════════════════════════════════════════════════

    private LinearLayout buildLibraryCard() {
        libraryCard = surfaceCard();

        LinearLayout topRow = new LinearLayout(this);
        topRow.setOrientation(LinearLayout.HORIZONTAL);
        topRow.setGravity(Gravity.CENTER_VERTICAL);
        topRow.addView(sectionLabel("OSTATNIE PLIKI"), new LinearLayout.LayoutParams(0, -2, 1f));
        libraryCard.addView(topRow, mbottom(dp(4)));

        libraryList = new LinearLayout(this);
        libraryList.setOrientation(LinearLayout.VERTICAL);
        libraryCard.addView(libraryList);

        libraryCard.setVisibility(View.GONE);   // ukryta dopóki nie ma plików
        return libraryCard;
    }

    /** Odświeża listę zapamiętanych plików na ekranie czytania. */
    private void refreshLibraryUi() {
        if (libraryList == null) return;
        List<FileLibrary.Item> items = FileLibrary.list(this);
        libraryList.removeAllViews();
        if (libraryCard != null)
            libraryCard.setVisibility(items.isEmpty() ? View.GONE : View.VISIBLE);
        for (FileLibrary.Item it : items) libraryList.addView(buildLibraryRow(it));
        updateStorageUsage();
    }

    private View buildLibraryRow(final FileLibrary.Item item) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setBackground(mkRound(C_SURFACE2, C_BORDER, 12));
        row.setPadding(dp(12), dp(10), dp(8), dp(10));
        LinearLayout.LayoutParams rlp = new LinearLayout.LayoutParams(-1, -2);
        rlp.setMargins(0, dp(6), 0, 0);
        row.setLayoutParams(rlp);

        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);

        TextView title = new TextView(this);
        title.setText(item.title);
        title.setTextColor(C_TEXT);
        title.setTextSize(13);
        title.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        title.setSingleLine(true);
        title.setEllipsize(TextUtils.TruncateAt.MIDDLE);
        col.addView(title);

        TextView meta = new TextView(this);
        int pct = item.percent();
        String state = item.totalChunks <= 0
            ? "gotowy do odtwarzania"
            : (pct >= 99 ? "ukończono" : "wznów od " + pct + "%  •  fragment "
                + (item.resumeChunk + 1) + "/" + item.totalChunks);
        meta.setText("▶  " + state + "   ·   " + fmtSize(item.sizeBytes));
        meta.setTextColor(C_MUTED);
        meta.setTextSize(11);
        LinearLayout.LayoutParams mlp = new LinearLayout.LayoutParams(-1, -2);
        mlp.setMargins(0, dp(2), 0, 0);
        col.addView(meta, mlp);

        row.addView(col, new LinearLayout.LayoutParams(0, -2, 1f));

        // Chip retencji per plik (decyzja osobna dla każdego pliku)
        Button keep = new Button(this);
        keep.setText(retentionShort(item.retentionDays));
        keep.setAllCaps(false);
        keep.setTextSize(11);
        keep.setTextColor(item.isForever() ? C_PRIMARY : C_MUTED);
        keep.setTypeface(Typeface.DEFAULT_BOLD);
        keep.setBackground(mkRound(isDarkMode() ? C_SURFACE : C_BG, C_BORDER, 14));
        keep.setPadding(dp(10), 0, dp(10), 0);
        keep.setStateListAnimator(null);
        LinearLayout.LayoutParams klp = new LinearLayout.LayoutParams(-2, dp(34));
        klp.setMargins(0, 0, dp(4), 0);
        row.addView(keep, klp);

        Button del = new Button(this);
        del.setText("✕");
        del.setTextSize(15);
        del.setAllCaps(false);
        del.setTextColor(C_MUTED);
        del.setBackground(null);
        del.setPadding(dp(8), 0, dp(8), 0);
        del.setMinWidth(dp(40));
        del.setMinimumWidth(dp(40));
        row.addView(del, new LinearLayout.LayoutParams(dp(40), dp(40)));

        col.setOnClickListener(v -> resumeLibraryItem(item));
        keep.setOnClickListener(v -> chooseRetention(item));
        del.setOnClickListener(v -> {
            FileLibrary.remove(this, item.id);
            if (item.id.equals(currentLibraryId)) currentLibraryId = "";
            refreshLibraryUi();
        });
        return row;
    }

    /** Krótka etykieta retencji na chip ("📌 zawsze" / "⏳ 3 dni"). */
    private String retentionShort(int days) {
        if (days <= FileLibrary.RETENTION_FOREVER) return "📌 zawsze";
        return "⏳ " + days + (days == 1 ? " dzień" : " dni");
    }

    private int indexOfRetention(int days) {
        for (int i = 0; i < RETENTION_DAYS.length; i++) if (RETENTION_DAYS[i] == days) return i;
        return 2;   // fallback: 3 dni
    }

    /** Dialog wyboru, jak długo trzymać dany plik (osobno dla każdego). */
    private void chooseRetention(FileLibrary.Item item) {
        new AlertDialog.Builder(this)
            .setTitle("Przechowuj „" + item.title + "”")
            .setSingleChoiceItems(RETENTION_LABELS, indexOfRetention(item.retentionDays),
                (d, which) -> {
                    FileLibrary.setRetention(this, item.id, RETENTION_DAYS[which]);
                    d.dismiss();
                    FileLibrary.enforce(this, storageCapBytes());
                    refreshLibraryUi();
                })
            .setNegativeButton("Anuluj", null)
            .show();
    }

    /** Wczytuje zapamiętany plik i wznawia od ostatniej pozycji słuchania. */
    private void resumeLibraryItem(FileLibrary.Item item) {
        String text = FileLibrary.readText(this, item.id);
        if (text == null || text.trim().isEmpty()) {
            setStatus("Nie można otworzyć zapisanego pliku.");
            FileLibrary.remove(this, item.id);
            refreshLibraryUi();
            return;
        }
        pendingResumeId    = item.id;
        pendingResumeTitle = item.title;
        pendingStartChunk  = item.resumeChunk;
        showScreen(true);
        speak(text);   // czyta zapisany tekst wprost (bez ponownego tłumaczenia)
        setStatus(item.percent() >= 99
            ? "Odtwarzam od początku: " + item.title
            : "Wznawiam: " + item.title + "  •  " + item.percent() + "%");
    }

    // ── Panel pamięci (Ustawienia) ────────────────────────────────────────────

    private LinearLayout buildStoragePanel() {
        LinearLayout panel = new LinearLayout(this);
        panel.setOrientation(LinearLayout.VERTICAL);
        panel.setPadding(dp(16), dp(14), dp(16), dp(16));
        panel.setBackground(mkRound(C_SURFACE, C_BORDER, 20));

        LinearLayout.LayoutParams spinnerLp = new LinearLayout.LayoutParams(-1, dp(50));
        LinearLayout.LayoutParams spacedLp  = new LinearLayout.LayoutParams(-1, -2);
        spacedLp.setMargins(0, dp(12), 0, 0);

        panel.addView(sectionLabel("PAMIĘĆ PLIKÓW"));

        TextView desc = new TextView(this);
        desc.setText("Wczytane pliki są zapisywane na urządzeniu (jako tekst) wraz z "
            + "miejscem, w którym skończyłeś słuchać — możesz do nich wrócić. Poniższy "
            + "czas to wartość domyślna dla NOWYCH plików; dla każdego pliku możesz ją "
            + "zmienić osobno na liście „Ostatnie pliki” (również „na zawsze”).");
        desc.setTextColor(C_MUTED);
        desc.setTextSize(11);
        desc.setLineSpacing(dp(2), 1f);
        panel.addView(desc, mbottom(dp(6)));

        panel.addView(sectionLabel("DOMYŚLNY CZAS PRZECHOWYWANIA"), spacedLp);
        retentionSpinner = styledSpinner(RETENTION_LABELS);
        panel.addView(retentionSpinner, spinnerLp);

        LinearLayout.LayoutParams spacedLp2 = new LinearLayout.LayoutParams(-1, -2);
        spacedLp2.setMargins(0, dp(12), 0, 0);
        panel.addView(sectionLabel("LIMIT PAMIĘCI"), spacedLp2);
        storageCapSpinner = styledSpinner(STORAGE_LABELS);
        panel.addView(storageCapSpinner, spinnerLp);

        storageUsageText = new TextView(this);
        storageUsageText.setText("");
        storageUsageText.setTextColor(C_PRIMARY);
        storageUsageText.setTextSize(11);
        storageUsageText.setPadding(dp(2), dp(10), 0, 0);
        panel.addView(storageUsageText);

        LinearLayout.LayoutParams listLabelLp = new LinearLayout.LayoutParams(-1, -2);
        listLabelLp.setMargins(0, dp(14), 0, 0);
        panel.addView(sectionLabel("TWOJE PLIKI"), listLabelLp);

        storageFileList = new LinearLayout(this);
        storageFileList.setOrientation(LinearLayout.VERTICAL);
        panel.addView(storageFileList);

        Button clearBtn = secondaryBtn("🗑  Wyczyść zapisane pliki");
        LinearLayout.LayoutParams cbLp = new LinearLayout.LayoutParams(-1, dp(46));
        cbLp.setMargins(0, dp(10), 0, 0);
        panel.addView(clearBtn, cbLp);

        retentionSpinner.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(AdapterView<?> p, View v, int pos, long id) {
                // Zmienia tylko wartość domyślną dla nowych plików (nie rusza istniejących).
                getSharedPreferences(PREFS, MODE_PRIVATE).edit().putInt(PREF_RETENTION, pos).apply();
            }
            @Override public void onNothingSelected(AdapterView<?> p) {}
        });

        storageCapSpinner.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(AdapterView<?> p, View v, int pos, long id) {
                getSharedPreferences(PREFS, MODE_PRIVATE).edit().putInt(PREF_STORAGE_CAP, pos).apply();
                FileLibrary.enforce(MainActivity.this, storageCapBytes());
                refreshLibraryUi();
            }
            @Override public void onNothingSelected(AdapterView<?> p) {}
        });

        clearBtn.setOnClickListener(v -> {
            FileLibrary.clearAll(this);
            currentLibraryId = "";
            refreshLibraryUi();
            if (settingsStatus != null) settingsStatus.setText("Pamięć plików wyczyszczona.");
        });

        return panel;
    }

    private void updateStorageUsage() {
        List<FileLibrary.Item> items = FileLibrary.list(this);
        long used = 0;
        for (FileLibrary.Item it : items) used += it.sizeBytes;

        if (storageUsageText != null)
            storageUsageText.setText("Zajęte: " + fmtSize(used) + " z " + fmtSize(storageCapBytes())
                + "   •   plików: " + items.size());

        if (storageFileList == null) return;
        storageFileList.removeAllViews();
        if (items.isEmpty()) {
            TextView empty = new TextView(this);
            empty.setText("Brak zapisanych plików. Wczytaj plik na ekranie „Czytanie”.");
            empty.setTextColor(C_MUTED);
            empty.setTextSize(11);
            empty.setPadding(dp(2), dp(8), 0, 0);
            storageFileList.addView(empty);
            return;
        }
        for (FileLibrary.Item it : items) storageFileList.addView(buildStorageRow(it));
    }

    /** Wiersz szczegółowy w Ustawieniach: nazwa, rozmiar, kiedy zniknie, otwórz/usuń. */
    private View buildStorageRow(final FileLibrary.Item item) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setBackground(mkRound(C_SURFACE2, C_BORDER, 12));
        row.setPadding(dp(12), dp(10), dp(8), dp(10));
        LinearLayout.LayoutParams rlp = new LinearLayout.LayoutParams(-1, -2);
        rlp.setMargins(0, dp(6), 0, 0);
        row.setLayoutParams(rlp);

        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);

        TextView title = new TextView(this);
        title.setText(item.title);
        title.setTextColor(C_TEXT);
        title.setTextSize(13);
        title.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        title.setSingleLine(true);
        title.setEllipsize(TextUtils.TruncateAt.MIDDLE);
        col.addView(title);

        TextView size = new TextView(this);
        size.setText("Rozmiar: " + fmtSize(item.sizeBytes)
            + (item.totalChunks > 0 ? "   •   postęp " + item.percent() + "%" : ""));
        size.setTextColor(C_MUTED);
        size.setTextSize(11);
        LinearLayout.LayoutParams szlp = new LinearLayout.LayoutParams(-1, -2);
        szlp.setMargins(0, dp(2), 0, 0);
        col.addView(size, szlp);

        TextView del = new TextView(this);
        del.setText(deletionInfo(item));
        del.setTextColor(item.isForever() ? C_PRIMARY : C_MUTED);
        del.setTextSize(11);
        col.addView(del);

        row.addView(col, new LinearLayout.LayoutParams(0, -2, 1f));

        // Chip retencji (zmiana czasu przechowywania dla tego pliku)
        Button keep = new Button(this);
        keep.setText(retentionShort(item.retentionDays));
        keep.setAllCaps(false);
        keep.setTextSize(11);
        keep.setTextColor(item.isForever() ? C_PRIMARY : C_MUTED);
        keep.setTypeface(Typeface.DEFAULT_BOLD);
        keep.setBackground(mkRound(isDarkMode() ? C_SURFACE : C_BG, C_BORDER, 14));
        keep.setPadding(dp(10), 0, dp(10), 0);
        keep.setStateListAnimator(null);
        LinearLayout.LayoutParams klp = new LinearLayout.LayoutParams(-2, dp(34));
        klp.setMargins(0, 0, dp(4), 0);
        row.addView(keep, klp);

        Button del2 = new Button(this);
        del2.setText("✕");
        del2.setTextSize(15);
        del2.setAllCaps(false);
        del2.setTextColor(C_MUTED);
        del2.setBackground(null);
        del2.setPadding(dp(8), 0, dp(8), 0);
        del2.setMinWidth(dp(40));
        del2.setMinimumWidth(dp(40));
        row.addView(del2, new LinearLayout.LayoutParams(dp(40), dp(40)));

        col.setOnClickListener(v -> resumeLibraryItem(item));   // otwórz / odtwórz
        keep.setOnClickListener(v -> chooseRetention(item));
        del2.setOnClickListener(v -> {
            FileLibrary.remove(this, item.id);
            if (item.id.equals(currentLibraryId)) currentLibraryId = "";
            refreshLibraryUi();
        });
        return row;
    }

    /** Tekst „kiedy plik zostanie usunięty" (lub że jest trzymany na zawsze). */
    private String deletionInfo(FileLibrary.Item item) {
        if (item.isForever()) return "📌 Nie zostanie usunięty (na zawsze)";
        long deleteAt = item.lastOpenedAt + item.retentionDays * 24L * 3600_000L;
        long remain = deleteAt - System.currentTimeMillis();
        String when;
        if (remain <= 0) {
            when = "wkrótce";
        } else {
            long hours = remain / 3600_000L;
            if (hours < 1)       when = "za <1 godz.";
            else if (hours < 24) when = "za " + hours + (hours == 1 ? " godz." : " godz.");
            else {
                long days = hours / 24;
                when = "za " + days + (days == 1 ? " dzień" : " dni");
            }
        }
        SimpleDateFormat fmt = new SimpleDateFormat("d MMM, HH:mm", new Locale("pl", "PL"));
        return "Usunięcie " + when + "  (" + fmt.format(new Date(deleteAt)) + ")";
    }

    private String fmtSize(long bytes) {
        if (bytes < 1024) return bytes + " B";
        double kb = bytes / 1024.0;
        if (kb < 1024) return String.format(Locale.US, "%.0f KB", kb);
        double mb = kb / 1024.0;
        if (mb < 1024) return String.format(Locale.US, "%.1f MB", mb);
        return String.format(Locale.US, "%.2f GB", mb / 1024.0);
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

        // ── Biblioteka: zapis nowego pliku lub wznowienie zapamiętanego ──
        String fileTitle = null;
        if (pendingLibraryTitle != null) {
            FileLibrary.Item it = FileLibrary.save(this, pendingLibraryTitle, text, retentionDays());
            currentLibraryId = it != null ? it.id : "";
            fileTitle = pendingLibraryTitle;
            pendingLibraryTitle = null;
            lastPersistedChunk = -1;
            FileLibrary.enforce(this, storageCapBytes());
            FileLibrary.updateProgress(this, currentLibraryId, 0, currentChunks.size());
            refreshLibraryUi();
        } else if (pendingResumeId != null) {
            currentLibraryId = pendingResumeId;
            fileTitle = pendingResumeTitle;
            pendingResumeId = null;
            pendingResumeTitle = null;
            lastPersistedChunk = -1;
        } else {
            currentLibraryId = "";   // URL / wklejony tekst — nie zapisujemy
        }

        int start = pendingStartChunk;
        pendingStartChunk = 0;
        currentChunkIndex = (start >= 0 && start < currentChunks.size()) ? start : 0;

        readingQueue = true;
        paused = false;
        currentTitle = (fileTitle != null && !fileTitle.isEmpty())
            ? fileTitle
            : text.substring(0, Math.min(60, text.length())).replace("\n", " ").trim();
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

    private float progressToRate(int p) { return RATE_MIN + p * RATE_STEP; }
    private int   rateToProgress(float r) {
        return Math.max(0, Math.min(RATE_STEPS, Math.round((r - RATE_MIN) / RATE_STEP)));
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
        persistProgressIfNeeded();
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
        persistProgressIfNeeded();
        updatePlayPauseBtn();
    }

    /** Zapisuje aktualną pozycję wznowienia dla pliku z biblioteki (jeśli się zmieniła). */
    private void persistProgressIfNeeded() {
        if (currentLibraryId.isEmpty() || currentChunks.isEmpty()) return;
        if (currentChunkIndex == lastPersistedChunk) return;
        lastPersistedChunk = currentChunkIndex;
        FileLibrary.updateProgress(this, currentLibraryId, currentChunkIndex, currentChunks.size());
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
        assignDisplayNames();
        refreshVoiceSpinner();
    }

    /** Przydziela każdemu realnemu głosowi czytelne imię (np. „PL · Ola") wg kolejności. */
    private void assignDisplayNames() {
        voiceDisplayNames.clear();
        int pl = 0, en = 0;
        for (Voice v : allVoices) {
            String lang = v.getLocale().getLanguage();
            String name;
            if ("pl".equalsIgnoreCase(lang)) {
                name = "PL · " + pickName(PL_NAMES, pl);
                pl++;
            } else {
                name = "EN · " + pickName(EN_NAMES, en);
                en++;
            }
            voiceDisplayNames.put(v.getName(), name);
        }
    }

    /** Imię z puli; po wyczerpaniu puli dokleja numer, żeby nazwy były unikalne. */
    private String pickName(String[] pool, int idx) {
        String base = pool[idx % pool.length];
        return idx < pool.length ? base : base + " " + (idx + 1);
    }

    private String displayName(Voice v) {
        String n = voiceDisplayNames.get(v.getName());
        return n != null ? n : v.getName();
    }

    private void refreshVoiceSpinner() {
        voices.clear();
        voiceLabels.clear();
        voiceLabels.add("Automatycznie");

        // Głosy pasujące do wybranego języka
        List<Voice> filtered = new ArrayList<>();
        for (Voice v : allVoices) {
            String lang = v.getLocale().getLanguage();
            boolean include = "auto".equals(selectedLanguageCode)
                || ("pl".equals(selectedLanguageCode) && "pl".equalsIgnoreCase(lang))
                || ("en".equals(selectedLanguageCode) && "en".equalsIgnoreCase(lang));
            if (include) filtered.add(v);
        }
        // Ulubieni (★) na samą górę, reszta poniżej — kolejność w obrębie grup zachowana
        for (Voice v : filtered) if (favoriteVoices.contains(v.getName())) voices.add(v);
        for (Voice v : filtered) if (!favoriteVoices.contains(v.getName())) voices.add(v);

        for (Voice v : voices) {
            boolean fav = favoriteVoices.contains(v.getName());
            voiceLabels.add((fav ? "★ " : "") + displayName(v));
        }

        ArrayAdapter<String> adapter = new ArrayAdapter<>(this,
            android.R.layout.simple_spinner_item, voiceLabels);
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        if (voiceSpinner == null) return;
        voiceSpinner.setAdapter(adapter);
        voiceSpinner.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(AdapterView<?> p, View v, int pos, long id) {
                updateStarButton();
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

        // Utrzymaj zaznaczenie na tym samym lektorze po przesortowaniu
        if (!selectedVoiceName.isEmpty()) {
            for (int i = 0; i < voices.size(); i++) {
                if (voices.get(i).getName().equals(selectedVoiceName)) {
                    voiceSpinner.setSelection(i + 1);
                    break;
                }
            }
        }
        updateStarButton();
    }

    /** Przełącza ulubionego (★) dla aktualnie wybranego lektora i zapisuje. */
    private void toggleFavoriteVoice() {
        int pos = voiceSpinner != null ? voiceSpinner.getSelectedItemPosition() : 0;
        if (pos <= 0 || pos - 1 >= voices.size()) {
            setStatus("Wybierz najpierw konkretnego lektora (nie Automatycznie).");
            return;
        }
        String name = voices.get(pos - 1).getName();
        if (favoriteVoices.contains(name)) favoriteVoices.remove(name);
        else                               favoriteVoices.add(name);
        getSharedPreferences(PREFS, MODE_PRIVATE).edit()
            .putStringSet(PREF_FAVORITES, new java.util.HashSet<>(favoriteVoices)).apply();
        selectedVoiceName = name;   // zostań na tym samym lektorze
        refreshVoiceSpinner();      // przesortuje i odświeży ★
    }

    private void updateStarButton() {
        if (favVoiceBtn == null || voiceSpinner == null) return;
        int pos = voiceSpinner.getSelectedItemPosition();
        boolean isFav = pos > 0 && pos - 1 < voices.size()
            && favoriteVoices.contains(voices.get(pos - 1).getName());
        favVoiceBtn.setText(isFav ? "★ Ulubiony (kliknij, by usunąć)" : "☆ Oznacz jako ulubiony");
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

        final String cleaned = cleanForTranslation(text);
        if (cleaned.isEmpty()) { speak(text); return; }

        // 1) Wykryj język źródłowy modelem ML Kit (a nie zgadywaniem)
        setStatus("Wykrywam język…");
        String sample = cleaned.length() > 4000 ? cleaned.substring(0, 4000) : cleaned;
        LanguageIdentifier li = LanguageIdentification.getClient();
        li.identifyLanguage(sample)
            .addOnSuccessListener(tag -> { li.close(); startTranslation(cleaned, tag); })
            .addOnFailureListener(e -> { li.close(); startTranslation(cleaned, null); });
    }

    /** Ustala język źródłowy (z wykrycia lub heurystyki) i uruchamia tłumaczenie. */
    private void startTranslation(String text, String langTag) {
        String sourceLang = null;
        if (langTag != null && !"und".equals(langTag)) {
            sourceLang = TranslateLanguage.fromLanguageTag(langTag);
        }
        if (sourceLang == null) {  // nieobsługiwany / niewykryty → heurystyka PL/EN
            sourceLang = "pl".equalsIgnoreCase(detectLocale(text).getLanguage())
                ? TranslateLanguage.POLISH : TranslateLanguage.ENGLISH;
        }
        final String src = sourceLang;
        if (src.equals(translateTargetLang)) { speak(text); return; }

        TranslatorOptions options = new TranslatorOptions.Builder()
            .setSourceLanguage(src)
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

    // ── Czyszczenie tekstu przed tłumaczeniem ─────────────────────────────────
    private static final String[] JUNK_MARKERS = {
        "cookie", "akceptuj", "accept all", "zaloguj", "sign in", "log in",
        "subskryb", "subscribe", "newsletter", "udostępnij", "share this",
        "reklama", "advertis", "menu", "nawigacj", "skip to", "przejdź do treści",
        "wszelkie prawa", "all rights", "polityka prywatności", "privacy policy"
    };

    /** Usuwa typowe śmieci (menu/cookie/nawigacja) i normalizuje białe znaki. */
    private String cleanForTranslation(String text) {
        if (text == null) return "";
        StringBuilder sb = new StringBuilder();
        for (String raw : text.split("\n")) {
            String line = raw.trim();
            if (line.isEmpty()) { sb.append('\n'); continue; }
            String low = line.toLowerCase(Locale.ROOT);
            int words = line.split("\\s+").length;
            // krótkie linie wyglądające jak menu/cookie/nawigacja
            if (words <= 6 && containsAny(low, JUNK_MARKERS)) continue;
            // linie złożone tylko z liczb/symboli
            if (words <= 3 && line.replaceAll("[\\d\\s\\p{Punct}]", "").isEmpty()) continue;
            sb.append(line).append('\n');
        }
        String out = sb.toString()
            .replaceAll("[ \\t]{2,}", " ")
            .replaceAll("\\n{3,}", "\n\n");
        return out.trim();
    }

    private boolean containsAny(String haystack, String[] needles) {
        for (String n : needles) if (haystack.contains(n)) return true;
        return false;
    }

    // ── Wstępne pobranie modeli offline (z Ustawień) ──────────────────────────

    /**
     * Pobiera z wyprzedzeniem model tłumaczenia dla wybranego języka docelowego,
     * dodatkowo angielski (ML Kit tłumaczy przez angielski jako pivot i to
     * najczęstszy język źródłowy). Dzięki temu pierwsze tłumaczenie nie czeka.
     */
    private void downloadSelectedModel(Button btn) {
        int pos = translateSpinner != null ? translateSpinner.getSelectedItemPosition() : 0;
        if (pos <= 0) {
            if (settingsStatus != null)
                settingsStatus.setText("Najpierw wybierz język tłumaczenia powyżej.");
            return;
        }
        String targetLang = TranslateLanguage.fromLanguageTag(TRANSLATE_CODES[pos]);
        if (targetLang == null) {
            if (settingsStatus != null)
                settingsStatus.setText("Ten język nie jest obsługiwany offline.");
            return;
        }

        List<String> langs = new ArrayList<>();
        langs.add(targetLang);
        if (!TranslateLanguage.ENGLISH.equals(targetLang)) langs.add(TranslateLanguage.ENGLISH);

        final String label = TRANSLATE_LANGS[pos];
        btn.setEnabled(false);
        if (settingsStatus != null)
            settingsStatus.setText("Pobieram model: " + label + " (~30 MB)…");

        RemoteModelManager manager = RemoteModelManager.getInstance();
        DownloadConditions conditions = new DownloadConditions.Builder().build();
        AtomicInteger remaining = new AtomicInteger(langs.size());
        AtomicBoolean failed = new AtomicBoolean(false);

        for (String lang : langs) {
            TranslateRemoteModel model = new TranslateRemoteModel.Builder(lang).build();
            manager.download(model, conditions)
                .addOnSuccessListener(unused -> {
                    if (failed.get()) return;
                    if (remaining.decrementAndGet() == 0) {
                        btn.setEnabled(true);
                        if (settingsStatus != null)
                            settingsStatus.setText("✓ Model „" + label + "” gotowy — tłumaczenie offline.");
                    }
                })
                .addOnFailureListener(e -> {
                    if (failed.compareAndSet(false, true)) {
                        btn.setEnabled(true);
                        if (settingsStatus != null)
                            settingsStatus.setText("Nie udało się pobrać modelu. Sprawdź połączenie i spróbuj ponownie.");
                    }
                });
        }
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

    /**
     * Dzieli tekst na paczki dla tłumacza po granicach ZDAŃ (nigdy w środku zdania).
     * Małe akapity zostają w całości; duże są grupowane po pełnych zdaniach do ~700 znaków.
     * ML Kit daje najlepsze wyniki na krótkich, kompletnych segmentach.
     */
    private List<String> splitForTranslation(String text) {
        final int TARGET = 700;
        List<String> chunks = new ArrayList<>();

        for (String para : text.split("\n\n+")) {
            para = para.trim();
            if (para.isEmpty()) continue;
            if (para.length() <= TARGET) { chunks.add(para); continue; }

            // duży akapit → grupuj całe zdania
            StringBuilder cur = new StringBuilder();
            for (String sentence : para.split("(?<=[.!?…])\\s+")) {
                String s = sentence.trim();
                if (s.isEmpty()) continue;
                if (cur.length() > 0 && cur.length() + s.length() + 1 > TARGET) {
                    chunks.add(cur.toString());
                    cur.setLength(0);
                }
                if (cur.length() > 0) cur.append(' ');
                cur.append(s);
            }
            if (cur.length() > 0) chunks.add(cur.toString());
        }
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
