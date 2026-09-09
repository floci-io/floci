package io.github.hectorvent.floci.services.appsync.graphql;

import io.github.hectorvent.floci.services.appsync.graphql.auth.AppSyncAuth;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertEquals;

class AppSyncVtlContextTest {

    private final com.fasterxml.jackson.databind.ObjectMapper mapper = new com.fasterxml.jackson.databind.ObjectMapper();

    @Test
    void nullIdentityStaysNullNotEmptyMap() {
        AppSyncVtlContext ctx = AppSyncVtlContext.builder(mapper)
                .identity(null)
                .authType(AppSyncAuth.AUTH_TYPE_API_KEY)
                .build();
        assertNull(ctx.getContextMap().get("identity"));
        assertEquals(AppSyncAuth.AUTH_TYPE_API_KEY, ctx.getUtil().authType());
    }

    @Test
    void populatedIdentityIsPreserved() {
        Map<String, Object> identity = Map.of("user", "AKIATEST");
        AppSyncVtlContext ctx = AppSyncVtlContext.builder(mapper)
                .identity(identity)
                .authType(AppSyncAuth.AUTH_TYPE_IAM)
                .build();
        assertEquals(identity, ctx.getContextMap().get("identity"));
    }
}
