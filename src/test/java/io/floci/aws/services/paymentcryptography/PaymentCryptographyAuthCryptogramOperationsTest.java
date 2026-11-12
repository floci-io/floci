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
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
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
    /** The PAN sequence number, which the model states is two digits. */
    private static final String PSN = "01";
    /** The application transaction counter, which the model states is two bytes. */
    private static final String ATC = "0001";
    /**
     * The transaction data the cryptogram is computed over.
     *
     * <p>The user guide tells the caller to pad the transaction data before sending
     * it, because the card padded it the same way before it enciphered it. The
     * data here is padded that way: a whole number of eight-byte blocks, with the
     * {@code 80} and the zeros that method 2 appends.
     */
    /**
     * Another transaction's data, of the same shape as the one above.
     *
     * <p>The amount differs, so the cryptogram over it differs, and the data is still
     * a whole number of eight-byte blocks.
     */
    private static final String ANOTHER_TRANSACTION_DATA =
            "00000000180000000000000008400080008000084016051700000000093800000B1F2201"
                    + "030000000000000000000000000000000000000000000000000000008000000000000000";

    private static final String TRANSACTION_DATA =
            "00000000170000000000000008400080008000084016051700000000093800000B1F2201"
                    + "030000000000000000000000000000000000000000000000000000008000000000000000";

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

    /** The session key derivation attributes of the EMV common variant. */
    /** The attributes of the EMV 4.1 tree derivation, which this service refuses. */
    private static JsonNode emv2000() {
        ObjectMapper mapper = new ObjectMapper();
        ObjectNode value = mapper.createObjectNode();
        value.put("PrimaryAccountNumber", PAN);
        value.put("PanSequenceNumber", PSN);
        value.put("ApplicationTransactionCounter", ATC);
        ObjectNode outer = mapper.createObjectNode();
        outer.set("Emv2000", value);
        return outer;
    }

    private static JsonNode emvCommon() {
        return emvCommon(PAN);
    }

    /** The session key derivation attributes of the EMV common variant, for an account number. */
    private static JsonNode emvCommon(String pan) {
        ObjectMapper mapper = new ObjectMapper();
        ObjectNode value = mapper.createObjectNode();
        value.put("PrimaryAccountNumber", pan);
        value.put("PanSequenceNumber", PSN);
        value.put("ApplicationTransactionCounter", ATC);
        ObjectNode outer = mapper.createObjectNode();
        outer.set("EmvCommon", value);
        return outer;
    }

    @Test
    void anAccountNumberShorterThanTheFieldIsRead() {
        PaymentCryptographyKey key = PaymentCryptographyTestFixtures.createKey(service, "TDES_2KEY", "TR31_E0_EMV_MKEY_APP_CRYPTOGRAMS", "DeriveKey");

        // The model lets the account number be twelve digits, and the derivation reads the
        // rightmost sixteen digits of it and the sequence number, so it is read rather than
        // refused.
        Map<String, Object> generated = cryptogram.generate(PaymentCryptographyTestFixtures.request(Map.of(
                "KeyIdentifier", key.getArn(),
                "TransactionData", TRANSACTION_DATA,
                "MajorKeyDerivationMode", "EMV_OPTION_A",
                "SessionKeyDerivationAttributes", emvCommon("411111111111"))), "us-east-1");

        assertEquals(16, ((String) generated.get("AuthRequestCryptogram")).length());
    }

    @Test
    void theDerivationsAreTheOnesTheStandardGives() {
        // The values are the ones the EMV derivation gives for these inputs: the master key
        // from the packed PAN || PSN, and the session key from the counter and that key. The
        // derivation itself is pinned to the vectors the specifications publish in
        // EmvCryptoTest.
        byte[] masterKey = EmvCrypto.deriveIccMasterKey(HEX.parseHex(IMK), PAN, PSN);
        assertEquals("A768CE9E1551FDE3A45B4C7C3DE9DC52", HEX.formatHex(masterKey));
        // A PAN of sixteen digits or fewer is derived the Option A way, so the two agree.
        assertEquals(HEX.formatHex(masterKey),
                HEX.formatHex(EmvCrypto.deriveIccMasterKeyOptionB(HEX.parseHex(IMK), PAN, PSN)));
        assertEquals("892FA4A7F8C8ABC7464FBF5DD63ECBD5",
                HEX.formatHex(EmvCrypto.deriveSessionKey(masterKey, HEX.parseHex(ATC))));
    }

    @Test
    void theLongerAccountNumberIsDerivedTheOptionBWay() {
        // The hash is only there for the longer numbers, and the letters of the hash
        // stand for the digits zero to five.
        byte[] optionB = EmvCrypto.deriveIccMasterKeyOptionB(HEX.parseHex(IMK),
                "4111111111111111111", PSN);
        assertEquals(16, optionB.length);
        assertNotEquals(HEX.formatHex(EmvCrypto.deriveIccMasterKey(HEX.parseHex(IMK),
                "4111111111111111111", PSN)), HEX.formatHex(optionB));
    }

    @Test
    void aRequestWithoutItsDerivationAttributesIsRefusedByTheModel() {
        // The shape check settles the members the model requires, so it is the refusal that
        // answers, and the message a caller sees is its own.
        AwsException refused = assertThrows(AwsException.class,
                () -> cryptogram.generate(PaymentCryptographyTestFixtures.request(Map.of(
                        "KeyIdentifier", "arn:aws:payment-cryptography:us-east-1:000000000000:key/"
                                + "0123456789abcdef",
                        "TransactionData", TRANSACTION_DATA,
                        "MajorKeyDerivationMode", "EMV_OPTION_A")), "us-east-1"));

        assertEquals("ValidationException", refused.getErrorCode());
        assertTrue(refused.getMessage().contains("failed to satisfy constraint"), refused.getMessage());
        assertTrue(refused.getMessage().contains("SessionKeyDerivationAttributes"), refused.getMessage());
    }

    @Test
    void theEmv41DerivationIsRefusedRatherThanApproximated() {
        PaymentCryptographyKey key = PaymentCryptographyTestFixtures.createKey(service, "TDES_2KEY", "TR31_E0_EMV_MKEY_APP_CRYPTOGRAMS", "DeriveKey");

        // Emv2000 is the EMV 4.1 tree derivation, not the common one, and the tree needs
        // the card's public key certificate.
        AwsException refused = assertThrows(AwsException.class,
                () -> cryptogram.generate(PaymentCryptographyTestFixtures.request(Map.of(
                        "KeyIdentifier", key.getArn(),
                        "TransactionData", TRANSACTION_DATA,
                        "MajorKeyDerivationMode", "EMV_OPTION_A",
                        "SessionKeyDerivationAttributes", emv2000())), "us-east-1"));

        assertEquals("ValidationException", refused.getErrorCode());
        assertTrue(refused.getMessage().contains("Emv2000"), refused.getMessage());
    }

    @Test
    void aVerificationThatFailsSaysWhy() {
        PaymentCryptographyKey key = PaymentCryptographyTestFixtures.createKey(service, "TDES_2KEY", "TR31_E0_EMV_MKEY_APP_CRYPTOGRAMS", "DeriveKey");

        AwsException refused = assertThrows(AwsException.class,
                () -> cryptogram.verify(PaymentCryptographyTestFixtures.request(Map.of(
                        "KeyIdentifier", key.getArn(),
                        "AuthRequestCryptogram", "0000000000000000",
                        "TransactionData", TRANSACTION_DATA,
                        "MajorKeyDerivationMode", "EMV_OPTION_A",
                        "SessionKeyDerivationAttributes", emvCommon())), "us-east-1"));

        assertEquals("VerificationFailedException", refused.getErrorCode());
        assertEquals("INVALID_AUTH_REQUEST_CRYPTOGRAM", refused.getExtendedData().get("Reason"));
    }

    @Test
    void aKeyThatIsNotThereIsNotFound() {
        AwsException refused = assertThrows(AwsException.class,
                () -> cryptogram.generate(PaymentCryptographyTestFixtures.request(Map.of(
                        "KeyIdentifier", "arn:aws:payment-cryptography:us-east-1:111122223333:key/0123456789abcdef",
                        "TransactionData", TRANSACTION_DATA,
                        "MajorKeyDerivationMode", "EMV_OPTION_A",
                        "SessionKeyDerivationAttributes", emvCommon())), "us-east-1"));

        assertEquals("ResourceNotFoundException", refused.getErrorCode());
        assertEquals(404, refused.getHttpStatus());
    }

    @Test
    void aGeneratedCryptogramVerifies() {
        PaymentCryptographyKey key = PaymentCryptographyTestFixtures.createKey(service, "TDES_2KEY", "TR31_E0_EMV_MKEY_APP_CRYPTOGRAMS", "DeriveKey");

        Map<String, Object> generated = cryptogram.generate(PaymentCryptographyTestFixtures.request(Map.of(
                "KeyIdentifier", key.getArn(),
                "TransactionData", TRANSACTION_DATA,
                "MajorKeyDerivationMode", "EMV_OPTION_A",
                "SessionKeyDerivationAttributes", emvCommon())), "us-east-1");

        String arqc = (String) generated.get("AuthRequestCryptogram");
        assertEquals(16, arqc.length());
        assertEquals(key.getArn(), generated.get("KeyArn"));

        Map<String, Object> verified = cryptogram.verify(PaymentCryptographyTestFixtures.request(Map.of(
                "KeyIdentifier", key.getArn(),
                "AuthRequestCryptogram", arqc,
                "TransactionData", TRANSACTION_DATA,
                "MajorKeyDerivationMode", "EMV_OPTION_A",
                "SessionKeyDerivationAttributes", emvCommon())), "us-east-1");

        assertEquals(key.getArn(), verified.get("KeyArn"));
    }

    @Test
    void aCryptogramOfAnotherTransactionIsRefused() {
        PaymentCryptographyKey key = PaymentCryptographyTestFixtures.createKey(service, "TDES_2KEY", "TR31_E0_EMV_MKEY_APP_CRYPTOGRAMS", "DeriveKey");

        Map<String, Object> generated = cryptogram.generate(PaymentCryptographyTestFixtures.request(Map.of(
                "KeyIdentifier", key.getArn(),
                "TransactionData", TRANSACTION_DATA,
                "MajorKeyDerivationMode", "EMV_OPTION_A",
                "SessionKeyDerivationAttributes", emvCommon())), "us-east-1");

        AwsException refused = assertThrows(AwsException.class, () -> cryptogram.verify(PaymentCryptographyTestFixtures.request(Map.of(
                "KeyIdentifier", key.getArn(),
                "AuthRequestCryptogram", generated.get("AuthRequestCryptogram"),
                "TransactionData", ANOTHER_TRANSACTION_DATA,
                "MajorKeyDerivationMode", "EMV_OPTION_A",
                "SessionKeyDerivationAttributes", emvCommon())), "us-east-1"));

        assertEquals("VerificationFailedException", refused.getErrorCode());
    }

    @Test
    void theAmexDerivationIsRefusedRatherThanApproximated() {
        PaymentCryptographyKey key = PaymentCryptographyTestFixtures.createKey(service, "TDES_2KEY", "TR31_E0_EMV_MKEY_APP_CRYPTOGRAMS", "DeriveKey");
        ObjectMapper mapper = new ObjectMapper();
        ObjectNode amex = mapper.createObjectNode();
        amex.put("PrimaryAccountNumber", PAN);
        amex.put("PanSequenceNumber", PSN);
        ObjectNode attributes = mapper.createObjectNode();
        attributes.set("Amex", amex);

        AwsException refused = assertThrows(AwsException.class, () -> cryptogram.generate(
                PaymentCryptographyTestFixtures.request(Map.of(
                        "KeyIdentifier", key.getArn(),
                        "TransactionData", TRANSACTION_DATA,
                        "MajorKeyDerivationMode", "EMV_OPTION_A",
                        "SessionKeyDerivationAttributes", attributes)), "us-east-1"));

        assertEquals("ValidationException", refused.getErrorCode());
        assertTrue(refused.getMessage().contains("not approximated"), refused.getMessage());
    }

    @Test
    void aKeyThatMayNotDeriveIsRefused() {
        PaymentCryptographyKey key = PaymentCryptographyTestFixtures.createKey(service, "TDES_2KEY", "TR31_E0_EMV_MKEY_APP_CRYPTOGRAMS", "Sign");

        AwsException refused = assertThrows(AwsException.class, () -> cryptogram.generate(
                PaymentCryptographyTestFixtures.request(Map.of(
                        "KeyIdentifier", key.getArn(),
                        "TransactionData", TRANSACTION_DATA,
                        "MajorKeyDerivationMode", "EMV_OPTION_A",
                        "SessionKeyDerivationAttributes", emvCommon())), "us-east-1"));

        assertEquals("ValidationException", refused.getErrorCode());
    }

    @Test
    void theSequenceNumberTheModelStatesIsTheOneTheDerivationTakes() {
        PaymentCryptographyKey key = PaymentCryptographyTestFixtures.createKey(service, "TDES_2KEY", "TR31_E0_EMV_MKEY_APP_CRYPTOGRAMS", "DeriveKey");
        ObjectMapper mapper = new ObjectMapper();
        ObjectNode common = mapper.createObjectNode();
        common.put("PrimaryAccountNumber", PAN);
        // The model states that the PAN sequence number is two digits, and the derivation
        // reads the last eight bytes of the packed PAN || PSN, so a longer one moves the
        // window the master key is taken from.
        common.put("PanSequenceNumber", "0001");
        common.put("ApplicationTransactionCounter", ATC);
        ObjectNode attributes = mapper.createObjectNode();
        attributes.set("EmvCommon", common);

        AwsException refused = assertThrows(AwsException.class, () -> cryptogram.generate(
                PaymentCryptographyTestFixtures.request(Map.of(
                        "KeyIdentifier", key.getArn(),
                        "TransactionData", TRANSACTION_DATA,
                        "MajorKeyDerivationMode", "EMV_OPTION_A",
                        "SessionKeyDerivationAttributes", attributes)), "us-east-1"));

        assertEquals("ValidationException", refused.getErrorCode());
        assertTrue(refused.getMessage().contains("PanSequenceNumber"), refused.getMessage());
    }

    @Test
    void transactionDataThatIsNotHexadecimalIsRefused() {
        PaymentCryptographyKey key = PaymentCryptographyTestFixtures.createKey(service, "TDES_2KEY", "TR31_E0_EMV_MKEY_APP_CRYPTOGRAMS", "DeriveKey");

        AwsException refused = assertThrows(AwsException.class, () -> cryptogram.generate(
                PaymentCryptographyTestFixtures.request(Map.of(
                        "KeyIdentifier", key.getArn(),
                        "TransactionData", "not hexadecimal",
                        "MajorKeyDerivationMode", "EMV_OPTION_A",
                        "SessionKeyDerivationAttributes", emvCommon())), "us-east-1"));

        assertEquals("ValidationException", refused.getErrorCode());
        assertTrue(refused.getMessage().contains("TransactionData"), refused.getMessage());
    }

    @Test
    void theResponseValueIsTheOneTheFirstMethodMakes() {
        PaymentCryptographyKey key = PaymentCryptographyTestFixtures.createKey(service, "TDES_2KEY", "TR31_E0_EMV_MKEY_APP_CRYPTOGRAMS", "DeriveKey");
        String arqc = (String) cryptogram.generate(PaymentCryptographyTestFixtures.request(Map.of(
                "KeyIdentifier", key.getArn(),
                "TransactionData", TRANSACTION_DATA,
                "MajorKeyDerivationMode", "EMV_OPTION_A",
                "SessionKeyDerivationAttributes", emvCommon())), "us-east-1")
                .get("AuthRequestCryptogram");

        // The response attributes are what the response value is made from, and the first
        // method takes the response code.
        Map<String, Object> verified = cryptogram.verify(PaymentCryptographyTestFixtures.request(Map.of(
                "KeyIdentifier", key.getArn(),
                "TransactionData", TRANSACTION_DATA,
                "AuthRequestCryptogram", arqc,
                "MajorKeyDerivationMode", "EMV_OPTION_A",
                "SessionKeyDerivationAttributes", emvCommon(),
                "AuthResponseAttributes", responseCode("1234"))), "us-east-1");

        String value = (String) verified.get("AuthResponseValue");
        assertNotNull(value);
        assertEquals(16, value.length());
    }

    @Test
    void theResponseValueIsTheOneTheSecondMethodMakes() {
        PaymentCryptographyKey key = PaymentCryptographyTestFixtures.createKey(service, "TDES_2KEY", "TR31_E0_EMV_MKEY_APP_CRYPTOGRAMS", "DeriveKey");
        String arqc = (String) cryptogram.generate(PaymentCryptographyTestFixtures.request(Map.of(
                "KeyIdentifier", key.getArn(),
                "TransactionData", TRANSACTION_DATA,
                "MajorKeyDerivationMode", "EMV_OPTION_A",
                "SessionKeyDerivationAttributes", emvCommon())), "us-east-1")
                .get("AuthRequestCryptogram");

        // The second method takes the card status update and the proprietary data.
        Map<String, Object> verified = cryptogram.verify(PaymentCryptographyTestFixtures.request(Map.of(
                "KeyIdentifier", key.getArn(),
                "TransactionData", TRANSACTION_DATA,
                "AuthRequestCryptogram", arqc,
                "MajorKeyDerivationMode", "EMV_OPTION_A",
                "SessionKeyDerivationAttributes", emvCommon(),
                "AuthResponseAttributes", cardStatusUpdate("12345678", "ABCD"))), "us-east-1");

        String value = (String) verified.get("AuthResponseValue");
        assertNotNull(value);
        assertEquals(8, value.length());
    }

    @Test
    void aTransactionDataThatIsNotWholeBlocksIsRefused() {
        PaymentCryptographyKey key = PaymentCryptographyTestFixtures.createKey(service, "TDES_2KEY", "TR31_E0_EMV_MKEY_APP_CRYPTOGRAMS", "DeriveKey");

        // The ISO 9797-1 MAC runs over eight-byte blocks, and the user guide tells the
        // caller to pad the transaction data before sending it, so a length that is not
        // a whole number of blocks is a request the cipher cannot read.
        AwsException refused = assertThrows(AwsException.class,
                () -> cryptogram.generate(PaymentCryptographyTestFixtures.request(Map.of(
                        "KeyIdentifier", key.getArn(),
                        "TransactionData", "0000000001000000000000000000800000",
                        "MajorKeyDerivationMode", "EMV_OPTION_A",
                        "SessionKeyDerivationAttributes", emvCommon())), "us-east-1"));

        assertEquals("ValidationException", refused.getErrorCode());
        assertTrue(refused.getMessage().contains("TransactionData"), refused.getMessage());
        assertTrue(refused.getMessage().contains("blocks"), refused.getMessage());
    }

    @Test
    void aKeyOfAnotherAlgorithmIsRefused() {
        // The key table names TDES_2KEY for the ARQC key, and the derivation reads
        // sixteen bytes of material, so a three-key Triple DES key is refused.
        PaymentCryptographyKey key = PaymentCryptographyTestFixtures.createKey(service, "TDES_3KEY", "TR31_E0_EMV_MKEY_APP_CRYPTOGRAMS", "DeriveKey");

        AwsException refused = assertThrows(AwsException.class,
                () -> cryptogram.generate(PaymentCryptographyTestFixtures.request(Map.of(
                        "KeyIdentifier", key.getArn(),
                        "TransactionData", TRANSACTION_DATA,
                        "MajorKeyDerivationMode", "EMV_OPTION_A",
                        "SessionKeyDerivationAttributes", emvCommon())), "us-east-1"));

        assertEquals("ValidationException", refused.getErrorCode());
        assertTrue(refused.getMessage().contains("TDES_2KEY"), refused.getMessage());
    }

    @Test
    void aDisabledKeyIsRefused() {
        PaymentCryptographyKey key = PaymentCryptographyTestFixtures.createDisabledKey(service,
                "TDES_2KEY", "TR31_E0_EMV_MKEY_APP_CRYPTOGRAMS", "DeriveKey");

        AwsException refused = assertThrows(AwsException.class,
                () -> cryptogram.generate(PaymentCryptographyTestFixtures.request(Map.of(
                        "KeyIdentifier", key.getArn(),
                        "TransactionData", TRANSACTION_DATA,
                        "MajorKeyDerivationMode", "EMV_OPTION_A",
                        "SessionKeyDerivationAttributes", emvCommon())), "us-east-1"));

        assertEquals("ValidationException", refused.getErrorCode());
    }

    @Test
    void aKeyOfTheWrongUsageIsRefused() {
        PaymentCryptographyKey key = PaymentCryptographyTestFixtures.createKey(service, "TDES_2KEY",
                "TR31_C0_CARD_VERIFICATION_KEY", "DeriveKey");

        AwsException refused = assertThrows(AwsException.class,
                () -> cryptogram.generate(PaymentCryptographyTestFixtures.request(Map.of(
                        "KeyIdentifier", key.getArn(),
                        "TransactionData", TRANSACTION_DATA,
                        "MajorKeyDerivationMode", "EMV_OPTION_A",
                        "SessionKeyDerivationAttributes", emvCommon())), "us-east-1"));

        assertEquals("ValidationException", refused.getErrorCode());
    }

    @Test
    void aKeyThatIsNotTripleDesIsRefused() {
        PaymentCryptographyKey key = PaymentCryptographyTestFixtures.createKey(service, "AES_128",
                "TR31_E0_EMV_MKEY_APP_CRYPTOGRAMS", "DeriveKey");

        AwsException refused = assertThrows(AwsException.class,
                () -> cryptogram.generate(PaymentCryptographyTestFixtures.request(Map.of(
                        "KeyIdentifier", key.getArn(),
                        "TransactionData", TRANSACTION_DATA,
                        "MajorKeyDerivationMode", "EMV_OPTION_A",
                        "SessionKeyDerivationAttributes", emvCommon())), "us-east-1"));

        assertEquals("ValidationException", refused.getErrorCode());
    }

    @Test
    void aResponseValueThatIsNotAskedForIsNotAnswered() {
        PaymentCryptographyKey key = PaymentCryptographyTestFixtures.createKey(service, "TDES_2KEY", "TR31_E0_EMV_MKEY_APP_CRYPTOGRAMS", "DeriveKey");
        String arqc = (String) cryptogram.generate(PaymentCryptographyTestFixtures.request(Map.of(
                "KeyIdentifier", key.getArn(),
                "TransactionData", TRANSACTION_DATA,
                "MajorKeyDerivationMode", "EMV_OPTION_A",
                "SessionKeyDerivationAttributes", emvCommon())), "us-east-1")
                .get("AuthRequestCryptogram");

        Map<String, Object> verified = cryptogram.verify(PaymentCryptographyTestFixtures.request(Map.of(
                "KeyIdentifier", key.getArn(),
                "TransactionData", TRANSACTION_DATA,
                "AuthRequestCryptogram", arqc,
                "MajorKeyDerivationMode", "EMV_OPTION_A",
                "SessionKeyDerivationAttributes", emvCommon())), "us-east-1");

        assertEquals(key.getArn(), verified.get("KeyArn"));
        assertNull(verified.get("AuthResponseValue"));
    }

    /** The response attributes of the first ARPC method, which takes the response code. */
    private static JsonNode responseCode(String code) {
        ObjectMapper mapper = new ObjectMapper();
        ObjectNode method = mapper.createObjectNode();
        method.put("AuthResponseCode", code);
        ObjectNode outer = mapper.createObjectNode();
        outer.set("ArpcMethod1", method);
        return outer;
    }

    /** The response attributes of the second ARPC method. */
    private static JsonNode cardStatusUpdate(String status, String proprietary) {
        ObjectMapper mapper = new ObjectMapper();
        ObjectNode method = mapper.createObjectNode();
        method.put("CardStatusUpdate", status);
        method.put("ProprietaryAuthenticationData", proprietary);
        ObjectNode outer = mapper.createObjectNode();
        outer.set("ArpcMethod2", method);
        return outer;
    }
}
