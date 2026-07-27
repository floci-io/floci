# Docker Images

Floci publishes images to multiple registries.

| Registry | Image | Channels |
|---|---|---|
| [Docker Hub](https://hub.docker.com/r/floci/floci) | `floci/floci` | Release + nightly |
| [GitHub Container Registry](https://github.com/floci-io/floci/pkgs/container/floci) | `ghcr.io/floci-io/floci` | Release + nightly |
| [ECR Public Gallery](https://gallery.ecr.aws/floci/floci) | `public.ecr.aws/floci/floci` | Nightly |

Every image tag combines two independent choices: **what's inside** (variant) and **how stable it is** (channel).

## Axis 1 — Variant (what's inside)

| Variant | Contents | When to use |
|---|---|---|
| **Standard** | Floci native binary only | General use — CI, local dev, Testcontainers **(recommended)** |
| **Compat** | Floci + Python 3 + AWS CLI + boto3 | Workflows that need AWS tooling available inside the container |

The compat image is built on top of the standard image — startup time and memory footprint are identical. Only the image size increases.

## Axis 2 — Channel (how stable)

| Channel | Source | Published |
|---|---|---|
| **Release** | Tagged version (e.g. `1.5.11`) | On every release |
| **Nightly** | Tip of `main` | Every night at 22:00 CT |

Release images are stable and recommended for most use cases. Nightly images track active development and may include unreleased changes.

## Full Tag Matrix

Combining both axes gives the complete set of published tags:

|  | Standard | Compat |
|---|---|---|
| **Release (latest)** | `latest` ✅ | `latest-compat` |
| **Release (pinned)** | `x.y.z` | `x.y.z-compat` |
| **Nightly (floating)** | `nightly` | `nightly-compat` |
| **Nightly (dated)** | `nightly-mmddyyyy` | `nightly-mmddyyyy-compat` |

Dated nightly tags (e.g. `nightly-05022026`) are fixed and never move — use them for reproducible builds from `main`.

!!! warning
    Nightly images may include unreleased or experimental changes. Use release tags in production-like environments.

## Quick Reference

```yaml title="docker-compose.yml"
# Standard release — recommended
image: floci/floci:latest

# Compat release — includes AWS CLI and boto3
image: floci/floci:latest-compat

# Pinned release — reproducible builds
image: floci/floci:1.5.11

# Nightly — track main
image: floci/floci:nightly
```

## Multi-Architecture

All images are published as multi-arch manifests supporting `linux/amd64` and `linux/arm64`. Docker selects the correct variant automatically.

## What's in the Compat Image

The compat image installs the following on top of the standard image:

- Python 3 + pip
- [AWS CLI](https://pypi.org/project/awscli/) (via pip)
- [boto3](https://pypi.org/project/boto3/) (via pip)

The AWS CLI is pre-configured to talk to the local Floci endpoint — no `--endpoint-url` flag is needed in hook scripts:

```sh
#!/bin/sh
aws sqs create-queue --queue-name my-queue   # works without --endpoint-url
aws s3 mb s3://my-bucket
```

The following environment variables are set in both the standard and compat images:

| Variable | Value |
|---|---|
| `AWS_DEFAULT_REGION` | `us-east-1` |
| `AWS_ACCESS_KEY_ID` | `test` |
| `AWS_SECRET_ACCESS_KEY` | `test` |
| `AWS_CONFIG_FILE` | `/etc/floci/aws/config` |

The compat image additionally sets:

| Variable | Value |
|---|---|
| `AWS_ENDPOINT_URL` | `http://localhost:4566` |

Override any of them at runtime via `docker run -e` or the Compose `environment` block.

## Local Development

The project ships a `docker-compose.yml` at the repository root configured for local development. By default it uses `docker/Dockerfile` (a fast JVM build suited for iteration). Switch the `dockerfile` entry to test the native image locally:

```yaml title="docker-compose.yml"
build:
  context: .
  dockerfile: docker/Dockerfile.native   # or docker/Dockerfile for fast JVM dev build
```
