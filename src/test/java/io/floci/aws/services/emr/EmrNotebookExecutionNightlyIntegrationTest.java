package io.floci.aws.services.emr;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.floci.aws.testing.AwsTestClients;
import io.floci.aws.testing.DockerAvailability;
import io.floci.aws.testing.PartitionCleanup;
import io.floci.aws.testing.RestAssuredJsonUtils;
import io.floci.aws.testing.S3TestClients;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.QuarkusTestProfile;
import io.quarkus.test.junit.TestProfile;
import jakarta.inject.Inject;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.junit.jupiter.api.extension.RegisterExtension;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.LockSupport;
import java.util.function.Supplier;

import static io.floci.aws.testing.EmrRequests.emr;
import static io.floci.aws.testing.EmrRequests.terminateClusterAndAwaitCleanup;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * EMR notebook executions run for real: a notebook in S3 runs on an emr-7.5.0 cluster's Spark, its parameters put in
 * after its parameters cell in place of those an earlier run left, its {@code %%sql} cell run by Spark SQL, and the
 * notebook as run, its cells' outputs in it, is written to S3 with its HTML; a notebook whose cell raises fails its
 * execution, the error kept in the notebook written and the outputs an earlier run left past it gone; a parameter that
 * is not a Python variable's name fails the run before any cell.
 *
 * <p>A nightly check: the cluster's image, HDFS and YARN, and each notebook's Spark driver take minutes and gigabytes.
 */
@QuarkusTest
@TestProfile(EmrNotebookExecutionNightlyIntegrationTest.Profile.class)
@EnabledIfSystemProperty(named = "nightly.checks.enabled", matches = "true")
// Bounded waits: the cluster's image of up to twenty minutes, its HDFS and YARN, and two notebooks' runs; the Nightly
// checks job's timeout counts the 40.
@Timeout(value = 40, unit = TimeUnit.MINUTES)
class EmrNotebookExecutionNightlyIntegrationTest {

    /** Clusters run their steps, and notebook executions their notebooks, in containers. */
    public static final class Profile implements QuarkusTestProfile {
        @Override
        public Map<String, String> getConfigOverrides() {
            return Map.of("floci.services.emr.runner-mode", "docker");
        }
    }

    private static final String ACCOUNT = "000000000000";
    private static final ObjectMapper JSON = new ObjectMapper();
    // As an earlier run wrote it: the parameters it was given are in a cell of their own.
    private static final String NOTEBOOK = """
            {"nbformat": 4, "nbformat_minor": 5, "metadata": {}, "cells": [
             {"cell_type": "markdown", "metadata": {}, "source": ["# A count"]},
             {"cell_type": "code", "metadata": {"tags": ["parameters"]}, "execution_count": null, "outputs": [],
              "source": ["limit = 3\\n"]},
             {"cell_type": "code", "metadata": {"tags": ["injected-parameters"]}, "execution_count": 1, "outputs": [],
              "source": ["limit = 99\\n"]},
             {"cell_type": "code", "metadata": {}, "execution_count": null, "outputs": [],
              "source": ["print('rows', spark.range(limit).count())\\n"]},
             {"cell_type": "code", "metadata": {}, "execution_count": null, "outputs": [],
              "source": ["limit * 2"]},
             {"cell_type": "code", "metadata": {}, "execution_count": null, "outputs": [],
              "source": ["%%sql\\n", "SELECT 41 + 1 AS answer"]},
             {"cell_type": "code", "metadata": {}, "execution_count": null, "outputs": [],
              "source": ["%matplotlib inline\\n", "label = 'rows: %d' % limit\\n", "label"]},
             {"cell_type": "code", "metadata": {}, "execution_count": null, "outputs": [],
              "source": ["text = '''\\n", "%d left\\n", "''' % limit\\n", "text.strip()"]}]}
            """;
    // As an earlier run wrote it: the cell past the one that raises has that run's output.
    private static final String FAILING = """
            {"nbformat": 4, "nbformat_minor": 5, "metadata": {}, "cells": [
             {"cell_type": "code", "metadata": {}, "execution_count": null, "outputs": [],
              "source": ["print('before')\\n"]},
             {"cell_type": "code", "metadata": {}, "execution_count": null, "outputs": [],
              "source": ["spark.sql('SELECT * FROM no_such_table').collect()"]},
             {"cell_type": "code", "metadata": {}, "execution_count": 7, "outputs": [{"output_type": "display_data",
              "metadata": {}, "data": {"image/png": "iVBORw0KGgo="}}],
              "source": ["print('never')"]}]}
            """;

    @RegisterExtension
    final PartitionCleanup cleanup = new PartitionCleanup();

    @Inject
    EmrService emrService;

    @BeforeAll
    static void configure() {
        RestAssuredJsonUtils.configureAwsContentTypes();
    }

    @Test
    void aNotebookRunsOnItsClusterAndItsRunIsWrittenToS3() throws Exception {
        // Enabled, the check needs Docker: without a daemon it fails rather than pass having run nothing.
        assertTrue(DockerAvailability.isAvailable(), "the nightly check needs a Docker daemon");
        S3Client s3 = AwsTestClients.create(S3Client.builder().forcePathStyle(true), ACCOUNT, Region.US_EAST_1);
        cleanup.register(s3::close);
        String bucket = "notebooks-" + UUID.randomUUID().toString().substring(0, 8);
        s3.createBucket(request -> request.bucket(bucket));
        cleanup.register(() -> S3TestClients.deleteBucket(s3, bucket));
        s3.putObject(request -> request.bucket(bucket).key("team/count.ipynb"), RequestBody.fromString(NOTEBOOK));
        s3.putObject(request -> request.bucket(bucket).key("team/failing.ipynb"), RequestBody.fromString(FAILING));
        String clusterId = emr("RunJobFlow", "{\"Name\":\"notebooks\",\"ReleaseLabel\":\"emr-7.5.0\","
                + "\"Applications\":[{\"Name\":\"Hadoop\"},{\"Name\":\"Spark\"},{\"Name\":\"Livy\"},"
                + "{\"Name\":\"JupyterEnterpriseGateway\"}],"
                + "\"Instances\":{\"KeepJobFlowAliveWhenNoSteps\":true,\"InstanceGroups\":[{\"Name\":\"primary\","
                + "\"InstanceRole\":\"MASTER\",\"InstanceType\":\"m5.xlarge\",\"InstanceCount\":1}]}}")
                .then().statusCode(200).extract().jsonPath().getString("JobFlowId");
        cleanup.register(() -> terminateClusterAndAwaitCleanup(clusterId, emrService));
        await(() -> emr("DescribeCluster", "{\"ClusterId\":\"" + clusterId + "\"}").jsonPath()
                .getString("Cluster.Status.State"), "WAITING", List.of("TERMINATED"), Duration.ofMinutes(5));

        String counting = start(clusterId, bucket, "team/count.ipynb", "{\"limit\": 5}");
        awaitExecution(counting, "FINISHED", Duration.ofMinutes(25));
        String uri = emr("DescribeNotebookExecution", "{\"NotebookExecutionId\":\"" + counting + "\"}").jsonPath()
                .getString("NotebookExecution.OutputNotebookURI");
        assertEquals("s3://" + bucket + "/runs/" + counting + "/count.html", uri);
        JsonNode run = JSON.readTree(s3.getObjectAsBytes(request -> request.bucket(bucket)
                .key("runs/" + counting + "/count.ipynb")).asUtf8String());
        JsonNode cells = run.path("cells");
        assertEquals(8, cells.size(), run.toString());
        assertEquals("injected-parameters", cells.path(2).path("metadata").path("tags").path(0).asText(),
                run.toString());
        assertEquals("limit = 5\n", cells.path(2).path("source").path(1).asText(), run.toString());
        assertEquals("rows 5\n", cells.path(3).path("outputs").path(0).path("text").path(0).asText(), run.toString());
        assertEquals("10", result(cells.path(4)), run.toString());
        String answer = String.join("", JSON.convertValue(cells.path(5).path("outputs").path(0).path("text"),
                String[].class));
        assertTrue(answer.contains("answer") && answer.contains("42"), run.toString());
        assertEquals("'rows: 5'", result(cells.path(6)), run.toString());
        assertEquals("'5 left'", result(cells.path(7)), run.toString());
        String page = s3.getObjectAsBytes(request -> request.bucket(bucket).key("runs/" + counting + "/count.html"))
                .asUtf8String();
        assertTrue(page.contains("rows 5"), page);

        String failing = start(clusterId, bucket, "team/failing.ipynb", null);
        awaitExecution(failing, "FAILED", Duration.ofMinutes(10));
        JsonNode failed = JSON.readTree(s3.getObjectAsBytes(request -> request.bucket(bucket)
                .key("runs/" + failing + "/failing.ipynb")).asUtf8String());
        assertEquals("error", failed.path("cells").path(1).path("outputs").path(0).path("output_type").asText(),
                failed.toString());
        assertTrue(failed.path("cells").path(2).path("outputs").isEmpty(), failed.toString());
        assertTrue(failed.path("cells").path(2).path("execution_count").isNull(), failed.toString());
        assertTrue(s3.getObjectAsBytes(request -> request.bucket(bucket).key("runs/" + failing + "/failing.html"))
                .asUtf8String().contains("no_such_table"));

        String misnamed = start(clusterId, bucket, "team/count.ipynb", "{\"two words\": 1}");
        awaitExecution(misnamed, "FAILED", Duration.ofMinutes(10));
        assertEquals(String.format(EmrNotebookExecutionService.RUN_FAILED, clusterId, 1),
                emr("DescribeNotebookExecution", "{\"NotebookExecutionId\":\"" + misnamed + "\"}").jsonPath()
                        .getString("NotebookExecution.LastStateChangeReason"));
    }

    // A cell's last expression's value, as the notebook as run holds it.
    private static String result(JsonNode cell) {
        return cell.path("outputs").path(0).path("data").path("text/plain").path(0).asText();
    }

    private static String start(String clusterId, String bucket, String key, String params) {
        return emr("StartNotebookExecution", "{\"ExecutionEngine\":{\"Id\":\"" + clusterId + "\"},"
                + "\"ServiceRole\":\"EMR_Notebooks_DefaultRole\","
                + "\"NotebookS3Location\":{\"Bucket\":\"" + bucket + "\",\"Key\":\"" + key + "\"},"
                + "\"OutputNotebookS3Location\":{\"Bucket\":\"" + bucket + "\",\"Key\":\"runs\"},"
                + "\"OutputNotebookFormat\":\"HTML\""
                + (params != null ? ",\"NotebookParams\":" + JSON.valueToTree(params) : "") + "}")
                .then().statusCode(200).extract().jsonPath().getString("NotebookExecutionId");
    }

    private static void awaitExecution(String executionId, String status, Duration limit) {
        await(() -> emr("DescribeNotebookExecution", "{\"NotebookExecutionId\":\"" + executionId + "\"}")
                .jsonPath().getString("NotebookExecution.Status"), status,
                List.of("FINISHED", "FAILED", "STOPPED"), limit);
    }

    private static void await(Supplier<String> read, String state, List<String> ends, Duration limit) {
        long deadline = System.nanoTime() + limit.toNanos();
        while (true) {
            String now = read.get();
            if (state.equals(now)) {
                return;
            }
            if (ends.contains(now) || System.nanoTime() - deadline > 0) {
                fail("reached " + now + ", not " + state);
            }
            LockSupport.parkNanos(TimeUnit.SECONDS.toNanos(2));
        }
    }
}
