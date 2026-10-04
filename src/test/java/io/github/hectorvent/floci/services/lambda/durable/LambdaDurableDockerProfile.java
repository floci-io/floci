package io.github.hectorvent.floci.services.lambda.durable;

import io.quarkus.test.junit.QuarkusTestProfile;

import java.util.Map;

/**
 * Pins the HTTP port so a function container can checkpoint back into this Floci instance:
 * containers reach Floci at the {@code floci.base-url} port, which the random test port does not match.
 */
public class LambdaDurableDockerProfile implements QuarkusTestProfile {

    static final String PORT = "4591";

    @Override
    public Map<String, String> getConfigOverrides() {
        return Map.of(
                "quarkus.http.test-port", PORT,
                "floci.port", PORT,
                "floci.base-url", "http://localhost:" + PORT);
    }
}
