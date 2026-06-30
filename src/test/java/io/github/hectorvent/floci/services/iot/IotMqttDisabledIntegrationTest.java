package io.github.hectorvent.floci.services.iot;

import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.QuarkusTestProfile;
import io.quarkus.test.junit.TestProfile;
import org.junit.jupiter.api.Test;

import java.net.InetSocketAddress;
import java.net.Socket;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertThrows;

@QuarkusTest
@TestProfile(IotMqttDisabledIntegrationTest.DisabledMqttProfile.class)
class IotMqttDisabledIntegrationTest {

    private static final int PORT = 18830;

    @Test
    void disabledMqttDoesNotOpenConfiguredPort() {
        assertThrows(Exception.class, () -> {
            try (Socket socket = new Socket()) {
                socket.connect(new InetSocketAddress("127.0.0.1", PORT), 250);
            }
        });
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
