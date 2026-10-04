package io.github.hectorvent.floci.services.eventbridge;

import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

class EventBridgeInvokerApiDestinationTest {

    @Test
    void substitutePathParameters_replacesWildcardsInOrder() {
        assertEquals("https://api.example.com/a/one/b/two",
                EventBridgeInvoker.substitutePathParameters(
                        "https://api.example.com/a/*/b/*", List.of("one", "two")));
    }

    @Test
    void substitutePathParameters_encodesValuesAsSingleSegment() {
        assertEquals("https://api.example.com/x/a%2Fb%3Fc%23d%40e%20f",
                EventBridgeInvoker.substitutePathParameters(
                        "https://api.example.com/x/*", List.of("a/b?c#d@e f")));
    }

    @Test
    void substitutePathParameters_neverTouchesHostOrQuery() {
        assertEquals("https://*.example.com/p/v?q=*",
                EventBridgeInvoker.substitutePathParameters(
                        "https://*.example.com/p/*?q=*", List.of("v", "ignored")));
    }

    @Test
    void substitutePathParameters_withoutPathLeavesUrlUntouched() {
        assertEquals("https://*.example.com",
                EventBridgeInvoker.substitutePathParameters("https://*.example.com", List.of("v")));
    }

    @Test
    void substitutePathParameters_keepsExtraWildcardsWhenValuesRunOut() {
        assertEquals("https://api.example.com/one/*",
                EventBridgeInvoker.substitutePathParameters("https://api.example.com/*/*", List.of("one")));
    }

    @Test
    void appendQueryParameters_encodesAndJoinsExistingQuery() {
        Map<String, String> params = new LinkedHashMap<>();
        params.put("a b", "c&d");
        assertEquals("https://x.test/p?k=v&a+b=c%26d",
                EventBridgeInvoker.appendQueryParameters("https://x.test/p?k=v", params));
        assertEquals("https://x.test/p?a+b=c%26d",
                EventBridgeInvoker.appendQueryParameters("https://x.test/p", params));
    }

    @Test
    void appendQueryParameters_insertsTheQueryBeforeAFragment() {
        Map<String, String> params = new LinkedHashMap<>();
        params.put("a", "b");
        assertEquals("https://x.test/p?a=b#frag",
                EventBridgeInvoker.appendQueryParameters("https://x.test/p#frag", params));
        assertEquals("https://x.test/p?k=v&a=b#frag?x",
                EventBridgeInvoker.appendQueryParameters("https://x.test/p?k=v#frag?x", params));
    }

    @Test
    void appendQueryParameters_withoutParamsReturnsUrl() {
        assertEquals("https://x.test/p", EventBridgeInvoker.appendQueryParameters("https://x.test/p", Map.of()));
    }
}
