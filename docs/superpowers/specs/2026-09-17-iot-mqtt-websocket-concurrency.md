# IoT MQTT WebSocket concurrency and framing reliability

## Context

Issue #3763 reports intermittent EOF failures while sixteen MQTT WebSocket clients connect concurrently. A related CI failure reports that a 300 KB binary WebSocket frame does not reliably close the session after exceeding the transport limit.

The current bridge performs two asynchronous upgrades for each session: it upgrades the HTTP request to a WebSocket, then opens a separate Vert.x `NetSocket` to the MQTT broker. The WebSocket is paused during the broker connection, but the bridge has no explicit per-session lifecycle owner or idempotent close path. WebSocket and broker socket callbacks can therefore observe the same session in different states while connect, close, and error events are still in flight.

The existing concurrent-client test passed once during investigation, so the failure is timing-dependent rather than a deterministic startup failure. The previous TLS isolation change in commit `9f36a8729` also shows that the remaining failure is on the plaintext bridge path.

## Goals

- Make one WebSocket-to-broker bridge session have one explicit lifecycle.
- Ensure connect, close, and exception callbacks cannot continue forwarding data after the session has been closed.
- Make closure propagate in both directions exactly once, including broker-side protocol/frame failures.
- Preserve AWS IoT MQTT WebSocket wire behavior and the existing `/mqtt` endpoint.
- Keep backpressure behavior intact.
- Add focused regression coverage for concurrent clients and oversized frames.

## Non-goals

- Changing MQTT protocol semantics or broker limits.
- Adding retries to hide failed WebSocket handshakes.
- Changing TLS configuration or introducing a new endpoint.
- Refactoring `IotMqttBrokerService` beyond what the bridge lifecycle requires.

## Design

Introduce a private per-session bridge object in `IotMqttWebSocketBridge`. It owns the WebSocket and broker socket references, tracks whether the session is closed, and exposes one idempotent close operation. The bridge will:

1. Pause the WebSocket before opening the broker connection.
2. Install both sides' handlers as one session after the broker connection succeeds.
3. Reject forwarding when the session has already closed.
4. Route WebSocket close/error and broker close/error through the same session close path.
5. Resume the WebSocket only after the broker socket and all handlers are ready.

The implementation must retain the existing binary-frame-only behavior, close code for text frames, and queue backpressure. The session close path must not block an event-loop thread.

## Tests

- Keep the existing concurrent-client integration test as the regression test and make cleanup include clients that connected before a later handshake failed.
- Keep the oversized-frame integration test and assert that the raw client observes session termination within its bounded wait.
- Run the two focused integration tests and any directly affected unit tests. The full Maven suite is intentionally out of scope for this task.

## Acceptance criteria

- The bridge has a single idempotent lifecycle for each WebSocket and broker socket pair.
- Concurrent MQTT WebSocket clients all complete connect and publish/receive in the regression test.
- An oversized frame terminates the WebSocket session within the existing bounded test wait.
- Focused tests pass, compilation succeeds, and no custom endpoint or protocol shape is introduced.
- The branch is reviewed manually against this spec before the draft PR is created.
