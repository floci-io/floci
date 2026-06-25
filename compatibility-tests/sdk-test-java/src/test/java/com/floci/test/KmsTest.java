package com.floci.test;

import org.junit.jupiter.api.*;
import software.amazon.awssdk.core.SdkBytes;
import software.amazon.awssdk.services.kms.KmsClient;
import software.amazon.awssdk.services.kms.model.*;

import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.*;

@DisplayName("KMS Key Management Service")
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class KmsTest {

    private static KmsClient kms;
    private static String keyId;
    private static String aliasName;
    private static SdkBytes ciphertext;
    private static final String PLAINTEXT = "secret data";

    @BeforeAll
    static void setup() {
        kms = TestFixtures.kmsClient();
        aliasName = "alias/test-key-" + System.currentTimeMillis();
    }

    @AfterAll
    static void cleanup() {
        if (kms != null) {
            try {
                kms.deleteAlias(b -> b.aliasName(aliasName));
            } catch (Exception ignored) {}
            kms.close();
        }
    }

    @Test
    @Order(1)
    void createKey() {
        CreateKeyResponse response = kms.createKey(b -> b.description("test-key"));
        keyId = response.keyMetadata().keyId();

        assertThat(keyId).isNotNull();
    }

    @Test
    @Order(2)
    void describeKey() {
        DescribeKeyResponse response = kms.describeKey(b -> b.keyId(keyId));

        assertThat(response.keyMetadata().keyId()).isEqualTo(keyId);
    }

    @Test
    @Order(3)
    void createAlias() {
        kms.createAlias(b -> b.aliasName(aliasName).targetKeyId(keyId));
        // No exception means success
    }

    @Test
    @Order(4)
    void listAliases() {
        ListAliasesResponse response = kms.listAliases();

        assertThat(response.aliases())
                .anyMatch(a -> a.aliasName().equals(aliasName));
    }

    @Test
    @Order(5)
    void encrypt() {
        EncryptResponse response = kms.encrypt(b -> b
                .keyId(keyId)
                .plaintext(SdkBytes.fromString(PLAINTEXT, StandardCharsets.UTF_8)));
        ciphertext = response.ciphertextBlob();

        assertThat(ciphertext).isNotNull();
    }

    @Test
    @Order(6)
    void decrypt() {
        Assumptions.assumeTrue(ciphertext != null, "Encrypt must succeed first");

        DecryptResponse response = kms.decrypt(b -> b.ciphertextBlob(ciphertext));

        assertThat(response.plaintext().asUtf8String()).isEqualTo(PLAINTEXT);
    }

    @Test
    @Order(7)
    void encryptUsingAlias() {
        EncryptResponse response = kms.encrypt(b -> b
                .keyId(aliasName)
                .plaintext(SdkBytes.fromString("alias data", StandardCharsets.UTF_8)));

        assertThat(response.ciphertextBlob()).isNotNull();
    }

    @Test
    @Order(8)
    void generateDataKey() {
        GenerateDataKeyResponse response = kms.generateDataKey(b -> b
                .keyId(keyId)
                .keySpec(DataKeySpec.AES_256));

        assertThat(response.plaintext()).isNotNull();
        assertThat(response.ciphertextBlob()).isNotNull();
    }

    @Test
    @Order(9)
    void tagging() {
        kms.tagResource(b -> b
                .keyId(keyId)
                .tags(software.amazon.awssdk.services.kms.model.Tag.builder().tagKey("Project").tagValue("Floci").build()));

        ListResourceTagsResponse tagsResponse = kms.listResourceTags(b -> b.keyId(keyId));

        assertThat(tagsResponse.tags())
                .anyMatch(t -> t.tagKey().equals("Project") && t.tagValue().equals("Floci"));
    }

    @Test
    @Order(10)
    void reEncrypt() {
        Assumptions.assumeTrue(ciphertext != null, "Encrypt must succeed first");

        String keyId2 = kms.createKey(b -> b.description("key2")).keyMetadata().keyId();
        ReEncryptResponse reResponse = kms.reEncrypt(b -> b
                .ciphertextBlob(ciphertext)
                .destinationKeyId(keyId2));

        assertThat(reResponse.ciphertextBlob()).isNotNull();

        DecryptResponse decResponse = kms.decrypt(b -> b.ciphertextBlob(reResponse.ciphertextBlob()));
        assertThat(decResponse.plaintext().asUtf8String()).isEqualTo(PLAINTEXT);
    }

    @Test
    @Order(11)
    void generateDataKeyWithoutPlaintext() {
        GenerateDataKeyWithoutPlaintextResponse response = kms.generateDataKeyWithoutPlaintext(b -> b
                .keyId(keyId)
                .keySpec(DataKeySpec.AES_256));

        assertThat(response.ciphertextBlob()).isNotNull();
    }

    @Test
    @Order(12)
    void signAndVerify() {
        CreateKeyResponse createResponse = kms.createKey(b -> b
                .description("asymmetric-ecc-sign-key")
                .keyUsage(KeyUsageType.SIGN_VERIFY)
                .customerMasterKeySpec(CustomerMasterKeySpec.ECC_NIST_P256));
        String asymmetricKeyId = createResponse.keyMetadata().keyId();

        SdkBytes msg = SdkBytes.fromString("message to sign", StandardCharsets.UTF_8);

        SignResponse signResponse = kms.sign(b -> b
                .keyId(asymmetricKeyId)
                .message(msg)
                .signingAlgorithm(SigningAlgorithmSpec.ECDSA_SHA_256));

        assertThat(signResponse.signature()).isNotNull();

        VerifyResponse verifyResponse = kms.verify(b -> b
                .keyId(asymmetricKeyId)
                .message(msg)
                .signature(signResponse.signature())
                .signingAlgorithm(SigningAlgorithmSpec.ECDSA_SHA_256));

        assertThat(verifyResponse.signatureValid()).isTrue();
    }

    @Test
    @Order(13)
    void signAndVerifyRSA() {
        CreateKeyResponse createResponse = kms.createKey(b -> b
                .description("asymmetric-rsa-sign-key")
                .keyUsage(KeyUsageType.SIGN_VERIFY)
                .customerMasterKeySpec(CustomerMasterKeySpec.RSA_2048));
        String asymmetricKeyId = createResponse.keyMetadata().keyId();

        assertThat(createResponse.keyMetadata().keySpec()).isEqualTo(KeySpec.RSA_2048);
        assertThat(createResponse.keyMetadata().signingAlgorithms()).contains(SigningAlgorithmSpec.RSASSA_PKCS1_V1_5_SHA_256);
        assertThat(createResponse.keyMetadata().customerMasterKeySpec().name()).isEqualTo(KeySpec.RSA_2048.name());
        assertThat(createResponse.keyMetadata().keySpec().name()).isEqualTo(KeySpec.RSA_2048.name());
        SdkBytes msg = SdkBytes.fromString("message to sign", StandardCharsets.UTF_8);

        SignResponse signResponse = kms.sign(b -> b
                .keyId(asymmetricKeyId)
                .message(msg)
                .signingAlgorithm(SigningAlgorithmSpec.RSASSA_PKCS1_V1_5_SHA_256));

        assertThat(signResponse.signature()).isNotNull();

        VerifyResponse verifyResponse = kms.verify(b -> b
                .keyId(asymmetricKeyId)
                .message(msg)
                .signature(signResponse.signature())
                .signingAlgorithm(SigningAlgorithmSpec.RSASSA_PKCS1_V1_5_SHA_256));

        assertThat(verifyResponse.signatureValid()).isTrue();
    }

    @Test
    @Order(14)
    void signWithDigest() throws Exception {
        CreateKeyResponse createResponse = kms.createKey(b -> b
                .keyUsage(KeyUsageType.SIGN_VERIFY)
                .customerMasterKeySpec(CustomerMasterKeySpec.ECC_NIST_P256));
        String asymmetricKeyId = createResponse.keyMetadata().keyId();

        // SHA-256 hash of "hello"
        byte[] digest = java.security.MessageDigest.getInstance("SHA-256")
                .digest("hello".getBytes(StandardCharsets.UTF_8));
        SdkBytes msg = SdkBytes.fromByteArray(digest);

        SignResponse signResponse = kms.sign(b -> b
                .keyId(asymmetricKeyId)
                .message(msg)
                .messageType(MessageType.DIGEST)
                .signingAlgorithm(SigningAlgorithmSpec.ECDSA_SHA_256));

        VerifyResponse verifyResponse = kms.verify(b -> b
                .keyId(asymmetricKeyId)
                .message(msg)
                .messageType(MessageType.DIGEST)
                .signature(signResponse.signature())
                .signingAlgorithm(SigningAlgorithmSpec.ECDSA_SHA_256));

        assertThat(verifyResponse.signatureValid()).isTrue();
    }

    @Test
    @Order(15)
    void getPublicKey() {
        CreateKeyResponse createResponse = kms.createKey(b -> b
                .keyUsage(KeyUsageType.SIGN_VERIFY)
                .customerMasterKeySpec(CustomerMasterKeySpec.ECC_NIST_P256));
        String asymmetricKeyId = createResponse.keyMetadata().keyId();

        GetPublicKeyResponse pubResponse = kms.getPublicKey(b -> b.keyId(asymmetricKeyId));
        assertThat(pubResponse.publicKey()).isNotNull();
        assertThat(pubResponse.keyUsage()).isEqualTo(KeyUsageType.SIGN_VERIFY);
        assertThat(pubResponse.customerMasterKeySpec()).isEqualTo(CustomerMasterKeySpec.ECC_NIST_P256);
        assertThat(pubResponse.keySpec()).isEqualTo(KeySpec.ECC_NIST_P256);
        assertThat(pubResponse.signingAlgorithms()).isNotEmpty();
        assertThat(pubResponse.signingAlgorithms()).contains(SigningAlgorithmSpec.ECDSA_SHA_256);

    }

    @Test
    @Order(16)
    void scheduleKeyDeletion() {
        kms.scheduleKeyDeletion(b -> b.keyId(keyId).pendingWindowInDays(7));

        DescribeKeyResponse descResponse = kms.describeKey(b -> b.keyId(keyId));

        assertThat(descResponse.keyMetadata().keyState()).isEqualTo(KeyState.PENDING_DELETION);
    }

    @Test
    @Order(17)
    void deleteAlias() {
        DeleteAliasResponse deleteAliasResponse = kms.deleteAlias(b -> b.aliasName(aliasName));
        assertThat(deleteAliasResponse).isNotNull();
        // No exception means success
    }

    @Test
    @Order(18)
    void signAndVerifySecp256k1() {
        CreateKeyResponse createResponse = kms.createKey(b -> b
                .description("secp256k1-sign-key")
                .keyUsage(KeyUsageType.SIGN_VERIFY)
                .keySpec(KeySpec.ECC_SECG_P256_K1));

        String eccKeyId = createResponse.keyMetadata().keyId();

        SdkBytes msg = SdkBytes.fromString("message to sign", StandardCharsets.UTF_8);

        SignResponse signResponse = kms.sign(b -> b
                .keyId(eccKeyId)
                .message(msg)
                .signingAlgorithm(SigningAlgorithmSpec.ECDSA_SHA_256));

        assertThat(signResponse.signature()).isNotNull();

        VerifyResponse verifyResponse = kms.verify(b -> b
                .keyId(eccKeyId)
                .message(msg)
                .signature(signResponse.signature())
                .signingAlgorithm(SigningAlgorithmSpec.ECDSA_SHA_256));

        assertThat(verifyResponse.signatureValid()).isTrue();
    }

    @Test
    @Order(19)
    void generateRandom() {
        var response = kms.generateRandom(b -> b.numberOfBytes(32));

        assertThat(response.plaintext()).isNotNull();
        assertThat(response.plaintext().asByteArray()).hasSize(32);
    }

    @Test
    @Order(19)
    void describeSignVerifyRsaKey() {
        CreateKeyResponse response = kms.createKey(b -> b.description("test-sign-key")
                .keySpec(KeySpec.RSA_2048)
                .keyUsage("SIGN_VERIFY"));
        String signKeyId = response.keyMetadata().keyId();
        assertThat(response.keyMetadata().encryptionAlgorithms()).isEmpty();
        assertThat(response.keyMetadata().signingAlgorithms()).isNotEmpty();
        assertThat(response.keyMetadata().keySpec()).isNotNull();
        assertThat(response.keyMetadata().signingAlgorithms()).contains(SigningAlgorithmSpec.RSASSA_PKCS1_V1_5_SHA_256);
        assertThat(signKeyId).isNotNull();
        DescribeKeyResponse describeKeyResponse = kms.describeKey(b -> b.keyId(signKeyId));
        assertThat(describeKeyResponse.keyMetadata().signingAlgorithms()).isNotEmpty();
        assertThat(describeKeyResponse.keyMetadata().keySpec()).isNotNull();
        assertThat(describeKeyResponse.keyMetadata().keyId()).isEqualTo(signKeyId);
    }

    @Test
    @Order(20)
    void describeSignVerifyEcKey() {
        CreateKeyResponse response = kms.createKey(b -> b.description("test-sign-key")
                .keySpec(KeySpec.ECC_SECG_P256_K1)
                .keyUsage("SIGN_VERIFY"));
        String signKeyId = response.keyMetadata().keyId();
        assertThat(response.keyMetadata().signingAlgorithms()).isNotEmpty();
        assertThat(response.keyMetadata().keySpec()).isNotNull();
        assertThat(response.keyMetadata().signingAlgorithms()).contains(SigningAlgorithmSpec.ECDSA_SHA_256);
        assertThat(signKeyId).isNotNull();
        DescribeKeyResponse describeKeyResponse = kms.describeKey(b -> b.keyId(signKeyId));
        assertThat(describeKeyResponse.keyMetadata().signingAlgorithms()).isNotEmpty();
        assertThat(describeKeyResponse.keyMetadata().signingAlgorithms()).contains(SigningAlgorithmSpec.ECDSA_SHA_256);
        assertThat(describeKeyResponse.keyMetadata().keySpec()).isNotNull();
        assertThat(describeKeyResponse.keyMetadata().keyId()).isEqualTo(signKeyId);
    }

    @Test
    @Order(21)
    void describeSignVerifyOtherKey() {
        CreateKeyResponse response = kms.createKey(b -> b.description("test-sign-key")
                .keySpec(KeySpec.RSA_2048)
                .keyUsage("ENCRYPT_DECRYPT"));
        String signKeyId = response.keyMetadata().keyId();
        assertThat(response.keyMetadata().signingAlgorithms()).isEmpty();
        assertThat(response.keyMetadata().keySpec()).isNotNull();
        assertThat(response.keyMetadata().encryptionAlgorithms()).contains(EncryptionAlgorithmSpec.RSAES_OAEP_SHA_1);
        assertThat(signKeyId).isNotNull();
        DescribeKeyResponse describeKeyResponse = kms.describeKey(b -> b.keyId(signKeyId));
        assertThat(describeKeyResponse.keyMetadata().encryptionAlgorithms()).isNotEmpty();
        assertThat(describeKeyResponse.keyMetadata().encryptionAlgorithms()).contains(EncryptionAlgorithmSpec.RSAES_OAEP_SHA_256);
        assertThat(describeKeyResponse.keyMetadata().keySpec()).isNotNull();
        assertThat(describeKeyResponse.keyMetadata().keyId()).isEqualTo(signKeyId);
    }

    @Test
    @Order(22)
    void describeHmacKey() {
        CreateKeyResponse response = kms.createKey(b -> b.description("test-sign-key")
                .keySpec(KeySpec.HMAC_224)
                .keyUsage("GENERATE_VERIFY_MAC"));
        String signKeyId = response.keyMetadata().keyId();
        assertThat(response.keyMetadata().signingAlgorithms()).isEmpty();
        assertThat(response.keyMetadata().encryptionAlgorithms()).isEmpty();
        assertThat(response.keyMetadata().keySpec()).isNotNull();
        assertThat(response.keyMetadata().macAlgorithms()).contains(MacAlgorithmSpec.HMAC_SHA_224);
        assertThat(signKeyId).isNotNull();
        DescribeKeyResponse describeKeyResponse = kms.describeKey(b -> b.keyId(signKeyId));
        assertThat(describeKeyResponse.keyMetadata().macAlgorithms()).isNotEmpty();
        assertThat(describeKeyResponse.keyMetadata().macAlgorithms()).contains(MacAlgorithmSpec.HMAC_SHA_224);
        assertThat(describeKeyResponse.keyMetadata().keySpec()).isNotNull();
        assertThat(describeKeyResponse.keyMetadata().keyId()).isEqualTo(signKeyId);
    }

    @Test
    @Order(23)
    void generateAndVerifyMac() {
        CreateKeyResponse createResponse = kms.createKey(b -> b
                .description("hmac-test-key")
                .keySpec(KeySpec.HMAC_256)
                .keyUsage(KeyUsageType.GENERATE_VERIFY_MAC));
        String hmacKeyId = createResponse.keyMetadata().keyId();

        SdkBytes msg = SdkBytes.fromString("message for mac", StandardCharsets.UTF_8);

        GenerateMacResponse generateMacResponse = kms.generateMac(b -> b
                .keyId(hmacKeyId)
                .message(msg)
                .macAlgorithm(MacAlgorithmSpec.HMAC_SHA_256));

        assertThat(generateMacResponse.mac()).isNotNull();

        VerifyMacResponse verifyMacResponse = kms.verifyMac(b -> b
                .keyId(hmacKeyId)
                .message(msg)
                .mac(generateMacResponse.mac())
                .macAlgorithm(MacAlgorithmSpec.HMAC_SHA_256));

        assertThat(verifyMacResponse.macValid()).isTrue();
    }
}
