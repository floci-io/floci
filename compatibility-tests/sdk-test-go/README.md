# sdk-test-go

Compatibility tests for [Floci](https://github.com/hectorvent/floci) using the **AWS SDK for Go v2 (1.41.4)**.

## Services Covered

| Group              | Description                                             |
| ------------------ | ------------------------------------------------------- |
| `ssm`              | Parameter Store — put, get, label, history, path, tags  |
| `sqs`              | Queues, send/receive/delete, DLQ, visibility            |
| `sns`              | Topics, subscriptions, publish, SQS delivery            |
| `s3`               | Buckets, objects, tagging, copy, batch delete           |
| `s3-cors`          | CORS configuration                                      |
| `s3-notifications` | S3 → SQS event notifications                            |
| `dynamodb`         | Tables, CRUD, batch, TTL, tags                          |
| `lambda`           | Create/invoke/update/delete functions                   |
| `iam`              | Users, roles, policies, access keys                     |
| `sts`              | GetCallerIdentity, AssumeRole, GetSessionToken          |
| `secretsmanager`   | Create/get/put/list/delete secrets, versioning, tags    |
| `kms`              | Keys, aliases, encrypt/decrypt, data keys, sign/verify  |
| `kinesis`          | Streams, shards, PutRecord/GetRecords                   |
| `cloudwatch`       | PutMetricData, ListMetrics, GetMetricStatistics, alarms |

## Requirements

- Go 1.24+

## Running

```bash
# All groups
gotestsum --junitfile test-results.xml ./tests/...

# Specific tests
go test ./tests/ -run TestSsm

# Via just (from compatibility-tests/)
just test-go
```

## Configuration

| Variable         | Default                 | Description             |
| ---------------- | ----------------------- | ----------------------- |
| `FLOCI_ENDPOINT` | `http://localhost:4566` | Floci emulator endpoint |

AWS credentials are always `test` / `test` / `us-east-1`.

## Docker

```bash
docker build -t floci-sdk-go .
docker run --rm --network host floci-sdk-go

# Custom endpoint (macOS/Windows)
docker run --rm -e FLOCI_ENDPOINT=http://host.docker.internal:4566 floci-sdk-go
```
