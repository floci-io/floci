package io.github.hectorvent.floci.services.kms.keytype;

import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.services.kms.model.KmsKey;
import io.github.hectorvent.floci.services.kms.model.KmsKeySpec;
import io.github.hectorvent.floci.services.kms.model.KmsMessageType;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class KmsKeyTypesTest {

    private static final String REGION = "us-east-1";
    private static final byte[] MESSAGE = "per key type".getBytes(StandardCharsets.UTF_8);

    private final KmsKeyTypes keyTypes = new KmsKeyTypes(new SecureRandom());

    @Test
    void everyKeySpecHasItsOwnKeyType() {
        Map<KmsKeySpec, Class<? extends KmsKeyType>> expected = new EnumMap<>(KmsKeySpec.class);
        expected.put(KmsKeySpec.SYMMETRIC_DEFAULT, SymmetricKeyType.class);
        expected.put(KmsKeySpec.RSA_2048, RsaKeyType.class);
        expected.put(KmsKeySpec.RSA_3072, RsaKeyType.class);
        expected.put(KmsKeySpec.RSA_4096, RsaKeyType.class);
        expected.put(KmsKeySpec.ECC_NIST_P256, EccNistKeyType.class);
        expected.put(KmsKeySpec.ECC_NIST_P384, EccNistKeyType.class);
        expected.put(KmsKeySpec.ECC_NIST_P521, EccNistKeyType.class);
        expected.put(KmsKeySpec.ECC_NIST_EDWARDS25519, Ed25519KeyType.class);
        expected.put(KmsKeySpec.ECC_SECG_P256K1, EccSecgP256k1KeyType.class);
        expected.put(KmsKeySpec.HMAC_224, HmacKeyType.class);
        expected.put(KmsKeySpec.HMAC_256, HmacKeyType.class);
        expected.put(KmsKeySpec.HMAC_384, HmacKeyType.class);
        expected.put(KmsKeySpec.HMAC_512, HmacKeyType.class);
        expected.put(KmsKeySpec.SM2, Sm2KeyType.class);
        expected.put(KmsKeySpec.ML_DSA_44, MlDsaKeyType.class);
        expected.put(KmsKeySpec.ML_DSA_65, MlDsaKeyType.class);
        expected.put(KmsKeySpec.ML_DSA_87, MlDsaKeyType.class);

        assertEquals(KmsKeySpec.values().length, expected.size());
        for (Map.Entry<KmsKeySpec, Class<? extends KmsKeyType>> entry : expected.entrySet()) {
            assertEquals(entry.getValue(), keyTypes.of(entry.getKey()).getClass(), entry.getKey().name());
        }
    }

    @Test
    void addingABackingKeyKeepsEarlierOnes() throws GeneralSecurityException {
        KmsKey key = generatedKey(KmsKeySpec.SYMMETRIC_DEFAULT, REGION);
        String first = key.getCurrentBackingKeyId();

        keyTypes.symmetric().addBackingKey(key);

        assertNotEquals(first, key.getCurrentBackingKeyId());
        assertEquals(2, key.getBackingKeys().size());
        assertTrue(key.getBackingKeys().containsKey(first));
        for (String material : key.getBackingKeys().values()) {
            assertEquals(32, Base64.getDecoder().decode(material).length);
        }
    }

    @Test
    void addingABackingKeyStartsAMissingMap() {
        KmsKey key = newKey(KmsKeySpec.SYMMETRIC_DEFAULT);
        key.setBackingKeys(null);

        keyTypes.symmetric().addBackingKey(key);

        assertEquals(1, key.getBackingKeys().size());
        assertTrue(key.getBackingKeys().containsKey(key.getCurrentBackingKeyId()));
    }

    @ParameterizedTest
    @CsvSource({
            "HMAC_224, HMAC_SHA_224, HmacSHA224",
            "HMAC_256, HMAC_SHA_256, HmacSHA256",
            "HMAC_384, HMAC_SHA_384, HmacSHA384",
            "HMAC_512, HMAC_SHA_512, HmacSHA512",
    })
    void hmacMatchesTheJdkMacOverTheGeneratedMaterial(KmsKeySpec spec, String algorithm, String jcaAlgorithm)
            throws GeneralSecurityException {
        KmsKey key = generatedKey(spec, REGION);
        byte[] material = Base64.getDecoder().decode(key.getPrivateKeyEncoded());
        Mac mac = Mac.getInstance(jcaAlgorithm);
        mac.init(new SecretKeySpec(material, jcaAlgorithm));

        assertEquals(spec.materialByteLength(), material.length);
        assertArrayEquals(mac.doFinal(MESSAGE), keyTypes.of(spec).generateMac(key, MESSAGE, algorithm));
    }

    @ParameterizedTest
    @CsvSource({
            "RSA_2048, RSASSA_PKCS1_V1_5_SHA_256",
            "RSA_2048, RSASSA_PKCS1_V1_5_SHA_384",
            "RSA_2048, RSASSA_PSS_SHA_256",
            "RSA_2048, RSASSA_PSS_SHA_512",
            "ECC_NIST_P256, ECDSA_SHA_256",
            "ECC_NIST_P384, ECDSA_SHA_384",
            "ECC_NIST_P521, ECDSA_SHA_512",
            "ECC_SECG_P256K1, ECDSA_SHA_256",
            "ECC_NIST_EDWARDS25519, ED25519_SHA_512",
            "ML_DSA_44, ML_DSA_SHAKE_256",
    })
    void rawSignatureVerifiesOnlyForTheSignedMessage(KmsKeySpec spec, String algorithm) throws Exception {
        KmsKey key = generatedKey(spec, REGION);
        KmsKeyType keyType = keyTypes.of(spec);

        byte[] signature = keyType.sign(key, MESSAGE, algorithm, KmsMessageType.RAW);

        assertTrue(keyType.verify(key, MESSAGE, signature, algorithm, KmsMessageType.RAW));
        assertFalse(keyType.verify(key, "another message".getBytes(StandardCharsets.UTF_8), signature,
                algorithm, KmsMessageType.RAW));
    }

    @ParameterizedTest
    @CsvSource({
            "RSA_2048, RSASSA_PKCS1_V1_5_SHA_256, SHA-256",
            "RSA_2048, RSASSA_PSS_SHA_384, SHA-384",
            "ECC_NIST_P256, ECDSA_SHA_256, SHA-256",
            "ECC_SECG_P256K1, ECDSA_SHA_256, SHA-256",
    })
    void digestSignatureVerifiesAsARawSignature(KmsKeySpec spec, String algorithm, String digestAlgorithm)
            throws Exception {
        KmsKey key = generatedKey(spec, REGION);
        KmsKeyType keyType = keyTypes.of(spec);
        byte[] digest = MessageDigest.getInstance(digestAlgorithm).digest(MESSAGE);

        byte[] signature = keyType.sign(key, digest, algorithm, KmsMessageType.DIGEST);

        assertTrue(keyType.verify(key, MESSAGE, signature, algorithm, KmsMessageType.RAW));
        assertTrue(keyType.verify(key, digest, signature, algorithm, KmsMessageType.DIGEST));
    }

    @Test
    void ed25519PreHashSignsTheSha512DigestOfTheMessage() throws Exception {
        KmsKey key = generatedKey(KmsKeySpec.ECC_NIST_EDWARDS25519, REGION);
        KmsKeyType keyType = keyTypes.of(KmsKeySpec.ECC_NIST_EDWARDS25519);
        byte[] digest = MessageDigest.getInstance("SHA-512").digest(MESSAGE);

        byte[] signature = keyType.sign(key, digest, "ED25519_PH_SHA_512", KmsMessageType.DIGEST);

        assertTrue(keyType.verify(key, digest, signature, "ED25519_PH_SHA_512", KmsMessageType.DIGEST));
    }

    @ParameterizedTest
    @EnumSource(value = KmsKeySpec.class, names = {"ECC_NIST_EDWARDS25519", "ML_DSA_65", "SM2"})
    void keyTypesWithOneSigningAlgorithmRejectAnyOther(KmsKeySpec spec) throws GeneralSecurityException {
        KmsKey key = generatedKey(spec, "cn-north-1");

        AwsException exception = assertThrows(AwsException.class,
                () -> keyTypes.of(spec).sign(key, MESSAGE, "ECDSA_SHA_256", KmsMessageType.RAW));

        assertEquals("InvalidKeyUsageException", exception.getErrorCode());
        assertEquals("Algorithm ECDSA_SHA_256 is incompatible with key spec " + spec.name() + ".",
                exception.getMessage());
    }

    @ParameterizedTest
    @ValueSource(strings = {"RSAES_OAEP_SHA_1", "RSAES_OAEP_SHA_256"})
    void rsaEncryptionRoundTrips(String algorithmName) throws GeneralSecurityException {
        KmsKeySpec.Algorithm algorithm = KmsKeySpec.Algorithm.valueOf(algorithmName);
        KmsKey key = generatedKey(KmsKeySpec.RSA_2048, REGION);
        KmsKeyType keyType = keyTypes.of(KmsKeySpec.RSA_2048);

        byte[] ciphertext = keyType.encrypt(key, algorithm, MESSAGE);

        assertEquals(256, ciphertext.length);
        assertArrayEquals(MESSAGE, keyType.decrypt(key, algorithm, ciphertext));
    }

    @Test
    void rsaEncryptionRejectsPlaintextOverTheOaepLimit() throws GeneralSecurityException {
        KmsKey key = generatedKey(KmsKeySpec.RSA_2048, REGION);
        KmsKeyType keyType = keyTypes.of(KmsKeySpec.RSA_2048);

        keyType.encrypt(key, KmsKeySpec.Algorithm.RSAES_OAEP_SHA_256, new byte[190]);
        AwsException exception = assertThrows(AwsException.class,
                () -> keyType.encrypt(key, KmsKeySpec.Algorithm.RSAES_OAEP_SHA_256, new byte[191]));

        assertEquals("ValidationException", exception.getErrorCode());
        assertEquals("Algorithm RSAES_OAEP_SHA_256 and key spec RSA_2048 cannot encrypt data larger than 190 bytes.",
                exception.getMessage());
    }

    @Test
    void rsaDecryptionOfForeignCiphertextIsInvalidCiphertext() throws GeneralSecurityException {
        KmsKey key = generatedKey(KmsKeySpec.RSA_2048, REGION);
        KmsKey other = generatedKey(KmsKeySpec.RSA_2048, REGION);
        KmsKeyType keyType = keyTypes.of(KmsKeySpec.RSA_2048);
        byte[] ciphertext = keyType.encrypt(other, KmsKeySpec.Algorithm.RSAES_OAEP_SHA_256, MESSAGE);

        AwsException exception = assertThrows(AwsException.class,
                () -> keyType.decrypt(key, KmsKeySpec.Algorithm.RSAES_OAEP_SHA_256, ciphertext));

        assertEquals("InvalidCiphertextException", exception.getErrorCode());
    }

    @ParameterizedTest
    @ValueSource(strings = {"cn-north-1", "cn-northwest-1"})
    void sm2SignsInChinaRegions(String region) throws Exception {
        KmsKey key = generatedKey(KmsKeySpec.SM2, region);
        KmsKeyType keyType = keyTypes.of(KmsKeySpec.SM2);

        byte[] signature = keyType.sign(key, MESSAGE, "SM2DSA", KmsMessageType.RAW);

        assertTrue(keyType.verify(key, MESSAGE, signature, "SM2DSA", KmsMessageType.RAW));
    }

    @Test
    void sm2KeyMaterialIsRefusedOutsideChinaRegions() {
        KmsKey key = newKey(KmsKeySpec.SM2);

        AwsException exception = assertThrows(AwsException.class,
                () -> keyTypes.of(KmsKeySpec.SM2).generateKeyMaterial(key, REGION));

        assertEquals("UnsupportedOperationException", exception.getErrorCode());
        assertNull(key.getPrivateKeyEncoded());
    }

    @ParameterizedTest
    @EnumSource(value = KmsKeySpec.class, names = {"SYMMETRIC_DEFAULT", "HMAC_256"})
    void keyTypesWithoutSigningRefuseToSignAndNeverVerify(KmsKeySpec spec) throws Exception {
        KmsKey key = generatedKey(spec, REGION);
        KmsKeyType keyType = keyTypes.of(spec);

        AwsException exception = assertThrows(AwsException.class,
                () -> keyType.sign(key, MESSAGE, "RSASSA_PSS_SHA_256", KmsMessageType.RAW));

        assertEquals("UnsupportedOperationException", exception.getErrorCode());
        assertFalse(keyType.verify(key, MESSAGE, new byte[64], "RSASSA_PSS_SHA_256", KmsMessageType.RAW));
    }

    private KmsKey generatedKey(KmsKeySpec spec, String region) throws GeneralSecurityException {
        KmsKey key = newKey(spec);
        keyTypes.of(spec).generateKeyMaterial(key, region);
        return key;
    }

    private static KmsKey newKey(KmsKeySpec spec) {
        KmsKey key = new KmsKey();
        key.setKeyId("key-" + spec.name());
        key.setKeySpec(spec);
        key.setBackingKeys(new HashMap<>());
        return key;
    }
}
