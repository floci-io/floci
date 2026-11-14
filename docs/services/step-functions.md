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
| `CreateStateMachineAlias` | - |
| `DescribeStateMachineAlias` | - |
| `ListStateMachineAliases` | - |
| `UpdateStateMachineAlias` | - |
| `DeleteStateMachineAlias` | - |
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

`ListExecutions` honors `statusFilter` when listing a state machine's executions.
Omitting the filter returns all statuses. Invalid filter values or types return
`ValidationException`. Map Run execution listing and `PENDING_REDRIVE` filtering are not
supported; `PENDING_REDRIVE` returns `ValidationException` even when a `mapRunArn` is supplied.

Lambda task invocation retains the alias or version from `FunctionName`, the
optimized integration's `Qualifier` parameter, or a direct function ARN when
acquiring execution capacity. Embedded and explicit qualifiers must agree.
A READY provisioned
concurrency pool for that qualifier can serve the task; resolving an alias to
its version does not discard its pool identity. Unqualified invocation retains
the normal on-demand path, and existing authorization checks still precede
execution.

## Task-token IAM resources

With IAM enforcement enabled, `SendTaskSuccess`, `SendTaskFailure`, and
`SendTaskHeartbeat` authorize against the resource associated with the token:
the activity ARN for an activity task, or the unqualified state-machine ARN
for a `.waitForTaskToken` service integration, including versioned executions.
A grant on only the version ARN does not match. A wildcard grant also matches, and
an explicit deny on the matching resource wins. An execution ARN or an
unrelated resource does not grant callback access.

This distinction was verified with signed SDK calls against AWS using both
Lambda callback integrations and activities. Recent completed tokens retain
their resource so a permitted retry receives `TaskTimedOut`. Unknown tokens
return `InvalidToken` before IAM resource evaluation. Resource lookup
does not consume the token. The caller must own the token before its resource
is used for IAM evaluation, including for recent completed tokens.

## Nested task credentials

Nested `states:startExecution` tasks, including `.sync` and `.sync:2`, and the SDK
`sfn:startExecution` and `sfn:startSyncExecution` integrations accept `Credentials.RoleArn`.
JSONPath `RoleArn.$` resolves against the state input after `InputPath` and before
`Parameters`. The role must exist with its exact ARN in the execution's partition.
The child call and polling use that role's account; parent history, subsequent states,
and cancellation checks retain the parent's account.

With IAM enforcement enabled, the existing task authorizer checks the execution role's
permission to assume the task role, its trust policy, and the task role's permission to
start the child. Account routing also works with enforcement disabled. The `.sync`
polling and cancellation paths retain the task account; their additional IAM permissions
are not evaluated. Every other integration evaluates `Credentials` itself
([Lambda](#lambda-tasks-under-iam-enforcement), [messaging](#messaging-tasks-under-iam-enforcement),
[data](#data-tasks-under-iam-enforcement), [jobs](#job-tasks-under-iam-enforcement),
[control](#control-tasks-under-iam-enforcement)), and so does a nested task in its `.waitForTaskToken`
form, which this routing does not serve: it runs in the state machine's account.
A foreign state-machine ARN without task credentials grants no account access.

A missing or mismatched task role fails with `States.TaskFailed`. A target account
that does not match the selected task account fails with
`StepFunctions.AWSStepFunctionsException` for optimized integrations or
`Sfn.SfnException` for SDK integrations, matching the live AWS probes.

## Execution history events

`GetExecutionHistory` emits an event family around a Task state's `TaskStateEntered` and
`TaskStateExited` pair. Which family depends on the resource type.

- A service integration ARN (`arn:aws:states:::...`) emits `TaskScheduled`, `TaskStarted`,
  and then `TaskSucceeded` or `TaskFailed`. `taskScheduledEventDetails` carries
  `resourceType`, `resource`, `region`, and `parameters`. `parameters` is the resolved
  `Parameters` or `Arguments` payload, serialized as a JSON string. `timeoutInSeconds` and
  `heartbeatInSeconds` appear only when the state sets `TimeoutSeconds` or
  `HeartbeatSeconds` as a literal number.
  `resourceType` is the integrated service (`states`, `ecs`, `aws-sdk:sqs`) and `resource` the
  API with its suffix (`startExecution.sync:2`, `runTask.sync`), as AWS names them. A `.sync`
  Task (`states:startExecution.sync` and `.sync:2`, `ecs:runTask.sync`, the `elasticmapreduce:`
  and `emr-serverless:` ones) also records
  `TaskSubmitted` after `TaskStarted`, once the call that started its job has returned and before
  it waits on the job. `taskSubmittedEventDetails` carries `resourceType`, `resource`,
  `outputDetails`, and that call's response as `output`, in PascalCase: `{"ExecutionArn",
  "StartDate"}` with `StartDate` in epoch milliseconds for a nested execution, `{"Failures",
  "Tasks"}` for an ECS task, `{"ClusterId"}`, `{"StepId"}` or `{}` for an EMR one, the API's
  response or `{"ApplicationId"}` for an EMR Serverless one. AWS folds the SDK's `SdkHttpMetadata` and `SdkResponseMetadata`
  into that response as well; Floci records the response fields alone.
- A direct Lambda function ARN emits `LambdaFunctionScheduled`, `LambdaFunctionStarted`,
  and then `LambdaFunctionSucceeded` or `LambdaFunctionFailed`. `LambdaFunctionScheduled`
  carries `resource` and `input`. `resource` holds the full function ARN. `LambdaFunctionStarted`
  carries no details at all. This matches AWS.
- An activity ARN emits the equivalent `Activity*` family.

A `Retry` re-entry emits its own Scheduled, Started, and Failed triple for each attempt. A
mocked Task (`SFN_MOCK_CONFIG`) emits the same events as a real one, because Step Functions
Local does the same.

Every event's `previousEventId` points to the id of the event right before it on the same
chain of states. The one exception is the first state's `*StateEntered` event. Its
`previousEventId` is `0`. That matches `ExecutionStarted`, which is always
`id: 1, previousEventId: 0`.

### Parallel branches and Map iterations

The states inside a `Parallel` branch or an inline `Map` iteration publish their events into
the parent execution's history, as on AWS. A `Parallel` records `ParallelStateStarted`,
`ParallelStateSucceeded` and `ParallelStateFailed`. An inline `Map` records `MapStateStarted`,
`MapIterationStarted`, `MapIterationSucceeded`, `MapIterationFailed`, `MapStateSucceeded` and
`MapStateFailed`. A Distributed `Map` records `MapRunStarted`, `MapRunSucceeded` and
`MapRunFailed` instead. Its items are child executions and publish nothing into the parent
history. A `Task` whose failure ends its branch also records `TaskStateAborted`. When a failure
ends a `Parallel`, each other branch that is still inside a `Task` or a `Wait` records
`TaskStateAborted` or `WaitStateAborted`, chained to the failing branch's last event and recorded
before `ParallelStateFailed`. The `Parallel` fails the moment any branch fails, whichever branch
is listed first, and cuts the others then; the branch outputs still come back in declaration
order. When a failure ends an inline `Map`, each other iteration that is still running records
`MapIterationAborted`, and one still inside a `Task` or a `Wait` records `TaskStateAborted` or
`WaitStateAborted` right after it, all chained to the failing iteration's last event and recorded
before `MapIterationFailed` and `MapStateFailed`.

Branches and iterations run concurrently, so the order in which their events interleave differs
from run to run. Each branch chains its own events through `previousEventId`, and that chain is
the same every time.

### The cause of a failure

The `cause` of a failure the interpreter raises starts with
`An error occurred while executing the state '<name>' (entered at the event id #<n>). `, as on
AWS. The prefix is added once, at the innermost state. A `Fail` state's `Cause` and a cause a
task's resource answered with pass through unchanged. A `Choice` that matches no rule and has
no `Default`, and a payload template path that matches nothing, fail with `States.Runtime`.
A JSONata expression that fails also records an `EvaluationFailed` event with `error`, `cause`,
`location` and `state`, once per attempt.

`inputDetails` appears on `ExecutionStarted`, on `stateEnteredEventDetails`, and on
`LambdaFunctionScheduled`/`ActivityScheduled`. `outputDetails` appears on
`stateExitedEventDetails`, on `executionSucceededEventDetails`, and on
`taskSucceededEventDetails`/`lambdaFunctionSucceededEventDetails`. Both are always
`{"truncated": false}`. Floci never truncates a payload, so the value never changes.
When the request sets `includeExecutionData` to false, the details objects stay but lose
`input`, `inputDetails`, `output`, and `outputDetails`. Every other field stays, including
`taskScheduledEventDetails.parameters`. This matches AWS.

A few gaps remain. `TaskStarted`, `LambdaFunctionStarted`, and `ActivityStarted` fire at
scheduling time, not when a worker actually picks up the task. `TaskSubmitted` is recorded for
the three `.sync` integrations above and not yet for a `.waitForTaskToken` one, which AWS also
records it for. A Distributed `Map` that declares no tolerance reports a failed item's own error
rather than AWS's `States.ExceedToleratedFailureThreshold`, and emits `MapRunFailed` with that
error. A `Map` that declares one reports `States.ExceedToleratedFailureThreshold`, as AWS does.

### Pages of history

`GetExecutionHistory` returns one page per call: `maxResults` events, 100 when it is omitted or
`0`, and at most 1,000. A `nextToken` comes back while events remain, and `reverseOrder` lists the
newest event first. A token belongs to its execution and its `reverseOrder`: used with another
execution or the other order it is refused with `InvalidToken`, while `maxResults` and
`includeExecutionData` may change from one page to the next. Each token expires 24 hours after it
was issued, as on AWS. Floci signs its tokens with a key it generates at startup, so an edited
token, or one issued before a restart, is refused with `InvalidToken` as well.

## Map concurrency

Map states honor `MaxConcurrency` and, for JSONPath state machines, `MaxConcurrencyPath`.
JSONata state machines may supply `MaxConcurrency` as an expression. A value of `0`, or an
omitted value, uses the AWS service ceiling: 40 concurrent iterations for Inline Map states and
10,000 for Distributed Map states. `MaxConcurrency: 1` runs iterations sequentially.

Results remain in input order even when iterations finish out of order. If an iteration fails,
the Map state fails promptly, cancels its active sibling iterations, and does not start queued
iterations. A cancelled iteration whose `Task` was waiting on a `.sync` job stops that job, the
way a cut `Parallel` branch does.

## Distributed Map ItemReader

`ItemReader` reads a dataset from S3. The resource decides how the dataset is found, and
`ReaderConfig.InputType` decides how it is read.

`arn:aws:states:::s3:getObject` reads a single object:

- `JSON` is either an array, or an object whose entries become `Key` and `Value` items.
  `ReaderConfig.ItemsPointer` selects a node inside it.
- `JSONL` is one item per line. Blank lines are skipped, and `ItemsPointer` does not apply,
  matching AWS.
- `CSV` takes its field names from the first row, or from `ReaderConfig.CSVHeaders` when
  `CSVHeaderLocation` is `GIVEN`. Every value is a string: a row shorter than the headers pads
  with empty strings, and a longer one drops the surplus. `ReaderConfig.CSVDelimiter` selects
  `COMMA`, `PIPE`, `SEMICOLON`, `SPACE` or `TAB`, and a quoted field may contain the delimiter
  or a line break without ending the record. A doubled quote stands for a single quote, in an
  unquoted field as well as a quoted one, and a backslash escapes another backslash, a quote or
  the delimiter. A backslash before anything else is dropped, as AWS documents.
- `PARQUET` and `MANIFEST` are accepted by `CreateStateMachine` and fail the execution with
  `States.ItemReaderFailed`.

`arn:aws:states:::s3:listObjectsV2` reads every page under `Prefix`. Each item carries the AWS
fields `Etag`, `Key`, `LastModified` (epoch seconds), `Size` and `StorageClass`. An empty prefix
gives zero iterations and the Map succeeds.

`ReaderConfig.MaxItems` applies to every reader. Literal values cannot exceed 100,000,000; a
larger value resolved by `MaxItemsPath` or a JSONata expression is capped at that reader limit.
A dynamic limit resolving to zero reads the entire dataset.
`MaxItemsPath` accepts integers and integer strings within the signed 64-bit range. Values
outside that range or decimal values fail with `States.Runtime`; negative integers fail with
`States.ItemReaderFailed`. JSONata expressions must return integers, not strings.
JSONPath state machines may resolve the limit from the Map input with `MaxItemsPath`, while
JSONata state machines may use a `{% %}` expression in `MaxItems`. `MaxItems` and `MaxItemsPath`
are mutually exclusive, and `MaxItemsPath` is rejected for JSONata state machines.

Under IAM enforcement the reader needs `s3:GetObject` or `s3:ListBucket`: see
[Distributed Map S3 calls](#distributed-map-s3-calls-under-iam-enforcement).

## Distributed Map ItemBatcher

`ItemBatcher` hands each child execution a batch of items instead of a single item. The child input
is `{"BatchInput": ..., "Items": [...]}`, with `BatchInput` present only when the state declares it.
`ItemSelector` still runs per item, before the items are grouped.

A batch closes on `MaxItemsPerBatch`, on `MaxInputBytesPerBatch`, or on the 256 KiB child-input
ceiling AWS applies whether or not a byte limit is declared. Either limit may be given as a
`...Path` field, or as an expression in a JSONata state machine. With neither declared, items fill
one batch up to that ceiling. The size measured is the serialized child payload, envelope and
`BatchInput` included, not the items alone. An item that would exceed the ceiling on its own can
never start a child execution, so the state fails with `States.DataLimitExceeded` rather than
building a batch AWS would reject: reduce the item with `ItemSelector` first.

`MaxConcurrency` then bounds concurrent batches, and the Map result has one entry per batch rather
than per item. `DescribeMapRun` reports items under `itemCounts` and batches under
`executionCounts`.

## Tolerated failures

`ToleratedFailureCount` and `ToleratedFailurePercentage` let a Distributed `Map` absorb failed items
instead of failing on the first one. Both accept a `...Path` field, or an expression in a JSONata
state machine, and the percentage is taken over the item count. Declaring both applies the stricter
of the two.

An absorbed failure contributes no result, so the `Map` output carries one entry per successful
child execution. A `ResultWriter` still exports it: successful children go to `SUCCEEDED_0.json` and
absorbed failures to `FAILED_0.json`, each listed under the matching key of the manifest's
`ResultFiles`. A failed record carries `Error` and `Cause` in place of an output. Once the budget is
spent, the state fails with `States.ExceedToleratedFailureThreshold` and the run emits
`MapRunFailed`.

Under IAM enforcement a `ResultWriter` export needs `s3:PutObject` on each of these files: see
[Distributed Map S3 calls](#distributed-map-s3-calls-under-iam-enforcement).

`DescribeMapRun` reports the declared values under `toleratedFailureCount` and
`toleratedFailurePercentage`. A `Map` that declares neither keeps the earlier behaviour: the first
failed item fails the state, carrying that item's own error.

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
between attempts is capped at `floci.services.stepfunctions.max-wait-seconds`
(default 30), the same ceiling Floci applies to `Wait` states, so emulated runs stay fast.

## Wait states

A `Wait` state honors `Seconds`, `SecondsPath`, `Timestamp`, and `TimestampPath`. The two
`Seconds` forms pause for the given number of seconds. The two `Timestamp` forms parse an
ISO-8601 instant and pause until it, or return promptly when it has already passed. An
unparseable timestamp fails the execution with `States.Runtime`. In a JSONata state machine,
`Seconds` and `Timestamp` each accept a literal or a JSONata expression that produces the value.

One deviation. Every pause is capped at `floci.services.stepfunctions.max-wait-seconds`
(default 30) so emulated runs stay fast, where AWS sleeps the full duration.

## Timeouts

ASL carries two `TimeoutSeconds` fields and Floci enforces both, in the two terminal shapes
AWS uses. The state machine's own field bounds every state; a `Task`'s own field bounds one that
waits: for a task token (an activity, or a `.waitForTaskToken` integration) or for a job to end
(`ecs:runTask.sync`, the `elasticmapreduce:` and `emr-serverless:` `.sync` ones, `states:startExecution.sync`
and `.sync:2`). A Lambda or other SDK task that
returns directly is not bound by it.

The state machine's own `TimeoutSeconds` is the whole execution's budget. It is checked before
every state and inside a `Wait`, so a `Wait` longer than what is left is cut rather than slept
out. The execution ends `TIMED_OUT` with `stopDate` set and no `error` and no `cause` at all;
`States.Timeout` is named only in the single `ExecutionTimedOut` event, whose `previousEventId`
is `0`. The state that was cut gets no `*StateExited` event. A `Parallel` or `Map` branch runs
on its own thread and is not cut mid-state: the budget is enforced again as soon as the branch
returns.

A `Task` that waits for a task token — an activity, or a `.waitForTaskToken` integration — has
two independent bounds. `TimeoutSeconds` is the whole wait; `HeartbeatSeconds` is the longest gap
allowed between two `SendTaskHeartbeat` calls, and every heartbeat pushes that gap forward, so a
worker that reports as often as the definition asks runs until `TimeoutSeconds` runs out. Either
clock ends the state the same way: an `ActivityTimedOut` event — `TaskTimedOut` for a
`.waitForTaskToken` integration — carrying `States.Timeout` and no cause, then an execution that
reads `FAILED` with that same error. A `Catch` on a heartbeat expiry matches under
`States.HeartbeatTimeout` and under `States.Timeout` alike. Neither timeout carries a cause:
`DescribeExecution` and the `ExecutionFailed` event both leave the key out, where every other
failure reports one. A `Task` that declares no `TimeoutSeconds` waits 300 seconds, where AWS
waits a year.

A `Task` that waits for a `.sync` job runs under the same two clocks and no other: the execution's
budget ends it `TIMED_OUT`, its own `TimeoutSeconds` ends it `FAILED` with a `TaskTimedOut` event
carrying `States.Timeout` and no cause, and a job that takes longer than either simply runs until
the earlier clock fires. The default of 300 seconds applies here too when the state declares none.
When the `Task`'s own clock fires, the job it was waiting on is stopped the way AWS stops it: the
ECS task reads `stopCode: UserInitiated`, the child execution reads `ABORTED` with no error, and
both carry the cause `The Task state in AWS Step Functions execution [<arn>] which was managing
this resource was aborted`. A `StopExecution` that lands while the state waits ends the wait and
stops the job with the same cause, and so does the execution's budget, and so does a failure in
another branch of the `Parallel`, or in another iteration of the `Map`, that the `Task` is in.
An `addStep.sync` step is cancelled with `CancelSteps` instead, and the cluster of a
`createCluster.sync` is left running (see [Amazon EMR tasks](#amazon-emr-tasks)). An EMR Serverless
job run is cancelled, a started application stopped and a created one deleted (see
[Amazon EMR Serverless tasks](#amazon-emr-serverless-tasks)).

One deviation. AWS starts the `TimeoutSeconds` clock when a worker picks the task up, the instant
it emits `ActivityStarted`. Floci emits `ActivityStarted` at schedule time, so both clocks start
when the task is scheduled.

## Intrinsic arguments

A `$.` reference passed to a `States.*` intrinsic must find something. An argument that matches
nothing fails the execution with `States.Runtime`, as on AWS.

One deviation. Indexing something that is not an array makes AWS leak its JSONPath library and
write `Filter: [0] can only be applied to arrays. Current context is: 1`. Floci writes its own
`The JsonPath argument for the field '$.other[0]' could not be found in the input ...` there.

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

The cause of a real execution carries the state prefix described under
[The cause of a failure](#the-cause-of-a-failure).

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

The execution input reaching `$states.input` follows the same number model as `$parse`: an integer
stays exact while it fits in a `long` and switches to a `double` past that boundary, matching AWS.

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
  `$sum([1..900000])` now answer. `$range` holds a tighter bound of its own, checked before it
  allocates rather than on the array it would have built: AWS accepts `$range(1, 360145, 1)`, all
  360,145 elements, and refuses one element more with the same refusal. A lazy literal range such
  as `[1..873782]` is exempt from both bounds on AWS, and stays exempt here.
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

## Execution names

A Standard execution name is unique per account, Region and state machine. Starting a Standard
execution with a name and input that match one still running returns that original execution, so a
retried call is idempotent; any other reuse of the name, whether a different input or a name whose
execution has already closed, fails with `ExecutionAlreadyExists`.

One deviation. AWS frees a Standard name 90 days after the execution closes; Floci keeps it taken for
the life of the emulator, so a closed name never becomes reusable on its own.

An Express execution name is not unique. Every `StartExecution` on an Express state machine is its
own execution that runs alongside any others of the same name, and its ARN carries a per-start id
after the name (`express:<stateMachine>:<name>:<id>`). Reusing an Express name never returns an
earlier execution and never fails with `ExecutionAlreadyExists`.

One more. AWS keeps no record of an Express execution once it ends, only its logs, and does not serve
`DescribeExecution`, `GetExecutionHistory` or `ListExecutions` for one. Floci keeps every Express
execution, running or finished, in its execution store (and, in persistent mode, in
`sfn-executions.json`), and nothing evicts them while the emulator runs. A workload that
starts the same Express name in a tight loop therefore grows that store without bound.

## Nested workflows

A parent workflow calls a child workflow through one of several integrations, and they differ in
more than syntax:

| Resource | Child type | A child that fails | Result |
| --- | --- | --- | --- |
| `arn:aws:states:::states:startExecution` | Standard | not awaited | `{ExecutionArn, StartDate}`, `StartDate` in epoch milliseconds |
| `arn:aws:states:::states:startExecution.sync` | Standard | fails the calling task | execution envelope, `Input` and `Output` as JSON strings |
| `arn:aws:states:::states:startExecution.sync:2` | Standard | fails the calling task | execution envelope, `Input` and `Output` as JSON values |
| `arn:aws:states:::aws-sdk:sfn:startExecution` | Standard | not awaited | `{ExecutionArn, StartDate}`, `StartDate` as an ISO-8601 string |
| `arn:aws:states:::aws-sdk:sfn:startSyncExecution` | Express | reported through `Status` | PascalCase envelope, `Output` as a JSON string |

`states:startExecution` and `aws-sdk:sfn:startExecution` are the same API through two different
integrations, and only the format of `StartDate` tells their results apart. AWS also adds
`SdkHttpMetadata` and `SdkResponseMetadata` to the `states:startExecution` result, which Floci
omits.

The execution envelope of the two `.sync` modes is the child's `DescribeExecution` response,
PascalCase with its keys in alphabetical order and dates in epoch milliseconds: `ExecutionArn`,
`Input`, `InputDetails`, `Name`, `Output`, `OutputDetails`, `RedriveCount`, `RedriveStatus`,
`RedriveStatusReason`, `StartDate`, `StateMachineArn`, `Status` and `StopDate`. So the child's
output is at `$.Output` in both modes: a JSON string through `.sync`, to read with
`States.StringToJson`, and a JSON value through `.sync:2`, to read directly, for example with a
`ResultSelector` or `OutputPath` of `$.Output`.

`startSyncExecution` is the only one that does not fail the calling task when the child fails: the
SDK call itself succeeded, so the task result carries `Status`, `Error` and `Cause` and the parent
decides what to do next.

The two `.sync` modes fail the calling task with `States.TaskFailed` however the child ended
(`FAILED`, `TIMED_OUT` or `ABORTED`) and whatever its own error was, `States.Timeout` included, so
a `Catch` on the child's own error name never takes it. The cause is the child's `DescribeExecution`
response as JSON, PascalCase with its keys in alphabetical order: `Cause` and `Error` when the
child has them, then `ExecutionArn`, `Input`, `InputDetails`, `Name`, `RedriveCount`,
`RedriveStatus`, `StartDate`, `StateMachineArn`, `Status` and `StopDate`, with dates in epoch
milliseconds and no `Output`. A child started through an alias also carries `StateMachineAliasArn`
and `StateMachineVersionArn`, and one started through a version carries `StateMachineVersionArn`,
each in its alphabetical place. A parent that needs the child's error reads it from there, for
example with `States.StringToJson($.Cause)` after a `Catch`.

A `Name` a Standard child already used fails the calling task with the child's collision error, named
for the integration that raised it: `StepFunctions.ExecutionAlreadyExistsException` through
`states:startExecution` in any of its modes, and `Sfn.ExecutionAlreadyExistsException` through
`aws-sdk:sfn:startExecution`. An Express child starts a new execution instead, so the same `Name`
never fails it.

## Lambda tasks under IAM enforcement

A Lambda task, given a function ARN as its `Resource` or as the `FunctionName` of
`arn:aws:states:::lambda:invoke`, runs the function in the account and Region the ARN names. With
[IAM enforcement](iam.md#resource-based-policies) on, the task invokes the function as the state
machine's execution role, as on AWS. In the role's own account, the role's policies or the
function's policy can allow `lambda:InvokeFunction`. For a function in another account, both have
to: the role's policies, and the function's policy through, for example, `AddPermission` naming the
role or its account. The role's permissions boundary and its account's service control policies
apply too, and a Deny in any of them refuses the call. A task that names a version or alias is
authorized against that version or alias. A refused task fails with `Lambda.AccessDeniedException`,
whose cause names the role's session and the kind of policy that refused it.

A task whose `Credentials` name a role invokes the function as that role instead, once the
execution role has assumed it. The role's trust policy has to allow the execution role, and Step
Functions passes the state machine's ARN as `sts:ExternalId`, which the trust policy can require.
The execution role's own policies have to allow `sts:AssumeRole` on the role too when the role is in
another account, or when the trust policy names only the execution role's account. `RoleArn` is
read the way the task's `Parameters` or `Arguments` are: as written, through a path or an intrinsic
function as `RoleArn.$`, or through JSONata. A role the execution role cannot assume fails the task
with `States.Permissions`, whose cause names the execution role's session and `sts:AssumeRole` on
the role.

## Messaging tasks under IAM enforcement

These tasks and their `.waitForTaskToken` forms are evaluated with
[IAM enforcement](iam.md#resource-based-policies) on:

| Resource | Action | Resource evaluated | Refused with |
|---|---|---|---|
| `arn:aws:states:::sqs:sendMessage` | `sqs:SendMessage` | The queue `QueueUrl` names | `SQS.AccessDeniedException` |
| `arn:aws:states:::aws-sdk:sqs:sendMessage` | `sqs:SendMessage` | The queue `QueueUrl` names | `Sqs.AccessDeniedException` |
| `arn:aws:states:::sns:publish` | `sns:Publish` | `TopicArn`, else `TargetArn`, or `*` for a `PhoneNumber` | `SNS.AuthorizationErrorException` |
| `arn:aws:states:::aws-sdk:sns:publish` | `sns:Publish` | `TopicArn`, else `TargetArn`, or `*` for a `PhoneNumber` | `Sns.AuthorizationErrorException` |
| `arn:aws:states:::events:putEvents` | `events:PutEvents` | Each distinct event bus the entries name | `EventBridge.AccessDeniedException` |

Each attempt is decided in turn, so `Retry` and `Catch` see a refusal like any other task failure:

1. The execution role has to exist and to trust `states.amazonaws.com`, or the task fails with
   `States.Permissions`.
2. If the task's `Credentials` name a role, the execution role has to be able to assume it, as for a
   [Lambda task](#lambda-tasks-under-iam-enforcement). A role it cannot assume, or one that does not
   exist, fails the task with `States.TaskFailed` and the cause "The role X is not authorized to
   assume the task state's role, Y.", the form AWS answers a nested execution with. `Credentials`
   that do not resolve to an object holding only `RoleArn` fail with `States.Runtime`.
3. The role the task ends up with has to be allowed the action on the resource, or the task fails with
   the error in the table, whose cause names the role's session and the kind of policy that refused
   it.

The resource is the one Floci's lookup reaches, which is what the task then acts on:

- **SQS:** the account and name in the URL's path, in the state machine's Region.
- **SNS:** the ARN as the request gives it. `PhoneNumber` comes first, since Floci sends the SMS
  whatever else the request names.
- **EventBridge:** the bus of the name `EventBusName` gives, or the default bus without one, in the
  state machine's account and Region. An ARN counts for its name only, because that is the bus
  `PutEvents` reaches from a task: a bus ARN naming another account or Region is evaluated, and sent
  to, as the bus of that name in the state machine's. A direct `PutEvents` request under enforcement
  evaluates the ARN as given. All the buses are evaluated before any entry is sent, so one refused
  bus sends nothing. An entry EventBridge would reject, such as one without `Source`, still counts,
  as for a direct `PutEvents`. The EventBridge User Guide states the check on every entry, and the
  refusal of the whole request, for the Custom Event Bus. It does not describe Classic buses, and
  Floci applies the same reading to them.

A request that names no resource has only the role checks in the list above, and its service then
answers it with its own error: no `QueueUrl`, a `TopicArn` or `TargetArn` that is not an SNS ARN or
neither of them, an entry that is not an object, an `EventBusName` that is a malformed ARN, or no
entries at all.

A refused permission is named as the task's other errors from its service are: the integration's
prefix and the exception the service's SDK raises. For SNS that is `AuthorizationErrorException`, the
refusal `Publish` declares and the one a direct `Publish` under enforcement answers with, and for the
others `AccessDeniedException`. `States.Permissions`, and these names for a refusal, are Floci's
reading: they have not been verified against AWS. Only global condition keys are available to the
policies.

Resource policies take part as they do for a direct request under enforcement, for queues, topics and
event buses:

- **SQS:** the queue's own policy (`SetQueueAttributes` or `AddPermission`) or the role's policies
  can allow the send within one account, and across accounts both have to.
- **SNS:** a topic policy can grant `sns:Publish` to a role of the topic's account with no identity
  policy, a Deny in it refuses a role its identity policy allows, and the default policy of a new
  topic admits no role, because no role's call carries `aws:SourceOwner`. Across accounts both the
  role's policies and the topic policy have to allow the publish.
- **EventBridge:** the bus's policy (`PutPermission`) can grant `events:PutEvents` to a role of the
  bus's account that it names, with no identity policy, and a Deny in it refuses a role its identity
  policy allows. A statement naming only the account, the form `PutPermission` writes for a
  `Principal` account id, leaves the decision to that account's IAM policies. Across accounts both the
  role's policies and the bus policy have to allow the send.

A role in another account, named by `Credentials`, is authorized as that role but the task still
runs in the state machine's account. Nested executions are the only tasks that run in the role's
account. With enforcement off nothing here is evaluated.

Floci has no `aws-sdk:eventbridge:putEvents` integration: that resource fails with
`States.TaskFailed` and "Unsupported resource" whether or not enforcement is on.

## Data tasks under IAM enforcement

These tasks and their `.waitForTaskToken` forms are evaluated with
[IAM enforcement](iam.md#resource-based-policies) on, in the same steps and with the same role errors
as [messaging tasks](#messaging-tasks-under-iam-enforcement):

| Resource | Action | Resource evaluated | Refused with |
|---|---|---|---|
| `arn:aws:states:::dynamodb:putItem`, `getItem`, `deleteItem`, `updateItem`, `scan` | `dynamodb:PutItem` and so on | The table `TableName` names | `DynamoDB.AccessDeniedException` |
| `arn:aws:states:::aws-sdk:dynamodb:<api>` | See below | See below | `DynamoDb.AccessDeniedException` |
| `arn:aws:states:::s3:putObject`, `arn:aws:states:::aws-sdk:s3:putObject` | `s3:PutObject` | `arn:<partition>:s3:::<bucket>/<key>` | `S3.AccessDeniedException` |
| `arn:aws:states:::aws-sdk:rdsdata:executeStatement` | `rds-data:ExecuteStatement`, `secretsmanager:GetSecretValue` | `ResourceArn`, `SecretArn` | `RdsData.AccessDeniedException` |
| `arn:aws:states:::aws-sdk:redshiftdata:<api>` | `redshift-data:<Action>` and what authenticates the call | See below | `RedshiftData.AccessDeniedException` |

The `aws-sdk:dynamodb` form is checked as a direct DynamoDB request under enforcement is:

- **Most APIs:** `dynamodb:<Api>` on the table, stream or other resource the request names, an ARN as
  given, or on `*` when it names none, which includes a `putItem` with no `TableName`.
- **`batchGetItem`, `batchWriteItem`:** the API's action on each table in `RequestItems`.
- **`transactWriteItems`, `transactGetItems`:** no action of their own. Each item needs its own, one of
  `dynamodb:PutItem`, `DeleteItem`, `UpdateItem`, `ConditionCheckItem` or `GetItem`, on its table, with
  `dynamodb:EnclosingOperation` available to the policy. A table named by ARN counts for its name, as
  execution reads it in the state machine's account.
- **`listTables`:** its action on the Region's `table/*` collection.
- **`executeStatement`:** `dynamodb:PartiQLSelect`, `PartiQLInsert`, `PartiQLUpdate` or `PartiQLDelete`,
  by what the statement does. `batchExecuteStatement` and `executeTransaction` are checked under their
  own names on each table a statement names, as a direct request is.

Every permission is evaluated before the call, so one refused table in a batch or a transaction writes
nothing.

The `aws-sdk:redshiftdata` form's statement operations (`describeStatement`, `cancelStatement`,
`getStatementResult`, `getStatementResultV2`, `listStatements`, `listSessions`) act on no resource in
the service reference, so they are checked on `*`. The others are checked on the cluster
`ClusterIdentifier` names, the workgroup `WorkgroupName` names, or the target of the session
`SessionId` names, with what an [EventBridge target](eventbridge.md) needs to authenticate:
`secretsmanager:GetSecretValue` on `SecretArn`, `redshift-serverless:GetCredentials` on the workgroup,
or `redshift:GetClusterCredentials` on the `dbuser` and `dbname` of a `DbUser`. A workgroup Floci does
not hold is evaluated on `workgroup/*`.

`secretsmanager:GetSecretValue` on an RDS Data `SecretArn` follows AWS's `AmazonRDSDataFullAccess`
managed policy and the CDK's `grantDataApiAccess`, which grant it along with the Data API actions.

A request whose member names nothing it can reach has only the role checks, and its service answers
it: no `TableName` in an optimized task or no recognised item in a transaction, a transaction's table
ARN that is malformed or in another Region, a DynamoDB `ResourceArn` or similar that is not an ARN, no
`Bucket` or `Key`, an RDS Data `ResourceArn` that is not an ARN, or a Redshift Data request naming no
target. An API a form does not serve keeps its existing answer: `dynamodb:query` and
`aws-sdk:rdsdata:beginTransaction` are "Unsupported" whether or not enforcement is on, and
`aws-sdk:redshiftdata` answers its own `ValidationException` for an operation it does not have.

The S3 bucket is resolved as a direct request resolves it. A bucket name is one namespace across
partitions, so a task can reach a bucket of another partition, and the object's ARN is evaluated in the
bucket's partition, not the state machine's. A bucket policy takes part as it does for a direct
request: it can grant `s3:PutObject` to a role of the bucket's account with no identity policy, and a
Deny in it refuses a role its identity policy allows. Another account's bucket is `S3.NoSuchBucket` to
an allowed role unless `floci.services.s3.global-bucket-namespace` is on, and with it on both the
role's policy and the bucket's have to allow. A DynamoDB table named by an ARN of another account is
refused, since no table policy can grant it here.

`<prefix>.AccessDeniedException` is Floci's reading for these too and has not been verified against
AWS. Apart from `dynamodb:EnclosingOperation`, only global condition keys are available to the
policies: `dynamodb:LeadingKeys`, `dynamodb:Attributes` and the like are absent. Global tables are not
treated apart. With enforcement off nothing here is evaluated.

## Job tasks under IAM enforcement

These tasks are evaluated with [IAM enforcement](iam.md#resource-based-policies) on, in the same steps
and with the same role errors as [messaging tasks](#messaging-tasks-under-iam-enforcement). Each action
and resource type comes from the Service Authorization Reference:

| Resource | Action | Resource evaluated | Refused with |
|---|---|---|---|
| `arn:aws:states:::ecs:runTask`, with `.sync` or `.waitForTaskToken` | `ecs:RunTask` | The task definition revision the task would run | `ECS.AccessDeniedException` |
| `arn:aws:states:::aws-sdk:ecs:updateService` | `ecs:UpdateService` | The service it acts on | `Ecs.AccessDeniedException` |
| `arn:aws:states:::aws-sdk:ecs:describeServices` | `ecs:DescribeServices` | Each service it answers for | `Ecs.AccessDeniedException` |
| `arn:aws:states:::aws-sdk:ecs:listServices` | `ecs:ListServices` | `*` | `Ecs.AccessDeniedException` |
| `arn:aws:states:::elasticmapreduce:createCluster`, `.sync` | `elasticmapreduce:RunJobFlow` | `*` | `EMR.AccessDeniedException` |
| `arn:aws:states:::elasticmapreduce:setClusterTerminationProtection` | `elasticmapreduce:SetTerminationProtection` | `cluster/<ClusterId>` | `EMR.AccessDeniedException` |
| `arn:aws:states:::elasticmapreduce:terminateCluster`, `.sync` | `elasticmapreduce:TerminateJobFlows` | `cluster/<ClusterId>` | `EMR.AccessDeniedException` |
| `arn:aws:states:::elasticmapreduce:addStep`, `.sync` | `elasticmapreduce:AddJobFlowSteps` | `cluster/<ClusterId>` | `EMR.AccessDeniedException` |
| `arn:aws:states:::elasticmapreduce:cancelStep` | `elasticmapreduce:CancelSteps` | `cluster/<ClusterId>` | `EMR.AccessDeniedException` |
| `arn:aws:states:::elasticmapreduce:modifyInstanceFleetByName` | `elasticmapreduce:ListInstanceFleets`, `ModifyInstanceFleet` | `cluster/<ClusterId>` | `EMR.AccessDeniedException` |
| `arn:aws:states:::elasticmapreduce:modifyInstanceGroupByName` | `elasticmapreduce:ListInstanceGroups`, `ModifyInstanceGroups` | `cluster/<ClusterId>` | `EMR.AccessDeniedException` |
| `arn:aws:states:::emr-serverless:createApplication`, `.sync` | `emr-serverless:CreateApplication` | `*` | `EmrServerless.AccessDeniedException` |
| `arn:aws:states:::emr-serverless:startApplication`, `stopApplication`, `deleteApplication`, `.sync` | `emr-serverless:StartApplication` and so on | `/applications/<ApplicationId>` | `EmrServerless.AccessDeniedException` |
| `arn:aws:states:::emr-serverless:startJobRun`, `.sync` | `emr-serverless:StartJobRun` | `/applications/<ApplicationId>` | `EmrServerless.AccessDeniedException` |
| `arn:aws:states:::emr-serverless:cancelJobRun`, `.sync` | `emr-serverless:CancelJobRun` | `/applications/<ApplicationId>/jobruns/<JobRunId>` | `EmrServerless.AccessDeniedException` |

A task definition is evaluated as the revision it resolves to, so a bare family, `family:revision` and
the ARN give the same resource: `task-definition/<family>:<revision>`. A role allowed only an older
revision is refused a bare family whose latest revision is newer.

The ECS services are resolved as ECS resolves them. `updateService` acts on the service of that name in
the request's `Cluster`, or in `default` with none, whatever cluster a service ARN in the request
names, and that is the service evaluated.

A ByName API needs its `List` action as well as its `Modify` one, since it reads the cluster's fleets or
groups to find the id. Nothing documents this for a task: it is inferred from what the integration
calls, and it is what the CDK's policies for these two tasks grant.

`.sync` authorizes the starting call only: a job cannot fail midway through its wait for lack of a
Describe permission, and the best-effort cancel of an abandoned job is not evaluated either. What the
CDK grants a `.sync` task beyond that, such as `elasticmapreduce:DescribeStep` or `ecs:DescribeTasks`,
is accepted but not needed here. `iam:PassRole` is not evaluated for the roles `runTask`, `createCluster` or
`startJobRun` pass, nor a tagging action such as `elasticmapreduce:AddTags` for what they tag.

A request naming nothing it can reach has only the role checks, and its service answers it: no
`TaskDefinition` or one ECS does not have, a service or cluster ECS does not have, no `ClusterId`, no
`ApplicationId`, or a `cancelJobRun` without `JobRunId`. A form an integration does not serve keeps
its answer: `.waitForTaskToken` on EMR and EMR Serverless and `.sync` on an EMR API with no wait are
"Unsupported" whether or not enforcement is on, and so is any API Floci does not have.

`<prefix>.AccessDeniedException` is Floci's reading for these too and has not been verified against
AWS; the prefix is the one each integration's other errors carry. EMR's own errors reach a task as
`EMR.AmazonElasticMapReduceException` unless EMR models them, so AWS may word this one that way. Only
global condition keys are available to the policies: `ecs:cluster` and the like are absent, so an Allow
conditioned on them does not apply. With enforcement off nothing here is evaluated.

## Control tasks under IAM enforcement

With `floci.services.iam.enforcement-enabled`, these tasks are authorized like the
[messaging tasks](#messaging-tasks-under-iam-enforcement), in the same order. Each action and resource
type comes from the Service Authorization Reference:

| Resource | Action | Resource evaluated | Refused with |
|---|---|---|---|
| `arn:aws:states:::aws-sdk:scheduler:createSchedule`, `updateSchedule`, `deleteSchedule` | `scheduler:CreateSchedule` and so on | `schedule/<GroupName>/<Name>`, `default` for a group left out | `Scheduler.AccessDeniedException` |
| `arn:aws:states:::aws-sdk:cloudformation:<api>` | `cloudformation:<Action>` | See below | `CloudFormation.AccessDeniedException` |
| `arn:aws:states:::http:invoke` | `states:InvokeHTTPEndpoint` | The state machine | `States.Http.AccessDeniedException` |
| `arn:aws:states:::aws-sdk:ec2:describeRegions` | `ec2:DescribeRegions` | `*` | `Ec2.AccessDeniedException` |
| `arn:aws:states:::aws-sdk:lambda:listEventSourceMappings` | `lambda:ListEventSourceMappings` | `*` | `Lambda.AccessDeniedException` |
| `arn:aws:states:::aws-sdk:lambda:updateEventSourceMapping` | `lambda:UpdateEventSourceMapping` | `event-source-mapping:<UUID>` | `Lambda.AccessDeniedException` |
| `arn:aws:states:::aws-sdk:secretsmanager:describeSecret` | `secretsmanager:DescribeSecret` | The secret's stored ARN, suffix included | `SecretsManager.AccessDeniedException` |
| `arn:aws:states:::aws-sdk:sfn:startExecution`, `startSyncExecution` | `states:StartExecution`, `states:StartSyncExecution` | The child state machine | `Sfn.AccessDeniedException` |
| `arn:aws:states:::aws-sdk:sfn:sendTaskSuccess`, `sendTaskFailure` | `states:SendTaskSuccess`, `states:SendTaskFailure` | The activity or state machine waiting on the token | `Sfn.AccessDeniedException` |
| `arn:aws:states:::aws-sdk:sfn:describeMapRun` | `states:DescribeMapRun` | The Map Run | `Sfn.AccessDeniedException` |
| `arn:aws:states:::states:startExecution`, with `.sync`, `.sync:2` or `.waitForTaskToken` | `states:StartExecution` | The child state machine | `StepFunctions.AccessDeniedException` |

A CloudFormation action is evaluated on the stack or stack set the request reaches, under the id
CloudFormation holds for it, so a name and the id it stands for are one resource:
`stack/<StackName>/<id>` and `stackset/<StackSetName>:<id>`. `createStack` and `createChangeSet` on a stack
not there yet evaluate `stack/<StackName>/*`. A change set named by its ARN, or a `describeStackResources`
naming a `PhysicalResourceId`, is evaluated on the stack that holds it. `describeStacks` and
`getTemplateSummary` without a `StackName` are on `*`, as are `validateTemplate`, `listStacks`, `listExports`,
`createStackSet`, `listStackSets`, `describeOrganizationsAccess` and `activateOrganizationsAccess`, which
have no resource type.

`states:InvokeHTTPEndpoint` has no resource type in the Service Authorization Reference, and the CDK grants
it on `*`, while the Developer Guide's example scopes it to the state machine, so the state machine is what is
evaluated: both policies allow it. The task gives `states:HTTPEndpoint` and `states:HTTPMethod`, both of which
those policies use. The value of `states:HTTPEndpoint` is `ApiEndpoint` as resolved, without `QueryParameters`.
The guide also requires `events:RetrieveConnectionCredentials` and the connection secret's Secrets Manager
permissions. Floci does not evaluate them, although the task reads the connection to authorize its request:
a role lacking them succeeds here and fails on AWS.

A nested `states:startExecution` task does not refuse its execution role for not existing or not
trusting Step Functions, as a Lambda task does not. It only needs the action, which a role that does not
exist does not have. `.sync` needs the starting call alone, not `states:DescribeExecution`, `states:StopExecution`
or the EventBridge permissions the CDK grants for its wait. With `Credentials`, a nested task other than
the `.waitForTaskToken` form keeps the checks and errors of
[nested task credentials](#nested-task-credentials).

Every integration that evaluates a role is refused on its `.waitForTaskToken` form the same way: the refused
task starts nothing and waits for nothing. With the permission, it calls its service and then waits on its
token.

A request naming nothing it can reach has only the role checks, and its service answers it: no `Name`, a stack,
stack set or secret name its service does not have, no `UUID`, no `ApiEndpoint`, a `StateMachineArn` or `MapRunArn`
that is not one, or a token naming no task. A secret or stack named by an ARN of what does not exist here is
evaluated on that ARN, for a secret, or left to the service, for a stack. An API Floci does not serve keeps its
answer, whatever the role: it is "Unsupported", or CloudFormation's `UnknownAction`, and the role is not checked.

An activity task is not evaluated: the CDK's own task needs no permission for it, and the worker calling
`GetActivityTask` is authorized by its own request. `Map`'s S3 calls are in
[their own section](#distributed-map-s3-calls-under-iam-enforcement).

`<prefix>.AccessDeniedException` and `States.Http.AccessDeniedException` are Floci's reading and have not
been verified against AWS; a nested task with `Credentials` reports a refused action as
`StepFunctions.AWSStepFunctionsException` or `Sfn.SfnException` instead, so a state machine catching this should
name both. `iam:PassRole` is not evaluated for the roles a schedule or a stack names, and only
the condition keys above and global ones are available to the policies. With enforcement off nothing here is
evaluated.

## Distributed Map S3 calls under IAM enforcement

With `floci.services.iam.enforcement-enabled`, a Distributed `Map` makes its S3 calls as its execution role, which
has to exist and to trust `states.amazonaws.com`. Each permission is evaluated before the call that needs it, on the
ARN a direct request is checked on, in the bucket's own partition, and the bucket's policy can grant or deny it.

| Field | Form | Action | Resource | Refused with |
|---|---|---|---|---|
| `ItemReader` | `arn:aws:states:::s3:getObject` (`JSON`, `JSONL`, `CSV`) | `s3:GetObject` | `<bucket>/<Key>` | `States.ItemReaderFailed` |
| `ItemReader` | `arn:aws:states:::s3:listObjectsV2` | `s3:ListBucket`, with `s3:prefix` when the request has a `Prefix` | `<bucket>` | `States.ItemReaderFailed` |
| `ResultWriter` | `arn:aws:states:::s3:putObject` | `s3:PutObject` | `<bucket>/<Prefix>/<run id>/SUCCEEDED_0.json`, `manifest.json`, and `FAILED_0.json` when a child failed | `States.ResultWriterFailed` |

An execution role that does not exist or does not trust Step Functions is refused with `States.Permissions`. Each is
a failure of the `Map` state, so its `Retry` and `Catch` apply. The refusal's cause names the action and the resource.

A refused reader starts no Map run and no child. A refused writer fails the Map run after its children have run, as
any other `ResultWriter` failure does. The keys of an export are known before its first write, so all of them are
evaluated first and a refusal of any leaves nothing in the bucket.

The export writes each file in a single `PutObject`, so the rest of what the CDK grants a `ResultWriter`,
`s3:GetObject`, `s3:ListMultipartUploadParts` and `s3:AbortMultipartUpload`, is accepted but not needed here.

A request naming nothing it can reach has only the role checks, and is answered as it would be without them: no
`Bucket` or `Key`, or a bucket S3 does not have once the role is allowed. The `ResultWriter` Region check comes after
the evaluation. `PARQUET` and `MANIFEST` make no S3 call here, so they keep their `States.ItemReaderFailed` whatever
the role. A `WriterConfig` with no `Resource`, a Map over its input and an inline `Map` make no S3 call either, and
need nothing. The tasks inside `ItemProcessor` are evaluated as any other task.

A `Map` has no credentials of its own here: `Credentials` on the state, or inside `ItemReader` or `ResultWriter`, is
not read. An execution with a [mock test case](#mocked-service-integrations) is evaluated on these calls too: a test
case cannot mock a `Map`, so its S3 calls stay real, as an unmocked task's call in that execution does. `States.ItemReaderFailed` and `States.ResultWriterFailed` for a refusal are Floci's
reading and have not been verified against AWS.

## Task credentials in history

A task that declares `Credentials` records the resolved role in the `taskCredentials.roleArn` of its
`TaskScheduled` or `LambdaFunctionScheduled` event, with enforcement on or off. With enforcement off,
`Credentials` that do not resolve are left out of the event and the task runs as if they were not
there. `ActivityScheduled` has no such member, as in the AWS model.

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
| `arn:aws:states:::aws-sdk:ecs:updateService` | `{Service}` | `Ecs.ServiceNotFoundException` |
| `arn:aws:states:::aws-sdk:ecs:listServices` | `{ServiceArns, NextToken?}` | `Ecs.ClusterNotFoundException` |
| `arn:aws:states:::aws-sdk:ecs:describeServices` | `{Services, Failures}` | Missing services appear in `Failures` |
| `arn:aws:states:::aws-sdk:lambda:listEventSourceMappings` | `{EventSourceMappings}` | `Lambda.InvalidParameterValueException` |
| `arn:aws:states:::aws-sdk:lambda:updateEventSourceMapping` | mapping metadata, including `UUID` | `Lambda.ResourceNotFoundException` |
| `arn:aws:states:::aws-sdk:secretsmanager:describeSecret` | secret metadata, including `VersionIdsToStages` | `SecretsManager.ResourceNotFoundException` |
| `arn:aws:states:::aws-sdk:sfn:startExecution` | `{ExecutionArn, StartDate}` | `Sfn.ExecutionAlreadyExistsException` when `Name` is reused on a Standard child |
| `arn:aws:states:::aws-sdk:sfn:startSyncExecution` | execution envelope | `Sfn.StateMachineTypeNotSupportedException` for a Standard child |
| `arn:aws:states:::aws-sdk:sfn:sendTaskSuccess` | `{}` | `Sfn.InvalidTokenException` when no task is waiting on the token |
| `arn:aws:states:::aws-sdk:sfn:sendTaskFailure` | `{}` | `Sfn.InvalidTokenException` |
| `arn:aws:states:::aws-sdk:rdsdata:executeStatement` | RDS Data statement result | `RdsData.BadRequestException` for an invalid request |
| `arn:aws:states:::aws-sdk:redshiftdata:executeStatement` | Data API statement handle (`Id`, `CreatedAt`, ...) | `RedshiftData.ValidationException` for an invalid request |
| `arn:aws:states:::aws-sdk:redshiftdata:describeStatement` | statement status, `ResultRows` | `RedshiftData.ResourceNotFoundException` for an unknown `Id` |
| `arn:aws:states:::aws-sdk:scheduler:createSchedule` | `{ScheduleArn}` | `Scheduler.ConflictException` when the name is taken |
| `arn:aws:states:::aws-sdk:scheduler:updateSchedule` | `{ScheduleArn}` | `Scheduler.ResourceNotFoundException` |
| `arn:aws:states:::aws-sdk:scheduler:deleteSchedule` | `{}` | `Scheduler.ResourceNotFoundException` |
| `arn:aws:states:::aws-sdk:sns:publish` | `{MessageId}` | `Sns.NotFoundException` when the topic does not exist |

Callback tokens are owned by the execution account and region. Foreign-account callbacks return
`AccessDeniedException`; callbacks sent to another region and unknown tokens return `InvalidToken`.
Recently closed tokens return `TaskTimedOut`. Closed-token classification retains at most 1,024 tokens for five minutes; older
or evicted tokens return `InvalidToken`. Invalid JSON output leaves a pending token available
for a corrected callback. Token state remains in memory.

The six ECS, Lambda mapping, and Secrets Manager management tasks above delegate
to the existing local service handlers in the execution account and state-machine
region. They use PascalCase request/response members and ISO-8601 response
timestamps. Opaque dictionary keys, including secret version IDs and ECS log
options, retain their original spelling. `describeSecret` returns metadata without
the secret value. Disabled services fail with a catchable service exception.

Lambda `updateEventSourceMapping` tasks identify the mapping with `Uuid` in
JSONPath `Parameters` or JSONata `Arguments`, following the Step Functions SDK
parameter spelling. The direct Lambda API and existing task-result fields retain
their `UUID` spelling.

ECS `listServices` supports the same `MaxResults` and opaque `NextToken` as the
direct ECS API. Lambda mapping tasks currently return an unpaginated list;
`Marker` and `MaxItems` are rejected with `Lambda.InvalidParameterValueException`
instead of being silently ignored. Under IAM enforcement the [ECS](#job-tasks-under-iam-enforcement)
and [Lambda and Secrets Manager](#control-tasks-under-iam-enforcement) ones evaluate the
state-machine execution role's per-action IAM policies. Name-based lookups use the
execution scope; explicit ARNs retain the underlying service's cross-account lookup
behavior. The underlying services' validation still applies. They implement
request-response calls, without job-completion polling.

For example, force a deployment of an existing disposable service:

```json
{
  "StartAt": "Restart",
  "States": {
    "Restart": {
      "Type": "Task",
      "Resource": "arn:aws:states:::aws-sdk:ecs:updateService",
      "Parameters": {"Cluster": "example", "Service": "example", "ForceNewDeployment": true},
      "End": true
    }
  }
}
```

Redshift Data API execution returns a statement `Id` before SQL completes. Follow the
`executeStatement` task with `describeStatement`, a Choice on `Status`, and a Wait state
before polling again. Read results only after `FINISHED`; route `FAILED` and `ABORTED`
to the workflow's failure path. `WithEvent=true` can also route the terminal status
through EventBridge.

Scheduler create and update tasks accept `StartDate` and `EndDate` as RFC 3339 strings, including
offsets and fractional seconds. The direct Scheduler API continues to use numeric epoch seconds.
Structured JSON values supplied as `Target.Input` are serialized once to the Scheduler API's string
field. Textual JSON remains unchanged, and malformed text reaches the existing Scheduler validation.

`sendTaskSuccess` and `sendTaskFailure` resolve a token a `.waitForTaskToken` task is parked on. A
token nobody is waiting for fails the calling task rather than reporting a delivery that never
happened.

`rdsdata:executeStatement` uses the existing RDS Data API implementation. Task arguments use SDK
PascalCase names such as `ResourceArn`, `SecretArn`, `Sql` and `Parameters`; the adapter translates
them to the direct API shape and returns a recursively PascalCase result. Other RDS Data actions are
not routed through Step Functions yet.

Every Redshift Data API action Floci implements is reachable as `aws-sdk:redshiftdata:<action>`
(`executeStatement`, `batchExecuteStatement`, `describeStatement`, `getStatementResult`,
`getStatementResultV2`, `listStatements`, `cancelStatement`, `listDatabases`, `listSchemas`,
`listTables`, `describeTable`). Arguments and results keep the Data API's PascalCase names, and
`CreatedAt` and `UpdatedAt` are ISO-8601 strings. Use `ClusterIdentifier` for a provisioned cluster
or `WorkgroupName` for a Serverless workgroup. A statement that fails in SQL is not a task failure:
`executeStatement` succeeds and `describeStatement` reports `Status: FAILED`, as on AWS. There is no
`.sync` variant, as on AWS.

## Publishing to SNS

`arn:aws:states:::sns:publish` calls the SNS Publish API with the task's parameters and returns the
Publish response, `{MessageId}`. `TopicArn`, `TargetArn`, `PhoneNumber`, `Message`, `Subject`,
`MessageStructure`, `MessageAttributes`, `MessageGroupId` and `MessageDeduplicationId` are the API's
own fields, so a FIFO topic needs a `MessageGroupId` here just as it does from the SDK. A `Message`
given as an object rather than a string is published as its JSON text, which is how a
`.waitForTaskToken` task hands its token to the subscriber:

```json
{
  "Type": "Task",
  "Resource": "arn:aws:states:::sns:publish.waitForTaskToken",
  "Parameters": {
    "TopicArn": "arn:aws:sns:us-east-1:000000000000:myTopic",
    "Message": {
      "Input.$": "$.message",
      "TaskToken.$": "$$.Task.Token"
    }
  },
  "End": true
}
```

A failure names the SDK exception class under the `SNS.` prefix, so a topic that does not exist
fails the task with `SNS.NotFoundException` and a missing `Message` with
`SNS.InvalidParameterException`. `arn:aws:states:::aws-sdk:sns:publish` is the same call under the
`Sns.` prefix, as the AWS SDK integration table above shows.

## Publishing events

`arn:aws:states:::events:putEvents` returns the PutEvents response itself, `Entries` and
`FailedEntryCount`, and one rejected entry fails the whole task with `EventBridge.FailedEntry`. The
cause is the response serialized as a string, so a `Catch` can read which entry was rejected:

```json
{"FailedEntryCount":1,"Entries":[{"EventId":"08cbdc46-…"},{"ErrorCode":"InvalidArgument","ErrorMessage":"Parameter Source is not valid. Reason: Source is a required argument."}]}
```

The optimized integration accepts `Detail` as a JSON object in JSONPath and JSONata workflows. It
serializes that object once for the EventBridge request, preserving nested values and escaped text.
The direct EventBridge API continues to accept its native string-valued `Detail` field.

An entry addressed to an event bus that does not exist is accepted with an `EventId`, as in AWS, and
dropped, so it does not fail the task.

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

A JSONata expression that fails to parse, such as `{% a[1,2) %}`, is refused the same way, with
`INVALID_JSONATA_EXPRESSION` and the parser's own message at the field's location. `$$`, the
reference to the top-level context, is refused under `UNSUPPORTED_JSONATA_EXPRESSION` with the
message `Reference to '$$' is not supported.`; so is `$states.errorOutput` outside the one place it
resolves, a catcher's own `Output` or `Assign`.

A definition is refused outright, with no state machine created, for a graph that never reaches a
terminal state (`MISSING_END_STATE`), for a `StartAt`, `Next`, `Default` or `Catch[].Next` naming a
state absent from its container or a state nothing transitions to (`MISSING_TRANSITION_TARGET`),
and for a field the state type does not carry: `TimeoutSeconds` only on `Task`, and `Catch`/`Retry`
only on `Task`, `Parallel` and `Map`.

## A state's QueryLanguage, and the fields it may carry

A state's query language is JSONPath when its `QueryLanguage` field is exactly the string
`"JSONPath"`, the state machine's when the field is absent, and JSONata for any other value: the
wrong case, an unknown string and a non-string alike. A state inside a `Map`'s `ItemProcessor` or `Iterator`, or
inside one of a `Parallel`'s `Branches`, falls back to the **state machine's** language and not to
the enclosing `Map`'s or `Parallel`'s, which the Amazon States Language calls independent of it. The
enclosing state's own fields, `Items` and `MaxConcurrency` among them, do use its own language.

Three more refusals, each measured against `ValidateStateMachineDefinition` on real AWS:

- A field that belongs to the other query language, at the state's path with no field suffix:
  `The QueryLanguage is set to 'JSONPath', but field 'Output' is only supported for the 'JSONata'
  QueryLanguage`, and the mirror of it for `InputPath`, `OutputPath`, `ResultPath`,
  `ResultSelector`, `Parameters`, `Result`, `ItemsPath` and `MaxConcurrencyPath` on a JSONata
  state. `Assign` belongs to neither list: AWS accepts it on both.
- A state declaring `"QueryLanguage": "JSONPath"` under a `JSONata` state machine:
  `'QueryLanguage' can not be 'JSONPath' if set to 'JSONata' for whole state machine`, again at the
  state's path. A JSONata machine cannot be reverted one state at a time; the upgrade in the other
  direction is allowed. That diagnostic is the whole answer for that state, so the fields its
  refused language forbids are not reported on top of it, while every other check still runs.
- `QueryLanguage` on the `ItemProcessor`, `Iterator` or branch object itself, which is not a state:
  `Field 'QueryLanguage' is not supported` at `/States/M/ItemProcessor`.

A `QueryLanguage` value outside the enum is reported at the field, `/States/X/QueryLanguage` or
`/QueryLanguage`, with `Value should be one of the following: [JSONPath, JSONata]`, and a
non-string value with `Expected value of type [STRING]`. That diagnostic is independent of the
resolution above, so `"jsonpath"` is a JSONata state that also carries it, and it is not a
downgrade: only the exact string `"JSONPath"` under a JSONata machine is.

Locations follow AWS: the two messages above point at the state, `/States/X`, while every other
schema error points at the offending field, `/States/X/MaxConcurrency`.

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

A mocked response with no attempt entries (`{}`) is not rejected. As in Step Functions
Local, the execution starts and fails with `States.Runtime` only if the state that names
it is entered. This keeps a generated mock file usable when the collection it was built
from is empty and the state is never reached.

## HTTP Task request bodies

For `arn:aws:states:::http:invoke`, `Transform.RequestBodyEncoding: URL_ENCODED` sends
an object body as form data. Set `Headers.Content-Type` to
`application/x-www-form-urlencoded`. Nested objects use bracketed keys, and text is
percent-encoded as UTF-8. `RequestEncodingOptions.ArrayFormat` supports `INDICES`
(the default), `REPEAT`, `COMMAS`, and `BRACKETS`, following the
[AWS HTTP Task encoding options](https://docs.aws.amazon.com/step-functions/latest/dg/call-https-apis.html#connect-http-task-url-encoding).
`NONE` keeps the JSON-body path unchanged.

Floci currently supports object roots and scalar `COMMAS` arrays. Non-object roots
(other than an absent or null body) and `COMMAS` arrays containing objects or arrays
fail explicitly instead of being silently coerced. These are Floci limitations,
not documented AWS restrictions; their exact AWS behavior has not been verified.

## HTTP Task connections

The EventBridge connection named by `InvocationConfig.ConnectionArn` or
`Authentication.ConnectionArn` authorizes the request: `BASIC` and `OAUTH_CLIENT_CREDENTIALS`
set the `Authorization` header and `API_KEY` sets the named header, as for an API destination.
The connection's `InvocationHttpParameters` are merged over the task's `Headers`,
`QueryParameters` and `RequestBody`, and the connection's value wins on a duplicate key, as
[AWS merges them](https://docs.aws.amazon.com/step-functions/latest/dg/call-https-apis.html#http-task-data-merge).
Body parameters are merged before `Transform` encodes the body. A `RequestBody` that is not a
JSON object fails the task with `States.Runtime` when the connection has body parameters, and a
connection that does not exist fails it with `Events.ConnectionResource.ResourceNotFound`.

## Executions abandoned by a restart

An execution runs in the Floci process. When Floci restarts while an execution is `RUNNING`,
no worker survives it, so at startup every execution still stored as `RUNNING` is aborted with no
error and no cause, the shape AWS returns for `StopExecution` called without them:
`DescribeExecution` reports `status` `ABORTED` and a `stopDate`, and leaves the `error` and `cause`
keys out. How many executions the sweep retired is reported once, as a `WARN` log line. Executions
of every account are swept, each written back under its own account. Executions that already
reached a terminal status are left untouched, and so is the status and `stopDate` of one this sweep
aborted on an earlier boot.

Execution history is stored with the execution. While an execution is running, the current history
is checkpointed every 100 events and when the execution reaches a terminal state. A graceful
shutdown flushes the current execution state before the emulator stops. On restart, persisted
history is retained, and a previously running execution is marked `ABORTED` with one
`ExecutionAborted` event appended. After a further restart, the execution is already terminal, so
no additional event is written.

## Execution status change events

Floci publishes AWS-shaped `Step Functions Execution Status Change` events to the **default**
EventBridge bus (`source: aws.states`, `resources: [executionArn]`) for every status a STANDARD
execution enters. Rules that match `aws.states`, for example on `detail.stateMachineArn` or
`detail.status`, fire as they do on AWS. EXPRESS executions emit no events, as on AWS.

| `detail.status` | When |
|---|---|
| `RUNNING` | `StartExecution` creates the execution, including a nested `states:startExecution` |
| `SUCCEEDED` | the execution reaches a terminal state without failing |
| `FAILED` | a `Fail` state, an unhandled error, or a runtime error ends the execution |
| `TIMED_OUT` | the state machine's `TimeoutSeconds` elapses |
| `ABORTED` | `StopExecution` stops the execution |

The `detail` carries every key of AWS's documented event, null when it does not apply:
`executionArn`, `stateMachineArn`, `name`, `status`, `startDate` and `stopDate` (epoch
milliseconds, `stopDate` null while `RUNNING`), `input`, `inputDetails`, `output` and
`outputDetails` (set only on `SUCCEEDED`), `stateMachineVersionArn` and `stateMachineAliasArn`
(null unless the execution was started through one), `error`, `cause`, and the redrive fields.

- A `TIMED_OUT` event names `States.Timeout` as its `error`.
- `FAILED` and `TIMED_OUT` report `redriveStatus` `REDRIVABLE` with the reason
  `Execution is <status> and is redrivable`. Every other status reports `NOT_REDRIVABLE` with
  `Execution is <status> and cannot be redriven`.
- As on AWS, when the escaped `input` and escaped `output` together exceed 248 KiB, `input` is
  left out and `inputDetails.included` is `false`. When the escaped `output` alone exceeds
  248 KiB, `output` is left out and `outputDetails.included` is `false`.
  `DescribeExecution` still returns both in full.

Known differences from AWS:

- Executions aborted by the startup sweep described above emit no `ABORTED` event.
- Floci does not implement redrive, so `redriveCount` is always `0` and `redriveDate` is null.

## Partitions

Service integration resources carry the partition of the state machine's region
(`arn:aws-cn:states:::lambda:invoke` in China, as the CDK writes them), and Floci dispatches them
in every partition. State machine and execution ARNs follow the region they are created in. See
[AWS Partitions](../configuration/partitions.md).

## API Gateway tasks

`arn:...:states:::apigateway:invoke` supports regional, same-account REST APIs with `POST`,
`IAM_ROLE`, a named stage and resource path, and an object request body without null values.
Headers accept multiple string values and HTTP token names. AWS-reserved headers are rejected.
Literal reserved names, including `Expect`, `Upgrade`, `Authorization` and `Host`, and the
`X-Amz-`, `X-Amzn-` and `X-Forwarded-` prefixes are rejected case-insensitively when a
definition is created, updated or validated, including JSONata arguments and nested workflows.
An explicit `Content-Length` must equal the serialized body size; the HTTP client writes that header.
Private endpoints, resource-policy authentication, query parameters and `AllowNullValues` remain unsupported.

With IAM enforcement enabled, the execution role must trust Step Functions. The task uses its
`Credentials.RoleArn` when supplied, including assume-role authorization, identity policies,
permissions boundaries and SCPs. With enforcement disabled, missing roles and absent policies do not
block execution. Temporary signing credentials are revoked after each request.

Successful responses preserve their headers and status. JSON response text is parsed regardless of
its content type, matching the live AWS integration; other text remains a string. Non-2xx responses
raise catchable `ApiGateway.<status>` failures.

## Amazon EMR tasks

`arn:...:states:::elasticmapreduce:` serves the optimized EMR integrations the Step Functions guide
lists, request-response for all seven and `.sync` for `createCluster`, `terminateCluster` and
`addStep`. Each one calls Floci's EMR in the state machine's Region, under the guide's field names:

| Resource | EMR calls | Result |
| --- | --- | --- |
| `createCluster` | `RunJobFlow` | `{ClusterId}` |
| `createCluster.sync` | `RunJobFlow`, then `DescribeCluster` until `WAITING` | `Cluster`, then `ClusterId` |
| `setClusterTerminationProtection` | `SetTerminationProtection`, `ClusterId` as `JobFlowIds` | `{}` |
| `terminateCluster` | `TerminateJobFlows`, `ClusterId` as `JobFlowIds` | `{}` |
| `terminateCluster.sync` | the same, then `DescribeCluster` until `TERMINATED` or `TERMINATED_WITH_ERRORS` | `Cluster` |
| `addStep` | `AddJobFlowSteps` with the one `Step`, and `ExecutionRoleArn` when given | `{StepId}` |
| `addStep.sync` | the same, then `DescribeStep` until `COMPLETED` | `Step`, then `StepId` |
| `cancelStep` | `CancelSteps` with the one `StepId` | `{CancelStepsInfo}` |
| `modifyInstanceFleetByName` | `ListInstanceFleets` for the fleet named `InstanceFleetName`, then `ModifyInstanceFleet` | `{}` |
| `modifyInstanceGroupByName` | `ListInstanceGroups` for the group named `InstanceGroupName`, then `ModifyInstanceGroups` | `{}` |

A `.sync` result is the describe call's response with the starting call's result after it. That is
the `createCluster.sync` output recorded on AWS: `Cluster`, the SDK's `SdkHttpMetadata` and
`SdkResponseMetadata`, then `ClusterId`. Floci leaves the SDK metadata out, as it does everywhere.
State machines read an `addStep.sync` result's `Step` (`$.result.Step.Status.State`); its `StepId`,
and `terminateCluster.sync`'s whole result, follow the same rule without a recording of their own.
The timestamps inside are EMR's epoch seconds, which no recording confirms either.

A `.sync` Task records `TaskSubmitted` with the starting call's result, then polls every 100 ms under
the same two clocks as the other `.sync` integrations (see [Timeouts](#timeouts)). A step that ends
`FAILED`, `CANCELLED` or `INTERRUPTED`, and a cluster that terminates before it reaches `WAITING`,
fail the Task with `States.TaskFailed`, the describe response as the cause: a JSON string, which a
`Catch` reads with `States.StringToJson` (for example its `Step.Status.State`). When
the wait ends early, `addStep.sync` cancels its step with `CancelSteps`, as Step Functions "will make
a best-effort attempt to cancel the task". A cluster is never terminated: "Step Functions does not
terminate an Amazon EMR cluster automatically if execution is stopped." EMR in Floci reaches each
state at once, so every wait ends at its first poll.

An error from EMR fails the Task with the SDK exception class under the `EMR.` prefix:
`EMR.InvalidRequestException` and `EMR.InternalServerException` for the two errors EMR models,
`EMR.AmazonElasticMapReduceException` for any other, such as the `ValidationException`
"Specified job flow ID does not exist." that `AddJobFlowSteps` answers for an unknown cluster. The cause is EMR's message; AWS
also appends `(Service: AmazonElasticMapReduce; Status Code: 400; Error Code: ...; Request ID: ...)`.

The integrations refuse what the guide rules out with `States.TaskFailed`, since no source gives
AWS's error; the `createCluster` messages are the guide's own rules, the others Floci's words: a
`createCluster` whose `Instances.KeepJobFlowAliveWhenNoSteps` is not `true` or that carries `Steps`,
an `addStep` without a `Step`, an `InstanceGroupId` or `InstanceFleetId` in a ByName request, and a
name that matches no group or fleet, or more than one. They also refuse a request without the
integration's own fields, before EMR sees it: a `ClusterId` (every API but `createCluster`),
`cancelStep`'s `StepId`, and a ByName request's name. A member EMR itself requires, such as
`TerminationProtected`, is left to EMR, which answers its validation error.
`.waitForTaskToken`, which AWS does not offer for EMR, and `.sync` on any other API fail as an
unsupported resource.

## Amazon EMR Serverless tasks

`arn:...:states:::emr-serverless:` serves the six optimized EMR Serverless integrations the Step
Functions guide lists, each request-response and `.sync`. Each one calls Floci's EMR Serverless in
the state machine's Region:

| Resource | EMR Serverless calls | Result | `.sync` waits until |
| --- | --- | --- | --- |
| `createApplication` | `CreateApplication` | `{ApplicationId, Name, Arn}` | the application is past `CREATING` |
| `startApplication` | `StartApplication` | `{ApplicationId}` | `STARTED` |
| `stopApplication` | `StopApplication` | `{ApplicationId}` | `STOPPED` |
| `deleteApplication` | `DeleteApplication` | `{ApplicationId}` | `TERMINATED` |
| `startJobRun` | `StartJobRun` | `{ApplicationId, JobRunId, Arn}` | the run is `SUCCESS` |
| `cancelJobRun` | `CancelJobRun` | `{ApplicationId, JobRunId}` | the run is `CANCELLED` |

The request is the API's, with its members in PascalCase (`ReleaseLabel`, `JobDriver.SparkSubmit.EntryPoint`,
`ConfigurationOverrides.MonitoringConfiguration`) and the application and run named by `ApplicationId`
and `JobRunId`; the result is the API's response in PascalCase, or `{ApplicationId}` where the API
answers nothing, as the guide's table says. Member names are mapped against botocore's EMR Serverless
model, so a map keeps the keys the state machine wrote: tag keys, `InitialCapacity`'s `DRIVER` and
`EXECUTOR`, and a configuration's `Properties` such as `spark.executor.memory`. A member may be written
in either case, the API's `applicationId` as well as `ApplicationId`. A member the model does not have
is dropped. A request without `ClientToken` gets a random one, as the SDK fills in an
idempotency token; the guide's examples leave it out of requests the API requires it for.

A `.sync` result is the starting call's own result, since the guide finds no difference between
the two responses: state machines read a `createApplication.sync` result's `ApplicationId` and a
`startJobRun.sync` result's `JobRunId`. The Task records `TaskSubmitted` with that result, then
polls `GetApplication` or `GetJobRun` every 100 ms under the same two clocks as the other `.sync`
integrations (see [Timeouts](#timeouts)). A state the wait can no longer end from fails the Task
with `States.TaskFailed` and the `GetApplication` or `GetJobRun` response, in PascalCase, as the
cause: a `startJobRun.sync` run that ends `FAILED` or `CANCELLED`, a `cancelJobRun.sync` run that
ends `SUCCESS` or `FAILED`, an application that is `TERMINATED` before `createApplication.sync` sees
it created, stops or terminates before `startApplication.sync` sees it `STARTED`, or starts or
terminates before `stopApplication.sync` sees it `STOPPED`. No source gives these failures' error or
cause. When the wait ends early, Step Functions "will make a best-effort attempt to cancel the task":
`startJobRun.sync` cancels its run, `startApplication.sync` stops the application and
`createApplication.sync` deletes it, the one call each integration's IAM policy grants beyond its
own and the read it polls with. If EMR Serverless answers that the application or run is gone or past
undoing (`ResourceNotFoundException`, `ValidationException`), the Task ends as it would have; any other
failure fails it. `stopApplication.sync`, `deleteApplication.sync` and
`cancelJobRun.sync` leave the application or run as it is. EMR Serverless in Floci reaches each
application state at once, and a job run's as its configured duration says, so an application's
wait ends at its first poll.

An error from EMR Serverless fails the Task with its code under the `EmrServerless.` prefix, such as
`EmrServerless.ResourceNotFoundException` for an unknown application or
`EmrServerless.ValidationException` "Job run is not in a cancellable state" for a finished run; the
cause is EMR Serverless's message. `EmrServerless` is the exception prefix the guide gives the
service's AWS SDK integration; no record shows the optimized integration's, which differs from the
SDK one for EMR (`EMR.` against `Emr.`). A request without `ApplicationId`, or a `cancelJobRun`
without `JobRunId`, fails with `States.TaskFailed` before EMR Serverless sees it, in Floci's words.
`.waitForTaskToken`, which AWS does not offer for EMR Serverless, and `.sync:2` fail as an
unsupported resource.

## Configuration

| Variable | Default | Description |
|---|---|---|
| `FLOCI_SERVICES_STEPFUNCTIONS_ENABLED` | `true` | Enable or disable the service |
| `FLOCI_SERVICES_STEPFUNCTIONS_MAX_WAIT_SECONDS` | `30` | Ceiling in seconds on a `Wait` state pause and a `Retry` backoff |
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
