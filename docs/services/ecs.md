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
| `DescribeServices` | Describe one or more services |
| `ListServices` | List service ARNs in a cluster |
| `ListServicesByNamespace` | List services filtered by Cloud Map namespace |

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

```yaml
floci:
  services:
    ecs:
      enabled: true
      mock: false           # Set true to skip Docker and run tasks as in-process stubs
      docker-network: ""    # Docker network for task containers
      default-memory-mb: 512
      default-cpu-units: 256
```

### Environment Variables

| Variable | Default | Description |
|---|---|---|
| `FLOCI_SERVICES_ECS_ENABLED` | `true` | Enable or disable the ECS service |
| `FLOCI_SERVICES_ECS_MOCK` | `false` | Skip Docker; tasks go straight to `RUNNING` (useful for CI) |
| `FLOCI_SERVICES_ECS_DOCKER_NETWORK` | *(unset)* | Docker network for task containers |
| `FLOCI_SERVICES_ECS_DEFAULT_MEMORY_MB` | `512` | Default memory (MB) when the task definition omits it |
| `FLOCI_SERVICES_ECS_DEFAULT_CPU_UNITS` | `256` | Default CPU units when the task definition omits it |

### Mock mode

Set `FLOCI_SERVICES_ECS_MOCK=true` to run without Docker. In this mode tasks skip container launch and immediately transition to `RUNNING`, then to `STOPPED` when stopped. This is the recommended mode for unit/integration tests and CI pipelines where Docker-in-Docker is unavailable.

```yaml
# docker-compose.yml — CI / test environment
services:
  floci:
    image: hectorvent/floci:latest
    environment:
      FLOCI_SERVICES_ECS_MOCK: "true"
```

```yaml
# docker-compose.yml — local development (real containers)
services:
  floci:
    image: hectorvent/floci:latest
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
    image: hectorvent/floci:latest
    volumes:
      - /var/run/docker.sock:/var/run/docker.sock
    environment:
      FLOCI_SERVICES_ECS_DOCKER_NETWORK: aws-local_default
```

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
