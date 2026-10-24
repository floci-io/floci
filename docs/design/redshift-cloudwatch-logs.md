# Redshift CloudWatch Logs delivery design

## Goal

Deliver Redshift audit events to the existing CloudWatch Logs service when a cluster has logging enabled with `LogDestinationType=cloudwatch`. Support all Redshift log exports: `connectionlog`, `userlog`, and `useractivitylog`, for SQL submitted through both Redshift Data API and the PostgreSQL wire proxy.

## Current state

`RedshiftService` persists logging configuration but does not deliver events. `CloudWatchLogsService` already owns account-scoped log groups, streams, and event ingestion. Data API execution lives under `services/redshiftdata`; proxy authentication is in `RedshiftAuthProxy`, and SQL messages pass through `RedshiftInterceptingBridge`.

## Design

- Add a Redshift audit-log publisher that receives the cluster identity, account, region, log type, timestamp, and event payload, and writes CSV log records through `CloudWatchLogsService`.
- Use AWS's group naming convention `/aws/redshift/cluster/<clusterIdentifier>/<logType>`. Create groups and streams lazily, with a stable stream per cluster and log type. Scope all writes to the cluster's account and region.
- Emit `useractivitylog` for each submitted SQL statement on the Data API and wire paths, including statements that fail. Require `enable_user_activity_logging=true`, omit bound parameter values, and redact password literals.
- Emit `connectionlog` for authentication success and failure, connection/disconnection, and the username/database when available.
- Emit `userlog` for successful SQL operations that create, alter, rename, or drop database users. Detect the supported SQL forms at the wire interception point and the Data API execution point; do not infer success from parsing alone.
- Honor `LogExports` when present. For CloudWatch destination with omitted exports, use the Redshift service's default export set as established by current AWS behavior and compatibility evidence. S3 and S3-table destinations remain unchanged.
- Logging delivery failures must not change query results or authentication outcomes. Log a diagnostic warning with cluster and log type, without including SQL secrets.
- Disabling logging immediately stops future event delivery. Existing CloudWatch groups and streams remain, matching CloudWatch resource ownership semantics.

## Alternatives considered

1. Data API activity log only: narrow and SDK-testable, but misses proxy SQL and the other two AWS export types.
2. Activity log over Data API and proxy: useful first slice, but incomplete relative to the requested full logging integration.
3. All three exports across Data API and proxy: selected for complete coverage, with more touchpoints in connection lifecycle, SQL parsing, and post-execution outcome handling.

## Validation

- Unit tests for export selection, group/stream naming, event formatting, account/region isolation, and best-effort delivery behavior.
- Data API integration tests that enable CloudWatch logging, submit SQL, and read events through the CloudWatch Logs SDK.
- Proxy integration tests for authentication/connect/disconnect, SQL activity, and user DDL events, including failed SQL not being reported as successful user DDL.
- Verify disable logging and non-CloudWatch destinations do not emit CloudWatch events.
- Update Redshift service documentation to remove the current no-delivery limitation.

## Risks and boundaries

- AWS audit logs follow the documented CSV column order. Values unavailable at the proxy boundary are empty, while password literals are redacted from query text.
- The wire proxy observes SQL text but may not know a statement's final result at the point it is parsed. User DDL events must be tied to backend completion, not merely recognized at parse time.
- Redshift parameter-group support may affect whether `enable_user_activity_logging` can be represented; confirm current parameter behavior and make the effective enablement rule explicit in implementation and tests.
