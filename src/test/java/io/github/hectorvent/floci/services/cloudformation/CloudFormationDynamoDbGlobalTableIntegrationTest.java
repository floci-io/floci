package io.github.hectorvent.floci.services.cloudformation;

import io.github.hectorvent.floci.testing.RestAssuredJsonUtils;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.path.json.JsonPath;
import io.restassured.response.Response;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.time.Duration;

import static io.restassured.RestAssured.given;
import static org.awaitility.Awaitility.await;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * {@code AWS::DynamoDB::GlobalTable} through the per-service provisioner: {@code Ref} is the table
 * name, every read-only attribute the registry schema declares ({@code Arn}, {@code TableId})
 * resolves through {@code Fn::GetAtt} to the value DescribeTable reports, and deleting the stack
 * removes the table. A stack status alone proves nothing here: an unowned type is stubbed
 * CREATE_COMPLETE with an {@code arn:aws:stub} attribute, so the assertions compare against the
 * DynamoDB API.
 */
@QuarkusTest
class CloudFormationDynamoDbGlobalTableIntegrationTest {

    private static final String CFN_AUTH =
            "AWS4-HMAC-SHA256 Credential=test/20260205/us-east-1/cloudformation/aws4_request";
    private static final String DDB_AUTH =
            "AWS4-HMAC-SHA256 Credential=test/20260205/us-east-1/dynamodb/aws4_request";
    private static final String SSM_AUTH =
            "AWS4-HMAC-SHA256 Credential=test/20260205/us-east-1/ssm/aws4_request";
    private static final Duration STACK_DELETE_TIMEOUT = Duration.ofSeconds(30);
    private static final Duration STACK_DELETE_POLL_INTERVAL = Duration.ofMillis(50);

    @BeforeAll
    static void configureRestAssured() {
        RestAssuredJsonUtils.configureAwsContentTypes();
    }

    @Test
    void globalTableRefAndGetAttResolveToTheDescribedTableAndStackDeleteRemovesIt() {
        String suffix = Long.toString(System.nanoTime(), 36);
        String tableName = "gt-v2-table-" + suffix;
        String stackName = "gt-v2-stack-" + suffix;
        String template = """
                {
                  "Resources": {
                    "Orders": {
                      "Type": "AWS::DynamoDB::GlobalTable",
                      "Properties": {
                        "TableName": "%s",
                        "AttributeDefinitions": [{"AttributeName": "pk", "AttributeType": "S"}],
                        "KeySchema": [{"AttributeName": "pk", "KeyType": "HASH"}],
                        "BillingMode": "PAY_PER_REQUEST",
                        "Replicas": [{"Region": "us-east-1"}]
                      }
                    },
                    "RefParam": {
                      "Type": "AWS::SSM::Parameter",
                      "Properties": {"Name": "/gt-v2/%s/ref", "Type": "String", "Value": {"Ref": "Orders"}}
                    },
                    "ArnParam": {
                      "Type": "AWS::SSM::Parameter",
                      "Properties": {"Name": "/gt-v2/%s/arn", "Type": "String",
                                     "Value": {"Fn::GetAtt": ["Orders", "Arn"]}}
                    },
                    "TableIdParam": {
                      "Type": "AWS::SSM::Parameter",
                      "Properties": {"Name": "/gt-v2/%s/table-id", "Type": "String",
                                     "Value": {"Fn::GetAtt": ["Orders", "TableId"]}}
                    }
                  }
                }
                """.formatted(tableName, suffix, suffix, suffix);

        createStack(stackName, template);
        assertStackStatus(stackName, "CREATE_COMPLETE");

        JsonPath described = describeTable(tableName).jsonPath();
        String tableArn = described.getString("Table.TableArn");
        String tableId = described.getString("Table.TableId");
        assertTrue(tableArn != null && tableArn.startsWith("arn:aws:dynamodb:"), "TableArn: " + tableArn);
        assertFalse(tableId == null || tableId.isBlank(), "DescribeTable reported no TableId");

        assertEquals(tableName, parameterValue("/gt-v2/" + suffix + "/ref"));
        assertEquals(tableArn, parameterValue("/gt-v2/" + suffix + "/arn"));
        assertEquals(tableId, parameterValue("/gt-v2/" + suffix + "/table-id"));

        deleteStack(stackName);
        awaitStackGone(stackName);

        given()
            .contentType("application/x-amz-json-1.0")
            .header("Authorization", DDB_AUTH)
            .header("X-Amz-Target", "DynamoDB_20120810.DescribeTable")
            .body("{\"TableName\":\"" + tableName + "\"}")
        .when().post("/").then().statusCode(400)
            .body("__type", containsString("ResourceNotFoundException"));
    }

    private void createStack(String stackName, String template) {
        given()
            .contentType("application/x-www-form-urlencoded")
            .header("Authorization", CFN_AUTH)
            .formParam("Action", "CreateStack")
            .formParam("StackName", stackName)
            .formParam("TemplateBody", template)
        .when().post("/").then().statusCode(200);
    }

    private void assertStackStatus(String stackName, String status) {
        given()
            .contentType("application/x-www-form-urlencoded")
            .header("Authorization", CFN_AUTH)
            .formParam("Action", "DescribeStacks")
            .formParam("StackName", stackName)
        .when().post("/").then().statusCode(200)
            .body(containsString("<StackStatus>" + status + "</StackStatus>"));
    }

    private void deleteStack(String stackName) {
        given()
            .contentType("application/x-www-form-urlencoded")
            .header("Authorization", CFN_AUTH)
            .formParam("Action", "DeleteStack")
            .formParam("StackName", stackName)
        .when().post("/").then().statusCode(200);
    }

    private Response describeTable(String tableName) {
        return given()
            .contentType("application/x-amz-json-1.0")
            .header("Authorization", DDB_AUTH)
            .header("X-Amz-Target", "DynamoDB_20120810.DescribeTable")
            .body("{\"TableName\":\"" + tableName + "\"}")
        .when().post("/").then().statusCode(200)
            .body("Table.TableName", equalTo(tableName))
            .extract().response();
    }

    private String parameterValue(String name) {
        return given()
            .contentType("application/x-amz-json-1.1")
            .header("Authorization", SSM_AUTH)
            .header("X-Amz-Target", "AmazonSSM.GetParameter")
            .body("{\"Name\":\"" + name + "\"}")
        .when().post("/").then().statusCode(200)
            .extract().jsonPath().getString("Parameter.Value");
    }

    private void awaitStackGone(String stackName) {
        await()
            .atMost(STACK_DELETE_TIMEOUT)
            .pollInterval(STACK_DELETE_POLL_INTERVAL)
            .untilAsserted(() -> {
                String body = given()
                    .contentType("application/x-www-form-urlencoded")
                    .header("Authorization", CFN_AUTH)
                    .formParam("Action", "DescribeStacks")
                    .formParam("StackName", stackName)
                .when().post("/").then().extract().asString();
                if (body.contains("<StackStatus>DELETE_FAILED</StackStatus>")) {
                    fail("stack delete failed: " + body);
                }
                assertTrue(body.contains("does not exist"), "stack still exists: " + body);
            });
    }
}
