package io.floci.aws.services.paymentcryptography.crypto;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.nio.charset.StandardCharsets;
import java.util.HexFormat;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * The DUKPT derivation, against the vectors ANSI X9.24-1 publishes.
 *
 * <p>The vectors are the initial sequence of the standard's annex A.4.2: the device whose
 * base derivation key is the one the standard uses, over the five counters it walks
 * through. The initial key is the same for all of them, because the counter is cleared
 * before the key is derived.
 *
 * <p>The MAC of each counter is the retail MAC over the data the standard names, under
 * the MAC request key, so the vectors hold the derivation and the ISO 9797-1 algorithm 3
 * MAC the data plane computes together.
 */
class DukptCryptoTest {

    /** The base derivation key of the published vectors. */
    private static final String BDK = "0123456789ABCDEFFEDCBA9876543210";

    /** The initial key of the device, the same for every counter. */
    private static final String INITIAL_KEY = "6AC292FAA1315B4D858AB3A3D7D5933A";

    /** The data the published MACs are taken over. */
    private static final byte[] DATA = "4012345678909D987".getBytes(StandardCharsets.US_ASCII);

    @Test
    void theInitialKeyIsTheOneOfTheStandard() {
        assertEquals(INITIAL_KEY, hex(DukptCrypto.deriveIpek(BDK, "FFFF9876543210E00000")));
    }

    @ParameterizedTest
    @CsvSource({"FFFF9876543210E00001", "FFFF9876543210E00005"})
    void theInitialKeyDoesNotDependOnTheCounter(String ksn) {
        assertEquals(INITIAL_KEY, hex(DukptCrypto.deriveIpek(BDK, ksn)));
    }

    @ParameterizedTest
    @CsvSource({
            "FFFF9876543210E00001, 042666B49184CFA368DE9628D0397BC9, 9CCC78173FC4FB64",
            "FFFF9876543210E00002, C46551CEF9FD24B0AA9AD834130D3BC7, F608A9BCA6FFC311",
            "FFFF9876543210E00003, 0DF3D9422ACA56E547676D07AD6BADFA, 20B59A4FEAC937E3",
            "FFFF9876543210E00004, 279C0F6AEED0BE652B2C733E1383AE91, C7BFA6CC44161828",
            "FFFF9876543210E00005, 5F8DC6D2C845C125508DDC048093B83F, 0202B96339022058"
    })
    void theDerivedKeyAndTheMacAreTheOnesOfTheStandard(String ksn, String key, String mac) {
        assertEquals(key, hex(DukptCrypto.deriveTransactionKey(BDK, ksn)));

        byte[] macKey = DukptCrypto.macKey(BDK, ksn);

        assertEquals(mac, hex(Ciphers.iso9797Alg3(macKey, DATA)));
    }

    private static String hex(byte[] bytes) {
        return HexFormat.of().withUpperCase().formatHex(bytes);
    }
}
