package io.floci.aws.core.common;

import javax.crypto.Mac;
import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.PBEKeySpec;
import javax.crypto.spec.SecretKeySpec;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.HexFormat;
import java.util.Optional;

/**
 * Answers the password challenge of a PostgreSQL server Floci logs in to as a client, such as the
 * RDS proxy's backend or an Aurora DSQL cluster's external database: cleartext, MD5 and
 * SCRAM-SHA-256 (RFC 5802 and 7677, without channel binding), or SCRAM-SHA-256 alone where the
 * server is known to store SCRAM verifiers. One instance serves one connection, as the SCRAM
 * exchange keeps its state between the messages.
 */
public final class BackendAuthenticator {

    private static final String SCRAM = "SCRAM-SHA-256";
    private static final int NONCE_BYTES = 18;
    /** PostgreSQL stores 4096; a server asking for far more would only make Floci burn CPU. */
    static final int MAX_ITERATIONS = 1_000_000;
    /**
     * The largest server-first or server-final SCRAM message read, in bytes. PostgreSQL's are under
     * 200; the limit keeps a peer from making Floci copy and split megabytes before it logs in.
     */
    static final int MAX_SCRAM_MESSAGE = 4096;
    /** The largest decoded salt taken, in bytes. PostgreSQL's are 16. */
    static final int MAX_SALT_BYTES = 1024;

    private final String user;
    private final String password;
    private final String clientNonce;
    private final String scramUserName;
    private final boolean scramOnly;

    /** Whether a request for a password, cleartext, MD5 or SASL, was answered on this connection. */
    private boolean started;
    private String clientFirstBare;
    private byte[] serverSignature;
    private boolean scramVerified;

    public BackendAuthenticator(String user, String password) {
        this(user, password, false);
    }

    /**
     * With {@code scramOnly}, only SCRAM-SHA-256 is answered: a cleartext or MD5 request is
     * refused, as a server that stores SCRAM verifiers never asks for them and a peer that does is
     * not that server. A login accepted before any request (trust) discloses nothing and is
     * accepted; one accepted after SASL started but before its signature was verified is not.
     */
    public BackendAuthenticator(String user, String password, boolean scramOnly) {
        this(user, password, newNonce(), "", scramOnly);
    }

    /** For the RFC 7677 vector, whose client-first message names the user and fixes the nonce. */
    BackendAuthenticator(String user, String password, String clientNonce, String scramUserName) {
        this(user, password, clientNonce, scramUserName, false);
    }

    BackendAuthenticator(String user, String password, String clientNonce, String scramUserName,
                         boolean scramOnly) {
        this.user = user;
        this.password = password;
        this.clientNonce = clientNonce;
        this.scramUserName = scramUserName;
        this.scramOnly = scramOnly;
    }

    private static String newNonce() {
        SecureRandom random = new SecureRandom();
        byte[] bytes = new byte[NONCE_BYTES];
        random.nextBytes(bytes);
        return Base64.getEncoder().encodeToString(bytes);
    }

    /**
     * The answer to an authentication request ({@code R}), or empty when none is due. An
     * AuthenticationOk that comes while a SCRAM exchange has not yet proved the server's signature
     * is refused, as the server would then be one that never knew the password.
     */
    public Optional<PostgresWire.Message> answer(PostgresWire.Message authRequest) throws IOException {
        byte[] body = authRequest.body();
        if (authRequest.type() != 'R' || body.length < 4) {
            throw new IOException("unexpected backend authentication message");
        }
        int code = PostgresWire.int32(body, 0);
        return switch (code) {
            case 0 -> {
                // Before any request it is a trusting server, which got no password; once SASL has
                // started, only a verified server signature lets the login through.
                if (clientFirstBare != null && !scramVerified) {
                    throw new IOException("backend accepted the login before proving its SCRAM signature");
                }
                yield Optional.empty();
            }
            case 3 -> {
                refusePasswordRequest("a cleartext password");
                yield Optional.of(new PostgresWire.Message((byte) 'p', PostgresWire.cStringBytes(password)));
            }
            case 5 -> {
                refusePasswordRequest("an MD5 password");
                yield Optional.of(md5(body));
            }
            case 10 -> {
                if (started) {
                    // A second exchange would let the first one's verified signature vouch for it.
                    throw new IOException("backend starts a second authentication exchange");
                }
                PostgresWire.Message initial = saslInitial(body);
                started = true;
                yield Optional.of(initial);
            }
            case 11 -> Optional.of(saslContinue(body));
            case 12 -> {
                saslFinal(body);
                yield Optional.empty();
            }
            default -> throw new IOException("unsupported backend authentication method: " + code);
        };
    }

    /**
     * Refuses a cleartext or MD5 request that would be a downgrade. In SCRAM-only mode, any such
     * request is. In every mode, one that comes once an authentication has started is: a server
     * asks for one method, so a peer that switches mid-SCRAM wants the password without proving it
     * knows it.
     */
    private void refusePasswordRequest(String method) throws IOException {
        if (scramOnly) {
            throw new IOException("backend asks for " + method + " where only SCRAM-SHA-256 is accepted");
        }
        if (started) {
            throw new IOException("backend asks for " + method + " after the authentication started");
        }
        started = true;
    }

    private PostgresWire.Message md5(byte[] body) throws IOException {
        if (body.length < 8) {
            throw new IOException("malformed backend MD5 authentication request");
        }
        MessageDigest md5 = Digests.md5Digest();
        md5.update(password.getBytes(StandardCharsets.UTF_8));
        md5.update(user.getBytes(StandardCharsets.UTF_8));
        String inner = HexFormat.of().formatHex(md5.digest());
        md5.update(inner.getBytes(StandardCharsets.US_ASCII));
        md5.update(body, 4, 4);
        String hashed = "md5" + HexFormat.of().formatHex(md5.digest());
        return new PostgresWire.Message((byte) 'p', PostgresWire.cStringBytes(hashed));
    }

    private PostgresWire.Message saslInitial(byte[] body) throws IOException {
        boolean offered = false;
        int offset = 4;
        while (offset < body.length && body[offset] != 0) {
            PostgresWire.CString mechanism = PostgresWire.cString(body, offset);
            offered |= SCRAM.equals(mechanism.value());
            offset = mechanism.next();
        }
        if (!offered) {
            throw new IOException("backend offers no supported SASL mechanism");
        }
        clientFirstBare = "n=" + scramUserName + ",r=" + clientNonce;
        byte[] clientFirst = ("n,," + clientFirstBare).getBytes(StandardCharsets.UTF_8);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.writeBytes(PostgresWire.cStringBytes(SCRAM));
        out.writeBytes(PostgresWire.int32(clientFirst.length));
        out.writeBytes(clientFirst);
        return new PostgresWire.Message((byte) 'p', out.toByteArray());
    }

    private PostgresWire.Message saslContinue(byte[] body) throws IOException {
        if (clientFirstBare == null || serverSignature != null) {
            throw new IOException("unexpected SASL continue from the backend");
        }
        String serverFirst = scramMessage(body);
        String nonce = null;
        byte[] salt = null;
        Integer iterations = null;
        try {
            for (String attribute : serverFirst.split(",")) {
                if (attribute.startsWith("m=")) {
                    // RFC 5802, section 5.1: an extension the client does not support must fail it.
                    throw new IOException("backend SCRAM message asks for a mandatory extension");
                }
                if (attribute.startsWith("r=")) {
                    requireFirst(nonce);
                    nonce = attribute.substring(2);
                } else if (attribute.startsWith("s=")) {
                    requireFirst(salt);
                    salt = decodeSalt(attribute.substring(2));
                } else if (attribute.startsWith("i=")) {
                    requireFirst(iterations);
                    iterations = Integer.parseInt(attribute.substring(2));
                }
            }
        } catch (IllegalArgumentException e) {
            throw new IOException("malformed SCRAM message from the backend", e);
        }
        if (nonce == null || salt == null || iterations == null || iterations <= 0) {
            throw new IOException("malformed SCRAM message from the backend");
        }
        if (salt.length == 0) {
            // PBKDF2 refuses an empty salt with an unchecked exception; PostgreSQL never sends one.
            throw new IOException("backend SCRAM salt is empty");
        }
        if (iterations > MAX_ITERATIONS) {
            throw new IOException("backend asks for " + iterations + " SCRAM iterations, more than "
                    + MAX_ITERATIONS);
        }
        // The server appends its own part (RFC 5802, section 5.1); the client's nonce alone is not one.
        if (nonce.length() <= clientNonce.length() || !nonce.startsWith(clientNonce)) {
            throw new IOException("backend SCRAM nonce does not extend the client nonce");
        }
        String withoutProof = "c=biws,r=" + nonce;
        byte[] authMessage = (clientFirstBare + "," + serverFirst + "," + withoutProof)
                .getBytes(StandardCharsets.UTF_8);
        try {
            byte[] salted = saltedPassword(password, salt, iterations);
            byte[] clientKey = hmac(salted, "Client Key".getBytes(StandardCharsets.UTF_8));
            byte[] storedKey = Digests.sha256(clientKey);
            byte[] clientSignature = hmac(storedKey, authMessage);
            byte[] proof = new byte[clientKey.length];
            for (int i = 0; i < proof.length; i++) {
                proof[i] = (byte) (clientKey[i] ^ clientSignature[i]);
            }
            byte[] serverKey = hmac(salted, "Server Key".getBytes(StandardCharsets.UTF_8));
            serverSignature = hmac(serverKey, authMessage);
            String clientFinal = withoutProof + ",p=" + Base64.getEncoder().encodeToString(proof);
            return new PostgresWire.Message((byte) 'p', clientFinal.getBytes(StandardCharsets.UTF_8));
        } catch (GeneralSecurityException e) {
            throw new IOException("SCRAM-SHA-256 is not available", e);
        }
    }

    /** The SCRAM message of a SASL continue or final, refused when larger than {@link #MAX_SCRAM_MESSAGE}. */
    private static String scramMessage(byte[] body) throws IOException {
        int length = body.length - 4;
        if (length > MAX_SCRAM_MESSAGE) {
            throw new IOException("backend SCRAM message is " + length + " bytes, more than " + MAX_SCRAM_MESSAGE);
        }
        return new String(body, 4, length, StandardCharsets.UTF_8);
    }

    /**
     * The salt, refused when longer than {@link #MAX_SALT_BYTES}: by its encoded length before it
     * is decoded, then exactly, as padding lets two lengths share one encoded length.
     */
    private static byte[] decodeSalt(String encoded) throws IOException {
        if (encoded.length() > (MAX_SALT_BYTES + 2) / 3 * 4) {
            throw new IOException("backend SCRAM salt is longer than " + MAX_SALT_BYTES + " bytes");
        }
        byte[] salt = Base64.getDecoder().decode(encoded);
        if (salt.length > MAX_SALT_BYTES) {
            throw new IOException("backend SCRAM salt is longer than " + MAX_SALT_BYTES + " bytes");
        }
        return salt;
    }

    /** A server-first attribute given twice makes the message malformed. */
    private static void requireFirst(Object earlier) throws IOException {
        if (earlier != null) {
            throw new IOException("malformed SCRAM message from the backend");
        }
    }

    private void saslFinal(byte[] body) throws IOException {
        String serverFinal = scramMessage(body);
        if (scramVerified) {
            throw new IOException("unexpected SASL final from the backend");
        }
        if (serverSignature == null || !serverFinal.startsWith("v=")) {
            throw new IOException("backend server signature mismatch");
        }
        byte[] received;
        try {
            received = Base64.getDecoder().decode(serverFinal.substring(2));
        } catch (IllegalArgumentException e) {
            throw new IOException("backend server signature mismatch", e);
        }
        if (!MessageDigest.isEqual(serverSignature, received)) {
            throw new IOException("backend server signature mismatch");
        }
        scramVerified = true;
    }

    /**
     * SCRAM's SaltedPassword: PBKDF2 with HMAC-SHA256 (RFC 5802, section 3) of the password after
     * SASLprep, as PostgreSQL derives it both when it stores a verifier and when it logs in.
     */
    public static byte[] saltedPassword(String password, byte[] salt, int iterations) throws GeneralSecurityException {
        PBEKeySpec spec = new PBEKeySpec(SaslPrep.prepare(password).toCharArray(), salt, iterations, 256);
        try {
            return SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).getEncoded();
        } finally {
            spec.clearPassword();
        }
    }

    public static byte[] hmac(byte[] key, byte[] data) throws GeneralSecurityException {
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(key, "HmacSHA256"));
        return mac.doFinal(data);
    }
}
