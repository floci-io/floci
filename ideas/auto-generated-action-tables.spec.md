# Spec: Auto-Generated Action Tables

Companion to [`auto-generated-action-tables.md`](auto-generated-action-tables.md). That doc is the *why* and the directional decision; this is the *what to build*.

## 1. Objective

Eliminate manual maintenance of the "Supported Actions" tables in `docs/services/*.md` by deriving them from handler source code at build time, and prevent silent drift via a CI gate.

**Target user:** floci contributors adding or modifying AWS service handlers. Today, adding a `case "X" ->` arm or a JAX-RS controller method requires a separate manual edit to a markdown table that is easy to forget; the freshly-shipped Secrets Manager docs (PR #698) are already missing three actions that exist in the handler.

**Acceptance criteria.** The feature is done when all of the following hold:

- Running `make docs-sync` on a clean checkout of `main` produces zero diff against committed `docs/services/*.md` (after the migration commit that seeds existing tables into marker blocks).
- Adding a new `case "ActionName" ->` arm to any switch-mode handler and committing without running `make docs-sync` causes CI to fail with a message naming the diverged file and the regen command.
- Adding a new `@GET`/`@POST`/`@PUT`/`@DELETE`/`@PATCH` method to any rest-mode controller and committing without running `make docs-sync` causes CI to fail the same way.
- A contributor running `make docs-sync` after such a change sees one new row appended to the relevant table inside the marker block, in source order, with an empty description cell. No other text in the file is modified.
- Adding a new service to floci requires exactly one new entry in `tools/docs/services.yaml` plus marker insertion in the new doc page; no script changes.
- S3's existing hand-written `## Supported Operations` section is left untouched by the generator.

## 2. Commands

```
make docs-sync     # Regenerate all marker blocks. Idempotent.
make docs-check    # Run docs-sync, then `git diff --exit-code docs/`. Used by CI.
```

CI invokes `make docs-check` as a step in the existing build job in `.github/workflows/ci.yml`. The step's failure message must include the literal string `make docs-sync` and the list of diverged files.

Local contributor workflow:

1. Add or modify a handler.
2. Run `make docs-sync`.
3. Optionally fill in the description cell for any new row.
4. Commit handler + docs together.

If a contributor adds an action to a service not present in `tools/docs/services.yaml`, the script exits non-zero in CI with `error: service '<dir>' has new action '<X>' but is not registered in tools/docs/services.yaml`. Locally (when invoked via `make docs-sync` without the `--strict` flag) it prints a warning and continues.

If an action exists inside a marker block but no longer appears in any registered source file, the script prints a warning naming the orphan but does not fail. The orphan row is left in place; the contributor decides whether to remove it.

## 3. Project Structure

```
tools/docs/
  regen_action_docs.py             # Single-file Python script + library. No package.
  services.yaml                    # Registry: service → mode → sources → doc page.
  requirements.txt                 # PyYAML, pytest.
  test_regen_action_docs.py        # Golden-file tests.
  fixtures/                        # Golden-file test fixtures.
    secretsmanager/
      inputs.yaml                  # { sources: [{ file, mode }, ...] }
      input/SecretsManagerJsonHandler.java
      input/before.md
      expected/after.md
    lambda/
      inputs.yaml
      input/LambdaController.java
      input/before.md
      expected/after.md
    sns/                           # Multi-handler switch-mode service.
      inputs.yaml
      input/SnsJsonHandler.java
      input/SnsQueryHandler.java
      input/before.md
      expected/after.md
    s3/                            # Skip-mode: input == expected.
      inputs.yaml
      input/before.md
      expected/after.md
docs/services/*.md                 # Each switch/rest service page contains:
                                   #   <!-- floci:actions:start -->
                                   #   ...generated table...
                                   #   <!-- floci:actions:end -->
Makefile                           # New file. Targets: docs-sync, docs-check.
.github/workflows/ci.yml           # Extended with one `make docs-check` step.
CONTRIBUTING.md                    # Note: do not hand-edit inside the markers.
```

`tools/docs/services.yaml` schema:

```yaml
- service: secretsmanager                                # Free-form key, used in error messages.
  doc: docs/services/secrets-manager.md
  sources:
    - { path: src/main/java/.../secretsmanager/SecretsManagerJsonHandler.java, mode: switch }

- service: sns                                           # Multi-handler switch service.
  doc: docs/services/sns.md
  sources:
    - { path: src/main/java/.../sns/SnsJsonHandler.java,  mode: switch }
    - { path: src/main/java/.../sns/SnsQueryHandler.java, mode: switch }

- service: lambda                                        # Multiple REST controllers, only AWS-API ones registered.
  doc: docs/services/lambda.md
  sources:
    - { path: src/main/java/.../lambda/LambdaController.java,         mode: rest }
    - { path: src/main/java/.../lambda/LambdaTagController.java,      mode: rest }
    - { path: src/main/java/.../lambda/LambdaLayerController.java,    mode: rest }
    # Proxy/invocation controllers (ApiGatewayController, LambdaUrlInvocationController, ...) are intentionally omitted.

- service: ses                                           # Mixed-mode: Query protocol + REST surface.
  doc: docs/services/ses.md
  sources:
    - { path: src/main/java/.../ses/SesQueryHandler.java, mode: switch }
    - { path: src/main/java/.../ses/SesController.java,   mode: rest   }

- service: s3                                            # Skip: empty sources means no markers, doc untouched.
  doc: docs/services/s3.md
  sources: []
```

**Per-source mode** (not per-service). A service's table is the union of every source's extracted actions, deduplicated by exact name, ordered by first appearance across the source list in registry order. `sources: []` means the service is intentionally excluded from regeneration; the doc page must not contain a marker block. Listing a `Controller.java` file is opt-in: controllers that exist in the codebase but are not registered are silently ignored (covers proxy routes, internal inspection endpoints, etc. — these are per-judgment classifications, not auto-discovery).

Marker block format inside doc pages:

```markdown
<!-- floci:actions:start -->
| Action | Description |
| --- | --- |
| `CreateSecret` | Create a new secret |
| `GetSecretValue` | Retrieve the current secret value |
...
<!-- floci:actions:end -->
```

## 4. Code Style

- Single Python file at `tools/docs/regen_action_docs.py`. Standard library only except `PyYAML` (already a transitive dep of mkdocs-material).
- Python 3.10+ (matches mkdocs toolchain).
- Type hints on every function. Pure-function extractors (`extract_switch_actions(text: str) -> list[str]`, `extract_rest_actions(text: str) -> list[str]`) so they are golden-file testable in isolation.
- Module shape:
  - `extract_switch_actions(java_source: str) -> list[str]` — regex `case\s+"([A-Z][A-Za-z0-9]+)"\s*->`, returns matches in source order.
  - `extract_rest_actions(java_source: str) -> list[str]` — finds methods preceded by `@GET|@POST|@PUT|@DELETE|@PATCH` inside an `@Path`-annotated class, returns `ucfirst(method_name)` in source order.
  - `merge_actions(per_source: list[list[str]]) -> list[str]` — flattens, deduplicates by first appearance.
  - `parse_marker_block(md: str) -> tuple[str, dict[str, str], str]` — returns `(prefix_before_start_marker, action_to_description_map, suffix_after_end_marker)`. Raises if marker pair is missing or unbalanced.
  - `render_marker_block(actions: list[str], descriptions: dict[str, str]) -> str` — emits the marker pair plus table; missing descriptions become empty strings.
  - `regenerate(service_entry, repo_root) -> bool` — returns True if the file was changed.
  - `main(argv)` — loads registry, iterates entries, exits non-zero on any unregistered service or other hard error. Default mode is non-strict (warnings allowed) unless `--strict` is passed (CI passes `--strict`).
- No string templating libraries; the marker block is small enough for f-strings.
- No clever metaprogramming. The whole script should fit comfortably under 300 lines including comments.

## 5. Testing Strategy

Golden-file tests at `tools/docs/test_regen_action_docs.py`, runnable via `pytest tools/docs/`. Each fixture directory contains a self-contained input (Java source + pre-state markdown) and the expected post-state markdown. The test parametrizes over fixture directories and asserts byte-exact equality after regeneration.

Required fixtures for the MVP:

- **secretsmanager** (`mode: switch`, single source) — covers the canonical switch path and validates the description-preservation logic on a service with known prior drift.
- **lambda** (`mode: rest`, single source) — covers the JAX-RS extraction path on a controller with many methods.
- **sns** (`mode: switch`, two sources) — covers the multi-handler merge and dedup.
- **s3** (`mode: skip`) — input file equals expected file; verifies skip-mode does not modify anything.
- **unregistered** — a fixture where Java source contains an action not present in the marker block; asserts script exits non-zero in `--strict` and warns otherwise.

Tests live alongside the fixtures, not inside `src/test`, because the regen tool is repo-tooling not Java code.

CI runs the test suite via a separate step (`pytest tools/docs/`) before the `make docs-check` step, so a script regression surfaces before the diff check.

## 6. Boundaries

**Always do:**

- Modify only the bytes between the `<!-- floci:actions:start -->` and `<!-- floci:actions:end -->` markers, inclusive of the markers themselves. Every byte outside this range must be byte-identical pre and post regen.
- Preserve every hand-written description that maps to an action still present in source. Match by exact action name.
- Emit actions in source order (first appearance across the source files in `services.yaml` order).
- Treat absence of a marker block in a non-`skip` service as a hard error in CI.
- Treat presence of a top-level `*Handler.java` file under `src/main/java/.../services/<svc>/` (depth 1, `*Handler.java` only) that is not listed as a source in any `services.yaml` entry, *and* whose service has no entry at all (or has `sources: []` and the file is new), as a warning locally and a hard error in CI (`--strict`). REST controllers are not subject to this check — registration of controllers is per-judgment opt-in (see schema notes).
- Exit non-zero whenever the registry is malformed (missing fields, invalid mode, source files that do not exist).

**Ask first (out of scope for this spec; require a follow-up decision):**

- Adding a new mode beyond `switch` / `rest` / `skip`.
- Refactoring S3's sub-action dispatch. Tracked as a separate follow-up; out of scope for this spec.
- Auto-extracting descriptions from any source (Javadoc, AWS SDK metadata, etc.).
- Regenerating any content outside the marker block (configuration tables, examples, headings).
- Pre-commit hook installation or any auto-fix in a developer's working tree.

**Never do:**

- Modify any `*.java` file. The generator is read-only on source.
- Modify any markdown text outside marker blocks, including reflowing whitespace, normalizing trailing newlines elsewhere, or rewriting headings.
- Auto-commit regenerated docs from CI. The contributor commits, CI gates.
- Embed source paths or other configuration data inside the markdown markers themselves; configuration lives only in `tools/docs/services.yaml`.
- Hide drift. If a registered service produces a diff, the script must surface it, never silently fix it.
- Touch `docs/services/s3.md`. S3 is mode `skip` and stays manual until the dispatch refactor lands.

## Open Items Carried Forward

Two questions from the one-pager remain answered-by-default in this spec; calling them out so they can be revisited if needed:

- **CI step placement:** runs in the existing build job (one less workflow file to manage). Split into a separate `docs-sync` job only if feedback latency becomes an issue.
- **Removed-action policy:** warn but don't fail. Keeps stale rows from accumulating without forcing churn when an action is intentionally removed in the same PR that rewrites a description.
