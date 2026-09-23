package io.github.hectorvent.floci.services.cloudfront;

import io.github.hectorvent.floci.services.cloudfront.CloudFrontOriginRequestBuilder.Forwarding;
import io.github.hectorvent.floci.services.cloudfront.CloudFrontOriginRequestBuilder.Header;
import io.github.hectorvent.floci.services.cloudfront.CloudFrontOriginRequestBuilder.OriginRequest;
import io.github.hectorvent.floci.services.cloudfront.CloudFrontOriginRequestBuilder.ViewerRequest;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CloudFrontOriginRequestBuilderTest {

    private static final String VIA_HOST = "d111111abcdef8.cloudfront.net";

    @Test
    void cachePolicyWithoutForwardingSendsOnlyTheHeadersCloudFrontAdds() {
        OriginRequest request = build(viewer("GET", "x=1&y=2",
                "Host", "d111111abcdef8.cloudfront.net",
                "User-Agent", "curl/8.0",
                "Cookie", "session=abc123",
                "Authorization", "Bearer t",
                "X-Custom", "custom",
                "Origin", "https://viewer.example",
                "X-Forwarded-For", "203.0.113.9"),
                Forwarding.policies(parameters(none("Header"), none("Cookie"), none("QueryString")),
                        null, false));

        assertNull(request.rawQuery());
        assertEquals(List.of("User-Agent", "X-Forwarded-For", "Via", "X-Amz-Cf-Id"), names(request));
        assertEquals("Amazon CloudFront", value(request, "User-Agent"));
        assertEquals("203.0.113.9,127.0.0.1", value(request, "X-Forwarded-For"));
        assertEquals("1.1 " + VIA_HOST + " (CloudFront)", value(request, "Via"));
        assertEquals(56, value(request, "X-Amz-Cf-Id").length());
    }

    @Test
    void allExceptForwardsEveryViewerValueButTheListedHeaders() {
        Map<String, Object> allViewerExceptHost = orp(
                selection("HeaderBehavior", "allExcept", "Headers", "host"),
                selection("CookieBehavior", "all", "Cookies"),
                selection("QueryStringBehavior", "all", "QueryStrings"));

        OriginRequest request = build(viewer("GET", "x=1&encoded=a%2Fb",
                "Host", "d111111abcdef8.cloudfront.net",
                "User-Agent", "curl/8.0",
                "Cookie", "session=abc123; csrf=xyz",
                "Authorization", "Bearer t",
                "X-Custom", "custom"),
                Forwarding.policies(parameters(none("Header"), none("Cookie"), none("QueryString")),
                        allViewerExceptHost, false));

        assertEquals("x=1&encoded=a%2Fb", request.rawQuery());
        assertNull(value(request, "Host"));
        assertEquals("curl/8.0", value(request, "User-Agent"));
        assertEquals("Bearer t", value(request, "Authorization"));
        assertEquals("custom", value(request, "X-Custom"));
        assertEquals("csrf=xyz; session=abc123", value(request, "Cookie"));
        // AWS documents AllViewerExceptHostHeader as adding CloudFront's protocol, version and
        // location headers; Floci generates the ones it can derive locally.
        assertEquals("http", value(request, "CloudFront-Forwarded-Proto"));
        assertEquals("1.1", value(request, "CloudFront-Viewer-Http-Version"));
        assertEquals("127.0.0.1:54321", value(request, "CloudFront-Viewer-Address"));
    }

    @Test
    void allViewerForwardsTheViewerHostAndAddsNoCloudFrontHeaders() {
        OriginRequest request = build(viewer("GET", null,
                "Host", "d111111abcdef8.cloudfront.net",
                "Accept-Language", "en-GB"),
                Forwarding.policies(null, orp(
                        selection("HeaderBehavior", "allViewer", "Headers"),
                        selection("CookieBehavior", "none", "Cookies"),
                        selection("QueryStringBehavior", "none", "QueryStrings")), false));

        assertEquals("d111111abcdef8.cloudfront.net", value(request, "Host"));
        assertEquals("en-GB", value(request, "Accept-Language"));
        assertTrue(names(request).stream().noneMatch(name -> name.startsWith("CloudFront-")),
                names(request).toString());
    }

    @Test
    void allViewerAndWhitelistCloudFrontGeneratesOnlyListedSupportedHeaders() {
        OriginRequest request = build(viewerOverHttp2("https",
                "X-Custom", "custom",
                "CloudFront-Viewer-Country", "XX",
                "CloudFront-Viewer-Address", "198.51.100.1:1"),
                Forwarding.policies(null, orp(
                        selection("HeaderBehavior", "allViewerAndWhitelistCloudFront", "Headers",
                                "CloudFront-Viewer-Address", "CloudFront-Viewer-Country",
                                "CloudFront-Forwarded-Proto"),
                        selection("CookieBehavior", "none", "Cookies"),
                        selection("QueryStringBehavior", "none", "QueryStrings")), false));

        assertEquals("custom", value(request, "X-Custom"));
        assertEquals("127.0.0.1:54321", value(request, "CloudFront-Viewer-Address"));
        assertEquals("https", value(request, "CloudFront-Forwarded-Proto"));
        assertNull(value(request, "CloudFront-Viewer-Country"), "viewer-supplied values are dropped");
        assertNull(value(request, "CloudFront-Viewer-Http-Version"), "not listed");
        assertEquals("2.0 " + VIA_HOST + " (CloudFront)", value(request, "Via"));
    }

    @Test
    void whitelistedHeadersMatchCaseInsensitivelyAndUnionWithTheCachePolicy() {
        OriginRequest request = build(viewer("GET", null,
                "authorization", "Bearer t",
                "x-custom", "custom",
                "X-Other", "other",
                "Referer", "https://viewer.example/"),
                Forwarding.policies(
                        parameters(selection("HeaderBehavior", "whitelist", "Headers", "Authorization"),
                                none("Cookie"), none("QueryString")),
                        orp(selection("HeaderBehavior", "whitelist", "Headers", "X-CUSTOM"),
                                selection("CookieBehavior", "none", "Cookies"),
                                selection("QueryStringBehavior", "none", "QueryStrings")),
                        false));

        assertEquals("Bearer t", value(request, "Authorization"));
        assertEquals("custom", value(request, "X-Custom"));
        assertNull(value(request, "X-Other"));
        assertNull(value(request, "Referer"));
    }

    @Test
    void cookieBehaviorsSelectSortAndDropMalformedCookies() {
        ViewerRequest viewer = viewer("GET", null,
                "Cookie", "session=abc; Session=upper; $Version=1; theme=dark; broken",
                "Cookie", "csrf=xyz");

        assertNull(value(build(viewer, cookies("none")), "Cookie"));
        assertEquals("session=abc",
                value(build(viewer, cookies("whitelist", "session", "missing")), "Cookie"));
        assertEquals("Session=upper; csrf=xyz; theme=dark",
                value(build(viewer, cookies("allExcept", "session")), "Cookie"));
        assertEquals("Session=upper; csrf=xyz; session=abc; theme=dark",
                value(build(viewer, cookies("all")), "Cookie"));
    }

    @Test
    void queryStringBehaviorsKeepViewerOrderAndEncoding() {
        ViewerRequest viewer = viewer("GET", "b=2&a=1&c=x%20y&flag&a=3", "Host", "d.example");

        assertNull(build(viewer, queryStrings("none")).rawQuery());
        assertEquals("b=2&a=1&c=x%20y&flag&a=3", build(viewer, queryStrings("all")).rawQuery());
        assertEquals("a=1&flag&a=3", build(viewer, queryStrings("whitelist", "a", "flag")).rawQuery());
        assertEquals("b=2&c=x%20y&flag", build(viewer, queryStrings("allExcept", "a")).rawQuery());
        assertNull(build(viewer, queryStrings("whitelist", "A")).rawQuery(),
                "query string names are case-sensitive");

        OriginRequest union = build(viewer, Forwarding.policies(
                parameters(none("Header"), none("Cookie"),
                        selection("QueryStringBehavior", "whitelist", "QueryStrings", "b")),
                orp(selection("HeaderBehavior", "none", "Headers"),
                        selection("CookieBehavior", "none", "Cookies"),
                        selection("QueryStringBehavior", "whitelist", "QueryStrings", "c")),
                false));
        assertEquals("b=2&c=x%20y", union.rawQuery());
    }

    @Test
    void legacySettingsForwardDefaultHeadersAndRemoveTheDocumentedOnes() {
        OriginRequest request = build(viewer("GET", "x=1",
                "Host", "d111111abcdef8.cloudfront.net",
                "User-Agent", "curl/8.0",
                "Accept", "text/html",
                "Accept-Language", "en-GB",
                "Referer", "https://viewer.example/",
                "Authorization", "Bearer t",
                "Cookie", "session=abc",
                "X-HTTP-Method-Override", "DELETE",
                "X-Custom", "custom",
                "Origin", "https://viewer.example",
                "If-None-Match", "\"etag\"",
                "Via", "1.1 proxy.example",
                "Accept-Encoding", "gzip, deflate"),
                Forwarding.legacy(null, false));

        assertNull(request.rawQuery());
        assertEquals("custom", value(request, "X-Custom"));
        assertEquals("https://viewer.example", value(request, "Origin"));
        assertEquals("\"etag\"", value(request, "If-None-Match"));
        assertEquals("gzip", value(request, "Accept-Encoding"));
        assertEquals("Amazon CloudFront", value(request, "User-Agent"));
        assertEquals("1.1 proxy.example, 1.1 " + VIA_HOST + " (CloudFront)", value(request, "Via"));
        for (String removed : List.of("Host", "Accept", "Accept-Language", "Referer", "Authorization",
                "Cookie", "X-HTTP-Method-Override")) {
            assertNull(value(request, removed), removed);
        }
    }

    @Test
    void legacyHeaderListsAddRemovedHeadersAndAWildcardForwardsAll() {
        ViewerRequest viewer = viewer("GET", null,
                "Host", "d111111abcdef8.cloudfront.net",
                "User-Agent", "curl/8.0",
                "Authorization", "Bearer t",
                "Accept-Language", "en-GB",
                "Accept-Encoding", "gzip, deflate",
                "X-Custom", "custom");

        OriginRequest listed = build(viewer, Forwarding.legacy(
                forwardedValues(false, "none", List.of(), List.of("Authorization", "accept-language")),
                false));
        assertEquals("Bearer t", value(listed, "Authorization"));
        assertEquals("en-GB", value(listed, "Accept-Language"));
        assertEquals("custom", value(listed, "X-Custom"));
        assertNull(value(listed, "Host"));

        OriginRequest all = build(viewer, Forwarding.legacy(
                forwardedValues(false, "none", List.of(), List.of("*")), false));
        assertEquals("d111111abcdef8.cloudfront.net", value(all, "Host"));
        assertEquals("curl/8.0", value(all, "User-Agent"));
        assertEquals("Bearer t", value(all, "Authorization"));
        assertEquals("gzip, deflate", value(all, "Accept-Encoding"));
    }

    @Test
    void legacyCookiesAndQueryStringFollowForwardedValues() {
        ViewerRequest viewer = viewer("GET", "a=1&b=2",
                "Cookie", "userid_42=me; userid_=empty; uid9=x; theme=dark");

        OriginRequest wildcard = build(viewer, Forwarding.legacy(
                forwardedValues(true, "whitelist", List.of("userid_*", "uid?"), List.of()), false));
        assertEquals("uid9=x; userid_=empty; userid_42=me", value(wildcard, "Cookie"));
        // QueryString=true forwards every parameter; QueryStringCacheKeys only narrows the cache key.
        Map<String, Object> keyed = forwardedValues(true, "all", List.of(), List.of());
        keyed.put("QueryStringCacheKeys", List.of("a"));
        OriginRequest all = build(viewer, Forwarding.legacy(keyed, false));
        assertEquals("a=1&b=2", all.rawQuery());
        assertEquals("theme=dark; uid9=x; userid_=empty; userid_42=me", value(all, "Cookie"));

        OriginRequest none = build(viewer, Forwarding.legacy(
                forwardedValues(false, "none", List.of(), List.of()), false));
        assertNull(none.rawQuery());
        assertNull(value(none, "Cookie"));
    }

    @Test
    void authorizationIsAlwaysForwardedForMethodsCloudFrontDoesNotCache() {
        Forwarding nothing = Forwarding.policies(null, null, false);
        for (String method : List.of("POST", "PUT", "PATCH", "DELETE", "OPTIONS")) {
            assertEquals("Bearer t", value(build(viewer(method, null, "Authorization", "Bearer t"),
                    nothing), "Authorization"), method);
        }
        assertNull(value(build(viewer("GET", null, "Authorization", "Bearer t"), nothing),
                "Authorization"));
        assertNull(value(build(viewer("HEAD", null, "Authorization", "Bearer t"), nothing),
                "Authorization"));
        assertNull(value(build(viewer("OPTIONS", null, "Authorization", "Bearer t"),
                Forwarding.policies(null, null, true)), "Authorization"),
                "a cached OPTIONS response drops Authorization");
    }

    @Test
    void contentTypeTravelsWithARequestBodyWhateverThePolicy() {
        Forwarding nothing = Forwarding.policies(null, null, false);
        assertEquals("application/json", value(build(
                viewer("POST", null, "Content-Type", "application/json"), nothing), "Content-Type"));
        assertNull(value(build(
                viewer("GET", null, "Content-Type", "application/json"), nothing), "Content-Type"));
    }

    @Test
    void hopByHopAndCloudFrontOwnedHeadersAreNeverForwarded() {
        OriginRequest request = build(viewer("POST", null,
                "Connection", "keep-alive, X-Hop",
                "X-Hop", "hop",
                "Keep-Alive", "timeout=5",
                "Proxy-Authorization", "Basic x",
                "TE", "trailers",
                "Upgrade", "websocket",
                "Transfer-Encoding", "chunked",
                "Content-Length", "10",
                "Expect", "100-continue",
                "X-Amz-Cf-Id", "forged",
                "X-Edge-Location", "forged",
                "X-Real-IP", "10.0.0.1",
                "X-Forwarded-Proto", "https",
                "X-Kept", "kept"),
                Forwarding.policies(null, orp(
                        selection("HeaderBehavior", "allViewer", "Headers"),
                        selection("CookieBehavior", "none", "Cookies"),
                        selection("QueryStringBehavior", "none", "QueryStrings")), false));

        assertEquals("kept", value(request, "X-Kept"));
        for (String dropped : List.of("Connection", "X-Hop", "Keep-Alive", "Proxy-Authorization", "TE",
                "Upgrade", "Transfer-Encoding", "Content-Length", "Expect", "X-Edge-Location",
                "X-Real-IP", "X-Forwarded-Proto")) {
            assertNull(value(request, dropped), dropped);
        }
        assertNotEquals("forged", value(request, "X-Amz-Cf-Id"));
        assertNotEquals(value(request, "X-Amz-Cf-Id"),
                value(build(viewer("GET", null), Forwarding.policies(null, null, false)), "X-Amz-Cf-Id"));
    }

    @Test
    void cachePolicyCompressionSettingsNormalizeAcceptEncoding() {
        Map<String, Object> both = parameters(none("Header"), none("Cookie"), none("QueryString"));
        both.put("EnableAcceptEncodingGzip", "true");
        both.put("EnableAcceptEncodingBrotli", "true");
        Map<String, Object> gzipOnly = parameters(none("Header"), none("Cookie"), none("QueryString"));
        gzipOnly.put("EnableAcceptEncodingGzip", "true");

        assertEquals("br,gzip", value(build(viewer("GET", null, "Accept-Encoding", "gzip, deflate, br;q=0.9"),
                Forwarding.policies(both, null, false)), "Accept-Encoding"));
        assertEquals("gzip", value(build(viewer("GET", null, "Accept-Encoding", "gzip, br"),
                Forwarding.policies(gzipOnly, null, false)), "Accept-Encoding"));
        assertEquals("identity", value(build(viewer("GET", null, "Accept-Encoding", "deflate"),
                Forwarding.policies(both, null, false)), "Accept-Encoding"));
        assertNull(value(build(viewer("GET", null), Forwarding.policies(both, null, false)),
                "Accept-Encoding"));
        assertEquals("gzip, deflate", value(build(viewer("GET", null, "Accept-Encoding", "gzip, deflate"),
                Forwarding.policies(null, orp(
                        selection("HeaderBehavior", "allViewer", "Headers"),
                        selection("CookieBehavior", "none", "Cookies"),
                        selection("QueryStringBehavior", "none", "QueryStrings")), false)),
                "Accept-Encoding"));
    }

    private static OriginRequest build(ViewerRequest viewer, Forwarding forwarding) {
        return CloudFrontOriginRequestBuilder.build(viewer, forwarding, VIA_HOST);
    }

    private static ViewerRequest viewer(String method, String rawQuery, String... headerPairs) {
        return new ViewerRequest(method, headers(headerPairs), rawQuery, "127.0.0.1", 54321,
                "http", "1.1");
    }

    private static ViewerRequest viewerOverHttp2(String scheme, String... headerPairs) {
        return new ViewerRequest("GET", headers(headerPairs), null, "127.0.0.1", 54321, scheme, "2.0");
    }

    private static List<Header> headers(String... headerPairs) {
        List<Header> headers = new ArrayList<>();
        for (int i = 0; i < headerPairs.length; i += 2) {
            headers.add(new Header(headerPairs[i], headerPairs[i + 1]));
        }
        return headers;
    }

    private static Forwarding cookies(String behavior, String... names) {
        return Forwarding.policies(null, orp(
                selection("HeaderBehavior", "none", "Headers"),
                selection("CookieBehavior", behavior, "Cookies", names),
                selection("QueryStringBehavior", "none", "QueryStrings")), false);
    }

    private static Forwarding queryStrings(String behavior, String... names) {
        return Forwarding.policies(null, orp(
                selection("HeaderBehavior", "none", "Headers"),
                selection("CookieBehavior", "none", "Cookies"),
                selection("QueryStringBehavior", behavior, "QueryStrings", names)), false);
    }

    private static Map<String, Object> orp(Map<String, Object> headers, Map<String, Object> cookies,
                                           Map<String, Object> queryStrings) {
        Map<String, Object> config = new LinkedHashMap<>();
        config.put("HeadersConfig", headers);
        config.put("CookiesConfig", cookies);
        config.put("QueryStringsConfig", queryStrings);
        return config;
    }

    private static Map<String, Object> parameters(Map<String, Object> headers,
                                                  Map<String, Object> cookies,
                                                  Map<String, Object> queryStrings) {
        return orp(headers, cookies, queryStrings);
    }

    private static Map<String, Object> none(String kind) {
        return selection(kind + "Behavior", "none", kind + "s");
    }

    private static Map<String, Object> selection(String behaviorKey, String behavior, String listKey,
                                                 String... names) {
        Map<String, Object> block = new LinkedHashMap<>();
        block.put(behaviorKey, behavior);
        if (names.length > 0) {
            block.put(listKey, List.of(names));
        }
        return block;
    }

    private static Map<String, Object> forwardedValues(boolean queryString, String cookiesForward,
                                                       List<String> cookieNames, List<String> headers) {
        Map<String, Object> values = new LinkedHashMap<>();
        values.put("QueryString", queryString);
        values.put("CookiesForward", cookiesForward);
        values.put("CookieNames", cookieNames);
        values.put("Headers", headers);
        return values;
    }

    private static List<String> names(OriginRequest request) {
        return request.headers().stream().map(Header::name).toList();
    }

    private static String value(OriginRequest request, String name) {
        List<String> values = request.headers().stream()
                .filter(header -> header.name().equalsIgnoreCase(name))
                .map(Header::value)
                .toList();
        assertTrue(values.size() <= 1, name + " appears more than once: " + values);
        return values.isEmpty() ? null : values.getFirst();
    }
}
