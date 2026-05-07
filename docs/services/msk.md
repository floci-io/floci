# MSK (Managed Streaming for Kafka)

**Protocol:** REST-JSON
**Endpoint:** `http://localhost:4566/`

Floci emulates Amazon MSK by orchestrating **Redpanda** containers. This provides high compatibility with the Kafka API while maintaining a low footprint.

## Supported Actions

<!-- floci:actions:start -->
| Action |
| --- |
| `CreateCluster` |
| `CreateClusterV2` |
| `ListClusters` |
| `ListClustersV2` |
| `DescribeCluster` |
| `DescribeClusterV2` |
| `DeleteCluster` |
| `GetBootstrapBrokers` |
<!-- floci:actions:end -->

## Configuration

```yaml
floci:
  services:
    msk:
      enabled: true
      mock: false  # Set to true for metadata-only CRUD (no Docker)
      default-image: "redpandadata/redpanda:latest"
```

## How it works

When `mock` is set to `false` (default), Floci uses the Docker API to start a Redpanda container for each created cluster. For Docker socket setup, private registry authentication, and other Docker settings see [Docker Configuration](../configuration/docker.md).

- **Port Mapping**: The Kafka API (9092) is mapped to a dynamic host port.
- **Persistence**: Data is stored in the Floci persistent path under `msk/<cluster-name>`.
- **Readiness**: The cluster state transitions to `ACTIVE` once the Redpanda `/ready` endpoint is reachable.

## Examples

```bash
export AWS_ENDPOINT_URL=http://localhost:4566

# Create a cluster
aws kafka create-cluster \
  --cluster-name my-cluster \
  --kafka-version "3.6.1" \
  --numberOfBrokerNodes 1 \
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
