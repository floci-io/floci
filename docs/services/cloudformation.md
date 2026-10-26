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
| `ContinueUpdateRollback` | Retry the rollback of a stack in `UPDATE_ROLLBACK_FAILED`, optionally skipping resources or forcing past every failed one (see [Failed update rollbacks](#failed-update-rollbacks)) |
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
| `ValidateTemplate` | Refuses a body that is no template (see [Template format](#template-format)) and a template holding a `null` value (see [Null values](#null-values)); otherwise returns success without validating further |
| `ListStacks` | List stacks by status |
| `ListExports` | - |
| `SetStackPolicy` | Accepted; no-op (stub — stack policies are not enforced) |
| `GetStackPolicy` | Accepted; returns an empty policy (stub) |
| `DescribeStackResource` | Get a specific stack resource |
| `DescribeOrganizationsAccess` | - |
| `ActivateOrganizationsAccess` | - |
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
| `ListStackSetAutoDeploymentTargets` | - |
<!-- floci:actions:end -->

## StackSets compatibility

StackSets support both `SELF_MANAGED` and the Cloud Launchpad `SERVICE_MANAGED` workflow. `ActivateOrganizationsAccess` requires an Organizations management account with all features enabled and enables trusted access for `stacksets.cloudformation.amazonaws.com`. Service-managed organizational-unit deployment targets are resolved from the caller's actual Organizations state rather than converted into synthetic account IDs. Targeting an OU includes eligible accounts directly in that OU and in all descendant OUs, while excluding the organization management account, matching AWS StackSets targeting semantics.

`CreateStackInstances` and `UpdateStackSet` execute the backing CloudFormation stacks in each target account. A failed resource can therefore drive the backing stack through rollback and leave the stack instance `INOPERABLE` with detailed status `FAILED`; the StackSet operation is reported as `FAILED` instead of being forced to success.

Operation IDs are recorded and validated. Duplicate IDs return `OperationIdAlreadyExistsException`, missing stack sets return `StackSetNotFoundException`, and missing operation IDs return `OperationNotFoundException`. Invalid targets and request shapes use the CloudFormation query-protocol validation errors. Operations complete locally, so `OperationInProgressException` is only reachable when local operation state actually overlaps; Floci does not inject concurrency failures solely to exercise an error code.

## CloudWatch Logs log streams

`AWS::Logs::LogStream` creates a stream in the required `LogGroupName`. `LogStreamName` is optional;
when omitted, including through `Fn::If` returning `AWS::NoValue`, CloudFormation generates a name
and keeps it across updates. An explicit empty name remains invalid. `Ref` returns the stream name.
The resource has no `Fn::GetAtt` attributes.

Changing either name replaces the stream. The previous stream and its events remain available
until the update commits; a failed stack update restores the previous stream. `UpdateReplacePolicy:
Retain` keeps a displaced stream. Stack deletion removes the current stream and its events, and
tolerates a stream that was already deleted.

## CloudWatch Logs metric filters

`AWS::Logs::MetricFilter` returns the filter name alone for both `Ref` and
`PhysicalResourceId`. Floci stores the log group separately for cleanup, including when names
contain `|`. Mutable updates keep generated names stable.

Changing `FilterName` or `LogGroupName` deletes the old filter before creating the replacement.
This works at the 100-filter group limit without temporarily exceeding the quota. The resource's
DELETE and CREATE events precede its final UPDATE event. The
[recorded AWS observations](cloudwatch-metric-filters-verification.md) also show this ordering
with an already-installed `UpdateReplacePolicy: Retain`. This is specific to metric-filter
replacement, not a change to ordinary stack `DeletionPolicy: Retain`.

Rollback restores the complete prior backing definition, not just its reference. A replacement
recreates the deleted filter and receives a new creation time. Failed restoration keeps its
snapshot for retry; a filter absent before the update remains absent after rollback.
That absence is retained as private nonownership metadata: later stack operations cannot adopt
or delete another filter that reuses the same group and name.
Named creates reject an existing filter rather than adopting it through Logs' upsert API.
If storage fails both the write and its ownership inspection, cleanup reports failure rather
than guessing ownership. The snapshot remains available, but an indeterminate surviving filter
requires operator reconciliation before rollback or deletion can finish.
`DeleteStack` preserves failed-rollback metadata until resource-aware cleanup runs. It removes
confirmed owned restorations or newly created filters whose rollback deletion failed. An
indeterminate outcome keeps the stack in `DELETE_FAILED` with its metadata available for retry.

CFN mutations record `APPLIED`, `NOT_APPLIED`, or `UNKNOWN` while holding the canonical filter
storage lock, including when storage throws after applying a write or delete. Each group/name
address tracks confirmed ownership, nonownership, or uncertainty. A confirmed delete relinquishes
ownership before another creator can enter, so neither rollback nor a later delete retry can
adopt that creator's filter. Unknown outcomes cannot authorize an upsert or deletion of a
surviving row. Confirmed absence permits safe recovery and finishes account-scoped pending
publication cleanup. Older private rollback flags that cannot establish an outcome are treated
conservatively as unknown.
Baseline snapshots containing `logGroupName` and `filterName` are retained as historical metadata,
not replayed as compensation for a new update: that older format could survive a successful
in-place update. Its explicit group/name fields preserve pipe-containing names without ambiguity.
After confirmed absence, a new update creates the requested definition rather than reviving
the stale snapshot.

CloudFormation dimensions use a `[{Key, Value}]` array, not the Logs API's object shape.
CFN model validation precedes mutation, while provider validation (such as parsing a replacement
filter pattern) can fail after deletion and trigger restoration. The CFN schema allows a
256-character metric namespace, but Floci's Logs API validation currently accepts at most 255:
passing model validation does not imply provider acceptance.

## Cognito user pool resource servers

`AWS::Cognito::UserPoolResourceServer` returns its `Identifier` for `Ref`. Changing `Identifier`
or `UserPoolId` replaces the server; changing `Name` or `Scopes` updates it in place. Optional
`Scopes` or individual scope entries can be omitted by `Fn::If` selecting `AWS::NoValue`.
The provisioner keeps the pool and identifier together in private replacement bookkeeping,
so changing only the pool still cleans up the displaced server without changing the `Ref` value.

Replacement rollback and historical cleanup use the shared bounded replacement lifecycle.
Provisioning leaves historical cleanup for a committed update or stack deletion. A failed provision
keeps that debt and its remaining retry budget.
Committed replacements honor `UpdateReplacePolicy: Retain`; failed rollback replacements remain
owed cleanup while their retry budget remains. Exhausted historical records are abandoned across
later updates and stack-delete retries. Historical cleanup is address-based and does not provide
an atomic ownership check against another stack or a direct Cognito API writer reusing the address.

Failed in-place restoration preserves the original name and scopes for a `ContinueUpdateRollback`
retry. Deleting the current server keeps this snapshot if deletion fails.

## SSM parameters

Like AWS's resource handler, `AWS::SSM::Parameter` is created with one `PutParameter` that carries
the template's tags and does not overwrite. A name that already exists outside the stack fails the
resource with `ParameterAlreadyExists`; the stack never overwrites, adopts or deletes that
parameter. Floci applies the `Name`, `Type`, `Value` and `Tags` properties.

An update that keeps `Name` overwrites the parameter. When a later resource fails the update, the
rollback writes the prior value and settings back, as a new SSM version when they changed (the
version history is not rewound, as on AWS), and returns the tags the previous or the failed
template set to their prior values; other tags are left alone. As on AWS, the update first checks
that the parameter exists: one deleted outside the stack fails it with `ParameterNotFound` before
anything is written. A rollback that finds the parameter gone fails the same way, leaving the stack
in `UPDATE_ROLLBACK_FAILED`.

Giving `Name` a new value replaces the parameter. The previous parameter stays until the update
commits, and is then deleted unless `UpdateReplacePolicy` is `Retain`; a failed update deletes the
replacement instead. A restore or delete that fails during the rollback leaves the stack in
`UPDATE_ROLLBACK_FAILED`, and `ContinueUpdateRollback` retries it (see
[Failed update rollbacks](#failed-update-rollbacks)). Deleting a parameter that is already gone
counts as deleted. Known differences: removing an explicit `Name` keeps the parameter under that
name, where AWS replaces it with one under a generated name; and an update in place of a parameter
deleted outside the stack ends in `UPDATE_ROLLBACK_COMPLETE`, since nothing was written, where AWS's
rollback repeats the existence check and likely ends in `UPDATE_ROLLBACK_FAILED`.

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
| S3 | `Bucket`, `BucketPolicy` (document stored on the bucket; S3 does not evaluate it) |
| S3 Vectors | `VectorBucket`, `Index` |
| SQS | `Queue`, `QueuePolicy` (accepted; policy not enforced) |
| SNS | `Topic`, `Subscription`, `TopicPolicy` |
| SES | `EmailIdentity` (BYODKIM is not supported by this resource; Easy DKIM DNS attributes use the shared emulator signing zone `dkim.amazonses.com` rather than AWS's region- and identity-specific zone assignment) |
| DynamoDB | `Table` (`TimeToLiveSpecification` applied on create and update; to rename an enabled TTL attribute, disable TTL in one update and enable the new attribute in a later one), `GlobalTable` |
| Lambda | `Function` (Zip via S3/inline `ZipFile`, Image, and the `hot-reload` bind-mount bucket), `LayerVersion`, `EventSourceMapping` (SQS, Kinesis, DynamoDB Streams; `ParallelizationFactor` and `TumblingWindowInSeconds` are ignored), `Version`, `Alias` (also what SAM's `AutoPublishAlias` expands into), `Permission`, `EventInvokeConfig` (also what a SAM function's `EventInvokeConfig` expands into), `MicrovmImage`, `NetworkConnector`, `Url`, `WebFunction` (control plane only; the endpoint domain does not serve requests yet), `WebFunctionEndpoint` (`RegionalEndpoints` is not exposed to `Fn::GetAtt` yet), `WebFunctionRevision` (every property is createOnly, so a change mints a new revision). Inline `ZipFile` packages include the `cfn-response` (Node.js) / `cfnresponse` (Python) module AWS injects for that code path, so Solutions-style custom-resource handlers work. |
| IAM | `Role`, `User` (template `LoginProfile` and `PermissionsBoundary` are ignored; an API-created login profile is removed on delete), `AccessKey`, `Policy`, `ManagedPolicy`, `InstanceProfile` |
| Organizations | `Organization`, `OrganizationalUnit`, `Account`, `Policy`, `ResourcePolicy` |
| SSM | `Parameter` |
| KMS | `Key`, `Alias` |
| Secrets Manager | `Secret`, `SecretTargetAttachment` |
| ECR | `Repository` |
| ECS | `Cluster`, `TaskDefinition`, `Service`, `CapacityProvider`, `ClusterCapacityProviderAssociations` |
| EKS | `Cluster`, `Nodegroup` |
| RDS | `DBInstance` (starts a real container), `DBCluster` (starts a real container), `DBSubnetGroup`, `DBParameterGroup`, `DBClusterParameterGroup`, `DBProxy`, `DBProxyTargetGroup` |
| Aurora DSQL | `Cluster` |
| ElastiCache | `CacheCluster` (starts a real container; modifying an existing cluster in place is not supported), `SubnetGroup`, `User` (Tags are ignored), `UserGroup` (Tags are ignored), `ServerlessCache` (GetAtt uses the dotted Endpoint.* and ReaderEndpoint.* form) |
| Redshift | `Cluster` (single-node container; Port and non-dev DBName ignored; ManageMasterPassword unsupported), `ClusterParameterGroup`, `ClusterSubnetGroup`, `ClusterSecurityGroup` (accepted; no EC2-Classic security group model) |
| Redshift Serverless | `Namespace` (Managed admin credentials use Secrets Manager; GetAtt uses the dotted Namespace.* form), `Workgroup` (starts a real container; one workgroup per namespace; capacity, VPC and price-performance settings are stored only; snapshot restore properties are ignored; GetAtt uses the dotted Workgroup.* form) |
| EC2 | `VPC` (deleted on stack teardown; a VPC with dependents outside the stack, including anything still holding a network interface in its subnets (an instance that is not terminated, a NAT gateway that is not deleted or failed, a standalone interface, an interface endpoint), fails the delete with `DependencyViolation`, as on AWS), `Subnet`, `SecurityGroup` (inline `SecurityGroupIngress`/`SecurityGroupEgress` supported), `SecurityGroupIngress`, `SecurityGroupEgress`, `InternetGateway`, `RouteTable`, `SubnetRouteTableAssociation`, `Route`, `NatGateway`, `EIP`, `Instance`, `LaunchTemplate`, `VPCGatewayAttachment`, `VPCEndpoint`, `NetworkAcl`, `NetworkAclEntry`, `SubnetNetworkAclAssociation`, `FlowLog`, `PrefixList` (updated in place; an update that changes `AddressFamily`, or `MaxEntries` together with `Entries`, fails) |
| Elastic Load Balancing v2 | `LoadBalancer`, `TargetGroup`, `Listener`, `ListenerRule` |
| Auto Scaling | `LaunchConfiguration`, `AutoScalingGroup`, `LifecycleHook`, `ScalingPolicy` |
| Application Auto Scaling | `ScalableTarget` (`ScheduledActions` are accepted and not run; Floci has no scheduled scaling), `ScalingPolicy` |
| Route 53 | `HostedZone`, `RecordSet`, `HealthCheck` |
| Cloud Map | `HttpNamespace`, `PrivateDnsNamespace`, `PublicDnsNamespace`, `Service` |
| API Gateway (v1) | `RestApi`, `Resource`, `Authorizer`, `Method`, `Deployment`, `Stage`, `Account`, `DomainName`, `BasePathMapping`, `GatewayResponse`, `ApiKey`, `UsagePlan`, `UsagePlanKey` |
| API Gateway v2 | `Api`, `Authorizer`, `Route`, `Integration`, `Stage`, `Deployment`, `DomainName`, `ApiMapping`, `VpcLink` |
| AppSync | `GraphQLApi`, `GraphQLSchema`, `DataSource`, `FunctionConfiguration`, `Resolver`, `ApiKey`. `Ref` on `GraphQLApi` and `ApiKey` returns the id where AWS returns the ARN. See [AppSync](appsync.md#cloudformation) for `Ref` and `Fn::GetAtt` compared with AWS. |
| Step Functions | `StateMachine` |
| CodePipeline | `Pipeline`, `CustomActionType`, `Webhook` |
| CodeBuild | `Project` |
| Batch | `ComputeEnvironment`, `JobQueue`, `JobDefinition` |
| Cognito | `UserPool` (`ProviderURL` is the local issuer of the tokens Floci mints, `<base-url>/<pool id>`), `UserPoolClient`, `UserPoolDomain`, `UserPoolGroup`, `UserPoolResourceServer`, `UserPoolUser`, `UserPoolUserToGroupAttachment` |
| ACM | `Certificate` |
| EventBridge | `Rule`, `EventBus`, `EventBusPolicy`, `ApiDestination`, `Archive` (`KmsKeyIdentifier` is ignored) |
| EventBridge Scheduler | `Schedule`, `ScheduleGroup` |
| Transfer Family | `Server` (management plane only; Ref returns the server ARN; Arn, ServerId and State attributes supported; AS2 managed egress IPs are not modeled; Domain replacement and IdentityProviderType changes are not supported), `User` (management plane only; Ref returns the user ARN; Arn attribute supported; Policy and PosixProfile are not supported; a ServerId or UserName change replaces the user) |
| Backup | `BackupVault` |
| GuardDuty | `MalwareProtectionPlan` (management plane only: no scan runs, so the plan stays ACTIVE; Ref returns the plan id; a BucketName change is rejected) |
| Pipes | `Pipe` |
| Kinesis | `Stream` |
| Kinesis Data Firehose | `DeliveryStream` (`DeliveryStreamName`, `Tags` and, from an S3 destination, `BucketARN`, `RoleARN`, `Prefix`, `ErrorOutputPrefix`, `CompressionFormat`, `BufferingHints`, `CloudWatchLoggingOptions` and `ProcessingConfiguration` are applied; other properties are ignored, and a destination member the template drops on update keeps its stored value, as on AWS) |
| IoT Core | `Authorizer`, `DomainConfiguration` (`ServerCertificates` resolves to a JSON string), `Policy` (deleted after detaching it from its principals; on AWS the delete fails with `DeleteConflictException` while the policy is attached), `Thing`, `TopicRule` |
| CloudFront | `CachePolicy`, `Distribution`, `Function` (`KeyValueStoreAssociations` is ignored), `KeyGroup`, `OriginAccessControl`, `OriginRequestPolicy`, `PublicKey`, `ResponseHeadersPolicy` |
| CloudWatch | `Alarm`, `CompositeAlarm`, `Dashboard`. Composite alarms have backing definitions with name Ref and Arn GetAtt, rollback, and replacement cleanup. Composite actions are stored only; suppression properties are rejected. |
| CloudWatch Logs | `LogGroup`, `LogStream`, `MetricFilter` |
| WAFv2 | `IPSet`, `LoggingConfiguration`, `WebACL`, `WebACLAssociation` |
| Config | `ConfigRule` |
| Bedrock AgentCore | `Runtime` (Ref returns the AgentRuntimeId; CapacityProviderConfiguration, FilesystemConfigurations, LifecycleConfiguration, PlatformVersion and RequestHeaderConfiguration are accepted and not stored), `RuntimeEndpoint` (Ref returns the endpoint ARN; Tags are accepted and not stored), `Memory` (Ref returns the memory ARN; MemoryStrategies, IndexedKeys, NamespaceKeys and StreamDeliveryResources are accepted and not stored) |
| CloudFormation | `CustomResource`, `Custom::DynamoDBReplica` (applied natively against DynamoDB, not via a provider Lambda), `Stack` (nested stacks), `Custom::*` (Lambda-backed) |
| CloudTrail | `Trail` |
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

## SES Email Identities

`AWS::SES::EmailIdentity` provisions an SES identity and supports in-place option updates and
replacement when `EmailIdentity` changes. Change-set previews also mark the replacement and its
`Ref` dependents.

If an update rollback cannot restore the identity snapshot, the stack remains in
`UPDATE_ROLLBACK_FAILED`. `ContinueUpdateRollback` retries the restoration; `UpdateStack` is
rejected until rollback completes. A successful retry permits later updates. `DeleteStack` can
delete the current managed identity without first restoring its snapshot.

Cleanup keeps displaced-identity records while deletion attempts remain. After three cleanup
attempts, an exhausted entry is removed from stack management and a later `DeleteStack` does not
retry it. Committed update cleanup uses the same three-attempt limit.

## EventBridge Scheduler Schedules

`AWS::Scheduler::Schedule` creates a real schedule. `Ref` returns its name and `Fn::GetAtt Arn`
returns its ARN. A group move preserves the name, removes the old group address, and restores the
original configuration if the stack update rolls back.

`Fn::If` values that select `AWS::NoValue` omit optional schedule properties and nested target
or time-window fields. `ScheduleExpression`, `Target`, `FlexibleTimeWindow`, and their required
fields still must be present and valid.

Floci uses its main engine's bounded replacement-cleanup policy for committed updates and
`DeleteStack`: each displaced address has up to three cleanup attempts, and exhausted entries are
dropped. A failed cleanup leaves the first stack deletion in `DELETE_FAILED`, even if the current
schedule was deleted; a later retry does not retry the dropped addresses. Schedules left after
exhausted cleanup require manual deletion through the Scheduler API. A failed current-schedule
delete preserves pending recovery state for a later deletion retry. This is Floci's shared cleanup
policy, not a claim that AWS guarantees three attempts for `DeleteStack`.
`UpdateReplacePolicy: Retain` keeps a name replacement's old schedule, but does not keep a group
move's old address or an orphan created by a failed update.

Schedules are addressed by group and name, as in the Scheduler API. If another client removes a
managed schedule and recreates the same address, an in-place stack update applies the template to
the current schedule there. Stack deletion and replacement cleanup delete the current schedule
at a tracked address, and rollback restores its saved configuration at that address.
A historical cleanup entry with attempts remaining can still affect a new occupant of its
address. Dropping exhausted debt prevents later retries, but does not establish resource ownership.

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

## EventBridge archives

`AWS::Events::Archive` creates a real archive of the events sent to `SourceArn`, which must be an
existing event bus in the stack's account and region, as `CreateArchive` requires. `ArchiveName` is
optional; when omitted, CloudFormation generates a name and keeps it across updates. `Ref` returns
the archive name and `Fn::GetAtt Arn` the archive ARN. A missing `RetentionDays` stores 0, and an
`EventPattern` object reads back from `DescribeArchive` as a compact JSON string.
`KmsKeyIdentifier` is ignored. A `Description` over 512 characters, an `EventPattern` over 4096, or a
negative `RetentionDays` fails the resource with the `CreateArchive` or `UpdateArchive` validation
message, as on AWS.

Changing `Description`, `EventPattern`, or `RetentionDays` updates the archive in place. A property
removed from the template is not sent, so the archive keeps its previous value, as on AWS. Changing
`SourceArn` or `ArchiveName`, or dropping an explicit `ArchiveName`, replaces the archive: the new
archive is created during the update and the previous one is deleted once the update commits. A
replacement that keeps the same explicit `ArchiveName`, or declares the generated name explicitly,
fails with CloudFormation's custom-named resource error and leaves the archive unchanged. Stack
deletion removes the archive and tolerates one that was already deleted.

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
  a number fails the stack rather than being dropped. `Overrides[].WeightedCapacity` is carried
  through as well.

The group also passes `ServiceLinkedRoleARN`, `InstanceMaintenancePolicy`,
`AvailabilityZoneDistribution` and `CapacityReservationSpecification` through to the group, with
the same validation the Auto Scaling API applies. The resource schema requires
`CapacityReservationPreference` on `CapacityReservationSpecification` and `Granularity` on each
`MetricsCollection` entry. `MetricsCollection` enables the listed metrics (an entry naming none
enables all of them), and on `UpdateStack` the group's enabled metrics are
set to exactly what the template lists. Removing the property leaves collection as it was.

## Updates With Nothing to Change

An `UpdateStack` that changes none of the stack's resources and outputs is refused with
`ValidationError` `No updates are to be performed.` and changes nothing, as on AWS. What is compared
is what CloudFormation compares (measured on AWS): of each active resource its `Type`, `Properties`,
`Metadata`, `DeletionPolicy`, `UpdateReplacePolicy` and `UpdatePolicy`, and of each active output its
`Value`, `Description` and `Export`, with whatever does not depend on a resource resolved first:
parameters, mappings, conditions, pseudo parameters, and dynamic references, whose stored value is
compared with the one the stack was deployed with. A literal written as `Fn::Join`, a parameter
written as `Fn::Sub`, or an `Fn::If` whose other branch changed is therefore no change, while a used
mapping value, a used `AWS::SSM::Parameter::Value` parameter whose stored value moved, or a dynamic
reference whose value moved is. A `Ref` or `Fn::GetAtt` of a resource, `Fn::ImportValue` and
`Fn::GetStackOutput` are compared as written. A new `Description`, template `Metadata` or `Rules`,
an unused parameter, mapping or condition, a `DependsOn`, a `CreationPolicy`, or an output whose
condition is false is not an update; tags that differ from the stack's are, and a template with a
nested stack is always updated, since its child template is read again. The template is processed
as CloudFormation processes it: `AWS::Include` fragments are read again and merged and the SAM
transform is applied with the stack's parameter values. The same comparison decides which resources
a change set modifies.

What each dynamic reference resolved to is kept on the stack when an operation completes, as an
HMAC-SHA256 under a random key of the stack's own, so no resolved value is stored. An update that
fails and rolls back keeps the values of the last deployment. A stack set instance whose stack has
nothing to change is left as it is and succeeds, and the change set that found it so is not kept.

A `CreateChangeSet` for such an update is created `FAILED`, `UNAVAILABLE`, with `The submitted
information didn't contain changes. Submit different information to create a change set.`, which is
what the AWS CLI's `deploy` and the CDK read as "no changes".

The CDK then deletes that change set, and before its next deploy deletes it again by name. A
`DeleteChangeSet` for a change set the stack does not have succeeds, as on AWS, whatever the stack's
status, named with its stack or by its ARN alone; a `StackName` that names no stack is still a
`ValidationError`.

## Lambda Stack Updates

`AWS::Lambda::Function` resources are reconciled during `UpdateStack` in the same shape as CloudFormation/CDK deployments:

- A no-op redeploy keeps the existing physical function name and does not call Lambda update APIs, so warm containers can be reused.
- Code and mutable configuration changes update the existing function in place.
- Replacement-only changes such as `FunctionName` or `PackageType` changes create a replacement function and remove the old one.
- A changed `DurableConfig` updates the function in place. A `RetentionPeriodInDays` the template leaves out goes back to 14, and a `KMSKeyArn` it leaves out is cleared. Adding or removing `DurableConfig` replaces the function, since Lambda cannot do either in place. A durable function created without `Timeout` gets `min(ExecutionTimeout, 900)`, while an update without it sets 3, as on AWS.
- `LoggingConfig` reaches the function on create and on update, in place. Removing it from the template puts the function back on its defaults: `Text` (`JSON` for a durable function) and `/aws/lambda/<name>`.
- S3-backed code stays linked through `S3Bucket` / `S3Key`, so Lambda's reactive S3 sync continues to work for functions created by CloudFormation or CDK.
- Hot-reload code (`S3Bucket: hot-reload`) is compared by host path: the same path is a no-op, a different path updates the bind mount in place.
- `Tags` are applied when the function is created, including a replacement function. On `UpdateStack` the template's tags are applied and only the keys the previous template set and the new one drops are removed, so a tag added outside the template is kept, as in AWS.

## SAM published aliases

`AutoPublishAlias` creates a version and points the generated alias at that version's
numeric `Version` attribute. CloudFormation resolves the version before creating the
alias. As in SAM, the version's logical id is `<Function>Version` followed by ten hex digits of a
SHA-1 over the function's code, with its template parameter references resolved (pseudo
parameters such as `${AWS::Region}` stay as written), its `Environment` and `MemorySize` as
written, and its `SnapStart` when it sets an `ApplyOn` other than `None`. By default, then, an
update that changes the code, `Environment` or `MemorySize`, or a parameter the code uses, publishes
a new version and moves the alias to it. A parameter used only in `Environment` or `MemorySize` does
not, and neither does a property outside the hash, such as `Timeout`.

Two optional function properties change the id. Under `AutoPublishAliasAllProperties: true` the
hash covers every property floci gives the function, its code's parameter references resolved as
above, plus its `SnapStart`; those properties differ from the ones SAM generates, so these ids are
floci's own rather than SAM's. An `AutoPublishCodeSha256` that is a string, or a `Ref` to a
parameter holding one, replaces the hash with its first ten characters, under
`AutoPublishAliasAllProperties` too; an empty value leaves the hash in place. As in SAM, a given
`AutoPublishCodeSha256` is also appended to the function's `Description`, after a space, so the
version a new value names publishes even when nothing else changed. The description's parameter
references are resolved first, one that is another intrinsic function is joined with the value by
`Fn::Join`, and a function without a description gets the value alone. An empty
`AutoPublishCodeSha256` appends nothing, but SAM checks the property as written, so a `Ref` to a
parameter holding an empty value still appends it: a description then ends in a space, and a
function without one gets an empty `Description`. `Globals` can set
`AutoPublishAliasAllProperties` and `VersionDeletionPolicy`, but as in SAM not
`AutoPublishCodeSha256`, which fails the transform there. floci does not honour a resource's
`IgnoreGlobals`, so no function can opt out of a `VersionDeletionPolicy` in `Globals` with it: one
without `AutoPublishAlias` fails the transform with
`'VersionDeletionPolicy' requires 'AutoPublishAlias'.`, as it does in SAM when it does not ignore
that global.

A new id publishes a version, and moves the alias to it, only when the function's code or
configuration changed since its last version. Otherwise the new version resource takes the last
version and the alias stays where it is, as when an update changes only one of these:

- A change to `SnapStart`: floci does not give the function `SnapStart`.
- Under `AutoPublishAliasAllProperties`, a change to `Tags` or `ReservedConcurrentExecutions`
  only: AWS fails that update with
  `A version for this Lambda function exists ( N ). Modify the function to create a new version.`,
  where floci completes it.

A function whose code is hot-reloaded publishes a version for every new id.

Like SAM, floci gives the version the function's `DeletionPolicy`, else its `VersionDeletionPolicy`
(`Delete` or `Retain`), else `Delete` for a function with a `CapacityProviderConfig` and `Retain`
for any other. So by default the version a new id replaces stays published, and the update reports
it `DELETE_SKIPPED`. With `Delete` floci reports the old version `DELETE_COMPLETE` but never deletes
it, where AWS does. A `VersionDeletionPolicy` written as an intrinsic function, such as a `Ref` to
a parameter, is copied onto the version unresolved, as SAM copies it. CloudFormation takes
intrinsic functions only in resource properties, outputs, metadata attributes and update policy
attributes, unless a transform such as `AWS::LanguageExtensions` resolves them first, and floci
implements no such transform: it reads any `DeletionPolicy` that is not a plain string as `Delete`
(see [Deletion Policies](#deletion-policies)).

Changes made to `$LATEST` outside the stack, including Lambda's reactive S3 sync, do not reach the
alias until an update publishes a new version. Event sources and API integrations still invoke the
function rather than the alias, so they run `$LATEST`.

## RDS Credential Dynamic References

`AWS::RDS::DBInstance` and `AWS::RDS::DBCluster` resolve CloudFormation dynamic
references in `MasterUsername` and `MasterUserPassword` during resource creation:

- `secretsmanager` references support whole secret strings, JSON keys, version stages, and version IDs.
- `ssm` references accept `String` and `StringList` parameters, using either the latest value or an explicit positive version.
- `ssm-secure` references require a `SecureString` parameter and are supported only for `MasterUserPassword`, matching the AWS resource-property allowlist.

Dynamic-reference expansion is currently scoped to these RDS credential properties.

## RDS Associated Roles

`AWS::RDS::DBCluster` and `AWS::RDS::DBInstance` apply `AssociatedRoles` through the same checks as
`AddRoleToDBCluster` / `AddRoleToDBInstance` (see [RDS IAM role associations](rds.md#iam-role-associations)):

- `Fn::If` may select the whole list or a single entry, and `AWS::NoValue` leaves it out. A value that is not a list of roles is a `ValidationError`.
- On create, the roles are associated with the new cluster or instance. A list RDS refuses (a missing `RoleArn`, an instance role without `FeatureName`, or a cluster role named twice) fails the resource, and the new database is deleted again. As in AWS, an instance role or feature the list repeats is skipped, not refused. A renamed instance keeps its predecessor until the new one has its roles.
- On `UpdateStack` (no interruption, as in AWS), a cluster's list is checked first, then the roles the template gained are added, and the roles the previous template declared and the new one drops are removed. A role associated outside the stack is kept while the template leaves it out; declaring it fails the update with `DBClusterRoleAlreadyExists`, as in AWS. If any step of an update is refused, the roles it already added are removed and the ones it removed are put back, so the database matches what the stack still records and a retry starts from there. A rolled-back cluster replacement restores the record of which roles the stack declared.
- An Aurora cluster member (`AWS::RDS::DBInstance` with `DBClusterIdentifier`) cannot carry `AssociatedRoles`: the schema documents the property as not applicable there, since the cluster manages its roles. The resource fails with a `ValidationError` pointing at the cluster's `AssociatedRoles`.

## S3 ownership controls

`AWS::S3::Bucket` applies `OwnershipControls` through the same S3 call as
`PutBucketOwnershipControls`, on create and in place on update.

- A malformed rule fails the resource before the bucket is created or changed, and the stack
  rolls back. The resource's status reason gives the first fault found: `OwnershipControls` is
  checked first, then each rule in turn, then whether a rule repeats another, then S3's own check.
- A fault the resource schema defines is reported as
  `Model validation failed (#/OwnershipControls...: <reason>)`: a value of the wrong type
  (`OwnershipControls` or a rule that is not an object, `Rules` that is not a list, an
  `ObjectOwnership` that is a list or an object), a key other than `Rules` on `OwnershipControls`
  or other than `ObjectOwnership` on a rule (`extraneous key [<key>] is not permitted`), or a
  rule listed twice (`array items are not unique`), comparing the rules after intrinsic
  functions such as `Ref` are resolved.
- A rule without `ObjectOwnership`, which S3 requires, fails among that rule's own checks in the
  same form, with `required key [ObjectOwnership] not found`. The schema accepts such a rule, so
  CloudFormation's reason for a template with one can differ.
- What S3 refuses then fails with the message of the `MalformedXML` error that
  `PutBucketOwnershipControls` returns, `The XML you provided was not well-formed or did not
  validate against our published schema.`: no rule, more than one rule, or a mode other than
  `BucketOwnerEnforced`, `BucketOwnerPreferred` or `ObjectWriter`. AWS's published resource
  schema refuses two of these itself, a missing `Rules` (`required key [Rules] not found`) and a
  mode outside the three (its `enum`), so CloudFormation's reason for them may differ.
- An update that removes `OwnershipControls` from a template that declared it deletes the
  controls, as a removed `NotificationConfiguration` is cleared; real AWS's answer for that
  removal is unmeasured. Controls set outside the stack are left alone while neither the previous
  nor the new template declares the property.
- A failed update, including one where a later resource fails, puts back the controls the bucket
  had before the update, with the rest of its configuration (see
  [Failed update rollbacks](#failed-update-rollbacks)).
- Floci's S3 gives a new bucket no ownership controls, so a bucket that never declares the
  property answers `GetBucketOwnershipControls` with `OwnershipControlsNotFoundError`, where AWS
  reports its default, `BucketOwnerEnforced`.

## ECS task definitions

CloudFormation hands an `AWS::ECS::TaskDefinition`'s properties to the ECS resource handler
without template validation, every value as text, and the handler converts each to the type the
resource schema declares (measured). Floci reads them the same way, before anything is registered:

- An integer is a sign and digits once trimmed, within 32 bits (`"+128"`, `" 128"` and `"0128"`
  are 128), and a boolean is `true` or `false` in lower, upper or title case once trimmed
  (`"True"`, `" true"`). A number where text is expected is its text.
- A value that does not convert (`"abc"`, `4.5` or `true` for an integer, `"yes"`, `"tRuE"` or
  `"1"` for a boolean), an object or a list where a scalar is expected, and a scalar where an object
  or a list is expected fail the resource with every violation of the unconverted model, where
  every scalar is text, so an integer or a boolean that converts is listed too:
  `Model validation failed (#/ContainerDefinitions/0/Memory: expected type: Integer, found: String.
  This may occur when a numeric value exceeds 32-bit integer max (2147483647). Consider using
  'number' type or ensure value fits in integer range.` and, on the next line,
  `#/ContainerDefinitions/0/LinuxParameters/Capabilities/Add: expected type: JSONArray, found:
  String)`. At each object its missing required members come first, then its members in the
  schema's order.
- When every value converts, the required members the model lacks fail it:
  `Model validation failed (#/ContainerDefinitions/0: required key [Image] not found)`. Several are
  counted where their paths meet, `Model validation failed (#/ContainerDefinitions: 2 schema
  violations found)`, with a line for each below it.
- A `Ref` to a `CommaDelimitedList` parameter is a list, and a `Ref` to a `String` parameter is text.
- The schema's other constraints (allowed values, patterns, bounds) are not checked. AWS also
  records a warning event for a value that it does not convert itself and the handler does
  (`" 128"`, `"0128"`, `" true"`); Floci does not.

## Account-Aware Provisioning

Resources provisioned by `CreateStack` / `UpdateStack` land in the **caller's account** namespace
(determined from the request's access key — see [Multi-Account Isolation](../configuration/multi-account.md)).
Deleting the stack removes them from that same account.

Stacks, change sets, and **exports are scoped to the caller account**. `ListExports` returns only the
exports created by that account's stacks, and `Fn::ImportValue` resolves against the same account's
export table — so two accounts can each own an export with the same name without colliding, mirroring
how CloudFormation isolates exports per account and region. This is what lets a multi-account LZA
deployment reuse identical export names across its member accounts.

## Stack Output References (`Fn::GetStackOutput`)

`Fn::GetStackOutput` reads an output of another stack, which needs no `Export`. It is what the CDK
synthesizes for a weak cross-stack reference. The value is read when the consuming resource is created
or updated. Floci follows what CloudFormation does, measured against it, where that departs from the
intrinsic's reference page:

- `StackName` and `OutputName` are required; `Region` defaults to the consuming stack's Region, and
  `RoleArn` names a role in the account that owns the referenced stack. A key the function does not
  define is ignored.
- A parameter value may use any intrinsic function, including a `Ref` or `Fn::GetAtt` to a resource
  of the same stack, `Fn::ImportValue` and another `Fn::GetStackOutput`; it resolves like any property.
  A dynamic reference is not resolved there, one inside an `Fn::Sub` variable map included: the stack
  name reaches DescribeStacks as written and fails its name constraint (`1 validation error detected: Value '{{resolve:ssm:...}}' at 'stackName'
  failed to satisfy constraint: ...`), as on AWS.
- A referenced stack that does not exist fails the resource with DescribeStacks' `ValidationError`
  (`Stack with id X does not exist (Service: AmazonCloudFormation; ...)`), and an output that does not
  exist with `TemplateError: Fn::GetStackOutput references output O from stack S, but this output was
  not found. The output may have been deleted.` The reference never resolves to an empty string.
- A `Region` that is not a Region fails with `TemplateError: Region R in Fn::GetStackOutput is not a
  valid AWS region.`, and one of another partition with `InvalidClientTokenId`, as the call to it does.
- A `RoleArn` that is not an ARN fails with `TemplateError: Invalid RoleArn parameter X in
  Fn::GetStackOutput`. Any other ARN that is not an existing role (a user, a role of another
  partition, a role that does not exist) fails with STS's `AccessDenied`, naming the account's root
  as the caller. On AWS the role is assumed by the stack's execution role, or without one by the
  caller of the stack operation, and the outputs are read with DescribeStacks in a session named
  `GetStackOutput`; the CDK's role trusts the consuming account's execution role. Floci runs stack
  operations under no execution role and keeps no caller for them. With IAM enforcement off, as
  `AssumeRole`, it evaluates neither the role's trust policy nor its permissions. With enforcement on,
  the trust policy has to allow a principal in the consuming account (its root, or any principal of
  it the policy names, such as the CDK's execution role), or STS's `AccessDenied` fails the resource,
  and the role's policies have to allow `cloudformation:DescribeStacks` on the stack, or
  DescribeStacks' `AccessDenied` does (`User: arn:aws:sts::A:assumed-role/R/GetStackOutput is not
  authorized to perform: cloudformation:DescribeStacks on resource: ... because no identity-based
  policy allows the cloudformation:DescribeStacks action`).
- Arguments that are not a map, or that lack `StackName` or `OutputName`, are refused when the stack
  operation is requested, with `ValidationError` `TemplateError: Fn::GetStackOutput requires a map of
  named parameters` or `GetStackOutput: StackName is required`, as on AWS.
- An output whose `Condition` is false is never read, so one naming a stack that does not exist
  leaves the operation complete; like any output whose condition is false, it is not listed.
- It resolves inside an `Fn::Sub` variable map, inside `Fn::Base64` and as a direct `Outputs` value,
  which the reference page lists as not supported yet. An output that cannot be read fails the
  operation once the resources exist, and the stack rolls back. Anywhere in `Conditions`, and inside
  `Fn::ImportValue`, the stack operation is refused when it is requested, with `ValidationError`,
  `AWS::Include` fragments included; in an output's `Export.Name` too, with `Template format error:
  Output O is malformed.  The Name field of Export must not depend on any resources, imported values,
  or Fn::GetAZs.` In a nested stack's template the same rules fail the child's resource with the
  same message when the child is created, and the parent rolls back.

## Template format

As on AWS, a body is read as YAML, of which JSON is a subset, so JSON with a trailing comma or
unquoted keys is still a template. A body that reads as neither is refused by `ValidateTemplate`,
`CreateStack`, `UpdateStack`, `CreateChangeSet` and `GetTemplateSummary`, before anything is
created, with `ValidationError` `Template format error: JSON not well-formed. (line L, column C)`
when it starts with `{` and ends with `}`, and `Template format error: YAML not well-formed. (line
L, column C)` otherwise, at the position where the parser stopped. A body that reads as a scalar or
a list is `Template format error: unsupported structure.`

## Attribute types

`AWS::EC2::PrefixList` `Version` remains numeric when used as another resource's property.
Numeric attributes are rejected in string outputs and `Fn::Join`, `Fn::Sub`,
`Fn::Split`, and `Fn::Base64`, including selected `Fn::If` and `Fn::Select` values.
Literal numbers and Number-parameter references retain their existing string coercion.

## Null values

As on AWS, a template holding a `null` anywhere (JSON `null`, YAML `~` or a key with no value) is
refused when `ValidateTemplate`, `CreateStack`, `UpdateStack` or `CreateChangeSet` is requested,
before anything is created, with `ValidationError` `[<path>] 'null' values are not allowed in
templates`. The path names the first `null` in document order, as AWS's does: below a resource's
`Properties` it says `Type` where `Properties` would be (`[/Resources/Queue/Type/QueueName]`), a
`null` `Properties` is `[/Resources/Queue/Properties]`, list entries are numbered from 0, and an
intrinsic's arguments are part of the path (`[/Resources/Queue/Type/QueueName/Fn::Join/1/1]`).
A template that is null as a whole (`null`, `~`) is refused with `Template format error: At least
one Resources member must be defined.` `CreateStackSet` and `UpdateStackSet` refuse a template
holding a `null` before the stack set keeps it, with the same message followed by the service
exception's details, as on AWS.

## Template URLs

A `TemplateURL` names an object in Floci's S3, virtual-hosted or path-style. As on AWS, one whose
bucket or object does not exist is refused by `ValidateTemplate`, `CreateStack` and
`GetTemplateSummary` alike, with `ValidationError` `S3 error: The specified bucket does not exist`
or `S3 object does not exist. Bucket: <bucket>, Key: <key>. Error: NoSuchKey`. AWS also refuses a
URL that is not an S3 one (`TemplateURL must be a supported URL.`); Floci reads any host as S3, so
the URL can name Floci by whatever host reaches it.

## Template Parameters

Parameters passed to `CreateStack` / `UpdateStack` (and echoed back by `DescribeStacks`) are resolved
before provisioning:

- **`String`, `Number`, `CommaDelimitedList`, and `List<...>`** parameter types are accepted and
  substituted into resource properties via `Ref`. Where a property takes a list (a Lambda function's
  `Layers` or `Architectures`, a layer's `CompatibleRuntimes` or `CompatibleArchitectures`), a `Ref` to
  a `CommaDelimitedList`, `List<...>` or SSM list parameter is a list, and a `Ref` to a `String` or
  `Number` parameter is refused as a string, as are `Fn::Select`, `Fn::Join`, `Fn::Sub`, `Fn::Base64`, a
  pseudo-parameter but `AWS::NotificationARNs`, and an `Fn::If` choosing one of these:
  `Property [Architectures] expected type: JSONArray, found: String`.
- **`AWS::SSM::Parameter::Value<String>`-typed parameters** are resolved at deploy time: the value the
  caller supplies is treated as an SSM parameter *name*, and floci substitutes the current value of
  that SSM parameter into the template. This is the pattern CDK uses to pull bootstrap values (image
  tags, bucket names) from Parameter Store.
- `AWS::SSM::Parameter::Value<List<String>>` and `AWS::SSM::Parameter::Name` typed parameters are
  **not yet** resolved — they are passed through as their literal input.
- **A declared parameter needs a value.** One that the request gives no value and the template no
  `Default` fails `CreateStack`, `UpdateStack` and `CreateChangeSet` with `ValidationError`
  `Parameters: [Env] must have values`, naming each such parameter, before anything changes. An
  empty value counts as a value. An update does not carry values over: set `UsePreviousValue` to
  keep the deployed one. `UsePreviousValue` on a parameter that has no deployed value passes none.
  The values are checked before the template's dependency checks (a cycle, an unresolved
  dependency, a malformed condition), which read them, so a template with a cycle and a missing
  value gets this error. A request for a stack that does not exist, cannot be updated, or already
  exists gets that stack error instead of this one; a dependency error still comes before the
  stack error. The same check
  refuses `CreateStackSet` and `UpdateStackSet`, and fails a nested stack's resource whose
  `Parameters` leave a child parameter without a value. A parameter that an `AWS::Include` defines
  is not checked, nor is any parameter in a section that carries an `AWS::Include` of its own.

The `AWS::SSM::Parameter` **resource** type exposes `Value`, `Type`, `Name`, and `Arn` attributes through
`Ref` / `Fn::GetAtt` so downstream resources can consume a parameter the same stack creates.
`Tags` are applied when the parameter is created. On `UpdateStack` the template's tags are applied and
only the keys the previous template set and the new one drops are removed, so a tag added outside the
template is kept, as in AWS.

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

## Explicit SAM REST API authentication

`AWS::Serverless::Api.Auth` supports `DefaultAuthorizer: AWS_IAM` and the boolean
`ApiKeyRequired` with an inline Swagger 2.0 or OpenAPI 3.0 `DefinitionBody`. The transform
adds the corresponding security schemes and operation requirements. API Gateway imports
these as `authorizationType: AWS_IAM` and `apiKeyRequired: true`: invocation requires both
a valid SigV4 signature and an enabled API key associated with the invoked stage when both
controls are selected. Existing operation requirements and their scheme definitions are
preserved. Defaults are selected by the SAM names `AWS_IAM` and `api_key`; an existing
scheme with another name does not suppress either default.
As in SAM, the defaults are added to each operation's own `security`, inside each branch of
an `Fn::If` written around a path or an operation, skipping an `AWS::NoValue` branch. A
nested `Fn::If` around an operation is left as written, without the defaults, as SAM leaves
it.
Root-level `security` stays in the document as written and is not copied into operations,
and an `Auth` that selects no default, such as `ApiKeyRequired: false` alone, leaves the
document unchanged. floci's REST importer still applies root-level `security` to an
operation that has none of its own, which API Gateway does not. As in SAM, any non-empty
`Auth`, even one that selects no default, needs a Swagger or OpenAPI 3 `DefinitionBody`
whose `paths` entries, and the branches of an `Fn::If` around one, are objects, so a body or
a `paths` map written as an `Fn::If` is rejected.

Other `Auth` properties, custom default authorizers and external definitions are rejected.
When a default applies, so are unresolved or malformed security requirements, method
override markers, a nested `Fn::If` around a path item, a body or security
scheme map written as an intrinsic function, an `AWS_IAM` or `api_key` scheme that contains
one, and a body that declares both `swagger` and `openapi`. Existing
custom schemes remain subject to API Gateway's importer and invocation support; preserving
them does not implement another authentication protocol. The importer rejects multiple
authorizer or IAM requirements, including a defined custom authorizer alongside the
generated IAM default, with `BadRequestException`. API-key requirements can accompany
one authorizer or IAM requirement. The transform
preserves integration credentials, Lambda permissions and inline resource policies.
Preserving a policy does not enforce it: API Gateway's documented IAM identity-policy and
resource-policy evaluation limitation still applies. This support does not establish
`execute-api:Invoke` authorization-policy fidelity.

## Conditions

Template `Conditions` are evaluated before provisioning. A resource whose `Condition` evaluates to
**false is skipped** — it is never provisioned, does not appear in `DescribeStackResources`, and its
`Ref` / `Fn::GetAtt` are not required to resolve. This matches CloudFormation and is required for the
condition-heavy templates CDK and LZA emit.

The operands of `Fn::Equals` are resolved as resource property values are, so `Ref` to a parameter
or a pseudo parameter (`AWS::StackName` and `AWS::StackId` included) and `Fn::FindInMap`, the
functions CloudFormation supports there, work inside a condition. The dependency check a create runs
before the stack exists sees the `AWS::StackId` the stack is then given. An operand Floci cannot
resolve compares as an empty string. `AWS::NotificationARNs` is the empty list everywhere, as
Floci keeps no notification ARNs. `Fn::ImportValue` is not allowed in a condition, as in CloudFormation: `CreateStack`,
`UpdateStack` and `CreateChangeSet` refuse such a template, with the message `Template error: Cannot
use Fn::ImportValue in Conditions.` A SAM template's change set fails with that reason instead, and
so does the stack when CreateStack or UpdateStack executes it; an `AWS::Include` template fails when
it is executed.

## Custom Resources and the CDK Provider Framework

`AWS::CloudFormation::CustomResource` and `Custom::*` resources are backed by their `ServiceToken`
Lambda. floci supports two shapes:

- **Direct custom resources** — floci invokes the handler Lambda with the CloudFormation custom-resource
  event and waits for it to `PUT` its `SUCCESS`/`FAILED` result to the response URL. Inline `ZipFile`
  handlers get the `cfn-response` / `cfnresponse` module bundled in (see the Lambda row in
  [Supported Resource Types](#supported-resource-types)), so Solutions-style handlers work unmodified.
  The event's `StackId` is the id of the stack that contains the resource, the value
  `Ref AWS::StackId` returns and `DescribeStacks` reports, on `Create`, `Update` and `Delete` alike.
  In a nested stack it is the nested stack's own id.
- **CDK Provider framework** — when the `ServiceToken` points at a CDK `framework.onEvent` function,
  floci drives the asynchronous provider protocol: `onEvent` starts the work and `framework.isComplete`
  is polled (via Step Functions [`Retry`](step-functions.md)) until it reports done, at which point the
  ResponseURL callback fires. The wait is bounded by an async custom-resource timeout (3 minutes by
  default); a resource that never completes fails the stack rather than hanging.

The handler's `Data` keys resolve through `Fn::GetAtt`, except keys that begin with `__Floci`. Floci
reserves that prefix for its own resource state and ignores such keys.

A failed stack update rolls back a custom resource whose handler was sent an `Update`, as
CloudFormation does. This holds whether the handler applied the update and a later resource failed,
or the handler answered `FAILED` itself. The handler is sent a second `Update` under the same
`PhysicalResourceId`, with the old properties as `ResourceProperties` and the attempted ones as
`OldResourceProperties`, so it can undo the change. A handler that answers `FAILED` to that rollback
leaves the resource `UPDATE_FAILED` with its `Reason`, and the stack `UPDATE_ROLLBACK_FAILED`. The
old properties still become the resource's own, as on AWS: a later `DeleteStack` sends them in its
`Delete`, and the next update sends them as `OldResourceProperties`. An update whose handler returned
a new `PhysicalResourceId` replaced the resource. Its rollback sends no `Update`: the new id gets a
`Delete` with the attempted properties, and the resource goes back to the old id. CloudFormation sends
that `Delete` in its `UPDATE_ROLLBACK_COMPLETE_CLEANUP_IN_PROGRESS` phase. Floci has no rollback
cleanup phase and sends it during the rollback. As in that phase, a `Delete` the handler fails is
logged and does not fail the rollback. When the rollback's own `Update` returns a new id, the id it
was sent gets a `Delete` straight away for the same reason. An update that changes `ServiceToken`
reaches no handler: it fails with `Modifying service token is not allowed.`, as on AWS, and its
rollback sends nothing.

### Node.js handlers that require HTTPS on port 443

Some packaged Node.js handlers, including AWS's
[`cfn-response` implementation](https://docs.aws.amazon.com/AWSCloudFormation/latest/UserGuide/cfn-lambda-function-code-cfnresponsemodule.html#cfn-lambda-function-code-cfnresponsemodule-source),
send the callback with `https.request` on port 443 instead of following the scheme and port in
`ResponseURL`. With Floci's default HTTP-only configuration, these handlers can fail with
`ECONNREFUSED` on port 443 and leave the custom resource waiting until its response timeout.

Set `FLOCI_TLS_ENABLED=true` on Floci before starting the stack. Keep
`FLOCI_TLS_AWS_HTTPS_PORT=443` (the default) and ensure that listener starts successfully and is
reachable from the Lambda container at the hostname in `ResponseURL`. A failed port-443 bind is
logged as a warning; HTTPS on port 4566 alone does not satisfy a handler that hard-codes port 443.
Floci injects a CA bundle and `NODE_EXTRA_CA_CERTS` into launched Lambda containers, so keep
certificate verification enabled. See [TLS / HTTPS](../configuration/tls.md) for trust configuration
and function-level environment overrides.

Floci's callback URL still uses `http://<container-reachable-host>:<port>/cfn-response/<token>`;
enabling TLS does not turn it into an AWS presigned S3 URL. The bundled Node.js `cfn-response`
module for inline `ZipFile` code follows that URL's scheme and port and does not require TLS for
this callback. The HTTPS/443 requirement applies to handlers with the hard-coded behavior above,
not to every custom-resource handler.

## Failed update rollbacks

When an update fails, Floci rolls back the resources it changed. A resource whose rollback fails
is left `UPDATE_FAILED`, and the stack settles in `UPDATE_ROLLBACK_FAILED`. As on AWS, `UpdateStack`
and an `UPDATE` change set are then refused with `ValidationError` (`Stack:<arn> is in
UPDATE_ROLLBACK_FAILED state and can not be updated.`). Two ways out remain:

- `DeleteStack` deletes the stack and its resources, the `UPDATE_FAILED` ones included (see
  [Deletion Policies](#deletion-policies)).
- `ContinueUpdateRollback` runs the rollback again for the resources still owed one, starting with
  an `UPDATE_ROLLBACK_IN_PROGRESS` stack event whose reason is `User Initiated`. A retry re-runs
  the restore of each resource still owed one, including the delete of a replacement whose earlier
  delete failed; a resource whose restore cannot be redone fails again, leaving the stack in
  `UPDATE_ROLLBACK_FAILED`, and can be passed in `ResourcesToSkip`. `ResourcesToSkip` names
  `UPDATE_FAILED` resources to report as `UPDATE_COMPLETE` with the reason `Resource skipped
  during UpdateRollback`, without rolling them back; a skipped resource keeps the state the failed
  update left it in. Once every other resource rolled back, the stack reaches
  `UPDATE_ROLLBACK_COMPLETE` with the template, parameters and outputs from before the failed
  update, and takes updates again.

`ForceRollback=true` skips every `UPDATE_FAILED` resource of the stack, each reported as a
`ResourcesToSkip` skip is. When a forced rollback skipped a resource and completes, the stack's
`StackStatusReason` and the reason of its `UPDATE_ROLLBACK_COMPLETE` event are `One or more
resources were skipped during UpdateRollback. These resources were not rolled back and may have
drifted from the template. Run drift detection (DetectStackDrift) to review their current state
before your next update.` A rollback completed with `ResourcesToSkip` has no stack reason, as on
AWS.

As on AWS, a malformed `ContinueUpdateRollback` is refused with `ValidationError` before the stack
is looked up, listing every violation: a `StackName` that is missing, empty, or does not match
`[a-zA-Z][-a-zA-Z0-9]*|arn:[-a-zA-Z0-9:/._+]*`, and a `ResourcesToSkip` member that does not
match `[a-zA-Z0-9]+|[a-zA-Z][-a-zA-Z0-9]*\.[a-zA-Z0-9]+`. `ForceRollback=true` with a non-empty
`ResourcesToSkip` is then refused with `ForceRollback and ResourcesToSkip are mutually exclusive`,
a stack that does not exist with `Stack: <StackName> does not exist`, and a stack in any status
other than `UPDATE_ROLLBACK_FAILED` with `RollbackUpdatedStack cannot be called from current stack
status`. `ResourcesToSkip` is checked against the stack after the call is accepted: a skip that is
not a resource of the stack, or not `UPDATE_FAILED`, returns the stack to `UPDATE_ROLLBACK_FAILED`
with the reason, touching no resource, and a later call can still finish the rollback. `RoleARN`
and `ClientRequestToken` are accepted and have no effect.

Known difference: `ResourcesToSkip` does not support a nested stack's resource
(`NestedStackName.ResourceLogicalID`). When the prefix names a nested stack of the stack, the
rollback fails again with a reason saying Floci does not support it, where AWS would skip that
resource.

A stack that reached `UPDATE_ROLLBACK_FAILED` under an earlier Floci version kept no record of its
state before the update. `ContinueUpdateRollback` then retries every `UPDATE_FAILED` resource and
keeps the template the stack holds.

## Deleted Stacks

A deleted stack is kept for a short window afterwards rather than being forgotten at
`DELETE_COMPLETE`, because the client that asked for the delete goes on polling for it. Within that
window AWS's asymmetry is reproduced:

- `DescribeStacks` **by stack id** (the ARN) answers with the stack, its `DELETE_COMPLETE` status
  and its `DeletionTime`. `DescribeStackEvents` by stack id answers likewise.
- `DescribeStacks` **by name** returns `ValidationError`, as it does on AWS. The CDK CLI depends on
  the pair: it monitors a delete by stack id, and a stack id that stops resolving ends the rest of
  a `cdk destroy --all` run.
- `ListStacks` includes the stack as `DELETE_COMPLETE` with its `DeletionTime`, and a
  `StackStatusFilter` of `DELETE_COMPLETE` selects it. A name reused after a delete is listed
  twice, once per stack id.

Once the window passes, all of those behave as though the stack had never existed. AWS keeps a
deleted stack for 90 days; locally it only has to outlive the delete polling of the client that
asked for it, so the window defaults to 30 seconds (see [Configuration](#configuration)).

## Deletion Policies

A resource's [`DeletionPolicy`](https://docs.aws.amazon.com/AWSCloudFormation/latest/TemplateReference/aws-attribute-deletionpolicy.html)
attribute is honored on `DeleteStack`, on the rollback of a failed `CreateStack`, and when an
update removes the resource from the template, where it applies as on `DeleteStack`:

| Value | `DeleteStack` | Rollback of the create that made the resource |
|---|---|---|
| `Delete` (default) | deleted | deleted |
| `Retain` | kept | kept |
| `RetainExceptOnCreate` | kept | deleted |

A kept resource is reported as `DELETE_SKIPPED` in `DescribeStackEvents` and does not fail the
deletion — the stack still reaches `DELETE_COMPLETE` while the resource keeps existing. This also
lets a stack owning a non-empty S3 bucket be deleted, since the bucket is never touched.

Otherwise `DeleteStack` deletes each resource that has a physical id and is in one of these
statuses:

- `CREATE_COMPLETE`, `UPDATE_COMPLETE`
- `UPDATE_FAILED`, which a failed update rollback leaves on the resources it could not restore, so
  deleting a stack in `UPDATE_ROLLBACK_FAILED` removes them
- `DELETE_FAILED`, which a retried `DeleteStack` attempts again
- `CREATE_FAILED`, only when the stack created the backing entity, since a failed create can name
  an entity that existed before and belongs to someone else

Resources in any other status (`DELETE_COMPLETE`, `DELETE_SKIPPED`, an in-progress status) and
resources without a physical id are skipped. A resource that cannot be deleted leaves the stack in
`DELETE_FAILED`, named in the status reason.

Deviations from AWS to be aware of:

- `Snapshot` deletes the resource without taking a snapshot; floci has no snapshot support for the
  types AWS allows it on.
- Every resource defaults to `Delete`. AWS instead defaults `AWS::RDS::DBCluster`, and
  `AWS::RDS::DBInstance` without a `DBClusterIdentifier`, to `Snapshot`.
- Unrecognized values are treated as `Delete`.
- After `DeleteStack` completes, a retained resource's `DELETE_SKIPPED` record is only visible
  through `DescribeStackEvents` for the deleted stack, not `DescribeStackResources`.
- `UpdateReplacePolicy` is honored only by the resource types that delete a displaced resource once
  the update commits, such as an SSM parameter or a log stream; other types keep the displaced
  resource, or delete it during the update, whatever the policy. The `RetainExceptOnCreate` request
  parameter of `CreateStack` / `UpdateStack` is not implemented.

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
| `FLOCI_SERVICES_CLOUDFORMATION_DELETED_STACK_RETENTION_SECONDS` | `30` | How long a deleted stack stays describable by its stack id and listed as `DELETE_COMPLETE` (see [Deleted Stacks](#deleted-stacks)) |

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
