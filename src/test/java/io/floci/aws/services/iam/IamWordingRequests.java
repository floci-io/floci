package io.floci.aws.services.iam;

import io.floci.aws.testing.PartitionCleanup;
import io.restassured.specification.RequestSpecification;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;

/**
 * IAM Query requests for the tests that pin IAM's error wording. RestAssured is used deliberately so the
 * raw wire messages are asserted, not SDK-rewritten ones. Each create registers its delete with the test's
 * {@link PartitionCleanup}, so nothing a test makes outlives it in the shared emulator.
 */
final class IamWordingRequests {

    private static final String IAM_AUTH =
            "AWS4-HMAC-SHA256 Credential=test/20261118/us-east-1/iam/aws4_request";

    private static final String CODE = "ErrorResponse.Error.Code";
    private static final String MESSAGE = "ErrorResponse.Error.Message";

    static final String DOCUMENT = "{\"Version\":\"2012-10-17\",\"Statement\":"
            + "[{\"Effect\":\"Allow\",\"Action\":\"s3:GetObject\",\"Resource\":\"*\"}]}";

    private static final String TRUST = "{\"Version\":\"2012-10-17\",\"Statement\":"
            + "[{\"Effect\":\"Allow\",\"Principal\":{\"Service\":\"ec2.amazonaws.com\"},"
            + "\"Action\":\"sts:AssumeRole\"}]}";

    private final PartitionCleanup cleanup;

    IamWordingRequests(PartitionCleanup cleanup) {
        this.cleanup = cleanup;
    }

    static RequestSpecification iam(String action) {
        return given().header("Authorization", IAM_AUTH).formParam("Action", action);
    }

    static String unique(String prefix) {
        return prefix + "-" + System.nanoTime();
    }

    static void expectError(RequestSpecification request, int status, String code, String message) {
        request.when().post("/").then().statusCode(status)
                .body(CODE, equalTo(code))
                .body(MESSAGE, equalTo(message));
    }

    static void expectNotFound(RequestSpecification request, String message) {
        expectError(request, 404, "NoSuchEntity", message);
    }

    String createUser() {
        String userName = unique("wording-user");
        iam("CreateUser").formParam("UserName", userName).when().post("/").then().statusCode(200);
        cleanup.register(() -> iam("DeleteUser").formParam("UserName", userName)
                .when().post("/").then().statusCode(200));
        return userName;
    }

    String createGroup() {
        String groupName = unique("wording-group");
        iam("CreateGroup").formParam("GroupName", groupName).when().post("/").then().statusCode(200);
        cleanup.register(() -> iam("DeleteGroup").formParam("GroupName", groupName)
                .when().post("/").then().statusCode(200));
        return groupName;
    }

    String createRole() {
        String roleName = unique("wording-role");
        iam("CreateRole").formParam("RoleName", roleName).formParam("AssumeRolePolicyDocument", TRUST)
                .when().post("/").then().statusCode(200);
        cleanup.register(() -> iam("DeleteRole").formParam("RoleName", roleName)
                .when().post("/").then().statusCode(200));
        return roleName;
    }

    String createPolicy() {
        return createPolicy(unique("wording-policy"));
    }

    String createPolicy(String policyName) {
        String arn = iam("CreatePolicy").formParam("PolicyName", policyName)
                .formParam("PolicyDocument", DOCUMENT)
                .when().post("/").then().statusCode(200)
                .extract().path("CreatePolicyResponse.CreatePolicyResult.Policy.Arn");
        cleanup.register(() -> iam("DeletePolicy").formParam("PolicyArn", arn)
                .when().post("/").then().statusCode(200));
        return arn;
    }

    String createInstanceProfile() {
        String instanceProfileName = unique("wording-profile");
        iam("CreateInstanceProfile").formParam("InstanceProfileName", instanceProfileName)
                .when().post("/").then().statusCode(200);
        cleanup.register(() -> iam("DeleteInstanceProfile").formParam("InstanceProfileName", instanceProfileName)
                .when().post("/").then().statusCode(200));
        return instanceProfileName;
    }
}
