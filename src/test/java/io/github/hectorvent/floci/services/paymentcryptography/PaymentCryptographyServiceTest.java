package io.github.hectorvent.floci.services.paymentcryptography;

import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.core.common.PaginatedResult;
import io.github.hectorvent.floci.core.common.RegionResolver;
import io.github.hectorvent.floci.core.storage.InMemoryStorage;
import io.github.hectorvent.floci.services.paymentcryptography.model.PaymentCryptographyAlias;
import io.github.hectorvent.floci.services.paymentcryptography.model.PaymentCryptographyKey;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PaymentCryptographyServiceTest {

    private PaymentCryptographyService service;

    @BeforeEach
    void setUp() {
        service = new PaymentCryptographyService(
                new InMemoryStorage<>(),
                new InMemoryStorage<>(),
                new RegionResolver("us-east-1", "000000000000"),
                new PaymentCryptographyRequestContext());
    }

    private PaymentCryptographyKey createKey(String region) {
        Map<String, Object> attributes = new LinkedHashMap<>();
        attributes.put("KeyAlgorithm", "AES_256");
        attributes.put("KeyClass", "SYMMETRIC_KEY");
        attributes.put("KeyUsage", "TR31_D0_SYMMETRIC_DATA_ENCRYPTION_KEY");
        Map<String, Boolean> modes = new LinkedHashMap<>();
        modes.put("Encrypt", Boolean.TRUE);
        modes.put("Decrypt", Boolean.TRUE);
        return service.createKey(region, attributes, modes, true, true, Map.of());
    }

    @Test
    void aCreatedKeyIsInItsRegionAndCarriesMaterial() {
        PaymentCryptographyKey key = createKey("us-east-1");

        assertTrue(key.getArn().startsWith("arn:aws:payment-cryptography:us-east-1:000000000000:key/"),
                key.getArn());
        assertEquals("CREATE_COMPLETE", key.getKeyState());
        assertEquals(64, key.getKeyMaterialHex().length());
        assertNotNull(key.getKeyCheckValue());
        assertEquals(6, key.getKeyCheckValue().length());
    }

    @Test
    void theCheckValueIsTheLeftmostThreeBytesOfTheCmac() {
        PaymentCryptographyKey key = createKey("us-east-1");

        // The CMAC of sixteen zero bytes under the key, leftmost three bytes.
        assertEquals(PaymentCryptographyCrypto.checkValue(key.getKeyMaterialHex()),
                key.getKeyCheckValue());
    }

    @Test
    void theKeyCarriesTheModesItWasAskedFor() {
        PaymentCryptographyKey key = createKey("us-east-1");

        assertEquals(Boolean.TRUE, key.getKeyModesOfUse().get("Encrypt"));
        assertEquals(Boolean.TRUE, key.getKeyModesOfUse().get("Decrypt"));
    }

    @Test
    void aKeyInAnotherRegionIsNotThisRegionsKey() {
        PaymentCryptographyKey west = createKey("us-west-2");

        assertNull(service.findKey("us-east-1", west.getArn()),
                "a key in another region was found in this one");
        assertNull(service.findKey("us-east-1", west.getKeyId()),
                "a key in another region was found in this one");
        assertNotNull(service.findKey("us-west-2", west.getArn()));
    }

    @Test
    void twoKeysGetDifferentMaterial() {
        PaymentCryptographyKey first = createKey("us-east-1");
        PaymentCryptographyKey second = createKey("us-east-1");

        assertTrue(!first.getKeyMaterialHex().equals(second.getKeyMaterialHex()),
                "two keys share their material");
        // The check value is three bytes of the CMAC of the key, so two keys may
        // legitimately share one. Only the material has to differ.
    }

    @Test
    void theAliasNameIsUniqueInARegion() {
        PaymentCryptographyKey key = createKey("us-east-1");
        service.createAlias("alias/team/card-key", key);

        AwsException conflict = assertThrows(AwsException.class,
                () -> service.createAlias("alias/team/card-key", createKey("us-east-1")));
        assertEquals("ConflictException", conflict.getErrorCode());
    }

    @Test
    void theSameAliasNameInAnotherRegionIsAnotherAlias() {
        service.createAlias("alias/team/card-key", createKey("us-east-1"));
        PaymentCryptographyAlias second = service.createAlias("alias/team/card-key",
                createKey("us-west-2"));

        assertEquals("us-west-2", second.getRegion());
        assertEquals(1, service.listAliases("us-east-1", null).size());
        assertEquals(1, service.listAliases("us-west-2", null).size());
    }

    @Test
    void theTagsAreTheKeysOwn() {
        PaymentCryptographyKey key = createKey("us-east-1");
        service.tag(key, Map.of("Origin", "PRIMARY"));

        assertEquals("PRIMARY", service.listTags(key).get("Origin"));
        assertEquals(1, service.listTags(key).size());

        service.untag(key, List.of("Origin"));
        assertTrue(service.listTags(key).isEmpty());
    }

    @Test
    void aKeyIsFoundByItsArnAndByItsAlias() {
        PaymentCryptographyKey key = createKey("us-east-1");
        service.createAlias("alias/team/card-key", key);

        assertEquals(key.getKeyId(), service.findKey("us-east-1", key.getArn()).getKeyId());
        assertEquals(key.getKeyId(),
                service.findKey("us-east-1", "alias/team/card-key").getKeyId());
        assertNull(service.findKey("us-west-2", "alias/team/card-key"),
                "an alias of one region is not an alias of another");
    }

    @Test
    void aDeletedKeyTakesItsAliasesWithIt() {
        PaymentCryptographyKey key = createKey("us-east-1");
        service.createAlias("alias/team/card-key", key);
        service.deleteKey(key);

        assertNull(service.findKey("us-east-1", key.getArn()));
        assertNull(service.findAlias("us-east-1", "alias/team/card-key"));
    }

    @Test
    void aKeyMayBeCreatedDisabledAndWithTags() {
        Map<String, Object> attributes = new LinkedHashMap<>();
        attributes.put("KeyAlgorithm", "AES_256");
        attributes.put("KeyClass", "SYMMETRIC_KEY");
        attributes.put("KeyUsage", "TR31_D0_SYMMETRIC_DATA_ENCRYPTION_KEY");

        PaymentCryptographyKey key = service.createKey("us-east-1", attributes, Map.of(), false,
                false, Map.of("owner", "team"));

        assertFalse(key.isEnabled(), "the key is enabled although it was created disabled");
        assertEquals("team", key.getTags().get("owner"));
    }

    @Test
    void aDeletedKeyDoesNotComeBackWhenItIsTagged() {
        PaymentCryptographyKey key = createKey("us-east-1");
        service.deleteKey(key);

        AwsException gone = assertThrows(AwsException.class,
                () -> service.tag(key, Map.of("owner", "team")));
        assertEquals("ResourceNotFoundException", gone.getErrorCode());
        assertNull(service.findKey("us-east-1", key.getKeyId()),
                "the key is in the store again after it was deleted");
    }

    @Test
    void theKeysAreListedAPageAtATime() {
        createKey("us-east-1");
        createKey("us-east-1");
        createKey("us-east-1");

        PaginatedResult<PaymentCryptographyKey> first = service.listKeys("us-east-1", null, 2, null);
        assertEquals(2, first.items().size());
        assertNotNull(first.nextToken(), "there are more keys, so there is a token");

        PaginatedResult<PaymentCryptographyKey> second =
                service.listKeys("us-east-1", null, 2, first.nextToken());
        assertEquals(1, second.items().size());
        assertNull(second.nextToken(), "the last page has no token");
    }

    /** The attributes of a key with that algorithm and usage. */
    private static Map<String, Object> attributes(String algorithm, String usage) {
        Map<String, Object> attributes = new LinkedHashMap<>();
        attributes.put("KeyAlgorithm", algorithm);
        attributes.put("KeyClass", "SYMMETRIC_KEY");
        attributes.put("KeyUsage", usage);
        return attributes;
    }

    private PaymentCryptographyKey createReplicatedKey(String region, List<String> regions) {
        Map<String, Object> attributes = new LinkedHashMap<>();
        attributes.put("KeyAlgorithm", "AES_256");
        attributes.put("KeyClass", "SYMMETRIC_KEY");
        attributes.put("KeyUsage", "TR31_D0_SYMMETRIC_DATA_ENCRYPTION_KEY");
        Map<String, Boolean> modes = new LinkedHashMap<>();
        modes.put("Encrypt", Boolean.TRUE);
        modes.put("Decrypt", Boolean.TRUE);
        return service.createKey(region, attributes, modes, true, true, Map.of(), regions);
    }

    @Test
    void aKeyIsCreatedInEveryRegionItIsReplicatedTo() {
        PaymentCryptographyKey primary = createReplicatedKey("us-east-1", List.of("us-west-2"));

        assertEquals("us-east-1", primary.getPrimaryRegion());
        assertEquals("PRIMARY", primary.getMultiRegionKeyType());
        assertEquals(List.of("us-west-2"), primary.getReplicationRegions());

        PaymentCryptographyKey replica = service.findKey("us-west-2", primary.getKeyId());

        assertNotNull(replica, "the key is not in the region it is replicated to");
        assertEquals(primary.getKeyId(), replica.getKeyId());
        assertEquals("REPLICA", replica.getMultiRegionKeyType());
        assertEquals("us-east-1", replica.getPrimaryRegion());
        assertTrue(replica.getArn().contains(":us-west-2:"), replica.getArn());
        assertEquals(primary.getKeyMaterialHex(), replica.getKeyMaterialHex());
    }

    @Test
    void aKeyThatIsReplicatedHasToBeExportable() {
        Map<String, Object> attributes = new LinkedHashMap<>();
        attributes.put("KeyAlgorithm", "AES_256");
        attributes.put("KeyClass", "SYMMETRIC_KEY");
        attributes.put("KeyUsage", "TR31_D0_SYMMETRIC_DATA_ENCRYPTION_KEY");

        AwsException refused = assertThrows(AwsException.class, () -> service.createKey("us-east-1",
                attributes, Map.of(), false, true, Map.of(), List.of("us-west-2")));

        assertEquals("ValidationException", refused.getErrorCode());
    }

    @Test
    void aRegionTheServiceDoesNotServeIsRefused() {
        AwsException refused = assertThrows(AwsException.class,
                () -> createReplicatedKey("us-east-1", List.of("moon-west-1")));

        assertEquals("ValidationException", refused.getErrorCode());
    }

    @Test
    void theAccountHasDefaultReplicationRegions() {
        assertEquals(List.of(), service.defaultReplicationRegions());

        assertEquals(List.of("us-west-2"), service.enableDefaultReplicationRegions(List.of("us-west-2")));
        assertEquals(List.of("us-west-2"), service.defaultReplicationRegions());

        // A key that is created with the default on is in the default region as well.
        PaymentCryptographyKey key = createReplicatedKey("us-east-1", List.of());
        assertEquals(List.of("us-west-2"), key.getReplicationRegions());
        assertTrue(key.isUsingDefaultReplicationRegions());
        assertNotNull(service.findKey("us-west-2", key.getKeyId()));

        // Turning it off leaves the other regions alone.
        assertEquals(List.of("us-west-2", "us-east-2"),
                service.enableDefaultReplicationRegions(List.of("us-east-2")));
        assertEquals(List.of("us-west-2"),
                service.disableDefaultReplicationRegions(List.of("us-east-2")));
        assertEquals(List.of(), service.disableDefaultReplicationRegions(List.of("us-west-2")));
    }

    @Test
    void theReplicationRegionsOfAKeyCanBeChanged() {
        PaymentCryptographyKey key = createReplicatedKey("us-east-1", List.of("us-west-2"));

        PaymentCryptographyKey added = service.addKeyReplicationRegions("us-east-1", key.getKeyId(),
                List.of("us-west-1"));
        assertEquals(List.of("us-west-2", "us-west-1"), added.getReplicationRegions());
        assertNotNull(service.findKey("us-west-1", key.getKeyId()));

        PaymentCryptographyKey removed = service.removeKeyReplicationRegions("us-east-1", key.getKeyId(),
                List.of("us-west-2"));
        assertEquals(List.of("us-west-1"), removed.getReplicationRegions());
        assertNull(service.findKey("us-west-2", key.getKeyId()), "the key is in the region it left");
    }

    @Test
    void aReplicaIsReadOnly() {
        PaymentCryptographyKey key = createReplicatedKey("us-east-1", List.of("us-west-2"));
        PaymentCryptographyKey replica = service.findKey("us-west-2", key.getKeyId());

        AwsException refused = assertThrows(AwsException.class, () -> service.deleteKey(replica));
        assertEquals("ValidationException", refused.getErrorCode());

        AwsException notPrimary = assertThrows(AwsException.class,
                () -> service.addKeyReplicationRegions("us-west-2", key.getKeyId(), List.of("us-west-1")));
        assertEquals("ValidationException", notPrimary.getErrorCode());
    }

    @Test
    void theTagsOfAReplicaAreItsOwn() {
        PaymentCryptographyKey key = createReplicatedKey("us-east-1", List.of("us-west-2"));
        service.tag(key, Map.of("owner", "team"));

        PaymentCryptographyKey replica = service.findKey("us-west-2", key.getKeyId());

        assertEquals(Map.of(), replica.getTags());
        assertEquals("team", service.findKey("us-east-1", key.getKeyId()).getTags().get("owner"));
    }

    @Test
    void aKeyLeavesTheServiceAndComesBack() {
        PaymentCryptographyKey protection = createKey("us-east-1");
        PaymentCryptographyKey key = createKey("us-east-1");

        PaymentCryptographyService.KeyExport export = service.exportKey("us-east-1", key.getKeyId(),
                protection.getKeyId());

        assertEquals(protection.getArn(), export.wrappingKeyArn());
        assertEquals(key.getKeyCheckValue(), export.keyCheckValue());
        assertTrue(export.block().length() > 32, "the block is only its header");

        PaymentCryptographyKey imported = service.importKey("us-east-1", protection.getKeyId(),
                export.block());

        assertEquals(key.getKeyMaterialHex(), imported.getKeyMaterialHex());
        assertEquals(key.getKeyCheckValue(), imported.getKeyCheckValue());
        assertEquals(key.getKeyUsage(), imported.getKeyUsage());
        assertTrue(imported.isExportable(), "the block said the key is exportable");
    }

    @Test
    void aKeyLeavesUnderATdesProtectionKeyAsATypeBBlock() {
        // The block type follows the protection key's family: a TDES key gives a type B block,
        // an AES one a type D block. That is the rule the emulator follows.
        PaymentCryptographyKey protection = service.createKey("us-east-1",
                attributes("TDES_2KEY", "TR31_K1_KEY_BLOCK_PROTECTION_KEY"),
                Map.of("Wrap", Boolean.TRUE, "Unwrap", Boolean.TRUE), true, true, Map.of());
        PaymentCryptographyKey key = service.createKey("us-east-1",
                attributes("TDES_2KEY", "TR31_D0_SYMMETRIC_DATA_ENCRYPTION_KEY"),
                Map.of("Encrypt", Boolean.TRUE, "Decrypt", Boolean.TRUE), true, true, Map.of());

        PaymentCryptographyService.KeyExport export = service.exportKey("us-east-1", key.getKeyId(),
                protection.getKeyId());

        assertTrue(export.block().startsWith("B"), "a TDES protection key gives a type B block");
        assertEquals("T", export.block().substring(7, 8), "the block carries a TDES key");

        PaymentCryptographyKey imported = service.importKey("us-east-1", protection.getKeyId(),
                export.block());

        assertEquals(key.getKeyMaterialHex(), imported.getKeyMaterialHex());
        assertEquals(key.getKeyCheckValue(), imported.getKeyCheckValue());
    }

    @Test
    void aKeyLeavesUnderAnAesProtectionKeyAsATypeDBlock() {
        PaymentCryptographyKey protection = service.createKey("us-east-1",
                attributes("AES_256", "TR31_K1_KEY_BLOCK_PROTECTION_KEY"),
                Map.of("Wrap", Boolean.TRUE, "Unwrap", Boolean.TRUE), true, true, Map.of());
        PaymentCryptographyKey key = service.createKey("us-east-1",
                attributes("AES_256", "TR31_D0_SYMMETRIC_DATA_ENCRYPTION_KEY"),
                Map.of("Encrypt", Boolean.TRUE, "Decrypt", Boolean.TRUE), true, true, Map.of());

        PaymentCryptographyService.KeyExport export = service.exportKey("us-east-1", key.getKeyId(),
                protection.getKeyId());

        assertTrue(export.block().startsWith("D"), "an AES protection key gives a type D block");
        assertEquals("A", export.block().substring(7, 8), "the block carries an AES key");
    }

    @Test
    void aKeyThatIsNotExportableDoesNotLeaveTheService() {
        PaymentCryptographyKey protection = createKey("us-east-1");
        Map<String, Object> attributes = new LinkedHashMap<>();
        attributes.put("KeyAlgorithm", "AES_256");
        attributes.put("KeyClass", "SYMMETRIC_KEY");
        attributes.put("KeyUsage", "TR31_D0_SYMMETRIC_DATA_ENCRYPTION_KEY");
        PaymentCryptographyKey key = service.createKey("us-east-1", attributes, Map.of(), false, true, Map.of());

        AwsException refused = assertThrows(AwsException.class,
                () -> service.exportKey("us-east-1", key.getKeyId(), protection.getKeyId()));

        assertEquals("ValidationException", refused.getErrorCode());
    }

    @Test
    void aBlockThatIsNotTheKeysDoesNotOpen() {
        PaymentCryptographyKey protection = createKey("us-east-1");
        PaymentCryptographyKey other = createKey("us-east-1");
        PaymentCryptographyKey key = createKey("us-east-1");
        PaymentCryptographyService.KeyExport export = service.exportKey("us-east-1", key.getKeyId(),
                protection.getKeyId());

        AwsException refused = assertThrows(AwsException.class,
                () -> service.importKey("us-east-1", other.getKeyId(), export.block()));

        assertEquals("ValidationException", refused.getErrorCode());
    }

    @Test
    void aKeyThatAsksForMaterialTheServiceDoesNotMakeIsRefused() {
        Map<String, Object> attributes = new LinkedHashMap<>();
        attributes.put("KeyAlgorithm", "RSA_2048");
        attributes.put("KeyClass", "SYMMETRIC_KEY");
        attributes.put("KeyUsage", "TR31_D0_SYMMETRIC_DATA_ENCRYPTION_KEY");

        AwsException bad = assertThrows(AwsException.class,
                () -> service.createKey("us-east-1", attributes, Map.of(), true, true, Map.of()));
        assertEquals("ValidationException", bad.getErrorCode());
    }

    @Test
    void aKeyHoldsTheMaterialItsAlgorithmCallsFor() {
        Map<String, Object> attributes = new LinkedHashMap<>();
        attributes.put("KeyClass", "SYMMETRIC_KEY");
        attributes.put("KeyUsage", "TR31_D0_SYMMETRIC_DATA_ENCRYPTION_KEY");

        attributes.put("KeyAlgorithm", "TDES_2KEY");
        PaymentCryptographyKey tdes = service.createKey("us-east-1", attributes, Map.of(), true, true, Map.of());
        assertEquals(32, tdes.getKeyMaterialHex().length(), "a TDES key is 16 bytes");

        attributes.put("KeyAlgorithm", "AES_256");
        PaymentCryptographyKey aes = service.createKey("us-east-1", attributes, Map.of(), true, true, Map.of());
        assertEquals(64, aes.getKeyMaterialHex().length(), "an AES-256 key is 32 bytes");
    }

    @Test
    void theCheckValueFollowsTheAlgorithmOfTheKey() {
        Map<String, Object> attributes = new LinkedHashMap<>();
        attributes.put("KeyClass", "SYMMETRIC_KEY");
        attributes.put("KeyUsage", "TR31_D0_SYMMETRIC_DATA_ENCRYPTION_KEY");

        // The check value of a TDES key is the ANSI X9.24 one, and of an AES key the CMAC.
        attributes.put("KeyAlgorithm", "TDES_2KEY");
        PaymentCryptographyKey tdes = service.createKey("us-east-1", attributes, Map.of(), true, true, Map.of());
        String expected = PaymentCryptographyCrypto.checkValue(tdes.getKeyMaterialHex(),
                PaymentCryptographyKeyAlgorithm.TDES_2KEY);
        assertEquals(expected, tdes.getKeyCheckValue());

        attributes.put("KeyAlgorithm", "AES_256");
        PaymentCryptographyKey aes = service.createKey("us-east-1", attributes, Map.of(), true, true, Map.of());
        assertEquals(PaymentCryptographyCrypto.checkValue(aes.getKeyMaterialHex(),
                PaymentCryptographyKeyAlgorithm.AES_256), aes.getKeyCheckValue());
    }

    @Test
    void aPageSizeBelowOneIsRefused() {
        createKey("us-east-1");

        AwsException bad = assertThrows(AwsException.class,
                () -> service.listKeys("us-east-1", null, 0, null));
        assertEquals("ValidationException", bad.getErrorCode());
    }

    @Test
    void aTokenThatNamesNoPageIsRefused() {
        createKey("us-east-1");

        AwsException bad = assertThrows(AwsException.class,
                () -> service.listKeys("us-east-1", null, 2, "not a token"));
        assertEquals("ValidationException", bad.getErrorCode());
    }

    /**
     * A key's usage says what the key is for, and the service requires it, as
     * the reference does.
     */
    @Test
    void aKeyWithoutItsUsageIsRefused() {
        Map<String, Object> attributes = new LinkedHashMap<>();
        attributes.put("KeyAlgorithm", "AES_256");
        attributes.put("KeyClass", "SYMMETRIC_KEY");

        AwsException bad = assertThrows(AwsException.class,
                () -> service.createKey("us-east-1", attributes, Map.of(), true, true, Map.of()));
        assertEquals("ValidationException", bad.getErrorCode());
    }

    /** A request naming a state is answered with the keys in that state only. */
    @Test
    void theKeysAreListedByTheirState() {
        createKey("us-east-1");
        createKey("us-east-1");

        assertEquals(2, service.listKeys("us-east-1", "CREATE_COMPLETE").size());
        assertEquals(0, service.listKeys("us-east-1", "DELETE_PENDING").size());
        assertEquals(0, service.listKeys("us-east-1", "CREATE_IN_PROGRESS").size());
    }

    /**
     * A deleted key is answered with the key, its state DELETE_PENDING and the
     * moment it is deleted.
     */
    @Test
    void aDeletedKeyIsAnsweredWithTheKeyItWas() {
        PaymentCryptographyKey key = createKey("us-east-1");

        PaymentCryptographyKey deleted = service.deleteKey(key);

        assertEquals(key.getArn(), deleted.getArn());
        assertEquals("DELETE_PENDING", deleted.getKeyState());
        assertFalse(deleted.isEnabled());
        assertTrue(deleted.getDeletePendingTimestamp() > 0);
        assertEquals(0, deleted.getDeleteTimestamp(),
                "a key that is pending deletion carries no deletion timestamp");
    }

    /**
     * An update naming no key removes the key the alias names, and the alias
     * stays.
     */
    @Test
    void anAliasIsDetachedFromItsKey() {
        PaymentCryptographyKey key = createKey("us-east-1");
        service.createAlias("alias/team/card-key", key);

        PaymentCryptographyAlias detached = service.detachAlias("us-east-1", "alias/team/card-key");

        assertEquals("alias/team/card-key", detached.getAliasName());
        assertNull(detached.getKeyArn());
        assertNull(service.findKey("us-east-1", "alias/team/card-key"),
                "a detached alias still names a key");
        assertNotNull(service.findAlias("us-east-1", "alias/team/card-key"));
    }

    /** The namespace of the resource types the service finds, as the request-resources table names it. */
    @Test
    void theNamespaceIsTheOneTheTableNames() {
        assertEquals("payment-cryptography", service.namespace());
    }

    /**
     * A key is named by its id or by an alias, and an alias names the key it points at, so a request naming a key by
     * an alias is checked on the key the alias names, as the handler resolves it.
     */
    @Test
    void aKeyIsFoundUnderItsIdAndUnderTheAliasThatNamesIt() {
        PaymentCryptographyKey key = createKey("us-east-1");
        service.createAlias("alias/team/card-key", key);

        assertEquals(Optional.of(key.getArn()), service.find("key", List.of(key.getKeyId()), "us-east-1"));
        assertEquals(Optional.of(key.getArn()), service.find("key", List.of("alias/team/card-key"), "us-east-1"));
        assertEquals(Optional.of(key.getArn()), service.find("key", List.of(key.getArn()), "us-east-1"));
        assertEquals(Optional.empty(), service.find("key", List.of("alias/team/no-such-key"), "us-east-1"));
        assertEquals(Optional.empty(), service.find("key", List.of("no-such-key"), "us-east-1"));
    }

    /**
     * An alias ARN names the alias in the Region, the account and the partition it was minted in, so an alias ARN
     * of another Region names no alias here and the key of this Region's alias is not the one it would select.
     */
    @Test
    void anAliasArnOfAnotherRegionNamesNoAliasHere() {
        PaymentCryptographyKey key = createKey("us-east-1");
        service.createAlias("alias/team/card-key", key);
        String aliasArn = "arn:aws:payment-cryptography:us-east-1:000000000000:alias/team/card-key";

        assertEquals(key, service.findKey("us-east-1", aliasArn));
        assertNull(service.findKey("us-east-1",
                        "arn:aws:payment-cryptography:us-west-2:000000000000:alias/team/card-key"),
                "an alias ARN of another Region selected this Region's key");
    }

    /**
     * An alias is resolved once for the request, so that the request acts on the key the permission check was
     * made on: an alias moved to another key in between would otherwise let the request act on a key no policy
     * was checked against.
     */
    @Test
    void anAliasIsResolvedOnceForTheRequest() {
        PaymentCryptographyKey allowed = createKey("us-east-1");
        PaymentCryptographyKey denied = createKey("us-east-1");
        service.createAlias("alias/team/card-key", allowed);

        // The permission check resolves the alias, which is the key the request is checked on.
        assertEquals(Optional.of(allowed.getArn()), service.find("key", List.of("alias/team/card-key"), "us-east-1"));

        // The alias is moved to another key while the request runs.
        service.updateAlias("alias/team/card-key", denied);

        assertEquals(allowed, service.findKey("us-east-1", "alias/team/card-key"),
                "the request did not act on the key the permission check was made on");
    }

    /**
     * An alias's ARN is the name it was given, which carries the {@code alias/} its own resource part carries too,
     * so it is that name without the prefix.
     */
    @Test
    void anAliasIsFoundUnderItsName() {
        assertEquals(Optional.of("arn:aws:payment-cryptography:us-east-1:000000000000:alias/team/card-key"),
                service.find("alias", List.of("alias/team/card-key"), "us-east-1"));
        assertEquals(Optional.empty(), service.find("approval-team", List.of("whatever"), "us-east-1"));
    }
}
