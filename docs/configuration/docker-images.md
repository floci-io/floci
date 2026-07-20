# Docker Images

Floci publishes images to [Docker Hub (`floci/floci`)](https://hub.docker.com/r/floci/floci).

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

## Reusable Image Publishing

The `Build Floci Images` reusable workflow publishes native and compat images
for an exact branch, tag, or commit. A calling repository owns the trigger,
destination registry, credentials, and package permissions; the shared workflow
owns the native amd64 and arm64 builds, multi-architecture manifests, image
labels, and provenance output.

For example, a fork can keep this small manual caller on its default branch:

```yaml title=".github/workflows/publish-images.yml"
name: Publish Floci Images

on:
  workflow_dispatch:
    inputs:
      source-ref:
        description: Floci branch, tag, or commit to publish
        required: true
        type: string
        default: main

permissions:
  contents: read
  packages: write

jobs:
  publish:
    uses: floci-io/floci/.github/workflows/build-images.yml@main
    with:
      source-ref: ${{ inputs.source-ref }}
      image: ghcr.io/${{ github.repository_owner }}/floci
```

Pin the reusable workflow to a trusted tag or full commit SHA when the caller
requires a stable build contract. The caller's `GITHUB_TOKEN` publishes a GHCR
image only below the caller's repository owner. Calls targeting another OCI
registry must pass `REGISTRY_USERNAME` and `REGISTRY_TOKEN` through the reusable
workflow's declared secrets.

The workflow publishes commit-derived convenience tags:

- `<commit-sha-12>` for the standard native image
- `<commit-sha-12>-compat` for the compat image

A rerun for the same commit can replace either tag. The workflow therefore
returns digest-qualified native and compat references and uploads them in a
provenance JSON artifact. Downstream tests should use a digest-qualified
reference when they require an immutable image:

```text
ghcr.io/example/floci@sha256:...
```

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

The project ships a `docker-compose.yml` at the repository root configured for local development.
By default it uses `docker/Dockerfile`, a fast Ubuntu Noble/glibc JVM image suited for iteration
with Java 25. The release and nightly images use the native Dockerfiles described above. Switch the
`dockerfile` entry to test the native image locally:

```yaml title="docker-compose.yml"
build:
  context: .
  dockerfile: docker/Dockerfile.native   # or docker/Dockerfile for fast JVM dev build
```
