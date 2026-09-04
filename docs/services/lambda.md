# Lambda

**Protocol:** REST JSON
**Endpoint:** `http://localhost:4566/2015-03-31/functions/...`

Floci Lambda runs your function code locally inside real Docker containers - close enough as AWS Lambda does (using Firecracker micro VM).

## Supported Operations

| Operation | Description |
|---|---|
| `CreateFunction` | Deploy a Lambda function |
| `GetFunction` | Get function details and download URL |
| `GetFunctionConfiguration` | Get runtime configuration |
| `ListFunctions` | List all functions |
| `UpdateFunctionCode` | Upload new code |
| `UpdateFunctionConfiguration` | Update runtime, handler, memory, timeout, environment, architectures, tracing, layers, and more |
| `DeleteFunction` | Remove a function |
| `Invoke` | Invoke a function synchronously or asynchronously |
| `CreateEventSourceMapping` | Connect SQS / Kinesis / DynamoDB Streams to a function |
| `GetEventSourceMapping` | Get event source mapping details |
| `ListEventSourceMappings` | List all event source mappings |
| `UpdateEventSourceMapping` | Update a mapping |
| `DeleteEventSourceMapping` | Remove a mapping |
| `PublishVersion` | Publish an immutable version |
| `ListVersionsByFunction` | List all published versions of a function |
| `CreateAlias` | Create a named alias pointing to a version |
| `GetAlias` | Get alias details |
| `ListAliases` | List all aliases for a function |
| `UpdateAlias` | Update an alias |
| `DeleteAlias` | Delete an alias |
| `AddPermission` | Add a resource-policy statement |
| `GetPolicy` | Get the function resource policy |
| `RemovePermission` | Remove a resource-policy statement |
| `GetFunctionCodeSigningConfig` | Return code-signing config (always empty) |
| `ListFunctionsByCodeSigningConfig` | Validates the ARN; no code-signing config can exist, so every well-formed ARN returns `ResourceNotFoundException` |
| `CreateFunctionUrlConfig` | Provision a function URL |
| `GetFunctionUrlConfig` | Read function URL config |
| `UpdateFunctionUrlConfig` | Update function URL config |
| `DeleteFunctionUrlConfig` | Delete function URL config |
| `ListTags` | List tags on a function |
| `TagResource` | Tag a function |
| `UntagResource` | Untag a function |
| `PutFunctionConcurrency` | Set reserved concurrent executions |
| `GetFunctionConcurrency` | Get reserved concurrent executions |
| `DeleteFunctionConcurrency` | Clear reserved concurrent executions |
| `GetAccountSettings` | Account limits plus usage derived from the caller's stored functions |

## Hot-Reloading via Reactive S3 Sync

Floci supports an automatic hot-reloading mechanism when functions are deployed via S3. This follows the standard AWS behavior where S3 and Lambda interact, but is optimized for a seamless local development experience.

When a Lambda function is created using an S3 bucket and key, Floci maintains a link between the function and its source object. Any subsequent update to that S3 object (e.g., via `s3:PutObject`) automatically triggers a reactive synchronization:

1.  **Detection**: Floci detects the S3 update via an internal event system.
2.  **Synchronization**: The new code is automatically re-extracted to the local code storage.
3.  **Invalidation**: Any active "warm" containers for that function are proactively drained.
4.  **Reload**: The very next invocation starts a fresh container with the updated code.

This allows you to update your Lambda code by simply re-uploading your ZIP to S3, without having to manually call `UpdateFunctionCode` or restart any containers.

### Example

```bash
# 1. Create a function linked to S3
aws lambda create-function \
  --function-name my-function \
  --code S3Bucket=my-bucket,S3Key=function.zip \
  ...

# 2. Invoke (starts a warm container)
aws lambda invoke --function-name my-function out.json

# 3. Update the code in S3 (Triggers Reactive Sync)
aws s3 cp updated-function.zip s3://my-bucket/function.zip

# 4. Invoke again (automatically picks up the new code)
aws lambda invoke --function-name my-function out.json
```

!!! note "Standard Behavior"
    This mechanism requires no custom configuration or non-standard magic strings. It works with standard AWS SDKs and CLI tools, providing a "live" development feel while staying within the AWS API contract.

## Hot-Reload via Bind Mount

For the tightest inner-loop development cycle, Floci supports a **bind-mount hot-reload** mode. Instead of packaging code into a ZIP and uploading it to S3, you point Floci directly at a directory on your host machine. The directory is bind-mounted into `/var/task` inside the container, so every invocation runs the files as they currently exist on disk, with no upload or redeploy.

This is enabled by using the magic bucket name `hot-reload` when creating a function:

```bash
aws lambda create-function \
  --function-name my-function \
  --runtime nodejs22.x \
  --role arn:aws:iam::000000000000:role/lambda-role \
  --handler index.handler \
  --code S3Bucket=hot-reload,S3Key=/absolute/path/to/your/code \
  --endpoint-url http://localhost:4566
```

The `S3Key` must be an **absolute path** reachable by the Docker daemon. When Floci runs in Docker Compose, this is the path on the Docker host (the machine running Docker), not the path inside the Floci container.

### How it works

1. `CreateFunction` with `S3Bucket=hot-reload` marks the function as a hot-reload function; `S3Key` is stored as the host-side path.
2. On each invocation, Floci starts a **fresh ephemeral container** with the host path bind-mounted at `/var/task`.
3. The container executes the files as they exist at invocation time. Editing a file and immediately invoking picks up the change without any API call.
4. After the invocation completes the container is stopped and removed, ensuring the next invocation always sees the current state of the directory.

### Configuration

Hot-reload must be enabled explicitly. By default it is disabled so that `S3Bucket=hot-reload` is treated as a regular S3 bucket name.

```bash
FLOCI_SERVICES_LAMBDA_HOT_RELOAD_ENABLED=true

# Optional: restrict which host paths may be bind-mounted (comma-separated)
FLOCI_SERVICES_LAMBDA_HOT_RELOAD_ALLOWED_PATHS=/home/user/projects,/tmp
```

**Docker Compose setup**: enable the feature and share the Docker socket:

```yaml
services:
  floci:
    volumes:
      - /var/run/docker.sock:/var/run/docker.sock
    environment:
      FLOCI_SERVICES_LAMBDA_HOT_RELOAD_ENABLED: "true"
```

### Limitations

- The `S3Key` path is interpreted by the **Docker daemon**, not by Floci. When Floci itself runs inside Docker, the path must exist on the Docker host machine, not inside the Floci container.
- Hot-reload containers are always ephemeral, so there is no warm-container reuse. Each invocation pays a cold-start penalty.
- `UpdateFunctionCode` on a hot-reload function converts it back to a standard Zip function (the hot-reload bind-mount is removed).
- S3 reactive sync is skipped for hot-reload functions because edits are picked up directly from disk.

### Difference from Reactive S3 Sync

| | Reactive S3 Sync | Bind-Mount Hot-Reload |
|---|---|---|
| Trigger | Upload a new ZIP to S3 | Edit files on disk |
| Cold start | Only after upload | Every invocation |
| Requires upload step | Yes | No |
| Works without `hot-reload` enabled | Yes | No |
| Path on host required | No | Yes |

!!! note "Concurrency enforcement"
    Reserved concurrency is enforced: invocations beyond the reserved value
    return `TooManyRequestsException` (HTTP 429). Functions without a reserved
    value share a **per-region** pool. AWS Lambda's "account-level" limit is
    in fact a per-account-per-region quota, and Floci mirrors that by
    partitioning counters on the ARN's region segment. The pool size (default
    1000) is configurable via `floci.services.lambda.region-concurrency-limit`
    and applies independently to each region. `PutFunctionConcurrency`
    validates that the requested value leaves at least
    `floci.services.lambda.unreserved-concurrency-min` (default 100) available
    for unreserved functions in that region. `PutProvisionedConcurrencyConfig`
    and related provisioned-concurrency operations remain unimplemented.

    Reducing or clearing a function's reserved value does not kill
    invocations that are already in flight. This matches AWS, which
    applies changes only to new invocations. As a consequence, during the
    drain window `Σreserved-inflight + unreserved-inflight` can briefly
    exceed `region-concurrency-limit`.

Function URLs are also reachable directly on `/{proxy:.*}` under the Lambda URL controller, which routes the request into the normal `Invoke` path.

**Versions:** `CreateFunction` and `UpdateFunctionCode` honour `Publish`, publishing a version
and reporting it in the response's `Version`. The two differ in the ARN they return, matching the
live service: `CreateFunction` keeps the unqualified `FunctionArn` while `UpdateFunctionCode`
returns the qualified one (`...:function:name:2`), as `PublishVersion` does. Without `Publish`
both answer for `$LATEST` and create nothing. `UpdateFunctionConfiguration` has no `Publish`
parameter in the AWS API and none here.

`DeleteFunction` honours `Qualifier` — it removes that published version only, leaving `$LATEST`,
the other versions and the function's aliases in place; without a qualifier the whole function
goes. Matching the live service, deleting `$LATEST` by qualifier and naming an alias are both
rejected with `InvalidParameterValueException`, a version an alias points at is a
`ResourceConflictException`, and a version that does not exist is a silent success rather than
a 404.

**Layers:** `PublishLayerVersion`, `GetLayerVersion`, `GetLayerVersionByArn`, `ListLayerVersions`,
`ListLayers`, and `DeleteLayerVersion` are implemented, with real local storage under
`{lambda.codePath}/layers/{name}/{version}`. `ListLayers` and `ListLayerVersions` honour
`CompatibleRuntime`, `CompatibleArchitecture`, `MaxItems` (1-50, defaulting to 50) and `Marker`,
and always emit `NextMarker`, null on the last page. Under a filter, `LatestMatchingVersion` is
the newest version that matches rather than the newest overall, and a layer with no matching
version is omitted; a version published without `CompatibleArchitectures` matches neither
architecture. `Marker` is opaque and signed with a key generated at startup, so a fabricated,
edited or previous-run token is rejected with `InvalidParameterValueException` rather than
applied as a cursor. One divergence: a parameter sent with an empty value
(`?CompatibleRuntime=`) is treated as absent rather than rejected, because RESTEasy binds an
empty query value as null. `CreateFunction`/`UpdateFunctionConfiguration`
validate each `Layers` ARN eagerly against that storage, matching real AWS - an unresolvable ARN
is rejected with `InvalidParameterValueException`, not silently accepted. Only resolves layers
published into this same local Floci instance; a real AWS-owned layer ARN (e.g. the AWS AppConfig
Extension or a Datadog-published layer) can never resolve here, since there's no mechanism in
Floci for fetching real AWS content - publish your own copy of the layer's content locally under
a name you control and reference that ARN instead.

## Not Implemented

These AWS Lambda operations have no handler in Floci. Calls will return `404` or an error:

- Layer permissions (`AddLayerVersionPermission`, `RemoveLayerVersionPermission`, `GetLayerVersionPolicy`)
- Provisioned concurrency (`PutProvisionedConcurrencyConfig`, `GetProvisionedConcurrencyConfig`, `ListProvisionedConcurrencyConfigs`, `DeleteProvisionedConcurrencyConfig`)
- Dead-letter, async invoke config, and event invoke config operations
- `InvokeWithResponseStream`
- Code signing management (only `GetFunctionCodeSigningConfig` and `ListFunctionsByCodeSigningConfig` are wired; there is no `PutFunctionCodeSigningConfig` or `CreateCodeSigningConfig`, so no code-signing config can exist and `ListFunctionsByCodeSigningConfig` reports every well-formed ARN as `ResourceNotFoundException` — a malformed ARN or an out-of-range `MaxItems` is rejected with `InvalidParameterValueException` first)

## Configuration

!!! warning "Lambda container architecture"
    Floci uses the Docker host architecture by default. Set
    `FLOCI_SERVICES_LAMBDA_HONOUR_ARCHITECTURES=true` to run each function with
    its declared `arm64` or `x86_64` architecture. The Docker host must support
    the selected architecture. Foreign architectures require Docker Desktop or
    host emulation such as `binfmt_misc` with QEMU. Floci does not fall back to
    the host architecture when this setting is enabled.

| Variable | Default | Description |
|---|---|---|
| `FLOCI_SERVICES_LAMBDA_ENABLED` | `true` | Enable or disable the service |
| `FLOCI_SERVICES_LAMBDA_EPHEMERAL` | `false` | Remove containers after each invocation |
| `FLOCI_SERVICES_LAMBDA_HONOUR_ARCHITECTURES` | `false` | Select each function's declared architecture for Docker image pulls and containers |
| `FLOCI_SERVICES_LAMBDA_DEFAULT_MEMORY_MB` | `128` | Default function memory (MB) |
| `FLOCI_SERVICES_LAMBDA_DEFAULT_TIMEOUT_SECONDS` | `3` | Default function timeout (seconds) |
| `FLOCI_SERVICES_LAMBDA_RUNTIME_API_BASE_PORT` | `12000` | First port in the Lambda Runtime API range |
| `FLOCI_SERVICES_LAMBDA_RUNTIME_API_MAX_PORT` | `12499` | Last port in the Lambda Runtime API range. One port is held per running container, so the range width caps concurrent executions |
| `FLOCI_SERVICES_LAMBDA_CODE_PATH` | `./data/lambda-code` | Directory where Lambda ZIP files are stored |
| `FLOCI_SERVICES_LAMBDA_POLL_INTERVAL_MS` | `1000` | Event-source mapping poll interval (milliseconds) |
| `FLOCI_SERVICES_LAMBDA_CONTAINER_IDLE_TIMEOUT_SECONDS` | `300` | Idle container shutdown timeout (seconds) |
| `FLOCI_SERVICES_LAMBDA_REGION_CONCURRENCY_LIMIT` | `1000` | Maximum concurrent executions per region |
| `FLOCI_SERVICES_LAMBDA_UNRESERVED_CONCURRENCY_MIN` | `100` | Minimum unreserved capacity `PutFunctionConcurrency` must leave |
| `FLOCI_SERVICES_LAMBDA_HOT_RELOAD_ENABLED` | `false` | Enable bind-mount hot-reload via `S3Bucket=hot-reload` |
| `FLOCI_SERVICES_LAMBDA_HOT_RELOAD_ALLOWED_PATHS` | *(unset)* | Comma-separated allowlist of host paths that may be bind-mounted |
| `FLOCI_SERVICES_LAMBDA_DOCKER_NETWORK` | *(unset)* | Docker network to attach Lambda containers to (overrides `FLOCI_SERVICES_DOCKER_NETWORK`) |
| `FLOCI_SERVICES_LAMBDA_EXTRA_HOSTS` | *(unset)* | Comma-separated `hostname:ip` entries added to each Lambda container's `/etc/hosts`; `ip` may be `host-gateway`, mirroring `docker run --add-host` |
| `FLOCI_SERVICES_LAMBDA_DOCKER_HOST_OVERRIDE` | *(unset)* | Explicit host/IP that spawned Lambda containers use to reach Floci's Runtime API, bypassing auto-detection |
| `FLOCI_SERVICES_LAMBDA_CONTAINER_NAME_PREFIX` | `floci` | Base name prefix for spawned Lambda containers and code volumes (e.g. `acme` → `acme-<function>-<id>` containers, `acme-code-<function>-<hash>` volumes). Must be a valid Docker name segment (`[A-Za-z0-9][A-Za-z0-9_.-]*`); invalid values are ignored with a warning |
| `FLOCI_SERVICES_LAMBDA_CODE_VOLUME_POPULATE_CONCURRENCY` | `max(2, cpus/2)` | Maximum concurrent first-time code-volume populates. See the note below |
| `FLOCI_SERVICES_LAMBDA_EXECUTOR` | `docker` | Execution backend: `docker` (containers) or `kubernetes` (pods) |
| `FLOCI_SERVICES_LAMBDA_KUBERNETES_NAMESPACE` | `default` | Namespace Lambda pods are created in |
| `FLOCI_SERVICES_LAMBDA_KUBERNETES_LABELS` | *(unset)* | Extra pod labels as comma-separated `key=value` entries |
| `FLOCI_SERVICES_LAMBDA_KUBERNETES_FLOCI_ADDRESS` | *(unset)* | Host/IP pods use to reach Floci; auto-detected when Floci runs in-cluster |
| `FLOCI_SERVICES_LAMBDA_KUBERNETES_INIT_IMAGE` | `busybox:1.36` | Init-container image that downloads function code (needs `sh`, `wget`, `unzip`) |

!!! note "Changing the container name prefix"
    Code volumes are resolved by name, and a Floci process only manages resources under its
    own prefix — deliberately, so multiple Floci processes with different prefixes can share
    one Docker daemon without touching each other's containers and volumes. Restarting with a
    different `container-name-prefix` therefore strands the code volumes (and their completion
    markers) created under the previous prefix: they are no longer reused and no longer part of
    automatic superseded-volume cleanup. They keep the prefix-independent `floci=true` label,
    so reclaim them at any time with:

    ```bash
    docker volume prune --filter label=floci=true
    ```

!!! note "Concurrent cold starts of large functions"
    A function whose unpacked code is at least 32 MB has that code streamed once into a
    read-only Docker volume, so later cold starts mount it instead of copying. Those
    first-time *populates* are capped — a burst of them overwhelms the Docker daemon — and
    the default cap is `max(2, cpus/2)`, derived from the CPU count the JVM sees.

    Because the JVM honours the container's cgroup CPU quota, running Floci with a small CPU
    allocation collapses the cap to 2, and a burst of cold starts across *distinct* large
    functions completes in waves of two rather than in parallel. Six such functions invoked
    at once take roughly three times the wall-clock of one. Raise the cap to decouple it from
    the CPU allocation:

    ```bash
    FLOCI_SERVICES_LAMBDA_CODE_VOLUME_POPULATE_CONCURRENCY=8
    ```

    Only first-time populates are gated. Warm containers, already-populated volumes, and
    functions under 32 MB are never serialised by this.

### Runtime API host override

When a Lambda container starts, it calls back into Floci's Runtime API to fetch
events and post results. Floci auto-detects the address containers should use
for that callback (its own container IP on the shared network, or
`host.docker.internal` when running on the host). In most setups this is
correct and needs no configuration.

On unusual network topologies, for example rootless Podman, auto-detection
can pick an address the Lambda container cannot reach, and invocations fail with
`connect ECONNREFUSED <ip>:12000`. Set `FLOCI_SERVICES_LAMBDA_DOCKER_HOST_OVERRIDE`
to the host or IP that containers can actually reach Floci on, and Floci uses it
verbatim instead of auto-detecting:

```bash
FLOCI_SERVICES_LAMBDA_DOCKER_HOST_OVERRIDE=floci
```

See [Docker Configuration → Running on Podman (rootless)](../configuration/docker.md#running-on-podman-rootless)
for a full rootless Podman walkthrough.

### Docker socket requirement

With the default `docker` executor, Lambda requires the Docker socket. Mount it
in your compose file:

```yaml
services:
  floci:
    volumes:
      - /var/run/docker.sock:/var/run/docker.sock
```

If mounting the Docker socket is not acceptable (for example on a hardened
Kubernetes cluster), use the [Kubernetes executor](#kubernetes-executor)
instead — it needs no privileged access and no socket.

### Kubernetes executor

Set `FLOCI_SERVICES_LAMBDA_EXECUTOR=kubernetes` to run each Lambda execution
environment as a Kubernetes pod instead of a Docker container. This is designed
for CI/CD clusters where privileged containers and `docker.sock` access are not
allowed. Floci talks to the cluster through the standard Kubernetes API: when
running inside the cluster it uses its ServiceAccount, and when running outside
it uses your local kubeconfig. Only an inline static bearer `token` or a
client-certificate/client-key credential with a PKCS#8 private key is
supported; `tokenFile`, exec, and auth-provider credential plugins
(`aws eks get-token`, gcloud, etc.) are not, and fail with a named error.

How an invocation works:

1. On a cold start Floci creates a pod from the function's runtime image
   (`public.ecr.aws/lambda/*`, same mapping as the Docker executor).
2. An init container (`busybox` by default) downloads the function's deployment
   package — and any layers — from Floci's S3 over HTTP and unpacks them into
   shared `emptyDir` volumes at `/var/task` and `/opt`.
3. The runtime container polls Floci's Lambda Runtime API
   (`AWS_LAMBDA_RUNTIME_API`) exactly like a Docker container would.
4. Warm pods are reused across invocations and reaped after
   `FLOCI_SERVICES_LAMBDA_CONTAINER_IDLE_TIMEOUT_SECONDS` of inactivity.
   Pods left behind by a crashed Floci are swept on startup via the
   `app.kubernetes.io/managed-by=floci` label.

Lambda pods connect **back** to Floci on the main port (4566) and the Runtime
API port range (9200–9299), so those ports must be reachable from pods in the
namespace. When Floci runs in-cluster this works out of the box (pod-to-pod
traffic); when Floci runs outside the cluster, set
`FLOCI_SERVICES_LAMBDA_KUBERNETES_FLOCI_ADDRESS` to an address the cluster's
pods can reach (for example your machine's LAN IP for a `kind` cluster).

#### Required RBAC

The ServiceAccount Floci runs under needs these permissions in the Lambda
namespace. The manifest below is complete, so applying it as-is (together
with the Deployment in the next section) yields a working setup:

```yaml
apiVersion: v1
kind: ServiceAccount
metadata:
  name: floci
  namespace: floci
---
apiVersion: rbac.authorization.k8s.io/v1
kind: Role
metadata:
  name: floci-lambda
  namespace: floci
rules:
  - apiGroups: [""]
    resources: ["pods"]
    verbs: ["create", "get", "list", "watch", "delete", "deletecollection"]
  - apiGroups: [""]
    resources: ["pods/log"]
    verbs: ["get", "watch"]
  # Only needed when FLOCI_TLS_ENABLED=true (CA cert is shared via a ConfigMap)
  - apiGroups: [""]
    resources: ["configmaps"]
    verbs: ["create", "get", "update", "patch"]
---
apiVersion: rbac.authorization.k8s.io/v1
kind: RoleBinding
metadata:
  name: floci-lambda
  namespace: floci
roleRef:
  apiGroup: rbac.authorization.k8s.io
  kind: Role
  name: floci-lambda
subjects:
  - kind: ServiceAccount
    name: floci
    namespace: floci
```

#### Running Floci in-cluster

A minimal Deployment (namespace `floci` assumed, RBAC from above bound to the
`floci` ServiceAccount):

```yaml
apiVersion: apps/v1
kind: Deployment
metadata:
  name: floci
spec:
  replicas: 1
  selector:
    matchLabels: { app: floci }
  template:
    metadata:
      labels: { app: floci }
    spec:
      serviceAccountName: floci
      # Required when a Service named `floci` exists in the namespace: service
      # links inject FLOCI_PORT=tcp://<ip>:4566, which collides with Floci's
      # FLOCI_* configuration convention and fails startup.
      enableServiceLinks: false
      containers:
        - name: floci
          image: floci/floci:latest
          env:
            - name: FLOCI_SERVICES_LAMBDA_EXECUTOR
              value: kubernetes
            - name: FLOCI_SERVICES_LAMBDA_KUBERNETES_NAMESPACE
              value: floci
          ports:
            - containerPort: 4566
---
apiVersion: v1
kind: Service
metadata:
  name: floci
spec:
  selector: { app: floci }
  ports:
    - port: 4566
      targetPort: 4566
```

Lambda pods reach Floci by pod IP, so the Runtime API ports need no Service
entries; only clients of the emulator itself use port 4566.

#### Limitations

- Hot reload (both bind-mount and Reactive S3 Sync bind variants) and
  `FLOCI_SERVICES_LAMBDA_AWS_CONFIG_PATH` rely on bind mounts and are not
  supported; hot-reload functions fail to launch with a clear error.
- `Image` package type URIs are passed to the kubelet unchanged. Images in
  Floci's emulated ECR registry are not pullable by cluster nodes — pre-load
  them onto the nodes (e.g. `kind load docker-image`) or use a real registry.
- Lambda pods carry no `imagePullSecrets`. If your runtime or init images come
  from an authenticated registry, attach the pull secret to the namespace's
  `default` ServiceAccount
  (`kubectl patch serviceaccount default -p '{"imagePullSecrets":[{"name":"<secret>"}]}'`)
  so the kubelet uses it for every pod in the namespace.
- A cold start waits up to 300 seconds for the pod to reach `Running` (broken
  images and failing init containers are detected and reported much earlier).
- The init container downloads code and layers over plain HTTP on the
  emulator port even when `FLOCI_TLS_ENABLED=true`. busybox `wget` cannot
  complete a TLS handshake with Floci, and the Runtime API traffic on the
  same pod network is plain HTTP anyway. Use a NetworkPolicy if the pod
  network is part of your threat model.
- With `FLOCI_TLS_ENABLED=true`, AWS SDK calls made from inside the function
  fail TLS verification when pods reach Floci by pod IP, because the
  self-signed certificate carries no SAN for dynamic pod IPs. Set
  `FLOCI_SERVICES_LAMBDA_KUBERNETES_FLOCI_ADDRESS` to a hostname covered by
  the certificate, or provide your own certificate via `floci.tls.cert-path`.
- Prefer an IP for `FLOCI_SERVICES_LAMBDA_KUBERNETES_FLOCI_ADDRESS`. With a
  bare hostname, S3 SDKs inside functions may use virtual-hosted-style
  addressing (`bucket.<hostname>`), and nothing in the cluster resolves those
  subdomains.
- IPv6-only clusters are not supported. Floci advertises an IPv4 address to
  pods and fails fast when none is available.
- Each Floci instance sweeps all `managed-by=floci` Lambda pods in its
  namespace at startup; run multiple Floci instances in separate namespaces.
- Layers published by Floci versions before this feature have no stored
  archive; re-publish them once to make them downloadable by pods.

### S3 virtual-hosted-style addressing inside Lambda containers

AWS SDKs use **virtual-hosted-style** S3 addressing by default, forming URLs like
`https://my-bucket.s3.amazonaws.com/key`. Against Floci the same pattern becomes
`http://my-bucket.localhost.floci.io:4566/key`.

When Floci runs **inside Docker**, Lambda containers are on the same Docker
network. Docker's embedded DNS resolves the exact alias `localhost.floci.io`
correctly, but has no wildcard support. `my-bucket.localhost.floci.io`
falls through to public DNS and resolves to the wrong IP, causing the Lambda
invocation to time out.

**Floci solves this automatically** by running an embedded DNS server (UDP/53)
on its container IP. All Lambda containers launched by Floci are configured to
use it as their DNS resolver. The embedded DNS server:

- Resolves `*.localhost.floci.io` → Floci's Docker network IP
- Forwards all other queries to the upstream resolver(s) from `/etc/resolv.conf`,
  falling back to public resolvers so **public hostnames** (e.g.
  `business-api.tiktok.com`) resolve from inside Lambda containers

No extra configuration or `cap_add` is needed because Docker containers have
`CAP_NET_BIND_SERVICE` in their default capability set, so Floci (running as a
non-root user) can bind UDP/53 without any changes to your Compose file.

### VpcConfig, SnapStart and LoggingConfig

All three round-trip through `CreateFunction`, `UpdateFunctionConfiguration`,
`GetFunctionConfiguration`, `GetFunction`, `ListFunctions` and `PublishVersion`.

The response shapes are **not** the request shapes, and Floci follows the AWS model
rather than echoing the request back:

| Field | Request shape | Response shape | Extra members Floci fills in |
|---|---|---|---|
| `VpcConfig` | `VpcConfig` | `VpcConfigResponse` | `VpcId`, resolved from the first subnet via EC2 |
| `SnapStart` | `SnapStart` | `SnapStartResponse` | `OptimizationStatus` — `On` only for a published version with `ApplyOn=PublishedVersions`, `Off` for `$LATEST` |
| `LoggingConfig` | `LoggingConfig` | `LoggingConfig` | — |

`SnapStart` and `LoggingConfig` are always present in a response, as on AWS: an
unset function reads back `SnapStart={ApplyOn: None, OptimizationStatus: Off}` and
`LoggingConfig={LogFormat: Text, LogGroup: /aws/lambda/<name>}`. With
`LogFormat=JSON`, `ApplicationLogLevel` and `SystemLogLevel` are also returned,
defaulting to `INFO`. Terraform treats these as `Computed` blocks, so a missing one
is a permanent diff rather than a cosmetic omission.

`LoggingConfig` is replaced wholesale on update, not merged — an update naming only
`LogFormat` resets `LogGroup` to the default.

`LogGroup` is validated against AWS's documented constraint: 1-512 characters matching
`[.\-_/#A-Za-z0-9]+`. `ApplicationLogLevel` and `SystemLogLevel` are accepted with any
`LogFormat` but are only ever stored — and therefore only ever returned — when the
resolved format is `JSON`; supplying them with `LogFormat=Text` is not an error, it is
simply a no-op, matching the fact that the response never surfaces them for Text.

`VpcConfig` is omitted entirely while the function is not attached to a VPC.
Subnets that EC2 does not know about are still accepted and returned; only `VpcId`
is left off in that case.

`RuntimeVersionConfig.RuntimeVersionArn` is returned for managed (non-image)
runtimes. Its value is derived from the runtime name, so it is stable across
restarts.

### File system configs

`FileSystemConfigs` accepts one EFS access point and mounts it under the
requested `/mnt/...` path for local Lambda containers. As on AWS, the function
must include VPC subnet and security group configuration. The mounted path uses
the same shared-volume initialization settings as ECS EFS volumes under
`floci.storage.efs`.

This configuration is supported through the Lambda API, `AWS::Lambda::Function`
resources, and `AWS::Serverless::Function` resources.

S3 Files access points are not currently emulated and are rejected instead of
being mounted as an empty local volume.

!!! note "Resolving public hostnames from Lambda"
    A Lambda whose handler reaches a public host (`fetch()`/HTTPS to e.g.
    `business-api.tiktok.com`) resolves it through Floci's embedded DNS. As a
    safety net, Floci also appends configurable public resolvers (default
    `8.8.8.8`, `8.8.4.4`) after its own IP on each spawned container's DNS list,
    so name resolution still works if the embedded forwarder cannot answer.

    Tune or disable this for offline / locked-down networks where those resolvers
    are blocked:

    ```bash
    FLOCI_DNS_CONTAINER_FALLBACK_SERVERS=1.1.1.1,1.0.0.1   # use different resolvers
    FLOCI_DNS_CONTAINER_FALLBACK_ENABLED=false             # inject only Floci's DNS
    ```

!!! tip "Docker Compose service names"
    If Floci runs as a Docker Compose service, set `FLOCI_HOSTNAME` to the
    service name, for example `FLOCI_HOSTNAME=floci`. When no explicit Lambda
    Docker network is configured, Floci automatically attaches Lambda
    containers to the current Compose network. Floci then injects
    `AWS_ENDPOINT_URL=http://floci:4566` into Lambda containers and returns
    SQS `QueueUrl` values with the same reachable host.

    This avoids function-side rewrites from `localhost` or `localhost.floci.io`
    to `floci`, and keeps normal AWS SDK clients pointed at the Docker DNS name
    that the Lambda container can resolve.

!!! note "Path-style as a workaround"
    If you cannot use virtual-hosted-style (e.g. Floci is running natively on
    the host, not in Docker), configure the SDK client with
    `forcePathStyle: true` / `s3ForcePathStyle: true`. Requests will go to
    `http://localhost:4566/my-bucket/key` instead and work without DNS.

#### Migrating from LocalStack

If your Lambda functions have `AWS_ENDPOINT_URL=http://localhost.localstack.cloud:4566`
hardcoded, add the LocalStack suffix to Floci's DNS resolver so it resolves to
Floci's IP without any function-side changes:

Via environment variable, use a comma-separated list for multiple suffixes:

```bash
# Single suffix
FLOCI_DNS_EXTRA_SUFFIXES=localhost.localstack.cloud

# Multiple suffixes
FLOCI_DNS_EXTRA_SUFFIXES=localhost.localstack.cloud,localhost.example.internal
```

### Real AWS Credentials

By default, a Lambda function whose execution role exists in Floci's IAM store receives temporary credentials for that role. SDK calls made by the function identify as `assumed-role/<role>/floci-session`, and IAM enforcement evaluates the role's policies. If the role is unknown to Floci, the container keeps the compatibility fallback described below.

For hybrid local/cloud testing, where some services are emulated and others hit real AWS, you can mount your host `~/.aws` directory into Lambda containers:

```yaml
services:
  floci:
    image: floci/floci:latest
    environment:
      FLOCI_SERVICES_LAMBDA_AWS_CONFIG_PATH: /Users/me/.aws
    volumes:
      - /var/run/docker.sock:/var/run/docker.sock
```

When `aws-config-path` is set:

- The host path is bind-mounted **read-only** into each Lambda container at `/opt/aws-config`
- `AWS_SHARED_CREDENTIALS_FILE` and `AWS_CONFIG_FILE` env vars are set so the SDK discovers credentials regardless of the container's HOME directory
- No `AWS_ACCESS_KEY_ID` / `AWS_SECRET_ACCESS_KEY` / `AWS_SESSION_TOKEN` env vars are injected

When unset (default), Floci injects execution-role credentials for a known role. For an unknown role, Floci reads credentials from its own environment and falls back to `test`/`test`/`test`.

!!! tip "Routing specific services to real AWS"
    To keep some services on Floci while others hit real AWS, clear the global endpoint and set service-specific overrides in your function's `--environment`:

    ```
    AWS_ENDPOINT_URL=                                          # clear Floci's global endpoint
    AWS_ENDPOINT_URL_SES=http://localhost.floci.io:4566       # SES stays on Floci
    AWS_ENDPOINT_URL_CLOUDWATCHLOGS=http://localhost.floci.io:4566  # CloudWatch stays on Floci
    ```

    The AWS SDK supports `AWS_ENDPOINT_URL_<SERVICE>` natively. Services without an override will use real AWS endpoints.

!!! note "Credential passthrough without mounting"
    For functions whose execution role is unknown to Floci, you can pass static credentials to Floci's environment directly. When `aws-config-path` is unset, Floci forwards its own `AWS_ACCESS_KEY_ID`, `AWS_SECRET_ACCESS_KEY`, and `AWS_SESSION_TOKEN` env vars into those Lambda containers:

    ```yaml
    environment:
      AWS_ACCESS_KEY_ID: ${AWS_ACCESS_KEY_ID}
      AWS_SECRET_ACCESS_KEY: ${AWS_SECRET_ACCESS_KEY}
      AWS_SESSION_TOKEN: ${AWS_SESSION_TOKEN}
    ```

    A known execution role takes precedence over `AWS_ACCESS_KEY_ID`, `AWS_SECRET_ACCESS_KEY`, and `AWS_SESSION_TOKEN` values in the function environment. Use `aws-config-path` when the function must use mounted credentials instead of its emulated execution role.

    Passthrough is on whenever those three variables are set in Floci's own environment, so a
    Floci started from a shell that exports real AWS credentials (`aws-vault exec`, a sourced
    credentials file, a CI runner) hands them to any function whose role it does not know. An
    `AWS_PROFILE` or an `aws sso login` alone does not do this: those populate config and cache
    files, not the environment. Floci logs a `WARN` carrying the forwarded access-key prefix the
    first time it happens. Give the function a role Floci knows, or set `aws-config-path`, to keep
    host credentials out of the container.

### Locally built images

A container image function whose `ImageUri` names an image already present on the Docker daemon runs that image directly. This includes AWS-shaped `<account>.dkr.ecr.<region>.amazonaws.com/<repo>:<tag>` URIs, which are otherwise rewritten to [Floci's emulated ECR registry](ecr.md) at pull time: build the image under the exact URI the function declares (`docker build -t <ImageUri> .`) and no push is needed. With `FLOCI_DOCKER_IMAGE_REGISTRY_BASE` set, tag it as `<base>/<ImageUri>` instead, since that is the reference Floci launches. Set `FLOCI_SERVICES_ECR_PREFER_LOCAL_IMAGES=false` to always resolve AWS-shaped URIs through the emulated registry.

### Private registry authentication

Container image functions (`"PackageType": "Image"`) that pull from private registries need Docker credentials. See [Docker Configuration → Private Registry Authentication](../configuration/docker.md#private-registry-authentication) for the full guide.

## Examples

```bash
export AWS_ENDPOINT_URL=http://localhost:4566

# Package a simple Node.js function
cat > index.mjs << 'EOF'
export const handler = async (event) => {
  console.log("Event:", JSON.stringify(event));
  return { statusCode: 200, body: JSON.stringify({ hello: "world" }) };
};
EOF
zip function.zip index.mjs

# Deploy the function
aws lambda create-function \
  --function-name my-function \
  --runtime nodejs22.x \
  --role arn:aws:iam::000000000000:role/lambda-role \
  --handler index.handler \
  --zip-file fileb://function.zip \
  --endpoint-url $AWS_ENDPOINT_URL

# Invoke synchronously
aws lambda invoke \
  --function-name my-function \
  --payload '{"key":"value"}' \
  --cli-binary-format raw-in-base64-out \
  response.json \
  --endpoint-url $AWS_ENDPOINT_URL

cat response.json

# Invoke asynchronously
aws lambda invoke \
  --function-name my-function \
  --invocation-type Event \
  --payload '{"key":"value"}' \
  --cli-binary-format raw-in-base64-out \
  /dev/null \
  --endpoint-url $AWS_ENDPOINT_URL

# Update code
zip function.zip index.mjs
aws lambda update-function-code \
  --function-name my-function \
  --zip-file fileb://function.zip \
  --endpoint-url $AWS_ENDPOINT_URL
```

## Event Source Mappings

Connect Lambda to SQS, Kinesis, or DynamoDB Streams:

```bash
# SQS trigger
QUEUE_ARN=$(aws sqs get-queue-attributes \
  --queue-url $AWS_ENDPOINT_URL/000000000000/orders \
  --attribute-names QueueArn \
  --query Attributes.QueueArn --output text \
  --endpoint-url $AWS_ENDPOINT_URL)

aws lambda create-event-source-mapping \
  --function-name my-function \
  --event-source-arn $QUEUE_ARN \
  --batch-size 10 \
  --endpoint-url $AWS_ENDPOINT_URL
```

### ScalingConfig (SQS only)

`CreateEventSourceMapping` and `UpdateEventSourceMapping` accept a
`ScalingConfig.MaximumConcurrency` integer between 2 and 1000 on SQS
event sources, matching the AWS wire format. `GetEventSourceMapping` and
`ListEventSourceMappings` echo the value back when set; responses omit
the `ScalingConfig` field entirely when no cap is configured.

```bash
aws lambda create-event-source-mapping \
  --function-name my-function \
  --event-source-arn $QUEUE_ARN \
  --scaling-config MaximumConcurrency=5 \
  --endpoint-url $AWS_ENDPOINT_URL
```

Validation mirrors AWS: values outside 2–1000 are rejected with
`InvalidParameterValueException`, and `ScalingConfig` on a non-SQS event
source (Kinesis / DynamoDB Streams) is also rejected. Those services
use `ParallelizationFactor` instead, which is a separate field.

!!! note "Enforcement status"
    The configured `MaximumConcurrency` is persisted and returned on the
    wire, but the SQS poller does not yet cap concurrent invocations at
    this value (the poller today serializes invocations per ESM to one
    at a time regardless). Real parallel dispatch capped by
    `MaximumConcurrency` is tracked as a follow-up.

## Supported Runtimes

Any runtime that has an official AWS Lambda container image works with Floci (e.g. `nodejs22.x`, `python3.13`, `java21`, `go1.x`, `provided.al2023`).
