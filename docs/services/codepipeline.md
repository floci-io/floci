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
| Build/Test | CodeBuild | Starts and monitors the configured local CodeBuild project |
| Deploy | S3 | Writes the input artifact to the configured bucket and key |
| Deploy | CodeDeploy | Starts and monitors a local CodeDeploy deployment |
| Invoke | Lambda | Invokes the configured local Lambda function |
| Invoke | CodePipeline | Starts a nested local pipeline execution |
| Approval | Manual | Waits for `PutApprovalResult` |
| Custom/third-party | Any registered action | Uses poll, acknowledge, success, and failure job APIs |

AWS-managed providers without a corresponding Floci execution adapter fail the action with an
AWS-shaped action error. Floci does not call real AWS accounts or third-party SaaS providers.

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
