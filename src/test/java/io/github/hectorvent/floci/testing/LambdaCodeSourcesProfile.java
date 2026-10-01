package io.github.hectorvent.floci.testing;

import io.quarkus.test.junit.QuarkusTestProfile;

import java.util.Map;

/**
 * Sets where a Lambda function's code may come from, for the classes that test it: foreign-account
 * layer ARNs are accepted (recorded verbatim, never mounted), and hot-reload mounts are limited to
 * an allow-list under {@code /home/ci/code}. Each setting only changes the requests its own class
 * makes, so the two classes share this profile, and Quarkus builds one application for both
 * rather than one each.
 */
public class LambdaCodeSourcesProfile implements QuarkusTestProfile {

    @Override
    public Map<String, String> getConfigOverrides() {
        return Map.of(
                "floci.services.lambda.accept-external-layer-arns", "true",
                "floci.services.lambda.hot-reload.allowed-paths", "/home/ci/code");
    }
}
