# Amazon Managed Service for Prometheus (AMP)

**Protocol:** REST JSON
**Endpoint:** `http://localhost:4566`

Floci implements the AMP **workspace** and **rule groups namespace** lifecycles — the surface
Terraform's and Pulumi's `aws_prometheus_workspace` and `aws_prometheus_rule_group_namespace`
resources use — plus tagging through the shared `/tags/{resourceArn}` dispatcher. Alert manager
definitions, scrapers and logging configurations are not implemented.

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
- A rule groups namespace is **ACTIVE from birth** for the same reason, so the provider's
  create (`CREATING` → `ACTIVE`) and update (`UPDATING` → `ACTIVE`) waiters complete on their
  first poll.
- The `data` blob is stored and returned byte for byte. Floci does not parse or validate the
  Prometheus rules it contains, so an invalid rule file is accepted.
- Rule groups namespaces live inside their workspace: `DeleteWorkspace` deletes the namespaces
  it holds, and every namespace operation on an unknown workspace returns
  `ResourceNotFoundException` (404).
- Creating a namespace whose name already exists in the workspace returns `ConflictException`
  (409). `PutRuleGroupsNamespace` requires an existing namespace and returns
  `ResourceNotFoundException` (404) otherwise.
- The `name` parameter of `ListRuleGroupsNamespaces` is a **prefix** filter, like `alias` on
  `ListWorkspaces`.
- Namespace names are validated against AMP's documented constraints (1 to 128 characters
  matching `.*[0-9A-Za-z][-.0-9A-Z_a-z]*.*`). Floci additionally rejects `/`, because the
  namespace is addressed as a single path segment.
- `clientToken` on `CreateRuleGroupsNamespace` is accepted and ignored, the same as on
  `CreateWorkspace`: creates are not deduplicated.
- The shared tags dispatcher rejects an ARN whose service, region or account does not match the
  request with `ValidationException` (400), rather than resolving it against the request's own
  region.

## Supported Operations

| Operation | Method and path | Description |
|---|---|---|
| `CreateWorkspace` | `POST /workspaces` | Creates a workspace (`202`); returns `workspaceId`, `arn`, `status`, `tags` |
| `DescribeWorkspace` | `GET /workspaces/{workspaceId}` | Returns the workspace description including `prometheusEndpoint` |
| `ListWorkspaces` | `GET /workspaces` | Lists workspaces; `alias` prefix filter, `maxResults`/`nextToken` pagination |
| `UpdateWorkspaceAlias` | `POST /workspaces/{workspaceId}/alias` | Updates the alias (`204`) |
| `DeleteWorkspace` | `DELETE /workspaces/{workspaceId}` | Deletes the workspace and its rule groups namespaces (`202`) |
| `CreateRuleGroupsNamespace` | `POST /workspaces/{workspaceId}/rulegroupsnamespaces` | Creates a namespace (`202`); returns `name`, `arn`, `status`, `tags` |
| `DescribeRuleGroupsNamespace` | `GET /workspaces/{workspaceId}/rulegroupsnamespaces/{name}` | Returns the namespace description including `data` |
| `ListRuleGroupsNamespaces` | `GET /workspaces/{workspaceId}/rulegroupsnamespaces` | Lists namespaces; `name` prefix filter, `maxResults`/`nextToken` pagination |
| `PutRuleGroupsNamespace` | `PUT /workspaces/{workspaceId}/rulegroupsnamespaces/{name}` | Replaces the namespace `data` (`202`) |
| `DeleteRuleGroupsNamespace` | `DELETE /workspaces/{workspaceId}/rulegroupsnamespaces/{name}` | Deletes the namespace (`202`) |
| `ListTagsForResource` | `GET /tags/{resourceArn}` | Lists workspace or namespace tags (shared tags dispatcher) |
| `TagResource` | `POST /tags/{resourceArn}` | Adds or overwrites workspace or namespace tags (`200`) |
| `UntagResource` | `DELETE /tags/{resourceArn}?tagKeys=...` | Removes workspace or namespace tags (`200`) |
