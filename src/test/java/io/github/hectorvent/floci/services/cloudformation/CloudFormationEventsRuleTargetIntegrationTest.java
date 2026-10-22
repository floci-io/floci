package io.github.hectorvent.floci.services.cloudformation;

import io.github.hectorvent.floci.testing.CfnStackWaits;
import io.github.hectorvent.floci.testing.RestAssuredJsonUtils;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.path.json.JsonPath;
import io.restassured.response.ValidatableResponse;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.emptyOrNullString;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasKey;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.not;
import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * An {@code AWS::Events::Rule} target's {@code DeadLetterConfig}, {@code RetryPolicy},
 * {@code RoleArn} and {@code EcsParameters} reach the rule as PutTargets would set them, and
 * delivery honours the dead-letter queue the stack declares.
 */
@QuarkusTest
class CloudFormationEventsRuleTargetIntegrationTest {

    private static final String EB_CT = "application/x-amz-json-1.1";
    private static final String SQS_CT = "application/x-amz-json-1.0";
    private static final String STACK = "cfn-rule-targets";
    private static final String BUS = "cfn-rule-targets-bus";

    private static final String TEMPLATE = """
            {
              "Resources": {
                "Dlq": {"Type": "AWS::SQS::Queue", "Properties": {"QueueName": "cfn-rule-targets-dlq"}},
                "Bus": {"Type": "AWS::Events::EventBus", "Properties": {"Name": "cfn-rule-targets-bus"}},
                "Undeliverable": {
                  "Type": "AWS::Events::Rule",
                  "Properties": {
                    "Name": "cfn-rule-targets-undeliverable",
                    "EventBusName": {"Ref": "Bus"},
                    "EventPattern": {"source": ["cfn.rule.targets"]},
                    "Targets": [{
                      "Id": "MissingQueue",
                      "Arn": {"Fn::Sub": "arn:${AWS::Partition}:sqs:${AWS::Region}:${AWS::AccountId}:cfn-rule-targets-missing"},
                      "DeadLetterConfig": {"Arn": {"Fn::GetAtt": ["Dlq", "Arn"]}},
                      "RetryPolicy": {"MaximumRetryAttempts": 2, "MaximumEventAgeInSeconds": 60}
                    }]
                  }
                },
                "RunTask": {
                  "Type": "AWS::Events::Rule",
                  "Properties": {
                    "Name": "cfn-rule-targets-ecs",
                    "EventBusName": {"Ref": "Bus"},
                    "EventPattern": {"source": ["cfn.rule.targets.never"]},
                    "Targets": [{
                      "Id": "Jobs",
                      "Arn": {"Fn::Sub": "arn:${AWS::Partition}:ecs:${AWS::Region}:${AWS::AccountId}:cluster/jobs"},
                      "RoleArn": {"Fn::Sub": "arn:${AWS::Partition}:iam::${AWS::AccountId}:role/events-run-task"},
                      "EcsParameters": {
                        "TaskDefinitionArn": "arn:aws:ecs:us-east-1:000000000000:task-definition/job:1",
                        "TaskCount": 1,
                        "LaunchType": "FARGATE",
                        "NetworkConfiguration": {"AwsVpcConfiguration": {
                          "Subnets": ["subnet-1"], "SecurityGroups": ["sg-1"], "AssignPublicIp": "DISABLED"}},
                        "PlacementStrategies": [{"Type": "spread", "Field": "attribute:ecs.availability-zone"}],
                        "TagList": [{"Key": "team", "Value": "jobs"}]
                      }
                    }]
                  }
                }
              }
            }
            """;

    @BeforeAll
    static void configureRestAssured() {
        RestAssuredJsonUtils.configureAwsContentTypes();
    }

    @Test
    void ruleTargetsKeepTheirDeliverySettingsAndTheDeadLetterQueueReceivesUndeliverableEvents() {
        given().contentType("application/x-www-form-urlencoded")
                .formParam("Action", "CreateStack")
                .formParam("StackName", STACK)
                .formParam("TemplateBody", TEMPLATE)
                .when().post("/").then().statusCode(200);
        assertEquals("CREATE_COMPLETE", CfnStackWaits.awaitTerminal(STACK).status());

        String dlqUrl = given().contentType(SQS_CT)
                .header("X-Amz-Target", "AmazonSQS.GetQueueUrl")
                .body("{\"QueueName\":\"cfn-rule-targets-dlq\"}")
                .when().post("/").then().statusCode(200)
                .extract().jsonPath().getString("QueueUrl");
        String dlqArn = given().contentType(SQS_CT)
                .header("X-Amz-Target", "AmazonSQS.GetQueueAttributes")
                .body("{\"QueueUrl\":\"" + dlqUrl + "\",\"AttributeNames\":[\"QueueArn\"]}")
                .when().post("/").then().statusCode(200)
                .extract().jsonPath().getString("Attributes.QueueArn");

        listTargets("cfn-rule-targets-undeliverable")
                .body("Targets", hasSize(1))
                .body("Targets[0].DeadLetterConfig.Arn", equalTo(dlqArn))
                .body("Targets[0].RetryPolicy.MaximumRetryAttempts", equalTo(2))
                .body("Targets[0].RetryPolicy.MaximumEventAgeInSeconds", equalTo(60))
                .body("Targets[0]", not(hasKey("RoleArn")));
        listTargets("cfn-rule-targets-ecs")
                .body("Targets[0].RoleArn", equalTo("arn:aws:iam::000000000000:role/events-run-task"))
                .body("Targets[0].EcsParameters.TaskDefinitionArn",
                        equalTo("arn:aws:ecs:us-east-1:000000000000:task-definition/job:1"))
                .body("Targets[0].EcsParameters.NetworkConfiguration.awsvpcConfiguration.Subnets[0]",
                        equalTo("subnet-1"))
                .body("Targets[0].EcsParameters.PlacementStrategy[0].type", equalTo("spread"))
                .body("Targets[0].EcsParameters.Tags[0].Key", equalTo("team"))
                .body("Targets[0]", not(hasKey("RetryPolicy")))
                .body("Targets[0]", not(hasKey("DeadLetterConfig")));

        given().contentType(EB_CT)
                .header("X-Amz-Target", "AWSEvents.PutEvents")
                .body("""
                        {"Entries": [{"EventBusName": "%s", "Source": "cfn.rule.targets",
                          "DetailType": "Undeliverable", "Detail": "{\\"orderId\\":\\"o-9\\"}"}]}
                        """.formatted(BUS))
                .when().post("/").then().statusCode(200)
                .body("FailedEntryCount", equalTo(0));

        String ruleArn = given().contentType(EB_CT)
                .header("X-Amz-Target", "AWSEvents.DescribeRule")
                .body("{\"Name\":\"cfn-rule-targets-undeliverable\",\"EventBusName\":\"" + BUS + "\"}")
                .when().post("/").then().statusCode(200)
                .extract().jsonPath().getString("Arn");
        JsonPath received = given().contentType(SQS_CT)
                .header("X-Amz-Target", "AmazonSQS.ReceiveMessage")
                .body("{\"QueueUrl\":\"" + dlqUrl + "\",\"MaxNumberOfMessages\":10,"
                        + "\"MessageAttributeNames\":[\"All\"]}")
                .when().post("/").then().statusCode(200)
                .body("Messages", hasSize(1))
                .body("Messages[0].MessageAttributes.RULE_ARN.StringValue", equalTo(ruleArn))
                .body("Messages[0].MessageAttributes.TARGET_ARN.StringValue",
                        equalTo(dlqArn.replace("cfn-rule-targets-dlq", "cfn-rule-targets-missing")))
                .body("Messages[0].MessageAttributes.ERROR_CODE.StringValue", equalTo("NO_RESOURCE"))
                .body("Messages[0].MessageAttributes.ERROR_MESSAGE.StringValue", not(emptyOrNullString()))
                .body("Messages[0].MessageAttributes.RETRY_ATTEMPTS.StringValue", equalTo("0"))
                .body("Messages[0].MessageAttributes", not(hasKey("EXHAUSTED_RETRY_CONDITION")))
                .extract().jsonPath();
        assertEquals("o-9", new JsonPath(received.getString("Messages[0].Body")).getString("detail.orderId"));
    }

    /**
     * With the real engine: a member whose Fn::If takes AWS::NoValue is left out of the target,
     * and a literal empty string, a tag's Value, is kept.
     */
    @Test
    void aMemberAnFnIfDropsIsLeftOutAndAnEmptyStringKept() {
        String template = """
                {"Conditions": {"Never": {"Fn::Equals": ["a", "b"]}},
                 "Resources": {"Rule": {"Type": "AWS::Events::Rule", "Properties": {
                   "Name": "cfn-rule-targets-novalue",
                   "EventPattern": {"source": ["cfn.rule.targets.novalue"]},
                   "Targets": [{"Id": "Ecs", "Arn": "arn:aws:ecs:us-east-1:000000000000:cluster/jobs",
                     "RoleArn": {"Fn::If": ["Never", "arn:aws:iam::000000000000:role/never",
                                            {"Ref": "AWS::NoValue"}]},
                     "RetryPolicy": {"MaximumRetryAttempts": {"Fn::If": ["Never", 3, {"Ref": "AWS::NoValue"}]},
                                     "MaximumEventAgeInSeconds": 120},
                     "EcsParameters": {"TaskDefinitionArn": "arn:aws:ecs:us-east-1:000000000000:task-definition/job:1",
                                       "TagList": [{"Key": "team", "Value": ""}]}}]}}}}
                """;
        given().contentType("application/x-www-form-urlencoded")
                .formParam("Action", "CreateStack")
                .formParam("StackName", "cfn-rule-targets-novalue")
                .formParam("TemplateBody", template)
                .when().post("/").then().statusCode(200);
        try {
            assertEquals("CREATE_COMPLETE", CfnStackWaits.awaitTerminal("cfn-rule-targets-novalue").status());
            listTargets("cfn-rule-targets-novalue", "default")
                    .body("Targets[0]", not(hasKey("RoleArn")))
                    .body("Targets[0].RetryPolicy", not(hasKey("MaximumRetryAttempts")))
                    .body("Targets[0].RetryPolicy.MaximumEventAgeInSeconds", equalTo(120))
                    .body("Targets[0].EcsParameters.Tags[0].Value", equalTo(""));
        } finally {
            deleteStack("cfn-rule-targets-novalue");
        }
    }

    /**
     * The resource schema types EventPattern as ["string", "object"], so a template can build the
     * pattern as text, for example with Fn::Sub. The rule stores the JSON the string holds.
     */
    @Test
    void anEventPatternGivenAsAStringIsStoredAsTheJsonItHolds() {
        String template = """
                {"Parameters": {"Src": {"Type": "String", "Default": "cfn.rule.pattern.string"}},
                 "Resources": {"Rule": {"Type": "AWS::Events::Rule", "Properties": {
                   "Name": "cfn-rule-pattern-string",
                   "EventPattern": {"Fn::Sub": "{\\"source\\":[\\"${Src}\\"]}"}}}}}
                """;
        given().contentType("application/x-www-form-urlencoded")
                .formParam("Action", "CreateStack")
                .formParam("StackName", "cfn-rule-pattern-string")
                .formParam("TemplateBody", template)
                .when().post("/").then().statusCode(200);
        try {
            assertEquals("CREATE_COMPLETE", CfnStackWaits.awaitTerminal("cfn-rule-pattern-string").status());
            given().contentType(EB_CT)
                    .header("X-Amz-Target", "AWSEvents.DescribeRule")
                    .body("{\"Name\":\"cfn-rule-pattern-string\"}")
                    .when().post("/").then().statusCode(200)
                    .body("EventPattern", equalTo("{\"source\":[\"cfn.rule.pattern.string\"]}"));
        } finally {
            deleteStack("cfn-rule-pattern-string");
        }
    }

    /** An Fn::If that chooses the whole Targets list is resolved before the targets are read. */
    @Test
    void targetsAnFnIfChoosesAreProvisioned() {
        String template = """
                {"Conditions": {"Always": {"Fn::Equals": ["a", "a"]}},
                 "Resources": {"Rule": {"Type": "AWS::Events::Rule", "Properties": {
                   "Name": "cfn-rule-targets-if",
                   "EventPattern": {"source": ["cfn.rule.targets.if"]},
                   "Targets": {"Fn::If": ["Always",
                     [{"Id": "Queue", "Arn": "arn:aws:sqs:us-east-1:000000000000:cfn-rule-targets-if"}],
                     {"Ref": "AWS::NoValue"}]}}}}}
                """;
        given().contentType("application/x-www-form-urlencoded")
                .formParam("Action", "CreateStack")
                .formParam("StackName", "cfn-rule-targets-if")
                .formParam("TemplateBody", template)
                .when().post("/").then().statusCode(200);
        try {
            assertEquals("CREATE_COMPLETE", CfnStackWaits.awaitTerminal("cfn-rule-targets-if").status());
            listTargets("cfn-rule-targets-if", "default")
                    .body("Targets[0].Id", equalTo("Queue"))
                    .body("Targets[0].Arn", equalTo("arn:aws:sqs:us-east-1:000000000000:cfn-rule-targets-if"));
        } finally {
            deleteStack("cfn-rule-targets-if");
        }
    }

    /**
     * A target member of the wrong type fails the rule before it exists, with the message measured
     * on CloudFormation (eu-west-1, 2026-10-18): template validation's where a member cannot be read
     * as the object or the integer the resource schema declares, the handler's model validation for
     * a boolean that is neither true nor false.
     */
    @Test
    void aTargetMemberOfTheWrongTypeFailsTheRuleBeforeItExists() {
        Map<String, String> members = new LinkedHashMap<>();
        members.put("\"RetryPolicy\": {\"MaximumRetryAttempts\": 4.5}",
                "Property [MaximumRetryAttempts] expected type: Integer, found: Float");
        members.put("\"DeadLetterConfig\": \"arn:aws:sqs:us-east-1:000000000000:cfn-rule-targets-dlq\"",
                "Property [DeadLetterConfig] expected type: JSONObject, found: String");
        members.put("\"EcsParameters\": {\"TaskDefinitionArn\": \"arn:aws:ecs:us-east-1:000000000000:task-definition/job:1\","
                        + " \"EnableECSManagedTags\": \"yes\"}",
                "Model validation failed (#/Targets/0/EcsParameters/EnableECSManagedTags:"
                        + " expected type: Boolean, found: String)");
        int n = 0;
        for (Map.Entry<String, String> member : members.entrySet()) {
            String stack = "cfn-rule-targets-typed-" + n;
            String rule = "cfn-rule-targets-typed-" + n++;
            given().contentType("application/x-www-form-urlencoded")
                    .formParam("Action", "CreateStack")
                    .formParam("StackName", stack)
                    .formParam("TemplateBody", """
                            {"Resources": {"Rule": {"Type": "AWS::Events::Rule", "Properties": {
                              "Name": "%s", "EventPattern": {"source": ["cfn.rule.targets.typed"]},
                              "Targets": [{"Id": "T", "Arn": "arn:aws:sqs:us-east-1:000000000000:typed", %s}]}}}}
                            """.formatted(rule, member.getKey()))
                    .when().post("/").then().statusCode(200);
            try {
                assertEquals("ROLLBACK_COMPLETE", CfnStackWaits.awaitTerminal(stack).status(), member.getKey());
                assertEquals(member.getValue(), CfnStackWaits.failedResourceReason(stack), member.getKey());
                given().contentType(EB_CT)
                        .header("X-Amz-Target", "AWSEvents.DescribeRule")
                        .body("{\"Name\":\"" + rule + "\"}")
                        .when().post("/").then().statusCode(400)
                        .body("__type", equalTo("ResourceNotFoundException"));
            } finally {
                deleteStack(stack);
            }
        }
    }

    /**
     * Measured on CloudFormation (eu-west-1, 2026-10-18): a Ref to a CommaDelimitedList, Fn::Split
     * and an Fn::If choosing either are lists of subnets, here also inside an EcsParameters an Fn::If
     * chooses; a Ref to a String inside a literal list is one subnet; a Ref to a String where the
     * list is expected fails template validation.
     */
    @Test
    void aSubnetListTakesEveryFormThatYieldsAList() {
        Map<String, String> forms = new LinkedHashMap<>();
        forms.put("cdl", "{\"Ref\": \"Cdl\"}");
        forms.put("split", "{\"Fn::Split\": [\",\", {\"Ref\": \"Str\"}]}");
        forms.put("if", "{\"Fn::If\": [\"Yes\", {\"Ref\": \"Cdl\"}, [\"subnet-x\"]]}");
        forms.put("item", "[{\"Ref\": \"Str\"}]");
        StringBuilder rules = new StringBuilder();
        forms.forEach((name, subnets) -> rules.append(rules.isEmpty() ? "" : ",").append(listRule(name,
                "{\"TaskDefinitionArn\": \"arn:aws:ecs:us-east-1:000000000000:task-definition/job:1\","
                        + " \"NetworkConfiguration\": {\"AwsVpcConfiguration\": {\"Subnets\": " + subnets + "}}}")));
        rules.append(",").append(listRule("chosen", "{\"Fn::If\": [\"Yes\", {\"TaskDefinitionArn\":"
                + " \"arn:aws:ecs:us-east-1:000000000000:task-definition/job:1\", \"NetworkConfiguration\":"
                + " {\"AwsVpcConfiguration\": {\"Subnets\": {\"Ref\": \"Cdl\"}}}}, {\"Ref\": \"AWS::NoValue\"}]}"));
        String stack = "cfn-rule-targets-lists";
        createListStack(stack, rules.toString());
        try {
            assertEquals("CREATE_COMPLETE", CfnStackWaits.awaitTerminal(stack).status());
            for (String name : List.of("cdl", "split", "if", "chosen")) {
                listTargets("cfn-rule-targets-list-" + name, "default")
                        .body("Targets[0].EcsParameters.NetworkConfiguration.awsvpcConfiguration.Subnets",
                                contains("subnet-a", "subnet-b"));
            }
            listTargets("cfn-rule-targets-list-item", "default")
                    .body("Targets[0].EcsParameters.NetworkConfiguration.awsvpcConfiguration.Subnets",
                            contains("subnet-a,subnet-b"));
        } finally {
            deleteStack(stack);
        }

        String refused = "cfn-rule-targets-list-string";
        createListStack(refused, listRule("string", "{\"TaskDefinitionArn\":"
                + " \"arn:aws:ecs:us-east-1:000000000000:task-definition/job:1\", \"NetworkConfiguration\":"
                + " {\"AwsVpcConfiguration\": {\"Subnets\": {\"Ref\": \"Str\"}}}}"));
        try {
            assertEquals("ROLLBACK_COMPLETE", CfnStackWaits.awaitTerminal(refused).status());
            assertEquals("Property [Subnets] expected type: JSONArray, found: String",
                    CfnStackWaits.failedResourceReason(refused));
        } finally {
            deleteStack(refused);
        }
    }

    private static String listRule(String name, String ecsParameters) {
        return """
                "Rule%s": {"Type": "AWS::Events::Rule", "Properties": {
                  "Name": "cfn-rule-targets-list-%s", "EventPattern": {"source": ["cfn.rule.targets.list"]},
                  "Targets": [{"Id": "T", "Arn": "arn:aws:ecs:us-east-1:000000000000:cluster/jobs",
                    "RoleArn": "arn:aws:iam::000000000000:role/events-run-task", "EcsParameters": %s}]}}
                """.formatted(name.substring(0, 1).toUpperCase() + name.substring(1), name, ecsParameters);
    }

    private static void createListStack(String stack, String resources) {
        given().contentType("application/x-www-form-urlencoded")
                .formParam("Action", "CreateStack")
                .formParam("StackName", stack)
                .formParam("TemplateBody", """
                        {"Parameters": {"Cdl": {"Type": "CommaDelimitedList", "Default": "subnet-a,subnet-b"},
                                        "Str": {"Type": "String", "Default": "subnet-a,subnet-b"}},
                         "Conditions": {"Yes": {"Fn::Equals": ["a", "a"]}},
                         "Resources": {%s}}
                        """.formatted(resources))
                .when().post("/").then().statusCode(200);
    }

    private static void deleteStack(String stack) {
        given().contentType("application/x-www-form-urlencoded")
                .formParam("Action", "DeleteStack")
                .formParam("StackName", stack)
                .when().post("/").then().statusCode(200);
    }

    private static ValidatableResponse listTargets(String rule) {
        return listTargets(rule, BUS);
    }

    private static ValidatableResponse listTargets(String rule, String bus) {
        return given().contentType(EB_CT)
                .header("X-Amz-Target", "AWSEvents.ListTargetsByRule")
                .body("{\"Rule\":\"" + rule + "\",\"EventBusName\":\"" + bus + "\"}")
                .when().post("/").then().statusCode(200);
    }
}
