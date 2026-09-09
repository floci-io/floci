package io.github.hectorvent.floci.services.s3;

import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.core.common.XmlBuilder;
import io.github.hectorvent.floci.core.common.XmlParser;
import io.github.hectorvent.floci.core.common.XmlParser.XmlElement;

import java.util.List;

/**
 * A parsed {@code AnalyticsConfiguration} request body, reduced to its id and a canonical
 * serialization of its contents.
 *
 * <p>The body is re-serialized rather than stored verbatim, so that what a later
 * GetBucketAnalyticsConfiguration or ListBucketAnalyticsConfigurations returns is built by floci
 * instead of being whatever XML the caller sent, and so that the same stored form can be wrapped
 * in either response.
 */
record S3AnalyticsConfiguration(String id, String innerXml) {

    private static final String ROOT = "AnalyticsConfiguration";

    private static final List<String> OUTPUT_SCHEMA_VERSIONS = List.of("V_1");
    private static final List<String> FORMATS = List.of("CSV");

    /** AWS reports a body that does not match the published schema this way. */
    private static AwsException malformed() {
        return new AwsException("MalformedXML",
                "The XML you provided was not well-formed or did not validate against our published schema", 400);
    }

    static S3AnalyticsConfiguration parse(String xml) {
        if (xml == null || xml.isBlank() || !ROOT.equals(XmlParser.rootElementName(xml))) {
            throw malformed();
        }
        XmlElement root = XmlParser.extractElementTree(xml, ROOT);
        if (root == null) {
            throw malformed();
        }

        // The configuration is one Id, at most one Filter and one StorageClassAnalysis, so
        // anything else under the root is a body AWS would not have accepted.
        long ids = count(root, "Id");
        long filters = count(root, "Filter");
        long analyses = count(root, "StorageClassAnalysis");
        if (ids != 1 || filters > 1 || analyses != 1
                || root.children().size() != ids + filters + analyses) {
            throw malformed();
        }

        String id = root.child("Id").text();
        if (id == null || id.isBlank()) {
            throw malformed();
        }

        XmlBuilder inner = new XmlBuilder().elem("Id", id);
        if (filters == 1) {
            inner.start("Filter").raw(filterXml(root.child("Filter"))).end("Filter");
        }
        inner.start("StorageClassAnalysis")
                .raw(storageClassAnalysisXml(root.child("StorageClassAnalysis")))
                .end("StorageClassAnalysis");
        return new S3AnalyticsConfiguration(id, inner.build());
    }

    /**
     * Serializes the filter. A filter is exactly one of a prefix, an object tag, or an And
     * conjunction: AWS answers MalformedXML for a filter naming none of them or more than one,
     * and for an And holding fewer than two predicates.
     */
    private static String filterXml(XmlElement filter) {
        if (filter.children().size() != 1) {
            throw malformed();
        }
        XmlElement predicate = filter.children().getFirst();
        return switch (predicate.name()) {
            case "Prefix" -> new XmlBuilder().elem("Prefix", predicate.text()).build();
            case "Tag" -> tagXml(predicate);
            case "And" -> {
                List<XmlElement> conjuncts = predicate.children();
                long prefixes = count(predicate, "Prefix");
                long tags = count(predicate, "Tag");
                // Every conjunct has to be one floci understands, or the filter it stores would
                // not be the filter that was sent.
                if (conjuncts.size() < 2 || prefixes > 1 || conjuncts.size() != prefixes + tags) {
                    throw malformed();
                }
                XmlBuilder and = new XmlBuilder().start("And");
                if (prefixes == 1) {
                    and.elem("Prefix", predicate.child("Prefix").text());
                }
                conjuncts.stream().filter(c -> "Tag".equals(c.name()))
                        .forEach(tag -> and.raw(tagXml(tag)));
                yield and.end("And").build();
            }
            default -> throw malformed();
        };
    }

    /** A tag carries both a key and a value, so either one missing is a schema violation. */
    private static String tagXml(XmlElement tag) {
        XmlElement key = tag.child("Key");
        XmlElement value = tag.child("Value");
        if (key == null || key.text().isBlank() || value == null || tag.children().size() != 2) {
            throw malformed();
        }
        return new XmlBuilder().start("Tag").elem("Key", key.text()).elem("Value", value.text()).end("Tag").build();
    }

    /**
     * Serializes the storage class analysis. It holds at most one DataExport, and an export names
     * the output schema version and a destination bucket, with the account id and prefix optional.
     */
    private static String storageClassAnalysisXml(XmlElement analysis) {
        long exports = count(analysis, "DataExport");
        if (exports > 1 || analysis.children().size() != exports) {
            throw malformed();
        }
        if (exports == 0) {
            return "";
        }
        XmlElement export = analysis.child("DataExport");
        XmlElement version = export.child("OutputSchemaVersion");
        XmlElement destination = export.child("Destination");
        if (version == null || !OUTPUT_SCHEMA_VERSIONS.contains(version.text())
                || destination == null || export.children().size() != 2) {
            throw malformed();
        }
        XmlElement bucketDestination = destination.child("S3BucketDestination");
        if (bucketDestination == null || destination.children().size() != 1) {
            throw malformed();
        }
        XmlElement format = bucketDestination.child("Format");
        XmlElement bucket = bucketDestination.child("Bucket");
        XmlElement accountId = bucketDestination.child("BucketAccountId");
        XmlElement prefix = bucketDestination.child("Prefix");
        int expected = 2 + (accountId != null ? 1 : 0) + (prefix != null ? 1 : 0);
        if (format == null || !FORMATS.contains(format.text())
                || bucket == null || bucket.text().isBlank()
                || bucketDestination.children().size() != expected) {
            throw malformed();
        }

        XmlBuilder out = new XmlBuilder()
                .start("DataExport")
                .elem("OutputSchemaVersion", version.text())
                .start("Destination")
                .start("S3BucketDestination")
                .elem("Format", format.text());
        if (accountId != null) {
            out.elem("BucketAccountId", accountId.text());
        }
        out.elem("Bucket", bucket.text());
        if (prefix != null) {
            out.elem("Prefix", prefix.text());
        }
        return out.end("S3BucketDestination").end("Destination").end("DataExport").build();
    }

    private static long count(XmlElement parent, String childName) {
        return parent.children().stream().filter(child -> childName.equals(child.name())).count();
    }
}
