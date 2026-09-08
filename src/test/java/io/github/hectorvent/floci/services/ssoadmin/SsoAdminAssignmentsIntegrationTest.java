package io.github.hectorvent.floci.services.ssoadmin;

import io.github.hectorvent.floci.testing.RestAssuredJsonUtils;
import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.notNullValue;

@QuarkusTest
class SsoAdminAssignmentsIntegrationTest {
    private static final String CONTENT_TYPE = "application/x-amz-json-1.1";
    private static final String AUTH = "AWS4-HMAC-SHA256 Credential=AKID/20260904/us-east-1/sso/aws4_request";

    @BeforeAll
    static void configureRestAssured() {
        RestAssuredJsonUtils.configureAwsContentTypes();
    }

    @Test
    void permissionSetAndAccountAssignmentLifecycle() {
        String instanceArn = json("SWBExternalService.ListInstances", "{}")
                .then().statusCode(200).extract().path("Instances[0].InstanceArn");
        String permissionSetArn = json("SWBExternalService.CreatePermissionSet",
                "{\"InstanceArn\":\"" + instanceArn + "\",\"Name\":\"PlatformAdmins\"}")
                .then().statusCode(200).body("PermissionSet.PermissionSetArn", notNullValue())
                .extract().path("PermissionSet.PermissionSetArn");
        json("SWBExternalService.ListPermissionSets", "{\"InstanceArn\":\"" + instanceArn + "\"}")
                .then().statusCode(200).body("PermissionSets", hasSize(1));

        String assignment = "{\"InstanceArn\":\"" + instanceArn
                + "\",\"TargetId\":\"123456789012\",\"TargetType\":\"AWS_ACCOUNT\",\"PermissionSetArn\":\""
                + permissionSetArn + "\",\"PrincipalType\":\"GROUP\",\"PrincipalId\":\"11111111-2222-3333-4444-555555555555\"}";
        String requestId = json("SWBExternalService.CreateAccountAssignment", assignment)
                .then().statusCode(200).extract().path("AccountAssignmentCreationStatus.RequestId");
        json("SWBExternalService.DescribeAccountAssignmentCreationStatus",
                "{\"InstanceArn\":\"" + instanceArn + "\",\"AccountAssignmentCreationRequestId\":\"" + requestId + "\"}")
                .then().statusCode(200).body("AccountAssignmentCreationStatus.Status", equalTo("SUCCEEDED"));
        json("SWBExternalService.ListAccountAssignments",
                "{\"InstanceArn\":\"" + instanceArn + "\",\"AccountId\":\"123456789012\",\"PermissionSetArn\":\""
                        + permissionSetArn + "\"}")
                .then().statusCode(200).body("AccountAssignments[0].PrincipalType", equalTo("GROUP"));
    }

    @Test
    void invalidAssignmentRequestIdReturnsResourceNotFound() {
        String instanceArn = json("SWBExternalService.ListInstances", "{}")
                .then().statusCode(200).extract().path("Instances[0].InstanceArn");
        json("SWBExternalService.DescribeAccountAssignmentCreationStatus",
                "{\"InstanceArn\":\"" + instanceArn + "\",\"AccountAssignmentCreationRequestId\":\"00000000-0000-0000-0000-000000000000\"}")
                .then().statusCode(400).body("__type", equalTo("ResourceNotFoundException"));
    }

    private static io.restassured.response.Response json(String target, String body) {
        return given().contentType(CONTENT_TYPE).header("Authorization", AUTH).header("X-Amz-Target", target)
                .body(body).post("/");
    }
}
