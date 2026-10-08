package io.github.hectorvent.floci.services.iot;

import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.QuarkusTestProfile;
import io.quarkus.test.junit.TestProfile;
import org.eclipse.paho.client.mqttv3.MqttClient;
import org.eclipse.paho.client.mqttv3.MqttConnectOptions;
import org.eclipse.paho.client.mqttv3.persist.MemoryPersistence;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.contains;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * SearchIndex connectivity against a real MQTT client on the plaintext broker: a client id that
 * names a thing makes that thing connected, and a DISCONNECT packet and a dropped socket end it
 * with AWS's two disconnect reasons. Unsigned requests run in the default account and region,
 * which is where a plaintext session counts.
 */
@QuarkusTest
@TestProfile(IotFleetIndexingConnectivityIntegrationTest.MqttProfile.class)
class IotFleetIndexingConnectivityIntegrationTest {

    private static final int PORT = 18840;
    private static final String BROKER_URI = "tcp://127.0.0.1:" + PORT;
    private static final String INDEXING = """
        {"thingIndexingConfiguration": {"thingIndexingMode": "REGISTRY", "thingConnectivityIndexingMode": "STATUS"}}
        """;

    @Test
    void searchReportsTheMqttSessionOfTheThingsClientId() throws Exception {
        String thingName = "fleet-connectivity-" + System.nanoTime();
        given().contentType("application/json").body(INDEXING).when().post("/indexing/config").then().statusCode(200);
        given().contentType("application/json").body("{}").when().post("/things/" + thingName).then().statusCode(200);
        assertEquals(Map.of("clientId", thingName, "connected", false, "timestamp", 0), connectivity(null, thingName));

        MqttClient client = new MqttClient(BROKER_URI, thingName, new MemoryPersistence());
        client.setTimeToWait(10_000);
        long beforeConnect = System.currentTimeMillis();
        client.connect(options(30, true));
        long afterConnect = System.currentTimeMillis();
        Map<String, Object> connected = connectivity(null, thingName);
        long connectedAt = ((Number) connected.get("timestamp")).longValue();
        assertTrue(connectedAt >= beforeConnect && connectedAt <= afterConnect, "connect timestamp " + connectedAt);
        assertEquals(Map.of("connected", true, "timestamp", connected.get("timestamp"), "keepAliveDuration", 30,
                "cleanSession", true, "clientId", thingName), connected);
        given()
            .contentType("application/json")
            .body("{\"queryString\": \"connectivity.connected:true AND thingName:" + thingName + "\"}")
        .when()
            .post("/indices/search")
        .then()
            .statusCode(200)
            .body("things.thingName", contains(thingName));

        String otherAccount = "AWS4-HMAC-SHA256 Credential=111100000012/20260215/us-east-1/iot/aws4_request, "
                + "SignedHeaders=host, Signature=abc";
        given().header("Authorization", otherAccount).contentType("application/json").body(INDEXING)
                .when().post("/indexing/config").then().statusCode(200);
        given().header("Authorization", otherAccount).contentType("application/json").body("{}")
                .when().post("/things/" + thingName).then().statusCode(200);
        assertEquals(Map.of("clientId", thingName, "connected", false, "timestamp", 0),
                connectivity(otherAccount, thingName));

        client.disconnect();
        Map<String, Object> disconnected = awaitDisconnected(thingName);
        assertTrue(((Number) disconnected.get("timestamp")).longValue() >= connectedAt);
        assertEquals(Map.of("connected", false, "timestamp", disconnected.get("timestamp"),
                "disconnectReason", "CLIENT_INITIATED_DISCONNECT", "keepAliveDuration", 30, "cleanSession", true,
                "clientId", thingName), disconnected);

        client.connect(options(45, false));
        Map<String, Object> reconnected = connectivity(null, thingName);
        assertEquals(true, reconnected.get("connected"));
        assertEquals(45, reconnected.get("keepAliveDuration"));
        assertEquals(false, reconnected.get("cleanSession"));
        client.disconnectForcibly(0, 0, false);
        client.close();
        Map<String, Object> lost = awaitDisconnected(thingName);
        assertEquals(Map.of("connected", false, "timestamp", lost.get("timestamp"), "disconnectReason",
                "CONNECTION_LOST", "keepAliveDuration", 45, "cleanSession", false, "clientId", thingName), lost);
    }

    private static Map<String, Object> connectivity(String authorization, String thingName) {
        return given()
            .headers(authorization == null ? Map.of() : Map.of("Authorization", authorization))
            .contentType("application/json")
            .body("{\"queryString\": \"thingName:" + thingName + "\"}")
        .when()
            .post("/indices/search")
        .then()
            .statusCode(200)
            .extract().path("things[0].connectivity");
    }

    /** The broker learns of a disconnect on its event loop, a moment after the client returns. */
    private static Map<String, Object> awaitDisconnected(String thingName) throws InterruptedException {
        Instant deadline = Instant.now().plus(Duration.ofSeconds(5));
        Map<String, Object> state = connectivity(null, thingName);
        while (Boolean.TRUE.equals(state.get("connected")) && Instant.now().isBefore(deadline)) {
            Thread.sleep(25);
            state = connectivity(null, thingName);
        }
        assertEquals(false, state.get("connected"));
        return state;
    }

    private static MqttConnectOptions options(int keepAliveSeconds, boolean cleanSession) {
        MqttConnectOptions options = new MqttConnectOptions();
        options.setCleanSession(cleanSession);
        options.setConnectionTimeout(2);
        options.setKeepAliveInterval(keepAliveSeconds);
        options.setAutomaticReconnect(false);
        return options;
    }

    public static final class MqttProfile implements QuarkusTestProfile {
        @Override
        public Map<String, String> getConfigOverrides() {
            return Map.of(
                    "floci.services.iot.mqtt.enabled", "true",
                    "floci.services.iot.mqtt.auto-start", "true",
                    "floci.services.iot.mqtt.host", "127.0.0.1",
                    "floci.services.iot.mqtt.port", Integer.toString(PORT));
        }
    }
}
