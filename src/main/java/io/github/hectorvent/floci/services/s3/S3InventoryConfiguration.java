package io.github.hectorvent.floci.services.s3;

import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.core.common.XmlBuilder;
import io.github.hectorvent.floci.core.common.XmlParser;
import io.github.hectorvent.floci.core.common.XmlParser.XmlElement;

import java.util.List;

/**
 * A parsed {@code InventoryConfiguration} request body, reduced to its id and a canonical
 * serialization of its contents.
 *
 * <p>The body is re-serialized rather than stored verbatim, so that what a later
 * GetBucketInventoryConfiguration or ListBucketInventoryConfigurations returns is built by floci
 * instead of being whatever XML the caller sent, and so that the same stored form can be wrapped
 * in either response.
 */
record S3InventoryConfiguration(String id, String innerXml) {

    private static final String ROOT = "InventoryConfiguration";

    private static final List<String> ENABLED = List.of("true", "false");
    private static final List<String> INCLUDED_OBJECT_VERSIONS = List.of("All", "Current");
    private static final List<String> FREQUENCIES = List.of("Daily", "Weekly");
    private static final List<String> FORMATS = List.of("CSV", "ORC", "Parquet");
    private static final List<String> FIELDS = List.of(
            "Size", "LastModifiedDate", "StorageClass", "ETag", "IsMultipartUploaded",
            "ReplicationStatus", "EncryptionStatus", "ObjectLockRetainUntilDate", "ObjectLockMode",
            "ObjectLockLegalHoldStatus", "IntelligentTieringAccessTier", "BucketKeyStatus",
            "ChecksumAlgorithm", "ObjectAccessControlList", "ObjectOwner");

    /** AWS reports a body that does not match the published schema this way. */
    private static AwsException malformed() {
        return new AwsException("MalformedXML",
                "The XML you provided was not well-formed or did not validate against our published schema", 400);
    }

    static S3InventoryConfiguration parse(String xml) {
        if (xml == null || xml.isBlank() || !ROOT.equals(XmlParser.rootElementName(xml))) {
            throw malformed();
        }
        XmlElement root = XmlParser.extractElementTree(xml, ROOT);
        if (root == null) {
            throw malformed();
        }

        // The configuration is one each of Id, IsEnabled, IncludedObjectVersions, Schedule and
        // Destination, with Filter and OptionalFields at most once, so anything else under the
        // root is a body AWS would not have accepted.
        long filters = count(root, "Filter");
        long optionalFields = count(root, "OptionalFields");
        if (count(root, "Id") != 1 || count(root, "IsEnabled") != 1
                || count(root, "IncludedObjectVersions") != 1 || count(root, "Schedule") != 1
                || count(root, "Destination") != 1 || filters > 1 || optionalFields > 1
                || root.children().size() != 5 + filters + optionalFields) {
            throw malformed();
        }

        String id = root.child("Id").text();
        String enabled = root.child("IsEnabled").text();
        String versions = root.child("IncludedObjectVersions").text();
        if (id == null || id.isBlank() || !ENABLED.contains(enabled)
                || !INCLUDED_OBJECT_VERSIONS.contains(versions)) {
            throw malformed();
        }

        XmlBuilder inner = new XmlBuilder()
                .elem("Id", id)
                .elem("IsEnabled", enabled)
                .start("Destination").raw(destinationXml(root.child("Destination"))).end("Destination")
                .start("Schedule").raw(scheduleXml(root.child("Schedule"))).end("Schedule");
        if (filters == 1) {
            inner.start("Filter").raw(filterXml(root.child("Filter"))).end("Filter");
        }
        inner.elem("IncludedObjectVersions", versions);
        if (optionalFields == 1) {
            inner.start("OptionalFields").raw(optionalFieldsXml(root.child("OptionalFields"))).end("OptionalFields");
        }
        return new S3InventoryConfiguration(id, inner.build());
    }

    /**
     * Serializes the destination. An inventory report lands in exactly one S3 bucket, named by
     * ARN with a required format, and the account id, prefix and encryption are optional.
     */
    private static String destinationXml(XmlElement destination) {
        XmlElement bucketDestination = destination.child("S3BucketDestination");
        if (bucketDestination == null || destination.children().size() != 1) {
            throw malformed();
        }
        XmlElement accountId = bucketDestination.child("AccountId");
        XmlElement bucket = bucketDestination.child("Bucket");
        XmlElement format = bucketDestination.child("Format");
        XmlElement prefix = bucketDestination.child("Prefix");
        XmlElement encryption = bucketDestination.child("Encryption");
        int expected = 2 + (accountId != null ? 1 : 0) + (prefix != null ? 1 : 0)
                + (encryption != null ? 1 : 0);
        if (bucket == null || bucket.text().isBlank()
                || format == null || !FORMATS.contains(format.text())
                || bucketDestination.children().size() != expected) {
            throw malformed();
        }

        XmlBuilder out = new XmlBuilder().start("S3BucketDestination");
        out.elem("Format", format.text());
        if (accountId != null) {
            out.elem("AccountId", accountId.text());
        }
        out.elem("Bucket", bucket.text());
        if (prefix != null) {
            out.elem("Prefix", prefix.text());
        }
        if (encryption != null) {
            out.start("Encryption").raw(encryptionXml(encryption)).end("Encryption");
        }
        return out.end("S3BucketDestination").build();
    }

    /** The report is encrypted with exactly one of SSE-S3 or SSE-KMS, and SSE-KMS names its key. */
    private static String encryptionXml(XmlElement encryption) {
        if (encryption.children().size() != 1) {
            throw malformed();
        }
        XmlElement method = encryption.children().getFirst();
        return switch (method.name()) {
            case "SSE-S3" -> {
                if (!method.children().isEmpty()) {
                    throw malformed();
                }
                yield "<SSE-S3/>";
            }
            case "SSE-KMS" -> {
                XmlElement keyId = method.child("KeyId");
                if (keyId == null || keyId.text().isBlank() || method.children().size() != 1) {
                    throw malformed();
                }
                yield new XmlBuilder().start("SSE-KMS").elem("KeyId", keyId.text()).end("SSE-KMS").build();
            }
            default -> throw malformed();
        };
    }

    private static String scheduleXml(XmlElement schedule) {
        XmlElement frequency = schedule.child("Frequency");
        if (frequency == null || !FREQUENCIES.contains(frequency.text()) || schedule.children().size() != 1) {
            throw malformed();
        }
        return new XmlBuilder().elem("Frequency", frequency.text()).build();
    }

    /** An inventory filter is a prefix and nothing else. */
    private static String filterXml(XmlElement filter) {
        XmlElement prefix = filter.child("Prefix");
        if (prefix == null || filter.children().size() != 1) {
            throw malformed();
        }
        return new XmlBuilder().elem("Prefix", prefix.text()).build();
    }

    /** Every optional field has to be one AWS defines, or the stored report shape would be wrong. */
    private static String optionalFieldsXml(XmlElement optionalFields) {
        XmlBuilder out = new XmlBuilder();
        for (XmlElement field : optionalFields.children()) {
            if (!"Field".equals(field.name()) || !FIELDS.contains(field.text())) {
                throw malformed();
            }
            out.elem("Field", field.text());
        }
        return out.build();
    }

    private static long count(XmlElement parent, String childName) {
        return parent.children().stream().filter(child -> childName.equals(child.name())).count();
    }
}
