package io.github.hectorvent.floci.services.kms;

import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.core.common.RegionResolver;
import io.github.hectorvent.floci.core.common.ReservedTags;
import io.github.hectorvent.floci.core.storage.InMemoryStorage;
import io.github.hectorvent.floci.services.kms.model.KmsAlias;
import io.github.hectorvent.floci.services.kms.model.KmsKey;
import org.bouncycastle.jce.provider.BouncyCastleProvider;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.security.Security;
import java.security.KeyFactory;
import java.security.PublicKey;
import java.security.spec.X509EncodedKeySpec;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.junit.jupiter.api.Assertions.*;

class KmsServiceTest {

    private static final String REGION = "us-east-1";

    private KmsService kmsService;

    @BeforeAll
    static void registerBouncyCastle() {
        if (Security.getProvider(BouncyCastleProvider.PROVIDER_NAME) == null) {
            Security.addProvider(new BouncyCastleProvider());
        }
    }

    @BeforeEach
    void setUp() {
        kmsService = new KmsService(
                new InMemoryStorage<>(),
                new InMemoryStorage<>(),
                new RegionResolver("us-east-1", "000000000000")
        );
    }

    @Test
    void createKeyAndDescribe() {
        KmsKey key = kmsService.createKey("my test key", REGION);

        assertNotNull(key.getKeyId());
        assertNotNull(key.getArn());
        assertTrue(key.getArn().contains("key/"));
        assertEquals("my test key", key.getDescription());
        assertEquals("Enabled", key.getKeyState());
    }

    @Test
    void listKeys() {
        kmsService.createKey("key1", REGION);
        kmsService.createKey("key2", REGION);
        kmsService.createKey("key3", "eu-west-1");

        List<KmsKey> keys = kmsService.listKeys(REGION);
        assertEquals(2, keys.size());
    }

    @Test
    void describeKeyNotFound() {
        AwsException ex = assertThrows(AwsException.class, () ->
                kmsService.describeKey("non-existent-id", REGION));
        assertEquals("NotFoundException", ex.getErrorCode());
    }

    @Test
    void scheduleKeyDeletion() {
        KmsKey key = kmsService.createKey(null, REGION);
        kmsService.scheduleKeyDeletion(key.getKeyId(), 7, REGION);

        KmsKey updated = kmsService.describeKey(key.getKeyId(), REGION);
        assertEquals("PendingDeletion", updated.getKeyState());
        assertTrue(updated.getDeletionDate() > 0);
    }

    @Test
    void cancelKeyDeletion() {
        KmsKey key = kmsService.createKey(null, REGION);
        kmsService.scheduleKeyDeletion(key.getKeyId(), 7, REGION);
        kmsService.cancelKeyDeletion(key.getKeyId(), REGION);

        KmsKey updated = kmsService.describeKey(key.getKeyId(), REGION);
        assertEquals("Enabled", updated.getKeyState());
        assertEquals(0, updated.getDeletionDate());
    }

    @Test
    void createAlias() {
        KmsKey key = kmsService.createKey(null, REGION);
        kmsService.createAlias("alias/my-key", key.getKeyId(), REGION);

        List<KmsAlias> aliases = kmsService.listAliases(REGION);
        assertEquals(1, aliases.size());
        assertEquals("alias/my-key", aliases.getFirst().getAliasName());
        assertEquals(key.getKeyId(), aliases.getFirst().getTargetKeyId());
    }

    @Test
    void createAliasWithoutPrefixThrows() {
        KmsKey key = kmsService.createKey(null, REGION);
        assertThrows(AwsException.class, () ->
                kmsService.createAlias("my-key", key.getKeyId(), REGION));
    }

    @Test
    void createAliasForNonExistentKeyThrows() {
        assertThrows(AwsException.class, () ->
                kmsService.createAlias("alias/test", "no-such-key", REGION));
    }

    @Test
    void deleteAlias() {
        KmsKey key = kmsService.createKey(null, REGION);
        kmsService.createAlias("alias/to-delete", key.getKeyId(), REGION);
        kmsService.deleteAlias("alias/to-delete", REGION);

        assertTrue(kmsService.listAliases(REGION).isEmpty());
    }

    @Test
    void deleteAliasNotFoundThrows() {
        assertThrows(AwsException.class, () ->
                kmsService.deleteAlias("alias/missing", REGION));
    }

    @Test
    void resolveKeyByAlias() {
        KmsKey key = kmsService.createKey(null, REGION);
        kmsService.createAlias("alias/by-name", key.getKeyId(), REGION);

        KmsKey resolved = kmsService.describeKey("alias/by-name", REGION);
        assertEquals(key.getKeyId(), resolved.getKeyId());
    }

    @Test
    void encryptAndDecryptWithId() {
        KmsKey key = kmsService.createKey(null, REGION);
        byte[] plaintext = "hello world".getBytes(StandardCharsets.UTF_8);

        byte[] ciphertext = kmsService.encrypt(key.getKeyId(), plaintext, REGION);
        byte[] decrypted = kmsService.decrypt(ciphertext, REGION);

        assertArrayEquals(plaintext, decrypted);
    }

    @Test
    void encryptAndDecryptWithArn() {
        KmsKey key = kmsService.createKey(null, REGION);
        byte[] plaintext = "hello world".getBytes(StandardCharsets.UTF_8);

        byte[] ciphertext = kmsService.encrypt(key.getArn(), plaintext, REGION);
        byte[] decrypted = kmsService.decrypt(ciphertext, REGION);

        assertArrayEquals(plaintext, decrypted);
    }

    @Test
    void encryptAndDecryptWithAliasName() {
        KmsKey key = kmsService.createKey(null, REGION);
        String aliasName = "alias/my-alias";
        kmsService.createAlias(aliasName, key.getKeyId(), REGION);
        byte[] plaintext = "hello world".getBytes(StandardCharsets.UTF_8);

        byte[] ciphertext = kmsService.encrypt(aliasName, plaintext, REGION);
        byte[] decrypted = kmsService.decrypt(ciphertext, REGION);

        assertArrayEquals(plaintext, decrypted);
    }

    @Test
    void encryptAndDecryptWithAliasArn() {
        KmsKey key = kmsService.createKey(null, REGION);
        String aliasName = "alias/my-alias";
        kmsService.createAlias(aliasName, key.getKeyId(), REGION);
        byte[] plaintext = "hello world".getBytes(StandardCharsets.UTF_8);

        byte[] ciphertext = kmsService.encrypt("arn:aws:kms:" + REGION + ":000000000000:" + aliasName, plaintext, REGION);
        byte[] decrypted = kmsService.decrypt(ciphertext, REGION);

        assertArrayEquals(plaintext, decrypted);
    }

    @Test
    void decryptInvalidCiphertextThrows() {
        assertThrows(AwsException.class, () ->
                kmsService.decrypt("not-valid-ciphertext".getBytes(StandardCharsets.UTF_8), REGION));
    }

    @ParameterizedTest
    @ValueSource(strings = {"ECC_NIST_P256", "ECC_NIST_P384", "ECC_NIST_P521", "ECC_SECG_P256K1"})
    void signAndVerify(String keySpec) {
        KmsKey key = kmsService.createKey("ecdsa key", "SIGN_VERIFY", keySpec, null, Map.of(), REGION);
        byte[] message = "sign me".getBytes(StandardCharsets.UTF_8);

        byte[] sig = kmsService.sign(key.getKeyId(), message, "ECDSA_SHA_256", REGION);
        assertNotNull(sig);
        assertTrue(kmsService.verify(key.getKeyId(), message, sig, "ECDSA_SHA_256", REGION));
    }

    @Test
    void signAndVerifyWithRsa() {
        KmsKey key = kmsService.createKey("rsa key", "SIGN_VERIFY", "RSA_2048", null, Map.of(), REGION);
        byte[] message = "sign me".getBytes(StandardCharsets.UTF_8);

        byte[] sig = kmsService.sign(key.getKeyId(), message, "RSASSA_PKCS1_V1_5_SHA_256", REGION);
        assertNotNull(sig);
        assertTrue(kmsService.verify(key.getKeyId(), message, sig, "RSASSA_PKCS1_V1_5_SHA_256", REGION));
    }

    @Test
    void verifyWithWrongSignatureReturnsFalse() {
        KmsKey key = kmsService.createKey("ecdsa key", "SIGN_VERIFY", "ECC_NIST_P256", null, Map.of(), REGION);
        byte[] message = "sign me".getBytes(StandardCharsets.UTF_8);

        assertFalse(kmsService.verify(key.getKeyId(), message,
                "not-a-valid-sig".getBytes(StandardCharsets.UTF_8), "ECDSA_SHA_256", REGION));
    }

    @Test
    void getPublicKeyReturnsValidDerBytes() throws Exception {
        KmsKey key = kmsService.createKey("ecdsa key", "SIGN_VERIFY", "ECC_NIST_P256", null, Map.of(), REGION);
        KmsKey publicKeyInfo = kmsService.getPublicKey(key.getKeyId(), REGION);

        assertNotNull(publicKeyInfo.getPublicKeyEncoded());
        byte[] derBytes = Base64.getDecoder().decode(publicKeyInfo.getPublicKeyEncoded());
        
        // Verify it can be parsed as a standard Java PublicKey
        KeyFactory factory = KeyFactory.getInstance("EC");
        PublicKey pub = factory.generatePublic(new X509EncodedKeySpec(derBytes));
        assertNotNull(pub);
    }

    @Test
    void generateDataKey() {
        KmsKey key = kmsService.createKey(null, REGION);
        Map<String, Object> result = kmsService.generateDataKey(key.getKeyId(), "AES_256", 0, REGION);

        assertNotNull(result.get("Plaintext"));
        assertNotNull(result.get("CiphertextBlob"));
        assertEquals(32, ((byte[]) result.get("Plaintext")).length);
    }

    @Test
    void tagResource() {
        KmsKey key = kmsService.createKey(null, REGION);
        kmsService.tagResource(key.getKeyId(), Map.of("env", "test", "team", "platform"), REGION);

        KmsKey updated = kmsService.describeKey(key.getKeyId(), REGION);
        assertEquals("test", updated.getTags().get("env"));
        assertEquals("platform", updated.getTags().get("team"));
    }

    @Test
    void untagResource() {
        KmsKey key = kmsService.createKey(null, REGION);
        kmsService.tagResource(key.getKeyId(), Map.of("env", "test", "team", "platform"), REGION);
        kmsService.untagResource(key.getKeyId(), List.of("env"), REGION);

        KmsKey updated = kmsService.describeKey(key.getKeyId(), REGION);
        assertFalse(updated.getTags().containsKey("env"));
        assertTrue(updated.getTags().containsKey("team"));
    }

    // ── Issue #269 — CreateKey with Tags ────────────────────────────────────

    @Test
    void createKeyWithTagsStoresTags() {
        KmsKey key = kmsService.createKey("tagged-key", null, Map.of("env", "prod", "team", "platform"), REGION);

        KmsKey found = kmsService.describeKey(key.getKeyId(), REGION);
        assertEquals("prod", found.getTags().get("env"));
        assertEquals("platform", found.getTags().get("team"));
    }

    @Test
    void createKeyWithoutTagsHasEmptyTagMap() {
        KmsKey key = kmsService.createKey(null, REGION);
        assertTrue(key.getTags().isEmpty());
    }

    @Test
    void createKeyWithOverrideIdUsesProvidedId() {
        KmsKey key = kmsService.createKey(
                "tagged-key",
                null,
                Map.of(ReservedTags.OVERRIDE_ID_KEY, "my-test-key"),
                REGION
        );

        assertEquals("my-test-key", key.getKeyId());
        assertEquals("arn:aws:kms:us-east-1:000000000000:key/my-test-key", key.getArn());
    }

    @Test
    void createKeyWithOverrideIdStripsReservedTagFromStoredKey() {
        KmsKey key = kmsService.createKey(
                "tagged-key",
                null,
                Map.of(ReservedTags.OVERRIDE_ID_KEY, "my-test-key", "env", "test"),
                REGION
        );

        KmsKey found = kmsService.describeKey(key.getKeyId(), REGION);
        assertEquals("test", found.getTags().get("env"));
        assertFalse(found.getTags().containsKey(ReservedTags.OVERRIDE_ID_KEY));
    }

    @Test
    void createKeyWithDuplicateOverrideIdThrowsAlreadyExists() {
        kmsService.createKey("first", null, Map.of(ReservedTags.OVERRIDE_ID_KEY, "my-test-key"), REGION);

        AwsException exception = assertThrows(
                AwsException.class,
                () -> kmsService.createKey("second", null, Map.of(ReservedTags.OVERRIDE_ID_KEY, "my-test-key"), REGION)
        );

        assertEquals("AlreadyExistsException", exception.getErrorCode());
    }

    @Test
    void createKeyWithBlankOverrideIdThrowsValidation() {
        AwsException exception = assertThrows(
                AwsException.class,
                () -> kmsService.createKey("bad", null, Map.of(ReservedTags.OVERRIDE_ID_KEY, "   "), REGION)
        );

        assertEquals("ValidationException", exception.getErrorCode());
    }

    @Test
    void tagResourceWithReservedKeyThrowsValidation() {
        KmsKey key = kmsService.createKey(null, REGION);

        AwsException exception = assertThrows(
                AwsException.class,
                () -> kmsService.tagResource(key.getKeyId(), Map.of(ReservedTags.OVERRIDE_ID_KEY, "late-id"), REGION)
        );

        assertEquals("ValidationException", exception.getErrorCode());
    }

    // ── Issue #258 — GetKeyPolicy ────────────────────────────────────────────

    @Test
    void createKeyWithoutPolicyHasDefaultPolicy() {
        KmsKey key = kmsService.createKey(null, REGION);
        Map<String, Object> result = kmsService.getKeyPolicy(key.getKeyId(), REGION);

        assertNotNull(result.get("Policy"));
        assertEquals("default", result.get("PolicyName"));
        assertTrue(((String) result.get("Policy")).contains("kms:*"));
    }

    @Test
    void createKeyWithPolicyStoresPolicy() {
        String customPolicy = "{\"Version\":\"2012-10-17\",\"Statement\":[]}";
        KmsKey key = kmsService.createKey("policy-key", customPolicy, Map.of(), REGION);

        Map<String, Object> result = kmsService.getKeyPolicy(key.getKeyId(), REGION);
        assertEquals(customPolicy, result.get("Policy"));
        assertEquals("default", result.get("PolicyName"));
    }

    // ── Issue #259 — PutKeyPolicy ────────────────────────────────────────────

    @Test
    void putKeyPolicyUpdatesPolicy() {
        KmsKey key = kmsService.createKey(null, REGION);
        String newPolicy = "{\"Version\":\"2012-10-17\",\"Statement\":[{\"Effect\":\"Allow\"}]}";

        kmsService.putKeyPolicy(key.getKeyId(), newPolicy, REGION);

        Map<String, Object> result = kmsService.getKeyPolicy(key.getKeyId(), REGION);
        assertEquals(newPolicy, result.get("Policy"));
    }

    @Test
    void putKeyPolicyOnNonExistentKeyThrows() {
        assertThrows(AwsException.class, () ->
                kmsService.putKeyPolicy("non-existent", "{}", REGION));
    }

    // ── Issue #290 — Key Rotation ───────────────────────────────────────────

    @Test
    void getKeyRotationStatusDefaultFalse() {
        KmsKey key = kmsService.createKey(null, REGION);
        assertFalse(kmsService.getKeyRotationStatus(key.getKeyId(), REGION));
    }

    @Test
    void enableAndGetKeyRotationStatus() {
        KmsKey key = kmsService.createKey(null, REGION);
        kmsService.enableKeyRotation(key.getKeyId(), REGION);
        assertTrue(kmsService.getKeyRotationStatus(key.getKeyId(), REGION));
    }

    @Test
    void disableKeyRotationAfterEnable() {
        KmsKey key = kmsService.createKey(null, REGION);
        kmsService.enableKeyRotation(key.getKeyId(), REGION);
        kmsService.disableKeyRotation(key.getKeyId(), REGION);
        assertFalse(kmsService.getKeyRotationStatus(key.getKeyId(), REGION));
    }

    @Test
    void keyRotationOnNonExistentKeyThrows() {
        assertThrows(AwsException.class, () ->
                kmsService.getKeyRotationStatus("non-existent", REGION));
    }

    @Test
    void enableKeyRotationOnAsymmetricKeyThrows() {
        KmsKey key = kmsService.createKey(null, REGION);
        key.setCustomerMasterKeySpec("RSA_2048");
        key.setKeyUsage("SIGN_VERIFY");
        assertThrows(AwsException.class, () ->
                kmsService.enableKeyRotation(key.getKeyId(), REGION));
    }

    @Test
    void getKeyRotationStatusOnAsymmetricKeyReturnsFalse() {
        KmsKey key = kmsService.createKey(null, REGION);
        key.setCustomerMasterKeySpec("ECC_NIST_P256");
        key.setKeyUsage("SIGN_VERIFY");
        assertFalse(kmsService.getKeyRotationStatus(key.getKeyId(), REGION));
    }

    @Test
    void getKeyRotationStatusOnHmacKeyReturnsFalse() {
        KmsKey key = kmsService.createKey(null, REGION);
        key.setCustomerMasterKeySpec("HMAC_256");
        key.setKeyUsage("GENERATE_VERIFY_MAC");
        assertFalse(kmsService.getKeyRotationStatus(key.getKeyId(), REGION));
    }

    // ── Issue #497 — HMAC key specs ─────────────────────────────────────────

    @ParameterizedTest
    @ValueSource(strings = {"HMAC_224", "HMAC_256", "HMAC_384", "HMAC_512"})
    void createHmacKey_allSpecs(String spec) {
        KmsKey key = kmsService.createKey("hmac key", "GENERATE_VERIFY_MAC", spec, null, Map.of(), REGION);

        assertEquals(spec, key.getCustomerMasterKeySpec());
        assertEquals("GENERATE_VERIFY_MAC", key.getKeyUsage());
        assertNotNull(key.getPrivateKeyEncoded());

        int expectedBytes = switch (spec) {
            case "HMAC_224" -> 28;
            case "HMAC_256" -> 32;
            case "HMAC_384" -> 48;
            case "HMAC_512" -> 64;
            default -> -1;
        };
        assertEquals(expectedBytes, Base64.getDecoder().decode(key.getPrivateKeyEncoded()).length);

        KmsKey found = kmsService.describeKey(key.getKeyId(), REGION);
        assertEquals(spec, found.getCustomerMasterKeySpec());
    }

    @Test
    void createHmacKey_requiresGenerateVerifyMacUsage() {
        AwsException ex = assertThrows(AwsException.class, () ->
                kmsService.createKey("hmac key", "ENCRYPT_DECRYPT", "HMAC_256", null, Map.of(), REGION));
        assertEquals("ValidationException", ex.getErrorCode());
    }

    @Test
    void createSymmetricKey_rejectsGenerateVerifyMacUsage() {
        AwsException ex = assertThrows(AwsException.class, () ->
                kmsService.createKey("bad", "GENERATE_VERIFY_MAC", "SYMMETRIC_DEFAULT", null, Map.of(), REGION));
        assertEquals("ValidationException", ex.getErrorCode());
    }

    @Test
    void getPublicKeyForHmacKey_throwsUnsupportedOperation() {
        KmsKey key = kmsService.createKey("hmac key", "GENERATE_VERIFY_MAC", "HMAC_256", null, Map.of(), REGION);
        AwsException ex = assertThrows(AwsException.class, () ->
                kmsService.getPublicKey(key.getKeyId(), REGION));
        assertEquals("UnsupportedOperationException", ex.getErrorCode());
    }
}
