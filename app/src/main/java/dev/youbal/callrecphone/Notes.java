package dev.youbal.callrecphone;

import android.content.Context;
import android.content.SharedPreferences;

/** One short note per phone number, kept on the phone. Shown on the call screen. */
final class Notes {
    private final SharedPreferences prefs;
    Notes(Context context) { prefs = context.getSharedPreferences("notes", Context.MODE_PRIVATE); }

    private static String key(String number) {
        String normal = PhoneNumbers.normalize(number);
        return normal.isEmpty() ? (number == null ? "" : number.trim()) : normal;
    }
    String get(String number) { String k = key(number); return k.isEmpty() ? "" : prefs.getString(k, ""); }
    void set(String number, String text) {
        String k = key(number); if (k.isEmpty()) return;
        String clean = text == null ? "" : text.trim();
        if (clean.isEmpty()) prefs.edit().remove(k).apply(); else prefs.edit().putString(k, clean.length() > 2000 ? clean.substring(0, 2000) : clean).apply();
    }
}
