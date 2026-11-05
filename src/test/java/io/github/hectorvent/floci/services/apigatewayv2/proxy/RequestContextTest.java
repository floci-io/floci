package io.github.hectorvent.floci.services.apigatewayv2.proxy;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class RequestContextTest {

    private static RequestContext context(Map<String, String> pathParams, Map<String, String> rawPathParams) {
        return new RequestContext("api1", "test", "GET", "/", "", "GET /", "req-1", "1.2.3.4",
                Map.of(), Map.of(), pathParams, null, Map.of(), Map.of(), Map.of(), Map.of(), rawPathParams);
    }

    @Test
    void rawValueThatDecodesToTheParameterIsKept() {
        Map<String, String> target = context(Map.of("proxy", "a/b c+d"), Map.of("proxy", "a%2Fb%20c+d"))
                .targetPathParams();

        assertEquals(Map.of("proxy", "a%2Fb%20c+d"), target);
    }

    @Test
    void rawValueReadFromShiftedSegmentsIsEncodedFromTheDecodedValue() {
        Map<String, String> target = context(Map.of("x", "a", "y", "b c/d"), Map.of("x", "a%2Fb%20c", "y", "d"))
                .targetPathParams();

        assertEquals(Map.of("x", "a", "y", "b%20c/d"), target);
    }

    @Test
    void parameterWithoutARawValueIsEncoded() {
        assertEquals(Map.of("name", "a%3Fb"), context(Map.of("name", "a?b"), Map.of()).targetPathParams());
        assertEquals(Map.of("name", "a%3Fb"), context(Map.of("name", "a?b"), null).targetPathParams());
    }

    @Test
    void rawValueThatIsNotValidPercentEncodingIsEncodedFromTheDecodedValue() {
        assertEquals(Map.of("name", "a%25zz"), context(Map.of("name", "a%zz"), Map.of("name", "a%zz")).targetPathParams());
    }

    @Test
    void absentPathParametersStayAbsent() {
        assertNull(context(null, Map.of("name", "a")).targetPathParams());
    }
}
