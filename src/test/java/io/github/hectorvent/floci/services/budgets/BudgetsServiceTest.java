package io.github.hectorvent.floci.services.budgets;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.core.storage.AccountAwareStorageBackend;
import io.github.hectorvent.floci.services.budgets.model.BudgetActionRecord;
import io.github.hectorvent.floci.services.budgets.model.BudgetRecord;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BudgetsServiceTest {
    private static final String ACCOUNT = "123456789012";
    private final ObjectMapper mapper = new ObjectMapper();
    private BudgetsService service;

    @BeforeEach
    void setUp() {
        service = new BudgetsService(AccountAwareStorageBackend.inMemory(ACCOUNT),
                AccountAwareStorageBackend.<BudgetActionRecord>inMemory(ACCOUNT), mapper);
    }

    @Test
    void describeBudgetsUsesAwsThousandItemLimitAndResetClearsState() {
        service.createBudget(createBudget("platform"));
        ObjectNode list = mapper.createObjectNode().put("AccountId", ACCOUNT).put("MaxResults", 1000);
        assertEquals(1, service.describeBudgets(list).items().size());
        list.put("MaxResults", 1001);
        assertError("InvalidParameterException", () -> service.describeBudgets(list));
        service.clear();
        list.put("MaxResults", 1000);
        assertTrue(service.describeBudgets(list).items().isEmpty());
    }

    @Test
    void subscriberAndNotificationUpdatesPreserveAwsLifecycle() {
        service.createBudget(createBudget("notify"));
        ObjectNode create = notificationRequest("notify", 80);
        create.putArray("Subscribers").addObject().put("SubscriptionType", "EMAIL").put("Address", "first@example.com");
        service.createNotification(create);

        ObjectNode subscriber = notificationRequest("notify", 80);
        subscriber.set("Subscriber", mapper.createObjectNode().put("SubscriptionType", "EMAIL").put("Address", "second@example.com"));
        service.createSubscriber(subscriber);
        assertEquals(2, service.describeSubscribers(notificationRequest("notify", 80)).items().size());

        ObjectNode update = mapper.createObjectNode().put("AccountId", ACCOUNT).put("BudgetName", "notify");
        update.set("OldNotification", notification(80));
        update.set("NewNotification", notification(90));
        service.updateNotification(update);
        assertEquals(1, service.describeNotifications(update).items().size());
    }


    @Test
    void callerScopeRejectsCrossAccountIdsAndArns() {
        ObjectNode request = mapper.createObjectNode().put("AccountId", "210987654321");
        assertError("AccessDeniedException", () -> service.validateCallerScope(request, ACCOUNT));

        ObjectNode arnRequest = mapper.createObjectNode()
                .put("ResourceARN", "arn:aws:budgets::210987654321:budget/foreign");
        assertError("AccessDeniedException", () -> service.validateCallerScope(arnRequest, ACCOUNT));
    }

    @Test
    void budgetActionDefinitionsMustMatchTypeAndContainRequiredFields() {
        service.createBudget(createBudget("actions"));

        ObjectNode emptyIam = actionRequest("actions", "APPLY_IAM_POLICY");
        emptyIam.set("Definition", mapper.createObjectNode().set("IamActionDefinition", mapper.createObjectNode()));
        assertError("InvalidParameterException", () -> service.createBudgetAction(emptyIam));

        ObjectNode noIamTarget = actionRequest("actions", "APPLY_IAM_POLICY");
        noIamTarget.set("Definition", mapper.createObjectNode().set("IamActionDefinition",
                mapper.createObjectNode().put("PolicyArn", "arn:aws:iam::aws:policy/ReadOnlyAccess")));
        assertError("InvalidParameterException", () -> service.createBudgetAction(noIamTarget));

        ObjectNode wrongVariant = actionRequest("actions", "APPLY_IAM_POLICY");
        ObjectNode ssm = mapper.createObjectNode().put("ActionSubType", "STOP_EC2_INSTANCES").put("Region", "us-east-1");
        ssm.putArray("InstanceIds").add("i-12345678");
        wrongVariant.set("Definition", mapper.createObjectNode().set("SsmActionDefinition", ssm));
        assertError("InvalidParameterException", () -> service.createBudgetAction(wrongVariant));

        BudgetActionRecord record = service.createBudgetAction(actionRequest("actions", "APPLY_IAM_POLICY"));
        ObjectNode update = mapper.createObjectNode().put("AccountId", ACCOUNT).put("BudgetName", "actions")
                .put("ActionId", record.getActionId());
        update.set("Definition", mapper.createObjectNode().set("SsmActionDefinition", ssm));
        assertError("InvalidParameterException", () -> service.updateBudgetAction(update));
    }

    @Test
    void actionExecutionUsesAwsStatusAndHistoryEnums() {
        service.createBudget(createBudget("execute"));
        BudgetActionRecord record = service.createBudgetAction(actionRequest("execute", "APPLY_IAM_POLICY"));

        ObjectNode execute = mapper.createObjectNode().put("AccountId", ACCOUNT).put("BudgetName", "execute")
                .put("ActionId", record.getActionId()).put("ExecutionType", "APPROVE_BUDGET_ACTION");
        BudgetActionRecord executed = service.executeBudgetAction(execute);

        assertEquals("EXECUTION_SUCCESS", executed.getAction().path("Status").asText());
        JsonNode history = executed.getHistories().get(executed.getHistories().size() - 1);
        assertEquals("EXECUTE_ACTION", history.path("EventType").asText());
        assertEquals("EXECUTION_SUCCESS", history.path("Status").asText());
    }

    private ObjectNode createBudget(String name) {
        ObjectNode request = mapper.createObjectNode().put("AccountId", ACCOUNT);
        ObjectNode budget = request.putObject("Budget");
        budget.put("BudgetName", name).put("BudgetType", "COST").put("TimeUnit", "MONTHLY");
        budget.putObject("BudgetLimit").put("Amount", "100").put("Unit", "USD");
        return request;
    }


    private ObjectNode actionRequest(String budgetName, String actionType) {
        ObjectNode request = mapper.createObjectNode().put("AccountId", ACCOUNT).put("BudgetName", budgetName)
                .put("NotificationType", "ACTUAL").put("ActionType", actionType).put("ApprovalModel", "MANUAL")
                .put("ExecutionRoleArn", "arn:aws:iam::" + ACCOUNT + ":role/BudgetExecutionRole");
        request.putObject("ActionThreshold").put("ActionThresholdType", "PERCENTAGE").put("ActionThresholdValue", 100);
        ObjectNode iam = mapper.createObjectNode().put("PolicyArn", "arn:aws:iam::aws:policy/ReadOnlyAccess");
        iam.putArray("Users").add("alice");
        request.set("Definition", mapper.createObjectNode().set("IamActionDefinition", iam));
        request.putArray("Subscribers").addObject().put("SubscriptionType", "EMAIL").put("Address", "ops@example.com");
        return request;
    }

    private ObjectNode notificationRequest(String name, double threshold) {
        ObjectNode request = mapper.createObjectNode().put("AccountId", ACCOUNT).put("BudgetName", name);
        request.set("Notification", notification(threshold));
        return request;
    }

    private ObjectNode notification(double threshold) {
        return mapper.createObjectNode().put("NotificationType", "ACTUAL")
                .put("ComparisonOperator", "GREATER_THAN").put("Threshold", threshold).put("ThresholdType", "PERCENTAGE");
    }

    private static void assertError(String code, Runnable operation) {
        AwsException error = assertThrows(AwsException.class, operation::run);
        assertEquals(code, error.getErrorCode());
    }
}
