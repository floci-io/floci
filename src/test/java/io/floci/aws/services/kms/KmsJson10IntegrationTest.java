package io.floci.aws.services.kms;

import io.floci.aws.testing.RestAssuredJsonUtils;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.path.json.JsonPath;
import io.restassured.response.ValidatableResponse;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.Base64;

import static io.floci.aws.core.common.AwsJson11Controller.CONTENT_TYPE_AWS_JSON_1_1;
import static io.floci.aws.core.common.AwsJsonController.CONTENT_TYPE_AWS_JSON_1_0;
import static io.floci.aws.testing.RestAssuredJsonUtils.awsAction;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.notNullValue;

@QuarkusTest
class KmsJson10IntegrationTest {

    @BeforeAll
    static void configureRestAssured() {
        RestAssuredJsonUtils.configureAwsContentTypes();
    }

    @Test
    void generateDataKeyAndDecryptOverJson10() {
        String keyId = call(CONTENT_TYPE_AWS_JSON_1_0, "CreateKey", "{}")
                .statusCode(200)
                .body("KeyMetadata.KeyId", notNullValue())
                .extract().path("KeyMetadata.KeyId");

        String context = "{\"record_id\":\"42\"}";
        JsonPath dataKey = call(CONTENT_TYPE_AWS_JSON_1_0, "GenerateDataKey", """
                {"KeyId":"%s","KeySpec":"AES_256","EncryptionContext":%s}
                """.formatted(keyId, context))
                .statusCode(200)
                .extract().jsonPath();
        String plaintext = dataKey.getString("Plaintext");
        String blob = dataKey.getString("CiphertextBlob");

        call(CONTENT_TYPE_AWS_JSON_1_0, "Decrypt", """
                {"CiphertextBlob":"%s","EncryptionContext":%s}
                """.formatted(blob, context))
                .statusCode(200)
                .body("Plaintext", equalTo(plaintext))
                .body("KeyId", notNullValue());

        call(CONTENT_TYPE_AWS_JSON_1_1, "Decrypt", """
                {"CiphertextBlob":"%s","EncryptionContext":%s}
                """.formatted(blob, context))
                .statusCode(200)
                .body("Plaintext", equalTo(plaintext));
    }

    @Test
    void encryptOverJson11DecryptsOverJson10() {
        String keyId = call(CONTENT_TYPE_AWS_JSON_1_1, "CreateKey", "{}")
                .statusCode(200)
                .extract().path("KeyMetadata.KeyId");
        String message = Base64.getEncoder().encodeToString("hello".getBytes(StandardCharsets.UTF_8));

        String blob = call(CONTENT_TYPE_AWS_JSON_1_1, "Encrypt", """
                {"KeyId":"%s","Plaintext":"%s"}
                """.formatted(keyId, message))
                .statusCode(200)
                .extract().path("CiphertextBlob");

        call(CONTENT_TYPE_AWS_JSON_1_0, "Decrypt", "{\"CiphertextBlob\":\"" + blob + "\"}")
                .statusCode(200)
                .body("Plaintext", equalTo(message));
    }

    @Test
    void json10KeepsKmsErrorShapes() {
        String keyId = call(CONTENT_TYPE_AWS_JSON_1_0, "CreateKey", "{}")
                .statusCode(200)
                .extract().path("KeyMetadata.KeyId");
        String blob = call(CONTENT_TYPE_AWS_JSON_1_0, "GenerateDataKey", """
                {"KeyId":"%s","KeySpec":"AES_256","EncryptionContext":{"record_id":"1"}}
                """.formatted(keyId))
                .statusCode(200)
                .extract().path("CiphertextBlob");

        call(CONTENT_TYPE_AWS_JSON_1_0, "Decrypt", """
                {"CiphertextBlob":"%s","EncryptionContext":{"record_id":"2"}}
                """.formatted(blob))
                .statusCode(400)
                .body("__type", equalTo("InvalidCiphertextException"));

        call(CONTENT_TYPE_AWS_JSON_1_0, "GenerateDataKey", """
                {"KeyId":"alias/does-not-exist","KeySpec":"AES_256"}
                """)
                .statusCode(400)
                .body("__type", equalTo("NotFoundException"));
    }

    private static ValidatableResponse call(String contentType, String action, String body) {
        return awsAction("TrentService", action, body, contentType).then();
    }
}
