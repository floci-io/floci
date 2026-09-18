package io.github.hectorvent.floci.services.kms.keytype;

import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.services.kms.model.KmsKey;
import io.github.hectorvent.floci.services.kms.model.KmsMessageType;
import org.bouncycastle.crypto.CryptoException;
import org.bouncycastle.crypto.params.ParametersWithRandom;
import org.bouncycastle.crypto.signers.SM2Signer;

import java.io.IOException;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;

final class Sm2KeyType implements KmsKeyType {

    private static final String CURVE = "sm2p256v1";

    private final SecureRandom random;

    Sm2KeyType(SecureRandom random) {
        this.random = random;
    }

    @Override
    public void generateKeyMaterial(KmsKey key, String region) throws GeneralSecurityException {
        if (!region.equals("cn-north-1") && !region.equals("cn-northwest-1")) {
            throw new AwsException("UnsupportedOperationException",
                    "KeySpec SM2 is not supported in this Region", 400);
        }
        AsymmetricKeys.store(key, BcEcKeys.generateKeyPair(CURVE));
    }

    @Override
    public byte[] sign(KmsKey key, byte[] message, String algorithm, KmsMessageType messageType)
            throws IOException, CryptoException {
        AsymmetricKeys.requireSpecAlgorithm(key.getKeySpec(), algorithm);
        AsymmetricKeys.requireRawMessage(key.getKeySpec(), messageType);
        SM2Signer signer = new SM2Signer();
        signer.init(true, new ParametersWithRandom(BcEcKeys.privateKeyParameters(key, CURVE), random));
        signer.update(message, 0, message.length);
        return signer.generateSignature();
    }

    @Override
    public boolean verify(KmsKey key, byte[] message, byte[] signature, String algorithm,
                          KmsMessageType messageType) throws IOException {
        AsymmetricKeys.requireSpecAlgorithm(key.getKeySpec(), algorithm);
        AsymmetricKeys.requireRawMessage(key.getKeySpec(), messageType);
        SM2Signer verifier = new SM2Signer();
        verifier.init(false, BcEcKeys.publicKeyParameters(key, CURVE));
        verifier.update(message, 0, message.length);
        return verifier.verifySignature(signature);
    }
}
