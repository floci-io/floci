package io.github.hectorvent.floci.services.kms;

import io.github.hectorvent.floci.core.common.AwsException;
import org.jboss.logging.Logger;

import javax.crypto.Cipher;
import javax.crypto.spec.OAEPParameterSpec;
import javax.crypto.spec.PSource;
import java.security.KeyFactory;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.NoSuchAlgorithmException;
import java.security.PrivateKey;
import java.security.spec.MGF1ParameterSpec;
import java.security.spec.PKCS8EncodedKeySpec;
import java.util.Base64;

/**
 * The cryptography behind the KMS import-key-material flow: the RSA wrapping key pair
 * GetParametersForImport hands out, and the unwrap ImportKeyMaterial performs on what comes
 * back.
 *
 * <p>Stateless on purpose. {@link KmsService} owns the import state machine, the token and the
 * store; this class only knows how to mint a wrapping key and how to open material wrapped
 * with one.
 */
final class KmsKeyImport {

    private static final Logger LOG = Logger.getLogger(KmsKeyImport.class);

    private static final String RSAES_PKCS1_V1_5 = "RSAES_PKCS1_V1_5";
    private static final String RSAES_OAEP_SHA_1 = "RSAES_OAEP_SHA_1";
    private static final String RSAES_OAEP_SHA_256 = "RSAES_OAEP_SHA_256";

    private KmsKeyImport() {
    }

    /**
     * An RSA wrapping key pair, both halves Base64-encoded. The public half goes to the caller
     * so it can wrap its key material; the private half stays on the KMS key so a later
     * ImportKeyMaterial can unwrap what the caller sends back.
     */
    record WrappingKeyPair(String publicKeyEncoded, String privateKeyEncoded) {
    }

    static WrappingKeyPair generateWrappingKeyPair(String wrappingKeySpec) {
        int keySize = switch (wrappingKeySpec == null ? "" : wrappingKeySpec) {
            case "RSA_2048" -> 2048;
            case "RSA_3072" -> 3072;
            case "RSA_4096" -> 4096;
            case "SM2" -> throw new AwsException("UnsupportedOperationException",
                    "WrappingKeySpec SM2 is not supported.", 400);
            default -> throw new AwsException("ValidationException",
                    "1 validation error detected: Value '" + wrappingKeySpec + "' at 'wrappingKeySpec' failed to "
                            + "satisfy constraint: Member must satisfy enum value set: "
                            + "[RSA_2048, RSA_3072, RSA_4096, SM2]", 400);
        };
        try {
            KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
            generator.initialize(keySize);
            KeyPair pair = generator.generateKeyPair();
            return new WrappingKeyPair(
                    Base64.getEncoder().encodeToString(pair.getPublic().getEncoded()),
                    Base64.getEncoder().encodeToString(pair.getPrivate().getEncoded()));
        } catch (NoSuchAlgorithmException e) {
            throw new AwsException("InternalFailure", "Failed to generate wrapping key: " + e.getMessage(), 500);
        }
    }

    /**
     * Rejects a WrappingAlgorithm this emulator cannot honour before a key pair is generated for
     * it, so a caller never wraps material against parameters that ImportKeyMaterial would then
     * refuse.
     *
     * <p>RSAES_PKCS1_V1_5 stays in the modelled enum but AWS KMS stopped honouring it on
     * October 10, 2023. The RSA_AES variants exist for material longer than an RSA modulus can
     * hold; every spec that can be imported here carries at most 64 bytes.
     */
    static void validateWrappingAlgorithm(String wrappingAlgorithm) {
        switch (wrappingAlgorithm == null ? "" : wrappingAlgorithm) {
            case RSAES_OAEP_SHA_1, RSAES_OAEP_SHA_256 -> { }
            case RSAES_PKCS1_V1_5 -> throw new AwsException("UnsupportedOperationException",
                    "AWS KMS stopped supporting the RSAES_PKCS1_V1_5 wrapping algorithm on October 10, 2023. "
                            + "Use RSAES_OAEP_SHA_256 or RSAES_OAEP_SHA_1.", 400);
            case "RSA_AES_KEY_WRAP_SHA_1", "RSA_AES_KEY_WRAP_SHA_256", "SM2PKE" ->
                    throw new AwsException("UnsupportedOperationException",
                            "WrappingAlgorithm " + wrappingAlgorithm + " is not supported. Supported values are "
                                    + "RSAES_OAEP_SHA_256 and RSAES_OAEP_SHA_1.", 400);
            default -> throw new AwsException("ValidationException",
                    "1 validation error detected: Value '" + wrappingAlgorithm + "' at 'wrappingAlgorithm' failed "
                            + "to satisfy constraint: Member must satisfy enum value set: "
                            + "[RSAES_OAEP_SHA_256, RSAES_OAEP_SHA_1, RSA_AES_KEY_WRAP_SHA_256, "
                            + "RSA_AES_KEY_WRAP_SHA_1, SM2PKE, RSAES_PKCS1_V1_5]", 400);
        }
    }

    /**
     * Unwraps key material with the private half of the pair issued for this import.
     *
     * <p>Every failure answers InvalidCiphertextException, matching real KMS: whether the bytes
     * were garbage, wrapped for a different key, or wrapped with a different algorithm than the
     * one requested is not something the caller gets to distinguish.
     */
    static byte[] unwrap(String wrappingPrivateKeyEncoded, String wrappingAlgorithm, byte[] encryptedKeyMaterial) {
        try {
            PrivateKey wrappingKey = KeyFactory.getInstance("RSA").generatePrivate(
                    new PKCS8EncodedKeySpec(Base64.getDecoder().decode(wrappingPrivateKeyEncoded)));
            String digest = RSAES_OAEP_SHA_1.equals(wrappingAlgorithm) ? "SHA-1" : "SHA-256";
            Cipher cipher = Cipher.getInstance("RSA/ECB/OAEPPadding");
            cipher.init(Cipher.DECRYPT_MODE, wrappingKey, new OAEPParameterSpec(digest, "MGF1",
                    new MGF1ParameterSpec(digest), PSource.PSpecified.DEFAULT));
            return cipher.doFinal(encryptedKeyMaterial);
        } catch (Exception e) {
            LOG.debugv(e, "Unwrapping imported key material failed for wrapping algorithm {0}", wrappingAlgorithm);
            throw new AwsException("InvalidCiphertextException", "The ciphertext is invalid.", 400);
        }
    }
}
