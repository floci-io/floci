package io.github.hectorvent.floci.compat;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FixtureLoaderTest {

    @Test
    void load_signUpHappy_returnsCanonicalShape() throws Exception {
        JsonNode fixture = FixtureLoader.load("sign-up.happy");

        assertEquals("sign-up.happy", fixture.path("name").asText());
        JsonNode response = fixture.path("response");
        assertFalse(response.isMissingNode(), "response present");
        assertFalse(response.path("UserConfirmed").asBoolean(true), "UserConfirmed=false on real AWS");
        JsonNode cdd = response.path("CodeDeliveryDetails");
        assertEquals("EMAIL", cdd.path("DeliveryMedium").asText());
        assertEquals("email", cdd.path("AttributeName").asText());
        assertTrue(cdd.path("Destination").asText().contains("@"), "destination is masked email");
    }

    @Test
    void load_codeMismatchError_returnsErrorShape() throws Exception {
        JsonNode fixture = FixtureLoader.load("confirm-sign-up.error.code-mismatch");

        JsonNode error = fixture.path("error");
        assertFalse(error.isMissingNode());
        assertEquals("CodeMismatchException", error.path("name").asText());
        assertEquals(400, error.path("$metadata").path("httpStatusCode").asInt());
    }

    @Test
    void normalize_stripsVolatileFields() throws Exception {
        JsonNode fixture = FixtureLoader.load("sign-up.happy");
        JsonNode response = fixture.path("response");
        assertNotNull(response.get("$metadata"));

        FixtureLoader.normalize(response);

        // Volatile fields removed from the response
        assertTrue(response.path("$metadata").isMissingNode(), "$metadata removed");
        assertTrue(response.path("UserSub").isMissingNode(), "UserSub removed");
        // Structural fields preserved
        assertFalse(response.path("CodeDeliveryDetails").isMissingNode(), "CDD preserved");
        assertFalse(response.path("UserConfirmed").isMissingNode(), "UserConfirmed preserved");
    }
}
