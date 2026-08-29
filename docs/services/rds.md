# RDS

**Protocol:** Query (XML) for management API + PostgreSQL / MySQL wire protocol for data plane
**Management Endpoint:** `POST http://localhost:4566/`
**Data Endpoint:** `localhost:<proxy-port>` (TCP)

Floci manages real PostgreSQL, MySQL, and MariaDB Docker containers and proxies TCP connections to them, including IAM authentication support.

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
| `DescribeOrderableDBInstanceOptions` | List deterministic instance class options |
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
| `DescribeDBParameters` | List parameters in a group |
| `CreateDBClusterParameterGroup` | - |
| `DescribeDBClusterParameterGroups` | - |
| `DeleteDBClusterParameterGroup` | - |
| `ModifyDBClusterParameterGroup` | - |
| `DescribeDBClusterParameters` | - |
| `CreateOptionGroup` | Create an option group |
| `DescribeOptionGroups` | List option groups, including the implicit `default:` groups |
| `ModifyOptionGroup` | Add, update, or remove options in an option group |
| `DeleteOptionGroup` | Delete an option group |
| `DescribeDBSnapshots` | Return an empty snapshot list (snapshots are not modeled) |
| `DescribeDBProxies` | List DB proxies |
| `CreateDBProxy` | Create a DB proxy |
| `ModifyDBProxy` | Update mutable DB proxy authentication, logging, timeout, TLS, role, and security-group settings |
| `DeleteDBProxy` | Delete a DB proxy |
| `RegisterDBProxyTargets` | Register a cluster or instance as a proxy target |
| `DeregisterDBProxyTargets` | Remove a cluster or instance from a proxy target group |
| `DescribeDBProxyTargetGroups` | List a proxy's target groups |
| `ModifyDBProxyTargetGroup` | Update target-group connection-pool configuration |
| `DescribeDBProxyTargets` | List a proxy target group's registered targets |
| `DescribeDBClusterSnapshots` | Return an empty cluster-snapshot list (snapshots are not modeled) |
| `DescribeGlobalClusters` | List global clusters — always empty, as none are modeled |
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

## Configuration

| Variable | Default | Description |
|---|---|---|
| `FLOCI_SERVICES_RDS_ENABLED` | `true` | Enable or disable the service |
| `FLOCI_SERVICES_RDS_MOCK` | `false` | `true` = metadata only (no Docker container or auth proxy) |
| `FLOCI_SERVICES_RDS_PROXY_BASE_PORT` | `7001` | First host port in the RDS proxy range |
| `FLOCI_SERVICES_RDS_PROXY_MAX_PORT` | `7099` | Last host port in the RDS proxy range |
| `FLOCI_SERVICES_RDS_ENDPOINT_HOST` | _(auto-detected)_ | Hostname advertised in RDS endpoints; when set in Docker, Floci advertises each proxy's published host port |
| `FLOCI_SERVICES_RDS_DEFAULT_POSTGRES_IMAGE` | `postgres:16-alpine` | Docker image for PostgreSQL instances |
| `FLOCI_SERVICES_RDS_DEFAULT_MYSQL_IMAGE` | `mysql:8.0` | Docker image for MySQL instances |
| `FLOCI_SERVICES_RDS_DEFAULT_MARIADB_IMAGE` | `mariadb:11` | Docker image for MariaDB instances |

### Docker Compose

RDS requires the Docker socket and port range exposure. For private registry authentication and other Docker settings see [Docker Configuration](../configuration/docker.md).

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
    DB proxies currently support `IPV4` for both endpoint and target connections; `IPV6` and `DUAL`
    endpoint networking require additional listener and Docker-network support.

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

This is control-plane compatibility: Floci persists and returns the scaling configuration, but it
does not resize or automatically pause the backing Docker container.

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
| `CopyOptionGroup`, `DescribeOptionGroupOptions` | Not implemented — separate actions, not part of option group CRUD |
| `OptionGroupQuotaExceededFault` (AWS caps an account at 20 groups) | Not enforced — capping a local emulator would only get in a test's way |
| `OptionSetting` metadata (`DataType`, `ApplyType`, `AllowedValues`, `DefaultValue`, `Description`) | Omitted — it would require the per-engine option catalog `DescribeOptionGroupOptions` serves |
| `MaxRecords` / `Marker` pagination | Every group is returned in one page, as with every other RDS list action |

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
