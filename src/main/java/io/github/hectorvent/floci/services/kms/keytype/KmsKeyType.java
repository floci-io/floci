package io.github.hectorvent.floci.services.kms.keytype;

import io.github.hectorvent.floci.services.kms.model.KmsKey;
import io.github.hectorvent.floci.services.kms.model.KmsKeySpec;
import io.github.hectorvent.floci.services.kms.model.KmsMessageType;

import java.security.GeneralSecurityException;

public interface KmsKeyType {

    void generateKeyMaterial(KmsKey key, String region) throws GeneralSecurityException;

    default byte[] sign(KmsKey key, byte[] message, KmsKeySpec.Algorithm algorithm, KmsMessageType messageType)
            throws Exception {
        throw new IllegalStateException(key.getKeySpec() + " does not sign");
    }

    default boolean verify(KmsKey key, byte[] message, byte[] signature, KmsKeySpec.Algorithm algorithm,
                           KmsMessageType messageType) throws Exception {
        throw new IllegalStateException(key.getKeySpec() + " does not verify");
    }

    default byte[] encrypt(KmsKey key, KmsKeySpec.Algorithm algorithm, byte[] plaintext) {
        throw new IllegalStateException(key.getKeySpec() + " does not encrypt with " + algorithm);
    }

    default byte[] decrypt(KmsKey key, KmsKeySpec.Algorithm algorithm, byte[] ciphertext) {
        throw new IllegalStateException(key.getKeySpec() + " does not decrypt with " + algorithm);
    }

    default byte[] generateMac(KmsKey key, byte[] message, String algorithm) throws GeneralSecurityException {
        throw new IllegalStateException(key.getKeySpec() + " does not generate MACs");
    }

    /**
     * Validates and stores imported key material for the specified KMS key.
     *
     * @param key target KMS key
     * @param material key material after transport wrapping has been removed
     */
    default void importKeyMaterial(KmsKey key, byte[] material) {
        throw new IllegalStateException(key.getKeySpec() + " does not import key material");
    }
}
