package io.github.hectorvent.floci.services.kms.keytype;

import io.github.hectorvent.floci.services.kms.model.KmsKey;
import io.github.hectorvent.floci.services.kms.model.KmsKeySpec;

import java.security.SecureRandom;
import java.util.Base64;
import java.util.HashMap;
import java.util.UUID;

public final class SymmetricKeyType implements KmsKeyType {

    private final SecureRandom random;

    SymmetricKeyType(SecureRandom random) {
        this.random = random;
    }

    @Override
    public void generateKeyMaterial(KmsKey key, String region) {
        addBackingKey(key);
    }

    /**
     * Mints a new AES-256 backing key for the AES-GCM ciphertext envelope and makes it the
     * current one, keeping any earlier backing keys in the map so ciphertext produced under
     * them keeps decrypting (AWS KMS never discards prior backing keys either).
     */
    public void addBackingKey(KmsKey key) {
        byte[] material = new byte[KmsKeySpec.SYMMETRIC_DEFAULT.materialByteLength()];
        random.nextBytes(material);
        String backingKeyId = UUID.randomUUID().toString();
        if (key.getBackingKeys() == null) {
            key.setBackingKeys(new HashMap<>());
        }
        key.getBackingKeys().put(backingKeyId, Base64.getEncoder().encodeToString(material));
        key.setCurrentBackingKeyId(backingKeyId);
    }
}
