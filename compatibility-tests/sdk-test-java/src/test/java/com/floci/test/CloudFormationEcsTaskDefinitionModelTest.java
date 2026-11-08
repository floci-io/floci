package com.floci.test;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import software.amazon.awssdk.services.cloudformation.CloudFormationClient;
import software.amazon.awssdk.services.cloudformation.model.StackEvent;
import software.amazon.awssdk.services.ecs.EcsClient;
import software.amazon.awssdk.services.ecs.model.ContainerDefinition;

import java.time.Duration;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * An AWS::ECS::TaskDefinition read as the ECS handler reads it, through the CloudFormation SDK
 * client. Measured on AWS (eu-west-1, 2026-10-19): CloudFormation hands the properties over
 * without template validation, every scalar as text; the handler converts "+128" and "False" (a
 * second container, since ECS refuses a task definition without an essential one), and
 * a value it cannot convert fails the resource with every violation of the unconverted model,
 * the Memory it could convert included.
 */
@DisplayName("CloudFormation ECS task definition model")
class CloudFormationEcsTaskDefinitionModelTest {

    private static final Duration DEPLOY_TIMEOUT =
            TestFixtures.isRealAws() ? Duration.ofMinutes(10) : Duration.ofSeconds(10);
    private static final String UNCONVERTED_STACK = TestFixtures.uniqueName("compat-cfn-ecs-unconverted");
    private static final String CONVERTED_STACK = TestFixtures.uniqueName("compat-cfn-ecs-converted");
    private static final String HINT = ". This may occur when a numeric value exceeds 32-bit integer max"
            + " (2147483647). Consider using 'number' type or ensure value fits in integer range.";

    private static CloudFormationClient cfn;

    @BeforeAll
    static void setup() {
        cfn = TestFixtures.cloudFormationClient();
    }

    @AfterAll
    static void cleanup() {
        if (cfn != null) {
            // DeleteStack answers for a stack that is gone too, so a failure here is a real one.
            cfn.deleteStack(request -> request.stackName(UNCONVERTED_STACK));
            cfn.deleteStack(request -> request.stackName(CONVERTED_STACK));
            cfn.close();
        }
    }

    @Test
    @DisplayName("a value the handler cannot convert fails the stack with every violation")
    void aValueTheHandlerCannotConvertFailsTheStack() throws InterruptedException {
        cfn.createStack(request -> request.stackName(UNCONVERTED_STACK).templateBody(template(UNCONVERTED_STACK,
                "\"Memory\": 128, \"LinuxParameters\": {\"SharedMemorySize\": \"abc\"}")));
        assertThat(TestFixtures.awaitStackTerminal(cfn, UNCONVERTED_STACK, DEPLOY_TIMEOUT)).isEqualTo("ROLLBACK_COMPLETE");
        assertThat(reasons(UNCONVERTED_STACK)).anyMatch(reason -> reason.contains("Model validation failed"
                + " (#/ContainerDefinitions/0/Memory: expected type: Integer, found: String" + HINT
                + "\n#/ContainerDefinitions/0/LinuxParameters/SharedMemorySize: expected type: Integer, found: String"
                + HINT + ")"));
    }

    @Test
    @DisplayName("values the handler converts are registered converted")
    void valuesTheHandlerConvertsAreRegisteredConverted() throws InterruptedException {
        cfn.createStack(request -> request.stackName(CONVERTED_STACK).templateBody(template(CONVERTED_STACK,
                "\"Memory\": \"+128\"}, {\"Name\": \"side\", \"Image\": \"public.ecr.aws/docker/library/busybox:latest\","
                        + " \"Memory\": 64, \"Essential\": \"False\"")));
        assertThat(TestFixtures.awaitStackTerminal(cfn, CONVERTED_STACK, DEPLOY_TIMEOUT)).isEqualTo("CREATE_COMPLETE");

        String taskDefinition = cfn.describeStackResource(request -> request.stackName(CONVERTED_STACK)
                .logicalResourceId("TaskDef")).stackResourceDetail().physicalResourceId();
        try (EcsClient ecs = TestFixtures.ecsClient()) {
            List<ContainerDefinition> containers = ecs.describeTaskDefinition(request -> request
                    .taskDefinition(taskDefinition)).taskDefinition().containerDefinitions();
            assertThat(containers).filteredOn(container -> container.name().equals("app"))
                    .singleElement().extracting(ContainerDefinition::memory).isEqualTo(128);
            assertThat(containers).filteredOn(container -> container.name().equals("side"))
                    .singleElement().extracting(ContainerDefinition::essential).isEqualTo(false);
        }
    }

    private static List<String> reasons(String stackName) {
        return cfn.describeStackEvents(request -> request.stackName(stackName)).stackEvents().stream()
                .map(StackEvent::resourceStatusReason)
                .filter(reason -> reason != null)
                .toList();
    }

    /**
     * A task definition named after the stack, whose first container has {@code members} beside
     * its name and image; {@code members} may close it and open others.
     */
    private static String template(String family, String members) {
        return """
                {
                  "Resources": {
                    "TaskDef": {
                      "Type": "AWS::ECS::TaskDefinition",
                      "Properties": {
                        "Family": "%s",
                        "ContainerDefinitions": [{
                          "Name": "app",
                          "Image": "public.ecr.aws/docker/library/busybox:latest",
                          %s
                        }]
                      }
                    }
                  }
                }
                """.formatted(family, members);
    }
}
