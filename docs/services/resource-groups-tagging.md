# Resource Groups Tagging API

**Protocol:** JSON 1.1
**Header:** `X-Amz-Target: ResourceGroupsTaggingAPI_20170126.<Action>`
**Endpoint prefix:** `tagging`

Floci emulates the AWS Resource Groups Tagging API for local tests that need
centralized tag discovery across AWS-shaped ARNs. `GetResources`, `GetTagKeys`
and `GetTagValues` list both the tags written through this API and the tags
each resource's own service holds, so a queue created with `CreateQueue` tags
is found without a separate `TagResources` call. Any other ARN is accepted as
well, and its tags are kept in the tagging store.

## Supported Operations

| Operation | Notes |
|-----------|-------|
| `TagResources` | Adds or updates tags for one or more resource ARNs |
| `UntagResources` | Removes tag keys from one or more resource ARNs |
| `GetResources` | Lists tagged resources, with ARN, tag, resource type, and pagination filters |
| `GetTagKeys` | Lists distinct tag keys for the current region |
| `GetTagValues` | Lists distinct values for a requested tag key in the current region |

`TagResources` and `UntagResources` return an empty `FailedResourcesMap` on
success. `GetResources`, `GetTagKeys`, and `GetTagValues` support pagination
tokens for multi-page responses; a token past the last result returns an empty
page.

## Resource discovery

Reads merge two sources for the request's region and account:

- The owning service's tags, for every service that
  [Resource Explorer 2 indexes](resource-explorer.md#discoverable-services),
  including SQS, Lambda, CloudWatch Logs, S3 and API Gateway (REST APIs,
  stages, API keys, usage plans and custom domain names). Only resources that
  carry at least one tag are listed.
- The tagging store, which holds tags written through `TagResources` that no
  owning service took, and the copies EventBridge and Glue keep there.

When both hold the same ARN, the result is one mapping, and for the same key
the owning service's value wins. CloudWatch Logs log group ARNs are returned
without the trailing `:*`.

`TagResources` forwards the tags to the owning service when that service
serves a REST `/tags/{arn}` endpoint, such as API Gateway, EventBridge
Scheduler, EKS and Pipes. If that service is also one of the indexed services
above for the resource type, the tags live only there, so `GetApiKey` shows a
tag set through `TagResources`. Otherwise, or when the owning service rejects
the ARN, the tags are kept in the tagging store. `UntagResources` removes the
keys from the owning service where it forwards, and always from the tagging
store.

### Known limitations

SQS, Lambda and CloudWatch Logs have no REST `/tags/{arn}` endpoint, so tags
set on their resources through `TagResources` stay in the tagging store. They
appear in `GetResources`, `GetTagKeys` and `GetTagValues`, but not in
`ListQueueTags`, Lambda `ListTags`, or CloudWatch Logs `ListTagsForResource`.
Tag those resources through their own service to see the tags in both places.

## Filtering

`GetResources` supports the common Resource Groups Tagging filters:

| Filter | Behavior |
|--------|----------|
| `ResourceARNList` | Restricts results to the requested ARNs |
| `TagFilters` | Matches resources that have each requested key; values are optional |
| `ResourceTypeFilters` | Matches `service` or `service:resourceType`, such as `lambda`, `lambda:function` or `ec2:instance` |
| `ResourcesPerPage` + `PaginationToken` | Pages through matching resource mappings |

The resource type in `service:resourceType` is the ARN resource part with one
leading `/` dropped, cut at the first `/` or `:`. So `lambda:function`,
`logs:log-group`, `ec2:instance` and `apigateway:apikeys` (or
`apigateway:/apikeys`) all match. SQS queue ARNs have no type segment, so
`sqs:queue` matches through the type SQS declares for its queues.

A resource is visible in the region its ARN names. When the ARN has no region,
such as an S3 bucket ARN, the owning service's region for the resource
decides; IAM users and roles, and region-less ARNs known only to the tagging
store, are visible in every region. A resource is visible to the account its
ARN names, and an ARN with no account, such as an API Gateway ARN, is visible
to every account.

## Configuration

| Variable | Default | Description |
|---|---|---|
| `FLOCI_SERVICES_TAGGING_ENABLED` | `true` | Enable or disable the Resource Groups Tagging API service |
| `FLOCI_STORAGE_SERVICES_TAGGING_MODE` | _(global storage mode)_ | Override the storage mode for the tagging store |
| `FLOCI_STORAGE_SERVICES_TAGGING_FLUSH_INTERVAL_MS` | `5000` | Flush interval for the tagging store in `hybrid` mode |

## Examples

```bash
export AWS_ENDPOINT_URL=http://localhost:4566
export AWS_DEFAULT_REGION=us-east-1
export AWS_ACCESS_KEY_ID=test
export AWS_SECRET_ACCESS_KEY=test

aws resourcegroupstaggingapi tag-resources \
  --resource-arn-list arn:aws:ec2:us-east-1:000000000000:instance/i-abc123 \
  --tags Environment=dev Team=platform

aws resourcegroupstaggingapi get-resources \
  --tag-filters Key=Environment,Values=dev

aws resourcegroupstaggingapi get-tag-keys

aws resourcegroupstaggingapi get-tag-values --key Environment

aws resourcegroupstaggingapi untag-resources \
  --resource-arn-list arn:aws:ec2:us-east-1:000000000000:instance/i-abc123 \
  --tag-keys Team
```

```python
import boto3

tagging = boto3.client(
    "resourcegroupstaggingapi",
    endpoint_url="http://localhost:4566",
    region_name="us-east-1",
)

arn = "arn:aws:lambda:us-east-1:000000000000:function:my-func"

tagging.tag_resources(
    ResourceARNList=[arn],
    Tags={"Environment": "dev", "Team": "platform"},
)

resources = tagging.get_resources(
    TagFilters=[{"Key": "Environment", "Values": ["dev"]}],
)
print(resources["ResourceTagMappingList"])
```

## Out of Scope

- Validation that a `TagResources` ARN names an existing resource: an ARN that
  no emulated service owns is accepted and kept in the tagging store.
- AWS Organizations tag policy enforcement.
