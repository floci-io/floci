# RDS

**Protocol:** Query (XML) for management API + PostgreSQL / MySQL / SQL Server wire protocol for data plane
**Management Endpoint:** `POST http://localhost:4566/`
**Data Endpoint:** `localhost:<proxy-port>` (TCP)

Floci manages real PostgreSQL, MySQL, MariaDB, and SQL Server Docker containers and proxies TCP connections to them, including IAM authentication support where the protocol supports it. SQL Server uses a transparent TCP relay for its native TDS protocol.

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
| `DescribeOrderableDBInstanceOptions` | List deterministic instance class options |
| `DescribeEvents` | - |
| `CreateEventSubscription` | Create an event notification subscription (stored, nothing is published) |
| `DescribeEventSubscriptions` | List subscriptions, or the one the request names |
| `ModifyEventSubscription` | Update the members the request names |
| `DeleteEventSubscription` | Delete a subscription |
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
| `DescribeDBSnapshots` | List DB instance snapshots |
| `DescribeDBSnapshotAttributes` | Return a snapshot's `restore` attribute (accounts authorized to copy/restore it) |
| `ModifyDBSnapshotAttribute` | Add or remove accounts authorized to copy/restore a snapshot |
| `DescribeDBProxies` | List DB proxies |
| `CreateDBProxy` | Create a DB proxy |
| `ModifyDBProxy` | Update mutable DB proxy authentication, logging, timeout, TLS, role, and security-group settings |
| `DeleteDBProxy` | Delete a DB proxy |
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
| `DescribeDBClusterSnapshotAttributes` | Return the `restore` attribute of a cluster snapshot |
| `ModifyDBClusterSnapshotAttribute` | Add or remove `restore` values (account ids or `all`) on a cluster snapshot |
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

`CreateDBInstance`, `CreateDBCluster`, `RestoreDBInstanceFromDBSnapshot`, and
`RestoreDBClusterFromSnapshot` honor a requested `Port` only within the configured
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

`StorageType`, `MultiAZ` and `Iops` are not read on a modify yet. Floci reports every instance as
`gp2`.

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

The RDS auth proxy validates the master username and password at the proxy layer. All other database users are passed through directly to the backend engine — create them with standard SQL (`CREATE USER`) and connect as normal.

IAM database authentication is also supported. Set `--enable-iam-database-authentication` at instance creation time and use `aws rds generate-db-auth-token` to obtain a token.

On RDS, a token is only good for the endpoint it was generated for: the hostname, port and region passed to `generate-db-auth-token` are the ones the instance (or cluster, or RDS Proxy) publishes. MySQL and MariaDB endpoints in Floci always refuse a token generated for another endpoint. PostgreSQL endpoints do the same by default; set `FLOCI_SERVICES_RDS_IAM_TOKEN_ENDPOINT_BINDING=false` to accept a token generated for another name, for clients that generate tokens for a container name or DNS alias the endpoint does not publish. Either way, the username must match the `DBUser` in the token exactly. Because clients on the host reach the same proxy over the loopback interface, a token generated for `localhost` or `127.0.0.1` on the published port is accepted as well. Clients on a Docker network connect by Floci's container name, so set `FLOCI_SERVICES_RDS_ENDPOINT_HOST` to that name (see [Docker Compose](#docker-compose)) so it is what the endpoint publishes and tokens are generated for. With [IAM enforcement](iam.md#iam-enforcement-mode) turned on, the token's principal must also be allowed `rds-db:connect` on the database user, scoped the way AWS scopes it:

```
arn:aws:rds-db:<region>:<account-id>:dbuser:<DbiResourceId>/<db-user-name>
```

Aurora clusters use the `DbClusterResourceId`, and connections through an RDS Proxy use the proxy's `prx-…` resource id. A token that fails any of these checks is refused with the engine's ordinary authentication failure; the reason is written to Floci's log.

On PostgreSQL, the token names a database role (`DBUser`) and the session runs as that role: `current_user` and `session_user` both report it, objects it creates are owned by it, and a token naming a role the database does not have is refused with `FATAL: role "..." does not exist`. Create the role first with `CREATE ROLE <name> WITH LOGIN` as the master user, and grant it whatever the application needs.

Underneath, the proxy reaches the container as the master user and hands the session over to the token's role, so an IAM session that talks its way back to the master role, via `RESET SESSION AUTHORIZATION` and its variants, is terminated with `FATAL: permission denied to set session authorization` rather than being allowed to regain superuser. `SET ROLE` is untouched: PostgreSQL still permission-checks it against the token's role, exactly as on RDS. One difference from RDS: the proxy learns of the switch from PostgreSQL's own report, so when several statements are batched into a single query after the switch, their results are returned before the session is closed.

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

## TLS / SSL

The RDS auth proxy terminates TLS itself (the backend container stays plaintext) using a
self-signed CA whose Subject Alternative Names cover every advertised host Floci has handed
out for a DB instance, cluster, or RDS Proxy — the Docker bridge IP, `host.docker.internal`,
`localhost`, or whatever `rds.endpointHost` resolves to. The CA is persisted at
`{storage.persistent-path}/tls/rds-ca.crt` and grows its SAN list as new hosts appear, so the
same root survives restarts and works for every local database, not just the one that
generated it.

Floci logs the certificate path (and the `PGSSLROOTCERT` hint) the first time it generates or
loads it:

```
RDS proxy TLS: CA cert at ./data/tls/rds-ca.crt
RDS proxy TLS: for sslmode=verify-full set PGSSLROOTCERT=./data/tls/rds-ca.crt
```

Because the SAN matches the address you actually connect to, `verify-full` (the same level
Aurora enforces in AWS) works locally too — no need to fall back to `sslmode=disable` just to
exercise the same connection-string settings you use in production:

```bash
# PostgreSQL
PGSSLROOTCERT=./data/tls/rds-ca.crt psql "host=localhost port=7001 user=admin sslmode=verify-full"

# MySQL / MariaDB
mysql -h 127.0.0.1 -P 7002 -u root -psecret123 \
  --ssl-mode=VERIFY_IDENTITY --ssl-ca=./data/tls/rds-ca.crt
```
