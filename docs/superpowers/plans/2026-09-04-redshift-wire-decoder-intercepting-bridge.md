# Redshift wire decoder + intercepting bridge (DDL path) Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Put `RedshiftSqlInterceptor` in the Redshift proxy's data path so Redshift-only table DDL sent over the PostgreSQL Simple Query protocol executes on the stock PostgreSQL backend.

**Architecture:** A new `PostgresWireDecoder` frames the client→backend byte stream into PostgreSQL wire messages. A new `RedshiftInterceptingBridge` replaces `PostgresProtocolHandler.bridge` for Redshift connections: backend→client stays a verbatim virtual-thread pump; client→backend becomes a framed loop that forwards every message opaque except a Simple Query (`'Q'`), whose SQL is run through `RedshiftSqlInterceptor.rewrite` and re-encoded if it changed. `RedshiftAuthProxy` is switched to the new bridge. Everything is fail-open: a rewrite failure or an untouched statement forwards the original bytes.

**Tech Stack:** Java 21 (virtual threads), Quarkus, JUnit 5, Mockito, Awaitility, JBoss Logging, pgjdbc (test only). Build via the Maven wrapper (`./mvnw`).

**Spec:** `docs/superpowers/specs/2026-09-04-redshift-wire-decoder-intercepting-bridge-design.md`

## Global Constraints

- **Repository language is English** — all code, comments, commit messages, PR text in English (overrides the global Vietnamese-commit rule for this repo).
- **Conventional Commits** — `feat(redshift): …`, `test(redshift): …`, `docs(redshift): …`.
- **Branch:** `feat/redshift-wire-decoder-bridge`, created from `second/main`. **No git worktree.**
- **No new dependencies.** Everything needed is already on the classpath.
- **`./mvnw test` must pass** before the PR is opened.
- **DDL path only.** No `S3Service` reference anywhere in this plan. No `CopyStatementParser` / `S3CopySimulator`. No backend-ownership machinery (`backendLock`, `WireFrameTracker`, `outstandingResponses`, pump suspension). No `AutoCloseable` / shared heap budget on the decoder.
- **Do not commit** `docs/superpowers/specs/…-design.md` or `docs/superpowers/plans/…` — they stay untracked working notes.
- New production files go in `src/main/java/io/github/hectorvent/floci/services/redshift/proxy/`; new tests mirror that under `src/test/java/…`.
- Package-private helper visibility and `org.jboss.logging.Logger` usage follow `PostgresProtocolHandler` / `RedshiftAuthProxy` in the same package.

## File Structure

| File | Status | Responsibility |
|---|---|---|
| `src/main/java/io/github/hectorvent/floci/services/redshift/proxy/PostgresWireDecoder.java` | **Create** | Frame the frontend byte stream into `FrontendMessage` records; encode a rewritten SQL string back into a `'Q'` packet; refuse an oversized declared length. Pure I/O framing, no protocol logic. |
| `src/main/java/io/github/hectorvent/floci/services/redshift/proxy/RedshiftInterceptingBridge.java` | **Create** | Bidirectional bridge for one authenticated Redshift connection: verbatim backend→client pump + framed client→backend loop that rewrites `'Q'` DDL. |
| `src/main/java/io/github/hectorvent/floci/services/redshift/proxy/RedshiftAuthProxy.java` | **Modify** (`handleConnection`, ~1 line + nothing else) | Use `RedshiftInterceptingBridge` instead of `PostgresProtocolHandler.bridge`. |
| `src/test/java/io/github/hectorvent/floci/services/redshift/proxy/PostgresWireDecoderTest.java` | **Create** | Unit tests for framing, encoding, EOF/lengths, boundary flag. |
| `src/test/java/io/github/hectorvent/floci/services/redshift/proxy/RedshiftInterceptingBridgeTest.java` | **Create** | Loopback-socket tests: DDL rewrite forwarded, non-DDL byte-exact, extended-protocol opaque, pump direction, Terminate ends loop. |
| `src/test/java/io/github/hectorvent/floci/services/redshift/RedshiftInterceptorIntegrationTest.java` | **Create** | `@QuarkusTest`: real cluster + pgjdbc `preferQueryMode=simple`, `CREATE TABLE` / `ALTER TABLE` with Redshift keywords. |
| `docs/services/redshift.md` | **Modify** (insert one section, edit one bullet) | Document the Simple Query DDL interceptor and its limits. |

`RedshiftProxyManager.java` is **not** touched.

## Notes for the executor

- **`RedshiftSqlInterceptor.rewrite(String)`** already exists (merged in PR #2947). It is `public static`, returns `String`, and **returns the exact same `String` instance** when nothing matched (leading keyword is not `CREATE TABLE` / `ALTER TABLE`, or no clause was stripped). The bridge relies on that identity (`rewritten != sql`) to skip re-encoding untouched statements. It never throws for well-formed input, but the bridge still wraps it in `catch (RuntimeException)` for fail-open.
- **`PostgresProtocolHandler.authenticate(...)`** (already merged) consumes the client startup + SSL negotiation + password exchange and returns the (possibly TLS-upgraded) client `Socket`, or `null` on failure. The bridge starts *after* that, on a stream positioned at the first post-auth frontend message.
- The reference implementation on branch `origin/feat/redshift-sql-interceptor` (umbrella PR #2836) contains fuller versions of both new classes with S3 COPY/UNLOAD machinery. This plan deliberately ships the reduced DDL-only form; do not copy the S3 parts.

---

### Task 1: `PostgresWireDecoder`

**Files:**
- Create: `src/main/java/io/github/hectorvent/floci/services/redshift/proxy/PostgresWireDecoder.java`
- Test: `src/test/java/io/github/hectorvent/floci/services/redshift/proxy/PostgresWireDecoderTest.java`

**Interfaces:**
- Consumes: nothing (leaf utility). Reads from a `java.io.InputStream`.
- Produces (relied on by Task 2 and Task 3):
  - `new PostgresWireDecoder(InputStream in)`
  - `FrontendMessage nextMessage() throws IOException` — returns `null` on clean EOF between messages; `EOFException` mid-message; `IOException` if `length < 4` or `length > 16 MiB`.
  - `boolean isBetweenMessages()` — `true` at a message boundary (before first byte, after a full message), `false` once a type byte is consumed.
  - `static byte[] encodeQuery(String sql)` — a `'Q'` packet, NUL-terminated body.
  - `record FrontendMessage(char type, byte[] body)` with `boolean isQuery()`, `String getSql()` (NUL stripped; `null` if not `'Q'`), `byte[] toPacketBytes()`.
  - `static final int MAX_MESSAGE_BYTES = 16 * 1024 * 1024` (package-private).

- [ ] **Step 1: Write the failing test file**

Create `src/test/java/io/github/hectorvent/floci/services/redshift/proxy/PostgresWireDecoderTest.java`:

```java
package io.github.hectorvent.floci.services.redshift.proxy;

import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PostgresWireDecoderTest {

    @Test
    void decodesASimpleQueryAndThenReportsEof() throws IOException {
        byte[] packet = PostgresWireDecoder.encodeQuery("SELECT 1");
        PostgresWireDecoder decoder = new PostgresWireDecoder(new ByteArrayInputStream(packet));

        PostgresWireDecoder.FrontendMessage msg = decoder.nextMessage();
        assertNotNull(msg);
        assertEquals('Q', msg.type());
        assertTrue(msg.isQuery());
        assertEquals("SELECT 1", msg.getSql());
        assertArrayEquals(packet, msg.toPacketBytes());

        assertNull(decoder.nextMessage());
    }

    @Test
    void decodesNonQueryMessagesWithoutInterpretingThem() throws IOException {
        byte[] terminate = new byte[]{'X', 0, 0, 0, 4};
        PostgresWireDecoder decoder = new PostgresWireDecoder(new ByteArrayInputStream(terminate));

        PostgresWireDecoder.FrontendMessage msg = decoder.nextMessage();
        assertNotNull(msg);
        assertEquals('X', msg.type());
        assertFalse(msg.isQuery());
        assertNull(msg.getSql());
        assertEquals(0, msg.body().length);
        assertArrayEquals(terminate, msg.toPacketBytes());
        assertNull(decoder.nextMessage());

        byte[] parsePayload = "stmt1\0SELECT $1\0\0\0".getBytes(StandardCharsets.UTF_8);
        int length = 4 + parsePayload.length;
        byte[] parsePacket = new byte[1 + length];
        parsePacket[0] = 'P';
        parsePacket[1] = (byte) ((length >> 24) & 0xFF);
        parsePacket[2] = (byte) ((length >> 16) & 0xFF);
        parsePacket[3] = (byte) ((length >> 8) & 0xFF);
        parsePacket[4] = (byte) (length & 0xFF);
        System.arraycopy(parsePayload, 0, parsePacket, 5, parsePayload.length);

        PostgresWireDecoder.FrontendMessage parse =
                new PostgresWireDecoder(new ByteArrayInputStream(parsePacket)).nextMessage();
        assertNotNull(parse);
        assertEquals('P', parse.type());
        assertFalse(parse.isQuery());
        assertArrayEquals(parsePayload, parse.body());
        assertArrayEquals(parsePacket, parse.toPacketBytes());
    }

    @Test
    void decodesSeveralMessagesFromOneStream() throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.write(PostgresWireDecoder.encodeQuery("SELECT 1"));
        out.write(PostgresWireDecoder.encodeQuery("SELECT 2"));
        out.write(new byte[]{'X', 0, 0, 0, 4});

        PostgresWireDecoder decoder = new PostgresWireDecoder(new ByteArrayInputStream(out.toByteArray()));
        assertEquals("SELECT 1", decoder.nextMessage().getSql());
        assertEquals("SELECT 2", decoder.nextMessage().getSql());
        assertEquals('X', decoder.nextMessage().type());
        assertNull(decoder.nextMessage());
    }

    @Test
    void reassemblesAMessageDeliveredTwoBytesAtATime() throws IOException {
        byte[] packet = PostgresWireDecoder.encodeQuery("SELECT * FROM users WHERE active = true");
        InputStream drip = new InputStream() {
            private int i = 0;
            @Override public int read() {
                return i >= packet.length ? -1 : packet[i++] & 0xFF;
            }
            @Override public int read(byte[] b, int off, int len) {
                if (i >= packet.length) return -1;
                int n = Math.min(Math.min(len, 2), packet.length - i);
                System.arraycopy(packet, i, b, off, n);
                i += n;
                return n;
            }
        };
        PostgresWireDecoder.FrontendMessage msg = new PostgresWireDecoder(drip).nextMessage();
        assertEquals("SELECT * FROM users WHERE active = true", msg.getSql());
        assertArrayEquals(packet, msg.toPacketBytes());
    }

    @Test
    void returnsNullOnAnEmptyStream() throws IOException {
        assertNull(new PostgresWireDecoder(new ByteArrayInputStream(new byte[0])).nextMessage());
    }

    @Test
    void throwsEofWhenTheLengthFieldIsTruncated() {
        PostgresWireDecoder decoder = new PostgresWireDecoder(new ByteArrayInputStream(new byte[]{'Q', 0, 0}));
        assertThrows(EOFException.class, decoder::nextMessage);
    }

    @Test
    void throwsEofWhenTheBodyIsTruncated() {
        PostgresWireDecoder decoder =
                new PostgresWireDecoder(new ByteArrayInputStream(new byte[]{'Q', 0, 0, 0, 10, 'S', 'E'}));
        assertThrows(EOFException.class, decoder::nextMessage);
    }

    @Test
    void rejectsALengthBelowFour() {
        PostgresWireDecoder decoder =
                new PostgresWireDecoder(new ByteArrayInputStream(new byte[]{'Q', 0, 0, 0, 2}));
        assertThrows(IOException.class, decoder::nextMessage);
    }

    @Test
    void refusesAnOversizedDeclaredLengthBeforeReadingTheBody() {
        byte[] hostile = new byte[]{'Q', 0x7F, (byte) 0xFF, (byte) 0xFF, (byte) 0xFF};
        PostgresWireDecoder decoder = new PostgresWireDecoder(new ByteArrayInputStream(hostile));
        IOException ex = assertThrows(IOException.class, decoder::nextMessage);
        assertTrue(ex.getMessage().contains("Refusing"), ex.getMessage());
    }

    @Test
    void encodeQueryProducesAWellFormedQPacket() {
        byte[] encoded = PostgresWireDecoder.encodeQuery("SHOW search_path");
        assertEquals('Q', (char) encoded[0]);
        int length = ((encoded[1] & 0xFF) << 24) | ((encoded[2] & 0xFF) << 16)
                | ((encoded[3] & 0xFF) << 8) | (encoded[4] & 0xFF);
        assertEquals(21, length); // 4 + 16 chars + 1 NUL
        assertEquals(0x00, encoded[encoded.length - 1]);
    }

    @Test
    void isBetweenMessagesIsTrueAtBoundariesAndFalseMidMessage() throws IOException {
        byte[] two = new ByteArrayOutputStream() {{
            try {
                write(PostgresWireDecoder.encodeQuery("SELECT 1"));
                write(new byte[]{'Q', 0, 0, 0, 50}); // header promises 46 body bytes that never arrive
            } catch (IOException e) {
                throw new RuntimeException(e);
            }
        }}.toByteArray();

        PostgresWireDecoder decoder = new PostgresWireDecoder(new ByteArrayInputStream(two));
        assertTrue(decoder.isBetweenMessages());
        assertNotNull(decoder.nextMessage());
        assertTrue(decoder.isBetweenMessages());
        assertThrows(EOFException.class, decoder::nextMessage);
        assertFalse(decoder.isBetweenMessages()); // type byte was consumed before the stream ran dry
    }
}
```

- [ ] **Step 2: Run the tests to verify they fail (no such class)**

Run: `./mvnw -q test -Dtest=PostgresWireDecoderTest`
Expected: FAIL — compilation error, `PostgresWireDecoder` does not exist.

- [ ] **Step 3: Create the implementation**

Create `src/main/java/io/github/hectorvent/floci/services/redshift/proxy/PostgresWireDecoder.java`:

```java
package io.github.hectorvent.floci.services.redshift.proxy;

import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Objects;

/**
 * Frames the PostgreSQL wire-protocol byte stream sent by the frontend (client).
 *
 * <p>Every frontend message is {@code type(1 byte) · length(int32, includes itself) · body(length-4)}.
 * This decoder turns that stream into {@link FrontendMessage} records for
 * {@link RedshiftInterceptingBridge}, which forwards each message opaque except a Simple Query
 * ({@code 'Q'}) — that one is rewritten via {@link RedshiftSqlInterceptor} and re-encoded with
 * {@link #encodeQuery(String)}.
 *
 * <p>The DDL path never reads the backend socket directly, so this decoder does no shared-heap
 * accounting: {@link #MAX_MESSAGE_BYTES} is the only guard, refusing an oversized declared length
 * before any body is read (the length field is attacker-controlled, up to ~2 GiB).
 */
public class PostgresWireDecoder {

    /** Largest single frontend message accepted; 16 MiB is far above any real SQL statement. */
    static final int MAX_MESSAGE_BYTES = 16 * 1024 * 1024;

    private final InputStream in;
    private boolean betweenMessages = true;

    public PostgresWireDecoder(InputStream in) {
        this.in = Objects.requireNonNull(in, "in must not be null");
    }

    /**
     * {@code true} when the decoder is at a clean boundary between messages — before the first
     * byte of a message and after a full message has been returned; {@code false} once a type
     * byte has been consumed. Lets the bridge tell "client idle between queries" from "client
     * stalled mid-message" when a read times out.
     */
    public boolean isBetweenMessages() {
        return betweenMessages;
    }

    /**
     * Read the next frontend message.
     *
     * @return the message, or {@code null} on a clean end-of-stream between messages.
     * @throws EOFException if EOF is hit inside the length field or body.
     * @throws IOException  on an I/O error, a length below 4, or a length above {@link #MAX_MESSAGE_BYTES}.
     */
    public FrontendMessage nextMessage() throws IOException {
        betweenMessages = true;

        int typeByte = in.read();
        if (typeByte == -1) {
            return null; // clean EOF between messages
        }
        betweenMessages = false;
        char type = (char) typeByte;

        byte[] lengthBytes = readFully(4);
        int length = ((lengthBytes[0] & 0xFF) << 24)
                | ((lengthBytes[1] & 0xFF) << 16)
                | ((lengthBytes[2] & 0xFF) << 8)
                | (lengthBytes[3] & 0xFF);

        if (length < 4) {
            throw new IOException("Invalid message length: " + length);
        }
        if (length > MAX_MESSAGE_BYTES) {
            throw new IOException("Refusing PostgreSQL message of " + length
                    + " bytes (limit " + MAX_MESSAGE_BYTES + ")");
        }

        byte[] body = (length == 4) ? new byte[0] : readFully(length - 4);
        betweenMessages = true;
        return new FrontendMessage(type, body);
    }

    /** Read exactly {@code n} bytes or throw {@link EOFException}. */
    private byte[] readFully(int n) throws IOException {
        byte[] buf = new byte[n];
        int off = 0;
        while (off < n) {
            int read = in.read(buf, off, n - off);
            if (read == -1) {
                throw new EOFException("Unexpected EOF (expected " + n + " bytes, got " + off + ")");
            }
            off += read;
        }
        return buf;
    }

    /** Encode an SQL string as a Simple Query ({@code 'Q'}) packet with a NUL-terminated body. */
    public static byte[] encodeQuery(String sql) {
        if (sql == null) {
            sql = "";
        }
        byte[] sqlBytes = sql.getBytes(StandardCharsets.UTF_8);
        int bodyLength = sqlBytes.length + 1;
        int totalLength = 4 + bodyLength;

        byte[] packet = new byte[1 + 4 + bodyLength];
        packet[0] = 'Q';
        packet[1] = (byte) ((totalLength >> 24) & 0xFF);
        packet[2] = (byte) ((totalLength >> 16) & 0xFF);
        packet[3] = (byte) ((totalLength >> 8) & 0xFF);
        packet[4] = (byte) (totalLength & 0xFF);
        System.arraycopy(sqlBytes, 0, packet, 5, sqlBytes.length);
        packet[packet.length - 1] = 0x00;
        return packet;
    }

    /** A single PostgreSQL wire message from the client. */
    public record FrontendMessage(char type, byte[] body) {

        public boolean isQuery() {
            return type == 'Q';
        }

        /** SQL text of a {@code 'Q'} (trailing NUL stripped), or {@code null} if this is not a {@code 'Q'}. */
        public String getSql() {
            if (type != 'Q' || body == null || body.length == 0) {
                return null;
            }
            int len = body.length;
            if (body[len - 1] == 0) {
                len--;
            }
            return new String(body, 0, len, StandardCharsets.UTF_8);
        }

        /** {@code type · length · body} — a byte-exact round-trip of the original frame. */
        public byte[] toPacketBytes() {
            int bodyLen = (body != null) ? body.length : 0;
            int lengthField = 4 + bodyLen;
            byte[] packet = new byte[1 + 4 + bodyLen];
            packet[0] = (byte) type;
            packet[1] = (byte) ((lengthField >> 24) & 0xFF);
            packet[2] = (byte) ((lengthField >> 16) & 0xFF);
            packet[3] = (byte) ((lengthField >> 8) & 0xFF);
            packet[4] = (byte) (lengthField & 0xFF);
            if (bodyLen > 0) {
                System.arraycopy(body, 0, packet, 5, bodyLen);
            }
            return packet;
        }
    }
}
```

- [ ] **Step 4: Run the tests to verify they pass**

Run: `./mvnw -q test -Dtest=PostgresWireDecoderTest`
Expected: PASS — 11 tests green.

- [ ] **Step 5: Commit**

```bash
git add src/main/java/io/github/hectorvent/floci/services/redshift/proxy/PostgresWireDecoder.java \
        src/test/java/io/github/hectorvent/floci/services/redshift/proxy/PostgresWireDecoderTest.java
git commit -m "feat(redshift): add PostgresWireDecoder for frontend wire-message framing"
```

---

### Task 2: `RedshiftInterceptingBridge` + wire it into `RedshiftAuthProxy`

**Files:**
- Create: `src/main/java/io/github/hectorvent/floci/services/redshift/proxy/RedshiftInterceptingBridge.java`
- Modify: `src/main/java/io/github/hectorvent/floci/services/redshift/proxy/RedshiftAuthProxy.java` (method `handleConnection`)
- Test: `src/test/java/io/github/hectorvent/floci/services/redshift/proxy/RedshiftInterceptingBridgeTest.java`

**Interfaces:**
- Consumes (from Task 1): `PostgresWireDecoder`, `PostgresWireDecoder.FrontendMessage`, `PostgresWireDecoder.encodeQuery`.
- Consumes (already in the repo): `RedshiftSqlInterceptor.rewrite(String)`.
- Produces:
  - `new RedshiftInterceptingBridge(java.net.Socket client, java.net.Socket backend)`
  - `void run()` — blocks the calling thread until the client loop ends; spawns one virtual thread for the backend→client pump; closes both sockets on exit.

- [ ] **Step 1: Write the failing test file**

Create `src/test/java/io/github/hectorvent/floci/services/redshift/proxy/RedshiftInterceptingBridgeTest.java`:

```java
package io.github.hectorvent.floci.services.redshift.proxy;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RedshiftInterceptingBridgeTest {

    private ServerSocket clientListener;
    private ServerSocket backendListener;
    private Socket testClientEnd;   // test writes client bytes here, reads pump output here
    private Socket bridgeClientEnd; // the bridge's "client" socket
    private Socket bridgeBackendEnd; // the bridge's "backend" socket
    private Socket testBackendEnd;  // test reads what the bridge forwarded, writes backend replies
    private Thread bridgeThread;

    private void startBridge() throws IOException {
        clientListener = new ServerSocket(0);
        testClientEnd = new Socket("localhost", clientListener.getLocalPort());
        bridgeClientEnd = clientListener.accept();

        backendListener = new ServerSocket(0);
        bridgeBackendEnd = new Socket("localhost", backendListener.getLocalPort());
        testBackendEnd = backendListener.accept();

        RedshiftInterceptingBridge bridge = new RedshiftInterceptingBridge(bridgeClientEnd, bridgeBackendEnd);
        bridgeThread = Thread.ofVirtual().name("bridge-under-test").start(bridge::run);
    }

    @AfterEach
    void tearDown() throws IOException {
        for (Socket s : new Socket[]{testClientEnd, bridgeClientEnd, bridgeBackendEnd, testBackendEnd}) {
            if (s != null && !s.isClosed()) {
                try { s.close(); } catch (IOException ignored) { }
            }
        }
        for (ServerSocket ss : new ServerSocket[]{clientListener, backendListener}) {
            if (ss != null && !ss.isClosed()) {
                ss.close();
            }
        }
    }

    private PostgresWireDecoder.FrontendMessage nextForwarded() throws IOException {
        return new PostgresWireDecoder(testBackendEnd.getInputStream()).nextMessage();
    }

    @Test
    void rewritesRedshiftCreateTableBeforeForwarding() throws IOException {
        startBridge();
        String ddl = "CREATE TABLE sales (id int ENCODE az64, d date) "
                + "DISTSTYLE KEY DISTKEY (id) COMPOUND SORTKEY (d);";
        testClientEnd.getOutputStream().write(PostgresWireDecoder.encodeQuery(ddl));
        testClientEnd.getOutputStream().flush();

        PostgresWireDecoder.FrontendMessage forwarded = nextForwarded();
        assertNotNull(forwarded);
        assertEquals('Q', forwarded.type());
        String sent = forwarded.getSql().toUpperCase();
        assertFalse(sent.contains("DISTKEY"), sent);
        assertFalse(sent.contains("SORTKEY"), sent);
        assertFalse(sent.contains("DISTSTYLE"), sent);
        assertFalse(sent.contains("ENCODE"), sent);
        assertTrue(forwarded.getSql().contains("CREATE TABLE sales"), forwarded.getSql());
    }

    @Test
    void forwardsANonDdlQueryByteForByte() throws IOException {
        startBridge();
        byte[] packet = PostgresWireDecoder.encodeQuery("SELECT 'DISTKEY' AS not_a_keyword");
        testClientEnd.getOutputStream().write(packet);
        testClientEnd.getOutputStream().flush();

        assertArrayEquals(packet, nextForwarded().toPacketBytes());
    }

    @Test
    void forwardsAnExtendedProtocolMessageOpaque() throws IOException {
        startBridge();
        byte[] parsePayload = "s1\0SELECT $1\0\0\0".getBytes(StandardCharsets.UTF_8);
        int length = 4 + parsePayload.length;
        byte[] parsePacket = new byte[1 + length];
        parsePacket[0] = 'P';
        parsePacket[1] = (byte) ((length >> 24) & 0xFF);
        parsePacket[2] = (byte) ((length >> 16) & 0xFF);
        parsePacket[3] = (byte) ((length >> 8) & 0xFF);
        parsePacket[4] = (byte) (length & 0xFF);
        System.arraycopy(parsePayload, 0, parsePacket, 5, parsePayload.length);

        testClientEnd.getOutputStream().write(parsePacket);
        testClientEnd.getOutputStream().flush();

        assertArrayEquals(parsePacket, nextForwarded().toPacketBytes());
    }

    @Test
    void pumpsBackendBytesToTheClientUnchanged() throws IOException {
        startBridge();
        byte[] readyForQuery = new byte[]{'Z', 0, 0, 0, 5, 'I'};
        testBackendEnd.getOutputStream().write(readyForQuery);
        testBackendEnd.getOutputStream().flush();

        byte[] got = testClientEnd.getInputStream().readNBytes(readyForQuery.length);
        assertArrayEquals(readyForQuery, got);
    }

    @Test
    void terminateMessageIsForwardedAndEndsTheSession() throws Exception {
        startBridge();
        testClientEnd.getOutputStream().write(new byte[]{'X', 0, 0, 0, 4});
        testClientEnd.getOutputStream().flush();

        PostgresWireDecoder.FrontendMessage forwarded = nextForwarded();
        assertEquals('X', forwarded.type());

        bridgeThread.join(5_000);
        assertFalse(bridgeThread.isAlive(), "bridge did not stop after Terminate");
        assertEquals(-1, testClientEnd.getInputStream().read(), "bridge left the client socket open");
    }
}
```

- [ ] **Step 2: Run the tests to verify they fail (no such class)**

Run: `./mvnw -q test -Dtest=RedshiftInterceptingBridgeTest`
Expected: FAIL — compilation error, `RedshiftInterceptingBridge` does not exist.

- [ ] **Step 3: Create the bridge implementation**

Create `src/main/java/io/github/hectorvent/floci/services/redshift/proxy/RedshiftInterceptingBridge.java`:

```java
package io.github.hectorvent.floci.services.redshift.proxy;

import org.jboss.logging.Logger;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Replaces the transparent {@code PostgresProtocolHandler.bridge} for Redshift connections so
 * Simple Query ({@code 'Q'}) DDL can be rewritten for a plain PostgreSQL backend before it is
 * forwarded.
 *
 * <p><b>Model.</b> Backend&rarr;client is a verbatim byte pump on a virtual thread, exactly as a
 * plain proxy — extended-protocol pipelines, {@code COPY … FROM STDIN}, asynchronous
 * {@code NotificationResponse} and backend EOF all flow through untouched. Client&rarr;backend is
 * a framed loop: every frontend message is forwarded opaque <em>except</em> a {@code 'Q'}, whose
 * SQL is run through {@link RedshiftSqlInterceptor#rewrite} and re-encoded only if it changed.
 *
 * <p><b>Fail-open.</b> A {@code rewrite} failure, or any statement the interceptor leaves
 * untouched, forwards the original bytes; PostgreSQL then returns its own error. The bridge never
 * closes the connection because of a rewrite failure.
 */
public class RedshiftInterceptingBridge {

    private static final Logger LOG = Logger.getLogger(RedshiftInterceptingBridge.class);

    /** Read timeout on the client socket so a stalled client cannot pin the connection forever. */
    private static final int CLIENT_READ_TIMEOUT_MS = 10_000;

    private final Socket client;
    private final Socket backend;
    private final AtomicBoolean closed = new AtomicBoolean();

    public RedshiftInterceptingBridge(Socket client, Socket backend) {
        this.client = client;
        this.backend = backend;
    }

    public void run() {
        try {
            client.setSoTimeout(CLIENT_READ_TIMEOUT_MS);

            InputStream clientIn = client.getInputStream();
            OutputStream backendOut = backend.getOutputStream();

            Thread.ofVirtual().name("redshift-pump-backend-to-client").start(this::pumpBackendToClient);

            PostgresWireDecoder decoder = new PostgresWireDecoder(clientIn);
            while (true) {
                PostgresWireDecoder.FrontendMessage msg;
                try {
                    msg = decoder.nextMessage();
                } catch (SocketTimeoutException e) {
                    if (decoder.isBetweenMessages()) {
                        continue; // client idle between queries — keep waiting
                    }
                    LOG.warnv("Client socket timed out mid-message: {0}", e.getMessage());
                    break;
                }
                if (msg == null) {
                    break; // client EOF
                }

                if (!msg.isQuery()) {
                    backendOut.write(msg.toPacketBytes());
                    backendOut.flush();
                    if (msg.type() == 'X') { // Terminate
                        break;
                    }
                    continue;
                }

                String sql = msg.getSql();
                byte[] toBackend = msg.toPacketBytes();
                try {
                    String rewritten = RedshiftSqlInterceptor.rewrite(sql);
                    if (rewritten != sql) { // identity: rewrite returns the same instance when nothing matched
                        toBackend = PostgresWireDecoder.encodeQuery(rewritten);
                    }
                } catch (RuntimeException e) {
                    LOG.warnv("RedshiftSqlInterceptor failed, forwarding original query: {0}", e.getMessage());
                    toBackend = msg.toPacketBytes();
                }
                backendOut.write(toBackend);
                backendOut.flush();
                // The response returns over the untouched pump.
            }
        } catch (IOException e) {
            LOG.debugv(e, "RedshiftInterceptingBridge client loop ended");
        } catch (Exception e) {
            LOG.warnv(e, "Unexpected error in RedshiftInterceptingBridge");
        } finally {
            closeBoth();
        }
    }

    private void pumpBackendToClient() {
        try {
            InputStream backendIn = backend.getInputStream();
            OutputStream clientOut = client.getOutputStream();
            byte[] buf = new byte[8192];
            int n;
            while ((n = backendIn.read(buf)) != -1) {
                clientOut.write(buf, 0, n);
                clientOut.flush();
            }
        } catch (IOException e) {
            LOG.debugv(e, "backend->client pump ended");
        } finally {
            closeBoth();
        }
    }

    private void closeBoth() {
        if (closed.compareAndSet(false, true)) {
            closeQuietly(client, "client");
            closeQuietly(backend, "backend");
        }
    }

    private void closeQuietly(Socket s, String which) {
        try {
            if (s != null && !s.isClosed()) {
                s.close();
            }
        } catch (IOException e) {
            LOG.debugv(e, "error closing {0} socket", which);
        }
    }
}
```

- [ ] **Step 4: Run the bridge tests to verify they pass**

Run: `./mvnw -q test -Dtest=RedshiftInterceptingBridgeTest`
Expected: PASS — 5 tests green.

- [ ] **Step 5: Wire the bridge into `RedshiftAuthProxy`**

In `src/main/java/io/github/hectorvent/floci/services/redshift/proxy/RedshiftAuthProxy.java`, method `handleConnection`, replace the post-auth bridge call. Current code:

```java
            // iamEnabled = false: the SigV4 branch inside authenticate is never taken.
            Socket activeClient = PostgresProtocolHandler.authenticate(
                    client, backend, masterUsername, masterPassword, dbName,
                    false, sigV4, tlsCertificates, passwordValidator::validate);
            if (activeClient != null) {
                PostgresProtocolHandler.bridge(activeClient, backend);
            }
```

New code:

```java
            // iamEnabled = false: the SigV4 branch inside authenticate is never taken.
            Socket activeClient = PostgresProtocolHandler.authenticate(
                    client, backend, masterUsername, masterPassword, dbName,
                    false, sigV4, tlsCertificates, passwordValidator::validate);
            if (activeClient != null) {
                // Redshift-only DDL (DISTKEY/SORTKEY/ENCODE/…) is rewritten for the plain
                // PostgreSQL backend on the way through; every other message is relayed verbatim.
                new RedshiftInterceptingBridge(activeClient, backend).run();
            }
```

`RedshiftInterceptingBridge` is in the same package — no import needed. Leave the `import ...PostgresProtocolHandler;` in place (still used for `authenticate`). Do not touch the class fields, the constructor, or `RedshiftProxyManager`.

- [ ] **Step 6: Run the existing proxy tests to verify the swap is safe**

Run: `./mvnw -q test -Dtest=RedshiftAuthProxyTest,RedshiftProxyManagerTest,RedshiftProxyConfigTest`
Expected: PASS — unchanged.

Why no fixture change: `RedshiftAuthProxyTest.bridgesClientBytesToTheBackendAfterASuccessfulPasswordAuth` asserts only that the fake backend saw the **forwarded startup packet**, which `PostgresProtocolHandler.authenticate` sends *before* the bridge is constructed. That test's fake backend reads once and closes, so `authenticate` returns `null` and the bridge is never entered. The other three tests never reach the bridge either (client drops mid-handshake / bind-retry / password-swap via reflection).

- [ ] **Step 7: Run the whole redshift proxy test package**

Run: `./mvnw -q test -Dtest="io.github.hectorvent.floci.services.redshift.proxy.*"`
Expected: PASS — all green.

- [ ] **Step 8: Commit**

```bash
git add src/main/java/io/github/hectorvent/floci/services/redshift/proxy/RedshiftInterceptingBridge.java \
        src/main/java/io/github/hectorvent/floci/services/redshift/proxy/RedshiftAuthProxy.java \
        src/test/java/io/github/hectorvent/floci/services/redshift/proxy/RedshiftInterceptingBridgeTest.java
git commit -m "feat(redshift): intercept Simple Query DDL through RedshiftInterceptingBridge"
```

---

### Task 3: End-to-end integration test

**Files:**
- Create: `src/test/java/io/github/hectorvent/floci/services/redshift/RedshiftInterceptorIntegrationTest.java`

**Interfaces:**
- Consumes: the running proxy path (Task 2), `RedshiftService` (CDI bean, already in the repo), pgjdbc `DriverManager`.
- Produces: nothing consumed by later tasks.

**Executor notes:**
- Model this on `RedshiftProxyIntegrationTest` (already in `src/test/java/io/github/hectorvent/floci/services/redshift/`) for the `@QuarkusTest` + cluster-lifecycle shape. Match its `createCluster` call signature and its way of reading the endpoint host/port if this plan's snippet differs from the current API — the snippet below reflects the umbrella branch and may need a one-line adjustment.
- `?preferQueryMode=simple` is **mandatory**: pgjdbc defaults to the extended protocol, which the bridge does not inspect, so without it the DDL reaches PostgreSQL unrewritten and the test fails.

- [ ] **Step 1: Write the integration test**

Create `src/test/java/io/github/hectorvent/floci/services/redshift/RedshiftInterceptorIntegrationTest.java`:

```java
package io.github.hectorvent.floci.services.redshift;

import io.github.hectorvent.floci.services.redshift.model.Cluster;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.awaitility.Awaitility;
import org.awaitility.core.ConditionTimeoutException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Duration;
import java.util.Objects;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

@QuarkusTest
class RedshiftInterceptorIntegrationTest {

    @Inject
    RedshiftService redshiftService;

    private String clusterId;

    @AfterEach
    void cleanUp() {
        if (clusterId != null) {
            try {
                redshiftService.deleteCluster(clusterId);
            } catch (Exception ignored) {
            }
        }
    }

    private static String jdbcUrl(Cluster c) {
        // preferQueryMode=simple forces pgjdbc onto the Simple Query ('Q') protocol — the only
        // protocol the interceptor inspects. With the driver default (extended) a Statement is
        // sent as Parse/Bind/Execute and is never rewritten.
        return "jdbc:postgresql://127.0.0.1:" + c.getEndpoint().getPort() + "/dev?preferQueryMode=simple";
    }

    private static Connection waitForConnection(Cluster cluster, String user, String password) throws SQLException {
        try {
            return Awaitility.await()
                    .atMost(Duration.ofSeconds(30))
                    .pollInterval(Duration.ofMillis(500))
                    .ignoreExceptions()
                    .until(() -> DriverManager.getConnection(jdbcUrl(cluster), user, password), Objects::nonNull);
        } catch (ConditionTimeoutException e) {
            return DriverManager.getConnection(jdbcUrl(cluster), user, password);
        }
    }

    @Test
    void createTableWithRedshiftKeywordsExecutesOnPostgres() throws SQLException {
        clusterId = "it-interceptor-create";
        Cluster cluster = redshiftService.createCluster(clusterId, "dc2.large", "admin", "Secret123");

        try (Connection conn = waitForConnection(cluster, "admin", "Secret123");
             Statement st = conn.createStatement()) {
            st.execute("CREATE TABLE sales (id int ENCODE az64, d date) "
                    + "DISTSTYLE KEY DISTKEY (id) COMPOUND SORTKEY (d);");
            st.execute("INSERT INTO sales VALUES (1, '2026-01-01');");

            try (ResultSet rs = st.executeQuery("SELECT count(*) FROM sales")) {
                assertTrue(rs.next());
                assertEquals(1, rs.getInt(1));
            }
        }
    }

    @Test
    void alterTableAddColumnWithEncodeExecutesOnPostgres() throws SQLException {
        clusterId = "it-interceptor-alter";
        Cluster cluster = redshiftService.createCluster(clusterId, "dc2.large", "admin", "Secret123");

        try (Connection conn = waitForConnection(cluster, "admin", "Secret123");
             Statement st = conn.createStatement()) {
            st.execute("CREATE TABLE t (id int);");
            st.execute("ALTER TABLE t ADD COLUMN note varchar(20) ENCODE lzo;");
            st.execute("INSERT INTO t (id, note) VALUES (1, 'ok');");

            try (ResultSet rs = st.executeQuery("SELECT note FROM t WHERE id = 1")) {
                assertTrue(rs.next());
                assertEquals("ok", rs.getString(1));
            }
        }
    }
}
```

- [ ] **Step 2: Run the integration test**

Run: `./mvnw -q test -Dtest=RedshiftInterceptorIntegrationTest`
Expected: PASS — 2 tests green. (First run pulls the PostgreSQL container image; allow a few minutes.)

If `createCluster` / `getEndpoint()` do not compile, open `RedshiftProxyIntegrationTest.java` in the same directory and copy its exact cluster-creation and endpoint-access calls, then re-run.

- [ ] **Step 3: Commit**

```bash
git add src/test/java/io/github/hectorvent/floci/services/redshift/RedshiftInterceptorIntegrationTest.java
git commit -m "test(redshift): end-to-end DDL rewrite over the Simple Query protocol"
```

---

### Task 4: Documentation

**Files:**
- Modify: `docs/services/redshift.md` (insert one section; edit one bullet in `## Out of Scope`)

**Interfaces:** none.

- [ ] **Step 1: Insert the interceptor section**

In `docs/services/redshift.md`, immediately after the closing ` ``` ` of the "Management API (Python / boto3)" example and its trailing blank line, and immediately before the `## Out of Scope` heading, insert:

```markdown
## SQL Interceptor

Floci's Redshift auth proxy inspects frontend queries on the PostgreSQL wire protocol (Simple Query `'Q'` protocol) and rewrites common Redshift-specific table DDL so it runs on the plain PostgreSQL backend.

### DDL compatibility

- Redshift-only table DDL keywords are stripped before the statement is forwarded: `DISTSTYLE ALL|EVEN|KEY|AUTO`, `DISTKEY (<col>)` and column-level `DISTKEY`, `[COMPOUND|INTERLEAVED] SORTKEY (<cols>)` and column-level `SORTKEY`, and `ENCODE <codec>` for the real Redshift column encodings (`raw`, `az64`, `bytedict`, `delta`, `delta32k`, `lzo`, `mostly8`, `mostly16`, `mostly32`, `runlength`, `text255`, `text32k`, `zstd`) or `auto`.
- The rewrite only runs when the statement's first keyword is `CREATE TABLE` or `ALTER TABLE`. A `SELECT`, `INSERT`, function body, or string literal that merely contains one of these keywords is forwarded byte-for-byte. Single-quoted and dollar-quoted string literals are masked before the rewrite, so a keyword inside a quoted value — including in a later statement of a multi-statement query — is preserved.
- Columns legitimately named `distkey`, `sortkey`, or `encode` survive.

### Limitations

- Emulation runs on the **Simple Query protocol** (`'Q'`) only. Extended Query protocol statements (`Parse`/`Bind`/`Execute`) pass through untouched — including anything a JDBC `PreparedStatement` sends, and, with the pgjdbc default `preferQueryMode=extended`, plain `Statement` calls too. Connect with `preferQueryMode=simple` to exercise the interceptor from JDBC.
- The rewrite is textual (regex-based). It masks single-quoted string literals first, so `DEFAULT` / `CHECK` string values are safe, but it is **not** comment-aware and does not recognise escape strings (`E'…'`): an apostrophe inside a `--` or `/* */` comment can make the rewrite skip a Redshift clause. That fails safe — the statement then reaches PostgreSQL, which returns its own syntax error — but avoid apostrophes-in-comments in `CREATE TABLE` / `ALTER TABLE`.
- A `rewrite` failure or any statement the interceptor does not recognise is forwarded unmodified (fail-open); PostgreSQL then rejects the Redshift-only syntax itself.
- A single wire-protocol message larger than 16 MiB is refused before its body is read.
- `COPY … FROM 's3://…'` and `UNLOAD (…) TO 's3://…'` are **not** yet emulated (planned).

```

- [ ] **Step 2: Update the first `## Out of Scope` bullet**

The current first bullet reads:

```markdown
- Real Redshift SQL semantics — the data plane is stock PostgreSQL, so Redshift-only SQL (distribution/sort keys, `COPY`/`UNLOAD` from S3, `SUPER`/`SPECTRUM`) is not emulated.
```

Replace it with:

```markdown
- Real Redshift SQL semantics — the data plane is stock PostgreSQL. Redshift-only table DDL keywords (`DISTSTYLE` / `DISTKEY` / `SORTKEY` / `ENCODE`) are stripped so `CREATE TABLE` / `ALTER TABLE` executes (see [SQL Interceptor](#sql-interceptor)), but the distribution/sort behaviour they request is not; `COPY`/`UNLOAD` from S3 and `SUPER`/`SPECTRUM` are not emulated.
```

Leave every other line of `## Out of Scope` unchanged.

- [ ] **Step 3: Sanity-check the rendered Markdown**

Run: `git diff docs/services/redshift.md`
Expected: one added `## SQL Interceptor` section between the boto3 example and `## Out of Scope`, and one changed bullet. No other hunks.

- [ ] **Step 4: Commit**

```bash
git add docs/services/redshift.md
git commit -m "docs(redshift): document the Simple Query DDL interceptor"
```

---

### Task 5: Full build + PR

- [ ] **Step 1: Run the full test suite**

Run: `./mvnw test`
Expected: BUILD SUCCESS. If an unrelated flaky test fails, re-run that module; the four new files must be green.

- [ ] **Step 2: Push the branch**

```bash
git push -u origin feat/redshift-wire-decoder-bridge
```

- [ ] **Step 3: Open the PR against `floci-io/floci:main`**

Title: `feat(redshift): wire the Simple Query DDL interceptor into the proxy`

Body — cover:
- Part 3 of the [#2836](https://github.com/floci-io/floci/pull/2836) split. Parts 1–2 done ([#2913](https://github.com/floci-io/floci/pull/2913), [#2947](https://github.com/floci-io/floci/pull/2947)); parts 4–5 (S3 COPY / UNLOAD) pending.
- What it does: `PostgresWireDecoder` frames the frontend stream; `RedshiftInterceptingBridge` replaces the transparent relay for Redshift, rewriting `'Q'` `CREATE TABLE` / `ALTER TABLE` via `RedshiftSqlInterceptor` and relaying everything else verbatim; `RedshiftAuthProxy` switched to it.
- Scope limits, stated explicitly: DDL path only, no `S3Service`, Simple Query protocol only, fail-open.
- Checklist: `./mvnw test` passes; new unit + integration tests added; Conventional Commits.
- End the PR description with:

  🤖 Generated with [Claude Code](https://claude.com/claude-code)

- [ ] **Step 4: Do NOT commit the spec or this plan**

`docs/superpowers/specs/2026-09-04-redshift-wire-decoder-intercepting-bridge-design.md` and `docs/superpowers/plans/2026-09-04-redshift-wire-decoder-intercepting-bridge.md` stay untracked. Confirm with `git status` that they are still listed under "Untracked files" and are not part of any commit on the branch.

---

## Self-Review

**1. Spec coverage**

| Spec section | Task |
|---|---|
| `PostgresWireDecoder` (lean, framing + `encodeQuery` + 16 MiB guard, no `AutoCloseable`/budget) | Task 1 |
| `RedshiftInterceptingBridge` (`(client, backend)` ctor, verbatim pump + framed `'Q'` loop, identity-check re-encode, fail-open, `'X'` ends loop, client read-timeout) | Task 2 steps 1–4 |
| `RedshiftAuthProxy` wiring (swap `bridge` → `new RedshiftInterceptingBridge(...).run()`, no ctor/field change) | Task 2 step 5 |
| `RedshiftProxyManager` untouched | Stated in File Structure + Task 2 step 5 |
| `PostgresWireDecoderTest` (frame, multi-message, EOF, `length < 4`, `> 16 MiB` refused pre-body, `encodeQuery` round-trip, `toPacketBytes` byte-exact, `isBetweenMessages`) | Task 1 step 1 |
| `RedshiftInterceptingBridgeTest` (DDL rewritten, non-DDL byte-exact, extended-proto opaque, pump direction, `'X'` ends loop) | Task 2 step 1 |
| Fail-open on `rewrite` throwing | Covered by code (`catch (RuntimeException)`) + the non-DDL passthrough test; no stub seam added (spec allowed skipping it when no real throwing input exists) |
| `RedshiftInterceptorIntegrationTest` — DDL only, `preferQueryMode=simple`, `CREATE TABLE` + `ALTER TABLE` | Task 3 |
| Existing `RedshiftAuthProxyTest` regression check | Task 2 step 6 (with rationale) |
| `docs/services/redshift.md` DDL slice restored, S3 bullets excluded, `## Out of Scope` first bullet corrected | Task 4 |
| Branch from `second/main`, no worktree | Global Constraints + Task 5 |
| Spec + plan not committed | Global Constraints + Task 5 step 4 |
| Commit split (4 commits) | Tasks 1–4, one commit each |

No gaps.

**2. Placeholder scan** — no `TBD` / `TODO` / "add error handling" / "write tests for the above". Every code step carries full source. Task 3 step 2 and the integration snippet flag a *possible* one-line API adjustment against `RedshiftProxyIntegrationTest`, with the exact fallback action — that is a guard, not a placeholder.

**3. Type consistency** — `PostgresWireDecoder` / `FrontendMessage` / `nextMessage()` / `isBetweenMessages()` / `encodeQuery(String)` / `toPacketBytes()` / `getSql()` / `isQuery()` / `MAX_MESSAGE_BYTES` are spelled identically in Task 1's implementation, Task 1's tests, and Task 2's bridge + tests. `RedshiftInterceptingBridge(Socket, Socket)` + `run()` match between Task 2's implementation, Task 2's test, and Task 2 step 5's `RedshiftAuthProxy` call site. `RedshiftSqlInterceptor.rewrite(String)` matches the merged signature.
