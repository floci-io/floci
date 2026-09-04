package com.floci.test;

import org.junit.jupiter.api.*;
import software.amazon.awssdk.core.SdkBytes;
import software.amazon.awssdk.services.kms.KmsClient;
import software.amazon.awssdk.services.kms.model.CreateKeyResponse;
import software.amazon.awssdk.services.kms.model.DisabledException;
import software.amazon.awssdk.services.kms.model.EncryptionAlgorithmSpec;
import software.amazon.awssdk.services.kms.model.GetKeyPolicyResponse;
import software.amazon.awssdk.services.kms.model.IncorrectKeyException;
import software.amazon.awssdk.services.kms.model.InvalidKeyUsageException;
import software.amazon.awssdk.services.kms.model.KeySpec;
import software.amazon.awssdk.services.kms.model.KeyUsageType;
import software.amazon.awssdk.services.kms.model.KmsInvalidStateException;
import software.amazon.awssdk.services.kms.model.ListResourceTagsResponse;

import java.nio.charset.StandardCharsets;
import java.util.Map;

import static org.assertj.core.api.Assertions.*;

/**
 * Compatibility tests for KMS fixes:
 *   #269 — CreateKey applies Tags at creation time
 *   #258 — GetKeyPolicy returns the stored policy
 *   #259 — PutKeyPolicy updates the key policy
 *   #DescribeKey — Returns proper EncryptionAlgorithms, SigningAlgorithms, and MacAlgorithms
 *   #1844 — Decrypt enforces KeyId against the wrapping CMK (IncorrectKeyException)
 *   #1844 — ReEncrypt enforces SourceKeyId against the wrapping CMK (IncorrectKeyException)
 *   #3024: Encrypt/Decrypt apply real RSAES-OAEP for RSA keys
 */
@DisplayName("KMS features (#258 #259 #269 #1844 #3024)")
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class KmsFeaturesTest {

    private static KmsClient kms;

    @BeforeAll
    static void setup() {
        kms = TestFixtures.kmsClient();
    }

    @AfterAll
    static void cleanup() {
        if (kms != null) kms.close();
    }

    // ── Issue #269 — CreateKey applies Tags ───────────────────────────────────

    @Test
    @Order(10)
    void createKeyWithTagsStoresTags() {
        CreateKeyResponse resp = kms.createKey(b -> b
                .description("tagged-key")
                .tags(
                        software.amazon.awssdk.services.kms.model.Tag.builder().tagKey("env").tagValue("prod").build(),
                        software.amazon.awssdk.services.kms.model.Tag.builder().tagKey("team").tagValue("platform").build()
                ));
        String keyId = resp.keyMetadata().keyId();

        ListResourceTagsResponse tags = kms.listResourceTags(b -> b.keyId(keyId));
        Map<String, String> tagMap = tags.tags().stream()
                .collect(java.util.stream.Collectors.toMap(
                        software.amazon.awssdk.services.kms.model.Tag::tagKey,
                        software.amazon.awssdk.services.kms.model.Tag::tagValue));

        assertThat(tagMap).containsEntry("env", "prod")
                .containsEntry("team", "platform");

        kms.scheduleKeyDeletion(b -> b.keyId(keyId).pendingWindowInDays(7));
    }

    @Test
    @Order(11)
    void createKeyWithoutTagsHasEmptyTagList() {
        CreateKeyResponse resp = kms.createKey(b -> b.description("no-tags-key"));
        String keyId = resp.keyMetadata().keyId();

        ListResourceTagsResponse tags = kms.listResourceTags(b -> b.keyId(keyId));
        assertThat(tags.tags()).isEmpty();

        kms.scheduleKeyDeletion(b -> b.keyId(keyId).pendingWindowInDays(7));
    }

    // ── Issue #258 — GetKeyPolicy ─────────────────────────────────────────────

    @Test
    @Order(20)
    void createKeyWithoutPolicyReturnsDefaultPolicy() {
        CreateKeyResponse resp = kms.createKey(b -> b.description("default-policy-key"));
        String keyId = resp.keyMetadata().keyId();

        GetKeyPolicyResponse policyResp = kms.getKeyPolicy(b -> b.keyId(keyId));
        assertThat(policyResp.policy()).isNotBlank();
        assertThat(policyResp.policyName()).isEqualTo("default");
        assertThat(policyResp.policy()).contains("kms:*");

        kms.scheduleKeyDeletion(b -> b.keyId(keyId).pendingWindowInDays(7));
    }

    @Test
    @Order(21)
    void createKeyWithPolicyStoresAndReturnsPolicy() {
        String customPolicy = "{\"Version\":\"2012-10-17\",\"Statement\":[{\"Sid\":\"Custom\"," +
                "\"Effect\":\"Allow\",\"Principal\":{\"AWS\":\"arn:aws:iam::000000000000:root\"}," +
                "\"Action\":\"kms:*\",\"Resource\":\"*\"}]}";

        CreateKeyResponse resp = kms.createKey(b -> b
                .description("custom-policy-key")
                .policy(customPolicy));
        String keyId = resp.keyMetadata().keyId();

        GetKeyPolicyResponse policyResp = kms.getKeyPolicy(b -> b.keyId(keyId));
        assertThat(policyResp.policy()).isEqualTo(customPolicy);
        assertThat(policyResp.policyName()).isEqualTo("default");

        kms.scheduleKeyDeletion(b -> b.keyId(keyId).pendingWindowInDays(7));
    }

    // ── Issue #259 — PutKeyPolicy ─────────────────────────────────────────────

    @Test
    @Order(30)
    void putKeyPolicyUpdatesPolicy() {
        CreateKeyResponse resp = kms.createKey(b -> b.description("put-policy-key"));
        String keyId = resp.keyMetadata().keyId();

        String newPolicy = "{\"Version\":\"2012-10-17\",\"Statement\":[{\"Sid\":\"Updated\"," +
                "\"Effect\":\"Allow\",\"Principal\":{\"AWS\":\"arn:aws:iam::000000000000:root\"}," +
                "\"Action\":\"kms:Decrypt\",\"Resource\":\"*\"}]}";

        kms.putKeyPolicy(b -> b.keyId(keyId).policy(newPolicy));

        GetKeyPolicyResponse policyResp = kms.getKeyPolicy(b -> b.keyId(keyId));
        assertThat(policyResp.policy()).isEqualTo(newPolicy);

        kms.scheduleKeyDeletion(b -> b.keyId(keyId).pendingWindowInDays(7));
    }

    @Test
    @Order(31)
    void putKeyPolicyRoundTrip() {
        CreateKeyResponse resp = kms.createKey(b -> b.description("round-trip-key"));
        String keyId = resp.keyMetadata().keyId();

        // Get initial policy
        String initial = kms.getKeyPolicy(b -> b.keyId(keyId)).policy();
        assertThat(initial).isNotBlank();

        // Put a new policy
        String updated = "{\"Version\":\"2012-10-17\",\"Statement\":[{\"Sid\":\"RoundTrip\"," +
                "\"Effect\":\"Allow\",\"Principal\":{\"AWS\":\"arn:aws:iam::000000000000:root\"}," +
                "\"Action\":\"kms:*\",\"Resource\":\"*\"}]}";
        kms.putKeyPolicy(b -> b.keyId(keyId).policy(updated));

        // Verify change persisted
        assertThat(kms.getKeyPolicy(b -> b.keyId(keyId)).policy()).isEqualTo(updated);
        assertThat(kms.getKeyPolicy(b -> b.keyId(keyId)).policy()).isNotEqualTo(initial);

        kms.scheduleKeyDeletion(b -> b.keyId(keyId).pendingWindowInDays(7));
    }

    // ── DescribeKey Responses ────────────────────────────────────────────────

    @Test
    @Order(40)
    void describeSymmetricKeyReturnsNoEncryptionAlgorithms() {
        CreateKeyResponse resp = kms.createKey(b -> b
                .description("symmetric-key")
                .keyUsage("ENCRYPT_DECRYPT")
                .keySpec("SYMMETRIC_DEFAULT"));
        String keyId = resp.keyMetadata().keyId();

        assertThat(resp.keyMetadata().encryptionAlgorithms()).isNotEmpty();
        assertThat(resp.keyMetadata().encryptionAlgorithms()).contains(EncryptionAlgorithmSpec.SYMMETRIC_DEFAULT);
        assertThat(resp.keyMetadata().signingAlgorithms()).isEmpty();
        assertThat(resp.keyMetadata().macAlgorithms()).isEmpty();

        kms.scheduleKeyDeletion(b -> b.keyId(keyId).pendingWindowInDays(7));
    }

    @Test
    @Order(41)
    void describeAsymmetricRsaSignKeyReturnsSigningAlgorithms() {
        CreateKeyResponse resp = kms.createKey(b -> b
                .description("rsa-sign-key")
                .keyUsage("SIGN_VERIFY")
                .keySpec("RSA_2048"));
        String keyId = resp.keyMetadata().keyId();

        assertThat(resp.keyMetadata().signingAlgorithms())
                .contains(software.amazon.awssdk.services.kms.model.SigningAlgorithmSpec.RSASSA_PKCS1_V1_5_SHA_256,
                        software.amazon.awssdk.services.kms.model.SigningAlgorithmSpec.RSASSA_PSS_SHA_256);
        assertThat(resp.keyMetadata().encryptionAlgorithms()).isEmpty();

        kms.scheduleKeyDeletion(b -> b.keyId(keyId).pendingWindowInDays(7));
    }

    @Test
    @Order(42)
    void describeHmacKeyReturnsMacAlgorithms() {
        CreateKeyResponse resp = kms.createKey(b -> b
                .description("hmac-key")
                .keyUsage("GENERATE_VERIFY_MAC")
                .keySpec("HMAC_256"));
        String keyId = resp.keyMetadata().keyId();

        assertThat(resp.keyMetadata().macAlgorithms())
                .containsExactly(software.amazon.awssdk.services.kms.model.MacAlgorithmSpec.HMAC_SHA_256);
        assertThat(resp.keyMetadata().signingAlgorithms()).isEmpty();
        assertThat(resp.keyMetadata().encryptionAlgorithms()).isEmpty();

        kms.scheduleKeyDeletion(b -> b.keyId(keyId).pendingWindowInDays(7));
    }

    // ── Issue #1844: Decrypt enforces KeyId against the wrapping key ────────
    @Test
    @Order(50)
    void decryptWithMatchingKeyIdReturnsPlaintext() {
        String keyId = kms.createKey(b -> b.description("issue-1844-match")).keyMetadata().keyId();

        try {
            SdkBytes ciphertext = kms.encrypt(b -> b
                    .keyId(keyId)
                    .plaintext(SdkBytes.fromString("secret data", StandardCharsets.UTF_8)))
                    .ciphertextBlob();

            SdkBytes plaintext = kms.decrypt(b -> b
                    .ciphertextBlob(ciphertext)
                    .keyId(keyId))
                    .plaintext();

            assertThat(plaintext.asUtf8String()).isEqualTo("secret data");
        } finally {
            kms.scheduleKeyDeletion(b -> b.keyId(keyId).pendingWindowInDays(7));
        }
    }

    @Test
    @Order(51)
    void decryptWithMismatchedKeyIdRaisesIncorrectKeyException() {
        String keyIdA = kms.createKey(b -> b.description("issue-1844-a")).keyMetadata().keyId();
        String keyIdB = kms.createKey(b -> b.description("issue-1844-b")).keyMetadata().keyId();

        try {
            SdkBytes ciphertext = kms.encrypt(b -> b
                    .keyId(keyIdA)
                    .plaintext(SdkBytes.fromString("secret data", StandardCharsets.UTF_8)))
                    .ciphertextBlob();

            assertThatThrownBy(() -> kms.decrypt(b -> b
                    .ciphertextBlob(ciphertext)
                    .keyId(keyIdB)))
                    .isInstanceOf(IncorrectKeyException.class);
        } finally {
            kms.scheduleKeyDeletion(b -> b.keyId(keyIdA).pendingWindowInDays(7));
            kms.scheduleKeyDeletion(b -> b.keyId(keyIdB).pendingWindowInDays(7));
        }
    }

    @Test
    @Order(52)
    void reEncryptWithMatchingSourceKeyIdReturnsNewCiphertext() {
        String keyIdA = kms.createKey(b -> b.description("reencrypt-match-a")).keyMetadata().keyId();
        String keyIdB = kms.createKey(b -> b.description("reencrypt-match-b")).keyMetadata().keyId();

        try {
            SdkBytes ciphertext = kms.encrypt(b -> b
                    .keyId(keyIdA)
                    .plaintext(SdkBytes.fromString("secret data", StandardCharsets.UTF_8)))
                    .ciphertextBlob();

            SdkBytes reEncrypted = kms.reEncrypt(b -> b
                    .ciphertextBlob(ciphertext)
                    .sourceKeyId(keyIdA)
                    .destinationKeyId(keyIdB))
                    .ciphertextBlob();

            SdkBytes plaintext = kms.decrypt(b -> b.ciphertextBlob(reEncrypted)).plaintext();
            assertThat(plaintext.asUtf8String()).isEqualTo("secret data");
        } finally {
            kms.scheduleKeyDeletion(b -> b.keyId(keyIdA).pendingWindowInDays(7));
            kms.scheduleKeyDeletion(b -> b.keyId(keyIdB).pendingWindowInDays(7));
        }
    }

    @Test
    @Order(53)
    void reEncryptWithMismatchedSourceKeyIdRaisesIncorrectKeyException() {
        String keyIdA = kms.createKey(b -> b.description("reencrypt-mismatch-a")).keyMetadata().keyId();
        String keyIdB = kms.createKey(b -> b.description("reencrypt-mismatch-b")).keyMetadata().keyId();

        try {
            SdkBytes ciphertext = kms.encrypt(b -> b
                    .keyId(keyIdA)
                    .plaintext(SdkBytes.fromString("secret data", StandardCharsets.UTF_8)))
                    .ciphertextBlob();

            assertThatThrownBy(() -> kms.reEncrypt(b -> b
                    .ciphertextBlob(ciphertext)
                    .sourceKeyId(keyIdB)
                    .destinationKeyId(keyIdA)))
                    .isInstanceOf(IncorrectKeyException.class);
        } finally {
            kms.scheduleKeyDeletion(b -> b.keyId(keyIdA).pendingWindowInDays(7));
            kms.scheduleKeyDeletion(b -> b.keyId(keyIdB).pendingWindowInDays(7));
        }
    }

    @Test
    @Order(60)
    void encryptWithDisabledKeyRaisesDisabledException() {
        String keyId = kms.createKey(b -> b.description("disabled-encrypt")).keyMetadata().keyId();

        try {
            kms.disableKey(b -> b.keyId(keyId));

            assertThatThrownBy(
                    () -> kms.encrypt(b -> b
                            .keyId(keyId)
                            .plaintext(SdkBytes.fromString("secret data", StandardCharsets.UTF_8)))
            ).isInstanceOf(DisabledException.class);
        } finally {
            kms.scheduleKeyDeletion(b -> b.keyId(keyId).pendingWindowInDays(7));
        }
    }

    @Test
    @Order(61)
    void decryptWithDisabledKeyRaisesDisabledException() {
        String keyId = kms.createKey(b -> b.description("disabled-decrypt")).keyMetadata().keyId();

        try {
            SdkBytes ciphertext = kms.encrypt(b -> b
                            .keyId(keyId)
                            .plaintext(SdkBytes.fromString("secret data", StandardCharsets.UTF_8)))
                    .ciphertextBlob();

            kms.disableKey(b -> b.keyId(keyId));

            assertThatThrownBy(
                    () -> kms.decrypt(b -> b
                            .ciphertextBlob(ciphertext)
                            .keyId(keyId))
            ).isInstanceOf(DisabledException.class);
        } finally {
            kms.scheduleKeyDeletion(b -> b.keyId(keyId).pendingWindowInDays(7));
        }
    }

    @Test
    @Order(62)
    void encryptWithPendingDeletionKeyRaisesKmsInvalidStateException() {
        String keyId = kms.createKey(b -> b.description("pending-encrypt")).keyMetadata().keyId();

        try {
            kms.scheduleKeyDeletion(b -> b.keyId(keyId).pendingWindowInDays(7));

            assertThatThrownBy(
                    () -> kms.encrypt(b -> b
                            .keyId(keyId)
                            .plaintext(SdkBytes.fromString("secret data", StandardCharsets.UTF_8)))
            ).isInstanceOf(KmsInvalidStateException.class);
        } finally {
            kms.scheduleKeyDeletion(b -> b.keyId(keyId).pendingWindowInDays(7));
        }
    }

    @Test
    @Order(63)
    void decryptWithPendingDeletionKeyRaisesKmsInvalidStateException() {
        String keyId = kms.createKey(b -> b.description("pending-decrypt")).keyMetadata().keyId();

        try {
            SdkBytes ciphertext = kms.encrypt(b -> b
                            .keyId(keyId)
                            .plaintext(SdkBytes.fromString("secret data", StandardCharsets.UTF_8)))
                    .ciphertextBlob();

            kms.scheduleKeyDeletion(b -> b.keyId(keyId).pendingWindowInDays(7));

            assertThatThrownBy(
                    () -> kms.decrypt(b -> b
                            .ciphertextBlob(ciphertext)
                            .keyId(keyId))
            ).isInstanceOf(KmsInvalidStateException.class);
        } finally {
            kms.scheduleKeyDeletion(b -> b.keyId(keyId).pendingWindowInDays(7));
        }
    }

    // ── Issue #3024: Encrypt/Decrypt apply real RSAES-OAEP for RSA keys ──────

    @Test
    @Order(70)
    void rsaOaepEncryptDecryptRoundTrip() {
        var keyId = kms.createKey(b -> b
                        .keyUsage(KeyUsageType.ENCRYPT_DECRYPT)
                        .keySpec(KeySpec.RSA_2048))
                .keyMetadata().keyId();

        try {
            var encrypted = kms.encrypt(b -> b
                    .keyId(keyId)
                    .plaintext(SdkBytes.fromString("secret payload", StandardCharsets.UTF_8))
                    .encryptionAlgorithm(EncryptionAlgorithmSpec.RSAES_OAEP_SHA_256));

            assertThat(encrypted.ciphertextBlob().asByteArray()).hasSize(256);
            assertThat(encrypted.encryptionAlgorithm()).isEqualTo(EncryptionAlgorithmSpec.RSAES_OAEP_SHA_256);

            var decrypted = kms.decrypt(b -> b
                    .keyId(keyId)
                    .ciphertextBlob(encrypted.ciphertextBlob())
                    .encryptionAlgorithm(EncryptionAlgorithmSpec.RSAES_OAEP_SHA_256));

            assertThat(decrypted.plaintext().asUtf8String()).isEqualTo("secret payload");
            assertThat(decrypted.encryptionAlgorithm()).isEqualTo(EncryptionAlgorithmSpec.RSAES_OAEP_SHA_256);
        } finally {
            kms.scheduleKeyDeletion(b -> b.keyId(keyId).pendingWindowInDays(7));
        }
    }

    @Test
    @Order(71)
    void decryptAcceptsRsaOaepCiphertextMadeWithGetPublicKey() throws Exception {
        var keyId = kms.createKey(b -> b
                        .keyUsage(KeyUsageType.ENCRYPT_DECRYPT)
                        .keySpec(KeySpec.RSA_2048))
                .keyMetadata().keyId();

        try {
            var publicKeyDer = kms.getPublicKey(b -> b.keyId(keyId)).publicKey().asByteArray();
            var publicKey = java.security.KeyFactory.getInstance("RSA")
                    .generatePublic(new java.security.spec.X509EncodedKeySpec(publicKeyDer));
            var cipher = javax.crypto.Cipher.getInstance("RSA/ECB/OAEPPadding");
            cipher.init(javax.crypto.Cipher.ENCRYPT_MODE, publicKey, new javax.crypto.spec.OAEPParameterSpec(
                    "SHA-256", "MGF1", java.security.spec.MGF1ParameterSpec.SHA256,
                    javax.crypto.spec.PSource.PSpecified.DEFAULT));
            var localCiphertext = cipher.doFinal("secret payload".getBytes(StandardCharsets.UTF_8));

            var decrypted = kms.decrypt(b -> b
                    .keyId(keyId)
                    .ciphertextBlob(SdkBytes.fromByteArray(localCiphertext))
                    .encryptionAlgorithm(EncryptionAlgorithmSpec.RSAES_OAEP_SHA_256));

            assertThat(decrypted.plaintext().asUtf8String()).isEqualTo("secret payload");
        } finally {
            kms.scheduleKeyDeletion(b -> b.keyId(keyId).pendingWindowInDays(7));
        }
    }

    @Test
    @Order(72)
    void rsaEncryptWithDefaultAlgorithmRaisesInvalidKeyUsage() {
        var keyId = kms.createKey(b -> b
                        .keyUsage(KeyUsageType.ENCRYPT_DECRYPT)
                        .keySpec(KeySpec.RSA_2048))
                .keyMetadata().keyId();

        try {
            assertThatThrownBy(
                    () -> kms.encrypt(b -> b
                            .keyId(keyId)
                            .plaintext(SdkBytes.fromString("secret payload", StandardCharsets.UTF_8)))
            ).isInstanceOf(InvalidKeyUsageException.class);
        } finally {
            kms.scheduleKeyDeletion(b -> b.keyId(keyId).pendingWindowInDays(7));
        }
    }
}
