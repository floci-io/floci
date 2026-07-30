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
