# Step Functions

**Protocol:** JSON 1.1 (`X-Amz-Target: AmazonStatesService.*`)
**Endpoint:** `POST http://localhost:4566/`

## Supported Actions

<!-- floci:actions:start -->
| Action |
| --- |
| `CreateStateMachine` |
| `DescribeStateMachine` |
| `ListStateMachines` |
| `DeleteStateMachine` |
| `StartExecution` |
| `StartSyncExecution` |
| `DescribeExecution` |
| `ListExecutions` |
| `StopExecution` |
| `GetExecutionHistory` |
| `SendTaskSuccess` |
| `SendTaskFailure` |
| `SendTaskHeartbeat` |
| `CreateActivity` |
| `DeleteActivity` |
| `DescribeActivity` |
| `ListActivities` |
| `GetActivityTask` |
<!-- floci:actions:end -->

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
