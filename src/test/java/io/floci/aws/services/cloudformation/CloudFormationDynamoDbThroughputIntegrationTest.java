package io.floci.aws.services.cloudformation;

import io.floci.aws.testing.CfnStackWaits;
import io.floci.aws.testing.RestAssuredJsonUtils;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.response.ValidatableResponse;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@code ProvisionedThroughput} and {@code WarmThroughput} on {@code AWS::DynamoDB::Table}, on the
 * table and on a global secondary index.
 */
@QuarkusTest
class CloudFormationDynamoDbThroughputIntegrationTest {

    private static final String CFN_AUTH =
            "AWS4-HMAC-SHA256 Credential=test/20260205/us-east-1/cloudformation/aws4_request";
    private static final String DDB_AUTH =
            "AWS4-HMAC-SHA256 Credential=test/20260205/us-east-1/dynamodb/aws4_request";

    private static final String TEMPLATE = """
            {
              "Resources": {
                "Orders": {
                  "Type": "AWS::DynamoDB::Table",
                  "Properties": {
                    "TableName": "%s",
                    "AttributeDefinitions": [
                      {"AttributeName": "pk", "AttributeType": "S"},
                      {"AttributeName": "gk", "AttributeType": "S"}
                    ],
                    "KeySchema": [{"AttributeName": "pk", "KeyType": "HASH"}],
                    "GlobalSecondaryIndexes": [{
                      "IndexName": "gix1",
                      "KeySchema": [{"AttributeName": "gk", "KeyType": "HASH"}],
                      "Projection": {"ProjectionType": "ALL"}%s
                    }]%s
                  }
                }
              }
            }
            """;

    private static String capacity(int read, int write) {
        return ",\n\"ProvisionedThroughput\": {\"ReadCapacityUnits\": " + read + ", \"WriteCapacityUnits\": "
                + write + "}";
    }

    private static String warm(String members) {
        return ",\n\"WarmThroughput\": {" + members + "}";
    }

    private static String template(String table, String index, String tableProperties) {
        return TEMPLATE.formatted(table, index, tableProperties);
    }

    @BeforeAll
    static void configureRestAssured() {
        RestAssuredJsonUtils.configureAwsContentTypes();
    }

    @Test
    void createAppliesTheTableAndIndexCapacity() {
        String stack = "cfn-ddb-tp-create-" + suffix();
        String table = "tp-create-" + suffix();

        cloudFormation(stack, "CreateStack", template(table, capacity(2, 2), capacity(1, 1)));
        assertEquals("CREATE_COMPLETE", CfnStackWaits.awaitTerminal(stack, CFN_AUTH).status());

        describe(table)
            .body("Table.ProvisionedThroughput.ReadCapacityUnits", equalTo(1))
            .body("Table.ProvisionedThroughput.WriteCapacityUnits", equalTo(1))
            .body("Table.GlobalSecondaryIndexes[0].ProvisionedThroughput.ReadCapacityUnits", equalTo(2))
            .body("Table.GlobalSecondaryIndexes[0].ProvisionedThroughput.WriteCapacityUnits", equalTo(2));

        deleteStack(stack);
    }

    @Test
    void createStoresTheWarmThroughputOfTheTableAndTheIndex() {
        String stack = "cfn-ddb-tp-warm-" + suffix();
        String table = "tp-warm-" + suffix();

        cloudFormation(stack, "CreateStack", template(table,
                capacity(2, 2) + warm("\"WriteUnitsPerSecond\": 4000"),
                capacity(1, 1) + warm("\"ReadUnitsPerSecond\": 5000")));
        assertEquals("CREATE_COMPLETE", CfnStackWaits.awaitTerminal(stack, CFN_AUTH).status());

        describe(table)
            .body("Table.WarmThroughput.ReadUnitsPerSecond", equalTo(5000))
            .body("Table.WarmThroughput.WriteUnitsPerSecond", equalTo(1))
            .body("Table.GlobalSecondaryIndexes[0].WarmThroughput.ReadUnitsPerSecond", equalTo(2))
            .body("Table.GlobalSecondaryIndexes[0].WarmThroughput.WriteUnitsPerSecond", equalTo(4000));

        deleteStack(stack);
    }

    @Test
    void createBelowTheInitialWarmThroughputFailsWithTheApiMessage() {
        String stack = "cfn-ddb-tp-low-" + suffix();
        String table = "tp-low-" + suffix();

        cloudFormation(stack, "CreateStack", template(table, capacity(2, 2),
                capacity(2, 2) + warm("\"ReadUnitsPerSecond\": 1")));
        assertEquals("ROLLBACK_COMPLETE", CfnStackWaits.awaitTerminal(stack, CFN_AUTH).status());
        assertTrue(CfnStackWaits.failedResourceReason(stack, CFN_AUTH).contains("Requested ReadUnitsPerSecond for"
                + " WarmThroughput for table is lower than ReadCapacityUnits of ProvisionedThroughput"));

        deleteStack(stack);
    }

    @Test
    void anUpdateChangesTheCapacityOfTheSameTable() {
        String stack = "cfn-ddb-tp-update-" + suffix();
        String table = "tp-update-" + suffix();

        cloudFormation(stack, "CreateStack", template(table, capacity(2, 2), capacity(1, 1)));
        assertEquals("CREATE_COMPLETE", CfnStackWaits.awaitTerminal(stack, CFN_AUTH).status());
        String tableId = describe(table).extract().path("Table.TableId");

        cloudFormation(stack, "UpdateStack", template(table, capacity(1, 1), capacity(3, 3)));
        assertEquals("UPDATE_COMPLETE", CfnStackWaits.awaitTerminal(stack, CFN_AUTH).status());

        describe(table)
            .body("Table.TableId", equalTo(tableId))
            .body("Table.ProvisionedThroughput.ReadCapacityUnits", equalTo(3))
            .body("Table.ProvisionedThroughput.WriteCapacityUnits", equalTo(3))
            .body("Table.GlobalSecondaryIndexes[0].ProvisionedThroughput.ReadCapacityUnits", equalTo(1))
            .body("Table.GlobalSecondaryIndexes[0].ProvisionedThroughput.WriteCapacityUnits", equalTo(1));

        deleteStack(stack);
    }

    @Test
    void anUpdateRaisesTheWarmThroughputAndAnEqualValueChangesNothing() {
        String stack = "cfn-ddb-tp-raise-" + suffix();
        String table = "tp-raise-" + suffix();
        String declared = capacity(1, 1) + warm("\"ReadUnitsPerSecond\": 5000");

        cloudFormation(stack, "CreateStack", template(table, capacity(2, 2), declared));
        assertEquals("CREATE_COMPLETE", CfnStackWaits.awaitTerminal(stack, CFN_AUTH).status());

        cloudFormation(stack, "UpdateStack", template(table, capacity(3, 3), declared));
        assertEquals("UPDATE_COMPLETE", CfnStackWaits.awaitTerminal(stack, CFN_AUTH).status());
        describe(table)
            .body("Table.WarmThroughput.ReadUnitsPerSecond", equalTo(5000))
            .body("Table.GlobalSecondaryIndexes[0].ProvisionedThroughput.ReadCapacityUnits", equalTo(3));

        cloudFormation(stack, "UpdateStack", template(table, capacity(3, 3),
                capacity(1, 1) + warm("\"ReadUnitsPerSecond\": 6000")));
        assertEquals("UPDATE_COMPLETE", CfnStackWaits.awaitTerminal(stack, CFN_AUTH).status());
        describe(table).body("Table.WarmThroughput.ReadUnitsPerSecond", equalTo(6000));

        deleteStack(stack);
    }

    @Test
    void aWarmThroughputTheTemplateKeptIsNotSentAgainWhenCapacityGrowsPastIt() {
        String stack = "cfn-ddb-tp-kept-" + suffix();
        String table = "tp-kept-" + suffix();
        String warmOne = warm("\"ReadUnitsPerSecond\": 1, \"WriteUnitsPerSecond\": 1");

        cloudFormation(stack, "CreateStack", template(table, capacity(2, 2), capacity(1, 1) + warmOne));
        assertEquals("CREATE_COMPLETE", CfnStackWaits.awaitTerminal(stack, CFN_AUTH).status());

        cloudFormation(stack, "UpdateStack", template(table, capacity(2, 2), capacity(3, 3) + warmOne));
        assertEquals("UPDATE_COMPLETE", CfnStackWaits.awaitTerminal(stack, CFN_AUTH).status());
        describe(table).body("Table.WarmThroughput.ReadUnitsPerSecond", equalTo(3));

        cloudFormation(stack, "UpdateStack", template(table, capacity(2, 2), capacity(4, 4) + warmOne));
        assertEquals("UPDATE_COMPLETE", CfnStackWaits.awaitTerminal(stack, CFN_AUTH).status());
        describe(table).body("Table.WarmThroughput.ReadUnitsPerSecond", equalTo(4));

        cloudFormation(stack, "UpdateStack", template(table, capacity(2, 2), capacity(4, 4)
                + warm("\"ReadUnitsPerSecond\": 2, \"WriteUnitsPerSecond\": 2")));
        assertEquals("UPDATE_ROLLBACK_COMPLETE", CfnStackWaits.awaitTerminal(stack, CFN_AUTH).status());
        assertTrue(CfnStackWaits.failedResourceReason(stack, CFN_AUTH).contains("lower than current WarmThroughput,"
                + " decreasing WarmThroughput is not supported"));
        describe(table).body("Table.WarmThroughput.ReadUnitsPerSecond", equalTo(4));

        deleteStack(stack);
    }

    @Test
    void anUpdateRaisingCapacityAndWarmThroughputTogetherPasses() {
        String stack = "cfn-ddb-tp-both-" + suffix();
        String table = "tp-both-" + suffix();

        cloudFormation(stack, "CreateStack", template(table, capacity(2, 2), capacity(1, 1)));
        assertEquals("CREATE_COMPLETE", CfnStackWaits.awaitTerminal(stack, CFN_AUTH).status());

        cloudFormation(stack, "UpdateStack", template(table, capacity(2, 2),
                capacity(10, 10) + warm("\"ReadUnitsPerSecond\": 10, \"WriteUnitsPerSecond\": 10")));
        assertEquals("UPDATE_COMPLETE", CfnStackWaits.awaitTerminal(stack, CFN_AUTH).status());

        describe(table)
            .body("Table.ProvisionedThroughput.ReadCapacityUnits", equalTo(10))
            .body("Table.WarmThroughput.ReadUnitsPerSecond", equalTo(10))
            .body("Table.WarmThroughput.WriteUnitsPerSecond", equalTo(10));

        deleteStack(stack);
    }

    @Test
    void anUpdateBelowTheCurrentWarmThroughputFailsAndRollsBack() {
        String stack = "cfn-ddb-tp-lower-" + suffix();
        String table = "tp-lower-" + suffix();

        cloudFormation(stack, "CreateStack", template(table, capacity(2, 2),
                capacity(1, 1) + warm("\"ReadUnitsPerSecond\": 5000")));
        assertEquals("CREATE_COMPLETE", CfnStackWaits.awaitTerminal(stack, CFN_AUTH).status());

        cloudFormation(stack, "UpdateStack", template(table, capacity(2, 2),
                capacity(3, 3) + warm("\"ReadUnitsPerSecond\": 4000")));
        assertEquals("UPDATE_ROLLBACK_COMPLETE", CfnStackWaits.awaitTerminal(stack, CFN_AUTH).status());
        assertTrue(CfnStackWaits.failedResourceReason(stack, CFN_AUTH).contains("Requested ReadUnitsPerSecond for"
                + " WarmThroughput for table is lower than current WarmThroughput,"
                + " decreasing WarmThroughput is not supported"));

        describe(table)
            .body("Table.ProvisionedThroughput.ReadCapacityUnits", equalTo(1))
            .body("Table.WarmThroughput.ReadUnitsPerSecond", equalTo(5000));

        deleteStack(stack);
    }

    @Test
    void aFailedUpdatePutsThePreviousCapacityBack() {
        String stack = "cfn-ddb-tp-rollback-" + suffix();
        String table = "tp-rollback-" + suffix();
        String missingStream = ",\n\"KinesisStreamSpecification\": {\"StreamArn\":"
                + " \"arn:aws:kinesis:us-east-1:000000000000:stream/no-such-stream-" + suffix() + "\"}";

        cloudFormation(stack, "CreateStack", template(table, capacity(2, 2), capacity(1, 1)));
        assertEquals("CREATE_COMPLETE", CfnStackWaits.awaitTerminal(stack, CFN_AUTH).status());

        cloudFormation(stack, "UpdateStack", template(table, capacity(4, 4), capacity(3, 3) + missingStream));
        assertEquals("UPDATE_ROLLBACK_COMPLETE", CfnStackWaits.awaitTerminal(stack, CFN_AUTH).status());

        describe(table)
            .body("Table.ProvisionedThroughput.ReadCapacityUnits", equalTo(1))
            .body("Table.ProvisionedThroughput.WriteCapacityUnits", equalTo(1))
            .body("Table.GlobalSecondaryIndexes[0].ProvisionedThroughput.ReadCapacityUnits", equalTo(2))
            .body("Table.GlobalSecondaryIndexes[0].ProvisionedThroughput.WriteCapacityUnits", equalTo(2));

        deleteStack(stack);
    }

    @Test
    void onDemandWithProvisionedThroughputFailsTheCreate() {
        String stack = "cfn-ddb-tp-ondemand-" + suffix();
        String table = "tp-ondemand-" + suffix();

        cloudFormation(stack, "CreateStack", template(table, "",
                ",\n\"BillingMode\": \"PAY_PER_REQUEST\"" + capacity(1, 1)));
        assertEquals("ROLLBACK_COMPLETE", CfnStackWaits.awaitTerminal(stack, CFN_AUTH).status());
        assertTrue(CfnStackWaits.failedResourceReason(stack, CFN_AUTH)
                .contains("Property ProvisionedThroughput can't be used with PAY_PER_REQUEST BillingMode."));

        describe(table).statusCode(400).body("__type", containsString("ResourceNotFoundException"));

        deleteStack(stack);
    }

    @Test
    void onDemandWithOnlyAnIndexCapacityFailsTheCreate() {
        String stack = "cfn-ddb-tp-ondemand-gsi-" + suffix();
        String table = "tp-ondemand-gsi-" + suffix();

        cloudFormation(stack, "CreateStack", template(table, capacity(1, 1),
                ",\n\"BillingMode\": \"PAY_PER_REQUEST\""));
        assertEquals("ROLLBACK_COMPLETE", CfnStackWaits.awaitTerminal(stack, CFN_AUTH).status());
        assertTrue(CfnStackWaits.failedResourceReason(stack, CFN_AUTH)
                .contains("Property ProvisionedThroughput can't be used with PAY_PER_REQUEST BillingMode."));

        describe(table).statusCode(400).body("__type", containsString("ResourceNotFoundException"));

        deleteStack(stack);
    }

    private static String suffix() {
        return Long.toString(System.nanoTime(), 36);
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

    private ValidatableResponse describe(String table) {
        return given()
            .contentType("application/x-amz-json-1.0")
            .header("Authorization", DDB_AUTH)
            .header("X-Amz-Target", "DynamoDB_20120810.DescribeTable")
            .body("{\"TableName\": \"" + table + "\"}")
        .when().post("/").then();
    }
}
