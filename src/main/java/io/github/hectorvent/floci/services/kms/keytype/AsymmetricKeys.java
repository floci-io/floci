package io.github.hectorvent.floci.services.kms.keytype;

import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.services.kms.model.KmsKey;
import io.github.hectorvent.floci.services.kms.model.KmsKeySpec;
import io.github.hectorvent.floci.services.kms.model.KmsMessageType;

import java.security.GeneralSecurityException;
import java.security.KeyFactory;
import java.security.KeyPair;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.security.Signature;
import java.security.spec.MGF1ParameterSpec;
import java.security.spec.PKCS8EncodedKeySpec;
import java.security.spec.PSSParameterSpec;
import java.security.spec.X509EncodedKeySpec;
import java.util.Base64;

final class AsymmetricKeys {

    private AsymmetricKeys() {
    }

    static void requireRawMessage(KmsKeySpec spec, KmsMessageType messageType) {
        if (messageType != KmsMessageType.RAW) {
            throw new AwsException("ValidationException",
                    "Message type " + messageType + " is incompatible with key spec " + spec.name() + ".", 400);
        }
    }

    static void store(KmsKey key, KeyPair pair) {
        key.setPrivateKeyEncoded(Base64.getEncoder().encodeToString(pair.getPrivate().getEncoded()));
        key.setPublicKeyEncoded(Base64.getEncoder().encodeToString(pair.getPublic().getEncoded()));
    }

    static PrivateKey privateKey(KmsKey key, String keyAlgorithm) throws GeneralSecurityException {
        byte[] decoded = Base64.getDecoder().decode(key.getPrivateKeyEncoded());
        return KeyFactory.getInstance(keyAlgorithm).generatePrivate(new PKCS8EncodedKeySpec(decoded));
    }

    static PublicKey publicKey(KmsKey key, String keyAlgorithm) throws GeneralSecurityException {
        byte[] decoded = Base64.getDecoder().decode(key.getPublicKeyEncoded());
        return KeyFactory.getInstance(keyAlgorithm).generatePublic(new X509EncodedKeySpec(decoded));
    }

    static byte[] sign(PrivateKey privateKey, String jcaAlgorithm, byte[] message) throws GeneralSecurityException {
        Signature signature = signatureFor(jcaAlgorithm);
        signature.initSign(privateKey);
        signature.update(message);
        return signature.sign();
    }

    static boolean verify(PublicKey publicKey, String jcaAlgorithm, byte[] message, byte[] signature)
            throws GeneralSecurityException {
        Signature verifier = signatureFor(jcaAlgorithm);
        verifier.initVerify(publicKey);
        verifier.update(message);
        return verifier.verify(signature);
    }

    // KMS RSASSA_PSS uses MGF1 over the same digest, with a salt as long as the digest.
    private static Signature signatureFor(String jcaAlgorithm) throws GeneralSecurityException {
        if (!jcaAlgorithm.endsWith("withRSA/PSS")) {
            return Signature.getInstance(jcaAlgorithm);
        }
        String digest = "SHA-" + jcaAlgorithm.substring("SHA".length(), jcaAlgorithm.indexOf("with"));
        MGF1ParameterSpec maskGeneration = switch (digest) {
            case "SHA-256" -> MGF1ParameterSpec.SHA256;
            case "SHA-384" -> MGF1ParameterSpec.SHA384;
            case "SHA-512" -> MGF1ParameterSpec.SHA512;
            default -> throw new NoSuchAlgorithmException("Unsupported PSS digest: " + digest);
        };
        int saltLength = MessageDigest.getInstance(digest).getDigestLength();
        Signature signature = Signature.getInstance("RSASSA-PSS");
        signature.setParameter(new PSSParameterSpec(digest, "MGF1", maskGeneration, saltLength, 1));
        return signature;
    }
}
