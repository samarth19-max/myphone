package dev.youbal.callrecphone;

import android.content.Context;
import android.os.Build;
import java.io.File;
import java.io.FileOutputStream;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/** Keeps the last crash or call-screen failure on the phone so it can be read in Settings and pasted to support. Never uploaded. */
final class CrashLog {
    private CrashLog() {}
    private static File file(Context c) { return new File(c.getApplicationContext().getFilesDir(), "last-problem.txt"); }

    static void install(Context app) {
        Thread.UncaughtExceptionHandler previous = Thread.getDefaultUncaughtExceptionHandler();
        Thread.setDefaultUncaughtExceptionHandler((thread, error) -> { record(app, "crash on " + thread.getName(), error); if (previous != null) previous.uncaughtException(thread, error); });
    }

    static void record(Context c, String where, Throwable error) {
        try {
            StringWriter trace = new StringWriter(); error.printStackTrace(new PrintWriter(trace));
            String text = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.ROOT).format(new Date()) + " · " + where + "\nCallrec Phone 0.0.1 · Android " + Build.VERSION.RELEASE + " (API " + Build.VERSION.SDK_INT + ") · " + Build.MANUFACTURER + " " + Build.MODEL + "\n\n" + trace;
            try (FileOutputStream out = new FileOutputStream(file(c))) { out.write(text.getBytes(StandardCharsets.UTF_8)); }
        } catch (Throwable ignored) { /* Diagnostics must never cause a second failure. */ }
    }

    /** The last report, or an empty string. */
    static String read(Context c) {
        try { File f = file(c); return f.isFile() ? new String(java.nio.file.Files.readAllBytes(f.toPath()), StandardCharsets.UTF_8) : ""; } catch (Throwable e) { return ""; }
    }
    static void clear(Context c) { file(c).delete(); }
}
