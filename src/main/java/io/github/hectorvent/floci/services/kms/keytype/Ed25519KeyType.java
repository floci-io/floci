package io.github.hectorvent.floci.services.kms.keytype;

import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.services.kms.model.KmsKey;
import io.github.hectorvent.floci.services.kms.model.KmsKeySpec;
import io.github.hectorvent.floci.services.kms.model.KmsMessageType;
import org.bouncycastle.asn1.x509.SubjectPublicKeyInfo;
import org.bouncycastle.crypto.CryptoException;
import org.bouncycastle.crypto.params.Ed25519PrivateKeyParameters;
import org.bouncycastle.crypto.params.Ed25519PublicKeyParameters;
import org.bouncycastle.crypto.signers.Ed25519phSigner;

import java.security.GeneralSecurityException;
import java.security.InvalidKeyException;
import java.security.KeyPairGenerator;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.security.interfaces.EdECPrivateKey;

final class Ed25519KeyType implements KmsKeyType {

    private static final int SHA_512_DIGEST_BYTES = 64;

    @Override
    public void generateKeyMaterial(KmsKey key, String region) throws GeneralSecurityException {
        AsymmetricKeys.store(key, KeyPairGenerator.getInstance("Ed25519").generateKeyPair());
    }

    // KMS runs Ed25519ph over the digest the caller sends, so the digest is hashed again.
    @Override
    public byte[] sign(KmsKey key, byte[] message, KmsKeySpec.Algorithm algorithm, KmsMessageType messageType)
            throws GeneralSecurityException, CryptoException {
        validateRequest(algorithm, messageType, message);
        PrivateKey privateKey = AsymmetricKeys.privateKey(key, "Ed25519");
        if (algorithm == KmsKeySpec.Algorithm.ED25519_SHA_512) {
            return AsymmetricKeys.sign(privateKey, "Ed25519", message);
        }
        Ed25519phSigner signer = new Ed25519phSigner(new byte[0]);
        signer.init(true, new Ed25519PrivateKeyParameters(seed(privateKey), 0));
        signer.update(message, 0, message.length);
        return signer.generateSignature();
    }

    @Override
    public boolean verify(KmsKey key, byte[] message, byte[] signature, KmsKeySpec.Algorithm algorithm,
                          KmsMessageType messageType) throws GeneralSecurityException {
        validateRequest(algorithm, messageType, message);
        PublicKey publicKey = AsymmetricKeys.publicKey(key, "Ed25519");
        if (algorithm == KmsKeySpec.Algorithm.ED25519_SHA_512) {
            return AsymmetricKeys.verify(publicKey, "Ed25519", message, signature);
        }
        Ed25519phSigner verifier = new Ed25519phSigner(new byte[0]);
        verifier.init(false, new Ed25519PublicKeyParameters(point(publicKey), 0));
        verifier.update(message, 0, message.length);
        return verifier.verifySignature(signature);
    }

    private static void validateRequest(KmsKeySpec.Algorithm algorithm, KmsMessageType messageType, byte[] message) {
        KmsMessageType required = algorithm == KmsKeySpec.Algorithm.ED25519_SHA_512
                ? KmsMessageType.RAW : KmsMessageType.DIGEST;
        if (messageType != required) {
            throw new AwsException("ValidationException",
                    "Message type " + messageType + " is incompatible with algorithm " + algorithm.getAlgName() + ".", 400);
        }
        if (algorithm == KmsKeySpec.Algorithm.ED25519_PH_SHA_512 && message.length != SHA_512_DIGEST_BYTES) {
            throw new AwsException("ValidationException",
                    "Digest is invalid length for algorithm " + algorithm.getAlgName() + ".", 400);
        }
    }

    private static byte[] seed(PrivateKey privateKey) throws InvalidKeyException {
        if (privateKey instanceof EdECPrivateKey edEC) {
            return edEC.getBytes().orElseThrow(() -> new InvalidKeyException("Ed25519 private key is not extractable"));
        }
        throw new InvalidKeyException("Expected an Ed25519 private key but got " + privateKey.getAlgorithm());
    }

    private static byte[] point(PublicKey publicKey) {
        return SubjectPublicKeyInfo.getInstance(publicKey.getEncoded()).getPublicKeyData().getBytes();
    }
}
