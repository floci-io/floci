# Auto-Generated Action Tables

## Problem Statement

How might we keep the "Supported Actions" tables in `docs/services/*.md` always in sync with the actual `case "X" ->` branches and JAX-RS controller methods, without anyone having to remember?

## Recommended Direction

A small build-time script extracts action names directly from handler source code and rewrites the action-name column inside marker blocks in each service's markdown page. CI fails any PR where the regenerated docs diverge from what's committed. Descriptions stay hand-written in markdown and are preserved across regenerations; the generator only owns the action-name column.

Two extraction modes cover the whole codebase:

1. **`switch` mode** — for the ~30 handlers that dispatch via `switch (action) { case "ActionName" -> ... }`. Regex `case "([A-Z][A-Za-z0-9]+)"\s*->` extracts every action. Uniform across `*JsonHandler`, `*QueryHandler`, and `*Handler` files. Lowercase cases (e.g. `case "application"` in tag handlers) are filtered by the leading-uppercase rule.
2. **`rest` mode** — for the ~8 JAX-RS controllers (lambda, eks, opensearch, msk, scheduler, pipes, bedrockruntime, cloudwatch). Method names of `@GET/@POST/@PUT/@DELETE/@PATCH`-annotated methods inside an `@Path` class match AWS action names 1:1 after `ucfirst()`. Verified on Lambda (`createFunction` → `CreateFunction`, `invoke` → `Invoke`, `listEventSourceMappings` → `ListEventSourceMappings`) and EKS; the other six need confirmation but follow the same convention.

S3 is the only `mode: skip`. Its 13 controller methods serve ~50 distinct AWS actions because sub-actions are dispatched by query-string inspection inside method bodies (`?tagging` → `PutObjectTagging`, `?acl` → `PutObjectAcl`, etc.), so neither extraction rule produces the right list. A follow-up PR refactors S3 sub-action dispatch into the same `case "X" ->` shape as the rest of the codebase, at which point the existing scanner picks it up with no additional rules.

A `tools/docs/services.yaml` registry maps each service to its mode, doc page, and source files. Adding a new service means one entry. Adding a new action means zero doc work — CI runs the generator, the action name appears in the marker block, the description column for new entries renders blank and a contributor optionally fills it in later.

## Key Assumptions to Validate

- [ ] **Switch-arm regex has no false positives across all ~30 handlers.** Spot-checked on Secrets Manager, RDS, EventBridge during ideation. Validate by running the extractor on `main` and diffing against existing tables.
- [ ] **REST controller method names match AWS action names 1:1 across all 8 rest-mode services.** Confirmed for Lambda and EKS. Validate by extracting from opensearch, msk, scheduler, pipes, bedrockruntime, cloudwatch and diffing against current hand-written tables before committing the registry.
- [ ] **Description preservation round-trips cleanly.** First run on each service must produce a table byte-identical to the current hand-written one (modulo any pre-existing drift the generator surfaces). Validate on Secrets Manager first since drift is already known there (`GetRandomPassword`, `BatchGetSecretValue`, `UpdateSecretVersionStage` are missing from the current table but exist in the handler).
- [ ] **CI gate produces an actionable failure message.** Failure output must name the regen command and the diverged file paths. Validate by deliberately introducing drift in a draft PR.

## MVP Scope

**In:**
- A single regen script (language: whatever fits the existing docs toolchain — likely Python given mkdocs-material).
- `tools/docs/services.yaml` with one entry per service, classifying as `switch` / `rest` / `skip` and pointing at source files + doc page.
- Marker pair (`<!-- floci:actions:start -->` / `<!-- floci:actions:end -->`) inserted into every `switch`/`rest` service page.
- A `Makefile` target (or equivalent) so contributors run `make docs-sync` after adding a handler.
- CI step that runs the regen and fails on `git diff --exit-code docs/`.
- `CONTRIBUTING.md` note: don't hand-edit inside the markers; run the regen.

**Out (deferred):**
- S3 dispatch refactor — separate follow-up PR with independent value (also unblocks per-action metrics/logging).
- Coverage matrix vs. AWS SDK action lists.
- Per-action parameter extraction.
- Auto-generated action descriptions (descriptions remain n2h, blank cells are fine).
- Pre-commit hook (CI is the real gate; pre-commit can come later without design changes).

## Not Doing (and Why)

- **Annotations on handler methods (`@AwsAction("...")`)** — moves the forgetting problem rather than removing it. The `case "X" ->` line is already the change a contributor came to make; nothing extra to remember. Annotations also break for handlers that route multiple actions to one helper or have inline `case "X" -> Response.ok(...)` arms with no method.
- **JavaParser / AST library** — over-engineered. The grammar is uniform inside one codebase; regex covers it with zero false positives.
- **Hand-maintained YAML/JSON manifest of action names** — adds a third place to update; same forgetting problem in a different file.
- **Auto-extracting descriptions from Javadoc** — bloats handlers with prose and conflates code style with doc voice. Hand-written descriptions in markdown stay editable and reviewable as docs.
- **Mandatory descriptions for new actions** — sneaks back the same "easy to forget" pressure this idea is meant to kill. Blank cells are acceptable; descriptions can be filled in over time.
- **Auto-committing regenerated docs from a bot** — magical, races with review, hides the contributor signal. CI failure with a clear regen command is the cleaner gate.
- **Generating whole service pages** — kills the hand-written prose around each table (configuration, examples, caveats). Marker injection preserves it.
- **Bundling the S3 dispatch refactor** — S3 is the most-exercised code path in floci; mixing a behavioral refactor with docs tooling muddles review and bisecting.

## Open Questions

- Sort order inside the generated table: source-order (preserves authorial grouping in the switch/controller) vs. alphabetical (friendlier to scan)? Lean source-order; willing to flip on request.
- Same CI job as the existing build, or a separate fast docs-sync job? Lean same job for simplicity; split later if feedback latency suffers.
- When a `case "X" ->` is added but the service has no entry in `tools/docs/services.yaml`: hard-fail in CI vs. warn-and-skip? Lean hard-fail — keeps the registry honest.
- Should the regen also flag *removed* actions (in the marker block but no longer in source)? Lean yes, as warnings — keeps stale rows from accumulating.
