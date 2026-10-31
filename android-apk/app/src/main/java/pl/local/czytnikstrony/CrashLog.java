package pl.local.czytnikstrony;

import android.app.Application;
import android.content.Context;
import android.os.Build;

import java.io.File;
import java.io.FileOutputStream;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/** Zapisuje ostatni crash do pliku, żeby pokazać go przy następnym uruchomieniu. */
public class CrashLog extends Application {

    @Override
    public void onCreate() {
        super.onCreate();
        install(this);
    }

    static void install(Context ctx) {
        final Context app = ctx.getApplicationContext();
        final Thread.UncaughtExceptionHandler prev = Thread.getDefaultUncaughtExceptionHandler();
        Thread.setDefaultUncaughtExceptionHandler((t, e) -> {
            try {
                StringWriter sw = new StringWriter();
                PrintWriter pw = new PrintWriter(sw);
                pw.println("Czytnik " + versionName(app) + " · Android " + Build.VERSION.RELEASE
                    + " (API " + Build.VERSION.SDK_INT + ") · " + Build.MANUFACTURER + " " + Build.MODEL
                    + " · " + Build.SUPPORTED_ABIS[0]);
                pw.println(new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(new Date())
                    + " · wątek: " + t.getName());
                e.printStackTrace(pw);
                pw.flush();
                try (FileOutputStream os = new FileOutputStream(file(app))) {
                    os.write(sw.toString().getBytes(StandardCharsets.UTF_8));
                }
            } catch (Throwable ignored) {}
            if (prev != null) prev.uncaughtException(t, e);
        });
    }

    static File file(Context ctx) { return new File(ctx.getFilesDir(), "last_crash.txt"); }

    /** Treść ostatniego crasha albo null. */
    static String read(Context ctx) {
        File f = file(ctx);
        if (!f.exists()) return null;
        try {
            byte[] b = new byte[(int) Math.min(f.length(), 200_000)];
            try (java.io.FileInputStream in = new java.io.FileInputStream(f)) {
                int off = 0, n;
                while (off < b.length && (n = in.read(b, off, b.length - off)) > 0) off += n;
                return new String(b, 0, off, StandardCharsets.UTF_8);
            }
        } catch (Throwable e) { return null; }
    }

    static void clear(Context ctx) {
        //noinspection ResultOfMethodCallIgnored
        file(ctx).delete();
    }

    private static String versionName(Context c) {
        try { return c.getPackageManager().getPackageInfo(c.getPackageName(), 0).versionName; }
        catch (Throwable e) { return "?"; }
    }
}
