package io.github.hectorvent.floci.services.appsync.graphql.execution.datasource;

import io.github.hectorvent.floci.services.appsync.graphql.execution.GraphQlRequestContext;
import io.github.hectorvent.floci.services.appsync.model.DataSource;
import io.github.hectorvent.floci.services.appsync.model.DataSourceType;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class NoneDataSourceInvokerTest {

    private final NoneDataSourceInvoker invoker = new NoneDataSourceInvoker();
    private final GraphQlRequestContext ctx = new GraphQlRequestContext(
            "api", "000000000000", "us-east-1", "API Key Authorization", null, Map.of(), Map.of());

    private static DataSource none() {
        DataSource ds = new DataSource();
        ds.setName("none");
        ds.setType(DataSourceType.NONE);
        return ds;
    }

    @Test
    void returnsPayload() {
        Map<String, Object> request = Map.of("version", "2018-05-29", "payload", Map.of("hello", "world"));
        DataSourceResult result = invoker.invoke(none(), request, ctx);
        assertEquals(Map.of("hello", "world"), result.result());
        assertNull(result.error());
    }

    @Test
    void missingPayloadIsNull() {
        DataSourceResult result = invoker.invoke(none(), Map.of("version", "2018-05-29"), ctx);
        assertNull(result.result());
        assertNull(result.error());
    }

    @Test
    void rejectsUnknownFields() {
        Map<String, Object> request = new LinkedHashMap<>();
        request.put("version", "2018-05-29");
        request.put("operation", "Invoke");
        InvalidRequestDocumentException e = assertThrows(InvalidRequestDocumentException.class,
                () -> invoker.invoke(none(), request, ctx));
        assertTrue(e.getMessage().contains("operation"));
    }
}
