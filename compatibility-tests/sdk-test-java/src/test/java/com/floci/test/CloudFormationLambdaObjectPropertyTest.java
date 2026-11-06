package com.floci.test;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import software.amazon.awssdk.services.cloudformation.CloudFormationClient;
import software.amazon.awssdk.services.cloudformation.model.OperationEvent;
import software.amazon.awssdk.services.cloudformation.model.StackEvent;
import software.amazon.awssdk.services.lambda.LambdaClient;
import software.amazon.awssdk.services.lambda.model.Architecture;

import java.time.Duration;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A property of AWS::Lambda::Function of the wrong JSON type, through the CloudFormation SDK
 * client. Measured on AWS (eu-west-1, 2026-10-18): the stack fails before the function is touched,
 * "Property [VpcConfig] expected type: JSONObject, found: String" ("found: JSONArray" for a list).
 * AWS reports it from its pre-deployment validation, read through DescribeEvents; Floci, which
 * does not model that validation, reports the same message on the resource's failed event.
 */
@DisplayName("CloudFormation Lambda object properties")
class CloudFormationLambdaObjectPropertyTest {

    private static final String ROLE = "arn:aws:iam::000000000000:role/cfn-lambda-role";
    private static final Duration DEPLOY_TIMEOUT = Duration.ofSeconds(30);
    private static final String STRING_STACK = TestFixtures.uniqueName("compat-cfn-object-string");
    private static final String LIST_STACK = TestFixtures.uniqueName("compat-cfn-object-list");
    private static final String LAYERS_STACK = TestFixtures.uniqueName("compat-cfn-layers-object");
    private static final String DELIMITED_STACK = TestFixtures.uniqueName("compat-cfn-comma-delimited");
    private static final String STRING_PARAMETER_STACK = TestFixtures.uniqueName("compat-cfn-string-parameter");
    private static final String NESTED_STACK = TestFixtures.uniqueName("compat-cfn-nested-member");

    private static CloudFormationClient cfn;

    @BeforeAll
    static void setup() {
        cfn = TestFixtures.cloudFormationClient();
    }

    @AfterAll
    static void cleanup() {
        if (cfn != null) {
            // DeleteStack answers for a stack that is gone too, so a failure here is a real one.
            cfn.deleteStack(request -> request.stackName(STRING_STACK));
            cfn.deleteStack(request -> request.stackName(LIST_STACK));
            cfn.deleteStack(request -> request.stackName(LAYERS_STACK));
            cfn.deleteStack(request -> request.stackName(DELIMITED_STACK));
            cfn.deleteStack(request -> request.stackName(STRING_PARAMETER_STACK));
            cfn.deleteStack(request -> request.stackName(NESTED_STACK));
            cfn.close();
        }
    }

    @Test
    @DisplayName("a VpcConfig that is a string or a DeadLetterConfig that is a list fails the stack")
    void anObjectPropertyThatIsNotAnObjectFailsTheStack() throws InterruptedException {
        cfn.createStack(request -> request.stackName(STRING_STACK).templateBody(template("\"VpcConfig\": \"x\"")));
        assertThat(TestFixtures.awaitStackTerminal(cfn, STRING_STACK, DEPLOY_TIMEOUT)).isEqualTo("ROLLBACK_COMPLETE");
        assertThat(reasons(STRING_STACK)).anyMatch(reason ->
                reason.contains("Property [VpcConfig] expected type: JSONObject, found: String"));

        cfn.createStack(request -> request.stackName(LIST_STACK)
                .templateBody(template("\"DeadLetterConfig\": [\"x\"]")));
        assertThat(TestFixtures.awaitStackTerminal(cfn, LIST_STACK, DEPLOY_TIMEOUT)).isEqualTo("ROLLBACK_COMPLETE");
        assertThat(reasons(LIST_STACK)).anyMatch(reason ->
                reason.contains("Property [DeadLetterConfig] expected type: JSONObject, found: JSONArray"));
    }

    @Test
    @DisplayName("a list property that is an object fails the stack")
    void aListPropertyThatIsAnObjectFailsTheStack() throws InterruptedException {
        cfn.createStack(request -> request.stackName(LAYERS_STACK).templateBody(template("\"Layers\": {\"a\": \"b\"}")));
        assertThat(TestFixtures.awaitStackTerminal(cfn, LAYERS_STACK, DEPLOY_TIMEOUT)).isEqualTo("ROLLBACK_COMPLETE");
        assertThat(reasons(LAYERS_STACK)).anyMatch(reason ->
                reason.contains("Property [Layers] expected type: JSONArray, found: JSONObject"));
    }

    /**
     * Measured on AWS (eu-west-1, 2026-10-18): a {@code Ref} to a {@code CommaDelimitedList}
     * parameter is a list, so the stack is created and the function has it.
     */
    @Test
    @DisplayName("a list property from a CommaDelimitedList parameter is a list")
    void aListPropertyFromACommaDelimitedListIsAList() throws InterruptedException {
        Assumptions.assumeFalse(TestFixtures.isRealAws(), "the function needs a real execution role on AWS");
        cfn.createStack(request -> request.stackName(DELIMITED_STACK).templateBody("""
                {
                  "Parameters": {"Archs": {"Type": "CommaDelimitedList", "Default": "arm64"}},
                  "Resources": {
                    "Fn": {
                      "Type": "AWS::Lambda::Function",
                      "Properties": {
                        "Runtime": "python3.14",
                        "Handler": "index.handler",
                        "Role": "%s",
                        "Code": {"ZipFile": "def handler(e, c): return 'ok'"},
                        "Architectures": {"Ref": "Archs"}
                      }
                    }
                  }
                }
                """.formatted(ROLE)));
        assertThat(TestFixtures.awaitStackTerminal(cfn, DELIMITED_STACK, DEPLOY_TIMEOUT)).isEqualTo("CREATE_COMPLETE");

        String function = cfn.describeStackResource(request -> request.stackName(DELIMITED_STACK)
                .logicalResourceId("Fn")).stackResourceDetail().physicalResourceId();
        try (LambdaClient lambda = TestFixtures.lambdaClient()) {
            assertThat(lambda.getFunctionConfiguration(request -> request.functionName(function)).architectures())
                    .containsExactly(Architecture.ARM64);
        }
    }

    /**
     * Measured on AWS (eu-west-1, 2026-10-18): a {@code Ref} to a {@code String} parameter is a
     * string, so a list property refuses it, though the same value from a {@code CommaDelimitedList}
     * parameter is a list.
     */
    @Test
    @DisplayName("a list property from a String parameter fails the stack")
    void aListPropertyFromAStringParameterFailsTheStack() throws InterruptedException {
        cfn.createStack(request -> request.stackName(STRING_PARAMETER_STACK).templateBody("""
                {
                  "Parameters": {"Archs": {"Type": "String", "Default": "x86_64"}},
                  "Resources": {
                    "Fn": {
                      "Type": "AWS::Lambda::Function",
                      "Properties": {
                        "Runtime": "python3.14",
                        "Handler": "index.handler",
                        "Role": "%s",
                        "Code": {"ZipFile": "def handler(e, c): return 'ok'"},
                        "Architectures": {"Ref": "Archs"}
                      }
                    }
                  }
                }
                """.formatted(ROLE)));
        assertThat(TestFixtures.awaitStackTerminal(cfn, STRING_PARAMETER_STACK, DEPLOY_TIMEOUT))
                .isEqualTo("ROLLBACK_COMPLETE");
        assertThat(reasons(STRING_PARAMETER_STACK)).anyMatch(reason ->
                reason.contains("Property [Architectures] expected type: JSONArray, found: String"));
    }

    /** Measured on AWS (eu-west-1, 2026-10-19): a member nested in a property is typed too. */
    @Test
    @DisplayName("a VpcConfig whose SubnetIds is a string fails the stack")
    void aNestedMemberOfTheWrongTypeFailsTheStack() throws InterruptedException {
        cfn.createStack(request -> request.stackName(NESTED_STACK).templateBody(
                template("\"VpcConfig\": {\"SubnetIds\": \"subnet-0123456789abcdef0\", \"SecurityGroupIds\": []}")));
        assertThat(TestFixtures.awaitStackTerminal(cfn, NESTED_STACK, DEPLOY_TIMEOUT)).isEqualTo("ROLLBACK_COMPLETE");
        assertThat(reasons(NESTED_STACK)).anyMatch(reason ->
                reason.contains("Property [SubnetIds] expected type: JSONArray, found: String"));
    }

    private static List<String> reasons(String stackName) {
        if (TestFixtures.isRealAws()) {
            return cfn.describeEvents(request -> request.stackName(stackName)).operationEvents().stream()
                    .map(OperationEvent::validationStatusReason)
                    .filter(reason -> reason != null)
                    .toList();
        }
        return cfn.describeStackEvents(request -> request.stackName(stackName)).stackEvents().stream()
                .map(StackEvent::resourceStatusReason)
                .filter(reason -> reason != null)
                .toList();
    }

    private static String template(String property) {
        return """
                {
                  "Resources": {
                    "Fn": {
                      "Type": "AWS::Lambda::Function",
                      "Properties": {
                        "Runtime": "python3.14",
                        "Handler": "index.handler",
                        "Role": "%s",
                        "Code": {"ZipFile": "def handler(e, c): return 'ok'"},
                        %s
                      }
                    }
                  }
                }
                """.formatted(ROLE, property);
    }
}
