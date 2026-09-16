# Amazon SageMaker

Floci emulates SageMaker control-plane APIs and runs real Docker containers for local training jobs and hosted endpoints. Containers use the SageMaker `/opt/ml` contract rather than mocks.

## Supported operations

| Area | Operations |
| --- | --- |
| Models | `CreateModel`, `DescribeModel`, `DeleteModel`, `ListModels` |
| Endpoint configs | `CreateEndpointConfig`, `DescribeEndpointConfig`, `DeleteEndpointConfig`, `ListEndpointConfigs` |
| Endpoints | `CreateEndpoint`, `DescribeEndpoint`, `UpdateEndpoint`, `DeleteEndpoint`, `ListEndpoints` |
| Training | `CreateTrainingJob`, `DescribeTrainingJob`, `ListTrainingJobs`, `StopTrainingJob` |
| Tags | `AddTags`, `ListTags`, `DeleteTags` |
| Runtime | `POST /endpoints/{EndpointName}/invocations` |

## Supported Actions

<!-- floci:actions:start -->
| Action | Description |
| --- | --- |
| `CreateModel` | Registers a model from `PrimaryContainer` or `Containers`; rejects an empty `Containers` array rather than dereferencing it. |
| `DescribeModel` | Returns a model by name, scoped to the calling region. |
| `DeleteModel` | Deletes a model by name, scoped to the calling region. |
| `ListModels` | Lists models for the calling region; supports `NameContains`, `MaxResults` (1-100), and `NextToken` pagination. |
| `CreateEndpointConfig` | Creates an endpoint configuration; every `ProductionVariants[].ModelName` must already exist. |
| `DescribeEndpointConfig` | Returns an endpoint configuration by name, scoped to the calling region. |
| `DeleteEndpointConfig` | Deletes an endpoint configuration by name, scoped to the calling region. |
| `ListEndpointConfigs` | Lists endpoint configurations for the calling region; supports `MaxResults` and `NextToken`. |
| `CreateEndpoint` | Starts hosting the configured model image asynchronously; the endpoint is `Creating` until the container passes its `/ping` check. |
| `DescribeEndpoint` | Returns an endpoint's status, scoped to the calling region. |
| `DeleteEndpoint` | Stops and removes the endpoint's container; a start still in flight for this endpoint discards its result instead of resurrecting the deleted record. |
| `ListEndpoints` | Lists endpoints for the calling region; supports `MaxResults` and `NextToken`. |
| `UpdateEndpoint` | Swaps an endpoint to a new endpoint configuration, restarting its container; a superseded in-flight start discards its result. |
| `CreateTrainingJob` | Runs `AlgorithmSpecification.TrainingImage` asynchronously against the `/opt/ml` contract and uploads the resulting model artifacts to S3. |
| `DescribeTrainingJob` | Returns a training job's status, scoped to the calling region. |
| `ListTrainingJobs` | Lists training jobs for the calling region; supports `NameContains`, `StatusEquals`, `MaxResults`, and `NextToken`. |
| `StopTrainingJob` | Stops a training job that is `InProgress` or `Stopping`; a no-op against a job already in a terminal state. |
| `AddTags` | Adds tags to a model, endpoint config, endpoint, or training job identified by ARN. |
| `ListTags` | Lists tags for a model, endpoint config, endpoint, or training job identified by ARN. |
| `DeleteTags` | Removes tags by key from a model, endpoint config, endpoint, or training job identified by ARN. |
<!-- floci:actions:end -->

## Training contract

`CreateTrainingJob` starts `AlgorithmSpecification.TrainingImage` with command `train` unless `ContainerEntrypoint`/`ContainerArguments` are supplied. Floci writes SageMaker config files under `/opt/ml/input/config`, downloads channel data from S3 into `/opt/ml/input/data/<channel>`, waits for container exit, and uploads `/opt/ml/model` as `model.tar.gz` under `OutputDataConfig.S3OutputPath/<TrainingJobName>/output/`.

## Endpoint hosting

`CreateEndpoint` starts the model image as a long-lived Docker container with command `serve`, port `8080`, `/ping` health checks, and `/invocations` runtime proxying. `ModelDataUrl` artifacts are downloaded from S3 and placed in `/opt/ml/model`.

## Examples

```python
import boto3
sm = boto3.client("sagemaker", endpoint_url="http://localhost:4566", region_name="us-east-1")
sm.create_model(ModelName="m", PrimaryContainer={"Image":"my-image"}, ExecutionRoleArn="arn:aws:iam::000000000000:role/r")
sm.create_endpoint_config(EndpointConfigName="cfg", ProductionVariants=[{"VariantName":"AllTraffic","ModelName":"m","InitialInstanceCount":1,"InstanceType":"ml.t2.medium"}])
sm.create_endpoint(EndpointName="ep", EndpointConfigName="cfg")
```

```bash
aws --endpoint-url=http://localhost:4566 sagemaker list-models
```
