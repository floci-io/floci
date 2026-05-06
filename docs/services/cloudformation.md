# CloudFormation

**Protocol:** Query (XML) — `POST http://localhost:4566/` with `Action=` parameter
**Endpoint:** `POST http://localhost:4566/`

## Supported Actions

<!-- floci:actions:start -->
| Action | Description |
| --- | --- |
| `DescribeStacks` | Get stack status and outputs |
| `CreateStack` | Deploy a CloudFormation template |
| `UpdateStack` | Update an existing stack |
| `DeleteStack` | Delete a stack and its resources |
| `CreateChangeSet` | Create a change set |
| `DescribeChangeSet` | Get change set details |
| `ExecuteChangeSet` | Apply a change set |
| `DeleteChangeSet` | Delete a change set |
| `ListChangeSets` | List change sets for a stack |
| `DescribeStackEvents` | Get stack creation/update event history |
| `DescribeStackResources` | Get all resources in a stack |
| `ListStackResources` | List resource summaries |
| `GetTemplate` | Retrieve the template body |
| `ValidateTemplate` | Validate a template without deploying |
| `ListStacks` | List stacks by status |
| `ListExports` |  |
| `SetStackPolicy` | Set a stack policy |
| `GetStackPolicy` | Retrieve the current stack policy |
| `DescribeStackResource` | Get a specific stack resource |
<!-- floci:actions:end -->

## Supported Resource Types

Resource types provisioned during `CreateStack` / `DeleteStack`:

| Resource Type | Notes |
|---|---|
| `AWS::S3::Bucket` | |
| `AWS::S3::BucketPolicy` | Accepted; policy not enforced |
| `AWS::SQS::Queue` | |
| `AWS::SQS::QueuePolicy` | Accepted; policy not enforced |
| `AWS::SNS::Topic` | |
| `AWS::DynamoDB::Table` | |
| `AWS::DynamoDB::GlobalTable` | |
| `AWS::Lambda::Function` | Zip (S3 or inline `ZipFile`) and Image package types |
| `AWS::Lambda::EventSourceMapping` | SQS, Kinesis, and DynamoDB Streams sources |
| `AWS::IAM::Role` | |
| `AWS::IAM::User` | |
| `AWS::IAM::AccessKey` | |
| `AWS::IAM::Policy` | |
| `AWS::IAM::ManagedPolicy` | |
| `AWS::IAM::InstanceProfile` | |
| `AWS::SSM::Parameter` | |
| `AWS::KMS::Key` | |
| `AWS::KMS::Alias` | |
| `AWS::SecretsManager::Secret` | |
| `AWS::ECR::Repository` | |
| `AWS::Events::Rule` | |
| `AWS::ApiGateway::RestApi` | |
| `AWS::ApiGateway::Resource` | |
| `AWS::ApiGateway::Method` | |
| `AWS::ApiGateway::Deployment` | |
| `AWS::ApiGateway::Stage` | |
| `AWS::ApiGatewayV2::Api` | |
| `AWS::ApiGatewayV2::Route` | |
| `AWS::ApiGatewayV2::Integration` | |
| `AWS::ApiGatewayV2::Stage` | |
| `AWS::ApiGatewayV2::Deployment` | |
| `AWS::Pipes::Pipe` | |
| `AWS::CloudFormation::Stack` | Nested stacks (stubbed — returns synthetic stack ID) |
| `AWS::CDK::Metadata` | Accepted; no-op |
| `AWS::Route53::HostedZone` | Stubbed |
| `AWS::Route53::RecordSet` | Stubbed |

All other resource types are accepted without error and assigned a synthetic physical ID, so templates with unsupported types still deploy rather than fail.

## Examples

```bash
export AWS_ENDPOINT_URL=http://localhost:4566

# Validate a template
aws cloudformation validate-template \
  --template-body file://template.yml \
  --endpoint-url $AWS_ENDPOINT_URL

# Deploy a stack
aws cloudformation create-stack \
  --stack-name my-stack \
  --template-body file://template.yml \
  --parameters ParameterKey=Env,ParameterValue=dev \
  --endpoint-url $AWS_ENDPOINT_URL

# Check status
aws cloudformation describe-stacks \
  --stack-name my-stack \
  --endpoint-url $AWS_ENDPOINT_URL

# Watch events
aws cloudformation describe-stack-events \
  --stack-name my-stack \
  --endpoint-url $AWS_ENDPOINT_URL

# Update
aws cloudformation update-stack \
  --stack-name my-stack \
  --template-body file://template.yml \
  --endpoint-url $AWS_ENDPOINT_URL

# Delete
aws cloudformation delete-stack \
  --stack-name my-stack \
  --endpoint-url $AWS_ENDPOINT_URL

# Create a change set
aws cloudformation create-change-set \
  --stack-name my-stack \
  --change-set-name my-change-set \
  --template-body file://template.yml \
  --endpoint-url $AWS_ENDPOINT_URL

# List change sets
aws cloudformation list-change-sets \
  --stack-name my-stack \
  --endpoint-url $AWS_ENDPOINT_URL

# Describe a change set
aws cloudformation describe-change-set \
  --stack-name my-stack \
  --change-set-name my-change-set \
  --endpoint-url $AWS_ENDPOINT_URL

# Delete a change set
aws cloudformation delete-change-set \
  --stack-name my-stack \
  --change-set-name my-change-set \
  --endpoint-url $AWS_ENDPOINT_URL
```

## Lambda + SQS Event Source Mapping

Deploy a Lambda function wired to an SQS queue as a single stack:

```yaml
# template.yml
Resources:
  MyQueue:
    Type: AWS::SQS::Queue
    Properties:
      QueueName: my-queue

  MyFunction:
    Type: AWS::Lambda::Function
    Properties:
      FunctionName: my-function
      Runtime: nodejs22.x
      Handler: index.handler
      Role: arn:aws:iam::000000000000:role/lambda-role
      Code:
        ZipFile: |
          exports.handler = async (event) => {
            console.log(JSON.stringify(event));
          };

  MyESM:
    Type: AWS::Lambda::EventSourceMapping
    Properties:
      FunctionName: !Ref MyFunction
      EventSourceArn: !GetAtt MyQueue.Arn
      Enabled: true
      BatchSize: 10
```

```bash
aws cloudformation create-stack \
  --stack-name my-lambda-sqs-stack \
  --template-body file://template.yml \
  --endpoint-url $AWS_ENDPOINT_URL
```

!!! note "Dependency ordering"
    Use `!Ref MyFunction` (not a plain string) for `FunctionName` so CloudFormation
    provisions the function before the event source mapping.
