# EMR

**Protocol:** JSON 1.1
**Endpoint:** `http://localhost:4566/`
**Target prefix:** `X-Amz-Target: ElasticMapReduce.*`

Floci emulates the Amazon EMR (Elastic MapReduce) management API. Clusters (job flows), instance groups and fleets, steps, Spark Connect sessions, EMR Studios and their session mappings, notebook executions, security configurations, and tags are tracked in Floci's storage backend so the AWS CLI and SDK clients can drive the full cluster lifecycle locally. EMR does not launch real Hadoop/Spark clusters. Clusters transition through their lifecycle states as a state machine. In the `docker` runner mode Spark steps, commands and scripts run for real, each in a container of its own, after the cluster's bootstrap actions (see [Running steps](#running-steps)).

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
| `StartSession` | Starts a Spark Connect session on a cluster with sessions enabled; see [Sessions](#sessions) |
| `GetSession` | Returns a session's state, times and configuration |
| `ListSessions` | Lists a cluster's sessions, newest first, filterable by state |
| `TerminateSession` | Terminates a session that has not ended |
| `GetSessionEndpoint` | Returns a session's endpoint and an authentication token valid for an hour |
| `CreateStudio` | Creates an EMR Studio in a VPC of Floci's EC2; see [Studios](#studios) |
| `DescribeStudio` | Returns a Studio's configuration |
| `ListStudios` | Lists the Region's Studios |
| `UpdateStudio` | Changes a Studio's name, description, subnets, default S3 location or encryption key |
| `DeleteStudio` | Deletes a Studio and its session mappings |
| `CreateStudioSessionMapping` | Maps an IAM Identity Center user or group to a Studio, with a session policy |
| `GetStudioSessionMapping` | Returns a user's or group's session mapping |
| `ListStudioSessionMappings` | Lists the session mappings of a Studio, or of every Studio |
| `UpdateStudioSessionMapping` | Changes a mapped user's or group's session policy |
| `DeleteStudioSessionMapping` | Removes a user or group from a Studio |
| `StartNotebookExecution` | Starts a notebook execution on a cluster; see [Notebook executions](#notebook-executions) |
| `DescribeNotebookExecution` | Returns a notebook execution's status, times and configuration |
| `StopNotebookExecution` | Stops a notebook execution that has not ended |
| `ListNotebookExecutions` | Lists notebook executions, newest first, by editor, status, time and cluster |
| `CreatePersistentAppUI` | Makes a cluster's persistent application UIs; see [Application UIs](#application-uis) |
| `DescribePersistentAppUI` | Returns a persistent application UI's types and status |
| `GetPersistentAppUIPresignedURL` | Returns a presigned URL to a persistent application UI, a page of Floci's |
| `GetOnClusterAppUIPresignedURL` | Returns a presigned URL to a running cluster's on-cluster UI, which opens for real in the `docker` mode; see [Application UIs](#application-uis) |
| `GetClusterSessionCredentials` | Returns temporary basic credentials for a runtime role enabled cluster; see [Application UIs](#application-uis) |
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

`CancelSteps` cancels a `PENDING` step, and a `RUNNING` one from release `emr-5.28.0`, as its API reference says,
adding that it "is idempotent but asynchronous". On an earlier release a `RUNNING` step answers `FAILED` with "Step
is not in a cancellable state: RUNNING".

The limits the API reference and the Management Guide give steps are checked, in each operation's own error code, in
Floci's words, as no source gives AWS's: a `StepConcurrencyLevel` from 1 to 256 ("You can specify a minimum of 1 step
and a maximum of 256 steps"), which `RunJobFlow` refuses with `ValidationException` and `ModifyCluster` with
`InvalidRequestException`; at most 256 steps `PENDING` or `RUNNING` ("only 256 steps can be RUNNING or PENDING at any
given time", a `CANCEL_PENDING` one counted as running), which `RunJobFlow` and `AddJobFlowSteps` refuse with
`ValidationException`; no step with an `ActionOnFailure` other than `CONTINUE` added while the level is above 1 ("The
step is not submitted and the action fails with a message that the ActionOnFailure setting is not valid"), which
`AddJobFlowSteps` refuses with `ValidationException`, a step naming none taking `TERMINATE_CLUSTER`, as it is
submitted, and so refused too; and `CancelSteps` "Available only
in Amazon EMR versions 4.8.0 and later, excluding version 5.0.0", with at most 256 steps a request, refused otherwise
with `InvalidRequestException`.

In the `docker` runner mode a cancelled `RUNNING` step is `CANCEL_PENDING` while its driver stops, and keeps its
place among the running steps until the driver is gone: then it is `CANCELLED`, and the next `PENDING` step starts.
That is Floci's reading of the Management Guide's "Canceling a running step does not allow a pending step to start
running, assuming no change to stepConcurrencyLevel", beside "Canceling a running or pending step removes that step
from the active step count"; its step events have a `CANCEL_PENDING` row, "was cancelled at Time and is pending
cancellation". Cancelling a `CANCEL_PENDING` step again answers `SUBMITTED`, and the step is `CANCELLED` however
its driver ends. The step's container stops, and what the applications it started on YARN have logged goes to
`containers/` (see **Output**), while an application in `--deploy-mode cluster` runs on to its end: EMR's cancel
signals the step and its descendant processes, and "asynchronous processes are not affected", as the Management
Guide puts it. In the `immediate` mode nothing runs, so a cancelled `RUNNING` step is `CANCELLED` at once and makes
room for the next.

A cluster created with `Instances.KeepJobFlowAliveWhenNoSteps` set to `false` terminates after its
initial steps or steps added later complete, reporting `ALL_STEPS_COMPLETED`, without waiting first. A
cluster with no steps remains `WAITING` until steps are added. Set `KeepJobFlowAliveWhenNoSteps` to
`true` to keep the cluster `WAITING` after its steps complete. Automatic step-completion termination
also applies when termination protection is enabled.
`AddJobFlowSteps` rejects a `TERMINATING` or `TERMINATED` cluster without adding steps or changing its
termination status, and answers an unknown cluster with `ValidationException` "Specified job flow ID
does not exist.", as AWS does.
Instance groups and fleets follow their cluster's state, Floci's reading, as no source says when theirs apply:
`PROVISIONING` while it is `STARTING`, with nothing running or provisioned yet (the guide's events end an instance
fleet's provisioning with "Provisioning for instance fleet ... is complete ... The instance fleet now has On-Demand
capacity of Num"), `BOOTSTRAPPING` while it is, with every instance up for its bootstrap actions, `RUNNING` while it
runs or waits, and `TERMINATING` as it ends; one added later takes the state the cluster's others are in. Once it is
`TERMINATED` they report `TERMINATED` with zero running or provisioned capacity; their requested and target
capacities remain recorded, and a group that ended while it was provisioned counts the instances it asked for as
terminated, which `ListInstances` lists so, as an ended fleet's targets are. `ListInstances` gives each instance
its group's or fleet's state, `PROVISIONING`,
`BOOTSTRAPPING`, `RUNNING` (while its cluster ends too) or `TERMINATED`, lists one being provisioned though none runs
yet, and its `InstanceStates` filter keeps the instances in the states it names. A cluster terminated while it is
`STARTING` keeps the instances it was provisioning listed, still `PROVISIONING` and with none of them running, until
it is `TERMINATED`, so the same instances are listed throughout. `ListInstances` numbers the
synthetic IDs and addresses of the `MASTER`
group's or fleet's instance first, so the primary node keeps `i-00000000000000001` and `10.0.0.1`, as
`DescribeCluster`'s `MasterPublicDnsName` says, while the others resize, and every other instance's private
address is `10.<group>.0.<n>`, its group's or fleet's number in the cluster and its own in it, so it too stays while
another resizes. An instance fleet has an instance for each unit of its capacity, its on-demand ones first, each of the
first instance type in its `InstanceTypeConfigs`. `InstanceGroupId` and `InstanceGroupTypes` keep instance groups'
instances, `InstanceFleetId` and `InstanceFleetType` instance fleets', and a call's filters all apply together.
`ListInstances` retains each group's or fleet's
instances with their original synthetic IDs and `TERMINATED` status for 30 days after termination. New groups or fleets are rejected
on terminated clusters. As on AWS, `ModifyInstanceGroups` answers `ValidationException` "An instance
group may only be modified when the cluster is running or waiting." for a cluster in any other state,
and `ModifyInstanceFleet` answers `InvalidRequestException` "A job flow that is shutting down,
terminated, or finished may not be modified." with `ErrorCode` `INVALID_JOB_SHUT_DOWN` for a
`TERMINATING` or `TERMINATED` one, and "An instance fleet may only be modified when the cluster is
running or waiting." with `ErrorCode` `MODIFY_INSTANCE_FLEET_OF_RUNNING_JOBFLOW_ONLY` for one in any
other state, such as `STARTING` or `BOOTSTRAPPING`. `AddInstanceFleet` gives a `TERMINATING` or
`TERMINATED` cluster the "shutting down" answer, and `AddInstanceGroups` gives it as
`ValidationException`, as `AddJobFlowSteps` does. `InstanceStates` filters the modeled `RUNNING` and
`TERMINATED` instances. As `ListInstances` "Provides information for all active Amazon EC2 instances and Amazon EC2
instances terminated in the last 30 days, up to a maximum of 2,000", a call lists at most 2,000 and gives a `Marker`
for the next; a `Marker` no call gave is refused with `InvalidRequestException`.
A cluster's `SecurityConfiguration` names one `CreateSecurityConfiguration` made, and `DescribeCluster` gives it back.
`RunJobFlow` naming one that does not exist is refused with `ValidationException` "Security configuration does not
exist: <name>"; no source gives AWS's answer, so the code is the one `RunJobFlow` refuses its other input with, and the
wording that of `DescribeSecurityConfiguration` and `DeleteSecurityConfiguration`.
A cluster has instance groups or instance fleets, never both, as "instance fleets and instance groups cannot coexist in a
cluster": `RunJobFlow` with both is refused with `ValidationException` "The RunJobFlow request can contain
InstanceFleets parameters or InstanceGroups parameters, but not both.", the API reference's own words, and
`AddInstanceGroups` on a cluster with instance fleets with `ValidationException`, `AddInstanceFleet` on one with
instance groups with `InvalidRequestException`, each saying the cluster uses the other kind; AWS's wording for these is
not documented, so it is Floci's. A fleet without its `InstanceFleetType`, or with an `InstanceTypeConfigs` member
without its `InstanceType`, both required, is refused with the framework's `ValidationException`.
`ListInstanceFleets` gives each fleet's `InstanceTypeConfigs` back as its `InstanceTypeSpecifications`, with a
`WeightedCapacity` of 1 where a config sets none and a `BidPriceAsPercentageOfOnDemandPrice` of 100 where it sets
neither bid price, the defaults the API reference gives, and its `EbsConfiguration` as one `EbsBlockDevice` per volume.
The reference names no devices, so Floci names them `/dev/sdb`, `/dev/sdc` and on. A config attaching more than 25
volumes to an instance is refused with `ValidationException` "There is a limit of 25 volumes per instance.", the
Management Guide's own limit and sentence; AWS's error for it is not documented. `ModifyInstanceFleet` changes a
fleet's target capacities only; the `InstanceTypeConfigs` it takes are not applied.

A cluster that is `TERMINATING`, `TERMINATED` or `TERMINATED_WITH_ERRORS` also refuses `ModifyCluster`,
the four `Set*` setters, and `Put` and `Remove` of its managed scaling, auto-termination and automatic
scaling policies, with the same "shutting down" message: `ModifyCluster` as `InvalidRequestException`
with `ErrorCode` `INVALID_JOB_SHUT_DOWN`, since its model declares that exception, and the others as
`ValidationException`, since theirs declare none. `GetManagedScalingPolicy` gives such a cluster that
`ValidationException` in place of its policy, as AWS was recorded doing, and Terraform's provider expects the
same answer from `RemoveManagedScalingPolicy`. For the other operations, which AWS documents for running
clusters, no answer for an ended one is recorded, so theirs is Floci's reading, taken from the operations
whose answer is. A `Set*` request naming such a cluster changes none of the clusters it names. `AddTags` and
`RemoveTags` refuse a `TERMINATED` or `TERMINATED_WITH_ERRORS` cluster, as the Management Guide says "you
cannot add, edit, or remove tags from a terminated cluster", with `InvalidRequestException` "Tags cannot
be modified on terminated clusters." The guide names no error: the message is the one a tagging tool
matches from AWS, and the code is Floci's reading.

`TerminateJobFlows`, the four `Set*` cluster setters and `CancelSteps` refuse a request without a
member their model requires (`JobFlowIds` and the setting, or `StepIds`) with `ValidationException`
"1 validation error detected: Value null at 'jobFlowIds' failed to satisfy constraint: Member must
not be null", AWS's framework wording, as `RunJobFlow` does for `Name`. They used to read a missing
list as empty and a missing setting as its default, and answer success. An empty `JobFlowIds` is
still accepted.

A state machine drives clusters and steps through the `elasticmapreduce:` integrations, `.sync`
included; see [Step Functions: Amazon EMR tasks](step-functions.md#amazon-emr-tasks).

## Sessions

A session is "a managed connection between your local PySpark client and a Spark Connect Server running on your Amazon
EMR cluster" (Management Guide, "Interactive sessions"). With `runner-mode` set to `docker`, Floci runs that server on
the session's cluster, and a client reaches it through Floci (see [Running a session's Spark Connect
server](#running-a-sessions-spark-connect-server)). With the default `immediate` mode, Floci keeps a session and its
lifecycle on its cluster but runs no server for it.

* **Starting**: `StartSession` needs a cluster created with `SessionEnabled` (which `DescribeCluster` reports), on
  "release emr-spark-8.0.0 or later", with "The Spark application installed on the cluster", that is `RUNNING` or
  `WAITING`. A cluster with more than one primary node is refused, as "High Availability (HA) clusters with multiple
  primary nodes are not supported for Spark Connect sessions", and so is a session past a cluster's 1000 that have not
  ended ("Amazon EMR enforces a limit of 1000 concurrent active sessions per cluster"). A request without a `Name` is
  refused, as "The `--name` parameter is required and must be a non-empty value", and so is a
  `SessionIdleTimeoutInMinutes` outside 1 to 1440, as the idle timeout has "a maximum of 24 hours". Each of these
  refusals is `InvalidRequestException`, the one error the model declares, in Floci's own wording, as AWS's is not
  recorded. A member of the wrong JSON type, such as `EngineConfigurations` that is not a list of configurations,
  `Properties` that are not strings, a `MonitoringConfiguration` member that is not a structure, or a `ClusterId`
  that is not a string, is refused with `SerializationException`, and the model's length and pattern constraints with
  `ValidationException`. A retry with the same `ClientRequestToken` that asks for the same session answers as the
  first call did, a member left out, empty or at its default counting as the same; the token reused for a different
  session is refused with `InvalidRequestException`.
* **States**: `StartSession` answers `SUBMITTED`, as "When a session is first created, it enters the SUBMITTED state".
  In the `immediate` mode the session is `IDLE` from then on, with `StartedAt` and `IdleSince` its creation time; in
  the `docker` mode it goes through `STARTING` and `IDLE`, then `BUSY` and back (see below). It is `TERMINATED` once
  it has been idle for its `SessionIdleTimeoutInMinutes` (60 when not given: "The default timeout is 60 minutes"), when
  `TerminateSession` ends it, or when its cluster shuts down, its `StateChangeReason` saying which. Floci never reports
  `STARTED`.
* **TerminateSession** ends a session with no server at once as `TERMINATED`; one whose server runs is `TERMINATING`
  until its server has stopped, as "the session enters the TERMINATING state and then transitions to TERMINATED". A
  session that has already ended is refused with `InvalidRequestException`, in Floci's own wording.
* **GetSessionEndpoint** answers for a session that has not ended with Floci's own address as its endpoint, such as
  `http://localhost:4566`, and a fresh `AuthToken` that expires in an hour, as "Authentication tokens expire after 1
  hour". AWS's endpoint is an `https://` URL per session without a port; a Spark Connect URL holds no path, so Floci's
  is its address alone, port included, and the token names the session. No `Credentials` are given. A session that has
  ended, or is `TERMINATING`, is refused with `InvalidRequestException`, in Floci's own wording.
* **ListSessions** returns the newest sessions first, as "Newer sessions are returned first", the later of two started
  in the same millisecond first too, filtered by `SessionStates`, at most `MaxResults` (1 to 100, 100 by default) to a
  page.
* A session's `EngineConfigurations`, `MonitoringConfiguration` and `Tags` are kept as given and reported by
  `GetSession`. In the `docker` mode the engine configurations' `spark-defaults` configure its server (below); the
  monitoring configuration configures nothing, and no session logs are written.

### Running a session's Spark Connect server

With `runner-mode` set to `docker`, each session runs a Spark Connect server on its cluster, as "Amazon EMR launches a
Spark Connect Server as a YARN application on your cluster": a container on the cluster, as a step's is, in the image
its steps run in, its HDFS and YARN started first where they are not running, the server's driver in that container
and its executors on the cluster's YARN. Its `spark-defaults` are the cluster's, then the session's
`EngineConfigurations`, which are "applied to the Spark Connect Server for that session only"; Floci sets
`spark.connect.grpc.binding.port` itself. It runs as the session's `ExecutionRoleArn`, else the cluster's instance
profile. The server's port is published on the Docker host's loopback interface only, and clients reach it through
Floci, as an EMR Serverless session's are (see EMR Serverless, "Running a session's Spark Connect server").

* **Connecting**: build the connection string from `GetSessionEndpoint`'s answer as AWS's guide does, but keep the
  endpoint's port: `sc://localhost:4566/;x-aws-proxy-auth=<AuthToken>;authorization=<SessionId>`, with
  `;use_ssl=true` only where Floci's TLS is on. The token names the session; the `authorization` parameter, which
  AWS's proxy reads, is dropped before the call reaches the server. The client's PySpark must match the release's
  Spark.
* **Tokens**: a call whose token Floci did not give, or which has expired, fails with gRPC's `UNAUTHENTICATED`; one
  to a session that is not `IDLE` or `BUSY`, or, in the `immediate` mode, to any session, with `UNAVAILABLE`, in
  Floci's own wording.
* **States**: a session is `SUBMITTED` until its container runs, `STARTING` until the server answers, then `IDLE`,
  with `StartedAt` and `IdleSince` set. No source says when a session is `BUSY`: Floci makes it `BUSY` while a call
  runs on its server, and `IDLE` again once none does, its idle timeout counting from then. A server that does not
  answer within 10 minutes, or stops on its own, fails its session, its `StateChangeReason` saying why; one Floci
  stopped running when it restarted fails too, and its container, if one is left, goes. The cluster's end ends its
  sessions and stops their servers.

## Studios

An EMR Studio is kept with the network and identities it names, each checked against Floci's own EC2 and IAM Identity
Center, but Floci serves no Studio: its `Url` is on Floci's own address (`/_aws/emr/studios/<studioId>`), where
nothing answers.

* **CreateStudio** refuses a request without one of the model's required members, or with an `AuthMode` or
  `IdcUserAssignment` it does not name, with `ValidationException`, and a member of the wrong JSON type with
  `SerializationException`. Then, each with `InvalidRequestException` in Floci's own wording, as AWS's is not recorded:
  more than 5 subnets ("A Studio can have a maximum of 5 subnets"), none, a VPC, subnet or security group Floci's EC2
  does not have, a subnet or security group outside the VPC ("The subnets must belong to the VPC specified by VpcId",
  each security group "must be in the same VPC"), an `SSO` Studio without a `UserRole` ("When you use IAM Identity
  Center authentication, you must specify a --user-role"), an `IAM` one with one ("Only specify a UserRole when you
  use IAM Identity Center authentication"), an `SSO` one with `IdpAuthUrl` or `IdpRelayStateParameterName`, which are
  for "when you use IAM authentication", and an `SSO` one when the account has no IAM Identity Center instance, or
  not the one `IdcInstanceArn` names. An `SSO` Studio's `TrustedIdentityPropagationEnabled` is `false` unless given,
  as "The default value is false". The Studio's id is `es-` and 25 letters and digits. A subnet named twice is kept
  once.
* **Tags**: `AddTags` and `RemoveTags` take a Studio's id as their `ResourceId`, which is "a cluster identifier or an
  Amazon EMR Studio ID", and IAM's `aws:ResourceTag` conditions read a Studio's tags.
* **UpdateStudio** changes what it is given. Its `SubnetIds` "can include new subnet IDs, but must also include all of
  the subnet IDs previously associated with the Studio", at most 5, in the Studio's VPC; anything else is refused.
* **DeleteStudio** removes the Studio and its session mappings. **ListStudios** lists the Region's Studios in the order
  they were made, 50 to a page, after a `Marker`, which resumes only the listing it came from: the same account and
  Region. No source gives AWS's order or page size.
* **Session mappings** are for an `SSO` Studio, as "Use CreateStudioSessionMapping to assign users to a Studio when you
  use IAM Identity Center authentication"; one on an `IAM` Studio is refused. A call names its user or group by
  "Either IdentityName or IdentityId ... but not both", and the identity must be in the identity store of the
  Studio's IAM Identity Center instance, a user by its `UserName` and a group by its `DisplayName`; a mapping reports
  both. A user or group mapped twice, or not mapped, is refused, each with `InvalidRequestException` in Floci's own
  wording. Getting, updating and removing a mapping finds it by the id or the name it was made with, so one whose
  user or group has since been deleted can still be removed. The `SessionPolicyArn` is kept as given. `ListStudioSessionMappings` lists a Studio's mappings, or every
  Studio's when it names none, filtered by `IdentityType`, in the order they were made, 50 to a page, after a `Marker`
  that resumes only the listing it came from: the same account, Region, Studio and `IdentityType`.

## Notebook executions

With `runner-mode` set to `docker`, a notebook execution runs its notebook on its cluster and writes it as run (see
[Running a notebook execution's notebook](#running-a-notebook-executions-notebook)). With the default `immediate`
mode, Floci keeps an execution and its lifecycle but runs no notebook and writes no output notebook.

* **StartNotebookExecution** refuses a request without `ExecutionEngine.Id` or `ServiceRole`, or with an
  `ExecutionEngine.Type` or `OutputNotebookFormat` the model does not name, with `ValidationException`, and a member of
  the wrong JSON type with `SerializationException`. The cluster must be `RUNNING` or `WAITING` and meet the EMR
  Notebooks cluster requirements: "Only clusters created using Amazon EMR release version 5.18.0 and later are
  supported", "The cluster must be launched with Hadoop, Spark, and Livy" and, "For Amazon EMR release versions
  5.32.0 and later, or 6.2.0 and later", Jupyter Enterprise Gateway too (the AWS runtime for Apache Spark's releases
  name no Hadoop among their applications). An account runs at most 100 executions that have not ended in a Region,
  as "A maximum of 100 concurrent executions are supported per AWS Region per account". A blank `ServiceRole` is
  refused too. Each of these refusals is
  `InvalidRequestException` in Floci's own wording. The editor, relative path, S3 locations and parameters are kept as
  given. The execution's id is `ex-` and 29 letters and digits, and its `ExecutionEngine.Type` is `EMR` unless given.
  IAM's `aws:ResourceTag` conditions read its tags.
* **States**: in the `immediate` mode an execution is `START_PENDING` as it starts, `RUNNING` from then on, and
  `FINISHED` once the cluster's `step-duration-seconds` has passed (at once by default), with AWS's "Execution is
  finished for cluster ...". It is `FAILED` if its cluster shuts down first, or once it has run 30 days, as "An
  execution is terminated if it runs for more than 30 days", in Floci's own wording. In the `docker` mode its run sets
  them (below).
* **StopNotebookExecution** stops one that has not ended, `STOPPED` with AWS's "Execution is stopped for cluster ...":
  at once with no notebook running, else `STOPPING` ("Execution is being stopped for cluster ...") until its run has
  stopped. One that has ended is refused with `InvalidRequestException`, in Floci's own wording.
* **ListNotebookExecutions** lists the Region's executions that started from `From` (by default 30 days ago) to
  `To` (by default now), filtered by `EditorId`, `Status` and `ExecutionEngineId`, newest first, at most 50 to a page
  ("Returns a maximum of 50 notebook executions") after a `Marker`, which resumes only the listing it came from: the
  same account, Region and filters.

### Running a notebook execution's notebook

With `runner-mode` set to `docker`, an execution runs its notebook on its cluster as a step's command runs: a
`spark-submit` there, a YARN application with the cluster's Spark settings, as the execution's `ExecutionRoleArn`,
else the cluster's instance profile, with its `EnvironmentVariables`. No source names the kernel AWS runs a notebook
with, so Floci runs it with a program of its own, needing only Python and PySpark, which every release's image has,
not Jupyter:

* **The notebook** is the execution's `NotebookS3Location`, read through the cluster's S3A. AWS finds an EMR
  Notebook named by `EditorId` and `RelativePath` under the notebook's own S3 location, which Floci keeps none of, so
  such an execution fails at once, in Floci's own wording.
* **Its cells**: the code cells run in turn in one Python namespace that holds `spark` and `sc`, each keeping what it
  printed, the value of its last expression, and any error, every output an earlier run left cleared first. Of
  sparkmagic's cell commands, a `%%sql` cell runs its body with `spark.sql`, showing the result's first 20 rows, and a
  `%%local` or `%%spark` one runs its body as Python; any other (`%%configure`, `%%info`, ...) is not run. A `%` or
  `!` line of IPython's or sparkmagic's commands is not run either, where the cell is not Python as it stands.
  `NotebookParams`, "Input parameters in JSON format", go in as papermill puts them, in a cell tagged
  `injected-parameters` after the one tagged `parameters`, else first, as "You can pass different parameter values to
  a notebook with a parameterized notebook cell", in place of any such cell an earlier run left; each parameter's name
  must be a Python variable's, or the run fails before any cell. An environment variable `KERNEL_EXTRA_SPARK_OPTS`, as
  AWS's example passes, goes to `spark-submit` as its options.
* **The notebook as run**: no source gives where AWS writes it, so Floci writes it under the
  `OutputNotebookS3Location` prefix, else beside the notebook, in a folder named for the execution, as `<name>.ipynb`
  and `<name>.html`, and reports the one `OutputNotebookFormat` names, the HTML one, else the notebook, as the
  execution's `OutputNotebookURI`.
* **States**: `START_PENDING` until its container runs, then `RUNNING`; `FINISHED` once every cell has run; `FAILED`
  at the first cell that raises (the notebook as run, written all the same, holds its error), where the run fails
  otherwise (its reason giving the run's exit code, the notebook as run perhaps not written), where it could not
  start, where its cluster shuts down, which stops the run, or after 30 days, in Floci's own wording. One Floci stopped
  running when it restarted fails too.

## Application UIs

Floci keeps a cluster's persistent application UIs, but serves no Spark History Server, Tez UI or YARN timeline
server: a presigned URL to one of those opens a page Floci serves at `/_aws/emr/app-ui` on its own address
(`base-url`, or `hostname`), with the cluster as `DescribeCluster` gives it and the interface asked for, and so does
one to an on-cluster UI in the `immediate` mode. The link is signed with a key Floci makes as it starts and lasts an
hour, as no source gives AWS's; one changed, past its hour or given before Floci restarted opens a `403` page, and one
whose cluster has gone a `404`.

In the `docker` mode, an on-cluster UI that runs on the cluster's primary node opens for real: the `ResourceManager`
("YARN ResourceManager | http://master-public-dns-name:8088/"), an `ApplicationMaster` through the ResourceManager's
web proxy, and the `JobHistoryServer`, on a cluster whose primary node runs YARN (see **YARN**). Each opens on a host of
its own, as AWS's application UIs are on hosts of their own under "emrappui-prod.us-east-1.amazonaws.com", which "are
registered in the Public Suffix List": `http://<secret>-<port>.emrappui.<Floci's host and port>/`, the secret 26
random letters and digits that only this link holds, so the host itself is the link's authority, as the interface
takes no AWS credentials. Floci's host must resolve its subdomains: `localhost` and `localhost.floci.io` do, and Floci's
TLS certificate names `*.emrappui.localhost` and `*.emrappui.localhost.floci.io`. Requests to the host go on to that
port of the primary node, which Floci reaches at a port Docker publishes on the host's loopback interface alone, or,
Floci in a container, on the cluster's network, which its container joins; the node's daemons write their links to
one another by the node's name, and Floci makes each such link, in the text it sends, a link to that interface's own
host under the same secret, so the ResourceManager's links to a job's history and to its containers' logs on the
NodeManager of a primary node that works open too. A NodeManager on a core or task node, which Floci does not reach,
keeps its links as written. The link lasts an hour, as the page's does, after which its host answers with a `403`
page; once its cluster has ended, its host answers with a `404` one. A request's body goes on to the interface as it
arrives, and an interface that sends nothing for a minute has the request given up with a `502` page.

* **CreatePersistentAppUI** takes an EMR cluster's ARN of the account in the Region as its `TargetResourceArn`; Floci
  keeps none for an EMR on EKS virtual cluster. The persistent Spark History Server is there "Starting with Amazon
  EMR version 5.25.0", and the "Tez UI and YARN timeline server persistent application interfaces are available
  starting with Amazon EMR version 5.30.1": the UI's `PersistentAppUITypeList` names `SHS` for a cluster with Spark,
  and, from 5.30.1, `TEZ` for one with Tez and `YTS` for one with Hadoop; a `ProfilerType` makes the one it names
  (`TEZUI` the Tez UI) the UI's only type, and one the cluster has not is refused. A cluster with none of them, an earlier
  release, a cluster with more than one primary node ("not available for EMR clusters with multiple master nodes"),
  and a UI past the account's 200 active ones ("the default limit for active application UIs is 200": a UI whose
  cluster has gone, or ended more than 30 days ago, as "The logs are available for 30 days after the application
  ends", no longer counts) are refused, each with
  `InvalidRequestException` in Floci's own wording. `RuntimeRoleEnabledCluster` is whether the cluster's security
  configuration sets `AuthorizationConfiguration.IAMConfiguration.EnableApplicationScopedIAMRole`. The UI's id is
  `p-` and 13 letters and digits, its `PersistentAppUIStatus` `ACTIVE` and its `AuthorId` the account, none of which
  any source gives. A tag without its `Key`, "the minimum required information for a valid tag", is refused, as it is
  for sessions, Studios and notebook executions.
* **GetPersistentAppUIPresignedURL** gives a URL to one of the UI's types, its first by default; a type it has not is
  refused, and the URL is `PresignedURLReady` at once. **GetOnClusterAppUIPresignedURL** gives one to an on-cluster UI,
  the `ResourceManager` by default, while the cluster is `RUNNING` or `WAITING`, and none for a `DryRun`. With an
  `ApplicationId`, the ResourceManager's link lands on the application's page, an application master's on its UI
  through the web proxy, and the JobHistory server's on the job of that id (`job_` in place of `application_`); without
  one, on the cluster's applications, its running ones, and its jobs. A link Floci opens for real is
  `PresignedURLReady` once the primary node is: until then, as on a cluster no step has run on yet, the node starts as
  a step's would ("Used to determine if the presigned URL is ready"), and its host answers with a `503` page meanwhile.
  Any other is ready at once.
* **GetClusterSessionCredentials** gives "temporary, HTTP basic credentials that are associated with a given runtime
  IAM role and used by a cluster with fine-grained access control activated": a random username and password that
  expire in an hour, as no source gives AWS's lifetime, for a `RUNNING` or `WAITING` cluster on `emr-6.11.0` or later
  (from which a Workspace attaches to a cluster with a runtime role) whose security configuration sets
  `EnableApplicationScopedIAMRole` as the cluster was created (deleting or reusing the configuration's name later
  changes nothing, for this and for `RuntimeRoleEnabledCluster`). A request without an `ExecutionRoleArn`, which the
  model leaves optional, is refused too, as the credentials are "associated with a given runtime IAM role". Anything
  else is refused with `InvalidRequestException`, in Floci's own wording. Floci runs no endpoint that checks them, and
  keeps none.

## Running steps

With `runner-mode` set to `docker` (`FLOCI_SERVICES_EMR_RUNNER_MODE=docker`), a step runs. Floci starts
one container per step from the cluster's release image, the one EMR Serverless's `docker` mode uses
(`public.ecr.aws/emr-serverless/spark/<release>`), and runs the step in it: a Spark step's
`spark-submit` on the YARN its primary node runs (see **YARN**), or in local mode where it runs none, the container
holding the driver and its executors, or a command or a script. The cluster itself goes through its lifecycle as in the
`immediate` mode, but for its bootstrap actions and its primary node (see **Storage**).

* **Which steps run**: a `command-runner.jar` step whose first argument is `spark-submit`, the way the
  Management Guide writes Spark steps, runs the arguments after it, with its `--master` and `--deploy-mode` as
  given where the cluster runs YARN (see **YARN**) and without them where it does not. A custom JAR step runs its `MainClass`, or else the one its manifest names, through
  `spark-submit`, with the JAR and the step's `Args`; a MapReduce job it starts, such as the Management
  Guide's `wordcount` of Hadoop's examples JAR, runs on the cluster's YARN, or in local mode where it runs none (see
  **Hadoop streaming and MapReduce**).
  A `Jar` of `/usr/lib/hadoop-mapreduce/hadoop-streaming.jar`, where EMR has it, runs the image's
  `/usr/lib/hadoop/hadoop-streaming.jar`. Any other `command-runner.jar` step runs its
  command, its first argument, with the rest, and a `script-runner.jar` step (by name, or by its
  `s3://<region>.elasticmapreduce/libs/script-runner/script-runner.jar` URI) its script, as the Release
  Guide's "Run commands and scripts on an Amazon EMR cluster" describes them. An `s3-dist-cp` step runs
  Floci's own S3DistCp (see **S3DistCp**), and `hadoop-streaming` and `hadoop-lzo` steps the image's
  Hadoop (see **Hadoop streaming and MapReduce**). On a cluster whose `Applications` name Hive, or HCatalog, with
  which EMR installs Hive's client too, a Hive step runs Hive (see **Hive steps**); on one with neither, `hive-script`
  runs in the release's image, and says what EMR's does without Hive, "Hive isn't installed.", and the step fails. An
  `emr-spark-*` release includes neither ("This release includes the following applications: AmazonCloudWatchAgent,
  Delta, Hudi, Iceberg, JupyterEnterpriseGateway, Livy, and Spark."), so a cluster of one has neither, whatever its
  `Applications` name: no image with Hive, no metastore service. A Pig step runs only where Floci adds Pig (see
  **Pig steps**); on a cluster that does not install it, EMR's `pig-script`, which the image has, says "Pig isn't
  installed.", and the step fails.
* **Commands and scripts**: they run as the image's `hadoop` user in `/home/hadoop`, with `bash`, Python
  3, Java, `curl` and the AWS CLI v2, which reaches Floci through `AWS_ENDPOINT_URL`. A
  `script-runner.jar` step's script is fetched from S3 with the AWS CLI and the step's credentials, then
  run with the step's other arguments; a path not in S3 is run as it is, a bare name from `/home/hadoop`
  when a file there has it, one a former step left there included. A step can run `sudo`, as the Release Guide's
  "Using HBase snapshots" step does with `command-runner.jar` and `sudo -u hbase`, and the Management Guide's
  Kerberos users step with a `script-runner.jar` script running `sudo kadmin.local`. The image has no `sudo`, so a
  step finds Floci's first on its `PATH`, a Hive step's shell commands (Hive's `!`) too, the bootstrap actions' one: it
  drops its options, with their values, and runs its command as it is, as the step's `hadoop` user. So
  `sudo -u hadoop spark-submit ...` runs as on EMR, and a command that needs root fails, as it would for `hadoop`. `hadoop fs` and `hdfs dfs` reach the
  cluster's HDFS (see **Storage**) and Floci's S3. A `spark-submit` they start runs on the cluster's YARN, or in local
  mode where it runs none, on Floci's S3 with the
  cluster's `spark-defaults`, `core-site`, `spark-hive-site` and `spark-log4j2`, which Floci puts in a Spark
  configuration of its own, a copy of the folder a `spark-env` export of `SPARK_CONF_DIR` names, else of the
  image's, ahead of the image's EMR Serverless master; where the cluster runs no YARN, its own `--master yarn` or
  `--deploy-mode cluster` fails.
* **Storage**: a cluster's primary node keeps its disks while the cluster lives, and so does Floci: each step's
  container mounts the cluster's two Docker volumes, one at `/home/hadoop` and one at `/mnt`, so what a step leaves
  there is there for the steps after it, and for steps running at the same time. What the bootstrap actions left in
  `/home/hadoop` or `/mnt` comes with the image the steps run in, and Docker copies it into the volumes when the
  first step starts. Every cluster runs HDFS, "best used for caching the results produced by intermediate job-flow
  steps", as the Management Guide puts it: a NameNode and a DataNode on its primary node, which Floci runs in a
  container of their own, `emr-<cluster-id>-hdfs`, from the image the cluster's steps run in where it runs YARN (see
  **YARN**), else from the release's EMR Serverless Hive image, which has HDFS
  where the release's image has only its client (`hive-image-template` names another), or, for an `emr-spark-*`
  release, which has no Hive image, from `emr-7.5.0`'s, with their data in a third volume and permissions off. Where
  the cluster was created with core nodes the DataNodes are theirs instead (see **YARN**), and HDFS keeps what EMR's does, one copy
  of a block below four core nodes, two below ten and three otherwise, or what the `hdfs-site` classification's
  `dfs.replication` says, which is the one property of that classification Floci applies. On a cluster with Hive or HCatalog (see **Which steps run**), the same container runs Hive's
  metastore service, which EMR's HCatalog server is, on port 9083, its Derby database in that volume and its warehouse
  in the HDFS; it reaches the S3 locations of tables through Floci's S3 as the role of the cluster's EC2 instance
  profile, as the bootstrap actions do, with a session that goes with the container. It gets the cluster's `core-site`
  and `hive-site` after its own
  settings, as EMR's primary node does, so a `hive-site` setting of `hive.metastore.warehouse.dir`, say, holds for the
  service as for the Hive steps. A primary node with the service that an earlier Floci left running starts again, on
  the data its volume keeps, as its session went with that Floci. The container is on a Docker network of the cluster's own, also
  `emr-<cluster-id>-hdfs`, which the cluster's steps join besides their own, where the cluster's nodes and steps reach
  it by the primary node's name; each cluster whose HDFS runs takes one network from the Docker daemon's address pools.
  Each of the cluster's nodes, this one and its core and task nodes (see **YARN**), is on the steps' network too, the
  one Floci's other containers are on, from before it starts, with Floci's DNS, as a cluster's nodes reach what is in
  their VPC: a cluster-mode driver or an executor reaches an MSK cluster's brokers or an RDS instance by the endpoints
  Floci gives, and a virtual-hosted bucket name under Floci's host name, as Iceberg's `S3FileIO` uses by default,
  resolves to Floci. The nodes' HDFS and YARN listen on their address on the cluster's network alone, so a container on
  the steps' network, another cluster's included, reaches neither. Hive 3's metastore has no setting for the address it
  listens on, so on a cluster with Hive or HCatalog the primary node, which runs it, stays on the cluster's network
  alone, and the executors YARN runs there, on such a cluster with no core node, reach Floci at its address on the
  cluster's network. Where Docker does not say the subnet of the cluster's network, its nodes stay on it alone. A node an earlier Floci started on the cluster's network alone starts again, on its data. When Floci runs in a container, that container joins the network of each cluster
  with Hive too, as `floci`, which is how the metastore reaches Floci's S3, and leaves it when the HDFS container
  goes. The cluster's first step starts it, and waits for it: a step whose cluster's HDFS is not ready within two
  minutes fails ("The step could not start: The HDFS of EMR cluster ... did not start within 120 seconds"). If its
  NameNode, DataNode or metastore exits, its container ends with it, and the cluster's next step starts it again on
  its data. Their warnings and errors are the container's output, which `docker logs` shows. With `docker-network`
  set to `host`, steps reach it on the host's loopback, and its daemons listen there only, as anyone reaching them
  could change its files, on the host's own ports (8020, 9870, 9866, 9864, 9867), so only one cluster's HDFS runs at
  a time. With `container:<id>`, its container joins that namespace as the steps do, and listens on its loopback in
  the same way; the primary node's name goes in the hosts file that namespace's containers share, `<id>`'s own
  included. In both, the metastore service does not run: Hive 3's has no setting for the address it listens on, so
  it would take 9083 on every interface of the namespace, the host's own for `host`, and Hive steps keep a metastore
  of their own (see **Hive steps**). With `none`, steps have no network, so the cluster runs no HDFS, and its steps
  run as without one. Steps reach
  it by the primary node's `PrivateDnsName`, as `ListInstances` gives it (`ip-10-0-0-1.ec2.internal`, say), on port
  8020: `hdfs://` paths without a host, and paths without a scheme ("hdfs:// (or no prefix)"), lead there through
  `fs.defaultFS`, a relative path under `/user/hadoop`, and the cluster's `spark-defaults` and `core-site` can set
  it otherwise. A command's `hadoop fs` gets the same, through a copy of the image's Hadoop configuration, or of the
  folder a `spark-env` export of `HADOOP_CONF_DIR` names, with the cluster's `core-site` after it, which wins; the
  image's `hdfs` command cannot run, so `hdfs dfs` runs as `hadoop fs`, and its other commands fail. A structured
  streaming query keeps its checkpoints through Spark's file system checkpoint manager, as its default one refuses
  an `hdfs://` path without a host. Where the cluster runs no YARN, in a command's Hadoop configuration, which a custom
  JAR's MapReduce in the driver reads too, the `hdfs` scheme is a view of that HDFS in which one folder, `/tmp/hadoop/mapred/staging`, is
  the container's own, for MapReduce in local mode (see **Hadoop streaming and MapReduce**); Spark and Hive keep
  HDFS's own client, so a Spark step can write a Hive table there. The HDFS container, its network and the volumes
  go once the cluster has ended and its last step is done ("HDFS is ephemeral storage that is reclaimed when you terminate a
  cluster"), on a state reset, and, for clusters that are gone or have ended, when the EMR service starts, unless
  `reconcile-containers-on-startup` is off. A shutdown of Floci removes the HDFS containers of the clusters that are
  still up, and their next step starts them again, on the data their volume keeps. `/tmp` is each container's own.
* **YARN**: on a Docker network of the cluster's own, the default, "the primary node runs the YARN ResourceManager
  service", as the Management Guide puts it, in the primary node's container, with a NodeManager beside it on a cluster
  created with no core node, which keeps it, and its DataNode, as core nodes are added, the blocks it holds staying in
  reach, and the cluster's Spark, MapReduce (see **Hadoop streaming and MapReduce**) and Hive's Tez (see **Hive steps**)
  run on them. Beside them runs the MapReduce JobHistory server EMR's primary node runs too (a release's `mapred-site`
  classification "Restarts Hadoop MapReduce-HistoryServer"), on Hadoop's own ports, 10020 and 19888 for its web
  pages, from the jobs' history in the cluster's HDFS, and the node is ready once it answers. A
  Spark step or custom JAR step submits to it, with `--master yarn` unless it names another master, and in the deploy
  mode it asks for, `client` when it names none, and so does a `spark-submit` a command or script starts. In client
  mode the driver stays in the step's container and its output in the step's logs, and the executors run in YARN's
  containers on the cluster's nodes; in cluster mode the driver runs on one of them too, its output in the application's
  container logs (see **Output**) and only the client's in the step's.
  A node needs Spark, Python and what the bootstrap actions installed, as an EMR cluster's nodes hold every
  application it installs, so each runs from the image the steps run in, the release's EMR Serverless image with the
  HDFS, MapReduce and YARN of its EMR Serverless Hive image, and Hive on a cluster with Hive, which Floci makes the
  first time one of the release's clusters needs it (see **Hive steps**), or, on a cluster with bootstrap actions, from
  the image its own bootstrap left on that image (see **Bootstrap actions**). An `emr-spark-*` release has no EMR
  Serverless Hive image, and `emr-7.5.0`'s HDFS and
  YARN do not run on its newer Hadoop, so its image holds the whole of `emr-7.5.0`'s Hadoop in `/opt`, which only the
  node's daemons run from (see **Known limitations**). EMR sizes a NodeManager, and the executors' memory and cores ("determined by the
  core and task instance types"), by the instance type; Floci's node shares the machine it runs on, so its NodeManager
  offers `yarn-node-memory-mb` and `yarn-node-vcores`, 4096 MB and 4 by default, and an executor is 1 GB and one core
  and a driver 2 GB and one core, which the cluster's `spark-defaults` and a step's own options replace. A container
  larger than the NodeManager offers is refused by YARN, and its step fails. Dynamic allocation is on, as it is on
  EMR, through Spark's shuffle tracking: the images have no external shuffle service. Executors reach Floci's S3 as the
  steps do, their node being on the steps' network (see **Storage**), or, where the steps have no network of Floci's,
  at Floci's address on the cluster's network, which Floci's container joins as for the metastore, over HTTP, which
  Floci serves beside HTTPS with TLS on, so no certificate is involved, with the step's AWS credentials, and find the
  driver at its container's name on the cluster's network.
  Each core and task node is a container of its own on that network, `emr-<cluster-id>-node-<group>-<n>`, with its data
  in a volume of its own: "Core nodes run the Data Node daemon", "a core node runs YARN NodeManager daemons, Hadoop
  MapReduce tasks, and Spark executors", and "Task nodes don't run the Data Node daemon". Its name on the network is the
  private DNS name `ListInstances` gives it, which YARN and HDFS know it by, an instance fleet's as an instance group's.
  How many there are follows the instance
  groups' counts or the instance fleets' target capacities, at most `yarn-max-worker-nodes`, 4 by default, core nodes
  first, as each costs 300 to 800 MB while idle; with 0 the cluster runs on its primary node alone. `ModifyInstanceGroups`,
  `AddInstanceGroups`, `ModifyInstanceFleet` and `AddInstanceFleet` reach the nodes of a cluster a step has started them
  on at once, and the first step of any other starts those asked for by then. A new node takes work as soon as it has
  registered, even from a step already running. One no longer wanted is decommissioned, as "both YARN NodeManager and
  HDFS DataNode daemons must be decommissioned", so the steps running on it finish and its blocks move to the nodes that
  stay, within EMR's default timeout of an hour, each node its own, and then it goes with its data; core nodes stop at the copies HDFS
  keeps, as "Amazon EMR will not allow clusters to scale core nodes below `dfs.replication`". A core node's NodeManager
  is decommissioned only once HDFS has moved its blocks, as a decommissioned NodeManager exits, which ends the node's
  container, its DataNode with it. A core node that has gone stays among HDFS's excluded nodes for a minute, until the
  NameNode, told to by Floci, has it dead 40 seconds after it stopped, so the copies of blocks it held never count
  again. A core node a resize brings back under its name in that minute takes no new blocks until it is over. A core
  node that stops before HDFS has moved its blocks starts again on its data, and one a shutdown of Floci cut off while
  it was being decommissioned starts again on its data with the cluster's next step, to be decommissioned then.
* **Hadoop streaming and MapReduce**: on a cluster that runs YARN (see **YARN**), a step's MapReduce runs there, its
  application master and tasks in YARN's containers on the cluster's nodes. A step's `hadoop` command, and so
  `hadoop-streaming`, a `hadoop jar` and `hdfs dfs`, run from the nodes' Hadoop (`/usr/lib`, or the one an `emr-spark-*`
  release keeps in `/opt`), with a `mapred-site.xml` that names YARN, the nodes' folders and the step's AWS environment,
  and an application master of 1 GB and tasks of 768 MB, which the cluster's `mapred-site` replaces, and that names the
  JobHistory server, which a client asks about a job once its application master has ended, for its counters and
  task reports as Pig does after each of its MapReduce jobs. A custom JAR step's MapReduce gets the same, and a Hive
  step's settings name the server too, for a query whose `hive.execution.engine` is `mr`. Where the cluster runs no YARN, a step's `hadoop` command runs MapReduce in local mode, in
  its own JVM: the image has MapReduce only in Spark's shaded Hadoop client, which
  Floci makes the
  command's MapReduce home (`HADOOP_MAPRED_HOME`, unless a `spark-env` export names one), so it is on the
  command's class path whatever `HADOOP_CLASSPATH` a command sets, and a copy of its `mapred-site.xml` sets
  `mapreduce.framework.name` to `local`. This has run on the `emr-7.5.0` and `emr-7.10.0` images; on another,
  whose Spark jars may be named otherwise, the step's standard error names each jar of the three the MapReduce
  home links that the image lacks, as a job that needs it fails later, on its class path. So a
  `command-runner.jar` step whose first argument is `hadoop-streaming`, which "Submits an Hadoop streaming
  program. In the console and some SDKs, this is a streaming step", as the Release Guide's tools table puts it
  (the AWS CLI's `Type=STREAMING` becomes one), runs the image's `hadoop-streaming.jar` with the step's other
  arguments: its mapper and reducer can come from Floci's S3 through `-files`, `-cacheFile` or
  `-cacheArchive`, and its `-input` and `-output` can be in Floci's S3 or the cluster's HDFS. A job stages its
  files in `/tmp/hadoop/mapred/staging`, a folder of the step's container, then looks for them at that path
  of the default file system, the cluster's HDFS, so there the `hdfs` scheme leads to that folder. A
  `hadoop-lzo` step runs the image's LZO indexer the same way, as does a `hadoop jar` a command or script
  runs. `s3://` and `s3a://` paths reach Floci's S3
  through S3A, with the AWS SDK Spark's own S3A finds, in place of the image's EMRFS, which is not pointed at
  Floci. The image's `log4j.properties` for Hadoop is empty, so Hadoop logs to the step's standard error, from
  `INFO` up, in the layout of the Management Guide's step log lines, and `stderr.gz` holds a failed job's
  reason ("ERROR org.apache.hadoop.streaming.StreamJob (main): Error Launching job : Output directory ...
  already exists"), which EMR writes to the step's `syslog`. A MapReduce job a custom JAR step's
  `spark-submit` starts runs in local mode the same way, in the driver's JVM, as `spark-submit` puts the
  copied Hadoop configuration (`HADOOP_CONF_DIR`) on its class path; with the image's, whose `mapred-site.xml`
  names YARN, the job would wait for it without end. The cluster's `mapred-site` properties, which "Change
  values in the MapReduce application's mapred-site.xml file", come before Floci's `mapreduce.framework.name`
  and staging folder, which win, so a job's own `-D mapreduce.framework.name=yarn` still fails, and
  `mapreduce.application.classpath`, which the Release Guide gives for a custom JAR's dependencies, changes
  nothing, as a job's tasks run in its own JVM. The cluster's `hadoop-log4j` properties, which "Change values
  in Hadoop's log4j.properties file", follow the `log4j.properties` lines, a later key winning, and its
  `hadoop-env` exports, which "Change values in the Hadoop environment for all Hadoop components", start the
  `hadoop-env.sh` the `hadoop` command reads, as given, with no shell expansion, as `spark-env`'s are, ahead
  of the image's own lines, which add to `HADOOP_CLASSPATH` rather than set it, so the Release Guide's
  `HADOOP_CLASSPATH` of `/path/to/my.jar` keeps the LZO codec the image's `core-site.xml` names; a name a
  shell cannot export is left out. A custom JAR step's `spark-submit` reads neither `hadoop-env.sh` nor
  Hadoop's `log4j.properties`, so those two do not reach it: a `hadoop-env` `HADOOP_CLASSPATH`, say, is not on
  its class path, and it logs as Spark does.
* **Hive steps**: an EMR cluster's nodes hold every application it installs, so a cluster whose `Applications` name
  Hive, or HCatalog, which installs Hive's client and metastore service, runs its bootstrap actions and all its steps
  in an image of the release's with Hive added: the release's EMR
  Serverless image with the Hive, Tez, HDFS, MapReduce and YARN of its EMR Serverless Hive image
  (`public.ecr.aws/emr-serverless/hive/<release>`, 2.7 GB unpacked, pulled to make it; `hive-image-template` names
  another). Floci makes it the first time one of the release's clusters needs it: a container of the release's
  image, never started, gets the Hive image's folders, and is committed, as a bootstrap's is, as
  `floci-aws-emr-release:<release>-<hash>`, 0.6 GB more than the release's image, and kept for the release's next
  cluster (`docker image rm` removes it); a newer pull of either image makes another, which replaces the ones made
  before from the same two image names for the same release and kind (with Hive or without): each is removed then,
  unless a container still uses it, or a step or a bootstrap given it has yet to end, which keeps it until the next
  newer one. Images of one kind are made one at a time, so one made of the older images never comes after the newer
  one. One made before Floci labelled images with their kind is left. A failure to make it, a Hive
  image that cannot be pulled say, fails the step that needed it ("The step could not start: ..."), or, for a
  bootstrap, ends the cluster with `INTERNAL_ERROR`. The two images ship different builds of one goodies JAR under
  one name, so Tez keeps the Hive image's, and Hive runs with the Hive image's Hadoop configuration, which puts Tez
  on its class path, while the cluster's other steps keep the release image's. Then a `command-runner.jar` step
  running `hive-script`, as the AWS CLI writes a `Type=Hive` step (`hive-script --run-hive-script --args` and the
  step's `Args`), or `hive` itself, runs Hive: `hive-script` runs `hive` with the arguments after `--args`, as the
  image's `hadoop` user in `/home/hadoop`, on the cluster's storage, its Tez on the cluster's YARN, or, where it runs
  none, in local mode in its JVM, as an [EMR Serverless Hive job run](emr-serverless.md#running-hive-job-runs)'s does.
  The images hold EMR Serverless's build of Tez, whose `tez-site.xml` names its own plugins, so on YARN Hive runs the
  stock classes from the same jars, with an application master of 1 GB and tasks of 768 MB and one virtual core. It gets the cluster's `core-site`,
  `tez-site` and `hive-site` properties, each a `--hiveconf`, then the step's own arguments, so a `--hiveconf` of
  the step's wins over a classification's, as it does over `hive-site.xml`; the cluster's `hive-env` exports are its
  environment. Floci's settings come before them all, and any of them replaces one: S3A on Floci's S3 for `s3://`,
  where Tez runs, the cluster's HDFS, and its metastore. "By default, Hive records metastore information in a
  MySQL database on the primary node's file system", which Floci keeps in a Derby database of the metastore service
  its primary node runs (see **Storage**), so a table one Hive step makes is there for the next, until the cluster
  ends, and any number of Hive steps use it at once. The `hive-site` setting of
  `hive.metastore.client.factory.class` the Release Guide gives for the Glue Data Catalog uses Floci's Glue instead,
  with the cluster's account as its `hive.metastore.glue.catalogid`, which a `hive-site` one replaces. A table
  without a `LOCATION` is in the cluster's HDFS, under `/user/hive/warehouse` ("By default, this is a location in
  HDFS"), which other steps read as `hdfs:///user/hive/warehouse/<table>`, and an `hdfs://` `LOCATION` is there too.
  The cluster's Spark steps use that metastore too, as Spark's default catalog "always connects to a Hive metastore"
  (the Release Guide's "Use an Iceberg cluster with Spark") and its "Using HCatalog" has Spark SQL read a table the
  HCat CLI made, so a Spark step with Hive support reads a Hive step's tables and makes tables Hive steps read; a
  `spark-hive-site` naming the Glue Data Catalog's client factory still uses Floci's Glue. Where that service does not
  run, with `docker-network` set to `host` or `container:<id>`, or to
  `none`, the metastore is an embedded Derby database in the cluster's `/mnt` instead, which only one Hive step at a
  time can use: with `StepConcurrencyLevel` above 1, a Hive step that starts while another runs fails ("Another
  instance of Derby may have already booted the database"), and Spark steps see a Hive step's tables only through
  the Glue Data Catalog. What the bootstrap actions installed is there for every step, Hive's included. A script or
  command that starts `hive` itself gets none of these settings: only a Hive step runs Hive. Hive's own log stays in
  the container.
* **Pig steps**: no AWS image carries Pig, so Floci adds Apache Pig to a cluster whose `Applications` name it only
  with `pig.enabled` on (`FLOCI_SERVICES_EMR_PIG_ENABLED=true`), off by default. The cluster's image (see **Hive
  steps**) then gets the Tez of its EMR Serverless Hive image, which Pig runs its scripts on, with the HDFS, MapReduce
  and YARN every cluster's has, and Apache Pig in `/usr/lib/pig`, where EMR keeps it, with a `/usr/bin/pig` that runs
  it: Floci downloads Apache's binary distribution, about 470 MB, to a temporary file, from the first of `pig.urls`
  that gives it whole with the checksum `pig.sha256` names, Apache's download CDN then its archive, a URL that cannot
  be reached, answers otherwise, sends nothing for a minute or gives another checksum passing to the next. It then
  writes the 200 MB Pig runs from into the image as it makes it, leaving out the docs, the sources and the jars of Pig
  on Spark, and removes the file. No URL giving it fails the step that needed the image ("The step could not start:
  Could not download Apache Pig: ...", with what each URL gave), and nothing goes into an image unchecked. The image's
  name holds the checksum, so a cluster with Pig has an image of its own, made once for its release and kept, as one
  with Hive has. EMR's releases ship Pig 0.17.0, whose Apache build is "compatible with Hadoop 2.5+ releases", so
  Floci's default is Pig 0.18.0, whose `pig-0.18.0.tar.gz` "is compatible with Hadoop 3, Spark 3, Hive 3 and HBase 2",
  the Hadoop the images hold; a script that relies on what changed between the two can behave otherwise. Pig runs on
  the image's Tez, as EMR's runs on EMR's, in place of the Tez its distribution bundles, whose classes need another
  protobuf. Then the AWS CLI's `Type=PIG` step, `command-runner.jar` running `pig-script --run-pig-script --args` and
  the step's `Args`, runs EMR's own `pig-script`, which the image has: it runs `pig` with the arguments after
  `--args`, as the image's `hadoop` user in `/home/hadoop`, on the cluster's storage, so `-f s3://...` reads its
  script from Floci's S3 and `-p` sets its parameters. "Pig 0.16.0 and later use Apache Tez", so Pig runs on Tez, on
  the cluster's YARN with the stock Tez classes and the sizes Hive's Tez gets (see **Hive steps**), or, where it runs
  none, in local mode in its JVM; `-x mapreduce`, or `exectype=mapreduce` in `pig-properties`, as the Release Guide's
  Pig considerations put it, runs MapReduce instead (see **Hadoop streaming and MapReduce**). Pig reads a
  configuration of its own, a copy of its `conf` folder whose `pig.properties` ends with Floci's `exectype=tez` and
  `mapreduce.job.working.dir=/user/${user.name}`, then the cluster's `tez-site` and `pig-properties`, a later key
  winning, Pig passing the Hadoop and Tez ones on to its jobs. Pig sets the working directory of each input's file
  system to it, which S3A, Floci's `s3://`, refuses when it names another file system, as Hadoop's own default, the
  HDFS home with its scheme, does; without a scheme, it is the same HDFS folder. A `pig-log4j` adds its lines to the
  `log4j.properties` Pig then reads, and the exports of `pig-env` start its `pig-env.sh`. Any command a step runs on
  such a cluster, `command-runner.jar pig ...` or a script's, gets the same. With `pig.enabled` off, a Pig step on a
  cluster whose `Applications` name Pig is `FAILED` without running ("Floci does not run this step: Pig is not in the
  image steps run in. floci.services.emr.pig.enabled adds Apache Pig to it.").
* **S3DistCp**: the image has no `s3-dist-cp`, so a `command-runner.jar` step whose
  first argument is `s3-dist-cp`, the Release Guide's form, runs Floci's own program instead, through
  `spark-submit` in the step's container, copying through Hadoop's file systems: `--src` and `--dest` take
  S3 paths in Floci's S3, the cluster's HDFS (`hdfs:///output`, or a path without a scheme) or a file of
  the container, a source folder's files keeping their folders under `--dest`. It takes the Release
  Guide's options, `--name=value` or `--name value`, and `-D property=value`. `--srcPattern` and
  `--groupBy` match the whole source path, as Python regular expressions, which share Java's common
  syntax. `--groupBy` concatenates the files whose groups give the same name, which is the groups' text,
  into the folder of the first of them, and refuses a regular expression with no group, as S3DistCp does;
  `--targetSize` breaks a group larger than it into parts named with a number before the extension.
  `--outputCodec` (`gzip`, `gz`, `lzo`, `lzop`, `snappy`, or `none` to decompress) decompresses each
  source by its own extension and replaces that extension with the codec's; without it a file's
  compression stays as it is, and a group keeps the compression its files share, named with that codec's
  extension, or has none when they differ, each file decompressed so that the group is one stream.
  `--deleteOnSuccess` deletes the copied files, but not their folders, once
  all are copied. `--outputManifest` writes gzipped JSON lines (`path`, `baseName`, `srcDir`, `size`) to
  `--dest`; `--previousManifest` leaves out the files it names by `baseName`, and fails the step when it
  is not there unless `--requirePreviousManifest` is `false` (`=false` or `false` after it);
  `--copyFromManifest` copies the files it names. `--srcPrefixesFile` copies the files under each of its
  lines instead of listing `--src`, a line ending in `/` naming that folder alone and any other a prefix as
  S3 lists one (`logs/day` takes in `logs/day-old/`).
  `--s3ServerSideEncryption`, `--storageClass`, `--disableMultipartUpload` and
  `--multipartUploadChunkSize` set S3A's matching settings; `--s3Endpoint` is ignored, as S3 is Floci's. A
  bucket name with an underscore fails the step, as S3DistCp "does not support" one, and so does a copy
  Hadoop fails, leaving what was copied, as S3DistCp does. `--appendToLastFile` puts the new data after
  what a destination file holds, through a copy renamed over it, on every file system, as S3 cannot append
  in place. The copy has a name of its own and goes if the writing fails; the file steps
  aside, under a name of its own too, until the copy has taken its place, and comes back if it cannot, so a
  failed append leaves it as it was. A copy that would write two files to the same path, the manifest included
  (`a.log` and `a.log.gz` with `--outputCodec=gzip`, say), a file over one of its own sources, as with the
  same `--src` and `--dest`, a file outside `--dest`, through a `..` in a manifest's `baseName` or in
  `--outputManifest`, or a file with no name, from a `--groupBy` whose groups matched nothing, say, fails
  before it writes anything. A step's log has one line saying what was copied.
  Floci's own readings, as no source says: the grouped file's folder, `--numberFiles` putting the number
  straight before the file's name, a group's compression without `--outputCodec`, and failing a copy that
  would write over its own output or source, or outside `--dest`.
* **HTTPS**: with [TLS](../configuration/tls.md) on, each step's container, and the bootstrap actions'
  one, also gets Floci's CA bundle as a Java trust store, which every JVM in it uses, as an
  [EMR Serverless job run](emr-serverless.md#running-spark-job-runs)'s do, so Java code reaches Floci
  over HTTPS too, and through AWS's own endpoint names with `FLOCI_DNS_SPOOF_AWS_ENDPOINTS`.
* **Bootstrap actions**: a cluster with `BootstrapActions` is `BOOTSTRAPPING` until they have run, one
  after the other, in one container of the release's image (with Hive added for a cluster with Hive, see
  **Hive steps**), whatever the second half of
  `cluster-startup-delay-seconds`. Each action's script is read from Floci's S3 (an `s3n://` or `s3a://`
  path names the same bucket, and another path is the image's own) and runs with its `Args` in
  `/home/hadoop`, with the credentials of the cluster's `JobFlowRole` instance profile, as a step would.
  "Bootstrap actions execute as the Hadoop user by default", and `sudo` gives them root, but the image
  has no `sudo`: an action runs as root, with a `sudo` that runs its command as it is, and what it leaves
  in `/home/hadoop` is the `hadoop` user's. Once every action has succeeded, the container is kept as an
  image named after the cluster, which its steps run in, so that what the actions installed or wrote is
  there for each of them; the image goes once the cluster has ended and its last step is done. An action
  that exits with another code than 0 ends the cluster: it cancels its steps and goes `TERMINATING`, then
  `TERMINATED_WITH_ERRORS`, with the reason `BOOTSTRAP_FAILURE` and AWS's message "On the master instance
  (i-00000000000000001), bootstrap action 1 returned a non-zero return code", the instance as
  `ListInstances` gives the master. A script that cannot be read ends it the same way, in Floci's own
  words, and a bootstrap that cannot run at all, an image that cannot be pulled say, with
  `INTERNAL_ERROR`. `TerminateJobFlows` stops the actions at once, and a cluster Floci was bootstrapping
  when it stopped ends with `INTERNAL_ERROR` once Floci is back. With a `LogUri`, each action's output
  goes to `stdout.gz` and `stderr.gz` under `<LogUri>/<cluster-id>/node/<instance-id>/bootstrap-actions/<n>/`,
  as the Management Guide's bootstrap failure pages give the folder. Bootstrap actions "run on cluster after
  Amazon EMR launches the instance", on each node: where a cluster's core and task nodes run in containers of their
  own (see **YARN**), each runs them too, in a container of its own beside the primary node's, as that node, its files
  saying so (see **Node files**), its instance metadata its own (see **Instance metadata**), its output under its own
  instance id, and is kept as the image that node runs from, so it holds what its own actions installed. A node a
  resize adds runs them before it starts, as "If you add nodes to a running cluster, bootstrap actions also run on
  those nodes in the same way", and one that leaves keeps its image only until the cluster ends, so one added again
  runs them again; a bootstrap that cannot run for the nodes a resize adds ends the cluster with `INTERNAL_ERROR`, as
  at its start. The nodes' bootstraps stop as the cluster ends, or as the primary node's actions fail. AWS
  terminates a node whose action fails, and "If too many instances fail their bootstrap actions, then Amazon EMR
  terminates the cluster. If just a few instances fail, Amazon EMR attempts to reallocate the failed instances and
  continue", with no number given: Floci runs a node's actions again once, in a fresh container, and a second failure
  ends the cluster with `BOOTSTRAP_FAILURE` and, as no source gives AWS's words or an error code for a core or task
  node, a message of Floci's after the primary node's, "On the core instance (i-00000000000000002), bootstrap action 1
  returned a non-zero return code", with no `ErrorDetails`. Elsewhere the actions run once, as on the primary node, and
  every node runs from the image they leave. Shutdown actions do not run. A cluster a failed action ended has a
  `Status.ErrorDetails` entry in `DescribeCluster` and
  `ListClusters`, with the code and `ErrorData` keys of the Management Guide's error code pages:
  `BOOTSTRAP_FAILURE_PRIMARY_WITH_NON_ZERO_CODE` with `primary-instance-id`, `bootstrap-action`, `return-code`,
  `amazon-s3-path` and `public-doc` for an action that exits with another code than 0, and
  `BOOTSTRAP_FAILURE_BA_DOWNLOAD_FAILED_PRIMARY` with the same but `return-code` for a script that cannot be read.
  `ErrorData` is a list of maps, and no source shows how AWS spreads the keys over them: Floci gives one map holding
  them all. `ErrorMessage` is the reason's message and the error code's page, in Floci's words, as AWS advises
  against parsing that text. A cluster that ends with `INTERNAL_ERROR` has no `ErrorDetails`.
* **Node files**: an EMR node's `/mnt/var/lib/info/instance.json` says whether it is the primary node, in its
  `isMaster`, which the Management Guide's bootstrap action reads (`grep isMaster /mnt/var/lib/info/instance.json |
  grep false` on any other node), and its `/mnt/var/lib/instance-controller/extraInstanceData.json` gives its
  `instanceRole`, which AWS's Knowledge Center reads with `jq` and compares, lowercased, with `master`, `core` and
  `task`. AWS documents nothing else in either file, so Floci writes those two fields alone: `{"isMaster": true}` and
  `{"instanceRole": "MASTER"}` in the bootstrap actions' container and each step's, which run as on the primary node,
  and in the primary node's own, and `false` with `CORE` or `TASK` in each core or task node's, the roles as the API's
  `InstanceRoleType` names them, and in each core or task node's bootstrap container its own (see **Bootstrap
  actions**). Files that cannot be written fail the bootstrap, the step or the node they are for.
* **States**: a step is `RUNNING` from when it starts until its driver or command exits; `step-duration-seconds`
  does not apply. The first step of a release also waits while its image is pulled (3.5 to 4.3 GB
  unpacked; see [`floci.docker.image-pull-timeout-seconds`](../configuration/docker.md)). It is then
  `COMPLETED` on exit code 0, and `FAILED` otherwise. A `FAILED` step's `Status.FailureDetails` has the `Reason`
  "Unknown Error", which the API reference says EMR gives when it cannot find the root cause, and a
  `Message` in Floci's own words, such as "The Spark driver exited with code 1." or "The step's command
  exited with code 1.".
* **ActionOnFailure**: a failed step's action is taken as the API reference and the Management Guide's
  termination protection table give it. `CONTINUE` runs the next step. `TERMINATE_CLUSTER` (or
  `TERMINATE_JOB_FLOW`) ends the cluster, unless it waits after its steps
  (`KeepJobFlowAliveWhenNoSteps`) and is termination protected, when it goes on. `CANCEL_AND_WAIT`
  cancels the `PENDING` steps and leaves the cluster `WAITING`, or ends it when it is set to end after
  its steps. A cluster a step failure ends cancels its other steps and goes `TERMINATING`, then
  `TERMINATED_WITH_ERRORS`, with the reason `STEP_FAILURE` and the message "Shut down as step failed".
  While `StepConcurrencyLevel` is above 1 no action is taken, and with level 1 and other steps still
  running, a `CANCEL_PENDING` one among them, `CANCEL_AND_WAIT` is not either, as the guide says.
* **Cancelling**: `CancelSteps` on a `RUNNING` step, and `TerminateJobFlows`, stop its driver at once; a
  cancelled step takes no `ActionOnFailure`. A step Floci was running when it stopped is `FAILED` once
  Floci is back, and its `ActionOnFailure` is taken then; one it was cancelling is `CANCELLED`, in either runner
  mode. Floci's
  shutdown and a state reset stop every driver, then the clusters' HDFS, a step not yet at its driver as if its driver were stopped, and start no other
  step; a driver a hard stop left running is removed when the EMR service
  starts, before any step can start, unless `reconcile-containers-on-startup` is off.
* **What `spark-submit` gets**: the step's arguments, after the `spark-defaults` properties of the
  cluster's `Configurations` and its `core-site` and `spark-hive-site` ones as `spark.hadoop.` settings,
  which Spark's Hadoop configuration, and its Hive client's, take as they would `core-site.xml` and
  `hive-site.xml`, so the Management Guide's `hive.metastore.client.factory.class` for the Glue Data Catalog
  applies; a `spark-hive-site` setting wins over the same one in `core-site`, and a `spark.hadoop.` setting
  in `spark-defaults` over both. `s3://` and `s3a://` paths reach Floci's S3 through S3A, on releases
  before 7.10.0 too, where `s3://` would otherwise be EMRFS, so the application and its files can be read
  from there; the Glue Data Catalog client is pointed at Floci's Glue, with the cluster's account as its
  `hive.metastore.glue.catalogid` (from `emr-7.10.0` the client would otherwise ask STS for it), which a
  `spark-hive-site` one replaces for a Data Catalog in another account; and `hdfs://` paths, and those
  without a scheme, at the cluster's HDFS. The cluster's `spark-log4j2` properties, which "Change values in
  Spark's log4j2.properties file", follow the `log4j2.properties` of the folder a `spark-env` export of
  `SPARK_CONF_DIR` names, else of the image's, in a copy of the step's own, a later key winning, so
  `rootLogger.level` set to `warn` quiets Spark's `INFO` lines. Its `HADOOP_CONF_DIR` is the copy of the
  cluster's Hadoop configuration a command's `hadoop` gets, `mapred-site` included (see **Hadoop streaming
  and MapReduce**). Other classifications (`spark-metrics`, `spark-log4j` for releases before 6.8.0,
  `mapred-env` and the rest) are not applied.
* **A step's `Properties`**: "Java properties that are set when the step runs. You can use these properties
  to pass key-value pairs to your main function", as the API Reference puts it. A custom JAR step's are
  system properties of the JVM its main class runs in, which `System.getProperty` reads, passed to
  `spark-submit` in `SPARK_SUBMIT_OPTS` after any Java options a `spark-env` export puts there, so a step's
  own win. A `command-runner.jar` or `script-runner.jar` step's would be those of the runner's own JVM,
  which Floci has none of, and a JVM's system properties do not pass to a process it starts, so they go
  nowhere. This is Floci's reading, as that sentence is the only source.
* **`spark-env`**: the variables the cluster's `spark-env` exports, in its nested `export` classification,
  are in each step's container environment, `PYSPARK_PYTHON` or `JAVA_HOME` say, replacing any of Floci's
  own with the same name. They are set as given, with no shell expansion of a `$VARIABLE` in
  them, and a command or script sees them too, where EMR writes them in `spark-env.sh`, which only Spark's
  own scripts read. The image's `spark-env.sh` gives `PYSPARK_PYTHON`, `PYSPARK_DRIVER_PYTHON`,
  `SPARK_HOME` and `HADOOP_CONF_DIR` only a default, so the exports win there, but a variable it sets
  outright, such as `AWS_STS_REGIONAL_ENDPOINTS`, keeps the image's value in `spark-submit`.
* **Credentials**: the container gets a session for the step's `ExecutionRoleArn`, or else for the role
  of the cluster's `JobFlowRole` instance profile, when IAM knows it and the profile holds one role,
  and otherwise the account's placeholder credentials, with `AWS_ENDPOINT_URL` pointing at Floci.
* **Instance metadata**: each node of an EMR cluster is an EC2 instance, so any process on it, a bootstrap action, a
  step's command or a Spark executor YARN runs, reads its instance metadata at `http://169.254.169.254`, IMDSv1 and
  IMDSv2. On a cluster with a Docker network of its own (see **Storage**), a relay container on that network holds
  that address, as ECS's credentials endpoint is held, and each of the cluster's containers gets a link-local address
  of its own there, so no container needs privileges; the relay forwards to Floci's EC2 metadata server, which answers
  each as the instance it is: the bootstrap actions and each step's container as the primary node, each core or task
  node's container as that node. Each answers with the instance id, type, private address and name and public name
  `ListInstances` gives it, the cluster's account, the cluster's availability zone, which `DescribeCluster`'s
  `Ec2InstanceAttributes` gives as `Ec2AvailabilityZone` (the one `RunJobFlow`'s `Instances.Placement` names, else the
  Region's first), and the `JobFlowRole` instance profile, whose role's credentials `iam/security-credentials` gives
  when IAM holds the profile with one role. Only the cluster's own containers reach its relay, and the metadata server
  trusts the caller the relay names only from the relays Floci started. A relay that cannot start fails the step that
  would read it, or ends the cluster as bootstrap actions that could not run do. Where the steps share a namespace or
  have none, there is no relay.
* **Output**: the driver's output goes to Floci's log, prefixed `emr:<stepId>`. With a `LogUri` on the
  cluster, its standard output and error also go to Floci's S3 once the step ends, gzipped, as
  `stdout.gz` and `stderr.gz` under `<LogUri>/<cluster-id>/steps/<step-id>/`, the step log folder of the
  Management Guide's "Log locations" (an `s3n://` or `s3a://` `LogUri` names the same bucket). A
  cancelled step's logs hold what its driver wrote until it was stopped. Logs that cannot be written,
  to a bucket that does not exist say, leave the step as it ended. On a cluster that runs YARN, the logs of its
  applications' containers, a cluster-mode driver's output among them, go gzipped to
  `<LogUri>/<cluster-id>/containers/<application>/<container>/` when a step ends, "Application container logs. The logs
  for each YARN application are stored in these locations", but for the `launch_container.sh` that holds a container's
  credentials. The `controller` and `syslog` logs
  and the other log folders are not written, nothing is copied while a step runs (EMR copies its logs
  every five minutes), and `FailureDetails` has no `LogFile`.
* **Image**: `image-template` names another image, such as a mirror or a dated tag of the release's
  image: `registry.internal/emr-serverless/spark/%s:20250425`, and `hive-image-template` another Hive image:
  `registry.internal/emr-serverless/hive/%s:20250425`. Every cluster's steps need both, the Hive image for the
  cluster's HDFS (see **Storage**), so a mirror sets both, and carries `emr-7.5.0`'s Hive image for an `emr-spark-*`
  release. A release with no EMR Serverless image, or another with no EMR Serverless Hive image, fails its steps
  with "The step could not start: Could not pull image".

### Known limitations

- **No more nodes than `yarn-max-worker-nodes`.** A cluster that names more core and task instances runs that many,
  core nodes first, while `ListInstances` still lists them all. Its core and task nodes offer the same capacity whatever
  their instance type.
- **An instance fleet's unit of capacity is one instance.** Floci does not choose among a fleet's instance types as EMR
  does: `ListInstances` gives each of its instances the first in its `InstanceTypeConfigs`, and its target capacity is
  divided by no `WeightedCapacity`, so it has an instance and a node for each unit.
- **A resize shows at once.** An instance group is `RUNNING` with its new count, and an instance fleet its new capacity,
  as soon as the resize is asked for, while the nodes take a few seconds to follow, or longer to decommission. A core
  group shrunk below the copies HDFS keeps says the count asked for, while its nodes stop at the copies; EMR documents
  that it does not scale below them, but no error for the request.
- **No YARN node labels.** An application master can run on any node, as on EMR's 6.x releases by default. EMR's 7.x
  releases label nodes by market type and 5.x's keep application masters on core nodes.
- **No nodes for a cluster made with `MasterInstanceType`, `SlaveInstanceType` and `InstanceCount`.** Floci keeps no
  instance groups for that older form of `RunJobFlow`, so such a cluster runs on its primary node alone.
- **An `emr-spark-*` cluster's HDFS and YARN are `emr-7.5.0`'s.** The Release Guide lists both at Hadoop 3.4.2 for
  `emr-spark-8.0.0` and `emr-spark-8.1.0`, and EMR Serverless publishes no image holding them, so the primary node runs
  Hadoop 3.4.0's. Spark and its Hadoop client are the release's own.
- **No YARN in a network namespace the steps share, or with no network.** With `docker-network` set to `host` or
  `container:<id>`, YARN's daemons would listen on every interface of the namespace, and with `none` the steps could not
  reach them, so the cluster's Spark runs in local mode.
- **MapReduce and Tez in local mode where there is no YARN.** Only a cluster that runs YARN (see **YARN**) runs them on
  it; on any other, `hadoop-streaming`, a custom JAR's MapReduce and Hive's and Pig's Tez run in the step's
  container.
- **Pig is Apache's 0.18.0, and only with `pig.enabled`.** EMR's 7.x releases ship Pig 0.17.0, whose Apache build is
  for Hadoop 2; no AWS image carries Pig, so Floci downloads Apache's 0.18.0, for Hadoop 3, when it is enabled, and
  runs it on the image's Tez (see **Pig steps**).
- **Tez's intermediate data is not compressed on YARN.** The images name the LZO codec for it, whose native library they
  lack, so Floci turns that compression off, which a `tez-site` classification can turn back on with a codec they have.
- **No Hive metastore service in a network namespace the steps share.** With `docker-network` set to `host` or
  `container:<id>`, the primary node runs HDFS but not Hive's metastore service: Hive 3's has no setting for the
  address it listens on, so it would serve its tables, and the files under them, on every interface of the
  namespace, the host's own for `host`. Hive steps there keep an embedded Derby database in the cluster's `/mnt`, so
  only one Hive step at a time can use it, and Spark steps see a Hive step's tables only through the Glue Data
  Catalog. On a cluster's own network, the default, the service runs (see **Storage** and **Hive steps**).

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
| `CancelSteps` | `CANCELLED` for each `PENDING` step it cancels; for a `RUNNING` one, `CANCEL_PENDING`, then `CANCELLED` once its driver is gone (at once in the `immediate` runner mode), and `RUNNING` for the step it makes room for |

A change that falls due between calls, such as a step completing after `step-duration-seconds`, is
made at that time and its events sent then, whether or not anything reads the cluster.

A `STARTING` or `WAITING` cluster's `stateChangeReason` is `{"code":""}`, as in the guide's example,
and a terminated one's carries the reason's code and message, such as
`{"code":"USER_REQUEST","message":"Terminated by user request"}`. `TERMINATED` is `INFO` for
`USER_REQUEST` and `ALL_STEPS_COMPLETED` and `CRITICAL` for any other reason, and
`TERMINATED_WITH_ERRORS` is `CRITICAL`; a `CANCEL_PENDING` or cancelled step is `WARN` and a failed one `ERROR`. The tables have no row for a cluster's `BOOTSTRAPPING`, `RUNNING` or `TERMINATING`, which
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
| `FLOCI_SERVICES_EMR_RUNNER_MODE` | `immediate` | `immediate` completes steps by time without running anything; `docker` runs each step, each session's Spark Connect server and each notebook execution's notebook in a container, after the cluster's bootstrap actions (see [Running steps](#running-steps), [Running a session's Spark Connect server](#running-a-sessions-spark-connect-server) and [Running a notebook execution's notebook](#running-a-notebook-executions-notebook)) |
| `FLOCI_SERVICES_EMR_DOCKER_NETWORK` | the global default | Docker network for step containers |
| `FLOCI_SERVICES_EMR_IMAGE_TEMPLATE` | `public.ecr.aws/emr-serverless/spark/%s:latest`, or `spark4/` in place of `spark/` for an `emr-spark-*` release | Image for a `docker` step, `%s` standing for the cluster's release label; a mirror sets `FLOCI_SERVICES_EMR_HIVE_IMAGE_TEMPLATE` too |
| `FLOCI_SERVICES_EMR_HIVE_IMAGE_TEMPLATE` | `public.ecr.aws/emr-serverless/hive/%s:latest` | The EMR Serverless Hive image a `docker` cluster takes HDFS and YARN from, and a cluster with Hive takes Hive from, `%s` standing for the cluster's release label, or `emr-7.5.0` for the Hadoop of an `emr-spark-*` release; see [Storage and Hive steps](#running-steps) |
| `FLOCI_SERVICES_EMR_YARN_NODE_MEMORY_MB` | `4096` | The memory a `docker` cluster's NodeManager offers the YARN containers its Spark executors run in (see [Running steps](#running-steps)) |
| `FLOCI_SERVICES_EMR_YARN_NODE_VCORES` | `4` | The virtual cores it offers them |
| `FLOCI_SERVICES_EMR_YARN_MAX_WORKER_NODES` | `4` | The most core and task nodes a `docker` cluster runs as containers of their own, core nodes first; 0 keeps a cluster on its primary node |
| `FLOCI_SERVICES_EMR_PIG_ENABLED` | `false` | Add Apache Pig, downloaded once per release's image, to a `docker` cluster whose `Applications` name Pig, so its Pig steps run (see [Running steps](#running-steps)) |
| `FLOCI_SERVICES_EMR_PIG_URLS` | `https://dlcdn.apache.org/pig/pig-0.18.0/pig-0.18.0.tar.gz,https://archive.apache.org/dist/pig/pig-0.18.0/pig-0.18.0.tar.gz` | Where Floci downloads Pig's binary distribution from, the first that answers; a mirror names its own |
| `FLOCI_SERVICES_EMR_PIG_SHA256` | `6845adb936a3c3bcc71451765953cae4103e410ba2f6c5a47cc9becade0a434a`, `pig-0.18.0.tar.gz`'s | The SHA-256 the download must have; set it with another distribution |
| `FLOCI_SERVICES_EMR_RECONCILE_CONTAINERS_ON_STARTUP` | `true` | Remove the step drivers and bootstrap containers a previous run of this Floci left on the Docker daemon, and the images bootstrap actions left for clusters that are gone or have ended, when the EMR service starts; turn it off when two Floci instances share a daemon with the same port and no resource namespace |

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
