package io.floci.aws.services.appsync.events;

import java.util.List;
import java.util.regex.Pattern;

/**
 * An AppSync Events channel path, {@code /<namespace>/<segment>...}: one to five segments, each 1
 * to 50 letters, digits or dashes that neither start nor end with a dash. A leading and a trailing
 * slash are optional. The first segment names the channel namespace.
 *
 * <p>A subscription may end in a {@code *} segment, which matches the channel up to it and any
 * deeper segments: {@code /ns/a/*} matches {@code /ns/a/b} and {@code /ns/a/b/c}, and {@code /ns/*}
 * every channel of the namespace. A published channel never carries a wildcard.
 */
public record EventsChannel(String path, List<String> segments, boolean wildcard) {

    public static final int MAX_SEGMENTS = 5;
    public static final String WILDCARD = "*";

    private static final Pattern SEGMENT = Pattern.compile("[A-Za-z0-9](?:[A-Za-z0-9-]{0,48}[A-Za-z0-9])?");

    /** The channel namespace: the first segment. */
    public String namespace() {
        return segments.getFirst();
    }

    /** Parses a channel a client publishes to, which may not carry a wildcard. */
    public static EventsChannel forPublish(String path) {
        return parse(path, false);
    }

    /** Parses a channel a client subscribes to, whose last segment may be {@code *}. */
    public static EventsChannel forSubscribe(String path) {
        return parse(path, true);
    }

    /**
     * Whether an event published to {@code published} reaches this subscription: the same segments,
     * or, for a wildcard subscription, the segments before the {@code *} followed by at least one more.
     */
    public boolean matches(EventsChannel published) {
        if (!wildcard) {
            return segments.equals(published.segments);
        }
        List<String> prefix = segments.subList(0, segments.size() - 1);
        return published.segments.size() > prefix.size()
                && published.segments.subList(0, prefix.size()).equals(prefix);
    }

    private static EventsChannel parse(String path, boolean allowWildcard) {
        if (path == null || path.isBlank()) {
            throw new IllegalArgumentException("A channel is required");
        }
        String trimmed = path;
        if (trimmed.startsWith("/")) {
            trimmed = trimmed.substring(1);
        }
        if (trimmed.endsWith("/")) {
            trimmed = trimmed.substring(0, trimmed.length() - 1);
        }
        List<String> segments = List.of(trimmed.split("/", -1));
        if (trimmed.isEmpty() || segments.size() > MAX_SEGMENTS) {
            throw new IllegalArgumentException("A channel has one to " + MAX_SEGMENTS + " segments: " + path);
        }
        boolean wildcard = false;
        for (int i = 0; i < segments.size(); i++) {
            String segment = segments.get(i);
            boolean last = i == segments.size() - 1;
            if (WILDCARD.equals(segment) && allowWildcard && last && i > 0) {
                wildcard = true;
                continue;
            }
            if (!SEGMENT.matcher(segment).matches()) {
                throw new IllegalArgumentException("Invalid channel segment '" + segment + "' in " + path);
            }
        }
        return new EventsChannel("/" + trimmed, segments, wildcard);
    }
}
