package io.github.hectorvent.floci.testing;

import io.quarkus.test.junit.QuarkusTestProfile;

import java.util.Map;

/**
 * Sets an explicit {@code floci.hostname}, so a test can assert on the host a service advertises
 * rather than on the default.
 */
public class ConfiguredHostnameProfile implements QuarkusTestProfile {

    @Override
    public Map<String, String> getConfigOverrides() {
        return Map.of("floci.hostname", "floci");
    }
}
