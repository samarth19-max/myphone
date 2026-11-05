package dev.youbal.callrecphone;

import android.app.Application;
import android.os.Handler;
import android.os.Looper;
import java.io.File;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Process-wide state: settings, notes, and the names saved in the phone's contacts. */
public final class PhoneApp extends Application {
    final Handler main = new Handler(Looper.getMainLooper());
    final ExecutorService io = Executors.newSingleThreadExecutor();
    Prefs prefs;
    Notes notes;
    /** Run on the main thread when a recording has been saved, so the Recordings list can refresh. */
    final List<Runnable> recordingListeners = new ArrayList<>();

    /** normalised number -> every name saved for it; number + newline + name -> Google / Phone / SIM. */
    private volatile Map<String, List<String>> names = Map.of();
    private volatile Map<String, String> sources = Map.of();
    private volatile int namesVersion = 1;
    private volatile boolean namesRequested;
    private volatile List<Person> people = List.of();
    private volatile int peopleVersion = -1;

    static PhoneApp get(android.content.Context context) { return (PhoneApp) context.getApplicationContext(); }

    @Override public void onCreate() {
        super.onCreate();
        CrashLog.install(this); PhoneNumbers.init(this);
        prefs = new Prefs(this); notes = new Notes(this);
        io.execute(() -> {
            try { Keys.master(this); } catch (Keys.Unavailable ignored) { /* Reported in Settings. */ }
            Vault.cleanupTemp(this);
            // Every launch: encrypt any plain recording left behind by a crash or by an older
            // version, and finish any queued encryption that was interrupted.
            Vault.migratePlain(this);
            Vault.deleteOlderThan(this, prefs.keepDays(), System.currentTimeMillis());
            Vault.notifyChanged(this);
        });
    }

    /** The app's own folder on shared storage. The recorder (which may run as the shell user) can write here; other apps cannot read it. */
    File recordingsDir() {
        File dir = getExternalFilesDir("Recordings");
        return dir != null ? dir : new File(getFilesDir(), "Recordings");
    }

    // ---- saved names ---------------------------------------------------------------------------------------------
    /** Reads the contacts again, then runs done on the main thread. */
    void reloadNames(Runnable done) {
        io.execute(() -> {
            DeviceContacts.Loaded loaded = DeviceContacts.load(this);
            names = loaded.names(); sources = loaded.sources(); namesVersion++;
            if (done != null) main.post(done);
        });
    }
    /** Loads the names once if nothing has yet, e.g. when a call arrives before the app was opened. */
    void ensureNames(Runnable changed) {
        if (namesRequested || !names.isEmpty()) return;
        namesRequested = true; reloadNames(changed);
    }
    boolean hasNames() { return !names.isEmpty(); }

    /** The name the phone has for this number, or "". */
    String phoneName(String number) {
        List<String> saved = names.get(PhoneNumbers.normalize(number));
        return saved == null || saved.isEmpty() ? "" : saved.get(0);
    }
    /** "Google: Mummy · Phone: Mom" for this number, or "". */
    String savedLine(String number) {
        String key = PhoneNumbers.normalize(number); List<String> saved = names.get(key);
        if (saved == null) return "";
        StringBuilder out = new StringBuilder();
        for (String name : saved) {
            String where = sources.get(key + "\n" + name);
            if (out.length() > 0) out.append(" · ");
            out.append(where == null ? "Phone" : where).append(": ").append(name);
        }
        return out.toString();
    }
    String sourceOf(String number) {
        String key = PhoneNumbers.normalize(number); String name = phoneName(number);
        String where = name.isEmpty() ? null : sources.get(key + "\n" + name);
        return where == null ? "" : where;
    }

    /** Everyone with a number, by name. Rebuilt only when the names are reloaded. */
    List<Person> people() {
        int version = namesVersion;
        if (version == peopleVersion) return people;
        Map<String, List<String>> byName = new HashMap<>(); Map<String, String> where = new HashMap<>();
        for (Map.Entry<String, List<String>> entry : names.entrySet()) {
            for (String name : entry.getValue()) {
                byName.computeIfAbsent(name, k -> new ArrayList<>()).add(entry.getKey());
                String source = sources.get(entry.getKey() + "\n" + name);
                if (source != null && (where.get(name) == null || source.equals("Google"))) where.put(name, source);
            }
        }
        List<Person> built = new ArrayList<>();
        for (Map.Entry<String, List<String>> entry : byName.entrySet()) built.add(new Person(entry.getKey(), where.getOrDefault(entry.getKey(), "Phone"), entry.getValue()));
        built.sort((a, b) -> a.name.compareToIgnoreCase(b.name));
        people = built; peopleVersion = version; return built;
    }
}
