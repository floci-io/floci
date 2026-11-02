package io.github.hectorvent.floci.services.elbv2;

import io.vertx.core.MultiMap;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.http.HttpMethod;
import io.vertx.core.http.HttpServerRequest;
import io.vertx.core.net.SocketAddress;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ElbV2ForwardedHeadersTest {

    private static final SocketAddress CLIENT_V4 = SocketAddress.inetSocketAddress(8080, "12.34.56.78");
    private static final SocketAddress CLIENT_V6 =
            SocketAddress.inetSocketAddress(8080, "2001:db8:85a3:8d3:1319:8a2e:370:7348");

    private static List<String> xff(ElbV2ForwardedHeaders headers, String... received) {
        MultiMap inbound = MultiMap.caseInsensitiveMultiMap();
        for (String value : received) {
            inbound.add("x-forwarded-for", value);
        }
        MultiMap outbound = MultiMap.caseInsensitiveMultiMap().addAll(inbound);
        headers.applyTo(inbound, outbound);
        return outbound.getAll("X-Forwarded-For");
    }

    private static ElbV2ForwardedHeaders mode(String mode) {
        return new ElbV2ForwardedHeaders(CLIENT_V4, false, 80, mode, false);
    }

    @Test
    void appendCreatesTheHeaderWhenTheClientSentNone() {
        assertEquals(List.of("12.34.56.78"), xff(mode("append")));
    }

    @Test
    void appendAddsTheClientAfterExistingAddresses() {
        assertEquals(List.of("127.0.0.4, 127.0.0.8, 12.34.56.78"), xff(mode("append"), "127.0.0.4, 127.0.0.8"));
    }

    @Test
    void appendJoinsRepeatedHeaderLinesInOrder() {
        assertEquals(List.of("127.0.0.4, 127.0.0.8, 12.34.56.78"), xff(mode("append"), "127.0.0.4", "127.0.0.8"));
    }

    @Test
    void appendIgnoresABlankClientValue() {
        assertEquals(List.of("12.34.56.78"), xff(mode("append"), " "));
    }

    @Test
    void appendIsTheModeWhenTheAttributeIsMissingOrUnknown() {
        assertEquals(List.of("12.34.56.78"), xff(mode(null)));
        assertEquals(List.of("12.34.56.78"), xff(mode("bogus")));
    }

    @Test
    void preserveLeavesTheHeaderUnchanged() {
        assertEquals(List.of(), xff(mode("preserve")));
        assertEquals(List.of("127.0.0.4, 127.0.0.8"), xff(mode("preserve"), "127.0.0.4, 127.0.0.8"));
        assertEquals(List.of("127.0.0.4", "127.0.0.8"), xff(mode("preserve"), "127.0.0.4", "127.0.0.8"));
    }

    @Test
    void removeDropsTheHeader() {
        assertEquals(List.of(), xff(mode("remove")));
        assertEquals(List.of(), xff(mode("remove"), "127.0.0.4"));
    }

    @Test
    void clientPortIsAddedToAnIpv4Address() {
        assertEquals(List.of("12.34.56.78:8080"), xff(new ElbV2ForwardedHeaders(CLIENT_V4, false, 80, "append", true)));
    }

    @Test
    void ipv6AddressIsBracketedOnlyWithAClientPort() {
        assertEquals(List.of("2001:db8:85a3:8d3:1319:8a2e:370:7348"),
                xff(new ElbV2ForwardedHeaders(CLIENT_V6, false, 80, "append", false)));
        assertEquals(List.of("[2001:db8:85a3:8d3:1319:8a2e:370:7348]:8080"),
                xff(new ElbV2ForwardedHeaders(CLIENT_V6, false, 80, "append", true)));
    }

    @Test
    void clientPortIsIgnoredOutsideAppendMode() {
        assertEquals(List.of("127.0.0.4"),
                xff(new ElbV2ForwardedHeaders(CLIENT_V4, false, 80, "preserve", true), "127.0.0.4"));
        assertEquals(List.of(), xff(new ElbV2ForwardedHeaders(CLIENT_V4, false, 80, "remove", true), "127.0.0.4"));
    }

    @Test
    void protoAndPortComeFromTheListenerConnectionInEveryMode() {
        for (String mode : List.of("append", "preserve", "remove")) {
            MultiMap inbound = MultiMap.caseInsensitiveMultiMap()
                    .add("X-Forwarded-Proto", "gopher")
                    .add("X-Forwarded-Port", "1");
            MultiMap outbound = MultiMap.caseInsensitiveMultiMap().addAll(inbound);

            new ElbV2ForwardedHeaders(CLIENT_V4, true, 443, mode, false).applyTo(inbound, outbound);

            assertEquals(List.of("https"), outbound.getAll("X-Forwarded-Proto"));
            assertEquals(List.of("443"), outbound.getAll("X-Forwarded-Port"));
        }
    }

    @Test
    @SuppressWarnings("unchecked")
    void lambdaEventCarriesTheForwardedHeadersInBothHeaderMaps() {
        HttpServerRequest request = mock(HttpServerRequest.class);
        when(request.method()).thenReturn(HttpMethod.GET);
        when(request.path()).thenReturn("/");
        when(request.headers()).thenReturn(MultiMap.caseInsensitiveMultiMap()
                .add("X-Forwarded-For", "127.0.0.4")
                .add("X-Forwarded-Proto", "gopher"));
        ElbV2ForwardedHeaders forwarded = new ElbV2ForwardedHeaders(CLIENT_V4, true, 443, "append", false);

        Map<String, Object> event = new ElbV2DataPlane(new ConcurrentHashMap<>())
                .buildAlbEvent(request, forwarded, Buffer.buffer());

        Map<String, String> headers = (Map<String, String>) event.get("headers");
        assertEquals("127.0.0.4, 12.34.56.78", headers.get("x-forwarded-for"));
        assertEquals("https", headers.get("x-forwarded-proto"));
        assertEquals("443", headers.get("x-forwarded-port"));
        Map<String, List<String>> multiValueHeaders = (Map<String, List<String>>) event.get("multiValueHeaders");
        assertEquals(List.of("127.0.0.4, 12.34.56.78"), multiValueHeaders.get("x-forwarded-for"));
    }

    @Test
    @SuppressWarnings("unchecked")
    void lambdaEventHeadersMapKeepsTheLastOfARepeatedHeader() {
        HttpServerRequest request = mock(HttpServerRequest.class);
        when(request.method()).thenReturn(HttpMethod.GET);
        when(request.path()).thenReturn("/");
        when(request.headers()).thenReturn(MultiMap.caseInsensitiveMultiMap()
                .add("Cookie", "name1=value1")
                .add("Cookie", "name2=value2"));

        Map<String, Object> event = new ElbV2DataPlane(new ConcurrentHashMap<>())
                .buildAlbEvent(request, mode("append"), Buffer.buffer());

        assertEquals("name2=value2", ((Map<String, String>) event.get("headers")).get("cookie"));
    }
}
