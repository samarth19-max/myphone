package dev.youbal.callrecphone;

import java.util.List;

/** A contact as Android merges them (Google, phone storage, SIM): a name and its numbers. */
final class Person {
    final String name, source;
    final List<String> numbers;
    final SearchText.Haystack search;
    Person(String name, String source, List<String> numbers) {
        this.name = name; this.source = source; this.numbers = numbers;
        search = new SearchText.Haystack(name, name, numbers);
    }
    String primaryNumber() { return numbers.isEmpty() ? "" : numbers.get(0); }
}
