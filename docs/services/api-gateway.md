# API Gateway

Floci supports both API Gateway v1 (REST APIs) and API Gateway v2 (HTTP APIs).

## Custom API IDs

API IDs are generated randomly, which means endpoint URLs change every time you recreate an API. To pin
one, pass the reserved `floci:override-id` tag on creation and Floci uses its value as the API ID. This
works for both v1 (`CreateRestApi`) and v2 (`CreateApi`), and matches the tag other services such as KMS
and Cognito already use.

```bash
aws apigateway create-rest-api \
  --name my-api \
  --tags '{"floci:override-id":"my-fixed-id","env":"test"}' \
  --endpoint-url http://localhost:4566
# the API is now reachable at the stable id "my-fixed-id"
```

The override key is consumed rather than stored, so it never appears in the tags the API returns. Any
other tags in the same request are kept. Because an ID cannot change after creation, supplying either
override key to `TagResource` is rejected with `BadRequestException`.

Values must be non-blank and must not contain whitespace, control characters, or `/`, `?`, `#`, since
those would break the endpoint URL. An invalid value is rejected with `BadRequestException`.

Creating a second API with an override ID that already exists in the region is rejected with
`ConflictException` instead of overwriting the existing API, matching how KMS and Cognito treat
duplicate override IDs.

> [!NOTE]
> API Gateway previously used a `_custom_id_` tag for this. It still works so existing setups keep
> running, and it is now stripped from the returned tags the same way, but it is deprecated: prefer
> `floci:override-id`. If both are present, `floci:override-id` wins, which lets you set both during a
> migration. The `_custom_id_` key is API Gateway specific and is not reserved for any other service.

## API Gateway v1 (REST APIs) {#v1}

**Protocol:** REST JSON
**Endpoint:** `http://localhost:4566/restapis/...`

### Supported Operations

| Category | Operations |
|---|---|
| **APIs** | CreateRestApi, ImportRestApi, PutRestApi, GetRestApi, GetRestApis, UpdateRestApi, DeleteRestApi |
| **Resources** | CreateResource, GetResource, GetResources, UpdateResource, DeleteResource |
| **Methods** | PutMethod, GetMethod, UpdateMethod, DeleteMethod |
| **Method Responses** | PutMethodResponse, GetMethodResponse, DeleteMethodResponse |
| **Integrations** | PutIntegration, GetIntegration, UpdateIntegration, DeleteIntegration |
| **Integration Responses** | PutIntegrationResponse, GetIntegrationResponse, UpdateIntegrationResponse, DeleteIntegrationResponse |
| **Deployments** | CreateDeployment, GetDeployment, GetDeployments, UpdateDeployment, DeleteDeployment |
| **Stages** | CreateStage, GetStage, GetStages, UpdateStage, DeleteStage |
| **Authorizers** | CreateAuthorizer, GetAuthorizer, GetAuthorizers, UpdateAuthorizer, DeleteAuthorizer |
| **API Keys** | CreateApiKey, ImportApiKeys, GetApiKey, GetApiKeys, UpdateApiKey, DeleteApiKey |
| **Usage Plans** | CreateUsagePlan, GetUsagePlan, GetUsagePlans, UpdateUsagePlan, DeleteUsagePlan, GetUsage |
| **Usage Plan Keys** | CreateUsagePlanKey, GetUsagePlanKey, GetUsagePlanKeys, DeleteUsagePlanKey |
| **Request Validators** | CreateRequestValidator, GetRequestValidator, GetRequestValidators, UpdateRequestValidator, DeleteRequestValidator |
| **Gateway Responses** | PutGatewayResponse, GetGatewayResponse, GetGatewayResponses, UpdateGatewayResponse, DeleteGatewayResponse |
| **Models** | CreateModel, GetModel, GetModels, UpdateModel, DeleteModel |
| **Domain Names** | CreateDomainName, GetDomainName, GetDomainNames, UpdateDomainName, DeleteDomainName |
| **Base Path Mappings** | CreateBasePathMapping, GetBasePathMapping, GetBasePathMappings, UpdateBasePathMapping, DeleteBasePathMapping |
| **Account** | GetAccount, UpdateAccount |
| **Tags** | TagResource, UntagResource, GetTags (ListTagsForResource) |

### API Key Behaviour Notes

#### `CreateApiKey` and `ImportApiKeys` share one route

Both operations are `POST /apikeys`, and AWS tells them apart by the query string, not by
`Content-Type`: `ImportApiKeys` carries `?mode=import&format=csv` and a CSV body, `CreateApiKey`
carries no query parameters and a JSON body. The SDKs send `ImportApiKeys` with no `Content-Type`
header at all, so Floci accepts any media type on this route and dispatches on `mode`.

The CSV header row is addressed by name, not position. AWS's own column set is
`Name,Key,Description,Enabled,UsagePlanIds`; a `Key` column is required, and a missing `Enabled`
column defaults to `true`. Duplicate key values are reported in the `warnings` array, and
`failonwarnings=true` turns those warnings into a `BadRequestException`.

#### `generateDistinctId`

Controls whether the key's `id` and `value` fields are distinct. AWS's undocumented default behaviour is that they are **the same string** unless `generateDistinctId=true` is explicitly requested.

| `generateDistinctId` | `id` | `value` |
|---|---|---|
| absent (default) | same as `value` | caller-supplied `value`, or a generated UUID-derived string |
| `false` | same as `value` | caller-supplied `value`, or a generated UUID-derived string |
| `true` | opaque short token (`shortId`) | caller-supplied `value`, or a generated UUID-derived string |

When `generateDistinctId` is absent or `false`, a single shared string is used for both `id` and `value`. If the caller supplies a `value` in the request body, that string is used for both; otherwise a UUID-derived string is generated and assigned to both.

When `generateDistinctId=true`, `id` is set to an opaque short token independent of `value`.

#### Revocation

`DeleteApiKey` detaches the key from every usage plan before removing it, matching AWS. A usage plan key
stores its own copy of the key value, so without that sweep a deleted key would stay listed by
`GetUsagePlanKeys` and keep being recognised on the data plane.

`requestContext.identity.apiKey` is only populated when the `x-api-key` header matches a key that still
exists and has `enabled` set to `true`, so disabling a key through `UpdateApiKey` takes effect
immediately.

> [!NOTE]
> Floci does not implement the `apiKeyRequired` gate on methods, so a request carrying an unknown,
> disabled, or deleted key is still executed — it simply arrives with a null `identity.apiKey` rather
> than being rejected with `403`.

### Custom Domain Names

A domain created with `CreateDomainName` is `AVAILABLE` at once. Its `regionalDomainName` is
`<domain>.regional.local`, and a request whose `Host` header is either that name or the domain
itself is routed through the domain's base path mappings to the mapped REST API stage. An
`EDGE` domain also reports a `distributionDomainName` under `cloudfront.net` and the fixed
CloudFront hosted zone `Z2FDTNDATAQYW2`; a `REGIONAL` domain reports neither, as on AWS. Moving a
domain between the two types with `UpdateDomainName` creates or drops the distribution.
`GetDomainName` returns the `domainNameArn`, the `endpointConfiguration`, both certificate ARNs
and the `tags`. Tags are managed through `TagResource`, `UntagResource` and `GetTags` on
`arn:aws:apigateway:<region>::/domainnames/<domain>`. A second mapping on a base path that
already has one is a `ConflictException`. A `PRIVATE` endpoint type is refused: private custom
domains are not emulated. Mutual TLS, ownership verification certificates, routing modes and
endpoint access modes are accepted and ignored.

With TLS enabled, `CreateDomainName` also adds the domain to Floci's server certificate, so
`https://<domain>` verifies against the ACM chain without a restart. The name must sit under a
local suffix such as `localhost.floci.io`; see [TLS](../configuration/tls.md).

Templates can create domains and mappings with `AWS::ApiGateway::DomainName` and
`AWS::ApiGateway::BasePathMapping`; see [CloudFormation](cloudformation.md).

### IAM Authorization (`AWS_IAM`) {#iam-authorization}

A method (v1) or route (v2) whose `authorizationType` is `AWS_IAM` requires a SigV4-signed caller.
Before the integration runs, the signature is verified against the request as it arrived (method,
path, query string, the headers named in `SignedHeaders`, and the SHA-256 of the body). Both
placements AWS accepts are honoured: an `Authorization` header and a presigned query string
(`X-Amz-Algorithm=AWS4-HMAC-SHA256`). The credential must be scoped to the `execute-api` service.

Temporary credentials must also present the session token they were issued with, as
`X-Amz-Security-Token` (a header, or a query parameter on a presigned request). The token is
compared against the value recorded when STS minted the credential, so a missing or fabricated one
is rejected: the secret alone is not the whole credential. Whether the token is covered by
`SignedHeaders` is not required either way, since SigV4 leaves that service-specific.

Rejections are `403`, and never reach the integration:

| Condition | REST (v1) `message` / `x-amzn-ErrorType` | HTTP API (v2) |
|---|---|---|
| No SigV4 credentials at all | `Missing Authentication Token` / `MissingAuthenticationTokenException` | `Forbidden` |
| Credentials present but incomplete, or scoped to another service | `Incomplete Signature` / `IncompleteSignatureException` | `Forbidden` |
| Access key the emulator never issued | `The security token included in the request is invalid.` / `UnrecognizedClientException` | `Forbidden` |
| Temporary credential presenting no `X-Amz-Security-Token`, or one that is not the token it was issued | `The security token included in the request is invalid.` / `UnrecognizedClientException` | `Forbidden` |
| `X-Amz-Date` outside the 5-minute window, or a presigned URL past `X-Amz-Expires` | `Signature expired` / `InvalidSignatureException` | `Forbidden` |
| Signature mismatch | `The request signature we calculated does not match…` / `InvalidSignatureException` | `Forbidden` |

A verified caller reaches the integration as `requestContext.identity.{accessKey, accountId, caller,
user, userArn}` on a REST proxy event, and as `requestContext.authorizer.iam` on an HTTP API 2.0
event.

Sign with any access key the emulator has issued (`CreateAccessKey`, or the temporary credentials
from `AssumeRole`), or with the well-known local-dev `test`/`test` pair that Floci accepts across
S3, RDS, and ElastiCache. No other unregistered key is accepted: an unknown key cannot sign for
itself. Any AWS SDK sends the session token automatically, so temporary credentials need no special
handling; a hand-rolled signer must include it.

> [!NOTE]
> A session minted before Floci recorded session tokens (one restored from a persisted store
> written by an earlier version) has no issued token to compare against. Such a credential must
> still present a token, but its value cannot be checked. Re-minting it with `AssumeRole` restores
> the full check.

> [!NOTE]
> Floci authenticates the caller but does not authorize the call: it does not evaluate
> `execute-api:Invoke` against the caller's IAM policies or a resource policy, so any validly
> signed, known caller is let through. `principalOrgId` and `cognitoIdentity` are always null -
> Organizations membership and identity-pool federation are not modelled.

### Gateway Responses

A REST API's gateway responses customise what the gateway itself answers when it, rather than
the integration, produces the response. All 21 AWS response types are accepted, keyed by
`responseType`; `GetGatewayResponses` lists every type, reporting the ones never customised with
`defaultResponse: true`, their AWS default `statusCode`, and the default
`{"message":$context.error.messageString}` template. `PutGatewayResponse` is an upsert,
`UpdateGatewayResponse` accepts `add`/`replace`/`remove` on `/statusCode`,
`/responseParameters/<name>` and `/responseTemplates/<content-type>` (JSON-pointer escaped, e.g.
`application~1json`), and `DeleteGatewayResponse` restores the default. The
`x-amazon-apigateway-gateway-responses` OpenAPI extension is imported by `ImportRestApi` and
`PutRestApi`, and `AWS::ApiGateway::GatewayResponse` is provisioned by CloudFormation.

On the execute plane every gateway-generated answer resolves the customisation for its type,
then for `DEFAULT_4XX` / `DEFAULT_5XX`, and applies the configured `statusCode`, the
`gatewayresponse.header.*` parameters (`'static'`, `method.request.header.*`,
`method.request.querystring.*`, `method.request.path.*`, `context.*`, `stageVariables.*`) and the
`responseTemplates` (selected by the request's `Accept` header, falling back to
`application/json`), with `$context.error.message`, `$context.error.messageString`,
`$context.error.responseType` and `$context.error.validationErrorString` available to the template.
This is what lets a browser read a `401`/`403`/`400` as such instead of as a CORS failure once
`DEFAULT_4XX` maps `Access-Control-Allow-Origin`, exactly as the console's "Enable CORS" does.

| Gateway-generated answer | `responseType` |
|---|---|
| No resource matches the path, or none declares the method (`403 Missing Authentication Token`) | `MISSING_AUTHENTICATION_TOKEN` |
| `AWS_IAM` method without a signature | `MISSING_AUTHENTICATION_TOKEN` |
| `AWS_IAM` signature malformed or mismatching | `INVALID_SIGNATURE` |
| `AWS_IAM` signature outside the 5-minute window, or a presigned URL past its expiry | `EXPIRED_TOKEN` |
| `AWS_IAM` access key the emulator never issued | `ACCESS_DENIED` |
| Lambda authorizer returns `Deny` | `ACCESS_DENIED` |
| Lambda authorizer fails or throws | `AUTHORIZER_FAILURE` |
| Method with `apiKeyRequired` and no usable `x-api-key` (`403 Forbidden`) | `INVALID_API_KEY` |
| Request validator rejects a parameter / the body | `BAD_REQUEST_PARAMETERS` / `BAD_REQUEST_BODY` |
| `passthroughBehavior` rejects the request `Content-Type` (`415`) | `UNSUPPORTED_MEDIA_TYPE` |
| Missing or unresolvable integration or URI, MOCK template that does not render | `API_CONFIGURATION_ERROR` |
| Lambda proxy function error, malformed proxy payload, or missing function | `INTEGRATION_FAILURE` |

A customised `statusCode` overrides the status Floci would otherwise send. Throttling, quota, request size and WAF answers are not produced on the execute plane, so
`THROTTLED`, `QUOTA_EXCEEDED`, `REQUEST_TOO_LARGE` and `WAF_FILTERED` can be configured but never
fire, and an `HTTP`/`HTTP_PROXY` backend's own status is relayed rather than treated as a gateway
response.

### Not Implemented

These management-plane operations have no handler in v1. Calls will return `404` or an error:

- Authorizer testing: `TestInvokeAuthorizer`
- Model templates: `GetModelTemplate`
- Documentation parts and versions (the entire family, 10 operations)
- VPC Links (5 operations)
- Client Certificates (5 operations)
- `GetExport` / `ImportDocumentationParts`

The execute plane (actual proxied HTTP traffic via `/restapis/{id}/{stage}/_user_request_/…`) is implemented separately and is not counted as management-plane operations. It supports these integration types; others return an error:

| Type | Support |
| --- | --- |
| `AWS_PROXY` (Lambda proxy) | ✅ |
| `AWS` (Lambda / AWS service with VTL request/response templates) | ✅ |
| `HTTP_PROXY` (passthrough to an arbitrary HTTP backend) | ✅ |
| `HTTP` (non-proxy, with VTL request/response templates) | ✅ |
| `MOCK` | ✅ |

A `MOCK` integration renders its request template and uses the `statusCode` it produces to pick the integration response, exactly as AWS does: the first response whose `selectionPattern` matches wins, otherwise the response without a pattern (the default) answers. This is what makes CORS preflights declared with a `204` response (CDK's `addCorsPreflight`) carry their `Access-Control-*` headers.

`HTTP_PROXY` forwards the request to the integration's `uri` — with `{param}` placeholders resolved from the matched resource's path parameters — and relays the backend's status, headers and body unchanged. Per AWS, no request templates and no integration-response selection apply to `HTTP_PROXY`, so a backend `4xx`/`5xx` reaches the caller verbatim rather than being remapped. `integration.request.{header,querystring,path}.*` → `method.request.*` mappings are applied. Hop-by-hop headers (including `Host`) are stripped. An unreachable or failing backend yields `502`.

Passthrough keeps repeated values repeated, in both directions: `?tag=a&tag=b` reaches the backend as two `tag` parameters rather than one `tag=a,b`, a header sent twice arrives twice, and a backend that returns two `Set-Cookie` headers relays two to the caller. Comma-joining them would not be reversible, since a cookie's `Expires` attribute contains a comma of its own. An explicit `integration.request.header.X` or `integration.request.querystring.X` mapping overwrites, so it replaces any repeated inbound values with the single mapped one.

`HTTP` (non-proxy) transforms in both directions instead:

- **Request** — the body is the rendered `requestTemplates` entry selected by the incoming `Content-Type` (falling back to the type without its charset), subject to `passthroughBehavior` (`NEVER` and `WHEN_NO_TEMPLATES` return `415`). Only headers and query parameters named by `integration.request.*` mappings are forwarded; unmapped inbound headers are **not** passed through — that passthrough is `HTTP_PROXY`'s job.
- **Response** — the backend's reply runs through the method's integration responses. As in AWS, `selectionPattern` is matched against the backend's **HTTP status code** (for `AWS`/Lambda integrations it is matched against the error message instead), so `"5\\d{2}"` on a `502` integration response remaps any backend `5xx` to `502`. The matched response's `responseTemplates` render the body, `responseParameters` map `integration.response.header.*` (case-insensitively) or `integration.response.body.<jsonpath>` onto `method.response.header.*`, and `$context.responseOverride` assignments take precedence. With no integration responses configured, the backend's status and body are relayed as-is.

### Examples

```bash
export AWS_ENDPOINT_URL=http://localhost:4566

# Create a REST API
API_ID=$(aws apigateway create-rest-api \
  --name "My API" \
  --query id --output text \
  --endpoint-url $AWS_ENDPOINT_URL)

# Get the root resource
ROOT_ID=$(aws apigateway get-resources \
  --rest-api-id $API_ID \
  --query 'items[?path==`/`].id' --output text \
  --endpoint-url $AWS_ENDPOINT_URL)

# Create a resource
RESOURCE_ID=$(aws apigateway create-resource \
  --rest-api-id $API_ID \
  --parent-id $ROOT_ID \
  --path-part users \
  --query id --output text \
  --endpoint-url $AWS_ENDPOINT_URL)

# Add a GET method
aws apigateway put-method \
  --rest-api-id $API_ID \
  --resource-id $RESOURCE_ID \
  --http-method GET \
  --authorization-type NONE \
  --endpoint-url $AWS_ENDPOINT_URL

# Add a Lambda integration
aws apigateway put-integration \
  --rest-api-id $API_ID \
  --resource-id $RESOURCE_ID \
  --http-method GET \
  --type AWS_PROXY \
  --integration-http-method POST \
  --uri "arn:aws:apigateway:us-east-1:lambda:path/2015-03-31/functions/arn:aws:lambda:us-east-1:000000000000:function:my-function/invocations" \
  --endpoint-url $AWS_ENDPOINT_URL

# Deploy to a stage
aws apigateway create-deployment \
  --rest-api-id $API_ID \
  --stage-name dev \
  --endpoint-url $AWS_ENDPOINT_URL

# Call the deployed API
curl http://localhost:4566/restapis/$API_ID/dev/_user_request_/users
```

### Usage reporting

`GetUsage` returns the real response envelope: a `values` map of API key id to one `[used, remaining]`
pair per day of the inclusive range, alongside `usagePlanId`, `startDate` and `endDate`. The second
element of each pair is the quota limit minus cumulative use on real API Gateway, not the quota
itself.

The operation pages over the API key entries with `limit` and `position`, defaulting to 25 keys a
page and emitting `position` only when another page exists.

Request acceptance and response page size are separate things here. Probed against real API
Gateway, every `limit` from 500 up to `Integer.MAX_VALUE` is accepted without error, so none is
rejected. That does not show the service ever returning more than 500 entries in one page, and the
documented contract caps a page at 500, so a larger `limit` is honoured as a request while the page
returned stays capped at 500.

The lower bound is a deliberate divergence: real API Gateway answers `limit=0` and `limit=-1` with
an `InternalFailure`, which is a fault rather than a contract, so a page size below one is rejected
as a `BadRequestException` instead of reproducing a 500.

**Both numbers are always zero.** Nothing meters requests per API key, and a usage plan stores no
quota to subtract from, so there is no limit to report against. Throttle settings are likewise
accepted and stored but never enforced. Storing a quota on the usage plan and counting on the
execute path are the two pieces still missing; a caller that sums the used counts gets zero, which
is what it already got before the action existed, without having to special-case a missing endpoint.

### Usage Plan Tags and Custom IDs

Usage plans accept arbitrary tags, and the same reserved `floci:override-id` tag used for
[custom API IDs](#custom-api-ids) pins the plan's `id`:

```bash
# Create a usage plan with a custom ID and additional tags
aws apigateway create-usage-plan \
  --name "my-plan" \
  --tags '{"floci:override-id":"my-plan-id","env":"staging"}' \
  --endpoint-url $AWS_ENDPOINT_URL

# The plan is now accessible at its custom ID
aws apigateway get-usage-plans --endpoint-url $AWS_ENDPOINT_URL
```

The override key is validated and consumed exactly as it is for `CreateRestApi`, so it never appears in
the tags a usage plan returns. The deprecated `_custom_id_` key is still honored on create for existing
setups, and `floci:override-id` wins when both are present. Every other tag is persisted and returned in
`CreateUsagePlan` and `GetUsagePlans` responses.

---

## Configuration

| Variable | Default | Description |
|---|---|---|
| `FLOCI_SERVICES_APIGATEWAY_ENABLED` | `true` | Enable or disable API Gateway v1 (REST APIs) |
| `FLOCI_SERVICES_APIGATEWAYV2_ENABLED` | `true` | Enable or disable API Gateway v2 (HTTP and WebSocket APIs) |

## API Gateway v2 (HTTP and WebSocket APIs) {#v2}

**Protocol:** REST JSON
**Endpoint:** `http://localhost:4566/v2/apis/...`

Both HTTP and WebSocket protocol types are fully supported, including the WebSocket data-plane (real connection handling, message routing, and the `@connections` management API).

### HTTP API data-plane

API Gateway v2 advertises HTTP APIs through Floci's local execute-api domain:

```bash
curl http://{apiId}.execute-api.localhost.floci.io:4566/{stageName}/{path}
```

When an API has a `$default` stage, callers may omit the stage segment:

```bash
curl http://{apiId}.execute-api.localhost.floci.io:4566/{path}
```

APIs created or updated with `disableExecuteApiEndpoint` reject requests to
this default hostname with `404 Not Found`, matching AWS HTTP API behavior.

Routes carrying `authorizationType: AWS_IAM`: including those an OpenAPI import resolves from an
`awsSigv4` security scheme: require a signed caller; see
[IAM Authorization](#iam-authorization).

### Supported Operations

| Category | Operations |
|---|---|
| **APIs** | CreateApi, GetApi, GetApis, UpdateApi, DeleteApi, DeleteCorsConfiguration, ImportApi, ReimportApi |
| **Routes** | CreateRoute, GetRoute, GetRoutes, UpdateRoute, DeleteRoute |
| **Route Responses** | CreateRouteResponse, GetRouteResponse, GetRouteResponses, UpdateRouteResponse, DeleteRouteResponse |
| **Integrations** | CreateIntegration, GetIntegration, GetIntegrations, UpdateIntegration, DeleteIntegration |
| **Integration Responses** | CreateIntegrationResponse, GetIntegrationResponse, GetIntegrationResponses, UpdateIntegrationResponse, DeleteIntegrationResponse |
| **Authorizers** | CreateAuthorizer, GetAuthorizer, GetAuthorizers, UpdateAuthorizer, DeleteAuthorizer |
| **Stages** | CreateStage, GetStage, GetStages, UpdateStage, DeleteStage |
| **Deployments** | CreateDeployment, GetDeployment, GetDeployments, UpdateDeployment, DeleteDeployment |
| **Models** | CreateModel, GetModel, GetModels, UpdateModel, DeleteModel |
| **Domain Names** | CreateDomainName, GetDomainName, GetDomainNames, DeleteDomainName |
| **API Mappings** | CreateApiMapping, GetApiMapping, GetApiMappings, DeleteApiMapping |
| **VPC Links** | CreateVpcLink, GetVpcLink, GetVpcLinks, DeleteVpcLink |
| **Tags** | TagResource, UntagResource, GetTags |

### WebSocket Data-Plane {#websocket-data-plane}

Floci supports real WebSocket connections for API Gateway v2 WebSocket APIs. Clients connect via:

```
ws://localhost:4566/ws/{apiId}/{stageName}
```

#### Supported Features

| Feature | Status |
|---------|--------|
| `$connect` route with Lambda integration | ✅ |
| `$disconnect` route with Lambda integration | ✅ |
| `$default` route (fallback) | ✅ |
| Custom routes via `routeSelectionExpression` | ✅ |
| Route response selection expression | ✅ |
| Lambda REQUEST authorizer on `$connect` | ✅ |
| Identity source validation (header/querystring) | ✅ |
| `@connections` POST (send message to client) | ✅ |
| `@connections` GET (get connection info) | ✅ |
| `@connections` DELETE (disconnect client) | ✅ |
| Stage variable substitution in integration URIs | ✅ |
| AWS_PROXY integration (Lambda) | ✅ |
| AWS integration (Lambda with VTL templates) | ✅ |
| HTTP_PROXY integration | ✅ |
| HTTP integration (with VTL templates) | ✅ |
| MOCK integration | ✅ |
| GoneException (410) for disconnected connections | ✅ |
| Binary frame support (`isBase64Encoded: true`) | ✅ |
| `$connect` response headers propagation | ✅ |
| 128 KB payload size limit enforcement | ✅ |
| 10-minute idle timeout | ✅ |
| 2-hour max connection duration | ✅ |

#### @connections Management API

The `@connections` API allows server-side code (e.g., Lambda functions) to send messages to connected clients, retrieve connection metadata, or disconnect clients:

```
POST   /execute-api/{apiId}/{stageName}/@connections/{connectionId}  — Send message
GET    /execute-api/{apiId}/{stageName}/@connections/{connectionId}  — Get connection info
DELETE /execute-api/{apiId}/{stageName}/@connections/{connectionId}  — Disconnect client
```

#### Behavior Notes

- **Connection URL**: Floci accepts the AWS-style execute-api host as well as the path form. All of these reach the same WebSocket API:
  - `ws://{apiId}.execute-api.{region}.localhost:4566/{stage}` — region-bearing, mirroring AWS's `wss://{api-id}.execute-api.{region}.amazonaws.com/{stage}`
  - `ws://{apiId}.execute-api.localhost.floci.io:4566/{stage}` — Floci's built-in execute-api domain (regionless; the region is resolved by an apiId lookup)
  - `ws://localhost:4566/ws/{apiId}/{stage}` — the explicit path form

  The `@connections` management API is likewise reachable on the execute-api host (`http://{apiId}.execute-api.{region}.localhost:4566/{stage}/@connections/{connectionId}`).
- **Idle timeout**: 10 minutes (matching AWS default). Not configurable per-API.
- **Max connection duration**: 2 hours (matching AWS). Connections are closed automatically.
- **Payload size limit**: 128 KB per frame (matching AWS). Oversized messages receive an error frame.

### Not Implemented

- `ExportApi`, `UpdateDomainName`, `UpdateApiMapping`
- `UpdateVpcLink` — the other four VPC Link operations are implemented; see the table above

### Examples

```bash
export AWS_ENDPOINT_URL=http://localhost:4566

# Create an HTTP API
API_ID=$(aws apigatewayv2 create-api \
  --name "My HTTP API" \
  --protocol-type HTTP \
  --query ApiId --output text \
  --endpoint-url $AWS_ENDPOINT_URL)

# Create a Lambda integration
INTEGRATION_ID=$(aws apigatewayv2 create-integration \
  --api-id $API_ID \
  --integration-type AWS_PROXY \
  --integration-uri "arn:aws:lambda:us-east-1:000000000000:function:my-function" \
  --payload-format-version 2.0 \
  --query IntegrationId --output text \
  --endpoint-url $AWS_ENDPOINT_URL)

# Create a route
aws apigatewayv2 create-route \
  --api-id $API_ID \
  --route-key "GET /users" \
  --target "integrations/$INTEGRATION_ID" \
  --endpoint-url $AWS_ENDPOINT_URL

# Deploy
aws apigatewayv2 create-stage \
  --api-id $API_ID \
  --stage-name dev \
  --auto-deploy \
  --endpoint-url $AWS_ENDPOINT_URL
```

#### WebSocket API

```bash
export AWS_ENDPOINT_URL=http://localhost:4566

# Create a WebSocket API
WS_API_ID=$(aws apigatewayv2 create-api \
  --name "My WebSocket API" \
  --protocol-type WEBSOCKET \
  --route-selection-expression '$request.body.action' \
  --query ApiId --output text \
  --endpoint-url $AWS_ENDPOINT_URL)

# Create a Lambda integration
WS_INTEGRATION_ID=$(aws apigatewayv2 create-integration \
  --api-id $WS_API_ID \
  --integration-type AWS_PROXY \
  --integration-uri "arn:aws:lambda:us-east-1:000000000000:function:my-ws-handler" \
  --query IntegrationId --output text \
  --endpoint-url $AWS_ENDPOINT_URL)

# Create $connect, $disconnect, and $default routes
aws apigatewayv2 create-route \
  --api-id $WS_API_ID \
  --route-key '$connect' \
  --target "integrations/$WS_INTEGRATION_ID" \
  --endpoint-url $AWS_ENDPOINT_URL

aws apigatewayv2 create-route \
  --api-id $WS_API_ID \
  --route-key '$disconnect' \
  --target "integrations/$WS_INTEGRATION_ID" \
  --endpoint-url $AWS_ENDPOINT_URL

aws apigatewayv2 create-route \
  --api-id $WS_API_ID \
  --route-key '$default' \
  --route-response-selection-expression '$default' \
  --target "integrations/$WS_INTEGRATION_ID" \
  --endpoint-url $AWS_ENDPOINT_URL

# Deploy
aws apigatewayv2 create-stage \
  --api-id $WS_API_ID \
  --stage-name prod \
  --endpoint-url $AWS_ENDPOINT_URL

# Connect via WebSocket (using wscat or any WebSocket client)
# wscat -c ws://localhost:4566/ws/$WS_API_ID/prod

# Send a message to a connected client via @connections API
# curl -X POST http://localhost:4566/execute-api/$WS_API_ID/prod/@connections/$CONNECTION_ID \
#   -d "Hello from server"

# Get connection info
# curl http://localhost:4566/execute-api/$WS_API_ID/prod/@connections/$CONNECTION_ID

# Disconnect a client
# curl -X DELETE http://localhost:4566/execute-api/$WS_API_ID/prod/@connections/$CONNECTION_ID
```
