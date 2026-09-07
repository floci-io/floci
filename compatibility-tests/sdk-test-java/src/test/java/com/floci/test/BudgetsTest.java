package com.floci.test;

import org.junit.jupiter.api.Test;
import software.amazon.awssdk.services.budgets.BudgetsClient;
import software.amazon.awssdk.services.budgets.model.*;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class BudgetsTest {
    private static final String ACCOUNT_ID = "000000000000";

    @Test
    void completeBudgetLifecycle() {
        String budgetName = TestFixtures.uniqueName("budget");
        Notification notification = Notification.builder()
                .notificationType(NotificationType.ACTUAL)
                .comparisonOperator(ComparisonOperator.GREATER_THAN)
                .threshold(80d)
                .thresholdType(ThresholdType.PERCENTAGE)
                .build();
        Subscriber firstSubscriber = Subscriber.builder()
                .subscriptionType(SubscriptionType.EMAIL)
                .address("billing@example.com")
                .build();

        try (BudgetsClient client = TestFixtures.budgetsClient()) {
            client.createBudget(CreateBudgetRequest.builder()
                    .accountId(ACCOUNT_ID)
                    .budget(Budget.builder()
                            .budgetName(budgetName)
                            .budgetType(BudgetType.COST)
                            .timeUnit(TimeUnit.MONTHLY)
                            .budgetLimit(Spend.builder().amount(new java.math.BigDecimal("100.00")).unit("USD").build())
                            .build())
                    .notificationsWithSubscribers(NotificationWithSubscribers.builder()
                            .notification(notification)
                            .subscribers(firstSubscriber)
                            .build())
                    .resourceTags(ResourceTag.builder().key("managed_by").value("floci-test").build())
                    .build());

            assertThat(client.describeBudget(b -> b.accountId(ACCOUNT_ID).budgetName(budgetName)).budget().budgetName())
                    .isEqualTo(budgetName);
            assertThat(client.describeBudgets(b -> b.accountId(ACCOUNT_ID).maxResults(1000)).budgets())
                    .extracting(Budget::budgetName).contains(budgetName);
            assertThat(client.describeNotificationsForBudget(b -> b.accountId(ACCOUNT_ID).budgetName(budgetName)).notifications())
                    .hasSize(1);
            assertThat(client.describeSubscribersForNotification(b -> b.accountId(ACCOUNT_ID).budgetName(budgetName).notification(notification)).subscribers())
                    .hasSize(1);
            assertThat(client.describeBudgetNotificationsForAccount(b -> b.accountId(ACCOUNT_ID)).budgetNotificationsForAccount())
                    .anyMatch(item -> budgetName.equals(item.budgetName()));

            Subscriber second = Subscriber.builder().subscriptionType(SubscriptionType.EMAIL).address("ops@example.com").build();
            client.createSubscriber(b -> b.accountId(ACCOUNT_ID).budgetName(budgetName).notification(notification).subscriber(second));
            Subscriber replacement = Subscriber.builder().subscriptionType(SubscriptionType.EMAIL).address("finops@example.com").build();
            client.updateSubscriber(b -> b.accountId(ACCOUNT_ID).budgetName(budgetName).notification(notification)
                    .oldSubscriber(second).newSubscriber(replacement));
            client.deleteSubscriber(b -> b.accountId(ACCOUNT_ID).budgetName(budgetName).notification(notification).subscriber(replacement));

            Notification updatedNotification = notification.toBuilder().threshold(90d).build();
            client.updateNotification(b -> b.accountId(ACCOUNT_ID).budgetName(budgetName)
                    .oldNotification(notification).newNotification(updatedNotification));

            String budgetArn = "arn:aws:budgets::" + ACCOUNT_ID + ":budget/" + budgetName;
            client.tagResource(b -> b.resourceARN(budgetArn).resourceTags(ResourceTag.builder().key("team").value("platform").build()));
            assertThat(client.listTagsForResource(b -> b.resourceARN(budgetArn)).resourceTags())
                    .anyMatch(tag -> "team".equals(tag.key()));
            client.untagResource(b -> b.resourceARN(budgetArn).resourceTagKeys("team"));

            client.updateBudget(b -> b.accountId(ACCOUNT_ID).newBudget(Budget.builder()
                    .budgetName(budgetName).budgetType(BudgetType.COST).timeUnit(TimeUnit.MONTHLY)
                    .budgetLimit(Spend.builder().amount(new java.math.BigDecimal("150.00")).unit("USD").build()).build()));
            assertThat(client.describeBudgetPerformanceHistory(b -> b.accountId(ACCOUNT_ID).budgetName(budgetName)).budgetPerformanceHistory())
                    .isNotNull();

            CreateBudgetActionResponse createdAction = client.createBudgetAction(CreateBudgetActionRequest.builder()
                    .accountId(ACCOUNT_ID)
                    .budgetName(budgetName)
                    .notificationType(NotificationType.ACTUAL)
                    .actionType(ActionType.APPLY_IAM_POLICY)
                    .actionThreshold(ActionThreshold.builder().actionThresholdType(ThresholdType.PERCENTAGE).actionThresholdValue(100d).build())
                    .definition(Definition.builder().iamActionDefinition(IamActionDefinition.builder()
                            .policyArn("arn:aws:iam::aws:policy/ReadOnlyAccess").users("alice").build()).build())
                    .executionRoleArn("arn:aws:iam::" + ACCOUNT_ID + ":role/BudgetExecutionRole")
                    .approvalModel(ApprovalModel.MANUAL)
                    .subscribers(firstSubscriber)
                    .resourceTags(ResourceTag.builder().key("kind").value("guardrail").build())
                    .build());
            String actionId = createdAction.actionId();
            assertThat(actionId).isNotBlank();
            assertThat(client.describeBudgetAction(b -> b.accountId(ACCOUNT_ID).budgetName(budgetName).actionId(actionId)).action().actionId())
                    .isEqualTo(actionId);
            assertThat(client.describeBudgetActionsForAccount(b -> b.accountId(ACCOUNT_ID)).actions())
                    .extracting(Action::actionId).contains(actionId);
            assertThat(client.describeBudgetActionsForBudget(b -> b.accountId(ACCOUNT_ID).budgetName(budgetName)).actions())
                    .extracting(Action::actionId).contains(actionId);

            client.updateBudgetAction(UpdateBudgetActionRequest.builder()
                    .accountId(ACCOUNT_ID).budgetName(budgetName).actionId(actionId)
                    .approvalModel(ApprovalModel.AUTOMATIC).build());
            client.executeBudgetAction(b -> b.accountId(ACCOUNT_ID).budgetName(budgetName).actionId(actionId)
                    .executionType(ExecutionType.APPROVE_BUDGET_ACTION));
            Action executed = client.describeBudgetAction(b -> b.accountId(ACCOUNT_ID).budgetName(budgetName).actionId(actionId)).action();
            assertThat(executed.status()).isEqualTo(ActionStatus.EXECUTION_SUCCESS);
            List<ActionHistory> histories = client.describeBudgetActionHistories(
                    b -> b.accountId(ACCOUNT_ID).budgetName(budgetName).actionId(actionId)).actionHistories();
            assertThat(histories).isNotEmpty();
            assertThat(histories).anySatisfy(history -> {
                assertThat(history.eventType()).isEqualTo(EventType.EXECUTE_ACTION);
                assertThat(history.status()).isEqualTo(ActionStatus.EXECUTION_SUCCESS);
            });

            String actionArn = budgetArn + "/action/" + actionId;
            client.tagResource(b -> b.resourceARN(actionArn).resourceTags(ResourceTag.builder().key("action").value("yes").build()));
            assertThat(client.listTagsForResource(b -> b.resourceARN(actionArn)).resourceTags()).isNotEmpty();

            client.deleteBudgetAction(b -> b.accountId(ACCOUNT_ID).budgetName(budgetName).actionId(actionId));
            client.deleteNotification(b -> b.accountId(ACCOUNT_ID).budgetName(budgetName).notification(updatedNotification));
            client.deleteBudget(b -> b.accountId(ACCOUNT_ID).budgetName(budgetName));
        }
    }
    @Test
    void crossAccountBudgetAccessIsDenied() {
        String caller = "123456789012";
        String foreign = "210987654321";
        try (BudgetsClient client = TestFixtures.budgetsClient(caller)) {
            assertThatThrownBy(() -> client.describeBudget(b -> b.accountId(foreign).budgetName("foreign")))
                    .isInstanceOf(AccessDeniedException.class);
            assertThatThrownBy(() -> client.listTagsForResource(b ->
                    b.resourceARN("arn:aws:budgets::" + foreign + ":budget/foreign")))
                    .isInstanceOf(AccessDeniedException.class);
        }
    }

}
