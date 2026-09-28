package io.github.hectorvent.floci.services.appsync.graphql;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.core.common.RequestContext;
import io.github.hectorvent.floci.services.appsync.AppSyncService;
import io.github.hectorvent.floci.services.appsync.GraphqlSidecarClient;
import io.github.hectorvent.floci.services.appsync.graphql.auth.AppSyncAuth;
import io.github.hectorvent.floci.services.appsync.graphql.auth.AppSyncAuthContext;
import io.github.hectorvent.floci.services.appsync.graphql.auth.AuthMiddleware;
import io.github.hectorvent.floci.core.common.docker.ContainerReachableEndpoint;
import io.github.hectorvent.floci.services.appsync.graphql.auth.SidecarFieldAuthorizationPlanner;
import io.github.hectorvent.floci.services.appsync.graphql.resolver.AppSyncResolverError;
import io.github.hectorvent.floci.services.appsync.graphql.resolver.AppSyncResolverExecutor;
import io.github.hectorvent.floci.services.appsync.graphql.resolver.ResolverCallbackSessions;
import io.github.hectorvent.floci.services.appsync.model.Resolver;
import io.github.hectorvent.floci.services.appsync.model.AuthenticationType;
import io.github.hectorvent.floci.services.appsync.model.GraphqlApi;
import jakarta.ws.rs.core.HttpHeaders;
import jakarta.ws.rs.core.MultivaluedHashMap;
import jakarta.ws.rs.core.Response;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AppSyncExecutionControllerTest {

    @Mock
    AppSyncService appSyncService;
    @Mock
    SchemaRegistry schemaRegistry;
    @Mock
    SidecarSchemaCompiler schemaCompiler;
    @Mock
    SidecarFieldAuthorizationPlanner fieldAuthorizationPlanner;
    @Mock
    GraphqlSidecarClient sidecarClient;
    @Mock
    AuthMiddleware authMiddleware;
    @Mock
    RequestContext requestContext;
    @Mock
    AppSyncResolverExecutor resolverExecutor;
    @Mock
    ContainerReachableEndpoint reachableEndpoint;

    private final ResolverCallbackSessions callbackSessions = new ResolverCallbackSessions();
    private AppSyncExecutionController controller;
    private HttpHeaders jsonHeaders;

    @BeforeEach
    void setUp() {
        controller = new AppSyncExecutionController(
                appSyncService,
                schemaRegistry,
                schemaCompiler,
                fieldAuthorizationPlanner,
                sidecarClient,
                new AppSyncErrorFormatter(),
                new ObjectMapper(),
                authMiddleware,
                requestContext,
                resolverExecutor,
                callbackSessions,
                reachableEndpoint);

        jsonHeaders = mock(HttpHeaders.class);
        lenient().when(jsonHeaders.getHeaderString(HttpHeaders.CONTENT_TYPE)).thenReturn("application/json");
        lenient().when(jsonHeaders.getRequestHeaders()).thenReturn(new MultivaluedHashMap<>());
    }

    @Test
    void unexpectedSidecarFailureReturns500InternalFailure() {
        GraphqlApi api = new GraphqlApi();
        api.setApiId("api-1");
        when(appSyncService.getGraphqlApi("api-1")).thenReturn(api);
        when(authMiddleware.authenticate(any(), any(), any())).thenReturn(authContext(api));
        when(schemaRegistry.getSdl("api-1")).thenReturn(Optional.of("type Query { hello: String }"));
        when(schemaCompiler.withDirectivesAndScalars(any())).thenReturn("prepared-sdl");
        when(schemaCompiler.scalarKindMapping()).thenReturn(Map.of());
        when(sidecarClient.plan(any(), any(), any(), any())).thenReturn(new GraphqlSidecarClient.PlanResult("QUERY", List.of()));
        when(fieldAuthorizationPlanner.planDenyFields(any(), any())).thenReturn(List.of());
        when(sidecarClient.execute(any(), any(), any(), any(), any(), any())).thenThrow(new RuntimeException("boom"));

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

    /** Plans one query over two coordinates, of which only {@code Query.getMessages} has a resolver. */
    private void planTwoFieldsOneWithAResolver() {
        GraphqlApi api = new GraphqlApi();
        api.setApiId("api-1");
        when(appSyncService.getGraphqlApi("api-1")).thenReturn(api);
        when(authMiddleware.authenticate(any(), any(), any())).thenReturn(authContext(api));
        when(schemaRegistry.getSdl("api-1")).thenReturn(Optional.of("type Query { getMessages: String hello: String }"));
        when(schemaCompiler.withDirectivesAndScalars(any())).thenReturn("prepared-sdl");
        when(schemaCompiler.scalarKindMapping()).thenReturn(Map.of());
        when(sidecarClient.plan(any(), any(), any(), any())).thenReturn(new GraphqlSidecarClient.PlanResult("QUERY",
                List.of(new GraphqlSidecarClient.PlannedField("Query", "getMessages", List.of(), List.of()),
                        new GraphqlSidecarClient.PlannedField("Query", "hello", List.of(), List.of()))));
        lenient().when(reachableEndpoint.baseUrl()).thenReturn("http://floci.local:4566");
        Resolver resolver = new Resolver();
        resolver.setTypeName("Query");
        resolver.setFieldName("getMessages");
        lenient().when(resolverExecutor.findResolver("api-1", "Query", "getMessages")).thenReturn(resolver);
        lenient().when(resolverExecutor.findResolver("api-1", "Query", "hello")).thenReturn(null);
    }

    @Test
    void onlyTheFieldsThatHaveResolversAreWiredToTheCallback() {
        planTwoFieldsOneWithAResolver();
        when(fieldAuthorizationPlanner.planDenyFields(any(), any())).thenReturn(List.of());
        ArgumentCaptor<GraphqlSidecarClient.ResolveSpec> spec =
                ArgumentCaptor.forClass(GraphqlSidecarClient.ResolveSpec.class);
        when(sidecarClient.execute(any(), any(), any(), any(), any(), any(), any())).thenReturn(Map.of("data", Map.of()));

        controller.execute("api-1", jsonHeaders, "{\"query\":\"{ getMessages hello }\"}");

        verify(sidecarClient).execute(any(), any(), any(), any(), any(), any(), spec.capture());
        assertEquals(List.of(new GraphqlSidecarClient.ResolveField("Query", "getMessages")),
                spec.getValue().fields());
        assertEquals("http://floci.local:4566/_floci/appsync/resolve", spec.getValue().url());
        assertNotNull(spec.getValue().token());
    }

    @Test
    void aDeniedFieldIsNeverWiredToTheCallback() {
        planTwoFieldsOneWithAResolver();
        // denyFields always wins, so an unauthorized field must not reach its resolver at all. With
        // the only resolver-backed field denied there is nothing left to call back for, which is the
        // plain execute the sidecar answers over its own null root value.
        when(fieldAuthorizationPlanner.planDenyFields(any(), any())).thenReturn(
                List.of(new GraphqlSidecarClient.DenyField("Query", "getMessages", "Unauthorized", "no")));
        when(sidecarClient.execute(any(), any(), any(), any(), any(), any())).thenReturn(Map.of("data", Map.of()));

        controller.execute("api-1", jsonHeaders, "{\"query\":\"{ getMessages hello }\"}");

        verify(sidecarClient).execute(any(), any(), any(), any(), any(), any());
        verify(sidecarClient, never()).execute(any(), any(), any(), any(), any(), any(), any());
    }

    @Test
    void appendedErrorsJoinTheEnvelopeBesideTheDataAndTheSessionIsClosed() {
        planTwoFieldsOneWithAResolver();
        when(fieldAuthorizationPlanner.planDenyFields(any(), any())).thenReturn(List.of());
        AtomicReference<String> token = new AtomicReference<>();
        when(sidecarClient.execute(any(), any(), any(), any(), any(), any(), any())).thenAnswer(call -> {
            GraphqlSidecarClient.ResolveSpec spec = call.getArgument(6);
            token.set(spec.token());
            // What a resolver calling util.appendError does, by way of the callback.
            callbackSessions.find(spec.token()).orElseThrow().appendErrors(List.of(
                    new AppSyncResolverError("one row was dropped", "Partial", null, Map.of("rows", 1),
                            List.of("getMessages"))));
            return Map.of("data", Map.of("getMessages", "partial"));
        });

        Response response = controller.execute("api-1", jsonHeaders, "{\"query\":\"{ getMessages }\"}");

        @SuppressWarnings("unchecked")
        Map<String, Object> body = (Map<String, Object>) response.getEntity();
        assertEquals(Map.of("getMessages", "partial"), body.get("data"));
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> errors = (List<Map<String, Object>>) body.get("errors");
        assertEquals(1, errors.size());
        assertEquals("one row was dropped", errors.get(0).get("message"));
        assertEquals("Partial", errors.get(0).get("errorType"));
        assertEquals(Map.of("rows", 1), errors.get(0).get("errorInfo"));
        assertEquals(List.of("getMessages"), errors.get(0).get("path"));
        // The token stops working the moment the operation returns, so a late callback resolves nothing.
        assertTrue(callbackSessions.find(token.get()).isEmpty());
    }
}
