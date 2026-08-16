# Step Functions

**Protocol:** AWS JSON 1.0 (`X-Amz-Target: AWSStepFunctions.*`)
**Endpoint:** `POST http://localhost:4566/`

## Supported Actions

<!-- floci:actions:start -->
| Action | Description |
| --- | --- |
| `CreateStateMachine` | Create a state machine (Standard or Express) |
| `UpdateStateMachine` | Update definition, role, logging, tracing, or encryption settings and optionally publish a version |
| `DescribeStateMachine` | Get state machine definition and metadata |
| `ListStateMachines` | List all state machines |
| `DeleteStateMachine` | Delete a state machine |
| `PublishStateMachineVersion` | - |
| `ListStateMachineVersions` | - |
| `DeleteStateMachineVersion` | - |
| `ValidateStateMachineDefinition` | Validate an ASL definition without creating a state machine |
| `StartExecution` | Start a new execution |
| `StartSyncExecution` | - |
| `DescribeExecution` | Get execution status and output |
| `ListExecutions` | List executions for a state machine |
| `StopExecution` | Stop a running execution |
| `GetExecutionHistory` | Get the full event history of an execution |
| `SendTaskSuccess` | Report task success (for `.waitForTaskToken` tasks) |
| `SendTaskFailure` | Report task failure |
| `SendTaskHeartbeat` | Send a heartbeat for long-running tasks |
| `CreateActivity` | - |
| `DeleteActivity` | - |
| `DescribeActivity` | - |
| `ListActivities` | - |
| `GetActivityTask` | - |
| `ListTagsForResource` | - |
| `TagResource` | - |
| `UntagResource` | - |
<!-- floci:actions:end -->

`UpdateStateMachine` returns the new revision ID and update timestamp. CloudFormation updates
definition, role, logging, tracing, encryption, and tags without replacing the state machine;
changes to `StateMachineName` or `StateMachineType` use replacement semantics.

## Map concurrency

Map states honor `MaxConcurrency` and, for JSONPath state machines, `MaxConcurrencyPath`.
JSONata state machines may supply `MaxConcurrency` as an expression. A value of `0`, or an
omitted value, uses the AWS service ceiling: 40 concurrent iterations for Inline Map states and
10,000 for Distributed Map states. `MaxConcurrency: 1` runs iterations sequentially.

Results remain in input order even when iterations finish out of order. If an iteration fails,
the Map state fails promptly, cancels its active sibling iterations, and does not start queued
iterations.

## Configuration

| Variable | Default | Description |
|---|---|---|
| `FLOCI_SERVICES_STEPFUNCTIONS_ENABLED` | `true` | Enable or disable the service |

## Examples

```bash
export AWS_ENDPOINT_URL=http://localhost:4566

# Create a state machine
SM_ARN=$(aws stepfunctions create-state-machine \
  --name my-workflow \
  --definition '{
    "Comment": "Simple workflow",
    "StartAt": "HelloWorld",
    "States": {
      "HelloWorld": {
        "Type": "Pass",
        "Result": {"message": "Hello, World!"},
        "End": true
      }
    }
  }' \
  --role-arn arn:aws:iam::000000000000:role/step-functions-role \
  --query stateMachineArn --output text \
  --endpoint-url $AWS_ENDPOINT_URL)

# Start an execution
EXEC_ARN=$(aws stepfunctions start-execution \
  --state-machine-arn $SM_ARN \
  --input '{"key":"value"}' \
  --query executionArn --output text \
  --endpoint-url $AWS_ENDPOINT_URL)

# Check status
aws stepfunctions describe-execution \
  --execution-arn $EXEC_ARN \
  --endpoint-url $AWS_ENDPOINT_URL

# Get event history
aws stepfunctions get-execution-history \
  --execution-arn $EXEC_ARN \
  --endpoint-url $AWS_ENDPOINT_URL
```
