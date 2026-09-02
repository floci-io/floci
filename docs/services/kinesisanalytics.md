# Managed Service for Apache Flink

**Protocol:** JSON 1.1
**Endpoint:** `http://localhost:4566/`

Floci emulates Amazon Managed Service for Apache Flink (the Kinesis Analytics V2 API). Unlike a
pure mock, Floci runs a **real Apache Flink cluster** as Docker sidecars when an application is
started and **submits the application's JAR** to it, so the application reaches a genuine `RUNNING`
state backed by a live Flink job on Floci's Docker network.

## Supported Actions

<!-- floci:actions:start -->
| Action | Description |
| --- | --- |
| `CreateApplication` | Creates a Flink application in the READY state |
| `CreateApplicationPresignedUrl` | Returns a URL to the application's Flink Dashboard |
| `DescribeApplication` | Returns details about an application |
| `ListApplications` | Lists all applications |
| `StartApplication` | Starts an application, provisioning a Flink JobManager container |
| `StopApplication` | Stops a running application and tears down its container |
| `UpdateApplication` | Updates an application (code, parallelism, execution role) and bumps its version; redeploys code in place on a running job |
| `DeleteApplication` | Deletes an application |
| `TagResource` | Assigns one or more tags to an application |
| `UntagResource` | Removes one or more tags from an application |
| `ListTagsForResource` | Lists the tags assigned to an application |
| `CreateApplicationSnapshot` | Triggers a Flink savepoint of the application's running job |
| `DescribeApplicationSnapshot` | Returns details about an application snapshot |
| `ListApplicationSnapshots` | Lists the snapshots for an application |
| `DeleteApplicationSnapshot` | Deletes an application snapshot |
<!-- floci:actions:end -->

## How it works

1. **CreateApplication**: registers the application in the `READY` state and stores its
   `ApplicationConfiguration` (the S3 location of the Flink JAR and the parallelism). No container is
   started yet — this mirrors AWS, where a freshly created application is not running.
2. **StartApplication**: reads the application JAR from Floci's local S3, launches an `apache/flink`
   **JobManager** container (plus a **TaskManager** for task slots) on Floci's Docker network, uploads
   the JAR to the cluster and runs it. The application transitions `STARTING → RUNNING` once the Flink
   job itself is `RUNNING`. An application created **without** code comes up `RUNNING` as a bare
   cluster (no job).
3. **StopApplication**: cancels the Flink job, tears down the JobManager and TaskManager, and returns
   the application to `READY`.
4. **DeleteApplication**: requires the application be stopped (`READY`); tears down any cluster and
   removes the application.

Because the Flink containers join Floci's Docker network, the job can reach `http://floci:4566` to
consume local Kinesis or MSK data streams.

### Deploying application code (a Flink JAR)

Pass an `ApplicationConfiguration` with an `ApplicationCodeConfiguration` pointing at a JAR in Floci's
local S3, exactly as with real AWS:

```bash
export AWS_ENDPOINT_URL=http://localhost:4566

# 1. Upload your Flink job JAR to (local) S3
aws s3 mb s3://flink-code
aws s3 cp my-flink-app.jar s3://flink-code/app.jar

# 2. Create the application pointing at that JAR
aws kinesisanalyticsv2 create-application \
  --application-name jobdemo --runtime-environment FLINK-1_18 \
  --service-execution-role arn:aws:iam::000000000000:role/x \
  --application-configuration '{
    "ApplicationCodeConfiguration": {
      "CodeContent": { "S3ContentLocation": {
        "BucketARN": "arn:aws:s3:::flink-code", "FileKey": "app.jar" } },
      "CodeContentType": "ZIPFILE" },
    "FlinkApplicationConfiguration": {
      "ParallelismConfiguration": { "ConfigurationType": "CUSTOM", "Parallelism": 1 } }
  }'

# 3. Start it — Floci pulls the JAR, runs it on the cluster, and reaches RUNNING
aws kinesisanalyticsv2 start-application --application-name jobdemo --run-configuration '{}'
aws kinesisanalyticsv2 describe-application --application-name jobdemo  # ApplicationStatus: RUNNING
```

The job's main class is taken from the JAR's manifest (as in AWS Managed Flink).

### Updating application code in place

`UpdateApplication` with a new `ApplicationConfigurationUpdate.ApplicationCodeConfigurationUpdate`
redeploys a new JAR onto an **already-running** application without tearing down its JobManager/
TaskManager containers: the current Flink job is cancelled and the new JAR is uploaded and run on the
same cluster, exactly the way `StartApplication` submits a job — the application briefly transitions
`RUNNING → STARTING → RUNNING` while the new job comes up. Calling `UpdateApplication` on a `READY`
application just stores the new code location for the next `StartApplication`.
`FlinkApplicationConfigurationUpdate.ParallelismConfigurationUpdate.ParallelismUpdate` is also applied.

```bash
aws kinesisanalyticsv2 update-application \
  --application-name jobdemo --current-application-version-id 1 \
  --application-configuration-update '{
    "ApplicationCodeConfigurationUpdate": { "CodeContentUpdate": { "S3ContentLocationUpdate": {
      "BucketARNUpdate": "arn:aws:s3:::flink-code", "FileKeyUpdate": "app-v2.jar" } } } }'
```

Not yet emulated: attaching code to a **bare** running cluster (one started without code) via
`UpdateApplication` — stop and start the application instead.

### Runtime properties (`EnvironmentProperties`)

Pass `ApplicationConfiguration.EnvironmentProperties.PropertyGroups` on `CreateApplication` to configure
your application without recompiling it — exactly as with real AWS. Floci writes them to
`/etc/flink/application_properties.json` inside **both** the JobManager and TaskManager containers
before the job runs, the same well-known path real Managed Service for Apache Flink uses, so a JAR
written against `KinesisAnalyticsRuntime.getApplicationProperties()` picks them up unmodified:

```bash
aws kinesisanalyticsv2 create-application \
  --application-name jobdemo --runtime-environment FLINK-1_18 \
  --service-execution-role arn:aws:iam::000000000000:role/x \
  --application-configuration '{
    "ApplicationCodeConfiguration": {
      "CodeContent": { "S3ContentLocation": {
        "BucketARN": "arn:aws:s3:::flink-code", "FileKey": "app.jar" } },
      "CodeContentType": "ZIPFILE" },
    "EnvironmentProperties": {
      "PropertyGroups": [
        { "PropertyGroupId": "ProducerConfigProperties",
          "PropertyMap": { "aws.region": "us-west-2", "flink.stream.initpos": "LATEST" } }
      ] }
  }'
```

`DescribeApplication` echoes them back under
`ApplicationConfigurationDescription.EnvironmentPropertyDescriptions.PropertyGroupDescriptions`.

### Snapshots (savepoints)

`CreateApplicationSnapshot` triggers a real Flink [savepoint](https://nightlies.apache.org/flink/flink-docs-stable/docs/ops/state/savepoints/)
of the application's running job — the application must be `RUNNING` with a deployed job. The
snapshot starts `CREATING` and transitions to `READY` (or `FAILED`) once Flink's savepoint completes,
polled the same way `StartApplication` polls job readiness. Snapshot files land on a **named Docker
volume** mounted at `/opt/flink/savepoints` in the JobManager container, not the container's ephemeral
filesystem, so they survive a `StopApplication`/`StartApplication` cycle; the volume itself is only
removed on `DeleteApplication`.

```bash
aws kinesisanalyticsv2 create-application-snapshot \
  --application-name jobdemo --snapshot-name before-upgrade

aws kinesisanalyticsv2 describe-application-snapshot \
  --application-name jobdemo --snapshot-name before-upgrade  # SnapshotStatus: CREATING -> READY

aws kinesisanalyticsv2 list-application-snapshots --application-name jobdemo

aws kinesisanalyticsv2 delete-application-snapshot \
  --application-name jobdemo --snapshot-name before-upgrade \
  --snapshot-creation-timestamp <SnapshotCreationTimestamp from describe>
```

Restoring a job *from* a snapshot (`CreateApplication`/`UpdateApplication` with a
`RestoreConfiguration` pointing at one) is not yet emulated.

Pass `ApplicationConfiguration.ApplicationSnapshotConfiguration.SnapshotsEnabled: false` on
`CreateApplication` (or `ApplicationSnapshotConfigurationUpdate.SnapshotsEnabledUpdate` on
`UpdateApplication`) to disable snapshots for an application — real AWS defaults this to `true`.
`CreateApplicationSnapshot` rejects the request with `InvalidRequestException` while disabled.

### CloudWatch Logs format

The JobManager and TaskManager are started with a `log4j-console.properties` that makes every log
line a single JSON object, matching the shape real Managed Service for Apache Flink writes to
CloudWatch Logs: `applicationARN`, `applicationVersionId`, `locationInformation`, `logger`,
`message`, `messageSchemaVersion`, `messageType`, `threadName`, `throwableInformation`. This applies
to Flink's own framework logs as well as anything a deployed JAR logs through SLF4J/Log4j2 — a JAR
whose own tests assert on this exact schema (or that simply expects its logs to reach CloudWatch
rather than being swallowed by a competing logging framework it bundles) sees the same thing here as
on real MSF. The config is written into the container before the JVM starts (config placed at
creation time, before `StartApplication`'s container start), overwriting the stock image's default,
non-JSON config entirely. `throwableInformation` is always present (an empty string when the log
event has no exception), unlike real AWS which omits the key entirely in that case. Not re-applied
on `UpdateApplication` — `applicationVersionId` in already-emitted log lines does not advance across
an in-place code update, since the JobManager/TaskManager JVMs (and therefore log4j2) are not
restarted for that, matching how real MSF also keeps the same processes running across an
`UpdateApplication`.

`applicationARN` and `applicationVersionId` are baked into the generated config as literal text, so
`CreateApplication` validates `ApplicationName` against AWS's own constraints (`[a-zA-Z0-9_.-]+`,
1-128 characters) rather than only requiring it non-blank — matching real AWS, and also keeping a
name containing `%`, `"`, `\`, or `$` out of the log4j2 pattern in the first place. `StartApplication`
re-checks the same pattern, so an application persisted by an older Floci build (before this
validation existed) fails loudly there rather than getting a corrupted `applicationARN` in its logs.

### Flink Dashboard access

`CreateApplicationPresignedUrl` (with `UrlType: FLINK_DASHBOARD_URL`) returns a URL to the running
application's Flink Dashboard — the application must be `RUNNING`:

```bash
aws kinesisanalyticsv2 create-application-presigned-url \
  --application-name jobdemo --url-type FLINK_DASHBOARD_URL
```

Real AWS returns a session-authorized, time-limited URL through its own proxy; Floci has no
equivalent session/proxy layer, so it returns the JobManager container's own REST/dashboard URL
directly — genuinely live and browsable, but not actually session-scoped or time-limited the way
`SessionExpirationDurationInSeconds` implies (the parameter is still validated to AWS's documented
1800–43200 second range). `UrlType: ZEPPELIN_UI_URL` is rejected, since Zeppelin/Studio applications
aren't supported at all (see below).

## Supported runtimes

The `RuntimeEnvironment` requested on `CreateApplication` selects the Flink image, so the running
container matches the requested version:

| RuntimeEnvironment | Image |
|---|---|
| `FLINK-2_3` | `apache/flink:2.3` |
| `FLINK-2_2` | `apache/flink:2.2` |
| `FLINK-2_1` | `apache/flink:2.1` |
| `FLINK-2_0` | `apache/flink:2.0` |
| `FLINK-1_20` | `apache/flink:1.20` |
| `FLINK-1_19` | `apache/flink:1.19` |
| `FLINK-1_18` | `apache/flink:1.18` |
| `FLINK-1_15` | `apache/flink:1.15` |

The set mirrors the runtimes AWS Managed Service for Apache Flink offers; `FLINK-1_16` and `FLINK-1_17`
are not offered by AWS and are rejected. The SQL (`SQL-1_0`) and Studio (`ZEPPELIN-FLINK-*`) runtimes
have no plain Flink image and are also rejected with `InvalidArgumentException`.

## Configuration

| Variable | Default | Description |
|---|---|---|
| `FLOCI_SERVICES_KINESIS_ANALYTICS_ENABLED` | `true` | Enable or disable the service |
| `FLOCI_SERVICES_KINESIS_ANALYTICS_MOCK` | `false` | Skip the Flink container; `StartApplication` comes up `RUNNING` immediately (useful without a Docker daemon) |
| `FLOCI_SERVICES_KINESIS_ANALYTICS_DEFAULT_IMAGE` | _(unset)_ | Optional override pinning **every** application to one image regardless of `RuntimeEnvironment`. When unset, the image is chosen from the runtime (see above) |

## Example

```bash
export AWS_ENDPOINT_URL=http://localhost:4566

# Create an application (lands in READY)
aws kinesisanalyticsv2 create-application \
  --application-name demo \
  --runtime-environment FLINK-1_18 \
  --service-execution-role arn:aws:iam::000000000000:role/x \
  --endpoint-url $AWS_ENDPOINT_URL

# Start it — spins up a real Flink JobManager container (STARTING -> RUNNING)
aws kinesisanalyticsv2 start-application --application-name demo \
  --run-configuration '{}' --endpoint-url $AWS_ENDPOINT_URL

# Check status
aws kinesisanalyticsv2 describe-application --application-name demo --endpoint-url $AWS_ENDPOINT_URL
```
