# AWS Partitions

Floci serves every AWS partition, not only the commercial one. A partition is the second
segment of every ARN (`arn:aws-cn:...`), the DNS suffix of every endpoint
(`amazonaws.com.cn`), and the set of services and regions AWS publishes there. Floci reads
all of it from AWS's own partition metadata (botocore's `partitions.json` and
`endpoints.json`, vendored as `aws/partitions.json` and refreshed with `make aws-data-sync`).

| Partition | DNS suffix | Regions | Example |
|---|---|---|---|
| `aws` | `amazonaws.com` | 34 published (17 opt-in) | `us-east-1` |
| `aws-cn` | `amazonaws.com.cn` | `cn-north-1`, `cn-northwest-1` | `cn-north-1` |
| `aws-us-gov` | `amazonaws.com` | `us-gov-east-1`, `us-gov-west-1` | `us-gov-west-1` |
| `aws-iso` | `c2s.ic.gov` | `us-iso-east-1`, `us-iso-west-1` | `us-iso-east-1` |
| `aws-iso-b` | `sc2s.sgov.gov` | `us-isob-east-1`, `us-isob-west-1` | `us-isob-east-1` |
| `aws-iso-e` | `cloud.adc-e.uk` | `eu-isoe-west-1` | `eu-isoe-west-1` |
| `aws-iso-f` | `csp.hci.ic.gov` | `us-isof-east-1`, `us-isof-south-1` | `us-isof-south-1` |
| `aws-eusc` | `amazonaws.eu` | `eusc-de-east-1` | `eusc-de-east-1` |

## Which partition a deployment serves

The deployment's partition is derived from `FLOCI_DEFAULT_REGION`: `cn-north-1` means
`aws-cn`, `us-gov-west-1` means `aws-us-gov`, and so on. Set `FLOCI_PARTITIONS_ID`
(`floci.partitions.id`) to name it explicitly; startup refuses a value that is not a
published partition or that contradicts a recognised default region, and logs one line
naming the partition it settled on:

```
Partition: aws-cn (default-region cn-north-1, dns suffix amazonaws.com.cn)
```

A default region the vendored data does not know only warns. AWS launches regions faster
than the data is refreshed, and botocore's region-shape rules (`cn-*`, `us-gov-*`, ...)
still place it; anything else falls back to `aws`, which is what the AWS SDKs do too.

## Which partition a request belongs to

Each request's partition comes from the region in its SigV4 credential scope, exactly as
LocalStack and moto do it. A client signing `cn-north-1` is a China client even when Floci
was started with `us-east-1`, so one process can serve several partitions at once. Requests
without a credential, and background work (pollers, schedulers, startup hooks), use the
deployment's partition.

The scope region of a global service is its *signing* region, not a place to put regional
resources: China IAM always signs `cn-north-1` even from Ningxia, GovCloud IAM signs
`us-gov-west-1`. Floci uses it to pick the partition and nothing else. The
`<partition>-global` pseudo-regions the SDKs accept (`aws-global`, `aws-cn-global`, ...)
resolve to their partition as well; `aws-eusc` publishes none.

A scope region that no partition publishes or admits by its region pattern, such as
`polygondwanaland-west-1`, is refused with a 400: an S3-signed request gets S3's
`AuthorizationHeaderMalformed` ("the region '...' is wrong"), every other service gets
`InvalidSignatureException`. On AWS such a request never resolves a host; moto
(`MOTO_ALLOW_NONEXISTENT_REGION`) and LocalStack (`ALLOW_NONSTANDARD_REGIONS`) refuse it too.
The pattern rule is the AWS SDKs' own, so a region AWS launches after the vendored data was
refreshed (`eu-south-9`, say) is still served; it is looser than S3's `LocationConstraint` enum,
which stays published-only. Set `FLOCI_PARTITIONS_ALLOW_UNKNOWN_REGIONS=true`
(`floci.partitions.allow-unknown-regions`) to serve any label with its own namespace, as Floci
did before.

## What changes per partition

- **ARNs**: regional resources carry the partition of their region (`arn:aws-cn:sqs:cn-north-1:...`);
  regionless resources (IAM, S3, STS, CloudFront, Route 53, Organizations) carry the request's
  partition.
- **DescribeRegions**: the request's partition's regions, with the partition's endpoints.
  As on AWS, a commercial deployment lists the 17 regions that need no opt-in by default and
  all 34 with `AllRegions=true`, where opt-in regions report `not-opted-in`.
- **Hostname recognition**: every published region id, in every partition, is a region label
  in an S3 virtual-host or execute-api hostname.

## What does not change

- **Service principals** are `<service>.amazonaws.com` in every partition; that is the rule
  the AWS CDK applies today, and the older per-partition forms (`.amazonaws.com.cn`) are legacy.
- **XML namespaces** and the S3 canned-ACL group URIs (`http://acs.amazonaws.com/groups/...`)
  are identifiers, not hosts.
- **AWS managed policy ARNs** keep the literal `aws` account slot: `arn:aws-cn:iam::aws:policy/AdministratorAccess`.

## Partition-absent services

AWS publishes which services exist in each partition (CloudFront is not in GovCloud, IAM is
not in `aws-eusc`). On AWS a request for an absent service never reaches an API: the SDK
fails to resolve the host. Floci serves every enabled service in every partition; a strict
mode that mirrors the SDK failure is planned as an opt-in flag.

## Open questions

These values have no published source Floci can cite, so it does not guess them; they keep
the commercial value or Floci's own base host until sourced:

- the China CloudFront distribution domain suffix (only the API host is published);
- the console device-authorization client ids (`arn:aws:signin:::devtools/...`) outside the
  commercial partition;
- the STS web-identity audience outside the commercial partition;
- the Lambda function-URL host outside the commercial partition;
- whether AWS managed policy documents differ in content in China or GovCloud;
- the API Gateway regional hosted zone per region;
- S3 `LocationConstraint` enum values and Route 53 hosted zones for the ISO and EUSC regions.

## Related

- [Environment Variables](environment-variables.md): `FLOCI_DEFAULT_REGION`, `FLOCI_PARTITIONS_ID`
- [Multi-Account Isolation](multi-account.md): the account half of the credential scope
