package io.github.hectorvent.floci.services.s3;

import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.core.common.XmlBuilder;
import io.github.hectorvent.floci.core.common.XmlParser;

import java.util.List;
import java.util.Map;

/**
 * A parsed {@code MetricsConfiguration} request body, reduced to its id and a canonical
 * serialization of its contents.
 *
 * <p>The body is re-serialized rather than stored verbatim, so that what a later
 * GetBucketMetricsConfiguration or ListBucketMetricsConfigurations returns is built by floci
 * instead of being whatever XML the caller sent, and so that the same stored form can be wrapped
 * in either response.
 */
record S3MetricsConfiguration(String id, String innerXml) {

    private static final String ROOT = "MetricsConfiguration";

    /** AWS reports a body that does not match the published schema this way. */
    private static AwsException malformed() {
        return new AwsException("MalformedXML",
                "The XML you provided was not well-formed or did not validate against our published schema", 400);
    }

    static S3MetricsConfiguration parse(String xml) {
        if (xml == null || xml.isBlank() || !ROOT.equals(XmlParser.rootElementName(xml))) {
            throw malformed();
        }

        // The configuration is one Id and at most one Filter, so anything else under the root —
        // a stray element, a second Id, a second Filter — is a body AWS would not have accepted.
        List<String> children = XmlParser.childElementNames(xml, ROOT);
        long ids = children.stream().filter("Id"::equals).count();
        long filters = children.stream().filter("Filter"::equals).count();
        if (ids != 1 || filters > 1 || children.size() != ids + filters) {
            throw malformed();
        }
        boolean hasFilter = filters == 1;

        String id = XmlParser.extractFirst(xml, "Id", null);
        if (id == null || id.isBlank()) {
            throw malformed();
        }

        XmlBuilder inner = new XmlBuilder().elem("Id", id);
        if (hasFilter) {
            inner.start("Filter").raw(filterXml(xml)).end("Filter");
        }
        return new S3MetricsConfiguration(id, inner.build());
    }

    /**
     * Serializes the filter. A filter is exactly one of a prefix, an object tag, an access point
     * ARN, or an And conjunction: AWS answers MalformedXML for a filter naming none of them or
     * more than one, and for an And holding fewer than two predicates.
     */
    private static String filterXml(String xml) {
        List<String> predicates = XmlParser.childElementNames(xml, "Filter");
        if (predicates.size() != 1) {
            throw malformed();
        }

        String predicate = predicates.getFirst();
        return switch (predicate) {
            case "Prefix", "AccessPointArn" -> new XmlBuilder()
                    .elem(predicate, requireText(xml, predicate))
                    .build();
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
                if (conjuncts.contains("AccessPointArn")) {
                    and.elem("AccessPointArn", requireText(xml, "AccessPointArn"));
                }
                // Every conjunct has to be one floci understands, or the filter it stores would
                // not be the filter that was sent.
                if (conjuncts.size() != tagCount + (conjuncts.contains("Prefix") ? 1 : 0)
                        + (conjuncts.contains("AccessPointArn") ? 1 : 0)) {
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

    private static String requireText(String xml, String element) {
        String value = XmlParser.extractFirst(xml, element, null);
        if (value == null) {
            throw malformed();
        }
        return value;
    }
}
