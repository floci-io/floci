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
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.startsWith;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

/**
 * Provisions {@code AWS::ApiGateway::ApiKey} resources through a CloudFormation stack: a named key
 * with every mutable property set, a key with no properties at all, and a key with a caller-chosen
 * value and a distinct id. Asserts that {@code Ref} and {@code Fn::GetAtt APIKeyId} both resolve to
 * the id {@code GetApiKey} serves rather than the literal the stub arm would leave, that an update
 * changes description, enabled flag and tags on the same key, that an update which fails on a later
 * resource puts the key's customer id, description, enabled flag and tags back, and that deleting
 * the stack removes every key.
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

    private static final String ROLLBACK_STACK = "apigw-apikey-cfn-rollback-it";

    /** One key with the given properties, optionally followed by more resources. */
    private static final String ROLLBACK_TEMPLATE = """
        {
          "Resources": {
            "Key": {
              "Type": "AWS::ApiGateway::ApiKey",
              "Properties": {%s}
            }%s
          },
          "Outputs": {"KeyRef": {"Value": {"Ref": "Key"}}}
        }
        """;

    /** A resource that fails after the key, so the update rolls back. */
    private static final String FAILING_RESOURCE = """
        ,
            "BadSecret": {
              "Type": "AWS::SecretsManager::Secret",
              "DependsOn": "Key",
              "Properties": {
                "Name": "apigw-apikey-cfn-rollback-it",
                "SecretString": "explicit",
                "GenerateSecretString": {"PasswordLength": 32}
              }
            }""";

    @BeforeAll
    static void configureRestAssured() {
        RestAssuredJsonUtils.configureAwsContentTypes();
    }

    @Test
    void createUpdateAndDeleteApiKeys() {
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
            .body("value", not(equalTo(namedId)));

        // A key with no properties gets a CloudFormation-style generated name and AWS's defaults.
        getApiKey(unnamedId)
            .statusCode(200)
            .body("name", startsWith(STACK + "-Unnamed-"))
            .body("enabled", equalTo(false));

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
        CfnStackWaits.awaitStackDeleted(STACK);

        getApiKey(namedId).statusCode(404);
        getApiKey(unnamedId).statusCode(404);
        getApiKey(distinctId).statusCode(404);
    }

    @Test
    void aFailedUpdatePutsTheKeyBackAsItWas() {
        String original = """
            "CustomerId": "customer-1", "Description": "original", "Enabled": "true",
            "Tags": [{"Key": "stack", "Value": "v1"}]""";
        cloudFormation(ROLLBACK_STACK, "CreateStack", ROLLBACK_TEMPLATE.formatted(original, ""), Map.of());
        String keyId = outputValue(describeStacks(ROLLBACK_STACK, "CREATE_COMPLETE"), "KeyRef");

        // Enabled is omitted, which disables the key, before the secret fails the update.
        String changed = """
            "CustomerId": "customer-2", "Description": "changed",
            "Tags": [{"Key": "stack", "Value": "v2"}]""";
        cloudFormation(ROLLBACK_STACK, "UpdateStack", ROLLBACK_TEMPLATE.formatted(changed, FAILING_RESOURCE), Map.of());
        assertEquals(keyId, outputValue(describeStacks(ROLLBACK_STACK, "UPDATE_ROLLBACK_COMPLETE"), "KeyRef"));

        getApiKey(keyId)
            .statusCode(200)
            .body("customerId", equalTo("customer-1"))
            .body("description", equalTo("original"))
            .body("enabled", equalTo(true))
            .body("tags", equalTo(Map.of("stack", "v1")));

        cloudFormation(ROLLBACK_STACK, "DeleteStack", null, Map.of());
        CfnStackWaits.awaitStackDeleted(ROLLBACK_STACK);
        getApiKey(keyId).statusCode(404);
    }

    @Test
    void aFailedUpdateDeletesTheKeyItCreatedInPlaceOfOneDeletedOutOfBand() {
        String stack = "apigw-apikey-cfn-recreate-it";
        String name = "apigw-apikey-cfn-recreate-it-key";
        String properties = "\"Name\": \"" + name + "\"";
        cloudFormation(stack, "CreateStack", ROLLBACK_TEMPLATE.formatted(properties, ""), Map.of());
        String keyId = outputValue(describeStacks(stack, "CREATE_COMPLETE"), "KeyRef");
        given().when().delete("/apikeys/" + keyId).then().statusCode(202);

        // The update creates a key in place of the deleted one before the secret fails it.
        cloudFormation(stack, "UpdateStack", ROLLBACK_TEMPLATE.formatted(properties, FAILING_RESOURCE), Map.of());
        describeStacks(stack, "UPDATE_ROLLBACK_COMPLETE");
        given().when().get("/apikeys").then()
            .statusCode(200)
            .body("item.name", not(hasItem(name)));

        cloudFormation(stack, "DeleteStack", null, Map.of());
        CfnStackWaits.awaitStackDeleted(stack);
    }

    private static Map<String, String> parameters(String description, String enabled, String tagValue) {
        return Map.of("Description", description, "Enabled", enabled, "TagValue", tagValue);
    }

    private static void cloudFormation(String action, Map<String, String> parameters) {
        cloudFormation(STACK, action, "DeleteStack".equals(action) ? null : TEMPLATE, parameters);
    }

    private static void cloudFormation(String stack, String action, String templateBody,
                                       Map<String, String> parameters) {
        RequestSpecification request = given()
            .contentType("application/x-www-form-urlencoded")
            .header("Authorization", CFN_AUTH)
            .formParam("Action", action)
            .formParam("StackName", stack);
        if (templateBody != null) {
            request.formParam("TemplateBody", templateBody);
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
        return describeStacks(STACK, expectedStatus);
    }

    private static String describeStacks(String stack, String expectedStatus) {
        CfnStackWaits.StackState state = CfnStackWaits.awaitTerminal(stack);
        assertEquals(expectedStatus, state.status(), state.reason());
        return given()
            .contentType("application/x-www-form-urlencoded")
            .header("Authorization", CFN_AUTH)
            .formParam("Action", "DescribeStacks")
            .formParam("StackName", stack)
        .when().post("/").then().statusCode(200)
            .body(containsString("<StackStatus>" + expectedStatus + "</StackStatus>"))
            .extract().asString();
    }

    /** DeleteStack runs asynchronously; a successful delete removes the stack entirely. */

    private static String outputValue(String xml, String key) {
        return XmlParser.extractPairs(xml, "Outputs", "OutputKey", "OutputValue").get(key);
    }

    private static ValidatableResponse getApiKey(String id) {
        return given().when().get("/apikeys/" + id + "?includeValue=true").then();
    }
}
