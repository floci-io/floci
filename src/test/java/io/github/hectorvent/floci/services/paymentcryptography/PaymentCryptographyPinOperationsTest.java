package io.github.hectorvent.floci.services.paymentcryptography;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.core.common.RegionResolver;
import io.github.hectorvent.floci.core.storage.InMemoryStorage;
import io.github.hectorvent.floci.services.paymentcryptography.crypto.Hex;
import io.github.hectorvent.floci.services.paymentcryptography.crypto.PinCrypto;
import io.github.hectorvent.floci.services.paymentcryptography.model.PaymentCryptographyKey;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The PIN operations, over the Visa PVV path.
 *
 * <p>The values in the first two tests are the ones the emulator computes, and the emulator
 * checks them against an independent implementation: the account field of a PAN,
 * the ISO 0 block of a PIN, the ISO 4
 * PIN field and the PVV of a PIN under a PVK.
 */
class PaymentCryptographyPinOperationsTest {

    private static final String PAN = "5081470082639564";
    private static final String PIN = "1234";
    private static final String PVK = "0123456789ABCDEFFEDCBA9876543210";

    private PaymentCryptographyService service;
    private PaymentCryptographyPinOperations pin;
    private final ObjectMapper mapper = new ObjectMapper();

    @BeforeEach
    void setUp() {
        service = new PaymentCryptographyService(
                new InMemoryStorage<>(),
                new InMemoryStorage<>(),
                new RegionResolver("us-east-1", "000000000000"));
        pin = new PaymentCryptographyPinOperations(service);
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
        ObjectNode node = new ObjectMapper().createObjectNode();
        members.forEach((name, value) -> {
            if (value instanceof JsonNode json) {
                node.set(name, json);
            } else if (value instanceof Integer number) {
                node.put(name, number);
            } else {
                node.put(name, String.valueOf(value));
            }
        });
        return node;
    }

    /**
     * A structure with one member.
     */
    private static JsonNode nested(String member, String... pairs) {
        ObjectMapper mapper = new ObjectMapper();
        ObjectNode node = mapper.createObjectNode();
        for (int i = 0; i < pairs.length; i += 2) {
            node.put(pairs[i], pairs[i + 1]);
        }
        ObjectNode outer = mapper.createObjectNode();
        outer.set(member, node);
        return outer;
    }

    /** The Visa attributes of a generation: the index of the verification key. */
    private static JsonNode visaPin(int pvki) {
        ObjectMapper mapper = new ObjectMapper();
        ObjectNode visa = mapper.createObjectNode();
        visa.put("PinVerificationKeyIndex", pvki);
        ObjectNode outer = mapper.createObjectNode();
        outer.set("VisaPin", visa);
        return outer;
    }

    /** The Visa attributes of a verification: the index and the value itself. */
    private static JsonNode visaPin(int pvki, String value) {
        ObjectMapper mapper = new ObjectMapper();
        ObjectNode visa = mapper.createObjectNode();
        visa.put("PinVerificationKeyIndex", pvki);
        visa.put("VerificationValue", value);
        ObjectNode outer = mapper.createObjectNode();
        outer.set("VisaPin", visa);
        return outer;
    }

    @Test
    void theAccountFieldAndTheBlocksAreTheOnesTheStandardGives() {
        assertEquals("147008263956", PinCrypto.account12(PAN));
        assertEquals("0412208FF7D9C6A9", PinCrypto.iso0PinBlock(PIN, PAN));
        assertEquals("441234AAAAAAAAAA0102030405060708",
                Hex.encodeUpper(PinCrypto.iso4PinField(PIN,
                        new byte[] {1, 2, 3, 4, 5, 6, 7, 8})));
        assertEquals("45081470082639564000000000000000",
                Hex.encodeUpper(PinCrypto.iso4PanField(PAN)));
    }

    @Test
    void theVerificationValueIsTheOneJposGives() {
        assertEquals("0463", PinCrypto.computePvv(PAN, 1, PIN, PVK));
    }

    @Test
    void aGeneratedBlockVerifiesAgainstTheValueThatGoesWithIt() {
        PaymentCryptographyKey pek = createKey("TDES_2KEY", "TR31_P0_PIN_ENCRYPTION_KEY",
                "Encrypt", "Decrypt");
        PaymentCryptographyKey pvk = createKey("TDES_2KEY", "TR31_P1_PIN_GENERATION_KEY",
                "Generate", "Verify");

        Map<String, Object> generated = pin.generatePinData(request(Map.of(
                "EncryptionKeyIdentifier", pek.getArn(),
                "GenerationKeyIdentifier", pvk.getArn(),
                "PinBlockFormat", "ISO_FORMAT_0",
                "PrimaryAccountNumber", PAN,
                "GenerationAttributes", visaPin(1))),
                "us-east-1");

        String block = (String) generated.get("EncryptedPinBlock");
        assertEquals(16, block.length());
        @SuppressWarnings("unchecked")
        Map<String, Object> pinData = (Map<String, Object>) generated.get("PinData");
        String pvv = (String) pinData.get("VerificationValue");
        assertNotNull(pvv);

        Map<String, Object> verified = pin.verifyPinData(request(Map.of(
                "EncryptionKeyIdentifier", pek.getArn(),
                "VerificationKeyIdentifier", pvk.getArn(),
                "PinBlockFormat", "ISO_FORMAT_0",
                "PrimaryAccountNumber", PAN,
                "EncryptedPinBlock", block,
                "VerificationAttributes", visaPin(1, pvv))),
                "us-east-1");

        assertEquals(pek.getArn(), verified.get("EncryptionKeyArn"));
        assertEquals(pvk.getArn(), verified.get("VerificationKeyArn"));
    }

    @Test
    void aBlockWithTheWrongVerificationValueIsRefused() {
        PaymentCryptographyKey pek = createKey("TDES_2KEY", "TR31_P0_PIN_ENCRYPTION_KEY",
                "Encrypt", "Decrypt");
        PaymentCryptographyKey pvk = createKey("TDES_2KEY", "TR31_P1_PIN_GENERATION_KEY",
                "Generate", "Verify");

        Map<String, Object> generated = pin.generatePinData(request(Map.of(
                "EncryptionKeyIdentifier", pek.getArn(),
                "GenerationKeyIdentifier", pvk.getArn(),
                "PinBlockFormat", "ISO_FORMAT_0",
                "PrimaryAccountNumber", PAN,
                "GenerationAttributes", visaPin(1))),
                "us-east-1");

        AwsException refused = assertThrows(AwsException.class, () -> pin.verifyPinData(request(Map.of(
                "EncryptionKeyIdentifier", pek.getArn(),
                "VerificationKeyIdentifier", pvk.getArn(),
                "PinBlockFormat", "ISO_FORMAT_0",
                "PrimaryAccountNumber", PAN,
                "EncryptedPinBlock", generated.get("EncryptedPinBlock"),
                "VerificationAttributes", visaPin(1, "9999"))),
                "us-east-1"));

        assertEquals("VerificationFailedException", refused.getErrorCode());
    }

    @Test
    void aBlockTranslatesFromFormatFourToFormatZero() {
        PaymentCryptographyKey aesPek = createKey("AES_128", "TR31_P0_PIN_ENCRYPTION_KEY",
                "Encrypt", "Decrypt");
        PaymentCryptographyKey tdesPek = createKey("TDES_2KEY", "TR31_P0_PIN_ENCRYPTION_KEY",
                "Encrypt", "Decrypt");
        PaymentCryptographyKey pvk = createKey("TDES_2KEY", "TR31_P1_PIN_GENERATION_KEY",
                "Generate", "Verify");

        Map<String, Object> generated = pin.generatePinData(request(Map.of(
                "EncryptionKeyIdentifier", aesPek.getArn(),
                "GenerationKeyIdentifier", pvk.getArn(),
                "PinBlockFormat", "ISO_FORMAT_4",
                "PrimaryAccountNumber", PAN,
                "GenerationAttributes", visaPin(1))),
                "us-east-1");
        String block4 = (String) generated.get("EncryptedPinBlock");
        assertEquals(32, block4.length());
        @SuppressWarnings("unchecked")
        Map<String, Object> pinData = (Map<String, Object>) generated.get("PinData");
        String pvv = (String) pinData.get("VerificationValue");

        Map<String, Object> translated = pin.translatePinData(request(Map.of(
                "EncryptedPinBlock", block4,
                "IncomingKeyIdentifier", aesPek.getArn(),
                "OutgoingKeyIdentifier", tdesPek.getArn(),
                "IncomingTranslationAttributes", nested("IsoFormat4", "PrimaryAccountNumber", PAN),
                "OutgoingTranslationAttributes", nested("IsoFormat0", "PrimaryAccountNumber", PAN))),
                "us-east-1");
        String block0 = (String) translated.get("PinBlock");
        assertEquals(16, block0.length());

        // The PIN survived the translation: the translated block still verifies against the
        // verification value the format 4 block carried.
        Map<String, Object> verified = pin.verifyPinData(request(Map.of(
                "EncryptionKeyIdentifier", tdesPek.getArn(),
                "VerificationKeyIdentifier", pvk.getArn(),
                "PinBlockFormat", "ISO_FORMAT_0",
                "PrimaryAccountNumber", PAN,
                "EncryptedPinBlock", block0,
                "VerificationAttributes", visaPin(1, pvv))),
                "us-east-1");
        assertEquals(tdesPek.getArn(), verified.get("EncryptionKeyArn"));
    }

    @Test
    void aFormatThatNeedsAnAesKeyRefusesATdesKey() {
        PaymentCryptographyKey tdesPek = createKey("TDES_2KEY", "TR31_P0_PIN_ENCRYPTION_KEY",
                "Encrypt", "Decrypt");
        PaymentCryptographyKey pvk = createKey("TDES_2KEY", "TR31_P1_PIN_GENERATION_KEY",
                "Generate", "Verify");

        AwsException refused = assertThrows(AwsException.class,
                () -> pin.generatePinData(request(Map.of(
                        "EncryptionKeyIdentifier", tdesPek.getArn(),
                        "GenerationKeyIdentifier", pvk.getArn(),
                        "PinBlockFormat", "ISO_FORMAT_4",
                        "PrimaryAccountNumber", PAN,
                        "GenerationAttributes", visaPin(1))),
                        "us-east-1"));

        assertEquals("ValidationException", refused.getErrorCode());
        assertTrue(refused.getMessage().contains("AES"), refused.getMessage());
    }

    @Test
    void aKeyThatMayNotEncryptIsRefused() {
        PaymentCryptographyKey pek = createKey("TDES_2KEY", "TR31_P0_PIN_ENCRYPTION_KEY", "Decrypt");
        PaymentCryptographyKey pvk = createKey("TDES_2KEY", "TR31_P1_PIN_GENERATION_KEY",
                "Generate", "Verify");

        AwsException refused = assertThrows(AwsException.class,
                () -> pin.generatePinData(request(Map.of(
                        "EncryptionKeyIdentifier", pek.getArn(),
                        "GenerationKeyIdentifier", pvk.getArn(),
                        "PinBlockFormat", "ISO_FORMAT_0",
                        "PrimaryAccountNumber", PAN,
                        "GenerationAttributes", visaPin(1))),
                        "us-east-1"));

        assertEquals("InvalidKeyUsageException", refused.getErrorCode());
    }
}
