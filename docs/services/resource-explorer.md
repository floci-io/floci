# Resource Explorer 2

AWS Resource Explorer 2 (`resource-explorer-2`) makes the resources other floci services hold
searchable from one place: `Search` and `ListResources` answer the Resource Explorer query
language across every enabled service, and index and view management works as it does in AWS.

The API is REST JSON — operation names are URI paths (`POST /Search`) — and floci serves it on
the shared port 4566.

## Supported actions

<!-- floci:actions:start -->
| Action | Description |
| --- | --- |
| `ListResources` | Search with filters only; pages through every match |
| `Search` | Search with filters and free-form keywords; returns at most the first 1000 matches |
| `ListSupportedResourceTypes` | List the resource types the enabled services expose |
| `CreateIndex` | Turn Resource Explorer on in a Region |
| `GetIndex` | Details of the Region's index |
| `DeleteIndex` | Turn Resource Explorer off in a Region |
| `ListIndexes` | List indexes, optionally by type or Region |
| `UpdateIndexType` | Promote a local index to aggregator, or demote it |
| `CreateView` | Create a view, optionally with a filter and tag data |
| `GetView` | A view's definition and tags |
| `DeleteView` | Delete a view, clearing it as a default if it was one |
| `UpdateView` | Replace a view's filter or included properties |
| `ListViews` | ARNs of the calling Region's views |
| `BatchGetView` | Several views at once, reporting the ones that are missing |
| `AssociateDefaultView` | Make a view the Region's default |
| `DisassociateDefaultView` | Leave the Region without a default view |
| `GetDefaultView` | The Region's default view ARN, if it has one |
| `GetAccountLevelServiceConfiguration` | Organization status — always `DISABLED`, as no organization is modeled |
| `ListIndexesForMembers` | Member accounts' indexes — this account's own, or empty |
| `ListManagedViews` | AWS-managed views — always empty, as none are modeled |
| `GetManagedView` | Always reports the managed view as not found |
| `ListServiceViews` | Service-owned views — always empty, as none are modeled |
| `GetServiceView` | Always reports the service view as not found |
| `ListServiceIndexes` | Indexes across Regions, in the service-index shape |
| `GetServiceIndex` | The Region's index ARN and type |
| `ListStreamingAccessForServices` | Services granted streaming access — always empty, as none are modeled |
| `CreateResourceExplorerSetup` | Create indexes and views across several Regions in one task |
| `DeleteResourceExplorerSetup` | Remove indexes and views from named Regions, or from all of them |
| `GetResourceExplorerSetup` | A setup task's per-Region index and view status |
| `ListTagsForResource` | An index's or view's tags |
| `TagResource` | Add or overwrite tags on an index or view |
| `UntagResource` | Remove tags by key from an index or view |
<!-- floci:actions:end -->

## Indexes and views

An index turns Resource Explorer on in a Region; a view decides which resources a search can
return and whether tag data comes back with them. floci auto-provisions an `AGGREGATOR` index
and a `default-view` in the default Region at startup, so `Search` works with no setup. Other
Regions need `CreateIndex` (or `CreateResourceExplorerSetup`) first.

`CreateResourceExplorerSetup` configures several Regions in one call — an index and a view per
Region, optionally promoting one Region to aggregator — and returns a `TaskId` that
`GetResourceExplorerSetup` reads back. Each Region's index and view step is recorded
independently, so a Region that fails reports its AWS error code through that Region's
`ErrorDetails` instead of abandoning the rest of the task.

## Making a service discoverable

A service exposes its resources by implementing `core/resource/ResourceProvider`:

```java
public interface ResourceProvider {
    List<ExplorerResource> getResources();
    Set<SupportedResourceType> getSupportedResourceTypes();
}
```

`ResourceExplorer2Service` collects every implementation through CDI. `LambdaService` is the
shortest example; `Ec2Service` is the largest, mapping eight resource types.

There is no index behind this: every `Search` and `ListResources` calls each provider's
`getResources()`, so results always match live service state. A provider must therefore be
cheap and must tolerate a resource that is mid-creation — skip a null ARN, and treat a null tag
map as empty.

### Discoverable services

| Service | Resource types |
|---|---|
| ACM | `acm:certificate` |
| Amazon MQ | `mq:broker` |
| CloudFormation | `cloudformation:stack` |
| CloudWatch Logs | `logs:log-group` |
| Cognito | `cognito-idp:userpool` |
| DynamoDB | `dynamodb:table` |
| EC2 | `ec2:instance`, `ec2:vpc`, `ec2:subnet`, `ec2:security-group`, `ec2:volume`, `ec2:internet-gateway`, `ec2:natgateway`, `ec2:route-table` |
| ECR | `ecr:repository` |
| ECS | `ecs:cluster`, `ecs:service` |
| EKS | `eks:cluster` |
| ElastiCache | `elasticache:cluster` |
| ELB v2 | `elasticloadbalancing:loadbalancer`, `elasticloadbalancing:targetgroup` |
| EventBridge | `events:event-bus`, `events:rule` |
| Firehose | `firehose:deliverystream` |
| IAM | `iam:user`, `iam:role` |
| Kinesis | `kinesis:stream` |
| KMS | `kms:key` |
| Lambda | `lambda:function` |
| Lightsail | `lightsail:instance`, `lightsail:database`, `lightsail:bucket` |
| MSK | `kafka:cluster` |
| OpenSearch | `es:domain` |
| Pipes | `pipes:pipe` |
| RDS | `rds:db` |
| RUM | `rum:appmonitor` |
| S3 | `s3:bucket` |
| Secrets Manager | `secretsmanager:secret` |
| SNS | `sns:topic` |
| SQS | `sqs:queue` |
| SSM | `ssm:parameter` |
| Step Functions | `states:statemachine` |

API Gateway and Route 53 are not discoverable yet, each for its own reason: `RestApi` carries no
Region, and `Route53Service` resolves no account id — a hosted zone's ARN has neither. Both are
waiting on a field, not on new query support.

## Query syntax

The filter grammar is implemented in full: the `accountid:`, `application:`, `id:`, `region:`,
`resourcetype:`, `resourcetype.supports:`, `service:`, `tag:`, `tag.key:` and `tag.value:`
filters, the `tag:all` and `tag:none` special cases, comma-OR within a filter, `-` negation,
trailing `*` prefix matching, quoted phrases, and `\` escaping.

`ListResources` accepts only filters — free-form text is rejected with a `ValidationException`,
as AWS rejects it. `Search` accepts both.

Tag filters (`tag:`, `tag.key:`, `tag.value:` and `application:`, which reads the
`awsApplication` tag) require a view whose `IncludedProperties` name `tags`. Against a view
without it the query is rejected rather than evaluated on data the view may not expose, and
results carry no tag data.

## Configuration

| Variable | Default | Description |
|---|---|---|
| `FLOCI_SERVICES_RESOURCEEXPLORER2_ENABLED` | `true` | Enable or disable Resource Explorer 2 |
| `FLOCI_STORAGE_SERVICES_RESOURCEEXPLORER2_MODE` | _(global storage mode)_ | Override the storage mode for indexes, views, and setup tasks |

## Where floci differs from AWS

- **Free-form keywords select rather than rank.** AWS reads `ec2 billing` as `ec2 OR billing`
  and never excludes a resource for missing a keyword — it ranks it lower and still returns it,
  so a keyword-only search in a live account returns everything. floci keeps the `OR` reading
  but returns only matches, since it has no relevance model and an unfiltered result set is not
  a useful answer locally. Filters, which AWS documents as deterministic, match exactly.
- **An index is `ACTIVE` immediately.** `CreateIndex` reports `CREATING` on its own response, as
  the API contract requires, but floci is synchronous and there is no provisioning window to
  fake, so the very next `GetIndex` reports `ACTIVE`.
- **Setup tasks finish before the call returns.** `CreateResourceExplorerSetup` and
  `DeleteResourceExplorerSetup` run synchronously, so a task's per-Region status is already
  terminal — `SUCCEEDED` or `FAILED` — the first time `GetResourceExplorerSetup` reads it.
- **A stale `NextToken` returns an empty page.** A token pointing past a result set that shrank
  between calls is answered with an empty page rather than an error, which is how AWS handles a
  replayed token.
- **Paginated results are ordered by ARN.** AWS treats `NextToken` as opaque and documents no
  result order — `Search` is relevance-ranked. floci's token is an offset into a list rebuilt on
  every call from provider and storage iteration, neither of which is ordered, so the results are
  sorted by ARN before the page is cut. Without that total order the second page would index into
  a differently arranged list and silently drop or repeat resources. The same ordering applies to
  `ListIndexes`, `ListViews` and `ListSupportedResourceTypes`.
- **The default view is stored, not inferred.** The per-Region default is persisted on its own
  rather than derived from a view's name, so `DisassociateDefaultView` survives a restart.
- **IAM tags are indexed.** AWS does not index tags attached to IAM users and roles; floci does,
  because hiding them would make locally tagged roles unfindable for no benefit.
- **Organization and trusted-service surfaces are empty.** floci models neither AWS
  Organizations nor trusted-service integrations, so `ListManagedViews`, `ListServiceViews` and
  `ListStreamingAccessForServices` answer with empty lists, `GetManagedView` and `GetServiceView`
  report `ResourceNotFoundException`, `GetAccountLevelServiceConfiguration` reports
  `AWSServiceAccessStatus: DISABLED`, and `ListIndexesForMembers` returns this account's indexes
  when its id is among those asked for and nothing otherwise.

## Routing note

Resource Explorer 2 and S3 Vectors are both REST-JSON services rooted at `/`, and both declare
`/CreateIndex`, `/GetIndex`, `/ListIndexes` and `/DeleteIndex`. JAX-RS cannot route those by
path, so `ResourceExplorer2PathRewriteFilter` disambiguates on the SigV4 credential scope — the
same signal `AwsQueryController` routes Query services by — and rewrites the path for Resource
Explorer 2 callers. Requests that carry no `Authorization` header reach S3 Vectors; every AWS
SDK and the CLI sign their requests, so this only affects hand-rolled `curl` calls.
