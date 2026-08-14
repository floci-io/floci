package io.github.hectorvent.floci.core.common;

import io.quarkus.test.junit.QuarkusTest;
import io.restassured.RestAssured;
import io.restassured.config.EncoderConfig;
import io.restassured.http.ContentType;
import io.restassured.parsing.Parser;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.startsWith;

/**
 * Malformed client input on a JSON-protocol service must yield a 4xx typed error, never a 500
 * InternalFailure. Both JSON dispatchers already render {@code AwsException} correctly; the leaks
 * are (a) an unparseable request body reaching {@code objectMapper.readTree} and (b) service
 * handlers that decode base64 / parse ints from client input without catching the failure. Each
 * of those escapes the dispatcher's generic {@code catch (Exception)} and surfaces as 500.
 *
 * <p>Every case here asserts the status is a 4xx (never 500) and, where AWS defines it, the exact
 * error code. Inputs are crafted to fail on the malformed value before any resource lookup, so no
 * seeding is required.
 */
@QuarkusTest
class JsonMalformedInputIntegrationTest {

    private static final String JSON_1_1 = "application/x-amz-json-1.1";
    private static final String JSON_1_0 = "application/x-amz-json-1.0";

    @BeforeAll
    static void registerJsonParsers() {
        RestAssured.registerParser(JSON_1_1, Parser.JSON);
        RestAssured.registerParser(JSON_1_0, Parser.JSON);
    }

    private static io.restassured.specification.RequestSpecification req(String contentType, String target, String body) {
        // RestAssured has no built-in serializer for the x-amz-json content types; send the raw body as text.
        return given()
                .config(RestAssured.config().encoderConfig(EncoderConfig.encoderConfig()
                        .encodeContentTypeAs(JSON_1_1, ContentType.TEXT)
                        .encodeContentTypeAs(JSON_1_0, ContentType.TEXT)))
                .header("X-Amz-Target", target)
                .contentType(contentType)
                .header("Authorization",
                        "AWS4-HMAC-SHA256 Credential=test/20260227/us-east-1/service/aws4_request")
                .body(body);
    }

    // ── Dispatcher: an unparseable body is a client deserialization error (both controllers) ──

    @Test
    void malformedJsonBodyOnJson11IsSerializationException() {
        req(JSON_1_1, "TrentService.CreateKey", "{ this is not json")
        .when().post("/")
        .then()
            .statusCode(400)
            .body("__type", equalTo("SerializationException"));
    }

    @Test
    void malformedJsonBodyOnJson10IsSerializationException() {
        req(JSON_1_0, "DynamoDB_20120810.ListTables", "{ not json ]")
        .when().post("/")
        .then()
            .statusCode(400)
            .body("__type", equalTo("SerializationException"));
    }

    @Test
    void trailingContentAfterValidJsonOnJson11IsSerializationException() {
        // Jackson's default readTree stops at the first complete value; without
        // FAIL_ON_TRAILING_TOKENS the garbage after {} is silently ignored.
        req(JSON_1_1, "TrentService.CreateKey", "{} not-json")
        .when().post("/")
        .then()
            .statusCode(400)
            .body("__type", equalTo("SerializationException"));
    }

    @Test
    void trailingContentAfterValidJsonOnJson10IsSerializationException() {
        req(JSON_1_0, "DynamoDB_20120810.ListTables", "{}{\"second\":\"document\"}")
        .when().post("/")
        .then()
            .statusCode(400)
            .body("__type", equalTo("SerializationException"));
    }

    @Test
    void trailingWhitespaceAfterValidJsonIsAccepted() {
        req(JSON_1_0, "DynamoDB_20120810.ListTables", "{}   \n")
        .when().post("/")
        .then()
            .statusCode(200);
    }

    // ── KMS: base64 blob fields ──

    @Test
    void kmsDecryptMalformedCiphertextIsNot500() {
        req(JSON_1_1, "TrentService.Decrypt", "{\"CiphertextBlob\":\"!!!not-base64!!!\"}")
        .when().post("/")
        .then()
            .statusCode(400)
            .body("__type", equalTo("SerializationException"));
    }

    @Test
    void kmsEncryptMalformedPlaintextIsNot500() {
        req(JSON_1_1, "TrentService.Encrypt", "{\"KeyId\":\"k\",\"Plaintext\":\"@@notbase64@@\"}")
        .when().post("/")
        .then()
            .statusCode(400)
            .body("__type", equalTo("SerializationException"));
    }

    // ── Kinesis: shard iterator (opaque token we mint) and the PutRecord data blob ──

    @Test
    void kinesisGetRecordsMalformedShardIteratorIsInvalidArgument() {
        req(JSON_1_1, "Kinesis_20131202.GetRecords", "{\"ShardIterator\":\"!!!not-base64!!!\"}")
        .when().post("/")
        .then()
            .statusCode(400)
            .body("__type", startsWith("InvalidArgumentException"));
    }

    @Test
    void kinesisGetRecordsIteratorWithNonNumericIndexIsInvalidArgument() {
        // valid base64 of "stream|shard|type|seq|NOTANUMBER" — decodes fine, parseInt(index) would throw
        String iterator = java.util.Base64.getEncoder()
                .encodeToString("s|shard-0|AT|0|xyz".getBytes(java.nio.charset.StandardCharsets.UTF_8));
        req(JSON_1_1, "Kinesis_20131202.GetRecords", "{\"ShardIterator\":\"" + iterator + "\"}")
        .when().post("/")
        .then()
            .statusCode(400)
            .body("__type", startsWith("InvalidArgumentException"));
    }

    @Test
    void kinesisPutRecordMalformedDataIsSerializationException() {
        req(JSON_1_1, "Kinesis_20131202.PutRecord",
                "{\"StreamName\":\"s\",\"PartitionKey\":\"p\",\"Data\":\"@@notbase64@@\"}")
        .when().post("/")
        .then()
            .statusCode(400)
            .body("__type", equalTo("SerializationException"));
    }

    // ── DynamoDB Streams: iterator base64 is guarded, the position parseInt was not ──

    @Test
    void dynamoDbStreamsGetRecordsIteratorWithNonNumericPositionIsNot500() {
        // valid base64 of "arn|NOTANUMBER" — decodeIterator succeeds, Integer.parseInt(position) would throw
        String iterator = java.util.Base64.getEncoder()
                .encodeToString("arn:aws:dynamodb:us-east-1:0:table/T/stream/x|notanumber"
                        .getBytes(java.nio.charset.StandardCharsets.UTF_8));
        req(JSON_1_0, "DynamoDBStreams_20120810.GetRecords", "{\"ShardIterator\":\"" + iterator + "\"}")
        .when().post("/")
        .then()
            .statusCode(400)
            .body("__type", not(equalTo("InternalFailure")));
    }
}
