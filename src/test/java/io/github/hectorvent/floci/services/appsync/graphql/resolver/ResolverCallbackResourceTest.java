package io.github.hectorvent.floci.services.appsync.graphql.resolver;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.services.appsync.model.Resolver;
import jakarta.ws.rs.core.Response;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The Floci half of the GraphQL sidecar's resolver callback: what it accepts, what it answers, and
 * what it refuses. The invocation shape here is the one in {@code graphql/API.md} of
 * floci-io/floci-sidecars, so a change to either side shows up as a failure in these assertions.
 */
class ResolverCallbackResourceTest {

    private static final String API_ID = "api-1";

    private final AppSyncResolverExecutor executor = mock(AppSyncResolverExecutor.class);
    private final ResolverCallbackSessions sessions = new ResolverCallbackSessions();
    private ResolverCallbackResource resource;
    private ResolverCallbackSessions.Session session;

    @BeforeEach
    void setUp() {
        resource = new ResolverCallbackResource(sessions, executor, new ObjectMapper());
        session = sessions.open(API_ID, Map.of("sub", "u-1"), "AWS_IAM");
    }

    private String body(String... invocations) {
        return "{\"invocations\":[" + String.join(",", invocations) + "]}";
    }

    private String invocation(String id, String typeName, String fieldName) {
        return "{\"id\":\"" + id + "\",\"typeName\":\"" + typeName + "\",\"fieldName\":\"" + fieldName
                + "\",\"arguments\":{\"orgNo\":\"7\"},\"source\":null,\"path\":[\"" + fieldName
                + "\"],\"variables\":{},\"selectionSetList\":[\"id\",\"author/name\"]}";
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> results(Response response) {
        assertEquals(200, response.getStatus());
        return (List<Map<String, Object>>) ((Map<String, Object>) response.getEntity()).get("results");
    }

    private Resolver aResolver() {
        Resolver resolver = new Resolver();
        resolver.setTypeName("Query");
        resolver.setFieldName("getMessages");
        return resolver;
    }

    @Test
    void aBatchIsAnsweredOncePerInvocationKeyedByItsOwnId() {
        Resolver resolver = aResolver();
        when(executor.findResolver(eq(API_ID), any(), any())).thenReturn(resolver);
        when(executor.execute(eq(resolver), any()))
                .thenReturn(new ResolverOutcome(Map.of("id", "1"), null, List.of()))
                .thenReturn(new ResolverOutcome(Map.of("id", "2"), null, List.of()));

        List<Map<String, Object>> results = results(resource.resolve("Bearer " + session.token(),
                body(invocation("0", "Query", "getMessages"), invocation("1", "Query", "getMessages"))));

        assertEquals(2, results.size());
        assertEquals("0", results.get(0).get("id"));
        assertEquals(Map.of("id", "1"), results.get(0).get("data"));
        assertEquals("1", results.get(1).get("id"));
        assertEquals(Map.of("id", "2"), results.get(1).get("data"));
    }

    @Test
    void theInvocationCarriesEverythingTheExecutorUsedToReadOffTheGraphqlEnvironment() {
        Resolver resolver = aResolver();
        when(executor.findResolver(eq(API_ID), any(), any())).thenReturn(resolver);
        when(executor.execute(eq(resolver), any())).thenReturn(new ResolverOutcome(null, null, List.of()));

        resource.resolve("Bearer " + session.token(), body(invocation("0", "Query", "getMessages")));

        org.mockito.ArgumentCaptor<ResolverInvocation> captor =
                org.mockito.ArgumentCaptor.forClass(ResolverInvocation.class);
        verify(executor).execute(eq(resolver), captor.capture());
        ResolverInvocation invocation = captor.getValue();
        assertEquals(API_ID, invocation.apiId());
        assertEquals("Query", invocation.typeName());
        assertEquals("getMessages", invocation.fieldName());
        assertEquals(Map.of("orgNo", "7"), invocation.arguments());
        assertEquals(List.of("getMessages"), invocation.path());
        assertEquals(List.of("id", "author/name"), invocation.selectionSetList());
        // The session is the only place these can come from: the callback itself is unauthenticated
        // beyond its token, so it cannot be trusted to state who the caller is.
        assertEquals(Map.of("sub", "u-1"), invocation.identity());
        assertEquals("AWS_IAM", invocation.authType());
    }

    @Test
    void aNullValuedArgumentIsPassedThroughRatherThanFailingTheWholeBatch() {
        Resolver resolver = aResolver();
        when(executor.findResolver(eq(API_ID), any(), any())).thenReturn(resolver);
        when(executor.execute(eq(resolver), any())).thenReturn(new ResolverOutcome("ok", null, List.of()));

        String withNull = "{\"id\":\"0\",\"typeName\":\"Query\",\"fieldName\":\"getMessages\","
                + "\"arguments\":{\"nextToken\":null},\"variables\":{\"after\":null},"
                + "\"path\":[\"getMessages\"],\"selectionSetList\":[\"id\"]}";
        List<Map<String, Object>> results = results(resource.resolve("Bearer " + session.token(), body(withNull)));

        // getMessages(nextToken: null) is ordinary GraphQL, and an explicit null is not the same as
        // an absent argument to a resolver reading ctx.args. Copying the map with Map.copyOf threw
        // on the null, which answered 500 and failed every field in the batch, not just this one.
        assertEquals("ok", results.get(0).get("data"));
        org.mockito.ArgumentCaptor<ResolverInvocation> captor =
                org.mockito.ArgumentCaptor.forClass(ResolverInvocation.class);
        verify(executor).execute(eq(resolver), captor.capture());
        assertTrue(captor.getValue().arguments().containsKey("nextToken"));
        assertNull(captor.getValue().arguments().get("nextToken"));
        assertTrue(captor.getValue().variables().containsKey("after"));
        assertNull(captor.getValue().variables().get("after"));
    }

    @Test
    void aFailedResolverBecomesAnErrorResultCarryingTheTypeTheResolverChose() {
        Resolver resolver = aResolver();
        when(executor.findResolver(eq(API_ID), any(), any())).thenReturn(resolver);
        when(executor.execute(eq(resolver), any())).thenReturn(new ResolverOutcome(null,
                new AppSyncResolverError("orgNo is required", "BadRequest", Map.of("field", "orgNo"),
                        Map.of("hint", "pass one"), List.of("getMessages")),
                List.of()));

        List<Map<String, Object>> results = results(resource.resolve("Bearer " + session.token(),
                body(invocation("0", "Query", "getMessages"))));

        @SuppressWarnings("unchecked")
        Map<String, Object> error = (Map<String, Object>) results.get(0).get("error");
        assertEquals("orgNo is required", error.get("message"));
        assertEquals("BadRequest", error.get("type"));
        assertEquals(Map.of("field", "orgNo"), error.get("data"));
        assertEquals(Map.of("hint", "pass one"), error.get("info"));
        assertTrue(results.get(0).containsKey("error"));
    }

    @Test
    void appendedErrorsRideTheSessionRatherThanReplacingTheData() {
        Resolver resolver = aResolver();
        AppSyncResolverError appended =
                new AppSyncResolverError("one row was dropped", "Partial", null, null, List.of("getMessages"));
        when(executor.findResolver(eq(API_ID), any(), any())).thenReturn(resolver);
        when(executor.execute(eq(resolver), any()))
                .thenReturn(new ResolverOutcome(List.of("partial"), null, List.of(appended)));

        List<Map<String, Object>> results = results(resource.resolve("Bearer " + session.token(),
                body(invocation("0", "Query", "getMessages"))));

        // util.appendError means "report this AND keep the data", which the callback response has no
        // way to say: it carries a value or an error, never both. So the data comes back here and
        // the error travels on the session, to be merged into the response envelope.
        assertEquals(List.of("partial"), results.get(0).get("data"));
        assertNull(results.get(0).get("error"));
        assertEquals(List.of(appended), session.appendedErrors());
    }

    @Test
    void aFieldWhoseResolverVanishedFallsBackToTheSourceProperty() {
        when(executor.findResolver(eq(API_ID), any(), any())).thenReturn(null);

        String invocation = "{\"id\":\"0\",\"typeName\":\"Message\",\"fieldName\":\"name\","
                + "\"source\":{\"name\":\"from the parent\"},\"path\":[\"getMessages\",0,\"name\"]}";
        List<Map<String, Object>> results = results(resource.resolve("Bearer " + session.token(), body(invocation)));

        assertEquals("from the parent", results.get(0).get("data"));
        verify(executor, never()).execute(any(), any());
    }

    @Test
    void aControlPlaneFailureLookingUpTheResolverFailsThatFieldOnly() {
        when(executor.findResolver(eq(API_ID), any(), any()))
                .thenThrow(new AwsException("ConcurrentModificationException", "Schema is being modified", 409));

        List<Map<String, Object>> results = results(resource.resolve("Bearer " + session.token(),
                body(invocation("0", "Query", "getMessages"))));

        @SuppressWarnings("unchecked")
        Map<String, Object> error = (Map<String, Object>) results.get(0).get("error");
        assertEquals("Schema is being modified", error.get("message"));
        assertEquals("ConcurrentModificationException", error.get("type"));
    }

    @Test
    void anUnknownOrClosedTokenResolvesNothing() {
        assertEquals(401, resource.resolve("Bearer not-a-token",
                body(invocation("0", "Query", "getMessages"))).getStatus());
        assertEquals(401, resource.resolve(null, body(invocation("0", "Query", "getMessages"))).getStatus());

        String token = session.token();
        session.close();
        assertEquals(401, resource.resolve("Bearer " + token,
                body(invocation("0", "Query", "getMessages"))).getStatus());
        verify(executor, never()).execute(any(), any());
    }

    @Test
    void aBodyThatIsNotAnInvocationBatchIsRefused() {
        assertEquals(400, resource.resolve("Bearer " + session.token(), "not json").getStatus());
        assertEquals(400, resource.resolve("Bearer " + session.token(), "{}").getStatus());
        assertEquals(400, resource.resolve("Bearer " + session.token(), "{\"invocations\":{}}").getStatus());
    }
}
