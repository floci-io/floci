package io.github.hectorvent.floci.services.comprehend;
import io.github.hectorvent.floci.testing.RestAssuredJsonUtils;
import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.*;
/**
 * Integration tests for the Amazon Comprehend stub.
 * Validates AWS-compatible wire format using RestAssured.
 * Protocol: JSON 1.1 — Content-Type: application/x-amz-json-1.1, X-Amz-Target: Comprehend_20171127.<Action>
 */
@QuarkusTest
class ComprehendIntegrationTest {
    @BeforeAll
    static void configureRestAssured() {
        RestAssuredJsonUtils.configureAwsContentTypes();
    }
    private static final String CONTENT_TYPE = "application/x-amz-json-1.1";
    private static final String AUTH_HEADER =
            "AWS4-HMAC-SHA256 Credential=AKID/20260101/us-east-1/comprehend/aws4_request";
    @Test
    void detectSentiment_returnsNeutralWithScores() {
        given()
            .contentType(CONTENT_TYPE)
            .header("X-Amz-Target", "Comprehend_20171127.DetectSentiment")
            .header("Authorization", AUTH_HEADER)
            .body("{\"Text\":\"Floci makes local AWS testing painless\",\"LanguageCode\":\"en\"}")
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body("Sentiment", equalTo("NEUTRAL"))
            .body("SentimentScore.Positive", notNullValue())
            .body("SentimentScore.Negative", notNullValue())
            .body("SentimentScore.Neutral", notNullValue())
            .body("SentimentScore.Mixed", notNullValue());
    }
    @Test
    void detectSentiment_missingText_returns400() {
        given()
            .contentType(CONTENT_TYPE)
            .header("X-Amz-Target", "Comprehend_20171127.DetectSentiment")
            .header("Authorization", AUTH_HEADER)
            .body("{\"LanguageCode\":\"en\"}")
        .when()
            .post("/")
        .then()
            .statusCode(400)
            .body("__type", equalTo("InvalidRequestException"));
    }
    @Test
    void detectSentiment_unsupportedLanguage_returns400() {
        given()
            .contentType(CONTENT_TYPE)
            .header("X-Amz-Target", "Comprehend_20171127.DetectSentiment")
            .header("Authorization", AUTH_HEADER)
            .body("{\"Text\":\"hello\",\"LanguageCode\":\"xx\"}")
        .when()
            .post("/")
        .then()
            .statusCode(400)
            .body("__type", equalTo("UnsupportedLanguageException"));
    }
    @Test
    void detectSentiment_nonStringText_returnsSerializationException() {
        given()
            .contentType(CONTENT_TYPE)
            .header("X-Amz-Target", "Comprehend_20171127.DetectSentiment")
            .header("Authorization", AUTH_HEADER)
            .body("{\"Text\":12345,\"LanguageCode\":\"en\"}")
        .when()
            .post("/")
        .then()
            .statusCode(400)
            .body("__type", equalTo("SerializationException"));
    }
    @Test
    void detectSentiment_nonStringLanguageCode_returnsSerializationException() {
        given()
            .contentType(CONTENT_TYPE)
            .header("X-Amz-Target", "Comprehend_20171127.DetectSentiment")
            .header("Authorization", AUTH_HEADER)
            .body("{\"Text\":\"hello\",\"LanguageCode\":true}")
        .when()
            .post("/")
        .then()
            .statusCode(400)
            .body("__type", equalTo("SerializationException"));
    }
    @Test
    void detectKeyPhrases_returnsStubPhrase() {
        given()
            .contentType(CONTENT_TYPE)
            .header("X-Amz-Target", "Comprehend_20171127.DetectKeyPhrases")
            .header("Authorization", AUTH_HEADER)
            .body("{\"Text\":\"Floci makes local AWS testing painless\",\"LanguageCode\":\"en\"}")
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body("KeyPhrases", hasSize(1))
            .body("KeyPhrases[0].Text", equalTo("Floci"))
            .body("KeyPhrases[0].Score", notNullValue())
            .body("KeyPhrases[0].BeginOffset", notNullValue())
            .body("KeyPhrases[0].EndOffset", notNullValue());
    }
    @Test
    void detectDominantLanguage_doesNotRequireLanguageCode() {
        given()
            .contentType(CONTENT_TYPE)
            .header("X-Amz-Target", "Comprehend_20171127.DetectDominantLanguage")
            .header("Authorization", AUTH_HEADER)
            .body("{\"Text\":\"Floci makes local AWS testing painless\"}")
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body("Languages", hasSize(1))
            .body("Languages[0].LanguageCode", equalTo("en"))
            .body("Languages[0].Score", notNullValue());
    }
    @Test
    void detectDominantLanguage_missingText_returns400() {
        given()
            .contentType(CONTENT_TYPE)
            .header("X-Amz-Target", "Comprehend_20171127.DetectDominantLanguage")
            .header("Authorization", AUTH_HEADER)
            .body("{}")
        .when()
            .post("/")
        .then()
            .statusCode(400)
            .body("__type", equalTo("InvalidRequestException"));
    }
    @Test
    void detectPiiEntities_returnsEmptyEntities() {
        given()
            .contentType(CONTENT_TYPE)
            .header("X-Amz-Target", "Comprehend_20171127.DetectPiiEntities")
            .header("Authorization", AUTH_HEADER)
            .body("{\"Text\":\"My SSN is 123-45-6789\",\"LanguageCode\":\"en\"}")
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body("Entities", hasSize(0));
    }
    @Test
    void detectPiiEntities_rejectsLanguageOutsideEnEs() {
        // DetectPiiEntities/ContainsPiiEntities support only en/es, unlike the general
        // 12-language set accepted by DetectSentiment/DetectKeyPhrases.
        given()
            .contentType(CONTENT_TYPE)
            .header("X-Amz-Target", "Comprehend_20171127.DetectPiiEntities")
            .header("Authorization", AUTH_HEADER)
            .body("{\"Text\":\"My SSN is 123-45-6789\",\"LanguageCode\":\"fr\"}")
        .when()
            .post("/")
        .then()
            .statusCode(400)
            .body("__type", equalTo("UnsupportedLanguageException"));
    }
    @Test
    void containsPiiEntities_rejectsLanguageOutsideEnEs() {
        given()
            .contentType(CONTENT_TYPE)
            .header("X-Amz-Target", "Comprehend_20171127.ContainsPiiEntities")
            .header("Authorization", AUTH_HEADER)
            .body("{\"Text\":\"My SSN is 123-45-6789\",\"LanguageCode\":\"fr\"}")
        .when()
            .post("/")
        .then()
            .statusCode(400)
            .body("__type", equalTo("UnsupportedLanguageException"));
    }
    @Test
    void containsPiiEntities_returnsEmptyLabels() {
        given()
            .contentType(CONTENT_TYPE)
            .header("X-Amz-Target", "Comprehend_20171127.ContainsPiiEntities")
            .header("Authorization", AUTH_HEADER)
            .body("{\"Text\":\"My SSN is 123-45-6789\",\"LanguageCode\":\"en\"}")
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body("Labels", hasSize(0));
    }
    @Test
    void unknownAction_returnsUnknownOperationError() {
        given()
            .contentType(CONTENT_TYPE)
            .header("X-Amz-Target", "Comprehend_20171127.DetectEntities")
            .header("Authorization", AUTH_HEADER)
            .body("{}")
        .when()
            .post("/")
        .then()
            .statusCode(400)
            .body("__type", equalTo("UnknownOperationException"));
    }
}
