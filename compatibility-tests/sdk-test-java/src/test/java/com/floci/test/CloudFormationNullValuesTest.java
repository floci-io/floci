package com.floci.test;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import software.amazon.awssdk.services.cloudformation.CloudFormationClient;
import software.amazon.awssdk.services.cloudformation.model.CloudFormationException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("CloudFormation templates holding a null value")
class CloudFormationNullValuesTest {

    private static CloudFormationClient cfn;
    private String stackName;

    @BeforeAll
    static void clients() {
        cfn = TestFixtures.cloudFormationClient();
    }

    @BeforeEach
    void setup() {
        stackName = TestFixtures.uniqueName("compat-cfn-null");
    }

    /** The stack is never created, and DeleteStack of a stack that does not exist succeeds; this covers a regression. */
    @AfterEach
    void cleanup() {
        cfn.deleteStack(r -> r.stackName(stackName));
    }

    @AfterAll
    static void closeClients() {
        cfn.close();
    }

    @Test
    @DisplayName("ValidateTemplate and CreateStack name the first null, and nothing is created")
    void aNullValueIsRefused() {
        String template = """
                Resources:
                  Queue:
                    Type: AWS::SQS::Queue
                    Properties:
                      QueueName:
                      DelaySeconds: ~
                """;
        String message = "[/Resources/Queue/Type/QueueName] 'null' values are not allowed in templates";

        assertThatThrownBy(() -> cfn.validateTemplate(r -> r.templateBody(template)))
                .isInstanceOfSatisfying(CloudFormationException.class, e -> {
                    assertThat(e.awsErrorDetails().errorCode()).isEqualTo("ValidationError");
                    assertThat(e.awsErrorDetails().errorMessage()).isEqualTo(message);
                });
        assertThatThrownBy(() -> cfn.createStack(r -> r.stackName(stackName).templateBody(template)))
                .isInstanceOfSatisfying(CloudFormationException.class, e -> {
                    assertThat(e.awsErrorDetails().errorCode()).isEqualTo("ValidationError");
                    assertThat(e.awsErrorDetails().errorMessage()).isEqualTo(message);
                });

        assertThatThrownBy(() -> cfn.describeStacks(r -> r.stackName(stackName)))
                .isInstanceOf(CloudFormationException.class)
                .hasMessageContaining("does not exist");
    }
}
