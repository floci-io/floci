package io.github.hectorvent.floci.services.rds.proxy;

import org.jboss.logging.Logger;

import javax.net.ssl.SSLSocket;
import java.io.ByteArrayOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Arrays;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Handles the MySQL wire protocol auth intercept using a transparent relay.
 *
 * <p>The proxy reads the backend's real Handshake V10 (with the backend's actual nonce)
 * and forwards it to the client (with {@code CLIENT_SSL} forced on, so the client can
 * request TLS even though the backend container itself stays plaintext). The client then
 * computes its scramble using the backend's nonce. The proxy validates the scramble against
 * the expected master password, then forwards the client's HandshakeResponse directly to the
 * backend for final validation.
 *
 * <p>This avoids any synthetic nonce and lets the backend handle all auth plugin
 * negotiation (including caching_sha2_password auth-switch) transparently.
 *
 * <p>If the client requests TLS, it sends a short {@code SSLRequest} packet (32-byte payload,
 * {@code CLIENT_SSL} set) instead of a full {@code HandshakeResponse41}, then immediately starts
 * a TLS handshake on the same socket. The proxy terminates that TLS handshake itself (the
 * backend connection remains plaintext), reads the real {@code HandshakeResponse41} over the
 * now-encrypted stream, and rewrites its sequence number before forwarding it to the backend —
 * the backend never saw an {@code SSLRequest}, so it expects that response at sequence 1, not 2.
 * That one "stolen" packet leaves the backend's view of the shared sequence counter permanently
 * one behind the client's for the rest of the connection phase, so every packet exchanged until
 * the terminal OK/ERR (including any {@code AuthSwitchRequest}/{@code AuthMoreData} round trips)
 * is relayed through {@link #relayConnectionPhase} to keep both sides in sync before handing off
 * to the plain byte-for-byte {@link #bridge}.
 *
 * <p>With IAM database authentication enabled, an IAM token offered in cleartext
 * ({@code mysql_clear_password}) is validated by the proxy, which then authenticates the session to
 * the backend as the master user. Clients normally reveal the token only after the server asks them
 * to switch to {@code mysql_clear_password}, so when the backend rejects a non-master login the proxy
 * asks for that switch itself (see {@link #authenticateIamUserAfterAuthSwitch}).
 */
public class MySqlProtocolHandler {

    private static final Logger LOG = Logger.getLogger(MySqlProtocolHandler.class);

    private static final int CLIENT_SSL = 0x0800;
    private static final int CLIENT_PLUGIN_AUTH = 0x0008_0000;
    private static final int CLIENT_PLUGIN_AUTH_LENENC_CLIENT_DATA = 0x200000;
    private static final int CLIENT_SECURE_CONNECTION = 0x8000;
    private static final int CLIENT_CONNECT_WITH_DB = 0x0008;
    private static final String MYSQL_NATIVE_PASSWORD = "mysql_native_password";
    private static final String CACHING_SHA2_PASSWORD = "caching_sha2_password";
    private static final String MYSQL_CLEAR_PASSWORD = "mysql_clear_password";

    // First payload byte of the packet types that can appear during the connection phase.
    private static final int OK_PACKET_MARKER = 0x00;
    private static final int ERR_PACKET_MARKER = 0xFF;
    private static final int AUTH_MORE_DATA_MARKER = 0x01;
    private static final int AUTH_SWITCH_REQUEST_MARKER = 0xFE;

    // Server error code for a failed login ("Access denied for user ...").
    private static final int ER_ACCESS_DENIED_ERROR = 1045;

    // Second payload byte of an AuthMoreData packet under caching_sha2_password: fast_auth_success
    // means the backend is about to send the terminal OK with no further input from the client.
    private static final int CACHING_SHA2_FAST_AUTH_SUCCESS = 0x03;

    // 4-byte capabilities + 4-byte max-packet-size + 1-byte charset + 23 reserved bytes.
    private static final int SSL_REQUEST_PAYLOAD_LENGTH = 32;

    private static final int COM_QUERY = 0x03;
    private static final int MAX_PACKET_PAYLOAD = 0xFFFFFF;

    private static final Pattern IAM_AUTH_PLUGIN = Pattern.compile(
            "IDENTIFIED\\s+WITH\\s+`?AWSAuthenticationPlugin`?"
                    + "(?:\\s+AS\\s+(?:'[^']*'|\"[^\"]*\"|0x[0-9A-Fa-f]+))?",
            Pattern.CASE_INSENSITIVE);
    private static final String NO_MATCHING_PASSWORD_HASH =
            "*0000000000000000000000000000000000000000";
    private static final String IAM_AUTH_PLUGIN_REPLACEMENT =
            "IDENTIFIED WITH mysql_native_password AS '" + NO_MATCHING_PASSWORD_HASH + "'";

    public static void handleAuth(Socket client, Socket backend,
                                  PostgresProtocolHandler.BackendConnector backendConnector,
                                  String masterUsername, String masterPassword,
                                  boolean iamEnabled, RdsSigV4Validator sigV4,
                                  RdsProxyTlsCertificates tlsCertificates,
                                  PasswordValidator passwordValidator,
                                  int handshakeTimeoutMillis) throws IOException {
        handleAuth(client, backend, backendConnector, masterUsername, masterPassword,
                iamEnabled, sigV4, tlsCertificates, passwordValidator, handshakeTimeoutMillis,
                username -> true, null);
    }

    public static void handleAuth(Socket client, Socket backend,
                                  PostgresProtocolHandler.BackendConnector backendConnector,
                                  String masterUsername, String masterPassword,
                                  boolean iamEnabled, RdsSigV4Validator sigV4,
                                  RdsProxyTlsCertificates tlsCertificates,
                                  PasswordValidator passwordValidator,
                                  int handshakeTimeoutMillis,
                                  IamUserChecker iamUserChecker) throws IOException {
        handleAuth(client, backend, backendConnector, masterUsername, masterPassword, iamEnabled,
                sigV4, tlsCertificates, passwordValidator, handshakeTimeoutMillis, iamUserChecker, null);
    }

    public static void handleAuth(Socket client, Socket backend,
                                  PostgresProtocolHandler.BackendConnector backendConnector,
                                  String masterUsername, String masterPassword,
                                  boolean iamEnabled, RdsSigV4Validator sigV4,
                                  RdsProxyTlsCertificates tlsCertificates,
                                  PasswordValidator passwordValidator,
                                  int handshakeTimeoutMillis,
                                  IamUserChecker iamUserChecker,
                                  RdsMysqlBinding mysqlBinding) throws IOException {

        client.setSoTimeout(handshakeTimeoutMillis);
        // The backend accepted the TCP connection but may never answer (or answer only
        // partially); bound every blocking backend read during the handshake so a silent
        // backend cannot pin this handler thread and its connection permit forever.
        backend.setSoTimeout(handshakeTimeoutMillis);

        InputStream backendIn = backend.getInputStream();
        OutputStream backendOut = backend.getOutputStream();

        // Phase 1: Read the backend's real Handshake V10
        byte[] backendHandshakeRaw = readMysqlPacketRaw(backendIn);
        if (backendHandshakeRaw == null || backendHandshakeRaw.length < 5) {
            LOG.warnv("MySQL backend sent no handshake");
            closeQuietly(client);
            closeQuietly(backend);
            return;
        }

        // Extract the backend's nonce for our credential validation
        byte[] backendNonce = extractMysqlNonce(backendHandshakeRaw);
        String backendAuthPlugin = extractMysqlAuthPlugin(backendHandshakeRaw);

        // The proxy terminates TLS itself, so advertise CLIENT_SSL to the client even if the
        // (plaintext) backend didn't.
        forceClientSslCapability(backendHandshakeRaw);

        InputStream clientIn = client.getInputStream();
        OutputStream clientOut = client.getOutputStream();

        // Phase 2: Forward backend's handshake (with CLIENT_SSL forced on) to the client
        clientOut.write(backendHandshakeRaw);
        clientOut.flush();

        // Phase 3: Read the client's first response — either the real HandshakeResponse41,
        // or a short SSLRequest signaling that a TLS upgrade should happen first.
        byte[] clientFirstRaw = readMysqlPacketRaw(clientIn);
        if (clientFirstRaw == null) {
            closeQuietly(client);
            closeQuietly(backend);
            return;
        }

        byte[] clientResponseRaw;
        int sequenceOffset = 0;
        if (isSslRequest(clientFirstRaw)) {
            SSLSocket sslSocket;
            try {
                sslSocket = upgradeToTls(client, tlsCertificates);
            } catch (IOException e) {
                LOG.warnv("MySQL TLS upgrade failed: {0}", e.getMessage());
                closeQuietly(client);
                closeQuietly(backend);
                return;
            }
            client = sslSocket;
            // upgradeToTls wraps the socket in a new SSLSocket; re-apply the handshake timeout
            // since it is not guaranteed to be inherited from the underlying socket.
            client.setSoTimeout(handshakeTimeoutMillis);
            clientIn = sslSocket.getInputStream();
            clientOut = sslSocket.getOutputStream();

            clientResponseRaw = readMysqlPacketRaw(clientIn);
            if (clientResponseRaw == null || clientResponseRaw.length < 40) {
                closeQuietly(client);
                closeQuietly(backend);
                return;
            }
            // The backend never saw the SSLRequest, so its view of the shared sequence counter is
            // permanently one behind the client's for the rest of the connection phase.
            sequenceOffset = 1;
            clientResponseRaw[3] = (byte) (clientResponseRaw[3] - sequenceOffset);
            // Real clients keep CLIENT_SSL set in the HandshakeResponse they send over the
            // now-encrypted stream. The backend connection stays plaintext, and a MySQL server
            // treats any first packet with CLIENT_SSL as an SSLRequest — it would sit waiting for
            // a TLS ClientHello that never comes while the proxy waits for its auth verdict,
            // deadlocking the connection. Clear the bit (the mirror of forceClientSslCapability).
            clearClientSslCapability(clientResponseRaw);
        } else {
            clientResponseRaw = clientFirstRaw;
            if (clientResponseRaw.length < 40) {
                closeQuietly(client);
                closeQuietly(backend);
                return;
            }
        }

        // Phase 4: Validate credentials against the backend nonce.
        // IAM token sent up front in cleartext: validate SigV4, then connect to backend as master.
        // Master user: validate the scramble locally against the known master password.
        // Non-master users: pass through — the backend validates their scramble directly.
        ParsedHandshakeResponse parsed = parseHandshakeResponse(
                Arrays.copyOfRange(clientResponseRaw, 4, clientResponseRaw.length));
        String clientUsername = parsed.username();
        boolean masterUser = masterUsername.equals(clientUsername);
        boolean tlsEstablished = client instanceof SSLSocket ssl && ssl.getSession().isValid();
        boolean iamLogin = iamEnabled && tlsEstablished && MYSQL_CLEAR_PASSWORD.equals(parsed.authPlugin())
                && isIamToken(clearPassword(parsed.authData()));

        boolean valid;
        try {
            if (iamEnabled && MYSQL_CLEAR_PASSWORD.equals(parsed.authPlugin())
                    && isIamToken(clearPassword(parsed.authData())) && !tlsEstablished) {
                valid = false;
            } else if (iamLogin) {
                valid = sigV4.validate(clearPassword(parsed.authData()), clientUsername, mysqlBinding);
                if (valid) {
                    clientResponseRaw = rewriteHandshakeCredentials(
                            clientResponseRaw, parsed, masterUsername,
                            scramblePassword(masterPassword, backendNonce, backendAuthPlugin),
                            backendAuthPlugin);
                }
            } else if (masterUser) {
                byte[] expected = scramblePassword(
                        masterPassword, backendNonce, backendAuthPlugin);
                valid = Arrays.equals(expected, parsed.authData());
            } else {
                // Non-master user: defer to backend — it knows their password.
                valid = true;
            }
        } catch (Exception e) {
            LOG.warnv("MySQL auth error for instance: {0}", e.getMessage());
            valid = false;
        }

        if (!valid) {
            byte[] err = buildErrorPacket(ER_ACCESS_DENIED_ERROR,
                    "Access denied for user '" + clientUsername + "'@'localhost' (using password: YES)");
            writeMysqlPacket(clientOut, 2, err);
            clientOut.flush();
            closeQuietly(client);
            closeQuietly(backend);
            return;
        }

        // Phase 5: Forward client's HandshakeResponse to backend, then relay the rest of the
        // connection phase (renumbering if a TLS upgrade shifted the shared sequence counter)
        // before handing off to the plain byte-for-byte bridge. With IAM auth enabled, a non-master
        // login may be an IAM user whose client only sends its token after an auth switch, so the
        // backend's verdict is inspected before it reaches the client.
        backendOut.write(clientResponseRaw);
        backendOut.flush();

        boolean mayBeIamUser = iamEnabled && !masterUser && !iamLogin;
        if (sequenceOffset != 0 || mayBeIamUser) {
            byte[] verdict = relayConnectionPhase(
                    clientIn, clientOut, backendIn, backendOut, sequenceOffset);
            if (verdict == null) {
                closeQuietly(client);
                closeQuietly(backend);
                return;
            }
            if (mayBeIamUser && isAccessDenied(verdict)
                    && tlsEstablished && iamUserChecker.isIamUser(clientUsername)) {
                closeQuietly(backend);
                authenticateIamUserAfterAuthSwitch(
                        client, clientIn, clientOut, verdict[3] & 0xFF, clientResponseRaw, parsed,
                        backendNonce, backendConnector, masterUsername, masterPassword, sigV4,
                        handshakeTimeoutMillis, mysqlBinding);
                return;
            }
            clientOut.write(verdict);
            clientOut.flush();
        }

        // Authenticated: clear the handshake deadline so a long-lived idle session is never killed.
        client.setSoTimeout(0);
        backend.setSoTimeout(0);
        bridge(client, backend);
    }

    /**
     * Relays packets between client and backend for the remainder of the connection phase,
     * shifting each sequence number by {@code offset} to correct for packets the backend never
     * saw (see {@link #handleAuth}). Stops at the terminal OK/ERR packet and returns it renumbered
     * but not yet forwarded, so the caller can act on the verdict. After it both sides
     * independently reset their sequence counters for the next command, so the offset no longer
     * applies and {@link #bridge} can take over untouched.
     *
     * @return the terminal OK/ERR packet, or {@code null} if either side closed the connection
     *         mid-exchange
     */
    private static byte[] relayConnectionPhase(InputStream clientIn, OutputStream clientOut,
                                               InputStream backendIn, OutputStream backendOut,
                                               int offset) throws IOException {
        while (true) {
            byte[] backendRaw = readMysqlPacketRaw(backendIn);
            if (backendRaw == null || backendRaw.length < 5) {
                return null;
            }
            backendRaw[3] = (byte) (backendRaw[3] + offset);

            int marker = backendRaw[4] & 0xFF;
            if (marker == OK_PACKET_MARKER || marker == ERR_PACKET_MARKER) {
                return backendRaw;
            }
            clientOut.write(backendRaw);
            clientOut.flush();

            if (marker == AUTH_MORE_DATA_MARKER && backendRaw.length > 5
                    && (backendRaw[5] & 0xFF) == CACHING_SHA2_FAST_AUTH_SUCCESS) {
                // caching_sha2_password fast-auth success: the client sends nothing back — the
                // backend's very next packet is the terminal OK. Reading from the client here would
                // block forever.
                continue;
            }

            // AuthSwitchRequest, or AuthMoreData requesting full authentication: the client replies
            // once more before the backend issues its next verdict.
            byte[] clientRaw = readMysqlPacketRaw(clientIn);
            if (clientRaw == null || clientRaw.length < 4) {
                return null;
            }
            clientRaw[3] = (byte) (clientRaw[3] - offset);
            backendOut.write(clientRaw);
            backendOut.flush();
        }
    }

    // ── IAM authentication ────────────────────────────────────────────────────

    /**
     * Standard clients (libmysqlclient, PyMySQL, mysql2, Connector/J) answer the plugin the server
     * advertised with a scramble of the IAM token, and send the token itself only when asked to
     * switch to {@code mysql_clear_password}, as RDS does for {@code AWSAuthenticationPlugin} users.
     * The backend holds those users with a password hash that never matches (see
     * {@link #rewriteIamAuthPlugin}), so it rejects the scramble. This turns that rejection into the
     * auth switch, validates the token, and authenticates the session as master on a fresh backend
     * connection, since the backend drops the one whose login failed.
     *
     * @param switchSequence the sequence number the client expects the server's next packet at
     */
    private static void authenticateIamUserAfterAuthSwitch(
            Socket client, InputStream clientIn, OutputStream clientOut, int switchSequence,
            byte[] clientResponseRaw, ParsedHandshakeResponse parsed, byte[] nonce,
            PostgresProtocolHandler.BackendConnector backendConnector,
            String masterUsername, String masterPassword, RdsSigV4Validator sigV4,
            int handshakeTimeoutMillis, RdsMysqlBinding mysqlBinding) throws IOException {
        if (!(client instanceof SSLSocket ssl) || !ssl.getSession().isValid()) {
            closeQuietly(client);
            return;
        }
        writeMysqlPacket(clientOut, switchSequence, buildAuthSwitchRequest(MYSQL_CLEAR_PASSWORD, nonce));
        clientOut.flush();

        byte[] reply = readMysqlPacketRaw(clientIn);
        if (reply == null) {
            closeQuietly(client);
            return;
        }
        int nextSequence = (reply[3] & 0xFF) + 1;
        String token = clearPassword(Arrays.copyOfRange(reply, 4, reply.length));
        boolean valid;
        try {
            valid = isIamToken(token) && sigV4.validate(token, parsed.username(), mysqlBinding);
        } catch (Exception e) {
            LOG.warnv("MySQL IAM auth error: {0}", e.getMessage());
            valid = false;
        }
        if (!valid) {
            byte[] err = buildErrorPacket(ER_ACCESS_DENIED_ERROR, "Access denied for user '"
                    + parsed.username() + "'@'localhost' (using password: YES)");
            writeMysqlPacket(clientOut, nextSequence, err);
            clientOut.flush();
            closeQuietly(client);
            return;
        }

        Socket backend = backendConnector.connect();
        try {
            backend.setSoTimeout(handshakeTimeoutMillis);
            InputStream backendIn = backend.getInputStream();
            OutputStream backendOut = backend.getOutputStream();
            byte[] handshake = readMysqlPacketRaw(backendIn);
            if (handshake == null || handshake.length < 5) {
                LOG.warnv("MySQL backend sent no handshake");
                closeQuietly(client);
                closeQuietly(backend);
                return;
            }
            String authPlugin = extractMysqlAuthPlugin(handshake);
            byte[] masterResponse = rewriteHandshakeCredentials(
                    clientResponseRaw, parsed, masterUsername,
                    scramblePassword(masterPassword, extractMysqlNonce(handshake), authPlugin),
                    authPlugin);
            // The fresh connection has seen nothing yet: it expects this response at sequence 1 and
            // answers at 2, while the client expects that answer at nextSequence.
            masterResponse[3] = 1;
            backendOut.write(masterResponse);
            backendOut.flush();

            byte[] verdict = relayConnectionPhase(
                    clientIn, clientOut, backendIn, backendOut, nextSequence - 2);
            if (verdict == null) {
                closeQuietly(client);
                closeQuietly(backend);
                return;
            }
            clientOut.write(verdict);
            clientOut.flush();
            client.setSoTimeout(0);
            backend.setSoTimeout(0);
        } catch (Exception e) {
            LOG.warnv("MySQL IAM auth against the backend failed: {0}", e.getMessage());
            closeQuietly(client);
            closeQuietly(backend);
            return;
        }
        bridge(client, backend);
    }

    /** mysql_clear_password sends the password NUL-terminated. */
    private static String clearPassword(byte[] authData) {
        int length = authData.length;
        if (length > 0 && authData[length - 1] == 0) {
            length--;
        }
        return new String(authData, 0, length, StandardCharsets.UTF_8);
    }

    private static boolean isIamToken(String password) {
        return password.contains("X-Amz-Signature");
    }

    private static boolean isAccessDenied(byte[] raw) {
        return raw.length >= 7 && (raw[4] & 0xFF) == ERR_PACKET_MARKER
                && ((raw[5] & 0xFF) | ((raw[6] & 0xFF) << 8)) == ER_ACCESS_DENIED_ERROR;
    }

    /** AuthSwitchRequest: marker, NUL-terminated plugin name, then the plugin's challenge data. */
    private static byte[] buildAuthSwitchRequest(String authPlugin, byte[] nonce) {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        baos.write(AUTH_SWITCH_REQUEST_MARKER);
        baos.writeBytes(authPlugin.getBytes(StandardCharsets.UTF_8));
        baos.write(0);
        baos.writeBytes(nonce);
        baos.write(0);
        return baos.toByteArray();
    }

    // ── TLS upgrade ───────────────────────────────────────────────────────────

    /**
     * An {@code SSLRequest} is a {@code HandshakeResponse41}-shaped packet truncated to just its
     * fixed 32-byte prefix (capabilities, max-packet-size, charset, reserved) — a full response
     * always has at least a null-terminated username after that prefix. {@code CLIENT_SSL} being
     * set distinguishes it from a (protocol-invalid) truncated real response.
     */
    private static boolean isSslRequest(byte[] raw) {
        if (raw.length != 4 + SSL_REQUEST_PAYLOAD_LENGTH) {
            return false;
        }
        int caps = (raw[4] & 0xFF) | ((raw[5] & 0xFF) << 8)
                | ((raw[6] & 0xFF) << 16) | ((raw[7] & 0xFF) << 24);
        return (caps & CLIENT_SSL) != 0;
    }

    private static SSLSocket upgradeToTls(Socket socket, RdsProxyTlsCertificates tlsCertificates)
            throws IOException {
        try {
            SSLSocket sslSocket = (SSLSocket) tlsCertificates.sslContext().getSocketFactory()
                    .createSocket(socket, socket.getInetAddress().getHostAddress(), socket.getPort(), true);
            sslSocket.setUseClientMode(false);
            sslSocket.startHandshake();
            return sslSocket;
        } catch (Exception e) {
            throw new IOException("Unable to negotiate MySQL SSL", e);
        }
    }

    /**
     * Clears the {@code CLIENT_SSL} bit in a raw HandshakeResponse41 packet's capability flags.
     * The client capabilities are the first 4 payload bytes, little-endian; {@code CLIENT_SSL}
     * (0x0800) lives in the second byte.
     */
    private static void clearClientSslCapability(byte[] raw) {
        raw[5] &= (byte) ~(CLIENT_SSL >> 8);
    }

    /**
     * Sets the {@code CLIENT_SSL} bit in a raw Handshake V10 packet's capability flags, so the
     * client is offered TLS even when the (plaintext) backend didn't advertise it. Walks the same
     * fields as {@link #extractMysqlNonce(byte[])} up to the low 2 bytes of the capability flags.
     */
    private static void forceClientSslCapability(byte[] raw) {
        int i = 4 + 1; // skip 4-byte header + protocol version byte

        while (i < raw.length && raw[i] != 0) {
            i++;
        }
        i++; // skip null-terminated server version

        i += 4; // connection id
        i += 8; // auth-plugin-data part 1
        i++; // filler byte

        // Capability flags lower 2 bytes, little-endian: CLIENT_SSL (0x0800) lives in the high
        // byte of this field.
        if (i + 1 < raw.length) {
            raw[i + 1] |= (byte) (CLIENT_SSL >> 8);
        }
    }

    // ── Nonce extraction ──────────────────────────────────────────────────────

    /**
     * Extracts the 20-byte auth nonce from a raw MySQL Handshake V10 packet.
     * {@code raw[0..3]} is the 4-byte packet header; the payload starts at {@code raw[4]}.
     */
    private static byte[] extractMysqlNonce(byte[] raw) {
        int i = 4 + 1; // skip 4-byte header + protocol version byte

        // skip null-terminated server version
        while (i < raw.length && raw[i] != 0) {
            i++;
        }
        i++; // skip null

        // skip connection ID (4 bytes LE)
        i += 4;

        byte[] nonce = new byte[20];

        // auth-plugin-data part 1 (8 bytes)
        if (i + 8 <= raw.length) {
            System.arraycopy(raw, i, nonce, 0, 8);
        }
        i += 8;
        i++; // skip filler byte

        // capability flags lower 2 bytes + charset + status flags + capability upper 2 bytes
        i += 7;

        // length of auth-plugin-data
        int authDataLen = (i < raw.length) ? (raw[i] & 0xFF) : 0;
        i++;

        // reserved 10 bytes
        i += 10;

        // auth-plugin-data part 2: max(13, authDataLen - 8) bytes, last byte is null
        int part2Len = Math.max(13, authDataLen - 8);
        int toCopy = Math.min(12, Math.min(part2Len - 1, raw.length - i));
        if (toCopy > 0) {
            System.arraycopy(raw, i, nonce, 8, toCopy);
        }

        return nonce;
    }

    private static String extractMysqlAuthPlugin(byte[] raw) {
        int i = 4 + 1;
        while (i < raw.length && raw[i] != 0) {
            i++;
        }
        i++;
        i += 4 + 8 + 1;
        if (i + 2 > raw.length) {
            return MYSQL_NATIVE_PASSWORD;
        }
        int capabilities = (raw[i] & 0xFF) | ((raw[i + 1] & 0xFF) << 8);
        i += 2;
        if (i + 5 > raw.length) {
            return MYSQL_NATIVE_PASSWORD;
        }
        i += 1 + 2;
        capabilities |= ((raw[i] & 0xFF) | ((raw[i + 1] & 0xFF) << 8)) << 16;
        i += 2;
        int authDataLen = raw[i] & 0xFF;
        i += 1 + 10;
        if ((capabilities & CLIENT_PLUGIN_AUTH) == 0) {
            return MYSQL_NATIVE_PASSWORD;
        }
        i += Math.max(13, authDataLen - 8);
        if (i >= raw.length) {
            return MYSQL_NATIVE_PASSWORD;
        }
        int pluginStart = i;
        while (i < raw.length && raw[i] != 0) {
            i++;
        }
        return i == pluginStart
                ? MYSQL_NATIVE_PASSWORD
                : new String(raw, pluginStart, i - pluginStart, StandardCharsets.UTF_8);
    }

    // ── Parse HandshakeResponse41 ─────────────────────────────────────────────

    /**
     * Parses a HandshakeResponse41 payload: capabilities, max-packet-size, charset, 23 reserved
     * bytes, username and auth-response, then (each only when its capability flag is set) the
     * database ({@code CLIENT_CONNECT_WITH_DB}), the client plugin name ({@code CLIENT_PLUGIN_AUTH}),
     * connection attributes and a zstd compression level. Records where the auth-response and plugin
     * name fields sit so {@link #rewriteHandshakeCredentials} can copy everything else verbatim.
     */
    private static ParsedHandshakeResponse parseHandshakeResponse(byte[] data) {
        int i = 0;
        // 4 bytes: capabilities
        int caps = (data[i] & 0xFF) | ((data[i + 1] & 0xFF) << 8)
                | ((data[i + 2] & 0xFF) << 16) | ((data[i + 3] & 0xFF) << 24);
        i += 4;
        // 4 bytes: max packet size
        i += 4;
        // 1 byte: character set
        i += 1;
        // 23 reserved bytes
        i += 23;

        // null-terminated username
        int nameStart = i;
        while (i < data.length && data[i] != 0) {
            i++;
        }
        String username = new String(data, nameStart, i - nameStart, StandardCharsets.UTF_8);
        i++; // skip null

        // auth-response
        byte[] authData = new byte[0];
        if (i < data.length) {
            if ((caps & CLIENT_PLUGIN_AUTH_LENENC_CLIENT_DATA) != 0) {
                int[] consumed = {0};
                long authLen = readLenencInt(data, i, consumed);
                i += consumed[0];
                authData = Arrays.copyOfRange(data, i, (int) Math.min(data.length, i + authLen));
                i += authData.length;
            } else if ((caps & CLIENT_SECURE_CONNECTION) != 0) {
                int authLen = data[i] & 0xFF;
                i++;
                authData = Arrays.copyOfRange(data, i, Math.min(data.length, i + authLen));
                i += authData.length;
            } else {
                int passStart = i;
                while (i < data.length && data[i] != 0) {
                    i++;
                }
                authData = Arrays.copyOfRange(data, passStart, i);
                i++; // skip null
            }
        }
        i = Math.min(i, data.length);
        int authEnd = i;

        // null-terminated database
        if ((caps & CLIENT_CONNECT_WITH_DB) != 0) {
            while (i < data.length && data[i] != 0) {
                i++;
            }
            i = Math.min(i + 1, data.length);
        }

        // null-terminated client plugin name
        int pluginStart = i;
        String authPlugin = null;
        if ((caps & CLIENT_PLUGIN_AUTH) != 0) {
            while (i < data.length && data[i] != 0) {
                i++;
            }
            if (i > pluginStart) {
                authPlugin = new String(data, pluginStart, i - pluginStart, StandardCharsets.UTF_8);
            }
            i = Math.min(i + 1, data.length);
        }

        return new ParsedHandshakeResponse(
                caps, username, authData, authPlugin, authEnd, pluginStart, i);
    }

    /**
     * @param authEnd     payload offset just past the auth-response field
     * @param pluginStart payload offset of the client plugin name field (where it would be if absent)
     * @param pluginEnd   payload offset just past the client plugin name field
     */
    private record ParsedHandshakeResponse(int capabilities, String username, byte[] authData,
                                           String authPlugin, int authEnd, int pluginStart,
                                           int pluginEnd) {}

    /**
     * Rebuilds a HandshakeResponse41 with other credentials and client plugin name, copying the
     * database, connection attributes and any trailing bytes verbatim and in place.
     */
    private static byte[] rewriteHandshakeCredentials(byte[] raw, ParsedHandshakeResponse parsed,
                                                       String username, byte[] authData,
                                                       String authPlugin) {
        int capabilities = parsed.capabilities();
        ByteArrayOutputStream rewritten = new ByteArrayOutputStream(raw.length);
        // Capabilities, max-packet-size, charset and reserved bytes: the prefix an SSLRequest carries.
        rewritten.write(raw, 4, SSL_REQUEST_PAYLOAD_LENGTH);
        rewritten.writeBytes(username.getBytes(StandardCharsets.UTF_8));
        rewritten.write(0);
        if ((capabilities & CLIENT_PLUGIN_AUTH_LENENC_CLIENT_DATA) != 0) {
            writeLenencInt(rewritten, authData.length);
            rewritten.writeBytes(authData);
        } else if ((capabilities & CLIENT_SECURE_CONNECTION) != 0) {
            rewritten.write(authData.length);
            rewritten.writeBytes(authData);
        } else {
            rewritten.writeBytes(authData);
            rewritten.write(0);
        }
        // The database, if any, sits between the auth-response and the plugin name.
        rewritten.write(raw, 4 + parsed.authEnd(), parsed.pluginStart() - parsed.authEnd());
        if ((capabilities & CLIENT_PLUGIN_AUTH) != 0) {
            rewritten.writeBytes(authPlugin.getBytes(StandardCharsets.UTF_8));
            rewritten.write(0);
        }
        // Connection attributes and zstd compression level.
        rewritten.write(raw, 4 + parsed.pluginEnd(), raw.length - 4 - parsed.pluginEnd());

        byte[] result = new byte[4 + rewritten.size()];
        int length = rewritten.size();
        result[0] = (byte) (length & 0xFF);
        result[1] = (byte) ((length >> 8) & 0xFF);
        result[2] = (byte) ((length >> 16) & 0xFF);
        result[3] = raw[3];
        System.arraycopy(rewritten.toByteArray(), 0, result, 4, length);
        return result;
    }

    private static void writeLenencInt(ByteArrayOutputStream out, int value) {
        if (value < 0xFB) {
            out.write(value);
        } else if (value <= 0xFFFF) {
            out.write(0xFC);
            out.write(value & 0xFF);
            out.write((value >> 8) & 0xFF);
        } else {
            out.write(0xFD);
            out.write(value & 0xFF);
            out.write((value >> 8) & 0xFF);
            out.write((value >> 16) & 0xFF);
        }
    }

    private static long readLenencInt(byte[] data, int offset, int[] consumed) {
        int first = data[offset] & 0xFF;
        if (first < 0xFB) {
            consumed[0] = 1;
            return first;
        }
        if (first == 0xFC) {
            consumed[0] = 3;
            return (data[offset + 1] & 0xFF) | ((data[offset + 2] & 0xFF) << 8);
        }
        if (first == 0xFD) {
            consumed[0] = 4;
            return (data[offset + 1] & 0xFF) | ((data[offset + 2] & 0xFF) << 8)
                    | ((data[offset + 3] & 0xFF) << 16);
        }
        consumed[0] = 9;
        long result = 0;
        for (int i = 0; i < 8; i++) {
            result |= ((long) (data[offset + 1 + i] & 0xFF)) << (8 * i);
        }
        return result;
    }

    // ── Scramble ──────────────────────────────────────────────────────────────

    private static byte[] scramblePassword(String password, byte[] nonce, String authPlugin)
            throws Exception {
        return switch (authPlugin) {
            case CACHING_SHA2_PASSWORD -> scrambleCachingSha2Password(password, nonce);
            case MYSQL_NATIVE_PASSWORD -> scrambleNativePassword(password, nonce);
            default -> throw new IllegalArgumentException(
                    "Unsupported MySQL authentication plugin: " + authPlugin);
        };
    }

    private static byte[] scrambleNativePassword(String password, byte[] nonce) throws Exception {
        if (password == null || password.isEmpty()) {
            return new byte[0];
        }
        MessageDigest sha1 = MessageDigest.getInstance("SHA-1");
        byte[] hash1 = sha1.digest(password.getBytes(StandardCharsets.UTF_8));
        sha1.reset();
        byte[] hash2 = sha1.digest(hash1);
        sha1.reset();
        sha1.update(nonce);
        sha1.update(hash2);
        byte[] hash3 = sha1.digest();

        byte[] result = new byte[20];
        for (int i = 0; i < 20; i++) {
            result[i] = (byte) (hash1[i] ^ hash3[i]);
        }
        return result;
    }

    private static byte[] scrambleCachingSha2Password(String password, byte[] nonce)
            throws Exception {
        if (password == null || password.isEmpty()) {
            return new byte[0];
        }
        MessageDigest sha256 = MessageDigest.getInstance("SHA-256");
        byte[] hash1 = sha256.digest(password.getBytes(StandardCharsets.UTF_8));
        sha256.reset();
        byte[] hash2 = sha256.digest(hash1);
        sha256.reset();
        sha256.update(hash2);
        sha256.update(nonce);
        byte[] hash3 = sha256.digest();

        byte[] result = new byte[32];
        for (int i = 0; i < result.length; i++) {
            result[i] = (byte) (hash1[i] ^ hash3[i]);
        }
        return result;
    }

    // ── MySQL packet helpers ──────────────────────────────────────────────────

    /**
     * Reads a MySQL packet and returns the raw bytes including the 4-byte header.
     */
    private static byte[] readMysqlPacketRaw(InputStream in) throws IOException {
        int b0 = in.read();
        int b1 = in.read();
        int b2 = in.read();
        int seq = in.read();
        if ((b0 | b1 | b2 | seq) < 0) {
            return null;
        }
        int length = b0 | (b1 << 8) | (b2 << 16);
        if (length > MAX_PACKET_PAYLOAD) {
            throw new IOException("MySQL packet exceeds protocol limit");
        }
        byte[] raw = new byte[4 + length];
        raw[0] = (byte) b0;
        raw[1] = (byte) b1;
        raw[2] = (byte) b2;
        raw[3] = (byte) seq;
        int offset = 4;
        while (offset < raw.length) {
            int n = in.read(raw, offset, raw.length - offset);
            if (n < 0) {
                throw new EOFException("Connection closed while reading MySQL packet");
            }
            offset += n;
        }
        return raw;
    }

    private static void writeMysqlPacket(OutputStream out, int seq, byte[] payload) throws IOException {
        int len = payload.length;
        out.write(len & 0xFF);
        out.write((len >> 8) & 0xFF);
        out.write((len >> 16) & 0xFF);
        out.write(seq & 0xFF);
        out.write(payload);
    }

    @FunctionalInterface
    public interface IamUserChecker {
        boolean isIamUser(String username);
    }

    private static byte[] buildErrorPacket(int errorCode, String message) throws IOException {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        baos.write(0xFF); // ERR marker
        baos.write(errorCode & 0xFF);
        baos.write((errorCode >> 8) & 0xFF);
        baos.write('#');
        baos.write("HY000".getBytes(StandardCharsets.UTF_8));
        baos.write(message.getBytes(StandardCharsets.UTF_8));
        return baos.toByteArray();
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

        Thread t1 = Thread.ofVirtual().name("rds-mysql-c2b")
                .start(() -> relayClientCommands(clientIn, backendOut));
        Thread t2 = Thread.ofVirtual().name("rds-mysql-b2c")
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

    private static void relayClientCommands(InputStream from, OutputStream to) {
        try {
            byte[] raw;
            while ((raw = readMysqlPacketRaw(from)) != null) {
                int length = (raw[0] & 0xFF) | ((raw[1] & 0xFF) << 8) | ((raw[2] & 0xFF) << 16);
                byte[] rewritten = length == MAX_PACKET_PAYLOAD || raw.length < 5
                        || (raw[4] & 0xFF) != COM_QUERY
                        ? null
                        : rewriteIamAuthPlugin(Arrays.copyOfRange(raw, 4, raw.length));
                if (rewritten == null) {
                    to.write(raw);
                } else {
                    writeMysqlPacket(to, raw[3] & 0xFF, rewritten);
                }
                to.flush();
            }
        } catch (IOException ignored) {}
    }

    static byte[] rewriteIamAuthPlugin(byte[] payload) {
        String text = new String(payload, StandardCharsets.ISO_8859_1);
        Matcher matcher = IAM_AUTH_PLUGIN.matcher(text);
        if (!matcher.find()) {
            return null;
        }
        return matcher.replaceAll(Matcher.quoteReplacement(IAM_AUTH_PLUGIN_REPLACEMENT))
                .getBytes(StandardCharsets.ISO_8859_1);
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

    static void closeQuietly(Socket s) {
        try { s.close(); } catch (IOException ignored) {}
    }
}
