# Amazon AppIntegrations

**Protocol:** REST-JSON
**Endpoint:** `http://localhost:4566/eventIntegrations`, `/dataIntegrations`
(SigV4 service `app-integrations`)

## Supported Actions

| Action | Description |
|---|---|
| `CreateEventIntegration` | Create an event integration; returns its ARN |
| `GetEventIntegration` | Get an event integration by name, including tags |
| `UpdateEventIntegration` | Change an event integration's description |
| `DeleteEventIntegration` | Delete an event integration |
| `ListEventIntegrations` | List event integrations |
| `ListEventIntegrationAssociations` | List an event integration's client associations |
| `CreateDataIntegration` | Create a data integration; returns the full resource |
| `GetDataIntegration` | Get a data integration by id or ARN |
| `UpdateDataIntegration` | Change a data integration's name or description |
| `DeleteDataIntegration` | Delete a data integration |
| `ListDataIntegrations` | List data integration summaries |
| `TagResource` | Tag either integration type (`POST /tags/{resourceArn}`) |
| `UntagResource` | Remove tags (`DELETE /tags/{resourceArn}?tagKeys=`) |
| `ListTagsForResource` | List tags (`GET /tags/{resourceArn}`) |

Event integrations are addressed by name and get the ARN
`arn:aws:app-integrations:<region>:<account>:event-integration/<name>`. Data
integrations get a generated id and the ARN
`arn:aws:app-integrations:<region>:<account>:data-integration/<id>`; `GetDataIntegration`,
`UpdateDataIntegration` and `DeleteDataIntegration` accept either the id or that ARN.

`EventBridgeBus`, `EventFilter.Source`, `Description` and `Tags` round-trip through
`GetEventIntegration`, which is what `aws_appintegrations_event_integration` reads.
Note that `CreateDataIntegration` takes the schedule under `ScheduleConfig` and reads
it back as `ScheduleConfiguration`, matching the AWS model.

`ListEventIntegrationAssociations` returns an empty list for an existing integration
and `ResourceNotFoundException` for a missing one. Associations are created by the
consuming service (Amazon Connect and friends) when it binds a client to the
integration; Floci has no path that creates one, so an empty list is the true state
rather than a placeholder.

## Not implemented

These return a clean `UnknownOperationException` rather than a stub success:

- `CreateDataIntegrationAssociation`, `UpdateDataIntegrationAssociation`,
  `ListDataIntegrationAssociations` — an association binds a data integration to a
  live client execution, which the emulator has no way to model.
- `CreateApplication`, `GetApplication`, `UpdateApplication`, `DeleteApplication`,
  `ListApplications`, `ListApplicationAssociations` — the third-party application
  surface is not yet modelled.

## Configuration

| Variable | Default | Description |
|---|---|---|
| `FLOCI_SERVICES_APPINTEGRATIONS_ENABLED` | `true` | Enable or disable the service |

## Examples

```bash
export AWS_ENDPOINT_URL=http://localhost:4566

aws appintegrations create-event-integration \
  --name my-integration \
  --event-bridge-bus my-bus \
  --event-filter Source=aws.partner/example.com/1234

aws appintegrations get-event-integration --name my-integration

aws appintegrations create-data-integration \
  --name my-data-integration \
  --kms-key arn:aws:kms:us-east-1:000000000000:key/abc \
  --source-uri "Salesforce://AppFlow/test"

aws appintegrations list-data-integrations

aws appintegrations delete-event-integration --name my-integration
```
