# EventBridge Scheduler

**Protocol:** REST JSON
**Endpoint:** `http://localhost:4566/`

## Supported Actions

| Action | Method | Path | Description |
|---|---|---|---|
| `CreateScheduleGroup` | `POST` | `/schedule-groups/{Name}` | Create a schedule group |
| `GetScheduleGroup` | `GET` | `/schedule-groups/{Name}` | Get schedule group details |
| `DeleteScheduleGroup` | `DELETE` | `/schedule-groups/{Name}` | Delete a schedule group and its schedules |
| `ListScheduleGroups` | `GET` | `/schedule-groups` | List schedule groups |
| `CreateSchedule` | `POST` | `/schedules/{Name}` | Create a schedule |
| `GetSchedule` | `GET` | `/schedules/{Name}` | Get schedule details |
| `UpdateSchedule` | `PUT` | `/schedules/{Name}` | Update a schedule |
| `DeleteSchedule` | `DELETE` | `/schedules/{Name}` | Delete a schedule |
| `ListSchedules` | `GET` | `/schedules` | List schedules |

## Not Yet Supported

- `TagResource` / `UntagResource` / `ListTagsForResource`
- Schedule invocation (triggering targets on schedule)
- `NextToken`-based pagination for List operations

## Examples

```bash
export AWS_ENDPOINT=http://localhost:4566

# Create a schedule group
aws scheduler create-schedule-group \
  --name my-group \
  --endpoint-url $AWS_ENDPOINT

# List schedule groups
aws scheduler list-schedule-groups \
  --endpoint-url $AWS_ENDPOINT

# Create a schedule in the default group
aws scheduler create-schedule \
  --name my-schedule \
  --schedule-expression "rate(1 hour)" \
  --flexible-time-window '{"Mode":"OFF"}' \
  --target '{
    "Arn": "arn:aws:lambda:us-east-1:000000000000:function:my-func",
    "RoleArn": "arn:aws:iam::000000000000:role/scheduler-role"
  }' \
  --endpoint-url $AWS_ENDPOINT

# Create a schedule with retry policy and dead-letter queue
aws scheduler create-schedule \
  --name my-resilient-schedule \
  --schedule-expression "rate(5 minutes)" \
  --flexible-time-window '{"Mode":"FLEXIBLE","MaximumWindowInMinutes":10}' \
  --target '{
    "Arn": "arn:aws:sqs:us-east-1:000000000000:my-queue",
    "RoleArn": "arn:aws:iam::000000000000:role/scheduler-role",
    "RetryPolicy": {"MaximumEventAgeInSeconds":3600,"MaximumRetryAttempts":5},
    "DeadLetterConfig": {"Arn":"arn:aws:sqs:us-east-1:000000000000:my-dlq"}
  }' \
  --endpoint-url $AWS_ENDPOINT

# Get a schedule
aws scheduler get-schedule \
  --name my-schedule \
  --endpoint-url $AWS_ENDPOINT

# Update a schedule
aws scheduler update-schedule \
  --name my-schedule \
  --schedule-expression "rate(30 minutes)" \
  --flexible-time-window '{"Mode":"OFF"}' \
  --target '{
    "Arn": "arn:aws:lambda:us-east-1:000000000000:function:my-func",
    "RoleArn": "arn:aws:iam::000000000000:role/scheduler-role"
  }' \
  --state DISABLED \
  --endpoint-url $AWS_ENDPOINT

# Delete a schedule
aws scheduler delete-schedule \
  --name my-schedule \
  --endpoint-url $AWS_ENDPOINT

# Delete a schedule group (cascades to all schedules in the group)
aws scheduler delete-schedule-group \
  --name my-group \
  --endpoint-url $AWS_ENDPOINT
```

## Default Schedule Group

A `default` schedule group is automatically created on first access. Schedules created without specifying a group are placed in the default group. The default group cannot be deleted.
