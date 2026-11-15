package io.floci.aws.services.kms;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.floci.aws.core.common.AwsException;
import io.floci.aws.core.common.RegionResolver;
import io.floci.aws.core.storage.InMemoryStorage;
import io.floci.aws.services.kms.model.KmsKey;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.function.Executable;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import javax.crypto.Cipher;
import javax.crypto.spec.OAEPParameterSpec;
import javax.crypto.spec.PSource;
import java.nio.charset.StandardCharsets;
import java.security.KeyFactory;
import java.security.PublicKey;
import java.security.spec.MGF1ParameterSpec;
import java.security.spec.X509EncodedKeySpec;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashSet;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class KmsKeyRotationsServiceTest {
    private static final String REGION = "us-east-1";
    private final InMemoryStorage<String, KmsKey> keys = new InMemoryStorage<>();
    private final KmsService service = new KmsService(keys, new InMemoryStorage<>(),
            new InMemoryStorage<>(), new RegionResolver(REGION, "000000000000"));

    @Test
    void firstMaterialIsOnlyIncludedWhenRequested() {
        KmsKey key = service.createKey("rotation history", REGION);
        assertTrue(list(key, null).items().isEmpty());
        KmsService.Page<KmsService.KeyRotation> all = list(key, "ALL_KEY_MATERIAL");
        assertFalse(all.truncated());
        assertNull(all.nextMarker());
        KmsService.KeyRotation first = all.items().getFirst();
        assertEquals(key.getKeyId(), first.keyId());
        assertTrue(first.keyMaterialId().matches("[a-f0-9]{64}"));
        assertEquals("CURRENT", first.keyMaterialState());
        assertNull(first.rotationDate());
        assertNull(first.importState());
        assertNull(first.expirationModel());
        assertNull(first.validTo());
    }

    @Test
    void rotationsTrackMaterialsWithoutInvalidatingEarlierCiphertexts() {
        KmsKey key = service.createKey("rotation history", REGION);
        String firstMaterial = list(key, "ALL_KEY_MATERIAL").items().getFirst().keyMaterialId();
        byte[] plaintext = "before rotation".getBytes(StandardCharsets.UTF_8);
        byte[] ciphertext = service.encrypt(key.getKeyId(), plaintext, REGION);
        long before = Instant.now().toEpochMilli();
        service.rotateKeyOnDemand(key.getKeyId(), REGION);
        service.rotateKeyOnDemand(key.getKeyId(), REGION);
        long after = Instant.now().toEpochMilli();

        List<KmsService.KeyRotation> rotations = list(key, null).items();
        assertEquals(2, rotations.size());
        assertEquals(rotations, list(key, "ROTATIONS_ONLY").items());
        assertEquals(1, rotations.stream().filter(r -> "CURRENT".equals(r.keyMaterialState())).count());
        for (KmsService.KeyRotation rotation : rotations) {
            assertTrue(rotation.rotationDate() >= before && rotation.rotationDate() <= after);
            assertNotEquals(firstMaterial, rotation.keyMaterialId());
            assertTrue(rotation.keyMaterialId().matches("[a-f0-9]{64}"));
        }
        List<KmsService.KeyRotation> all = list(key, "ALL_KEY_MATERIAL").items();
        assertEquals(3, all.size());
        assertEquals(firstMaterial, all.getFirst().keyMaterialId());
        assertEquals("NON_CURRENT", all.getFirst().keyMaterialState());
        assertNull(all.getFirst().rotationDate());
        assertArrayEquals(plaintext, service.decrypt(ciphertext, REGION));
    }

    @Test
    void paginationWalksEachMaterialOnceAndBindsMarkersToTheKeyAndFilter() {
        KmsKey key = service.createKey("paged history", REGION);
        for (int i = 0; i < 4; i++) {
            service.rotateKeyOnDemand(key.getKeyId(), REGION);
        }
        List<KmsService.KeyRotation> expected = list(key, "ALL_KEY_MATERIAL").items();
        List<KmsService.KeyRotation> walked = new ArrayList<>();
        String marker = null;
        do {
            KmsService.Page<KmsService.KeyRotation> page = service.listKeyRotations(
                    key.getArn(), "ALL_KEY_MATERIAL", REGION, marker, 2);
            assertTrue(page.items().size() <= 2);
            walked.addAll(page.items());
            marker = page.nextMarker();
            assertEquals(marker != null, page.truncated());
        } while (marker != null);
        assertEquals(expected, walked);
        assertEquals(5, new HashSet<>(walked.stream().map(KmsService.KeyRotation::keyMaterialId).toList()).size());

        String issued = service.listKeyRotations(key.getKeyId(), "ALL_KEY_MATERIAL", REGION, null, 1).nextMarker();
        KmsKey other = service.createKey("another key", REGION);
        assertError("InvalidMarkerException", () -> service.listKeyRotations(
                other.getKeyId(), "ALL_KEY_MATERIAL", REGION, issued, 1));
        assertError("InvalidMarkerException", () -> service.listKeyRotations(
                key.getKeyId(), null, REGION, issued, 1));
        assertError("InvalidMarkerException", () -> service.listKeyRotations(
                key.getKeyId(), "ALL_KEY_MATERIAL", REGION, issued + "changed", 1));
    }

    @Test
    void historyAndCiphertextSurviveAStoredKeyRoundTrip() throws Exception {
        KmsKey key = service.createKey("persisted history", REGION);
        byte[] plaintext = "stored ciphertext".getBytes(StandardCharsets.UTF_8);
        byte[] ciphertext = service.encrypt(key.getKeyId(), plaintext, REGION);
        service.rotateKeyOnDemand(key.getKeyId(), REGION);
        List<KmsService.KeyRotation> expected = list(key, "ALL_KEY_MATERIAL").items();
        ObjectMapper mapper = new ObjectMapper();
        KmsKey reloaded = mapper.readValue(mapper.writeValueAsBytes(service.describeKey(key.getKeyId(), REGION)), KmsKey.class);
        keys.put(REGION + "::" + key.getKeyId(), reloaded);

        KmsService restarted = new KmsService(keys, new InMemoryStorage<>(),
                new InMemoryStorage<>(), new RegionResolver(REGION, "000000000000"));
        assertEquals(expected, restarted.listKeyRotations(key.getKeyId(), "ALL_KEY_MATERIAL", REGION, null, null).items());
        assertArrayEquals(plaintext, restarted.decrypt(ciphertext, REGION));
    }

    @Test
    void aLegacySnapshotDoesNotInventRotationDatesOrReplaceItsMaterial() throws Exception {
        KmsKey key = service.createKey("legacy history", REGION);
        service.rotateKeyOnDemand(key.getKeyId(), REGION);
        ObjectMapper mapper = new ObjectMapper();
        String snapshot = mapper.writeValueAsString(service.describeKey(key.getKeyId(), REGION));
        ObjectNode node = (ObjectNode) mapper.readTree(snapshot);
        node.remove("backingKeyRotationDates");
        KmsKey reloaded = mapper.treeToValue(node, KmsKey.class);
        keys.put(REGION + "::" + key.getKeyId(), reloaded);
        Map<String, String> materials = Map.copyOf(reloaded.getBackingKeys());
        assertTrue(list(reloaded, null).items().isEmpty());
        assertEquals(2, list(reloaded, "ALL_KEY_MATERIAL").items().size());
        assertTrue(list(reloaded, "ALL_KEY_MATERIAL").items().stream().allMatch(r -> r.rotationDate() == null));
        assertEquals(materials, reloaded.getBackingKeys());
    }

    @Test
    void primaryRotationHistoryFollowsExistingAndNewReplicas() {
        KmsKey primary = service.createKey("primary", "ENCRYPT_DECRYPT", "SYMMETRIC_DEFAULT",
                null, Map.of(), null, true, REGION);
        service.replicateKey(primary.getKeyId(), null, null, Map.of(), "us-west-2", REGION);
        service.rotateKeyOnDemand(primary.getKeyId(), REGION);
        service.replicateKey(primary.getKeyId(), null, null, Map.of(), "eu-west-1", REGION);
        KmsService.KeyRotation expected = list(primary, null).items().getFirst();
        for (String region : List.of("us-west-2", "eu-west-1")) {
            KmsService.KeyRotation actual = service.listKeyRotations(primary.getKeyId(), null, region, null, null)
                    .items().getFirst();
            assertEquals(expected.keyMaterialId(), actual.keyMaterialId());
            assertEquals(expected.rotationDate(), actual.rotationDate());
            assertEquals("CURRENT", actual.keyMaterialState());
            assertEquals(primary.getKeyId(), actual.keyId());
        }
    }

    @Test
    void disabledAndPendingDeletionKeysRetainTheirHistory() {
        KmsKey key = service.createKey("disabled history", REGION);
        service.rotateKeyOnDemand(key.getKeyId(), REGION);
        List<KmsService.KeyRotation> expected = list(key, null).items();
        service.disableKey(key.getKeyId(), REGION);
        assertEquals(expected, list(key, null).items());
        service.scheduleKeyDeletion(key.getKeyId(), 7, REGION);
        assertEquals(expected, list(key, null).items());
    }

    @Test
    void importedMaterialReportsItsImportAndExpirationState() throws Exception {
        KmsKey key = service.createKey("import history", "ENCRYPT_DECRYPT", "SYMMETRIC_DEFAULT",
                null, Map.of(), "EXTERNAL", REGION);
        assertTrue(list(key, "ALL_KEY_MATERIAL").items().isEmpty());
        KmsService.ImportParameters parameters =
                service.getParametersForImport(key.getKeyId(), "RSAES_OAEP_SHA_256", "RSA_2048", REGION);
        PublicKey wrappingKey = KeyFactory.getInstance("RSA")
                .generatePublic(new X509EncodedKeySpec(Base64.getDecoder().decode(parameters.publicKeyEncoded())));
        Cipher cipher = Cipher.getInstance("RSA/ECB/OAEPPadding");
        cipher.init(Cipher.ENCRYPT_MODE, wrappingKey, new OAEPParameterSpec("SHA-256", "MGF1",
                MGF1ParameterSpec.SHA256, PSource.PSpecified.DEFAULT));
        long validTo = Instant.now().plusSeconds(3600).getEpochSecond();
        KmsKey imported = service.importKeyMaterial(key.getKeyId(), parameters.importToken(),
                cipher.doFinal(new byte[32]), "KEY_MATERIAL_EXPIRES", validTo, null, REGION);

        assertTrue(list(key, null).items().isEmpty());
        KmsService.KeyRotation first = list(key, "ALL_KEY_MATERIAL").items().getFirst();
        assertEquals(imported.getKeyMaterialId(), first.keyMaterialId());
        assertEquals("IMPORTED", first.importState());
        assertEquals("KEY_MATERIAL_EXPIRES", first.expirationModel());
        assertEquals(validTo, first.validTo());
        assertNull(first.rotationDate());
        service.deleteImportedKeyMaterial(key.getKeyId(), REGION);
        KmsService.KeyRotation deleted = list(key, "ALL_KEY_MATERIAL").items().getFirst();
        assertEquals(first.keyMaterialId(), deleted.keyMaterialId());
        assertEquals(key.getKeyId(), deleted.keyId());
        assertEquals(deleted, service.listKeyRotations(key.getArn(), "ALL_KEY_MATERIAL", REGION, null, null)
                .items().getFirst());
        assertEquals("PENDING_IMPORT", deleted.importState());
        assertTrue(list(key, null).items().isEmpty());
    }

    @Test
    void unrotatableKeysHaveNoHistoryAndRejectIncludeKeyMaterial() {
        KmsKey key = service.createKey("hmac history", "GENERATE_VERIFY_MAC", "HMAC_256",
                null, Map.of(), REGION);
        assertTrue(list(key, null).items().isEmpty());
        assertError("UnsupportedOperationException", () -> list(key, "ALL_KEY_MATERIAL"));
        assertError("UnsupportedOperationException", () -> list(key, "ROTATIONS_ONLY"));
    }

    @ParameterizedTest
    @ValueSource(ints = {0, 1001})
    void invalidPageSizeIsRejected(int limit) {
        KmsKey key = service.createKey("limit", REGION);
        assertError("ValidationException", () -> service.listKeyRotations(key.getKeyId(), null, REGION, null, limit));
    }

    @Test
    void invalidFilterAndMissingKeyAreRejected() {
        KmsKey key = service.createKey("filter", REGION);
        assertError("ValidationException", () -> list(key, "everything"));
        assertError("NotFoundException", () -> service.listKeyRotations(
                "00000000-0000-0000-0000-000000000000", null, REGION, null, null));
    }

    private KmsService.Page<KmsService.KeyRotation> list(KmsKey key, String include) {
        return service.listKeyRotations(key.getKeyId(), include, REGION, null, null);
    }

    private static void assertError(String code, Executable call) {
        assertEquals(code, assertThrows(AwsException.class, call).getErrorCode());
    }
}
