package io.github.hectorvent.floci.services.ecr;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** How {@link EcrJsonHandler} reads the requests it hands to {@link EcrService}. */
class EcrJsonHandlerTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    /**
     * AWS's {@code BatchGetRepositoryScanningConfiguration} request has no {@code registryId}, so the repositories
     * are the caller's whatever one the request carries, as IAM checks them.
     */
    @Test
    void theScanningConfigurationIsOfTheCallersRepositories() throws Exception {
        EcrService service = mock(EcrService.class);
        when(service.batchGetRepositoryScanningConfiguration(List.of("web"), null, "us-east-1"))
                .thenReturn(new EcrService.BatchScanningConfigurationResult(List.of(), List.of()));
        EcrJsonHandler handler = new EcrJsonHandler(service, MAPPER);

        assertEquals(200, handler.handle("BatchGetRepositoryScanningConfiguration",
                MAPPER.readTree("{\"registryId\":\"111122223333\",\"repositoryNames\":[\"web\"]}"), "us-east-1")
                .getStatus());
        verify(service).batchGetRepositoryScanningConfiguration(List.of("web"), null, "us-east-1");
    }
}
