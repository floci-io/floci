# QuickSight

QuickSight uses the AWS REST JSON wire protocol and the `quicksight` signing scope.
Resources use account-aware storage and region-specific, partition-aware ARNs.
Every management request requires `AwsAccountId` to match the caller's account,
including requests made with Floci's default local credentials. With IAM enforcement
enabled, management actions use the shared REST route and request-resource tables
to evaluate identity policies.
This is a metadata-only subset for local SDK integration tests.

## Supported operations

<!-- floci:actions:start -->
| Action | Description |
| --- | --- |
| `SearchDashboards` | Searches directly shared dashboards with scoped pagination |
| `CreateDashboard` | Creates a dashboard from a definition and stored dataset references |
| `DescribeDashboard` | Reads dashboard metadata and its published version |
| `DeleteDashboard` | Deletes a dashboard |
| `CreateDataSet` | Stores physical-table dataset metadata |
| `DescribeDataSet` | Reads dataset metadata |
| `DeleteDataSet` | Deletes a dataset and its ingestions |
| `CreateDataSource` | Stores data-source metadata |
| `DescribeDataSource` | Reads data-source metadata |
| `DeleteDataSource` | Deletes a data source |
| `RegisterUser` | Registers a QuickSight-native reader, author or administrator |
| `DescribeUser` | Reads a registered user |
| `CreateIngestion` | Creates a completed metadata-only SPICE ingestion |
| `ListIngestions` | Lists ingestion records newest first with scoped pagination |
| `DescribeIngestion` | Reads an ingestion record |
| `GenerateEmbedUrlForRegisteredUser` | Issues a short-lived URL for a shared dashboard |
<!-- floci:actions:end -->

## Configuration

`FLOCI_SERVICES_QUICKSIGHT_ENABLED` enables the service, default `true`.
`FLOCI_SERVICES_QUICKSIGHT_EMBED_BASE_URL` sets the public embedding origin, falling
back to `floci.base-url`. Use HTTPS for cookie-backed embedding sessions.
When Floci runs behind a TLS proxy, forward `/embed/*` to Floci and set this variable
to the proxy's HTTPS origin. Preserve `Referer` and `Cookie` headers.

## Local embedding

Dashboard URLs use `/embed/{session}/dashboards/{id}?code={bearer}` and resolve the
persisted dashboard after checking the registered user's direct sharing permissions.
The bearer expires after five minutes and can be redeemed once. Subsequent loads
require the secure session cookie; session lifetime is 15 to 600 minutes. Requested
allowed origins are checked against the embedding page's referrer. Host names and
schemes are lowercased and default ports are omitted when origins are stored or
compared. `frame-ancestors` includes every session allowed origin so nested frames
on those origins can embed the dashboard. Allowed domains are exact HTTPS origins
or HTTP localhost, with one to three entries; wildcard domains and a configured
domain allowlist are not implemented.

When QuickSight is enabled, a background sweep removes expired unredeemed URLs and
redeemed sessions at startup and every sixty seconds, including when idle. The
worker pauses for emulator reset and stops before storage is flushed at shutdown.
Treat embed URLs as credentials and do not put them in logs or shared reports.

## Scope

This subset supports local application integration against stored service resources.
It does not execute SQL, fetch S3 data or render BI visualizations. Ingestions model
metadata transitions synchronously and report zero imported rows. Dashboard embedding
shows the stored dashboard's title. Embedding-SDK control iframes, interactive
commands and QuickSight postMessage event processing are not implemented.
Console analysis authoring, templates, federated registration, group-based dashboard
sharing, creation-tag IAM conditions, role passing, dataset transforms and scheduling
are not implemented.
QuickSight-native users become active immediately; there is no invitation flow.
Search requires a registered user in the addressed account and region and enforces
direct user sharing in addition to IAM policy checks when enforcement is enabled.
Denied management requests return `AccessDeniedException` with HTTP 401; an embed
session lifetime outside 15 to 600 minutes returns
`SessionLifetimeInMinutesInvalidException` with HTTP 400.

Timestamp fields use epoch seconds as defined by the QuickSight REST JSON model and
are verified by strict AWS SDK clients. `SearchDashboards` and `ListIngestions` validate
limits of 1 to 100 and scope continuation tokens to account, region and query identity.

## AWS references

- [QuickSight API operations](https://docs.aws.amazon.com/quicksight/latest/APIReference/API_Operations.html)
- [SearchDashboards](https://docs.aws.amazon.com/quicksight/latest/APIReference/API_SearchDashboards.html)
- [GenerateEmbedUrlForRegisteredUser](https://docs.aws.amazon.com/quicksight/latest/APIReference/API_GenerateEmbedUrlForRegisteredUser.html)
