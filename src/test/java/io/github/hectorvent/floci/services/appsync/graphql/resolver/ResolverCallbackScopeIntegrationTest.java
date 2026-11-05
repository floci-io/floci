package io.github.hectorvent.floci.services.appsync.graphql.resolver;

import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.core.common.RegionResolver;
import io.github.hectorvent.floci.core.common.RequestScopes;
import io.github.hectorvent.floci.services.appsync.model.Resolver;
import io.quarkus.test.InjectMock;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.when;

@QuarkusTest
class ResolverCallbackScopeIntegrationTest {

    private static final String ACCOUNT = "725018800003";
    private static final String REGION = "cn-north-1";
    private static final String API = "callback-scope";
    private static final String BODY = "{\"invocations\":[{\"id\":\"0\",\"typeName\":\"Mutation\","
            + "\"fieldName\":\"publish\",\"accountId\":\"untrusted\",\"region\":\"us-west-2\"}]}";

    @Inject
    ResolverCallbackSessions sessions;
    @Inject
    ResolverCallbackResource resource;
    @Inject
    RegionResolver regionResolver;
    @InjectMock
    AppSyncResolverExecutor executor;

    @AfterEach
    void clearMocks() {
        reset(executor);
    }

    @Test
    void callbackHttpRequestUsesTheTokenBoundAccountRegionAndIdentity() {
        AtomicReference<String> lookup = new AtomicReference<>();
        AtomicReference<String> execution = new AtomicReference<>();
        AtomicReference<Object> identity = new AtomicReference<>();
        Resolver resolver = new Resolver();
        when(executor.findResolver(eq(API), eq("Mutation"), eq("publish"))).thenAnswer(call -> {
            lookup.set(scope());
            return resolver;
        });
        when(executor.execute(eq(resolver), any())).thenAnswer(call -> {
            execution.set(scope());
            identity.set(((ResolverInvocation) call.getArgument(1)).identity());
            return new ResolverOutcome("delivered", null, List.of());
        });
        Map<String, Object> roleIdentity = Map.of("userArn", "role-identity");
        try (ResolverCallbackSessions.Session session = sessions.open(API, roleIdentity, "IAM Authorization", ACCOUNT, REGION)) {
            given().contentType("application/json").header("Authorization", "Bearer " + session.token())
                    .body(BODY).post("/_floci/appsync/resolve")
                    .then().statusCode(200).body("results[0].data", equalTo("delivered"));
        }
        assertEquals(ACCOUNT + "|" + REGION + "|aws-cn", lookup.get());
        assertEquals(lookup.get(), execution.get());
        assertEquals(roleIdentity, identity.get());
    }

    @Test
    void callbackRestoresTheSurroundingScopeEvenWhenResolverLookupFails() {
        when(executor.findResolver(eq(API), any(), any())).thenAnswer(call -> {
            assertEquals(ACCOUNT + "|" + REGION + "|aws-cn", scope());
            throw new AwsException("ConcurrentModificationException", "reloading", 409);
        });
        try (ResolverCallbackSessions.Session session = sessions.open(API, Map.of(), "IAM Authorization", ACCOUNT, REGION)) {
            RequestScopes.runAs("725018800004", "eu-west-1", () -> {
                assertEquals(200, resource.resolve("Bearer " + session.token(), BODY).getStatus());
                assertEquals("725018800004|eu-west-1|aws", scope());
            });
        }
    }

    private String scope() {
        return regionResolver.getAccountId() + "|" + regionResolver.getRegion() + "|" + regionResolver.getPartition();
    }
}
