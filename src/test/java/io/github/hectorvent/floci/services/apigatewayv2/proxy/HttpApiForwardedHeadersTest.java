package io.github.hectorvent.floci.services.apigatewayv2.proxy;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

class HttpApiForwardedHeadersTest {

    private static Map<String, List<String>> apply(Map<String, List<String>> headers) {
        return HttpApiForwardedHeaders.apply(headers, "192.0.2.43", "10.0.0.1", "api.example.test", "https");
    }

    @Test
    void callerWithoutAChainIsTheOnlyForElement() {
        Map<String, List<String>> result = apply(Map.of());

        assertEquals(List.of("for=192.0.2.43;by=10.0.0.1;host=api.example.test;proto=https"), result.get("Forwarded"));
        assertEquals(List.of("10.0.0.1"), result.get("X-Forwarded-For"));
    }

    @Test
    void callersChainMovesToForwardedAndClientComesLast() {
        Map<String, List<String>> result = apply(Map.of("x-forwarded-for", List.of("198.51.100.7, 198.51.100.8", "198.51.100.9")));

        assertEquals(List.of("for=198.51.100.7,for=198.51.100.8,for=198.51.100.9,for=192.0.2.43"
                + ";by=10.0.0.1;host=api.example.test;proto=https"), result.get("Forwarded"));
        assertEquals(List.of("10.0.0.1"), result.get("X-Forwarded-For"));
        assertFalse(result.containsKey("x-forwarded-for"));
    }

    @Test
    void callersForwardedHeaderIsReplacedAndOtherHeadersAreKept() {
        Map<String, List<String>> result = apply(Map.of("forwarded", List.of("for=203.0.113.1"), "X-Probe", List.of("kept")));

        assertFalse(result.containsKey("forwarded"));
        assertEquals(List.of("for=192.0.2.43;by=10.0.0.1;host=api.example.test;proto=https"), result.get("Forwarded"));
        assertEquals(List.of("kept"), result.get("X-Probe"));
    }

    @Test
    void ipv6NodesAndHostPortsAreQuoted() {
        Map<String, List<String>> result = HttpApiForwardedHeaders.apply(Map.of(), "2001:db8::1", "::1",
                "localhost:4566", "http");

        assertEquals(List.of("for=\"[2001:db8::1]\";by=\"[::1]\";host=\"localhost:4566\";proto=http"),
                result.get("Forwarded"));
    }

    @Test
    void quotesInACallersEntryCannotBreakOutOfTheParameter() {
        Map<String, List<String>> result = apply(Map.of("X-Forwarded-For", List.of("a\"b;by=evil")));

        assertEquals(List.of("for=\"a\\\"b;by=evil\",for=192.0.2.43;by=10.0.0.1;host=api.example.test;proto=https"),
                result.get("Forwarded"));
    }
}
