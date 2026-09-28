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

## Build from Source

### Prerequisites

- Java 25+
- Maven 3.9+
- (Optional) GraalVM Mandrel for native compilation

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
make native-install  # copy it to ~/.local/bin/floci (PREFIX=/usr/local to change)
```

On Mandrel 25.0.4 and later the build needs `-H:-AOTSingleCallsiteInline`: Quarkus 3.39 turns
single-callsite inlining on and the generated REST invokers then outgrow the aarch64 branch range.
`make native-host` adds the flag when the installed `native-image` is Mandrel; GraalVM does not
know it and does not need it.

!!! note
    A native build takes about four minutes with the quick profile the Makefile uses (`-Ob`); a
    full optimisation build takes longer. `make help` lists every target.