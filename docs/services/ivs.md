# Amazon IVS

**Protocol:** REST-JSON (RPC-style paths: `POST /{OperationName}`)
**Endpoint:** `http://localhost:4566/CreateChannel`, ... (SigV4 service `ivs`)

## Supported Actions

| Action | Description |
|---|---|
| `CreateChannel` | Create a channel; returns the channel and its stream key |
| `GetChannel` | Get channel details by ARN |
| `DeleteChannel` | Delete a channel |
| `ImportPlaybackKeyPair` | Import a playback public key; returns a computed fingerprint |
| `GetPlaybackKeyPair` | Get a playback key pair by ARN |
| `DeletePlaybackKeyPair` | Delete a playback key pair |
| `CreateRecordingConfiguration` | Create a recording configuration; ACTIVE immediately |
| `GetRecordingConfiguration` | Get a recording configuration by ARN |
| `DeleteRecordingConfiguration` | Delete a recording configuration |
| `ListTagsForResource` | List tags for an IVS resource ARN |
| `TagResource` | Tag an IVS resource |
| `UntagResource` | Remove tags from an IVS resource |

Ingest endpoints and playback URLs are plausible but non-functional; the video
data plane is not emulated. Recording configurations are ACTIVE as soon as a
create returns, so SDK and Terraform waiters complete on their first poll.

## Configuration

| Variable | Default | Description |
|---|---|---|
| `FLOCI_SERVICES_IVS_ENABLED` | `true` | Enable or disable the service |

## Examples

```bash
export AWS_ENDPOINT_URL=http://localhost:4566

aws ivs create-channel --name my-channel

aws ivs get-channel --arn arn:aws:ivs:us-east-1:000000000000:channel/...
```
