package io.github.hectorvent.floci.services.cloudformation;

import io.github.hectorvent.floci.testing.CfnRequests;
import io.github.hectorvent.floci.testing.CfnStackWaits;
import io.github.hectorvent.floci.testing.EnforcementFixtures;
import io.github.hectorvent.floci.testing.RestAssuredJsonUtils;
import io.github.hectorvent.floci.testing.TestAccounts;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.path.json.JsonPath;
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
        CfnStackWaits.awaitStatus(stack, "CREATE_COMPLETE", EnforcementFixtures.auth(account, "cloudformation"));

        String keyId = CfnRequests.physicalId(stack, KEY_RESOURCE, EnforcementFixtures.auth(account, "cloudformation"));
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
        CfnStackWaits.awaitStatus(stack, "CREATE_COMPLETE", EnforcementFixtures.auth(account, "cloudformation"));

        String keyId = CfnRequests.physicalId(stack, KEY_RESOURCE, EnforcementFixtures.auth(account, "cloudformation"));
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
        CfnRequests.cfn("CreateStack", stack)
            .header("Authorization", EnforcementFixtures.auth(account, "cloudformation"))
            .formParam("TemplateBody", template)
        .when()
            .post("/")
        .then()
            .statusCode(200);
    }

    private static JsonPath kms(String account, String action, String body) {
        return given()
                .header("Authorization", EnforcementFixtures.auth(account, "kms"))
                .header("X-Amz-Target", "TrentService." + action)
                .contentType("application/x-amz-json-1.1")
                .body(body)
            .when()
                .post("/")
            .then()
                .statusCode(200)
                .extract().jsonPath();
    }
}
