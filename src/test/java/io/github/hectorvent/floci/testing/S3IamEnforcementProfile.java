package io.github.hectorvent.floci.testing;

import io.quarkus.test.junit.QuarkusTestProfile;

import java.util.Map;

/**
 * S3 auth enforcement, IAM enforcement and the global bucket namespace together, for the classes
 * that need all three. {@link S3EnforceAuthProfile} covers the narrower case.
 */
public class S3IamEnforcementProfile implements QuarkusTestProfile {

    @Override
    public Map<String, String> getConfigOverrides() {
        return Map.of(
                "floci.services.s3.enforce-auth", "true",
                "floci.services.iam.enforcement-enabled", "true",
                "floci.services.s3.global-bucket-namespace", "true");
    }
}
