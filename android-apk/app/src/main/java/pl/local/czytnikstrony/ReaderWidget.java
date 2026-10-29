package pl.local.czytnikstrony;

import android.app.PendingIntent;
import android.appwidget.AppWidgetManager;
import android.appwidget.AppWidgetProvider;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.widget.RemoteViews;

/** Widżet ekranu głównego: okładka, tytuł i przycisk odtwarzania. */
public class ReaderWidget extends AppWidgetProvider {

    private static String lastKey = "";

    @Override
    public void onUpdate(Context ctx, AppWidgetManager mgr, int[] ids) {
        lastKey = "";
        update(ctx);
    }

    static void update(Context ctx) {
        try {
            AppWidgetManager mgr = AppWidgetManager.getInstance(ctx);
            int[] ids = mgr.getAppWidgetIds(new ComponentName(ctx, ReaderWidget.class));
            if (ids == null || ids.length == 0) return;
            Reader r = Reader.get(ctx);
            String title = r.hasContent() ? r.displayTitle() : "Czytnik strony";
            String sub = r.hasContent()
                ? (r.getDomain().isEmpty() ? "" : r.getDomain() + " · ")
                    + (r.isPlaying() ? "czyta" : r.isPaused() ? "pauza" : "gotowy")
                : "Stuknij, aby wkleić link";
            String key = title + "|" + sub + "|" + r.isPlaying();
            if (key.equals(lastKey)) return;
            lastKey = key;

            RemoteViews v = new RemoteViews(ctx.getPackageName(), R.layout.widget_player);
            v.setTextViewText(R.id.w_title, title);
            v.setTextViewText(R.id.w_sub, sub);
            v.setImageViewBitmap(R.id.w_cover, Cover.bitmap(r.getDomain(), title, 144));
            v.setImageViewResource(R.id.w_play, r.isPlaying() ? R.drawable.ic_pause : R.drawable.ic_play);

            Intent open = new Intent(ctx, MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            v.setOnClickPendingIntent(R.id.w_root,
                PendingIntent.getActivity(ctx, 10, open, PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT));

            PendingIntent play;
            if (r.hasContent()) {
                Intent i = new Intent(ctx, PlayerService.class).setAction(PlayerService.ACTION_PLAY_PAUSE);
                play = Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
                    ? PendingIntent.getForegroundService(ctx, 11, i, PendingIntent.FLAG_IMMUTABLE)
                    : PendingIntent.getService(ctx, 11, i, PendingIntent.FLAG_IMMUTABLE);
            } else {
                play = PendingIntent.getActivity(ctx, 12, open, PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
            }
            v.setOnClickPendingIntent(R.id.w_play, play);
            mgr.updateAppWidget(ids, v);
        } catch (Exception ignored) {}
    }
}
