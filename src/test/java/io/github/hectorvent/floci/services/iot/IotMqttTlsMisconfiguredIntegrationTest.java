package io.github.hectorvent.floci.services.iot;

import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.QuarkusTestProfile;
import io.quarkus.test.junit.TestProfile;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@QuarkusTest
@TestProfile(IotMqttTlsMisconfiguredIntegrationTest.MisconfiguredTlsProfile.class)
class IotMqttTlsMisconfiguredIntegrationTest {

    @Inject
    IotMqttBrokerService mqttBrokerService;

    @Test
    void startFailsFastWhenTlsEnabledWithoutCertificatePaths() {
        IllegalStateException failure = assertThrows(IllegalStateException.class,
                () -> mqttBrokerService.startIfEnabled());
        assertTrue(failure.getMessage().contains("floci.services.iot.mqtt.tls.cert-path"),
                "message should name the missing config key, was: " + failure.getMessage());
        assertFalse(mqttBrokerService.isRunning(), "a misconfigured broker must not stay half-started");
    }

    public static final class MisconfiguredTlsProfile implements QuarkusTestProfile {
        @Override
        public Map<String, String> getConfigOverrides() {
            return Map.of(
                    "floci.services.iot.mqtt.enabled", "true",
                    "floci.services.iot.mqtt.auto-start", "false",
                    "floci.services.iot.mqtt.host", "127.0.0.1",
                    "floci.services.iot.mqtt.port", "18835",
                    "floci.services.iot.mqtt.tls.enabled", "true",
                    "floci.services.iot.mqtt.tls.port", "18836"
            );
        }
    }
}
