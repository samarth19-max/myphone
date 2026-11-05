package dev.youbal.callrecphone;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Bundle;
import android.provider.Settings;
import android.text.InputType;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;
import dev.youbal.callrec.lib.BuiltinAccess;
import dev.youbal.callrec.lib.CallRecorderSetup;
import java.util.List;
import java.util.Locale;

/** Setup (four steps with ticks), recording and encryption options, help, and problem reports. */
public final class SettingsActivity extends Activity {
    private static final int REQ_PERMISSIONS = 19, REQ_ROLE = 12, REQ_SHIZUKU = 71;
    private PhoneApp app;
    private LinearLayout content;

    @Override protected void onCreate(Bundle state) { super.onCreate(state); Ui.init(this); app = PhoneApp.get(this); }
    @Override protected void onResume() { super.onResume(); build(); }
    @Override protected void onActivityResult(int request, int result, Intent data) { super.onActivityResult(request, result, data); build(); }
    @Override public void onRequestPermissionsResult(int request, String[] permissions, int[] grants) { super.onRequestPermissionsResult(request, permissions, grants); app.reloadNames(null); build(); }

    private int dp(float n) { return Ui.dp(this, n); }
    private LinearLayout.LayoutParams lp(int w, int h, float weight) { return new LinearLayout.LayoutParams(w, h, weight); }
    private LinearLayout.LayoutParams lp(int w, int h) { return new LinearLayout.LayoutParams(w, h); }
    private boolean has(String permission) { return checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED; }
    private boolean isDefaultPhone() { return DefaultPhone.held(this); }
    private String[] permissions() {
        // minSdk is Android 15, so POST_NOTIFICATIONS is always required.
        return new String[]{Manifest.permission.READ_CONTACTS, Manifest.permission.READ_CALL_LOG, Manifest.permission.CALL_PHONE, Manifest.permission.RECORD_AUDIO, Manifest.permission.POST_NOTIFICATIONS};
    }
    private boolean permissionsDone() { for (String p : permissions()) if (!has(p)) return false; return true; }

    private void build() {
        Ui.init(this);
        LinearLayout root = Ui.column(this); root.setBackgroundColor(Ui.BG); root.setPadding(dp(16), dp(8), dp(16), dp(8));
        root.setOnApplyWindowInsetsListener((v, insets) -> { android.graphics.Insets bars = insets.getInsets(android.view.WindowInsets.Type.systemBars()); v.setPadding(dp(16) + bars.left, dp(8) + bars.top, dp(16) + bars.right, dp(8) + bars.bottom); return insets; });
        setContentView(root); root.requestApplyInsets();
        LinearLayout header = Ui.row(this);
        header.addView(Ui.icon(this, "Back", Ui.INK, v -> finish()), lp(dp(48), dp(48)));
        header.addView(Ui.title(this, "Settings", 22), lp(0, -2, 1)); root.addView(header, lp(-1, dp(56)));
        ScrollView scroll = new ScrollView(this); scroll.setVerticalScrollBarEnabled(false); content = Ui.column(this); scroll.addView(content); root.addView(scroll, lp(-1, 0, 1));
        setupCard(); recordingCard(); securityCard(); recoveryCard(); helpCard(); reportCard(); aboutCard();
    }

    private void step(LinearLayout card, int n, boolean done, String title, String text) {
        TextView t = Ui.title(this, n + "   " + title + (done ? "   ✓" : ""), 16); t.setTextColor(done ? Ui.OK : Ui.INK); t.setPadding(0, dp(10), 0, 0); card.addView(t); card.addView(Ui.text(this, text, 13, Ui.MUTED));
    }

    // ---- setup -------------------------------------------------------------------------------------------------------
    private void setupCard() {
        LinearLayout card = Ui.card(this); content.addView(card); card.addView(Ui.title(this, "Setup", 18));
        BuiltinAccess.State builtin = BuiltinAccess.state(this);
        CallRecorderSetup.State shizuku = CallRecorderSetup.state(this);
        boolean access = builtin == BuiltinAccess.State.RUNNING || shizuku == CallRecorderSetup.State.READY;

        step(card, 1, permissionsDone(), "Allow restricted settings and permissions", "App info \u2192 \u22EE (top right) \u2192 Allow restricted settings. Then allow the permissions.");
        card.addView(Ui.button(this, "Open app info", false, v -> startActivity(new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:" + getPackageName())))));
        card.addView(Ui.button(this, permissionsDone() ? "Permissions allowed \u2713" : "Allow permissions", !permissionsDone(), v -> requestPermissions(permissions(), REQ_PERMISSIONS)));

        boolean isDefault = isDefaultPhone();
        step(card, 2, isDefault, "Set as default phone app", "Gives you the call screen and lets the app see your calls.");
        card.addView(Ui.button(this, isDefault ? "Default phone app \u2713" : "Set as default", !isDefault, v -> {
            if (isDefault) { startActivity(new Intent(Settings.ACTION_MANAGE_DEFAULT_APPS_SETTINGS)); return; }
            Intent ask = DefaultPhone.request(this);
            if (ask != null) startActivityForResult(ask, REQ_ROLE);
            else Toast.makeText(this, "This phone does not offer the phone-app role.", Toast.LENGTH_LONG).show();
        }));

        step(card, 3, access, "Start recording access", "Lets the app record both sides of a call. Needs Wireless debugging (Android 11 or newer) and has to be started again after every reboot. Skip it to record with the microphone only.");
        switch (builtin) {
            case UNSUPPORTED:
                card.addView(Ui.text(this, "Not available on this phone (needs Android 11 or newer). Use the Shizuku app below, or the microphone.", 13, Ui.WARN));
                break;
            case NOT_PAIRED:
                card.addView(Ui.button(this, "Pair and start", true, v -> pairDialog()));
                break;
            case STOPPED:
                card.addView(Ui.button(this, "Start recording access", true, v -> startAccess()));
                card.addView(Ui.button(this, "Pair again", false, v -> pairDialog()));
                break;
            case RUNNING:
                card.addView(Ui.button(this, "Recording access is on \u2713", false, v -> Toast.makeText(this, "Running. It stops when the phone restarts.", Toast.LENGTH_SHORT).show()));
                card.addView(Ui.button(this, "Turn off", false, v -> { BuiltinAccess.stop(this); Toast.makeText(this, "Turning off\u2026", Toast.LENGTH_SHORT).show(); card.postDelayed(this::build, 1500); }));
                break;
        }
        if (builtin != BuiltinAccess.State.NOT_PAIRED && builtin != BuiltinAccess.State.UNSUPPORTED)
            card.addView(Ui.button(this, "Forget pairing", false, v -> new AlertDialog.Builder(this).setTitle("Forget pairing?").setMessage("Recording access stops and you will have to pair again.").setPositiveButton("Forget", (d, n) -> { BuiltinAccess.forget(this); build(); }).setNegativeButton("Cancel", null).show()));

        card.addView(Ui.text(this, "Prefer the Shizuku app? It works too: install it, start it, then allow this app in it.", 13, Ui.MUTED));
        card.addView(Ui.button(this, shizuku == CallRecorderSetup.State.NOT_INSTALLED ? "Install Shizuku" : "Open Shizuku", false, v -> startActivity(CallRecorderSetup.shizukuIntent(this))));
        card.addView(Ui.button(this, "Shizuku setup guide", false, v -> startActivity(CallRecorderSetup.guideIntent())));
        if (shizuku == CallRecorderSetup.State.NEEDS_PERMISSION || shizuku == CallRecorderSetup.State.READY)
            card.addView(Ui.button(this, shizuku == CallRecorderSetup.State.READY ? "Allowed in Shizuku \u2713" : "Allow in Shizuku", shizuku != CallRecorderSetup.State.READY, v -> CallRecorderSetup.requestPermission(REQ_SHIZUKU)));

        boolean tested = !Vault.list(this).isEmpty();
        step(card, 4, tested, "Make a test call", "Call someone for a few seconds. The recording appears in the Recordings tab.");
    }

    private void pairDialog() {
        new AlertDialog.Builder(this).setTitle("Pair with wireless debugging").setMessage(
            "1. Turn on Developer options, then Wireless debugging (Wi-Fi must be on).\n"
            + "2. Tap \u201CPair device with pairing code\u201D and leave that box open.\n"
            + "3. Pull down the notification from this app and type the six-digit code.\n\n"
            + "You only do this once. Allow notifications first if you have not.")
            .setPositiveButton("Continue", (d, n) -> {
                startForegroundService(new Intent(this, PairingService.class));
                startActivity(new Intent(Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS));
            }).setNegativeButton("Cancel", null).show();
    }
    private void startAccess() {
        Toast.makeText(this, "Starting\u2026", Toast.LENGTH_SHORT).show();
        BuiltinAccess.start(this, new BuiltinAccess.Listener() {
            @Override public void onStatus(String message) { app.main.post(() -> Toast.makeText(SettingsActivity.this, message, Toast.LENGTH_SHORT).show()); }
            @Override public void onDone(boolean ok, String message) { app.prefs.lastStatus(message); app.main.post(() -> { Toast.makeText(SettingsActivity.this, message, Toast.LENGTH_LONG).show(); build(); }); }
        });
    }

    // ---- recording ---------------------------------------------------------------------------------------------------
    private void recordingCard() {
        LinearLayout card = Ui.card(this); content.addView(card); card.addView(Ui.title(this, "Recording", 18));
        boolean on = app.prefs.recording();
        card.addView(Ui.text(this, on ? "On. Calls with one person are recorded once they connect." : "Off. Nothing is recorded.", 14, on ? Ui.OK : Ui.MUTED));
        card.addView(Ui.button(this, on ? "Turn recording off" : "Turn recording on", !on, v -> { app.prefs.recording(!on); build(); }));
        String[] modes = {"AUTO", "BUILTIN", "SHIZUKU", "MIC"}, modeNames = {"Automatic: both sides if access is on, else the microphone", "Built-in access only: both sides", "Shizuku app only: both sides", "Microphone only: needs speakerphone"};
        int current = 0; for (int i = 0; i < modes.length; i++) if (modes[i].equals(app.prefs.mode())) current = i;
        card.addView(Ui.button(this, "Source: " + modeNames[current].split(":")[0], false, v -> Ui.sheet(this, "Recording source", modeNames, indexOf(modes, app.prefs.mode()), n -> { app.prefs.mode(modes[n]); build(); }).show()));
        int[] days = {0, 7, 30, 90}; String[] dayNames = {"Keep forever", "Delete after 7 days", "Delete after 30 days", "Delete after 90 days"};
        int keep = 0; for (int i = 0; i < days.length; i++) if (days[i] == app.prefs.keepDays()) keep = i;
        card.addView(Ui.button(this, dayNames[keep], false, v -> Ui.sheet(this, "Keep recordings", dayNames, indexOf(days, app.prefs.keepDays()), n -> { app.prefs.keepDays(days[n]); app.io.execute(() -> { Vault.deleteOlderThan(this, days[n], System.currentTimeMillis()); app.main.post(this::build); }); build(); }).show()));
        List<Vault.Rec> all = Vault.list(this);
        long bytes = Vault.totalBytes(this);
        card.addView(Ui.text(this, all.size() + (all.size() == 1 ? " recording" : " recordings") + " · " + (bytes < 1048576 ? Math.max(0, bytes / 1024) + " KB" : String.format(Locale.ROOT, "%.1f MB", bytes / 1048576.0)), 13, Ui.MUTED));
        card.addView(Ui.text(this, "Saved on this phone only. Nothing is uploaded.", 13, Ui.MUTED));
        String status = app.prefs.lastStatus(); if (!status.isEmpty()) card.addView(Ui.text(this, "Last: " + status, 13, Ui.MUTED));
        card.addView(Ui.text(this, "Recording calls may need everyone's consent where you live. You are responsible for following the law.", 12, Ui.WARN));
    }
    private static int indexOf(String[] values, String value) { for (int i = 0; i < values.length; i++) if (values[i].equals(value)) return i; return 0; }
    private static int indexOf(int[] values, int value) { for (int i = 0; i < values.length; i++) if (values[i] == value) return i; return 0; }

    // ---- encryption --------------------------------------------------------------------------------------------------
    private void securityCard() {
        LinearLayout card = Ui.card(this); content.addView(card); card.addView(Ui.title(this, "Encryption", 18));
        boolean enc = app.prefs.encrypt();
        card.addView(Ui.text(this, enc
            ? "On. New recordings are sealed with AES-256-GCM and kept in the app's private storage."
            : "Off. New recordings are saved as plain audio on the phone, readable by anything that can reach the folder.",
            14, enc ? Ui.OK : Ui.WARN));
        card.addView(Ui.button(this, enc ? "Save new recordings without encryption" : "Encrypt new recordings (AES-256)", !enc, v -> {
            boolean next = !enc;
            app.prefs.encrypt(next);
            if (next) app.io.execute(() -> { Vault.migratePlain(this); app.main.post(this::build); });
            else Toast.makeText(this, "New recordings will be plain audio.", Toast.LENGTH_LONG).show();
            build();
        }));
        card.addView(Ui.text(this, "Playback and sharing always produce a decrypted .m4a first, so WhatsApp (and any other app) receives ordinary audio, never ciphertext. Everything is local — no account, no server, no network.", 13, Ui.MUTED));
        card.addView(Ui.text(this, Keys.status(this), 13, Ui.MUTED));
        List<Vault.Rec> all = Vault.list(this);
        int sealed = 0; for (Vault.Rec r : all) if (r.encrypted) sealed++;
        card.addView(Ui.text(this, sealed + " encrypted · " + (all.size() - sealed) + " unencrypted", 13, Ui.MUTED));
        card.addView(Ui.button(this, "Save all to phone without encryption", false, v -> confirmSaveAll(all.size())));
        String vaultStatus = app.prefs.vaultStatus();
        if (!vaultStatus.isEmpty()) card.addView(Ui.text(this, "Encryption: " + vaultStatus, 13, Ui.WARN));
    }

    private void confirmSaveAll(int count) {
        if (count == 0) { Toast.makeText(this, "No recordings to save.", Toast.LENGTH_SHORT).show(); return; }
        new AlertDialog.Builder(this).setTitle("Save all recordings without encryption?")
            .setMessage("Writes " + count + " plain audio file(s) into Music/Callrec Phone so the phone itself can keep them. Anyone who can reach that folder can play them. This stays entirely on the device.")
            .setPositiveButton("Save copies", (d, w) -> app.io.execute(() -> {
                try {
                    int saved = Vault.saveAllToPhone(this);
                    app.main.post(() -> { Toast.makeText(this, "Saved " + saved + " unencrypted recording(s).", Toast.LENGTH_LONG).show(); build(); });
                } catch (Exception e) {
                    app.main.post(() -> { Toast.makeText(this, "Could not save: " + e.getMessage(), Toast.LENGTH_LONG).show(); build(); });
                }
            }))
            .setNegativeButton("Cancel", null).show();
    }

    private void recoveryCard() {
        LinearLayout card = Ui.card(this); content.addView(card); card.addView(Ui.title(this, "Recovery key", 18));
        card.addView(Ui.text(this, "Encrypted recordings are tied to this installation. If the encryption key is ever lost (for example after a Keystore reset), this offline key restores access. Keep it private: it never leaves the phone unless you copy it.", 13, Ui.MUTED));
        card.addView(Ui.button(this, "Show recovery key", false, v -> {
            try {
                final String key = Keys.exportRecovery(this);
                new AlertDialog.Builder(this).setTitle("Recovery key")
                    .setMessage("Anyone with this key can decrypt your recordings. Store it offline and never share it.\n\n" + key)
                    .setPositiveButton("Copy", (d, w) -> {
                        ClipData clip = ClipData.newPlainText("Callrec recovery key", key);
                        // Android 13+ masks the clipboard preview for sensitive content.
                        android.os.PersistableBundle extras = new android.os.PersistableBundle();
                        extras.putBoolean(android.content.ClipDescription.EXTRA_IS_SENSITIVE, true);
                        clip.getDescription().setExtras(extras);
                        getSystemService(ClipboardManager.class).setPrimaryClip(clip);
                        Toast.makeText(this, "Copied. Keep it private — the clipboard is readable by other apps.", Toast.LENGTH_LONG).show();
                    })
                    .setNegativeButton("Close", null).show();
            } catch (Keys.Unavailable e) { Toast.makeText(this, e.getMessage(), Toast.LENGTH_LONG).show(); }
        }));
        card.addView(Ui.button(this, "Restore a recovery key", false, v -> {
            EditText box = new EditText(this); box.setHint("Paste CRKEY1:…"); box.setInputType(InputType.TYPE_CLASS_TEXT);
            new AlertDialog.Builder(this).setTitle("Restore recovery key").setView(box)
                .setPositiveButton("Restore", (d, w) -> {
                    try { Keys.importRecovery(this, box.getText().toString()); Toast.makeText(this, "Recovery key restored.", Toast.LENGTH_SHORT).show(); build(); }
                    catch (Keys.Unavailable e) { Toast.makeText(this, e.getMessage(), Toast.LENGTH_LONG).show(); }
                }).setNegativeButton("Cancel", null).show();
        }));
    }

    // ---- help, problems, about ---------------------------------------------------------------------------------------
    private void helpCard() {
        LinearLayout card = Ui.card(this); content.addView(card); card.addView(Ui.title(this, "How it works", 18));
        String[][] items = {
            {"Recents", "Your calls, with names. Tap a row to call back. Search any name or number."},
            {"Contacts", "People saved in Google, on the phone or on the SIM. Tap one for numbers and a note."},
            {"Dialpad", "Type or paste a number. Saved names show as suggestions."},
            {"Call screen", "Shows who is calling, where they are saved, your last call and your note. Answer, Decline, Silence, Mute, Speaker, Hold, Keypad, End."},
            {"Notes", "A short note per number. Tap the note on the call screen or in a contact."},
            {"Recording", "Connected calls with one person record by themselves. Stop it from the call screen."},
            {"Recordings", "Play, share, save an unencrypted copy, or delete. Old ones can delete themselves."},
            {"Encryption", "New recordings are AES-256-GCM encrypted. Play and Share decrypt a temporary copy; Save to phone writes a plain .m4a into Music/Callrec Phone. All offline."},
            {"Something broke", "Settings → Problem report → Copy."}};
        for (String[] item : items) { TextView t = Ui.title(this, item[0], 15); t.setPadding(0, dp(10), 0, 0); card.addView(t); card.addView(Ui.text(this, item[1], 13, Ui.MUTED)); }
    }
    private void reportCard() {
        String problem = CrashLog.read(this); if (problem.isEmpty()) return;
        LinearLayout card = Ui.card(this); content.addView(card); card.addView(Ui.title(this, "Problem report", 18));
        card.addView(Ui.text(this, "Saved on this phone only. Copy it when you report a bug.", 13, Ui.MUTED));
        TextView trace = Ui.text(this, problem.length() > 1200 ? problem.substring(0, 1200) + "…" : problem, 11, Ui.MUTED); trace.setTextIsSelectable(true); card.addView(trace);
        card.addView(Ui.button(this, "Copy report", true, v -> {
            ClipData clip = ClipData.newPlainText("Callrec Phone report", problem);
            android.os.PersistableBundle extras = new android.os.PersistableBundle();
            extras.putBoolean(android.content.ClipDescription.EXTRA_IS_SENSITIVE, true);
            clip.getDescription().setExtras(extras);
            getSystemService(ClipboardManager.class).setPrimaryClip(clip);
            Toast.makeText(this, "Copied.", Toast.LENGTH_SHORT).show();
        }));
        card.addView(Ui.button(this, "Clear report", false, v -> { CrashLog.clear(this); build(); }));
    }
    private void aboutCard() {
        LinearLayout card = Ui.card(this); content.addView(card); card.addView(Ui.title(this, "About", 18));
        String version = ""; try { version = getPackageManager().getPackageInfo(getPackageName(), 0).versionName; } catch (PackageManager.NameNotFoundException ignored) { /* Shown without a version. */ }
        card.addView(Ui.text(this, "Callrec Phone " + version + ". For Android 15 and above (API 35–37). Open source. No account, no ads, no tracking: your calls, contacts and recordings never leave the phone. Encryption, decryption and saving work entirely offline. The network permission is only used to talk to this phone\u2019s own wireless debugging.", 13, Ui.MUTED));
        card.addView(Ui.text(this, "Call recording by callrec. Uses this phone\u2019s own wireless debugging, or the Shizuku app if you prefer it.", 13, Ui.MUTED));
        card.addView(Ui.button(this, "Source code", false, v -> startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse("https://github.com/Youbal-oops/Phone")))));
    }
}
