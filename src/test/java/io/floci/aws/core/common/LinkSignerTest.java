package io.floci.aws.core.common;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LinkSignerTest {

    private static final byte[] KEY = "a key of this process, 32 bytes!".getBytes(StandardCharsets.US_ASCII);

    @Test
    void aSignatureVerifiesForTheSameFieldsAndKeyAndForNothingElse() {
        LinkSigner signer = new LinkSigner(KEY);
        String signature = signer.sign("123456789012", "us-east-1", "j-1", "SHS");

        assertEquals(signature, new LinkSigner(KEY).sign("123456789012", "us-east-1", "j-1", "SHS"));
        assertTrue(signer.verifies(signature, "123456789012", "us-east-1", "j-1", "SHS"));
        assertFalse(signer.verifies(signature, "123456789012", "us-east-1", "j-1", "TEZ"));
        assertFalse(signer.verifies(signature, "123456789012", "us-east-1", "j-1"));
        assertFalse(signer.verifies(null, "123456789012", "us-east-1", "j-1", "SHS"));
        assertFalse(LinkSigner.withNewKey().verifies(signature, "123456789012", "us-east-1", "j-1", "SHS"));
    }
}
