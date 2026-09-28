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
success. An ARN the owning service rejected is listed there with its
`StatusCode`, `ErrorCode` and `ErrorMessage`, and the call still returns
HTTP 200. `GetResources`, `GetTagKeys`, and `GetTagValues` support pagination
tokens for multi-page responses; a token past the last result returns an empty
page.

## Resource discovery

Reads merge two sources for the request's region and account:

- The owning service's tags, for every service that
  [Resource Explorer 2 indexes](resource-explorer.md#discoverable-services),
  including SQS, Lambda, CloudWatch Logs, S3 and API Gateway (REST APIs,
  stages, API keys, usage plans and custom domain names). Only resources that
  carry at least one tag are listed.
- The tagging store, which holds tags written through `TagResources` that were
  not forwarded to an owning service, and the copies EventBridge and Glue keep
  there.

When both hold the same ARN, the result is one mapping, and for the same key
the owning service's value wins. CloudWatch Logs log group ARNs are returned
without the trailing `:*`.

`TagResources` forwards the tags to the owning service only when that service
registers a shared tag handler, such as API Gateway, and currently lists the
exact ARN as one of its resources. The tags then live only in the owning
service, so `GetApiKey` shows a tag set through `TagResources`, and nothing is
written to the tagging store. When the owning service rejects the tags, for
example a reserved `floci:` key on an API key, the ARN appears in
`FailedResourcesMap` and no tags are stored for it. Every other ARN, including
one the owning service does not list such as an API Gateway deployment ARN,
goes to the tagging store and the owning service is not called.
`UntagResources` routes the same way, and also removes the keys from the
tagging store for every ARN the owning service did not reject.

### Known limitations

No shared tag handler is registered for SQS, Lambda or CloudWatch Logs, so the
tagging API cannot forward to them, although Lambda serves its own REST
`/tags/{arn}` API. Tags set on their resources through `TagResources` stay in
the tagging store. They appear in `GetResources`, `GetTagKeys` and
`GetTagValues`, but not in `ListQueueTags`, Lambda `ListTags`, or CloudWatch
Logs `ListTagsForResource`.
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
decides, also for tags the tagging store holds for that ARN, so an
`eu-west-1` bucket tagged through `TagResources` is not listed from
`us-east-1`. IAM users and roles, and region-less ARNs known only to the
tagging store, are visible in every region. Every store is per account, so
results are scoped to the calling account. An ARN with an empty account
segment, such as an API Gateway ARN, only leaves the account out of the ARN;
the resource is still listed for the account that owns it.

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
  no emulated service lists is accepted and kept in the tagging store.
- AWS Organizations tag policy enforcement.
