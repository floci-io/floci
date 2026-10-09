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
A `QUEUED` or `PARALLEL` pipeline holds at most 50 active executions, as on AWS;
`StartPipelineExecution` beyond that returns `ConcurrentPipelineExecutionsLimitExceededException`.

S3 source actions poll for source changes by default, matching AWS when `PollForSourceChanges` is
omitted or set to `true`. Floci establishes a baseline for the configured object and starts one new
execution when its version ID or ETag changes, recording `PollForSourceChanges` as the execution
trigger. Set `PollForSourceChanges` to `false` to disable this polling path.

`RetryStageExecution` resumes the same pipeline execution at a failed stage. `FAILED_ACTIONS`
reruns failed or not-yet-started actions while preserving successful actions; `ALL_ACTIONS` reruns
the complete stage. Runtime artifacts from failed executions are retained for the retry until a newer
execution fails the same stage, and a successful retry continues with the stages that follow rather
than restarting from the source.
A retried execution counts toward the `QUEUED`/`PARALLEL` active-execution limit, and a second retry of
the same execution while it is still running, including while sibling actions of a failed action
finish, returns `ConflictException`.

S3 source actions resolve `sourceRevisions` from the object actually consumed by the execution.
Unversioned objects report their ETag as the revision ID; versioned objects report the version ID.
`StartPipelineExecution.sourceRevisions` is treated as an override, including supported S3 object-key
and version-ID overrides, rather than being copied directly into execution history.
Resolved revisions appear in `ListPipelineExecutions` summaries, as on AWS. `RollbackStage` reruns
with the object key and version ID the target execution consumed, so rolling back redeploys that
version rather than the latest upload.

`RollbackStage` starts a new execution with `executionType: ROLLBACK` and
`rollbackMetadata.rollbackTargetPipelineExecutionId` pointing at the target execution. The stage must
have succeeded in the target execution (judged by the stage status, so a stage whose `onSuccess` condition failed does not qualify), and the pipeline version must not have changed since that execution ran; otherwise the call fails with `UnableToRollbackStageException`. The execution's `trigger.triggerType` is `ManualRollback`. GitHub source actions are pinned to the commit the target execution resolved.
Other rejections: `ValidationException` when the pipeline is `PARALLEL` (AWS does not support stage rollback in that execution mode), `UnableToRollbackStageException` for a source stage, a rollback execution as the target, or a stage that is currently running (including one being rolled back that is still fetching its sources), `StageNotFoundException` for an unknown stage, and `ConflictException` when the pipeline has no free execution slot. The running check and the new execution are registered under the pipeline start lock.
The emulator has no per-execution artifact archive, so source-only stages re-run first to seed input
artifacts, then only the rolled-back stage executes; intermediate stages are skipped.

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

## V2 stage conditions

V2 stage condition blocks (`beforeEntry` and `onSuccess`) are evaluated during execution. Two rule providers evaluate for real: **LambdaInvoke** (invokes the configured local Lambda function; the rule passes when the invocation succeeds) and **VariableCheck** (compares a `#{variables.name}` reference with `EQ`, `NE`, `CONTAINS`, or `MATCHES`; `MATCHES` rejects a pattern or value longer than 256 characters). `Commands`, `DeploymentWindow` and `CloudWatchAlarm` rules are accepted but pass permissively. A `VariableCheck` reference resolves against the start request's variables and the pipeline's declared defaults; a reference that cannot be resolved (including action output variables) fails the rule. Every rule run is recorded on the execution and returned by `ListRuleExecutions`; `ListRuleTypes` returns the AWS rule catalog.

A failed `beforeEntry` or `onSuccess` condition applies its declared `result`: `FAIL` (the default) stops the execution, and `SKIP` skips the stage when it is on a `beforeEntry` condition. A failed `onSuccess` condition always fails the stage, because the stage has already run. `OverrideStageCondition` marks the condition overridden and, when the execution failed on exactly that condition, resumes it from the overridden stage. `onFailure` conditions and the `ROLLBACK` result are not acted on; a stage failure is recorded as a plain failure.

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
the approval token, `expires` as an ISO-8601 timestamp, `consoleLink`, `approvalReviewLink`,
`CustomData` and `ExternalEntityLink`) to that SNS topic when it starts waiting. The subject is truncated to SNS's 100-character limit.

## Configuration

| Variable | Default | Description |
|---|---|---|
| `FLOCI_SERVICES_CODEPIPELINE_ENABLED` | `true` | Enables the CodePipeline API |
| `FLOCI_STORAGE_SERVICES_CODEPIPELINE_MODE` | global mode | Overrides CodePipeline storage mode |
| `FLOCI_STORAGE_SERVICES_CODEPIPELINE_FLUSH_INTERVAL_MS` | `5000` | Hybrid storage flush interval |
| `FLOCI_SERVICES_CODEPIPELINE_SOURCE_POLL_INTERVAL_MS` | `500` | How often (ms) S3 sources are polled for a new object revision |

## Example

```bash
aws --endpoint-url http://localhost:4566 codepipeline create-pipeline \
  --pipeline file://pipeline.json

aws --endpoint-url http://localhost:4566 codepipeline start-pipeline-execution \
  --name local-release

aws --endpoint-url http://localhost:4566 codepipeline list-pipeline-executions \
  --pipeline-name local-release
```
