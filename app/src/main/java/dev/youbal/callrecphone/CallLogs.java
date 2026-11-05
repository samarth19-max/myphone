package dev.youbal.callrecphone;

import android.Manifest;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.provider.CallLog;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/** Reads the phone's own call log. Nothing is stored or sent anywhere. */
final class CallLogs {
    private CallLogs() {}

    static final class Entry {
        final String number, direction, state; final long time, duration;
        Entry(String number, String direction, String state, long time, long duration) { this.number = number; this.direction = direction; this.state = state; this.time = time; this.duration = duration; }
        boolean missed() { return state.equals("missed") || state.equals("rejected"); }
        /** "Outgoing · 0:16", "Missed", "Not answered" */
        String subtitle() {
            String type = direction.equals("incoming") ? "Incoming" : "Outgoing";
            if (state.equals("missed")) type = "Missed"; else if (state.equals("rejected")) type = "Declined"; else if (state.equals("unanswered")) type = "Not answered";
            if (duration > 0) type += " · " + duration / 60 + ":" + String.format(Locale.ROOT, "%02d", duration % 60);
            return type;
        }
    }

    static boolean allowed(PhoneApp app) { return app.checkSelfPermission(Manifest.permission.READ_CALL_LOG) == PackageManager.PERMISSION_GRANTED; }

    /** Newest first. Empty without call-log access. */
    static List<Entry> load(PhoneApp app, int limit) {
        List<Entry> out = new ArrayList<>();
        if (!allowed(app)) return out;
        String[] columns = {CallLog.Calls.NUMBER, CallLog.Calls.DATE, CallLog.Calls.DURATION, CallLog.Calls.TYPE};
        try (Cursor rows = app.getContentResolver().query(CallLog.Calls.CONTENT_URI, columns, null, null, CallLog.Calls.DATE + " DESC")) {
            while (rows != null && rows.moveToNext() && out.size() < limit) {
                int type = rows.getInt(3);
                String direction = type == CallLog.Calls.OUTGOING_TYPE ? "outgoing" : "incoming";
                String state = type == CallLog.Calls.MISSED_TYPE ? "missed" : type == CallLog.Calls.REJECTED_TYPE ? "rejected" : rows.getLong(2) > 0 ? "completed" : "unanswered";
                out.add(new Entry(rows.isNull(0) ? "" : rows.getString(0), direction, state, rows.getLong(1), rows.getLong(2)));
            }
        } catch (RuntimeException e) { /* Access removed while reading: show what there is. */ }
        return out;
    }

    // ---- "last call" for the call screen ---------------------------------------------------------------------------
    private static final long FRESH_MS = 60_000;
    private static final class Cached { volatile String text = ""; volatile boolean loading; volatile long at; }
    private static final Map<String, Cached> cache = new ConcurrentHashMap<>();

    /** "18 Oct, 4:20 pm · Outgoing · 2:10" for the number's most recent finished call, "" when none yet. Refreshes in the background. */
    static String last(PhoneApp app, String number, long ignoreAfter, Runnable changed) {
        String key = PhoneNumbers.normalize(number);
        if (key.isEmpty() || !allowed(app)) return "";
        Cached cached = cache.computeIfAbsent(key, k -> new Cached());
        if (!cached.loading && System.currentTimeMillis() - cached.at > FRESH_MS) {
            cached.loading = true;
            app.io.execute(() -> {
                String text = "";
                for (Entry entry : load(app, 400)) {
                    if (entry.time >= ignoreAfter || !PhoneNumbers.normalize(entry.number).equals(key)) continue;
                    text = DateTimeFormatter.ofPattern("d MMM, h:mm a", Locale.getDefault()).format(Instant.ofEpochMilli(entry.time).atZone(ZoneId.systemDefault())) + " · " + entry.subtitle();
                    break;
                }
                cached.text = text; cached.at = System.currentTimeMillis(); cached.loading = false; app.main.post(changed);
            });
        }
        return cached.text;
    }
}
