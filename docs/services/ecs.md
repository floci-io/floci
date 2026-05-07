# ECS (Elastic Container Service)

**Protocol:** JSON 1.1
**Endpoint:** `POST /` + `X-Amz-Target: AmazonEC2ContainerServiceV20141113.<Action>`

ECS emulates clusters, task definitions, tasks, and services. In the default configuration tasks run as real Docker containers. Set `mock: true` (enabled automatically in tests) to run tasks as in-process stubs without Docker.

## Supported Actions

<!-- floci:actions:start -->
| Action |
| --- |
| `CreateCluster` |
| `DescribeClusters` |
| `ListClusters` |
| `DeleteCluster` |
| `UpdateCluster` |
| `UpdateClusterSettings` |
| `PutClusterCapacityProviders` |
| `RegisterTaskDefinition` |
| `DescribeTaskDefinition` |
| `ListTaskDefinitions` |
| `ListTaskDefinitionFamilies` |
| `DeregisterTaskDefinition` |
| `DeleteTaskDefinitions` |
| `RunTask` |
| `StartTask` |
| `StopTask` |
| `DescribeTasks` |
| `ListTasks` |
| `UpdateTaskProtection` |
| `GetTaskProtection` |
| `CreateService` |
| `UpdateService` |
| `DeleteService` |
| `DescribeServices` |
| `ListServices` |
| `ListServicesByNamespace` |
| `TagResource` |
| `UntagResource` |
| `ListTagsForResource` |
| `PutAccountSetting` |
| `PutAccountSettingDefault` |
| `DeleteAccountSetting` |
| `ListAccountSettings` |
| `PutAttributes` |
| `DeleteAttributes` |
| `ListAttributes` |
| `RegisterContainerInstance` |
| `DeregisterContainerInstance` |
| `DescribeContainerInstances` |
| `ListContainerInstances` |
| `UpdateContainerAgent` |
| `UpdateContainerInstancesState` |
| `CreateCapacityProvider` |
| `UpdateCapacityProvider` |
| `DeleteCapacityProvider` |
| `DescribeCapacityProviders` |
| `CreateTaskSet` |
| `UpdateTaskSet` |
| `DeleteTaskSet` |
| `DescribeTaskSets` |
| `UpdateServicePrimaryTaskSet` |
| `DescribeServiceDeployments` |
| `ListServiceDeployments` |
| `DescribeServiceRevisions` |
| `SubmitTaskStateChange` |
| `SubmitContainerStateChange` |
| `SubmitAttachmentStateChanges` |
| `DiscoverPollEndpoint` |
<!-- floci:actions:end -->
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
