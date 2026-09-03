# CodePipeline

Floci implements the AWS CodePipeline JSON 1.1 API and a local pipeline execution engine.

**Protocol:** `POST /` with `Content-Type: application/x-amz-json-1.1` and
`X-Amz-Target: CodePipeline_20150709.<Action>`.

## Supported Operations (44 total)

The complete CodePipeline 2015-07-09 API surface is routed:

- Pipeline lifecycle, state, execution history, start, stop, retry, and rollback
- Stage transitions, manual approvals, action and rule execution history
- Custom action types and AWS/third-party worker job polling
- Webhook registration and tag lifecycle

Pipeline definitions, executions, custom action types, jobs, webhooks, tags, and transition
state use Floci's configured storage backend.

## Execution

Stages execute in declaration order. Actions with the same `runOrder` execute in parallel.
`SUPERSEDED`, `QUEUED`, and `PARALLEL` execution modes are recognized, with `QUEUED` and
`PARALLEL` restricted to V2 pipelines.

The following providers execute against local Floci services:

| Category | Provider | Behavior |
|---|---|---|
| Source | S3 | Reads the configured object and publishes the output artifact |
| Source | GitHub (ThirdParty, v1) | Downloads the configured branch archive from github.com and publishes it with the repo contents at the artifact root |
| Build/Test | CodeBuild | Starts and monitors the configured local CodeBuild project |
| Deploy | S3 | Writes the input artifact to the configured bucket and key |
| Deploy | CodeDeploy | Starts and monitors a local CodeDeploy deployment |
| Invoke | Lambda | Invokes the configured local Lambda function |
| Invoke | CodePipeline | Starts a nested local pipeline execution |
| Approval | Manual | Waits for `PutApprovalResult` |
| Custom/third-party | Any registered action | Uses poll, acknowledge, success, and failure job APIs |

AWS-managed providers without a corresponding Floci execution adapter fail the action with an
AWS-shaped action error. Floci does not call real AWS accounts or third-party SaaS providers.

## Approvals

`PutApprovalResult` completes waiting Manual approval actions with AWS-shaped validation
and error responses. Floci validates the stage and action names, enforces `result.status`
as `Approved` or `Rejected`, limits `result.summary` to 512 characters, returns
`InvalidApprovalTokenException` for unknown tokens, and returns
`ApprovalAlreadyCompletedException` if the same approval token is reused after completion.

## Events and notifications

Executions publish the real `aws.codepipeline` state-change events to the **default
EventBridge bus**: `CodePipeline Pipeline Execution State Change` (STARTED, SUCCEEDED,
FAILED, STOPPING, STOPPED, RESUMED), `CodePipeline Stage Execution State Change`, and
`CodePipeline Action Execution State Change`, with the pipeline ARN in `resources` and
the documented detail fields. EventBridge rules matching `{"source":
["aws.codepipeline"]}` deliver them to any configured target. Publishing is best-effort
and never fails the execution.

A Manual approval action whose configuration sets `NotificationArn` publishes the
approval-needed message (subject `APPROVAL NEEDED: AWS CodePipeline ...`, JSON body with
the approval token and `CustomData`) to that SNS topic when it starts waiting.

## V2 stage conditions, retry, and rollback

Floci currently provides a useful **partial V2 implementation**. Completing trigger-driven
execution, strict rule evaluation, execution isolation, and persistent artifact lineage is
tracked in the [CodePipeline V2 follow-up epic](codepipeline-v2-epic.md). The LZA scenario uses
a V1 pipeline and does not depend on that follow-up.

V2 stage condition blocks (`beforeEntry`, `onSuccess`, `onFailure`) are evaluated during
execution. Two rule providers evaluate for real: **LambdaInvoke** (invokes the configured
local Lambda function; the rule passes when the invocation succeeds) and **VariableCheck**
(compares a `#{variables.name}` reference with `EQ`, `NE`, `CONTAINS`, or `MATCHES`).
`Commands` and `DeployWindow` rules are accepted but pass permissively. Every rule run is
recorded and returned by `ListRuleExecutions`; `ListRuleTypes` returns the AWS rule catalog.

A failed `beforeEntry`/`onSuccess` condition applies its declared `result` — `FAIL` stops
the execution, `SKIP` (entry only) skips the stage. `OverrideStageCondition` marks the
condition overridden and, when the execution failed on exactly that condition, resumes it
from the overridden stage.

`RetryStageExecution` retries the failed stage **in place** on the same execution ID:
`FAILED_ACTIONS` re-runs only the stage's non-succeeded actions, `ALL_ACTIONS` re-runs the
whole stage, then the pipeline continues to the remaining stages.

`RollbackStage` starts a new execution with `executionType: ROLLBACK` and
`rollbackMetadata.rollbackTargetPipelineExecutionId` pointing at the target execution. The
emulator has no per-execution artifact archive, so source-only stages re-run first to seed
input artifacts, then only the rolled-back stage executes — intermediate build/deploy
stages are skipped. A stage declaring `onFailure: {result: ROLLBACK}` rolls back
automatically to the most recent successful execution when it fails.

## Configuration

| Variable | Default | Description |
|---|---|---|
| `FLOCI_SERVICES_CODEPIPELINE_ENABLED` | `true` | Enables the CodePipeline API |
| `FLOCI_STORAGE_SERVICES_CODEPIPELINE_MODE` | global mode | Overrides CodePipeline storage mode |
| `FLOCI_STORAGE_SERVICES_CODEPIPELINE_FLUSH_INTERVAL_MS` | `5000` | Hybrid storage flush interval |

## Example

```bash
aws --endpoint-url http://localhost:4566 codepipeline create-pipeline \
  --pipeline file://pipeline.json

aws --endpoint-url http://localhost:4566 codepipeline start-pipeline-execution \
  --name local-release

aws --endpoint-url http://localhost:4566 codepipeline list-pipeline-executions \
  --pipeline-name local-release
```
