# Timestream for InfluxDB

**Protocol:** JSON 1.0 (`X-Amz-Target: AmazonTimestreamInfluxDB.*`)
**Endpoint:** `POST http://localhost:4566/`
**Signing name:** `timestream-influxdb`

Floci emulates the Amazon Timestream for InfluxDB management API and backs InfluxDB 2.x DB instances with
real InfluxDB containers. The endpoint and port returned by `CreateDbInstance`, `GetDbInstance` and
`GetDbCluster` point at the container, so the influx CLI, the InfluxDB UI and any InfluxDB 2.x client can
write and query data against it.

## Supported Actions

<!-- floci:actions:start -->
| Action | Description |
| --- | --- |
| `CreateDbInstance` | Starts an InfluxDB 2.x container initialized with the requested username, password, organization and bucket |
| `GetDbInstance` | Returns the instance, including the reachable endpoint and port once it is AVAILABLE |
| `ListDbInstances` | Lists standalone and cluster instances with pagination |
| `UpdateDbInstance` | Updates instance settings; a new parameter group recreates the container with its InfluxDB options |
| `RebootDbInstance` | Restarts the backing container |
| `DeleteDbInstance` | Stops and removes the container and its volumes |
| `CreateDbCluster` | Creates a read replica cluster backed by one InfluxDB 2.x container, or an InfluxDB 3 cluster record |
| `GetDbCluster` | Returns the cluster, including writer and reader endpoints |
| `ListDbClusters` | Lists clusters with pagination |
| `ListDbInstancesForCluster` | Lists the nodes of a cluster with their instance modes |
| `UpdateDbCluster` | Updates cluster settings and applies them to every node |
| `RebootDbCluster` | Restarts the cluster container; `instanceIds` must name nodes of the cluster |
| `DeleteDbCluster` | Removes the cluster, its nodes, and its container |
| `CreateDbParameterGroup` | Creates an InfluxDB v2, v3 Core or v3 Enterprise parameter group |
| `GetDbParameterGroup` | Returns a parameter group and its parameters |
| `ListDbParameterGroups` | Lists parameter groups with pagination |
| `CreateDbBackup` | Takes an on-demand backup with `influx backup` inside the container |
| `GetDbBackup` | Returns a backup and the resource configuration captured with it |
| `ListDbBackups` | Lists backups, optionally filtered by `dbResourceId` |
| `DeleteDbBackup` | Deletes a backup and its stored files |
| `RestoreFromDbBackup` | Restores a backup into a new resource or into the existing one with `influx restore --full` |
| `ListTagsForResource` | Lists tags on an instance, cluster, parameter group or backup |
| `TagResource` | Adds tags, up to 200 per resource |
| `UntagResource` | Removes tags by key |
<!-- floci:actions:end -->

## Data plane

- `CreateDbInstance` returns `CREATING`. Floci starts the configured image with
  `DOCKER_INFLUXDB_INIT_MODE=setup` and moves the instance to `AVAILABLE` once `GET /health` answers. If the
  container fails to start or never becomes healthy, the instance becomes `FAILED`.
- The InfluxDB HTTP listener (container port 8086) is published on a host port from
  `host-port-base`..`host-port-max`. The returned `port` is that published port, so `UpdateDbInstance` and
  `UpdateDbCluster` store a requested `port` but the reachable port stays the published one.
- `organization`, `bucket` and `username` are optional in the API but required by InfluxDB setup. When they
  are omitted Floci uses `default`, `default` and `admin`.
- The initial authorization parameters are stored in a Secrets Manager secret named
  `READONLY-InfluxDB-auth-parameters-<id>` with the keys `organization`, `bucket`, `username` and `password`.
  `influxAuthParametersSecretArn` is returned once the resource leaves `CREATING`.
- InfluxDB v2 parameter group values are passed to the container as the matching `INFLUXD_*` environment
  variables. Assigning a different parameter group recreates the container on the same volumes.
- Instance types, storage types and allocated storage are recorded but do not limit the container.
- Data and the influx CLI configuration live on per-resource Docker volumes (or host directories when
  `FLOCI_STORAGE_HOST_PERSISTENT_PATH` is an absolute path). After a Floci restart, persisted instances and
  clusters get their containers back without running setup again.
- When no Docker daemon is reachable, resources still reach `AVAILABLE` without a container so the
  management API keeps working.

## Clusters

- An InfluxDB v2 read replica cluster (`MULTI_NODE_READ_REPLICAS`) has a `PRIMARY` and a `REPLICA` node. Both
  nodes, the writer endpoint and the reader endpoint are served by one InfluxDB 2.x container: open source
  InfluxDB has no read replica replication, so replication and failover are not emulated.
- A cluster created without `password` has no container, because InfluxDB setup needs one.
- InfluxDB 3 Core and Enterprise clusters are management records only. Nodes follow the parameter group
  (`ingestQueryInstances`, `queryOnlyInstances`, `dedicatedCompactor`) and the default port is 8181, but no
  InfluxDB 3 container is started.
- Nodes can't be updated or deleted individually; use the cluster operations.

## Backups

- `CreateDbBackup` runs `influx backup` inside the container, copies the result to
  `<persistent-path>/timestream-influxdb/backups`, and moves the backup from `IN_PROGRESS` to `COMPLETED`
  (or `FAILED`).
- `RestoreFromDbBackup` with `NEW_RESOURCE` starts a new container and runs `influx restore --full`, so the
  restored resource has the users, tokens, organizations and buckets of the source. `REPLACE_EXISTING`
  restores into the existing container and requires `name` to match the existing resource.
- `restoreToTime` is accepted but ignored: only on-demand backups exist, so there is no point-in-time log.
- `dbBackupConfigurations` are validated and stored; automated backups are not scheduled.

## Configuration

| Variable | Default | Description |
| --- | --- | --- |
| `FLOCI_SERVICES_TIMESTREAM_INFLUXDB_ENABLED` | `true` | Enable or disable the service |
| `FLOCI_SERVICES_TIMESTREAM_INFLUXDB_MOCK` | `false` | `true` keeps metadata only, without containers |
| `FLOCI_SERVICES_TIMESTREAM_INFLUXDB_DEFAULT_IMAGE` | `influxdb:2.7` | InfluxDB 2.x image for DB instances |
| `FLOCI_SERVICES_TIMESTREAM_INFLUXDB_HOST_PORT_BASE` | `8086` | Lowest published host port |
| `FLOCI_SERVICES_TIMESTREAM_INFLUXDB_HOST_PORT_MAX` | `8185` | Highest published host port |
| `FLOCI_SERVICES_TIMESTREAM_INFLUXDB_READINESS_TIMEOUT_SECONDS` | `120` | Time to wait for the container health check |
| `FLOCI_SERVICES_TIMESTREAM_INFLUXDB_DOCKER_NETWORK` | unset | Docker network for InfluxDB containers |

## Example

```bash
export AWS_ENDPOINT_URL=http://localhost:4566

id=$(aws timestream-influxdb create-db-instance \
  --name metrics-db \
  --username admin --password password123 \
  --organization acme --bucket metrics \
  --db-instance-type db.influx.medium --allocated-storage 20 \
  --vpc-subnet-ids subnet-abc123 --vpc-security-group-ids sg-abc123 \
  --query id --output text)

aws timestream-influxdb get-db-instance --identifier "$id" \
  --query "[status,endpoint,port]"

influx config create --config-name floci --active \
  --host-url "http://localhost:<port>" --org acme --username-password admin:password123
influx write --bucket metrics "cpu,host=a value=42"
```
