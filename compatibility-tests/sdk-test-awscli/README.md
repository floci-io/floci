# sdk-test-awscli

Compatibility tests for [Floci](https://github.com/hectorvent/floci) using the **AWS CLI v2 (2.22.35)**.

Tests are plain bash scripts that call `aws` CLI commands with `--endpoint-url` pointed at the emulator.

## Services Covered

| Group              | Description                                          |
| ------------------ | ---------------------------------------------------- |
| `ssm`              | Parameter Store — put, get, path, tags               |
| `sqs`              | Queues, send/receive/delete, attributes              |
| `sns`              | Topics, publish, attributes                          |
| `s3`               | Buckets, objects, tagging, copy, delete              |
| `dynamodb`         | Tables, put/get/update/query/delete items            |
| `iam`              | Users, roles, create/get/delete                      |
| `sts`              | GetCallerIdentity                                    |
| `ses`              | Identities, sending, quotas, notification attributes |
| `secretsmanager`   | Create/get/put/list/tag/delete secrets               |
| `kms`              | Keys, aliases, encrypt/decrypt                       |
| `cognito`          | User pools, clients                                  |
| `s3-notifications` | S3 → SQS event notifications                         |

## Requirements

- AWS CLI v2
- bash
- jq
- bats-core (installed via `just setup-awscli`)

## Running

```bash
# All groups (from compatibility-tests/)
just test-awscli

# Run bats directly
./lib/run-bats-with-junit.sh sdk-test-awscli/test/ sdk-test-awscli/test-results/junit.xml
```

## Configuration

| Variable         | Default                 | Description             |
| ---------------- | ----------------------- | ----------------------- |
| `FLOCI_ENDPOINT` | `http://localhost:4566` | Floci emulator endpoint |

AWS credentials are always `test` / `test` / `us-east-1`.

## Docker

```bash
docker build -t floci-sdk-awscli .
docker run --rm --network host floci-sdk-awscli

# Custom endpoint (macOS/Windows)
docker run --rm -e FLOCI_ENDPOINT=http://host.docker.internal:4566 floci-sdk-awscli
```
