package io.floci.aws.services.pipes;

import io.floci.aws.testing.RestAssuredJsonUtils;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.path.json.JsonPath;
import io.restassured.response.Response;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.UUID;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;
import static org.junit.jupiter.api.Assertions.assertEquals;

@QuarkusTest
class PipesKinesisTargetIntegrationTest {

    private static final String SQS_CONTENT_TYPE = "application/x-www-form-urlencoded";
    private static final String KINESIS_CONTENT_TYPE = "application/x-amz-json-1.1";
    private static final String ROLE = "arn:aws:iam::000000000000:role/pipe-role";

    @BeforeAll
    static void configureRestAssured() {
        RestAssuredJsonUtils.configureAwsContentTypes();
    }

    @Test
    void streamTargetReceivesTheRenderedTemplateUnderThePartitionKeyReadOffTheEvent() {
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        String sourceName = "pipe-kin-src-" + suffix;
        String streamName = "pipe-kin-stream-" + suffix;
        String pipeName = "pipe-kin-" + suffix;
        String sourceUrl = createQueue(sourceName);
        kinesis("CreateStream", "{\"StreamName\": \"" + streamName + "\", \"ShardCount\": 1}");
        try {
            given()
                .contentType("application/json")
                .body("""
                    {
                        "Source": "arn:aws:sqs:us-east-1:000000000000:%s",
                        "Target": "arn:aws:kinesis:us-east-1:000000000000:stream/%s",
                        "RoleArn": "%s",
                        "TargetParameters": {
                            "InputTemplate": "{\\"t\\": <$.body.k>}",
                            "KinesisStreamParameters": {
                                "PartitionKey": "$.body.k"
                            }
                        }
                    }
                    """.formatted(sourceName, streamName, ROLE))
            .when()
                .post("/v1/pipes/" + pipeName)
            .then()
                .statusCode(200);

            given()
                .contentType(SQS_CONTENT_TYPE)
                .formParam("Action", "SendMessage")
                .formParam("QueueUrl", sourceUrl)
                .formParam("MessageBody", "{\"k\":\"key1\"}")
            .when()
                .post("/")
            .then()
                .statusCode(200);

            JsonPath record = readFirstRecord(streamName);
            assertEquals("key1", record.getString("Records[0].PartitionKey"));
            assertEquals("{\"t\": \"key1\"}", new String(
                    Base64.getDecoder().decode(record.getString("Records[0].Data")), StandardCharsets.UTF_8));
        } finally {
            given().when().delete("/v1/pipes/" + pipeName);
            deleteQueue(sourceUrl);
            kinesis("DeleteStream", "{\"StreamName\": \"" + streamName + "\"}");
        }
    }

    @Test
    void createPipeForAKinesisTargetWithoutTargetParametersIsRefusedWithTheFieldList() {
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        String sourceName = "pipe-kin-src-" + suffix;
        String pipeName = "pipe-kin-refused-" + suffix;
        String sourceUrl = createQueue(sourceName);
        try {
            given()
                .contentType("application/json")
                .body("""
                    {
                        "Source": "arn:aws:sqs:us-east-1:000000000000:%s",
                        "Target": "arn:aws:kinesis:us-east-1:000000000000:stream/any-stream",
                        "RoleArn": "%s"
                    }
                    """.formatted(sourceName, ROLE))
            .when()
                .post("/v1/pipes/" + pipeName)
            .then()
                .statusCode(400)
                .body("__type", equalTo("ValidationException"))
                .body("message", equalTo("{\"ValidationErrorFields\":[{\"Name\":"
                        + "\"TargetParameters.KinesisStreamParameters\","
                        + "\"Message\":\"Missing required target parameter.\"}]}"))
                .body("fieldList.size()", equalTo(1))
                .body("fieldList[0].name", equalTo("TargetParameters.KinesisStreamParameters"))
                .body("fieldList[0].message", equalTo("Missing required target parameter."));
        } finally {
            given().when().delete("/v1/pipes/" + pipeName);
            deleteQueue(sourceUrl);
        }
    }

    private static Response kinesis(String action, String body) {
        return given()
                .header("X-Amz-Target", "Kinesis_20131202." + action)
                .contentType(KINESIS_CONTENT_TYPE)
                .body(body)
            .when()
                .post("/");
    }

    private static JsonPath readFirstRecord(String streamName) {
        return PipesTargetWaits.awaitTarget("a record in stream " + streamName, () -> {
            String iterator = kinesis("GetShardIterator", "{\"StreamName\": \"" + streamName
                    + "\", \"ShardId\": \"shardId-000000000000\", \"ShardIteratorType\": \"TRIM_HORIZON\"}")
                    .then().statusCode(200).extract().jsonPath().getString("ShardIterator");
            return kinesis("GetRecords", "{\"ShardIterator\": \"" + iterator + "\"}")
                    .then().statusCode(200).extract().jsonPath();
        }, result -> !result.getList("Records").isEmpty());
    }

    private static String createQueue(String name) {
        return given()
                .contentType(SQS_CONTENT_TYPE)
                .formParam("Action", "CreateQueue")
                .formParam("QueueName", name)
            .when()
                .post("/")
            .then()
                .statusCode(200).extract().xmlPath()
                .getString("CreateQueueResponse.CreateQueueResult.QueueUrl");
    }

    private static void deleteQueue(String queueUrl) {
        given()
            .contentType(SQS_CONTENT_TYPE)
            .formParam("Action", "DeleteQueue")
            .formParam("QueueUrl", queueUrl)
        .when()
            .post("/");
    }
}
