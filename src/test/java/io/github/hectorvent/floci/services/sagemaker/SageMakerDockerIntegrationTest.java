package io.github.hectorvent.floci.services.sagemaker;

import com.github.dockerjava.api.DockerClient;
import io.github.hectorvent.floci.services.s3.S3Service;
import io.github.hectorvent.floci.testing.RestAssuredJsonUtils;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Map;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.notNullValue;
import static org.junit.jupiter.api.Assertions.assertNotNull;

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
        String script = "cat > /server.py <<'PY'\nfrom http.server import BaseHTTPRequestHandler,HTTPServer\nclass H(BaseHTTPRequestHandler):\n def do_GET(self):\n  self.send_response(200 if self.path == '/ping' else 404); self.end_headers()\n def do_POST(self):\n  n=int(self.headers.get('content-length','0')); b=self.rfile.read(n); self.send_response(200); self.send_header('Content-Type','text/plain'); self.end_headers(); self.wfile.write(b.upper())\nHTTPServer(('0.0.0.0',8080),H).serve_forever()\nPY\npython /server.py";
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

    private void waitForTraining(String job, String status) throws Exception {
        long deadline = System.currentTimeMillis() + Duration.ofSeconds(120).toMillis();
        while (System.currentTimeMillis() < deadline) {
            String current = post("SageMaker.DescribeTrainingJob", "{\"TrainingJobName\":\"%s\"}".formatted(job)).then().statusCode(200).extract().path("TrainingJobStatus");
            if (status.equals(current)) return;
            if ("Failed".equals(current)) throw new AssertionError("Training failed");
            Thread.sleep(1000);
        }
        throw new AssertionError("Timed out waiting for training job");
    }

    private void waitForEndpoint(String endpoint, String status) throws Exception {
        long deadline = System.currentTimeMillis() + Duration.ofSeconds(150).toMillis();
        while (System.currentTimeMillis() < deadline) {
            String current = post("SageMaker.DescribeEndpoint", "{\"EndpointName\":\"%s\"}".formatted(endpoint)).then().statusCode(200).extract().path("EndpointStatus");
            if (status.equals(current)) return;
            if ("Failed".equals(current)) throw new AssertionError("Endpoint failed");
            Thread.sleep(1000);
        }
        throw new AssertionError("Timed out waiting for endpoint");
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
