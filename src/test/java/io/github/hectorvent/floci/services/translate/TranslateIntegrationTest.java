package io.github.hectorvent.floci.services.translate;
import io.github.hectorvent.floci.testing.RestAssuredJsonUtils;
import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.*;
/**
 * Integration tests for the Amazon Translate stub.
 * Validates AWS-compatible wire format using RestAssured.
 * Protocol: JSON 1.1 — Content-Type: application/x-amz-json-1.1,
 * X-Amz-Target: AWSShineFrontendService_20170701.<Action>
 */
@QuarkusTest
class TranslateIntegrationTest {
    @BeforeAll
    static void configureRestAssured() {
        RestAssuredJsonUtils.configureAwsContentTypes();
    }
    private static final String CONTENT_TYPE = "application/x-amz-json-1.1";
    private static final String AUTH_HEADER =
            "AWS4-HMAC-SHA256 Credential=AKID/20260101/us-east-1/translate/aws4_request";
    @Test
    void translateText_returnsStubTranslation() {
        given()
            .contentType(CONTENT_TYPE)
            .header("X-Amz-Target", "AWSShineFrontendService_20170701.TranslateText")
            .header("Authorization", AUTH_HEADER)
            .body("{\"Text\":\"Floci makes local AWS testing painless\",\"SourceLanguageCode\":\"en\",\"TargetLanguageCode\":\"es\"}")
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body("TranslatedText", notNullValue())
            .body("SourceLanguageCode", equalTo("en"))
            .body("TargetLanguageCode", equalTo("es"));
    }
    @Test
    void translateText_matchingMockConfig_returnsConfiguredResponse() {
        // src/test/resources/fixtures/ai-mock-config.json maps this exact Text to a Spanish translation.
        given()
            .contentType(CONTENT_TYPE)
            .header("X-Amz-Target", "AWSShineFrontendService_20170701.TranslateText")
            .header("Authorization", AUTH_HEADER)
            .body("{\"Text\":\"Hello, world!\",\"SourceLanguageCode\":\"en\",\"TargetLanguageCode\":\"es\"}")
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body("TranslatedText", equalTo("¡Hola, mundo!"));
    }
    @Test
    void translateText_autoSourceLanguage_resolvesToEn() {
        given()
            .contentType(CONTENT_TYPE)
            .header("X-Amz-Target", "AWSShineFrontendService_20170701.TranslateText")
            .header("Authorization", AUTH_HEADER)
            .body("{\"Text\":\"hello\",\"SourceLanguageCode\":\"auto\",\"TargetLanguageCode\":\"es\"}")
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body("SourceLanguageCode", equalTo("en"));
    }
    @Test
    void translateText_missingText_returns400() {
        given()
            .contentType(CONTENT_TYPE)
            .header("X-Amz-Target", "AWSShineFrontendService_20170701.TranslateText")
            .header("Authorization", AUTH_HEADER)
            .body("{\"SourceLanguageCode\":\"en\",\"TargetLanguageCode\":\"es\"}")
        .when()
            .post("/")
        .then()
            .statusCode(400)
            .body("__type", equalTo("InvalidRequestException"));
    }
    @Test
    void translateText_missingTargetLanguageCode_returns400() {
        given()
            .contentType(CONTENT_TYPE)
            .header("X-Amz-Target", "AWSShineFrontendService_20170701.TranslateText")
            .header("Authorization", AUTH_HEADER)
            .body("{\"Text\":\"hello\",\"SourceLanguageCode\":\"en\"}")
        .when()
            .post("/")
        .then()
            .statusCode(400)
            .body("__type", equalTo("InvalidRequestException"));
    }
    @Test
    void translateText_unsupportedLanguagePair_returns400() {
        given()
            .contentType(CONTENT_TYPE)
            .header("X-Amz-Target", "AWSShineFrontendService_20170701.TranslateText")
            .header("Authorization", AUTH_HEADER)
            .body("{\"Text\":\"hello\",\"SourceLanguageCode\":\"en\",\"TargetLanguageCode\":\"xx\"}")
        .when()
            .post("/")
        .then()
            .statusCode(400)
            .body("__type", equalTo("UnsupportedLanguagePairException"));
    }
    @Test
    void translateText_nonStringText_returnsSerializationException() {
        given()
            .contentType(CONTENT_TYPE)
            .header("X-Amz-Target", "AWSShineFrontendService_20170701.TranslateText")
            .header("Authorization", AUTH_HEADER)
            .body("{\"Text\":12345,\"SourceLanguageCode\":\"en\",\"TargetLanguageCode\":\"es\"}")
        .when()
            .post("/")
        .then()
            .statusCode(400)
            .body("__type", equalTo("SerializationException"));
    }
    @Test
    void translateDocument_echoesContentUnchanged() {
        given()
            .contentType(CONTENT_TYPE)
            .header("X-Amz-Target", "AWSShineFrontendService_20170701.TranslateDocument")
            .header("Authorization", AUTH_HEADER)
            .body("{\"Document\":{\"Content\":\"aGVsbG8=\",\"ContentType\":\"text/plain\"},"
                    + "\"SourceLanguageCode\":\"en\",\"TargetLanguageCode\":\"es\"}")
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body("TranslatedDocument.Content", equalTo("aGVsbG8="))
            .body("SourceLanguageCode", equalTo("en"))
            .body("TargetLanguageCode", equalTo("es"));
    }
    @Test
    void translateDocument_missingContent_returns400() {
        given()
            .contentType(CONTENT_TYPE)
            .header("X-Amz-Target", "AWSShineFrontendService_20170701.TranslateDocument")
            .header("Authorization", AUTH_HEADER)
            .body("{\"Document\":{\"ContentType\":\"text/plain\"},"
                    + "\"SourceLanguageCode\":\"en\",\"TargetLanguageCode\":\"es\"}")
        .when()
            .post("/")
        .then()
            .statusCode(400)
            .body("__type", equalTo("InvalidRequestException"));
    }
    @Test
    void translateDocument_missingDocument_returns400() {
        given()
            .contentType(CONTENT_TYPE)
            .header("X-Amz-Target", "AWSShineFrontendService_20170701.TranslateDocument")
            .header("Authorization", AUTH_HEADER)
            .body("{\"SourceLanguageCode\":\"en\",\"TargetLanguageCode\":\"es\"}")
        .when()
            .post("/")
        .then()
            .statusCode(400)
            .body("__type", equalTo("InvalidRequestException"));
    }
    @Test
    void translateDocument_nonObjectDocument_returnsSerializationException() {
        given()
            .contentType(CONTENT_TYPE)
            .header("X-Amz-Target", "AWSShineFrontendService_20170701.TranslateDocument")
            .header("Authorization", AUTH_HEADER)
            .body("{\"Document\":\"not-an-object\",\"SourceLanguageCode\":\"en\",\"TargetLanguageCode\":\"es\"}")
        .when()
            .post("/")
        .then()
            .statusCode(400)
            .body("__type", equalTo("SerializationException"));
    }
    @Test
    void listLanguages_returnsFixedCatalog() {
        given()
            .contentType(CONTENT_TYPE)
            .header("X-Amz-Target", "AWSShineFrontendService_20170701.ListLanguages")
            .header("Authorization", AUTH_HEADER)
            .body("{}")
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body("Languages.size()", greaterThan(0))
            .body("Languages.LanguageCode", hasItem("en"))
            .body("DisplayLanguageCode", equalTo("en"));
    }
    @Test
    void unknownAction_returnsUnknownOperationError() {
        given()
            .contentType(CONTENT_TYPE)
            .header("X-Amz-Target", "AWSShineFrontendService_20170701.StartTextTranslationJob")
            .header("Authorization", AUTH_HEADER)
            .body("{}")
        .when()
            .post("/")
        .then()
            .statusCode(400)
            .body("__type", equalTo("UnknownOperationException"));
    }
}
