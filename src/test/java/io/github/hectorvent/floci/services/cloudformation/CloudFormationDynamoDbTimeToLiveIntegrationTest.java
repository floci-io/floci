package io.github.hectorvent.floci.services.cloudformation;

import io.github.hectorvent.floci.services.dynamodb.DynamoDbService;
import io.github.hectorvent.floci.testing.RestAssuredJsonUtils;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.mockito.InjectSpy;
import io.restassured.response.ValidatableResponse;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.nullValue;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyString;

/**
 * {@code TimeToLiveSpecification} on {@code AWS::DynamoDB::Table}: the setting a stack declares is
 * what DescribeTimeToLive reports after create, after an update that disables it and after an
 * update that removes the block, and a failed update puts the previous setting back. A failure
 * inside the table's own provision is injected through a spy on the DynamoDB service.
 */
@QuarkusTest
class CloudFormationDynamoDbTimeToLiveIntegrationTest {

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
                    "AttributeDefinitions": [{"AttributeName": "pk", "AttributeType": "S"}],
                    "KeySchema": [{"AttributeName": "pk", "KeyType": "HASH"}],
                    "BillingMode": "PAY_PER_REQUEST",
                    "Tags": [{"Key": "env", "Value": "%s"}]%s
                  }
                }%s
              }
            }
            """;

    private static final String TTL_ENABLED =
            ",\n\"TimeToLiveSpecification\": {\"AttributeName\": \"expiresAt\", \"Enabled\": true}";
    private static final String TTL_DISABLED =
            ",\n\"TimeToLiveSpecification\": {\"AttributeName\": \"expiresAt\", \"Enabled\": false}";
    private static final String TTL_RENAMED =
            ",\n\"TimeToLiveSpecification\": {\"AttributeName\": \"deleteAfter\", \"Enabled\": true}";
    private static final String TTL_WITHOUT_ATTRIBUTE =
            ",\n\"TimeToLiveSpecification\": {\"Enabled\": true}";
    private static final String STREAMED =
            ",\n\"StreamSpecification\": {\"StreamViewType\": \"NEW_AND_OLD_IMAGES\"}";

    /** A resource that fails after the table, so the update that changed the table rolls back. */
    private static final String FAILING_RESOURCE = """
            ,
                "BadSecret": {
                  "Type": "AWS::SecretsManager::Secret",
                  "DependsOn": "Orders",
                  "Properties": {
                    "Name": "ttl-rollback-%s",
                    "SecretString": "explicit",
                    "GenerateSecretString": {"PasswordLength": 32}
                  }
                }""";

    @InjectSpy
    DynamoDbService dynamoDbService;

    @BeforeAll
    static void configureRestAssured() {
        RestAssuredJsonUtils.configureAwsContentTypes();
    }

    @Test
    void createEnablesTheDeclaredTimeToLiveAndEnabledFalseDisablesIt() {
        String suffix = Long.toString(System.nanoTime(), 36);
        String stack = "cfn-ddb-ttl-" + suffix;
        String table = "ttl-table-" + suffix;

        cloudFormation(stack, "CreateStack", TEMPLATE.formatted(table, "dev", TTL_ENABLED, ""));
        assertEquals("CREATE_COMPLETE", CfnStackWaits.awaitTerminal(stack, CFN_AUTH).status());
        describeTimeToLive(table)
            .body("TimeToLiveDescription.TimeToLiveStatus", equalTo("ENABLED"))
            .body("TimeToLiveDescription.AttributeName", equalTo("expiresAt"));

        cloudFormation(stack, "UpdateStack", TEMPLATE.formatted(table, "dev", TTL_DISABLED, ""));
        assertEquals("UPDATE_COMPLETE", CfnStackWaits.awaitTerminal(stack, CFN_AUTH).status());
        describeTimeToLive(table)
            .body("TimeToLiveDescription.TimeToLiveStatus", equalTo("DISABLED"))
            .body("TimeToLiveDescription.AttributeName", nullValue());

        deleteStack(stack);
    }

    @Test
    void removingTheTimeToLiveBlockDisablesIt() {
        String suffix = Long.toString(System.nanoTime(), 36);
        String stack = "cfn-ddb-ttl-rm-" + suffix;
        String table = "ttl-rm-table-" + suffix;

        cloudFormation(stack, "CreateStack", TEMPLATE.formatted(table, "dev", TTL_ENABLED, ""));
        assertEquals("CREATE_COMPLETE", CfnStackWaits.awaitTerminal(stack, CFN_AUTH).status());
        describeTimeToLive(table).body("TimeToLiveDescription.TimeToLiveStatus", equalTo("ENABLED"));

        cloudFormation(stack, "UpdateStack", TEMPLATE.formatted(table, "dev", "", ""));
        assertEquals("UPDATE_COMPLETE", CfnStackWaits.awaitTerminal(stack, CFN_AUTH).status());
        describeTimeToLive(table).body("TimeToLiveDescription.TimeToLiveStatus", equalTo("DISABLED"));

        deleteStack(stack);
    }

    @Test
    void anUpdateThatFailsOnALaterResourcePutsTheTableBack() {
        String suffix = Long.toString(System.nanoTime(), 36);
        String stack = "cfn-ddb-ttl-rb-" + suffix;
        String table = "ttl-rb-table-" + suffix;

        cloudFormation(stack, "CreateStack", TEMPLATE.formatted(table, "dev", TTL_ENABLED, ""));
        assertEquals("CREATE_COMPLETE", CfnStackWaits.awaitTerminal(stack, CFN_AUTH).status());
        String tableArn = dynamoDb("DescribeTable", "{\"TableName\": \"" + table + "\"}")
            .statusCode(200)
            .extract().path("Table.TableArn");

        cloudFormation(stack, "UpdateStack",
                TEMPLATE.formatted(table, "prod", TTL_DISABLED, FAILING_RESOURCE.formatted(suffix)));
        assertEquals("UPDATE_ROLLBACK_COMPLETE", CfnStackWaits.awaitTerminal(stack, CFN_AUTH).status());

        describeTimeToLive(table)
            .body("TimeToLiveDescription.TimeToLiveStatus", equalTo("ENABLED"))
            .body("TimeToLiveDescription.AttributeName", equalTo("expiresAt"));
        dynamoDb("ListTagsOfResource", "{\"ResourceArn\": \"" + tableArn + "\"}")
            .statusCode(200)
            .body("Tags.Value", contains("dev"));

        deleteStack(stack);
    }

    /**
     * The table's own update changes the tags and switches the stream off, then its TTL call fails.
     * The rollback must put the tags and the stream back, not just report that it did.
     */
    @Test
    void anUpdateThatFailsInsideTheTablePutsItsTagsAndStreamBack() {
        String suffix = Long.toString(System.nanoTime(), 36);
        String stack = "cfn-ddb-ttl-own-" + suffix;
        String table = "ttl-own-table-" + suffix;

        cloudFormation(stack, "CreateStack", TEMPLATE.formatted(table, "dev", STREAMED, ""));
        assertEquals("CREATE_COMPLETE", CfnStackWaits.awaitTerminal(stack, CFN_AUTH).status());
        String tableArn = dynamoDb("DescribeTable", "{\"TableName\": \"" + table + "\"}")
            .statusCode(200)
            .extract().path("Table.TableArn");

        failNextUpdateTimeToLive();
        try {
            cloudFormation(stack, "UpdateStack", TEMPLATE.formatted(table, "prod", TTL_ENABLED, ""));
            assertEquals("UPDATE_ROLLBACK_COMPLETE", CfnStackWaits.awaitTerminal(stack, CFN_AUTH).status());
        } finally {
            Mockito.doCallRealMethod().when(dynamoDbService)
                    .updateTimeToLive(anyString(), anyString(), anyBoolean(), anyString());
        }

        dynamoDb("ListTagsOfResource", "{\"ResourceArn\": \"" + tableArn + "\"}")
            .statusCode(200)
            .body("Tags.Value", contains("dev"));
        dynamoDb("DescribeTable", "{\"TableName\": \"" + table + "\"}")
            .statusCode(200)
            .body("Table.StreamSpecification.StreamEnabled", equalTo(true))
            .body("Table.StreamSpecification.StreamViewType", equalTo("NEW_AND_OLD_IMAGES"));
        describeTimeToLive(table).body("TimeToLiveDescription.TimeToLiveStatus", equalTo("DISABLED"));

        deleteStack(stack);
    }

    /** A create that fails after the table exists rolls back to no table, not to an orphan. */
    @Test
    void aCreateThatFailsAfterTheTableExistsLeavesNoTable() {
        String suffix = Long.toString(System.nanoTime(), 36);
        String stack = "cfn-ddb-ttl-orphan-" + suffix;
        String table = "ttl-orphan-table-" + suffix;

        failNextUpdateTimeToLive();
        try {
            cloudFormation(stack, "CreateStack", TEMPLATE.formatted(table, "dev", TTL_ENABLED, ""));
            assertEquals("ROLLBACK_COMPLETE", CfnStackWaits.awaitTerminal(stack, CFN_AUTH).status());
        } finally {
            Mockito.doCallRealMethod().when(dynamoDbService)
                    .updateTimeToLive(anyString(), anyString(), anyBoolean(), anyString());
        }

        dynamoDb("DescribeTable", "{\"TableName\": \"" + table + "\"}")
            .statusCode(400)
            .body("__type", containsString("ResourceNotFoundException"));

        deleteStack(stack);
    }

    /** As measured on AWS: the rename fails the table before any DynamoDB change and the update rolls back. */
    @Test
    void renamingAnEnabledAttributeFailsWithTheAwsReasonAndRollsBack() {
        String suffix = Long.toString(System.nanoTime(), 36);
        String stack = "cfn-ddb-ttl-rename-" + suffix;
        String table = "ttl-rename-table-" + suffix;

        cloudFormation(stack, "CreateStack", TEMPLATE.formatted(table, "dev", TTL_ENABLED, ""));
        assertEquals("CREATE_COMPLETE", CfnStackWaits.awaitTerminal(stack, CFN_AUTH).status());

        cloudFormation(stack, "UpdateStack", TEMPLATE.formatted(table, "dev", TTL_RENAMED, ""));
        assertEquals("UPDATE_ROLLBACK_COMPLETE", CfnStackWaits.awaitTerminal(stack, CFN_AUTH).status());

        describeStackEvents(stack).body(containsString("<ResourceStatusReason>Invalid request provided: Cannot change"
                + " time-to-live attribute name. To update this property, you must first disable TTL then enable"
                + " TTL with the new attribute name.</ResourceStatusReason>"));
        describeTimeToLive(table)
            .body("TimeToLiveDescription.TimeToLiveStatus", equalTo("ENABLED"))
            .body("TimeToLiveDescription.AttributeName", equalTo("expiresAt"));

        deleteStack(stack);
    }

    /** As measured on AWS: the create fails with the reason below and leaves no table. */
    @Test
    void enablingWithoutAnAttributeNameFailsTheCreateWithTheAwsReason() {
        String suffix = Long.toString(System.nanoTime(), 36);
        String stack = "cfn-ddb-ttl-noattr-" + suffix;
        String table = "ttl-noattr-table-" + suffix;

        cloudFormation(stack, "CreateStack", TEMPLATE.formatted(table, "dev", TTL_WITHOUT_ATTRIBUTE, ""));
        assertEquals("ROLLBACK_COMPLETE", CfnStackWaits.awaitTerminal(stack, CFN_AUTH).status());

        describeStackEvents(stack).body(containsString("<ResourceStatusReason>Invalid request provided: AttributeName"
                + " property of TimeToLiveSpecification is required when TTL status is enabled or when enabling"
                + " TTL.</ResourceStatusReason>"));
        dynamoDb("DescribeTable", "{\"TableName\": \"" + table + "\"}")
            .statusCode(400)
            .body("__type", containsString("ResourceNotFoundException"));

        deleteStack(stack);
    }

    private ValidatableResponse describeStackEvents(String stack) {
        return given()
            .contentType("application/x-www-form-urlencoded")
            .header("Authorization", CFN_AUTH)
            .formParam("Action", "DescribeStackEvents")
            .formParam("StackName", stack)
        .when().post("/").then().statusCode(200);
    }

    private void failNextUpdateTimeToLive() {
        Mockito.doThrow(new IllegalStateException("simulated UpdateTimeToLive failure"))
                .doCallRealMethod()
                .when(dynamoDbService)
                .updateTimeToLive(anyString(), anyString(), anyBoolean(), anyString());
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

    private ValidatableResponse describeTimeToLive(String table) {
        return dynamoDb("DescribeTimeToLive", "{\"TableName\": \"" + table + "\"}").statusCode(200);
    }

    private ValidatableResponse dynamoDb(String action, String body) {
        return given()
            .contentType("application/x-amz-json-1.0")
            .header("Authorization", DDB_AUTH)
            .header("X-Amz-Target", "DynamoDB_20120810." + action)
            .body(body)
        .when().post("/").then();
    }
}
