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

---

## Fork-specific additions

*This section exists only on this fork and is not carried on any feature branch. It records defect
classes that reached review here, so they get caught earlier next time.*

### Never treat the implementation as evidence of the spec

When judging whether an operation matches AWS, the code under review is the claim, never the source
of truth. If the AWS shape is not in front of you, call the operation unverifiable rather than
inferring the spec from what the code accepts.

This is not hypothetical. A reviewer shown only `if (!"name".equals(header.get(0)))` concluded that
AWS sends `name` — it sends `Name`, and the operation rejected every real AWS client. Reading the
implementation and reasoning backwards produces a confident, wrong "conforms".

Ground truth, in order: the Botocore service model for the operation; AWS documentation for formats
the model does not encode (CSV bodies, header conventions); then sibling operations already
implemented here.

### A silent 2xx is worse than an error

Flag any path where an unrecognised or malformed request shape returns success without acting.
Both of these shipped:

- `UpdateModel` / `UpdateUsagePlan` parsed a flat JSON body while AWS sends
  `{"patchOperations":[{op,path,value}]}`. The parse succeeded, matched nothing, and returned HTTP
  200 with the resource unchanged — an SDK caller sees success and no change.
- An `ImportApiKeys` CSV validator accepted only its own invented lowercase header.

### Validate before mutating — storage hands back live references

Storage backends return the stored object, not a copy. A method that mutates and then validates has
already applied the change when it throws, so the caller sees a failure response beside a mutated
resource. Check that every write path validates first.

Also check a written value can be read back: a field persisted without validation and later parsed
unguarded during serialisation will fail every subsequent read, not just the failing write, leaving
the resource unrecoverable without deletion.

### Tests must be able to fail

Coverage that passes with and without the fix advertises safety that does not exist. For a bug fix,
check the assertion actually depends on the changed behaviour — asserting only a status code or
exception type frequently does not, because an unrelated default path returns the same one.

### Account and region scoping

Storage keyed by name alone is silently clobbered when the same name is created in a second account.
Real Organizations and CDK deployments do this routinely. Check keys carry the owning account and
region.

### Documentation must describe the branch it ships on

Work here is split into per-scope branches, so documentation has repeatedly described behaviour
implemented elsewhere. Flag docs that describe a feature absent from the same diff, link to a
heading anchor that does not exist, or carry a `-` placeholder in a generated action table.

Flag duplicated content too — a service count or table row added alongside the original instead of
replacing it, which leaves two contradictory statements a line apart. This is a recurring merge
artifact rather than an authoring mistake.

### Attribution and naming

No AI attribution anywhere: no `Co-Authored-By` for tools, no generated-by footers, no assistant
session URLs in commit messages or PR bodies. No internal jargon in branch names, commit subjects or
PR titles — priority labels and process words end up in the public changelog.
