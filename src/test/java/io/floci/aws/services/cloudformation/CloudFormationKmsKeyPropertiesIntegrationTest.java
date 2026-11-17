package io.floci.aws.services.cloudformation;

import io.floci.aws.testing.CfnRequests;
import io.floci.aws.testing.CfnStackWaits;
import io.floci.aws.testing.EnforcementFixtures;
import io.floci.aws.testing.RestAssuredJsonUtils;
import io.floci.aws.testing.TestAccounts;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.path.json.JsonPath;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.List;

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

    @Test
    void updateStackChangesDescriptionAndTagsInPlace() {
        String account = TestAccounts.randomId();
        String stack = "kms-cfn-update-" + account;
        String auth = EnforcementFixtures.auth(account, "cloudformation");
        CfnRequests.deploy("CreateStack", stack, template("v1", tags("one")), auth, "CREATE_COMPLETE");
        String keyId = CfnRequests.physicalId(stack, KEY_RESOURCE, auth);
        String arn = kms(account, "DescribeKey", "{\"KeyId\":\"%s\"}".formatted(keyId))
                .getString("KeyMetadata.Arn");
        int keysBefore = kms(account, "ListKeys", "{}").getList("Keys").size();

        CfnRequests.deploy("UpdateStack", stack, template("v2", tags("two")), auth, "UPDATE_COMPLETE");

        JsonPath key = kms(account, "DescribeKey", "{\"KeyId\":\"%s\"}".formatted(keyId));
        assertAll(
                () -> assertEquals(keyId, CfnRequests.physicalId(stack, KEY_RESOURCE, auth)),
                () -> assertEquals(arn, key.getString("KeyMetadata.Arn")),
                () -> assertEquals("v2", key.getString("KeyMetadata.Description")),
                () -> assertEquals(keysBefore, kms(account, "ListKeys", "{}").getList("Keys").size()),
                () -> assertEquals(List.of("two"), kms(account, "ListResourceTags",
                        "{\"KeyId\":\"%s\"}".formatted(keyId)).getList("Tags.TagValue")));
    }

    @Test
    void updateStackRollsBackWhenKeyUsageChanges() {
        String account = TestAccounts.randomId();
        String stack = "kms-cfn-immutable-" + account;
        String auth = EnforcementFixtures.auth(account, "cloudformation");
        CfnRequests.deploy("CreateStack", stack, template(KEY_DESCRIPTION, ""), auth, "CREATE_COMPLETE");
        String keyId = CfnRequests.physicalId(stack, KEY_RESOURCE, auth);
        int keysBefore = kms(account, "ListKeys", "{}").getList("Keys").size();

        CfnRequests.deploy("UpdateStack", stack, template("v2", "\"KeySpec\": \"ECC_NIST_P256\",\n\"KeyUsage\": \"SIGN_VERIFY\""),
                auth, "UPDATE_ROLLBACK_COMPLETE");

        JsonPath key = kms(account, "DescribeKey", "{\"KeyId\":\"%s\"}".formatted(keyId));
        assertAll(
                () -> assertEquals(keyId, CfnRequests.physicalId(stack, KEY_RESOURCE, auth)),
                () -> assertEquals(KEY_DESCRIPTION, key.getString("KeyMetadata.Description")),
                () -> assertEquals("ENCRYPT_DECRYPT", key.getString("KeyMetadata.KeyUsage")),
                () -> assertEquals(keysBefore, kms(account, "ListKeys", "{}").getList("Keys").size()));
    }

    private static String tags(String value) {
        return "\"Tags\": [{\"Key\": \"env\", \"Value\": \"%s\"}]".formatted(value);
    }

    private static String template(String additionalProperties) {
        return template(KEY_DESCRIPTION, additionalProperties);
    }

    private static String template(String description, String additionalProperties) {
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
            """.formatted(description, additionalProperties.isEmpty() ? ""
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
