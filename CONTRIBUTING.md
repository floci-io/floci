# Contributing to Floci

Thank you for your interest in contributing! Floci is a community-driven project and all contributions are welcome.

**Join us on [Slack](https://join.slack.com/t/floci/shared_invite/zt-3tjn02s3q-A00kEjJ1cZxsg_imTfy6Cw)**: it is the fastest way to reach maintainers. Ask about AWS behaviour, sanity-check an approach before you build it, or get unstuck on a PR.

## Ways to Contribute

- **Bug reports**: open an issue with a minimal reproduction
- **Feature requests**: open an issue describing the AWS behavior you need
- **Pull requests**: bug fixes, new service implementations, or improvements
- **Compatibility tests**: add cases to `./compatibility-tests/`

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

Floci uses a **tag-driven release model**. Merging a PR never publishes a release image. `main` is published once a day as the `nightly` channel, and a stable release is published only when a maintainer runs the "Release Cut" workflow (`.github/workflows/release-cut.yml`, `workflow_dispatch`). That workflow runs semantic-release, which bumps `pom.xml`, writes `CHANGELOG.md`, commits, and pushes the `X.Y.Z` tag. The tag then triggers `.github/workflows/release.yml`, which builds and publishes the release images to Docker Hub and public ECR.

| Branch / tag | Purpose | Docker published? |
|---|---|---|
| `main` | Integration branch: all PRs merge here. Treated as unstable. | Nightly only (`nightly`, `nightly-<date>`, `nightly-compat`, `nightly-<date>-compat`) via `nightly.yml` |
| `X.Y.Z` tag | Signals a production release. Pushed by the Release Cut workflow. | Yes (`x.y.z`, `latest`, `x.y.z-compat`, `latest-compat`) via `release.yml` |

## Commit Message Format

This project uses [Conventional Commits](https://www.conventionalcommits.org/): semantic-release reads these to generate the changelog and version bumps automatically.

> **The PR title is validated automatically by CI** and must follow this format, since it becomes the squash-merge commit message that semantic-release reads.

### Format

```
<type>[optional scope]: <description>
```

- **type**: one of the values in the table below (lowercase)
- **scope**: optional, in parentheses, identifies the service or area (e.g. `s3`, `dynamodb`, `core`)
- **description**: short summary in the imperative mood, no trailing period
- Append `!` before the colon to signal a breaking change: `feat(api)!:`

| Type | When to use | Version bump |
|------|-------------|--------------|
| `feat` | New AWS API action or service | minor |
| `fix` | Bug fix or AWS compatibility correction | patch |
| `perf` | Performance improvement | patch |
| `revert` | Reverts a previous commit | patch |
| `docs` | Documentation only | none |
| `style` | Formatting, whitespace, no logic change | none |
| `chore` | Build, CI, dependencies, housekeeping | none |
| `refactor` | Code restructure without behavior change | none |
| `test` | Adding or updating tests | none |
| `build` | Build system or tooling changes | none |
| `ci` | CI workflow changes | none |
| `BREAKING CHANGE` | Footer or `!` suffix, incompatible change | major |

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

CI enforces this: the **Commits omit AI attribution trailers** check fails a pull request whose commits carry a `Co-Authored-By` for an identity that is not a person (one whose GitHub address belongs to a bot account, or that uses a `noreply@` mailbox), along with the session and generator lines such tools add on their own. It never judges a co-author by name, so co-authoring a person is always fine; that trailer is how GitHub credits reviewers on a squash merge. `dependabot[bot]` is allowlisted. If the check fires, drop the offending lines with `git commit --amend` (or a rebase for several commits) and force-push.

## Architecture

See [AGENTS.md](AGENTS.md) for a detailed description of the three-layer architecture (Controller → Service → Storage), the AWS wire protocol mapping, and conventions for adding new services.

`AGENTS.md` is the canonical agent instructions file for this repository, following the [AGENTS.md standard](https://agents.md/). If your coding agent expects a different filename, create a local symlink to `AGENTS.md` instead of copying the file.

```bash
ln -s AGENTS.md CLAUDE.md
ln -s AGENTS.md GEMINI.md
ln -s AGENTS.md COPILOT.md
```

## Adding a New AWS Service

1. Create a package under `src/main/java/.../services/<service>/`
2. Add a Controller (follow the correct protocol: Query, JSON 1.1, REST JSON, or REST XML)
3. Add a Service (`@ApplicationScoped`) and model POJOs
4. Add a `<Svc>ServiceConfig` interface and its accessor on `ServicesConfig` in `EmulatorConfig.java`
5. Register a `ServiceDescriptor` in `ResolvedServiceCatalog`. This is the only registration point: `ServiceRegistry` reads the catalog and has no registration API
6. Add `floci.services.<key>.enabled` to both `src/main/resources/application.yml` and `src/test/resources/application.yml`
7. Wire controller/handler dispatch for the service (JSON 1.1 handlers are injected into `AwsJson11Controller`)
8. Obtain storage through `StorageFactory` and implement `Resettable`; list any static `Random` or `SecureRandom` field under `--initialize-at-run-time` in `application.yml`
9. Add `*ServiceTest.java` and `*IntegrationTest.java` tests
10. Document it: `docs/services/<service>.md`, a `mkdocs.yml` nav entry, a Service Matrix row in `docs/services/index.md`, and a row in the README category table
11. Register the handler in `tools/docs/services.yaml`, then run `make docs-sync` and `make docs-check`
12. Add a client factory in the compat suite's `TestFixtures` and a `<Svc>Test` under `compatibility-tests/sdk-test-java`

`ServiceRegistry`, `ServiceEnabledFilter`, and `StorageFactory` resolve service metadata from the descriptor catalog. Adding a service should not require new service-keyed switch statements in those consumers. `make docs-check` gates steps 10 and 11: a registered service with no matrix row, a `docs/services` page with no matrix row, and a stale action table all fail CI.

Always implement the **real AWS wire protocol**. Never invent custom endpoints. The AWS SDK must work against Floci without modification.

## Adding a CloudFormation Resource Type

CloudFormation resource types live in **per-service provisioner classes**, not in
`CloudFormationResourceProvisioner`. That class is a legacy monolith being dismantled, so please do
not add cases to it. If the service you need already has a `*CfnProvisioner`, add your type there;
otherwise create one.

1. Create `services/cloudformation/provisioners/<Service>CfnProvisioner.java`, annotate it
   `@ApplicationScoped`, and inject **only** the service it wraps. Registration is automatic:
   `CloudFormationResourceRegistry` discovers it via CDI. (Forgetting `@ApplicationScoped` compiles
   and unit-tests green, but the type is never wired.)
2. Implement `resourceTypes()` returning every `AWS::*` type the class serves, and `provision(...)`.
   When you serve more than one type, switch on `resource.getResourceType()`.
3. In `provision`, set **both**:
   - `resource.setPhysicalId(...)`: this is what `Ref` resolves to
   - `resource.getAttributes().put("Name", value)` for every `Fn::GetAtt` attribute, a *separate*
     map. Forgetting it does not fail: `Fn::GetAtt` silently resolves to the literal string
     `"LogicalId.Attr"`. Take the attribute names from the resource's registry schema under
     `local/aws/cfn-resource-schemas/us-east-1/` (`readOnlyProperties`).
4. **`provision` is also the update path.** On `UpdateStack` it is called again with the previous
   physical id and attributes already set on the resource. Use `ctx.isUpdate()` and
   `ctx.priorPhysicalId()` rather than reading the id back off the resource: `provision` assigns the
   new id as it works, so a resource-derived check changes meaning mid-method. Update in place
   rather than creating unconditionally, which would otherwise throw `AlreadyExists` or orphan the
   old resource.
5. Override `delete(...)` if the type has a backing delete. Deleting a resource that is already gone
   should be tolerated: `CfnDeletes.safeDelete` does this, taking the specific error codes that
   mean "already gone" (never a catch-all: a real failure such as `BucketNotEmpty` must propagate so
   the stack reports `DELETE_FAILED`).
   If the delete needs more than the physical id (a create-time attribute such as a rule's event
   bus), override `delete(StackResource, String)` instead, which receives the whole resource.
6. **Register the type in `src/test/resources/cloudformation/supported-resource-types.tsv`**
   (`type<TAB>YourCfnProvisioner`). `CfnResourceInventoryTest` compares that file against the
   CDI-resolved registry, so it fails if you forget, which is also what catches a missing
   `@ApplicationScoped`.
7. **Make your provisioner reachable from `CfnProvisionerFixture`** if it takes a single service.
   That is three edits, not one: a `private <Svc>Service` field on `Builder`, a
   `public Builder <svc>(<Svc>Service v)` setter for it, and a construction arm in
   `inferredProvisioners()`. Tests name a service and the fixture wires the matching provisioner;
   without all three, a test exercising your type silently falls through to the stub arm instead.
   The setter is the step that gets missed: a field nothing can assign stays null, so its
   construction arm never runs. `CfnProvisionerFixtureTest` fails if the provisioner cannot be
   reached through the public builder.
8. Add a focused unit test (mock only your service, see `SqsCfnProvisioner`'s test) and an
   integration test. Assert the **specific `Fn::GetAtt` attribute keys**, not just
   `CREATE_COMPLETE`: an unmapped type is stubbed out as a successful no-op, so a status-only
   assertion passes even when nothing was provisioned.
9. Run `make docs-sync` to regenerate the resource-type table in `docs/services/cloudformation.md`,
   and commit the result. **Do not hand-edit that table**: it is generated from the inventory in
   step 6. Presentation (service label, ordering, notes) lives in `tools/docs/cfn_resource_types.yaml`.
10. If your type has a schema `readOnlyProperties` entry you cannot set, add a row to
    `src/test/resources/cloudformation/getatt-attribute-gaps.tsv` with the reason.
    `CfnSchemaCoverageTest` requires every unset attribute to be either fixed or recorded, so
    "not emulated" stays distinguishable from "forgotten".

`SqsCfnProvisioner` is the smallest reference implementation; `Ec2LaunchTemplateCfnProvisioner`
shows update-in-place and replacement handling; `LogsCfnProvisioner` shows a full reconcile-vs-replace
update path.

## Pull Request Guidelines

1. Branch off `main`: `git checkout -b feature/my-feature`
2. Open a PR targeting `main`.
3. CI runs tests automatically. All checks must pass before merge.
4. Keep PRs focused: one feature or fix per PR.
5. Reference any related issues in the PR description.
6. Keep at most **5 open PRs** at a time. A bot leaves an advisory note and an `over-pr-limit` label on PRs opened beyond that. Nothing gets closed or blocked, but please land or close your existing PRs before opening more.

Docker images are built on contributor PRs only for the compatibility suite (`.github/workflows/compatibility.yml`, on changes under `src/**` and the compat test trees) and are never published, so merging to `main` is always cheap.

## Release Process (maintainers)

Stable releases ship on the **1st and 3rd Tuesday of each month**. Merging to `main` does
not cut a release: the change rides the next train, and reaches the `nightly` image on the
next nightly build.

Releases are cut from `main` with the **Release Cut** workflow
(Actions → Release Cut → Run workflow). semantic-release analyzes the
Conventional Commits since the last tag, bumps `pom.xml`, regenerates
`CHANGELOG.md`, commits, tags, and publishes the GitHub Release; the tag
push triggers the Docker publish pipeline. Use the `dry-run` input to
preview the next version and notes without releasing.

`CHANGELOG.md` is generated. **Do not edit it by hand.** Your Conventional
Commit message is the changelog entry. Genuine corrections to the file
require the `changelog-edit` label on the PR.

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

## Documentation: Action Tables

The **Supported Actions** tables in `docs/services/*.md` are generated from handler
source, so they cannot drift from the code. The action list comes from the handler
(`case "X" ->` arms or REST controller methods); the **Description** column is
hand-written and preserved across regeneration, keyed by action name.

- **Do not hand-edit** the action rows between the `<!-- floci:actions:start -->` and
  `<!-- floci:actions:end -->` markers. Edit the handler, then regenerate.
- After adding or changing a handler action, run `make docs-sync` and commit the
  updated doc alongside your code. Fill in the `-` placeholder description for any new
  row.
- `make docs-check` (run in CI) fails if a registered service's table is out of date,
  or if a new switch handler is neither registered in `tools/docs/services.yaml` nor
  listed under `deferred_handlers`.
- `make docs-test` runs the tooling's unit tests.

Registering a new service's action table is one entry in `tools/docs/services.yaml`.

## Reporting Security Issues

Please do **not** open public issues for security vulnerabilities. Report them privately by emailing the maintainer or using [GitHub private vulnerability reporting](https://docs.github.com/en/code-security/security-advisories/guidance-on-reporting-and-writing/privately-reporting-a-security-vulnerability).
