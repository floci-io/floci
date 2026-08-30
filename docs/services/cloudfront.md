# CloudFront

CloudFront management-plane and local content-delivery emulation. Supports distribution lifecycle,
cache policies, origin request policies, response headers policies, origin access controls, origin
access identities, public keys, trusted key groups, CloudFront Functions, invalidations, tagging, and
GET/HEAD/OPTIONS delivery from S3 or custom origins.

**Protocol:** REST XML  
**API version:** `2020-05-31`  
**Endpoint prefix:** `cloudfront`  
**Namespace:** `http://cloudfront.amazonaws.com/doc/2020-05-31/`  
**Global service** — ARNs contain no region segment.

## Supported Operations

### Distributions

| Operation | Method | Path |
|---|---|---|
| `CreateDistribution` | POST | `/2020-05-31/distribution` |
| `CreateDistributionWithTags` | POST | `/2020-05-31/distribution?WithTags` |
| `GetDistribution` | GET | `/2020-05-31/distribution/{Id}` |
| `GetDistributionConfig` | GET | `/2020-05-31/distribution/{Id}/config` |
| `UpdateDistribution` | PUT | `/2020-05-31/distribution/{Id}/config` |
| `DeleteDistribution` | DELETE | `/2020-05-31/distribution/{Id}` |
| `ListDistributions` | GET | `/2020-05-31/distribution` |
| `AssociateAlias` | PUT | `/2020-05-31/distribution/{TargetDistributionId}/associate-alias` |

### Invalidations

| Operation | Method | Path |
|---|---|---|
| `CreateInvalidation` | POST | `/2020-05-31/distribution/{Id}/invalidation` |
| `GetInvalidation` | GET | `/2020-05-31/distribution/{Id}/invalidation/{InvId}` |
| `ListInvalidations` | GET | `/2020-05-31/distribution/{Id}/invalidation` |

### Cache Policies

| Operation | Method | Path |
|---|---|---|
| `CreateCachePolicy` | POST | `/2020-05-31/cache-policy` |
| `GetCachePolicy` | GET | `/2020-05-31/cache-policy/{Id}` |
| `GetCachePolicyConfig` | GET | `/2020-05-31/cache-policy/{Id}/config` |
| `UpdateCachePolicy` | PUT | `/2020-05-31/cache-policy/{Id}` |
| `DeleteCachePolicy` | DELETE | `/2020-05-31/cache-policy/{Id}` |
| `ListCachePolicies` | GET | `/2020-05-31/cache-policy` |

### Origin Request Policies

| Operation | Method | Path |
|---|---|---|
| `CreateOriginRequestPolicy` | POST | `/2020-05-31/origin-request-policy` |
| `GetOriginRequestPolicy` | GET | `/2020-05-31/origin-request-policy/{Id}` |
| `GetOriginRequestPolicyConfig` | GET | `/2020-05-31/origin-request-policy/{Id}/config` |
| `UpdateOriginRequestPolicy` | PUT | `/2020-05-31/origin-request-policy/{Id}` |
| `DeleteOriginRequestPolicy` | DELETE | `/2020-05-31/origin-request-policy/{Id}` |
| `ListOriginRequestPolicies` | GET | `/2020-05-31/origin-request-policy` |

### Response Headers Policies

| Operation | Method | Path |
|---|---|---|
| `CreateResponseHeadersPolicy` | POST | `/2020-05-31/response-headers-policy` |
| `GetResponseHeadersPolicy` | GET | `/2020-05-31/response-headers-policy/{Id}` |
| `GetResponseHeadersPolicyConfig` | GET | `/2020-05-31/response-headers-policy/{Id}/config` |
| `UpdateResponseHeadersPolicy` | PUT | `/2020-05-31/response-headers-policy/{Id}` |
| `DeleteResponseHeadersPolicy` | DELETE | `/2020-05-31/response-headers-policy/{Id}` |
| `ListResponseHeadersPolicies` | GET | `/2020-05-31/response-headers-policy` |

### Origin Access Control (OAC)

| Operation | Method | Path |
|---|---|---|
| `CreateOriginAccessControl` | POST | `/2020-05-31/origin-access-control` |
| `GetOriginAccessControl` | GET | `/2020-05-31/origin-access-control/{Id}` |
| `GetOriginAccessControlConfig` | GET | `/2020-05-31/origin-access-control/{Id}/config` |
| `UpdateOriginAccessControl` | PUT | `/2020-05-31/origin-access-control/{Id}` |
| `DeleteOriginAccessControl` | DELETE | `/2020-05-31/origin-access-control/{Id}` |
| `ListOriginAccessControls` | GET | `/2020-05-31/origin-access-control` |

### Origin Access Identity (OAI — legacy)

| Operation | Method | Path |
|---|---|---|
| `CreateCloudFrontOriginAccessIdentity` | POST | `/2020-05-31/origin-access-identity/cloudfront` |
| `GetCloudFrontOriginAccessIdentity` | GET | `/2020-05-31/origin-access-identity/cloudfront/{Id}` |
| `GetCloudFrontOriginAccessIdentityConfig` | GET | `/2020-05-31/origin-access-identity/cloudfront/{Id}/config` |
| `UpdateCloudFrontOriginAccessIdentity` | PUT | `/2020-05-31/origin-access-identity/cloudfront/{Id}/config` |
| `DeleteCloudFrontOriginAccessIdentity` | DELETE | `/2020-05-31/origin-access-identity/cloudfront/{Id}` |
| `ListCloudFrontOriginAccessIdentities` | GET | `/2020-05-31/origin-access-identity/cloudfront` |

### CloudFront Functions

| Operation | Method | Path |
|---|---|---|
| `CreateFunction` | POST | `/2020-05-31/function` |
| `GetFunction` | GET | `/2020-05-31/function/{Name}` |
| `DescribeFunction` | GET | `/2020-05-31/function/{Name}/describe` |
| `UpdateFunction` | PUT | `/2020-05-31/function/{Name}` |
| `PublishFunction` | POST | `/2020-05-31/function/{Name}/publish` |
| `DeleteFunction` | DELETE | `/2020-05-31/function/{Name}` |
| `ListFunctions` | GET | `/2020-05-31/function` |

### Public Keys and Key Groups

| Operation | Method | Path |
|---|---|---|
| `CreatePublicKey` | POST | `/2020-05-31/public-key` |
| `GetPublicKey` | GET | `/2020-05-31/public-key/{Id}` |
| `GetPublicKeyConfig` | GET | `/2020-05-31/public-key/{Id}/config` |
| `UpdatePublicKey` | PUT | `/2020-05-31/public-key/{Id}/config` |
| `DeletePublicKey` | DELETE | `/2020-05-31/public-key/{Id}` |
| `ListPublicKeys` | GET | `/2020-05-31/public-key` |
| `CreateKeyGroup` | POST | `/2020-05-31/key-group` |
| `GetKeyGroup` | GET | `/2020-05-31/key-group/{Id}` |
| `GetKeyGroupConfig` | GET | `/2020-05-31/key-group/{Id}/config` |
| `UpdateKeyGroup` | PUT | `/2020-05-31/key-group/{Id}` |
| `DeleteKeyGroup` | DELETE | `/2020-05-31/key-group/{Id}` |
| `ListKeyGroups` | GET | `/2020-05-31/key-group` |

### Tagging

| Operation | Method | Path |
|---|---|---|
| `ListTagsForResource` | GET | `/2020-05-31/tagging?Resource={arn}` |
| `TagResource` | POST | `/2020-05-31/tagging?Operation=Tag&Resource={arn}` |
| `UntagResource` | POST | `/2020-05-31/tagging?Operation=Untag&Resource={arn}` |

## Behavior

- All distributions are immediately set to `Deployed` state (no async `InProgress` delay).
- Distribution IDs are 14 uppercase alphanumeric characters starting with `E` (e.g. `E1Z2X3C4V5B6N7`).
- Distribution domain names follow the pattern `{id}.cloudfront.net`.
- ARNs are global — no region segment: `arn:aws:cloudfront::{accountId}:distribution/{id}`.
- Invalidations are immediately marked `Completed`.
- `DeleteDistribution` returns `DistributionNotDisabled` (409) if `Enabled` is `true` in the config.
- All mutating operations (`PUT`, `DELETE`) require an `If-Match` header containing the current
  `ETag`. Response headers policies, public keys, and key groups distinguish a missing header
  (`InvalidIfMatchVersion`, 400) from a stale `ETag` (`PreconditionFailed`, 412). Other CloudFront
  resources currently return `InvalidIfMatchVersion` (400) for either case.
- All `GET` and `POST` (create) responses include an `ETag` response header.
- List operations emit the payload root declared by the CloudFront REST XML model (for example,
  `ListDistributions` returns `<DistributionList>`), with list contents represented by
  `<Quantity>N</Quantity><Items>...</Items>`.
- OAI `CallerReference` uniqueness is enforced — duplicate `CallerReference` values return `CloudFrontOriginAccessIdentityAlreadyExists` (409).
- CNAME aliases are globally unique. `AssociateAlias` atomically transfers an alias from its current
  owner to the target distribution. Exact aliases take precedence over the most-specific matching
  wildcard alias.
- Viewer GET/HEAD requests, and OPTIONS requests allowed by the matched cache behavior, addressed to
  an enabled distribution's generated domain or alias are routed to the matching S3 or custom
  origin. Origin forwarding preserves the raw path; custom-origin redirects are not followed.
- Origin custom headers are persisted through the CloudFront API and CloudFormation. They replace
  same-named viewer headers on custom-origin GET/HEAD/OPTIONS requests. For in-process S3 origins, a
  configured `Origin` header is used for S3 CORS evaluation. AWS-prohibited names, malformed
  values, inconsistent quantities, duplicates, and quota violations are rejected with modeled
  CloudFront errors when the distribution is created or updated.
- Cache behaviors with enabled `TrustedKeyGroups` require a valid CloudFront signed URL or signed
  cookie before the origin is contacted. Signed URL parameters take precedence over signed cookies.
  Canned and custom policies support SHA-1 or SHA-256 signatures with RSA-2048 or ECDSA P-256 public
  keys. Custom policies enforce resource wildcards, expiration, optional activation time, and
  IPv4 CIDR restrictions. Canned resources compare literally, including query strings. Exact custom
  resources can include one raw query delimiter. As a conservative limitation, other custom
  resources containing a raw `?` fail closed because the character is ambiguous with CloudFront's
  one-character wildcard; custom query-string wildcards are therefore not supported. Invalid or
  expired signatures return 403.
- A key group must contain one to five existing public keys. Public keys that belong to a key group
  and key groups referenced by a cache behavior cannot be deleted until those references are removed.
- Application query parameters are retained when constructing the resource covered by a signature.
  CloudFront signing parameters are excluded from that resource and are never sent to the origin.
- S3-origin reads honor anonymous access, OAI bucket-policy or object-ACL grants, and OAC
  service-principal bucket-policy grants (including the distribution `AWS:SourceArn`) when strict S3
  authentication is enabled. OAC `always`, `never`, and unsigned `no-override` requests follow their
  documented signing behavior; signed `no-override` viewer requests retain their authorization.
- Cache-policy, origin-request-policy, and legacy `ForwardedValues` data-plane evaluation is not
  implemented yet. Viewer query strings therefore follow CloudFront's default behavior and are not
  forwarded to origins.
- Custom origins that resolve to loopback, private, link-local, carrier-grade NAT, or other non-routable addresses are rejected by default. Development-only private origins must be explicitly allowlisted by exact hostname.
- Response headers policies validate the AWS configuration shape and are applied after the origin
  response, including CORS preflight fields, origin override behavior, custom headers, security
  headers, allowed header removals, and sampled `Server-Timing` metrics. `Pragma: server-timing`
  forces those metrics for enabled policies. Distribution writes reject unknown policy IDs, and
  policies attached to a cache behavior cannot be deleted.
- Up to 20 custom response headers policies can be created, and one policy can be associated with
  up to 100 distributions.
- The five AWS managed response headers policy IDs are available and can be selected with
  `ListResponseHeadersPolicies?Type=managed`; `Type` uses the AWS lowercase `managed` or `custom`
  values.

## Configuration

| Property | Env var | Default | Description |
|---|---|---|---|
| `floci.services.cloudfront.enabled` | `FLOCI_SERVICES_CLOUDFRONT_ENABLED` | `true` | Enable or disable the service |
| `floci.services.cloudfront.domain-suffix` | `FLOCI_SERVICES_CLOUDFRONT_DOMAIN_SUFFIX` | `cloudfront.net` | Domain suffix for generated distribution domain names |
| `floci.services.cloudfront.allowed-private-origin-hosts` | `FLOCI_SERVICES_CLOUDFRONT_ALLOWED_PRIVATE_ORIGIN_HOSTS` | `[]` | Exact custom-origin hosts permitted to resolve to private/non-routable addresses (comma-separated in the environment variable) |

## CLI Examples

```bash
export AWS_ENDPOINT_URL=http://localhost:4566
export AWS_DEFAULT_REGION=us-east-1
export AWS_ACCESS_KEY_ID=test
export AWS_SECRET_ACCESS_KEY=test

# Create a distribution with an S3 origin
aws cloudfront create-distribution --distribution-config '{
  "CallerReference": "ref-1",
  "Enabled": true,
  "Comment": "my distribution",
  "Origins": {
    "Quantity": 1,
    "Items": [{
      "Id": "my-origin",
      "DomainName": "mybucket.s3.amazonaws.com",
      "S3OriginConfig": {"OriginAccessIdentity": ""}
    }]
  },
  "DefaultCacheBehavior": {
    "TargetOriginId": "my-origin",
    "ViewerProtocolPolicy": "redirect-to-https",
    "CachePolicyId": "658327ea-f89d-4fab-a63d-7e88639e58f6",
    "AllowedMethods": {"Quantity": 2, "Items": ["GET","HEAD"]},
    "Compress": true
  }
}'

# Get a distribution
aws cloudfront get-distribution --id E1Z2X3C4V5B6N7

# List distributions
aws cloudfront list-distributions

# Create a cache invalidation
aws cloudfront create-invalidation \
  --distribution-id E1Z2X3C4V5B6N7 \
  --invalidation-batch '{
    "CallerReference": "inv-1",
    "Paths": {"Quantity": 1, "Items": ["/*"]}
  }'

# Create an OAI (Origin Access Identity)
aws cloudfront create-cloud-front-origin-access-identity \
  --cloud-front-origin-access-identity-config \
  "CallerReference=oai-1,Comment=my-oai"

# Create an OAC (Origin Access Control)
aws cloudfront create-origin-access-control \
  --origin-access-control-config '{
    "Name": "my-oac",
    "Description": "",
    "OriginAccessControlOriginType": "s3",
    "SigningBehavior": "always",
    "SigningProtocol": "sigv4"
  }'

# Create a cache policy
aws cloudfront create-cache-policy --cache-policy-config '{
  "Name": "my-cache-policy",
  "DefaultTTL": 86400,
  "MinTTL": 0,
  "MaxTTL": 31536000,
  "ParametersInCacheKeyAndForwardedToOrigin": {
    "EnableAcceptEncodingGzip": true,
    "EnableAcceptEncodingBrotli": true,
    "HeadersConfig": {"HeaderBehavior": "none"},
    "CookiesConfig": {"CookieBehavior": "none"},
    "QueryStringsConfig": {"QueryStringBehavior": "none"}
  }
}'

# Disable and delete a distribution
ETAG=$(aws cloudfront get-distribution --id E1Z2X3C4V5B6N7 \
  --query 'ETag' --output text)
aws cloudfront update-distribution --id E1Z2X3C4V5B6N7 \
  --if-match "$ETAG" \
  --distribution-config '...(config with Enabled: false)...'
ETAG=$(aws cloudfront get-distribution --id E1Z2X3C4V5B6N7 \
  --query 'ETag' --output text)
aws cloudfront delete-distribution --id E1Z2X3C4V5B6N7 --if-match "$ETAG"
```

## Not Supported (Phase 2)

- Continuous deployment policies (`CreateContinuousDeploymentPolicy`, etc.)
- `CopyDistribution` (staging distributions)
- Real-time log configs (`CreateRealtimeLogConfig`, etc.)
- Field-level encryption (`CreateFieldLevelEncryptionConfig`, etc.)
- `TestFunction` execution (function is stored, not executed)
- Streaming distributions (RTMP — deprecated by AWS)
- VPC origins, Anycast IP lists, key value stores
- Monitoring subscriptions
- CloudFormation provisioning of custom `AWS::CloudFront::ResponseHeadersPolicy` resources
  (literal custom or managed policy IDs are supported on distributions)
- Persistent edge caching and global CDN propagation
