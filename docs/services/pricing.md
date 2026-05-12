# Pricing (AWS Price List Service)

**Protocol:** JSON 1.1
**Header:** `X-Amz-Target: AWSPriceListService.<Action>`
**Endpoint prefix:** `api.pricing`

Floci emulates the AWS Price List Service backed by a bundled static snapshot.
Responses match the real AWS wire format so AWS SDK and CLI clients accept the
reply without modification. The bundled snapshot covers a minimal, representative
set of services and regions; for broader coverage, point Floci at your own
snapshot with `FLOCI_SERVICES_PRICING_SNAPSHOT_PATH`.

## Supported Operations

| Operation | Notes |
|-----------|-------|
| `DescribeServices` | Lists bundled services and their queryable attribute names |
| `GetAttributeValues` | Returns the set of values a given attribute can take |
| `GetProducts` | Returns `PriceList` as an array of JSON-encoded product-offer strings (matches AWS format) |
| `ListPriceLists` | Lists available price-list ARNs filtered by service, currency, and optional region |
| `GetPriceListFileUrl` | Returns a stub HTTPS URL; useful for code paths that validate URL presence |

Pagination is supported on all list operations via `NextToken` + `MaxResults`.

## Bundled Snapshot

The default snapshot on the classpath covers:

| ServiceCode | Regions | Notes |
|-------------|---------|-------|
| `AmazonEC2` | `us-east-1` (Linux/Shared tenancy, 3 instance types) | `t3.micro`, `m5.large`, `c5.large` |
| `AmazonS3` | `us-east-1` (Standard storage) | |
| `AWSLambda` | `us-east-1` (Requests) | |

The snapshot is intentionally minimal — enough to exercise SDK parsing and
filter logic — not a comprehensive price database.

## Configuration

| Variable | Default | Description |
|---|---|---|
| `FLOCI_SERVICES_PRICING_ENABLED` | `true` | Enable or disable the service |
| `FLOCI_SERVICES_PRICING_SNAPSHOT_PATH` | *(unset)* | Filesystem directory overriding the bundled snapshot |

### Snapshot directory layout

When `FLOCI_SERVICES_PRICING_SNAPSHOT_PATH` is set, Floci reads files in this
layout (falling back to the classpath entry for any file that does not exist):

```
<path>/
  services.json                              # [ { "ServiceCode": "...", "AttributeNames": [...] } ]
  attribute-values/<ServiceCode>/<Attr>.json # [ { "Value": "..." } ]
  products/<ServiceCode>/<Region>.json       # [ { "product": {...}, "terms": {...}, ... } ]
  price-lists/<ServiceCode>.json             # [ { "PriceListArn": "...", "RegionCode": "...", ... } ]
```

Each product entry is stored as a JSON object; Floci re-serializes it into the
array-of-JSON-strings shape AWS returns. Drop in a full snapshot generated from
the [AWS Price List Bulk API](https://docs.aws.amazon.com/awsaccountbilling/latest/aboutv2/price-changes.html)
when the bundled fixtures are insufficient.

## Examples

```bash
export AWS_ENDPOINT_URL=http://localhost:4566
export AWS_DEFAULT_REGION=us-east-1
export AWS_ACCESS_KEY_ID=test
export AWS_SECRET_ACCESS_KEY=test

aws pricing describe-services --service-code AmazonEC2

aws pricing get-attribute-values \
  --service-code AmazonEC2 --attribute-name instanceType

aws pricing get-products \
  --service-code AmazonEC2 \
  --filters 'Type=TERM_MATCH,Field=instanceType,Value=t3.micro' \
            'Type=TERM_MATCH,Field=regionCode,Value=us-east-1'

aws pricing list-price-lists \
  --service-code AmazonEC2 \
  --effective-date 2026-01-01T00:00:00Z \
  --currency-code USD
```

```python
import boto3

client = boto3.client(
    "pricing",
    endpoint_url="http://localhost:4566",
    region_name="us-east-1",
)

resp = client.get_products(
    ServiceCode="AmazonEC2",
    Filters=[
        {"Type": "TERM_MATCH", "Field": "instanceType", "Value": "t3.micro"},
        {"Type": "TERM_MATCH", "Field": "regionCode",   "Value": "us-east-1"},
    ],
)
for item in resp["PriceList"]:
    # AWS returns PriceList as an array of JSON strings; parse each separately.
    import json
    print(json.loads(item)["product"]["sku"])
```

## Out of Scope

- Bulk download of full regional price lists (`GetPriceListFileUrl` returns a
  stub URL; the file is not served).
- Volume discounts, Savings Plans, or Reserved Instance pricing terms beyond
  what the bundled snapshot declares.
- Automatic refresh of the snapshot from upstream AWS.
