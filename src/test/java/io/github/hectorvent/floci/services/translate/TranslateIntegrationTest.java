package io.github.hectorvent.floci.services.translate;
import io.github.hectorvent.floci.testing.RestAssuredJsonUtils;
import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import java.util.Base64;
import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.*;
/**
 * Integration tests for the Amazon Translate stub.
 * Validates AWS-compatible wire format using RestAssured.
 * Protocol: JSON 1.1, Content-Type: application/x-amz-json-1.1,
 * X-Amz-Target: AWSShineFrontendService_20170701.&lt;Action&gt;
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
    private static final String TARGET_TEXT = "AWSShineFrontendService_20170701.TranslateText";
    private static final String TARGET_DOCUMENT = "AWSShineFrontendService_20170701.TranslateDocument";
    private static final String TARGET_LIST = "AWSShineFrontendService_20170701.ListLanguages";
    private static String base64(String plain) {
        return Base64.getEncoder().encodeToString(plain.getBytes());
    }
    // --- TranslateText ---
    @Test
    void translateText_echoesInputAsTranslatedText() {
        given()
            .contentType(CONTENT_TYPE)
            .header("X-Amz-Target", TARGET_TEXT)
            .header("Authorization", AUTH_HEADER)
            .body("{\"Text\":\"Floci makes local AWS testing painless\","
                    + "\"SourceLanguageCode\":\"en\",\"TargetLanguageCode\":\"fr\"}")
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body("TranslatedText", equalTo("Floci makes local AWS testing painless"))
            .body("SourceLanguageCode", equalTo("en"))
            .body("TargetLanguageCode", equalTo("fr"));
    }
    @Test
    void translateText_anyToAnyPairIsAllowed() {
        // Unlike TranslateDocument, TranslateText does not require an English pivot.
        given()
            .contentType(CONTENT_TYPE)
            .header("X-Amz-Target", TARGET_TEXT)
            .header("Authorization", AUTH_HEADER)
            .body("{\"Text\":\"bonjour\",\"SourceLanguageCode\":\"fr\",\"TargetLanguageCode\":\"de\"}")
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body("TranslatedText", equalTo("bonjour"));
    }
    @Test
    void translateText_autoSourceIsReportedAsEn() {
        given()
            .contentType(CONTENT_TYPE)
            .header("X-Amz-Target", TARGET_TEXT)
            .header("Authorization", AUTH_HEADER)
            .body("{\"Text\":\"hello\",\"SourceLanguageCode\":\"auto\",\"TargetLanguageCode\":\"de\"}")
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body("SourceLanguageCode", equalTo("en"))
            .body("TargetLanguageCode", equalTo("de"));
    }
    @Test
    void translateText_matchingMockConfig_returnsConfiguredResponse() {
        // src/test/resources/fixtures/ai-mock-config.json maps "Hello world" to "Hola mundo".
        given()
            .contentType(CONTENT_TYPE)
            .header("X-Amz-Target", TARGET_TEXT)
            .header("Authorization", AUTH_HEADER)
            .body("{\"Text\":\"Hello world\",\"SourceLanguageCode\":\"en\",\"TargetLanguageCode\":\"es\"}")
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body("TranslatedText", equalTo("Hola mundo"));
    }
    @Test
    void translateText_missingText_returns400() {
        given()
            .contentType(CONTENT_TYPE)
            .header("X-Amz-Target", TARGET_TEXT)
            .header("Authorization", AUTH_HEADER)
            .body("{\"SourceLanguageCode\":\"en\",\"TargetLanguageCode\":\"fr\"}")
        .when()
            .post("/")
        .then()
            .statusCode(400)
            .body("__type", equalTo("InvalidRequestException"));
    }
    @Test
    void translateText_oversizedText_returns400() {
        given()
            .contentType(CONTENT_TYPE)
            .header("X-Amz-Target", TARGET_TEXT)
            .header("Authorization", AUTH_HEADER)
            .body("{\"Text\":\"" + "a".repeat(10_001) + "\","
                    + "\"SourceLanguageCode\":\"en\",\"TargetLanguageCode\":\"fr\"}")
        .when()
            .post("/")
        .then()
            .statusCode(400)
            .body("__type", equalTo("TextSizeLimitExceededException"));
    }
    @Test
    void translateText_missingSourceLanguage_returns400() {
        given()
            .contentType(CONTENT_TYPE)
            .header("X-Amz-Target", TARGET_TEXT)
            .header("Authorization", AUTH_HEADER)
            .body("{\"Text\":\"hello\",\"TargetLanguageCode\":\"fr\"}")
        .when()
            .post("/")
        .then()
            .statusCode(400)
            .body("__type", equalTo("InvalidRequestException"));
    }
    @Test
    void translateText_unknownSourceLanguage_returns400() {
        given()
            .contentType(CONTENT_TYPE)
            .header("X-Amz-Target", TARGET_TEXT)
            .header("Authorization", AUTH_HEADER)
            .body("{\"Text\":\"hello\",\"SourceLanguageCode\":\"zz\",\"TargetLanguageCode\":\"fr\"}")
        .when()
            .post("/")
        .then()
            .statusCode(400)
            .body("__type", equalTo("UnsupportedLanguagePairException"));
    }
    @Test
    void translateText_unknownTargetLanguage_returns400() {
        given()
            .contentType(CONTENT_TYPE)
            .header("X-Amz-Target", TARGET_TEXT)
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
            .header("X-Amz-Target", TARGET_TEXT)
            .header("Authorization", AUTH_HEADER)
            .body("{\"Text\":123,\"SourceLanguageCode\":\"en\",\"TargetLanguageCode\":\"fr\"}")
        .when()
            .post("/")
        .then()
            .statusCode(400)
            .body("__type", equalTo("SerializationException"));
    }
    @Test
    void translateText_nonStringTargetLanguage_returnsSerializationException() {
        given()
            .contentType(CONTENT_TYPE)
            .header("X-Amz-Target", TARGET_TEXT)
            .header("Authorization", AUTH_HEADER)
            .body("{\"Text\":\"hello\",\"SourceLanguageCode\":\"en\",\"TargetLanguageCode\":true}")
        .when()
            .post("/")
        .then()
            .statusCode(400)
            .body("__type", equalTo("SerializationException"));
    }
    // --- TranslateDocument ---
    @Test
    void translateDocument_echoesContentUnchanged() {
        String content = base64("<p>hello</p>");
        given()
            .contentType(CONTENT_TYPE)
            .header("X-Amz-Target", TARGET_DOCUMENT)
            .header("Authorization", AUTH_HEADER)
            .body("{\"Document\":{\"Content\":\"" + content + "\",\"ContentType\":\"text/html\"},"
                    + "\"SourceLanguageCode\":\"en\",\"TargetLanguageCode\":\"fr\"}")
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body("TranslatedDocument.Content", equalTo(content))
            .body("SourceLanguageCode", equalTo("en"))
            .body("TargetLanguageCode", equalTo("fr"));
    }
    @Test
    void translateDocument_autoSourceWithEnglishTarget_isReportedAsEn() {
        String content = base64("bonjour");
        given()
            .contentType(CONTENT_TYPE)
            .header("X-Amz-Target", TARGET_DOCUMENT)
            .header("Authorization", AUTH_HEADER)
            .body("{\"Document\":{\"Content\":\"" + content + "\",\"ContentType\":\"text/plain\"},"
                    + "\"SourceLanguageCode\":\"auto\",\"TargetLanguageCode\":\"en\"}")
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body("SourceLanguageCode", equalTo("en"));
    }
    @Test
    void translateDocument_nonEnglishPair_returns400() {
        String content = base64("hola");
        given()
            .contentType(CONTENT_TYPE)
            .header("X-Amz-Target", TARGET_DOCUMENT)
            .header("Authorization", AUTH_HEADER)
            .body("{\"Document\":{\"Content\":\"" + content + "\",\"ContentType\":\"text/plain\"},"
                    + "\"SourceLanguageCode\":\"es\",\"TargetLanguageCode\":\"fr\"}")
        .when()
            .post("/")
        .then()
            .statusCode(400)
            .body("__type", equalTo("UnsupportedLanguagePairException"));
    }
    @Test
    void translateDocument_missingContentType_returns400() {
        String content = base64("hello");
        given()
            .contentType(CONTENT_TYPE)
            .header("X-Amz-Target", TARGET_DOCUMENT)
            .header("Authorization", AUTH_HEADER)
            .body("{\"Document\":{\"Content\":\"" + content + "\"},"
                    + "\"SourceLanguageCode\":\"en\",\"TargetLanguageCode\":\"fr\"}")
        .when()
            .post("/")
        .then()
            .statusCode(400)
            .body("__type", equalTo("InvalidRequestException"));
    }
    @Test
    void translateDocument_unsupportedContentType_returns400() {
        String content = base64("hello");
        given()
            .contentType(CONTENT_TYPE)
            .header("X-Amz-Target", TARGET_DOCUMENT)
            .header("Authorization", AUTH_HEADER)
            .body("{\"Document\":{\"Content\":\"" + content + "\",\"ContentType\":\"application/pdf\"},"
                    + "\"SourceLanguageCode\":\"en\",\"TargetLanguageCode\":\"fr\"}")
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
            .header("X-Amz-Target", TARGET_DOCUMENT)
            .header("Authorization", AUTH_HEADER)
            .body("{\"SourceLanguageCode\":\"en\",\"TargetLanguageCode\":\"fr\"}")
        .when()
            .post("/")
        .then()
            .statusCode(400)
            .body("__type", equalTo("InvalidRequestException"));
    }
    @Test
    void translateDocument_documentNotObject_returnsSerializationException() {
        given()
            .contentType(CONTENT_TYPE)
            .header("X-Amz-Target", TARGET_DOCUMENT)
            .header("Authorization", AUTH_HEADER)
            .body("{\"Document\":\"not-a-structure\","
                    + "\"SourceLanguageCode\":\"en\",\"TargetLanguageCode\":\"fr\"}")
        .when()
            .post("/")
        .then()
            .statusCode(400)
            .body("__type", equalTo("SerializationException"));
    }
    @Test
    void translateDocument_invalidBase64Content_returns400() {
        given()
            .contentType(CONTENT_TYPE)
            .header("X-Amz-Target", TARGET_DOCUMENT)
            .header("Authorization", AUTH_HEADER)
            .body("{\"Document\":{\"Content\":\"@@@not-base64@@@\",\"ContentType\":\"text/plain\"},"
                    + "\"SourceLanguageCode\":\"en\",\"TargetLanguageCode\":\"fr\"}")
        .when()
            .post("/")
        .then()
            .statusCode(400)
            .body("__type", equalTo("InvalidRequestException"));
    }
    @Test
    void translateDocument_oversizedContent_returns400() {
        String content = Base64.getEncoder().encodeToString(new byte[100 * 1024 + 1]);
        given()
            .contentType(CONTENT_TYPE)
            .header("X-Amz-Target", TARGET_DOCUMENT)
            .header("Authorization", AUTH_HEADER)
            .body("{\"Document\":{\"Content\":\"" + content + "\",\"ContentType\":\"text/plain\"},"
                    + "\"SourceLanguageCode\":\"en\",\"TargetLanguageCode\":\"fr\"}")
        .when()
            .post("/")
        .then()
            .statusCode(400)
            // TranslateDocument does not model TextSizeLimitExceededException (that is TranslateText only).
            .body("__type", equalTo("InvalidRequestException"));
    }
    // --- ListLanguages ---
    @Test
    void listLanguages_returnsCatalogWithDefaultDisplayCode() {
        given()
            .contentType(CONTENT_TYPE)
            .header("X-Amz-Target", TARGET_LIST)
            .header("Authorization", AUTH_HEADER)
            .body("{}")
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body("DisplayLanguageCode", equalTo("en"))
            .body("Languages.size()", greaterThan(20))
            .body("Languages.LanguageCode", hasItems("en", "es", "fr", "vi"))
            .body("Languages.find { it.LanguageCode == 'en' }.LanguageName", equalTo("English"))
            .body("NextToken", nullValue());
    }
    @Test
    void listLanguages_explicitValidDisplayCode_isEchoed() {
        given()
            .contentType(CONTENT_TYPE)
            .header("X-Amz-Target", TARGET_LIST)
            .header("Authorization", AUTH_HEADER)
            .body("{\"DisplayLanguageCode\":\"ja\",\"MaxResults\":5}")
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body("DisplayLanguageCode", equalTo("ja"))
            .body("Languages.find { it.LanguageCode == 'en' }.LanguageName", equalTo("English"));
    }
    @Test
    void listLanguages_unsupportedDisplayCode_returns400() {
        given()
            .contentType(CONTENT_TYPE)
            .header("X-Amz-Target", TARGET_LIST)
            .header("Authorization", AUTH_HEADER)
            .body("{\"DisplayLanguageCode\":\"xx\"}")
        .when()
            .post("/")
        .then()
            .statusCode(400)
            .body("__type", equalTo("UnsupportedDisplayLanguageCodeException"));
    }
    @Test
    void unknownAction_returnsUnknownOperationError() {
        given()
            .contentType(CONTENT_TYPE)
            .header("X-Amz-Target", "AWSShineFrontendService_20170701.ImportTerminology")
            .header("Authorization", AUTH_HEADER)
            .body("{}")
        .when()
            .post("/")
        .then()
            .statusCode(400)
            .body("__type", equalTo("UnknownOperationException"));
    }
}
