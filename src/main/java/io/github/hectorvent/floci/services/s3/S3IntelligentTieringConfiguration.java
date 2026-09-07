package io.github.hectorvent.floci.services.s3;

import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.core.common.XmlBuilder;
import io.github.hectorvent.floci.core.common.XmlParser;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * A parsed {@code IntelligentTieringConfiguration} request body, reduced to a canonical
 * serialization of its contents.
 *
 * <p>The body is re-serialized rather than stored verbatim, so that what a later
 * GetBucketIntelligentTieringConfiguration or ListBucketIntelligentTieringConfigurations returns
 * is built by floci instead of being whatever XML the caller sent, and so that the same stored
 * form can be wrapped in either response.
 */
record S3IntelligentTieringConfiguration(String id, String innerXml) {

    private static final String ROOT = "IntelligentTieringConfiguration";

    private static final List<String> STATUSES = List.of("Enabled", "Disabled");
    private static final List<String> ACCESS_TIERS = List.of("ARCHIVE_ACCESS", "DEEP_ARCHIVE_ACCESS");

    private static final int ARCHIVE_ACCESS_MIN_DAYS = 90;
    private static final int DEEP_ARCHIVE_ACCESS_MIN_DAYS = 180;
    private static final int MAX_DAYS = 730;

    /** AWS reports a body that does not match the published schema this way. */
    private static AwsException malformed() {
        return new AwsException("MalformedXML",
                "The XML you provided was not well-formed or did not validate against our published schema", 400);
    }

    /**
     * AWS reports a {@code Days} value outside the tier-specific allowed range this way: the XML
     * is well-formed, but the value is semantically invalid. Minimums are tier-specific
     * ({@link #ARCHIVE_ACCESS_MIN_DAYS} for ARCHIVE_ACCESS, {@link #DEEP_ARCHIVE_ACCESS_MIN_DAYS}
     * for DEEP_ARCHIVE_ACCESS) and both are capped at {@link #MAX_DAYS}. The response carries
     * {@code ArgumentName} and {@code ArgumentValue} so the SDK can surface which input was
     * rejected, matching the real S3 error shape.
     */
    private static AwsException daysOutOfRange(String accessTier, int min, int days) {
        String message = days < min
                ? "Days must be at least " + min + " for AccessTier " + accessTier + "."
                : "Days must be at most " + MAX_DAYS + " for AccessTier " + accessTier + ".";
        Map<String, Object> detail = new LinkedHashMap<>();
        detail.put("ArgumentName", "Tiering.Days");
        detail.put("ArgumentValue", String.valueOf(days));
        return new AwsException("InvalidArgument", message, 400, detail);
    }

    static S3IntelligentTieringConfiguration parse(String xml) {
        if (xml == null || xml.isBlank() || !ROOT.equals(XmlParser.rootElementName(xml))) {
            throw malformed();
        }

        // The configuration is one Id, one Status, at most one Filter, and at least one Tiering,
        // so anything else under the root is a body AWS would not have accepted.
        List<String> children = XmlParser.childElementNames(xml, ROOT);
        long ids = children.stream().filter("Id"::equals).count();
        long statuses = children.stream().filter("Status"::equals).count();
        long filters = children.stream().filter("Filter"::equals).count();
        long tierings = children.stream().filter("Tiering"::equals).count();
        if (ids != 1 || statuses != 1 || filters > 1 || tierings < 1
                || children.size() != ids + statuses + filters + tierings) {
            throw malformed();
        }

        String id = XmlParser.extractFirst(xml, "Id", null);
        if (id == null || id.isBlank()) {
            throw malformed();
        }

        String status = XmlParser.extractFirst(xml, "Status", null);
        if (status == null || !STATUSES.contains(status)) {
            throw malformed();
        }

        XmlBuilder inner = new XmlBuilder().elem("Id", id);
        if (filters == 1) {
            inner.start("Filter").raw(filterXml(xml)).end("Filter");
        }
        inner.elem("Status", status);
        for (int i = 0; i < tierings; i++) {
            inner.start("Tiering").raw(tieringXml(xml, i)).end("Tiering");
        }
        return new S3IntelligentTieringConfiguration(id, inner.build());
    }

    /**
     * Serializes the filter. A filter is exactly one of a prefix, an object tag, or an And
     * conjunction: AWS answers MalformedXML for a filter naming none of them or more than one,
     * and for an And holding fewer than two predicates.
     */
    private static String filterXml(String xml) {
        List<String> predicates = XmlParser.childElementNames(xml, "Filter");
        if (predicates.size() != 1) {
            throw malformed();
        }

        String predicate = predicates.getFirst();
        return switch (predicate) {
            case "Prefix" -> new XmlBuilder().elem("Prefix", requireText(xml, "Prefix")).build();
            case "Tag" -> tagsXml(xml, 1);
            case "And" -> {
                List<String> conjuncts = XmlParser.childElementNames(xml, "And");
                if (conjuncts.size() < 2) {
                    throw malformed();
                }
                XmlBuilder and = new XmlBuilder().start("And");
                if (conjuncts.contains("Prefix")) {
                    and.elem("Prefix", requireText(xml, "Prefix"));
                }
                long tagCount = conjuncts.stream().filter("Tag"::equals).count();
                if (tagCount > 0) {
                    and.raw(tagsXml(xml, (int) tagCount));
                }
                // Every conjunct has to be one floci understands, or the filter it stores would
                // not be the filter that was sent.
                if (conjuncts.size() != tagCount + (conjuncts.contains("Prefix") ? 1 : 0)) {
                    throw malformed();
                }
                yield and.end("And").build();
            }
            default -> throw malformed();
        };
    }

    /**
     * Serializes {@code expected} tags. A tag carries both a key and a value, so a pair short of
     * the element count means one of them was missing.
     */
    private static String tagsXml(String xml, int expected) {
        Map<String, String> tags = XmlParser.extractPairs(xml, "Tag", "Key", "Value");
        if (tags.size() != expected) {
            throw malformed();
        }
        XmlBuilder out = new XmlBuilder();
        tags.forEach((key, value) -> {
            if (key.isBlank() || value == null) {
                throw malformed();
            }
            out.start("Tag").elem("Key", key).elem("Value", value).end("Tag");
        });
        return out.build();
    }

    /**
     * Serializes the {@code index}-th {@code Tiering} entry. Each tiering is an access tier and a
     * positive day count, and both are required. Days must also fall in the tier-specific range
     * AWS enforces: {@link #ARCHIVE_ACCESS_MIN_DAYS}-{@link #MAX_DAYS} for ARCHIVE_ACCESS and
     * {@link #DEEP_ARCHIVE_ACCESS_MIN_DAYS}-{@link #MAX_DAYS} for DEEP_ARCHIVE_ACCESS.
     */
    private static String tieringXml(String xml, int index) {
        List<Map<String, String>> entries = XmlParser.extractGroups(xml, "Tiering");
        if (index >= entries.size()) {
            throw malformed();
        }
        Map<String, String> tiering = entries.get(index);
        String accessTier = tiering.get("AccessTier");
        String days = tiering.get("Days");
        if (accessTier == null || !ACCESS_TIERS.contains(accessTier)) {
            throw malformed();
        }
        if (days == null || !days.matches("\\d+")) {
            throw malformed();
        }
        int dayCount;
        try {
            dayCount = Integer.parseInt(days);
        } catch (NumberFormatException e) {
            // Values above Integer.MAX_VALUE match the regex but overflow parseInt.
            throw malformed();
        }
        if (dayCount < 1) {
            throw malformed();
        }
        // Beyond well-formedness, AWS enforces a tier-specific range. A value outside it is
        // semantically invalid, not malformed, so AWS answers InvalidArgument.
        int min = "DEEP_ARCHIVE_ACCESS".equals(accessTier) ? DEEP_ARCHIVE_ACCESS_MIN_DAYS : ARCHIVE_ACCESS_MIN_DAYS;
        if (dayCount < min || dayCount > MAX_DAYS) {
            throw daysOutOfRange(accessTier, min, dayCount);
        }
        return new XmlBuilder()
                .elem("AccessTier", accessTier)
                .elem("Days", days)
                .build();
    }

    private static String requireText(String xml, String element) {
        String value = XmlParser.extractFirst(xml, element, null);
        if (value == null) {
            throw malformed();
        }
        return value;
    }
}
