package io.github.hectorvent.floci.services.secretsmanager;

import io.github.hectorvent.floci.services.lambda.LambdaService;
import io.github.hectorvent.floci.services.lambda.model.InvokeResult;
import io.github.hectorvent.floci.services.lambda.model.LambdaFunction;
import io.github.hectorvent.floci.testing.IamEnforcementProfile;
import io.github.hectorvent.floci.testing.RestAssuredJsonUtils;
import io.quarkus.test.InjectMock;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;
import io.restassured.response.ValidatableResponse;
import io.restassured.specification.RequestSpecification;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.Map;
import java.util.UUID;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;

/**
 * RotateSecret needs the caller to hold {@code lambda:InvokeFunction} on the rotation function
 * as well as {@code secretsmanager:RotateSecret} (Secrets Manager API Reference, RotateSecret,
 * "Required permissions").
 */
@QuarkusTest
@TestProfile(IamEnforcementProfile.class)
class SecretsManagerRotationInvokeAuthorizationIntegrationTest {

    private static final String SM_CONTENT_TYPE = "application/x-amz-json-1.1";
    private static final String ACCOUNT_ID = "111122223333";
    private static final String REGION = "us-east-1";
    private static final DateTimeFormatter AMZ_DATE_FMT =
            DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss'Z'").withZone(ZoneOffset.UTC);

    @InjectMock
    LambdaService lambdaService;

    @BeforeAll
    static void configureRestAssured() {
        RestAssuredJsonUtils.configureAwsContentTypes();
    }

    @BeforeEach
    void stubLambda() {
        Mockito.when(lambdaService.getFunction(anyString(), anyString())).thenReturn(new LambdaFunction());
        InvokeResult ok = new InvokeResult();
        ok.setStatusCode(200);
        Mockito.when(lambdaService.invoke(any(), any(), any(), any())).thenReturn(ok);
    }

    @Test
    void rotateSecretIsDeniedWhenCallerCannotInvokeTheRotationFunction() {
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        String functionArn = "arn:aws:lambda:" + REGION + ":" + ACCOUNT_ID + ":function:rotator-" + suffix;
        String accessKeyId = createUserWithPolicy("sm-only-" + suffix, """
                {"Version":"2012-10-17","Statement":[
                  {"Effect":"Allow","Action":"secretsmanager:*","Resource":"*"}]}""");
        String secretName = "rotate-denied-" + suffix;
        secretsManager(accessKeyId, "CreateSecret",
                "{\"Name\":\"" + secretName + "\",\"SecretString\":\"v1\"}").statusCode(200);

        secretsManager(accessKeyId, "RotateSecret",
                "{\"SecretId\":\"" + secretName + "\",\"RotationLambdaARN\":\"" + functionArn + "\"}")
                .statusCode(400)
                .body("__type", equalTo("AccessDeniedException"));

        Mockito.verify(lambdaService, Mockito.never()).invoke(any(), eq(functionArn), any(), any());
    }

    @Test
    void presignedRotateSecretIsCheckedLikeASignedOne() {
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        String functionArn = "arn:aws:lambda:" + REGION + ":" + ACCOUNT_ID + ":function:rotator-" + suffix;
        String accessKeyId = createUserWithPolicy("sm-presigned-" + suffix, """
                {"Version":"2012-10-17","Statement":[
                  {"Effect":"Allow","Action":"secretsmanager:*","Resource":"*"}]}""");
        String secretName = "rotate-presigned-" + suffix;
        secretsManager(accessKeyId, "CreateSecret",
                "{\"Name\":\"" + secretName + "\",\"SecretString\":\"v1\"}").statusCode(200);

        String amzDate = AMZ_DATE_FMT.format(Instant.now());
        given()
                .queryParam("X-Amz-Algorithm", "AWS4-HMAC-SHA256")
                .queryParam("X-Amz-Credential",
                        accessKeyId + "/" + amzDate.substring(0, 8) + "/" + REGION + "/secretsmanager/aws4_request")
                .queryParam("X-Amz-Date", amzDate)
                .queryParam("X-Amz-Expires", "3600")
                .queryParam("X-Amz-SignedHeaders", "host")
                .queryParam("X-Amz-Signature", "abc")
                .header("X-Amz-Target", "secretsmanager.RotateSecret")
                .contentType(SM_CONTENT_TYPE)
                .body("{\"SecretId\":\"" + secretName + "\",\"RotationLambdaARN\":\"" + functionArn + "\"}")
        .when()
                .post("/")
        .then()
                .statusCode(400)
                .body("__type", equalTo("AccessDeniedException"));

        Mockito.verify(lambdaService, Mockito.never()).invoke(any(), eq(functionArn), any(), any());
    }

    @Test
    void functionNamedByNameIsCheckedAsItsArn() {
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        String functionName = "rotator-" + suffix;
        String functionArn = "arn:aws:lambda:" + REGION + ":" + ACCOUNT_ID + ":function:" + functionName;
        String accessKeyId = createUserWithPolicy("sm-by-name-" + suffix, """
                {"Version":"2012-10-17","Statement":[
                  {"Effect":"Allow","Action":"secretsmanager:*","Resource":"*"},
                  {"Effect":"Allow","Action":"lambda:InvokeFunction","Resource":"%s"}]}"""
                .formatted(functionArn));
        String secretName = "rotate-by-name-" + suffix;
        secretsManager(accessKeyId, "CreateSecret",
                "{\"Name\":\"" + secretName + "\",\"SecretString\":\"v1\"}").statusCode(200);

        secretsManager(accessKeyId, "RotateSecret",
                "{\"SecretId\":\"" + secretName + "\",\"RotationLambdaARN\":\"" + functionName + "\"}")
                .statusCode(200);

        Mockito.verify(lambdaService, Mockito.timeout(5000).atLeastOnce())
                .invoke(any(), eq(functionArn), any(), any());
    }

    @Test
    void rotateSecretProceedsWhenCallerCanInvokeTheRotationFunction() {
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        String functionArn = "arn:aws:lambda:" + REGION + ":" + ACCOUNT_ID + ":function:rotator-" + suffix;
        String accessKeyId = createUserWithPolicy("sm-and-invoke-" + suffix, """
                {"Version":"2012-10-17","Statement":[
                  {"Effect":"Allow","Action":"secretsmanager:*","Resource":"*"},
                  {"Effect":"Allow","Action":"lambda:InvokeFunction","Resource":"%s"}]}"""
                .formatted(functionArn));
        String secretName = "rotate-allowed-" + suffix;
        secretsManager(accessKeyId, "CreateSecret",
                "{\"Name\":\"" + secretName + "\",\"SecretString\":\"v1\"}").statusCode(200);

        secretsManager(accessKeyId, "RotateSecret",
                "{\"SecretId\":\"" + secretName + "\",\"RotationLambdaARN\":\"" + functionArn + "\"}")
                .statusCode(200);

        Mockito.verify(lambdaService, Mockito.timeout(5000).atLeastOnce())
                .invoke(any(), eq(functionArn), any(), any());
    }

    private static ValidatableResponse secretsManager(String accessKeyId, String operation, String body) {
        return given()
                .header("Authorization", authorization(accessKeyId, "secretsmanager"))
                .header("X-Amz-Target", "secretsmanager." + operation)
                .contentType(SM_CONTENT_TYPE)
                .body(body)
        .when()
                .post("/")
        .then();
    }

    private static String createUserWithPolicy(String userName, String policyDocument) {
        adminIam("CreateUser", Map.of("UserName", userName)).statusCode(200);
        adminIam("PutUserPolicy", Map.of(
                "UserName", userName,
                "PolicyName", "inline",
                "PolicyDocument", policyDocument)).statusCode(200);
        return adminIam("CreateAccessKey", Map.of("UserName", userName))
                .statusCode(200)
                .extract().path("CreateAccessKeyResponse.CreateAccessKeyResult.AccessKey.AccessKeyId");
    }

    private static ValidatableResponse adminIam(String action, Map<String, String> params) {
        RequestSpecification spec = given()
                .header("Authorization", authorization(ACCOUNT_ID, "iam"))
                .contentType("application/x-www-form-urlencoded")
                .formParam("Action", action)
                .formParam("Version", "2010-05-08");
        params.forEach(spec::formParam);
        return spec.when().post("/").then();
    }

    private static String authorization(String accessKeyId, String service) {
        return "AWS4-HMAC-SHA256 Credential=" + accessKeyId + "/20260929/" + REGION + "/" + service
                + "/aws4_request, SignedHeaders=host, Signature=abc";
    }
}
