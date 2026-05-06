package io.github.hectorvent.floci.services.textract;
import io.github.hectorvent.floci.testing.RestAssuredJsonUtils;
import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.*;
import static org.hamcrest.MatcherAssert.assertThat;
/**
 * Integration tests for the Amazon Textract stub.
 * Validates AWS-compatible wire format using RestAssured.
 * Protocol: JSON 1.1 — Content-Type: application/x-amz-json-1.1, X-Amz-Target: Textract.<Action>
 */
@QuarkusTest
class TextractIntegrationTest {
    @BeforeAll
    static void configureRestAssured() {
        RestAssuredJsonUtils.configureAwsContentTypes();
    }
    private static final String CONTENT_TYPE = "application/x-amz-json-1.1";
    private static final String AUTH_HEADER =
            "AWS4-HMAC-SHA256 Credential=AKID/20260101/us-east-1/textract/aws4_request";
    @Test
    void detectDocumentText_returnsBlocksAndDocumentMetadata() {
        given()
            .contentType(CONTENT_TYPE)
            .header("X-Amz-Target", "Textract.DetectDocumentText")
            .header("Authorization", AUTH_HEADER)
            .body("{\"Document\":{\"S3Object\":{\"Bucket\":\"my-bucket\",\"Name\":\"test.pdf\"}}}")
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body("DocumentMetadata.Pages", equalTo(1))
            .body("DetectDocumentTextModelVersion", equalTo("1.0"))
            .body("Blocks", hasSize(3))
            .body("Blocks.BlockType", hasItems("PAGE", "LINE", "WORD"));
    }
    @Test
    void detectDocumentText_blockShapesAreAwsCompatible() {
        given()
            .contentType(CONTENT_TYPE)
            .header("X-Amz-Target", "Textract.DetectDocumentText")
            .header("Authorization", AUTH_HEADER)
            .body("{}")
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body("Blocks[0].Id", notNullValue())
            .body("Blocks[0].Confidence", notNullValue())
            .body("Blocks[0].Geometry.BoundingBox.Width", notNullValue())
            .body("Blocks[0].Geometry.BoundingBox.Height", notNullValue())
            .body("Blocks[0].Geometry.BoundingBox.Left", notNullValue())
            .body("Blocks[0].Geometry.BoundingBox.Top", notNullValue())
            .body("Blocks[0].Geometry.Polygon", hasSize(4));
    }
    @Test
    void analyzeDocument_returnsBlocksAndModelVersion() {
        given()
            .contentType(CONTENT_TYPE)
            .header("X-Amz-Target", "Textract.AnalyzeDocument")
            .header("Authorization", AUTH_HEADER)
            .body("{\"Document\":{\"S3Object\":{\"Bucket\":\"my-bucket\",\"Name\":\"test.pdf\"}},\"FeatureTypes\":[\"TABLES\",\"FORMS\"]}")
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body("DocumentMetadata.Pages", equalTo(1))
            .body("AnalyzeDocumentModelVersion", equalTo("1.0"))
            .body("Blocks", hasSize(3))
            .body("Blocks.BlockType", hasItems("PAGE", "LINE", "WORD"));
    }
    @Test
    void asyncTextDetection_startAndGetSucceeded() {
        String jobId = given()
            .contentType(CONTENT_TYPE)
            .header("X-Amz-Target", "Textract.StartDocumentTextDetection")
            .header("Authorization", AUTH_HEADER)
            .body("{\"DocumentLocation\":{\"S3Object\":{\"Bucket\":\"my-bucket\",\"Name\":\"test.pdf\"}}}")
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body("JobId", notNullValue())
            .extract().path("JobId");
        given()
            .contentType(CONTENT_TYPE)
            .header("X-Amz-Target", "Textract.GetDocumentTextDetection")
            .header("Authorization", AUTH_HEADER)
            .body("{\"JobId\":\"" + jobId + "\"}")
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body("JobStatus", equalTo("SUCCEEDED"))
            .body("DocumentMetadata.Pages", equalTo(1))
            .body("DetectDocumentTextModelVersion", equalTo("1.0"))
            .body("Blocks", hasSize(3));
    }
    @Test
    void getDocumentTextDetection_unknownJobId_returns400() {
        given()
            .contentType(CONTENT_TYPE)
            .header("X-Amz-Target", "Textract.GetDocumentTextDetection")
            .header("Authorization", AUTH_HEADER)
            .body("{\"JobId\":\"non-existent-job-id\"}")
        .when()
            .post("/")
        .then()
            .statusCode(400)
            .body("__type", equalTo("InvalidJobIdException"));
    }
    @Test
    void getDocumentTextDetection_missingJobId_returns400() {
        given()
            .contentType(CONTENT_TYPE)
            .header("X-Amz-Target", "Textract.GetDocumentTextDetection")
            .header("Authorization", AUTH_HEADER)
            .body("{}")
        .when()
            .post("/")
        .then()
            .statusCode(400)
            .body("__type", equalTo("ValidationException"));
    }
    @Test
    void asyncDocumentAnalysis_startAndGetSucceeded() {
        String jobId = given()
            .contentType(CONTENT_TYPE)
            .header("X-Amz-Target", "Textract.StartDocumentAnalysis")
            .header("Authorization", AUTH_HEADER)
            .body("{\"DocumentLocation\":{\"S3Object\":{\"Bucket\":\"my-bucket\",\"Name\":\"test.pdf\"}},\"FeatureTypes\":[\"TABLES\"]}")
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body("JobId", notNullValue())
            .extract().path("JobId");
        given()
            .contentType(CONTENT_TYPE)
            .header("X-Amz-Target", "Textract.GetDocumentAnalysis")
            .header("Authorization", AUTH_HEADER)
            .body("{\"JobId\":\"" + jobId + "\"}")
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body("JobStatus", equalTo("SUCCEEDED"))
            .body("DocumentMetadata.Pages", equalTo(1))
            .body("AnalyzeDocumentModelVersion", equalTo("1.0"))
            .body("Blocks", hasSize(3));
    }
    @Test
    void getDocumentAnalysis_wrongJobType_returns400() {
        String jobId = given()
            .contentType(CONTENT_TYPE)
            .header("X-Amz-Target", "Textract.StartDocumentTextDetection")
            .header("Authorization", AUTH_HEADER)
            .body("{}")
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .extract().path("JobId");
        given()
            .contentType(CONTENT_TYPE)
            .header("X-Amz-Target", "Textract.GetDocumentAnalysis")
            .header("Authorization", AUTH_HEADER)
            .body("{\"JobId\":\"" + jobId + "\"}")
        .when()
            .post("/")
        .then()
            .statusCode(400)
            .body("__type", equalTo("InvalidJobIdException"));
    }
    @Test
    void unknownAction_returnsUnknownOperationError() {
        given()
            .contentType(CONTENT_TYPE)
            .header("X-Amz-Target", "Textract.DetectSentiment")
            .header("Authorization", AUTH_HEADER)
            .body("{}")
        .when()
            .post("/")
        .then()
            .statusCode(400)
            .body("__type", equalTo("UnknownOperationException"));
    }

    @Test
    void analyzeDocument_blockShapesAreAwsCompatible() {
        given()
            .contentType(CONTENT_TYPE)
            .header("X-Amz-Target", "Textract.AnalyzeDocument")
            .header("Authorization", AUTH_HEADER)
            .body("{\"Document\":{\"S3Object\":{\"Bucket\":\"my-bucket\",\"Name\":\"test.pdf\"}},\"FeatureTypes\":[\"TABLES\"]}")
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body("Blocks[0].Id", notNullValue())
            .body("Blocks[0].Geometry.BoundingBox.Width", notNullValue())
            .body("Blocks[0].Geometry.Polygon", hasSize(4));
    }

    @Test
    void analyzeDocument_featureTypesIsOptional() {
        given()
            .contentType(CONTENT_TYPE)
            .header("X-Amz-Target", "Textract.AnalyzeDocument")
            .header("Authorization", AUTH_HEADER)
            .body("{}")
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body("DocumentMetadata.Pages", equalTo(1))
            .body("AnalyzeDocumentModelVersion", equalTo("1.0"));
    }

    @Test
    void detectDocumentText_wordBlockHasText() {
        given()
            .contentType(CONTENT_TYPE)
            .header("X-Amz-Target", "Textract.DetectDocumentText")
            .header("Authorization", AUTH_HEADER)
            .body("{}")
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body("Blocks.findAll { it.BlockType == 'WORD' }.Text", hasItem("Floci"));
    }

    @Test
    void detectDocumentText_confidenceIsPresent() {
        given()
            .contentType(CONTENT_TYPE)
            .header("X-Amz-Target", "Textract.DetectDocumentText")
            .header("Authorization", AUTH_HEADER)
            .body("{}")
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body("Blocks.Confidence", everyItem(notNullValue()));
    }

    @Test
    void detectDocumentText_eachBlockHasPageNumber() {
        given()
            .contentType(CONTENT_TYPE)
            .header("X-Amz-Target", "Textract.DetectDocumentText")
            .header("Authorization", AUTH_HEADER)
            .body("{}")
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body("Blocks.Page", everyItem(equalTo(1)));
    }

    @Test
    void getDocumentAnalysis_unknownJobId_returns400() {
        given()
            .contentType(CONTENT_TYPE)
            .header("X-Amz-Target", "Textract.GetDocumentAnalysis")
            .header("Authorization", AUTH_HEADER)
            .body("{\"JobId\":\"non-existent-job-id\"}")
        .when()
            .post("/")
        .then()
            .statusCode(400)
            .body("__type", equalTo("InvalidJobIdException"));
    }

    @Test
    void getDocumentAnalysis_missingJobId_returns400() {
        given()
            .contentType(CONTENT_TYPE)
            .header("X-Amz-Target", "Textract.GetDocumentAnalysis")
            .header("Authorization", AUTH_HEADER)
            .body("{}")
        .when()
            .post("/")
        .then()
            .statusCode(400)
            .body("__type", equalTo("ValidationException"));
    }

    @Test
    void getDocumentTextDetection_wrongJobType_returns400() {
        // Start a DocumentAnalysis job, then try to get it as TextDetection
        String jobId = given()
            .contentType(CONTENT_TYPE)
            .header("X-Amz-Target", "Textract.StartDocumentAnalysis")
            .header("Authorization", AUTH_HEADER)
            .body("{\"FeatureTypes\":[\"TABLES\"]}")
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .extract().path("JobId");

        given()
            .contentType(CONTENT_TYPE)
            .header("X-Amz-Target", "Textract.GetDocumentTextDetection")
            .header("Authorization", AUTH_HEADER)
            .body("{\"JobId\":\"" + jobId + "\"}")
        .when()
            .post("/")
        .then()
            .statusCode(400)
            .body("__type", equalTo("InvalidJobIdException"));
    }

    @Test
    void asyncTextDetection_jobIdIsUnique() {
        String jobId1 = given()
            .contentType(CONTENT_TYPE)
            .header("X-Amz-Target", "Textract.StartDocumentTextDetection")
            .header("Authorization", AUTH_HEADER)
            .body("{}")
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .extract().path("JobId");

        String jobId2 = given()
            .contentType(CONTENT_TYPE)
            .header("X-Amz-Target", "Textract.StartDocumentTextDetection")
            .header("Authorization", AUTH_HEADER)
            .body("{}")
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .extract().path("JobId");

        assertThat(jobId1, not(equalTo(jobId2)));
    }

    @Test
    void asyncDocumentAnalysis_jobIdIsUnique() {
        String jobId1 = given()
            .contentType(CONTENT_TYPE)
            .header("X-Amz-Target", "Textract.StartDocumentAnalysis")
            .header("Authorization", AUTH_HEADER)
            .body("{\"FeatureTypes\":[\"TABLES\"]}")
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .extract().path("JobId");

        String jobId2 = given()
            .contentType(CONTENT_TYPE)
            .header("X-Amz-Target", "Textract.StartDocumentAnalysis")
            .header("Authorization", AUTH_HEADER)
            .body("{\"FeatureTypes\":[\"FORMS\"]}")
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .extract().path("JobId");

        assertThat(jobId1, not(equalTo(jobId2)));
    }

    @Test
    void getDocumentTextDetection_returnsCorrectModelVersion() {
        String jobId = given()
            .contentType(CONTENT_TYPE)
            .header("X-Amz-Target", "Textract.StartDocumentTextDetection")
            .header("Authorization", AUTH_HEADER)
            .body("{}")
        .when()
            .post("/")
        .then()
            .extract().path("JobId");

        given()
            .contentType(CONTENT_TYPE)
            .header("X-Amz-Target", "Textract.GetDocumentTextDetection")
            .header("Authorization", AUTH_HEADER)
            .body("{\"JobId\":\"" + jobId + "\"}")
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body("DetectDocumentTextModelVersion", equalTo("1.0"));
    }

    @Test
    void getDocumentAnalysis_returnsCorrectModelVersion() {
        String jobId = given()
            .contentType(CONTENT_TYPE)
            .header("X-Amz-Target", "Textract.StartDocumentAnalysis")
            .header("Authorization", AUTH_HEADER)
            .body("{\"FeatureTypes\":[\"TABLES\"]}")
        .when()
            .post("/")
        .then()
            .extract().path("JobId");

        given()
            .contentType(CONTENT_TYPE)
            .header("X-Amz-Target", "Textract.GetDocumentAnalysis")
            .header("Authorization", AUTH_HEADER)
            .body("{\"JobId\":\"" + jobId + "\"}")
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body("AnalyzeDocumentModelVersion", equalTo("1.0"));
    }
}
