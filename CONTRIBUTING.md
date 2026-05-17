# Contributing to Floci

Thank you for your interest in contributing! Floci is a community-driven project and all contributions are welcome.

## Ways to Contribute

- **Bug reports** — open an issue with a minimal reproduction
- **Feature requests** — open an issue describing the AWS behavior you need
- **Pull requests** — bug fixes, new service implementations, or improvements
- **Compatibility tests** — add cases to `./compatibility-tests/`

## Getting Started

### Prerequisites

- Java 25+
- Maven 3.9+
- Docker (for integration tests that spin up Lambda/RDS/ElastiCache)

Any Java 25+ distribution will work. If you need to install it, [SDKMAN](https://sdkman.io/) is a convenient option:

```bash
curl -s "https://get.sdkman.io" | bash
source "$HOME/.sdkman/bin/sdkman-init.sh"
sdk install java 25-open
```

### Build & Run

This project includes a Maven wrapper, so you don't need to install Maven separately:

```bash
git clone https://github.com/floci-io/floci.git
cd floci
./mvnw quarkus:dev     # hot reload on port 4566
```

If you prefer to use your own Maven installation (3.9+), you can use `mvn` instead of `./mvnw`.

### Run Tests

```bash
./mvnw test                                          # all tests
./mvnw test -Dtest=SsmIntegrationTest                # single class
./mvnw test -Dtest=SsmIntegrationTest#putParameter   # single method
```

## Branching Model

Floci uses a **tag-driven release model**. Docker images are never published on PR merge — only when a maintainer pushes a version tag.

| Branch | Purpose | Docker published? |
|---|---|---|
| `main` | Integration branch — all PRs merge here. Treated as unstable/nightly. | No (CI tests only) |
| `release/x.y.x` | Stable line for a minor version. Receives cherry-picked fixes from `main`. | No (CI tests only) |
| `X.Y.Z` tag | Signals a production release. Triggers the full Docker publish pipeline. | Yes (`x.y.z`, `latest`, `x.y.z-jvm`, `latest-jvm`) |

## Commit Message Format

This project uses [Conventional Commits](https://www.conventionalcommits.org/) — semantic-release reads these to generate the changelog and version bumps automatically.

> **The PR title is validated automatically by CI** and must follow this format, since it becomes the squash-merge commit message that semantic-release reads.

### Format

```
<type>[optional scope]: <description>
```

- **type** — one of the values in the table below (lowercase)
- **scope** — optional, in parentheses, identifies the service or area (e.g. `s3`, `dynamodb`, `core`)
- **description** — short summary in the imperative mood, no trailing period
- Append `!` before the colon to signal a breaking change: `feat(api)!:`

| Type | When to use | Version bump |
|------|-------------|--------------|
| `feat` | New AWS API action or service | minor |
| `fix` | Bug fix or AWS compatibility correction | patch |
| `perf` | Performance improvement | patch |
| `revert` | Reverts a previous commit | patch |
| `docs` | Documentation only | none |
| `style` | Formatting, whitespace — no logic change | none |
| `chore` | Build, CI, dependencies, housekeeping | none |
| `refactor` | Code restructure without behavior change | none |
| `test` | Adding or updating tests | none |
| `build` | Build system or tooling changes | none |
| `ci` | CI workflow changes | none |
| `BREAKING CHANGE` | Footer or `!` suffix — incompatible change | major |

### Valid examples ✅

```
feat(dynamodb): add PartiQL ExecuteStatement support
fix(s3): make us-east-1 bucket creation idempotent
perf(kinesis): reduce lock contention in shard iterator
chore: release 1.5.16
docs: update README with new configuration options
refactor(sqs): extract message visibility logic
test(kms): add encrypt/decrypt round-trip test
feat!: remove legacy v1 endpoint
fix(dynamodb)!: correct TransactWriteItems error shape
ci: add conventional commits lint workflow
build: bump Quarkus to 3.32.3
```

### Invalid examples ❌

```
Add PartiQL support                  # missing type
Feature: add something               # "Feature" is not a valid type
feat : space before colon            # space before colon
feat(dynamodb)add missing colon      # missing colon
FIX(s3): uppercase type              # type must be lowercase
feat(my scope): scope has spaces     # scope cannot contain spaces
fix(): empty scope                   # empty scope
feat(s3):no space after colon        # missing space after colon
wip: still working on this          # "wip" is not a recognised type
```

Do not include `Co-Authored-By` trailers for AI tools in commit messages. Attribution should be limited to human contributors.

## Architecture

See [AGENT.md](AGENT.md) for a detailed description of the three-layer architecture (Controller → Service → Storage), the AWS wire protocol mapping, and conventions for adding new services.

`AGENT.md` is the canonical agent instructions file for this repository. If your coding agent expects a different filename, create a local symlink to `AGENT.md` instead of copying the file.

```bash
ln -s AGENT.md CLAUDE.md
ln -s AGENT.md GEMINI.md
ln -s AGENT.md COPILOT.md
```

## Adding a New AWS Service

1. Create a package under `src/main/java/.../services/<service>/`
2. Add a Controller (follow the correct protocol — Query, JSON 1.1, REST JSON, or REST XML)
3. Add a Service (`@ApplicationScoped`) and model POJOs
4. Add config entries in `EmulatorConfig.java` and `application.yml`
5. Register a `ServiceDescriptor` in `ResolvedServiceCatalog`
6. Wire controller/handler dispatch for the service
7. Add integration tests in `*IntegrationTest.java`

`ServiceRegistry`, `ServiceEnabledFilter`, and `StorageFactory` now resolve service metadata from the descriptor catalog. Adding a service should not require new service-keyed switch statements in those consumers.

Always implement the **real AWS wire protocol** — never invent custom endpoints. The AWS SDK must work against Floci without modification.

## Pull Request Guidelines

1. Branch off `main`: `git checkout -b feature/my-feature`
2. Open a PR targeting `main`.
3. CI runs tests automatically — all checks must pass before merge.
4. Keep PRs focused — one feature or fix per PR.
5. Reference any related issues in the PR description.

Docker images are never built on contributor PRs, so merging to `main` is always cheap.

## Release Process (maintainers)

### New minor or major release

```bash
# 1. Create a release branch from main
git checkout main && git pull
git checkout -b release/1.2.x

# 2. Push — the semver.yml workflow runs semantic-release automatically,
#    bumps the version, updates CHANGELOG.md + pom.xml, and pushes tag 1.2.0.
git push origin release/1.2.x

# 3. The tag push triggers the Docker publish pipeline.
```

### Patch release on an existing line

```bash
git checkout release/1.1.x
git cherry-pick <commit-sha>
git push origin release/1.1.x
# semver workflow creates 1.1.x and triggers Docker publish
```

### Hotfix

1. Fix on `main` via the normal PR process.
2. Cherry-pick the merge commit onto the relevant `release/x.y.x` branch and push.
3. If the bug only affects a release branch, open a PR directly against that branch.

### Edge builds

The `edge.yml` workflow publishes a JVM-only `floci/floci:edge` image from `main` every Monday at 00:00 UTC. It can also be triggered manually from the Actions tab.

## Testing Policy for Pull Requests

Floci accepts pull requests only when the test coverage is appropriate for the type of change being proposed.

As a project policy:

- Pull requests that introduce new behavior must include tests that validate that behavior.
- Pull requests that fix bugs should include a regression test whenever the bug can be covered realistically.
- Pull requests that modify runtime logic, request handling, persistence behavior, protocol compatibility, or service responses are expected to include updated or additional tests.
- Pull requests that do not change observable behavior, such as documentation updates, formatting, comments, dependency housekeeping, or low-risk internal refactors, may not require new tests.
- Even when no new tests are needed, the existing test suite must still pass.

If a pull request does not include new tests, the author should explain why in the PR description. Valid reasons may include:

- no functional behavior changed
- existing tests already cover the change
- the change is not meaningfully testable in isolation

Maintainers may request additional or more targeted test coverage before approving a PR.

CI runs automatically on every pull request, and build/test checks must pass before merge.

## Reporting Security Issues

Please do **not** open public issues for security vulnerabilities. Report them privately by emailing the maintainer or using [GitHub private vulnerability reporting](https://docs.github.com/en/code-security/security-advisories/guidance-on-reporting-and-writing/privately-reporting-a-security-vulnerability).
