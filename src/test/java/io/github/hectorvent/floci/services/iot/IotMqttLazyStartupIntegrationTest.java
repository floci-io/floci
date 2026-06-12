package io.github.hectorvent.floci.services.iot;

import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.QuarkusTestProfile;
import io.quarkus.test.junit.TestProfile;
import jakarta.inject.Inject;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.net.InetSocketAddress;
import java.net.Socket;
import java.util.Map;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@QuarkusTest
@TestProfile(IotMqttLazyStartupIntegrationTest.LazyMqttProfile.class)
class IotMqttLazyStartupIntegrationTest {

    private static final int PORT = 18832;

    @Inject
    IotMqttBrokerService mqttBrokerService;

    @BeforeEach
    void stopBroker() {
        mqttBrokerService.stop();
    }

    @Test
    void enabledMqttDoesNotListenUntilEndpointDiscovery() throws Exception {
        assertThrows(Exception.class, () -> {
            try (Socket socket = new Socket()) {
                socket.connect(new InetSocketAddress("127.0.0.1", PORT), 250);
            }
        });

        given()
        .when()
            .get("/endpoint")
        .then()
            .statusCode(200);

        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress("127.0.0.1", PORT), 2_000);
            assertTrue(socket.isConnected());
        }
    }

    @Test
    void createThingStartsMqttWhenEnabled() throws Exception {
        assertThrows(Exception.class, () -> {
            try (Socket socket = new Socket()) {
                socket.connect(new InetSocketAddress("127.0.0.1", PORT), 250);
            }
        });

        given()
            .contentType("application/json")
            .body("{}")
        .when()
            .post("/things/phase-six-lazy-create")
        .then()
            .statusCode(200)
            .body("thingName", equalTo("phase-six-lazy-create"));

        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress("127.0.0.1", PORT), 2_000);
            assertTrue(socket.isConnected());
        }
    }

    public static final class LazyMqttProfile implements QuarkusTestProfile {
        @Override
        public Map<String, String> getConfigOverrides() {
            return Map.of(
                    "floci.services.iot.mqtt.enabled", "true",
                    "floci.services.iot.mqtt.auto-start", "false",
                    "floci.services.iot.mqtt.host", "127.0.0.1",
                    "floci.services.iot.mqtt.port", Integer.toString(PORT)
            );
        }
    }
}
