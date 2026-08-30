package io.github.hectorvent.floci.services.rekognition;
import io.github.hectorvent.floci.testing.RestAssuredJsonUtils;
import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.*;
/**
 * Integration tests for the Amazon Rekognition stub.
 * Validates AWS-compatible wire format using RestAssured.
 * Protocol: JSON 1.1 — Content-Type: application/x-amz-json-1.1, X-Amz-Target: RekognitionService.<Action>
 */
@QuarkusTest
class RekognitionIntegrationTest {
    @BeforeAll
    static void configureRestAssured() {
        RestAssuredJsonUtils.configureAwsContentTypes();
    }
    private static final String CONTENT_TYPE = "application/x-amz-json-1.1";
    private static final String AUTH_HEADER =
            "AWS4-HMAC-SHA256 Credential=AKID/20260101/us-east-1/rekognition/aws4_request";
    private static final String S3_IMAGE =
            "{\"S3Object\":{\"Bucket\":\"my-bucket\",\"Name\":\"photo.jpg\"}}";
    @Test
    void detectLabels_returnsStubLabel() {
        given()
            .contentType(CONTENT_TYPE)
            .header("X-Amz-Target", "RekognitionService.DetectLabels")
            .header("Authorization", AUTH_HEADER)
            .body("{\"Image\":" + S3_IMAGE + "}")
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body("Labels", hasSize(1))
            .body("Labels[0].Name", equalTo("Floci"))
            .body("Labels[0].Confidence", notNullValue())
            .body("LabelModelVersion", equalTo("1.0"));
    }
    @Test
    void detectLabels_matchingMockConfig_returnsConfiguredResponse() {
        // src/test/resources/fixtures/ai-mock-config.json maps mock-bucket/cat.jpg.
        given()
            .contentType(CONTENT_TYPE)
            .header("X-Amz-Target", "RekognitionService.DetectLabels")
            .header("Authorization", AUTH_HEADER)
            .body("{\"Image\":{\"S3Object\":{\"Bucket\":\"mock-bucket\",\"Name\":\"cat.jpg\"}}}")
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body("Labels", hasSize(2))
            .body("Labels.Name", hasItems("Cat", "Animal"));
    }
    @Test
    void detectLabels_missingImage_returns400() {
        given()
            .contentType(CONTENT_TYPE)
            .header("X-Amz-Target", "RekognitionService.DetectLabels")
            .header("Authorization", AUTH_HEADER)
            .body("{}")
        .when()
            .post("/")
        .then()
            .statusCode(400)
            .body("__type", equalTo("InvalidParameterException"));
    }
    @Test
    void detectLabels_imageWithoutBytesOrS3Object_returns400() {
        given()
            .contentType(CONTENT_TYPE)
            .header("X-Amz-Target", "RekognitionService.DetectLabels")
            .header("Authorization", AUTH_HEADER)
            .body("{\"Image\":{}}")
        .when()
            .post("/")
        .then()
            .statusCode(400)
            .body("__type", equalTo("InvalidParameterException"));
    }
    @Test
    void detectLabels_nonObjectImage_returnsSerializationException() {
        given()
            .contentType(CONTENT_TYPE)
            .header("X-Amz-Target", "RekognitionService.DetectLabels")
            .header("Authorization", AUTH_HEADER)
            .body("{\"Image\":\"banana\"}")
        .when()
            .post("/")
        .then()
            .statusCode(400)
            .body("__type", equalTo("SerializationException"));
    }
    @Test
    void detectLabels_nonStringBytes_returnsSerializationException() {
        given()
            .contentType(CONTENT_TYPE)
            .header("X-Amz-Target", "RekognitionService.DetectLabels")
            .header("Authorization", AUTH_HEADER)
            .body("{\"Image\":{\"Bytes\":12345}}")
        .when()
            .post("/")
        .then()
            .statusCode(400)
            .body("__type", equalTo("SerializationException"));
    }
    @Test
    void detectLabels_nonObjectS3Object_returnsSerializationException() {
        given()
            .contentType(CONTENT_TYPE)
            .header("X-Amz-Target", "RekognitionService.DetectLabels")
            .header("Authorization", AUTH_HEADER)
            .body("{\"Image\":{\"S3Object\":\"not-an-object\"}}")
        .when()
            .post("/")
        .then()
            .statusCode(400)
            .body("__type", equalTo("SerializationException"));
    }
    @Test
    void detectLabels_bothBytesAndS3ObjectAccepted() {
        // Neither field's content is read, and nothing in the Rekognition model
        // declares supplying both invalid.
        given()
            .contentType(CONTENT_TYPE)
            .header("X-Amz-Target", "RekognitionService.DetectLabels")
            .header("Authorization", AUTH_HEADER)
            .body("{\"Image\":{\"Bytes\":\"aGVsbG8=\",\"S3Object\":{\"Bucket\":\"my-bucket\",\"Name\":\"photo.jpg\"}}}")
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body("Labels", hasSize(1));
    }
    @Test
    void detectFaces_returnsEmptyFaceDetails() {
        given()
            .contentType(CONTENT_TYPE)
            .header("X-Amz-Target", "RekognitionService.DetectFaces")
            .header("Authorization", AUTH_HEADER)
            .body("{\"Image\":" + S3_IMAGE + "}")
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body("FaceDetails", hasSize(0));
    }
    @Test
    void detectText_returnsLineAndWordDetections() {
        given()
            .contentType(CONTENT_TYPE)
            .header("X-Amz-Target", "RekognitionService.DetectText")
            .header("Authorization", AUTH_HEADER)
            .body("{\"Image\":" + S3_IMAGE + "}")
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body("TextDetections", hasSize(2))
            .body("TextDetections.Type", hasItems("LINE", "WORD"))
            .body("TextDetections.findAll { it.Type == 'LINE' }.DetectedText", hasItem("Floci"))
            .body("TextDetections[0].Geometry.BoundingBox.Width", notNullValue())
            .body("TextDetections[0].Geometry.Polygon", hasSize(4))
            .body("TextModelVersion", equalTo("1.0"));
    }
    @Test
    void compareFaces_returnsEmptyMatches() {
        given()
            .contentType(CONTENT_TYPE)
            .header("X-Amz-Target", "RekognitionService.CompareFaces")
            .header("Authorization", AUTH_HEADER)
            .body("{\"SourceImage\":" + S3_IMAGE + ",\"TargetImage\":" + S3_IMAGE + "}")
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body("FaceMatches", hasSize(0))
            .body("UnmatchedFaces", hasSize(0));
    }
    @Test
    void compareFaces_missingTargetImage_returns400() {
        given()
            .contentType(CONTENT_TYPE)
            .header("X-Amz-Target", "RekognitionService.CompareFaces")
            .header("Authorization", AUTH_HEADER)
            .body("{\"SourceImage\":" + S3_IMAGE + "}")
        .when()
            .post("/")
        .then()
            .statusCode(400)
            .body("__type", equalTo("InvalidParameterException"));
    }
    @Test
    void detectModerationLabels_returnsEmptyLabels() {
        given()
            .contentType(CONTENT_TYPE)
            .header("X-Amz-Target", "RekognitionService.DetectModerationLabels")
            .header("Authorization", AUTH_HEADER)
            .body("{\"Image\":" + S3_IMAGE + "}")
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body("ModerationLabels", hasSize(0))
            .body("ModerationModelVersion", equalTo("1.0"));
    }
    @Test
    void unknownAction_returnsUnknownOperationError() {
        given()
            .contentType(CONTENT_TYPE)
            .header("X-Amz-Target", "RekognitionService.IndexFaces")
            .header("Authorization", AUTH_HEADER)
            .body("{}")
        .when()
            .post("/")
        .then()
            .statusCode(400)
            .body("__type", equalTo("UnknownOperationException"));
    }
}
