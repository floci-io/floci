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
| `DescribeMapRun` | Get the item and execution counters of a distributed Map run |
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

## Execution history events

`GetExecutionHistory` emits an event family around a Task state's `TaskStateEntered` and
`TaskStateExited` pair. Which family depends on the resource type.

- A service integration ARN (`arn:aws:states:::...`) emits `TaskScheduled`, `TaskStarted`,
  and then `TaskSucceeded` or `TaskFailed`. `taskScheduledEventDetails` carries
  `resourceType`, `resource`, `region`, and `parameters`. `parameters` is the resolved
  `Parameters` or `Arguments` payload, serialized as a JSON string. `timeoutInSeconds` and
  `heartbeatInSeconds` appear only when the state sets `TimeoutSeconds` or
  `HeartbeatSeconds` as a literal number.
- A direct Lambda function ARN emits `LambdaFunctionScheduled`, `LambdaFunctionStarted`,
  and then `LambdaFunctionSucceeded` or `LambdaFunctionFailed`. `LambdaFunctionScheduled`
  carries `resource` and `input`. `resource` holds the full function ARN. `LambdaFunctionStarted`
  carries no details at all. This matches AWS.
- An activity ARN emits the equivalent `Activity*` family.

A `Retry` re-entry emits its own Scheduled, Started, and Failed triple for each attempt. A
mocked Task (`SFN_MOCK_CONFIG`) emits the same events as a real one, because Step Functions
Local does the same.

Every event's `previousEventId` points to the id of the event right before it. The one
exception is the first state's `*StateEntered` event. Its `previousEventId` is `0`. That
matches `ExecutionStarted`, which is always `id: 1, previousEventId: 0`.

`inputDetails` appears on `ExecutionStarted`, on `stateEnteredEventDetails`, and on
`LambdaFunctionScheduled`/`ActivityScheduled`. `outputDetails` appears on
`stateExitedEventDetails`, on `executionSucceededEventDetails`, and on
`taskSucceededEventDetails`/`lambdaFunctionSucceededEventDetails`. Both are always
`{"truncated": false}`. Floci never truncates a payload, so the value never changes.
When the request sets `includeExecutionData` to false, the details objects stay but lose
`input`, `inputDetails`, `output`, and `outputDetails`. Every other field stays, including
`taskScheduledEventDetails.parameters`. This matches AWS.

A few gaps remain. `TaskStarted`, `LambdaFunctionStarted`, and `ActivityStarted` fire at
scheduling time, not when a worker actually picks up the task. Events inside a `Parallel` or
`Map` branch are not recorded in the parent execution's history. `TaskSubmitted`, which real
AWS emits for `.sync` and `.waitForTaskToken` integrations, is not emitted yet.

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

A state with no `Retry` array runs exactly once. This is what makes CDK-generated provider-framework
workflows converge: the `framework-isComplete-task` throws on every not-yet-complete poll and relies on
`Retry` to poll again until the custom resource reports done — see
[CloudFormation custom resources](cloudformation.md#custom-resources-and-the-cdk-provider-framework).

`JitterStrategy` supports `NONE` (the default) and `FULL`. `FULL` draws the delay
uniformly between zero and the computed delay, as on AWS. One deviation. The delay
between attempts is capped at 30 seconds, the same cap Floci applies to `Wait` states,
so emulated runs stay fast.

## JSONata nulls

An expression that evaluates to JSON `null` produces a value, not a missing one. It keeps its key
in `Output`, in `Assign` and in a Task's `Arguments`, at any nesting depth, and it stays in place as
an array element:

```
"Output": {"v": "{% $states.input.bar %}"}   on input {"bar": null}   ->   {"v": null}
```

The same `null` is a value inside the expression too: `$exists()` on it is true and `$type()` on it
is `"null"`.

One deviation. `$count()` on a JSON null answers `0`; AWS answers `1`.

## An expression that returns nothing fails the state

An expression that returns nothing, which is what `$states.input.absent` and the functions listed
below that evaluate to undefined do, is not a missing value the state carries on without. It fails
the state with `States.QueryEvaluationError`, naming the field it was written in, and a `Catch` on
that error fires:

```
"Output": {"v": "{% $states.input.absent %}"}
  ->  States.QueryEvaluationError
      The JSONata expression '$states.input.absent' specified for the field 'Output/v'
      returned nothing (undefined).
```

The field is named relative to the state, with `/` before each object key and `[i]` for each array
index: `Output`, `Output/a/b[0]`, `Assign/x`, `Arguments/MessageGroupId`, `Choices[1]/Condition`,
`Seconds`, `Error`, `Cause`, `Items`, `MaxConcurrency`. A matched `Choice` rule and a matching
`Catch` clause carry their own `Assign` and `Output`, which are named under the rule or clause:
`Choices[1]/Output/v`, `Choices[0]/Assign/x`, `Catch[1]/Output/v`. A `Choice` stops at the first rule
that matches, so an undefined condition in a later rule is never evaluated.

One deviation. AWS prefixes the cause of a real execution with
`An error occurred while executing the state '<name>' (entered at the event id #<n>).`; Floci
returns the cause without it, which is the form AWS's own `TestState` returns.

## JSONata functions

State machines with `"QueryLanguage": "JSONata"` reach the six functions Step Functions adds on
top of the JSONata language, alongside every function JSONata itself provides.

| Function | Returns |
| --- | --- |
| `$parse(jsonString)` | the deserialized value; the replacement for `$eval`, which AWS disables and so does Floci, answering `T1006` to a call |
| `$partition(array, chunkSize)` | `array` split into chunks of `chunkSize`, the last one holding the remainder |
| `$range(start, end, step)` | the values from `start` to `end`, inclusive when `step` lands on `end` |
| `$hash(str, algorithm)` | the hex digest of `str`; `algorithm` is `MD5`, `SHA-1`, `SHA-256`, `SHA-384` or `SHA-512`, case-sensitive |
| `$random(seed)` | a number in `[0, 1)`, reproducible under the optional integer `seed` |
| `$uuid()` | a v4 UUID |

Three behaviours are worth knowing before reading an unexpected result, and all three are AWS's:

- A non-integer argument is rounded **towards zero**, so `$range(-1.7, 2, 1)` starts at `-1` and
  `$partition(items, 2.9)` chunks by 2.
- Several arguments evaluate to undefined rather than failing: a chunk size of zero, an empty
  array, a `$range` with no `step` or with a step whose sign disagrees with the direction, and a
  `$hash` with no algorithm. The argument itself does not fail; the field the expression was
  written in does, under `States.QueryEvaluationError`, as the section above records.
- `$range` collapses a single-element range to the bare number, not a one-element array.

JSONata's own `$string` follows AWS's number notation: a whole number is written out in full below
`1e21` and in exponent notation from there, on both signs, so `$string(1e20)` is
`100000000000000000000` and `$string(1e21)` is `1e+21`.

JSONata's own `$formatNumber` checks its picture string against the fourteen rules of XPath F&O
4.7.3, as AWS does, so `$formatNumber(1, "x")` fails with `D3086` rather than answering `x1`: a
picture with no digit in it describes no number.

Evaluation is bounded on three axes, as it is on AWS, and past any of them the state fails with
`States.QueryEvaluationError`.

- **Depth**: one expression may nest 100 levels, which is AWS's own ceiling: AWS accepts `1+1+…+1`
  at 100 terms, 99 parentheses, 99 brackets, 99 `~>` stages, and a non-tail-recursive `$f(31)`,
  which nests `3n+5`, and refuses one level more of each. The refusal names the depth reached, as
  in `Stack overflow error: … Depth=101 max=100`.
- **Memory**: one value an expression builds may hold 6,990,256 bytes, counting a number as eight
  bytes and a character as one. That is AWS's own bound: AWS accepts `[1..873782]` and refuses one
  element more, and it accepts a string doubled 22 times, 2^22 characters, refusing the 23rd. The
  refusal is AWS's own
  `Expression evaluation memory limit exceeded`, which is what `[1..900000] ~> $count()` and
  `$sum([1..900000])` now answer.
- **Time**: five seconds, which is the library's own default and roughly fifty times the slowest
  evaluation of a payload AWS itself accepts. A recursive expression with no base case is a tail
  call, so it loops rather than nesting and only the clock ends it.

JSON has no literal for a non-finite number, so AWS writes each one as the string JavaScript names
it by, wherever it lands: `1/0` is `"Infinity"`, `1e308 * 10` is `"Infinity"`, `0/0` is `"NaN"` and
`$parseInteger("abc", "0")` is `"NaN"`, and nested, `[1/0]` is `["Infinity"]` and `{"k": 1/0}` is
`{"k": "Infinity"}`.

One deviation, on the arithmetic half of that. A non-finite number a **function** answers is a value
like any other here, so `$parseInteger("abc", "0")` is `"NaN"` on its own, inside an array and
inside an object, as on AWS. One the **arithmetic** produces is not: the JSONata library refuses to
carry it and raises instead, so Floci sees the value only in that refusal, after it has unwound
whatever was being built around it. `1/0`, `-1/0` and `1e308 * 10` are answered because the
expression's own result is what was refused; `[1/0]`, `{"k": 1/0}` and `$string(1/0)` fail the state
with `States.QueryEvaluationError` where AWS answers a string, and `0/0` fails the field that holds
it, because NaN is dropped as not-a-number without even a refusal to read it from. A state that
fails is one a `Catch` fires on, which is the half of the divergence worth keeping.

## Nested workflows

A parent workflow calls a child workflow through one of several integrations, and they differ in
more than syntax:

| Resource | Child type | A child that fails | Result |
| --- | --- | --- | --- |
| `arn:aws:states:::states:startExecution` | Standard | not awaited | `{executionArn, startDate}` |
| `arn:aws:states:::states:startExecution.sync` | Standard | fails the calling task | execution envelope, `output` as a JSON string |
| `arn:aws:states:::states:startExecution.sync:2` | Standard | fails the calling task | the child output, parsed |
| `arn:aws:states:::aws-sdk:sfn:startExecution` | Standard | not awaited | `{ExecutionArn, StartDate}` |
| `arn:aws:states:::aws-sdk:sfn:startSyncExecution` | Express | reported through `Status` | PascalCase envelope, `Output` as a JSON string |

`states:startExecution` and `aws-sdk:sfn:startExecution` are the same API through two different
integrations, and only the casing of the result tells them apart.

`startSyncExecution` is the only one that does not fail the calling task when the child fails: the
SDK call itself succeeded, so the task result carries `Status`, `Error` and `Cause` and the parent
decides what to do next.

## AWS SDK task integrations

A resource of the form `arn:aws:states:::aws-sdk:<service>:<action>` calls the service's API and
returns its response. Two conventions separate that result from the same API's wire response, and
both are AWS's:

- **Field names are the SDK's**, so a `startDate` on the wire is a `StartDate` in the task result.
- **A timestamp is an ISO-8601 string** such as `2026-08-28T20:34:59.712Z`, where the wire response
  carries epoch seconds.

A failure names the SDK exception class, which always ends in `Exception`:
`StartExecution` answers a missing state machine with the error code `StateMachineDoesNotExist` on
the wire and the task fails with `Sfn.StateMachineDoesNotExistException`.

| Resource | Result | Notable failure |
| --- | --- | --- |
| `arn:aws:states:::aws-sdk:sfn:startExecution` | `{ExecutionArn, StartDate}` | `Sfn.ExecutionAlreadyExistsException` when `Name` is reused |
| `arn:aws:states:::aws-sdk:sfn:startSyncExecution` | execution envelope | `Sfn.StateMachineTypeNotSupportedException` for a Standard child |
| `arn:aws:states:::aws-sdk:sfn:sendTaskSuccess` | `{}` | `Sfn.InvalidTokenException` when no task is waiting on the token |
| `arn:aws:states:::aws-sdk:sfn:sendTaskFailure` | `{}` | `Sfn.InvalidTokenException` |
| `arn:aws:states:::aws-sdk:scheduler:createSchedule` | `{ScheduleArn}` | `Scheduler.ConflictException` when the name is taken |
| `arn:aws:states:::aws-sdk:scheduler:updateSchedule` | `{ScheduleArn}` | `Scheduler.ResourceNotFoundException` |

`sendTaskSuccess` and `sendTaskFailure` resolve a token a `.waitForTaskToken` task is parked on. A
token nobody is waiting for fails the calling task rather than reporting a delivery that never
happened.

## Publishing events

`arn:aws:states:::events:putEvents` returns the PutEvents response itself, `Entries` and
`FailedEntryCount`, and one rejected entry fails the whole task with `EventBridge.FailedEntry`. The
cause is the response serialized as a string, so a `Catch` can read which entry was rejected:

```json
{"FailedEntryCount":1,"Entries":[{"EventId":"08cbdc46-…"},{"ErrorCode":"InvalidArgument","ErrorMessage":"EventBus not found: no-such-bus"}]}
```

One deviation, and it belongs to EventBridge rather than to the integration: Floci rejects an entry
addressed to an event bus that does not exist, while AWS accepts it and returns an `EventId`.

## JSONata expressions are validated when the state machine is created

A JSONata expression runs with no context item: the execution input arrives as `$states.input`
and a variable written by `Assign` as `$name`. A path that starts from neither reads a context
item that does not exist, and AWS refuses the whole definition:

```
An error occurred (InvalidDefinition) when calling the CreateStateMachine operation:
Invalid State Machine Definition: 'UNSUPPORTED_JSONATA_EXPRESSION: Reference to 'phone' at the
top level is not supported. at /States/E/Output/v'
```

Floci refuses it too, from `CreateStateMachine`, `UpdateStateMachine` and
`ValidateStateMachineDefinition`, with that message and that location. Write
`$states.input.phone` to read the input and `$phone` to read a variable: an earlier `Assign` of
`phone` does not put a bare `phone` in scope on AWS either.

Only the first step of a path is read against the top-level context, so a name in a later step,
in a predicate, in a sort term or in an object grouping stays legal and
`$states.input.items[value > 3]` is accepted. A lambda body keeps the context of the expression
that defines it, so `$map($states.input.a, function($x){ b })` does name `b` at the top level.

Two neighbouring rules stay with the execution rather than the definition. A syntax error such as
`{% a[1,2) %}` is accepted here and fails the execution, where AWS reports
`INVALID_JSONATA_EXPRESSION` when the state machine is created; and `$$`, which AWS refuses under
its own message, is accepted here.

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
