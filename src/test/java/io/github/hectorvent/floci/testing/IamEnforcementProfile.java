package io.github.hectorvent.floci.testing;

import io.quarkus.test.junit.QuarkusTestProfile;

import java.util.Map;

/**
 * Turns IAM enforcement on, and nothing else. Quarkus restarts and re-augments the application
 * once per distinct profile class, so the classes that need only this override share this one
 * rather than each declaring an identical nested profile.
 */
public class IamEnforcementProfile implements QuarkusTestProfile {

    @Override
    public Map<String, String> getConfigOverrides() {
        return Map.of("floci.services.iam.enforcement-enabled", "true");
    }
}
