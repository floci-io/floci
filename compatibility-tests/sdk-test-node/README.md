# sdk-test-node

Compatibility tests for [Floci](https://github.com/hectorvent/floci) using the **AWS SDK for JavaScript v3 (3.1003.0)**.

## Services Covered

| Group                   | Description                                                                                        |
| ----------------------- | -------------------------------------------------------------------------------------------------- |
| `ssm`                   | Parameter Store — put, get, label, history, path, tags                                             |
| `sqs`                   | Queues, send/receive/delete, DLQ, visibility                                                       |
| `sns`                   | Topics, subscriptions, publish, SQS delivery                                                       |
| `s3`                    | Buckets, objects, tagging, copy, batch delete                                                      |
| `s3-cors`               | CORS configuration                                                                                 |
| `s3-notifications`      | S3 → SQS and S3 → SNS event notifications                                                          |
| `dynamodb`              | Tables, CRUD, batch, TTL, tags                                                                     |
| `lambda`                | Create/invoke/update/delete functions                                                              |
| `iam`                   | Users, roles, policies, access keys                                                                |
| `sts`                   | GetCallerIdentity, AssumeRole, GetSessionToken                                                     |
| `secretsmanager`        | Create/get/put/list/delete secrets, versioning, tags                                               |
| `kms`                   | Keys, aliases, encrypt/decrypt, data keys, sign/verify                                             |
| `kinesis`               | Streams, shards, PutRecord/GetRecords                                                              |
| `cloudwatch`            | PutMetricData, ListMetrics, GetMetricStatistics, alarms                                            |
| `cloudformation-naming` | Auto physical name generation, explicit name precedence, cross-reference                           |
| `cognito`               | User pools, clients, AdminCreateUser, InitiateAuth, GetUser                                        |
| `cognito-oauth`         | Resource server CRUD, confidential clients, `/oauth2/token`, OIDC discovery, JWKS/JWT verification |

## Requirements

- Node.js 20+
- npm

## Running

```bash
npm install

# All groups
npm test

# Via just (from compatibility-tests/)
just test-typescript
```

## Configuration

| Variable         | Default                 | Description             |
| ---------------- | ----------------------- | ----------------------- |
| `FLOCI_ENDPOINT` | `http://localhost:4566` | Floci emulator endpoint |

AWS credentials are always `test` / `test` / `us-east-1`.

## Docker

```bash
docker build -t floci-sdk-node .
docker run --rm --network host floci-sdk-node

# Custom endpoint (macOS/Windows)
docker run --rm -e FLOCI_ENDPOINT=http://host.docker.internal:4566 floci-sdk-node
```
