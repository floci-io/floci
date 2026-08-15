# AWS Elemental MediaLive

**Protocol:** REST-JSON (camelCase wire fields, `/prod` stage prefix on every path)
**Endpoint:** `http://localhost:4566/prod/multiplexes`, ... (SigV4 service `medialive`)

## Supported Actions

| Action | Description |
|---|---|
| `CreateMultiplex` | Create a multiplex; IDLE immediately |
| `DescribeMultiplex` | Get multiplex details by id |
| `DeleteMultiplex` | Delete a multiplex; it stays readable in state DELETED |
| `CreateMultiplexProgram` | Create a program in a multiplex |
| `DescribeMultiplexProgram` | Get a program by multiplex id and program name |
| `DeleteMultiplexProgram` | Delete a program |
| `ListTagsForResource` | List tags for a MediaLive resource ARN (`/prod/tags/{arn}`) |
| `CreateTags` | Tag a MediaLive resource |
| `DeleteTags` | Remove tags from a MediaLive resource |

Channels, inputs and the video transport data plane are not emulated. A
multiplex is IDLE as soon as a create returns, so SDK and Terraform waiters
complete on their first poll; a deleted multiplex stays readable in state
DELETED because the SDK's MultiplexDeleted waiter polls DescribeMultiplex for
that state.

## Configuration

| Variable | Default | Description |
|---|---|---|
| `FLOCI_SERVICES_MEDIALIVE_ENABLED` | `true` | Enable or disable the service |

## Examples

```bash
export AWS_ENDPOINT_URL=http://localhost:4566

aws medialive create-multiplex \
  --name my-multiplex \
  --availability-zones us-east-1a us-east-1b \
  --multiplex-settings transportStreamBitrate=1000000,transportStreamId=1 \
  --request-id demo-1

aws medialive describe-multiplex --multiplex-id <id>
```
