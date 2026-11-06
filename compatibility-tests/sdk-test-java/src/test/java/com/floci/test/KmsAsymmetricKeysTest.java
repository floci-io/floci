package com.floci.test;

import org.bouncycastle.jce.provider.BouncyCastleProvider;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import software.amazon.awssdk.core.SdkBytes;
import software.amazon.awssdk.services.kms.KmsClient;
import software.amazon.awssdk.services.kms.model.AlgorithmSpec;
import software.amazon.awssdk.services.kms.model.CreateKeyResponse;
import software.amazon.awssdk.services.kms.model.ExpirationModelType;
import software.amazon.awssdk.services.kms.model.GetParametersForImportResponse;
import software.amazon.awssdk.services.kms.model.KeySpec;
import software.amazon.awssdk.services.kms.model.KeyUsageType;
import software.amazon.awssdk.services.kms.model.MessageType;
import software.amazon.awssdk.services.kms.model.OriginType;
import software.amazon.awssdk.services.kms.model.SigningAlgorithmSpec;
import software.amazon.awssdk.services.kms.model.WrappingKeySpec;

import javax.crypto.Cipher;
import javax.crypto.spec.OAEPParameterSpec;
import javax.crypto.spec.PSource;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.KeyFactory;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.MessageDigest;
import java.security.Provider;
import java.security.PublicKey;
import java.security.Signature;
import java.security.spec.ECGenParameterSpec;
import java.security.spec.MGF1ParameterSpec;
import java.security.spec.PSSParameterSpec;
import java.security.spec.X509EncodedKeySpec;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Signing and key import paths that build BouncyCastle key parameters by hand, so a class the
 * native image left out shows up here rather than only in JVM tests.
 */
class KmsAsymmetricKeysTest {

    // secp256k1 is not in the JDK.
    private static final Provider BC = new BouncyCastleProvider();
    private static final byte[] MESSAGE = "floci asymmetric keys".getBytes(StandardCharsets.UTF_8);

    private static KmsClient kms;
    // RSA key generation is the slow part of this class, so the PSS cases share one key.
    private static String rsaKeyId;

    @BeforeAll
    static void setUp() {
        kms = TestFixtures.kmsClient();
        rsaKeyId = createKey(KeySpec.RSA_2048);
    }

    @AfterAll
    static void tearDown() {
        if (kms != null) {
            if (rsaKeyId != null) {
                scheduleDeletion(rsaKeyId);
            }
            kms.close();
        }
    }

    @Test
    void secp256k1KeySignsRawAndDigestMessages() throws Exception {
        String keyId = createKey(KeySpec.ECC_SECG_P256_K1);
        try {
            PublicKey publicKey = publicKey(keyId, "EC");
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(MESSAGE);

            byte[] rawSignature = sign(keyId, MESSAGE, MessageType.RAW, SigningAlgorithmSpec.ECDSA_SHA_256);
            byte[] digestSignature = sign(keyId, digest, MessageType.DIGEST, SigningAlgorithmSpec.ECDSA_SHA_256);

            assertThat(verifyLocally(publicKey, "SHA256withECDSA", rawSignature)).isTrue();
            assertThat(verifyLocally(publicKey, "SHA256withECDSA", digestSignature)).isTrue();
            assertThat(verify(keyId, MESSAGE, MessageType.RAW, SigningAlgorithmSpec.ECDSA_SHA_256, digestSignature))
                    .isTrue();
            assertThat(verify(keyId, digest, MessageType.DIGEST, SigningAlgorithmSpec.ECDSA_SHA_256, rawSignature))
                    .isTrue();
        } finally {
            scheduleDeletion(keyId);
        }
    }

    @ParameterizedTest
    @CsvSource({
            "ECC_NIST_P256, secp256r1, RSA_2048, ECDSA_SHA_256, SHA256withECDSA",
            "ECC_NIST_P384, secp384r1, RSA_2048, ECDSA_SHA_384, SHA384withECDSA",
            "ECC_NIST_P521, secp521r1, RSA_3072, ECDSA_SHA_512, SHA512withECDSA",
            "ECC_SECG_P256_K1, secp256k1, RSA_2048, ECDSA_SHA_256, SHA256withECDSA"
    })
    void importedEcKeyKeepsItsPublicKeyAndSigns(KeySpec keySpec, String curve, WrappingKeySpec wrappingKeySpec,
                                                SigningAlgorithmSpec algorithm, String jcaAlgorithm)
            throws Exception {
        // The JDK leaves the public key out of PKCS#8, which keeps the material under the OAEP size limit.
        KeyPairGenerator generator = "secp256k1".equals(curve) ? KeyPairGenerator.getInstance("EC", BC)
                : KeyPairGenerator.getInstance("EC");
        generator.initialize(new ECGenParameterSpec(curve));
        KeyPair keyPair = generator.generateKeyPair();

        CreateKeyResponse created = kms.createKey(b -> b
                .keySpec(keySpec)
                .keyUsage(KeyUsageType.SIGN_VERIFY)
                .origin(OriginType.EXTERNAL));
        String keyId = created.keyMetadata().keyId();
        try {
            GetParametersForImportResponse parameters = kms.getParametersForImport(b -> b
                    .keyId(keyId)
                    .wrappingAlgorithm(AlgorithmSpec.RSAES_OAEP_SHA_256)
                    .wrappingKeySpec(wrappingKeySpec));
            byte[] wrapped = wrapWithRsaOaepSha256(parameters.publicKey().asByteArray(),
                    keyPair.getPrivate().getEncoded());
            kms.importKeyMaterial(b -> b
                    .keyId(keyId)
                    .importToken(parameters.importToken())
                    .encryptedKeyMaterial(SdkBytes.fromByteArray(wrapped))
                    .expirationModel(ExpirationModelType.KEY_MATERIAL_DOES_NOT_EXPIRE));

            byte[] publicKey = kms.getPublicKey(b -> b.keyId(keyId)).publicKey().asByteArray();
            assertThat(publicKey).isEqualTo(keyPair.getPublic().getEncoded());

            byte[] signature = sign(keyId, MESSAGE, MessageType.RAW, algorithm);
            assertThat(verifyLocally(keyPair.getPublic(), jcaAlgorithm, signature)).isTrue();
        } finally {
            scheduleDeletion(keyId);
        }
    }

    @ParameterizedTest
    @CsvSource({
            "RSASSA_PSS_SHA_256, SHA-256",
            "RSASSA_PSS_SHA_384, SHA-384",
            "RSASSA_PSS_SHA_512, SHA-512"
    })
    void rsaPssSignsDigestMessages(SigningAlgorithmSpec algorithm, String digestName) throws Exception {
        byte[] digest = MessageDigest.getInstance(digestName).digest(MESSAGE);
        byte[] signature = sign(rsaKeyId, digest, MessageType.DIGEST, algorithm);

        assertThat(verify(rsaKeyId, digest, MessageType.DIGEST, algorithm, signature)).isTrue();
        assertThat(verify(rsaKeyId, MESSAGE, MessageType.RAW, algorithm, signature)).isTrue();

        Signature verifier = Signature.getInstance("RSASSA-PSS");
        int saltLength = MessageDigest.getInstance(digestName).getDigestLength();
        verifier.setParameter(new PSSParameterSpec(digestName, "MGF1", new MGF1ParameterSpec(digestName),
                saltLength, 1));
        verifier.initVerify(publicKey(rsaKeyId, "RSA"));
        verifier.update(MESSAGE);
        assertThat(verifier.verify(signature)).isTrue();
    }

    private static String createKey(KeySpec keySpec) {
        return kms.createKey(b -> b.keySpec(keySpec).keyUsage(KeyUsageType.SIGN_VERIFY)).keyMetadata().keyId();
    }

    private static PublicKey publicKey(String keyId, String algorithm) throws GeneralSecurityException {
        byte[] encoded = kms.getPublicKey(b -> b.keyId(keyId)).publicKey().asByteArray();
        KeyFactory factory = "EC".equals(algorithm) ? KeyFactory.getInstance(algorithm, BC)
                : KeyFactory.getInstance(algorithm);
        return factory.generatePublic(new X509EncodedKeySpec(encoded));
    }

    private static byte[] sign(String keyId, byte[] message, MessageType messageType,
                               SigningAlgorithmSpec algorithm) {
        return kms.sign(b -> b
                .keyId(keyId)
                .message(SdkBytes.fromByteArray(message))
                .messageType(messageType)
                .signingAlgorithm(algorithm)).signature().asByteArray();
    }

    private static boolean verify(String keyId, byte[] message, MessageType messageType,
                                  SigningAlgorithmSpec algorithm, byte[] signature) {
        return kms.verify(b -> b
                .keyId(keyId)
                .message(SdkBytes.fromByteArray(message))
                .messageType(messageType)
                .signingAlgorithm(algorithm)
                .signature(SdkBytes.fromByteArray(signature))).signatureValid();
    }

    private static boolean verifyLocally(PublicKey publicKey, String jcaAlgorithm, byte[] signature)
            throws GeneralSecurityException {
        Signature verifier = Signature.getInstance(jcaAlgorithm, BC);
        verifier.initVerify(publicKey);
        verifier.update(MESSAGE);
        return verifier.verify(signature);
    }

    private static void scheduleDeletion(String keyId) {
        kms.scheduleKeyDeletion(b -> b.keyId(keyId).pendingWindowInDays(7));
    }

    private static byte[] wrapWithRsaOaepSha256(byte[] publicKeyDer, byte[] material) throws GeneralSecurityException {
        PublicKey wrappingKey = KeyFactory.getInstance("RSA").generatePublic(new X509EncodedKeySpec(publicKeyDer));
        Cipher cipher = Cipher.getInstance("RSA/ECB/OAEPPadding");
        cipher.init(Cipher.ENCRYPT_MODE, wrappingKey, new OAEPParameterSpec("SHA-256", "MGF1",
                MGF1ParameterSpec.SHA256, PSource.PSpecified.DEFAULT));
        return cipher.doFinal(material);
    }
}
