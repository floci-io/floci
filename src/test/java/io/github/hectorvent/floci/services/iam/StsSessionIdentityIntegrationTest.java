package io.github.hectorvent.floci.services.iam;

import io.quarkus.test.junit.QuarkusTest;
import io.restassured.response.ValidatableResponse;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;
import static org.junit.jupiter.api.Assertions.assertEquals;

@QuarkusTest
class StsSessionIdentityIntegrationTest {

    private static final String ROLE_NAME = "sts-session-identity-role";
    private static final String ROLE_ARN = "arn:aws:iam::000000000000:role/" + ROLE_NAME;
    private static final String SESSION_NAME = "my-custom-session-name";
    private static final String AUTHORIZATION =
            "AWS4-HMAC-SHA256 Credential=test/20260925/us-east-1/iam/aws4_request";
    private static final String STS_AUTHORIZATION =
            "AWS4-HMAC-SHA256 Credential=test/20260925/us-east-1/sts/aws4_request";
    private static final String CHINA_ROLE_NAME = "sts-session-identity-china-role";
    private static final String CHINA_IAM_AUTHORIZATION =
            "AWS4-HMAC-SHA256 Credential=test/20260925/cn-north-1/iam/aws4_request";

    @AfterEach
    void deleteRole() {
        given()
                .formParam("Action", "DeleteRole")
                .formParam("RoleName", ROLE_NAME)
                .header("Authorization", AUTHORIZATION)
                .when().post("/");
        given()
                .formParam("Action", "DeleteRole")
                .formParam("RoleName", CHINA_ROLE_NAME)
                .header("Authorization", CHINA_IAM_AUTHORIZATION)
                .when().post("/");
    }

    @Test
    void callerIdentityMatchesTheAssumedRoleSession() {
        given()
                .formParam("Action", "CreateRole")
                .formParam("RoleName", ROLE_NAME)
                .formParam("AssumeRolePolicyDocument",
                        "{\"Version\":\"2012-10-17\",\"Statement\":[{\"Effect\":\"Allow\","
                        + "\"Principal\":{\"AWS\":\"arn:aws:iam::000000000000:root\"},"
                        + "\"Action\":\"sts:AssumeRole\"}]}")
                .header("Authorization", AUTHORIZATION)
                .when().post("/").then().statusCode(200);

        ValidatableResponse assumed = given()
                .formParam("Action", "AssumeRole")
                .formParam("RoleArn", ROLE_ARN)
                .formParam("RoleSessionName", SESSION_NAME)
                .header("Authorization", STS_AUTHORIZATION)
                .when().post("/").then().statusCode(200);
        String accessKeyId = assumed.extract().path(
                "AssumeRoleResponse.AssumeRoleResult.Credentials.AccessKeyId");
        String sessionToken = assumed.extract().path(
                "AssumeRoleResponse.AssumeRoleResult.Credentials.SessionToken");
        String assumedRoleArn = assumed.extract().path(
                "AssumeRoleResponse.AssumeRoleResult.AssumedRoleUser.Arn");
        String assumedRoleId = assumed.extract().path(
                "AssumeRoleResponse.AssumeRoleResult.AssumedRoleUser.AssumedRoleId");

        given()
                .formParam("Action", "GetCallerIdentity")
                .header("Authorization",
                        "AWS4-HMAC-SHA256 Credential=" + accessKeyId
                        + "/20260925/us-east-1/sts/aws4_request")
                .header("X-Amz-Security-Token", sessionToken)
                .when().post("/").then().statusCode(200)
                .body("GetCallerIdentityResponse.GetCallerIdentityResult.Arn", equalTo(assumedRoleArn))
                .body("GetCallerIdentityResponse.GetCallerIdentityResult.UserId", equalTo(assumedRoleId));
    }

    /** The session keeps its role's partition: GetCallerIdentity reports the ARN AssumeRole issued. */
    @Test
    void callerIdentityKeepsTheChinaPartitionOfTheAssumedRoleSession() {
        String roleArn = given()
                .formParam("Action", "CreateRole")
                .formParam("RoleName", CHINA_ROLE_NAME)
                .formParam("AssumeRolePolicyDocument",
                        "{\"Version\":\"2012-10-17\",\"Statement\":[{\"Effect\":\"Allow\","
                        + "\"Principal\":{\"AWS\":\"arn:aws-cn:iam::000000000000:root\"},"
                        + "\"Action\":\"sts:AssumeRole\"}]}")
                .header("Authorization", CHINA_IAM_AUTHORIZATION)
                .when().post("/").then().statusCode(200)
                .extract().path("CreateRoleResponse.CreateRoleResult.Role.Arn");
        assertEquals("arn:aws-cn:iam::000000000000:role/" + CHINA_ROLE_NAME, roleArn);

        ValidatableResponse assumed = given()
                .formParam("Action", "AssumeRole")
                .formParam("RoleArn", roleArn)
                .formParam("RoleSessionName", SESSION_NAME)
                .header("Authorization", "AWS4-HMAC-SHA256 Credential=test/20260925/cn-north-1/sts/aws4_request")
                .when().post("/").then().statusCode(200);
        String accessKeyId = assumed.extract().path(
                "AssumeRoleResponse.AssumeRoleResult.Credentials.AccessKeyId");
        String sessionToken = assumed.extract().path(
                "AssumeRoleResponse.AssumeRoleResult.Credentials.SessionToken");
        String assumedRoleArn = assumed.extract().path(
                "AssumeRoleResponse.AssumeRoleResult.AssumedRoleUser.Arn");
        assertEquals("arn:aws-cn:sts::000000000000:assumed-role/" + CHINA_ROLE_NAME + "/" + SESSION_NAME,
                assumedRoleArn);

        given()
                .formParam("Action", "GetCallerIdentity")
                .header("Authorization",
                        "AWS4-HMAC-SHA256 Credential=" + accessKeyId
                        + "/20260925/cn-north-1/sts/aws4_request")
                .header("X-Amz-Security-Token", sessionToken)
                .when().post("/").then().statusCode(200)
                .body("GetCallerIdentityResponse.GetCallerIdentityResult.Arn", equalTo(assumedRoleArn));
    }

    /**
     * A role created in the commercial partition and assumed with a call signed for China: the
     * session stays in the role's partition, and AssumeRole and GetCallerIdentity agree on it.
     */
    @Test
    void assumeRoleAndCallerIdentityAgreeWhenSignedForAnotherPartition() {
        String roleArn = given()
                .formParam("Action", "CreateRole")
                .formParam("RoleName", ROLE_NAME)
                .formParam("AssumeRolePolicyDocument",
                        "{\"Version\":\"2012-10-17\",\"Statement\":[{\"Effect\":\"Allow\","
                        + "\"Principal\":{\"AWS\":\"*\"},\"Action\":\"sts:AssumeRole\"}]}")
                .header("Authorization", AUTHORIZATION)
                .when().post("/").then().statusCode(200)
                .extract().path("CreateRoleResponse.CreateRoleResult.Role.Arn");

        ValidatableResponse assumed = given()
                .formParam("Action", "AssumeRole")
                .formParam("RoleArn", roleArn)
                .formParam("RoleSessionName", SESSION_NAME)
                .header("Authorization", "AWS4-HMAC-SHA256 Credential=test/20260925/cn-north-1/sts/aws4_request")
                .when().post("/").then().statusCode(200);
        String assumedRoleArn = assumed.extract().path(
                "AssumeRoleResponse.AssumeRoleResult.AssumedRoleUser.Arn");
        assertEquals("arn:aws:sts::000000000000:assumed-role/" + ROLE_NAME + "/" + SESSION_NAME, assumedRoleArn);

        given()
                .formParam("Action", "GetCallerIdentity")
                .header("Authorization",
                        "AWS4-HMAC-SHA256 Credential="
                        + assumed.extract().path("AssumeRoleResponse.AssumeRoleResult.Credentials.AccessKeyId")
                        + "/20260925/cn-north-1/sts/aws4_request")
                .header("X-Amz-Security-Token", (String) assumed.extract().path(
                        "AssumeRoleResponse.AssumeRoleResult.Credentials.SessionToken"))
                .when().post("/").then().statusCode(200)
                .body("GetCallerIdentityResponse.GetCallerIdentityResult.Arn", equalTo(assumedRoleArn));
    }

    /**
     * A RoleArn naming another partition still finds the role (Floci keys IAM by account and
     * name), but the session belongs to the stored role, so it keeps the role's China partition.
     */
    @Test
    void sessionFollowsTheStoredRoleWhenTheRoleArnNamesAnotherPartition() {
        given()
                .formParam("Action", "CreateRole")
                .formParam("RoleName", CHINA_ROLE_NAME)
                .formParam("AssumeRolePolicyDocument",
                        "{\"Version\":\"2012-10-17\",\"Statement\":[{\"Effect\":\"Allow\","
                        + "\"Principal\":{\"AWS\":\"*\"},\"Action\":\"sts:AssumeRole\"}]}")
                .header("Authorization", CHINA_IAM_AUTHORIZATION)
                .when().post("/").then().statusCode(200);

        ValidatableResponse assumed = given()
                .formParam("Action", "AssumeRole")
                .formParam("RoleArn", "arn:aws:iam::000000000000:role/" + CHINA_ROLE_NAME)
                .formParam("RoleSessionName", SESSION_NAME)
                .header("Authorization", STS_AUTHORIZATION)
                .when().post("/").then().statusCode(200);
        String assumedRoleArn = assumed.extract().path(
                "AssumeRoleResponse.AssumeRoleResult.AssumedRoleUser.Arn");
        assertEquals("arn:aws-cn:sts::000000000000:assumed-role/" + CHINA_ROLE_NAME + "/" + SESSION_NAME,
                assumedRoleArn);

        given()
                .formParam("Action", "GetCallerIdentity")
                .header("Authorization",
                        "AWS4-HMAC-SHA256 Credential="
                        + assumed.extract().path("AssumeRoleResponse.AssumeRoleResult.Credentials.AccessKeyId")
                        + "/20260925/us-east-1/sts/aws4_request")
                .header("X-Amz-Security-Token", (String) assumed.extract().path(
                        "AssumeRoleResponse.AssumeRoleResult.Credentials.SessionToken"))
                .when().post("/").then().statusCode(200)
                .body("GetCallerIdentityResponse.GetCallerIdentityResult.Arn", equalTo(assumedRoleArn));
    }
}
