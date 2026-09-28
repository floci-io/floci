package io.github.hectorvent.floci.services.kms.keytype;

import io.github.hectorvent.floci.services.kms.model.KmsKey;
import io.github.hectorvent.floci.services.kms.model.KmsKeySpec;
import io.github.hectorvent.floci.services.kms.model.KmsMessageType;

import java.security.GeneralSecurityException;
import java.security.KeyPairGenerator;
import java.security.PrivateKey;
import java.security.PublicKey;

final class MlDsaKeyType implements KmsKeyType {

    @Override
    public void generateKeyMaterial(KmsKey key, String region) throws GeneralSecurityException {
        String algorithm = key.getKeySpec().name().replace('_', '-');
        AsymmetricKeys.store(key, KeyPairGenerator.getInstance(algorithm).generateKeyPair());
    }

    @Override
    public byte[] sign(KmsKey key, byte[] message, KmsKeySpec.Algorithm algorithm, KmsMessageType messageType)
            throws GeneralSecurityException {
        AsymmetricKeys.requireRawMessage(key.getKeySpec(), messageType);
        PrivateKey privateKey = AsymmetricKeys.privateKey(key, "ML-DSA");
        return AsymmetricKeys.sign(privateKey, algorithm.getJavaName(), message);
    }

    @Override
    public boolean verify(KmsKey key, byte[] message, byte[] signature, KmsKeySpec.Algorithm algorithm,
                          KmsMessageType messageType) throws GeneralSecurityException {
        AsymmetricKeys.requireRawMessage(key.getKeySpec(), messageType);
        PublicKey publicKey = AsymmetricKeys.publicKey(key, "ML-DSA");
        return AsymmetricKeys.verify(publicKey, algorithm.getJavaName(), message, signature);
    }
}
