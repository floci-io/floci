package io.github.hectorvent.floci.services.cloudformation;

import io.github.hectorvent.floci.core.common.XmlParser;
import io.github.hectorvent.floci.testing.RestAssuredJsonUtils;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.response.Response;
import io.restassured.specification.RequestSpecification;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.not;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * Provisions an {@code AWS::CloudWatch::Dashboard} through a CloudFormation stack and reads it
 * back through the CloudWatch API. {@code Ref} is the dashboard name; a body or tag change updates
 * the dashboard in place; a name change replaces it and deletes the displaced dashboard once the
 * update commits; a failed update rolls the replacement back; and the stack delete removes it.
 */
@QuarkusTest
class CloudFormationCloudWatchDashboardIntegrationTest {

    private static final String CFN_AUTH =
            "AWS4-HMAC-SHA256 Credential=test/20260908/us-east-1/cloudformation/aws4_request";
    private static final String CW_AUTH =
            "AWS4-HMAC-SHA256 Credential=test/20260908/us-east-1/monitoring/aws4_request";

    /**
     * The body is an {@code Fn::Join} of fragments, the shape the CDK emits, and the name, the
     * widget title and the tag value are parameters so one template drives every update.
     */
    private static final String TEMPLATE = """
        {
          "Parameters": {
            "Name": {"Type": "String"},
            "Title": {"Type": "String", "Default": "Requests"},
            "Team": {"Type": "String", "Default": "platform"}
          },
          "Resources": {
            "Dashboard": {
              "Type": "AWS::CloudWatch::Dashboard",
              "Properties": {
                "DashboardName": {"Ref": "Name"},
                "DashboardBody": {"Fn::Join": ["", [
                  "{\\"widgets\\":[{\\"type\\":\\"text\\",\\"properties\\":{\\"markdown\\":\\"",
                  {"Ref": "Title"},
                  "\\"}}]}"
                ]]},
                "Tags": [{"Key": "team", "Value": {"Ref": "Team"}}%s]
              }
            }%s
          },
          "Outputs": {
            "DashboardRef": {"Value": {"Ref": "Dashboard"}}
          }
        }
        """;

    private static final String UNNAMED_TEMPLATE = """
        {
          "Parameters": {"Title": {"Type": "String", "Default": "Latency"}},
          "Resources": {
            "Dashboard": {
              "Type": "AWS::CloudWatch::Dashboard",
              "Properties": {
                "DashboardBody": {"widgets": [{"type": "text", "properties": {"markdown": {"Ref": "Title"}}}]}
              }
            }
          },
          "Outputs": {
            "DashboardRef": {"Value": {"Ref": "Dashboard"}}
          }
        }
        """;

    private static final String EXTRA_TAG = ", {\"Key\": \"env\", \"Value\": \"dev\"}";

    /** A resource that fails after the dashboard, so the update rolls the replacement back. */
    private static final String FAILING_RESOURCE = """
        ,
            "BadSecret": {
              "Type": "AWS::SecretsManager::Secret",
              "DependsOn": "Dashboard",
              "Properties": {
                "Name": "dashboard-rollback-%s",
                "SecretString": "explicit",
                "GenerateSecretString": {"PasswordLength": 32}
              }
            }""";

    @BeforeAll
    static void configureRestAssured() {
        RestAssuredJsonUtils.configureAwsContentTypes();
    }

    @Test
    void dashboardFollowsTheTemplateThroughCreateUpdateReplacementAndDelete() throws InterruptedException {
        String suffix = Long.toString(System.nanoTime(), 36);
        String stack = "cfn-dashboard-" + suffix;
        String name = "ops-" + suffix;
        String renamed = "ops-v2-" + suffix;

        cloudFormation(stack, "CreateStack", TEMPLATE.formatted(EXTRA_TAG, ""), Map.of("Name", name));
        assertEquals(name, outputValue(describeStacks(stack, "CREATE_COMPLETE"), "DashboardRef"),
                "Ref is the dashboard name");
        getDashboard(name).then()
            .statusCode(200)
            .body("GetDashboardResponse.GetDashboardResult.DashboardName", equalTo(name))
            .body("GetDashboardResponse.GetDashboardResult.DashboardBody",
                    equalTo("{\"widgets\":[{\"type\":\"text\",\"properties\":{\"markdown\":\"Requests\"}}]}"));
        String arn = dashboardArn(name);
        assertTags(arn, Map.of("team", "platform", "env", "dev"), Map.of());

        cloudFormation(stack, "UpdateStack", TEMPLATE.formatted("", ""),
                Map.of("Name", name, "Title", "Errors", "Team", "data"));
        assertEquals(name, outputValue(describeStacks(stack, "UPDATE_COMPLETE"), "DashboardRef"),
                "a body or tag change keeps the same dashboard");
        getDashboard(name).then()
            .statusCode(200)
            .body("GetDashboardResponse.GetDashboardResult.DashboardBody", containsString("\"Errors\""));
        assertTags(arn, Map.of("team", "data"), Map.of("env", "dev"));

        cloudFormation(stack, "UpdateStack", TEMPLATE.formatted("", ""),
                Map.of("Name", renamed, "Title", "Errors", "Team", "data"));
        assertEquals(renamed, outputValue(describeStacks(stack, "UPDATE_COMPLETE"), "DashboardRef"),
                "a name change is a replacement");
        getDashboard(renamed).then()
            .statusCode(200)
            .body("GetDashboardResponse.GetDashboardResult.DashboardBody", containsString("\"Errors\""));
        assertTags(dashboardArn(renamed), Map.of("team", "data"), Map.of());
        getDashboard(name).then()
            .statusCode(404)
            .body("ErrorResponse.Error.Code", equalTo("ResourceNotFound"));
        assertEvent(describeEvents(stack), "DELETE_COMPLETE", name);

        cloudFormation(stack, "DeleteStack", null, Map.of());
        awaitStackDeleted(stack);
        getDashboard(renamed).then()
            .statusCode(404)
            .body("ErrorResponse.Error.Code", equalTo("ResourceNotFound"));
    }

    @Test
    void anUnnamedDashboardKeepsItsGeneratedNameAcrossUpdates() throws InterruptedException {
        String suffix = Long.toString(System.nanoTime(), 36);
        String stack = "cfn-dashboard-unnamed-" + suffix;

        cloudFormation(stack, "CreateStack", UNNAMED_TEMPLATE, Map.of());
        String generated = outputValue(describeStacks(stack, "CREATE_COMPLETE"), "DashboardRef");
        assertTrue(generated.startsWith(stack + "-Dashboard-"), generated);
        getDashboard(generated).then()
            .statusCode(200)
            .body("GetDashboardResponse.GetDashboardResult.DashboardBody",
                    equalTo("{\"widgets\":[{\"type\":\"text\",\"properties\":{\"markdown\":\"Latency\"}}]}"));

        cloudFormation(stack, "UpdateStack", UNNAMED_TEMPLATE, Map.of("Title", "Throughput"));
        assertEquals(generated, outputValue(describeStacks(stack, "UPDATE_COMPLETE"), "DashboardRef"));
        getDashboard(generated).then()
            .statusCode(200)
            .body("GetDashboardResponse.GetDashboardResult.DashboardBody", containsString("\"Throughput\""));

        cloudFormation(stack, "DeleteStack", null, Map.of());
        awaitStackDeleted(stack);
        getDashboard(generated).then().statusCode(404);
    }

    @Test
    void aFailedUpdateRollsTheReplacementBack() throws InterruptedException {
        String suffix = Long.toString(System.nanoTime(), 36);
        String stack = "cfn-dashboard-rb-" + suffix;
        String name = "ops-rb-" + suffix;
        String renamed = "ops-rb-v2-" + suffix;

        cloudFormation(stack, "CreateStack", TEMPLATE.formatted("", ""), Map.of("Name", name));
        describeStacks(stack, "CREATE_COMPLETE");

        cloudFormation(stack, "UpdateStack", TEMPLATE.formatted("", FAILING_RESOURCE.formatted(suffix)),
                Map.of("Name", renamed));
        String rolledBack = describeStacks(stack, "UPDATE_ROLLBACK_COMPLETE");
        assertEquals(name, outputValue(rolledBack, "DashboardRef"), "the resource names the prior dashboard again");
        getDashboard(name).then().statusCode(200);
        getDashboard(renamed).then()
            .statusCode(404)
            .body("ErrorResponse.Error.Code", equalTo("ResourceNotFound"));

        cloudFormation(stack, "DeleteStack", null, Map.of());
        awaitStackDeleted(stack);
    }

    private static Response getDashboard(String name) {
        return given()
            .contentType("application/x-www-form-urlencoded")
            .header("Authorization", CW_AUTH)
            .formParam("Action", "GetDashboard")
            .formParam("DashboardName", name)
        .when().post("/");
    }

    private static String dashboardArn(String name) {
        return getDashboard(name).then().statusCode(200)
            .extract().path("GetDashboardResponse.GetDashboardResult.DashboardArn");
    }

    private static void assertTags(String arn, Map<String, String> present, Map<String, String> absent) {
        String tags = given()
            .contentType("application/x-www-form-urlencoded")
            .header("Authorization", CW_AUTH)
            .formParam("Action", "ListTagsForResource")
            .formParam("ResourceARN", arn)
        .when().post("/").then().statusCode(200).extract().asString();
        present.forEach((key, value) -> assertTrue(
                tags.contains("<Key>" + key + "</Key>") && tags.contains("<Value>" + value + "</Value>"),
                "expected tag " + key + "=" + value + " in " + tags));
        absent.forEach((key, value) -> assertTrue(!tags.contains("<Key>" + key + "</Key>"),
                "tag " + key + " should be gone: " + tags));
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
        Matcher m = Pattern.compile("<member>(.*?)</member>", Pattern.DOTALL).matcher(eventsXml);
        while (m.find()) {
            String member = m.group(1);
            if (member.contains("<ResourceStatus>" + status + "</ResourceStatus>")
                    && member.contains("<PhysicalResourceId>" + physicalId + "</PhysicalResourceId>")) {
                return;
            }
        }
        fail("no " + status + " event for " + physicalId + " in " + eventsXml);
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
