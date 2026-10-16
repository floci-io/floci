package io.github.hectorvent.floci.services.rds.export;

import com.github.dockerjava.api.DockerClient;
import com.github.dockerjava.api.command.InspectVolumeResponse;
import io.github.hectorvent.floci.services.floci.duck.FlociDuckClient;
import io.github.hectorvent.floci.services.s3.S3Service;
import io.github.hectorvent.floci.services.s3.model.S3Object;
import io.github.hectorvent.floci.testing.SidecarContainersProfile;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;
import io.restassured.RestAssured;
import jakarta.inject.Inject;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.postgresql.Driver;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.http.urlconnection.UrlConnectionHttpClient;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.rds.RdsClient;
import software.amazon.awssdk.services.rds.model.DBInstance;
import software.amazon.awssdk.services.rds.model.ExportTask;
import software.amazon.awssdk.services.rds.model.StartExportTaskResponse;

import java.net.URI;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.UUID;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsInAnyOrder;
import static org.hamcrest.Matchers.empty;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.matchesPattern;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * A PostgreSQL snapshot exported through the SDK, read back from Floci's S3 with DuckDB: the
 * statuses the task reports, the object layout, and the Parquet type and value of each column
 * against the RDS export mapping.
 *
 * <p>Needs Docker for the database and the floci-duck sidecar, and a sidecar that can reach
 * Floci's S3 from its container, which is why it is opted into like {@code ParquetEmitterIntegrationTest}.
 */
@QuarkusTest
@TestProfile(SidecarContainersProfile.class)
@Tag("docker")
@EnabledIfEnvironmentVariable(named = "FLOCI_DUCK_CROSS_CONTAINER_TEST", matches = "1|true|yes")
class RdsSnapshotExportIntegrationTest {

    private static final String MASTER_USER = "master";
    private static final String MASTER_PASSWORD = "Master123!";

    @Inject
    DockerClient dockerClient;

    @Inject
    S3Service s3Service;

    @Inject
    FlociDuckClient duckClient;

    @BeforeEach
    void checkDocker() {
        try {
            dockerClient.pingCmd().exec();
        } catch (Exception e) {
            assumeTrue(false, "Docker is not available: " + e.getMessage());
        }
    }

    @Test
    void snapshotTablesAreExportedAsParquetInTheRdsLayout() throws Exception {
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        String dbId = "export-src-" + suffix;
        String bucket = "rds-export-" + suffix;
        String taskId = "export-" + suffix;
        s3Service.createBucket(bucket, "us-east-1");
        try (RdsClient rds = rdsClient()) {
            DBInstance instance = rds.createDBInstance(request -> request
                    .dbInstanceIdentifier(dbId)
                    .engine("postgres")
                    .masterUsername(MASTER_USER)
                    .masterUserPassword(MASTER_PASSWORD)
                    .dbName("appdb")
                    .allocatedStorage(20)
                    .dbInstanceClass("db.t3.micro")).dbInstance();
            try {
                asMaster(instance.endpoint().port(),
                        "CREATE TABLE items (id int PRIMARY KEY, small int2, big int8, ratio float8, flag bool, "
                                + "name text, price numeric(10,2), created_at timestamptz, attrs jsonb, "
                                + "tags uuid[], payload bytea)",
                        "INSERT INTO items VALUES (1, 7, 9000000000, 0.5, true, 'first', 12.30, "
                                + "'2026-10-13 10:00:00+02', '{\"color\": \"red\"}', "
                                + "'{6f1c2d34-1a2b-4c3d-8e9f-0a1b2c3d4e5f}', '\\xdeadbeef')",
                        "INSERT INTO items (id, name) VALUES (2, 'second')",
                        "CREATE TABLE empty_items (id int PRIMARY KEY)",
                        "CREATE TABLE left_out (id int PRIMARY KEY)",
                        "INSERT INTO left_out VALUES (1)");

                String snapshotArn = rds.createDBSnapshot(request -> request
                        .dbInstanceIdentifier(dbId)
                        .dbSnapshotIdentifier(dbId + "-snap")).dbSnapshot().dbSnapshotArn();
                asMaster(instance.endpoint().port(), "INSERT INTO items (id, name) VALUES (3, 'after the snapshot')");

                StartExportTaskResponse started = rds.startExportTask(request -> request
                        .exportTaskIdentifier(taskId)
                        .sourceArn(snapshotArn)
                        .s3BucketName(bucket)
                        .s3Prefix("exports/rds")
                        .iamRoleArn("arn:aws:iam::000000000000:role/export")
                        .kmsKeyId("alias/export")
                        .exportOnly("appdb.public.items", "appdb.public.empty_items"));
                assertThat(started.status(), equalTo("STARTING"));
                assertThat(started.exportOnly(), containsInAnyOrder("appdb.public.items", "appdb.public.empty_items"));

                ExportTask finished = awaitTerminal(rds, taskId);
                assertThat(finished.failureCause(), finished.status(), equalTo("COMPLETE"));
                assertThat(finished.percentProgress(), equalTo(100));
                assertThat(finished.sourceArn(), equalTo(snapshotArn));
                assertThat(finished.sourceTypeAsString(), equalTo("SNAPSHOT"));
                assertThat("the container the snapshot was restored into is removed",
                        dockerClient.listContainersCmd().withShowAll(true)
                                .withLabelFilter(Map.of("io.floci.resource-id", taskId)).exec(), empty());
                assertThat("its volume is removed", dockerClient.listVolumesCmd().exec().getVolumes().stream()
                        .map(InspectVolumeResponse::getName)
                        .filter(name -> name.contains("rds-export-")).toList(), empty());
                assertThat("nothing is left in the staging bucket",
                        s3Service.listObjects("floci-rds-export-staging", "rds-export/", null, 1000), empty());

                List<String> keys = s3Service.listObjects(bucket, "exports/rds/" + taskId + "/", null, 1000)
                        .stream().map(S3Object::getKey).toList();
                assertThat(keys, hasSize(3));
                assertThat(keys, hasItem("exports/rds/" + taskId + "/export_info_" + taskId + ".json"));
                assertThat(keys, hasItem("exports/rds/" + taskId + "/export_tables_info_" + taskId
                        + "_from_1_to_2.json"));
                String dataKey = keys.stream().filter(key -> key.endsWith(".parquet")).findFirst().orElseThrow();
                assertThat(dataKey, matchesPattern("exports/rds/" + taskId
                        + "/appdb/public\\.items/1/part-00000-[0-9a-f-]{36}\\.gz\\.parquet"));

                String parquet = "read_parquet('s3://" + bucket + "/" + dataKey + "')";
                Map<String, String> types = Map.of(
                        "id", "INTEGER", "small", "SMALLINT", "big", "BIGINT", "ratio", "DOUBLE",
                        "flag", "BOOLEAN", "name", "VARCHAR", "price", "VARCHAR", "created_at", "VARCHAR",
                        "attrs", "VARCHAR", "tags", "VARCHAR");
                for (Map<String, Object> column : duckClient.query("DESCRIBE SELECT * FROM " + parquet, null)) {
                    String name = (String) column.get("column_name");
                    String expected = "payload".equals(name) ? "BLOB" : types.get(name);
                    assertThat(name, column.get("column_type"), equalTo(expected));
                }

                List<Map<String, Object>> rows = duckClient.query("SELECT id, small, big, ratio, flag, name, "
                        + "price, created_at, attrs, tags, hex(payload) AS payload FROM " + parquet + " ORDER BY id",
                        null);
                assertThat(rows, hasSize(2));
                Map<String, Object> first = rows.get(0);
                assertEquals(1, ((Number) first.get("id")).intValue());
                assertEquals(7, ((Number) first.get("small")).intValue());
                assertEquals(9_000_000_000L, ((Number) first.get("big")).longValue());
                assertEquals(0.5, ((Number) first.get("ratio")).doubleValue());
                assertEquals(true, first.get("flag"));
                assertEquals("first", first.get("name"));
                assertEquals("12.30", first.get("price"));
                assertEquals("2026-10-13 08:00:00+00", first.get("created_at"));
                assertEquals("{\"color\": \"red\"}", first.get("attrs"));
                assertEquals("{6f1c2d34-1a2b-4c3d-8e9f-0a1b2c3d4e5f}", first.get("tags"));
                assertEquals("DEADBEEF", first.get("payload"));
                assertEquals("second", rows.get(1).get("name"));
            } finally {
                rds.deleteDBInstance(request -> request.dbInstanceIdentifier(dbId).skipFinalSnapshot(true));
            }
        }
    }

    @Test
    void anInheritanceParentAndItsChildEachExportOnlyTheirOwnRows() throws Exception {
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        String dbId = "export-inh-" + suffix;
        String bucket = "rds-export-" + suffix;
        String taskId = "export-" + suffix;
        s3Service.createBucket(bucket, "us-east-1");
        try (RdsClient rds = rdsClient()) {
            DBInstance instance = rds.createDBInstance(request -> request
                    .dbInstanceIdentifier(dbId)
                    .engine("postgres")
                    .masterUsername(MASTER_USER)
                    .masterUserPassword(MASTER_PASSWORD)
                    .dbName("appdb")
                    .allocatedStorage(20)
                    .dbInstanceClass("db.t3.micro")).dbInstance();
            try {
                asMaster(instance.endpoint().port(),
                        "CREATE TABLE events (id int)",
                        "CREATE TABLE late_events () INHERITS (events)",
                        "INSERT INTO events VALUES (1)",
                        "INSERT INTO late_events VALUES (2)");
                String snapshotArn = rds.createDBSnapshot(request -> request
                        .dbInstanceIdentifier(dbId)
                        .dbSnapshotIdentifier(dbId + "-snap")).dbSnapshot().dbSnapshotArn();

                rds.startExportTask(request -> request
                        .exportTaskIdentifier(taskId)
                        .sourceArn(snapshotArn)
                        .s3BucketName(bucket)
                        .iamRoleArn("arn:aws:iam::000000000000:role/export")
                        .kmsKeyId("alias/export")
                        .exportOnly("appdb.public.events", "appdb.public.late_events"));
                ExportTask finished = awaitTerminal(rds, taskId);
                assertThat(finished.failureCause(), finished.status(), equalTo("COMPLETE"));

                for (Map.Entry<String, Integer> table : Map.of("events", 1, "late_events", 2).entrySet()) {
                    String dataKey = s3Service.listObjects(bucket, taskId + "/appdb/public." + table.getKey() + "/",
                            null, 1000).stream().map(S3Object::getKey).findFirst().orElseThrow();
                    List<Map<String, Object>> rows = duckClient.query(
                            "SELECT id FROM read_parquet('s3://" + bucket + "/" + dataKey + "')", null);
                    assertThat(table.getKey(), rows, hasSize(1));
                    assertEquals(table.getValue(), ((Number) rows.getFirst().get("id")).intValue());
                }
            } finally {
                rds.deleteDBInstance(request -> request.dbInstanceIdentifier(dbId).skipFinalSnapshot(true));
            }
        }
    }

    private static ExportTask awaitTerminal(RdsClient rds, String taskId) throws InterruptedException {
        Instant deadline = Instant.now().plus(Duration.ofMinutes(3));
        while (true) {
            ExportTask task = rds.describeExportTasks(request -> request.exportTaskIdentifier(taskId))
                    .exportTasks().getFirst();
            if (List.of("COMPLETE", "FAILED", "CANCELED").contains(task.status()) || Instant.now().isAfter(deadline)) {
                return task;
            }
            Thread.sleep(1_000);
        }
    }

    private static RdsClient rdsClient() {
        return RdsClient.builder()
                .endpointOverride(URI.create("http://localhost:" + RestAssured.port))
                .httpClientBuilder(UrlConnectionHttpClient.builder())
                .region(Region.US_EAST_1)
                .credentialsProvider(StaticCredentialsProvider.create(AwsBasicCredentials.create("test", "test")))
                .build();
    }

    private static void asMaster(int port, String... statements) throws SQLException {
        Properties master = new Properties();
        master.setProperty("user", MASTER_USER);
        master.setProperty("password", MASTER_PASSWORD);
        master.setProperty("sslmode", "disable");
        // The driver itself rather than DriverManager, which does not see it from a test profile's
        // class loader once another profile has run.
        try (Connection connection = new Driver().connect("jdbc:postgresql://127.0.0.1:" + port + "/appdb", master);
             Statement statement = connection.createStatement()) {
            for (String sql : statements) {
                statement.execute(sql);
            }
        }
    }
}
