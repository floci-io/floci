package io.github.hectorvent.floci.services.amazonmq.model;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MqUserTest {

    /**
     * The admin password is a secret: it must never be written to JSON (neither the
     * API response nor StorageBackend's amazonmq-brokers.json). A serialize ->
     * deserialize round-trip therefore drops it, by design — persisting it would
     * store the credential in cleartext.
     */
    @Test
    void passwordIsNeverSerialized() throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        MqUser user = new MqUser("admin", "AdminPass123", true, List.of("admin"));

        String json = mapper.writeValueAsString(user);
        assertFalse(json.contains("AdminPass123"), "password value must not be serialized");
        assertFalse(json.toLowerCase().contains("password"), "password key must not be serialized");

        MqUser roundTripped = mapper.readValue(json, MqUser.class);
        assertNull(roundTripped.getPassword(), "password is in-memory only; not restored from JSON");
        assertEquals("admin", roundTripped.getUsername());
        assertTrue(roundTripped.isConsoleAccess());
        assertEquals(List.of("admin"), roundTripped.getGroups());
    }
}
