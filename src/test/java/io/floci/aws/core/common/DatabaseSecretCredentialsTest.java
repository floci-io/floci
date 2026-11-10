package io.floci.aws.core.common;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DatabaseSecretCredentialsTest {

    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void parsesUsernameAndPassword() {
        Optional<DatabaseSecretCredentials> creds = DatabaseSecretCredentials.parse(
                "{\"username\":\"admin\",\"password\":\"secret123\"}");

        assertTrue(creds.isPresent());
        assertEquals("admin", creds.get().username());
        assertEquals("secret123", creds.get().password());
    }

    @Test
    void fallsBackToUserFieldWhenUsernameIsAbsent() {
        Optional<DatabaseSecretCredentials> creds = DatabaseSecretCredentials.parse(
                "{\"user\":\"postgres\",\"password\":\"secret456\"}");

        assertTrue(creds.isPresent());
        assertEquals("postgres", creds.get().username());
        assertEquals("secret456", creds.get().password());
    }

    @Test
    void parsesWithCustomMapper() {
        Optional<DatabaseSecretCredentials> creds = DatabaseSecretCredentials.parse(
                mapper, "{\"username\":\"admin\",\"password\":\"secret\"}");

        assertTrue(creds.isPresent());
        assertEquals("admin", creds.get().username());
        assertEquals("secret", creds.get().password());
    }

    @Test
    void returnsEmptyForNullOrBlank() {
        assertTrue(DatabaseSecretCredentials.parse((String) null).isEmpty());
        assertTrue(DatabaseSecretCredentials.parse((JsonNode) null).isEmpty());
        assertTrue(DatabaseSecretCredentials.parse("").isEmpty());
        assertTrue(DatabaseSecretCredentials.parse("   ").isEmpty());
    }

    @Test
    void returnsEmptyForMalformedJson() {
        assertTrue(DatabaseSecretCredentials.parse("not-a-json").isEmpty());
    }

    @Test
    void returnsEmptyWhenMissingRequiredFields() {
        assertTrue(DatabaseSecretCredentials.parse("{\"username\":\"admin\"}").isEmpty());
        assertTrue(DatabaseSecretCredentials.parse("{\"password\":\"secret\"}").isEmpty());
        assertTrue(DatabaseSecretCredentials.parse("{\"other\":\"value\"}").isEmpty());
    }

    @Test
    void returnsEmptyForNonObjectNode() throws Exception {
        assertTrue(DatabaseSecretCredentials.parse(mapper.readTree("[\"item\"]")).isEmpty());
    }

    @Test
    void constructorRejectsNull() {
        assertThrows(NullPointerException.class, () -> new DatabaseSecretCredentials(null, "pass"));
        assertThrows(NullPointerException.class, () -> new DatabaseSecretCredentials("user", null));
    }
}
