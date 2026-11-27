package io.floci.aws.services.stepfunctions;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.floci.aws.core.common.RequestScopes;
import io.floci.aws.services.stepfunctions.model.Execution;
import io.floci.aws.services.stepfunctions.model.StateMachine;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.time.Duration;
import java.util.Map;
import java.util.UUID;

import static org.awaitility.Awaitility.await;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * "Cross-Region AWS SDK integration and cross-Region AWS resource access aren't available in Step Functions"
 * (Developer Guide, "Accessing cross-account AWS resources in Step Functions"), so a task naming a state machine of
 * another Region starts nothing there or in its own Region.
 */
@QuarkusTest
class StepFunctionsNestedExecutionRegionIntegrationTest {

    private static final String ACCOUNT = "000000000739";
    private static final String PARENT_REGION = "us-east-1";
    private static final String CHILD_REGION = "eu-west-1";
    private static final String ROLE = "arn:aws:iam::" + ACCOUNT + ":role/test";
    private static final String PASS = """
            {"StartAt":"Done","States":{"Done":{"Type":"Pass","End":true}}}
            """;

    @Inject
    StepFunctionsService states;

    @Inject
    ObjectMapper mapper;

    @ParameterizedTest
    @CsvSource({"states:startExecution,StepFunctions.AWSStepFunctionsException,STANDARD",
            "states:startExecution.sync,StepFunctions.AWSStepFunctionsException,STANDARD",
            "states:startExecution.sync:2,StepFunctions.AWSStepFunctionsException,STANDARD",
            "states:startExecution.waitForTaskToken,StepFunctions.AWSStepFunctionsException,STANDARD",
            "aws-sdk:sfn:startExecution,Sfn.SfnException,STANDARD",
            "aws-sdk:sfn:startSyncExecution,Sfn.SfnException,EXPRESS"})
    void aStateMachineInAnotherRegionFailsTheTaskAndStartsNothing(String integration, String error, String childType)
            throws Exception {
        String suffix = UUID.randomUUID().toString();
        StateMachine child = RequestScopes.callAs(ACCOUNT, CHILD_REGION, () -> states.createStateMachine(
                "regional-child-" + suffix, PASS, ROLE, childType, CHILD_REGION, Map.of()));
        String definition = mapper.writeValueAsString(Map.of("StartAt", "Child", "States", Map.of("Child", Map.of(
                "Type", "Task", "Resource", "arn:aws:states:::" + integration,
                "Parameters", Map.of("StateMachineArn", child.getStateMachineArn(), "Input", Map.of()),
                "End", true))));
        StateMachine parent = RequestScopes.callAs(ACCOUNT, PARENT_REGION, () -> states.createStateMachine(
                "regional-parent-" + suffix, definition, ROLE, "STANDARD", PARENT_REGION, Map.of()));
        try {
            Execution execution = RequestScopes.callAs(ACCOUNT, PARENT_REGION,
                    () -> states.startExecution(parent.getStateMachineArn(), null, "{}", PARENT_REGION));
            await().atMost(Duration.ofSeconds(10)).until(() -> RequestScopes.callAs(ACCOUNT, PARENT_REGION,
                    () -> !"RUNNING".equals(states.describeExecution(execution.getExecutionArn()).getStatus())));
            Execution result = RequestScopes.callAs(ACCOUNT, PARENT_REGION,
                    () -> states.describeExecution(execution.getExecutionArn()));

            assertEquals("FAILED", result.getStatus(), result.getCause());
            assertEquals(error, result.getError(), result.getCause());
            assertTrue(result.getCause().contains(child.getStateMachineArn()), result.getCause());
            for (String region : new String[] {PARENT_REGION, CHILD_REGION}) {
                assertTrue(RequestScopes.callAs(ACCOUNT, region,
                        () -> states.listExecutions(child.getStateMachineArn())).isEmpty(), region);
            }
        } finally {
            RequestScopes.runAs(ACCOUNT, PARENT_REGION, () -> states.deleteStateMachine(parent.getStateMachineArn()));
            RequestScopes.runAs(ACCOUNT, CHILD_REGION, () -> states.deleteStateMachine(child.getStateMachineArn()));
        }
    }
}
