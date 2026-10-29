# EMR Serverless

EMR Serverless is an AWS service that provides serverless data analytics environments. Floci provides an emulator for the management-plane API (REST JSON) which covers the lifecycle of an Application and of the job runs submitted to it.

## Configuration

| Key | Description | Default |
|-----|-------------|---------|
| `floci.emrserverless.enabled` | Whether the EMR Serverless API is enabled | `true` |
| `floci.emrserverless.port` | The port the service is exposed on | `4566` |
| `floci.services.emrserverless.job-run-duration-seconds` | How long a `BATCH` job run stays `RUNNING` before it succeeds; `0` makes it `SUCCESS` as soon as it starts | `0` |
| `floci.services.emrserverless.runner-mode` | `immediate` moves job runs through their states without running anything; `docker` runs each Spark job run in a container (see [Running Spark job runs](#running-spark-job-runs)) | `immediate` |
| `floci.services.emrserverless.docker-network` | Docker network for job run containers | the global default |
| `floci.services.emrserverless.image-template` | Image for a `docker` job run whose application names no custom image, `%s` standing for the release label | `public.ecr.aws/emr-serverless/spark/%s:latest`, or `spark4/` in place of `spark/` for an `emr-spark-*` release |
| `floci.services.emrserverless.reconcile-containers-on-startup` | Remove the drivers a previous run of this Floci left on the Docker daemon before the first job run starts; turn it off when two Floci instances share a daemon with the same port and no resource namespace | `true` |

## Endpoints

The emulator implements the standard AWS `emr-serverless` service endpoints:

* `POST /applications` (CreateApplication)
* `GET /applications` (ListApplications)
* `GET /applications/{applicationId}` (GetApplication)
* `PATCH /applications/{applicationId}` (UpdateApplication)
* `DELETE /applications/{applicationId}` (DeleteApplication)
* `POST /applications/{applicationId}/start` (StartApplication)
* `POST /applications/{applicationId}/stop` (StopApplication)
* `POST /applications/{applicationId}/jobruns` (StartJobRun)
* `GET /applications/{applicationId}/jobruns` (ListJobRuns)
* `GET /applications/{applicationId}/jobruns/{jobRunId}` (GetJobRun)
* `DELETE /applications/{applicationId}/jobruns/{jobRunId}` (CancelJobRun)
* `GET /applications/{applicationId}/jobruns/{jobRunId}/attempts` (ListJobRunAttempts)
* `POST /tags/{resourceArn}` (TagResource)
* `DELETE /tags/{resourceArn}?tagKeys=...` (UntagResource)
* `GET /tags/{resourceArn}` (ListTagsForResource)

The tagging operations work on application and job run ARNs, as `CreateApplication` and `StartJobRun`
return them (`arn:aws:emr-serverless:<region>:<account>:/applications/<id>` and
`.../applications/<id>/jobruns/<id>`). They share the tags those calls store, so `GetApplication` and
`GetJobRun` always report the current set. Following the EMR Serverless user guide, a resource holds
at most 50 tags, and `aws:` in any case is reserved as the prefix of a tag key or value. A session ARN
(`.../sessions/<id>`) answers `ResourceNotFoundException`, as sessions are not emulated. Once an
application is deleted, its ARN and its runs' ARNs answer `ResourceNotFoundException` too, while
`GetApplication` and `GetJobRun` still report them.

Applications, job runs, creation client tokens, and tags are scoped to the request's AWS Region. An
application or run created in one Region is not returned by list, get, or tag requests signed for
another.

`ListApplications` refuses a `states` filter naming a state that does not exist, or more than 7, and a
`maxResults` outside 1 to 50, with the `ValidationException` AWS's validation layer answers.

`CreateApplication` stores every configuration `UpdateApplication` does, `runtimeConfiguration`,
`monitoringConfiguration`, `schedulerConfiguration`, `diskEncryptionConfiguration`,
`interactiveConfiguration`, `identityCenterConfiguration` and `jobLevelCostAllocationConfiguration`
among them, and `GetApplication` returns each as it was written.

`DeleteApplication` leaves the application in `TERMINATED` state. `GetApplication` continues to
report that state so lifecycle waiters can observe it, while an unfiltered `ListApplications`
omits the terminated application. A terminated application cannot be started or stopped again.

## Job runs

In the default `immediate` runner mode, job runs are metadata only: nothing executes. A run follows
the job run states and `GetJobRun`'s response shape, and settles when it is read. In the `docker`
runner mode a Spark run executes; see [Running Spark job runs](#running-spark-job-runs).

* **Lifecycle**: a run is `RUNNING` from the moment it is submitted. With the default
  `job-run-duration-seconds` of `0`, a `BATCH` run is `SUCCESS` as soon as it starts; a positive
  duration keeps it `RUNNING` that long. A run whose duration would pass its
  `executionTimeoutMinutes` (720 when not given, as AWS reports it) is `CANCELLED` at the timeout.
  A `STREAMING` run stays `RUNNING` until it is cancelled.
* **Auto-start**: a run submitted to a `CREATED` or `STOPPED` application starts the application,
  as `autoStartConfiguration.enabled` defaults to `true`. With auto-start turned off, the run is
  refused with `ValidationException` until the application is started. A run on a terminated
  application is refused.
* **Idempotency**: `clientToken` is the idempotency token. Repeating a request with the same token
  returns the original run; reusing the token with a different request answers `ConflictException`
  (HTTP 409) "Request did not match the original".
* **Execution timeout**: `executionTimeoutMinutes` is 0 to 1,000,000, 0 meaning no timeout. From
  release `emr-7.11.0`, a `BATCH` run's timeout must be 1 to 10080 minutes (7 days), as the model and
  the 7.11.0 release notes say; a run outside that is refused with `ValidationException`.
* **CancelJobRun** moves a run that has not finished to `CANCELLED`. A finished run answers
  `ValidationException` "Job run is not in a cancellable state".
* **ListJobRuns** returns the newest runs first, filtered by `states`, `createdAtAfter`,
  `createdAtBefore` and `mode`, at most 50 per page. With several `states`, the runs are grouped by
  state in the order the filter names them. A state or mode that does not exist, or more than 8
  states, is refused with `ValidationException`. An unknown application has no runs. A `nextToken`
  resumes only the listing it came from, with the same application and filters.
* **Attempts**: a run starts as attempt 1, and in the `immediate` mode, where none fails, it stays its
  only one. In the `docker` mode a run whose driver fails is tried again on release 7.1.0 or later, as
  the user guide's "Job resiliency" gives it: a `BATCH` run until it has made `retryPolicy.maxAttempts`
  attempts (1 by default, no retry), and a `STREAMING` run until it has failed
  `maxFailedAttemptsPerHour` times within an hour (5 by default). Either outside 1 to 10, the user
  guide's "Accepted values", is refused with `ValidationException`, and either not an integer with
  `SerializationException`. Each new attempt is `SCHEDULED` again
  and runs its driver from the beginning; a cancelled run, one past its timeout, and one Floci's own
  shutdown stopped are not tried again. `GetJobRun` without `attempt` returns the latest attempt, with
  `attempt` an earlier one as it ended (`FAILED`, with its `stateDetails`), and an attempt the run never
  made answers `ResourceNotFoundException`; one below 1 or not an integer is `ValidationException`.
  `ListJobRunAttempts` lists every attempt, oldest first, each with its own creation and update times.
  No source says whether a run's timeout spans its attempts; Floci applies it to each.
* `createdBy` is the caller's ARN, as `GetCallerIdentity` reports it. `releaseLabel` and
  `networkConfiguration` come from the application.

A state machine drives applications and job runs through the `emr-serverless:` integrations, `.sync`
included; see [Step Functions: Amazon EMR Serverless tasks](step-functions.md#amazon-emr-serverless-tasks).

## Running Spark job runs

With `runner-mode` set to `docker` (`FLOCI_SERVICES_EMRSERVERLESS_RUNNER_MODE=docker`), a Spark job
run executes. Floci starts one container per run from the application's custom image, if it names one
(see **Image** below), or else from the release's image, the base image AWS publishes for customizing
EMR Serverless images (`public.ecr.aws/emr-serverless/spark/<release>`, and `spark4/<release>` for the
AWS runtime for Apache Spark releases, `emr-spark-8.0.0` on), and runs
`spark-submit` in local mode in it: the container holds the driver and its executors.

* **States**: a run is `SCHEDULED` while its image is pulled and its container starts (the first run
  of a release pulls its image, 3.5 to 4.3 GB unpacked; a pull still downloading after
  [`floci.docker.image-pull-timeout-seconds`](../configuration/docker.md), five minutes by default,
  is tried again, three attempts in all, so on a slow connection raise it or run `docker pull` on the
  image first, or the run is `FAILED` with "Could not pull image"), and `RUNNING` once the Spark driver runs, which sets `startedAt`. It
  is `SUCCESS` when the driver exits with code 0 and `FAILED` otherwise, with the exit code in
  `stateDetails`. `CancelJobRun`, or a `BATCH` run passing its `executionTimeoutMinutes`, makes it
  `CANCELLING` until the driver is stopped, then `CANCELLED`, as the job run states describe. A run
  Floci was running when it stopped is `FAILED` once it is read again. Floci's shutdown and a state
  reset stop every driver, one whose image is still being pulled included, and a driver a hard stop
  left running is removed the first time job runs are used after Floci restarts, unless
  `reconcile-containers-on-startup` is off.
* **What `spark-submit` gets**: the `entryPoint`, its `entryPointArguments` and the
  `sparkSubmitParameters`, after the `spark-defaults` properties of the application's
  `runtimeConfiguration`, or the run's own in `configurationOverrides.applicationConfiguration`,
  which replace the application's whole `spark-defaults` classification. From `emr-spark-8.1.0` the
  two are merged by property instead, the run's winning. `s3://` and `s3a://` paths reach Floci's S3 through S3A, on releases before 7.10.0
  too, where `s3://` would otherwise be EMRFS; the entry point and its files can be read from there.
  The Glue Data Catalog client is pointed at Floci's Glue.
* **Credentials**: the container gets a session for the run's execution role when IAM knows the role,
  otherwise the account's placeholder credentials, and `AWS_ENDPOINT_URL` pointing at Floci, so the
  job's own SDK clients reach Floci too.
* **HTTPS**: with [TLS](../configuration/tls.md) on, the container also gets Floci's CA bundle as a
  Java trust store, `/etc/floci-ca-bundle.p12`, which every JVM in it uses through
  `JAVA_TOOL_OPTIONS`, each saying so on its standard error (`Picked up JAVA_TOOL_OPTIONS: ...`); a
  trust store the job names itself, with `-Djavax.net.ssl.trustStore`, replaces it. So
  the job trusts Floci over HTTPS, and with
  [`FLOCI_DNS_SPOOF_AWS_ENDPOINTS`](../configuration/environment-variables.md#transparent-endpoints)
  on as well, which leads AWS's own endpoint names to Floci, SDK clients that ignore `AWS_ENDPOINT_URL`,
  the AWS SDK for Java 1.x's for one, reach Floci unchanged.
* **Output**: the driver's output goes to Floci's log, prefixed `emr-serverless:<jobRunId>`. With an S3
  log destination, the run's `s3MonitoringConfiguration` or else the application's, its standard output
  and error also go to Floci's S3 once the run ends, gzipped, as `stdout.gz` and `stderr.gz` under
  `<logUri>/applications/<applicationId>/jobs/<jobRunId>/SPARK_DRIVER/`, with `attempts/<n>/` before
  `SPARK_DRIVER/` for a `STREAMING` run, or a `BATCH` run whose `retryPolicy` allows more than one
  attempt, on release 7.1.0 or later, each attempt's logs under its own number. A cancelled run's logs hold what its driver wrote until it was
  stopped. Logs that cannot be written, to a bucket that does not exist say, leave the run as it
  ended.
* **CloudWatch Logs**: with CloudWatch logging enabled, the run's `cloudWatchLoggingConfiguration` or
  else the application's (`"enabled": false` at the run turns it off), the driver's standard output and
  error go line by line, while it runs, to the log streams `<prefix>/applications/<applicationId>/jobs/<jobRunId>/SPARK_DRIVER/stdout`
  and `.../stderr` in Floci's CloudWatch Logs, with `attempts/<n>/` before `SPARK_DRIVER` as for S3. The log
  group is `logGroupName`, `/aws/emr-serverless` by default, and `<prefix>` the `logStreamNamePrefix`, if
  any; both are created as needed, in the run's account and Region. A `logTypes` sends only the driver's
  types it names, and none of the driver's output when it names only `SPARK_EXECUTOR`.
* **Image**: an application's custom image runs in place of the release's: the `Driver` worker type's
  `imageConfiguration` in `workerTypeSpecifications`, or else the application's `imageConfiguration`,
  which applies to every worker type (local mode runs the executors in the driver's container, so an
  `Executor` image is not used). An `<account>.dkr.ecr.<region>.amazonaws.com/<repository>:<tag>` URI
  leads to Floci's [ECR](ecr.md), as a Lambda function's or an ECS task's image does, so the image is
  pushed to a repository there; the `repositoryUri` Floci's ECR hands out works too. The image is pulled
  again for every run, so a tag pushed again is what the next run gets; when the pull fails the image the
  Docker daemon already has runs, one built locally under that URI included, and without one the run is
  `FAILED` with "Job run could not start". `CreateApplication` refuses an image configuration without an
  `imageUri`, and `CreateApplication` and `UpdateApplication` an image whose ECR repository, AWS-shaped or
  a `repositoryUri` from Floci's ECR, is in another Region, both with `ValidationException` in Floci's own
  wording. An update that leaves `imageUri` out removes that image configuration. Without a custom image,
  `image-template` names another image than the release's, such as a mirror or a dated tag of it:
  `registry.internal/emr-serverless/spark/%s:20250425`.

## Limitations and Differences from AWS

* **Jobs do not execute by default**: in the `immediate` runner mode `StartJobRun` records a job run and moves it through its states, but nothing runs. In the `docker` mode a Spark run executes in local mode in one container, so worker counts and sizes, initial and maximum capacity are not applied; a Hive run is `FAILED` without running, in Floci's own wording, as is a driver's failure. Of the monitoring configuration, the S3 log destination gets the driver's `stdout.gz` and `stderr.gz` once a run ends (no event logs, rotated files or uploads while it runs), and CloudWatch Logs the driver's output while it runs (there are no executor logs, local mode having no executors, and `encryptionKeyArn` is ignored).
* **Unsourced errors**: AWS documents neither the error for a run submitted to an application whose auto-start is off nor the one for a run on a terminated application; Floci answers both with `ValidationException`. The same holds for a `BATCH` timeout outside 1 to 10080 minutes on `emr-7.11.0` or later (`ValidationException`) and for `GetJobRun` naming an attempt a run never made (`ResourceNotFoundException`), both in Floci's own wording. A run needs a `jobDriver`, as every `JobRun` carries one, and `jobDriver` is a union, so a run without a driver, or with one that sets both `sparkSubmit` and `hive` or neither, is refused with `ValidationException` too, in Floci's own wording.
* **Custom images**: Floci does not check an `imageUri` against the model's pattern, which would refuse the repository URIs Floci's own ECR hands out, nor that the image was built from its release's base image (AWS refuses one that was not from `emr-7.14.0` and `emr-spark-8.1.0` on), nor the image's size. An image that cannot be pulled fails the run, not the request that names it. Every run resolves its image again, as releases from 7.14 do and earlier ones with `applicationLevelDigestResolution` false: Floci keeps no digest from the application's start, and reports no `resolvedImageDigest`, on the application or on a job run.
* **StopApplication with running jobs**: AWS requires running jobs to finish or be cancelled before an application stops. Floci does not enforce it; with the default duration of `0` no `BATCH` run is ever running.
* **Not emulated**: the "EMR Serverless Job Run Retry" event, as Floci sends no EMR Serverless events, queuing (`QUEUED`), `totalResourceUtilization` and `billedResourceUtilization`, and `shutdownGracePeriodInSeconds` (accepted and ignored: a cancelled run's driver is stopped at once).
* **Instant Start/Stop**: Floci marks the application `STARTED` or `STOPPED` immediately without provisioning actual compute capacity in the background.
* **Data Plane**: Floci does not implement the data-plane API, such as interactive sessions.
