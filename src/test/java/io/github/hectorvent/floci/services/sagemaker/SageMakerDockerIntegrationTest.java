package io.github.hectorvent.floci.services.sagemaker;

import com.github.dockerjava.api.DockerClient;
import com.github.dockerjava.api.async.ResultCallback;
import com.github.dockerjava.api.model.Event;
import com.github.dockerjava.api.model.EventType;
import io.github.hectorvent.floci.services.s3.S3Service;
import io.github.hectorvent.floci.testing.RestAssuredJsonUtils;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static io.restassured.RestAssured.given;
import static org.awaitility.Awaitility.await;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.notNullValue;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

@QuarkusTest
class SageMakerDockerIntegrationTest {
    private static final String AUTH =
            "AWS4-HMAC-SHA256 Credential=test/20260205/us-east-1/sagemaker/aws4_request";

    @Inject DockerClient dockerClient;
    @Inject S3Service s3Service;

    @BeforeAll
    static void configureRestAssured() {
        RestAssuredJsonUtils.configureAwsContentTypes();
    }

    @BeforeEach
    void requireDocker() {
        Assumptions.assumeTrue(isDockerAvailable(), "Docker daemon must be available for SageMaker Docker tests");
    }

    @Test
    void trainingJobRunsRealContainerAndUploadsModelArtifacts() throws Exception {
        String suffix = Long.toString(System.nanoTime(), 36);
        String bucket = "sm-train-" + suffix;
        s3Service.createBucket(bucket, "us-east-1");
        s3Service.putObject(bucket, "input/data.txt", "hello".getBytes(StandardCharsets.UTF_8), "text/plain", Map.of());
        String job = "sm-train-" + suffix;
        post("SageMaker.CreateTrainingJob", """
                {
                  "TrainingJobName":"%s",
                  "AlgorithmSpecification":{
                    "TrainingImage":"public.ecr.aws/docker/library/busybox:stable",
                    "ContainerEntrypoint":["/bin/sh","-c"],
                    "ContainerArguments":["mkdir -p /opt/ml/model && cp /opt/ml/input/data/train/data.txt /opt/ml/model/model.txt"]
                  },
                  "InputDataConfig":[{"ChannelName":"train","DataSource":{"S3DataSource":{"S3Uri":"s3://%s/input"}},"ContentType":"text/plain","TrainingInputMode":"File"}],
                  "OutputDataConfig":{"S3OutputPath":"s3://%s/output"},
                  "ResourceConfig":{"InstanceType":"ml.m5.large","InstanceCount":1,"VolumeSizeInGB":1},
                  "StoppingCondition":{"MaxRuntimeInSeconds":60}
                }
                """.formatted(job, bucket, bucket)).then().statusCode(200).body("TrainingJobArn", notNullValue());

        waitForTraining(job, "Completed");
        String artifact = post("SageMaker.DescribeTrainingJob", "{\"TrainingJobName\":\"%s\"}".formatted(job))
                .then().statusCode(200).body("TrainingJobStatus", equalTo("Completed"))
                .extract().path("ModelArtifacts.S3ModelArtifacts");
        S3Uri uri = S3Uri.parse(artifact);
        assertNotNull(s3Service.getObject(uri.bucket(), uri.key()).getData());
    }

    @Test
    void endpointHostsRealContainerAndRuntimeProxiesInvocations() throws Exception {
        String suffix = Long.toString(System.nanoTime(), 36);
        String model = "sm-ep-model-" + suffix;
        String cfg = "sm-ep-cfg-" + suffix;
        String endpoint = "sm-endpoint-" + suffix;
        String script = "trap 'exit 0' TERM; cat > /server.py <<'PY'\nfrom http.server import BaseHTTPRequestHandler,HTTPServer\nclass H(BaseHTTPRequestHandler):\n def do_GET(self):\n  self.send_response(200 if self.path == '/ping' else 404); self.end_headers()\n def do_POST(self):\n  n=int(self.headers.get('content-length','0')); b=self.rfile.read(n); self.send_response(200); self.send_header('Content-Type','text/plain'); self.end_headers(); self.wfile.write(b.upper())\nHTTPServer(('0.0.0.0',8080),H).serve_forever()\nPY\npython /server.py & wait $!";
        post("SageMaker.CreateModel", """
                {"ModelName":"%s","PrimaryContainer":{"Image":"public.ecr.aws/docker/library/python:3-alpine","ContainerEntrypoint":["/bin/sh","-c"],"ContainerArguments":[%s]}}
                """.formatted(model, json(script))).then().statusCode(200);
        post("SageMaker.CreateEndpointConfig", """
                {"EndpointConfigName":"%s","ProductionVariants":[{"VariantName":"AllTraffic","ModelName":"%s","InitialInstanceCount":1,"InstanceType":"ml.t2.medium","InitialVariantWeight":1.0}]}
                """.formatted(cfg, model)).then().statusCode(200);
        post("SageMaker.CreateEndpoint", "{\"EndpointName\":\"%s\",\"EndpointConfigName\":\"%s\"}".formatted(endpoint, cfg))
                .then().statusCode(200);
        waitForEndpoint(endpoint, "InService");
        given().header("Authorization", "AWS4-HMAC-SHA256 Credential=test/20260205/us-east-1/runtime.sagemaker/aws4_request")
                .contentType("text/plain").body("hello")
                .when().post("/endpoints/%s/invocations".formatted(endpoint))
                .then().statusCode(200).body(equalTo("HELLO"));
        post("SageMaker.DeleteEndpoint", "{\"EndpointName\":\"%s\"}".formatted(endpoint)).then().statusCode(200);
    }

    @Test
    void stoppingATrainingJobDoesNotGetRelabeledFailed() throws Exception {
        String suffix = Long.toString(System.nanoTime(), 36);
        String bucket = "sm-stop-" + suffix;
        s3Service.createBucket(bucket, "us-east-1");
        String job = "sm-stop-" + suffix;
        post("SageMaker.CreateTrainingJob", """
                {
                  "TrainingJobName":"%s",
                  "AlgorithmSpecification":{
                    "TrainingImage":"public.ecr.aws/docker/library/busybox:stable",
                    "ContainerEntrypoint":["/bin/sh","-c"],
                    "ContainerArguments":["trap 'exit 143' TERM; mkfifo /tmp/hold; cat /tmp/hold & wait"]
                  },
                  "OutputDataConfig":{"S3OutputPath":"s3://%s/output"},
                  "ResourceConfig":{"InstanceType":"ml.m5.large","InstanceCount":1,"VolumeSizeInGB":1},
                  "StoppingCondition":{"MaxRuntimeInSeconds":300}
                }
                """.formatted(job, bucket)).then().statusCode(200);
        // Wait for the container to actually be running, blocked on an empty pipe, so the stop
        // races a real running container rather than one still being staged.
        awaitContainerRunning("floci-sagemaker-training-" + job);
        post("SageMaker.StopTrainingJob", "{\"TrainingJobName\":\"%s\"}".formatted(job)).then().statusCode(200);
        post("SageMaker.DescribeTrainingJob", "{\"TrainingJobName\":\"%s\"}".formatted(job))
                .then().statusCode(200).body("TrainingJobStatus", equalTo("Stopped"));
        // The runner's exit-code handling races the container disappearing after the stop. It
        // polls the container every 500ms, so keep watching for three of those polls and confirm
        // it never relabels the stop a failure once it observes the container gone.
        await().during(Duration.ofMillis(1500)).atMost(Duration.ofSeconds(5)).pollInterval(Duration.ofMillis(100))
                .untilAsserted(() -> post("SageMaker.DescribeTrainingJob", "{\"TrainingJobName\":\"%s\"}".formatted(job))
                        .then().statusCode(200).body("TrainingJobStatus", equalTo("Stopped")));
    }

    @Test
    void deletingAnEndpointDuringStartupDoesNotResurrectIt() throws Exception {
        String suffix = Long.toString(System.nanoTime(), 36);
        String model = "sm-del-model-" + suffix;
        String cfg = "sm-del-cfg-" + suffix;
        String endpoint = "sm-del-endpoint-" + suffix;
        // DeleteEndpoint returns while CreateEndpoint's async start is still bringing the container
        // up, well before /ping can succeed, so the start it races is always still in flight.
        String script = "trap 'exit 0' TERM; cat > /server.py <<'PY'\nfrom http.server import BaseHTTPRequestHandler,HTTPServer\nclass H(BaseHTTPRequestHandler):\n def do_GET(self):\n  self.send_response(200); self.end_headers()\nHTTPServer(('0.0.0.0',8080),H).serve_forever()\nPY\npython /server.py & wait $!";
        post("SageMaker.CreateModel", """
                {"ModelName":"%s","PrimaryContainer":{"Image":"public.ecr.aws/docker/library/python:3-alpine","ContainerEntrypoint":["/bin/sh","-c"],"ContainerArguments":[%s]}}
                """.formatted(model, json(script))).then().statusCode(200);
        post("SageMaker.CreateEndpointConfig", """
                {"EndpointConfigName":"%s","ProductionVariants":[{"VariantName":"AllTraffic","ModelName":"%s","InitialInstanceCount":1,"InstanceType":"ml.t2.medium","InitialVariantWeight":1.0}]}
                """.formatted(cfg, model)).then().statusCode(200);
        // A superseded start tears down the container it brought up, so that container being
        // destroyed marks the point where the worker has decided. Watching Docker's event stream
        // rather than polling for the container means a container that is created and discarded
        // between two polls is still seen: events are queued and replayed from the watch's start.
        try (ContainerDestroyWatch discarded =
                new ContainerDestroyWatch(dockerClient, "floci-sagemaker-endpoint-" + endpoint)) {
            post("SageMaker.CreateEndpoint", "{\"EndpointName\":\"%s\",\"EndpointConfigName\":\"%s\"}".formatted(endpoint, cfg))
                    .then().statusCode(200);
            post("SageMaker.DeleteEndpoint", "{\"EndpointName\":\"%s\"}".formatted(endpoint)).then().statusCode(200);
            // Let the superseded start worker finish (it should discard its result, not persist it).
            assertTrue(discarded.await(Duration.ofSeconds(30)),
                    "the superseded start should have discarded the container it brought up");
        }
        post("SageMaker.DescribeEndpoint", "{\"EndpointName\":\"%s\"}".formatted(endpoint)).then().statusCode(400);
    }

    /**
     * Watches Docker's event stream for the destruction of a container whose name contains the
     * given fragment. The stream is opened before the container exists and replays from that
     * moment, so unlike polling for the container this cannot miss one that came and went inside
     * a single poll interval.
     */
    private static final class ContainerDestroyWatch implements AutoCloseable {
        private final CountDownLatch destroyed = new CountDownLatch(1);
        private final ResultCallback.Adapter<Event> events;

        ContainerDestroyWatch(DockerClient dockerClient, String nameFragment) {
            events = dockerClient.eventsCmd()
                    .withEventTypeFilter(EventType.CONTAINER)
                    .withEventFilter("destroy")
                    .withSince(String.valueOf(Instant.now().getEpochSecond()))
                    .exec(new ResultCallback.Adapter<Event>() {
                        @Override
                        public void onNext(Event event) {
                            String name = event.getActor() == null
                                    ? null : event.getActor().getAttributes().get("name");
                            if (name != null && name.contains(nameFragment)) {
                                destroyed.countDown();
                            }
                        }
                    });
        }

        boolean await(Duration timeout) throws InterruptedException {
            return destroyed.await(timeout.toMillis(), TimeUnit.MILLISECONDS);
        }

        @Override
        public void close() throws IOException {
            events.close();
        }
    }

    private void awaitContainerRunning(String nameFragment) {
        await().atMost(Duration.ofSeconds(30)).pollInterval(Duration.ofMillis(100)).until(() ->
                !dockerClient.listContainersCmd().withNameFilter(List.of(nameFragment)).exec().isEmpty());
    }

    private void waitForTraining(String job, String status) {
        String current = awaitTerminalStatus(status, () -> post("SageMaker.DescribeTrainingJob",
                "{\"TrainingJobName\":\"%s\"}".formatted(job)).then().statusCode(200).extract().path("TrainingJobStatus"));
        if ("Failed".equals(current) && !status.equals(current)) {
            throw new AssertionError("Training failed");
        }
    }

    private void waitForEndpoint(String endpoint, String status) {
        String current = awaitTerminalStatus(status, () -> post("SageMaker.DescribeEndpoint",
                "{\"EndpointName\":\"%s\"}".formatted(endpoint)).then().statusCode(200).extract().path("EndpointStatus"));
        if ("Failed".equals(current) && !status.equals(current)) {
            throw new AssertionError("Endpoint failed");
        }
    }

    /** Polls until the resource reaches the wanted status, or Failed, and returns the status it reached. */
    private static String awaitTerminalStatus(String wanted, Callable<String> currentStatus) {
        return await().atMost(Duration.ofSeconds(150)).pollDelay(Duration.ZERO).pollInterval(Duration.ofMillis(100))
                .until(currentStatus, current -> wanted.equals(current) || "Failed".equals(current));
    }

    private io.restassured.response.Response post(String target, String body) {
        return given().header("Authorization", AUTH).header("X-Amz-Target", target)
                .contentType("application/x-amz-json-1.1").body(body).when().post("/");
    }

    private boolean isDockerAvailable() {
        try { dockerClient.pingCmd().exec(); return true; } catch (Exception e) { return false; }
    }

    private String json(String value) {
        return "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n") + "\"";
    }
}
