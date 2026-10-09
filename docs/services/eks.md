# EKS (Elastic Kubernetes Service)

**Protocol:** REST-JSON  
**Endpoint:** `http://localhost:4566/` (path-routed via JAX-RS)

EKS uses a standard REST API with JSON bodies: not the JSON 1.1 (`X-Amz-Target`) or Query protocol.

## Supported Operations

| Operation | Description |
|---|---|
| `CreateCluster` | Create a new EKS cluster |
| `DescribeCluster` | Describe a cluster by name |
| `ListClusters` | List all cluster names |
| `DeleteCluster` | Delete a cluster |
| `CreateAccessEntry` | Create STANDARD or EC2_LINUX access-entry metadata |
| `DescribeAccessEntry` | Describe an access entry by IAM principal ARN |
| `ListAccessEntries` | List principal ARNs with pagination |
| `DeleteAccessEntry` | Delete access-entry metadata |
| `CreatePodIdentityAssociation` | Create a pod identity association between a service account and IAM role |
| `DescribePodIdentityAssociation` | Describe a pod identity association by association ID |
| `ListPodIdentityAssociations` | List pod identity associations in a cluster with optional filtering and pagination |
| `UpdatePodIdentityAssociation` | Update the IAM role, target role, or session tags for a pod identity association |
| `DeletePodIdentityAssociation` | Delete a pod identity association |
| `CreateAddon` | Create an addon for an EKS cluster |
| `DescribeAddon` | Describe an addon by cluster and addon name |
| `ListAddons` | List addon names installed in a cluster with pagination |
| `UpdateAddon` | Update addon configuration, version, or service account role |
| `DescribeUpdate` | Describe an update for an addon |
| `DeleteAddon` | Delete an addon from a cluster |
| `DescribeAddonVersions` | Describe supported addon versions by Kubernetes version or addon name |
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

## Access-entry management

`CreateCluster` accepts `accessConfig.authenticationMode` (`CONFIG_MAP`, `API_AND_CONFIG_MAP`, or `API`) and `bootstrapClusterCreatorAdminPermissions`. The API default is `CONFIG_MAP`; access-entry operations require an ACTIVE cluster created with `API` or `API_AND_CONFIG_MAP`.

The four access-entry management operations support `STANDARD` (the default) and `EC2_LINUX`. STANDARD accepts existing IAM users or roles, including principals in another account. EC2_LINUX requires a role in the cluster account and generates `system:node:{{EC2PrivateDNSName}}`; custom usernames and Kubernetes groups are not accepted for node entries. Tags supplied at creation are preserved. Repeating a create with the same client token and normalized parameters returns the existing entry. Listing accepts `maxResults` from 1 to 100 and cluster-specific `nextToken` values.

Access entries use EKS storage and retain the IAM principal's stable ID internally. Cluster deletion removes its entries, and a cluster recreated with the same name does not inherit previous entries or pagination tokens.

### EC2 Linux worker authentication

IMDS instance-profile credentials authenticate through a matching `EC2_LINUX` entry as
`system:node:<private-DNS-name>` with `system:bootstrappers` and `system:nodes` groups.
The webhook verifies the signed token, cluster account/region/incarnation, stored role ID,
running EC2 instance, and attached instance profile. Missing/deleted entries, recreated roles,
revoked sessions and terminated instances are rejected without falling back to administrator access.
The existing k3s webhook cache can retain a successful authentication for up to 30 seconds.

New cluster webhook configurations carry the target account, region and creation timestamp in the URL path.
Kubernetes client-go replaces server URL query parameters when sending TokenReview requests, so
worker scope must not depend on those parameters.
Recreate older local clusters before using worker authentication; a legacy unscoped webhook
rejects instance credentials. Obtain fresh IMDS credentials after upgrading so the session
includes the stable role ID.

!!! note "Authentication scope"
    Non-worker IAM users and ordinary STS sessions retain the existing cluster-admin compatibility behavior. STANDARD entries, access policies, aws-auth ConfigMap, the cluster-creator bootstrap flag and automatic managed-node entries are not enforced by this change. Updating authentication mode, UpdateAccessEntry, access-policy association, and entry tag updates remain unimplemented. Native AL2023 images, bootstrap RBAC/CSR approval, CNI and worker networking are separate requirements for registration and Ready.

```bash
aws --endpoint-url http://localhost:4566 eks create-cluster \
  --name local-nodes --role-arn arn:aws:iam::000000000000:role/cluster \
  --resources-vpc-config '{}' \
  --access-config authenticationMode=API,bootstrapClusterCreatorAdminPermissions=false
# The IAM role must already exist.
aws --endpoint-url http://localhost:4566 eks create-access-entry \
  --cluster-name local-nodes --principal-arn arn:aws:iam::000000000000:role/worker \
  --type EC2_LINUX
```

## Pod Identity Associations

Floci supports the EKS Pod Identity association management plane. You can map a Kubernetes `(namespace, serviceAccount)` pair directly to an IAM role without configuring OpenID Connect (OIDC) identity providers or mutating ServiceAccount annotations.

### Management Plane Operations

- **Creation**: `CreatePodIdentityAssociation` associates a Kubernetes service account in a specific namespace with an IAM role ARN. The referenced IAM role must exist in IAM. A cluster cannot have duplicate associations for the same `(namespace, serviceAccount)` pair (rejected with HTTP 409 `ResourceInUseException`). Requests support idempotency via `clientRequestToken`.
- **Retrieval**: `DescribePodIdentityAssociation` retrieves full association details by cluster name and association ID (`a-` followed by 17 alphanumeric characters).
- **Listing**: `ListPodIdentityAssociations` returns summaries of associations for a cluster, with optional filtering by `namespace` and `serviceAccount`, plus pagination using `maxResults` (1-100) and cluster-bound `nextToken`.
- **Updating**: `UpdatePodIdentityAssociation` modifies the associated `roleArn`, `targetRoleArn`, `disableSessionTags`, or `policy`.
- **Deletion**: `DeletePodIdentityAssociation` removes an association and returns its final metadata. Deleting an EKS cluster automatically purges all of its pod identity associations.

### Credential injection at admission

Floci registers a `MutatingWebhookConfiguration` in every real-mode cluster, scoped to pod `CREATE`. When a pod is created, Floci looks up an association matching the pod's namespace and its `spec.serviceAccountName`. A pod that matches nothing is admitted unchanged.

Injection happens only when the `eks-pod-identity-agent` addon is installed on the cluster, which is what real EKS requires too. Install it with `CreateAddon` before expecting pods to be mutated.

A matching pod is patched exactly the way real EKS patches it. Every init container and container gets:

| What | Value |
|---|---|
| `AWS_CONTAINER_CREDENTIALS_FULL_URI` | `http://169.254.170.23/v1/credentials` |
| `AWS_CONTAINER_AUTHORIZATION_TOKEN_FILE` | `/var/run/secrets/pods.eks.amazonaws.com/serviceaccount/eks-pod-identity-token` |
| Volume mount | `/var/run/secrets/pods.eks.amazonaws.com/serviceaccount`, read-only, from volume `eks-pod-identity-token` |

The volume is a projected service account token with audience `pods.eks.amazonaws.com`, `expirationSeconds: 86400` and path `eks-pod-identity-token`. A name the pod already carries (a volume, a mount, or either environment variable) is left as the workload set it.

### Credential endpoint

Floci serves pod identity credentials through a link-local relay bound to `169.254.170.23:80` inside the cluster container, forwarding requests to Floci's credentials endpoint (`GET /v1/credentials`). Workloads reach this endpoint using the injected `AWS_CONTAINER_CREDENTIALS_FULL_URI` environment variable, passing their projected token in the `Authorization` header (`Authorization: <token>` or `Authorization: Bearer <token>`).

The credentials endpoint performs the following validations:
1. Validates token format and extracts the issuer URL without verifying signatures yet.
2. Identifies the matching EKS cluster by comparing the token issuer URL to cluster OIDC issuer URLs.
3. Validates the token signature against the cluster OIDC public key, confirming the token is unexpired and carries audience `pods.eks.amazonaws.com`.
4. Extracts the subject claim (`system:serviceaccount:<namespace>:<serviceAccount>`) and looks up the pod identity association for the cluster, namespace, and service account.
5. Issues temporary session credentials (`ASIA...`) for the associated IAM role and returns them in standard AWS container credentials format (`AccessKeyId`, `SecretAccessKey`, `Token`, `AccountId`, `Expiration`).

#### TLS is required

Kubernetes only accepts an `https` `clientConfig.url` on a `MutatingWebhookConfiguration`, together with a `caBundle` the API server trusts. Floci serves HTTP with the default `floci.tls.enabled=false`, so the webhook is skipped with a warning in that case and pods start unmutated. Set `FLOCI_TLS_ENABLED=true` to get injection; the `caBundle` is then Floci's local CA, the same one served at `GET /_floci/ca.pem`.

The webhook URL uses the hostname containers reach Floci on. When Floci runs natively that is `host.docker.internal`, which the self-signed certificate covers. When Floci itself runs in a container the hostname is its container IP, and the self-signed certificate carries no SAN for it, so the API server rejects the handshake. Pods still start, unmutated. Point `FLOCI_SERVICES_LAMBDA_DOCKER_HOST_OVERRIDE` at a name the certificate covers (add it with `FLOCI_HOSTNAME`) to make the webhook reachable from that setup.

The webhook carries `failurePolicy: Ignore`, and Floci admits the pod unchanged on any internal error, so a webhook that cannot be registered or cannot be reached never prevents a pod from being created. Registration failures log a warning and leave the cluster running.

The manifest is written into the cluster's k3s server manifests directory (`/var/lib/rancher/k3s/server/manifests`) before the container starts, so k3s applies it as the API server comes up.

## Addon management

Floci supports the EKS cluster addon management plane for AWS SDKs and Terraform (`aws_eks_addon` resource).

### Supported operations

- **Creation**: `CreateAddon` creates an addon on an ACTIVE cluster. Supported addons include `vpc-cni`, `coredns`, `kube-proxy`, and `eks-pod-identity-agent`. If `addonVersion` is omitted, the default version compatible with the cluster Kubernetes version is resolved automatically. Referenced `serviceAccountRoleArn` must exist in IAM. Idempotency is supported via `clientRequestToken`.
- **Retrieval**: `DescribeAddon` returns the complete addon resource shape, including ARN, cluster name, version, status (`ACTIVE`), health issues, tags, service account role ARN, configuration values, pod identity associations, owner, and publisher.
- **Listing**: `ListAddons` lists installed addon names with pagination (`maxResults` and `nextToken`).
- **Updating**: `UpdateAddon` updates the addon version, configuration values, service account role ARN, or resolve-conflicts strategy. It returns an `Update` tracking object and updates the addon metadata.
- **Update tracking**: `DescribeUpdate` describes the status of an addon update (such as an in-place addon version update queried by Terraform).
- **Deletion**: `DeleteAddon` marks the addon as `DELETING` and removes it from the cluster. Deleting a cluster automatically cleans up all associated addons.
- **Supported versions**: `DescribeAddonVersions` queries the addon version catalog with optional filtering by `addonName` and `kubernetesVersion`, supporting pagination.

### Metadata recording only

Addons in Floci are recorded metadata only. Creating or updating an addon does not install or reconcile Kubernetes DaemonSets, Deployments, or custom resources inside the cluster container.

## Cluster security group

When an EKS cluster is created with a VPC (either specified directly or resolved from subnets), Floci provisions a dedicated EC2 cluster security group and populates its ID in `cluster.resourcesVpcConfig.clusterSecurityGroupId` on both `CreateCluster` and `DescribeCluster` responses.

### Naming convention and description

The generated security group name follows the AWS pattern:

```
eks-cluster-sg-<cluster-name>-<suffix>
```

where `<suffix>` is an 8-character random hexadecimal string. The security group description is set to:

```
EKS created security group applied to ENI that is attached to EKS Control Plane master nodes, as well as any managed workloads.
```

### System tags

Floci tags the cluster security group with the standard AWS system tags:

- `Name`: `eks-cluster-sg-<cluster-name>-<suffix>`
- `kubernetes.io/cluster/<cluster-name>`: `owned`
- `aws:eks:cluster-name`: `<cluster-name>`

### Lifecycle management and rules

- **Creation**: The security group is created in the resolved cluster VPC when the cluster is created. Both real and mock clusters receive a security group. If a cluster is created without any VPC or subnets, `clusterSecurityGroupId` is omitted from the response. If a caller explicitly provides a `resourcesVpcConfig.vpcId` that does not exist in EC2, the request is rejected with `InvalidParameterException` (HTTP 400).
- **Deletion**: When the cluster is deleted via `DeleteCluster`, Floci deletes the associated cluster security group from EC2. If the security group was already removed out-of-band, the deletion succeeds idempotently without error.
- **Backfill**: Existing persisted clusters that were created before this feature receive an auto-generated cluster security group during startup backfill if their VPC is present.
- **Rules**: In accordance with AWS EKS behavior, the cluster security group includes a self-referencing inbound rule allowing all traffic from members of the same security group to enable control plane and node communication, a self-referencing outbound rule for Elastic Fabric Adapter (EFA) traffic, and the standard EC2 outbound rule.

## Custom control-plane and node arguments (emulator affordance)

Floci allows callers to pass custom flags to k3s server components when creating an EKS cluster via resource tags. This is an emulator affordance with no direct AWS equivalent, intended for testing configurations that require custom kubelet or control plane flags in local environments.

### Tag formats

Custom arguments are supplied as tags in `CreateCluster`:

- Prefixed flag tag: `floci:kubelet-arg:<flag>=<value>` or `floci:kubelet-arg:<flag>` with tag value set to `<value>` (for example, `floci:kubelet-arg:max-pods` = `250`).
- Plural tag: `floci:kubelet-args` with tag value set to a JSON array of flag strings (for example, `["max-pods=250", "image-gc-high-threshold-percent=80"]`) or newline-separated lines.
- Singular tag: `floci:kubelet-arg` with tag value set to `<flag>=<value>`.

The supported component prefixes correspond to k3s server argument flags:
- `kubelet` (`--kubelet-arg`): worker node kubelet settings.
- `kube-apiserver` or `apiserver` (`--kube-apiserver-arg`): Kubernetes API server settings.
- `kube-controller-manager` or `controller-manager` (`--kube-controller-manager-arg`): controller manager settings.
- `kube-scheduler` or `scheduler` (`--kube-scheduler-arg`): scheduler settings.
- `kube-cloud-controller-manager` (`--kube-cloud-controller-manager-arg`): cloud controller manager settings.

### Common examples

- **Kubelet capacity**: `floci:kubelet-arg:max-pods=250` raises the pod ceiling on the local node.
- **Kubelet garbage collection**: `floci:kubelet-args` = `["image-gc-high-threshold-percent=70", "image-gc-low-threshold-percent=50"]`.
- **API server feature gates**: `floci:kube-apiserver-arg:feature-gates=CSIStorageCapacity=true`.
- **Controller manager single-node tuning**: `floci:kube-controller-manager-arg:leader-elect=false`.

### Collision rules and Floci-managed arguments

Floci manages a specific set of flags required for container networking, IAM authentication, audit logging, and topology emulation. Passing a conflicting value or modifier (such as `+=` or `-=`) for any of these flags causes `CreateCluster` to reject the request with `InvalidParameterException` (HTTP 400).

Floci manages the following 17 flags:
- Kubelet:
  - `provider-id`: manages EC2 worker node identity.
  - `node-labels`: manages topology zones, instance types, and nodegroup labels. Nodegroup labels merge directly into this managed argument rather than through the passthrough.
  - `system-reserved`: manages memory and CPU reservations.
  - `kube-reserved`: manages kubelet resource allocations.
  - `eviction-hard`: manages memory pressure thresholds.
  - `register-with-taints`: manages worker node taints configured on managed nodegroups.
- API server:
  - `authentication-token-webhook-config-file`: manages worker IAM authentication.
  - `authentication-token-webhook-version`: token webhook protocol version.
  - `authentication-token-webhook-cache-ttl`: webhook cache duration.
  - `service-account-issuer`: IRSA OIDC discovery issuer URL.
  - `service-account-key-file`: IRSA public signing key.
  - `service-account-signing-key-file`: IRSA private signing key.
  - `audit-policy-file`: EKS control plane audit policy.
  - `audit-log-path`: audit log destination.
  - `audit-log-maxage`: audit log retention days.
  - `audit-log-maxbackup`: audit log file rotation count.
  - `audit-log-maxsize`: audit log rotation size.

### Refused arguments

The following flags are refused outright because they conflict with the k3s embedded SQLite (kine) engine or bypass API server RBAC authorization:
- `storage-backend`
- `etcd-servers`
- `authorization-mode`

### Error handling and tag stripping

- **Malformed arguments**: If an argument tag is blank, contains control characters, or cannot be parsed, Floci logs a warning and proceeds with cluster creation.
- **Tag privacy and immutability**: All `floci:*` tags are stripped from public responses and are not returned by `DescribeCluster` or `ListTagsForResource`. Modification via `TagResource` after creation is rejected with `ValidationException`.

## Encryption and logging configuration

EKS clusters support configuring KMS envelope encryption for secrets and control plane logging export.

### Encryption configuration

`CreateCluster` accepts an `encryptionConfig` array specifying the encryption provider for Kubernetes resources:

```json
{
  "encryptionConfig": [
    {
      "resources": ["secrets"],
      "provider": {
        "keyArn": "arn:aws:kms:us-east-1:000000000000:key/12345678-1234-1234-1234-123456789012"
      }
    }
  ]
}
```

- **Supported resources**: Only `["secrets"]` is supported by AWS EKS. Specifying any other resource or leaving resources empty causes `InvalidParameterException` (HTTP 400).
- **Provider**: `provider.keyArn` must be non-empty.
- **Cardinality**: AWS allows at most one encryption configuration entry. Specifying more than one causes `InvalidParameterException` (HTTP 400).
- **Omission**: When omitted at creation time, `encryptionConfig` is not populated and is omitted from `DescribeCluster` responses (null or omitted, no synthetic default).
- **Scope**: Floci stores and returns the encryption configuration faithfully. Envelope encryption of Secrets at the Kubernetes storage layer and KMS cryptographic operations are out of scope.

### Logging configuration

`CreateCluster` accepts control plane logging configuration in `logging.clusterLogging`:

```json
{
  "logging": {
    "clusterLogging": [
      {
        "types": ["api", "audit"],
        "enabled": true
      }
    ]
  }
}
```

- **Supported log types**: The valid EKS log types are `api`, `audit`, `authenticator`, `controllerManager`, and `scheduler`. Specifying an unrecognized type causes `InvalidParameterException` (HTTP 400).
- **Response normalization**: AWS EKS always returns the status of all five log types. Enabled types are returned first (`enabled: true`), followed by disabled types (`enabled: false`).
- **Default logging**: When omitted or empty at creation time, Floci returns all five log types disabled in a single entry (`enabled: false`).
- **Backfill**: Existing persisted clusters created before this feature was introduced are automatically backfilled on startup with default disabled logging.
- **CloudWatch Logs delivery**:
  - When the `api` log type is enabled on a cluster, Floci creates the CloudWatch Logs log group `/aws/eks/<cluster-name>/cluster` and streams control plane container output to a log stream named `kube-apiserver-<hash>`, where `<hash>` is the first 32 characters of the container ID.
  - When the `audit` log type is enabled on a cluster, Floci configures k3s with the Amazon EKS control plane audit policy, writes audit logs to `/var/log/audit.log` inside the container, and streams audit records to a log stream named `kube-apiserver-audit-<hash>`. Audit records are available in CloudWatch Logs and are not echoed to the Floci console.
  - When both `api` and `audit` are enabled, both streams are created and populated under `/aws/eks/<cluster-name>/cluster`. When neither is enabled, no log group or streams are created.
- **Component streams deviation**: AWS EKS provisions separate log streams for each component (`kube-apiserver-*`, `kube-apiserver-audit-*`, `kube-controller-manager-*`, `kube-scheduler-*`, `authenticator-*`). Because Floci runs clusters on k3s, which embeds the Kubernetes API server, controller manager, and scheduler within a single process, control plane container logs are delivered to the single `kube-apiserver-<hash>` stream when `api` is enabled, and API server audit logs are delivered to `kube-apiserver-audit-<hash>` when `audit` is enabled. Other control plane log types (`authenticator`, `controllerManager`, `scheduler`) do not provision separate streams.
- **Authenticator logs**: Authenticator webhook authentication events are logged directly by Floci.

## Node group inputs

`CreateNodegroup` accepts the full documented input set and stores every member, so `CreateNodegroup` and `DescribeNodegroup` both return exactly what was supplied.

### Scalar and collection inputs

`nodegroupName`, `nodeRole`, `subnets`, `version`, `releaseVersion`, `amiType`, `capacityType`, `diskSize`, `instanceTypes`, `scalingConfig`, `labels`, `tags`, and `clientRequestToken`. Several of these receive an AWS-shaped default when omitted: `capacityType` becomes `ON_DEMAND`, `amiType` becomes `AL2_x86_64`, `diskSize` becomes `20`, `instanceTypes` becomes `["t3.medium"]`, `version` is inherited from the cluster, and `releaseVersion` becomes `<version>-eks-1`.

### Structured inputs

`launchTemplate`, `remoteAccess`, `taints`, `updateConfig`, `nodeRepairConfig`, and `warmPoolConfig` are recorded as supplied and echoed back verbatim, including nested members:

```json
{
  "nodegroupName": "my-nodegroup",
  "nodeRole": "arn:aws:iam::000000000000:role/eks-node-role",
  "subnets": ["subnet-123"],
  "launchTemplate": {
    "id": "lt-0a1b2c3d4e5f60718",
    "version": "3"
  },
  "remoteAccess": {
    "ec2SshKey": "my-keypair",
    "sourceSecurityGroups": ["sg-0123456789abcdef0"]
  },
  "taints": [
    {"key": "dedicated", "value": "gpu", "effect": "NO_SCHEDULE"}
  ],
  "nodeRepairConfig": {"enabled": true},
  "warmPoolConfig": {"enabled": true, "minSize": 2, "poolState": "Stopped"}
}
```

- **Omission**: When a structured input is not supplied, it is omitted from `CreateNodegroup` and `DescribeNodegroup` responses rather than serialized as an explicit `null`. The exception is `updateConfig`, which receives the AWS default of `{"maxUnavailable": 1}`.
- **Validation**: When `launchTemplate` is supplied, Floci validates it against EC2. Requests must specify either `id` or `name`, but not both; supplying neither or both is rejected with `InvalidParameterException` (HTTP 400). The template and requested version (defaulting to the template's default version when omitted) must exist in EC2, otherwise the request is rejected with `InvalidParameterException` (HTTP 400). The other structured inputs (`remoteAccess`, `taints`, `nodeRepairConfig`, `warmPoolConfig`) are not validated against external resources.
- **No backfill**: Node groups created before this was supported genuinely had no launch template, taints, remote access, node repair config, or warm pool config, so there is nothing to reconstruct. They continue to omit those members, which is the correct answer for them.

### Launch template user data execution

When a node group specifies a `launchTemplate`, Floci resolves the launch template from EC2 (by ID or name, and version) and executes any provided user data inside the cluster's running k3s container. If the referenced launch template carries no user data, execution is skipped.

- **Execution target**: Floci runs one k3s container per cluster. Launch template user data executes inside this cluster container via `docker exec`.
- **Single-container mapping rule**:
  - The first node group specifying user data executes its scripts in the container.
  - Subsequent node groups on the same cluster with identical user data skip execution as a no-op.
  - Subsequent node groups on the same cluster with differing user data log a warning and skip execution without failing the node group.
- **Timing and tradeoffs**: User data runs post-start when `CreateNodegroup` is invoked. Because the k3s process is already running, bootstrap configurations requiring pre-kubelet drop-ins (such as `/etc/rancher/k3s/config.yaml`) cannot be reloaded dynamically without restarting the container.
- **Failure handling**: If any user data script exits with a non-zero status or times out (30-minute limit), the node group status is set to `CREATE_FAILED` and `health.issues` is populated with code `NodeCreationFailure`, the failure message, and the node group name in `resourceIds`.
- **Differences from EC2**: Scripts run inside the existing shared k3s container rather than an isolated VM instance. Other launch template settings (AMI, instance type, block device mappings, network interfaces) remain metadata-only and do not alter container provisioning.

### Nodegroup labels, taints and capacity type

When managed nodegroups are defined for a cluster, Floci applies their labels and taints to the Kubernetes worker node at startup via `--kubelet-arg` flags.

#### Applied labels
Floci configures `--kubelet-arg=node-labels=...` with the following entries:
- Standard topology labels: `topology.kubernetes.io/region` and `topology.kubernetes.io/zone`.
- Instance type: `node.kubernetes.io/instance-type` (sourced from the nodegroup's first configured instance type, or defaulting to `m5.large`).
- EKS system labels:
  - `eks.amazonaws.com/nodegroup=<nodegroupName>`
  - `eks.amazonaws.com/capacityType=<ON_DEMAND|SPOT|CAPACITY_BLOCK>` (defaults to `ON_DEMAND` when omitted)
  - `eks.amazonaws.com/nodegroup-image=<imageId>` (sourced from `releaseVersion`, launch template AMI, or defaulting to `ami-eks-k3s`)
- User-defined labels: all key-value pairs specified in the nodegroup's `labels` map, excluding reserved system keys (`topology.kubernetes.io/*`, `eks.amazonaws.com/*`, and `node.kubernetes.io/instance-type`).

#### Applied taints
Floci configures `--kubelet-arg=register-with-taints=...` using the taints specified on the nodegroup. Each taint is formatted as `key=value:effect` (or `key:effect` if value is omitted or empty). AWS taint effects are mapped to standard Kubernetes casing:
- `NO_SCHEDULE` maps to `NoSchedule`
- `NO_EXECUTE` maps to `NoExecute`
- `PREFER_NO_SCHEDULE` maps to `PreferNoSchedule`

#### Single shared node mapping
Floci runs one k3s container per cluster. The worker node reflects the metadata of the first active nodegroup (ordered by `createdAt` ascending, then `nodegroupName`). If additional nodegroups are created on the same cluster, Floci logs a warning that only the first nodegroup provides node metadata to the shared node.

#### Timing and post-start nodegroups
Kubelet flags are fixed when the cluster container starts. For nodegroups created while the cluster is already running, metadata is recorded in the EKS store and Floci logs an informational message that labels and taints are not applied to the running node. Surviving cluster containers are adopted as-is without rebuilding kubelet flags on restore.

### Metadata only inputs

The remaining structured inputs are recorded as metadata:

- `remoteAccess` does not open SSH access, and the referenced key pair and security groups are not wired up.
- `nodeRepairConfig` starts no repair loop, and `warmPoolConfig` pre-initializes no instances.

The value of storing them is state fidelity: infrastructure tools read `DescribeNodegroup` back and compare it against their declared configuration. Dropping `launchTemplate` in particular made the OpenTofu AWS provider see a node group with no launch template where one was declared, which is a `ForceNew` difference and caused a destroy and recreate on every plan.

## Modes

### Mock mode (`mock: true`)

Cluster metadata is stored in-process. No Docker containers are started. The cluster transitions directly to `ACTIVE` on creation. Use this in CI or whenever you only need the EKS API shape, not a real Kubernetes API server.

### Real mode (`mock: false`, default)

Floci starts a **k3s** (`rancher/k3s`) container for each cluster. The k3s API server is exposed on a host port from the configured range (`6500-6599`). Once `/readyz` responds, the cluster transitions to `ACTIVE` and the CA certificate is extracted from the kubeconfig.

By default `describe-cluster` returns a **host-reachable** endpoint (`https://localhost:<hostPort>`); the k3s server certificate includes a `localhost` SAN, so it verifies against the CA in `cluster.certificateAuthority.data`. Set `endpoint-mode: network` to return the container DNS name (`https://floci-eks-<name>:6443`) instead: reachable from other containers on the Docker network (the pre-#1118 behaviour). In `network` mode the endpoint falls back to the host-reachable form when Floci runs natively, since there is no container DNS name a host client could use.

#### Connecting with `kubectl` (native AWS workflow)

The standard AWS flow works end to end:

```bash
aws eks update-kubeconfig --name my-cluster
kubectl get nodes
```

`aws eks update-kubeconfig` wires `aws eks get-token` into the kubeconfig as an exec credential. The bearer token contains a SigV4-presigned STS `GetCallerIdentity` request. Floci validates its signature and 15-minute token lifetime matching `aws-iam-authenticator` rather than the presigned expiry, then verifies the signed `x-k8s-aws-id` header against the cluster-specific `/_floci/eks/clusters/<cluster-name>/token-webhook` endpoint before resolving the caller identity. Instance-profile sessions require an EC2_LINUX access entry as described above; non-worker callers retain the `system:masters` mapping (bound to `cluster-admin`). A long-term access key gets it only on clusters of its own account, so a default-account key is refused on another account's cluster; on the older unscoped webhook path only the default account's keys get it. No `aws-iam-authenticator` is required.

Create an IAM access key before using EKS authentication. The public local-development pairs `test`/`test` and `floci`/`floci` are deliberately rejected because the webhook grants cluster-admin access.

Temporary IAM credentials must include their `AWS_SESSION_TOKEN` when signing the token.

This webhook is enabled by default (`iam-auth-webhook: true`). Set it to `false` to start k3s without it (in which case `aws eks get-token` tokens are rejected with `401`).

!!! note "Webhook reachability & networking"
    The k3s API server must be able to reach Floci's webhook URL. When Floci runs natively, k3s containers reach it via `host.docker.internal`; when Floci runs in a container (`floci start`), Floci and the k3s containers share a Docker network. The k3s network is taken from `FLOCI_SERVICES_EKS_DOCKER_NETWORK` if set, otherwise the global `FLOCI_SERVICES_DOCKER_NETWORK`, otherwise the network Floci is itself attached to (auto-detected), so no EKS-specific network configuration is required in the standard compose setup.

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
    k3s containers bind their API server port (6500-6599) directly on the host via Docker: no `ports:` entry is required in `docker-compose.yml`. See [Ports Reference](../configuration/ports.md#ports-65006599-eks-real-mode) for the full explanation.

#### Clusters survive a restart

With a persistent [storage mode](../configuration/storage.md) (the default), clusters recorded in
`eks-clusters.json` are **re-latched to their k3s containers when Floci starts**:

- A surviving container (for example after a Docker Desktop / daemon reboot) is adopted and
  started in place, keeping its published API server port and data volume: deployments come
  back as they were.
- A missing container is recreated. Its named k3s data volume (`floci-eks-<name>`; for a
  [non-default account](../configuration/multi-account.md), `floci-eks-<account>.<name>`) is
  reused if it survived; volumes follow the global prune policy
  (`FLOCI_STORAGE_PRUNE_VOLUMES_ON_DELETE`, default `false`), so they are retained when the
  container is stopped or the cluster deleted, except in `memory` storage mode.
- A non-default-account cluster created before account-qualified naming keeps its historical
  `floci-eks-<name>` container and volume: restoration adopts the surviving container when its
  `io.floci.account` label matches the owning account, so pre-upgrade workloads are not
  orphaned.

A restored cluster reports `CREATING` until its API server answers again, then returns to
`ACTIVE` with a freshly extracted certificate authority. If the container cannot be brought
back (for example Docker is unavailable), the cluster is marked `FAILED` instead of appearing
`ACTIVE` while unreachable.

#### Cluster node capacity

Floci runs one k3s container per cluster. Before a node group exists, its node uses the
`m5.large` entry in the EC2 instance type catalog. Creating the first node group selects the
group's first `instanceTypes` entry. If that changes the type, Floci recreates the container,
keeps its named k3s data volume, and reuses its published API port so existing kubeconfigs stay
valid. Later node groups share this node and cannot change its type. An unknown type falls back to
`m5.large` with a warning. The synthesized EC2 instance reports the selected type.
First-group selection is serialized per cluster. If the replacement fails, Floci recreates the
previous node, marks that node group `CREATE_FAILED`, and keeps its data volume. If recovery also
fails, the cluster becomes `FAILED` instead of waiting indefinitely for readiness.
Groups remain `CREATING` while launch-template user data runs. Later groups wait for the first
group's result before accepting its node type as the shared capacity.

The container receives a hard CPU quota equal to the selected type's vCPUs and a memory limit
equal to its catalog memory plus 10% headroom (at least 128 MiB). Both are capped by Docker's host
capacity. Memory is capped at 80% of the Docker host's memory so other processes can continue to
run. `floci.services.eks.max-memory-mib` and `floci.services.eks.max-vcpus` provide optional lower
ceilings; zero means no extra ceiling. Their environment variables are `FLOCI_SERVICES_EKS_MAX_MEMORY_MIB`
and `FLOCI_SERVICES_EKS_MAX_VCPUS`.

The same calculation sets kubelet's `kube-reserved`, `system-reserved`, and `eviction-hard` flags.
The EKS AMI reserves `255 + 11 * maxPods` MiB and a tiered CPU fraction for kubelet and the
container runtime. Floci derives `maxPods` from the catalog's primary network card and IPv4
addresses per interface. Its `system-reserved` memory holds the container headroom, and its CPU
reservation excludes the Docker host CPUs outside the container quota. Its absolute
`memory.available` threshold includes the difference between Docker host memory and the container
limit, plus a 100 MiB eviction buffer. This is necessary because kubelet in the nested container
can report host memory while the kernel enforces the smaller cgroup limit.
The EKS node filesystem eviction defaults remain at 10% available space and 5% free inodes.
The pod-density calculation uses EKS managed node group caps of 110 pods up to 30 vCPUs and
250 pods above 30 vCPUs; it does not change k3s `maxPods`.

If Docker cannot report host capacity, or the host is too small to leave 256 MiB for pods after
reservations, Floci warns and launches the cluster without the derived limits or kubelet arguments.
This preserves startup on small hosts. An explicit memory or CPU ceiling remains a hard bound even
when Docker host information is unavailable; in that case Floci cannot calculate kubelet arguments.
When an explicit memory ceiling is smaller than the default reservation budget, Floci reduces the
memory reservations to leave some room for pods and warns that they no longer match the EKS AMI
amount.

The reservation formulas follow the [EKS AMI nodeadm source](https://github.com/awslabs/amazon-eks-ami/blob/main/nodeadm/internal/kubelet/config.go) and [AWS's node memory calculation](https://docs.aws.amazon.com/batch/latest/userguide/memory-cpu-batch-eks.html).
On restore, a surviving container whose capacity label differs from the current calculation is
recreated against the retained data volume, reusing its published API port. Floci keeps the old
container until the replacement starts and restarts it if replacement fails. This also upgrades
containers started before resource limits were available. If Docker cannot remove the stopped
backup after a successful replacement, the new node keeps running and cluster deletion retries
the backup cleanup after stopping the live node. If cleanup still fails, deletion reports the
error and can be retried before the data volume is removed. Backup container names put the
`capacity-backup.` marker before the account-qualified cluster name, for example
`floci-aws-eks-capacity-backup.demo` for the default account and
`floci-aws-eks-capacity-backup.999999999999.demo` for another account. The configured Docker resource
namespace applies to backups too. This keeps backup names outside both accounts' live-container
namespaces without changing existing cluster container names or data volumes.
Cleanup only targets this backup namespace; it does not look up old suffix-form names, which can
identify another account's live cluster. Verify ownership before manually removing leftover stopped
backups after upgrading.

#### Cluster node naming, provider ID, and topology labels

In real mode, cluster nodes are named after their backing EC2 instance's private DNS name using AWS resource-based naming. Real EKS nodes take their Kubernetes name from the instance's private DNS name (`system:node:{{EC2PrivateDNSName}}`). Under resource-based naming, this hostname includes the domain: `i-<instance-id>.ec2.internal` in `us-east-1`, or `i-<instance-id>.<region>.compute.internal` in other regions. Floci implements resource-based naming by deriving the instance ID and domain deterministically from the cluster identity, passing `--node-name=<privateDnsName>` to k3s before startup, and assigning the same `PrivateDnsName` to the synthetic EC2 node instance. This keeps the node name stable across container restarts and recreations, and ensures agreement between Kubernetes node queries and EC2 `DescribeInstances`.

Cluster nodes also carry a Kubernetes `spec.providerID` matching the AWS format:

```text
aws:///<availability-zone>/<instance-id>
```

For example, `aws:///us-east-1a/i-0123456789abcdef0`. Floci derives this identifier deterministically before container startup and passes `--kubelet-arg=provider-id=<providerId>` to k3s.

Cluster nodes also carry standard Kubernetes topology labels:

- `topology.kubernetes.io/zone`
- `topology.kubernetes.io/region`

Floci derives the availability zone from the cluster region (for example, `<region>a` for `us-east-1`, yielding `us-east-1a`), rather than from node group subnets as real EKS does. The zone matches the availability zone in the node's `spec.providerID` and synthetic EC2 node instance. These labels enable topology-aware scheduling and allow controllers such as the AWS EBS CSI driver to discover the node's availability zone.

The node name, the instance ID in `spec.providerID`, and the synthetic EC2 instance (`InstanceId` and `PrivateDnsName`) all describe the same instance. This enables controllers that reconcile nodes against EC2 (such as CSI drivers) to look up the node instance via `DescribeInstances`.

#### Storage classes

Stock Amazon EKS clusters historically define a default `gp2` StorageClass pointing to the legacy in-tree `kubernetes.io/aws-ebs` plugin, but run no active storage provisioner without the EBS CSI driver. Unqualified PersistentVolumeClaims remain `Pending` on EKS because nothing can provision them. Floci matches this behavior by disabling k3s's bundled `local-path` provisioner and StorageClass, preventing unqualified claims from silently binding to the node filesystem.

Callers requiring dynamic volume provisioning can install a driver (such as the `aws-ebs-csi-driver` addon) and define their desired StorageClass.

For local testing workflows that rely on automatic volume binding without a CSI driver, set `floci.services.eks.default-storage-class: true` (or `FLOCI_SERVICES_EKS_DEFAULT_STORAGE_CLASS=true`) to retain k3s's bundled `local-path` provisioner and default StorageClass.

## Joining EC2 instances as cluster nodes

Floci allows EC2 instances to join an EKS cluster as Kubernetes worker nodes. Launching an instance via `RunInstances` with user data targeting a cluster automatically registers a matching Kubernetes node.

### Join mechanisms

Supported user data formats include:
- Amazon Linux 2 `/etc/eks/bootstrap.sh <cluster-name>` commands (plain text, base64, or gzipped base64).
- Amazon Linux 2023 `nodeadm` YAML documents or MIME multipart payloads (`NodeConfig` containing `spec.cluster.name`).

On AWS, EC2 does not fail instance launches if a target cluster is missing. Floci mirrors this: if the target cluster does not exist or node registration fails, Floci logs a warning and allows the launch to proceed.

### Node metadata and AWS outcome alignment

When an instance joins a cluster, Floci registers a Kubernetes `Node` matching AWS conventions:
- **Node name**: Uses the instance's private DNS name (`privateDnsName`), matching AWS EKS conventions.
- **Provider ID**: Set to `spec.providerID: aws:///<availability-zone>/<instance-id>`.
- **Labels & Status**: Includes topology and instance-type labels, ready status, internal IP, and catalog capacity.
- **EC2 persistence**: The instance remains an authentic EC2 instance reported by `DescribeInstances`.

### Differences and limitations

- Floci registers the `Node` directly in the control plane with matching provider ID, labels, and ready status.
- Node cleanup on termination is handled in part two; an instance can belong to at most one cluster at a time.

## Configuration

| Variable | Default | Description |
|---|---|---|
| `FLOCI_SERVICES_EKS_ENABLED` | `true` | Enable the EKS service |
| `FLOCI_SERVICES_EKS_MOCK` | `false` | Metadata-only mode (no Docker) |
| `FLOCI_SERVICES_EKS_MAX_MEMORY_MIB` | `0` | Optional k3s container memory ceiling in MiB |
| `FLOCI_SERVICES_EKS_MAX_VCPUS` | `0` | Optional k3s container vCPU ceiling |
| `FLOCI_SERVICES_EKS_DEFAULT_IMAGE` | `rancher/k3s:latest` | k3s Docker image fallback |
| `FLOCI_SERVICES_EKS_IMAGE_TEMPLATE` | *(unset)* | Format string for custom k3s images (e.g. `myregistry.io/k3s:v%s`), taking cluster version |
| `FLOCI_SERVICES_EKS_API_SERVER_BASE_PORT` | `6500` | First port in the k3s API server range |
| `FLOCI_SERVICES_EKS_API_SERVER_MAX_PORT` | `6599` | Last port in the k3s API server range |
| `FLOCI_SERVICES_EKS_DATA_PATH` | `./data/eks` | Host bind-mount root for cluster data |
| `FLOCI_SERVICES_EKS_DOCKER_NETWORK` | *(unset)* | Docker network for k3s containers (falls back to the global `FLOCI_SERVICES_DOCKER_NETWORK`, then Floci's own network) |
| `FLOCI_SERVICES_EKS_KEEP_RUNNING_ON_SHUTDOWN` | `false` | Leave k3s containers running after Floci stops |
| `FLOCI_SERVICES_EKS_ENDPOINT_MODE` | `host` | `describe-cluster` endpoint: `host` (`localhost:<hostPort>`) or `network` (container DNS) |
| `FLOCI_SERVICES_EKS_IAM_AUTH_WEBHOOK` | `true` | Wire a token-auth webhook into k3s so `aws eks get-token` works |
| `FLOCI_SERVICES_EKS_ECR_REGISTRY_MIRROR` | `true` | Inject a containerd `registries.yaml` so pods can pull images pushed to [Floci ECR](ecr.md) |
| `FLOCI_SERVICES_EKS_DISABLE_CNI` | `false` | Start k3s without bundled flannel, network policy, and kube-proxy so an external CNI can take over |
| `FLOCI_SERVICES_EKS_DEFAULT_STORAGE_CLASS` | `false` | Retain k3s bundled local-path provisioner and default StorageClass (default false matches EKS with no default class) |
| `FLOCI_SERVICES_EKS_IRSA_SIGNING_KEY` | `true` | Pass the cluster OIDC signing key to k3s so in-cluster projected service account tokens can assume IAM roles via Floci STS |
| `FLOCI_SERVICES_EKS_POD_IDENTITY_WEBHOOK` | `true` | Register a mutating admission webhook that injects pod identity credentials. Needs `FLOCI_TLS_ENABLED=true` |
| `FLOCI_SERVICES_EKS_IMDS` | `false` | Enable link-local IMDS (`169.254.169.254`) proxy in cluster containers |
| `FLOCI_SERVICES_EKS_EMBEDDED_DNS` | `true` | Inject Floci's embedded DNS server into cluster containers for Route 53 private hosted zones and internal name resolution |
| `FLOCI_SERVICES_EKS_VPC_ROUTE_PROGRAMMING` | `true` | Program emulated VPC route table entries into k3s cluster containers |

### Kubernetes versions and network configuration

Floci supports standard AWS EKS Kubernetes versions (`1.xx`, 1.28 and above). Known releases are mapped to stable pinned k3s images, while newer or unpinned versions dynamically resolve to upstream k3s release images (`rancher/k3s:v<version>.0-k3s1`):

- `1.28` (`rancher/k3s:v1.28.15-k3s1`)
- `1.29` (`rancher/k3s:v1.29.14-k3s1`, default version metadata)
- `1.30` (`rancher/k3s:v1.30.10-k3s1`)
- `1.31` (`rancher/k3s:v1.31.5-k3s1`)
- `1.32` (`rancher/k3s:v1.32.2-k3s1`)
- `1.33` (`rancher/k3s:v1.33.1-k3s1`)
- `1.34` (`rancher/k3s:v1.34.1-k3s1`)
- `1.35` (`rancher/k3s:v1.35.0-k3s1`)
- `1.36` (`rancher/k3s:v1.36.0-k3s1`)
- `1.37+` (dynamically resolved to `rancher/k3s:v<version>.0-k3s1`)

When `--version` is omitted, the cluster version defaults to `1.29` and the container image resolution uses `FLOCI_SERVICES_EKS_DEFAULT_IMAGE` (`rancher/k3s:latest` by default). When a version is explicitly requested, it maps to the corresponding pinned image or template.

You can specify standard EKS `version` and `kubernetesNetworkConfig.serviceIpv4Cidr` when creating a cluster:

```bash
aws --endpoint-url http://localhost:4566 eks create-cluster \
  --name my-cluster \
  --role-arn arn:aws:iam::000000000000:role/eks-role \
  --version 1.31 \
  --kubernetes-network-config serviceIpv4Cidr=172.20.0.0/16
```

Floci validates that `serviceIpv4Cidr` falls within RFC 1918 private address ranges (`10.0.0.0/8`, `172.16.0.0/12`, or `192.168.0.0/16`), has a prefix length between `/12` and `/24`, and does not overlap with the VPC CIDR. When omitted, Floci defaults `serviceIpv4Cidr` to `10.100.0.0/16` (or `172.20.0.0/16` if `10.100.0.0/16` overlaps with the VPC). Internal pod CIDR (`--cluster-cidr`) defaults to `10.42.0.0/16` to avoid collisions with Docker bridge networks.

### VPC route table programming

When a cluster is created in a VPC (`resourcesVpcConfig.vpcId` is specified) and `floci.services.eks.vpc-route-programming` is enabled (`true` by default), Floci inspects the VPC route tables and programs matching routes into the k3s cluster container via `ip route replace <dest> via <gw>`.

- **Association rules**:
  - If the cluster specifies `subnetIds`, Floci resolves the route table explicitly associated with each subnet. Subnets without an explicit association fall back to the VPC main route table. Route tables associated only with other subnets in the VPC are ignored.
  - If the cluster specifies no subnets, the VPC main route table is used.
- **Programmable route targets**:
  - **Instance targets** (`InstanceId`): Resolved to the EC2 container bridge IP or private IP address on the shared Docker network.
  - **Network interface targets** (`NetworkInterfaceId`): Resolved to the ENI private IP address.
- **Ignored and out-of-scope targets**:
  - The default route (`0.0.0.0/0`) is never overridden, preserving the container's external network reachability and Docker bridge routing.
  - Internet gateways (`igw-*`) and NAT gateways (`nat-*`) are out of scope because Docker networking already handles outbound traffic.
  - VPC peering connections (`pcx-*`) and prefix lists are recorded in route table metadata but not programmed into the container kernel routing table.
- **Lifecycle and dynamic updates**:
  - Routes are programmed when a cluster starts or is restored after a restart.
  - An event-driven listener updates active clusters dynamically whenever routes or associations change via `CreateRoute`, `ReplaceRoute`, `DeleteRoute`, `AssociateRouteTable`, or `DisassociateRouteTable`.
- **Fault tolerance**:
  - Route programming is idempotent.
  - If routing commands fail inside the container (for example, if the `ip` tool is missing or returns a non-zero exit code), Floci logs a warning and the cluster proceeds to `ACTIVE` without failing creation.

### Pulling images from Floci ECR

Images pushed to the [Floci ECR registry](ecr.md) use `localhost`-based repository URIs
(for example `000000000000.dkr.ecr.us-east-1.localhost:4566/my-repo:tag`). Inside a k3s
cluster that hostname would resolve to the k3s container itself, and containerd insists
on HTTPS for anything it doesn't recognize as loopback: so, out of the box, k3s cannot
pull from the registry even though `docker push` from the host works.

Floci solves this at cluster creation: each new k3s container gets a generated
`/etc/rancher/k3s/registries.yaml` that mirrors every repository hostname the emulator
can mint: the default account across the full region catalog, plus the path-style
`localhost:<port>` form used by `FLOCI_SERVICES_ECR_URI_STYLE=path`, to Floci's
in-network data plane. The same image
reference then works for the host-side push and the in-cluster pull, with no retagging
and no manual containerd configuration:

```bash
aws ecr create-repository --repository-name my-repo
docker build -t 000000000000.dkr.ecr.us-east-1.localhost:4566/my-repo:v1 .
docker push 000000000000.dkr.ecr.us-east-1.localhost:4566/my-repo:v1

aws eks create-cluster --name demo ...
helm install my-app ./chart \
  --set image.repository=000000000000.dkr.ecr.us-east-1.localhost:4566/my-repo \
  --set image.tag=v1
```

Requirements and limits:

- The k3s container must reach Floci's Docker-network address. Set the global
  `FLOCI_SERVICES_DOCKER_NETWORK` as in the standard `docker-compose.yml` or the
  EKS service network variable.
- Only Floci-mintable hostnames are mirrored; public registries (docker.io, ghcr.io, …)
  are never touched.
- Mirrored hostnames carry the port the repository URIs advertise, which is the host port
  when Floci's container publishes `4566` on another one (see [ECR](ecr.md#docker-compose-port-mapping)).
- Repository URIs using a non-default `registryId` (account) are not covered.
- The mirror set is snapshotted when the cluster is created. Clusters created before this
  feature can be fixed manually because the k3s container filesystem survives a restart:

  ```bash
  docker cp registries.yaml floci-eks-<cluster>:/etc/rancher/k3s/registries.yaml
  docker restart floci-eks-<cluster>
  ```

### DNS resolution and Route 53 private hosted zones

By default, Floci injects its embedded DNS server into each cluster container (`FLOCI_SERVICES_EKS_EMBEDDED_DNS=true`). Cluster containers and CoreDNS forward to Floci's embedded resolver on port 53, enabling resolution of Route 53 private hosted zone records, internal hostnames, and records managed by `external-dns`. Set `FLOCI_SERVICES_EKS_EMBEDDED_DNS=false` to use standard Docker network DNS instead.

### Registry host configuration (pull-through caches and custom mirrors)

On standard EKS (such as AL2023), node registry host configuration is provided through the node
group's launch template user data, which writes containerd
[`hosts.toml`](https://github.com/containerd/containerd/blob/main/docs/hosts.md) configurations
under `/etc/containerd/certs.d/<host>/hosts.toml`.

Floci links `/etc/containerd/certs.d` to k3s's containerd certs directory
(`/var/lib/rancher/k3s/agent/etc/containerd/certs.d`) inside the cluster container before it
starts. Standard launch template user data works unchanged: pull-through cache endpoints, custom
request headers, and TLS settings take effect immediately, and the files persist across cluster
container restarts in the cluster's named data volume.

Example node group launch template user data configuring a pull-through cache with an auth header
wrapped in MIME multipart for managed node groups:

```bash
MIME-Version: 1.0
Content-Type: multipart/mixed; boundary="==MYBOUNDARY=="

--==MYBOUNDARY==
Content-Type: text/x-shellscript; charset="us-ascii"

#!/bin/sh
mkdir -p /etc/containerd/certs.d/my-registry.internal:5000
cat << 'EOF' > /etc/containerd/certs.d/my-registry.internal:5000/hosts.toml
server = "https://my-registry.internal:5000"

[host."https://cache.internal:5000"]
  capabilities = ["pull", "resolve"]
  [host."https://cache.internal:5000".header]
    Authorization = "Bearer <token>"
EOF

--==MYBOUNDARY==--
```

### Mock mode (CI / tests)

Use `FLOCI_SERVICES_EKS_MOCK=true` when you only need the API shape:

```yaml
# docker-compose.yml: CI / test environment
services:
  floci:
    image: floci/floci:latest
    environment:
      FLOCI_SERVICES_EKS_MOCK: "true"
```

## Instance Metadata Service (IMDS)
The cluster container is registered as a synthesized EC2 instance node (image `ami-eks-k3s`) regardless of whether IMDS is enabled, making it visible to the EC2 service (`DescribeInstances`, `DescribeInstanceStatus`, volume attachments, tagging). Its instance type is the same type used for the container limits.

When IMDS is enabled (`FLOCI_SERVICES_EKS_IMDS=true`), each k3s cluster container exposes the AWS Instance Metadata Service on the link-local address `169.254.169.254:80`. Inside the container, Floci adds `169.254.169.254/32` to the loopback interface (`lo`) and runs a lightweight `socat` TCP relay forwarding metadata requests to Floci's IMDS server.

Both IMDSv1 and IMDSv2 (`PUT /latest/api/token`) are supported. The cluster container is registered as a synthesized EC2 instance node associated with the cluster's IAM role:

```bash
# IMDSv2: obtain token
TOKEN=$(curl -s -X PUT "http://169.254.169.254/latest/api/token" \
  -H "x-aws-ec2-metadata-token-ttl-seconds: 21600")

# Read node instance ID
curl -s -H "x-aws-ec2-metadata-token: $TOKEN" \
  http://169.254.169.254/latest/meta-data/instance-id
```

### Reachability and network namespaces

The link-local proxy attaches to the loopback interface of the k3s container network namespace (the node network namespace).

- **Reachable from node network namespace:** Workloads configured with `hostNetwork: true` share the node network namespace and can reach `169.254.169.254:80`. This allows local testing of host-network security policies, intrusion-detection rules, and credential exfiltration defenses.
- **Default isolation for ordinary pods:** By default, pods running in separate pod network namespaces cannot reach `169.254.169.254`. CNI portmap rules on the node intercept local traffic, preserving node credential isolation for multi-tenant or untrusted workloads.
- **Opt-in pod network routing:** When `floci.services.eks.imds-pod-network=true` is set alongside `floci.services.eks.imds=true`, iptables DNAT rules are programmed in a custom `FLOCI-LINK-LOCAL` chain at the head of `PREROUTING`. Traffic originating from the pod CIDR (`10.42.0.0/16`) targeting `169.254.169.254:80` matches first and is routed directly to the local node listener, preempting CNI portmap rules. This enables standard AWS SDK credential resolution inside ordinary pods without requiring `hostNetwork: true`.

### Configuration

IMDS proxy initialization is disabled by default (`floci.services.eks.imds=false`). Set `FLOCI_SERVICES_EKS_IMDS=true` to enable the proxy setup inside the k3s container.

Pod network reachability is also disabled by default (`floci.services.eks.imds-pod-network=false`) to maintain pod isolation. Set `FLOCI_SERVICES_EKS_IMDS_POD_NETWORK=true` together with `FLOCI_SERVICES_EKS_IMDS=true` to route pod traffic to the link-local proxy.

A failure to configure the proxy (for example on custom minimal images lacking network utilities) logs a warning and allows cluster startup to continue.

### Hop limits

EC2 metadata options store `HttpPutResponseHopLimit`, but the userspace TCP proxy relay terminates the incoming connection and opens a new connection to Floci, regenerating the IP packet TTL. Hop limits are recorded on the instance metadata options model but are not enforced by the userspace proxy.

## IRSA (IAM Roles for Service Accounts)

Every cluster gets an OIDC identity provider, so the full IRSA flow (trust policy, service-account token, `sts:AssumeRoleWithWebIdentity`) works end to end without mocked or hardcoded tokens.

`CreateCluster` generates an RSA-2048 signing keypair and an AWS-shaped issuer URL, returned by `DescribeCluster`:

```json
{
  "cluster": {
    "name": "my-cluster",
    "identity": { "oidc": { "issuer": "https://oidc.eks.us-east-1.amazonaws.com/id/3F8A…" } }
  }
}
```

The issuer URL is a faithful string for building trust policies, but it is not fetched: Floci's STS resolves the signing key in-process. The private key is held in storage separate from the cluster model and is never returned by any API.

### Service-account tokens

Floci supports two ways to obtain IRSA service account tokens:

#### 1. In-cluster projected tokens (real k3s mode)

In real mode (`mock: false`), Floci starts a k3s container for the cluster. When IRSA signing key support is enabled (`FLOCI_SERVICES_EKS_IRSA_SIGNING_KEY=true`, default `true`), Floci exports the cluster RSA keypair to `/etc/rancher/k3s/sa-signing-key.pem` and `/etc/rancher/k3s/sa-public-key.pem` with restrictive filesystem permissions (`0600`) before k3s starts.

The k3s API server is configured with:
- `--kube-apiserver-arg=service-account-signing-key-file=/etc/rancher/k3s/sa-signing-key.pem`
- `--kube-apiserver-arg=service-account-key-file=/etc/rancher/k3s/sa-public-key.pem`
- `--kube-apiserver-arg=service-account-issuer=<cluster-oidc-issuer>`
- `--kube-apiserver-arg=service-account-issuer=https://kubernetes.default.svc.cluster.local`
- `--kube-apiserver-arg=api-audiences=https://kubernetes.default.svc.cluster.local,sts.amazonaws.com`

This enables in-cluster pods to project service account tokens directly using Kubernetes standard projected volumes:

```yaml
apiVersion: v1
kind: Pod
metadata:
  name: irsa-workload
  namespace: default
spec:
  serviceAccountName: my-service-account
  containers:
  - name: workload
    image: my-workload:latest
    env:
    - name: AWS_ROLE_ARN
      value: arn:aws:iam::000000000000:role/my-irsa-role
    - name: AWS_WEB_IDENTITY_TOKEN_FILE
      value: /var/run/secrets/eks.amazonaws.com/serviceaccount/token
    volumeMounts:
    - mountPath: /var/run/secrets/eks.amazonaws.com/serviceaccount
      name: aws-iam-token
      readOnly: true
  volumes:
  - name: aws-iam-token
    projected:
      sources:
      - serviceAccountToken:
          path: token
          expirationSeconds: 86400
          audience: sts.amazonaws.com
```

Tokens projected with audience `sts.amazonaws.com` are signed by k3s using the cluster key and advertise the cluster issuer URL. When the AWS SDK inside the container calls `sts:AssumeRoleWithWebIdentity`, Floci verifies the signature against the cluster public key and checks the trust policy conditions. In-cluster components that do not specify an audience receive tokens with both the in-cluster audience and STS audience, allowing normal Kubernetes operation without disruption.

#### 2. Minting a service-account token via HTTP (mock mode & out-of-cluster testing)

For local development harnesses running outside Kubernetes or when using mock mode (`FLOCI_SERVICES_EKS_MOCK=true`), tokens can be requested directly from Floci via HTTP. Signing happens server-side, so the private key never leaves Floci:

```bash
curl -sX POST http://localhost:4566/_floci/eks/clusters/my-cluster/oidc-token \
  -H 'Content-Type: application/json' \
  -d '{"namespace":"my-namespace","serviceAccount":"my-service-account"}'
```

```json
{
  "token": "eyJhbGciOiJSUzI1NiIs…",
  "issuer": "https://oidc.eks.us-east-1.amazonaws.com/id/3F8A…",
  "subject": "system:serviceaccount:my-namespace:my-service-account",
  "audience": "sts.amazonaws.com"
}
```

`audience` (default `sts.amazonaws.com`) and `expirySeconds` (default 24h, max 7d) are optional. This is Floci plumbing under `_floci/…`, not an AWS API.

### Trust policy

Note that the OIDC provider ARN and the condition keys use the issuer with the scheme stripped `oidc.eks.<region>.amazonaws.com/id/<id>`, which is how the AWS console, `eksctl`, and Terraform all render it.

```json
{
  "Version": "2012-10-17",
  "Statement": [{
    "Effect": "Allow",
    "Principal": {
      "Federated": "arn:aws:iam::000000000000:oidc-provider/<oidcProvider>"
    },
    "Action": "sts:AssumeRoleWithWebIdentity",
    "Condition": {
      "StringEquals": {
        "<oidcProvider>:sub": "system:serviceaccount:<namespace>:<serviceAccount>",
        "<oidcProvider>:aud": "sts.amazonaws.com"
      }
    }
  }]
}
```

### What STS validates

When `sts:AssumeRoleWithWebIdentity` receives a token whose `iss` names an issuer Floci hosts, it enforces all of:

- the RS256 signature, against that cluster's public key
- `iss` matches the cluster's issuer exactly
- `aud` contains `sts.amazonaws.com`
- `exp` / `nbf`, with 60s of clock-skew tolerance
- the role's trust policy: `Principal.Federated` and the `Condition` block, comparing `oidc:sub` / `oidc:aud` with exact, case-sensitive equality

The response then carries the token's real claims in `SubjectFromWebIdentityToken`, `Provider`, and `Audience`. Failures return `InvalidIdentityToken` (400) for a bad token, `ExpiredTokenException` (400) for an expired one, or `AccessDenied` (403) when the trust policy does not permit the subject.

A token whose issuer Floci does not host is treated as opaque and accepted, since Floci cannot adjudicate a third-party provider. Validation is therefore automatic for Floci-issued tokens and requires no configuration flag.

### OIDC discovery endpoints

Served for fidelity and debugging, nothing in the IRSA flow dereferences them:

| Route | Description |
|---|---|
| `GET /_floci/eks/clusters/<name>/oidc/.well-known/openid-configuration` | OIDC discovery document |
| `GET /_floci/eks/clusters/<name>/oidc/keys` | JWKS containing the cluster's public key |

These live under `_floci/…` rather than at the AWS-shaped issuer path, which Floci's embedded DNS does not resolve and which would collide with S3's path-style routing.

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
- `UpdateNodegroupConfig` / `UpdateNodegroupVersion`
- Add-ons (`CreateAddon`, `DescribeAddon`, `ListAddons`)
- Identity provider configs
- Access policies
