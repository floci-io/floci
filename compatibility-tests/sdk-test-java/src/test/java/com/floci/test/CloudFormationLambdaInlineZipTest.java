package com.floci.test;

import org.junit.jupiter.api.*;
import software.amazon.awssdk.services.cloudformation.CloudFormationClient;
import software.amazon.awssdk.services.cloudformation.model.*;
import software.amazon.awssdk.services.lambda.LambdaClient;
import software.amazon.awssdk.services.lambda.model.GetFunctionResponse;

import java.util.List;

import static org.assertj.core.api.Assertions.*;

/**
 * Verifies that CloudFormation AWS::Lambda::Function with inline ZipFile source code
 * reaches CREATE_COMPLETE instead of failing with "Illegal base64 character".
 * Reproduces https://github.com/floci-io/floci/issues/607
 */
@DisplayName("CloudFormation Lambda inline ZipFile")
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class CloudFormationLambdaInlineZipTest {

    private static final String STACK_NAME  = "compat-cfn-inline-zip-stack";
    private static final String FUNC_NAME   = "compat-cfn-inline-zip-fn";
    private static final String ROLE        = "arn:aws:iam::000000000000:role/cfn-lambda-role";

    private static CloudFormationClient cfn;
    private static LambdaClient         lambda;

    @BeforeAll
    static void setup() {
        cfn    = TestFixtures.cloudFormationClient();
        lambda = TestFixtures.lambdaClient();
    }

    @AfterAll
    static void cleanup() {
        if (cfn != null) {
            try {
                cfn.deleteStack(DeleteStackRequest.builder().stackName(STACK_NAME).build());
            } catch (Exception ignored) {}
            cfn.close();
        }
        if (lambda != null) {
            lambda.close();
        }
    }

    // ── Node.js inline source ──────────────────────────────────────────────────

    @Test
    @Order(1)
    @DisplayName("CreateStack with Node.js ZipFile source reaches CREATE_COMPLETE")
    void createStack_nodejsInlineZipFile() throws InterruptedException {
        String template = """
            {
              "Resources": {
                "MyFunction": {
                  "Type": "AWS::Lambda::Function",
                  "Properties": {
                    "FunctionName": "%s",
                    "Runtime": "nodejs20.x",
                    "Handler": "index.handler",
                    "Role": "%s",
                    "Code": {
                      "ZipFile": "exports.handler = async (e) => ({ statusCode: 200, body: 'ok' });"
                    }
                  }
                }
              }
            }
            """.formatted(FUNC_NAME, ROLE);

        cfn.createStack(CreateStackRequest.builder()
                .stackName(STACK_NAME)
                .templateBody(template)
                .build());

        String status = waitForTerminal(STACK_NAME, 30);
        assertThat(status)
                .as("Stack must reach CREATE_COMPLETE, not fail with base64 error")
                .isEqualTo("CREATE_COMPLETE");
    }

    @Test
    @Order(2)
    @DisplayName("Lambda function created by inline ZipFile stack is discoverable")
    void lambdaFunctionExists() {
        GetFunctionResponse fn = lambda.getFunction(r -> r.functionName(FUNC_NAME));
        assertThat(fn.configuration().functionName()).isEqualTo(FUNC_NAME);
        assertThat(fn.configuration().runtimeAsString()).isEqualTo("nodejs20.x");
        assertThat(fn.configuration().handler()).isEqualTo("index.handler");
    }

    @Test
    @Order(3)
    @DisplayName("DescribeStackResources shows Lambda in CREATE_COMPLETE")
    void stackResourceIsComplete() {
        List<StackResource> resources = cfn.describeStackResources(
                DescribeStackResourcesRequest.builder().stackName(STACK_NAME).build()
        ).stackResources();

        assertThat(resources).hasSize(1);
        StackResource r = resources.get(0);
        assertThat(r.resourceType()).isEqualTo("AWS::Lambda::Function");
        assertThat(r.resourceStatusAsString()).isEqualTo("CREATE_COMPLETE");
    }

    // ── Python inline source ───────────────────────────────────────────────────

    @Test
    @Order(4)
    @DisplayName("CreateStack with Python ZipFile source reaches CREATE_COMPLETE")
    void createStack_pythonInlineZipFile() throws InterruptedException {
        String pythonStack = STACK_NAME + "-py";
        String pythonFunc  = FUNC_NAME  + "-py";

        String template = """
            {
              "Resources": {
                "PyFunction": {
                  "Type": "AWS::Lambda::Function",
                  "Properties": {
                    "FunctionName": "%s",
                    "Runtime": "python3.12",
                    "Handler": "lambda_function.lambda_handler",
                    "Role": "%s",
                    "Code": {
                      "ZipFile": "def lambda_handler(event, context):\\n    return {'statusCode': 200}"
                    }
                  }
                }
              }
            }
            """.formatted(pythonFunc, ROLE);

        cfn.createStack(CreateStackRequest.builder()
                .stackName(pythonStack)
                .templateBody(template)
                .build());

        String status = waitForTerminal(pythonStack, 30);
        assertThat(status)
                .as("Python inline ZipFile stack must reach CREATE_COMPLETE")
                .isEqualTo("CREATE_COMPLETE");

        GetFunctionResponse fn = lambda.getFunction(r -> r.functionName(pythonFunc));
        assertThat(fn.configuration().runtimeAsString()).isEqualTo("python3.12");
        assertThat(fn.configuration().handler()).isEqualTo("lambda_function.lambda_handler");

        cfn.deleteStack(DeleteStackRequest.builder().stackName(pythonStack).build());
    }

    // ── helpers ────────────────────────────────────────────────────────────────

    private String waitForTerminal(String stackName, int maxSeconds) throws InterruptedException {
        long deadline = System.currentTimeMillis() + maxSeconds * 1000L;
        while (System.currentTimeMillis() < deadline) {
            List<Stack> stacks = cfn.describeStacks(
                    DescribeStacksRequest.builder().stackName(stackName).build()
            ).stacks();
            if (!stacks.isEmpty()) {
                String status = stacks.get(0).stackStatusAsString();
                if (!status.endsWith("_IN_PROGRESS")) {
                    return status;
                }
            }
            Thread.sleep(500);
        }
        throw new AssertionError("Stack " + stackName + " did not reach a terminal state within " + maxSeconds + "s");
    }
}
