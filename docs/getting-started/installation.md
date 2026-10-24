# Installation

Floci can be run three ways: as a Docker image, as a pre-built native binary, or built from source.

## Docker (Recommended)

No installation required beyond Docker itself.

```bash
docker pull floci/floci:latest
```

### Requirements

- Docker 20.10+
- `docker compose` v2+ (plugin syntax, not standalone `docker-compose`)

## Image Tags

Each tag combines a **variant** (what's inside) and a **channel** (how stable).

|  | Standard | Baseline (ARM64 only) | Compat (+ AWS CLI + boto3) |
|---|---|---|---|
| **Release (latest)** | `latest` ✅ | `latest-baseline` | `latest-compat` |
| **Release (pinned)** | `x.y.z` | `x.y.z-baseline` | `x.y.z-compat` |
| **Nightly (floating)** | `nightly` | — | `nightly-compat` |
| **Nightly (dated)** | `nightly-mmddyyyy` | — | `nightly-mmddyyyy-compat` |

For the full breakdown see [Docker Images](../configuration/docker-images.md).

## Choosing a tag

```yaml title="docker-compose.yml"
# Standard release — recommended for most use cases
services:
  floci:
    image: floci/floci:latest
    ports:
      - "4566:4566"
```

Use the compat image if your workflow requires the AWS CLI or boto3 available inside the container:

```yaml title="docker-compose.yml"
services:
  floci:
    image: floci/floci:latest-compat
    ports:
      - "4566:4566"
```

Standard and compat have identical startup time (~24 ms) and memory footprint (~13 MiB). On Raspberry Pi 4-class ARM64 CPUs that lack LSE, use `floci/floci:latest-baseline` (or a pinned `x.y.z-baseline` release).

## Native binary

Every release attaches a standalone executable for each platform to its
[GitHub release](https://github.com/floci-io/floci/releases). It is the same native build as the image,
without the image around it, so Floci runs with no Docker at all.

| Platform | Asset |
|---|---|
| Linux x86_64 | `floci-aws-linux-amd64` |
| Linux arm64 | `floci-aws-linux-arm64` |
| Linux arm64 without LSE (Raspberry Pi 4 and older) | `floci-aws-linux-arm64-baseline` |
| macOS Apple Silicon | `floci-aws-darwin-arm64` |
| macOS Intel | `floci-aws-darwin-amd64` |
| Windows x86_64 | `floci-aws-windows-amd64.exe` |

```bash
VERSION=x.y.z   # the release to install
ASSET=floci-aws-linux-amd64
curl -fsSLO "https://github.com/floci-io/floci/releases/download/$VERSION/$ASSET"
curl -fsSLO "https://github.com/floci-io/floci/releases/download/$VERSION/sha256sums.txt"
sha256sum --ignore-missing -c sha256sums.txt     # shasum -a 256 --ignore-missing -c on macOS
install -m 755 "$ASSET" ~/.local/bin/floci-aws
floci-aws
```

The binary is named `floci-aws` because `floci` is the [CLI](https://github.com/floci-io/floci-cli).

### Verifying a download

Each asset has a keyless [cosign](https://docs.sigstore.dev/) bundle and a GitHub build-provenance attestation:

```bash
curl -fsSLO "https://github.com/floci-io/floci/releases/download/$VERSION/$ASSET.sigstore.json"
cosign verify-blob "$ASSET" --bundle "$ASSET.sigstore.json" \
  --certificate-identity-regexp '^https://github.com/floci-io/floci/.github/workflows/release.yml@' \
  --certificate-oidc-issuer https://token.actions.githubusercontent.com
gh attestation verify "$ASSET" --repo floci-io/floci
```

### Requirements

- **Linux:** glibc 2.34 or newer (RHEL 9, Ubuntu 22.04, Debian 12, Amazon Linux 2023). Older distributions
  (RHEL 8, Amazon Linux 2) and musl-based ones such as Alpine cannot run it; use the image there.
- **macOS:** a binary downloaded with a browser is quarantined by Gatekeeper. Clear it once with
  `xattr -d com.apple.quarantine floci-aws`. Downloads made with `curl` are not quarantined.
- **Docker is optional.** Without it every service that does not run containers works as usual. The
  container-backed ones keep their control plane but start no containers (RDS, ElastiCache, MemoryDB,
  OpenSearch, Neptune, DocumentDB, MSK, EKS, Timestream for InfluxDB), or fail the request that needs one
  (Lambda, ECS, EC2 instances, CodeBuild, Batch, SageMaker, AppSync JavaScript resolvers, Pipes with Kafka
  sources, MWAA, Amazon MQ, Managed Flink, Redshift, Redshift Serverless, the RDS Data API, Athena, the ECR
  registry, CodeArtifact, Verified Permissions). Floci logs one
  warning at startup naming the affected services when Docker is unreachable. With Docker, the binary finds
  the daemon the way the Docker CLI does: `DOCKER_HOST`, then the active Docker context (Docker Desktop,
  OrbStack, Colima, Podman), then the default socket.

### Where state goes

The binary listens on `127.0.0.1:4566` and keeps state in memory, as the image does. When persistence is
turned on (`FLOCI_STORAGE_MODE=persistent`, `hybrid` or `wal`), state goes to your user data directory
rather than the working directory:

| Platform | Default path |
|---|---|
| Linux | `$XDG_DATA_HOME/floci-aws`, or `~/.local/share/floci-aws` |
| macOS | `~/Library/Application Support/floci-aws` |
| Windows | `%LOCALAPPDATA%\floci-aws` |

`FLOCI_STORAGE_PERSISTENT_PATH` overrides it. Containers the binary starts are named with the namespace
`host-<port>`, so they never collide with an image or a second binary sharing the same Docker daemon;
`FLOCI_DOCKER_RESOURCE_NAMESPACE` overrides it.

The state directory is not per port, though. Two binaries with persistence on need their own
`FLOCI_STORAGE_PERSISTENT_PATH` each, or they write the same state files. Restart a persistent binary on
the port it used before: on another port it reads its resources back, but their containers belong to the
old namespace, so container-backed resources (an RDS database, an ElastiCache cluster) come back without
their data.

### Configuring it

The binary reads the same settings as the image: `FLOCI_*` environment variables, `-D` system properties
(`floci-aws -Dfloci.port=4567`), or a `config/application.yml` in the directory it starts from. LocalStack
variables such as `PERSISTENCE` and `EDGE_PORT` are translated as described in
[Migrate from LocalStack](./migrate-from-localstack.md). Init hooks are read from
`FLOCI_INIT_HOOKS_DIR` (see [Initialization Hooks](../configuration/initialization-hooks.md)); on Windows,
`.ps1`, `.cmd` and `.bat` hooks run natively.

### Running it as a service

A systemd user unit, for example `~/.config/systemd/user/floci-aws.service`:

```ini
[Unit]
Description=Floci AWS emulator

[Service]
ExecStart=%h/.local/bin/floci-aws
Environment=FLOCI_STORAGE_MODE=persistent
Restart=on-failure

[Install]
WantedBy=default.target
```

Enable it with `systemctl --user enable --now floci-aws`. On macOS, a launchd agent in
`~/Library/LaunchAgents` with `ProgramArguments` pointing at the binary and `RunAtLoad` set does the same.
Floci stops cleanly on `SIGTERM`, so either service manager can stop it.

## Build from Source

### Prerequisites

- Java 25+
- Maven 3.9+
- (Optional) GraalVM Mandrel for native compilation
- (Optional) Docker Engine 25.0+ to build the Docker images

### Clone and run

```bash
git clone https://github.com/floci-io/floci.git
cd floci
mvn quarkus:dev          # dev mode with hot reload on port 4566
```

### Build a production JAR

```bash
mvn clean package -DskipTests
java -jar target/quarkus-app/quarkus-run.jar
```

### Build a native executable

The Makefile builds the binary inside the Quarkus builder container, so nothing but Docker is
needed on the host, and packages it the way the release workflow does:

```bash
make native          # Linux binary for the host architecture, staged in native/<arch>/
make native-image    # packaged as floci:local-native with docker/Dockerfile.native-package
make native-up       # started with docker compose, waits for /_floci/health
```

To run Floci as a plain binary on your machine, install GraalVM or Mandrel with `native-image`
(for example `sdk install java 25.0.3-graal`, or set `GRAALVM_HOME`) and build on the host:

```bash
make native-host     # target/floci-<version>-runner for this machine, no Docker
make run-native      # start it on port 4566, state under ./data
make native-install  # copy it to ~/.local/bin/floci-aws (PREFIX=/usr/local to change)
```

On Mandrel 25.0.4 and later the build needs `-H:-AOTSingleCallsiteInline`: Quarkus 3.39 turns
single-callsite inlining on and the generated REST invokers then outgrow the aarch64 branch range.
`make native-host` adds the flag when the installed `native-image` is Mandrel; GraalVM does not
know it and does not need it.

!!! note
    A native build takes about four minutes with the quick profile the Makefile uses (`-Ob`); a
    full optimisation build takes longer. `make help` lists every target.