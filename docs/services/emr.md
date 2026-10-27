# EMR

**Protocol:** JSON 1.1
**Endpoint:** `http://localhost:4566/`
**Target prefix:** `X-Amz-Target: ElasticMapReduce.*`

Floci emulates the Amazon EMR (Elastic MapReduce) management API. Clusters (job flows), instance groups and fleets, steps, security configurations, and tags are tracked in Floci's storage backend so the AWS CLI and SDK clients can drive the full cluster lifecycle locally. EMR does not launch real Hadoop/Spark clusters. Clusters transition through their lifecycle states as a state machine. In the `docker` runner mode Spark steps run for real, each in a container of its own (see [Running Spark steps](#running-spark-steps)).

## Supported Actions

<!-- floci:actions:start -->
| Action | Description |
| --- | --- |
| `RunJobFlow` | Creates a new cluster (job flow) and returns its `JobFlowId` |
| `DescribeCluster` | Returns the configuration and status of a cluster |
| `ListClusters` | Lists clusters, filterable by state and creation time |
| `TerminateJobFlows` | Terminates one or more clusters |
| `SetTerminationProtection` | Enables or disables termination protection |
| `SetVisibleToAllUsers` | Sets cluster visibility for the account |
| `SetKeepJobFlowAliveWhenNoSteps` | Controls auto-termination when steps complete |
| `SetUnhealthyNodeReplacement` | Toggles unhealthy-node replacement |
| `ModifyCluster` | Updates cluster-level settings such as step concurrency |
| `AddJobFlowSteps` | Adds one or more steps to a cluster |
| `DescribeStep` | Returns the detail and status of a single step |
| `ListSteps` | Lists the steps of a cluster, filterable by state |
| `CancelSteps` | Cancels pending steps |
| `AddInstanceGroups` | Adds instance groups to a running cluster |
| `ListInstanceGroups` | Lists the instance groups of a cluster |
| `AddInstanceFleet` | Adds an instance fleet to a cluster |
| `ListInstanceFleets` | Lists the instance fleets of a cluster |
| `ListInstances` | Lists the EC2 instances of a cluster |
| `CreateSecurityConfiguration` | Creates a named security configuration |
| `DescribeSecurityConfiguration` | Returns a security configuration |
| `DeleteSecurityConfiguration` | Deletes a security configuration |
| `ListSecurityConfigurations` | Lists all security configurations |
| `AddTags` | Adds tags to a cluster |
| `RemoveTags` | Removes tags from a cluster |
| `ModifyInstanceGroups` | Resizes or reconfigures instance groups |
| `ModifyInstanceFleet` | Changes an instance fleet's target capacities |
| `ListBootstrapActions` | Lists the bootstrap actions given to `RunJobFlow` |
| `PutManagedScalingPolicy` | Sets a cluster's managed scaling policy |
| `GetManagedScalingPolicy` | Returns a cluster's managed scaling policy |
| `RemoveManagedScalingPolicy` | Removes a cluster's managed scaling policy |
| `PutAutoTerminationPolicy` | Sets a cluster's idle timeout auto termination policy |
| `GetAutoTerminationPolicy` | Returns a cluster's auto termination policy |
| `RemoveAutoTerminationPolicy` | Removes a cluster's auto termination policy |
| `PutAutoScalingPolicy` | Attaches an automatic scaling policy to a core or task instance group |
| `RemoveAutoScalingPolicy` | Removes an instance group's automatic scaling policy |
| `ListReleaseLabels` | List release labels, newest first, by prefix or application; see [Release catalogue](#release-catalogue) |
| `DescribeReleaseLabel` | A release's applications and Amazon Linux releases (the latest release without a label) |
| `ListSupportedInstanceTypes` | The instance types a release supports, with vCPU, memory, storage and architecture |
| `GetBlockPublicAccessConfiguration` | Returns the account's block public access configuration for the Region |
| `PutBlockPublicAccessConfiguration` | Sets the account's block public access configuration for the Region |
<!-- floci:actions:end -->

## Cluster lifecycle

In the default `immediate` runner mode nothing runs, but a cluster and its steps go through the states
of the Management Guide's cluster lifecycle, each held for as long as the [configuration](#configuration)
says. A cluster is `STARTING` for the first half of `cluster-startup-delay-seconds` and `BOOTSTRAPPING`
for the second, then `RUNNING` while it runs steps and `WAITING` once it has none left. A request's
steps are all created `PENDING`, then start in the order they were added, at most `StepConcurrencyLevel`
at a time, each `RUNNING` for `step-duration-seconds`. Steps added while the cluster starts wait for it
to be ready. Raising the level with `ModifyCluster` starts the steps that now fit at once, and lowering
it lets the running ones complete. `TerminateJobFlows` makes a cluster `TERMINATING` for
`cluster-termination-delay-seconds` and then `TERMINATED`, and cancels any step not yet completed, as its
API reference says. Each call finds the clusters it reads as they stand at that moment. With every
duration at its default of 0, a cluster is ready, and its steps complete, before the call that started
them returns.

`CancelSteps` cancels a `PENDING` step, and a `RUNNING` one from release `emr-5.28.0`, as its API
reference says. A cancelled `RUNNING` step makes room for the next `PENDING` one. On an earlier release
a `RUNNING` step answers `FAILED` with "Step is not in a cancellable state: RUNNING".

A cluster created with `Instances.KeepJobFlowAliveWhenNoSteps` set to `false` terminates after its
initial steps or steps added later complete, reporting `ALL_STEPS_COMPLETED`, without waiting first. A
cluster with no steps remains `WAITING` until steps are added. Set `KeepJobFlowAliveWhenNoSteps` to
`true` to keep the cluster `WAITING` after its steps complete. Automatic step-completion termination
also applies when termination protection is enabled.
`AddJobFlowSteps` rejects a `TERMINATING` or `TERMINATED` cluster without adding steps or changing its
termination status, and answers an unknown cluster with `ValidationException` "Specified job flow ID
does not exist.", as AWS does.
Instance groups and fleets report `RUNNING` from the cluster's creation until it is `TERMINATED`. Then
they report `TERMINATED` with zero running or provisioned capacity; their requested and target
capacities remain recorded. `ListInstances` retains each group's instances with their original
synthetic IDs and `TERMINATED` status for 30 days after termination. New groups or fleets are rejected
on terminated clusters. As on AWS, `ModifyInstanceGroups` answers `ValidationException` "An instance
group may only be modified when the cluster is running or waiting." for a cluster in any other state,
and `ModifyInstanceFleet` answers `InvalidRequestException` "A job flow that is shutting down,
terminated, or finished may not be modified." with `ErrorCode` `INVALID_JOB_SHUT_DOWN` for a
`TERMINATING` or `TERMINATED` one, and "An instance fleet may only be modified when the cluster is
running or waiting." with `ErrorCode` `MODIFY_INSTANCE_FLEET_OF_RUNNING_JOBFLOW_ONLY` for one in any
other state, such as `STARTING` or `BOOTSTRAPPING`. `AddInstanceFleet` gives a `TERMINATING` cluster
the "shutting down" answer, and `AddInstanceGroups` gives it as `ValidationException`, as
`AddJobFlowSteps` does. `InstanceStates` filters the modeled `RUNNING` and `TERMINATED`
instances.

`TerminateJobFlows`, the four `Set*` cluster setters and `CancelSteps` refuse a request without a
member their model requires (`JobFlowIds` and the setting, or `StepIds`) with `ValidationException`
"1 validation error detected: Value null at 'jobFlowIds' failed to satisfy constraint: Member must
not be null", AWS's framework wording, as `RunJobFlow` does for `Name`. They used to read a missing
list as empty and a missing setting as its default, and answer success. An empty `JobFlowIds` is
still accepted.

A state machine drives clusters and steps through the `elasticmapreduce:` integrations, `.sync`
included; see [Step Functions: Amazon EMR tasks](step-functions.md#amazon-emr-tasks).

## Running Spark steps

With `runner-mode` set to `docker` (`FLOCI_SERVICES_EMR_RUNNER_MODE=docker`), a Spark step runs. Floci
starts one container per step from the cluster's release image, the one EMR Serverless's `docker` mode
uses (`public.ecr.aws/emr-serverless/spark/<release>`), and runs `spark-submit` in local mode in it: the
container holds the driver and its executors. The cluster itself gets no container, and goes through
its lifecycle as in the `immediate` mode.

* **Which steps run**: a `command-runner.jar` step whose first argument is `spark-submit`, the way the
  Management Guide writes Spark steps, runs the arguments after it, without its `--master` and
  `--deploy-mode`. A custom JAR step runs its `MainClass`, or else the one its manifest names, through
  `spark-submit`, with the JAR and the step's `Args`. Any other step, such as Hive, Pig,
  `script-runner.jar` or another `command-runner.jar` command, is `FAILED` without running.
* **States**: a step is `RUNNING` from when it starts until its driver exits; `step-duration-seconds`
  does not apply. The first step of a release also waits while its image is pulled (3.5 to 4.3 GB
  unpacked; see [`floci.docker.image-pull-timeout-seconds`](../configuration/docker.md)). It is then
  `COMPLETED` on exit code 0, and `FAILED` otherwise. A `FAILED` step's `Status.FailureDetails` has the `Reason`
  "Unknown Error", which the API reference says EMR gives when it cannot find the root cause, and a
  `Message` in Floci's own words, such as "The Spark driver exited with code 1.".
* **ActionOnFailure**: a failed step's action is taken as the API reference and the Management Guide's
  termination protection table give it. `CONTINUE` runs the next step. `TERMINATE_CLUSTER` (or
  `TERMINATE_JOB_FLOW`) ends the cluster, unless it waits after its steps
  (`KeepJobFlowAliveWhenNoSteps`) and is termination protected, when it goes on. `CANCEL_AND_WAIT`
  cancels the `PENDING` steps and leaves the cluster `WAITING`, or ends it when it is set to end after
  its steps. A cluster a step failure ends cancels its other steps and goes `TERMINATING`, then
  `TERMINATED_WITH_ERRORS`, with the reason `STEP_FAILURE` and the message "Shut down as step failed".
  While `StepConcurrencyLevel` is above 1 no action is taken, and with level 1 and other steps still
  running `CANCEL_AND_WAIT` is not either, as the guide says.
* **Cancelling**: `CancelSteps` on a `RUNNING` step, and `TerminateJobFlows`, stop its driver at once; a
  cancelled step takes no `ActionOnFailure`. A step Floci was running when it stopped is `FAILED` once
  Floci is back, and its `ActionOnFailure` is taken then. Floci's shutdown and a state reset stop every
  driver, and start no other step; a driver a hard stop left running is removed when the EMR service
  starts, before any step can start, unless `reconcile-containers-on-startup` is off.
* **What `spark-submit` gets**: the step's arguments, after the `spark-defaults` properties of the
  cluster's `Configurations`. `s3://` and `s3a://` paths reach Floci's S3 through S3A, on releases
  before 7.10.0 too, where `s3://` would otherwise be EMRFS, so the application and its files can be read
  from there; the Glue Data Catalog client is pointed at Floci's Glue. A step's `Properties` are not
  applied.
* **Credentials**: the container gets a session for the step's `ExecutionRoleArn`, or else for the role
  of the cluster's `JobFlowRole` instance profile, when IAM knows it and the profile holds one role,
  and otherwise the account's placeholder credentials, with `AWS_ENDPOINT_URL` pointing at Floci.
* **Output**: the driver's output goes to Floci's log, prefixed `emr:<stepId>`. With a `LogUri` on the
  cluster, its standard output and error also go to Floci's S3 once the step ends, gzipped, as
  `stdout.gz` and `stderr.gz` under `<LogUri>/<cluster-id>/steps/<step-id>/`, the step log folder of the
  Management Guide's "Log locations" (an `s3n://` or `s3a://` `LogUri` names the same bucket). A
  cancelled step's logs hold what its driver wrote until it was stopped. Logs that cannot be written,
  to a bucket that does not exist say, leave the step as it ended. The `controller` and `syslog` logs
  and the other log folders are not written, nothing is copied while a step runs (EMR copies its logs
  every five minutes), and `FailureDetails` has no `LogFile`.
* **Image**: `image-template` names another image, such as a mirror or a dated tag of the release's
  image: `registry.internal/emr-serverless/spark/%s:20250425`. A release with no EMR Serverless image
  fails its steps with "The step could not start: Could not pull image".

## EventBridge events

Every state a cluster or a step enters is sent to the default EventBridge bus of its Region, in the
account that made the call, as AWS sends it: source `aws.emr`, detail type `EMR Cluster State Change`
or `EMR Step Status Change`, an empty `resources`, and the detail of the EMR Management Guide's
examples. A cluster's detail holds `severity`, `stateChangeReason`, `name`, `clusterId`, `state` and
`message`; a step's holds `severity`, `actionOnFailure`, `stepId`, `name`, `clusterId`, `state` and
`message`. The severity and message are the ones the guide's "Monitoring Amazon EMR events" tables
give each state, with times to the minute (`2026-10-24 09:41 UTC`).

Each change sends its events:

| Change | Events, in order |
|---|---|
| `RunJobFlow` | the cluster `STARTING`, then each step `PENDING` |
| `AddJobFlowSteps` | each step `PENDING` |
| The cluster is ready, or a step completes | the step `COMPLETED`; each step it makes room for `RUNNING`; once none is left, the cluster `WAITING`, or `TERMINATED` with `ALL_STEPS_COMPLETED` when it ends after its steps |
| A step fails (`docker` runner mode) | the step `FAILED`; then as its `ActionOnFailure` goes: as a completed step, or `CANCELLED` for each step it cancels and the cluster `WAITING`, or `CANCELLED` for each step not yet completed and the cluster `TERMINATED_WITH_ERRORS` with `STEP_FAILURE` |
| `TerminateJobFlows` | `CANCELLED` for each step not yet completed; the cluster `TERMINATED` with `USER_REQUEST` once it has been `TERMINATING` for the delay, also when a protected cluster fails the call |
| `CancelSteps` | `CANCELLED` for each step it cancels, and `RUNNING` for the step a cancelled `RUNNING` one makes room for |

A change that falls due between calls, such as a step completing after `step-duration-seconds`, is
made at that time and its events sent then, whether or not anything reads the cluster.

A `STARTING` or `WAITING` cluster's `stateChangeReason` is `{"code":""}`, as in the guide's example,
and a terminated one's carries the reason's code and message, such as
`{"code":"USER_REQUEST","message":"Terminated by user request"}`. `TERMINATED` is `INFO` for
`USER_REQUEST` and `ALL_STEPS_COMPLETED` and `CRITICAL` for any other reason, and
`TERMINATED_WITH_ERRORS` is `CRITICAL`; a cancelled step is `WARN` and a failed one `ERROR`. The tables have no row for a cluster's `BOOTSTRAPPING`, `RUNNING` or `TERMINATING`, which
send nothing, so a `WAITING` cluster that runs added steps sends `WAITING` again when they are done.
The guide gives two `WAITING` messages without saying when each applies: a cluster that ran steps
reports it "finished running all pending steps", one that did not that it "was created ... and is
ready for use".

Events go out in the order their changes were made, once the call that made them no longer holds the
cluster. A rule's target that calls EMR while an event is delivered, such as one ending the cluster on
its `STARTING` event, has its events sent after the rest of that call's.

## Configuration

| Variable | Default | Description |
|---|---|---|
| `FLOCI_SERVICES_EMR_ENABLED` | `true` | Enable or disable the service |
| `FLOCI_SERVICES_EMR_DEFAULT_RELEASE_LABEL` | `emr-7.5.0` | The release label of a cluster created without one; see [Release catalogue](#release-catalogue) |
| `FLOCI_SERVICES_EMR_CLUSTER_STARTUP_DELAY_SECONDS` | `0` | Seconds a new cluster takes to be ready to run steps, the first half `STARTING` and the second `BOOTSTRAPPING`; `0` makes it ready as soon as it is created |
| `FLOCI_SERVICES_EMR_STEP_DURATION_SECONDS` | `0` | Seconds a step stays `RUNNING` before it completes; `0` completes it as soon as it starts |
| `FLOCI_SERVICES_EMR_CLUSTER_TERMINATION_DELAY_SECONDS` | `0` | Seconds a cluster stays `TERMINATING` before it is `TERMINATED`; `0` ends it at once |
| `FLOCI_SERVICES_EMR_RUNNER_MODE` | `immediate` | `immediate` completes steps by time without running anything; `docker` runs each Spark step in a container (see [Running Spark steps](#running-spark-steps)) |
| `FLOCI_SERVICES_EMR_DOCKER_NETWORK` | the global default | Docker network for step containers |
| `FLOCI_SERVICES_EMR_IMAGE_TEMPLATE` | `public.ecr.aws/emr-serverless/spark/%s:latest`, or `spark4/` in place of `spark/` for an `emr-spark-*` release | Image for a `docker` step, `%s` standing for the cluster's release label |
| `FLOCI_SERVICES_EMR_RECONCILE_CONTAINERS_ON_STARTUP` | `true` | Remove the step drivers a previous run of this Floci left on the Docker daemon when the EMR service starts; turn it off when two Floci instances share a daemon with the same port and no resource namespace |

## Release catalogue

`ListReleaseLabels`, `DescribeReleaseLabel` and `ListSupportedInstanceTypes` read a curated catalogue,
not AWS's full list: `emr-7.5.0` (Floci's default), `emr-7.4.0`, `emr-6.15.0`, `emr-6.14.0` and
`emr-5.36.2`. Each release's applications and versions come from its page in the Amazon EMR Release
Guide, with names lowercased and AWS's `-amzn-N` build suffix dropped, as the API reports them, and
its two most recent Amazon Linux releases. A `floci.services.emr.default-release-label` outside the
catalogue is still listed, with no applications.

`ListReleaseLabels` filters by `Prefix` (such as `emr-6`) and `Application` (`spark` or
`spark@3.4.1`, matched without regard to case) and pages with `MaxResults` (1 to 100) /
`NextToken`; a token only works with the filters it was issued for. `DescribeReleaseLabel` answers
every application at once, since AWS reserves its `MaxResults` and `NextToken` for future use.
`ListSupportedInstanceTypes` reports common families (c5, m5, m5d, m6g, r5, r6g) with
their EC2 sizes. An unknown release label, a bad token or an out-of-range `MaxResults` is
`InvalidRequestException`. `RunJobFlow` does not check its release label or instance types against
the catalogue.

## Example

```bash
export AWS_ENDPOINT_URL=http://localhost:4566

# Create a cluster
CLUSTER_ID=$(aws emr run-job-flow \
  --name "demo-cluster" \
  --release-label emr-7.0.0 \
  --instances KeepJobFlowAliveWhenNoSteps=true,InstanceGroups='[{InstanceCount=1,InstanceGroupType=MASTER,InstanceType=m5.xlarge}]' \
  --query 'JobFlowId' --output text)

# Inspect it
aws emr describe-cluster --cluster-id "$CLUSTER_ID"
aws emr list-clusters

# Add a step
aws emr add-steps --cluster-id "$CLUSTER_ID" \
  --steps Type=CUSTOM_JAR,Name=demo,Jar=command-runner.jar,Args=[echo,hello]

# Terminate
aws emr terminate-clusters --cluster-ids "$CLUSTER_ID"
```

`aws emr add-steps --execution-role-arn` applies the role to every step in that request.
`RunJobFlow` also applies `StepExecutionRoleArn` to its initial steps. `describe-step`
includes `ExecutionRoleArn` for those steps; `list-steps` returns step summaries without it.
