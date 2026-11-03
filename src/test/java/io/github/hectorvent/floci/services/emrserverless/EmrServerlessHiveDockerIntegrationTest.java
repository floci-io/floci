package io.github.hectorvent.floci.services.emrserverless;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.hectorvent.floci.testing.AwsTestClients;
import io.github.hectorvent.floci.testing.ContainerDataPlaneProfile;
import io.github.hectorvent.floci.testing.DockerAvailability;
import io.github.hectorvent.floci.testing.PartitionCleanup;
import io.github.hectorvent.floci.testing.S3TestClients;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;
import io.restassured.response.Response;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.glue.GlueClient;
import software.amazon.awssdk.services.glue.model.Table;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.S3Object;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.UUID;
import java.util.zip.GZIPInputStream;

import static io.github.hectorvent.floci.testing.EmrServerlessRequests.awaitJobRun;
import static io.github.hectorvent.floci.testing.EmrServerlessRequests.createApplication;
import static io.github.hectorvent.floci.testing.EmrServerlessRequests.deleteApplication;
import static io.github.hectorvent.floci.testing.EmrServerlessRequests.startJobRun;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * EMR Serverless Hive job runs through Floci's Docker runner for real: a Hive application in the {@code docker} runner
 * mode runs each job run's {@code hive} in a container of the release's EMR Serverless Hive image, Tez in local mode,
 * on Floci's S3 and with Floci's Glue as its metastore. The cached {@code emr-7.5.0} Hive image keeps a local run from
 * pulling; CI pulls it (2.7 GB) the first time.
 */
@QuarkusTest
@TestProfile(ContainerDataPlaneProfile.class)
class EmrServerlessHiveDockerIntegrationTest {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String REGION = "us-east-1";
    private static final String ROWS = "a,1\nb,2\nc,3\n";

    @RegisterExtension
    final PartitionCleanup cleanup = new PartitionCleanup();

    private S3Client s3;
    private String bucket;

    @BeforeEach
    void putInputs() throws InterruptedException {
        assumeTrue(DockerAvailability.isAvailable(), "Docker daemon must be available for EMR Serverless's Hive runs");
        s3 = AwsTestClients.create(S3Client.builder().forcePathStyle(true), "000000000000", Region.US_EAST_1);
        cleanup.register(s3::close);
        bucket = "emr-hive-" + UUID.randomUUID().toString().substring(0, 8);
        s3.createBucket(request -> request.bucket(bucket));
        cleanup.register(() -> S3TestClients.deleteBucket(s3, bucket));
        put("data/rows/rows.csv", ROWS);
    }

    @Test
    void theGuidesHiveRunKeepsItsTablesInFlocisGlueAndWritesItsReportAndLogsToS3() {
        String database = bucket.replace('-', '_');
        // The user guide's Hive job: an init query file, then the query, with its variables and settings.
        put("queries/init.sql", """
                create database if not exists ${dbname};
                create external table if not exists ${dbname}.kv (k string, v int)
                  row format delimited fields terminated by ',' location '%s';
                """.formatted(s3Uri("data/rows/")));
        put("queries/report.sql", """
                create table ${dbname}.scaled stored as textfile
                  as select k, v * ${hiveconf:factor} as v from ${dbname}.kv;
                insert overwrite directory '%s' row format delimited fields terminated by ','
                  select k, v from ${dbname}.scaled order by k;
                select k, v from ${dbname}.scaled order by k;
                select '${env:HOME}', '${system:user.dir}', '${system:user.name}';
                """.formatted(s3Uri("out/report/")));
        GlueClient glue = AwsTestClients.create(GlueClient.builder(), "000000000000", Region.US_EAST_1);
        cleanup.register(glue::close);
        cleanup.register(() -> glue.deleteDatabase(request -> request.name(database)));
        String applicationId = hiveApplication();
        ObjectNode run = run(applicationId, "--hivevar dbname=" + database + " --hiveconf factor=10");

        Response ended = awaitJobRun(REGION, applicationId, startJobRun(REGION, applicationId, run.toString()));

        assertEquals("SUCCESS", ended.jsonPath().getString("jobRun.state"));
        assertEquals("a,10\nb,20\nc,30\n", objects("out/report/"));
        // The managed table's data is under the hive-site classification's warehouse, and Floci's Glue holds both.
        assertEquals("a\u000110\nb\u000120\nc\u000130\n", objects("hive/warehouse/" + database + ".db/scaled/"));
        assertEquals(List.of("kv", "scaled"), glue.getTables(request -> request.databaseName(database)).tableList()
                .stream().map(Table::name).sorted().toList());
        // The query's result is the driver's standard output, in the run's HIVE_DRIVER log folder.
        String driverLogs = "logs/applications/" + applicationId + "/jobs/"
                + ended.jsonPath().getString("jobRun.jobRunId") + "/HIVE_DRIVER/";
        String stdout = gunzipped(driverLogs + "stdout.gz");
        assertTrue(stdout.contains("a\t10\nb\t20\nc\t30\n"));
        // Hive runs as the image's user, in its home folder, as EMR Serverless runs it.
        assertTrue(stdout.contains("/home/hadoop\t/home/hadoop\thadoop\n"));
    }

    @Test
    void aHiveQueryThatFailsFailsItsRun() {
        put("queries/report.sql", "select k from missing_database.missing_table;\n");
        String applicationId = hiveApplication();

        Response ended = awaitJobRun(REGION, applicationId,
                startJobRun(REGION, applicationId, run(applicationId, null).toString()));

        assertEquals("FAILED", ended.jsonPath().getString("jobRun.state"));
        // Hive's reason, on the driver's standard error.
        assertTrue(gunzipped("logs/applications/" + applicationId + "/jobs/"
                + ended.jsonPath().getString("jobRun.jobRunId") + "/HIVE_DRIVER/stderr.gz")
                .contains("Table not found 'missing_table'"));
    }

    /** A HIVE application on the cached release, deleted after the test. */
    private String hiveApplication() {
        String applicationId = createApplication(REGION, "emr-7.5.0", "HIVE");
        cleanup.register(() -> deleteApplication(REGION, applicationId));
        return applicationId;
    }

    /**
     * A job run of {@code queries/report.sql}, after {@code queries/init.sql} when there is one, with
     * {@code parameters} (null for none), the guide's two mandatory Hive properties in its {@code hive-site} and its
     * logs under {@code logs/}.
     */
    private ObjectNode run(String applicationId, String parameters) {
        ObjectNode run = JSON.createObjectNode().put("clientToken", UUID.randomUUID().toString())
                .put("executionRoleArn", "arn:aws:iam::000000000000:role/emr-serverless-job");
        ObjectNode hive = run.putObject("jobDriver").putObject("hive").put("query", s3Uri("queries/report.sql"));
        if (s3.listObjectsV2(request -> request.bucket(bucket).prefix("queries/init.sql")).keyCount() > 0) {
            hive.put("initQueryFile", s3Uri("queries/init.sql"));
        }
        if (parameters != null) {
            hive.put("parameters", parameters);
        }
        ObjectNode overrides = run.putObject("configurationOverrides");
        overrides.putArray("applicationConfiguration").addObject().put("classification", "hive-site")
                .putObject("properties")
                .put("hive.exec.scratchdir", s3Uri("hive/scratch/"))
                .put("hive.metastore.warehouse.dir", s3Uri("hive/warehouse/"));
        overrides.putObject("monitoringConfiguration").putObject("s3MonitoringConfiguration")
                .put("logUri", s3Uri("logs/"));
        return run;
    }

    private void put(String key, String text) {
        s3.putObject(request -> request.bucket(bucket).key(key), RequestBody.fromString(text));
    }

    private String s3Uri(String key) {
        return "s3://" + bucket + "/" + key;
    }

    /** The objects under {@code prefix}, in key order, one after the other. */
    private String objects(String prefix) {
        StringBuilder text = new StringBuilder();
        for (S3Object object : s3.listObjectsV2(request -> request.bucket(bucket).prefix(prefix)).contents()) {
            text.append(s3.getObjectAsBytes(request -> request.bucket(bucket).key(object.key())).asUtf8String());
        }
        return text.toString();
    }

    private String gunzipped(String key) {
        try (GZIPInputStream in = new GZIPInputStream(s3.getObject(request -> request.bucket(bucket).key(key)))) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
