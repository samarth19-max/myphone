package dev.youbal.callrecphone;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.RemoteInput;
import android.app.Service;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.graphics.drawable.Icon;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import dev.youbal.callrec.lib.BuiltinAccess;

/**
 * Waits for the phone's "Pair device with pairing code" dialog, then takes the six-digit code from a notification
 * (typing into the dialog and this app at once is only possible through a notification), pairs, and starts recording
 * access. Stops itself when done or after five minutes.
 */
public final class PairingService extends Service {
    static final String ACTION_CODE = "dev.youbal.callrecphone.PAIRING_CODE";
    private static final String CHANNEL = "pairing", KEY = "code";
    private static final int NOTIFICATION = 51;
    private static final long GIVE_UP_MS = 5 * 60 * 1000;

    private final Handler main = new Handler(Looper.getMainLooper());
    private NotificationManager notifications;
    private AutoCloseable discovery;
    private int port = -1;
    private boolean working, finished;
    private final Runnable giveUp = () -> finish("Pairing timed out. Start again from Settings.", false);

    @Override public void onCreate() {
        super.onCreate();
        notifications = getSystemService(NotificationManager.class);
        notifications.createNotificationChannel(new NotificationChannel(CHANNEL, "Pairing", NotificationManager.IMPORTANCE_HIGH));
        Notification waiting = build("Waiting for the pairing dialog", "Open Wireless debugging and tap “Pair device with pairing code”.", false);
        if (Build.VERSION.SDK_INT >= 34) startForeground(NOTIFICATION, waiting, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE);
        else startForeground(NOTIFICATION, waiting);
        try {
            discovery = BuiltinAccess.discoverPairing(this, new BuiltinAccess.PairingListener() {
                @Override public void onFound(int found) { main.post(() -> found(found)); }
                @Override public void onLost() { main.post(() -> lost()); }
            });
        } catch (RuntimeException e) { finish("Could not look for the pairing dialog: " + e.getMessage(), false); return; }
        main.postDelayed(giveUp, GIVE_UP_MS);
    }

    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null && ACTION_CODE.equals(intent.getAction())) {
            Bundle results = RemoteInput.getResultsFromIntent(intent);
            CharSequence code = results == null ? null : results.getCharSequence(KEY);
            if (code != null) submit(code.toString().replaceAll("\\s", ""));
        }
        return START_NOT_STICKY;
    }

    private void found(int foundPort) {
        if (finished || working) return;
        port = foundPort;
        notifications.notify(NOTIFICATION, build("Pairing dialog found", "Pull this down and type the six-digit code shown on the dialog.", true));
    }
    private void lost() {
        if (finished || working) return;
        port = -1;
        notifications.notify(NOTIFICATION, build("Waiting for the pairing dialog", "Open Wireless debugging and tap “Pair device with pairing code”.", false));
    }

    private void submit(String code) {
        if (finished || working) return;
        if (port <= 0) { notifications.notify(NOTIFICATION, build("Open the pairing dialog first", "Tap “Pair device with pairing code” in Wireless debugging.", false)); return; }
        if (!code.matches("[0-9]{6}")) { notifications.notify(NOTIFICATION, build("The code is six digits", "Type the code shown on the dialog.", true)); return; }
        working = true;
        BuiltinAccess.pair(this, port, code, new BuiltinAccess.Listener() {
            @Override public void onStatus(String message) { main.post(() -> notifications.notify(NOTIFICATION, build(message, "", false))); }
            @Override public void onDone(boolean ok, String message) { main.post(() -> finish(message, ok)); }
        });
    }

    private void finish(String message, boolean ok) {
        if (finished) return;
        finished = true; main.removeCallbacks(giveUp);
        PhoneApp.get(this).prefs.lastStatus(message);
        if (discovery != null) { try { discovery.close(); } catch (Exception ignored) { /* Already stopped. */ } discovery = null; }
        stopForeground(STOP_FOREGROUND_DETACH);
        notifications.notify(NOTIFICATION, new Notification.Builder(this, CHANNEL).setSmallIcon(R.drawable.ic_phone).setContentTitle(ok ? "Recording access is on" : "Recording access is off").setContentText(message).setAutoCancel(true).build());
        stopSelf();
    }

    private Notification build(String title, String text, boolean withCode) {
        Notification.Builder builder = new Notification.Builder(this, CHANNEL).setSmallIcon(R.drawable.ic_phone).setContentTitle(title).setContentText(text).setOngoing(true).setOnlyAlertOnce(true);
        if (withCode) {
            RemoteInput input = new RemoteInput.Builder(KEY).setLabel("Six-digit code").build();
            PendingIntent send = PendingIntent.getService(this, 1, new Intent(this, PairingService.class).setAction(ACTION_CODE), PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_MUTABLE);
            builder.addAction(new Notification.Action.Builder(Icon.createWithResource(this, R.drawable.ic_phone), "Enter code", send).addRemoteInput(input).build());
        }
        return builder.build();
    }

    @Override public void onDestroy() {
        main.removeCallbacks(giveUp);
        if (discovery != null) { try { discovery.close(); } catch (Exception ignored) { /* Already stopped. */ } }
        super.onDestroy();
    }

    @Override public IBinder onBind(Intent intent) { return null; }
}
