package io.github.hectorvent.floci.services.kms.keytype;

import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.services.kms.model.KmsKey;
import io.github.hectorvent.floci.services.kms.model.KmsKeySpec;
import io.github.hectorvent.floci.services.kms.model.KmsMessageType;
import org.bouncycastle.asn1.ASN1Integer;
import org.bouncycastle.asn1.ASN1Primitive;
import org.bouncycastle.asn1.ASN1Sequence;
import org.bouncycastle.asn1.DERSequenceGenerator;
import org.bouncycastle.crypto.params.ECPrivateKeyParameters;
import org.bouncycastle.crypto.params.ECPublicKeyParameters;
import org.bouncycastle.crypto.params.ParametersWithRandom;
import org.bouncycastle.crypto.signers.ECDSASigner;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.math.BigInteger;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.SecureRandom;

/**
 * ECC_SECG_P256K1 signs with BC's lightweight {@link ECDSASigner}. BC's {@code SignatureSpi}
 * subclasses extend {@code java.security.SignatureSpi} (not {@code java.security.Signature}), so
 * they cannot be used as a drop-in {@code Signature}. The lightweight API avoids JCA's
 * {@code ClassLoader.loadClass} entirely, so every class it needs stays in the native image.
 */
final class EccSecgP256k1KeyType implements KmsKeyType {

    private static final String CURVE = "secp256k1";

    private final SecureRandom random;

    EccSecgP256k1KeyType(SecureRandom random) {
        this.random = random;
    }

    @Override
    public void generateKeyMaterial(KmsKey key, String region) throws GeneralSecurityException {
        AsymmetricKeys.store(key, BcEcKeys.generateKeyPair(CURVE));
    }

    @Override
    public byte[] sign(KmsKey key, byte[] message, String algorithm, KmsMessageType messageType)
            throws GeneralSecurityException, IOException {
        ECPrivateKeyParameters privateKey = BcEcKeys.privateKeyParameters(key, CURVE);
        byte[] hash = switch (messageType) {
            case DIGEST -> message;
            case RAW -> hashForEcdsa(message, KmsKeySpec.getSignVerifyAlgorithm(algorithm));
        };

        ECDSASigner signer = new ECDSASigner();
        signer.init(true, new ParametersWithRandom(privateKey, random));
        BigInteger[] rs = signer.generateSignature(hash);

        ByteArrayOutputStream bOut = new ByteArrayOutputStream();
        DERSequenceGenerator seq = new DERSequenceGenerator(bOut);
        seq.addObject(new ASN1Integer(rs[0]));
        seq.addObject(new ASN1Integer(rs[1]));
        seq.close();
        return bOut.toByteArray();
    }

    @Override
    public boolean verify(KmsKey key, byte[] message, byte[] signature, String algorithm,
                          KmsMessageType messageType) throws GeneralSecurityException, IOException {
        ECPublicKeyParameters publicKey = BcEcKeys.publicKeyParameters(key, CURVE);
        // An unknown algorithm name fails even for DIGEST.
        KmsKeySpec.Algorithm signingAlgorithm = KmsKeySpec.getSignVerifyAlgorithm(algorithm);
        byte[] hash = switch (messageType) {
            case DIGEST -> message;
            case RAW -> hashForEcdsa(message, signingAlgorithm);
        };

        ASN1Sequence asn1 = ASN1Sequence.getInstance(ASN1Primitive.fromByteArray(signature));
        BigInteger r = ASN1Integer.getInstance(asn1.getObjectAt(0)).getValue();
        BigInteger s = ASN1Integer.getInstance(asn1.getObjectAt(1)).getValue();

        ECDSASigner verifier = new ECDSASigner();
        verifier.init(false, publicKey);
        return verifier.verifySignature(hash, r, s);
    }

    private static byte[] hashForEcdsa(byte[] message, KmsKeySpec.Algorithm algorithm)
            throws GeneralSecurityException {
        String digest = switch (algorithm) {
            case ECDSA_SHA_256 -> "SHA-256";
            case ECDSA_SHA_384 -> "SHA-384";
            case ECDSA_SHA_512 -> "SHA-512";
            default -> throw new AwsException("InvalidSigningAlgorithmException",
                    "Unsupported EC algorithm: " + algorithm.getJavaName(), 400);
        };
        return MessageDigest.getInstance(digest).digest(message);
    }
}
