# OpenSearch Service

**Protocol:** REST JSON  
**Endpoint:** `http://localhost:4566/2021-01-01/...`  
**Credential scope:** `es`

## Implementation Modes

OpenSearch supports two modes controlled by `FLOCI_SERVICES_OPENSEARCH_MOCK`.

### Mock mode (`mock: true`)

Domain metadata is stored in-process. No Docker containers are started. Domains appear `Created: true` and `Processing: false` immediately. Use this in CI or whenever you only need the management API shape, not a real search cluster.

### Real mode (`mock: false`, default)

Floci starts an **OpenSearch** Docker container per domain, choosing the image based on the requested `EngineVersion` (e.g. `OpenSearch_3.6` → `opensearchproject/opensearch:3.6.0`, `Elasticsearch_7.10` → `docker.elastic.co/elasticsearch/elasticsearch-oss:7.10.2`). The container is exposed on a host port from the configured range (`9400–9499`). Once `/_cluster/health` returns `green` or `yellow`, the domain transitions to `Created: true` and the `Endpoint` field is populated with the container's address.

OpenSearch 2.12+ requires an initial admin password even when the security plugin is disabled; Floci sets `OPENSEARCH_INITIAL_ADMIN_PASSWORD=FlociAdmin1!` automatically for those versions. The security plugin itself stays disabled.

!!! note "Docker socket required"
    Real mode starts Docker containers. Mount the Docker socket and set the Docker network so containers can reach each other. For private registry authentication and other Docker settings see [Docker Configuration](../configuration/docker.md).

```yaml
services:
  floci:
    image: floci/floci:latest
    volumes:
      - /var/run/docker.sock:/var/run/docker.sock
    ports:
      - "4566:4566"
    environment:
      FLOCI_SERVICES_DOCKER_NETWORK: my_project_default
```

## Supported Operations

### Domain Lifecycle

| Operation | Method + Path | Description |
|---|---|---|
| `CreateDomain` | `POST /2021-01-01/opensearch/domain` | Create a new domain |
| `DescribeDomain` | `GET /2021-01-01/opensearch/domain/{name}` | Get domain details |
| `DescribeDomains` | `POST /2021-01-01/opensearch/domain-info` | Batch describe domains |
| `DescribeDomainConfig` | `GET /2021-01-01/opensearch/domain/{name}/config` | Get domain configuration |
| `UpdateDomainConfig` | `POST /2021-01-01/opensearch/domain/{name}/config` | Update cluster config, EBS options, engine version |
| `DeleteDomain` | `DELETE /2021-01-01/opensearch/domain/{name}` | Delete a domain |
| `ListDomainNames` | `GET /2021-01-01/domain` | List all domains (supports `?engineType=` filter) |

### Tags

| Operation | Method + Path | Description |
|---|---|---|
| `AddTags` | `POST /2021-01-01/tags` | Add tags to a domain by ARN |
| `ListTags` | `GET /2021-01-01/tags/?arn=` | List tags for a domain |
| `RemoveTags` | `POST /2021-01-01/tags-removal` | Remove tag keys from a domain |

### Versions & Instance Types

| Operation | Method + Path | Description |
|---|---|---|
| `ListVersions` | `GET /2021-01-01/opensearch/versions` | List supported engine versions |
| `GetCompatibleVersions` | `GET /2021-01-01/opensearch/compatibleVersions` | List valid upgrade paths |
| `ListInstanceTypeDetails` | `GET /2021-01-01/opensearch/instanceTypeDetails/{version}` | List available instance types |
| `DescribeInstanceTypeLimits` | `GET /2021-01-01/opensearch/instanceTypeLimits/{version}/{type}` | Get limits for an instance type |

### Stubs (SDK-compatible, no-op responses)

| Operation | Notes |
|---|---|
| `DescribeDomainChangeProgress` | Returns empty `ChangeProgressStatus` |
| `DescribeDomainAutoTunes` | Returns empty `AutoTunes` list |
| `DescribeDryRunProgress` | Returns empty `DryRunProgressStatus` |
| `DescribeDomainHealth` | Returns `ClusterHealth: Green` |
| `GetUpgradeHistory` | Returns empty list |
| `GetUpgradeStatus` | Returns `StepStatus: SUCCEEDED` |
| `UpgradeDomain` | Stores new engine version, returns immediately with a generated `UpgradeId` |
| `CancelDomainConfigChange` | Returns empty `CancelledChangeIds` |
| `StartServiceSoftwareUpdate` | Returns no-op `ServiceSoftwareOptions` |
| `CancelServiceSoftwareUpdate` | Returns no-op `ServiceSoftwareOptions` |

## Configuration

| Variable | Default | Description |
|---|---|---|
| `FLOCI_SERVICES_OPENSEARCH_ENABLED` | `true` | Enable/disable the service |
| `FLOCI_SERVICES_OPENSEARCH_MOCK` | `false` | `true` = metadata only (no Docker) |
| `FLOCI_SERVICES_OPENSEARCH_DEFAULT_IMAGE` | *(unset)* | Optional fixed image used for every domain regardless of `EngineVersion`. Useful for private registry mirrors. When unset, images resolve per-version from the built-in version map. |
| `FLOCI_SERVICES_OPENSEARCH_PROXY_BASE_PORT` | `9400` | Port range start for real mode |
| `FLOCI_SERVICES_OPENSEARCH_PROXY_MAX_PORT` | `9499` | Port range end for real mode |
| `FLOCI_SERVICES_OPENSEARCH_KEEP_RUNNING_ON_SHUTDOWN` | `false` | Leave containers running after Floci stops |
| `FLOCI_SERVICES_DOCKER_NETWORK` | *(unset)* | Shared Docker network for all container-based services including OpenSearch |
| `FLOCI_STORAGE_SERVICES_OPENSEARCH_FLUSH_INTERVAL_MS` | `5000` | Flush interval (ms) |

### Mock mode (CI / tests)

Use `FLOCI_SERVICES_OPENSEARCH_MOCK=true` when you only need the API shape:

```yaml
# docker-compose.yml — CI / test environment
services:
  floci:
    image: floci/floci:latest
    environment:
      FLOCI_SERVICES_OPENSEARCH_MOCK: "true"
```

## Emulation Behaviour

- **Domain name validation:** 3–28 characters, must start with a lowercase letter, only lowercase letters, digits, and hyphens.
- **ARN format:** `arn:aws:es:{region}:{accountId}:domain/{domainName}`
- **Domain ID format:** `{accountId}/{domainName}`
- **`Created` flag:** `true` immediately in mock mode; set to `true` by the readiness poller in real mode once `/_cluster/health` reports `green` or `yellow`.
- **`Processing` flag:** `false` immediately in mock mode; `true` until the container is ready in real mode.
- **Engine version default:** `OpenSearch_2.19`
- **Supported engine versions:** `OpenSearch_3.6`, `OpenSearch_3.5`, `OpenSearch_3.4`, `OpenSearch_3.3`, `OpenSearch_3.2`, `OpenSearch_3.1`, `OpenSearch_3.0`, `OpenSearch_2.19`, `OpenSearch_2.17`, `OpenSearch_2.15`, `OpenSearch_2.13`, `OpenSearch_2.11`, `OpenSearch_2.9`, `OpenSearch_2.7`, `OpenSearch_2.5`, `OpenSearch_2.3`, `OpenSearch_1.3`, `OpenSearch_1.2`, `Elasticsearch_7.10`, `Elasticsearch_7.9`, `Elasticsearch_7.8`
- **Version validation:** `CreateDomain`, `UpdateDomainConfig`, and `UpgradeDomain` reject unknown engine versions with `ValidationException`. `UpgradeDomain` also rejects targets that aren't reachable from the current version per AWS's documented upgrade matrix.
- **Cluster defaults:** `m5.large.search`, 1 instance, EBS enabled with 10 GiB `gp2` volume.
- **Container storage:** each domain gets a named Docker volume (`floci-opensearch-{volumeId}`) created automatically. In memory mode the volume is removed on domain delete; in persistent modes it is retained unless `FLOCI_STORAGE_PRUNE_VOLUMES_ON_DELETE=true`.

## Examples

```bash
export AWS_ENDPOINT_URL=http://localhost:4566
export AWS_DEFAULT_REGION=us-east-1
export AWS_ACCESS_KEY_ID=test
export AWS_SECRET_ACCESS_KEY=test

# Create a domain
aws opensearch create-domain \
  --domain-name my-search \
  --engine-version "OpenSearch_2.11" \
  --cluster-config InstanceType=m5.large.search,InstanceCount=1 \
  --ebs-options EBSEnabled=true,VolumeType=gp2,VolumeSize=10

# Describe the domain
aws opensearch describe-domain --domain-name my-search

# List all domains
aws opensearch list-domain-names

# Update cluster config
aws opensearch update-domain-config \
  --domain-name my-search \
  --cluster-config InstanceCount=3

# Add tags
aws opensearch add-tags \
  --arn arn:aws:es:us-east-1:000000000000:domain/my-search \
  --tag-list Key=env,Value=dev

# List tags
aws opensearch list-tags \
  --arn arn:aws:es:us-east-1:000000000000:domain/my-search

# Delete domain
aws opensearch delete-domain --domain-name my-search
```

## SDK Example (Java)

```java
OpenSearchClient os = OpenSearchClient.builder()
    .endpointOverride(URI.create("http://localhost:4566"))
    .region(Region.US_EAST_1)
    .credentialsProvider(StaticCredentialsProvider.create(
        AwsBasicCredentials.create("test", "test")))
    .build();

// Create a domain
CreateDomainResponse created = os.createDomain(req -> req
    .domainName("my-search")
    .engineVersion("OpenSearch_2.11")
    .clusterConfig(c -> c
        .instanceType(OpenSearchPartitionInstanceType.M5_LARGE_SEARCH)
        .instanceCount(1))
    .ebsOptions(e -> e
        .ebsEnabled(true)
        .volumeType(VolumeType.GP2)
        .volumeSize(10)));

System.out.println("ARN: " + created.domainStatus().arn());

// Wait for domain to be ready (real mode)
// created.domainStatus().created() == true when ready

// Describe the domain
DescribeDomainResponse desc = os.describeDomain(req -> req
    .domainName("my-search"));

System.out.println("Version: " + desc.domainStatus().engineVersion());
System.out.println("Endpoint: " + desc.domainStatus().endpoint());

// List domains
os.listDomainNames(req -> req.build())
    .domainNames()
    .forEach(d -> System.out.println(d.domainName()));

// Delete
os.deleteDomain(req -> req.domainName("my-search"));
```

## SDK Example (Python)

```python
import boto3

os_client = boto3.client(
    "opensearch",
    endpoint_url="http://localhost:4566",
    region_name="us-east-1",
    aws_access_key_id="test",
    aws_secret_access_key="test"
)

# Create a domain
response = os_client.create_domain(
    DomainName="my-search",
    EngineVersion="OpenSearch_2.11",
    ClusterConfig={"InstanceType": "m5.large.search", "InstanceCount": 1},
    EBSOptions={"EBSEnabled": True, "VolumeType": "gp2", "VolumeSize": 10}
)
print(response["DomainStatus"]["ARN"])

# List domains
domains = os_client.list_domain_names()
for d in domains["DomainNames"]:
    print(d["DomainName"])

# Delete
os_client.delete_domain(DomainName="my-search")
```

## Limitations

- In mock mode, no data-plane endpoints (`/_search`, `/_index`, etc.) are served — only the management API is emulated.
- No Elasticsearch-compatible management endpoints (`/2015-01-01/es/domain/...`).
- VPC options, fine-grained access control, encryption-at-rest, and cross-cluster connections are accepted in the request but silently ignored.
- All unsupported operations (VPC endpoints, reserved instances, packages, applications, data sources) return `UnsupportedOperationException`.
