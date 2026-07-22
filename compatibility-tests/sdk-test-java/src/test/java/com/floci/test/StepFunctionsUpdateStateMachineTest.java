package com.floci.test;

import java.util.logging.Level;
import java.util.logging.Logger;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import software.amazon.awssdk.services.sfn.SfnClient;
import software.amazon.awssdk.services.sfn.model.DescribeStateMachineResponse;
import software.amazon.awssdk.services.sfn.model.EncryptionType;
import software.amazon.awssdk.services.sfn.model.LogLevel;
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
                .roleArn(ROLE_ARN)
                .loggingConfiguration(logging -> logging
                        .level(LogLevel.ALL)
                        .includeExecutionData(true)
                        .destinations(destination -> destination
                                .cloudWatchLogsLogGroup(group -> group.logGroupArn(
                                        "arn:aws:logs:us-east-1:000000000000:log-group:sfn:*"))))
                .tracingConfiguration(tracing -> tracing.enabled(true))
                .encryptionConfiguration(encryption -> encryption
                        .type(EncryptionType.CUSTOMER_MANAGED_KMS_KEY)
                        .kmsKeyId("alias/sfn-key")
                        .kmsDataKeyReusePeriodSeconds(120))
                .publish(true)
                .versionDescription("SDK update snapshot"));

        assertThat(update.updateDate()).isNotNull();
        assertThat(update.revisionId()).isNotBlank();
        assertThat(update.stateMachineVersionArn()).isEqualTo(stateMachineArn + ":1");

        DescribeStateMachineResponse describe = sfn.describeStateMachine(b -> b
                .stateMachineArn(stateMachineArn));
        assertThat(describe.definition()).isEqualTo(UPDATED_DEFINITION);
        assertThat(describe.roleArn()).isEqualTo(ROLE_ARN);
        assertThat(describe.revisionId()).isEqualTo(update.revisionId());
        assertThat(describe.loggingConfiguration().level()).isEqualTo(LogLevel.ALL);
        assertThat(describe.loggingConfiguration().includeExecutionData()).isTrue();
        assertThat(describe.tracingConfiguration().enabled()).isTrue();
        assertThat(describe.encryptionConfiguration().type())
                .isEqualTo(EncryptionType.CUSTOMER_MANAGED_KMS_KEY);

        DescribeStateMachineResponse version = sfn.describeStateMachine(b -> b
                .stateMachineArn(update.stateMachineVersionArn()));
        assertThat(version.description()).isEqualTo("SDK update snapshot");
        assertThat(version.definition()).isEqualTo(UPDATED_DEFINITION);
        assertThat(version.revisionId()).isEqualTo(update.revisionId());
    }
}
