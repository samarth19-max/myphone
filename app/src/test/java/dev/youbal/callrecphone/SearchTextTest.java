package dev.youbal.callrecphone;

import org.junit.Test;
import java.util.List;
import static org.junit.Assert.*;

public class SearchTextTest {
    private static SearchText.Haystack hay(String name, String text, String... numbers) {
        return new SearchText.Haystack(name, name + " " + text, List.of(numbers));
    }
    private static boolean find(String query, SearchText.Haystack h) { return SearchText.matches(new SearchText.Query(query), h); }

    @Test public void foldIgnoresCaseSymbolsAndAccents() {
        assertEquals("dr ra jesh kumar", SearchText.fold("  Dr. Ra’jesh--KUMAR!! "));
        assertEquals("jose cafe", SearchText.fold("José Café"));
        assertEquals("", SearchText.fold("--- ()"));
    }

    @Test public void foldKeepsIndicWords() {
        assertEquals("राजेश कुमार", SearchText.fold("राजेश, कुमार"));
        assertTrue(find("राजेश", hay("राजेश कुमार", "")));
    }

    @Test public void namesMatchWithoutCaseOrPunctuation() {
        SearchText.Haystack h = hay("Dr. Rajesh Kumar", "Kanpur ayurvedic clinic");
        assertTrue(find("RAJESH", h));
        assertTrue(find("dr rajesh", h));
        assertTrue(find("rajesh-kumar", h));
        assertTrue(find("kumar raj", h));
        assertTrue(find("ayur kanpur", h));
        assertFalse(find("sharma", h));
    }

    @Test public void wordsSplitByPunctuationStillMatchWhenTypedTogether() {
        assertTrue(find("rajesh", hay("Ra'jesh Kumar", "")));
        assertTrue(find("ra'jesh", hay("Rajesh Kumar", "")));
    }

    @Test public void numbersMatchWhateverTheFormatting() {
        SearchText.Haystack h = hay("Anil", "", "+919876543210");
        assertTrue(find("98765 43210", h));
        assertTrue(find("+91 98765-43210", h));
        assertTrue(find("098765 43210", h));
        assertTrue(find("(987) 654", h));
        assertTrue(find("3210", h));
        assertFalse(find("98765 43211", h));
    }

    @Test public void blankQueryMatchesEverything() {
        assertTrue(find("", hay("Anil", "")));
        assertTrue(find(" -- ", hay("Anil", "")));
    }

    @Test public void namePrefixRanksAheadOfLooseMatches() {
        SearchText.Query q = new SearchText.Query("ani");
        int prefix = SearchText.rank(q, hay("Anil Sharma", ""));
        int word = SearchText.rank(q, hay("Sunil Anita", ""));
        int inside = SearchText.rank(q, hay("Mohani Rao", ""));
        int other = SearchText.rank(q, hay("Raj", "Rani area"));
        assertTrue(prefix < word);
        assertTrue(word < inside);
        assertTrue(inside < other);
    }
    @Test public void findsContactByNameSavedOnThePhone() {
        SearchText.Haystack h = new SearchText.Haystack("Rajesh Traders", "Rajesh Traders Lead", List.of("+919876543210"), List.of("Bhaiya Kanpur", "Raju Mama"));
        assertTrue(find("bhaiya", h));
        assertTrue(find("raju-mama", h));
        assertTrue(find("rajesh", h));
        assertFalse(find("sunil", h));
        assertFalse(find("bhaiya", hay("Rajesh Traders", "Lead", "+919876543210")));
    }

    @Test public void phoneSavedNameRanksLikeAName() {
        SearchText.Query q = new SearchText.Query("bhaiya");
        int aliasPrefix = SearchText.rank(q, new SearchText.Haystack("Rajesh Traders", "Rajesh Traders", List.of(), List.of("Bhaiya Kanpur")));
        int looseOnly = SearchText.rank(q, hay("Rajesh Traders", "bhaiya in notes"));
        assertTrue(aliasPrefix < looseOnly);
    }
}
