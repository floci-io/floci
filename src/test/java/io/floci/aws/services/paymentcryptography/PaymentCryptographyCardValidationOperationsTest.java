package io.floci.aws.services.paymentcryptography;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.floci.aws.core.common.AwsException;
import io.floci.aws.core.common.RegionResolver;
import io.floci.aws.core.storage.InMemoryStorage;
import io.floci.aws.services.paymentcryptography.crypto.CvvCrypto;
import io.floci.aws.services.paymentcryptography.model.PaymentCryptographyKey;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The card validation operations.
 *
 * <p>The values in the first test are the emulator's, and the emulator checks them against
 * an independent implementation: the card verification value of an account number,
 * an expiry and a service code.
 */
class PaymentCryptographyCardValidationOperationsTest {

    private static final String PAN = "5081470082639564";
    private static final String EXPIRY = "3003";
    private static final String CVK = "0123456789ABCDEFFEDCBA9876543210";

    private PaymentCryptographyService service;
    private PaymentCryptographyCardValidationOperations cardValidation;
    private final ObjectMapper mapper = new ObjectMapper();

    @BeforeEach
    void setUp() {
        service = new PaymentCryptographyService(
                new InMemoryStorage<>(),
                new InMemoryStorage<>(),
                new RegionResolver("us-east-1", "000000000000"));
        cardValidation = new PaymentCryptographyCardValidationOperations(service);
    }

    /** The attributes of a card verification value: the expiry and the service code. */
    private static JsonNode cardVerificationValue1(String expiry, String serviceCode) {
        ObjectMapper mapper = new ObjectMapper();
        ObjectNode value = mapper.createObjectNode();
        value.put("CardExpiryDate", expiry);
        value.put("ServiceCode", serviceCode);
        ObjectNode outer = mapper.createObjectNode();
        outer.set("CardVerificationValue1", value);
        return outer;
    }

    @Test
    void theCardVerificationValueIsTheOneTheStandardGives() {
        assertEquals("131", CvvCrypto.computeCvv(PAN, EXPIRY, "201", CVK, 3));
        assertEquals("886", CvvCrypto.computeCvv(PAN, EXPIRY, "000", CVK, 3));
        assertEquals("660", CvvCrypto.computeCvv(PAN, EXPIRY, "999", CVK, 3));
    }

    @Test
    void aGeneratedValueVerifies() {
        PaymentCryptographyKey cvk = PaymentCryptographyTestFixtures.createKey(service, "TDES_2KEY", "TR31_C0_CARD_VERIFICATION_KEY",
                "Generate", "Verify");

        Map<String, Object> generated = cardValidation.generate(PaymentCryptographyTestFixtures.request(Map.of(
                "KeyIdentifier", cvk.getArn(),
                "PrimaryAccountNumber", PAN,
                "GenerationAttributes", cardVerificationValue1(EXPIRY, "201"))), "us-east-1");

        String value = (String) generated.get("ValidationData");
        assertEquals(3, value.length());
        assertEquals(cvk.getArn(), generated.get("KeyArn"));

        Map<String, Object> verified = cardValidation.verify(PaymentCryptographyTestFixtures.request(Map.of(
                "KeyIdentifier", cvk.getArn(),
                "PrimaryAccountNumber", PAN,
                "ValidationData", value,
                "VerificationAttributes", cardVerificationValue1(EXPIRY, "201"))), "us-east-1");

        assertEquals(cvk.getArn(), verified.get("KeyArn"));
    }

    @Test
    void aWrongValueIsRefused() {
        PaymentCryptographyKey cvk = PaymentCryptographyTestFixtures.createKey(service, "TDES_2KEY", "TR31_C0_CARD_VERIFICATION_KEY",
                "Generate", "Verify");

        AwsException refused = assertThrows(AwsException.class, () -> cardValidation.verify(
                PaymentCryptographyTestFixtures.request(Map.of(
                        "KeyIdentifier", cvk.getArn(),
                        "PrimaryAccountNumber", PAN,
                        "ValidationData", "999",
                        "VerificationAttributes", cardVerificationValue1(EXPIRY, "201"))),
                "us-east-1"));

        assertEquals("VerificationFailedException", refused.getErrorCode());
    }

    @Test
    void aKeyOfTheWrongUsageIsRefused() {
        PaymentCryptographyKey wrong = PaymentCryptographyTestFixtures.createKey(service, "TDES_2KEY", "TR31_D0_SYMMETRIC_DATA_ENCRYPTION_KEY",
                "Generate", "Verify");

        AwsException refused = assertThrows(AwsException.class,
                () -> cardValidation.generate(PaymentCryptographyTestFixtures.request(Map.of(
                        "KeyIdentifier", wrong.getArn(),
                        "PrimaryAccountNumber", PAN,
                        "GenerationAttributes", cardVerificationValue1(EXPIRY, "201"))), "us-east-1"));

        assertEquals("ValidationException", refused.getErrorCode());
        assertTrue(refused.getMessage().contains("TR31_C0_CARD_VERIFICATION_KEY"),
                refused.getMessage());
    }

    @Test
    void aKeyThatMayNotGenerateIsRefused() {
        PaymentCryptographyKey cvk = PaymentCryptographyTestFixtures.createKey(service, "TDES_2KEY", "TR31_C0_CARD_VERIFICATION_KEY",
                "Verify");

        AwsException refused = assertThrows(AwsException.class,
                () -> cardValidation.generate(PaymentCryptographyTestFixtures.request(Map.of(
                        "KeyIdentifier", cvk.getArn(),
                        "PrimaryAccountNumber", PAN,
                        "GenerationAttributes", cardVerificationValue1(EXPIRY, "201"))), "us-east-1"));

        assertEquals("ValidationException", refused.getErrorCode());
    }

    @Test
    void anExpiryThatIsNotFourDigitsIsRefused() {
        PaymentCryptographyKey cvk = PaymentCryptographyTestFixtures.createKey(service, "TDES_2KEY", "TR31_C0_CARD_VERIFICATION_KEY",
                "Generate", "Verify");

        // The model states that the expiry is four digits, and the card verification value
        // is computed over the expiry the request carries.
        AwsException refused = assertThrows(AwsException.class,
                () -> cardValidation.generate(PaymentCryptographyTestFixtures.request(Map.of(
                        "KeyIdentifier", cvk.getArn(),
                        "PrimaryAccountNumber", PAN,
                        "GenerationAttributes", cardVerificationValue1("30", "201"))), "us-east-1"));

        assertEquals("ValidationException", refused.getErrorCode());
        assertTrue(refused.getMessage().contains("CardExpiryDate"), refused.getMessage());
    }

    @Test
    void anAccountNumberThatIsNotDigitsIsRefused() {
        PaymentCryptographyKey cvk = PaymentCryptographyTestFixtures.createKey(service, "TDES_2KEY", "TR31_C0_CARD_VERIFICATION_KEY",
                "Generate", "Verify");

        // The model states that the account number is digits, so a formatted one is refused
        // rather than read as the digits it carries.
        AwsException refused = assertThrows(AwsException.class,
                () -> cardValidation.generate(PaymentCryptographyTestFixtures.request(Map.of(
                        "KeyIdentifier", cvk.getArn(),
                        "PrimaryAccountNumber", "5081-4700-8263-9564",
                        "GenerationAttributes", cardVerificationValue1(EXPIRY, "201"))), "us-east-1"));

        assertEquals("ValidationException", refused.getErrorCode());
        assertTrue(refused.getMessage().contains("PrimaryAccountNumber"), refused.getMessage());
    }

    @Test
    void theDynamicCardVerificationCodeIsRefusedRatherThanApproximated() {
        PaymentCryptographyKey key = PaymentCryptographyTestFixtures.createKey(service, "TDES_2KEY", "TR31_E4_EMV_MKEY_DYNAMIC_NUMBERS",
                "Generate", "Verify", "DeriveKey");

        // The dynamic code is a Mastercard proprietary construction, and there is nothing
        // published to check a derivation against.
        AwsException refused = assertThrows(AwsException.class,
                () -> cardValidation.generate(PaymentCryptographyTestFixtures.request(Map.of(
                        "KeyIdentifier", key.getArn(),
                        "PrimaryAccountNumber", PAN,
                        "GenerationAttributes", dynamicCardVerificationCode())), "us-east-1"));

        assertEquals("ValidationException", refused.getErrorCode());
        assertTrue(refused.getMessage().contains("not implemented"), refused.getMessage());
    }

    @Test
    void theValidationDataLengthIsTheOneTheModelStates() {
        PaymentCryptographyKey cvk = PaymentCryptographyTestFixtures.createKey(service, "TDES_2KEY", "TR31_C0_CARD_VERIFICATION_KEY",
                "Generate", "Verify");

        // The model lets the length be three to five, and the value carries that many
        // digits of the decimalised block.
        cardValidation.generate(PaymentCryptographyTestFixtures.request(Map.of(
                "KeyIdentifier", cvk.getArn(),
                "PrimaryAccountNumber", PAN,
                "ValidationDataLength", 3,
                "GenerationAttributes", cardVerificationValue1(EXPIRY, "201"))), "us-east-1");

        Map<String, Object> longer = cardValidation.generate(PaymentCryptographyTestFixtures.request(Map.of(
                "KeyIdentifier", cvk.getArn(),
                "PrimaryAccountNumber", PAN,
                "ValidationDataLength", 5,
                "GenerationAttributes", cardVerificationValue1(EXPIRY, "201"))), "us-east-1");

        assertEquals(5, ((String) longer.get("ValidationData")).length());
    }

    @Test
    void aLengthTheModelDoesNotStateIsRefused() {
        PaymentCryptographyKey cvk = PaymentCryptographyTestFixtures.createKey(service, "TDES_2KEY", "TR31_C0_CARD_VERIFICATION_KEY",
                "Generate", "Verify");

        // The model states the length as three to five, so the request is refused against the
        // model's own constraint rather than by a check of our own.
        AwsException refused = assertThrows(AwsException.class,
                () -> cardValidation.generate(PaymentCryptographyTestFixtures.request(Map.of(
                        "KeyIdentifier", cvk.getArn(),
                        "PrimaryAccountNumber", PAN,
                        "ValidationDataLength", 2,
                        "GenerationAttributes", cardVerificationValue1(EXPIRY, "201"))), "us-east-1"));

        assertEquals("ValidationException", refused.getErrorCode());
        assertTrue(refused.getMessage().contains("ValidationDataLength"), refused.getMessage());
    }

    @Test
    void aValueTheModelDoesNotStateIsRefused() {
        PaymentCryptographyKey cvk = PaymentCryptographyTestFixtures.createKey(service, "TDES_2KEY", "TR31_C0_CARD_VERIFICATION_KEY",
                "Generate", "Verify");

        // The model states the value as three to five digits, so a shorter one is refused
        // against the model's own constraint.
        AwsException refused = assertThrows(AwsException.class,
                () -> cardValidation.verify(PaymentCryptographyTestFixtures.request(Map.of(
                        "KeyIdentifier", cvk.getArn(),
                        "PrimaryAccountNumber", PAN,
                        "ValidationData", "99",
                        "VerificationAttributes", cardVerificationValue1(EXPIRY, "201"))), "us-east-1"));

        assertEquals("ValidationException", refused.getErrorCode());
        assertTrue(refused.getMessage().contains("ValidationData"), refused.getMessage());
    }

    @Test
    void aDisabledKeyIsRefused() {
        PaymentCryptographyKey key = PaymentCryptographyTestFixtures.createDisabledKey(service,
                "TDES_2KEY", "TR31_C0_CARD_VERIFICATION_KEY", "Generate", "Verify");

        AwsException refused = assertThrows(AwsException.class,
                () -> cardValidation.generate(PaymentCryptographyTestFixtures.request(Map.of(
                        "KeyIdentifier", key.getArn(),
                        "PrimaryAccountNumber", PAN,
                        "GenerationAttributes", cardVerificationValue1(EXPIRY, "201"))), "us-east-1"));

        assertEquals("ValidationException", refused.getErrorCode());
    }

    /** The attributes of the dynamic card verification code, which is computed over the track. */
    private static JsonNode dynamicCardVerificationCode() {
        return dynamicCardVerificationCode("01", "5081470082639564300303D25061010000000000");
    }

    /** The attributes of the dynamic card verification code, over the given track data. */
    private static JsonNode dynamicCardVerificationCode(String trackData) {
        return dynamicCardVerificationCode("01", trackData);
    }

    /**
     * The attributes of the dynamic card verification code of a card with the given
     * sequence number, over the given track data.
     */
    private static JsonNode dynamicCardVerificationCode(String psn, String trackData) {
        ObjectMapper mapper = new ObjectMapper();
        ObjectNode value = mapper.createObjectNode();
        value.put("UnpredictableNumber", "12345678");
        value.put("PanSequenceNumber", psn);
        value.put("ApplicationTransactionCounter", "0001");
        value.put("TrackData", trackData);
        ObjectNode outer = mapper.createObjectNode();
        outer.set("DynamicCardVerificationCode", value);
        return outer;
    }
}
