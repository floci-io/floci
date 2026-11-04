package io.github.hectorvent.floci.services.cloudformation;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.hectorvent.floci.services.dynamodb.KinesisStreamingForwarder;
import io.github.hectorvent.floci.testing.CfnStackWaits;
import io.github.hectorvent.floci.testing.RestAssuredJsonUtils;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.response.ValidatableResponse;
import jakarta.inject.Inject;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.Base64;
import java.util.List;
import java.util.function.Function;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

/**
 * {@code KinesisStreamSpecification} on {@code AWS::DynamoDB::Table}: a stack that declares it gets
 * a table whose item changes reach the declared Kinesis stream, an update that removes the block
 * turns the destination off, and a stream that does not exist fails the create without leaving the
 * table behind.
 */
@QuarkusTest
class CloudFormationDynamoDbKinesisStreamSpecificationIntegrationTest {

    private static final String CFN_AUTH =
            "AWS4-HMAC-SHA256 Credential=test/20260205/us-east-1/cloudformation/aws4_request";
    private static final String DDB_AUTH =
            "AWS4-HMAC-SHA256 Credential=test/20260205/us-east-1/dynamodb/aws4_request";
    private static final String KINESIS_AUTH =
            "AWS4-HMAC-SHA256 Credential=test/20260205/us-east-1/kinesis/aws4_request";

    private static final String TEMPLATE = """
            {
              "Resources": {
                "Events": {
                  "Type": "AWS::Kinesis::Stream",
                  "Properties": {"Name": "%s", "ShardCount": 1}
                },
                "Orders": {
                  "Type": "AWS::DynamoDB::Table",
                  "Properties": {
                    "TableName": "%s",
                    "AttributeDefinitions": [{"AttributeName": "pk", "AttributeType": "S"}],
                    "KeySchema": [{"AttributeName": "pk", "KeyType": "HASH"}],
                    "BillingMode": "PAY_PER_REQUEST"%s
                  }
                }
              }
            }
            """;

    private static final String FORWARDED =
            ",\n\"KinesisStreamSpecification\": {\"StreamArn\": {\"Fn::GetAtt\": [\"Events\", \"Arn\"]}}";

    private final ObjectMapper mapper = new ObjectMapper();

    @Inject
    KinesisStreamingForwarder forwarder;

    @BeforeAll
    static void configureRestAssured() {
        RestAssuredJsonUtils.configureAwsContentTypes();
    }

    @Test
    void createForwardsItemChangesToTheDeclaredStream() throws Exception {
        String suffix = Long.toString(System.nanoTime(), 36);
        String stack = "cfn-ddb-kds-" + suffix;
        String stream = "kds-events-" + suffix;
        String table = "kds-table-" + suffix;

        cloudFormation(stack, "CreateStack", TEMPLATE.formatted(stream, table, FORWARDED));
        assertEquals("CREATE_COMPLETE", CfnStackWaits.awaitTerminal(stack, CFN_AUTH).status());
        dynamoDb("DescribeKinesisStreamingDestination", "{\"TableName\": \"" + table + "\"}")
            .statusCode(200)
            .body("KinesisDataStreamDestinations.size()", equalTo(1))
            .body("KinesisDataStreamDestinations[0].StreamArn", containsString(":stream/" + stream))
            .body("KinesisDataStreamDestinations[0].DestinationStatus", equalTo("ACTIVE"));

        dynamoDb("PutItem", "{\"TableName\": \"" + table + "\", \"Item\": {\"pk\": {\"S\": \"k1\"}}}")
            .statusCode(200);
        forwarder.awaitIdle(Duration.ofSeconds(5));

        List<String> records = kinesisRecords(stream);
        assertFalse(records.isEmpty(), "Expected the PutItem to reach " + stream);
        JsonNode payload = mapper.readTree(Base64.getDecoder().decode(records.get(0)));
        assertEquals("INSERT", payload.path("eventName").asText());
        assertEquals(table, payload.path("tableName").asText());

        deleteStack(stack);
    }

    @Test
    void removingTheBlockDisablesTheDestination() {
        String suffix = Long.toString(System.nanoTime(), 36);
        String stack = "cfn-ddb-kds-rm-" + suffix;
        String stream = "kds-rm-events-" + suffix;
        String table = "kds-rm-table-" + suffix;

        cloudFormation(stack, "CreateStack", TEMPLATE.formatted(stream, table, FORWARDED));
        assertEquals("CREATE_COMPLETE", CfnStackWaits.awaitTerminal(stack, CFN_AUTH).status());

        cloudFormation(stack, "UpdateStack", TEMPLATE.formatted(stream, table, ""));
        assertEquals("UPDATE_COMPLETE", CfnStackWaits.awaitTerminal(stack, CFN_AUTH).status());
        dynamoDb("DescribeKinesisStreamingDestination", "{\"TableName\": \"" + table + "\"}")
            .statusCode(200)
            .body("KinesisDataStreamDestinations[0].DestinationStatus", equalTo("DISABLED"));

        deleteStack(stack);
    }

    /** A block that is not an object is refused, not read as a removed block that turns forwarding off. */
    @Test
    void anUpdateWithABlockThatIsNotAnObjectKeepsTheDestinationActive() {
        String suffix = Long.toString(System.nanoTime(), 36);
        String stack = "cfn-ddb-kds-scalar-" + suffix;
        String stream = "kds-scalar-events-" + suffix;
        String table = "kds-scalar-table-" + suffix;

        cloudFormation(stack, "CreateStack", TEMPLATE.formatted(stream, table, FORWARDED));
        assertEquals("CREATE_COMPLETE", CfnStackWaits.awaitTerminal(stack, CFN_AUTH).status());

        cloudFormation(stack, "UpdateStack",
                TEMPLATE.formatted(stream, table, ",\n\"KinesisStreamSpecification\": [\"off\"]"));
        assertEquals("UPDATE_ROLLBACK_COMPLETE", CfnStackWaits.awaitTerminal(stack, CFN_AUTH).status());
        assertEquals("Property [KinesisStreamSpecification] expected type: JSONObject, found: JSONArray",
                CfnStackWaits.failedResourceReason(stack, CFN_AUTH));
        dynamoDb("DescribeKinesisStreamingDestination", "{\"TableName\": \"" + table + "\"}")
            .statusCode(200)
            .body("KinesisDataStreamDestinations.size()", equalTo(1))
            .body("KinesisDataStreamDestinations[0].DestinationStatus", equalTo("ACTIVE"));

        deleteStack(stack);
    }

    @Test
    void changingThePrecisionKeepsForwardingToTheSameStream() throws Exception {
        String suffix = Long.toString(System.nanoTime(), 36);
        String stack = "cfn-ddb-kds-prec-" + suffix;
        String stream = "kds-prec-events-" + suffix;
        String table = "kds-prec-table-" + suffix;
        String microsecond = ",\n\"KinesisStreamSpecification\": {\"StreamArn\": {\"Fn::GetAtt\": [\"Events\", \"Arn\"]},"
                + " \"ApproximateCreationDateTimePrecision\": \"MICROSECOND\"}";

        cloudFormation(stack, "CreateStack", TEMPLATE.formatted(stream, table, FORWARDED));
        assertEquals("CREATE_COMPLETE", CfnStackWaits.awaitTerminal(stack, CFN_AUTH).status());
        dynamoDb("PutItem", "{\"TableName\": \"" + table + "\", \"Item\": {\"pk\": {\"S\": \"before\"}}}")
            .statusCode(200);

        cloudFormation(stack, "UpdateStack", TEMPLATE.formatted(stream, table, microsecond));
        assertEquals("UPDATE_COMPLETE", CfnStackWaits.awaitTerminal(stack, CFN_AUTH).status());
        dynamoDb("DescribeKinesisStreamingDestination", "{\"TableName\": \"" + table + "\"}")
            .statusCode(200)
            .body("KinesisDataStreamDestinations.size()", equalTo(1))
            .body("KinesisDataStreamDestinations[0].DestinationStatus", equalTo("ACTIVE"))
            .body("KinesisDataStreamDestinations[0].ApproximateCreationDateTimePrecision", equalTo("MICROSECOND"));

        dynamoDb("PutItem", "{\"TableName\": \"" + table + "\", \"Item\": {\"pk\": {\"S\": \"after\"}}}")
            .statusCode(200);
        forwarder.awaitIdle(Duration.ofSeconds(5));

        List<String> records = kinesisRecords(stream);
        assertEquals(2, records.size(), "Both writes reach " + stream);
        JsonNode before = mapper.readTree(Base64.getDecoder().decode(records.get(0))).path("dynamodb");
        JsonNode after = mapper.readTree(Base64.getDecoder().decode(records.get(1))).path("dynamodb");
        assertEquals("before", before.path("Keys").path("pk").path("S").asText());
        assertEquals(13, Long.toString(before.path("ApproximateCreationDateTime").asLong()).length());
        assertEquals("after", after.path("Keys").path("pk").path("S").asText());
        assertEquals(16, Long.toString(after.path("ApproximateCreationDateTime").asLong()).length());

        deleteStack(stack);
    }

    @Test
    void aStreamThatDoesNotExistFailsTheCreateAndLeavesNoTable() {
        assertCreateDoesNotStabilizeAndLeavesNoTable("missing", suffix -> "\"arn:aws:kinesis:us-east-1:000000000000"
                + ":stream/no-such-stream-" + suffix + "\"");
    }

    /** Ref of an AWS::Kinesis::Stream is the stream's name, which AWS never makes a destination. */
    @Test
    void aStreamNameFromRefFailsTheCreateAndLeavesNoTable() {
        assertCreateDoesNotStabilizeAndLeavesNoTable("ref", suffix -> "{\"Ref\": \"Events\"}");
    }

    private void assertCreateDoesNotStabilizeAndLeavesNoTable(String label, Function<String, String> streamArn) {
        String suffix = Long.toString(System.nanoTime(), 36);
        String stack = "cfn-ddb-kds-" + label + "-" + suffix;
        String stream = "kds-" + label + "-events-name-long-enough-" + suffix;
        String table = "kds-" + label + "-table-" + suffix;
        String spec = ",\n\"KinesisStreamSpecification\": {\"StreamArn\": " + streamArn.apply(suffix) + "}";

        cloudFormation(stack, "CreateStack", TEMPLATE.formatted(stream, table, spec));
        assertEquals("ROLLBACK_COMPLETE", CfnStackWaits.awaitTerminal(stack, CFN_AUTH).status());
        assertEquals("Resource of type 'AWS::DynamoDB::Table' with identifier '" + table + "' did not stabilize.",
                CfnStackWaits.failedResourceReason(stack, CFN_AUTH));

        dynamoDb("DescribeTable", "{\"TableName\": \"" + table + "\"}")
            .statusCode(400)
            .body("__type", containsString("ResourceNotFoundException"));

        deleteStack(stack);
    }

    private List<String> kinesisRecords(String stream) {
        String shardIterator = kinesis("GetShardIterator", "{\"StreamName\": \"" + stream + "\","
                + " \"ShardId\": \"shardId-000000000000\", \"ShardIteratorType\": \"TRIM_HORIZON\"}")
            .statusCode(200)
            .extract().path("ShardIterator");
        return kinesis("GetRecords", "{\"ShardIterator\": \"" + shardIterator + "\", \"Limit\": 10}")
            .statusCode(200)
            .extract().path("Records.Data");
    }

    private void cloudFormation(String stack, String action, String template) {
        given()
            .contentType("application/x-www-form-urlencoded")
            .header("Authorization", CFN_AUTH)
            .formParam("Action", action)
            .formParam("StackName", stack)
            .formParam("TemplateBody", template)
        .when().post("/").then().statusCode(200);
    }

    private void deleteStack(String stack) {
        given()
            .contentType("application/x-www-form-urlencoded")
            .header("Authorization", CFN_AUTH)
            .formParam("Action", "DeleteStack")
            .formParam("StackName", stack)
        .when().post("/").then().statusCode(200);
        CfnStackWaits.awaitStackDeleted(stack, CFN_AUTH);
    }

    private ValidatableResponse dynamoDb(String action, String body) {
        return given()
            .contentType("application/x-amz-json-1.0")
            .header("Authorization", DDB_AUTH)
            .header("X-Amz-Target", "DynamoDB_20120810." + action)
            .body(body)
        .when().post("/").then();
    }

    private ValidatableResponse kinesis(String action, String body) {
        return given()
            .contentType("application/x-amz-json-1.1")
            .header("Authorization", KINESIS_AUTH)
            .header("X-Amz-Target", "Kinesis_20131202." + action)
            .body(body)
        .when().post("/").then();
    }
}
