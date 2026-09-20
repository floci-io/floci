package io.github.hectorvent.floci.services.sagemaker;

import com.github.dockerjava.api.DockerClient;
import io.github.hectorvent.floci.services.s3.S3Service;
import io.github.hectorvent.floci.testing.RestAssuredJsonUtils;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.QuarkusTestProfile;
import io.quarkus.test.junit.TestProfile;
import io.restassured.response.Response;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.HashMap;
import java.util.Map;

import static io.restassured.RestAssured.given;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Proves an {@code ml.*} GPU instance type reaches a real accelerator, end to end through
 * {@code CreateTrainingJob} rather than through the container layer directly.
 *
 * <p>Opt-in for the same reason as {@link
 * io.github.hectorvent.floci.core.common.docker.ContainerGpuDockerIntegrationTest}: it needs
 * hardware no CI runner has. Run it with {@code -Dgpu.integration.enabled=true} on a host with a
 * GPU, naming the device the same way the feature's own configuration does:
 *
 * <pre>
 * ./mvnw test -Dtest=SageMakerGpuDockerIntegrationTest \
 *     -Dgpu.integration.enabled=true \
 *     -Dgpu.integration.cdi-device=nvidia.com/gpu=GPU-&lt;uuid&gt;
 * </pre>
 *
 * <p>Without the flag it skips, leaving {@link SageMakerGpuResolverTest} and {@link
 * SageMakerGpuIntegrationTest} as the coverage that runs by default. Those pin every path that
 * refuses a request; this pins the one that honours it.
 *
 * <p>The assertion is the container's exit code, not a log line. The image runs {@code nvidia-smi},
 * which the runtime injects alongside the device, so it is absent when no device was attached: a
 * job that quietly fell back to CPU exits nonzero and reports Failed. Naming a device also makes
 * the check exact, since the daemon must hand over that card rather than any card.
 *
 * <p>With no device named the test asks for every GPU using the count form, which is the shape to
 * run against Docker. Podman accepts that form and attaches nothing
 * (containers/podman#22645), so that combination fails here by design, which is the same reason
 * {@code mode} defaults to {@code cdi}.
 */
@QuarkusTest
@TestProfile(SageMakerGpuDockerIntegrationTest.GpuHardwareProfile.class)
class SageMakerGpuDockerIntegrationTest {

    private static final Logger LOG = Logger.getLogger(SageMakerGpuDockerIntegrationTest.class);

    private static final String ENABLED_PROPERTY = "gpu.integration.enabled";
    private static final String CDI_DEVICE_PROPERTY = "gpu.integration.cdi-device";
    private static final String IMAGE_PROPERTY = "gpu.integration.image";
    private static final String DEFAULT_IMAGE = "docker.io/nvidia/cuda:12.8.1-base-ubuntu24.04";

    /**
     * Mirrors the deployment-side configuration the feature documents. A named device selects
     * {@code cdi} mode and the allowlist; with none, {@code count} lets the daemon choose.
     */
    public static class GpuHardwareProfile implements QuarkusTestProfile {
        @Override
        public Map<String, String> getConfigOverrides() {
            Map<String, String> overrides = new HashMap<>();
            overrides.put("floci.services.sagemaker.gpu.enabled", "true");
            String device = System.getProperty(CDI_DEVICE_PROPERTY);
            if (device != null && !device.isBlank()) {
                overrides.put("floci.services.sagemaker.gpu.mode", "cdi");
                overrides.put("floci.services.sagemaker.gpu.devices", device);
            } else {
                overrides.put("floci.services.sagemaker.gpu.mode", "count");
            }
            return overrides;
        }
    }

    private static final String AUTH =
            "AWS4-HMAC-SHA256 Credential=test/20260205/us-east-1/sagemaker/aws4_request";

    @Inject
    DockerClient dockerClient;

    @Inject
    S3Service s3Service;

    @BeforeAll
    static void configureRestAssured() {
        RestAssuredJsonUtils.configureAwsContentTypes();
    }

    @BeforeEach
    void requireGpuEnabledDaemon() {
        Assumptions.assumeTrue(Boolean.getBoolean(ENABLED_PROPERTY),
                "GPU hardware tests are opt-in: run with -D" + ENABLED_PROPERTY + "=true on a host with a GPU");
        Assumptions.assumeTrue(isDockerAvailable(),
                "A container daemon must be available for GPU integration tests");
    }

    @Test
    void gpuInstanceTypeTrainsOnTheRequestedDevice() throws Exception {
        String suffix = Long.toString(System.nanoTime(), 36);
        String bucket = "sm-gpu-" + suffix;
        s3Service.createBucket(bucket, "us-east-1");
        String job = "sm-gpu-" + suffix;
        String image = System.getProperty(IMAGE_PROPERTY, DEFAULT_IMAGE);

        // nvidia-smi is injected with the device, so its absence is the clearest signal that
        // nothing was attached. Writing the UUID into /opt/ml/model makes Floci upload the
        // evidence rather than leaving it in a log.
        //
        // Redirect rather than pipe: a pipeline exits with the status of its last command, so
        // `nvidia-smi -L | tee` reports success even when nvidia-smi could not reach the driver.
        // That would be this test passing on a CPU container, which is the exact failure it exists
        // to catch.
        post("SageMaker.CreateTrainingJob", """
                {
                  "TrainingJobName":"%s",
                  "AlgorithmSpecification":{
                    "TrainingImage":"%s",
                    "ContainerEntrypoint":["/bin/sh","-c"],
                    "ContainerArguments":["mkdir -p /opt/ml/model && nvidia-smi -L > /opt/ml/model/devices.txt && cat /opt/ml/model/devices.txt"]
                  },
                  "OutputDataConfig":{"S3OutputPath":"s3://%s/output"},
                  "ResourceConfig":{"InstanceType":"ml.g5.xlarge","InstanceCount":1,"VolumeSizeInGB":1},
                  "StoppingCondition":{"MaxRuntimeInSeconds":300}
                }
                """.formatted(job, image, bucket)).then().statusCode(200);

        Response describe = awaitTerminal(job);
        String status = describe.path("TrainingJobStatus");
        String reason = describe.path("FailureReason");
        assertEquals("Completed", status,
                "ml.g5.xlarge did not reach a GPU: " + (reason == null ? "no FailureReason" : reason));

        String artifact = describe.path("ModelArtifacts.S3ModelArtifacts");
        assertTrue(artifact != null && artifact.endsWith("model.tar.gz"),
                "no model artifact was uploaded: " + artifact);
        LOG.infov("GPU training job completed, artifact at {0}", artifact);
    }

    private Response awaitTerminal(String job) throws Exception {
        long deadline = System.currentTimeMillis() + Duration.ofSeconds(300).toMillis();
        while (System.currentTimeMillis() < deadline) {
            Response describe = post("SageMaker.DescribeTrainingJob",
                    "{\"TrainingJobName\":\"%s\"}".formatted(job));
            describe.then().statusCode(200);
            String status = describe.path("TrainingJobStatus");
            if ("Failed".equals(status) || "Completed".equals(status) || "Stopped".equals(status)) {
                return describe;
            }
            Thread.sleep(1000);
        }
        throw new AssertionError("Timed out waiting for training job " + job);
    }

    private Response post(String target, String body) {
        return given().header("Authorization", AUTH)
                .header("X-Amz-Target", target)
                .contentType("application/x-amz-json-1.1")
                .body(body)
                .when().post("/");
    }

    private boolean isDockerAvailable() {
        try {
            dockerClient.pingCmd().exec();
            return true;
        } catch (Exception e) {
            LOG.warn("No container daemon available for the SageMaker GPU integration test", e);
            return false;
        }
    }
}
