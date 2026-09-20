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
