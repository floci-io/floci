package io.github.hectorvent.floci.services.kms;

import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.core.common.RegionResolver;
import io.github.hectorvent.floci.core.storage.InMemoryStorage;
import io.github.hectorvent.floci.services.kms.model.KmsAlias;
import io.github.hectorvent.floci.services.kms.model.KmsKey;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class KmsServiceTest {

    private static final String REGION = "us-east-1";

    private KmsService kmsService;

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

    @Test
    void signAndVerify() {
        KmsKey key = kmsService.createKey(null, REGION);
        byte[] message = "sign me".getBytes(StandardCharsets.UTF_8);

        byte[] sig = kmsService.sign(key.getKeyId(), message, "RSASSA_PSS_SHA_256", REGION);
        assertTrue(kmsService.verify(key.getKeyId(), message, sig, "RSASSA_PSS_SHA_256", REGION));
    }

    @Test
    void verifyWithWrongSignatureReturnsFalse() {
        KmsKey key = kmsService.createKey(null, REGION);
        byte[] message = "sign me".getBytes(StandardCharsets.UTF_8);

        kmsService.sign(key.getKeyId(), message, "RSASSA_PSS_SHA_256", REGION);
        assertFalse(kmsService.verify(key.getKeyId(), message,
                "wrong-sig".getBytes(StandardCharsets.UTF_8), "RSASSA_PSS_SHA_256", REGION));
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
}