package com.floci.test;

import org.junit.jupiter.api.*;
import software.amazon.awssdk.core.SdkBytes;
import software.amazon.awssdk.services.kms.KmsClient;
import software.amazon.awssdk.services.kms.model.CreateKeyResponse;
import software.amazon.awssdk.services.kms.model.DataKeySpec;
import software.amazon.awssdk.services.kms.model.DecryptResponse;
import software.amazon.awssdk.services.kms.model.DescribeKeyResponse;
import software.amazon.awssdk.services.kms.model.EncryptResponse;
import software.amazon.awssdk.services.kms.model.GenerateDataKeyResponse;
import software.amazon.awssdk.services.kms.model.GenerateDataKeyWithoutPlaintextResponse;
import software.amazon.awssdk.services.kms.model.KeyState;
import software.amazon.awssdk.services.kms.model.ListAliasesResponse;
import software.amazon.awssdk.services.kms.model.ListResourceTagsResponse;
import software.amazon.awssdk.services.kms.model.ReEncryptResponse;
import software.amazon.awssdk.services.kms.model.SignResponse;
import software.amazon.awssdk.services.kms.model.SigningAlgorithmSpec;
import software.amazon.awssdk.services.kms.model.VerifyResponse;

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
        SdkBytes msg = SdkBytes.fromString("message to sign", StandardCharsets.UTF_8);

        SignResponse signResponse = kms.sign(b -> b
                .keyId(keyId)
                .message(msg)
                .signingAlgorithm(SigningAlgorithmSpec.RSASSA_PSS_SHA_256));

        assertThat(signResponse.signature()).isNotNull();

        VerifyResponse verifyResponse = kms.verify(b -> b
                .keyId(keyId)
                .message(msg)
                .signature(signResponse.signature())
                .signingAlgorithm(SigningAlgorithmSpec.RSASSA_PSS_SHA_256));

        assertThat(verifyResponse.signatureValid()).isTrue();
    }

    @Test
    @Order(13)
    void scheduleKeyDeletion() {
        kms.scheduleKeyDeletion(b -> b.keyId(keyId).pendingWindowInDays(7));

        DescribeKeyResponse descResponse = kms.describeKey(b -> b.keyId(keyId));

        assertThat(descResponse.keyMetadata().keyState()).isEqualTo(KeyState.PENDING_DELETION);
    }

    @Test
    @Order(14)
    void deleteAlias() {
        kms.deleteAlias(b -> b.aliasName(aliasName));
        // No exception means success
    }
}
