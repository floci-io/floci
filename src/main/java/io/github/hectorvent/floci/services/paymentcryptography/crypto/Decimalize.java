package io.github.hectorvent.floci.services.paymentcryptography.crypto;

/**
 * Decimalize: reduce a hex ciphertext to a fixed number of decimal digits.
 *
 * <p>Scan left to right taking the {@code 0}-{@code 9} nibbles; if that yields
 * fewer than the requested count, make a second pass taking each {@code A}-{@code F}
 * nibble as its value minus ten. Used by both the Visa PVV and the card
 * verification value, which share the routine in the JavaScript.
 */
public final class Decimalize {

    private Decimalize() {
    }

    public static String of(String hex, int n) {
        StringBuilder out = new StringBuilder(n);
        for (int i = 0; i < hex.length() && out.length() < n; i++) {
            char c = hex.charAt(i);
            if (c >= '0' && c <= '9') {
                out.append(c);
            }
        }
        if (out.length() < n) {
            for (int i = 0; i < hex.length() && out.length() < n; i++) {
                int v = Character.digit(hex.charAt(i), 16);
                if (v >= 10) {
                    out.append(v - 10);
                }
            }
        }
        return out.toString();
    }
}
