package com.floci.test;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import software.amazon.awssdk.services.cloudformation.CloudFormationClient;
import software.amazon.awssdk.services.cloudformation.model.CloudFormationException;
import software.amazon.awssdk.services.sqs.SqsClient;
import software.amazon.awssdk.services.sqs.model.QueueDoesNotExistException;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("CloudFormation CreateStack with a dependency cycle")
class CloudFormationCircularDependencyTest {

    private static CloudFormationClient cfn;
    private static SqsClient sqs;
    private String stackName;

    @BeforeAll
    static void clients() {
        cfn = TestFixtures.cloudFormationClient();
        sqs = TestFixtures.sqsClient();
    }

    @BeforeEach
    void setup() {
        stackName = TestFixtures.uniqueName("compat-cfn-cycle");
    }

    @AfterEach
    void cleanup() {
        try {
            cfn.deleteStack(r -> r.stackName(stackName));
        } catch (CloudFormationException ignored) {
            // The stack is never created; this only covers the rejection regressing.
        }
    }

    @AfterAll
    static void closeClients() {
        cfn.close();
        sqs.close();
    }

    @Test
    @DisplayName("rejects the template, names the cycle and creates nothing")
    void rejectsCycleBeforeCreatingResources() {
        String template = """
                {"Resources":{
                  "Downstream":{"Type":"AWS::SQS::Queue","DependsOn":"FirstQueue",
                    "Properties":{"QueueName":"%1$s-downstream"}},
                  "FirstQueue":{"Type":"AWS::SQS::Queue","DependsOn":"SecondQueue",
                    "Properties":{"QueueName":"%1$s-first"}},
                  "SecondQueue":{"Type":"AWS::SQS::Queue","DependsOn":"FirstQueue",
                    "Properties":{"QueueName":"%1$s-second"}}}}
                """.formatted(stackName);

        assertThatThrownBy(() -> cfn.createStack(r -> r.stackName(stackName).templateBody(template)))
                .isInstanceOfSatisfying(CloudFormationException.class, e -> {
                    assertThat(e.awsErrorDetails().errorCode()).isEqualTo("ValidationError");
                    assertThat(e.awsErrorDetails().errorMessage())
                            .contains("Circular dependency between resources", "FirstQueue", "SecondQueue");
                });

        assertThatThrownBy(() -> cfn.describeStacks(r -> r.stackName(stackName)))
                .isInstanceOf(CloudFormationException.class)
                .hasMessageContaining("does not exist");
        for (String suffix : List.of("downstream", "first", "second")) {
            assertThatThrownBy(() -> sqs.getQueueUrl(r -> r.queueName(stackName + "-" + suffix)))
                    .isInstanceOf(QueueDoesNotExistException.class);
        }
    }
}
