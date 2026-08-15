# AWS Elemental MediaPackage V2

**Protocol:** REST-JSON (PascalCase wire fields)
**Endpoint:** `http://localhost:4566/channelGroup`, ... (SigV4 service `mediapackagev2`)

## Supported Actions

| Action | Description |
|---|---|
| `CreateChannelGroup` | Create a channel group; returns a plausible egress domain |
| `GetChannelGroup` | Get channel group details by name |
| `DeleteChannelGroup` | Delete a channel group |
| `ListTagsForResource` | List tags for a MediaPackage V2 resource ARN |
| `TagResource` | Tag a MediaPackage V2 resource |
| `UntagResource` | Remove tags from a MediaPackage V2 resource |

Channels, origin endpoints and the packaging data plane are not emulated. One
wire quirk is reproduced faithfully: the service's own model names the tag map
`Tags` in create responses but `tags` in get responses.

## Configuration

| Variable | Default | Description |
|---|---|---|
| `FLOCI_SERVICES_MEDIAPACKAGEV2_ENABLED` | `true` | Enable or disable the service |

## Examples

```bash
export AWS_ENDPOINT_URL=http://localhost:4566

aws mediapackagev2 create-channel-group --channel-group-name my-group

aws mediapackagev2 get-channel-group --channel-group-name my-group
```
