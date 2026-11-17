package io.floci.aws.core.common;

import java.text.Normalizer;

/**
 * SASLprep (RFC 4013) as PostgreSQL applies it to a SCRAM password ({@code pg_saslprep}): an
 * all-ASCII password is taken as it is; any other is mapped (RFC 3454 table B.1 to nothing, table
 * C.1.2 spaces to U+0020), the mapped code points are checked for prohibited and unassigned
 * characters and the bidirectional rules, and the result is their NFKC normalization. As in
 * {@code pg_saslprep}, the checks run before NFKC rather than on its output, so a prohibited
 * character that NFKC would replace, such as U+0341, still fails them. Where PostgreSQL finds the
 * password invalid, it derives the key from the password as given, and so does this.
 *
 * <p>The JDK's character data is of a later Unicode version than RFC 3454's 3.2. A code point
 * unassigned in 3.2 but assigned since, such as most emoji, is prohibited by PostgreSQL, so it
 * falls back to the password as given, while here only code points unassigned in the JDK's version
 * are: a password holding such a character and a mapped or NFKC-changed one prepares differently.
 * The bidirectional categories are likewise the JDK's rather than tables D.1 and D.2.
 */
final class SaslPrep {

    /** RFC 3454 table C.1.2, non-ASCII spaces, mapped to U+0020. */
    private static final int[][] NON_ASCII_SPACES = {
            {0x00A0, 0x00A0}, {0x1680, 0x1680}, {0x2000, 0x200B}, {0x202F, 0x202F}, {0x205F, 0x205F},
            {0x3000, 0x3000},
    };

    /** RFC 3454 table B.1, commonly mapped to nothing. */
    private static final int[][] MAPPED_TO_NOTHING = {
            {0x00AD, 0x00AD}, {0x034F, 0x034F}, {0x1806, 0x1806}, {0x180B, 0x180D}, {0x200B, 0x200D},
            {0x2060, 0x2060}, {0xFE00, 0xFE0F}, {0xFEFF, 0xFEFF},
    };

    /** RFC 4013 section 2.3: RFC 3454 tables C.1.2, C.2.1, C.2.2, C.3, C.4, C.5, C.6, C.7, C.8 and C.9. */
    private static final int[][] PROHIBITED = {
            // C.1.2 non-ASCII spaces
            {0x00A0, 0x00A0}, {0x1680, 0x1680}, {0x2000, 0x200B}, {0x202F, 0x202F}, {0x205F, 0x205F},
            {0x3000, 0x3000},
            // C.2.1 ASCII controls
            {0x0000, 0x001F}, {0x007F, 0x007F},
            // C.2.2 non-ASCII controls
            {0x0080, 0x009F}, {0x06DD, 0x06DD}, {0x070F, 0x070F}, {0x180E, 0x180E}, {0x200C, 0x200D},
            {0x2028, 0x2029}, {0x2060, 0x2063}, {0x206A, 0x206F}, {0xFEFF, 0xFEFF}, {0xFFF9, 0xFFFC},
            {0x1D173, 0x1D17A},
            // C.3 private use
            {0xE000, 0xF8FF}, {0xF0000, 0xFFFFD}, {0x100000, 0x10FFFD},
            // C.4 non-characters
            {0xFDD0, 0xFDEF}, {0xFFFE, 0xFFFF}, {0x1FFFE, 0x1FFFF}, {0x2FFFE, 0x2FFFF}, {0x3FFFE, 0x3FFFF},
            {0x4FFFE, 0x4FFFF}, {0x5FFFE, 0x5FFFF}, {0x6FFFE, 0x6FFFF}, {0x7FFFE, 0x7FFFF}, {0x8FFFE, 0x8FFFF},
            {0x9FFFE, 0x9FFFF}, {0xAFFFE, 0xAFFFF}, {0xBFFFE, 0xBFFFF}, {0xCFFFE, 0xCFFFF}, {0xDFFFE, 0xDFFFF},
            {0xEFFFE, 0xEFFFF}, {0xFFFFE, 0xFFFFF}, {0x10FFFE, 0x10FFFF},
            // C.5 surrogates
            {0xD800, 0xDFFF},
            // C.6 inappropriate for plain text
            {0xFFF9, 0xFFFD},
            // C.7 inappropriate for canonical representation
            {0x2FF0, 0x2FFB},
            // C.8 change display properties or deprecated
            {0x0340, 0x0341}, {0x200E, 0x200F}, {0x202A, 0x202E}, {0x206A, 0x206F},
            // C.9 tagging characters
            {0xE0001, 0xE0001}, {0xE0020, 0xE007F},
    };

    private SaslPrep() {
    }

    /** The password PostgreSQL derives a SCRAM key from: prepared, or as given where that fails. */
    static String prepare(String password) {
        if (password.chars().allMatch(c -> c < 0x80)) {
            return password;
        }
        StringBuilder mapped = new StringBuilder(password.length());
        password.codePoints().forEach(codePoint -> {
            if (in(NON_ASCII_SPACES, codePoint)) {
                mapped.append(' ');
            } else if (!in(MAPPED_TO_NOTHING, codePoint)) {
                mapped.appendCodePoint(codePoint);
            }
        });
        if (mapped.isEmpty()) {
            return password;
        }
        int[] codePoints = mapped.codePoints().toArray();
        boolean rightToLeft = false;
        boolean leftToRight = false;
        for (int codePoint : codePoints) {
            if (in(PROHIBITED, codePoint) || Character.getType(codePoint) == Character.UNASSIGNED) {
                return password;
            }
            rightToLeft |= isRightToLeft(codePoint);
            leftToRight |= Character.getDirectionality(codePoint) == Character.DIRECTIONALITY_LEFT_TO_RIGHT;
        }
        // RFC 3454 section 6: a string with a right-to-left character holds no left-to-right one,
        // and starts and ends with a right-to-left character.
        if (rightToLeft && (leftToRight || !isRightToLeft(codePoints[0])
                || !isRightToLeft(codePoints[codePoints.length - 1]))) {
            return password;
        }
        return Normalizer.normalize(mapped, Normalizer.Form.NFKC);
    }

    private static boolean isRightToLeft(int codePoint) {
        byte directionality = Character.getDirectionality(codePoint);
        return directionality == Character.DIRECTIONALITY_RIGHT_TO_LEFT
                || directionality == Character.DIRECTIONALITY_RIGHT_TO_LEFT_ARABIC;
    }

    private static boolean in(int[][] ranges, int codePoint) {
        for (int[] range : ranges) {
            if (codePoint >= range[0] && codePoint <= range[1]) {
                return true;
            }
        }
        return false;
    }
}
