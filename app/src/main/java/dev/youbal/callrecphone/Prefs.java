package dev.youbal.callrecphone;

import android.content.Context;
import android.content.SharedPreferences;

/** The few settings the app has. Recording and encryption are on by default. */
final class Prefs {
    private final SharedPreferences prefs;
    Prefs(Context context) { prefs = context.getSharedPreferences("settings", Context.MODE_PRIVATE); }
    static Prefs of(Context context) { return new Prefs(context.getApplicationContext()); }

    boolean recording() { return prefs.getBoolean("recording", true); }
    void recording(boolean on) { prefs.edit().putBoolean("recording", on).apply(); }

    /** AUTO (built-in access when ready, else the microphone), BUILTIN, SHIZUKU or MIC. */
    String mode() { return prefs.getString("mode", "AUTO"); }
    void mode(String value) { prefs.edit().putString("mode", value).apply(); }

    /** Delete recordings older than this many days; 0 keeps everything. */
    int keepDays() { return prefs.getInt("keep_days", 0); }
    void keepDays(int days) { prefs.edit().putInt("keep_days", days).apply(); }

    String lastStatus() { return prefs.getString("status", ""); }
    void lastStatus(String text) { prefs.edit().putString("status", text).apply(); }

    /** Encrypt new recordings with AES-256-GCM. Off keeps plain audio in the app's folder. */
    boolean encrypt() { return prefs.getBoolean("encrypt", true); }
    void encrypt(boolean on) { prefs.edit().putBoolean("encrypt", on).apply(); }

    /** Last encryption/export problem, shown in Settings so a failure is never silent. */
    String vaultStatus() { return prefs.getString("vault_status", ""); }
    void vaultStatus(String text) { prefs.edit().putString("vault_status", text).apply(); }
}
