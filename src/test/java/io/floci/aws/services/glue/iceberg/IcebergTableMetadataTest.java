package io.floci.aws.services.glue.iceberg;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class IcebergTableMetadataTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** The commit DuckDB 1.5.4 sends after a staged create of {@code (id BIGINT, name VARCHAR)}. */
    private static final String DUCKDB_CREATE_COMMIT = """
            {"requirements":[{"type":"assert-create"}],"updates":[
             {"action":"assign-uuid","uuid":"11111111-2222-3333-4444-555555555555"},
             {"action":"upgrade-format-version","format-version":2},
             {"action":"add-schema","last-column-id":2,"schema":{"type":"struct","schema-id":0,"fields":[
               {"id":1,"name":"id","required":false,"type":"long"},
               {"id":2,"name":"name","required":false,"type":"string"}],"identifier-field-ids":[]}},
             {"action":"add-spec","spec":{"spec-id":0,"fields":[]}},
             {"action":"set-default-spec","spec-id":0},
             {"action":"add-sort-order","sort-order":{"order-id":0,"fields":[]}},
             {"action":"set-default-sort-order","sort-order-id":0},
             {"action":"set-location","location":"s3://lake-bucket/lake/events"},
             {"action":"set-properties","updates":{"format-version":"2"}},
             {"action":"set-current-schema","schema-id":0}],
             "identifier":{"name":"events","namespace":["lake"]}}
            """;

    private static JsonNode json(String text) throws Exception {
        return MAPPER.readTree(text);
    }

    private static ObjectNode created() throws Exception {
        JsonNode commit = json(DUCKDB_CREATE_COMMIT);
        IcebergTableMetadata.check(null, commit.path("requirements"));
        ObjectNode metadata = IcebergTableMetadata.empty(1_000L);
        IcebergTableMetadata.apply(metadata, commit.path("updates"), 2_000L);
        return metadata;
    }

    private static JsonNode snapshotCommit(long snapshotId, Long parentRefId, long sequence) throws Exception {
        return json(IcebergCommits.snapshotCommit(snapshotId, parentRefId, sequence));
    }

    @Test
    void duckdbsStagedCreateCommitBuildsTheTablesMetadata() throws Exception {
        ObjectNode metadata = created();

        assertEquals("11111111-2222-3333-4444-555555555555", metadata.path("table-uuid").asText());
        assertEquals(2, metadata.path("format-version").asInt());
        assertEquals(0, metadata.path("current-schema-id").asInt());
        assertEquals(2, metadata.path("last-column-id").asInt());
        assertEquals("long", IcebergTableMetadata.currentSchema(metadata).path("fields").get(0).path("type").asText());
        assertEquals(0, metadata.path("default-spec-id").asInt());
        assertEquals(999, metadata.path("last-partition-id").asInt());
        assertEquals(0, metadata.path("default-sort-order-id").asInt());
        assertEquals("s3://lake-bucket/lake/events", metadata.path("location").asText());
        assertEquals("2", metadata.path("properties").path("format-version").asText());
        assertEquals(2_000L, metadata.path("last-updated-ms").asLong());
    }

    @Test
    void aSnapshotOnMainBecomesTheCurrentSnapshotAndIsLogged() throws Exception {
        ObjectNode metadata = created();
        JsonNode commit = snapshotCommit(7L, null, 1);

        IcebergTableMetadata.check(metadata, commit.path("requirements"));
        IcebergTableMetadata.apply(metadata, commit.path("updates"), 6_000L);

        assertEquals(7L, metadata.path("current-snapshot-id").asLong());
        assertEquals(1L, metadata.path("last-sequence-number").asLong());
        assertEquals(7L, metadata.path("refs").path("main").path("snapshot-id").asLong());
        assertEquals("branch", metadata.path("refs").path("main").path("type").asText());
        assertEquals(1, metadata.path("snapshots").size());
        assertEquals(5_000L, metadata.path("snapshot-log").get(0).path("timestamp-ms").asLong());
    }

    @Test
    void aCommitBasedOnAStaleSnapshotOrAnExistingTableFails() throws Exception {
        ObjectNode metadata = created();
        JsonNode first = snapshotCommit(7L, null, 1);
        IcebergTableMetadata.apply(metadata, first.path("updates"), 6_000L);

        IcebergRestException concurrentAppend = assertThrows(IcebergRestException.class,
                () -> IcebergTableMetadata.check(metadata, snapshotCommit(8L, null, 2).path("requirements")));
        IcebergRestException staleParent = assertThrows(IcebergRestException.class,
                () -> IcebergTableMetadata.check(metadata, snapshotCommit(9L, 6L, 2).path("requirements")));
        IcebergRestException existing = assertThrows(IcebergRestException.class,
                () -> IcebergTableMetadata.check(metadata, json(DUCKDB_CREATE_COMMIT).path("requirements")));

        assertEquals("CommitFailedException", concurrentAppend.type());
        assertEquals(409, staleParent.code());
        assertEquals("CommitFailedException", existing.type());
        IcebergTableMetadata.check(metadata, snapshotCommit(8L, 7L, 2).path("requirements"));
    }

    @Test
    void propertiesSnapshotsAndRefsCanBeRemoved() throws Exception {
        ObjectNode metadata = created();
        IcebergTableMetadata.apply(metadata, snapshotCommit(7L, null, 1).path("updates"), 6_000L);

        IcebergTableMetadata.apply(metadata, json("""
                [{"action":"set-properties","updates":{"write.format.default":"parquet"}},
                 {"action":"remove-properties","removals":["format-version"]},
                 {"action":"remove-snapshot-ref","ref-name":"main"},
                 {"action":"remove-snapshots","snapshot-ids":[7]}]
                """), 7_000L);

        assertEquals("parquet", metadata.path("properties").path("write.format.default").asText());
        assertTrue(metadata.path("properties").path("format-version").isMissingNode());
        assertTrue(metadata.path("refs").path("main").isMissingNode());
        assertEquals(0, metadata.path("snapshots").size());
        assertEquals(0, metadata.path("snapshot-log").size());
    }

    @Test
    void removingMainOrItsSnapshotLeavesNoCurrentSnapshotAndNoDanglingRef() throws Exception {
        ObjectNode metadata = created();
        IcebergTableMetadata.apply(metadata, snapshotCommit(7L, null, 1).path("updates"), 6_000L);
        IcebergTableMetadata.apply(metadata,
                json("[{\"action\":\"set-snapshot-ref\",\"ref-name\":\"audit\",\"type\":\"tag\",\"snapshot-id\":7}]"),
                6_500L);

        IcebergTableMetadata.apply(metadata, json("[{\"action\":\"remove-snapshots\",\"snapshot-ids\":[7]}]"), 7_000L);

        assertEquals(-1L, metadata.path("current-snapshot-id").asLong());
        assertTrue(metadata.path("refs").isEmpty(), metadata.path("refs").toString());
        ObjectNode other = created();
        IcebergTableMetadata.apply(other, snapshotCommit(7L, null, 1).path("updates"), 6_000L);
        IcebergTableMetadata.apply(other, json("[{\"action\":\"remove-snapshot-ref\",\"ref-name\":\"main\"}]"), 7_000L);
        assertEquals(-1L, other.path("current-snapshot-id").asLong());
    }

    @Test
    void aRemovedSnapshotsStatisticsFilesGoWithIt() throws Exception {
        ObjectNode metadata = created();
        IcebergTableMetadata.apply(metadata, snapshotCommit(7L, null, 1).path("updates"), 6_000L);
        IcebergTableMetadata.apply(metadata, snapshotCommit(8L, 7L, 2).path("updates"), 6_100L);
        IcebergTableMetadata.apply(metadata, json("""
                [{"action":"set-statistics","statistics":{"snapshot-id":7,"statistics-path":"s3://b/t/7.stats",
                  "file-size-in-bytes":1,"file-footer-size-in-bytes":1,"blob-metadata":[]}},
                 {"action":"set-statistics","statistics":{"snapshot-id":8,"statistics-path":"s3://b/t/8.stats",
                  "file-size-in-bytes":1,"file-footer-size-in-bytes":1,"blob-metadata":[]}},
                 {"action":"set-partition-statistics","partition-statistics":{"snapshot-id":7,
                  "statistics-path":"s3://b/t/7.pstats","file-size-in-bytes":1}}]
                """), 6_200L);

        IcebergTableMetadata.apply(metadata, json("[{\"action\":\"remove-snapshots\",\"snapshot-ids\":[7]}]"), 7_000L);

        assertEquals(1, metadata.path("statistics").size());
        assertEquals(8L, metadata.path("statistics").get(0).path("snapshot-id").asLong());
        assertTrue(metadata.path("partition-statistics").isEmpty(), metadata.path("partition-statistics").toString());
    }

    @Test
    void aSchemaSpecOrSortOrderIdTheTableDoesNotHaveIsRefused() throws Exception {
        ObjectNode metadata = created();

        for (String update : new String[] {"{\"action\":\"set-current-schema\",\"schema-id\":999}",
                "{\"action\":\"set-current-schema\",\"schema-id\":-1}",
                "{\"action\":\"set-default-spec\",\"spec-id\":5}",
                "{\"action\":\"set-default-sort-order\",\"sort-order-id\":-1}"}) {
            IcebergRestException e = assertThrows(IcebergRestException.class,
                    () -> IcebergTableMetadata.apply(metadata, json("[" + update + "]"), 8_000L), update);
            assertEquals("BadRequestException", e.type(), update);
        }
        assertEquals(0, metadata.path("current-schema-id").asInt());
    }

    @Test
    void anAddedSchemaReusesAnIdenticalOneAndNeverRewritesAnExistingId() throws Exception {
        ObjectNode metadata = created();
        String changed = "[{\"action\":\"add-schema\",\"schema\":{\"type\":\"struct\",\"schema-id\":0,\"fields\":["
                + "{\"id\":1,\"name\":\"id\",\"required\":false,\"type\":\"long\"},"
                + "{\"id\":2,\"name\":\"name\",\"required\":false,\"type\":\"string\"},"
                + "{\"id\":3,\"name\":\"tag\",\"required\":false,\"type\":\"string\"}],\"identifier-field-ids\":[]}},"
                + "{\"action\":\"set-current-schema\",\"schema-id\":-1}]";

        IcebergTableMetadata.apply(metadata, json(changed), 8_000L);
        IcebergTableMetadata.apply(metadata, json(changed), 9_000L);

        assertEquals(2, metadata.path("schemas").size());
        assertEquals(2, metadata.path("schemas").get(0).path("fields").size(), "schema 0 is left as it was");
        assertEquals(1, metadata.path("current-schema-id").asInt());
        assertEquals(3, metadata.path("last-column-id").asInt());
    }

    @Test
    void aSnapshotAfterExpiryStillComesAfterEverySequenceNumberUsed() throws Exception {
        ObjectNode metadata = created();
        IcebergTableMetadata.apply(metadata, snapshotCommit(7L, null, 1).path("updates"), 6_000L);
        IcebergTableMetadata.apply(metadata, snapshotCommit(8L, 7L, 2).path("updates"), 6_100L);
        IcebergTableMetadata.apply(metadata, json("[{\"action\":\"remove-snapshots\",\"snapshot-ids\":[7]}]"), 6_200L);

        IcebergRestException e = assertThrows(IcebergRestException.class, () -> IcebergTableMetadata.apply(metadata,
                snapshotCommit(9L, 8L, 2).path("updates"), 6_300L));

        assertEquals("BadRequestException", e.type());
        IcebergTableMetadata.apply(metadata, snapshotCommit(9L, 8L, 3).path("updates"), 6_400L);
        assertEquals(3L, metadata.path("last-sequence-number").asLong());
    }

    @Test
    void aVersionThreeSnapshotTakesTheNextRowIdsAndAdvancesThem() throws Exception {
        ObjectNode metadata = created();
        IcebergTableMetadata.apply(metadata, json("[{\"action\":\"upgrade-format-version\",\"format-version\":3}]"),
                6_000L);

        IcebergTableMetadata.apply(metadata, json("""
                [{"action":"add-snapshot","snapshot":{"snapshot-id":7,"sequence-number":1,"timestamp-ms":5000,
                  "manifest-list":"s3://b/m.avro","summary":{"operation":"append"},"added-rows":10}}]
                """), 7_000L);

        assertEquals(0L, metadata.path("snapshots").get(0).path("first-row-id").asLong());
        assertEquals(10L, metadata.path("next-row-id").asLong());
    }

    @Test
    void aCreateRequestBecomesTheSameMetadataItsUpdatesWould() throws Exception {
        ObjectNode metadata = IcebergTableMetadata.forCreate(json("""
                {"name":"events","schema":{"type":"struct","schema-id":0,"fields":[
                  {"id":1,"name":"id","required":true,"type":"long"},
                  {"id":2,"name":"tags","required":false,"type":{"type":"list","element-id":3,"element":"string",
                   "element-required":false}}]},
                 "partition-spec":{"spec-id":0,"type":"struct","fields":[
                  {"name":"id_bucket","transform":"bucket[4]","source-id":1,"field-id":1000}]},
                 "properties":{"format-version":"2","owner":"lake"}}
                """), "s3://lake-bucket/lake/events", 3_000L);

        assertEquals(3, metadata.path("last-column-id").asInt());
        assertEquals(1000, metadata.path("last-partition-id").asInt());
        assertTrue(metadata.path("partition-specs").get(0).path("type").isMissingNode());
        assertEquals("lake", metadata.path("properties").path("owner").asText());
        assertEquals("s3://lake-bucket/lake/events", metadata.path("location").asText());
    }

    @Test
    void anUpdateTheCatalogDoesNotApplyIsRefused() throws Exception {
        ObjectNode metadata = created();

        IcebergRestException e = assertThrows(IcebergRestException.class, () -> IcebergTableMetadata.apply(metadata,
                json("[{\"action\":\"add-encryption-key\",\"encryption-key\":{}}]"), 1L));

        assertEquals("BadRequestException", e.type());
        assertEquals(400, e.code());
    }

    @Test
    void hiveTypesSpellOutNestedIcebergTypes() throws Exception {
        assertEquals("bigint", IcebergTypes.hiveType(json("\"long\"")));
        assertEquals("decimal(10,2)", IcebergTypes.hiveType(json("\"decimal(10, 2)\"")));
        assertEquals("struct<a:int,b:array<string>>", IcebergTypes.hiveType(json("""
                {"type":"struct","fields":[{"id":1,"name":"a","type":"int"},
                 {"id":2,"name":"b","type":{"type":"list","element-id":3,"element":"string"}}]}
                """)));
        assertEquals("map<string,timestamp>", IcebergTypes.hiveType(json("""
                {"type":"map","key-id":1,"key":"string","value-id":2,"value":"timestamptz"}
                """)));
    }
}
