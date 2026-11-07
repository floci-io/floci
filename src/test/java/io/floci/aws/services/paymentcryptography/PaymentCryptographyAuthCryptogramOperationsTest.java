package io.floci.aws.services.paymentcryptography;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.floci.aws.core.common.AwsException;
import io.floci.aws.core.common.RegionResolver;
import io.floci.aws.core.storage.InMemoryStorage;
import io.floci.aws.services.paymentcryptography.crypto.EmvCrypto;
import io.floci.aws.services.paymentcryptography.model.PaymentCryptographyKey;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The authorization request cryptograms.
 *
 * <p>The derivations in the first test are the emulator's, and the emulator checks them
 * against an independent implementation: the ICC master key, the session key that
 * comes from it, and the ARQC of a
 * transaction.
 */
class PaymentCryptographyAuthCryptogramOperationsTest {

    /** The hexadecimal the data plane's blobs are written in. */
    private static final HexFormat HEX = HexFormat.of().withUpperCase();

    private static final String IMK = "0123456789ABCDEFFEDCBA9876543210";
    private static final String PAN = "4111111111111111";
    private static final String PSN = "0001";
    private static final String ATC = "0000000000000001";
    private static final String TRANSACTION_DATA =
            "000000000100000000000008000000000000080010000000000000000000000000000000";

    private PaymentCryptographyService service;
    private PaymentCryptographyAuthCryptogramOperations cryptogram;
    private final ObjectMapper mapper = new ObjectMapper();

    @BeforeEach
    void setUp() {
        service = new PaymentCryptographyService(
                new InMemoryStorage<>(),
                new InMemoryStorage<>(),
                new RegionResolver("us-east-1", "000000000000"));
        cryptogram = new PaymentCryptographyAuthCryptogramOperations(service);
    }

    private PaymentCryptographyKey createKey(String... modes) {
        Map<String, Object> attributes = new LinkedHashMap<>();
        attributes.put("KeyAlgorithm", "TDES_2KEY");
        attributes.put("KeyClass", "SYMMETRIC_KEY");
        attributes.put("KeyUsage", "TR31_E0_EMV_MKEY_APP_CRYPTOGRAMS");
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

    /** The session key derivation attributes of the EMV common variant. */
    private static JsonNode emvCommon() {
        ObjectMapper mapper = new ObjectMapper();
        ObjectNode value = mapper.createObjectNode();
        value.put("PrimaryAccountNumber", PAN);
        value.put("PanSequenceNumber", PSN);
        value.put("ApplicationTransactionCounter", ATC);
        ObjectNode outer = mapper.createObjectNode();
        outer.set("EmvCommon", value);
        return outer;
    }

    @Test
    void theDerivationsAreTheOnesTheStandardGives() {
        byte[] masterKey = EmvCrypto.deriveIccMasterKey(HEX.parseHex(IMK), PAN, PSN);
        assertEquals("4F5891BFD91C5B106B3445AB2AFD7A89", HEX.formatHex(masterKey));
        assertEquals("C764C813866737C7985192465B1AC8EA",
                HEX.formatHex(EmvCrypto.deriveIccMasterKeyOptionB(HEX.parseHex(IMK), PAN, PSN)));
        assertEquals("FE924AE3BF4620D94C8F1351B061D54A",
                HEX.formatHex(EmvCrypto.deriveSessionKey(masterKey, HEX.parseHex(ATC))));
    }

    @Test
    void aGeneratedCryptogramVerifies() {
        PaymentCryptographyKey key = createKey("DeriveKey");

        Map<String, Object> generated = cryptogram.generate(request(Map.of(
                "KeyIdentifier", key.getArn(),
                "TransactionData", TRANSACTION_DATA,
                "MajorKeyDerivationMode", "EMV_OPTION_A",
                "SessionKeyDerivationAttributes", emvCommon())), "us-east-1");

        String arqc = (String) generated.get("AuthRequestCryptogram");
        assertEquals(16, arqc.length());
        assertEquals(key.getArn(), generated.get("KeyArn"));

        Map<String, Object> verified = cryptogram.verify(request(Map.of(
                "KeyIdentifier", key.getArn(),
                "AuthRequestCryptogram", arqc,
                "TransactionData", TRANSACTION_DATA,
                "MajorKeyDerivationMode", "EMV_OPTION_A",
                "SessionKeyDerivationAttributes", emvCommon())), "us-east-1");

        assertEquals(key.getArn(), verified.get("KeyArn"));
    }

    @Test
    void aCryptogramOfAnotherTransactionIsRefused() {
        PaymentCryptographyKey key = createKey("DeriveKey");

        Map<String, Object> generated = cryptogram.generate(request(Map.of(
                "KeyIdentifier", key.getArn(),
                "TransactionData", TRANSACTION_DATA,
                "MajorKeyDerivationMode", "EMV_OPTION_A",
                "SessionKeyDerivationAttributes", emvCommon())), "us-east-1");

        AwsException refused = assertThrows(AwsException.class, () -> cryptogram.verify(request(Map.of(
                "KeyIdentifier", key.getArn(),
                "AuthRequestCryptogram", generated.get("AuthRequestCryptogram"),
                "TransactionData", "000000000200000000000008000000000000080020000000000000000000000000000000",
                "MajorKeyDerivationMode", "EMV_OPTION_A",
                "SessionKeyDerivationAttributes", emvCommon())), "us-east-1"));

        assertEquals("VerificationFailedException", refused.getErrorCode());
    }

    @Test
    void theAmexDerivationIsRefusedRatherThanApproximated() {
        PaymentCryptographyKey key = createKey("DeriveKey");
        ObjectMapper mapper = new ObjectMapper();
        ObjectNode amex = mapper.createObjectNode();
        amex.put("PrimaryAccountNumber", PAN);
        amex.put("PanSequenceNumber", PSN);
        amex.put("ApplicationTransactionCounter", ATC);
        ObjectNode attributes = mapper.createObjectNode();
        attributes.set("Amex", amex);

        AwsException refused = assertThrows(AwsException.class, () -> cryptogram.generate(
                request(Map.of(
                        "KeyIdentifier", key.getArn(),
                        "TransactionData", TRANSACTION_DATA,
                        "MajorKeyDerivationMode", "EMV_OPTION_A",
                        "SessionKeyDerivationAttributes", attributes)), "us-east-1"));

        assertEquals("ValidationException", refused.getErrorCode());
        assertTrue(refused.getMessage().contains("not approximated"), refused.getMessage());
    }

    @Test
    void aKeyThatMayNotDeriveIsRefused() {
        PaymentCryptographyKey key = createKey("Sign");

        AwsException refused = assertThrows(AwsException.class, () -> cryptogram.generate(
                request(Map.of(
                        "KeyIdentifier", key.getArn(),
                        "TransactionData", TRANSACTION_DATA,
                        "MajorKeyDerivationMode", "EMV_OPTION_A",
                        "SessionKeyDerivationAttributes", emvCommon())), "us-east-1"));

        assertEquals("InvalidKeyUsageException", refused.getErrorCode());
    }
}
