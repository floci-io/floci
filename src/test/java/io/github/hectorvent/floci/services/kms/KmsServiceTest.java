package io.github.hectorvent.floci.services.kms;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.core.common.RegionResolver;
import io.github.hectorvent.floci.core.common.ReservedTags;
import io.github.hectorvent.floci.core.storage.InMemoryStorage;
import io.github.hectorvent.floci.services.kms.model.KmsAlias;
import io.github.hectorvent.floci.services.kms.model.KmsGrant;
import io.github.hectorvent.floci.services.kms.model.KmsKey;
import io.github.hectorvent.floci.services.kms.model.KmsKeySpec;
import io.github.hectorvent.floci.services.kms.model.KmsKeyUsage;
import io.github.hectorvent.floci.services.kms.model.KmsMessageType;
import jakarta.ws.rs.core.Response;
import org.bouncycastle.asn1.ASN1Encodable;
import org.bouncycastle.asn1.ASN1EncodableVector;
import org.bouncycastle.asn1.ASN1Integer;
import org.bouncycastle.asn1.DERBitString;
import org.bouncycastle.asn1.DEROctetString;
import org.bouncycastle.asn1.DERSequence;
import org.bouncycastle.asn1.DERTaggedObject;
import org.bouncycastle.asn1.pkcs.PrivateKeyInfo;
import org.bouncycastle.asn1.sec.ECPrivateKey;
import org.bouncycastle.asn1.sec.SECObjectIdentifiers;
import org.bouncycastle.asn1.x509.AlgorithmIdentifier;
import org.bouncycastle.asn1.x509.SubjectPublicKeyInfo;
import org.bouncycastle.asn1.x9.ECNamedCurveTable;
import org.bouncycastle.asn1.x9.X962Parameters;
import org.bouncycastle.asn1.x9.X9ObjectIdentifiers;
import org.bouncycastle.jce.provider.BouncyCastleProvider;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import javax.crypto.Cipher;
import javax.crypto.Mac;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.OAEPParameterSpec;
import javax.crypto.spec.PSource;
import javax.crypto.spec.SecretKeySpec;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.security.KeyFactory;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.MessageDigest;
import java.security.PublicKey;
import java.security.Security;
import java.security.Signature;
import java.security.spec.ECGenParameterSpec;
import java.security.spec.MGF1ParameterSpec;
import java.security.spec.PSSParameterSpec;
import java.security.spec.X509EncodedKeySpec;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import static org.junit.jupiter.api.Assertions.*;

class KmsServiceTest {

    private static final String REGION = "us-east-1";

    private KmsService kmsService;
    private InMemoryStorage<String, KmsKey> keyStore;

    @BeforeAll
    static void registerBouncyCastle() {
        if (Security.getProvider(BouncyCastleProvider.PROVIDER_NAME) == null) {
            Security.addProvider(new BouncyCastleProvider());
        }
    }

    @BeforeEach
    void setUp() {
        keyStore = new InMemoryStorage<>();
        kmsService = new KmsService(
                keyStore,
                new InMemoryStorage<>(),
                new InMemoryStorage<>(),
                new RegionResolver("us-east-1", "000000000000")
        );
    }

    /**
     * Round-trip in a non-commercial partition. The key ARN a GovCloud or China deployment mints
     * carries that partition, and resolving a key by ARN used to test
     * {@code startsWith("arn:aws:kms:")}, so the emulator refused to find a key it had just
     * created and reported NotFound for a key that exists.
     */
    @Test
    void resolvesAKeyByItsOwnArnInAnyPartition() {
        for (String region : List.of("us-gov-west-1", "cn-north-1", "us-east-1")) {
            KmsKey created = kmsService.createKey("partition key", region);

            assertEquals(created.getKeyId(), kmsService.describeKey(created.getArn(), region).getKeyId(),
                    "key in " + region + " was not resolvable by its own ARN " + created.getArn());
        }
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
    void createMultiRegionKeyUsesMrkIdAndPrimaryMetadata() {
        KmsKey key = kmsService.createKey("multi-region key", "ENCRYPT_DECRYPT", "SYMMETRIC_DEFAULT",
                null, Map.of(), null, true, REGION);

        assertTrue(key.isMultiRegion());
        assertTrue(key.getKeyId().startsWith("mrk-"));
        assertEquals("PRIMARY", key.getMultiRegionKeyType());
        assertEquals(REGION, key.getMultiRegionPrimaryRegion());
    }

    @Test
    void replicateMultiRegionKeySharesKeyMaterialAndIdentity() {
        KmsKey primary = kmsService.createKey("primary", "ENCRYPT_DECRYPT", "SYMMETRIC_DEFAULT",
                null, Map.of(), null, true, REGION);

        KmsKey replica = kmsService.replicateKey(primary.getKeyId(), "replica", null,
                Map.of("environment", "test"), "us-west-2", REGION);

        assertEquals(primary.getKeyId(), replica.getKeyId());
        assertEquals("us-west-2", replica.getArn().split(":", 6)[3]);
        assertEquals("REPLICA", replica.getMultiRegionKeyType());
        assertEquals(REGION, replica.getMultiRegionPrimaryRegion());
        assertEquals(primary.getBackingKeys(), replica.getBackingKeys());
        assertEquals(replica, kmsService.describeKey(primary.getKeyId(), "us-west-2"));
    }

    @Test
    void replicaDecryptsCiphertextCreatedByPrimary() {
        KmsKey primary = kmsService.createKey("primary", "ENCRYPT_DECRYPT", "SYMMETRIC_DEFAULT",
                null, Map.of(), null, true, REGION);
        kmsService.replicateKey(primary.getKeyId(), "replica", null, Map.of(), "us-west-2", REGION);
        byte[] plaintext = "multi-region payload".getBytes(StandardCharsets.UTF_8);

        byte[] ciphertext = kmsService.encrypt(primary.getKeyId(), plaintext, REGION);
        KmsService.DecryptResult result = kmsService.decryptAndResolveKey(
                ciphertext, Map.of(), "us-west-2", primary.getKeyId());

        assertArrayEquals(plaintext, result.plaintext());
        assertEquals("arn:aws:kms:us-west-2:000000000000:key/" + primary.getKeyId(), result.keyArn());
    }

    @Test
    void rotatingPrimarySynchronizesBackingMaterialToReplica() {
        KmsKey primary = kmsService.createKey("primary", "ENCRYPT_DECRYPT", "SYMMETRIC_DEFAULT",
                null, Map.of(), null, true, REGION);
        kmsService.replicateKey(primary.getKeyId(), "replica", null, Map.of(), "us-west-2", REGION);

        byte[] beforePlaintext = "before".getBytes(StandardCharsets.UTF_8);
        byte[] afterPlaintext = "after".getBytes(StandardCharsets.UTF_8);
        byte[] beforeRotation = kmsService.encrypt(primary.getKeyId(), beforePlaintext, REGION);
        kmsService.rotateKeyOnDemand(primary.getKeyId(), REGION);
        byte[] afterRotation = kmsService.encrypt(primary.getKeyId(), afterPlaintext, REGION);

        assertArrayEquals(beforePlaintext, kmsService.decrypt(beforeRotation, "us-west-2"));
        assertArrayEquals(afterPlaintext, kmsService.decrypt(afterRotation, "us-west-2"));

        KmsKey storedPrimary = keyStore.get(REGION + "::" + primary.getKeyId()).orElseThrow();
        KmsKey storedReplica = keyStore.get("us-west-2::" + primary.getKeyId()).orElseThrow();
        assertEquals(storedPrimary.getBackingKeys(), storedReplica.getBackingKeys());
        assertEquals(storedPrimary.getCurrentBackingKeyId(), storedReplica.getCurrentBackingKeyId());
    }

    @Test
    void rotatingMultiRegionReplicaIsRejected() {
        KmsKey primary = kmsService.createKey("primary", "ENCRYPT_DECRYPT", "SYMMETRIC_DEFAULT",
                null, Map.of(), null, true, REGION);
        KmsKey replica = kmsService.replicateKey(primary.getKeyId(), "replica", null, Map.of(), "us-west-2", REGION);

        AwsException exception = assertThrows(AwsException.class,
                () -> kmsService.rotateKeyOnDemand(replica.getKeyId(), "us-west-2"));

        assertEquals("UnsupportedOperationException", exception.getErrorCode());
    }

    @Test
    void missingPrimaryUsesFallbackArnInThePrimaryRegion() {
        KmsKey primary = kmsService.createKey("primary", "ENCRYPT_DECRYPT", "SYMMETRIC_DEFAULT",
                null, Map.of(), null, true, REGION);
        KmsKey replica = kmsService.replicateKey(primary.getKeyId(), "replica", null, Map.of(), "us-west-2", REGION);
        keyStore.delete(REGION + "::" + primary.getKeyId());

        ObjectMapper objectMapper = new ObjectMapper();
        KmsJsonHandler handler = new KmsJsonHandler(kmsService, objectMapper,
                new RegionResolver("us-east-1", "000000000000"));
        ObjectNode request = objectMapper.createObjectNode();
        request.put("KeyId", replica.getKeyId());

        Response response = handler.handle("DescribeKey", request, "us-west-2");
        JsonNode body = (JsonNode) response.getEntity();
        JsonNode primaryKey = body.path("KeyMetadata").path("MultiRegionConfiguration").path("PrimaryKey");

        assertEquals("us-east-1", primaryKey.path("Region").asText());
        assertEquals("arn:aws:kms:us-east-1:000000000000:key/" + primary.getKeyId(),
                primaryKey.path("Arn").asText());
    }

    @Test
    void replicateNonMultiRegionKeyIsRejected() {
        KmsKey key = kmsService.createKey("regional", REGION);

        AwsException exception = assertThrows(AwsException.class, () ->
                kmsService.replicateKey(key.getKeyId(), null, null, Map.of(), "us-west-2", REGION));

        assertEquals("UnsupportedOperationException", exception.getErrorCode());
    }

    @Test
    void replicateDisabledMultiRegionKeyIsRejected() {
        KmsKey primary = kmsService.createKey("primary", "ENCRYPT_DECRYPT", "SYMMETRIC_DEFAULT",
                null, Map.of(), null, true, REGION);
        kmsService.disableKey(primary.getKeyId(), REGION);

        AwsException exception = assertThrows(AwsException.class, () ->
                kmsService.replicateKey(primary.getKeyId(), null, null, Map.of(), "us-west-2", REGION));

        assertEquals("DisabledException", exception.getErrorCode());
    }

    @Test
    void replicateMultiRegionKeyPendingDeletionIsRejected() {
        KmsKey primary = kmsService.createKey("primary", "ENCRYPT_DECRYPT", "SYMMETRIC_DEFAULT",
                null, Map.of(), null, true, REGION);
        kmsService.scheduleKeyDeletion(primary.getKeyId(), 7, REGION);

        AwsException exception = assertThrows(AwsException.class, () ->
                kmsService.replicateKey(primary.getKeyId(), null, null, Map.of(), "us-west-2", REGION));

        assertEquals("KMSInvalidStateException", exception.getErrorCode());
    }

    @Test
    void replicateMultiRegionKeyPendingImportIsRejected() {
        KmsKey primary = kmsService.createKey("imported primary", "ENCRYPT_DECRYPT", "SYMMETRIC_DEFAULT",
                null, Map.of(), "EXTERNAL", true, REGION);

        AwsException exception = assertThrows(AwsException.class, () ->
                kmsService.replicateKey(primary.getKeyId(), null, null, Map.of(), "us-west-2", REGION));

        assertEquals("KMSInvalidStateException", exception.getErrorCode());
    }

    @Test
    void replicateToPrimaryRegionIsReportedAsAlreadyExisting() {
        KmsKey primary = kmsService.createKey("primary", "ENCRYPT_DECRYPT", "SYMMETRIC_DEFAULT",
                null, Map.of(), null, true, REGION);

        AwsException exception = assertThrows(AwsException.class, () ->
                kmsService.replicateKey(primary.getKeyId(), null, null, Map.of(), REGION, REGION));

        assertEquals("AlreadyExistsException", exception.getErrorCode());
    }

    @Test
    void replicateAcrossPartitionsIsRejectedAsUnsupported() {
        KmsKey primary = kmsService.createKey("primary", "ENCRYPT_DECRYPT", "SYMMETRIC_DEFAULT",
                null, Map.of(), null, true, REGION);

        AwsException exception = assertThrows(AwsException.class, () ->
                kmsService.replicateKey(primary.getKeyId(), null, null, Map.of(), "cn-north-1", REGION));

        assertEquals("UnsupportedOperationException", exception.getErrorCode());
    }

    @ParameterizedTest
    @ValueSource(strings = {"us-east-1", "cn-fake-1"})
    void createSm2KeyReportsRegionUnsupportedOperation(String region) {
        AwsException exception = assertThrows(AwsException.class, () ->
                kmsService.createKey(null, "SIGN_VERIFY", "SM2", null, Map.of(), region));

        assertEquals("UnsupportedOperationException", exception.getErrorCode());
        assertEquals("KeySpec SM2 is not supported in this Region", exception.getMessage());
        assertEquals(400, exception.getHttpStatus());
    }

    @ParameterizedTest
    @ValueSource(strings = {"cn-north-1", "cn-northwest-1"})
    void createSm2KeySucceedsInChinaRegions(String region) {
        KmsKey key = kmsService.createKey(null, "SIGN_VERIFY", "SM2", null, Map.of(), region);
        byte[] message = "SM2 regional signing".getBytes(StandardCharsets.UTF_8);
        byte[] signature = kmsService.sign(key.getKeyId(), message, "SM2DSA", region);

        assertEquals(KmsKeySpec.SM2, key.getKeySpec());
        assertEquals(KmsKeyUsage.SIGN_VERIFY, key.getKeyUsage());
        assertNotNull(key.getPrivateKeyEncoded());
        assertNotNull(key.getPublicKeyEncoded());
        assertDoesNotThrow(() -> kmsService.verify(key.getKeyId(), message, signature, "SM2DSA", region));
    }

    @Test
    void updateKeyDescriptionPersistsDescription() {
        KmsKey key = kmsService.createKey("old description", REGION);

        kmsService.updateKeyDescription(key.getKeyId(), "new description", REGION);

        KmsKey updated = kmsService.describeKey(key.getKeyId(), REGION);
        assertEquals("new description", updated.getDescription());
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
    void grantTokensAreUniqueUrlSafeBase64Of32Bytes() {
        KmsKey key = kmsService.createKey("csprng grant key", REGION);

        KmsGrant first = kmsService.createGrant(key.getKeyId(),
                "arn:aws:iam::000000000000:user/grantee", List.of("Encrypt"), REGION);
        KmsGrant second = kmsService.createGrant(key.getKeyId(),
                "arn:aws:iam::000000000000:user/grantee", List.of("Encrypt"), REGION);

        assertEquals(43, first.getGrantToken().length());
        assertNotEquals(first.getGrantToken(), second.getGrantToken());
    }

    @Test
    void generatedDataKeyPlaintextsAreUniqueAndRequestedLength() {
        KmsKey key = kmsService.createKey("csprng data key", REGION);

        byte[] first = (byte[]) kmsService.generateDataKey(key.getKeyId(), "AES_256", null, REGION).get("Plaintext");
        byte[] second = (byte[]) kmsService.generateDataKey(key.getKeyId(), "AES_256", null, REGION).get("Plaintext");

        assertEquals(32, first.length);
        assertFalse(Arrays.equals(first, second));
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
    void createGrantUnknownOperationThrowsValidation() {
        KmsKey key = kmsService.createKey("grant key", REGION);

        AwsException ex = assertThrows(AwsException.class, () ->
                kmsService.createGrant(key.getKeyId(), "arn:aws:iam::000000000000:user/grantee",
                        List.of("Encrypt", "NotARealOperation"), REGION));

        assertEquals("ValidationException", ex.getErrorCode());
        assertTrue(ex.getMessage().contains("NotARealOperation"),
                "message should name the rejected operation, was: " + ex.getMessage());
    }

    @Test
    void createGrantInvalidNameCharacterThrowsValidation() {
        KmsKey key = kmsService.createKey("grant key", REGION);

        AwsException ex = assertThrows(AwsException.class, () ->
                kmsService.createGrant(key.getKeyId(), "arn:aws:iam::000000000000:user/grantee",
                        List.of("Encrypt"), null, "invalid name with spaces", null, REGION));

        assertEquals("ValidationException", ex.getErrorCode());
        assertTrue(ex.getMessage().contains("'name'"),
                "message should name the rejected field, was: " + ex.getMessage());
    }

    @Test
    void createGrantNameTooLongThrowsValidation() {
        KmsKey key = kmsService.createKey("grant key", REGION);
        String tooLong = "a".repeat(257);

        AwsException ex = assertThrows(AwsException.class, () ->
                kmsService.createGrant(key.getKeyId(), "arn:aws:iam::000000000000:user/grantee",
                        List.of("Encrypt"), null, tooLong, null, REGION));

        assertEquals("ValidationException", ex.getErrorCode());
        assertTrue(ex.getMessage().contains("less than or equal to 256"),
                "too-long-name message should cite the max-length constraint, was: " + ex.getMessage());
    }

    @Test
    void createGrantEmptyNameThrowsValidationWithMinLengthMessage() {
        KmsKey key = kmsService.createKey("grant key", REGION);

        AwsException ex = assertThrows(AwsException.class, () ->
                kmsService.createGrant(key.getKeyId(), "arn:aws:iam::000000000000:user/grantee",
                        List.of("Encrypt"), null, "", null, REGION));

        assertEquals("ValidationException", ex.getErrorCode());
        assertTrue(ex.getMessage().contains("greater than or equal to 1"),
                "empty-name message should cite the min-length constraint, was: " + ex.getMessage());
    }

    @Test
    void createGrantConstraintsUnknownMemberThrowsValidation() {
        KmsKey key = kmsService.createKey("grant key", REGION);
        Map<String, Object> constraints = new LinkedHashMap<>();
        constraints.put("NotARealConstraint", "value");

        AwsException ex = assertThrows(AwsException.class, () ->
                kmsService.createGrant(key.getKeyId(), "arn:aws:iam::000000000000:user/grantee",
                        List.of("Encrypt"), null, null, constraints, REGION));

        assertEquals("ValidationException", ex.getErrorCode());
    }

    @Test
    void createGrantConstraintsStringValuedEncryptionContextEqualsThrowsValidation() {
        KmsKey key = kmsService.createKey("grant key", REGION);
        Map<String, Object> constraints = new LinkedHashMap<>();
        constraints.put("EncryptionContextEquals", "not-a-map");

        AwsException ex = assertThrows(AwsException.class, () ->
                kmsService.createGrant(key.getKeyId(), "arn:aws:iam::000000000000:user/grantee",
                        List.of("Encrypt"), null, null, constraints, REGION));

        assertEquals("ValidationException", ex.getErrorCode());
    }

    @Test
    void createGrantConstraintsInvalidSourceArnThrowsValidation() {
        KmsKey key = kmsService.createKey("grant key", REGION);
        Map<String, Object> constraints = new LinkedHashMap<>();
        constraints.put("SourceArn", "not-an-arn");

        AwsException ex = assertThrows(AwsException.class, () ->
                kmsService.createGrant(key.getKeyId(), "arn:aws:iam::000000000000:user/grantee",
                        List.of("Encrypt"), null, null, constraints, REGION));

        assertEquals("ValidationException", ex.getErrorCode());
    }

    @Test
    void createGrantConstraintsValidEncryptionContextRoundTrips() {
        KmsKey key = kmsService.createKey("grant key", REGION);
        Map<String, Object> constraints = new LinkedHashMap<>();
        Map<String, String> encryptionContext = new LinkedHashMap<>();
        encryptionContext.put("purpose", "test");
        constraints.put("EncryptionContextEquals", encryptionContext);
        constraints.put("SourceArn", "arn:aws:iam::123456789012:role/test-role");

        KmsGrant grant = kmsService.createGrant(key.getKeyId(), "arn:aws:iam::000000000000:user/grantee",
                List.of("Encrypt"), null, null, constraints, REGION);

        assertEquals(constraints, grant.getConstraints());
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
    void updateAlias() {
        KmsKey keyA = kmsService.createKey(null, REGION);
        KmsKey keyB = kmsService.createKey(null, REGION);
        kmsService.createAlias("alias/my-key", keyA.getKeyId(), REGION);

        kmsService.updateAlias("alias/my-key", keyB.getKeyId(), REGION);

        List<KmsAlias> aliases = kmsService.listAliases(REGION);
        assertEquals(1, aliases.size());
        assertEquals(keyB.getKeyId(), aliases.getFirst().getTargetKeyId());
    }

    @Test
    void updateAliasNotFoundThrows() {
        KmsKey key = kmsService.createKey(null, REGION);
        AwsException ex = assertThrows(AwsException.class, () ->
                kmsService.updateAlias("alias/missing", key.getKeyId(), REGION));

        assertEquals("NotFoundException", ex.getErrorCode());
    }

    @Test
    void updateAliasForNonExistentTargetKeyThrows() {
        KmsKey key = kmsService.createKey(null, REGION);
        kmsService.createAlias("alias/my-key", key.getKeyId(), REGION);

        AwsException ex = assertThrows(AwsException.class, () ->
                kmsService.updateAlias("alias/my-key", "no-such-key", REGION));

        assertEquals("NotFoundException", ex.getErrorCode());
        assertEquals(key.getKeyId(), kmsService.listAliases(REGION).getFirst().getTargetKeyId());
    }

    @Test
    void updateAliasForPendingDeletionTargetThrows() {
        KmsKey keyA = kmsService.createKey(null, REGION);
        KmsKey keyB = kmsService.createKey(null, REGION);
        kmsService.createAlias("alias/my-key", keyA.getKeyId(), REGION);
        kmsService.scheduleKeyDeletion(keyB.getKeyId(), 7, REGION);

        AwsException ex = assertThrows(AwsException.class, () ->
                kmsService.updateAlias("alias/my-key", keyB.getKeyId(), REGION));

        assertEquals("KMSInvalidStateException", ex.getErrorCode());
        assertEquals(keyA.getKeyId(), kmsService.listAliases(REGION).getFirst().getTargetKeyId());
    }

    @Test
    void updateAliasForIncompatibleKeyUsageThrows() {
        KmsKey symmetricKey = kmsService.createKey(null, REGION);
        KmsKey hmacKey = kmsService.createKey("hmac key", "GENERATE_VERIFY_MAC", "HMAC_256", null, Map.of(), REGION);
        kmsService.createAlias("alias/my-key", symmetricKey.getKeyId(), REGION);

        AwsException ex = assertThrows(AwsException.class, () ->
                kmsService.updateAlias("alias/my-key", hmacKey.getKeyId(), REGION));

        assertEquals("ValidationException", ex.getErrorCode());
        assertEquals(symmetricKey.getKeyId(), kmsService.listAliases(REGION).getFirst().getTargetKeyId());
    }

    @Test
    void updateAliasAllowsSameTypeDifferentSpec() {
        KmsKey rsa2048 = kmsService.createKey("rsa 2048", "SIGN_VERIFY", "RSA_2048", null, Map.of(), REGION);
        KmsKey rsa3072 = kmsService.createKey("rsa 3072", "SIGN_VERIFY", "RSA_3072", null, Map.of(), REGION);
        kmsService.createAlias("alias/my-key", rsa2048.getKeyId(), REGION);

        kmsService.updateAlias("alias/my-key", rsa3072.getKeyId(), REGION);

        assertEquals(rsa3072.getKeyId(), kmsService.listAliases(REGION).getFirst().getTargetKeyId());
    }

    @Test
    void updateAliasAllowsSameUsageDifferentAsymmetricFamily() {
        KmsKey rsaKey = kmsService.createKey("rsa key", "SIGN_VERIFY", "RSA_2048", null, Map.of(), REGION);
        KmsKey eccKey = kmsService.createKey("ecc key", "SIGN_VERIFY", "ECC_NIST_P256", null, Map.of(), REGION);
        kmsService.createAlias("alias/my-key", rsaKey.getKeyId(), REGION);

        kmsService.updateAlias("alias/my-key", eccKey.getKeyId(), REGION);

        assertEquals(eccKey.getKeyId(), kmsService.listAliases(REGION).getFirst().getTargetKeyId());
    }

    @Test
    void updateAliasRejectsSymmetricToAsymmetricEvenWithSameUsage() {
        KmsKey symmetricKey = kmsService.createKey(null, REGION);
        KmsKey rsaEncryptKey = kmsService.createKey("rsa encrypt key", "ENCRYPT_DECRYPT", "RSA_2048", null, Map.of(), REGION);
        kmsService.createAlias("alias/my-key", symmetricKey.getKeyId(), REGION);

        AwsException ex = assertThrows(AwsException.class, () ->
                kmsService.updateAlias("alias/my-key", rsaEncryptKey.getKeyId(), REGION));

        assertEquals("ValidationException", ex.getErrorCode());
        assertEquals(symmetricKey.getKeyId(), kmsService.listAliases(REGION).getFirst().getTargetKeyId());
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
    void resolveKeyByAliasCreatedWithArn() {
        KmsKey key = kmsService.createKey(null, REGION);
        kmsService.createAlias("alias/by-arn", key.getArn(), REGION);

        KmsKey resolved = kmsService.describeKey("alias/by-arn", REGION);
        assertEquals(key.getKeyId(), resolved.getKeyId());
    }

    @Test
    void listAliasesFilteredByKeyId() {
        KmsKey key1 = kmsService.createKey(null, REGION);
        KmsKey key2 = kmsService.createKey(null, REGION);
        kmsService.createAlias("alias/key1-a", key1.getKeyId(), REGION);
        kmsService.createAlias("alias/key1-b", key1.getKeyId(), REGION);
        kmsService.createAlias("alias/key2-a", key2.getKeyId(), REGION);

        List<KmsAlias> filtered = kmsService.listAliases(key1.getKeyId(), REGION);
        assertEquals(2, filtered.size());
        assertTrue(filtered.stream().allMatch(a -> key1.getKeyId().equals(a.getTargetKeyId())));
    }

    @Test
    void listAliasesFilteredByKeyIdWithArn() {
        KmsKey key1 = kmsService.createKey(null, REGION);
        KmsKey key2 = kmsService.createKey(null, REGION);
        kmsService.createAlias("alias/key1-a", key1.getKeyId(), REGION);
        kmsService.createAlias("alias/key2-a", key2.getKeyId(), REGION);

        List<KmsAlias> filtered = kmsService.listAliases(key1.getArn(), REGION);
        assertEquals(1, filtered.size());
        assertEquals("alias/key1-a", filtered.getFirst().getAliasName());
    }

    @Test
    void listAliasesFilteredByKeyIdReturnsEmptyWhenNoAliases() {
        KmsKey key1 = kmsService.createKey(null, REGION);
        KmsKey key2 = kmsService.createKey(null, REGION);
        kmsService.createAlias("alias/key2-a", key2.getKeyId(), REGION);

        List<KmsAlias> filtered = kmsService.listAliases(key1.getKeyId(), REGION);
        assertTrue(filtered.isEmpty());
    }

    @Test
    void listAliasesFilteredByKeyIdWithAliasCreatedByArn() {
        KmsKey key = kmsService.createKey(null, REGION);
        kmsService.createAlias("alias/by-arn", key.getArn(), REGION);

        List<KmsAlias> filtered = kmsService.listAliases(key.getKeyId(), REGION);
        assertEquals(1, filtered.size());
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
    void encryptWithDisabledKeyThrowsDisabledException() {
        KmsKey key = kmsService.createKey(null, REGION);
        kmsService.disableKey(key.getKeyId(), REGION);

        AwsException ex = assertThrows(AwsException.class, () ->
                kmsService.encrypt(key.getKeyId(), "hello".getBytes(StandardCharsets.UTF_8), REGION));

        assertEquals("DisabledException", ex.getErrorCode());
        assertEquals(400, ex.getHttpStatus());
    }

    @Test
    void encryptWithPendingDeletionKeyThrowsKmsInvalidStateException() {
        KmsKey key = kmsService.createKey(null, REGION);
        kmsService.scheduleKeyDeletion(key.getKeyId(), 7, REGION);

        AwsException ex = assertThrows(AwsException.class, () ->
                kmsService.encrypt(key.getKeyId(), "hello".getBytes(StandardCharsets.UTF_8), REGION));

        assertEquals("KMSInvalidStateException", ex.getErrorCode());
        assertEquals(400, ex.getHttpStatus());
    }

    /**
     * RSAES-OAEP on RSA keys, issue #3024. Expected ciphertext sizes, error codes and
     * messages were measured against real AWS KMS in us-east-1.
     */
    @Nested
    class RsaEncryptDecryptTests {

        private static final byte[] PLAINTEXT = "secret payload".getBytes(StandardCharsets.UTF_8);

        private KmsKey createRsaKey() {
            return kmsService.createKey("rsa key", "ENCRYPT_DECRYPT", "RSA_2048", null, Map.of(), REGION);
        }

        @Test
        void oaepSha256RoundTripProducesRawRsaCiphertext() {
            KmsKey key = createRsaKey();

            KmsService.EncryptResult encrypted =
                    kmsService.encrypt(key.getKeyId(), PLAINTEXT, Map.of(), "RSAES_OAEP_SHA_256", REGION);

            assertEquals(256, encrypted.ciphertext().length);
            assertEquals(key.getArn(), encrypted.keyArn());
            assertEquals("RSAES_OAEP_SHA_256", encrypted.encryptionAlgorithm());

            KmsService.DecryptResult decrypted = kmsService.decryptAndResolveKey(
                    encrypted.ciphertext(), Map.of(), REGION, key.getKeyId(), "RSAES_OAEP_SHA_256");

            assertArrayEquals(PLAINTEXT, decrypted.plaintext());
            assertEquals(key.getArn(), decrypted.keyArn());
            assertEquals("RSAES_OAEP_SHA_256", decrypted.encryptionAlgorithm());
        }

        @Test
        void oaepSha1RoundTripAllowsUpTo214Bytes() {
            KmsKey key = createRsaKey();
            byte[] plaintext = new byte[214];

            KmsService.EncryptResult encrypted =
                    kmsService.encrypt(key.getKeyId(), plaintext, Map.of(), "RSAES_OAEP_SHA_1", REGION);
            KmsService.DecryptResult decrypted = kmsService.decryptAndResolveKey(
                    encrypted.ciphertext(), Map.of(), REGION, key.getKeyId(), "RSAES_OAEP_SHA_1");

            assertEquals(256, encrypted.ciphertext().length);
            assertArrayEquals(plaintext, decrypted.plaintext());
        }

        @Test
        void decryptAcceptsCiphertextMadeLocallyWithThePublicKey() throws Exception {
            KmsKey key = createRsaKey();
            var publicKey = KeyFactory.getInstance("RSA").generatePublic(new X509EncodedKeySpec(
                    Base64.getDecoder().decode(kmsService.getPublicKey(key.getKeyId(), REGION).getPublicKeyEncoded())));
            var cipher = javax.crypto.Cipher.getInstance("RSA/ECB/OAEPPadding");
            cipher.init(javax.crypto.Cipher.ENCRYPT_MODE, publicKey, new javax.crypto.spec.OAEPParameterSpec(
                    "SHA-256", "MGF1", MGF1ParameterSpec.SHA256, javax.crypto.spec.PSource.PSpecified.DEFAULT));
            byte[] localCiphertext = cipher.doFinal(PLAINTEXT);

            KmsService.DecryptResult decrypted = kmsService.decryptAndResolveKey(
                    localCiphertext, Map.of(), REGION, key.getKeyId(), "RSAES_OAEP_SHA_256");

            assertArrayEquals(PLAINTEXT, decrypted.plaintext());
        }

        @Test
        void encryptWithDefaultAlgorithmThrowsInvalidKeyUsage() {
            KmsKey key = createRsaKey();

            AwsException ex = assertThrows(AwsException.class, () ->
                    kmsService.encrypt(key.getKeyId(), PLAINTEXT, Map.of(), null, REGION));

            assertEquals("InvalidKeyUsageException", ex.getErrorCode());
            assertEquals("Algorithm SYMMETRIC_DEFAULT is incompatible with key spec RSA_2048.", ex.getMessage());
        }

        @Test
        void encryptOnSymmetricKeyWithRsaAlgorithmThrowsInvalidKeyUsage() {
            KmsKey key = kmsService.createKey(null, REGION);

            AwsException ex = assertThrows(AwsException.class, () ->
                    kmsService.encrypt(key.getKeyId(), PLAINTEXT, Map.of(), "RSAES_OAEP_SHA_256", REGION));

            assertEquals("InvalidKeyUsageException", ex.getErrorCode());
            assertEquals("Algorithm RSAES_OAEP_SHA_256 is incompatible with key spec SYMMETRIC_DEFAULT.",
                    ex.getMessage());
        }

        @Test
        void encryptWithSignVerifyKeyThrowsInvalidKeyUsage() {
            KmsKey key = kmsService.createKey("sign key", "SIGN_VERIFY", "RSA_2048", null, Map.of(), REGION);

            AwsException ex = assertThrows(AwsException.class, () ->
                    kmsService.encrypt(key.getKeyId(), PLAINTEXT, Map.of(), "RSAES_OAEP_SHA_256", REGION));

            assertEquals("InvalidKeyUsageException", ex.getErrorCode());
            assertEquals(key.getArn() + " key usage is SIGN_VERIFY which is not valid for Encrypt.",
                    ex.getMessage());
        }

        @Test
        void encryptWithEncryptionContextThrowsValidation() {
            KmsKey key = createRsaKey();

            AwsException ex = assertThrows(AwsException.class, () ->
                    kmsService.encrypt(key.getKeyId(), PLAINTEXT, Map.of("foo", "bar"), "RSAES_OAEP_SHA_256", REGION));

            assertEquals("ValidationException", ex.getErrorCode());
            assertEquals("EncryptionContext is not supported when encrypting/decrypting with asymmetric CMKs.",
                    ex.getMessage());
        }

        @Test
        void encryptOver190BytesWithOaepSha256ThrowsValidation() {
            KmsKey key = createRsaKey();

            AwsException ex = assertThrows(AwsException.class, () ->
                    kmsService.encrypt(key.getKeyId(), new byte[191], Map.of(), "RSAES_OAEP_SHA_256", REGION));

            assertEquals("ValidationException", ex.getErrorCode());
            assertEquals("Algorithm RSAES_OAEP_SHA_256 and key spec RSA_2048 cannot encrypt data larger than 190 bytes.",
                    ex.getMessage());
        }

        @Test
        void decryptWithoutKeyIdThrowsValidation() {
            KmsKey key = createRsaKey();
            byte[] ciphertext = kmsService.encrypt(key.getKeyId(), PLAINTEXT, Map.of(),
                    "RSAES_OAEP_SHA_256", REGION).ciphertext();

            AwsException ex = assertThrows(AwsException.class, () ->
                    kmsService.decryptAndResolveKey(ciphertext, Map.of(), REGION, null, "RSAES_OAEP_SHA_256"));

            assertEquals("ValidationException", ex.getErrorCode());
            assertEquals("KeyId must not be null", ex.getMessage());
        }

        @Test
        void decryptGarbageThrowsInvalidCiphertext() {
            KmsKey key = createRsaKey();

            AwsException ex = assertThrows(AwsException.class, () ->
                    kmsService.decryptAndResolveKey(new byte[256], Map.of(), REGION,
                            key.getKeyId(), "RSAES_OAEP_SHA_256"));

            assertEquals("InvalidCiphertextException", ex.getErrorCode());
        }

        @Test
        void decryptWithWrongRsaKeyThrowsInvalidCiphertext() {
            KmsKey key = createRsaKey();
            KmsKey otherKey = createRsaKey();
            byte[] ciphertext = kmsService.encrypt(key.getKeyId(), PLAINTEXT, Map.of(),
                    "RSAES_OAEP_SHA_256", REGION).ciphertext();

            AwsException ex = assertThrows(AwsException.class, () ->
                    kmsService.decryptAndResolveKey(ciphertext, Map.of(), REGION,
                            otherKey.getKeyId(), "RSAES_OAEP_SHA_256"));

            assertEquals("InvalidCiphertextException", ex.getErrorCode());
        }

        @Test
        void decryptWithWrongOaepHashThrowsInvalidCiphertext() {
            KmsKey key = createRsaKey();
            byte[] ciphertext = kmsService.encrypt(key.getKeyId(), PLAINTEXT, Map.of(),
                    "RSAES_OAEP_SHA_256", REGION).ciphertext();

            AwsException ex = assertThrows(AwsException.class, () ->
                    kmsService.decryptAndResolveKey(ciphertext, Map.of(), REGION,
                            key.getKeyId(), "RSAES_OAEP_SHA_1"));

            assertEquals("InvalidCiphertextException", ex.getErrorCode());
        }

        @Test
        void decryptOnSymmetricKeyWithRsaAlgorithmThrowsInvalidKeyUsage() {
            KmsKey key = kmsService.createKey(null, REGION);
            byte[] ciphertext = kmsService.encrypt(key.getKeyId(), PLAINTEXT, REGION);

            AwsException ex = assertThrows(AwsException.class, () ->
                    kmsService.decryptAndResolveKey(ciphertext, Map.of(), REGION,
                            key.getKeyId(), "RSAES_OAEP_SHA_256"));

            assertEquals("InvalidKeyUsageException", ex.getErrorCode());
            assertEquals("Algorithm RSAES_OAEP_SHA_256 is incompatible with key spec SYMMETRIC_DEFAULT.",
                    ex.getMessage());
        }

        @Test
        void symmetricDecryptStillReportsSymmetricDefaultAlgorithm() {
            KmsKey key = kmsService.createKey(null, REGION);
            byte[] ciphertext = kmsService.encrypt(key.getKeyId(), PLAINTEXT, REGION);

            KmsService.DecryptResult result =
                    kmsService.decryptAndResolveKey(ciphertext, Map.of(), REGION, key.getKeyId(), null);

            assertArrayEquals(PLAINTEXT, result.plaintext());
            assertEquals("SYMMETRIC_DEFAULT", result.encryptionAlgorithm());
        }

        @Test
        void decryptWithRsaKeyIdAndDefaultAlgorithmThrowsInvalidCiphertext() {
            // Real KMS parses the ciphertext before comparing the defaulted SYMMETRIC_DEFAULT
            // algorithm with the key spec, so raw RSA bytes fail as a bad ciphertext.
            KmsKey key = createRsaKey();
            byte[] ciphertext = kmsService.encrypt(key.getKeyId(), PLAINTEXT, Map.of(),
                    "RSAES_OAEP_SHA_256", REGION).ciphertext();

            AwsException ex = assertThrows(AwsException.class, () ->
                    kmsService.decryptAndResolveKey(ciphertext, Map.of(), REGION, key.getKeyId(), null));

            assertEquals("InvalidCiphertextException", ex.getErrorCode());
        }

        @Test
        void decryptWithEncryptionContextThrowsValidation() {
            KmsKey key = createRsaKey();
            byte[] ciphertext = kmsService.encrypt(key.getKeyId(), PLAINTEXT, Map.of(),
                    "RSAES_OAEP_SHA_256", REGION).ciphertext();

            AwsException ex = assertThrows(AwsException.class, () ->
                    kmsService.decryptAndResolveKey(ciphertext, Map.of("foo", "bar"), REGION,
                            key.getKeyId(), "RSAES_OAEP_SHA_256"));

            assertEquals("ValidationException", ex.getErrorCode());
            assertEquals("EncryptionContext is not supported when encrypting/decrypting with asymmetric CMKs.",
                    ex.getMessage());
        }

        @Test
        void decryptWithSignVerifyKeyThrowsInvalidKeyUsage() {
            KmsKey key = kmsService.createKey("sign key", "SIGN_VERIFY", "RSA_2048", null, Map.of(), REGION);

            AwsException ex = assertThrows(AwsException.class, () ->
                    kmsService.decryptAndResolveKey(new byte[256], Map.of(), REGION,
                            key.getKeyId(), "RSAES_OAEP_SHA_256"));

            assertEquals("InvalidKeyUsageException", ex.getErrorCode());
            assertEquals(key.getArn() + " key usage is SIGN_VERIFY which is not valid for Decrypt.",
                    ex.getMessage());
        }

        @Test
        void encryptEmptyPlaintextThrowsValidation() {
            KmsKey key = createRsaKey();

            AwsException ex = assertThrows(AwsException.class, () ->
                    kmsService.encrypt(key.getKeyId(), new byte[0], Map.of(), "RSAES_OAEP_SHA_256", REGION));

            assertEquals("ValidationException", ex.getErrorCode());
            assertEquals("1 validation error detected: Value at 'plaintext' failed to satisfy constraint: "
                    + "Member must have length greater than or equal to 1", ex.getMessage());
        }

        @Test
        void encryptOver4096BytesThrowsValidation() {
            KmsKey key = kmsService.createKey(null, REGION);

            AwsException ex = assertThrows(AwsException.class, () ->
                    kmsService.encrypt(key.getKeyId(), new byte[4097], REGION));

            assertEquals("ValidationException", ex.getErrorCode());
        }

        @Test
        void encryptWithUnknownAlgorithmOnMissingKeyThrowsValidation() {
            AwsException ex = assertThrows(AwsException.class, () ->
                    kmsService.encrypt("no-such-key", PLAINTEXT, Map.of(), "RSAES_OAEP_SHA_384", REGION));

            assertEquals("ValidationException", ex.getErrorCode());
        }

        @Test
        void decryptBlobWithMalformedPayloadThrowsInvalidCiphertext() {
            byte[] ciphertext = "kms:v2:some-key:0011223344556677::@@@".getBytes(StandardCharsets.UTF_8);

            AwsException ex = assertThrows(AwsException.class, () ->
                    kmsService.decryptAndResolveKey(ciphertext, Map.of(), REGION, null, null));

            assertEquals("InvalidCiphertextException", ex.getErrorCode());
        }

        @Test
        void generateDataKeyWithRsaKeyThrowsInvalidKeyUsage() {
            KmsKey key = createRsaKey();

            AwsException ex = assertThrows(AwsException.class, () ->
                    kmsService.generateDataKey(key.getKeyId(), "AES_256", null, REGION));

            assertEquals("InvalidKeyUsageException", ex.getErrorCode());
            assertEquals("You cannot generate a data key with an asymmetric CMK", ex.getMessage());
        }

        @Test
        void generateDataKeyWithSignVerifyKeyThrowsInvalidKeyUsage() {
            KmsKey key = kmsService.createKey("sign key", "SIGN_VERIFY", "RSA_2048", null, Map.of(), REGION);

            AwsException ex = assertThrows(AwsException.class, () ->
                    kmsService.generateDataKey(key.getKeyId(), "AES_256", null, REGION));

            assertEquals("InvalidKeyUsageException", ex.getErrorCode());
            assertEquals(key.getArn() + " key usage is SIGN_VERIFY which is not valid for GenerateDataKey.",
                    ex.getMessage());
        }
    }

    @Nested
    class DecryptAndReEncryptTests {

        @Test
        void decryptAndResolveKeyWithMatchingKeyIdSucceeds() {
            KmsKey key = kmsService.createKey(null, REGION);
            byte[] plaintext = "hello".getBytes(StandardCharsets.UTF_8);
            byte[] ciphertext = kmsService.encrypt(key.getKeyId(), plaintext, REGION);

            KmsService.DecryptResult result = kmsService.decryptAndResolveKey(ciphertext, Map.of(), REGION, key.getKeyId());

            assertArrayEquals(plaintext, result.plaintext());
            assertEquals(key.getArn(), result.keyArn());
        }

        @Test
        void decryptAndResolveKeyWithMismatchedKeyIdThrowsIncorrectKeyException() {
            KmsKey keyA = kmsService.createKey(null, REGION);
            KmsKey keyB = kmsService.createKey(null, REGION);
            byte[] ciphertext = kmsService.encrypt(keyA.getKeyId(), "hello".getBytes(StandardCharsets.UTF_8), REGION);

            AwsException ex = assertThrows(
                    AwsException.class,
                    () -> kmsService.decryptAndResolveKey(ciphertext, Map.of(), REGION, keyB.getKeyId())
            );

            assertEquals("IncorrectKeyException", ex.getErrorCode());
        }

        @Test
        void decryptAndResolveKeyWithMismatchedAliasKeyIdThrowsIncorrectKeyException() {
            KmsKey keyA = kmsService.createKey(null, REGION);
            KmsKey keyB = kmsService.createKey(null, REGION);
            String aliasB = "alias/other-key";
            kmsService.createAlias(aliasB, keyB.getKeyId(), REGION);
            byte[] ciphertext = kmsService.encrypt(keyA.getKeyId(), "hello".getBytes(StandardCharsets.UTF_8), REGION);

            AwsException ex = assertThrows(
                    AwsException.class,
                    () -> kmsService.decryptAndResolveKey(ciphertext, Map.of(), REGION, aliasB)
            );

            assertEquals("IncorrectKeyException", ex.getErrorCode());
        }

        @Test
        void decryptAndResolveKeyWithMatchingAliasKeyIdSucceeds() {
            KmsKey keyA = kmsService.createKey(null, REGION);
            String aliasA = "alias/my-alias";
            kmsService.createAlias(aliasA, keyA.getKeyId(), REGION);
            byte[] plaintext = "hello".getBytes(StandardCharsets.UTF_8);
            byte[] ciphertext = kmsService.encrypt(keyA.getKeyId(), plaintext, REGION);

            KmsService.DecryptResult result = kmsService.decryptAndResolveKey(ciphertext, Map.of(), REGION, aliasA);

            assertArrayEquals(plaintext, result.plaintext());
            assertEquals(keyA.getArn(), result.keyArn());
        }

        @Test
        void decryptAndResolveKeyWithNullKeyIdBehavesAsSelfDescribing() {
            KmsKey key = kmsService.createKey(null, REGION);
            byte[] plaintext = "hello".getBytes(StandardCharsets.UTF_8);
            byte[] ciphertext = kmsService.encrypt(key.getKeyId(), plaintext, REGION);

            KmsService.DecryptResult result = kmsService.decryptAndResolveKey(ciphertext, Map.of(), REGION, null);

            assertArrayEquals(plaintext, result.plaintext());
            assertEquals(key.getArn(), result.keyArn());
        }

        @Test
        void decryptAndResolveKeyWithDisabledKeyThrowsDisabledException() {
            KmsKey key = kmsService.createKey(null, REGION);
            byte[] ciphertext = kmsService.encrypt(key.getKeyId(),
                    "hello".getBytes(StandardCharsets.UTF_8), REGION);
            kmsService.disableKey(key.getKeyId(), REGION);

            AwsException ex = assertThrows(
                    AwsException.class,
                    () -> kmsService.decryptAndResolveKey(ciphertext, Map.of(), REGION, key.getKeyId())
            );

            assertEquals("DisabledException", ex.getErrorCode());
            assertEquals(400, ex.getHttpStatus());
        }

        @Test
        void decryptAndResolveKeyWithPendingDeletionKeyThrowsKmsInvalidStateException() {
            KmsKey key = kmsService.createKey(null, REGION);
            byte[] ciphertext = kmsService.encrypt(key.getKeyId(),
                    "hello".getBytes(StandardCharsets.UTF_8), REGION);
            kmsService.scheduleKeyDeletion(key.getKeyId(), 7, REGION);

            AwsException ex = assertThrows(
                    AwsException.class,
                    () -> kmsService.decryptAndResolveKey(ciphertext, Map.of(), REGION, key.getKeyId())
            );

            assertEquals("KMSInvalidStateException", ex.getErrorCode());
            assertEquals(400, ex.getHttpStatus());
        }

        @Test
        void decryptAndResolveKeySelfDescribingWithDisabledKeyThrowsDisabledException() {
            KmsKey key = kmsService.createKey(null, REGION);
            byte[] ciphertext = kmsService.encrypt(key.getKeyId(),
                    "hello".getBytes(StandardCharsets.UTF_8), REGION);
            kmsService.disableKey(key.getKeyId(), REGION);

            AwsException ex = assertThrows(
                    AwsException.class,
                    () -> kmsService.decryptAndResolveKey(ciphertext, Map.of(), REGION, null)
            );

            assertEquals("DisabledException", ex.getErrorCode());
        }

        @Test
        void decryptAndResolveKeySelfDescribingWithPendingDeletionKeyThrowsKmsInvalidStateException() {
            KmsKey key = kmsService.createKey(null, REGION);
            byte[] ciphertext = kmsService.encrypt(key.getKeyId(),
                    "hello".getBytes(StandardCharsets.UTF_8), REGION);
            kmsService.scheduleKeyDeletion(key.getKeyId(), 7, REGION);

            AwsException ex = assertThrows(
                    AwsException.class,
                    () -> kmsService.decryptAndResolveKey(ciphertext, Map.of(), REGION, null)
            );

            assertEquals("KMSInvalidStateException", ex.getErrorCode());
        }

        @Test
        void reEncryptSourceKeyIdMismatchThrowsIncorrectKeyException() {
            KmsKey sourceKey = kmsService.createKey(null, REGION);
            KmsKey wrongKey = kmsService.createKey(null, REGION);
            byte[] ciphertext = kmsService.encrypt(sourceKey.getKeyId(),
                    "hello".getBytes(StandardCharsets.UTF_8), REGION);

            AwsException ex = assertThrows(
                    AwsException.class,
                    () -> kmsService.decryptAndResolveKey(ciphertext, Map.of(), REGION, wrongKey.getKeyId())
            );

            assertEquals("IncorrectKeyException", ex.getErrorCode());
        }
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
    @CsvSource({
            "ECC_NIST_P256, ECDSA_SHA_256",
            "ECC_NIST_P384, ECDSA_SHA_384",
            "ECC_NIST_P521, ECDSA_SHA_512",
            "ECC_SECG_P256K1, ECDSA_SHA_256",
    })
    void signAndVerify(String keySpec, String algorithm) {
        KmsKey key = kmsService.createKey("ecdsa key", "SIGN_VERIFY", keySpec, null, Map.of(), REGION);
        byte[] message = "sign me".getBytes(StandardCharsets.UTF_8);

        byte[] sig = kmsService.sign(key.getKeyId(), message, algorithm, REGION);
        assertNotNull(sig);
        assertDoesNotThrow(() -> kmsService.verify(key.getKeyId(), message, sig, algorithm, REGION));
    }

    @Test
    void signAndVerifyWithRsa() {
        KmsKey key = kmsService.createKey("rsa key", "SIGN_VERIFY", "RSA_2048", null, Map.of(), REGION);
        byte[] message = "sign me".getBytes(StandardCharsets.UTF_8);

        byte[] sig = kmsService.sign(key.getKeyId(), message, "RSASSA_PKCS1_V1_5_SHA_256", REGION);
        assertNotNull(sig);
        assertDoesNotThrow(() -> kmsService.verify(key.getKeyId(), message, sig, "RSASSA_PKCS1_V1_5_SHA_256", REGION));
    }

    @Test
    void signWithDigestMessageTypeVerifiesWithExternalVerifier() throws Exception {
        KmsKey key = kmsService.createKey("rsa digest key", "SIGN_VERIFY", "RSA_2048", null, Map.of(), REGION);
        byte[] message = "floci kms round-trip".getBytes(StandardCharsets.UTF_8);
        byte[] digest = MessageDigest.getInstance("SHA-512").digest(message);

        byte[] sig = kmsService.sign(key.getKeyId(), digest,
                "RSASSA_PKCS1_V1_5_SHA_512", KmsMessageType.DIGEST, REGION);

        // floci's own Verify round-trips.
        assertDoesNotThrow(() -> kmsService.verify(key.getKeyId(), digest, sig,
                "RSASSA_PKCS1_V1_5_SHA_512", KmsMessageType.DIGEST, REGION));

        // External verifier (standard JCA, standing in for openssl/python) reconstructs
        // DigestInfo from the message hash and must validate the DIGEST signature (#1345).
        byte[] der = Base64.getDecoder().decode(kmsService.getPublicKey(key.getKeyId(), REGION).getPublicKeyEncoded());
        PublicKey pub = KeyFactory.getInstance("RSA").generatePublic(new X509EncodedKeySpec(der));
        Signature verifier = Signature.getInstance("SHA512withRSA");
        verifier.initVerify(pub);
        verifier.update(message);
        assertTrue(verifier.verify(sig), "DIGEST signature must verify with standard SHA512withRSA");
    }

    @Test
    void signWithDigestMessageTypeMatchesRawSignature() {
        KmsKey key = kmsService.createKey("rsa digest key", "SIGN_VERIFY", "RSA_2048", null, Map.of(), REGION);
        byte[] message = "deterministic pkcs1".getBytes(StandardCharsets.UTF_8);
        byte[] digest;
        try {
            digest = MessageDigest.getInstance("SHA-256").digest(message);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }

        // PKCS#1 v1.5 is deterministic, so signing the digest (DIGEST) and signing the
        // message (RAW) with the same algorithm must produce identical signatures.
        byte[] digestSig = kmsService.sign(key.getKeyId(), digest,
                "RSASSA_PKCS1_V1_5_SHA_256", KmsMessageType.DIGEST, REGION);
        byte[] rawSig = kmsService.sign(key.getKeyId(), message,
                "RSASSA_PKCS1_V1_5_SHA_256", KmsMessageType.RAW, REGION);
        assertArrayEquals(rawSig, digestSig);
    }

    @Test
    void signWithDigestMessageTypePssVerifiesWithExternalVerifier() throws Exception {
        KmsKey key = kmsService.createKey("rsa pss digest key", "SIGN_VERIFY", "RSA_2048", null, Map.of(), REGION);
        byte[] message = "floci kms pss round-trip".getBytes(StandardCharsets.UTF_8);
        byte[] digest = MessageDigest.getInstance("SHA-256").digest(message);

        byte[] sig = kmsService.sign(key.getKeyId(), digest,
                "RSASSA_PSS_SHA_256", KmsMessageType.DIGEST, REGION);

        // floci's own Verify round-trips, against the digest and against the raw message.
        assertDoesNotThrow(() -> kmsService.verify(key.getKeyId(), digest, sig,
                "RSASSA_PSS_SHA_256", KmsMessageType.DIGEST, REGION));
        assertDoesNotThrow(() -> kmsService.verify(key.getKeyId(), message, sig,
                "RSASSA_PSS_SHA_256", KmsMessageType.RAW, REGION));

        // External verifier with the PSS parameters AWS documents for RSASSA_PSS_SHA_256.
        byte[] der = Base64.getDecoder().decode(kmsService.getPublicKey(key.getKeyId(), REGION).getPublicKeyEncoded());
        PublicKey pub = KeyFactory.getInstance("RSA").generatePublic(new X509EncodedKeySpec(der));
        Signature verifier = Signature.getInstance("RSASSA-PSS");
        verifier.setParameter(new PSSParameterSpec("SHA-256", "MGF1", MGF1ParameterSpec.SHA256, 32, 1));
        verifier.initVerify(pub);
        verifier.update(message);
        assertTrue(verifier.verify(sig), "DIGEST PSS signature must verify as real RSASSA-PSS");
    }

    @Test
    void signWithInvalidAlgorithmFailsValidation() {
        KmsKey key = kmsService.createKey("ecdsa key", "SIGN_VERIFY", "ECC_NIST_P256", null, Map.of(), REGION);

        AwsException ex = assertThrows(AwsException.class, () ->
                kmsService.sign(key.getKeyId(), "sign me".getBytes(StandardCharsets.UTF_8), "NOT_AN_ALGORITHM", REGION));

        assertEquals("ValidationException", ex.getErrorCode());
        assertTrue(ex.getMessage().startsWith(
                "1 validation error detected: Value 'NOT_AN_ALGORITHM' at 'signingAlgorithm' failed to satisfy constraint"));
    }

    @Test
    void verifyWithInvalidAlgorithmFailsValidation() {
        KmsKey key = kmsService.createKey("ecdsa key", "SIGN_VERIFY", "ECC_NIST_P256", null, Map.of(), REGION);
        byte[] message = "sign me".getBytes(StandardCharsets.UTF_8);
        byte[] sig = kmsService.sign(key.getKeyId(), message, "ECDSA_SHA_256", REGION);

        AwsException ex = assertThrows(AwsException.class, () ->
                kmsService.verify(key.getKeyId(), message, sig, "NOT_AN_ALGORITHM", REGION));

        assertEquals("ValidationException", ex.getErrorCode());
    }

    @Test
    void verifyWithWrongSignatureThrowsInvalidSignature() {
        KmsKey key = kmsService.createKey("ecdsa key", "SIGN_VERIFY", "ECC_NIST_P256", null, Map.of(), REGION);
        byte[] message = "sign me".getBytes(StandardCharsets.UTF_8);

        AwsException ex = assertThrows(AwsException.class, () -> kmsService.verify(key.getKeyId(), message,
                "not-a-valid-sig".getBytes(StandardCharsets.UTF_8), "ECDSA_SHA_256", REGION));

        assertEquals("KMSInvalidSignatureException", ex.getErrorCode());
        assertNull(ex.getMessage());
    }

    @Test
    void verifyWithCorruptKeyMaterialIsAnInternalFailure() {
        KmsKey key = kmsService.createKey("ecdsa key", "SIGN_VERIFY", "ECC_NIST_P256", null, Map.of(), REGION);
        KmsKey stored = keyStore.get(REGION + "::" + key.getKeyId()).orElseThrow();
        stored.setPublicKeyEncoded(Base64.getEncoder().encodeToString(new byte[16]));
        keyStore.put(REGION + "::" + key.getKeyId(), stored);

        AwsException ex = assertThrows(AwsException.class, () -> kmsService.verify(key.getKeyId(),
                "sign me".getBytes(StandardCharsets.UTF_8), new byte[64], "ECDSA_SHA_256", REGION));

        assertEquals("InternalFailure", ex.getErrorCode());
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
        Map<String, Object> result = kmsService.generateDataKey(key.getKeyId(), "AES_256", null, REGION);

        assertNotNull(result.get("Plaintext"));
        assertNotNull(result.get("CiphertextBlob"));
        assertEquals(32, ((byte[]) result.get("Plaintext")).length);
    }

    @Test
    void generateDataKeyWithoutPlaintextKeepsThePlaintextToItself() {
        KmsKey key = kmsService.createKey(null, REGION);

        Map<String, Object> result = kmsService.generateDataKeyWithoutPlaintext(key.getKeyId(), "AES_256", null,
                Map.of(), REGION);

        assertFalse(result.containsKey("Plaintext"));
        assertNotNull(result.get("CiphertextBlob"));
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

        assertEquals("TagException", exception.getErrorCode());
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

    // ── Issue #1528 — ListKeyPolicies ────────────────────────────────────────

    @Test
    void listKeyPoliciesReturnsDefaultPolicy() {
        KmsKey key = kmsService.createKey("list-policy-key", REGION);

        Map<String, Object> result = kmsService.listKeyPolicies(key.getKeyId(), REGION);

        @SuppressWarnings("unchecked")
        List<String> policyNames = (List<String>) result.get("PolicyNames");
        assertEquals(1, policyNames.size());
        assertEquals("default", policyNames.getFirst());
        assertFalse((Boolean) result.get("Truncated"));
        // Truncated is always false, so NextMarker must not be emitted at all.
        assertFalse(result.containsKey("NextMarker"));
    }

    @Test
    void listKeyPoliciesResolvesByArn() {
        // The model documents KeyId for this operation as key ID or key ARN only; alias
        // acceptance is a Floci-wide resolveKey superset, not this operation's AWS contract.
        KmsKey key = kmsService.createKey("list-policy-arn", REGION);

        assertEquals(List.of("default"),
                kmsService.listKeyPolicies(key.getArn(), REGION).get("PolicyNames"));
    }

    @Test
    void listKeyPoliciesOnNonExistentKeyThrows() {
        AwsException ex = assertThrows(AwsException.class, () ->
                kmsService.listKeyPolicies("non-existent", REGION));
        assertEquals("NotFoundException", ex.getErrorCode());
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
        key.setKeySpec(KmsKeySpec.RSA_2048);
        key.setKeyUsage(KmsKeyUsage.SIGN_VERIFY);
        assertThrows(AwsException.class, () ->
                kmsService.enableKeyRotation(key.getKeyId(), REGION));
    }

    @Test
    void getKeyRotationStatusOnAsymmetricKeyReturnsFalse() {
        KmsKey key = kmsService.createKey(null, REGION);
        key.setKeySpec(KmsKeySpec.ECC_NIST_P256);
        key.setKeyUsage(KmsKeyUsage.SIGN_VERIFY);
        assertFalse(kmsService.getKeyRotationStatus(key.getKeyId(), REGION));
    }

    @Test
    void getKeyRotationStatusOnHmacKeyReturnsFalse() {
        KmsKey key = kmsService.createKey(null, REGION);
        key.setKeySpec(KmsKeySpec.HMAC_256);
        key.setKeyUsage(KmsKeyUsage.GENERATE_VERIFY_MAC);
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

    @Test
    void rotateKeyOnDemandLimitExceededThrows() {
        KmsKey key = kmsService.createKey(null, REGION);

        for (int i = 0; i < 25; i++) {
            kmsService.rotateKeyOnDemand(key.getKeyId(), REGION);
        }

        AwsException ex = assertThrows(AwsException.class, () ->
                kmsService.rotateKeyOnDemand(key.getKeyId(), REGION));

        assertEquals("LimitExceededException", ex.getErrorCode());
        assertEquals(400, ex.getHttpStatus());
        assertEquals(25, kmsService.describeKey(key.getKeyId(), REGION).getOnDemandRotationCount());
    }

    // ── Issue #497 — HMAC key specs ─────────────────────────────────────────

    @ParameterizedTest
    @ValueSource(strings = {"HMAC_224", "HMAC_256", "HMAC_384", "HMAC_512"})
    void createHmacKey_allSpecs(String spec) {
        KmsKey key = kmsService.createKey("hmac key", "GENERATE_VERIFY_MAC", spec, null, Map.of(), REGION);

        assertEquals(KmsKeySpec.valueOf(spec), key.getKeySpec());
        assertEquals("GENERATE_VERIFY_MAC", key.getKeyUsage().name());
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
        assertEquals(KmsKeySpec.valueOf(spec), found.getKeySpec());
    }

    @ParameterizedTest
    @ValueSource(strings = {"HMAC_224", "HMAC_256", "HMAC_384", "HMAC_512"})
    void generateMacSupportsAllAdvertisedHmacSpecs(String spec) {
        KmsKey key = kmsService.createKey("hmac key", "GENERATE_VERIFY_MAC", spec, null, Map.of(), REGION);
        byte[] message = "floci-mac-probe".getBytes(StandardCharsets.UTF_8);

        byte[] mac = kmsService.generateMac(key.getKeyId(), message, KmsKeySpec.valueOf(spec).getAlgorithm().getFirst().getAlgName(), REGION);

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

    @ParameterizedTest
    @ValueSource(strings = {"RSA_2048", "RSA_3072", "RSA_4096"})
    void signAndVerifyWithAllRsaSpecs(String keySpec) {
        KmsKey key = kmsService.createKey("rsa key", "SIGN_VERIFY", keySpec, null, Map.of(), REGION);
        byte[] message = "sign me with rsa".getBytes(StandardCharsets.UTF_8);

        String algo = "RSASSA_PKCS1_V1_5_SHA_256";
        byte[] sig = kmsService.sign(key.getKeyId(), message, algo, REGION);
        assertNotNull(sig);
        assertDoesNotThrow(() -> kmsService.verify(key.getKeyId(), message, sig, algo, REGION));
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "ECC_NIST_P256", "ECC_NIST_P384", "ECC_NIST_P521", "ECC_SECG_P256K1",
            "RSA_2048", "RSA_3072", "RSA_4096"
    })
    void signAndVerifyWithDigestAllSpecs(String keySpec) throws Exception {
        KmsKey key = kmsService.createKey("digest key", "SIGN_VERIFY", keySpec, null, Map.of(), REGION);
        byte[] message = "floci kms digest test".getBytes(StandardCharsets.UTF_8);

        KmsKeySpec.Algorithm algorithm = KmsKeySpec.valueOf(keySpec).getAlgorithm().getFirst();
        String digestAlgo = algorithm.getJavaName().substring(0, 6);
        byte[] digest = MessageDigest.getInstance(digestAlgo).digest(message);

        byte[] sig = kmsService.sign(key.getKeyId(), digest, algorithm.getAlgName(), KmsMessageType.DIGEST, REGION);
        assertNotNull(sig);
        assertDoesNotThrow(() -> kmsService.verify(key.getKeyId(), digest, sig, algorithm.getAlgName(), KmsMessageType.DIGEST, REGION));
    }

    @Test
    void signFailsForSymmetricKey() {
        KmsKey key = kmsService.createKey("symmetric", "ENCRYPT_DECRYPT", "SYMMETRIC_DEFAULT", null, Map.of(), REGION);
        byte[] message = "hello".getBytes(StandardCharsets.UTF_8);

        String keyId = key.getKeyId();
        AwsException ex = assertThrows(AwsException.class, () ->
                kmsService.sign(keyId, message, "RSASSA_PKCS1_V1_5_SHA_256", REGION));
        assertEquals("InvalidKeyUsageException", ex.getErrorCode());
        assertEquals(key.getArn() + " key usage is ENCRYPT_DECRYPT which is not valid for Sign.", ex.getMessage());
    }

    @Test
    void generateMacFailsForAsymmetricKey() {
        KmsKey key = kmsService.createKey("asymmetric", "SIGN_VERIFY", "RSA_2048", null, Map.of(), REGION);
        byte[] message = "hello".getBytes(StandardCharsets.UTF_8);

        String keyId = key.getKeyId();
        AwsException ex = assertThrows(AwsException.class, () ->
                kmsService.generateMac(keyId, message, "HMAC_SHA_256", REGION));
        assertEquals("InvalidKeyUsageException", ex.getErrorCode());
    }

    @Nested
    class ImportedKeyMaterial {

        private static final String OAEP_SHA_256 = "RSAES_OAEP_SHA_256";
        private static final String RSA_AES_SHA_1 = "RSA_AES_KEY_WRAP_SHA_1";
        private static final String RSA_AES_SHA_256 = "RSA_AES_KEY_WRAP_SHA_256";

        private KmsKey externalKey(String keySpec, String keyUsage) {
            return kmsService.createKey("external", keyUsage, keySpec, null, Map.of(), "EXTERNAL", REGION);
        }

        private KmsKey externalSymmetricKey() {
            return externalKey("SYMMETRIC_DEFAULT", "ENCRYPT_DECRYPT");
        }

        private byte[] material(int length, byte seed) {
            byte[] material = new byte[length];
            Arrays.fill(material, seed);
            return material;
        }

        /** Wraps material the way a caller would, with the public key GetParametersForImport returned. */
        private byte[] wrapOAEP(String publicKeyEncoded, String wrappingAlgorithm, byte[] material) throws Exception {
            PublicKey wrappingKey = KeyFactory.getInstance("RSA")
                    .generatePublic(new X509EncodedKeySpec(Base64.getDecoder().decode(publicKeyEncoded)));
            String digest = "RSAES_OAEP_SHA_1".equals(wrappingAlgorithm) ? "SHA-1" : "SHA-256";
            Cipher cipher = Cipher.getInstance("RSA/ECB/OAEPPadding");
            cipher.init(Cipher.ENCRYPT_MODE, wrappingKey, new OAEPParameterSpec(digest, "MGF1",
                    new MGF1ParameterSpec(digest), PSource.PSpecified.DEFAULT));
            return cipher.doFinal(material);
        }

        private byte[] wrapRsaAes(String publicKeyEncoded, String wrappingAlgorithm, byte[] material,
                                  byte[] aesKeyBytes) throws Exception {
            SecretKeySpec aesKey = new SecretKeySpec(aesKeyBytes, "AES");
            Cipher aesKwp = Cipher.getInstance("AES/KWP/NoPadding");
            aesKwp.init(Cipher.ENCRYPT_MODE, aesKey);
            byte[] wrappedMaterial = aesKwp.doFinal(material);

            PublicKey wrappingKey = KeyFactory.getInstance("RSA")
                    .generatePublic(new X509EncodedKeySpec(Base64.getDecoder().decode(publicKeyEncoded)));
            String digest = RSA_AES_SHA_1.equals(wrappingAlgorithm) ? "SHA-1" : "SHA-256";
            Cipher rsaOaep = Cipher.getInstance("RSA/ECB/OAEPPadding");
            rsaOaep.init(Cipher.ENCRYPT_MODE, wrappingKey, new OAEPParameterSpec(digest, "MGF1",
                    new MGF1ParameterSpec(digest), PSource.PSpecified.DEFAULT));
            byte[] wrappedAesKey = rsaOaep.doFinal(aesKeyBytes);

            byte[] payload = Arrays.copyOf(wrappedAesKey, wrappedAesKey.length + wrappedMaterial.length);
            System.arraycopy(wrappedMaterial, 0, payload, wrappedAesKey.length, wrappedMaterial.length);
            return payload;
        }

        private KeyPair rsaKeyPair(String keySpec) throws Exception {
            KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
            generator.initialize(Integer.parseInt(keySpec.substring("RSA_".length())));
            return generator.generateKeyPair();
        }

        private KeyPair ecKeyPair(String keySpec) throws Exception {
            KeyPairGenerator generator = KeyPairGenerator.getInstance("EC", BouncyCastleProvider.PROVIDER_NAME);
            generator.initialize(new ECGenParameterSpec(KmsKeySpec.valueOf(keySpec).curveName()));
            return generator.generateKeyPair();
        }

        private byte[] ecPublicPoint(KeyPair keyPair) {
            return SubjectPublicKeyInfo.getInstance(keyPair.getPublic().getEncoded()).getPublicKeyData().getBytes();
        }

        /** Re-encodes an EC private key with the given algorithm identifier and embedded public point. */
        private byte[] ecPkcs8(KeyPair keyPair, String curveName, AlgorithmIdentifier algorithm,
                               byte[] embeddedPublicPoint) throws Exception {
            return ecPkcs8(keyPair, curveName, algorithm, embeddedPublicPoint, null);
        }

        /** As above, also setting the RFC 5915 ECPrivateKey's own optional curve parameters. */
        private byte[] ecPkcs8(KeyPair keyPair, String curveName, AlgorithmIdentifier algorithm,
                               byte[] embeddedPublicPoint, ASN1Encodable innerParameters) throws Exception {
            BigInteger privateScalar = ECPrivateKey.getInstance(
                    PrivateKeyInfo.getInstance(keyPair.getPrivate().getEncoded()).parsePrivateKey()).getKey();
            int orderBits = ECNamedCurveTable.getByName(curveName).getN().bitLength();
            ECPrivateKey ecPrivateKey = new ECPrivateKey(orderBits, privateScalar,
                    new DERBitString(embeddedPublicPoint), innerParameters);
            return new PrivateKeyInfo(algorithm, ecPrivateKey).getEncoded();
        }

        /** A P-256 PKCS#8 key whose RFC 5915 fields after the version are given as-is, malformed or not. */
        private byte[] ecPkcs8WithRawFields(ASN1Encodable... fieldsAfterVersion) throws Exception {
            ASN1EncodableVector fields = new ASN1EncodableVector();
            fields.add(new ASN1Integer(1));
            for (ASN1Encodable field : fieldsAfterVersion) {
                fields.add(field);
            }
            return new PrivateKeyInfo(p256NamedCurve(), new DERSequence(fields)).getEncoded();
        }

        private AlgorithmIdentifier p256NamedCurve() {
            return new AlgorithmIdentifier(X9ObjectIdentifiers.id_ecPublicKey, X9ObjectIdentifiers.prime256v1);
        }

        private KmsKey importInto(KmsKey key, byte[] rawMaterial, String wrappingAlgorithm) throws Exception {
            return importInto(key, rawMaterial, wrappingAlgorithm, "RSA_2048");
        }

        private KmsKey importInto(KmsKey key, byte[] rawMaterial, String wrappingAlgorithm,
                                  String wrappingKeySpec) throws Exception {
            KmsService.ImportParameters parameters =
                    kmsService.getParametersForImport(key.getKeyId(), wrappingAlgorithm, wrappingKeySpec, REGION);
            byte[] wrappedMaterial = wrappingAlgorithm.startsWith("RSA_AES_KEY_WRAP_")
                    ? wrapRsaAes(parameters.publicKeyEncoded(), wrappingAlgorithm, rawMaterial,
                            material(32, (byte) 91))
                    : wrapOAEP(parameters.publicKeyEncoded(), wrappingAlgorithm, rawMaterial);
            return kmsService.importKeyMaterial(key.getKeyId(), parameters.importToken(),
                    wrappedMaterial,
                    "KEY_MATERIAL_DOES_NOT_EXPIRE", null, null, REGION);
        }

        @Test
        void externalOriginCreatesAKeyWithoutMaterial() {
            KmsKey key = externalSymmetricKey();

            assertEquals("EXTERNAL", key.getOrigin());
            assertEquals("PendingImport", key.getKeyState());
            assertFalse(key.isEnabled());
            assertNull(key.getPrivateKeyEncoded());
        }

        @Test
        void replicatedExternalKeyStartsPendingImportWithoutExpiration() throws Exception {
            KmsKey primary = kmsService.createKey("external primary", "ENCRYPT_DECRYPT", "SYMMETRIC_DEFAULT",
                    null, Map.of(), "EXTERNAL", true, REGION);
            importInto(primary, material(32, (byte) 0x5A), OAEP_SHA_256);

            KmsKey replica = kmsService.replicateKey(
                    primary.getKeyId(), null, null, Map.of(), "us-west-2", REGION);

            assertEquals("EXTERNAL", replica.getOrigin());
            assertEquals("PendingImport", replica.getKeyState());
            assertFalse(replica.isEnabled());
            assertTrue(replica.getBackingKeys().isEmpty());
            assertEquals(primary.getKeyMaterialId(), replica.getKeyMaterialId());
            assertNull(replica.getExpirationModel());
            assertEquals(0, replica.getValidTo());
        }

        @Test
        void encryptIsRejectedWhileTheKeyHasNoMaterial() {
            String keyId = externalSymmetricKey().getKeyId();
            byte[] plaintext = "hello".getBytes(StandardCharsets.UTF_8);

            AwsException ex = assertThrows(AwsException.class, () ->
                    kmsService.encrypt(keyId, plaintext, REGION));
            assertEquals("KMSInvalidStateException", ex.getErrorCode());
        }

        @ParameterizedTest
        @ValueSource(strings = {"RSAES_OAEP_SHA_256", "RSAES_OAEP_SHA_1"})
        void everySupportedWrappingAlgorithmCompletesAnImport(String wrappingAlgorithm) throws Exception {
            KmsKey key = externalSymmetricKey();

            KmsKey imported = importInto(key, material(32, (byte) 7), wrappingAlgorithm);

            assertEquals("Enabled", imported.getKeyState());
            assertTrue(imported.isEnabled());
            assertNull(imported.getImportParameters());
            assertNotNull(kmsService.encrypt(key.getKeyId(), "hello".getBytes(StandardCharsets.UTF_8), REGION));
        }

        @Test
        void materialWrappedForAnotherKeyIsRejected() throws Exception {
            KmsKey key = externalSymmetricKey();
            KmsKey otherKey = externalSymmetricKey();
            KmsService.ImportParameters foreign =
                    kmsService.getParametersForImport(otherKey.getKeyId(), OAEP_SHA_256, "RSA_2048", REGION);
            KmsService.ImportParameters own =
                    kmsService.getParametersForImport(key.getKeyId(), OAEP_SHA_256, "RSA_2048", REGION);
            byte[] wrongly = wrapOAEP(foreign.publicKeyEncoded(), OAEP_SHA_256, material(32, (byte) 1));

            String keyId = key.getKeyId();
            String token = own.importToken();
            AwsException ex = assertThrows(AwsException.class, () -> kmsService.importKeyMaterial(
                    keyId, token, wrongly, "KEY_MATERIAL_DOES_NOT_EXPIRE", null, null, REGION));
            assertEquals("InvalidCiphertextException", ex.getErrorCode());
            assertNull(ex.getMessage());
            assertEquals("PendingImport", kmsService.describeKey(keyId, REGION).getKeyState());
        }

        @Test
        void materialOfTheWrongLengthIsRejected() throws Exception {
            KmsKey key = externalSymmetricKey();

            AwsException ex = assertThrows(AwsException.class, () ->
                    importInto(key, material(16, (byte) 1), OAEP_SHA_256));
            assertEquals("IncorrectKeyMaterialException", ex.getErrorCode());
        }

        @Test
        void hmacMaterialOfTheWrongLengthIsRejected() {
            KmsKey key = externalKey("HMAC_256", "GENERATE_VERIFY_MAC");

            AwsException ex = assertThrows(AwsException.class, () ->
                    importInto(key, material(16, (byte) 1), OAEP_SHA_256));

            assertEquals("IncorrectKeyMaterialException", ex.getErrorCode());
        }

        @Test
        void aSupersededImportTokenIsRejected() throws Exception {
            KmsKey key = externalSymmetricKey();
            KmsService.ImportParameters first =
                    kmsService.getParametersForImport(key.getKeyId(), OAEP_SHA_256, "RSA_2048", REGION);
            kmsService.getParametersForImport(key.getKeyId(), OAEP_SHA_256, "RSA_2048", REGION);
            byte[] wrapped = wrapOAEP(first.publicKeyEncoded(), OAEP_SHA_256, material(32, (byte) 1));

            String keyId = key.getKeyId();
            String staleToken = first.importToken();
            AwsException ex = assertThrows(AwsException.class, () -> kmsService.importKeyMaterial(
                    keyId, staleToken, wrapped, "KEY_MATERIAL_DOES_NOT_EXPIRE", null, null, REGION));
            assertEquals("InvalidImportTokenException", ex.getErrorCode());
        }

        @Test
        void importWithoutOutstandingParametersIsRejected() {
            String keyId = externalSymmetricKey().getKeyId();
            byte[] anything = material(32, (byte) 1);

            AwsException ex = assertThrows(AwsException.class, () -> kmsService.importKeyMaterial(
                    keyId, "some-token", anything, "KEY_MATERIAL_DOES_NOT_EXPIRE", null, null, REGION));
            assertEquals("InvalidImportTokenException", ex.getErrorCode());
        }

        @Test
        void anImportTokenIsSpentByTheImportThatUsedIt() throws Exception {
            KmsKey key = externalSymmetricKey();
            KmsService.ImportParameters parameters =
                    kmsService.getParametersForImport(key.getKeyId(), OAEP_SHA_256, "RSA_2048", REGION);
            byte[] wrapped = wrapOAEP(parameters.publicKeyEncoded(), OAEP_SHA_256, material(32, (byte) 3));
            kmsService.importKeyMaterial(key.getKeyId(), parameters.importToken(), wrapped,
                    "KEY_MATERIAL_DOES_NOT_EXPIRE", null, null, REGION);

            String keyId = key.getKeyId();
            String spentToken = parameters.importToken();
            AwsException ex = assertThrows(AwsException.class, () -> kmsService.importKeyMaterial(
                    keyId, spentToken, wrapped, "KEY_MATERIAL_DOES_NOT_EXPIRE", null, null, REGION));
            assertEquals("InvalidImportTokenException", ex.getErrorCode());
        }

        /**
         * AWS requires ValidTo to be in the future, so the only honest way to reach an expired key
         * is to let the clock pass it. Rewinding the stored ValidTo stands in for that wait.
         */
        @Test
        void expiringMaterialReturnsTheKeyToPendingImport() throws Exception {
            KmsKey key = externalSymmetricKey();
            KmsService.ImportParameters parameters =
                    kmsService.getParametersForImport(key.getKeyId(), OAEP_SHA_256, "RSA_2048", REGION);
            byte[] wrapped = wrapOAEP(parameters.publicKeyEncoded(), OAEP_SHA_256, material(32, (byte) 9));
            kmsService.importKeyMaterial(key.getKeyId(), parameters.importToken(), wrapped,
                    "KEY_MATERIAL_EXPIRES", Instant.now().getEpochSecond() + 3600, null, REGION);

            KmsKey stored = keyStore.get(REGION + "::" + key.getKeyId()).orElseThrow();
            stored.setValidTo(Instant.now().getEpochSecond() - 60);
            keyStore.put(REGION + "::" + key.getKeyId(), stored);

            KmsKey expired = kmsService.describeKey(key.getKeyId(), REGION);
            assertEquals("PendingImport", expired.getKeyState());
            assertFalse(expired.isEnabled());
            assertNull(expired.getPrivateKeyEncoded());
            assertEquals(0, expired.getValidTo());
            assertNull(expired.getExpirationModel());
        }

        @Test
        void validToInThePastIsRejected() throws Exception {
            KmsKey key = externalSymmetricKey();
            KmsService.ImportParameters parameters =
                    kmsService.getParametersForImport(key.getKeyId(), OAEP_SHA_256, "RSA_2048", REGION);
            byte[] wrapped = wrapOAEP(parameters.publicKeyEncoded(), OAEP_SHA_256, material(32, (byte) 1));

            String keyId = key.getKeyId();
            String token = parameters.importToken();
            long past = Instant.now().getEpochSecond() - 60;
            AwsException ex = assertThrows(AwsException.class, () -> kmsService.importKeyMaterial(
                    keyId, token, wrapped, "KEY_MATERIAL_EXPIRES", past, null, REGION));
            assertEquals("ValidationException", ex.getErrorCode());
        }

        @Test
        void validToBeyondThreeHundredSixtyFiveDaysIsRejected() throws Exception {
            KmsKey key = externalSymmetricKey();
            KmsService.ImportParameters parameters =
                    kmsService.getParametersForImport(key.getKeyId(), OAEP_SHA_256, "RSA_2048", REGION);
            byte[] wrapped = wrapOAEP(parameters.publicKeyEncoded(), OAEP_SHA_256, material(32, (byte) 1));

            String keyId = key.getKeyId();
            String token = parameters.importToken();
            long tooFar = Instant.now().plus(Duration.ofDays(366)).getEpochSecond();
            AwsException ex = assertThrows(AwsException.class, () -> kmsService.importKeyMaterial(
                    keyId, token, wrapped, "KEY_MATERIAL_EXPIRES", tooFar, null, REGION));
            assertEquals("ValidationException", ex.getErrorCode());
        }

        @Test
        void theKeyMaterialIdIsDerivedFromTheKeyAndTheMaterial() throws Exception {
            byte[] sharedMaterial = material(32, (byte) 21);
            KmsKey first = externalSymmetricKey();
            KmsKey second = externalSymmetricKey();

            String firstId = importInto(first, sharedMaterial, OAEP_SHA_256).getKeyMaterialId();
            String secondId = importInto(second, sharedMaterial, OAEP_SHA_256).getKeyMaterialId();

            assertTrue(firstId.matches("[a-f0-9]{64}"));
            assertNotEquals(firstId, secondId);

            kmsService.deleteImportedKeyMaterial(first.getKeyId(), REGION);
            assertEquals(firstId, importInto(first, sharedMaterial, OAEP_SHA_256).getKeyMaterialId());
        }

        @Test
        void deprecatedPkcs1WrappingIsRejected() {
            String keyId = externalSymmetricKey().getKeyId();

            AwsException ex = assertThrows(AwsException.class, () ->
                    kmsService.getParametersForImport(keyId, "RSAES_PKCS1_V1_5", "RSA_2048", REGION));
            assertEquals("UnsupportedOperationException", ex.getErrorCode());
        }

        @Test
        void importingNewMaterialIntoAKeyThatAlreadyHasSomeIsRejected() throws Exception {
            KmsKey key = externalSymmetricKey();
            importInto(key, material(32, (byte) 5), OAEP_SHA_256);
            KmsService.ImportParameters parameters =
                    kmsService.getParametersForImport(key.getKeyId(), OAEP_SHA_256, "RSA_2048", REGION);
            byte[] wrapped = wrapOAEP(parameters.publicKeyEncoded(), OAEP_SHA_256, material(32, (byte) 6));

            String keyId = key.getKeyId();
            String token = parameters.importToken();
            AwsException ex = assertThrows(AwsException.class, () -> kmsService.importKeyMaterial(
                    keyId, token, wrapped, "KEY_MATERIAL_DOES_NOT_EXPIRE", null, "NEW_KEY_MATERIAL", REGION));
            assertEquals("UnsupportedOperationException", ex.getErrorCode());
        }

        @Test
        void reimportingExistingMaterialIntoAFreshKeyIsRejected() throws Exception {
            KmsKey key = externalSymmetricKey();
            KmsService.ImportParameters parameters =
                    kmsService.getParametersForImport(key.getKeyId(), OAEP_SHA_256, "RSA_2048", REGION);
            byte[] wrapped = wrapOAEP(parameters.publicKeyEncoded(), OAEP_SHA_256, material(32, (byte) 6));

            String keyId = key.getKeyId();
            String token = parameters.importToken();
            AwsException ex = assertThrows(AwsException.class, () -> kmsService.importKeyMaterial(
                    keyId, token, wrapped, "KEY_MATERIAL_DOES_NOT_EXPIRE", null, "EXISTING_KEY_MATERIAL", REGION));
            assertEquals("IncorrectKeyMaterialException", ex.getErrorCode());
        }

        @Test
        void anUnmodelledImportTypeIsRejected() throws Exception {
            KmsKey key = externalSymmetricKey();
            KmsService.ImportParameters parameters =
                    kmsService.getParametersForImport(key.getKeyId(), OAEP_SHA_256, "RSA_2048", REGION);
            byte[] wrapped = wrapOAEP(parameters.publicKeyEncoded(), OAEP_SHA_256, material(32, (byte) 6));

            String keyId = key.getKeyId();
            String token = parameters.importToken();
            AwsException ex = assertThrows(AwsException.class, () -> kmsService.importKeyMaterial(
                    keyId, token, wrapped, "KEY_MATERIAL_DOES_NOT_EXPIRE", null, "ROTATE", REGION));
            assertEquals("ValidationException", ex.getErrorCode());
        }

        @Test
        void deletingMaterialFromAKeyPendingDeletionLeavesItPendingDeletion() throws Exception {
            KmsKey key = externalSymmetricKey();
            importInto(key, material(32, (byte) 5), OAEP_SHA_256);
            kmsService.scheduleKeyDeletion(key.getKeyId(), 7, REGION);

            KmsKey deleted = kmsService.deleteImportedKeyMaterial(key.getKeyId(), REGION);

            assertEquals("PendingDeletion", deleted.getKeyState());
            assertNull(deleted.getPrivateKeyEncoded());
        }

        @Test
        void keyMaterialExpiresRequiresValidTo() throws Exception {
            KmsKey key = externalSymmetricKey();
            KmsService.ImportParameters parameters =
                    kmsService.getParametersForImport(key.getKeyId(), OAEP_SHA_256, "RSA_2048", REGION);
            byte[] wrapped = wrapOAEP(parameters.publicKeyEncoded(), OAEP_SHA_256, material(32, (byte) 1));

            String keyId = key.getKeyId();
            String token = parameters.importToken();
            AwsException ex = assertThrows(AwsException.class, () -> kmsService.importKeyMaterial(
                    keyId, token, wrapped, "KEY_MATERIAL_EXPIRES", null, null, REGION));
            assertEquals("ValidationException", ex.getErrorCode());
        }

        @Test
        void keyMaterialDoesNotExpireRejectsValidTo() throws Exception {
            KmsKey key = externalSymmetricKey();
            KmsService.ImportParameters parameters =
                    kmsService.getParametersForImport(key.getKeyId(), OAEP_SHA_256, "RSA_2048", REGION);
            byte[] wrapped = wrapOAEP(parameters.publicKeyEncoded(), OAEP_SHA_256, material(32, (byte) 1));

            String keyId = key.getKeyId();
            String token = parameters.importToken();
            long validTo = Instant.now().getEpochSecond() + 3600;
            AwsException ex = assertThrows(AwsException.class, () -> kmsService.importKeyMaterial(
                    keyId, token, wrapped, "KEY_MATERIAL_DOES_NOT_EXPIRE", validTo, null, REGION));
            assertEquals("ValidationException", ex.getErrorCode());
        }

        @Test
        void deletingImportedMaterialReturnsTheKeyToPendingImport() throws Exception {
            KmsKey key = externalSymmetricKey();
            importInto(key, material(32, (byte) 5), OAEP_SHA_256);

            KmsKey deleted = kmsService.deleteImportedKeyMaterial(key.getKeyId(), REGION);

            assertEquals("PendingImport", deleted.getKeyState());
            assertFalse(deleted.isEnabled());
            assertNull(deleted.getPrivateKeyEncoded());
        }

        @Test
        void reimportingTheSameMaterialRestoresTheKey() throws Exception {
            KmsKey key = externalSymmetricKey();
            byte[] rawMaterial = material(32, (byte) 5);
            importInto(key, rawMaterial, OAEP_SHA_256);
            kmsService.deleteImportedKeyMaterial(key.getKeyId(), REGION);

            KmsKey reimported = importInto(key, rawMaterial, OAEP_SHA_256);

            assertEquals("Enabled", reimported.getKeyState());
            assertTrue(reimported.isEnabled());
        }

        @Test
        void reimportingDifferentMaterialIsRejected() throws Exception {
            KmsKey key = externalSymmetricKey();
            importInto(key, material(32, (byte) 5), OAEP_SHA_256);
            kmsService.deleteImportedKeyMaterial(key.getKeyId(), REGION);

            AwsException ex = assertThrows(AwsException.class, () ->
                    importInto(key, material(32, (byte) 6), OAEP_SHA_256));
            assertEquals("IncorrectKeyMaterialException", ex.getErrorCode());
        }

        @Test
        void anHmacKeyMacsWithTheMaterialThatWasImported() throws Exception {
            KmsKey key = externalKey("HMAC_256", "GENERATE_VERIFY_MAC");
            byte[] rawMaterial = material(32, (byte) 42);
            byte[] message = "hello".getBytes(StandardCharsets.UTF_8);
            importInto(key, rawMaterial, OAEP_SHA_256);

            byte[] mac = kmsService.generateMac(key.getKeyId(), message, "HMAC_SHA_256", REGION);

            Mac expected = Mac.getInstance("HmacSHA256");
            expected.init(new SecretKeySpec(rawMaterial, "HmacSHA256"));
            assertArrayEquals(expected.doFinal(message), mac);
        }

        @Test
        void macIsRejectedWhileTheHmacKeyHasNoMaterial() {
            String keyId = externalKey("HMAC_256", "GENERATE_VERIFY_MAC").getKeyId();
            byte[] message = "hello".getBytes(StandardCharsets.UTF_8);

            AwsException ex = assertThrows(AwsException.class, () ->
                    kmsService.generateMac(keyId, message, "HMAC_SHA_256", REGION));
            assertEquals("KMSInvalidStateException", ex.getErrorCode());
        }

        @Test
        void cancellingDeletionLeavesAKeyWithNoMaterialInPendingImport() {
            KmsKey key = externalSymmetricKey();
            kmsService.scheduleKeyDeletion(key.getKeyId(), 7, REGION);

            kmsService.cancelKeyDeletion(key.getKeyId(), REGION);

            KmsKey restored = kmsService.describeKey(key.getKeyId(), REGION);
            assertEquals("PendingImport", restored.getKeyState());
            assertFalse(restored.isEnabled());
        }

        @Test
        void cancellingDeletionAfterTheMaterialWasDeletedKeepsTheKeyUnusable() throws Exception {
            KmsKey key = externalSymmetricKey();
            importInto(key, material(32, (byte) 5), OAEP_SHA_256);
            kmsService.scheduleKeyDeletion(key.getKeyId(), 7, REGION);
            kmsService.deleteImportedKeyMaterial(key.getKeyId(), REGION);

            kmsService.cancelKeyDeletion(key.getKeyId(), REGION);

            String keyId = key.getKeyId();
            assertEquals("PendingImport", kmsService.describeKey(keyId, REGION).getKeyState());
            byte[] plaintext = "hello".getBytes(StandardCharsets.UTF_8);
            AwsException ex = assertThrows(AwsException.class, () ->
                    kmsService.encrypt(keyId, plaintext, REGION));
            assertEquals("KMSInvalidStateException", ex.getErrorCode());
        }

        @Test
        void cancellingDeletionRestoresAKeyThatStillHasItsMaterial() throws Exception {
            KmsKey key = externalSymmetricKey();
            importInto(key, material(32, (byte) 5), OAEP_SHA_256);
            kmsService.scheduleKeyDeletion(key.getKeyId(), 7, REGION);

            kmsService.cancelKeyDeletion(key.getKeyId(), REGION);

            KmsKey restored = kmsService.describeKey(key.getKeyId(), REGION);
            assertEquals("Enabled", restored.getKeyState());
            assertTrue(restored.isEnabled());
            assertNotNull(kmsService.encrypt(key.getKeyId(), "hello".getBytes(StandardCharsets.UTF_8), REGION));
        }

        /**
         * The AWS key state table marks DeleteImportedKeyMaterial as successful in the Pending
         * import column, so a key with nothing to delete is not an error.
         */
        @Test
        void deletingMaterialFromAKeyThatHasNoneSucceeds() {
            String keyId = externalSymmetricKey().getKeyId();

            KmsKey deleted = kmsService.deleteImportedKeyMaterial(keyId, REGION);

            assertEquals("PendingImport", deleted.getKeyState());
            assertNull(deleted.getKeyMaterialId());
        }

        @ParameterizedTest
        @CsvSource({
                "RSA_2048, RSA_AES_KEY_WRAP_SHA_1, RSA_2048",
                "RSA_3072, RSA_AES_KEY_WRAP_SHA_256, RSA_3072",
                "RSA_4096, RSA_AES_KEY_WRAP_SHA_256, RSA_4096"
        })
        void supportedRsaKeyAndWrappingSpecsImportUsablePrivateKeyMaterial(
                String keySpec, String wrappingAlgorithm, String wrappingKeySpec) throws Exception {
            KmsKey key = externalKey(keySpec, "SIGN_VERIFY");
            KeyPair importedKeyPair = rsaKeyPair(keySpec);

            KmsKey imported = importInto(key, importedKeyPair.getPrivate().getEncoded(),
                    wrappingAlgorithm, wrappingKeySpec);

            assertEquals("Enabled", imported.getKeyState());
            assertTrue(imported.isEnabled());
            assertArrayEquals(importedKeyPair.getPublic().getEncoded(),
                    Base64.getDecoder().decode(imported.getPublicKeyEncoded()));

            byte[] message = "imported RSA key".getBytes(StandardCharsets.UTF_8);
            byte[] signature = kmsService.sign(key.getKeyId(), message,
                    "RSASSA_PKCS1_V1_5_SHA_256", REGION);
            Signature verifier = Signature.getInstance("SHA256withRSA");
            verifier.initVerify(importedKeyPair.getPublic());
            verifier.update(message);
            assertTrue(verifier.verify(signature));
        }

        @Test
        void rsaImportRejectsAModulusThatDoesNotMatchTheKeySpec() throws Exception {
            KmsKey key = externalKey("RSA_2048", "SIGN_VERIFY");
            byte[] rsa3072Material = rsaKeyPair("RSA_3072").getPrivate().getEncoded();

            AwsException ex = assertThrows(AwsException.class, () ->
                    importInto(key, rsa3072Material, RSA_AES_SHA_256));

            assertEquals("IncorrectKeyMaterialException", ex.getErrorCode());
            assertEquals("PendingImport", kmsService.describeKey(key.getKeyId(), REGION).getKeyState());
        }

        @Test
        void rsaImportRejectsInvalidPkcs8Material() {
            KmsKey key = externalKey("RSA_2048", "SIGN_VERIFY");

            AwsException ex = assertThrows(AwsException.class, () ->
                    importInto(key, material(32, (byte) 7), RSA_AES_SHA_256));

            assertEquals("IncorrectKeyMaterialException", ex.getErrorCode());
            assertEquals("PendingImport", kmsService.describeKey(key.getKeyId(), REGION).getKeyState());
        }

        @ParameterizedTest
        @CsvSource({
                "ECC_NIST_P256, RSA_AES_KEY_WRAP_SHA_256, RSA_2048",
                "ECC_NIST_P384, RSA_AES_KEY_WRAP_SHA_1, RSA_3072",
                "ECC_NIST_P521, RSA_AES_KEY_WRAP_SHA_256, RSA_4096",
                "ECC_SECG_P256K1, RSA_AES_KEY_WRAP_SHA_256, RSA_2048",
                "ECC_NIST_P256, RSAES_OAEP_SHA_256, RSA_2048",
                "ECC_NIST_P521, RSAES_OAEP_SHA_1, RSA_4096",
                "ECC_NIST_P521, RSAES_OAEP_SHA_256, RSA_3072",
                "ECC_NIST_P521, RSA_AES_KEY_WRAP_SHA_256, RSA_2048",
                "ECC_SECG_P256K1, RSAES_OAEP_SHA_256, RSA_3072"
        })
        void supportedEccKeyAndWrappingSpecsImportUsablePrivateKeyMaterial(
                String keySpec, String wrappingAlgorithm, String wrappingKeySpec) throws Exception {
            KmsKey key = externalKey(keySpec, "SIGN_VERIFY");
            KeyPair importedKeyPair = ecKeyPair(keySpec);

            KmsKey imported = importInto(key, importedKeyPair.getPrivate().getEncoded(),
                    wrappingAlgorithm, wrappingKeySpec);

            assertEquals("Enabled", imported.getKeyState());
            assertTrue(imported.isEnabled());
            assertArrayEquals(importedKeyPair.getPublic().getEncoded(),
                    Base64.getDecoder().decode(imported.getPublicKeyEncoded()));

            KmsKeySpec.Algorithm algorithm = KmsKeySpec.valueOf(keySpec).getAlgorithm().getFirst();
            byte[] message = "imported ECC key".getBytes(StandardCharsets.UTF_8);
            byte[] signature = kmsService.sign(key.getKeyId(), message, algorithm.name(), REGION);
            Signature verifier = Signature.getInstance(algorithm.getJavaName(), BouncyCastleProvider.PROVIDER_NAME);
            verifier.initVerify(importedKeyPair.getPublic());
            verifier.update(message);
            assertTrue(verifier.verify(signature));
        }

        @Test
        void eccImportAcceptsACompressedEmbeddedPublicKey() throws Exception {
            KmsKey key = externalKey("ECC_NIST_P256", "SIGN_VERIFY");
            KeyPair keyPair = ecKeyPair("ECC_NIST_P256");
            byte[] compressedPoint = ECNamedCurveTable.getByName("secp256r1").getCurve()
                    .decodePoint(ecPublicPoint(keyPair)).getEncoded(true);

            KmsKey imported = importInto(key, ecPkcs8(keyPair, "secp256r1", p256NamedCurve(), compressedPoint),
                    RSA_AES_SHA_256);

            assertEquals("Enabled", imported.getKeyState());
            assertArrayEquals(keyPair.getPublic().getEncoded(),
                    Base64.getDecoder().decode(imported.getPublicKeyEncoded()));
        }

        @Test
        void eccImportRejectsKeyMaterialOnAnotherCurve() throws Exception {
            KmsKey key = externalKey("ECC_NIST_P256", "SIGN_VERIFY");
            byte[] p384Material = ecKeyPair("ECC_NIST_P384").getPrivate().getEncoded();

            AwsException ex = assertThrows(AwsException.class, () ->
                    importInto(key, p384Material, RSA_AES_SHA_256));

            assertEquals("IncorrectKeyMaterialException", ex.getErrorCode());
            assertEquals("PendingImport", kmsService.describeKey(key.getKeyId(), REGION).getKeyState());
        }

        @Test
        void eccImportRejectsExplicitCurveParameters() throws Exception {
            KmsKey key = externalKey("ECC_NIST_P256", "SIGN_VERIFY");
            KeyPair keyPair = ecKeyPair("ECC_NIST_P256");
            AlgorithmIdentifier explicitCurve = new AlgorithmIdentifier(X9ObjectIdentifiers.id_ecPublicKey,
                    new X962Parameters(ECNamedCurveTable.getByName("secp256r1")));
            byte[] material = ecPkcs8(keyPair, "secp256r1", explicitCurve, ecPublicPoint(keyPair));

            AwsException ex = assertThrows(AwsException.class, () ->
                    importInto(key, material, RSA_AES_SHA_256));

            assertEquals("IncorrectKeyMaterialException", ex.getErrorCode());
            assertEquals("PendingImport", kmsService.describeKey(key.getKeyId(), REGION).getKeyState());
        }

        @Test
        void eccImportAcceptsInnerParametersNamingTheSameCurve() throws Exception {
            KmsKey key = externalKey("ECC_NIST_P256", "SIGN_VERIFY");
            KeyPair keyPair = ecKeyPair("ECC_NIST_P256");
            byte[] material = ecPkcs8(keyPair, "secp256r1", p256NamedCurve(), ecPublicPoint(keyPair),
                    X9ObjectIdentifiers.prime256v1);

            KmsKey imported = importInto(key, material, RSA_AES_SHA_256);

            assertEquals("Enabled", imported.getKeyState());
        }

        @Test
        void eccImportRejectsInnerParametersNamingAnotherCurve() throws Exception {
            KmsKey key = externalKey("ECC_NIST_P256", "SIGN_VERIFY");
            KeyPair keyPair = ecKeyPair("ECC_NIST_P256");
            byte[] material = ecPkcs8(keyPair, "secp256r1", p256NamedCurve(), ecPublicPoint(keyPair),
                    SECObjectIdentifiers.secp384r1);

            AwsException ex = assertThrows(AwsException.class, () ->
                    importInto(key, material, RSA_AES_SHA_256));

            assertEquals("IncorrectKeyMaterialException", ex.getErrorCode());
            assertEquals("PendingImport", kmsService.describeKey(key.getKeyId(), REGION).getKeyState());
        }

        @Test
        void eccImportRejectsExplicitInnerCurveParameters() throws Exception {
            KmsKey key = externalKey("ECC_NIST_P256", "SIGN_VERIFY");
            KeyPair keyPair = ecKeyPair("ECC_NIST_P256");
            byte[] material = ecPkcs8(keyPair, "secp256r1", p256NamedCurve(), ecPublicPoint(keyPair),
                    new X962Parameters(ECNamedCurveTable.getByName("secp256r1")));

            AwsException ex = assertThrows(AwsException.class, () ->
                    importInto(key, material, RSA_AES_SHA_256));

            assertEquals("IncorrectKeyMaterialException", ex.getErrorCode());
            assertEquals("PendingImport", kmsService.describeKey(key.getKeyId(), REGION).getKeyState());
        }

        @Test
        void eccImportRejectsAMalformedPrivateKeyField() throws Exception {
            KmsKey key = externalKey("ECC_NIST_P256", "SIGN_VERIFY");
            byte[] material = ecPkcs8WithRawFields(new ASN1Integer(7));

            AwsException ex = assertThrows(AwsException.class, () ->
                    importInto(key, material, RSA_AES_SHA_256));

            assertEquals("IncorrectKeyMaterialException", ex.getErrorCode());
            assertEquals("PendingImport", kmsService.describeKey(key.getKeyId(), REGION).getKeyState());
        }

        @Test
        void eccImportRejectsMalformedInnerParameters() throws Exception {
            KmsKey key = externalKey("ECC_NIST_P256", "SIGN_VERIFY");
            byte[] material = ecPkcs8WithRawFields(new DEROctetString(new byte[] {1}),
                    new DERTaggedObject(false, 0, new DERSequence(new ASN1Integer(3))));

            AwsException ex = assertThrows(AwsException.class, () ->
                    importInto(key, material, RSA_AES_SHA_256));

            assertEquals("IncorrectKeyMaterialException", ex.getErrorCode());
            assertEquals("PendingImport", kmsService.describeKey(key.getKeyId(), REGION).getKeyState());
        }

        @Test
        void eccImportRejectsAMalformedEmbeddedPublicKeyField() throws Exception {
            KmsKey key = externalKey("ECC_NIST_P256", "SIGN_VERIFY");
            byte[] material = ecPkcs8WithRawFields(new DEROctetString(new byte[] {1}),
                    new DERTaggedObject(true, 1, new ASN1Integer(5)));

            AwsException ex = assertThrows(AwsException.class, () ->
                    importInto(key, material, RSA_AES_SHA_256));

            assertEquals("IncorrectKeyMaterialException", ex.getErrorCode());
            assertEquals("PendingImport", kmsService.describeKey(key.getKeyId(), REGION).getKeyState());
        }

        @Test
        void eccImportRejectsAnEmbeddedPublicKeyThatDoesNotMatch() throws Exception {
            KmsKey key = externalKey("ECC_NIST_P256", "SIGN_VERIFY");
            KeyPair keyPair = ecKeyPair("ECC_NIST_P256");
            byte[] otherPublicPoint = ecPublicPoint(ecKeyPair("ECC_NIST_P256"));
            byte[] material = ecPkcs8(keyPair, "secp256r1", p256NamedCurve(), otherPublicPoint);

            AwsException ex = assertThrows(AwsException.class, () ->
                    importInto(key, material, RSA_AES_SHA_256));

            assertEquals("IncorrectKeyMaterialException", ex.getErrorCode());
            assertEquals("PendingImport", kmsService.describeKey(key.getKeyId(), REGION).getKeyState());
        }

        @Test
        void eccImportRejectsAnRsaPrivateKey() throws Exception {
            KmsKey key = externalKey("ECC_NIST_P256", "SIGN_VERIFY");
            byte[] rsaMaterial = rsaKeyPair("RSA_2048").getPrivate().getEncoded();

            AwsException ex = assertThrows(AwsException.class, () ->
                    importInto(key, rsaMaterial, RSA_AES_SHA_256));

            assertEquals("IncorrectKeyMaterialException", ex.getErrorCode());
            assertEquals("PendingImport", kmsService.describeKey(key.getKeyId(), REGION).getKeyState());
        }

        @Test
        void eccImportRejectsInvalidPkcs8Material() {
            KmsKey key = externalKey("ECC_SECG_P256K1", "SIGN_VERIFY");

            AwsException ex = assertThrows(AwsException.class, () ->
                    importInto(key, material(32, (byte) 7), RSA_AES_SHA_256));

            assertEquals("IncorrectKeyMaterialException", ex.getErrorCode());
            assertEquals("PendingImport", kmsService.describeKey(key.getKeyId(), REGION).getKeyState());
        }

        @ParameterizedTest
        @ValueSource(strings = {"ECC_NIST_P256", "ECC_NIST_P384", "ECC_NIST_P521", "ECC_SECG_P256K1"})
        void importedEccKeyVerifiesSignaturesAndSignsDigests(String keySpec) throws Exception {
            KmsKey key = externalKey(keySpec, "SIGN_VERIFY");
            KeyPair importedKeyPair = ecKeyPair(keySpec);
            importInto(key, importedKeyPair.getPrivate().getEncoded(), RSA_AES_SHA_256, "RSA_4096");
            KmsKeySpec.Algorithm algorithm = KmsKeySpec.valueOf(keySpec).getAlgorithm().getFirst();
            byte[] message = "imported ECC key".getBytes(StandardCharsets.UTF_8);

            Signature signer = Signature.getInstance(algorithm.getJavaName(), BouncyCastleProvider.PROVIDER_NAME);
            signer.initSign(importedKeyPair.getPrivate());
            signer.update(message);
            byte[] externalSignature = signer.sign();
            assertDoesNotThrow(() -> kmsService.verify(key.getKeyId(), message, externalSignature,
                    algorithm.name(), REGION));
            byte[] tampered = "tampered message".getBytes(StandardCharsets.UTF_8);
            AwsException ex = assertThrows(AwsException.class, () -> kmsService.verify(key.getKeyId(), tampered,
                    externalSignature, algorithm.name(), REGION));
            assertEquals("KMSInvalidSignatureException", ex.getErrorCode());

            byte[] digest = MessageDigest.getInstance(algorithm.getJavaName().substring(0, 6)).digest(message);
            byte[] digestSignature = kmsService.sign(key.getKeyId(), digest, algorithm.name(),
                    KmsMessageType.DIGEST, REGION);
            Signature verifier = Signature.getInstance(algorithm.getJavaName(), BouncyCastleProvider.PROVIDER_NAME);
            verifier.initVerify(importedKeyPair.getPublic());
            verifier.update(message);
            assertTrue(verifier.verify(digestSignature));
        }

        @Test
        void reimportingTheSameEccKeyAfterDeletionRestoresIt() throws Exception {
            KmsKey key = externalKey("ECC_SECG_P256K1", "SIGN_VERIFY");
            KeyPair keyPair = ecKeyPair("ECC_SECG_P256K1");
            byte[] material = keyPair.getPrivate().getEncoded();
            importInto(key, material, RSA_AES_SHA_256);

            KmsKey deleted = kmsService.deleteImportedKeyMaterial(key.getKeyId(), REGION);
            assertEquals("PendingImport", deleted.getKeyState());
            assertNull(deleted.getPrivateKeyEncoded());

            KmsKey reimported = importInto(key, material, RSA_AES_SHA_256);
            assertEquals("Enabled", reimported.getKeyState());
            assertArrayEquals(keyPair.getPublic().getEncoded(),
                    Base64.getDecoder().decode(reimported.getPublicKeyEncoded()));
        }

        @Test
        void reimportingADifferentEccKeyIsRejected() throws Exception {
            KmsKey key = externalKey("ECC_NIST_P384", "SIGN_VERIFY");
            importInto(key, ecKeyPair("ECC_NIST_P384").getPrivate().getEncoded(), RSA_AES_SHA_256);
            kmsService.deleteImportedKeyMaterial(key.getKeyId(), REGION);
            byte[] otherKey = ecKeyPair("ECC_NIST_P384").getPrivate().getEncoded();

            AwsException ex = assertThrows(AwsException.class, () ->
                    importInto(key, otherKey, RSA_AES_SHA_256));

            assertEquals("IncorrectKeyMaterialException", ex.getErrorCode());
            assertEquals("PendingImport", kmsService.describeKey(key.getKeyId(), REGION).getKeyState());
        }

        @Test
        void expiringEccMaterialReturnsTheKeyToPendingImport() throws Exception {
            KmsKey key = externalKey("ECC_NIST_P256", "SIGN_VERIFY");
            KmsService.ImportParameters parameters =
                    kmsService.getParametersForImport(key.getKeyId(), RSA_AES_SHA_256, "RSA_2048", REGION);
            byte[] wrapped = wrapRsaAes(parameters.publicKeyEncoded(), RSA_AES_SHA_256,
                    ecKeyPair("ECC_NIST_P256").getPrivate().getEncoded(), material(32, (byte) 91));
            kmsService.importKeyMaterial(key.getKeyId(), parameters.importToken(), wrapped,
                    "KEY_MATERIAL_EXPIRES", Instant.now().getEpochSecond() + 3600, null, REGION);

            KmsKey stored = keyStore.get(REGION + "::" + key.getKeyId()).orElseThrow();
            stored.setValidTo(Instant.now().getEpochSecond() - 60);
            keyStore.put(REGION + "::" + key.getKeyId(), stored);

            KmsKey expired = kmsService.describeKey(key.getKeyId(), REGION);
            assertEquals("PendingImport", expired.getKeyState());
            assertFalse(expired.isEnabled());
            assertNull(expired.getPrivateKeyEncoded());
        }

        @Test
        void aReplicaOfAnImportedEccKeyAcceptsTheSameKey() throws Exception {
            String replicaRegion = "us-west-2";
            KmsKey primary = kmsService.createKey("external primary", "SIGN_VERIFY", "ECC_NIST_P256",
                    null, Map.of(), "EXTERNAL", true, REGION);
            KeyPair keyPair = ecKeyPair("ECC_NIST_P256");
            importInto(primary, keyPair.getPrivate().getEncoded(), RSA_AES_SHA_256);
            KmsKey replica = kmsService.replicateKey(primary.getKeyId(), null, null, Map.of(), replicaRegion, REGION);
            assertEquals("PendingImport", replica.getKeyState());

            KmsService.ImportParameters parameters = kmsService.getParametersForImport(
                    replica.getKeyId(), RSA_AES_SHA_256, "RSA_2048", replicaRegion);
            byte[] wrapped = wrapRsaAes(parameters.publicKeyEncoded(), RSA_AES_SHA_256,
                    keyPair.getPrivate().getEncoded(), material(32, (byte) 91));
            KmsKey imported = kmsService.importKeyMaterial(replica.getKeyId(), parameters.importToken(), wrapped,
                    "KEY_MATERIAL_DOES_NOT_EXPIRE", null, null, replicaRegion);

            assertEquals("Enabled", imported.getKeyState());
            assertArrayEquals(keyPair.getPublic().getEncoded(),
                    Base64.getDecoder().decode(imported.getPublicKeyEncoded()));
        }

        @ParameterizedTest
        @ValueSource(strings = {"RSAES_OAEP_SHA_1", "RSAES_OAEP_SHA_256"})
        void p521MaterialRejectsRsaesOaepUnderAnRsa2048WrappingKey(String wrappingAlgorithm) {
            String keyId = externalKey("ECC_NIST_P521", "SIGN_VERIFY").getKeyId();

            AwsException ex = assertThrows(AwsException.class, () ->
                    kmsService.getParametersForImport(keyId, wrappingAlgorithm, "RSA_2048", REGION));

            assertEquals("UnsupportedOperationException", ex.getErrorCode());
        }

        @ParameterizedTest
        @ValueSource(strings = {"ECC_NIST_EDWARDS25519", "ML_DSA_44"})
        void asymmetricKeySpecsWithoutImportSupportCannotUseExternalOrigin(String keySpec) {
            AwsException ex = assertThrows(AwsException.class, () ->
                    externalKey(keySpec, "SIGN_VERIFY"));

            assertEquals("UnsupportedOperationException", ex.getErrorCode());
        }

        @Test
        void importIsRejectedOnAKeyWhoseMaterialKmsGenerated() {
            String keyId = kmsService.createKey("aws managed", REGION).getKeyId();

            AwsException ex = assertThrows(AwsException.class, () ->
                    kmsService.getParametersForImport(keyId, OAEP_SHA_256, "RSA_2048", REGION));
            assertEquals("UnsupportedOperationException", ex.getErrorCode());
        }

        @Test
        void automaticRotationIsRejectedForImportedMaterial() throws Exception {
            KmsKey key = externalSymmetricKey();
            importInto(key, material(32, (byte) 5), OAEP_SHA_256);

            String keyId = key.getKeyId();
            AwsException ex = assertThrows(AwsException.class, () ->
                    kmsService.enableKeyRotation(keyId, REGION));
            assertEquals("UnsupportedOperationException", ex.getErrorCode());
        }

        @Test
        void asymmetricKeyRejectsRsaAesWrappingBeforeParametersAreIssued() {
            String keyId = externalSymmetricKey().getKeyId();

            AwsException ex = assertThrows(AwsException.class, () ->
                    kmsService.getParametersForImport(keyId, RSA_AES_SHA_256, "RSA_2048", REGION));
            assertEquals("UnsupportedOperationException", ex.getErrorCode());
        }

        @Test
        void rsaKeyRejectsDirectRsaOaepWrappingBeforeParametersAreIssued() {
            String keyId = externalKey("RSA_2048", "ENCRYPT_DECRYPT").getKeyId();

            AwsException ex = assertThrows(AwsException.class, () ->
                    kmsService.getParametersForImport(keyId, OAEP_SHA_256, "RSA_2048", REGION));

            assertEquals("UnsupportedOperationException", ex.getErrorCode());
        }

        @Test
        void unmodelledWrappingAlgorithmIsAValidationError() {
            String keyId = externalSymmetricKey().getKeyId();

            AwsException ex = assertThrows(AwsException.class, () ->
                    kmsService.getParametersForImport(keyId, "NOT_AN_ALGORITHM", "RSA_2048", REGION));

            assertEquals("ValidationException", ex.getErrorCode());
        }

        @Test
        void rsaAesWrappingRejectsANon256BitAesKey() throws Exception {
            KmsKey key = externalKey("RSA_2048", "SIGN_VERIFY");
            KmsService.ImportParameters parameters = kmsService.getParametersForImport(
                    key.getKeyId(), RSA_AES_SHA_256, "RSA_2048", REGION);
            byte[] rsaMaterial = rsaKeyPair("RSA_2048").getPrivate().getEncoded();
            byte[] wrapped = wrapRsaAes(parameters.publicKeyEncoded(), RSA_AES_SHA_256, rsaMaterial,
                    material(16, (byte) 17));

            AwsException ex = assertThrows(AwsException.class, () -> kmsService.importKeyMaterial(
                    key.getKeyId(), parameters.importToken(), wrapped,
                    "KEY_MATERIAL_DOES_NOT_EXPIRE", null, null, REGION));

            assertEquals("InvalidCiphertextException", ex.getErrorCode());
            assertEquals("PendingImport", kmsService.describeKey(key.getKeyId(), REGION).getKeyState());
        }

        @Test
        void aSymmetricKeyEncryptsWithTheMaterialThatWasImported() throws Exception {
            KmsKey key = externalSymmetricKey();
            byte[] rawMaterial = material(32, (byte) 42);
            byte[] plaintext = "bring-your-own-key".getBytes(StandardCharsets.UTF_8);
            importInto(key, rawMaterial, OAEP_SHA_256);

            byte[] envelope = kmsService.encrypt(key.getKeyId(), plaintext, REGION);

            // Open the documented KMS3 envelope with nothing but the imported material: the header
            // up to and including the 12-byte IV is the AAD (an empty EncryptionContext adds none)
            // and the 16-byte GCM tag trails the ciphertext.
            int ivEnd = envelope.length - plaintext.length - 16;
            byte[] headerAndIv = Arrays.copyOfRange(envelope, 0, ivEnd);
            byte[] iv = Arrays.copyOfRange(envelope, ivEnd - 12, ivEnd);
            Cipher aesGcm = Cipher.getInstance("AES/GCM/NoPadding");
            aesGcm.init(Cipher.DECRYPT_MODE, new SecretKeySpec(rawMaterial, "AES"), new GCMParameterSpec(128, iv));
            aesGcm.updateAAD(headerAndIv);
            assertArrayEquals(plaintext, aesGcm.doFinal(Arrays.copyOfRange(envelope, ivEnd, envelope.length)));
        }

        @Test
        void legacyImportedSymmetricMaterialIsMigratedToTheEnvelopeBackingKey() {
            KmsKey key = externalSymmetricKey();
            byte[] material = material(32, (byte) 11);
            key.setPrivateKeyEncoded(Base64.getEncoder().encodeToString(material));
            key.setKeyState("Enabled");
            key.setEnabled(true);
            key.setBackingKeys(new HashMap<>());
            key.setCurrentBackingKeyId(null);
            keyStore.put(REGION + "::" + key.getKeyId(), key);

            byte[] ciphertext = kmsService.encrypt(key.getKeyId(), "legacy-import".getBytes(StandardCharsets.UTF_8), REGION);

            assertArrayEquals("legacy-import".getBytes(StandardCharsets.UTF_8), kmsService.decrypt(ciphertext, REGION));
            KmsKey migrated = keyStore.get(REGION + "::" + key.getKeyId()).orElseThrow();
            assertEquals(1, migrated.getBackingKeys().size());
            assertNotNull(migrated.getCurrentBackingKeyId());
        }

        @Test
        void deletingImportedMaterialLeavesNothingThatCanOpenItsCiphertext() throws Exception {
            KmsKey key = externalSymmetricKey();
            importInto(key, material(32, (byte) 42), OAEP_SHA_256);
            byte[] ciphertext = kmsService.encrypt(key.getKeyId(), "bring-your-own-key".getBytes(StandardCharsets.UTF_8), REGION);

            kmsService.deleteImportedKeyMaterial(key.getKeyId(), REGION);

            AwsException ex = assertThrows(AwsException.class, () -> kmsService.decrypt(ciphertext, REGION));
            assertEquals("InvalidCiphertextException", ex.getErrorCode());
        }

        @Test
        void decryptingUnderAKeyWhoseMaterialWasDeletedReportsPendingImport() throws Exception {
            KmsKey key = externalSymmetricKey();
            importInto(key, material(32, (byte) 42), OAEP_SHA_256);
            byte[] ciphertext = kmsService.encrypt(key.getKeyId(), "bring-your-own-key".getBytes(StandardCharsets.UTF_8), REGION);

            kmsService.deleteImportedKeyMaterial(key.getKeyId(), REGION);

            AwsException ex = assertThrows(AwsException.class, () ->
                    kmsService.decryptAndResolveKey(ciphertext, Map.of(), REGION, null));
            assertEquals("KMSInvalidStateException", ex.getErrorCode());
        }

        @Test
        void reimportingTheSameMaterialOpensCiphertextMadeBeforeTheDeletion() throws Exception {
            KmsKey key = externalSymmetricKey();
            byte[] rawMaterial = material(32, (byte) 42);
            byte[] plaintext = "bring-your-own-key".getBytes(StandardCharsets.UTF_8);
            importInto(key, rawMaterial, OAEP_SHA_256);
            byte[] ciphertext = kmsService.encrypt(key.getKeyId(), plaintext, REGION);
            kmsService.deleteImportedKeyMaterial(key.getKeyId(), REGION);

            importInto(key, rawMaterial, OAEP_SHA_256);

            assertArrayEquals(plaintext, kmsService.decrypt(ciphertext, REGION));
        }
    }

    /**
     * The v2 blob ("kms:v2:&lt;keyId&gt;:&lt;nonce&gt;:&lt;contextFingerprint&gt;:&lt;base64(plaintext)&gt;")
     * carried no real encryption: the payload was plain base64 with no key material involved, so
     * anyone holding a CiphertextBlob could read the plaintext and a tampered blob still "decrypted".
     * These tests pin the AES-GCM envelope that replaces it: real per-CMK key material, AEAD binding
     * of key id / backing key id / EncryptionContext, and rejection of any tampering.
     */
    @Nested
    class EnvelopeEncryptionTests {

        @Test
        void encryptDoesNotProduceLegacyBase64Blob() {
            KmsKey key = kmsService.createKey(null, REGION);
            byte[] plaintext = "super-secret-value".getBytes(StandardCharsets.UTF_8);

            byte[] ciphertext = kmsService.encrypt(key.getKeyId(), plaintext, REGION);

            String asText = new String(ciphertext, StandardCharsets.UTF_8);
            assertFalse(asText.startsWith("kms:"),
                    "encrypt must not produce the legacy plaintext-revealing blob format");
            byte[] plaintextBase64 = Base64.getEncoder().encodeToString(plaintext).getBytes(StandardCharsets.UTF_8);
            assertEquals(-1, indexOf(ciphertext, plaintextBase64),
                    "ciphertext must not contain the base64 plaintext as a substring");
        }

        @Test
        void decryptRoundTripsTheNewEnvelope() {
            KmsKey key = kmsService.createKey(null, REGION);
            byte[] plaintext = "hello envelope".getBytes(StandardCharsets.UTF_8);

            byte[] ciphertext = kmsService.encrypt(key.getKeyId(), plaintext, Map.of("tenant", "1"), REGION);

            assertArrayEquals(plaintext, kmsService.decrypt(ciphertext, Map.of("tenant", "1"), REGION));
        }

        @Test
        void decryptWithFlippedTagByteThrowsInvalidCiphertext() {
            KmsKey key = kmsService.createKey(null, REGION);
            byte[] ciphertext = kmsService.encrypt(key.getKeyId(), "hello world".getBytes(StandardCharsets.UTF_8), REGION);

            byte[] tampered = ciphertext.clone();
            tampered[tampered.length - 1] ^= 0x01;

            AwsException ex = assertThrows(AwsException.class, () -> kmsService.decrypt(tampered, REGION));
            assertEquals("InvalidCiphertextException", ex.getErrorCode());
        }

        @Test
        void decryptWithFlippedIvByteThrowsInvalidCiphertext() {
            KmsKey key = kmsService.createKey(null, REGION);
            byte[] plaintext = "hello world".getBytes(StandardCharsets.UTF_8);
            byte[] ciphertext = kmsService.encrypt(key.getKeyId(), plaintext, REGION);

            int gcmTagBytes = 16;
            int gcmIvBytes = 12;
            int ivStart = ciphertext.length - gcmIvBytes - (plaintext.length + gcmTagBytes);
            byte[] tampered = ciphertext.clone();
            tampered[ivStart] ^= 0x01;

            AwsException ex = assertThrows(AwsException.class, () -> kmsService.decrypt(tampered, REGION));
            assertEquals("InvalidCiphertextException", ex.getErrorCode());
        }

        @Test
        void decryptWithCorruptedKeyIdInHeaderThrowsInvalidCiphertextNeverAPlaintext() {
            KmsKey key = kmsService.createKey(null, REGION);
            byte[] plaintext = "hello world".getBytes(StandardCharsets.UTF_8);
            byte[] ciphertext = kmsService.encrypt(key.getKeyId(), plaintext, REGION);

            // magic(4) + version(1) + keyId length prefix(2) = keyId bytes start at index 7.
            int keyIdStart = 7;
            byte[] tampered = ciphertext.clone();
            tampered[keyIdStart] ^= 0x01;

            AwsException ex = assertThrows(AwsException.class, () -> kmsService.decrypt(tampered, REGION));
            assertEquals("InvalidCiphertextException", ex.getErrorCode());
        }

        @Test
        void decryptWithCorruptedBackingKeyIdInHeaderThrowsInvalidCiphertext() {
            KmsKey key = kmsService.createKey(null, REGION);
            byte[] ciphertext = kmsService.encrypt(key.getKeyId(), "hello world".getBytes(StandardCharsets.UTF_8), REGION);

            int backingKeyIdStart = 4 + 1 + 2 + key.getKeyId().getBytes(StandardCharsets.UTF_8).length + 2;
            byte[] tampered = ciphertext.clone();
            tampered[backingKeyIdStart] ^= 0x01;

            AwsException ex = assertThrows(AwsException.class, () -> kmsService.decrypt(tampered, REGION));
            assertEquals("InvalidCiphertextException", ex.getErrorCode());
        }

        @Test
        void decryptOfTruncatedEnvelopeThrowsInvalidCiphertext() {
            KmsKey key = kmsService.createKey(null, REGION);
            byte[] ciphertext = kmsService.encrypt(key.getKeyId(), "hello world".getBytes(StandardCharsets.UTF_8), REGION);

            byte[] truncated = Arrays.copyOf(ciphertext, ciphertext.length - 20);

            AwsException ex = assertThrows(AwsException.class, () -> kmsService.decrypt(truncated, REGION));
            assertEquals("InvalidCiphertextException", ex.getErrorCode());
        }

        @Test
        void rotateKeyOnDemandKeepsOldCiphertextDecryptableAndMintsANewBackingKey() {
            KmsKey key = kmsService.createKey(null, REGION);
            byte[] plaintext = "before-rotation".getBytes(StandardCharsets.UTF_8);
            byte[] beforeRotation = kmsService.encrypt(key.getKeyId(), plaintext, REGION);

            kmsService.rotateKeyOnDemand(key.getKeyId(), REGION);
            byte[] afterRotation = kmsService.encrypt(key.getKeyId(), plaintext, REGION);

            assertArrayEquals(plaintext, kmsService.decrypt(beforeRotation, REGION));
            assertArrayEquals(plaintext, kmsService.decrypt(afterRotation, REGION));
            assertFalse(Arrays.equals(beforeRotation, afterRotation));

            KmsKey stored = keyStore.get(REGION + "::" + key.getKeyId()).orElseThrow();
            assertEquals(2, stored.getBackingKeys().size());
        }

        @Test
        void concurrentOnDemandRotationsKeepEveryRotation() throws Exception {
            KmsKey key = kmsService.createKey(null, REGION);
            int rotationCount = 20;
            CountDownLatch startGate = new CountDownLatch(1);
            List<Future<String>> futures = new ArrayList<>();

            try (ExecutorService executor = Executors.newFixedThreadPool(rotationCount)) {
                for (int i = 0; i < rotationCount; i++) {
                    futures.add(executor.submit(() -> {
                        startGate.await();
                        return kmsService.rotateKeyOnDemand(key.getKeyId(), REGION);
                    }));
                }
                startGate.countDown();
                for (Future<String> future : futures) {
                    assertEquals(key.getKeyId(), future.get(10, TimeUnit.SECONDS));
                }
            }

            KmsKey stored = keyStore.get(REGION + "::" + key.getKeyId()).orElseThrow();
            assertEquals(rotationCount, stored.getOnDemandRotationCount());
            assertEquals(rotationCount + 1, stored.getBackingKeys().size());
        }

        @Test
        void legacyKeyWithoutBackingMaterialLazilyGeneratesItOnUse() {
            KmsKey key = kmsService.createKey(null, REGION);
            KmsKey stored = keyStore.get(REGION + "::" + key.getKeyId()).orElseThrow();
            // Simulate a key persisted before this fix: no backing key material on disk.
            stored.setBackingKeys(new HashMap<>());
            stored.setCurrentBackingKeyId(null);
            keyStore.put(REGION + "::" + key.getKeyId(), stored);

            byte[] plaintext = "legacy-key-plaintext".getBytes(StandardCharsets.UTF_8);
            byte[] ciphertext = kmsService.encrypt(key.getKeyId(), plaintext, REGION);
            assertArrayEquals(plaintext, kmsService.decrypt(ciphertext, REGION));

            KmsKey healed = keyStore.get(REGION + "::" + key.getKeyId()).orElseThrow();
            assertNotNull(healed.getCurrentBackingKeyId());
            assertFalse(healed.getBackingKeys().isEmpty());
        }

        @Test
        void concurrentFirstUsesOfALegacyKeyMintExactlyOneBackingKey() throws Exception {
            KmsKey key = kmsService.createKey(null, REGION);
            KmsKey stored = keyStore.get(REGION + "::" + key.getKeyId()).orElseThrow();
            // Simulate a key persisted before this fix: no backing key material on disk.
            stored.setBackingKeys(new HashMap<>());
            stored.setCurrentBackingKeyId(null);
            keyStore.put(REGION + "::" + key.getKeyId(), stored);

            int threadCount = 12;
            byte[] plaintext = "concurrent-legacy-plaintext".getBytes(StandardCharsets.UTF_8);
            CountDownLatch startGate = new CountDownLatch(1);
            List<Future<byte[]>> futures = new ArrayList<>();

            try (ExecutorService executor = Executors.newFixedThreadPool(threadCount)) {
                for (int i = 0; i < threadCount; i++) {
                    futures.add(executor.submit(() -> {
                        startGate.await();
                        return kmsService.encrypt(key.getKeyId(), plaintext, REGION);
                    }));
                }
                startGate.countDown();

                List<byte[]> ciphertexts = new ArrayList<>();
                for (Future<byte[]> future : futures) {
                    ciphertexts.add(future.get(10, TimeUnit.SECONDS));
                }

                // Every thread must have healed onto the same backing key: exactly one backing key
                // was minted for the whole race, never one per racing thread.
                KmsKey healed = keyStore.get(REGION + "::" + key.getKeyId()).orElseThrow();
                assertNotNull(healed.getCurrentBackingKeyId());
                assertEquals(1, healed.getBackingKeys().size(),
                        "concurrent first uses of a legacy key must mint exactly one backing key");
                assertTrue(healed.getBackingKeys().containsKey(healed.getCurrentBackingKeyId()));

                // Every ciphertext produced under the race, regardless of which thread produced it,
                // must still decrypt: none of them can have been encrypted under a backing key that
                // a losing keyStore.put then discarded.
                for (byte[] ciphertext : ciphertexts) {
                    assertArrayEquals(plaintext, kmsService.decrypt(ciphertext, REGION));
                }
            }
        }

        private static int indexOf(byte[] haystack, byte[] needle) {
            outer:
            for (int i = 0; i <= haystack.length - needle.length; i++) {
                for (int j = 0; j < needle.length; j++) {
                    if (haystack[i + j] != needle[j]) {
                        continue outer;
                    }
                }
                return i;
            }
            return -1;
        }
    }
}
