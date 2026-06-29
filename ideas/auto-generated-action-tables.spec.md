# Spec: Auto-Generated Action Tables

Origin: [PR #738](https://github.com/floci-io/floci/pull/738) by @ericbsantana, which
introduced the deterministic parser, marker blocks, and CI gate. This revives that
work and completes the description-preservation his spec already called for (the
original implementation dropped the Description column, which is what blocked it).

## Objective

The "Supported Actions" tables in `docs/services/*.md` are hand-maintained and drift
from the handlers. Derive the action **list** from handler source so it cannot drift,
keep the hand-written **Description** column, and gate both in CI.

Split of responsibility:

- **Which actions exist** is a correctness question, answered only by the code. The
  parser owns the action list; a model or a human never invents or omits a name here.
- **What each action does** is prose. Hand-written, preserved across regeneration,
  keyed by action name. New/unknown actions get a `-` placeholder to fill in.

## Commands

```
make docs-sync     # Regenerate every registered service's action table, in place. Idempotent.
make docs-check    # CI gate: regenerate with --strict, then fail if docs/ changed.
make docs-test     # Run the tooling's unit tests.
```

The generator only ever rewrites the bytes between the marker pair. Every byte outside
the markers is byte-identical before and after.

## Registry: `tools/docs/services.yaml`

```yaml
services:
  - service: secretsmanager                 # free-form key, used in messages
    doc: docs/services/secrets-manager.md
    sources:
      - { path: .../SecretsManagerJsonHandler.java, mode: switch }

  - service: sqs                            # multiple sources merge + dedup, in order
    doc: docs/services/sqs.md
    sources:
      - { path: .../SqsJsonHandler.java,  mode: switch }
      - { path: .../SqsQueryHandler.java, mode: switch }

deferred_handlers:                          # switch handlers known to exist but not yet covered
  - { path: .../dynamodb/DynamoDbJsonHandler.java }  # multi-table doc
```

Optional per-service overrides:

- `rename_actions: { methodName: CanonicalAction }` — for REST controllers whose method
  name is not the canonical AWS action (e.g. `updateV2Integration` → `UpdateIntegration`).
- `exclude_actions: [ ... ]` — drop names that are not real actions (compat wrappers, etc.).

## Extraction modes

- **switch** — `case "Action" ->` arms, in source order. Multi-label arms
  (`case "A", "B" ->`) yield every label.
- **rest** — `ucfirst(methodName)` of `@GET/@POST/@PUT/@DELETE/@PATCH` methods inside an
  `@Path` class, corrected by `rename_actions`/`exclude_actions`.

A service's list is the union across its sources, deduped by exact name, ordered by first
appearance. Registering a controller is opt-in; unregistered controllers are ignored.

## Marker block

```markdown
<!-- floci:actions:start -->
| Action | Description |
| --- | --- |
| `CreateSecret` | Create a new secret |
| `RestoreSecret` | - |
<!-- floci:actions:end -->
```

Regeneration preserves the description for any action still in source, drops rows for
actions no longer in source (warning), and emits `-` for new actions. The table parser
tolerates escaped pipes (`\|`), padded alignment rows, and a missing Description column,
and rejects a malformed block loudly rather than mangling it.

## CI gate

`make docs-check` runs `regen --strict` (which fails on orphan rows or an unregistered
switch handler) then `git diff --exit-code docs/`. A handler change that adds an action
without running `docs-sync` fails the check, naming the command to run. A brand-new
switch handler must be either registered or added to `deferred_handlers` (with a reason),
so coverage gaps are explicit, not silent.

The gate runs in its own Python-only workflow (`.github/workflows/docs-actions.yml`),
triggered by changes under `src/main/**`, `docs/services/**`, or `tools/docs/**`.

## Boundaries

- Read-only on `*.java`. Never modifies source.
- Never modifies markdown outside the marker block.
- Never auto-commits from CI. The contributor commits; CI gates.
- Surfaces drift; never hides it.

## Scope of the first cut (Phase 1)

Covered: single-table, switch-mode services.

Deferred (tracked in `deferred_handlers` and follow-up work):

- **REST-mode services** (scheduler, pipes, msk, rds-data) — need `rename_actions` /
  `exclude_actions` verified per controller.
- **Mixed-mode** (ses: Query + REST surface).
- **Multi-table docs** (dynamodb, sns, ssm, transfer, cloudwatch, config, backup) — need
  multiple keyed marker blocks so curated groupings survive.
- **Non-tabular docs** (iam, ec2, ecs, cognito, glue, elbv2, ...) — separate docs effort.
- **S3** — query-string dispatch, not a switch/REST-per-action shape.
- **ecr** — doc packs several actions per cell and adds REST endpoints; needs de-grouping.
