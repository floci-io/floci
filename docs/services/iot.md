# AWS IoT Core

Floci's IoT service emulates the AWS IoT Core control plane, IoT Data shadow APIs, and MQTT data-plane behavior used by local device and SDK tests.

## MVP 1 Coverage

Status: complete for the local emulator slice.

Supported MVP 1 behavior:

- Thing CRUD with idempotent identical `CreateThing`, duplicate-conflict semantics, `UpdateThing.expectedVersion`, and list pagination.
- Certificate basics: `CreateKeysAndCertificate`, `CreateCertificateFromCsr`, `DescribeCertificate`, `ListCertificates`, `UpdateCertificate`, and `DeleteCertificate` with active/attached delete constraints.
- Policy basics: `CreatePolicy`, `GetPolicy`, `ListPolicies`, `DeletePolicy`, policy version lifecycle, `AttachPolicy`, `DetachPolicy`, `ListAttachedPolicies`, and `ListTargetsForPolicy`.
- Thing principal basics: `AttachThingPrincipal`, `DetachThingPrincipal`, `ListThingPrincipals`, and `ListPrincipalThings`.
- Tags for things, certificates, policies, and topic rules.
- IoT Data retained messages: retained `Publish`, `GetRetainedMessage`, and paginated `ListRetainedMessages`.
- Shadow null-delete and version-conflict behavior for HTTP and shared service paths.
- Topic rule duplicate/delete/replace semantics, plus `republish`, `sqs`, `sns`, `s3`, `dynamoDBv2`, `kinesis`, and `lambda` action dispatch.

Current MVP 1 limitations:

- Certificate CSR handling creates emulator-local certificates; it does not perform real CA signing.
- MQTT auth remains permissive; certificate and policy resources are modeled for provisioning compatibility, not enforced as broker authorization yet.
- Rules support basic topic filter extraction and action dispatch only; SQL projection, WHERE evaluation, substitutions, and error actions remain follow-up scope.

## MVP 2 Coverage

Status: implemented for the current SDK compatibility slice.

Supported MVP 2 behavior:

- Thing types: `CreateThingType`, `DescribeThingType`, `ListThingTypes`, `UpdateThingType`, `DeprecateThingType`, and `DeleteThingType` with typed `CreateThing` association and in-use delete protection.
- Static thing groups: `CreateThingGroup`, `DescribeThingGroup`, `ListThingGroups`, `UpdateThingGroup`, `DeleteThingGroup`, `AddThingToThingGroup`, `RemoveThingFromThingGroup`, `ListThingsInThingGroup`, and `ListThingGroupsForThing`.
- Jobs control plane: `CreateJob`, `DescribeJob`, and `ListJobs`, including thing ARN targets and static thing group targets.
- Jobs data plane: pending-job listing, `StartNextPendingJobExecution`, `DescribeJobExecution`, and `UpdateJobExecution` with version conflicts and terminal-state checks.
- Endpoint discovery accepts `iot:Jobs` in addition to IoT Data endpoint types.
- MQTT clients can use QoS 1 subscribe/publish paths with broker PUBACK and delivery behavior.
- IoT Data connection APIs for live MQTT sessions: `GetConnection`, `DeleteConnection`, `ListSubscriptions`, and `SendDirectMessage`.
- `DeleteConnection` closes active MQTT client sessions through the embedded broker and optionally purges broker session state for `cleanSession=true`.
- IoT rules can dispatch matching payloads to SQS, SNS, S3, DynamoDB v2, Kinesis, Lambda, and MQTT republish targets.

Current MVP 2 limitations:

- `DeleteConnection.preventWillMessage` is accepted for SDK request compatibility, but the embedded broker does not expose selective Last Will suppression.
- HTTP IoT Data `Publish` still treats QoS and MQTT5 metadata as compatibility inputs only; those properties are not fully forwarded or persisted yet.
- `SendDirectMessage` publishes to the requested MQTT topic through the embedded broker. Unlike AWS IoT Core, it does not yet bypass subscription matching to deliver to a client that is not subscribed to that topic.
- `GetConnection` and `ListSubscriptions` report live in-memory broker state only; offline persistent session subscription reporting is not modeled yet.
- Jobs reserved MQTT topics remain follow-up scope; Jobs Data HTTP APIs are implemented first.
- Dynamic thing groups, fleet indexing, job rollouts, cancellations, documents from S3, and advanced job scheduling are not yet modeled.

## MQTT Broker

Status: complete.

Floci uses Vert.x MQTT as the embedded MQTT protocol server. `IotMqttBrokerService` owns the broker lifecycle, live session registry, subscription registry, MQTT fan-out, and the bridge into IoT service behavior.

Broker scope:

- Target real AWS IoT/device SDK style MQTT clients, not only handcrafted packet tests.
- Support MQTT v3 and MQTT 5 CONNECT handling used by local compatibility tests.
- Support QoS 0 and QoS 1 publish/subscribe behavior for the local AWS IoT slice.
- Keep MQTT plaintext-only for this phase; TLS and mTLS are out of scope.
- Keep MQTT authorization permissive for now, but leave room for a later pluggable IoT certificate and policy authorizer.
- Keep MQTT broker logging minimal.
- Validate the relevant IoT compatibility tests against the native binary before considering the phase complete.

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

- Certificate and policy authorization are not enforced at the broker layer yet.
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
- SQL topic filter extraction for rules shaped like `SELECT * FROM 'topic/filter'`.
- MQTT-style topic filter matching for exact topics, `+`, and terminal `#`.
- IoT Data `Publish` and MQTT publishes use the same rule dispatch path.
- `republish` action republishes the original payload to another MQTT topic through `IotMqttBrokerService`.
- `sqs` action sends the original payload to an SQS queue through Floci's SQS service boundary.
- `sns` action publishes the original payload to an SNS topic through Floci's SNS service boundary.
- `s3` action writes the original payload to the configured bucket/key through Floci's S3 service boundary.
- `dynamoDBv2` action writes JSON object payload fields as DynamoDB attribute values through Floci's DynamoDB service boundary.
- `kinesis` action puts the original payload into a Kinesis stream through Floci's Kinesis service boundary.
- `lambda` action invokes the configured function ARN through Floci's Lambda service boundary.

Current limitations:

- SQL projection, WHERE clauses, functions, substitutions, error actions, and less common AWS IoT rule action types are follow-up scope.

Open follow-up scope for phase 7 unless explicitly deferred:

- Basic Ingest topics under `$aws/rules/...`.
- AWS IoT Jobs reserved topics and required job lifecycle behavior.
