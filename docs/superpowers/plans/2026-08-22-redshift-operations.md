# Redshift Core Operations Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Implement Snapshot (Backup/Restore) and Parameter Groups APIs for the AWS Redshift service emulator.

**Architecture:** Extend the existing `RedshiftQueryHandler` and `RedshiftService`. Use Docker's exec API to run `pg_dump` and `psql` for real snapshotting capabilities.

**Tech Stack:** Java, Quarkus, JUnit 5, AWS SDK v2, Testcontainers.

## Global Constraints

- Must follow existing Floci patterns (`StorageFactory`, `AwsException`).
- Output XML must match AWS Query format.
- Code must be formatted according to Floci's existing checkstyle/conventions.

---

### Task 1: Domain Models and Service State

**Files:**
- Create: `src/main/java/io/github/hectorvent/floci/services/redshift/model/Snapshot.java`
- Create: `src/main/java/io/github/hectorvent/floci/services/redshift/model/ClusterParameterGroup.java`
- Modify: `src/main/java/io/github/hectorvent/floci/services/redshift/RedshiftService.java`

**Interfaces:**
- Produces: `Snapshot`, `ClusterParameterGroup` models, and methods in `RedshiftService` to save/retrieve them.

- [ ] **Step 1: Write Snapshot model**
Create `Snapshot.java` with properties `snapshotIdentifier`, `clusterIdentifier`, `status`, `port`, `masterUsername`. Use `@RegisterForReflection`.

- [ ] **Step 2: Write ClusterParameterGroup model**
Create `ClusterParameterGroup.java` with properties `parameterGroupName`, `parameterGroupFamily`, `description`. Use `@RegisterForReflection`.

- [ ] **Step 3: Update RedshiftService**
Add methods to create, get, and delete Snapshots and ParameterGroups. Store them in the `StorageBackend` or a `ConcurrentHashMap` managed by `StorageFactory`.

- [ ] **Step 4: Commit**
`git add ... && git commit -m "feat: add redshift operations models and service logic"`

---

### Task 2: Snapshot Data Plane (Docker Exec)

**Files:**
- Modify: `src/main/java/io/github/hectorvent/floci/services/redshift/container/RedshiftContainerManager.java`

**Interfaces:**
- Consumes: `RedshiftService` logic.
- Produces: `createSnapshot(Cluster)` returning SQL string, `restoreSnapshot(Cluster, String)` accepting SQL string.

- [ ] **Step 1: Implement createSnapshot (pg_dump)**
Add a method `String takeSnapshot(String clusterId, String username, String dbname)`. Retrieve the container, use Docker Exec API to run `["pg_dump", "-U", username, dbname]`. Return the standard output as a String.

- [ ] **Step 2: Implement restoreSnapshot (psql)**
Add a method `void restoreSnapshot(String clusterId, String username, String dbname, String sqlDump)`. Retrieve the container, use Docker Exec API to run `["psql", "-U", username, "-d", dbname]` and pipe the `sqlDump` into its standard input. Wait for completion.

- [ ] **Step 3: Wire into RedshiftService**
Call these methods during `CreateClusterSnapshot` and `RestoreFromClusterSnapshot` respectively. Save the returned dump to `StorageFactory`.

- [ ] **Step 4: Commit**
`git commit -m "feat: implement pg_dump and psql for redshift snapshots"`

---

### Task 3: Query Handler APIs

**Files:**
- Modify: `src/main/java/io/github/hectorvent/floci/services/redshift/RedshiftQueryHandler.java`

**Interfaces:**
- Consumes: `RedshiftService` methods.

- [ ] **Step 1: Implement Snapshot Actions**
Add handling for `CreateClusterSnapshot`, `DescribeClusterSnapshots`, `DeleteClusterSnapshot`, `RestoreFromClusterSnapshot` inside `handle(...)`. Format XML responses correctly.

- [ ] **Step 2: Implement ParameterGroup Actions**
Add handling for `CreateClusterParameterGroup`, `DescribeClusterParameterGroups`, `DescribeClusterParameters`. Format XML responses correctly.

- [ ] **Step 3: Commit**
`git commit -m "feat: add redshift core operations query actions"`

---

### Task 4: Integration Testing

**Files:**
- Create: `compatibility-tests/sdk-test-java/src/test/java/com/floci/test/RedshiftOperationsTest.java`

**Interfaces:**
- Consumes: AWS Java SDK v2 (`RedshiftClient`), JDBC.

- [ ] **Step 1: Write integration test**
Create a test that:
1. Creates a cluster.
2. Uses JDBC to create a table and insert a row.
3. Calls `createClusterSnapshot` via AWS SDK.
4. Drops the table.
5. Calls `restoreFromClusterSnapshot` to a NEW cluster.
6. Uses JDBC to query the new cluster and verify the row exists.

- [ ] **Step 2: Run test and verify pass**
`./mvnw test -Dtest=RedshiftOperationsTest`

- [ ] **Step 3: Commit**
`git commit -m "test: add redshift operations integration test"`
