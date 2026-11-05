package dev.youbal.callrecphone;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

public class PhoneNumbersTest {
    @Test public void indiaNumbersMatchhowEverTheyAreWritten() {
        PhoneNumbers.setRegion("IN");
        assertEquals("+919876543210", PhoneNumbers.normalize("98765 43210"));
        assertEquals("+919876543210", PhoneNumbers.normalize("098765-43210"));
        assertEquals("+919876543210", PhoneNumbers.normalize("+91 98765 43210"));
        assertEquals("+919876543210", PhoneNumbers.normalize("919876543210"));
        assertEquals("+919876543210", PhoneNumbers.normalize("00919876543210"));
    }

    @Test public void usNumbersUseTheirOwnCode() {
        PhoneNumbers.setRegion("US");
        assertEquals("+15551234567", PhoneNumbers.normalize("(555) 123-4567"));
        assertEquals("+15551234567", PhoneNumbers.normalize("1 555 123 4567"));
        assertEquals("+442071838750", PhoneNumbers.normalize("+44 20 7183 8750"));
    }

    @Test public void codesAndTextAreNotNumbers() {
        PhoneNumbers.setRegion("IN");
        assertEquals("", PhoneNumbers.normalize("*#06#"));
        assertEquals("", PhoneNumbers.normalize("Mummy"));
        assertEquals("", PhoneNumbers.normalize(null));
        assertEquals("", PhoneNumbers.normalize("+0123"));
    }

    @Test public void unknownRegionKeepsDigitsAsTyped() {
        PhoneNumbers.setRegion("ZZ");
        assertEquals("5551234567", PhoneNumbers.normalize("555 123 4567"));
        assertEquals("+15551234567", PhoneNumbers.normalize("+1 555 123 4567"));
    }

    @Test public void displayGroupsByCountry() {
        PhoneNumbers.setRegion("IN");
        assertEquals("98765 43210", PhoneNumbers.display("9876543210"));
        assertEquals("+91 98765 43210", PhoneNumbers.display("+919876543210"));
        assertEquals("*#06#", PhoneNumbers.display("*#06#"));
        PhoneNumbers.setRegion("US");
        assertEquals("555 123 4567", PhoneNumbers.display("5551234567"));
    }
}
