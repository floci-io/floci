# Amazon Managed Service for Prometheus (AMP)

**Protocol:** REST-JSON
**Endpoint:** `http://localhost:4566/workspaces`, `/scrapers` (SigV4 service `aps`)

## Supported Actions

| Action | Description |
|---|---|
| `CreateWorkspace` | Create a workspace; returns ACTIVE immediately |
| `DescribeWorkspace` | Get workspace details including status and Prometheus endpoint |
| `ListWorkspaces` | List workspaces, optionally filtered by alias |
| `DeleteWorkspace` | Delete a workspace |
| `CreateAlertManagerDefinition` | Attach an alert manager definition to a workspace |
| `PutAlertManagerDefinition` | Replace a workspace's alert manager definition |
| `DescribeAlertManagerDefinition` | Get a workspace's alert manager definition |
| `DeleteAlertManagerDefinition` | Remove a workspace's alert manager definition |
| `CreateQueryLoggingConfiguration` | Attach a query logging configuration to a workspace |
| `UpdateQueryLoggingConfiguration` | Replace a workspace's query logging configuration |
| `DescribeQueryLoggingConfiguration` | Get a workspace's query logging configuration |
| `DeleteQueryLoggingConfiguration` | Remove a workspace's query logging configuration |
| `CreateScraper` | Create a scraper; returns ACTIVE immediately |
| `DescribeScraper` | Get scraper details |
| `ListScrapers` | List scrapers |
| `DeleteScraper` | Delete a scraper |
| `UpdateScraperLoggingConfiguration` | Set a scraper's logging configuration |
| `DescribeScraperLoggingConfiguration` | Get a scraper's logging configuration |
| `DeleteScraperLoggingConfiguration` | Remove a scraper's logging configuration |
| `ListTagsForResource` | List tags for a workspace or scraper ARN |
| `TagResource` | Tag a workspace or scraper |
| `UntagResource` | Remove tags from a workspace or scraper |

Statuses are `ACTIVE` as soon as a create returns, so SDK and Terraform waiters
complete on their first poll. The workspace `prometheusEndpoint` points at the
emulator base URL; remote-write and query data planes are not emulated.

## Configuration

| Variable | Default | Description |
|---|---|---|
| `FLOCI_SERVICES_AMP_ENABLED` | `true` | Enable or disable the service |

## Examples

```bash
export AWS_ENDPOINT_URL=http://localhost:4566

aws amp create-workspace --alias my-workspace

aws amp describe-workspace --workspace-id ws-...

aws amp delete-workspace --workspace-id ws-...
```
