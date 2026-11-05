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
}
