package io.github.hectorvent.floci.services.kms.keytype;

import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.services.kms.model.KmsKey;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.Base64;

final class HmacKeyType implements KmsKeyType {

    private final SecureRandom random;

    HmacKeyType(SecureRandom random) {
        this.random = random;
    }

    @Override
    public void generateKeyMaterial(KmsKey key, String region) {
        byte[] material = new byte[key.getKeySpec().materialByteLength()];
        random.nextBytes(material);
        key.setPrivateKeyEncoded(Base64.getEncoder().encodeToString(material));
    }

    @Override
    public byte[] generateMac(KmsKey key, byte[] message, String algorithm) throws GeneralSecurityException {
        byte[] keyBytes = Base64.getDecoder().decode(key.getPrivateKeyEncoded());
        String jcaAlgorithm = mapMacAlgorithm(algorithm);
        Mac mac = Mac.getInstance(jcaAlgorithm);
        mac.init(new SecretKeySpec(keyBytes, jcaAlgorithm));
        mac.update(message);
        return mac.doFinal();
    }

    private static String mapMacAlgorithm(String awsAlgo) {
        return switch (awsAlgo) {
            case "HMAC_SHA_224" -> "HmacSHA224";
            case "HMAC_SHA_256" -> "HmacSHA256";
            case "HMAC_SHA_384" -> "HmacSHA384";
            case "HMAC_SHA_512" -> "HmacSHA512";
            default -> throw new AwsException("InvalidMacAlgorithmException", "Unsupported MAC algorithm: " + awsAlgo, 400);
        };
    }

    @Override
    public void importKeyMaterial(KmsKey key, byte[] material) {
        validateKeyMaterialLength(key, material);
        key.setPrivateKeyEncoded(Base64.getEncoder().encodeToString(material));
    }

    private static void validateKeyMaterialLength(KmsKey key, byte[] material) {
        int expected = key.getKeySpec().materialByteLength();
        if (material.length != expected) {
            throw new AwsException("IncorrectKeyMaterialException",
                    "Key material for key spec " + key.getKeySpec() + " must be " + expected
                            + " bytes but was " + material.length + " bytes.", 400);
        }
    }
}
