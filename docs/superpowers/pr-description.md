## Summary

Implements AWS Redshift emulation for Floci, including both the Management API (Control Plane) and Data Plane via Docker.

**Phase 1 — Core Management API:**
- `CreateCluster`, `DescribeClusters`, `DeleteCluster`
- Data Plane: spins up a `postgres:15-alpine` container per cluster (same pattern as RDS)
- Wired through `AwsQueryController` -> `RedshiftQueryHandler` -> `RedshiftService`
- Storage via `StorageFactory` / `AccountAwareStorageBackend`

**Phase 2 — Operations (Snapshots & Parameter Groups):**
- `CreateClusterSnapshot`, `DescribeClusterSnapshots`, `DeleteClusterSnapshot`, `RestoreFromClusterSnapshot`
  - Real backup/restore using `pg_dump` and `psql` via Docker Exec API
  - SQL dumps streamed directly to/from disk (no in-memory buffering)
- `CreateClusterParameterGroup`, `DescribeClusterParameterGroups`, `DescribeClusterParameters`, `DeleteClusterParameterGroup`
  - Mock state - stores metadata, returns standard parameter list

## Type of change

- [ ] Bug fix (`fix:`)
- [x] New feature (`feat:`)
- [ ] Breaking change (`feat!:` or `fix!:`)
- [ ] Docs / chore

## AWS Compatibility

Verified against **AWS SDK for Java v2** (`software.amazon.awssdk:redshift`):
- `CreateCluster` / `DescribeClusters` / `DeleteCluster`
- `CreateClusterSnapshot` / `DescribeClusterSnapshots` / `RestoreFromClusterSnapshot`
- `CreateClusterParameterGroup` / `DescribeClusterParameterGroups`

Wire protocol: **AWS Query Protocol** (form-encoded POST + `Action` param, XML response) - consistent with existing SQS, SNS, RDS implementations.

Data Plane verified via JDBC (`org.postgresql:postgresql`) - create table, insert, snapshot, restore, query on restored cluster.

## Checklist

- [x] `./mvnw test` passes locally
- [x] New or updated integration test added (`RedshiftServiceTest`, `RedshiftQueryHandlerTest`, `RedshiftContainerManagerTest`, `RedshiftOperationsTest`, `compatibility-tests/.../RedshiftOperationsTest`)
- [x] Commit messages follow Conventional Commits
