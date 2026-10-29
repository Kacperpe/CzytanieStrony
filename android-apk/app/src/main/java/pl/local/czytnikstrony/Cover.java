package pl.local.czytnikstrony;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Shader;
import android.graphics.Typeface;

import java.util.Locale;

/** „Okładka” artykułu: gradient w kolorze zależnym od strony + pierwsza litera domeny. */
public final class Cover {

    private Cover() {}

    /** Dwa kolory gradientu (ARGB) dla klucza (domena lub tytuł). */
    static int[] colors(String key) {
        int h = key == null ? 0 : key.hashCode();
        float hue = ((h & 0x7fffffff) % 360);
        int a = Color.HSVToColor(new float[]{hue, 0.55f, 0.78f});
        int b = Color.HSVToColor(new float[]{(hue + 40) % 360, 0.70f, 0.42f});
        return new int[]{a, b};
    }

    static String letter(String domain, String title) {
        String s = domain != null && !domain.isEmpty() ? domain : (title != null ? title.trim() : "");
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (Character.isLetterOrDigit(c)) return String.valueOf(c).toUpperCase(Locale.ROOT);
        }
        return "♪";
    }

    static Bitmap bitmap(String domain, String title, int size) {
        String key = domain != null && !domain.isEmpty() ? domain : title;
        int[] c = colors(key);
        Bitmap bmp = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(bmp);
        Paint bg = new Paint(Paint.ANTI_ALIAS_FLAG);
        bg.setShader(new LinearGradient(0, 0, size, size, c[0], c[1], Shader.TileMode.CLAMP));
        canvas.drawRoundRect(new RectF(0, 0, size, size), size * 0.18f, size * 0.18f, bg);
        Paint t = new Paint(Paint.ANTI_ALIAS_FLAG);
        t.setColor(0xF2FFFFFF);
        t.setTextAlign(Paint.Align.CENTER);
        t.setTypeface(Typeface.create("sans-serif-medium", Typeface.BOLD));
        t.setTextSize(size * 0.46f);
        Paint.FontMetrics fm = t.getFontMetrics();
        float y = size / 2f - (fm.ascent + fm.descent) / 2f;
        canvas.drawText(letter(domain, title), size / 2f, y, t);
        return bmp;
    }
}
