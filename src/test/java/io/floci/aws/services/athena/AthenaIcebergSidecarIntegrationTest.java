package io.floci.aws.services.athena;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.floci.aws.core.common.RegionResolver;
import io.floci.aws.core.common.RequestScopes;
import io.floci.aws.core.storage.StorageFactory;
import io.floci.aws.services.athena.model.QueryExecution;
import io.floci.aws.services.athena.model.QueryExecutionContext;
import io.floci.aws.services.athena.model.QueryExecutionState;
import io.floci.aws.services.athena.model.ResultConfiguration;
import io.floci.aws.services.floci.duck.FlociDuckClient;
import io.floci.aws.services.glue.GlueCatalogService;
import io.floci.aws.services.glue.GlueService;
import io.floci.aws.services.glue.iceberg.GlueIcebergCatalog;
import io.floci.aws.services.glue.model.Database;
import io.floci.aws.services.glue.model.Table;
import io.floci.aws.services.s3.S3Service;
import io.floci.aws.services.s3tables.S3TablesService;
import io.floci.aws.services.s3tables.model.Namespace;
import io.floci.aws.services.s3tables.model.S3Table;
import io.floci.aws.services.s3tables.model.TableBucket;
import io.floci.aws.testing.SidecarContainersProfile;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;
import io.vertx.core.Vertx;
import jakarta.inject.Inject;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import static org.awaitility.Awaitility.await;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * Athena's Iceberg statements run end to end through the real floci-duck sidecar: {@code CREATE TABLE} makes a Glue
 * Iceberg table, {@code INSERT}, {@code UPDATE}, {@code DELETE} and {@code MERGE} write it through the sidecar's
 * attachment of Glue's Iceberg REST endpoint, each commit moving the Glue table's {@code metadata_location}, and
 * {@code VACUUM} expires old snapshots and removes files nothing references.
 */
@QuarkusTest
@TestProfile(SidecarContainersProfile.class)
class AthenaIcebergSidecarIntegrationTest {

    private static final String ACCOUNT = "000000000000";
    private static final String REGION = "us-east-1";

    @Inject
    StorageFactory storageFactory;

    @Inject
    FlociDuckClient duck;

    @Inject
    GlueService glue;

    @Inject
    S3Service s3;

    @Inject
    Vertx vertx;

    @Inject
    GlueViewDdlBuilder ddlBuilder;

    @Inject
    RegionResolver regionResolver;

    @Inject
    AthenaIcebergStatements icebergStatements;

    @Inject
    GlueIcebergCatalog catalog;

    @Inject
    S3TablesService s3Tables;

    @Inject
    GlueCatalogService catalogs;

    private AthenaService athena;
    private String bucket;
    private String database;

    @BeforeAll
    static void requireDocker() {
        SidecarContainersProfile.requireDocker();
    }

    @BeforeEach
    void createDatabase() {
        athena = new AthenaService(storageFactory, duck, glue, s3, AthenaTestConfigs.realQueries(), vertx, ddlBuilder, regionResolver,
                icebergStatements);
        long suffix = System.nanoTime();
        bucket = "athena-iceberg-" + suffix;
        database = "lake_" + suffix;
        s3.createBucket(bucket, REGION);
        Database lake = new Database(database);
        lake.setLocationUri("s3://" + bucket + "/lake");
        RequestScopes.runAs(ACCOUNT, REGION, () -> glue.createDatabase(lake));
    }

    @AfterEach
    void dropDatabase() {
        RequestScopes.runAs(ACCOUNT, REGION, () -> {
            for (Table table : glue.getTables(database)) {
                glue.deleteTable(database, table.getName());
            }
            glue.deleteDatabase(database);
        });
        TableLocation.of("s3://" + bucket + "/").forEachObject(s3, object -> s3.deleteObject(bucket, object.getKey()));
        s3.deleteBucket(bucket);
    }

    @Test
    void anIcebergTableIsCreatedThenWrittenByEachDmlStatementAndReadBack() {
        run("CREATE TABLE events (id bigint, name string, category string) LOCATION 's3://" + bucket
                + "/lake/events/' TBLPROPERTIES ('table_type'='ICEBERG')");

        committed("INSERT INTO events VALUES (1, 'a', 'x'), (2, 'b', 'y')");
        committed("UPDATE " + database + ".events SET name = 'bb' WHERE id = 2");
        committed("DELETE FROM awsdatacatalog." + database + ".events WHERE id = 1");
        committed("""
                MERGE INTO events t
                USING (SELECT 3 AS id, 'c' AS name, 'x' AS category UNION ALL SELECT 2, 'b2', 'y') s
                ON t.id = s.id
                WHEN MATCHED THEN UPDATE SET name = s.name
                WHEN NOT MATCHED THEN INSERT VALUES (s.id, s.name, s.category)""");

        assertEquals(List.of(row("id", "2", "name", "b2", "category", "y"), row("id", "3", "name", "c", "category", "x")),
                rows("SELECT id, name, category FROM events ORDER BY id"));
        assertEquals("2", loadTable("events").path("metadata").path("properties").path("format-version").asText());
    }

    /** Runs a write and checks its commit landed on the Glue table, moving its {@code metadata_location}. */
    private void committed(String sql) {
        String before = metadataLocation("events");
        run(sql);
        assertNotEquals(before, metadataLocation("events"), sql);
    }

    @Test
    void aPartitionedIcebergTableIsWrittenByItsSpec() {
        run("CREATE TABLE events (id bigint, category string) PARTITIONED BY (category, bucket(4, id)) LOCATION 's3://"
                + bucket + "/lake/events/' TBLPROPERTIES ('table_type'='ICEBERG')");
        JsonNode spec = loadTable("events").path("metadata").path("partition-specs").get(0).path("fields");
        assertEquals("category", spec.get(0).path("name").asText());
        assertEquals("bucket[4]", spec.get(1).path("transform").asText());

        run("INSERT INTO events VALUES (1, 'x'), (2, 'y'), (3, 'x')");

        assertEquals(List.of(row("category", "x", "n", "2"), row("category", "y", "n", "1")),
                rows("SELECT category, count(*) AS n FROM events GROUP BY category ORDER BY category"));
    }

    @Test
    void vacuumExpiresSnapshotsOlderThanTheTablesMaximumAgeAndRemovesOrphans() {
        run("CREATE TABLE events (id bigint) LOCATION 's3://" + bucket + "/lake/events/' "
                + "TBLPROPERTIES ('table_type'='ICEBERG', 'vacuum_max_snapshot_age_seconds'='1')");
        run("INSERT INTO events VALUES (1)");
        run("INSERT INTO events VALUES (2)");
        run("INSERT INTO events VALUES (3)");
        assertEquals(3, loadTable("events").path("metadata").path("snapshots").size());
        String orphan = "lake/events/data/orphan.parquet";
        s3.putObject(bucket, orphan, "not table data".getBytes(StandardCharsets.UTF_8), "application/octet-stream",
                null);

        // The snapshots and the orphan become older than the table's one-second maximum age.
        await().atMost(Duration.ofSeconds(30)).pollInterval(Duration.ofSeconds(1)).untilAsserted(() -> {
            run("VACUUM events");
            assertEquals(1, loadTable("events").path("metadata").path("snapshots").size());
            assertTrue(s3.listObjects(bucket, orphan, null, 10).isEmpty(), "the orphan file is removed");
        });

        assertEquals(List.of(row("id", "1"), row("id", "2"), row("id", "3")),
                rows("SELECT id FROM events ORDER BY id"));
    }

    @Test
    void anS3TablesCatalogIsCreatedWrittenAndReadInItsOwnNameAndAsTheContext() throws Exception {
        String tableBucket = "athena-s3t-" + System.nanoTime();
        String catalogName = "s3tablescatalog/" + tableBucket;
        TableBucket created = RequestScopes.callAs(ACCOUNT, REGION, () -> {
            TableBucket made = s3Tables.createTableBucket(tableBucket, null, null, Map.of(), REGION);
            try {
                catalogs.createCatalog("s3tablescatalog", new ObjectMapper().readTree(
                        "{\"FederatedCatalog\":{\"Identifier\":\"" + made.getArn()
                                + "\",\"ConnectionName\":\"aws:s3tables\"}}"), Map.of(), REGION);
            } catch (IOException e) {
                throw new IllegalStateException(e);
            }
            return made;
        });
        try {
            run(catalogName, null, "CREATE DATABASE sales");
            run(catalogName, "sales", "CREATE TABLE orders (id bigint, name string) "
                    + "TBLPROPERTIES ('table_type'='iceberg')");
            run(catalogName, "sales", "INSERT INTO orders VALUES (1, 'a'), (2, 'b')");
            run(null, database, "INSERT INTO \"" + catalogName + "\".sales.orders VALUES (3, 'c')");
            run(catalogName, "sales", "DELETE FROM orders WHERE id = 1");

            assertEquals(List.of(row("id", "2", "name", "b"), row("id", "3", "name", "c")),
                    s3TablesRows(catalogName, "sales", "SELECT id, name FROM orders ORDER BY id"));
            assertTrue(RequestScopes.callAs(ACCOUNT, REGION, () -> s3Tables.getTable(created.getArn(), "sales",
                    "orders", REGION)).getMetadataLocation().contains("/metadata/"));
            // With no Database, Athena's context database is "default", which the bucket does not have.
            run(catalogName, null, "SELECT count(*) FROM \"" + catalogName + "\".sales.orders");
            for (String maintenance : List.of("VACUUM orders", "OPTIMIZE orders REWRITE DATA USING BIN_PACK")) {
                QueryExecution refused = execute(catalogName, "sales", maintenance);
                assertEquals(QueryExecutionState.FAILED, refused.getStatus().getState(), maintenance);
                assertTrue(refused.getStatus().getStateChangeReason().contains("not supported on S3 Tables"),
                        refused.getStatus().getStateChangeReason());
            }

            run(catalogName, "sales", "DROP TABLE orders");
            assertTrue(RequestScopes.callAs(ACCOUNT, REGION,
                    () -> s3Tables.listTables(created.getArn(), "sales", null, REGION)).isEmpty());
        } finally {
            RequestScopes.runAs(ACCOUNT, REGION, () -> {
                catalogs.deleteCatalog("s3tablescatalog", REGION);
                for (Namespace namespace : s3Tables.listNamespaces(created.getArn(), null, REGION)) {
                    for (S3Table table : s3Tables.listTables(created.getArn(), namespace.getName(), null, REGION)) {
                        s3Tables.deleteTable(created.getArn(), namespace.getName(), table.getName(), REGION);
                    }
                    s3Tables.deleteNamespace(created.getArn(), namespace.getName(), REGION);
                }
                s3Tables.deleteTableBucket(created.getArn(), REGION);
            });
        }
    }

    private void run(String sql) {
        run(null, database, sql);
    }

    /** Runs {@code sql} in the context catalog and database, failing the test unless it succeeds. */
    private void run(String catalogName, String contextDatabase, String sql) {
        QueryExecution execution = execute(catalogName, contextDatabase, sql);
        if (execution.getStatus().getState() != QueryExecutionState.SUCCEEDED) {
            fail(sql + " -> " + execution.getStatus().getState() + ": " + execution.getStatus().getStateChangeReason());
        }
    }

    private QueryExecution execute(String catalogName, String contextDatabase, String sql) {
        QueryExecutionContext context = new QueryExecutionContext();
        context.setCatalog(catalogName);
        context.setDatabase(contextDatabase);
        String id = RequestScopes.callAs(ACCOUNT, REGION, () -> athena.startQueryExecution(sql, "primary", context,
                new ResultConfiguration("s3://" + bucket + "/results/")));
        return await().atMost(Duration.ofSeconds(60)).pollInterval(Duration.ofMillis(100))
                .until(() -> RequestScopes.callAs(ACCOUNT, REGION, () -> athena.getQueryExecution(id)),
                        found -> found.getStatus().getState() != QueryExecutionState.RUNNING
                                && found.getStatus().getState() != QueryExecutionState.QUEUED);
    }

    /** The rows of {@code sql} read in an S3 Tables context catalog, through the attachment Athena gives a query. */
    private List<Map<String, String>> s3TablesRows(String catalogName, String contextDatabase, String sql) {
        return rowsOf(RequestScopes.callAs(ACCOUNT, REGION, () -> {
            AthenaIcebergStatements.S3TablesAttachment attachment = icebergStatements.s3TablesAttachment(sql,
                    catalogName, contextDatabase);
            try {
                return duck.query(TrinoSqlCompatibility.translate(sql), attachment.attachSql()
                        + TrinoSqlCompatibility.FUNCTION_MACROS + attachment.searchPathSql(), ACCOUNT);
            } finally {
                icebergStatements.finished(attachment);
            }
        }));
    }

    private List<Map<String, String>> rows(String sql) {
        return rowsOf(RequestScopes.callAs(ACCOUNT, REGION, () -> duck.query(TrinoSqlCompatibility.translate(sql),
                TrinoSqlCompatibility.FUNCTION_MACROS + ddlBuilder.build(database, sql), ACCOUNT)));
    }

    /** The engine's rows, each column's value as its text, columns in name order. */
    private static List<Map<String, String>> rowsOf(List<Map<String, Object>> found) {
        return found.stream().map(columns -> {
            Map<String, String> row = new TreeMap<>();
            columns.forEach((column, value) -> row.put(column, String.valueOf(value)));
            return row;
        }).toList();
    }

    private static Map<String, String> row(String... columnsAndValues) {
        Map<String, String> row = new TreeMap<>();
        for (int i = 0; i < columnsAndValues.length; i += 2) {
            row.put(columnsAndValues[i], columnsAndValues[i + 1]);
        }
        return row;
    }

    private String metadataLocation(String table) {
        return RequestScopes.callAs(ACCOUNT, REGION,
                () -> glue.getTable(database, table).getParameters().get("metadata_location"));
    }

    private JsonNode loadTable(String table) {
        return RequestScopes.callAs(ACCOUNT, REGION, () -> catalog.loadTable(":", database, table, null));
    }
}
