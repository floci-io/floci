# Design: resource policy enforcement

Status: contract only. This document changes no behaviour. It records what AWS does for the
resource-policy cases a CDK application relies on, what Floci does today, and the follow-up units
that would close the gap. Every AWS result in the matrix and the response shapes was measured on
2026-10-02 (see [Evidence](#evidence)).

## Why

CDK applications grant access through resource policies as often as through identity policies: SQS
queue policies with conditions on principal tags, Lambda permissions for named roles and for API
Gateway, API Gateway REST resource policies that allow named roles and deny everyone else, and AWS
IoT policies on device certificates. Floci stores these documents but evaluates only S3 bucket
policies and, for IoT, `iot:Connect`. With IAM enforcement off, the default, the IAM request filter evaluates no policy. Only service-specific checks, such as `iot:Connect`, AppSync IAM auth and S3 bucket policies under `FLOCI_SERVICES_S3_ENFORCE_AUTH`, still apply, so most calls AWS denies by policy pass.
With enforcement on, Floci denies calls that only a resource policy grants and still allows calls a
resource policy denies.

## Layers

| Layer | AWS | Floci today | Switch | Owning code |
|---|---|---|---|---|
| Authentication | Every signed call carries a known credential. | With enforcement on, an unknown access key is refused in each protocol's vocabulary and an unsigned RPC call gets `403 MissingAuthenticationToken`; the `test` key and an unsigned REST call pass. See [IAM bypass rules](../services/iam.md#bypass-rules). | `FLOCI_SERVICES_IAM_ENFORCEMENT_ENABLED` | `IamEnforcementFilter` |
| SigV4 verification | Every signed call's signature is verified. | Per service, no global verifier: S3 header and presigned signatures, API Gateway `AWS_IAM` methods and routes, AppSync IAM auth, the ElastiCache and RDS IAM auth tokens. Elsewhere the signature is not checked. | `FLOCI_SERVICES_S3_ENFORCE_AUTH` and `FLOCI_AUTH_VALIDATE_SIGNATURES` for S3; always on for API Gateway `AWS_IAM` | `S3HeaderSignatureFilter`, `PreSignedUrlFilter`, `ExecuteApiSigV4Authorizer`, `IamAuthValidator`, `SigV4RequestValidator` |
| Identity policies | Evaluated on every authenticated call. | Evaluated for Query and JSON calls and for the REST routes `IamActionRegistry` maps (S3, Lambda, RDS Data, API Gateway management, SES, Kinesis). The API Gateway execute path is not evaluated: a signed caller with no policy at all is let through (measured, S3.2). | `FLOCI_SERVICES_IAM_ENFORCEMENT_ENABLED` | `IamEnforcementFilter`, `IamPolicyEvaluator` |
| Resource policies | Evaluated with the identity policies. In the same account either may allow, across accounts both must, and an explicit `Deny` in either wins. | S3 bucket policies only, through `S3ResourcePolicyProvider` under the IAM flag and in `S3Service` under `FLOCI_SERVICES_S3_ENFORCE_AUTH`. Other resource policies, among them SQS queue, SNS topic, Lambda function, API Gateway REST, EventBridge bus, KMS key, Secrets Manager and ECR repository policies, are stored and returned, never evaluated. | `FLOCI_SERVICES_IAM_ENFORCEMENT_ENABLED` | `ResourcePolicyProvider` |
| IoT device authorization | `iot:Connect` at connect, `iot:Subscribe`, `iot:Publish` and `iot:Receive` per packet, against the policies attached to the certificate. | TLS listener: a registered, `ACTIVE` certificate inside its validity and `iot:Connect` for the client id. `Subscribe`, `Publish` and `Receive` are not evaluated. Plaintext 1883 and the `/mqtt` WebSocket bridge admit every client. | `FLOCI_TLS_ENABLED` opens the TLS listener; the IAM flag plays no part | `IotMqttBrokerService`, `IotService.isConnectAllowed` |

## Measured matrix

Callers are role sessions from `AssumeRole`. `<role-a>` is tagged `env=devlocal, fd=ingestion`,
`<role-b>` and `<role-c>` `env=devlocal, fd=egress`. Only `<role-c>` has an identity policy (Allow
`sqs:SendMessage` on the queue). AWS response shapes R1 to R9 are listed under the matrix.

S1 queue policy: Allow `Principal: "*"` `sqs:SendMessage` on the queue ARN with `StringEquals`
`aws:PrincipalTag/env = devlocal` and `aws:PrincipalTag/fd = ingestion`. In S1.4 and S1.5 a second
statement denies `sqs:SendMessage` to `Principal: "*"` with `ArnEquals aws:PrincipalArn = <role-c>`.

| ID | Case | AWS | Floci, enforcement off | Floci, enforcement on |
|---|---|---|---|---|
| S1.1 | `<role-a>` `SendMessage`, tags match, no identity policy | 200, JSON and Query | 200 | JSON `400 AccessDeniedException`, Query `403 AccessDenied`. False negative |
| S1.2 | `<role-b>` `SendMessage`, tags differ, no identity policy | `403 AccessDenied`, R1 and R3 | 200. False positive | JSON `400 AccessDeniedException`, Query `403 AccessDenied`. Right decision, wrong shape |
| S1.3 | `<role-c>` `SendMessage`, identity Allow | 200, JSON and Query | 200 | JSON 200, Query `403 AccessDenied`. False negative on Query |
| S1.4 | `<role-c>` after the queue-policy Deny | `403 AccessDenied`, R2 and R3 | 200. False positive | JSON 200. False positive. Query `403`, for the S1.3 reason |
| S1.5 | `<role-a>` after the queue-policy Deny | 200 | 200 | JSON `400`, Query `403`. False negative |
| S2.1 | `<role-b>` `Invoke`, no function permission | `403 AccessDeniedException`, R4 | 200. False positive | `403 AccessDeniedException` |
| S2.2 | `<role-b>` `Invoke` after `AddPermission` with `Principal` = `<role-b>` ARN | 200 | 200 | `403 AccessDeniedException`. False negative |
| S2.3 | `<role-a>` `Invoke`, not in the function policy | `403 AccessDeniedException`, R4 | 200. False positive | `403 AccessDeniedException` |
| S3.1 | `<role-a>` signed `GET /x` (`AWS_IAM`, MOCK) | 200 | 200 | 200 |
| S3.2 | `<role-b>` signed `GET /x` | `403`, R5 | 200. False positive | 200. False positive |
| S3.3 | Unsigned `GET /x` | `403`, R7 | `403`, R7 | `403`, R7 |
| S3.4 | Anonymous `GET /open` (`NONE`) from the denied address | `403`, R6 | 200. False positive | 200. False positive |
| S4.1 | `GET /a`, `AWS_PROXY` to Lambda, permission `SourceArn` `.../*/GET/a` | 200 | 200 | 200 |
| S4.2 | `GET /b`, same function, no permission matches | `500`, R8 | 200. False positive | 200. False positive |
| S5.1 | Subscribe `<p>/ok` | SUBACK granted QoS 1 | Same as AWS | Same as AWS |
| S5.2 | Subscribe `<p>/nope` | R9 | SUBACK granted QoS 1. False positive | Same as off |
| S5.3 | Publish QoS 1 to `<p>/ok`, subscribed to it | PUBACK success, message delivered | Same as AWS | Same as AWS |
| S5.4 | Publish QoS 1 to `<p>/nope` | R9 | PUBACK success. False positive | Same as off |
| S5.5 | Publish QoS 0 to `<p>/nope` | R9 | Accepted. False positive | Same as off |
| S5.6 | Subscribed to `<p>/+`, publish to `<p>/other` (Publish and Subscribe allowed, Receive not) | PUBACK success, message not delivered | Delivered. False positive | Same as off |

S3 is a REST API with a resource policy shaped like the one a CDK OpenAPI builder emits: Allow
`Principal: {"AWS": [<role-a>]}` `execute-api:Invoke` on `execute-api:/*/GET/x`, Deny `Principal: "*"`
on the same resource with `ForAllValues:ArnNotEquals aws:PrincipalArn [<role-a>]`, Allow
`Principal: "*"` on `execute-api:/*/GET/open` and Deny `Principal: "*"` there with `IpAddress
aws:SourceIp` set to the caller's address. S4 is a second REST API. S5 runs on the TLS port with a
certificate whose policy allows `iot:Connect` on `client/<p>-c*`, `iot:Publish` and `iot:Receive` on
`topic/<p>/ok` and `iot:Subscribe` on `topicfilter/<p>/ok`; S5.6 uses a second policy version that
also allows `iot:Publish` on `topic/<p>/other` and `iot:Subscribe` on `topicfilter/<p>/+`.

Two Floci findings beyond the missing evaluation:

- The Query path builds the wrong resource ARN. `ResourceArnBuilder` reads `QueueUrl` from the URI
  query string only, so a form-encoded Query call is checked against
  `arn:aws:sqs:<region>:<account>:*` instead of the queue. An identity Allow that names the queue
  therefore matches over JSON and not over Query (S1.3). The same Query call with `QueueUrl` also
  in the URI query string returns 200.
- `aws:PrincipalTag/<key>` is not placed in the request context, so a `StringEquals` condition on it never
  matches (S1.1, S1.5).

### AWS response shapes

Account `111122223333` replaces the real one. `<session>` is the role session name.

- **R1** SQS JSON, no policy grants: `403`, header `x-amzn-query-error: AccessDenied;Sender`, body
  `{"__type":"com.amazon.coral.service#AccessDeniedException","Message":"User: arn:aws:sts::111122223333:assumed-role/<role-b>/<session> is not authorized to perform: sqs:sendmessage on resource: arn:aws:sqs:eu-west-1:111122223333:<queue> because no identity-based policy allows the sqs:sendmessage action"}`.
  The SDK reports error code `AccessDenied`, taken from the header.
- **R2** SQS JSON, queue-policy Deny: as R1, the message ending
  `with an explicit deny in a resource-based policy`.
- **R3** SQS Query: `403`,
  `<ErrorResponse xmlns="http://queue.amazonaws.com/doc/2012-11-05/"><Error><Type>Sender</Type><Code>AccessDenied</Code><Message>...</Message><Detail/></Error><RequestId>...</RequestId></ErrorResponse>`,
  the message as in R1 or R2.
- **R4** Lambda `Invoke`: `403`, header `x-amzn-ErrorType: AccessDeniedException`, body
  `{"Message":"User: arn:aws:sts::111122223333:assumed-role/<role-b>/<session> is not authorized to perform: lambda:InvokeFunction on resource: arn:aws:lambda:eu-west-1:111122223333:function:<function> because no identity-based policy allows the lambda:InvokeFunction action"}`.
- **R5** `execute-api`, signed caller, resource-policy Deny: `403`, header
  `x-amzn-ErrorType: AccessDeniedException`, body
  `{"Message":"User: arn:aws:sts::111122223333:assumed-role/<role-b>/<session> is not authorized to perform: execute-api:Invoke on resource: arn:aws:execute-api:eu-west-1:********3333:<api-id>/v1/GET/x with an explicit deny in a resource-based policy"}`.
  The account in the resource ARN is masked to its last four digits, and the key is `Message`.
- **R6** `execute-api`, anonymous caller, resource-policy Deny: as R5 with `User: anonymous` and
  resource `.../v1/GET/open`.
- **R7** `execute-api`, unsigned call to an `AWS_IAM` method: `403`, header
  `x-amzn-ErrorType: MissingAuthenticationTokenException`, body `{"message":"Missing Authentication Token"}`.
- **R8** API Gateway invoking a Lambda integration the function policy does not allow: `500`, header
  `x-amzn-ErrorType: InternalServerErrorException`, body `{"message": "Internal server error"}`.
- **R9** IoT on 8883:

  | Denied action | MQTT 3.1.1 | MQTT 5 |
  |---|---|---|
  | `SUBSCRIBE` | Connection closed, no SUBACK | SUBACK reason `0x87` (Not authorized), session kept |
  | `PUBLISH` QoS 1 | Connection closed, no PUBACK | PUBACK reason `0x87`, session kept |
  | `PUBLISH` QoS 0 | Connection closed | `DISCONNECT` reason `0x87`, reason string `DISCONNECT:Client is not authenticated/authorized to send the message:<uuid>` |
  | `Receive` | Message not delivered to that subscriber, publisher unaffected | Same |

Floci's denials today, with enforcement on: SQS JSON `400`
`{"__type":"AccessDeniedException","message":"User is not authorized to perform: sqs:SendMessage"}`
without `x-amzn-query-error`, so the SDK reports `AccessDeniedException`; SQS Query `403`
`<ErrorResponse>` without the namespace and without `<Detail/>`; Lambda `403`
`{"__type":"AccessDeniedException","message":"User is not authorized to perform: lambda:InvokeFunction"}`
without `x-amzn-ErrorType`.

`GetRestApi` on AWS returns the stored policy rewritten: `execute-api:/*/GET/x` becomes
`arn:aws:execute-api:eu-west-1:111122223333:<api-id>/*/GET/x` and a one-element principal list
becomes a string. Floci returns the document as sent.

## Defaults

- Enforcement stays opt-in through `FLOCI_SERVICES_IAM_ENFORCEMENT_ENABLED`. Every unit below
  evaluates only when that flag is on. With it off, behaviour stays exactly as the "enforcement off"
  column.
- U5 is gated by the same flag although `iot:Connect` is checked on the TLS port without it today,
  so a device whose policy allows only `iot:Connect` keeps working by default.
- Plaintext MQTT on 1883 and the `/mqtt` WebSocket bridge stay permissive.
- Deliveries Floci performs on behalf of a service principal stay ungated: SNS to SQS, EventBridge
  to SQS and Lambda, S3 notifications to SNS, SQS and Lambda, and IoT rule actions to Lambda. On AWS
  each needs the target's resource policy to allow the service principal, usually with
  `aws:SourceArn` or `aws:SourceAccount`. That was not measured here and is out of scope.
- `aws:SecureTransport` stays absent from the request context. A CDK `enforceSSL` statement
  (`Deny` when `"Bool": {"aws:SecureTransport": "false"}`) therefore does not apply, which matches a
  TLS client on AWS and keeps plain-HTTP clients of Floci working.

## Follow-up units

One pull request per unit. Each is independent of the others.

### U1: SQS queue policy evaluation

- A `ResourcePolicyProvider` for credential scope `sqs` returns the queue's `Policy` attribute and
  owning account. `IamEnforcementFilter` already combines it with the identity policies: in the same
  account identity or resource may allow, an explicit `Deny` wins.
- `ResourceArnBuilder` reads `QueueUrl` from the form body of a Query call, so both protocols check
  the queue ARN.
- The request context carries `aws:PrincipalTag/<key>`: the role's tags for a role session, the
  user's tags for a user. This also affects identity policies with a `StringEquals` condition on
  the key, which never matches today.
- A denial reproduces R1 or R2 over JSON and R3 over Query, including the `403`, the
  `x-amzn-query-error` header and the AWS message.
- Positive: S1.1, S1.3 and S1.5 return 200 over JSON and Query. A queue with an `enforceSSL` Deny
  still accepts a plain-HTTP call.
- Negative: S1.2 returns R1 and R3, S1.4 returns R2 and R3.
- Acceptance: an SDK integration test with the Java SDK v2 `SqsClient` and a Query-protocol test
  cover every case. The suite with enforcement off is unchanged.

### U2: Lambda function policy for `lambda:InvokeFunction`

- A `ResourcePolicyProvider` for credential scope `lambda` returns the statements `AddPermission`
  stored on the function. Only `AWS` principals (an account, a role or a user) match an IAM caller;
  service-principal statements never do, which is the evaluator's existing rule.
- A denial reproduces R4.
- Positive: S2.2 returns 200, and a caller with an identity Allow and no permission still returns
  200.
- Negative: S2.1 and S2.3 return R4, with the `x-amzn-ErrorType` header Floci omits today.
- Acceptance: an SDK integration test with `LambdaClient.invoke` covers these cases. Qualified
  invocations (versions, aliases) were not measured and stay as today.

### U3: API Gateway REST `execute-api:Invoke` resource policy

- `ApiGatewayExecuteController` evaluates the REST API's `policy` after `ExecuteApiSigV4Authorizer`
  has authenticated an `AWS_IAM` call, and for an anonymous call to a `NONE` method. The resource is
  `arn:<partition>:execute-api:<region>:<account>:<api-id>/<stage>/<METHOD>/<path>`, with
  `execute-api:/` in the stored policy expanded to that prefix. The context carries `aws:PrincipalArn`
  for a signed caller and `aws:SourceIp` for every caller.
- Only an API with a resource policy is evaluated. An `AWS_IAM` method of an API without one keeps
  today's authentication-only behaviour (on AWS it then needs an identity Allow, not measured here).
- For a signed caller the resource policy is combined with the caller's identity policies under the
  same-account rule `IamEnforcementFilter` applies to S3: either may allow, an explicit `Deny` wins.
  An anonymous caller needs a resource-policy Allow.
- A denial reproduces R5 or R6. Authentication failures keep their current responses, so S3.3 still
  returns R7. Whether a customised gateway response applies to R5 and R6 was not measured.
- Positive: S3.1, and an anonymous `GET /open` from an address outside the Deny range, return 200.
- Negative: S3.2 returns R5, S3.4 returns R6.
- Acceptance: an integration test signs with a role session from `AssumeRole`, as the SDK does.
  HTTP APIs (v2) have no resource policy on AWS and are untouched.

### U4: Lambda permission check when API Gateway invokes a Lambda integration

- Before `ApiGatewayExecuteController` invokes a Lambda integration, the function policy must allow
  `lambda:InvokeFunction` to `apigateway.amazonaws.com` with an `aws:SourceArn` matching
  `arn:<partition>:execute-api:<region>:<account>:<api-id>/<stage>/<METHOD>/<resource-path>`.
  `IamPolicyEvaluator.evaluateServicePrincipal` exists and has no caller yet.
- A denial reproduces R8 and never reaches the function.
- Positive: S4.1 returns 200. Negative: S4.2 returns R8.
- An integration that names a `credentials` role is authorized by that role on AWS. It was not
  measured and is out of this unit.

### U5: IoT `Publish`, `Subscribe` and `Receive` on the TLS port

- `IotMqttBrokerService` evaluates `iot:Subscribe` on `topicfilter/<filter>`, `iot:Publish` on
  `topic/<topic>` and `iot:Receive` on `topic/<topic>` for each delivery, against the certificate's
  attached policies with the policy variables `IotService.isConnectAllowed` already resolves. Only
  sessions admitted on the TLS listener are evaluated.
- A denial reproduces R9 for the client's protocol version.
- Positive: S5.1 and S5.3.
- Negative: S5.2, S5.4, S5.5 and S5.6, each over MQTT 3.1.1 and MQTT 5.
- Acceptance: an integration test with an MQTT client over TLS and a certificate from
  `CreateKeysAndCertificate`. A `SUBSCRIBE` with several filters, one of them denied, was not
  measured.

## Out of scope

- A general policy framework, request throttling, encryption at rest.
- SNS topic, EventBridge bus, KMS key, Secrets Manager and ECR repository policy evaluation.
- Service-principal deliveries, see [Defaults](#defaults).
- API Gateway HTTP and WebSocket APIs, private APIs and `aws:SourceVpce`, Lambda authorizers and
  function URL `AuthType`.
- IoT custom authorizers, thing-group policies, and topic authorization on 1883 and `/mqtt`.
- Organization condition keys such as `aws:PrincipalOrgID`, and general SigV4 verification.

## Evidence

Measured on 2026-10-02 in `eu-west-1` with an administrator SSO session, AWS CLI 2.37.7,
boto3/botocore 1.43.106 and paho-mqtt 2.1.0. Every resource was deleted afterwards.

- SQS `SendMessage` through the boto3 client (JSON protocol) and as a SigV4-signed form POST
  (Query protocol). Queue policy changes were given 70 seconds to propagate.
- Lambda `Invoke` and `AddPermission` through boto3. The function was a Python 3.12 handler
  returning `{"statusCode": 200, "body": "ok"}`.
- The raw R1 and R4 headers and bodies were captured with SigV4-signed HTTP calls from a role with
  no policy. R4 came from invoking a function name that does not exist: AWS answers it with the
  authorization error, not `ResourceNotFoundException`.
- API Gateway REST APIs created with boto3, stage `v1`; calls signed with botocore `SigV4Auth` for
  `execute-api`, or sent unsigned.
- IoT: `CreateKeysAndCertificate`, `CreatePolicy`, `AttachPolicy` and `AttachThingPrincipal` with the
  CLI, then MQTT 3.1.1 and MQTT 5 clients on 8883, one fresh connection per step and no automatic
  reconnect.
- Floci built from `upstream/main` (`0460e51a6`) with `./mvnw -q package -DskipTests` and run with
  `java -jar target/quarkus-app/quarkus-run.jar`, `FLOCI_TLS_ENABLED=true` and
  `FLOCI_SERVICES_IOT_MQTT_AUTO_START=true`, once with the defaults and once with
  `FLOCI_SERVICES_IAM_ENFORCEMENT_ENABLED=true`. Resources were created with the `test` key and
  every call was made as a role session from Floci's `AssumeRole`, account `000000000000`.
