package com.floci.test;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.io.ByteArrayOutputStream;
import java.math.BigInteger;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.MessageDigest;
import java.security.Signature;
import java.security.interfaces.ECPublicKey;
import java.security.interfaces.RSAPublicKey;
import java.security.spec.ECGenParameterSpec;
import java.util.Arrays;
import java.util.Base64;
import java.util.UUID;

/**
 * A software passkey authenticator for tests, standing in for a browser and its authenticator:
 * it answers creation options with a {@code RegistrationResponseJSON} ({@code none} or
 * {@code packed} self attestation) and request options with an {@code AuthenticationResponseJSON}.
 */
final class WebAuthnTestAuthenticator {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final byte FLAG_UP = 0x01;
    private static final byte FLAG_UV = 0x04;
    private static final byte FLAG_AT = 0x40;

    private final String origin;
    private final KeyPair keyPair;
    private final long algorithm;
    private final String signatureAlgorithm;
    private final byte[] credentialId = UUID.randomUUID().toString().getBytes(StandardCharsets.UTF_8);
    private String relyingPartyIdOverride;
    private boolean userVerified = true;
    private boolean corruptAttestation;
    private long signCount;

    private WebAuthnTestAuthenticator(String origin, KeyPair keyPair, long algorithm, String signatureAlgorithm) {
        this.origin = origin;
        this.keyPair = keyPair;
        this.algorithm = algorithm;
        this.signatureAlgorithm = signatureAlgorithm;
    }

    static WebAuthnTestAuthenticator es256(String origin) throws Exception {
        KeyPairGenerator generator = KeyPairGenerator.getInstance("EC");
        generator.initialize(new ECGenParameterSpec("secp256r1"));
        return new WebAuthnTestAuthenticator(origin, generator.generateKeyPair(), -7, "SHA256withECDSA");
    }

    static WebAuthnTestAuthenticator es384(String origin) throws Exception {
        KeyPairGenerator generator = KeyPairGenerator.getInstance("EC");
        generator.initialize(new ECGenParameterSpec("secp384r1"));
        return new WebAuthnTestAuthenticator(origin, generator.generateKeyPair(), -35, "SHA384withECDSA");
    }

    static WebAuthnTestAuthenticator rs256(String origin) throws Exception {
        KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
        generator.initialize(2048);
        return new WebAuthnTestAuthenticator(origin, generator.generateKeyPair(), -257, "SHA256withRSA");
    }

    /** Claims {@code relyingPartyId} instead of the one the options name. */
    WebAuthnTestAuthenticator relyingPartyId(String relyingPartyId) {
        this.relyingPartyIdOverride = relyingPartyId;
        return this;
    }

    WebAuthnTestAuthenticator userVerified(boolean userVerified) {
        this.userVerified = userVerified;
        return this;
    }

    /** Signs a {@code packed} attestation statement over the wrong data. */
    WebAuthnTestAuthenticator corruptAttestation() {
        this.corruptAttestation = true;
        return this;
    }

    String credentialId() {
        return base64Url(credentialId);
    }

    ObjectNode register(JsonNode creationOptions, String format) throws Exception {
        String relyingPartyId = relyingPartyIdOverride != null
                ? relyingPartyIdOverride : creationOptions.path("rp").path("id").asText();
        byte[] clientData = clientData("webauthn.create", creationOptions.path("challenge").asText());
        ByteArrayOutputStream authData = new ByteArrayOutputStream();
        authData.write(sha256(relyingPartyId.getBytes(StandardCharsets.UTF_8)));
        authData.write(FLAG_UP | FLAG_AT | (userVerified ? FLAG_UV : 0));
        authData.write(ByteBuffer.allocate(4).putInt((int) signCount).array());
        authData.write(new byte[16]);
        authData.write(ByteBuffer.allocate(2).putShort((short) credentialId.length).array());
        authData.write(credentialId);
        authData.write(coseKey());
        byte[] authenticatorData = authData.toByteArray();

        Cbor statement = new Cbor();
        if ("packed".equals(format)) {
            byte[] signed = concat(authenticatorData, sha256(clientData));
            if (corruptAttestation) {
                signed[0] ^= 0x01;
            }
            statement.map(2).text("alg").integer(algorithm).text("sig").bytes(sign(signed));
        } else {
            statement.map(0);
        }
        byte[] attestationObject = new Cbor().map(3)
                .text("fmt").text(format)
                .text("attStmt").raw(statement.toByteArray())
                .text("authData").bytes(authenticatorData)
                .toByteArray();

        ObjectNode credential = JSON.createObjectNode();
        credential.put("id", credentialId());
        credential.put("rawId", credentialId());
        credential.put("type", "public-key");
        credential.put("authenticatorAttachment", "platform");
        credential.putObject("clientExtensionResults");
        ObjectNode response = credential.putObject("response");
        response.put("clientDataJSON", base64Url(clientData));
        response.put("attestationObject", base64Url(attestationObject));
        response.putArray("transports").add("internal").add("hybrid");
        return credential;
    }

    String authenticate(String requestOptionsJson) throws Exception {
        JsonNode options = JSON.readTree(requestOptionsJson);
        String relyingPartyId = relyingPartyIdOverride != null ? relyingPartyIdOverride : options.path("rpId").asText();
        byte[] clientData = clientData("webauthn.get", options.path("challenge").asText());
        signCount++;
        byte[] authenticatorData = concat(sha256(relyingPartyId.getBytes(StandardCharsets.UTF_8)),
                new byte[] {(byte) (FLAG_UP | (userVerified ? FLAG_UV : 0))},
                ByteBuffer.allocate(4).putInt((int) signCount).array());

        ObjectNode credential = JSON.createObjectNode();
        credential.put("id", credentialId());
        credential.put("rawId", credentialId());
        credential.put("type", "public-key");
        credential.put("authenticatorAttachment", "platform");
        credential.putObject("clientExtensionResults");
        ObjectNode response = credential.putObject("response");
        response.put("authenticatorData", base64Url(authenticatorData));
        response.put("clientDataJSON", base64Url(clientData));
        response.put("signature", base64Url(sign(concat(authenticatorData, sha256(clientData)))));
        return JSON.writeValueAsString(credential);
    }

    private byte[] clientData(String type, String challenge) throws Exception {
        ObjectNode clientData = JSON.createObjectNode();
        clientData.put("type", type);
        clientData.put("challenge", challenge);
        clientData.put("origin", origin);
        clientData.put("crossOrigin", false);
        return JSON.writeValueAsBytes(clientData);
    }

    private byte[] coseKey() {
        if (keyPair.getPublic() instanceof RSAPublicKey rsa) {
            return new Cbor().map(4)
                    .integer(1).integer(3)
                    .integer(3).integer(algorithm)
                    .integer(-1).bytes(unsigned(rsa.getModulus()))
                    .integer(-2).bytes(unsigned(rsa.getPublicExponent()))
                    .toByteArray();
        }
        ECPublicKey ec = (ECPublicKey) keyPair.getPublic();
        int size = algorithm == -35 ? 48 : 32;
        return new Cbor().map(5)
                .integer(1).integer(2)
                .integer(3).integer(algorithm)
                .integer(-1).integer(algorithm == -35 ? 2 : 1)
                .integer(-2).bytes(fixed(ec.getW().getAffineX(), size))
                .integer(-3).bytes(fixed(ec.getW().getAffineY(), size))
                .toByteArray();
    }

    private byte[] sign(byte[] data) throws Exception {
        Signature signature = Signature.getInstance(signatureAlgorithm);
        signature.initSign(keyPair.getPrivate());
        signature.update(data);
        return signature.sign();
    }

    private static byte[] sha256(byte[] data) throws Exception {
        return MessageDigest.getInstance("SHA-256").digest(data);
    }

    private static byte[] concat(byte[]... parts) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        for (byte[] part : parts) {
            out.writeBytes(part);
        }
        return out.toByteArray();
    }

    private static byte[] unsigned(BigInteger value) {
        byte[] bytes = value.toByteArray();
        return bytes[0] == 0 ? Arrays.copyOfRange(bytes, 1, bytes.length) : bytes;
    }

    private static byte[] fixed(BigInteger value, int size) {
        byte[] bytes = unsigned(value);
        byte[] padded = new byte[size];
        System.arraycopy(bytes, 0, padded, size - bytes.length, bytes.length);
        return padded;
    }

    static String base64Url(byte[] bytes) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    /** The few CBOR items an attestation object and a COSE key need. */
    private static final class Cbor {
        private final ByteArrayOutputStream out = new ByteArrayOutputStream();

        Cbor map(int entries) {
            head(5, entries);
            return this;
        }

        Cbor text(String value) {
            byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
            head(3, bytes.length);
            out.writeBytes(bytes);
            return this;
        }

        Cbor bytes(byte[] value) {
            head(2, value.length);
            out.writeBytes(value);
            return this;
        }

        Cbor integer(long value) {
            if (value >= 0) {
                head(0, value);
            } else {
                head(1, -1 - value);
            }
            return this;
        }

        Cbor raw(byte[] encoded) {
            out.writeBytes(encoded);
            return this;
        }

        byte[] toByteArray() {
            return out.toByteArray();
        }

        private void head(int majorType, long value) {
            int major = majorType << 5;
            if (value < 24) {
                out.write(major | (int) value);
            } else if (value < 0x100) {
                out.write(major | 24);
                out.write((int) value);
            } else if (value < 0x10000) {
                out.write(major | 25);
                out.writeBytes(ByteBuffer.allocate(2).putShort((short) value).array());
            } else {
                out.write(major | 26);
                out.writeBytes(ByteBuffer.allocate(4).putInt((int) value).array());
            }
        }
    }
}
