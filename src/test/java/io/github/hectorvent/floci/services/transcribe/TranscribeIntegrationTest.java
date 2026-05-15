package io.github.hectorvent.floci.services.transcribe;

import io.github.hectorvent.floci.testing.RestAssuredJsonUtils;
import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.*;

/**
 * Integration tests for the Amazon Transcribe stub.
 * Protocol: JSON 1.1 — Content-Type: application/x-amz-json-1.1, X-Amz-Target: Transcribe.&lt;Action&gt;
 */
@QuarkusTest
class TranscribeIntegrationTest {

    @BeforeAll
    static void configureRestAssured() {
        RestAssuredJsonUtils.configureAwsContentTypes();
    }

    private static final String CONTENT_TYPE = "application/x-amz-json-1.1";
    private static final String AUTH_HEADER =
            "AWS4-HMAC-SHA256 Credential=AKID/20260101/us-east-1/transcribe/aws4_request";

    // ── StartTranscriptionJob ────────────────────────────────────────────────

    @Test
    void startTranscriptionJob_returnsCompletedJob() {
        given()
            .contentType(CONTENT_TYPE)
            .header("X-Amz-Target", "Transcribe.StartTranscriptionJob")
            .header("Authorization", AUTH_HEADER)
            .body("""
                {"TranscriptionJobName":"test-job-start",
                 "Media":{"MediaFileUri":"s3://my-bucket/audio.mp3"},
                 "LanguageCode":"en-US","MediaFormat":"mp3"}""")
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body("TranscriptionJob.TranscriptionJobName", equalTo("test-job-start"))
            .body("TranscriptionJob.TranscriptionJobStatus", equalTo("COMPLETED"))
            .body("TranscriptionJob.LanguageCode", equalTo("en-US"))
            .body("TranscriptionJob.MediaFormat", equalTo("mp3"))
            .body("TranscriptionJob.Media.MediaFileUri", equalTo("s3://my-bucket/audio.mp3"))
            .body("TranscriptionJob.Transcript.TranscriptFileUri", notNullValue())
            .body("TranscriptionJob.CreationTime", notNullValue())
            .body("TranscriptionJob.CompletionTime", notNullValue());
    }

    @Test
    void startTranscriptionJob_defaultLanguageCode() {
        given()
            .contentType(CONTENT_TYPE)
            .header("X-Amz-Target", "Transcribe.StartTranscriptionJob")
            .header("Authorization", AUTH_HEADER)
            .body("""
                {"TranscriptionJobName":"test-job-defaults",
                 "Media":{"MediaFileUri":"s3://bucket/audio.wav"}}""")
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body("TranscriptionJob.LanguageCode", equalTo("en-US"))
            .body("TranscriptionJob.MediaFormat", equalTo("mp4"));
    }

    @Test
    void startTranscriptionJob_duplicateName_returnsConflict() {
        String body = """
            {"TranscriptionJobName":"test-job-dup",
             "Media":{"MediaFileUri":"s3://bucket/audio.wav"}}""";

        given()
            .contentType(CONTENT_TYPE)
            .header("X-Amz-Target", "Transcribe.StartTranscriptionJob")
            .header("Authorization", AUTH_HEADER)
            .body(body)
        .when()
            .post("/")
        .then()
            .statusCode(200);

        given()
            .contentType(CONTENT_TYPE)
            .header("X-Amz-Target", "Transcribe.StartTranscriptionJob")
            .header("Authorization", AUTH_HEADER)
            .body(body)
        .when()
            .post("/")
        .then()
            .statusCode(400)
            .body("__type", equalTo("ConflictException"));
    }

    @Test
    void startTranscriptionJob_missingJobName_returnsBadRequest() {
        given()
            .contentType(CONTENT_TYPE)
            .header("X-Amz-Target", "Transcribe.StartTranscriptionJob")
            .header("Authorization", AUTH_HEADER)
            .body("""
                {"Media":{"MediaFileUri":"s3://bucket/audio.wav"}}""")
        .when()
            .post("/")
        .then()
            .statusCode(400)
            .body("__type", equalTo("BadRequestException"));
    }

    @Test
    void startTranscriptionJob_missingMedia_returnsBadRequest() {
        given()
            .contentType(CONTENT_TYPE)
            .header("X-Amz-Target", "Transcribe.StartTranscriptionJob")
            .header("Authorization", AUTH_HEADER)
            .body("""
                {"TranscriptionJobName":"test-job-no-media"}""")
        .when()
            .post("/")
        .then()
            .statusCode(400)
            .body("__type", equalTo("BadRequestException"));
    }

    // ── GetTranscriptionJob ──────────────────────────────────────────────────

    @Test
    void getTranscriptionJob_returnsJobDetails() {
        given()
            .contentType(CONTENT_TYPE)
            .header("X-Amz-Target", "Transcribe.StartTranscriptionJob")
            .header("Authorization", AUTH_HEADER)
            .body("""
                {"TranscriptionJobName":"test-job-get",
                 "Media":{"MediaFileUri":"s3://bucket/audio.wav"},
                 "LanguageCode":"fr-FR"}""")
        .when()
            .post("/")
        .then()
            .statusCode(200);

        given()
            .contentType(CONTENT_TYPE)
            .header("X-Amz-Target", "Transcribe.GetTranscriptionJob")
            .header("Authorization", AUTH_HEADER)
            .body("""
                {"TranscriptionJobName":"test-job-get"}""")
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body("TranscriptionJob.TranscriptionJobName", equalTo("test-job-get"))
            .body("TranscriptionJob.TranscriptionJobStatus", equalTo("COMPLETED"))
            .body("TranscriptionJob.LanguageCode", equalTo("fr-FR"));
    }

    @Test
    void getTranscriptionJob_notFound_returns400() {
        given()
            .contentType(CONTENT_TYPE)
            .header("X-Amz-Target", "Transcribe.GetTranscriptionJob")
            .header("Authorization", AUTH_HEADER)
            .body("""
                {"TranscriptionJobName":"nonexistent-job"}""")
        .when()
            .post("/")
        .then()
            .statusCode(400)
            .body("__type", equalTo("NotFoundException"));
    }

    // ── ListTranscriptionJobs ────────────────────────────────────────────────

    @Test
    void listTranscriptionJobs_returnsJobs() {
        for (String name : new String[]{"list-job-a", "list-job-b"}) {
            given()
                .contentType(CONTENT_TYPE)
                .header("X-Amz-Target", "Transcribe.StartTranscriptionJob")
                .header("Authorization", AUTH_HEADER)
                .body("{\"TranscriptionJobName\":\"" + name
                        + "\",\"Media\":{\"MediaFileUri\":\"s3://b/a.wav\"}}")
            .when()
                .post("/")
            .then()
                .statusCode(200);
        }

        given()
            .contentType(CONTENT_TYPE)
            .header("X-Amz-Target", "Transcribe.ListTranscriptionJobs")
            .header("Authorization", AUTH_HEADER)
            .body("""
                {"JobNameContains":"list-job-"}""")
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body("TranscriptionJobSummaries", hasSize(greaterThanOrEqualTo(2)))
            .body("TranscriptionJobSummaries.TranscriptionJobName",
                    hasItems("list-job-a", "list-job-b"));
    }

    // ── DeleteTranscriptionJob ───────────────────────────────────────────────

    @Test
    void deleteTranscriptionJob_removesJob() {
        given()
            .contentType(CONTENT_TYPE)
            .header("X-Amz-Target", "Transcribe.StartTranscriptionJob")
            .header("Authorization", AUTH_HEADER)
            .body("""
                {"TranscriptionJobName":"test-job-delete",
                 "Media":{"MediaFileUri":"s3://b/a.wav"}}""")
        .when()
            .post("/")
        .then()
            .statusCode(200);

        given()
            .contentType(CONTENT_TYPE)
            .header("X-Amz-Target", "Transcribe.DeleteTranscriptionJob")
            .header("Authorization", AUTH_HEADER)
            .body("""
                {"TranscriptionJobName":"test-job-delete"}""")
        .when()
            .post("/")
        .then()
            .statusCode(200);

        given()
            .contentType(CONTENT_TYPE)
            .header("X-Amz-Target", "Transcribe.GetTranscriptionJob")
            .header("Authorization", AUTH_HEADER)
            .body("""
                {"TranscriptionJobName":"test-job-delete"}""")
        .when()
            .post("/")
        .then()
            .statusCode(400)
            .body("__type", equalTo("NotFoundException"));
    }

    @Test
    void deleteTranscriptionJob_notFound_returnsBadRequest() {
        given()
            .contentType(CONTENT_TYPE)
            .header("X-Amz-Target", "Transcribe.DeleteTranscriptionJob")
            .header("Authorization", AUTH_HEADER)
            .body("""
                {"TranscriptionJobName":"nonexistent-delete"}""")
        .when()
            .post("/")
        .then()
            .statusCode(400)
            .body("__type", equalTo("BadRequestException"));
    }

    // ── CreateVocabulary ─────────────────────────────────────────────────────

    @Test
    void createVocabulary_returnsReadyVocabulary() {
        given()
            .contentType(CONTENT_TYPE)
            .header("X-Amz-Target", "Transcribe.CreateVocabulary")
            .header("Authorization", AUTH_HEADER)
            .body("""
                {"VocabularyName":"test-vocab-create","LanguageCode":"en-US"}""")
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body("VocabularyName", equalTo("test-vocab-create"))
            .body("LanguageCode", equalTo("en-US"))
            .body("VocabularyState", equalTo("READY"))
            .body("LastModifiedTime", notNullValue());
    }

    @Test
    void createVocabulary_duplicateName_returnsConflict() {
        String body = """
            {"VocabularyName":"test-vocab-dup","LanguageCode":"en-US"}""";

        given()
            .contentType(CONTENT_TYPE)
            .header("X-Amz-Target", "Transcribe.CreateVocabulary")
            .header("Authorization", AUTH_HEADER)
            .body(body)
        .when()
            .post("/")
        .then()
            .statusCode(200);

        given()
            .contentType(CONTENT_TYPE)
            .header("X-Amz-Target", "Transcribe.CreateVocabulary")
            .header("Authorization", AUTH_HEADER)
            .body(body)
        .when()
            .post("/")
        .then()
            .statusCode(400)
            .body("__type", equalTo("ConflictException"));
    }

    // ── GetVocabulary ────────────────────────────────────────────────────────

    @Test
    void getVocabulary_returnsVocabularyDetails() {
        given()
            .contentType(CONTENT_TYPE)
            .header("X-Amz-Target", "Transcribe.CreateVocabulary")
            .header("Authorization", AUTH_HEADER)
            .body("""
                {"VocabularyName":"test-vocab-get","LanguageCode":"de-DE"}""")
        .when()
            .post("/")
        .then()
            .statusCode(200);

        given()
            .contentType(CONTENT_TYPE)
            .header("X-Amz-Target", "Transcribe.GetVocabulary")
            .header("Authorization", AUTH_HEADER)
            .body("""
                {"VocabularyName":"test-vocab-get"}""")
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body("VocabularyName", equalTo("test-vocab-get"))
            .body("LanguageCode", equalTo("de-DE"))
            .body("VocabularyState", equalTo("READY"));
    }

    @Test
    void getVocabulary_notFound_returns400() {
        given()
            .contentType(CONTENT_TYPE)
            .header("X-Amz-Target", "Transcribe.GetVocabulary")
            .header("Authorization", AUTH_HEADER)
            .body("""
                {"VocabularyName":"nonexistent-vocab"}""")
        .when()
            .post("/")
        .then()
            .statusCode(400)
            .body("__type", equalTo("NotFoundException"));
    }

    // ── ListVocabularies ─────────────────────────────────────────────────────

    @Test
    void listVocabularies_returnsAll() {
        for (String name : new String[]{"list-vocab-a", "list-vocab-b"}) {
            given()
                .contentType(CONTENT_TYPE)
                .header("X-Amz-Target", "Transcribe.CreateVocabulary")
                .header("Authorization", AUTH_HEADER)
                .body("{\"VocabularyName\":\"" + name + "\",\"LanguageCode\":\"en-US\"}")
            .when()
                .post("/")
            .then()
                .statusCode(200);
        }

        given()
            .contentType(CONTENT_TYPE)
            .header("X-Amz-Target", "Transcribe.ListVocabularies")
            .header("Authorization", AUTH_HEADER)
            .body("""
                {"NameContains":"list-vocab-"}""")
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body("Vocabularies", hasSize(greaterThanOrEqualTo(2)))
            .body("Vocabularies.VocabularyName", hasItems("list-vocab-a", "list-vocab-b"));
    }

    // ── DeleteVocabulary ─────────────────────────────────────────────────────

    @Test
    void deleteVocabulary_removesVocabulary() {
        given()
            .contentType(CONTENT_TYPE)
            .header("X-Amz-Target", "Transcribe.CreateVocabulary")
            .header("Authorization", AUTH_HEADER)
            .body("""
                {"VocabularyName":"test-vocab-delete","LanguageCode":"en-US"}""")
        .when()
            .post("/")
        .then()
            .statusCode(200);

        given()
            .contentType(CONTENT_TYPE)
            .header("X-Amz-Target", "Transcribe.DeleteVocabulary")
            .header("Authorization", AUTH_HEADER)
            .body("""
                {"VocabularyName":"test-vocab-delete"}""")
        .when()
            .post("/")
        .then()
            .statusCode(200);

        given()
            .contentType(CONTENT_TYPE)
            .header("X-Amz-Target", "Transcribe.GetVocabulary")
            .header("Authorization", AUTH_HEADER)
            .body("""
                {"VocabularyName":"test-vocab-delete"}""")
        .when()
            .post("/")
        .then()
            .statusCode(400)
            .body("__type", equalTo("NotFoundException"));
    }

    @Test
    void deleteVocabulary_notFound_returns400() {
        given()
            .contentType(CONTENT_TYPE)
            .header("X-Amz-Target", "Transcribe.DeleteVocabulary")
            .header("Authorization", AUTH_HEADER)
            .body("""
                {"VocabularyName":"nonexistent-vocab-del"}""")
        .when()
            .post("/")
        .then()
            .statusCode(400)
            .body("__type", equalTo("NotFoundException"));
    }

    // ── Unknown operation ────────────────────────────────────────────────────

    @Test
    void unknownAction_returnsUnknownOperationError() {
        given()
            .contentType(CONTENT_TYPE)
            .header("X-Amz-Target", "Transcribe.FakeAction")
            .header("Authorization", AUTH_HEADER)
            .body("{}")
        .when()
            .post("/")
        .then()
            .statusCode(400)
            .body("__type", equalTo("UnknownOperationException"));
    }
}
