# EKS (Elastic Kubernetes Service)

**Protocol:** REST-JSON  
**Endpoint:** `http://localhost:4566/` (path-routed via JAX-RS)

EKS uses a standard REST API with JSON bodies — not the JSON 1.1 (`X-Amz-Target`) or Query protocol.

## Supported Operations

| Operation | Description |
|---|---|
| `CreateCluster` | Create a new EKS cluster |
| `DescribeCluster` | Describe a cluster by name |
| `ListClusters` | List all cluster names |
| `DeleteCluster` | Delete a cluster |
| `CreateNodegroup` | Create node group metadata for a cluster |
| `DescribeNodegroup` | Describe a node group by cluster and name |
| `ListNodegroups` | List node group names for a cluster |
| `DeleteNodegroup` | Delete a node group |
| `CreateFargateProfile` | Create Fargate profile metadata for a cluster |
| `DescribeFargateProfile` | Describe a Fargate profile by cluster and name |
| `ListFargateProfiles` | List Fargate profile names for a cluster |
| `DeleteFargateProfile` | Delete a Fargate profile |
| `TagResource` | Add tags to a cluster |
| `UntagResource` | Remove tags from a cluster |
| `ListTagsForResource` | List tags on a cluster |

## Modes

### Mock mode (`mock: true`)

Cluster metadata is stored in-process. No Docker containers are started. The cluster transitions directly to `ACTIVE` on creation. Use this in CI or whenever you only need the EKS API shape, not a real Kubernetes API server.

### Real mode (`mock: false`, default)

Floci starts a **k3s** (`rancher/k3s`) container for each cluster. The k3s API server is exposed on a host port from the configured range (`6500–6599`). Once `/readyz` responds, the cluster transitions to `ACTIVE` and the CA certificate is extracted from the kubeconfig.

By default `describe-cluster` returns a **host-reachable** endpoint (`https://localhost:<hostPort>`); the k3s server certificate includes a `localhost` SAN, so it verifies against the CA in `cluster.certificateAuthority.data`. Set `endpoint-mode: network` to return the container DNS name (`https://floci-eks-<name>:6443`) instead — reachable from other containers on the Docker network (the pre-#1118 behaviour). In `network` mode the endpoint falls back to the host-reachable form when Floci runs natively, since there is no container DNS name a host client could use.

#### Connecting with `kubectl` (native AWS workflow)

The standard AWS flow works end to end:

```bash
aws eks update-kubeconfig --name my-cluster
kubectl get nodes
```

`aws eks update-kubeconfig` wires `aws eks get-token` into the kubeconfig as an exec credential. The bearer token it produces is validated by a **token-authentication webhook** that Floci wires into k3s: the k3s API server POSTs a Kubernetes `TokenReview` to Floci's `/_floci/eks/token-webhook` endpoint, and Floci maps the token to the `system:masters` group (bound to `cluster-admin`). No `aws-iam-authenticator` is required.

This webhook is enabled by default (`iam-auth-webhook: true`). Set it to `false` to start k3s without it (in which case `aws eks get-token` tokens are rejected with `401`).

!!! note "Webhook reachability & networking"
    The k3s API server must be able to reach Floci's webhook URL. When Floci runs natively, k3s containers reach it via `host.docker.internal`; when Floci runs in a container (`floci start`), Floci and the k3s containers share a Docker network. The k3s network is taken from `FLOCI_SERVICES_EKS_DOCKER_NETWORK` if set, otherwise the global `FLOCI_SERVICES_DOCKER_NETWORK`, otherwise the network Floci is itself attached to (auto-detected) — so no EKS-specific network configuration is required in the standard compose setup.

    The webhook kubeconfig is copied into the k3s container via the Docker API (not bind-mounted), so the token-webhook works the same in native and Docker-in-Docker modes with **no host-path / `host-persistent-path` configuration**.

!!! note "Docker socket required"
    Real mode starts privileged Docker containers. Mount the Docker socket and set the Docker network so containers can reach each other.

```yaml
services:
  floci:
    image: floci/floci:latest
    volumes:
      - /var/run/docker.sock:/var/run/docker.sock
    ports:
      - "4566:4566"
    environment:
      FLOCI_SERVICES_EKS_DOCKER_NETWORK: my_project_default
```

!!! note "No port mapping needed for k3s ports"
    k3s containers bind their API server port (6500–6599) directly on the host via Docker — no `ports:` entry is required in `docker-compose.yml`. See [Ports Reference](../configuration/ports.md#ports-65006599-eks-real-mode) for the full explanation.

## Configuration

| Variable | Default | Description |
|---|---|---|
| `FLOCI_SERVICES_EKS_ENABLED` | `true` | Enable the EKS service |
| `FLOCI_SERVICES_EKS_MOCK` | `false` | Metadata-only mode (no Docker) |
| `FLOCI_SERVICES_EKS_DEFAULT_IMAGE` | `rancher/k3s:latest` | k3s Docker image |
| `FLOCI_SERVICES_EKS_API_SERVER_BASE_PORT` | `6500` | First port in the k3s API server range |
| `FLOCI_SERVICES_EKS_API_SERVER_MAX_PORT` | `6599` | Last port in the k3s API server range |
| `FLOCI_SERVICES_EKS_DATA_PATH` | `./data/eks` | Host bind-mount root for cluster data |
| `FLOCI_SERVICES_EKS_DOCKER_NETWORK` | *(unset)* | Docker network for k3s containers (falls back to the global `FLOCI_SERVICES_DOCKER_NETWORK`, then Floci's own network) |
| `FLOCI_SERVICES_EKS_KEEP_RUNNING_ON_SHUTDOWN` | `false` | Leave k3s containers running after Floci stops |
| `FLOCI_SERVICES_EKS_ENDPOINT_MODE` | `host` | `describe-cluster` endpoint: `host` (`localhost:<hostPort>`) or `network` (container DNS) |
| `FLOCI_SERVICES_EKS_IAM_AUTH_WEBHOOK` | `true` | Wire a token-auth webhook into k3s so `aws eks get-token` works |
| `FLOCI_SERVICES_EKS_ECR_REGISTRY_MIRROR` | `true` | Inject a containerd `registries.yaml` so pods can pull images pushed to [Floci ECR](ecr.md) |

### Pulling images from Floci ECR

Images pushed to the [Floci ECR registry](ecr.md) use `localhost`-based repository URIs
(for example `000000000000.dkr.ecr.us-east-1.localhost:5100/my-repo:tag`). Inside a k3s
cluster that hostname would resolve to the k3s container itself, and containerd insists
on HTTPS for anything it doesn't recognize as loopback — so, out of the box, k3s cannot
pull from the registry even though `docker push` from the host works.

Floci solves this at cluster creation: each new k3s container gets a generated
`/etc/rancher/k3s/registries.yaml` that mirrors every repository hostname the emulator
can mint — the default account across the full region catalog, plus the path-style
`localhost:<port>` form used by `FLOCI_SERVICES_ECR_URI_STYLE=path` — to the registry
container's in-network endpoint (`http://floci-ecr-registry:5000`). The same image
reference then works for the host-side push and the in-cluster pull, with no retagging
and no manual containerd configuration:

```bash
aws ecr create-repository --repository-name my-repo
docker build -t 000000000000.dkr.ecr.us-east-1.localhost:5100/my-repo:v1 .
docker push 000000000000.dkr.ecr.us-east-1.localhost:5100/my-repo:v1

aws eks create-cluster --name demo ...
helm install my-app ./chart \
  --set image.repository=000000000000.dkr.ecr.us-east-1.localhost:5100/my-repo \
  --set image.tag=v1
```

Requirements and limits:

- The k3s and registry containers must share a Docker network — set the global
  `FLOCI_SERVICES_DOCKER_NETWORK` (as in the standard `docker-compose.yml`) or the
  per-service network variables.
- Only Floci-mintable hostnames are mirrored; public registries (docker.io, ghcr.io, …)
  are never touched.
- Repository URIs using a non-default `registryId` (account) are not covered.
- The mirror set is snapshotted when the cluster is created. Clusters created before this
  feature (or after the registry was re-created on a different port) can be fixed
  manually — the k3s container filesystem survives a restart:

  ```bash
  docker cp registries.yaml floci-eks-<cluster>:/etc/rancher/k3s/registries.yaml
  docker restart floci-eks-<cluster>
  ```

### Mock mode (CI / tests)

Use `FLOCI_SERVICES_EKS_MOCK=true` when you only need the API shape:

```yaml
# docker-compose.yml — CI / test environment
services:
  floci:
    image: floci/floci:latest
    environment:
      FLOCI_SERVICES_EKS_MOCK: "true"
```

## ARN Format

```
arn:aws:eks:<region>:<accountId>:cluster/<clusterName>
```

Node groups use:

```
arn:aws:eks:<region>:<accountId>:nodegroup/<clusterName>/<nodegroupName>/<id>
```

Fargate profiles use:

```
arn:aws:eks:<region>:<accountId>:fargateprofile/<clusterName>/<fargateProfileName>/<id>
```

## Examples

```bash
export AWS_ENDPOINT_URL=http://localhost:4566
export AWS_DEFAULT_REGION=us-east-1
export AWS_ACCESS_KEY_ID=test
export AWS_SECRET_ACCESS_KEY=test

# Create a cluster
aws eks create-cluster \
  --name my-cluster \
  --role-arn arn:aws:iam::000000000000:role/eks-role \
  --resources-vpc-config subnetIds=[],securityGroupIds=[] \
  --kubernetes-version 1.29

# Describe the cluster
aws eks describe-cluster --name my-cluster

# List clusters
aws eks list-clusters

# Create a node group
curl -s -X POST "$AWS_ENDPOINT_URL/clusters/my-cluster/node-groups" \
  -H "Content-Type: application/json" \
  -d '{
    "nodegroupName": "my-nodegroup",
    "nodeRole": "arn:aws:iam::000000000000:role/eks-node-role",
    "subnets": ["subnet-123", "subnet-456"],
    "instanceTypes": ["t3.medium"],
    "scalingConfig": {
      "minSize": 1,
      "maxSize": 3,
      "desiredSize": 1
    }
  }'

# Describe the node group
curl -s "$AWS_ENDPOINT_URL/clusters/my-cluster/node-groups/my-nodegroup"

# List node groups
curl -s "$AWS_ENDPOINT_URL/clusters/my-cluster/node-groups"

# Delete the node group
curl -s -X DELETE "$AWS_ENDPOINT_URL/clusters/my-cluster/node-groups/my-nodegroup"

# Create a Fargate profile
curl -s -X POST "$AWS_ENDPOINT_URL/clusters/my-cluster/fargate-profiles" \
  -H "Content-Type: application/json" \
  -d '{
    "fargateProfileName": "my-fargate-profile",
    "podExecutionRoleArn": "arn:aws:iam::000000000000:role/eks-fargate-role",
    "subnets": ["subnet-123", "subnet-456"],
    "selectors": [
      {
        "namespace": "default",
        "labels": {
          "app": "api"
        }
      }
    ],
    "tags": {
      "env": "dev"
    }
  }'

# Describe the Fargate profile
curl -s "$AWS_ENDPOINT_URL/clusters/my-cluster/fargate-profiles/my-fargate-profile"

# List Fargate profiles
curl -s "$AWS_ENDPOINT_URL/clusters/my-cluster/fargate-profiles"

# Delete the Fargate profile
curl -s -X DELETE "$AWS_ENDPOINT_URL/clusters/my-cluster/fargate-profiles/my-fargate-profile"

# Tag a cluster
aws eks tag-resource \
  --resource-arn arn:aws:eks:us-east-1:000000000000:cluster/my-cluster \
  --tags env=dev,team=platform

# Delete a cluster
aws eks delete-cluster --name my-cluster
```

## Java SDK Example

```java
EksClient eks = EksClient.builder()
    .endpointOverride(URI.create("http://localhost:4566"))
    .region(Region.US_EAST_1)
    .credentialsProvider(StaticCredentialsProvider.create(
        AwsBasicCredentials.create("test", "test")))
    .build();

// Create cluster
CreateClusterResponse created = eks.createCluster(r -> r
    .name("my-cluster")
    .roleArn("arn:aws:iam::000000000000:role/eks-role")
    .resourcesVpcConfig(v -> v
        .subnetIds(List.of())
        .securityGroupIds(List.of()))
    .version("1.29")
    .tags(Map.of("env", "dev")));

// Describe cluster
DescribeClusterResponse described = eks.describeCluster(r -> r
    .name("my-cluster"));

System.out.println(described.cluster().status()); // ACTIVE

// List clusters
List<String> names = eks.listClusters(r -> {}).clusters();

// Node group and Fargate profile support are currently exposed through the REST paths.

// Tag resource
eks.tagResource(r -> r
    .resourceArn(created.cluster().arn())
    .tags(Map.of("team", "platform")));

// Delete cluster
eks.deleteCluster(r -> r.name("my-cluster"));
```

## Not Implemented (Phase 1)

The following EKS features are not yet supported:

- `UpdateClusterConfig` / `UpdateClusterVersion`
- Add-ons (`CreateAddon`, `DescribeAddon`, `ListAddons`)
- Identity provider configs
- Access entries and policies
- Encryption config
