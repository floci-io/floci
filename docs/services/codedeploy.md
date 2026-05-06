# CodeDeploy

Floci implements the CodeDeploy API — stored-state management for applications, deployment groups, and configs, plus real Lambda and ECS deployment execution with traffic shifting and lifecycle hooks.

**Protocol:** JSON 1.1 — `POST /` with `X-Amz-Target: CodeDeploy_20141006.<Action>`

**ARN formats:**

- `arn:aws:codedeploy:<region>:<account>:application:<name>`
- `arn:aws:codedeploy:<region>:<account>:deploymentgroup:<app>/<group>`
- `arn:aws:codedeploy:<region>:<account>:deploymentconfig:<name>`
- `arn:aws:codedeploy:<region>:<account>:deployment:<id>`

## Supported Actions

<!-- floci:actions:start -->
| Action | Description |
| --- | --- |
| `CreateApplication` | Supports `computePlatform`: `Server`, `Lambda`, `ECS` |
| `GetApplication` | Returns application metadata |
| `UpdateApplication` | Renames an application |
| `DeleteApplication` | Removes application and all its deployment groups |
| `ListApplications` | Returns all application names |
| `BatchGetApplications` | Returns info for multiple applications |
| `CreateDeploymentGroup` | Stores group config; supports `ecsServices` and `loadBalancerInfo` for ECS blue/green; deployment config defaults to `CodeDeployDefault.OneAtATime` |
| `GetDeploymentGroup` | Returns group metadata |
| `UpdateDeploymentGroup` | Partial update; supports rename via `newDeploymentGroupName` |
| `DeleteDeploymentGroup` | Returns `hooksNotCleanedUp: []` |
| `ListDeploymentGroups` | Returns all group names for an application |
| `BatchGetDeploymentGroups` | Returns info for multiple groups |
| `CreateDeploymentConfig` | Creates a custom config; names starting with `CodeDeployDefault.` are rejected |
| `GetDeploymentConfig` | Returns config including built-ins |
| `DeleteDeploymentConfig` | Custom configs only; built-ins cannot be deleted |
| `ListDeploymentConfigs` | Returns all configs including all 17 pre-seeded built-ins |
| `TagResource` | Tags any resource by ARN |
| `UntagResource` | Removes specific tag keys |
| `ListTagsForResource` | Returns tags for a resource ARN |
| `CreateDeployment` | Starts a real Lambda or ECS blue/green deployment; shifts traffic via alias weights (Lambda) or ELB listener rules (ECS); invokes lifecycle hooks |
| `GetDeployment` | Returns current deployment state; poll `status` until `Succeeded`, `Failed`, or `Stopped` |
| `ListDeployments` | Returns deployment IDs filtered by application, group, or status |
| `StopDeployment` | Signals an in-progress deployment to stop; transitions to `Stopped` |
| `ContinueDeployment` | Accepted (no-op for fully automated deployments) |
| `BatchGetDeployments` | Returns info for multiple deployments |
| `ListDeploymentTargets` | Returns target IDs for a deployment |
| `BatchGetDeploymentTargets` | Returns target details including lifecycle event status |
| `PutLifecycleEventHookExecutionStatus` | Called by lifecycle hook Lambda to report `Succeeded` or `Failed`; failure triggers auto-rollback |
| `RegisterOnPremisesInstance` |  |
| `DeregisterOnPremisesInstance` |  |
| `GetOnPremisesInstance` |  |
| `BatchGetOnPremisesInstances` |  |
| `ListOnPremisesInstances` |  |
| `AddTagsToOnPremisesInstances` |  |
| `RemoveTagsFromOnPremisesInstances` |  |
<!-- floci:actions:end -->
## Pre-seeded Built-in Deployment Configs

The following 17 configurations are always available (matching real AWS):

**Server:**
- `CodeDeployDefault.OneAtATime`
- `CodeDeployDefault.HalfAtATime`
- `CodeDeployDefault.AllAtOnce`

**Lambda:**
- `CodeDeployDefault.LambdaAllAtOnce`
- `CodeDeployDefault.LambdaCanary10Percent5Minutes`
- `CodeDeployDefault.LambdaCanary10Percent10Minutes`
- `CodeDeployDefault.LambdaCanary10Percent15Minutes`
- `CodeDeployDefault.LambdaCanary10Percent30Minutes`
- `CodeDeployDefault.LambdaLinear10PercentEvery1Minute`
- `CodeDeployDefault.LambdaLinear10PercentEvery2Minutes`
- `CodeDeployDefault.LambdaLinear10PercentEvery3Minutes`
- `CodeDeployDefault.LambdaLinear10PercentEvery10Minutes`

**ECS:**
- `CodeDeployDefault.ECSAllAtOnce`
- `CodeDeployDefault.ECSCanary10Percent5Minutes`
- `CodeDeployDefault.ECSCanary10Percent15Minutes`
- `CodeDeployDefault.ECSLinear10PercentEvery1Minutes`
- `CodeDeployDefault.ECSLinear10PercentEvery3Minutes`

## ECS Deployment Model (Blue/Green)

For `computePlatform: ECS`, `CreateDeployment` performs a full blue/green traffic shift against a real ECS service and ELB v2 listener:

1. Parses the AppSpec (JSON, `revisionType: AppSpecContent`) to extract the target task definition, container name, and port
2. Creates a **green task set** on the ECS service (via `CreateTaskSet`) pointing to the new task definition
3. Runs lifecycle hook Lambdas in order: `BeforeInstall` → (install) → `AfterInstall` → `BeforeAllowTraffic` → (traffic shift) → `AfterAllowTraffic`
4. **Traffic shift** — atomically updates the ELB v2 listener's default forward rule:
   - `ECSAllAtOnce`: immediately shifts 100% of traffic to the green target group
   - `ECSCanary*`: shifts the canary percentage first, waits a short interval (capped at 5 s in emulator), then shifts to 100%
   - `ECSLinear*`: shifts traffic in equal increments (capped at 2 s per step in emulator)
5. Promotes the green task set to **PRIMARY** on the ECS service and deletes the original blue task set
6. Marks the deployment `Succeeded`; if any lifecycle hook reports `Failed`, the deployment is marked `Failed`

**Compute platform resolution**: `computePlatform` is set on the Application at creation time. The deployment group inherits it — you do not pass `computePlatform` to `CreateDeploymentGroup`.

### ECS AppSpec Format

```json
{
  "version": 0.0,
  "Resources": [{
    "TargetService": {
      "Type": "AWS::ECS::Service",
      "Properties": {
        "TaskDefinition": "my-task:2",
        "LoadBalancerInfo": {
          "ContainerName": "app",
          "ContainerPort": 80
        }
      }
    }
  }],
  "Hooks": [
    { "BeforeInstall": "my-before-install-hook" },
    { "AfterInstall": "my-after-install-hook" },
    { "BeforeAllowTraffic": "my-before-traffic-hook" },
    { "AfterAllowTraffic": "my-after-traffic-hook" }
  ]
}
```

All hook fields are optional.

### ECS Deployment Group Configuration

```json
{
  "applicationName": "my-ecs-app",
  "deploymentGroupName": "my-ecs-group",
  "deploymentConfigName": "CodeDeployDefault.ECSAllAtOnce",
  "serviceRoleArn": "arn:aws:iam::000000000000:role/codedeploy-role",
  "deploymentStyle": {
    "deploymentType": "BLUE_GREEN",
    "deploymentOption": "WITH_TRAFFIC_CONTROL"
  },
  "ecsServices": [{
    "clusterName": "my-cluster",
    "serviceName": "my-service"
  }],
  "loadBalancerInfo": {
    "targetGroupPairInfoList": [{
      "targetGroups": [
        { "name": "my-blue-tg" },
        { "name": "my-green-tg" }
      ],
      "prodTrafficRoute": {
        "listenerArns": ["arn:aws:elasticloadbalancing:..."]
      }
    }]
  }
}
```

The ECS service must be created with `deploymentController.type: EXTERNAL`.

## Lambda Deployment Model

For `computePlatform: Lambda`, `CreateDeployment` performs real traffic shifting:

1. Reads the deployment group's `deploymentStyle` and `deploymentConfigName` to determine the traffic shift strategy
2. For **canary** and **linear** strategies: updates the Lambda alias `RoutingConfig` to route a percentage to the new function version, waits the configured interval, then shifts to 100%
3. For **all-at-once**: shifts directly to 100% of the new version
4. Invokes `BeforeAllowTraffic` lifecycle hook Lambda (if configured) and waits for `PutLifecycleEventHookExecutionStatus` callback
5. Invokes `AfterAllowTraffic` lifecycle hook Lambda (if configured) and waits for the callback
6. If any lifecycle hook reports `Failed`, auto-rolls back the alias to the previous version and marks the deployment `Failed`

## Configuration

```yaml
floci:
  services:
    codedeploy:
      enabled: true   # default
```

## CLI Examples

### Lambda

```bash
# Create a Lambda application
aws --endpoint-url http://localhost:4566 deploy create-application \
  --application-name my-app \
  --compute-platform Lambda

# Create a deployment group for Lambda
aws --endpoint-url http://localhost:4566 deploy create-deployment-group \
  --application-name my-app \
  --deployment-group-name my-group \
  --deployment-config-name CodeDeployDefault.LambdaCanary10Percent5Minutes \
  --service-role-arn arn:aws:iam::000000000000:role/codedeploy-role \
  --deployment-style deploymentType=BLUE_GREEN,deploymentOption=WITH_TRAFFIC_CONTROL

# Start a Lambda deployment
aws --endpoint-url http://localhost:4566 deploy create-deployment \
  --application-name my-app \
  --deployment-group-name my-group \
  --revision 'revisionType=AppSpecContent,appSpecContent={content="{\"version\":0.0,\"Resources\":[{\"myFunction\":{\"Type\":\"AWS::Lambda::Function\",\"Properties\":{\"Name\":\"my-function\",\"Alias\":\"live\",\"CurrentVersion\":\"1\",\"TargetVersion\":\"2\"}}}]}"}'
```

### ECS Blue/Green

```bash
# Create an ECS application
aws --endpoint-url http://localhost:4566 deploy create-application \
  --application-name my-ecs-app \
  --compute-platform ECS

# Create a deployment group (listener ARN from ELB v2)
aws --endpoint-url http://localhost:4566 deploy create-deployment-group \
  --application-name my-ecs-app \
  --deployment-group-name my-ecs-group \
  --deployment-config-name CodeDeployDefault.ECSAllAtOnce \
  --service-role-arn arn:aws:iam::000000000000:role/codedeploy-role \
  --ecs-services clusterName=my-cluster,serviceName=my-service \
  --load-balancer-info 'targetGroupPairInfoList=[{targetGroups=[{name=blue-tg},{name=green-tg}],prodTrafficRoute={listenerArns=[<listener-arn>]}}]'

# Start an ECS blue/green deployment
aws --endpoint-url http://localhost:4566 deploy create-deployment \
  --application-name my-ecs-app \
  --deployment-group-name my-ecs-group \
  --revision 'revisionType=AppSpecContent,appSpecContent={content="{\"version\":0.0,\"Resources\":[{\"TargetService\":{\"Type\":\"AWS::ECS::Service\",\"Properties\":{\"TaskDefinition\":\"my-task:2\",\"LoadBalancerInfo\":{\"ContainerName\":\"app\",\"ContainerPort\":80}}}}]}"}'

# Poll deployment status
aws --endpoint-url http://localhost:4566 deploy get-deployment --deployment-id <id>

# List deployment targets
aws --endpoint-url http://localhost:4566 deploy list-deployment-targets --deployment-id <id>

# Get target details
aws --endpoint-url http://localhost:4566 deploy batch-get-deployment-targets \
  --deployment-id <id> \
  --target-ids <target-id>

# List built-in deployment configs
aws --endpoint-url http://localhost:4566 deploy list-deployment-configs
```
