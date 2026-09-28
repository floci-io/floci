package io.github.hectorvent.floci.services.glue;

import com.fasterxml.jackson.databind.JsonNode;
import io.github.hectorvent.floci.testing.RestAssuredJsonUtils;
import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.UUID;

import static io.github.hectorvent.floci.testing.RestAssuredJsonUtils.awsAction;
import static io.github.hectorvent.floci.testing.RestAssuredJsonUtils.awsActionJson;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasKey;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.notNullValue;
import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * The catalog's resource policy and encryption settings over JSON 1.1, in the order Terraform
 * drives them, plus the observable effect of ConnectionPasswordEncryption: a connection created
 * while it is on stores its password KMS-encrypted and GetConnection returns it that way.
 * Both settings are one per catalog, so the class runs in order and puts the catalog back at
 * the end.
 */
@QuarkusTest
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class GlueCatalogPolicyAndEncryptionIntegrationTest {

    private static final String GLUE = "AWSGlue";
    private static final String KMS = "TrentService";
    private static final String POLICY = "{\"Version\":\"2012-10-17\",\"Statement\":[{\"Effect\":\"Allow\","
            + "\"Principal\":{\"AWS\":\"arn:aws:iam::111122223333:root\"},\"Action\":\"glue:GetTable\",\"Resource\":\"*\"}]}";
    private static final String POLICY_V2 = POLICY.replace("glue:GetTable", "glue:GetTables");

    private static String policyHash;

    @BeforeAll
    static void configureRestAssured() {
        RestAssuredJsonUtils.configureAwsContentTypes();
    }

    @Test
    @Order(1)
    void resourcePolicy_absentAtFirst_isEntityNotFound() {
        // The catalog may carry a policy from another class; start from a known state.
        awsAction(GLUE, "DeleteResourcePolicy", "{}");
        awsAction(GLUE, "GetResourcePolicy", "{}")
                .then()
                .statusCode(400)
                .body("__type", equalTo("EntityNotFoundException"));
        awsAction(GLUE, "GetResourcePolicies", "{}")
                .then()
                .statusCode(200)
                .body("GetResourcePoliciesResponseList.size()", equalTo(0));
    }

    @Test
    @Order(2)
    void putResourcePolicy_notExistThenMustExist_createsThenUpdates() throws Exception {
        JsonNode created = awsActionJson(GLUE, "PutResourcePolicy",
                "{\"PolicyInJson\": " + quoted(POLICY) + ", \"PolicyExistsCondition\": \"NOT_EXIST\"}");
        policyHash = created.get("PolicyHash").asText();

        awsAction(GLUE, "GetResourcePolicy", "{}")
                .then()
                .statusCode(200)
                .body("PolicyInJson", equalTo(POLICY))
                .body("PolicyHash", equalTo(policyHash))
                .body("CreateTime", notNullValue())
                .body("UpdateTime", notNullValue());

        // Terraform's second create against an existing policy must be refused.
        awsAction(GLUE, "PutResourcePolicy",
                "{\"PolicyInJson\": " + quoted(POLICY_V2) + ", \"PolicyExistsCondition\": \"NOT_EXIST\"}")
                .then()
                .statusCode(400)
                .body("__type", equalTo("ConditionCheckFailureException"));

        JsonNode updated = awsActionJson(GLUE, "PutResourcePolicy",
                "{\"PolicyInJson\": " + quoted(POLICY_V2) + ", \"PolicyExistsCondition\": \"MUST_EXIST\","
                        + " \"PolicyHashCondition\": " + quoted(policyHash) + ", \"EnableHybrid\": \"TRUE\"}");
        policyHash = updated.get("PolicyHash").asText();
        awsAction(GLUE, "GetResourcePolicies", "{}")
                .then()
                .statusCode(200)
                .body("GetResourcePoliciesResponseList.size()", equalTo(1))
                .body("GetResourcePoliciesResponseList[0].PolicyInJson", equalTo(POLICY_V2));
    }

    @Test
    @Order(3)
    void deleteResourcePolicy_wrongHashRefused_rightHashDeletes() {
        awsAction(GLUE, "DeleteResourcePolicy", "{\"PolicyHashCondition\": \"stale\"}")
                .then()
                .statusCode(400)
                .body("__type", equalTo("ConditionCheckFailureException"));
        awsAction(GLUE, "DeleteResourcePolicy", "{\"PolicyHashCondition\": " + quoted(policyHash) + "}")
                .then()
                .statusCode(200);
        awsAction(GLUE, "DeleteResourcePolicy", "{}")
                .then()
                .statusCode(400)
                .body("__type", equalTo("EntityNotFoundException"));
    }

    @Test
    @Order(4)
    void encryptionSettings_defaultOff_thenEncryptConnectionPasswordsWithARealKmsKey() throws Exception {
        awsAction(GLUE, "GetDataCatalogEncryptionSettings", "{}")
                .then()
                .statusCode(200)
                .body("DataCatalogEncryptionSettings.EncryptionAtRest.CatalogEncryptionMode", equalTo("DISABLED"))
                .body("DataCatalogEncryptionSettings.ConnectionPasswordEncryption.ReturnConnectionPasswordEncrypted", equalTo(false));

        String keyId = awsActionJson(KMS, "CreateKey", "{\"Description\": \"glue connection passwords\"}")
                .path("KeyMetadata").path("KeyId").asText();
        awsAction(GLUE, "PutDataCatalogEncryptionSettings", """
                {"DataCatalogEncryptionSettings": {
                   "EncryptionAtRest": {"CatalogEncryptionMode": "DISABLED"},
                   "ConnectionPasswordEncryption": {"ReturnConnectionPasswordEncrypted": true, "AwsKmsKeyId": "%s"}
                }}
                """.formatted(keyId))
                .then().statusCode(200);
        awsAction(GLUE, "GetDataCatalogEncryptionSettings", "{}")
                .then()
                .statusCode(200)
                .body("DataCatalogEncryptionSettings.ConnectionPasswordEncryption.ReturnConnectionPasswordEncrypted", equalTo(true))
                .body("DataCatalogEncryptionSettings.ConnectionPasswordEncryption.AwsKmsKeyId", equalTo(keyId));

        String name = "enc-" + UUID.randomUUID().toString().substring(0, 8);
        awsAction(GLUE, "CreateConnection", """
                {"ConnectionInput": {"Name": "%s", "ConnectionType": "JDBC",
                  "ConnectionProperties": {"JDBC_CONNECTION_URL": "jdbc:postgresql://db:5432/orders", "USERNAME": "app", "PASSWORD": "s3cret"}}}
                """.formatted(name))
                .then().statusCode(200);

        JsonNode connection = awsActionJson(GLUE, "GetConnection", "{\"Name\": \"" + name + "\"}").path("Connection");
        JsonNode properties = connection.path("ConnectionProperties");
        assertEquals("app", properties.path("USERNAME").asText());
        assertEquals(false, properties.has("PASSWORD"));
        String encrypted = properties.path("ENCRYPTED_PASSWORD").asText();
        // The stored value is a real KMS ciphertext: KMS itself decrypts it back to the password.
        JsonNode decrypted = awsActionJson(KMS, "Decrypt", "{\"CiphertextBlob\": " + quoted(encrypted) + "}");
        assertEquals("s3cret", new String(Base64.getDecoder().decode(decrypted.path("Plaintext").asText()), StandardCharsets.UTF_8));

        awsAction(GLUE, "GetConnection", "{\"Name\": \"" + name + "\", \"HidePassword\": true}")
                .then()
                .statusCode(200)
                .body("Connection.ConnectionProperties", not(hasKey("ENCRYPTED_PASSWORD")))
                .body("Connection.ConnectionProperties", not(hasKey("PASSWORD")));

        awsAction(GLUE, "DeleteConnection", "{\"ConnectionName\": \"" + name + "\"}").then().statusCode(200);
        // Put the catalog back so other classes see the default.
        awsAction(GLUE, "PutDataCatalogEncryptionSettings", """
                {"DataCatalogEncryptionSettings": {
                   "EncryptionAtRest": {"CatalogEncryptionMode": "DISABLED"},
                   "ConnectionPasswordEncryption": {"ReturnConnectionPasswordEncrypted": false}
                }}
                """)
                .then().statusCode(200);
    }

    @Test
    @Order(5)
    void putDataCatalogEncryptionSettings_unknownMode_isInvalidInput() {
        awsAction(GLUE, "PutDataCatalogEncryptionSettings",
                "{\"DataCatalogEncryptionSettings\": {\"EncryptionAtRest\": {\"CatalogEncryptionMode\": \"AES\"}}}")
                .then()
                .statusCode(400)
                .body("__type", equalTo("InvalidInputException"));
    }

    private static String quoted(String value) {
        return "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"") + "\"";
    }
}
