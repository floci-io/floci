package io.github.hectorvent.floci.services.appsync.graphql;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.core.common.RequestContext;
import io.github.hectorvent.floci.services.appsync.AppSyncService;
import io.github.hectorvent.floci.services.appsync.graphql.auth.AppSyncAuth;
import io.github.hectorvent.floci.services.appsync.graphql.auth.AppSyncAuthContext;
import io.github.hectorvent.floci.services.appsync.graphql.auth.AuthMiddleware;
import io.github.hectorvent.floci.services.appsync.graphql.auth.IamAuthValidator;
import io.github.hectorvent.floci.services.appsync.model.AuthenticationType;
import io.github.hectorvent.floci.services.appsync.model.GraphqlApi;
import io.github.hectorvent.floci.services.floci.appsync.FlociAppSyncClient;
import jakarta.ws.rs.core.HttpHeaders;
import jakarta.ws.rs.core.MultivaluedHashMap;
import jakarta.ws.rs.core.Response;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AppSyncExecutionControllerTest {

    @Mock
    AppSyncService appSyncService;
    @Mock
    FlociAppSyncClient flociAppSyncClient;
    @Mock
    AuthMiddleware authMiddleware;
    @Mock
    IamAuthValidator iamAuthValidator;
    @Mock
    RequestContext requestContext;

    private AppSyncExecutionController controller;
    private HttpHeaders jsonHeaders;

    @BeforeEach
    void setUp() {
        controller = new AppSyncExecutionController(
                appSyncService,
                flociAppSyncClient,
                new AppSyncErrorFormatter(),
                new ObjectMapper(),
                authMiddleware,
                iamAuthValidator,
                requestContext);

        jsonHeaders = mock(HttpHeaders.class);
        lenient().when(jsonHeaders.getHeaderString(HttpHeaders.CONTENT_TYPE)).thenReturn("application/json");
        lenient().when(jsonHeaders.getRequestHeaders()).thenReturn(new MultivaluedHashMap<>());
    }

    @Test
    void successfulExecutePassesThroughSidecarBody() {
        GraphqlApi api = new GraphqlApi();
        api.setApiId("api-1");
        when(appSyncService.getGraphqlApi("api-1")).thenReturn(api);
        when(authMiddleware.authenticate(any(), any(), any())).thenReturn(authContext(api));
        when(flociAppSyncClient.execute(eq("api-1"), eq("{ hello }"), isNull(), isNull(), any()))
                .thenReturn(new FlociAppSyncClient.ExecuteResult(200, Map.of("data", Map.of("hello", "world"))));

        Response response = controller.execute("api-1", jsonHeaders, "{\"query\":\"{ hello }\"}");

        assertEquals(200, response.getStatus());
        @SuppressWarnings("unchecked")
        Map<String, Object> body = (Map<String, Object>) response.getEntity();
        assertEquals(Map.of("hello", "world"), body.get("data"));
    }

    @Test
    void noSchemaRegisteredPassesThroughSidecar502() {
        GraphqlApi api = new GraphqlApi();
        api.setApiId("api-1");
        when(appSyncService.getGraphqlApi("api-1")).thenReturn(api);
        when(authMiddleware.authenticate(any(), any(), any())).thenReturn(authContext(api));
        when(flociAppSyncClient.execute(eq("api-1"), anyString(), any(), any(), any()))
                .thenReturn(new FlociAppSyncClient.ExecuteResult(502, Map.of("errors", List.of(
                        Map.of("errorType", "GraphQLSchemaException", "message", AppSyncErrorFormatter.MSG_NO_SCHEMA)))));

        Response response = controller.execute("api-1", jsonHeaders, "{\"query\":\"{ hello }\"}");

        assertEquals(502, response.getStatus());
        assertEquals("GraphQLSchemaException", response.getHeaderString("x-amzn-errortype"));
    }

    @Test
    void unexpectedSidecarFailureReturns500InternalFailure() {
        GraphqlApi api = new GraphqlApi();
        api.setApiId("api-1");
        when(appSyncService.getGraphqlApi("api-1")).thenReturn(api);
        when(authMiddleware.authenticate(any(), any(), any())).thenReturn(authContext(api));
        when(flociAppSyncClient.execute(eq("api-1"), eq("{ hello }"), isNull(), isNull(), any()))
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
        assertEquals("InternalFailure", error.get("message"));
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

    @Test
    void non404AwsExceptionFromLookupReturnsDataPlaneInternalFailure() {
        when(appSyncService.getGraphqlApi("api-1"))
                .thenThrow(new AwsException("BadRequestException", "unexpected management error", 400));

        Response response = controller.execute("api-1", jsonHeaders, "{\"query\":\"{ hello }\"}");

        assertEquals(500, response.getStatus());
        assertEquals("InternalFailure", response.getHeaderString("x-amzn-errortype"));
        @SuppressWarnings("unchecked")
        Map<String, Object> body = (Map<String, Object>) response.getEntity();
        assertNotNull(body.get("errors"));
        @SuppressWarnings("unchecked")
        Map<String, Object> error = ((List<Map<String, Object>>) body.get("errors")).get(0);
        assertEquals("InternalFailure", error.get("errorType"));
        assertEquals("InternalFailure", error.get("message"));
    }

    @Test
    void authFailureReturns401WithoutCallingSidecar() {
        GraphqlApi api = new GraphqlApi();
        api.setApiId("api-1");
        when(appSyncService.getGraphqlApi("api-1")).thenReturn(api);
        when(authMiddleware.authenticate(any(), any(), any())).thenThrow(AppSyncAuth.unauthorized());

        Response response = controller.execute("api-1", jsonHeaders, "{\"query\":\"{ hello }\"}");

        assertEquals(401, response.getStatus());
        assertEquals("UnauthorizedException", response.getHeaderString("x-amzn-errortype"));
        @SuppressWarnings("unchecked")
        Map<String, Object> body = (Map<String, Object>) response.getEntity();
        @SuppressWarnings("unchecked")
        Map<String, Object> error = ((List<Map<String, Object>>) body.get("errors")).get(0);
        assertEquals("UnauthorizedException", error.get("errorType"));
        assertEquals("You are not authorized to make this call.", error.get("message"));
    }

    private static AppSyncAuthContext authContext(GraphqlApi api) {
        return new AppSyncAuthContext(
                null, AppSyncAuth.AUTH_TYPE_API_KEY, AuthenticationType.API_KEY, Set.of(),
                api, null, "us-east-1", "000000000000");
    }
}
