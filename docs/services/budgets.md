# AWS Budgets

**Protocol:** JSON 1.1 (`X-Amz-Target: AWSBudgetServiceGateway.*`)

**Signing name:** `budgets`

Floci emulates the complete AWS Budgets management API surface, including budgets, notifications, subscribers, budget actions, action history, performance history, and resource tags. State is persisted locally and isolated by AWS account.

## Supported Actions

<!-- floci:actions:start -->
| Action | Description |
| --- | --- |
| `CreateBudget` | - |
| `CreateBudgetAction` | - |
| `CreateNotification` | - |
| `CreateSubscriber` | - |
| `DeleteBudget` | - |
| `DeleteBudgetAction` | - |
| `DeleteNotification` | - |
| `DeleteSubscriber` | - |
| `DescribeBudget` | - |
| `DescribeBudgetAction` | - |
| `DescribeBudgetActionHistories` | - |
| `DescribeBudgetActionsForAccount` | - |
| `DescribeBudgetActionsForBudget` | - |
| `DescribeBudgetNotificationsForAccount` | - |
| `DescribeBudgetPerformanceHistory` | - |
| `DescribeBudgets` | - |
| `DescribeNotificationsForBudget` | - |
| `DescribeSubscribersForNotification` | - |
| `ExecuteBudgetAction` | - |
| `ListTagsForResource` | - |
| `TagResource` | - |
| `UntagResource` | - |
| `UpdateBudget` | - |
| `UpdateBudgetAction` | - |
| `UpdateNotification` | - |
| `UpdateSubscriber` | - |
<!-- floci:actions:end -->

## Behavior

Budgets support the AWS budget types and time units, fixed or planned limits, legacy or expression-based filters, notifications and subscribers, and account-scoped listing. Budget actions support IAM, SCP, and SSM definition shapes, approval models, execution status transitions, history, and tags.

Pagination uses the operation-specific AWS limits. `DescribeBudgets` and `DescribeBudgetNotificationsForAccount` allow up to 1000 results, while notification, subscriber, action, action-history, and performance-history listings use their documented lower limits.

`DescribeBudgetPerformanceHistory` returns deterministic local history metadata. Floci does not manufacture provider billing usage, forecasts, or provider-side failures that do not arise from local state.

## AWS-compatible failures

Floci validates account IDs, budget names, budget/filter unions, tags, notification thresholds, subscriber quotas, action definitions, role ownership, action identifiers, and pagination. Deterministic failures use modeled AWS errors such as `InvalidParameterException`, `NotFoundException`, `DuplicateRecordException`, `CreationLimitExceededException`, `ServiceQuotaExceededException`, and `InvalidNextTokenException`.

See the [AWS Budgets API Reference](https://docs.aws.amazon.com/aws-cost-management/latest/APIReference/API_Operations_AWS_Budgets.html).

## Configuration

| Variable | Default | Description |
|---|---|---|
| `FLOCI_SERVICES_BUDGETS_ENABLED` | `true` | Enable or disable AWS Budgets |
