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

@QuarkusTest
@TestProfile(IamEnforcementProfile.class)
class IamServingServiceEnforcementIntegrationTest {

    private static final String ACCOUNT_ID = "000000000000";
    private static final String REGION = "us-east-1";

    @Test
    void queryRequestCannotBorrowPermissionsFromItsSigningScope() {
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        String caller = "query-scope-" + suffix;
        String attempted = "created-through-query-" + suffix;
        String accessKeyId = createUserWithPolicy(caller, "LambdaOnly", """
                {"Version":"2012-10-17","Statement":[
                  {"Effect":"Allow","Action":"lambda:*","Resource":"*"}
                ]}""");

        iamCall(accessKeyId, "lambda", "CreateUser", Map.of("UserName", attempted))
                .statusCode(403)
                .body(containsString("iam:CreateUser"));

        adminIam("GetUser", Map.of("UserName", attempted)).statusCode(404);
    }

    @Test
    void operationOnlyQueryRequestCannotBypassIamEnforcement() {
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        String caller = "operation-scope-" + suffix;
        String attempted = "created-through-operation-" + suffix;
        String accessKeyId = createUserWithPolicy(caller, "LambdaOnly", """
                {"Version":"2012-10-17","Statement":[
                  {"Effect":"Allow","Action":"lambda:*","Resource":"*"}
                ]}""");

        iamOperationCall(accessKeyId, "lambda", "CreateUser", Map.of("UserName", attempted))
                .statusCode(403)
                .body(containsString("iam:CreateUser"));

        adminIam("GetUser", Map.of("UserName", attempted)).statusCode(404);
    }

    @Test
    void restRequestCannotBypassApiGatewayPolicyWithIamScope() {
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        String caller = "rest-scope-" + suffix;
        String apiName = "blocked-api-" + suffix;
        String accessKeyId = createUserWithPolicy(caller, "IamOnly", """
                {"Version":"2012-10-17","Statement":[
                  {"Effect":"Allow","Action":"iam:*","Resource":"*"}
                ]}""");

        given()
                .header("Authorization", authorization(accessKeyId, "iam"))
                .contentType("application/json")
                .body(Map.of("name", apiName))
        .when()
                .post("/restapis")
        .then()
                .statusCode(403)
                .body(containsString("apigateway:POST"));

        given()
                .header("Authorization", authorization(ACCOUNT_ID, "apigateway"))
        .when()
                .get("/restapis")
        .then()
                .statusCode(200)
                .body(not(containsString(apiName)));
    }

    private static String createUserWithPolicy(String userName, String policyName, String policyDocument) {
        adminIam("CreateUser", Map.of("UserName", userName)).statusCode(200);
        adminIam("PutUserPolicy", Map.of(
                "UserName", userName,
                "PolicyName", policyName,
                "PolicyDocument", policyDocument)).statusCode(200);
        return adminIam("CreateAccessKey", Map.of("UserName", userName))
                .statusCode(200)
                .extract().path("CreateAccessKeyResponse.CreateAccessKeyResult.AccessKey.AccessKeyId");
    }

    private static ValidatableResponse adminIam(String action, Map<String, String> params) {
        return iamCall(ACCOUNT_ID, "iam", action, params);
    }

    private static ValidatableResponse iamCall(String accessKeyId, String scope, String action,
                                               Map<String, String> params) {
        return iamCall(accessKeyId, scope, "Action", action, params);
    }

    private static ValidatableResponse iamOperationCall(String accessKeyId, String scope, String operation,
                                                        Map<String, String> params) {
        return iamCall(accessKeyId, scope, "Operation", operation, params);
    }

    private static ValidatableResponse iamCall(String accessKeyId, String scope, String operationName,
                                               String operation, Map<String, String> params) {
        RequestSpecification spec = given()
                .header("Authorization", authorization(accessKeyId, scope))
                .contentType("application/x-www-form-urlencoded")
                .formParam(operationName, operation)
                .formParam("Version", "2010-05-08");
        params.forEach(spec::formParam);
        return spec.when().post("/").then();
    }

    private static String authorization(String accessKeyId, String service) {
        return "AWS4-HMAC-SHA256 Credential=" + accessKeyId + "/20260924/" + REGION + "/" + service
                + "/aws4_request, SignedHeaders=host, Signature=abc";
    }
}
