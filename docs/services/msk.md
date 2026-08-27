# MSK (Managed Streaming for Kafka)

**Protocol:** REST-JSON
**Endpoint:** `http://localhost:4566/`

Floci emulates Amazon MSK by orchestrating **Redpanda** containers. This provides high compatibility with the Kafka API while maintaining a low footprint.

## Supported Actions

| Action | Description |
|---|---|
| `CreateCluster` | Spawns a new Redpanda container for the cluster |
| `CreateClusterV2` | Create a provisioned or serverless cluster |
| `ListClusters` | List all emulated clusters |
| `ListClustersV2` | List all emulated clusters using V2 API |
| `DescribeCluster` | Get cluster metadata and state |
| `DescribeClusterV2` | Get cluster metadata and state using V2 API |
| `DeleteCluster` | Stops and removes the Redpanda container |
| `GetBootstrapBrokers` | Get the connection strings for the cluster |
| `CreateConfiguration` | Create a broker configuration (`server.properties`) |
| `ListConfigurations` | List all configurations |
| `DescribeConfiguration` | Get configuration metadata and latest revision |
| `DeleteConfiguration` | Delete a configuration |
| `UpdateConfiguration` | Create a new revision of a configuration |
| `ListConfigurationRevisions` | List all revisions of a configuration |
| `DescribeConfigurationRevision` | Get a specific revision, including its `server.properties` |
| `ListTagsForResource` | List the tags on a cluster or configuration |
| `TagResource` | Add tags to a cluster or configuration |
| `UntagResource` | Remove tags from a cluster or configuration |

## Configuration

| Variable | Default | Description |
|---|---|---|
| `FLOCI_SERVICES_MSK_ENABLED` | `true` | Enable or disable the service |
| `FLOCI_SERVICES_MSK_MOCK` | `false` | `true` = metadata-only CRUD, no Docker containers |
| `FLOCI_SERVICES_MSK_DEFAULT_IMAGE` | `redpandadata/redpanda:latest` | Docker image for Redpanda (Kafka) containers |

## How it works

When `mock` is set to `false` (default), Floci uses the Docker API to start a Redpanda container for each created cluster. For Docker socket setup, private registry authentication, and other Docker settings see [Docker Configuration](../configuration/docker.md).

- **Port Mapping**: The Kafka API (9092) is mapped to a dynamic host port.
- **Persistence**: Each cluster gets a named Docker volume (`floci-msk-{volumeId}`). In memory mode the volume is removed on cluster delete; in persistent modes it is retained unless `FLOCI_STORAGE_PRUNE_VOLUMES_ON_DELETE=true`.
- **Readiness**: The cluster state transitions to `ACTIVE` once the Redpanda `/ready` endpoint is reachable.

## Cluster metadata

`CreateCluster` and `CreateClusterV2` persist the metadata you pass — broker node group
(instance type, subnets, security groups, storage, connectivity), number of broker nodes,
encryption, client authentication, enhanced monitoring, open monitoring, logging,
configuration, storage mode, rebalancing and tags —
and echo it back from `DescribeCluster`/`DescribeClusterV2` and the matching `List` calls.

The two API versions return different shapes, matching AWS:

- **v1** (`DescribeCluster`) returns a flat `ClusterInfo`: `brokerNodeGroupInfo`,
  `encryptionInfo`, `clientAuthentication`, `enhancedMonitoring`, `loggingInfo`,
  `openMonitoring`, `storageMode`, `rebalancing`, `numberOfBrokerNodes` and
  `zookeeperConnectString` all sit directly on the cluster.
- **v2** (`DescribeClusterV2`) nests all of those under `ClusterInfo.Provisioned`, alongside a
  top-level `ClusterType` of `PROVISIONED`. `ClusterArn`, `ClusterName`, `State`,
  `CreationTime`, `CurrentVersion` and `Tags` stay top-level.

Two details worth knowing:

- `configurationInfo` is a *request-only* member. The configuration a cluster was created
  with is reported back on `currentBrokerSoftwareInfo` as `configurationArn` and
  `configurationRevision`, which is where the Terraform AWS provider reads it from.
- Members AWS defaults server-side are defaulted here too, so a `terraform plan` converges:
  `enhancedMonitoring` becomes `DEFAULT`, and `encryptionInfo.encryptionInTransit` becomes
  `clientBroker: TLS_PLAINTEXT` / `inCluster: true` when the request omits them.

### Serverless clusters

`CreateClusterV2` accepts a `serverless` member as well as `provisioned` — exactly one of the
two, as on AWS. A serverless cluster stores its `vpcConfigs` and `clientAuthentication`, reports
`clusterType: SERVERLESS`, and comes back from `DescribeClusterV2` under a `serverless` envelope
rather than a `provisioned` one. It is backed by the same emulated Kafka endpoint, so
`GetBootstrapBrokers` works normally.

The v1 API predates serverless and its `ClusterInfo` cannot represent one, so — as on AWS —
`DescribeCluster` on a serverless cluster returns `BadRequestException` pointing you at
`DescribeClusterV2`, and `ListClusters` omits serverless clusters entirely.

### Validation

`CreateCluster`/`CreateClusterV2` reject the values AWS rejects: a missing or over-64-character
`clusterName`, a `numberOfBrokerNodes` below 1, an EBS `volumeSize` outside 1–16384, a
`configurationInfo.revision` below 1, and any unrecognised `enhancedMonitoring`, `storageMode`,
`rebalancing.status` or `encryptionInTransit.clientBroker` value. Each 400 names the offending
member in `invalidParameter`, as MSK's `Error` schema does.

There is deliberately **no** upper bound on `numberOfBrokerNodes`. The SDK model caps it at 15,
but the REST API reference documents no maximum for it — on a page that gives ranges for its
neighbours — and the quota page allows 30 brokers per ZooKeeper cluster and 60 per KRaft
cluster, both adjustable. A 30-broker cluster is an ordinary thing to have.

Unlike AWS, the *presence* of `kafkaVersion`, `numberOfBrokerNodes` and `brokerNodeGroupInfo` is
not required — the emulator defaults them so that a minimal create request still works.

### Tags

Cluster and configuration tags are managed through `/v1/tags/{resourceArn}`
(`ListTagsForResource`, `TagResource`, `UntagResource`). Cluster tags also round-trip through
`DescribeCluster`/`DescribeClusterV2`, so a tag change is visible to a refresh either way.
Configuration tags are reachable only through the tag endpoints, because AWS's
`DescribeConfiguration` response has no tags member.

## Examples

```bash
export AWS_ENDPOINT_URL=http://localhost:4566

# Create a cluster
aws kafka create-cluster \
  --cluster-name my-cluster \
  --kafka-version "3.6.1" \
  --number-of-broker-nodes 1 \
  --broker-node-group-info '{"InstanceType":"kafka.m5.large","ClientSubnets":["subnet-1"]}' \
  --endpoint-url $AWS_ENDPOINT_URL

# List clusters
aws kafka list-clusters --endpoint-url $AWS_ENDPOINT_URL

# Get bootstrap brokers
CLUSTER_ARN=$(aws kafka list-clusters --query 'ClusterInfoList[0].ClusterArn' --output text --endpoint-url $AWS_ENDPOINT_URL)
aws kafka get-bootstrap-brokers --cluster-arn $CLUSTER_ARN --endpoint-url $AWS_ENDPOINT_URL

# Delete a cluster
aws kafka delete-cluster --cluster-arn $CLUSTER_ARN --endpoint-url $AWS_ENDPOINT_URL
```
