package io.github.hectorvent.floci.core.common;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.hectorvent.floci.services.sqs.SqsJsonHandler;
import jakarta.ws.rs.core.HttpHeaders;
import jakarta.ws.rs.core.Response;
import org.junit.jupiter.api.Test;

import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class AwsJsonControllerSerializationTest {

    @Test
    void sqsSerializationFailurePreservesOriginalResponse() throws Exception {
        ObjectMapper mapper = new ObjectMapper() {
            @Override
            public byte[] writeValueAsBytes(Object value) throws JsonProcessingException {
                throw new JsonProcessingException("expected serialization failure") {};
            }
        };
        ResolvedServiceCatalog catalog = mock(ResolvedServiceCatalog.class);
        ServiceDescriptor descriptor = new ServiceDescriptor("sqs", "sqs", true, true,
                "sqs", "memory", 0, null, ServiceProtocol.QUERY, Set.of(ServiceProtocol.JSON),
                Set.of("AmazonSQS."), Set.of("sqs"), Set.of(), Set.of(), Set.of());
        when(catalog.matchTarget("AmazonSQS.Test"))
                .thenReturn(Optional.of(new ServiceCatalog.TargetMatch(descriptor, "AmazonSQS.", "Test")));
        RegionResolver regionResolver = mock(RegionResolver.class);
        HttpHeaders headers = mock(HttpHeaders.class);
        when(regionResolver.resolveRegion(headers)).thenReturn("us-east-1");
        SqsJsonHandler sqs = mock(SqsJsonHandler.class);
        Object entity = new Object();
        Response original = Response.status(202).entity(entity)
                .header("x-amzn-query-error", "example;Sender").build();
        when(sqs.handle(eq("Test"), any(), eq("us-east-1"))).thenReturn(original);

        AwsJsonController controller = new AwsJsonController(mapper, catalog, regionResolver,
                null, null, sqs, null, null, null,
                null, null, null, null, null, null, null, null);
        Response result = controller.handleJsonRequest("AmazonSQS.Test", headers, "{}");

        assertSame(original, result);
        assertEquals(202, result.getStatus());
        assertSame(entity, result.getEntity());
        assertEquals("example;Sender", result.getHeaderString("x-amzn-query-error"));
    }
}
