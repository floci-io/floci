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
| `DescribeChangeSet` | Get change set details (no computed diff/preview) |
| `ExecuteChangeSet` | Apply a change set |
| `DeleteChangeSet` | Delete a change set |
| `ListChangeSets` | List change sets for a stack |
| `DescribeStackEvents` | Get stack creation/update event history |
| `DescribeStackResources` | Get all resources in a stack |
| `ListStackResources` | List resource summaries |
| `GetTemplate` | Retrieve the template body |
| `ValidateTemplate` | Accepted; returns success without validating (stub) |
| `ListStacks` | List stacks by status |
| `ListExports` | - |
| `SetStackPolicy` | Accepted; no-op (stub — stack policies are not enforced) |
| `GetStackPolicy` | Accepted; returns an empty policy (stub) |
| `DescribeStackResource` | Get a specific stack resource |
| `CreateStackSet` | Create a stack set from a template |
| `DescribeStackSet` | Get stack set details |
| `ListStackSets` | List stack sets |
| `UpdateStackSet` | Update the stack set and re-apply to existing instances |
| `DeleteStackSet` | Delete an empty stack set |
| `CreateStackInstances` | Provision instances into target accounts/regions |
| `ListStackInstances` | List instances (optionally filtered by account/region) |
| `DescribeStackInstance` | - |
| `DeleteStackInstances` | Remove instances and their resources |
| `ListStackSetOperations` | List operations performed on a stack set |
| `DescribeStackSetOperation` | - |
<!-- floci:actions:end -->

## Supported Resource Types

Resource types provisioned during `CreateStack` / `UpdateStack` / `DeleteStack`. Each delegates to
the backing service and sets a real physical ID plus the `Ref` / `Fn::GetAtt` attributes used by
cross-resource references.

| Service | Resource types |
|---|---|
| S3 | `Bucket`, `BucketPolicy` (accepted; policy not enforced) |
| SQS | `Queue`, `QueuePolicy` (accepted; policy not enforced) |
| SNS | `Topic`, `Subscription` |
| DynamoDB | `Table`, `GlobalTable` |
| Lambda | `Function` (Zip via S3/inline `ZipFile`, and Image), `LayerVersion`, `EventSourceMapping` (SQS, Kinesis, DynamoDB Streams) |
| IAM | `Role`, `User`, `AccessKey`, `Policy`, `ManagedPolicy`, `InstanceProfile` |
| SSM | `Parameter` |
| KMS | `Key`, `Alias` |
| Secrets Manager | `Secret` |
| ECR | `Repository` |
| ECS | `Cluster`, `TaskDefinition`, `Service` |
| EKS | `Cluster`, `Nodegroup` |
| RDS | `DBInstance`, `DBCluster`, `DBSubnetGroup`, `DBParameterGroup`, `DBClusterParameterGroup` (DBInstance/DBCluster start real containers) |
| EC2 | `VPC`, `Subnet`, `SecurityGroup`, `InternetGateway`, `RouteTable`, `SubnetRouteTableAssociation`, `Route`, `NatGateway`, `EIP`, `Instance` |
| Elastic Load Balancing v2 | `LoadBalancer`, `TargetGroup`, `Listener`, `ListenerRule` |
| Auto Scaling | `LaunchConfiguration`, `AutoScalingGroup` |
| Route 53 | `HostedZone`, `RecordSet` |
| API Gateway (v1) | `RestApi`, `Resource`, `Authorizer`, `Method`, `Deployment`, `Stage` |
| API Gateway v2 | `Api`, `Route`, `Integration`, `Stage`, `Deployment` |
| Step Functions | `StateMachine` |
| Batch | `ComputeEnvironment`, `JobQueue`, `JobDefinition` |
| Cognito | `UserPool`, `UserPoolClient` |
| EventBridge | `Events::Rule` |
| Pipes | `Pipe` |
| Kinesis | `Stream` |
| Kinesis Data Firehose | `DeliveryStream` |
| CloudWatch | `Alarm` |
| CloudWatch Logs | `LogGroup` |
| CloudFormation | `Stack` (nested stacks), `CustomResource` and `Custom::*` (Lambda-backed) |
| CDK | `CDK::Metadata` (accepted; no-op) |

All other resource types are accepted without error and assigned a synthetic physical ID (with an
`arn:aws:stub:::<logicalId>` ARN attribute), so templates with unsupported types still reach
`CREATE_COMPLETE` rather than failing.

## Lambda Stack Updates

`AWS::Lambda::Function` resources are reconciled during `UpdateStack` in the same shape as CloudFormation/CDK deployments:

- A no-op redeploy keeps the existing physical function name and does not call Lambda update APIs, so warm containers can be reused.
- Code and mutable configuration changes update the existing function in place.
- Replacement-only changes such as `FunctionName` or `PackageType` changes create a replacement function and remove the old one.
- S3-backed code stays linked through `S3Bucket` / `S3Key`, so Lambda's reactive S3 sync continues to work for functions created by CloudFormation or CDK.

## Account-Aware Provisioning

Resources provisioned by `CreateStack` / `UpdateStack` land in the **caller's account** namespace
(determined from the request's access key — see [Multi-Account Isolation](../configuration/multi-account.md)).
Deleting the stack removes them from that same account.

## StackSets

StackSets deploy a single template into many target accounts and regions:

```bash
export AWS_ENDPOINT_URL=http://localhost:4566

# 1. Create the stack set (in the administration account)
aws cloudformation create-stack-set \
  --stack-set-name my-set \
  --template-body file://template.yml \
  --endpoint-url $AWS_ENDPOINT_URL

# 2. Create instances in two target accounts
aws cloudformation create-stack-instances \
  --stack-set-name my-set \
  --accounts 222222222222 333333333333 \
  --regions us-east-1 \
  --endpoint-url $AWS_ENDPOINT_URL

# 3. The resources materialize in each target account's namespace
aws cloudformation list-stack-instances \
  --stack-set-name my-set \
  --endpoint-url $AWS_ENDPOINT_URL
```

`CreateStackInstances` drives the single-stack engine once per `(account, region)` pair, provisioning
each instance's resources into that target account's namespace — so a queue named `orders` deployed
into accounts `222222222222` and `333333333333` exists independently in each. The stack set, its
instances, and its operation history are recorded in the administration (caller) account.

`DeleteStackInstances` removes instances and their resources, unless `RetainStacks=true`, which
detaches the instances from the stack set but leaves their underlying stacks and resources in place.
A stack set must be empty before `DeleteStackSet`.

A `CreateStackInstances` / `UpdateStackSet` operation reports `FAILED` if any of its instances fails
to deploy (the instance is marked `INOPERABLE`), so polling `DescribeStackSetOperation` reflects real
provisioning outcomes rather than always returning `SUCCEEDED`.

## Configuration

| Variable | Default | Description |
|---|---|---|
| `FLOCI_SERVICES_CLOUDFORMATION_ENABLED` | `true` | Enable or disable the service |

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
