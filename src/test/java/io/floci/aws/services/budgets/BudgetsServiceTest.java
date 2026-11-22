package io.floci.aws.services.budgets;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.floci.aws.core.common.AwsException;
import io.floci.aws.core.common.RegionResolver;
import io.floci.aws.core.storage.AccountAwareStorageBackend;
import io.floci.aws.core.storage.InMemoryStorage;
import io.floci.aws.services.budgets.model.BudgetActionRecord;
import io.floci.aws.services.budgets.model.BudgetRecord;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.Set;

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
                AccountAwareStorageBackend.<BudgetActionRecord>inMemory(ACCOUNT), mapper,
                new RegionResolver("us-east-1", ACCOUNT));
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

    /**
     * IAM's lookup reads the tags of the budget or the action the ARN names, as the tag calls leave them, and nothing
     * for another account's, another partition's, a Regional or an unknown ARN.
     */
    @Test
    void storedTagsAreTheTagsOfTheBudgetOrTheActionTheArnNames() {
        ObjectNode create = createBudget("tagged");
        create.putArray("ResourceTags").addObject().put("Key", "team").put("Value", "web");
        service.createBudget(create);
        String arn = "arn:aws:budgets::" + ACCOUNT + ":budget/tagged";
        ObjectNode tag = mapper.createObjectNode().put("ResourceARN", arn);
        tag.putArray("ResourceTags").add(mapper.createObjectNode().put("Key", "team").put("Value", "data"))
                .add(mapper.createObjectNode().put("Key", "env").put("Value", "dev"));
        service.tagResource(tag);
        ObjectNode untag = mapper.createObjectNode().put("ResourceARN", arn);
        untag.putArray("ResourceTagKeys").add("env");
        service.untagResource(untag);
        ObjectNode action = actionRequest("tagged", "APPLY_IAM_POLICY");
        action.putArray("ResourceTags").addObject().put("Key", "team").put("Value", "ops");
        String actionArn = arn + "/action/" + service.createBudgetAction(action).getActionId();

        assertEquals("budgets", service.namespace());
        assertEquals(Map.of("team", "data"), service.storedTags(arn));
        assertEquals(Map.of("team", "ops"), service.storedTags(actionArn));
        assertTrue(service.storedTags(arn.replace(ACCOUNT, "210987654321")).isEmpty());
        assertTrue(service.storedTags(arn.replace("arn:aws:", "arn:aws-cn:")).isEmpty());
        assertTrue(service.storedTags(arn.replace("budgets::", "budgets:us-east-1:")).isEmpty());
        assertTrue(service.storedTags(arn.replace("budget/tagged", "budget/missing")).isEmpty());
        assertTrue(service.storedTags("arn:aws:ce::" + ACCOUNT + ":anomalymonitor/tagged").isEmpty());
    }

    /** A budget stored before storage was account-aware is read in place: the lookup migrates nothing. */
    @Test
    void storedTagsReadAPreAccountAwareBudgetInPlace() {
        InMemoryStorage<String, BudgetRecord> raw = new InMemoryStorage<>();
        BudgetsService legacyState = new BudgetsService(new AccountAwareStorageBackend<>(raw, null, ACCOUNT),
                AccountAwareStorageBackend.<BudgetActionRecord>inMemory(ACCOUNT), mapper,
                new RegionResolver("us-east-1", ACCOUNT));
        BudgetRecord legacy = new BudgetRecord();
        legacy.setAccountId(ACCOUNT);
        legacy.setBudget(createBudget("legacy").get("Budget"));
        legacy.setResourceTags(mapper.createArrayNode().add(mapper.createObjectNode().put("Key", "team")
                .put("Value", "data")));
        raw.put(ACCOUNT + "::legacy", legacy);

        assertEquals(Map.of("team", "data"), legacyState.storedTags("arn:aws:budgets::" + ACCOUNT + ":budget/legacy"));
        assertEquals(Set.of(ACCOUNT + "::legacy"), raw.keys());
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
