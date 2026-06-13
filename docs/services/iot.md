# AWS IoT Core

Floci's IoT service emulates the AWS IoT Core control plane, IoT Data shadow APIs, and MQTT data-plane behavior used by local device and SDK tests.

## Phase 7 MQTT Broker Decision

Status: complete.

Phase 7 keeps the embedded Moquette broker. Replacing it with a hand-written socket broker is not acceptable because real MQTT client compatibility requires broker behavior beyond simple CONNECT, SUBSCRIBE, and QoS 0 PUBLISH handling.

The broker direction for phase 7 is:

- Use the vendored Floci Moquette build as the MQTT broker dependency.
- Keep native-image support required, including the Moquette runtime-initialization configuration needed by Quarkus native builds.
- Target real AWS IoT/device SDK style MQTT 5 clients, not only handcrafted packet tests.
- Require MQTT 5 CONNECT, SUBSCRIBE, and PUBLISH handling with MQTT 5 property-length encoding for QoS 0 traffic.
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

Moquette's public interceptor API observes publishes but does not provide a stock pre-route rewrite or drop hook. Because of that limitation, Floci should not expose a user-facing "strict reserved topic passthrough" configuration in phase 7 unless strict suppression is actually implemented.

Phase 7 implementation note:

- A first Moquette restoration slice showed that stock Moquette rejects inbound client publishes to topics beginning with `$` before the publish interceptor can handle them.
- The log message is `Avoid to publish on topic which contains reserved topic (starts with $)`.
- This blocked AWS IoT reserved request topics such as `$aws/things/{thingName}/shadow/update` on the stock-Moquette path.
- Floci uses a patched Moquette build that allows configured reserved publish prefixes, with Floci configuring `$aws/`.

Current accepted limitation:

- If stock Moquette routes the original reserved request publish to subscribers, Floci may accept that passthrough temporarily while still publishing the correct AWS response topics.
- Do not document strict AWS-style suppression as supported until it is enforceable.
- If strict suppression becomes required by compatibility tests, revisit a Moquette fork, upstream patch, or broker front-filter design.

## Implementation Shape

The MQTT integration should keep service behavior separated from broker mechanics:

- `IotMqttBrokerService` owns Moquette lifecycle and broker-native publish helpers.
- A Moquette publish interceptor detects AWS IoT reserved topics.
- IoT reserved-topic handling lives in IoT service code or a focused reserved-topic handler, not in packet parsing code.
- AWS-generated shadow responses are published back through Moquette with `Server.internalPublish(...)` so regular MQTT subscribers receive broker-native messages.

## Phase 7 Completion Criteria

Phase 7 completion criteria:

- Moquette is restored as the active MQTT broker implementation.
- The hand-written socket broker is removed or no longer used for MQTT service behavior.
- Reserved shadow topics are handled from a Moquette interceptor or equivalent broker bridge.
- AWS-generated shadow responses are published through Moquette, not by manually writing MQTT packets.
- MQTT 5 QoS 0 CONNECT, SUBSCRIBE, and PUBLISH behavior is tested with MQTT 5 property-length encoding.
- Classic unnamed shadow MQTT topics are covered by automated tests.
- Named shadow MQTT topics are covered by automated tests.
- Relevant IoT compatibility tests pass against the native binary.

## Rules Engine

Status: complete for the first action slice.

Phase 8 adds stored IoT topic rules and dispatches matching IoT publishes to rule actions.

Supported rule behavior:

- `CreateTopicRule`, `GetTopicRule`, `ListTopicRules`, `EnableTopicRule`, `DisableTopicRule`, and `DeleteTopicRule` through AWS SDK-compatible IoT control-plane paths.
- SQL topic filter extraction for rules shaped like `SELECT * FROM 'topic/filter'`.
- MQTT-style topic filter matching for exact topics, `+`, and terminal `#`.
- IoT Data `Publish` and MQTT publishes use the same rule dispatch path.
- `republish` action republishes the original payload to another MQTT topic through Moquette.
- `sqs` action sends the original payload to an SQS queue through Floci's SQS service boundary.

Current limitations:

- SQL projection, WHERE clauses, functions, substitutions, error actions, and additional AWS IoT rule action types are follow-up scope.

Open follow-up scope for phase 7 unless explicitly deferred:

- Basic Ingest topics under `$aws/rules/...`.
- AWS IoT Jobs reserved topics and required job lifecycle behavior.
