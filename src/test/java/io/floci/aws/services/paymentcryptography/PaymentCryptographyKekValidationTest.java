package io.floci.aws.services.paymentcryptography;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.floci.aws.core.common.AwsException;
import io.floci.aws.core.common.RegionResolver;
import io.floci.aws.core.storage.InMemoryStorage;
import io.floci.aws.services.paymentcryptography.model.PaymentCryptographyKey;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The AS2805 KEK validation.
 *
 * <p>The values in the first tests are an independent implementation's: a reference that
 * implements the standard's ceremony answers the same numbers for the same key, and the
 * emulator is held to them. They are the keys and the numbers of that reference, so a change
 * to the construction is a change to the bytes the standard's two nodes agree on.
 */
class PaymentCryptographyKekValidationTest {

    /** The key the reference used, and the variant of it the random number is enciphered under. */
    private static final String KEK = "000102030405060708090A0B0C0D0E0F";
    private static final String VARIANT_RN = "82838081868784858A8B88898E8F8C8D";
    private static final String VARIANT_NOT_RN = "84858687808182838C8D8E8F88898A8B";

    /** The random number of one block, and the two numbers the ceremony makes of it. */
    private static final String RN = "468AD0B5CE10FBB6";
    private static final String SEND = "53E0A068482E18E7";
    private static final String RECEIVE = "3A343F9B1845D305";

    /** The random number of two blocks, and the two numbers the ceremony makes of it. */
    private static final String LONG_RN = "468AD0B5CE10FBB60123456789ABCDEF";
    private static final String LONG_SEND = "53E0A068482E18E75FF1C1A1C1A1EF65";
    private static final String LONG_RECEIVE = "3A343F9B1845D3050CC638EBBA236EC2";

    private PaymentCryptographyService service;
    private PaymentCryptographyKekValidationOperations validation;

    @BeforeEach
    void setUp() {
        service = new PaymentCryptographyService(
                new InMemoryStorage<>(),
                new InMemoryStorage<>(),
                new RegionResolver("us-east-1", "000000000000"));
        validation = new PaymentCryptographyKekValidationOperations(service);
    }

    // ---- the construction ----------------------------------------------------

    @Test
    void theVariantKeyIsTheKeyExclusiveOrWithTheMask() {
        byte[] key = PaymentCryptographyCrypto.fromHex(KEK);

        assertArrayEquals(PaymentCryptographyCrypto.fromHex(VARIANT_RN),
                PaymentCryptographyKekValidation.variantKey(key, "82"));
        assertArrayEquals(PaymentCryptographyCrypto.fromHex(VARIANT_NOT_RN),
                PaymentCryptographyKekValidation.variantKey(key, "84"));
    }

    @Test
    void theRandomNumberIsEncipheredUnderTheVariant() {
        byte[] variant = PaymentCryptographyKekValidation.variantKey(
                PaymentCryptographyCrypto.fromHex(KEK), "82");

        assertArrayEquals(PaymentCryptographyCrypto.fromHex(SEND),
                PaymentCryptographyKekValidation.encrypt(
                        PaymentCryptographyCrypto.fromHex(RN), variant,
                        PaymentCryptographyKeyAlgorithm.TDES_2KEY));
    }

    @Test
    void theInvertedNumberIsEncipheredUnderTheOtherVariant() {
        byte[] variant = PaymentCryptographyKekValidation.variantKey(
                PaymentCryptographyCrypto.fromHex(KEK), "84");

        assertArrayEquals(PaymentCryptographyCrypto.fromHex(RECEIVE),
                PaymentCryptographyKekValidation.encrypt(
                        PaymentCryptographyKekValidation.invert(
                                PaymentCryptographyCrypto.fromHex(RN)),
                        variant, PaymentCryptographyKeyAlgorithm.TDES_2KEY));
    }

    @Test
    void aLongerNumberIsEncipheredABlockAtATime() {
        byte[] variant = PaymentCryptographyKekValidation.variantKey(
                PaymentCryptographyCrypto.fromHex(KEK), "82");
        byte[] other = PaymentCryptographyKekValidation.variantKey(
                PaymentCryptographyCrypto.fromHex(KEK), "84");

        assertArrayEquals(PaymentCryptographyCrypto.fromHex(LONG_SEND),
                PaymentCryptographyKekValidation.encrypt(
                        PaymentCryptographyCrypto.fromHex(LONG_RN), variant,
                        PaymentCryptographyKeyAlgorithm.TDES_2KEY));
        assertArrayEquals(PaymentCryptographyCrypto.fromHex(LONG_RECEIVE),
                PaymentCryptographyKekValidation.encrypt(
                        PaymentCryptographyKekValidation.invert(
                                PaymentCryptographyCrypto.fromHex(LONG_RN)),
                        other, PaymentCryptographyKeyAlgorithm.TDES_2KEY));
    }

    @Test
    void theRandomNumberCarriesTheOddParity() {
        assertArrayEquals(new byte[] {0x01},
                PaymentCryptographyKekValidation.oddParity(new byte[] {0x00}));
        assertArrayEquals(new byte[] {0x7F},
                PaymentCryptographyKekValidation.oddParity(new byte[] {0x7E}));
        assertArrayEquals(new byte[] {0x7F},
                PaymentCryptographyKekValidation.oddParity(new byte[] {0x7F}));
    }

    // ---- the operation -------------------------------------------------------

    @ParameterizedTest
    @ValueSource(strings = {"VARIANT_MASK_82", "VARIANT_MASK_82C0"})
    void theAnswerOfTheRequestFormIsTheAnswerOfTheResponseForm(String mask) {
        PaymentCryptographyKey kek = kek();

        Map<String, Object> asked = validation.generate(request(kek.getArn(), null,
                "BYTES_16", mask, "TDES_2KEY"), "us-east-1");
        String send = (String) asked.get("RandomKeySend");
        String receive = (String) asked.get("RandomKeyReceive");

        assertEquals(kek.getArn(), asked.get("KeyArn"));
        assertTrue(send.matches("[0-9A-F]{32}"), send);
        assertTrue(receive.matches("[0-9A-F]{32}"), receive);

        Map<String, Object> answered = validation.generate(request(kek.getArn(), send, null, mask,
                "TDES_2KEY"), "us-east-1");

        assertEquals(send, answered.get("RandomKeySend"));
        assertEquals(receive, answered.get("RandomKeyReceive"));
    }

    @Test
    void theTwoNodesThatHoldDifferentKeysDoNotAgree() {
        PaymentCryptographyKey one = kek();
        PaymentCryptographyKey other = kek();

        Map<String, Object> asked = validation.generate(request(one.getArn(), null, "BYTES_16"),
                "us-east-1");
        Map<String, Object> answered = validation.generate(request(other.getArn(),
                (String) asked.get("RandomKeySend"), null), "us-east-1");

        assertNotEquals(asked.get("RandomKeyReceive"), answered.get("RandomKeyReceive"));
    }

    @Test
    void aKeyOfTheWrongUsageIsRefused() {
        PaymentCryptographyKey wrong = PaymentCryptographyTestFixtures.createKey(service, "TDES_2KEY",
                "TR31_D0_SYMMETRIC_DATA_ENCRYPTION_KEY", "Encrypt", "Decrypt");
        AwsException refused = assertThrows(AwsException.class, () -> validation.generate(
                request(wrong.getArn(), null, "BYTES_16"), "us-east-1"));

        assertEquals("ValidationException", refused.getErrorCode());
    }

    @Test
    void aKeyThatMayNotEncryptIsRefused() {
        PaymentCryptographyKey wrong = PaymentCryptographyTestFixtures.createKey(service, "TDES_2KEY",
                "TR31_K0_KEY_ENCRYPTION_KEY", "Decrypt");

        AwsException refused = assertThrows(AwsException.class, () -> validation.generate(
                request(wrong.getArn(), null, "BYTES_16"), "us-east-1"));

        assertEquals("ValidationException", refused.getErrorCode());
    }

    @Test
    void aKeyThatIsNotThereIsRefused() {
        AwsException refused = assertThrows(AwsException.class, () -> validation.generate(
                request("arn:aws:payment-cryptography:us-east-1:000000000000:key/"
                        + "abcdefghij012345", null, "BYTES_16"), "us-east-1"));

        assertEquals("ResourceNotFoundException", refused.getErrorCode());
    }

    @Test
    void aRequestThatNamesNoVariantIsRefused() {
        PaymentCryptographyKey kek = kek();

        AwsException refused = assertThrows(AwsException.class, () -> validation.generate(
                requestWithoutAVariant(kek.getArn()), "us-east-1"));

        assertEquals("ValidationException", refused.getErrorCode());
    }

    @Test
    void aMaskTheModelDoesNotNameIsRefused() {
        PaymentCryptographyKey kek = kek();

        AwsException refused = assertThrows(AwsException.class, () -> validation.generate(
                request(kek.getArn(), null, "BYTES_16", "VARIANT_MASK_42", "TDES_2KEY"),
                "us-east-1"));

        assertEquals("ValidationException", refused.getErrorCode());
    }

    @Test
    void aDerivedAlgorithmThatIsNotTheKeysIsRefused() {
        PaymentCryptographyKey kek = kek();

        AwsException refused = assertThrows(AwsException.class, () -> validation.generate(
                request(kek.getArn(), null, "BYTES_16", "VARIANT_MASK_82", "AES_256"),
                "us-east-1"));

        assertEquals("ValidationException", refused.getErrorCode());
    }

    /** A key of the usage and the modes a KEK validation needs. */
    private PaymentCryptographyKey kek() {
        return PaymentCryptographyTestFixtures.createKey(service, "TDES_2KEY",
                "TR31_K0_KEY_ENCRYPTION_KEY", "Encrypt", "Decrypt");
    }

    /**
     * A request of the operation: the request form, or the response form when a random
     * number is given.
     */
    private static JsonNode request(String keyIdentifier, String incoming, String length) {
        return request(keyIdentifier, incoming, length, "VARIANT_MASK_82", "TDES_2KEY");
    }

    /**
     * A request of the operation under the mask and the algorithm the request names.
     */
    private static JsonNode request(String keyIdentifier, String incoming, String length,
                                    String mask, String algorithm) {
        ObjectMapper mapper = new ObjectMapper();
        ObjectNode body = mapper.createObjectNode();
        body.put("KeyIdentifier", keyIdentifier);
        body.put("RandomKeySendVariantMask", mask);
        ObjectNode type = mapper.createObjectNode();
        if (incoming == null) {
            ObjectNode attributes = mapper.createObjectNode();
            attributes.put("DeriveKeyAlgorithm", algorithm);
            attributes.put("RandomKeyMaxLength", length);
            type.set("KekValidationRequest", attributes);
        } else {
            ObjectNode attributes = mapper.createObjectNode();
            attributes.put("RandomKeySend", incoming);
            type.set("KekValidationResponse", attributes);
        }
        body.set("KekValidationType", type);
        return body;
    }

    /**
     * A request whose validation type names no variant at all.
     */
    private static JsonNode requestWithoutAVariant(String keyIdentifier) {
        ObjectMapper mapper = new ObjectMapper();
        ObjectNode body = mapper.createObjectNode();
        body.put("KeyIdentifier", keyIdentifier);
        body.put("RandomKeySendVariantMask", "VARIANT_MASK_82");
        body.set("KekValidationType", mapper.createObjectNode());
        return body;
    }
}
