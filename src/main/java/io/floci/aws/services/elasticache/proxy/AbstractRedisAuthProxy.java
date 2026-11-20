package io.floci.aws.services.elasticache.proxy;

import io.floci.aws.core.common.ListenBacklog;
import org.jboss.logging.Logger;

import java.io.BufferedOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.LongSupplier;

/**
 * Shared TCP auth-proxy skeleton for the Redis (RESP) wire protocol. Intercepts the
 * {@code AUTH} command or the {@code AUTH} option in {@code HELLO}, validates credentials
 * through the subclass, then relays the connection to the backend container.
 * Used by both the ElastiCache and MemoryDB proxies, since MemoryDB's real AWS design is
 * explicitly modeled on ElastiCache: same Redis-compatible wire protocol, same IAM-auth
 * mechanism.
 *
 * <p>Where the proxy checks credentials, it keeps reading the client's commands after sign-in, so a
 * later {@code AUTH} or {@code HELLO} is checked the same way, and a connection signed in with an IAM
 * token is closed 12 hours after its last sign-in, as on AWS. Everything else is relayed byte for
 * byte.
 *
 * <p>Uses Java virtual threads to accept connections and run the AUTH handshake.
 */
public abstract class AbstractRedisAuthProxy {
    private static final int HANDSHAKE_TIMEOUT_MILLIS = 10_000;
    private static final long RELAY_JOIN_TIMEOUT_MILLIS = 1_000;
    /** How long a connection signed in with an IAM token lasts, unless it signs in again. */
    private static final long IAM_CONNECTION_LIFETIME_NANOS = TimeUnit.HOURS.toNanos(12);

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
    private final LongSupplier nanoTime;
    private final Set<Socket> handshakingClients = ConcurrentHashMap.newKeySet();
    private final Set<Socket> unencryptedClients = ConcurrentHashMap.newKeySet();
    private final Set<Connection> connections = ConcurrentHashMap.newKeySet();

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
        this(log, serviceName, threadPrefix, resourceId, backendHost, backendPort, handshakeTimeoutMillis,
                System::nanoTime);
    }

    /** @param nanoTime the time IAM connection lifetimes are measured on, a seam for tests */
    AbstractRedisAuthProxy(Logger log, String serviceName, String threadPrefix, String resourceId,
                           String backendHost, int backendPort, int handshakeTimeoutMillis,
                           LongSupplier nanoTime) {
        this.log = log;
        this.serviceName = serviceName;
        this.threadPrefix = threadPrefix;
        this.resourceId = resourceId;
        this.backendHost = backendHost;
        this.backendPort = backendPort;
        this.handshakeTimeoutMillis = handshakeTimeoutMillis;
        this.nanoTime = nanoTime;
    }

    protected final String backendHost() {
        return backendHost;
    }

    protected final int backendPort() {
        return backendPort;
    }

    /** Whether the {@code AUTH} command is required before bridging to the backend. */
    protected abstract boolean authRequired();

    /** Validates the supplied credentials. Not called when {@link #authRequired()} is false. */
    protected abstract boolean authenticate(String username, String password);

    /** Closes a socket, tolerating errors. Implementations may add their own logging. */
    protected abstract void closeQuietly(Socket socket);

    /** The user a username names: a blank one, as {@code AUTH <password>} sends, names {@code default}. */
    protected static String userOrDefault(String username) {
        return username == null || username.isEmpty() ? "default" : username;
    }

    /** Applies the validated frontend identity to a newly connected backend. */
    protected void initializeBackend(Socket backend, String username) throws IOException {
    }

    /**
     * Whether {@code username} signs in with an IAM token. Such a connection is closed 12 hours after
     * its last sign-in, and cannot sign in again inside a transaction.
     */
    protected boolean signsInWithIam(String username) {
        return false;
    }

    /**
     * Whether the proxy checks the credentials of an {@code AUTH} or {@code HELLO} sent after sign-in,
     * as it does at sign-in. When it does not, they reach the backend as sent.
     */
    protected boolean checksCredentials() {
        return authRequired();
    }

    /**
     * The username and password a relayed connection sends its backend to run as {@code username} from
     * then on, once the proxy has accepted that user's own credentials. The default suits a backend
     * without access control, whose default user takes any password.
     */
    protected List<String> backendCredentials(String username) throws IOException {
        return List.of("default", UUID.randomUUID().toString());
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
                    bridgeCommand(client, input, cmd, null, false);
                    return;
                }
                if (authRequired()) {
                    writeResponse(client, NOAUTH_RESPONSE);
                    closeQuietly(client);
                    return;
                }

                bridgeCommand(client, input, cmd, null, false);
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

        bridgeCommand(client, input, null, username, true);
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

        bridgeCommand(client, input, forwarded.toArray(String[]::new), username, authProvided);
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

    private void bridgeCommand(Socket client, InputStream input, String[] command, String username,
                               boolean signedIn) throws IOException {
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
            bridge(new Connection(client, backend, signedIn && signsInWithIam(username)), input);
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
    private void bridge(Connection connection, InputStream clientInput) throws IOException {
        Socket client = connection.client;
        Socket backend = connection.backend;
        InputStream backendInput = backend.getInputStream();
        boolean checkCredentials = checksCredentials();
        connections.add(connection);
        Thread t1 = Thread.ofPlatform().daemon(true).name(threadPrefix + "-relay-c2b-" + resourceId)
                .start(() -> {
                    try {
                        if (checkCredentials) {
                            relayCommands(clientInput, connection);
                        } else {
                            relay(clientInput, backend);
                        }
                    } finally {
                        connection.relayFinished();
                    }
                });
        Thread t2 = Thread.ofPlatform().daemon(true).name(threadPrefix + "-relay-b2c-" + resourceId)
                .start(() -> {
                    try {
                        relay(backendInput, client);
                    } finally {
                        connection.relayFinished();
                    }
                });
        try {
            if (connection.awaitEnd()) {
                closeExpired(connection);
            }
            t1.join(RELAY_JOIN_TIMEOUT_MILLIS);
            t2.join(RELAY_JOIN_TIMEOUT_MILLIS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } finally {
            connections.remove(connection);
            closeQuietly(client);
            closeQuietly(backend);
        }
    }

    /**
     * Closes every connection whose IAM sign-in is 12 hours old by {@code nanoTime}, as each
     * connection's own wait does once that time has passed. A seam for tests that move the time on.
     */
    void closeExpiredConnections() {
        for (Connection connection : connections) {
            if (connection.expire()) {
                closeExpired(connection);
            }
        }
    }

    private void closeExpired(Connection connection) {
        log.infov("Closing a connection to {0} 12 hours after it signed in with an IAM token", resourceId);
        closeQuietly(connection.client);
        closeQuietly(connection.backend);
    }

    private void relayCommands(InputStream clientInput, Connection connection) {
        try {
            OutputStream backendOutput = new BufferedOutputStream(connection.backend.getOutputStream());
            new ClientCommandRelay(clientInput, backendOutput, new SignedInCommands(connection)).run();
        } catch (IOException ignored) {
            // Normal when either side closes the connection
        } catch (RuntimeException e) {
            log.warnv(e, "Closing a connection to {0} after a failure checking its commands", resourceId);
        } finally {
            shutdownOutput(connection.backend);
        }
    }

    private static void relay(InputStream in, Socket to) {
        byte[] buf = new byte[8192];
        try {
            OutputStream out = to.getOutputStream();
            int n;
            while ((n = in.read(buf)) != -1) {
                out.write(buf, 0, n);
                out.flush();
            }
        } catch (IOException ignored) {
            // Normal when either side closes the connection
        } finally {
            shutdownOutput(to);
        }
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

    /**
     * A relayed connection. It ends when either side closes it, or, while the client is signed in with
     * an IAM token, 12 hours after that sign-in.
     */
    private final class Connection {
        private final Socket client;
        private final Socket backend;
        private final ReentrantLock lock = new ReentrantLock();
        private final Condition changed = lock.newCondition();
        private boolean relayFinished;
        private boolean expired;
        private boolean iam;
        private long signedInAt;

        private Connection(Socket client, Socket backend, boolean iam) {
            this.client = client;
            this.backend = backend;
            this.iam = iam;
            this.signedInAt = nanoTime.getAsLong();
        }

        /**
         * Restarts the lifetime of a connection that signs in again, which lasts only after an IAM sign-in.
         * Once the connection has expired, it is being closed, and signing in no longer renews it.
         */
        void signedIn(boolean iamUser) {
            lock.lock();
            try {
                if (expired) {
                    return;
                }
                iam = iamUser;
                signedInAt = nanoTime.getAsLong();
                changed.signalAll();
            } finally {
                lock.unlock();
            }
        }

        void relayFinished() {
            lock.lock();
            try {
                relayFinished = true;
                changed.signalAll();
            } finally {
                lock.unlock();
            }
        }

        /**
         * Marks the connection expired once its IAM sign-in has run out, which the caller then closes. True
         * only for the call that marks it, and decided under the same lock as a renewing sign-in.
         */
        boolean expire() {
            lock.lock();
            try {
                if (expired || !iam || remainingNanos() > 0) {
                    return false;
                }
                expired = true;
                return true;
            } finally {
                lock.unlock();
            }
        }

        /** Waits until a relay direction finishes, or until this wait expires the connection, which returns true. */
        boolean awaitEnd() throws InterruptedException {
            lock.lock();
            try {
                while (!relayFinished) {
                    if (expire()) {
                        return true;
                    }
                    if (iam && !expired) {
                        changed.awaitNanos(remainingNanos());
                    } else {
                        changed.await();
                    }
                }
                return false;
            } finally {
                lock.unlock();
            }
        }

        private long remainingNanos() {
            return IAM_CONNECTION_LIFETIME_NANOS - (nanoTime.getAsLong() - signedInAt);
        }
    }

    /**
     * Follows a signed-in client's commands: it checks the credentials of each {@code AUTH} and
     * {@code HELLO}, and tracks transactions, since IAM re-authentication is not supported inside one.
     * A sign-in queued in a transaction leaves the connection's lifetime as it was: the proxy cannot see
     * whether {@code EXEC} runs it, and an aborted transaction never does.
     *
     * <p>The backend answers every command, so its replies keep their order: accepted credentials are
     * relayed as the backend credentials of their user, and refused ones as credentials the backend
     * refuses with {@code WRONGPASS}, which leaves the connection signed in as before. The client's own
     * password or token never reaches the backend.
     */
    private final class SignedInCommands implements ClientCommandRelay.Commands {
        /** The commands that begin or end a transaction, none of which takes an argument. */
        private static final List<String> TRANSACTION_COMMANDS = List.of("MULTI", "EXEC", "DISCARD", "RESET");

        private final Connection connection;
        private boolean transaction;

        private SignedInCommands(Connection connection) {
            this.connection = connection;
        }

        @Override
        public boolean wants(String name) {
            return isSignIn(name) || TRANSACTION_COMMANDS.stream().anyMatch(name::equalsIgnoreCase);
        }

        private static boolean isSignIn(String name) {
            return name.equalsIgnoreCase("AUTH") || name.equalsIgnoreCase("HELLO");
        }

        @Override
        public List<String> rewrite(List<String> command) {
            String name = command.get(0);
            if (!isSignIn(name)) {
                // With an argument the backend refuses it for its arity, leaving the transaction as it was.
                if (command.size() == 1) {
                    transaction = name.equalsIgnoreCase("MULTI");
                }
                return command;
            }
            SignIn signIn = name.equalsIgnoreCase("AUTH") ? SignIn.auth(command) : SignIn.hello(command);
            if (signIn == null) {
                return command;
            }
            if (!signIn.reachesSignIn()) {
                return signIn.relayed(refusedCredentials());
            }
            String username = signIn.username();
            boolean iam = signsInWithIam(username);
            if (transaction && iam) {
                // Sends MULTI in its place: inside a transaction the backend refuses it as a command that
                // cannot be queued, which also aborts the transaction, and the refusal keeps its place.
                return List.of("MULTI");
            }
            List<String> credentials = authenticate(username, signIn.password()) ? switchTo(username) : null;
            if (credentials == null) {
                return signIn.relayed(refusedCredentials());
            }
            if (!transaction) {
                connection.signedIn(iam);
            }
            return signIn.relayed(credentials);
        }

        @Override
        public List<String> inPlaceOfTooLarge(String name) {
            if (isSignIn(name)) {
                List<String> refused = refusedCredentials();
                return List.of("AUTH", refused.get(0), refused.get(1));
            }
            // A transaction command too large to hold has arguments, so the backend refuses this one too.
            return List.of(name, "");
        }

        private List<String> switchTo(String username) {
            try {
                return backendCredentials(username);
            } catch (IOException e) {
                log.warnv("Could not switch a connection to {0} over to another user: {1}", resourceId,
                        e.getMessage());
                return null;
            }
        }

        private static List<String> refusedCredentials() {
            return List.of("floci-refused-" + UUID.randomUUID(), UUID.randomUUID().toString());
        }
    }

    /**
     * The credentials in an {@code AUTH} or {@code HELLO} sent after sign-in.
     *
     * @param arguments    what the backend reads of the command: all of it, or for a {@code HELLO} the
     *                     backend refuses, the arguments up to the one it refuses
     * @param credentials  the index among them of each username and password pair
     * @param reachesSignIn whether the backend gets as far as checking the credentials
     */
    private record SignIn(List<String> arguments, List<Integer> credentials, boolean reachesSignIn) {

        /** Null for an {@code AUTH} without arguments, which carries no credentials. */
        static SignIn auth(List<String> command) {
            return switch (command.size()) {
                case 1 -> null;
                // AUTH <password> signs in as the default user, which the proxy names null, as at sign-in.
                case 2 -> new SignIn(Arrays.asList("AUTH", null, command.get(1)), List.of(1), true);
                case 3 -> new SignIn(command, List.of(1), true);
                // The backend refuses more arguments as a syntax error, before reading any of them.
                default -> {
                    List<String> blanks = new ArrayList<>(Collections.nCopies(command.size(), ""));
                    blanks.set(0, command.get(0));
                    yield new SignIn(blanks, List.of(), false);
                }
            };
        }

        /**
         * Reads the options as the backend does, which stops at the first it refuses. Null for a
         * well-formed {@code HELLO} without an {@code AUTH} option.
         */
        static SignIn hello(List<String> command) {
            List<Integer> credentials = new ArrayList<>();
            if (command.size() > 1 && !supportedProtocol(command.get(1))) {
                return new SignIn(command.subList(0, 2), credentials, false);
            }
            int index = 2;
            while (index < command.size()) {
                String option = command.get(index);
                int remaining = command.size() - 1 - index;
                if (option.equalsIgnoreCase("AUTH") && remaining >= 2) {
                    credentials.add(index + 1);
                    index += 3;
                } else if (option.equalsIgnoreCase("SETNAME") && remaining >= 1
                        && isValidClientName(command.get(index + 1))) {
                    index += 2;
                } else {
                    int refused = option.equalsIgnoreCase("SETNAME") && remaining >= 1 ? index + 1 : index;
                    return new SignIn(command.subList(0, refused + 1), credentials, false);
                }
            }
            return credentials.isEmpty() ? null : new SignIn(command, credentials, true);
        }

        private static boolean supportedProtocol(String version) {
            try {
                long protocol = Long.parseLong(version);
                return protocol >= 2 && protocol <= 3;
            } catch (NumberFormatException e) {
                return false;
            }
        }

        /** The last pair is the one the backend signs in with. */
        String username() {
            return arguments.get(credentials.getLast());
        }

        String password() {
            return arguments.get(credentials.getLast() + 1);
        }

        List<String> relayed(List<String> replacement) {
            List<String> relayed = new ArrayList<>(arguments);
            for (int index : credentials) {
                relayed.set(index, replacement.get(0));
                relayed.set(index + 1, replacement.get(1));
            }
            return relayed;
        }
    }
}
