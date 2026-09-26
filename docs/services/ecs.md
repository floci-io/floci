# ECS (Elastic Container Service)

**Protocol:** JSON 1.1
**Endpoint:** `POST /` + `X-Amz-Target: AmazonEC2ContainerServiceV20141113.<Action>`

ECS emulates clusters, task definitions, tasks, and services. In the default configuration tasks run as real Docker containers. Set `mock: true` (enabled automatically in tests) to run tasks as in-process stubs without Docker.

A value an enum-typed member does not have is rejected with `InvalidParameterException` rather
than treated as absent. That matters more than it sounds: a `launchType` of `EC22` silently
ignored would fall through to Floci's default and place the task on Fargate, which is neither
what the caller asked for nor what AWS answers.

## Supported Operations

### Clusters

| Operation | Description |
|---|---|
| `CreateCluster` | Create a cluster (idempotent) |
| `DescribeClusters` | Describe one or more clusters |
| `ListClusters` | List cluster ARNs |
| `UpdateCluster` | Update cluster settings |
| `UpdateClusterSettings` | Update `containerInsights` and other settings |
| `PutClusterCapacityProviders` | Associate capacity providers with a cluster |
| `DeleteCluster` | Delete an empty cluster |

A cluster round-trips what it was created with: `settings`, `tags`, `configuration`,
`serviceConnectDefaults`, `capacityProviders` and `defaultCapacityProviderStrategy` are all
accepted on `CreateCluster` and returned as sent. `UpdateCluster` replaces only the members the
request named, leaving the ones it omits alone; `UpdateClusterSettings` replaces `settings`
outright.

`DescribeClusters` returns `settings`, `tags`, `statistics`, `attachments` and `configuration`
only when `include` asks for them, one of `SETTINGS`, `TAGS`, `STATISTICS`, `ATTACHMENTS` and
`CONFIGURATIONS`; any other value is rejected. A member that was asked for is returned even when
it is empty, so a client can tell an empty answer from a withheld one. `STATISTICS` reports the
eight documented counts split by launch type (`runningEC2TasksCount`, `runningFargateTasksCount`,
`pendingEC2TasksCount`, `pendingFargateTasksCount`, `activeEC2ServiceCount`,
`activeFargateServiceCount`, `drainingEC2ServiceCount`, `drainingFargateServiceCount`) as
name/value strings. The API reference spells one of those names `RunningFargateTasksCount`; Floci
follows the wire, which is lower camel case like the other seven. `ATTACHMENTS` is always an empty
list, because Floci creates no managed scaling policies to attach.

`DescribeClusters` reports a cluster it cannot resolve as a `MISSING` entry in `failures` rather
than dropping it, as `DescribeServices` and `DescribeTasks` do. `ListClusters` pages with
`maxResults` (1 to 100) and `nextToken`, returning up to 100 ARNs when the request names neither.

`DeleteCluster` only deletes an empty cluster, and says which way it is not empty:
`ClusterContainsServicesException` while a service is still `ACTIVE`,
`ClusterContainsContainerInstancesException` while a container instance is still registered, and
`ClusterContainsTasksException` while a task is still running. Delete the services, deregister the
instances and stop the tasks first, as on AWS.

### Task Definitions

| Operation | Description |
|---|---|
| `RegisterTaskDefinition` | Register a new revision of a task definition |
| `DescribeTaskDefinition` | Describe a task definition by family:revision or ARN |
| `ListTaskDefinitions` | List task definition ARNs |
| `ListTaskDefinitionFamilies` | List task definition family names |
| `DeregisterTaskDefinition` | Mark a revision INACTIVE |
| `DeleteTaskDefinitions` | Delete one or more task definitions |

A task definition round-trips whole. Members Floci acts on are modelled
(`ephemeralStorage`, `pidMode`, `ipcMode`, `runtimePlatform`, and at container level `dependsOn`,
`startTimeout`, `stopTimeout`, `user`, `workingDirectory`, `readonlyRootFilesystem`,
`environmentFiles`, `dockerLabels`, `repositoryCredentials` and the rest); everything else it does
not act on, such as `proxyConfiguration`, `linuxParameters`, `ulimits`, `resourceRequirements`,
`systemControls` and placement constraints, is kept verbatim and returned as registered. A client
that reads back what it wrote (Terraform, or a deploy tool verifying its own
`RegisterTaskDefinition`) sees no drift. `runtimePlatform` does not change where a local task
runs: Floci launches every task on the host's own architecture.

The round trip reaches inside the members Floci does parse. A port mapping keeps its `name`,
`appProtocol` and `containerPortRange`, which is what a service's `serviceConnectConfiguration`
and `vpcLatticeConfigurations` reference by name, and a volume keeps the configurations Floci
backs with nothing: `dockerVolumeConfiguration`, `fsxWindowsFileServerVolumeConfiguration`,
`s3filesVolumeConfiguration` and `configuredAtLaunch`.

`compatibilities` is derived from the definition rather than echoed from
`requiresCompatibilities`: ECS reports the launch types a definition actually works on, so an
`awsvpc` definition with a valid Fargate size pair and no Fargate-unsupported parameters comes
back as `["EC2", "FARGATE"]` even when it never asked for FARGATE. `EXTERNAL` is added for any
network mode other than `awsvpc`, which ECS Anywhere instances do not support. The derivation runs
Fargate's own registration rules, so it can never disagree with what `requiresCompatibilities:
["FARGATE"]` would have accepted.

`requiresAttributes` names the container instance capabilities the definition needs for EC2
placement. ECS derives these from the container agent's capability model, which AWS does not
publish in full, so Floci derives the subset whose names appear verbatim in the AWS documentation:
the base `com.amazonaws.ecs.capability.docker-remote-api.1.18`, `ecs.capability.task-eni` for
`awsvpc`, `com.amazonaws.ecs.capability.task-iam-role` (or
`...task-iam-role-network-host` under `host` networking) for a `taskRoleArn`,
`com.amazonaws.ecs.capability.logging-driver.<driver>` per container log driver,
`com.amazonaws.ecs.capability.ecr-auth` for an ECR image, and
`com.amazonaws.ecs.capability.privileged-container`. Capabilities outside that set are not
reported.

A revision goes ACTIVE, then INACTIVE on `DeregisterTaskDefinition` (which stamps
`deregisteredAt`), then `DELETE_IN_PROGRESS` on `DeleteTaskDefinitions` (which stamps
`deleteRequestedAt`). Deleting a revision that has not been deregistered is refused, as is a
reference that names only the family: `DeleteTaskDefinitions` takes a revision, at most 10 per
call. A deleted revision is not dropped, because AWS keeps describing it and the tasks already on
it keep running; it just cannot start anything new, so `RunTask`, `CreateService` and
`UpdateService` refuse it.

`ListTaskDefinitions` lists only `ACTIVE` revisions unless another `status` is asked for, orders
them lexicographically by family and then numerically by revision (`sort: DESC` reverses it, so
revision 11 sorts after revision 2 rather than before it), and pages with `maxResults` and
`nextToken`.

`ListTaskDefinitionFamilies` filters on `familyPrefix` and on `status`, where `ACTIVE` keeps the
families with at least one ACTIVE revision, `INACTIVE` the families with none, and `ALL` (the
default) keeps both. It pages with `maxResults` and `nextToken` the same way.

At launch, a container's `dependsOn` decides the start order, and a `COMPLETE`, `SUCCESS` or
`HEALTHY` condition holds the dependent container until the dependency gets there. The wait is
bounded by the dependent container's `startTimeout`, defaulting to 60 seconds rather than AWS's
longer agent default, because `RunTask` here answers synchronously. A container's `cpu` becomes its
CPU shares, its `memory` its hard limit, and a container without a `memory` of its own is capped at
the task's; the task's `cpu` becomes a CPU quota. `stopTimeout` is the grace period the container gets
on teardown; a container that does not ask for one gets 5 seconds rather than AWS's 30, because
`StopTask` here answers synchronously and most containers ignore SIGTERM as PID 1. A container
still running when its grace period is up is killed, so its exit code is always reported.

`firelensConfiguration` is stored and returned the same way. `RegisterTaskDefinition` rejects a
missing or unsupported `type` (`fluentd` and `fluentbit` only), and a task using `awsfirelens`
must name exactly one router: a task definition with two FireLens routers, or a router publishing
port `24224`, is rejected at launch. A `fluentbit` or `fluentd` FireLens
container is acted on at launch: Floci generates the router config (unix socket input, TCP forward
on bridge/awsvpc, ECS metadata, optional include of a `config-file-type=file` or `s3` extra
config, and one output per `awsfirelens` container), starts that router first, and points application
containers with `logDriver: awsfirelens` at the generated unix socket. Other log drivers,
including `awslogs`, still stream to CloudWatch via Floci rather than the configured driver.
An `[OUTPUT]` for an AWS destination whose plugin reads a URL from `endpoint` (`s3`,
`cloudwatch`, `firehose`) also gets `Endpoint` set to Floci's container-reachable base URL. The
Fluent Bit AWS plugins take a custom endpoint only from their own configuration and ignore the
`AWS_ENDPOINT_URL` injected into the container, so without it the router would ship logs to the
real service. An `endpoint` set in the task definition's log options is never overwritten, so
aiming one output at real AWS still works, and outputs declared in an `@INCLUDE`d or
`config-file-type=s3` config are not visible to Floci and keep whatever endpoint they were
written with.
The upstream C plugins (`cloudwatch_logs`, `kinesis_firehose`, `kinesis_streams`) are left
alone instead. They hand `endpoint` to `getaddrinfo` as a bare host name rather than parsing it as
a URL, and they always dial TLS, so Floci's `http://host:port` base URL fails there as
`Misformatted domain name` and a bare host fails certificate verification; no output-level switch
disables either. On the `aws-for-fluent-bit` 3.x line these plugins also honour a separate `port`
(undocumented for `kinesis_firehose` and `cloudwatch_logs`, but it works), so an output can be
aimed at Floci's port, but it still cannot complete the TLS handshake. Those outputs go wherever
the task definition points them.
An injected `http://` endpoint also gets `tls Off`. Fluent Bit 1.9 (the `aws-for-fluent-bit` 2.x
and `:latest` line) still calls `flb_tls_session_create` on HTTP S3 and SIGSEGVs on a NULL
TLS context; the scheme alone is not enough. A `tls` the task definition already set is left
alone.
The TCP forward listens on `0.0.0.0` rather than AWS's awsvpc `127.0.0.1` because Floci only
shares a network namespace when security-group enforcement is enabled for an awsvpc task. In every
other case, the injected `FLUENT_HOST` (the router's container IP) must be reachable. Fluent Bit
config is written to `/fluent-bit/etc/fluent-bit.conf`. Fluentd config is
written to `/fluentd/etc/fluent.conf` and uses `@type` (not `Name`) for output plugins; Floci
does not inject an `endpoint` into Fluentd outputs.
`config-file-type=s3` follows where ECS itself draws the line. `RegisterTaskDefinition` rejects
it for a Fargate-compatible task definition, with `Fargate launch type does not support
FirelensConfiguration config file from 's3'`, and rejects a `config-file-value` that is not an S3
object ARN with `Invalid arn syntax`. A Fargate task can still take its config from S3 the way AWS
documents, by giving the aws-for-fluent-bit init process its `aws_fluent_bit_init_s3_*`
environment variables. ECS never inspects those and Floci passes them through, so that
registration is accepted here too; the init process reads the task metadata endpoint before
downloading, and Floci serves one (see [Task metadata endpoint](#task-metadata-endpoint)).
Floci does not validate a task definition's `compatibilities` /
`requiresCompatibilities` against `RunTask` `launchType`; a Fargate-compatible
definition can still be run with `launchType=EC2` (and the reverse).
On an EC2-compatible task definition Floci reads the object from its own S3, writes it to the
fixed `external.conf` path next to the generated config (`/fluent-bit/etc/external.conf` or
`/fluentd/etc/external.conf`), and includes it from there, matching the paths the ECS agent uses.
The object is read before any container is created, so a missing bucket or key stops the task with
the agent's reason, `Unable to download firelens s3 config file: unable to download s3 config
<key> from bucket <bucket>: <detail>`, instead of leaking a started router. Shared network
namespaces (AppConfig agent on `127.0.0.1:2772`) are not implemented.

Container `volumesFrom` entries are also stored and returned. In Docker mode, source containers
are launched before their consumers and their declared volumes are inherited with the requested
read-only or read-write access mode. Startup ordering also respects FireLens router dependencies;
cycles involving both volume inheritance and log routing are rejected before containers start.

### Tasks

| Operation | Description |
|---|---|
| `RunTask` | Launch one or more task instances |
| `StartTask` | Start a task on specific container instances |
| `StopTask` | Stop a running task |
| `DescribeTasks` | Describe one or more tasks |
| `ListTasks` | List task ARNs (filterable by cluster, family, service, status) |
| `UpdateTaskProtection` | Set scale-in protection for tasks |
| `GetTaskProtection` | Get current task protection state |
| `ExecuteCommand` | Open an ECS Exec session into a container (see [ECS Exec](#ecs-exec)) |

### Fargate

A task definition that declares `FARGATE` in `requiresCompatibilities` is held to Fargate's rules
at registration:

- `awsvpc` network mode.
- A task-level `cpu` and `memory` forming one of the documented pairs, from 256 CPU units through
  32768 (the 32 vCPU tier offers only 60 GB, 120 GB and 244 GB), in CPU units or the `1 vCPU` and
  `1 GB` string forms. Windows rules out the sub-vCPU sizes.
- An `ephemeralStorage.sizeInGiB` between 21 and 200.
- None of the parameters that are not valid in a Fargate task: `disableNetworking`,
  `dnsSearchDomains`, `dnsServers`, `dockerSecurityOptions`, `extraHosts`, a `GPU`
  `resourceRequirements` entry, `ipcMode`, `links`, `placementConstraints`, `privileged`,
  `linuxParameters.maxSwap`, `linuxParameters.swappiness`, a `pidMode` other than `task`, or a
  host volume with a `sourcePath`. All of them are accepted on an EC2-compatible definition.
- A `dependsOn` graph that names only containers of the same task definition and has no cycle.

A launched Fargate task reports what AWS reports: `platformVersion` (with `LATEST` resolved to a
concrete version, as AWS resolves it) and `platformFamily` (`Linux`, or the Windows Server family
from the task definition's `runtimePlatform.operatingSystemFamily`), `connectivity` and
`connectivityAt`, `healthStatus`, `stopCode`, `version`, `availabilityZone`, `attributes` with
`ecs.cpu-architecture`, `pullStartedAt` / `pullStoppedAt` / `stoppingAt` / `executionStoppedAt`,
`ephemeralStorage` and `fargateEphemeralStorage`, and `enableExecuteCommand`. `overrides` is
always present with one `containerOverrides` entry per container, carrying whatever the request
overrode, which is how AWS answers a task nobody overrode. `group` defaults to `family:<family>`
for a `RunTask` and is `service:<name>` for a service's tasks. A container that asked for no CPU
units reports `"cpu": "0"`, as on AWS.

A task's `attachments` entry is an `ElasticNetworkInterface` carrying `subnetId`,
`networkInterfaceId`, `macAddress`, `privateDnsName` and `privateIPv4Address`. `DescribeTasks` and
`DescribeServices` report tags only when the request asks for them with `include: ["TAGS"]`, as
`DescribeTaskDefinition` already did. A task `DescribeTasks` cannot resolve comes back as a
`MISSING` entry in `failures`, which is what the `TasksRunning` and `TasksStopped` waiters treat
as terminal.

A created service reports the documented defaults: `propagateTags` is `NONE` and
`healthCheckGracePeriodSeconds` is `0` when the request sets neither. A `role` is accepted only on
a load-balanced service whose task definition does not use `awsvpc`, which is the only case AWS
permits it in.

A described service always carries `events` and `taskSets`, empty list and all, so a client can
tell an empty answer from a member Floci never wrote. `taskSets` holds the service's task sets, the
same ones `DescribeTaskSets` returns, which is where a blue/green deploy tool reads them from.
`events` is derived from the service's current state rather than replayed from a rollout, the same
way `deployments` is: a service that has converged reports the one event tools poll for,
`(service <name>) has reached a steady state.`, with an id stable across calls; one still
converging reports none. Each `deployments` entry carries the placement it runs under, its
`launchType` or `capacityProviderStrategy`, `platformVersion`, `platformFamily` and
`networkConfiguration`, not only its id and counts.

`RunTask` places at most 10 tasks per call and rejects `propagateTags: SERVICE`, which is a
service-only option; `TASK_DEFINITION` copies the task definition's tags onto each task.
`StartTask` requires the `containerInstances` it places onto, at most 10 of them.

`ListTasks` filters by `cluster`, `family`, `desiredStatus`, `launchType`, `containerInstance`,
`serviceName` and `startedBy`, and pages with `maxResults` and `nextToken` (100 per page by
default). The default filter is a desired status of `RUNNING`, so a listing that has not asked for
stopped tasks does not get them, `PENDING` matches nothing (ECS never sets that desired status),
and `startedBy` has to be the only filter in the request.

On `UpdateService`, the changes that start new tasks roll the deployment: the task definition, the
network configuration, the load balancers, the service registries, the Service Connect
configuration, `platformVersion` and `forceNewDeployment`. The ones AWS documents as not
triggering a deployment (`desiredCount`, `deploymentConfiguration`, `enableExecuteCommand`,
`enableECSManagedTags`, `propagateTags`, `healthCheckGracePeriodSeconds`,
`availabilityZoneRebalancing`, the capacity provider strategy and the placement members) are
applied without rolling anything. An update replaces only the members it names: one that sends
`placementStrategy` alone leaves a `placementConstraints` stored earlier in place, and an empty
array clears a member the way AWS clears it.

When a service replaces or stops a task in Docker mode, the task remains `STOPPING` until its
containers are removed. A failed Docker removal is retried on later reconciliation ticks, so the
task is not reported as `STOPPED` while its container may still be serving.

Every `awsvpc` task gets a real ENI in the subnet it asked for, in Docker and in mock mode alike,
and reports it as an `attachments` entry with its `networkInterfaceId`, `privateIPv4Address` and
`subnetId`; the task's containers report the same interface in `networkInterfaces`. A subnet that
does not exist fails the request rather than being ignored. While the task runs, `ec2
DescribeNetworkInterfaces` reports that ENI as `in-use` and refuses to delete it, so it never
answers a search for a free interface; it carries no EC2 attachment, every member of one naming an
instance that a task does not have. The ECS attachment is reported as `DELETED` once the task
stops, and the ENI is released. Security-group enforcement
(`FLOCI_NETWORK_SECURITY_GROUP_ENFORCEMENT_ENABLED`) adds packet filtering on top of the ENI; it is
not needed for the ENI itself.

#### Capacity providers

`capacityProviderStrategy` is honoured on `RunTask` and `CreateService`, including the built-in
`FARGATE` and `FARGATE_SPOT` providers. Tasks are spread the way ECS spreads them: the single entry
that declares a `base` is filled first, then the rest are split by weight, with the remainder of
the integer split going to the heaviest entries. A strategy and a `launchType` in the same request
are rejected, as is an unknown provider or a second `base`, and a cluster's
`defaultCapacityProviderStrategy` applies when a request carries neither. The documented bounds
are enforced: at most 20 providers in a strategy, a `weight` of 0 to 1,000 and a `base` of 0 to
100,000. A provider of `weight` 0 places nothing beyond its base, so a strategy naming several
providers that all weigh 0 is rejected; a lone provider of `weight` 0 still places the tasks,
since the request named no alternative.

A task placed through a provider reports `capacityProviderName` **and** the `launchType` that
provider resolves to. A service reports one or the other: `DescribeServices` omits `launchType`
for a service created with a strategy, and omits `capacityProviderStrategy` for one created with a
launch type, which is what AWS documents for the `Service` shape.

With no launch type, no strategy and no cluster default, a task keeps Floci's `FARGATE` default: a
local cluster has no container instances, so an EC2 default would have nowhere to place it.

#### Task metadata endpoint

Floci serves the [task metadata endpoint version
4](https://docs.aws.amazon.com/AmazonECS/latest/developerguide/task-metadata-endpoint-v4-fargate.html)
and injects `ECS_CONTAINER_METADATA_URI_V4` into every container it launches. AWS serves it on the
link-local address `169.254.170.2`, which a local container cannot be given, so Floci serves the
same paths on its own port; applications and the AWS SDKs read the environment variable rather than
the address, so they work unchanged.

| Path | Returns |
|---|---|
| `/v4/{id}` | The container's metadata |
| `/v4/{id}/task` | The task's metadata, including every container |
| `/v4/{id}/taskWithTags` | The task's metadata with its tags and its container instance's (EC2 only) |
| `/v4/{id}/stats` | The container's Docker stats |
| `/v4/{id}/task/stats` | The Docker stats of every container in the task, keyed by Docker id |

The `{id}` is minted per container at launch, as the ECS agent mints it. The task document carries
`Cluster`, `TaskARN`, `Family`, `Revision`, the statuses, `Limits` (CPU in vCPUs), the pull
timestamps, `AvailabilityZone`, `LaunchType`, `ServiceName` for a service's task, `VPCID` for an
EC2 task, and, for Fargate, `ClockDrift` and `EphemeralStorageMetrics`. Floci has no clock drift to
report and does not meter the disk, so those two report a synchronized clock and zero usage.

An `awsvpc` container's `Networks` object describes the task ENI and the subnet it sits in:
`NetworkMode`, `IPv4Addresses`, `AttachmentIndex`, `MACAddress`, `PrivateDNSName`,
`IPv4SubnetCIDRBlock`, `SubnetGatewayIpv4Address`, `DomainNameServers` and `DomainNameSearchList`.
A local ENI has no DHCP option set behind it, so the gateway is derived as the first address of the
subnet's CIDR, the resolver as the third address of the VPC's, and the search domain from the
region, the way a real VPC assigns them.

The stats paths read the Docker daemon when the request arrives, so they return the same
[ContainerStats](https://docs.docker.com/engine/api/v1.30/#operation/ContainerStats) document AWS
returns, plus the `network_rate_stats` the ECS agent adds. Docker reports cumulative counters only,
so the rates are taken across two consecutive samples: as on Fargate, that means a container has to
have run for about a second before its stats are there. A container Floci has no running Docker
container for reports an empty document instead, and one sampled only once reports its stats
without `network_rate_stats`: the path exists for every container in the task, whether or not the
daemon can measure it.

`/v4/{id}/task/stats` samples the task's containers in one pass, with every stream open at once,
rather than one after another. A sidecar polls that path for network metrics, so the response costs
about the single collection tick one container costs however many containers the task has.

`taskWithTags` is the container agent's path, so it answers for an EC2 task and 404s for a Fargate
one, as on AWS. A container's `CreatedAt`, `StartedAt` and `FinishedAt` are Docker's own for that
container, read when it starts and again when it stops; a task that never reached a daemon, which
means `mock: true` or one restored from storage, reports the task's timestamps instead.

#### ECS Exec

`ExecuteCommand` opens a real shell in a task's container. The session must be `interactive`, which
is the only mode ECS supports. The task must belong to the cluster the request names, must be
`RUNNING`, must have been run with
`enableExecuteCommand`, and must have a container behind it, which means Docker mode:
a mock-mode task reports the `ExecuteCommandAgent` as running but has no runtime to exec into, and
`ExecuteCommand` answers `TargetNotConnectedException`.

The response carries a `session` with a `streamUrl` pointing at Floci's own data channel and a
single-use `tokenValue`, so the AWS CLI works as documented:

```bash
aws ecs execute-command --cluster my-cluster --task <task-arn> \
  --container app --interactive --command "/bin/sh" \
  --endpoint-url $AWS_ENDPOINT_URL
```

Floci plays the SSM agent's half of the Session Manager protocol on that channel (the binary
`AgentMessage` framing, the handshake, sequenced acknowledgements and terminal resizes) and bridges
it to a `docker exec` in the container. Deliberate limits:

- The command runs through `/bin/sh -c`, so an image without a shell cannot be exec'd into.
- Sessions are in memory, single use, and expire after five minutes if nobody connects.
- `ExecuteCommand` logging (the `executeCommandConfiguration` on a cluster, which sends session
  transcripts to S3 or CloudWatch) is not implemented.

### Services

| Operation | Description |
|---|---|
| `CreateService` | Create a long-running service |
| `UpdateService` | Update desired count, task definition, or deployment config |
| `DeleteService` | Delete a service (supports `force`) |
| `DescribeServices` | Describe one or more services (includes `deployments`, see below) |
| `ListServices` | List service ARNs in a cluster |
| `ListServicesByNamespace` | List services filtered by Cloud Map namespace |

`ListServices` filters on `launchType` and `schedulingStrategy` as well as `cluster`, and pages
with `maxResults` and `nextToken`. Unlike every other ECS listing, which pages a hundred at a
time, a request that names no `maxResults` gets ten ARNs and a `nextToken`.

#### Service deployments

An `ACTIVE` service reports exactly one `PRIMARY` entry in `services[].deployments`,
synthesized from the service's current state rather than tracked as a rollout. This is
what AWS's `ServicesStable` waiter accepts on, so `aws ecs wait services-stable`, the
SDK waiters, and Terraform's `aws_ecs_service` all converge normally. A deleted
(`INACTIVE`) service reports an empty list.

`rolloutState` is `COMPLETED` once `runningCount` reaches `desiredCount`, and
`IN_PROGRESS` before that. The deployment `id` is derived from the service ARN and its
task definition, so it is stable across calls and across restarts, and rolls over when
the task definition changes. `createdAt` tracks the deployment rather than the service:
it is the service's creation time until a task-definition change starts a new
deployment, and moves with it thereafter.

Known differences from AWS:

- There is never a second `ACTIVE` deployment draining alongside the `PRIMARY` one.
  The running tasks *are* rolled onto a changed task definition (replacements on the new
  revision start first, then the stale tasks are drained, one reconciler tick apart), but
  the deployments list reports only the single `PRIMARY` throughout.
- `deployments` is reported for every service. AWS omits it for services that use the
  `CODE_DEPLOY` or `EXTERNAL` deployment controller; Floci records and echoes
  `deploymentController` (along with `schedulingStrategy` and
  `availabilityZoneRebalancing`; AWS defaults `ECS` / `REPLICA` / `ENABLED` on create) but
  still synthesises the `deployments` list regardless of the controller type.
- `DAEMON` scheduling runs exactly one task per `ACTIVE` container instance and derives
  `desiredCount` from that count; it is rejected for the Fargate launch type and for the
  `CODE_DEPLOY` / `EXTERNAL` controllers, as on AWS. Placement constraints are not evaluated.
- `pendingCount` is always `0`, matching the top-level service field.
- `forceNewDeployment` (with an unchanged task definition) mints a new deployment `id`
  and rolls the running tasks: a replacement on the new deployment starts first, then
  the task from the previous deployment is drained one reconciler tick later. The
  `deployments` list still reports a single `PRIMARY` throughout.
- `updatedAt` equals `createdAt`. AWS advances it as a rollout progresses; Floci has no
  intermediate rollout state to report.
- `deploymentConfiguration` (including the circuit breaker), `healthCheckGracePeriodSeconds`,
  `serviceRegistries` and the placement constraints and strategies are stored and reported as
  given, so a client that reads them back sees no drift, but the reconciler does not act on them:
  it converges to `desiredCount` without a maximum or minimum percent, registers nothing in Cloud
  Map, and places tasks without evaluating constraints.
- `StopServiceDeployment` is not implemented.

#### ECS EventBridge events

Floci publishes AWS-shaped lifecycle events to the **default** EventBridge bus
(`source: aws.ecs`). Rules matching `aws.ecs` fire from ECS activity, in both docker
and mock mode.

| `detail-type` | When | Key `detail` fields |
|---|---|---|
| `ECS Task State Change` | a task starts or stops | `lastStatus`, `desiredStatus`, `taskDefinitionArn`, `group`, `startedBy`, `stoppedReason`, `containers[].exitCode` |
| `ECS Deployment State Change` | a service deployment starts, is in progress, or reaches steady state | `eventType` (always `INFO`), `eventName`, `deploymentId` |

`eventName` is one of `SERVICE_DEPLOYMENT_STARTED`, `SERVICE_DEPLOYMENT_IN_PROGRESS`,
`SERVICE_DEPLOYMENT_COMPLETED`.

Known differences from AWS:

- The task phase ladder is **synthesized**. Floci's task model only occupies
  `PENDING`, `RUNNING` and `STOPPED`, but a start emits
  `PROVISIONING -> PENDING -> ACTIVATING -> RUNNING` and a stop emits
  `DEACTIVATING -> STOPPING -> DEPROVISIONING -> STOPPED`, one `ECS Task State Change`
  per phase, so rules that filter on `detail.lastStatus` behave as on AWS.
- `SERVICE_DEPLOYMENT_FAILED` and the deployment circuit breaker are not emitted.
- `SubmitTaskStateChange` / `SubmitContainerStateChange` remain ACK-only; Floci drives
  the task lifecycle itself rather than via agent submissions.

#### Service discovery

A service that declares `serviceRegistries` has each of its running tasks registered as a
Cloud Map instance of the named service, and deregistered when the task stops. The instance
carries `AWS_INSTANCE_IPV4` (the task's ENI address on an awsvpc task, otherwise the
container's address on the Docker network) and `AWS_INSTANCE_PORT` (the entry's `port`, or
the host port its `containerPort` was published on), alongside the metadata attributes AWS
records: `AVAILABILITY_ZONE`, `REGION`, `ECS_SERVICE_NAME`, `ECS_CLUSTER_NAME` and
`ECS_TASK_DEFINITION_FAMILY`. The task id is the instance id, as on AWS, so a replacement
task supersedes its predecessor.

Combined with [Cloud Map answering DNS](cloudmap.md#dns-resolution) for its namespaces, that
is what makes `<cloud-map-service>.<namespace>` resolve to a running task from another
container. Registration is best effort: a registry entry Floci cannot place is logged and
skipped rather than failing the task. `UpdateService` moves running task registrations to
replacement registries; stopping a task removes the registrations it actually created.

Known differences from AWS:

- AWS supports only `SRV` records for a `bridge` or `host` network mode task, since the
  reachable port is the published host port rather than the container port. Floci serves
  only A records, so such a task registers its address and its published host port and
  resolves by address; the port is readable through `DiscoverInstances` rather than DNS.
- `EC2_INSTANCE_ID` is not recorded. Every Floci task runs as a container rather than on a
  registered EC2 host, which is the Fargate case on AWS, where the attribute is also absent.
- An instance's health status never moves. AWS registers a task `UNHEALTHY` and promotes it to
  `HEALTHY` once the container health check passes, for a service discovery service that
  declares `HealthCheckCustomConfig`. Floci feeds no container health into Cloud Map, so a task
  registers `HEALTHY`, which is Cloud Map's own default for a `RegisterInstance` that names no
  `AWS_INIT_HEALTH_STATUS`, and stays there until it is deregistered.

#### Unknown services

A service reference that does not resolve is returned in `failures` with
`reason: MISSING` and the ARN the service would have had, rather than being dropped from
the response. `DescribeServices` therefore returns partial results instead of erroring, as
AWS does, and `aws ecs wait services-stable` on a nonexistent service fails immediately
instead of polling for its full timeout. A reference supplied as an ARN is echoed back
unchanged.

### Task Sets

| Operation | Description |
|---|---|
| `CreateTaskSet` | Create a task set inside a service |
| `UpdateTaskSet` | Update a task set's scale |
| `DeleteTaskSet` | Delete a task set |
| `DescribeTaskSets` | Describe task sets for a service |
| `UpdateServicePrimaryTaskSet` | Promote a task set to primary |

A task set can only be created in a service whose deployment controller is `EXTERNAL` or
`CODE_DEPLOY`; a rolling service runs its own deployments, and AWS refuses the call rather than
creating a task set nothing will place. `launchType` and `capacityProviderStrategy` are mutually
exclusive, and a request naming neither takes the cluster's `defaultCapacityProviderStrategy`,
falling back to `EC2`. `scale` accepts only the `PERCENT` unit.

The task set round-trips `networkConfiguration` (defaulting to the service's), `loadBalancers`,
`serviceRegistries`, `platformVersion` and `tags`, and reports `platformFamily`, `startedBy`
(`CODE_DEPLOY` for a task set Floci's CodeDeploy created, unset for an external one) and
`stabilityStatusAt`. `computedDesiredCount` is the service's `desiredCount` times the task set's
scale percentage, always rounded up: 3 desired at 50 percent is 2 tasks, not 1. `UpdateTaskSet`
recomputes it.

Floci places no tasks for a task set, so `runningCount` and `pendingCount` stay at 0 and
`stabilityStatus` is always `STEADY_STATE`. On AWS the status reaches `STEADY_STATE` once the
running count matches the computed one; reporting `STABILIZING` here would hang anything that
waits for the set to stabilise, since no count ever moves.

`DeleteTaskSet` refuses a task set that has not been scaled down to zero unless `force` is set.
`DescribeTaskSets` returns tags only for `include: ["TAGS"]` and reports a reference naming no
task set of that service as a `MISSING` failure. A task set reference that resolves to nothing is
a `TaskSetNotFoundException`, and task sets are scoped to one cluster and service: another
service's task set is not visible through this one.

### Container Instances

| Operation | Description |
|---|---|
| `RegisterContainerInstance` | Register a container instance with a cluster |
| `DeregisterContainerInstance` | Deregister a container instance |
| `DescribeContainerInstances` | Describe container instances |
| `ListContainerInstances` | List container instance ARNs |
| `UpdateContainerAgent` | Trigger agent update (stub) |
| `UpdateContainerInstancesState` | Drain or activate container instances |

`RegisterContainerInstance` keeps what the agent sent: `totalResources` comes back as both
`registeredResources` and `remainingResources`, and `versionInfo`, `attributes`, `tags` and a
re-registered `containerInstanceArn` all round-trip. The agent version is reported inside
`versionInfo`, which is where the `ContainerInstance` shape puts it, not as a top-level
`agentVersion`. An instance also reports `registeredAt` and a `version` counter that each state
change increments.

`DeregisterContainerInstance` leaves the instance in the cluster as `INACTIVE` rather than
dropping it, so a later `DescribeContainerInstances` still answers for it, as on AWS. A cluster's
`registeredContainerInstancesCount` counts only the `ACTIVE` and `DRAINING` instances.

`ListContainerInstances` defaults to every instance other than the `INACTIVE` ones, validates
`status` against the five documented values, and pages with `maxResults` (1 to 100) and
`nextToken`. The cluster query language `filter` is accepted and ignored.

`UpdateContainerInstancesState` sets only `ACTIVE` or `DRAINING`, takes at most 10 instances per
call, and refuses to drain an instance that is not `ACTIVE` first. It and
`DescribeContainerInstances` report an instance the cluster does not have as a `MISSING` failure
rather than dropping it.

`DescribeContainerInstances` withholds two members until the request asks for them, and rejects an
`include` value that is neither: `TAGS` for the instance's tags, and `CONTAINER_INSTANCE_HEALTH`
for its `healthStatus`. Floci runs no agent health checks of its own, so the one check it reports
is `AGENT_CONNECTIVITY`, taken from the registration state the instance already tracks, and
`overallStatus` follows it.

### Capacity Providers

| Operation | Description |
|---|---|
| `CreateCapacityProvider` | Create a custom capacity provider |
| `UpdateCapacityProvider` | Update a capacity provider |
| `DeleteCapacityProvider` | Delete a capacity provider |
| `DescribeCapacityProviders` | Describe capacity providers (includes FARGATE built-ins) |

A capacity provider name is validated the way AWS documents it: up to 255 letters, numbers,
underscores and hyphens, and never prefixed with `aws`, `ecs` or `fargate`. A created provider
reports its `capacityProviderArn`, a `type` of `EC2_AUTOSCALING`, and the
`autoScalingGroupProvider` it was created with, so a client that wrote one reads back what it
registered instead of seeing drift.

`DescribeCapacityProviders` returns tags only for `include: ["TAGS"]`, and reports a name that
resolves to nothing as a `MISSING` entry in `failures` rather than dropping it. The built-in
`FARGATE` and `FARGATE_SPOT` providers are described with their own ARNs and a `type` matching
their name.

`DeleteCapacityProvider` refuses the reserved `FARGATE` and `FARGATE_SPOT` providers, one that is
still attached to a cluster (detach it with `PutClusterCapacityProviders`, or delete the cluster,
first), and one still named in an active service's capacity provider strategy, which AWS requires
to be removed with `UpdateService` before the provider goes. A deleted provider is reported with
`updateStatus: DELETE_IN_PROGRESS` and an unchanged `status`, because `DELETE_IN_PROGRESS` is not
one of the four values `CapacityProviderStatus` takes.

### Service Deployments & Revisions

| Operation | Description |
|---|---|
| `DescribeServiceDeployments` | Describe service deployments |
| `ListServiceDeployments` | List service deployment ARNs |
| `DescribeServiceRevisions` | Describe service revisions |

A deployment is recorded on `CreateService` and on every `UpdateService` that rolls one, together
with the service revision it targets. The two are linked the way AWS links them: a deployment
reports `targetServiceRevision` (its ARN and the task counts) and `sourceServiceRevisions`, and
the service itself reports `currentServiceDeployment` and `currentServiceRevisions`, so a caller
gets from `DescribeServices` to the deployment without listing first. A service deployment carries
no `taskDefinition` member, because AWS's `ServiceDeployment` shape has none: the revision names
it.

A service revision is the snapshot of the service's configuration at that moment, copied rather
than referenced, so it keeps reporting what the service looked like then: `taskDefinition`, the
`launchType` or `capacityProviderStrategy`, `platformVersion`, `platformFamily`,
`networkConfiguration`, `loadBalancers`, `serviceRegistries`, `serviceConnectConfiguration` and
one `containerImages` entry per container definition. `guardDutyEnabled` is always `false`: Floci
runs no runtime monitoring. Floci applies a change in place rather than rolling it, so a
deployment is `SUCCESSFUL` with the same `startedAt` and `finishedAt` from the moment it is
recorded. Both describes report an ARN that resolves to nothing as a `MISSING` failure.

### Tags

| Operation | Description |
|---|---|
| `TagResource` | Add tags to a cluster, service, task, or task definition |
| `UntagResource` | Remove tags from a resource |
| `ListTagsForResource` | List tags on a resource |

Every operation that takes `tags` holds it to the limits ECS documents: at most 50 tags, a key of
1 to 128 characters and a value of up to 256. The count is taken from the request rather than the
parsed map, so 51 entries that collapse to fewer distinct keys are still rejected.

### Account Settings & Attributes

| Operation | Description |
|---|---|
| `PutAccountSetting` | Set an account-level setting for the calling user |
| `PutAccountSettingDefault` | Set the default account-level setting |
| `DeleteAccountSetting` | Delete an account setting |
| `ListAccountSettings` | List account settings |
| `PutAttributes` | Set custom key-value attributes on resources |
| `DeleteAttributes` | Remove attributes from resources |
| `ListAttributes` | List resources with a given attribute |

An account setting name must be one of the eleven ECS accepts, and its value is checked against
what that name allows: `0`, `7` or `14` for `fargateTaskRetirementWaitPeriod`, `blocking` or
`non-blocking` for `defaultLogDriverMode`, `enhanced` on top of the usual flags for
`containerInsights`, and `enabled`/`disabled`/`on`/`off` for the rest. A `Setting` reports its
`principalArn` and its `type`, which is `user` except for `guardDutyActivate`, the setting
GuardDuty owns on the account's behalf.

A request that names no `principalArn` acts for the account root, since Floci does not
authenticate a separate user, and `PutAccountSettingDefault` writes that same root row: AWS
describes the effective value as "the account settings for the root user or the default setting".
`ListAccountSettings` returns only the settings the principal explicitly set, which is why an
untouched account lists nothing; with `effectiveSettings: true` it returns all eleven at the value
that principal actually reads, falling back from its own setting to the root default to the
setting's built-in default. It pages with `maxResults` (1 to 10) and `nextToken`, ten at a time.

A setting is persisted under `<principalArn>::<name>` rather than the name alone, so settings
written by an earlier Floci version are ignored after upgrading: delete
`ecs-account-settings.json` from the persistence directory, or write the settings again.

Attributes belong to a cluster, not to a target id alone, so the same container instance id in two
clusters is two targets. An attribute must name a container instance the cluster has, or the call
is a `TargetNotFoundException`, and a target holds at most 10 custom attributes
(`AttributeLimitExceededException` past that, and for a single call carrying more than 10).
`ListAttributes` requires `targetType`, whose only valid value is `container-instance`, and pages
with `maxResults` (1 to 100) and `nextToken`.

### Agent / State Change Stubs

| Operation | Description |
|---|---|
| `SubmitTaskStateChange` | Agent callback stub |
| `SubmitContainerStateChange` | Agent callback stub |
| `SubmitAttachmentStateChanges` | Agent callback stub |
| `DiscoverPollEndpoint` | Returns the agent polling endpoint |

## Configuration

Every `awsvpc` task receives an ENI in its subnet. With `FLOCI_NETWORK_SECURITY_GROUP_ENFORCEMENT_ENABLED=true`, a Docker-backed task's containers additionally share one protected network namespace built around that ENI, and packets are filtered against its security groups. A task without explicit security groups uses its subnet VPC's default group. Containers in the same task can communicate over localhost. Bridge and host task networking do not attach task-level `awsvpc` security groups. Mock mode reports control-plane state, including the ENI, and does not enforce packet filtering.

| Variable | Default | Description |
|---|---|---|
| `FLOCI_SERVICES_ECS_ENABLED` | `true` | Enable or disable the ECS service |
| `FLOCI_SERVICES_ECS_MOCK` | `false` | Skip Docker; tasks go straight to `RUNNING` (useful for CI) |
| `FLOCI_SERVICES_ECS_DOCKER_NETWORK` | *(unset)* | Docker network for task containers |
| `FLOCI_SERVICES_ECS_DEFAULT_MEMORY_MB` | `512` | Default memory (MB) when the task definition omits it |
| `FLOCI_SERVICES_ECS_DEFAULT_CPU_UNITS` | `256` | Default CPU units when the task definition omits it |
| `FLOCI_SERVICES_ECS_HOST_VOLUME_ROOTS` | *(unset)* | Approved parent directories for host volume bind mounts (`volumes[].host.sourcePath`) |
| `FLOCI_SERVICES_ECS_ALLOW_UNSAFE_HOST_VOLUMES` | `false` | Allow any host path, bypassing the `HOST_VOLUME_ROOTS` allowlist; traversal, the bare root, and the Docker socket are still always rejected |

### Host volume safety

A task definition's `volumes[].host.sourcePath` is a caller-controlled filesystem path that Floci bind-mounts straight into the launched container, so both `RegisterTaskDefinition` and the actual bind mount at `RunTask` time validate it (the second check narrows the window between validation and mount, and also covers task definitions registered before this policy existed).

Always rejected, regardless of configuration:

- Relative paths, and any path containing a `..` segment.
- The bare filesystem root (`/`).
- The Docker daemon socket and any directory that contains it (e.g. `/var/run`, `/run`, `/var`), including via a symlink that resolves onto one of these paths. The protected socket is the one Floci's own Docker client connects to, resolved the same way the client resolves it: `floci.docker.docker-host`, then `DOCKER_HOST`, then the active Docker context (Colima, OrbStack, Rancher Desktop, Podman), then `/var/run/docker.sock`. The conventional locations `/var/run/docker.sock` and `/run/docker.sock` are always protected as well.

**By default, with no configuration, every host `sourcePath` is rejected.** You must explicitly opt in with one of:

- `FLOCI_SERVICES_ECS_HOST_VOLUME_ROOTS`: a comma-separated allowlist of approved parent directories. A `sourcePath` must resolve (symlinks included) under one of them:

  ```yaml
  services:
    floci:
      image: floci/floci:latest
      environment:
        FLOCI_SERVICES_ECS_HOST_VOLUME_ROOTS: /srv/floci/volumes,/data
  ```

- `FLOCI_SERVICES_ECS_ALLOW_UNSAFE_HOST_VOLUMES=true`: allow any host path (for local development where any host path should be mountable). The traversal, bare-root, and Docker socket blocks above are never bypassed by this flag.

A rejected `sourcePath` fails `RegisterTaskDefinition` with `InvalidParameterException`; a rejection caught again at `RunTask` time (e.g. a task definition registered before this policy existed) stops the task with that message as its `stoppedReason`. Named Docker volumes, EFS volumes, and host volumes with no `sourcePath` (ephemeral, container-local storage) are unaffected by these checks.

### EFS volume ownership

A task's `efsVolumeConfiguration` volumes are backed by shared local Docker volumes (Floci cannot mount a real EFS file system). A Docker named volume is created `root:root 0755`, so a task whose image runs as a non-root `USER` cannot write it. To emulate an [EFS access point](https://docs.aws.amazon.com/efs/latest/ug/efs-access-points.html)'s `RootDirectory.CreationInfo` and `PosixUser`, configure `floci.storage.efs.*` (all opt-in; the default is a plain named volume, so existing behaviour is unchanged):

| Key (`floci.storage.efs.`) | Env | AWS equivalent | Description |
|---|---|---|---|
| `owner-uid` | `FLOCI_STORAGE_EFS_OWNER_UID` | `CreationInfo.OwnerUid` | Owner uid of the volume root (set together with `owner-gid`) |
| `owner-gid` | `FLOCI_STORAGE_EFS_OWNER_GID` | `CreationInfo.OwnerGid` | Owner gid of the volume root (set together with `owner-uid`) |
| `root-permissions` | `FLOCI_STORAGE_EFS_ROOT_PERMISSIONS` | `CreationInfo.Permissions` | 3-4 octal digits, e.g. `0777`, or `2775` for the setgid bit |
| `mount-user` | `FLOCI_STORAGE_EFS_MOUNT_USER` | `PosixUser {Uid,Gid}` | Run mounting containers as `uid[:gid]` |
| `mount-group-add` | `FLOCI_STORAGE_EFS_MOUNT_GROUP_ADD` | `PosixUser` supplementary | Supplementary gid added to mounting containers |
| `init-image` | `FLOCI_STORAGE_EFS_INIT_IMAGE` | — | Image for the one-off `chown`/`chmod` of the volume root (default `busybox:stable`) |

`owner-uid` and `owner-gid` must be set together (a partial `CreationInfo` is not valid on AWS). The volume root is initialised once per volume; express the setgid bit through a 4-digit `root-permissions` (e.g. `2775`) so subdirectories inherit the owner gid.

### Mock mode

Set `FLOCI_SERVICES_ECS_MOCK=true` to run without Docker. In this mode tasks skip container launch and immediately transition to `RUNNING`, then to `STOPPED` when stopped. The task still reports a container per container definition and, for `awsvpc`, a real ENI, so a client reading `containers[]` or waiting on the task's address behaves as it does against AWS; nothing is running behind those containers, so ECS Exec answers `TargetNotConnectedException` and no logs are streamed. This is the recommended mode for unit/integration tests and CI pipelines where Docker-in-Docker is unavailable.

```yaml
# docker-compose.yml — CI / test environment
services:
  floci:
    image: floci/floci:latest
    environment:
      FLOCI_SERVICES_ECS_MOCK: "true"
```

```yaml
# docker-compose.yml — local development (real containers)
services:
  floci:
    image: floci/floci:latest
    volumes:
      - /var/run/docker.sock:/var/run/docker.sock
    environment:
      FLOCI_SERVICES_ECS_MOCK: "false"
      FLOCI_SERVICES_ECS_DOCKER_NETWORK: my_network
```

### Docker socket requirement

When `mock: false` (the default), ECS launches real Docker containers and requires the Docker socket. Mount it and set the network so containers can reach each other. For private registry authentication and other Docker settings see [Docker Configuration](../configuration/docker.md).

```yaml
services:
  floci:
    image: floci/floci:latest
    volumes:
      - /var/run/docker.sock:/var/run/docker.sock
    environment:
      FLOCI_SERVICES_ECS_DOCKER_NETWORK: aws-local_default
```

### Host access to awsvpc task ports

By default, Floci preserves the isolation expected from `awsvpc`: native runs use a dynamic Docker host port, while Floci-in-Docker exposes the container port only on the configured Docker network. A process running directly on the Docker host therefore has no stable port for an `awsvpc` task.

Set `FLOCI_SERVICES_ECS_PUBLISH_AWSVPC_PORTS_TO_HOST=true` to opt into stable host publishing. Floci binds each `containerPort` to the same host port, or uses an explicit non-zero `hostPort` when one is present. A host-side Terraform provider can then connect to `localhost:<port>`.

```yaml
services:
  floci:
    image: floci/floci:latest
    volumes:
      - /var/run/docker.sock:/var/run/docker.sock
    environment:
      FLOCI_SERVICES_ECS_PUBLISH_AWSVPC_PORTS_TO_HOST: "true"
```

This setting is an emulator-specific networking convenience and defaults to `false`. Docker cannot bind the same host port twice, so use it only when at most one running task publishes each port.

## Examples

```bash
export AWS_ENDPOINT_URL=http://localhost:4566
export AWS_DEFAULT_REGION=us-east-1
export AWS_ACCESS_KEY_ID=test
export AWS_SECRET_ACCESS_KEY=test

# Create a cluster
aws ecs create-cluster --cluster-name my-cluster \
  --endpoint-url $AWS_ENDPOINT_URL

# Register a task definition
aws ecs register-task-definition \
  --family my-task \
  --container-definitions '[
    {
      "name": "app",
      "image": "nginx:latest",
      "cpu": 256,
      "memory": 512,
      "essential": true,
      "portMappings": [{"containerPort": 80, "protocol": "tcp"}]
    }
  ]' \
  --requires-compatibilities FARGATE \
  --cpu 256 --memory 512 \
  --network-mode awsvpc \
  --endpoint-url $AWS_ENDPOINT_URL

# Run a task
aws ecs run-task \
  --cluster my-cluster \
  --task-definition my-task \
  --launch-type FARGATE \
  --endpoint-url $AWS_ENDPOINT_URL

# Create a service
aws ecs create-service \
  --cluster my-cluster \
  --service-name my-service \
  --task-definition my-task \
  --desired-count 1 \
  --launch-type FARGATE \
  --endpoint-url $AWS_ENDPOINT_URL

# List running tasks
aws ecs list-tasks --cluster my-cluster \
  --endpoint-url $AWS_ENDPOINT_URL

# Stop a task
aws ecs stop-task \
  --cluster my-cluster \
  --task <task-arn> \
  --endpoint-url $AWS_ENDPOINT_URL

# Delete a service
aws ecs delete-service \
  --cluster my-cluster \
  --service my-service \
  --force \
  --endpoint-url $AWS_ENDPOINT_URL
```

## Java SDK Example

```java
EcsClient ecs = EcsClient.builder()
    .endpointOverride(URI.create("http://localhost:4566"))
    .region(Region.US_EAST_1)
    .credentialsProvider(StaticCredentialsProvider.create(
        AwsBasicCredentials.create("test", "test")))
    .build();

// Create cluster
ecs.createCluster(r -> r.clusterName("my-cluster"));

// Register task definition
ecs.registerTaskDefinition(r -> r
    .family("my-task")
    .containerDefinitions(c -> c
        .name("app")
        .image("nginx:latest")
        .cpu(256)
        .memory(512)
        .essential(true))
    .requiresCompatibilities(Compatibility.FARGATE)
    .cpu("256")
    .memory("512")
    .networkMode(NetworkMode.AWSVPC));

// Run a task
RunTaskResponse response = ecs.runTask(r -> r
    .cluster("my-cluster")
    .taskDefinition("my-task")
    .launchType(LaunchType.FARGATE)
    .count(1));

String taskArn = response.tasks().get(0).taskArn();
```
