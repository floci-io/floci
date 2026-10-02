package io.github.hectorvent.floci.services.iam;

import io.github.hectorvent.floci.testing.IamEnforcementProfile;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.startsWith;

/**
 * Verifies that, with {@code iam.enforcement-enabled=true}, STS AssumeRole honors the target role's
 * trust policy: a caller the trust policy permits succeeds; one it does not is denied; and a role
 * that does not exist is denied.
 */
@QuarkusTest
@TestProfile(IamEnforcementProfile.class)
class AssumeRoleTrustPolicyIntegrationTest {

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
        createRole(ACCOUNT_B, roleName, TRUST_ALLOW_A);
    }

    private static void createRole(String accountId, String roleName, String trustPolicyDocument) {
        given()
            .contentType("application/x-www-form-urlencoded")
            .formParam("Action", "CreateRole")
            .formParam("RoleName", roleName)
            .formParam("AssumeRolePolicyDocument", trustPolicyDocument)
            .header("Authorization", auth(accountId, "iam"))
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
    void alternatePartitionArnKeepsTheStoredRolePartitionInSessionIdentity() {
        String role = "trust-partition-" + UUID.randomUUID().toString().substring(0, 8);
        createRoleInB(role);

        String accessKeyId = given()
            .contentType("application/x-www-form-urlencoded")
            .formParam("Action", "AssumeRole")
            .formParam("RoleArn", "arn:aws-cn:iam::" + ACCOUNT_B + ":role/" + role)
            .formParam("RoleSessionName", "s")
            .header("Authorization", auth(ACCOUNT_A, "sts"))
        .when().post("/")
        .then().statusCode(200)
            .body("AssumeRoleResponse.AssumeRoleResult.Credentials.AccessKeyId", startsWith("ASIA"))
            .body("AssumeRoleResponse.AssumeRoleResult.AssumedRoleUser.Arn",
                    containsString("arn:aws:sts::" + ACCOUNT_B + ":assumed-role/" + role + "/s"))
            .extract().path("AssumeRoleResponse.AssumeRoleResult.Credentials.AccessKeyId");

        given()
            .contentType("application/x-www-form-urlencoded")
            .formParam("Action", "GetCallerIdentity")
            .header("Authorization", auth(accessKeyId, "sts"))
        .when().post("/")
        .then().statusCode(200)
            .body("GetCallerIdentityResponse.GetCallerIdentityResult.Arn",
                    containsString("arn:aws:sts::" + ACCOUNT_B + ":assumed-role/" + role + "/s"));
    }

    @Test
    void requestedPartitionCannotChangeCallerPrincipalForLaterTrustChecks() {
        String firstRole = "trust-source-" + UUID.randomUUID().toString().substring(0, 8);
        String protectedRole = "trust-target-" + UUID.randomUUID().toString().substring(0, 8);
        String sessionName = "partition-spoof";
        createRoleInB(firstRole);

        String forgedPrincipal = "arn:aws-cn:sts::" + ACCOUNT_B + ":assumed-role/" + firstRole + "/" + sessionName;
        String targetTrust = "{\"Version\":\"2012-10-17\",\"Statement\":[{"
                + "\"Effect\":\"Allow\",\"Principal\":{\"AWS\":\"" + forgedPrincipal + "\"},"
                + "\"Action\":\"sts:AssumeRole\"}]}";
        createRole(ACCOUNT_C, protectedRole, targetTrust);

        String accessKeyId = given()
            .contentType("application/x-www-form-urlencoded")
            .formParam("Action", "AssumeRole")
            .formParam("RoleArn", "arn:aws-cn:iam::" + ACCOUNT_B + ":role/" + firstRole)
            .formParam("RoleSessionName", sessionName)
            .header("Authorization", auth(ACCOUNT_A, "sts"))
        .when().post("/")
        .then().statusCode(200)
            .body("AssumeRoleResponse.AssumeRoleResult.AssumedRoleUser.Arn",
                    containsString("arn:aws:sts::" + ACCOUNT_B + ":assumed-role/" + firstRole + "/" + sessionName))
            .extract().path("AssumeRoleResponse.AssumeRoleResult.Credentials.AccessKeyId");

        given()
            .contentType("application/x-www-form-urlencoded")
            .formParam("Action", "AssumeRole")
            .formParam("RoleArn", "arn:aws:iam::" + ACCOUNT_C + ":role/" + protectedRole)
            .formParam("RoleSessionName", "second-session")
            .header("Authorization", auth(accessKeyId, "sts"))
        .when().post("/")
        .then().statusCode(403)
            .body(containsString("AccessDenied"));
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
    void unknownRoleIsDenied() {
        String roleArn = "arn:aws:iam::" + ACCOUNT_B + ":role/never-created-"
                + UUID.randomUUID().toString().substring(0, 8);
        given()
            .contentType("application/x-www-form-urlencoded")
            .formParam("Action", "AssumeRole")
            .formParam("RoleArn", roleArn)
            .formParam("RoleSessionName", "s")
            .header("Authorization", auth(ACCOUNT_C, "sts"))
        .when().post("/")
        .then().statusCode(403)
            .body(containsString("<Code>AccessDenied</Code>"))
            .body(containsString("is not authorized to perform: sts:AssumeRole on resource: " + roleArn));
    }
}
