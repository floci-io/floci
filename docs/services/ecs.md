# ECS (Elastic Container Service)

**Protocol:** JSON 1.1
**Endpoint:** `POST /` + `X-Amz-Target: AmazonEC2ContainerServiceV20141113.<Action>`

ECS emulates clusters, task definitions, tasks, and services. In the default configuration tasks run as real Docker containers. Set `mock: true` (enabled automatically in tests) to run tasks as in-process stubs without Docker.

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

### Task Definitions

| Operation | Description |
|---|---|
| `RegisterTaskDefinition` | Register a new revision of a task definition |
| `DescribeTaskDefinition` | Describe a task definition by family:revision or ARN |
| `ListTaskDefinitions` | List task definition ARNs |
| `ListTaskDefinitionFamilies` | List task definition family names |
| `DeregisterTaskDefinition` | Mark a revision INACTIVE |
| `DeleteTaskDefinitions` | Delete one or more task definitions |

`runtimePlatform` and a container's `logConfiguration` are stored and returned exactly as
registered, so a client that reads back what it wrote (Terraform, or a deploy tool verifying its
own `RegisterTaskDefinition`) sees no drift. `runtimePlatform` does not change where a local task
runs: Floci launches every task on the host's own architecture.

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
registration is accepted here too; it fetches nothing locally either, because Floci serves no ECS
task metadata endpoint, which the init process reads before downloading.
Floci also does not validate a task definition's `compatibilities` /
`requiresCompatibilities` against `RunTask` `launchType`; a Fargate-compatible
definition can still be run with `launchType=EC2` (and the reverse) the same
way a missing metadata endpoint is accepted at registration.
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

### Services

| Operation | Description |
|---|---|
| `CreateService` | Create a long-running service |
| `UpdateService` | Update desired count, task definition, or deployment config |
| `DeleteService` | Delete a service (supports `force`) |
| `DescribeServices` | Describe one or more services (includes `deployments`, see below) |
| `ListServices` | List service ARNs in a cluster |
| `ListServicesByNamespace` | List services filtered by Cloud Map namespace |

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

### Container Instances

| Operation | Description |
|---|---|
| `RegisterContainerInstance` | Register a container instance with a cluster |
| `DeregisterContainerInstance` | Deregister a container instance |
| `DescribeContainerInstances` | Describe container instances |
| `ListContainerInstances` | List container instance ARNs |
| `UpdateContainerAgent` | Trigger agent update (stub) |
| `UpdateContainerInstancesState` | Drain or activate container instances |

### Capacity Providers

| Operation | Description |
|---|---|
| `CreateCapacityProvider` | Create a custom capacity provider |
| `UpdateCapacityProvider` | Update a capacity provider |
| `DeleteCapacityProvider` | Delete a capacity provider |
| `DescribeCapacityProviders` | Describe capacity providers (includes FARGATE built-ins) |

### Service Deployments & Revisions

| Operation | Description |
|---|---|
| `DescribeServiceDeployments` | Describe service deployments |
| `ListServiceDeployments` | List service deployment ARNs |
| `DescribeServiceRevisions` | Describe service revisions |

### Tags

| Operation | Description |
|---|---|
| `TagResource` | Add tags to a cluster, service, task, or task definition |
| `UntagResource` | Remove tags from a resource |
| `ListTagsForResource` | List tags on a resource |

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

### Agent / State Change Stubs

| Operation | Description |
|---|---|
| `SubmitTaskStateChange` | Agent callback stub |
| `SubmitContainerStateChange` | Agent callback stub |
| `SubmitAttachmentStateChanges` | Agent callback stub |
| `DiscoverPollEndpoint` | Returns the agent polling endpoint |

## Configuration

Docker-backed `awsvpc` tasks receive an emulated ENI and share one protected network namespace across their containers when `FLOCI_NETWORK_SECURITY_GROUP_ENFORCEMENT_ENABLED=true`. A task without explicit security groups uses its subnet VPC's default group. Containers in the same task can communicate over localhost. Bridge and host task networking do not attach task-level `awsvpc` security groups. Mock mode reports control-plane state and does not enforce packet filtering.

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

Set `FLOCI_SERVICES_ECS_MOCK=true` to run without Docker. In this mode tasks skip container launch and immediately transition to `RUNNING`, then to `STOPPED` when stopped. This is the recommended mode for unit/integration tests and CI pipelines where Docker-in-Docker is unavailable.

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
