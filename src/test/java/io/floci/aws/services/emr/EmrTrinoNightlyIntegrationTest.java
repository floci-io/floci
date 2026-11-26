package io.floci.aws.services.emr;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.github.dockerjava.api.DockerClient;
import com.github.dockerjava.api.command.PullImageResultCallback;
import io.floci.aws.testing.AwsTestClients;
import io.floci.aws.testing.CloudFormationSettingsProfile;
import io.floci.aws.testing.DockerAvailability;
import io.floci.aws.testing.EmrRequests;
import io.floci.aws.testing.PartitionCleanup;
import io.floci.aws.testing.RestAssuredJsonUtils;
import io.floci.aws.testing.S3TestClients;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;
import io.restassured.RestAssured;
import io.restassured.path.json.JsonPath;
import io.restassured.response.Response;
import io.restassured.specification.RequestSpecification;
import jakarta.inject.Inject;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.junit.jupiter.api.extension.RegisterExtension;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.glue.GlueClient;
import software.amazon.awssdk.services.glue.model.EntityNotFoundException;
import software.amazon.awssdk.services.glue.model.Table;
import software.amazon.awssdk.services.s3.S3Client;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.URI;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import static io.floci.aws.testing.EmrRequests.awaitStep;
import static io.floci.aws.testing.EmrRequests.emr;
import static io.floci.aws.testing.EmrRequests.terminateClusterAndAwaitCleanup;
import static io.restassured.RestAssured.given;
import static org.awaitility.Awaitility.await;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Trino on an EMR cluster through Floci's Docker runner for real, with {@code floci.services.emr.trino.enabled} on: a
 * cluster whose {@code Applications} name Trino runs its coordinator on its primary node, and a step runs EMR's
 * {@code trino-cli} against it, as the Release Guide's "Connect to the primary node for the Amazon EMR cluster and run
 * queries" does, over a CSV in Floci's S3. Its {@code hive} catalog is the cluster's Hive metastore, or, with
 * {@code trino-connector-hive} setting {@code hive.metastore} to {@code glue}, Floci's Glue Data Catalog. The
 * cluster's other {@code trino-connector-<catalog>} classifications add catalogs, Iceberg's on Glue as the Release
 * Guide's "Use an Iceberg cluster with Trino" has it, its {@code trino-config} moves Trino to another port, and its
 * {@code trino-node}, {@code trino-log} and {@code trino-env} reach Trino's node, log and environment, and its
 * {@code trino-exchange-manager} spools a fault-tolerant query in the cluster's HDFS. A
 * cluster with no step starts its Trino as it becomes ready, and a client outside the cluster queries it through Floci
 * on the cluster's own host.
 *
 * <p>A nightly check: it pulls the Trino image of {@code emr-7.5.0}'s Trino, 446, about 1 GB, and the release's Spark
 * and Hive images.
 */
@QuarkusTest
@TestProfile(EmrTrinoNightlyIntegrationTest.TrinoProfile.class)
@EnabledIfSystemProperty(named = "nightly.checks.enabled", matches = "true")
// One instance, so that the image is pulled once for the class.
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class EmrTrinoNightlyIntegrationTest {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String TRINO_IMAGE = "trinodb/trino:446";
    private static final String SALES = "1,apple,3\n2,pear,5\n3,apple,7\n";

    @RegisterExtension
    final PartitionCleanup cleanup = new PartitionCleanup();

    @Inject
    EmrService emrService;

    @Inject
    DockerClient docker;

    private S3Client s3;
    private String bucket;
    private String clusterId;

    /** The Docker data planes, with Floci's Trino on. */
    public static class TrinoProfile extends CloudFormationSettingsProfile {
        @Override
        public Map<String, String> getConfigOverrides() {
            Map<String, String> overrides = new HashMap<>(super.getConfigOverrides());
            overrides.put("floci.services.emr.trino.enabled", "true");
            return overrides;
        }
    }

    @BeforeAll
    static void configure() {
        RestAssuredJsonUtils.configureAwsContentTypes();
    }

    // Trino's image is there before a step's five minutes start, as the step would pull it while the cluster's image
    // is made.
    @BeforeAll
    @Timeout(value = 10, unit = TimeUnit.MINUTES)
    void pullImage() throws InterruptedException {
        assertTrue(DockerAvailability.isAvailable(), "the nightly check needs a Docker daemon");
        docker.pullImageCmd(TRINO_IMAGE).exec(new PullImageResultCallback()).awaitCompletion(9, TimeUnit.MINUTES);
    }

    @BeforeEach
    void putSales() {
        s3 = AwsTestClients.create(S3Client.builder().forcePathStyle(true), "000000000000", Region.US_EAST_1);
        cleanup.register(s3::close);
        bucket = "emr-trino-" + UUID.randomUUID().toString().substring(0, 8);
        s3.createBucket(request -> request.bucket(bucket));
        cleanup.register(() -> S3TestClients.deleteBucket(s3, bucket));
        s3.putObject(request -> request.bucket(bucket).key("data/sales/sales.csv"), RequestBody.fromString(SALES));
    }

    // The cluster's image made with Trino's CLI, its HDFS and metastore started, then Trino, within the step's five
    // minutes, and the cluster's end.
    @Test
    @Timeout(value = 7, unit = TimeUnit.MINUTES)
    void trinoCliQueriesTheClustersMetastoreTablesOnFlocisS3() {
        String schema = bucket.replace('-', '_');
        GlueClient glue = AwsTestClients.create(GlueClient.builder(), "000000000000", Region.US_EAST_1);
        cleanup.register(glue::close);
        startCluster("[]");

        String step = trinoStep("SHOW CATALOGS; " + tables(schema));

        assertEquals("COMPLETED", state(step), () -> logs(step));
        String logs = logs(step);
        // Its one catalog, as EMR's Trino has the catalogs the cluster configures, and its sums.
        assertTrue(logs.contains("\"hive\"\n\"system\"") || logs.contains("\"system\"\n\"hive\""), logs);
        assertTrue(logs.contains("\"apple\",\"10\"\n\"pear\",\"5\""), logs);
        // The table Trino made, in its schema's folder of Floci's S3.
        assertFalse(s3.listObjectsV2(request -> request.bucket(bucket).prefix("warehouse/totals/")).contents()
                .isEmpty());
        // The cluster's metastore holds them, not Floci's Glue.
        assertThrows(EntityNotFoundException.class, () -> glue.getDatabase(request -> request.name(schema)));
    }

    @Test
    @Timeout(value = 7, unit = TimeUnit.MINUTES)
    void withTrinoConnectorHiveOnGlueTrinoCliQueriesFlocisGlueDataCatalog() {
        String schema = bucket.replace('-', '_');
        GlueClient glue = AwsTestClients.create(GlueClient.builder(), "000000000000", Region.US_EAST_1);
        cleanup.register(glue::close);
        cleanup.register(() -> {
            for (Table table : glue.getTables(request -> request.databaseName(schema)).tableList()) {
                glue.deleteTable(request -> request.databaseName(schema).name(table.name()));
            }
            glue.deleteDatabase(request -> request.name(schema));
        });
        // The Release Guide's configuration for Trino on the Glue Data Catalog.
        startCluster("""
                [{"Classification": "trino-connector-hive", "Properties": {"hive.metastore": "glue"}}]""");

        String step = trinoStep(tables(schema));

        assertEquals("COMPLETED", state(step), () -> logs(step));
        assertTrue(logs(step).contains("\"apple\",\"10\"\n\"pear\",\"5\""), () -> logs(step));
        assertEquals(List.of("sales", "totals"), glue.getTables(request -> request.databaseName(schema)).tableList()
                .stream().map(Table::name).sorted().toList());
    }

    @Test
    @Timeout(value = 7, unit = TimeUnit.MINUTES)
    void trinoConnectorClassificationsAddCatalogsAndTrinoConfigMovesTheCoordinatorsPort() {
        String schema = bucket.replace('-', '_');
        GlueClient glue = AwsTestClients.create(GlueClient.builder(), "000000000000", Region.US_EAST_1);
        cleanup.register(glue::close);
        cleanup.register(() -> {
            for (Table table : glue.getTables(request -> request.databaseName(schema)).tableList()) {
                glue.deleteTable(request -> request.databaseName(schema).name(table.name()));
            }
            glue.deleteDatabase(request -> request.name(schema));
        });
        // The Release Guide's Iceberg catalog on the Glue Data Catalog, a TPC-H one, and another port; the node's
        // environment, a logger, and a variable trino-env exports, which the Iceberg catalog's file format names; and
        // fault-tolerant execution, its exchange manager at EMR's defaults, spooling in the cluster's HDFS, without
        // which Trino refuses a query under a TASK retry policy.
        startCluster("""
                [{"Classification": "trino-connector-iceberg", "Properties": {"connector.name": "iceberg",
                  "iceberg.catalog.type": "glue", "iceberg.file-format": "${ENV:ICEBERG_FORMAT}"}},
                 {"Classification": "trino-connector-tpch", "Properties": {}},
                 {"Classification": "trino-config",
                  "Properties": {"http-server.http.port": "8890", "retry-policy": "TASK"}},
                 {"Classification": "trino-exchange-manager"},
                 {"Classification": "trino-node", "Properties": {"node.environment": "production"}},
                 {"Classification": "trino-log", "Properties": {"io.trino": "INFO"}},
                 {"Classification": "trino-env",
                  "Configurations": [{"Classification": "export", "Properties": {"ICEBERG_FORMAT": "ORC"}}]}]""");

        String step = trinoStep("SHOW CATALOGS; CREATE SCHEMA iceberg." + schema + " WITH (location = '"
                + s3Uri("iceberg/") + "'); CREATE TABLE iceberg." + schema + ".nation AS SELECT nationkey, name FROM "
                + "tpch.tiny.nation; SELECT count(*) FROM iceberg." + schema + ".nation");

        assertEquals("COMPLETED", state(step), () -> logs(step));
        String logs = logs(step);
        assertTrue(logs.contains("\"hive\"\n\"iceberg\"\n\"system\"\n\"tpch\""), logs);
        assertTrue(logs.contains("\"25\""), logs);
        // An Iceberg table, in Floci's Glue, its files in the schema's folder of Floci's S3.
        assertEquals("ICEBERG", glue.getTable(request -> request.databaseName(schema).name("nation")).table()
                .parameters().get("table_type").toUpperCase(Locale.ROOT));
        assertFalse(s3.listObjectsV2(request -> request.bucket(bucket).prefix("iceberg/")).contents().isEmpty());
        // In the file format trino-env's variable gave the catalog.
        assertTrue(s3.listObjectsV2(request -> request.bucket(bucket).prefix("iceberg/")).contents().stream()
                .anyMatch(object -> object.key().endsWith(".orc")));
    }

    // No step: Trino starts with the cluster, and a client outside the cluster's network queries it through Floci on
    // the cluster's own host, the query's next pages on that host too.
    @Test
    @Timeout(value = 7, unit = TimeUnit.MINUTES)
    void trinoStartsWithTheClusterAndAClientOutsideItQueriesItThroughFlocisHostForIt() {
        startCluster("[]");
        String host = clusterId.toLowerCase(Locale.ROOT) + "." + EmrTrino.HOST_LABEL + ".localhost:" + RestAssured.port;

        assertEquals(List.of(List.of("hive"), List.of("system")), query(host, "SHOW CATALOGS"));
        // A catalog read, on the cluster's metastore, once Trino's node serves it.
        assertTrue(query(host, "SHOW SCHEMAS FROM hive").contains(List.of("default")));
        assertEquals("WAITING", emr("DescribeCluster", "{\"ClusterId\":\"" + clusterId + "\"}").then()
                .statusCode(200).extract().jsonPath().getString("Cluster.Status.State"));
    }

    // A schema in the bucket's warehouse folder, an external table over the CSV, a table of each item's sum, then the
    // sums.
    private String tables(String schema) {
        return "CREATE SCHEMA hive." + schema + " WITH (location = '" + s3Uri("warehouse/") + "'); "
                + "CREATE TABLE hive." + schema + ".sales (id integer, item varchar, qty integer) WITH (format = "
                + "'TEXTFILE', textfile_field_separator = ',', external_location = '" + s3Uri("data/sales/") + "'); "
                + "CREATE TABLE hive." + schema + ".totals AS SELECT item, sum(qty) AS total FROM hive." + schema
                + ".sales GROUP BY item; "
                + "SELECT item, total FROM hive." + schema + ".totals ORDER BY item";
    }

    private void startCluster(String configurations) {
        ObjectNode request = JSON.createObjectNode().put("Name", "trino").put("ReleaseLabel", "emr-7.5.0")
                .put("LogUri", s3Uri("logs/"));
        try {
            request.set("Applications", JSON.readTree("[{\"Name\":\"Trino\"}]"));
            request.set("Configurations", JSON.readTree(configurations));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        request.putObject("Instances").put("KeepJobFlowAliveWhenNoSteps", true).putArray("InstanceGroups")
                .addObject().put("Name", "primary").put("InstanceRole", "MASTER").put("InstanceType", "m5.xlarge")
                .put("InstanceCount", 1);
        clusterId = emr("RunJobFlow", request.toString()).then().statusCode(200).extract().jsonPath()
                .getString("JobFlowId");
        cleanup.register(() -> terminateClusterAndAwaitCleanup(clusterId, emrService));
    }

    // A client outside the cluster: the statement posted to Trino through Floci's host for the cluster, asked again
    // while that answers 503 as Trino's clients do, Trino starting with the cluster, then each next page Trino names
    // followed, on the host it was asked on, until the last; the rows they hold together.
    private static List<List<Object>> query(String host, String statement) {
        Response[] first = new Response[1];
        await("the Trino of the cluster to take a query").atMost(Duration.ofMinutes(5))
                .pollInterval(Duration.ofSeconds(1))
                .until(() -> (first[0] = trino(host).body(statement).post("/v1/statement")).statusCode() != 503);
        JsonPath page = first[0].then().statusCode(200).extract().jsonPath();
        List<List<Object>> rows = new ArrayList<>();
        while (true) {
            List<List<Object>> data = page.getList("data");
            if (data != null) {
                rows.addAll(data);
            }
            String next = page.getString("nextUri");
            if (next == null) {
                JsonPath last = page;
                assertNull(last.get("error"), last::prettify);
                return rows;
            }
            URI uri = URI.create(next);
            assertEquals(host, uri.getRawAuthority());
            page = trino(host).get(uri.getRawPath()).then().statusCode(200).extract().jsonPath();
        }
    }

    private static RequestSpecification trino(String host) {
        return given().header("Host", host).header("X-Trino-User", "floci");
    }

    // EMR's trino-cli, through command-runner.jar, running the statements given.
    private String trinoStep(String statements) {
        return EmrRequests.addStep(clusterId, "trino", "command-runner.jar", "trino-cli", "--execute", statements);
    }

    private String state(String stepId) {
        return awaitStep(clusterId, stepId).jsonPath().getString("Step.Status.State");
    }

    // What a step wrote to its standard output, then its standard error.
    private String logs(String stepId) {
        String folder = "logs/" + clusterId + "/steps/" + stepId + "/";
        return S3TestClients.gunzipped(s3, bucket, folder + "stdout.gz") + "\n"
                + S3TestClients.gunzipped(s3, bucket, folder + "stderr.gz");
    }

    private String s3Uri(String key) {
        return "s3://" + bucket + "/" + key;
    }
}
