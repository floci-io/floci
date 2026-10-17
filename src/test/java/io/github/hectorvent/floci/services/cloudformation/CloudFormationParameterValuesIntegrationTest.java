package io.github.hectorvent.floci.services.cloudformation;

import io.github.hectorvent.floci.testing.RestAssuredJsonUtils;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.response.Response;
import io.restassured.specification.RequestSpecification;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;
import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * A declared parameter that the request gives no value and the template no {@code Default}
 * fails CreateStack, UpdateStack and CreateChangeSet with {@code Parameters: [X] must have values},
 * before any stack state changes. {@code UsePreviousValue} supplies the deployed value on an update.
 */
@QuarkusTest
class CloudFormationParameterValuesIntegrationTest {

    private static final String BLANK_ENV_TEMPLATE = """
            {
              "Parameters": {"Env": {"Type": "String"}},
              "Conditions": {"IsBlank": {"Fn::Equals": [{"Ref": "Env"}, ""]}},
              "Resources": {"Queue": {"Type": "AWS::SQS::Queue", "Condition": "IsBlank"}}
            }
            """;

    @BeforeAll
    static void configureRestAssured() {
        RestAssuredJsonUtils.configureAwsContentTypes();
    }

    @Test
    void createStackRefusesAParameterWithNoValueAndNoDefault() {
        String stackName = "cfn-param-missing-" + Long.toString(System.nanoTime(), 36);

        request("CreateStack", stackName)
            .formParam("TemplateBody", BLANK_ENV_TEMPLATE)
        .when()
            .post("/")
        .then()
            .statusCode(400)
            .body("ErrorResponse.Error.Code", equalTo("ValidationError"))
            .body("ErrorResponse.Error.Message", equalTo("Parameters: [Env] must have values"));

        describeStacks(stackName).then().statusCode(400);
    }

    /** Every such parameter is named, in the order the template declares them. */
    @Test
    void everyParameterWithoutAValueIsNamed() {
        String stackName = "cfn-param-several-" + Long.toString(System.nanoTime(), 36);

        request("CreateStack", stackName)
            .formParam("TemplateBody", """
                    {
                      "Parameters": {
                        "Zeta": {"Type": "String"},
                        "Alpha": {"Type": "String", "Default": "a"},
                        "Beta": {"Type": "Number"},
                        "Gamma": {"Type": "String"}
                      },
                      "Resources": {"Queue": {"Type": "AWS::SQS::Queue"}}
                    }
                    """)
            .formParam("Parameters.member.1.ParameterKey", "Gamma")
            .formParam("Parameters.member.1.ParameterValue", "g")
        .when()
            .post("/")
        .then()
            .statusCode(400)
            .body("ErrorResponse.Error.Message", equalTo("Parameters: [Zeta, Beta] must have values"));

        describeStacks(stackName).then().statusCode(400);
    }

    /**
     * The values are checked before the template's dependency checks, which evaluate conditions
     * that read them, so a template with both a cycle and a missing value gets the parameter error.
     */
    @Test
    void aMissingValueIsReportedBeforeADependencyCycle() {
        String stackName = "cfn-param-cycle-" + Long.toString(System.nanoTime(), 36);

        request("CreateStack", stackName)
            .formParam("TemplateBody", """
                    {
                      "Parameters": {"Env": {"Type": "String"}},
                      "Resources": {
                        "A": {"Type": "AWS::SQS::Queue", "DependsOn": "B"},
                        "B": {"Type": "AWS::SQS::Queue", "DependsOn": "A"}
                      }
                    }
                    """)
        .when()
            .post("/")
        .then()
            .statusCode(400)
            .body("ErrorResponse.Error.Message", equalTo("Parameters: [Env] must have values"));

        describeStacks(stackName).then().statusCode(400);
    }

    /** An empty value is a value, and a Default stands in for an omitted one. */
    @Test
    void anEmptyValueAndADefaultAreValues() {
        String stackName = "cfn-param-empty-" + Long.toString(System.nanoTime(), 36);
        String queueName = "param-empty-" + Long.toString(System.nanoTime(), 36);

        request("CreateStack", stackName)
            .formParam("TemplateBody", """
                    {
                      "Parameters": {
                        "Env": {"Type": "String"},
                        "Timeout": {"Type": "Number", "Default": "45"}
                      },
                      "Conditions": {"IsBlank": {"Fn::Equals": [{"Ref": "Env"}, ""]}},
                      "Resources": {"Queue": {"Type": "AWS::SQS::Queue", "Condition": "IsBlank",
                        "Properties": {"QueueName": "%s", "VisibilityTimeout": {"Ref": "Timeout"}}}}
                    }
                    """.formatted(queueName))
            .formParam("Parameters.member.1.ParameterKey", "Env")
            .formParam("Parameters.member.1.ParameterValue", "")
        .when()
            .post("/")
        .then()
            .statusCode(200);
        try {
            assertEquals("CREATE_COMPLETE", CfnStackWaits.awaitTerminal(stackName).status());
            getQueueUrl(queueName).then().statusCode(200);
        } finally {
            deleteStack(stackName);
        }
    }

    /** A CREATE change set is refused too, and leaves no REVIEW_IN_PROGRESS stack behind. */
    @Test
    void createChangeSetRefusesAParameterWithNoValue() {
        String stackName = "cfn-param-changeset-" + Long.toString(System.nanoTime(), 36);

        request("CreateChangeSet", stackName)
            .formParam("ChangeSetName", "deploy")
            .formParam("ChangeSetType", "CREATE")
            .formParam("TemplateBody", BLANK_ENV_TEMPLATE)
        .when()
            .post("/")
        .then()
            .statusCode(400)
            .body("ErrorResponse.Error.Code", equalTo("ValidationError"))
            .body("ErrorResponse.Error.Message", equalTo("Parameters: [Env] must have values"));

        describeStacks(stackName).then().statusCode(400);
    }

    /**
     * The check runs before the SAM transform, so a SAM template is refused with the same 400
     * rather than given a change set that fails later.
     */
    @Test
    void samTemplateWithoutAValueIsRefusedBeforeTheTransform() {
        String stackName = "cfn-param-sam-" + Long.toString(System.nanoTime(), 36);

        request("CreateChangeSet", stackName)
            .formParam("ChangeSetName", "deploy")
            .formParam("ChangeSetType", "CREATE")
            .formParam("TemplateBody", """
                    {
                      "Transform": "AWS::Serverless-2016-10-31",
                      "Parameters": {"Env": {"Type": "String"}},
                      "Resources": {"Queue": {"Type": "AWS::SQS::Queue",
                        "Properties": {"QueueName": {"Fn::Sub": "sam-${Env}"}}}}
                    }
                    """)
        .when()
            .post("/")
        .then()
            .statusCode(400)
            .body("ErrorResponse.Error.Message", equalTo("Parameters: [Env] must have values"));

        describeStacks(stackName).then().statusCode(400);
    }

    /**
     * An update does not carry a parameter over: one it omits is refused whether the update sends
     * a template or reuses the previous one, and the stack keeps its status and value.
     * {@code UsePreviousValue} keeps the deployed value, on UpdateStack and on an UPDATE change set.
     */
    @Test
    void anUpdateNeedsAValueOrUsePreviousValue() {
        String suffix = Long.toString(System.nanoTime(), 36);
        String stackName = "cfn-param-update-" + suffix;
        String queueName = "param-update-" + suffix;
        String template = """
                {
                  "Parameters": {"Env": {"Type": "String"}},
                  "Resources": {"Queue": {"Type": "AWS::SQS::Queue",
                    "Properties": {"QueueName": {"Fn::Sub": "%s-${Env}"}, "VisibilityTimeout": %d}}}
                }
                """;
        request("CreateStack", stackName)
            .formParam("TemplateBody", template.formatted(queueName, 30))
            .formParam("Parameters.member.1.ParameterKey", "Env")
            .formParam("Parameters.member.1.ParameterValue", "dev")
        .when()
            .post("/")
        .then()
            .statusCode(200);
        try {
            assertEquals("CREATE_COMPLETE", CfnStackWaits.awaitTerminal(stackName).status());

            request("UpdateStack", stackName)
                .formParam("TemplateBody", template.formatted(queueName, 60))
            .when()
                .post("/")
            .then()
                .statusCode(400)
                .body("ErrorResponse.Error.Message", equalTo("Parameters: [Env] must have values"));
            request("UpdateStack", stackName)
                .formParam("UsePreviousTemplate", "true")
            .when()
                .post("/")
            .then()
                .statusCode(400)
                .body("ErrorResponse.Error.Message", equalTo("Parameters: [Env] must have values"));
            request("CreateChangeSet", stackName)
                .formParam("ChangeSetName", "omits-env")
                .formParam("ChangeSetType", "UPDATE")
                .formParam("TemplateBody", template.formatted(queueName, 60))
            .when()
                .post("/")
            .then()
                .statusCode(400)
                .body("ErrorResponse.Error.Message", equalTo("Parameters: [Env] must have values"));
            describeStacks(stackName).then()
                .statusCode(200)
                .body(containsString("<StackStatus>CREATE_COMPLETE</StackStatus>"))
                .body(containsString("<ParameterValue>dev</ParameterValue>"));

            request("CreateChangeSet", stackName)
                .formParam("ChangeSetName", "keeps-env")
                .formParam("ChangeSetType", "UPDATE")
                .formParam("TemplateBody", template.formatted(queueName, 45))
                .formParam("Parameters.member.1.ParameterKey", "Env")
                .formParam("Parameters.member.1.UsePreviousValue", "true")
            .when()
                .post("/")
            .then()
                .statusCode(200);

            request("UpdateStack", stackName)
                .formParam("TemplateBody", template.formatted(queueName, 60))
                .formParam("Parameters.member.1.ParameterKey", "Env")
                .formParam("Parameters.member.1.UsePreviousValue", "true")
            .when()
                .post("/")
            .then()
                .statusCode(200);
            assertEquals("UPDATE_COMPLETE", CfnStackWaits.awaitTerminal(stackName).status());
            assertEquals("60", visibilityTimeout(queueName + "-dev"));
            describeStacks(stackName).then()
                .statusCode(200)
                .body(containsString("<ParameterValue>dev</ParameterValue>"));
        } finally {
            deleteStack(stackName);
        }
    }

    /**
     * {@code UsePreviousValue} keeps a deployed value; on a parameter that has none, on a create or
     * for a parameter the update adds, it passes nothing, so the template's Default applies, or the
     * request is refused.
     */
    @Test
    void usePreviousValueWithNothingDeployedPassesNoValue() {
        String suffix = Long.toString(System.nanoTime(), 36);
        String stackName = "cfn-param-previous-" + suffix;
        String queueName = "param-previous-" + suffix;

        request("CreateStack", stackName)
            .formParam("TemplateBody", BLANK_ENV_TEMPLATE)
            .formParam("Parameters.member.1.ParameterKey", "Env")
            .formParam("Parameters.member.1.UsePreviousValue", "true")
        .when()
            .post("/")
        .then()
            .statusCode(400)
            .body("ErrorResponse.Error.Message", equalTo("Parameters: [Env] must have values"));

        request("CreateStack", stackName)
            .formParam("TemplateBody", """
                    {"Resources": {"Queue": {"Type": "AWS::SQS::Queue", "Properties": {"QueueName": "%s"}}}}
                    """.formatted(queueName))
        .when()
            .post("/")
        .then()
            .statusCode(200);
        try {
            assertEquals("CREATE_COMPLETE", CfnStackWaits.awaitTerminal(stackName).status());
            String added = """
                    {
                      "Parameters": {"Added": {"Type": "String"%s}},
                      "Resources": {"Queue": {"Type": "AWS::SQS::Queue",
                        "Properties": {"QueueName": "%s", "Tags": [{"Key": "added", "Value": {"Ref": "Added"}}]}}}
                    }
                    """;
            request("UpdateStack", stackName)
                .formParam("TemplateBody", added.formatted("", queueName))
                .formParam("Parameters.member.1.ParameterKey", "Added")
                .formParam("Parameters.member.1.UsePreviousValue", "true")
            .when()
                .post("/")
            .then()
                .statusCode(400)
                .body("ErrorResponse.Error.Message", equalTo("Parameters: [Added] must have values"));

            request("UpdateStack", stackName)
                .formParam("TemplateBody", added.formatted(", \"Default\": \"fallback\"", queueName))
                .formParam("Parameters.member.1.ParameterKey", "Added")
                .formParam("Parameters.member.1.UsePreviousValue", "true")
            .when()
                .post("/")
            .then()
                .statusCode(200);
            assertEquals("UPDATE_COMPLETE", CfnStackWaits.awaitTerminal(stackName).status());
            describeStacks(stackName).then()
                .statusCode(200)
                .body(containsString("<ParameterValue>fallback</ParameterValue>"));
        } finally {
            deleteStack(stackName);
        }
    }

    /**
     * A request its stack refuses outright gets that refusal, whatever its parameters: an update of
     * a stack that does not exist, whose UsePreviousValue has nothing to keep, and a create over a
     * name in use.
     */
    @Test
    void aRequestItsStackRefusesGetsThatRefusal() {
        String suffix = Long.toString(System.nanoTime(), 36);
        String missing = "cfn-param-missing-stack-" + suffix;
        request("UpdateStack", missing)
            .formParam("TemplateBody", BLANK_ENV_TEMPLATE)
            .formParam("Parameters.member.1.ParameterKey", "Env")
            .formParam("Parameters.member.1.UsePreviousValue", "true")
        .when()
            .post("/")
        .then()
            .statusCode(400)
            .body("ErrorResponse.Error.Message", equalTo("Stack with id " + missing + " does not exist"));

        String taken = "cfn-param-taken-" + suffix;
        request("CreateStack", taken)
            .formParam("TemplateBody", BLANK_ENV_TEMPLATE)
            .formParam("Parameters.member.1.ParameterKey", "Env")
            .formParam("Parameters.member.1.ParameterValue", "")
        .when()
            .post("/")
        .then()
            .statusCode(200);
        try {
            assertEquals("CREATE_COMPLETE", CfnStackWaits.awaitTerminal(taken).status());
            request("CreateStack", taken)
                .formParam("TemplateBody", BLANK_ENV_TEMPLATE)
            .when()
                .post("/")
            .then()
                .statusCode(400)
                .body("ErrorResponse.Error.Code", equalTo("AlreadyExistsException"));
        } finally {
            deleteStack(taken);
        }
    }

    /**
     * A REVIEW_IN_PROGRESS placeholder refuses an update, so an update without a value gets that
     * refusal; a CREATE change set may attach to it, so one without a value gets the parameter error.
     */
    @Test
    void aPlaceholderStackRefusesTheUpdateButNotTheAttachingCreate() {
        String stackName = "cfn-param-placeholder-" + Long.toString(System.nanoTime(), 36);
        request("CreateChangeSet", stackName)
            .formParam("ChangeSetName", "first")
            .formParam("ChangeSetType", "CREATE")
            .formParam("TemplateBody", BLANK_ENV_TEMPLATE)
            .formParam("Parameters.member.1.ParameterKey", "Env")
            .formParam("Parameters.member.1.ParameterValue", "")
        .when()
            .post("/")
        .then()
            .statusCode(200);
        try {
            request("UpdateStack", stackName)
                .formParam("TemplateBody", BLANK_ENV_TEMPLATE)
            .when()
                .post("/")
            .then()
                .statusCode(400)
                .body("ErrorResponse.Error.Message",
                        containsString("is in REVIEW_IN_PROGRESS state and can not be updated."));

            request("CreateChangeSet", stackName)
                .formParam("ChangeSetName", "second")
                .formParam("ChangeSetType", "CREATE")
                .formParam("TemplateBody", BLANK_ENV_TEMPLATE)
            .when()
                .post("/")
            .then()
                .statusCode(400)
                .body("ErrorResponse.Error.Message", equalTo("Parameters: [Env] must have values"));
        } finally {
            deleteStack(stackName);
        }
    }

    /**
     * A nested stack is created with the values its resource passes, so a child parameter left
     * without one fails that resource, as the child's CreateStack would, and the parent rolls
     * back. The child stack is never created.
     */
    @Test
    void aNestedStackWithoutAValueFailsItsResource() {
        String suffix = Long.toString(System.nanoTime(), 36);
        String bucket = "cfn-param-nested-" + suffix;
        String stackName = "cfn-param-nested-" + suffix;
        String templateUrl = putChildTemplate(bucket);
        try {
            request("CreateStack", stackName)
                .formParam("TemplateBody", parentTemplate(templateUrl, "{}"))
            .when()
                .post("/")
            .then()
                .statusCode(200);
            CfnStackWaits.StackState state = CfnStackWaits.awaitStatus(stackName, "ROLLBACK_COMPLETE");
            assertEquals("Parameters: [Env] must have values", state.reason());
            request("DescribeStackEvents", stackName)
            .when()
                .post("/")
            .then()
                .statusCode(200)
                .body(containsString("<ResourceStatusReason>Parameters: [Env] must have values</ResourceStatusReason>"));
            describeStacks(stackName + "-Child").then().statusCode(400);
        } finally {
            deleteStack(stackName);
            CfnStackWaits.awaitStackDeleted(stackName);
            deleteChildTemplate(bucket);
        }
    }

    /**
     * A nested stack update that leaves a child parameter without a value fails the resource
     * before the child changes, and the parent rolls back with the child as it was.
     */
    @Test
    void aNestedStackUpdateWithoutAValueLeavesTheChild() {
        String suffix = Long.toString(System.nanoTime(), 36);
        String bucket = "cfn-param-nested-update-" + suffix;
        String stackName = "cfn-param-nested-update-" + suffix;
        String templateUrl = putChildTemplate(bucket);
        try {
            request("CreateStack", stackName)
                .formParam("TemplateBody", parentTemplate(templateUrl, "{\"Env\": \"dev\"}"))
            .when()
                .post("/")
            .then()
                .statusCode(200);
            assertEquals("CREATE_COMPLETE", CfnStackWaits.awaitTerminal(stackName).status());

            request("UpdateStack", stackName)
                .formParam("TemplateBody", parentTemplate(templateUrl, "{\"Size\": \"2\"}"))
            .when()
                .post("/")
            .then()
                .statusCode(200);
            CfnStackWaits.awaitStatus(stackName, "UPDATE_ROLLBACK_COMPLETE");
            request("DescribeStackEvents", stackName)
            .when()
                .post("/")
            .then()
                .statusCode(200)
                .body(containsString("<ResourceStatusReason>Parameters: [Env] must have values</ResourceStatusReason>"));
            describeStacks(stackName + "-Child").then()
                .statusCode(200)
                .body(containsString("<StackStatus>CREATE_COMPLETE</StackStatus>"))
                .body(containsString("<ParameterValue>dev</ParameterValue>"));
        } finally {
            deleteStack(stackName);
            CfnStackWaits.awaitStackDeleted(stackName);
            deleteChildTemplate(bucket);
        }
    }

    /**
     * The resource's Parameters resolve as a property value: an Fn::If choosing the whole map
     * passes the chosen one, and a value that is AWS::NoValue passes nothing, so the child's
     * Default applies.
     */
    @Test
    void aNestedStacksParametersResolveAsAPropertyValue() {
        String suffix = Long.toString(System.nanoTime(), 36);
        String bucket = "cfn-param-nested-if-" + suffix;
        String stackName = "cfn-param-nested-if-" + suffix;
        String templateUrl = putChildTemplate(bucket);
        try {
            request("CreateStack", stackName)
                .formParam("TemplateBody", """
                        {
                          "Conditions": {"Always": {"Fn::Equals": ["a", "a"]}},
                          "Resources": {
                            "Child": {
                              "Type": "AWS::CloudFormation::Stack",
                              "Properties": {"TemplateURL": "%s", "Parameters": {"Fn::If": ["Always",
                                {"Env": "dev", "Size": {"Ref": "AWS::NoValue"}}, {"Env": "prod"}]}}
                            }
                          }
                        }
                        """.formatted(templateUrl))
            .when()
                .post("/")
            .then()
                .statusCode(200);
            assertEquals("CREATE_COMPLETE", CfnStackWaits.awaitTerminal(stackName).status());
            describeStacks(stackName + "-Child").then()
                .statusCode(200)
                .body("**.find { it.ParameterKey == 'Env' }.ParameterValue", equalTo("dev"))
                .body("**.find { it.ParameterKey == 'Size' }.ParameterValue", equalTo("1"));
        } finally {
            deleteStack(stackName);
            CfnStackWaits.awaitStackDeleted(stackName);
            deleteChildTemplate(bucket);
        }
    }

    /**
     * A parameter definition an AWS::Include supplies is only known once execution merges it, so it
     * is not checked up front: a Default the snippet carries still applies. The section's other
     * parameters are checked as usual.
     */
    @Test
    void aParameterAnIncludeDefinesIsNotCheckedUpFront() {
        String suffix = Long.toString(System.nanoTime(), 36);
        String bucket = "cfn-param-include-" + suffix;
        String stackName = "cfn-param-include-" + suffix;
        String queueName = "param-include-" + suffix;
        given().when().put("/" + bucket).then().statusCode(200);
        given().body("{\"Type\": \"String\", \"Default\": \"dev\"}")
            .when().put("/" + bucket + "/env.json").then().statusCode(200);
        try {
            request("CreateStack", stackName)
                .formParam("TemplateBody", """
                        {
                          "Parameters": {"Env": {"Fn::Transform": {"Name": "AWS::Include",
                            "Parameters": {"Location": "s3://%s/env.json"}}}},
                          "Resources": {"Queue": {"Type": "AWS::SQS::Queue",
                            "Properties": {"QueueName": {"Fn::Sub": "%s-${Env}"}}}}
                        }
                        """.formatted(bucket, queueName))
            .when()
                .post("/")
            .then()
                .statusCode(200);
            assertEquals("CREATE_COMPLETE", CfnStackWaits.awaitTerminal(stackName).status());
            getQueueUrl(queueName + "-dev").then().statusCode(200);

            request("CreateStack", stackName + "-other")
                .formParam("TemplateBody", """
                        {
                          "Parameters": {"Other": {"Type": "String"}, "Env": {"Fn::Transform": {"Name": "AWS::Include",
                            "Parameters": {"Location": "s3://%s/env.json"}}}},
                          "Resources": {"Queue": {"Type": "AWS::SQS::Queue"}}
                        }
                        """.formatted(bucket))
            .when()
                .post("/")
            .then()
                .statusCode(400)
                .body("ErrorResponse.Error.Message", equalTo("Parameters: [Other] must have values"));
        } finally {
            deleteStack(stackName);
            CfnStackWaits.awaitStackDeleted(stackName);
            given().when().delete("/" + bucket + "/env.json");
            given().when().delete("/" + bucket);
        }
    }

    /** Puts a child template declaring Env (no Default) and Size (Default 1) in a new bucket. */
    private static String putChildTemplate(String bucket) {
        given().when().put("/" + bucket).then().statusCode(200);
        given().contentType("application/json")
            .body("""
                    {
                      "Parameters": {"Env": {"Type": "String"}, "Size": {"Type": "Number", "Default": "1"}},
                      "Resources": {"Queue": {"Type": "AWS::SQS::Queue"}}
                    }
                    """)
        .when()
            .put("/" + bucket + "/child.json")
        .then()
            .statusCode(200);
        return "http://localhost/" + bucket + "/child.json";
    }

    private static void deleteChildTemplate(String bucket) {
        given().when().delete("/" + bucket + "/child.json");
        given().when().delete("/" + bucket);
    }

    private static String parentTemplate(String templateUrl, String parameters) {
        return """
                {
                  "Resources": {
                    "Child": {
                      "Type": "AWS::CloudFormation::Stack",
                      "Properties": {"TemplateURL": "%s", "Parameters": %s}
                    }
                  }
                }
                """.formatted(templateUrl, parameters);
    }

    private static RequestSpecification request(String action, String stackName) {
        return given()
            .contentType("application/x-www-form-urlencoded")
            .formParam("Action", action)
            .formParam("StackName", stackName);
    }

    private static Response describeStacks(String stackName) {
        return request("DescribeStacks", stackName).when().post("/");
    }

    private static void deleteStack(String stackName) {
        request("DeleteStack", stackName).when().post("/").then().statusCode(200);
    }

    private static Response getQueueUrl(String queueName) {
        return given()
            .contentType("application/x-www-form-urlencoded")
            .formParam("Action", "GetQueueUrl")
            .formParam("QueueName", queueName)
        .when()
            .post("/");
    }

    private static String visibilityTimeout(String queueName) {
        String queueUrl = getQueueUrl(queueName).then().statusCode(200).extract().xmlPath()
                .getString("GetQueueUrlResponse.GetQueueUrlResult.QueueUrl");
        return given()
            .contentType("application/x-www-form-urlencoded")
            .formParam("Action", "GetQueueAttributes")
            .formParam("QueueUrl", queueUrl)
            .formParam("AttributeName.1", "VisibilityTimeout")
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .extract().xmlPath()
            .getString("**.find { it.Name == 'VisibilityTimeout' }.Value");
    }
}
