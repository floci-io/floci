package io.github.hectorvent.floci.services.elasticache.proxy;

import io.github.hectorvent.floci.core.common.ListenBacklog;
import org.jboss.logging.Logger;

import java.io.BufferedInputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.LinkedBlockingQueue;

/**
 * Shared TCP auth-proxy skeleton for the Redis (RESP) wire protocol. Intercepts the
 * {@code AUTH} command or the {@code AUTH} option in {@code HELLO}, validates credentials
 * through the subclass, then becomes a transparent byte relay to the backend container.
 * Used by both the ElastiCache and MemoryDB proxies, since MemoryDB's real AWS design is
 * explicitly modeled on ElastiCache: same Redis-compatible wire protocol, same IAM-auth
 * mechanism.
 *
 * <p>Uses Java virtual threads to accept connections and run the AUTH handshake.
 */
public abstract class AbstractRedisAuthProxy {
    private static final int HANDSHAKE_TIMEOUT_MILLIS = 10_000;
    private static final long RELAY_JOIN_TIMEOUT_MILLIS = 1_000;

    private static final byte[] OK_RESPONSE = "+OK\r\n".getBytes(StandardCharsets.UTF_8);
    private static final byte[] NOAUTH_RESPONSE =
            "-NOAUTH Authentication required.\r\n".getBytes(StandardCharsets.UTF_8);
    private static final byte[] INVALID_AUTH_RESPONSE =
            "-ERR invalid username-password pair or user is disabled.\r\n"
                    .getBytes(StandardCharsets.UTF_8);
    private static final byte[] WRONGPASS_RESPONSE =
            "-WRONGPASS invalid username-password pair or user is disabled.\r\n"
                    .getBytes(StandardCharsets.UTF_8);
    private static final byte[] WRONG_ARGS_RESPONSE =
            "-ERR wrong number of arguments for 'auth' command\r\n"
                    .getBytes(StandardCharsets.UTF_8);
    private static final byte[] HELLO_NOAUTH_RESPONSE =
            ("-NOAUTH HELLO must be called with the client already authenticated, otherwise the "
                    + "HELLO <proto> AUTH <user> <pass> option can be used to authenticate the client "
                    + "and select the RESP protocol version at the same time\r\n")
                    .getBytes(StandardCharsets.UTF_8);
    private static final byte[] HELLO_PROTOCOL_ERROR_RESPONSE =
            "-ERR Protocol version is not an integer or out of range\r\n"
                    .getBytes(StandardCharsets.UTF_8);
    private static final byte[] HELLO_UNSUPPORTED_PROTOCOL_RESPONSE =
            "-NOPROTO unsupported protocol version\r\n".getBytes(StandardCharsets.UTF_8);
    private static final byte[] INVALID_CLIENT_NAME_RESPONSE =
            "-ERR Client names cannot contain spaces, newlines or special characters.\r\n"
                    .getBytes(StandardCharsets.UTF_8);

    private final Logger log;
    private final String serviceName;
    private final String threadPrefix;
    private final String resourceId;
    private final String backendHost;
    private final int backendPort;
    private final int handshakeTimeoutMillis;
    private final Set<Socket> handshakingClients = ConcurrentHashMap.newKeySet();
    private final Set<Socket> unencryptedClients = ConcurrentHashMap.newKeySet();

    private volatile boolean running;
    private ServerSocket serverSocket;

    protected AbstractRedisAuthProxy(Logger log, String serviceName, String threadPrefix,
                                      String resourceId, String backendHost, int backendPort) {
        this(log, serviceName, threadPrefix, resourceId, backendHost, backendPort,
                HANDSHAKE_TIMEOUT_MILLIS);
    }

    protected AbstractRedisAuthProxy(Logger log, String serviceName, String threadPrefix,
                                      String resourceId, String backendHost, int backendPort,
                                      int handshakeTimeoutMillis) {
        this.log = log;
        this.serviceName = serviceName;
        this.threadPrefix = threadPrefix;
        this.resourceId = resourceId;
        this.backendHost = backendHost;
        this.backendPort = backendPort;
        this.handshakeTimeoutMillis = handshakeTimeoutMillis;
    }

    /** Whether the {@code AUTH} command is required before bridging to the backend. */
    protected abstract boolean authRequired();

    /** Validates the supplied credentials. Not called when {@link #authRequired()} is false. */
    protected abstract boolean authenticate(String username, String password);

    /** Closes a socket, tolerating errors. Implementations may add their own logging. */
    protected abstract void closeQuietly(Socket socket);

    /** Applies the validated frontend identity to a newly connected backend. */
    protected void initializeBackend(Socket backend, String username) throws IOException {
    }

    /** Establishes the client transport before reading credentials. */
    protected ClientTransport clientTransport(Socket client) throws IOException {
        return ClientTransport.unencrypted(client);
    }

    /**
     * The connection a client's commands arrive on: the socket to answer and close, and the stream
     * to read, which replays any bytes already read to choose the transport.
     */
    protected record ClientTransport(Socket socket, InputStream input, boolean encrypted) {
        static ClientTransport unencrypted(Socket socket) throws IOException {
            return new ClientTransport(socket, socket.getInputStream(), false);
        }

        static ClientTransport encrypted(Socket socket) throws IOException {
            return new ClientTransport(socket, socket.getInputStream(), true);
        }
    }

    public void start(int proxyPort) throws IOException {
        serverSocket = listenSocket(proxyPort, ListenBacklog.ACCEPT);
        running = true;
        Thread.ofVirtual().name(threadPrefix + "-proxy-accept-" + resourceId).start(this::acceptLoop);
        log.infov("{0} proxy started for {1} on port {2} → {3}:{4}",
                serviceName, resourceId, String.valueOf(proxyPort), backendHost, String.valueOf(backendPort));
    }

    /**
     * Opens the listening socket. A seam so a test can pin the accept backlog: a bound
     * {@code ServerSocket} does not report the backlog it was created with.
     */
    ServerSocket listenSocket(int proxyPort, int backlog) throws IOException {
        return new ServerSocket(proxyPort, backlog);
    }

    int localPort() {
        return serverSocket.getLocalPort();
    }

    public void stop() {
        running = false;
        try {
            if (serverSocket != null) {
                serverSocket.close();
            }
        } catch (IOException e) {
            log.warnv("Error closing proxy server socket for {0}: {1}", resourceId, e.getMessage());
        }
        for (Socket client : handshakingClients) {
            closeQuietly(client);
        }
        handshakingClients.clear();
    }

    /** Closes every connection that is not encrypted, as AWS does once transit encryption is required. */
    public void dropUnencryptedConnections() {
        for (Socket client : unencryptedClients) {
            closeQuietly(client);
        }
    }

    private void acceptLoop() {
        while (running) {
            try {
                Socket client = serverSocket.accept();
                handshakingClients.add(client);
                if (!running) {
                    handshakingClients.remove(client);
                    closeQuietly(client);
                    continue;
                }
                Thread.ofVirtual().name(threadPrefix + "-proxy-conn-" + resourceId)
                        .start(() -> handleConnection(client));
            } catch (IOException e) {
                if (running) {
                    log.warnv("Accept error for {0}: {1}", resourceId, e.getMessage());
                }
            }
        }
    }

    private void handleConnection(Socket client) {
        try {
            client.setTcpNoDelay(true);
            client.setSoTimeout(handshakeTimeoutMillis);
            ClientTransport transport = clientTransport(client);
            if (transport.socket() != client) {
                handshakingClients.add(transport.socket());
                handshakingClients.remove(client);
                client = transport.socket();
            }
            if (!transport.encrypted()) {
                unencryptedClients.add(client);
            }
            if (!running) {
                closeQuietly(client);
                return;
            }
            InputStream input = transport.input();
            RespReader reader = new RespReader(input);
            while (true) {
                String[] cmd = reader.readCommand();

                if (cmd.length == 0) {
                    closeQuietly(client);
                    return;
                }

                if (cmd[0].equalsIgnoreCase("AUTH")) {
                    handleAuth(client, input, cmd);
                    return;
                }
                if (cmd[0].equalsIgnoreCase("HELLO")) {
                    if (authRequired() || hasAuthOption(cmd)) {
                        if (handleHello(client, input, cmd)) {
                            return;
                        }
                        continue;
                    }
                    bridgeCommand(client, input, cmd, null);
                    return;
                }
                if (authRequired()) {
                    writeResponse(client, NOAUTH_RESPONSE);
                    closeQuietly(client);
                    return;
                }

                bridgeCommand(client, input, cmd, null);
                return;
            }
        } catch (Exception e) {
            log.debugv("Connection error for {0}: {1}", resourceId, e.getMessage());
            closeQuietly(client);
        } finally {
            handshakingClients.remove(client);
            unencryptedClients.remove(client);
        }
    }

    private void handleAuth(Socket client, InputStream input, String[] cmd) throws IOException {
        String username;
        String password;

        if (cmd.length == 2) {
            // AUTH password
            username = null;
            password = cmd[1];
        } else if (cmd.length == 3) {
            // AUTH username password
            username = cmd[1];
            password = cmd[2];
        } else {
            client.getOutputStream().write(WRONG_ARGS_RESPONSE);
            client.getOutputStream().flush();
            closeQuietly(client);
            return;
        }

        boolean authenticated = authenticate(username, password);
        if (!authenticated) {
            writeResponse(client, INVALID_AUTH_RESPONSE);
            closeQuietly(client);
            return;
        }

        bridgeCommand(client, input, null, username);
    }

    private boolean handleHello(Socket client, InputStream input, String[] cmd) throws IOException {
        if (cmd.length > 1) {
            long protocolVersion;
            try {
                protocolVersion = Long.parseLong(cmd[1]);
            } catch (NumberFormatException e) {
                writeResponse(client, HELLO_PROTOCOL_ERROR_RESPONSE);
                return false;
            }
            if (protocolVersion < 2 || protocolVersion > 3) {
                writeResponse(client, HELLO_UNSUPPORTED_PROTOCOL_RESPONSE);
                return false;
            }
        }

        List<String> forwarded = new ArrayList<>();
        forwarded.add(cmd[0]);
        if (cmd.length > 1) {
            forwarded.add(cmd[1]);
        }

        String username = null;
        String password = null;
        String clientName = null;
        boolean authProvided = false;
        boolean clientNameProvided = false;
        int optionIndex = 2;
        while (optionIndex < cmd.length) {
            String option = cmd[optionIndex];
            if (option.equalsIgnoreCase("AUTH") && optionIndex + 2 < cmd.length) {
                username = cmd[optionIndex + 1];
                password = cmd[optionIndex + 2];
                authProvided = true;
                optionIndex += 3;
            } else if (option.equalsIgnoreCase("SETNAME") && optionIndex + 1 < cmd.length) {
                clientName = cmd[optionIndex + 1];
                if (!isValidClientName(clientName)) {
                    writeResponse(client, INVALID_CLIENT_NAME_RESPONSE);
                    return false;
                }
                clientNameProvided = true;
                optionIndex += 2;
            } else {
                writeHelloSyntaxError(client, option);
                return false;
            }
        }

        if (clientNameProvided) {
            forwarded.add("SETNAME");
            forwarded.add(clientName);
        }

        if (authRequired() && !authProvided) {
            writeResponse(client, HELLO_NOAUTH_RESPONSE);
            return false;
        }
        if (authProvided && !authenticate(username, password)) {
            writeResponse(client, WRONGPASS_RESPONSE);
            return false;
        }

        bridgeCommand(client, input, forwarded.toArray(String[]::new), username);
        return true;
    }

    private static void writeHelloSyntaxError(Socket client, String option) throws IOException {
        String sanitizedOption = option.replace('\r', ' ').replace('\n', ' ');
        byte[] response = ("-ERR Syntax error in HELLO option '" + sanitizedOption + "'\r\n")
                .getBytes(StandardCharsets.UTF_8);
        writeResponse(client, response);
    }

    private static boolean isValidClientName(String clientName) {
        for (int index = 0; index < clientName.length(); index++) {
            char character = clientName.charAt(index);
            if (character <= ' ' || character > '~') {
                return false;
            }
        }
        return true;
    }

    private void bridgeCommand(Socket client, InputStream input, String[] command, String username) throws IOException {
        Socket backend = new Socket(backendHost, backendPort);
        try {
            backend.setTcpNoDelay(true);
            initializeBackend(backend, username);
            if (command != null) {
                resendCommand(command, backend.getOutputStream());
            } else {
                writeResponse(client, OK_RESPONSE);
            }
            client.setSoTimeout(0);
            handshakingClients.remove(client);
            bridge(client, input, backend, command != null);
        } catch (IOException e) {
            closeQuietly(backend);
            throw e;
        }
    }

    private static void writeResponse(Socket client, byte[] response) throws IOException {
        client.getOutputStream().write(response);
        client.getOutputStream().flush();
    }

    /**
     * Relay I/O runs on platform daemon threads (not virtual threads). A parent virtual thread
     * from {@code handleConnection} blocks in {@code join} here; scheduling nested virtual-thread
     * relays under load can stall delivery of backend responses (e.g. PING/PONG) to the client.
     */
    private void bridge(Socket client, InputStream clientInput, Socket backend, boolean commandForwarded) throws IOException {
        CountDownLatch firstRelayDone = new CountDownLatch(1);
        BlockingQueue<ReplyAction> replyQueue = new LinkedBlockingQueue<>();
        if (commandForwarded) {
            replyQueue.add(ReplyAction.backend());
        }
        Thread t1 = Thread.ofPlatform().daemon(true).name(threadPrefix + "-relay-c2b-" + resourceId)
                .start(() -> {
                    try {
                        // Bug #4502: Frame client RESP commands to intercept re-authentication on live connections
                        relayClientToBackend(client, clientInput, backend, replyQueue);
                    } finally {
                        firstRelayDone.countDown();
                    }
                });
        Thread t2 = Thread.ofPlatform().daemon(true).name(threadPrefix + "-relay-b2c-" + resourceId)
                .start(() -> {
                    try {
                        relayRepliesToClient(backend, client, replyQueue);
                    } finally {
                        firstRelayDone.countDown();
                    }
                });
        try {
            firstRelayDone.await();
            t1.join(RELAY_JOIN_TIMEOUT_MILLIS);
            t2.join(RELAY_JOIN_TIMEOUT_MILLIS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } finally {
            closeQuietly(client);
            closeQuietly(backend);
        }
    }

    private void relayClientToBackend(Socket client, InputStream clientInput, Socket backend, BlockingQueue<ReplyAction> replyQueue) {
        byte[] transferBuf = new byte[8192];
        try {
            BufferedInputStream in = (clientInput instanceof BufferedInputStream bis)
                    ? bis : new BufferedInputStream(clientInput, 8192);
            OutputStream out = backend.getOutputStream();
            while (running) {
                int first = in.read();
                if (first == -1) {
                    break;
                }
                if (first == '*') {
                    String countLine = readAsciiLine(in);
                    if (countLine == null || countLine.isEmpty()) {
                        break;
                    }
                    int argCount = Integer.parseInt(countLine);
                    if (argCount <= 0) {
                        continue;
                    }
                    String bulkHdr0 = readAsciiLine(in);
                    if (bulkHdr0 == null || !bulkHdr0.startsWith("$")) {
                        break;
                    }
                    int len0 = Integer.parseInt(bulkHdr0.substring(1));
                    byte[] arg0Bytes = in.readNBytes(len0);
                    in.readNBytes(2); // consume \r\n
                    String cmd0 = new String(arg0Bytes, StandardCharsets.US_ASCII);

                    if ("AUTH".equalsIgnoreCase(cmd0)) {
                        String[] authArgs = new String[argCount - 1];
                        for (int i = 0; i < argCount - 1; i++) {
                            String bulkHdr = readAsciiLine(in);
                            int len = Integer.parseInt(bulkHdr.substring(1));
                            byte[] data = in.readNBytes(len);
                            in.readNBytes(2);
                            authArgs[i] = new String(data, StandardCharsets.UTF_8);
                        }
                        String username = (authArgs.length == 2) ? authArgs[0] : null;
                        String password = (authArgs.length == 2) ? authArgs[1] : (authArgs.length == 1 ? authArgs[0] : null);
                        boolean ok = (password != null) && authenticate(username, password);
                        replyQueue.put(ReplyAction.local(ok ? OK_RESPONSE : WRONGPASS_RESPONSE));
                        continue;
                    }
                    if ("HELLO".equalsIgnoreCase(cmd0)) {
                        String[] helloArgs = new String[argCount - 1];
                        for (int i = 0; i < argCount - 1; i++) {
                            String bulkHdr = readAsciiLine(in);
                            int len = Integer.parseInt(bulkHdr.substring(1));
                            byte[] data = in.readNBytes(len);
                            in.readNBytes(2);
                            helloArgs[i] = new String(data, StandardCharsets.UTF_8);
                        }
                        int optionIndex = 0;
                        if (optionIndex < helloArgs.length
                                && !helloArgs[optionIndex].equalsIgnoreCase("AUTH")
                                && !helloArgs[optionIndex].equalsIgnoreCase("SETNAME")) {
                            optionIndex++;
                        }
                        int authIdx = -1;
                        while (optionIndex < helloArgs.length) {
                            String opt = helloArgs[optionIndex];
                            if (opt.equalsIgnoreCase("AUTH") && optionIndex + 2 < helloArgs.length) {
                                authIdx = optionIndex;
                                optionIndex += 3;
                            } else if (opt.equalsIgnoreCase("SETNAME") && optionIndex + 1 < helloArgs.length) {
                                optionIndex += 2;
                            } else {
                                optionIndex++;
                            }
                        }
                        if (authIdx != -1 && authIdx + 2 < helloArgs.length) {
                            String username = helloArgs[authIdx + 1];
                            String password = helloArgs[authIdx + 2];
                            if (!authenticate(username, password)) {
                                replyQueue.put(ReplyAction.local(WRONGPASS_RESPONSE));
                                continue;
                            }
                            List<String> forwarded = new ArrayList<>();
                            forwarded.add(cmd0);
                            for (int i = 0; i < helloArgs.length; i++) {
                                if (i == authIdx) {
                                    i += 2; // skip AUTH user pass
                                } else {
                                    forwarded.add(helloArgs[i]);
                                }
                            }
                            resendCommand(forwarded.toArray(String[]::new), out);
                            replyQueue.put(ReplyAction.backend());
                            continue;
                        }
                        List<String> forwarded = new ArrayList<>();
                        forwarded.add(cmd0);
                        forwarded.addAll(List.of(helloArgs));
                        resendCommand(forwarded.toArray(String[]::new), out);
                        replyQueue.put(ReplyAction.backend());
                        continue;
                    }

                    // Stream all other commands directly to preserve binary payloads
                    out.write(('*' + String.valueOf(argCount) + "\r\n").getBytes(StandardCharsets.US_ASCII));
                    out.write(('$' + String.valueOf(len0) + "\r\n").getBytes(StandardCharsets.US_ASCII));
                    out.write(arg0Bytes);
                    out.write("\r\n".getBytes(StandardCharsets.US_ASCII));
                    for (int i = 1; i < argCount; i++) {
                        String bulkHdr = readAsciiLine(in);
                        int len = Integer.parseInt(bulkHdr.substring(1));
                        out.write(('$' + String.valueOf(len) + "\r\n").getBytes(StandardCharsets.US_ASCII));
                        int remaining = len;
                        while (remaining > 0) {
                            int toRead = Math.min(transferBuf.length, remaining);
                            int r = in.read(transferBuf, 0, toRead);
                            if (r == -1) {
                                throw new IOException("Unexpected EOF in RESP bulk string");
                            }
                            out.write(transferBuf, 0, r);
                            remaining -= r;
                        }
                        in.readNBytes(2); // trailing \r\n
                        out.write("\r\n".getBytes(StandardCharsets.US_ASCII));
                    }
                    out.flush();
                    replyQueue.put(ReplyAction.backend());
                } else {
                    // Inline command fallback
                    String line = (char) first + readAsciiLine(in);
                    if (line.trim().isEmpty()) {
                        continue;
                    }
                    if (line.toUpperCase(Locale.ROOT).startsWith("AUTH ")) {
                        String[] parts = line.trim().split("\\s+");
                        String username = parts.length == 3 ? parts[1] : null;
                        String password = parts.length == 3 ? parts[2] : (parts.length == 2 ? parts[1] : null);
                        boolean ok = (password != null) && authenticate(username, password);
                        replyQueue.put(ReplyAction.local(ok ? OK_RESPONSE : WRONGPASS_RESPONSE));
                        continue;
                    }
                    out.write((line + "\r\n").getBytes(StandardCharsets.US_ASCII));
                    out.flush();
                    replyQueue.put(ReplyAction.backend());
                }
            }
        } catch (IOException e) {
            // Expected when client disconnects or connection closes
            log.debugv("Client relay connection closed for {0}: {1}", resourceId, e.getMessage());
        } catch (Exception e) {
            log.warnv(e, "Unexpected error in client relay for {0}: {1}", resourceId, e.getMessage());
        } finally {
            try {
                replyQueue.put(ReplyAction.eof());
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            shutdownOutput(backend);
        }
    }

    private void relayRepliesToClient(Socket backend, Socket client, BlockingQueue<ReplyAction> replyQueue) {
        byte[] transferBuf = new byte[8192];
        try {
            InputStream in = new BufferedInputStream(backend.getInputStream(), 8192);
            OutputStream out = client.getOutputStream();
            while (running) {
                ReplyAction action;
                try {
                    action = replyQueue.take();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                }
                if (action.isEof()) {
                    break;
                }
                if (action.isLocal()) {
                    out.write(action.localResponse());
                    out.flush();
                } else {
                    transferRespFrame(in, out, transferBuf);
                    out.flush();
                }
            }
        } catch (EOFException e) {
            // Expected when backend closes connection
            log.debugv("Backend connection closed for {0}: {1}", resourceId, e.getMessage());
        } catch (IOException e) {
            // Expected when either side closes the socket
            log.debugv("Relay reply stream closed for {0}: {1}", resourceId, e.getMessage());
        } catch (Exception e) {
            log.warnv(e, "Unexpected error in backend reply relay for {0}: {1}", resourceId, e.getMessage());
        } finally {
            shutdownOutput(client);
        }
    }

    private static void transferRespFrame(InputStream in, OutputStream out, byte[] transferBuf) throws IOException {
        int type = in.read();
        if (type == -1) {
            throw new EOFException("Unexpected EOF from backend while expecting RESP frame");
        }
        out.write(type);
        switch (type) {
            case '+': // Simple String
            case '-': // Simple Error
            case ':': // Integer
            case '_': // RESP3 Null
            case '#': // RESP3 Boolean
            case ',': // RESP3 Double
            case '(': // RESP3 BigNumber
                transferLine(in, out);
                break;
            case '$': // Bulk String
            case '!': // RESP3 Bulk Error
            case '=': // RESP3 Verbatim String
                transferBulk(in, out, transferBuf);
                break;
            case '*': // Array
            case '~': // RESP3 Set
            case '>': // RESP3 Push
                transferAggregate(in, out, transferBuf, 1);
                break;
            case '%': // RESP3 Map
                transferAggregate(in, out, transferBuf, 2);
                break;
            default:
                // Non-standard response (e.g. raw text from mock backend in tests or inline response)
                transferNonStandard(in, out, transferBuf);
                break;
        }
    }

    private static void transferLine(InputStream in, OutputStream out) throws IOException {
        int c;
        while ((c = in.read()) != -1) {
            out.write(c);
            if (c == '\r') {
                int next = in.read();
                if (next == -1) {
                    throw new EOFException("Unexpected EOF after \r");
                }
                out.write(next);
                if (next == '\n') {
                    return;
                }
            }
        }
        throw new EOFException("Unexpected EOF while reading RESP line");
    }

    private static void transferBulk(InputStream in, OutputStream out, byte[] transferBuf) throws IOException {
        String lenStr = readAsciiLineAndEcho(in, out);
        long len = Long.parseLong(lenStr);
        if (len < 0) {
            return;
        }
        long remaining = len;
        while (remaining > 0) {
            int toRead = (int) Math.min(transferBuf.length, remaining);
            int r = in.read(transferBuf, 0, toRead);
            if (r == -1) {
                throw new EOFException("Unexpected EOF in RESP bulk string payload");
            }
            out.write(transferBuf, 0, r);
            remaining -= r;
        }
        int r1 = in.read();
        if (r1 == -1) {
            throw new EOFException("Unexpected EOF before \r in RESP bulk string");
        }
        out.write(r1);
        int r2 = in.read();
        if (r2 == -1) {
            throw new EOFException("Unexpected EOF before \n in RESP bulk string");
        }
        out.write(r2);
        if (r1 != '\r' || r2 != '\n') {
            throw new IOException("Expected \r\n after bulk string payload");
        }
    }

    private static void transferAggregate(InputStream in, OutputStream out, byte[] transferBuf, int multiplier) throws IOException {
        String countStr = readAsciiLineAndEcho(in, out);
        long count = Long.parseLong(countStr);
        if (count <= 0) {
            return;
        }
        long totalItems = count * multiplier;
        for (long i = 0; i < totalItems; i++) {
            transferRespFrame(in, out, transferBuf);
        }
    }

    private static void transferNonStandard(InputStream in, OutputStream out, byte[] transferBuf) throws IOException {
        int avail = in.available();
        if (avail > 0) {
            int toRead = Math.min(transferBuf.length, avail);
            int r = in.read(transferBuf, 0, toRead);
            if (r != -1) {
                out.write(transferBuf, 0, r);
            }
        }
    }

    private static String readAsciiLineAndEcho(InputStream in, OutputStream out) throws IOException {
        StringBuilder sb = new StringBuilder();
        int c;
        while ((c = in.read()) != -1) {
            out.write(c);
            if (c == '\r') {
                int next = in.read();
                if (next == -1) {
                    throw new EOFException("Unexpected EOF after \r");
                }
                out.write(next);
                if (next == '\n') {
                    return sb.toString();
                }
                sb.append((char) c);
                sb.append((char) next);
                continue;
            }
            sb.append((char) c);
        }
        throw new EOFException("Unexpected EOF while reading RESP line");
    }

    private static final class ReplyAction {
        enum Type { BACKEND, LOCAL, EOF }

        private final Type type;
        private final byte[] localResponse;

        private ReplyAction(Type type, byte[] localResponse) {
            this.type = type;
            this.localResponse = localResponse;
        }

        static ReplyAction backend() {
            return new ReplyAction(Type.BACKEND, null);
        }

        static ReplyAction local(byte[] response) {
            return new ReplyAction(Type.LOCAL, response);
        }

        static ReplyAction eof() {
            return new ReplyAction(Type.EOF, null);
        }

        boolean isLocal() {
            return type == Type.LOCAL;
        }

        boolean isEof() {
            return type == Type.EOF;
        }

        byte[] localResponse() {
            return localResponse;
        }
    }

    private static String readAsciiLine(InputStream in) throws IOException {
        StringBuilder sb = new StringBuilder();
        int c;
        while ((c = in.read()) != -1) {
            if (c == '\r') {
                int next = in.read();
                if (next != '\n') {
                    throw new IOException("Expected \n after \r in RESP");
                }
                break;
            }
            sb.append((char) c);
        }
        if (c == -1 && sb.length() == 0) {
            return null;
        }
        return sb.toString();
    }


    private static void shutdownOutput(Socket socket) {
        try {
            socket.shutdownOutput();
        } catch (IOException ignored) {
            // The bridge closes both sockets after both relay directions finish.
        }
    }

    private static void resendCommand(String[] args, OutputStream out) throws IOException {
        StringBuilder sb = new StringBuilder();
        sb.append("*").append(args.length).append("\r\n");
        for (String arg : args) {
            byte[] bytes = arg.getBytes(StandardCharsets.UTF_8);
            sb.append("$").append(bytes.length).append("\r\n");
            out.write(sb.toString().getBytes(StandardCharsets.UTF_8));
            sb.setLength(0);
            out.write(bytes);
            out.write("\r\n".getBytes(StandardCharsets.UTF_8));
        }
        if (sb.length() > 0) {
            out.write(sb.toString().getBytes(StandardCharsets.UTF_8));
        }
        out.flush();
    }

    private static boolean hasAuthOption(String[] cmd) {
        for (int i = 2; i < cmd.length; i++) {
            if ("AUTH".equalsIgnoreCase(cmd[i])) {
                return true;
            }
        }
        return false;
    }
}
