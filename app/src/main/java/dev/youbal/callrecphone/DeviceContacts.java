package dev.youbal.callrecphone;

import android.Manifest;
import android.content.Context;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.provider.ContactsContract;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** Names saved in the phone's own contacts (Google, phone storage and SIM, as Android merges them), kept on the phone and used only to show and find people. */
final class DeviceContacts {
    private DeviceContacts() {}
    /** names: normalised number -> every name saved for it. sources: normalised number + newline + name -> "Google", "Phone" or "SIM". */
    record Loaded(Map<String, List<String>> names, Map<String, String> sources) {}

    /** Empty when Contacts access has not been given. */
    static Loaded load(Context context) {
        Map<String, List<String>> names = new HashMap<>(); Map<String, String> sources = new HashMap<>();
        if (context.checkSelfPermission(Manifest.permission.READ_CONTACTS) != PackageManager.PERMISSION_GRANTED) return new Loaded(names, sources);
        Map<Long, String> accounts = new HashMap<>();
        try (Cursor raw = context.getContentResolver().query(ContactsContract.RawContacts.CONTENT_URI, new String[]{ContactsContract.RawContacts._ID, ContactsContract.RawContacts.ACCOUNT_TYPE}, null, null, null)) {
            while (raw != null && raw.moveToNext()) accounts.put(raw.getLong(0), source(raw.getString(1)));
        } catch (RuntimeException e) { /* Without account types every name is simply labelled Phone. */ }
        try { readPhones(context, true, accounts, names, sources); }
        catch (RuntimeException first) {
            report(context, "with account labels", first); names.clear(); sources.clear();
            // Names matter more than the Google/Phone/SIM label, so retry without the label column.
            try { readPhones(context, false, accounts, names, sources); } catch (RuntimeException second) { report(context, "names only", second); names.clear(); sources.clear(); }
        }
        return new Loaded(names, sources);
    }

    private static void readPhones(Context context, boolean withAccount, Map<Long, String> accounts, Map<String, List<String>> names, Map<String, String> sources) {
        String[] columns = withAccount
            ? new String[]{ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME, ContactsContract.CommonDataKinds.Phone.NUMBER, ContactsContract.CommonDataKinds.Phone.RAW_CONTACT_ID}
            : new String[]{ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME, ContactsContract.CommonDataKinds.Phone.NUMBER};
        try (Cursor rows = context.getContentResolver().query(ContactsContract.CommonDataKinds.Phone.CONTENT_URI, columns, null, null, null)) {
            while (rows != null && rows.moveToNext()) {
                String name = rows.getString(0), key = PhoneNumbers.normalize(rows.getString(1));
                if (name == null || name.isBlank() || key.isEmpty()) continue; name = name.trim();
                List<String> saved = names.computeIfAbsent(key, k -> new ArrayList<>());
                if (!saved.contains(name)) saved.add(name);
                String where = withAccount ? accounts.getOrDefault(rows.getLong(2), "Phone") : "Phone", id = key + "\n" + name, known = sources.get(id);
                if (known == null || (!known.equals("Google") && where.equals("Google"))) sources.put(id, where);
            }
        }
    }
    private static boolean reported;
    /** One saved note per run, so a contacts problem shows in Settings → Last problem report instead of silently hiding every name. */
    private static void report(Context context, String stage, RuntimeException error) {
        if (reported) return; reported = true; CrashLog.record(context, "reading phone contacts (" + stage + ")", error);
    }

    private static String source(String accountType) {
        if (accountType == null) return "Phone";
        String type = accountType.toLowerCase(java.util.Locale.ROOT);
        if (type.equals("com.google")) return "Google";
        if (type.contains("sim")) return "SIM";
        return "Phone";
    }
}
