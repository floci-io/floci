package io.floci.aws.services.cloudformation;

import io.floci.aws.testing.CfnStackWaits;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.response.Response;
import io.restassured.specification.RequestSpecification;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.List;
import java.util.stream.Stream;

import static io.restassured.RestAssured.given;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.allOf;
import static org.hamcrest.Matchers.endsWith;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.startsWith;
import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * A template holding a null value, or a body that is no template, is refused when the operation is
 * requested, before anything is created. Measured on CloudFormation (eu-west-1, 2026-10-20):
 * ValidateTemplate, CreateStack, UpdateStack and CreateChangeSet answer 400 ValidationError "[path]
 * 'null' values are not allowed in templates", the path naming the first null in document order,
 * with Type where a resource's Properties would be.
 */
@QuarkusTest
class CloudFormationNullValuesIntegrationTest {

    private static final String AUTH =
            "AWS4-HMAC-SHA256 Credential=test/20261020/us-east-1/cloudformation/aws4_request";

    @ParameterizedTest(name = "{0}")
    @MethodSource("nullValues")
    void aNullValueIsRefusedWhereAwsNamesIt(String where, String template, String path) {
        String message = "[" + path + "] 'null' values are not allowed in templates";
        String name = "cfn-null-" + System.nanoTime();

        assertRefused(request("ValidateTemplate", null, template), message);
        assertRefused(request("CreateStack", name, template), message);
        given().contentType("application/x-www-form-urlencoded").header("Authorization", AUTH)
                .formParam("Action", "DescribeStacks").formParam("StackName", name)
                .when().post("/").then().statusCode(400);
    }

    static Stream<Arguments> nullValues() {
        return Stream.of(
                Arguments.of("a property", queue("{\"QueueName\": null}"), "/Resources/Q/Type/QueueName"),
                Arguments.of("a nested member", queue("{\"RedrivePolicy\": {\"deadLetterTargetArn\": null, \"maxReceiveCount\": 3}}"),
                        "/Resources/Q/Type/RedrivePolicy/deadLetterTargetArn"),
                Arguments.of("a list item's member", queue("{\"Tags\": [{\"Key\": \"k\", \"Value\": null}]}"),
                        "/Resources/Q/Type/Tags/0/Value"),
                Arguments.of("a list entry", "{\"Resources\": {\"T\": {\"Type\": \"AWS::SNS::Topic\", \"Properties\": {\"Subscription\": [null]}}}}",
                        "/Resources/T/Type/Subscription/0"),
                Arguments.of("Properties", "{\"Resources\": {\"Q\": {\"Type\": \"AWS::SQS::Queue\", \"Properties\": null}}}",
                        "/Resources/Q/Properties"),
                Arguments.of("Type", "{\"Resources\": {\"Q\": {\"Type\": null}}}", "/Resources/Q/Type"),
                Arguments.of("Resources", "{\"Resources\": null}", "/Resources"),
                Arguments.of("an intrinsic's argument", queue("{\"QueueName\": {\"Fn::Join\": [\"-\", [\"a\", null]]}}"),
                        "/Resources/Q/Type/QueueName/Fn::Join/1/1"),
                Arguments.of("an output's value", "{\"Resources\": {\"H\": {\"Type\": \"AWS::CloudFormation::WaitConditionHandle\"}},"
                        + " \"Outputs\": {\"O\": {\"Value\": null}}}", "/Outputs/O/Value"),
                Arguments.of("a parameter's default", "{\"Parameters\": {\"P\": {\"Type\": \"String\", \"Default\": null}},"
                        + " \"Resources\": {\"H\": {\"Type\": \"AWS::CloudFormation::WaitConditionHandle\"}}}", "/Parameters/P/Default"),
                Arguments.of("a resource's metadata", "{\"Resources\": {\"H\": {\"Type\": \"AWS::CloudFormation::WaitConditionHandle\","
                        + " \"Metadata\": {\"m\": null}}}}", "/Resources/H/Metadata/m"),
                Arguments.of("two nulls", queue("{\"QueueName\": null, \"DelaySeconds\": null}"), "/Resources/Q/Type/QueueName"),
                Arguments.of("YAML, empty and ~", "Resources:\n  Q:\n    Type: AWS::SQS::Queue\n    Properties:\n"
                        + "      QueueName:\n      DelaySeconds: ~\n", "/Resources/Q/Type/QueueName"));
    }

    /** Measured for a null value (2026-10-20) and for a body that is no template (2026-10-21). */
    @Test
    void anUpdateAndAChangeSetAreRefusedToo() {
        String name = "cfn-null-update-" + System.nanoTime();
        given().contentType("application/x-www-form-urlencoded").header("Authorization", AUTH)
                .formParam("Action", "CreateStack").formParam("StackName", name)
                .formParam("TemplateBody", "{\"Resources\": {\"H\": {\"Type\": \"AWS::CloudFormation::WaitConditionHandle\"}}}")
                .when().post("/").then().statusCode(200);
        assertEquals("CREATE_COMPLETE", CfnStackWaits.awaitTerminal(name).status());
        String message = "[/Resources/Q/Type/QueueName] 'null' values are not allowed in templates";
        String template = queue("{\"QueueName\": null}");

        assertRefused(request("UpdateStack", name, template), message);
        assertRefused(given().contentType("application/x-www-form-urlencoded").header("Authorization", AUTH)
                .formParam("Action", "CreateChangeSet").formParam("StackName", name)
                .formParam("ChangeSetName", "with-null").formParam("TemplateBody", template)
                .when().post("/"), message);
        assertRefused(request("UpdateStack", name, "[1, 2]"), "Template format error: unsupported structure.");
        assertRefused(given().contentType("application/x-www-form-urlencoded").header("Authorization", AUTH)
                .formParam("Action", "CreateChangeSet").formParam("StackName", name)
                .formParam("ChangeSetName", "not-a-template").formParam("TemplateBody", "Resources:\n  A: [unclosed\n")
                .when().post("/"), "Template format error: YAML not well-formed. (line 3, column 1)");

        given().contentType("application/x-www-form-urlencoded").header("Authorization", AUTH)
                .formParam("Action", "DeleteStack").formParam("StackName", name)
                .when().post("/").then().statusCode(200);
        CfnStackWaits.awaitStackDeleted(name);
    }

    /** Measured: a template that is null as a whole, or holds only a comment, is refused for lacking its resources. */
    @ParameterizedTest
    @ValueSource(strings = {"null", "~", "---\n~\n", "---\n", "# only a comment"})
    void aTemplateThatIsNullAsAWholeLacksItsResources(String template) {
        String message = "Template format error: At least one Resources member must be defined.";

        assertRefused(request("ValidateTemplate", null, template), message);
        assertRefused(request("CreateStack", "cfn-null-whole-" + System.nanoTime(), template), message);
    }

    /**
     * Measured (eu-west-1, 2026-10-21): a body that reads as neither JSON nor YAML, or as a scalar or
     * a list, is no template, alike for the three calls that read one, and no stack is created.
     */
    @ParameterizedTest(name = "{0}")
    @MethodSource("notTemplates")
    void aBodyThatIsNoTemplateIsRefused(String body, String message) {
        String name = "cfn-format-" + System.nanoTime();

        for (String action : List.of("ValidateTemplate", "CreateStack", "GetTemplateSummary")) {
            assertRefused(request(action, "CreateStack".equals(action) ? name : null, body),
                    "Template format error: " + message);
        }
        given().contentType("application/x-www-form-urlencoded").header("Authorization", AUTH)
                .formParam("Action", "DescribeStacks").formParam("StackName", name)
                .when().post("/").then().statusCode(400);
    }

    static Stream<Arguments> notTemplates() {
        return Stream.of(
                Arguments.of("{\"Resources\": {\"A\": {\"Type\": \"AWS::SNS::Topic\"}}",
                        "JSON not well-formed. (line 1, column 49)"),
                Arguments.of("Resources:\n  A: [unclosed\n", "YAML not well-formed. (line 3, column 1)"),
                Arguments.of("Resources:\n  A:\n    Type: AWS::SNS::Topic\n    Properties: !Bogus x\n",
                        "YAML not well-formed. (line 4, column 17)"),
                Arguments.of("just a string", "unsupported structure."),
                Arguments.of("[1, 2]", "unsupported structure."));
    }

    /** Measured: CloudFormation reads a body as YAML, so JSON with a trailing comma is still a template. */
    @Test
    void aBodyThatIsNotStrictJsonStillReadsAsYaml() {
        request("ValidateTemplate", null,
                "{\"Resources\": {\"H\": {\"Type\": \"AWS::CloudFormation::WaitConditionHandle\",}}}")
                .then().statusCode(200);
    }

    /**
     * Measured: CreateStackSet and UpdateStackSet refuse the template before the stack set keeps it,
     * passing CloudFormation's message on with the service exception's details, for a null value
     * (2026-10-20) and for a body that is no template (2026-10-21).
     */
    @Test
    void aStackSetRefusesATemplateWithANullValueBeforeKeepingIt() {
        String name = "cfn-null-set-" + System.nanoTime();
        String good = "{\"Resources\": {\"H\": {\"Type\": \"AWS::CloudFormation::WaitConditionHandle\"}}}";
        String prefix = "[/Resources/Q/Type/QueueName] 'null' values are not allowed in templates (Service:"
                + " AmazonCloudFormation; Status Code: 400; Error Code: ValidationError; Request ID: ";

        Response create = stackSet("CreateStackSet", name + "-refused", queue("{\"QueueName\": null}"));
        create.then().statusCode(400);
        assertThat(create.xmlPath().getString("ErrorResponse.Error.Message"),
                allOf(startsWith(prefix), endsWith("; Proxy: null)")));
        stackSet("CreateStackSet", name, good).then().statusCode(200);
        Response update = stackSet("UpdateStackSet", name, queue("{\"QueueName\": null}"));
        update.then().statusCode(400);
        assertThat(update.xmlPath().getString("ErrorResponse.Error.Message"), startsWith(prefix));
        String details = " (Service: AmazonCloudFormation; Status Code: 400; Error Code: ValidationError; Request ID: ";
        Response notATemplate = stackSet("CreateStackSet", name + "-list", "[1, 2]");
        notATemplate.then().statusCode(400);
        assertThat(notATemplate.xmlPath().getString("ErrorResponse.Error.Message"),
                startsWith("Template format error: unsupported structure." + details));
        Response unparsable = stackSet("UpdateStackSet", name, "{\"Resources\": {\"A\": {\"Type\": \"AWS::SNS::Topic\"}}");
        unparsable.then().statusCode(400);
        assertThat(unparsable.xmlPath().getString("ErrorResponse.Error.Message"),
                startsWith("Template format error: JSON not well-formed. (line 1, column 49)" + details));

        given().contentType("application/x-www-form-urlencoded").header("Authorization", AUTH)
                .formParam("Action", "DescribeStackSet").formParam("StackSetName", name)
                .when().post("/").then().statusCode(200)
                .body("DescribeStackSetResponse.DescribeStackSetResult.StackSet.TemplateBody", equalTo(good));
        given().contentType("application/x-www-form-urlencoded").header("Authorization", AUTH)
                .formParam("Action", "DeleteStackSet").formParam("StackSetName", name)
                .when().post("/").then().statusCode(200);
    }

    /** Measured: a template URL whose object or bucket does not exist, alike for the three calls that read one. */
    @Test
    void aTemplateUrlOfNothingIsRefused() {
        String bucket = "cfn-null-tpl-" + System.nanoTime();
        given().header("Authorization", AUTH.replace("cloudformation", "s3")).when().put("/" + bucket)
                .then().statusCode(200);
        String missingKey = "S3 object does not exist. Bucket: " + bucket + ", Key: missing.json. Error: NoSuchKey";
        String missingBucket = "S3 error: The specified bucket does not exist\nFor more information check"
                + " http://docs.aws.amazon.com/AmazonS3/latest/API/ErrorResponses.html";

        for (String action : List.of("ValidateTemplate", "CreateStack", "GetTemplateSummary")) {
            assertRefused(templateUrl(action, "http://localhost:4566/" + bucket + "/missing.json"), missingKey);
            assertRefused(templateUrl(action, "http://localhost:4566/" + bucket + "-gone/t.json"), missingBucket);
        }
    }

    private static Response templateUrl(String action, String url) {
        RequestSpecification request = given().contentType("application/x-www-form-urlencoded")
                .header("Authorization", AUTH).formParam("Action", action).formParam("TemplateURL", url);
        if ("CreateStack".equals(action)) {
            request.formParam("StackName", "cfn-null-url-" + System.nanoTime());
        }
        return request.when().post("/");
    }

    private static Response stackSet(String action, String name, String template) {
        return given().contentType("application/x-www-form-urlencoded").header("Authorization", AUTH)
                .formParam("Action", action).formParam("StackSetName", name).formParam("TemplateBody", template)
                .when().post("/");
    }

    private static String queue(String properties) {
        return "{\"Resources\": {\"Q\": {\"Type\": \"AWS::SQS::Queue\", \"Properties\": " + properties + "}}}";
    }

    private static Response request(String action, String stackName, String template) {
        RequestSpecification request = given().contentType("application/x-www-form-urlencoded").header("Authorization", AUTH)
                .formParam("Action", action).formParam("TemplateBody", template);
        if (stackName != null) {
            request.formParam("StackName", stackName);
        }
        return request.when().post("/");
    }

    private static void assertRefused(Response response, String message) {
        response.then().statusCode(400);
        assertEquals("ValidationError", response.xmlPath().getString("ErrorResponse.Error.Code"));
        assertEquals(message, response.xmlPath().getString("ErrorResponse.Error.Message"));
    }
}
