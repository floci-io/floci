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
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.util.List;
import java.util.stream.Stream;

import static io.restassured.RestAssured.given;
import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

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

    private static final String POLICY_ONE = policy("PolicyOne");
    private static final String POLICY_TWO = policy("PolicyTwo");

    static Stream<Arguments> honoredCreateProperties() {
        return Stream.of(
                Arguments.of("\"KeyPolicy\": " + POLICY_ONE, "GetKeyPolicy", "Policy", "PolicyOne"),
                Arguments.of("\"Enabled\": false", "DescribeKey", "KeyMetadata.KeyState", "Disabled"),
                Arguments.of("\"Enabled\": false", "DescribeKey", "KeyMetadata.Enabled", "false"),
                Arguments.of("\"EnableKeyRotation\": true", "GetKeyRotationStatus", "KeyRotationEnabled", "true"),
                Arguments.of("\"Origin\": \"EXTERNAL\"", "DescribeKey", "KeyMetadata.Origin", "EXTERNAL"),
                Arguments.of("\"Origin\": \"EXTERNAL\"", "DescribeKey", "KeyMetadata.KeyState", "PendingImport"),
                Arguments.of("\"MultiRegion\": true", "DescribeKey", "KeyMetadata.MultiRegion", "true"));
    }

    @ParameterizedTest
    @MethodSource("honoredCreateProperties")
    void createStackHonorsKeyProperty(String property, String action, String path, String expected) {
        String account = TestAccounts.randomId();
        String stack = "kms-cfn-honor-" + account;
        String auth = EnforcementFixtures.auth(account, "cloudformation");
        CfnRequests.deploy("CreateStack", stack, template(property), auth, "CREATE_COMPLETE");
        String keyId = CfnRequests.physicalId(stack, KEY_RESOURCE, auth);

        String actual = kms(account, action, "{\"KeyId\":\"%s\"}".formatted(keyId)).getString(path);

        assertTrue(actual.contains(expected), path + " was " + actual);
    }

    @Test
    void updateChangesKeyPropertiesInPlaceAndARolledBackUpdateRestoresThem() {
        String account = TestAccounts.randomId();
        String stack = "kms-cfn-lifecycle-" + account;
        String auth = EnforcementFixtures.auth(account, "cloudformation");
        String keyBody = "{\"KeyId\":\"%s\"}";
        CfnRequests.deploy("CreateStack", stack, template("v1",
                "\"Enabled\": false,\n\"EnableKeyRotation\": false,\n\"KeyPolicy\": " + POLICY_ONE + ",\n" + tags("one")),
                auth, "CREATE_COMPLETE");
        String keyId = CfnRequests.physicalId(stack, KEY_RESOURCE, auth);

        CfnRequests.deploy("UpdateStack", stack, template("v2",
                "\"Enabled\": true,\n\"EnableKeyRotation\": true,\n\"KeyPolicy\": " + POLICY_TWO + ",\n" + tags("two")),
                auth, "UPDATE_COMPLETE");

        JsonPath updated = kms(account, "DescribeKey", keyBody.formatted(keyId));
        assertAll(
                () -> assertEquals(keyId, CfnRequests.physicalId(stack, KEY_RESOURCE, auth)),
                () -> assertEquals("Enabled", updated.getString("KeyMetadata.KeyState")),
                () -> assertTrue(kms(account, "GetKeyRotationStatus", keyBody.formatted(keyId))
                        .getBoolean("KeyRotationEnabled")),
                () -> assertTrue(kms(account, "GetKeyPolicy", keyBody.formatted(keyId))
                        .getString("Policy").contains("PolicyTwo")));

        String failing = template("v3", "\"Enabled\": false,\n\"EnableKeyRotation\": false,\n" + tags("three"))
                .replace("\n  }\n}", ",\n"
                        + "\"BadSecret\": {\"Type\": \"AWS::SecretsManager::Secret\", \"Properties\": "
                        + "{\"SecretString\": \"x\", \"GenerateSecretString\": {\"PasswordLength\": 8}}}\n  }\n}");
        CfnRequests.deploy("UpdateStack", stack, failing, auth, "UPDATE_ROLLBACK_COMPLETE");

        JsonPath restored = kms(account, "DescribeKey", keyBody.formatted(keyId));
        assertAll(
                () -> assertEquals(keyId, CfnRequests.physicalId(stack, KEY_RESOURCE, auth)),
                () -> assertEquals("v2", restored.getString("KeyMetadata.Description")),
                () -> assertEquals("Enabled", restored.getString("KeyMetadata.KeyState")),
                () -> assertTrue(kms(account, "GetKeyRotationStatus", keyBody.formatted(keyId))
                        .getBoolean("KeyRotationEnabled")),
                () -> assertEquals(List.of("two"), kms(account, "ListResourceTags", keyBody.formatted(keyId))
                        .getList("Tags.TagValue")));
    }

    private static String policy(String sid) {
        return "{\"Version\": \"2012-10-17\", \"Statement\": [{\"Sid\": \"%s\", \"Effect\": \"Allow\", "
                .formatted(sid) + "\"Principal\": {\"AWS\": \"*\"}, \"Action\": \"kms:*\", \"Resource\": \"*\"}]}";
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
