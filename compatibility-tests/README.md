# floci-compatibility-tests

Compatibility test suite for [Floci](https://github.com/hectorvent/floci) — a local AWS emulator.

Verifies that standard AWS tooling (SDKs, CDK, OpenTofu/Terraform) works correctly against the emulator without modification. Tests run against a live Floci instance and use real AWS SDK clients — no mocks.

## Quick Start

```bash
# Install just (task runner)
# macOS: brew install just
# Linux: cargo install just

# Copy and configure environment
cp env.example .env

# Install dependencies
just setup

# Run all tests
just test-all

# Run specific SDK tests
just test-python
just test-typescript
just test-awscli
```

## Test Runners

| Module                                | Language       | Test Framework | Command                |
| ------------------------------------- | -------------- | -------------- | ---------------------- |
| [`sdk-test-python`](sdk-test-python/) | Python 3       | pytest         | `just test-python`     |
| [`sdk-test-node`](sdk-test-node/)     | TypeScript     | vitest         | `just test-typescript` |
| [`sdk-test-awscli`](sdk-test-awscli/) | Bash / AWS CLI | bats-core      | `just test-awscli`     |
| [`sdk-test-java`](sdk-test-java/)     | Java 17        | JUnit 5        | `just test-java`       |
| [`sdk-test-go`](sdk-test-go/)         | Go 1.24        | go test        | `just test-go`         |
| [`sdk-test-rust`](sdk-test-rust/)     | Rust           | cargo-nextest  | `just test-rust`       |

### IaC Compatibility

| Module                                  | Tool       | Command    |
| --------------------------------------- | ---------- | ---------- |
| [`compat-cdk`](compat-cdk/)             | AWS CDK v2 | `./run.sh` |
| [`compat-opentofu`](compat-opentofu/)   | OpenTofu   | `./run.sh` |
| [`compat-terraform`](compat-terraform/) | Terraform  | `./run.sh` |

## Prerequisites

- **Floci running** on `http://localhost:4566` (or set `FLOCI_ENDPOINT`)
- **Docker** — required for Lambda invocation tests
- **just** — task runner for orchestration

Per-module requirements:

| Module            | Requirements                        |
| ----------------- | ----------------------------------- |
| `sdk-test-python` | Python 3.9+, pip                    |
| `sdk-test-node`   | Node.js 20+, npm, vitest            |
| `sdk-test-awscli` | AWS CLI v2, bash, jq                |
| `sdk-test-java`   | Java 17+, Maven                     |
| `sdk-test-go`     | Go 1.24+                            |
| `sdk-test-rust`   | Rust (stable), Cargo, cargo-nextest |

## Setup

```bash
# Setup all SDKs
just setup

# Setup individual SDKs
just setup-python      # pip install -r requirements.txt
just setup-typescript  # npm install
just setup-awscli      # Clone bats-core, bats-support, bats-assert
```

## Running Tests

### All SDKs

```bash
just test-all
```

### Individual SDKs

```bash
# Python (pytest)
just test-python

# TypeScript (vitest)
just test-typescript

# AWS CLI (bats-core)
just test-awscli
```

Bats-based suites keep their normal console output and also write JUnit XML reports:

- `sdk-test-awscli/test-results/junit.xml`
- `compat-cdk/test-results/junit.xml`
- `compat-terraform/test-results/junit.xml`
- `compat-opentofu/test-results/junit.xml`

## Configuration

All modules read from environment variables (see `.env.example`):

```bash
FLOCI_ENDPOINT=http://localhost:4566
AWS_ACCESS_KEY_ID=test
AWS_SECRET_ACCESS_KEY=test
AWS_DEFAULT_REGION=us-east-1
```

## Running with Docker

Each module includes a `Dockerfile` for isolated execution:

```bash
# Python
docker build -t floci-sdk-python sdk-test-python/
docker run --rm --network host floci-sdk-python pytest

# TypeScript
docker build -t floci-sdk-node sdk-test-node/
docker run --rm --network host floci-sdk-node npm test
```

On macOS/Windows, use `host.docker.internal` instead of `localhost`:

```bash
docker run --rm -e FLOCI_ENDPOINT=http://host.docker.internal:4566 floci-sdk-python pytest
```

## Exit Codes

All test runners exit `0` on full pass and non-zero if any test fails — suitable for CI pipelines.
