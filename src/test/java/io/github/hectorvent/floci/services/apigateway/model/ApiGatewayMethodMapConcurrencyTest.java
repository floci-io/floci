package io.github.hectorvent.floci.services.apigateway.model;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ApiGatewayMethodMapConcurrencyTest {

    enum MapSource { NEW, SETTER, JACKSON, NULL_RESET }

    @ParameterizedTest
    @EnumSource(MapSource.class)
    void resourceMethodsTolerateWritesDuringReadTraversal(MapSource source) throws Exception {
        ApiGatewayResource resource = switch (source) {
            case NEW -> new ApiGatewayResource();
            case SETTER -> {
                ApiGatewayResource configured = new ApiGatewayResource();
                configured.setResourceMethods(new HashMap<>());
                yield configured;
            }
            case JACKSON -> new ObjectMapper().readValue(
                    "{\"resourceMethods\":{\"GET\":{\"httpMethod\":\"GET\"}}}", ApiGatewayResource.class);
            case NULL_RESET -> {
                ApiGatewayResource configured = new ApiGatewayResource();
                configured.setResourceMethods(null);
                yield configured;
            }
        };
        MethodConfig method = new MethodConfig();
        method.setHttpMethod("GET");
        mutateDuringTraversal(resource.getResourceMethods(), "GET", "POST", "DELETE", method);
    }

    @ParameterizedTest
    @EnumSource(MapSource.class)
    void methodResponsesTolerateWritesDuringReadTraversal(MapSource source) throws Exception {
        MethodConfig method = switch (source) {
            case NEW -> new MethodConfig();
            case SETTER -> {
                MethodConfig configured = new MethodConfig();
                configured.setMethodResponses(new HashMap<>());
                yield configured;
            }
            case JACKSON -> new ObjectMapper().readValue(
                    "{\"methodResponses\":{\"200\":{\"statusCode\":\"200\"}}}", MethodConfig.class);
            case NULL_RESET -> {
                MethodConfig configured = new MethodConfig();
                configured.setMethodResponses(null);
                yield configured;
            }
        };
        mutateDuringTraversal(method.getMethodResponses(), "200", "400", "500",
                new MethodResponse("200", Map.of("method.response.header.X-Trace", true)));
    }

    @Test
    void resourceSetterOwnsItsMapAndKeepsMethodConfiguration() {
        MethodConfig method = new MethodConfig();
        method.setHttpMethod("GET");
        Map<String, MethodConfig> input = new HashMap<>(Map.of("GET", method));
        ApiGatewayResource resource = new ApiGatewayResource();
        resource.setResourceMethods(input);
        input.clear();

        assertSame(method, resource.getResourceMethods().get("GET"));
        resource.getResourceMethods().put("POST", new MethodConfig());
        assertTrue(input.isEmpty(), "Mutating the stored map must not mutate the setter's input");
    }

    @Test
    void responseSetterOwnsItsMapAndKeepsResponseConfiguration() {
        MethodResponse response = new MethodResponse("200", Map.of("method.response.header.X-Trace", true));
        Map<String, MethodResponse> input = new HashMap<>(Map.of("200", response));
        MethodConfig method = new MethodConfig();
        method.setMethodResponses(input);
        input.clear();

        assertEquals(response, method.getMethodResponses().get("200"));
        method.getMethodResponses().put("400", new MethodResponse("400", Map.of()));
        assertTrue(input.isEmpty(), "Mutating the stored map must not mutate the setter's input");
    }

    private static <T> void mutateDuringTraversal(Map<String, T> map, String first, String second,
                                                 String added, T value) {
        map.put(first, value);
        map.put(second, value);
        AtomicBoolean changed = new AtomicBoolean();
        try (ExecutorService writer = Executors.newSingleThreadExecutor()) {
            assertDoesNotThrow(() -> map.forEach((key, entry) -> {
                if (changed.compareAndSet(false, true)) {
                    Future<?> mutation = writer.submit(() -> {
                        map.put(added, value);
                        map.remove(second);
                    });
                    assertDoesNotThrow(() -> mutation.get(5, TimeUnit.SECONDS));
                }
                assertNotNull(key);
                assertNotNull(entry);
            }));
        }
        assertTrue(changed.get(), "The writer must run while the reader is traversing the map");
        assertSame(value, map.get(added));
        assertFalse(map.containsKey(second));
    }
}
