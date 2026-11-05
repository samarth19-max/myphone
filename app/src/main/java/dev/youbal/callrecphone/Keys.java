package dev.youbal.callrecphone;

import android.annotation.SuppressLint;
import android.content.Context;
import android.content.SharedPreferences;
import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;
import android.util.Base64;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.KeyStore;
import java.util.Arrays;
import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;

/**
 * Holds the single 256-bit key that encrypts every recording. The key is wrapped with a
 * hardware-backed Android Keystore key when the device supports it; if Keystore is missing or
 * broken the raw key is kept in app-private storage instead (still protected by the app sandbox
 * and Android's file-based encryption). Either way nothing leaves the phone and no network is used.
 *
 * <p>A canary — a fixed string sealed with the master key — is checked on every access so a lost
 * or changed key is detected before we ever try to decrypt a recording. The recovery key lets the
 * user restore access after a Keystore reset; it is generated and entered entirely offline.
 */
public final class Keys {
    /** The recorded key can no longer be recovered. The caller must not silently make a new one. */
    public static final class Unavailable extends Exception {
        Unavailable(String message, Throwable cause) { super(message, cause); }
        Unavailable(String message) { super(message); }
    }

    private static final String PREFS = "vault_keys";
    private static final String ALIAS = "callrec_recordings_v1";
    private static final String KEYSTORE = "AndroidKeyStore";
    private static final String MODE = "mode";
    private static final String BLOB = "blob";
    private static final String CANARY = "canary";
    private static final String MODE_KEYSTORE = "keystore";
    private static final String MODE_SOFTWARE = "software";
    private static final String RECOVERY_PREFIX = "CRKEY1:";
    private static final byte[] AAD_CANARY = "dev.youbal.callrecphone/canary/v1".getBytes(StandardCharsets.UTF_8);
    private static final byte[] CANARY_PLAIN = "callrec-canary-v1".getBytes(StandardCharsets.UTF_8);

    private Keys() { }

    private static SharedPreferences prefs(Context c) {
        return c.getApplicationContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    /** The verified master key, or {@link Unavailable} when the key cannot be recovered. */
    public static SecretKey master(Context c) throws Unavailable {
        SharedPreferences p = prefs(c);
        String mode = p.getString(MODE, null), blob64 = p.getString(BLOB, null), canary64 = p.getString(CANARY, null);
        if (mode == null || blob64 == null || canary64 == null) return create(c);
        try {
            byte[] blob = Base64.decode(blob64, Base64.DEFAULT);
            byte[] canary = Base64.decode(canary64, Base64.DEFAULT);
            byte[] raw = MODE_KEYSTORE.equals(mode) ? unwrapKeystoreBlob(existingKeystoreKey(), blob) : blob;
            SecretKey key = Aes.key(raw);
            if (!Arrays.equals(Aes.open(key, canary, AAD_CANARY), CANARY_PLAIN))
                throw new Unavailable("The encryption key no longer matches your recordings.");
            return key;
        } catch (Unavailable e) {
            throw e;
        } catch (Exception e) {
            throw new Unavailable("The encryption key is unavailable. Restore it in Settings → Recovery key.", e);
        }
    }

    private static SecretKey create(Context c) throws Unavailable {
        SecretKey key = Aes.key(Aes.randomKey());
        byte[] canary;
        try { canary = Aes.seal(key, CANARY_PLAIN, AAD_CANARY); }
        catch (Exception e) { throw new Unavailable("Could not initialise encryption on this device.", e); }
        store(c, key, canary);
        return key;
    }

    /** Wraps the master key with the current Keystore key, or falls back to private storage. */
    private static void store(Context c, SecretKey key, byte[] canary) {
        byte[] raw = key.getEncoded();
        try {
            commit(c, MODE_KEYSTORE, keystoreWrap(keystoreKey(), raw), canary);
        } catch (Exception e) {
            // App-private storage is still protected by the app sandbox and file-based encryption.
            android.util.Log.w("CallrecKeys", "Keystore key wrapping unavailable; using app-private storage", e);
            commit(c, MODE_SOFTWARE, raw, canary);
        }
    }

    /**
     * Android Keystore keys are created with randomized encryption required, so they generate
     * their own IV and refuse a caller-supplied one ("Caller-provided IV not permitted"). Let the
     * keystore choose the IV, read it back with getIV(), and store [ivLen][iv][ciphertext+tag].
     */
    private static byte[] keystoreWrap(SecretKey kek, byte[] raw) throws GeneralSecurityException {
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.ENCRYPT_MODE, kek);
        byte[] body = cipher.doFinal(raw);
        byte[] iv = cipher.getIV();
        if (iv == null || iv.length == 0 || iv.length > 255) throw new GeneralSecurityException("Keystore returned an unusable IV");
        byte[] out = new byte[1 + iv.length + body.length];
        out[0] = (byte) iv.length;
        System.arraycopy(iv, 0, out, 1, iv.length);
        System.arraycopy(body, 0, out, 1 + iv.length, body.length);
        return out;
    }

    private static byte[] unwrapKeystoreBlob(SecretKey kek, byte[] blob) throws GeneralSecurityException {
        try { return keystoreUnwrap(kek, blob); }
        catch (GeneralSecurityException first) {
            // Tolerate a blob written by an early build that used Aes.seal (12-byte nonce prefix).
            try { return Aes.open(kek, blob, null); }
            catch (GeneralSecurityException second) { throw first; }
        }
    }

    private static byte[] keystoreUnwrap(SecretKey kek, byte[] blob) throws GeneralSecurityException {
        if (blob == null || blob.length < 2) throw new GeneralSecurityException("Wrapped key is truncated");
        int ivLength = blob[0] & 0xff;
        if (ivLength == 0 || 1 + ivLength >= blob.length) throw new GeneralSecurityException("Wrapped key is truncated");
        byte[] iv = Arrays.copyOfRange(blob, 1, 1 + ivLength);
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.DECRYPT_MODE, kek, new GCMParameterSpec(128, iv));
        return cipher.doFinal(blob, 1 + ivLength, blob.length - 1 - ivLength);
    }

    @SuppressLint("ApplySharedPref") // The key must be durable before the first recording is encrypted.
    private static void commit(Context c, String mode, byte[] blob, byte[] canary) {
        // commit() (not apply()): the key must be on disk before the first recording is encrypted.
        prefs(c).edit()
            .putString(MODE, mode)
            .putString(BLOB, Base64.encodeToString(blob, Base64.NO_WRAP))
            .putString(CANARY, Base64.encodeToString(canary, Base64.NO_WRAP))
            .commit();
    }

    /** Returns the existing Keystore key; never generates a replacement (that would strand old files). */
    private static SecretKey existingKeystoreKey() throws GeneralSecurityException, java.io.IOException {
        KeyStore ks = KeyStore.getInstance(KEYSTORE);
        ks.load(null);
        KeyStore.Entry entry = ks.getEntry(ALIAS, null);
        if (entry instanceof KeyStore.SecretKeyEntry) return ((KeyStore.SecretKeyEntry) entry).getSecretKey();
        throw new GeneralSecurityException("Android Keystore no longer holds the recordings key");
    }

    /** Returns the Keystore key, generating it on first use. */
    private static SecretKey keystoreKey() throws GeneralSecurityException, java.io.IOException {
        KeyStore ks = KeyStore.getInstance(KEYSTORE);
        ks.load(null);
        KeyStore.Entry entry = ks.getEntry(ALIAS, null);
        if (entry instanceof KeyStore.SecretKeyEntry) return ((KeyStore.SecretKeyEntry) entry).getSecretKey();
        KeyGenParameterSpec spec = new KeyGenParameterSpec.Builder(ALIAS,
                KeyProperties.PURPOSE_ENCRYPT | KeyProperties.PURPOSE_DECRYPT)
            .setKeySize(256)
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setRandomizedEncryptionRequired(true)
            .setUserAuthenticationRequired(false)
            .setInvalidatedByBiometricEnrollment(false)
            .build();
        KeyGenerator generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, KEYSTORE);
        generator.init(spec);
        return generator.generateKey();
    }

    /** A short description for Settings, or a warning when the key is unavailable. */
    public static String status(Context c) {
        try {
            master(c);
            return MODE_KEYSTORE.equals(prefs(c).getString(MODE, MODE_SOFTWARE))
                ? "AES-256-GCM · key held in the Android Keystore (hardware-backed where available)"
                : "AES-256-GCM · key held in app-private storage (keystore unavailable on this device)";
        } catch (Unavailable e) {
            return "Encryption key unavailable: " + e.getMessage();
        }
    }

    /** An offline recovery key: the master key plus its canary, so a paste can be verified. */
    public static String exportRecovery(Context c) throws Unavailable {
        SecretKey key = master(c);
        byte[] canary;
        try { canary = Base64.decode(prefs(c).getString(CANARY, ""), Base64.DEFAULT); }
        catch (IllegalArgumentException e) { throw new Unavailable("Could not read the key canary.", e); }
        byte[] raw = key.getEncoded();
        byte[] out = new byte[raw.length + canary.length];
        System.arraycopy(raw, 0, out, 0, raw.length);
        System.arraycopy(canary, 0, out, raw.length, canary.length);
        return RECOVERY_PREFIX + Base64.encodeToString(out, Base64.NO_WRAP);
    }

    /** Re-wraps a previously exported key under this installation's Keystore key. */
    public static void importRecovery(Context c, String text) throws Unavailable {
        String value = text == null ? "" : text.trim();
        if (!value.startsWith(RECOVERY_PREFIX)) throw new Unavailable("That is not a Callrec recovery key.");
        byte[] all;
        try { all = Base64.decode(value.substring(RECOVERY_PREFIX.length()), Base64.DEFAULT); }
        catch (IllegalArgumentException e) { throw new Unavailable("That recovery key is damaged.", e); }
        if (all.length < Aes.KEY_BYTES + 28) throw new Unavailable("That recovery key is incomplete.");
        byte[] raw = Arrays.copyOfRange(all, 0, Aes.KEY_BYTES);
        byte[] canary = Arrays.copyOfRange(all, Aes.KEY_BYTES, all.length);
        SecretKey key = Aes.key(raw);
        try {
            if (!Arrays.equals(Aes.open(key, canary, AAD_CANARY), CANARY_PLAIN))
                throw new Unavailable("That recovery key does not match your recordings.");
            store(c, key, canary);
        } catch (GeneralSecurityException e) {
            throw new Unavailable("That recovery key could not be verified.", e);
        }
    }
}
