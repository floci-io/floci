# Resource Groups Tagging API

**Protocol:** JSON 1.1
**Header:** `X-Amz-Target: ResourceGroupsTaggingAPI_20170126.<Action>`
**Endpoint prefix:** `tagging`

Floci emulates the AWS Resource Groups Tagging API for local tests that need
centralized tag discovery across AWS-shaped ARNs. The service accepts arbitrary
resource ARNs, stores their tags in-process, and supports filtering by ARN, tag
filters, and resource type filters.

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
tokens for multi-page responses.

## Filtering

`GetResources` supports the common Resource Groups Tagging filters:

| Filter | Behavior |
|--------|----------|
| `ResourceARNList` | Restricts results to the requested ARNs |
| `TagFilters` | Matches resources that have each requested key; values are optional |
| `ResourceTypeFilters` | Matches `service` or `service:resourceType`, such as `lambda` or `ec2:instance` |
| `ResourcesPerPage` + `PaginationToken` | Pages through matching resource mappings |

Region filtering follows the ARN region when present. Global ARNs, such as S3
bucket ARNs, are visible across regions because their ARN region segment is
empty.

## Configuration

| Variable | Default | Description |
|---|---|---|
| `FLOCI_SERVICES_TAGGING_ENABLED` | `true` | Enable or disable the Resource Groups Tagging API service |

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

arn = "arn:aws:lambda:us-east-1:000000000000:function/my-func"

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

- Validation that an ARN already exists in another emulated service.
- AWS Organizations tag policy enforcement.
- Persistent tag storage across process restarts.
