package com.floci.test;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import software.amazon.awssdk.services.cloudformation.CloudFormationClient;
import software.amazon.awssdk.services.lambda.LambdaClient;
import software.amazon.awssdk.services.lambda.model.LogFormat;
import software.amazon.awssdk.services.lambda.model.LoggingConfig;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * AWS::Lambda::Function.LoggingConfig through the CloudFormation and Lambda SDK clients. Measured on
 * AWS (eu-west-1, 2026-10-16): the function reads back the LogGroup the template names; a change to
 * JSON with an ApplicationLogLevel updates it in place, SystemLogLevel reading back INFO; removing
 * the property puts it back on Text and {@code /aws/lambda/<name>}, again in place.
 */
@DisplayName("CloudFormation Lambda LoggingConfig")
class CloudFormationLambdaLoggingConfigTest {

    private static final String STACK_NAME = TestFixtures.uniqueName("compat-cfn-logging");
    private static final String ROLE = "arn:aws:iam::000000000000:role/cfn-lambda-role";
    private static final Duration DEPLOY_TIMEOUT = Duration.ofSeconds(30);

    private static CloudFormationClient cfn;
    private static LambdaClient lambda;

    @BeforeAll
    static void setup() {
        cfn = TestFixtures.cloudFormationClient();
        lambda = TestFixtures.lambdaClient();
    }

    @AfterAll
    static void cleanup() {
        if (cfn != null) {
            // DeleteStack answers for a stack that is gone too, so a failure here is a real one.
            cfn.deleteStack(request -> request.stackName(STACK_NAME));
            cfn.close();
        }
        if (lambda != null) {
            lambda.close();
        }
    }

    @Test
    @DisplayName("LoggingConfig is created, updated and removed in place")
    void loggingConfigLifecycle() throws InterruptedException {
        cfn.createStack(request -> request.stackName(STACK_NAME)
                .templateBody(template(",\"LoggingConfig\": {\"LogGroup\": \"/compat/cfn-a\"}")));
        assertThat(TestFixtures.awaitStackTerminal(cfn, STACK_NAME, DEPLOY_TIMEOUT)).isEqualTo("CREATE_COMPLETE");
        String created = functionName();
        LoggingConfig named = loggingConfig(created);
        assertThat(named.logFormat()).isEqualTo(LogFormat.TEXT);
        assertThat(named.logGroup()).isEqualTo("/compat/cfn-a");
        assertThat(named.applicationLogLevel()).isNull();

        cfn.updateStack(request -> request.stackName(STACK_NAME).templateBody(template(
                ",\"LoggingConfig\": {\"LogGroup\": \"/compat/cfn-b\", \"LogFormat\": \"JSON\","
                        + " \"ApplicationLogLevel\": \"WARN\"}")));
        assertThat(TestFixtures.awaitStackTerminal(cfn, STACK_NAME, DEPLOY_TIMEOUT)).isEqualTo("UPDATE_COMPLETE");
        assertThat(functionName()).as("a changed LoggingConfig updates in place").isEqualTo(created);
        LoggingConfig json = loggingConfig(created);
        assertThat(json.logFormat()).isEqualTo(LogFormat.JSON);
        assertThat(json.applicationLogLevelAsString()).isEqualTo("WARN");
        assertThat(json.systemLogLevelAsString()).isEqualTo("INFO");
        assertThat(json.logGroup()).isEqualTo("/compat/cfn-b");

        cfn.updateStack(request -> request.stackName(STACK_NAME).templateBody(template("")));
        assertThat(TestFixtures.awaitStackTerminal(cfn, STACK_NAME, DEPLOY_TIMEOUT)).isEqualTo("UPDATE_COMPLETE");
        assertThat(functionName()).as("removing LoggingConfig updates in place").isEqualTo(created);
        LoggingConfig defaults = loggingConfig(created);
        assertThat(defaults.logFormat()).isEqualTo(LogFormat.TEXT);
        assertThat(defaults.applicationLogLevel()).isNull();
        assertThat(defaults.logGroup()).isEqualTo("/aws/lambda/" + created);
    }

    private static String template(String loggingConfig) {
        return """
                {
                  "Resources": {
                    "Fn": {
                      "Type": "AWS::Lambda::Function",
                      "Properties": {
                        "Runtime": "python3.14",
                        "Handler": "index.handler",
                        "Role": "%s",
                        "Code": {"ZipFile": "def handler(e, c): return 'ok'"}
                        %s
                      }
                    }
                  },
                  "Outputs": {"Name": {"Value": {"Ref": "Fn"}}}
                }
                """.formatted(ROLE, loggingConfig);
    }

    private static String functionName() {
        return cfn.describeStacks(request -> request.stackName(STACK_NAME)).stacks().get(0).outputs().get(0).outputValue();
    }

    private static LoggingConfig loggingConfig(String functionName) {
        return lambda.getFunctionConfiguration(request -> request.functionName(functionName)).loggingConfig();
    }
}
