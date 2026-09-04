# AWS Budgets

**Protocol:** JSON 1.1 (`X-Amz-Target: AWSBudgetServiceGateway.*`)

Floci implements the budget lifecycle used by local cost-governance workflows.

## Supported operations

`DescribeBudget`, `CreateBudget`, `UpdateBudget`, `DeleteBudget`, `ListTagsForResource`, `DescribeNotificationsForBudget`, `DescribeSubscribersForNotification`, `CreateNotification`, and `DeleteNotification`.

Budget, notification, subscriber, and tag state is stored locally and isolated by account.

## AWS-compatible failures

Budget names, limits, time units, budget types, tags, notification thresholds, subscribers, duplicate records, and local creation limits are validated. Deterministic failures use the modeled errors such as `InvalidParameterException`, `NotFoundException`, `DuplicateRecordException`, `CreationLimitExceededException`, and `ServiceQuotaExceededException`.

AWS also models failures including `AccessDeniedException`, `InternalErrorException`, `ThrottlingException`, and billing-view health failures. Floci does not manufacture provider-side failures that have no local cause.

See the [AWS Budgets API Reference](https://docs.aws.amazon.com/aws-cost-management/latest/APIReference/API_Operations_AWS_Budgets.html).
