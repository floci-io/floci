# Docker Configuration

Floci spawns real Docker containers for services that need them: Lambda, RDS, ElastiCache, OpenSearch, MSK, and ECS. All of these share the same Docker client configuration, controlled under `floci.docker`.

## Docker Daemon Socket

By default Floci connects to the local Docker daemon via the Unix socket. Override it with `docker-host` when needed (e.g. a remote Docker host or a non-standard socket path):

```yaml
floci:
  docker:
    docker-host: unix:///var/run/docker.sock
```

**Running natively on Windows** (not inside WSL or a container): Windows has no equivalent of `/var/run/docker.sock`, so when `docker-host` is left at its default and `DOCKER_HOST` isn't set, Floci automatically falls back to Docker Desktop's named pipe (`npipe:////./pipe/docker_engine`) instead. An explicit `docker-host` or `DOCKER_HOST` always takes priority over this fallback.

Environment variable: `FLOCI_DOCKER_DOCKER_HOST`

When running Floci inside Docker Compose, mount the host socket:

```yaml
services:
  floci:
    volumes:
      - /var/run/docker.sock:/var/run/docker.sock
```

The container normally switches to the unprivileged `floci` user (UID 1001), including when started with `--user root`. If the mounted socket is accessible only to root, set the entrypoint option `FLOCI_RUN_AS_ROOT=true` to keep Floci running as root:

```bash
docker run --rm -p 4566:4566 \
  -v /var/run/docker.sock:/var/run/docker.sock \
  -e FLOCI_RUN_AS_ROOT=true \
  floci/floci:latest
```

In Docker Compose, use the same environment variable:

```yaml
services:
  floci:
    image: floci/floci:latest
    volumes:
      - /var/run/docker.sock:/var/run/docker.sock
    environment:
      FLOCI_RUN_AS_ROOT: "true"
```

Use this only when the socket's permissions require root. Without the option, Floci retains its unprivileged default. The setting applies to the container entrypoint, not to `floci.docker` configuration.

## Connection Pool

Every Docker call Floci makes goes through one shared client with a bounded connection pool. Some of those connections stay open for as long as a container runs: each Lambda container holds one (the watcher that notices its runtime exiting) plus one per Lambda extension. Log-follow streams use a second pool, described below. When those long-lived connections fill the pool, every other Docker call (create, start, stop, remove) waits for one to free up, and Floci stops making progress.

The default of 1024 connections covers the 500 concurrent Lambda containers the default [Runtime API port range](../services/lambda.md#configuration) allows. Raise it if you widen that range or run many other containers at the same time:

```yaml
floci:
  docker:
    max-connections: 1024
```

Environment variable: `FLOCI_DOCKER_MAX_CONNECTIONS`

Container log-follow streams use a second, separate pool so they can never starve create, start, stop and remove calls. Its size defaults to 512 and follows the same rule (at least 1). Exec-output and container-wait streams (CodeBuild phases, Lambda extensions and exit watchers, EKS audit followers) still use the main pool and count against `max-connections`:

```yaml
floci:
  docker:
    streaming-max-connections: 512
```

Environment variable: `FLOCI_DOCKER_STREAMING_MAX_CONNECTIONS`

When a pool is full, a Docker call waits for a free connection, then fails. The wait defaults to 30 seconds for both pools and must be at least 1 (httpclient5 reads 0 as no limit, so a call would wait forever); a failed wait is not retried, because a full pool means Floci is saturated:

```yaml
floci:
  docker:
    connection-request-timeout-seconds: 30
```

Environment variable: `FLOCI_DOCKER_CONNECTION_REQUEST_TIMEOUT_SECONDS`

A Docker call that gets no response within 5 minutes fails, on Unix-socket and TCP hosts alike. Requests that follow a container for as long as it runs (container waits, exec and attach output, followed logs, streamed stats, daemon events, image pulls and builds) have no such limit, since they can stay silent while healthy.

## Private Registry Authentication

Any service that pulls a container image from a private registry (Lambda image functions, custom OpenSearch images, private Postgres images, etc.) needs Docker credentials. Two approaches are supported and can be combined.

### Mount the host Docker config

Reuses existing `docker login` sessions and credential helpers from the host machine. Mount the host `~/.docker` directory and point Floci at it:

```yaml
services:
  floci:
    volumes:
      - /var/run/docker.sock:/var/run/docker.sock
      - ~/.docker:/root/.docker:ro
    environment:
      FLOCI_DOCKER_DOCKER_CONFIG_PATH: /root/.docker
```

Or in `application.yml`:

```yaml
floci:
  docker:
    docker-config-path: /root/.docker
```

This works with any credential helper configured on the host (`docker-credential-desktop`, `ecr-credential-helper`, etc.) as long as the helper binary is also available inside the Floci container.

### Explicit per-registry credentials

For CI environments or air-gapped setups where mounting the host filesystem is not practical:

```yaml
services:
  floci:
    environment:
      FLOCI_DOCKER_REGISTRY_CREDENTIALS_0__SERVER: myregistry.example.com
      FLOCI_DOCKER_REGISTRY_CREDENTIALS_0__USERNAME: myuser
      FLOCI_DOCKER_REGISTRY_CREDENTIALS_0__PASSWORD: mypassword
      # Add more registries by incrementing the index:
      # FLOCI_DOCKER_REGISTRY_CREDENTIALS_1__SERVER: other.registry.io
      # FLOCI_DOCKER_REGISTRY_CREDENTIALS_1__USERNAME: ...
      # FLOCI_DOCKER_REGISTRY_CREDENTIALS_1__PASSWORD: ...
```

Or in `application.yml`:

```yaml
floci:
  docker:
    registry-credentials:
      - server: myregistry.example.com
        username: myuser
        password: mypassword
      - server: other.registry.io
        username: otheruser
        password: otherpassword
```

The `server` field must match the registry hostname exactly as it appears in the image URI (e.g. `myregistry.example.com` for `myregistry.example.com/repo:tag`). Docker Hub images (e.g. `ubuntu:22.04`) have an empty hostname and are not matched by any explicit credential entry — use the Docker config mount approach for Docker Hub authentication.

### Precedence

Explicit credentials take precedence for registries they cover. For everything else, Floci falls back to the Docker config file (if `docker-config-path` is set) and then to an anonymous pull.

## Container Log Settings

Configure log rotation for all containers spawned by Floci:

```yaml
floci:
  docker:
    log-max-size: "10m"   # Max size per log file before rotation (Docker json-file format)
    log-max-file: "3"     # Number of rotated log files to retain per container
```

## Container Labels

Every container and volume Floci creates carries three reserved labels for discovery and cleanup:

| Label | Value | Purpose |
|---|---|---|
| `floci` | `true` | Umbrella across all Floci emulators — `docker ps --filter label=floci=true` |
| `floci_emulator` | `floci-aws` | Per-emulator discriminator |
| `floci_namespace` | *(the configured namespace)* | Only present when `resource-namespace` is set |

Add your own labels with `extra-labels` — they are applied to every container **and** volume Floci creates (Lambda functions and code volumes, RDS databases, ElastiCache, OpenSearch, MSK, ECS, ...):

```yaml
floci:
  docker:
    extra-labels:
      - key: "com.example.project"
        value: my-project
      - key: environment
        value: dev
```

Or via environment variables (indexed entries, like `registry-credentials`):

```yaml
services:
  floci:
    environment:
      FLOCI_DOCKER_EXTRA_LABELS_0__KEY: "com.example.project"
      FLOCI_DOCKER_EXTRA_LABELS_0__VALUE: my-project
      FLOCI_DOCKER_EXTRA_LABELS_1__KEY: environment
      FLOCI_DOCKER_EXTRA_LABELS_1__VALUE: dev
```

Extra labels are a list of key/value entries rather than a map so that label keys containing dots, colons, or uppercase characters survive the environment-variable naming convention.

!!! note
    Entries using one of the reserved keys (`floci`, `floci_emulator`, `floci_namespace`, or any key listed under [Internal Labels](#internal-labels), new or legacy) are ignored with a warning: user configuration can never break Floci's own container discovery and volume pruning, or fake the owner or identity a cleanup path trusts.

### Resource Identity Labels

A container backing an emulated AWS resource also carries labels tying it back to that resource, additive to the labels above:

| Label | Value | Purpose |
|---|---|---|
| `io.floci` | `aws` | Cloud provider, for multi-cloud discovery when several Floci-like emulators share a host |
| `io.floci.service` | e.g. `rds` | The AWS service the container backs |
| `io.floci.resource-id` | e.g. `orders-db-primary` | The resource's identifier (DB instance identifier, cluster name, function name, ...) |
| `io.floci.account` | the resolved account id | The AWS account the resource belongs to |
| `io.floci.region` | the resolved region | The AWS region the resource belongs to |

This makes `docker ps --filter label=io.floci.resource-id=orders-db-primary` resolve a specific emulated resource to its backing container directly, without inferring it from names or creation order. Applied to RDS, DocDB, ElastiCache (Redis/Valkey and Memcached), MemoryDB, Neptune, MSK, OpenSearch, ECS, EKS, AmazonMQ, MWAA, Kinesis Data Analytics (Flink), Batch, CodeBuild, Lambda, and EC2. ECR's backing registry container carries every label except `io.floci.resource-id`, since it is a shared singleton with no single resource identifier. The EventBridge Pipes Kafka bridge carries only `io.floci` and `io.floci.service=pipes`, since one bridge serves every pipe reading the same cluster. The Docker networks backing EC2 VPCs carry `io.floci`, `io.floci.service=ec2`, `io.floci.resource-id` (the VPC id) and `io.floci.region`.

The same key set is used by Floci's GCP, Azure and OCI emulators, each with its own scope keys in place of `io.floci.account` and `io.floci.region` (see the [floci-gcp](https://floci.io/floci-gcp/), [floci-az](https://floci.io/floci-az/) and [floci-oci](https://floci.io/floci-oci/) documentation).

### Internal Labels

Floci also stamps bookkeeping labels it reads back itself: which Floci deployment owns a container or network, which process run created an ECS container, and which containers belong to the security-group sandbox. Each key Floci used before it adopted the `io.floci.*` namespace is still written next to its new key with the same value, and read when the new key is missing, so resources created by an earlier version keep being found. *Legacy: still written, prefer the new key.*

| Label | Legacy alias | On | Value |
|---|---|---|---|
| `io.floci.owner` | `floci_owner_port` | ECS task containers, ECS credentials proxies, EC2 instance containers, security-group helpers | The owning deployment: `<namespace>/<port>`, or the bare API port without a resource namespace |
| `io.floci.owner` | `floci_vpc_owner_port` | EC2 VPC networks | As above |
| `io.floci.ecs.run` | `floci.ecs-run` | ECS task containers and their security-group helpers | The process run that created the container |
| `io.floci.ecs.credentials-proxy` | `floci.ecs-task-role-credentials-proxy` | ECS credentials proxies | `true` |
| `io.floci.security-group.helper` | `floci.security-group-helper` | Security-group helpers | `true` |
| `io.floci.security-group.workload` | `floci.security-group-workload` | Workloads inside a security-group helper's network namespace | `true` |
| `io.floci.component` | `floci.component` | The EventBridge Pipes Kafka bridge | `pipes-kafka-rest-bridge` |
| `io.floci.service=ec2` and `io.floci.component=vpc-network` | `floci_component=ec2-vpc` | EC2 VPC networks | |
| `io.floci.resource-id` | `floci_vpc_id` | EC2 VPC networks | The VPC id |
| `io.floci.region` | `floci_vpc_region` | EC2 VPC networks | The VPC's region |

Startup cleanup acts on an object only when every owner key it carries, new or legacy, names this deployment. An object whose new and legacy labels disagree was relabelled outside Floci and is left alone with a warning.

## Docker Network

Containers spawned by Floci (Lambda, RDS, ElastiCache, OpenSearch, MSK, ECS) need to be on the same Docker network to communicate with each other and with Floci itself.

When Floci itself runs inside Docker and no network is configured, it automatically detects the current container's Docker network and uses it for spawned containers. You only need to set this manually when you want to force a specific network.

Set the shared network at the top level:

```yaml
floci:
  services:
    docker-network: my-project_default
```

Environment variable: `FLOCI_SERVICES_DOCKER_NETWORK`

Individual services can override the network with their own `docker-network` setting (e.g. `floci.services.lambda.docker-network`).

A network mode of `host`, `none` or `container:<id>` places the spawned containers in that network namespace instead of on a Docker network. Docker publishes no host ports there, so a container on the host network serves its own port directly on the host (an OpenSearch domain listens on `9200` rather than on a port from its configured range), and Floci reaches it via `localhost`. A container placed in another container's namespace (`container:<id>`) is reached through that container's address instead: its IP on the Docker network, or `localhost` when it is itself on the host network.

!!! tip
    In Docker Compose, the default network name is `<project-name>_default`. If your compose file is in a directory named `myapp`, the network is `myapp_default`.

## Running on Podman (rootless)

Floci runs under rootless Podman, but Podman's network topology needs a few
explicit settings that Docker handles automatically. The following configuration
is known to work:

```bash
podman network create floci-net

podman run -d --name floci \
  --network floci-net \
  -p 4566:4566 \
  -v /run/user/$(id -u)/podman/podman.sock:/var/run/docker.sock:z \
  -e FLOCI_SERVICES_LAMBDA_DOCKER_NETWORK=floci-net \
  -e FLOCI_HOSTNAME=floci \
  floci/floci
```

What each setting does and why it is needed:

- **Named network (`floci-net`)** — the rootless default bridge does not assign
  reachable IPs between containers, so spawned Lambda containers cannot reach
  Floci. Create a named network and put both Floci and its Lambda containers on it.
- **`FLOCI_SERVICES_LAMBDA_DOCKER_NETWORK=floci-net`** — makes Floci attach the
  Lambda containers it spawns to that same named network.
- **`FLOCI_HOSTNAME=floci`** — gives Floci a stable name that Lambda containers
  resolve when calling back to the Runtime API.
- **`:z` on the socket mount** — relabels the Podman socket for SELinux. Without
  it, Floci fails to talk to the Podman socket: Lambda/ECR container creation
  errors with `java.io.IOException: Broken pipe`, and the **Floci UI** sidecar
  fails to launch with `java.net.BindException: Permission denied`. Use the
  lowercase `:z` (shared relabel) rather than `:Z` — the Podman API socket is
  shared with the Podman service, and `:Z` applies a container-private SELinux
  label that can break access. If `:z` is still not enough on your host, fall
  back to `--security-opt label=disable`.

!!! tip "When the Runtime API address is still unreachable"
    On some Podman network topologies the auto-detected Runtime API address
    (the host/IP Lambda containers use to call back into Floci) is still wrong,
    and invocations fail with `connect ECONNREFUSED <ip>:12000`. Set the address
    explicitly to bypass auto-detection:

    ```bash
    FLOCI_SERVICES_LAMBDA_DOCKER_HOST_OVERRIDE=floci
    ```

    This forces every spawned Lambda container to reach the Runtime API at the
    given host (here the `FLOCI_HOSTNAME` value), skipping Floci's
    auto-detection entirely. See the [Lambda docs](../services/lambda.md#configuration)
    for details.

## Transient I/O retry

All of Floci's short-lived docker calls (create/start/inspect/remove container, volume management, image operations) travel one shared daemon socket, and under fan-out load the daemon occasionally drops a connection mid-call with `java.io.IOException: Broken pipe`. Floci retries these transient failures centrally, at the docker transport layer, up to 6 attempts with a capped exponential backoff (500ms base, 8s cap); so every call site is covered without per-call configuration, and a genuine daemon rejection (a 4xx, a name conflict) still surfaces immediately.

A request is only replayed when doing so cannot change semantics: requests carrying a one-shot upload stream (e.g. copying an archive into a container), bidirectional attach streams, and `exec` requests (which would re-run the command) are never retried. Log-follow connections use a separate transport that does not retry at all. Bodyless actions whose replay changes the outcome (unnamed container create, network create, container restart, network connect and disconnect) are not retried either. A tar-writer failure on the Floci host (for example an unreadable source file) is reported at once instead of retried.

## Full Reference

| Environment variable | Default | Description |
|---|---|---|
| `FLOCI_DOCKER_DOCKER_HOST` | `unix:///var/run/docker.sock` | Docker daemon socket |
| `FLOCI_DOCKER_DOCKER_CONFIG_PATH` | _(unset)_ | Path to directory containing Docker's `config.json` |
| `FLOCI_DOCKER_MAX_CONNECTIONS` | `1024` | Connection pool size for Floci's Docker client. Each live Lambda container holds two connections, so about half this many can run at once |
| `FLOCI_DOCKER_REGISTRY_CREDENTIALS_0__SERVER` | _(unset)_ | Registry hostname for credential entry 0 |
| `FLOCI_DOCKER_REGISTRY_CREDENTIALS_0__USERNAME` | _(unset)_ | Username for credential entry 0 |
| `FLOCI_DOCKER_REGISTRY_CREDENTIALS_0__PASSWORD` | _(unset)_ | Password for credential entry 0 |
| `FLOCI_DOCKER_LOG_MAX_SIZE` | `10m` | Max container log file size before rotation |
| `FLOCI_DOCKER_LOG_MAX_FILE` | `3` | Number of rotated log files to retain |
| `FLOCI_DOCKER_EXTRA_LABELS_0__KEY` | _(unset)_ | Label key for extra-label entry 0, applied to every Floci-created container and volume (increment the index for more) |
| `FLOCI_DOCKER_EXTRA_LABELS_0__VALUE` | _(unset)_ | Label value for extra-label entry 0 |
| `FLOCI_SERVICES_DOCKER_NETWORK` | _(unset)_ | Shared Docker network for all container-based services |
| `FLOCI_SERVICES_LAMBDA_CONTAINER_NAME_PREFIX` | `floci` | Base name prefix for spawned Lambda containers and code volumes (e.g. `acme` → `acme-<function>-<id>` containers, `acme-code-<function>-<hash>` volumes; with `FLOCI_DOCKER_RESOURCE_NAMESPACE` set, the namespace follows the prefix and a short hash of it ends the volume name, e.g. `acme-<namespace>-code-<function>-<hash>-<namespace hash>`). Must be a valid Docker name segment (`[A-Za-z0-9][A-Za-z0-9_.-]*`); invalid values are ignored with a warning. See the [Lambda docs](../services/lambda.md#configuration) |
