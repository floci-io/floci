# EMR on EKS

- **Protocol:** REST JSON
- **Endpoint:** `http://localhost:4566/`
- **Credential scope:** `emr-containers`

Floci implements the Amazon EMR on EKS (`emr-containers`) API: virtual clusters, job runs, job
templates, managed endpoints, security configurations and tags. It is the API Airflow's
`EmrContainerOperator`, the `aws emr-containers` CLI and the SDKs call. Floci keeps the records and
moves job runs through their states, and in the `docker` runner mode runs each job run's Spark job in a
container (see [Running job runs](#running-job-runs)).

## Configuration

| Key | Description | Default |
|-----|-------------|---------|
| `floci.services.emrcontainers.enabled` | Whether the EMR on EKS API is enabled | `true` |
| `floci.services.emrcontainers.job-run-duration-seconds` | In the `immediate` runner mode, how long a job run stays `RUNNING` before it is `COMPLETED`; `0` completes it as soon as it is submitted | `0` |
| `floci.services.emrcontainers.runner-mode` | `immediate` moves job runs through their states without running anything; `docker` runs each job run's Spark job in a container (see [Running job runs](#running-job-runs)), and each managed endpoint's Jupyter Enterprise Gateway (see [Running managed endpoints](#running-managed-endpoints)) | `immediate` |
| `floci.services.emrcontainers.docker-network` | Docker network for job run containers | the global default |
| `floci.services.emrcontainers.image-template` | Image for a `docker` job run that names no custom image, `%s` standing for its release's container image tag, `emr-7.2.0:20240610` for `emr-7.2.0-20240610` | `public.ecr.aws/emr-on-eks/spark/%s` |
| `floci.services.emrcontainers.gateway-image-template` | Image a `docker` managed endpoint's gateway and kernels run in, `%s` standing for its release's container image tag | `public.ecr.aws/emr-on-eks/notebook-spark/%s` |
| `floci.services.emrcontainers.reconcile-containers-on-startup` | Remove the drivers a previous run of this Floci left on the Docker daemon before the first job run starts; turn it off when two Floci instances share a daemon with the same port and no resource namespace | `true` |
| `floci.storage.services.emrcontainers.mode` | Storage mode for EMR on EKS resources | the global storage mode |
| `floci.storage.services.emrcontainers.flush-interval-ms` | Flush interval for persistent storage | `5000` |

## Supported Actions

<!-- floci:actions:start -->
| Action | Description |
| --- | --- |
| `CreateVirtualCluster` | Registers a virtual cluster on an EKS cluster's namespace, `RUNNING` at once (`POST /virtualclusters`) |
| `ListVirtualClusters` | Lists virtual clusters, filtered by container provider, creation time and state (`GET /virtualclusters`) |
| `DescribeVirtualCluster` | Gets a virtual cluster, with its scheduler status when it has concurrent job limits (`GET /virtualclusters/{virtualClusterId}`) |
| `UpdateVirtualCluster` | Sets or clears a virtual cluster's concurrent job limits (`PATCH /virtualclusters/{virtualClusterId}`) |
| `DeleteVirtualCluster` | Terminates a virtual cluster and clears its tags (`DELETE /virtualclusters/{virtualClusterId}`) |
| `StartJobRun` | Submits a job run, directly or from a job template with its parameters (`POST /virtualclusters/{virtualClusterId}/jobruns`) |
| `ListJobRuns` | Lists a virtual cluster's job runs, filtered by name, creation time and state (`GET /virtualclusters/{virtualClusterId}/jobruns`) |
| `DescribeJobRun` | Gets a job run (`GET /virtualclusters/{virtualClusterId}/jobruns/{jobRunId}`) |
| `CancelJobRun` | Cancels a job run that has not finished (`DELETE /virtualclusters/{virtualClusterId}/jobruns/{jobRunId}`) |
| `CreateManagedEndpoint` | Creates a managed endpoint on a virtual cluster, `ACTIVE` at once (`POST /virtualclusters/{virtualClusterId}/endpoints`) |
| `ListManagedEndpoints` | Lists a virtual cluster's managed endpoints, filtered by type, creation time and state (`GET /virtualclusters/{virtualClusterId}/endpoints`) |
| `DescribeManagedEndpoint` | Gets a managed endpoint (`GET /virtualclusters/{virtualClusterId}/endpoints/{endpointId}`) |
| `DeleteManagedEndpoint` | Terminates a managed endpoint (`DELETE /virtualclusters/{virtualClusterId}/endpoints/{endpointId}`) |
| `GetManagedEndpointSessionCredentials` | Issues a session token for an active managed endpoint (`POST /virtualclusters/{virtualClusterId}/endpoints/{endpointId}/credentials`) |
| `CreateJobTemplate` | Stores a job template (`POST /jobtemplates`) |
| `ListJobTemplates` | Lists job templates, filtered by creation time (`GET /jobtemplates`) |
| `DescribeJobTemplate` | Gets a job template (`GET /jobtemplates/{templateId}`) |
| `DeleteJobTemplate` | Deletes a job template (`DELETE /jobtemplates/{templateId}`) |
| `CreateSecurityConfiguration` | Stores a security configuration (`POST /securityconfigurations`) |
| `ListSecurityConfigurations` | Lists security configurations, filtered by creation time (`GET /securityconfigurations`) |
| `DescribeSecurityConfiguration` | Gets a security configuration (`GET /securityconfigurations/{securityConfigurationId}`) |
| `DeleteSecurityConfiguration` | Deletes a security configuration (`DELETE /securityconfigurations/{securityConfigurationId}`) |
| `ListTagsForResource` | Lists the tags on a virtual cluster, job run or managed endpoint (`GET /tags/{resourceArn}`) |
| `TagResource` | Adds or replaces tags on an active virtual cluster, job run or managed endpoint (`POST /tags/{resourceArn}`) |
| `UntagResource` | Removes tag keys from an active virtual cluster, job run or managed endpoint (`DELETE /tags/{resourceArn}`) |
<!-- floci:actions:end -->

## Behaviour

**Virtual clusters.** A virtual cluster is `RUNNING` as soon as it is created and `TERMINATED` once it is
deleted; deleting it again answers its id. Terminating it clears its tags, as the EMR on EKS Development
Guide says ("When a virtual cluster is terminated, tags are cleaned and no longer accessible"). Floci
does not refuse a container provider its EKS has no cluster for, nor a second virtual cluster on the same
namespace: AWS documents no error for either. A `securityConfigurationId` must name a security
configuration of the account. `schedulerConfiguration` is stored and returned, an empty object clears it
on `UpdateVirtualCluster`, and `DescribeVirtualCluster` adds a `schedulerStatus` with the current queued
and running job run counts. A virtual cluster created on an EKS
cluster whose `authenticationMode` admits access entries (`API` or `API_AND_CONFIG_MAP`) is EKS access entry
integrated, as the guide's cluster access management setup has it, and `ListVirtualClusters`'
`eksAccessEntryIntegrated` keeps the ones that are, or are not; one on a cluster Floci's EKS does not have is
not.

**Job runs.** A job run on a virtual cluster that does not exist answers `ResourceNotFoundException`
("Virtual cluster *id* doesn't exist."), and one on a virtual cluster that is not `RUNNING`
`ValidationException` ("Virtual cluster state *state* is not valid to create resource JobRun."), both as
AWS's "Common errors when running jobs" page gives them. When Floci's EKS has no cluster of the virtual
cluster's container provider id in the request's account and Region, the run is `FAILED` with
`stateDetails` "Cluster *id* does not exist." and `failureReason` `CLUSTER_UNAVAILABLE`, as that page
gives it. Otherwise, in the `immediate` runner mode, the run is `COMPLETED` at once, or `RUNNING` for
`job-run-duration-seconds` and then `COMPLETED`, and `CancelJobRun` moves a run that has not finished to
`CANCELLED`; in the `docker` one it runs (see [Running job runs](#running-job-runs)). A
`retryPolicyConfiguration` whose `maxAttempts` is not from 1 to 2,147,483,647 is refused with
`ValidationException` ("Retry policy configuration's parameter value of maxAttempts is invalid"), as the
development guide's "Using job retry policies" gives it. A `maxAttempts` of 1 disables retries, and then
neither the policy nor `retryPolicyExecution` is shown ("These fields will not be visible when retry policy
is disabled in the job"); with more, the run reports `retryPolicyExecution`, its `currentAttemptCount`
starting at 1. The release label is not checked against EMR on EKS's releases.

**Concurrent job limits.** `StartJobRun` applies the virtual cluster's limits, as the development guide's
"Concurrent job limits for virtual clusters" describes them. At most `maxConcurrentJobRuns` runs are
`RUNNING` at once, 1000 when it is not set. A run beyond that waits `PENDING` in the queue, and when a
running slot frees up, the run first in the queue starts. Once `maxInQueueJobRuns` runs wait, `StartJobRun`
refuses another with `ValidationException`. A run still queued 30 minutes after it was submitted is `FAILED`,
as the guide's "buffered jobs fail after 30 minutes if no slot frees up" has it. A higher limit, a cleared
one, or a cancelled run starts queued runs at once. The guide gives neither message, so the refusal's and
the timed-out run's `stateDetails` are Floci's wording. In the `immediate` runner mode a run takes its slot
only while it is `RUNNING`, so with the default `job-run-duration-seconds` of `0` no run waits. In the
`docker` one a run takes its slot as it is submitted, while its container starts, and keeps it until its
driver ends: a `SUBMITTED` or `CANCEL_PENDING` run whose container runs counts in `currentConcurrentJobRuns`, not
in `currentInQueueJobRuns`, and only the runs that wait for a slot count toward `maxInQueueJobRuns`.

**Job templates.** `StartJobRun` with a `jobTemplateId` takes `executionRoleArn`, `releaseLabel`,
`jobDriver` and `configurationOverrides` from the template where the request leaves them out, and the
template's `jobTags` under the request's `tags`, which together keep the 50-tag limit. Every `${name}` in the template is replaced by the
parameter's value in `jobTemplateParameters`, or else its `defaultValue`. A declared parameter with
neither, or a `NUMBER` parameter whose value is not a number, is refused with `ValidationException`; a
placeholder no parameter declares stays as written.

**Managed endpoints.** In the `immediate` runner mode, an endpoint is `ACTIVE` as soon as it is created and
`TERMINATED` once deleted, and has no `serverUrl`, as nothing runs it; in the `docker` one it runs a Jupyter
Enterprise Gateway (see [Running managed endpoints](#running-managed-endpoints)).
`GetManagedEndpointSessionCredentials` accepts the `TOKEN` credential type alone and a duration of up to
12 hours, 15 minutes by default, as the API reference gives them, and answers a random token.

**Tags.** The model's ARN pattern for the tagging operations names virtual clusters and what sits under
them, so a job template or security configuration ARN is refused; their tags are set on creation. As the
development guide says, only resources in an active state can be tagged or untagged: a terminated virtual
cluster or endpoint, or a finished job run, answers `ValidationException`. A resource holds at most 50
tags. An ARN of another Region than the request's names no resource, as for every other operation.

**IAM.** With IAM enforcement on, each operation is checked as its `emr-containers:` action on the resource its
path names, from AWS's Service Reference: `StartJobRun` and `CreateManagedEndpoint` also need `iam:PassRole` on
the request's `executionRoleArn`, and the create operations and `StartJobRun` need `emr-containers:TagResource`
for the request's `tags`. A role or tags a job template supplies are not part of the request, so they are not
checked that way.

**Idempotency.** A create request with a `clientToken` already used for that kind of resource answers the
resource it created, as EMR Serverless does; the model declares no error for a token reused with other
parameters.

**Validation.** Every request is checked against the constraints of botocore's `emr-containers` model
(required members, lengths, patterns and enums, vendored by `tools/aws/regen_emr_containers_shapes.py`),
and a violation answers `ValidationException`. The messages for the other refusals AWS does not document
are Floci's own.

Resources are scoped to the request's account and Region, and IDs follow AWS's lengths: 25 characters for
a virtual cluster, 13 for a managed endpoint and 19 for the others.

## Running job runs

With `runner-mode` set to `docker` (`FLOCI_SERVICES_EMRCONTAINERS_RUNNER_MODE=docker`), a job run executes.
Floci starts one container per run from its custom image, if it names one, or else from its release's base
image, the one AWS publishes for customizing EMR on EKS images on Amazon ECR Public ("For Amazon EMR 6.9.0
and higher, you can get the base images from the Amazon ECR Public Gallery"): the release label names the
image's repository and tag, so `emr-7.2.0-20240610` runs `public.ecr.aws/emr-on-eks/spark/emr-7.2.0:20240610`,
`emr-7.2.0-latest` its `latest`, and `emr-7.2.0-java11-latest` `emr-7.2.0-java11:latest`. The driver runs
in that container in Spark's client mode, its executors with it in Spark's local mode, where EMR on EKS runs
each in a pod of its own on the cluster. The first run of a release pulls its image, about 5 GB. The mode needs a
Docker daemon: explicitly mount `/var/run/docker.sock` when running Floci in a container or a Testcontainers
wrapper with `FLOCI_SERVICES_EMRCONTAINERS_RUNNER_MODE=docker` (see [Docker](../configuration/docker.md)).

* **What runs**: a `sparkSubmitJobDriver` runs `spark-submit` with its `sparkSubmitParameters`, its
  `entryPoint` and its `entryPointArguments`; a `sparkSqlJobDriver` runs `spark-sql` with its
  `sparkSqlParameters` and its `entryPoint` as the file it runs (`-f`). Both get the `spark-defaults`
  properties of the run's `configurationOverrides.applicationConfiguration` before their own options, which
  win; the run's other classifications are not applied. `s3://` and `s3a://` paths reach Floci's S3 through
  S3A, so the entry point and its files can be read from there, and the Glue Data Catalog client is pointed at
  Floci's Glue. The driver gets its execution role's credentials.
* **Image**: `spark.kubernetes.driver.container.image`, or else `spark.kubernetes.container.image`, from the
  `spark-defaults` or a `--conf` in the parameters, names the run's custom image, as the development guide's
  "Customizing Docker images" has it. An image in Floci's ECR is pulled from Floci's registry.
* **Driver environment and archives**: a `spark.kubernetes.driverEnv.<KEY>` property is an environment
  variable of the container, and the run's `spark.archives`, or its `--archives`, are unpacked in its working
  directory, a PySpark virtual environment among them.
* **States**: a run is `SUBMITTED` while its image is pulled and its container starts, and `RUNNING` once its
  Spark driver runs. It is `COMPLETED` when the driver exits with code 0, and `FAILED` otherwise, with
  `failureReason` `USER_ERROR` and `stateDetails` "Jobrun failed. Main Spark container terminated with errors.
  Please refer logs uploaded to S3/CloudWatch based on your monitoring configuration.", as AWS answers it. A run
  whose container cannot start is `FAILED` with `failureReason` `INTERNAL_ERROR` and `stateDetails` "JobRun
  submission failed due to an internal error.", the reason after it Floci's. `CancelJobRun` makes a run whose
  container runs `CANCEL_PENDING` until its driver is stopped, then `CANCELLED`; a run still queued is
  `CANCELLED` at once. A run Floci was running when it stopped is `FAILED` once it is read again, with
  `failureReason` `INTERNAL_ERROR` and `stateDetails` "Trouble monitoring your JobRun due to an internal
  error". Floci's shutdown and a state reset stop every driver, and a driver a hard stop left running is
  removed when Floci starts again, unless `reconcile-containers-on-startup` is off.
* **Logs**: the driver's output always goes to Floci's log, and to the run's `monitoringConfiguration`
  destinations as the development guide lays them out. With an `s3MonitoringConfiguration`, its `stdout.gz`
  and `stderr.gz` go to `<logUri>/<virtual-cluster-id>/jobs/<job-id>/containers/<spark-application-id>/spark-<job-id>-driver/`
  once the run ends, where EMR on EKS uploads them as it runs. With a `cloudWatchMonitoringConfiguration`,
  its standard output and standard error go, as the driver writes them, to the streams
  `<logStreamNamePrefix>/<virtual-cluster-id>/jobs/<job-id>/containers/<spark-application-id>/spark-<job-id>-driver/stdout`
  and `.../stderr` in its `logGroupName`, the group and streams created if they do not exist; without a prefix a
  stream's name starts at the virtual cluster's id. Each line goes as the driver wrote it, but for an empty one,
  which CloudWatch Logs refuses as an event. A run from a job template whose destination leaves out its
  `logGroupName` or `logUri`, which the template's model allows, is refused with `ValidationException`, as
  `StartJobRun` refuses a request that does. With retries enabled, the driver pod's name has a suffix of
  each attempt's own, `spark-<job-id>-driver-<random-suffix>`, as the guide gives it, so each attempt's logs
  go to a folder and streams of their own, under the run's one `<spark-application-id>`. That id has the form
  a Spark application on Kubernetes gets, `spark-` and 32 hexadecimal digits, but is Floci's own: the driver's
  local mode reports another. Floci runs no job submitter, so there are no submitter logs, and its executors run
  in the driver's container, so their output is the driver's. `persistentAppUI`, `containerLogRotationConfiguration`
  and `managedLogs` are kept but not applied.
* **Retries**: with a `maxAttempts` above 1, a driver that exits with an error is started again, "restarted
  automatically if it fails", until the run has made `maxAttempts` attempts, after "an exponential retry
  interval of (10 seconds, 20 seconds, 40 seconds ...) which is capped at 6 minutes". Between its attempts
  the run is `SUBMITTED` and keeps its running slot, and each new attempt raises `currentAttemptCount`. A run
  whose last attempt fails is `FAILED` as above. `CancelJobRun` between attempts cancels the run at once. A
  run Floci stopped between its attempts waits out the rest of its backoff once it is read again, and then
  makes its next attempt. A driver that cannot start, or that Floci stopped, is not retried. Floci sends no
  EMR on EKS events, so there is no `EMR Job Run New Driver Attempt` event for each new driver.
* **Not emulated yet**: a run waits for its container however long it takes, with no `job-start-timeout`.

## Running managed endpoints

With `runner-mode` set to `docker`, a managed endpoint runs the Jupyter Enterprise Gateway that "provides the
remote kernel lifecycle management capability that interactive clients need" (EMR on EKS Development Guide,
"Overview of interactive endpoints"), so a self-hosted Jupyter or JupyterLab can run kernels on it as the guide's
"Example: Deploy a JupyterLab notebook" does, with `JUPYTER_GATEWAY_URL` set to the endpoint's `serverUrl` and
`JUPYTER_GATEWAY_AUTH_TOKEN` to a token `GetManagedEndpointSessionCredentials` gave for it.

* **Image**: the gateway and its kernels run in one container of the release's `notebook-spark` image on Amazon
  ECR Public, the base image of interactive endpoints ("the base image URI in your Docker file must use
  notebook-spark in place of spark"), so `emr-7.5.0-latest` runs
  `public.ecr.aws/emr-on-eks/notebook-spark/emr-7.5.0:latest`. That image holds jupyter_server and ipykernel but
  no Enterprise Gateway, so a program of Floci's own serves the gateway's kernels API on jupyter_server. The first
  endpoint of a release pulls its image. The container gets the endpoint's execution role's credentials and the
  `spark-defaults` of its `configurationOverrides`, and reaches Floci's S3, as a job run's driver does.
* **States**: an endpoint is `CREATING` until its gateway answers ("Initially, your endpoint is in the CREATING
  state"), then `ACTIVE`, with its `serverUrl` and `stateDetails` "Endpoint created successfully. It took
  *m* Minutes *s* Seconds", the guide's wording. `DeleteManagedEndpoint` makes it `TERMINATING` until its gateway
  has stopped, then `TERMINATED`. A gateway that cannot start, exits or does not answer within 10 minutes ends its
  endpoint `TERMINATED_WITH_ERRORS`, with `failureReason` `INTERNAL_ERROR` and a `stateDetails` of Floci's own, as
  does Floci shutting down, and a restart that finds an endpoint still `CREATING` or `ACTIVE`, once it is read
  again: its kernels go with the container.
* **serverUrl**: Floci's own port, at `/_aws/emr-containers/endpoints/<endpoint-id>` under Floci's base URL, as
  every URL Floci gives is (`FLOCI_BASE_URL`, its host replaced by `FLOCI_HOSTNAME` where that is set; see
  [Docker Compose](../configuration/docker-compose.md)). A client that reaches Floci at another address, such as a
  port Testcontainers maps, joins that path to the address it uses. A request there goes to the
  endpoint's gateway, its kernels' WebSocket tunnelled through, when it carries a token given for that endpoint and
  not yet expired, as Jupyter Enterprise Gateway takes one: its last `token` parameter, else an `Authorization: token
  <token>` header, which is what a Jupyter client sends. Any other request gets that gateway's 401
  `{"reason": "Unauthorized", "message": ""}`, an expired token's with a message saying so, and one to an endpoint
  that is not `ACTIVE` gets 503.
* **Kernels**: the image's `python3` ("Python 3"); `spark-python-kubernetes` ("PySpark on Kubernetes", the name the
  guide's `jupyter-kernel-overrides` example gives it); and `spark-scala-kubernetes` ("Apache Spark with Scala", a
  name of Floci's beside it, as the guide gives the Scala kernel none), which runs the image's Apache Toree under
  `spark-submit`, as Toree's own kernel spec does. The Spark kernels' `spark` and `sc` are a Spark session in Spark's
  local mode, where EMR on EKS runs each kernel and its executors in pods of their own. The PySpark kernel runs on
  the image's Python, with `spark.pyspark.virtualenv.enabled` set to `false`: the image's `spark-defaults.conf` turns
  the virtualenv on, and `spark-submit` makes one on YARN and Kubernetes alone. Every kernel runs with the endpoint's
  execution role, as "All kernels and users that connect to the interactive endpoint utilize the role that you
  specified when you created the endpoint", whichever role its session token was asked for with. As Jupyter
  Enterprise Gateway does, a kernel gets the `KERNEL_` variables of its start request's `env`, which a Jupyter client
  sends from its own environment; `KERNEL_EXTRA_SPARK_OPTS`, "additional Spark configurations for the Spark kernels",
  goes to the Spark kernels' `spark-submit`. Listing the kernels (`GET /api/kernels`) answers with those started with
  the request's session token alone, as the guide's "Monitoring interactive endpoints" has ListKernels "list the
  kernels that have been previously started using the same session token", whether the kernels API or the sessions
  API (`POST /api/sessions`) started them, and listing the sessions (`GET /api/sessions`) answers with those holding
  such kernels; any of the endpoint's tokens reaches a kernel or a session by its id.
* **`%%configure`**: the PySpark kernel loads the image's own `%%configure` magic, with which "you can adjust the
  Spark configuration associated with a PySpark session" ("Modifying PySpark session parameters"). The magic turns
  its cell's settings into `KERNEL_EXTRA_SPARK_OPTS` and posts them, signed with an AES key of its kernel's own, to
  the gateway, which restarts the kernel, keeping its id, with them in place of those it started with. Once the
  restarted kernel answers, the gateway ends the cell with the replies the magic prepared; if it dies first, or does
  not answer within a minute, the cell ends with an error saying so. The magic itself refuses a reserved setting,
  such as `spark.kubernetes.namespace`.
* **`jeg-config`**: the two settings the guide's "Jupyter Enterprise Gateway (JEG) configuration options"
  allow-lists go to the gateway's kernel manager, named `RemoteMappingKernelManager` as Jupyter Enterprise Gateway's
  is: `RemoteMappingKernelManager.cull_idle_timeout`, "Timeout in seconds (integer), after which a kernel is
  considered idle and ready to be culled. Values of 0 or lower deactivate culling.", and
  `RemoteMappingKernelManager.cull_interval`, "The interval in seconds (integer) on which to check for idle kernels
  that exceed the cull timeout value." As jupyter_server's culler does by default, a kernel with a client connected
  is not culled. Other `jeg-config` properties go nowhere.
* **Not emulated yet**: `jupyter-kernel-overrides` images. A browser's CORS preflight (`OPTIONS`), which Jupyter
  Enterprise Gateway takes without a token, needs one here. `certificateArn` is kept, and the `serverUrl` is plain
  HTTP on Floci's port.
