# AWS Batch

**Protocol:** REST JSON
**Endpoint:** `http://localhost:4566/v1/...`

Floci Batch implements the AWS Batch control plane for local integration tests. It supports queue and job-definition metadata, immediate job completion for fast contract tests, Docker-backed execution when enabled, CloudFormation resource provisioning, and EventBridge rule targets with `BatchParameters`.

## Supported Operations

| Operation | Endpoint | Description |
|---|---|---|
| `CreateComputeEnvironment` | `POST /v1/createcomputeenvironment` | Store a local compute environment and return its ARN |
| `DescribeComputeEnvironments` | `POST /v1/describecomputeenvironments` | Describe all or selected compute environments |
| `CreateJobQueue` | `POST /v1/createjobqueue` | Store a local job queue attached to compute environments |
| `DescribeJobQueues` | `POST /v1/describejobqueues` | Describe all or selected job queues |
| `RegisterJobDefinition` | `POST /v1/registerjobdefinition` | Register a revisioned container job definition |
| `DeregisterJobDefinition` | `POST /v1/deregisterjobdefinition` | Mark a job definition revision inactive |
| `DescribeJobDefinitions` | `POST /v1/describejobdefinitions` | List job definitions by name, ARN, revision, and status |
| `SubmitJob` | `POST /v1/submitjob` | Submit a local Batch job |
| `DescribeJobs` | `POST /v1/describejobs` | Describe jobs by job ID |
| `ListJobs` | `POST /v1/listjobs` | List jobs by queue, status, AWS `filters`, and pagination |

## Runner Modes

Batch uses `floci.services.batch.runner-mode`.

| Value | Behavior |
|---|---|
| `immediate` | Default. `SubmitJob` persists the job, records lifecycle timestamps, creates one successful attempt, and returns after the job is `SUCCEEDED`. |
| `docker` | Starts one Docker container per attempt from the job-definition image, passes resolved command and environment values, applies `MEMORY` resource requirements as Docker memory limits, captures a CloudWatch Logs stream name, and sets `SUCCEEDED` or `FAILED` from the container exit code. Timed-out jobs fail without retry, matching AWS Batch timeout behavior. |

`process` mode is not implemented.

## Submit Behavior

Supported `SubmitJob` fields:

- `jobName`
- `jobDefinition`
- `jobQueue`
- `parameters`
- `containerOverrides.command`
- `containerOverrides.environment`
- `timeout.attemptDurationSeconds`
- `retryStrategy.attempts`
- `tags`

`containerOverrides.command` replaces the job-definition command. Command entries like `Ref::inputKey` are resolved from the merged parameter map before execution. Submit-time environment overrides merge over job-definition environment variables.

Jobs move through these local statuses:

```text
SUBMITTED -> PENDING -> RUNNABLE -> STARTING -> RUNNING -> SUCCEEDED|FAILED
```

Driving every immediate-mode job through `PENDING` is a local simplification for tests. AWS may skip that state when there are no dependency or capacity waits.

## EventBridge

EventBridge targets whose ARN points at a Batch job queue can include:

```json
{
  "BatchParameters": {
    "JobDefinition": "my-job:1",
    "JobName": "nightly-job",
    "ArrayProperties": {"Size": 2},
    "RetryStrategy": {"Attempts": 2}
  }
}
```

When the rule fires, Floci submits an equivalent Batch job to the target queue. If the target payload contains a root `Parameters` object, those key/value pairs are stringified and passed as Batch submit parameters. Flat payload fields are not converted into Batch parameters.

`ArrayProperties` is accepted and returned as target metadata for local deployment compatibility, but Batch still submits one local job and does not fan out array children.

## CloudFormation

Floci provisions these resource types:

- `AWS::Batch::ComputeEnvironment`
- `AWS::Batch::JobQueue`
- `AWS::Batch::JobDefinition`

IAM roles, VPC fields, Fargate declarations, log configuration, storage, and resource requirements are accepted as metadata. Docker mode applies `MEMORY` requirements as container memory limits; local scheduling does not simulate AWS capacity, VCPU allocation, or VPC networking.

## Configuration

| Variable | Default | Description |
|---|---|---|
| `FLOCI_SERVICES_BATCH_ENABLED` | `true` | Enable or disable Batch |
| `FLOCI_SERVICES_BATCH_RUNNER_MODE` | `immediate` | `immediate` or `docker` |
| `FLOCI_SERVICES_BATCH_DOCKER_NETWORK` | *(unset)* | Docker network for Batch containers |
| `FLOCI_STORAGE_SERVICES_BATCH_MODE` | *(inherits global)* | Optional storage mode override |
| `FLOCI_STORAGE_SERVICES_BATCH_FLUSH_INTERVAL_MS` | `5000` | Persistent storage flush interval |

## Limitations

- No IAM enforcement.
- No VPC/subnet/security-group simulation.
- No AWS-faithful capacity scheduling.
- No array job fan-out or multi-node jobs.
- `CancelJob` and `TerminateJob` are not implemented.
- EventBridge input transformers work through the existing EventBridge target input path; full Batch-specific input-transformer parity is not implemented.
