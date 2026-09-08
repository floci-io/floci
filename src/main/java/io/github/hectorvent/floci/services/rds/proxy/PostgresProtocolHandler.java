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
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashMap;
import java.util.List;
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
 *   <li>For IAM logins, hand the session over to the role named in the token
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
    public record AuthenticatedSession(Socket client, String iamRole) {}

    public static AuthenticatedSession authenticate(Socket client, Socket backend,
                                      String masterUsername, String masterPassword, String dbName,
                                      boolean iamEnabled, RdsSigV4Validator sigV4,
                                      RdsProxyTlsCertificates tlsCertificates,
                                      PasswordValidator passwordValidator) throws IOException {

        // Phase 1: Read client startup message (possibly preceded by SSL request)
        StartupMessage startup = readStartupMessage(client, tlsCertificates);
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
        // - Master user (plain password): validated at the proxy via passwordValidator, which
        //   reads from RdsService and therefore reflects modifyDBInstance password changes.
        // - Non-master users: pass through — the backend is the authority for their passwords.
        boolean isIam = iamEnabled && clientPassword.contains("X-Amz-Signature");
        boolean isMaster = masterUsername.equals(clientUsername);

        if (isIam) {
            if (!sigV4.validate(clientPassword, clientUsername)) {
                sendErrorResponse(clientOut, "FATAL", "28P01",
                        "password authentication failed for user \"" + clientUsername + "\"");
                clientOut.flush();
                closeQuietly(client);
                closeQuietly(backend);
                return null;
            }
        } else if (isMaster) {
            if (!passwordValidator.validate(clientUsername, clientPassword)) {
                sendErrorResponse(clientOut, "FATAL", "28P01",
                        "password authentication failed for user \"" + clientUsername + "\"");
                clientOut.flush();
                closeQuietly(client);
                closeQuietly(backend);
                return null;
            }
        }

        // Phase 5: Connect to backend PostgreSQL.
        // IAM and master: use master credentials — the backend has the original container password
        // and is never updated directly, so the proxy always authenticates as master.
        // Non-master: forward the client's own credentials so the backend enforces its own ACLs.
        InputStream backendIn = backend.getInputStream();
        OutputStream backendOut = backend.getOutputStream();

        String effectiveDbName = resolveEffectiveDbName(startup.database(), dbName);
        String backendUser = (isIam || isMaster) ? masterUsername : clientUsername;
        String backendPass = (isIam || isMaster) ? masterPassword : clientPassword;
        sendStartupToBackend(backendOut, backendUser, effectiveDbName);
        backendOut.flush();

        if (!authenticateWithBackend(backendIn, backendOut, backendUser, backendPass)) {
            sendErrorResponse(clientOut, "FATAL", "08006",
                    "Backend database authentication failed");
            clientOut.flush();
            closeQuietly(client);
            closeQuietly(backend);
            return null;
        }

        // Buffer all backend messages until ReadyForQuery ('Z')
        List<byte[]> bufferedMessages = readUntilReadyForQuery(backendIn);

        String iamRole = null;

        // Phase 5b: An IAM token is issued for one specific database role, so the session must run
        // as that role even though the backend connection was opened as master. Handing it over
        // gives the session the role's own privileges and object ownership, and refuses a token
        // naming a role the database does not have instead of silently granting a master session.
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

        return new AuthenticatedSession(client, iamRole);
    }

    // ── Startup ───────────────────────────────────────────────────────────────

    private static StartupMessage readStartupMessage(Socket socket, RdsProxyTlsCertificates tlsCertificates)
            throws IOException {
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
                    params.get("database"));
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

    private record StartupMessage(Socket socket, String username, String database) {}

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

    private static Map<String, String> parseStartupParams(byte[] data) {
        Map<String, String> params = new HashMap<>();
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

    private static void sendStartupToBackend(OutputStream out, String username, String dbName)
            throws IOException {
        byte[] userKey = "user".getBytes(StandardCharsets.UTF_8);
        byte[] userVal = username.getBytes(StandardCharsets.UTF_8);
        byte[] dbKey = "database".getBytes(StandardCharsets.UTF_8);
        byte[] dbVal = dbName.getBytes(StandardCharsets.UTF_8);

        int length = 4 + 4
                + userKey.length + 1 + userVal.length + 1
                + dbKey.length + 1 + dbVal.length + 1
                + 1; // final null

        writeInt32(out, length);
        writeInt32(out, STARTUP_PROTOCOL_VERSION);
        out.write(userKey); out.write(0);
        out.write(userVal); out.write(0);
        out.write(dbKey); out.write(0);
        out.write(dbVal); out.write(0);
        out.write(0); // final null
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
        int type = in.read();
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
        if (in.read() != 'R') {
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
        if (in.read() != 'R') {
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
        int type = in.read();
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
            messages.add(full);

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
        int i = 5; // skip type byte and Int32 length
        while (i < errorResponse.length && errorResponse[i] != 0) {
            char fieldType = (char) errorResponse[i];
            i++;
            int start = i;
            while (i < errorResponse.length && errorResponse[i] != 0) {
                i++;
            }
            if (fieldType == 'M') {
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
    public static void bridge(AuthenticatedSession session, Socket backend) {
        Socket client = session.client();
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
