# Installation

Floci can be run three ways: as a Docker image, as a pre-built native binary, or built from source.

## Docker (Recommended)

No installation required beyond Docker itself.

```bash
docker pull floci/floci:latest
```

Images are also mirrored to GitHub Container Registry — useful when Docker Hub rate limits apply:

```bash
docker pull ghcr.io/floci-io/floci:latest
```

### Requirements

- Docker 20.10+
- `docker compose` v2+ (plugin syntax, not standalone `docker-compose`)

## Image Tags

Each tag combines a **variant** (what's inside) and a **channel** (how stable).

|  | Standard | Compat (+ AWS CLI + boto3) |
|---|---|---|
| **Release (latest)** | `latest` ✅ | `latest-compat` |
| **Release (pinned)** | `x.y.z` | `x.y.z-compat` |
| **Nightly (floating)** | `nightly` | `nightly-compat` |
| **Nightly (dated)** | `nightly-mmddyyyy` | `nightly-mmddyyyy-compat` |

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

Both variants have identical startup time (~24 ms) and memory footprint (~13 MiB).

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

```bash
mvn clean package -Pnative -DskipTests
./target/floci-runner
```

!!! note
    Native compilation requires GraalVM or Mandrel with the `native-image` tool on your PATH. Build time is typically 2–5 minutes.