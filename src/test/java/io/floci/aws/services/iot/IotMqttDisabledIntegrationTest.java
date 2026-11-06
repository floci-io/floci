package io.floci.aws.services.iot;

import io.floci.aws.testing.IndependentServiceSettingsProfile;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.junit.jupiter.api.Test;

import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.WebSocket;
import java.net.http.WebSocketHandshakeException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;

import static io.floci.aws.testing.IndependentServiceSettingsProfile.IOT_MQTT_PORT;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * With the MQTT broker off, nothing listens on its configured port and the MQTT WebSocket upgrade
 * is refused with 503.
 *
 * <p>The broker settings, port {@link IndependentServiceSettingsProfile#IOT_MQTT_PORT} included,
 * come from {@link IndependentServiceSettingsProfile}, which this class shares with other classes
 * that each call a different service.
 */
@QuarkusTest
@TestProfile(IndependentServiceSettingsProfile.class)
class IotMqttDisabledIntegrationTest {

    @ConfigProperty(name = "quarkus.http.test-port", defaultValue = "0")
    int testHttpPort;

    @Test
    void disabledMqttDoesNotOpenConfiguredPort() {
        assertThrows(Exception.class, () -> {
            try (Socket socket = new Socket()) {
                socket.connect(new InetSocketAddress("127.0.0.1", IOT_MQTT_PORT), 250);
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
}
