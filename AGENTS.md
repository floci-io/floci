Guidance for AI coding agents working in the Floci repository.

This file defines repository-specific operating rules for autonomous or semi-autonomous coding agents. Follow these instructions unless a maintainer explicitly tells you otherwise.

---

## Project Overview

Floci is a Java-based local AWS emulator built on Quarkus.

Its goal is full AWS SDK and AWS CLI compatibility through real AWS wire protocols, not convenience APIs or simplified abstractions.

Floci acts as an open-source alternative to LocalStack Community.

- Port: 4566
- Stack:
  - Java 25
  - Quarkus 3.32.3
  - JUnit 5
  - RestAssured
  - Jackson
  - Docker integrations for Lambda, RDS, and ElastiCache

---

## First Principles

When making changes, follow these priorities:

1. Preserve AWS protocol compatibility
2. Match AWS SDK and CLI behavior
3. Reuse existing Floci patterns
4. Prefer correctness over convenience
5. Keep changes narrow and testable

Critical rules:

- Do not introduce custom endpoint shapes
- Do not change request or response formats for convenience
- Do not perform broad refactors unless the task explicitly requires them
- Keep behavior aligned with AWS expectations and existing Floci conventions

---

## Architecture

Floci follows a layered design:

- **Controller / Handler**
  - Parses AWS protocol input
  - Produces AWS-compatible responses

- **Service**
  - Contains business logic
  - Throws `AwsException`

- **Model**
  - Domain objects

### Core Infrastructure

- `EmulatorConfig`
- `ServiceRegistry`
- `StorageBackend` + `StorageFactory`
- `AwsJson11Controller`
- `AwsQueryController`
- `AwsException` + `AwsExceptionMapper`
- `EmulatorLifecycle`

---

## Package Layout

- `io.github.hectorvent.floci.config`
- `io.github.hectorvent.floci.core.common`
- `io.github.hectorvent.floci.core.storage`
- `io.github.hectorvent.floci.lifecycle`
- `io.github.hectorvent.floci.services.<service>`

Typical service structure:

- `services/<svc>/`
  - `*Controller.java`
  - `*Service.java`
  - `model/`

Rule:
Copy an existing service pattern before introducing a new one.

---

## AWS Protocol Rules

Floci must implement real AWS wire protocols.

| Protocol | Services | Request Format | Response Format | Implementation |
|----------|----------|----------------|-----------------|----------------|
| Query | SQS, SNS, IAM, STS, RDS, ElastiCache, CloudFormation, CloudWatch Metrics | form-encoded POST + `Action` | XML | `AwsQueryController` |
| JSON 1.1 | SSM, EventBridge, CloudWatch Logs, Kinesis, KMS, Cognito, Secrets Manager, ACM | POST + `X-Amz-Target` | JSON | `AwsJson11Controller` |
| REST JSON | Lambda, API Gateway, SES V2 | REST paths | JSON | JAX-RS |
| REST XML | S3 | REST paths | XML | JAX-RS |
| TCP | ElastiCache, RDS | raw protocol | native | proxies |

### Important exceptions

- CloudWatch Metrics supports both Query and JSON 1.1; handlers must remain aligned
- SQS and SNS may expose multiple compatibility paths; do not let them drift
- Cognito well-known endpoints are OIDC REST JSON endpoints, not AWS management APIs
- Data-plane protocols may use raw TCP sockets
- Management APIs should be validated with AWS SDK clients, not only handcrafted HTTP requests

---

## XML / JSON Rules

- Use `XmlBuilder` for XML responses
- Use `XmlParser` for XML parsing; do not use regex
- Use `AwsNamespaces` constants
- JSON errors must follow AWS error structures
- Types returned directly from controllers must remain compatible with native-image reflection requirements

---

## Storage Rules

Supported storage modes:

- `memory`
- `persistent`
- `hybrid`
- `wal`

Rules:

- Always use `StorageFactory`
- Do not instantiate storage implementations directly inside services
- Respect lifecycle hooks for load and flush behavior

Important nuance:

Configuration interfaces may declare fallback defaults, but `application.yml` defines effective runtime behavior. Treat repository YAML as the source of truth unless a task explicitly changes configuration semantics.

When adding storage-related behavior:

1. Update `EmulatorConfig`
2. Update main `application.yml`
3. Update test `application.yml`
4. Wire through `StorageFactory`
5. Verify lifecycle integration

---

## Configuration Rules

Configuration lives under `floci.*`.

When adding config:

1. Add it to `EmulatorConfig`
2. Add it to main `application.yml`
3. Add it to test `application.yml` if needed
4. Update documentation if user-facing
5. Follow `FLOCI_*` environment variable conventions

Critical areas:

- `base-url`
- `hostname`
- region and account defaults
- port ranges
- persistence paths
- Docker networking

---

## Build & Run

    ./mvnw quarkus:dev
    ./mvnw test
    ./mvnw clean package
    ./mvnw clean package -DskipTests

### Focused tests

    ./mvnw test -Dtest=SsmIntegrationTest
    ./mvnw test -Dtest=SsmIntegrationTest#putParameter

---

## Compatibility Project

Compatibility test suite: `./compatibility-tests/`

Guidelines:

- Prefer AWS SDK clients over raw HTTP for management-plane validation
- Use this suite when changes may affect real SDK behavior

---

## Testing Rules

### Conventions

- Unit tests: `*ServiceTest.java`
- Integration tests: `*IntegrationTest.java`
- Prefer package-private constructors for testability
- Integration tests may use ordered execution when stateful behavior requires it

### Expectations

- Test any behavior affecting AWS compatibility
- Do not rely only on manual HTTP testing
- Prefer SDK-based validation where possible

### When touching protocol behavior

If a change affects request parsing, response shape, error handling, persistence semantics, URL generation, or service enablement:

1. Add or update automated tests
2. Prefer SDK-based verification where possible
3. Check compatibility across alternate protocol paths
4. Document intentional deviations clearly

---

## Error Handling

- Services should throw `AwsException`
- Query and REST XML flows should use `AwsExceptionMapper`
- JSON 1.1 flows should return structured AWS error responses where required
- Controller return types must remain reflection-safe

---

## Service Implementation Pattern

When adding functionality:

1. Identify the AWS protocol
2. Reuse an existing service pattern
3. Keep controllers thin
4. Use `AwsException` for domain errors
5. Reuse shared utilities
6. Update config, storage, docs, and tests together
7. Validate behavior against AWS SDK expectations

---

## Adding a New AWS Service

1. Create a package under `services/<svc>/` with a Controller, a Service, and `model/`
2. Add a `<Svc>ServiceConfig` interface and its accessor on `ServicesConfig` in `EmulatorConfig`
3. Add one `descriptor(...)` entry in `ResolvedServiceCatalog`. This is the registration point;
   `ServiceRegistry` only reads the catalog and has no registration API
4. Add `floci.services.<key>.enabled` to both `src/main/resources/application.yml` and
   `src/test/resources/application.yml`
5. JSON 1.1 only: inject the handler in `AwsJson11Controller`
6. Obtain storage through `StorageFactory` and implement `Resettable`
7. List any static `Random` or `SecureRandom` field under `--initialize-at-run-time` in
   `application.yml`
8. Add `<Svc>ServiceTest` and `<Svc>IntegrationTest`
9. Document it: `docs/services/<svc>.md`, a `mkdocs.yml` nav entry, a Service Matrix row in
   `docs/services/index.md`, and a row in the README category table
10. Register the handler in `tools/docs/services.yaml`, then run `make docs-sync` and
    `make docs-check`
11. Add a `TestFixtures` client factory and a `<Svc>Test` in `compatibility-tests/sdk-test-java`

---

## Adding a CloudFormation Resource Type

**Do not add cases to `CloudFormationResourceProvisioner`.** That class is a legacy
monolith being dismantled; new types go in per-service provisioners under
`services/cloudformation/provisioners/`.

1. Add the type to the existing `<Service>CfnProvisioner`, or create one:
   `@ApplicationScoped`, injecting only the service it wraps. CDI discovery via
   `CloudFormationResourceRegistry` handles registration: no manual wiring, but a
   missing `@ApplicationScoped` silently means the type is never provisioned.
2. `resourceTypes()` lists the `AWS::*` types; `provision(resource, props, ctx)`
   does the work, switching on `resource.getResourceType()` when it serves several.
3. Set **both** reference mechanisms. They are separate:
   - `resource.setPhysicalId(...)` backs `Ref`
   - `resource.getAttributes().put(...)` backs `Fn::GetAtt`, one entry per attribute
   Omitting an attribute does not fail; `Fn::GetAtt` resolves to the literal
   `"LogicalId.Attr"`. Source the attribute names from the type's registry schema in
   `local/aws/cfn-resource-schemas/us-east-1/` (`readOnlyProperties`), and validate
   `required` from the same file.
4. **`provision` serves create *and* update.** On `UpdateStack` it is re-invoked with
   the prior physical id and attributes already populated on the resource. Branch with
   `ctx.isUpdate()` / `ctx.priorPhysicalId()`, not by reading the id off the resource:
   `provision` assigns the new id as it runs, so a resource-derived check flips
   mid-method.
5. Override `delete(...)` when the type has a backing delete; tolerate already-deleted
   via `CfnDeletes.safeDelete`, passing the specific "already gone" error codes. Never
   a catch-all: a real failure such as `BucketNotEmpty` must propagate so the stack
   reports `DELETE_FAILED`. When the delete needs a create-time attribute rather than
   just the physical id, override `delete(StackResource, String)`.
6. **Register in `src/test/resources/cloudformation/supported-resource-types.tsv`**
   (`type<TAB>Owner`). `CfnResourceInventoryTest` diffs that file against the
   CDI-resolved registry, so it also catches a missing `@ApplicationScoped`.
7. **Add the provisioner to `CfnProvisionerFixture.inferredProvisioners()`** when it
   takes a single service, or a fixture test naming that service silently falls through
   to the stub arm.
8. Tests: focused unit test mocking one service (`SqsCfnProvisioner`'s test is the
   pattern) plus an integration test asserting the **exact `Fn::GetAtt` keys**. An
   unmapped type is stubbed as `CREATE_COMPLETE` with a fake ARN, so asserting status
   alone cannot detect a type that was never wired. Note the engine's constructor is
   package-private, so tests in `provisioners/` mock it.
9. Run `make docs-sync` and commit the result. The resource-type table in
   `docs/services/cloudformation.md` is **generated** from the step-6 inventory; hand
   edits fail `docs-check`. Labels, ordering and notes live in
   `tools/docs/cfn_resource_types.yaml`.
10. A schema `readOnlyProperties` entry you cannot set goes in
    `src/test/resources/cloudformation/getatt-attribute-gaps.tsv` with a reason;
    `CfnSchemaCoverageTest` requires every unset attribute to be fixed or recorded.

References: `SqsCfnProvisioner` (smallest), `Ec2LaunchTemplateCfnProvisioner`
(update-in-place and replacement), `LogsCfnProvisioner` (reconcile-vs-replace update).

---

## Code Style

- Use constructor injection
- Prefer self-explanatory code over comments
- Avoid unnecessary comments
- Always use braces in conditionals
- Never leave a `catch` block empty. If an exception is intentionally tolerated, log it with enough context to diagnose it later.
- Follow existing project patterns
- Use modern Java features only when they improve clarity

---

## Documentation Style

- No em-dashes anywhere, in any content. Use colons, commas, or periods.

## Logging

- Use JBoss Logging
- Keep logs structured
- Avoid noisy logs in hot paths

---

## Pull Request Guidelines

- Keep changes focused
- Avoid unrelated refactors
- Preserve behavior unless the task explicitly requires change
- Update docs when necessary
- Explain missing tests when behavior changed but no automated coverage was added

Conventional commits:

- `feat:`
- `fix:`
- `perf:`
- `docs:`
- `chore:`

Do not add `Co-Authored-By` trailers for AI tools in commit messages. Keep attribution limited to human contributors.

---

## Release Awareness

- Changes merged into `main` do not automatically imply a stable release
- Releases are cut from `main` via the "Release Cut" workflow (`workflow_dispatch`
  on `.github/workflows/release-cut.yml`), which runs semantic-release: it bumps
  `pom.xml`, writes `CHANGELOG.md`, commits, tags, and creates the GitHub Release
- `release/x.y.x` branches are retired for now
- Tags still trigger the publishing workflows (`release.yml`)

Treat release workflows as critical infrastructure.

---

## Agent Workflow

### Before editing

1. Identify service and protocol
2. Locate an existing implementation to mirror
3. Check config impact
4. Check storage impact
5. Check documentation impact
6. Define the minimal useful test plan

### Before finishing

1. Run relevant tests
2. Validate protocol behavior
3. Ensure no custom endpoints were introduced
4. Verify config and docs updates

---

## Common Mistakes

- Creating non-AWS endpoints
- Bypassing `StorageFactory`
- Changing wire formats without tests
- Forgetting YAML updates
- Producing inconsistent URLs or ARNs
- Testing only with raw HTTP
- Introducing unnecessary new patterns
- Adding a CloudFormation type to `CloudFormationResourceProvisioner` instead of a
  per-service provisioner
- Setting a CloudFormation resource's physical id but not its `Fn::GetAtt`
  attributes (they are two separate mechanisms, and the miss is silent)
- Hand-editing the resource-type table in `docs/services/cloudformation.md`, which is
  generated, run `make docs-sync` instead
- Adding a CloudFormation provisioner without a row in
  `supported-resource-types.tsv` or an entry in `CfnProvisionerFixture`, either of
  which leaves a type quietly served by the stub arm

---

## Human Handoff

If behavior is unclear:

1. Prefer AWS behavior
2. Then existing Floci behavior
3. Then compatibility test expectations

If a task would require broad architectural changes, stop and surface the tradeoffs instead of refactoring across services blindly.
