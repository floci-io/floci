# SWF

**Protocol:** JSON 1.0 (`X-Amz-Target: SimpleWorkflowService.*`)
**Endpoint:** `POST http://localhost:4566/`

Domains, workflow and activity type registration, and the workflow execution state
machine: decision tasks, activity tasks, timers, signals, child workflows, and timeouts.

## Supported Actions

<!-- floci:actions:start -->
| Action | Description |
| --- | --- |
| `RegisterDomain` | Create a domain with a workflow-execution retention period |
| `DescribeDomain` | Get a domain's status, description, ARN, and retention configuration |
| `ListDomains` | List domains by registration status |
| `DeprecateDomain` | Deprecate a domain, blocking new registrations and executions |
| `UndeprecateDomain` | Return a deprecated domain to REGISTERED |
| `RegisterWorkflowType` | Register a workflow type and its execution defaults |
| `DescribeWorkflowType` | Get a workflow type's status and registered defaults |
| `ListWorkflowTypes` | List workflow types by registration status, optionally by name |
| `DeprecateWorkflowType` | Deprecate a workflow type, blocking new executions |
| `UndeprecateWorkflowType` | Return a deprecated workflow type to REGISTERED |
| `DeleteWorkflowType` | Delete a deprecated workflow type |
| `RegisterActivityType` | Register an activity type and its task timeout defaults |
| `DescribeActivityType` | Get an activity type's status and registered defaults |
| `ListActivityTypes` | List activity types by registration status, optionally by name |
| `DeprecateActivityType` | Deprecate an activity type, blocking new task scheduling |
| `UndeprecateActivityType` | Return a deprecated activity type to REGISTERED |
| `DeleteActivityType` | Delete a deprecated activity type |
| `StartWorkflowExecution` | Start an execution, resolving unset fields from the type defaults |
| `DescribeWorkflowExecution` | Get an execution's status, configuration, and open task counts |
| `GetWorkflowExecutionHistory` | Read an execution's history, paginated and optionally reversed |
| `ListOpenWorkflowExecutions` | List open executions with execution, type, tag, and time filters |
| `ListClosedWorkflowExecutions` | List closed executions, additionally filtered by close status |
| `CountOpenWorkflowExecutions` | Count open executions matching the same filters |
| `CountClosedWorkflowExecutions` | Count closed executions matching the same filters |
| `CountPendingActivityTasks` | Count activity tasks awaiting a worker on a task list |
| `CountPendingDecisionTasks` | Count decision tasks awaiting a decider on a task list |
| `PollForDecisionTask` | Claim a decision task with the execution history the decider needs |
| `RespondDecisionTaskCompleted` | Apply a decider's decisions in order |
| `PollForActivityTask` | Claim an activity task with its input |
| `RecordActivityTaskHeartbeat` | Report progress and learn whether cancellation was requested |
| `RespondActivityTaskCompleted` | Complete an activity task with a result |
| `RespondActivityTaskFailed` | Fail an activity task with a reason and details |
| `RespondActivityTaskCanceled` | Confirm an activity task was canceled |
| `SignalWorkflowExecution` | Deliver a signal to an open execution |
| `RequestCancelWorkflowExecution` | Ask an execution's decider to cancel it |
| `TerminateWorkflowExecution` | Close an execution immediately, applying its child policy |
| `ListTagsForResource` | List a domain's tags |
| `TagResource` | Add tags to a domain |
| `UntagResource` | Remove tags from a domain |
<!-- floci:actions:end -->

All 39 modeled operations are implemented.

## Decisions

`RespondDecisionTaskCompleted` applies decisions in order:

| Decision | Behavior |
|----------|----------|
| `ScheduleActivityTask` | Schedules an activity task; timeouts fall back to the activity type's registration defaults |
| `RequestCancelActivityTask` | Requests cancellation; a task that has not started yet is canceled immediately |
| `CompleteWorkflowExecution` | Closes the execution as `COMPLETED` |
| `FailWorkflowExecution` | Closes the execution as `FAILED` |
| `CancelWorkflowExecution` | Closes the execution as `CANCELED` |
| `ContinueAsNewWorkflowExecution` | Closes the run as `CONTINUED_AS_NEW` and starts a successor run |
| `RecordMarker` | Appends `MarkerRecorded` |
| `StartTimer` / `CancelTimer` | Starts or cancels a timer; a due timer fires `TimerFired` |
| `SignalExternalWorkflowExecution` | Delivers a signal to another execution in the same domain |
| `RequestCancelExternalWorkflowExecution` | Requests cancellation of another execution |
| `StartChildWorkflowExecution` | Starts a child execution and reports its outcome back to the parent |
| `ScheduleLambdaFunction` | Recorded as `ScheduleLambdaFunctionFailed` with cause `LAMBDA_SERVICE_NOT_AVAILABLE_IN_REGION` (see [Limitations](#limitations)) |

A decision that cannot be applied appends its `*Failed` event with the AWS cause
(`ACTIVITY_TYPE_DOES_NOT_EXIST`, `ACTIVITY_ID_ALREADY_IN_USE`, `TIMER_ID_UNKNOWN`,
`UNHANDLED_DECISION`, …) instead of failing the request, and the decider is given a
fresh decision task. Decisions that follow a closing decision are dropped.

## Timeouts

A background sweep expires timeouts and appends the matching event:

| Timeout | Event | `timeoutType` |
|---------|-------|---------------|
| Activity schedule-to-start | `ActivityTaskTimedOut` | `SCHEDULE_TO_START` |
| Activity start-to-close | `ActivityTaskTimedOut` | `START_TO_CLOSE` |
| Activity schedule-to-close | `ActivityTaskTimedOut` | `SCHEDULE_TO_CLOSE` |
| Activity heartbeat | `ActivityTaskTimedOut` | `HEARTBEAT` |
| Decision task start-to-close | `DecisionTaskTimedOut` | `START_TO_CLOSE` |
| Workflow execution start-to-close | `WorkflowExecutionTimedOut` | `START_TO_CLOSE` |

Any timeout other than the workflow's own schedules a new decision task. The literal
`NONE` disables a timeout, as in AWS.

## Child policy

When an execution closes, its `childPolicy` is applied to still-open children:
`TERMINATE` terminates them with cause `CHILD_POLICY_APPLIED`, `REQUEST_CANCEL` requests
cancellation, and `ABANDON` leaves them running.

## Configuration

```yaml
floci:
  services:
    swf:
      enabled: true
      timeout-sweep-enabled: true
      timeout-sweep-interval-seconds: 1
```

| Setting | Env var | Default | Description |
|---------|---------|---------|-------------|
| `enabled` | `FLOCI_SERVICES_SWF_ENABLED` | `true` | Enable the service |
| `timeout-sweep-enabled` | `FLOCI_SERVICES_SWF_TIMEOUT_SWEEP_ENABLED` | `true` | Run the background timeout sweep |
| `timeout-sweep-interval-seconds` | `FLOCI_SERVICES_SWF_TIMEOUT_SWEEP_INTERVAL_SECONDS` | `1` | Sweep interval |

## Example

Register a domain and the types an execution needs:

```bash
aws --endpoint-url http://localhost:4566 swf register-domain \
  --name orders --workflow-execution-retention-period-in-days 7

aws --endpoint-url http://localhost:4566 swf register-workflow-type \
  --domain orders --name OrderWorkflow --workflow-version 1.0 \
  --default-task-list name=orders-tl \
  --default-task-start-to-close-timeout 60 \
  --default-execution-start-to-close-timeout 3600 \
  --default-child-policy TERMINATE

aws --endpoint-url http://localhost:4566 swf register-activity-type \
  --domain orders --name ChargeCard --activity-version 1.0 \
  --default-task-list name=orders-tl \
  --default-task-schedule-to-start-timeout 60 \
  --default-task-start-to-close-timeout 300
```

Start an execution:

```bash
aws --endpoint-url http://localhost:4566 swf start-workflow-execution \
  --domain orders --workflow-id order-1234 \
  --workflow-type name=OrderWorkflow,version=1.0 \
  --input '{"orderId":"1234"}'
```

Poll as a decider and schedule the activity:

```bash
aws --endpoint-url http://localhost:4566 swf poll-for-decision-task \
  --domain orders --task-list name=orders-tl --identity decider-1 > task.json

TASK_TOKEN=$(jq -r .taskToken task.json)

cat > decisions.json <<'EOF'
[{"decisionType": "ScheduleActivityTask",
  "scheduleActivityTaskDecisionAttributes": {
    "activityId": "charge-1",
    "activityType": {"name": "ChargeCard", "version": "1.0"},
    "input": "{\"amount\":4200}"}}]
EOF

aws --endpoint-url http://localhost:4566 swf respond-decision-task-completed \
  --task-token "$TASK_TOKEN" --decisions file://decisions.json
```

Poll as a worker and complete the activity:

```bash
aws --endpoint-url http://localhost:4566 swf poll-for-activity-task \
  --domain orders --task-list name=orders-tl --identity worker-1 > activity.json

ACTIVITY_TOKEN=$(jq -r .taskToken activity.json)

aws --endpoint-url http://localhost:4566 swf respond-activity-task-completed \
  --task-token "$ACTIVITY_TOKEN" --result '{"charged":true}'
```

Read the history:

```bash
aws --endpoint-url http://localhost:4566 swf get-workflow-execution-history \
  --domain orders --execution workflowId=order-1234,runId=<runId>
```

## Errors

Faults use the AWS codes and messages, so SDK error handling works unchanged:

| Fault | Raised by |
|-------|-----------|
| `UnknownResourceFault` | Unknown domain, type, execution, or task token |
| `DomainAlreadyExistsFault` | `RegisterDomain` for an existing domain; `UndeprecateDomain` on a registered domain |
| `DomainDeprecatedFault` | Registering into, or starting an execution in, a deprecated domain |
| `TypeAlreadyExistsFault` | Registering an existing type; undeprecating a registered type |
| `TypeDeprecatedFault` | Starting an execution of a deprecated workflow type |
| `TypeNotDeprecatedFault` | `DeleteWorkflowType` / `DeleteActivityType` before deprecation |
| `WorkflowExecutionAlreadyStartedFault` | `StartWorkflowExecution` while an execution with that `workflowId` is open |
| `DefaultUndefinedFault` | A required field is absent from both the request and the type's defaults |
| `TooManyTagsFault` | More than 50 tags on a domain |
| `ValidationException` | Missing required member or a value outside an enum |

## Limitations

- **Long polling.** `PollForDecisionTask` and `PollForActivityTask` return immediately
  instead of holding the connection for up to 60 seconds. Workers that loop on an empty
  poll behave the same; workers that rely on the call blocking will spin.
- **`ScheduleLambdaFunction`.** Lambda-backed activities are recorded as
  `ScheduleLambdaFunctionFailed` rather than invoking a function, so a decider that uses
  them keeps making progress instead of waiting for an event that never arrives.
- **Task tokens are in-memory.** Tokens do not survive a restart even under persistent
  storage modes, matching the fact that SWF tokens are not durable handles.
- **Retention.** `workflowExecutionRetentionPeriodInDays` is stored and returned but
  closed executions are not pruned.
- **Pagination.** `maximumPageSize` and `nextPageToken` are honored; tokens are opaque
  offsets and are not interchangeable with real SWF tokens.
