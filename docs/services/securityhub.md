# AWS Security Hub

Floci implements the REST JSON organization and central-configuration surfaces used by local security-governance workflows.

## Supported behavior

The supported surface includes delegated administrator management, Security Hub enablement, finding aggregators, organization configuration, configuration policies, policy associations, and resource tag lookup. Delegation is visible in the delegated account so subsequent calls can be signed by that account exactly as they are against AWS.

Finding aggregators support `ALL_REGIONS`, `ALL_REGIONS_EXCEPT_SPECIFIED`, `SPECIFIED_REGIONS`, and `NO_REGIONS`. `GetFindingAggregator` accepts the complete finding-aggregator ARN carried in the greedy AWS REST path.

Central organization configuration models asynchronous convergence. Changing to `CENTRAL` first returns organization status `PENDING`; a subsequent poll converges to `ENABLED`. Configuration-policy association behaves the same way with `PENDING` followed by `SUCCESS`. Disassociation is also represented as a pending transition before the association disappears.

Configuration-policy tags are persisted and returned by `ListTagsForResource` rather than synthesized at read time.

## AWS-compatible failures

Floci validates administrator account IDs, finding-aggregator modes and Region lists, central-configuration input, policy names and documents, target identifiers, tags, and association state. Deterministic failures use the AWS-modeled errors, including `InvalidInputException`, `InvalidAccessException`, `ResourceNotFoundException`, `ResourceConflictException`, and `LimitExceededException`.

AWS also defines provider-side `InternalException` and rate-limit failures. Floci does not inject those failures without a request or local state condition that causes them.

See the [AWS Security Hub API Reference](https://docs.aws.amazon.com/securityhub/1.0/APIReference/Welcome.html).
