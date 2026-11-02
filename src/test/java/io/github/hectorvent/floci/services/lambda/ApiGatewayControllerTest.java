package io.github.hectorvent.floci.services.lambda;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.hectorvent.floci.core.common.RegionResolver;
import io.github.hectorvent.floci.services.cloudwatch.logs.CloudWatchLogsService;
import io.github.hectorvent.floci.services.lambda.model.InvocationType;
import io.github.hectorvent.floci.services.lambda.model.InvokeResult;
import jakarta.ws.rs.core.HttpHeaders;
import jakarta.ws.rs.core.MultivaluedHashMap;
import jakarta.ws.rs.core.UriInfo;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The {@code /_api/{functionName}} proxy packages a request as an API Gateway v1 proxy event and
 * invokes the function with it.
 */
class ApiGatewayControllerTest {

    private static final String REGION = "us-east-1";
    private static final String FUNCTION = "my-function";

    @Test
    void deleteRequestBodyIsPassedToTheFunction() {
        JsonNode eventNode = deleteEventFor("{\"ids\":[\"a\",\"b\"]}".getBytes(StandardCharsets.UTF_8));

        assertEquals("DELETE", eventNode.get("httpMethod").asText());
        assertEquals("{\"ids\":[\"a\",\"b\"]}", eventNode.get("body").asText());
    }

    @Test
    void deleteWithoutBodyHasNullBody() {
        JsonNode eventNode = deleteEventFor(new byte[0]);

        assertTrue(eventNode.get("body").isNull());
    }

    private JsonNode deleteEventFor(byte[] body) {
        LambdaService lambdaService = mock(LambdaService.class);
        InvokeResult invokeResult = new InvokeResult();
        invokeResult.setStatusCode(200);
        invokeResult.setPayload("{\"statusCode\":204}".getBytes(StandardCharsets.UTF_8));
        when(lambdaService.invoke(eq(REGION), eq(FUNCTION), any(byte[].class), eq(InvocationType.RequestResponse)))
                .thenReturn(invokeResult);

        HttpHeaders headers = mock(HttpHeaders.class);
        MultivaluedHashMap<String, String> requestHeaders = new MultivaluedHashMap<>();
        requestHeaders.putSingle("content-type", "application/json");
        when(headers.getRequestHeaders()).thenReturn(requestHeaders);

        UriInfo uriInfo = mock(UriInfo.class);
        when(uriInfo.getQueryParameters()).thenReturn(new MultivaluedHashMap<>());

        RegionResolver regionResolver = mock(RegionResolver.class);
        when(regionResolver.resolveRegion(headers)).thenReturn(REGION);

        ApiGatewayController controller = new ApiGatewayController(
                lambdaService, regionResolver, new ObjectMapper(), mock(CloudWatchLogsService.class));
        controller.handleDelete(headers, uriInfo, FUNCTION, "items", body);

        ArgumentCaptor<byte[]> event = ArgumentCaptor.forClass(byte[].class);
        verify(lambdaService).invoke(eq(REGION), eq(FUNCTION), event.capture(), eq(InvocationType.RequestResponse));
        try {
            return new ObjectMapper().readTree(event.getValue());
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }
}
