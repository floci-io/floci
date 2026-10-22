package io.github.hectorvent.floci.services.firehose;

import io.github.hectorvent.floci.testing.RestAssuredJsonUtils;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.response.Response;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.Base64;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.notNullValue;

/**
 * Wire-level enforcement of the input constraints the 2015-08-04 model pins on
 * the pre-existing Firehose operations: the DeliveryStreamType enum, and the
 * list bounds on Tags (1-50), Records (1-500) and TagKeys (1-50). All of these
 * were previously accepted verbatim.
 */
@QuarkusTest
class FirehoseInputConstraintConsumerTest {

    private static final String CONTENT_TYPE = "application/x-amz-json-1.1";
    private static final String TARGET_PREFIX = "Firehose_20150804.";
    private static final int MAX_RECORD_DATA_BYTES = 1_024_000;

    @BeforeAll
    static void configureRestAssured() {
        RestAssuredJsonUtils.configureAwsContentTypes();
    }

    private static Response call(String action, String body) {
        return given()
                .contentType(CONTENT_TYPE)
                .header("X-Amz-Target", TARGET_PREFIX + action)
                .body(body)
            .when()
                .post("/");
    }

    private static void createStream(String name) {
        call("CreateDeliveryStream", "{\"DeliveryStreamName\":\"" + name + "\"}")
                .then().statusCode(200);
    }

    private static String tagArray(int n) {
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < n; i++) {
            if (i > 0) {
                sb.append(',');
            }
            sb.append("{\"Key\":\"k").append(i).append("\",\"Value\":\"v\"}");
        }
        return sb.append(']').toString();
    }

    @Test
    void createDeliveryStream_unknownType_returnsInvalidArgument() {
        call("CreateDeliveryStream", "{\"DeliveryStreamName\":\"ab-badtype\","
                + "\"DeliveryStreamType\":\"CarrierPigeon\"}")
        .then()
            .statusCode(400)
            .body("__type", equalTo("InvalidArgumentException"));
    }

    @Test
    void createDeliveryStream_tooManyTags_returnsInvalidArgument() {
        call("CreateDeliveryStream", "{\"DeliveryStreamName\":\"ab-manytags\","
                + "\"Tags\":" + tagArray(51) + "}")
        .then()
            .statusCode(400)
            .body("__type", equalTo("InvalidArgumentException"));
    }

    @Test
    void putRecordBatch_emptyRecords_returnsInvalidArgument() {
        createStream("ab-batch-empty");
        call("PutRecordBatch", "{\"DeliveryStreamName\":\"ab-batch-empty\",\"Records\":[]}")
        .then()
            .statusCode(400)
            .body("__type", equalTo("InvalidArgumentException"));
    }

    @Test
    void putRecord_recordWithoutData_returnsValidationAndStreamKeepsAcceptingRecords() {
        createStream("ab-no-data");
        call("PutRecord", "{\"DeliveryStreamName\":\"ab-no-data\",\"Record\":{}}")
        .then()
            .statusCode(400)
            .body("__type", equalTo("ValidationException"))
            .body("message", containsString("'record.data'"));

        call("PutRecord", "{\"DeliveryStreamName\":\"ab-no-data\",\"Record\":{\"Data\":\"aGVsbG8=\"}}")
        .then()
            .statusCode(200)
            .body("RecordId", notNullValue());
    }

    @Test
    void putRecord_missingRecord_returnsValidation() {
        createStream("ab-no-record");
        call("PutRecord", "{\"DeliveryStreamName\":\"ab-no-record\"}")
        .then()
            .statusCode(400)
            .body("__type", equalTo("ValidationException"))
            .body("message", containsString("'record'"));
    }

    @Test
    void putRecord_dataOverTheModeledMaximum_returnsValidation() {
        createStream("ab-big-data");
        String data = Base64.getEncoder().encodeToString(new byte[MAX_RECORD_DATA_BYTES + 1]);
        call("PutRecord", "{\"DeliveryStreamName\":\"ab-big-data\",\"Record\":{\"Data\":\"" + data + "\"}}")
        .then()
            .statusCode(400)
            .body("__type", equalTo("ValidationException"))
            .body("message", containsString("'record.data'"));

        String atLimit = Base64.getEncoder().encodeToString(new byte[MAX_RECORD_DATA_BYTES]);
        call("PutRecord", "{\"DeliveryStreamName\":\"ab-big-data\",\"Record\":{\"Data\":\"" + atLimit + "\"}}")
        .then()
            .statusCode(200);
    }

    @Test
    void putRecordBatch_entryWithoutData_returnsValidationAndStreamKeepsAcceptingRecords() {
        createStream("ab-batch-no-data");
        call("PutRecordBatch", "{\"DeliveryStreamName\":\"ab-batch-no-data\","
                + "\"Records\":[{\"Data\":\"aGVsbG8=\"},{}]}")
        .then()
            .statusCode(400)
            .body("__type", equalTo("ValidationException"))
            .body("message", containsString("'records.2.member.data'"));

        call("PutRecordBatch", "{\"DeliveryStreamName\":\"ab-batch-no-data\","
                + "\"Records\":[{\"Data\":\"aGVsbG8=\"}]}")
        .then()
            .statusCode(200)
            .body("FailedPutCount", equalTo(0));
    }

    @Test
    void putRecordAndPutRecordBatch_kinesisStreamAsSource_returnInvalidArgument() {
        call("CreateDeliveryStream", "{\"DeliveryStreamName\":\"ab-kinesis-sourced\","
                + "\"DeliveryStreamType\":\"KinesisStreamAsSource\","
                + "\"KinesisStreamSourceConfiguration\":{"
                + "\"KinesisStreamARN\":\"arn:aws:kinesis:us-east-1:000000000000:stream/ab-kinesis-source\","
                + "\"RoleARN\":\"arn:aws:iam::000000000000:role/firehose\"}}")
        .then()
            .statusCode(200);

        call("PutRecord", "{\"DeliveryStreamName\":\"ab-kinesis-sourced\",\"Record\":{\"Data\":\"aGVsbG8=\"}}")
        .then()
            .statusCode(400)
            .body("__type", equalTo("InvalidArgumentException"));
        call("PutRecordBatch", "{\"DeliveryStreamName\":\"ab-kinesis-sourced\","
                + "\"Records\":[{\"Data\":\"aGVsbG8=\"}]}")
        .then()
            .statusCode(400)
            .body("__type", equalTo("InvalidArgumentException"));
    }

    @Test
    void tagDeliveryStream_emptyAndOversizedTags_returnInvalidArgument() {
        createStream("ab-tag-bounds");
        call("TagDeliveryStream", "{\"DeliveryStreamName\":\"ab-tag-bounds\",\"Tags\":[]}")
        .then()
            .statusCode(400)
            .body("__type", equalTo("InvalidArgumentException"));
        call("TagDeliveryStream", "{\"DeliveryStreamName\":\"ab-tag-bounds\",\"Tags\":" + tagArray(51) + "}")
        .then()
            .statusCode(400)
            .body("__type", equalTo("InvalidArgumentException"));
    }

    @Test
    void untagDeliveryStream_emptyTagKeys_returnsInvalidArgument() {
        createStream("ab-untag-bounds");
        call("UntagDeliveryStream", "{\"DeliveryStreamName\":\"ab-untag-bounds\",\"TagKeys\":[]}")
        .then()
            .statusCode(400)
            .body("__type", equalTo("InvalidArgumentException"));
    }
}
