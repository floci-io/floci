package io.github.hectorvent.floci.services.sagemaker;

import io.github.hectorvent.floci.testing.RestAssuredJsonUtils;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;
import io.quarkus.test.junit.QuarkusTestProfile;
import io.restassured.response.Response;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.Map;

import static io.restassured.RestAssured.given;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Covers the GPU refusal paths, which need no GPU and no container daemon.
 *
 * <p>{@link SageMakerGpuResolver} raises before the runner asks the daemon for anything, and
 * {@code removeIfExists} on the line above it swallows a missing daemon, so every case here
 * reaches its verdict on a plain CI runner. The hardware path is the opposite and lives in
 * {@link SageMakerGpuDockerIntegrationTest}, which skips without a GPU.
 *
 * <p>What is being pinned is the contract that a request Floci cannot honour fails with a
 * reason naming the fix, rather than falling back to CPU. A job that reports {@code Completed}
 * after training on the wrong hardware leaves an artifact that looks legitimate.
 *
 * <p>The profile turns the feature on because {@code application.yml} ships it off. Devices are
 * deliberately left unset, which is what makes the third case reachable.
 */
@QuarkusTest
@TestProfile(SageMakerGpuIntegrationTest.GpuEnabledProfile.class)
class SageMakerGpuIntegrationTest {

    public static class GpuEnabledProfile implements QuarkusTestProfile {
        @Override
        public Map<String, String> getConfigOverrides() {
            return Map.of("floci.services.sagemaker.gpu.enabled", "true");
        }
    }

    private static final String AUTH =
            "AWS4-HMAC-SHA256 Credential=test/20260205/us-east-1/sagemaker/aws4_request";

    @BeforeAll
    static void configureRestAssured() {
        RestAssuredJsonUtils.configureAwsContentTypes();
    }

    @Test
    void gpuFamilyMissingFromTheCatalogFailsNamingTheCatalog() throws Exception {
        String job = submit("ml.p5.48xlarge", 1);
        String reason = awaitFailureReason(job);

        assertTrue(reason.contains("ml.p5.48xlarge"), reason);
        assertTrue(reason.contains("catalog"), reason);
    }

    @Test
    void instanceCountAboveOneFailsRatherThanTrainingOnOneContainer() throws Exception {
        String job = submit("ml.g5.xlarge", 2);
        String reason = awaitFailureReason(job);

        assertTrue(reason.contains("InstanceCount 2"), reason);
        assertTrue(reason.contains("ml.g5.xlarge"), reason);
    }

    @Test
    void cdiModeWithNoConfiguredDevicesFailsRatherThanTakingEveryGpu() throws Exception {
        String job = submit("ml.g5.xlarge", 1);
        String reason = awaitFailureReason(job);

        assertTrue(reason.contains("floci.services.sagemaker.gpu.devices"), reason);
    }

    /**
     * Enabling the feature must not change what a CPU instance type does. The job's outcome
     * depends on whether a daemon is present, so only the reason is asserted: whatever happens,
     * it must not be the resolver refusing the request.
     */
    @Test
    void cpuInstanceTypeIsNotRefusedWhenGpuSupportIsEnabled() throws Exception {
        String job = submit("ml.m5.large", 1);
        Response describe = awaitTerminal(job);
        String reason = describe.path("FailureReason") == null ? "" : describe.path("FailureReason");

        assertFalse(reason.contains("floci.services.sagemaker.gpu"), reason);
        assertFalse(reason.contains("catalog"), reason);
    }

    private String submit(String instanceType, int instanceCount) {
        String job = "sm-gpu-" + Long.toString(System.nanoTime(), 36);
        post("SageMaker.CreateTrainingJob", """
                {
                  "TrainingJobName":"%s",
                  "AlgorithmSpecification":{"TrainingImage":"busybox:stable","TrainingInputMode":"File"},
                  "OutputDataConfig":{"S3OutputPath":"s3://sm-gpu-out/output"},
                  "ResourceConfig":{"InstanceType":"%s","InstanceCount":%d,"VolumeSizeInGB":1},
                  "StoppingCondition":{"MaxRuntimeInSeconds":60}
                }
                """.formatted(job, instanceType, instanceCount))
                .then().statusCode(200);
        return job;
    }

    private String awaitFailureReason(String job) throws Exception {
        Response describe = awaitTerminal(job);
        assertEquals("Failed", describe.path("TrainingJobStatus"),
                "expected the request to be refused, not honoured");
        String reason = describe.path("FailureReason");
        assertTrue(reason != null && !reason.isBlank(), "Failed with no FailureReason");
        return reason;
    }

    private Response awaitTerminal(String job) throws Exception {
        long deadline = System.currentTimeMillis() + Duration.ofSeconds(60).toMillis();
        while (System.currentTimeMillis() < deadline) {
            Response describe = post("SageMaker.DescribeTrainingJob",
                    "{\"TrainingJobName\":\"%s\"}".formatted(job));
            describe.then().statusCode(200);
            String status = describe.path("TrainingJobStatus");
            if ("Failed".equals(status) || "Completed".equals(status) || "Stopped".equals(status)) {
                return describe;
            }
            Thread.sleep(200);
        }
        throw new AssertionError("Timed out waiting for training job " + job + " to reach a terminal state");
    }

    private Response post(String target, String body) {
        return given().header("Authorization", AUTH)
                .header("X-Amz-Target", target)
                .contentType("application/x-amz-json-1.1")
                .body(body)
                .when().post("/");
    }
}
