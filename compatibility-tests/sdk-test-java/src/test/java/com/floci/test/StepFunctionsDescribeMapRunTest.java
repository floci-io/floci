package com.floci.test;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.DeleteBucketRequest;
import software.amazon.awssdk.services.s3.model.DeleteObjectRequest;
import software.amazon.awssdk.services.s3.model.ListObjectsV2Response;
import software.amazon.awssdk.services.s3.model.S3Object;
import software.amazon.awssdk.services.sfn.SfnClient;
import software.amazon.awssdk.services.sfn.model.DescribeExecutionResponse;
import software.amazon.awssdk.services.sfn.model.DescribeMapRunResponse;
import software.amazon.awssdk.services.sfn.model.ExecutionStatus;
import software.amazon.awssdk.services.sfn.model.MapRunExecutionCounts;
import software.amazon.awssdk.services.sfn.model.MapRunItemCounts;
import software.amazon.awssdk.services.sfn.model.MapRunStatus;
import software.amazon.awssdk.services.sfn.model.ResourceNotFoundException;

import com.fasterxml.jackson.databind.ObjectMapper;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Compatibility test for DescribeMapRun through the real SFN SDK client.
 *
 * <p>StepFunctionsDescribeMapRunIntegrationTest already pins the wire shape and every field
 * value with RestAssured. This test exists to exercise what that one cannot: the SDK's own
 * request serialization, endpoint routing and response deserialization for
 * {@code sfn.describeMapRun(...)}.
 *
 * <p>A distributed Map run is only describable once its {@code ResultWriter} has minted the Map
 * run ARN and returned it in the Map result — the same way an SDK caller obtains it.
 */
@DisplayName("SFN DescribeMapRun")
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class StepFunctionsDescribeMapRunTest {

    private static final String ROLE_ARN = System.getenv("SFN_ROLE_ARN") != null
            ? System.getenv("SFN_ROLE_ARN")
            : "arn:aws:iam::000000000000:role/service-role/test-role";
    /** AWS reports an unbounded Map run as Integer.MAX_VALUE, not as the ASL default of 0. */
    private static final int UNBOUNDED_CONCURRENCY = 2147483647;
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static SfnClient sfn;
    private static S3Client s3;
    private static String bucketName;
    private static String stateMachineArn;
    private static String executionArn;
    private static String mapRunArn;

    @BeforeAll
    static void setup() throws Exception {
        sfn = TestFixtures.sfnClient();
        s3 = TestFixtures.s3Client();

        bucketName = TestFixtures.uniqueName("describe-map-run");
        s3.createBucket(b -> b.bucket(bucketName));

        String definition = """
                {
                  "QueryLanguage": "JSONata",
                  "StartAt": "Fan",
                  "States": {
                    "Fan": {
                      "Type": "Map",
                      "End": true,
                      "Items": [{"n": 1}, {"n": 2}, {"n": 3}],
                      "ItemProcessor": {
                        "ProcessorConfig": {"Mode": "DISTRIBUTED", "ExecutionType": "STANDARD"},
                        "StartAt": "Keep",
                        "States": {"Keep": {"Type": "Pass", "End": true}}
                      },
                      "ResultWriter": {
                        "Resource": "arn:aws:states:::s3:putObject",
                        "Arguments": {"Bucket": "%s", "Prefix": "out"}
                      }
                    }
                  }
                }
                """.formatted(bucketName);

        stateMachineArn = sfn.createStateMachine(b -> b
                .name(TestFixtures.uniqueName("describe-map-run-sm"))
                .definition(definition)
                .roleArn(ROLE_ARN)).stateMachineArn();

        executionArn = sfn.startExecution(b -> b
                .stateMachineArn(stateMachineArn)
                .input("{}")).executionArn();

        DescribeExecutionResponse execution = pollUntilDone(executionArn);
        assertThat(execution.status())
                .as("cause: %s", execution.cause())
                .isEqualTo(ExecutionStatus.SUCCEEDED);

        mapRunArn = MAPPER.readTree(execution.output()).path("MapRunArn").asText();
        assertThat(mapRunArn).contains(":mapRun:");
    }

    @AfterAll
    static void cleanup() {
        if (sfn != null) {
            if (stateMachineArn != null) {
                try {
                    sfn.deleteStateMachine(b -> b.stateMachineArn(stateMachineArn));
                } catch (Exception ignored) {
                }
            }
            sfn.close();
        }
        if (s3 != null) {
            try {
                ListObjectsV2Response objects = s3.listObjectsV2(b -> b.bucket(bucketName));
                for (S3Object object : objects.contents()) {
                    s3.deleteObject(DeleteObjectRequest.builder()
                            .bucket(bucketName).key(object.key()).build());
                }
                s3.deleteBucket(DeleteBucketRequest.builder().bucket(bucketName).build());
            } catch (Exception ignored) {
            }
            s3.close();
        }
    }

    @Test
    @Order(1)
    void describeMapRun_deserializesTheFinishedRun() {
        DescribeMapRunResponse response = sfn.describeMapRun(b -> b.mapRunArn(mapRunArn));

        assertThat(response.status()).isEqualTo(MapRunStatus.SUCCEEDED);
        assertThat(response.executionArn()).isEqualTo(executionArn);
        assertThat(response.maxConcurrency()).isEqualTo(UNBOUNDED_CONCURRENCY);
        assertThat(response.toleratedFailurePercentage()).isEqualTo(0.0f);
        assertThat(response.toleratedFailureCount()).isEqualTo(0);
        assertThat(response.redriveCount()).isEqualTo(0);

        assertItemCounts(response.itemCounts());
        assertExecutionCounts(response.executionCounts());
    }

    @Test
    @Order(2)
    void describeMapRun_unknownArnRaisesResourceNotFound() {
        String unknown = "arn:aws:states:us-east-1:000000000000:mapRun:"
                + TestFixtures.uniqueName("no-such-map-run") + "/"
                + "00000000-0000-0000-0000-000000000000:11111111-1111-1111-1111-111111111111";

        assertThatThrownBy(() -> sfn.describeMapRun(b -> b.mapRunArn(unknown)))
                .isInstanceOf(ResourceNotFoundException.class)
                .hasMessageContaining("Resource not found: '" + unknown + "'");
    }

    // ──────────────────────────── helpers ────────────────────────────

    /** The ten counters of a Map run all of whose three items succeeded. */
    private static void assertItemCounts(MapRunItemCounts counts) {
        assertThat(counts.pending()).isEqualTo(0);
        assertThat(counts.running()).isEqualTo(0);
        assertThat(counts.succeeded()).isEqualTo(3);
        assertThat(counts.failed()).isEqualTo(0);
        assertThat(counts.timedOut()).isEqualTo(0);
        assertThat(counts.aborted()).isEqualTo(0);
        assertThat(counts.total()).isEqualTo(3);
        assertThat(counts.resultsWritten()).isEqualTo(3);
        assertThat(counts.failuresNotRedrivable()).isEqualTo(0);
        assertThat(counts.pendingRedrive()).isEqualTo(0);
    }

    /** One child execution per item: ItemBatcher is not applied, so the shape matches itemCounts. */
    private static void assertExecutionCounts(MapRunExecutionCounts counts) {
        assertThat(counts.pending()).isEqualTo(0);
        assertThat(counts.running()).isEqualTo(0);
        assertThat(counts.succeeded()).isEqualTo(3);
        assertThat(counts.failed()).isEqualTo(0);
        assertThat(counts.timedOut()).isEqualTo(0);
        assertThat(counts.aborted()).isEqualTo(0);
        assertThat(counts.total()).isEqualTo(3);
        assertThat(counts.resultsWritten()).isEqualTo(3);
        assertThat(counts.failuresNotRedrivable()).isEqualTo(0);
        assertThat(counts.pendingRedrive()).isEqualTo(0);
    }

    private static DescribeExecutionResponse pollUntilDone(String execArn) throws InterruptedException {
        for (int i = 0; i < 150; i++) {
            DescribeExecutionResponse resp = sfn.describeExecution(b -> b.executionArn(execArn));
            if (resp.status() != ExecutionStatus.RUNNING) {
                return resp;
            }
            Thread.sleep(100);
        }
        throw new AssertionError("Execution did not complete within timeout: " + execArn);
    }
}
