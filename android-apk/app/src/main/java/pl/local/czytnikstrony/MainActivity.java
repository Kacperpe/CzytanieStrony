package pl.local.czytnikstrony;

import android.app.Activity;
import android.app.StatusBarManager;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.res.Configuration;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.Icon;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.speech.tts.TextToSpeech;
import android.speech.tts.UtteranceProgressListener;
import android.speech.tts.Voice;
import android.view.Gravity;
import android.view.View;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.SeekBar;
import android.widget.Spinner;
import android.widget.TextView;

import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Set;

public class MainActivity extends Activity implements TextToSpeech.OnInitListener {

    private static final int   MAX_CHUNK = 260;
    private static final float DEF_RATE  = 0.92f;

    // ── Aktualizacje z GitHub ─────────────────────────────────────────────
    private static final String GITHUB_OWNER = "";  // wpisz login GitHub np. "jan"
    private static final String GITHUB_REPO  = "";  // wpisz nazwę repozytorium np. "czytnik"

    // ── Kolory (ustawiane dynamicznie w initColors) ──────────────────────────
    private int C_BG, C_SURFACE, C_SURFACE2, C_PRIMARY, C_ON_PRIMARY;
    private int C_TEXT, C_MUTED, C_BORDER, C_DANGER, C_DANGER_BG;

    // ── Views ─────────────────────────────────────────────────────────────────
    private EditText    urlInput;
    private EditText    textInput;
    private TextView    statusText;
    private TextView    rateText;
    private TextView    progressText;
    private WebView     webView;
    private ProgressBar loadingBar;
    private SeekBar     rateSeekBar;
    private SeekBar     progressSeekBar;
    private Spinner     languageSpinner;
    private Spinner     voiceSpinner;
    private LinearLayout settingsPanel;
    private Button      playPauseButton;

    // ── Stan TTS ──────────────────────────────────────────────────────────────
    private TextToSpeech    tts;
    private final List<Voice>  voices      = new ArrayList<>();
    private final List<String> voiceLabels = new ArrayList<>();
    private String  selectedLanguageCode = "auto";
    private String  selectedVoiceName    = "";
    private float   speechRate           = DEF_RATE;
    private boolean ttsReady       = false;
    private boolean readingQueue    = false;
    private boolean paused          = false;
    private List<String> currentChunks = new ArrayList<>();
    private int currentChunkIndex = 0;
    private boolean settingsVisible = false;
    private String  currentTitle    = "";
    private Button  updateButton;

    private final BroadcastReceiver controlReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(android.content.Context context, Intent intent) {
            String action = intent.getStringExtra(PlayerService.KEY_NOTIF_ACTION);
            if (action != null) handleNotifAction(action);
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
        } else {
            registerReceiver(controlReceiver, filter);
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
        if (tts != null) { tts.stop(); tts.shutdown(); }
        PlayerService.hide(this);
        super.onDestroy();
    }

    // ════════════════════════════════════════════════════════════════════════
    //  Kolory
    // ════════════════════════════════════════════════════════════════════════

    private boolean isDarkMode() {
        int mask = getResources().getConfiguration().uiMode & Configuration.UI_MODE_NIGHT_MASK;
        return mask == Configuration.UI_MODE_NIGHT_YES;
    }

    private void initColors() {
        if (isDarkMode()) {
            C_BG         = 0xFF0D1614;
            C_SURFACE    = 0xFF182220;
            C_SURFACE2   = 0xFF1F2D2A;
            C_PRIMARY    = 0xFF3ECBA8;
            C_ON_PRIMARY = 0xFF001A14;
            C_TEXT       = 0xFFDCEDE9;
            C_MUTED      = 0xFF7CA99F;
            C_BORDER     = 0xFF2B3E3B;
            C_DANGER     = 0xFFFF6E6E;
            C_DANGER_BG  = 0xFF2A1515;
        } else {
            C_BG         = 0xFFF0F6F5;
            C_SURFACE    = 0xFFFFFFFF;
            C_SURFACE2   = 0xFFF7FBFA;
            C_PRIMARY    = 0xFF1A6B5A;
            C_ON_PRIMARY = 0xFFFFFFFF;
            C_TEXT       = 0xFF182624;
            C_MUTED      = 0xFF58726E;
            C_BORDER     = 0xFFCBDAD8;
            C_DANGER     = 0xFFC0392B;
            C_DANGER_BG  = 0xFFFFF0EE;
        }
    }

    // ════════════════════════════════════════════════════════════════════════
    //  TTS init
    // ════════════════════════════════════════════════════════════════════════

    @Override
    public void onInit(int status) {
        ttsReady = (status == TextToSpeech.SUCCESS);
        if (!ttsReady) { setStatus("TTS niedostępny na tym telefonie."); return; }

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
        setStatus("Gotowy.");
    }

    // ════════════════════════════════════════════════════════════════════════
    //  Budowanie UI
    // ════════════════════════════════════════════════════════════════════════

    private void buildUi() {
        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        scroll.setBackgroundColor(C_BG);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(16), dp(12), dp(16), dp(28));
        scroll.addView(root, new ScrollView.LayoutParams(-1, -2));

        // ── Tytuł ──
        TextView title = new TextView(this);
        title.setText("Czytnik");
        title.setTextColor(C_TEXT);
        title.setTextSize(30);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        title.setPadding(0, dp(4), 0, dp(10));
        root.addView(title);

        // ── Baner aktualizacji (domyślnie ukryty) ──
        updateButton = new Button(this);
        updateButton.setText("↑ Dostępna aktualizacja");
        updateButton.setAllCaps(false);
        updateButton.setTextSize(13);
        updateButton.setTypeface(Typeface.DEFAULT_BOLD);
        updateButton.setTextColor(C_ON_PRIMARY);
        updateButton.setBackground(mkRound(C_PRIMARY, C_PRIMARY, 10));
        updateButton.setPadding(dp(12), dp(10), dp(12), dp(10));
        updateButton.setVisibility(View.GONE);
        root.addView(updateButton, mbottom(dp(8)));

        // ── Status ──
        statusText = new TextView(this);
        statusText.setText("Startuje…");
        statusText.setTextColor(C_PRIMARY);
        statusText.setTextSize(13);
        statusText.setPadding(dp(12), dp(8), dp(12), dp(8));
        statusText.setBackground(mkRound(C_SURFACE, C_BORDER, 10));
        root.addView(statusText, mbottom(dp(14)));

        // ── Karta URL ──
        root.addView(buildUrlCard(), mbottom(dp(12)));

        // WebView ukryty (1x1 px — tylko do ładowania stron w tle)
        webView = new WebView(this);
        webView.setVisibility(View.GONE);
        root.addView(webView, new LinearLayout.LayoutParams(1, 1));

        // ── Karta tekstu ──
        root.addView(buildTextCard(), mbottom(dp(12)));

        // ── Player ──
        root.addView(buildPlayer(), mbottom(dp(12)));

        // ── Sekcja głosu (zwijana) ──
        root.addView(buildSettingsToggle());
        settingsPanel = buildSettingsPanel();
        settingsPanel.setVisibility(View.GONE);
        root.addView(settingsPanel, mbottom(dp(10)));

        // ── Kafelek ──
        Button tileBtn = new Button(this);
        tileBtn.setText("Dodaj kafelek do panelu szybkich ustawień");
        tileBtn.setAllCaps(false);
        tileBtn.setTextSize(13);
        tileBtn.setTextColor(C_MUTED);
        tileBtn.setBackground(mkRound(C_SURFACE, C_BORDER, 10));
        tileBtn.setPadding(dp(12), dp(10), dp(12), dp(10));
        tileBtn.setOnClickListener(v -> requestTile());
        root.addView(tileBtn, mbottom(dp(6)));

        setContentView(scroll);

        // Kolor paska statusu
        getWindow().setStatusBarColor(C_BG);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            android.view.WindowInsetsController wic = getWindow().getInsetsController();
            if (wic != null) {
                int flag = android.view.WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS;
                wic.setSystemBarsAppearance(isDarkMode() ? 0 : flag, flag);
            }
        }
    }

    // ── Karta URL ────────────────────────────────────────────────────────────

    private LinearLayout buildUrlCard() {
        LinearLayout card = surfaceCard();

        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);

        urlInput = new EditText(this);
        urlInput.setSingleLine(true);
        urlInput.setHint("Adres strony…");
        urlInput.setTextColor(C_TEXT);
        urlInput.setHintTextColor(C_MUTED);
        urlInput.setTextSize(14);
        urlInput.setPadding(dp(10), dp(8), dp(10), dp(8));
        urlInput.setBackground(mkRound(C_SURFACE2, C_BORDER, 10));
        LinearLayout.LayoutParams ulp = new LinearLayout.LayoutParams(0, dp(44), 1f);
        ulp.setMargins(0, 0, dp(8), 0);
        row.addView(urlInput, ulp);

        Button loadBtn   = compBtn("Otwórz", false);
        Button readerBtn = compBtn("▶ Czytaj", true);
        row.addView(loadBtn,   compBtnLp());
        row.addView(readerBtn, compBtnLp());
        card.addView(row);

        loadingBar = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
        loadingBar.setMax(100);
        loadingBar.setVisibility(View.GONE);
        LinearLayout.LayoutParams lbp = new LinearLayout.LayoutParams(-1, dp(3));
        lbp.setMargins(0, dp(6), 0, 0);
        card.addView(loadingBar, lbp);

        loadBtn.setOnClickListener(v   -> loadUrlFromInput());
        readerBtn.setOnClickListener(v -> extractAndRead());

        return card;
    }

    // ── Karta tekstu ─────────────────────────────────────────────────────────

    private LinearLayout buildTextCard() {
        LinearLayout card = surfaceCard();

        LinearLayout header = new LinearLayout(this);
        header.setOrientation(LinearLayout.HORIZONTAL);
        header.setGravity(Gravity.CENTER_VERTICAL);
        header.setPadding(0, 0, 0, dp(8));

        TextView lbl = new TextView(this);
        lbl.setText("TEKST");
        lbl.setTextColor(C_MUTED);
        lbl.setTextSize(11);
        lbl.setTypeface(Typeface.DEFAULT_BOLD);
        header.addView(lbl, new LinearLayout.LayoutParams(0, -2, 1f));

        Button clearBtn = new Button(this);
        clearBtn.setText("Wyczyść");
        clearBtn.setAllCaps(false);
        clearBtn.setTextSize(12);
        clearBtn.setTextColor(C_MUTED);
        clearBtn.setBackground(null);
        clearBtn.setMinHeight(0);
        clearBtn.setMinimumHeight(0);
        clearBtn.setPadding(dp(8), 0, 0, 0);
        header.addView(clearBtn);
        card.addView(header);

        textInput = new EditText(this);
        textInput.setGravity(Gravity.TOP);
        textInput.setMinLines(4);
        textInput.setHint("Tekst pojawi się tu po kliknięciu ▶ Czytaj,\nlub wklej własny tekst…");
        textInput.setTextColor(C_TEXT);
        textInput.setHintTextColor(C_MUTED);
        textInput.setTextSize(15);
        textInput.setLineSpacing(dp(2), 1f);
        textInput.setPadding(dp(10), dp(8), dp(10), dp(8));
        textInput.setBackground(mkRound(C_SURFACE2, C_BORDER, 10));
        card.addView(textInput, new LinearLayout.LayoutParams(-1, dp(160)));

        LinearLayout btns = new LinearLayout(this);
        btns.setOrientation(LinearLayout.HORIZONTAL);
        btns.setGravity(Gravity.END);
        LinearLayout.LayoutParams btp = new LinearLayout.LayoutParams(-1, -2);
        btp.setMargins(0, dp(8), 0, 0);

        Button readTextBtn = compBtn("▶ Czytaj tekst", true);
        Button cursorBtn   = compBtn("Od kursora", false);
        btns.addView(cursorBtn,   compBtnLp());
        btns.addView(readTextBtn, compBtnLp());
        card.addView(btns, btp);

        clearBtn.setOnClickListener(v   -> { textInput.setText(""); stopReading(); });
        readTextBtn.setOnClickListener(v -> speak(textInput.getText().toString()));
        cursorBtn.setOnClickListener(v   -> speakFromCursor());

        return card;
    }

    // ── Player ───────────────────────────────────────────────────────────────

    private LinearLayout buildPlayer() {
        LinearLayout card = surfaceCard();

        // Wiersz postępu
        LinearLayout progRow = new LinearLayout(this);
        progRow.setOrientation(LinearLayout.HORIZONTAL);
        progRow.setGravity(Gravity.CENTER_VERTICAL);

        progressText = new TextView(this);
        progressText.setText("—");
        progressText.setTextColor(C_MUTED);
        progressText.setTextSize(12);
        progressText.setTypeface(Typeface.DEFAULT_BOLD);
        progRow.addView(progressText, new LinearLayout.LayoutParams(0, -2, 1f));

        rateText = new TextView(this);
        rateText.setText(String.format(Locale.US, "%.2fx", DEF_RATE));
        rateText.setTextColor(C_MUTED);
        rateText.setTextSize(12);
        rateText.setTypeface(Typeface.DEFAULT_BOLD);
        progRow.addView(rateText);
        card.addView(progRow);

        // Pasek postępu czytania
        progressSeekBar = new SeekBar(this);
        progressSeekBar.setMax(100);
        progressSeekBar.setProgress(0);
        LinearLayout.LayoutParams psp = new LinearLayout.LayoutParams(-1, dp(32));
        psp.setMargins(0, dp(4), 0, 0);
        card.addView(progressSeekBar, psp);

        // Przyciski sterowania
        LinearLayout controls = new LinearLayout(this);
        controls.setOrientation(LinearLayout.HORIZONTAL);
        controls.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams clp = new LinearLayout.LayoutParams(-1, -2);
        clp.setMargins(0, dp(14), 0, 0);

        Button prevBtn = navBtn("⏮");
        playPauseButton = playBtn();
        Button nextBtn = navBtn("⏭");
        Button stopBtn = stopBtn();

        LinearLayout.LayoutParams navLp = new LinearLayout.LayoutParams(dp(52), dp(52));
        navLp.setMargins(dp(6), 0, dp(6), 0);
        navLp.gravity = Gravity.CENTER_VERTICAL;

        LinearLayout.LayoutParams playLp = new LinearLayout.LayoutParams(dp(68), dp(68));
        playLp.setMargins(dp(12), 0, dp(12), 0);
        playLp.gravity = Gravity.CENTER_VERTICAL;

        controls.addView(prevBtn, navLp);
        controls.addView(playPauseButton, playLp);
        controls.addView(nextBtn, navLp);
        controls.addView(stopBtn, navLp);
        card.addView(controls, clp);

        // Pasek tempa
        LinearLayout rateRow = new LinearLayout(this);
        rateRow.setOrientation(LinearLayout.HORIZONTAL);
        rateRow.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout.LayoutParams rlp = new LinearLayout.LayoutParams(-1, -2);
        rlp.setMargins(0, dp(14), 0, 0);

        TextView rateLabel = new TextView(this);
        rateLabel.setText("Tempo");
        rateLabel.setTextColor(C_MUTED);
        rateLabel.setTextSize(12);
        rateLabel.setPadding(0, 0, dp(10), 0);
        rateRow.addView(rateLabel);

        rateSeekBar = new SeekBar(this);
        rateSeekBar.setMax(120);
        rateSeekBar.setProgress(32); // ~0.92x
        rateRow.addView(rateSeekBar, new LinearLayout.LayoutParams(0, -2, 1f));
        card.addView(rateRow, rlp);

        // Zdarzenia
        prevBtn.setOnClickListener(v         -> goToPrevChunk());
        playPauseButton.setOnClickListener(v -> handlePlayPause());
        nextBtn.setOnClickListener(v         -> goToNextChunk());
        stopBtn.setOnClickListener(v         -> stopReading());

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

    // ── Sekcja ustawień głosu (zwijana) ─────────────────────────────────────

    private Button buildSettingsToggle() {
        Button toggle = new Button(this);
        toggle.setText("⚙  Głos i język  ▾");
        toggle.setAllCaps(false);
        toggle.setTextSize(13);
        toggle.setTextColor(C_MUTED);
        toggle.setBackground(null);
        toggle.setGravity(Gravity.START | Gravity.CENTER_VERTICAL);
        toggle.setPadding(dp(2), dp(6), dp(4), dp(6));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
        lp.setMargins(0, 0, 0, dp(4));
        toggle.setLayoutParams(lp);
        toggle.setOnClickListener(v -> {
            settingsVisible = !settingsVisible;
            settingsPanel.setVisibility(settingsVisible ? View.VISIBLE : View.GONE);
            toggle.setText(settingsVisible
                ? "⚙  Głos i język  ▴"
                : "⚙  Głos i język  ▾");
        });
        return toggle;
    }

    private LinearLayout buildSettingsPanel() {
        LinearLayout panel = new LinearLayout(this);
        panel.setOrientation(LinearLayout.VERTICAL);
        panel.setPadding(dp(14), dp(12), dp(14), dp(14));
        panel.setBackground(mkRound(C_SURFACE, C_BORDER, 14));

        panel.addView(settingsLabel("Język"));

        languageSpinner = new Spinner(this);
        languageSpinner.setBackground(mkRound(C_SURFACE2, C_BORDER, 10));
        languageSpinner.setPadding(dp(8), 0, dp(8), 0);
        ArrayAdapter<String> langAdapter = new ArrayAdapter<>(this, android.R.layout.simple_spinner_item,
            new String[]{"Automatycznie", "Polski", "English"});
        langAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        languageSpinner.setAdapter(langAdapter);
        panel.addView(languageSpinner, new LinearLayout.LayoutParams(-1, dp(48)));

        LinearLayout.LayoutParams vlp = new LinearLayout.LayoutParams(-1, -2);
        vlp.setMargins(0, dp(10), 0, 0);
        panel.addView(settingsLabel("Głos"), vlp);

        voiceSpinner = new Spinner(this);
        voiceSpinner.setBackground(mkRound(C_SURFACE2, C_BORDER, 10));
        voiceSpinner.setPadding(dp(8), 0, dp(8), 0);
        panel.addView(voiceSpinner, new LinearLayout.LayoutParams(-1, dp(48)));

        languageSpinner.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                selectedLanguageCode = position == 1 ? "pl" : position == 2 ? "en" : "auto";
            }
            @Override public void onNothingSelected(AdapterView<?> parent) { selectedLanguageCode = "auto"; }
        });

        return panel;
    }

    // ════════════════════════════════════════════════════════════════════════
    //  Przyciski — fabryki
    // ════════════════════════════════════════════════════════════════════════

    private Button compBtn(String text, boolean primary) {
        Button btn = new Button(this);
        btn.setText(text);
        btn.setAllCaps(false);
        btn.setTextSize(14);
        btn.setTypeface(Typeface.DEFAULT_BOLD);
        btn.setPadding(dp(12), 0, dp(12), 0);
        if (primary) {
            btn.setTextColor(C_ON_PRIMARY);
            btn.setBackground(mkRound(C_PRIMARY, C_PRIMARY, 10));
        } else {
            btn.setTextColor(C_TEXT);
            btn.setBackground(mkRound(C_SURFACE2, C_BORDER, 10));
        }
        return btn;
    }

    private LinearLayout.LayoutParams compBtnLp() {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-2, dp(44));
        lp.setMargins(dp(6), 0, 0, 0);
        return lp;
    }

    private Button navBtn(String text) {
        Button btn = new Button(this);
        btn.setText(text);
        btn.setTextSize(18);
        btn.setAllCaps(false);
        btn.setTextColor(C_TEXT);
        btn.setBackground(mkRound(C_SURFACE2, C_BORDER, 26));
        btn.setPadding(0, 0, 0, 0);
        return btn;
    }

    private Button playBtn() {
        Button btn = new Button(this);
        btn.setText("▶");
        btn.setTextSize(26);
        btn.setAllCaps(false);
        btn.setTextColor(C_ON_PRIMARY);
        btn.setBackground(mkRound(C_PRIMARY, C_PRIMARY, 34));
        btn.setPadding(0, 0, 0, 0);
        return btn;
    }

    private Button stopBtn() {
        Button btn = new Button(this);
        btn.setText("⏹");
        btn.setTextSize(18);
        btn.setAllCaps(false);
        btn.setTextColor(C_DANGER);
        btn.setBackground(mkRound(C_DANGER_BG, C_DANGER_BG, 26));
        btn.setPadding(0, 0, 0, 0);
        return btn;
    }

    private TextView settingsLabel(String text) {
        TextView tv = new TextView(this);
        tv.setText(text);
        tv.setTextColor(C_MUTED);
        tv.setTextSize(12);
        tv.setTypeface(Typeface.DEFAULT_BOLD);
        tv.setPadding(0, 0, 0, dp(4));
        return tv;
    }

    // ════════════════════════════════════════════════════════════════════════
    //  Helpers UI
    // ════════════════════════════════════════════════════════════════════════

    private LinearLayout surfaceCard() {
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setPadding(dp(14), dp(14), dp(14), dp(14));
        card.setBackground(mkRound(C_SURFACE, C_BORDER, 16));
        return card;
    }

    private GradientDrawable mkRound(int fill, int stroke, int radiusDp) {
        GradientDrawable d = new GradientDrawable();
        d.setColor(fill);
        d.setCornerRadius(dp(radiusDp));
        d.setStroke(dp(1), stroke);
        return d;
    }

    private LinearLayout.LayoutParams mbottom(int bottom) {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
        lp.setMargins(0, 0, 0, bottom);
        return lp;
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
            textInput.setText(text);
            setStatus("Artykuł gotowy.");
            speak(text);
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
            speak(value);
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
                PlayerService.hide(this);
            }
            return;
        }
        applySpeechRate();
        tts.speak(currentChunks.get(currentChunkIndex),
            TextToSpeech.QUEUE_FLUSH, null, "c-" + currentChunkIndex);
        PlayerService.update(this, currentTitle, getCurrentChunkPreview(), true, currentChunkIndex, currentChunks.size());
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
        currentChunkIndex = 0;
        if (tts != null) tts.stop();
        updateProgress();
        PlayerService.hide(this);
    }

    private void updateProgress() {
        if (progressText == null || progressSeekBar == null) return;
        if (currentChunks.isEmpty()) {
            progressText.setText("—");
            progressSeekBar.setProgress(0);
        } else {
            int total = currentChunks.size();
            progressText.setText((currentChunkIndex + 1) + " / " + total);
            int pct = total <= 1 ? 0 : (int)(currentChunkIndex * 100f / (total - 1));
            progressSeekBar.setProgress(pct);
        }
        updatePlayPauseBtn();
    }

    private void updatePlayPauseBtn() {
        if (playPauseButton != null)
            playPauseButton.setText(readingQueue && !paused ? "⏸" : "▶");
    }

    private String getCurrentChunkPreview() {
        if (currentChunks.isEmpty() || currentChunkIndex < 0 || currentChunkIndex >= currentChunks.size()) {
            return "";
        }
        String chunk = currentChunks.get(currentChunkIndex).replace("\n", " ").trim();
        if (chunk.length() <= 90) return chunk;
        return chunk.substring(0, 90).trim() + "...";
    }

    // ════════════════════════════════════════════════════════════════════════
    //  Głosy i locale
    // ════════════════════════════════════════════════════════════════════════

    private void loadVoices() {
        Set<Voice> available = tts.getVoices();
        voices.clear();
        voiceLabels.clear();
        voiceLabels.add("Automatycznie");
        if (available != null) {
            List<Voice> sorted = new ArrayList<>(available);
            sorted.sort(Comparator.comparing(v -> v.getLocale().toLanguageTag() + v.getName()));
            for (Voice v : sorted) {
                if (isSupportedLang(v)) {
                    voices.add(v);
                    voiceLabels.add(v.getName() + " (" + v.getLocale().toLanguageTag() + ")");
                }
            }
        }
        ArrayAdapter<String> adapter = new ArrayAdapter<>(this,
            android.R.layout.simple_spinner_item, voiceLabels);
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        voiceSpinner.setAdapter(adapter);
        voiceSpinner.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(AdapterView<?> p, View v, int pos, long id) {
                selectedVoiceName = pos > 0 ? voices.get(pos - 1).getName() : "";
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
        String[] parts = text.split("(?<=[.!?;:])\\s+");
        StringBuilder current = new StringBuilder();
        for (String part : parts) {
            String t = part.trim();
            if (t.isEmpty()) continue;
            if (current.length() > 0 && current.length() + t.length() + 1 > MAX_CHUNK) {
                chunks.add(current.toString());
                current.setLength(0);
            }
            if (current.length() > 0) current.append(' ');
            current.append(t);
        }
        if (current.length() > 0) chunks.add(current.toString());
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
    //  Sprawdzanie aktualizacji
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

                JSONObject json     = new JSONObject(sb.toString());
                String latestTag    = json.optString("tag_name", "").replaceAll("[^0-9.]", "");
                String releaseUrl   = json.optString("html_url", "");
                String currentVer   = getPackageManager()
                    .getPackageInfo(getPackageName(), 0).versionName;

                if (!latestTag.isEmpty() && isNewerVersion(latestTag, currentVer)) {
                    runOnUiThread(() -> {
                        updateButton.setText("↑ Nowa wersja " + latestTag + " – kliknij aby pobrać");
                        updateButton.setVisibility(View.VISIBLE);
                        updateButton.setOnClickListener(v ->
                            startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(releaseUrl))));
                    });
                }
            } catch (Exception ignored) {}
        }).start();
    }

    private boolean isNewerVersion(String latest, String current) {
        try {
            int[] l = parseVer(latest);
            int[] c = parseVer(current);
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
