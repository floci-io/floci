package io.github.hectorvent.floci.core.common;

import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.QuarkusTestProfile;
import io.quarkus.test.junit.TestProfile;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.containsString;

/**
 * The guard is only as good as the catalog's scope list, so it has an off switch: if Floci
 * serves a route whose signing scope is not enumerated, rejecting it is a 404 with no
 * workaround. Disabling restores the pre-#1754 fall-through rather than failing the request.
 */
@QuarkusTest
@TestProfile(UnknownServiceScopeGuardDisabledIntegrationTest.GuardDisabledProfile.class)
class UnknownServiceScopeGuardDisabledIntegrationTest {

    @Test
    void unsupportedScopeFallsThroughWhenRejectionDisabled() {
        given()
            .header("Authorization", "AWS4-HMAC-SHA256 Credential=test/20260707/us-east-1/securityhub"
                    + "/aws4_request, SignedHeaders=host;x-amz-date, Signature=deadbeef")
        .when()
            .get("/accounts")
        .then()
            // Back to the old behaviour: S3's path-style catch-all answers for the bucket
            // named "accounts", instead of the guard's UnknownOperationException.
            .statusCode(404)
            .body(containsString("<Code>NoSuchBucket</Code>"));
    }

    @Test
    void knownScopeS3FallthroughAlsoFallsThroughWhenRejectionDisabled() {
        given()
            .header("Authorization", "AWS4-HMAC-SHA256 Credential=test/20260707/us-east-1/backup"
                    + "/aws4_request, SignedHeaders=host;x-amz-date, Signature=deadbeef")
            .contentType("application/json")
            .body("{}")
        .when()
            .post("/audit/frameworks")
        .then()
            // The switch disables both guards: the S3 wildcard answers again.
            .contentType(containsString("xml"));
    }

    public static final class GuardDisabledProfile implements QuarkusTestProfile {
        @Override
        public Map<String, String> getConfigOverrides() {
            return Map.of("floci.protocols.reject-unknown-service-scope", "false");
        }
    }
}
