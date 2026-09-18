package io.github.hectorvent.floci.services.kms.keytype;

import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.services.kms.model.KmsKey;
import io.github.hectorvent.floci.services.kms.model.KmsKeySpec;
import io.github.hectorvent.floci.services.kms.model.KmsMessageType;

import java.security.GeneralSecurityException;

/**
 * The cryptography of one kind of KMS key.
 */
public interface KmsKeyType {

    void generateKeyMaterial(KmsKey key, String region) throws GeneralSecurityException;

    default byte[] sign(KmsKey key, byte[] message, String algorithm, KmsMessageType messageType)
            throws Exception {
        throw new AwsException("UnsupportedOperationException", "Unsupported key spec for signing.", 400);
    }

    default boolean verify(KmsKey key, byte[] message, byte[] signature, String algorithm,
                           KmsMessageType messageType) throws Exception {
        return false;
    }

    /** Symmetric keys use the ciphertext envelope in KmsService instead. */
    default byte[] encrypt(KmsKey key, KmsKeySpec.Algorithm algorithm, byte[] plaintext) {
        throw new IllegalStateException(key.getKeySpec() + " does not encrypt with " + algorithm);
    }

    default byte[] decrypt(KmsKey key, KmsKeySpec.Algorithm algorithm, byte[] ciphertext) {
        throw new IllegalStateException(key.getKeySpec() + " does not decrypt with " + algorithm);
    }

    default byte[] generateMac(KmsKey key, byte[] message, String algorithm) throws GeneralSecurityException {
        throw new IllegalStateException(key.getKeySpec() + " does not generate MACs");
    }
}
