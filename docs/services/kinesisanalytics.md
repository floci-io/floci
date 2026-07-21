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
| `DescribeApplication` | Returns details about an application |
| `ListApplications` | Lists all applications |
| `StartApplication` | Starts an application, provisioning a Flink JobManager container |
| `StopApplication` | Stops a running application and tears down its container |
| `UpdateApplication` | Updates an application and bumps its version |
| `DeleteApplication` | Deletes an application |
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

The job's main class is taken from the JAR's manifest (as in AWS Managed Flink). Not yet emulated:
`EnvironmentProperties` injection, snapshots/savepoints, and in-place code updates.

## Supported runtimes

The `RuntimeEnvironment` requested on `CreateApplication` selects the Flink image, so the running
container matches the requested version:

| RuntimeEnvironment | Image |
|---|---|
| `FLINK-1_20` | `apache/flink:1.20` |
| `FLINK-1_19` | `apache/flink:1.19` |
| `FLINK-1_18` | `apache/flink:1.18` |
| `FLINK-1_17` | `apache/flink:1.17` |
| `FLINK-1_16` | `apache/flink:1.16` |
| `FLINK-1_15` | `apache/flink:1.15` |

The SQL (`SQL-1_0`) and Studio (`ZEPPELIN-FLINK-*`) runtimes have no plain Flink image and are
rejected with `InvalidArgumentException`.

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
