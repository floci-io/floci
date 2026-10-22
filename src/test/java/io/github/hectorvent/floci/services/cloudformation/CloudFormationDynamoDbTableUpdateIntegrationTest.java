package io.github.hectorvent.floci.services.cloudformation;

import io.github.hectorvent.floci.testing.CfnStackWaits;
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
 * An UpdateStack that changes an {@code AWS::DynamoDB::Table}'s global secondary indexes or its
 * billing reaches the table in place, as on AWS: an index the template adds is created, one it drops
 * is deleted, and {@code BillingMode} and {@code ProvisionedThroughput} are applied on create and on
 * update.
 */
@QuarkusTest
class CloudFormationDynamoDbTableUpdateIntegrationTest {

    private static final String CFN_AUTH =
            "AWS4-HMAC-SHA256 Credential=test/20260205/us-east-1/cloudformation/aws4_request";
    private static final String DDB_AUTH =
            "AWS4-HMAC-SHA256 Credential=test/20260205/us-east-1/dynamodb/aws4_request";

    /**
     * A table named by the first argument, with the billing given by the second and the extra members
     * given by the third, followed by the resources given by the fourth.
     */
    private static final String STACK_TEMPLATE = """
            {
              "Resources": {
                "Users": {
                  "Type": "AWS::DynamoDB::Table",
                  "Properties": {
                    "TableName": "%s",
                    "KeySchema": [{"AttributeName": "pk", "KeyType": "HASH"}],
                    %s%s
                  }
                }%s
              }
            }
            """;

    /** A resource that fails after the table, so the update that changed the table rolls back. */
    private static final String FAILING_RESOURCE = """
            ,
                "BadSecret": {
                  "Type": "AWS::SecretsManager::Secret",
                  "DependsOn": "Users",
                  "Properties": {
                    "Name": "gsi-rollback-%s",
                    "SecretString": "explicit",
                    "GenerateSecretString": {"PasswordLength": 32}
                  }
                }""";

    private static final String ON_DEMAND = "\"BillingMode\": \"PAY_PER_REQUEST\"";
    private static final String KEY_ONLY = """
            ,
                    "AttributeDefinitions": [{"AttributeName": "pk", "AttributeType": "S"}]""";
    private static final String BY_EMAIL = """
            ,
                    "AttributeDefinitions": [
                      {"AttributeName": "pk", "AttributeType": "S"},
                      {"AttributeName": "email", "AttributeType": "S"}
                    ],
                    "GlobalSecondaryIndexes": [{
                      "IndexName": "byEmail",
                      "KeySchema": [{"AttributeName": "email", "KeyType": "HASH"}],
                      "Projection": {"ProjectionType": "ALL"}
                    }]""";

    @BeforeAll
    static void configureRestAssured() {
        RestAssuredJsonUtils.configureAwsContentTypes();
    }

    @Test
    void updateStackCreatesTheIndexTheTemplateAddsAndDeletesTheOneItDrops() {
        String suffix = Long.toString(System.nanoTime(), 36);
        String stack = "cfn-ddb-gsi-" + suffix;
        String table = "gsi-table-" + suffix;

        cloudFormation(stack, "CreateStack", template(table, ON_DEMAND, KEY_ONLY));
        assertEquals("CREATE_COMPLETE", CfnStackWaits.awaitTerminal(stack, CFN_AUTH).status());

        cloudFormation(stack, "UpdateStack", template(table, ON_DEMAND, BY_EMAIL));
        CfnStackWaits.StackState added = CfnStackWaits.awaitTerminal(stack, CFN_AUTH);
        assertEquals("UPDATE_COMPLETE", added.status(), added.reason());
        describeTable(table).body("Table.GlobalSecondaryIndexes.IndexName", contains("byEmail"));

        cloudFormation(stack, "UpdateStack", template(table, ON_DEMAND, KEY_ONLY));
        CfnStackWaits.StackState dropped = CfnStackWaits.awaitTerminal(stack, CFN_AUTH);
        assertEquals("UPDATE_COMPLETE", dropped.status(), dropped.reason());
        describeTable(table).body("Table.GlobalSecondaryIndexes", nullValue());

        deleteStack(stack);
    }

    /** A failed update takes back the index it created, with the rest of the table's prior state. */
    @Test
    void aFailedUpdateRemovesTheIndexItCreated() {
        String suffix = Long.toString(System.nanoTime(), 36);
        String stack = "cfn-ddb-gsi-rollback-" + suffix;
        String table = "gsi-rollback-table-" + suffix;

        cloudFormation(stack, "CreateStack", template(table, ON_DEMAND, KEY_ONLY));
        assertEquals("CREATE_COMPLETE", CfnStackWaits.awaitTerminal(stack, CFN_AUTH).status());

        cloudFormation(stack, "UpdateStack",
                STACK_TEMPLATE.formatted(table, ON_DEMAND, BY_EMAIL, FAILING_RESOURCE.formatted(suffix)));
        CfnStackWaits.StackState rolledBack = CfnStackWaits.awaitTerminal(stack, CFN_AUTH);
        assertEquals("UPDATE_ROLLBACK_COMPLETE", rolledBack.status(), rolledBack.reason());
        describeTable(table).body("Table.GlobalSecondaryIndexes", nullValue());

        deleteStack(stack);
    }

    @Test
    void billingModeIsAppliedOnCreateAndOnUpdate() {
        String suffix = Long.toString(System.nanoTime(), 36);
        String stack = "cfn-ddb-billing-" + suffix;
        String table = "billing-table-" + suffix;

        cloudFormation(stack, "CreateStack", template(table, ON_DEMAND, KEY_ONLY));
        assertEquals("CREATE_COMPLETE", CfnStackWaits.awaitTerminal(stack, CFN_AUTH).status());
        describeTable(table).body("Table.BillingModeSummary.BillingMode", equalTo("PAY_PER_REQUEST"));

        cloudFormation(stack, "UpdateStack", template(table, "\"BillingMode\": \"PROVISIONED\","
                + " \"ProvisionedThroughput\": {\"ReadCapacityUnits\": 3, \"WriteCapacityUnits\": 4}", KEY_ONLY));
        CfnStackWaits.StackState updated = CfnStackWaits.awaitTerminal(stack, CFN_AUTH);
        assertEquals("UPDATE_COMPLETE", updated.status(), updated.reason());
        describeTable(table)
            .body("Table.BillingModeSummary", nullValue())
            .body("Table.ProvisionedThroughput.ReadCapacityUnits", equalTo(3))
            .body("Table.ProvisionedThroughput.WriteCapacityUnits", equalTo(4));

        deleteStack(stack);
    }

    private static String template(String table, String billing, String members) {
        return STACK_TEMPLATE.formatted(table, billing, members, "");
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

    private ValidatableResponse describeTable(String table) {
        return given()
            .contentType("application/x-amz-json-1.0")
            .header("Authorization", DDB_AUTH)
            .header("X-Amz-Target", "DynamoDB_20120810.DescribeTable")
            .body("{\"TableName\": \"" + table + "\"}")
        .when().post("/").then().statusCode(200);
    }
}
