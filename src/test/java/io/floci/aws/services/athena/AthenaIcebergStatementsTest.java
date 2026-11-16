package io.floci.aws.services.athena;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.floci.aws.core.common.AwsException;
import io.floci.aws.core.common.RegionResolver;
import io.floci.aws.core.common.docker.ContainerReachableEndpoint;
import io.floci.aws.services.floci.duck.FlociDuckClient;
import io.floci.aws.services.glue.GlueService;
import io.floci.aws.services.glue.iceberg.GlueIcebergCatalog;
import io.floci.aws.services.glue.iceberg.GlueIcebergTokens;
import io.floci.aws.services.glue.iceberg.IcebergRestException;
import io.floci.aws.services.glue.model.Column;
import io.floci.aws.services.glue.model.StorageDescriptor;
import io.floci.aws.services.glue.model.Table;
import io.floci.aws.services.s3.S3Service;
import io.floci.aws.services.s3.model.S3Object;
import io.floci.aws.testing.InlineMocks;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Which statements write an Iceberg table and how they are pointed at the attached catalog, which Iceberg tables
 * {@code CREATE TABLE} accepts, and which snapshots and files {@code VACUUM} keeps.
 */
class AthenaIcebergStatementsTest {

    private static final long NOW = 1_000_000_000L;
    private static final long DAY = Duration.ofDays(1).toMillis();

    private final ObjectMapper mapper = new ObjectMapper();
    private GlueIcebergCatalog catalog;
    private GlueService glue;
    private S3Service s3;
    private GlueIcebergTokens tokens;
    private FlociDuckClient duck;
    private AthenaIcebergStatements statements;

    @BeforeEach
    void setUp() {
        catalog = Mockito.mock(GlueIcebergCatalog.class);
        glue = Mockito.mock(GlueService.class);
        s3 = Mockito.mock(S3Service.class);
        tokens = Mockito.mock(GlueIcebergTokens.class);
        duck = Mockito.mock(FlociDuckClient.class);
        ContainerReachableEndpoint endpoint = Mockito.mock(ContainerReachableEndpoint.class);
        when(endpoint.baseUrl()).thenReturn("http://floci:4566");
        statements = new AthenaIcebergStatements(catalog, glue, s3, tokens, endpoint, duck,
                new RegionResolver("eu-west-1", "111122223333"), mapper,
                Clock.fixed(Instant.ofEpochMilli(NOW), ZoneOffset.UTC));
    }

    private void glueTable(String database, String name, boolean iceberg) {
        Table table = new Table();
        table.setName(name);
        Map<String, String> parameters = new HashMap<>();
        if (iceberg) {
            parameters.put("table_type", "ICEBERG");
            parameters.put("metadata_location", "s3://lake/" + name + "/metadata/00000-a.metadata.json");
        }
        table.setParameters(parameters);
        when(glue.getTable(database, name)).thenReturn(table);
    }

    // ─── Writes ──────────────────────────────────────────────────────────────────

    @Test
    void theTargetOfEachWritingStatementIsFoundPastCommentsAndQuoting() {
        String insert = "-- load\n/* batch */ insert into Lake . \"Ev\"\"ents\" (a) SELECT 1";
        AthenaIcebergStatements.Target target = AthenaIcebergStatements.target(insert);
        assertEquals("lake", target.database());
        assertEquals("ev\"ents", target.table());
        assertEquals("Lake . \"Ev\"\"ents\"", insert.substring(target.start(), target.end()));

        assertEquals(new AthenaIcebergStatements.Target(null, "events", false, 12, 18),
                AthenaIcebergStatements.target("DELETE FROM events WHERE id = 1"));
        AthenaIcebergStatements.Target merge = AthenaIcebergStatements.target(
                "MERGE INTO awsdatacatalog.lake.events AS t USING src s ON t.id = s.id WHEN MATCHED THEN DELETE");
        assertEquals("lake", merge.database());
        assertEquals("events", merge.table());
        assertTrue(merge.catalogNamed());
        assertEquals("events", AthenaIcebergStatements.target("UPDATE events SET a = 1").table());

        assertNull(AthenaIcebergStatements.target("UPDATE other_catalog.lake.events SET a = 1"));
        assertNull(AthenaIcebergStatements.target("SELECT * FROM events"));
        assertNull(AthenaIcebergStatements.target("INSERT INTO"));
    }

    @Test
    void aWriteToAnIcebergTableGoesThroughTheAttachedCatalogWithATokenForTheCallingAccount() {
        glueTable("lake", "events", true);
        when(tokens.mint(eq("111122223333"), eq("eu-west-1"), any())).thenReturn("tok'en");

        AthenaIcebergStatements.IcebergWrite write = statements.write(
                "INSERT INTO events SELECT * FROM staging", "lake");

        assertEquals("INSERT INTO glue_iceberg.\"lake\".\"events\" SELECT * FROM staging", write.query());
        assertEquals("""
                INSTALL iceberg; LOAD iceberg;
                ATTACH '111122223333' AS glue_iceberg (TYPE iceberg, ENDPOINT 'http://floci:4566/iceberg', \
                AUTHORIZATION_TYPE 'oauth2', TOKEN 'tok''en', ACCESS_DELEGATION_MODE 'none');
                """, write.setupSql());
        statements.finished(write);
        verify(tokens).revoke("tok'en");
    }

    /**
     * In an S3 Tables context catalog, a write naming the default catalog goes through Glue's attachment, and one
     * naming no catalog is the context's, which the S3 Tables attachment serves.
     */
    @Test
    void inAnS3TablesContextOnlyAWriteNamingTheDefaultCatalogGoesThroughGlue() {
        glueTable("lake", "events", true);
        when(tokens.mint(eq("111122223333"), eq("eu-west-1"), any())).thenReturn("tok");

        assertNull(statements.write("INSERT INTO events VALUES (1)", "s3tablescatalog/bucket", "lake"));
        assertNull(statements.write("INSERT INTO lake.events VALUES (1)", "s3tablescatalog/bucket", "sales"));
        verify(tokens, never()).mint(anyString(), anyString(), any());
        AthenaIcebergStatements.IcebergWrite write = statements.write(
                "INSERT INTO awsdatacatalog.lake.events SELECT * FROM orders", "s3tablescatalog/bucket", "sales");
        assertEquals("INSERT INTO glue_iceberg.\"lake\".\"events\" SELECT * FROM orders", write.query());
        statements.finished(write);
    }

    @Test
    void aWriteToAHiveTableOrAMissingOneIsLeftAlone() {
        glueTable("lake", "hive_events", false);
        when(glue.getTable("lake", "missing")).thenThrow(new AwsException("EntityNotFoundException", "missing", 400));

        assertNull(statements.write("INSERT INTO lake.hive_events VALUES (1)", "default"));
        assertNull(statements.write("INSERT INTO missing VALUES (1)", "lake"));
        assertNull(statements.write("SELECT 1", "lake"));
        verify(tokens, never()).mint(anyString(), anyString(), any());
    }

    @Test
    void anS3TablesCatalogIsAttachedUnderItsOwnNameAndIsTheSearchPathAsTheContext() {
        when(tokens.mint(eq("111122223333"), eq("eu-west-1"), any())).thenReturn("tok");
        assertEquals("lake", AthenaIcebergStatements.s3TablesBucket("S3TablesCatalog/lake"));
        assertNull(AthenaIcebergStatements.s3TablesBucket("AwsDataCatalog"));
        assertNull(statements.s3TablesAttachment("SELECT 1", "AwsDataCatalog", "default"));

        AthenaIcebergStatements.S3TablesAttachment attachment = statements.s3TablesAttachment(
                "INSERT INTO \"s3tablescatalog/other\".ns.t SELECT * FROM orders", "s3tablescatalog/lake", "sales");

        assertEquals("INSTALL iceberg; LOAD iceberg;\n"
                + "ATTACH '111122223333:s3tablescatalog/lake' AS \"s3tablescatalog/lake\" (TYPE iceberg, ENDPOINT "
                + "'http://floci:4566/iceberg', AUTHORIZATION_TYPE 'oauth2', TOKEN 'tok', ACCESS_DELEGATION_MODE 'none');\n"
                + "ATTACH '111122223333:s3tablescatalog/other' AS \"s3tablescatalog/other\" (TYPE iceberg, ENDPOINT "
                + "'http://floci:4566/iceberg', AUTHORIZATION_TYPE 'oauth2', TOKEN 'tok', ACCESS_DELEGATION_MODE 'none');\n",
                attachment.attachSql());
        assertEquals("SET search_path = '\"s3tablescatalog/lake\".\"sales\",memory.main';\n", attachment.searchPathSql());
        statements.finished(attachment);
        verify(tokens).revoke("tok");
    }

    /**
     * A catalog only a comment or a string literal names is not attached, and a context namespace the bucket does
     * not have (Athena's {@code default} for a query naming none) is not the search path, which the engine would
     * refuse.
     */
    @Test
    void anS3TablesCatalogNamedOnlyInACommentOrLiteralIsNotAttachedNorAMissingNamespaceSearched() {
        when(tokens.mint(eq("111122223333"), eq("eu-west-1"), any())).thenReturn("tok");
        assertNull(statements.s3TablesAttachment("SELECT '\"s3tablescatalog/a\".ns.t' -- \"s3tablescatalog/b\".ns.t\n"
                + "/* \"s3tablescatalog/c\".ns.t */ FROM dual", "AwsDataCatalog", "default"));

        IcebergRestException missing = InlineMocks.inlineMock(IcebergRestException.class);
        when(missing.type()).thenReturn("NoSuchNamespaceException");
        when(catalog.loadNamespace("s3tablescatalog:lake", "default")).thenThrow(missing);
        AthenaIcebergStatements.S3TablesAttachment attachment = statements.s3TablesAttachment(
                "SELECT * FROM \"s3tablescatalog/lake\".sales.orders", "s3tablescatalog/lake", "default");
        assertTrue(attachment.attachSql().contains("AS \"s3tablescatalog/lake\""), attachment.attachSql());
        assertEquals("", attachment.searchPathSql());
    }

    // ─── CREATE TABLE ─────────────────────────────────────────────────────────────

    private static AthenaHiveDdl.CreateIcebergTable create(boolean ifNotExists, Map<String, String> properties) {
        return new AthenaHiveDdl.CreateIcebergTable(new AthenaHiveDdl.TableRef(null, "events"), ifNotExists,
                List.of(new Column("id", "bigint")), List.of(), "s3://lake/events/", properties);
    }

    @Test
    void createTableAsksTheCatalogForAVersion2TableWithItsProperties() {
        when(glue.getTable("lake", "events")).thenThrow(new AwsException("EntityNotFoundException", "missing", 400));

        statements.createTable("lake", create(false, Map.of("Format", "parquet",
                "vacuum_max_snapshot_age_seconds", "60")));

        ArgumentCaptor<JsonNode> request = ArgumentCaptor.forClass(JsonNode.class);
        verify(catalog).createTable(eq(":"), eq("lake"), request.capture());
        assertEquals("events", request.getValue().path("name").asText());
        assertEquals("s3://lake/events/", request.getValue().path("location").asText());
        assertEquals(Map.of("format", "parquet", "vacuum_max_snapshot_age_seconds", "60", "format-version", "2"),
                mapper.convertValue(request.getValue().path("properties"), Map.class));
    }

    @Test
    void createTableRefusesPropertiesAthenaDoesNotAllowAndFormatsFlociCannotWrite() {
        AwsException unknown = assertThrows(AwsException.class,
                () -> statements.createTable("lake", create(false, Map.of("write.format.default", "parquet"))));
        assertTrue(unknown.getMessage().contains("write.format.default"), unknown.getMessage());

        AwsException orc = assertThrows(AwsException.class,
                () -> statements.createTable("lake", create(false, Map.of("format", "orc"))));
        assertEquals("InvalidRequestException", orc.getErrorCode());
        verify(catalog, never()).createTable(anyString(), anyString(), any());
    }

    @Test
    void createTableIfNotExistsLeavesAnExistingTableAlone() {
        glueTable("lake", "events", true);

        statements.createTable("lake", create(true, Map.of()));

        verify(catalog, never()).createTable(anyString(), anyString(), any());
    }

    // ─── VACUUM ───────────────────────────────────────────────────────────────────

    private static String snapshot(long id, Long parent, long timestamp) {
        return "{\"snapshot-id\":" + id + (parent == null ? "" : ",\"parent-snapshot-id\":" + parent)
                + ",\"timestamp-ms\":" + timestamp + ",\"manifest-list\":\"s3://lake/events/metadata/snap-" + id
                + ".avro\"}";
    }

    @Test
    void vacuumKeepsTagsTheNewestSnapshotsOfEachBranchAndAnythingNewerThanTheCutoff() throws Exception {
        JsonNode metadata = mapper.readTree("{\"snapshots\":[" + String.join(",",
                snapshot(1, null, 100), snapshot(2, 1L, 200), snapshot(3, 2L, 300), snapshot(4, null, 50),
                snapshot(5, null, 400)) + "],\"refs\":{\"main\":{\"snapshot-id\":3,\"type\":\"branch\"},"
                + "\"v1\":{\"snapshot-id\":1,\"type\":\"tag\"}}}");

        assertEquals(Set.of(3L, 1L, 5L), AthenaIcebergStatements.retainedSnapshots(metadata, 250, 1));
        assertEquals(Set.of(3L, 2L, 1L, 5L), AthenaIcebergStatements.retainedSnapshots(metadata, 250, 2));
        assertEquals(Set.of(1L, 2L, 3L, 4L, 5L), AthenaIcebergStatements.retainedSnapshots(metadata, 0, 1));
    }

    @Test
    void vacuumExpiresOldSnapshotsAndDeletesOnlyFilesNothingRetainedReferences() throws Exception {
        glueTable("lake", "events", true);
        long old = NOW - 10 * DAY;
        String base = "s3://lake/events/metadata/";
        ObjectNode before = (ObjectNode) mapper.readTree("{\"table-uuid\":\"u\",\"location\":\"s3://lake/events\","
                + "\"properties\":{},\"snapshots\":[" + snapshot(1, null, old) + "," + snapshot(2, 1L, old + 1)
                + "],\"refs\":{\"main\":{\"snapshot-id\":2,\"type\":\"branch\"}},\"metadata-log\":[]}");
        ObjectNode after = before.deepCopy();
        after.withArray("snapshots").remove(0);
        // A retained manifest list and a statistics file named with the other S3 schemes are still referenced.
        ((ObjectNode) after.withArray("snapshots").get(0)).put("manifest-list",
                "s3a://lake/events/metadata/snap-2.avro");
        after.withArray("statistics").addObject().put("snapshot-id", 2)
                .put("statistics-path", "s3n://lake/events/metadata/stats-2.puffin");
        after.withArray("metadata-log").addObject().put("metadata-file", base + "00001-b.metadata.json");
        when(catalog.loadTable(":", "lake", "events", null)).thenReturn(loaded(base + "00001-b.metadata.json",
                before));
        when(catalog.commitTable(eq(":"), eq("lake"), eq("events"), any()))
                .thenReturn(loaded(base + "00002-c.metadata.json", after));
        when(s3.getBucketOwnerAccountId("lake")).thenReturn("111122223333");
        when(duck.query(anyString(), anyString(), eq("111122223333"))).thenReturn(List.of(Map.of(
                "manifest_path", "s3a://lake/events/metadata/m2.avro",
                "file_path", "s3://lake/events/data/kept.parquet")));
        when(s3.listObjectsWithPrefixes(eq("lake"), eq("events/"), isNull(), anyInt(), isNull(), isNull()))
                .thenReturn(new S3Service.ListObjectsResult(List.of(
                        object("events/metadata/00000-a.metadata.json", old),
                        object("events/metadata/00001-b.metadata.json", old),
                        object("events/metadata/00002-c.metadata.json", NOW),
                        object("events/metadata/snap-1.avro", old),
                        object("events/metadata/snap-2.avro", old),
                        object("events/metadata/stats-2.puffin", old),
                        object("events/metadata/m1.avro", old),
                        object("events/metadata/m2.avro", old),
                        object("events/data/expired.parquet", old),
                        object("events/data/kept.parquet", old),
                        object("events/data/in-flight.parquet", NOW - 1000)), List.of(), false, null));

        statements.vacuum(new AthenaHiveDdl.TableRef("lake", "events"), "default");

        ArgumentCaptor<JsonNode> commit = ArgumentCaptor.forClass(JsonNode.class);
        verify(catalog).commitTable(eq(":"), eq("lake"), eq("events"), commit.capture());
        assertEquals(mapper.readTree("""
                {"requirements":[{"type":"assert-table-uuid","uuid":"u"},
                  {"type":"assert-ref-snapshot-id","ref":"main","snapshot-id":2}],
                 "updates":[{"action":"remove-snapshots","snapshot-ids":[1]}]}
                """), mapper.readTree(commit.getValue().toString()));
        ArgumentCaptor<String> deleted = ArgumentCaptor.forClass(String.class);
        verify(s3, Mockito.atLeastOnce()).deleteObject(eq("lake"), deleted.capture());
        assertEquals(Set.of("events/metadata/00000-a.metadata.json", "events/metadata/snap-1.avro",
                "events/metadata/m1.avro", "events/data/expired.parquet"), Set.copyOf(deleted.getAllValues()));
    }

    @Test
    void aDroppedIcebergTablesFilesAreDeletedOnlyFromTheCallingAccountsBucket() {
        when(s3.getBucketOwnerAccountId("ours")).thenReturn("111122223333");
        when(s3.getBucketOwnerAccountId("theirs")).thenReturn("999988887777");
        when(s3.listObjectsWithPrefixes(eq("ours"), eq("events/"), isNull(), anyInt(), isNull(), isNull()))
                .thenReturn(new S3Service.ListObjectsResult(List.of(object("events/data/a.parquet", NOW)), List.of(),
                        false, null));

        statements.purge(tableAt("s3://ours/events"));
        statements.purge(tableAt("s3://theirs/events/"));

        verify(s3).deleteObject("ours", "events/data/a.parquet");
        verify(s3, never()).listObjectsWithPrefixes(eq("theirs"), any(), any(), anyInt(), any(), any());
    }

    private static Table tableAt(String location) {
        Table table = new Table();
        StorageDescriptor descriptor = new StorageDescriptor();
        descriptor.setLocation(location);
        table.setStorageDescriptor(descriptor);
        return table;
    }

    @Test
    void whileFlocisOwnWriteToTheTableRunsVacuumLeavesItsFilesForTheNextRun() throws Exception {
        glueTable("lake", "events", true);
        when(tokens.mint(anyString(), anyString(), any())).thenReturn("t");
        ObjectNode metadata = (ObjectNode) mapper.readTree("{\"table-uuid\":\"u\",\"location\":\"s3://lake/events\","
                + "\"properties\":{},\"snapshots\":[],\"refs\":{},\"metadata-log\":[]}");
        when(catalog.loadTable(":", "lake", "events", null)).thenReturn(loaded(
                "s3://lake/events/metadata/00000-a.metadata.json", metadata));
        when(s3.getBucketOwnerAccountId("lake")).thenReturn("111122223333");
        when(s3.listObjectsWithPrefixes(eq("lake"), eq("events/"), isNull(), anyInt(), isNull(), isNull()))
                .thenReturn(new S3Service.ListObjectsResult(List.of(
                        object("events/data/staged.parquet", NOW - 10 * DAY)), List.of(), false, null));

        AthenaIcebergStatements.IcebergWrite write = statements.write("INSERT INTO events VALUES (1)", "lake");
        statements.vacuum(new AthenaHiveDdl.TableRef("lake", "events"), "default");
        verify(s3, never()).deleteObject(anyString(), anyString());

        statements.finished(write);
        statements.vacuum(new AthenaHiveDdl.TableRef("lake", "events"), "default");
        verify(s3).deleteObject("lake", "events/data/staged.parquet");
    }

    @Test
    void vacuumOfAHiveTableIsRefused() {
        glueTable("lake", "hive_events", false);

        AwsException e = assertThrows(AwsException.class,
                () -> statements.vacuum(new AthenaHiveDdl.TableRef(null, "hive_events"), "lake"));

        assertTrue(e.getMessage().contains("only for Apache Iceberg tables"), e.getMessage());
    }

    private ObjectNode loaded(String metadataLocation, JsonNode metadata) {
        ObjectNode result = mapper.createObjectNode();
        result.put("metadata-location", metadataLocation);
        result.set("metadata", metadata);
        return result;
    }

    private static S3Object object(String key, long lastModified) {
        S3Object object = new S3Object();
        object.setKey(key);
        object.setLastModified(Instant.ofEpochMilli(lastModified));
        return object;
    }
}
