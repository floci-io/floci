# CloudFront

CloudFront management-plane and local content-delivery emulation. Supports distribution lifecycle,
cache policies, origin request policies, response headers policies, origin access controls, origin
access identities, public keys, trusted key groups, CloudFront Functions, invalidations, tagging, and
viewer delivery from S3 or custom origins for every CloudFront viewer method (GET, HEAD, OPTIONS, POST,
PUT, PATCH and DELETE).

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
- Distribution domain names follow the pattern `{id}.cloudfront.net`, with the id lower-cased as AWS
  writes it in a host name.
- Public key IDs are `K` followed by 13 uppercase alphanumeric characters (e.g. `K2JCJMDEHXQW5F`),
  the value a signed URL carries as `Key-Pair-Id`. Key group, cache policy, origin request policy and
  response headers policy IDs are UUIDs, as they are on AWS.
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
- Viewer requests addressed to an enabled distribution's generated domain or alias are routed to the
  matching S3 or custom origin when the matched cache behavior's `AllowedMethods` includes the
  method; any other method returns 403 `Invalid method.`. A behavior without `AllowedMethods` allows
  GET and HEAD. Origin forwarding preserves the raw path; custom-origin redirects are not followed.
- POST, PUT, PATCH and DELETE are forwarded to custom origins with the viewer request body and its
  `Content-Type`. The body is streamed to the origin with the viewer's `Content-Length`, or chunked
  when the viewer sent it chunked or, as HTTP/2 allows, with no length, so it is never held in
  memory. Their responses are never cached, as on AWS. Signed URL and signed cookie enforcement
  applies to them like any other method. Request bodies are bounded by the emulator-wide
  `floci.protocols.max-request-size` limit (`FLOCI_PROTOCOLS_MAX_REQUEST_SIZE`, 2048 MB by default);
  CloudFront accepts up to 64 GB, so raise it for larger uploads.
- Every distribution is also served as `{id}.cloudfront.{host}` for each endpoint host Floci
  resolves: `localhost`, `localhost.floci.io`, `localhost.localstack.cloud`, `FLOCI_HOSTNAME` and
  every `FLOCI_DNS_EXTRA_SUFFIXES` entry. `{id}.cloudfront.localhost.floci.io` and
  `{id}.cloudfront.localhost` reach loopback with no host-file edit and are covered by the generated
  HTTPS certificate, so a signed URL for either can be downloaded over `https://`. See
  [Downloading over HTTPS](#downloading-over-https).
- Origin custom headers are persisted through the CloudFront API and CloudFormation. They replace
  every same-named forwarded viewer header on custom-origin requests. For in-process S3 origins, a
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
- Requests to custom origins carry what the matched cache behavior forwards. With a cache policy,
  the viewer headers, cookies and query strings selected by the cache policy's
  `ParametersInCacheKeyAndForwardedToOrigin` and by the origin request policy are forwarded (the
  union of both). `HeaderBehavior` `none`, `whitelist`, `allViewer`,
  `allViewerAndWhitelistCloudFront` and `allExcept`, `CookieBehavior` and `QueryStringBehavior`
  `none`, `whitelist`, `allExcept` and `all` are evaluated. A cache policy that selects nothing and no
  origin request policy forwards no viewer headers, cookies or query strings, as on AWS, apart from
  the headers below that CloudFront forwards whatever the policies.
- A cache behavior without a cache policy uses its legacy `ForwardedValues`: `QueryString=true`
  forwards every query string (`QueryStringCacheKeys` only narrows the cache key), cookies follow
  `Forward` `none`, `whitelist` (with `*` and `?` wildcards) or `all`, and viewer headers are
  forwarded by default except `Accept`, `Accept-Charset`, `Accept-Language`, `Authorization`,
  `Host`, `Referer`, `User-Agent` and `X-HTTP-Method-Override`, which `Headers` can add back (`*`
  forwards all of them). An origin request policy needs a cache policy on AWS, so a behavior without
  one ignores it.
- Whatever the policies, CloudFront sets `Host` to the origin's domain name unless the viewer's
  `Host` is forwarded, sends `User-Agent: Amazon CloudFront` unless the viewer's `User-Agent` is
  forwarded, appends the viewer address to `X-Forwarded-For`, adds a
  `Via: <viewer HTTP version> <distribution domain> (CloudFront)` hop and an `X-Amz-Cf-Id`.
  `Authorization` is always forwarded on POST, PUT, PATCH and DELETE, and on OPTIONS unless OPTIONS
  is a cached method; `Content-Type` travels with a request body; a GET or HEAD with a `Range`
  carries the viewer's `Range` and `If-Range`, since CloudFront forwards a range GET to the origin,
  and a HEAD can then report the range's length. Cache policy compression settings normalize
  `Accept-Encoding` to `br,gzip`, `gzip`, `br` or `identity`.
  Forwarded cookies are sorted by name, and cookies that are not `name=value` or whose name starts
  with `$` are dropped. Hop-by-hop headers, `Expect`, `X-Edge-*`, `X-Real-IP`, `X-Forwarded-Proto`
  and viewer-supplied `CloudFront-*` headers are never forwarded.
- Of the CloudFront request headers, `CloudFront-Forwarded-Proto`, `CloudFront-Viewer-Address` and
  `CloudFront-Viewer-Http-Version` are generated when a policy names them, or when an `allExcept`
  header behavior does not exclude them (AWS documents Managed-AllViewerExceptHostHeader as adding
  them). Device-type, geolocation, TLS, JA3/JA4 and header-order headers are not generated.
- The eight AWS managed origin request policies are available by their AWS IDs through
  `GetOriginRequestPolicy` and `ListOriginRequestPolicies` (`Type` is `managed` or `custom`), and
  cannot be updated or deleted. AWS managed cache policies are not modeled: a behavior that
  references one forwards nothing on its account, which matches `CachingDisabled`,
  `CachingOptimized` and `CachingOptimizedForUncompressedObjects` but not `Amplify`,
  `Elemental-MediaPackage` or the `UseOriginCacheControlHeaders` policies.
- In-process S3 origins do not apply forwarding settings: viewer query strings, cookies and headers
  do not change the S3 read, apart from the viewer's `Range` and `If-Range` on a GET or HEAD.
- Origin responses are streamed to the viewer, from in-process S3 origins and custom origins alike,
  so a large object is never held in memory. A custom origin's `Content-Length` is passed on, and a
  response it sends chunked stays chunked. Floci does not cache, so it serves objects larger than
  50 GB the way CloudFront does with caching disabled; with caching enabled, CloudFront refuses them.
- Range GETs: an S3 origin serves the one byte range a viewer's `Range` asks for as 206 with its
  `Content-Range`, and answers 416 when the range starts past the end of the object. Any other
  `Range` gets the whole object: one CloudFront does not accept (ranges out of order or overlapping,
  or a negative value), several ranges, which S3 does not serve in one request, or a last byte
  before the first, as does a `Range` whose `If-Range` no longer describes the object, which S3
  answers with the whole object. A HEAD is answered as S3's HeadObject answers a `Range`: a
  satisfiable range changes only its `Content-Length`, and one past the end gets 416. A custom
  origin gets the viewer's `Range` and `If-Range` on every GET or HEAD with a `Range`, whatever the
  forwarding settings, and its answer (206, the whole object, or 416) is passed on. Floci does not
  cache, so unlike CloudFront it cannot serve a later range request from an object the origin sent
  whole. A custom error page is served whole, whatever the `Range` of the request it replaces.
- Origin `Set-Cookie` headers always reach the viewer. With legacy `Forward=none`, AWS strips them
  from the response; Floci does not.
- Custom-origin requests honor the origin's connection settings. `Origin.ConnectionAttempts` (1 to 3,
  default 3) is how many times CloudFront tries to reach the origin, and `Origin.ConnectionTimeout`
  (1 to 10 seconds, default 10) how long it waits for the connection. `CustomOriginConfig.OriginReadTimeout`
  (1 to 120 seconds, default 30) bounds the wait for the origin's response, and a read timeout on a
  GET or HEAD is retried up to the connection attempts, as CloudFront does, while a POST, PUT, PATCH
  or DELETE is not retried. `Origin.ResponseCompletionTimeout` bounds the time the origin takes,
  connecting, answering and streaming the body included, over every attempt, and ends the connection
  when it expires; unset, or 0, leaves the origin as long as it wants to take, and a set value must
  be at least the read timeout. The time a slow viewer takes to read what the origin already sent is
  not part of it: the origin answered in time, so the response is not expired.
  `CustomOriginConfig.OriginKeepaliveTimeout` (1 to 300 seconds, default 5) is how long CloudFront
  keeps a connection to the origin after the last packet of a response. An origin that does not
  answer in time is answered 504, "The origin didn't respond before the request expired", as on AWS.
- Custom origins that resolve to loopback, private, link-local, carrier-grade NAT, or other non-routable addresses are rejected by default. Development-only private origins must be explicitly allowlisted by exact hostname.
- A custom origin whose domain is a [Lambda Web Function](lambda-web-functions.md) endpoint of this Floci is
  served by Floci itself, at the address the viewer reached. The origin names the endpoint's
  `domainName` with or without its port, and its protocol and ports do not matter, so neither DNS nor
  the private address check applies. A forwarded viewer `Host` is a 502, as on AWS, where the endpoint's
  certificate does not name it. Under an `always-amz-auth` origin access control, Floci signs these
  requests for the distribution. An `IamAuth` endpoint then lets them through, or with IAM enforcement on
  evaluates the function's resource policy for `cloudfront.amazonaws.com`, with the distribution as
  `aws:SourceArn` and its account as `aws:SourceAccount`. Without one, an `IamAuth` endpoint answers
  with its 403 page. CloudFront forwards the viewer's `Authorization` on GET and HEAD only when the
  cache policy includes it. A forwarded `Authorization` reaches the function unless it is an AWS
  signature.
- Origin access controls and distributions follow the AWS checks for Lambda origins. CloudFront tells
  them apart by a hostname under `on.aws` with its fixed labels in lowercase, and Floci also by one
  under its own host. A Web Function endpoint needs a `lambda` origin access control with
  `always-amz-auth`, signed with `sigv4a` for a `global` endpoint and `sigv4` otherwise, and
  `always-amz-auth` is for Web Function origins only. A Lambda@Edge origin trigger is refused in
  front of an origin signed with `always-amz-auth` or `sigv4a`. The `execute-api` origin type is
  refused with `AccessDenied`, as AWS refuses it. A distribution has to name an origin access control
  that exists, and a control in use keeps its origin type, its signing protocol and whether it is
  `always-amz-auth`.
- Response headers policies validate the AWS configuration shape and are applied after the origin
  response, including CORS preflight fields, origin override behavior, custom headers, security
  headers, allowed header removals, and sampled `Server-Timing` metrics. `Pragma: server-timing`
  forces those metrics for enabled policies. Distribution writes reject unknown policy IDs, and
  policies attached to a cache behavior cannot be deleted.
- `ViewerCertificate.IAMCertificateId` must be the `ServerCertificateId` of an uploaded IAM server
  certificate: distribution create and update reject an unknown one with `InvalidViewerCertificate`,
  and `DeleteServerCertificate` returns `DeleteConflict` while a distribution references it.
- Up to 20 custom response headers policies can be created, and one policy can be associated with
  up to 100 distributions.
- The five AWS managed response headers policy IDs are available and can be selected with
  `ListResponseHeadersPolicies?Type=managed`; `Type` uses the AWS lowercase `managed` or `custom`
  values.

## Configuration

| Property | Env var | Default | Description |
|---|---|---|---|
| `floci.services.cloudfront.enabled` | `FLOCI_SERVICES_CLOUDFRONT_ENABLED` | `true` | Enable or disable the service |
| `floci.services.cloudfront.domain-suffix` | `FLOCI_SERVICES_CLOUDFRONT_DOMAIN_SUFFIX` | unset | Domain suffix for generated distribution domain names. Unset, it follows the request's partition: `cloudfront.net`, or `cloudfront.cn` in China. A configured suffix applies in every partition |
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

## Downloading over HTTPS

A distribution's own domain name (`{id}.cloudfront.net` by default) resolves to nothing local, so
address the distribution by one of its local delivery hostnames instead. `*.cloudfront.localhost.floci.io`
resolves to `127.0.0.1` through public DNS and works on Linux, macOS and in containers.
`*.cloudfront.localhost` needs no DNS at all but only resolves where the runtime handles `.localhost`
itself, which macOS and browsers do and Debian-based images do not.

Start Floci with [TLS](../configuration/tls.md) enabled and trust its CA once:

```bash
docker run -e FLOCI_TLS_ENABLED=true -p 4566:4566 floci/floci:latest
curl -s http://localhost:4566/_floci/ca.pem -o floci-root-ca.pem
```

Sign the URL of the hostname you download from — the port is part of what a canned policy signs —
and fetch it:

```bash
HOST=e1z2x3c4v5b6n7.cloudfront.localhost.floci.io:4566

SIGNED=$(aws cloudfront sign \
  --url "https://$HOST/hello.txt" \
  --key-pair-id K2JCJMDEHXQW5F \
  --private-key file://private_key.pem \
  --date-less-than 2026-12-31T00:00:00Z)

curl --cacert floci-root-ca.pem "$SIGNED"
```

To drop the `:4566`, publish the HTTPS port Floci also binds when TLS is on (`-p 443:443`, see
`FLOCI_TLS_AWS_HTTPS_PORT`) and sign `https://e1z2x3c4v5b6n7.cloudfront.localhost.floci.io/hello.txt`.

Set `FLOCI_SERVICES_CLOUDFRONT_DOMAIN_SUFFIX=cloudfront.localhost.floci.io` to have
`CreateDistribution` return that hostname as the `DomainName`, so test code can sign the API response
as it is.

### Behind a reverse proxy

When a proxy terminates HTTPS and forwards HTTP to Floci, sign the browser-visible HTTPS URL,
including its external hostname, port and path. Floci rebuilds the signed resource from the
request scheme and host, so it must receive the external scheme through trusted proxy headers.
A signature made for `https://` does not match a resource rebuilt as `http://`.

For a proxy that preserves `Host` and sets `X-Forwarded-Proto` and `X-Forwarded-Port`,
configure the Floci container with:

```yaml
environment:
  QUARKUS_HTTP_PROXY_PROXY_ADDRESS_FORWARDING: "true"
  QUARKUS_HTTP_PROXY_ALLOW_X_FORWARDED: "true"
  QUARKUS_HTTP_PROXY_ALLOW_FORWARDED: "false"
  QUARKUS_HTTP_PROXY_ENABLE_FORWARDED_HOST: "true"
  QUARKUS_HTTP_PROXY_TRUSTED_PROXIES: "<proxy source IP or CIDR>"
```

Replace the placeholder with the proxy's source address **as seen by Floci**. In Docker this may
be a bridge or gateway address rather than the host's loopback address. Quarkus ignores forwarded
headers from peers outside `QUARKUS_HTTP_PROXY_TRUSTED_PROXIES`; a missing proxy address leaves
Floci seeing HTTP and a valid HTTPS URL fails with `Signature does not match the policy`.

For example, when signing `https://e1z2x3c4v5b6n7.cloudfront.localhost.floci.io:19443/hello.txt`,
the proxy should forward these headers to Floci:

```http
Host: e1z2x3c4v5b6n7.cloudfront.localhost.floci.io:19443
X-Forwarded-Proto: https
X-Forwarded-Port: 19443
```

A non-default external port needs `X-Forwarded-Port`, even when the proxy preserves `Host`.
Applying `X-Forwarded-Proto` resets Quarkus' port, so preserving `Host` alone rebuilds the URL
without `:19443` and fails the signature check. Alternatively, send `X-Forwarded-Host` with
the external hostname **and port**; the forwarded-host setting above enables its use.

The proxy must replace client-supplied forwarded headers, and the trusted-proxy list should cover
only its actual peers. See [Quarkus reverse-proxy configuration](https://quarkus.io/version/3.40/guides/http-reference/#running-behind-a-reverse-proxy)
for other header layouts.

With these settings a valid proxy HTTPS signed URL succeeds; missing, expired or invalid
signatures must still return 403. Verify those cases through the proxy after configuring it.

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
- Forwarding POST, PUT, PATCH and DELETE to S3 origins. AWS passes them to S3, which evaluates them
  against the bucket policy (OAC supports `PUT` and `DELETE`); Floci's in-process S3 origin answers
  them with 403 `Access Denied`, as S3 does for a request that holds no write grant.
