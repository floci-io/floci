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

import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
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

    private PaymentCryptographyKey createKey(String algorithm, String usage, String... modes) {
        Map<String, Object> attributes = new LinkedHashMap<>();
        attributes.put("KeyAlgorithm", algorithm);
        attributes.put("KeyClass", "SYMMETRIC_KEY");
        attributes.put("KeyUsage", usage);
        Map<String, Boolean> declared = new LinkedHashMap<>();
        for (String mode : modes) {
            declared.put(mode, Boolean.TRUE);
        }
        return service.createKey("us-east-1", attributes, declared, true, true, Map.of());
    }

    private static JsonNode request(Map<String, Object> members) {
        ObjectMapper mapper = new ObjectMapper();
        ObjectNode node = mapper.createObjectNode();
        members.forEach((name, value) -> {
            if (value instanceof JsonNode json) {
                node.set(name, json);
            } else {
                node.put(name, String.valueOf(value));
            }
        });
        return node;
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
        assertEquals("131", CvvCrypto.computeCvv(PAN, EXPIRY, "201", CVK));
        assertEquals("886", CvvCrypto.computeCvv(PAN, EXPIRY, "000", CVK));
        assertEquals("660", CvvCrypto.computeCvv(PAN, EXPIRY, "999", CVK));
    }

    @Test
    void aGeneratedValueVerifies() {
        PaymentCryptographyKey cvk = createKey("TDES_2KEY", "TR31_C0_CARD_VERIFICATION_KEY",
                "Generate", "Verify");

        Map<String, Object> generated = cardValidation.generate(request(Map.of(
                "KeyIdentifier", cvk.getArn(),
                "PrimaryAccountNumber", PAN,
                "GenerationAttributes", cardVerificationValue1(EXPIRY, "201"))), "us-east-1");

        String value = (String) generated.get("ValidationData");
        assertEquals(3, value.length());
        assertEquals(cvk.getArn(), generated.get("KeyArn"));

        Map<String, Object> verified = cardValidation.verify(request(Map.of(
                "KeyIdentifier", cvk.getArn(),
                "PrimaryAccountNumber", PAN,
                "ValidationData", value,
                "VerificationAttributes", cardVerificationValue1(EXPIRY, "201"))), "us-east-1");

        assertEquals(cvk.getArn(), verified.get("KeyArn"));
    }

    @Test
    void aWrongValueIsRefused() {
        PaymentCryptographyKey cvk = createKey("TDES_2KEY", "TR31_C0_CARD_VERIFICATION_KEY",
                "Generate", "Verify");

        AwsException refused = assertThrows(AwsException.class, () -> cardValidation.verify(
                request(Map.of(
                        "KeyIdentifier", cvk.getArn(),
                        "PrimaryAccountNumber", PAN,
                        "ValidationData", "999",
                        "VerificationAttributes", cardVerificationValue1(EXPIRY, "201"))),
                "us-east-1"));

        assertEquals("VerificationFailedException", refused.getErrorCode());
    }

    @Test
    void aKeyOfTheWrongUsageIsRefused() {
        PaymentCryptographyKey wrong = createKey("TDES_2KEY", "TR31_D0_SYMMETRIC_DATA_ENCRYPTION_KEY",
                "Generate", "Verify");

        AwsException refused = assertThrows(AwsException.class,
                () -> cardValidation.generate(request(Map.of(
                        "KeyIdentifier", wrong.getArn(),
                        "PrimaryAccountNumber", PAN,
                        "GenerationAttributes", cardVerificationValue1(EXPIRY, "201"))), "us-east-1"));

        assertEquals("ValidationException", refused.getErrorCode());
        assertTrue(refused.getMessage().contains("TR31_C0_CARD_VERIFICATION_KEY"),
                refused.getMessage());
    }

    @Test
    void aKeyThatMayNotGenerateIsRefused() {
        PaymentCryptographyKey cvk = createKey("TDES_2KEY", "TR31_C0_CARD_VERIFICATION_KEY",
                "Verify");

        AwsException refused = assertThrows(AwsException.class,
                () -> cardValidation.generate(request(Map.of(
                        "KeyIdentifier", cvk.getArn(),
                        "PrimaryAccountNumber", PAN,
                        "GenerationAttributes", cardVerificationValue1(EXPIRY, "201"))), "us-east-1"));

        assertEquals("InvalidKeyUsageException", refused.getErrorCode());
    }

    @Test
    void anExpiryThatIsNotFourDigitsIsRefused() {
        PaymentCryptographyKey cvk = createKey("TDES_2KEY", "TR31_C0_CARD_VERIFICATION_KEY",
                "Generate", "Verify");

        // The model states that the expiry is four digits, and the card verification value
        // is computed over the expiry the request carries.
        AwsException refused = assertThrows(AwsException.class,
                () -> cardValidation.generate(request(Map.of(
                        "KeyIdentifier", cvk.getArn(),
                        "PrimaryAccountNumber", PAN,
                        "GenerationAttributes", cardVerificationValue1("30", "201"))), "us-east-1"));

        assertEquals("ValidationException", refused.getErrorCode());
        assertTrue(refused.getMessage().contains("CardExpiryDate"), refused.getMessage());
    }

    @Test
    void anAccountNumberThatIsNotDigitsIsRefused() {
        PaymentCryptographyKey cvk = createKey("TDES_2KEY", "TR31_C0_CARD_VERIFICATION_KEY",
                "Generate", "Verify");

        // The model states that the account number is digits, so a formatted one is refused
        // rather than read as the digits it carries.
        AwsException refused = assertThrows(AwsException.class,
                () -> cardValidation.generate(request(Map.of(
                        "KeyIdentifier", cvk.getArn(),
                        "PrimaryAccountNumber", "5081-4700-8263-9564",
                        "GenerationAttributes", cardVerificationValue1(EXPIRY, "201"))), "us-east-1"));

        assertEquals("ValidationException", refused.getErrorCode());
        assertTrue(refused.getMessage().contains("PrimaryAccountNumber"), refused.getMessage());
    }
}
