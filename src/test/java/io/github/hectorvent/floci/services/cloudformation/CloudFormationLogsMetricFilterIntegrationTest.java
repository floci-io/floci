package io.github.hectorvent.floci.services.cloudformation;

import io.github.hectorvent.floci.core.common.XmlParser;
import io.github.hectorvent.floci.testing.RestAssuredJsonUtils;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.response.Response;
import io.restassured.specification.RequestSpecification;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasSize;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * Provisions an {@code AWS::Logs::MetricFilter} through a CloudFormation stack and reads it back
 * through the Logs API. {@code Ref} is the composite {@code LogGroupName|FilterName} id a real
 * stack shows; a pattern change updates the filter in place; a name change replaces it and deletes
 * the displaced filter once the update commits; a failed update puts the previous definition back;
 * and the stack delete removes the filter.
 */
@QuarkusTest
class CloudFormationLogsMetricFilterIntegrationTest {

    private static final String CFN_AUTH =
            "AWS4-HMAC-SHA256 Credential=test/20260908/us-east-1/cloudformation/aws4_request";
    private static final String LOGS_CONTENT_TYPE = "application/x-amz-json-1.1";
    private static final String LOGS_AUTH =
            "AWS4-HMAC-SHA256 Credential=test/20260908/us-east-1/logs/aws4_request";

    private static final String TEMPLATE = """
        {
          "Parameters": {
            "FilterName": {"Type": "String"},
            "Pattern": {"Type": "String", "Default": "ERROR"}
          },
          "Resources": {
            "Filter": {
              "Type": "AWS::Logs::MetricFilter",
              "Properties": {
                "LogGroupName": "%s",
                "FilterName": {"Ref": "FilterName"},
                "FilterPattern": {"Ref": "Pattern"},
                "MetricTransformations": [{
                  "MetricName": "ErrorCount",
                  "MetricNamespace": "Stack/%s",
                  "MetricValue": "1",
                  "DefaultValue": 0,
                  "Unit": "Count"
                }]
              }
            }%s
          },
          "Outputs": {
            "FilterRef": {"Value": {"Ref": "Filter"}}
          }
        }
        """;

    private static final String UNNAMED_TEMPLATE = """
        {
          "Resources": {
            "Filter": {
              "Type": "AWS::Logs::MetricFilter",
              "Properties": {
                "LogGroupName": "%s",
                "FilterPattern": "{ $.latency = * }",
                "MetricTransformations": [{
                  "MetricName": "Latency",
                  "MetricNamespace": "Stack/%s",
                  "MetricValue": "$.latency",
                  "Dimensions": [{"Key": "Route", "Value": "$.route"}]
                }]
              }
            }
          },
          "Outputs": {
            "FilterRef": {"Value": {"Ref": "Filter"}}
          }
        }
        """;

    /** The log group in a stack of its own, so the filter stacks hold nothing else. */
    private static final String GROUP_TEMPLATE = """
        {
          "Resources": {
            "LogGroup": {
              "Type": "AWS::Logs::LogGroup",
              "Properties": {"LogGroupName": "%s"}
            }
          }
        }
        """;

    /** A resource that fails after the filter, so the update rolls back. */
    private static final String FAILING_RESOURCE = """
        ,
            "BadSecret": {
              "Type": "AWS::SecretsManager::Secret",
              "DependsOn": "Filter",
              "Properties": {
                "Name": "metric-filter-rollback-%s",
                "SecretString": "explicit",
                "GenerateSecretString": {"PasswordLength": 32}
              }
            }""";

    @BeforeAll
    static void configureRestAssured() {
        RestAssuredJsonUtils.configureAwsContentTypes();
    }

    @Test
    void filterFollowsTheTemplateThroughCreateUpdateReplacementAndDelete() throws InterruptedException {
        String suffix = Long.toString(System.nanoTime(), 36);
        String stack = "cfn-metric-filter-" + suffix;
        String groupStack = "cfn-metric-filter-group-" + suffix;
        String group = "/cfn/metric-filter/" + suffix;
        createGroupStack(groupStack, group);
        String template = TEMPLATE.formatted(group, suffix, "");

        cloudFormation(stack, "CreateStack", template, Map.of("FilterName", "errors"));
        assertEquals(group + "|errors", outputValue(describeStacks(stack, "CREATE_COMPLETE"), "FilterRef"),
                "Ref is the composite LogGroupName|FilterName identifier");
        describeFilters(group).then()
            .statusCode(200)
            .body("metricFilters", hasSize(1))
            .body("metricFilters[0].filterName", equalTo("errors"))
            .body("metricFilters[0].filterPattern", equalTo("ERROR"))
            .body("metricFilters[0].metricTransformations[0].metricName", equalTo("ErrorCount"))
            .body("metricFilters[0].metricTransformations[0].metricNamespace", equalTo("Stack/" + suffix))
            .body("metricFilters[0].metricTransformations[0].metricValue", equalTo("1"))
            .body("metricFilters[0].metricTransformations[0].defaultValue", equalTo(0.0f))
            .body("metricFilters[0].metricTransformations[0].unit", equalTo("Count"));

        cloudFormation(stack, "UpdateStack", template, Map.of("FilterName", "errors", "Pattern", "FATAL"));
        assertEquals(group + "|errors", outputValue(describeStacks(stack, "UPDATE_COMPLETE"), "FilterRef"),
                "a pattern change keeps the same filter");
        describeFilters(group).then()
            .statusCode(200)
            .body("metricFilters", hasSize(1))
            .body("metricFilters[0].filterPattern", equalTo("FATAL"));

        cloudFormation(stack, "UpdateStack", template, Map.of("FilterName", "fatal", "Pattern", "FATAL"));
        assertEquals(group + "|fatal", outputValue(describeStacks(stack, "UPDATE_COMPLETE"), "FilterRef"),
                "a name change is a replacement");
        describeFilters(group).then()
            .statusCode(200)
            .body("metricFilters", hasSize(1))
            .body("metricFilters[0].filterName", equalTo("fatal"));
        assertEvent(describeEvents(stack), "DELETE_COMPLETE", group + "|errors");

        cloudFormation(stack, "DeleteStack", null, Map.of());
        awaitStackDeleted(stack);
        describeFilters(group).then()
            .statusCode(200)
            .body("metricFilters", hasSize(0));
        cloudFormation(groupStack, "DeleteStack", null, Map.of());
        awaitStackDeleted(groupStack);
    }

    @Test
    void anUnnamedFilterGetsAGeneratedNameAndItsDimensionsReachTheService() throws InterruptedException {
        String suffix = Long.toString(System.nanoTime(), 36);
        String stack = "cfn-metric-filter-unnamed-" + suffix;
        String groupStack = "cfn-metric-filter-unnamed-group-" + suffix;
        String group = "/cfn/metric-filter/unnamed/" + suffix;
        createGroupStack(groupStack, group);
        String template = UNNAMED_TEMPLATE.formatted(group, suffix);

        cloudFormation(stack, "CreateStack", template, Map.of());
        String ref = outputValue(describeStacks(stack, "CREATE_COMPLETE"), "FilterRef");
        assertTrue(ref.startsWith(group + "|" + stack + "-Filter-"), ref);
        describeFilters(group).then()
            .statusCode(200)
            .body("metricFilters", hasSize(1))
            .body("metricFilters[0].metricTransformations[0].metricValue", equalTo("$.latency"))
            .body("metricFilters[0].metricTransformations[0].dimensions.Route", equalTo("$.route"));

        cloudFormation(stack, "UpdateStack", template, Map.of());
        assertEquals(ref, outputValue(describeStacks(stack, "UPDATE_COMPLETE"), "FilterRef"),
                "an unnamed filter keeps its generated name");

        cloudFormation(stack, "DeleteStack", null, Map.of());
        awaitStackDeleted(stack);
        cloudFormation(groupStack, "DeleteStack", null, Map.of());
        awaitStackDeleted(groupStack);
    }

    @Test
    void aFailedUpdateRestoresThePatternAnInPlaceUpdateChanged() throws InterruptedException {
        String suffix = Long.toString(System.nanoTime(), 36);
        String stack = "cfn-metric-filter-rb-" + suffix;
        String groupStack = "cfn-metric-filter-rb-group-" + suffix;
        String group = "/cfn/metric-filter/rb/" + suffix;
        createGroupStack(groupStack, group);

        cloudFormation(stack, "CreateStack", TEMPLATE.formatted(group, suffix, ""), Map.of("FilterName", "errors"));
        describeStacks(stack, "CREATE_COMPLETE");

        cloudFormation(stack, "UpdateStack", TEMPLATE.formatted(group, suffix, FAILING_RESOURCE.formatted(suffix)),
                Map.of("FilterName", "errors", "Pattern", "FATAL"));
        assertEquals(group + "|errors", outputValue(describeStacks(stack, "UPDATE_ROLLBACK_COMPLETE"), "FilterRef"));
        describeFilters(group).then()
            .statusCode(200)
            .body("metricFilters", hasSize(1))
            .body("metricFilters[0].filterPattern", equalTo("ERROR"));

        cloudFormation(stack, "DeleteStack", null, Map.of());
        awaitStackDeleted(stack);
        cloudFormation(groupStack, "DeleteStack", null, Map.of());
        awaitStackDeleted(groupStack);
    }

    @Test
    void aFailedUpdateRollsAReplacementBack() throws InterruptedException {
        String suffix = Long.toString(System.nanoTime(), 36);
        String stack = "cfn-metric-filter-rb2-" + suffix;
        String groupStack = "cfn-metric-filter-rb2-group-" + suffix;
        String group = "/cfn/metric-filter/rb2/" + suffix;
        createGroupStack(groupStack, group);

        cloudFormation(stack, "CreateStack", TEMPLATE.formatted(group, suffix, ""), Map.of("FilterName", "errors"));
        describeStacks(stack, "CREATE_COMPLETE");

        cloudFormation(stack, "UpdateStack", TEMPLATE.formatted(group, suffix, FAILING_RESOURCE.formatted(suffix)),
                Map.of("FilterName", "fatal"));
        assertEquals(group + "|errors", outputValue(describeStacks(stack, "UPDATE_ROLLBACK_COMPLETE"), "FilterRef"));
        describeFilters(group).then()
            .statusCode(200)
            .body("metricFilters", hasSize(1))
            .body("metricFilters[0].filterName", equalTo("errors"));

        cloudFormation(stack, "DeleteStack", null, Map.of());
        awaitStackDeleted(stack);
        cloudFormation(groupStack, "DeleteStack", null, Map.of());
        awaitStackDeleted(groupStack);
    }

    @Test
    void anInvalidPatternFailsTheResource() throws InterruptedException {
        String suffix = Long.toString(System.nanoTime(), 36);
        String stack = "cfn-metric-filter-bad-" + suffix;
        String groupStack = "cfn-metric-filter-bad-group-" + suffix;
        String group = "/cfn/metric-filter/bad/" + suffix;
        createGroupStack(groupStack, group);

        cloudFormation(stack, "CreateStack", TEMPLATE.formatted(group, suffix, ""),
                Map.of("FilterName", "bad", "Pattern", "{ $.a = }"));

        String events = awaitStackStatus(stack, "ROLLBACK_COMPLETE");
        assertTrue(events.contains("Invalid filter pattern"), "the status reason carries the parse error: " + events);
        cloudFormation(stack, "DeleteStack", null, Map.of());
        awaitStackDeleted(stack);
        cloudFormation(groupStack, "DeleteStack", null, Map.of());
        awaitStackDeleted(groupStack);
    }

    private static void createGroupStack(String stack, String group) {
        cloudFormation(stack, "CreateStack", GROUP_TEMPLATE.formatted(group), Map.of());
        describeStacks(stack, "CREATE_COMPLETE");
    }

    private static Response describeFilters(String group) {
        return given().contentType(LOGS_CONTENT_TYPE).header("Authorization", LOGS_AUTH)
                .header("X-Amz-Target", "Logs_20140328.DescribeMetricFilters")
                .body("{\"logGroupName\":\"" + group + "\"}").post("/");
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

    private static String describeStacks(String stack, String expectedStatus) {
        return given()
            .contentType("application/x-www-form-urlencoded")
            .header("Authorization", CFN_AUTH)
            .formParam("Action", "DescribeStacks")
            .formParam("StackName", stack)
        .when().post("/").then().statusCode(200)
            .body(containsString("<StackStatus>" + expectedStatus + "</StackStatus>"))
            .extract().asString();
    }

    private static String describeEvents(String stack) {
        return given()
            .contentType("application/x-www-form-urlencoded")
            .header("Authorization", CFN_AUTH)
            .formParam("Action", "DescribeStackEvents")
            .formParam("StackName", stack)
        .when().post("/").then().statusCode(200).extract().asString();
    }

    private static void assertEvent(String eventsXml, String status, String physicalId) {
        boolean found = XmlParser.extractGroups(eventsXml, "member").stream()
                .anyMatch(event -> status.equals(event.get("ResourceStatus"))
                        && physicalId.equals(event.get("PhysicalResourceId")));
        assertTrue(found, "no " + status + " event for " + physicalId + " in " + eventsXml);
    }

    private static String awaitStackStatus(String stack, String expectedStatus) throws InterruptedException {
        for (int i = 0; i < 100; i++) {
            String body = given()
                .contentType("application/x-www-form-urlencoded")
                .header("Authorization", CFN_AUTH)
                .formParam("Action", "DescribeStacks")
                .formParam("StackName", stack)
            .when().post("/").then().extract().asString();
            if (body.contains("<StackStatus>" + expectedStatus + "</StackStatus>")) {
                return describeEvents(stack);
            }
            Thread.sleep(50);
        }
        return fail("stack " + stack + " did not reach " + expectedStatus + " within the timeout");
    }

    private static void awaitStackDeleted(String stack) throws InterruptedException {
        for (int i = 0; i < 100; i++) {
            String body = given()
                .contentType("application/x-www-form-urlencoded")
                .header("Authorization", CFN_AUTH)
                .formParam("Action", "DescribeStacks")
                .formParam("StackName", stack)
            .when().post("/").then().extract().asString();
            if (body.contains("does not exist")) {
                return;
            }
            if (body.contains("<StackStatus>DELETE_FAILED</StackStatus>")) {
                fail("stack delete failed: " + body);
            }
            Thread.sleep(50);
        }
        fail("stack " + stack + " was not deleted within the timeout");
    }

    private static String outputValue(String xml, String key) {
        return XmlParser.extractPairs(xml, "Outputs", "OutputKey", "OutputValue").get(key);
    }
}
