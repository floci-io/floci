package io.github.hectorvent.floci.services.cloudformation;

import io.github.hectorvent.floci.testing.CfnStackWaits;
import io.github.hectorvent.floci.testing.RestAssuredJsonUtils;
import io.github.hectorvent.floci.testing.TestAccounts;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.path.json.JsonPath;
import io.restassured.specification.RequestSpecification;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static io.restassured.RestAssured.given;
import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;

@QuarkusTest
class CloudFormationKmsKeyPropertiesIntegrationTest {

    private static final String KEY_RESOURCE = "Key";
    private static final String KEY_DESCRIPTION = "CloudFormation key properties";

    @BeforeAll
    static void configureRestAssured() {
        RestAssuredJsonUtils.configureAwsContentTypes();
    }

    @Test
    void createStackPreservesKmsKeySpecAndUsage() {
        String account = TestAccounts.randomId();
        String stack = "kms-cfn-spec-" + account;
        createStack(account, stack, template("""
            "KeySpec": "ECC_NIST_P256",
            "KeyUsage": "SIGN_VERIFY"
            """));
        CfnStackWaits.awaitStatus(stack, "CREATE_COMPLETE", authorization(account, "cloudformation"));

        String keyId = physicalId(account, stack);
        JsonPath key = kms(account, "DescribeKey", "{\"KeyId\":\"%s\"}".formatted(keyId));
        assertAll(
                () -> assertEquals(keyId, key.getString("KeyMetadata.KeyId")),
                () -> assertEquals("ECC_NIST_P256", key.getString("KeyMetadata.KeySpec")),
                () -> assertEquals("SIGN_VERIFY", key.getString("KeyMetadata.KeyUsage")));
    }

    @Test
    void createStackUsesDefaultKeySpecAndUsageWhenOmitted() {
        String account = TestAccounts.randomId();
        String stack = "kms-cfn-default-" + account;
        createStack(account, stack, template(""));
        CfnStackWaits.awaitStatus(stack, "CREATE_COMPLETE", authorization(account, "cloudformation"));

        String keyId = physicalId(account, stack);
        JsonPath key = kms(account, "DescribeKey", "{\"KeyId\":\"%s\"}".formatted(keyId));

        assertAll(
                () -> assertEquals(keyId, key.getString("KeyMetadata.KeyId")),
                () -> assertEquals("SYMMETRIC_DEFAULT", key.getString("KeyMetadata.KeySpec")),
                () -> assertEquals("ENCRYPT_DECRYPT", key.getString("KeyMetadata.KeyUsage")));
    }

    private static String template(String additionalProperties) {
        return """
            {
              "Resources": {
                "Key": {
                  "Type": "AWS::KMS::Key",
                  "Properties": {
                    "Description": "%s"%s
                  }
                }
              }
            }
            """.formatted(KEY_DESCRIPTION, additionalProperties.isEmpty() ? ""
                    : ",\n" + additionalProperties.strip());
    }

    private static void createStack(String account, String stack, String template) {
        cloudFormation(account, "CreateStack", stack)
            .formParam("TemplateBody", template)
        .when()
            .post("/")
        .then()
            .statusCode(200);
    }

    private static String physicalId(String account, String stack) {
        return cloudFormation(account, "DescribeStackResource", stack)
            .formParam("LogicalResourceId", KEY_RESOURCE)
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .extract().xmlPath().getString("**.find { it.name() == 'PhysicalResourceId' }");
    }

    private static RequestSpecification cloudFormation(String account, String action, String stack) {
        return given()
                .header("Authorization", authorization(account, "cloudformation"))
                .contentType("application/x-www-form-urlencoded")
                .formParam("Action", action)
                .formParam("StackName", stack);
    }

    private static JsonPath kms(String account, String action, String body) {
        return given()
                .header("Authorization", authorization(account, "kms"))
                .header("X-Amz-Target", "TrentService." + action)
                .contentType("application/x-amz-json-1.1")
                .body(body)
            .when()
                .post("/")
            .then()
                .statusCode(200)
                .extract().jsonPath();
    }

    private static String authorization(String account, String service) {
        return "AWS4-HMAC-SHA256 Credential=" + account + "/20261105/us-east-1/" + service + "/aws4_request";
    }
}
