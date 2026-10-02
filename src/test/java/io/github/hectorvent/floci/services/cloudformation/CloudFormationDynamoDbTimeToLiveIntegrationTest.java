package io.github.hectorvent.floci.services.cloudformation;

import io.github.hectorvent.floci.testing.RestAssuredJsonUtils;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.response.ValidatableResponse;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.nullValue;
import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * {@code TimeToLiveSpecification} on {@code AWS::DynamoDB::Table}: the setting a stack declares is
 * what DescribeTimeToLive reports after create, after an update that disables it and after an
 * update that removes the block, and a failed update puts the previous setting back.
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
