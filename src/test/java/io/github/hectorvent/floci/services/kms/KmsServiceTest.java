package io.github.hectorvent.floci.services.kms;

import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.core.common.RegionResolver;
import io.github.hectorvent.floci.core.common.ReservedTags;
import io.github.hectorvent.floci.core.storage.InMemoryStorage;
import io.github.hectorvent.floci.services.kms.model.KmsAlias;
import io.github.hectorvent.floci.services.kms.model.KmsGrant;
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
import java.util.Arrays;
import java.util.Base64;
import java.util.LinkedHashMap;
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
    void listGrantsReturnsEmptyListForExistingKey() {
        KmsKey key = kmsService.createKey("grant key", REGION);

        Map<String, Object> result = kmsService.listGrants(key.getKeyId(), REGION, null, null, null, null);
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> grants = (List<Map<String, Object>>) result.get("Grants");

        assertTrue(grants.isEmpty());
    }

    @Test
    void listGrantsUnknownKeyThrowsNotFound() {
        AwsException ex = assertThrows(AwsException.class, () ->
                kmsService.listGrants("non-existent-id", REGION, null, null, null, null));

        assertEquals("NotFoundException", ex.getErrorCode());
    }

    @Test
    void createGrantAndListGrantsRoundTrip() {
        KmsKey key = kmsService.createKey("grant key", REGION);

        KmsGrant grant = kmsService.createGrant(
                key.getKeyId(),
                "arn:aws:iam::000000000000:user/grantee",
                List.of("Encrypt", "Decrypt"),
                REGION);

        assertNotNull(grant.getGrantId());
        assertFalse(grant.getGrantId().isBlank());
        assertNotNull(grant.getGrantToken());
        assertFalse(grant.getGrantToken().isBlank());

        Map<String, Object> result = kmsService.listGrants(key.getKeyId(), REGION, null, null, null, null);
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> grants = (List<Map<String, Object>>) result.get("Grants");

        assertEquals(1, grants.size());
        Map<String, Object> listedGrant = grants.getFirst();
        assertEquals(grant.getGrantId(), listedGrant.get("GrantId"));
        assertEquals(key.getArn(), listedGrant.get("KeyId"));
        assertEquals("arn:aws:iam::000000000000:user/grantee", listedGrant.get("GranteePrincipal"));
        assertEquals(List.of("Encrypt", "Decrypt"), listedGrant.get("Operations"));
        assertEquals(false, result.get("Truncated"));
    }

    @Test
    void createGrantMissingKeyIdThrowsValidation() {
        AwsException ex = assertThrows(AwsException.class, () ->
                kmsService.createGrant(null, "arn:aws:iam::000000000000:user/grantee", List.of("Encrypt"), REGION));

        assertEquals("ValidationException", ex.getErrorCode());
    }

    @Test
    void createGrantMissingGranteePrincipalThrowsValidation() {
        KmsKey key = kmsService.createKey("grant key", REGION);

        AwsException ex = assertThrows(AwsException.class, () ->
                kmsService.createGrant(key.getKeyId(), "", List.of("Encrypt"), REGION));

        assertEquals("ValidationException", ex.getErrorCode());
    }

    @Test
    void createGrantMissingOperationsThrowsValidation() {
        KmsKey key = kmsService.createKey("grant key", REGION);

        AwsException ex = assertThrows(AwsException.class, () ->
                kmsService.createGrant(key.getKeyId(), "arn:aws:iam::000000000000:user/grantee", List.of(), REGION));

        assertEquals("ValidationException", ex.getErrorCode());
    }

    @Test
    void createGrantUnknownKeyThrowsNotFound() {
        AwsException ex = assertThrows(AwsException.class, () ->
                kmsService.createGrant("non-existent-id", "arn:aws:iam::000000000000:user/grantee", List.of("Encrypt"), REGION));

        assertEquals("NotFoundException", ex.getErrorCode());
    }

    // ──────────────────────────── Phase 4: Pagination, Filters, ListRetirableGrants ────────────────────────────

    @Test
    void listGrantsPaginatesWithLimit() {
        KmsKey key = kmsService.createKey("pagination key", REGION);
        for (int i = 0; i < 5; i++) {
            kmsService.createGrant(key.getKeyId(), "arn:aws:iam::000000000000:user/grantee-" + i,
                    List.of("Encrypt"), REGION);
        }

        Map<String, Object> page1 = kmsService.listGrants(key.getKeyId(), REGION, null, 3, null, null);
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> grants1 = (List<Map<String, Object>>) page1.get("Grants");
        assertEquals(3, grants1.size());
        assertEquals(true, page1.get("Truncated"));
        assertNotNull(page1.get("NextMarker"));

        Map<String, Object> page2 = kmsService.listGrants(key.getKeyId(), REGION,
                (String) page1.get("NextMarker"), 3, null, null);
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> grants2 = (List<Map<String, Object>>) page2.get("Grants");
        assertEquals(2, grants2.size());
        assertEquals(false, page2.get("Truncated"));
    }

    @Test
    void listGrantsInvalidMarkerThrows() {
        KmsKey key = kmsService.createKey("marker key", REGION);

        AwsException ex = assertThrows(AwsException.class, () ->
                kmsService.listGrants(key.getKeyId(), REGION, "invalid-marker", null, null, null));

        assertEquals("InvalidMarkerException", ex.getErrorCode());
    }

    @Test
    void listGrantsRespectsDefaultLimit() {
        KmsKey key = kmsService.createKey("default limit key", REGION);
        for (int i = 0; i < 60; i++) {
            kmsService.createGrant(key.getKeyId(), "arn:aws:iam::000000000000:user/grantee-" + i,
                    List.of("Encrypt"), REGION);
        }

        Map<String, Object> result = kmsService.listGrants(key.getKeyId(), REGION, null, null, null, null);
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> grants = (List<Map<String, Object>>) result.get("Grants");
        assertEquals(50, grants.size());
        assertEquals(true, result.get("Truncated"));
    }

    @Test
    void listGrantsEnforcesMaxLimit() {
        KmsKey key = kmsService.createKey("max limit key", REGION);

        Map<String, Object> result = kmsService.listGrants(key.getKeyId(), REGION, null, 200, null, null);
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> grants = (List<Map<String, Object>>) result.get("Grants");
        assertTrue(grants.size() <= 100);
    }

    @Test
    void listGrantsFiltersByGrantId() {
        KmsKey key = kmsService.createKey("filter key", REGION);
        KmsGrant grant1 = kmsService.createGrant(key.getKeyId(), "arn:aws:iam::000000000000:user/grantee",
                List.of("Encrypt"), REGION);
        kmsService.createGrant(key.getKeyId(), "arn:aws:iam::000000000000:user/grantee",
                List.of("Decrypt"), REGION);

        Map<String, Object> result = kmsService.listGrants(key.getKeyId(), REGION, null, null,
                grant1.getGrantId(), null);
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> grants = (List<Map<String, Object>>) result.get("Grants");
        assertEquals(1, grants.size());
        assertEquals(grant1.getGrantId(), grants.getFirst().get("GrantId"));
    }

    @Test
    void listGrantsFiltersByGranteePrincipal() {
        KmsKey key = kmsService.createKey("principal filter key", REGION);
        kmsService.createGrant(key.getKeyId(), "arn:aws:iam::000000000000:user/alice",
                List.of("Encrypt"), REGION);
        kmsService.createGrant(key.getKeyId(), "arn:aws:iam::000000000000:user/bob",
                List.of("Decrypt"), REGION);

        Map<String, Object> result = kmsService.listGrants(key.getKeyId(), REGION, null, null, null,
                "arn:aws:iam::000000000000:user/alice");
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> grants = (List<Map<String, Object>>) result.get("Grants");
        assertEquals(1, grants.size());
        assertEquals("arn:aws:iam::000000000000:user/alice", grants.getFirst().get("GranteePrincipal"));
    }

    @Test
    void listRetirableGrantsReturnsMatchingGrants() {
        KmsKey key = kmsService.createKey("retirable key", REGION);
        kmsService.createGrant(key.getKeyId(), "arn:aws:iam::000000000000:user/grantee",
                List.of("Encrypt"), "arn:aws:iam::000000000000:role/retirer", REGION);
        kmsService.createGrant(key.getKeyId(), "arn:aws:iam::000000000000:user/grantee",
                List.of("Decrypt"), null, REGION);

        Map<String, Object> result = kmsService.listRetirableGrants(
                "arn:aws:iam::000000000000:role/retirer", REGION, null, null);
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> grants = (List<Map<String, Object>>) result.get("Grants");
        assertEquals(1, grants.size());
        assertEquals("arn:aws:iam::000000000000:role/retirer", grants.getFirst().get("RetiringPrincipal"));
    }

    @Test
    void listRetirableGrantsMissingPrincipalThrowsValidation() {
        AwsException ex = assertThrows(AwsException.class, () ->
                kmsService.listRetirableGrants("", REGION, null, null));
        assertEquals("ValidationException", ex.getErrorCode());
    }

    // ──────────────────────────── Phase 5: RevokeGrant ────────────────────────────

    @Test
    void revokeGrantRemovesGrant() {
        KmsKey key = kmsService.createKey("revoke key", REGION);
        KmsGrant grant = kmsService.createGrant(
                key.getKeyId(),
                "arn:aws:iam::000000000000:user/grantee",
                List.of("Encrypt"),
                REGION);

        // Grant exists before revoke
        Map<String, Object> before = kmsService.listGrants(key.getKeyId(), REGION, null, null, null, null);
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> beforeGrants = (List<Map<String, Object>>) before.get("Grants");
        assertEquals(1, beforeGrants.size());

        // Revoke the grant
        kmsService.revokeGrant(key.getKeyId(), grant.getGrantId(), REGION);

        // Grant is gone after revoke
        Map<String, Object> after = kmsService.listGrants(key.getKeyId(), REGION, null, null, null, null);
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> afterGrants = (List<Map<String, Object>>) after.get("Grants");
        assertTrue(afterGrants.isEmpty());
    }

    @Test
    void revokeGrantUnknownGrantThrowsNotFound() {
        KmsKey key = kmsService.createKey("revoke unknown grant key", REGION);

        AwsException ex = assertThrows(AwsException.class, () ->
                kmsService.revokeGrant(key.getKeyId(), "non-existent-grant-id", REGION));

        assertEquals("NotFoundException", ex.getErrorCode());
    }

    @Test
    void revokeGrantUnknownKeyThrowsNotFound() {
        AwsException ex = assertThrows(AwsException.class, () ->
                kmsService.revokeGrant("non-existent-key", "some-grant-id", REGION));

        assertEquals("NotFoundException", ex.getErrorCode());
    }

    @Test
    void revokeGrantMissingKeyIdThrowsValidation() {
        AwsException ex = assertThrows(AwsException.class, () ->
                kmsService.revokeGrant(null, "some-grant-id", REGION));

        assertEquals("ValidationException", ex.getErrorCode());
    }

    @Test
    void revokeGrantMissingGrantIdThrowsValidation() {
        KmsKey key = kmsService.createKey("revoke missing grant key", REGION);

        AwsException ex = assertThrows(AwsException.class, () ->
                kmsService.revokeGrant(key.getKeyId(), null, REGION));

        assertEquals("ValidationException", ex.getErrorCode());
    }

    // ──────────────────────────── Phase 6: RetireGrant ────────────────────────────

    @Test
    void retireGrantByTokenRemovesGrant() {
        KmsKey key = kmsService.createKey("retire token key", REGION);
        KmsGrant grant = kmsService.createGrant(
                key.getKeyId(),
                "arn:aws:iam::000000000000:user/grantee",
                List.of("Encrypt"),
                REGION);

        // Grant exists before retire
        Map<String, Object> before = kmsService.listGrants(key.getKeyId(), REGION, null, null, null, null);
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> beforeGrants = (List<Map<String, Object>>) before.get("Grants");
        assertEquals(1, beforeGrants.size());

        // Retire by grant token
        kmsService.retireGrant(grant.getGrantToken(), null, null, REGION);

        // Grant is gone after retire
        Map<String, Object> after = kmsService.listGrants(key.getKeyId(), REGION, null, null, null, null);
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> afterGrants = (List<Map<String, Object>>) after.get("Grants");
        assertTrue(afterGrants.isEmpty());
    }

    @Test
    void retireGrantByTokenWithMatchingGrantIdSucceeds() {
        KmsKey key = kmsService.createKey("retire token+grant key", REGION);
        KmsGrant grant = kmsService.createGrant(
                key.getKeyId(),
                "arn:aws:iam::000000000000:user/grantee",
                List.of("Encrypt"),
                REGION);

        kmsService.retireGrant(grant.getGrantToken(), null, grant.getGrantId(), REGION);

        Map<String, Object> after = kmsService.listGrants(key.getKeyId(), REGION, null, null, null, null);
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> afterGrants = (List<Map<String, Object>>) after.get("Grants");
        assertTrue(afterGrants.isEmpty());
    }

    @Test
    void retireGrantByTokenWithMismatchedGrantIdThrowsNotFound() {
        KmsKey key = kmsService.createKey("retire mismatch key", REGION);
        KmsGrant grant = kmsService.createGrant(
                key.getKeyId(),
                "arn:aws:iam::000000000000:user/grantee",
                List.of("Encrypt"),
                REGION);

        AwsException ex = assertThrows(AwsException.class, () ->
                kmsService.retireGrant(grant.getGrantToken(), null, "wrong-grant-id", REGION));

        assertEquals("NotFoundException", ex.getErrorCode());
    }

    @Test
    void retireGrantByKeyAndGrantIdRemovesGrant() {
        KmsKey key = kmsService.createKey("retire admin key", REGION);
        KmsGrant grant = kmsService.createGrant(
                key.getKeyId(),
                "arn:aws:iam::000000000000:user/grantee",
                List.of("Encrypt"),
                REGION);

        // Administrative retire by KeyId + GrantId
        kmsService.retireGrant(null, key.getKeyId(), grant.getGrantId(), REGION);

        Map<String, Object> after = kmsService.listGrants(key.getKeyId(), REGION, null, null, null, null);
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> afterGrants = (List<Map<String, Object>>) after.get("Grants");
        assertTrue(afterGrants.isEmpty());
    }

    @Test
    void retireGrantUnknownTokenThrowsNotFound() {
        AwsException ex = assertThrows(AwsException.class, () ->
                kmsService.retireGrant("nonexistent-token-value", null, null, REGION));

        assertEquals("NotFoundException", ex.getErrorCode());
    }

    @Test
    void retireGrantUnknownKeyThrowsNotFound() {
        AwsException ex = assertThrows(AwsException.class, () ->
                kmsService.retireGrant(null, "non-existent-key", "some-grant-id", REGION));

        assertEquals("NotFoundException", ex.getErrorCode());
    }

    @Test
    void retireGrantMissingAllIdentifiersThrowsValidation() {
        AwsException ex = assertThrows(AwsException.class, () ->
                kmsService.retireGrant(null, null, null, REGION));

        assertEquals("ValidationException", ex.getErrorCode());
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
    void encryptIsNonDeterministic() {
        KmsKey key = kmsService.createKey(null, REGION);
        byte[] plaintext = "hello".getBytes(StandardCharsets.UTF_8);

        byte[] c1 = kmsService.encrypt(key.getKeyId(), plaintext, REGION);
        byte[] c2 = kmsService.encrypt(key.getKeyId(), plaintext, REGION);

        assertFalse(Arrays.equals(c1, c2),
                "two Encrypt calls with identical inputs must yield different ciphertexts");
        // Both still round-trip
        assertArrayEquals(plaintext, kmsService.decrypt(c1, REGION));
        assertArrayEquals(plaintext, kmsService.decrypt(c2, REGION));
    }

    @Test
    void encryptIsNonDeterministicWithIdenticalContext() {
        KmsKey key = kmsService.createKey(null, REGION);
        byte[] plaintext = "hello".getBytes(StandardCharsets.UTF_8);
        Map<String, String> ctx = Map.of("tenant", "1");

        byte[] c1 = kmsService.encrypt(key.getKeyId(), plaintext, ctx, REGION);
        byte[] c2 = kmsService.encrypt(key.getKeyId(), plaintext, ctx, REGION);

        assertFalse(Arrays.equals(c1, c2));
    }

    @Test
    void decryptWithMatchingContextSucceeds() {
        KmsKey key = kmsService.createKey(null, REGION);
        byte[] plaintext = "hello".getBytes(StandardCharsets.UTF_8);
        Map<String, String> ctx = Map.of("tenant", "1", "purpose", "test");

        byte[] ciphertext = kmsService.encrypt(key.getKeyId(), plaintext, ctx, REGION);
        // Different insertion order, same logical context — must succeed (AWS is order-independent)
        Map<String, String> reordered = new LinkedHashMap<>();
        reordered.put("purpose", "test");
        reordered.put("tenant", "1");

        assertArrayEquals(plaintext, kmsService.decrypt(ciphertext, reordered, REGION));
    }

    @Test
    void decryptWithMismatchedContextValueThrows() {
        KmsKey key = kmsService.createKey(null, REGION);
        byte[] ciphertext = kmsService.encrypt(key.getKeyId(),
                "hello".getBytes(StandardCharsets.UTF_8), Map.of("tenant", "1"), REGION);

        AwsException ex = assertThrows(AwsException.class, () ->
                kmsService.decrypt(ciphertext, Map.of("tenant", "999"), REGION));
        assertEquals("InvalidCiphertextException", ex.getErrorCode());
    }

    @Test
    void decryptWithMismatchedContextKeyThrows() {
        KmsKey key = kmsService.createKey(null, REGION);
        byte[] ciphertext = kmsService.encrypt(key.getKeyId(),
                "hello".getBytes(StandardCharsets.UTF_8), Map.of("tenant", "1"), REGION);

        // Same value under a different key — must fail
        assertThrows(AwsException.class, () ->
                kmsService.decrypt(ciphertext, Map.of("account", "1"), REGION));
    }

    @Test
    void decryptWithoutContextRejectsCiphertextEncryptedWithContext() {
        KmsKey key = kmsService.createKey(null, REGION);
        byte[] ciphertext = kmsService.encrypt(key.getKeyId(),
                "hello".getBytes(StandardCharsets.UTF_8), Map.of("tenant", "1"), REGION);

        // Decrypt without supplying the context the ciphertext was bound to — must fail
        assertThrows(AwsException.class, () ->
                kmsService.decrypt(ciphertext, REGION));
        assertThrows(AwsException.class, () ->
                kmsService.decrypt(ciphertext, Map.of(), REGION));
    }

    @Test
    void decryptWithExtraContextKeyThrows() {
        KmsKey key = kmsService.createKey(null, REGION);
        byte[] ciphertext = kmsService.encrypt(key.getKeyId(),
                "hello".getBytes(StandardCharsets.UTF_8), Map.of("tenant", "1"), REGION);

        assertThrows(AwsException.class, () ->
                kmsService.decrypt(ciphertext, Map.of("tenant", "1", "extra", "x"), REGION));
    }

    @Test
    void emptyContextIsInterchangeableWithNull() {
        // AWS treats omitted EncryptionContext and {} the same: both must decrypt
        // a ciphertext that was encrypted without one.
        KmsKey key = kmsService.createKey(null, REGION);
        byte[] plaintext = "hello".getBytes(StandardCharsets.UTF_8);

        byte[] noCtx = kmsService.encrypt(key.getKeyId(), plaintext, REGION);
        assertArrayEquals(plaintext, kmsService.decrypt(noCtx, REGION));
        assertArrayEquals(plaintext, kmsService.decrypt(noCtx, Map.of(), REGION));

        byte[] emptyCtx = kmsService.encrypt(key.getKeyId(), plaintext, Map.of(), REGION);
        assertArrayEquals(plaintext, kmsService.decrypt(emptyCtx, REGION));
        assertArrayEquals(plaintext, kmsService.decrypt(emptyCtx, Map.of(), REGION));
    }

    @Test
    void contextIsCaseSensitive() {
        // AWS performs case-sensitive matching of EncryptionContext keys and values.
        KmsKey key = kmsService.createKey(null, REGION);
        byte[] ciphertext = kmsService.encrypt(key.getKeyId(),
                "hello".getBytes(StandardCharsets.UTF_8), Map.of("Tenant", "Alpha"), REGION);

        assertThrows(AwsException.class, () ->
                kmsService.decrypt(ciphertext, Map.of("tenant", "Alpha"), REGION));
        assertThrows(AwsException.class, () ->
                kmsService.decrypt(ciphertext, Map.of("Tenant", "alpha"), REGION));
        // Exact match still works
        assertNotNull(kmsService.decrypt(ciphertext, Map.of("Tenant", "Alpha"), REGION));
    }

    @Test
    void decryptToKeyArnResolvesKeyFromBlob() {
        KmsKey key = kmsService.createKey(null, REGION);
        byte[] ciphertext = kmsService.encrypt(key.getKeyId(),
                "hello".getBytes(StandardCharsets.UTF_8), Map.of("tenant", "1"), REGION);

        assertEquals(key.getArn(), kmsService.decryptToKeyArn(ciphertext, REGION));
    }

    @Test
    void reEncryptHonorsSourceContext() {
        // Simulates the ReEncrypt flow: decrypt under source ctx, encrypt under dest ctx.
        KmsKey src = kmsService.createKey(null, REGION);
        KmsKey dst = kmsService.createKey(null, REGION);
        byte[] plaintext = "hello".getBytes(StandardCharsets.UTF_8);
        Map<String, String> sourceCtx = Map.of("tenant", "1");
        Map<String, String> destCtx = Map.of("tenant", "2");

        byte[] srcBlob = kmsService.encrypt(src.getKeyId(), plaintext, sourceCtx, REGION);

        // Wrong source context → fails on the decrypt half of ReEncrypt
        assertThrows(AwsException.class, () ->
                kmsService.decrypt(srcBlob, Map.of("tenant", "wrong"), REGION));

        // Correct source context → ReEncrypt round-trips, output is bound to destCtx
        byte[] roundtripped = kmsService.decrypt(srcBlob, sourceCtx, REGION);
        byte[] destBlob = kmsService.encrypt(dst.getKeyId(), roundtripped, destCtx, REGION);

        // New blob requires destCtx, not sourceCtx, to decrypt
        assertThrows(AwsException.class, () ->
                kmsService.decrypt(destBlob, sourceCtx, REGION));
        assertArrayEquals(plaintext, kmsService.decrypt(destBlob, destCtx, REGION));
    }

    @Test
    void v1BlobWithOverrideIdEqualToV2VersionMarkerCollidesAndFails() {
        // Documented limitation: a v1 blob with override-id "v2" looks like "kms:v2:<base64>",
        // which collides with the v2 prefix. Pinned so any change to BLOB_PREFIX_V2 or v2-branch
        // fall-through behavior fails this test loudly instead of silently changing semantics.
        KmsKey key = kmsService.createKey(null, "ENCRYPT_DECRYPT", "SYMMETRIC_DEFAULT", null,
                Map.of("floci:override-id", "v2"), REGION);
        byte[] v1BlobWithV2KeyId = ("kms:v2:"
                + Base64.getEncoder().encodeToString("hello".getBytes(StandardCharsets.UTF_8)))
                .getBytes(StandardCharsets.UTF_8);

        AwsException ex = assertThrows(AwsException.class, () ->
                kmsService.decrypt(v1BlobWithV2KeyId, REGION));
        assertEquals("InvalidCiphertextException", ex.getErrorCode());
        assertEquals("v2", key.getKeyId());
    }

    @Test
    void legacyV1BlobDecryptsForBackCompat() {
        // Persistent stores written before this PR contain kms:<keyId>:<base64> blobs.
        // Decrypt must still accept them (no context binding on v1).
        KmsKey key = kmsService.createKey(null, REGION);
        byte[] plaintext = "hello".getBytes(StandardCharsets.UTF_8);
        byte[] v1Blob = ("kms:" + key.getKeyId() + ":"
                + Base64.getEncoder().encodeToString(plaintext)).getBytes(StandardCharsets.UTF_8);

        assertArrayEquals(plaintext, kmsService.decrypt(v1Blob, REGION));
        assertArrayEquals(plaintext, kmsService.decrypt(v1Blob, Map.of(), REGION));
        // v1 carried no context, so any non-empty context must fail (matches AWS semantics)
        assertThrows(AwsException.class, () ->
                kmsService.decrypt(v1Blob, Map.of("tenant", "1"), REGION));
        assertEquals(key.getArn(), kmsService.decryptToKeyArn(v1Blob, REGION));
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

    // ── Issue #911 — RotateKeyOnDemand ──────────────────────────────────────

    @Test
    void rotateKeyOnDemandReturnsKeyId() {
        KmsKey key = kmsService.createKey(null, REGION);
        String keyId = kmsService.rotateKeyOnDemand(key.getKeyId(), REGION);
        assertEquals(key.getKeyId(), keyId);
    }

    @Test
    void rotateKeyOnDemandOnAsymmetricKeyThrows() {
        KmsKey key = kmsService.createKey("rsa key", "SIGN_VERIFY", "RSA_2048", null, Map.of(), REGION);
        AwsException ex = assertThrows(AwsException.class, () ->
                kmsService.rotateKeyOnDemand(key.getKeyId(), REGION));

        assertEquals("UnsupportedOperationException", ex.getErrorCode());
        assertEquals(400, ex.getHttpStatus());
    }

    @Test
    void rotateKeyOnDemandOnDisabledKeyThrows() {
        KmsKey key = kmsService.createKey(null, REGION);
        key.setEnabled(false);
        AwsException ex = assertThrows(AwsException.class, () ->
                kmsService.rotateKeyOnDemand(key.getKeyId(), REGION));

        assertEquals("DisabledException", ex.getErrorCode());
        assertEquals(400, ex.getHttpStatus());
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

    @ParameterizedTest
    @ValueSource(strings = {"HMAC_224", "HMAC_256", "HMAC_384", "HMAC_512"})
    void generateMacSupportsAllAdvertisedHmacSpecs(String spec) {
        KmsKey key = kmsService.createKey("hmac key", "GENERATE_VERIFY_MAC", spec, null, Map.of(), REGION);
        byte[] message = "floci-mac-probe".getBytes(StandardCharsets.UTF_8);

        byte[] mac = kmsService.generateMac(key.getKeyId(), message, KmsService.macAlgorithmFor(spec), REGION);

        assertEquals(expectedMacByteLength(spec), mac.length);
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

    @Test
    void generateMacWithHmacKeyReturnsDeterministicMac() {
        KmsKey key = kmsService.createKey("hmac key", "GENERATE_VERIFY_MAC", "HMAC_256", null, Map.of(), REGION);
        byte[] message = "floci-mac-probe".getBytes(StandardCharsets.UTF_8);

        byte[] first = kmsService.generateMac(key.getKeyId(), message, "HMAC_SHA_256", REGION);
        byte[] second = kmsService.generateMac(key.getKeyId(), message, "HMAC_SHA_256", REGION);

        assertEquals(32, first.length);
        assertArrayEquals(first, second);
    }

    @Test
    void generateMacRejectsAlgorithmThatDoesNotMatchKeySpec() {
        KmsKey key = kmsService.createKey("hmac key", "GENERATE_VERIFY_MAC", "HMAC_256", null, Map.of(), REGION);
        byte[] message = "floci-mac-probe".getBytes(StandardCharsets.UTF_8);

        AwsException ex = assertThrows(AwsException.class, () ->
                kmsService.generateMac(key.getKeyId(), message, "HMAC_SHA_512", REGION));

        assertEquals("InvalidKeyUsageException", ex.getErrorCode());
    }

    @Test
    void generateMacRejectsMessageOutsideAwsLengthBounds() {
        KmsKey key = kmsService.createKey("hmac key", "GENERATE_VERIFY_MAC", "HMAC_256", null, Map.of(), REGION);

        AwsException empty = assertThrows(AwsException.class, () ->
                kmsService.generateMac(key.getKeyId(), new byte[0], "HMAC_SHA_256", REGION));
        AwsException oversized = assertThrows(AwsException.class, () ->
                kmsService.generateMac(key.getKeyId(), new byte[4097], "HMAC_SHA_256", REGION));

        assertEquals("ValidationException", empty.getErrorCode());
        assertEquals("ValidationException", oversized.getErrorCode());
    }

    @Test
    void verifyMacAcceptsMatchingMacAndRejectsMismatch() {
        KmsKey key = kmsService.createKey("hmac key", "GENERATE_VERIFY_MAC", "HMAC_256", null, Map.of(), REGION);
        byte[] message = "floci-mac-probe".getBytes(StandardCharsets.UTF_8);
        byte[] mac = kmsService.generateMac(key.getKeyId(), message, "HMAC_SHA_256", REGION);

        assertDoesNotThrow(() -> kmsService.verifyMac(key.getKeyId(), message, mac, "HMAC_SHA_256", REGION));

        byte[] tamperedMac = mac.clone();
        tamperedMac[0] ^= 1;
        AwsException ex = assertThrows(AwsException.class, () ->
                kmsService.verifyMac(key.getKeyId(), message, tamperedMac, "HMAC_SHA_256", REGION));

        assertEquals("KMSInvalidMacException", ex.getErrorCode());
    }

    @Test
    void verifyMacRejectsMacOutsideAwsLengthBounds() {
        KmsKey key = kmsService.createKey("hmac key", "GENERATE_VERIFY_MAC", "HMAC_256", null, Map.of(), REGION);
        byte[] message = "floci-mac-probe".getBytes(StandardCharsets.UTF_8);

        AwsException empty = assertThrows(AwsException.class, () ->
                kmsService.verifyMac(key.getKeyId(), message, new byte[0], "HMAC_SHA_256", REGION));
        AwsException oversized = assertThrows(AwsException.class, () ->
                kmsService.verifyMac(key.getKeyId(), message, new byte[6145], "HMAC_SHA_256", REGION));

        assertEquals("ValidationException", empty.getErrorCode());
        assertEquals("ValidationException", oversized.getErrorCode());
    }

    @Test
    void verifyMacRejectsMessageOutsideAwsLengthBounds() {
        KmsKey key = kmsService.createKey("hmac key", "GENERATE_VERIFY_MAC", "HMAC_256", null, Map.of(), REGION);
        byte[] validMessage = "floci-mac-probe".getBytes(StandardCharsets.UTF_8);
        byte[] mac = kmsService.generateMac(key.getKeyId(), validMessage, "HMAC_SHA_256", REGION);

        AwsException empty = assertThrows(AwsException.class, () ->
                kmsService.verifyMac(key.getKeyId(), new byte[0], mac, "HMAC_SHA_256", REGION));
        AwsException oversized = assertThrows(AwsException.class, () ->
                kmsService.verifyMac(key.getKeyId(), new byte[4097], mac, "HMAC_SHA_256", REGION));

        assertEquals("ValidationException", empty.getErrorCode());
        assertEquals("ValidationException", oversized.getErrorCode());
    }

    private static int expectedMacByteLength(String spec) {
        return switch (spec) {
            case "HMAC_224" -> 28;
            case "HMAC_256" -> 32;
            case "HMAC_384" -> 48;
            case "HMAC_512" -> 64;
            default -> -1;
        };
    }
}
