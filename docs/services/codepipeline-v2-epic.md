# Follow-up epic: complete CodePipeline V2

## Outcome

Promote Floci's partial CodePipeline V2 support into an AWS-compatible local execution
environment. A V2 pipeline created through the AWS CLI, an AWS SDK, or CloudFormation must
behave like the corresponding AWS pipeline for triggers, execution modes, stage conditions,
variables, retry, rollback, history, and events.

This is follow-up work. The LZA scenario currently exercises a **V1** pipeline and does not
depend on completion of this epic.

## Current baseline

Floci already accepts `pipelineType: V2` and implements several V2 capabilities:

- `SUPERSEDED`, `QUEUED`, and `PARALLEL` execution modes
- pipeline execution variables and `#{variables.name}` resolution
- `beforeEntry`, `onSuccess`, and `onFailure` stage conditions
- real `LambdaInvoke` and `VariableCheck` rule evaluation
- condition override, failed-stage retry, and stage rollback
- rule-execution history and CodePipeline state-change events

The remaining work is to make those capabilities complete and to remove permissive or
metadata-only behavior that can produce a false-green local execution.

## Scope

### 1. V2 definition and validation parity

- Validate the V2-only fields, required combinations, limits, names, and enum values used by
  `CreatePipeline` and `UpdatePipeline`.
- Reject V2 fields on a V1 pipeline with AWS-shaped validation errors.
- Preserve `pipelineType`, `executionMode`, variables, triggers, conditions, and rule
  declarations through create, get, update, list, CloudFormation, and persistent reload.
- Cover single-region `artifactStore` and cross-region `artifactStores` without silently
  selecting the wrong store.

### 2. Native V2 triggers

- Implement trigger declarations and filter matching for supported source providers,
  including branch, file-path, tag, and pull-request filters where AWS exposes them.
- Start executions from actual local source events rather than recording trigger metadata
  only.
- Populate `trigger`, `sourceRevisions`, and source action output variables from the event that
  started the execution.
- Deduplicate redelivery and apply each pipeline's execution mode before work is dispatched.
- Keep provider support explicit: an unsupported trigger/provider combination must fail
  validation or execution, never pass silently.

### 3. Execution-mode fidelity

- `SUPERSEDED`: supersede only executions and stages AWS would supersede, and expose the same
  terminal state and reason.
- `QUEUED`: maintain deterministic FIFO admission across restarts and release the next run only
  after the active run reaches a terminal state.
- `PARALLEL`: isolate artifacts, variables, action tokens, approvals, retry state, and rollback
  targets per execution.
- Enforce AWS restrictions on retry, rollback, and stage-condition operations for each mode.

### 4. Complete stage-condition rules

- Replace the current permissive `Commands` and `DeployWindow` behavior with real evaluation.
- Match AWS rule lifecycle, timeout, retry, failure, result, and override semantics.
- Implement rule input/output variable expansion and expose AWS-compatible rule execution
  summaries.
- Reject unknown rule providers rather than treating them as successful.
- Verify `beforeEntry`, `onSuccess`, and `onFailure` combinations, including `FAIL`, `SKIP`,
  `ROLLBACK`, and condition overrides.

### 5. Retry, rollback, and artifact lineage

- Persist immutable per-execution artifact and source-revision lineage.
- Retry `FAILED_ACTIONS` and `ALL_ACTIONS` with AWS-compatible attempt numbering and history.
- Roll back using the selected successful execution's artifacts instead of rerunning source
  merely to reconstruct them.
- Preserve lineage across Floci restart and reject missing or expired rollback targets with an
  AWS-shaped error.

### 6. API, event, and CloudFormation parity

- Make pipeline, stage, action, rule, retry, rollback, and trigger fields agree across
  `GetPipeline`, `GetPipelineState`, `GetPipelineExecution`, and the list APIs.
- Publish accurate EventBridge events for queued, superseded, parallel, condition, retry, and
  rollback transitions.
- Round-trip every supported V2 property through `AWS::CodePipeline::Pipeline`, including
  create, update in place, replacement rules, `Ref`, `Fn::GetAtt`, and deletion.
- Keep API responses reflection-safe for native-image builds.

## Acceptance criteria

The epic is complete only when all of the following are demonstrated:

1. AWS SDK integration tests create and update both V1 and V2 pipelines and verify that invalid
   cross-version fields return AWS-compatible errors.
2. Event-driven tests prove each supported trigger filter starts exactly the intended execution
   and records the real trigger and source revision.
3. Deterministic tests run overlapping executions in `SUPERSEDED`, `QUEUED`, and `PARALLEL`
   modes and verify status, isolation, ordering, artifacts, variables, and events.
4. Every advertised condition rule provider has positive, negative, timeout, retry, and
   override coverage; no provider succeeds through a permissive fallback.
5. Retry and rollback tests prove artifact lineage is reused correctly before and after a Floci
   restart.
6. CloudFormation integration tests create and update a V2 pipeline and assert its exact
   triggers, variables, conditions, execution mode, physical ID, and attributes.
7. The CodePipeline compatibility suite passes using AWS SDK clients, and the V1 suite remains
   green to prove the V2 work did not regress LZA's pipeline.
8. This service guide is updated so every advertised V2 feature corresponds to an executable
   test rather than accepted-but-inert configuration.

## Out of scope

- Calling real AWS accounts or hosted third-party CI/CD providers
- Undocumented AWS internals that are not observable through public APIs, events, or SDK
  behavior
- Replacing the existing in-process orchestration engine


