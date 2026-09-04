# Redshift wire decoder + intercepting bridge (DDL path) — design

**Umbrella PR:** [floci-io/floci#2836](https://github.com/floci-io/floci/pull/2836) (`feat/redshift-sql-interceptor`, open) — full multi-phase implementation. This is **Part 3** of that split.
**Prerequisites (merged):**
- [#2913](https://github.com/floci-io/floci/pull/2913) — split `PostgresProtocolHandler` into `authenticate(...)` and `bridge(...)` primitives.
- [#2947](https://github.com/floci-io/floci/pull/2947) — `RedshiftSqlInterceptor.rewrite(String): String`, a pure literal-aware DDL rewriter (`DISTSTYLE` / `DISTKEY` / `SORTKEY` / `ENCODE`), fail-open, not yet wired into the proxy.

**Branch:** `feat/redshift-wire-decoder-bridge` (from `second/main`, no worktree)

## Problem

`RedshiftSqlInterceptor.rewrite` exists but nothing calls it. `RedshiftAuthProxy` authenticates the client, then hands the two sockets to `PostgresProtocolHandler.bridge`, a transparent byte relay — so Redshift-only DDL (`CREATE TABLE … DISTKEY (id) COMPOUND SORTKEY (d)`) reaches the stock PostgreSQL backend verbatim and fails with a syntax error.

Part 3 puts the interceptor in the path: replace the transparent relay for Redshift connections with a bridge that frames the client→backend stream, and runs `RedshiftSqlInterceptor.rewrite` on Simple Query (`'Q'`) messages before forwarding.

## Goals

- `PostgresWireDecoder` — frame PostgreSQL frontend wire messages off an `InputStream`; encode a rewritten SQL string back into a `'Q'` packet.
- `RedshiftInterceptingBridge` — replace `PostgresProtocolHandler.bridge` for Redshift: verbatim backend→client pump, framed client→backend loop that rewrites `'Q'` and forwards everything else opaque.
- Wire it into `RedshiftAuthProxy`.
- `CREATE TABLE` / `ALTER TABLE` with Redshift keywords, sent over the Simple Query protocol, executes on the PostgreSQL backend.
- Restore the DDL slice of the `docs/services/redshift.md` interceptor section that was dropped when #2947 was split out.

## Non-goals (later phases of the umbrella PR)

- **S3 COPY simulator** (`CopyStatementParser` + `S3CopySimulator.runCopyFrom`) — Part 4.
- **S3 UNLOAD simulator** (`S3CopySimulator.runUnload`) — Part 5.
- Any `S3Service` reference in the bridge, `RedshiftAuthProxy`, or `RedshiftProxyManager`. The bridge constructor is `(Socket client, Socket backend)`. Part 4 adds `S3Service` threading + CDI injection when the simulator lands.
- The backend-ownership machinery the umbrella bridge carries **only** for the simulator: `backendLock`, `WireFrameTracker`, `outstandingResponses`, `pumpBetweenMessages`, `runWithBackendOwned`, pump suspension. The DDL path never reads the backend socket directly, so the pump stays a dumb relay.
- The decoder's shared-heap-budget machinery (`DECODER_HEAP_BUDGET` semaphore, `AutoCloseable`, incremental budget acquisition) — added on the umbrella for S3 UNLOAD/COPY memory pressure. Part 3 keeps only the fixed oversized-message guard.
- Extended Query protocol (`Parse` / `Bind` / `Execute`) rewriting — passes through untouched, by design.

## Design

All new/modified production code is in `src/main/java/io/github/hectorvent/floci/services/redshift/proxy/`.

Source of truth for the extraction: the final (branch-tip) versions of `PostgresWireDecoder.java` and `RedshiftInterceptingBridge.java` on `origin/feat/redshift-sql-interceptor`. Part 3 takes them and removes the non-goal machinery above.

### 1. `PostgresWireDecoder.java` (new, ~120 lines)

Lean variant — message framing + `'Q'` encoding, plus one fixed guard.

```
public class PostgresWireDecoder {
    static final int MAX_MESSAGE_BYTES = 16 * 1024 * 1024;

    PostgresWireDecoder(InputStream in)

    // type(1) · length(int32, includes itself) · body(length-4)
    FrontendMessage nextMessage() throws IOException
    boolean isBetweenMessages()

    static byte[] encodeQuery(String sql)   // builds a 'Q' packet, null-terminated body

    record FrontendMessage(char type, byte[] body) {
        boolean isQuery()          // type == 'Q'
        String  getSql()           // 'Q' body minus trailing NUL, UTF-8; null if not a 'Q'
        byte[]  toPacketBytes()    // type · length · body, byte-exact round-trip
    }
}
```

`nextMessage()` contract:
- Returns `null` on a clean EOF **between** messages (first `in.read()` is `-1`).
- Throws `EOFException` on EOF in the middle of the length field or body.
- Throws `IOException` if `length < 4`, or `length > MAX_MESSAGE_BYTES` (refused **before** the body is read).
- `isBetweenMessages()` is `true` before the first byte of a message and after a full message is returned; `false` once the type byte has been consumed. Lets the bridge loop tell "client idle between queries" from "client stalled mid-message" when a read times out.

Dropped vs the umbrella decoder: `implements AutoCloseable`, `DECODER_HEAP_BUDGET`, `readBodyBounded` incremental `tryAcquire`, `heldBodyBytes` / `releaseHeldBudget`. Body is read with a single bounded `readNBytes` / `readFully` after the size check.

### 2. `RedshiftInterceptingBridge.java` (new, ~110 lines)

```
public class RedshiftInterceptingBridge {
    private static final int CLIENT_READ_TIMEOUT_MS = 10_000;

    RedshiftInterceptingBridge(Socket client, Socket backend)   // no S3Service

    void run()
}
```

`run()`:
1. `client.setSoTimeout(CLIENT_READ_TIMEOUT_MS)` before entering the loop, so a stalled client cannot pin the connection.
2. Start one virtual thread — **backend→client verbatim pump**: `while ((n = backendIn.read(buf)) != -1) { clientOut.write(buf, 0, n); clientOut.flush(); }`. Byte-for-byte identical to `PostgresProtocolHandler`'s `relay`. No frame tracking, no lock, no timeout on the backend socket.
3. **client→backend framed loop** on the calling thread, over `new PostgresWireDecoder(clientIn)`:
   - `msg = decoder.nextMessage()`.
     - `SocketTimeoutException` + `decoder.isBetweenMessages()` → `continue` (client idle).
     - `SocketTimeoutException` mid-message → log warn, `break`.
     - `null` → `break` (EOF).
   - `msg.isQuery()` false → `backendOut.write(msg.toPacketBytes()); backendOut.flush();` then `break` if `msg.type() == 'X'` (Terminate), else `continue`.
   - `msg.isQuery()` true:
     - `String sql = msg.getSql();`
     - `byte[] toBackend = msg.toPacketBytes();`
     - `try { String rewritten = RedshiftSqlInterceptor.rewrite(sql); if (rewritten != sql) toBackend = PostgresWireDecoder.encodeQuery(rewritten); }`
       `catch (RuntimeException e) { LOG.warnv("RedshiftSqlInterceptor failed, forwarding original query: {0}", e.getMessage()); toBackend = msg.toPacketBytes(); }`
     - `backendOut.write(toBackend); backendOut.flush();`
     - Response returns over the untouched pump.
   - The `rewritten != sql` identity check is deliberate: `rewrite` returns the **same String instance** when nothing matched, so an unchanged statement forwards its original bytes with no re-encode.
4. `finally` → close both sockets. The pump's own `finally` closes both too; close is idempotent (`PostgresProtocolHandler.closeQuietly` pattern).

Fail-open is absolute: a `rewrite` exception or an unrecognised statement forwards the original bytes and lets PostgreSQL answer. The bridge never closes the connection because of a rewrite failure.

### 3. `RedshiftAuthProxy.java` (modified, ~3 lines)

In `handleConnection`, after `PostgresProtocolHandler.authenticate(...)` returns a non-null `activeClient`:

```java
-            if (activeClient != null) {
-                PostgresProtocolHandler.bridge(activeClient, backend);
-            }
+            if (activeClient != null) {
+                new RedshiftInterceptingBridge(activeClient, backend).run();
+            }
```

No constructor change, no new field, no `S3Service`. Import `...redshift.proxy.RedshiftInterceptingBridge` (same package — no import needed).

### 4. `RedshiftProxyManager.java`

**Untouched.**

### 5. `docs/services/redshift.md` (modified)

Restore only the DDL-relevant slice of the "SQL Interceptor & S3 COPY/UNLOAD" section from `origin/feat/redshift-sql-interceptor`:

- New `## SQL Interceptor` heading (drop "& S3 COPY/UNLOAD") after the `create_cluster` example.
- Intro sentence: the proxy inspects frontend Simple Query (`'Q'`) messages to emulate Redshift-specific SQL.
- **DDL Compatibility** bullet: which keywords are stripped (`DISTSTYLE ALL|EVEN|KEY|AUTO`, `DISTKEY (<col>)`, `[COMPOUND|INTERLEAVED] SORTKEY (<cols>)`, `ENCODE <codec>` for real Redshift encodings + `AUTO`); rewrite runs only when the first keyword is `CREATE TABLE` / `ALTER TABLE`; string literals are masked first.
- **Limitations**: Simple Query protocol only (extended protocol / JDBC `PreparedStatement` / pgjdbc default `preferQueryMode=extended` pass through untouched — connect with `preferQueryMode=simple` to exercise it); the rewrite is textual regex, masks single-quoted literals, is **not** comment-aware and does not recognise dollar-quoting or `E'…'` — fails safe (statement reaches PostgreSQL, which returns its own error).

Drop every S3 COPY / UNLOAD bullet and the "Out of Scope" section rewrite. Leave `## Out of Scope` exactly as it is on `second/main`.

## Testing

### `PostgresWireDecoderTest.java` (new, unit)

- Frame a single `'Q'` — `type()=='Q'`, `getSql()` matches, `isQuery()` true.
- Multi-message stream — successive `nextMessage()` calls return each in order.
- Clean EOF between messages → `nextMessage()` returns `null`.
- Truncated length field → `EOFException`; truncated body → `EOFException`.
- `length < 4` → `IOException`; `length > MAX_MESSAGE_BYTES` → `IOException`, thrown before body consumption.
- `encodeQuery(sql)` → decode round-trips through `FrontendMessage.getSql()`.
- `toPacketBytes()` is byte-exact for a known frame.
- `isBetweenMessages()` — true before first read and after a full message; false once a partial read has consumed the type byte (drive with a `PipedInputStream` or a hand-fed stream).

### `RedshiftInterceptingBridgeTest.java` (new, loopback sockets)

Pattern after the existing `RedshiftAuthProxyTest` — real `ServerSocket` fakes, virtual-thread readers, `AtomicReference<byte[]>` capture.

- `CREATE TABLE … DISTKEY (id) COMPOUND SORTKEY (d)` sent as `'Q'` → fake backend receives the **rewritten** SQL (no `DISTKEY` / `SORTKEY`).
- `SELECT 'DISTKEY'` sent as `'Q'` → backend receives it **byte-for-byte** (interceptor no-ops on non-DDL).
- An extended-protocol `'P'` (Parse) message → forwarded opaque, unchanged.
- Bytes written by the fake backend → arrive at the client unchanged (pump direction).
- `rewrite` throwing — inject via a statement the real interceptor no-ops on, then assert original forwarded; if a stub is needed, extract the rewrite call behind a package-private `java.util.function.UnaryOperator<String>` seam defaulting to `RedshiftSqlInterceptor::rewrite`. **Prefer no seam** if a real fail-open input exists.
- `'X'` (Terminate) → forwarded, then the client loop ends.

### `RedshiftInterceptorIntegrationTest.java` (new, `@QuarkusTest`)

DDL cases only — extract `testCreateTableWithRedshiftKeywords` from `origin/feat/redshift-sql-interceptor` and drop the S3 tests and the `@Inject S3Service`.

- `jdbcUrl` uses `?preferQueryMode=simple`.
- `testCreateTableWithRedshiftKeywords`: `CREATE TABLE sales (id int ENCODE az64, d date) DISTSTYLE KEY DISTKEY (id) COMPOUND SORTKEY (d)` → `INSERT` → `SELECT count(*)` returns 1.
- Add `testAlterTableAddColumnWithEncode`: `ALTER TABLE sales ADD COLUMN note varchar(20) ENCODE lzo` succeeds, `INSERT` + `SELECT` confirm the column is usable.
- `@AfterEach` deletes the cluster.

### Regression

- `RedshiftAuthProxyTest.bridgesClientBytesToTheBackendAfterASuccessfulPasswordAuth` — the test sends raw non-`'Q'` bytes; confirm they still relay through `RedshiftInterceptingBridge`. Adjust the fixture only if the raw bytes are not a valid wire frame (the framed loop needs `type · length · body`); if so, send a real `'Q'` or a valid small frame and assert it arrives.
- `./mvnw test` green before opening the PR.

## Commit plan

Branch `feat/redshift-wire-decoder-bridge` off `second/main`, no worktree.

1. `feat(redshift): add PostgresWireDecoder for frontend wire-message framing` — `PostgresWireDecoder.java` + `PostgresWireDecoderTest.java`.
2. `feat(redshift): intercept Simple Query DDL through RedshiftInterceptingBridge` — `RedshiftInterceptingBridge.java` + `RedshiftInterceptingBridgeTest.java` + `RedshiftAuthProxy.java` wiring + any `RedshiftAuthProxyTest` fixture fix.
3. `test(redshift): end-to-end DDL rewrite over the Simple Query protocol` — `RedshiftInterceptorIntegrationTest.java`.
4. `docs(redshift): document the Simple Query DDL interceptor` — `docs/services/redshift.md`.

## PR description

Part 3 of the [#2836](https://github.com/floci-io/floci/pull/2836) split. Link parts 1–2 (#2913, #2947) as done; parts 4–5 (S3 COPY / UNLOAD) as pending. State explicitly: DDL path only, no `S3Service`, Simple Query protocol only, fail-open.
