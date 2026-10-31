package pl.local.czytnikstrony;

import android.app.Activity;
import android.content.Intent;
import android.content.res.Configuration;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.webkit.WebView;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

/**
 * Cel „Udostępnij → Czytaj później”: zapisuje artykuł do kolejki bez otwierania
 * całej aplikacji (pobiera treść w tle, pokazuje mały panel postępu).
 */
public class QueueActivity extends Activity {

    private ArticleExtractor extractor;
    private WebView web;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        Intent in = getIntent();
        CharSequence shared = in != null ? in.getCharSequenceExtra(Intent.EXTRA_TEXT) : null;
        if (shared == null) { finish(); return; }
        String value = shared.toString().trim();
        String subject = in.getStringExtra(Intent.EXTRA_SUBJECT);
        String url = ArticleExtractor.extractUrl(value);

        if (url == null) {
            Library.get(this).upsert(subject != null ? subject : "", "", Reader.cleanText(value), true);
            done("Dodano do kolejki");
            return;
        }
        buildUi();
        extractor = new ArticleExtractor(web);
        extractor.extract(url, new ArticleExtractor.Callback() {
            @Override public void onProgress(int p) {}
            @Override public void onResult(String title, String text, String err) {
                if (err != null) {
                    // zapisz sam link — treść pobierze się przy otwarciu
                    Library.get(QueueActivity.this).upsert(subject != null ? subject : "", url, null, true);
                    done("Zapisano link (treść pobierze się później)");
                    return;
                }
                Library.get(QueueActivity.this).upsert(title.isEmpty() && subject != null ? subject : title, url, text, true);
                done("Dodano do kolejki: " + (title.isEmpty() ? Library.domainOf(url) : title));
            }
        });
    }

    private void buildUi() {
        boolean dark = (getResources().getConfiguration().uiMode & Configuration.UI_MODE_NIGHT_MASK)
            == Configuration.UI_MODE_NIGHT_YES;
        float d = getResources().getDisplayMetrics().density;
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.HORIZONTAL);
        root.setGravity(Gravity.CENTER_VERTICAL);
        int pad = Math.round(18 * d);
        root.setPadding(pad, pad, pad, pad);
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(dark ? 0xFF1E2038 : 0xFFFFFFFF);
        bg.setCornerRadius(20 * d);
        root.setBackground(bg);
        ProgressBar pb = new ProgressBar(this);
        root.addView(pb, new LinearLayout.LayoutParams(Math.round(28 * d), Math.round(28 * d)));
        TextView t = new TextView(this);
        t.setText("Zapisuję do „Czytaj później”…");
        t.setTextColor(dark ? 0xFFECECFF : 0xFF1A1535);
        t.setTextSize(15);
        t.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-2, -2);
        lp.setMargins(Math.round(14 * d), 0, 0, 0);
        root.addView(t, lp);
        web = new WebView(this);
        web.setVisibility(View.INVISIBLE);
        root.addView(web, new LinearLayout.LayoutParams(Math.round(360 * d), 1));
        setContentView(root);
    }

    private void done(String msg) {
        Toast.makeText(getApplicationContext(), msg, Toast.LENGTH_SHORT).show();
        finish();
    }

    @Override
    protected void onDestroy() {
        if (extractor != null) extractor.cancel();
        if (web != null) web.destroy();
        super.onDestroy();
    }
}
