package dev.youbal.callrecphone;

import android.content.Context;
import android.telephony.TelephonyManager;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

/**
 * Makes numbers comparable: "098765 43210", "+91 98765-43210" and "9876543210" are one number.
 * Numbers without a country code get the one for the phone's own country.
 */
public final class PhoneNumbers {
    private PhoneNumbers() {}

    private static final Map<String, String> CODES = new HashMap<>();
    static {
        String[] pairs = {"US:1", "CA:1", "GB:44", "IN:91", "AU:61", "NZ:64", "IE:353", "ZA:27", "NG:234", "KE:254", "GH:233",
            "PK:92", "BD:880", "LK:94", "NP:977", "AE:971", "SA:966", "QA:974", "KW:965", "OM:968", "BH:973", "IL:972", "TR:90",
            "EG:20", "MA:212", "DZ:213", "DE:49", "FR:33", "ES:34", "IT:39", "PT:351", "NL:31", "BE:32", "CH:41", "AT:43",
            "SE:46", "NO:47", "DK:45", "FI:358", "PL:48", "CZ:420", "GR:30", "RO:40", "HU:36", "UA:380", "RU:7", "KZ:7",
            "CN:86", "JP:81", "KR:82", "SG:65", "MY:60", "ID:62", "TH:66", "VN:84", "PH:63", "HK:852", "TW:886",
            "BR:55", "AR:54", "CL:56", "CO:57", "PE:51", "MX:52", "VE:58"};
        for (String pair : pairs) { int i = pair.indexOf(':'); CODES.put(pair.substring(0, i), pair.substring(i + 1)); }
    }
    private static volatile String code = "";

    /** The phone's own country: network first, then the SIM, then the language setting. */
    static void init(Context context) {
        String iso = "";
        try {
            TelephonyManager phone = context.getSystemService(TelephonyManager.class);
            if (phone != null) { iso = phone.getNetworkCountryIso(); if (iso == null || iso.isEmpty()) iso = phone.getSimCountryIso(); }
        } catch (RuntimeException ignored) { /* Fall back to the language setting. */ }
        if (iso == null || iso.isEmpty()) iso = Locale.getDefault().getCountry();
        setRegion(iso);
    }
    /** Two-letter country code such as "IN" or "US". An unknown one means local numbers are compared as typed. */
    static void setRegion(String iso) {
        String found = CODES.get(iso == null ? "" : iso.toUpperCase(Locale.ROOT));
        code = found == null ? "" : found;
    }

    /** "+<country><number>" for anything that is a phone number, "" for codes like *#06# or text. */
    public static String normalize(String raw) {
        if (raw == null) return "";
        String value = raw.trim().replaceAll("[\\s().-]", "");
        if (value.startsWith("00")) value = "+" + value.substring(2);
        if (value.startsWith("+")) return value.matches("\\+[1-9][0-9]{6,14}") ? value : "";
        if (!value.matches("[0-9]{4,15}")) return "";
        if (code.isEmpty()) return value.length() >= 6 ? value : "";
        String national = value.startsWith("0") ? value.substring(1) : value;
        if (national.length() >= 11 && national.startsWith(code)) return "+" + national;
        return national.length() >= 6 && national.length() <= 12 ? "+" + code + national : "";
    }

    /** Groups what is shown on screen; codes with * or # are shown as typed. */
    public static String display(String raw) {
        if (raw == null || raw.isEmpty()) return "";
        if (raw.indexOf('*') >= 0 || raw.indexOf('#') >= 0) return raw;
        String kept = raw.replaceAll("[^0-9+]", "");
        boolean plus = kept.startsWith("+");
        String digits = kept.replace("+", "");
        if (plus) return !code.isEmpty() && digits.startsWith(code) ? "+" + code + " " + group(digits.substring(code.length())) : "+" + digits;
        return group(digits);
    }
    private static String group(String d) {
        if (d.length() != 10) return d;
        return code.equals("91") ? d.substring(0, 5) + " " + d.substring(5) : d.substring(0, 3) + " " + d.substring(3, 6) + " " + d.substring(6);
    }
}
