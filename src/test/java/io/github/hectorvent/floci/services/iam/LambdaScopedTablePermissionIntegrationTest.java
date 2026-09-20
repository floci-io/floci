package io.github.hectorvent.floci.services.iam;

import io.github.hectorvent.floci.services.iam.model.SessionCreds;
import io.github.hectorvent.floci.services.lambda.launcher.LambdaExecutionRoleCredentials;
import io.github.hectorvent.floci.services.lambda.model.LambdaFunction;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.containsString;

@QuarkusTest
@TestProfile(LambdaExecutionRoleIamEnforcementIntegrationTest.IamEnforcementProfile.class)
class LambdaScopedTablePermissionIntegrationTest {

    private static final String ACCOUNT_ID = "000000000000";
    private static final String REGION = "us-east-1";

    @Inject
    IamService iamService;

    @Inject
    LambdaExecutionRoleCredentials executionRoleCredentials;

    @Test
    void executionRoleCanUseOnlyItsGrantedTableAndRequestBodySurvivesAuthorization() {
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        String tableName = "granted-table-" + suffix;
        String roleName = "ScopedTableRole" + suffix;
        String tableArn = "arn:aws:dynamodb:" + REGION + ":" + ACCOUNT_ID + ":table/" + tableName;
        given().config(io.restassured.RestAssured.config().encoderConfig(
                        io.restassured.config.EncoderConfig.encoderConfig().encodeContentTypeAs(
                                "application/x-amz-json-1.0", io.restassured.http.ContentType.TEXT)))
                .header("Authorization", auth("test"))
                .header("X-Amz-Target", "DynamoDB_20120810.CreateTable")
                .contentType("application/x-amz-json-1.0")
                .body("""
                        {"TableName":"%s","KeySchema":[{"AttributeName":"id","KeyType":"HASH"}],
                         "AttributeDefinitions":[{"AttributeName":"id","AttributeType":"S"}],
                         "BillingMode":"PAY_PER_REQUEST"}
                        """.formatted(tableName))
        .when().post("/")
        .then().statusCode(200);

        iamService.createRole(roleName, "/", "{}", null, 3600, null);
        iamService.putRolePolicy(roleName, "TableAccess", """
                {"Version":"2012-10-17","Statement":[{"Effect":"Allow",
                 "Action":["dynamodb:PutItem","dynamodb:GetItem"],"Resource":"%s"}]}
                """.formatted(tableArn));
        LambdaFunction function = new LambdaFunction();
        function.setFunctionName("scoped-table-" + suffix);
        function.setAccountId(ACCOUNT_ID);
        function.setRole("arn:aws:iam::" + ACCOUNT_ID + ":role/" + roleName);
        SessionCreds credentials = executionRoleCredentials.forFunction(function).orElseThrow();
        try {
            given().config(io.restassured.RestAssured.config().encoderConfig(
                        io.restassured.config.EncoderConfig.encoderConfig().encodeContentTypeAs(
                                "application/x-amz-json-1.0", io.restassured.http.ContentType.TEXT)))
                    .header("Authorization", auth(credentials.accessKeyId()))
                    .header("X-Amz-Target", "DynamoDB_20120810.PutItem")
                    .contentType("application/x-amz-json-1.0")
                    .body("""
                            {"TableName":"%s","Item":{"id":{"S":"record"},"value":{"S":"persisted"}}}
                            """.formatted(tableName))
            .when().post("/")
            .then().statusCode(200);

            given().config(io.restassured.RestAssured.config().encoderConfig(
                        io.restassured.config.EncoderConfig.encoderConfig().encodeContentTypeAs(
                                "application/x-amz-json-1.0", io.restassured.http.ContentType.TEXT)))
                    .header("Authorization", auth(credentials.accessKeyId()))
                    .header("X-Amz-Target", "DynamoDB_20120810.GetItem")
                    .contentType("application/x-amz-json-1.0")
                    .body("""
                            {"TableName":"%s","Key":{"id":{"S":"record"}}}
                            """.formatted(tableName))
            .when().post("/")
            .then().statusCode(200).body(containsString("persisted"));

            given().config(io.restassured.RestAssured.config().encoderConfig(
                        io.restassured.config.EncoderConfig.encoderConfig().encodeContentTypeAs(
                                "application/x-amz-json-1.0", io.restassured.http.ContentType.TEXT)))
                    .header("Authorization", auth(credentials.accessKeyId()))
                    .header("X-Amz-Target", "DynamoDB_20120810.GetItem")
                    .contentType("application/x-amz-json-1.0")
                    .body("""
                            {"TableName":"not-granted-%s","Key":{"id":{"S":"record"}}}
                            """.formatted(suffix))
            .when().post("/")
            .then().statusCode(403).body(containsString("AccessDenied"));
        } finally {
            executionRoleCredentials.unregister(ACCOUNT_ID, credentials.accessKeyId());
            iamService.deleteRolePolicy(roleName, "TableAccess");
            iamService.deleteRole(roleName);
        }
    }

    private static String auth(String accessKeyId) {
        return "AWS4-HMAC-SHA256 Credential=" + accessKeyId + "/20260910/" + REGION
                + "/dynamodb/aws4_request, SignedHeaders=host, Signature=abc";
    }
}
