package io.github.hectorvent.floci.services.rds.proxy;

import org.jboss.logging.Logger;
import org.bouncycastle.asn1.x500.X500Name;
import org.bouncycastle.asn1.x509.Extension;
import org.bouncycastle.asn1.x509.GeneralName;
import org.bouncycastle.asn1.x509.GeneralNames;
import org.bouncycastle.cert.X509v3CertificateBuilder;
import org.bouncycastle.cert.jcajce.JcaX509CertificateConverter;
import org.bouncycastle.cert.jcajce.JcaX509v3CertificateBuilder;
import org.bouncycastle.jce.provider.BouncyCastleProvider;
import org.bouncycastle.operator.ContentSigner;
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder;

import javax.crypto.Mac;
import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.PBEKeySpec;
import javax.crypto.spec.SecretKeySpec;
import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLSocket;
import java.io.ByteArrayOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.math.BigInteger;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.KeyStore;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.security.Security;
import java.security.cert.X509Certificate;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

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
 *   <li>Send AuthOK + buffered messages to client, then bridge
 * </ol>
 */
public class PostgresProtocolHandler {

    private static final Logger LOG = Logger.getLogger(PostgresProtocolHandler.class);

    private static final int SSL_REQUEST_CODE = 80877103;
    private static final int STARTUP_PROTOCOL_VERSION = 196608; // v3.0
    private static volatile SSLContext serverSslContext;

    public static void handleAuth(Socket client, Socket backend,
                                  String masterUsername, String masterPassword, String dbName,
                                  boolean iamEnabled, RdsSigV4Validator sigV4,
                                  PasswordValidator passwordValidator) throws IOException {

        // Phase 1: Read client startup message (possibly preceded by SSL request)
        StartupMessage startup = readStartupMessage(client);
        if (startup == null) {
            closeQuietly(client);
            return;
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
            return;
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
                return;
            }
        } else if (isMaster) {
            if (!passwordValidator.validate(clientUsername, clientPassword)) {
                sendErrorResponse(clientOut, "FATAL", "28P01",
                        "password authentication failed for user \"" + clientUsername + "\"");
                clientOut.flush();
                closeQuietly(client);
                closeQuietly(backend);
                return;
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
            return;
        }

        // Buffer all backend messages until ReadyForQuery ('Z')
        List<byte[]> bufferedMessages = readUntilReadyForQuery(backendIn);

        // Phase 6: Send AuthenticationOK to client, forward buffered messages, then bridge
        if (endsWithErrorResponse(bufferedMessages)) {
            for (byte[] msg : bufferedMessages) {
                clientOut.write(msg);
            }
            clientOut.flush();
            closeQuietly(client);
            closeQuietly(backend);
            return;
        }

        sendMessage(clientOut, 'R', intBytes(0)); // AuthenticationOK
        for (byte[] msg : bufferedMessages) {
            clientOut.write(msg);
        }
        clientOut.flush();

        bridge(client, backend);
    }

    // ── Startup ───────────────────────────────────────────────────────────────

    private static StartupMessage readStartupMessage(Socket socket) throws IOException {
        Socket currentSocket = socket;
        while (true) {
            InputStream in = currentSocket.getInputStream();
            OutputStream out = currentSocket.getOutputStream();
            int length = readInt32(in);
            if (length < 8) {
                return null;
            }
            int proto = readInt32(in);

            if (proto == SSL_REQUEST_CODE) {
                out.write('S');
                out.flush();
                currentSocket = acceptSsl(currentSocket);
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

    static Socket acceptSsl(Socket socket) throws IOException {
        try {
            SSLSocket sslSocket = (SSLSocket) serverSslContext().getSocketFactory()
                    .createSocket(socket, socket.getInetAddress().getHostAddress(), socket.getPort(), true);
            sslSocket.setUseClientMode(false);
            sslSocket.startHandshake();
            return sslSocket;
        } catch (Exception e) {
            throw new IOException("Unable to negotiate PostgreSQL SSL", e);
        }
    }

    private static SSLContext serverSslContext() throws Exception {
        SSLContext context = serverSslContext;
        if (context != null) {
            return context;
        }
        synchronized (PostgresProtocolHandler.class) {
            context = serverSslContext;
            if (context == null) {
                context = createServerSslContext();
                serverSslContext = context;
            }
            return context;
        }
    }

    private static SSLContext createServerSslContext() throws Exception {
        if (Security.getProvider(BouncyCastleProvider.PROVIDER_NAME) == null) {
            Security.addProvider(new BouncyCastleProvider());
        }

        KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
        generator.initialize(2048, new SecureRandom());
        KeyPair keyPair = generator.generateKeyPair();
        Instant now = Instant.now();

        X500Name name = new X500Name("CN=localhost");
        X509v3CertificateBuilder certificateBuilder = new JcaX509v3CertificateBuilder(
                name,
                new BigInteger(128, new SecureRandom()),
                Date.from(now.minus(1, ChronoUnit.MINUTES)),
                Date.from(now.plus(365, ChronoUnit.DAYS)),
                name,
                keyPair.getPublic());
        certificateBuilder.addExtension(Extension.subjectAlternativeName, false, new GeneralNames(new GeneralName[] {
                new GeneralName(GeneralName.dNSName, "localhost"),
                new GeneralName(GeneralName.iPAddress, "127.0.0.1")
        }));

        ContentSigner signer = new JcaContentSignerBuilder("SHA256withRSA")
                .setProvider(BouncyCastleProvider.PROVIDER_NAME)
                .build(keyPair.getPrivate());
        X509Certificate certificate = new JcaX509CertificateConverter()
                .setProvider(BouncyCastleProvider.PROVIDER_NAME)
                .getCertificate(certificateBuilder.build(signer));

        char[] password = new char[0];
        KeyStore keyStore = KeyStore.getInstance("PKCS12");
        keyStore.load(null, password);
        keyStore.setKeyEntry("floci-rds-postgres", keyPair.getPrivate(), password, new java.security.cert.Certificate[] {certificate});

        KeyManagerFactory keyManagerFactory = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
        keyManagerFactory.init(keyStore, password);

        SSLContext context = SSLContext.getInstance("TLS");
        context.init(keyManagerFactory.getKeyManagers(), null, new SecureRandom());
        return context;
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
        int length = readInt32(in);
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

        int length = readInt32(in);
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
        int len2 = readInt32(in);
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
        int len3 = readInt32(in);
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
        int length = readInt32(in);
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
            int length = readInt32(in);
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

    // ── Bridge ────────────────────────────────────────────────────────────────

    private static void bridge(Socket client, Socket backend) {
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
        Thread t1 = Thread.ofVirtual().name("rds-pg-c2b")
                .start(() -> relay(clientIn, backendOut));
        Thread t2 = Thread.ofVirtual().name("rds-pg-b2c")
                .start(() -> relay(backendIn, clientOut));
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

    private static void relay(InputStream from, OutputStream to) {
        try {
            byte[] buf = new byte[8192];
            int n;
            while ((n = from.read(buf)) != -1) {
                to.write(buf, 0, n);
                to.flush();
            }
        } catch (IOException ignored) {}
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
