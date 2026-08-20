package io.github.hectorvent.floci.services.firehose;

import io.github.hectorvent.floci.testing.RestAssuredJsonUtils;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.RestAssured;
import io.restassured.config.DecoderConfig;
import io.restassured.response.Response;
import io.restassured.response.ValidatableResponse;
import jakarta.inject.Inject;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Locale;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * End-to-end coverage of the CompressionFormat and FileExtension delivery
 * contract over the wire, asserted on the bytes and headers a consumer reading
 * the delivered object actually sees. Expectations come from real AWS, see
 * https://github.com/floci-io/floci/issues/2328.
 */
@QuarkusTest
class FirehoseCompressionIntegrationTest {

    @Inject
    FirehoseService firehoseService;

    private static final String CONTENT_TYPE = "application/x-amz-json-1.1";
    private static final String TARGET_PREFIX = "Firehose_20150804.";
    private static final String ROLE_ARN = "arn:aws:iam::000000000000:role/firehose-delivery-role";
    private static final String RECORD = "{\"id\": 1}";
    private static final String EXPECTED_PAYLOAD = (RECORD + "\n").repeat(5);
    private static final String ENUM_SET = "[ZIP, HADOOP_SNAPPY, Snappy, GZIP, UNCOMPRESSED]";

    @BeforeAll
    static void configureRestAssured() {
        RestAssuredJsonUtils.configureAwsContentTypes();
    }

    @ParameterizedTest
    @EnumSource(FirehoseCompression.class)
    void deliveredObjectCarriesTheFormatsFramingExtensionAndHeaders(FirehoseCompression format)
            throws IOException {
        String slug = format.name().toLowerCase(Locale.ROOT);
        String stream = "compression-" + slug + "-stream";
        String bucket = "firehose-compression-" + slug;
        createDeliveryStream(stream, bucket, "\"CompressionFormat\": \"" + format.wireValue() + "\"");
        putFiveRecordsAndFlush(stream);

        String key = firstDeliveredKey(bucket);
        assertTrue(key.endsWith(format.extension()), key);

        Response object = getObject(bucket, key);
        assertEquals("application/octet-stream", object.getHeader("Content-Type"));
        assertEquals(format.contentEncoding(), object.getHeader("Content-Encoding"));
        assertEquals(EXPECTED_PAYLOAD, new String(
                FirehoseCompressionDecoder.decompress(format, object.getBody().asByteArray()),
                StandardCharsets.UTF_8));
    }

    @Test
    void fileExtensionReplacesTheCompressionExtensionWithoutChangingTheBody() throws IOException {
        String stream = "compression-fileext-stream";
        String bucket = "firehose-compression-fileext";
        createDeliveryStream(stream, bucket,
                "\"CompressionFormat\": \"GZIP\", \"FileExtension\": \".custom.log\"");
        putFiveRecordsAndFlush(stream);

        String key = firstDeliveredKey(bucket);
        assertTrue(key.endsWith(".custom.log"), key);
        assertFalse(key.contains(".gz"), key);

        Response object = getObject(bucket, key);
        assertEquals("gzip", object.getHeader("Content-Encoding"));
        assertEquals(EXPECTED_PAYLOAD, new String(
                FirehoseCompressionDecoder.decompress(FirehoseCompression.GZIP, object.getBody().asByteArray()),
                StandardCharsets.UTF_8));
    }

    @Test
    void fileExtensionIsEchoedAndMergedByUpdateDestination() {
        String stream = "compression-fileext-echo-stream";
        createDeliveryStream(stream, "firehose-compression-fileext-echo",
                "\"CompressionFormat\": \"GZIP\", \"FileExtension\": \".custom.log\"");

        describe(stream)
            .body("DeliveryStreamDescription.Destinations[0].ExtendedS3DestinationDescription.FileExtension",
                    equalTo(".custom.log"));

        given()
            .contentType(CONTENT_TYPE)
            .header("X-Amz-Target", TARGET_PREFIX + "UpdateDestination")
            .body("""
                    {
                      "DeliveryStreamName": "%s",
                      "CurrentDeliveryStreamVersionId": "1",
                      "DestinationId": "destinationId-000000000001",
                      "ExtendedS3DestinationUpdate": { "FileExtension": ".other.log" }
                    }
                    """.formatted(stream))
        .when()
            .post("/")
        .then()
            .statusCode(200);

        describe(stream)
            .body("DeliveryStreamDescription.Destinations[0].ExtendedS3DestinationDescription.FileExtension",
                    equalTo(".other.log"))
            .body("DeliveryStreamDescription.Destinations[0].ExtendedS3DestinationDescription.CompressionFormat",
                    equalTo("GZIP"));
    }

    /** A stream without FileExtension must not grow the member in its description. */
    @Test
    void fileExtensionIsAbsentFromTheDescriptionWhenNotSpecified() {
        String stream = "compression-no-fileext-stream";
        createDeliveryStream(stream, "firehose-compression-no-fileext", "\"CompressionFormat\": \"GZIP\"");

        describe(stream)
            .body("DeliveryStreamDescription.Destinations[0].ExtendedS3DestinationDescription.FileExtension",
                    equalTo(null));
    }

    @Test
    void createRejectsCompressionFormatOutsideTheEnum() {
        createDeliveryStreamExpecting("compression-bad-enum-stream", "firehose-compression-bad-enum",
                "\"CompressionFormat\": \"BROTLI\"")
            .statusCode(400)
            .body("__type", equalTo("ValidationException"))
            .body("message", equalTo("1 validation error detected: Value at "
                    + "'extendedS3DestinationConfiguration.compressionFormat' failed to satisfy constraint: "
                    + "Member must satisfy enum value set: " + ENUM_SET));
    }

    /** Wrong case is a different value: AWS's enum has Snappy, not SNAPPY. */
    @Test
    void createRejectsMiscasedCompressionFormat() {
        createDeliveryStreamExpecting("compression-miscased-stream", "firehose-compression-miscased",
                "\"CompressionFormat\": \"SNAPPY\"")
            .statusCode(400)
            .body("__type", equalTo("ValidationException"));
    }

    @Test
    void createRejectsFileExtensionBreakingTheApiPattern() {
        createDeliveryStreamExpecting("compression-bad-ext-stream", "firehose-compression-bad-ext",
                "\"FileExtension\": \"nodot\"")
            .statusCode(400)
            .body("__type", equalTo("ValidationException"))
            .body("message", equalTo("1 validation error detected: Value at "
                    + "'extendedS3DestinationConfiguration.fileExtension' failed to satisfy constraint: "
                    + "Member must satisfy regular expression pattern: ^(|\\.[0-9a-z!\\-_.*'()]+)$"));
    }

    /** AWS names the member the value arrived in, so the legacy shape reports its own path. */
    @Test
    void legacyDestinationShapeReportsItsOwnPathInValidationErrors() {
        given()
            .contentType(CONTENT_TYPE)
            .header("X-Amz-Target", TARGET_PREFIX + "CreateDeliveryStream")
            .body("""
                    {
                      "DeliveryStreamName": "compression-legacy-shape-stream",
                      "S3DestinationConfiguration": {
                        "RoleARN": "%s",
                        "BucketARN": "arn:aws:s3:::firehose-compression-legacy",
                        "CompressionFormat": "BROTLI"
                      }
                    }
                    """.formatted(ROLE_ARN))
        .when()
            .post("/")
        .then()
            .statusCode(400)
            .body("__type", equalTo("ValidationException"))
            .body("message", equalTo("1 validation error detected: Value at "
                    + "'s3DestinationConfiguration.compressionFormat' failed to satisfy constraint: "
                    + "Member must satisfy enum value set: " + ENUM_SET));
    }

    @Test
    void updateDestinationReportsTheUpdateShapeInValidationErrors() {
        String stream = "compression-update-shape-stream";
        createDeliveryStream(stream, "firehose-compression-update-shape", "\"CompressionFormat\": \"GZIP\"");

        given()
            .contentType(CONTENT_TYPE)
            .header("X-Amz-Target", TARGET_PREFIX + "UpdateDestination")
            .body("""
                    {
                      "DeliveryStreamName": "%s",
                      "CurrentDeliveryStreamVersionId": "1",
                      "DestinationId": "destinationId-000000000001",
                      "ExtendedS3DestinationUpdate": { "CompressionFormat": "BROTLI" }
                    }
                    """.formatted(stream))
        .when()
            .post("/")
        .then()
            .statusCode(400)
            .body("__type", equalTo("ValidationException"))
            .body("message", equalTo("1 validation error detected: Value at "
                    + "'extendedS3DestinationUpdate.compressionFormat' failed to satisfy constraint: "
                    + "Member must satisfy enum value set: " + ENUM_SET));
    }

    private void createDeliveryStream(String streamName, String bucket, String extraDestinationJson) {
        createDeliveryStreamExpecting(streamName, bucket, extraDestinationJson).statusCode(200);
    }

    private ValidatableResponse createDeliveryStreamExpecting(
            String streamName, String bucket, String extraDestinationJson) {
        return given()
            .contentType(CONTENT_TYPE)
            .header("X-Amz-Target", TARGET_PREFIX + "CreateDeliveryStream")
            .body("""
                    {
                      "DeliveryStreamName": "%s",
                      "ExtendedS3DestinationConfiguration": {
                        "RoleARN": "%s",
                        "BucketARN": "arn:aws:s3:::%s",
                        %s
                      }
                    }
                    """.formatted(streamName, ROLE_ARN, bucket, extraDestinationJson))
        .when()
            .post("/")
        .then();
    }

    private ValidatableResponse describe(String streamName) {
        return given()
            .contentType(CONTENT_TYPE)
            .header("X-Amz-Target", TARGET_PREFIX + "DescribeDeliveryStream")
            .body("{ \"DeliveryStreamName\": \"" + streamName + "\" }")
        .when()
            .post("/")
        .then()
            .statusCode(200);
    }

    private void putFiveRecordsAndFlush(String streamName) {
        String data = Base64.getEncoder().encodeToString(RECORD.getBytes(StandardCharsets.UTF_8));
        given()
            .contentType(CONTENT_TYPE)
            .header("X-Amz-Target", TARGET_PREFIX + "PutRecordBatch")
            .body("""
                    {
                      "DeliveryStreamName": "%s",
                      "Records": [
                        {"Data": "%s"}, {"Data": "%s"}, {"Data": "%s"}, {"Data": "%s"}, {"Data": "%s"}
                      ]
                    }
                    """.formatted(streamName, data, data, data, data, data))
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body("FailedPutCount", equalTo(0));
        firehoseService.flush(streamName);
    }

    private String firstDeliveredKey(String bucket) {
        return given().when().get("/" + bucket)
                .then().statusCode(200)
                .extract().xmlPath().getString("ListBucketResult.Contents[0].Key");
    }

    /**
     * Content decoders are switched off so the compressed bytes arrive as stored:
     * a client that honours Content-Encoding would gunzip the GZIP object in
     * flight and the framing under test would never be seen.
     */
    private Response getObject(String bucket, String key) {
        return given()
                .config(RestAssured.config().decoderConfig(DecoderConfig.decoderConfig().noContentDecoders()))
            .when()
                .get("/" + bucket + "/" + key)
            .then()
                .statusCode(200)
                .extract().response();
    }
}
