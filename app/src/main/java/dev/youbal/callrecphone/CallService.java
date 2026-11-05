package dev.youbal.callrecphone;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Intent;
import android.graphics.drawable.Icon;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.telecom.Call;
import android.telecom.CallAudioState;
import android.telecom.InCallService;
import dev.youbal.callrec.lib.CallRecorder;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Telecom owns the calls; this service is the phone UI. It also tells the recorder when a call is live. */
public final class CallService extends InCallService implements CallRecorder.Listener {
    static CallService instance;
    static final List<Runnable> listeners = new ArrayList<>();
    private static final int NOTIFICATION = 41;
    private final Map<Call, Call.Callback> calls = new LinkedHashMap<>();
    private final Set<Call> attempted = new HashSet<>();
    private NotificationManager notifications;
    private CallRecorder recorder;
    private Call recordingCall;
    private boolean recording;

    @Override public void onCreate() {
        super.onCreate(); instance = this; PhoneApp app = PhoneApp.get(this);
        notifications = getSystemService(NotificationManager.class);
        NotificationChannel ring = new NotificationChannel("calls", "Incoming calls", NotificationManager.IMPORTANCE_HIGH);
        notifications.createNotificationChannel(ring);
        notifications.createNotificationChannel(new NotificationChannel("calls_active", "Call in progress", NotificationManager.IMPORTANCE_LOW));
        CallRecorder.Mode mode;
        try { mode = CallRecorder.Mode.valueOf(app.prefs.mode()); } catch (IllegalArgumentException e) { mode = CallRecorder.Mode.AUTO; }
        recorder = new CallRecorder(this, app.recordingsDir().getAbsolutePath(), this, mode);
        app.ensureNames(this::changed);
    }

    @Override public void onCallAdded(Call call) {
        super.onCallAdded(call);
        Call.Callback callback = new Call.Callback() {
            @Override public void onStateChanged(Call c, int state) { changed(); }
            @Override public void onDetailsChanged(Call c, Call.Details details) { changed(); }
            @Override public void onCallDestroyed(Call c) { changed(); }
        };
        calls.put(call, callback); call.registerCallback(callback, new Handler(Looper.getMainLooper()));
        changed(); bringForward();
    }

    @Override public void onCallRemoved(Call call) {
        super.onCallRemoved(call);
        Call.Callback callback = calls.remove(call);
        if (callback != null) call.unregisterCallback(callback);
        attempted.remove(call); changed();
    }

    @Override public void onBringToForeground(boolean dialpad) { bringForward(); }
    @Override public void onCallAudioStateChanged(CallAudioState state) { super.onCallAudioStateChanged(state); changed(); }

    private void bringForward() {
        try { startActivity(new Intent(this, CallActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_SINGLE_TOP)); }
        catch (RuntimeException ignored) { /* The notification stays available when background starts are restricted. */ }
    }

    List<Call> currentCalls() { return new ArrayList<>(calls.keySet()); }
    boolean recordingBusy() { return recording; }
    void refreshRecording() { updateRecording(); }
    /** Stops the recording but not the call. */
    void stopRecording() { if (recordingCall != null) { recorder.onCallEnded(); recordingCall = null; } }

    static String numberOf(Call call) { return call.getDetails().getHandle() == null ? "" : call.getDetails().getHandle().getSchemeSpecificPart(); }
    private static boolean incoming(Call call) {
        return Build.VERSION.SDK_INT >= 29 ? call.getDetails().getCallDirection() == Call.Details.DIRECTION_INCOMING : call.getState() == Call.STATE_RINGING;
    }

    // ---- recording -----------------------------------------------------------------------------------------------
    /** Records a single connected call. Stops on hold, a second call, the end of the call, or when switched off. */
    private void updateRecording() {
        if (recorder == null) return;
        PhoneApp app = PhoneApp.get(this);
        if (recordingCall != null) {
            if (!app.prefs.recording() || calls.size() != 1 || !calls.containsKey(recordingCall) || recordingCall.getState() != Call.STATE_ACTIVE) { recorder.onCallEnded(); recordingCall = null; }
            return;
        }
        if (!app.prefs.recording() || calls.size() != 1) return;
        Call call = calls.keySet().iterator().next();
        if (call.getState() != Call.STATE_ACTIVE || !attempted.add(call)) return;
        recordingCall = call;
        Vault.setRecordingActive(true);
        recorder.onCallActive(numberOf(call), incoming(call) ? CallRecorder.IN : CallRecorder.OUT);
    }

    private void status(String text) { PhoneApp app = PhoneApp.get(this); app.prefs.lastStatus(text); changed(); }
    @Override public void onRecordingStarted() { recording = true; status("Recording this call"); }
    @Override public void onRecordingSaved(String path, String source, int seconds, int audiblePercent) {
        recording = false; recordingCall = null; Vault.setRecordingActive(false);
        // The recorder can only write plain audio to shared storage. Queue it, then seal it off
        // the main thread; the queue survives a crash and is retried at the next launch.
        Vault.enqueue(this, path, seconds * 1000L);
        PhoneApp app = PhoneApp.get(this);
        app.io.execute(() -> Vault.processPending(app));
        status("Saved a " + seconds + " s recording"); notifySaved();
    }
    @Override public void onRecordingSilent(String path, String source, int seconds, int audiblePercent) {
        recording = false; recordingCall = null; Vault.setRecordingActive(false); Vault.discard(this, path);
        status("The recording had no sound, so it was discarded. Check Shizuku, or try the microphone mode.");
    }
    @Override public void onShizukuUnavailable() { recording = false; recordingCall = null; Vault.setRecordingActive(false); status("Recording access is off, so this call was not recorded. Start it in Settings → Setup."); }
    @Override public void onError(String message) { recording = false; recordingCall = null; Vault.setRecordingActive(false); status("Recording failed: " + message); }
    private void notifySaved() { for (Runnable r : new ArrayList<>(PhoneApp.get(this).recordingListeners)) r.run(); }

    // ---- notification --------------------------------------------------------------------------------------------
    private Notification.Action action(String title, String what, int code) {
        PendingIntent intent = PendingIntent.getBroadcast(this, code, new Intent(this, CallActionReceiver.class).setAction(what), PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        return new Notification.Action.Builder(Icon.createWithResource(this, R.drawable.ic_phone), title, intent).build();
    }
    /** Answers, declines or ends whichever call the notification is about: the ringing one first. */
    void act(String what) {
        Call focus = null;
        for (Call c : currentCalls()) if (c.getState() == Call.STATE_RINGING) { focus = c; break; }
        if (focus == null && !calls.isEmpty()) focus = currentCalls().get(0);
        if (focus == null) return;
        if (CallActionReceiver.ANSWER.equals(what) && focus.getState() == Call.STATE_RINGING) focus.answer(focus.getDetails().getVideoState());
        else if (CallActionReceiver.DECLINE.equals(what) && focus.getState() == Call.STATE_RINGING) focus.reject(false, null);
        else if (CallActionReceiver.END.equals(what)) focus.disconnect();
    }

    private void changed() {
        updateRecording();
        if (calls.isEmpty()) notifications.cancel(NOTIFICATION);
        else {
            Call focus = null;
            for (Call c : calls.keySet()) if (c.getState() == Call.STATE_RINGING) { focus = c; break; }
            if (focus == null) focus = calls.keySet().iterator().next();
            boolean ringing = focus.getState() == Call.STATE_RINGING;
            PendingIntent open = PendingIntent.getActivity(this, NOTIFICATION, new Intent(this, CallActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_SINGLE_TOP), PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
            String number = numberOf(focus); String saved = PhoneApp.get(this).phoneName(number);
            String title = !saved.isEmpty() ? saved : number.isEmpty() ? "Private number" : PhoneNumbers.display(number);
            Notification.Builder builder = new Notification.Builder(this, ringing && !CallActivity.visible ? "calls" : "calls_active").setOnlyAlertOnce(true)
                .setSmallIcon(R.drawable.ic_phone).setContentTitle(title).setContentText(ringing ? "Incoming call" : recording ? "Recording · tap for controls" : "Call in progress · tap for controls")
                .setCategory(Notification.CATEGORY_CALL).setOngoing(true).setContentIntent(open).setVisibility(Notification.VISIBILITY_PRIVATE);
            if (ringing) { builder.setFullScreenIntent(open, true); builder.addAction(action("Decline", CallActionReceiver.DECLINE, 42)); builder.addAction(action("Answer", CallActionReceiver.ANSWER, 43)); }
            else builder.addAction(action("End call", CallActionReceiver.END, 44));
            notifications.notify(NOTIFICATION, builder.build());
        }
        for (Runnable listener : new ArrayList<>(listeners)) listener.run();
    }

    @Override public void onDestroy() {
        Vault.setRecordingActive(false);
        if (recorder != null) { if (recordingCall != null) recorder.onCallEnded(); recorder.release(); }
        for (Map.Entry<Call, Call.Callback> e : calls.entrySet()) e.getKey().unregisterCallback(e.getValue());
        calls.clear(); notifications.cancel(NOTIFICATION); instance = null;
        for (Runnable r : new ArrayList<>(listeners)) r.run();
        super.onDestroy();
    }
}
