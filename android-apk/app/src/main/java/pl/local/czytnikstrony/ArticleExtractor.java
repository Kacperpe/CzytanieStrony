package pl.local.czytnikstrony;

import android.annotation.SuppressLint;
import android.os.Handler;
import android.os.Looper;
import android.webkit.WebChromeClient;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;

import org.json.JSONObject;

/** Wczytuje stronę w (ukrytym) WebView i wyciąga z niej tytuł oraz treść artykułu. */
final class ArticleExtractor {

    interface Callback {
        void onProgress(int percent);
        void onResult(String title, String text, String errorOrNull);
    }

    private static final String JS = "(function(){"
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
        + "var og=document.querySelector('meta[property=\"og:title\"]');"
        + "var title=(h1&&h1.innerText.trim())||(og&&og.content)||document.title||'';"
        + "return JSON.stringify({title:title,text:root.innerText});"
        + "})()";

    private final WebView web;
    private final Handler main = new Handler(Looper.getMainLooper());
    private int session = 0;

    @SuppressLint("SetJavaScriptEnabled")
    ArticleExtractor(WebView web) {
        this.web = web;
        WebSettings s = web.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setLoadsImagesAutomatically(false);
        s.setMediaPlaybackRequiresUserGesture(true);
    }

    void cancel() {
        session++;
        try { web.stopLoading(); } catch (Exception ignored) {}
    }

    void extract(String url, Callback cb) {
        final int my = ++session;
        final boolean[] done = {false};
        web.setWebChromeClient(new WebChromeClient() {
            @Override public void onProgressChanged(WebView v, int p) { if (my == session) cb.onProgress(p); }
        });
        web.setWebViewClient(new WebViewClient() {
            @Override public void onPageFinished(WebView view, String u) {
                if (done[0] || my != session) return;
                main.postDelayed(() -> {
                    if (done[0] || my != session) return;
                    done[0] = true;
                    run(my, cb);
                }, 900);
            }
        });
        main.postDelayed(() -> {
            if (done[0] || my != session) return;
            done[0] = true;
            run(my, cb);
        }, 25000);
        web.loadUrl(url);
    }

    private void run(int my, Callback cb) {
        web.evaluateJavascript(JS, value -> {
            if (my != session) return;
            String title = "", text = "";
            try {
                String json = new JSONObject("{\"v\":" + value + "}").getString("v");
                JSONObject o = new JSONObject(json);
                title = o.optString("title", "").trim();
                text = o.optString("text", "");
            } catch (Exception ignored) {}
            text = Reader.cleanText(text);
            if (text.length() < 40) {
                cb.onResult(title, "", "Nie znaleziono tekstu. Strona może wymagać logowania.");
                return;
            }
            if (!title.isEmpty() && !text.startsWith(title)) text = title + "\n\n" + text;
            cb.onResult(title, text, null);
        });
    }

    static String normalizeUrl(String raw) {
        if (raw == null) return null;
        String url = raw.trim();
        if (url.isEmpty()) return null;
        if (!url.startsWith("http://") && !url.startsWith("https://")) url = "https://" + url;
        return url;
    }

    /** Wyciąga adres z tekstu (np. „Tytuł https://…”); null gdy to nie link. */
    static String extractUrl(String v) {
        if (v == null) return null;
        java.util.regex.Matcher m = java.util.regex.Pattern.compile("https?://\\S+").matcher(v);
        if (m.find() && v.length() < 600) return m.group();
        String t = v.trim();
        if (!t.contains(" ") && t.matches("[\\w.-]+\\.[a-z]{2,}(/\\S*)?")) return "https://" + t;
        return null;
    }
}
