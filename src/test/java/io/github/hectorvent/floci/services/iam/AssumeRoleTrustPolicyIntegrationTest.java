package io.github.hectorvent.floci.services.iam;

import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.QuarkusTestProfile;
import io.quarkus.test.junit.TestProfile;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.UUID;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.startsWith;

/**
 * Verifies that, with {@code iam.enforcement-enabled=true}, STS AssumeRole honors the target role's
 * trust policy: a caller the trust policy permits succeeds; one it does not is denied; and a role
 * Floci does not know about stays permissive (backward-compatible).
 */
@QuarkusTest
@TestProfile(AssumeRoleTrustPolicyIntegrationTest.EnforcementProfile.class)
class AssumeRoleTrustPolicyIntegrationTest {

    public static class EnforcementProfile implements QuarkusTestProfile {
        @Override
        public Map<String, String> getConfigOverrides() {
            return Map.of("floci.services.iam.enforcement-enabled", "true");
        }
    }

    private static final String ACCOUNT_A = "111111111111";
    private static final String ACCOUNT_B = "222222222222";
    private static final String ACCOUNT_C = "333333333333";

    private static final String TRUST_ALLOW_A = "{\"Version\":\"2012-10-17\",\"Statement\":[{"
            + "\"Effect\":\"Allow\",\"Principal\":{\"AWS\":\"arn:aws:iam::" + ACCOUNT_A + ":root\"},"
            + "\"Action\":\"sts:AssumeRole\"}]}";

    private static String auth(String account, String service) {
        return "AWS4-HMAC-SHA256 Credential=" + account + "/20260215/us-east-1/" + service
                + "/aws4_request, SignedHeaders=host, Signature=abc";
    }

    private static void createRoleInB(String roleName) {
        given()
            .contentType("application/x-www-form-urlencoded")
            .formParam("Action", "CreateRole")
            .formParam("RoleName", roleName)
            .formParam("AssumeRolePolicyDocument", TRUST_ALLOW_A)
            .header("Authorization", auth(ACCOUNT_B, "iam"))
        .when().post("/")
        .then().statusCode(200);
    }

    @Test
    void permittedCallerCanAssumeRole() {
        String role = "trust-ok-" + UUID.randomUUID().toString().substring(0, 8);
        createRoleInB(role);

        given()
            .contentType("application/x-www-form-urlencoded")
            .formParam("Action", "AssumeRole")
            .formParam("RoleArn", "arn:aws:iam::" + ACCOUNT_B + ":role/" + role)
            .formParam("RoleSessionName", "s")
            .header("Authorization", auth(ACCOUNT_A, "sts"))
        .when().post("/")
        .then().statusCode(200)
            .body("AssumeRoleResponse.AssumeRoleResult.Credentials.AccessKeyId", startsWith("ASIA"));
    }

    @Test
    void unauthorizedCallerIsDenied() {
        String role = "trust-deny-" + UUID.randomUUID().toString().substring(0, 8);
        createRoleInB(role);

        given()
            .contentType("application/x-www-form-urlencoded")
            .formParam("Action", "AssumeRole")
            .formParam("RoleArn", "arn:aws:iam::" + ACCOUNT_B + ":role/" + role)
            .formParam("RoleSessionName", "s")
            .header("Authorization", auth(ACCOUNT_C, "sts"))
        .when().post("/")
        .then().statusCode(403)
            .body(containsString("AccessDenied"))
            // AWS prefixes the denial with the caller and names the action and resource.
            .body(containsString("User: "))
            .body(containsString("is not authorized to perform: sts:AssumeRole on resource: "
                    + "arn:aws:iam::" + ACCOUNT_B + ":role/" + role));
    }

    @Test
    void unknownRoleStaysPermissive() {
        // No role created — enforcement must not block roles Floci has never seen.
        given()
            .contentType("application/x-www-form-urlencoded")
            .formParam("Action", "AssumeRole")
            .formParam("RoleArn", "arn:aws:iam::" + ACCOUNT_B + ":role/never-created-"
                    + UUID.randomUUID().toString().substring(0, 8))
            .formParam("RoleSessionName", "s")
            .header("Authorization", auth(ACCOUNT_C, "sts"))
        .when().post("/")
        .then().statusCode(200)
            .body("AssumeRoleResponse.AssumeRoleResult.Credentials.AccessKeyId", startsWith("ASIA"));
    }
}
