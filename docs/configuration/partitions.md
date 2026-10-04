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
- **Hostnames**: AWS-shaped hosts in responses (an HTTP API's `ApiEndpoint`, a bucket's
  `RegionalDomainName` and `WebsiteURL`, Cognito and EKS OIDC issuers, EC2 public DNS names)
  use the DNS suffix of their region's partition, so a `cn-north-1` API answers
  `<id>.execute-api.cn-north-1.amazonaws.com.cn`. `DualStackDomainName` is only returned where
  S3 publishes a dual-stack endpoint.
- **Hostname recognition**: every published region id and DNS suffix, in every partition, is
  recognised in an S3 virtual-host, execute-api, ECR image or CloudFront origin hostname.
- **S3 CreateBucket**: as on AWS, the `us-east-1` endpoint takes any `LocationConstraint` but
  its own, and every other regional endpoint requires a constraint naming exactly its region
  (`IllegalLocationConstraintException` otherwise). A China or GovCloud client must send the
  constraint, which the AWS SDKs do; `GetBucketLocation` still answers an empty constraint only
  for `us-east-1`, in every partition.
- **Hosted zones and console URLs**: load balancer and S3 website hosted zone ids come from a
  per-region table (`aws/region-facts.json`, generated from the Terraform provider and the CDK,
  which transcribe the AWS General Reference); Network load balancers have their own zone,
  distinct from the Application/Classic one. The CloudFront hosted zone is published for `aws`
  and `aws-cn`; the SAML sign-on URL for five partitions. The ISO and EUSC regions have no load
  balancer hosted zones, and only the two ISO-F regions have an S3 website zone; where a table has
  no row the field is omitted rather than guessed. An `EDGE` API Gateway custom domain is refused
  outside the commercial partition (`BadRequestException`): GovCloud and ISO have no CloudFront,
  and China has no edge-optimized API Gateway
  ([China API Gateway](https://docs.amazonaws.cn/en_us/aws/latest/userguide/api-gateway.html)).
- **VPC endpoint service names**: interface endpoints (as `DescribeVpcEndpointServices` lists them)
  are `com.amazonaws.<region>.<service>` everywhere, except the (region, service) pairs the CDK
  lists for China, ISO and EUSC, which reverse the DNS suffix (`cn.com.amazonaws.cn-north-1.lambda`).
  GovCloud keeps `com.amazonaws`. Gateway endpoints and their AWS-managed prefix lists (S3,
  DynamoDB) are `com.amazonaws.<region>.<service>` in every partition. S3 offers both kinds: where
  the two names agree it is one service carrying both types, and in China it is listed twice, the
  gateway `com.amazonaws.cn-north-1.s3` and the interface `cn.com.amazonaws.cn-north-1.s3`.
- **Lambda runtime images** pull from ECR Public (`public.ecr.aws`), which exists only in the
  commercial partition; point `FLOCI_SERVICES_LAMBDA_ECR_BASE_URI` at a mirror elsewhere.
- **WAF `CLOUDFRONT` scope**: available only where CloudFront exists (`aws`, `aws-cn`), and its
  resources live in the partition's implicit global region (`cn-northwest-1` in China).

## What does not change

- **Service principals** are `<service>.amazonaws.com` in every partition; that is the rule
  the AWS CDK applies today, and everything Floci emits (generated trust policies, CloudTrail's
  `eventSource`, service-linked role paths) uses it. The older per-partition forms the CDK
  retired (`elasticmapreduce.amazonaws.com.cn`, `logs.<region>.amazonaws.com.cn`,
  `config.c2s.ic.gov`) are still accepted wherever Floci matches a principal against a policy,
  such as a role's trust policy, so a policy written in either form works; matching stays exact
  and case-sensitive. `CreateServiceLinkedRole` given a legacy form creates the same role as the
  universal one: its name, path and trust policy all use `<service>.amazonaws.com`.
- **XML namespaces** and the S3 canned-ACL group URIs (`http://acs.amazonaws.com/groups/...`)
  are identifiers, not hosts.
- **AWS managed policy ARNs** keep the literal `aws` account slot: `arn:aws-cn:iam::aws:policy/AdministratorAccess`.
  The catalog Floci bundles is the commercial one; every other partition's is derived from it on
  first use, with the partition in every ARN (what AWS's own SAM translator does) and the
  partition's DNS suffix in the region-bearing `kms:ViaService` hosts (`s3.*.amazonaws.com.cn`).
  Service principals and service-linked role paths are the same in every partition and are left
  alone. Whether AWS's China or GovCloud documents differ in content beyond that is an open
  question below.

## Partition-absent services

AWS publishes which services exist in each partition (CloudFront is not in GovCloud, IAM is
not in `aws-eusc`). On AWS a request for an absent service never reaches an API: the SDK
fails to resolve the host and reports an `UnknownHostException`. Floci serves every enabled
service in every partition by default.

Set `FLOCI_PARTITIONS_STRICT=true` (`floci.partitions.strict`) to mirror AWS instead. A request
whose SigV4 signing name the request's partition does not publish is refused with a 404
`UnknownOperationException` whose message names the service and the partition; Floci cannot
fail DNS, so this is the same shape the unknown-service guard uses. The check reads the vendored
service list, matching the signing name directly or through the endpoint prefixes it covers
(`ecr` signs for `api.ecr`, `bedrock` for `bedrock-runtime`), so a China ECR client is served
while a GovCloud CloudFront client is refused. Only a service the data lists in some other
partition is refused: `endpoints.json` omits the newer services that ship an endpoint ruleset
alone (FIS, MWAA, S3 Tables), and those are served everywhere.

STS is regionalized everywhere and its global host `sts.amazonaws.com` exists only in `aws`, so
IAM's `GetAccountSummary` reports `GlobalEndpointTokenVersion` only there.

An IAM resource (user, group, role, policy, instance profile, OIDC provider) is created in the
request's partition and keeps it: a rename signed for another partition does not move it. On AWS
an account belongs to one partition, but one Floci process serves them all, so anything derived
from a stored resource follows that resource. A role's sessions (`assumed-role`) and the OIDC
provider its web-identity trust policy names take the stored role's partition, whichever partition
the request's `RoleArn` names, so `AssumeRole` and a later `GetCallerIdentity` agree whatever region
each call is signed for. The credential report is kept per partition, so its root row always
names the caller's. ARNs with nothing stored
behind them, the `root` fallback of `GetCallerIdentity` and `federated-user`, take the request's
partition.

## How it is tested

Every partition-dependent rule has unit and integration tests over all eight partitions. On top
of that, the nightly Partition Compatibility workflow runs the Java SDK compatibility suite
against a Floci deployed with `FLOCI_DEFAULT_REGION=cn-north-1`, with the SDK clients signing for
`cn-north-1`, so the SDK's own China partition handling has to round-trip end to end. Its known
failures are listed in `.github/ci/compat-partition-allowlist-cn-north-1.txt`; the list only
shrinks.

## Open questions

These values have no published source Floci can cite, so it does not guess them; they keep
the commercial value or Floci's own base host until sourced:

- the China CloudFront distribution domain suffix (only the API host is published);
- the console device-authorization client ids (`arn:aws:signin:::devtools/...`) outside the
  commercial partition;
- the STS web-identity audience outside the commercial partition;
- the EKS Pod Identity token audience (`pods.eks.amazonaws.com`) outside the commercial partition;
- the Lambda function-URL host outside the commercial partition;
- whether AWS managed policy documents differ in content in China or GovCloud;
- the API Gateway regional hosted zone per region;
- S3 `LocationConstraint` enum values and Route 53 hosted zones for the ISO and EUSC regions;
- the SAML sign-on URL in `aws-iso-e`, `aws-iso-f` and `aws-eusc`, where assertions keep being
  checked against the commercial `https://signin.aws.amazon.com/saml`;
- the exact error AWS WAF returns for the `CLOUDFRONT` scope in a partition without CloudFront
  (GovCloud, the ISO partitions): Floci refuses it with `WAFInvalidParameterException` and its own
  message.

## Related

- [Environment Variables](environment-variables.md): `FLOCI_DEFAULT_REGION`, `FLOCI_PARTITIONS_ID`,
  `FLOCI_PARTITIONS_STRICT`, `FLOCI_PARTITIONS_ALLOW_UNKNOWN_REGIONS`
- [Multi-Account Isolation](multi-account.md): the account half of the credential scope
