# Amazon Connect

**Protocol:** REST-JSON
**Endpoint:** `http://localhost:4566/instance` (SigV4 service `connect`)

## Supported Actions

<!-- floci:actions:start -->
| Action | Description |
| --- | --- |
| `CreateInstance` | Create an instance; returns `Id` and `Arn`, ACTIVE immediately |
| `DescribeInstance` | Get the full instance including tags |
| `DeleteInstance` | Delete an instance and its storage config associations |
| `ListInstances` | List instance summaries |
| `UpdateInstanceAttribute` | Set one `InstanceAttributeType` |
| `DescribeInstanceAttribute` | Read one `InstanceAttributeType` |
| `ListInstanceAttributes` | List every attribute on an instance |
| `AssociateInstanceStorageConfig` | Attach a storage config for a resource type |
| `DescribeInstanceStorageConfig` | Read a storage config by association id |
| `UpdateInstanceStorageConfig` | Replace a storage config |
| `DisassociateInstanceStorageConfig` | Remove a storage config |
| `ListInstanceStorageConfigs` | List storage configs for one resource type |
| `ListTagsForResource` | List instance tags (`GET /tags/{resourceArn}`) |
| `TagResource` | Tag an instance (`POST /tags/{resourceArn}`) |
| `UntagResource` | Remove instance tags (`DELETE /tags/{resourceArn}?tagKeys=`) |
<!-- floci:actions:end -->

`InstanceStatus` is `ACTIVE` from the first read, so `aws_connect_instance`'s status
poll completes without a transition. `CreateInstance` returns only `Id` and `Arn` —
the full `Instance` shape, tags included, comes back from `DescribeInstance`.
`InstanceSummary` has no `Tags` member in the AWS model, so `ListInstances` omits it.

Every `InstanceAttributeType` in the AWS model is seeded when an instance is created:
`INBOUND_CALLS` and `OUTBOUND_CALLS` take their values from the create request, the
rest default to `false`. This keeps `DescribeInstanceAttribute` from returning a
404 for a valid attribute type, which `aws_connect_instance` reads on every refresh.

Storage configs are keyed by instance, resource type and association id, so the same
instance can hold separate configurations for `CHAT_TRANSCRIPTS`, `CALL_RECORDINGS`
and the rest. `ResourceType` is required on every read and write, as AWS requires it.

Both a bare instance id and a full instance ARN are accepted wherever the API takes
an `InstanceId`.

## Not implemented

These return a clean `UnknownOperationException` rather than a stub success. The
telephony and contact surfaces of Connect depend on real voice, chat and agent
traffic, which the emulator has no way to model:

- Phone numbers (`ClaimPhoneNumber`, `SearchAvailablePhoneNumbers`, ...) — these
  allocate real numbers from carrier inventory.
- Contacts (`StartOutboundVoiceContact`, `StopContact`, `GetContactAttributes`, ...)
  — these require a live contact.
- Agent status, user hierarchy and routing profiles bound to live agents.
- Real-time and historical metrics (`GetCurrentMetricData`, `GetMetricDataV2`, ...) —
  these aggregate real traffic.
- Queues, hours of operation, contact flows and quick connects — not yet modelled.

## Configuration

| Variable | Default | Description |
|---|---|---|
| `FLOCI_SERVICES_CONNECT_ENABLED` | `true` | Enable or disable the service |

## Examples

```bash
export AWS_ENDPOINT_URL=http://localhost:4566

aws connect create-instance \
  --identity-management-type CONNECT_MANAGED \
  --instance-alias my-contact-center \
  --no-inbound-calls-enabled \
  --outbound-calls-enabled

aws connect describe-instance --instance-id <id>

aws connect update-instance-attribute \
  --instance-id <id> --attribute-type CONTACTFLOW_LOGS --value true

aws connect associate-instance-storage-config \
  --instance-id <id> --resource-type CHAT_TRANSCRIPTS \
  --storage-config '{"StorageType":"S3","S3Config":{"BucketName":"b","BucketPrefix":"chat/"}}'

aws connect delete-instance --instance-id <id>
```
