# EventBridge Pipes

**Protocol:** REST-JSON
**Endpoint:** `POST http://localhost:4566/`

## Supported Actions

| Action | Description |
|---|---|
| `CreatePipe` | Create a new pipe with source, target, and optional enrichment |
| `DescribePipe` | Get pipe details including state and configuration |
| `UpdatePipe` | Update pipe configuration (source, target, role, enrichment, desired state) |
| `DeletePipe` | Delete a pipe |
| `ListPipes` | List all pipes with optional filtering by state and prefix |
| `StartPipe` | Start a stopped pipe |
| `StopPipe` | Stop a running pipe |

## Configuration

| Variable | Default | Description |
|---|---|---|
| `FLOCI_SERVICES_PIPES_ENABLED` | `true` | Enable or disable the service |

## Examples

```bash
export AWS_ENDPOINT_URL=http://localhost:4566

# Create a pipe (SQS to Lambda)
aws pipes create-pipe \
  --name my-pipe \
  --source "arn:aws:sqs:us-east-1:000000000000:source-queue" \
  --target "arn:aws:lambda:us-east-1:000000000000:function:my-function" \
  --role-arn "arn:aws:iam::000000000000:role/pipe-role" \
  --endpoint-url $AWS_ENDPOINT_URL

# Describe a pipe
aws pipes describe-pipe \
  --name my-pipe \
  --endpoint-url $AWS_ENDPOINT_URL

# List all pipes
aws pipes list-pipes \
  --endpoint-url $AWS_ENDPOINT_URL

# Start a pipe
aws pipes start-pipe \
  --name my-pipe \
  --endpoint-url $AWS_ENDPOINT_URL

# Stop a pipe
aws pipes stop-pipe \
  --name my-pipe \
  --endpoint-url $AWS_ENDPOINT_URL

# Update a pipe
aws pipes update-pipe \
  --name my-pipe \
  --target "arn:aws:lambda:us-east-1:000000000000:function:new-function" \
  --endpoint-url $AWS_ENDPOINT_URL

# Delete a pipe
aws pipes delete-pipe \
  --name my-pipe \
  --endpoint-url $AWS_ENDPOINT_URL
```

## Pipe States

- `STARTING` - Pipe is being started
- `RUNNING` - Pipe is actively processing events
- `STOPPING` - Pipe is being stopped
- `STOPPED` - Pipe is stopped and not processing events
- `DELETED` - Pipe has been deleted

## Supported Sources and Targets

Floci emulates EventBridge Pipes with the following supported source and target types:

**Sources:**
- Amazon SQS queues
- Amazon Kinesis streams
- Amazon DynamoDB streams
- Kafka topics (MSK and self-managed via `smk://`)

**Targets:**
- Lambda functions
- SQS queues
- SNS topics
- Kinesis streams
- Step Functions state machines
