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

@QuarkusTest
@TestProfile(StsSessionPolicyS3EnforcementIntegrationTest.IamEnforcementProfile.class)
class StsSessionPolicyS3EnforcementIntegrationTest {

    private static final String CALLER_ACCOUNT_ID = "111122223333";
    private static final String ROLE_ACCOUNT_ID = "222233334444";
    private static final String REGION = "us-east-1";

    @Test
    void assumeRoleSessionPolicyDenyRestrictsS3PutObject() {
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        String bucket = "session-policy-" + suffix;
        String roleName = "SessionPolicyRole" + suffix;

        createBucket(bucket);
        createRole(roleName);
        putBroadS3RolePolicy(roleName, bucket);

        String accessKeyId = assumeRoleWithS3SessionPolicy(roleName, bucket);

        given()
                .header("Authorization", auth(accessKeyId, "s3"))
                .contentType("text/plain")
                .body("allowed")
        .when()
                .put("/" + bucket + "/allowed/file.txt")
        .then()
                .statusCode(200);

        given()
                .header("Authorization", auth(accessKeyId, "s3"))
                .contentType("text/plain")
                .body("denied")
        .when()
                .put("/" + bucket + "/blocked/file.txt")
        .then()
                .statusCode(403)
                .body(containsString("<Code>AccessDenied</Code>"));
    }

    private static void createBucket(String bucket) {
        given()
                .header("Authorization", auth(ROLE_ACCOUNT_ID, "s3"))
        .when()
                .put("/" + bucket)
        .then()
                .statusCode(200);
    }

    private static void createRole(String roleName) {
        given()
                .formParam("Action", "CreateRole")
                .formParam("RoleName", roleName)
                .formParam("Path", "/")
                .formParam("AssumeRolePolicyDocument", """
                    {
                      "Version": "2012-10-17",
                      "Statement": [
                        {
                          "Effect": "Allow",
                          "Principal": { "AWS": "*" },
                          "Action": "sts:AssumeRole"
                        }
                      ]
                    }
                    """)
                .header("Authorization", auth(ROLE_ACCOUNT_ID, "iam"))
        .when()
                .post("/")
        .then()
                .statusCode(200);
    }

    private static void putBroadS3RolePolicy(String roleName, String bucket) {
        given()
                .formParam("Action", "PutRolePolicy")
                .formParam("RoleName", roleName)
                .formParam("PolicyName", "AllowS3")
                .formParam("PolicyDocument", """
                    {
                      "Version": "2012-10-17",
                      "Statement": [
                        {
                          "Effect": "Allow",
                          "Action": "s3:*",
                          "Resource": [
                            "arn:aws:s3:::%1$s",
                            "arn:aws:s3:::%1$s/*"
                          ]
                        }
                      ]
                    }
                    """.formatted(bucket))
                .header("Authorization", auth(ROLE_ACCOUNT_ID, "iam"))
        .when()
                .post("/")
        .then()
                .statusCode(200);
    }

    private static String assumeRoleWithS3SessionPolicy(String roleName, String bucket) {
        return given()
                .formParam("Action", "AssumeRole")
                .formParam("RoleArn", "arn:aws:iam::" + ROLE_ACCOUNT_ID + ":role/" + roleName)
                .formParam("RoleSessionName", "session-policy-test")
                .formParam("Policy", """
                    {
                      "Version": "2012-10-17",
                      "Statement": [
                        {
                          "Effect": "Allow",
                          "Action": "s3:*",
                          "Resource": [
                            "arn:aws:s3:::%1$s",
                            "arn:aws:s3:::%1$s/*"
                          ]
                        },
                        {
                          "Effect": "Deny",
                          "Action": "s3:*",
                          "Resource": "arn:aws:s3:::%1$s/blocked/*"
                        }
                      ]
                    }
                    """.formatted(bucket))
                .header("Authorization", auth(CALLER_ACCOUNT_ID, "sts"))
        .when()
                .post("/")
        .then()
                .statusCode(200)
                .body("AssumeRoleResponse.AssumeRoleResult.Credentials.AccessKeyId", startsWith("ASIA"))
                .extract()
                .path("AssumeRoleResponse.AssumeRoleResult.Credentials.AccessKeyId");
    }

    private static String auth(String accessKeyId, String service) {
        return "AWS4-HMAC-SHA256 Credential=" + accessKeyId + "/20260629/" + REGION + "/" + service
                + "/aws4_request, SignedHeaders=host, Signature=abc";
    }

    public static final class IamEnforcementProfile implements QuarkusTestProfile {
        @Override
        public Map<String, String> getConfigOverrides() {
            return Map.of("floci.services.iam.enforcement-enabled", "true");
        }
    }
}
