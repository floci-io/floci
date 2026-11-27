package io.floci.aws.services.kinesisvideo;

import io.quarkus.test.junit.QuarkusTest;
import io.restassured.response.Response;
import jakarta.inject.Inject;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.instanceOf;
import static org.hamcrest.Matchers.matchesPattern;
import static org.hamcrest.Matchers.notNullValue;
import static org.hamcrest.Matchers.nullValue;

@QuarkusTest
class KinesisVideoIntegrationTest {
    private static final String ACCOUNT = "111111111111";
    private static final String AUTH = "AWS4-HMAC-SHA256 Credential=" + ACCOUNT
            + "/20260101/us-east-1/kinesisvideo/aws4_request, SignedHeaders=host, Signature=abc";

    @Inject
    KinesisVideoService kinesisVideoService;

    @BeforeEach
    void resetStreams() {
        kinesisVideoService.clear();
    }

    @Test
    void streamLifecycle_createDescribeListDelete() {
        String arn = post("/createStream", "{\"StreamName\":\"camera-1\",\"MediaType\":\"video/h264\","
                + "\"DataRetentionInHours\":24,\"DeviceName\":\"device-1\",\"Tags\":{\"env\":\"dev\"}}")
                .then().statusCode(200)
                .body("StreamARN", matchesPattern(
                        "arn:aws:kinesisvideo:us-east-1:" + ACCOUNT + ":stream/camera-1/[0-9]+"))
                .extract().path("StreamARN");

        post("/describeStream", "{\"StreamName\":\"camera-1\"}")
                .then().statusCode(200)
                .body("StreamInfo.StreamName", equalTo("camera-1"))
                .body("StreamInfo.StreamARN", equalTo(arn))
                .body("StreamInfo.DeviceName", equalTo("device-1"))
                .body("StreamInfo.MediaType", equalTo("video/h264"))
                .body("StreamInfo.KmsKeyId", equalTo(
                        "arn:aws:kms:us-east-1:" + ACCOUNT + ":alias/aws/kinesisvideo"))
                .body("StreamInfo.Status", equalTo("ACTIVE"))
                .body("StreamInfo.DataRetentionInHours", equalTo(24))
                .body("StreamInfo.Version", notNullValue())
                .body("StreamInfo.CreationTime", instanceOf(Number.class));

        post("/describeStream", "{\"StreamARN\":\"" + arn + "\"}")
                .then().statusCode(200)
                .body("StreamInfo.StreamName", equalTo("camera-1"));

        post("/listStreams", "{}")
                .then().statusCode(200)
                .body("StreamInfoList", hasSize(1))
                .body("StreamInfoList[0].StreamARN", equalTo(arn))
                .body("NextToken", nullValue());

        post("/deleteStream", "{\"StreamARN\":\"" + arn + "\"}")
                .then().statusCode(200);

        post("/describeStream", "{\"StreamName\":\"camera-1\"}")
                .then().statusCode(404)
                .body("__type", equalTo("ResourceNotFoundException"));
        post("/listStreams", "{}")
                .then().statusCode(200)
                .body("StreamInfoList", hasSize(0));
    }

    @Test
    void createStream_duplicateName_returnsResourceInUse() {
        post("/createStream", "{\"StreamName\":\"camera-1\"}").then().statusCode(200);

        post("/createStream", "{\"StreamName\":\"camera-1\"}")
                .then().statusCode(400)
                .body("__type", equalTo("ResourceInUseException"));
    }

    @Test
    void requestsBreakingTheModelConstraints_returnInvalidArgument() {
        assertInvalidArgument("/createStream", "{}", "StreamName");
        assertInvalidArgument("/createStream", "{\"StreamName\":\"bad name\"}", "StreamName");
        assertInvalidArgument("/createStream", "{\"StreamName\":\"" + "a".repeat(257) + "\"}", "StreamName");
        assertInvalidArgument("/createStream", "{\"StreamName\":\"camera-1\",\"MediaType\":\"h264\"}",
                "MediaType");
        assertInvalidArgument("/createStream", "{\"StreamName\":\"camera-1\",\"DataRetentionInHours\":-1}",
                "DataRetentionInHours");
        assertInvalidArgument("/createStream", "{\"StreamName\":\"camera-1\",\"Tags\":{}}", "Tags");
        assertInvalidArgument("/deleteStream", "{}", "StreamARN");
        assertInvalidArgument("/deleteStream", "{\"StreamARN\":\"not-an-arn\"}", "StreamARN");
        assertInvalidArgument("/deleteStream", "{\"StreamARN\":\"arn:aws:kinesisvideo:us-east-1:" + ACCOUNT
                + ":stream/camera-1/1\",\"CurrentVersion\":\"not valid\"}", "CurrentVersion");
        assertInvalidArgument("/listStreams", "{\"MaxResults\":0}", "MaxResults");
        assertInvalidArgument("/listStreams", "{\"MaxResults\":10001}", "MaxResults");
        assertInvalidArgument("/listStreams", "{\"StreamNameCondition\":{\"ComparisonOperator\":\"ENDS_WITH\","
                + "\"ComparisonValue\":\"camera\"}}", "StreamNameCondition.ComparisonOperator");

        post("/listStreams", "{}").then().statusCode(200).body("StreamInfoList", hasSize(0));
    }

    @Test
    void createStream_moreThanFiftyTags_returnsTagsPerResourceExceededLimit() {
        StringBuilder tags = new StringBuilder();
        for (int i = 0; i < 51; i++) {
            tags.append(i == 0 ? "" : ",").append("\"key").append(i).append("\":\"value\"");
        }

        post("/createStream", "{\"StreamName\":\"camera-1\",\"Tags\":{" + tags + "}}")
                .then().statusCode(400)
                .body("__type", equalTo("TagsPerResourceExceededLimitException"));
    }

    @Test
    void memberOfTheWrongType_returnsSerializationException() {
        post("/createStream", "{\"StreamName\":\"camera-1\",\"DataRetentionInHours\":\"24\"}")
                .then().statusCode(400)
                .body("__type", equalTo("SerializationException"));
        post("/createStream", "{\"StreamName\":5}")
                .then().statusCode(400)
                .body("__type", equalTo("SerializationException"));
    }

    @Test
    void deleteStream_staleVersion_returnsVersionMismatch() {
        String arn = post("/createStream", "{\"StreamName\":\"camera-1\"}")
                .then().statusCode(200).extract().path("StreamARN");

        post("/deleteStream", "{\"StreamARN\":\"" + arn + "\",\"CurrentVersion\":\"stale1\"}")
                .then().statusCode(400)
                .body("__type", equalTo("VersionMismatchException"));

        String version = post("/describeStream", "{\"StreamARN\":\"" + arn + "\"}")
                .then().statusCode(200).extract().path("StreamInfo.Version");
        post("/deleteStream", "{\"StreamARN\":\"" + arn + "\",\"CurrentVersion\":\"" + version + "\"}")
                .then().statusCode(200);
    }

    @Test
    void deleteStream_unknownStream_returnsResourceNotFound() {
        post("/deleteStream", "{\"StreamARN\":\"arn:aws:kinesisvideo:us-east-1:" + ACCOUNT
                + ":stream/missing/1\"}")
                .then().statusCode(404)
                .body("__type", equalTo("ResourceNotFoundException"));
    }

    @Test
    void listStreams_nameConditionAndPagination() {
        post("/createStream", "{\"StreamName\":\"camera-b\"}").then().statusCode(200);
        post("/createStream", "{\"StreamName\":\"camera-a\"}").then().statusCode(200);
        post("/createStream", "{\"StreamName\":\"doorbell\"}").then().statusCode(200);

        String condition = "\"StreamNameCondition\":{\"ComparisonOperator\":\"BEGINS_WITH\","
                + "\"ComparisonValue\":\"camera\"}";
        String nextToken = post("/listStreams", "{\"MaxResults\":1," + condition + "}")
                .then().statusCode(200)
                .body("StreamInfoList.StreamName", contains("camera-a"))
                .body("NextToken", notNullValue())
                .extract().path("NextToken");

        post("/listStreams", "{\"MaxResults\":1,\"NextToken\":\"" + nextToken + "\"," + condition + "}")
                .then().statusCode(200)
                .body("StreamInfoList.StreamName", contains("camera-b"))
                .body("NextToken", nullValue());
    }

    private static void assertInvalidArgument(String path, String body, String member) {
        post(path, body).then().statusCode(400)
                .body("__type", equalTo("InvalidArgumentException"))
                .body("message", containsString("'" + member + "'"));
    }

    private static Response post(String path, String body) {
        return given().contentType("application/json").header("Authorization", AUTH)
                .body(body).post(path);
    }
}
