package pl.local.czytnikstrony;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.StatusBarManager;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.res.Configuration;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.Icon;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.speech.tts.Voice;
import android.text.Editable;
import android.text.InputType;
import android.text.Spannable;
import android.text.TextUtils;
import android.text.style.BackgroundColorSpan;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputMethodManager;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.SeekBar;
import android.widget.TextView;
import android.widget.Toast;

import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

public class MainActivity extends Activity implements Reader.StateListener, VoiceCatalog.DownloadListener {

    private static final String GITHUB_OWNER = "Kacperpe";
    private static final String GITHUB_REPO  = "CzytanieStrony";
    private static final int REQ_IMPORT = 41;

    private static final float[] RATE_PRESETS = {0.8f, 1.0f, 1.25f, 1.5f, 1.75f};

    // ── Paleta ───────────────────────────────────────────────────────────
    private int C_BG, C_SURFACE, C_SURFACE2, C_PRIMARY, C_PRIMARY_DIM, C_ON_PRIMARY;
    private int C_TEXT, C_MUTED, C_BORDER, C_DANGER, C_DANGER_BG, C_HIGHLIGHT;

    private Reader reader;
    private final Handler main = new Handler(Looper.getMainLooper());

    // ── Widoki ───────────────────────────────────────────────────────────
    private EditText urlInput, textInput;
    private TextView statusText, nowTitle, nowPreview, progressText, rateValue, textMeta;
    private TextView voicePlLabel, voiceEnLabel, voiceTip;
    private ProgressBar loadingBar, voiceLoading;
    private SeekBar progressSeek;
    private Button playPauseBtn, updateButton, miniPlayBtn, expandBtn;
    private View playerView, playerDetails;
    private boolean playerExpanded = false;
    private final List<Button> langButtons = new ArrayList<>();
    private final List<Button> rateChips = new ArrayList<>();
    private WebView webView;

    private String shownText = null;           // referencja tekstu z Readera pokazanego w polu
    private BackgroundColorSpan highlight;
    private int highlightedIndex = -2;
    private boolean userSeeking = false;

    // Okno wyboru głosu
    private AlertDialog voiceDialog;
    private LinearLayout voiceList;
    private String voiceDialogLang;
    private long lastListRender = 0;

    private Runnable extractTimeout;

    // ════════════════════════════════════════════════════════════════════
    //  Cykl życia
    // ════════════════════════════════════════════════════════════════════

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        reader = Reader.get(this);
        initColors();
        buildUi();
        configureWebView();
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            requestPermissions(new String[]{android.Manifest.permission.POST_NOTIFICATIONS}, 0);
        }
        reader.systemEngine.whenReady(this::onReaderState);
        handleIncomingIntent(getIntent());
        checkForUpdate();
    }

    @Override
    protected void onStart() {
        super.onStart();
        reader.addListener(this);
        VoiceCatalog.addListener(this);
        onReaderState();
    }

    @Override
    protected void onStop() {
        reader.removeListener(this);
        VoiceCatalog.removeListener(this);
        super.onStop();
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        handleIncomingIntent(intent);
    }

    @Override
    protected void onDestroy() {
        // Czytanie trwa dalej w tle (serwis) — sprzątamy tylko WebView.
        if (webView != null) {
            webView.stopLoading();
            webView.destroy();
            webView = null;
        }
        super.onDestroy();
    }

    // ════════════════════════════════════════════════════════════════════
    //  Kolory
    // ════════════════════════════════════════════════════════════════════

    private boolean isDarkMode() {
        int mask = getResources().getConfiguration().uiMode & Configuration.UI_MODE_NIGHT_MASK;
        return mask == Configuration.UI_MODE_NIGHT_YES;
    }

    private void initColors() {
        if (isDarkMode()) {
            C_BG = 0xFF0C0D14; C_SURFACE = 0xFF151729; C_SURFACE2 = 0xFF1E2038;
            C_PRIMARY = 0xFF8B7DFA; C_PRIMARY_DIM = 0xFF24224D; C_ON_PRIMARY = 0xFF0C0D14;
            C_TEXT = 0xFFECECFF; C_MUTED = 0xFF9796C8; C_BORDER = 0xFF2B2D4D;
            C_DANGER = 0xFFFF7B7B; C_DANGER_BG = 0xFF2E1719; C_HIGHLIGHT = 0x668B7DFA;
        } else {
            C_BG = 0xFFF4F3FB; C_SURFACE = 0xFFFFFFFF; C_SURFACE2 = 0xFFF0EFFA;
            C_PRIMARY = 0xFF5B4AE8; C_PRIMARY_DIM = 0xFFE9E6FF; C_ON_PRIMARY = 0xFFFFFFFF;
            C_TEXT = 0xFF1A1535; C_MUTED = 0xFF65628E; C_BORDER = 0xFFDCDAF0;
            C_DANGER = 0xFFC0392B; C_DANGER_BG = 0xFFFFEFEC; C_HIGHLIGHT = 0x4D5B4AE8;
        }
    }

    // ════════════════════════════════════════════════════════════════════
    //  Budowa UI
    // ════════════════════════════════════════════════════════════════════

    private void buildUi() {
        LinearLayout screen = new LinearLayout(this);
        screen.setOrientation(LinearLayout.VERTICAL);
        screen.setBackgroundColor(C_BG);
        screen.setFitsSystemWindows(true);

        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(16), dp(18), dp(16), dp(16));
        scroll.addView(root, new ScrollView.LayoutParams(-1, -2));
        screen.addView(scroll, new LinearLayout.LayoutParams(-1, 0, 1f));

        root.addView(buildHeader(), mb(16));
        root.addView(buildSourceCard(), mb(12));
        root.addView(buildTextCard(), mb(12));
        root.addView(buildVoiceCard(), mb(12));

        TextView tileLink = new TextView(this);
        tileLink.setText("＋ Dodaj kafelek „Czytnik” do szybkich ustawień");
        tileLink.setTextColor(C_MUTED);
        tileLink.setTextSize(12);
        tileLink.setPadding(dp(4), dp(6), dp(4), dp(6));
        tileLink.setOnClickListener(v -> requestTile());
        root.addView(tileLink);

        webView = new WebView(this);
        webView.setVisibility(View.INVISIBLE);
        root.addView(webView, new LinearLayout.LayoutParams(dp(360), 1));

        screen.addView(buildPlayer(), new LinearLayout.LayoutParams(-1, -2));

        setContentView(screen);
        getWindow().setStatusBarColor(C_BG);
        getWindow().setNavigationBarColor(C_SURFACE);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            android.view.WindowInsetsController wic = getWindow().getInsetsController();
            if (wic != null) {
                int both = android.view.WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS
                    | android.view.WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS;
                wic.setSystemBarsAppearance(isDarkMode() ? 0 : both, both);
            }
        } else if (!isDarkMode()) {
            getWindow().getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR);
        }
    }

    private View buildHeader() {
        LinearLayout header = row();
        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);

        TextView name = new TextView(this);
        name.setText("Czytnik");
        name.setTextColor(C_TEXT);
        name.setTextSize(26);
        name.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        name.setLetterSpacing(-0.02f);
        col.addView(name);

        statusText = new TextView(this);
        statusText.setTextColor(C_MUTED);
        statusText.setTextSize(12);
        col.addView(statusText);
        header.addView(col, new LinearLayout.LayoutParams(0, -2, 1f));

        updateButton = pill("↑ Aktualizacja", true);
        updateButton.setVisibility(View.GONE);
        header.addView(updateButton, new LinearLayout.LayoutParams(-2, dp(34)));
        return header;
    }

    private View buildSourceCard() {
        LinearLayout card = card();
        card.addView(label("STRONA INTERNETOWA"), mb(8));

        LinearLayout r = row();
        urlInput = new EditText(this);
        urlInput.setSingleLine(true);
        urlInput.setHint("Wklej adres, np. onet.pl/artykul…");
        styleInput(urlInput);
        urlInput.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI);
        urlInput.setImeOptions(EditorInfo.IME_ACTION_GO);
        LinearLayout.LayoutParams ilp = new LinearLayout.LayoutParams(0, dp(48), 1f);
        r.addView(urlInput, ilp);

        Button paste = secondaryBtn("Wklej");
        LinearLayout.LayoutParams plp = new LinearLayout.LayoutParams(-2, dp(48));
        plp.setMargins(dp(8), 0, 0, 0);
        r.addView(paste, plp);

        Button go = primaryBtn("Czytaj");
        LinearLayout.LayoutParams glp = new LinearLayout.LayoutParams(-2, dp(48));
        glp.setMargins(dp(8), 0, 0, 0);
        r.addView(go, glp);
        card.addView(r);

        loadingBar = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
        loadingBar.setMax(100);
        loadingBar.setVisibility(View.GONE);
        LinearLayout.LayoutParams lb = new LinearLayout.LayoutParams(-1, dp(3));
        lb.setMargins(0, dp(10), 0, 0);
        card.addView(loadingBar, lb);

        paste.setOnClickListener(v -> {
            String clip = clipboardText();
            if (clip.isEmpty()) { toast("Schowek jest pusty"); return; }
            if (looksLikeUrl(clip)) { urlInput.setText(clip); extractAndRead(); }
            else { reader.play(clip, "", 0); }
        });
        go.setOnClickListener(v -> extractAndRead());
        urlInput.setOnEditorActionListener((v, id, e) -> { extractAndRead(); return true; });
        return card;
    }

    private View buildTextCard() {
        LinearLayout card = card();

        LinearLayout top = row();
        LinearLayout lc = new LinearLayout(this);
        lc.setOrientation(LinearLayout.VERTICAL);
        lc.addView(label("TEKST"));
        textMeta = new TextView(this);
        textMeta.setTextColor(C_MUTED);
        textMeta.setTextSize(11);
        lc.addView(textMeta);
        top.addView(lc, new LinearLayout.LayoutParams(0, -2, 1f));
        Button pasteTxt = ghostBtn("Wklej");
        Button clear = ghostBtn("Wyczyść");
        top.addView(pasteTxt);
        top.addView(clear);
        card.addView(top, mb(8));

        textInput = new EditText(this);
        textInput.setGravity(Gravity.TOP | Gravity.START);
        textInput.setHint("Tu pojawi się artykuł. Możesz też wkleić dowolny tekst.\n\nStuknij w zdanie i wybierz „Od kursora”, żeby zacząć od niego.");
        styleInput(textInput);
        textInput.setTextSize(15);
        textInput.setLineSpacing(dp(4), 1f);
        textInput.setVerticalScrollBarEnabled(true);
        textInput.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_MULTI_LINE
            | InputType.TYPE_TEXT_FLAG_CAP_SENTENCES);
        textInput.setHorizontallyScrolling(false);
        textInput.setMaxLines(Integer.MAX_VALUE);
        // Przewijanie wewnątrz pola zamiast całego ekranu
        textInput.setOnTouchListener((v, ev) -> {
            v.getParent().requestDisallowInterceptTouchEvent(true);
            if ((ev.getActionMasked()) == android.view.MotionEvent.ACTION_UP) {
                v.getParent().requestDisallowInterceptTouchEvent(false);
            }
            return false;
        });
        card.addView(textInput, new LinearLayout.LayoutParams(-1, dp(260)));

        LinearLayout btns = row();
        LinearLayout.LayoutParams blp = new LinearLayout.LayoutParams(0, dp(46), 1f);
        Button fromCursor = secondaryBtn("Od kursora");
        Button fromStart = primaryBtn("Czytaj od początku");
        btns.addView(fromCursor, blp);
        LinearLayout.LayoutParams blp2 = new LinearLayout.LayoutParams(0, dp(46), 1.4f);
        blp2.setMargins(dp(8), 0, 0, 0);
        btns.addView(fromStart, blp2);
        LinearLayout.LayoutParams bwrap = mb(0);
        bwrap.setMargins(0, dp(10), 0, 0);
        card.addView(btns, bwrap);

        pasteTxt.setOnClickListener(v -> {
            String clip = clipboardText();
            if (clip.isEmpty()) { toast("Schowek jest pusty"); return; }
            textInput.setText(clip);
        });
        clear.setOnClickListener(v -> { reader.clear(); textInput.setText(""); shownText = null; });
        fromStart.setOnClickListener(v -> { hideKeyboard(); readEditor(0); });
        fromCursor.setOnClickListener(v -> {
            hideKeyboard();
            int pos = Math.max(0, textInput.getSelectionStart());
            readEditor(pos);
        });
        return card;
    }

    private View buildVoiceCard() {
        LinearLayout card = card();
        card.addView(label("JĘZYK I GŁOS"), mb(8));

        LinearLayout seg = new LinearLayout(this);
        seg.setOrientation(LinearLayout.HORIZONTAL);
        seg.setPadding(dp(3), dp(3), dp(3), dp(3));
        seg.setBackground(round(C_SURFACE2, 0, 12));
        String[][] modes = {{"auto", "Automatycznie"}, {"pl", "Polski"}, {"en", "English"}};
        for (String[] m : modes) {
            Button b = new Button(this);
            b.setText(m[1]);
            b.setAllCaps(false);
            b.setTextSize(13);
            b.setTag(m[0]);
            b.setStateListAnimator(null);
            b.setOnClickListener(v -> reader.setLangMode((String) v.getTag()));
            seg.addView(b, new LinearLayout.LayoutParams(0, dp(38), 1f));
            langButtons.add(b);
        }
        card.addView(seg, mb(12));

        voicePlLabel = new TextView(this);
        voiceEnLabel = new TextView(this);
        card.addView(voiceRow("Polski", voicePlLabel, "pl"), mb(8));
        card.addView(voiceRow("English", voiceEnLabel, "en"));

        voiceTip = new TextView(this);
        voiceTip.setText("✨ Robotyczny głos? Stuknij „Zmień” i pobierz naturalny głos neuronowy — działa offline.");
        voiceTip.setTextColor(C_PRIMARY);
        voiceTip.setTextSize(12);
        voiceTip.setLineSpacing(dp(2), 1f);
        voiceTip.setPadding(dp(12), dp(10), dp(12), dp(10));
        voiceTip.setBackground(round(C_PRIMARY_DIM, 0, 12));
        voiceTip.setOnClickListener(v -> showVoiceDialog("pl"));
        LinearLayout.LayoutParams tlp = mb(0);
        tlp.setMargins(0, dp(12), 0, 0);
        card.addView(voiceTip, tlp);
        return card;
    }

    private View voiceRow(String langName, TextView valueView, String lang) {
        LinearLayout r = row();
        r.setPadding(dp(12), dp(10), dp(8), dp(10));
        r.setBackground(round(C_SURFACE2, 0, 12));
        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        TextView l = new TextView(this);
        l.setText(langName);
        l.setTextColor(C_MUTED);
        l.setTextSize(11);
        col.addView(l);
        valueView.setTextColor(C_TEXT);
        valueView.setTextSize(14);
        valueView.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        valueView.setSingleLine(true);
        valueView.setEllipsize(TextUtils.TruncateAt.END);
        col.addView(valueView);
        r.addView(col, new LinearLayout.LayoutParams(0, -2, 1f));
        Button change = ghostBtn("Zmień ›");
        change.setTextColor(C_PRIMARY);
        r.addView(change);
        View.OnClickListener open = v -> showVoiceDialog(lang);
        r.setOnClickListener(open);
        change.setOnClickListener(open);
        return r;
    }

    private View buildPlayer() {
        LinearLayout p = new LinearLayout(this);
        p.setOrientation(LinearLayout.VERTICAL);
        p.setPadding(dp(16), dp(8), dp(8), dp(8));
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(C_SURFACE);
        float r = dp(22);
        bg.setCornerRadii(new float[]{r, r, r, r, 0, 0, 0, 0});
        bg.setStroke(dp(1), C_BORDER);
        p.setBackground(bg);
        p.setElevation(dp(8));
        playerView = p;
        playerExpanded = getSharedPreferences("ui", MODE_PRIVATE).getBoolean("player_expanded", false);

        LinearLayout titleRow = row();
        nowTitle = new TextView(this);
        nowTitle.setTextColor(C_TEXT);
        nowTitle.setTextSize(14);
        nowTitle.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        nowTitle.setSingleLine(true);
        nowTitle.setEllipsize(TextUtils.TruncateAt.END);
        titleRow.addView(nowTitle, new LinearLayout.LayoutParams(0, -2, 1f));
        voiceLoading = new ProgressBar(this, null, android.R.attr.progressBarStyleSmall);
        voiceLoading.setVisibility(View.GONE);
        titleRow.addView(voiceLoading, new LinearLayout.LayoutParams(dp(18), dp(18)));
        miniPlayBtn = circleBtn("▶", true);
        miniPlayBtn.setTextSize(18);
        LinearLayout.LayoutParams mpl = new LinearLayout.LayoutParams(dp(42), dp(42));
        mpl.setMargins(dp(8), 0, 0, 0);
        titleRow.addView(miniPlayBtn, mpl);
        expandBtn = ghostBtn("▴");
        expandBtn.setTextSize(16);
        expandBtn.setTextColor(C_MUTED);
        titleRow.addView(expandBtn, new LinearLayout.LayoutParams(dp(40), dp(42)));
        p.addView(titleRow);

        LinearLayout details = new LinearLayout(this);
        details.setOrientation(LinearLayout.VERTICAL);
        details.setPadding(0, 0, dp(8), dp(4));
        playerDetails = details;

        nowPreview = new TextView(this);
        nowPreview.setTextColor(C_MUTED);
        nowPreview.setTextSize(12);
        nowPreview.setMaxLines(2);
        nowPreview.setEllipsize(TextUtils.TruncateAt.END);
        nowPreview.setLineSpacing(dp(2), 1f);
        LinearLayout.LayoutParams pvl = mb(4);
        pvl.setMargins(0, dp(2), 0, dp(4));
        details.addView(nowPreview, pvl);

        LinearLayout prog = row();
        progressSeek = new SeekBar(this);
        prog.addView(progressSeek, new LinearLayout.LayoutParams(0, dp(30), 1f));
        progressText = new TextView(this);
        progressText.setTextColor(C_MUTED);
        progressText.setTextSize(11);
        progressText.setTypeface(Typeface.DEFAULT_BOLD);
        prog.addView(progressText);
        details.addView(prog);

        LinearLayout ctr = new LinearLayout(this);
        ctr.setOrientation(LinearLayout.HORIZONTAL);
        ctr.setGravity(Gravity.CENTER);
        Button prev = circleBtn("⏮", false);
        playPauseBtn = circleBtn("▶", true);
        Button next = circleBtn("⏭", false);
        Button stop = circleBtn("⏹", false);
        stop.setTextColor(C_DANGER);
        stop.setBackground(round(C_DANGER_BG, 0, 26));
        LinearLayout.LayoutParams small = new LinearLayout.LayoutParams(dp(50), dp(50));
        small.setMargins(dp(6), 0, dp(6), 0);
        LinearLayout.LayoutParams big = new LinearLayout.LayoutParams(dp(68), dp(68));
        big.setMargins(dp(10), 0, dp(10), 0);
        ctr.addView(prev, small);
        ctr.addView(playPauseBtn, big);
        ctr.addView(next, small);
        ctr.addView(stop, small);
        LinearLayout.LayoutParams cl = mb(0);
        cl.setMargins(0, dp(4), 0, dp(10));
        details.addView(ctr, cl);

        // Tempo: − [presety] +
        LinearLayout rate = row();
        Button minus = chip("−");
        rate.addView(minus, new LinearLayout.LayoutParams(dp(36), dp(32)));
        for (float preset : RATE_PRESETS) {
            Button c = chip(fmtRate(preset));
            c.setTag(preset);
            c.setOnClickListener(v -> reader.setRate((Float) v.getTag()));
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, dp(32), 1f);
            lp.setMargins(dp(4), 0, 0, 0);
            rate.addView(c, lp);
            rateChips.add(c);
        }
        Button plus = chip("+");
        LinearLayout.LayoutParams plp = new LinearLayout.LayoutParams(dp(36), dp(32));
        plp.setMargins(dp(4), 0, 0, 0);
        rate.addView(plus, plp);
        details.addView(rate);

        rateValue = new TextView(this);
        rateValue.setTextColor(C_MUTED);
        rateValue.setTextSize(11);
        rateValue.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams rv = mb(0);
        rv.setMargins(0, dp(4), 0, 0);
        details.addView(rateValue, rv);

        p.addView(details);

        View.OnClickListener toggle = v -> {
            playerExpanded = !playerExpanded;
            getSharedPreferences("ui", MODE_PRIVATE).edit().putBoolean("player_expanded", playerExpanded).apply();
            onReaderState();
        };
        titleRow.setOnClickListener(toggle);
        expandBtn.setOnClickListener(toggle);
        miniPlayBtn.setOnClickListener(v -> onPlayPressed());

        prev.setOnClickListener(v -> reader.prev());
        next.setOnClickListener(v -> reader.next());
        stop.setOnClickListener(v -> reader.stop());
        playPauseBtn.setOnClickListener(v -> onPlayPressed());
        minus.setOnClickListener(v -> reader.setRate(Math.round((reader.getRate() - 0.05f) * 100) / 100f));
        plus.setOnClickListener(v -> reader.setRate(Math.round((reader.getRate() + 0.05f) * 100) / 100f));

        progressSeek.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar sb, int v, boolean fromUser) {
                if (fromUser && reader.getChunkCount() > 0) {
                    progressText.setText((v + 1) + " / " + reader.getChunkCount());
                }
            }
            @Override public void onStartTrackingTouch(SeekBar sb) { userSeeking = true; }
            @Override public void onStopTrackingTouch(SeekBar sb) {
                userSeeking = false;
                reader.seek(sb.getProgress());
            }
        });
        return p;
    }

    // ════════════════════════════════════════════════════════════════════
    //  Odświeżanie stanu
    // ════════════════════════════════════════════════════════════════════

    @Override
    public void onReaderState() {
        if (statusText == null) return;
        statusText.setText(reader.getStatus());

        // Tekst z Readera → pole (tylko gdy się zmienił)
        String t = reader.getText();
        if (t != shownText) {
            shownText = t;
            highlight = null;
            highlightedIndex = -2;
            textInput.setText(t);
        }
        int count = reader.getChunkCount();
        textMeta.setText(t.isEmpty() ? "" : String.format(Locale.US, "%,d znaków · %s",
            t.length(), formatDuration(Math.round(t.length() / (15f * reader.getRate())))).replace(',', ' '));

        // Odtwarzacz
        boolean has = count > 0;
        nowTitle.setText(has ? reader.displayTitle() : "Nic nie jest czytane");
        nowPreview.setText(has ? reader.currentChunkPreview() : "Wklej adres strony albo tekst powyżej.");
        progressSeek.setEnabled(has);
        progressSeek.setMax(Math.max(0, count - 1));
        if (!userSeeking) {
            progressSeek.setProgress(Math.min(reader.getIndex(), Math.max(0, count - 1)));
            progressText.setText(has ? (reader.getIndex() + 1) + " / " + count
                + "  ·  " + formatDuration(reader.remainingSeconds()) : "—");
        }
        playPauseBtn.setText(reader.isPlaying() ? "⏸" : "▶");
        miniPlayBtn.setText(reader.isPlaying() ? "⏸" : "▶");
        // Pasek tylko gdy jest co czytać; domyślnie zwinięty do jednego wiersza
        playerView.setVisibility(has ? View.VISIBLE : View.GONE);
        playerDetails.setVisibility(playerExpanded ? View.VISIBLE : View.GONE);
        miniPlayBtn.setVisibility(playerExpanded ? View.GONE : View.VISIBLE);
        expandBtn.setText(playerExpanded ? "▾" : "▴");
        voiceLoading.setVisibility(reader.isLoading() ? View.VISIBLE : View.GONE);

        float rate = reader.getRate();
        rateValue.setText("Tempo " + String.format(Locale.US, "%.2fx", rate));
        for (Button c : rateChips) {
            boolean sel = Math.abs((Float) c.getTag() - rate) < 0.01f;
            c.setTextColor(sel ? C_ON_PRIMARY : C_PRIMARY);
            c.setBackground(round(sel ? C_PRIMARY : C_PRIMARY_DIM, 0, 10));
        }

        String mode = reader.getLangMode();
        for (Button b : langButtons) {
            boolean sel = mode.equals(b.getTag());
            b.setTextColor(sel ? C_TEXT : C_MUTED);
            b.setTypeface(Typeface.create(sel ? "sans-serif-medium" : "sans-serif", Typeface.NORMAL));
            b.setBackground(sel ? round(C_SURFACE, C_BORDER, 10) : null);
        }

        voicePlLabel.setText(reader.voiceLabel("pl"));
        voiceEnLabel.setText(reader.voiceLabel("en"));
        boolean anyNeural = reader.getVoice("pl").startsWith(Reader.VOICE_NEURAL_PREFIX)
            || reader.getVoice("en").startsWith(Reader.VOICE_NEURAL_PREFIX);
        voiceTip.setVisibility(anyNeural ? View.GONE : View.VISIBLE);

        applyHighlight();
        if (voiceDialog != null && voiceDialog.isShowing()) renderVoiceList(false);
    }

    private void applyHighlight() {
        int i = reader.isActive() ? reader.getIndex() : -1;
        if (i == highlightedIndex) return;
        Editable e = textInput.getText();
        if (highlight != null) e.removeSpan(highlight);
        highlight = null;
        highlightedIndex = i;
        if (i < 0 || shownText == null || !shownText.contentEquals(e)) return;
        int a = reader.chunkStart(i), b = reader.chunkEnd(i);
        if (a < 0 || b > e.length() || b <= a) return;
        highlight = new BackgroundColorSpan(C_HIGHLIGHT);
        e.setSpan(highlight, a, b, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE);
        textInput.post(() -> {
            if (textInput.getLayout() == null) return;
            int line = textInput.getLayout().getLineForOffset(a);
            int y = textInput.getLayout().getLineTop(line) - dp(40);
            textInput.scrollTo(0, Math.max(0, y));
        });
    }

    // ════════════════════════════════════════════════════════════════════
    //  Akcje
    // ════════════════════════════════════════════════════════════════════

    private void onPlayPressed() {
        hideKeyboard();
        String editor = textInput.getText().toString();
        boolean same = shownText != null && shownText.contentEquals(editor);
        if (same && reader.hasContent()) { reader.togglePlayPause(); return; }
        if (editor.trim().isEmpty()) { toast("Wklej adres strony albo tekst"); return; }
        reader.play(editor, "", 0);
    }

    private void readEditor(int offset) {
        String editor = textInput.getText().toString();
        if (editor.trim().isEmpty()) { toast("Brak tekstu do czytania"); return; }
        boolean same = shownText != null && shownText.contentEquals(editor);
        if (same && reader.hasContent()) {
            if (reader.isActive()) reader.seekToOffset(offset);
            else { reader.seekToOffset(offset); reader.togglePlayPause(); }
        } else {
            reader.play(editor, "", offset);
        }
    }

    private void handleIncomingIntent(Intent intent) {
        if (intent == null) return;
        String action = intent.getAction();
        CharSequence shared = null;
        if (Intent.ACTION_SEND.equals(action)) shared = intent.getCharSequenceExtra(Intent.EXTRA_TEXT);
        else if (Intent.ACTION_PROCESS_TEXT.equals(action)) shared = intent.getCharSequenceExtra(Intent.EXTRA_PROCESS_TEXT);
        if (shared == null) return;
        String value = shared.toString().trim();
        intent.setAction(null); // nie czytaj ponownie po obróceniu ekranu
        String url = extractUrl(value);
        if (url != null && value.length() < 600) {
            urlInput.setText(url);
            extractAndRead();
        } else {
            String subject = intent.getStringExtra(Intent.EXTRA_SUBJECT);
            reader.play(value, subject != null ? subject : "", 0);
        }
    }

    // ════════════════════════════════════════════════════════════════════
    //  Okno wyboru głosu
    // ════════════════════════════════════════════════════════════════════

    private void showVoiceDialog(String lang) {
        voiceDialogLang = lang;
        ScrollView sv = new ScrollView(this);
        voiceList = new LinearLayout(this);
        voiceList.setOrientation(LinearLayout.VERTICAL);
        voiceList.setPadding(dp(18), dp(4), dp(18), dp(12));
        sv.addView(voiceList);
        renderVoiceList(true);

        int theme = isDarkMode() ? android.R.style.Theme_Material_Dialog_Alert
            : android.R.style.Theme_Material_Light_Dialog_Alert;
        voiceDialog = new AlertDialog.Builder(this, theme)
            .setTitle("pl".equals(lang) ? "Głos — polski" : "Głos — angielski")
            .setView(sv)
            .setPositiveButton("Gotowe", null)
            .create();
        voiceDialog.setOnDismissListener(d -> { if (reader.isPreviewing()) reader.stop(); voiceList = null; });
        voiceDialog.show();
        if (voiceDialog.getWindow() != null) {
            voiceDialog.getWindow().setBackgroundDrawable(round(C_SURFACE, 0, 24));
        }
    }

    private void renderVoiceList(boolean force) {
        if (voiceList == null) return;
        long now = System.currentTimeMillis();
        if (!force && now - lastListRender < 200) return;
        lastListRender = now;
        String lang = voiceDialogLang;
        String selected = reader.getVoice(lang);
        voiceList.removeAllViews();

        // ── Naturalne ──
        voiceList.addView(label("NATURALNE · NEURONOWE (OFFLINE)"), mbTop(10, 4));
        TextView info = new TextView(this);
        info.setText("Głosy Piper działające na telefonie bez internetu. Pobierasz raz (~65 MB).");
        info.setTextColor(C_MUTED);
        info.setTextSize(12);
        voiceList.addView(info, mb(8));

        for (VoiceCatalog.Model m : VoiceCatalog.forLang(this, lang)) {
            voiceList.addView(neuralRow(m, lang, selected), mb(6));
        }

        Button importBtn = secondaryBtn("＋ Importuj własny głos Piper (.onnx + .json)");
        importBtn.setTextSize(12);
        importBtn.setOnClickListener(v -> startImport());
        voiceList.addView(importBtn, mbTop(4, 4));

        // ── Systemowe ──
        voiceList.addView(label("SYSTEMOWE"), mbTop(16, 6));
        voiceList.addView(systemRow(Reader.VOICE_SYSTEM_AUTO, "Automatycznie",
            "najlepszy zainstalowany głos", lang, selected), mb(6));
        for (Voice v : reader.systemEngine.voicesFor(lang)) {
            String spec = Reader.VOICE_SYSTEM_PREFIX + v.getName();
            String q = v.getQuality() >= Voice.QUALITY_VERY_HIGH ? "bardzo wysoka jakość"
                : v.getQuality() >= Voice.QUALITY_HIGH ? "wysoka jakość" : "standard";
            String net = v.isNetworkConnectionRequired() ? " · wymaga internetu" : " · offline";
            voiceList.addView(systemRow(spec, Reader.prettyVoiceName(v.getName()),
                v.getLocale().toLanguageTag() + " · " + q + net, lang, selected), mb(6));
        }
        Button sys = ghostBtn("Pobierz więcej głosów systemowych ›");
        sys.setTextColor(C_PRIMARY);
        sys.setOnClickListener(v -> {
            try { startActivity(new Intent("com.android.settings.TTS_SETTINGS")); }
            catch (Exception e) { toast("Otwórz Ustawienia → Zamiana tekstu na mowę"); }
        });
        voiceList.addView(sys, mbTop(4, 0));
    }

    private View neuralRow(VoiceCatalog.Model m, String lang, String selected) {
        String spec = Reader.VOICE_NEURAL_PREFIX + m.id;
        boolean installed = VoiceCatalog.isInstalled(this, m.id);
        boolean isSel = spec.equals(selected);
        boolean downloading = VoiceCatalog.isDownloading(m.id);

        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(dp(12), dp(10), dp(8), dp(10));
        box.setBackground(round(isSel ? C_PRIMARY_DIM : C_SURFACE2, isSel ? C_PRIMARY : 0, 12));

        LinearLayout r = row();
        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        TextView name = new TextView(this);
        name.setText((isSel ? "✓ " : "") + m.name);
        name.setTextColor(isSel ? C_PRIMARY : C_TEXT);
        name.setTextSize(15);
        name.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        col.addView(name);
        TextView desc = new TextView(this);
        String size = installed
            ? String.format(Locale.US, "pobrany · %d MB", VoiceCatalog.sizeOnDisk(this, m.id) / (1024 * 1024))
            : (m.approxMb > 0 ? "~" + m.approxMb + " MB" : "");
        desc.setText(m.desc + (size.isEmpty() ? "" : " · " + size));
        desc.setTextColor(C_MUTED);
        desc.setTextSize(12);
        col.addView(desc);
        r.addView(col, new LinearLayout.LayoutParams(0, -2, 1f));

        if (installed) {
            Button play = chip("▶");
            play.setOnClickListener(v -> reader.preview(spec, lang));
            r.addView(play, new LinearLayout.LayoutParams(dp(40), dp(34)));
            if (!isSel) {
                Button use = pill("Wybierz", true);
                use.setOnClickListener(v -> { reader.setVoice(lang, spec); renderVoiceList(true); });
                LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-2, dp(34));
                lp.setMargins(dp(6), 0, 0, 0);
                r.addView(use, lp);
            }
            box.setOnLongClickListener(v -> { confirmDelete(m, lang); return true; });
        } else if (downloading) {
            Button cancel = pill("Anuluj", false);
            cancel.setOnClickListener(v -> VoiceCatalog.cancel(m.id));
            r.addView(cancel, new LinearLayout.LayoutParams(-2, dp(34)));
        } else {
            Button dl = pill("Pobierz", true);
            dl.setOnClickListener(v -> { VoiceCatalog.download(this, m); renderVoiceList(true); });
            r.addView(dl, new LinearLayout.LayoutParams(-2, dp(34)));
        }
        box.addView(r);

        if (downloading) {
            long[] p = VoiceCatalog.progressOf(m.id);
            ProgressBar bar = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
            bar.setMax(1000);
            if (p != null && p[1] > 0) bar.setProgress((int) Math.min(1000, p[0] * 1000 / p[1]));
            LinearLayout.LayoutParams bl = new LinearLayout.LayoutParams(-1, dp(6));
            bl.setMargins(0, dp(8), dp(4), 0);
            box.addView(bar, bl);
            TextView pt = new TextView(this);
            pt.setTextColor(C_MUTED);
            pt.setTextSize(11);
            pt.setText(p != null ? String.format(Locale.US, "Pobieranie… %.0f / %.0f MB",
                p[0] / 1048576.0, p[1] / 1048576.0) : "Łączenie…");
            box.addView(pt);
        }
        return box;
    }

    private View systemRow(String spec, String title, String sub, String lang, String selected) {
        boolean isSel = spec.equals(selected);
        LinearLayout r = row();
        r.setPadding(dp(12), dp(10), dp(8), dp(10));
        r.setBackground(round(isSel ? C_PRIMARY_DIM : C_SURFACE2, isSel ? C_PRIMARY : 0, 12));
        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        TextView t = new TextView(this);
        t.setText((isSel ? "✓ " : "") + title);
        t.setTextColor(isSel ? C_PRIMARY : C_TEXT);
        t.setTextSize(14);
        col.addView(t);
        TextView s = new TextView(this);
        s.setText(sub);
        s.setTextColor(C_MUTED);
        s.setTextSize(11);
        col.addView(s);
        r.addView(col, new LinearLayout.LayoutParams(0, -2, 1f));
        Button play = chip("▶");
        play.setOnClickListener(v -> reader.preview(spec, lang));
        r.addView(play, new LinearLayout.LayoutParams(dp(40), dp(34)));
        r.setOnClickListener(v -> { reader.setVoice(lang, spec); renderVoiceList(true); });
        return r;
    }

    private void confirmDelete(VoiceCatalog.Model m, String lang) {
        new AlertDialog.Builder(this)
            .setTitle("Usunąć głos " + m.name + "?")
            .setMessage("Zwolni miejsce na telefonie. Możesz go pobrać ponownie.")
            .setNegativeButton("Anuluj", null)
            .setPositiveButton("Usuń", (d, w) -> {
                if (reader.getVoice(lang).equals(Reader.VOICE_NEURAL_PREFIX + m.id)) {
                    reader.stop();
                    reader.setVoice(lang, Reader.VOICE_SYSTEM_AUTO);
                }
                VoiceCatalog.delete(this, m.id);
                renderVoiceList(true);
                onReaderState();
            })
            .show();
    }

    @Override
    public void onProgress(String id, long done, long total) { renderVoiceList(false); }

    @Override
    public void onDone(String id, String error) {
        VoiceCatalog.Model m = VoiceCatalog.find(this, id);
        if (error != null) {
            if (!"Anulowano".equals(error)) toast("Nie udało się pobrać głosu: " + error);
        } else if (m != null) {
            toast("Pobrano głos " + m.name);
            // Pierwszy pobrany naturalny głos — ustaw go od razu
            if (!reader.getVoice(m.lang).startsWith(Reader.VOICE_NEURAL_PREFIX)) {
                reader.setVoice(m.lang, Reader.VOICE_NEURAL_PREFIX + m.id);
            }
        }
        renderVoiceList(true);
        onReaderState();
    }

    // ── Import własnego głosu ───────────────────────────────────────────

    private void startImport() {
        if (!VoiceCatalog.hasAnyEspeakData(this)) {
            new AlertDialog.Builder(this)
                .setTitle("Najpierw pobierz jeden głos")
                .setMessage("Własny głos Piper potrzebuje plików wymowy (espeak-ng), które są w każdym głosie z listy. Pobierz dowolny głos, a potem zaimportuj swój.")
                .setPositiveButton("OK", null)
                .show();
            return;
        }
        new AlertDialog.Builder(this)
            .setTitle("Import własnego głosu")
            .setMessage("Wybierz DWA pliki naraz: model .onnx oraz jego plik .onnx.json (taki jak z treningu Piper albo z huggingface.co/rhasspy/piper-voices).")
            .setNegativeButton("Anuluj", null)
            .setPositiveButton("Wybierz pliki", (d, w) -> {
                Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT);
                i.addCategory(Intent.CATEGORY_OPENABLE);
                i.setType("*/*");
                i.putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true);
                try { startActivityForResult(i, REQ_IMPORT); }
                catch (Exception e) { toast("Brak menedżera plików"); }
            })
            .show();
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode != REQ_IMPORT || resultCode != RESULT_OK || data == null) return;
        List<Uri> uris = new ArrayList<>();
        if (data.getClipData() != null) {
            ClipData cd = data.getClipData();
            for (int i = 0; i < cd.getItemCount(); i++) uris.add(cd.getItemAt(i).getUri());
        } else if (data.getData() != null) {
            uris.add(data.getData());
        }
        Uri onnx = null, json = null;
        for (Uri u : uris) {
            String n = VoiceCatalog.displayName(this, u).toLowerCase(Locale.ROOT);
            if (n.endsWith(".json")) json = u;
            else if (n.endsWith(".onnx")) onnx = u;
        }
        if (onnx == null || json == null) {
            toast("Zaznacz oba pliki: .onnx i .onnx.json");
            return;
        }
        toast("Importuję głos…");
        VoiceCatalog.importCustom(this, onnx, json, (id, err) -> {
            if (err != null) { toast("Import nieudany: " + err); return; }
            VoiceCatalog.Model m = VoiceCatalog.find(this, id);
            if (m != null) {
                reader.setVoice(m.lang, Reader.VOICE_NEURAL_PREFIX + m.id);
                toast("Dodano głos " + m.name);
                if (voiceDialog != null && voiceDialog.isShowing() && !m.lang.equals(voiceDialogLang)) {
                    voiceDialogLang = m.lang;
                }
            }
            renderVoiceList(true);
            onReaderState();
        });
    }

    // ════════════════════════════════════════════════════════════════════
    //  WebView i wyciąganie artykułu
    // ════════════════════════════════════════════════════════════════════

    private void configureWebView() {
        WebSettings s = webView.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setLoadsImagesAutomatically(false);
        s.setMediaPlaybackRequiresUserGesture(true);
        webView.setWebChromeClient(new android.webkit.WebChromeClient() {
            @Override public void onProgressChanged(WebView view, int p) {
                loadingBar.setProgress(p);
            }
        });
    }

    private void extractAndRead() {
        hideKeyboard();
        String url = normalizeUrl(urlInput.getText().toString());
        if (url == null) { toast("Wpisz adres strony"); return; }
        urlInput.setText(url);
        statusText.setText("Ładuję stronę…");
        loadingBar.setProgress(0);
        loadingBar.setVisibility(View.VISIBLE);
        final boolean[] done = {false};
        webView.setWebViewClient(new WebViewClient() {
            @Override public void onPageFinished(WebView view, String u) {
                if (done[0]) return;
                // daj stronie chwilę na doładowanie treści przez JS
                main.postDelayed(() -> {
                    if (done[0]) return;
                    done[0] = true;
                    runExtraction(url);
                }, 900);
            }
        });
        if (extractTimeout != null) main.removeCallbacks(extractTimeout);
        extractTimeout = () -> {
            if (done[0]) return;
            done[0] = true;
            runExtraction(url);  // spróbuj z tym, co się wczytało
        };
        main.postDelayed(extractTimeout, 25000);
        webView.loadUrl(url);
    }

    private void runExtraction(String url) {
        if (webView == null) return;
        if (extractTimeout != null) main.removeCallbacks(extractTimeout);
        statusText.setText("Wyciągam artykuł…");
        String js = "(function(){"
            + "var junk='script,style,noscript,nav,aside,footer,form,button,iframe,svg,figure figcaption,"
            + "[aria-hidden=\"true\"],[role=\"navigation\"],[role=\"complementary\"],.ad,.ads,.advert,"
            + ".advertisement,.share,.social,.related,.newsletter,.comments,#comments,.cookie,.paywall';"
            + "document.querySelectorAll(junk).forEach(function(e){e.style.display='none';});"
            + "var sel=['article','[itemprop=\"articleBody\"]','[role=\"main\"]','main','.article-body',"
            + "'.article-content','.post-content','.entry-content','.story-body','#article-body','.body-copy'];"
            + "var root=null;"
            + "for(var i=0;i<sel.length&&!root;i++){var el=document.querySelector(sel[i]);"
            + " if(el&&el.innerText.trim().length>300)root=el;}"
            + "if(!root){var best=null,bs=0;document.querySelectorAll('div,section').forEach(function(el){"
            + " var t=el.innerText||'';if(t.length<200)return;"
            + " var c=(el.className+' '+el.id).toLowerCase();"
            + " if(/comment|sidebar|nav|footer|header|\\bad\\b|banner|widget|menu|cookie|popup|modal|overlay|related/.test(c))return;"
            + " var s=el.querySelectorAll('p').length*4+t.length/80;if(s>bs){bs=s;best=el;}});root=best||document.body;}"
            + "var h1=document.querySelector('h1');"
            + "var title=(h1&&h1.innerText.trim())||document.title||'';"
            + "return JSON.stringify({title:title,text:root.innerText});"
            + "})()";
        webView.evaluateJavascript(js, value -> {
            loadingBar.setVisibility(View.GONE);
            String title = "", text = "";
            try {
                String json = new JSONObject("{\"v\":" + value + "}").getString("v");
                JSONObject o = new JSONObject(json);
                title = o.optString("title", "").trim();
                text = o.optString("text", "");
            } catch (Exception ignored) {}
            text = Reader.cleanText(text);
            if (text.length() < 40) {
                statusText.setText("Nie znaleziono tekstu. Strona może wymagać logowania.");
                return;
            }
            if (!title.isEmpty() && !text.startsWith(title)) text = title + "\n\n" + text;
            reader.play(text, title, 0);
        });
    }

    // ════════════════════════════════════════════════════════════════════
    //  Fabryki widoków
    // ════════════════════════════════════════════════════════════════════

    private LinearLayout card() {
        LinearLayout c = new LinearLayout(this);
        c.setOrientation(LinearLayout.VERTICAL);
        c.setPadding(dp(16), dp(14), dp(16), dp(16));
        c.setBackground(round(C_SURFACE, C_BORDER, 20));
        return c;
    }

    private LinearLayout row() {
        LinearLayout r = new LinearLayout(this);
        r.setOrientation(LinearLayout.HORIZONTAL);
        r.setGravity(Gravity.CENTER_VERTICAL);
        return r;
    }

    private TextView label(String text) {
        TextView tv = new TextView(this);
        tv.setText(text);
        tv.setTextColor(C_MUTED);
        tv.setTextSize(10);
        tv.setTypeface(Typeface.DEFAULT_BOLD);
        tv.setLetterSpacing(0.12f);
        return tv;
    }

    private void styleInput(EditText e) {
        e.setTextColor(C_TEXT);
        e.setHintTextColor(C_MUTED);
        e.setTextSize(14);
        e.setPadding(dp(14), dp(11), dp(14), dp(11));
        e.setBackground(round(C_SURFACE2, C_BORDER, 12));
    }

    private Button baseBtn(String text, int size) {
        Button b = new Button(this);
        b.setText(text);
        b.setAllCaps(false);
        b.setTextSize(size);
        b.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        b.setStateListAnimator(null);
        b.setMinHeight(0);
        b.setMinimumHeight(0);
        b.setMinWidth(0);
        b.setMinimumWidth(0);
        return b;
    }

    private Button primaryBtn(String text) {
        Button b = baseBtn(text, 14);
        b.setTextColor(C_ON_PRIMARY);
        b.setBackground(round(C_PRIMARY, 0, 12));
        b.setPadding(dp(16), 0, dp(16), 0);
        return b;
    }

    private Button secondaryBtn(String text) {
        Button b = baseBtn(text, 14);
        b.setTextColor(C_TEXT);
        b.setBackground(round(C_SURFACE2, C_BORDER, 12));
        b.setPadding(dp(14), 0, dp(14), 0);
        return b;
    }

    private Button ghostBtn(String text) {
        Button b = baseBtn(text, 13);
        b.setTextColor(C_MUTED);
        b.setBackground(null);
        b.setPadding(dp(10), dp(6), dp(6), dp(6));
        return b;
    }

    private Button pill(String text, boolean filled) {
        Button b = baseBtn(text, 12);
        b.setTextColor(filled ? C_ON_PRIMARY : C_PRIMARY);
        b.setBackground(round(filled ? C_PRIMARY : C_PRIMARY_DIM, 0, 17));
        b.setPadding(dp(14), 0, dp(14), 0);
        return b;
    }

    private Button chip(String text) {
        Button b = baseBtn(text, 12);
        b.setTextColor(C_PRIMARY);
        b.setBackground(round(C_PRIMARY_DIM, 0, 10));
        b.setPadding(0, 0, 0, 0);
        return b;
    }

    private Button circleBtn(String text, boolean primary) {
        Button b = baseBtn(text, primary ? 26 : 18);
        b.setTextColor(primary ? C_ON_PRIMARY : C_PRIMARY);
        b.setBackground(round(primary ? C_PRIMARY : C_PRIMARY_DIM, 0, primary ? 34 : 25));
        b.setPadding(0, 0, 0, 0);
        return b;
    }

    private GradientDrawable round(int fill, int stroke, int radiusDp) {
        GradientDrawable d = new GradientDrawable();
        d.setColor(fill);
        d.setCornerRadius(dp(radiusDp));
        if (stroke != 0) d.setStroke(dp(1), stroke);
        return d;
    }

    private LinearLayout.LayoutParams mb(int bottomDp) {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
        lp.setMargins(0, 0, 0, dp(bottomDp));
        return lp;
    }

    private LinearLayout.LayoutParams mbTop(int topDp, int bottomDp) {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.setMargins(0, dp(topDp), 0, dp(bottomDp));
        return lp;
    }

    private int dp(int v) { return Math.round(v * getResources().getDisplayMetrics().density); }

    // ════════════════════════════════════════════════════════════════════
    //  Pomocnicze
    // ════════════════════════════════════════════════════════════════════

    private static String fmtRate(float r) {
        String s = String.format(Locale.US, "%.2f", r);
        s = s.replaceAll("0$", "").replaceAll("\\.$", ".0");
        return s + "×";
    }

    private static String formatDuration(int sec) {
        if (sec <= 0) return "0 min";
        if (sec < 60) return "<1 min";
        int m = Math.round(sec / 60f);
        if (m < 60) return m + " min";
        return (m / 60) + " h " + (m % 60) + " min";
    }

    private void hideKeyboard() {
        InputMethodManager imm = (InputMethodManager) getSystemService(Context.INPUT_METHOD_SERVICE);
        View f = getCurrentFocus();
        if (imm != null && f != null) imm.hideSoftInputFromWindow(f.getWindowToken(), 0);
        if (f != null) f.clearFocus();
    }

    private void toast(String s) { Toast.makeText(this, s, Toast.LENGTH_SHORT).show(); }

    private String clipboardText() {
        ClipboardManager cb = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
        if (cb == null || !cb.hasPrimaryClip()) return "";
        ClipData clip = cb.getPrimaryClip();
        if (clip == null || clip.getItemCount() == 0) return "";
        CharSequence t = clip.getItemAt(0).coerceToText(this);
        return t == null ? "" : t.toString().trim();
    }

    private static String normalizeUrl(String raw) {
        if (raw == null) return null;
        String url = raw.trim();
        if (url.isEmpty()) return null;
        if (!url.startsWith("http://") && !url.startsWith("https://")) url = "https://" + url;
        return url;
    }

    private static boolean looksLikeUrl(String v) { return extractUrl(v) != null && v.length() < 600; }

    /** Wyciąga adres z udostępnionego tekstu (np. „Tytuł https://…”). */
    private static String extractUrl(String v) {
        java.util.regex.Matcher m = java.util.regex.Pattern.compile("https?://\\S+").matcher(v);
        if (m.find()) return m.group();
        String t = v.trim();
        if (!t.contains(" ") && t.matches("[\\w.-]+\\.[a-z]{2,}(/\\S*)?")) return "https://" + t;
        return null;
    }

    // ════════════════════════════════════════════════════════════════════
    //  Aktualizacje z GitHub
    // ════════════════════════════════════════════════════════════════════

    private void checkForUpdate() {
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
                JSONObject json = new JSONObject(sb.toString());
                String latest = json.optString("tag_name", "").replaceAll("[^0-9.]", "");
                String page = json.optString("html_url", "");
                String current = getPackageManager().getPackageInfo(getPackageName(), 0).versionName;
                if (!latest.isEmpty() && isNewer(latest, current)) {
                    runOnUiThread(() -> {
                        updateButton.setText("↑ Wersja " + latest);
                        updateButton.setVisibility(View.VISIBLE);
                        updateButton.setOnClickListener(v ->
                            startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(page))));
                    });
                }
            } catch (Exception ignored) {}
        }).start();
    }

    private static boolean isNewer(String latest, String current) {
        try {
            String[] l = latest.split("\\."), c = current.split("\\.");
            for (int i = 0; i < Math.max(l.length, c.length); i++) {
                int a = i < l.length ? Integer.parseInt(l[i]) : 0;
                int b = i < c.length ? Integer.parseInt(c[i]) : 0;
                if (a != b) return a > b;
            }
        } catch (Exception ignored) {}
        return false;
    }

    // ════════════════════════════════════════════════════════════════════
    //  Kafelek
    // ════════════════════════════════════════════════════════════════════

    private void requestTile() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
            toast("Dodaj kafelek ręcznie: rozwiń panel → edytuj kafelki → „Czytnik”");
            return;
        }
        StatusBarManager sbm = getSystemService(StatusBarManager.class);
        if (sbm == null) return;
        sbm.requestAddTileService(new ComponentName(this, ReaderTileService.class), "Czytnik",
            Icon.createWithResource(this, R.drawable.ic_reader_tile), getMainExecutor(), result -> {
                if (result == StatusBarManager.TILE_ADD_REQUEST_RESULT_TILE_ADDED) toast("Kafelek dodany");
                else if (result == StatusBarManager.TILE_ADD_REQUEST_RESULT_TILE_ALREADY_ADDED) toast("Kafelek już jest w panelu");
            });
    }
}
