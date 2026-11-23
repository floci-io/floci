package com.floci.test;

/** Values the Payment Cryptography tests derive from what a key generated. */
final class PaymentCryptographyValues {

    private PaymentCryptographyValues() {
    }

    /**
     * {@code value} with another first digit: a value a test generated under a key of its own, changed so that it
     * can't be that key's, as a fixed value could be once in its range.
     */
    static String withAnotherFirstDigit(String value) {
        return (value.startsWith("0") ? "1" : "0") + value.substring(1);
    }
}
