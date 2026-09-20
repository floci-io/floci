package io.github.hectorvent.floci.services.timestreaminfluxdb;

import com.github.dockerjava.api.DockerClient;
import io.github.hectorvent.floci.testing.RestAssuredJsonUtils;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.QuarkusTestProfile;
import io.quarkus.test.junit.TestProfile;
import io.restassured.response.ValidatableResponse;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Base64;
import java.util.Map;

import static io.restassured.RestAssured.given;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

@Tag("docker")
@QuarkusTest
@TestProfile(TimestreamInfluxDbDockerIntegrationTest.RealContainerProfile.class)
class TimestreamInfluxDbDockerIntegrationTest {

    private static final String CONTENT_TYPE = "application/x-amz-json-1.0";
    private static final String TARGET = "AmazonTimestreamInfluxDB.";
    private static final String USERNAME = "operator";
    private static final String PASSWORD = "password123";

    public static class RealContainerProfile implements QuarkusTestProfile {
        @Override
        public Map<String, String> getConfigOverrides() {
            return Map.of(
                    "floci.services.timestream-influxdb.mock", "false",
                    "floci.services.timestream-influxdb.host-port-base", "18186",
                    "floci.services.timestream-influxdb.host-port-max", "18285");
        }
    }

    @Inject
    DockerClient dockerClient;

    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();

    @BeforeEach
    void requireDocker() {
        RestAssuredJsonUtils.configureAwsContentTypes();
        boolean available;
        try {
            dockerClient.pingCmd().exec();
            available = true;
        } catch (RuntimeException e) {
            available = false;
        }
        Assumptions.assumeTrue(available, "Docker daemon must be available for InfluxDB container tests");
    }

    @Test
    void instanceEndpointServesInfluxDbAndBackupsRestoreItsData() throws Exception {
        String sourceId = call("CreateDbInstance", """
                {"name":"docker-source","username":"%s","password":"%s","organization":"acme","bucket":"metrics",
                 "dbInstanceType":"db.influx.medium","allocatedStorage":20,
                 "vpcSubnetIds":["subnet-abc123"],"vpcSecurityGroupIds":["sg-abc123"]}
                """.formatted(USERNAME, PASSWORD)).statusCode(200).extract().path("id");
        String restoredId = null;
        try {
            Map<String, Object> source = awaitInstance(sourceId, "AVAILABLE");
            String sourceUrl = "http://" + source.get("endpoint") + ":" + source.get("port");

            String session = signIn(sourceUrl);
            HttpResponse<String> write = http.send(HttpRequest.newBuilder(
                            URI.create(sourceUrl + "/api/v2/write?org=acme&bucket=metrics&precision=s"))
                    .header("Cookie", session)
                    .POST(HttpRequest.BodyPublishers.ofString("cpu,host=a value=42 1700000000"))
                    .build(), HttpResponse.BodyHandlers.ofString());
            assertEquals(204, write.statusCode(), write.body());
            assertTrue(query(sourceUrl, session).contains(",42,value,cpu,a"));

            String backupId = call("CreateDbBackup", "{\"name\":\"docker-snap\",\"dbResourceId\":\"" + sourceId + "\"}")
                    .statusCode(200).extract().path("id");
            awaitStatus("GetDbBackup", backupId, "COMPLETED");

            restoredId = call("RestoreFromDbBackup", "{\"name\":\"docker-restored\",\"dbBackupId\":\"" + backupId + "\"}")
                    .statusCode(200).extract().path("restoredDbResourceId");
            Map<String, Object> restored = awaitInstance(restoredId, "AVAILABLE");
            String restoredUrl = "http://" + restored.get("endpoint") + ":" + restored.get("port");

            assertTrue(query(restoredUrl, signIn(restoredUrl)).contains(",42,value,cpu,a"));
        } finally {
            call("DeleteDbInstance", "{\"identifier\":\"" + sourceId + "\"}");
            if (restoredId != null) {
                call("DeleteDbInstance", "{\"identifier\":\"" + restoredId + "\"}");
            }
        }
    }

    private String signIn(String baseUrl) throws Exception {
        String basic = Base64.getEncoder().encodeToString((USERNAME + ":" + PASSWORD).getBytes(StandardCharsets.UTF_8));
        HttpResponse<String> response = http.send(HttpRequest.newBuilder(URI.create(baseUrl + "/api/v2/signin"))
                .header("Authorization", "Basic " + basic)
                .POST(HttpRequest.BodyPublishers.noBody())
                .build(), HttpResponse.BodyHandlers.ofString());
        assertEquals(204, response.statusCode(), response.body());
        return response.headers().firstValue("Set-Cookie").orElseThrow().split(";")[0];
    }

    private String query(String baseUrl, String session) throws Exception {
        HttpResponse<String> response = http.send(HttpRequest.newBuilder(URI.create(baseUrl + "/api/v2/query?org=acme"))
                .header("Cookie", session)
                .header("Content-Type", "application/vnd.flux")
                .POST(HttpRequest.BodyPublishers.ofString(
                        "from(bucket:\"metrics\") |> range(start: 0) |> filter(fn: (r) => r._measurement == \"cpu\")"))
                .build(), HttpResponse.BodyHandlers.ofString());
        assertEquals(200, response.statusCode(), response.body());
        return response.body();
    }

    private Map<String, Object> awaitInstance(String id, String expectedStatus) throws InterruptedException {
        awaitStatus("GetDbInstance", id, expectedStatus);
        return call("GetDbInstance", "{\"identifier\":\"" + id + "\"}").extract().jsonPath().getMap("$");
    }

    private void awaitStatus(String action, String id, String expectedStatus) throws InterruptedException {
        long deadline = System.currentTimeMillis() + 180_000;
        String status = null;
        while (System.currentTimeMillis() < deadline) {
            status = call(action, "{\"identifier\":\"" + id + "\"}").statusCode(200).extract().path("status");
            if (expectedStatus.equals(status)) {
                return;
            }
            if (status.endsWith("FAILED")) {
                break;
            }
            Thread.sleep(1000);
        }
        fail(action + " " + id + " ended in status " + status + " instead of " + expectedStatus);
    }

    private static ValidatableResponse call(String action, String body) {
        return given().contentType(CONTENT_TYPE)
                .header("X-Amz-Target", TARGET + action)
                .body(body)
                .when().post("/")
                .then();
    }
}
