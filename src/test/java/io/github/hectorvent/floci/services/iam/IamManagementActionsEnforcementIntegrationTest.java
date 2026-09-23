package io.github.hectorvent.floci.services.iam;

import io.github.hectorvent.floci.testing.IamEnforcementProfile;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;
import io.restassured.response.ValidatableResponse;
import io.restassured.specification.RequestSpecification;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.UUID;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;

/**
 * Regression guard for issue #3931: an IAM user whose only policy grants S3 permissions must
 * receive AccessDenied for IAM management actions (CreateUser, CreateGroup,
 * AttachUserPolicy, DeleteUser) when enforcement is enabled.
 */
@QuarkusTest
@TestProfile(IamEnforcementProfile.class)
class IamManagementActionsEnforcementIntegrationTest {

    private static final String ACCOUNT_ID = "000000000000";
    private static final String REGION = "us-east-1";

    private static final String S3_ONLY_POLICY = """
        {"Version":"2012-10-17","Statement":[
          {"Effect":"Allow","Action":["s3:ListAllMyBuckets","s3:CreateBucket"],"Resource":"*"},
          {"Effect":"Allow","Action":["s3:ListBucket","s3:GetObject","s3:PutObject"],"Resource":"*"}
        ]}""";

    @Test
    void s3OnlyUserIsDeniedIamManagementActions() {
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        String developer = "test-developer-" + suffix;
        String group = "Developers-" + suffix;

        adminIam("CreateUser", Map.of("UserName", developer)).statusCode(200);
        adminIam("CreateGroup", Map.of("GroupName", group)).statusCode(200);
        adminIam("PutGroupPolicy", Map.of("GroupName", group, "PolicyName", "DeveloperS3Policy",
                "PolicyDocument", S3_ONLY_POLICY)).statusCode(200);
        adminIam("AddUserToGroup", Map.of("GroupName", group, "UserName", developer)).statusCode(200);
        String akid = adminIam("CreateAccessKey", Map.of("UserName", developer))
                .statusCode(200)
                .extract().path("CreateAccessKeyResponse.CreateAccessKeyResult.AccessKey.AccessKeyId");

        // Granted S3 action still works.
        given().header("Authorization", auth(akid, "s3"))
        .when().get("/")
        .then().statusCode(200);

        userIam(akid, "CreateUser", Map.of("UserName", "should-fail-" + suffix))
                .statusCode(403)
                .body(containsString("AccessDenied"))
                .body(containsString("iam:CreateUser"));
        userIam(akid, "CreateGroup", Map.of("GroupName", "should-fail-" + suffix))
                .statusCode(403)
                .body(containsString("AccessDenied"));
        userIam(akid, "AttachUserPolicy", Map.of("UserName", developer,
                "PolicyArn", "arn:aws:iam::aws:policy/AdministratorAccess"))
                .statusCode(403)
                .body(containsString("AccessDenied"));
        userIam(akid, "DeleteUser", Map.of("UserName", developer))
                .statusCode(403)
                .body(containsString("AccessDenied"));

        // The user was not created and the policy was not attached.
        adminIam("GetUser", Map.of("UserName", "should-fail-" + suffix)).statusCode(404);
        adminIam("ListAttachedUserPolicies", Map.of("UserName", developer))
                .statusCode(200)
                .body(not(containsString("AdministratorAccess")));
    }

    @Test
    void explicitDenyOnIamActionsIsHonoured() {
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        String user = "deny-user-" + suffix;
        adminIam("CreateUser", Map.of("UserName", user)).statusCode(200);
        adminIam("PutUserPolicy", Map.of("UserName", user, "PolicyName", "S3AllowIamDeny",
                "PolicyDocument", """
                    {"Version":"2012-10-17","Statement":[
                      {"Effect":"Allow","Action":["s3:*"],"Resource":"*"},
                      {"Effect":"Deny","Action":["iam:*"],"Resource":"*"}
                    ]}""")).statusCode(200);
        String akid = adminIam("CreateAccessKey", Map.of("UserName", user))
                .statusCode(200)
                .extract().path("CreateAccessKeyResponse.CreateAccessKeyResult.AccessKey.AccessKeyId");

        userIam(akid, "CreateUser", Map.of("UserName", "deny-direct-" + suffix))
                .statusCode(403)
                .body(containsString("AccessDenied"));
    }

    private static ValidatableResponse adminIam(String action, Map<String, String> params) {
        return iamCall(ACCOUNT_ID, action, params);
    }

    private static ValidatableResponse userIam(String akid, String action, Map<String, String> params) {
        return iamCall(akid, action, params);
    }

    private static ValidatableResponse iamCall(String akid, String action, Map<String, String> params) {
        RequestSpecification spec = given()
                .header("Authorization", auth(akid, "iam"))
                .contentType("application/x-www-form-urlencoded")
                .formParam("Action", action)
                .formParam("Version", "2010-05-08");
        params.forEach(spec::formParam);
        return spec.when().post("/").then();
    }

    private static String auth(String accessKeyId, String service) {
        return "AWS4-HMAC-SHA256 Credential=" + accessKeyId + "/20260919/" + REGION + "/" + service
                + "/aws4_request, SignedHeaders=host, Signature=abc";
    }
}
