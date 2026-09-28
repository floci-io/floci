package io.github.hectorvent.floci.testing;

import io.quarkus.test.junit.QuarkusTestProfile;

import java.util.Map;

/**
 * Runs the real ELBv2 data plane instead of the mock, and nothing else. Quarkus restarts and
 * re-augments the application once per distinct profile class, so the classes that need only this
 * override share this one rather than each declaring an identical nested profile.
 */
public class RealElbV2DataPlaneProfile implements QuarkusTestProfile {

    @Override
    public Map<String, String> getConfigOverrides() {
        return Map.of("floci.services.elbv2.mock", "false");
    }
}
