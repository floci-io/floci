# Lambda Web Functions

**Protocol:** REST JSON
**Endpoint:** `http://localhost:4566/2025-03-07/web-functions/...`

The `Lambda Web` service model (apiVersion 2025-03-07, `aws lambda-web` in the AWS CLI) signs as `lambda`, so it is served under Floci's existing lambda service and is enabled by it. A Web Function owns immutable revisions (code and configuration) and endpoints (a domain name that routes to one or two revisions).

## Supported Actions

<!-- floci:actions:start -->
| Action | Description |
| --- | --- |
| `CreateWebFunction` | Create a function with its first revision and endpoint |
| `ListWebFunctions` | List functions by name; `state` is the only filter |
| `GetWebFunction` | Function detail |
| `DeleteWebFunction` | Delete a function with its revisions and endpoints |
| `CreateWebFunctionRevision` | Create a revision from an S3 object; endpoints in `LatestRevision` mode move to it |
| `ListWebFunctionRevisions` | List revisions, newest first |
| `GetWebFunctionRevision` | Revision detail with the defaults the service fills in |
| `DeleteWebFunctionRevision` | Delete a revision no endpoint routes to |
| `CreateWebFunctionEndpoint` | Create a `HomeRegion`, `MultiRegion` or `PerRegion` endpoint |
| `ListWebFunctionEndpoints` | List endpoints by name |
| `GetWebFunctionEndpoint` | Endpoint detail with its per-region block |
| `UpdateWebFunctionEndpoint` | Change auth type, description, auto-deployment mode, revision weights, scaling and throttling |
| `DeleteWebFunctionEndpoint` | Delete an endpoint |
| `GetWebAccountSettings` | Account quotas and the function count |
| `ListTags` | Tags of a function |
| `TagResource` | Tag a function |
| `UntagResource` | Untag a function |
| `PutResourcePolicy` | Set the function's resource policy with an optimistic `revisionId` |
| `GetResourcePolicy` | Read the resource policy |
| `DeleteResourcePolicy` | Delete the resource policy |
<!-- floci:actions:end -->

## Emulation Behavior

- **Instant convergence:** a revision and an endpoint are stored `Active` and an endpoint update is stored `Successful`. The create and update responses still report the transitional state AWS reports (`Pending`, `updateStatus: InProgress`, and the per-region block as it was before the update), so a waiter sees one poll.
- **Code comes from S3 only.** The object named in `buildConfig.codeConfig.s3Object` must exist in Floci's S3, as on AWS. AWS also requires bucket versioning and a bucket policy for the Lambda service principal. Floci does not check them yet, and the revision does not pin a `versionId` the request did not give yet.
- **Execution role and network connector** ARNs are checked for shape only. The assume-role check AWS makes is not done yet.
- **Resource policies** are checked the way AWS checks them before storing: the document grammar, a `lambda:` action, a `Resource` that names the function, a valid principal, and no public `Allow`. A wildcard principal needs a positive condition (`StringEquals`, `StringLike`, `ArnLike`, `Bool`) on a trusted key such as `aws:PrincipalAccount` or `aws:PrincipalArn`, with no wildcard in the account it names. The policy is not evaluated on requests yet.
- **ARNs** given as identifiers must name the request's region and the caller's account, as on AWS. A revision or endpoint ARN resolves by its id or name only.
- **Page tokens** are base64 cursors that carry the request and the position of the page, not the UUIDs AWS issues. A token reused with other parameters gets the AWS "does not match the request" error, and any other token the "invalid or has expired" error.
- **Domain names** take the form `<id>.001.lambda-web.<region>.<floci host>` and `<id>.lambda-web.global.<floci host>`, with the host and port of `floci.base-url`. A `PerRegion` endpoint gets one domain per region in `regionalEndpoints`.
- **Not implemented yet:** `IamAuth` on requests, the automatic deletion of revisions past 50, the metadata API, the logs and metrics, and serving a `MultiRegion` or `PerRegion` endpoint in its other regions.

### `aws lambda-web deploy`

The CLI's `deploy` command works against Floci with `AWS_ENDPOINT_URL` set:

```bash
AWS_ENDPOINT_URL=http://localhost:4566 aws lambda-web deploy --name my-app --hello-world --create
```

The command passes `--endpoint-url` only to the Lambda Web client. Its STS, S3 and IAM calls use the default endpoints, so the environment variable is what routes them to Floci. The `endpointUrl` it prints is the Floci domain name behind `https://`. Floci serves it over `http://`, as below.

## Invoking an endpoint

A request whose `Host` is an endpoint's `domainName` goes to the function. The domain carries the host and port of `floci.base-url`, so either address works:

```bash
curl http://<domainName>/
curl -H "Host: <domainName>" http://localhost:4566/
```

The `Host` must match the domain exactly, as on AWS: another case or another port is `404` with `lambda-web-error-type: Platform.InvalidEndpoint`.

An execution environment is a `public.ecr.aws/lambda/nodejs:24` container that a request starts when every environment of the endpoint is full. The code is the revision's S3 object unpacked into `/var/task`, the entry file is `index.js` or the file `AWS_LAMBDA_NODEJS_ENTRYPOINT` names, and node runs with the flags AWS uses. The app sees `AWS_LAMBDA_HTTP_ENDPOINT=0.0.0.0:3000`, `AWS_LAMBDA_INITIALIZATION_TYPE=native-http`, `AWS_EXECUTION_ENV`, `AWS_LAMBDA_FUNCTION_MEMORY_SIZE=2048`, `AWS_LAMBDA_LOG_LEVEL`, `AWS_ACCOUNT_ID`, `AWS_REGION`, credentials and `AWS_ENDPOINT_URL` for Floci, `NODE_PATH`, and its own variables. The server has 10 seconds to listen. An environment that sees no request for `floci.services.lambda.container-idle-timeout-seconds` is stopped with `SIGTERM` and one second, as on scale to zero. A process that exits between requests is replaced by a fresh environment on the next request.

Each endpoint scales on its own, as on AWS. An environment takes up to the revision's `maxConcurrencyPerEnvironment` requests at once, the least busy one first, so requests rotate over the environments. A request that finds every environment full starts another one, or waits for one that is starting and has room. When the endpoint already runs `maxEnvironments`, an idle environment of another revision makes room, and without one the request is refused with `429` and `Platform.EndpointMaxEnvThrottle`. A `rateLimit` admits a burst of up to `rateLimit` requests that refills at `rateLimit` per second, and refuses the rest with `429` and `Platform.EndpointRequestThrottle`. AWS adjusts the concurrency of each environment under `maxConcurrencyPerEnvironment` from the measured load, so a burst there can spread over more environments than in Floci, and the account quotas are not applied.

Requests are forwarded over HTTP/1.1 with the headers AWS adds and drops: `lambda-web-request-id`, `lambda-web-endpoint-name`, `x-forwarded-proto`, `x-forwarded-port` and `Connection: close` are set, the client address is appended as an `X-Forwarded-For` line, and `Connection`, `Keep-Alive`, `Upgrade`, `Expect`, `Trailer`, the `Proxy-*` headers, `X-Real-IP`, the method override headers and any `lambda-web-*` header the client sent are dropped. A body without a `Content-Length` reaches the app chunked. Responses carry `lambda-web-request-id` and `lambda-web-logstream`, lose their connection headers and trailers, and stream as the app writes them. Requests with two revisions in `revisionWeights` are split by weight.

The revision's `timeoutSeconds` bounds the whole exchange, as on AWS: a response that has not started by then is the 504 page, and one that is still streaming is cut. The request body is taken in full before the answer, so a slow upload gets its 504 once the body is in.

The container gets the execution role's session credentials when the role exists in IAM, and the account's test credentials otherwise, so SDK calls from the app land in the function's account.

The platform answers these cases itself, with the AWS HTML pages and `lambda-web-error-type`:

| Status | Error type | When |
| --- | --- | --- |
| 400 | `Platform.MalformedRequest` | A method other than `GET`, `HEAD`, `POST`, `PUT`, `PATCH`, `OPTIONS`, `DELETE` |
| 404 | `Platform.InvalidEndpoint` | The `Host` is a `lambda-web` domain Floci did not mint |
| 429 | `Platform.EndpointMaxEnvThrottle` | Every environment is full and the endpoint runs `maxEnvironments` |
| 429 | `Platform.EndpointRequestThrottle` | The requests exceed the endpoint's `rateLimit` |
| 502 | `Application.TimeoutListenError`, `Application.InitCodeError`, `Runtime.Exit` | The server did not listen within 10 seconds, threw at import, or exited at start |
| 503 | `Platform.EndpointDisabled` | The endpoint's `rateLimit` is 0 |
| 503 | `Runtime.OriginRequestError` | The process died while handling the request, or tens of milliseconds before it |
| 504 | `Runtime.RequestTimeout` | No response within the revision's `timeoutSeconds` |
| 505 | `Platform.UnsupportedHttpVersion` | HTTP/1.0 |

A request head over 128 KiB is refused with `431`, without the AWS page. A request without a `Host` header cannot be told apart from one for another Floci service, so it does not get `Platform.MissingHostHeader`.

## CloudFormation

`AWS::Lambda::WebFunction`, `AWS::Lambda::WebFunctionRevision` and `AWS::Lambda::WebFunctionEndpoint` provision through the resource registry. `Ref` of each is its ARN. Every property of a revision is createOnly, so a changed revision mints a new one and the displaced revision is deleted once the update commits. An endpoint keeps its identity while its function, name, type and regions stay the same. A changed type or region list under the same name is refused the way CloudFormation refuses to replace a custom-named resource. `Fn::GetAtt` on `RegionalEndpoints` is not supported yet.

CloudFormation has no `AutoDeploymentMode` property, so an endpoint it creates is always `Disabled` and needs `RevisionWeights`, as on AWS. Name the revision with `Fn::GetAtt` so a replaced revision moves the endpoint with it. A stack update that fails after an in-place change to a function's tags or an endpoint's configuration puts the previous values back.
