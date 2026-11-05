package dev.youbal.callrecphone;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.security.GeneralSecurityException;
import java.util.Arrays;
import javax.crypto.SecretKey;
import static org.junit.Assert.*;

/** JVM tests for the pure-Java AES-256-GCM file codec, including corruption and truncation cases. */
public class AesTest {
    @Rule public TemporaryFolder tmp = new TemporaryFolder();

    private final SecretKey key = Aes.key(Aes.randomKey());
    private final SecretKey otherKey = Aes.key(Aes.randomKey());

    private File plain(byte[] data) throws IOException {
        File f = tmp.newFile();
        Files.write(f.toPath(), data);
        return f;
    }
    private File encrypted(byte[] data, long meta) throws Exception {
        File e = tmp.newFile();
        Aes.encrypt(key, plain(data), e, meta);
        return e;
    }
    private static byte[] bytes(File f) throws IOException { return Files.readAllBytes(f.toPath()); }

    @Test public void keyIs256Bits() {
        assertEquals(32, Aes.KEY_BYTES);
        assertEquals(32, Aes.randomKey().length);
        try { Aes.key(new byte[16]); fail("short key must be rejected"); }
        catch (IllegalArgumentException expected) { }
    }

    @Test public void roundTripsSmallLargeAndEmpty() throws Exception {
        for (int size : new int[]{0, 1, 40, 64 * 1024 + 7, 1024 * 1024}) {
            byte[] data = new byte[size];
            for (int i = 0; i < size; i++) data[i] = (byte) (i * 31 + 7);
            File enc = encrypted(data, size * 1000L);
            File out = tmp.newFile();
            Aes.Header h = Aes.decrypt(key, enc, out);
            assertArrayEquals("size " + size, data, bytes(out));
            assertEquals(size, h.originalSize);
            assertEquals(size * 1000L, h.meta);
            assertTrue(Aes.isEncrypted(enc));
            assertFalse(Aes.isEncrypted(out));
        }
    }

    @Test public void headerIsReadableWithoutTheKey() throws Exception {
        File enc = encrypted("call audio".getBytes(StandardCharsets.UTF_8), 42000L);
        Aes.Header h = Aes.readHeader(enc);
        assertEquals(10, h.originalSize);
        assertEquals(42000L, h.meta);
        assertEquals(Aes.HEADER, 40);
    }

    @Test public void eachEncryptionUsesAFreshNonce() throws Exception {
        byte[] data = "same call".getBytes(StandardCharsets.UTF_8);
        File a = encrypted(data, 0), b = encrypted(data, 0);
        assertFalse(Arrays.equals(bytes(a), bytes(b)));
        assertEquals(bytes(a).length, bytes(b).length);
    }

    @Test public void tamperedCiphertextIsRejectedAndDestinationUntouched() throws Exception {
        byte[] data = "important call".getBytes(StandardCharsets.UTF_8);
        File enc = encrypted(data, 0);
        byte[] raw = bytes(enc);
        raw[raw.length - 1] ^= 0x01;                 // flip a tag byte
        Files.write(enc.toPath(), raw);
        File out = tmp.newFile();
        Files.write(out.toPath(), "keep me".getBytes(StandardCharsets.UTF_8));
        try { Aes.decrypt(key, enc, out); fail("tampering must be detected"); }
        catch (GeneralSecurityException | IOException expected) { }
        assertEquals("keep me", new String(bytes(out), StandardCharsets.UTF_8));
        assertFalse(new File(out.getAbsolutePath() + ".dec-tmp").exists());
    }

    @Test public void tamperedHeaderIsRejected() throws Exception {
        File enc = encrypted("call".getBytes(StandardCharsets.UTF_8), 5000L);
        byte[] raw = bytes(enc);
        raw[28] ^= 0x01;                              // change the authenticated metadata
        Files.write(enc.toPath(), raw);
        try { Aes.decrypt(key, enc, tmp.newFile()); fail("header tampering must be detected"); }
        catch (GeneralSecurityException | IOException expected) { }
    }

    @Test public void truncatedCiphertextIsRejected() throws Exception {
        File enc = encrypted("a longer call".getBytes(StandardCharsets.UTF_8), 0);
        byte[] raw = bytes(enc);
        Files.write(enc.toPath(), Arrays.copyOf(raw, raw.length - 5));
        try { Aes.decrypt(key, enc, tmp.newFile()); fail("truncation must be detected"); }
        catch (GeneralSecurityException | IOException expected) { }
    }

    @Test public void wrongKeyCannotDecrypt() throws Exception {
        File enc = encrypted("private".getBytes(StandardCharsets.UTF_8), 0);
        try { Aes.decrypt(otherKey, enc, tmp.newFile()); fail("wrong key must fail"); }
        catch (GeneralSecurityException | IOException expected) { }
    }

    @Test public void truncatedHeaderIsRejected() throws Exception {
        File f = tmp.newFile();
        Files.write(f.toPath(), new byte[]{ 'C', 'R', 'P', '1', 1 });
        try { Aes.readHeader(f); fail("short header must fail"); }
        catch (IOException expected) { }
        assertFalse(Aes.isEncrypted(f));
    }

    @Test public void plainFileIsNotMistakenForEncrypted() throws Exception {
        File f = plain(new byte[]{ 0, 0, 0, 0x18, 'f', 't', 'y', 'p' });   // MP4 box header
        assertFalse(Aes.isEncrypted(f));
        try { Aes.readHeader(f); fail("not our format"); }
        catch (IOException expected) { }
    }

    @Test public void sealedFileIsRecognisedAsARecordingAndDecrypts() throws Exception {
        // Mirrors the real pipeline: the recorder writes in_+91..._stamp.m4a, the vault seals it
        // as .m4a.enc, the list parses the name, and sharing decrypts back to the original audio.
        byte[] audio = "fake m4a call audio".getBytes(StandardCharsets.UTF_8);
        File plainRec = new File(tmp.getRoot(), "in_+919876543210_20261105-113000.m4a");
        Files.write(plainRec.toPath(), audio);
        File sealed = new File(tmp.getRoot(), "in_+919876543210_20261105-113000.m4a.enc");

        Aes.encrypt(key, plainRec, sealed, 93000L);

        Vault.Rec rec = Vault.Rec.parse(sealed);
        assertNotNull(rec);
        assertTrue(rec.encrypted);
        assertEquals("in", rec.direction);
        assertEquals("+919876543210", rec.number);
        assertEquals("in_+919876543210_20261105-113000.m4a", rec.displayName());
        Aes.Header header = Aes.readHeader(sealed);
        assertEquals(audio.length, header.originalSize);
        assertEquals(93000L, header.meta);

        File shared = new File(tmp.getRoot(), rec.displayName() + ".shared.m4a");
        Aes.decrypt(key, sealed, shared);
        assertArrayEquals(audio, bytes(shared));
        assertFalse(Aes.isEncrypted(shared));
    }

    @Test public void sealAndOpenRoundTrip() throws Exception {
        byte[] aad = "context".getBytes(StandardCharsets.UTF_8);
        byte[] blob = Aes.seal(key, "secret".getBytes(StandardCharsets.UTF_8), aad);
        assertArrayEquals("secret".getBytes(StandardCharsets.UTF_8), Aes.open(key, blob, aad));
        byte[] bad = blob.clone();
        bad[bad.length - 1] ^= 0x01;
        try { Aes.open(key, bad, aad); fail("tamper must be detected"); }
        catch (GeneralSecurityException expected) { }
        try { Aes.open(otherKey, blob, aad); fail("wrong key must fail"); }
        catch (GeneralSecurityException expected) { }
    }

    @Test public void sealAndOpenWithoutAadForKeystoreWrapping() throws Exception {
        // Keys.java wraps the master key with a Keystore key that rejects updateAAD().
        byte[] secret = Aes.randomKey();
        byte[] blob = Aes.seal(key, secret, null);
        assertArrayEquals(secret, Aes.open(key, blob, null));
        byte[] bad = blob.clone();
        bad[0] ^= 0x01;
        try { Aes.open(key, bad, null); fail("tamper must be detected"); }
        catch (GeneralSecurityException expected) { }
    }
}
