package io.floci.aws.services.paymentcryptography;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.floci.aws.core.common.AwsException;
import io.floci.aws.core.common.RegionResolver;
import io.floci.aws.core.storage.InMemoryStorage;
import io.floci.aws.services.paymentcryptography.crypto.DukptCrypto;
import io.floci.aws.services.paymentcryptography.crypto.PinBlockFormat;
import io.floci.aws.services.paymentcryptography.crypto.PinCrypto;
import io.floci.aws.services.paymentcryptography.model.PaymentCryptographyKey;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.security.SecureRandom;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The PIN operations: the PIN blocks of the ISO 9564-1 formats, the Visa PIN verification
 * value, and the rules a key has to satisfy before it protects one.
 *
 * <p>The block and the verification value are checked against the values the standards
 * give, and the rules are checked by refusing: a key of another usage, a key of another
 * family, a key that may not do what the operation needs, and a verification value that is
 * not the one the issuer holds.
 */
class PaymentCryptographyPinOperationsTest {

    /** The account number the PIN belongs to. */
    private static final String PAN = "5081470082639564";

    /** The PIN the account holds. */
    private static final String PIN = "1234";

    /** The Visa PIN verification key the PVV is computed under. */
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

    /** A key with the algorithm, the usage and the modes the test needs. */
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

    /** The PIN encryption key of the tests: a Triple DES one that may encrypt and decrypt. */
    private PaymentCryptographyKey pinEncryptionKey() {
        return createKey("TDES_2KEY", "TR31_P0_PIN_ENCRYPTION_KEY", "Encrypt", "Decrypt");
    }

    /** The Visa PIN verification key of the tests. */
    private PaymentCryptographyKey visaVerificationKey() {
        return createKey("TDES_2KEY", "TR31_V2_VISA_PIN_VERIFICATION_KEY", "Generate", "Verify");
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

    private static JsonNode visaPin(int pvki) {
        ObjectMapper mapper = new ObjectMapper();
        ObjectNode visa = mapper.createObjectNode();
        visa.put("PinVerificationKeyIndex", pvki);
        ObjectNode outer = mapper.createObjectNode();
        outer.set("VisaPin", visa);
        return outer;
    }

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
                HexFormat.of().withUpperCase().formatHex(PinCrypto.iso4PinField(PIN,
                        new byte[] {1, 2, 3, 4, 5, 6, 7, 8})));
        assertEquals("45081470082639564000000000000000",
                HexFormat.of().withUpperCase().formatHex(PinCrypto.iso4PanField(PAN)));
    }

    @Test
    void aPanOfTwelveDigitsIsAPanAndNotAnAccountField() {
        // The API takes a primary account number, which carries its own check digit, so a
        // twelve-digit one leaves eleven digits and the account field is padded with a
        // zero on the left.
        assertEquals("014700826395", PinCrypto.account12("147008263956"));
        // A longer PAN gives the twelve right-most digits without its check digit.
        assertEquals("147008263956", PinCrypto.account12(PAN));
        // The verification value follows the account field, so the twelve-digit PAN does
        // not give the one its own account field would.
        assertNotEquals(PinCrypto.computePvv(PAN, 1, PIN, PVK),
                PinCrypto.computePvv("147008263956", 1, PIN, PVK));
    }

    @Test
    void theVerificationValueIsTheOneTheStandardGives() {
        assertEquals("0463", PinCrypto.computePvv(PAN, 1, PIN, PVK));
    }

    private static JsonNode visaPinVerificationValue(String block) {
        ObjectMapper mapper = new ObjectMapper();
        ObjectNode value = mapper.createObjectNode();
        value.put("EncryptedPinBlock", block);
        value.put("PinVerificationKeyIndex", 1);
        ObjectNode variant = mapper.createObjectNode();
        variant.set("VisaPinVerificationValue", value);
        return variant;
    }

    @Test
    void aGeneratedBlockVerifiesAgainstTheValueThatGoesWithIt() {
        PaymentCryptographyKey pek = pinEncryptionKey();
        PaymentCryptographyKey pvk = visaVerificationKey();
        Map<String, Object> generated = pin.generatePinData(request(Map.of(
                "EncryptionKeyIdentifier", pek.getArn(),
                "GenerationKeyIdentifier", pvk.getArn(),
                "PinBlockFormat", "ISO_FORMAT_0",
                "PrimaryAccountNumber", PAN,
                "GenerationAttributes", visaPin(1))),
                "us-east-1");
        String block = (String) generated.get("EncryptedPinBlock");
        assertEquals(16, block.length());
        assertEquals(pek.getArn(), generated.get("EncryptionKeyArn"));
        assertEquals(pvk.getArn(), generated.get("GenerationKeyArn"));

        @SuppressWarnings("unchecked")
        Map<String, Object> pinData = (Map<String, Object>) generated.get("PinData");
        String pvv = (String) pinData.get("VerificationValue");
        assertNotNull(pvv);
        // The model's PinData is a union of the PIN offset and the verification value, and a
        // union carries one of them: this is the verification value and nothing else.
        assertEquals(Map.of("VerificationValue", pvv), pinData);

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
        PaymentCryptographyKey pek = pinEncryptionKey();
        PaymentCryptographyKey pvk = visaVerificationKey();
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
    void aPinLongerThanFourDigitsDoesNotVerify() {
        // The Visa verification value is computed over four PIN digits, so a block that
        // carries a longer PIN is not one the value can be compared with. A block for
        // "12345" must not verify against the value over "1234".
        PaymentCryptographyKey pek = pinEncryptionKey();
        PaymentCryptographyKey pvk = visaVerificationKey();
        byte[] key = HexFormat.of().parseHex(pek.getKeyMaterialHex());
        String block = PinCrypto.encipherPinBlock(PinBlockFormat.ISO_FORMAT_0, "12345", PAN, key,
                new SecureRandom());
        // The verification value is over the four-digit PIN the issuer holds.
        String pvv = PinCrypto.computePvv(PAN, 1, PIN, pvk.getKeyMaterialHex());
        AwsException refused = assertThrows(AwsException.class, () -> pin.verifyPinData(request(Map.of(
                "EncryptionKeyIdentifier", pek.getArn(),
                "VerificationKeyIdentifier", pvk.getArn(),
                "PinBlockFormat", "ISO_FORMAT_0",
                "PrimaryAccountNumber", PAN,
                "EncryptedPinBlock", block,
                "VerificationAttributes", visaPin(1, pvv))),
                "us-east-1"));
        assertEquals("VerificationFailedException", refused.getErrorCode());
    }

    @Test
    void aDeclaredPinLengthThatIsNotFourIsRefused() {
        // The Visa verification value is over four PIN digits, so a request that declares
        // another length names something this path cannot verify.
        PaymentCryptographyKey pek = pinEncryptionKey();
        PaymentCryptographyKey pvk = visaVerificationKey();
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
                "PinDataLength", 5,
                "EncryptedPinBlock", generated.get("EncryptedPinBlock"),
                "VerificationAttributes", visaPin(1, "1234"))),
                "us-east-1"));
        assertEquals("ValidationException", refused.getErrorCode());
    }

    @Test
    void aGivenBlockWithALongerPinIsRefused() {
        // The Visa verification value is a four digit number, so a block that carries a
        // longer PIN has no value to be computed for it. The request is refused, rather
        // than the computation failing inside the request.
        PaymentCryptographyKey pek = pinEncryptionKey();
        PaymentCryptographyKey pvk = visaVerificationKey();
        byte[] key = HexFormat.of().parseHex(pek.getKeyMaterialHex());
        String block = PinCrypto.encipherPinBlock(PinBlockFormat.ISO_FORMAT_0, "12345", PAN, key,
                new SecureRandom());
        AwsException refused = assertThrows(AwsException.class,
                () -> pin.generatePinData(request(Map.of(
                        "EncryptionKeyIdentifier", pek.getArn(),
                        "GenerationKeyIdentifier", pvk.getArn(),
                        "PinBlockFormat", "ISO_FORMAT_0",
                        "PrimaryAccountNumber", PAN,
                        "GenerationAttributes", visaPinVerificationValue(block))),
                        "us-east-1"));
        assertEquals("ValidationException", refused.getErrorCode());
    }

    @Test
    void aBlockTranslatesFromFormatFourToFormatZero() {
        PaymentCryptographyKey aesPek = createKey("AES_128", "TR31_P0_PIN_ENCRYPTION_KEY",
                "Encrypt", "Decrypt");
        PaymentCryptographyKey tdesPek = pinEncryptionKey();
        PaymentCryptographyKey pvk = visaVerificationKey();
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
        // The answer names the outgoing key, which is the key the block is enciphered under.
        assertEquals(tdesPek.getArn(), translated.get("KeyArn"));
        assertEquals(tdesPek.getKeyCheckValue(), translated.get("KeyCheckValue"));

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
        PaymentCryptographyKey tdesPek = pinEncryptionKey();
        PaymentCryptographyKey pvk = visaVerificationKey();
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
    void aVerificationKeyThatIsNotTdesIsRefused() {
        // The PVV is defined over Triple DES, so an AES verification key has
        // no such computation. It is refused with the reason rather than
        // failing inside the cipher.
        PaymentCryptographyKey pek = pinEncryptionKey();
        PaymentCryptographyKey pvk = createKey("AES_256", "TR31_V2_VISA_PIN_VERIFICATION_KEY",
                "Generate", "Verify");
        AwsException refused = assertThrows(AwsException.class,
                () -> pin.generatePinData(request(Map.of(
                        "EncryptionKeyIdentifier", pek.getArn(),
                        "GenerationKeyIdentifier", pvk.getArn(),
                        "PinBlockFormat", "ISO_FORMAT_0",
                        "PrimaryAccountNumber", PAN,
                        "GenerationAttributes", visaPin(1))),
                        "us-east-1"));
        assertEquals("ValidationException", refused.getErrorCode());
    }

    @Test
    void aVerificationKeyOfAnotherUsageIsRefused() {
        // The Visa PIN verification value is computed under a Visa PIN verification key, and
        // the usage is what says which key that is.
        PaymentCryptographyKey pek = pinEncryptionKey();
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
        assertEquals("ValidationException", refused.getErrorCode());
        assertTrue(refused.getMessage().contains("TR31_V2_VISA_PIN_VERIFICATION_KEY"),
                refused.getMessage());
    }

    @Test
    void aKeyThatMayNotEncryptIsRefused() {
        PaymentCryptographyKey pek = createKey("TDES_2KEY", "TR31_P0_PIN_ENCRYPTION_KEY", "Decrypt");
        PaymentCryptographyKey pvk = visaVerificationKey();
        AwsException refused = assertThrows(AwsException.class,
                () -> pin.generatePinData(request(Map.of(
                        "EncryptionKeyIdentifier", pek.getArn(),
                        "GenerationKeyIdentifier", pvk.getArn(),
                        "PinBlockFormat", "ISO_FORMAT_0",
                        "PrimaryAccountNumber", PAN,
                        "GenerationAttributes", visaPin(1))),
                        "us-east-1"));
        assertEquals("ValidationException", refused.getErrorCode());
    }

    @Test
    void aVerificationKeyThatMayNotVerifyIsRefused() {
        PaymentCryptographyKey pek = pinEncryptionKey();
        PaymentCryptographyKey pvk = createKey("TDES_2KEY", "TR31_V2_VISA_PIN_VERIFICATION_KEY",
                "Generate");
        AwsException refused = assertThrows(AwsException.class,
                () -> pin.verifyPinData(request(Map.of(
                        "EncryptionKeyIdentifier", pek.getArn(),
                        "VerificationKeyIdentifier", pvk.getArn(),
                        "PinBlockFormat", "ISO_FORMAT_0",
                        "PrimaryAccountNumber", PAN,
                        "EncryptedPinBlock", "0000000000000000",
                        "VerificationAttributes", visaPin(1, "1234"))),
                        "us-east-1"));
        assertEquals("ValidationException", refused.getErrorCode());
    }

    @Test
    void theTranslationOfAnAs2805BlockIsRefused() {
        // The byte layout of the AS2805 cryptogram is in the standard and is not published,
        // so a cryptogram the service produced would not open here.
        AwsException refusal = assertThrows(AwsException.class, () -> pin.translatePinData(request(Map.of(
                "IncomingKeyIdentifier", "arn:aws:payment-cryptography:us-east-1:000000000000:key/x",
                "OutgoingKeyIdentifier", "arn:aws:payment-cryptography:us-east-1:000000000000:key/y",
                "IncomingAs2805Attributes", Map.of("WrappingKeyIdentifier", "x"),
                "EncryptedPinBlock", "0011223344556677")), "us-east-1"));
        assertEquals("ValidationException", refusal.getErrorCode());
    }

    @Test
    void aVerificationKeyIndexThatIsOutOfRangeIsRefused() {
        // The Visa PVV names one of ten keys, and the API's index is between zero and six.
        PaymentCryptographyKey pek = pinEncryptionKey();
        PaymentCryptographyKey pvk = visaVerificationKey();
        AwsException refused = assertThrows(AwsException.class,
                () -> pin.generatePinData(request(Map.of(
                        "EncryptionKeyIdentifier", pek.getArn(),
                        "GenerationKeyIdentifier", pvk.getArn(),
                        "PinBlockFormat", "ISO_FORMAT_0",
                        "PrimaryAccountNumber", PAN,
                        "GenerationAttributes", visaPin(7))),
                        "us-east-1"));
        assertEquals("ValidationException", refused.getErrorCode());
    }

    @Test
    void aFormatThatIsNotMadeIsRefused() {
        PaymentCryptographyKey pek = pinEncryptionKey();
        PaymentCryptographyKey pvk = visaVerificationKey();
        AwsException refused = assertThrows(AwsException.class,
                () -> pin.generatePinData(request(Map.of(
                        "EncryptionKeyIdentifier", pek.getArn(),
                        "GenerationKeyIdentifier", pvk.getArn(),
                        "PinBlockFormat", "ISO_FORMAT_1",
                        "PrimaryAccountNumber", PAN,
                        "GenerationAttributes", visaPin(1))),
                        "us-east-1"));
        assertEquals("ValidationException", refused.getErrorCode());
        assertTrue(refused.getMessage().contains("ISO_FORMAT_0"), refused.getMessage());
    }

    @Test
    void theWrappedKeyFormIsRefused() {
        // A wrapped key is unwrapped with a key-encryption key, and the unwrapping is part of
        // the key exchange, which this service does not make yet.
        PaymentCryptographyKey pek = pinEncryptionKey();
        PaymentCryptographyKey pvk = visaVerificationKey();
        AwsException refused = assertThrows(AwsException.class,
                () -> pin.generatePinData(request(Map.of(
                        "EncryptionKeyIdentifier", pek.getArn(),
                        "GenerationKeyIdentifier", pvk.getArn(),
                        "PinBlockFormat", "ISO_FORMAT_0",
                        "PrimaryAccountNumber", PAN,
                        "GenerationAttributes", visaPin(1),
                        "EncryptionWrappedKey", Map.of("KeyIdentifier", "arn:aws:payment-cryptography"
                                + ":us-east-1:000000000000:key/x"))),
                        "us-east-1"));
        assertEquals("ValidationException", refused.getErrorCode());
    }

    @Test
    void theEmvPinChangeMacIsRefused() {
        AwsException refused = assertThrows(AwsException.class,
                () -> pin.generateMacEmvPinChange(request(Map.of(
                        "NewPinPekIdentifier", "arn:aws:payment-cryptography:us-east-1:000000000000:key/x",
                        "SecureMessagingIntegrityKeyIdentifier",
                        "arn:aws:payment-cryptography:us-east-1:000000000000:key/y",
                        "SecureMessagingConfidentialityKeyIdentifier",
                        "arn:aws:payment-cryptography:us-east-1:000000000000:key/z",
                        "NewEncryptedPinBlock", "0000000000000000",
                        "PinBlockFormat", "ISO_FORMAT_0",
                        "MessageData", "0000",
                        "DerivationMethodAttributes", Map.of("DerivationMethod", "V2"))),
                        "us-east-1"));
        assertEquals("ValidationException", refused.getErrorCode());
    }

    @Test
    void aKeyThatIsNotThereIsRefused() {
        AwsException refused = assertThrows(AwsException.class,
                () -> pin.verifyPinData(request(Map.of(
                        "EncryptionKeyIdentifier", "arn:aws:payment-cryptography:us-east-1:"
                                + "000000000000:key/1234abcd1234abcd1234abcd1234abcd",
                        "VerificationKeyIdentifier", "arn:aws:payment-cryptography:us-east-1:"
                                + "000000000000:key/1234abcd1234abcd1234abcd1234abcd",
                        "PinBlockFormat", "ISO_FORMAT_0",
                        "PrimaryAccountNumber", PAN,
                        "EncryptedPinBlock", "0000000000000000",
                        "VerificationAttributes", visaPin(1, "1234"))),
                        "us-east-1"));
        assertEquals("ResourceNotFoundException", refused.getErrorCode());
        assertEquals(404, refused.getHttpStatus());
    }

    @Test
    void aBlockThatIsNotReadAsAPinBlockIsRefused() {
        // The block is enciphered under one key and verified under another, so what it
        // deciphers to is not a PIN. The reference names that an invalid PIN block, and lists
        // the wrong key among its causes.
        PaymentCryptographyKey pek = pinEncryptionKey();
        PaymentCryptographyKey otherPek = pinEncryptionKey();
        PaymentCryptographyKey pvk = visaVerificationKey();
        Map<String, Object> generated = pin.generatePinData(request(Map.of(
                "EncryptionKeyIdentifier", pek.getArn(),
                "GenerationKeyIdentifier", pvk.getArn(),
                "PinBlockFormat", "ISO_FORMAT_0",
                "PrimaryAccountNumber", PAN,
                "GenerationAttributes", visaPin(1))),
                "us-east-1");
        AwsException refused = assertThrows(AwsException.class, () -> pin.verifyPinData(request(Map.of(
                "EncryptionKeyIdentifier", otherPek.getArn(),
                "VerificationKeyIdentifier", pvk.getArn(),
                "PinBlockFormat", "ISO_FORMAT_0",
                "PrimaryAccountNumber", PAN,
                "EncryptedPinBlock", generated.get("EncryptedPinBlock"),
                "VerificationAttributes", visaPin(1, "1234"))),
                "us-east-1"));
        assertEquals("ValidationException", refused.getErrorCode());
    }

    @Test
    void aPinThatDoesNotVerifyIsRefusedWithTheVerificationError() {
        // The block is read, and the PIN it carries does not match the verification value.
        // That is a verification that failed, not an invalid block.
        PaymentCryptographyKey pek = pinEncryptionKey();
        PaymentCryptographyKey pvk = visaVerificationKey();
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
        assertEquals("Pin block verification failed.", refused.getMessage());
    }

    @Test
    void aTranslationBetweenTwoAccountNumbersIsRefused() {
        // For PCI compliance the incoming and the outgoing account number are the same one,
        // and a PIN is not translated from one account number to another.
        PaymentCryptographyKey aesPek = createKey("AES_128", "TR31_P0_PIN_ENCRYPTION_KEY",
                "Encrypt", "Decrypt");
        PaymentCryptographyKey tdesPek = pinEncryptionKey();
        Map<String, Object> generated = pin.generatePinData(request(Map.of(
                "EncryptionKeyIdentifier", aesPek.getArn(),
                "GenerationKeyIdentifier", visaVerificationKey().getArn(),
                "PinBlockFormat", "ISO_FORMAT_4",
                "PrimaryAccountNumber", PAN,
                "GenerationAttributes", visaPin(1))),
                "us-east-1");
        AwsException refused = assertThrows(AwsException.class,
                () -> pin.translatePinData(request(Map.of(
                        "EncryptedPinBlock", generated.get("EncryptedPinBlock"),
                        "IncomingKeyIdentifier", aesPek.getArn(),
                        "OutgoingKeyIdentifier", tdesPek.getArn(),
                        "IncomingTranslationAttributes", nested("IsoFormat4", "PrimaryAccountNumber", PAN),
                        "OutgoingTranslationAttributes", nested("IsoFormat0",
                                "PrimaryAccountNumber", "5081470082639565"))),
                        "us-east-1"));
        assertEquals("ValidationException", refused.getErrorCode());
    }

    @Test
    void aTranslationWhoseAttributesCarryNoAccountNumberIsRefused() {
        // The model's translation attributes carry the account number, and a request that
        // leaves it out is refused rather than translated.
        PaymentCryptographyKey aesPek = createKey("AES_128", "TR31_P0_PIN_ENCRYPTION_KEY",
                "Encrypt", "Decrypt");
        PaymentCryptographyKey tdesPek = pinEncryptionKey();
        ObjectNode incoming = mapper.createObjectNode();
        incoming.putNull("IsoFormat4");
        AwsException refused = assertThrows(AwsException.class,
                () -> pin.translatePinData(request(Map.of(
                        "EncryptedPinBlock", "00112233445566778899AABBCCDDEEFF",
                        "IncomingKeyIdentifier", aesPek.getArn(),
                        "OutgoingKeyIdentifier", tdesPek.getArn(),
                        "IncomingTranslationAttributes", incoming,
                        "OutgoingTranslationAttributes", nested("IsoFormat0",
                                "PrimaryAccountNumber", PAN))),
                        "us-east-1"));
        assertEquals("ValidationException", refused.getErrorCode());
    }

    @Test
    void aDukptPinKeyIsDerivedFromTheBaseDerivationKey() {
        // A DUKPT PIN key is derived from the base derivation key, so that is what the
        // identifier names, and the key has to allow the derivation.
        PaymentCryptographyKey bdk = createKey("TDES_2KEY", "TR31_B0_BASE_DERIVATION_KEY",
                "DeriveKey");
        PaymentCryptographyKey pvk = visaVerificationKey();
        String ksn = "12345678901234567890";
        ObjectNode attributes = mapper.createObjectNode();
        attributes.put("KeySerialNumber", ksn);
        attributes.put("DukptDerivationType", "TDES_2KEY");
        attributes.put("DukptKeyVariant", "BIDIRECTIONAL");

        // The block is enciphered under the derived PIN key, which is what the service
        // derives from the base derivation key and the KSN.
        byte[] pinKey = DukptCrypto.pinKey(bdk.getKeyMaterialHex(), ksn);
        String block = PinCrypto.encipherPinBlock(PinBlockFormat.ISO_FORMAT_0, PIN, PAN, pinKey,
                new SecureRandom());

        Map<String, Object> verified = pin.verifyPinData(request(Map.of(
                "EncryptionKeyIdentifier", bdk.getArn(),
                "VerificationKeyIdentifier", pvk.getArn(),
                "PinBlockFormat", "ISO_FORMAT_0",
                "PrimaryAccountNumber", PAN,
                "EncryptedPinBlock", block,
                "DukptAttributes", attributes,
                "VerificationAttributes", visaPin(1,
                        PinCrypto.computePvv(PAN, 1, PIN, pvk.getKeyMaterialHex())))),
                "us-east-1");
        assertEquals(bdk.getArn(), verified.get("EncryptionKeyArn"));
    }

    @Test
    void aDukptTranslationDerivesItsPinKeyFromTheBaseDerivationKey() {
        // The translation's DUKPT attributes are the model's other shape: the key serial
        // number, an optional derivation type and an optional key variant.
        PaymentCryptographyKey bdk = createKey("TDES_2KEY", "TR31_B0_BASE_DERIVATION_KEY",
                "DeriveKey");
        PaymentCryptographyKey tdesPek = pinEncryptionKey();
        String ksn = "12345678901234567890";
        ObjectNode attributes = mapper.createObjectNode();
        attributes.put("KeySerialNumber", ksn);
        attributes.put("DukptKeyDerivationType", "TDES_2KEY");
        attributes.put("DukptKeyVariant", "BIDIRECTIONAL");

        // The block is enciphered under the PIN key the DUKPT derivation makes.
        byte[] pinKey = DukptCrypto.pinKey(bdk.getKeyMaterialHex(), ksn);
        String block = PinCrypto.encipherPinBlock(PinBlockFormat.ISO_FORMAT_0, PIN, PAN, pinKey,
                new SecureRandom());

        Map<String, Object> translated = pin.translatePinData(request(Map.of(
                "EncryptedPinBlock", block,
                "IncomingKeyIdentifier", bdk.getArn(),
                "OutgoingKeyIdentifier", tdesPek.getArn(),
                "IncomingDukptAttributes", attributes,
                "IncomingTranslationAttributes", nested("IsoFormat0", "PrimaryAccountNumber", PAN),
                "OutgoingTranslationAttributes", nested("IsoFormat0", "PrimaryAccountNumber", PAN))),
                "us-east-1");
        // The answer names the outgoing key, which is the key the block is enciphered under.
        assertEquals(tdesPek.getArn(), translated.get("KeyArn"));
    }

    @Test
    void aDukptTranslationOfAnotherKeyVariantIsRefused() {
        // The PIN key this path derives both encrypts and decrypts, so it is the
        // bidirectional one.
        PaymentCryptographyKey bdk = createKey("TDES_2KEY", "TR31_B0_BASE_DERIVATION_KEY",
                "DeriveKey");
        PaymentCryptographyKey tdesPek = pinEncryptionKey();
        ObjectNode attributes = mapper.createObjectNode();
        attributes.put("KeySerialNumber", "12345678901234567890");
        attributes.put("DukptKeyDerivationType", "TDES_2KEY");
        attributes.put("DukptKeyVariant", "REQUEST");

        AwsException refused = assertThrows(AwsException.class,
                () -> pin.translatePinData(request(Map.of(
                        "EncryptedPinBlock", "0000000000000000",
                        "IncomingKeyIdentifier", bdk.getArn(),
                        "OutgoingKeyIdentifier", tdesPek.getArn(),
                        "IncomingDukptAttributes", attributes,
                        "IncomingTranslationAttributes", nested("IsoFormat0",
                                "PrimaryAccountNumber", PAN),
                        "OutgoingTranslationAttributes", nested("IsoFormat0",
                                "PrimaryAccountNumber", PAN))),
                        "us-east-1"));
        assertEquals("ValidationException", refused.getErrorCode());
    }

    @Test
    void aTranslationWithAWrappedKeyIsRefused() {
        // The wrapped-key form of a PIN encryption key is refused: the unwrapping is part of
        // the key exchange, which this service does not make.
        PaymentCryptographyKey pek = pinEncryptionKey();
        AwsException refused = assertThrows(AwsException.class,
                () -> pin.translatePinData(request(Map.of(
                        "EncryptedPinBlock", "0000000000000000",
                        "IncomingKeyIdentifier", pek.getArn(),
                        "OutgoingKeyIdentifier", pek.getArn(),
                        "IncomingWrappedKey", Map.of("WrappedKeyMaterial",
                                Map.of("Tr31KeyBlock", "0123456789ABCDEF0123456789ABCDEF")),
                        "OutgoingWrappedKey", Map.of("WrappedKeyMaterial",
                                Map.of("Tr31KeyBlock", "0123456789ABCDEF0123456789ABCDEF")),
                        "IncomingTranslationAttributes", nested("IsoFormat0",
                                "PrimaryAccountNumber", PAN),
                        "OutgoingTranslationAttributes", nested("IsoFormat0",
                                "PrimaryAccountNumber", PAN))),
                        "us-east-1"));
        assertEquals("ValidationException", refused.getErrorCode());
    }

    @Test
    void aDukptTranslationEncryptsTheOutgoingBlockUnderTheDerivedKey() {
        // The outgoing leg may be a DUKPT key as well, and the block is enciphered under
        // the PIN key the derivation makes.
        PaymentCryptographyKey pek = pinEncryptionKey();
        PaymentCryptographyKey bdk = createKey("TDES_2KEY", "TR31_B0_BASE_DERIVATION_KEY",
                "DeriveKey");
        ObjectNode attributes = mapper.createObjectNode();
        attributes.put("KeySerialNumber", "12345678901234567890");
        attributes.put("DukptKeyDerivationType", "TDES_2KEY");
        attributes.put("DukptKeyVariant", "BIDIRECTIONAL");

        Map<String, Object> generated = pin.generatePinData(request(Map.of(
                "EncryptionKeyIdentifier", pek.getArn(),
                "GenerationKeyIdentifier", visaVerificationKey().getArn(),
                "PinBlockFormat", "ISO_FORMAT_0",
                "PrimaryAccountNumber", PAN,
                "GenerationAttributes", visaPin(1))),
                "us-east-1");

        Map<String, Object> translated = pin.translatePinData(request(Map.of(
                "EncryptedPinBlock", generated.get("EncryptedPinBlock"),
                "IncomingKeyIdentifier", pek.getArn(),
                "OutgoingKeyIdentifier", bdk.getArn(),
                "OutgoingDukptAttributes", attributes,
                "IncomingTranslationAttributes", nested("IsoFormat0", "PrimaryAccountNumber", PAN),
                "OutgoingTranslationAttributes", nested("IsoFormat0", "PrimaryAccountNumber", PAN))),
                "us-east-1");
        // The answer names the outgoing key, and the block it carries is enciphered under
        // the key that derivation makes.
        assertEquals(bdk.getArn(), translated.get("KeyArn"));
    }

    @Test
    void aDukptDerivationOfAnotherTypeIsRefused() {
        PaymentCryptographyKey bdk = createKey("TDES_2KEY", "TR31_B0_BASE_DERIVATION_KEY",
                "DeriveKey");
        PaymentCryptographyKey pvk = visaVerificationKey();
        ObjectNode attributes = mapper.createObjectNode();
        attributes.put("KeySerialNumber", "12345678901234567890");
        attributes.put("DukptDerivationType", "AES_128");

        AwsException refused = assertThrows(AwsException.class,
                () -> pin.verifyPinData(request(Map.of(
                        "EncryptionKeyIdentifier", bdk.getArn(),
                        "VerificationKeyIdentifier", pvk.getArn(),
                        "PinBlockFormat", "ISO_FORMAT_0",
                        "PrimaryAccountNumber", PAN,
                        "EncryptedPinBlock", "0000000000000000",
                        "DukptAttributes", attributes,
                        "VerificationAttributes", visaPin(1, "1234"))),
                        "us-east-1"));
        assertEquals("ValidationException", refused.getErrorCode());
    }
}
