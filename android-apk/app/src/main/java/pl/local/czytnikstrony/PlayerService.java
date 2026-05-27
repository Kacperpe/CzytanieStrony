package pl.local.czytnikstrony;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.graphics.drawable.Icon;
import android.media.session.MediaSession;
import android.os.Build;
import android.os.IBinder;

public class PlayerService extends Service {

    // Akcje wysyłane z przycisków powiadomienia do MainActivity
    static final String NOTIF_ACTION_PLAY_PAUSE = "czytnik.PLAY_PAUSE";
    static final String NOTIF_ACTION_PREV       = "czytnik.PREV";
    static final String NOTIF_ACTION_NEXT       = "czytnik.NEXT";
    static final String NOTIF_ACTION_STOP       = "czytnik.STOP";
    static final String KEY_NOTIF_ACTION        = "notif_action";
    static final String ACTION_CONTROL_EVENT    = "czytnik.CONTROL_EVENT";

    private static final String ACTION_UPDATE = "czytnik.UPDATE";
    private static final String EXTRA_TITLE   = "title";
    private static final String EXTRA_NOW_PLAYING = "now_playing";
    private static final String EXTRA_PLAYING = "playing";
    private static final String EXTRA_CHUNK   = "chunk";
    private static final String EXTRA_TOTAL   = "total";

    private static final int    NOTIF_ID   = 1;
    private static final String CHANNEL_ID = "czytnik_playback";

    private MediaSession mediaSession;

    // ── Publiczne API (wołane z MainActivity) ─────────────────────────────

    static void update(Context ctx, String title, String nowPlaying, boolean playing, int chunk, int total) {
        Intent i = new Intent(ctx, PlayerService.class);
        i.setAction(ACTION_UPDATE);
        i.putExtra(EXTRA_TITLE,   title);
        i.putExtra(EXTRA_NOW_PLAYING, nowPlaying);
        i.putExtra(EXTRA_PLAYING, playing);
        i.putExtra(EXTRA_CHUNK,   chunk);
        i.putExtra(EXTRA_TOTAL,   total);
        ctx.startService(i);
    }

    static void hide(Context ctx) {
        ctx.stopService(new Intent(ctx, PlayerService.class));
    }

    // ── Cykl życia serwisu ────────────────────────────────────────────────

    @Override
    public void onCreate() {
        super.onCreate();
        createChannel();
        mediaSession = new MediaSession(this, "CzytnikStrony");
        mediaSession.setActive(true);
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent == null) {
            stopSelf();
            return START_NOT_STICKY;
        }
        String action = intent.getAction();
        if (isControlAction(action)) {
            Intent control = new Intent(ACTION_CONTROL_EVENT);
            control.setPackage(getPackageName());
            control.putExtra(KEY_NOTIF_ACTION, action);
            sendBroadcast(control);
            return START_NOT_STICKY;
        }
        if (!ACTION_UPDATE.equals(action)) {
            stopSelf();
            return START_NOT_STICKY;
        }
        Notification notif = buildNotification(
            intent.getStringExtra(EXTRA_TITLE),
            intent.getStringExtra(EXTRA_NOW_PLAYING),
            intent.getBooleanExtra(EXTRA_PLAYING, false),
            intent.getIntExtra(EXTRA_CHUNK, 0),
            intent.getIntExtra(EXTRA_TOTAL, 0));

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIF_ID, notif, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK);
        } else {
            startForeground(NOTIF_ID, notif);
        }
        return START_NOT_STICKY;
    }

    @Override
    public void onDestroy() {
        if (mediaSession != null) {
            mediaSession.setActive(false);
            mediaSession.release();
        }
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) { return null; }

    // ── Powiadomienie ─────────────────────────────────────────────────────

    private void createChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationManager nm = getSystemService(NotificationManager.class);
            if (nm.getNotificationChannel(CHANNEL_ID) != null) return;
            NotificationChannel ch = new NotificationChannel(
                CHANNEL_ID, "Odtwarzanie", NotificationManager.IMPORTANCE_LOW);
            ch.setDescription("Sterowanie czytaniem");
            nm.createNotificationChannel(ch);
        }
    }

    private Notification buildNotification(String title, String nowPlaying, boolean playing, int chunk, int total) {
        Icon   icon      = Icon.createWithResource(this, R.drawable.ic_reader_tile);
        String mainTitle = (title != null && !title.isEmpty()) ? title : "Czytnik strony";
        String contentText = (nowPlaying != null && !nowPlaying.isEmpty()) ? nowPlaying : "Czyta…";
        String subText   = total > 0 ? "Fragment " + (chunk + 1) + " / " + total : null;

        return new Notification.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_reader_tile)
            .setContentTitle(mainTitle)
            .setContentText(contentText)
            .setSubText(subText)
            .setContentIntent(pendingToMain())
            .setOngoing(playing)
            .addAction(new Notification.Action.Builder(icon, "Cofnij", pendingControl(NOTIF_ACTION_PREV, 1)).build())
            .addAction(new Notification.Action.Builder(icon, playing ? "Pauza" : "Start", pendingControl(NOTIF_ACTION_PLAY_PAUSE, 2)).build())
            .addAction(new Notification.Action.Builder(icon, "Dalej", pendingControl(NOTIF_ACTION_NEXT, 3)).build())
            .addAction(new Notification.Action.Builder(icon, "Stop", pendingControl(NOTIF_ACTION_STOP, 4)).build())
            .setStyle(new Notification.MediaStyle()
                .setMediaSession(mediaSession.getSessionToken())
                .setShowActionsInCompactView(0, 1, 2))
            .setVisibility(Notification.VISIBILITY_PUBLIC)
            .build();
    }

    private PendingIntent pendingToMain() {
        Intent i = new Intent(this, MainActivity.class);
        i.setFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        return PendingIntent.getActivity(this, 0, i, PendingIntent.FLAG_IMMUTABLE);
    }

    private PendingIntent pendingControl(String action, int reqCode) {
        Intent i = new Intent(this, PlayerService.class);
        i.setAction(action);
        return PendingIntent.getService(this, reqCode, i, PendingIntent.FLAG_IMMUTABLE);
    }

    private boolean isControlAction(String action) {
        return NOTIF_ACTION_PLAY_PAUSE.equals(action)
            || NOTIF_ACTION_PREV.equals(action)
            || NOTIF_ACTION_NEXT.equals(action)
            || NOTIF_ACTION_STOP.equals(action);
    }
}
