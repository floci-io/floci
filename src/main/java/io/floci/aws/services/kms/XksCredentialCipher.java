package io.floci.aws.services.kms;

import io.floci.aws.config.EmulatorConfig;
import io.floci.aws.core.common.AwsException;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.annotation.PreDestroy;

import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.Base64;

/** Encrypts XKS proxy secrets with a stable, operator-managed AES-256-GCM key. */
@ApplicationScoped
public class XksCredentialCipher {
    private static final String PREFIX = "v1:";
    private static final int NONCE_BYTES = 12;
    private static final int TAG_BITS = 128;
    private final byte[] key;
    private final SecureRandom random = new SecureRandom();

    @Inject
    public XksCredentialCipher(EmulatorConfig config) {
        this(config.services().kms().xksCredentialEncryptionKey().orElse(null));
    }

    XksCredentialCipher(String configuredKey) {
        if (configuredKey == null || configuredKey.isBlank()) {
            key = null;
            return;
        }
        byte[] decoded;
        try {
            decoded = Base64.getDecoder().decode(configuredKey);
        } catch (IllegalArgumentException e) {
            throw configurationError();
        }
        if (decoded.length != 32) {
            Arrays.fill(decoded, (byte) 0);
            throw configurationError();
        }
        key = decoded;
    }

    public String encrypt(String plaintext, String context) {
        requireKey();
        byte[] nonce = new byte[NONCE_BYTES];
        random.nextBytes(nonce);
        try {
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(key, "AES"), new GCMParameterSpec(TAG_BITS, nonce));
            cipher.updateAAD(context.getBytes(StandardCharsets.UTF_8));
            byte[] cleartext = plaintext.getBytes(StandardCharsets.UTF_8);
            byte[] encrypted;
            try {
                encrypted = cipher.doFinal(cleartext);
            } finally {
                Arrays.fill(cleartext, (byte) 0);
            }
            try {
                return PREFIX + Base64.getEncoder().encodeToString(ByteBuffer.allocate(nonce.length + encrypted.length)
                        .put(nonce).put(encrypted).array());
            } finally {
                Arrays.fill(encrypted, (byte) 0);
            }
        } catch (GeneralSecurityException e) {
            throw new AwsException("InternalFailure", "Could not protect XKS proxy credentials", 500);
        }
    }

    public String decrypt(String envelope, String context) {
        requireKey();
        if (envelope == null || envelope.length() > 256 || !envelope.startsWith(PREFIX)) {
            throw new AwsException("InternalFailure", "Stored XKS proxy credentials have an unsupported format", 500);
        }
        byte[] payload = null;
        try {
            payload = Base64.getDecoder().decode(envelope.substring(PREFIX.length()));
            if (payload.length <= NONCE_BYTES + 16 || payload.length > 128) {
                throw new IllegalArgumentException();
            }
            byte[] nonce = Arrays.copyOfRange(payload, 0, NONCE_BYTES);
            byte[] ciphertext = Arrays.copyOfRange(payload, NONCE_BYTES, payload.length);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, new SecretKeySpec(key, "AES"), new GCMParameterSpec(TAG_BITS, nonce));
            cipher.updateAAD(context.getBytes(StandardCharsets.UTF_8));
            byte[] plaintext = cipher.doFinal(ciphertext);
            try {
                return new String(plaintext, StandardCharsets.UTF_8);
            } finally {
                Arrays.fill(plaintext, (byte) 0);
                Arrays.fill(ciphertext, (byte) 0);
            }
        } catch (GeneralSecurityException | IllegalArgumentException e) {
            throw new AwsException("InternalFailure", "Could not decrypt stored XKS proxy credentials; verify the configured encryption key", 500);
        } finally {
            // payload contains ciphertext, but clear the temporary copy regardless.
            if (payload != null) {
                Arrays.fill(payload, (byte) 0);
            }
        }
    }

    public void requireConfigured() {
        requireKey();
    }

    @PreDestroy
    void destroy() {
        if (key != null) {
            Arrays.fill(key, (byte) 0);
        }
    }

    private void requireKey() {
        if (key == null) {
            throw new AwsException("KMSInternalException",
                    "Configure FLOCI_SERVICES_KMS_XKS_CREDENTIAL_ENCRYPTION_KEY with a stable Base64-encoded 32-byte key before creating or connecting XKS custom key stores", 500);
        }
    }

    private static AwsException configurationError() {
        return new AwsException("KMSInternalException",
                "FLOCI_SERVICES_KMS_XKS_CREDENTIAL_ENCRYPTION_KEY must be valid Base64 encoding of exactly 32 bytes", 500);
    }
}
