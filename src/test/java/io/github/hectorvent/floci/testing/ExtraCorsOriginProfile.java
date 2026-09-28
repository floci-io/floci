package io.github.hectorvent.floci.testing;

import io.quarkus.test.junit.QuarkusTestProfile;

import java.util.Map;

/**
 * Allows one extra CORS origin, {@link #ORIGIN}, and leaves every other CORS setting at its
 * default, including {@code cors-allow-private-network}. Quarkus restarts and re-augments the
 * application once per distinct profile class, so the classes that need only this override share
 * this one rather than each declaring an identical nested profile.
 */
public class ExtraCorsOriginProfile implements QuarkusTestProfile {

    public static final String ORIGIN = "http://localhost:3000";

    @Override
    public Map<String, String> getConfigOverrides() {
        return Map.of("floci.security.extra-cors-allowed-origins", ORIGIN);
    }
}
