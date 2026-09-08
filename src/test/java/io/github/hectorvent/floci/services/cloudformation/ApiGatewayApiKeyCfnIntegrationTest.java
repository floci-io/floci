package io.github.hectorvent.floci.services.cloudformation;

import io.github.hectorvent.floci.core.common.XmlParser;
import io.github.hectorvent.floci.testing.RestAssuredJsonUtils;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.response.ValidatableResponse;
import io.restassured.specification.RequestSpecification;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.startsWith;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * Provisions {@code AWS::ApiGateway::ApiKey} resources through a CloudFormation stack: a named key
 * with every mutable property set, a key with no properties at all, and a key with a caller-chosen
 * value and a distinct id. Asserts that {@code Ref} and {@code Fn::GetAtt APIKeyId} both resolve to
 * the id {@code GetApiKey} serves rather than the literal the stub arm would leave, that an update
 * changes description, enabled flag and tags on the same key, and that deleting the stack removes
 * every key.
 */
@QuarkusTest
class ApiGatewayApiKeyCfnIntegrationTest {

    private static final String CFN_AUTH =
            "AWS4-HMAC-SHA256 Credential=test/20260905/us-east-1/cloudformation/aws4_request";
    private static final String STACK = "apigw-apikey-cfn-it";
    private static final String NAMED = "apigw-apikey-cfn-it-named";
    private static final String CHOSEN_VALUE = "apigw-apikey-cfn-it-value-0123456789";

    /** Description, Enabled and the tag value are parameters so one template drives create and update. */
    private static final String TEMPLATE = """
        {
          "Parameters": {
            "Description": {"Type": "String"},
            "Enabled": {"Type": "String"},
            "TagValue": {"Type": "String"}
          },
          "Resources": {
            "Named": {
              "Type": "AWS::ApiGateway::ApiKey",
              "Properties": {
                "Name": "%s",
                "Description": {"Ref": "Description"},
                "Enabled": {"Ref": "Enabled"},
                "Tags": [{"Key": "stack", "Value": {"Ref": "TagValue"}}]
              }
            },
            "Unnamed": {"Type": "AWS::ApiGateway::ApiKey"},
            "Distinct": {
              "Type": "AWS::ApiGateway::ApiKey",
              "Properties": {"GenerateDistinctId": "true", "Value": "%s"}
            }
          },
          "Outputs": {
            "NamedRef": {"Value": {"Ref": "Named"}},
            "NamedId": {"Value": {"Fn::GetAtt": ["Named", "APIKeyId"]}},
            "UnnamedRef": {"Value": {"Ref": "Unnamed"}},
            "DistinctRef": {"Value": {"Ref": "Distinct"}}
          }
        }
        """.formatted(NAMED, CHOSEN_VALUE);

    @BeforeAll
    static void configureRestAssured() {
        RestAssuredJsonUtils.configureAwsContentTypes();
    }

    @Test
    void createUpdateAndDeleteApiKeys() throws InterruptedException {
        cloudFormation("CreateStack", parameters("first", "false", "v1"));
        String created = describeStacks("CREATE_COMPLETE");
        String namedId = outputValue(created, "NamedRef");
        String unnamedId = outputValue(created, "UnnamedRef");
        String distinctId = outputValue(created, "DistinctRef");

        // Fn::GetAtt APIKeyId is the id itself, not the literal "Named.APIKeyId" the stub arm leaves.
        assertEquals(namedId, outputValue(created, "NamedId"));

        getApiKey(namedId)
            .statusCode(200)
            .body("name", equalTo(NAMED))
            .body("description", equalTo("first"))
            .body("enabled", equalTo(false))
            .body("tags.stack", equalTo("v1"))
            .body("value", equalTo(namedId));

        // A key with no properties gets a CloudFormation-style generated name and AWS's defaults.
        getApiKey(unnamedId)
            .statusCode(200)
            .body("name", startsWith(STACK + "-Unnamed-"))
            .body("enabled", equalTo(true));

        // A caller-chosen value with a distinct id keeps the value and mints a separate id.
        assertNotEquals(CHOSEN_VALUE, distinctId);
        getApiKey(distinctId)
            .statusCode(200)
            .body("value", equalTo(CHOSEN_VALUE))
            .body("id", not(equalTo(CHOSEN_VALUE)));

        cloudFormation("UpdateStack", parameters("second", "true", "v2"));
        String updated = describeStacks("UPDATE_COMPLETE");

        // The mutable properties change on the same key; nothing is replaced.
        assertEquals(namedId, outputValue(updated, "NamedRef"));
        getApiKey(namedId)
            .statusCode(200)
            .body("description", equalTo("second"))
            .body("enabled", equalTo(true))
            .body("tags.stack", equalTo("v2"));

        cloudFormation("DeleteStack", Map.of());
        awaitStackDeleted();

        getApiKey(namedId).statusCode(404);
        getApiKey(unnamedId).statusCode(404);
        getApiKey(distinctId).statusCode(404);
    }

    private static Map<String, String> parameters(String description, String enabled, String tagValue) {
        return Map.of("Description", description, "Enabled", enabled, "TagValue", tagValue);
    }

    private static void cloudFormation(String action, Map<String, String> parameters) {
        RequestSpecification request = given()
            .contentType("application/x-www-form-urlencoded")
            .header("Authorization", CFN_AUTH)
            .formParam("Action", action)
            .formParam("StackName", STACK);
        if (!"DeleteStack".equals(action)) {
            request.formParam("TemplateBody", TEMPLATE);
        }
        int index = 1;
        for (Map.Entry<String, String> parameter : parameters.entrySet()) {
            request.formParam("Parameters.member." + index + ".ParameterKey", parameter.getKey());
            request.formParam("Parameters.member." + index + ".ParameterValue", parameter.getValue());
            index++;
        }
        request.when().post("/").then().statusCode(200);
    }

    private static String describeStacks(String expectedStatus) {
        return given()
            .contentType("application/x-www-form-urlencoded")
            .header("Authorization", CFN_AUTH)
            .formParam("Action", "DescribeStacks")
            .formParam("StackName", STACK)
        .when().post("/").then().statusCode(200)
            .body(containsString("<StackStatus>" + expectedStatus + "</StackStatus>"))
            .extract().asString();
    }

    /** DeleteStack runs asynchronously; a successful delete removes the stack entirely. */
    private static void awaitStackDeleted() throws InterruptedException {
        for (int i = 0; i < 100; i++) {
            String body = given()
                .contentType("application/x-www-form-urlencoded")
                .header("Authorization", CFN_AUTH)
                .formParam("Action", "DescribeStacks")
                .formParam("StackName", STACK)
            .when().post("/").then().extract().asString();
            if (body.contains("does not exist")) {
                return;
            }
            if (body.contains("<StackStatus>DELETE_FAILED</StackStatus>")) {
                fail("stack delete failed: " + body);
            }
            Thread.sleep(50);
        }
        fail("stack " + STACK + " was not deleted within the timeout");
    }

    private static String outputValue(String xml, String key) {
        return XmlParser.extractPairs(xml, "Outputs", "OutputKey", "OutputValue").get(key);
    }

    private static ValidatableResponse getApiKey(String id) {
        return given().when().get("/apikeys/" + id + "?includeValue=true").then();
    }
}
