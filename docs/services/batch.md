# AWS Batch

**Protocol:** REST JSON
**Endpoint:** `http://localhost:4566/v1/...`

Floci Batch implements the AWS Batch control plane for local integration tests. It supports queue and job-definition metadata, immediate job completion for fast contract tests, Docker-backed execution when enabled, CloudFormation resource provisioning, and EventBridge rule targets with `BatchParameters`.

## Supported Operations

| Operation | Endpoint | Description |
|---|---|---|
| `CreateComputeEnvironment` | `POST /v1/createcomputeenvironment` | Store a local compute environment and return its ARN |
| `DescribeComputeEnvironments` | `POST /v1/describecomputeenvironments` | Describe all or selected compute environments |
| `UpdateComputeEnvironment` | `POST /v1/updatecomputeenvironment` | Update a compute environment's state, service role, and compute resources |
| `DeleteComputeEnvironment` | `POST /v1/deletecomputeenvironment` | Delete a `DISABLED` compute environment not referenced by any job queue; deleting a missing one is a no-op |
| `CreateJobQueue` | `POST /v1/createjobqueue` | Store a local job queue attached to compute environments |
| `UpdateJobQueue` | `POST /v1/updatejobqueue` | Update a job queue's state, priority, and compute environment order |
| `DeleteJobQueue` | `POST /v1/deletejobqueue` | Delete a `DISABLED` job queue; deleting a missing queue is a no-op |
| `DescribeJobQueues` | `POST /v1/describejobqueues` | Describe all or selected job queues |
| `RegisterJobDefinition` | `POST /v1/registerjobdefinition` | Register a revisioned `container` or `multinode` job definition |
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
- `arrayProperties.size` (array jobs)
- `nodeOverrides.numNodes`, `nodeOverrides.nodePropertyOverrides` (multi-node parallel jobs)
- `timeout.attemptDurationSeconds`
- `retryStrategy.attempts`
- `tags`

`containerOverrides.command` replaces the job-definition command. Command entries like `Ref::inputKey` are resolved from the merged parameter map before execution. Submit-time environment overrides merge over job-definition environment variables. `dependsOn` (job dependencies) is not supported.

Jobs move through these local statuses:

```text
SUBMITTED -> PENDING -> RUNNABLE -> STARTING -> RUNNING -> SUCCEEDED|FAILED
```

Driving every immediate-mode job through `PENDING` is a local simplification for tests. AWS may skip that state when there are no dependency or capacity waits.

### Array jobs

Submitting with `arrayProperties.size` (2-10,000) fans out one child job per index, sharing the
parent's job name and running through the same execution pipeline as a normal job (each child gets
its own Docker container in `docker` mode). The parent's `status` and `arrayProperties.statusSummary`
are computed live from its children on every `DescribeJobs`/`ListJobs` call rather than stored and
updated incrementally, so there is nothing to race when many children finish concurrently. A
queue-level `ListJobs` shows one entry per array job (the parent); pass `arrayJobId` to list its
children. `TerminateJob`/`CancelJob` cascade is not applicable since neither operation is
implemented (see Limitations).

### Multi-node parallel (MNP) jobs

A job definition registered with `"type": "multinode"` and `nodeProperties` (`numNodes`, `mainNode`,
`nodeRangeProperties`) becomes an MNP job definition; `SubmitJob` against it (with optional
`nodeOverrides`) runs one container per node concurrently in `docker` mode, each with
`AWS_BATCH_JOB_NODE_INDEX`, `AWS_BATCH_JOB_MAIN_NODE_INDEX`, and `AWS_BATCH_JOB_NUM_NODES` set. The
job's status, retry decision, and `startedAt`/`stoppedAt`/`statusReason` are all determined solely by
the **main node** (matching AWS: a child node failing does not fail or retry the job). Every node
still runs to completion rather than being stopped early (see Limitations below for the two cases
this affects). `DescribeJobs`/queue-level `ListJobs` return one entry for the whole
job (with `nodeProperties`, no per-node `container`); pass `multiNodeJobId` to `ListJobs` for a
one-entry-per-node breakdown with each node's own `container` and `nodeProperties.nodeIndex`.
`containerOverrides` and array jobs are not supported on MNP job definitions; use `nodeOverrides`
instead.

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
- No job dependencies (`dependsOn`).
- `CancelJob` and `TerminateJob` are not implemented, so there is no way to cancel/terminate an
  array job's children or an MNP job's nodes either — this is the same pre-existing gap as for a
  regular job, not something array/MNP jobs make worse.
- EventBridge input transformers work through the existing EventBridge target input path; full Batch-specific input-transformer parity is not implemented.
- An MNP job does not finish the instant its main node exits. AWS stops the job (and its remaining
  child nodes) as soon as the main node exits; Floci instead waits for every node's container to
  exit before scoring the attempt, so a child that outlives the main node keeps the job `RUNNING`
  longer than AWS would. Closing this needs a way for `BatchDockerRunner` to stop one node's
  container from outside that node's own run loop, which does not exist yet.

**Known follow-ups on the array/MNP surface** (not required for `SubmitJob`/`DescribeJobs`/`ListJobs`
correctness, deliberately deferred):

- `submitFromEventBridge` does not forward `arrayProperties`/`nodeOverrides` from a rule target's
  `BatchParameters`, even though `ArrayProperties` is accepted and echoed back as target metadata
  (see EventBridge section above). An EventBridge-triggered Batch job cannot fan out as an array or
  run as MNP today; only a direct `SubmitJob` call can.
- CloudFormation's `AWS::Batch::JobDefinition` provisioner only registers `container`-type job
  definitions; there is no way to provision a `multinode` job definition via CloudFormation, only
  via a direct `RegisterJobDefinition` call.
- `ListJobs` with `arrayJobId` ignores the `filters` parameter (only `jobStatus` is honored for
  children) — matches AWS's own note that filters don't apply to child jobs, but it means array
  children can't be filtered by `JOB_NAME`/`JOB_DEFINITION`/etc.
- `ListJobs` with `multiNodeJobId` has no pagination (`maxResults`/`nextToken`); it always returns
  every node in one response.
