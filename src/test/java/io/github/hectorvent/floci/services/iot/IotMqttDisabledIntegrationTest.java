package io.github.hectorvent.floci.services.iot;

import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.QuarkusTestProfile;
import io.quarkus.test.junit.TestProfile;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.junit.jupiter.api.Test;

import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.WebSocket;
import java.net.http.WebSocketHandshakeException;
import java.util.Map;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;

@QuarkusTest
@TestProfile(IotMqttDisabledIntegrationTest.DisabledMqttProfile.class)
class IotMqttDisabledIntegrationTest {

    private static final int PORT = 18830;

    @ConfigProperty(name = "quarkus.http.test-port", defaultValue = "0")
    int testHttpPort;

    @Test
    void disabledMqttDoesNotOpenConfiguredPort() {
        assertThrows(Exception.class, () -> {
            try (Socket socket = new Socket()) {
                socket.connect(new InetSocketAddress("127.0.0.1", PORT), 250);
            }
        });
    }

    @Test
    void disabledMqttRefusesTheWebSocketUpgrade() {
        ExecutionException failure = assertThrows(ExecutionException.class, () -> HttpClient.newHttpClient()
                .newWebSocketBuilder()
                .subprotocols("mqtt")
                .buildAsync(URI.create("ws://127.0.0.1:" + testHttpPort + "/mqtt"), new WebSocket.Listener() { })
                .get(10, TimeUnit.SECONDS));
        WebSocketHandshakeException handshake = assertInstanceOf(WebSocketHandshakeException.class, failure.getCause());
        assertEquals(503, handshake.getResponse().statusCode());
    }

    public static final class DisabledMqttProfile implements QuarkusTestProfile {
        @Override
        public Map<String, String> getConfigOverrides() {
            return Map.of(
                    "floci.services.iot.mqtt.enabled", "false",
                    "floci.services.iot.mqtt.host", "127.0.0.1",
                    "floci.services.iot.mqtt.port", Integer.toString(PORT)
            );
        }
    }
}
