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

import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PaymentCryptographyCryptoHandlerTest {

    private PaymentCryptographyService service;
    private PaymentCryptographyCryptoHandler handler;
    private InMemoryStorage<String, PaymentCryptographyKey> keys;
    private final ObjectMapper mapper = new ObjectMapper();

    @BeforeEach
    void setUp() {
        keys = new InMemoryStorage<>();
        service = new PaymentCryptographyService(
                keys,
                new InMemoryStorage<>(),
                new RegionResolver("us-east-1", "000000000000"));
        handler = new PaymentCryptographyCryptoHandler(service);
    }

    /** A TDES key, which is what the ISO 9797-1 MAC is computed under. */
    private PaymentCryptographyKey createTdesKey(String... modes) {
        return createKeyOf("TDES_2KEY", "TR31_M6_ISO_9797_5_CMAC_KEY", modes);
    }

    private PaymentCryptographyKey createTdesKeyOf(String algorithm, String... modes) {
        return createKeyOf(algorithm, "TR31_M6_ISO_9797_5_CMAC_KEY", modes);
    }

    /** A Triple DES key of the data encryption usage, which the data plane ciphers with. */
    private PaymentCryptographyKey createTdesDataKey(String algorithm, String... modes) {
        return createKeyOf(algorithm, "TR31_D0_SYMMETRIC_DATA_ENCRYPTION_KEY", modes);
    }

    private PaymentCryptographyKey createKey(String... modes) {
        return createKeyOf("AES_256", "TR31_D0_SYMMETRIC_DATA_ENCRYPTION_KEY", modes);
    }

    /** A key of the algorithm and the usage a test asks for. */
    private PaymentCryptographyKey createKeyOf(String algorithm, String usage, String... modes) {
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

    private static byte[] hexBytes(String hex) {
        return PaymentCryptographyCrypto.fromHex(hex);
    }

    /** The MAC attributes of the ISO 9797-1 algorithm 3, the retail MAC. */
    private static JsonNode iso9797() {
        ObjectMapper mapper = new ObjectMapper();
        ObjectNode attributes = mapper.createObjectNode();
        attributes.put("Algorithm", "ISO9797_ALGORITHM3");
        return attributes;
    }

    private static String hex(byte[] bytes) {
        return PaymentCryptographyCrypto.toHex(bytes).toUpperCase(Locale.ROOT);
    }

    /** A request body, built as a map so the test says what it means. */
    private JsonNode request(Map<String, Object> members) {
        ObjectNode node = mapper.createObjectNode();
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


    /** The encryption attributes of a request that encrypts in CBC mode. */
    private JsonNode cbc() {
        ObjectNode symmetric = mapper.createObjectNode();
        symmetric.put("Mode", "CBC");
        ObjectNode attributes = mapper.createObjectNode();
        attributes.set("Symmetric", symmetric);
        return attributes;
    }

    /** The CBC attributes with the vector the request names. */
    private JsonNode cbcWithVector(String vector) {
        ObjectNode symmetric = mapper.createObjectNode();
        symmetric.put("Mode", "CBC");
        symmetric.put("InitializationVector", vector);
        ObjectNode attributes = mapper.createObjectNode();
        attributes.set("Symmetric", symmetric);
        return attributes;
    }

    @Test
    void aKeyEncryptsAndDecrypts() {
        PaymentCryptographyKey key = createKey("Encrypt", "Decrypt");

        Map<String, Object> encrypted = handler.encryptData(request(Map.of(
                "KeyIdentifier", key.getArn(),
                "PlainText", hex("hello, world!!!!".getBytes()),
                "EncryptionAttributes", cbc())), "us-east-1");

        assertEquals(key.getArn(), encrypted.get("KeyArn"));
        assertEquals(key.getKeyCheckValue(), encrypted.get("KeyCheckValue"));
        String cipherText = (String) encrypted.get("CipherText");

        Map<String, Object> decrypted = handler.decryptData(request(Map.of(
                "KeyIdentifier", key.getArn(),
                "CipherText", cipherText,
                "DecryptionAttributes", cbc())), "us-east-1");

        assertEquals(hex("hello, world!!!!".getBytes()), decrypted.get("PlainText"));
    }

    @Test
    void theCiphertextIsNotThePlaintextAndTheVectorTravelsBesideIt() {
        PaymentCryptographyKey key = createKey("Encrypt", "Decrypt");
        String plain = hex("the same data!!!".getBytes());

        String first = (String) handler.encryptData(request(Map.of(
                "KeyIdentifier", key.getArn(), "PlainText", plain,
                "EncryptionAttributes", cbc())), "us-east-1").get("CipherText");
        String second = (String) handler.encryptData(request(Map.of(
                "KeyIdentifier", key.getArn(), "PlainText", plain,
                "EncryptionAttributes", cbc())), "us-east-1").get("CipherText");

        assertNotEquals(plain, first);
        // The vector is not in the ciphertext, so two encryptions under the same vector are
        // the same ciphertext, and two under different vectors are not.
        assertEquals(first, second, "the same vector gives the same ciphertext");
        String other = (String) handler.encryptData(request(Map.of(
                "KeyIdentifier", key.getArn(), "PlainText", plain,
                "EncryptionAttributes", cbcWithVector("00112233445566778899aabbccddeeff"))),
                "us-east-1").get("CipherText");
        assertNotEquals(first, other, "another vector gives another ciphertext");
    }

    @Test
    void aKeyThatMayNotEncryptRefuses() {
        PaymentCryptographyKey key = createKey("Decrypt");

        AwsException refused = assertThrows(AwsException.class, () -> handler.encryptData(request(Map.of(
                "KeyIdentifier", key.getArn(), "PlainText", hex("xxxxxxxxxxxxxxxx".getBytes()),
                "EncryptionAttributes", cbc())), "us-east-1"));

        assertEquals("ValidationException", refused.getErrorCode());
    }

    @Test
    void aKeyOfAnotherPurposeRefuses() {
        // A PIN encryption key may encrypt a PIN block and nothing else, so it does not encrypt
        // arbitrary data, and a MAC key does not either.
        PaymentCryptographyKey pin =
                createKeyOf("AES_256", "TR31_P0_PIN_ENCRYPTION_KEY", "Encrypt", "Decrypt");

        AwsException refused = assertThrows(AwsException.class, () -> handler.encryptData(request(Map.of(
                "KeyIdentifier", pin.getArn(), "PlainText", hex("xxxxxxxxxxxxxxxx".getBytes()),
                "EncryptionAttributes", cbc())), "us-east-1"));

        assertEquals("ValidationException", refused.getErrorCode());

        PaymentCryptographyKey mac = createTdesKey("Generate", "Verify");

        AwsException refusedMac = assertThrows(AwsException.class, () -> handler.encryptData(request(Map.of(
                "KeyIdentifier", mac.getArn(), "PlainText", hex("xxxxxxxxxxxxxxxx".getBytes()),
                "EncryptionAttributes", cbc())), "us-east-1"));

        assertEquals("ValidationException", refusedMac.getErrorCode());
    }

    @Test
    void aKeyIdentifierThatNamesNoKeyIsRefused() {
        // The identifier of a key on its own is not the ARN of a key nor an alias, and the
        // model says so, so the request is refused before it looks for the key.
        PaymentCryptographyKey key = createTdesKey("Encrypt", "Decrypt");

        AwsException refused = assertThrows(AwsException.class, () -> handler.encryptData(request(Map.of(
                "KeyIdentifier", key.getKeyId(), "PlainText", hex("xxxxxxxxxxxxxxxx".getBytes()),
                "EncryptionAttributes", cbc())), "us-east-1"));

        assertEquals("ValidationException", refused.getErrorCode());
    }

    @Test
    void aKeyThatIsNotThereIsNotFound() {
        AwsException refused = assertThrows(AwsException.class, () -> handler.encryptData(request(Map.of(
                "KeyIdentifier", "arn:aws:payment-cryptography:us-east-1:000000000000:key/"
                        + "1234abcd1234abcd",
                "PlainText", hex("xxxxxxxxxxxxxxxx".getBytes()),
                "EncryptionAttributes", cbc())), "us-east-1"));

        assertEquals("ResourceNotFoundException", refused.getErrorCode());
    }

    @Test
    void aMacIsComputedAndVerified() {
        PaymentCryptographyKey key = createTdesKey("Generate", "Verify");
        String message = hex("a message".getBytes());

        Map<String, Object> mac = handler.generateMac(request(Map.of(
                "KeyIdentifier", key.getArn(), "MessageData", message, "MacLength", 8,
                "GenerationAttributes", iso9797())), "us-east-1");
        String value = (String) mac.get("Mac");

        assertEquals(8, hexBytes(value).length);

        Map<String, Object> verified = handler.verifyMac(request(Map.of(
                "KeyIdentifier", key.getArn(), "MessageData", message, "Mac", value, "MacLength", 8,
                "VerificationAttributes", iso9797())), "us-east-1");
        assertEquals(key.getArn(), verified.get("KeyArn"));
    }

    @Test
    void aMacUnderAThreeKeyTdesKeyIsComputedAndVerified() {
        // The ISO 9797 MACs are defined over Triple DES, and the cipher takes the three keys
        // a three-key key holds, so such a key computes a MAC rather than failing.
        PaymentCryptographyKey key = createTdesKeyOf("TDES_3KEY", "Generate", "Verify");
        String message = hex("a message".getBytes());

        Map<String, Object> mac = handler.generateMac(request(Map.of(
                "KeyIdentifier", key.getArn(), "MessageData", message, "MacLength", 8,
                "GenerationAttributes", iso9797())), "us-east-1");
        String value = (String) mac.get("Mac");

        assertEquals(8, hexBytes(value).length);

        Map<String, Object> verified = handler.verifyMac(request(Map.of(
                "KeyIdentifier", key.getArn(), "MessageData", message, "Mac", value, "MacLength", 8,
                "VerificationAttributes", iso9797())), "us-east-1");
        assertEquals(key.getArn(), verified.get("KeyArn"));
    }

    @Test
    void aMacOfAnotherMessageIsNotValid() {
        PaymentCryptographyKey key = createTdesKey("Generate", "Verify");
        String mac = (String) handler.generateMac(request(Map.of(
                "KeyIdentifier", key.getArn(),
                "MessageData", hex("a message".getBytes()),
                "GenerationAttributes", iso9797())), "us-east-1").get("Mac");

        AwsException refused = assertThrows(AwsException.class, () -> handler.verifyMac(request(Map.of(
                "KeyIdentifier", key.getArn(),
                "MessageData", hex("another message".getBytes()),
                "Mac", mac,
                "VerificationAttributes", iso9797())), "us-east-1"));

        assertEquals("VerificationFailedException", refused.getErrorCode());
    }

    @Test
    void aTripleDesKeyEncryptsAndDecrypts() {
        // The reference ciphers with Triple DES as well as with AES, and the block follows the
        // key's cipher: eight bytes for Triple DES.
        PaymentCryptographyKey key = createTdesDataKey("TDES_2KEY", "Encrypt", "Decrypt");
        String plaintext = "0011223344556677";

        Map<String, Object> encrypted = handler.encryptData(request(Map.of(
                "KeyIdentifier", key.getArn(), "PlainText", plaintext,
                "EncryptionAttributes", cbc())), "us-east-1");
        String cipherText = (String) encrypted.get("CipherText");

        assertEquals(plaintext.length(), cipherText.length());

        Map<String, Object> decrypted = handler.decryptData(request(Map.of(
                "KeyIdentifier", key.getArn(), "CipherText", cipherText,
                "DecryptionAttributes", cbc())), "us-east-1");

        assertEquals(plaintext, decrypted.get("PlainText"));
    }

    @Test
    void aTripleDesPlaintextThatDoesNotFillWholeBlocksIsRefused() {
        // The block of a Triple DES key is eight bytes, so twelve bytes do not fill one.
        PaymentCryptographyKey key = createTdesDataKey("TDES_2KEY", "Encrypt", "Decrypt");

        AwsException refused = assertThrows(AwsException.class, () -> handler.encryptData(request(Map.of(
                "KeyIdentifier", key.getArn(), "PlainText", "00112233445566778899ab",
                "EncryptionAttributes", cbc())), "us-east-1"));

        assertEquals("ValidationException", refused.getErrorCode());
        assertTrue(refused.getMessage().contains("8 bytes (one TDES block)"), refused.getMessage());
    }

    @Test
    void aPlaintextThatDoesNotFillWholeBlocksIsRefused() {
        // The cipher pads no data, so the caller supplies whole blocks: a plaintext of
        // fifteen bytes does not fill an AES block and is refused rather than padded.
        PaymentCryptographyKey key = createKey("Encrypt", "Decrypt");

        AwsException refused = assertThrows(AwsException.class, () -> handler.encryptData(request(Map.of(
                "KeyIdentifier", key.getArn(), "PlainText", "00".repeat(15),
                "EncryptionAttributes", cbc())), "us-east-1"));

        assertEquals("ValidationException", refused.getErrorCode());
        assertTrue(refused.getMessage().contains("16 bytes"), refused.getMessage());
    }

    @Test
    void aCipherTextIsAsLongAsThePlainTextItHolds() {
        // The cipher pads no data, so a ciphertext is as long as its plaintext.
        PaymentCryptographyKey key = createKey("Encrypt", "Decrypt");
        String plaintext = "00".repeat(32);

        Map<String, Object> answer = handler.encryptData(request(Map.of(
                "KeyIdentifier", key.getArn(), "PlainText", plaintext,
                "EncryptionAttributes", cbc())), "us-east-1");

        assertEquals(plaintext.length(), ((String) answer.get("CipherText")).length());
    }

    @Test
    void aMacLengthThatIsNotANumberIsRefused() {
        // The protocol carries the length as a number, so a member that is not one is a
        // malformed request, and not the length the service would compute.
        PaymentCryptographyKey key = createTdesKey("Generate", "Verify");

        AwsException refused = assertThrows(AwsException.class, () -> handler.generateMac(request(Map.of(
                "KeyIdentifier", key.getArn(), "MessageData", "00".repeat(16),
                "GenerationAttributes", iso9797(), "MacLength", "abc")), "us-east-1"));

        assertEquals("ValidationException", refused.getErrorCode());
    }

    @Test
    void aKeyWhoseMaterialIsNotHeldIsRefusedRatherThanFailing() {
        // The material is held by the key while the emulator runs and is not written to the
        // store, so a key read back after a restart has none. A request that needs it is then
        // refused with an error that says so, and not with a server error.
        PaymentCryptographyKey key = createKey("Encrypt", "Decrypt");
        key.setKeyMaterialHex(null);
        keys.put("us-east-1\u0000" + key.getKeyId(), key);

        AwsException refused = assertThrows(AwsException.class, () -> handler.encryptData(request(Map.of(
                "KeyIdentifier", key.getArn(), "PlainText", "00".repeat(16),
                "EncryptionAttributes", cbc())), "us-east-1"));

        assertEquals("ValidationException", refused.getErrorCode());
        assertTrue(refused.getMessage().contains("material"), refused.getMessage());
    }

    @Test
    void aMacLengthTheServiceDoesNotComputeIsRefused() {
        PaymentCryptographyKey key = createKey("Generate", "Verify");

        AwsException refused = assertThrows(AwsException.class, () -> handler.generateMac(request(Map.of(
                "KeyIdentifier", key.getArn(), "MessageData", "", "MacLength", 7)), "us-east-1"));

        assertEquals("ValidationException", refused.getErrorCode());
    }

    /** The MAC attributes of a DUKPT MAC, derived from a base derivation key. */
    private static JsonNode dukpt(String variant) {
        ObjectMapper mapper = new ObjectMapper();
        ObjectNode dukpt = mapper.createObjectNode();
        // The model asks for at least sixteen hexadecimal digits, and a KSN is ten bytes.
        dukpt.put("KeySerialNumber", "98765432109876543210");
        dukpt.put("DukptKeyVariant", "REQUEST");
        dukpt.put("DukptDerivationType", "TDES_2KEY");
        ObjectNode attributes = mapper.createObjectNode();
        attributes.set(variant, dukpt);
        return attributes;
    }

    /** The base derivation key the DUKPT MAC is derived from. */
    private PaymentCryptographyKey createBaseDerivationKey(String algorithm, String... modes) {
        Map<String, Object> attributes = new LinkedHashMap<>();
        attributes.put("KeyAlgorithm", algorithm);
        attributes.put("KeyClass", "SYMMETRIC_KEY");
        attributes.put("KeyUsage", "TR31_B0_BASE_DERIVATION_KEY");
        Map<String, Boolean> declared = new LinkedHashMap<>();
        for (String mode : modes) {
            declared.put(mode, Boolean.TRUE);
        }
        return service.createKey("us-east-1", attributes, declared, true, true, Map.of());
    }

    @Test
    void aDukptMacIsComputedAndVerifiedUnderABaseDerivationKey() {
        // The DUKPT MAC key is derived from the base derivation key, so that is the key the
        // request names, and it derives rather than computes the MAC itself.
        PaymentCryptographyKey key = createBaseDerivationKey("TDES_2KEY", "DeriveKey");
        String message = hex("a message".getBytes());

        Map<String, Object> mac = handler.generateMac(request(Map.of(
                "KeyIdentifier", key.getArn(), "MessageData", message, "MacLength", 8,
                "GenerationAttributes", dukpt("DukptIso9797Algorithm1"))), "us-east-1");
        String value = (String) mac.get("Mac");

        assertEquals(8, hexBytes(value).length);

        Map<String, Object> verified = handler.verifyMac(request(Map.of(
                "KeyIdentifier", key.getArn(), "MessageData", message, "Mac", value, "MacLength", 8,
                "VerificationAttributes", dukpt("DukptIso9797Algorithm1"))), "us-east-1");
        assertEquals(key.getArn(), verified.get("KeyArn"));
    }

    @Test
    void aDukptMacUnderAKeyThatMayNotDeriveRefuses() {
        // The key is named by the request but may only compute MACs of its own, so it cannot
        // be the base a DUKPT MAC key is derived from.
        PaymentCryptographyKey key = createTdesKey("Generate", "Verify");

        AwsException refused = assertThrows(AwsException.class, () -> handler.generateMac(request(Map.of(
                "KeyIdentifier", key.getArn(), "MessageData", hex("a message".getBytes()), "MacLength", 8,
                "GenerationAttributes", dukpt("DukptIso9797Algorithm1"))), "us-east-1"));

        assertEquals("ValidationException", refused.getErrorCode());
    }

    @Test
    void aDukptMacUnderAKeyOfAnotherPurposeRefuses() {
        // The key may derive, but it is a data encryption key rather than a base derivation
        // key, so it is not one a DUKPT MAC key is derived from.
        PaymentCryptographyKey key =
                createKeyOf("AES_256", "TR31_D0_SYMMETRIC_DATA_ENCRYPTION_KEY", "DeriveKey");

        AwsException refused = assertThrows(AwsException.class, () -> handler.generateMac(request(Map.of(
                "KeyIdentifier", key.getArn(), "MessageData", hex("a message".getBytes()), "MacLength", 8,
                "GenerationAttributes", dukpt("DukptIso9797Algorithm1"))), "us-east-1"));

        assertEquals("ValidationException", refused.getErrorCode());
    }

    @Test
    void aDukptMacUnderAnAesKeyRefuses() {
        // The derivation is over Triple DES, so a key of another family is not one.
        PaymentCryptographyKey key = createBaseDerivationKey("AES_256", "DeriveKey");

        AwsException refused = assertThrows(AwsException.class, () -> handler.generateMac(request(Map.of(
                "KeyIdentifier", key.getArn(), "MessageData", hex("a message".getBytes()), "MacLength", 8,
                "GenerationAttributes", dukpt("DukptIso9797Algorithm1"))), "us-east-1"));

        assertEquals("ValidationException", refused.getErrorCode());
    }

    @Test
    void aDukptDerivationTypeTheServiceDoesNotDeriveByRefuses() {
        PaymentCryptographyKey key = createBaseDerivationKey("TDES_2KEY", "DeriveKey");
        JsonNode attributes = dukpt("DukptIso9797Algorithm1");
        ((ObjectNode) attributes.path("DukptIso9797Algorithm1")).put("DukptDerivationType", "AES_256");
        AwsException refused = assertThrows(AwsException.class, () -> handler.generateMac(request(Map.of(
                "KeyIdentifier", key.getArn(), "MessageData", hex("a message".getBytes()), "MacLength", 8,
                "GenerationAttributes", attributes)), "us-east-1"));

        assertEquals("ValidationException", refused.getErrorCode());
    }

    @Test
    void aDukptMacUnderASerialNumberTheDerivationDoesNotTakeRefuses() {
        // The model takes a serial number of sixteen, twenty or twenty-four hexadecimal
        // digits, and the derivation takes one of ten bytes, so a longer one is refused by
        // the derivation and not by the model.
        PaymentCryptographyKey key = createBaseDerivationKey("TDES_2KEY", "DeriveKey");
        JsonNode attributes = dukpt("DukptIso9797Algorithm1");
        ((ObjectNode) attributes.path("DukptIso9797Algorithm1"))
                .put("KeySerialNumber", "987654321098765432109876");

        AwsException refused = assertThrows(AwsException.class, () -> handler.generateMac(request(Map.of(
                "KeyIdentifier", key.getArn(), "MessageData", hex("a message".getBytes()), "MacLength", 8,
                "GenerationAttributes", attributes)), "us-east-1"));

        assertEquals("ValidationException", refused.getErrorCode());
        assertTrue(refused.getMessage().contains("DUKPT derivation failed"), refused.getMessage());
    }

    @Test
    void aKeyReEncryptsUnderAnotherKey() {
        PaymentCryptographyKey incoming = createKey("Encrypt", "Decrypt");
        PaymentCryptographyKey outgoing = createKey("Encrypt", "Decrypt");
        String cipherText = (String) handler.encryptData(request(Map.of(
                "KeyIdentifier", incoming.getArn(),
                "PlainText", hex("moved over there".getBytes()),
                "EncryptionAttributes", cbc())), "us-east-1").get("CipherText");

        Map<String, Object> reEncrypted = handler.reEncryptData(request(Map.of(
                "IncomingKeyIdentifier", incoming.getArn(),
                "OutgoingKeyIdentifier", outgoing.getArn(),
                "CipherText", cipherText,
                "IncomingEncryptionAttributes", cbc(),
                "OutgoingEncryptionAttributes", cbc())), "us-east-1");

        Map<String, Object> decrypted = handler.decryptData(request(Map.of(
                "KeyIdentifier", outgoing.getArn(),
                "CipherText", (String) reEncrypted.get("CipherText"),
                "DecryptionAttributes", cbc())), "us-east-1");
        assertEquals(hex("moved over there".getBytes()), decrypted.get("PlainText"));
    }

    @Test
    void theOperationsThatNeedAMaterialTheServiceDoesNotHaveAreRefused() {
        // The translation of a key's material is the one operation of these that the service
        // does not serve: the byte layout of the cryptogram is in the standard and is not
        // published, so a cryptogram the service produced would not open here.
        for (Runnable refused : new Runnable[] {
                () -> handler.translateKeyMaterial(request(Map.of()), "us-east-1")}) {
            AwsException refusal = assertThrows(AwsException.class, refused::run);
            assertEquals("ValidationException", refusal.getErrorCode());
        }
    }

    @Test
    void aKeyWithNoRestrictionsMayBeUsedForAnything() {
        PaymentCryptographyKey key = createKey("NoRestrictions");

        Map<String, Object> encrypted = handler.encryptData(request(Map.of(
                "KeyIdentifier", key.getArn(), "PlainText", hex("xxxxxxxxxxxxxxxx".getBytes()),
                "EncryptionAttributes", cbc())), "us-east-1");

        assertTrue(encrypted.containsKey("CipherText"));
    }

    @Test
    void thePlainTextIsTheBytesTheCallerGave() {
        PaymentCryptographyKey key = createKey("Encrypt", "Decrypt");
        byte[] plain = "a longer plaintext that spans a block boundary!!".getBytes(StandardCharsets.UTF_8);

        String cipherText = (String) handler.encryptData(request(Map.of(
                "KeyIdentifier", key.getArn(), "PlainText", hex(plain),
                "EncryptionAttributes", cbc())), "us-east-1").get("CipherText");

        assertEquals(hex(plain), handler.decryptData(request(Map.of(
                "KeyIdentifier", key.getArn(), "CipherText", cipherText,
                "DecryptionAttributes", cbc())), "us-east-1").get("PlainText"));
    }
}
