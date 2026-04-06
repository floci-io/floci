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
| REST JSON | Lambda, API Gateway | REST paths | JSON | JAX-RS |
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

### Manual testing

    ./test-services.sh

---

## Compatibility Project

Compatibility test suite:
`./compatibility-tests/`

Guidelines:

- Prefer AWS SDK clients over raw HTTP for management-plane validation
- Use this suite when changes may affect real SDK behavior
- If the suite is unavailable locally, state that limitation explicitly

Default module:

- `sdk-test-java`

Use `docker-compose-test.yml` for container-based testing.

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

1. Create a package under `services/`
2. Add:
   - Controller
   - Service
   - `model/`
3. Register the service in `ServiceRegistry`
4. Add config to `EmulatorConfig`
5. Add YAML config in main and test config files
6. Wire storage through `StorageFactory`
7. Add tests
8. Update documentation

---

## Code Style

- Use constructor injection
- Prefer self-explanatory code over comments
- Avoid unnecessary comments
- Always use braces in conditionals
- Follow existing project patterns
- Use modern Java features only when they improve clarity

---

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
- Release branches define stable release lines
- Tags trigger publishing workflows

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

---

## Human Handoff

If behavior is unclear:

1. Prefer AWS behavior
2. Then existing Floci behavior
3. Then compatibility test expectations

If a task would require broad architectural changes, stop and surface the tradeoffs instead of refactoring across services blindly.
