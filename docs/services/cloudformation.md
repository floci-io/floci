# CloudFormation

**Protocol:** Query (XML) — `POST http://localhost:4566/` with `Action=` parameter
**Endpoint:** `POST http://localhost:4566/`

## Supported Actions

<!-- floci:actions:start -->
| Action | Description |
| --- | --- |
| `DescribeStacks` | Get stack status, parameters, and outputs |
| `CreateStack` | Deploy a CloudFormation template |
| `UpdateStack` | Update an existing stack |
| `DeleteStack` | Delete a stack and its resources |
| `UpdateTerminationProtection` | - |
| `CreateChangeSet` | Create a change set |
| `DescribeChangeSet` | Get change set details with a computed Add/Modify/Remove resource diff |
| `ExecuteChangeSet` | Apply a change set |
| `DeleteChangeSet` | Delete a change set |
| `ListChangeSets` | List change sets for a stack |
| `DescribeStackEvents` | Get stack creation/update event history |
| `DescribeStackResources` | Get all resources in a stack |
| `ListStackResources` | List resource summaries |
| `GetTemplate` | Retrieve the template body |
| `GetTemplateSummary` | Summarize a template's Parameters, Resources and Transform sections, by StackName, TemplateBody or TemplateURL |
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

> Adding a type? See [Adding a CloudFormation Resource Type](https://github.com/floci-io/floci/blob/main/CONTRIBUTING.md#adding-a-cloudformation-resource-type).
> Types live in per-service provisioners under `services/cloudformation/provisioners/`. This table
> is generated from the provisioner inventory by `make docs-sync`; edit that, not the table.

<!-- floci:cfn-types:start -->
| Service | Resource types |
|---|---|
| S3 | `Bucket`, `BucketPolicy` (accepted; policy not enforced) |
| SQS | `Queue`, `QueuePolicy` (accepted; policy not enforced) |
| SNS | `Topic`, `Subscription` |
| DynamoDB | `Table`, `GlobalTable` |
| Lambda | `Function` (Zip via S3/inline `ZipFile`, and Image), `LayerVersion`, `EventSourceMapping` (SQS, Kinesis, DynamoDB Streams), `Version`, `Alias` (also what SAM's `AutoPublishAlias` expands into), `Permission`, `MicrovmImage`, `NetworkConnector`. Inline `ZipFile` packages include the `cfn-response` (Node.js) / `cfnresponse` (Python) module AWS injects for that code path, so Solutions-style custom-resource handlers work. |
| IAM | `Role`, `User`, `AccessKey`, `Policy`, `ManagedPolicy`, `InstanceProfile` |
| Organizations | `Organization`, `OrganizationalUnit`, `Account`, `Policy`, `ResourcePolicy` |
| SSM | `Parameter` |
| KMS | `Key`, `Alias` |
| Secrets Manager | `Secret`, `SecretTargetAttachment` |
| ECR | `Repository` |
| ECS | `Cluster`, `TaskDefinition`, `Service`, `CapacityProvider`, `ClusterCapacityProviderAssociations` |
| EKS | `Cluster`, `Nodegroup` |
| RDS | `DBInstance` (starts a real container), `DBCluster` (starts a real container), `DBSubnetGroup`, `DBParameterGroup`, `DBClusterParameterGroup`, `DBProxy`, `DBProxyTargetGroup` |
| EC2 | `VPC`, `Subnet`, `SecurityGroup` (inline `SecurityGroupIngress`/`SecurityGroupEgress` supported), `SecurityGroupIngress`, `SecurityGroupEgress`, `InternetGateway`, `RouteTable`, `SubnetRouteTableAssociation`, `Route`, `NatGateway`, `EIP`, `Instance`, `LaunchTemplate`, `VPCGatewayAttachment`, `VPCEndpoint`, `NetworkAcl`, `NetworkAclEntry`, `SubnetNetworkAclAssociation`, `FlowLog` |
| Elastic Load Balancing v2 | `LoadBalancer`, `TargetGroup`, `Listener`, `ListenerRule` |
| Auto Scaling | `LaunchConfiguration`, `AutoScalingGroup`, `LifecycleHook` |
| Route 53 | `HostedZone`, `RecordSet` |
| API Gateway (v1) | `RestApi`, `Resource`, `Authorizer`, `Method`, `Deployment`, `Stage`, `Account` |
| API Gateway v2 | `Api`, `Authorizer`, `Route`, `Integration`, `Stage`, `Deployment` |
| Step Functions | `StateMachine` |
| CodePipeline | `Pipeline`, `CustomActionType`, `Webhook` |
| CodeBuild | `Project` |
| Batch | `ComputeEnvironment`, `JobQueue`, `JobDefinition` |
| Cognito | `UserPool`, `UserPoolClient`, `UserPoolDomain` |
| ACM | `Certificate` |
| EventBridge | `Rule`, `EventBus`, `EventBusPolicy` |
| EventBridge Scheduler | `ScheduleGroup` |
| Pipes | `Pipe` |
| Kinesis | `Stream` |
| Kinesis Data Firehose | `DeliveryStream` |
| IoT Core | `DomainConfiguration` (`ServerCertificates` resolves to a JSON string) |
| CloudFront | `Distribution` |
| CloudWatch | `Alarm` |
| CloudWatch Logs | `LogGroup` |
| WAFv2 | `WebACL` |
| Config | `ConfigRule` |
| CloudFormation | `CustomResource`, `Custom::DynamoDBReplica` (applied natively against DynamoDB, not via a provider Lambda), `Stack` (nested stacks), `Custom::*` (Lambda-backed) |
| CDK | `CDK::Metadata` (accepted; no-op) |
<!-- floci:cfn-types:end -->

All other resource types are accepted without error and assigned a synthetic physical ID (with an
`arn:aws:stub:::<logicalId>` ARN attribute), so templates with unsupported types still reach
`CREATE_COMPLETE` rather than failing. Nothing is created for such a resource, so each one is
logged at `WARN` and carries a resource status reason saying so, which `DescribeStackEvents`
returns:

```
Resource type AWS::Fake::Thing is not supported by Floci. It was stubbed and nothing was created for it.
```

Set `floci.services.cloudformation.allow-stub-unsupported-resource-types` to `false`
(`FLOCI_SERVICES_CLOUDFORMATION_ALLOW_STUB_UNSUPPORTED_RESOURCE_TYPES=false`) to fail such a
resource instead: it reaches `CREATE_FAILED` and the stack rolls back. Use it in a pipeline that
must not pass over a resource it never got.

## EventBridge Event Buses

`AWS::Events::EventBus` creates a real custom EventBridge bus. `Name` is required, `Ref` returns
the bus name, and `Fn::GetAtt` supports `Arn` and `Name`. `Description` and `Tags` are applied when
the bus is created. Rules that reference the bus are removed before stack deletion.

The current implementation is limited to custom buses with the `Name`, `Description`, `Tags`, and
`Policy` properties. `EventSourceName`, `KmsKeyIdentifier`, `DeadLetterConfig`, and `LogConfig` are
rejected with `ValidationError` instead of being silently ignored. AWS models a `Name` change as
resource replacement; Floci currently rejects that update until generic replacement handling is
available. `Policy` is applied when the bus is created. Changing `Description`, `Tags`, or `Policy`
during `UpdateStack` is rejected until transactional resource rollback is available; this prevents a
failed stack update from leaving the live bus in the rejected configuration.

## Secrets Manager Target Attachments

`AWS::SecretsManager::SecretTargetAttachment` adds the target's database connection fields to the
referenced secret while preserving credentials and custom fields. `Ref` and `Fn::GetAtt Id` return
the complete secret ARN, only one attachment can own a secret, and deleting the attachment removes
only its managed connection fields.

Supported target types are:

- `AWS::RDS::DBInstance`
- `AWS::RDS::DBCluster`
- `AWS::DocDB::DBInstance`
- `AWS::DocDB::DBCluster`

Redshift clusters, Redshift Serverless namespaces, and DocumentDB Elastic clusters are not supported
because their backing services are not implemented. A `SecretId` change is applied in place rather
than reproducing CloudFormation's replacement event sequence; failed changes restore affected secret
data and attachment ownership.

## Organizations

`AWS::Organizations::Organization` uses the stack's calling account as the management account, so
the rest of the stack's Organizations resources are provisioned into that account's organization.
`Fn::GetAtt Org.RootId` is the usual way to root the OU tree in the same template. `Organization` is
the one type in this section where bare `Ref` does not return the resource's own id: per AWS, `Ref`
returns the management account id, while `Fn::GetAtt Org.Id` returns the organization id.

- `OrganizationalUnit` — `ParentId` is create-only per the registry schema, so an update only
  renames the OU in place; the physical id survives, keeping every `Ref` to it valid.
- `Account` — `ParentIds` accepts a single entry and the account is moved there after creation
  (accounts are always created under the root). Because Floci resolves a 12-digit access key id
  straight to an account, `Ref`/`Fn::GetAtt AccountId` yields an id you can immediately use as a
  caller identity against other services. `State` mirrors `Status`, the parameter AWS is retiring
  it in favour of; Floci does not model the `PENDING_ACTIVATION` or `CLOSED` phases that only
  `State` can express.
- `Policy` — `TargetIds` is reconciled on update: targets the template adds are attached and ones
  it drops are detached. Deleting the resource detaches its remaining targets first, since
  `DeletePolicy` refuses while a policy is still attached.
- `ResourcePolicy` — backed by `PutResourcePolicy`, which is already create-or-update.

`Fn::GetAtt OrganizationalUnit.Path` and `Fn::GetAtt Account.Paths` return the organization path —
`o-<org>/r-<root>/[ou-<ou>/…]<id>/`. An account has one parent and therefore one path, so the
list-typed `Paths` holds a single entry; read it with `Fn::Select` as you would on AWS.

Deleting the stack removes the resources in dependency order and finally the organization itself.
Deleting a resource that is already gone is tolerated.

## Auto Scaling Launch Template Resolution

`AWS::AutoScaling::AutoScalingGroup` resolves its launch template through any of the shapes AWS
accepts, not only by name:

- `LaunchTemplate` with `LaunchTemplateId` **or** `LaunchTemplateName` (plus an optional `Version`).
  The id and name are distinct lookup keys, so an `lt-` id is matched as an id rather than being
  treated as a name.
- A `Ref` to an in-stack `AWS::EC2::LaunchTemplate`, whose `Ref` returns the `lt-` id and whose
  `Fn::GetAtt LatestVersionNumber` supplies the version.
- `MixedInstancesPolicy` → `LaunchTemplate` → `LaunchTemplateSpecification`, including
  `Overrides[].InstanceType` and `InstancesDistribution` (`OnDemandBaseCapacity`,
  `OnDemandPercentageAboveBaseCapacity`, `SpotAllocationStrategy`). A non-integer where AWS expects
  a number fails the stack rather than being dropped.

## Lambda Stack Updates

`AWS::Lambda::Function` resources are reconciled during `UpdateStack` in the same shape as CloudFormation/CDK deployments:

- A no-op redeploy keeps the existing physical function name and does not call Lambda update APIs, so warm containers can be reused.
- Code and mutable configuration changes update the existing function in place.
- Replacement-only changes such as `FunctionName` or `PackageType` changes create a replacement function and remove the old one.
- S3-backed code stays linked through `S3Bucket` / `S3Key`, so Lambda's reactive S3 sync continues to work for functions created by CloudFormation or CDK.

## RDS Credential Dynamic References

`AWS::RDS::DBInstance` and `AWS::RDS::DBCluster` resolve CloudFormation dynamic
references in `MasterUsername` and `MasterUserPassword` during resource creation:

- `secretsmanager` references support whole secret strings, JSON keys, version stages, and version IDs.
- `ssm` references accept `String` and `StringList` parameters, using either the latest value or an explicit positive version.
- `ssm-secure` references require a `SecureString` parameter and are supported only for `MasterUserPassword`, matching the AWS resource-property allowlist.

Dynamic-reference expansion is currently scoped to these RDS credential properties.

## Account-Aware Provisioning

Resources provisioned by `CreateStack` / `UpdateStack` land in the **caller's account** namespace
(determined from the request's access key — see [Multi-Account Isolation](../configuration/multi-account.md)).
Deleting the stack removes them from that same account.

Stacks, change sets, and **exports are scoped to the caller account**. `ListExports` returns only the
exports created by that account's stacks, and `Fn::ImportValue` resolves against the same account's
export table — so two accounts can each own an export with the same name without colliding, mirroring
how CloudFormation isolates exports per account and region. This is what lets a multi-account LZA
deployment reuse identical export names across its member accounts.

## Template Parameters

Parameters passed to `CreateStack` / `UpdateStack` (and echoed back by `DescribeStacks`) are resolved
before provisioning:

- **`String`, `Number`, `CommaDelimitedList`, and `List<...>`** parameter types are accepted and
  substituted into resource properties via `Ref`.
- **`AWS::SSM::Parameter::Value<String>`-typed parameters** are resolved at deploy time: the value the
  caller supplies is treated as an SSM parameter *name*, and floci substitutes the current value of
  that SSM parameter into the template. This is the pattern CDK uses to pull bootstrap values (image
  tags, bucket names) from Parameter Store.
- `AWS::SSM::Parameter::Value<List<String>>` and `AWS::SSM::Parameter::Name` typed parameters are
  **not yet** resolved — they are passed through as their literal input.

The `AWS::SSM::Parameter` **resource** type exposes `Value`, `Type`, `Name`, and `Arn` attributes through
`Ref` / `Fn::GetAtt` so downstream resources can consume a parameter the same stack creates.

## AWS::Include (`Fn::Transform`)

`Fn::Transform` with `Name: AWS::Include` splices the YAML/JSON snippet at `Parameters.Location`
into its enclosing mapping before any other intrinsic resolves, on both `CreateStack`/`UpdateStack`
and the `CreateChangeSet` preview. `Location` must be an `s3://bucket/key` URI: the template
CloudFormation itself receives always carries one, because `aws cloudformation package` rewrites
every local path before a stack ever sees it. A `Location` that is not `s3://` fails the stack with
a `ValidationError` naming it, rather than silently dropping the include. A `Location` that is a
well-formed `s3://` URI but cannot be read (the bucket or key does not exist) fails the stack with
the underlying S3 error naming it, for example `NoSuchKey` at HTTP 404, instead of a
`ValidationError`, since that error is what S3 itself already reports and floci has no more specific
answer to give.

A snippet may not itself use `AWS::Include`; nesting is rejected rather than expanded or looped.
`Location` must be a plain string; a `Ref` or another intrinsic in its place is rejected, naming
the rejected node, rather than resolved.

Two known deviations from AWS:

- The snippet is parsed with floci's CloudFormation-aware YAML parser, so it accepts CloudFormation
  YAML short tags (`!Ref`, `!Sub`, ...) where AWS's own `AWS::Include` documentation says a snippet
  does not.
- AWS's own `Fn::Transform` documentation shows a `Location` written as an intrinsic function (its
  example uses `Ref`) and describes it as accepted; floci does not resolve one and rejects the
  template instead. This deviation comes from reading AWS's documentation, not from a request
  measured against a real account.

`CAPABILITY_AUTO_EXPAND` is not one of them. floci requires no capability for a template that
declares `AWS::Include`, and neither does AWS on the change-set path: a `CreateChangeSet` carrying
an embedded `Fn::Transform`/`AWS::Include` was accepted against a real account with
`CAPABILITY_IAM` alone. The `CreateStack` path was not measured.

`GetTemplateSummary` does not expand the include: it reports `AWS::Include` in the template's
`Transform`/`DeclaredTransforms` and neither fetches nor validates the snippet, matching AWS.
`GetTemplate` accepts `TemplateStage` (`Original`, the default, or `Processed`) and validates it
against that enum, rejecting anything else with a `ValidationError` naming the value, matching AWS.
`Original` returns the template exactly as submitted, `Fn::Transform` node and all. `Processed`
returns the merged and SAM-expanded tree, the same one a `CreateChangeSet` preview diffs against.

floci expands neither `AWS::LanguageExtensions`, nor a third-party macro, nor the top-level
`Transform: {Name: AWS::Include, Parameters: {Location: ...}}` form. A template carrying only one of
those three keeps `Processed` equal to `Original`, byte for byte.

Two triggers break that equality, because `executeTemplate` re-serializes the persisted body when
either one fires: an embedded `Fn::Transform` that merges, and a SAM transform. A SAM transform goes
further, because `SamTransformProcessor` removes the whole `Transform` section unconditionally, so a
macro co-declared beside SAM is absent from `Processed` as well.

Real AWS's answer for these shapes is unmeasured, co-declared with SAM or not. `StagesAvailable`
always lists both stages, matching AWS.

## Conditions

Template `Conditions` are evaluated before provisioning. A resource whose `Condition` evaluates to
**false is skipped** — it is never provisioned, does not appear in `DescribeStackResources`, and its
`Ref` / `Fn::GetAtt` are not required to resolve. This matches CloudFormation and is required for the
condition-heavy templates CDK and LZA emit.

## Custom Resources and the CDK Provider Framework

`AWS::CloudFormation::CustomResource` and `Custom::*` resources are backed by their `ServiceToken`
Lambda. floci supports two shapes:

- **Direct custom resources** — floci invokes the handler Lambda with the CloudFormation custom-resource
  event and waits for it to `PUT` its `SUCCESS`/`FAILED` result to the response URL. Inline `ZipFile`
  handlers get the `cfn-response` / `cfnresponse` module bundled in (see the Lambda row in
  [Supported Resource Types](#supported-resource-types)), so Solutions-style handlers work unmodified.
- **CDK Provider framework** — when the `ServiceToken` points at a CDK `framework.onEvent` function,
  floci drives the asynchronous provider protocol: `onEvent` starts the work and `framework.isComplete`
  is polled (via Step Functions [`Retry`](step-functions.md)) until it reports done, at which point the
  ResponseURL callback fires. The wait is bounded by an async custom-resource timeout (3 minutes by
  default); a resource that never completes fails the stack rather than hanging.

## Deletion Policies

A resource's [`DeletionPolicy`](https://docs.aws.amazon.com/AWSCloudFormation/latest/TemplateReference/aws-attribute-deletionpolicy.html)
attribute is honored on `DeleteStack` and on the rollback of a failed `CreateStack`:

| Value | `DeleteStack` | Rollback of the create that made the resource |
|---|---|---|
| `Delete` (default) | deleted | deleted |
| `Retain` | kept | kept |
| `RetainExceptOnCreate` | kept | deleted |

A kept resource is reported as `DELETE_SKIPPED` in `DescribeStackEvents` and does not fail the
deletion — the stack still reaches `DELETE_COMPLETE` while the resource keeps existing. This also
lets a stack owning a non-empty S3 bucket be deleted, since the bucket is never touched.

Deviations from AWS to be aware of:

- `Snapshot` deletes the resource without taking a snapshot; floci has no snapshot support for the
  types AWS allows it on.
- Every resource defaults to `Delete`. AWS instead defaults `AWS::RDS::DBCluster`, and
  `AWS::RDS::DBInstance` without a `DBClusterIdentifier`, to `Snapshot`.
- Unrecognized values are treated as `Delete`.
- An update that removes a resource from the template does not delete it (or consult its policy);
  only the new template's resources are provisioned. AWS applies `DeletionPolicy` to update-time
  removals as well.
- After `DeleteStack` completes, a retained resource's `DELETE_SKIPPED` record is only visible
  through `DescribeStackEvents` for the deleted stack, not `DescribeStackResources`.
- `UpdateReplacePolicy`, and the `RetainExceptOnCreate` request parameter of `CreateStack` /
  `UpdateStack`, are not implemented.

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
