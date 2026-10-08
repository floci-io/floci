package io.github.hectorvent.floci.services.kms.keytype;

import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.services.kms.model.KmsKey;
import io.github.hectorvent.floci.services.kms.model.KmsKeySpec;
import io.github.hectorvent.floci.services.kms.model.KmsMessageType;

import java.security.GeneralSecurityException;
import java.security.KeyPairGenerator;
import java.security.PrivateKey;
import java.security.PublicKey;

final class Ed25519KeyType implements KmsKeyType {

    @Override
    public void generateKeyMaterial(KmsKey key, String region) throws GeneralSecurityException {
        AsymmetricKeys.store(key, KeyPairGenerator.getInstance("Ed25519").generateKeyPair());
    }

    // KMS runs Ed25519ph over the digest the caller sends, so the digest is hashed again.
    @Override
    public byte[] sign(KmsKey key, byte[] message, KmsKeySpec.Algorithm algorithm, KmsMessageType messageType)
            throws GeneralSecurityException {
        validateMessageType(algorithm, messageType);
        PrivateKey privateKey = AsymmetricKeys.privateKey(key, "Ed25519");
        return AsymmetricKeys.sign(privateKey, algorithm.getJavaName(), message);
    }

    @Override
    public boolean verify(KmsKey key, byte[] message, byte[] signature, KmsKeySpec.Algorithm algorithm,
                          KmsMessageType messageType) throws GeneralSecurityException {
        validateMessageType(algorithm, messageType);
        PublicKey publicKey = AsymmetricKeys.publicKey(key, "Ed25519");
        return AsymmetricKeys.verify(publicKey, algorithm.getJavaName(), message, signature);
    }

    private static void validateMessageType(KmsKeySpec.Algorithm algorithm, KmsMessageType messageType) {
        KmsMessageType required = algorithm == KmsKeySpec.Algorithm.ED25519_SHA_512
                ? KmsMessageType.RAW : KmsMessageType.DIGEST;
        if (messageType != required) {
            throw new AwsException("ValidationException",
                    "Message type " + messageType + " is incompatible with algorithm " + algorithm.getAlgName() + ".", 400);
        }
    }
}
