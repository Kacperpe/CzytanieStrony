package pl.local.czytnikstrony;

import android.app.Activity;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.content.res.Configuration;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.speech.tts.TextToSpeech;
import android.view.Gravity;
import android.view.Window;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.util.Locale;

public class FloatingReaderActivity extends Activity implements TextToSpeech.OnInitListener {

    private int C_SURFACE, C_PRIMARY, C_ON_PRIMARY, C_TEXT, C_MUTED, C_BORDER, C_SURFACE2;

    private TextView statusText;
    private TextToSpeech tts;
    private boolean ttsReady = false;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        initColors();
        configureWindow();
        buildUi();
        tts = new TextToSpeech(this, this);
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
            tts.setSpeechRate(0.95f);
            setStatus("Gotowy — skopiuj tekst lub adres i kliknij Czytaj.");
        } else {
            setStatus("TTS niedostępny na tym urządzeniu.");
        }
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
            C_SURFACE    = 0xFF1A2826;
            C_SURFACE2   = 0xFF1F2D2A;
            C_PRIMARY    = 0xFF3ECBA8;
            C_ON_PRIMARY = 0xFF001A14;
            C_TEXT       = 0xFFDCEDE9;
            C_MUTED      = 0xFF7CA99F;
            C_BORDER     = 0xFF2B3E3B;
        } else {
            C_SURFACE    = 0xFFFFFFFF;
            C_SURFACE2   = 0xFFF7FBFA;
            C_PRIMARY    = 0xFF1A6B5A;
            C_ON_PRIMARY = 0xFFFFFFFF;
            C_TEXT       = 0xFF182624;
            C_MUTED      = 0xFF58726E;
            C_BORDER     = 0xFFCBDAD8;
        }
    }

    // ════════════════════════════════════════════════════════════════════════
    //  Okno
    // ════════════════════════════════════════════════════════════════════════

    private void configureWindow() {
        Window window = getWindow();
        WindowManager.LayoutParams params = window.getAttributes();
        params.gravity = Gravity.TOP | Gravity.END;
        params.width   = dp(300);
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
        root.setPadding(dp(16), dp(16), dp(16), dp(16));
        root.setBackground(mkRound(C_SURFACE, C_BORDER, 18));

        // Tytuł
        TextView title = new TextView(this);
        title.setText("Szybki czytnik");
        title.setTextColor(C_TEXT);
        title.setTextSize(18);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        root.addView(title);

        // Status
        statusText = new TextView(this);
        statusText.setText("Startuje…");
        statusText.setTextColor(C_MUTED);
        statusText.setTextSize(13);
        statusText.setPadding(0, dp(4), 0, dp(12));
        root.addView(statusText);

        // Przyciski
        Button readBtn  = btn("▶  Czytaj schowek", true);
        Button openBtn  = btn("Otwórz pełny czytnik", false);
        Button stopBtn  = btn("⏹  Stop", false);
        Button closeBtn = btn("Zamknij", false);

        root.addView(readBtn,  fullWidth(dp(8)));
        root.addView(openBtn,  fullWidth(dp(6)));
        root.addView(stopBtn,  fullWidth(dp(6)));
        root.addView(closeBtn, fullWidth(dp(0)));

        readBtn.setOnClickListener(v  -> readClipboard());
        openBtn.setOnClickListener(v  -> openFullReader());
        stopBtn.setOnClickListener(v  -> {
            if (tts != null) tts.stop();
            setStatus("Zatrzymano.");
        });
        closeBtn.setOnClickListener(v -> finish());

        setContentView(root);
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
        tts.setSpeechRate(0.95f);
        tts.speak(text, TextToSpeech.QUEUE_FLUSH, null, "floating");
        setStatus("Czytam: " + locale.toLanguageTag());
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
        int score = 0;
        for (String m : markers) if (sample.contains(m)) score++;
        return score;
    }

    private void setStatus(String msg) {
        if (statusText != null) statusText.setText(msg);
    }

    // ════════════════════════════════════════════════════════════════════════
    //  Helpers UI
    // ════════════════════════════════════════════════════════════════════════

    private Button btn(String text, boolean primary) {
        Button btn = new Button(this);
        btn.setText(text);
        btn.setAllCaps(false);
        btn.setTextSize(14);
        btn.setTypeface(Typeface.DEFAULT_BOLD);
        btn.setMinHeight(dp(44));
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

    private LinearLayout.LayoutParams fullWidth(int topMargin) {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
        lp.setMargins(0, topMargin, 0, 0);
        return lp;
    }

    private GradientDrawable mkRound(int fill, int stroke, int radiusDp) {
        GradientDrawable d = new GradientDrawable();
        d.setColor(fill);
        d.setCornerRadius(dp(radiusDp));
        d.setStroke(dp(1), stroke);
        return d;
    }

    private int dp(int v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }
}
