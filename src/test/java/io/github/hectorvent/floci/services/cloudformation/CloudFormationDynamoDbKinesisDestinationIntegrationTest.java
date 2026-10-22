package io.github.hectorvent.floci.services.cloudformation;

import io.github.hectorvent.floci.core.common.XmlParser;
import io.github.hectorvent.floci.testing.CfnStackWaits;
import io.github.hectorvent.floci.testing.RestAssuredJsonUtils;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.response.ValidatableResponse;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.contains;
import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * An {@code AWS::DynamoDB::Table} whose {@code KinesisStreamSpecification} names a Kinesis data
 * stream forwards its changes there: the destination is ACTIVE after the create, and an update that
 * drops the block disables it, as on AWS.
 */
@QuarkusTest
class CloudFormationDynamoDbKinesisDestinationIntegrationTest {

    private static final String CFN_AUTH =
            "AWS4-HMAC-SHA256 Credential=test/20260205/us-east-1/cloudformation/aws4_request";
    private static final String DDB_AUTH =
            "AWS4-HMAC-SHA256 Credential=test/20260205/us-east-1/dynamodb/aws4_request";

    /** A stream named by the first argument and a table named by the second, with any extra table members. */
    private static final String TEMPLATE = """
            {
              "Resources": {
                "Changes": {
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
              },
              "Outputs": {
                "StreamArn": {"Value": {"Fn::GetAtt": ["Changes", "Arn"]}}
              }
            }
            """;

    private static final String FORWARDED = """
            ,
                    "KinesisStreamSpecification": {"StreamArn": {"Fn::GetAtt": ["Changes", "Arn"]}}""";

    @BeforeAll
    static void configureRestAssured() {
        RestAssuredJsonUtils.configureAwsContentTypes();
    }

    @Test
    void theDeclaredStreamReceivesTheTableAndDroppingTheBlockDisablesIt() {
        String suffix = Long.toString(System.nanoTime(), 36);
        String stack = "cfn-ddb-kinesis-" + suffix;
        String stream = "ddb-changes-" + suffix;
        String table = "kinesis-table-" + suffix;

        cloudFormation(stack, "CreateStack", TEMPLATE.formatted(stream, table, FORWARDED));
        CfnStackWaits.StackState created = CfnStackWaits.awaitTerminal(stack, CFN_AUTH);
        assertEquals("CREATE_COMPLETE", created.status(), created.reason());
        String streamArn = streamArn(stack);

        describeKinesisStreamingDestination(table)
            .body("KinesisDataStreamDestinations.StreamArn", contains(streamArn))
            .body("KinesisDataStreamDestinations.DestinationStatus", contains("ACTIVE"));

        cloudFormation(stack, "UpdateStack", TEMPLATE.formatted(stream, table, ""));
        CfnStackWaits.StackState updated = CfnStackWaits.awaitTerminal(stack, CFN_AUTH);
        assertEquals("UPDATE_COMPLETE", updated.status(), updated.reason());
        describeKinesisStreamingDestination(table)
            .body("KinesisDataStreamDestinations.StreamArn", contains(streamArn))
            .body("KinesisDataStreamDestinations.DestinationStatus", contains("DISABLED"));

        deleteStack(stack);
    }

    private static String streamArn(String stack) {
        String xml = given()
            .contentType("application/x-www-form-urlencoded")
            .header("Authorization", CFN_AUTH)
            .formParam("Action", "DescribeStacks")
            .formParam("StackName", stack)
        .when().post("/").then().statusCode(200).extract().asString();
        return XmlParser.extractPairs(xml, "Outputs", "OutputKey", "OutputValue").get("StreamArn");
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

    private ValidatableResponse describeKinesisStreamingDestination(String table) {
        return given()
            .contentType("application/x-amz-json-1.0")
            .header("Authorization", DDB_AUTH)
            .header("X-Amz-Target", "DynamoDB_20120810.DescribeKinesisStreamingDestination")
            .body("{\"TableName\": \"" + table + "\"}")
        .when().post("/").then().statusCode(200);
    }
}
