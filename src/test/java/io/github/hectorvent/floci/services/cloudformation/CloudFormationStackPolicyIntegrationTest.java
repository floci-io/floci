package io.github.hectorvent.floci.services.cloudformation;

import io.github.hectorvent.floci.testing.CfnRequests;
import io.github.hectorvent.floci.testing.CfnStackWaits;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.response.Response;
import io.restassured.specification.RequestSpecification;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

/**
 * SetStackPolicy, GetStackPolicy, and the stack policy CreateStack and UpdateStack carry, as
 * measured on CloudFormation (eu-west-1). Floci stores the policy and checks its structure; it
 * does not enforce it during updates.
 */
@QuarkusTest
class CloudFormationStackPolicyIntegrationTest {

    private static final String CFN_AUTH =
            "AWS4-HMAC-SHA256 Credential=test/20260205/us-east-1/cloudformation/aws4_request";
    private static final String TEMPLATE = "{\"Resources\":{\"P\":{\"Type\":\"AWS::SSM::Parameter\","
            + "\"Properties\":{\"Type\":\"String\",\"Value\":\"v\"}}}}";
    private static final String UPDATED_TEMPLATE = TEMPLATE.replace("\"v\"", "\"v2\"");
    // SSM refuses a comma in a tag key, so this template fails to create and fails to update.
    private static final String FAILING_TEMPLATE = TEMPLATE.replace("\"Value\":\"v\"",
            "\"Value\":\"v3\",\"Tags\":{\"a,b\":\"x\"}");
    private static final String POLICY = "{\n  \"Statement\" : [ {\"Effect\":\"Deny\",\"Action\":\"Update:*\","
            + "\"Principal\":\"*\",\"Resource\":\"*\"} ]\n}";
    private static final String OTHER_POLICY = "{\"Statement\":{\"Effect\":\"allow\",\"NotAction\":\"Update:Delete\","
            + "\"Principal\":\"*\",\"Resource\":\"*\"}}";
    private static final String INVALID = "Error validating stack policy: Invalid stack policy";

    @Test
    void setStoresThePolicyVerbatimAndGetReturnsItByNameAndByArn() {
        String stackName = createdStack();
        String stackId = stackId(stackName);
        try {
            policyOf(stackName).then().statusCode(200)
                    .body(containsString("<GetStackPolicyResult"))
                    .body(not(containsString("StackPolicyBody")));

            cfn("SetStackPolicy", stackName, "StackPolicyBody", POLICY).then().statusCode(200)
                    .body(containsString("<SetStackPolicyResponse"))
                    .body(not(containsString("SetStackPolicyResult")));
            assertEquals(POLICY, storedPolicy(stackName));
            assertEquals(POLICY, storedPolicy(stackId));

            cfn("SetStackPolicy", stackId, "StackPolicyBody", OTHER_POLICY).then().statusCode(200);
            assertEquals(OTHER_POLICY, storedPolicy(stackName));
        } finally {
            CfnStackWaits.deleteStack(stackName);
        }
    }

    @Test
    void setRequiresExactlyOnePolicySourceBeforeLookingTheStackUp() {
        cfn("SetStackPolicy", "never-created").then().statusCode(400)
                .body(containsString("<Code>ValidationError</Code>"))
                .body(containsString("<Message>Ether StackPolicyURL or StackPolicyBody must be specified.</Message>"));
        cfn("SetStackPolicy", "never-created", "StackPolicyBody", POLICY,
                "StackPolicyURL", "https://s3.amazonaws.com/b/k").then().statusCode(400)
                .body(containsString("<Message>You cannot specify both StackPolicyURL and StackPolicyBody</Message>"));
    }

    @Test
    void setAndGetOnAMissingStackReportIt() {
        cfn("SetStackPolicy", "never-created", "StackPolicyBody", "nope").then().statusCode(400)
                .body(containsString("<Code>ValidationError</Code>"))
                .body(containsString("<Message>Stack: never-created does not exist</Message>"));
        policyOf("never-created").then().statusCode(400)
                .body(containsString("<Message>Stack: never-created does not exist</Message>"));
    }

    /** Before the policy source, so a request without a name never reaches a stack named "null". */
    @Test
    void aMissingOrMalformedStackNameIsRefusedFirst() {
        String missing = "1 validation error detected: Value null at 'stackName' failed to satisfy constraint:"
                + " Member must not be null";
        assertEquals(missing, validationError(cfn("SetStackPolicy", null)));
        assertEquals(missing, validationError(policyOf(null)));
        assertEquals("2 validation errors detected: Value '' at 'stackName' failed to satisfy constraint:"
                        + " Member must have length greater than or equal to 1; Value '' at 'stackName' failed"
                        + " to satisfy constraint: Member must satisfy regular expression pattern:"
                        + " [a-zA-Z][-a-zA-Z0-9]*|arn:[-a-zA-Z0-9:/._+]*",
                validationError(policyOf("")));
    }

    @ParameterizedTest
    @ValueSource(strings = {
        "nope",
        "[]",
        "   ",
        "null",
        "{\"Foo\":1}",
        "{\"Version\":\"2012-10-17\"}",
        "{\"Statement\":null}",
        "{\"Statement\":[]}",
        "{\"Statement\":\"x\"}",
        "{\"Statement\":[{}]}",
        "{\"Statement\":[\"x\"]}",
        "{\"Statement\":[{\"Effect\":\"Maybe\"}]}",
        "{\"Statement\":[{\"Effect\":\"Allow\",\"Action\":\"Update:*\",\"NotAction\":\"Update:Delete\"}]}",
        "{\"Statement\":{\"Effect\":\"Allow\"}} junk"
    })
    void setRejectsAPolicyThatIsNotAPolicy(String policy) {
        String stackName = createdStack();
        try {
            cfn("SetStackPolicy", stackName, "StackPolicyBody", policy).then().statusCode(400)
                    .body(containsString("<Code>ValidationError</Code>"))
                    .body(containsString("<Message>" + INVALID + "</Message>"));
            policyOf(stackName).then().statusCode(200).body(not(containsString("StackPolicyBody")));
        } finally {
            CfnStackWaits.deleteStack(stackName);
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {
        "{}",
        "{\"Statement\":{\"Effect\":\"allow\"}}",
        "{\"Statement\":[{\"Effect\":\"Deny\",\"Action\":[\"Update:Replace\",\"Update:Delete\"]}]}"
    })
    void setAcceptsAStructurallyValidPolicy(String policy) {
        String stackName = createdStack();
        try {
            cfn("SetStackPolicy", stackName, "StackPolicyBody", policy).then().statusCode(200);
            assertEquals(policy, storedPolicy(stackName));
        } finally {
            CfnStackWaits.deleteStack(stackName);
        }
    }

    @Test
    void setReadsAPolicyUrlFromS3() {
        String stackName = createdStack();
        String bucket = "cfn-stack-policy-" + Long.toString(System.nanoTime(), 36);
        try {
            given().when().put("/" + bucket).then().statusCode(200);
            given().contentType("application/json").body(POLICY).when().put("/" + bucket + "/policy.json")
                    .then().statusCode(200);
            cfn("SetStackPolicy", stackName,
                    "StackPolicyURL", "https://s3.us-east-1.amazonaws.com/" + bucket + "/policy.json")
                    .then().statusCode(200);
            assertEquals(POLICY, storedPolicy(stackName));
        } finally {
            CfnStackWaits.deleteStack(stackName);
            given().when().delete("/" + bucket + "/policy.json");
            given().when().delete("/" + bucket);
        }
    }

    @Test
    void setIsRefusedOnAStackThatFailedToCreate() {
        String stackName = uniqueName();
        cfn("CreateStack", stackName, "TemplateBody", FAILING_TEMPLATE).then().statusCode(200);
        CfnStackWaits.awaitStatus(stackName, "ROLLBACK_COMPLETE", CFN_AUTH);
        try {
            cfn("SetStackPolicy", stackName, "StackPolicyBody", "nope").then().statusCode(400)
                    .body(containsString("<Message>SetStackPolicy cannot be called when stack is in the"
                            + " ROLLBACK_COMPLETE state</Message>"));
            policyOf(stackName).then().statusCode(200).body(not(containsString("StackPolicyBody")));
        } finally {
            CfnStackWaits.deleteStack(stackName);
        }
    }

    @Test
    void aDeletedStackKeepsItsPolicyByArnAndRefusesANewOne() {
        String stackName = createdStack();
        String stackId = stackId(stackName);
        cfn("SetStackPolicy", stackName, "StackPolicyBody", POLICY).then().statusCode(200);
        CfnStackWaits.deleteStack(stackName);

        assertEquals(POLICY, storedPolicy(stackId));
        policyOf(stackName).then().statusCode(400)
                .body(containsString("<Message>Stack: " + stackName + " does not exist</Message>"));
        cfn("SetStackPolicy", stackId, "StackPolicyBody", OTHER_POLICY).then().statusCode(400)
                .body(containsString("<Message>SetStackPolicy cannot be called when stack is in the"
                        + " DELETE_COMPLETE state</Message>"));
    }

    @Test
    void createStackStoresItsPolicy() {
        String stackName = uniqueName();
        cfn("CreateStack", stackName, "TemplateBody", TEMPLATE, "StackPolicyBody", POLICY)
                .then().statusCode(200);
        try {
            assertEquals(POLICY, storedPolicy(stackName));
            CfnStackWaits.awaitStatus(stackName, "CREATE_COMPLETE", CFN_AUTH);
            assertEquals(POLICY, storedPolicy(stackName));
        } finally {
            CfnStackWaits.deleteStack(stackName);
        }
    }

    @Test
    void createStackRefusesAnInvalidPolicyWithoutCreatingTheStack() {
        String stackName = uniqueName();
        cfn("CreateStack", stackName, "TemplateBody", TEMPLATE, "StackPolicyBody", "{\"Statement\":[]}")
                .then().statusCode(400)
                .body(containsString("<Code>ValidationError</Code>"))
                .body(containsString("<Message>Error validating stack policy to be applied after stack creation:"
                        + " Invalid stack policy</Message>"));
        cfn("CreateStack", stackName, "TemplateBody", TEMPLATE, "StackPolicyBody", POLICY,
                "StackPolicyURL", "https://s3.amazonaws.com/b/k").then().statusCode(400)
                .body(containsString("<Message>You cannot specify both StackPolicyURL and StackPolicyBody</Message>"));
        cfn("DescribeStacks", stackName).then().statusCode(400);
    }

    @Test
    void updateStackAppliesItsPolicyOnceTheUpdateCompletes() {
        String stackName = createdStack();
        try {
            cfn("UpdateStack", stackName, "TemplateBody", UPDATED_TEMPLATE, "StackPolicyBody", POLICY)
                    .then().statusCode(200);
            CfnStackWaits.awaitStatus(stackName, "UPDATE_COMPLETE", CFN_AUTH);
            assertEquals(POLICY, storedPolicy(stackName));
        } finally {
            CfnStackWaits.deleteStack(stackName);
        }
    }

    @Test
    void aFailedUpdateKeepsThePreviousPolicy() {
        String stackName = createdStack();
        try {
            cfn("SetStackPolicy", stackName, "StackPolicyBody", POLICY).then().statusCode(200);
            cfn("UpdateStack", stackName, "TemplateBody", FAILING_TEMPLATE, "StackPolicyBody", OTHER_POLICY)
                    .then().statusCode(200);
            CfnStackWaits.awaitStatus(stackName, "UPDATE_ROLLBACK_COMPLETE", CFN_AUTH);
            assertEquals(POLICY, storedPolicy(stackName));
        } finally {
            CfnStackWaits.deleteStack(stackName);
        }
    }

    @Test
    void aNewPolicyAloneIsAnUpdateButTheSamePolicyIsNot() {
        String stackName = createdStack();
        try {
            int resourceEvents = resourceEvents(stackName);
            assertNotEquals(0, resourceEvents);
            cfn("UpdateStack", stackName, "UsePreviousTemplate", "true", "StackPolicyBody", POLICY)
                    .then().statusCode(200);
            CfnStackWaits.awaitStatus(stackName, "UPDATE_COMPLETE", CFN_AUTH);
            assertEquals(POLICY, storedPolicy(stackName));
            assertEquals(resourceEvents, resourceEvents(stackName));
            cfn("ListChangeSets", stackName).then().statusCode(200)
                    .body(not(containsString("<Status>FAILED</Status>")))
                    .body(containsString("<ExecutionStatus>EXECUTE_COMPLETE</ExecutionStatus>"));

            cfn("UpdateStack", stackName, "UsePreviousTemplate", "true", "StackPolicyBody", POLICY)
                    .then().statusCode(400)
                    .body(containsString("<Message>No updates are to be performed.</Message>"));
            cfn("UpdateStack", stackName, "UsePreviousTemplate", "true",
                    "StackPolicyDuringUpdateBody", OTHER_POLICY).then().statusCode(400)
                    .body(containsString("<Message>No updates are to be performed.</Message>"));
        } finally {
            CfnStackWaits.deleteStack(stackName);
        }
    }

    @Test
    void updateStackRefusesAnInvalidPolicy() {
        String stackName = createdStack();
        try {
            cfn("UpdateStack", stackName, "TemplateBody", UPDATED_TEMPLATE, "StackPolicyBody", "nope")
                    .then().statusCode(400)
                    .body(containsString("<Message>Error validating stack policy to be applied after stack update:"
                            + " Invalid stack policy</Message>"));
            CfnStackWaits.awaitStatus(stackName, "CREATE_COMPLETE", CFN_AUTH);
        } finally {
            CfnStackWaits.deleteStack(stackName);
        }
    }

    private static String uniqueName() {
        return "stack-policy-" + Long.toString(System.nanoTime(), 36);
    }

    private static String createdStack() {
        String stackName = uniqueName();
        cfn("CreateStack", stackName, "TemplateBody", TEMPLATE).then().statusCode(200);
        CfnStackWaits.awaitStatus(stackName, "CREATE_COMPLETE", CFN_AUTH);
        return stackName;
    }

    private static String stackId(String stackName) {
        return cfn("DescribeStacks", stackName).then().statusCode(200).extract().xmlPath()
                .getString("DescribeStacksResponse.DescribeStacksResult.Stacks.member.StackId");
    }

    private static Response policyOf(String stackName) {
        return cfn("GetStackPolicy", stackName);
    }

    private static String storedPolicy(String stackName) {
        Response response = policyOf(stackName);
        response.then().statusCode(200);
        return response.xmlPath().getString("GetStackPolicyResponse.GetStackPolicyResult.StackPolicyBody");
    }

    private static String validationError(Response response) {
        return response.then().statusCode(400).body(containsString("<Code>ValidationError</Code>"))
                .extract().xmlPath().getString("ErrorResponse.Error.Message");
    }

    /** How many events the stack's one resource has, so a policy-only update can be shown not to touch it. */
    private static int resourceEvents(String stackName) {
        return cfn("DescribeStackEvents", stackName).then().statusCode(200).extract().xmlPath()
                .getList("DescribeStackEventsResponse.DescribeStackEventsResult.StackEvents.member"
                        + ".findAll { it.LogicalResourceId == 'P' }").size();
    }

    private static Response cfn(String action, String stackName, String... params) {
        RequestSpecification request = CfnRequests.cfn(action, stackName).header("Authorization", CFN_AUTH);
        for (int i = 0; i < params.length; i += 2) {
            request = request.formParam(params[i], params[i + 1]);
        }
        return request.when().post("/");
    }
}
