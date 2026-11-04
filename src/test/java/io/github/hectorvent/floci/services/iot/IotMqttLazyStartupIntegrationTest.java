package io.github.hectorvent.floci.services.iot;

import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;
import jakarta.inject.Inject;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.net.InetSocketAddress;
import java.net.Socket;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@QuarkusTest
@TestProfile(LazyMqttTestProfile.class)
class IotMqttLazyStartupIntegrationTest {

    private static final int PORT = LazyMqttTestProfile.PORT;

    @Inject
    IotMqttBrokerService mqttBrokerService;

    @BeforeEach
    @AfterEach
    void stopBroker() throws InterruptedException {
        mqttBrokerService.stop();
        awaitPortClosed(PORT);
    }

    /**
     * Vert.x's {@code MqttServer.close()} future can resolve a moment before the OS
     * releases the listening socket, so a fresh connect immediately after {@link
     * IotMqttBrokerService#stop()} may still succeed. Poll until the port actually
     * refuses connections before a test asserts the broker is not listening — this
     * removes a startup-order race that only surfaced under the full ordered suite.
     */
    private static void awaitPortClosed(int port) throws InterruptedException {
        long deadline = System.currentTimeMillis() + 5_000;
        while (System.currentTimeMillis() < deadline) {
            try (Socket socket = new Socket()) {
                socket.connect(new InetSocketAddress("127.0.0.1", port), 100);
            } catch (Exception refused) {
                return; // connection refused — the broker port is closed
            }
            Thread.sleep(50); // still accepting connections; back off and retry
        }
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

}
