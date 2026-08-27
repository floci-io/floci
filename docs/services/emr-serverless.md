# EMR Serverless

EMR Serverless is an AWS service that provides serverless data analytics environments. Floci provides an emulator for the management-plane API (REST JSON) which covers the basic lifecycle of an Application.

## Configuration

| Key | Description | Default |
|-----|-------------|---------|
| `floci.emrserverless.enabled` | Whether the EMR Serverless API is enabled | `true` |
| `floci.emrserverless.port` | The port the service is exposed on | `4566` |

## Endpoints

The emulator implements the standard AWS `emr-serverless` service endpoints:

* `POST /applications` (CreateApplication)
* `GET /applications` (ListApplications)
* `GET /applications/{applicationId}` (GetApplication)
* `PATCH /applications/{applicationId}` (UpdateApplication)
* `DELETE /applications/{applicationId}` (DeleteApplication)
* `POST /applications/{applicationId}/start` (StartApplication)
* `POST /applications/{applicationId}/stop` (StopApplication)

## Limitations and Differences from AWS

* **Jobs not implemented**: Floci supports the provisioning of the `Application` management plane (which satisfies tools like Terraform's `aws_emrserverless_application` resource). Executing actual Spark or Hive jobs against these applications via `StartJobRun` is not currently implemented.
* **Instant Start/Stop**: Floci marks the application `STARTED` or `STOPPED` immediately without provisioning actual compute capacity in the background.
* **Data Plane**: Floci does not implement the data-plane API or any execution environments.
* **Tagging APIs**: `TagResource`, `UntagResource`, and `ListTagsForResource` are currently unsupported.
