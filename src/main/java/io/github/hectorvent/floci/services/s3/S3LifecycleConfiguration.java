package io.github.hectorvent.floci.services.s3;

import io.github.hectorvent.floci.core.common.XmlParser;
import io.github.hectorvent.floci.core.common.XmlParser.XmlElement;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Function;

/**
 * The expiration actions of a bucket's lifecycle configuration, read the way S3 applies them.
 *
 * <p>PutBucketLifecycleConfiguration stores the body verbatim, so this is parsed when it is used
 * and is lenient: a body that does not parse has no rules, and an action whose values do not
 * parse is ignored, rather than failing the request or the sweep that reads it. Disabled rules are
 * dropped. Transition actions are not modelled.
 */
record S3LifecycleConfiguration(List<Rule> rules) {

    static final S3LifecycleConfiguration EMPTY = new S3LifecycleConfiguration(List.of());

    private static final DateTimeFormatter HTTP_DATE = DateTimeFormatter
            .ofPattern("EEE, dd MMM yyyy HH:mm:ss 'GMT'", Locale.US)
            .withZone(ZoneOffset.UTC);

    // Date is ISO 8601 on the wire. The leniency covers bodies Floci writes itself, such as a
    // CloudFormation ExpirationDate passed through as the template spelled it.
    private static final List<Function<String, Instant>> DATE_PARSERS = List.of(
            text -> OffsetDateTime.parse(text).toInstant(),
            text -> LocalDateTime.parse(text).toInstant(ZoneOffset.UTC),
            text -> LocalDate.parse(text).atStartOfDay(ZoneOffset.UTC).toInstant());

    record Rule(String id, Filter filter, Expiration expiration,
                NoncurrentVersionExpiration noncurrentVersionExpiration, Integer abortDaysAfterInitiation) {
    }

    /** A rule's filter: every condition that is set must hold. An empty filter matches everything. */
    record Filter(String prefix, Map<String, String> tags, Long objectSizeGreaterThan, Long objectSizeLessThan) {

        boolean matches(String key, Map<String, String> objectTags, long size) {
            if (!key.startsWith(prefix)) {
                return false;
            }
            for (Map.Entry<String, String> tag : tags.entrySet()) {
                String value = objectTags == null ? null : objectTags.get(tag.getKey());
                if (!tag.getValue().equals(value)) {
                    return false;
                }
            }
            // Both size bounds are exclusive.
            return (objectSizeGreaterThan == null || size > objectSizeGreaterThan)
                    && (objectSizeLessThan == null || size < objectSizeLessThan);
        }

        /**
         * An in-progress multipart upload has no tags or size to test, and S3 refuses
         * AbortIncompleteMultipartUpload under a tag filter, so only a prefix selects uploads.
         */
        boolean selectsUpload(String key) {
            return tags.isEmpty() && objectSizeGreaterThan == null && objectSizeLessThan == null
                    && key.startsWith(prefix);
        }
    }

    record Expiration(Integer days, Instant date, boolean expiredObjectDeleteMarker) {

        /** When something created at {@code created} expires under this action, or null if it sets no age. */
        Instant expiresAt(Instant created) {
            if (days != null) {
                return afterDays(created, days);
            }
            return date;
        }
    }

    record NoncurrentVersionExpiration(Integer noncurrentDays, Integer newerNoncurrentVersions) {
    }

    /** The earliest current-version expiration that applies to an object, and the rule that sets it. */
    record Expiry(Instant date, String ruleId) {

        /**
         * The {@code x-amz-expiration} header value, in the shape the S3 API reference shows,
         * {@code expiry-date="Fri, 23 Dec 2012 00:00:00 GMT", rule-id="1"}, with the rule id
         * URL-encoded as the reference requires.
         */
        String header() {
            String encodedId = URLEncoder.encode(ruleId == null ? "" : ruleId, StandardCharsets.UTF_8)
                    .replace("+", "%20")
                    .replace("*", "%2A")
                    .replace("%7E", "~");
            return "expiry-date=\"" + HTTP_DATE.format(date) + "\", rule-id=\"" + encodedId + "\"";
        }
    }

    /**
     * Day-based lifecycle times run from a start time: "Amazon S3 calculates the time by adding
     * the number of days specified in the rule to the object creation time and rounding up the
     * resulting time to the next day at midnight UTC."
     */
    static Instant afterDays(Instant start, int days) {
        return start.plus(days, ChronoUnit.DAYS).truncatedTo(ChronoUnit.DAYS).plus(1, ChronoUnit.DAYS);
    }

    /**
     * The earliest Days or Date expiration among the rules that match a current object version,
     * or null when none does.
     */
    Expiry currentVersionExpiry(String key, Map<String, String> tags, long size, Instant created) {
        Expiry earliest = null;
        for (Rule rule : rules) {
            if (rule.expiration() == null || !rule.filter().matches(key, tags, size)) {
                continue;
            }
            Instant at = rule.expiration().expiresAt(created);
            if (at != null && (earliest == null || at.isBefore(earliest.date()))) {
                earliest = new Expiry(at, rule.id());
            }
        }
        return earliest;
    }

    /**
     * Whether a delete marker that is the only version of its key, an expired object delete
     * marker, is due for removal. ExpiredObjectDeleteMarker removes it at once; a Days or Date
     * expiration removes it once the marker itself is old enough.
     */
    boolean removesExpiredObjectDeleteMarker(String key, Instant markerCreated, Instant now) {
        for (Rule rule : rules) {
            Expiration expiration = rule.expiration();
            if (expiration == null || !rule.filter().matches(key, Map.of(), 0)) {
                continue;
            }
            if (expiration.expiredObjectDeleteMarker()) {
                return true;
            }
            Instant at = expiration.expiresAt(markerCreated);
            if (at != null && !now.isBefore(at)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Whether a noncurrent version is due for permanent deletion: it has been noncurrent, counted
     * from when its successor was created, for NoncurrentDays, and at least
     * NewerNoncurrentVersions newer noncurrent versions are retained ahead of it.
     */
    boolean expiresNoncurrentVersion(String key, Map<String, String> tags, long size,
                                     Instant becameNoncurrent, int newerNoncurrentVersions, Instant now) {
        for (Rule rule : rules) {
            NoncurrentVersionExpiration action = rule.noncurrentVersionExpiration();
            if (action == null || !rule.filter().matches(key, tags, size)) {
                continue;
            }
            if (action.noncurrentDays() == null && action.newerNoncurrentVersions() == null) {
                continue;
            }
            boolean oldEnough = action.noncurrentDays() == null
                    || !now.isBefore(afterDays(becameNoncurrent, action.noncurrentDays()));
            boolean beyondRetained = action.newerNoncurrentVersions() == null
                    || newerNoncurrentVersions >= action.newerNoncurrentVersions();
            if (oldEnough && beyondRetained) {
                return true;
            }
        }
        return false;
    }

    /** Whether an incomplete multipart upload for {@code key} initiated at {@code initiated} is due to be aborted. */
    boolean abortsUpload(String key, Instant initiated, Instant now) {
        for (Rule rule : rules) {
            Integer days = rule.abortDaysAfterInitiation();
            if (days != null && rule.filter().selectsUpload(key) && !now.isBefore(afterDays(initiated, days))) {
                return true;
            }
        }
        return false;
    }

    static S3LifecycleConfiguration parse(String xml) {
        XmlElement root = XmlParser.extractElementTree(xml, "LifecycleConfiguration");
        if (root == null) {
            return EMPTY;
        }
        List<Rule> rules = new ArrayList<>();
        for (XmlElement rule : root.children()) {
            if ("Rule".equals(rule.name()) && "Enabled".equals(text(rule, "Status"))) {
                rules.add(new Rule(
                        text(rule, "ID"),
                        filter(rule),
                        expiration(rule.child("Expiration")),
                        noncurrentVersionExpiration(rule.child("NoncurrentVersionExpiration")),
                        integer(rule.child("AbortIncompleteMultipartUpload"), "DaysAfterInitiation")));
            }
        }
        return new S3LifecycleConfiguration(List.copyOf(rules));
    }

    /** The rule's Filter, or the deprecated rule-level Prefix that predates it. */
    private static Filter filter(XmlElement rule) {
        XmlElement filter = rule.child("Filter");
        if (filter == null) {
            String prefix = text(rule, "Prefix");
            return new Filter(prefix == null ? "" : prefix, Map.of(), null, null);
        }
        XmlElement and = filter.child("And");
        XmlElement conditions = and != null ? and : filter;
        String prefix = text(conditions, "Prefix");
        Map<String, String> tags = new LinkedHashMap<>();
        for (XmlElement tag : conditions.children()) {
            if ("Tag".equals(tag.name()) && text(tag, "Key") != null) {
                // A tag condition with no Value matches a tag whose value is empty.
                String value = text(tag, "Value");
                tags.put(text(tag, "Key"), value == null ? "" : value);
            }
        }
        return new Filter(prefix == null ? "" : prefix, Map.copyOf(tags),
                longValue(conditions, "ObjectSizeGreaterThan"), longValue(conditions, "ObjectSizeLessThan"));
    }

    private static Expiration expiration(XmlElement element) {
        if (element == null) {
            return null;
        }
        Integer days = integer(element, "Days");
        String dateText = text(element, "Date");
        Instant date = dateText == null ? null : date(dateText);
        boolean deleteMarker = "true".equalsIgnoreCase(text(element, "ExpiredObjectDeleteMarker"));
        if (days == null && date == null && !deleteMarker) {
            return null;
        }
        return new Expiration(days, date, deleteMarker);
    }

    private static NoncurrentVersionExpiration noncurrentVersionExpiration(XmlElement element) {
        if (element == null) {
            return null;
        }
        return new NoncurrentVersionExpiration(
                integer(element, "NoncurrentDays"), integer(element, "NewerNoncurrentVersions"));
    }

    private static String text(XmlElement parent, String childName) {
        XmlElement child = parent.child(childName);
        return child == null ? null : child.text();
    }

    private static Integer integer(XmlElement parent, String childName) {
        if (parent == null) {
            return null;
        }
        Long value = longValue(parent, childName);
        return value == null || value < 0 || value > Integer.MAX_VALUE ? null : value.intValue();
    }

    private static Long longValue(XmlElement parent, String childName) {
        String text = text(parent, childName);
        if (text == null || text.isEmpty()) {
            return null;
        }
        try {
            return Long.parseLong(text);
        } catch (NumberFormatException ignored) {
            // A value S3 would have rejected leaves the condition or action unset.
            return null;
        }
    }

    private static Instant date(String text) {
        for (Function<String, Instant> parser : DATE_PARSERS) {
            try {
                return parser.apply(text);
            } catch (DateTimeParseException ignored) {
                // Not this shape; try the next one.
            }
        }
        return null;
    }
}
