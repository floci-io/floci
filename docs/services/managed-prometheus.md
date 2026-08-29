# Amazon Managed Service for Prometheus (AMP)

**Protocol:** REST JSON
**Endpoint:** `http://localhost:4566`

Floci implements the AMP **workspace** lifecycle — the surface Terraform's and Pulumi's
`aws_prometheus_workspace` resource uses — plus tagging through the shared
`/tags/{resourceArn}` dispatcher. Alert manager definitions, rule groups namespaces, scrapers
and logging configurations are not implemented.

## Emulation notes

- A workspace is **ACTIVE from birth**. Real AMP answers the create `202` with status
  `CREATING`; Floci provisions nothing, so `DescribeWorkspace` reports `ACTIVE` immediately and
  the Terraform provider's create waiter (`CREATING` → `ACTIVE`) completes on its first poll.
- `DeleteWorkspace` removes the workspace immediately; a subsequent `DescribeWorkspace` returns
  `ResourceNotFoundException` (404), which the provider's delete waiter treats as gone.
- `prometheusEndpoint` is reported in the real AWS shape
  (`https://aps-workspaces.<region>.amazonaws.com/workspaces/<id>/`). It is not a live
  ingestion/query endpoint.
- The `alias` parameter of `ListWorkspaces` is a **prefix** filter, matching the provider's
  `alias_prefix` data-source argument.
- `clientToken` on `CreateWorkspace` is accepted and ignored: creates are not deduplicated.
- `kmsKeyArn` is stored and echoed back but no encryption is performed.

## Supported Operations

| Operation | Method and path | Description |
|---|---|---|
| `CreateWorkspace` | `POST /workspaces` | Creates a workspace (`202`); returns `workspaceId`, `arn`, `status`, `tags` |
| `DescribeWorkspace` | `GET /workspaces/{workspaceId}` | Returns the workspace description including `prometheusEndpoint` |
| `ListWorkspaces` | `GET /workspaces` | Lists workspaces; `alias` prefix filter, `maxResults`/`nextToken` pagination |
| `UpdateWorkspaceAlias` | `POST /workspaces/{workspaceId}/alias` | Updates the alias (`204`) |
| `DeleteWorkspace` | `DELETE /workspaces/{workspaceId}` | Deletes the workspace (`202`) |
| `ListTagsForResource` | `GET /tags/{resourceArn}` | Lists workspace tags (shared tags dispatcher) |
| `TagResource` | `POST /tags/{resourceArn}` | Adds or overwrites workspace tags (`200`) |
| `UntagResource` | `DELETE /tags/{resourceArn}?tagKeys=...` | Removes workspace tags (`200`) |
