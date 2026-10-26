package io.github.hectorvent.floci.services.dsql.dataplane;

import io.github.hectorvent.floci.config.TlsProxyServer;
import io.github.hectorvent.floci.core.common.PostgresWire;
import io.github.hectorvent.floci.services.dsql.DsqlIdentifiers;

import javax.net.ssl.SNIHostName;
import javax.net.ssl.SNIMatcher;
import javax.net.ssl.SNIServerName;
import javax.net.ssl.SSLParameters;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.StandardConstants;
import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.PushbackInputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;
import java.util.stream.Stream;

/**
 * One client connection to a DSQL endpoint, as Aurora DSQL serves it (measured on AWS, eu-west-1,
 * 2026-10-16 to 2026-10-21): TLS is mandatory, the password is an IAM token for the endpoint, the
 * startup is read as DSQL reads it, DSQL's own statements become the {@code sys} calls that
 * implement them, their errors come back in DSQL's words, a transaction lives 300 seconds and a
 * connection an hour.
 */
final class DsqlSession {

    static final Duration CONNECTION_LIFETIME = Duration.ofHours(1);
    // DSQL's (measured): a startup packet of 10,000 bytes or more, and a client message over 10 MiB,
    // are refused with a FATAL before the connection closes. What the database sends is not DSQL's
    // limit to apply, and is read up to 64 MB.
    private static final int MAX_STARTUP = 9_999;
    private static final int MAX_CLIENT_MESSAGE = 10 * 1024 * 1024;
    private static final int MAX_DATABASE_MESSAGE = 64 * 1024 * 1024;
    private static final String INVALID_STARTUP_LENGTH = "invalid length of startup packet";
    private static final String INVALID_MESSAGE_LENGTH = "invalid message length";
    // DSQL's (measured): picked whenever a client offers it, and the one a client starting TLS at
    // once, with no SSLRequest, must offer.
    private static final String ALPN_POSTGRESQL = "postgresql";
    private static final String SERIALIZATION_FAILURE = "40001";
    private static final String QUERY_CANCELED = "57014";
    private static final String ACCESS_DENIED = "unable to accept connection, access denied";

    private final Socket socket;
    private final DsqlEndpoint endpoint;
    private final String sessionId = DsqlIdentifiers.newId();
    // One batch per Query or Sync sent, oldest first, holding what the database's answers to it
    // settle, in the order it answers them. A batch goes with its ReadyForQuery, and with it what an
    // error left unanswered, so a later batch's are never touched.
    private final Deque<Batch> batches = new ArrayDeque<>();
    private Batch openBatch;
    // The prepared statements DSQL tags its own way, and the statement each portal runs, as the
    // database confirmed them. Only the thread relaying the database's answers touches them.
    private final Map<String, String> statementTags = new HashMap<>();
    private final Map<String, String> portalStatements = new HashMap<>();
    private volatile String principalArn;
    private volatile int backendPid;
    private volatile long cancelKey;
    // What the age limit needs: where each side is written, whether a statement is still running
    // (a Query or Sync whose ReadyForQuery has not come back), and the open transaction's start.
    private final Object clientWrites = new Object();
    private final Object databaseWrites = new Object();
    private final AtomicInteger awaitingReady = new AtomicInteger();
    private volatile OutputStream clientOut;
    private volatile OutputStream databaseOut;
    private volatile Socket clientSocket;
    private volatile Socket databaseSocket;
    private volatile Instant lastClientMessage;
    private volatile Instant transactionStart;
    // The session's one age check pending, by the endpoint's clock: replaced by the next, and
    // cancelled when the transaction or the connection ends.
    private DsqlEndpoint.ScheduledCheck ageCheck;
    // When the database last answered a batch: a statement it runs next starts no earlier.
    private volatile Instant lastReady;
    // The startup's BackendKeyData, to cancel the session's statement with; and whether the
    // statement being cancelled was cancelled for its transaction's age.
    private volatile byte[] cancelKeyData;
    private volatile boolean cancelledForAge;
    private volatile char transactionStatus = 'I';

    DsqlSession(Socket socket, DsqlEndpoint endpoint) {
        this.socket = socket;
        this.endpoint = endpoint;
        this.lastClientMessage = now();
        this.lastReady = now();
    }

    private Instant now() {
        return endpoint.clock().instant();
    }

    void serve() throws IOException {
        socket.setTcpNoDelay(true);
        socket.setSoTimeout(endpoint.handshakeTimeoutMillis());
        PushbackInputStream rawIn = new PushbackInputStream(socket.getInputStream(), 1);
        OutputStream rawOut = socket.getOutputStream();
        int firstByte = rawIn.read();
        boolean direct = firstByte == TlsProxyServer.TLS_HANDSHAKE;
        if (!direct) {
            if (firstByte >= 0) {
                rawIn.unread(firstByte);
            }
            PostgresWire.Startup first = readPlaintextPacket(rawIn);
            if (first.code() == PostgresWire.CANCEL_REQUEST) {
                endpoint.forwardCancel(first.body());
                return;
            }
            if (first.code() == PostgresWire.GSSENC_REQUEST) {
                rawOut.write('N');
                rawOut.flush();
                first = readPlaintextPacket(rawIn);
            }
            if (first.code() != PostgresWire.SSL_REQUEST) {
                refuse(rawOut, "08P01",
                        "unable to accept connection, SSL is mandatory. AWS Authentication is required.", null);
                return;
            }
            rawOut.write('S');
            rawOut.flush();
        }

        String[] serverName = new String[1];
        // A direct connection's first byte, read to tell it apart, is handed back to the TLS handshake.
        InputStream consumed = direct ? new ByteArrayInputStream(new byte[] {TlsProxyServer.TLS_HANDSHAKE}) : null;
        SSLSocket tls = (SSLSocket) endpoint.sslContext().getSocketFactory().createSocket(socket, consumed, true);
        SSLParameters parameters = tls.getSSLParameters();
        parameters.setSNIMatchers(List.of(new SNIMatcher(StandardConstants.SNI_HOST_NAME) {
            @Override
            public boolean matches(SNIServerName name) {
                if (name instanceof SNIHostName host) {
                    serverName[0] = host.getAsciiName().toLowerCase(Locale.ROOT);
                }
                return true;
            }
        }));
        tls.setSSLParameters(parameters);
        tls.setUseClientMode(false);
        tls.setHandshakeApplicationProtocolSelector((handshaking, offered) ->
                offered.contains(ALPN_POSTGRESQL) ? ALPN_POSTGRESQL : "");
        tls.startHandshake();
        if (direct && !ALPN_POSTGRESQL.equals(tls.getApplicationProtocol())) {
            // DSQL completes a direct handshake without postgresql as its protocol, then closes the
            // connection without a word (measured).
            tls.close();
            return;
        }

        InputStream in = new BufferedInputStream(tls.getInputStream());
        OutputStream out = new BufferedOutputStream(tls.getOutputStream());
        PostgresWire.Startup startup;
        try {
            startup = PostgresWire.readStartup(in, MAX_STARTUP, "startup message");
        } catch (PostgresWire.InvalidLengthException e) {
            refuse(out, "08P01", INVALID_STARTUP_LENGTH, null);
            return;
        }
        if (startup.code() == PostgresWire.CANCEL_REQUEST) {
            endpoint.forwardCancel(startup.body());
            return;
        }
        if (startup.code() != PostgresWire.PROTOCOL_3) {
            refuse(out, "08P01", "unsupported frontend protocol", null);
            return;
        }
        Map<String, String> startupParameters = PostgresWire.startupParameters(startup.body());
        String user = startupParameters.getOrDefault("user", "");
        String database = startupParameters.getOrDefault("database", user);

        PostgresWire.write(out, (byte) 'R', PostgresWire.int32(3));
        out.flush();
        PostgresWire.Message password;
        try {
            password = PostgresWire.read(in, MAX_CLIENT_MESSAGE, "message");
        } catch (PostgresWire.InvalidLengthException e) {
            refuse(out, "08P01", INVALID_MESSAGE_LENGTH, null);
            return;
        }
        if (password.type() != 'p') {
            refuse(out, "08006", ACCESS_DENIED, null);
            return;
        }
        if (serverName[0] == null) {
            // DSQL knows the cluster only by the TLS server name, and refuses a connection that sends
            // none once its password is in, whatever the password (measured).
            refuse(out, "08P01", "unable to accept connection, sni was not received", null);
            return;
        }
        String token = PostgresWire.cString(password.body(), 0).value();
        DsqlEndpoint.Admission admission = endpoint.admit(token, user, serverName[0],
                socket.getInetAddress().getHostAddress());
        if (!admission.allowed()) {
            refuse(out, admission.state(), ACCESS_DENIED, admission.hint());
            return;
        }
        if (!"postgres".equals(database)) {
            refuse(out, "55000", "database \"" + database + "\" is not currently accepting connections", null);
            return;
        }
        Map<String, String> optionSettings = optionSettings(startupParameters.get("options"));
        String custom = customParameter(optionSettings, startupParameters);
        if (custom != null) {
            refuse(out, "42704", "unrecognized configuration parameter \"" + custom + "\"", null);
            return;
        }

        DsqlDatabases.Backend target = admission.route().backend();
        endpoint.opened(admission.route(), tls);
        Socket backend = new Socket();
        try (backend) {
            backend.connect(new InetSocketAddress(target.host(), target.port()), 5_000);
            backend.setTcpNoDelay(true);
            socket.setSoTimeout(0);
            InputStream backendIn = new BufferedInputStream(backend.getInputStream());
            OutputStream backendOut = new BufferedOutputStream(backend.getOutputStream());
            Map<String, String> backendStartup = new LinkedHashMap<>(startupParameters);
            backendStartup.remove("options");
            if (!optionSettings.isEmpty()) {
                backendStartup.put("options", backendOptions(optionSettings));
            }
            backendStartup.put("database", "postgres");
            principalArn = admission.principalArn();
            backendOut.write(PostgresWire.startupMessage(backendStartup));
            backendOut.flush();
            clientOut = out;
            databaseOut = backendOut;
            clientSocket = tls;
            databaseSocket = backend;

            Thread toClient = Thread.ofVirtual().name("dsql-backend-" + sessionId)
                    .start(() -> relayToClient(backendIn, out, admission.route(), tls));
            Thread lifetime = Thread.ofVirtual().name("dsql-lifetime-" + sessionId).start(() -> {
                try {
                    Thread.sleep(CONNECTION_LIFETIME.toMillis());
                    closeQuietly(tls);
                    closeQuietly(backend);
                } catch (InterruptedException ignored) {
                    // The connection ended first.
                }
            });
            try {
                relayToBackend(in, backendOut);
            } finally {
                cancelAgeCheck();
                lifetime.interrupt();
                closeQuietly(backend);
                toClient.interrupt();
            }
        } finally {
            endpoint.closed(admission.route(), tls);
            if (cancelKey != 0) {
                endpoint.forgetCancelKey(cancelKey);
            }
        }
    }

    private void relayToBackend(InputStream in, OutputStream backendOut) throws IOException {
        while (true) {
            PostgresWire.Message message;
            try {
                message = PostgresWire.read(in, MAX_CLIENT_MESSAGE, "message");
            } catch (PostgresWire.InvalidLengthException e) {
                close("FATAL", "08P01", INVALID_MESSAGE_LENGTH, "Session Id: " + sessionId);
                return;
            }
            if (message.type() != 'X') {
                lastClientMessage = now();
                if (transactionStatus == 'T' && pastAgeLimit()) {
                    // DSQL ends a transaction past its age at the client's next message, a ROLLBACK
                    // included, and the connection with it (measured).
                    terminate();
                    return;
                }
                if (message.type() == 'Q' || message.type() == 'S' || message.type() == 'F') {
                    awaitingReady.incrementAndGet();
                }
            }
            synchronized (databaseWrites) {
                if (relayOne(message, backendOut)) {
                    return;
                }
                if (in.available() == 0) {
                    backendOut.flush();
                }
            }
        }
    }

    /** Hands one client message to the database, DSQL's syntax rewritten; true once the client said goodbye. */
    private boolean relayOne(PostgresWire.Message message, OutputStream backendOut) throws IOException {
        switch (message.type()) {
            case 'Q' -> {
                String query = PostgresWire.cString(message.body(), 0).value();
                PostgresWire.write(backendOut, 'Q', PostgresWire.cStringBytes(rewriteSimpleQuery(query)));
            }
            case 'P' -> {
                PostgresWire.CString name = PostgresWire.cString(message.body(), 0);
                PostgresWire.CString query = PostgresWire.cString(message.body(), name.next());
                DsqlStatements.Query rewritten = DsqlStatements.rewriteQuery(query.value());
                String tag = rewritten.overridesTags() && rewritten.commandTags().size() == 1
                        ? rewritten.commandTags().get(0) : null;
                synchronized (batches) {
                    extendedBatch().parses.addLast(new Named(name.value(), tag));
                }
                PostgresWire.write(backendOut, 'P', PostgresWire.cStringBytes(name.value()),
                        PostgresWire.cStringBytes(rewritten.sql()),
                        Arrays.copyOfRange(message.body(), query.next(), message.body().length));
            }
            case 'B' -> {
                PostgresWire.CString portal = PostgresWire.cString(message.body(), 0);
                PostgresWire.CString statement = PostgresWire.cString(message.body(), portal.next());
                synchronized (batches) {
                    extendedBatch().binds.addLast(new Named(portal.value(), statement.value()));
                }
                PostgresWire.write(backendOut, message);
            }
            case 'E' -> {
                String portal = PostgresWire.cString(message.body(), 0).value();
                synchronized (batches) {
                    extendedBatch().results.addLast(new Named(portal, null));
                }
                PostgresWire.write(backendOut, message);
            }
            case 'C' -> {
                String kind = message.body()[0] == 'S' ? "S" : "P";
                synchronized (batches) {
                    extendedBatch().closes.addLast(new Named(PostgresWire.cString(message.body(), 1).value(), kind));
                }
                PostgresWire.write(backendOut, message);
            }
            case 'S', 'F' -> {
                // A Sync, or a FunctionCall, is answered by a ReadyForQuery of its own.
                synchronized (batches) {
                    endBatch();
                }
                PostgresWire.write(backendOut, message);
            }
            case 'X' -> {
                PostgresWire.write(backendOut, message);
                backendOut.flush();
                return true;
            }
            default -> PostgresWire.write(backendOut, message);
        }
        return false;
    }

    /** When {@code batch} could first run: once sent, and once the database had answered the batch before it. */
    private Instant couldRun(Batch batch) {
        Instant answeredBefore = lastReady;
        return batch.sent.isAfter(answeredBefore) ? batch.sent : answeredBefore;
    }

    /** When the transaction the database is running started: the open one, or the batch's own. */
    private Instant runningTransactionStart() {
        Instant start = transactionStatus == 'T' ? transactionStart : null;
        if (start == null) {
            synchronized (batches) {
                Batch answering = batches.peekFirst();
                start = answering == null ? null : couldRun(answering);
            }
        }
        return start;
    }

    private Duration ageOfRunning() {
        Instant start = runningTransactionStart();
        return start == null ? Duration.ZERO : Duration.between(start, now());
    }

    /** DSQL's age error for a transaction {@code age} old. */
    private byte[] ageError(Duration age) {
        long limit = endpoint.transactionAgeLimit().toSeconds();
        return error("ERROR", "54000", "transaction age limit of " + limit + "s exceeded",
                "Current transaction age " + age.toSeconds() + "s exceeds " + limit + "s", null);
    }

    private boolean pastAgeLimit() {
        Instant start = transactionStart;
        return start != null && Duration.between(start, now()).compareTo(endpoint.transactionAgeLimit()) > 0;
    }

    private Duration transactionAge() {
        Instant start = transactionStart;
        return start == null ? Duration.ZERO : Duration.between(start, now());
    }

    /**
     * Follows the transaction the database reports. One that opens started when its batch could
     * first run: once sent, and once the database had answered the batch before it, so a BEGIN
     * queued behind a long statement does not count that wait. At its age limit a statement still
     * running is stopped with the age error, which fails the transaction; idle past the limit and
     * its grace the transaction is ended as DSQL ends it (measured: 315 s after BEGIN, whatever it
     * last ran). One that fails stays until it idles as long, as DSQL's idle-in-transaction
     * timeout lets it.
     */
    private void transactionStatus(char status, Instant batchCouldRun) {
        transactionStatus = status;
        if (status == 'I') {
            transactionStart = null;
            cancelAgeCheck();
        } else if (status == 'T' && transactionStart == null) {
            Instant start = batchCouldRun;
            transactionStart = start;
            schedule(start.plus(endpoint.transactionAgeLimit()), () -> {
                if (start.equals(transactionStart) && transactionStatus == 'T') {
                    cancelForAge();
                    schedule(start.plus(endpoint.transactionAgeLimit()).plus(endpoint.transactionIdleGrace()), () -> {
                        if (start.equals(transactionStart) && transactionStatus == 'T' && awaitingReady.get() == 0) {
                            terminate();
                        }
                    });
                }
            });
        } else if (status == 'E') {
            closeWhenIdle(now());
        }
    }

    /**
     * Outside a transaction block, the batch the database runs next is a transaction of its own,
     * under the same limit (measured): one still running at the limit is stopped with the age error.
     */
    private void watchImplicitTransaction(Batch running, Instant couldRun) {
        schedule(couldRun.plus(endpoint.transactionAgeLimit()), () -> {
            boolean stillRunning;
            synchronized (batches) {
                stillRunning = batches.peekFirst() == running;
            }
            if (stillRunning && transactionStatus == 'I') {
                cancelForAge();
            }
        });
    }

    /**
     * Stops the statement running at the transaction's age limit, with the cancel request a client
     * would send; its {@code 57014} reaches the client as DSQL's age error. DSQL lets the statement
     * finish and fails it then (measured); stopping it at the limit ends the same way, the error
     * and nothing kept, the rest of its query, a COMMIT included, never run.
     */
    private void cancelForAge() {
        byte[] keyData = cancelKeyData;
        if (awaitingReady.get() > 0 && keyData != null) {
            cancelledForAge = true;
            endpoint.forwardCancel(keyData);
        }
    }

    /**
     * Closes a failed transaction's connection once the client has sent nothing for its idle time
     * since {@code since}; a message in between moves the wait to start from it.
     */
    private void closeWhenIdle(Instant since) {
        schedule(since.plus(endpoint.transactionAgeLimit()).plus(endpoint.transactionIdleGrace()), () -> {
            Instant last = lastClientMessage;
            if (transactionStatus != 'E' || awaitingReady.get() > 0) {
                return;
            }
            if (last.isAfter(since)) {
                closeWhenIdle(last);
                return;
            }
            close("FATAL", "25P03", "terminating connection due to idle-in-transaction timeout", null);
        });
    }

    /** Puts {@code check}, due at {@code when}, in place of the age check pending. */
    private synchronized void schedule(Instant when, Runnable check) {
        if (ageCheck != null) {
            ageCheck.cancel();
        }
        ageCheck = endpoint.schedule(when, check);
    }

    private synchronized void cancelAgeCheck() {
        if (ageCheck != null) {
            ageCheck.cancel();
            ageCheck = null;
        }
    }

    /** DSQL's end of a transaction past its age: the age error, then the connection's (measured). */
    private void terminate() {
        try {
            synchronized (clientWrites) {
                PostgresWire.write(clientOut, 'E', ageError(transactionAge()));
            }
        } catch (IOException ignored) {
            // The client left first; the connection is closed below either way.
        }
        close("FATAL", "08P01", "terminating connection because protocol synchronization was lost", null);
    }

    private void close(String severity, String state, String message, String detail) {
        try {
            synchronized (clientWrites) {
                PostgresWire.write(clientOut, 'E', error(severity, state, message, detail, null));
                clientOut.flush();
            }
        } catch (IOException ignored) {
            // The client left first; both sides are closed below either way.
        }
        closeQuietly(clientSocket);
        closeQuietly(databaseSocket);
    }

    /**
     * A simple query as the database runs it. Its statements' command tags join the batch its
     * ReadyForQuery ends: the extended-protocol messages sent since the last Sync, if any.
     */
    private String rewriteSimpleQuery(String query) {
        DsqlStatements.Query rewritten = DsqlStatements.rewriteQuery(query);
        synchronized (batches) {
            Batch batch = endBatch();
            rewritten.commandTags().forEach(tag -> batch.results.addLast(new Named(null, tag)));
        }
        return rewritten.sql();
    }

    /**
     * The batch a Query, Sync or FunctionCall ends, the extended-protocol messages since the last
     * Sync included, which its ReadyForQuery answers. Called holding {@code batches}.
     */
    private Batch endBatch() {
        Batch batch = extendedBatch();
        openBatch = null;
        return batch;
    }

    /** The batch the extended-protocol messages since the last Sync belong to. Called holding {@code batches}. */
    private Batch extendedBatch() {
        if (openBatch == null) {
            openBatch = new Batch(now());
            batches.addLast(openBatch);
            if (batches.size() == 1 && transactionStatus == 'I') {
                watchImplicitTransaction(openBatch, couldRun(openBatch));
            }
        }
        return openBatch;
    }

    /**
     * What one Query or Sync's messages wait for the database to settle, each kind in the order the
     * database answers it: the statements parsed and their tags, the portals bound and the statements
     * they run, the statements and portals closed, and the results, each a portal's or a simple
     * query statement's tag.
     */
    private static final class Batch {
        final Instant sent;
        final Deque<Named> parses = new ArrayDeque<>();
        final Deque<Named> binds = new ArrayDeque<>();
        final Deque<Named> closes = new ArrayDeque<>();
        final Deque<Named> results = new ArrayDeque<>();

        Batch(Instant sent) {
            this.sent = sent;
        }
    }

    /** A statement or portal name, and what goes with it; a simple query's result has no name. */
    private record Named(String name, String value) {
    }

    /** The answer of the oldest batch the database is answering, from {@code queue}. */
    private Named settle(Function<Batch, Deque<Named>> queue) {
        synchronized (batches) {
            Batch batch = batches.peekFirst();
            return batch == null ? null : queue.apply(batch).pollFirst();
        }
    }

    /**
     * The settings a client's startup {@code options} make, as DSQL reads them (measured, eu-west-1,
     * 2026-10-20): words split on white space, with no escapes, of which only {@code -c name=value}
     * counts, the first for a name winning. DSQL ignores every other word, {@code --name=value}
     * included, so none of them may reach the database.
     */
    static Map<String, String> optionSettings(String options) {
        Map<String, String> settings = new LinkedHashMap<>();
        Set<String> seen = new HashSet<>();
        String[] words = options == null ? new String[0] : options.strip().split("\\s+");
        for (int i = 0; i + 1 < words.length; i++) {
            if ("-c".equals(words[i])) {
                String setting = words[++i];
                int equals = setting.indexOf('=');
                if (equals > 0 && seen.add(setting.substring(0, equals).toLowerCase(Locale.ROOT))) {
                    settings.put(setting.substring(0, equals), setting.substring(equals + 1));
                }
            }
        }
        return settings;
    }

    /**
     * The first custom (dotted) parameter a startup sets, in its {@code options} or on its own: DSQL
     * refuses the connection for one (measured, eu-west-1, 2026-10-20), {@code dsql.} or not.
     */
    static String customParameter(Map<String, String> optionSettings, Map<String, String> startupParameters) {
        return Stream.concat(optionSettings.keySet().stream(), startupParameters.keySet().stream())
                .filter(name -> name.contains(".")).findFirst().orElse(null);
    }

    /** {@code settings} as PostgreSQL's {@code options}, whose backslash escapes the next character. */
    static String backendOptions(Map<String, String> settings) {
        List<String> words = new ArrayList<>();
        settings.forEach((name, value) -> words.add("-c " + (name + "=" + value).replace("\\", "\\\\")));
        return String.join(" ", words);
    }

    private void relayToClient(InputStream backendIn, OutputStream out, DsqlEndpoint.Route route, Socket client) {
        try {
            List<PostgresWire.Message> startup = new ArrayList<>();
            boolean started = false;
            while (true) {
                PostgresWire.Message message = PostgresWire.read(backendIn, MAX_DATABASE_MESSAGE, "message");
                if (!started) {
                    // DSQL refuses a connection instead of authenticating it, so what the database
                    // answers the startup waits for its ReadyForQuery, and a refusal takes its place.
                    switch (message.type()) {
                        case 'E' -> {
                            startup.clear();
                            message = new PostgresWire.Message(message.type(), withSessionId(message.body()));
                        }
                        case 'Z' -> {
                            // The session's identity is in place before the client's first statement.
                            if (!endpoint.recordSession(route.backend(), backendPid, principalArn, sessionId)) {
                                refuse(out, "08006", ACCESS_DENIED, null);
                                closeQuietly(client);
                                return;
                            }
                            for (PostgresWire.Message answer : startup) {
                                PostgresWire.write(out, answer);
                            }
                            started = true;
                        }
                        case 'K' -> {
                            cancelKey = endpoint.registerCancelKey(message.body(), route.backend());
                            backendPid = PostgresWire.int32(message.body(), 0);
                            cancelKeyData = message.body();
                            startup.add(message);
                            continue;
                        }
                        default -> {
                            startup.add(message);
                            continue;
                        }
                    }
                }
                switch (message.type()) {
                    case 'C' -> {
                        Named result = settle(batch -> batch.results);
                        String override = result == null ? null : result.name() == null ? result.value()
                                : statementTags.get(portalStatements.get(result.name()));
                        if (override != null) {
                            message = new PostgresWire.Message((byte) 'C', PostgresWire.cStringBytes(override));
                        }
                    }
                    case 'I', 's' -> settle(batch -> batch.results);
                    case '1' -> {
                        Named parsed = settle(batch -> batch.parses);
                        if (parsed != null && parsed.value() != null) {
                            statementTags.put(parsed.name(), parsed.value());
                        } else if (parsed != null) {
                            statementTags.remove(parsed.name());
                        }
                    }
                    case '2' -> {
                        Named bound = settle(batch -> batch.binds);
                        if (bound != null) {
                            portalStatements.put(bound.name(), bound.value());
                        }
                    }
                    case '3' -> {
                        Named closed = settle(batch -> batch.closes);
                        if (closed != null) {
                            (closed.value().equals("S") ? statementTags : portalStatements).remove(closed.name());
                        }
                    }
                    case 'E', 'N' -> {
                        if (message.type() == 'E') {
                            String state = PostgresWire.parseFields(message.body(), 0).get('C');
                            if (cancelledForAge && QUERY_CANCELED.equals(state)) {
                                cancelledForAge = false;
                                message = new PostgresWire.Message(message.type(), ageError(ageOfRunning()));
                                break;
                            }
                        }
                        message = new PostgresWire.Message(message.type(), inDsqlWords(message.type(), message.body()));
                    }
                    case 'Z' -> {
                        awaitingReady.updateAndGet(n -> Math.max(0, n - 1));
                        // A cancel that reached no statement leaves nothing for a later one to inherit.
                        cancelledForAge = false;
                        Batch answered;
                        synchronized (batches) {
                            answered = batches.pollFirst();
                        }
                        Instant couldRun = answered != null ? couldRun(answered) : lastReady;
                        lastReady = now();
                        if (message.body()[0] == 'I') {
                            // Outside a transaction no portal outlives its Sync.
                            portalStatements.clear();
                        }
                        char status = (char) message.body()[0];
                        if (started) {
                            transactionStatus(status, couldRun);
                            Batch next;
                            synchronized (batches) {
                                next = batches.peekFirst();
                            }
                            if (status == 'I' && next != null) {
                                watchImplicitTransaction(next, couldRun(next));
                            }
                        }
                    }
                    default -> {
                    }
                }
                synchronized (clientWrites) {
                    PostgresWire.write(out, message);
                    if (backendIn.available() == 0) {
                        out.flush();
                    }
                }
            }
        } catch (IOException ignored) {
            // The database or the client ended the connection: the client's side is closed with it.
            closeQuietly(client);
        }
    }

    /**
     * An error or notice as DSQL words it (measured on AWS). An optimistic concurrency conflict is
     * DSQL's OC000. An error carries no source location, PostgreSQL's own included, while a notice
     * keeps its location. The context keeps what the client's SQL ran, such as the statement a
     * foreign key's cascade ran, and drops what Floci's {@code sys} functions ran, as DSQL's context
     * never shows its own: the context lists the innermost call first, so everything up to the last
     * line of a {@code sys} function, the statements it ran included, is theirs.
     */
    static byte[] inDsqlWords(byte type, byte[] body) {
        Map<Character, String> fields = PostgresWire.parseFields(body, 0);
        if (type == 'E' && SERIALIZATION_FAILURE.equals(fields.get('C'))) {
            Map<Character, String> conflict = new LinkedHashMap<>();
            conflict.put('S', fields.getOrDefault('S', "ERROR"));
            conflict.put('V', fields.getOrDefault('V', "ERROR"));
            conflict.put('C', SERIALIZATION_FAILURE);
            conflict.put('M', "change conflicts with another transaction (OC000)");
            return PostgresWire.fields(conflict);
        }
        boolean changed = false;
        String where = fields.get('W');
        if (where != null) {
            List<String> lines = where.lines().toList();
            int ownLines = 0;
            for (int i = 0; i < lines.size(); i++) {
                if (lines.get(i).contains("function sys.")) {
                    ownLines = i + 1;
                }
            }
            String kept = String.join("\n", lines.subList(ownLines, lines.size()));
            if (!kept.equals(where)) {
                changed = true;
                if (kept.isEmpty()) {
                    fields.remove('W');
                } else {
                    fields.put('W', kept);
                }
            }
        }
        if (type == 'E') {
            changed |= fields.remove('F') != null;
            changed |= fields.remove('L') != null;
            changed |= fields.remove('R') != null;
        }
        return changed ? PostgresWire.fields(fields) : body;
    }

    /** A refusal of the database's as DSQL words every one: the session's id as its detail. */
    private byte[] withSessionId(byte[] body) {
        Map<Character, String> fields = PostgresWire.parseFields(body, 0);
        fields.putIfAbsent('D', "Session Id: " + sessionId);
        return PostgresWire.fields(fields);
    }

    /**
     * A packet sent before TLS. DSQL refuses one that is no SSLRequest, GSSENCRequest or
     * CancelRequest as not TLS whatever length it claims, and ends the connection without a word on
     * one under 8 bytes (measured). So a length past what a startup packet may hold is refused with
     * its body left unread; a shorter body is read, so the refusal reaches the client in order.
     */
    private static PostgresWire.Startup readPlaintextPacket(InputStream in) throws IOException {
        int length = PostgresWire.checkedLength(PostgresWire.readInt32(in), 8, Integer.MAX_VALUE, "startup message");
        int code = PostgresWire.readInt32(in);
        if (length > MAX_STARTUP) {
            return new PostgresWire.Startup(code, new byte[0]);
        }
        byte[] body = new byte[length - 8];
        PostgresWire.readFully(in, body);
        return new PostgresWire.Startup(code, body);
    }

    /** An ErrorResponse body of {@code severity} (FATAL, ERROR), as DSQL orders its fields. */
    static byte[] error(String severity, String state, String message, String detail, String hint) {
        Map<Character, String> fields = new LinkedHashMap<>();
        fields.put('S', severity);
        fields.put('V', severity);
        fields.put('C', state);
        fields.put('M', message);
        if (detail != null) {
            fields.put('D', detail);
        }
        if (hint != null) {
            fields.put('H', hint);
        }
        return PostgresWire.fields(fields);
    }

    /** A refusal as DSQL words every one: FATAL, with the session's id as its detail. */
    private void refuse(OutputStream out, String state, String message, String hint) throws IOException {
        PostgresWire.write(out, (byte) 'E', error("FATAL", state, message, "Session Id: " + sessionId, hint));
        out.flush();
    }

    private static void closeQuietly(Socket closing) {
        try {
            closing.close();
        } catch (IOException ignored) {
            // Already closed.
        }
    }
}
