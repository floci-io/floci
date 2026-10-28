# RDS

**Protocol:** Query (XML) for management API + PostgreSQL / MySQL / SQL Server wire protocol for data plane
**Management Endpoint:** `POST http://localhost:4566/`
**Data Endpoint:** `localhost:<proxy-port>` (TCP)

### Recoverable backend startup failures

If a persisted database's container or authentication relay cannot start when Floci restarts,
Floci retains the database record, data volume, and reserved endpoint port and retries when its
backend is next needed. A failed restore does not release or renumber a reserved endpoint; a retry
or the next boot uses the same port.
This recoverable emulator condition uses `available`, the same metadata-only status as a
restart without a reachable Docker daemon, not AWS's terminal `failed` state. It does not
guarantee that a database connection is ready while Docker is unavailable. Port allocation
failures still report `failed` because no endpoint could be allocated.

A successful lazy retry updates legacy `failed` records to `available` only after the container
and relay are ready. Older records whose endpoint was cleared receive a new relay endpoint on
retry. A failed retry leaves their status unchanged and cleans up the attempted backend and
any newly allocated relay port.

Instance and cluster start operations, and cluster reboot, reconstruct a missing relay endpoint
through the same retry path.
Retrying a cluster directly, or resolving its ARN for a Data API request, also restores its missing
member relays without restarting a healthy cluster backend or rebinding members that already
recovered. Data API cluster-ARN resolution checks for missing members even when the cluster backend
is already running, so a member relay that fails once is retried on the next request. Stopped
members remain stopped until a control-plane start operation.
When the cluster and all active members have backends, Data API resolution skips the synchronized
recovery path, so healthy queries do not wait for unrelated container starts or image pulls.
Lazy cluster recovery tries every active member even if one relay fails; a failed member does
not prevent Data API access through a recovered cluster backend, and repeated member failures are
logged at debug level. Start and reboot report member relay errors after trying all members and
mark those members `failed` until a successful retry.

Floci manages real PostgreSQL, MySQL, MariaDB, and SQL Server Docker containers and proxies TCP connections to them, including IAM authentication support where the protocol supports it. SQL Server uses a transparent TCP relay for its native TDS protocol.

MySQL and MariaDB master-grant script uploads, and PostgreSQL snapshot restore archive uploads,
retry transient Docker connection failures up to six attempts with exponential backoff starting
at 500 ms. Every attempt sends a fresh archive. Containers start, or snapshot restore commands
run, only after the upload succeeds. A permanent Docker error or exhausted retries follows the
existing failure path. The PostgreSQL restore SQL readiness retry policy is unchanged.

RDS Data API (`rds-data`) is documented separately because it uses REST JSON routes instead of the RDS Query protocol. See [RDS Data API](rds-data.md).

## Supported Management Actions

<!-- floci:actions:start -->
| Action | Description |
| --- | --- |
| `CreateDBInstance` | Start a new database instance |
| `DescribeDBInstances` | List instances and their connection info — the list form includes DocumentDB and Neptune instances and takes an `engine` filter |
| `DeleteDBInstance` | Stop and remove an instance |
| `ModifyDBInstance` | Update instance settings |
| `RebootDBInstance` | Restart a database instance |
| `StopDBInstance` | Stop a standalone instance temporarily: an optional `DBSnapshotIdentifier` first, then its container goes away while the record, endpoint and volume stay |
| `StartDBInstance` | Start a stopped instance on the volume it kept, same endpoint |
| `StopDBCluster` | Stop a cluster and its member instances |
| `StartDBCluster` | Start a stopped cluster and its members |
| `RebootDBCluster` | Restart a cluster's database and its members' proxies |
| `CreateDBInstanceReadReplica` | Create a read replica of a PostgreSQL instance, initialised from a copy of the source; see [Read replicas](#read-replicas) |
| `PromoteReadReplica` | Detach a read replica into a standalone instance and turn automated backups on |
| `SwitchoverReadReplica` | Refused with `InvalidDBInstanceState`: AWS supports switchover only for Oracle and SQL Server replicas, neither of which is emulated |
| `PromoteReadReplicaDBCluster` | Refused with `InvalidDBClusterStateFault`: no cluster is created as a replica of an instance |
| `DescribeDBEngineVersions` | List the engine catalogue's versions with their parameter group family, default flag and upgrade targets |
| `DescribeEngineDefaultParameters` | Return a known family's default parameters (an empty list, as Floci's default groups hold none) |
| `DescribeEngineDefaultClusterParameters` | Return a known family's default cluster parameters (an empty list, as for instances) |
| `DescribeValidDBInstanceModifications` | Return the storage an instance can move to: its type and the sizes a modify stores as asked |
| `DescribeOrderableDBInstanceOptions` | List the orderable engine, version and instance class combinations from the engine catalogue; see [Engine catalogue](#engine-catalogue) |
| `DescribeEvents` | - |
| `DescribeCertificates` | List the Region's RDS certificate authorities and the one new instances get; see [Certificate authorities](#certificate-authorities) |
| `ModifyCertificates` | Set or remove the account's default certificate authority for new instances |
| `CreateEventSubscription` | Create an event notification subscription (stored, nothing is published) |
| `DescribeEventSubscriptions` | List subscriptions, or the one the request names |
| `ModifyEventSubscription` | Update the members the request names |
| `DeleteEventSubscription` | Delete a subscription |
| `CreateDBClusterEndpoint` | Create an Aurora custom endpoint (`READER` or `ANY`) with a static or exclusion member list; see [Cluster endpoints](#cluster-endpoints) |
| `DescribeDBClusterEndpoints` | List a cluster's built-in writer and reader endpoints and its custom endpoints, with the four documented filters |
| `ModifyDBClusterEndpoint` | Change a custom endpoint's type or member list |
| `DeleteDBClusterEndpoint` | Delete a custom endpoint |
| `EnableHttpEndpoint` | Turn on an Aurora cluster's Data API endpoint by ARN; see [Data API endpoint](#data-api-endpoint) |
| `DisableHttpEndpoint` | Turn off a cluster's Data API endpoint by ARN |
| `DescribeDBLogFiles` | List an instance's log files, read from its database container; see [Log files](#log-files) |
| `DownloadDBLogFilePortion` | Read a block of a log file, from the start or the most recent lines |
| `AddRoleToDBCluster` | Associate an IAM role with a cluster, optionally for a feature; see [IAM role associations](#iam-role-associations) |
| `RemoveRoleFromDBCluster` | Remove a role from a cluster |
| `AddRoleToDBInstance` | Associate an IAM role with an instance for a feature |
| `RemoveRoleFromDBInstance` | Remove a role from an instance |
| `AddSourceIdentifierToSubscription` | Add a source id to a subscription |
| `RemoveSourceIdentifierFromSubscription` | Remove a source id from a subscription |
| `CreateDBSubnetGroup` | Create a DB subnet group; tags given here are readable through `ListTagsForResource` |
| `DescribeDBSubnetGroups` | List DB subnet groups |
| `ModifyDBSubnetGroup` | Update DB subnet group description and subnet list |
| `DeleteDBSubnetGroup` | Delete a DB subnet group |
| `CreateDBCluster` | Create an Aurora-compatible cluster |
| `DescribeDBClusters` | List clusters — the list form covers the RDS family, DocumentDB and Neptune clusters included, and takes an `engine` filter |
| `DeleteDBCluster` | Delete a cluster |
| `ModifyDBCluster` | Update cluster settings |
| `CreateDBParameterGroup` | Create a parameter group |
| `DescribeDBParameterGroups` | List parameter groups |
| `DeleteDBParameterGroup` | Delete a parameter group |
| `ModifyDBParameterGroup` | Update parameter group settings |
| `CopyDBParameterGroup` | Copy a parameter group (by name or ARN) with its family and parameter overrides into a new group |
| `ResetDBParameterGroup` | Reset named parameters, or all of them, to engine defaults |
| `DescribeDBParameters` | List parameters in a group |
| `CreateDBClusterParameterGroup` | Create an Aurora-compatible cluster parameter group |
| `DescribeDBClusterParameterGroups` | List cluster parameter groups |
| `DeleteDBClusterParameterGroup` | Delete a cluster parameter group |
| `ModifyDBClusterParameterGroup` | Update cluster parameter group settings |
| `CopyDBClusterParameterGroup` | Copy a cluster parameter group into a new group; a managed `default.*` group cannot be copied, as on AWS |
| `ResetDBClusterParameterGroup` | Reset named cluster parameters, or all of them, to engine defaults |
| `DescribeDBClusterParameters` | List parameters in a cluster group |
| `CreateOptionGroup` | Create an option group |
| `CopyOptionGroup` | Copy an option group with its engine, major version and options into a new group |
| `DescribeOptionGroups` | List option groups, including the implicit `default:` groups |
| `ModifyOptionGroup` | Add, update, or remove options in an option group |
| `DeleteOptionGroup` | Delete an option group |
| `CreateDBSnapshot` | Create a snapshot of a DB instance |
| `DeleteDBSnapshot` | Delete an available manual DB snapshot and its saved data |
| `CopyDBSnapshot` | Copy an available DB snapshot, optionally copying tags or overriding the option group and KMS key |
| `ModifyDBSnapshot` | Change the engine version or option group of an available manual DB snapshot |
| `RestoreDBInstanceFromDBSnapshot` | Create a new DB instance from a snapshot |
| `RestoreDBInstanceToPointInTime` | Create a new DB instance from a source instance at a time in its restorable window; see [Point in time restore](#point-in-time-restore) |
| `DescribeDBSnapshots` | List DB instance snapshots |
| `DescribeDBSnapshotAttributes` | Return a snapshot's `restore` attribute (accounts authorized to copy/restore it) |
| `ModifyDBSnapshotAttribute` | Add or remove accounts authorized to copy/restore a snapshot |
| `DescribeDBProxies` | List DB proxies |
| `CreateDBProxy` | Create a DB proxy |
| `ModifyDBProxy` | Update mutable DB proxy authentication, logging, timeout, TLS, role, and security-group settings |
| `DeleteDBProxy` | Delete a DB proxy and its endpoints |
| `CreateDBProxyEndpoint` | Add a read/write or read-only endpoint to a DB proxy; see the proxy note below |
| `DescribeDBProxyEndpoints` | List proxy endpoints, each proxy's default endpoint first, filtered by `DBProxyName` and `DBProxyEndpointName` |
| `ModifyDBProxyEndpoint` | Rename a proxy endpoint or replace its security groups |
| `DeleteDBProxyEndpoint` | Delete a proxy endpoint |
| `RegisterDBProxyTargets` | Register a cluster or instance as a proxy target |
| `DeregisterDBProxyTargets` | Remove a cluster or instance from a proxy target group |
| `DescribeDBProxyTargetGroups` | List a proxy's target groups |
| `ModifyDBProxyTargetGroup` | Update target-group connection-pool configuration |
| `DescribeDBProxyTargets` | List a proxy target group's registered targets |
| `DescribeDBClusterSnapshots` | List cluster snapshots, filtered by `DBClusterSnapshotIdentifier`, `DBClusterIdentifier` and `SnapshotType` |
| `CreateDBClusterSnapshot` | Take a manual snapshot of an available cluster, with its data and `Tags` |
| `DeleteDBClusterSnapshot` | Delete an available cluster snapshot and its data; the response carries `Status` `deleted` |
| `CopyDBClusterSnapshot` | Copy an available cluster snapshot (by identifier or same-region ARN) to a new manual one with its data; `CopyTags` and `Tags`; the copy reports `SourceDBClusterSnapshotArn` |
| `RestoreDBClusterFromSnapshot` | Create a cluster from a cluster snapshot's settings and data; `Engine` must match the snapshot's |
| `RestoreDBClusterToPointInTime` | Create a cluster from a source cluster at a time in its restorable window; see [Point in time restore](#point-in-time-restore) |
| `DescribeDBClusterSnapshotAttributes` | Return the `restore` attribute of a cluster snapshot |
| `ModifyDBClusterSnapshotAttribute` | Add or remove `restore` values (account ids or `all`) on a cluster snapshot |
| `StartExportTask` | Start an export task for a DB snapshot or DB cluster snapshot; see [Exporting snapshots to Amazon S3](#exporting-snapshots-to-amazon-s3) |
| `DescribeExportTasks` | List export tasks, by `ExportTaskIdentifier`, `SourceArn`, `SourceType` and the `export-task-identifier`, `s3-bucket`, `source-arn` and `status` filters |
| `CancelExportTask` | Cancel a running export task; what it already wrote stays in the bucket |
| `DescribeGlobalClusters` | List the account's global clusters with their primary and secondary members; see [Global clusters](#global-clusters) |
| `CreateGlobalCluster` | Create an Aurora global database, empty or with an existing Aurora cluster as its primary |
| `ModifyGlobalCluster` | Rename a global cluster, set deletion protection, or upgrade its engine version (members follow) |
| `DeleteGlobalCluster` | Delete a global cluster once every member has been removed and deletion protection is off |
| `RemoveFromGlobalCluster` | Detach a member into a standalone cluster; the primary goes last |
| `FailoverGlobalCluster` | Promote a secondary to primary (failover with `AllowDataLoss`, or switchover) |
| `SwitchoverGlobalCluster` | Promote a secondary to primary and demote the current primary to a secondary |
| `FailoverDBCluster` | Move a DB cluster's writer role to a reader instance, the named one or the first |
| `AddTagsToResource` | Add tags to a DB resource |
| `ListTagsForResource` | List tags for a DB resource |
| `RemoveTagsFromResource` | Remove tags from a DB resource |
<!-- floci:actions:end -->

`CreateDBInstance` stores `StorageEncrypted`, `KmsKeyId`, `BackupRetentionPeriod`,
`PreferredBackupWindow`, `PreferredMaintenanceWindow` and `CopyTagsToSnapshot`, and
`DescribeDBInstances` returns them; `ModifyDBInstance` changes the backup settings and
the windows. The same checks as on AWS apply (`KmsKeyId` needs `StorageEncrypted`,
windows are at least 30 minutes and may not overlap). `KmsKeyId` is accepted as a key ARN,
key id, alias ARN or alias name, resolved against the KMS store in the request's region and
returned as the key ARN; a key that does not exist or is not enabled is
`KMSKeyNotAccessibleFault`. Where AWS picks a random window,
Floci uses `04:00-06:00` and `mon:00:00-mon:03:00` (or, when the window given on create overlaps the
usual default, a 30-minute window starting where the given one ends); a window given on modify is
checked against the instance's other window. Modifications apply immediately —
`PendingModifiedValues` is not modeled.

!!! note "Stopping and starting"

    `StopDBInstance` and `StopDBCluster` follow the user guide: the response reports `stopping`
    (`StopDBCluster` for the cluster and its members), the stored status settles to `stopped`,
    and `StartDBInstance` / `StartDBCluster` report `starting` and settle to `available`. While
    stopped, the identifier, endpoint (same port), parameter and option groups and the Docker
    volume all stay, so the data comes back on start; the container itself is removed and the
    endpoint refuses connections. `StopDBInstance` refuses a cluster member (use `StopDBCluster`),
    a read replica or an instance that has one, and anything not `available`, with
    `InvalidDBInstanceState`; `ModifyDBInstance` on a stopped instance is refused the same way.
    `DeleteDBInstance` works on a stopped instance. A stopped instance or cluster stays stopped
    across an emulator restart. Not modeled: the automatic restart after seven days, and the
    Multi-AZ SQL Server restriction.

!!! note "DB snapshot tagging and lifecycle"

    `CreateDBSnapshot` accepts `Tags`, and `TagResource`/`UntagResource`/`ListTagsForResource`
    work against a snapshot's ARN like they do for other tagged resource types.
    `DescribeDBSnapshotAttributes`/`ModifyDBSnapshotAttribute` are modeled as plain in-memory
    state (no real cross-account sharing). Available manual snapshots can be deleted, copied by
    identifier or ARN, and modified. Copies retain the source data and can copy source tags or add
    request tags. Snapshots are region-scoped like DB instances and clusters: `DBSnapshotArn` reflects
    the request's signed region, and a snapshot is only visible to `Describe`/`Tag` calls signed
    for that same region. Cluster snapshots follow the same lifecycle: `CreateDBClusterSnapshot`
    dumps the cluster's database, `RestoreDBClusterFromSnapshot` loads it into a new cluster, and
    `Copy`, `Delete` and the `restore` attribute behave as they do for instance snapshots, under
    `arn:aws:rds:<region>:<account>:cluster-snapshot:<name>`. RDS reserved instances aren't
    modeled (there's no reserved-instance API), so tagging doesn't apply to them.

## Cluster parameter groups

`DescribeDBClusterParameterGroups` includes `DBClusterParameterGroupArn` for managed `default.*`
groups as well as customer-created groups, in both named and list responses. Managed default ARNs
use the request's account, region, and partition with the `cluster-pg:<group-name>` resource form.

## Boolean parameters

The following parameters accept `true` or `false` (case-insensitive). Other values,
including empty strings, return `InvalidParameterValue` before changing resources.

| Actions | Parameters | When omitted |
|---|---|---|
| `CreateDBProxy` | `RequireTLS`, `DebugLogging` | Defaults to `false` |
| `ModifyDBProxy` | `RequireTLS`, `DebugLogging` | Preserves the current value |
| `ResetDBParameterGroup`, `ResetDBClusterParameterGroup` | `ResetAllParameters` | Defaults to `false`; named parameter resets keep their existing behavior |
| `CopyDBClusterSnapshot` | `CopyTags` | Defaults to `false`; explicit request tags still apply |
| `CreateEventSubscription` | `Enabled` | Defaults to `true` |
| `ModifyEventSubscription` | `Enabled` | Preserves the current value |
| `RestoreDBInstanceFromDBSnapshot` | `MultiAZ`, `EnableIAMDatabaseAuthentication` | Defaults to `false`, regardless of the snapshot setting |
| `RestoreDBClusterFromSnapshot`, `RestoreDBClusterToPointInTime` | `EnableIAMDatabaseAuthentication` | Defaults to `false`, regardless of the source setting |

Malformed reset flags leave saved parameter overrides unchanged. Malformed event-subscription
flags leave the subscription and its topic, source type and event categories unchanged.
Malformed restore flags are rejected before starting an instance or cluster restore.


## Event notification subscriptions

The four subscription actions manage the resource itself. **Nothing is published to the topic.**
A subscription is stored and reported back so a client can manage it. No RDS event reaches SNS
through it, so an SNS subscriber sees nothing.

`SourceIds` is set at create and changed only through `AddSourceIdentifierToSubscription` and
`RemoveSourceIdentifierFromSubscription`, since `ModifyEventSubscription` carries no `SourceIds`
member. Adding an id twice is a no-op, because the model declares no fault that fits a duplicate.

`SnsTopicArn` is required and is not resolved against the SNS service. `SourceType` is checked
against the model's valid values, and a request naming `SourceIds` must also name the `SourceType`
they belong to, as the model requires. An omitted `Enabled` activates the subscription.

## Configuration

| Variable | Default | Description |
|---|---|---|
| `FLOCI_SERVICES_RDS_ENABLED` | `true` | Enable or disable the service |
| `FLOCI_SERVICES_RDS_MOCK` | `false` | `true` = metadata only (no Docker container or auth proxy) |
| `FLOCI_SERVICES_RDS_PROXY_BASE_PORT` | `7001` | First host port in the RDS proxy range |
| `FLOCI_SERVICES_RDS_PROXY_MAX_PORT` | `7099` | Last host port in the RDS proxy range |
| `FLOCI_SERVICES_RDS_ENDPOINT_HOST` | _(auto-detected)_ | Hostname advertised in RDS endpoints; when set in Docker, Floci advertises each proxy's published host port |
| `FLOCI_SERVICES_RDS_IAM_TOKEN_ENDPOINT_BINDING` | `true` | Refuse a PostgreSQL IAM auth token generated for another hostname, port or region than the endpoint publishes, as RDS does; set `false` to accept such tokens. MySQL and MariaDB always refuse them |
| `FLOCI_SERVICES_RDS_DEFAULT_POSTGRES_IMAGE` | `postgres:16-alpine` | Docker image for PostgreSQL instances |
| `FLOCI_SERVICES_RDS_DEFAULT_MYSQL_IMAGE` | `mysql:8.0` | Docker image for MySQL instances |
| `FLOCI_SERVICES_RDS_DEFAULT_MARIADB_IMAGE` | `mariadb:11` | Docker image for MariaDB instances |
| `FLOCI_SERVICES_RDS_DEFAULT_SQL_SERVER_IMAGE` | `mcr.microsoft.com/mssql/server:2022-latest` | Docker image for SQL Server instances |
| `FLOCI_SERVICES_RDS_PROXY_HANDSHAKE_TIMEOUT_MILLIS` | `10000` | Max time a client has to complete the startup/auth handshake before the proxy drops it |
| `FLOCI_SERVICES_RDS_PROXY_BACKEND_CONNECT_TIMEOUT_MILLIS` | `5000` | Max time the proxy waits for the backend TCP connect |
| `FLOCI_SERVICES_RDS_PROXY_MAX_CONNECTIONS` | `100` | Max concurrent connections per proxy before new ones are refused |
| `FLOCI_SERVICES_RDS_AURORA_AUTO_PAUSE_ENABLED` | `true` | Pause an Aurora Serverless v2 cluster whose `MinCapacity` is 0 after `SecondsUntilAutoPause` without connections; see [Automatic pause and resume](#automatic-pause-and-resume) |
| `FLOCI_SERVICES_RDS_AURORA_RESUME_DELAY_MILLIS` | `0` | How long the first connection to a paused cluster is held while it resumes; Aurora takes about `15000` |

### Docker Compose

RDS requires the Docker socket and port range exposure. For private registry authentication and other Docker settings see [Docker Configuration](../configuration/docker.md).

`CreateDBInstance`, `CreateDBCluster`, `RestoreDBInstanceFromDBSnapshot`,
`RestoreDBClusterFromSnapshot` and the two point in time restores honor a requested `Port` only within the configured
RDS proxy range. A port outside that range causes Floci to assign the next free port
and return it in the endpoint. A port inside the range that is already in use is
rejected. This differs from AWS because Floci's local proxy must use a published
host port; for example, requesting `5432` with the default
`7001`-`7099` range returns a port in that range. Ports outside AWS's valid
`1150`-`65535` interval are rejected.

When Docker publishes RDS proxy ports dynamically, set `FLOCI_SERVICES_RDS_ENDPOINT_HOST` to the
hostname used by clients. Floci inspects its own container through the Docker socket and returns the
corresponding published port from `DescribeDBInstances` and `DescribeDBClusters`. Leave the setting
unset to retain the auto-detected endpoint host and configured proxy port.

```yaml
services:
  floci:
    image: floci/floci:latest
    ports:
      - "4566:4566"
      - "7001-7099:7001-7099"   # RDS proxy ports
    volumes:
      - /var/run/docker.sock:/var/run/docker.sock
    environment:
      FLOCI_SERVICES_DOCKER_NETWORK: my-project_default
      FLOCI_SERVICES_RDS_PROXY_BASE_PORT: "7001"
```

### Without a reachable Docker daemon

A DB instance or cluster record is metadata. Its identifier, ARN, endpoint address and tags come
from Floci's configuration, not from Docker. When no daemon is reachable, because Floci runs inside
Docker with no socket mounted or the daemon on the host is stopped, `CreateDBInstance` and
`CreateDBCluster` still succeed and the resource reaches `available`. `DescribeDBInstances`,
`ModifyDBInstance`, the tagging APIs and `DeleteDBInstance` all work on that record, and Floci logs
a warning naming the missing daemon.

Nothing listens behind the endpoint in that state. The backing container is retried by every
operation that needs the live database, so it starts as soon as a daemon becomes reachable. Until
then, RDS Data API calls fail with a modelled `InternalServerErrorException` that names the missing
daemon. A daemon that is reachable but cannot start the container still fails `CreateDBInstance`
outright, since that is a real error rather than a degraded mode.

### Mock mode (CI / tests)

Set `FLOCI_SERVICES_RDS_MOCK=true` when you only need the management API shape: clusters and
instances are registered as `available` immediately, with no Docker container or auth proxy behind
them. Each resource still gets a unique endpoint port, but nothing listens on it.

```yaml
# docker-compose.yml — CI / test environment
services:
  floci:
    image: floci/floci:latest
    environment:
      FLOCI_SERVICES_RDS_MOCK: "true"
```

!!! note "Switching modes over persisted state"
    With a persistent storage mode, changing `FLOCI_SERVICES_RDS_MOCK` between restarts is
    best-effort, as with the other mock-capable services: resources created in real mode and
    deleted under mock leave their containers and volumes behind, and resources created in mock
    mode are restored with fresh, empty containers when loaded in real mode.

!!! warning "DB proxy endpoint routing"

    DB proxy control-plane resources and target registration are modeled, but Floci's current
    single-host TCP relay cannot expose multiple same-engine DB proxies as distinct AWS-style bare
    hostnames on the same engine-default port. The standard Docker Compose mapping also exposes
    only the `7001-7099` instance/cluster proxy range, not `1433`, `3306`, or `5432`. Use mock mode
    for DB proxy provisioning workflows until a dedicated endpoint-routing design is implemented.

!!! note "DB proxy control-plane settings"

    Proxy and target-group settings are persisted and round-trip through the RDS Query API and
    CloudFormation. Pool sizing, borrow timeout, idle timeout, TLS, init-query, and session-pinning
    settings are currently control-plane metadata; the TCP relay does not yet implement those data-plane
    behaviors. `DefaultAuthScheme=IAM_AUTH` is supported for control-plane workflows, but a real-mode
    proxy using that scheme cannot register a target until backend IAM authentication is implemented.
    Requests to `RegisterDBProxyTargets`, `DeregisterDBProxyTargets`, and
    `DescribeDBProxyTargets` use the `default` target group when `TargetGroupName` is omitted,
    matching the RDS API contract.
    `CreateDBProxy`/`AWS::RDS::DBProxy` accept `EndpointNetworkType` (`IPV4`, `IPV6`, or `DUAL`) and
    `TargetConnectionNetworkType` (`IPV4` or `IPV6`) and round-trip them like the other proxy
    settings above; the TCP relay itself still only listens on IPv4, so a non-`IPV4` value is
    accepted as control-plane metadata rather than making the relay dual-stack. A non-`IPV4` value
    is rejected with `InvalidParameterValue` unless the proxy's VPC and every subnet in
    `VpcSubnetIds` already carry an associated IPv6 CIDR block, matching AWS's own network
    prerequisites for RDS Proxy.
    Proxy endpoints report the proxy's own host, so a client connects through one as through the
    proxy, and an IAM token signed for that host verifies there. The host is read from the proxy
    for each response, so when a restore gives the proxy a new host every endpoint reports it. `TargetRole` is recorded: Floci
    proxies have one target, so a `READ_ONLY` endpoint reaches the same target as the proxy rather
    than an Aurora reader. Each proxy's default endpoint (`default`, `IsDefault` true, `READ_WRITE`)
    is built from the proxy, has no ARN, and cannot be modified or deleted
    (`InvalidDBProxyEndpointStateFault`). Endpoint names are unique per account and Region, a proxy
    holds at most 20 endpoints (`DBProxyEndpointQuotaExceededFault`), and an endpoint with no
    `VpcSecurityGroupIds` gets its VPC's default security group.

## Aurora Serverless v2 scaling

`CreateDBCluster`, `ModifyDBCluster`, and `DescribeDBClusters` support the AWS
`ServerlessV2ScalingConfiguration` Query shape for `aurora-mysql` and `aurora-postgresql`
clusters. Requests that apply this configuration to another engine fail with
`InvalidParameterCombination`.

The minimum and maximum capacities use half-ACU increments; the maximum must be at least 1 ACU and
no greater than 256 ACUs. AWS's actual maximum depends on the Aurora engine and platform version,
while Floci currently applies the 256-ACU ceiling uniformly.

When `MinCapacity` is zero, `SecondsUntilAutoPause` accepts 300–86,400 seconds and defaults to 300.
Changing the minimum to a nonzero value removes the auto-pause interval, matching the AWS response
shape. AWS limits zero-capacity auto-pause to compatible Aurora versions; Floci does not currently
enforce that version matrix. `ModifyDBCluster` accepts partial scaling updates and preserves omitted
values. `AWS::RDS::DBCluster` creation also maps the equivalent CloudFormation property.

If a persisted cluster record does not contain its original AWS engine identifier, Floci rejects a
new scaling configuration instead of assuming that the cluster is Aurora.

Floci persists and returns the scaling configuration but does not resize the backing Docker
container: capacity between `MinCapacity` and `MaxCapacity` is not modeled. A `MinCapacity` of 0
does take effect, as described next.

### Automatic pause and resume

A cluster whose `MinCapacity` is 0 pauses the way Aurora Serverless v2 does. Once nothing has used
it for `SecondsUntilAutoPause`, Floci freezes its database container with `docker pause`, which
keeps its data and its endpoint. The next connection resumes it: the client is held, not refused,
until the database runs again, and is then served as usual. As on AWS, a connection attempt with
wrong credentials also resumes the cluster.

- **What keeps a cluster awake.** Every open connection through the cluster endpoint, the reader
  endpoint, an instance endpoint or an RDS Proxy, every RDS Data API call and every open Data API
  transaction. The idle clock starts when the last of them ends.
- **Which clusters pause.** Aurora PostgreSQL and Aurora MySQL clusters with at least one instance,
  all of them `db.serverless` and available. As on AWS, a cluster with a provisioned instance, a
  cluster in a global database and a cluster registered with an RDS Proxy stay running. Floci runs
  a cluster's instances in one container, so they pause and resume together, like Aurora's writer
  and its tier 0 and 1 readers; a reader in tiers 2 to 15 does not pause on its own.
- **What you can observe.** The cluster `Status` and each `DBInstanceStatus` stay `available`.
  `DescribeEvents` reports RDS-EVENT-0370 to 0374 for each instance, for example "Successfully
  paused the DB instance.". While the cluster is paused, `ServerlessDatabaseCapacity` reports 0
  and `ACUUtilization` and `CPUUtilization` report 0 percent in the `AWS/RDS` namespace once a
  minute, by `DBClusterIdentifier` and by `DBInstanceIdentifier`. Floci publishes none of them
  while the cluster runs.
- **Resume delay.** Aurora takes about 15 seconds to resume. Floci resumes at once unless
  `FLOCI_SERVICES_RDS_AURORA_RESUME_DELAY_MILLIS` is set; `15000` exercises client connect timeouts,
  retries and connection-pool validation against a realistic cold start.
- **Configuration changes.** A `ModifyDBCluster` that raises `MinCapacity` above 0 or changes
  `SecondsUntilAutoPause` resumes a paused cluster and restarts its idle clock.
- **Commands Floci runs in the container.** `CreateDBClusterSnapshot` and a master password change
  run inside the database container, so they resume a paused cluster first. Aurora takes snapshots
  from storage without resuming it.
- Stopping, rebooting or deleting a cluster, restarting Floci and resetting its state never leave a
  container frozen. A cluster that was paused when Floci stopped comes back running.
- `FLOCI_SERVICES_RDS_AURORA_AUTO_PAUSE_ENABLED=false` keeps every cluster running.

Not emulated: Data API requests to a paused cluster wait for the resume, as the Aurora user guide
describes, and never fail with `DatabaseResumingException`; logical or binlog replication does not
keep a cluster awake; and parameter group changes do not resume one.

## Modifying a DB instance

`ModifyDBInstance` applies `DBInstanceClass`, `AllocatedStorage` and `EngineVersion`, and
`DescribeDBInstances` reports the new values. Members the request leaves out keep their current
value, so a partial modify does not reset anything.

`AllocatedStorage` follows the AWS rules for the member:

- A size smaller than the current one fails with `InvalidParameterCombination`. RDS storage never
  shrinks.
- On PostgreSQL, MySQL and MariaDB, an increase of less than 10% is rounded up to 10% greater
  rather than refused, which is what AWS does. SQL Server takes the size it is given.
- The same size is not a change and is left alone.

`EngineVersion` accepts a minor upgrade on its own. A different major version needs
`AllowMajorVersionUpgrade`, without which the request fails with `InvalidParameterCombination`.
Major versions are compared on the leading version component, so a MySQL 8.0 to 8.4 upgrade reads
as a minor one here while AWS treats it as major.

A refused member fails the whole request: a modify that carries a new `DBInstanceClass` alongside a
shrinking `AllocatedStorage` returns `InvalidParameterCombination` and leaves the class as it was,
so a read-back never reports a value the request did not get.

Two deviations are worth knowing:

- **Changes apply immediately.** AWS defers a class, storage or engine-version change to the
  preferred maintenance window unless `ApplyImmediately` is set, and reports it under
  `PendingModifiedValues` until then. Floci runs no maintenance window and does not model
  `PendingModifiedValues`, so it applies the change as soon as the request lands whatever
  `ApplyImmediately` says. Without this a deferred change would never be applied at all, and every
  read-back would report the same drift.
- **The backing container is not re-imaged.** An engine-version change is recorded on the instance
  and reported on read, but the container started at create time keeps running the image it was
  started with. This is control-plane compatibility, like Aurora Serverless v2 scaling above.

For standalone, non-Aurora instances with instance-owned storage, `CreateDBInstance` and
`ModifyDBInstance` retain the `StorageType` values `gp2`, `gp3`, `io1` and `io2`. A modify
that omits the member leaves it unchanged; a request naming another type is
refused with `StorageTypeNotSupported` before the instance is created or changed. Describe and
modification responses report the stored value, and valid-modification discovery lists these four
supported types. Aurora and cluster-member instance overrides remain ignored because their
storage belongs to the cluster. Explicit type overrides also work on snapshot restores, point-in-time copies
and read replicas. Snapshots and their copies retain the type; Floci carries it into snapshot
restores, point-in-time copies and read replicas. It also survives persistent, hybrid and WAL storage
reloads. Legacy records without the member retain the existing `gp2` fallback.

Storage type changes apply immediately to control-plane metadata; the backing container volume's
performance is unchanged. `Iops`, `StorageThroughput` and `MultiAZ` changes remain unimplemented.
Omitted create requests retain Floci's existing `gp2` default, which differs from the current
[AWS create default](https://docs.aws.amazon.com/AmazonRDS/latest/APIReference/API_CreateDBInstance.html).
Floci's snapshot and point-in-time copies inherit the source type when the request omits it, rather
than applying the [AWS restore default](https://docs.aws.amazon.com/AmazonRDS/latest/APIReference/API_RestoreDBInstanceFromDBSnapshot.html).
Floci does not enforce every engine-specific storage allocation, IOPS restriction or regional
storage-type availability. Magnetic `standard` storage is refused.

### Storage autoscaling threshold changes

For standalone instances with instance-owned storage, `ModifyDBInstance` checks an explicit
`MaxAllocatedStorage` against the current allocation. The threshold must be at least 10% higher,
except that a value equal to the current allocation retains the existing disable handling.
For example, with 20 GiB allocated, maxima of 19 or 21 GiB are refused and 22 GiB meets this
minimum. Explicit zero and negative maxima are also refused. Rejection occurs before any settings
in the request change, including backup retention and allocation. An omitted maximum retains its
stored value without rechecking historical state.

The check uses the current allocation even when the request also changes storage. AWS applies
threshold changes immediately while storage changes can wait for maintenance. Floci's existing
immediate storage-change behavior remains; this is not a live AWS combined-growth capture or a
complete check of all combined-update constraints.

Aurora/cluster members and RDS Custom remain outside this check. Engine/class upper limits,
IOPS ratios and create/restore/replica constraints remain separate. The chosen HTTP 400
`InvalidParameterCombination` follows the adjacent allocated-storage refusal precedent. The
message follows the guide's example, `Invalid max storage size for {engine_name}`; neither is
claimed as an independently captured, universal live AWS maximum-error response.


## Examples

```bash
export AWS_ENDPOINT_URL=http://localhost:4566

# Create a PostgreSQL instance
aws rds create-db-instance \
  --db-instance-identifier mypostgres \
  --db-instance-class db.t3.micro \
  --engine postgres \
  --master-username admin \
  --master-user-password secret123 \
  --allocated-storage 20 \
  --endpoint-url $AWS_ENDPOINT_URL

# Get connection details
aws rds describe-db-instances \
  --db-instance-identifier mypostgres \
  --query 'DBInstances[0].Endpoint' \
  --endpoint-url $AWS_ENDPOINT_URL

# Connect with psql (use the port returned above)
psql -h localhost -p 7001 -U admin

# Create a MySQL instance
aws rds create-db-instance \
  --db-instance-identifier mymysql \
  --db-instance-class db.t3.micro \
  --engine mysql \
  --master-username root \
  --master-user-password secret123 \
  --allocated-storage 20 \
  --endpoint-url $AWS_ENDPOINT_URL

# Connect with mysql client
mysql -h 127.0.0.1 -P 7002 -u root -psecret123
```

## Supported Engines

| Engine | Default image |
|---|---|
| `postgres` | `postgres:16-alpine` |
| `mysql` | `mysql:8.0` |
| `mariadb` | `mariadb:11` |

Override the image per-instance with the `--engine-version` flag or globally via environment variables.
Aurora MySQL versions such as `8.0.mysql_aurora.3.08.0` use the MySQL version in front, so that example runs `mysql:8.0`.

## Parameter groups

A MySQL or MariaDB database runs with its parameter group: an Aurora MySQL cluster with its DB
cluster parameter group, with the writer instance's DB parameter group on top (so an
instance-level value such as `max_connections` wins, as on AWS), and a standalone instance with
its DB parameter group. Floci passes each override to the server when it starts the database
container, so a value set in the group is the engine's value; creating a cluster's writer restarts
the cluster's container when the writer's group changes anything. A later change to a group, or a
new `DBClusterParameterGroupName` on `ModifyDBCluster` or `DBParameterGroupName` on
`ModifyDBInstance`, takes effect the next time the container starts: `RebootDBInstance` on the
writer or on a standalone instance, a stop and start, or a Floci restart. A cluster's members share
one database, so rebooting the writer restarts it for every member, and a reader's own DB
parameter group is not applied. `time_zone` is passed as `--default-time-zone`, and Aurora's
`binlog_format` of `OFF` turns binary logging off. A parameter the server does not know, such as
one only Aurora has, is skipped with a warning in the database log. A formula value such as
`{DBInstanceClassMemory*3/4}` keeps the server's default, and so do `port`, `bind_address`,
`socket`, `datadir` and `skip_networking`, which Floci needs to reach the database, and
`require_secure_transport` and `skip_grant_tables`, which would lock out Floci's proxy (it talks
to the server without TLS). When the server refuses a value and stops, the instance, or every
member of the cluster, reports `incompatible-parameters` and Floci logs the server's error; correct
the group and reboot to start it again. PostgreSQL and SQL Server databases do not take parameter
group values yet.

## Option Groups

Option groups are metadata: Floci stores the options you add, returns them on the wire, and
attaches a group to a DB instance, but it does not install the underlying engine feature in the
container.

As on AWS, every engine has an implicit `default:<engine>-<major version>` group that
`DescribeOptionGroups` returns even when you have created none. Floci ships the defaults for the
engines it can run (`postgres 13`–`18`, `mysql 8.0`/`8.4`, `mariadb 10.11`/`11.2`/`11.4`), so an
instance created without `--option-group-name` reports the matching default. Default groups can't
be modified, deleted, or tagged.

`CreateOptionGroup` accepts any `EngineName` AWS accepts — including `oracle-*`, `sqlserver-*`,
and `db2-*` — so a Terraform `aws_db_option_group` for an engine Floci cannot start still applies.
Attaching one to a DB instance requires the group's engine and major engine version to match the
instance, as on AWS: a `mysql 8.0` group can't be attached to a `mysql 8.4` instance. A mismatch
fails with `InvalidParameterCombination`.

```bash
aws rds create-option-group \
  --option-group-name my-og \
  --engine-name mysql \
  --major-engine-version 8.0 \
  --option-group-description "MySQL options" \
  --endpoint-url $AWS_ENDPOINT_URL

aws rds modify-option-group \
  --option-group-name my-og \
  --options OptionName=MEMCACHED,Port=11211 \
  --apply-immediately \
  --endpoint-url $AWS_ENDPOINT_URL

aws rds describe-option-groups \
  --engine-name mysql \
  --endpoint-url $AWS_ENDPOINT_URL
```

Deleting a group that is still attached to a DB instance fails with
`InvalidOptionGroupStateFault`, matching AWS.

Known gaps, all deliberate:

| Behavior | Status |
|---|---|
| `DescribeOptionGroupOptions` | Not implemented: the per-engine option catalog is not modeled |
| `OptionGroupQuotaExceededFault` (AWS caps an account at 20 groups) | Not enforced — capping a local emulator would only get in a test's way |
| `OptionSetting` metadata (`DataType`, `ApplyType`, `AllowedValues`, `DefaultValue`, `Description`) | Omitted — it would require the per-engine option catalog `DescribeOptionGroupOptions` serves |
| `MaxRecords` / `Marker` pagination | Every group is returned in one page, as with every other RDS list action |

## Read replicas

`CreateDBInstanceReadReplica` creates a standalone instance with its own container and endpoint.
As on AWS it inherits the source's engine, version, credentials and database name and, unless the
request overrides them, the instance class, storage and minor version upgrade setting. A replica
in the source's region also inherits the source's parameter group, option group, subnet group and
security groups; a replica in another region (source given by ARN, `--region` set to the
destination) gets that region's defaults. IAM authentication and `CopyTagsToSnapshot` are off
unless requested. The replica starts with `BackupRetentionPeriod` 0. Both ends report the link
the way `DescribeDBInstances` does: the replica carries `ReadReplicaSourceDBInstanceIdentifier`
and a `StatusInfos` entry of type `read replication`, the source lists it under
`ReadReplicaDBInstanceIdentifiers`; within a region the link is the identifier, across regions
it is the ARN. A `DBSubnetGroupName` with a source given by plain identifier is refused with
`DBSubnetGroupNotAllowedFault`, as on AWS.

The replica's database is initialised from a `pg_dumpall` of the source taken when the replica is
created, the same mechanism `RestoreDBInstanceFromDBSnapshot` uses, so it holds the source's data
as of that moment. Writes made to the source afterwards are not streamed to the replica. Because
the copy is dump based, only PostgreSQL sources are accepted; MySQL and MariaDB sources are refused
with `InvalidDBInstanceState`, as `CreateDBSnapshot` refuses them. A source with automated backups
off (`BackupRetentionPeriod` 0) is refused with the same error AWS uses.

`PromoteReadReplica` clears the link on both ends, sets the requested `BackupRetentionPeriod`
(one day when omitted) and `PreferredBackupWindow`, and reboots the instance as AWS does, so open
connections drop while the container, endpoint and data stay. Deleting a source promotes its
same-region replicas; a cross-region replica keeps its link with the replication status
`terminated` until it is promoted or deleted, which is what AWS does for PostgreSQL. Deleting a
replica drops it from its source's list.

## Engine catalogue

`DescribeDBEngineVersions` and `DescribeOrderableDBInstanceOptions` read one curated catalogue: a
representative version for each major version Floci knows through its managed parameter and option
groups (PostgreSQL 13 to 18, MySQL 8.0 and 8.4, MariaDB 10.11, 11.2 and 11.4, SQL Server Standard 15,
Aurora PostgreSQL 15 to 17 and Aurora MySQL 8.0). It is not AWS's full version list. Each version
reports the parameter group family the create path computes for it, its major version and its upgrade
targets (the engine's later versions, with `IsMajorVersionUpgrade` set across a major version). Each
engine's default is the version a create request that names none gets (instances, clusters and global
clusters alike), so `DefaultOnly` agrees with what Floci builds; for Aurora MySQL that is
`8.0.mysql_aurora.3.05.2`. Each version also reports what Floci supports for it: `SupportsReadReplica`
for PostgreSQL only, `SupportsGlobalDatabases` for the two Aurora engines, and `SupportsParallelQuery`
and `SupportsBabelfish` as false. `ExportableLogTypes` and `SupportedFeatureNames` list the engine's
documented log types and IAM role feature names. `EngineVersion` takes an exact version or a major version such as `16`, and the
documented filters (`db-parameter-group-family`, `engine`, `engine-mode`, `engine-version`, `status`)
apply. Every version is `available`, so `IncludeAll` changes nothing.

`DescribeEngineDefaultParameters` and `DescribeEngineDefaultClusterParameters` accept a family from the
catalogue (`InvalidParameterValue` otherwise) and return an empty parameter list, consistent with
`DescribeDBParameters` on a default group, which lists only values set on the group.
`DescribeValidDBInstanceModifications` reports the instance's storage type and the sizes `ModifyDBInstance`
stores as asked: the current size, and from the smallest real increase up to the engine's maximum (16384
GiB for SQL Server, 65536 GiB otherwise). Storage only grows, and for PostgreSQL, MySQL and MariaDB an
increase under 10% is rounded up to 10%, so at 40 GiB the second range starts at 44. An Aurora instance
has no storage of its own to modify, so its list is empty. `DescribeOrderableDBInstanceOptions` matches
`EngineVersion` exactly or as a major version prefix, so `8.0` finds the MySQL `8.0.36` rows.

## Cluster endpoints

`CreateDBClusterEndpoint` adds an Aurora custom endpoint to an available cluster. As the Aurora user
guide describes, its type is `READER` or `ANY` (case-insensitive, as the CLI sends it), it holds either
a static list or an exclusion list of the cluster's instances but not both
(`InvalidParameterCombination`), and a `READER` endpoint cannot list the writer. A member that is not
an instance of the cluster is `DBInstanceNotFound`. The identifier follows the RDS identifier rules
(at most 63 characters), is stored lower case and is unique in the Region
(`DBClusterEndpointAlreadyExistsFault`); a cluster holds at most five custom endpoints
(`DBClusterEndpointQuotaExceededFault`). The endpoint's ARN,
`arn:aws:rds:<region>:<account>:cluster-endpoint:<id>`, works with the tagging actions.

`DescribeDBClusterEndpoints` lists each cluster's built-in `WRITER` and `READER` endpoints followed by
its custom endpoints, narrowed by `DBClusterIdentifier`, `DBClusterEndpointIdentifier` and the filters
`db-cluster-endpoint-type`, `db-cluster-endpoint-custom-type`, `db-cluster-endpoint-id` and
`db-cluster-endpoint-status`. `ModifyDBClusterEndpoint` changes the type or replaces the member list
(giving static members clears the exclusion list, and the other way round). Deleting a cluster deletes
its custom endpoints, and a deleted instance drops out of the lists that name it.

A `READER` endpoint leaves out the cluster's current writer, so after a failover the promoted
instance no longer shows among its members, as Aurora adjusts `READER` membership. Member names match
the cluster's instances regardless of case and are stored as the cluster spells them.

Custom endpoints are modelled, not routed: a custom endpoint reports the cluster's own endpoint host
(read from the cluster each time, so it follows a restart onto another proxy port), so it is
reachable, but connections through it go where the cluster endpoint sends them rather than being
spread across the chosen members.

## Data API endpoint

`EnableHttpEndpoint` and `DisableHttpEndpoint` turn a cluster's Data API endpoint on and off by the
cluster's ARN, and `DescribeDBClusters` reports it as `HttpEndpointEnabled`. `CreateDBCluster` and
`ModifyDBCluster` take `EnableHttpEndpoint` too, as does the `AWS::RDS::DBCluster` CloudFormation
property, so Terraform's `enable_http_endpoint` and CDK's `enableDataApi` read back what they set.

The Data API is offered for Aurora PostgreSQL and Aurora MySQL clusters, so turning it on for any
other engine is `InvalidParameterCombination` (a create checks before the cluster exists); turning
it off is always accepted. An ARN that names no cluster in the caller's account and Region is
`ResourceNotFoundFault`, and a cluster that is not available is `InvalidResourceStateFault`. The flag
is recorded and reported only: the Data API (`rds-data`) does not refuse a cluster with it off.

## Log files

`DescribeDBLogFiles` and `DownloadDBLogFilePortion` read an instance's log from its database
container (`docker logs`, the most recent 100,000 lines), cut into files the way RDS names them: PostgreSQL rotates hourly into
`error/postgresql.log.YYYY-MM-DD-HH` (UTC), MySQL and MariaDB keep `error/mysql-error.log`, and SQL
Server keeps `log/ERROR`. An Aurora member reads its cluster's container. `LastWritten` is the time
Docker recorded for the file's last line, and `Size` the bytes of its lines.

`DescribeDBLogFiles` takes `FilenameContains`, `FileLastWritten` (files written at or after it) and
`FileSize` (files larger than it), and pages by `MaxRecords` / `Marker`. `DownloadDBLogFilePortion`
follows the API reference: without a `Marker` it returns the most recent `NumberOfLines` lines (the
whole file, up to 10000 lines, without either), `Marker` `0` starts at the first line, and the
returned `Marker` continues until `AdditionalDataPending` is false. A block stops at 1 MB, and a
single line longer than that is cut at 1 MB, as the API reference says the block is truncated.
Control characters XML cannot carry (a terminal colour code) come back as U+FFFD.

An instance that is not available, or whose database container cannot be found or started, is
`DBInstanceNotReady`, and an unknown file name `DBLogFileNotFoundFault`. Negative `FileSize`,
`FileLastWritten` or `Marker` values are `InvalidParameterValue`. In mock RDS mode there is no container, so an instance has no log files.

## IAM role associations

`AddRoleToDBCluster` and `AddRoleToDBInstance` associate an IAM role with an available cluster or
instance, and `DescribeDBClusters` / `DescribeDBInstances` report it in `AssociatedRoles` with status
`ACTIVE` straight away. `CreateDBCluster` accepts `AssociatedRoles` too: a list that names a role twice,
or has an entry without a `RoleArn`, refuses the create before any cluster exists, and the roles are
associated in one step, so a create that fails leaves no cluster behind.

`FeatureName` (such as `s3Import`, `s3Export` or `Lambda`) is optional for a cluster and required for an
instance. A role is associated at most once per cluster (`DBClusterRoleAlreadyExists`, which covers
the role ARN only, so two roles may share a feature), and a role or a feature at most once per instance
(`DBInstanceRoleAlreadyExists`, documented as "The specified RoleArn or FeatureName value is already
associated"). `RemoveRoleFromDBCluster` removes the
role, matching the feature name when one is given, and `RemoveRoleFromDBInstance` removes the role for
the feature named; an association that does not exist is `DBClusterRoleNotFound` /
`DBInstanceRoleNotFound`. A stopped cluster or instance refuses both (`InvalidDBClusterStateFault` /
`InvalidDBInstanceState`).

A `FeatureName` must be one the engine lists in `SupportedFeatureNames` (`DescribeDBEngineVersions`),
matched without regard to case; any other is `InvalidParameterValue`, and a `CreateDBCluster` naming
one creates nothing. MySQL, MariaDB and Aurora MySQL list none, so their roles take no feature name.

Associations are recorded, not exercised: Floci does not check the role in IAM and does not hand the
role's credentials to the database, so an `aws_s3` import still needs credentials of its own. Deleting a cluster or instance drops its
associations, and snapshots, restores and read replicas start without any.

## Aurora managed password opt-out

When `ModifyDBCluster` disables `ManageMasterUserPassword` while the managed secret has
cross-Region replicas, the request succeeds but retains the cluster password and its
`MasterUserSecret` metadata, as Aurora does. Remove the secret's replicas and retry to
release password management. Other requested cluster settings still apply.

Floci reserves the secret while changing backend credentials, without holding a shared
Secrets Manager lock during backend I/O. Replication and deletion requests for that secret
fail while release is in progress; unrelated secrets remain available.
A failed backend password rotation retains the managed secret and stored credentials.
If Secrets Manager support is unavailable, opt-out fails before changing cluster state.
This does not provide a transaction across database backends and persistent storage.

## PostgreSQL restore connections

Snapshot and point in time restores drop the target's non-template databases with
`DROP DATABASE ... WITH (FORCE)` before replaying the dump. This terminates connections
on the restore target, including preloaded workers such as pg_cron; the source is unchanged.
Custom PostgreSQL images must support this syntax (PostgreSQL 13 or later). Prepared
transactions and active logical replication slots or subscriptions can still prevent a drop.

## Point in time restore

`RestoreDBInstanceToPointInTime` and `RestoreDBClusterToPointInTime` check the request as AWS does:
exactly one of `RestoreTime` (`RestoreToTime` for a cluster) and `UseLatestRestorableTime`, and a
time inside the source's restorable window, or `InvalidRestoreFault`. The window is what the describe
calls report. An instance's runs from the later of its creation and the start of its backup
retention period up to `LatestRestorableTime` on `DescribeDBInstances`; an instance with
`BackupRetentionPeriod` 0 reports no `LatestRestorableTime` and is refused with
`PointInTimeRestoreNotEnabled`. Floci does not model a cluster's backup retention, so a cluster's
window runs from its creation (`EarliestRestorableTime`) to `LatestRestorableTime` on
`DescribeDBClusters`.

An instance source is named by `SourceDBInstanceIdentifier` or `SourceDbiResourceId`, a cluster
source by `SourceDBClusterIdentifier` (identifier or ARN) or `SourceDbClusterResourceId`. Restoring
from retained automated backups (`SourceDBInstanceAutomatedBackupsArn`, used for a deleted instance)
is not supported, since Floci keeps no automated backups, and answers
`DBInstanceAutomatedBackupNotFound`. A restored instance takes the source's engine, version,
credentials, database name, storage and encryption, and its instance class unless the request names
one; everything else is the request's or the default a new instance gets. A restored cluster takes
the source's engine, version, credentials, database name and encryption, and has no instances until
they are added with `CreateDBInstance`, as on AWS.

Floci keeps no transaction log, so it cannot rebuild a database as it was at an earlier time. The
target is seeded from a `pg_dumpall` of the source taken when the request arrives, the mechanism read
replicas use, so the restored data is the source's latest state whatever time in the window is
asked for; this is also why `LatestRestorableTime` is always the current time. As with
`CreateDBSnapshot`, only PostgreSQL sources are accepted.

## Exporting snapshots to Amazon S3

`StartExportTask`, `DescribeExportTasks` and `CancelExportTask` manage RDS snapshot export tasks.
`ExportTaskIdentifier`, `SourceArn`, `S3BucketName`, `IamRoleArn` and `KmsKeyId` are required,
`S3Prefix` and `ExportOnly` are optional.

A task reports `STARTING`, then `IN_PROGRESS` with a `PercentProgress` while its tables are written,
then `COMPLETE` or `FAILED` with a `FailureCause`. `CancelExportTask` moves a running task to
`CANCELING`; it stops before its next table and becomes `CANCELED`, and the data it already wrote
stays in the bucket, as on RDS. A table the export skips is named in `WarningMessage`. A task still
running when Floci stops is `FAILED` as soon as Floci starts again, or `CANCELED` if it was being
canceled. A `StartExportTask` that arrives while Floci is resetting waits for the reset to finish,
and is refused with `Throttling` if it lasts more than 30 seconds.

Floci does not write snapshot data to S3 yet. In [mock mode](#mock-mode-ci-tests) a task completes
without writing anything, unless its bucket is in another region (see below). Outside mock mode a task is accepted and then ends `FAILED` with the
`FailureCause` `Floci does not write snapshot data to S3 yet: export tasks complete only in mock
mode (FLOCI_SERVICES_RDS_MOCK=true)`.

`ExportOnly` takes `database`, `database.schema` or `database.schema.table` entries made of
letters, digits, `$` and `_`, and refuses anything else with `InvalidExportOnly`, since RDS supports
a partial export only for such names.

What differs from RDS:

- The IAM role is not assumed and the KMS key encrypts nothing: both are recorded and reported back
  as given. The bucket must exist, or the request fails with `InvalidS3BucketFault`. A bucket in
  another region than the snapshot's is accepted and the task then ends `FAILED`, as on RDS.
- Only PostgreSQL snapshots can be exported, since Floci's snapshots hold PostgreSQL data only.
  Any other source is refused with `InvalidExportSourceState`, and so is a snapshot that is not
  `available`.
- `SourceArn` must name a snapshot: exporting a live DB cluster (`SourceType` `CLUSTER`) is not
  supported.

## Global clusters

`CreateGlobalCluster` creates an Aurora global database (`aurora-mysql` or `aurora-postgresql`),
either empty or with an existing Aurora cluster as its primary through `SourceDBClusterIdentifier`
(an ARN, or an identifier in the request's region), in which case engine, version, database name
and encryption come from that cluster and may not be given, as on AWS. The record is account wide:
its ARN has no region and `DescribeGlobalClusters` lists it from any region.

`CreateDBCluster` with `GlobalClusterIdentifier` joins a cluster: the first one becomes the
primary, every later one a secondary. A secondary must be in a region that holds neither the
primary nor another secondary, may not carry its own master credentials or database name (it takes
the primary's), and its database is initialised from a `pg_dumpall` of the primary the way a read
replica is, so only `aurora-postgresql` primaries accept secondaries; writes after that are not
streamed. `DescribeGlobalClusters` reports every member with `IsWriter` and, on the primary, the
secondaries under `Readers`; `DescribeDBClusters` reports `GlobalClusterIdentifier` on members.

`SwitchoverGlobalCluster`, and `FailoverGlobalCluster` with or without `AllowDataLoss`, promote
the named secondary and demote the current primary to a secondary, the topology AWS keeps for a
switchover and restores after a managed failover once the old primary region is healthy again.
The response is the completed state; AWS answers with a pending `FailoverState` because the
operation runs asynchronously there. `RemoveFromGlobalCluster` detaches a member into a standalone
cluster; the primary can only be removed once every secondary is gone, and deleting the primary
cluster while secondaries remain is refused the same way. `DeleteGlobalCluster` needs an empty
global cluster with deletion protection off.

`FailoverDBCluster` moves the writer role inside a DB cluster to the named reader instance, or to
the first reader when none is named; a cluster with no reader has nothing to fail over to and is
refused. `DescribeDBClusters` reports the role as `IsClusterWriter`, and deleting the writer
promotes a remaining member, as Aurora does on its own.

## Persistence

Each DB instance and cluster gets its own named Docker volume (`floci-rds-{volumeId}`) created
automatically. No configuration is required.

| Scenario | Volume behavior |
|---|---|
| `memory` mode (default) | Volume is removed automatically when the instance is deleted |
| `persistent` / `hybrid` / `wal` | Volume is retained after delete — data survives for manual recovery |

```bash
# CI — ephemeral, volumes cleaned up on each delete
FLOCI_STORAGE_MODE=memory

# Local dev — retain DB data across Floci restarts
FLOCI_STORAGE_MODE=hybrid

# Local dev — also remove volumes immediately on delete
FLOCI_STORAGE_MODE=hybrid
FLOCI_STORAGE_PRUNE_VOLUMES_ON_DELETE=true
```

To use a host bind mount instead of a named volume (advanced), set an absolute path:

```bash
FLOCI_STORAGE_HOST_PERSISTENT_PATH=/absolute/host/path/data
```

!!! note "Docker Desktop on macOS"
    Named volumes work correctly on Docker Desktop for macOS. Bind mounts to paths inside the Floci container are not supported — use named volumes (the default).

## Authentication

The RDS auth proxy checks the master username and password at the proxy layer. Other database users authenticate against the backend engine. For PostgreSQL instances with IAM database authentication enabled, the database role's `rds_iam` membership also determines which authentication method it can use.

PostgreSQL authentication responses are buffered before they are flushed to the client,
so the password challenge is not emitted as separate header and payload writes.
This supports PgBouncer logins over plaintext and TLS while keeping the same
password, IAM and database-role checks.

IAM database authentication is also supported. Set `--enable-iam-database-authentication` at instance creation time and use `aws rds generate-db-auth-token` to obtain a token.

Aurora members use their cluster's IAM database authentication setting and master
username and password for backend connections. `EnableIAMDatabaseAuthentication`
on `CreateDBInstance` does not override the cluster setting. A member request with
`MasterUsername` or `MasterUserPassword` returns `InvalidParameterCombination`;
configure those credentials on the cluster and omit them from the member request.
Managed master secrets belong to the cluster; `ManageMasterUserPassword` on a member
does not create a separate member secret. When a cluster record lacks a credential,
the member retains its normalized default for that field.

`ModifyDBInstance` preserves the cluster-owned master password when
`MasterUserPassword` is supplied for a member; other supported instance settings
still apply. Change the cluster password with `ModifyDBCluster`.

Accepted cluster password changes update stored member credentials even when the
cluster has no backend or runs in mock mode. An unchanged password does not update
relays. Rotating an initialized data volume requires a running backend; changing
stored metadata alone does not rotate that volume.

Changing `EnableIAMDatabaseAuthentication` with `ModifyDBCluster` updates existing
Aurora members and applies to new connections through the cluster and member
endpoints. Omitting the flag preserves the current setting. Existing connections
are not disconnected.


A token can be signed with an access key or with temporary credentials: an assumed role's, or the execution role credentials Floci hands a Lambda function. A token signed with temporary credentials carries their session token in `X-Amz-Security-Token`, and it is accepted while that session is valid and only with the session token issued alongside the access key.

On RDS, a token is only good for the endpoint it was generated for: the hostname, port and region passed to `generate-db-auth-token` are the ones the instance (or cluster, or RDS Proxy) publishes. MySQL and MariaDB endpoints in Floci always refuse a token generated for another endpoint. PostgreSQL endpoints do the same by default; set `FLOCI_SERVICES_RDS_IAM_TOKEN_ENDPOINT_BINDING=false` to accept a token generated for another name, for clients that generate tokens for a container name or DNS alias the endpoint does not publish. Either way, the username must match the `DBUser` in the token exactly. Because clients on the host reach the same proxy over the loopback interface, a token generated for `localhost` or `127.0.0.1` on the published port is accepted as well. Clients on a Docker network connect by Floci's container name, so set `FLOCI_SERVICES_RDS_ENDPOINT_HOST` to that name (see [Docker Compose](#docker-compose)) so it is what the endpoint publishes and tokens are generated for. With [IAM enforcement](iam.md#iam-enforcement-mode) turned on, the token's principal must also be allowed `rds-db:connect` on the database user, scoped the way AWS scopes it:

```
arn:aws:rds-db:<region>:<account-id>:dbuser:<DbiResourceId>/<db-user-name>
```

Aurora cluster endpoints and their member instance endpoints use the cluster's `DbClusterResourceId`, including after member reboot or runtime recovery, and connections through an RDS Proxy use the proxy's `prx-…` resource id. A token that fails any of these checks is refused with the engine's ordinary authentication failure; the reason is written to Floci's log.

On native PostgreSQL instance and cluster endpoints, the token names a database
role (`DBUser`). The role must have its own `LOGIN` attribute and a direct or indirect grant of `rds_iam`, including when
logging in as the master user. Standard RDS Proxy client IAM connections do not
require that database IAM grant: the backend role still needs `LOGIN`, and the
session remains bound to that role's identity. End-to-end DB Proxy IAM is not
supported by the real database mode. Create an application role for a native
database endpoint as the master user:

```sql
CREATE ROLE app_user LOGIN;
GRANT rds_iam TO app_user;
```

On native database endpoints with IAM authentication enabled, membership in
`rds_iam` makes IAM take
precedence over the role's password. Roles without that membership continue to use
passwords. These database-role checks apply independently of Floci's IAM policy
enforcement setting. Disabling IAM database authentication restores ordinary
password authentication for new connections. Password attempts refused because of that membership report
`PAM authentication failed` with SQLSTATE `28000`.

The IAM session runs as the token's role: `current_user` and `session_user` both
report it, and objects it creates belong to it. A missing role or a role without
`LOGIN` is refused with an authentication failure. Native database endpoints also
require `rds_iam` membership. Grant the application any additional database
privileges it needs.

Underneath, the proxy reaches the container as the master user and hands the session over to the token's role, so an IAM session that talks its way back to the master role, via `RESET SESSION AUTHORIZATION` and its variants, is terminated with `FATAL: permission denied to set session authorization` rather than being allowed to regain superuser. `SET ROLE` is untouched: PostgreSQL still permission-checks it against the token's role, exactly as on RDS. One difference from RDS: the proxy learns of the switch from PostgreSQL's own report, so when several statements are batched into a single query after the switch, their results are returned before the session is closed.

The parameters a PostgreSQL client sends when it connects (`options` such as `-c search_path=app`, `application_name`, and any other run-time parameter) apply to the session as they do on RDS. An IAM session for a role other than the master gets them once the session has been handed over to the token's role, applied with `set_config()` so PostgreSQL checks each one against that role's privileges, and a parameter PostgreSQL refuses fails the login with PostgreSQL's own error, as it would at startup. The role's own `ALTER ROLE ... SET` defaults load as they do at login: the role-wide ones, then those for the database. They are the values `RESET` returns to, and they apply with the authority of whoever stored them, so a superuser-only default set by an administrator binds the role even after `RESET ALL`. To get this, a role with defaults has its backend session opened a second time, with the defaults as startup options. A default PostgreSQL refuses is skipped with a `WARNING`, as at login. A `role` default is checked against the role's memberships, and a `client_encoding` default applies unless the client chose an encoding; both are set once the session is the role's, so `RESET` does not return to them. Defaults stored on the master role itself are not undone for these sessions. A `session_authorization` naming any other role is refused with `permission denied to set session authorization` before anything is applied, since PostgreSQL would authorize it against the master the proxy connected as. Because those sessions have already started, three things differ from RDS: `options` may carry only `-c name=value` and `--name=value` switches, a parameter that can only be set at connection start is refused with `cannot be set after connection start`, and `replication` connections are refused. And because the values are set with `set_config()` rather than at startup, `RESET` (and `RESET ALL`) takes such a parameter back to the server default, not to the value the client connected with: after `-c search_path=app`, `RESET search_path` gives `"$user", public` where RDS keeps `app`.

On MySQL, `AWSAuthenticationPlugin` is proprietary to RDS and ships in no public MySQL build, so
`CREATE USER ... IDENTIFIED WITH AWSAuthenticationPlugin AS 'RDS'` would fail against the container
with `ERROR 1524 (HY000): Plugin 'AWSAuthenticationPlugin' is not loaded`. The proxy rewrites that
clause to `IDENTIFIED WITH mysql_native_password AS '*000...0'`, an authentication string no
password hashes to. The account is therefore created, can be granted to and can be dropped, but
holds no password of its own, which is what an IAM DB user is: an account whose credentials come
from IAM rather than from MySQL. This is the MySQL counterpart of the empty `rds_iam` role Floci
pre-creates for PostgreSQL.

Two limits follow from that. `SHOW CREATE USER` reports the substituted plugin rather than
`AWSAuthenticationPlugin`, so a Terraform or Pulumi refresh sees drift on `auth_plugin`. And
connecting with a token is not yet emulated for MySQL: unlike PostgreSQL, which receives the
password in cleartext, MySQL sends a scramble, so the proxy would have to drive the
`mysql_clear_password` auth switch that real RDS triggers before it could see a token to validate.

## Certificate authorities

`DescribeCertificates` lists the Region's RDS certificate authorities, `rds-ca-rsa2048-g1`,
`rds-ca-rsa4096-g1` and `rds-ca-ecc384-g1`, with the thumbprint and validity AWS reports. They are
vendored from the certificate bundles AWS publishes for each Region of the `aws` partition by
`tools/aws/regen_rds_ca_certificates.py`. As the user guide says, ap-south-1, ap-southeast-4,
ca-west-1, eu-central-2, eu-south-2 and il-central-1 offer only `rds-ca-rsa2048-g1`, though their
bundles carry all three. A Region with no vendored entry, such as a GovCloud, China or custom one,
reports us-east-1's certificates under its own ARN.

- `CertificateIdentifier` filters, without regard to case, and an unknown one is
  `CertificateNotFound`. `MaxRecords` (20 to 100) and `Marker` page the list. `Filters` is not taken,
  since the API reference says it isn't supported.
- `DefaultCertificateForNewLaunches` is `rds-ca-rsa2048-g1` until `ModifyCertificates` sets an
  override for the account and Region. The overriding certificate then reports `CustomerOverride`
  true and its `CustomerOverrideValidTill`. `RemoveCustomerOverride` puts RDS's default back.
  Sending both members, or neither, is `InvalidParameterCombination`. AWS doesn't document what it does here, so
  this behavior is a guess.
- `CreateDBInstance`, `RestoreDBInstanceFromDBSnapshot`, `RestoreDBInstanceToPointInTime`,
  `CreateDBInstanceReadReplica` and `ModifyDBInstance` take `CACertificateIdentifier`. An instance
  given none gets the account's default when it is created. An unknown identifier is
  `CertificateNotFound`. A modify changes it at once, as Floci applies every modification
  immediately, and a blank value leaves it as it was. `CertificateRotationRestart` is accepted and
  has no effect.
- Instances report `CACertificateIdentifier` and `CertificateDetails` (`CAIdentifier`, with the
  CA's `ValidTill`, as Floci keeps no per-instance server certificate). An instance stored before
  this was recorded reports `rds-ca-rsa2048-g1`, since an override applies only to instances
  created after it. `AWS::RDS::DBInstance` takes
  `CACertificateIdentifier` and resolves `CertificateDetails.CAIdentifier` and
  `CertificateDetails.ValidTill` in `Fn::GetAtt`.

The identifier is metadata: the auth proxy presents a certificate from Floci's own CA whatever
the instance names, so clients trust Floci's CA (see [TLS / SSL](#tls-ssl)), not AWS's bundle.
Multi-AZ DB clusters do not take `CACertificateIdentifier` yet.

## TLS / SSL

The RDS auth proxy terminates TLS itself (the backend container stays plaintext). It presents
a non-CA server certificate followed by Floci's shared root CA, so strict TLS
clients can validate an end-entity certificate rather than being asked to accept a CA as a server.
The server certificate's Subject Alternative Names cover every advertised DB instance, cluster,
or RDS Proxy host: the Docker bridge IP, `host.docker.internal`, `localhost`, or whatever
`rds.endpointHost` resolves to.

The shared CA is the same one used by ACM, IoT, and EKS. It is persisted at
`{storage.persistent-path}/tls/floci-root-ca.crt`, with its owner-only private key in
`floci-root-ca.key`. The server certificate is issued in memory on startup and when a new host
appears, and renewed before expiry during TLS use; its SAN list is recorded in `rds-ca.metadata.json`.
Renewal preserves the CA and SANs. If the CA itself expires, the proxy refuses new TLS use
rather than silently changing client trust; restart to regenerate the unusable CA and trust its replacement.
The shared CA certificate and key do not change during SAN growth or restarts. Missing or
corrupt SAN metadata is rebuilt without replacing a valid
CA. An invalid or unusable CA/key pair is replaced with a warning; only then must clients trust
the replacement CA again.

The proxy also writes the shared root certificate to `tls/rds-ca.crt`, preserving existing
`PGSSLROOTCERT` and MySQL `--ssl-ca` paths. This compatibility copy is refreshed when the proxy
initializes, even if the old RDS certificate is expired or corrupt. The unused `rds-ca.key`
is removed; the shared CA key and existing `rds-ca.metadata.json` SAN list are retained.
The shared certificate is also available from `GET /_floci/ca.pem`.

The proxy logs the shared certificate path when it first issues a server certificate:

```
RDS proxy TLS: for sslmode=verify-full set PGSSLROOTCERT=./data/tls/floci-root-ca.crt
```

Because the SAN matches the address you actually connect to, `verify-full` (the same level
Aurora enforces in AWS) works locally too, without falling back to `sslmode=disable` just to
exercise the same connection-string settings you use in production:

```bash
# PostgreSQL
PGSSLROOTCERT=./data/tls/floci-root-ca.crt psql "host=localhost port=7001 user=admin sslmode=verify-full"

# MySQL / MariaDB
mysql -h 127.0.0.1 -P 7002 -u root -psecret123 \
  --ssl-mode=VERIFY_IDENTITY --ssl-ca=./data/tls/floci-root-ca.crt
```
