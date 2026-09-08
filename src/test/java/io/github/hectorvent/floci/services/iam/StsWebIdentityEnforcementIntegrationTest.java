package io.github.hectorvent.floci.services.iam;

import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.QuarkusTestProfile;
import io.quarkus.test.junit.TestProfile;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.containsString;

@QuarkusTest
@TestProfile(StsWebIdentityEnforcementIntegrationTest.EnforcementProfile.class)
class StsWebIdentityEnforcementIntegrationTest {

    @Test
    void rejectsOpaqueTokenWhenIamEnforcementIsEnabled() {
        assumeRole("dummy-token")
        .then()
            .statusCode(400)
            .body(containsString("InvalidIdentityToken"));
    }

    @Test
    void rejectsUnknownIssuerWhenIamEnforcementIsEnabled() {
        String header = java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(
                "{\"alg\":\"none\"}".getBytes(java.nio.charset.StandardCharsets.UTF_8));
        String payload = java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(
                "{\"iss\":\"https://untrusted.example\",\"aud\":\"sts.amazonaws.com\"}"
                        .getBytes(java.nio.charset.StandardCharsets.UTF_8));

        assumeRole(header + "." + payload + ".")
        .then()
            .statusCode(400)
            .body(containsString("InvalidIdentityToken"));
    }

    private static io.restassured.response.Response assumeRole(String token) {
        return given()
            .contentType("application/x-www-form-urlencoded")
            .formParam("Action", "AssumeRoleWithWebIdentity")
            .formParam("Version", "2011-06-15")
            .formParam("RoleArn", "arn:aws:iam::000000000000:role/web-identity-role")
            .formParam("RoleSessionName", "enforcement-test-session")
            .formParam("WebIdentityToken", token)
        .when()
            .post("/");
    }

    public static final class EnforcementProfile implements QuarkusTestProfile {
        @Override
        public Map<String, String> getConfigOverrides() {
            return Map.of("floci.services.iam.enforcement-enabled", "true");
        }
    }
}
