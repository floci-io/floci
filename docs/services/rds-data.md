# RDS Data API

**Protocol:** REST JSON
**Endpoint:** `POST http://localhost:4566/{operation}`
**Backing data plane:** Local RDS MySQL / MariaDB containers

Floci implements the AWS RDS Data API routes used by AWS SDK clients and executes raw SQL against local RDS resources created through the RDS emulator. The first implementation focuses on MySQL / MariaDB compatibility for local development workflows that already use `ExecuteStatement` and transactions.

For the upstream API shape, see the AWS RDS Data API documentation:

- [Using the Data API for Aurora DB clusters](https://docs.aws.amazon.com/AmazonRDS/latest/AuroraUserGuide/data-api.html)
- [Data API operations](https://docs.aws.amazon.com/AmazonRDS/latest/AuroraUserGuide/data-api-operations.html)
- [`ExecuteStatement`](https://docs.aws.amazon.com/rdsdataservice/latest/APIReference/API_ExecuteStatement.html)
- [`BeginTransaction`](https://docs.aws.amazon.com/rdsdataservice/latest/APIReference/API_BeginTransaction.html)
- [`CommitTransaction`](https://docs.aws.amazon.com/rdsdataservice/latest/APIReference/API_CommitTransaction.html)
- [`RollbackTransaction`](https://docs.aws.amazon.com/rdsdataservice/latest/APIReference/API_RollbackTransaction.html)
- [`BatchExecuteStatement`](https://docs.aws.amazon.com/rdsdataservice/latest/APIReference/API_BatchExecuteStatement.html)

## Supported Actions

| Action | Route | Required request fields | Description |
|---|---|---|---|
| `ExecuteStatement` | `POST /Execute` | `resourceArn`, `secretArn`, `sql` | Execute raw SQL against a local RDS cluster or instance |
| `BeginTransaction` | `POST /BeginTransaction` | `resourceArn`, `secretArn` | Open a JDBC transaction and return a transaction ID |
| `CommitTransaction` | `POST /CommitTransaction` | `resourceArn`, `secretArn`, `transactionId` | Commit an open transaction |
| `RollbackTransaction` | `POST /RollbackTransaction` | `resourceArn`, `secretArn`, `transactionId` | Roll back an open transaction |

`BatchExecuteStatement` is recognized at `POST /BatchExecute` and returns an AWS-style `BadRequestException` because batch execution is not implemented yet. The deprecated `ExecuteSql` operation is also recognized at `POST /ExecuteSql` and returns an AWS-style `BadRequestException`.

## Compatibility Notes

- `resourceArn` and `secretArn` are required on Data API requests. `resourceArn` must identify an existing local RDS cluster or instance.
- `database` is optional when the resolved RDS resource has a database name; otherwise it must be provided. Transactional `ExecuteStatement` requests must use the same database as the active transaction when `database` is present.
- Transaction requests validate `resourceArn` against the active transaction resource. Floci resolves accepted ARN aliases to the local resource before comparing transaction identity.
- MySQL and MariaDB resources are supported. PostgreSQL Data API execution is not implemented yet.
- SQL is sent directly to the local database engine through JDBC. `SqlParameter` binding is not implemented yet; send raw SQL strings. Non-empty or malformed `parameters` requests return `BadRequestException`.
- Result records include Data API field variants such as `stringValue`, `longValue`, `blobValue`, `booleanValue`, `doubleValue`, and `isNull`.
- SQL errors are returned as `DatabaseErrorException` so AWS SDK callers can handle database failures with normal AWS error decoding.
- If `secretArn` points to a local Secrets Manager secret with JSON credentials (`username` or `user`, plus `password`), those credentials are used. If the secret is missing or cannot be parsed, Floci falls back to the resolved RDS resource's master credentials for local development convenience.
- `formatRecordsAs=JSON`, `formattedRecords`, `generatedFields`, and `resultSetOptions` are not implemented yet. Requests that require those unsupported result modes return `BadRequestException`.
- RDS `HttpEndpointEnabled` control-plane gating is not modeled locally; availability is controlled by `FLOCI_SERVICES_RDS_DATA_ENABLED` and whether the target local RDS resource is running.

## Configuration

| Variable | Default | Description |
|---|---|---|
| `FLOCI_SERVICES_RDS_DATA_ENABLED` | `true` | Enable or disable the RDS Data API service |
| `FLOCI_SERVICES_RDS_DATA_TRANSACTION_TTL_SECONDS` | `180` | Idle timeout, in seconds, before leaked Data API transactions expire |

The RDS Data API also requires the RDS service itself to be enabled because it resolves `resourceArn` values to local RDS containers.

## Example

```bash
export AWS_ENDPOINT_URL=http://localhost:4566

aws rds create-db-cluster \
  --db-cluster-identifier appdb \
  --engine aurora-mysql \
  --master-username admin \
  --master-user-password secret123 \
  --database-name app \
  --endpoint-url "$AWS_ENDPOINT_URL"

RESOURCE_ARN=$(aws rds describe-db-clusters \
  --db-cluster-identifier appdb \
  --query 'DBClusters[0].DBClusterArn' \
  --output text \
  --endpoint-url "$AWS_ENDPOINT_URL")

SECRET_ARN=$(aws secretsmanager create-secret \
  --name appdb/data-api \
  --secret-string '{"username":"admin","password":"secret123"}' \
  --query ARN \
  --output text \
  --endpoint-url "$AWS_ENDPOINT_URL")

aws rds-data execute-statement \
  --resource-arn "$RESOURCE_ARN" \
  --secret-arn "$SECRET_ARN" \
  --database app \
  --sql "select 1 as count" \
  --include-result-metadata \
  --endpoint-url "$AWS_ENDPOINT_URL"
```
