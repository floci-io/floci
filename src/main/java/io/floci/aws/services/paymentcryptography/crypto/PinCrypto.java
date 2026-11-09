package io.floci.aws.services.paymentcryptography.crypto;

import java.util.HexFormat;
import java.util.Random;

/**
 * Issuer PIN primitives: the ISO 9564-1 PIN blocks and their decoders.
 *
 * <p>The account field is the 12 right-most PAN digits <em>excluding the check
 * digit</em>. The API takes a primary account number, which carries its own
 * check digit, so the digit is dropped and the twelve right-most of what is
 * left are the field. A PAN of twelve digits leaves eleven, and the field is
 * padded with a zero on the left.
 *
 * <p>Format 4 is the odd one out. It does not use the account field at all:
 * it carries the whole PAN, and it is the only format an AES key can protect.
 *
 * <p>The blocks are enciphered in ECB mode, which is what ISO 9564-1 defines: one block
 * goes in and one block comes out. The ciphers are BouncyCastle's, so the block that goes
 * in and the block that comes out are the ones the standard is written over.
 */
public final class PinCrypto {

    /** The hexadecimal form the blocks are written in: upper case, no separators. */
    private static final HexFormat HEX = HexFormat.of().withUpperCase();

    private PinCrypto() {
    }

    /**
     * The 12-digit ISO-0 account field.
     *
     * <p>The field is the twelve right-most digits of the PAN without its check
     * digit, so the digit is dropped and the twelve right-most of what is left
     * are the field. A PAN of twelve digits leaves eleven, and the field is
     * padded with a zero on the left.
     *
     * @throws IllegalArgumentException if the PAN has fewer than twelve digits,
     *         which is fewer than the field holds
     */
    public static String account12(String pan) {
        String d = digits(pan);
        if (d.length() < 12) {
            throw new IllegalArgumentException("the account field is twelve digits, and the PAN "
                    + "has fewer");
        }
        String withoutCheckDigit = d.substring(0, d.length() - 1);
        if (withoutCheckDigit.length() >= 12) {
            return withoutCheckDigit.substring(withoutCheckDigit.length() - 12);
        }
        return "0" + withoutCheckDigit;
    }

    /**
     * The ISO 9564-1 Format-0 clear PIN block, as 16 upper-case hex characters.
     *
     * <p>Layout: a filler nibble, the PIN length as one hex digit, the PIN
     * digits, then {@code F} padding to 16 characters. XOR that against
     * {@code 0000} followed by the account field.
     */
    public static String iso0PinBlock(String pin, String pan) {
        String p = digits(pin);
        if (p.length() < 4 || p.length() > 12) {
            throw new IllegalArgumentException("PIN length must be 4..12");
        }
        StringBuilder pinField = new StringBuilder();
        pinField.append('0');
        pinField.append(Integer.toHexString(p.length()).toUpperCase());
        pinField.append(p);
        while (pinField.length() < 16) {
            pinField.append('F');
        }
        String panField = "0000" + account12(pan);
        return HEX.formatHex(Ciphers.xor(
                HEX.parseHex(pinField.toString()), HEX.parseHex(panField)));
    }

    /**
     * Recover the clear PIN digits from a Format-0 or Format-3 block.
     *
     * <p>The block is checked before the PIN is read out of it. The format nibble says which
     * format the block is, the length nibble says how long the PIN is, the digits have to be
     * digits, and the fill has to be what the format says it is. A block that is not checked
     * would give a PIN out of a block of another format, and a block that was made up would
     * pass as one that was not.
     *
     * @param blockHex the clear block, as hexadecimal
     * @param pan the account number the block is bound to
     * @param format the format the block is in
     * @return the PIN the block carries
     */
    public static String decodeIso0PinBlock(String blockHex, String pan, PinBlockFormat format) {
        String panField = "0000" + account12(pan);
        String pinField = HEX.formatHex(Ciphers.xor(HEX.parseHex(blockHex), HEX.parseHex(panField)));
        if (pinField.length() != 16) {
            throw new IllegalArgumentException("the PIN block is " + pinField.length()
                    + " characters; it is 16");
        }
        char formatNibble = pinField.charAt(0);
        char wanted = format == PinBlockFormat.ISO_FORMAT_3 ? '3' : '0';
        if (formatNibble != wanted) {
            throw new IllegalArgumentException("the PIN block is a format " + formatNibble
                    + " block, and a " + format.label() + " block was expected");
        }
        int len = Character.digit(pinField.charAt(1), 16);
        if (len < 4 || len > 12) {
            throw new IllegalArgumentException("the PIN block says the PIN is " + len
                    + " digits; it is 4 to 12");
        }
        String pin = pinField.substring(2, 2 + len);
        for (int i = 0; i < pin.length(); i++) {
            if (!Character.isDigit(pin.charAt(i))) {
                throw new IllegalArgumentException("the PIN block does not carry digits");
            }
        }
        for (int i = 2 + len; i < pinField.length(); i++) {
            char fill = pinField.charAt(i);
            // Format 0 fills with F, and format 3 with a value in the range A to F.
            boolean good = format == PinBlockFormat.ISO_FORMAT_3
                    ? fill >= 'A' && fill <= 'F'
                    : fill == 'F';
            if (!good) {
                throw new IllegalArgumentException("the PIN block does not carry the fill of a "
                        + format.label() + " block");
            }
        }
        return pin;
    }

    // ---- ISO 9564-1 Format 3 ------------------------------------------------

    /**
     * The ISO 9564-1 Format-3 clear PIN block, as 16 upper-case hex characters.
     *
     * <p>Format 3 is format 0 with two differences: the control nibble is
     * {@code 3}, and the fill nibbles are random values in the range A to F
     * rather than a constant F. The random fill is the point of the format -
     * it stops the same PIN from producing the same ciphertext twice.
     *
     * @param random the source of the fill nibbles
     */
    public static String iso3PinBlock(String pin, String pan, Random random) {
        String p = digits(pin);
        if (p.length() < 4 || p.length() > 12) {
            throw new IllegalArgumentException("PIN length must be 4..12");
        }
        StringBuilder pinField = new StringBuilder();
        pinField.append('3');
        pinField.append(Integer.toHexString(p.length()).toUpperCase());
        pinField.append(p);
        while (pinField.length() < 16) {
            // 10 to 15, which is A to F.
            pinField.append(Integer.toHexString(10 + random.nextInt(6)).toUpperCase());
        }
        String panField = "0000" + account12(pan);
        return HEX.formatHex(Ciphers.xor(
                HEX.parseHex(pinField.toString()), HEX.parseHex(panField)));
    }

    // ---- ISO 9564-1 Format 4 ------------------------------------------------

    /**
     * The ISO 9564-1 Format-4 plain-text PIN field: 16 bytes.
     *
     * <p>Layout: the control nibble {@code 4}, the PIN length, the PIN digits,
     * {@code A} fill up to 16 nibbles, then 8 random bytes. The random half is
     * what makes every block unique, so the same PIN never enciphers to the
     * same ciphertext twice.
     *
     * @param randomPad 8 random bytes
     */
    public static byte[] iso4PinField(String pin, byte[] randomPad) {
        String p = digits(pin);
        if (p.length() < 4 || p.length() > 12) {
            throw new IllegalArgumentException("PIN length must be 4..12");
        }
        if (randomPad == null || randomPad.length != 8) {
            throw new IllegalArgumentException("the random pad must be 8 bytes");
        }
        StringBuilder pinField = new StringBuilder();
        pinField.append('4');
        pinField.append(Integer.toHexString(p.length()).toUpperCase());
        pinField.append(p);
        while (pinField.length() < 16) {
            pinField.append('A');
        }
        pinField.append(HEX.formatHex(randomPad));
        return HEX.parseHex(pinField.toString());
    }

    /**
     * The ISO 9564-1 Format-4 plain-text PAN field: 16 bytes.
     *
     * <p>Layout: the M nibble, which records the PAN length as 12 plus its own
     * value, then the PAN digits, then zero padding to 32 nibbles. A PAN
     * shorter than 12 digits is right justified and padded to the left with
     * zeros, and M is then zero.
     *
     * <p>The field carries the whole PAN, the check digit included, which is
     * what the standard says: the format 4 PAN field is the complete PAN,
     * where the account field of the formats 0 and 3 is the twelve right-most
     * digits without the check digit.
     *
     * <p>The M nibble is four bits, so the PAN it records may be up to twenty
     * seven digits, which is what the field holds beside the nibble.
     */
    public static byte[] iso4PanField(String pan) {
        String d = digits(pan);
        if (d.isEmpty() || d.length() > 27) {
            throw new IllegalArgumentException("the PAN field holds up to twenty seven digits");
        }
        StringBuilder panField = new StringBuilder();
        panField.append(Integer.toHexString(Math.max(0, d.length() - 12)));
        String panDigits = d;
        while (panDigits.length() < 12) {
            panDigits = "0" + panDigits;
        }
        panField.append(panDigits);
        while (panField.length() < 32) {
            panField.append('0');
        }
        return HEX.parseHex(panField.toString());
    }

    /**
     * The encrypted ISO 9564-1 Format-4 PIN block, as 32 upper-case hex
     * characters.
     *
     * <p>The PIN field is enciphered, XORed with the PAN field, and the result
     * enciphered again. Two passes, because one pass over the PIN field alone
     * would not bind the PIN to the account.
     *
     * @param key the AES PIN encryption key
     * @param randomPad 8 random bytes for the random half of the PIN field
     */
    public static String iso4PinBlock(String pin, String pan, byte[] key, byte[] randomPad) {
        byte[] intermediateA = Ciphers.aesEcbEncrypt(iso4PinField(pin, randomPad), key);
        byte[] intermediateB = Ciphers.xor(intermediateA, iso4PanField(pan));
        return HEX.formatHex(Ciphers.aesEcbEncrypt(intermediateB, key));
    }

    /**
     * The encrypted ISO 9564-1 Format-4 PIN block, with the random half drawn
     * from {@code random}.
     */
    public static String iso4PinBlock(String pin, String pan, byte[] key, Random random) {
        byte[] randomPad = new byte[8];
        random.nextBytes(randomPad);
        return iso4PinBlock(pin, pan, key, randomPad);
    }

    /**
     * Recover the clear PIN digits from an encrypted Format-4 block.
     *
     * @return the PIN digits, or null when the block does not carry a
     *         well-formed Format-4 PIN field. A wrong key or a wrong PAN
     *         produces exactly that, so the caller reports a failed
     *         verification rather than a bad request.
     */
    public static String decodeIso4PinBlock(String blockHex, String pan, byte[] key) {
        byte[] intermediateB = Ciphers.aesEcbDecrypt(HEX.parseHex(blockHex), key);
        byte[] intermediateA = Ciphers.xor(intermediateB, iso4PanField(pan));
        String pinField = HEX.formatHex(Ciphers.aesEcbDecrypt(intermediateA, key));

        if (pinField.charAt(0) != '4') {
            return null;
        }
        int len = Character.digit(pinField.charAt(1), 16);
        if (len < 4 || len > 12) {
            return null;
        }
        String pin = pinField.substring(2, 2 + len);
        if (!pin.matches("[0-9]{4,12}")) {
            return null;
        }
        // The fill between the PIN and the random half is fixed at A.
        StringBuilder fill = new StringBuilder();
        for (int i = 0; i < 14 - len; i++) {
            fill.append('A');
        }
        if (!pinField.substring(2 + len, 16).equals(fill.toString())) {
            return null;
        }
        return pin;
    }

    // ---- format dispatch ----------------------------------------------------

    /**
     * Encipher a PIN into a PIN block of the given format.
     *
     * @param format the ISO 9564-1 format
     * @param pin the clear PIN
     * @param pan the PAN in the form the format takes: a whole PAN for format 4,
     *            and anything the account field can be derived from for
     *            formats 0 and 3
     * @param key the PIN encryption key: TDES for formats 0 and 3, AES for
     *            format 4
     * @param random the source of the random fill
     */
    public static String encipherPinBlock(PinBlockFormat format, String pin, String pan,
                                          byte[] key, Random random) {
        switch (format) {
            case ISO_FORMAT_0:
                return HEX.formatHex(Ciphers.tdes3Encrypt(
                        HEX.parseHex(iso0PinBlock(pin, pan)), key));
            case ISO_FORMAT_3:
                return HEX.formatHex(Ciphers.tdes3Encrypt(
                        HEX.parseHex(iso3PinBlock(pin, pan, random)), key));
            case ISO_FORMAT_4:
                return iso4PinBlock(pin, pan, key, random);
            default:
                throw new IllegalArgumentException("unsupported PIN block format: " + format);
        }
    }

    /**
     * Decipher a PIN block of the given format and recover the PIN.
     *
     * @param pan the PAN in the form the format takes
     * @param key the PIN encryption key
     * @return the PIN digits, or null when the block does not carry a
     *         well-formed PIN field
     */
    public static String decipherPinBlock(PinBlockFormat format, String blockHex, String pan,
                                          byte[] key) {
        switch (format) {
            case ISO_FORMAT_0:
            case ISO_FORMAT_3:
                return decodeIso0PinBlock(
                        HEX.formatHex(Ciphers.tdesEcbDecrypt(HEX.parseHex(blockHex), key)), pan,
                        format);
            case ISO_FORMAT_4:
                return decodeIso4PinBlock(blockHex, pan, key);
            default:
                throw new IllegalArgumentException("unsupported PIN block format: " + format);
        }
    }

    /**
     * The Visa PVV, the standard algorithm.
     *
     * <pre>
     *   TSP (16 digits) = 11 rightmost PAN digits excluding the check digit
     *                    + 1 PVKI digit + 4 PIN digits
     *   PVV = decimalize(3DES_EDE(PVK, TSP as an 8-byte block), 4)
     * </pre>
     *
     * <p>{@code pvkHex} is a double-length (16-byte) key.
     */
    public static String computePvv(String pan, int pvki, String pin, String pvkHex) {
        String d = digits(pan);
        String pan11 = tspDigits(d);
        String p4 = digits(pin);
        if (p4.length() != 4) {
            throw new IllegalArgumentException("Visa PVV uses a 4-digit PIN");
        }
        String tsp = pan11 + pvki + p4;
        if (tsp.length() != 16) {
            throw new IllegalArgumentException("TSP must be 16 digits, got " + tsp.length());
        }
        return Decimalize.of(
                HEX.formatHex(Ciphers.tdes2Encrypt(HEX.parseHex(tsp), HEX.parseHex(pvkHex))), 4);
    }

    /**
     * The 11 digits of the TSP: the 11 right-most digits of the account
     * number, excluding the check digit.
     *
     * <p>The API takes a primary account number, which carries its own check
     * digit, so the digit is dropped and the eleven right-most of what is left
     * are the TSP's.
     */
    private static String tspDigits(String pan) {
        return pan.substring(0, pan.length() - 1)
                .substring(pan.length() - 1 - 11);
    }

    /** Strip everything that is not a decimal digit. */
    public static String digits(String s) {
        if (s == null) {
            return "";
        }
        StringBuilder sb = new StringBuilder(s.length());
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c >= '0' && c <= '9') {
                sb.append(c);
            }
        }
        return sb.toString();
    }
}
