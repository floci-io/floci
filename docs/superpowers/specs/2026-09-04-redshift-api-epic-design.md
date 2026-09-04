# Redshift API expansion — epic design

Umbrella design for filling out Floci's Amazon Redshift surface beyond the current
control plane plus the Simple Query DDL interceptor. This document defines the
sub-projects, their dependencies, the build order, and the shared conventions each
one follows. **Each sub-project gets its own spec and implementation plan** — this
doc is the map, not an implementation plan.

## Background

Floci today emulates Redshift as:

- A Redshift-shaped **control plane** (`RedshiftService`) — cluster lifecycle,
  snapshots, parameter groups, subnet groups, tagging — backed by one real
  PostgreSQL container per cluster (`RedshiftContainerManager`).
- A per-cluster **auth proxy** (`RedshiftAuthProxy` / `RedshiftProxyManager`) that
  validates the master password and speaks the PostgreSQL wire protocol.
- A **Simple Query DDL interceptor** (umbrella PR #2836, parts 1–5) that rewrites
  Redshift-only `CREATE TABLE` / `ALTER TABLE` keywords and, in later parts,
  simulates `COPY` / `UNLOAD` against S3.

What callers still cannot do:

- Use the **Redshift Data API** (`redshift-data`) — the HTTP/JSON API that Lambda,
  Step Functions, and EventBridge use to run SQL without opening a wire connection.
- Get **temporary database credentials** (`GetClusterCredentials`).
- Provision a cluster through **CloudFormation** (`AWS::Redshift::Cluster` today
  falls through to the generic CFN stub — a fake physical id, no container).
- Run intercepted DDL over the **Extended Query protocol** (JDBC `PreparedStatement`,
  pgjdbc default `preferQueryMode=extended`).
- Query Redshift **system / catalog views** (`SVV_TABLE_INFO`, `PG_TABLE_DEF`, …).
- **Pause / resume / resize** a cluster.

## Sub-projects

| # | Sub-project | Depends on | Rough size | ROI |
|---|---|---|---|---|
| A | Redshift Data API (`redshift-data`) | control plane (done) | large — new service | highest |
| B | `GetClusterCredentials` (+ `GetClusterCredentialsWithIAM`) | control plane, auth proxy | medium | high |
| C | CloudFormation `AWS::Redshift::Cluster` provisioner | control plane (done) | small | high (IaC) |
| D | Extended Query protocol interception | umbrella #2836 merged | medium | medium |
| E | Catalog / system views seed | container infra | medium | medium |
| F | `pause` / `resume` / `resize` cluster | control plane (done) | small–medium | low–medium |

### A. Redshift Data API

New service `redshift-data`, `application/x-amz-json-1.1`, target prefix
`RedshiftData.`. Resolves a target cluster from `ClusterIdentifier` (+ `DbUser`,
`Database`) or `SecretArn`, opens a JDBC connection straight to that cluster's
PostgreSQL container, executes SQL synchronously, stores the result in memory with
a 24 h TTL, and answers the async-shaped `DescribeStatement` / `GetStatementResult`
polling contract. Full operation list and component breakdown in the dedicated
design: `2026-09-04-redshift-data-api-design.md`.

### B. `GetClusterCredentials`

`redshift:GetClusterCredentials` returns a short-lived `DbUser` / `DbPassword` pair.
Emulation: on request, ensure the PostgreSQL role exists
(`CREATE USER … VALID UNTIL <now + DurationSeconds>` or `ALTER ROLE … VALID UNTIL`),
optionally `IN GROUP` for `DbGroups`, and return a generated password that the auth
proxy will accept until expiry. `AutoCreate=false` with a missing user is an error,
matching AWS. `GetClusterCredentialsWithIAM` maps the caller's IAM identity to a
`IAM:<user>` / `IAMR:<role>` DB user name. Interacts with the auth proxy's
`PasswordValidator`, which currently only checks the master password — it must be
widened to also accept a live non-expired temp credential.

### C. CloudFormation `AWS::Redshift::Cluster`

One `RedshiftCfnProvisioner implements CfnResourceProvisioner`, `@ApplicationScoped`,
discovered by `CloudFormationResourceRegistry`. `resourceTypes()` =
`AWS::Redshift::Cluster` (plus `AWS::Redshift::ClusterParameterGroup`,
`AWS::Redshift::ClusterSubnetGroup`, `AWS::Redshift::ClusterSecurityGroup` — the
metadata ones map straight onto existing `RedshiftService` methods). `provision`
maps template properties onto `createCluster(...)`, sets the physical id to the
cluster identifier, and populates the `Endpoint.Address` and `Endpoint.Port`
attributes for `Fn::GetAtt`. `delete` calls `deleteCluster`. Handle
replacement (new `ClusterIdentifier`) the way the RDS arm of
`CloudFormationResourceProvisioner` does. Pattern is fully established — see
`S3CfnProvisioner`.

### D. Extended Query protocol interception

Extend `PostgresWireDecoder` / `RedshiftInterceptingBridge` (delivered by umbrella
#2836) to frame `Parse` (`'P'`), `Bind` (`'B'`), `Execute` (`'E'`), `Describe`,
`Close`, and `Sync` messages, and run `RedshiftSqlInterceptor.rewrite` on the SQL
carried by a `Parse`. Everything else still forwards opaque. Removes the
`preferQueryMode=simple` requirement documented as a limitation in
`docs/services/redshift.md`. Depends on the umbrella landing first so there is one
bridge to extend rather than two.

### E. Catalog / system views seed

On container start (`RedshiftContainerManager`), run a bootstrap SQL script that
creates the most-queried Redshift catalog objects as PostgreSQL views or tables:
`PG_TABLE_DEF`, `SVV_TABLE_INFO`, `SVV_ALL_COLUMNS`, `STV_TBL_PERM`,
`STL_LOAD_ERRORS` (populated by the part 4 S3 COPY simulator), `SVL_QLOG` shape.
Best-effort: values are structural, not real query telemetry. Migration scripts and
BI tools that introspect the schema stop failing.

### F. `pause` / `resume` / `resize`

`PauseCluster` → `RedshiftContainerManager.stop` + status `paused` (keep the proxy
port reserved). `ResumeCluster` → `start` + reattach proxy + status `available`.
`ResizeCluster` → metadata only (`NodeType` / `NumberOfNodes`), same as the
`ModifyCluster` node-type path, status flip `resizing` → `available`. Also close the
known gap where `NumberOfNodes` is accepted but never stored.

## Build order

```
A ─┐         (Data API — independent, highest ROI)
C ─┴─► B ─► D ─► E ─► F
```

- **A and C are independent** of each other and of everything else — start them
  first, in parallel if capacity allows.
- **B** builds on the auth proxy and is a prerequisite for realistic IAM-auth
  scenarios; do it before D so the proxy's credential model is settled.
- **D** needs umbrella #2836 fully merged.
- **E** and **F** are standalone polish; sequence them last by ROI.

Each arrow is a separate spec → plan → implementation → PR cycle. No further
umbrella branch — the sub-projects do not share code surface the way umbrella
#2836's parts 3–5 do.

## Shared conventions

Every sub-project follows patterns already in the codebase; a spec that needs to
deviate must say so explicitly.

- **Service registration.** A new HTTP surface is a `descriptor(...)` row in
  `ResolvedServiceCatalog`, gated on its own config flag `&&` the parent
  `redshift.enabled`. JSON 1.1 services (A) register a target prefix and a
  `*JsonHandler` reached through `AwsJson11Controller`, mirroring
  `AthenaJsonHandler`. No new JAX-RS `@Path` controller unless the protocol is
  REST-JSON.
- **Config.** One `interface XxxServiceConfig` in `EmulatorConfig` with
  `@WithDefault` values, one block under `services:` in `application.yml`.
- **Storage.** Durable metadata goes through `StorageFactory.create(...)` with the
  service's own JSON file and the account-aware backend, as `RedshiftService` does.
  Transient or large data (Data API result sets) stays in memory with an explicit
  TTL and is documented as lost on restart.
- **Errors.** Throw `AwsException(errorCode, message, httpStatus)` with the exact
  AWS error code (`ValidationException`, `ResourceNotFoundException`,
  `ClusterNotFoundFault`, …). Do not invent codes.
- **CloudFormation.** New resource types are a `CfnResourceProvisioner`
  implementation registered via CDI, never a new arm of the switch in
  `CloudFormationResourceProvisioner`.
- **Docs.** Each sub-project adds or extends a page under `docs/services/` and adds
  its actions to the Service Matrix row — the matrix row is a merge gate for
  Redshift-family work.
- **Tests.** Unit tests per component; one `@QuarkusTest` integration test that
  provisions a real cluster through the control plane and exercises the new surface
  end to end; a compatibility test in `compatibility-tests/sdk-test-java` driving
  the real AWS SDK v2 client.

## Out of scope for this epic

- **Redshift Serverless** (`redshift-serverless`, `AWS::RedshiftServerless::*`,
  Data API `WorkgroupName`). Not emulated anywhere in Floci today. Every
  sub-project that meets a `WorkgroupName` / workgroup ARN rejects it with a clear
  `ValidationException` ("Redshift Serverless is not emulated"). A future
  standalone epic can add the namespace/workgroup control plane and then widen
  Data API + credentials to accept it.
- Real Redshift query semantics (distribution, sort keys, `SUPER`, Spectrum,
  Glue-backed external schemas).
- Multi-node cluster behaviour beyond stored metadata.
- Snapshot schedules, cross-region snapshot copy, IAM database authentication
  beyond the `GetClusterCredentialsWithIAM` name mapping in sub-project B.
