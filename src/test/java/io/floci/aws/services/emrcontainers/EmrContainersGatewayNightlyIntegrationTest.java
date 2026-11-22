package io.floci.aws.services.emrcontainers;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.github.dockerjava.api.DockerClient;
import com.github.dockerjava.api.command.PullImageResultCallback;
import io.floci.aws.testing.AwsTestClients;
import io.floci.aws.testing.CloudFormationSettingsProfile;
import io.floci.aws.testing.DockerAvailability;
import io.floci.aws.testing.EksRequests;
import io.floci.aws.testing.EnforcementFixtures;
import io.floci.aws.testing.PartitionCleanup;
import io.floci.aws.testing.RestAssuredJsonUtils;
import io.floci.aws.testing.S3TestClients;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;
import io.restassured.RestAssured;
import io.restassured.specification.RequestSpecification;
import jakarta.inject.Inject;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.junit.jupiter.api.extension.RegisterExtension;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.WebSocket;
import java.time.Duration;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

import static io.restassured.RestAssured.given;
import static org.awaitility.Awaitility.await;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * An EMR on EKS managed endpoint, run for real in the {@code docker} runner mode: it runs a Jupyter Enterprise Gateway
 * in its release's {@code notebook-spark} image from Amazon ECR Public, and a Jupyter client reaches it at its
 * {@code serverUrl} with a session token, starts a PySpark kernel there, and runs code on Floci's S3 over the kernel's
 * WebSocket, as the guide's "Example: Deploy a JupyterLab notebook" does through {@code JUPYTER_GATEWAY_URL}.
 *
 * <p>A nightly check: the release's notebook image is about 6 GB.
 */
@QuarkusTest
@TestProfile(CloudFormationSettingsProfile.class)
@EnabledIfSystemProperty(named = "nightly.checks.enabled", matches = "true")
// One instance, so that the image is pulled once for the class.
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
// The endpoint's gateway answers within its five minutes, the kernel's WebSocket opens within its two, once its Spark
// session is up, and each of its two cells answers within its two: eleven minutes, and one for the requests between.
// With the image's pull of up to 21, 33 minutes, which the Nightly checks' docker-runner group counts.
@Timeout(value = 12, unit = TimeUnit.MINUTES)
class EmrContainersGatewayNightlyIntegrationTest {

    private static final String ACCOUNT = "000000000000";
    private static final String REGION = "us-east-1";
    private static final String ROLE = "arn:aws:iam::000000000000:role/emr-job";
    private static final String RELEASE = "emr-7.5.0-latest";
    // The release's notebook image, which the gateway's default template names.
    private static final String NOTEBOOK_IMAGE = "public.ecr.aws/emr-on-eks/notebook-spark/emr-7.5.0:latest";
    private static final Duration ACTIVE_TIMEOUT = Duration.ofMinutes(5);
    // jupyter_server holds a kernel's handshake until the kernel answers, up to its own minute, and the PySpark kernel
    // answers once its Spark session is up.
    private static final Duration HANDSHAKE_TIMEOUT = Duration.ofMinutes(2);
    private static final Duration REPLY_TIMEOUT = Duration.ofMinutes(2);
    private static final ObjectMapper JSON = new ObjectMapper();

    @RegisterExtension
    final PartitionCleanup cleanup = new PartitionCleanup();

    @Inject
    DockerClient docker;

    @BeforeAll
    static void configure() {
        RestAssuredJsonUtils.configureAwsContentTypes();
    }

    // The notebook image's pull alone may take its twenty minutes.
    @BeforeAll
    @Timeout(value = 21, unit = TimeUnit.MINUTES)
    void pullImage() throws InterruptedException {
        // Enabled, the check needs Docker: without a daemon it fails rather than pass having run nothing.
        assertTrue(DockerAvailability.isAvailable(), "the nightly check needs a Docker daemon");
        docker.pullImageCmd(NOTEBOOK_IMAGE).exec(new PullImageResultCallback()).awaitCompletion(20, TimeUnit.MINUTES);
    }

    @Test
    void pysparkKernelOnTheEndpointsGatewayRunsCodeOnFlocisS3() throws Exception {
        S3Client s3 = AwsTestClients.create(S3Client.builder().forcePathStyle(true), ACCOUNT, Region.US_EAST_1);
        cleanup.register(s3::close);
        String bucket = "emr-eks-jeg-" + UUID.randomUUID().toString().substring(0, 8);
        s3.createBucket(request -> request.bucket(bucket));
        cleanup.register(() -> S3TestClients.deleteBucket(s3, bucket));
        s3.putObject(request -> request.bucket(bucket).key("lines.txt"), RequestBody.fromString("a\nb\nc\n"));
        String virtualClusterId = virtualCluster();
        String endpointId = emr().body("""
                        {"name": "nightly", "type": "JUPYTER_ENTERPRISE_GATEWAY", "releaseLabel": "%s",
                         "executionRoleArn": "%s", "clientToken": "%s"}
                        """.formatted(RELEASE, ROLE, UUID.randomUUID()))
                .post("/virtualclusters/" + virtualClusterId + "/endpoints")
                .then().statusCode(200)
                .extract().path("id");
        cleanup.register(() -> emr().delete("/virtualclusters/" + virtualClusterId + "/endpoints/" + endpointId)
                .then().statusCode(200));

        await().atMost(ACTIVE_TIMEOUT).pollInterval(Duration.ofSeconds(1)).until(() -> "ACTIVE".equals(
                emr().get("/virtualclusters/" + virtualClusterId + "/endpoints/" + endpointId)
                        .then().statusCode(200).extract().path("endpoint.state")));
        String serverUrl = emr().get("/virtualclusters/" + virtualClusterId + "/endpoints/" + endpointId)
                .then().extract().path("endpoint.serverUrl");
        // On the profile's base URL; the test's own server is on a port of its own.
        String path = URI.create(serverUrl).getPath();
        assertEquals(EmrContainersService.GATEWAY_PATH + endpointId, path);
        String gateway = "http://localhost:" + RestAssured.port + path;
        String token = emr().body("""
                        {"executionRoleArn": "%s", "credentialType": "TOKEN"}
                        """.formatted(ROLE))
                .post("/virtualclusters/" + virtualClusterId + "/endpoints/" + endpointId + "/credentials")
                .then().statusCode(200)
                .extract().path("credentials.token");

        HttpClient http = HttpClient.newHttpClient();
        assertEquals(401, http.send(HttpRequest.newBuilder(URI.create(gateway + "/api/kernelspecs")).build(),
                HttpResponse.BodyHandlers.discarding()).statusCode(), "a request without the session token");
        HttpResponse<String> specs = http.send(HttpRequest.newBuilder(URI.create(gateway + "/api/kernelspecs"))
                .header("Authorization", "token " + token).build(), HttpResponse.BodyHandlers.ofString());
        assertEquals(200, specs.statusCode(), specs.body());
        assertTrue(JSON.readTree(specs.body()).path("kernelspecs").has("spark-python-kubernetes"), specs.body());
        // As a Jupyter client sends its KERNEL_ variables.
        HttpResponse<String> started = http.send(HttpRequest.newBuilder(URI.create(gateway + "/api/kernels"))
                .header("Authorization", "token " + token)
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString("""
                        {"name": "spark-python-kubernetes",
                         "env": {"KERNEL_EXTRA_SPARK_OPTS": "--conf spark.app.name=from-kernel-env"}}
                        """)).build(), HttpResponse.BodyHandlers.ofString());
        assertEquals(201, started.statusCode(), started.body());
        String kernelId = JSON.readTree(started.body()).path("id").textValue();

        Messages messages = new Messages();
        WebSocket socket = http.newWebSocketBuilder().header("Authorization", "token " + token)
                .buildAsync(URI.create(gateway.replaceFirst("^http", "ws") + "/api/kernels/" + kernelId
                        + "/channels"), messages)
                .get(HANDSHAKE_TIMEOUT.toSeconds(), TimeUnit.SECONDS);
        String session = UUID.randomUUID().toString();
        assertEquals("3", execute(socket, messages, session,
                "spark.read.text(\"s3://" + bucket + "/lines.txt\").count()"));
        assertEquals("'from-kernel-env'", execute(socket, messages, session, "spark.conf.get(\"spark.app.name\")"));
        socket.sendClose(WebSocket.NORMAL_CLOSURE, "done").get(10, TimeUnit.SECONDS);
        assertEquals(204, http.send(HttpRequest.newBuilder(URI.create(gateway + "/api/kernels/" + kernelId))
                .header("Authorization", "token " + token).DELETE().build(),
                HttpResponse.BodyHandlers.discarding()).statusCode());
    }

    // Runs code in the kernel, as a Jupyter client sends an execute_request, and answers its result's text/plain.
    private static String execute(WebSocket socket, Messages messages, String session, String code) throws Exception {
        String messageId = UUID.randomUUID().toString();
        ObjectNode request = JSON.createObjectNode();
        request.putObject("header").put("msg_id", messageId).put("username", "nightly").put("session", session)
                .put("msg_type", "execute_request").put("version", "5.3").put("date", Instant.now().toString());
        request.putObject("parent_header");
        request.putObject("metadata");
        request.putObject("content").put("code", code).put("silent", false).put("store_history", true)
                .put("allow_stdin", false).put("stop_on_error", true).putObject("user_expressions");
        request.putArray("buffers");
        request.put("channel", "shell");
        socket.sendText(JSON.writeValueAsString(request), true).get(10, TimeUnit.SECONDS);
        return await("an execute_result for " + code).atMost(REPLY_TIMEOUT).pollInterval(Duration.ofMillis(100))
                .until(() -> result(messages, messageId), Objects::nonNull);
    }

    // The text/plain of the result of the request messageId among the messages come so far, or null until it comes.
    private static String result(Messages messages, String messageId) throws Exception {
        for (String text = messages.received.poll(); text != null; text = messages.received.poll()) {
            JsonNode message = JSON.readTree(text);
            if (!messageId.equals(message.at("/parent_header/msg_id").textValue())) {
                continue;
            }
            String type = message.at("/header/msg_type").textValue();
            if ("execute_result".equals(type)) {
                return message.at("/content/data/text~1plain").textValue();
            }
            if ("error".equals(type)) {
                fail("the kernel raised: " + message.path("content"));
            }
        }
        return null;
    }

    private String virtualCluster() {
        String eksCluster = "emr-" + UUID.randomUUID().toString().substring(0, 8);
        EksRequests.eks(ACCOUNT, REGION)
                .body("""
                        {"name": "%s", "roleArn": "arn:aws:iam::000000000000:role/eks-role", "version": "1.29"}
                        """.formatted(eksCluster))
                .post("/clusters")
                .then().statusCode(200);
        cleanup.register(() -> EksRequests.eks(ACCOUNT, REGION).delete("/clusters/" + eksCluster)
                .then().statusCode(200));
        String id = emr().body("""
                        {"name": "nightly", "clientToken": "%s",
                         "containerProvider": {"type": "EKS", "id": "%s", "info": {"eksInfo": {"namespace": "spark"}}}}
                        """.formatted(UUID.randomUUID(), eksCluster))
                .post("/virtualclusters")
                .then().statusCode(200)
                .extract().path("id");
        cleanup.register(() -> emr().delete("/virtualclusters/" + id).then().statusCode(200));
        return id;
    }

    private static RequestSpecification emr() {
        return given().header("Authorization", EnforcementFixtures.auth(ACCOUNT, REGION, "emr-containers"))
                .contentType("application/json")
                .urlEncodingEnabled(false);
    }

    /** The kernel's messages, each whole, as its WebSocket sends them. */
    private static final class Messages implements WebSocket.Listener {
        final BlockingQueue<String> received = new LinkedBlockingQueue<>();
        private final StringBuilder partial = new StringBuilder();

        @Override
        public CompletionStage<?> onText(WebSocket webSocket, CharSequence data, boolean last) {
            partial.append(data);
            if (last) {
                received.add(partial.toString());
                partial.setLength(0);
            }
            webSocket.request(1);
            return null;
        }
    }
}
