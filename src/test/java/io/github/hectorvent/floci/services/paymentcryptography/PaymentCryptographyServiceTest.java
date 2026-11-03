package io.github.hectorvent.floci.services.paymentcryptography;

import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.core.common.PaginatedResult;
import io.github.hectorvent.floci.core.common.RegionResolver;
import io.github.hectorvent.floci.core.storage.InMemoryStorage;
import io.github.hectorvent.floci.services.paymentcryptography.model.PaymentCryptographyAlias;
import io.github.hectorvent.floci.services.paymentcryptography.model.PaymentCryptographyKey;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import javax.crypto.Cipher;
import javax.crypto.spec.SecretKeySpec;

import java.security.GeneralSecurityException;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
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

    /** A key of the given algorithm and class, for the tests that need one. */
    private PaymentCryptographyKey createKey(String region, String algorithm, String keyClass,
                                             String usage, Map<String, Boolean> modes) {
        Map<String, Object> attributes = new LinkedHashMap<>();
        attributes.put("KeyAlgorithm", algorithm);
        attributes.put("KeyClass", keyClass);
        attributes.put("KeyUsage", usage);
        return service.createKey(region, attributes, modes, true, true, Map.of());
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
    void aTdesKeyCarriesTheAnsiCheckValueOfItsAlgorithm() {
        // The ANSI X9.24 check value is the leftmost three bytes of the encrypted zeroes
        // under the key, so it is the same for the two algorithms of different length only
        // when the keys are the same.
        PaymentCryptographyKey two = createKey("us-east-1", "TDES_2KEY", "SYMMETRIC_KEY",
                "TR31_D0_SYMMETRIC_DATA_ENCRYPTION_KEY", Map.of("Encrypt", Boolean.TRUE,
                        "Decrypt", Boolean.TRUE));
        assertEquals("ANSI_X9_24", two.getKeyCheckValueAlgorithm());
        assertEquals(32, two.getKeyMaterialHex().length());

        PaymentCryptographyKey three = createKey("us-east-1", "TDES_3KEY", "SYMMETRIC_KEY",
                "TR31_D0_SYMMETRIC_DATA_ENCRYPTION_KEY", Map.of("Encrypt", Boolean.TRUE,
                        "Decrypt", Boolean.TRUE));
        assertEquals("ANSI_X9_24", three.getKeyCheckValueAlgorithm());
        assertEquals(48, three.getKeyMaterialHex().length());
    }

    @Test
    void theCheckValueOfATdesKeyIsTheOneTheStandardGives() {
        // The key of the vector, so the check value is the one of the standard.
        Map<String, Object> attributes = new LinkedHashMap<>();
        attributes.put("KeyAlgorithm", "TDES_2KEY");
        attributes.put("KeyClass", "SYMMETRIC_KEY");
        attributes.put("KeyUsage", "TR31_D0_SYMMETRIC_DATA_ENCRYPTION_KEY");
        PaymentCryptographyKey key = service.createKey("us-east-1", attributes,
                Map.of("Encrypt", Boolean.TRUE, "Decrypt", Boolean.TRUE), true, true, Map.of());

        // The check value is the one the standard gives for the material the key carries.
        assertEquals("ANSI_X9_24", key.getKeyCheckValueAlgorithm());
        assertEquals(6, key.getKeyCheckValue().length());
        assertEquals(checkValueOf(key.getKeyMaterialHex()), key.getKeyCheckValue());
        // And the standard gives this value for the material of the vector.
        assertEquals("08D7B4", checkValueOf("0123456789ABCDEFFEDCBA9876543210"));
    }

    @Test
    void anEllipticCurveKeyIsAKeyPair() {
        PaymentCryptographyKey key = createKey("us-east-1", "ECC_NIST_P256", "ASYMMETRIC_KEY_PAIR",
                "TR31_S0_ASYMMETRIC_KEY_FOR_DIGITAL_SIGNATURE", Map.of("Sign", Boolean.TRUE,
                        "Verify", Boolean.TRUE));

        assertEquals("ASYMMETRIC_KEY_PAIR", key.getKeyClass());
        assertNotNull(key.getKeyPublicKeyBase64());
        assertNotNull(key.getKeyPrivateKeyBase64());
        // An asymmetric key has no material a check value could be computed from, so it
        // reports the fixed placeholder.
        assertEquals("000000", key.getKeyCheckValue());
        assertEquals("ANSI_X9_24", key.getKeyCheckValueAlgorithm());
    }

    @Test
    void aUsageThatTheAlgorithmCannotServeIsRefused() {
        // A key pair has no material a symmetric usage would bind it to, and a symmetric
        // key has no pair a key pair's usage would bind it to.
        assertValidation(() -> createKey("us-east-1", "RSA_2048", "ASYMMETRIC_KEY_PAIR",
                "TR31_M1_ISO_9797_1_MAC_KEY", Map.of("Generate", Boolean.TRUE)),
                "may not be made of");
        assertValidation(() -> createKey("us-east-1", "AES_256", "SYMMETRIC_KEY",
                "TR31_S0_ASYMMETRIC_KEY_FOR_DIGITAL_SIGNATURE", Map.of("Sign", Boolean.TRUE)),
                "may not be made of");
    }

    @Test
    void aKeyPairCarriesItsOwnHalves() {
        // An RSA key is made of a pair, and the halves are kept with it.
        PaymentCryptographyKey rsa = createKey("us-east-1", "RSA_2048", "ASYMMETRIC_KEY_PAIR",
                "TR31_S0_ASYMMETRIC_KEY_FOR_DIGITAL_SIGNATURE",
                Map.of("Sign", Boolean.TRUE, "Verify", Boolean.TRUE));
        assertNotNull(rsa.getKeyPublicKeyBase64());
        assertNotNull(rsa.getKeyPrivateKeyBase64());
        assertTrue(rsa.getKeyPublicKeyBase64().length() > 256,
                "the public half of an RSA-2048 key is a long DER encoding");

        // And an EC one is made of a pair on the curve the algorithm names.
        PaymentCryptographyKey ec = createKey("us-east-1", "ECC_NIST_P256", "ASYMMETRIC_KEY_PAIR",
                "TR31_K3_ASYMMETRIC_KEY_FOR_KEY_AGREEMENT",
                Map.of("DeriveKey", Boolean.TRUE));
        assertNotNull(ec.getKeyPublicKeyBase64());
        assertNotNull(ec.getKeyPrivateKeyBase64());
        assertNotEquals(rsa.getKeyPublicKeyBase64(), ec.getKeyPublicKeyBase64(),
                "the two pairs are different");
    }

    @Test
    void everyAsymmetricUsageTakesAKeyPair() {
        // The usages the model names for a key pair, each of which is made.
        for (String usage : List.of("TR31_D1_ASYMMETRIC_KEY_FOR_DATA_ENCRYPTION",
                "TR31_K2_TR34_ASYMMETRIC_KEY",
                "TR31_K3_ASYMMETRIC_KEY_FOR_KEY_AGREEMENT",
                "TR31_S0_ASYMMETRIC_KEY_FOR_DIGITAL_SIGNATURE")) {
            PaymentCryptographyKey key = createKey("us-east-1", "RSA_2048", "ASYMMETRIC_KEY_PAIR",
                    usage, Map.of("Sign", Boolean.TRUE, "Verify", Boolean.TRUE));
            assertEquals("RSA_2048", key.getKeyAlgorithm());
            assertEquals(usage, key.getKeyUsage());
        }
        // And the key agreement one takes a curve as well.
        PaymentCryptographyKey agreed = createKey("us-east-1", "ECC_NIST_P256",
                "ASYMMETRIC_KEY_PAIR", "TR31_K3_ASYMMETRIC_KEY_FOR_KEY_AGREEMENT",
                Map.of("DeriveKey", Boolean.TRUE));
        assertEquals("ECC_NIST_P256", agreed.getKeyAlgorithm());
    }

    @Test
    void aClassThatContradictsTheAlgorithmIsRefused() {
        assertValidation(() -> createKey("us-east-1", "AES_256", "ASYMMETRIC_KEY_PAIR",
                "TR31_S0_ASYMMETRIC_KEY_FOR_DIGITAL_SIGNATURE", Map.of("Verify", Boolean.TRUE)),
                "a symmetric algorithm");
        assertValidation(() -> createKey("us-east-1", "RSA_2048", "SYMMETRIC_KEY",
                "TR31_D0_SYMMETRIC_DATA_ENCRYPTION_KEY", Map.of("Encrypt", Boolean.TRUE)),
                "an asymmetric algorithm");
    }

    /** The ANSI X9.24 check value of a key's material, computed the way the standard says. */
    private static String checkValueOf(String materialHex) {
        byte[] material = HexFormat.of().parseHex(materialHex);
        byte[] wide = new byte[24];
        System.arraycopy(material, 0, wide, 0, 16);
        System.arraycopy(material, 0, wide, 16, 8);
        try {
            Cipher cipher = Cipher.getInstance("DESede/ECB/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(wide, "DESede"));
            return HexFormat.of().withUpperCase().formatHex(cipher.doFinal(new byte[8]))
                    .substring(0, 6);
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("the Triple DES key could not be used", e);
        }
    }

    private void assertValidation(Runnable refused, String message) {
        AwsException failure = assertThrows(AwsException.class, refused::run);
        assertEquals("ValidationException", failure.getErrorCode());
        assertTrue(failure.getMessage().contains(message), failure.getMessage());
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
    void aKeySavedBeforeTheRegionWasPartOfItsNameIsStillFound() {
        // A store written before the region became part of the name holds its keys under
        // their identifier alone, so an alias written then keeps working.
        InMemoryStorage<String, PaymentCryptographyKey> store = new InMemoryStorage<>();
        PaymentCryptographyService old = new PaymentCryptographyService(store,
                new InMemoryStorage<>(), new RegionResolver("us-east-1", "000000000000"),
                new PaymentCryptographyRequestContext());
        PaymentCryptographyKey key = createKey("us-east-1");
        // The key under the name it was saved under before the region was part of it.
        store.put(key.getKeyId(), key);
        old.createAlias("alias/team/old-key", key);

        PaymentCryptographyKey found = old.findKey("us-east-1", "alias/team/old-key");
        assertNotNull(found, "the alias names the key that was saved under the old name");
        assertEquals(key.getKeyId(), found.getKeyId());
    }

    @Test
    void aDeletedKeyIsGoneUnderTheNameItWasSavedUnder() {
        InMemoryStorage<String, PaymentCryptographyKey> store = new InMemoryStorage<>();
        PaymentCryptographyService old = new PaymentCryptographyService(store,
                new InMemoryStorage<>(), new RegionResolver("us-east-1", "000000000000"),
                new PaymentCryptographyRequestContext());
        PaymentCryptographyKey key = createKey("us-east-1");
        store.put(key.getKeyId(), key);

        old.deleteKey(key);

        assertNull(store.get(key.getKeyId()).orElse(null),
                "the key is gone under the name it was saved under too");
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

    @Test
    void aKeyThatAsksForMaterialTheServiceDoesNotMakeIsRefused() {
        Map<String, Object> attributes = new LinkedHashMap<>();
        // The service makes the AES sizes, so the algorithm it does not make is one it has
        // no material for.
        attributes.put("KeyAlgorithm", "DES");
        attributes.put("KeyClass", "SYMMETRIC_KEY");
        attributes.put("KeyUsage", "TR31_D0_SYMMETRIC_DATA_ENCRYPTION_KEY");

        AwsException bad = assertThrows(AwsException.class,
                () -> service.createKey("us-east-1", attributes, Map.of(), true, true, Map.of()));
        assertEquals("ValidationException", bad.getErrorCode());
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
