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

## Retry policies

`Task`, `Parallel`, and `Map` states honor their `Retry` field. `ErrorEquals` matching
follows AWS semantics, including the `States.ALL` and `States.TaskFailed` wildcards.
`States.Runtime` is never retried. AWS defaults apply when fields are omitted
(`MaxAttempts` 3, `IntervalSeconds` 1, `BackoffRate` 2.0), `MaxDelaySeconds` is honored,
and each retrier keeps its own attempt counter. `Retry` is evaluated before `Catch`, and
`$$.State.RetryCount` increments per attempt. Attempt counts, defaults, and backoff
timing were verified against real AWS Step Functions.

`JitterStrategy` supports `NONE` (the default) and `FULL`. `FULL` draws the delay
uniformly between zero and the computed delay, as on AWS. One deviation. The delay
between attempts is capped at 30 seconds, the same cap Floci applies to `Wait` states,
so emulated runs stay fast.

## Mocked service integrations

Floci supports the Step Functions Local mock configuration format
(`MockConfigFile.json`). This lets a Task state return a predefined result or error
instead of calling the integrated service. It is the standard way to unit test `Catch`
and `Retry` branches, and it also lets you execute state machines whose integrations
Floci does not implement yet.

Point `SFN_MOCK_CONFIG` at the mock configuration file and start an execution against
`<stateMachineArn>#<testCaseName>`:

```bash
# docker run -e SFN_MOCK_CONFIG=/tmp/mock.json -v ./MockConfigFile.json:/tmp/mock.json ...
aws stepfunctions start-execution \
  --state-machine-arn "$SM_ARN#Throw422" \
  --input '{}' \
  --endpoint-url $AWS_ENDPOINT_URL
```

```json
{
  "StateMachines": {
    "Test": { "TestCases": { "Throw422": { "Call API": "ApiFailure" } } }
  },
  "MockedResponses": {
    "ApiFailure": {
      "0": { "Throw": { "Error": "ApiGateway.422", "Cause": "Unprocessable" } }
    },
    "ApiSuccess": {
      "0-1": { "Return": { "StatusCode": 200, "ResponseBody": { "id": 1 } } }
    }
  }
}
```

Each test case maps a state name to a `MockedResponses` entry. Each mocked response is
keyed by retry attempt (`"0"`, `"1"`, or a range like `"1-2"`), so a state can fail on
the first attempt and succeed on a retry. `Return` supplies the task result. `Throw`
fails the task with the given `Error` and `Cause`, which flow through `Retry` and
`Catch` unchanged. States not named in the test case run their real integration, so
mocked and real service calls can be combined in one execution. The file is re-read
when it changes, so it can be edited without restarting Floci.

Behavior was verified against Step Functions Local 2.0.0. As there, `StartSyncExecution`
rejects a test case suffix with `UnsupportedOperation` and does not strip a bare trailing
`#`, a bare trailing `#` on `StartExecution` runs the execution unmocked, and a retry
attempt with no mocked entry fails the execution with `States.Runtime`. One intentional deviation: Floci reports an unknown test case and any
invalid mock configuration (unparseable file, bad attempt key, missing `MockedResponses`
entry, `Return` and `Throw` together, `Throw` without `Error`) as a structured 400 error
at `StartExecution`. Step Functions Local instead returns a plain HTTP 500 for most of
these and starts the execution only to fail it with `States.Runtime` for the last two.

## Configuration

| Variable | Default | Description |
|---|---|---|
| `FLOCI_SERVICES_STEPFUNCTIONS_ENABLED` | `true` | Enable or disable the service |
| `SFN_MOCK_CONFIG` | unset | Path to a Step Functions Local compatible mock configuration file (alias: `FLOCI_SERVICES_STEPFUNCTIONS_MOCK_CONFIG_FILE`) |

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
