# EMR on EKS

- **Protocol:** REST JSON
- **Endpoint:** `http://localhost:4566/`
- **Credential scope:** `emr-containers`

Floci implements the Amazon EMR on EKS (`emr-containers`) API: virtual clusters, job runs, job
templates, managed endpoints, security configurations and tags. It is the API Airflow's
`EmrContainerOperator`, the `aws emr-containers` CLI and the SDKs call. Floci keeps the records and
moves job runs through their states; it does not run Spark on Kubernetes.

## Configuration

| Key | Description | Default |
|-----|-------------|---------|
| `floci.services.emrcontainers.enabled` | Whether the EMR on EKS API is enabled | `true` |
| `floci.services.emrcontainers.job-run-duration-seconds` | How long a job run stays `RUNNING` before it is `COMPLETED`; `0` completes it as soon as it is submitted | `0` |
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
and running job run counts; the limits are not enforced on `StartJobRun`. A virtual cluster created on an EKS
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
gives it. Otherwise the run is `COMPLETED` at once, or `RUNNING` for
`job-run-duration-seconds` and then `COMPLETED`. `CancelJobRun` moves a run that has not finished to
`CANCELLED`. A run with a `retryPolicyConfiguration` reports `retryPolicyExecution` with one attempt.
The release label is not checked against EMR on EKS's releases.

**Job templates.** `StartJobRun` with a `jobTemplateId` takes `executionRoleArn`, `releaseLabel`,
`jobDriver` and `configurationOverrides` from the template where the request leaves them out, and the
template's `jobTags` under the request's `tags`, which together keep the 50-tag limit. Every `${name}` in the template is replaced by the
parameter's value in `jobTemplateParameters`, or else its `defaultValue`. A declared parameter with
neither, or a `NUMBER` parameter whose value is not a number, is refused with `ValidationException`; a
placeholder no parameter declares stays as written.

**Managed endpoints.** An endpoint is `ACTIVE` as soon as it is created and `TERMINATED` once deleted.
Floci runs no Jupyter Enterprise Gateway, so an endpoint has no `serverUrl`.
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
