package pl.local.czytnikstrony;

import android.app.Activity;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.content.res.Configuration;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Build;
import android.os.Bundle;
import android.speech.tts.TextToSpeech;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.Window;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.util.Locale;

public class FloatingReaderActivity extends Activity implements TextToSpeech.OnInitListener {
    private static final int MAX_TTS_CHUNK = 260;

    private int C_SURFACE, C_SURFACE2, C_PRIMARY, C_PRIMARY_DIM, C_ON_PRIMARY;
    private int C_TEXT, C_MUTED, C_BORDER, C_DANGER, C_DANGER_BG;

    private TextView statusText;
    private TextView clipPreview;
    private TextToSpeech tts;
    private boolean ttsReady = false;
    private float speechRate = 0.95f;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        initColors();
        configureWindow();
        buildUi();
        tts = new TextToSpeech(this, this);
    }

    @Override
    protected void onResume() {
        super.onResume();
        refreshClipPreview();
    }

    @Override
    protected void onDestroy() {
        if (tts != null) { tts.stop(); tts.shutdown(); }
        super.onDestroy();
    }

    @Override
    public void onInit(int status) {
        ttsReady = (status == TextToSpeech.SUCCESS);
        if (ttsReady) {
            tts.setLanguage(new Locale("pl", "PL"));
            applySpeechRate();
            setStatus("Gotowy.");
        } else {
            setStatus("TTS niedostępny.");
        }
    }

    // ════════════════════════════════════════════════════════════════════════
    //  Kolory — spójne z MainActivity (indigo/fiolet)
    // ════════════════════════════════════════════════════════════════════════

    private boolean isDarkMode() {
        int mask = getResources().getConfiguration().uiMode & Configuration.UI_MODE_NIGHT_MASK;
        return mask == Configuration.UI_MODE_NIGHT_YES;
    }

    private void initColors() {
        if (isDarkMode()) {
            C_SURFACE     = 0xFF241E16;
            C_SURFACE2    = 0xFF1A1410;
            C_PRIMARY     = 0xFFD4A020;
            C_PRIMARY_DIM = 0xFF2A1E06;
            C_ON_PRIMARY  = 0xFF1A1410;
            C_TEXT        = 0xFFF0EBE0;
            C_MUTED       = 0xFF8E8878;
            C_BORDER      = 0xFF2A1E06;
            C_DANGER      = 0xFFE05A00;
            C_DANGER_BG   = 0xFF2A1008;
        } else {
            C_SURFACE     = 0xFFF7F2E8;
            C_SURFACE2    = 0xFFEDE8DC;
            C_PRIMARY     = 0xFFB8820A;
            C_PRIMARY_DIM = 0xFFF5E8C0;
            C_ON_PRIMARY  = 0xFF1C1810;
            C_TEXT        = 0xFF1C1810;
            C_MUTED       = 0xFF8E8878;
            C_BORDER      = 0xFFF5E8C0;
            C_DANGER      = 0xFFC04E00;
            C_DANGER_BG   = 0xFFF7F2E8;
        }
    }

    // ════════════════════════════════════════════════════════════════════════
    //  Okno
    // ════════════════════════════════════════════════════════════════════════

    private void configureWindow() {
        Window window = getWindow();
        WindowManager.LayoutParams params = window.getAttributes();
        params.gravity = Gravity.TOP | Gravity.END;
        params.width   = dp(330);
        params.height  = WindowManager.LayoutParams.WRAP_CONTENT;
        params.x       = dp(10);
        params.y       = dp(72);
        window.setAttributes(params);
    }

    // ════════════════════════════════════════════════════════════════════════
    //  UI
    // ════════════════════════════════════════════════════════════════════════

    private void buildUi() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(14), dp(14), dp(14), dp(14));
        root.setBackground(mkRound(C_SURFACE, C_BORDER, 20));

        // ── Nagłówek (tytuł + [✕]) ─────────────────────────────────────────
        LinearLayout header = new LinearLayout(this);
        header.setOrientation(LinearLayout.HORIZONTAL);
        header.setGravity(Gravity.CENTER_VERTICAL);

        ImageView logo = new ImageView(this);
        logo.setImageResource(R.drawable.ic_app_logo);
        logo.setScaleType(ImageView.ScaleType.FIT_CENTER);
        LinearLayout.LayoutParams loglp = new LinearLayout.LayoutParams(dp(28), dp(28));
        loglp.setMargins(0, 0, dp(8), 0);
        loglp.gravity = Gravity.CENTER_VERTICAL;
        header.addView(logo, loglp);

        TextView titleTv = new TextView(this);
        titleTv.setText("Czytnik strony");
        titleTv.setTextColor(C_TEXT);
        titleTv.setTextSize(15);
        titleTv.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        header.addView(titleTv, new LinearLayout.LayoutParams(0, -2, 1f));

        Button closeX = new Button(this);
        closeX.setText("✕");
        closeX.setTextSize(14);
        closeX.setAllCaps(false);
        closeX.setTextColor(C_MUTED);
        closeX.setBackground(null);
        closeX.setMinHeight(0);
        closeX.setMinimumHeight(0);
        closeX.setPadding(dp(8), 0, 0, 0);
        closeX.setOnClickListener(v -> finish());
        header.addView(closeX);

        LinearLayout.LayoutParams hlp = new LinearLayout.LayoutParams(-1, -2);
        hlp.setMargins(0, 0, 0, dp(4));
        root.addView(header, hlp);

        // ── Status ─────────────────────────────────────────────────────────
        statusText = new TextView(this);
        statusText.setText("Startuje…");
        statusText.setTextColor(C_MUTED);
        statusText.setTextSize(11);
        LinearLayout.LayoutParams slp = new LinearLayout.LayoutParams(-1, -2);
        slp.setMargins(0, 0, 0, dp(10));
        root.addView(statusText, slp);

        // ── Podgląd schowka ─────────────────────────────────────────────────
        clipPreview = new TextView(this);
        clipPreview.setMaxLines(2);
        clipPreview.setEllipsize(TextUtils.TruncateAt.END);
        clipPreview.setTextColor(C_MUTED);
        clipPreview.setTextSize(12);
        clipPreview.setLineSpacing(dp(2), 1f);
        clipPreview.setPadding(dp(10), dp(8), dp(10), dp(8));
        clipPreview.setBackground(mkRound(C_SURFACE2, C_BORDER, 10));
        LinearLayout.LayoutParams cplp = new LinearLayout.LayoutParams(-1, -2);
        cplp.setMargins(0, 0, 0, dp(10));
        root.addView(clipPreview, cplp);

        // ── Wiersz 1: [▶ Czytaj] [⏹ Stop] ──────────────────────────────────
        LinearLayout row1 = new LinearLayout(this);
        row1.setOrientation(LinearLayout.HORIZONTAL);

        Button readBtn = btnPrimary("▶  Czytaj");
        Button stopBtn = btnDanger("⏹  Stop");

        LinearLayout.LayoutParams rblp = new LinearLayout.LayoutParams(0, dp(46), 1f);
        LinearLayout.LayoutParams sblp = new LinearLayout.LayoutParams(0, dp(46), 1f);
        sblp.setMargins(dp(6), 0, 0, 0);
        row1.addView(readBtn, rblp);
        row1.addView(stopBtn, sblp);

        LinearLayout.LayoutParams r1lp = new LinearLayout.LayoutParams(-1, -2);
        r1lp.setMargins(0, 0, 0, dp(6));
        root.addView(row1, r1lp);

        // ── Wiersz 2: [↗ Otwórz pełny czytnik] ─────────────────────────────
        Button openBtn = btnSecondary("↗  Otwórz pełny czytnik");
        LinearLayout.LayoutParams oblp = new LinearLayout.LayoutParams(-1, dp(42));
        oblp.setMargins(0, 0, 0, dp(10));
        root.addView(openBtn, oblp);

        // ── Wiersz 3: Tempo — 3 przyciski ────────────────────────────────────
        LinearLayout rateRow = new LinearLayout(this);
        rateRow.setOrientation(LinearLayout.HORIZONTAL);
        rateRow.setGravity(Gravity.CENTER_VERTICAL);

        TextView rateLabel = new TextView(this);
        rateLabel.setText("Tempo");
        rateLabel.setTextColor(C_MUTED);
        rateLabel.setTextSize(11);
        rateLabel.setTypeface(Typeface.DEFAULT_BOLD);
        rateLabel.setPadding(0, 0, dp(8), 0);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            rateLabel.setLetterSpacing(0.06f);
        }
        rateRow.addView(rateLabel);

        Button slowBtn   = btnRate("Wolno");
        Button normalBtn = btnRate("Normal");
        Button fastBtn   = btnRate("Szybko");

        LinearLayout.LayoutParams rl1 = new LinearLayout.LayoutParams(0, dp(34), 1f);
        LinearLayout.LayoutParams rl2 = new LinearLayout.LayoutParams(0, dp(34), 1f);
        rl2.setMargins(dp(4), 0, 0, 0);
        LinearLayout.LayoutParams rl3 = new LinearLayout.LayoutParams(0, dp(34), 1f);
        rl3.setMargins(dp(4), 0, 0, 0);

        rateRow.addView(slowBtn,   rl1);
        rateRow.addView(normalBtn, rl2);
        rateRow.addView(fastBtn,   rl3);
        root.addView(rateRow);

        // ── Zdarzenia ─────────────────────────────────────────────────────────
        readBtn.setOnClickListener(v  -> readClipboard());
        stopBtn.setOnClickListener(v  -> { if (tts != null) tts.stop(); setStatus("Zatrzymano."); });
        openBtn.setOnClickListener(v  -> openFullReader());
        slowBtn.setOnClickListener(v  -> setRate(0.70f, "wolno"));
        normalBtn.setOnClickListener(v -> setRate(0.95f, "normalne"));
        fastBtn.setOnClickListener(v  -> setRate(1.40f, "szybko"));

        setContentView(root);
    }

    private void refreshClipPreview() {
        if (clipPreview == null) return;
        String text = getClipboardText();
        if (text.isEmpty()) {
            clipPreview.setText("Schowek jest pusty");
            clipPreview.setTextColor(C_MUTED);
        } else {
            clipPreview.setText(text);
            clipPreview.setTextColor(C_TEXT);
        }
    }

    // ════════════════════════════════════════════════════════════════════════
    //  Logika
    // ════════════════════════════════════════════════════════════════════════

    private void readClipboard() {
        String text = getClipboardText();
        if (text.isEmpty()) { setStatus("Schowek jest pusty."); return; }
        if (!ttsReady)      { setStatus("TTS nie jest jeszcze gotowy."); return; }
        Locale locale = detectLocale(text);
        tts.setLanguage(locale);
        applySpeechRate();
        tts.stop();
        String[] chunks = splitForTts(text);
        for (int i = 0; i < chunks.length; i++) {
            int queueMode = (i == 0) ? TextToSpeech.QUEUE_FLUSH : TextToSpeech.QUEUE_ADD;
            tts.speak(chunks[i], queueMode, null, "floating-" + i);
        }
        setStatus("Czytam • " + locale.toLanguageTag());
    }

    private String[] splitForTts(String text) {
        String normalized = text == null ? "" : text.replaceAll("\\s+", " ").trim();
        if (normalized.isEmpty()) return new String[0];
        java.util.List<String> chunks = new java.util.ArrayList<>();
        StringBuilder current = new StringBuilder();
        String[] parts = normalized.split("(?<=[.!?;:])\\s+");
        for (String part : parts) {
            String t = part.trim();
            if (t.isEmpty()) continue;
            if (current.length() > 0 && current.length() + t.length() + 1 > MAX_TTS_CHUNK) {
                chunks.add(current.toString());
                current.setLength(0);
            }
            if (current.length() > 0) current.append(' ');
            current.append(t);
        }
        if (current.length() > 0) chunks.add(current.toString());
        return chunks.toArray(new String[0]);
    }

    private void openFullReader() {
        String text = getClipboardText();
        Intent intent = new Intent(this, MainActivity.class)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        if (!text.isEmpty()) {
            intent.setAction(Intent.ACTION_SEND);
            intent.setType("text/plain");
            intent.putExtra(Intent.EXTRA_TEXT, text);
        }
        startActivity(intent);
        finish();
    }

    private void setRate(float rate, String label) {
        speechRate = rate;
        applySpeechRate();
        setStatus("Tempo: " + label + " (" + rate + "×)");
    }

    private void applySpeechRate() {
        if (tts != null && ttsReady) tts.setSpeechRate(speechRate);
    }

    private String getClipboardText() {
        ClipboardManager cb = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
        if (cb == null || !cb.hasPrimaryClip()) return "";
        ClipData clip = cb.getPrimaryClip();
        if (clip == null || clip.getItemCount() == 0) return "";
        CharSequence text = clip.getItemAt(0).coerceToText(this);
        return text == null ? "" : text.toString().trim();
    }

    private Locale detectLocale(String text) {
        String sample = " " + text.substring(0, Math.min(3000, text.length())).toLowerCase(Locale.ROOT) + " ";
        int en = count(sample, new String[]{" the ", " and ", " is ", " are ", " with ", " from ", " that ", " this ", " you ", " for "});
        int pl = count(sample, new String[]{" ze ", " nie ", " jest ", " się ", " na ", " do ", " oraz ", " który ", "ą", "ć", "ę", "ł", "ń", "ó", "ś", "ź", "ż"});
        return en > pl ? Locale.US : new Locale("pl", "PL");
    }

    private int count(String sample, String[] markers) {
        int c = 0;
        for (String m : markers) if (sample.contains(m)) c++;
        return c;
    }

    private void setStatus(String msg) {
        if (statusText != null) statusText.setText(msg);
    }

    // ════════════════════════════════════════════════════════════════════════
    //  Fabryki przycisków
    // ════════════════════════════════════════════════════════════════════════

    private Button btnPrimary(String text) {
        Button btn = new Button(this);
        btn.setText(text);
        btn.setAllCaps(false);
        btn.setTextSize(13);
        btn.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        btn.setTextColor(C_ON_PRIMARY);
        btn.setBackground(mkRound(C_PRIMARY, 0, 10));
        btn.setPadding(0, 0, 0, 0);
        return btn;
    }

    private Button btnSecondary(String text) {
        Button btn = new Button(this);
        btn.setText(text);
        btn.setAllCaps(false);
        btn.setTextSize(13);
        btn.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        btn.setTextColor(C_TEXT);
        btn.setBackground(mkRound(C_SURFACE2, C_BORDER, 10));
        btn.setPadding(0, 0, 0, 0);
        return btn;
    }

    private Button btnDanger(String text) {
        Button btn = new Button(this);
        btn.setText(text);
        btn.setAllCaps(false);
        btn.setTextSize(13);
        btn.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        btn.setTextColor(C_DANGER);
        btn.setBackground(mkRound(C_DANGER_BG, 0, 10));
        btn.setPadding(0, 0, 0, 0);
        return btn;
    }

    private Button btnRate(String text) {
        Button btn = new Button(this);
        btn.setText(text);
        btn.setAllCaps(false);
        btn.setTextSize(11);
        btn.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        btn.setTextColor(C_PRIMARY);
        btn.setBackground(mkRound(isDarkMode() ? C_SURFACE2 : C_PRIMARY_DIM, C_BORDER, 8));
        btn.setPadding(0, 0, 0, 0);
        return btn;
    }

    // ════════════════════════════════════════════════════════════════════════
    //  Helpers
    // ════════════════════════════════════════════════════════════════════════

    private GradientDrawable mkRound(int fill, int stroke, int radiusDp) {
        GradientDrawable d = new GradientDrawable();
        d.setColor(fill);
        d.setCornerRadius(dp(radiusDp));
        if (stroke != 0) d.setStroke(dp(1), stroke);
        return d;
    }

    private int dp(int v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }
}
