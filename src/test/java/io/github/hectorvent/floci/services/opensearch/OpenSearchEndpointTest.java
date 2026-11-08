package io.github.hectorvent.floci.services.opensearch;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.hectorvent.floci.core.common.RegionResolver;
import io.github.hectorvent.floci.services.opensearch.model.Domain;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class OpenSearchEndpointTest {
    @Test
    void allDomainResponsesReturnAnEndpointWithoutItsScheme() {
        OpenSearchService service = mock(OpenSearchService.class);
        RegionResolver resolver = mock(RegionResolver.class);
        when(resolver.resolveRegion(any())).thenReturn("us-east-1");
        Domain domain = new Domain();
        when(service.createDomain(any(), any(), any(), any(), any(), any(), any(), any())).thenReturn(domain);
        when(service.describeDomain(anyString())).thenReturn(domain);
        when(service.describeDomains(anyList())).thenReturn(List.of(domain));
        when(service.deleteDomain(anyString())).thenReturn(domain);
        OpenSearchController controller = new OpenSearchController(service, resolver, new ObjectMapper());

        String[][] cases = {{"http://host:9200", "host:9200"}, {"HTTPS://host:443", "host:443"},
                {"host", "host"}, {"http://[::1]:9200", "[::1]:9200"}, {null, ""}, {"", ""}};
        for (String[] testCase : cases) {
            domain.setEndpoint(testCase[0]);
            JsonNode create = (JsonNode) controller.createDomain(null, "{\"DomainName\":\"test\"}").getEntity();
            JsonNode describe = (JsonNode) controller.describeDomain(null, "test").getEntity();
            JsonNode bulk = (JsonNode) controller.describeDomains(null, "{\"DomainNames\":[\"test\"]}").getEntity();
            JsonNode delete = (JsonNode) controller.deleteDomain(null, "test").getEntity();
            assertEquals(testCase[1], create.path("DomainStatus").path("Endpoint").asText());
            assertEquals(testCase[1], describe.path("DomainStatus").path("Endpoint").asText());
            assertEquals(testCase[1], bulk.path("DomainStatusList").get(0).path("Endpoint").asText());
            assertEquals(testCase[1], delete.path("DomainStatus").path("Endpoint").asText());
            assertEquals(testCase[0], domain.getEndpoint());
        }
    }
}
