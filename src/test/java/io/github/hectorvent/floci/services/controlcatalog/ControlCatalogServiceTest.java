package io.github.hectorvent.floci.services.controlcatalog;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.core.storage.AccountAwareStorageBackend;
import io.github.hectorvent.floci.services.controlcatalog.model.ControlDefinition;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ControlCatalogServiceTest {
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final AccountAwareStorageBackend<ControlDefinition> controls =
            AccountAwareStorageBackend.inMemory("000000000000");
    private final ControlCatalogService service = new ControlCatalogService(controls, objectMapper);

    @Test
    void listControlsFiltersByImplementationIdentifierAndProvider() throws Exception {
        var response = service.listControls(objectMapper.readTree("""
                {"Filter":{"Implementations":{"Identifiers":["CT.S3.PV.5"]},"GovernedProviders":["AWS"]}}
                """), null, null);

        assertEquals(1, response.path("Controls").size());
        assertEquals("CT.S3.PV.5", response.path("Controls").get(0).path("Implementation").path("Identifier").asText());
    }

    @Test
    void listControlsRejectsInvalidProviderFilter() throws Exception {
        AwsException error = assertThrows(AwsException.class, () -> service.listControls(
                objectMapper.readTree("{\"Filter\":{\"GovernedProviders\":[\"invalid\"]}}"), null, null));
        assertEquals("ValidationException", error.getErrorCode());
        assertEquals(400, error.getHttpStatus());
    }

    @Test
    void clearRemovesPersistedCatalogAndNextReadReseedsIt() {
        assertTrue(controls.keysForAccount("000000000000").isEmpty());

        service.listControls(objectMapper.createObjectNode(), null, null);
        assertFalse(controls.keysForAccount("000000000000").isEmpty());

        service.clear();
        assertTrue(controls.keysForAccount("000000000000").isEmpty());

        var response = service.listControls(objectMapper.createObjectNode(), null, null);
        assertEquals(6, response.path("Controls").size());
        assertFalse(controls.keysForAccount("000000000000").isEmpty());
    }
}
