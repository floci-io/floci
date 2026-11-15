package io.floci.aws.services.glue.iceberg;

/** The commit bodies the Iceberg catalog tests send, as an Iceberg writer builds them. */
final class IcebergCommits {

    private IcebergCommits() {
    }

    /**
     * An append committing snapshot {@code snapshotId} to {@code main}, required to find {@code main} at
     * {@code parentSnapshotId} (absent when null), which is also the new snapshot's parent.
     */
    static String snapshotCommit(long snapshotId, Long parentSnapshotId, long sequenceNumber) {
        String parent = parentSnapshotId == null ? "" : "\"parent-snapshot-id\":" + parentSnapshotId + ",";
        return """
                {"requirements":[{"type":"assert-ref-snapshot-id","ref":"main","snapshot-id":%s}],"updates":[
                 {"action":"add-snapshot","snapshot":{"snapshot-id":%d,%s"sequence-number":%d,"timestamp-ms":5000,
                  "manifest-list":"s3://lake-bucket/lake/events/metadata/snap-%d.avro",
                  "summary":{"operation":"append"},"schema-id":0}},
                 {"action":"set-snapshot-ref","ref-name":"main","type":"branch","snapshot-id":%d}]}
                """.formatted(parentSnapshotId == null ? "null" : parentSnapshotId, snapshotId, parent, sequenceNumber,
                snapshotId, snapshotId);
    }
}
