# AWS Elemental MediaPackage

**Protocol:** REST-JSON (camelCase wire fields)
**Endpoint:** `http://localhost:4566/channels`, ... (SigV4 service `mediapackage`)

## Supported Actions

| Action | Description |
|---|---|
| `CreateChannel` | Create a channel; returns two plausible HLS ingest endpoints |
| `DescribeChannel` | Get channel details by id |
| `UpdateChannel` | Update a channel's description |
| `DeleteChannel` | Delete a channel |
| `ListTagsForResource` | List tags for a MediaPackage resource ARN |
| `TagResource` | Tag a MediaPackage resource |
| `UntagResource` | Remove tags from a MediaPackage resource |

Origin endpoints, harvest jobs and the packaging data plane are not emulated.
The HLS ingest endpoints are plausible but non-functional.

## Configuration

| Variable | Default | Description |
|---|---|---|
| `FLOCI_SERVICES_MEDIAPACKAGE_ENABLED` | `true` | Enable or disable the service |

## Examples

```bash
export AWS_ENDPOINT_URL=http://localhost:4566

aws mediapackage create-channel --id my-channel

aws mediapackage describe-channel --id my-channel
```
