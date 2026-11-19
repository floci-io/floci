package io.floci.aws.services.kms;

import io.floci.aws.core.common.AwsException;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class XksCredentialCipherTest {
    private static final String KEY = "MDEyMzQ1Njc4OWFiY2RlZjAxMjM0NTY3ODlhYmNkZWY=";

    @Test
    void encryptsWithFreshNonceAndAuthenticatesContext() {
        XksCredentialCipher cipher = new XksCredentialCipher(KEY);
        String first = cipher.encrypt("secret-value", "account/region/store");
        String second = cipher.encrypt("secret-value", "account/region/store");

        assertNotEquals(first, second);
        assertEquals("secret-value", cipher.decrypt(first, "account/region/store"));
        assertThrows(AwsException.class, () -> cipher.decrypt(first, "another-account/region/store"));
    }

    @Test
    void refusesMissingOrInvalidDeploymentKey() {
        XksCredentialCipher missing = new XksCredentialCipher(null);
        assertThrows(AwsException.class, () -> missing.encrypt("secret-value", "scope"));
        assertThrows(AwsException.class, () -> new XksCredentialCipher("not-base64"));
    }
}
