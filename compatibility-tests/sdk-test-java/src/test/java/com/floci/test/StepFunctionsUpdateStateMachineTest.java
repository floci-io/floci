package com.floci.test;

import java.util.logging.Level;
import java.util.logging.Logger;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import software.amazon.awssdk.services.sfn.SfnClient;
import software.amazon.awssdk.services.sfn.model.DescribeStateMachineResponse;
import software.amazon.awssdk.services.sfn.model.UpdateStateMachineResponse;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("SFN UpdateStateMachine")
class StepFunctionsUpdateStateMachineTest {

    private static final Logger LOGGER = Logger.getLogger(StepFunctionsUpdateStateMachineTest.class.getName());
    private static final String ROLE_ARN = "arn:aws:iam::000000000000:role/service-role/test-role";
    private static final String INITIAL_DEFINITION = """
            {"StartAt":"Initial","States":{"Initial":{"Type":"Pass","End":true}}}
            """.strip();
    private static final String UPDATED_DEFINITION = """
            {"StartAt":"Updated","States":{"Updated":{"Type":"Pass","End":true}}}
            """.strip();

    private static SfnClient sfn;
    private static String stateMachineArn;

    @BeforeAll
    static void setup() {
        sfn = TestFixtures.sfnClient();
        stateMachineArn = sfn.createStateMachine(b -> b
                .name(TestFixtures.uniqueName("update-sm"))
                .definition(INITIAL_DEFINITION)
                .roleArn(ROLE_ARN))
                .stateMachineArn();
    }

    @AfterAll
    static void cleanup() {
        if (sfn != null) {
            if (stateMachineArn != null) {
                try {
                    sfn.deleteStateMachine(b -> b.stateMachineArn(stateMachineArn));
                } catch (Exception e) {
                    LOGGER.log(Level.WARNING, "Failed to clean up Step Functions update compatibility test", e);
                }
            }
            sfn.close();
        }
    }

    @Test
    void sdkUpdatesAndDescribesStateMachine() {
        UpdateStateMachineResponse update = sfn.updateStateMachine(b -> b
                .stateMachineArn(stateMachineArn)
                .definition(UPDATED_DEFINITION)
                .roleArn(ROLE_ARN));

        assertThat(update.updateDate()).isNotNull();

        DescribeStateMachineResponse describe = sfn.describeStateMachine(b -> b
                .stateMachineArn(stateMachineArn));
        assertThat(describe.definition()).isEqualTo(UPDATED_DEFINITION);
        assertThat(describe.roleArn()).isEqualTo(ROLE_ARN);
    }
}
