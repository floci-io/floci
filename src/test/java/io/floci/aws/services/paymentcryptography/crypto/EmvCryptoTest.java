package io.floci.aws.services.paymentcryptography.crypto;

import org.junit.jupiter.api.Test;

import java.util.HexFormat;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * The EMV derivations, against the vectors the specifications publish.
 *
 * <p>The ICC master key is the one EMV 4.4 Book 2 Annex A1.4.1 Option A gives, and the
 * Visa Integrated Circuit Card Specification states the same rule. The session keys and the
 * cryptogram are the worked examples of the M/Chip Card Application Cryptographic
 * Algorithms: the MasterCard proprietary derivation and the common one, with the application
 * cryptogram each produces over the same data.
 */
class EmvCryptoTest {

    /** The issuer master key of the published ICC master key vector. */
    private static final String IMK = "0123456789ABCDEFFEDCBA9876543210";

    /** The account number of the published ICC master key vector. */
    private static final String PAN = "4111111111111111";

    /** The sequence number of the published ICC master key vector. */
    private static final String PSN = "00";

    /** The ICC master key the published vector gives. */
    private static final String UDK = "31089464674C73EC851A6E0737029D19";

    /** The AC master key of the M/Chip worked examples. */
    private static final String MKAC = "08DF34253220A720EFF2C1343852E63D";

    /** The application transaction counter of the M/Chip worked examples. */
    private static final String ATC = "3456";

    /** The terminal unpredictable number of the M/Chip worked examples. */
    private static final String UN = "11111111";

    /** The data the M/Chip worked examples compute the application cryptogram over. */
    private static final String CDOL = """
            00 00 00 01 00 00 00 00 00 00 10 00 08 40 00 00
            00 10 80 08 40 98 07 04 00 11 11 11 11 58 00 34
            56 03 A4 00 20""";

    /** The application cryptogram the M/Chip worked example gives. */
    private static final String ARQC = "DBC1E0B820FFE7DB";

    /** The common session key the M/Chip worked example gives. */
    private static final String COMMON_SESSION_KEY = "182025BA4FAB32F5A63A1BA5E6845D4E";

    @Test
    void theIccMasterKeyIsTheOneTheStandardGives() {
        assertEquals(UDK, hex(EmvCrypto.deriveIccMasterKey(hex(IMK), PAN, PSN)));
    }

    @Test
    void aShortAccountNumberIsPaddedOnTheLeft() {
        // The block is the rightmost sixteen digits of the account number and the sequence
        // number, padded on the left with zeros when they are shorter. So an account number
        // that is two digits short of the field derives the key that the same number with
        // those two zeros derives.
        String shortNumber = "411111111111";
        String paddedNumber = "00" + shortNumber;

        assertArrayEquals(EmvCrypto.deriveIccMasterKey(hex(IMK), paddedNumber, "01"),
                EmvCrypto.deriveIccMasterKey(hex(IMK), shortNumber, "01"));
    }

    @Test
    void theMasterCardSessionKeyAndCryptogramAreTheOnesTheDocumentGives() {
        byte[] sessionKey = EmvCrypto.deriveSessionKeyMastercard(hex(MKAC), hex(ATC), hex(UN));

        assertEquals(ARQC, hex(EmvCrypto.retailMac(sessionKey, hex(CDOL), EmvCrypto.Padding.METHOD2)));
    }

    @Test
    void theCommonSessionKeyIsTheOneTheDocumentGives() {
        byte[] sessionKey = EmvCrypto.deriveSessionKey(hex(MKAC), hex(ATC));

        // The document does not force the parity of the derived key, and the derivation does
        // not affect the computation, so the parity bits are masked on both sides.
        assertEquals(withoutParity(COMMON_SESSION_KEY), withoutParity(hex(sessionKey)));
    }

    /** The bytes of a hexadecimal string, the spaces and the line breaks in it ignored. */
    private static byte[] hex(String value) {
        return HexFormat.of().parseHex(value.replaceAll("\\s", ""));
    }

    /** The hexadecimal of the bytes, in the case the documents print them. */
    private static String hex(byte[] value) {
        return HexFormat.of().withUpperCase().formatHex(value);
    }

    /** The key with the DES parity bits masked, which the derivation does not affect. */
    private static String withoutParity(String key) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < key.length(); i += 2) {
            sb.append(String.format("%02X", Integer.parseInt(key.substring(i, i + 2), 16) & 0xFE));
        }
        return sb.toString();
    }
}
