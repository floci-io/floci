# Contributing

Floci is MIT licensed and welcomes contributions of all kinds.

**Join us on [Slack](https://join.slack.com/t/floci/shared_invite/zt-3tjn02s3q-A00kEjJ1cZxsg_imTfy6Cw)**: the fastest way to reach maintainers for questions, design tradeoffs, or feedback on an approach before you build it.

## Ways to Help 

- **Bug reports**: open a [GitHub issue](https://github.com/floci-io/floci/issues/new?template=bug_report.md) with a minimal reproduction
- **Missing API actions**: open a [feature request](https://github.com/floci-io/floci/issues/new?template=feature_request.md)
- **Pull requests**: new service actions, bug fixes, documentation improvements

## Development Setup

```bash
# Clone
git clone https://github.com/floci-io/floci.git
cd floci

# Run in dev mode (hot reload, port 4566)
mvn quarkus:dev

# Run all tests
mvn test

# Run a specific test
mvn test -Dtest=SsmIntegrationTest
mvn test -Dtest=SsmIntegrationTest#putParameter
```

## Commit Message Format

This project uses [Conventional Commits](https://www.conventionalcommits.org/), required for semantic-release to generate the changelog and version bumps automatically.

> **The PR title is validated automatically by CI** and must follow this format, since it becomes the squash-merge commit message that semantic-release reads.

### Format

```
<type>[optional scope]: <description>
```

| Type | Effect |
|---|---|
| `feat` | New feature → minor version bump |
| `fix` | Bug fix → patch version bump |
| `perf` | Performance improvement → patch |
| `revert` | Reverts a previous commit → patch |
| `docs` | Documentation only → no version bump |
| `style` | Formatting, whitespace → no version bump |
| `chore` | Build/CI/housekeeping → no version bump |
| `refactor` | Code restructure → no version bump |
| `test` | Adding/updating tests → no version bump |
| `build` | Build system changes → no version bump |
| `ci` | CI workflow changes → no version bump |
| `feat!:` or `BREAKING CHANGE:` | Breaking change → major bump |

### Valid examples ✅

```
feat(dynamodb): add PartiQL ExecuteStatement support
fix(s3): make us-east-1 bucket creation idempotent
chore: release 1.5.16
feat!: remove legacy v1 endpoint
ci: add conventional commits lint workflow
```

### Invalid examples ❌

```
Add PartiQL support              # missing type
Feature: add something           # not a valid type
feat : space before colon        # space before colon
FIX(s3): uppercase type          # type must be lowercase
feat(my scope): spaces in scope  # scope cannot contain spaces
wip: still working on this      # not a recognised type
```

## Adding a New AWS Service

See [AGENTS.md](https://github.com/floci-io/floci/blob/main/AGENTS.md) for the full architecture guide. `AGENTS.md` is the canonical agent instructions file for this repository, following the [AGENTS.md standard](https://agents.md/). If your coding agent expects a different filename, create a local symlink to `AGENTS.md` instead of copying it.

```bash
ln -s AGENTS.md CLAUDE.md
ln -s AGENTS.md GEMINI.md
ln -s AGENTS.md COPILOT.md
```

Quick summary:

1. Create `src/main/java/.../services/<service>/` with a Controller, Service, and `model/` package
2. Pick the right protocol (see the protocol table in `AGENTS.md`)
3. Register a `ServiceDescriptor` in `ResolvedServiceCatalog` (`ServiceRegistry` only reads the catalog)
4. Add config in `EmulatorConfig.java` and in both the main and test `application.yml`
5. Add `*ServiceTest.java` and `*IntegrationTest.java` tests
6. Add the docs page, its `mkdocs.yml` nav entry, a Service Matrix row and a README row (`make docs-check` gates the matrix)

## Pull Request Checklist

- [ ] `mvn test` passes
- [ ] New or updated integration test added
- [ ] Commit messages follow Conventional Commits

Please keep at most **5 open PRs** at a time. A bot leaves an advisory note (label `over-pr-limit`) on PRs opened beyond that. See [CONTRIBUTING.md](https://github.com/floci-io/floci/blob/main/CONTRIBUTING.md#pull-request-guidelines) for details.

## Releases

Stable releases ship on the **1st and 3rd Tuesday of each month**. Merging to `main` does not cut a release: the change rides the next train, and reaches the `nightly` image on the next nightly build.

Maintainers cut releases from `main` with the Release Cut workflow, which runs semantic-release over the Conventional Commits since the last tag. That is why the commit type matters: `feat:` and `fix:` move the version, `docs:` and `chore:` do not. `CHANGELOG.md` is generated from those messages and is not edited by hand; a genuine correction goes in a PR carrying the `changelog-edit` label.

## Reporting Security Issues

Do **not** open public issues for security vulnerabilities. Use [GitHub private vulnerability reporting](https://docs.github.com/en/code-security/security-advisories/guidance-on-reporting-and-writing/privately-reporting-a-security-vulnerability) instead.
