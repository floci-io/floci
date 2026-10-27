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

- **Instant convergence:** a revision and an endpoint are stored `Active`, or `Failed` as below, and an endpoint update is stored `Successful`. The create and update responses still report the transitional state AWS reports (`Pending`, `updateStatus: InProgress`, and the per-region block as it was before the update), so a waiter sees one poll.
- **Failed revisions:** a revision whose code is not a zip, or whose files add up to more than 512 MiB, is stored `Failed`, as on AWS. It cannot be deployed: weights that name it, or `LatestRevision` while it is the newest revision, are `409 ConflictException`, and `LatestRevision` endpoints stay on their revision. The endpoint of a `CreateWebFunction` whose revision fails is `Failed` too. It answers every request with `409` and `Platform.EndpointNotActive` and refuses updates until it is deleted. In a stack, the revision's resource fails with the revision's `stateReason` and the rollback deletes it. An empty object is a `500 InternalServerException` with the body `{}`, and an object over 512 MiB is a `400 ValidationException`, as on AWS.
- **Code comes from S3 only.** The object named in `buildConfig.codeConfig.s3Object` must exist in Floci's S3, as on AWS. A request without `versionId` is refused when the latest version is the null version, so the object has to be written after the bucket's versioning is enabled, as on AWS. When IAM enforcement is on, the bucket policy must also let `lambda.amazonaws.com` take `s3:GetObjectVersion` on the object. The policy sees the new revision ARN as `aws:SourceArn`, the function's account as `aws:SourceAccount` and the version Lambda reads as `s3:VersionId`. As on AWS, `s3:GetObject` is not evaluated, although the error names it. Floci does not check the caller's own S3 permissions or the KMS key policy of an encrypted object.
- **Each revision keeps its own copy of the code**, read at creation from the version the request names or the current one, under the Lambda `code-path`. As on AWS, a later write or delete in the bucket does not change what the revision runs. AWS moves a revision to `Failed` when it later finds its object version gone, which Floci does not do.
- **Revision cleanup:** when a revision turns `Active` and the function has more than 50, the oldest revisions that no endpoint routes to are deleted until 50 are left, `Failed` ones included, as on AWS. A new `Failed` revision deletes nothing. The revision that a `LatestRevision` endpoint leaves is not deleted before the next revision turns `Active`, as on AWS.
- **Execution role:** a role in another account is `403 AccessDeniedException` "Cross-account pass role is not allowed.". A role that does not exist in IAM, or whose trust policy does not let `lambda.amazonaws.com` assume it, is `400 ValidationException` "The execution role <arn> cannot be assumed by lambda.amazonaws.com. Verify its trust policy. If the role was just created, retry in a few seconds.". As on AWS, this check sets no `aws:SourceAccount` or `aws:SourceArn`, so a trust policy that requires either refuses the role.
- **Egress network connectors:** `egressNetworkConnectorArn` names a connector version by the connector id, in the function's region, as on AWS. A version that does not exist, a connector name, or another region is `404 ResourceNotFoundException` "The egress network connector <arn> does not exist.". A connector without `WebFunction` in its `AssociatedComputeResourceTypes` is `400 ValidationException`. An unknown region, a partition that is not the region's, or a version such as `0` or `01` is `400 ValidationException` "The egress network connector ARN: '<arn>' is not valid. ...", and a connector in another account is `403 AccessDeniedException`. A version past the 64-bit range is `500 InternalServerException` with the body `{}`, as on AWS. Only a `HomeRegion` endpoint can serve such a revision: `CreateWebFunction` with another endpoint type, and weights of a `PerRegion` or `MultiRegion` endpoint that name such a revision, are `400 ValidationException`. Floci settles a connector at once, so the AWS refusal of a `PENDING` version never happens. When IAM enforcement is on, the caller also needs `lambda:PassNetworkConnector` on the version ARN as the request gives it, refused as on AWS (see [IAM](iam.md)). Floci checks it before the request reaches Lambda, so a refusal comes before the ARN checks above, which AWS runs first. The connector does not change the network of the function's container.
- **Resource policies** are checked the way AWS checks them before storing: the document grammar, a `lambda:` action, a `Resource` that names the function, a valid principal, and no public `Allow`. A wildcard principal needs a positive condition (`StringEquals`, `StringLike`, `ArnLike`, `Bool`) on a trusted key such as `aws:PrincipalAccount` or `aws:PrincipalArn`, with no wildcard in the account it names. An `IamAuth` endpoint evaluates it on requests, as below.
- **Function names** are unique across the regions of an account, as on AWS. A name that another region already has is `409 ConflictException` "A function named <name> already exists in the account. Function names must be unique across all regions in an account.".
- **ARNs** given as identifiers must name the request's region and the caller's account, as on AWS. A revision or endpoint ARN resolves by its id or name only.
- **Page tokens** are base64 cursors that carry the request and the position of the page, not the UUIDs AWS issues. A token reused with other parameters gets the AWS "does not match the request" error, and any other token the "invalid or has expired" error.
- **Domain names** take the form `<id>.001.lambda-web.<region>.<floci host>` and `<id>.lambda-web.global.<floci host>`, with the host and port of `floci.base-url`. A `PerRegion` endpoint gets one domain per region in `regionalEndpoints`, all with the same id, as on AWS.
- **Not implemented yet:** SigV4a on `MultiRegion` endpoints and the check that a policy's principal exists.

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

A CloudFront distribution of this Floci can use the endpoint as a custom origin, named by its `domainName` without the port as on AWS. Under an `always-amz-auth` origin access control, CloudFront signs the requests to an `IamAuth` endpoint, and the function's resource policy has to allow `cloudfront.amazonaws.com` when IAM enforcement is on. Its `aws:RequestedRegion` is the region that serves the request, as on AWS. See [CloudFront](cloudfront.md).

An execution environment is a `public.ecr.aws/lambda/nodejs:24` container that a request starts when every environment of the endpoint is full. The code is the revision's S3 object unpacked into `/var/task`. Node starts `/var/runtime/index.js` with the flags AWS uses, as on AWS, and that runtime imports `index.js` or the file `AWS_LAMBDA_NODEJS_ENTRYPOINT` names. So `require.main` is not the app's module, as on AWS. A `--max-old-space-size` or `--max-semi-space-size` in `NODE_OPTIONS` takes the place of both default heap flags, as on AWS. Other flags there do not replace a default flag, so `--max-http-header-size` in `NODE_OPTIONS` has no effect, and a flag Node does not allow in `NODE_OPTIONS` fails the start with `502 Runtime.ExitError`, as on AWS. The app sees `AWS_LAMBDA_HTTP_ENDPOINT=0.0.0.0:3000`, `AWS_LAMBDA_INITIALIZATION_TYPE=native-http`, `AWS_EXECUTION_ENV`, `AWS_LAMBDA_FUNCTION_MEMORY_SIZE=2048`, `AWS_LAMBDA_LOG_LEVEL`, `AWS_LAMBDA_METADATA_API`, `AWS_LAMBDA_METADATA_TOKEN`, `AWS_LAMBDA_RUNTIME_API`, `AWS_ACCOUNT_ID`, `AWS_REGION`, credentials and `AWS_ENDPOINT_URL` for Floci, `NODE_PATH`, and its own variables. The server has 9 seconds to listen. An environment that sees no request for `floci.services.lambda.container-idle-timeout-seconds` is stopped with `SIGTERM` and one second, as on scale to zero. Node runs under Docker's init, so a server without a `SIGTERM` handler stops at once, as on AWS. A process that exits between requests is replaced by a fresh environment on the next request.

Each endpoint scales on its own, as on AWS. An environment takes up to the revision's `maxConcurrencyPerEnvironment` requests at once, the least busy one first, so requests rotate over the environments. A request that finds every environment full starts another one, or waits for one that is starting and has room. When the endpoint already runs `maxEnvironments`, an idle environment of another revision makes room, and without one the request is refused with `429` and `Platform.EndpointMaxEnvThrottle`. A `rateLimit` admits a burst of up to `rateLimit` requests that refills at `rateLimit` per second, and refuses the rest with `429` and `Platform.EndpointRequestThrottle`. AWS adjusts the concurrency of each environment under `maxConcurrencyPerEnvironment` from the measured load, so a burst there can spread over more environments than in Floci, and the account quotas are not applied.

Each regional domain of a `PerRegion` endpoint is served in its own region, as on AWS. Its environments see that region in `AWS_REGION` and `AWS_DEFAULT_REGION` and get that region's zone from the metadata endpoint, and `maxEnvironments` and `rateLimit` hold in each region on its own. AWS serves the global domain of a `MultiRegion` endpoint from the region nearest the caller. Floci takes `floci.default-region` for where the caller is, so it serves the global domain in that region when the endpoint has it, and in the home region otherwise. The serving region then works as for a regional domain.

Requests are forwarded over HTTP/1.1 with the headers AWS adds and drops: `lambda-web-request-id`, `lambda-web-endpoint-name`, `x-forwarded-proto`, `x-forwarded-port` and `Connection: close` are set, the client address is appended as an `X-Forwarded-For` line, and `Connection`, `Keep-Alive`, `Upgrade`, `Expect`, `Trailer`, the `Proxy-*` headers, `X-Real-IP`, the method override headers and any `lambda-web-*` header the client sent are dropped. A body without a `Content-Length` reaches the app chunked. The path reaches the app without dot segments, as on AWS, and repeated slashes stay. Responses carry `lambda-web-request-id` and `lambda-web-logstream`, lose their connection headers and trailers, and stream as the app writes them. Requests with two revisions in `revisionWeights` are split by weight.

An `IamAuth` endpoint takes a request signed with SigV4 for the service `lambda` in the region of the domain, in the `Authorization` header or in a presigned query. The request must carry `X-Amz-Content-SHA256`. Lambda takes its value as the payload hash and does not read the body, so `UNSIGNED-PAYLOAD` works. The signing time must be within five minutes. Floci verifies keys whose secret it holds: `test` with the secret `test`, IAM user keys and STS credentials with their session token. Any other key is refused. When `floci.services.iam.enforcement-enabled` is on, the signer also needs `lambda:InvokeWebFunctionEndpoint` on the endpoint ARN. Within one account the identity policy or the function's resource policy can allow it, and across accounts both must. `lambda:Resource/WebFunctionAuthType` and the function's tags as `aws:ResourceTag/<key>` are in the context, and `aws:RequestedRegion` is the region that serves the request, as on AWS. The `test` key is not evaluated, as elsewhere in Floci. The global domain of a `MultiRegion` endpoint takes SigV4 for the region that serves the request, as on AWS. Its SigV4a requests are refused, because Floci does not verify SigV4a yet. On every endpoint, an `Authorization` that is an AWS signature is removed before the request reaches the app, together with the `X-Amz-Security-Token` header. Other `Authorization` values pass. A presigned query reaches the app unchanged, as on AWS.

The revision's `timeoutSeconds` bounds the whole exchange, as on AWS: a response that has not started by then is the 504 page, and one that is still streaming is cut. The request body is taken in full before the answer, so a slow upload gets its 504 once the body is in.

The container gets the execution role's session credentials, so SDK calls from the app land in the function's account. An environment assumes the role with the function ARN as `aws:SourceArn` and its account as `aws:SourceAccount`. When the role was deleted or no longer trusts Lambda, the request gets `502 Platform.CannotAssumeRole` and the revision stays `Active`, as on AWS.

The [metadata endpoint](https://docs.aws.amazon.com/lambda/latest/dg/configuration-metadata-endpoint.html) answers `GET http://${AWS_LAMBDA_METADATA_API}/2026-01-15/metadata/execution-environment` with `{"AvailabilityZoneID":"<id>"}` when the request carries `Authorization: Bearer ${AWS_LAMBDA_METADATA_TOKEN}`, and with `401` and `"Client.Unauthorized"` otherwise. As on AWS, it takes any method, has no `Cache-Control`, answers another path with `404` before it looks at the token, and redirects a path with `//`, `.` or `..` segments with `307`. `AWS_LAMBDA_RUNTIME_API` names the same server, as on AWS. The server listens on a port the system picks, outside the Lambda Runtime API range, at the host the container reaches Floci at, where AWS uses `169.254.100.1:9001`, and the zone is always the region's first zone id, such as `use1-az1`.

The platform answers these cases itself, with the AWS HTML pages and `lambda-web-error-type`:

| Status | Error type | When |
| --- | --- | --- |
| 400 | `Platform.MalformedRequest` | A method other than `GET`, `HEAD`, `POST`, `PUT`, `PATCH`, `OPTIONS`, `DELETE` |
| 403 | none | An `IamAuth` request with no valid signature or no permission. The endpoint's `rateLimit` 0 answers first, and a refused request takes no part of the `rateLimit` |
| 404 | `Platform.InvalidEndpoint` | The `Host` is a `lambda-web` domain Floci did not mint |
| 429 | `Platform.EndpointMaxEnvThrottle` | Every environment is full and the endpoint runs `maxEnvironments` |
| 429 | `Platform.EndpointRequestThrottle` | The requests exceed the endpoint's `rateLimit` |
| 431 | `Platform.HeaderTooLarge` | The request line, the headers and the blank line pass 128 KiB, counted as `Name: value` lines. This page answers before every other check |
| 502 | `Application.TimeoutListenError`, `Application.InitCodeError`, `Runtime.ImportCodeError`, `Runtime.Exit`, `Runtime.ExitError` | The server did not listen within 9 seconds, threw at import, could not find a module at import, or exited at start with status 0 or another status |
| 503 | `Platform.EndpointDisabled` | The endpoint's `rateLimit` is 0 |
| 503 | `Runtime.OriginRequestError` | The process died while handling the request, or tens of milliseconds before it |
| 504 | `Runtime.RequestTimeout` | No response within the revision's `timeoutSeconds` |
| 505 | `Platform.UnsupportedHttpVersion` | HTTP/1.0 |

A URI of 65,535 bytes or more gets an empty `414`, as on AWS. Headers or a request line past 256 KiB get the HTTP server's empty `431` or `414` instead of the AWS answer. A request without a `Host` header cannot be told apart from one for another Floci service, so it does not get `Platform.MissingHostHeader`.

## Logs

What an environment writes goes to the revision's `logGroup`, by default `/aws/lambda/web/<function>`. Each event goes to the stream that `lambda-web-logstream` names in the `<logGroup>.local` group of the region that served, and to the same stream with a `-<account>-<region>` suffix in `<logGroup>` of the home region, where AWS centralizes the regions an endpoint serves. Floci writes both at once, where AWS copies the events of another region to the home region within minutes. A group and a stream are created when their first event arrives, with no retention.

Console output becomes the JSON events AWS writes, with `timestamp`, `level`, `message`, `region` (the region that served), and `requestId` and `endpointName` for output made while a request runs. `console.log` and `console.info` are `INFO`, `console.debug` is `DEBUG`, `console.trace` is `TRACE` with the stack, `console.warn` is `WARN` and `console.error` is `ERROR`. One argument that is not a string goes into `message` as JSON, and an `Error` as `errorType`, `errorMessage` and `stackTrace`. Several arguments make one string, formatted as Node prints them. Output written to stdout or stderr directly, and `console.dir`, stays as it is, one event per line. Events below the revision's `applicationLogLevel` are dropped, and direct output counts as `INFO`. `FATAL` drops all console output, as on AWS. Bytes that are not UTF-8 become U+FFFD. A line longer than 262,118 bytes in UTF-8 is cut before the character that would pass it, and a line of only spaces makes no event.

The output of an environment that fails before it listens is written too, as on AWS. A stack trace shows the frames of Floci's runtime where AWS shows its own.

The platform writes the `platform.*` events of the [system log schema](https://docs.aws.amazon.com/lambda/latest/dg/web-functions-logging-system-log-schema.html) to the same stream, and drops those below the revision's `systemLogLevel`:

| Event | When |
| --- | --- |
| `platform.initStart` | The environment starts |
| `platform.initReport` | The server listens, or fails to, with the error type of the `502` page. A server that does not listen or an app that fails at import also gets an `ERROR` event that says so, unless `applicationLogLevel` is `FATAL` |
| `platform.invokeStart` | Right after a successful `initReport` |
| `platform.requestComplete` | At `DEBUG`, for each request the environment took, with the status, the body bytes and the error type of a `503` or `504` page |
| `platform.invokeSummary` | Every 60 seconds from `invokeStart`, and once more when the environment ends |
| `platform.invokeReport` | When the environment ends, as a failure with `Runtime.Exit` or `Runtime.ExitError` when the process exited on its own |
| `platform.spinDownStart`, `platform.spinDownReport` | When the environment stops, after a failed init too. A process still running one second after `SIGTERM` is a `Runtime.SpinDownTimeout` failure |
| `platform.logsDropped` | Before the next event, after events were dropped because more than 10,000 were waiting |

CPU time and memory come from the container's cgroup, read at each report. Floci cannot read them once the container has stopped, so the CPU time since the last reading is lost when the process exits on its own, an init that fails because the process exited reports neither, and `spinDownReport` has no CPU time. AWS reports the memory of its whole environment, so its numbers are higher.

## Metrics

Floci publishes the [Web Function metrics](https://docs.aws.amazon.com/lambda/latest/dg/web-functions-monitoring-metrics.html) to CloudWatch in the function's account, in the region that served. `AWS/Lambda/Web` also goes to the home region, where AWS centralizes it, and `AWS/Lambda/Web/Usage` does not, as on AWS:

| Namespace | Metrics | Dimensions | Datapoints |
| --- | --- | --- | --- |
| `AWS/Lambda/Web` | `ExecutedRequests` | `FunctionName`, `EndpointName` | 1 for each request that reached an environment, also one answered with `Runtime.OriginRequestError` or `Runtime.RequestTimeout` |
| `AWS/Lambda/Web` | `HTTPResponseStatusClass4XX`, `HTTPResponseStatusClass5XX` | `FunctionName`, `EndpointName` | 1 for each answer in the class, also the platform pages. The `400`, `404` and `505` pages are not counted, as on AWS. An answer cut after its head counts under the status it began with |
| `AWS/Lambda/Web` | `EnvironmentActiveTime`, `EnvironmentCPUTime`, `EnvironmentMaxMemoryUsage`, `EnvironmentConcurrentRequests`, `ResponseBytes` | `FunctionName` | One per environment at `initReport`, also when the init fails, and one with each `invokeSummary`, for the time since the previous one |
| `AWS/Lambda/Web/Usage` | `ExecutedRequests`, `EndpointActiveEnvironments` | `FunctionName`, `EndpointName`, `ExecutionRegion` | One for each request that reached an environment. `EndpointActiveEnvironments` is the number of environments of the endpoint, those that are starting included |
| `AWS/Lambda/Web/Usage` | `EndpointRequestThrottles`, `EndpointActiveEnvironmentThrottles` | `FunctionName`, `EndpointName`, `ExecutionRegion` | 1 for each `429` of the `rateLimit` or of `maxEnvironments` |

The account quotas are not applied, so `AccountRequestThrottles` and `AccountActiveVCPUArm64Throttles` never appear. AWS starts to centralize another region some minutes after the endpoint is `Active`, and the datapoints from before that never reach the home region. Floci centralizes every datapoint at once. CPU time and memory have the gaps of the platform events. The datapoints are stored on their own thread, and when 10,000 writes are waiting new ones are dropped with a warning in Floci's log.

## CloudFormation

`AWS::Lambda::WebFunction`, `AWS::Lambda::WebFunctionRevision` and `AWS::Lambda::WebFunctionEndpoint` provision through the resource registry. `Ref` of each is its ARN. Every property of a revision is createOnly, so a changed revision mints a new one and the displaced revision is deleted once the update commits. An endpoint keeps its identity while its function, name, type and regions stay the same. A changed type or region list under the same name is refused the way CloudFormation refuses to replace a custom-named resource. `Fn::GetAtt` on `RegionalEndpoints` is not supported yet.

CloudFormation has no `AutoDeploymentMode` property, so an endpoint it creates is always `Disabled` and needs `RevisionWeights`, as on AWS. Name the revision with `Fn::GetAtt` so a replaced revision moves the endpoint with it. A stack update that fails after an in-place change to a function's tags or an endpoint's configuration puts the previous values back.
