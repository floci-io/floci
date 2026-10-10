package io.github.hectorvent.floci.services.rds.proxy;

import org.jboss.logging.Logger;

import javax.crypto.Mac;
import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.PBEKeySpec;
import javax.crypto.spec.SecretKeySpec;
import javax.net.ssl.SSLSocket;
import java.io.BufferedOutputStream;
import java.io.ByteArrayOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Handles the PostgreSQL wire protocol auth intercept.
 *
 * <p>Flow:
 * <ol>
 *   <li>Read client StartupMessage (handles PostgreSQL SSL negotiation)
 *   <li>Challenge client with AuthenticationCleartextPassword
 *   <li>Read client password
 *   <li>Validate (IAM SigV4 or plain password)
 *   <li>Connect to backend with MD5 or SCRAM-SHA-256 auth
 *   <li>Buffer backend messages until ReadyForQuery
 *   <li>For IAM logins, hand the session over to the role named in the token, then apply the
 *       client's startup parameters as that role
 *   <li>Send AuthOK + buffered messages to client, then bridge, guarding an IAM
 *       session against being handed back to the master role
 * </ol>
 */
public class PostgresProtocolHandler {

    private static final Logger LOG = Logger.getLogger(PostgresProtocolHandler.class);

    private static final int SSL_REQUEST_CODE = 80877103;
    private static final int STARTUP_PROTOCOL_VERSION = 196608; // v3.0
    private static final int MAX_HANDSHAKE_PACKET_LENGTH = 1_048_576;

    /**
     * An authenticated client connection ready to be bridged. {@code iamRole} is the role an IAM
     * token named and the session was handed over to, or {@code null} for every other login.
     */
    public record AuthenticatedSession(Socket client, Socket backend, String iamRole) {}

    /** The username/password the proxy opens the backend PostgreSQL connection with. */
    record BackendLogin(String user, String password) {}

    /**
     * Opens the backend connection. Called only once the client's startup message and credentials
     * have been validated, so an unauthenticated or malformed client never causes a backend
     * connection attempt.
     */
    @FunctionalInterface
    public interface BackendConnector {
        Socket connect() throws IOException;
    }

    /**
     * Picks the backend login: the cluster master whenever the proxy is the authority for this
     * connection (an IAM token, the master user itself, or a client the validator vouched for),
     * otherwise the client's own credentials so the backend enforces its ACLs.
     */
    static BackendLogin resolveBackendLogin(boolean isMaster, boolean isIam,
                                            PasswordValidator.AuthResult result,
                                            String masterUsername, String masterPassword,
                                            String clientUsername, String clientPassword) {
        boolean useMaster = isIam || isMaster
                || result == PasswordValidator.AuthResult.MASTER_EQUIVALENT;
        if (useMaster) {
            return new BackendLogin(masterUsername, masterPassword);
        }
        return new BackendLogin(clientUsername, clientPassword);
    }

    /**
     * Authenticates the client, opening the backend session with only {@code user} and
     * {@code database}: the client's other StartupMessage parameters are not forwarded.
     */
    public static AuthenticatedSession authenticate(Socket client, BackendConnector backendConnector,
                                      String masterUsername, String masterPassword, String dbName,
                                      boolean iamEnabled, RdsSigV4Validator sigV4,
                                      RdsProxyBinding binding,
                                      RdsProxyTlsCertificates tlsCertificates,
                                      PasswordValidator passwordValidator,
                                      int handshakeTimeoutMillis) throws IOException {
        return authenticate(client, backendConnector, masterUsername, masterPassword, dbName,
                iamEnabled, sigV4, binding, tlsCertificates, passwordValidator,
                handshakeTimeoutMillis, false);
    }

    /**
     * Authenticates the client. With {@code forwardStartupParameters}, the client's other
     * StartupMessage parameters ({@code options}, {@code application_name} and any run-time
     * parameter) apply as they would on the server itself: they open the backend session, or,
     * for an IAM login handed over to the token's role, are set once the role owns the session.
     */
    public static AuthenticatedSession authenticate(Socket client, BackendConnector backendConnector,
                                      String masterUsername, String masterPassword, String dbName,
                                      boolean iamEnabled, RdsSigV4Validator sigV4,
                                      RdsProxyBinding binding,
                                      RdsProxyTlsCertificates tlsCertificates,
                                      PasswordValidator passwordValidator,
                                      int handshakeTimeoutMillis,
                                      boolean forwardStartupParameters) throws IOException {

        client.setSoTimeout(handshakeTimeoutMillis);

        // Phase 1: Read client startup message (possibly preceded by SSL request)
        StartupMessage startup = readStartupMessage(client, tlsCertificates, handshakeTimeoutMillis);
        if (startup == null) {
            closeQuietly(client);
            return null;
        }
        client = startup.socket();
        String clientUsername = startup.username();
        InputStream clientIn = client.getInputStream();
        OutputStream clientOut = client.getOutputStream();

        // Phase 2: Challenge client with cleartext password request
        sendMessage(clientOut, 'R', intBytes(3)); // AuthenticationCleartextPassword
        clientOut.flush();

        // Phase 3: Read client password
        String clientPassword = readPasswordMessage(clientIn);
        if (clientPassword == null) {
            closeQuietly(client);
            return null;
        }

        // Phase 4: Validate credentials.
        // - IAM tokens: validated locally via SigV4.
        // - Every non-IAM client: classified by passwordValidator into REJECT / PASSTHROUGH /
        //   MASTER_EQUIVALENT. PASSTHROUGH keeps the legacy non-master behaviour (the backend is
        //   the authority for the password); MASTER_EQUIVALENT means the proxy vouched for the
        //   client, so the backend leg runs as the cluster master. The RDS validator reads from
        //   RdsService, so a master login still reflects modifyDBInstance password changes.
        boolean isIam = iamEnabled && clientPassword.contains("X-Amz-Signature");
        boolean isMaster = masterUsername.equals(clientUsername);

        PasswordValidator.AuthResult authResult = PasswordValidator.AuthResult.PASSTHROUGH;
        if (isIam) {
            if (!sigV4.validate(clientPassword, clientUsername, binding)) {
                sendErrorResponse(clientOut, "FATAL", "28P01",
                        "password authentication failed for user \"" + clientUsername + "\"");
                clientOut.flush();
                closeQuietly(client);
                return null;
            }
        } else {
            authResult = passwordValidator.validate(clientUsername, clientPassword);
            if (authResult == PasswordValidator.AuthResult.REJECT) {
                sendErrorResponse(clientOut, "FATAL", "28P01",
                        "password authentication failed for user \"" + clientUsername + "\"");
                clientOut.flush();
                closeQuietly(client);
                return null;
            }
        }

        // Phase 5: Connect to backend PostgreSQL, now that the client's startup message and
        // credentials have been validated. IAM and master: use master credentials. The backend
        // has the original container password and is never updated directly, so the proxy always
        // authenticates as master. Non-master: forward the client's own credentials so the backend
        // enforces its own ACLs, unless the proxy vouched for the client (MASTER_EQUIVALENT).
        Socket backend;
        try {
            backend = backendConnector.connect();
        } catch (IOException e) {
            sendErrorResponse(clientOut, "FATAL", "08006", "could not connect to backend database");
            clientOut.flush();
            closeQuietly(client);
            return null;
        }

        try {
            // The backend accepted the TCP connection but may never answer (or answer only
            // partially); bound every blocking backend read during the handshake so a silent
            // backend cannot pin this handler thread and its connection permit forever.
            backend.setSoTimeout(handshakeTimeoutMillis);

            InputStream backendIn = backend.getInputStream();
            OutputStream backendOut = backend.getOutputStream();

            String effectiveDbName = resolveEffectiveDbName(startup.database(), dbName);
            BackendLogin backendLogin = resolveBackendLogin(isMaster, isIam, authResult,
                    masterUsername, masterPassword, clientUsername, clientPassword);
            String backendUser = backendLogin.user();
            String backendPass = backendLogin.password();
            // An IAM login for any role but the master runs on a session opened as the master and
            // handed over below, so PostgreSQL would apply the client's parameters with the
            // master's privileges: a "-c role=<master>" would outlive the handover and RESET ROLE
            // would regain the master. Those logins open with only user and database, and their
            // parameters are applied once the session belongs to the role (Phase 5c).
            Map<String, String> clientParameters = forwardStartupParameters && !(isIam && !isMaster)
                    ? startup.parameters() : Map.of();
            sendStartupToBackend(backendOut, backendUser, effectiveDbName, clientParameters);
            backendOut.flush();

            boolean backendAuthenticated = false;
            byte[] backendRejection = null;
            try {
                backendAuthenticated = authenticateWithBackend(backendIn, backendOut, backendUser, backendPass);
            } catch (BackendRejectedException e) {
                backendRejection = e.errorResponse();
            }
            if (!backendAuthenticated) {
                // PostgreSQL's own refusal, such as a missing pg_hba.conf entry for a forwarded
                // replication=true, says far more than the proxy's generic error.
                if (backendRejection != null) {
                    clientOut.write(backendRejection);
                } else {
                    sendErrorResponse(clientOut, "FATAL", "08006",
                            "Backend database authentication failed");
                }
                clientOut.flush();
                closeQuietly(client);
                closeQuietly(backend);
                return null;
            }

            // Buffer all backend messages until ReadyForQuery ('Z')
            List<byte[]> bufferedMessages = readUntilReadyForQuery(backendIn);

            String iamRole = null;

            // Phase 5b: An IAM token is issued for one specific database role, so the session must
            // run as that role even though the backend connection was opened as master. Handing it
            // over gives the session the role's own privileges and object ownership, and refuses a
            // token naming a role the database does not have instead of silently granting a master
            // session.
            if (isIam && !isMaster && !endsWithErrorResponse(bufferedMessages)) {
                List<byte[]> roleSwitch = assumeSessionRole(backendIn, backendOut, clientUsername);
                if (endsWithErrorResponse(roleSwitch)) {
                    sendErrorResponse(clientOut, "FATAL", "28000",
                            errorMessage(roleSwitch.get(roleSwitch.size() - 1),
                                    "role \"" + clientUsername + "\" does not exist"));
                    clientOut.flush();
                    closeQuietly(client);
                    closeQuietly(backend);
                    return null;
                }
                bufferedMessages = applyParameterStatusUpdates(bufferedMessages, roleSwitch);
                iamRole = clientUsername;

                // Phase 5c: Apply the client's startup parameters now that the session belongs to
                // the role, so PostgreSQL checks each one against the role's privileges, as it
                // does when the role logs in directly. A parameter PostgreSQL refuses fails the
                // login with PostgreSQL's own reason, as it would at startup. One check cannot be
                // left to PostgreSQL: it authorizes session_authorization against the user the
                // backend connection authenticated as, the superuser master, so a change of it is
                // refused here before anything is applied, and the outcome is checked again below.
                if (forwardStartupParameters) {
                    List<SessionSetting> settings;
                    try {
                        settings = sessionSettings(startup.parameters());
                        refuseSessionAuthorizationChange(settings, clientUsername);
                    } catch (StartupParameterException e) {
                        sendErrorResponse(clientOut, "FATAL", e.sqlState(), e.getMessage());
                        clientOut.flush();
                        closeQuietly(client);
                        closeQuietly(backend);
                        return null;
                    }
                    if (!settings.isEmpty()) {
                        List<byte[]> applied = applySessionSettings(backendIn, backendOut, settings);
                        if (endsWithErrorResponse(applied)) {
                            byte[] refusal = applied.get(applied.size() - 1);
                            sendErrorResponse(clientOut, "FATAL", errorField(refusal, 'C', "42601"),
                                    errorField(refusal, 'M', "invalid startup parameter"));
                            clientOut.flush();
                            closeQuietly(client);
                            closeQuietly(backend);
                            return null;
                        }
                        if (reportsAnotherSessionAuthorization(applied, iamRole)) {
                            LOG.warnv("RDS IAM session for role {0} changed its session authorization "
                                    + "through startup parameters; refusing the login", iamRole);
                            sendErrorResponse(clientOut, "FATAL", "42501",
                                    "permission denied to set session authorization");
                            clientOut.flush();
                            closeQuietly(client);
                            closeQuietly(backend);
                            return null;
                        }
                        bufferedMessages = applyParameterStatusUpdates(bufferedMessages, applied);
                    }
                }
            }

            // Phase 6: Send AuthenticationOK to client, forward buffered messages, then bridge
            if (endsWithErrorResponse(bufferedMessages)) {
                for (byte[] msg : bufferedMessages) {
                    clientOut.write(msg);
                }
                clientOut.flush();
                closeQuietly(client);
                closeQuietly(backend);
                return null;
            }

            sendMessage(clientOut, 'R', intBytes(0)); // AuthenticationOK
            for (byte[] msg : bufferedMessages) {
                clientOut.write(msg);
            }
            clientOut.flush();

            client.setSoTimeout(0);
            backend.setSoTimeout(0);
            return new AuthenticatedSession(client, backend, iamRole);
        } catch (IOException | RuntimeException e) {
            closeQuietly(backend);
            throw e;
        }
    }

    // ── Startup ───────────────────────────────────────────────────────────────

    private static StartupMessage readStartupMessage(Socket socket, RdsProxyTlsCertificates tlsCertificates,
            int handshakeTimeoutMillis) throws IOException {
        Socket currentSocket = socket;
        while (true) {
            InputStream in = currentSocket.getInputStream();
            OutputStream out = currentSocket.getOutputStream();
            int length = checkedPacketLength(readInt32(in), 8, "startup message");
            int proto = readInt32(in);

            if (proto == SSL_REQUEST_CODE) {
                out.write('S');
                out.flush();
                currentSocket = acceptSsl(currentSocket, tlsCertificates);
                // acceptSsl wraps the socket in a new SSLSocket; re-apply the handshake timeout
                // since it is not guaranteed to be inherited from the underlying socket.
                currentSocket.setSoTimeout(handshakeTimeoutMillis);
                continue;
            }

            if (proto != STARTUP_PROTOCOL_VERSION) {
                LOG.warnv("Unexpected PostgreSQL startup protocol version: {0}", proto);
                return null;
            }

            byte[] payload = new byte[length - 8];
            readFully(in, payload);
            Map<String, String> params = parseStartupParams(payload);
            return new StartupMessage(currentSocket,
                    params.getOrDefault("user", "postgres"),
                    params.get("database"),
                    params);
        }
    }

    static Socket acceptSsl(Socket socket, RdsProxyTlsCertificates tlsCertificates) throws IOException {
        try {
            SSLSocket sslSocket = (SSLSocket) tlsCertificates.sslContext().getSocketFactory()
                    .createSocket(socket, socket.getInetAddress().getHostAddress(), socket.getPort(), true);
            sslSocket.setUseClientMode(false);
            sslSocket.startHandshake();
            return sslSocket;
        } catch (Exception e) {
            throw new IOException("Unable to negotiate PostgreSQL SSL", e);
        }
    }

    private record StartupMessage(Socket socket, String username, String database,
                                  Map<String, String> parameters) {}

    static String resolveEffectiveDbName(String clientDatabase, String instanceDbName) {
        if (clientDatabase != null && !clientDatabase.isBlank()) {
            return clientDatabase;
        }
        if (instanceDbName != null && !instanceDbName.isBlank()) {
            return instanceDbName;
        }
        return "postgres";
    }

    private static boolean endsWithErrorResponse(List<byte[]> messages) {
        return !messages.isEmpty() && messages.get(messages.size() - 1)[0] == 'E';
    }

    static Map<String, String> parseStartupParams(byte[] data) {
        Map<String, String> params = new LinkedHashMap<>();
        int i = 0;
        while (i < data.length) {
            int keyStart = i;
            while (i < data.length && data[i] != 0) {
                i++;
            }
            if (i >= data.length) {
                break;
            }
            String key = new String(data, keyStart, i - keyStart, StandardCharsets.UTF_8);
            i++; // skip null
            if (key.isEmpty()) {
                break; // final null terminator
            }
            int valStart = i;
            while (i < data.length && data[i] != 0) {
                i++;
            }
            String value = new String(data, valStart, i - valStart, StandardCharsets.UTF_8);
            i++; // skip null
            params.put(key, value);
        }
        return params;
    }

    /**
     * Writes the backend StartupMessage: the proxy's own {@code user} and {@code database}, then
     * the client's other parameters in the order the client sent them. {@code _pq_.} names request
     * protocol extensions, which the proxy does not negotiate with the backend.
     */
    private static void sendStartupToBackend(OutputStream out, String username, String dbName,
                                             Map<String, String> clientParameters) throws IOException {
        ByteArrayOutputStream parameters = new ByteArrayOutputStream();
        writeStartupParameter(parameters, "user", username);
        writeStartupParameter(parameters, "database", dbName);
        for (Map.Entry<String, String> parameter : clientParameters.entrySet()) {
            String name = parameter.getKey();
            if (!"user".equals(name) && !"database".equals(name) && !name.startsWith("_pq_.")) {
                writeStartupParameter(parameters, name, parameter.getValue());
            }
        }
        parameters.write(0); // final null

        writeInt32(out, 4 + 4 + parameters.size());
        writeInt32(out, STARTUP_PROTOCOL_VERSION);
        parameters.writeTo(out);
    }

    private static void writeStartupParameter(ByteArrayOutputStream out, String name, String value)
            throws IOException {
        out.write(name.getBytes(StandardCharsets.UTF_8));
        out.write(0);
        out.write(value.getBytes(StandardCharsets.UTF_8));
        out.write(0);
    }

    // ── Client auth phase ─────────────────────────────────────────────────────

    private static String readPasswordMessage(InputStream in) throws IOException {
        int type = in.read();
        if (type < 0) {
            return null;
        }
        if (type != 'p') {
            LOG.warnv("Expected PasswordMessage ('p'), got {0}", (char) type);
            return null;
        }
        int length = checkedPacketLength(readInt32(in), 5, "password message");
        byte[] data = new byte[length - 4];
        readFully(in, data);
        // Strip trailing null terminator
        int end = data.length;
        while (end > 0 && data[end - 1] == 0) {
            end--;
        }
        return new String(data, 0, end, StandardCharsets.UTF_8);
    }

    // ── Backend auth phase ────────────────────────────────────────────────────

    private static boolean authenticateWithBackend(InputStream in, OutputStream out,
                                                   String username, String password) throws IOException {
        int type = readAuthenticationType(in);
        if (type != 'R') {
            LOG.warnv("Expected Authentication ('R') from backend, got type={0}", type);
            return false;
        }

        int length = checkedPacketLength(readInt32(in), 8, "backend authentication message");
        int authType = readInt32(in);

        if (authType == 0) {
            // Trust auth — no password needed
            return true;
        }

        if (authType == 3) {
            // CleartextPassword
            sendPasswordMessage(out, password);
            out.flush();
            return readAuthOk(in);
        }

        if (authType == 5) {
            // MD5Password — read 4-byte salt
            byte[] salt = new byte[4];
            readFully(in, salt);
            String md5pw = computeMd5Password(password, username, salt);
            sendPasswordMessage(out, md5pw);
            out.flush();
            return readAuthOk(in);
        }

        if (authType == 10) {
            // SCRAM-SHA-256 — drain the mechanisms list and perform SCRAM handshake
            byte[] mechanismsBytes = new byte[length - 8];
            readFully(in, mechanismsBytes);
            return performScramSha256(in, out, username, password);
        }

        LOG.warnv("Unsupported backend PostgreSQL auth type: {0}", authType);
        if (length > 8) {
            byte[] extra = new byte[length - 8];
            readFully(in, extra);
        }
        return false;
    }

    /**
     * Reads the type byte of a message the handshake expects to be an Authentication one. When
     * PostgreSQL refuses the login instead, the whole ErrorResponse is read and thrown so the
     * client sees PostgreSQL's reason.
     */
    private static int readAuthenticationType(InputStream in) throws IOException {
        int type = in.read();
        if (type == 'E') {
            throw new BackendRejectedException(readMessage(in, type));
        }
        return type;
    }

    /** PostgreSQL refused the backend login with {@code errorResponse}, a complete message. */
    private static final class BackendRejectedException extends IOException {
        private final byte[] errorResponse;

        BackendRejectedException(byte[] errorResponse) {
            super("Backend rejected the login: " + errorMessage(errorResponse, "no message"));
            this.errorResponse = errorResponse;
        }

        byte[] errorResponse() {
            return errorResponse;
        }
    }

    // ── SCRAM-SHA-256 ─────────────────────────────────────────────────────────

    private static boolean performScramSha256(InputStream in, OutputStream out,
                                              String username, String password) throws IOException {
        // Step 1: Send SASLInitialResponse with client-first-message
        String clientNonce = generateNonce();
        String clientFirstMessageBare = "n=" + username + ",r=" + clientNonce;
        String clientFirstMessage = "n,," + clientFirstMessageBare;
        byte[] firstMsgBytes = clientFirstMessage.getBytes(StandardCharsets.UTF_8);

        // Body: mechanism-name '\0' Int32(msg-length) msg-bytes
        ByteArrayOutputStream saslInit = new ByteArrayOutputStream();
        saslInit.write("SCRAM-SHA-256".getBytes(StandardCharsets.UTF_8));
        saslInit.write(0);
        saslInit.write((firstMsgBytes.length >> 24) & 0xFF);
        saslInit.write((firstMsgBytes.length >> 16) & 0xFF);
        saslInit.write((firstMsgBytes.length >> 8) & 0xFF);
        saslInit.write(firstMsgBytes.length & 0xFF);
        saslInit.write(firstMsgBytes);
        sendMessage(out, 'p', saslInit.toByteArray());
        out.flush();

        // Step 2: Read AuthenticationSASLContinue (authType=11)
        if (readAuthenticationType(in) != 'R') {
            return false;
        }
        int len2 = checkedPacketLength(readInt32(in), 8, "SASL continue message");
        if (readInt32(in) != 11) {
            return false;
        }
        byte[] serverFirstBytes = new byte[len2 - 8];
        readFully(in, serverFirstBytes);
        String serverFirstMessage = new String(serverFirstBytes, StandardCharsets.UTF_8);

        // Parse server-first-message: r=<nonce>,s=<base64-salt>,i=<iterations>
        Map<String, String> sp = parseScramParams(serverFirstMessage);
        String serverNonce = sp.get("r");
        byte[] salt = Base64.getDecoder().decode(sp.get("s"));
        int iterations = Integer.parseInt(sp.get("i"));

        if (serverNonce == null || !serverNonce.startsWith(clientNonce)) {
            LOG.warn("SCRAM: server nonce does not start with client nonce");
            return false;
        }

        // Step 3: Compute client-final-message with proof
        // c = base64("n,,") = "biws" (GS2 header, no channel binding)
        String clientFinalWithoutProof = "c=biws,r=" + serverNonce;
        String authMessage = clientFirstMessageBare + "," + serverFirstMessage + "," + clientFinalWithoutProof;

        byte[] saltedPassword = pbkdf2HmacSha256(password, salt, iterations);
        byte[] clientKey = hmacSha256(saltedPassword, "Client Key");
        byte[] storedKey = sha256(clientKey);
        byte[] clientSignature = hmacSha256(storedKey, authMessage.getBytes(StandardCharsets.UTF_8));
        byte[] clientProof = xor(clientKey, clientSignature);

        String clientFinalMessage = clientFinalWithoutProof
                + ",p=" + Base64.getEncoder().encodeToString(clientProof);

        // Send SASLResponse: just the final message bytes
        sendMessage(out, 'p', clientFinalMessage.getBytes(StandardCharsets.UTF_8));
        out.flush();

        // Step 4: Read AuthenticationSASLFinal (authType=12) — server signature (ignored)
        if (readAuthenticationType(in) != 'R') {
            return false;
        }
        int len3 = checkedPacketLength(readInt32(in), 8, "SASL final message");
        if (readInt32(in) != 12) {
            return false;
        }
        byte[] serverFinalBytes = new byte[len3 - 8];
        readFully(in, serverFinalBytes);

        // Step 5: Read final AuthenticationOK
        return readAuthOk(in);
    }

    private static String generateNonce() {
        byte[] bytes = new byte[18];
        new SecureRandom().nextBytes(bytes);
        return Base64.getEncoder().withoutPadding().encodeToString(bytes);
    }

    private static Map<String, String> parseScramParams(String msg) {
        Map<String, String> params = new HashMap<>();
        for (String part : msg.split(",")) {
            int eq = part.indexOf('=');
            if (eq > 0) {
                params.put(part.substring(0, eq), part.substring(eq + 1));
            }
        }
        return params;
    }

    private static byte[] pbkdf2HmacSha256(String password, byte[] salt, int iterations) {
        try {
            PBEKeySpec spec = new PBEKeySpec(password.toCharArray(), salt, iterations, 256);
            SecretKeyFactory skf = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256");
            return skf.generateSecret(spec).getEncoded();
        } catch (Exception e) {
            throw new RuntimeException("PBKDF2-HMAC-SHA256 failed", e);
        }
    }

    private static byte[] hmacSha256(byte[] key, String data) {
        return hmacSha256(key, data.getBytes(StandardCharsets.UTF_8));
    }

    private static byte[] hmacSha256(byte[] key, byte[] data) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(key, "HmacSHA256"));
            return mac.doFinal(data);
        } catch (Exception e) {
            throw new RuntimeException("HMAC-SHA256 failed", e);
        }
    }

    private static byte[] sha256(byte[] data) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(data);
        } catch (Exception e) {
            throw new RuntimeException("SHA-256 failed", e);
        }
    }

    private static byte[] xor(byte[] a, byte[] b) {
        byte[] result = new byte[a.length];
        for (int i = 0; i < a.length; i++) {
            result[i] = (byte) (a[i] ^ b[i]);
        }
        return result;
    }

    // ── MD5 password ──────────────────────────────────────────────────────────

    private static boolean readAuthOk(InputStream in) throws IOException {
        int type = readAuthenticationType(in);
        if (type != 'R') {
            LOG.warnv("Expected AuthenticationOK from backend, got type={0}", type);
            return false;
        }
        int length = checkedPacketLength(readInt32(in), 8, "authentication response");
        int authType = readInt32(in);
        if (length > 8) {
            byte[] extra = new byte[length - 8];
            readFully(in, extra);
        }
        return authType == 0;
    }

    private static void sendPasswordMessage(OutputStream out, String password) throws IOException {
        byte[] pwBytes = password.getBytes(StandardCharsets.UTF_8);
        // 'p' + Int32(4 + pwLen + 1) + password + null
        sendMessage(out, 'p', pwBytes, new byte[]{0});
    }

    private static String computeMd5Password(String password, String username, byte[] salt) {
        try {
            MessageDigest md5 = MessageDigest.getInstance("MD5");
            md5.update(password.getBytes(StandardCharsets.UTF_8));
            md5.update(username.getBytes(StandardCharsets.UTF_8));
            String hex1 = bytesToHex(md5.digest());

            md5.reset();
            md5.update(hex1.getBytes(StandardCharsets.UTF_8));
            md5.update(salt);
            return "md5" + bytesToHex(md5.digest());
        } catch (Exception e) {
            throw new RuntimeException("MD5 computation failed", e);
        }
    }

    // ── Post-auth buffering ───────────────────────────────────────────────────

    private static List<byte[]> readUntilReadyForQuery(InputStream in) throws IOException {
        List<byte[]> messages = new ArrayList<>();
        while (true) {
            int type = in.read();
            if (type < 0) {
                throw new EOFException("Connection closed before ReadyForQuery");
            }
            messages.add(readMessage(in, type));

            if (type == 'Z') { // ReadyForQuery
                break;
            }
            // Error from backend during startup
            if (type == 'E') {
                break;
            }
        }
        return messages;
    }

    /** Reads the rest of a backend message whose type byte was {@code type}, returning it whole. */
    private static byte[] readMessage(InputStream in, int type) throws IOException {
        int length = checkedPacketLength(readInt32(in), 4, "backend message");
        byte[] payload = new byte[length - 4];
        readFully(in, payload);

        // Reconstruct full message: type + length(4) + payload
        byte[] full = new byte[1 + 4 + payload.length];
        full[0] = (byte) type;
        full[1] = (byte) ((length >> 24) & 0xFF);
        full[2] = (byte) ((length >> 16) & 0xFF);
        full[3] = (byte) ((length >> 8) & 0xFF);
        full[4] = (byte) (length & 0xFF);
        System.arraycopy(payload, 0, full, 5, payload.length);
        return full;
    }

    // ── IAM session role ──────────────────────────────────────────────────────

    /**
     * Hands the backend session over to {@code role} so {@code current_user} and
     * {@code session_user} both report the role named in the IAM token and the session carries
     * only that role's privileges.
     *
     * @return the backend messages the statement produced, up to ReadyForQuery or ErrorResponse
     */
    private static List<byte[]> assumeSessionRole(InputStream in, OutputStream out, String role)
            throws IOException {
        String sql = "SET SESSION AUTHORIZATION " + quoteIdentifier(role);
        sendMessage(out, 'Q', sql.getBytes(StandardCharsets.UTF_8), new byte[]{0});
        out.flush();
        return readUntilReadyForQuery(in);
    }

    static String quoteIdentifier(String identifier) {
        return "\"" + identifier.replace("\"", "\"\"") + "\"";
    }

    /** One run-time parameter a client asked for at startup, by name. */
    record SessionSetting(String name, String value) {}

    /**
     * A startup parameter PostgreSQL would refuse while parsing the StartupMessage, before any
     * setting is applied.
     */
    static final class StartupParameterException extends IOException {
        private final String sqlState;

        StartupParameterException(String sqlState, String message) {
            super(message);
            this.sqlState = sqlState;
        }

        String sqlState() {
            return sqlState;
        }
    }

    /**
     * The run-time parameters a StartupMessage asks for, in the order PostgreSQL applies them:
     * the {@code -c} switches in {@code options} first, then every other parameter as sent.
     * {@code user}, {@code database} and {@code _pq_.} protocol extensions are not parameters.
     */
    static List<SessionSetting> sessionSettings(Map<String, String> startupParameters)
            throws StartupParameterException {
        List<SessionSetting> settings = new ArrayList<>();
        String options = startupParameters.get("options");
        if (options != null) {
            settings.addAll(parseOptions(options));
        }
        for (Map.Entry<String, String> parameter : startupParameters.entrySet()) {
            String name = parameter.getKey();
            if ("user".equals(name) || "database".equals(name) || "options".equals(name)
                    || name.startsWith("_pq_.")) {
                continue;
            }
            if ("replication".equals(name)) {
                String value = parameter.getValue();
                Boolean replication = "database".equals(value) ? Boolean.TRUE : parseBool(value);
                if (replication == null) {
                    throw new StartupParameterException("22023",
                            "invalid value for parameter \"replication\": \"" + value + "\"");
                }
                if (replication) {
                    throw new StartupParameterException("0A000",
                            "replication connections are not supported for IAM sessions through the proxy");
                }
                continue;
            }
            settings.add(new SessionSetting(name, parameter.getValue()));
        }
        return settings;
    }

    /**
     * A boolean as PostgreSQL's {@code parse_bool} reads it: any case-insensitive prefix of
     * {@code true}, {@code false}, {@code yes} or {@code no}, at least two letters of {@code on}
     * or {@code off}, or exactly {@code 1} or {@code 0}. {@code null} for anything else,
     * including the ambiguous {@code o}.
     */
    static Boolean parseBool(String value) {
        if (value.isEmpty()) {
            return null;
        }
        String lower = value.toLowerCase(Locale.ROOT);
        if ("true".startsWith(lower) || "yes".startsWith(lower)) {
            return Boolean.TRUE;
        }
        if ("false".startsWith(lower) || "no".startsWith(lower)) {
            return Boolean.FALSE;
        }
        if (lower.length() >= 2 && "on".startsWith(lower)) {
            return Boolean.TRUE;
        }
        if (lower.length() >= 2 && "off".startsWith(lower)) {
            return Boolean.FALSE;
        }
        if ("1".equals(lower)) {
            return Boolean.TRUE;
        }
        if ("0".equals(lower)) {
            return Boolean.FALSE;
        }
        return null;
    }

    /**
     * Refuses a {@code session_authorization} setting naming anyone but {@code role}. PostgreSQL
     * would authorize it against the superuser the backend connection authenticated as, not the
     * token's role, so it would hand the session back to the master.
     */
    static void refuseSessionAuthorizationChange(List<SessionSetting> settings, String role)
            throws StartupParameterException {
        for (SessionSetting setting : settings) {
            if ("session_authorization".equalsIgnoreCase(setting.name()) && !role.equals(setting.value())) {
                throw new StartupParameterException("42501", "permission denied to set session authorization");
            }
        }
    }

    /** Whether {@code messages} report a {@code session_authorization} other than {@code role}. */
    private static boolean reportsAnotherSessionAuthorization(List<byte[]> messages, String role) {
        for (byte[] message : messages) {
            if (!"session_authorization".equals(parameterStatusName(message))) {
                continue;
            }
            String[] status = parameterStatus(message, 5);
            if (status != null && !role.equals(status[1])) {
                return true;
            }
        }
        return false;
    }

    /**
     * The settings an {@code options} startup parameter carries: {@code -c name=value},
     * {@code -cname=value} or {@code --name=value}, with arguments split on unescaped ASCII
     * whitespace and a backslash taking the next character literally, as PostgreSQL's
     * {@code pg_split_opts} does; a backslash that ends the string is dropped. A dash in a name stands for an underscore. The proxy cannot apply PostgreSQL's other
     * server switches to a session that has already started, so it refuses them.
     */
    static List<SessionSetting> parseOptions(String options) throws StartupParameterException {
        List<String> arguments = splitOptions(options);
        List<SessionSetting> settings = new ArrayList<>();
        for (int i = 0; i < arguments.size(); i++) {
            String argument = arguments.get(i);
            String assignment;
            String switchName;
            if (argument.startsWith("--")) {
                assignment = argument.substring(2);
                switchName = "--";
            } else if ("-c".equals(argument)) {
                if (i + 1 >= arguments.size()) {
                    throw new StartupParameterException("42601",
                            "invalid command-line argument for server process: -c");
                }
                assignment = arguments.get(++i);
                switchName = "-c ";
            } else if (argument.startsWith("-c")) {
                assignment = argument.substring(2);
                switchName = "-c ";
            } else {
                throw new StartupParameterException("42601",
                        "invalid command-line argument for server process: " + argument);
            }
            int equals = assignment.indexOf('=');
            if (equals <= 0) {
                throw new StartupParameterException("42601",
                        switchName + assignment + " requires a value");
            }
            settings.add(new SessionSetting(assignment.substring(0, equals).replace('-', '_'),
                    assignment.substring(equals + 1)));
        }
        return settings;
    }

    private static List<String> splitOptions(String options) {
        List<String> arguments = new ArrayList<>();
        int i = 0;
        while (i < options.length()) {
            while (i < options.length() && isAsciiSpace(options.charAt(i))) {
                i++;
            }
            if (i >= options.length()) {
                break;
            }
            StringBuilder argument = new StringBuilder();
            while (i < options.length() && !isAsciiSpace(options.charAt(i))) {
                if (options.charAt(i) == '\\') {
                    i++;
                    if (i >= options.length()) {
                        break;
                    }
                }
                argument.append(options.charAt(i));
                i++;
            }
            arguments.add(argument.toString());
        }
        return arguments;
    }

    /** The characters C's {@code isspace} accepts in the C locale. */
    private static boolean isAsciiSpace(char c) {
        return c == ' ' || c == '\t' || c == '\n' || c == '\u000B' || c == '\f' || c == '\r';
    }

    /**
     * Applies {@code settings} to the session in one statement, in order, with
     * {@code set_config(name, value, false)}: what a startup parameter does, checked against the
     * privileges of the role the session now belongs to.
     *
     * @return the backend messages the statement produced, up to ReadyForQuery or ErrorResponse
     */
    private static List<byte[]> applySessionSettings(InputStream in, OutputStream out,
                                                     List<SessionSetting> settings) throws IOException {
        sendMessage(out, 'Q', setConfigStatement(settings).getBytes(StandardCharsets.UTF_8), new byte[]{0});
        out.flush();
        return readUntilReadyForQuery(in);
    }

    static String setConfigStatement(List<SessionSetting> settings) {
        StringBuilder sql = new StringBuilder("SELECT ");
        for (int i = 0; i < settings.size(); i++) {
            if (i > 0) {
                sql.append(", ");
            }
            sql.append("pg_catalog.set_config(")
                    .append(quoteLiteral(settings.get(i).name()))
                    .append(", ")
                    .append(quoteLiteral(settings.get(i).value()))
                    .append(", false)");
        }
        return sql.toString();
    }

    /**
     * An escape-string literal, so it reads the same whatever standard_conforming_strings says.
     * Apostrophes are doubled rather than backslash-escaped, which backslash_quote can forbid.
     */
    static String quoteLiteral(String value) {
        return "E'" + value.replace("\\", "\\\\").replace("'", "''") + "'";
    }

    /**
     * Folds the ParameterStatus messages a statement produced into the buffered startup messages,
     * so the client's view of parameters such as {@code session_authorization} and
     * {@code is_superuser} matches the session it is handed. Same-named entries are replaced;
     * new ones are inserted ahead of BackendKeyData/ReadyForQuery.
     */
    static List<byte[]> applyParameterStatusUpdates(List<byte[]> buffered, List<byte[]> updates) {
        List<byte[]> merged = new ArrayList<>(buffered);
        for (byte[] update : updates) {
            String name = parameterStatusName(update);
            if (name == null) {
                continue;
            }
            int existing = indexOfParameterStatus(merged, name);
            if (existing >= 0) {
                merged.set(existing, update);
            } else {
                merged.add(startupTrailerIndex(merged), update);
            }
        }
        return merged;
    }

    private static int indexOfParameterStatus(List<byte[]> messages, String name) {
        for (int i = 0; i < messages.size(); i++) {
            if (name.equals(parameterStatusName(messages.get(i)))) {
                return i;
            }
        }
        return -1;
    }

    /** Index of the first BackendKeyData/ReadyForQuery: where ParameterStatus messages end. */
    private static int startupTrailerIndex(List<byte[]> messages) {
        for (int i = 0; i < messages.size(); i++) {
            char type = (char) messages.get(i)[0];
            if (type == 'K' || type == 'Z') {
                return i;
            }
        }
        return messages.size();
    }

    /** Name carried by a ParameterStatus message, or {@code null} for any other message. */
    private static String parameterStatusName(byte[] message) {
        if (message.length < 6 || message[0] != 'S') {
            return null;
        }
        String[] status = parameterStatus(message, 5);
        return status == null ? null : status[0];
    }

    /**
     * Splits the {@code name}/{@code value} pair a ParameterStatus carries, reading from
     * {@code offset}, or {@code null} when the message is truncated.
     */
    private static String[] parameterStatus(byte[] message, int offset) {
        int nameEnd = offset;
        while (nameEnd < message.length && message[nameEnd] != 0) {
            nameEnd++;
        }
        if (nameEnd >= message.length) {
            return null;
        }
        int valueEnd = nameEnd + 1;
        while (valueEnd < message.length && message[valueEnd] != 0) {
            valueEnd++;
        }
        return new String[] {
                new String(message, offset, nameEnd - offset, StandardCharsets.UTF_8),
                new String(message, nameEnd + 1, valueEnd - nameEnd - 1, StandardCharsets.UTF_8)
        };
    }

    /** Human-readable 'M' field of an ErrorResponse, or {@code fallback} when it carries none. */
    static String errorMessage(byte[] errorResponse, String fallback) {
        return errorField(errorResponse, 'M', fallback);
    }

    /** The {@code field} of an ErrorResponse, such as 'C' for SQLSTATE, or {@code fallback}. */
    static String errorField(byte[] errorResponse, char field, String fallback) {
        int i = 5; // skip type byte and Int32 length
        while (i < errorResponse.length && errorResponse[i] != 0) {
            char fieldType = (char) errorResponse[i];
            i++;
            int start = i;
            while (i < errorResponse.length && errorResponse[i] != 0) {
                i++;
            }
            if (fieldType == field) {
                return new String(errorResponse, start, i - start, StandardCharsets.UTF_8);
            }
            i++; // skip the field's null terminator
        }
        return fallback;
    }

    /**
     * Relays the authenticated session until either side closes.
     *
     * <p>An IAM session gets its backend→client direction read message by message so the proxy
     * sees PostgreSQL report the session's identity. The backend connection underneath is the
     * master one, so a client that talks the session back to the master role, via {@code RESET
     * SESSION AUTHORIZATION} and its variants, would otherwise regain superuser, which the
     * token never granted. Watching the server's own {@code session_authorization} report catches
     * that whatever SQL produced it; the session is then terminated, since a handover that already
     * happened cannot be taken back.
     */
    public static void bridge(AuthenticatedSession session) {
        Socket client = session.client();
        Socket backend = session.backend();
        InputStream clientIn, backendIn;
        OutputStream clientOut, backendOut;
        try {
            clientIn = client.getInputStream();
            clientOut = client.getOutputStream();
            backendIn = backend.getInputStream();
            backendOut = backend.getOutputStream();
        } catch (IOException e) {
            closeQuietly(client);
            closeQuietly(backend);
            return;
        }
        AtomicBoolean closed = new AtomicBoolean();
        Runnable closeBoth = () -> {
            if (closed.compareAndSet(false, true)) {
                closeQuietly(client);
                closeQuietly(backend);
            }
        };
        Thread t1 = Thread.ofVirtual().name("rds-pg-c2b")
                .start(() -> relay(clientIn, backendOut, closeBoth));
        Thread t2 = Thread.ofVirtual().name("rds-pg-b2c")
                .start(() -> relayToClient(backendIn, clientOut, session.iamRole(), closeBoth));
        try {
            t1.join();
            t2.join();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } finally {
            closeQuietly(client);
            closeQuietly(backend);
        }
    }

    private static void relayToClient(InputStream from, OutputStream to, String iamRole,
                                      Runnable onDone) {
        if (iamRole == null) {
            relay(from, to, onDone);
            return;
        }
        BufferedOutputStream buffered = new BufferedOutputStream(to, 8192);
        try {
            relayGuardingSessionRole(from, buffered, iamRole);
        } catch (IOException e) {
            // Either side closing mid-relay is the normal way a session ends, so this stays at
            // debug: it is one line per connection teardown, not per message.
            LOG.debugv("RDS IAM session relay for role {0} ended: {1}", iamRole, e.getMessage());
        } finally {
            try {
                buffered.flush();
            } catch (IOException e) {
                LOG.debugv("Client for RDS IAM role {0} gone before the last flush: {1}",
                        iamRole, e.getMessage());
            }
            onDone.run();
        }
    }

    /**
     * Copies framed backend messages, stopping the session if PostgreSQL ever reports a
     * {@code session_authorization} other than {@code iamRole}.
     */
    private static void relayGuardingSessionRole(InputStream from, OutputStream to, String iamRole)
            throws IOException {
        byte[] buf = new byte[8192];
        while (true) {
            int type = from.read();
            if (type < 0) {
                return;
            }
            byte[] header = new byte[4];
            readFully(from, header);
            int length = ((header[0] & 0xFF) << 24) | ((header[1] & 0xFF) << 16)
                    | ((header[2] & 0xFF) << 8) | (header[3] & 0xFF);
            if (length < 4) {
                return;
            }
            int remaining = length - 4;

            if (type == 'S') {
                // ParameterStatus is always small, so reading it whole to inspect costs nothing.
                byte[] payload = new byte[remaining];
                readFully(from, payload);
                String[] status = parameterStatus(payload, 0);
                if (status != null && "session_authorization".equals(status[0])
                        && !iamRole.equals(status[1])) {
                    LOG.warnv("RDS IAM session for role {0} tried to switch to {1}; terminating",
                            iamRole, status[1]);
                    sendErrorResponse(to, "FATAL", "42501",
                            "permission denied to set session authorization");
                    to.flush();
                    return;
                }
                to.write(type);
                to.write(header);
                to.write(payload);
            } else {
                to.write(type);
                to.write(header);
                while (remaining > 0) {
                    int n = from.read(buf, 0, Math.min(buf.length, remaining));
                    if (n < 0) {
                        return;
                    }
                    to.write(buf, 0, n);
                    remaining -= n;
                }
            }

            // Batch while the backend keeps talking, flush as soon as it pauses.
            if (from.available() == 0) {
                to.flush();
            }
        }
    }

    private static void relay(InputStream from, OutputStream to, Runnable onDone) {
        try {
            byte[] buf = new byte[8192];
            int n;
            while ((n = from.read(buf)) != -1) {
                to.write(buf, 0, n);
                to.flush();
            }
        } catch (IOException ignored) {
        } finally {
            onDone.run();
        }
    }

    // ── Error response ────────────────────────────────────────────────────────

    private static void sendErrorResponse(OutputStream out, String severity, String sqlState,
                                          String message) throws IOException {
        byte[] sevBytes = severity.getBytes(StandardCharsets.UTF_8);
        byte[] stateBytes = sqlState.getBytes(StandardCharsets.UTF_8);
        byte[] msgBytes = message.getBytes(StandardCharsets.UTF_8);

        // Fields: S=severity, C=sqlstate, M=message, then final null byte
        ByteArrayOutputStream fields = new ByteArrayOutputStream();
        fields.write('S'); fields.write(sevBytes); fields.write(0);
        fields.write('C'); fields.write(stateBytes); fields.write(0);
        fields.write('M'); fields.write(msgBytes); fields.write(0);
        fields.write(0); // final null

        sendMessage(out, 'E', fields.toByteArray());
    }

    // ── Wire helpers ──────────────────────────────────────────────────────────

    private static void sendMessage(OutputStream out, char type, byte[]... parts) throws IOException {
        int totalPayload = 0;
        for (byte[] p : parts) {
            totalPayload += p.length;
        }
        out.write((byte) type);
        writeInt32(out, 4 + totalPayload); // length includes itself
        for (byte[] p : parts) {
            out.write(p);
        }
    }

    private static byte[] intBytes(int value) {
        return new byte[]{
                (byte) ((value >> 24) & 0xFF),
                (byte) ((value >> 16) & 0xFF),
                (byte) ((value >> 8) & 0xFF),
                (byte) (value & 0xFF)
        };
    }

    private static void writeInt32(OutputStream out, int value) throws IOException {
        out.write((value >> 24) & 0xFF);
        out.write((value >> 16) & 0xFF);
        out.write((value >> 8) & 0xFF);
        out.write(value & 0xFF);
    }

    private static int readInt32(InputStream in) throws IOException {
        int b0 = in.read();
        int b1 = in.read();
        int b2 = in.read();
        int b3 = in.read();
        if ((b0 | b1 | b2 | b3) < 0) {
            throw new EOFException("Connection closed while reading Int32");
        }
        return (b0 << 24) | (b1 << 16) | (b2 << 8) | b3;
    }

    private static int checkedPacketLength(int length, int minimum, String packetName) throws IOException {
        if (length < minimum) {
            throw new IOException("PostgreSQL " + packetName + " length is below the "
                    + minimum + " byte minimum: " + length);
        }
        if (length > MAX_HANDSHAKE_PACKET_LENGTH) {
            throw new IOException("PostgreSQL " + packetName + " length exceeds the "
                    + MAX_HANDSHAKE_PACKET_LENGTH + " byte limit: " + length);
        }
        return length;
    }

    private static void readFully(InputStream in, byte[] buf) throws IOException {
        int offset = 0;
        while (offset < buf.length) {
            int n = in.read(buf, offset, buf.length - offset);
            if (n < 0) {
                throw new EOFException("Connection closed while reading " + buf.length + " bytes");
            }
            offset += n;
        }
    }

    private static String bytesToHex(byte[] bytes) {
        StringBuilder sb = new StringBuilder(bytes.length * 2);
        for (byte b : bytes) {
            sb.append(String.format("%02x", b));
        }
        return sb.toString();
    }

    static void closeQuietly(Socket s) {
        try { s.close(); } catch (IOException ignored) {}
    }
}
