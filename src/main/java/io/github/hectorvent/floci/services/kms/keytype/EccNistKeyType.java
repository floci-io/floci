package io.github.hectorvent.floci.services.kms.keytype;

import io.github.hectorvent.floci.services.kms.model.KmsKey;
import io.github.hectorvent.floci.services.kms.model.KmsKeySpec;
import io.github.hectorvent.floci.services.kms.model.KmsMessageType;

import java.security.GeneralSecurityException;
import java.security.KeyPairGenerator;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.security.spec.ECGenParameterSpec;

final class EccNistKeyType implements KmsKeyType {

    @Override
    public void generateKeyMaterial(KmsKey key, String region) throws GeneralSecurityException {
        KeyPairGenerator generator = KeyPairGenerator.getInstance("EC");
        generator.initialize(new ECGenParameterSpec(key.getKeySpec().curveName()));
        AsymmetricKeys.store(key, generator.generateKeyPair());
    }

    @Override
    public byte[] sign(KmsKey key, byte[] message, String algorithm, KmsMessageType messageType)
            throws GeneralSecurityException {
        PrivateKey privateKey = AsymmetricKeys.privateKey(key, "EC");
        String jcaAlgorithm = switch (messageType) {
            case DIGEST -> "NONEwithECDSA";
            case RAW -> KmsKeySpec.getSignVerifyAlgorithm(algorithm).getJavaName();
        };
        return AsymmetricKeys.sign(privateKey, jcaAlgorithm, message);
    }

    @Override
    public boolean verify(KmsKey key, byte[] message, byte[] signature, String algorithm,
                          KmsMessageType messageType) throws GeneralSecurityException {
        PublicKey publicKey = AsymmetricKeys.publicKey(key, "EC");
        // An unknown algorithm name fails even for DIGEST.
        KmsKeySpec.Algorithm signingAlgorithm = KmsKeySpec.getSignVerifyAlgorithm(algorithm);
        String jcaAlgorithm = switch (messageType) {
            case DIGEST -> "NONEwithECDSA";
            case RAW -> signingAlgorithm.getJavaName();
        };
        return AsymmetricKeys.verify(publicKey, jcaAlgorithm, message, signature);
    }
}
