package dev.youbal.callrecphone;

import org.junit.Test;
import java.io.File;
import static org.junit.Assert.*;

/** File-name parsing for both the plain and the encrypted form of a recording. */
public class VaultRecTest {

    private static Vault.Rec parse(String name) { return Vault.Rec.parse(new File("/recordings", name)); }

    @Test public void parsesPlainRecording() {
        Vault.Rec r = parse("in_+919876543210_20261105-113000.m4a");
        assertNotNull(r);
        assertEquals("in", r.direction);
        assertEquals("+919876543210", r.number);
        assertFalse(r.encrypted);
        assertFalse(r.broken);
        assertEquals("in_+919876543210_20261105-113000.m4a", r.displayName());
    }

    @Test public void parsesEncryptedRecording() {
        Vault.Rec r = parse("out_12345_20261105-093000.m4a.enc");
        assertNotNull(r);
        assertEquals("out", r.direction);
        assertEquals("12345", r.number);
        assertTrue(r.encrypted);
        assertEquals("out_12345_20261105-093000.m4a", r.displayName());
    }

    @Test public void acceptsUnknownNumber() {
        assertNotNull(parse("call_unknown_20261105-113000.m4a"));
        assertNotNull(parse("call_unknown_20261105-113000.m4a.enc"));
    }

    @Test public void rejectsTempAndUnrelatedFiles() {
        assertNull(parse("pending.txt"));
        assertNull(parse("in_123_20261105-113000.m4a.enc-tmp"));
        assertNull(parse("in_123_20261105-113000.m4a.dec-tmp"));
        assertNull(parse("voice-note.mp3"));
        assertNull(parse("photo.m4a"));
        assertNull(parse("in_123_20261105-113000.m4a.enc.enc"));
    }

    @Test public void rejectsInvalidTimestamps() {
        assertNull(parse("in_123_20261105-999999.m4a"));       // impossible hour
        assertNull(parse("in_123_20261340-113000.m4a"));       // impossible month/day
        assertNull(parse("in_123_20261105-1130.m4a"));         // wrong width
        assertNull(parse("xx_123_20261105-113000.m4a"));       // unknown direction
    }

    @Test public void timestampIsParsedInLocalTime() {
        Vault.Rec a = parse("in_1_20261105-113000.m4a");
        Vault.Rec b = parse("in_1_20261105-113001.m4a");
        assertNotNull(a);
        assertNotNull(b);
        assertEquals(1000L, b.startedAt - a.startedAt);
    }

    @Test public void brokenMarkerKeepsFileListable() {
        Vault.Rec r = parse("in_1_20261105-113000.m4a.enc").broken();
        assertTrue(r.broken);
        assertTrue(r.encrypted);
        assertEquals("in_1_20261105-113000.m4a", r.displayName());
    }

    @Test public void pathGuardAcceptsOnlyRealRecordingsInsideOurFolder() throws Exception {
        java.nio.file.Path dir = java.nio.file.Files.createTempDirectory("recs");
        String good = dir.resolve("in_12345_20261105-113000.m4a").toString();
        assertTrue(Vault.safePlainPathAgainst(dir.toFile(), good));

        // Anything outside the recordings folder is refused (would otherwise be read and deleted).
        assertFalse(Vault.safePlainPathAgainst(dir.toFile(),
            dir.resolve("../../shared_prefs/vault_keys.xml").toString()));
        assertFalse(Vault.safePlainPathAgainst(dir.toFile(), "/etc/hosts"));
        assertFalse(Vault.safePlainPathAgainst(dir.toFile(), dir.resolve("notes.txt").toString()));
        assertFalse(Vault.safePlainPathAgainst(dir.toFile(), dir.resolve("in_1_20261105-113000.m4a.enc").toString()));
        assertFalse(Vault.safePlainPathAgainst(dir.toFile(), null));
        assertFalse(Vault.safePlainPathAgainst(dir.toFile(), ""));
    }

    @Test public void pathGuardRejectsQueueInjectionCharacters() throws Exception {
        java.nio.file.Path dir = java.nio.file.Files.createTempDirectory("recs");
        String base = dir.resolve("in_1_20261105-113000.m4a").toString();
        assertFalse(Vault.safePlainPathAgainst(dir.toFile(), base + "|0|0|1"));
        assertFalse(Vault.safePlainPathAgainst(dir.toFile(), base.replace("in_", "in_\n")));
        assertFalse(Vault.safePlainPathAgainst(dir.toFile(), base.replace("in_", "in_\r")));
    }

    @Test public void pathGuardRejectsSymlinkEscape() throws Exception {
        java.nio.file.Path dir = java.nio.file.Files.createTempDirectory("recs");
        java.nio.file.Path outside = java.nio.file.Files.createTempDirectory("outside");
        java.nio.file.Path target = outside.resolve("in_1_20261105-113000.m4a");
        java.nio.file.Files.write(target, new byte[]{1, 2, 3});
        java.nio.file.Path link = dir.resolve("in_1_20261105-113000.m4a");
        try {
            java.nio.file.Files.createSymbolicLink(link, target);
            assertFalse("symlink must not be followed out of the folder",
                Vault.safePlainPathAgainst(dir.toFile(), link.toString()));
        } catch (java.nio.file.FileSystemException | UnsupportedOperationException e) {
            // Symlinks unsupported on this host; the canonical-path check still applies at runtime.
        }
    }
}
