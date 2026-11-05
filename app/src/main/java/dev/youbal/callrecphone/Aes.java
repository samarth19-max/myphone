package dev.youbal.callrecphone;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.Arrays;
import javax.crypto.Cipher;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;

/**
 * AES-256-GCM file codec. Pure Java (no Android classes) so the round trip, corruption and
 * truncation cases can be unit-tested on the JVM.
 *
 * <p>File layout, all authenticated as additional data:
 * <pre>
 *   0  4   "CRP1" magic
 *   4  1   version (1)
 *   5  1   flags (0)
 *   6  2   reserved (0)
 *   8  12  random GCM nonce
 *   20 8   plaintext length, big-endian
 *   28 8   app metadata (recording length in ms), big-endian
 *   36 4   reserved (0)
 *   40 ..  ciphertext followed by the 16-byte GCM tag
 * </pre>
 * A fresh nonce is generated for every file. The whole 40-byte header plus a fixed domain string
 * are fed as AAD, so changing the magic, length, metadata or nonce invalidates the tag.
 */
public final class Aes {
    public static final int KEY_BYTES = 32;                 // 256 bits
    static final byte[] MAGIC = {'C', 'R', 'P', '1'};
    static final int HEADER = 40;
    private static final byte VERSION = 1;
    private static final int NONCE = 12;
    private static final int TAG_BITS = 128;
    private static final int CHUNK = 64 * 1024;
    private static final byte[] DOMAIN = "dev.youbal.callrecphone/recordings/v1".getBytes(StandardCharsets.UTF_8);
    private static final SecureRandom RANDOM = new SecureRandom();

    /** Authenticated header values that can be read without the key (e.g. to show a duration). */
    public static final class Header {
        public final long originalSize;
        public final long meta;
        Header(long originalSize, long meta) { this.originalSize = originalSize; this.meta = meta; }
    }

    private Aes() { }

    public static SecretKeySpec key(byte[] raw) {
        if (raw == null || raw.length != KEY_BYTES) throw new IllegalArgumentException("AES-256 needs exactly 32 key bytes");
        return new SecretKeySpec(raw, "AES");
    }

    public static byte[] randomKey() { byte[] raw = new byte[KEY_BYTES]; RANDOM.nextBytes(raw); return raw; }

    /** True only when the file starts with our magic and is long enough to hold a header. */
    public static boolean isEncrypted(File file) throws IOException {
        if (!file.isFile() || file.length() < HEADER) return false;
        try (InputStream in = new FileInputStream(file)) {
            byte[] magic = new byte[MAGIC.length];
            return in.read(magic) == MAGIC.length && Arrays.equals(magic, MAGIC);
        }
    }

    /** Reads and validates the header of an encrypted file. Does not verify the GCM tag. */
    public static Header readHeader(File file) throws IOException {
        if (!file.isFile() || file.length() < HEADER) throw new IOException("Encrypted file is truncated");
        byte[] h = new byte[HEADER];
        try (InputStream in = new FileInputStream(file)) {
            if (in.read(h) != HEADER) throw new IOException("Encrypted file is truncated");
        }
        return validate(h);
    }

    private static Header validate(byte[] h) throws IOException {
        if (h.length != HEADER) throw new IOException("Encrypted file is truncated");
        for (int i = 0; i < MAGIC.length; i++) if (h[i] != MAGIC[i]) throw new IOException("Not an encrypted recording");
        if (h[4] != VERSION) throw new IOException("Unsupported encrypted recording version " + (h[4] & 0xff));
        ByteBuffer b = ByteBuffer.wrap(h).order(ByteOrder.BIG_ENDIAN);
        long originalSize = b.getLong(20), meta = b.getLong(28);
        if (originalSize < 0) throw new IOException("Invalid encrypted recording length");
        return new Header(originalSize, meta);
    }

    private static byte[] header(long originalSize, long meta) {
        byte[] h = new byte[HEADER];
        System.arraycopy(MAGIC, 0, h, 0, MAGIC.length);
        h[4] = VERSION;
        byte[] nonce = new byte[NONCE];
        RANDOM.nextBytes(nonce);
        System.arraycopy(nonce, 0, h, 8, NONCE);
        ByteBuffer b = ByteBuffer.wrap(h).order(ByteOrder.BIG_ENDIAN);
        b.putLong(20, originalSize);
        b.putLong(28, meta);
        return h;
    }

    private static Cipher cipher(SecretKey key, int mode, byte[] nonce, byte[] aad) throws GeneralSecurityException {
        if (!"AES".equalsIgnoreCase(key.getAlgorithm())) throw new GeneralSecurityException("Not an AES key");
        Cipher c = Cipher.getInstance("AES/GCM/NoPadding");
        c.init(mode, key, new GCMParameterSpec(TAG_BITS, nonce));
        // A null/empty aad skips updateAAD entirely: several Android Keystore implementations
        // reject AAD on AES/GCM keys, so key wrapping uses this form.
        if (aad != null && aad.length > 0) c.updateAAD(aad);
        return c;
    }

    private static byte[] nonceOf(byte[] header) { return Arrays.copyOfRange(header, 8, 8 + NONCE); }

    /**
     * Streams {@code plain} into {@code dest} as an authenticated ciphertext. Writes through a
     * temporary file and renames atomically, so a crash never leaves a half-encrypted recording.
     * {@code meta} is an app-defined value carried in the authenticated header.
     */
    public static void encrypt(SecretKey key, File plain, File dest, long meta) throws GeneralSecurityException, IOException {
        if (!plain.isFile()) throw new IOException("Recording to encrypt is missing");
        long size = plain.length();
        byte[] h = header(size, meta);
        Cipher c = cipher(key, Cipher.ENCRYPT_MODE, nonceOf(h), aad(h));
        File tmp = sibling(dest, ".enc-tmp");
        try {
            try (InputStream in = new FileInputStream(plain); OutputStream out = new FileOutputStream(tmp)) {
                out.write(h);
                pump(in, out, c);
            }
            replace(tmp, dest);
        } finally {
            if (tmp.exists()) tmp.delete();
        }
    }

    /**
     * Verifies the tag and writes the plaintext to {@code dest}. On any failure the destination is
     * left untouched and no partial plaintext survives. Returns the authenticated header values.
     */
    public static Header decrypt(SecretKey key, File enc, File dest) throws GeneralSecurityException, IOException {
        if (!enc.isFile() || enc.length() < HEADER) throw new IOException("Encrypted file is truncated");
        File tmp = sibling(dest, ".dec-tmp");
        try {
            long originalSize, meta, written;
            try (InputStream in = new FileInputStream(enc); OutputStream out = new FileOutputStream(tmp)) {
                byte[] h = new byte[HEADER];
                if (in.read(h) != HEADER) throw new IOException("Encrypted file is truncated");
                Header header = validate(h);
                originalSize = header.originalSize;
                meta = header.meta;
                Cipher c = cipher(key, Cipher.DECRYPT_MODE, nonceOf(h), aad(h));
                // pump() calls doFinal(), which verifies the tag before 'out' is closed.
                written = pump(in, out, c);
            }
            if (written != originalSize) throw new IOException("Encrypted recording length mismatch");
            replace(tmp, dest);
            return new Header(originalSize, meta);
        } finally {
            if (tmp.exists()) tmp.delete();
        }
    }

    private static byte[] aad(byte[] header) {
        byte[] out = Arrays.copyOf(DOMAIN, DOMAIN.length + header.length);
        System.arraycopy(header, 0, out, DOMAIN.length, header.length);
        return out;
    }

    private static long pump(InputStream in, OutputStream out, Cipher c) throws GeneralSecurityException, IOException {
        byte[] buf = new byte[CHUNK];
        byte[] last = null;
        long written = 0;
        int n;
        while ((n = in.read(buf)) != -1) {
            byte[] step = c.update(buf, 0, n);
            if (step != null && step.length > 0) { out.write(step); written += step.length; }
        }
        last = c.doFinal();
        if (last != null && last.length > 0) { out.write(last); written += last.length; }
        return written;
    }

    /** Small authenticated envelope: nonce (12) || ciphertext || tag. Used for key wrapping and canaries. */
    public static byte[] seal(SecretKey key, byte[] plain, byte[] aad) throws GeneralSecurityException {
        byte[] nonce = new byte[NONCE];
        RANDOM.nextBytes(nonce);
        Cipher c = cipher(key, Cipher.ENCRYPT_MODE, nonce, aad);
        byte[] body = c.doFinal(plain);
        byte[] out = new byte[NONCE + body.length];
        System.arraycopy(nonce, 0, out, 0, NONCE);
        System.arraycopy(body, 0, out, NONCE, body.length);
        return out;
    }

    public static byte[] open(SecretKey key, byte[] blob, byte[] aad) throws GeneralSecurityException {
        if (blob == null || blob.length <= NONCE) throw new GeneralSecurityException("Sealed blob is truncated");
        byte[] nonce = Arrays.copyOfRange(blob, 0, NONCE);
        Cipher c = cipher(key, Cipher.DECRYPT_MODE, nonce, aad);
        return c.doFinal(blob, NONCE, blob.length - NONCE);
    }

    private static File sibling(File dest, String suffix) {
        File parent = dest.getAbsoluteFile().getParentFile();
        return new File(parent, dest.getName() + suffix);
    }

    private static void replace(File tmp, File dest) throws IOException {
        File parent = dest.getAbsoluteFile().getParentFile();
        if (parent != null) parent.mkdirs();
        try {
            Files.move(tmp.toPath(), dest.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } catch (java.nio.file.AtomicMoveNotSupportedException e) {
            Files.move(tmp.toPath(), dest.toPath(), StandardCopyOption.REPLACE_EXISTING);
        }
    }
}
