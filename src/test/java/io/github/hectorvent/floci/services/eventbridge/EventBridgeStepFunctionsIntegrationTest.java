package io.github.hectorvent.floci.services.eventbridge;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.hectorvent.floci.core.common.RequestScopes;
import io.github.hectorvent.floci.services.eventbridge.model.InputTransformer;
import io.github.hectorvent.floci.services.eventbridge.model.RuleState;
import io.github.hectorvent.floci.services.eventbridge.model.Target;
import io.github.hectorvent.floci.services.stepfunctions.StepFunctionsService;
import io.github.hectorvent.floci.services.stepfunctions.model.Execution;
import io.github.hectorvent.floci.services.stepfunctions.model.StateMachine;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

@QuarkusTest
class EventBridgeStepFunctionsIntegrationTest {

    private static final String SOURCE_REGION = "us-east-1";
    private static final String TARGET_REGION = "eu-west-1";
    private static final String TARGET_ACCOUNT = "111122223333";
    private static final String ROLE_ARN = "arn:aws:iam::111122223333:role/eventbridge-role";
    private static final String DEFINITION =
            "{\"StartAt\":\"Pass\",\"States\":{\"Pass\":{\"Type\":\"Pass\",\"End\":true}}}";

    @Inject
    EventBridgeService eventBridgeService;

    @Inject
    StepFunctionsService stepFunctionsService;

    @Inject
    ObjectMapper objectMapper;

    private final List<String> ruleNames = new ArrayList<>();
    private final List<String> stateMachineArns = new ArrayList<>();

    @AfterEach
    void cleanUp() {
        for (String ruleName : ruleNames) {
            List<String> targetIds = eventBridgeService.listTargetsByRule(ruleName, null, SOURCE_REGION)
                    .stream()
                    .map(Target::getId)
                    .toList();
            eventBridgeService.removeTargets(ruleName, null, targetIds, SOURCE_REGION);
            eventBridgeService.deleteRule(ruleName, null, SOURCE_REGION);
        }
        RequestScopes.runAs(TARGET_ACCOUNT, () -> {
            for (String stateMachineArn : stateMachineArns) {
                stepFunctionsService.deleteStateMachine(stateMachineArn);
            }
        });
    }

    @Test
    void matchingRuleStartsStateMachinesWithEachInputMode() throws Exception {
        String defaultArn = createStateMachine("default");
        String explicitArn = createStateMachine("explicit");
        String pathArn = createStateMachine("path");
        String transformerArn = createStateMachine("transformer");
        String ruleName = createRule("input-modes", "{\"source\":[\"orders.created\"]}");

        Target defaultTarget = new Target("default", defaultArn, null, null);
        Target explicitTarget = new Target("explicit", explicitArn, "{\"mode\":\"explicit\"}", null);
        Target pathTarget = new Target("path", pathArn, null, "$.detail");
        Target transformerTarget = new Target("transformer", transformerArn, null, null);
        transformerTarget.setInputTransformer(new InputTransformer(
                Map.of("orderId", "$.detail.orderId"), "{\"order\":\"<orderId>\"}"));
        assertEquals(0, eventBridgeService.putTargets(
                ruleName, null,
                List.of(defaultTarget, explicitTarget, pathTarget, transformerTarget),
                SOURCE_REGION));

        EventBridgeService.PutEventsResult unmatched = eventBridgeService.putEvents(List.of(Map.of(
                "Source", "orders.ignored",
                "DetailType", "OrderCreated",
                "Detail", "{\"orderId\":\"o-42\"}")), SOURCE_REGION);
        assertEquals(0, unmatched.failedCount());
        assertEquals(0, executions(defaultArn).size());
        assertEquals(0, executions(explicitArn).size());
        assertEquals(0, executions(pathArn).size());
        assertEquals(0, executions(transformerArn).size());

        EventBridgeService.PutEventsResult matched = eventBridgeService.putEvents(List.of(Map.of(
                "Source", "orders.created",
                "DetailType", "OrderCreated",
                "Detail", "{\"orderId\":\"o-42\",\"total\":19}")), SOURCE_REGION);
        assertEquals(0, matched.failedCount());
        assertNotNull(matched.entries().getFirst().get("EventId"));

        Execution defaultExecution = onlyExecution(defaultArn);
        JsonNode defaultInput = objectMapper.readTree(defaultExecution.getInput());
        assertEquals("orders.created", defaultInput.path("source").asText());
        assertEquals("OrderCreated", defaultInput.path("detail-type").asText());
        assertEquals("o-42", defaultInput.path("detail").path("orderId").asText());
        assertEquals(SOURCE_REGION, defaultInput.path("region").asText());
        assertEquals("{\"mode\":\"explicit\"}", onlyExecution(explicitArn).getInput());
        assertEquals("{\"orderId\":\"o-42\",\"total\":19}", onlyExecution(pathArn).getInput());
        assertEquals("{\"order\":\"o-42\"}", onlyExecution(transformerArn).getInput());
    }

    @Test
    void startFailureDoesNotRejectAcceptedEvent() {
        String ruleName = createRule("missing-target", "{\"source\":[\"orders.failed\"]}");
        Target missingTarget = new Target(
                "missing",
                "arn:aws:states:eu-west-1:111122223333:stateMachine:missing",
                "{}",
                null);
        assertEquals(0, eventBridgeService.putTargets(
                ruleName, null, List.of(missingTarget), SOURCE_REGION));

        EventBridgeService.PutEventsResult result = eventBridgeService.putEvents(List.of(Map.of(
                "Source", "orders.failed",
                "DetailType", "OrderCreated",
                "Detail", "{}")), SOURCE_REGION);

        assertEquals(0, result.failedCount());
        assertNotNull(result.entries().getFirst().get("EventId"));
    }

    private String createStateMachine(String suffix) {
        String name = "eventbridge-" + suffix + "-" + UUID.randomUUID();
        StateMachine stateMachine = RequestScopes.callAs(TARGET_ACCOUNT,
                () -> stepFunctionsService.createStateMachine(
                        name, DEFINITION, ROLE_ARN, "STANDARD", TARGET_REGION, Map.of()));
        stateMachineArns.add(stateMachine.getStateMachineArn());
        return stateMachine.getStateMachineArn();
    }

    private String createRule(String suffix, String eventPattern) {
        String name = "eventbridge-sfn-" + suffix + "-" + UUID.randomUUID();
        eventBridgeService.putRule(
                name, null, eventPattern, null, RuleState.ENABLED, null, null, null, SOURCE_REGION);
        ruleNames.add(name);
        return name;
    }

    private List<Execution> executions(String stateMachineArn) {
        return RequestScopes.callAs(
                TARGET_ACCOUNT, () -> stepFunctionsService.listExecutions(stateMachineArn));
    }

    private Execution onlyExecution(String stateMachineArn) {
        List<Execution> executions = executions(stateMachineArn);
        assertEquals(1, executions.size());
        return executions.getFirst();
    }
}
