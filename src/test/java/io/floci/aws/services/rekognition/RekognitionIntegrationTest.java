package io.floci.aws.services.rekognition;
import io.floci.aws.testing.RestAssuredJsonUtils;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.response.ValidatableResponse;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Base64;

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
    private static final String JPEG_BASE64 = Base64.getEncoder().encodeToString(TestImages.jpeg(100, 100));
    private static boolean imagesUploaded;

    /**
     * Rekognition reads an S3Object and refuses one it cannot read, so the objects the tests name
     * exist: my-bucket/photo.jpg, mock-bucket/cat.jpg (the AI mock fixture's key) and two images
     * Rekognition refuses.
     */
    @BeforeEach
    void uploadImages() {
        if (imagesUploaded) {
            return;
        }
        put("my-bucket", "photo.jpg", TestImages.jpeg(100, 100));
        put("mock-bucket", "cat.jpg", TestImages.jpeg(100, 100));
        put("my-bucket", "upload.webp", TestImages.WEBP);
        put("my-bucket", "panorama.jpg", TestImages.jpeg(10001, 100));
        imagesUploaded = true;
    }

    private static void put(String bucket, String key, byte[] data) {
        given().when().put("/" + bucket);
        given().body(data).when().put("/" + bucket + "/" + key).then().statusCode(200);
    }

    private static ValidatableResponse call(String action, String body) {
        return given()
            .contentType(CONTENT_TYPE)
            .header("X-Amz-Target", "RekognitionService." + action)
            .header("Authorization", AUTH_HEADER)
            .body(body)
        .when()
            .post("/")
        .then();
    }
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
        // Nothing in the Rekognition model declares supplying both invalid; the bytes are what is checked.
        given()
            .contentType(CONTENT_TYPE)
            .header("X-Amz-Target", "RekognitionService.DetectLabels")
            .header("Authorization", AUTH_HEADER)
            .body("{\"Image\":{\"Bytes\":\"" + JPEG_BASE64 + "\",\"S3Object\":{\"Bucket\":\"my-bucket\",\"Name\":\"photo.jpg\"}}}")
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
    /** Measured on Rekognition (us-east-2): WebP is refused, as Bytes or as an S3Object. */
    @Test
    void detectModerationLabels_webpImage_returnsInvalidImageFormat() {
        String webp = Base64.getEncoder().encodeToString(TestImages.WEBP);
        call("DetectModerationLabels", "{\"Image\":{\"Bytes\":\"" + webp + "\"}}")
            .statusCode(400)
            .body("__type", equalTo("InvalidImageFormatException"))
            .body("message", equalTo("Request has invalid image format"));
        call("DetectModerationLabels", "{\"Image\":{\"S3Object\":{\"Bucket\":\"my-bucket\",\"Name\":\"upload.webp\"}}}")
            .statusCode(400)
            .body("__type", equalTo("InvalidImageFormatException"));
    }

    @Test
    void detectModerationLabels_missingObject_returnsInvalidS3Object() {
        call("DetectModerationLabels", "{\"Image\":{\"S3Object\":{\"Bucket\":\"my-bucket\",\"Name\":\"never-uploaded.jpg\"}}}")
            .statusCode(400)
            .body("__type", equalTo("InvalidS3ObjectException"))
            .body("message", equalTo("Unable to get object metadata from S3. Check object key, region and/or access permissions."));
    }

    @Test
    void detectModerationLabels_bucketInAnotherRegion_returnsInvalidS3Object() {
        given()
            .body("<CreateBucketConfiguration><LocationConstraint>us-west-2</LocationConstraint></CreateBucketConfiguration>")
        .when()
            .put("/west-bucket")
        .then()
            .statusCode(200);
        given().body(TestImages.jpeg(100, 100)).when().put("/west-bucket/photo.jpg").then().statusCode(200);

        call("DetectModerationLabels", "{\"Image\":{\"S3Object\":{\"Bucket\":\"west-bucket\",\"Name\":\"photo.jpg\"}}}")
            .statusCode(400)
            .body("__type", equalTo("InvalidS3ObjectException"));
    }

    @Test
    void detectModerationLabels_bucketWithoutARegionCalledFromAnotherRegion_returnsInvalidS3Object() {
        given()
            .contentType(CONTENT_TYPE)
            .header("X-Amz-Target", "RekognitionService.DetectModerationLabels")
            .header("Authorization", "AWS4-HMAC-SHA256 Credential=AKID/20260101/us-west-2/rekognition/aws4_request")
            .body("{\"Image\":" + S3_IMAGE + "}")
        .when()
            .post("/")
        .then()
            .statusCode(400)
            .body("__type", equalTo("InvalidS3ObjectException"));
    }

    @Test
    void compareFaces_bothImagesOversized_reportsBothInOneValidationException() {
        String oversized = "{\"Bytes\":\"" + Base64.getEncoder().encodeToString(new byte[RekognitionImageValidator.MAX_BYTES + 1]) + "\"}";
        call("CompareFaces", "{\"SourceImage\":" + oversized + ",\"TargetImage\":" + oversized + "}")
            .statusCode(400)
            .body("__type", equalTo("ValidationException"))
            .body("message", startsWith("2 validation errors detected: Value at 'sourceImage.bytes'"));
    }

    @Test
    void detectModerationLabels_bytesOverFiveMebibytes_returnsValidationException() {
        String oversized = Base64.getEncoder().encodeToString(new byte[RekognitionImageValidator.MAX_BYTES + 1]);
        call("DetectModerationLabels", "{\"Image\":{\"Bytes\":\"" + oversized + "\"}}")
            .statusCode(400)
            .body("__type", equalTo("ValidationException"))
            .body("message", equalTo("1 validation error detected: Value at 'image.bytes' failed to satisfy constraint: "
                    + "Member must have length less than or equal to 5242880"));
    }

    /** DetectModerationLabels reports a side over 10,000 px as too large; DetectText as an invalid format. */
    @Test
    void anImageWiderThanTenThousandPixels_isRefusedWithEachOperationsError() {
        String panorama = "{\"Image\":{\"S3Object\":{\"Bucket\":\"my-bucket\",\"Name\":\"panorama.jpg\"}}}";
        call("DetectModerationLabels", panorama)
            .statusCode(400)
            .body("__type", equalTo("ImageTooLargeException"))
            .body("message", equalTo("Input image dimensions 10001 x 100 exceed maximum dimension size of 10000 pixels"));
        call("DetectText", panorama)
            .statusCode(400)
            .body("__type", equalTo("InvalidImageFormatException"));
    }

    /** The documented 80 pixel minimum is not enforced by Rekognition. */
    @Test
    void detectModerationLabels_imageUnderEightyPixels_isAccepted() {
        String tiny = Base64.getEncoder().encodeToString(TestImages.jpeg(10, 10));
        call("DetectModerationLabels", "{\"Image\":{\"Bytes\":\"" + tiny + "\"}}")
            .statusCode(200)
            .body("ModerationLabels", hasSize(0));
    }

    @Test
    void compareFaces_invalidTargetImage_returnsInvalidImageFormat() {
        String webp = Base64.getEncoder().encodeToString(TestImages.WEBP);
        call("CompareFaces", "{\"SourceImage\":" + S3_IMAGE + ",\"TargetImage\":{\"Bytes\":\"" + webp + "\"}}")
            .statusCode(400)
            .body("__type", equalTo("InvalidImageFormatException"));
    }

    @Test
    void unknownAction_returnsUnknownOperationError() {
        given()
            .contentType(CONTENT_TYPE)
            .header("X-Amz-Target", "RekognitionService.UnknownAction")
            .header("Authorization", AUTH_HEADER)
            .body("{}")
        .when()
            .post("/")
        .then()
            .statusCode(400)
            .body("__type", equalTo("UnknownOperationException"));
    }
}
