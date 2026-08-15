# Amazon IVS Chat

**Protocol:** REST-JSON (RPC-style paths: `POST /{OperationName}`)
**Endpoint:** `http://localhost:4566/CreateRoom`, ... (SigV4 service `ivschat`)

## Supported Actions

| Action | Description |
|---|---|
| `CreateRoom` | Create a chat room |
| `GetRoom` | Get room details by ARN or id |
| `DeleteRoom` | Delete a room |
| `CreateLoggingConfiguration` | Create a logging configuration; ACTIVE immediately |
| `GetLoggingConfiguration` | Get a logging configuration by ARN or id |
| `DeleteLoggingConfiguration` | Delete a logging configuration |
| `ListTagsForResource` | List tags for an IVS Chat resource ARN |
| `TagResource` | Tag an IVS Chat resource |
| `UntagResource` | Remove tags from an IVS Chat resource |

The chat message data plane (CreateChatToken, SendEvent, ...) is not emulated.
Logging configurations are ACTIVE as soon as a create returns, so SDK and
Terraform waiters complete on their first poll.

## Configuration

| Variable | Default | Description |
|---|---|---|
| `FLOCI_SERVICES_IVSCHAT_ENABLED` | `true` | Enable or disable the service |

## Examples

```bash
export AWS_ENDPOINT_URL=http://localhost:4566

aws ivschat create-room --name my-room

aws ivschat get-room --identifier arn:aws:ivschat:us-east-1:000000000000:room/...
```
