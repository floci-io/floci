package io.github.hectorvent.floci.core.common;

import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CookieHeadersTest {

    @Test
    void splitsEveryCookieHeaderInRequestOrder() {
        Map<String, List<String>> headers = new LinkedHashMap<>();
        headers.put("Cookie", List.of("a=1; b=2;; ", " c=3 "));
        headers.put("x-other", List.of("v"));
        headers.put("cookie", List.of("d=4"));

        assertEquals(List.of("a=1", "b=2", "c=3", "d=4"), CookieHeaders.cookiePairs(headers));
    }

    @Test
    void noCookieHeaderGivesNoPairs() {
        assertTrue(CookieHeaders.cookiePairs(Map.of("x-other", List.of("v"))).isEmpty());
        assertTrue(CookieHeaders.cookiePairs(Map.of("Cookie", List.of(" ; "))).isEmpty());
    }
}
