# Lake Formation

The `lakeformation` service allows you to manage data lake settings, resources, permissions, and LF-Tags, serving as the permission layer over the Glue Data Catalog.

## Configuration

| Key | Default | Description |
|---|---|---|
| `floci.services.lakeformation.enabled` | `true` | Enable or disable the Lake Formation service. |
| `floci.storage.services.lakeformation.mode` | *inherited* | Storage backend for Lake Formation settings and permissions: `memory`, `persistent`. |
| `floci.storage.services.lakeformation.flush-interval-ms` | `5000` | How often to flush state to disk when using persistent storage. |

## Supported Operations

### Data Lake Settings
*   `PutDataLakeSettings`
*   `GetDataLakeSettings`

### Resource Registration
*   `RegisterResource`
*   `UpdateResource`
*   `DeregisterResource`
*   `ListResources`
*   `DescribeResource`

### Permissions
*   `GrantPermissions`
*   `RevokePermissions`
*   `ListPermissions`

### LF-Tags
*   `CreateLFTag`
*   `GetLFTag`
*   `UpdateLFTag`
*   `DeleteLFTag`
*   `ListLFTags`
*   `AddLFTagsToResource`
*   `RemoveLFTagsFromResource`

## Implementation Details
Floci currently supports basic CRUD operations for Lake Formation resources, permissions, and tags. This state is persisted and, apart from the Redshift Spectrum enforcement below, not consulted by other data-plane emulators.

**Redshift Spectrum enforcement.** Set `floci.services.redshift.spectrum-lake-formation-enforcement=true` (`FLOCI_SERVICES_REDSHIFT_SPECTRUM_LAKE_FORMATION_ENFORCEMENT`, default `false`) to make Spectrum queries honor the grants stored here. The IAM role of the external schema must hold `SELECT` (or `ALL`) on the table, on a `TableWildcard` of its database, or on a column wildcard without exclusions, or the query fails with SQLSTATE `42501`. A grant to `IAM_ALLOWED_PRINCIPALS` leaves the table to IAM, and so does a catalog with no data lake settings stored or whose `CreateTableDefaultPermissions` names `IAM_ALLOWED_PRINCIPALS`; store settings with that list empty to hand tables to Lake Formation. A schema without a Glue database (the legacy catalog) is refused. Column grants, LF-Tags and data cells filters are not evaluated. A local view over an external table is not followed, so only a query that names the table is checked. See [Redshift](redshift.md).

Permissions retrieval via `ListPermissions` currently lists all explicitly granted permissions to satisfy Terraform state expectations without strict caller permission filtering.
