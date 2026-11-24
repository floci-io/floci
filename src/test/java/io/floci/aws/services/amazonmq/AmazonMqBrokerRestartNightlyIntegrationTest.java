package io.floci.aws.services.amazonmq;

import com.github.dockerjava.api.DockerClient;
import com.github.dockerjava.api.model.Container;
import io.floci.aws.config.EmulatorConfig;
import io.floci.aws.core.common.docker.ContainerStorageHelper;
import io.floci.aws.testing.DockerAvailability;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.QuarkusTestProfile;
import io.quarkus.test.junit.TestProfile;
import io.restassured.specification.RequestSpecification;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import static io.restassured.RestAssured.given;
import static org.awaitility.Awaitility.await;
import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * #5664: an Amazon MQ broker comes back after Floci restarts with what its RabbitMQ node holds. Shutdown removes the
 * broker's container; the startup restore starts a new one on the broker's volume, under the same node name, so the
 * admin user, whose password Floci never stored, and a vhost created through the console are still there. Removing
 * the container stands in for the shutdown, and the restore is what startup calls.
 *
 * <p>A nightly check: it runs a real RabbitMQ broker, which the shared test configuration mocks.
 */
@QuarkusTest
@TestProfile(AmazonMqBrokerRestartNightlyIntegrationTest.Profile.class)
@EnabledIfSystemProperty(named = "nightly.checks.enabled", matches = "true")
// Bounded waits: the RabbitMQ image's pull, then the broker starting twice.
@Timeout(value = 10, unit = TimeUnit.MINUTES)
class AmazonMqBrokerRestartNightlyIntegrationTest {

    /** Real RabbitMQ brokers. */
    public static final class Profile implements QuarkusTestProfile {
        @Override
        public Map<String, String> getConfigOverrides() {
            return Map.of("floci.services.amazonmq.mock", "false");
        }
    }

    private static final String AUTH = "AWS4-HMAC-SHA256 Credential=test/20260205/us-east-1/mq/aws4_request";
    private static final String ADMIN = "admin";
    private static final String PASSWORD = "RestartCheck1234";
    private static final String VHOST = "restart-check";
    private static final Duration BROKER_TIMEOUT = Duration.ofMinutes(4);

    // HTTP/1.1: the RabbitMQ console closes the connection on the h2c upgrade the client otherwise offers.
    private final HttpClient http = HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1)
            .connectTimeout(Duration.ofSeconds(5)).build();

    @Inject
    DockerClient dockerClient;

    @Inject
    EmulatorConfig config;

    @Inject
    AmazonMqService amazonMqService;

    @BeforeEach
    void requireDocker() {
        Assumptions.assumeTrue(DockerAvailability.answers(dockerClient),
                "Docker daemon must be available for the Amazon MQ restart check");
    }

    @Test
    void aBrokerKeepsItsUsersAndVhostsAcrossARestart() throws Exception {
        String brokerId = mq().contentType("application/json").body("""
                {"brokerName": "restart-%s", "engineType": "RABBITMQ", "deploymentMode": "SINGLE_INSTANCE",
                 "hostInstanceType": "mq.t3.micro", "publiclyAccessible": false,
                 "users": [{"username": "%s", "password": "%s", "consoleAccess": true}]}
                """.formatted(UUID.randomUUID().toString().substring(0, 8), ADMIN, PASSWORD))
                .when().post("/v1/brokers")
                .then().statusCode(200).extract().path("brokerId");
        try {
            String console = awaitRunning(brokerId);
            assertEquals(201, console(console, "PUT", "/api/vhosts/" + VHOST).statusCode());

            for (Container container : dockerClient.listContainersCmd().withShowAll(true)
                    .withNameFilter(List.of(ContainerStorageHelper.dockerName(config, "amazonmq-" + brokerId))).exec()) {
                dockerClient.removeContainerCmd(container.getId()).withForce(true).exec();
            }
            amazonMqService.restorePersistedRuntime();

            String restoredConsole = awaitRunning(brokerId);
            assertEquals(200, console(restoredConsole, "GET", "/api/vhosts/" + VHOST).statusCode());
        } finally {
            mq().when().delete("/v1/brokers/{id}", brokerId);
        }
    }

    /** Waits for the broker to be RUNNING and returns its console URL. */
    private String awaitRunning(String brokerId) {
        await("broker " + brokerId + " running").atMost(BROKER_TIMEOUT).pollInterval(Duration.ofSeconds(2))
                .until(() -> mq().when().get("/v1/brokers/{id}", brokerId).then().statusCode(200)
                        .extract().path("brokerState"), state -> {
                            if ("CREATION_FAILED".equals(state)) {
                                throw new AssertionError("broker " + brokerId + " failed to start");
                            }
                            return "RUNNING".equals(state);
                        });
        return mq().when().get("/v1/brokers/{id}", brokerId).then().statusCode(200)
                .extract().path("brokerInstances[0].consoleURL");
    }

    /** Calls the broker's RabbitMQ management API as its admin user. */
    private HttpResponse<String> console(String consoleUrl, String method, String path) throws Exception {
        String credentials = Base64.getEncoder().encodeToString(
                (ADMIN + ":" + PASSWORD).getBytes(StandardCharsets.UTF_8));
        HttpRequest request = HttpRequest.newBuilder(URI.create(consoleUrl + path))
                .header("Authorization", "Basic " + credentials)
                .header("Content-Type", "application/json")
                .method(method, "PUT".equals(method)
                        ? HttpRequest.BodyPublishers.ofString("{}") : HttpRequest.BodyPublishers.noBody())
                .timeout(Duration.ofSeconds(10))
                .build();
        return http.send(request, HttpResponse.BodyHandlers.ofString());
    }

    private static RequestSpecification mq() {
        return given().header("Authorization", AUTH);
    }
}
