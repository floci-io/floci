package io.github.hectorvent.floci.services.paymentcryptography;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.core.common.RegionResolver;
import io.github.hectorvent.floci.core.storage.InMemoryStorage;
import io.github.hectorvent.floci.services.paymentcryptography.model.PaymentCryptographyKey;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Locale;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PaymentCryptographyDataPlaneHandlerTest {

    private PaymentCryptographyService service;
    private PaymentCryptographyDataPlaneHandler handler;
    private final ObjectMapper mapper = new ObjectMapper();

    @BeforeEach
    void setUp() {
        service = new PaymentCryptographyService(
                new InMemoryStorage<>(),
                new InMemoryStorage<>(),
                new RegionResolver("us-east-1", "000000000000"));
        handler = new PaymentCryptographyDataPlaneHandler(service);
    }

    /** A TDES key, which is what the ISO 9797-1 MAC is computed under. */
    private PaymentCryptographyKey createTdesKey(String... modes) {
        Map<String, Object> attributes = new LinkedHashMap<>();
        attributes.put("KeyAlgorithm", "TDES_2KEY");
        attributes.put("KeyClass", "SYMMETRIC_KEY");
        attributes.put("KeyUsage", "TR31_M6_ISO_9797_5_CMAC_KEY");
        Map<String, Boolean> declared = new LinkedHashMap<>();
        for (String mode : modes) {
            declared.put(mode, Boolean.TRUE);
        }
        return service.createKey("us-east-1", attributes, declared, true, true, Map.of());
    }

    private PaymentCryptographyKey createKey(String... modes) {
        Map<String, Object> attributes = new LinkedHashMap<>();
        attributes.put("KeyAlgorithm", "AES_256");
        attributes.put("KeyClass", "SYMMETRIC_KEY");
        attributes.put("KeyUsage", "TR31_D0_SYMMETRIC_DATA_ENCRYPTION_KEY");
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

    @Test
    void aKeyEncryptsAndDecrypts() {
        PaymentCryptographyKey key = createKey("Encrypt", "Decrypt");

        Map<String, Object> encrypted = handler.encryptData(request(Map.of(
                "KeyIdentifier", key.getArn(),
                "PlainText", hex("hello, world".getBytes()),
                "EncryptionAttributes", cbc())), "us-east-1");

        assertEquals(key.getArn(), encrypted.get("KeyArn"));
        assertEquals(key.getKeyCheckValue(), encrypted.get("KeyCheckValue"));
        String cipherText = (String) encrypted.get("CipherText");

        Map<String, Object> decrypted = handler.decryptData(request(Map.of(
                "KeyIdentifier", key.getArn(),
                "CipherText", cipherText,
                "DecryptionAttributes", cbc())), "us-east-1");

        assertEquals(hex("hello, world".getBytes()), decrypted.get("PlainText"));
    }

    @Test
    void theCiphertextIsNotThePlaintextAndTwoEncryptionsDiffer() {
        PaymentCryptographyKey key = createKey("Encrypt", "Decrypt");
        String plain = hex("the same plaintext".getBytes());

        String first = (String) handler.encryptData(request(Map.of(
                "KeyIdentifier", key.getArn(), "PlainText", plain,
                "EncryptionAttributes", cbc())), "us-east-1").get("CipherText");
        String second = (String) handler.encryptData(request(Map.of(
                "KeyIdentifier", key.getArn(), "PlainText", plain,
                "EncryptionAttributes", cbc())), "us-east-1").get("CipherText");

        assertNotEquals(plain, first);
        assertNotEquals(first, second, "two encryptions of the same plaintext are the same");
    }

    @Test
    void aKeyThatMayNotEncryptRefuses() {
        PaymentCryptographyKey key = createKey("Decrypt");

        AwsException refused = assertThrows(AwsException.class, () -> handler.encryptData(request(Map.of(
                "KeyIdentifier", key.getArn(), "PlainText", hex("xxxxxxxx".getBytes()))), "us-east-1"));

        assertEquals("InvalidKeyUsageException", refused.getErrorCode());
    }

    @Test
    void aKeyThatIsNotThereIsNotFound() {
        AwsException refused = assertThrows(AwsException.class, () -> handler.encryptData(request(Map.of(
                "KeyIdentifier", "arn:aws:payment-cryptography:us-east-1:000000000000:key/nope",
                "PlainText", "")), "us-east-1"));

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
    void aMacLengthTheServiceDoesNotComputeIsRefused() {
        PaymentCryptographyKey key = createKey("Generate", "Verify");

        AwsException refused = assertThrows(AwsException.class, () -> handler.generateMac(request(Map.of(
                "KeyIdentifier", key.getArn(), "MessageData", "", "MacLength", 7)), "us-east-1"));

        assertEquals("ValidationException", refused.getErrorCode());
    }

    @Test
    void aKeyReEncryptsUnderAnotherKey() {
        PaymentCryptographyKey incoming = createKey("Encrypt", "Decrypt");
        PaymentCryptographyKey outgoing = createKey("Encrypt", "Decrypt");
        String cipherText = (String) handler.encryptData(request(Map.of(
                "KeyIdentifier", incoming.getArn(),
                "PlainText", hex("moved here".getBytes()),
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
        assertEquals(hex("moved here".getBytes()), decrypted.get("PlainText"));
    }

    @Test
    void theOperationsThatNeedAMaterialTheServiceDoesNotHaveAreRefused() {
        // The translation of an AS2805 block is the one operation of these that the service
        // does not serve: the byte layout of the cryptogram is in the standard and is not
        // published, so a cryptogram the service produced would not open here.
        for (Runnable refused : new Runnable[] {
                () -> handler.translatePinData(request(Map.of(
                        "IncomingKeyIdentifier", "arn:aws:payment-cryptography:us-east-1:000000000000:key/x",
                        "OutgoingKeyIdentifier", "arn:aws:payment-cryptography:us-east-1:000000000000:key/y",
                        "IncomingAs2805Attributes", Map.of("WrappingKeyIdentifier", "x"),
                        "EncryptedPinBlock", "0011223344556677")), "us-east-1"),
                () -> handler.translateKeyMaterial(request(Map.of()), "us-east-1")}) {
            AwsException refusal = assertThrows(AwsException.class, refused::run);
            assertEquals("ValidationException", refusal.getErrorCode());
        }
    }

    @Test
    void aKeyWithNoRestrictionsMayBeUsedForAnything() {
        PaymentCryptographyKey key = createKey("NoRestrictions");

        Map<String, Object> encrypted = handler.encryptData(request(Map.of(
                "KeyIdentifier", key.getArn(), "PlainText", hex("xxxxxxxx".getBytes()),
                "EncryptionAttributes", cbc())), "us-east-1");

        assertTrue(encrypted.containsKey("CipherText"));
    }

    @Test
    void thePlainTextIsTheBytesTheCallerGave() {
        PaymentCryptographyKey key = createKey("Encrypt", "Decrypt");
        byte[] plain = "a longer plaintext that spans a block boundary".getBytes(StandardCharsets.UTF_8);

        String cipherText = (String) handler.encryptData(request(Map.of(
                "KeyIdentifier", key.getArn(), "PlainText", hex(plain),
                "EncryptionAttributes", cbc())), "us-east-1").get("CipherText");

        assertEquals(hex(plain), handler.decryptData(request(Map.of(
                "KeyIdentifier", key.getArn(), "CipherText", cipherText,
                "DecryptionAttributes", cbc())), "us-east-1").get("PlainText"));
    }
}
