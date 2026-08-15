# Amazon SageMaker

**Protocol:** JSON 1.1 (`X-Amz-Target: SageMaker.<Operation>`)
**Endpoint:** `http://localhost:4566/` (SigV4 service `sagemaker`)

Only the algorithm operations `aws_sagemaker_algorithm` needs for a full
create, read, tag, and delete round trip are implemented. Every other
SageMaker action returns a clean `UnknownOperationException` rather than a
stub success.

## Supported Actions

| Action | Description |
|---|---|
| `CreateAlgorithm` | Create an algorithm; `Completed` immediately, no Pending/InProgress transition |
| `DescribeAlgorithm` | Describe an algorithm by name or ARN; echoes the training/inference/validation specifications verbatim |
| `DeleteAlgorithm` | Delete an algorithm |
| `ListAlgorithms` | List algorithms, with optional `NameContains` filtering |
| `AddTags` | Tag a SageMaker resource by ARN |
| `ListTags` | List tags for a SageMaker resource ARN |
| `DeleteTags` | Remove tags from a SageMaker resource ARN |

Algorithms are `Completed` as soon as a create returns, so `terraform-provider-aws`'s
`waitAlgorithmCreated` completes on its first poll rather than modeling a
Pending/InProgress transition. A missing algorithm on `DescribeAlgorithm` or
`DeleteAlgorithm` returns `ValidationException` with a message containing
"does not exist" rather than a typed not-found error: SageMaker's own API
model declares no error shapes for either operation, so the AWS SDK never
generates a typed exception for them, and the real provider source
(`internal/service/sagemaker/algorithm.go`) only recognizes a missing
algorithm through that specific `ValidationException` message match. Any
other error code is invisible to the provider's delete waiter and hangs the
apply.

## Configuration

| Variable | Default | Description |
|---|---|---|
| `FLOCI_SERVICES_SAGEMAKER_ENABLED` | `true` | Enable or disable the service |

## Examples

```bash
export AWS_ENDPOINT_URL=http://localhost:4566

aws sagemaker create-algorithm \
  --algorithm-name my-algorithm \
  --training-specification '{
    "TrainingImage": "123456789012.dkr.ecr.us-east-1.amazonaws.com/algo:latest",
    "SupportedTrainingInstanceTypes": ["ml.m5.large"],
    "TrainingChannels": [{
      "Name": "train",
      "SupportedContentTypes": ["text/csv"],
      "SupportedInputModes": ["File"]
    }]
  }'

aws sagemaker describe-algorithm --algorithm-name my-algorithm

aws sagemaker delete-algorithm --algorithm-name my-algorithm
```
