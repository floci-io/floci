package io.github.hectorvent.floci.services.eventbridgev2;

import com.fasterxml.jackson.databind.JsonNode;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The JSONata a target parameter may hold, as far as Floci evaluates it: a literal value, or one expression in
 * {@code {% %}} that is a path into the event, {@code $events.SystemMetadata.EventGroupId}, whose segments are
 * names, {@code "quoted"} or {@code `backquoted`} for a name such as {@code aws:EventId} or {@code detail-type}.
 */
final class EventExpressions {

    private static final Pattern EXPRESSION = Pattern.compile("\\{%[\\s\\S]*%}");
    private static final Pattern PATH = Pattern.compile("\\{%\\s*\\$events((?:\\.(?:[A-Za-z_][A-Za-z0-9_]*"
            + "|\"[^\"]+\"|`[^`]+`))+)\\s*%}");
    private static final Pattern SEGMENT = Pattern.compile("\\.([A-Za-z_][A-Za-z0-9_]*|\"([^\"]+)\"|`([^`]+)`)");

    private EventExpressions() {
    }

    static boolean isExpression(String value) {
        return value != null && EXPRESSION.matcher(value.trim()).matches();
    }

    /** Whether Floci evaluates the value: a literal, or an expression that is a path into the event. */
    static boolean isSupported(String value) {
        return !isExpression(value) || PATH.matcher(value.trim()).matches();
    }

    /**
     * The value for one event: a literal as given, a path's value as text (a string unquoted, anything else as
     * JSON), or empty when the path names nothing, which fails the delivery as AWS fails it.
     */
    static Optional<String> resolve(String value, PublishedEvent event) {
        if (!isExpression(value)) {
            return Optional.of(value);
        }
        Matcher path = PATH.matcher(value.trim());
        if (!path.matches()) {
            return Optional.empty();
        }
        JsonNode node = event.withMetadata();
        for (String segment : segments(path.group(1))) {
            node = node == null || !node.isObject() ? null : node.get(segment);
        }
        if (node == null || node.isNull()) {
            return Optional.empty();
        }
        return Optional.of(node.isTextual() ? node.textValue() : node.toString());
    }

    private static List<String> segments(String path) {
        List<String> segments = new ArrayList<>();
        Matcher segment = SEGMENT.matcher(path);
        while (segment.find()) {
            segments.add(segment.group(2) != null ? segment.group(2)
                    : segment.group(3) != null ? segment.group(3) : segment.group(1));
        }
        return segments;
    }
}
