package io.floci.aws.services.paymentcryptography;

import io.floci.aws.core.common.AwsException;
import io.floci.aws.core.common.PaginatedResult;
import io.floci.aws.core.common.RegionResolver;
import io.floci.aws.core.storage.InMemoryStorage;
import io.floci.aws.services.paymentcryptography.model.PaymentCryptographyAlias;
import io.floci.aws.services.paymentcryptography.model.PaymentCryptographyKey;
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
    /** The store the keys live in, for the tests that write a record themselves. */
    private InMemoryStorage<String, PaymentCryptographyKey> store;

    @BeforeEach
    void setUp() {
        store = new InMemoryStorage<>();
        service = new PaymentCryptographyService(
                store,
                new InMemoryStorage<>(),
                new RegionResolver("us-east-1", "000000000000"),
                new PaymentCryptographyRequestContext());
    }

    /**
     * IAM's tag lookup reads the tags held on the key the whole ARN names, without the service's lock: tagging puts a
     * new map in place of the held one, so a set read before a change stays whole; nothing for another Region's ARN.
     */
    @Test
    void storedTagsAreTheTagsOfTheKeyTheArnNamesAndTaggingPutsANewMap() {
        PaymentCryptographyKey key = createKey("us-east-1");
        service.tag(key, Map.of("team", "data"));
        Map<String, String> held = service.storedTags(key.getArn());

        service.tag(key, Map.of("env", "prod"));
        service.untag(key, List.of("team"));

        assertEquals(Map.of("team", "data"), held);
        assertEquals(Map.of("env", "prod"), service.storedTags(key.getArn()));
        assertEquals(Map.of(), service.storedTags(key.getArn().replace(":us-east-1:", ":eu-west-1:")));
        assertEquals(Map.of(), service.storedTags("arn:aws:s3:::bucket"));
    }

    /** A key of the given algorithm and class, for the tests that need one. */
    private PaymentCryptographyKey createKey(String region, String algorithm, String keyClass,
                                             String usage, Map<String, Boolean> modes) {
        return service.createKey(region, attributes(algorithm, keyClass, usage), modes, true, true,
                Map.of());
    }

    private PaymentCryptographyKey createKey(String region) {
        return service.createKey(region, dataKeyAttributes(), dataKeyModes(), true, true, Map.of());
    }

    /**
     * The key a key block is wrapped under.
     *
     * <p>A key block is wrapped under a key encryption key, and not under a data encryption
     * key: the usages keep the two apart.
     */
    private PaymentCryptographyKey createWrappingKey(String region) {
        Map<String, Boolean> modes = new LinkedHashMap<>();
        modes.put("Wrap", Boolean.TRUE);
        modes.put("Unwrap", Boolean.TRUE);
        return service.createKey(region,
                attributes("AES_256", "TR31_K1_KEY_BLOCK_PROTECTION_KEY"), modes, true, true, Map.of());
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
    void aKeyPairCarriesTheUsageItsDerivedKeysHave() {
        // The usage a derived key is for is named when the key pair is made, as the export
        // guide's key agreement flow does.
        Map<String, Object> attributes = new LinkedHashMap<>();
        attributes.put("KeyAlgorithm", "ECC_NIST_P256");
        attributes.put("KeyClass", "ASYMMETRIC_KEY_PAIR");
        attributes.put("KeyUsage", "TR31_K3_ASYMMETRIC_KEY_FOR_KEY_AGREEMENT");
        attributes.put("DeriveKeyUsage", "TR31_K1_KEY_BLOCK_PROTECTION_KEY");

        PaymentCryptographyKey key = service.createKey("us-east-1", attributes,
                Map.of("DeriveKey", Boolean.TRUE), true, true, Map.of());

        assertEquals("TR31_K1_KEY_BLOCK_PROTECTION_KEY", key.getDeriveKeyUsage());
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
        PaymentCryptographyService old = serviceOverLegacyStore(store);
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
        PaymentCryptographyService old = serviceOverLegacyStore(store);
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
        Map<String, Object> attributes = dataKeyAttributes();

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

    /** The policy that grants different permissions to different accounts. */
    private static final String TWO_ACCOUNTS_POLICY = """
            {"Version":"2012-10-17","Statement":[
             {"Sid":"Allow3DSACSToGenerate","Effect":"Allow",
              "Principal":{"AWS":"arn:aws:iam::111122223333:role/3dsAcsRole"},
              "Action":["payment-cryptography:GenerateCardValidationData"],"Resource":"*"},
             {"Sid":"AllowPaymentAuthToVerify","Effect":"Allow",
              "Principal":{"AWS":"arn:aws:iam::444455556666:role/PaymentAuthRole"},
              "Action":["payment-cryptography:VerifyAuthRequestCryptogram"],"Resource":"*"}]}""";

    @Test
    void aPolicyIsNotAttachedToAReplica() {
        // The reference attaches a policy to the primary key alone, so a replica takes none.
        PaymentCryptographyKey primary = createReplicatedKey(service, "us-east-1", List.of("us-west-2"));
        PaymentCryptographyKey replica = service.findKey("us-west-2", primary.getKeyId());

        AwsException refused = assertThrows(AwsException.class,
                () -> service.putResourcePolicy(replica, PaymentCryptographyTestFixtures.CROSS_ACCOUNT_POLICY));
        assertEquals("ValidationException", refused.getErrorCode());
    }

    @Test
    void thePolicyOfTheReferenceIsAttachedAndReadBack() {
        // The reference's own page shows these two policies, and the service keeps the
        // document as it was given.
        PaymentCryptographyKey key = createKey("us-east-1");
        String policy = PaymentCryptographyTestFixtures.CROSS_ACCOUNT_POLICY;

        assertEquals(policy, service.putResourcePolicy(key, policy).policy());
        assertEquals(policy, service.resourcePolicy(key).policy());
        assertEquals(TWO_ACCOUNTS_POLICY,
                service.putResourcePolicy(key, TWO_ACCOUNTS_POLICY).policy());
    }

    @Test
    void aPolicyThatNamesTheKeyItIsOnIsAttached() {
        // The resource may be the key's own ARN as well as every resource.
        PaymentCryptographyKey key = createKey("us-east-1");
        String policy = "{\"Version\":\"2012-10-17\",\"Statement\":[{\"Effect\":\"Allow\","
                + "\"Principal\":{\"AWS\":\"arn:aws:iam::111122223333:role/ExampleRole\"},"
                + "\"Action\":[\"payment-cryptography:GenerateCardValidationData\"],"
                + "\"Resource\":\"" + key.getArn() + "\"}]}";

        assertEquals(policy, service.putResourcePolicy(key, policy).policy());
    }

    @Test
    void aResourcePolicyIsPutReadAndDeleted() {
        PaymentCryptographyKey key = createKey("us-east-1");
        String policy = PaymentCryptographyTestFixtures.CROSS_ACCOUNT_POLICY;

        assertNull(key.getResourcePolicy(), "a key has a policy before one was put on it");

        assertEquals(policy, service.putResourcePolicy(key, policy).policy());
        assertEquals(policy, service.resourcePolicy(key).policy());

        service.deleteResourcePolicy(key);

        AwsException gone = assertThrows(AwsException.class,
                () -> service.resourcePolicy(key));
        assertEquals("ResourceNotFoundException", gone.getErrorCode());
    }

    @Test
    void aResourcePolicyIsReplacedByTheNextOne() {
        PaymentCryptographyKey key = createKey("us-east-1");

        service.putResourcePolicy(key, PaymentCryptographyTestFixtures.CROSS_ACCOUNT_POLICY);
        service.putResourcePolicy(key, TWO_ACCOUNTS_POLICY);

        assertEquals(TWO_ACCOUNTS_POLICY, service.resourcePolicy(key).policy());
    }

    @Test
    void aDeletedKeyHasNoResourcePolicy() {
        PaymentCryptographyKey key = createKey("us-east-1");
        service.putResourcePolicy(key, PaymentCryptographyTestFixtures.CROSS_ACCOUNT_POLICY);
        service.deleteKey(key);

        AwsException gone = assertThrows(AwsException.class,
                () -> service.resourcePolicy(key));
        assertEquals("ResourceNotFoundException", gone.getErrorCode());

        AwsException deleted = assertThrows(AwsException.class,
                () -> service.putResourcePolicy(key, PaymentCryptographyTestFixtures.CROSS_ACCOUNT_POLICY));
        assertEquals("ResourceNotFoundException", deleted.getErrorCode());
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

    @Test
    void aKeyLeavesTheServiceAndComesBack() {
        PaymentCryptographyKey protection = createWrappingKey("us-east-1");
        PaymentCryptographyKey key = createKey("us-east-1");

        Map<String, Object> answer = exportTr31Answer("us-east-1", key.getKeyId(),
                protection.getKeyId());
        Map<?, ?> wrapped = (Map<?, ?>) answer.get("WrappedKey");
        String export = (String) wrapped.get("KeyMaterial");

        assertEquals(protection.getArn(), wrapped.get("WrappingKeyArn"));
        assertEquals(key.getKeyCheckValue(), wrapped.get("KeyCheckValue"));
        assertTrue(export.length() > 32, "the block is only its header");

        PaymentCryptographyKey imported = importTr31("us-east-1", protection.getKeyId(), export);

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

        String export = exportTr31("us-east-1", key.getKeyId(), protection.getKeyId());

        assertTrue(export.startsWith("B"), "a TDES protection key gives a type B block");
        assertEquals("T", export.substring(7, 8), "the block carries a TDES key");

        PaymentCryptographyKey imported = importTr31("us-east-1", protection.getKeyId(), export);

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

        String export = exportTr31("us-east-1", key.getKeyId(), protection.getKeyId());

        assertTrue(export.startsWith("D"), "an AES protection key gives a type D block");
        assertEquals("A", export.substring(7, 8), "the block carries an AES key");
    }

    @Test
    void aKeyUnderAWeakerProtectionKeyIsRefused() {
        // The wrapping key has to be at least as strong as the key it protects, or the
        // protection buys nothing. The strengths are the ones the standards count, so a
        // two-key TDES key is 80 bits and an AES-128 one is 128.
        PaymentCryptographyKey protection = service.createKey("us-east-1",
                attributes("AES_128", "TR31_K1_KEY_BLOCK_PROTECTION_KEY"),
                Map.of("Wrap", Boolean.TRUE, "Unwrap", Boolean.TRUE), true, true, Map.of());
        PaymentCryptographyKey key = service.createKey("us-east-1",
                attributes("AES_256", "TR31_D0_SYMMETRIC_DATA_ENCRYPTION_KEY"),
                Map.of("Encrypt", Boolean.TRUE, "Decrypt", Boolean.TRUE), true, true, Map.of());

        AwsException refused = assertThrows(AwsException.class,
                () -> exportTr31("us-east-1", key.getKeyId(), protection.getKeyId()));

        assertEquals("ValidationException", refused.getErrorCode());
        assertTrue(refused.getMessage().contains("at least as strong"), refused.getMessage());
    }

    @Test
    void aKeyUnderAStrongerProtectionKeyIsAllowed() {
        PaymentCryptographyKey protection = service.createKey("us-east-1",
                attributes("AES_256", "TR31_K1_KEY_BLOCK_PROTECTION_KEY"),
                Map.of("Wrap", Boolean.TRUE, "Unwrap", Boolean.TRUE), true, true, Map.of());
        PaymentCryptographyKey key = service.createKey("us-east-1",
                attributes("AES_128", "TR31_D0_SYMMETRIC_DATA_ENCRYPTION_KEY"),
                Map.of("Encrypt", Boolean.TRUE, "Decrypt", Boolean.TRUE), true, true, Map.of());

        String export = exportTr31("us-east-1", key.getKeyId(), protection.getKeyId());

        assertTrue(export.startsWith("D"), "an AES protection key gives a type D block");
    }

    @Test
    void aKeyOfEveryUsageTheServiceMakesLeavesAsItsOwnUsage() {
        // A key block carries the usage of the key it carries, so every usage the service
        // makes has to have a code. A usage without one could not leave the service at all,
        // and the counterparty could not tell what it had.
        //
        // The asymmetric usage is not in the list: a key pair has no material a key block
        // could carry, so it leaves in a TR-34 block, which this service does not write.
        List<String[]> usages = List.of(
                new String[] {"TR31_B0_BASE_DERIVATION_KEY", "AES_256"},
                new String[] {"TR31_C0_CARD_VERIFICATION_KEY", "AES_256"},
                new String[] {"TR31_D0_SYMMETRIC_DATA_ENCRYPTION_KEY", "AES_256"},
                new String[] {"TR31_E0_EMV_MKEY_APP_CRYPTOGRAMS", "AES_256"},
                new String[] {"TR31_E1_EMV_MKEY_CONFIDENTIALITY", "AES_256"},
                new String[] {"TR31_E2_EMV_MKEY_INTEGRITY", "AES_256"},
                new String[] {"TR31_E4_EMV_MKEY_DYNAMIC_NUMBERS", "AES_256"},
                new String[] {"TR31_E5_EMV_MKEY_CARD_PERSONALIZATION", "AES_256"},
                new String[] {"TR31_E6_EMV_MKEY_OTHER", "AES_256"},
                new String[] {"TR31_K0_KEY_ENCRYPTION_KEY", "AES_256"},
                new String[] {"TR31_K1_KEY_BLOCK_PROTECTION_KEY", "AES_256"},
                // The ISO 9797 MACs are defined over Triple DES.
                new String[] {"TR31_M0_ISO_16609_MAC_KEY", "TDES_2KEY"},
                new String[] {"TR31_M1_ISO_9797_1_MAC_KEY", "TDES_2KEY"},
                new String[] {"TR31_M3_ISO_9797_3_MAC_KEY", "TDES_2KEY"},
                new String[] {"TR31_M6_ISO_9797_5_CMAC_KEY", "AES_256"},
                new String[] {"TR31_M7_HMAC_KEY", "AES_256"},
                new String[] {"TR31_P0_PIN_ENCRYPTION_KEY", "AES_256"},
                new String[] {"TR31_P1_PIN_GENERATION_KEY", "AES_256"},
                new String[] {"TR31_V1_IBM3624_PIN_VERIFICATION_KEY", "AES_256"},
                new String[] {"TR31_V2_VISA_PIN_VERIFICATION_KEY", "AES_256"});

        PaymentCryptographyKey protection = service.createKey("us-east-1",
                attributes("AES_256", "TR31_K1_KEY_BLOCK_PROTECTION_KEY"),
                Map.of("Wrap", Boolean.TRUE, "Unwrap", Boolean.TRUE), true, true, Map.of());

        for (String[] usage : usages) {
            PaymentCryptographyKey key = service.createKey("us-east-1",
                    attributes(usage[1], usage[0]),
                    Map.of("Encrypt", Boolean.TRUE, "Decrypt", Boolean.TRUE), true, true, Map.of());

            String export = exportTr31("us-east-1", key.getKeyId(), protection.getKeyId());

            PaymentCryptographyKey imported = importTr31("us-east-1", protection.getKeyId(), export);

            assertEquals(usage[0], imported.getKeyUsage(), usage[0] + " did not survive the round trip");
        }
    }

    @Test
    void anAsymmetricKeyDoesNotLeaveInAKeyBlock() {
        // The asymmetric usage is a key pair, and a key pair has no material a key block
        // could carry. It leaves in a TR-34 block, which this service does not write.
        PaymentCryptographyKey protection = service.createKey("us-east-1",
                attributes("AES_256", "TR31_K1_KEY_BLOCK_PROTECTION_KEY"),
                Map.of("Wrap", Boolean.TRUE, "Unwrap", Boolean.TRUE), true, true, Map.of());
        Map<String, Object> attributes = new LinkedHashMap<>();
        attributes.put("KeyAlgorithm", "RSA_2048");
        attributes.put("KeyClass", "ASYMMETRIC_KEY_PAIR");
        attributes.put("KeyUsage", "TR31_S0_ASYMMETRIC_KEY_FOR_DIGITAL_SIGNATURE");
        PaymentCryptographyKey key = service.createKey("us-east-1", attributes,
                Map.of("Sign", Boolean.TRUE, "Verify", Boolean.TRUE), true, true, Map.of());

        AwsException refused = assertThrows(AwsException.class,
                () -> exportTr31("us-east-1", key.getKeyId(), protection.getKeyId()));

        assertEquals("ValidationException", refused.getErrorCode());
    }

    @Test
    void aKeyThatIsNotExportableDoesNotLeaveTheService() {
        PaymentCryptographyKey protection = createKey("us-east-1");
        Map<String, Object> attributes = dataKeyAttributes();
        PaymentCryptographyKey key = service.createKey("us-east-1", attributes, Map.of(), false, true, Map.of());

        AwsException refused = assertThrows(AwsException.class,
                () -> exportTr31("us-east-1", key.getKeyId(), protection.getKeyId()));

        assertEquals("ValidationException", refused.getErrorCode());
    }

    @Test
    void aBlockThatIsNotTheKeysDoesNotOpen() {
        PaymentCryptographyKey protection = createWrappingKey("us-east-1");
        PaymentCryptographyKey other = createWrappingKey("us-east-1");
        PaymentCryptographyKey key = createKey("us-east-1");
        String export = exportTr31("us-east-1", key.getKeyId(), protection.getKeyId());

        AwsException refused = assertThrows(AwsException.class,
                () -> importTr31("us-east-1", other.getKeyId(), export));

        assertEquals("ValidationException", refused.getErrorCode());
    }

    @Test
    void aKeyIsCreatedInEveryRegionItIsReplicatedTo() {
        PaymentCryptographyKey primary = createReplicatedKey(service, "us-east-1", List.of("us-west-2"));

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
        Map<String, Object> attributes = dataKeyAttributes();

        AwsException refused = assertThrows(AwsException.class, () -> service.createKey("us-east-1",
                attributes, Map.of(), false, true, Map.of(), List.of("us-west-2")));

        assertEquals("ValidationException", refused.getErrorCode());
    }

    @Test
    void aNameThatIsNotARegionIsRefused() {
        // A Region a request names has to be a name of a Region this service serves, and a
        // name that is not one of those is refused.
        AwsException refused = assertThrows(AwsException.class,
                () -> createReplicatedKey(service, "us-east-1", List.of("moon-west-1")));

        assertEquals("ValidationException", refused.getErrorCode());
    }

    @Test
    void aRegionOfAnotherPartitionIsRefused() {
        // A key is replicated within the partition it is in, as the reference says, so a
        // Region of another partition is refused.
        AwsException refused = assertThrows(AwsException.class,
                () -> createReplicatedKey(service, "us-east-1", List.of("cn-north-1")));

        assertEquals("ValidationException", refused.getErrorCode());
        assertTrue(refused.getMessage().contains("cn-north-1"), refused.getMessage());
        assertNoKeyWasMade();
    }

    @Test
    void aDefaultRegionOfAnotherPartitionIsRefused() {
        // The default Regions are the Regions a new key is replicated to, so they are
        // within the partition of the Region they are the defaults of.
        AwsException refused = assertThrows(AwsException.class,
                () -> service.enableDefaultReplicationRegions("us-east-1",
                        List.of("us-gov-west-1")));

        assertEquals("ValidationException", refused.getErrorCode());
        assertTrue(refused.getMessage().contains("us-gov-west-1"), refused.getMessage());
        assertEquals(List.of(), service.defaultReplicationRegions("us-east-1"));
    }

    @Test
    void aRegionOfAnotherPartitionIsRefusedWhenTheRegionsOfAKeyAreChanged() {
        // The same rule holds when the Regions of a key are changed.
        PaymentCryptographyKey key = createReplicatedKey(service, "us-east-1", List.of("us-west-2"));

        AwsException refused = assertThrows(AwsException.class,
                () -> service.addKeyReplicationRegions("us-east-1", key.getKeyId(),
                        List.of("cn-north-1")));

        assertEquals("ValidationException", refused.getErrorCode());
        assertEquals(List.of("us-west-2"),
                service.findKey("us-east-1", key.getKeyId()).getReplicationRegions(),
                "the refused Region is in the replication of the key");
    }

    @Test
    void aKeyIsReplicatedWithinItsOwnPartition() {
        // The rule is the partition and not a list of Regions, so a key of one partition is
        // replicated to another Region of that partition.
        PaymentCryptographyKey primary = createReplicatedKey(service, "cn-north-1",
                List.of("cn-northwest-1"));

        assertEquals("PRIMARY", primary.getMultiRegionKeyType());
        assertEquals("cn-north-1", primary.getPrimaryRegion());

        PaymentCryptographyKey replica = service.findKey("cn-northwest-1", primary.getKeyId());
        assertNotNull(replica, "the key is not in the region it is replicated to");
        assertEquals("REPLICA", replica.getMultiRegionKeyType());
        assertEquals(primary.getKeyMaterialHex(), replica.getKeyMaterialHex());
    }

    @Test
    void aKeyIsReplicatedWithinThePartitionOfItsDeployment() {
        // The partition of a Region the catalog cannot place is the partition of the
        // deployment, which is the one the ARN of the key is minted in, so a key of a
        // deployment in another partition is replicated to a Region of that partition.
        PaymentCryptographyService china = new PaymentCryptographyService(
                new InMemoryStorage<>(), new InMemoryStorage<>(),
                new RegionResolver("cn-north-1", "000000000000"),
                new PaymentCryptographyRequestContext());
        PaymentCryptographyKey primary = createReplicatedKey(china, "local-1", List.of("cn-north-1"));

        assertEquals("PRIMARY", primary.getMultiRegionKeyType());
        assertTrue(primary.getArn().startsWith("arn:aws-cn:"), primary.getArn());

        PaymentCryptographyKey replica = china.findKey("cn-north-1", primary.getKeyId());
        assertNotNull(replica, "the key is not in the region it is replicated to");
        assertTrue(replica.getArn().startsWith("arn:aws-cn:"), replica.getArn());
    }

    @Test
    void theRegionOfTheRequestIsNotARegionToReplicateTo() {
        // The key is in the region of the request already, so naming that region is a request
        // that cannot be carried out.
        AwsException refused = assertThrows(AwsException.class,
                () -> createReplicatedKey(service, "us-east-1", List.of("us-east-1")));

        assertEquals("ValidationException", refused.getErrorCode());
        assertTrue(refused.getMessage().contains("us-east-1"), refused.getMessage());
        assertNoKeyWasMade();
    }

    @Test
    void theRegionOfTheRequestIsNotADefaultReplicationRegion() {
        // The default regions of a region are the regions the keys made there are replicated
        // to, and the region itself is where they are primary.
        AwsException refused = assertThrows(AwsException.class,
                () -> service.enableDefaultReplicationRegions("us-east-1", List.of("us-east-1")));

        assertEquals("ValidationException", refused.getErrorCode());
        assertEquals(List.of(), service.defaultReplicationRegions("us-east-1"));
    }

    @Test
    void aKeyWhoseRegionsAreChangedHasAConfigurationOfItsOwn() {
        // A key that took the account's default regions says so, and a key whose regions are
        // changed afterwards has a configuration of its own, so it says the other thing.
        service.enableDefaultReplicationRegions("us-east-1", List.of("us-west-2"));
        PaymentCryptographyKey key = createReplicatedKey(service, "us-east-1", List.of());
        assertTrue(key.isUsingDefaultReplicationRegions());

        PaymentCryptographyKey added = service.addKeyReplicationRegions("us-east-1", key.getKeyId(),
                List.of("us-west-1"));

        assertFalse(added.isUsingDefaultReplicationRegions());
        assertEquals(List.of("us-west-2", "us-west-1"), added.getReplicationRegions());

        // The replica carries the primary's answer, so it does not say the key takes the
        // account's defaults either.
        PaymentCryptographyKey replica = service.findKey("us-west-2", key.getKeyId());
        assertFalse(replica.isUsingDefaultReplicationRegions());
    }

    @Test
    void theAccountHasDefaultReplicationRegions() {
        assertEquals(List.of(), service.defaultReplicationRegions("us-east-1"));

        assertEquals(List.of("us-west-2"),
                service.enableDefaultReplicationRegions("us-east-1", List.of("us-west-2")));
        assertEquals(List.of("us-west-2"), service.defaultReplicationRegions("us-east-1"));

        // A key that is created with the default on is in the default region as well.
        PaymentCryptographyKey key = createReplicatedKey(service, "us-east-1", List.of());
        assertEquals(List.of("us-west-2"), key.getReplicationRegions());
        assertTrue(key.isUsingDefaultReplicationRegions());
        assertNotNull(service.findKey("us-west-2", key.getKeyId()));

        // Turning it off leaves the other regions alone.
        assertEquals(List.of("us-west-2", "us-east-2"),
                service.enableDefaultReplicationRegions("us-east-1", List.of("us-east-2")));
        assertEquals(List.of("us-west-2"),
                service.disableDefaultReplicationRegions("us-east-1", List.of("us-east-2")));
        assertEquals(List.of(),
                service.disableDefaultReplicationRegions("us-east-1", List.of("us-west-2")));
    }

    @Test
    void eachRegionHasItsOwnDefaultReplicationRegions() {
        // The settings are the ones of the Region they are read in, so a Region whose keys
        // are replicated is not the same as another Region whose keys are not.
        service.enableDefaultReplicationRegions("us-east-1", List.of("us-west-2"));

        assertEquals(List.of("us-west-2"), service.defaultReplicationRegions("us-east-1"));
        assertEquals(List.of(), service.defaultReplicationRegions("us-west-2"));

        // A key created in the other Region takes the settings of that Region, which are
        // none, so it is not replicated.
        PaymentCryptographyKey key = createReplicatedKey(service, "us-west-2", List.of());
        assertTrue(key.getReplicationRegions().isEmpty());
        assertNull(key.getMultiRegionKeyType());
    }

    @Test
    void theReplicationRegionsOfAKeyCanBeChanged() {
        PaymentCryptographyKey key = createReplicatedKey(service, "us-east-1", List.of("us-west-2"));

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
    void takingTheLastReplicaAwayEndsTheReplication() {
        // Taking the replica regions away is how the replication is turned off for a key, so
        // a key that replicates nowhere is not a multi-region key and says so by carrying no
        // type at all.
        PaymentCryptographyKey key = createReplicatedKey(service, "us-east-1", List.of("us-west-2"));
        assertEquals("PRIMARY", key.getMultiRegionKeyType());

        PaymentCryptographyKey plain = service.removeKeyReplicationRegions("us-east-1",
                key.getKeyId(), List.of("us-west-2"));

        assertTrue(plain.getReplicationRegions().isEmpty(), "the key replicates nowhere");
        assertNull(plain.getMultiRegionKeyType(), "the key is still a primary");
        assertNull(plain.getPrimaryRegion(), "the key still names a primary region");
        assertNull(service.findKey("us-west-2", key.getKeyId()), "the key is in the region it left");
    }

    @Test
    void aReplicaIsReadOnly() {
        PaymentCryptographyKey key = createReplicatedKey(service, "us-east-1", List.of("us-west-2"));
        PaymentCryptographyKey replica = service.findKey("us-west-2", key.getKeyId());

        AwsException refused = assertThrows(AwsException.class, () -> service.deleteKey(replica));
        assertEquals("ValidationException", refused.getErrorCode());

        AwsException notPrimary = assertThrows(AwsException.class,
                () -> service.addKeyReplicationRegions("us-west-2", key.getKeyId(), List.of("us-west-1")));
        assertEquals("ValidationException", notPrimary.getErrorCode());
    }

    @Test
    void aRegionTheKeyAlreadyReplicatesIntoIsRefused() {
        // Adding a region the key already replicates into changes nothing, and a request
        // that cannot be carried out is refused rather than answered with what it already was.
        PaymentCryptographyKey key = createReplicatedKey(service, "us-east-1", List.of("us-west-2"));

        AwsException refused = assertThrows(AwsException.class,
                () -> service.addKeyReplicationRegions("us-east-1", key.getKeyId(),
                        List.of("us-west-2")));
        assertEquals("ValidationException", refused.getErrorCode());
        assertTrue(refused.getMessage().contains("already replicates into"), refused.getMessage());
    }

    @Test
    void aRegionTheKeyDoesNotReplicateIntoIsRefused() {
        // There is nothing to take away from a region the key does not replicate into.
        PaymentCryptographyKey key = createReplicatedKey(service, "us-east-1", List.of("us-west-2"));

        AwsException refused = assertThrows(AwsException.class,
                () -> service.removeKeyReplicationRegions("us-east-1", key.getKeyId(),
                        List.of("us-west-1")));
        assertEquals("ValidationException", refused.getErrorCode());
        assertTrue(refused.getMessage().contains("does not replicate into"), refused.getMessage());
    }

    @Test
    void theTagsOfAReplicaAreThePrimaries() {
        // A replica is a copy of the primary's material and metadata, so it carries the
        // primary's tags, and a change to them is replicated.
        PaymentCryptographyKey key = createReplicatedKey(service, "us-east-1", List.of("us-west-2"));
        service.tag(key, Map.of("owner", "team"));

        PaymentCryptographyKey replica = service.findKey("us-west-2", key.getKeyId());

        assertEquals("team", replica.getTags().get("owner"));
        assertEquals("team", service.findKey("us-east-1", key.getKeyId()).getTags().get("owner"));
    }

    @Test
    void aReplicaIsReadOnlyForItsOwnMetadata() {
        // The tags of a replica are the primary's, so a change to them is made on the primary.
        PaymentCryptographyKey key = createReplicatedKey(service, "us-east-1", List.of("us-west-2"));
        PaymentCryptographyKey replica = service.findKey("us-west-2", key.getKeyId());

        AwsException refused = assertThrows(AwsException.class,
                () -> service.tag(replica, Map.of("owner", "team")));
        assertEquals("ValidationException", refused.getErrorCode());
    }

    @Test
    void aRefusedReplicationLeavesNoKeyBehind() {
        // The key is refused before it is made, so a request that cannot be carried out
        // leaves nothing in the region it was made in.
        AwsException refused = assertThrows(AwsException.class,
                () -> createReplicatedKey(service, "us-east-1", List.of("moon-west-1")));
        assertEquals("ValidationException", refused.getErrorCode());
        assertNoKeyWasMade();
    }

    @Test
    void aRefusedImportLeavesNoKeyBehind() {
        PaymentCryptographyKey protection = createKey("us-east-1", "AES_256", "SYMMETRIC_KEY",
                "TR31_K1_KEY_BLOCK_PROTECTION_KEY",
                Map.of("Wrap", Boolean.TRUE, "Unwrap", Boolean.TRUE));
        PaymentCryptographyKey key = createKey("us-east-1", "AES_256", "SYMMETRIC_KEY",
                "TR31_D0_SYMMETRIC_DATA_ENCRYPTION_KEY",
                Map.of("Encrypt", Boolean.TRUE, "Decrypt", Boolean.TRUE));
        String block = exportTr31("us-east-1", key.getKeyId(), protection.getKeyId());

        AwsException refused = assertThrows(AwsException.class,
                () -> service.importKey("us-east-1", "Tr31KeyBlock", Map.of(
                        "WrappingKeyIdentifier", protection.getKeyId(),
                        "WrappedKeyBlock", block), "", true, Map.of(), List.of("moon-west-1")));
        assertEquals("ValidationException", refused.getErrorCode());
        assertEquals(2, service.listKeys("us-east-1", null, 10, null).items().size(),
                "the refused import left a key in the store");
    }

    @Test
    void aKeyThatIsNotExportableIsNotReplicated() {
        // A replica is a copy of the key's material, so a key that may not leave is not
        // replicated, and the refusal leaves no key behind.
        Map<String, Object> attributes = dataKeyAttributes();

        AwsException refused = assertThrows(AwsException.class,
                () -> service.createKey("us-east-1", attributes, Map.of(), false, true, Map.of(),
                        List.of("us-west-2")));
        assertEquals("ValidationException", refused.getErrorCode());
        assertNoKeyWasMade();
    }

    @Test
    void aKeyThatIsNotExportableIsNotReplicatedToTheDefaultsEither() {
        // The defaults are the regions the key takes when the request names none, so a key
        // that may not leave is refused there as well.
        service.enableDefaultReplicationRegions("us-east-1", List.of("us-west-2"));
        Map<String, Object> attributes = dataKeyAttributes();

        AwsException refused = assertThrows(AwsException.class,
                () -> service.createKey("us-east-1", attributes, Map.of(), false, true, Map.of(),
                        List.of()));
        assertEquals("ValidationException", refused.getErrorCode());
        assertNoKeyWasMade();
    }

    @Test
    void aRegionTheKeyIsAlreadyInIsNotAReplicationTarget() {
        // The key is in its own region already, so naming that region again is a request
        // that cannot be carried out, and the key does not become a multi-region key.
        PaymentCryptographyKey key = createKey("us-east-1");

        AwsException refused = assertThrows(AwsException.class,
                () -> service.addKeyReplicationRegions("us-east-1", key.getKeyId(),
                        List.of("us-east-1")));
        assertEquals("ValidationException", refused.getErrorCode());

        PaymentCryptographyKey current = service.findKey("us-east-1", key.getKeyId());
        assertNull(current.getMultiRegionKeyType(), "the key is a multi-region key with no replica");
        assertTrue(current.getReplicationRegions().isEmpty());
    }

    @Test
    void aRequestThatNamesNoRegionToAddIsRefused() {
        PaymentCryptographyKey key = createKey("us-east-1");

        AwsException refused = assertThrows(AwsException.class,
                () -> service.addKeyReplicationRegions("us-east-1", key.getKeyId(), List.of()));
        assertEquals("ValidationException", refused.getErrorCode());
        assertNull(service.findKey("us-east-1", key.getKeyId()).getMultiRegionKeyType());
    }

    @Test
    void theRegionsOfAReplicaFollowThePrimary() {
        // A replica reports the regions the primary is in, so it does not report a region
        // the primary has left behind.
        PaymentCryptographyKey key = createReplicatedKey(service, "us-east-1", List.of("us-west-2"));

        service.addKeyReplicationRegions("us-east-1", key.getKeyId(), List.of("us-west-1"));

        PaymentCryptographyKey replica = service.findKey("us-west-2", key.getKeyId());
        assertEquals(List.of("us-west-2", "us-west-1"), replica.getReplicationRegions());

        service.removeKeyReplicationRegions("us-east-1", key.getKeyId(), List.of("us-west-2"));

        PaymentCryptographyKey left = service.findKey("us-west-1", key.getKeyId());
        assertEquals(List.of("us-west-1"), left.getReplicationRegions());
    }

    @Test
    void aReplicaThatIsLeftBehindReportsTheRegionsThePrimaryHasNow() {
        PaymentCryptographyKey key = createReplicatedKey(service, "us-east-1", List.of("us-west-2"));

        service.removeKeyReplicationRegions("us-east-1", key.getKeyId(), List.of("us-west-2"));

        assertNull(service.findKey("us-west-2", key.getKeyId()), "the replica is still there");
        PaymentCryptographyKey primary = service.findKey("us-east-1", key.getKeyId());
        assertNull(primary.getMultiRegionKeyType(), "the key is still a multi-region key");
        assertTrue(primary.getReplicationRegions().isEmpty());
    }

    @Test
    void theAliasesOfARemovedReplicaGoWithIt() {
        // An alias that names a key that is not there cannot be resolved, so it goes when
        // the replica does.
        PaymentCryptographyKey key = createReplicatedKey(service, "us-east-1", List.of("us-west-2"));
        PaymentCryptographyKey replica = service.findKey("us-west-2", key.getKeyId());
        service.createAlias("alias/team/card-key", replica);
        assertNotNull(service.findAlias("us-west-2", "alias/team/card-key"));

        service.removeKeyReplicationRegions("us-east-1", key.getKeyId(), List.of("us-west-2"));

        assertNull(service.findAlias("us-west-2", "alias/team/card-key"),
                "the alias of the removed replica is still there");
        assertEquals(0, service.listAliases("us-west-2", null).size());
    }

    @Test
    void theAliasesOfAReplicaGoWithThePrimary() {
        // A replica shares the identifier of its primary, so the aliases that name the
        // replica are the aliases of the key, and they go when the key goes.
        PaymentCryptographyKey key = createReplicatedKey(service, "us-east-1", List.of("us-west-2"));
        PaymentCryptographyKey replica = service.findKey("us-west-2", key.getKeyId());
        service.createAlias("alias/team/card-key", replica);
        PaymentCryptographyKey primary = service.findKey("us-east-1", key.getKeyId());
        service.createAlias("alias/team/primary-key", primary);

        service.deleteKey(primary);

        assertNull(service.findAlias("us-west-2", "alias/team/card-key"),
                "the alias of the replica is still there");
        assertNull(service.findAlias("us-east-1", "alias/team/primary-key"),
                "the alias of the primary is still there");
    }

    @Test
    void aReplicaCarriesTheMetadataOfItsPrimary() {
        // A replica is a copy of the primary's material and metadata, so every change made
        // on the primary reaches it: its tags, its state, whether it is enabled, and the
        // regions it replicates to.
        PaymentCryptographyKey key = createReplicatedKey(service, "us-east-1", List.of("us-west-2"));
        service.tag(key, Map.of("owner", "team"));
        service.addKeyReplicationRegions("us-east-1", key.getKeyId(), List.of("us-west-1"));

        PaymentCryptographyKey replica = service.findKey("us-west-2", key.getKeyId());
        PaymentCryptographyKey primary = service.findKey("us-east-1", key.getKeyId());

        assertEquals(primary.getTags(), replica.getTags());
        assertEquals(primary.getKeyState(), replica.getKeyState());
        assertEquals(primary.isEnabled(), replica.isEnabled());
        assertEquals(primary.getPrimaryRegion(), replica.getPrimaryRegion());
        assertEquals(primary.getReplicationRegions(), replica.getReplicationRegions());
        assertEquals(primary.isUsingDefaultReplicationRegions(),
                replica.isUsingDefaultReplicationRegions());
    }

    @Test
    void replicatingAKeySavedUnderTheOldNameKeepsOneRecordOfIt() {
        // A store written before the region became part of the name holds its keys under
        // their identifier alone, so replicating one moves it to the name the store holds it
        // under now, and the list does not answer with the key twice.
        InMemoryStorage<String, PaymentCryptographyKey> store = new InMemoryStorage<>();
        PaymentCryptographyService old = serviceOverLegacyStore(store);
        PaymentCryptographyKey key = createKey("us-east-1");
        // The key under the name it was saved under before the region was part of it.
        store.put(key.getKeyId(), key);

        old.addKeyReplicationRegions("us-east-1", key.getKeyId(), List.of("us-west-2"));

        assertEquals(1, old.listKeys("us-east-1", null, 10, null).items().size(),
                "the key is in the list twice");
        assertNull(store.get(key.getKeyId()).orElse(null),
                "the key is still under the name it was saved under");
    }

    @Test
    void aReplicaIsUsableOnItsOwn() {
        // A replica is a complete copy of the key, so the key leaves the service from the
        // region the replica is in as well as from the primary's.
        PaymentCryptographyKey key = createReplicatedKey(service, "us-east-1", List.of("us-west-2"));
        PaymentCryptographyKey protection = createWrappingKey("us-west-2");

        Map<String, Object> answer = exportTr31Answer("us-west-2", key.getKeyId(),
                protection.getKeyId());
        Map<?, ?> wrapped = (Map<?, ?>) answer.get("WrappedKey");
        String export = (String) wrapped.get("KeyMaterial");

        assertTrue(export.length() > 32, "the block is only its header");
        assertEquals(key.getKeyCheckValue(), wrapped.get("KeyCheckValue"));

        PaymentCryptographyKey imported = importTr31("us-west-2", protection.getKeyId(), export);
        assertEquals(key.getKeyMaterialHex(), imported.getKeyMaterialHex());
    }

    @Test
    void aReplicaThatIsAlreadyThereTakesThePrimariesTags() {
        // A replica is a copy of the primary's material and metadata, so a replica that is
        // already there takes the primary's tags when the replication changes.
        PaymentCryptographyKey key = createReplicatedKey(service, "us-east-1", List.of("us-west-2"));
        PaymentCryptographyKey primary = service.findKey("us-east-1", key.getKeyId());
        primary.getTags().put("owner", "team");
        store.put("us-east-1\u0000" + key.getKeyId(), primary);
        // A replica whose tags are not the primary's, as a record written before the tags
        // were replicated may hold.
        PaymentCryptographyKey replica = service.findKey("us-west-2", key.getKeyId());
        replica.setTags(new LinkedHashMap<>(Map.of("owner", "other")));
        store.put("us-west-2\u0000" + key.getKeyId(), replica);

        service.addKeyReplicationRegions("us-east-1", key.getKeyId(), List.of("us-west-1"));

        PaymentCryptographyKey after = service.findKey("us-west-2", key.getKeyId());
        assertEquals("team", after.getTags().get("owner"), "the replica kept the tags it had");
    }

    @Test
    void aReplicaThatIsLeftBehindTakesThePrimariesTags() {
        // The same holds for the replicas that survive a removal of a region.
        PaymentCryptographyKey key = createReplicatedKey(service, "us-east-1", List.of("us-west-2"));
        service.addKeyReplicationRegions("us-east-1", key.getKeyId(), List.of("us-west-1"));
        PaymentCryptographyKey primary = service.findKey("us-east-1", key.getKeyId());
        primary.getTags().put("owner", "team");
        store.put("us-east-1\u0000" + key.getKeyId(), primary);
        PaymentCryptographyKey replica = service.findKey("us-west-1", key.getKeyId());
        replica.setTags(new LinkedHashMap<>(Map.of("owner", "other")));
        store.put("us-west-1\u0000" + key.getKeyId(), replica);

        service.removeKeyReplicationRegions("us-east-1", key.getKeyId(), List.of("us-west-2"));

        PaymentCryptographyKey after = service.findKey("us-west-1", key.getKeyId());
        assertEquals("team", after.getTags().get("owner"), "the replica kept the tags it had");
    }

    /**
     * A key of the data the cipher protects, replicated to the regions named.
     *
     * @param service the service the key is made in
     * @param region the region the key is made in
     * @param regions the regions the key is replicated to
     */
    private static PaymentCryptographyKey createReplicatedKey(PaymentCryptographyService service, String region,
                                                              List<String> regions) {
        return service.createKey(region, dataKeyAttributes(), dataKeyModes(), true, true, Map.of(), regions);
    }

    /**
     * The TR-31 key block a key leaves the service in.
     */
    private String exportTr31(String region, String keyId, String wrappingKeyId) {
        return (String) ((Map<?, ?>) exportTr31Answer(region, keyId, wrappingKeyId)
                .get("WrappedKey")).get("KeyMaterial");
    }

    /**
     * The whole answer of an export, which carries the block and what wrapped it.
     */
    private Map<String, Object> exportTr31Answer(String region, String keyId, String wrappingKeyId) {
        return service.exportKey(region, keyId, "Tr31KeyBlock",
                Map.of("WrappingKeyIdentifier", wrappingKeyId), "", "");
    }

    /**
     * The key that comes into the service out of a TR-31 key block.
     */
    private PaymentCryptographyKey importTr31(String region, String wrappingKeyId, String block) {
        return service.importKey(region, "Tr31KeyBlock", Map.of(
                "WrappingKeyIdentifier", wrappingKeyId,
                "WrappedKeyBlock", block), "", true, Map.of());
    }

    @Test
    void anImportedKeyIsReplicatedToTheRegionsTheRequestNames() {
        // The model gives an import the same replication regions a creation does, so a key
        // that comes in can be replicated as soon as it lands.
        PaymentCryptographyKey protection = createKey("us-east-1", "AES_256", "SYMMETRIC_KEY",
                "TR31_K1_KEY_BLOCK_PROTECTION_KEY",
                Map.of("Wrap", Boolean.TRUE, "Unwrap", Boolean.TRUE));
        PaymentCryptographyKey key = createKey("us-east-1", "AES_256", "SYMMETRIC_KEY",
                "TR31_D0_SYMMETRIC_DATA_ENCRYPTION_KEY",
                Map.of("Encrypt", Boolean.TRUE, "Decrypt", Boolean.TRUE));
        String block = exportTr31("us-east-1", key.getKeyId(), protection.getKeyId());

        PaymentCryptographyKey imported = service.importKey("us-east-1", "Tr31KeyBlock", Map.of(
                "WrappingKeyIdentifier", protection.getKeyId(),
                "WrappedKeyBlock", block), "", true, Map.of(), List.of("us-west-2"));

        assertEquals("PRIMARY", imported.getMultiRegionKeyType());
        assertEquals("us-east-1", imported.getPrimaryRegion());
        assertEquals(List.of("us-west-2"), imported.getReplicationRegions());

        PaymentCryptographyKey replica = service.findKey("us-west-2", imported.getKeyId());
        assertNotNull(replica, "the key was not replicated");
        assertEquals("REPLICA", replica.getMultiRegionKeyType());
        assertEquals(imported.getKeyMaterialHex(), replica.getKeyMaterialHex());
    }

    @Test
    void theTagsOfAReplicaFollowThePrimary() {
        // A replica is a copy of its primary's material and metadata, so a change to the
        // primary's tags reaches it.
        PaymentCryptographyKey key = createReplicatedKey(service, "us-east-1", List.of("us-west-2"));

        service.tag(key, Map.of("owner", "team"));

        PaymentCryptographyKey replica = service.findKey("us-west-2", key.getKeyId());
        assertEquals("team", replica.getTags().get("owner"));

        service.untag(key, List.of("owner"));

        PaymentCryptographyKey after = service.findKey("us-west-2", key.getKeyId());
        assertNull(after.getTags().get("owner"), "the tag was not taken away");
    }

    /** The attributes of a key with that algorithm and usage. */
    private static Map<String, Object> attributes(String algorithm, String usage) {
        return attributes(algorithm, "SYMMETRIC_KEY", usage);
    }

    /** The attributes of a key with that algorithm, class and usage. */
    private static Map<String, Object> attributes(String algorithm, String keyClass, String usage) {
        Map<String, Object> attributes = new LinkedHashMap<>();
        attributes.put("KeyAlgorithm", algorithm);
        attributes.put("KeyClass", keyClass);
        attributes.put("KeyUsage", usage);
        return attributes;
    }

    /** The attributes of an AES-256 data encryption key. */
    private static Map<String, Object> dataKeyAttributes() {
        return attributes("AES_256", "TR31_D0_SYMMETRIC_DATA_ENCRYPTION_KEY");
    }

    /** The modes of use of a data encryption key. */
    private static Map<String, Boolean> dataKeyModes() {
        Map<String, Boolean> modes = new LinkedHashMap<>();
        modes.put("Encrypt", Boolean.TRUE);
        modes.put("Decrypt", Boolean.TRUE);
        return modes;
    }

    /**
     * The service over a store that holds its keys under their identifier alone.
     *
     * <p>A store written before the region became part of the name holds its keys under
     * their identifier alone, and the tests that need such a store make one.
     *
     * @param store the store the service keeps its keys in
     */
    private static PaymentCryptographyService serviceOverLegacyStore(
            InMemoryStorage<String, PaymentCryptographyKey> store) {
        return new PaymentCryptographyService(store,
                new InMemoryStorage<>(), new RegionResolver("us-east-1", "000000000000"),
                new PaymentCryptographyRequestContext());
    }

    /** That a refused request left no key behind. */
    private void assertNoKeyWasMade() {
        assertEquals(0, service.listKeys("us-east-1", null, 10, null).items().size(),
                "the refused request left a key in the store");
    }
}
