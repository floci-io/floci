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
Floci currently supports basic CRUD operations for Lake Formation resources, permissions, and tags. This state is strictly persisted without deep integration into other data-plane emulators. 

Permissions retrieval via `ListPermissions` currently lists all explicitly granted permissions to satisfy Terraform state expectations without strict caller permission filtering.
