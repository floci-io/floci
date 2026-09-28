# sdk-test-python

Compatibility tests for [Floci](https://github.com/hectorvent/floci) using **boto3 (1.37.1)**.

## Services Covered

| Group                   | Description                                                              |
| ----------------------- | ------------------------------------------------------------------------ |
| `ssm`                   | Parameter Store — put, get, label, history, path, tags                   |
| `sqs`                   | Queues, send/receive/delete, DLQ, visibility                             |
| `sns`                   | Topics, subscriptions, publish, SQS delivery                             |
| `s3`                    | Buckets, objects, tagging, copy, batch delete                            |
| `s3-cors`               | CORS configuration                                                       |
| `s3-notifications`      | S3 → SQS event notifications                                             |
| `dynamodb`              | Tables, CRUD, batch, TTL, tags                                           |
| `lambda`                | Create/invoke/update/delete functions                                    |
| `iam`                   | Users, roles, policies, access keys                                      |
| `sts`                   | GetCallerIdentity, AssumeRole, GetSessionToken                           |
| `secretsmanager`        | Create/get/put/list/delete secrets, versioning, tags                     |
| `kms`                   | Keys, aliases, encrypt/decrypt, data keys, sign/verify                   |
| `kinesis`               | Streams, shards, PutRecord/GetRecords                                    |
| `cloudwatch-metrics`    | PutMetricData, ListMetrics, GetMetricStatistics, alarms                  |
| `cloudformation-naming` | Auto physical name generation, explicit name precedence, cross-reference |
| `cognito`               | User pools, clients, AdminCreateUser, InitiateAuth, GetUser              |

## Requirements

- Python 3.9+
- pip

## Running

```bash
pip install -r requirements.txt

# All groups
pytest tests/ --junit-xml=test-results/junit.xml

# Specific tests
pytest tests/test_s3.py

# Via just (from compatibility-tests/)
just test-python
```

## Configuration

| Variable         | Default                 | Description             |
| ---------------- | ----------------------- | ----------------------- |
| `FLOCI_ENDPOINT` | `http://localhost:4566` | Floci emulator endpoint |

AWS credentials are always `test` / `test` / `us-east-1`.

## Opt-in metric filter replay against AWS

`tests/metric_filter_replay.py` is a standalone script, not a test: pytest does
not collect it, and `tests/test_metric_filter_replay.py` checks its logic offline
with the network blocked. It does not change `conftest.py`, and `FLOCI_TARGET=aws`
does not select real AWS.

From the repository root, with the declared requirements installed:

```bash
python -m pytest compatibility-tests/sdk-test-python/tests/test_metric_filter_replay.py

# Against a local Floci
python compatibility-tests/sdk-test-python/tests/metric_filter_replay.py \
  --endpoint http://127.0.0.1:4566 --region eu-west-1 \
  --timeout 30 --stable-seconds 2 --poll-interval 1
```

Real AWS mode requires all three safety arguments. **It creates billable
CloudWatch metrics and a tagged log group, and metric series cannot be deleted**;
they remain until AWS ages them out.

```bash
python compatibility-tests/sdk-test-python/tests/metric_filter_replay.py \
  --aws --profile YOUR_DISPOSABLE_TEST_PROFILE --region eu-west-1 \
  --ack-live-writes I_ACCEPT_AWS_WRITES
```

Only commercial AWS regions are accepted, the clients pin the official regional
endpoints, and the group is deleted in `finally` only after its ownership tag is
verified. A cleanup failure exits nonzero and prints the group name. Credentials,
account ids, profile names and raw service errors are never printed.

The expected values come from `tests/fixtures/metric-filter-publishing-aws.json`,
a byte-identical copy of the root fixture
`src/test/resources/cloudwatchlogs/metric-filter-publishing-aws.json`; the root
`CloudWatchLogsMetricFilterFixturePackagingTest` fails when either SDK module copy
drifts. The replay ingests each scenario into its own backdated minute, runs the
quiet-default scenario last with no later ingestion, and accepts a result only
after every expected series, absent series and the idle minute after the quiet one
have stayed stable for a full window (AWS defaults: 240 second deadline, 30 second
window). Output is JSON lines of observations, not a regenerated fixture.

## Docker

```bash
docker build -t floci-sdk-python .
docker run --rm --network host floci-sdk-python

# Custom endpoint (macOS/Windows)
docker run --rm -e FLOCI_ENDPOINT=http://host.docker.internal:4566 floci-sdk-python
```
