package pl.local.czytnikstrony;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.ServiceInfo;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.drawable.Icon;
import android.media.AudioManager;
import android.media.MediaMetadata;
import android.media.session.MediaSession;
import android.media.session.PlaybackState;
import android.os.Build;
import android.os.IBinder;

/**
 * Serwis pierwszoplanowy: trzyma proces przy życiu podczas czytania,
 * pokazuje powiadomienie w stylu odtwarzacza i obsługuje przyciski
 * słuchawek / ekranu blokady (MediaSession).
 */
public class PlayerService extends Service implements Reader.StateListener {

    static final String ACTION_PLAY_PAUSE = "czytnik.PLAY_PAUSE";
    static final String ACTION_PREV       = "czytnik.PREV";
    static final String ACTION_NEXT       = "czytnik.NEXT";
    static final String ACTION_STOP       = "czytnik.STOP";

    private static final int    NOTIF_ID   = 1;
    private static final String CHANNEL_ID = "czytnik_playback";
    private static final int    COLOR      = 0xFF5B4AE8;

    private Reader reader;
    private MediaSession mediaSession;
    private boolean foreground = false;
    private boolean noisyRegistered = false;
    private Bitmap largeIcon;

    private final BroadcastReceiver noisyReceiver = new BroadcastReceiver() {
        @Override public void onReceive(Context c, Intent i) {
            if (AudioManager.ACTION_AUDIO_BECOMING_NOISY.equals(i.getAction())) reader.pause();
        }
    };

    @Override
    public void onCreate() {
        super.onCreate();
        reader = Reader.get(this);
        createChannel();
        largeIcon = createLargeIcon();
        mediaSession = new MediaSession(this, "CzytnikStrony");
        mediaSession.setCallback(new MediaSession.Callback() {
            @Override public void onPlay()             { if (!reader.isPlaying()) reader.togglePlayPause(); }
            @Override public void onPause()            { reader.pause(); }
            @Override public void onSkipToNext()       { reader.next(); }
            @Override public void onSkipToPrevious()   { reader.prev(); }
            @Override public void onFastForward()      { reader.next(); }
            @Override public void onRewind()           { reader.prev(); }
            @Override public void onStop()             { reader.stop(); }
        });
        mediaSession.setActive(true);
        reader.addListener(this);
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        // Zawsze najpierw wejdź na pierwszy plan (wymóg startForegroundService)
        startForegroundNow(buildNotification(reader.isPlaying()));
        String action = intent != null ? intent.getAction() : null;
        if (action != null) {
            switch (action) {
                case ACTION_PLAY_PAUSE: reader.togglePlayPause(); break;
                case ACTION_PREV:       reader.prev();            break;
                case ACTION_NEXT:       reader.next();            break;
                case ACTION_STOP:       reader.stop();            break;
                default: break;
            }
        }
        onReaderState();
        return START_NOT_STICKY;
    }

    @Override
    public void onReaderState() {
        if (reader.isPreviewing()) return;
        if (!reader.isActive()) {
            if (noisyRegistered) { try { unregisterReceiver(noisyReceiver); } catch (Exception ignored) {} noisyRegistered = false; }
            updateSession();
            stopForegroundCompat(true);
            stopSelf();
            return;
        }
        if (!noisyRegistered) {
            registerReceiver(noisyReceiver, new IntentFilter(AudioManager.ACTION_AUDIO_BECOMING_NOISY));
            noisyRegistered = true;
        }
        updateSession();
        showNotification(reader.isPlaying());
    }

    @Override
    public void onDestroy() {
        reader.removeListener(this);
        if (noisyRegistered) { try { unregisterReceiver(noisyReceiver); } catch (Exception ignored) {} }
        mediaSession.setActive(false);
        mediaSession.release();
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) { return null; }

    // ── MediaSession ─────────────────────────────────────────────────────

    private void updateSession() {
        int state = reader.isPlaying() ? PlaybackState.STATE_PLAYING
            : reader.isPaused() ? PlaybackState.STATE_PAUSED : PlaybackState.STATE_STOPPED;
        mediaSession.setPlaybackState(new PlaybackState.Builder()
            .setActions(PlaybackState.ACTION_PLAY | PlaybackState.ACTION_PAUSE
                | PlaybackState.ACTION_PLAY_PAUSE | PlaybackState.ACTION_STOP
                | PlaybackState.ACTION_SKIP_TO_NEXT | PlaybackState.ACTION_SKIP_TO_PREVIOUS)
            .setState(state, reader.getIndex(), reader.isPlaying() ? 1f : 0f)
            .build());
        mediaSession.setMetadata(new MediaMetadata.Builder()
            .putString(MediaMetadata.METADATA_KEY_TITLE, reader.displayTitle())
            .putString(MediaMetadata.METADATA_KEY_ARTIST, "Czytnik strony")
            .putBitmap(MediaMetadata.METADATA_KEY_ART, largeIcon)
            .build());
    }

    // ── Powiadomienie ────────────────────────────────────────────────────

    private void createChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationManager nm = getSystemService(NotificationManager.class);
            if (nm == null || nm.getNotificationChannel(CHANNEL_ID) != null) return;
            NotificationChannel ch = new NotificationChannel(CHANNEL_ID, "Odtwarzanie", NotificationManager.IMPORTANCE_LOW);
            ch.setDescription("Sterowanie czytaniem");
            ch.setShowBadge(false);
            nm.createNotificationChannel(ch);
        }
    }

    private void showNotification(boolean playing) {
        Notification n = buildNotification(playing);
        if (playing) {
            startForegroundNow(n);
        } else {
            // W pauzie pozwól zamknąć powiadomienie przesunięciem (deleteIntent = stop)
            if (foreground) stopForegroundCompat(false);
            NotificationManager nm = getSystemService(NotificationManager.class);
            if (nm != null) nm.notify(NOTIF_ID, n);
        }
    }

    private void startForegroundNow(Notification n) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIF_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK);
        } else {
            startForeground(NOTIF_ID, n);
        }
        foreground = true;
    }

    private void stopForegroundCompat(boolean remove) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            stopForeground(remove ? STOP_FOREGROUND_REMOVE : STOP_FOREGROUND_DETACH);
        } else {
            stopForeground(remove);
        }
        foreground = false;
    }

    private Notification buildNotification(boolean playing) {
        int total = reader.getChunkCount();
        String sub = total > 0 ? "Fragment " + (reader.getIndex() + 1) + " / " + total : null;
        String preview = reader.currentChunkPreview();

        Notification.Builder b = Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
            ? new Notification.Builder(this, CHANNEL_ID) : new Notification.Builder(this);
        b.setSmallIcon(R.drawable.ic_reader_tile)
            .setLargeIcon(largeIcon)
            .setContentTitle(reader.displayTitle().isEmpty() ? "Czytnik strony" : reader.displayTitle())
            .setContentText(preview.isEmpty() ? reader.getStatus() : preview)
            .setSubText(sub)
            .setContentIntent(pendingToMain())
            .setDeleteIntent(pendingAction(ACTION_STOP, 9))
            .setOngoing(playing)
            .setShowWhen(false)
            .addAction(new Notification.Action.Builder(Icon.createWithResource(this, R.drawable.ic_skip_prev), "Cofnij", pendingAction(ACTION_PREV, 1)).build())
            .addAction(new Notification.Action.Builder(Icon.createWithResource(this, playing ? R.drawable.ic_pause : R.drawable.ic_play), playing ? "Pauza" : "Czytaj", pendingAction(ACTION_PLAY_PAUSE, 2)).build())
            .addAction(new Notification.Action.Builder(Icon.createWithResource(this, R.drawable.ic_skip_next), "Dalej", pendingAction(ACTION_NEXT, 3)).build())
            .addAction(new Notification.Action.Builder(Icon.createWithResource(this, R.drawable.ic_stop), "Stop", pendingAction(ACTION_STOP, 4)).build())
            .setStyle(new Notification.MediaStyle()
                .setMediaSession(mediaSession.getSessionToken())
                .setShowActionsInCompactView(0, 1, 2))
            .setVisibility(Notification.VISIBILITY_PUBLIC)
            .setColor(COLOR);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) b.setColorized(true);
        return b.build();
    }

    private Bitmap createLargeIcon() {
        int size = 128;
        Bitmap bmp = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(bmp);
        Paint bg = new Paint(Paint.ANTI_ALIAS_FLAG);
        bg.setColor(COLOR);
        canvas.drawRoundRect(new RectF(0, 0, size, size), 28, 28, bg);
        Paint wave = new Paint(Paint.ANTI_ALIAS_FLAG);
        wave.setColor(0xFFFFFFFF);
        wave.setStyle(Paint.Style.STROKE);
        wave.setStrokeCap(Paint.Cap.ROUND);
        float[] h = {14, 22, 32, 22, 14};
        float barW = 8f, gap = 6f;
        float totalW = h.length * barW + (h.length - 1) * gap;
        float startX = size / 2f - totalW / 2f + barW / 2f;
        for (int i = 0; i < h.length; i++) {
            float x = startX + i * (barW + gap);
            wave.setStrokeWidth(barW);
            canvas.drawLine(x, size / 2f - h[i], x, size / 2f + h[i], wave);
        }
        return bmp;
    }

    private PendingIntent pendingToMain() {
        Intent i = new Intent(this, MainActivity.class);
        i.setFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        return PendingIntent.getActivity(this, 0, i, PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
    }

    private PendingIntent pendingAction(String action, int reqCode) {
        Intent i = new Intent(this, PlayerService.class).setAction(action);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            return PendingIntent.getForegroundService(this, reqCode, i, PendingIntent.FLAG_IMMUTABLE);
        }
        return PendingIntent.getService(this, reqCode, i, PendingIntent.FLAG_IMMUTABLE);
    }
}
