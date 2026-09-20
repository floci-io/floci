package io.github.hectorvent.floci.services.cloudwatch.logs.filter;

import java.util.Collections;
import java.util.Map;
import java.util.function.Function;

/**
 * The outcome of matching one log event message against a {@link FilterPattern}: whether it
 * matched, the values the pattern extracted (what {@code TestMetricFilter} reports), and a way to
 * read any field of the event by reference, which is how a metric filter finds its metric value
 * ({@code $.latency}, {@code $bytes}) and its dimensions.
 */
public final class FilterMatch {

    public static final FilterMatch NONE = new FilterMatch(false, Map.of(), reference -> null);

    private final boolean matched;
    private final Map<String, String> extractedValues;
    private final Function<String, String> values;

    FilterMatch(boolean matched, Map<String, String> extractedValues, Function<String, String> values) {
        this.matched = matched;
        this.extractedValues = Collections.unmodifiableMap(extractedValues);
        this.values = values;
    }

    static FilterMatch of(Map<String, String> extractedValues, Function<String, String> values) {
        return new FilterMatch(true, extractedValues, values);
    }

    public boolean matched() {
        return matched;
    }

    /** The named values the pattern pulled out of the event, in pattern order; empty for term patterns. */
    public Map<String, String> extractedValues() {
        return extractedValues;
    }

    /**
     * The text of the event field a reference names ({@code $.path} for a JSON event, {@code $name}
     * or {@code $3} for a space-delimited one), or null when the event has no such scalar field.
     */
    public String value(String reference) {
        return reference == null ? null : values.apply(reference);
    }
}
