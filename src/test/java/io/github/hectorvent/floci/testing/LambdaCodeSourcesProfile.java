package io.github.hectorvent.floci.testing;

import io.quarkus.test.junit.QuarkusTestProfile;

import java.util.Map;

/**
 * Sets where a Lambda function's code may come from, for the classes that test it: foreign-account
 * layer ARNs are accepted (recorded verbatim, never mounted), hot-reload mounts are limited to an
 * allow-list under {@code /home/ci/code}, and a reactive S3 sync re-deploys a function in the
 * background rather than inside the upload of its code. Each setting only changes the requests its
 * own class makes, so the three classes share this profile, and Quarkus builds one application for
 * all of them rather than one each.
 */
public class LambdaCodeSourcesProfile implements QuarkusTestProfile {

    @Override
    public Map<String, String> getConfigOverrides() {
        return Map.of(
                "floci.services.lambda.accept-external-layer-arns", "true",
                "floci.services.lambda.hot-reload.allowed-paths", "/home/ci/code",
                "floci.services.lambda.reactive-s3-sync.async", "true");
    }
}
