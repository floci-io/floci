# IoT MQTT WebSocket concurrency and framing implementation plan

## Task 1: Establish the regression coverage

1. Inspect the existing WebSocket integration tests and bridge callback contracts.
2. Adjust only test cleanup or assertions needed to make failed partial connections observable and safely closeable.
3. Run `IotMqttWebSocketIntegrationTest#manyConcurrentWebSocketClientsEachReceiveThePublish` and `IotMqttWebSocketFramingIntegrationTest#frameAboveTheTransportLimitClosesTheSession` before the production change.
4. Confirm the tests exercise the issue's two reported paths without adding retries.

## Task 2: Add an explicit bridge session lifecycle

1. Add a private session abstraction inside `IotMqttWebSocketBridge` that owns the WebSocket and broker socket after connection.
2. Add an atomic/idempotent close guard and route both endpoint close/error directions through it.
3. Keep the WebSocket paused until handlers are installed and resume it only after the session is ready.
4. Preserve binary frame forwarding, text-frame rejection, and existing backpressure pause/resume behavior.
5. Ensure callbacks return immediately when the session is closed and do not write to a closed peer.

## Task 3: Verify the implementation

1. Run the focused concurrent-client test.
2. Run the focused oversized-frame test.
3. Run directly affected IoT WebSocket tests if the focused methods pass.
4. Compile the project without running the full test suite.
5. Review the complete branch diff against the spec and repository conventions, including imports, explicit types, logging, and no em dashes.

## Task 4: Commit and hand off

1. Commit the spec, plan, production change, and tests with a Conventional Commit message.
2. Push the new branch to `origin`.
3. Create a draft PR against `main` using `.github/pull_request_template.md`, link issue #3763, and record the focused verification commands and the intentionally skipped full suite.
