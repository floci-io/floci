package io.github.hectorvent.floci.services.stepfunctions;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.hectorvent.floci.core.common.RequestScopes;
import io.github.hectorvent.floci.services.stepfunctions.model.Execution;
import io.github.hectorvent.floci.services.stepfunctions.model.StateMachine;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

import static org.awaitility.Awaitility.await;
import static org.junit.jupiter.api.Assertions.assertEquals;

@QuarkusTest
class StepFunctionsCallbackSdkRegionIntegrationTest {

    private static final String ACCOUNT = "724600009876";
    private static final String REGION = "us-west-2";

    @Inject
    StepFunctionsService states;
    @Inject
    ObjectMapper mapper;

    @ParameterizedTest
    @CsvSource({"sendTaskSuccess,us-west-2,true,Task", "sendTaskFailure,us-west-2,true,Task",
            "sendTaskSuccess,us-east-1,false,Task", "sendTaskFailure,us-east-1,false,Task",
            "sendTaskSuccess,us-west-2,true,Parallel", "sendTaskFailure,us-west-2,true,Parallel",
            "sendTaskSuccess,us-east-1,false,Parallel", "sendTaskFailure,us-east-1,false,Parallel",
            "sendTaskSuccess,us-west-2,true,Map", "sendTaskFailure,us-west-2,true,Map",
            "sendTaskSuccess,us-east-1,false,Map", "sendTaskFailure,us-east-1,false,Map"})
    void sdkCallbacksUseTheExecutionRegionRatherThanTheDefault(String operation, String tokenRegion,
                                                               boolean sameRegion, String worker) throws Exception {
        String suffix = UUID.randomUUID().toString();
        String token = "regional-" + suffix;
        String resource = "arn:aws:states:" + tokenRegion + ":" + ACCOUNT + ":stateMachine:waiter";
        CompletableFuture<JsonNode> waiting = states.registerPendingToken(token, ACCOUNT, resource);
        Map<String, Object> parameters = new HashMap<>();
        parameters.put("TaskToken", token);
        if ("sendTaskSuccess".equals(operation)) {
            parameters.put("Output", "{}");
        } else {
            parameters.put("Error", "Rejected");
            parameters.put("Cause", "test");
        }
        Map<String, Object> callback = Map.of("Type", "Task", "Resource", "arn:aws:states:::aws-sdk:sfn:" + operation,
                "Parameters", parameters, "End", true);
        Map<String, Object> branch = Map.of("StartAt", "Send", "States", Map.of("Send", callback));
        Map<String, Object> entry = switch (worker) {
            case "Parallel" -> Map.of("Type", "Parallel", "Branches", List.of(branch), "End", true);
            case "Map" -> Map.of("Type", "Map", "ItemsPath", "$", "MaxConcurrency", 2, "Iterator", branch, "End", true);
            default -> callback;
        };
        String definition = mapper.writeValueAsString(Map.of("StartAt", "Send", "States", Map.of("Send", entry)));
        StateMachine machine = RequestScopes.callAs(ACCOUNT, REGION, () -> states.createStateMachine(
                "regional-" + suffix, definition, "arn:aws:iam::" + ACCOUNT + ":role/test", "STANDARD", REGION, Map.of()));
        try {
            Execution execution = RequestScopes.callAs(ACCOUNT, REGION,
                    () -> states.startExecution(machine.getStateMachineArn(), "sender", "Map".equals(worker) ? "[{}]" : "{}", REGION));
            await().atMost(Duration.ofSeconds(10)).until(() -> RequestScopes.callAs(ACCOUNT, REGION,
                    () -> !"RUNNING".equals(states.describeExecution(execution.getExecutionArn()).getStatus())));
            Execution result = RequestScopes.callAs(ACCOUNT, REGION, () -> states.describeExecution(execution.getExecutionArn()));
            assertEquals(sameRegion ? "SUCCEEDED" : "FAILED", result.getStatus());
            assertEquals(sameRegion, waiting.isDone());
            if (!sameRegion && "Task".equals(worker)) {
                assertEquals("Sfn.InvalidTokenException", result.getError());
            }
        } finally {
            states.discardPendingToken(token);
            RequestScopes.runAs(ACCOUNT, REGION, () -> states.deleteStateMachine(machine.getStateMachineArn()));
        }
    }
}
