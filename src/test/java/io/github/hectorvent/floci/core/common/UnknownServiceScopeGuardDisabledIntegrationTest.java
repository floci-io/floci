package io.github.hectorvent.floci.core.common;

import io.github.hectorvent.floci.testing.RestAssuredJsonUtils;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.QuarkusTestProfile;
import io.quarkus.test.junit.TestProfile;
import org.junit.jupiter.api.BeforeAll;
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

    @BeforeAll
    static void configureRestAssured() {
        RestAssuredJsonUtils.configureAwsContentTypes();
    }

    @Test
    void unsupportedScopeFallsThroughWhenRejectionDisabled() {
        given()
            .header("Authorization", "AWS4-HMAC-SHA256 Credential=test/20260707/us-east-1/account"
                    + "/aws4_request, SignedHeaders=host;x-amz-date, Signature=deadbeef")
        .when()
            .get("/guard-disabled-no-such-bucket")
        .then()
            // Back to the old behaviour: S3's path-style catch-all answers for the bucket
            // named "accounts", instead of the guard's UnknownOperationException.
            .statusCode(404)
            .body(containsString("<Code>NoSuchBucket</Code>"));
    }

    @Test
    void knownRestJsonScopeFallsThroughWhenRejectionDisabled() {
        given()
            .header("Authorization", "AWS4-HMAC-SHA256 Credential=test/20260707/us-east-1/bedrock"
                    + "/aws4_request, SignedHeaders=host;x-amz-date, Signature=deadbeef")
            .contentType("application/x-amz-json-1.1")
            .body("{\"name\":\"probe\"}")
        .when()
            .post("/prompts")
        .then()
            .statusCode(400)
            .body(containsString("<Code>InvalidArgument</Code>"));
    }

    public static final class GuardDisabledProfile implements QuarkusTestProfile {
        @Override
        public Map<String, String> getConfigOverrides() {
            return Map.of("floci.protocols.reject-unknown-service-scope", "false");
        }
    }
}
