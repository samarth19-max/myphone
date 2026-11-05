package dev.youbal.callrecphone;

import android.content.ContentValues;
import android.content.Context;
import android.net.Uri;
import android.os.Environment;
import android.provider.MediaStore;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.RandomAccessFile;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import javax.crypto.SecretKey;

/**
 * Where recordings live and how they are turned into playable, shareable audio.
 *
 * <ul>
 *   <li>The recorder (a separate shell process) can only write to the app's folder on shared
 *       storage, so a finished call lands there as a plain .m4a for a moment.</li>
 *   <li>With encryption on, {@link #processPending} immediately seals it with AES-256-GCM into
 *       the app's <em>private internal</em> Vault folder, then wipes the plain file. The work is
 *       queued in a small file first, so a crash mid-encryption is retried at next launch.</li>
 *   <li>Playback, sharing and "save to phone" always go through {@link #plainFile}, which
 *       decrypts to a temporary .m4a in the cache. WhatsApp (or any other app) therefore always
 *       receives ordinary audio, never ciphertext.</li>
 * </ul>
 * Everything here is local file I/O plus the Android Keystore. No network is involved.
 */
public final class Vault {
    public static final String MIME = "audio/mp4";
    private static final String PUBLIC_DIR = Environment.DIRECTORY_MUSIC + "/Callrec Phone";
    /** Decrypted cache copies are short-lived; two hours is plenty for any receiving app to read them. */
    private static final long TEMP_TTL = 2L * 60 * 60 * 1000;
    private static final long MIGRATE_AGE = 5L * 60 * 1000;
    private static final Object LOCK = new Object();
    /** Set by the in-call service so startup migration never touches a file that is still being written. */
    private static volatile boolean recordingActive = false;
    static void setRecordingActive(boolean active) { recordingActive = active; }

    /** One recording, encrypted or plain. Metadata comes from the library's file name. */
    public static final class Rec {
        private static final Pattern NAME =
            Pattern.compile("^(in|out|call)_([A-Za-z0-9+]+)_(\\d{8}-\\d{6})\\.m4a(\\.enc)?$");

        public final File file;
        public final String direction, number;
        public final long startedAt;
        /** Plaintext byte count, even for an encrypted file, so the UI shows the real size. */
        public final long sizeBytes;
        /** Recording length from the authenticated header; 0 when unknown. */
        public final long durationMs;
        public final boolean encrypted;
        /** Encrypted file whose header cannot be read (truncated or not ours). Still listed so it can be deleted. */
        public final boolean broken;

        private Rec(File file, String direction, String number, long startedAt, long sizeBytes,
                    long durationMs, boolean encrypted, boolean broken) {
            this.file = file; this.direction = direction; this.number = number; this.startedAt = startedAt;
            this.sizeBytes = sizeBytes; this.durationMs = durationMs; this.encrypted = encrypted; this.broken = broken;
        }

        static Rec parse(File f) {
            Matcher m = NAME.matcher(f.getName());
            if (!m.matches()) return null;
            try {
                SimpleDateFormat fmt = new SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US);
                fmt.setLenient(false);
                long when = fmt.parse(m.group(3)).getTime();
                return new Rec(f, m.group(1), m.group(2), when, f.length(), 0, m.group(4) != null, false);
            } catch (ParseException e) {
                return null;
            }
        }

        Rec withHeader(long size, long duration) {
            return new Rec(file, direction, number, startedAt, size, duration, true, false);
        }
        Rec broken() {
            return new Rec(file, direction, number, startedAt, file.length(), 0, true, true);
        }
        /** The name the audio has when decrypted / saved to the phone. */
        public String displayName() {
            String n = file.getName();
            return encrypted ? n.substring(0, n.length() - 4) : n;
        }
    }

    private Vault() { }

    // ---- directories ----------------------------------------------------------------------------------------------
    static File vaultDir(Context c) { return new File(c.getFilesDir(), "Vault"); }
    static File plainDir(Context c) { return PhoneApp.get(c).recordingsDir(); }
    static File shareDir(Context c) { return new File(c.getCacheDir(), "shared"); }
    private static File pendingFile(Context c) { return new File(vaultDir(c), "pending.txt"); }

    // ---- listing ----------------------------------------------------------------------------------------------------
    /** Newest first. Both vault (.m4a.enc) and plain (.m4a) recordings are included. */
    static List<Rec> list(Context c) {
        List<Rec> found = new ArrayList<>();
        collect(found, vaultDir(c));
        collect(found, plainDir(c));
        // A crash can leave both copies of the same call. Prefer the sealed one; the plain leftover
        // is wiped as soon as pending work is processed.
        Map<String, Rec> byName = new LinkedHashMap<>();
        for (Rec r : found) {
            Rec prev = byName.get(r.displayName());
            if (prev == null || (r.encrypted && !prev.encrypted)) byName.put(r.displayName(), r);
        }
        List<Rec> out = new ArrayList<>(byName.values());
        out.sort((a, b) -> Long.compare(b.startedAt, a.startedAt));
        return out;
    }

    private static void collect(List<Rec> out, File dir) {
        File[] files = dir.listFiles();
        if (files == null) return;
        for (File f : files) {
            if (!f.isFile()) continue;
            Rec r = Rec.parse(f);
            if (r == null) continue;
            if (r.encrypted) {
                try {
                    Aes.Header h = Aes.readHeader(f);
                    out.add(r.withHeader(h.originalSize, h.meta));
                } catch (Exception e) {
                    out.add(r.broken());
                }
            } else {
                out.add(r);
            }
        }
    }

    static long totalBytes(Context c) {
        long total = 0;
        for (Rec r : list(c)) total += r.sizeBytes;
        return total;
    }

    // ---- encryption lifecycle -----------------------------------------------------------------------------------------
    /**
     * Only recordings inside our own recordings folder may ever be encrypted or wiped. The path
     * arrives from a separate recorder process (or from the durable queue file), so it is untrusted:
     * without this check a forged path would let the app read an arbitrary file into the vault,
     * share its contents, and then delete the original. Canonical paths also defeat symlinks.
     */
    private static boolean safePlainPath(Context c, String path) {
        return safePlainPathAgainst(plainDir(c), path);
    }

    /** Pure form, so the rejection rules can be unit-tested without an Android Context. */
    static boolean safePlainPathAgainst(File dir, String path) {
        if (path == null || path.isEmpty() || path.indexOf('|') >= 0
                || path.indexOf('\n') >= 0 || path.indexOf('\r') >= 0) return false;
        File f = new File(path);
        Rec r = Rec.parse(f);
        if (r == null || r.encrypted) return false;
        try {
            File parent = f.getCanonicalFile().getParentFile();
            return parent != null && parent.equals(dir.getCanonicalFile());
        } catch (IOException e) {
            return false;
        }
    }

    /** Called on the main thread when the recorder finishes: remember the file, then process it off-thread. */
    static void enqueue(Context c, String path, long durationMs) {
        if (!safePlainPath(c, path)) return;
        enqueueAll(c, java.util.Collections.singletonList(durationMs + "|" + path));
    }

    private static void enqueueAll(Context c, List<String> entries) {
        if (!Prefs.of(c).encrypt()) return;
        List<String> safe = new ArrayList<>();
        for (String entry : entries) {
            int bar = entry.indexOf('|');
            if (bar >= 0 && safePlainPath(c, entry.substring(bar + 1))) safe.add(entry);
        }
        if (safe.isEmpty()) return;
        synchronized (LOCK) {
            File pf = pendingFile(c);
            pf.getParentFile().mkdirs();
            List<String> lines = readLines(pf);
            for (String entry : safe) {
                String path = entry.substring(entry.indexOf('|') + 1);
                lines.removeIf(l -> l.endsWith("|" + path));
            }
            lines.addAll(safe);
            writeLines(c, pf, lines);
        }
    }

    /** Retries queued encryptions. Safe to call repeatedly; never deletes a plain file on failure. */
    static void processPending(Context c) {
        synchronized (LOCK) {
            Prefs prefs = Prefs.of(c);
            File pf = pendingFile(c);
            List<String> lines = readLines(pf);
            if (lines.isEmpty()) return;
            if (!prefs.encrypt()) { writeLines(c, pf, new ArrayList<>()); return; }
            SecretKey key;
            try {
                key = Keys.master(c);
            } catch (Keys.Unavailable e) {
                prefs.vaultStatus("Encryption key unavailable: " + e.getMessage() + " Restore it in Settings → Recovery key.");
                return; // keep the queue; the recordings are still safe as plain files
            }
            List<String> remaining = new ArrayList<>();
            for (String line : lines) {
                int bar = line.indexOf('|');
                if (bar < 0) continue;
                long duration = 0;
                try { duration = Long.parseLong(line.substring(0, bar)); } catch (NumberFormatException ignored) { }
                File plain = new File(line.substring(bar + 1));
                // The queue file is on disk and could have been tampered with; re-validate it.
                if (!safePlainPath(c, plain.getAbsolutePath())) continue;
                if (!plain.isFile()) continue; // already gone
                File dest = new File(vaultDir(c), plain.getName() + ".enc");
                try {
                    // A previous run may have sealed this file but failed to wipe the plain copy.
                    // Never re-encrypt over a good vault file (that would drop its stored duration).
                    boolean alreadySealed = false;
                    if (dest.isFile()) {
                        try { Aes.readHeader(dest); alreadySealed = true; }
                        catch (IOException ignored) { dest.delete(); }
                    }
                    if (!alreadySealed) Aes.encrypt(key, plain, dest, duration);
                    secureDelete(plain);
                    if (plain.exists()) { remaining.add(line); continue; } // retry the wipe later
                    prefs.vaultStatus("");
                } catch (Exception e) {
                    prefs.vaultStatus("Could not encrypt a recording: " + e.getMessage());
                    remaining.add(line);
                }
            }
            writeLines(c, pf, remaining);
            // Always refresh: the list may have shown a plain file that has just become a vault file.
            notifyChanged(c);
        }
    }

    /**
     * Brings recordings from an older install (or from a period with encryption off) into the vault.
     * Files touched in the last few minutes are skipped: a call may still be writing to them.
     */
    static void migratePlain(Context c) {
        if (!Prefs.of(c).encrypt()) return;
        // A call may be live right now and still writing its .m4a. Never seal a file mid-call;
        // this runs on every launch, so a skipped sweep is retried next time.
        if (recordingActive) return;
        File[] files = plainDir(c).listFiles();
        if (files == null) return;
        long cutoff = System.currentTimeMillis() - MIGRATE_AGE;
        List<String> entries = new ArrayList<>();
        for (File f : files) {
            if (!f.isFile() || f.lastModified() > cutoff) continue;
            Rec r = Rec.parse(f);
            if (r != null && !r.encrypted) entries.add("0|" + f.getAbsolutePath());
        }
        enqueueAll(c, entries);
        processPending(c);
    }

    // ---- decrypting for use -------------------------------------------------------------------------------------------
    /**
     * A real .m4a that can be played or shared. Encrypted recordings are decrypted into the cache;
     * plain ones are used directly. Returns only complete, tag-verified audio.
     */
    static File plainFile(Context c, Rec r) throws IOException, java.security.GeneralSecurityException, Keys.Unavailable {
        if (!r.encrypted) return r.file;
        if (r.broken) throw new IOException("This encrypted recording is damaged.");
        synchronized (LOCK) {
            Aes.Header h = Aes.readHeader(r.file);
            File dir = shareDir(c);
            dir.mkdirs();
            File dest = new File(dir, r.displayName());
            if (dest.isFile() && dest.length() == h.originalSize && !Aes.isEncrypted(dest)) return dest;
            Aes.decrypt(Keys.master(c), r.file, dest);
            return dest;
        }
    }

    // ---- saving without encryption ------------------------------------------------------------------------------------
    /** Copies a decrypted recording into Music/Callrec Phone so any file manager or player can see it. */
    static String saveToPhone(Context c, Rec r) throws IOException, java.security.GeneralSecurityException, Keys.Unavailable {
        File plain = plainFile(c, r);
        String name = r.displayName();
        android.content.ContentResolver cr = c.getContentResolver();
        Uri uri = null;
        try {
            ContentValues cv = new ContentValues();
            cv.put(MediaStore.Audio.Media.DISPLAY_NAME, name);
            cv.put(MediaStore.Audio.Media.MIME_TYPE, MIME);
            cv.put(MediaStore.Audio.Media.RELATIVE_PATH, PUBLIC_DIR);
            cv.put(MediaStore.Audio.Media.IS_PENDING, 1);
            uri = cr.insert(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, cv);
            if (uri != null) {
                try (InputStream in = new FileInputStream(plain); OutputStream out = cr.openOutputStream(uri)) {
                    if (out == null) throw new IOException("MediaStore would not open the file");
                    copy(in, out);
                }
                ContentValues done = new ContentValues();
                done.put(MediaStore.Audio.Media.IS_PENDING, 0);
                cr.update(uri, done, null, null);
                // MediaStore renames duplicates to "name (1).m4a"; report the name actually used.
                String actual = name;
                try (android.database.Cursor cur = cr.query(uri,
                        new String[]{MediaStore.Audio.Media.DISPLAY_NAME}, null, null, null)) {
                    if (cur != null && cur.moveToFirst()) {
                        String assigned = cur.getString(0);
                        if (assigned != null && !assigned.isEmpty()) actual = assigned;
                    }
                } catch (Exception ignored) { }
                return actual;
            }
        } catch (Exception e) {
            if (uri != null) { try { cr.delete(uri, null, null); } catch (Exception ignored) { } }
            // Some devices reject MediaStore writes; fall back to the app's own folder on the phone.
        }
        File fallbackDir = c.getExternalFilesDir(Environment.DIRECTORY_MUSIC);
        if (fallbackDir == null) throw new IOException("No writable storage for an unencrypted copy");
        fallbackDir.mkdirs();
        File target = unique(fallbackDir, name);
        copy(new FileInputStream(plain), new FileOutputStream(target));
        return target.getAbsolutePath();
    }

    /** Decrypts every recording into Music/Callrec Phone. Returns how many were saved. */
    static int saveAllToPhone(Context c) throws Keys.Unavailable {
        Keys.master(c); // Fail loudly on a lost key instead of reporting a misleading "0 saved".
        int saved = 0, failed = 0;
        for (Rec r : list(c)) {
            try { saveToPhone(c, r); saved++; }
            catch (Exception e) { failed++; }
        }
        Prefs.of(c).vaultStatus(failed == 0 ? "" : failed + " recording(s) could not be saved unencrypted.");
        return saved;
    }

    private static File unique(File dir, String name) {
        File f = new File(dir, name);
        if (!f.exists()) return f;
        String base = name.endsWith(".m4a") ? name.substring(0, name.length() - 4) : name;
        for (int i = 1; ; i++) {
            f = new File(dir, base + " (" + i + ").m4a");
            if (!f.exists()) return f;
        }
    }

    // ---- deletion -----------------------------------------------------------------------------------------------------
    static boolean delete(Context c, Rec r) {
        boolean ok = r.file.delete();
        String plainName = r.displayName();
        File shared = new File(shareDir(c), plainName);
        if (shared.exists()) shared.delete();
        // Clean up the exact temp names Aes/Vault use, so no half-written file is orphaned.
        new File(vaultDir(c), r.file.getName() + ".enc-tmp").delete();
        new File(plainDir(c), r.file.getName() + ".enc-tmp").delete();
        new File(shareDir(c), plainName + ".dec-tmp").delete();
        dropPending(c, r.file.getAbsolutePath());
        return ok;
    }

    /** Drops a recording the library decided was silent, and forgets any queued encryption for it. */
    static void discard(Context c, String path) {
        if (!safePlainPath(c, path)) return; // never delete an arbitrary path
        dropPending(c, path);
        new File(path).delete();
    }

    static int deleteOlderThan(Context c, int days, long now) {
        if (days <= 0) return 0;
        long cutoff = now - days * 24L * 60 * 60 * 1000;
        int deleted = 0;
        for (Rec r : list(c)) if (r.startedAt < cutoff && delete(c, r)) deleted++;
        return deleted;
    }

    /** Removes decrypted cache files that are a day old and any half-written temp files. */
    static void cleanupTemp(Context c) {
        long now = System.currentTimeMillis();
        File shared = shareDir(c);
        for (File dir : new File[]{shared, vaultDir(c), plainDir(c)}) {
            File[] files = dir.listFiles();
            if (files == null) continue;
            for (File f : files) {
                String n = f.getName();
                if (n.endsWith(".enc-tmp") || n.endsWith(".dec-tmp")) { f.delete(); continue; }
                if (dir.equals(shared) && f.isFile() && now - f.lastModified() > TEMP_TTL) f.delete();
            }
        }
    }

    static void notifyChanged(Context c) {
        PhoneApp app = PhoneApp.get(c);
        app.main.post(() -> {
            for (Runnable r : new ArrayList<>(app.recordingListeners)) r.run();
        });
    }

    // ---- helpers ------------------------------------------------------------------------------------------------------
    private static void copy(InputStream in, OutputStream out) throws IOException {
        try (InputStream a = in; OutputStream b = out) {
            byte[] buf = new byte[64 * 1024];
            int n;
            while ((n = a.read(buf)) != -1) b.write(buf, 0, n);
        }
    }

    /** Best-effort wipe before unlinking; flash storage and FBE make this only a mitigation. */
    private static void secureDelete(File f) {
        if (!f.exists()) return;
        try (RandomAccessFile raf = new RandomAccessFile(f, "rw")) {
            long left = raf.length();
            byte[] zeros = new byte[8192];
            raf.seek(0);
            while (left > 0) {
                int n = (int) Math.min(left, zeros.length);
                raf.write(zeros, 0, n);
                left -= n;
            }
            raf.getFD().sync();
        } catch (IOException ignored) { }
        f.delete();
    }

    private static List<String> readLines(File f) {
        try {
            return new ArrayList<>(Files.readAllLines(f.toPath(), StandardCharsets.UTF_8));
        } catch (IOException e) {
            return new ArrayList<>();
        }
    }

    private static void writeLines(Context c, File f, List<String> lines) {
        try {
            f.getParentFile().mkdirs();
            File tmp = new File(f.getParentFile(), f.getName() + ".tmp");
            Files.write(tmp.toPath(), lines, StandardCharsets.UTF_8);
            try {
                Files.move(tmp.toPath(), f.toPath(),
                    java.nio.file.StandardCopyOption.ATOMIC_MOVE, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            } catch (java.nio.file.AtomicMoveNotSupportedException e) {
                Files.move(tmp.toPath(), f.toPath(), java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException e) {
            Prefs.of(c).vaultStatus("Could not save the encryption queue.");
        }
    }

    private static void dropPending(Context c, String path) {
        synchronized (LOCK) {
            File pf = pendingFile(c);
            List<String> lines = readLines(pf);
            lines.removeIf(l -> l.endsWith("|" + path));
            writeLines(c, pf, lines);
        }
    }
}
