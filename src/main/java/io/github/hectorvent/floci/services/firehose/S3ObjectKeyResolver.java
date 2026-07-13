package io.github.hectorvent.floci.services.firehose;

import org.jboss.logging.Logger;

import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Locale;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Builds S3 object keys for Firehose deliveries the way AWS does (verified
 * against real AWS, see https://github.com/floci-io/floci/issues/1857): the key
 * is {@code <evaluated prefix><suffix>} where the prefix supports
 * {@code !{timestamp:<DateTimeFormatter pattern>}} and
 * {@code !{firehose:random-string}} expressions, {@code yyyy/MM/dd/HH/} is
 * appended by literal concatenation when the prefix holds no expression at all
 * (including the null/empty prefix), and the suffix is
 * {@code <streamName>-<versionId>-<yyyy-MM-dd-HH-mm-ss>-<uuid>} with no file
 * extension.
 *
 * Known deviations from AWS: timestamps are evaluated at delivery time instead
 * of the oldest buffered record's arrival time, and expressions AWS would
 * reject at create time (unknown namespaces, dynamic-partitioning keys,
 * invalid patterns) are kept literally in the key instead of failing.
 */
final class S3ObjectKeyResolver {

    private static final Logger LOG = Logger.getLogger(S3ObjectKeyResolver.class);
    private static final Pattern EXPRESSION = Pattern.compile("!\\{(?<namespace>[^:}]*)(?::(?<argument>[^}]*))?}");
    private static final String DEFAULT_TIMESTAMP_PATTERN = "yyyy/MM/dd/HH/";
    private static final DateTimeFormatter SUFFIX_TIMESTAMP =
            DateTimeFormatter.ofPattern("yyyy-MM-dd-HH-mm-ss", Locale.ROOT);
    private static final String RANDOM_STRING_ALPHABET =
            "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789";
    private static final int RANDOM_STRING_LENGTH = 11;

    private S3ObjectKeyResolver() {}

    static String resolveKey(String prefix, String streamName, String versionId,
                             Instant deliveryTime, ZoneId zone) {
        ZonedDateTime time = ZonedDateTime.ofInstant(deliveryTime, zone);
        return evaluatePrefix(prefix, time) + objectNameSuffix(streamName, versionId, time);
    }

    static String evaluatePrefix(String prefix, ZonedDateTime time) {
        StringBuilder out = new StringBuilder();
        boolean expressionSeen = false;
        Matcher matcher = EXPRESSION.matcher(prefix == null ? "" : prefix);
        while (matcher.find()) {
            expressionSeen = true;
            matcher.appendReplacement(out, Matcher.quoteReplacement(evaluateExpression(matcher, time)));
        }
        matcher.appendTail(out);
        if (!expressionSeen) {
            out.append(DateTimeFormatter.ofPattern(DEFAULT_TIMESTAMP_PATTERN, Locale.ROOT).format(time));
        }
        return out.toString();
    }

    static String objectNameSuffix(String streamName, String versionId, ZonedDateTime time) {
        return streamName + "-" + versionId + "-" + SUFFIX_TIMESTAMP.format(time) + "-" + UUID.randomUUID();
    }

    static ZoneId resolveZone(String customTimeZone) {
        if (customTimeZone == null || customTimeZone.isBlank()) {
            return ZoneOffset.UTC;
        }
        try {
            return ZoneId.of(customTimeZone);
        } catch (Exception e) {
            LOG.warnv("Invalid CustomTimeZone {0}, evaluating S3 prefixes in UTC", customTimeZone);
            return ZoneOffset.UTC;
        }
    }

    private static String evaluateExpression(Matcher matcher, ZonedDateTime time) {
        String namespace = matcher.group("namespace");
        String argument = matcher.group("argument");
        if ("timestamp".equals(namespace) && argument != null) {
            return formatTimestamp(argument, matcher.group(), time);
        }
        if ("firehose".equals(namespace) && "random-string".equals(argument)) {
            return randomString();
        }
        LOG.warnv("Unsupported Firehose prefix expression {0}, keeping it literally", matcher.group());
        return matcher.group();
    }

    private static String formatTimestamp(String pattern, String expression, ZonedDateTime time) {
        try {
            return DateTimeFormatter.ofPattern(pattern, Locale.ROOT).format(time);
        } catch (Exception e) {
            LOG.warnv("Invalid pattern in Firehose prefix expression {0}, keeping it literally: {1}",
                    expression, e.getMessage());
            return expression;
        }
    }

    private static String randomString() {
        StringBuilder random = new StringBuilder(RANDOM_STRING_LENGTH);
        for (int i = 0; i < RANDOM_STRING_LENGTH; i++) {
            random.append(RANDOM_STRING_ALPHABET.charAt(
                    ThreadLocalRandom.current().nextInt(RANDOM_STRING_ALPHABET.length())));
        }
        return random.toString();
    }
}
