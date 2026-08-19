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
    Entries using one of the reserved keys (`floci`, `floci_emulator`, `floci_namespace`) are ignored with a warning — user configuration can never break Floci's own container discovery and volume pruning.

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
    and invocations fail with `connect ECONNREFUSED <ip>:9200`. Set the address
    explicitly to bypass auto-detection:

    ```bash
    FLOCI_SERVICES_LAMBDA_DOCKER_HOST_OVERRIDE=floci
    ```

    This forces every spawned Lambda container to reach the Runtime API at the
    given host (here the `FLOCI_HOSTNAME` value), skipping Floci's
    auto-detection entirely. See the [Lambda docs](../services/lambda.md#configuration)
    for details.

## Full Reference

| Environment variable | Default | Description |
|---|---|---|
| `FLOCI_DOCKER_DOCKER_HOST` | `unix:///var/run/docker.sock` | Docker daemon socket |
| `FLOCI_DOCKER_DOCKER_CONFIG_PATH` | _(unset)_ | Path to directory containing Docker's `config.json` |
| `FLOCI_DOCKER_REGISTRY_CREDENTIALS_0__SERVER` | _(unset)_ | Registry hostname for credential entry 0 |
| `FLOCI_DOCKER_REGISTRY_CREDENTIALS_0__USERNAME` | _(unset)_ | Username for credential entry 0 |
| `FLOCI_DOCKER_REGISTRY_CREDENTIALS_0__PASSWORD` | _(unset)_ | Password for credential entry 0 |
| `FLOCI_DOCKER_LOG_MAX_SIZE` | `10m` | Max container log file size before rotation |
| `FLOCI_DOCKER_LOG_MAX_FILE` | `3` | Number of rotated log files to retain |
| `FLOCI_DOCKER_EXTRA_LABELS_0__KEY` | _(unset)_ | Label key for extra-label entry 0, applied to every Floci-created container and volume (increment the index for more) |
| `FLOCI_DOCKER_EXTRA_LABELS_0__VALUE` | _(unset)_ | Label value for extra-label entry 0 |
| `FLOCI_SERVICES_DOCKER_NETWORK` | _(unset)_ | Shared Docker network for all container-based services |
| `FLOCI_SERVICES_LAMBDA_CONTAINER_NAME_PREFIX` | `floci` | Base name prefix for spawned Lambda containers and code volumes (e.g. `acme` → `acme-<function>-<id>` containers, `acme-code-<function>-<hash>` volumes). Must be a valid Docker name segment (`[A-Za-z0-9][A-Za-z0-9_.-]*`); invalid values are ignored with a warning. See the [Lambda docs](../services/lambda.md#configuration) |
