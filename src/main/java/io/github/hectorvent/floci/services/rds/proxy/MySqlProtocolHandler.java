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
 */
public class MySqlProtocolHandler {

    private static final Logger LOG = Logger.getLogger(MySqlProtocolHandler.class);

    private static final int CLIENT_SSL = 0x0800;
    private static final int CLIENT_PLUGIN_AUTH = 0x0008_0000;
    private static final int CLIENT_PLUGIN_AUTH_LENENC_CLIENT_DATA = 0x200000;
    private static final int CLIENT_SECURE_CONNECTION = 0x8000;
    private static final String MYSQL_NATIVE_PASSWORD = "mysql_native_password";
    private static final String CACHING_SHA2_PASSWORD = "caching_sha2_password";

    // First payload byte of the packet types that can appear during the connection phase.
    private static final int OK_PACKET_MARKER = 0x00;
    private static final int ERR_PACKET_MARKER = 0xFF;
    private static final int AUTH_MORE_DATA_MARKER = 0x01;

    // Second payload byte of an AuthMoreData packet under caching_sha2_password: fast_auth_success
    // means the backend is about to send the terminal OK with no further input from the client.
    private static final int CACHING_SHA2_FAST_AUTH_SUCCESS = 0x03;

    // 4-byte capabilities + 4-byte max-packet-size + 1-byte charset + 23 reserved bytes.
    private static final int SSL_REQUEST_PAYLOAD_LENGTH = 32;

    public static void handleAuth(Socket client, Socket backend,
                                  String masterUsername, String masterPassword,
                                  boolean iamEnabled, RdsSigV4Validator sigV4,
                                  RdsProxyTlsCertificates tlsCertificates,
                                  PasswordValidator passwordValidator) throws IOException {

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
        // Master user: validate the scramble locally against the known master password.
        // Non-master users: pass through — the backend validates their scramble directly.
        // IAM tokens: validate SigV4, then connect to backend as master.
        byte[] clientPayload = Arrays.copyOfRange(clientResponseRaw, 4, clientResponseRaw.length);
        String[] parsed = parseHandshakeResponse(clientPayload);
        String clientUsername = parsed[0];
        byte[] clientAuthData = parsed[1] != null
                ? parsed[1].getBytes(StandardCharsets.ISO_8859_1) : new byte[0];

        boolean valid;
        try {
            if (masterUsername.equals(clientUsername)) {
                byte[] expected = scramblePassword(
                        masterPassword, backendNonce, backendAuthPlugin);
                valid = Arrays.equals(expected, clientAuthData);
            } else {
                // Non-master user: defer to backend — it knows their password.
                valid = true;
            }
        } catch (Exception e) {
            LOG.warnv("MySQL auth error for instance: {0}", e.getMessage());
            valid = false;
        }

        if (!valid) {
            byte[] err = buildErrorPacket(1045,
                    "Access denied for user '" + clientUsername + "'@'localhost' (using password: YES)");
            writeMysqlPacket(clientOut, 2, err);
            clientOut.flush();
            closeQuietly(client);
            closeQuietly(backend);
            return;
        }

        // Phase 5: Forward client's HandshakeResponse to backend, then relay the rest of the
        // connection phase (renumbering if a TLS upgrade shifted the shared sequence counter)
        // before handing off to the plain byte-for-byte bridge.
        backendOut.write(clientResponseRaw);
        backendOut.flush();

        if (sequenceOffset != 0
                && !relayConnectionPhase(clientIn, clientOut, backendIn, backendOut, sequenceOffset)) {
            closeQuietly(client);
            closeQuietly(backend);
            return;
        }

        bridge(client, backend);
    }

    /**
     * Relays packets between client and backend for the remainder of the connection phase,
     * shifting each sequence number by {@code offset} to correct for a packet the backend never
     * saw (see {@link #handleAuth}). Stops once the terminal OK/ERR packet reaches the client —
     * at that point both sides independently reset their sequence counters for the next command,
     * so the offset no longer applies and {@link #bridge} can take over untouched.
     *
     * @return {@code false} if either side closed the connection mid-exchange
     */
    private static boolean relayConnectionPhase(InputStream clientIn, OutputStream clientOut,
                                                InputStream backendIn, OutputStream backendOut,
                                                int offset) throws IOException {
        while (true) {
            byte[] backendRaw = readMysqlPacketRaw(backendIn);
            if (backendRaw == null || backendRaw.length < 5) {
                return false;
            }
            backendRaw[3] = (byte) (backendRaw[3] + offset);
            clientOut.write(backendRaw);
            clientOut.flush();

            int marker = backendRaw[4] & 0xFF;
            if (marker == OK_PACKET_MARKER || marker == ERR_PACKET_MARKER) {
                return true;
            }
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
                return false;
            }
            clientRaw[3] = (byte) (clientRaw[3] - offset);
            backendOut.write(clientRaw);
            backendOut.flush();
        }
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

    private static String[] parseHandshakeResponse(byte[] data) {
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
        String password = "";
        if (i < data.length) {
            byte[] authData;
            if ((caps & CLIENT_PLUGIN_AUTH_LENENC_CLIENT_DATA) != 0) {
                int[] consumed = {0};
                long authLen = readLenencInt(data, i, consumed);
                i += consumed[0];
                authData = new byte[(int) authLen];
                if (i + authData.length <= data.length) {
                    System.arraycopy(data, i, authData, 0, authData.length);
                }
            } else if ((caps & CLIENT_SECURE_CONNECTION) != 0) {
                int authLen = data[i] & 0xFF;
                i++;
                authData = new byte[authLen];
                if (i + authLen <= data.length) {
                    System.arraycopy(data, i, authData, 0, authLen);
                }
            } else {
                int passStart = i;
                while (i < data.length && data[i] != 0) {
                    i++;
                }
                authData = new byte[i - passStart];
                System.arraycopy(data, passStart, authData, 0, authData.length);
            }
            // Preserve raw bytes using ISO-8859-1 so binary scramble data survives
            password = new String(authData, StandardCharsets.ISO_8859_1);
        }

        return new String[]{username, password};
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
                .start(() -> relay(clientIn, backendOut));
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
