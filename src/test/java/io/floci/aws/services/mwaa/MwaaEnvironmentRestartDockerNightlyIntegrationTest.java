package io.floci.aws.services.mwaa;

import com.fasterxml.jackson.core.type.TypeReference;
import com.github.dockerjava.api.DockerClient;
import io.floci.aws.core.storage.StorageFactory;
import io.floci.aws.services.mwaa.model.Environment;
import io.floci.aws.services.mwaa.model.EnvironmentRuntime;
import io.floci.aws.services.mwaa.proxy.MwaaProxyManager;
import io.floci.aws.testing.DockerAvailability;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.QuarkusTestProfile;
import io.quarkus.test.junit.TestProfile;
import io.restassured.path.json.JsonPath;
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
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import static io.restassured.RestAssured.given;
import static org.awaitility.Awaitility.await;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * #5664: an MWAA environment comes back after Floci restarts with what its metadata database holds. Shutdown removes
 * the environment's containers and keeps their volumes; the startup restore starts new ones on them with the secrets
 * the database was set up with, so an Airflow variable set before, which Airflow stores encrypted with the
 * environment's Fernet key, still reads back, through the same webserver URL. Stopping the web proxy and the
 * containers, as shutdown does, stands in for the shutdown, reloading the records from disk for what a restart reads
 * back, and the restore is what startup calls.
 *
 * <p>A Docker end-to-end check: it runs real Postgres and Airflow containers, which the shared test configuration
 * mocks, with the environment records on disk, which it keeps in memory.
 */
@QuarkusTest
@TestProfile(MwaaEnvironmentRestartDockerNightlyIntegrationTest.Profile.class)
@EnabledIfSystemProperty(named = "nightly.checks.enabled", matches = "true")
// Bounded waits: the Airflow image's pull, then the environment starting twice.
@Timeout(value = 10, unit = TimeUnit.MINUTES)
class MwaaEnvironmentRestartDockerNightlyIntegrationTest {

    /** Real Airflow environments, their records on disk. */
    public static final class Profile implements QuarkusTestProfile {
        // Its own directory, so the restore finds no environment records but this test's.
        private static final String DATA = "./target/test-data/mwaa-restart-" + UUID.randomUUID();

        @Override
        public Map<String, String> getConfigOverrides() {
            return Map.of("floci.services.mwaa.mock", "false",
                    "floci.storage.mode", "persistent",
                    "floci.storage.persistent-path", DATA);
        }
    }

    private static final String JSON = "application/json";
    private static final String VARIABLE = "restart_check";
    private static final Duration ENVIRONMENT_TIMEOUT = Duration.ofMinutes(4);

    private final HttpClient http = HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1)
            .connectTimeout(Duration.ofSeconds(5)).build();

    @Inject
    DockerClient dockerClient;

    @Inject
    MwaaService mwaaService;

    @Inject
    MwaaEnvironmentManager environmentManager;

    @Inject
    MwaaProxyManager proxyManager;

    @Inject
    StorageFactory storageFactory;

    @BeforeEach
    void requireDocker() {
        Assumptions.assumeTrue(DockerAvailability.answers(dockerClient),
                "Docker daemon must be available for the MWAA restart check");
    }

    @Test
    void anEnvironmentKeepsItsMetadataAcrossARestart() throws Exception {
        String name = "restart-" + UUID.randomUUID().toString().substring(0, 8);
        given().contentType(JSON)
                .body("""
                        {"ExecutionRoleArn": "arn:aws:iam::000000000000:role/mwaa-execution-role",
                         "SourceBucketArn": "arn:aws:s3:::%s", "DagS3Path": "dags"}
                        """.formatted(name))
                .when().put("/environments/" + name)
                .then().statusCode(200);
        try {
            String webserverUrl = awaitAvailable(name);
            CliResult set = cli(name, "variables set " + VARIABLE + " kept");
            assertEquals(200, set.status(), set.toString());

            Environment running = mwaaService.getEnvironment(name);
            String airflowContainer = running.getAirflowContainerId();
            proxyManager.stopProxy(mwaaService.environmentIdentity(running));
            environmentManager.stopContainers(running);
            // What a restart reads back: the records on disk, not the ones in memory.
            storageFactory.create("mwaa", "mwaa-environments.json",
                    new TypeReference<Map<String, Environment>>() {}).load();
            storageFactory.create("mwaa", "mwaa-environment-runtime.json",
                    new TypeReference<Map<String, EnvironmentRuntime>>() {}).load();
            assertNotSame(running, mwaaService.getEnvironment(name));
            mwaaService.restorePersistedRuntime().join();

            assertEquals(webserverUrl, awaitAvailable(name));
            assertNotEquals(airflowContainer, mwaaService.getEnvironment(name).getAirflowContainerId());
            CliResult get = cli(name, "variables get " + VARIABLE);
            assertEquals(200, get.status(), get.toString());
            assertTrue(get.stdout().contains("kept"), get.toString());
        } finally {
            given().when().delete("/environments/" + name);
        }
    }

    /** Waits for the environment to be AVAILABLE and returns its webserver URL. */
    private static String awaitAvailable(String name) {
        await("environment " + name + " available").atMost(ENVIRONMENT_TIMEOUT).pollInterval(Duration.ofSeconds(3))
                .until(() -> given().when().get("/environments/" + name)
                        .then().statusCode(200).extract().<String>path("Environment.Status"), status -> {
                            if ("CREATE_FAILED".equals(status) || "UNAVAILABLE".equals(status)) {
                                throw new AssertionError("environment " + name + " is " + status);
                            }
                            return "AVAILABLE".equals(status);
                        });
        return given().when().get("/environments/" + name)
                .then().statusCode(200).extract().path("Environment.WebserverUrl");
    }

    /** The HTTP status of a CLI call and the command's output. */
    private record CliResult(int status, String stdout, String stderr) {
    }

    /** Runs an Airflow CLI command through the environment's web server, as an Amazon MWAA CLI token allows. */
    private CliResult cli(String name, String command) throws Exception {
        JsonPath token = given().contentType(JSON).when().post("/clitoken/" + name)
                .then().statusCode(200).extract().jsonPath();
        HttpRequest request = HttpRequest.newBuilder(
                        URI.create("http://" + token.getString("WebServerHostname") + "/aws_mwaa/cli"))
                .header("Authorization", "Bearer " + token.getString("CliToken"))
                .header("Content-Type", "text/plain")
                .POST(HttpRequest.BodyPublishers.ofString(command))
                .timeout(Duration.ofSeconds(60))
                .build();
        HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() != 200) {
            return new CliResult(response.statusCode(), "", response.body());
        }
        JsonPath body = new JsonPath(response.body());
        return new CliResult(response.statusCode(), decode(body.getString("stdout")), decode(body.getString("stderr")));
    }

    private static String decode(String base64) {
        return base64 == null ? "" : new String(Base64.getDecoder().decode(base64), StandardCharsets.UTF_8);
    }
}
