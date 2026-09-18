package io.github.hectorvent.floci.services.glue;

import com.fasterxml.jackson.core.type.TypeReference;
import io.github.hectorvent.floci.core.storage.PersistentStorage;
import io.github.hectorvent.floci.services.glue.model.SecurityConfiguration;
import io.github.hectorvent.floci.testing.RestAssuredJsonUtils;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.QuarkusTestProfile;
import io.quarkus.test.junit.TestProfile;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.UUID;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.equalTo;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

@QuarkusTest
@TestProfile(GlueSecurityConfigurationIntegrationTest.PersistentStorageProfile.class)
class GlueSecurityConfigurationIntegrationTest {

    private static final String CONTENT_TYPE = "application/x-amz-json-1.1";
    private static final String STORAGE_DIR = "target/glue-security-configuration-it";
    private static final Path STORAGE_FILE = Path.of(STORAGE_DIR, "security_configurations.json");
    private static final String NAME = "security-" + UUID.randomUUID().toString().substring(0, 8);
    private static final String ENCRYPTION = """
            {"S3Encryption":[{"S3EncryptionMode":"SSE-KMS","KmsKeyArn":"arn:aws:kms:us-east-1:000000000000:key/test"}],
             "JobBookmarksEncryption":{"JobBookmarksEncryptionMode":"CSE-KMS"}}
            """;

    @BeforeAll
    static void setup() throws Exception {
        RestAssuredJsonUtils.configureAwsContentTypes();
        Files.deleteIfExists(STORAGE_FILE);
    }

    @Test
    void securityConfigurationCrudAndEncryptionRoundTrip() {
        create(NAME, "us-east-1", "000000000000");

        given().contentType(CONTENT_TYPE)
                .header("X-Amz-Target", "AWSGlue.GetSecurityConfiguration")
                .body("{\"Name\":\"" + NAME + "\"}")
        .when().post("/")
        .then().statusCode(200)
                .body("SecurityConfiguration.Name", equalTo(NAME))
                .body("SecurityConfiguration.EncryptionConfiguration.S3Encryption[0].S3EncryptionMode",
                        equalTo("SSE-KMS"))
                .body("SecurityConfiguration.EncryptionConfiguration.JobBookmarksEncryption.JobBookmarksEncryptionMode",
                        equalTo("CSE-KMS"));

        given().contentType(CONTENT_TYPE)
                .header("X-Amz-Target", "AWSGlue.GetSecurityConfigurations")
                .body("{}")
        .when().post("/")
        .then().statusCode(200)
                .body("SecurityConfigurations", hasSize(1))
                .body("SecurityConfigurations[0].Name", equalTo(NAME));

        given().contentType(CONTENT_TYPE)
                .header("X-Amz-Target", "AWSGlue.DeleteSecurityConfiguration")
                .body("{\"Name\":\"" + NAME + "\"}")
        .when().post("/")
        .then().statusCode(200);
    }

    @Test
    void duplicateAndMissingConfigurationsUseAwsErrors() {
        create(NAME, "us-east-1", "000000000000");

        given().contentType(CONTENT_TYPE)
                .header("X-Amz-Target", "AWSGlue.CreateSecurityConfiguration")
                .body("{\"Name\":\"" + NAME + "\",\"EncryptionConfiguration\":{} }")
        .when().post("/")
        .then().statusCode(400)
                .body("__type", containsString("AlreadyExistsException"));

        delete(NAME, "us-east-1", "000000000000");

        given().contentType(CONTENT_TYPE)
                .header("X-Amz-Target", "AWSGlue.GetSecurityConfiguration")
                .body("{\"Name\":\"" + NAME + "\"}")
        .when().post("/")
        .then().statusCode(400)
                .body("__type", containsString("EntityNotFoundException"));
    }

    @Test
    void accountAndRegionAreIsolatedAndConfigurationIsPersisted() throws Exception {
        create(NAME, "us-east-1", "000000000001");

        given().contentType(CONTENT_TYPE)
                .header("X-Amz-Target", "AWSGlue.GetSecurityConfiguration")
                .header("Authorization", authorization("000000000002", "us-east-1"))
                .body("{\"Name\":\"" + NAME + "\"}")
        .when().post("/")
        .then().statusCode(400)
                .body("__type", containsString("EntityNotFoundException"));

        given().contentType(CONTENT_TYPE)
                .header("X-Amz-Target", "AWSGlue.GetSecurityConfiguration")
                .header("Authorization", authorization("000000000001", "us-west-2"))
                .body("{\"Name\":\"" + NAME + "\"}")
        .when().post("/")
        .then().statusCode(400)
                .body("__type", containsString("EntityNotFoundException"));

        PersistentStorage<String, SecurityConfiguration> store = new PersistentStorage<>(
                STORAGE_FILE, new TypeReference<Map<String, SecurityConfiguration>>() {});
        store.load();
        assertTrue(store.get("000000000001/us-east-1:" + NAME).isPresent());
        assertEquals(NAME, store.get("000000000001/us-east-1:" + NAME).orElseThrow().getName());
    }

    private static void create(String name, String region, String account) {
        given().contentType(CONTENT_TYPE)
                .header("X-Amz-Target", "AWSGlue.CreateSecurityConfiguration")
                .header("Authorization", authorization(account, region))
                .body("{\"Name\":\"" + name + "\",\"EncryptionConfiguration\":" + ENCRYPTION + "}")
        .when().post("/")
        .then().statusCode(200)
                .body("Name", equalTo(name));
    }

    private static void delete(String name, String region, String account) {
        given().contentType(CONTENT_TYPE)
                .header("X-Amz-Target", "AWSGlue.DeleteSecurityConfiguration")
                .header("Authorization", authorization(account, region))
                .body("{\"Name\":\"" + name + "\"}")
        .when().post("/")
        .then().statusCode(200);
    }

    private static String authorization(String account, String region) {
        return "AWS4-HMAC-SHA256 Credential=" + account
                + "/20260918/" + region + "/glue/aws4_request, SignedHeaders=host, Signature=abc";
    }

    public static final class PersistentStorageProfile implements QuarkusTestProfile {
        @Override
        public Map<String, String> getConfigOverrides() {
            return Map.of(
                    "floci.storage.mode", "persistent",
                    "floci.storage.persistent-path", STORAGE_DIR);
        }
    }
}