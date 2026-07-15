package io.github.hectorvent.floci.services.appsync.graphql;

import com.fasterxml.jackson.databind.ObjectMapper;
import graphql.GraphQL;
import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.services.appsync.AppSyncService;
import io.github.hectorvent.floci.services.appsync.model.GraphqlApi;
import jakarta.ws.rs.core.HttpHeaders;
import jakarta.ws.rs.core.Response;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AppSyncExecutionControllerTest {

    @Mock
    AppSyncService appSyncService;
    @Mock
    SchemaRegistry schemaRegistry;
    @Mock
    QueryExecutor queryExecutor;

    private AppSyncExecutionController controller;
    private HttpHeaders jsonHeaders;

    @BeforeEach
    void setUp() {
        controller = new AppSyncExecutionController(
                appSyncService,
                schemaRegistry,
                queryExecutor,
                new AppSyncErrorFormatter(),
                new ObjectMapper());

        jsonHeaders = mock(HttpHeaders.class);
        when(jsonHeaders.getHeaderString(HttpHeaders.CONTENT_TYPE)).thenReturn("application/json");
    }

    @Test
    void unexpectedExecutorFailureReturns500InternalFailure() {
        when(appSyncService.getGraphqlApi("api-1")).thenReturn(new GraphqlApi());
        when(schemaRegistry.getGraphQL("api-1")).thenReturn(Optional.of(mock(GraphQL.class)));
        when(queryExecutor.execute(any(GraphQL.class), eq("{ hello }"), isNull(), isNull()))
                .thenThrow(new RuntimeException("boom"));

        Response response = controller.execute("api-1", jsonHeaders, "{\"query\":\"{ hello }\"}");

        assertEquals(500, response.getStatus());
        assertEquals("InternalFailure", response.getHeaderString("x-amzn-errortype"));
        @SuppressWarnings("unchecked")
        Map<String, Object> body = (Map<String, Object>) response.getEntity();
        assertNotNull(body.get("errors"));
        @SuppressWarnings("unchecked")
        Map<String, Object> error = ((List<Map<String, Object>>) body.get("errors")).get(0);
        assertEquals("InternalFailure", error.get("errorType"));
        assertEquals("boom", error.get("message"));
    }

    @Test
    void emptyBodyReturns400WithErrorTypeHeader() {
        Response response = controller.execute("api-1", jsonHeaders, "");

        assertEquals(400, response.getStatus());
        assertEquals("MalformedHttpRequestException", response.getHeaderString("x-amzn-errortype"));
        @SuppressWarnings("unchecked")
        Map<String, Object> body = (Map<String, Object>) response.getEntity();
        @SuppressWarnings("unchecked")
        Map<String, Object> error = ((List<Map<String, Object>>) body.get("errors")).get(0);
        assertEquals("MalformedHttpRequestException", error.get("errorType"));
        assertEquals(AppSyncErrorFormatter.MSG_EMPTY_BODY, error.get("message"));
    }

    @Test
    void unknownApiReturns404WithErrorTypeHeader() {
        when(appSyncService.getGraphqlApi("missing"))
                .thenThrow(new AwsException("NotFoundException", "API not found", 404));

        Response response = controller.execute("missing", jsonHeaders, "{\"query\":\"{ hello }\"}");

        assertEquals(404, response.getStatus());
        assertEquals("NotFoundException", response.getHeaderString("x-amzn-errortype"));
        @SuppressWarnings("unchecked")
        Map<String, Object> body = (Map<String, Object>) response.getEntity();
        @SuppressWarnings("unchecked")
        Map<String, Object> error = ((List<Map<String, Object>>) body.get("errors")).get(0);
        assertEquals("NotFoundException", error.get("errorType"));
    }
}
