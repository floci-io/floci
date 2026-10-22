package io.github.hectorvent.floci.services.cloudformation;

import io.github.hectorvent.floci.core.common.XmlParser;
import io.github.hectorvent.floci.testing.CfnStackWaits;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.specification.RequestSpecification;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.containsString;
import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * An UpdateStack that changes an AWS::SNS::Subscription's filter and drops its raw delivery flag
 * updates the subscription in place, as the resource schema says it does: the subscription keeps
 * its ARN and reads back the new filter and the default delivery.
 */
@QuarkusTest
class SnsSubscriptionUpdateCfnIntegrationTest {

    private static final String CFN_AUTH =
            "AWS4-HMAC-SHA256 Credential=test/20260905/us-east-1/cloudformation/aws4_request";
    private static final String SNS_AUTH =
            "AWS4-HMAC-SHA256 Credential=test/20260905/us-east-1/sns/aws4_request";
    private static final String STACK = "sns-subscription-update-cfn-it";

    private static final String TEMPLATE = """
        {
          "Resources": {
            "Queue": {
              "Type": "AWS::SQS::Queue",
              "Properties": {"QueueName": "sns-subscription-update-cfn-it"}
            },
            "Topic": {
              "Type": "AWS::SNS::Topic",
              "Properties": {"TopicName": "sns-subscription-update-cfn-it"}
            },
            "Subscription": {
              "Type": "AWS::SNS::Subscription",
              "Properties": {
                "TopicArn": {"Ref": "Topic"},
                "Protocol": "sqs",
                "Endpoint": {"Fn::GetAtt": ["Queue", "Arn"]},
                %s
              }
            }
          },
          "Outputs": {
            "SubscriptionArn": {"Value": {"Ref": "Subscription"}}
          }
        }
        """;

    @Test
    void aChangedFilterPolicyAndADroppedRawMessageDeliveryReachTheSubscription() {
        cloudFormation("CreateStack", TEMPLATE.formatted("""
                "FilterPolicy": {"eventType": ["created"]},
                "RawMessageDelivery": true"""));
        String subscriptionArn = outputValue(describeStacks("CREATE_COMPLETE"), "SubscriptionArn");
        Map<String, String> created = subscriptionAttributes(subscriptionArn);
        assertEquals("{\"eventType\":[\"created\"]}", created.get("FilterPolicy"));
        assertEquals("true", created.get("RawMessageDelivery"));

        cloudFormation("UpdateStack", TEMPLATE.formatted("""
                "FilterPolicy": {"eventType": ["deleted"]}"""));
        assertEquals(subscriptionArn, outputValue(describeStacks("UPDATE_COMPLETE"), "SubscriptionArn"),
                "a FilterPolicy change is an in-place update, so the subscription keeps its ARN");
        Map<String, String> updated = subscriptionAttributes(subscriptionArn);
        assertEquals("{\"eventType\":[\"deleted\"]}", updated.get("FilterPolicy"),
                "the new filter has to reach the existing subscription");
        assertEquals("false", updated.get("RawMessageDelivery"),
                "a dropped RawMessageDelivery goes back to the default");

        cloudFormation("DeleteStack", null);
        CfnStackWaits.awaitStackDeleted(STACK);
    }

    private static void cloudFormation(String action, String template) {
        RequestSpecification request = given()
            .contentType("application/x-www-form-urlencoded")
            .header("Authorization", CFN_AUTH)
            .formParam("Action", action)
            .formParam("StackName", STACK);
        if (template != null) {
            request.formParam("TemplateBody", template);
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

    private static String outputValue(String xml, String key) {
        return XmlParser.extractPairs(xml, "Outputs", "OutputKey", "OutputValue").get(key);
    }

    private static Map<String, String> subscriptionAttributes(String subscriptionArn) {
        String xml = given()
            .contentType("application/x-www-form-urlencoded")
            .header("Authorization", SNS_AUTH)
            .formParam("Action", "GetSubscriptionAttributes")
            .formParam("SubscriptionArn", subscriptionArn)
        .when().post("/").then().statusCode(200).extract().asString();
        return XmlParser.extractPairs(xml, "entry", "key", "value");
    }
}
