package io.github.hectorvent.floci.services.ssm;

import io.github.hectorvent.floci.testing.AwsTestClients;
import io.github.hectorvent.floci.testing.RestAssuredJsonUtils;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.path.json.JsonPath;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.ssm.SsmClient;
import software.amazon.awssdk.services.ssm.model.Parameter;
import software.amazon.awssdk.services.ssm.model.ParameterHistory;
import software.amazon.awssdk.services.ssm.model.ParameterType;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.List;
import java.util.UUID;

import static io.restassured.RestAssured.given;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

@QuarkusTest
class SsmSecureStringCiphertextIntegrationTest {

    private static final String CONTENT_TYPE = "application/x-amz-json-1.1";

    private SsmClient ssm;

    @BeforeAll
    static void configureContentTypes() {
        RestAssuredJsonUtils.configureAwsContentTypes();
    }

    @BeforeEach
    void createClient() {
        ssm = AwsTestClients.create(SsmClient.builder(), "test", Region.US_EAST_1);
    }

    @AfterEach
    void closeClient() {
        ssm.close();
    }

    @Test
    void getParameterWithoutDecryptionReturnsTheValueEncryptedUnderTheAwsManagedKey() {
        String name = "/ciphertext/" + UUID.randomUUID();
        putSecure(name, "s3cr3t", null);

        Parameter parameter = ssm.getParameter(r -> r.name(name)).parameter();

        assertNotEquals("s3cr3t", parameter.value());
        JsonPath decrypted = decrypt(parameter.value(), parameter.arn());
        assertEquals("s3cr3t", plaintextOf(decrypted));
        assertEquals(keyArnOf("alias/aws/ssm"), decrypted.getString("KeyId"));
    }

    @Test
    void getParameterWithoutDecryptionReturnsTheValueEncryptedUnderTheParametersOwnKey() {
        String keyId = createKey();
        String name = "/ciphertext/" + UUID.randomUUID();
        putSecure(name, "own-key", keyId);

        Parameter parameter = ssm.getParameter(r -> r.name(name)).parameter();

        JsonPath decrypted = decrypt(parameter.value(), parameter.arn());
        assertEquals("own-key", plaintextOf(decrypted));
        assertEquals(keyArnOf(keyId), decrypted.getString("KeyId"));
    }

    @Test
    void everyReadOfOneVersionReturnsTheSameCiphertext() {
        String name = "/ciphertext/" + UUID.randomUUID();
        putSecure(name, "stable", null);

        Parameter first = ssm.getParameter(r -> r.name(name)).parameter();
        String second = ssm.getParameter(r -> r.name(name)).parameter().value();

        assertEquals("stable", plaintextOf(decrypt(first.value(), first.arn())));
        assertEquals(first.value(), second);
    }

    @Test
    void aVersionSelectorReturnsThatVersionsCiphertext() {
        String name = "/ciphertext/" + UUID.randomUUID();
        putSecure(name, "first", null);
        ssm.putParameter(r -> r.name(name).value("second").type(ParameterType.SECURE_STRING).overwrite(true));

        Parameter parameter = ssm.getParameter(r -> r.name(name + ":1")).parameter();

        assertEquals("first", plaintextOf(decrypt(parameter.value(), parameter.arn())));
    }

    @Test
    void getParameterWithDecryptionReturnsThePlaintext() {
        String name = "/ciphertext/" + UUID.randomUUID();
        putSecure(name, "s3cr3t", null);

        assertEquals("s3cr3t", ssm.getParameter(r -> r.name(name).withDecryption(true)).parameter().value());
    }

    @Test
    void getParametersWithoutDecryptionReturnsCiphertext() {
        String name = "/ciphertext/" + UUID.randomUUID();
        putSecure(name, "batch", null);

        Parameter parameter = ssm.getParameters(r -> r.names(name)).parameters().getFirst();

        assertEquals("batch", plaintextOf(decrypt(parameter.value(), parameter.arn())));
    }

    @Test
    void getParametersByPathWithoutDecryptionReturnsCiphertextAndWithDecryptionThePlaintext() {
        String path = "/ciphertext/" + UUID.randomUUID();
        putSecure(path + "/secret", "by-path", null);

        Parameter encrypted = ssm.getParametersByPath(r -> r.path(path)).parameters().getFirst();
        Parameter decrypted = ssm.getParametersByPath(r -> r.path(path).withDecryption(true)).parameters().getFirst();

        assertEquals("by-path", plaintextOf(decrypt(encrypted.value(), encrypted.arn())));
        assertEquals("by-path", decrypted.value());
    }

    @Test
    void getParameterHistoryWithoutDecryptionReturnsEachVersionEncryptedUnderItsOwnKey() {
        String keyId = createKey();
        String name = "/ciphertext/" + UUID.randomUUID();
        putSecure(name, "first", keyId);
        ssm.putParameter(r -> r.name(name).value("second").type(ParameterType.SECURE_STRING).overwrite(true));
        String arn = ssm.getParameter(r -> r.name(name)).parameter().arn();

        List<ParameterHistory> encrypted = ssm.getParameterHistory(r -> r.name(name)).parameters();
        List<ParameterHistory> decrypted = ssm.getParameterHistory(r -> r.name(name).withDecryption(true)).parameters();

        JsonPath first = decrypt(encrypted.get(0).value(), arn);
        JsonPath second = decrypt(encrypted.get(1).value(), arn);
        assertEquals("first", plaintextOf(first));
        assertEquals(keyArnOf(keyId), first.getString("KeyId"));
        assertEquals("second", plaintextOf(second));
        assertEquals(keyArnOf("alias/aws/ssm"), second.getString("KeyId"));
        assertEquals(List.of("first", "second"), decrypted.stream().map(ParameterHistory::value).toList());
    }

    @Test
    void aStringParameterIsReturnedAsStoredWithoutDecryption() {
        String name = "/ciphertext/" + UUID.randomUUID();
        ssm.putParameter(r -> r.name(name).value("plain").type(ParameterType.STRING));

        assertEquals("plain", ssm.getParameter(r -> r.name(name)).parameter().value());
    }

    private void putSecure(String name, String value, String keyId) {
        ssm.putParameter(r -> r.name(name).value(value).type(ParameterType.SECURE_STRING).keyId(keyId));
    }

    private static String createKey() {
        return kms("CreateKey", "{}").getString("KeyMetadata.KeyId");
    }

    private static String keyArnOf(String keyId) {
        return kms("DescribeKey", "{\"KeyId\":\"" + keyId + "\"}").getString("KeyMetadata.Arn");
    }

    private static JsonPath decrypt(String ciphertext, String parameterArn) {
        return kms("Decrypt", """
                {"CiphertextBlob": "%s", "EncryptionContext": {"PARAMETER_ARN": "%s"}}
                """.formatted(ciphertext, parameterArn));
    }

    private static String plaintextOf(JsonPath decrypted) {
        return new String(Base64.getDecoder().decode(decrypted.getString("Plaintext")), StandardCharsets.UTF_8);
    }

    private static JsonPath kms(String operation, String body) {
        return given()
                .header("X-Amz-Target", "TrentService." + operation)
                .contentType(CONTENT_TYPE)
                .body(body)
                .when().post("/")
                .then().statusCode(200)
                .extract().jsonPath();
    }
}
