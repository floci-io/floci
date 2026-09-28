package io.github.hectorvent.floci.core.common;

import java.util.List;
import java.util.Map;

/**
 * The S3 {@code Filter} sub-resource grammar shared by configuration types that scope
 * themselves to a subset of objects: a filter is exactly one of a prefix, an object tag, one
 * of the caller's extra conjuncts, or an {@code And} conjunction of two or more of those.
 * {@code S3IntelligentTieringConfiguration} and {@code S3MetricsConfiguration} each implemented
 * this independently before this class existed; the two aren't 100% identical, since
 * {@code MetricsFilter} additionally accepts {@code AccessPointArn} where
 * {@code IntelligentTieringFilter} does not, which is why {@code extraConjuncts} is a parameter
 * rather than a hardcoded set.
 */
public final class S3FilterGrammar {

    private S3FilterGrammar() {
    }

    /** AWS reports a body that does not match the published schema this way. */
    private static AwsException malformed() {
        return new AwsException("MalformedXML",
                "The XML you provided was not well-formed or did not validate against our published schema", 400);
    }

    /**
     * Serializes the filter. A filter is exactly one of a prefix, an object tag, one of
     * {@code extraConjuncts}, or an And conjunction: AWS answers MalformedXML for a filter naming
     * none of those or more than one, and for an And holding fewer than two predicates.
     */
    public static String filterXml(String xml, List<String> extraConjuncts) {
        List<String> predicates = XmlParser.childElementNames(xml, "Filter");
        if (predicates.size() != 1) {
            throw malformed();
        }

        String predicate = predicates.getFirst();
        return switch (predicate) {
            case "Tag" -> tagsXml(xml, 1);
            case "And" -> andXml(xml, extraConjuncts);
            default -> {
                if (!"Prefix".equals(predicate) && !extraConjuncts.contains(predicate)) {
                    throw malformed();
                }
                yield new XmlBuilder().elem(predicate, requireText(xml, predicate)).build();
            }
        };
    }

    private static String andXml(String xml, List<String> extraConjuncts) {
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
        long extraCount = 0;
        for (String extra : extraConjuncts) {
            if (conjuncts.contains(extra)) {
                and.elem(extra, requireText(xml, extra));
                extraCount++;
            }
        }
        // Every conjunct has to be one floci understands, or the filter it stores would
        // not be the filter that was sent.
        if (conjuncts.size() != tagCount + (conjuncts.contains("Prefix") ? 1 : 0) + extraCount) {
            throw malformed();
        }
        return and.end("And").build();
    }

    /**
     * Serializes {@code expected} tags. A tag carries both a key and a value, so a pair short of
     * the element count means one of them was missing.
     */
    public static String tagsXml(String xml, int expected) {
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

    public static String requireText(String xml, String element) {
        String value = XmlParser.extractFirst(xml, element, null);
        if (value == null) {
            throw malformed();
        }
        return value;
    }
}
