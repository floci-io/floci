package io.github.hectorvent.floci.services.cloudformation;

import io.github.hectorvent.floci.core.common.XmlParser;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.response.ValidatableResponse;
import io.restassured.specification.RequestSpecification;
import org.junit.jupiter.api.Test;

import java.util.List;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * Provisions an {@code AWS::IoT::Thing}, an {@code AWS::IoT::Policy} and an
 * {@code AWS::IoT::TopicRule} through one CloudFormation stack and asserts that {@code Ref} and every
 * {@code Fn::GetAtt} attribute carry what the IoT API reports, rather than the literal
 * {@code Logical.Attr} the stub arm would leave behind. The rule's action targets a queue from the
 * same template, so a publish through the rule proves the payload reached the service in the API's
 * shape; a second action on a queue that does not exist proves that one failing action neither
 * fails the publish nor the other action, and that the error action receives the failure. Also
 * covers the in-place update of all three and that deleting the stack removes them.
 */
@QuarkusTest
class IotCfnIntegrationTest {

    private static final String CFN_AUTH =
            "AWS4-HMAC-SHA256 Credential=test/20260903/us-east-1/cloudformation/aws4_request";
    private static final String STACK = "iot-cfn-it";
    private static final String THING = "iot-cfn-it-sensor";
    private static final String POLICY = "iot-cfn-it-policy";
    private static final String RULE = "iotCfnItRule";
    private static final String ROLE_ARN = "arn:aws:iam::000000000000:role/iot-cfn-it";

    private static String template(String serialNumber, String policyAction, String topicFilter, String tagValue) {
        return """
            {
              "Resources": {
                "Queue": {"Type": "AWS::SQS::Queue", "Properties": {"QueueName": "iot-cfn-it-queue"}},
                "ErrorQueue": {"Type": "AWS::SQS::Queue", "Properties": {"QueueName": "iot-cfn-it-error-queue"}},
                "Sensor": {
                  "Type": "AWS::IoT::Thing",
                  "Properties": {
                    "ThingName": "%s",
                    "AttributePayload": {"Attributes": {"SerialNumber": "%s"}}
                  }
                },
                "Policy": {
                  "Type": "AWS::IoT::Policy",
                  "Properties": {
                    "PolicyName": "%s",
                    "PolicyDocument": {
                      "Version": "2012-10-17",
                      "Statement": [{"Effect": "Allow", "Action": "%s", "Resource": "arn:aws:iot:us-east-1:000000000000:client/${iot:Connection.Thing.ThingName}"}]
                    },
                    "Tags": [{"Key": "stack", "Value": "%s"}]
                  }
                },
                "Rule": {
                  "Type": "AWS::IoT::TopicRule",
                  "Properties": {
                    "RuleName": "%s",
                    "TopicRulePayload": {
                      "Sql": "SELECT * FROM '%s'",
                      "AwsIotSqlVersion": "2016-03-23",
                      "RuleDisabled": false,
                      "Actions": [
                        {"Sqs": {"QueueUrl": {"Ref": "Queue"}, "RoleArn": "%s", "UseBase64": false}},
                        {"Sqs": {"QueueUrl": "http://localhost:4566/000000000000/iot-cfn-it-missing-queue", "RoleArn": "%s"}}
                      ],
                      "ErrorAction": {"Sqs": {"QueueUrl": {"Ref": "ErrorQueue"}, "RoleArn": "%s"}}
                    },
                    "Tags": [{"Key": "stack", "Value": "%s"}]
                  }
                }
              },
              "Outputs": {
                "QueueUrl": {"Value": {"Ref": "Queue"}},
                "ErrorQueueUrl": {"Value": {"Ref": "ErrorQueue"}},
                "SensorRef": {"Value": {"Ref": "Sensor"}},
                "SensorArn": {"Value": {"Fn::GetAtt": ["Sensor", "Arn"]}},
                "SensorId": {"Value": {"Fn::GetAtt": ["Sensor", "Id"]}},
                "PolicyRef": {"Value": {"Ref": "Policy"}},
                "PolicyArn": {"Value": {"Fn::GetAtt": ["Policy", "Arn"]}},
                "PolicyId": {"Value": {"Fn::GetAtt": ["Policy", "Id"]}},
                "RuleRef": {"Value": {"Ref": "Rule"}},
                "RuleArn": {"Value": {"Fn::GetAtt": ["Rule", "Arn"]}}
              }
            }
            """.formatted(THING, serialNumber, POLICY, policyAction, tagValue, RULE, topicFilter,
                ROLE_ARN, ROLE_ARN, ROLE_ARN, tagValue);
    }

    @Test
    void iotStackExposesRealIdentifiersUpdatesInPlaceAndDeletesTheResources() throws Exception {
        cloudFormation("CreateStack", template("SN-1", "iot:Connect", "iot-cfn-it/+/telemetry", "v1"));

        String stacks = describeStacks("CREATE_COMPLETE");
        String queueUrl = outputValue(stacks, "QueueUrl");
        String errorQueueUrl = outputValue(stacks, "ErrorQueueUrl");
        String thingArn = outputValue(stacks, "SensorArn");
        String thingId = outputValue(stacks, "SensorId");
        String policyArn = outputValue(stacks, "PolicyArn");
        String ruleArn = outputValue(stacks, "RuleArn");
        assertEquals(THING, outputValue(stacks, "SensorRef"));
        assertEquals("arn:aws:iot:us-east-1:000000000000:thing/" + THING, thingArn);
        assertEquals(POLICY, outputValue(stacks, "PolicyRef"));
        assertEquals(POLICY, outputValue(stacks, "PolicyId"));
        assertEquals("arn:aws:iot:us-east-1:000000000000:policy/" + POLICY, policyArn);
        assertEquals(RULE, outputValue(stacks, "RuleRef"));
        assertEquals("arn:aws:iot:us-east-1:000000000000:rule/" + RULE, ruleArn);

        given()
        .when()
            .get("/things/" + THING)
        .then()
            .statusCode(200)
            .body("thingArn", equalTo(thingArn))
            .body("thingId", equalTo(thingId))
            .body("attributes.SerialNumber", equalTo("SN-1"));
        given()
        .when()
            .get("/policies/" + POLICY)
        .then()
            .statusCode(200)
            .body("policyArn", equalTo(policyArn))
            .body("defaultVersionId", equalTo("1"))
            .body("policyDocument", containsString("iot:Connect"))
            .body("policyDocument", containsString("${iot:Connection.Thing.ThingName}"));
        assertTagValue(policyArn, "v1");
        given()
        .when()
            .get("/rules/" + RULE)
        .then()
            .statusCode(200)
            .body("ruleArn", equalTo(ruleArn))
            .body("rule.sql", equalTo("SELECT * FROM 'iot-cfn-it/+/telemetry'"))
            .body("rule.awsIotSqlVersion", equalTo("2016-03-23"))
            .body("rule.ruleDisabled", equalTo(false))
            .body("rule.actions[0].sqs.queueUrl", equalTo(queueUrl))
            .body("rule.errorAction.sqs.queueUrl", equalTo(errorQueueUrl));
        assertTagValue(ruleArn, "v1");

        given()
            .contentType("text/plain")
            .body("iot-cfn-it-payload")
        .when()
            .post("/topics/iot-cfn-it/d1/telemetry")
        .then()
            .statusCode(200);
        receiveMessage(queueUrl).body(containsString("iot-cfn-it-payload"));
        receiveMessage(errorQueueUrl)
            .body(containsString(RULE))
            .body(containsString("SqsAction"))
            .body(containsString("iot-cfn-it-missing-queue"));

        cloudFormation("UpdateStack", template("SN-2", "iot:Publish", "iot-cfn-it/+/telemetry/v2", "v2"));

        String updated = describeStacks("UPDATE_COMPLETE");
        assertEquals(thingId, outputValue(updated, "SensorId"));
        given()
        .when()
            .get("/things/" + THING)
        .then()
            .statusCode(200)
            .body("thingId", equalTo(thingId))
            .body("attributes.SerialNumber", equalTo("SN-2"));
        given()
        .when()
            .get("/policies/" + POLICY)
        .then()
            .statusCode(200)
            .body("defaultVersionId", equalTo("2"))
            .body("policyDocument", containsString("iot:Publish"));
        assertTagValue(policyArn, "v2");
        given()
        .when()
            .get("/rules/" + RULE)
        .then()
            .statusCode(200)
            .body("ruleArn", equalTo(ruleArn))
            .body("rule.sql", equalTo("SELECT * FROM 'iot-cfn-it/+/telemetry/v2'"));
        assertTagValue(ruleArn, "v2");

        // Four more document changes. The service refuses a sixth version, so the stack deletes
        // the oldest one first, as the AWS handler does, and the policy keeps five versions.
        for (String action : List.of("iot:Subscribe", "iot:Receive", "iot:GetThingShadow", "iot:UpdateThingShadow")) {
            cloudFormation("UpdateStack", template("SN-2", action, "iot-cfn-it/+/telemetry/v2", "v2"));
            describeStacks("UPDATE_COMPLETE");
        }
        given()
        .when()
            .get("/policies/" + POLICY + "/version")
        .then()
            .statusCode(200)
            .body("policyVersions.versionId", contains("2", "3", "4", "5", "6"))
            .body("policyVersions.find { it.isDefaultVersion }.versionId", equalTo("6"));
        given()
        .when()
            .get("/policies/" + POLICY)
        .then()
            .statusCode(200)
            .body("defaultVersionId", equalTo("6"))
            .body("policyDocument", containsString("iot:UpdateThingShadow"));

        cloudFormation("DeleteStack", null);
        awaitStackDeleted();

        given().when().get("/things/" + THING).then().statusCode(404);
        given().when().get("/policies/" + POLICY).then().statusCode(404);
        given().when().get("/rules/" + RULE).then().statusCode(404);
    }

    private static ValidatableResponse receiveMessage(String queueUrl) {
        return given()
            .contentType("application/x-www-form-urlencoded")
            .formParam("Action", "ReceiveMessage")
            .formParam("QueueUrl", queueUrl)
            .formParam("MaxNumberOfMessages", "1")
        .when()
            .post("/")
        .then()
            .statusCode(200);
    }

    private static void assertTagValue(String arn, String expected) {
        given()
            .queryParam("resourceArn", arn)
        .when()
            .get("/tags")
        .then()
            .statusCode(200)
            .body("tags.find { it.Key == 'stack' }.Value", equalTo(expected));
    }

    private static void cloudFormation(String action, String templateBody) {
        RequestSpecification request = given()
            .contentType("application/x-www-form-urlencoded")
            .header("Authorization", CFN_AUTH)
            .formParam("Action", action)
            .formParam("StackName", STACK);
        if (templateBody != null) {
            request.formParam("TemplateBody", templateBody);
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
}
