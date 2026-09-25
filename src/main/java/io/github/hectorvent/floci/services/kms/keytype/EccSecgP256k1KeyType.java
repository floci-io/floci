package io.github.hectorvent.floci.services.kms.keytype;

import io.github.hectorvent.floci.services.kms.model.KmsKey;
import io.github.hectorvent.floci.services.kms.model.KmsKeySpec;
import io.github.hectorvent.floci.services.kms.model.KmsMessageType;
import org.bouncycastle.asn1.ASN1Integer;
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
    public void importKeyMaterial(KmsKey key, byte[] material) {
        BcEcKeys.importPrivateKey(key, material, CURVE);
    }

    @Override
    public byte[] sign(KmsKey key, byte[] message, KmsKeySpec.Algorithm algorithm, KmsMessageType messageType)
            throws GeneralSecurityException, IOException {
        ECPrivateKeyParameters privateKey = BcEcKeys.privateKeyParameters(key, CURVE);
        byte[] hash = hash(message, algorithm, messageType);

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
    public boolean verify(KmsKey key, byte[] message, byte[] signature, KmsKeySpec.Algorithm algorithm,
                          KmsMessageType messageType) throws GeneralSecurityException, IOException {
        ECPublicKeyParameters publicKey = BcEcKeys.publicKeyParameters(key, CURVE);
        byte[] hash = hash(message, algorithm, messageType);

        BigInteger r;
        BigInteger s;
        try {
            ASN1Sequence asn1 = ASN1Sequence.getInstance(signature);
            if (asn1.size() != 2) {
                return false;
            }
            r = ASN1Integer.getInstance(asn1.getObjectAt(0)).getValue();
            s = ASN1Integer.getInstance(asn1.getObjectAt(1)).getValue();
        } catch (IllegalArgumentException e) {
            return false;
        }

        ECDSASigner verifier = new ECDSASigner();
        verifier.init(false, publicKey);
        return verifier.verifySignature(hash, r, s);
    }

    private static byte[] hash(byte[] message, KmsKeySpec.Algorithm algorithm, KmsMessageType messageType)
            throws GeneralSecurityException {
        if (messageType == KmsMessageType.DIGEST) {
            return message;
        }
        String digest = switch (algorithm) {
            case ECDSA_SHA_256 -> "SHA-256";
            case ECDSA_SHA_384 -> "SHA-384";
            case ECDSA_SHA_512 -> "SHA-512";
            default -> throw new IllegalStateException("Not an ECDSA algorithm: " + algorithm);
        };
        return MessageDigest.getInstance(digest).digest(message);
    }
}
