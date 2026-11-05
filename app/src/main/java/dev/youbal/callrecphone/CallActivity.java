package dev.youbal.callrecphone;

import android.animation.ValueAnimator;
import android.app.Activity;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.telecom.Call;
import android.telecom.CallAudioState;
import android.telecom.PhoneAccount;
import android.telecom.PhoneAccountHandle;
import android.telecom.TelecomManager;
import android.view.Gravity;
import android.view.View;
import android.view.animation.LinearInterpolator;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** The call screen: who you are talking to, the timer, and the controls. Built once, then updated in place. */
public final class CallActivity extends Activity {
    private static final int TOP = 0xff0b1220, BOTTOM = 0xff1b2a55, GLASS = 0x26ffffff;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Runnable refresh = this::bind;
    private final Runnable tick = new Runnable() { public void run() { bind(); handler.postDelayed(this, 1000); } };
    private boolean accountDialog = false, keypadOpen = false;
    private StringBuilder dtmf = new StringBuilder();
    private TextView status, timer, name, number, recordingPill, dtmfText;
    private CallCard card;
    private TextView avatar;
    private View ring, controls, keypad, ringingButtons, endRow, mute, speaker, hold, stop;
    private LinearLayout othersBox;
    private ValueAnimator pulse;
    private Call shown;
    static volatile boolean visible;
    private boolean safeMode;
    private TextView safeText, silence;
    private android.widget.Button safeAnswer, safeDecline, safeEnd;

    @Override public void onCreate(Bundle state) {
        super.onCreate(state); Ui.init(this);
        if (Build.VERSION.SDK_INT >= 27) { setShowWhenLocked(true); setTurnScreenOn(true); } else getWindow().addFlags(android.view.WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED | android.view.WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON);
        getWindow().setStatusBarColor(TOP); getWindow().setNavigationBarColor(BOTTOM);
        try { build(); } catch (Throwable e) { CrashLog.record(this, "CallActivity.build", e); buildSafe(); }
        lightIcons();
    }
    /** Dark call screen, so light status and navigation icons. Only after the layout exists: Android 15 throws if the window controller is asked for earlier, and styling must never stop a call screen opening. */
    private void lightIcons() {
        try {
            if (Build.VERSION.SDK_INT >= 30) { var controller = getWindow().getDecorView().getWindowInsetsController(); if (controller != null) controller.setSystemBarsAppearance(0, android.view.WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS | android.view.WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS); }
            else getWindow().getDecorView().setSystemUiVisibility(0);
        } catch (RuntimeException ignored) { /* Purely cosmetic. */ }
    }
    @Override protected void onStart() { super.onStart(); visible = true; CallService.listeners.add(refresh); handler.post(tick); if (pulse != null && !pulse.isStarted()) pulse.start(); }
    @Override protected void onStop() { visible = false; handler.removeCallbacks(tick); CallService.listeners.remove(refresh); if (pulse != null) pulse.cancel(); super.onStop(); }

    private int dp(float n) { return Ui.dp(this, n); }
    private LinearLayout.LayoutParams lp(int w, int h, int l, int t, int r, int b) { LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(w, h); p.setMargins(dp(l), dp(t), dp(r), dp(b)); return p; }
    private TextView label(String value, int sp, int color) { TextView v = new TextView(this); v.setText(value); v.setTextSize(sp); v.setTextColor(color); v.setGravity(Gravity.CENTER); return v; }

    private void build() {
        FrameLayout frame = new FrameLayout(this);
        GradientDrawable background = new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, new int[]{TOP, BOTTOM}); frame.setBackground(background);
        LinearLayout root = Ui.column(this); root.setGravity(Gravity.CENTER_HORIZONTAL); root.setPadding(dp(24), dp(16), dp(24), dp(20)); frame.addView(root, new FrameLayout.LayoutParams(-1, -1));
        if (Build.VERSION.SDK_INT >= 30) frame.setOnApplyWindowInsetsListener((v, insets) -> { android.graphics.Insets bars = insets.getInsets(android.view.WindowInsets.Type.systemBars()); root.setPadding(dp(24) + bars.left, dp(16) + bars.top, dp(24) + bars.right, dp(20) + bars.bottom); return insets; });
        else frame.setOnApplyWindowInsetsListener((v, i) -> { root.setPadding(dp(24) + i.getSystemWindowInsetLeft(), dp(16) + i.getSystemWindowInsetTop(), dp(24) + i.getSystemWindowInsetRight(), dp(20) + i.getSystemWindowInsetBottom()); return i; });

        TextView brand = label("CALLREC PHONE", 11, 0x99ffffff); brand.setLetterSpacing(0.16f); root.addView(brand);
        status = label("", 15, 0xffbcd0ff); root.addView(status, lp(-2, -2, 0, 22, 0, 0));
        timer = label("", 17, Color.WHITE); timer.setTypeface(android.graphics.Typeface.create("sans-serif-light", android.graphics.Typeface.NORMAL)); root.addView(timer);

        FrameLayout hero = new FrameLayout(this);
        ring = new View(this); GradientDrawable halo = new GradientDrawable(); halo.setShape(GradientDrawable.OVAL); halo.setStroke(dp(2), 0x66a5c0ff); halo.setColor(Color.TRANSPARENT); ring.setBackground(halo);
        hero.addView(ring, new FrameLayout.LayoutParams(dp(132), dp(132), Gravity.CENTER));
        avatar = Ui.avatar(this, "?", 104); avatar.setTextSize(36); hero.addView(avatar, new FrameLayout.LayoutParams(dp(104), dp(104), Gravity.CENTER));
        root.addView(hero, lp(dp(176), dp(176), 0, 14, 0, 6));
        pulse = ValueAnimator.ofFloat(0f, 1f); pulse.setDuration(1600); pulse.setRepeatCount(ValueAnimator.INFINITE); pulse.setInterpolator(new LinearInterpolator());
        pulse.addUpdateListener(a -> { float t = (float) a.getAnimatedValue(); ring.setScaleX(1f + t * .32f); ring.setScaleY(1f + t * .32f); ring.setAlpha(1f - t); });

        name = label("", 28, Color.WHITE); name.setTypeface(android.graphics.Typeface.create("sans-serif-medium", android.graphics.Typeface.NORMAL)); name.setSingleLine(true); name.setEllipsize(android.text.TextUtils.TruncateAt.END); root.addView(name, lp(-1, -2, 0, 6, 0, 0));
        number = label("", 16, 0xffbcd0ff); root.addView(number);
        card = new CallCard(this); card.setVisibility(View.GONE);
        recordingPill = label("● Recording", 12, 0xffff8a8a); recordingPill.setPadding(dp(12), dp(4), dp(12), dp(4)); recordingPill.setBackground(Ui.shape(0x33ff5a5a, 14, this)); recordingPill.setVisibility(View.GONE); root.addView(recordingPill, lp(-2, -2, 0, 10, 0, 0));
        othersBox = Ui.column(this); root.addView(othersBox, lp(-1, -2, 0, 10, 0, 0));
        android.widget.ScrollView details = new android.widget.ScrollView(this); details.setVerticalScrollBarEnabled(false); details.addView(card); root.addView(details, lp(-1, 0, 0, 12, 0, 8)); ((LinearLayout.LayoutParams) details.getLayoutParams()).weight = 1;

        // Controls grid: mute, keypad, speaker, hold
        LinearLayout grid = Ui.row(this); grid.setGravity(Gravity.CENTER);
        mute = control(grid, "Mute", "Mute", v -> { CallService s = CallService.instance; CallAudioState a = s == null ? null : s.getCallAudioState(); if (s != null && a != null) s.setMuted(!a.isMuted()); });
        control(grid, "Keypad", "Keypad", v -> { keypadOpen = true; bind(); });
        speaker = control(grid, "Speaker", "Speaker", v -> toggleSpeaker());
        hold = control(grid, "Pause", "Hold", v -> { Call c = shown; if (c == null) return; if (c.getState() == Call.STATE_HOLDING) c.unhold(); else c.hold(); });
        controls = grid; root.addView(grid, lp(-1, -2, 0, 0, 0, 14));

        // Keypad (DTMF)
        LinearLayout pad = Ui.column(this); pad.setGravity(Gravity.CENTER_HORIZONTAL); pad.setVisibility(View.GONE);
        dtmfText = label("", 24, Color.WHITE); dtmfText.setMinHeight(dp(36)); pad.addView(dtmfText);
        for (String keys : new String[]{"123", "456", "789", "*0#"}) {
            LinearLayout row = Ui.row(this); row.setGravity(Gravity.CENTER);
            for (char digit : keys.toCharArray()) {
                TextView key = label(String.valueOf(digit), 24, Color.WHITE); Ui.rippleCircle(key, GLASS); key.setOnClickListener(v -> { Call c = shown; if (c == null) return; c.playDtmfTone(digit); handler.postDelayed(c::stopDtmfTone, 140); dtmf.append(digit); dtmfText.setText(dtmf); });
                row.addView(key, lp(dp(64), dp(64), 8, 4, 8, 4));
            }
            pad.addView(row);
        }
        TextView hide = label("Hide keypad", 14, 0xffbcd0ff); hide.setPadding(dp(16), dp(10), dp(16), dp(10)); hide.setOnClickListener(v -> { keypadOpen = false; bind(); }); pad.addView(hide);
        keypad = pad; root.addView(pad, lp(-1, -2, 0, 0, 0, 10));

        // Answer / decline
        LinearLayout incoming = Ui.row(this); incoming.setGravity(Gravity.CENTER); incoming.setVisibility(View.GONE);
        incoming.addView(labelled("Decline", Ui.circleButton(this, "End", 0xffe5484d, Color.WHITE, 72, v -> { if (shown != null) shown.reject(false, null); })), lp(dp(110), -2, 0, 0, 0, 0));
        incoming.addView(labelled("Answer", Ui.circleButton(this, "Call", 0xff22c55e, Color.WHITE, 72, v -> { if (shown != null) shown.answer(shown.getDetails().getVideoState()); })), lp(dp(110), -2, 0, 0, 0, 0));
        ringingButtons = incoming; root.addView(incoming, lp(-1, -2, 0, 0, 0, 4));
        silence = label("Silence ringer", 14, 0xffbcd0ff); silence.setPadding(dp(18), dp(12), dp(18), dp(12)); silence.setOnClickListener(v -> silenceRinger()); root.addView(silence, lp(-2, -2, 0, 0, 0, 8));

        // End call
        LinearLayout end = Ui.row(this); end.setGravity(Gravity.CENTER);
        end.addView(Ui.circleButton(this, "End", 0xffe5484d, Color.WHITE, 72, v -> { if (shown != null) shown.disconnect(); }));
        stop = label("Stop recording", 13, 0xffff8a8a); stop.setPadding(dp(18), dp(10), dp(18), dp(10)); stop.setOnClickListener(v -> { if (CallService.instance != null) CallService.instance.stopRecording(); bind(); }); stop.setVisibility(View.GONE);
        end.addView(stop, lp(-2, -2, 16, 0, 0, 0));
        endRow = end; root.addView(end, lp(-1, -2, 0, 0, 0, 6));
        setContentView(frame); frame.requestApplyInsets();
    }
    private LinearLayout labelled(String text, View button) { LinearLayout c = Ui.column(this); c.setGravity(Gravity.CENTER_HORIZONTAL); c.addView(button); c.addView(label(text, 13, 0xffd8e2ff), lp(-2, -2, 0, 6, 0, 0)); return c; }
    private View control(LinearLayout row, String icon, String text, View.OnClickListener click) {
        View button = Ui.circleButton(this, icon, GLASS, Color.WHITE, 60, click); LinearLayout c = labelled(text, button); ((TextView) c.getChildAt(1)).setTag(text); row.addView(c, lp(0, -2, 0, 0, 0, 0)); ((LinearLayout.LayoutParams) c.getLayoutParams()).weight = 1; return button;
    }
    private void setActive(View button, String icon, boolean on) { Ui.rippleCircle(button, on ? Color.WHITE : GLASS); Ui.setIcon(button, icon, on ? 0xff0b1220 : Color.WHITE); }
    private void toggleSpeaker() {
        CallService s = CallService.instance; CallAudioState a = s == null ? null : s.getCallAudioState(); if (s == null || a == null) return;
        if ((a.getSupportedRouteMask() & (CallAudioState.ROUTE_BLUETOOTH | CallAudioState.ROUTE_WIRED_HEADSET)) != 0) { audioRoutes(s, a); return; }
        s.setAudioRoute(a.getRoute() == CallAudioState.ROUTE_SPEAKER ? CallAudioState.ROUTE_EARPIECE : CallAudioState.ROUTE_SPEAKER);
    }
    private void audioRoutes(CallService service, CallAudioState audio) {
        ArrayList<Integer> routes = new ArrayList<>(); ArrayList<String> labels = new ArrayList<>(); int selected = -1;
        int[] choices = {CallAudioState.ROUTE_EARPIECE, CallAudioState.ROUTE_SPEAKER, CallAudioState.ROUTE_BLUETOOTH, CallAudioState.ROUTE_WIRED_HEADSET}; String[] names = {"Phone", "Speaker", "Bluetooth", "Wired headset"};
        for (int i = 0; i < choices.length; i++) if ((audio.getSupportedRouteMask() & choices[i]) != 0) { if (audio.getRoute() == choices[i]) selected = routes.size(); routes.add(choices[i]); labels.add(names[i]); }
        Ui.sheet(this, "Audio output", labels.toArray(new String[0]), selected, n -> service.setAudioRoute(routes.get(n))).show();
    }

    private Call primary(List<Call> calls) {
        for (Call c : calls) if (c.getState() == Call.STATE_RINGING) return c;
        for (Call c : calls) if (c.getState() != Call.STATE_DISCONNECTED) return c;
        return calls.get(0);
    }
    private String stateText(Call call) {
        return switch (call.getState()) {
            case Call.STATE_RINGING, Call.STATE_SIMULATED_RINGING -> "Incoming call"; case Call.STATE_DIALING, Call.STATE_CONNECTING -> "Calling…"; case Call.STATE_ACTIVE -> "Connected"; case Call.STATE_HOLDING -> "On hold";
            case Call.STATE_DISCONNECTED -> "Call ended"; case Call.STATE_SELECT_PHONE_ACCOUNT -> "Choose a SIM"; case Call.STATE_DISCONNECTING -> "Ending call…"; case Call.STATE_AUDIO_PROCESSING -> "Processing call…"; case Call.STATE_NEW, Call.STATE_PULLING_CALL -> "Connecting…"; default -> "Call";
        };
    }
    private String phoneOf(Call call) { return call.getDetails().getHandle() == null ? "" : call.getDetails().getHandle().getSchemeSpecificPart(); }

    private void bind() {
        if (safeMode) { bindSafe(); return; }
        try { bindFull(); } catch (Throwable e) { CrashLog.record(this, "CallActivity.bind", e); buildSafe(); bindSafe(); }
    }
    private void editNote(String phone) {
        android.widget.EditText box = new android.widget.EditText(this); PhoneApp app = PhoneApp.get(this); box.setText(app.notes.get(phone)); box.setMinLines(3); box.setGravity(Gravity.TOP);
        new android.app.AlertDialog.Builder(this).setTitle("Note for this number").setView(box)
            .setPositiveButton("Save", (d, n) -> { app.notes.set(phone, box.getText().toString()); bind(); }).setNegativeButton("Cancel", null).show();
    }
    /** Allowed without MODIFY_PHONE_STATE because this app is the default phone app whenever this screen is used. */
    @android.annotation.SuppressLint("MissingPermission")
    private void silenceRinger() { try { getSystemService(TelecomManager.class).silenceRinger(); } catch (SecurityException ignored) { /* Some phones only allow this from the hardware volume key. */ } }

    /** Plain call screen with nothing fancy to fail: used only if the designed screen throws, so a call can always be answered and ended. */
    private void buildSafe() {
        safeMode = true;
        LinearLayout box = new LinearLayout(this); box.setOrientation(LinearLayout.VERTICAL); box.setGravity(Gravity.CENTER); box.setBackgroundColor(TOP); box.setPadding(48, 96, 48, 48);
        safeText = new TextView(this); safeText.setTextColor(Color.WHITE); safeText.setTextSize(24); safeText.setGravity(Gravity.CENTER); box.addView(safeText);
        safeAnswer = new android.widget.Button(this); safeAnswer.setText("Answer"); safeAnswer.setOnClickListener(v -> { if (shown != null) shown.answer(shown.getDetails().getVideoState()); }); box.addView(safeAnswer);
        safeDecline = new android.widget.Button(this); safeDecline.setText("Decline"); safeDecline.setOnClickListener(v -> { if (shown != null) shown.reject(false, null); }); box.addView(safeDecline);
        safeEnd = new android.widget.Button(this); safeEnd.setText("End call"); safeEnd.setOnClickListener(v -> { if (shown != null) shown.disconnect(); }); box.addView(safeEnd);
        setContentView(box);
    }
    private void bindSafe() {
        CallService service = CallService.instance;
        if (service == null || service.currentCalls().isEmpty()) { finish(); return; }
        Call call = primary(service.currentCalls()); shown = call; boolean ringing = call.getState() == Call.STATE_RINGING;
        safeText.setText(PhoneNumbers.display(phoneOf(call)) + "\n" + stateText(call));
        safeAnswer.setVisibility(ringing ? View.VISIBLE : View.GONE); safeDecline.setVisibility(ringing ? View.VISIBLE : View.GONE); safeEnd.setVisibility(ringing ? View.GONE : View.VISIBLE);
    }
    private void bindFull() {
        CallService service = CallService.instance;
        if (service == null || service.currentCalls().isEmpty()) { finish(); return; }
        service.refreshRecording();
        List<Call> calls = service.currentCalls(); Call call = primary(calls); shown = call; int state = call.getState();
        String phone = phoneOf(call); PhoneApp app = PhoneApp.get(this); app.ensureNames(refresh); String saved = app.phoneName(phone);
        String title = !saved.isEmpty() ? saved : phone.isEmpty() ? "Private number" : PhoneNumbers.display(phone);
        name.setText(title); Ui.bindAvatar(avatar, !saved.isEmpty() ? saved : phone.isEmpty() ? "?" : title);
        number.setText(!saved.isEmpty() ? PhoneNumbers.display(phone) : "");
        card.bind(app.savedLine(phone), CallLogs.last(app, phone, Long.MAX_VALUE, refresh), app.notes.get(phone), v -> editNote(phone));

        boolean ringing = state == Call.STATE_RINGING, dialing = state == Call.STATE_DIALING || state == Call.STATE_CONNECTING || state == Call.STATE_NEW || state == Call.STATE_PULLING_CALL, active = state == Call.STATE_ACTIVE, held = state == Call.STATE_HOLDING;
        status.setText(stateText(call)); status.setTextColor(active ? 0xff7ee2a8 : ringing ? 0xffffd479 : 0xffbcd0ff);
        if (active && call.getDetails().getConnectTimeMillis() > 0) { long seconds = Math.max(0, (System.currentTimeMillis() - call.getDetails().getConnectTimeMillis()) / 1000); timer.setText(seconds / 3600 > 0 ? String.format(Locale.ROOT, "%d:%02d:%02d", seconds / 3600, seconds / 60 % 60, seconds % 60) : String.format(Locale.ROOT, "%d:%02d", seconds / 60, seconds % 60)); timer.setVisibility(View.VISIBLE); }
        else timer.setVisibility(View.GONE);
        if (pulse != null) { boolean wantPulse = ringing || dialing; if (wantPulse && !pulse.isStarted()) pulse.start(); if (!wantPulse && pulse.isStarted()) { pulse.cancel(); ring.setAlpha(0f); } }
        ring.setVisibility(ringing || dialing ? View.VISIBLE : View.INVISIBLE);

        boolean showControls = !ringing && state != Call.STATE_DISCONNECTED && !keypadOpen;
        controls.setVisibility(showControls ? View.VISIBLE : View.GONE); keypad.setVisibility(!ringing && keypadOpen ? View.VISIBLE : View.GONE);
        ringingButtons.setVisibility(ringing ? View.VISIBLE : View.GONE); silence.setVisibility(ringing ? View.VISIBLE : View.GONE); endRow.setVisibility(ringing ? View.GONE : View.VISIBLE);
        if (!keypadOpen && dtmf.length() > 0) { dtmf.setLength(0); dtmfText.setText(""); }
        CallAudioState audio = service.getCallAudioState();
        setActive(mute, audio != null && audio.isMuted() ? "Mute" : "Mic", audio != null && audio.isMuted());
        setActive(speaker, "Speaker", audio != null && audio.getRoute() == CallAudioState.ROUTE_SPEAKER);
        setActive(hold, "Pause", held); hold.setAlpha((call.getDetails().getCallCapabilities() & Call.Details.CAPABILITY_HOLD) != 0 ? 1f : .35f);

        boolean recording = service.recordingBusy(); recordingPill.setVisibility(recording ? View.VISIBLE : View.GONE); stop.setVisibility(recording && !ringing ? View.VISIBLE : View.GONE);

        othersBox.removeAllViews();
        for (Call other : calls) {
            if (other == call) continue; String otherPhone = phoneOf(other); String otherName = app.phoneName(otherPhone);
            LinearLayout line = Ui.row(this); line.setPadding(dp(14), dp(8), dp(14), dp(8)); line.setBackground(Ui.shape(GLASS, 16, this));
            line.addView(label((!otherName.isEmpty() ? otherName : PhoneNumbers.display(otherPhone)) + " · " + stateText(other), 13, Color.WHITE), lp(0, -2, 0, 0, 0, 0)); ((LinearLayout.LayoutParams) line.getChildAt(0).getLayoutParams()).weight = 1; ((TextView) line.getChildAt(0)).setGravity(Gravity.START);
            if (other.getState() == Call.STATE_RINGING) { TextView answer = label("Answer", 13, 0xff7ee2a8); answer.setPadding(dp(10), dp(6), dp(10), dp(6)); answer.setOnClickListener(v -> other.answer(other.getDetails().getVideoState())); line.addView(answer); }
            else { TextView swap = label(other.getState() == Call.STATE_HOLDING ? "Resume" : "Hold", 13, 0xffbcd0ff); swap.setPadding(dp(10), dp(6), dp(10), dp(6)); swap.setOnClickListener(v -> { if (other.getState() == Call.STATE_HOLDING) other.unhold(); else other.hold(); }); line.addView(swap); }
            othersBox.addView(line, lp(-1, -2, 0, 4, 0, 0));
        }
        if (state == Call.STATE_SELECT_PHONE_ACCOUNT && !accountDialog) chooseSim(call);
    }
    private void chooseSim(Call call) {
        Bundle extras = call.getDetails().getExtras(); if (extras == null) return;
        List<PhoneAccountHandle> accounts = extras.getParcelableArrayList(Call.AVAILABLE_PHONE_ACCOUNTS); if (accounts == null || accounts.isEmpty()) return;
        String[] labels = new String[accounts.size()]; TelecomManager telecom = getSystemService(TelecomManager.class);
        for (int i = 0; i < accounts.size(); i++) { PhoneAccount account = telecom.getPhoneAccount(accounts.get(i)); labels[i] = account == null ? "SIM " + (i + 1) : account.getLabel().toString(); }
        accountDialog = true; android.app.Dialog dialog = Ui.sheet(this, "Call using", labels, -1, n -> call.phoneAccountSelected(accounts.get(n), false)); dialog.setOnDismissListener(d -> accountDialog = false); dialog.show();
    }
}
