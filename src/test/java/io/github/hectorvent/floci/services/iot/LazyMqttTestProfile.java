package io.github.hectorvent.floci.services.iot;

import io.quarkus.test.junit.QuarkusTestProfile;

import java.util.Map;

public final class LazyMqttTestProfile implements QuarkusTestProfile {

    static final int PORT = 18838;

    @Override
    public Map<String, String> getConfigOverrides() {
        return Map.of(
                "floci.services.iot.mqtt.enabled", "true",
                "floci.services.iot.mqtt.auto-start", "false",
                "floci.services.iot.mqtt.host", "127.0.0.1",
                "floci.services.iot.mqtt.port", Integer.toString(PORT));
    }
}
