# AWS IoT Core

Floci's IoT service emulates the AWS IoT Core control plane, IoT Data shadow APIs, and MQTT data-plane behavior used by local device and SDK tests.

## MVP 1 Coverage

Status: complete for the local emulator slice.

Supported MVP 1 behavior:

- Thing CRUD with idempotent identical `CreateThing`, duplicate-conflict semantics, `UpdateThing.expectedVersion`, and list pagination.
- Certificate basics: `CreateKeysAndCertificate`, `CreateCertificateFromCsr`, `DescribeCertificate`, `ListCertificates`, `UpdateCertificate`, and `DeleteCertificate` with active/attached delete constraints. `CreateKeysAndCertificate` returns a real X.509 client certificate issued by Floci's local CA (`GET /_floci/ca.pem`) with a fresh RSA 2048 key pair, valid until 2049-12-31T23:59:59Z as on AWS, and `certificateId` is the SHA-256 of the certificate's DER encoding as on AWS. `DescribeCertificate` reports `validity` and `certificateMode`. `CreateCertificateFromCsr` signs the request's public key (RSA of at least 2048 bits, or EC on P-256, P-384 or P-521) with the same CA and, as on AWS, returns no private key.
- Policy basics: `CreatePolicy`, `GetPolicy`, `ListPolicies`, `DeletePolicy`, policy version lifecycle, `AttachPolicy`, `DetachPolicy`, `ListAttachedPolicies`, and `ListTargetsForPolicy`.
- Thing principal basics: `AttachThingPrincipal`, `DetachThingPrincipal`, `ListThingPrincipals`, and `ListPrincipalThings`.
- Tags for things, certificates, policies, and topic rules.
- IoT Data retained messages: retained `Publish`, `GetRetainedMessage`, and paginated `ListRetainedMessages`.
- Shadow null-delete and version-conflict behavior for HTTP and shared service paths.
- Topic rule duplicate/delete/replace semantics, plus `republish`, `sqs`, `sns`, `s3`, `dynamoDBv2`, `kinesis`, `lambda`, `firehose`, and `cloudwatchLogs` action dispatch.

Current MVP 1 limitations:

- The MQTT listener does not verify device certificates yet.
- MQTT auth remains permissive; certificate and policy resources are modeled for provisioning compatibility, not enforced as broker authorization yet.
- Rules evaluate the SQL subset described under [Rule SQL](#rule-sql); substitution templates remain follow-up scope.

## MVP 2 Coverage

Status: implemented for the current SDK compatibility slice.

Supported MVP 2 behavior:

- Thing types: `CreateThingType`, `DescribeThingType`, `ListThingTypes`, `UpdateThingType`, `DeprecateThingType`, and `DeleteThingType` with typed `CreateThing` association and in-use delete protection.
- Static thing groups: `CreateThingGroup`, `DescribeThingGroup`, `ListThingGroups`, `UpdateThingGroup`, `DeleteThingGroup`, `AddThingToThingGroup`, `RemoveThingFromThingGroup`, `ListThingsInThingGroup`, and `ListThingGroupsForThing`.
- Jobs control plane: `CreateJob`, `DescribeJob`, and `ListJobs`, including thing ARN targets and static thing group targets.
- Jobs data plane: pending-job listing, `StartNextPendingJobExecution`, `DescribeJobExecution`, and `UpdateJobExecution` with version conflicts and terminal-state checks.
- Endpoint discovery accepts `iot:Jobs` and `iot:CredentialProvider` in addition to IoT Data endpoint types.
- MQTT clients can use QoS 1 subscribe/publish paths with broker PUBACK and delivery behavior.
- IoT Data connection APIs for live MQTT sessions: `GetConnection`, `DeleteConnection`, `ListSubscriptions`, and `SendDirectMessage`.
- `DeleteConnection` closes active MQTT client sessions through the embedded broker and optionally purges broker session state for `cleanSession=true`.
- IoT rules can dispatch matching payloads to SQS, SNS, S3, DynamoDB v2, Kinesis, Lambda, Kinesis Data Firehose, CloudWatch Logs, and MQTT republish targets.

Current MVP 2 limitations:

- `DeleteConnection.preventWillMessage` is accepted for SDK request compatibility, but the embedded broker does not expose selective Last Will suppression.
- HTTP IoT Data `Publish` still treats QoS and MQTT5 metadata as compatibility inputs only; those properties are not fully forwarded or persisted yet.
- `SendDirectMessage` publishes to the requested MQTT topic through the embedded broker. Unlike AWS IoT Core, it does not yet bypass subscription matching to deliver to a client that is not subscribed to that topic.
- `GetConnection` and `ListSubscriptions` report live in-memory broker state only; offline persistent session subscription reporting is not modeled yet.
- Jobs reserved MQTT topics remain follow-up scope; Jobs Data HTTP APIs are implemented first.
- Dynamic thing groups, fleet indexing, job rollouts, cancellations, documents from S3, and advanced job scheduling are not yet modeled.

## Domain Configurations

Status: control plane only.

`CreateDomainConfiguration`, `DescribeDomainConfiguration`, `UpdateDomainConfiguration`, `DeleteDomainConfiguration` and `ListDomainConfigurations` are served on the REST-JSON paths the AWS SDKs use, with the AWS shapes and error codes:

- A new configuration is `ENABLED` and `CUSTOMER_MANAGED`, its server certificate is reported `VALID`, and its ARN carries the short id AWS appends (`domainconfiguration/<name>/<id>`). A configuration created without a domain name has the `ENDPOINT` domain type.
- `UpdateDomainConfiguration` changes the status, the authorizer (or removes it with `removeAuthorizerConfig`), the TLS, server certificate and client certificate settings, the authentication type and the application protocol.
- `DeleteDomainConfiguration` refuses an `ENABLED` configuration with `InvalidRequestException`; disable it first, as on AWS.
- `ListDomainConfigurations` filters by `serviceType` and pages with `marker` and `pageSize`.
- Tags work through `TagResource`, `UntagResource` and `ListTagsForResource` on the configuration ARN.
- CloudFormation provisions `AWS::IoT::DomainConfiguration` through the same operations; see the CloudFormation service page for the attribute list.
- With TLS enabled, a customer-managed configuration (`domainName` set) adds its domain to Floci's server certificate as soon as it is created, so `https://<domain>` verifies without a restart; see [TLS](../configuration/tls.md) for the accepted suffixes. A configuration without a domain name registers nothing.
- The four AWS-managed configurations every account has (`iot:Data-ATS`, `iot:Data`, `iot:CredentialProvider`, `iot:Jobs`) exist in every region without being created: `AWS_MANAGED`, `ENABLED`, no server certificate, and the address `DescribeEndpoint` returns as their domain name. They can be updated and tagged but not deleted, as on AWS.

Current limitations:

- A custom domain does not change where the broker listens. `DescribeEndpoint` keeps returning Floci's own address, and pointing DNS at the emulator is outside its scope.
- Server certificate ARNs are stored as given and reported `VALID`; they are not checked against ACM.

## MQTT Broker

Status: complete.

Floci uses Vert.x MQTT as the embedded MQTT protocol server. `IotMqttBrokerService` owns the broker lifecycle, live session registry, subscription registry, MQTT fan-out, and the bridge into IoT service behavior.

Broker scope:

- Target real AWS IoT/device SDK style MQTT clients, not only handcrafted packet tests.
- Support MQTT v3 and MQTT 5 CONNECT handling used by local compatibility tests.
- Support QoS 0 and QoS 1 publish/subscribe behavior for the local AWS IoT slice.
- Serve MQTT over TLS on 8883 next to plaintext 1883 when TLS is enabled; verifying device certificates (mutual TLS) is follow-up scope.
- Keep MQTT authorization permissive for now, but leave room for a later pluggable IoT certificate and policy authorizer.
- Keep MQTT broker logging minimal.
- Validate the relevant IoT compatibility tests against the native binary before considering the phase complete.

### MQTT over TLS

With `FLOCI_TLS_ENABLED=true` the broker also listens on `FLOCI_SERVICES_IOT_MQTT_TLS_PORT` (default `8883`, the port AWS IoT uses for X.509 device connections; `0` disables it). It presents the same certificate as the HTTPS endpoint, issued by the Floci CA, over TLS 1.2 or 1.3. A device connects with `ssl://localhost:8883` trusting `GET /_floci/ca.pem`, as it would trust Amazon Root CA 1 against AWS IoT. A custom domain added to the server certificate at runtime (see [TLS](../configuration/tls.md#custom-domains-learned-at-runtime)) is served on 8883 from the next connection on, without a restart.

The listener asks for a client certificate and accepts the connection whether or not one is presented and whoever signed it: 8883 is as permissive as 1883 until certificate verification lands. Sessions, subscriptions and reserved topics are shared with the plaintext listener, so a client id connecting on one port replaces its session on the other, as on AWS. Both listeners start together: with the first IoT API call, or at boot with `FLOCI_SERVICES_IOT_MQTT_AUTO_START=true`.

```bash
docker run -e FLOCI_TLS_ENABLED=true -e FLOCI_SERVICES_IOT_MQTT_AUTO_START=true -p 4566:4566 -p 8883:8883 floci/floci:latest
curl http://localhost:4566/_floci/ca.pem -o ca.pem
mosquitto_sub -h localhost -p 8883 --cafile ca.pem -t 'devices/#'
```

## Reserved Topics

AWS IoT reserved topics such as `$aws/things/{thingName}/shadow/update` are service control topics, not ordinary application topics. Floci should handle these publishes by invoking IoT shadow behavior and then publishing the AWS-compatible response topics through the broker.

Required phase 7 reserved-topic behavior:

- Classic unnamed shadows: `$aws/things/{thingName}/shadow/update`, `get`, and `delete`.
- Named shadows: `$aws/things/{thingName}/shadow/name/{shadowName}/update`, `get`, and `delete`.
- Shadow response topics: `accepted`, `rejected`, `documents`, and `delta` where applicable.
- Basic Ingest and Jobs topic families are desired follow-up scope, but should not block restoring the broker unless explicitly pulled into the implementation phase.

Reserved request topics are handled by Floci before normal MQTT fan-out. The original `$aws/...` request publish is not routed as an application message; generated accepted, rejected, documents, and delta responses are published back through `IotMqttBrokerService.publish(...)` so matching MQTT subscribers receive broker-native messages.

Implementation notes:

- Vert.x MQTT handles the wire protocol and connection lifecycle.
- Floci-owned session, subscription, and retained-message state drives local AWS IoT compatibility behavior.
- Normal client publishes call `IotService.publish(...)` so retained-message storage, event recording, and rule evaluation remain service-owned.
- Internal broker publishes fan out only to MQTT subscribers and do not recursively evaluate IoT topic rules.

Current accepted limitation:

- Certificate and policy authorization are not enforced at the broker layer yet, on either port.
- Persistent offline sessions are not modeled yet.
- QoS 2 and advanced MQTT 5 property semantics remain follow-up scope.

## Implementation Shape

The MQTT integration should keep service behavior separated from broker mechanics:

- `IotMqttBrokerService` owns Vert.x MQTT lifecycle and broker-native publish helpers.
- The broker publish handler detects AWS IoT reserved topics.
- IoT reserved-topic handling lives in IoT service code or a focused reserved-topic handler, not in packet parsing code.
- AWS-generated shadow responses are published back through `IotMqttBrokerService.publish(...)` so regular MQTT subscribers receive broker-native messages.

## Phase 7 Completion Criteria

Phase 7 completion criteria:

- Vert.x MQTT is the active MQTT broker implementation.
- Reserved shadow topics are handled from the broker publish handler.
- AWS-generated shadow responses are published through the broker service, not by manually writing MQTT packets.
- MQTT 5 CONNECT and publish/subscribe behavior are covered by automated tests.
- Classic unnamed shadow MQTT topics are covered by automated tests.
- Named shadow MQTT topics are covered by automated tests.
- Relevant IoT compatibility tests pass against the native binary.

## Rules Engine

Status: complete for the MVP 2 action slice.

Phase 8 adds stored IoT topic rules and dispatches matching IoT publishes to rule actions.

Supported rule behavior:

- `CreateTopicRule`, `GetTopicRule`, `ListTopicRules`, `EnableTopicRule`, `DisableTopicRule`, and `DeleteTopicRule` through AWS SDK-compatible IoT control-plane paths.
- Rule SQL parsing and evaluation for the subset described under [Rule SQL](#rule-sql): the `SELECT` projection,
  the `FROM` topic filter, and the `WHERE` predicate.
- MQTT-style topic filter matching for exact topics, `+`, and terminal `#`.
- IoT Data `Publish` and MQTT publishes use the same rule dispatch path.
- Rule matching is region-scoped: an IoT Data `Publish` evaluates the rules of the region named by its SigV4 credential, and a rule's actions target the rule's own region.
- Publishes that carry no region — MQTT, or an IoT Data `Publish` whose `Authorization` header is absent or not SigV4 — are evaluated against every region's rules.
- Actions receive the projected document, which is the payload itself for a statement that selects only `*`.
- `republish` action republishes to another MQTT topic through `IotMqttBrokerService`.
- `sqs` action sends to an SQS queue through Floci's SQS service boundary.
- `sns` action publishes to an SNS topic through Floci's SNS service boundary.
- `s3` action writes to the configured bucket/key through Floci's S3 service boundary.
- `dynamoDBv2` action writes JSON object fields as DynamoDB attribute values through Floci's DynamoDB service boundary.
- `kinesis` action puts the document into a Kinesis stream through Floci's Kinesis service boundary.
- `lambda` action invokes the configured function ARN through Floci's Lambda service boundary.
- `firehose` action puts the document into a Kinesis Data Firehose delivery stream through Floci's Firehose service boundary, with `separator` appended to each record; the separator must be `\n`, `\t`, `\r\n` or `,`, as the API model requires, or the rule is rejected with `InvalidRequestException`. With `batchMode`, a JSON array document becomes one record per element.
- `cloudwatchLogs` action writes the document as a log event through Floci's CloudWatch Logs service boundary, into a log stream named after the rule that is created in `logGroupName` on first use. The log group must exist. With `batchMode`, a JSON array document becomes one event per element, and each element supplies its own `timestamp` (epoch milliseconds) and `message`, as the AWS message format for batched device logs requires; an element without them is logged as its JSON text at publish time.
- One failing action never fails the publish or the other actions of the rule. The failure is logged, and once every action ran the rule's `errorAction` receives the AWS failure document: `ruleName`, `topic`, `base64OriginalPayload` and `failures` with `failedAction`, `failedResource` and `errorMessage` per failed action.
- `GetTopicRule` returns `awsIotSqlVersion` and `errorAction` as they were given to `CreateTopicRule` or `ReplaceTopicRule`.

### Rule SQL

A rule's statement is parsed once when it is created or replaced, and once on the first publish
for rules restored from storage. The grammar Floci understands is:

```
statement  := SELECT item (',' item)* FROM '<topic filter>' [WHERE expr]
item       := '*' | operand [AS identifier]
expr       := term (OR term)*
term       := factor (AND factor)*
factor     := NOT factor | '(' expr ')' | operand [comparison operand]
comparison := '=' | '<>' | '!=' | '<' | '<=' | '>' | '>='
operand    := path | literal | call
call       := topic() | topic(<segment>) | startswith(operand, operand) | endswith(operand, operand)
path       := identifier ('.' identifier)*
literal    := 'string' | "string" | number | TRUE | FALSE | NULL
```

Semantics:

- Keywords and function names are case insensitive, field names are case sensitive.
- `topic()` is the full MQTT topic, `topic(n)` is its nth segment counting from 1.
- A select item without `AS` is written under the last segment of its path, or under the function
  name, so `topic()` becomes `topic`.
- When `*` is present, every payload field is copied first and the other select items are written
  over it. `SELECT *, topic() as topic` on a payload that already has a `topic` field therefore
  yields the MQTT topic, which is what AWS does.
- A select item whose value is undefined is left out of the document.
- A missing field, an out of range topic segment, and a function argument that cannot be converted
  are `Undefined`, as in AWS. It spreads: a comparison, `AND`, `OR` or `NOT` with an undefined
  operand is undefined, and a rule fires only when its `WHERE` is true. `endswith(clientToken, 'x')`
  therefore does not fire when the payload has no `clientToken`, and neither does
  `clientToken <> 'x'`.
- JSON `null` is a value, not `Undefined`: it equals only `NULL`, so `clientToken <> 'x'` is true
  when the field is null and undefined when it is missing.
- `=` and `<>` compare two numbers by value and anything else by type and value, so operands of
  different types are simply not equal: `level = '3'` is false and `level <> '3'` is true when
  `level` is the number 3. These follow the operator tables in the AWS IoT SQL reference.
- `<`, `<=`, `>` and `>=` convert both operands to a number. A string converts when it looks like
  one (`'10' > 9` is true); any other operand makes the comparison undefined.
- Payload numbers are read exactly, never through a double, so `9007199254740993.0`, `1e-400` and
  `0.30000000000000004` compare as written, at any size or precision, as AWS's Decimal does.
- `AND`, `OR` and `NOT` take booleans or the strings `'true'` and `'false'` in any case. Any other
  operand makes the result undefined.
- `startswith` and `endswith` convert numbers, booleans, arrays and objects to their string form
  first. A `null` or undefined argument makes the result undefined.
- String literals use single or double quotes, doubled to escape the quote itself.
- A statement that selects only `*` forwards the published bytes unchanged, so the payload does not
  have to be JSON when there is no `WHERE`. Any other statement needs a JSON object: a payload that
  is not one is logged at DEBUG and the rule does not fire.

Statements outside this grammar are not rejected. They are stored as sent and keep the behavior they
had before Floci evaluated rule SQL: the topic filter is read out of the statement and the rule fires
on every matching topic with the whole payload. Floci logs one WARN naming the rule and the token it
could not parse. Setting `floci.services.iot.rule-sql-strict` to `true`
(`FLOCI_SERVICES_IOT_RULE_SQL_STRICT`) makes `CreateTopicRule` and `ReplaceTopicRule` reject such a
statement with `SqlParseException` instead, the way AWS does. It is `false` by default.

Current limitations:

- Not evaluated: `clientid()`, `timestamp()`, `accountid()`, `principal()`, `newuuid()`, `isNull()`,
  `isUndefined()`, `encode()`, `get_thing_shadow()`, arithmetic, array indexing, `IN`, `CASE`, and
  `${}` substitution templates in action fields. A rule using any of them takes the unparsed path
  described above.
- `awsIotSqlVersion` is stored and echoed back but not acted on. The versions differ in how
  `SELECT *` treats arrays, which Floci does not model.
- Less common AWS IoT rule action types are follow-up scope.

Open follow-up scope for phase 7 unless explicitly deferred:

- Basic Ingest topics under `$aws/rules/...`.
- AWS IoT Jobs reserved topics and required job lifecycle behavior.
