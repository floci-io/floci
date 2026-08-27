package io.github.hectorvent.floci.services.iot;

import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.QuarkusTestProfile;
import io.quarkus.test.junit.TestProfile;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;

import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

@QuarkusTest
@TestProfile(IotMqttTlsStartupRollbackIntegrationTest.BrokenTlsProfile.class)
class IotMqttTlsStartupRollbackIntegrationTest {

    private static final int PLAINTEXT_PORT = 18837;

    @Inject
    IotMqttBrokerService mqttBrokerService;

    @Test
    void failedTlsStartupReleasesThePlaintextPortBeforeReporting() throws Exception {
        // The rollback must complete before startIfEnabled reports failure: the
        // plaintext port has to be released again by the time the caller sees the
        // exception, or an immediate lazy-start retry fails with
        // address-already-in-use on the plaintext port instead of the real cause.
        for (int attempt = 0; attempt < 20; attempt++) {
            IllegalStateException failure = assertThrows(IllegalStateException.class,
                    () -> mqttBrokerService.startIfEnabled());
            Throwable root = failure;
            while (root.getCause() != null) {
                root = root.getCause();
            }
            assertFalse(root instanceof java.net.BindException,
                    "attempt " + attempt + " failed on the plaintext port instead of the TLS cause: " + root);
        }
        assertFalse(mqttBrokerService.isRunning());

        try (ServerSocket socket = new ServerSocket()) {
            socket.bind(new InetSocketAddress("127.0.0.1", PLAINTEXT_PORT));
        }
    }

    public static final class BrokenTlsProfile implements QuarkusTestProfile {
        @Override
        public Map<String, String> getConfigOverrides() {
            return Map.of(
                    "floci.services.iot.mqtt.enabled", "true",
                    "floci.services.iot.mqtt.auto-start", "false",
                    "floci.services.iot.mqtt.host", "127.0.0.1",
                    "floci.services.iot.mqtt.port", Integer.toString(PLAINTEXT_PORT),
                    "floci.services.iot.mqtt.tls.enabled", "true",
                    "floci.services.iot.mqtt.tls.port", "18838",
                    "floci.services.iot.mqtt.tls.cert-path", "/nonexistent/server.pem",
                    "floci.services.iot.mqtt.tls.key-path", "/nonexistent/server.key",
                    "floci.services.iot.mqtt.tls.ca-path", "/nonexistent/ca.pem"
            );
        }
    }
}
