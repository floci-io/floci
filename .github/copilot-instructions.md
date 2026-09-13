# Copilot Instructions for Pull Request Review

Review pull requests in the Floci repository with AWS compatibility as the primary concern.

Floci is a Java-based local AWS emulator built on Quarkus. Its goal is to match AWS SDK and AWS CLI behavior through real AWS wire protocols, not convenience APIs or custom abstractions.

## Review Priorities

Evaluate changes in this order:

1. Preserve AWS protocol compatibility
2. Match AWS SDK and AWS CLI behavior
3. Reuse existing Floci patterns
4. Prefer correctness over convenience
5. Keep changes focused and testable

## What to Flag

Raise concerns when a PR introduces any of the following without strong justification:

- Non-AWS endpoint shapes
- Request or response format changes made for convenience
- Broad refactors unrelated to the PR goal
- New service patterns where an existing Floci pattern should be reused
- Direct storage implementation usage instead of `StorageFactory`
- `var` where an explicit type belongs. Floci reproduces AWS wire contracts, so the
  concrete type at a call site is usually what a reviewer needs to see. The one
  exception is a record deconstruction pattern.
- Fully-qualified class names written inline instead of imported. Flag
  `new java.util.ArrayList<>()`. Do not flag it when the file has a genuine name
  collision, such as `apigateway` versus `apigatewayv2` model types, CDI `Instance`
  versus the EC2 model `Instance`, or a service `Record` versus `java.lang.Record`.
- Wildcard imports in `src/main`. Static wildcards in tests are fine.
- An empty `catch` block. A tolerated exception is logged, or named `ignored` or
  `expected` with a comment saying why swallowing is safe.

## Architecture Expectations

Floci follows a layered design:

- Controllers / handlers parse AWS protocol input and produce AWS-compatible responses
- Services contain business logic and should throw `AwsException`
- Models hold domain data

Core infrastructure commonly relevant in reviews:

- `EmulatorConfig`
- `ServiceRegistry`
- `StorageFactory`
- `AwsQueryController`
- `AwsJson11Controller`
- `AwsException`
- `AwsExceptionMapper`
- `EmulatorLifecycle`

Check that controllers stay thin, business logic remains in services, and new changes fit existing repository patterns.

## Protocol Review Rules

Floci implements real AWS wire protocols. Review protocol-affecting changes carefully.

- Query services should keep form-encoded POST requests with `Action` and XML responses
- JSON 1.1 services should keep `X-Amz-Target` requests and AWS-style JSON responses
- REST JSON and REST XML services should stay aligned with AWS path and payload conventions
- TCP-based services should not drift into HTTP-style abstractions

Pay extra attention to these cases:

- CloudWatch Metrics supports both Query and JSON 1.1 and both paths must stay aligned
- SQS and SNS may have multiple compatibility paths that must not drift
- Cognito well-known endpoints are OIDC REST JSON endpoints, not AWS management APIs
- Management APIs should ideally be validated with AWS SDK clients, not only handcrafted HTTP

## XML and JSON Rules

Flag PRs that:

- Ignore `AwsNamespaces` constants
- Return JSON errors that do not follow AWS error structures
- Change controller return types in ways that may break reflection or native-image compatibility

## Config and Storage Review

When a PR changes configuration or persistence behavior, verify the change is wired consistently.

Check for updates to:

- `EmulatorConfig`
- main `application.yml`
- test `application.yml`
- `StorageFactory`
- lifecycle hooks when relevant

Supported storage modes include:

- `memory`
- `persistent`
- `hybrid`
- `wal`

Treat repository YAML as the source of truth for runtime behavior unless the PR explicitly changes configuration semantics.

## Testing Expectations

Expect automated coverage for changes that affect:

- request parsing
- response shape
- error handling
- persistence semantics
- URL generation
- service enablement

Prefer:

- AWS SDK-based validation over raw HTTP-only testing
- integration tests for compatibility-sensitive behavior
- existing naming conventions such as `*ServiceTest.java` and `*IntegrationTest.java`

If behavior changes without automated coverage, call that out explicitly.

## Review Checklist

When analyzing a PR, check:

- Is the change focused?
- Does it preserve AWS-compatible wire behavior?
- Does it reuse an existing Floci pattern?
- Are controllers thin and services responsible for domain logic?
- Are `AwsException` and existing error-mapping patterns used correctly?
- Are config and YAML updates complete?
- Are storage changes wired through `StorageFactory`?
- Are tests added or updated where compatibility is affected?
- Are docs updated when user-facing behavior changes?
- Does new code follow the AGENTS.md Code Style rules: explicit types over `var`,
  imported classes over inline fully-qualified names, no wildcard imports in
  `src/main`, braces in conditionals, constructor injection?

## How to Write Feedback

Write review comments that are:

- specific
- repository-aware
- grounded in AWS compatibility risk

Use severity when helpful:

- `high`: likely breaks AWS SDK / CLI compatibility or protocol behavior
- `medium`: inconsistent with Floci architecture, wiring, or testing expectations
- `low`: maintainability, clarity, or minor convention issue

Prefer comments that explain:

- what is risky
- why it matters in Floci
- which existing pattern should be followed instead

## If Behavior Is Unclear

Use this fallback order:

1. Prefer AWS behavior
2. Then existing Floci behavior
3. Then compatibility test expectations

If correctness would require a broader architectural change, call out the tradeoff instead of suggesting blind refactoring.
