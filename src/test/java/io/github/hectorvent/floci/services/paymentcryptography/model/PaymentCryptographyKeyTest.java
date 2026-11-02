package io.github.hectorvent.floci.services.paymentcryptography.model;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * How a key is written to the store.
 */
class PaymentCryptographyKeyTest {

    /**
     * The material of a key is held by the key while the emulator runs, and is
     * not written to the store: no operation of the service returns it, so
     * leaving it out of the persisted key keeps it off the disk.
     */
    @Test
    void theMaterialOfAKeyIsNotWrittenToTheStore() throws Exception {
        PaymentCryptographyKey key = new PaymentCryptographyKey();
        key.setKeyId("abc123");
        key.setKeyMaterialHex("0f1e2d3c4b5a69788796a5b4c3d2e1f0");
        key.setKeyCheckValue("7AD386");

        String written = new ObjectMapper().writeValueAsString(key);

        assertFalse(written.contains("keyMaterialHex"), written);
        assertFalse(written.contains("0f1e2d3c4b5a69788796a5b4c3d2e1f0"), written);
        assertTrue(written.contains("keyCheckValue"), written);
    }
}
