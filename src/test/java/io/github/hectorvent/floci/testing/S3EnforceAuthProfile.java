package io.github.hectorvent.floci.testing;

import io.quarkus.test.junit.QuarkusTestProfile;

import java.util.Map;

/**
 * Turns S3 request-signature enforcement on, and nothing else. Quarkus builds the application once
 * per distinct profile class, so the classes that need only this override share this one rather
 * than each declaring an identical nested profile.
 */
public class S3EnforceAuthProfile implements QuarkusTestProfile {

    @Override
    public Map<String, String> getConfigOverrides() {
        return Map.of("floci.services.s3.enforce-auth", "true");
    }
}
