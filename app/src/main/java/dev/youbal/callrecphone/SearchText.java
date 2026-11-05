package dev.youbal.callrecphone;

import java.text.Normalizer;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Search that ignores case, accents, punctuation and spacing. "dr. rajesh-kumar" finds "Dr Rajesh Kumar";
 * "98765 43210", "+91 98765-43210" and "098765 43210" all find the same number.
 */
final class SearchText {
    private SearchText() {}

    /** Lower-case, accent-free text where every run of other characters is one space. */
    static String fold(String raw) {
        if (raw == null || raw.isEmpty()) return "";
        String text = Normalizer.normalize(raw, Normalizer.Form.NFKD);
        StringBuilder out = new StringBuilder(text.length());
        boolean gap = true;
        for (int i = 0; i < text.length(); ) {
            int cp = text.codePointAt(i);
            i += Character.charCount(cp);
            if (cp >= 0x0300 && cp <= 0x036F) continue; // Latin accents only; Indic vowel signs must stay.
            int type = Character.getType(cp);
            boolean part = Character.isLetterOrDigit(cp) || type == Character.NON_SPACING_MARK || type == Character.COMBINING_SPACING_MARK || type == Character.ENCLOSING_MARK;
            if (part) {
                out.appendCodePoint(Character.toLowerCase(cp));
                gap = false;
            } else if (!gap) {
                out.append(' ');
                gap = true;
            }
        }
        int end = out.length();
        if (end > 0 && out.charAt(end - 1) == ' ') out.setLength(end - 1);
        return out.toString().toLowerCase(Locale.ROOT);
    }

    static String digits(String raw) {
        if (raw == null) return "";
        StringBuilder out = new StringBuilder();
        for (int i = 0; i < raw.length(); i++) {
            int d = Character.digit(raw.charAt(i), 10);
            if (d >= 0) out.append((char) ('0' + d));
        }
        return out.toString();
    }

    /** A typed query, parsed once per keystroke. */
    static final class Query {
        final String folded;
        final String[] tokens;
        final boolean numeric;
        final boolean blank;
        Query(String raw) {
            String fold = fold(raw);
            boolean letters = false;
            for (int i = 0; i < fold.length(); i++) {
                char c = fold.charAt(i);
                if (!Character.isDigit(c) && c != ' ') { letters = true; break; }
            }
            numeric = !letters && !fold.isEmpty();
            folded = numeric ? digits(fold) : fold;
            blank = folded.isEmpty();
            tokens = blank ? new String[0] : numeric ? new String[]{folded} : fold.split(" ");
        }
    }

    /** What a contact or call can be found by, prepared once. */
    static final class Haystack {
        final String words, squished, name;
        final String[] numberDigits;
        /** Other names the same number is saved under, e.g. in the phone's own contacts. */
        final String[] aliases;
        Haystack(String name, String text, List<String> numbers) { this(name, text, numbers, List.of()); }
        Haystack(String name, String text, List<String> numbers, List<String> alsoSavedAs) {
            this.name = fold(name);
            List<String> folded = new ArrayList<>();
            for (String alias : alsoSavedAs) { String f = fold(alias); if (!f.isEmpty()) folded.add(f); }
            aliases = folded.toArray(new String[0]);
            words = fold(text + " " + String.join(" ", folded));
            squished = words.replace(" ", "");
            List<String> forms = new ArrayList<>();
            for (String number : numbers) {
                String d = digits(number);
                if (d.isEmpty()) continue;
                forms.add(d);
                if (d.length() == 12 && d.startsWith("91")) forms.add(d.substring(2));
            }
            numberDigits = forms.toArray(new String[0]);
        }
    }

    static boolean matches(Query q, Haystack h) {
        if (q.blank) return true;
        for (String token : q.tokens) if (!tokenMatches(token, q.numeric, h)) return false;
        return true;
    }

    private static boolean tokenMatches(String token, boolean numeric, Haystack h) {
        if (numeric || isAllDigits(token)) {
            String alt = token.length() > 7 && token.startsWith("91") ? token.substring(2) : token.length() > 1 && token.startsWith("0") ? token.substring(1) : "";
            for (String d : h.numberDigits) if (d.contains(token) || (!alt.isEmpty() && d.contains(alt))) return true;
            if (numeric) return false;
        }
        return h.words.contains(token) || h.squished.contains(token);
    }

    private static boolean isAllDigits(String s) {
        if (s.isEmpty()) return false;
        for (int i = 0; i < s.length(); i++) if (!Character.isDigit(s.charAt(i))) return false;
        return true;
    }

    /** Lower is better: name starts with the query, then a name word, then anywhere in the name, then the rest. */
    static int rank(Query q, Haystack h) {
        if (q.blank) return 0;
        String first = q.tokens[0];
        if (q.numeric) {
            for (String d : h.numberDigits) if (d.startsWith(first) || d.endsWith(first)) return 1;
            return 3;
        }
        int best = rankName(q, first, h.name);
        for (String alias : h.aliases) best = Math.min(best, rankName(q, first, alias));
        return best;
    }

    private static int rankName(Query q, String first, String name) {
        if (name.startsWith(q.folded) || name.replace(" ", "").startsWith(q.folded.replace(" ", ""))) return 0;
        if (name.startsWith(first) || name.contains(" " + first)) return 1;
        if (name.contains(first)) return 2;
        return 3;
    }
}
