package io.github.hectorvent.floci.services.controlcatalog;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.hectorvent.floci.core.common.AwsException;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class ControlCatalogServiceTest {
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final ControlCatalogService service = new ControlCatalogService(objectMapper);

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
}
