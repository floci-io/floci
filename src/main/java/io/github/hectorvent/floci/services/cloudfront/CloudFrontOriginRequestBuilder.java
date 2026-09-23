package io.github.hectorvent.floci.services.cloudfront;

import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Decides what CloudFront sends to a custom origin for one viewer request: the viewer headers,
 * cookies and query strings the matched cache behavior forwards, and the headers CloudFront adds on
 * its own. Implemented to the CloudFront Developer Guide:
 *
 * <ul>
 *   <li>With a cache policy, only what the cache policy's
 *       {@code ParametersInCacheKeyAndForwardedToOrigin} or the origin request policy selects is
 *       forwarded; the two selections are unioned.</li>
 *   <li>Without a cache policy (legacy cache settings), {@code ForwardedValues} applies, and viewer
 *       headers are forwarded by default except the ones the custom-origin request table says
 *       CloudFront removes.</li>
 *   <li>Every origin request carries {@code User-Agent: Amazon CloudFront} unless the viewer's
 *       {@code User-Agent} is forwarded, an {@code X-Forwarded-For} ending in the viewer address,
 *       CloudFront's {@code Via} hop and an {@code X-Amz-Cf-Id}. {@code Host} is the origin's own
 *       name unless the viewer's {@code Host} is forwarded.</li>
 * </ul>
 *
 * <p>This class holds no state and does no I/O. Origin custom headers are applied afterwards by the
 * transport, which lets them replace same-named viewer headers.
 */
final class CloudFrontOriginRequestBuilder {

    static final String CLOUDFRONT_USER_AGENT = "Amazon CloudFront";

    private static final Set<String> BODY_METHODS = Set.of("POST", "PUT", "PATCH", "DELETE");
    /**
     * Never copied from a viewer request: hop-by-hop and framing fields the transport owns, and
     * fields CloudFront removes or sets itself whatever the policy says.
     */
    private static final Set<String> NEVER_FORWARDED = Set.of(
            "connection", "keep-alive", "proxy-connection", "proxy-authenticate",
            "proxy-authorization", "te", "trailer", "transfer-encoding", "upgrade",
            "content-length", "expect", "x-amz-cf-id", "x-real-ip", "x-forwarded-proto");
    /** Removed by legacy cache settings unless the behavior's {@code Headers} names them. */
    private static final Set<String> LEGACY_REMOVED = Set.of(
            "accept", "accept-charset", "accept-language", "authorization", "host", "referer",
            "user-agent", "x-http-method-override");
    private static final String FORWARDED_PROTO = "CloudFront-Forwarded-Proto";
    private static final String VIEWER_ADDRESS = "CloudFront-Viewer-Address";
    private static final String VIEWER_HTTP_VERSION = "CloudFront-Viewer-Http-Version";
    /** The CloudFront request headers Floci can derive from a local viewer connection. */
    private static final List<String> GENERATED_HEADERS =
            List.of(FORWARDED_PROTO, VIEWER_ADDRESS, VIEWER_HTTP_VERSION);

    private CloudFrontOriginRequestBuilder() {
    }

    /** One request header field, in the order it was received or will be sent. */
    record Header(String name, String value) {
    }

    /**
     * The viewer request as CloudFront received it. {@code rawQuery} has CloudFront's signing
     * parameters removed already; {@code httpVersion} is the viewer's protocol version, such as
     * {@code 1.1} or {@code 2.0}.
     */
    record ViewerRequest(String method, List<Header> headers, String rawQuery,
                         String clientAddress, int clientPort, String scheme, String httpVersion) {
    }

    /**
     * The forwarding configuration of the cache behavior that matched the request. A behavior
     * without a cache policy uses legacy cache settings, where only {@code forwardedValues} applies.
     * The policy maps use the shapes {@link CloudFrontPolicyConfigCodec} stores.
     */
    record Forwarding(boolean legacy, Map<?, ?> cachePolicyParameters,
                      Map<?, ?> originRequestPolicy, Map<?, ?> forwardedValues,
                      boolean optionsCached) {

        static Forwarding legacy(Map<?, ?> forwardedValues, boolean optionsCached) {
            return new Forwarding(true, null, null, forwardedValues, optionsCached);
        }

        static Forwarding policies(Map<?, ?> cachePolicyParameters, Map<?, ?> originRequestPolicy,
                                   boolean optionsCached) {
            return new Forwarding(false, cachePolicyParameters, originRequestPolicy, null,
                    optionsCached);
        }
    }

    /**
     * What CloudFront sends to the origin before origin custom headers are applied. {@code rawQuery}
     * is {@code null} when no query string is forwarded. A {@code Host} header is present only when
     * the viewer's {@code Host} is forwarded.
     */
    record OriginRequest(List<Header> headers, String rawQuery) {

        static OriginRequest empty() {
            return new OriginRequest(List.of(), null);
        }
    }

    static OriginRequest build(ViewerRequest viewer, Forwarding forwarding, String viaHost) {
        Selections selections = forwarding.legacy()
                ? legacySelections(forwarding.forwardedValues())
                : policySelections(forwarding.cachePolicyParameters(),
                        forwarding.originRequestPolicy());
        Set<String> connectionTokens = connectionTokens(viewer.headers());
        boolean forwardsAuthorization = BODY_METHODS.contains(viewer.method())
                || ("OPTIONS".equals(viewer.method()) && !forwarding.optionsCached());

        List<Header> headers = new ArrayList<>();
        List<String> cookieHeaders = new ArrayList<>();
        List<String> forwardedFor = new ArrayList<>();
        List<String> via = new ArrayList<>();
        List<String> acceptEncoding = new ArrayList<>();
        boolean userAgentForwarded = false;
        for (Header header : viewer.headers()) {
            String name = normalize(header.name());
            if (name.isEmpty() || NEVER_FORWARDED.contains(name) || connectionTokens.contains(name)
                    || name.startsWith("x-edge-") || name.startsWith("cloudfront-")) {
                continue;
            }
            switch (name) {
                case "cookie" -> cookieHeaders.add(header.value());
                case "x-forwarded-for" -> forwardedFor.add(header.value());
                case "via" -> {
                    if (selections.forwardsHeader(name)) {
                        via.add(header.value());
                    }
                }
                case "accept-encoding" -> {
                    if (selections.normalizesAcceptEncoding()) {
                        acceptEncoding.add(header.value());
                    } else if (selections.forwardsHeader(name)) {
                        headers.add(header);
                    }
                }
                case "authorization" -> {
                    if (forwardsAuthorization || selections.forwardsHeader(name)) {
                        headers.add(header);
                    }
                }
                case "content-type" -> {
                    if (BODY_METHODS.contains(viewer.method()) || selections.forwardsHeader(name)) {
                        headers.add(header);
                    }
                }
                default -> {
                    if (selections.forwardsHeader(name)) {
                        headers.add(header);
                        userAgentForwarded |= "user-agent".equals(name);
                    }
                }
            }
        }

        if (!userAgentForwarded) {
            headers.add(new Header("User-Agent", CLOUDFRONT_USER_AGENT));
        }
        if (!acceptEncoding.isEmpty()) {
            String normalized = normalizedAcceptEncoding(acceptEncoding, selections);
            if (normalized != null) {
                headers.add(new Header("Accept-Encoding", normalized));
            }
        }
        String cookies = forwardedCookies(cookieHeaders, selections.cookies());
        if (cookies != null) {
            headers.add(new Header("Cookie", cookies));
        }
        for (String generated : GENERATED_HEADERS) {
            String value = selections.generatesHeader(normalize(generated))
                    ? generatedValue(generated, viewer) : null;
            if (value != null) {
                headers.add(new Header(generated, value));
            }
        }
        String clientForwardedFor = forwardedFor(forwardedFor, viewer.clientAddress());
        if (clientForwardedFor != null) {
            headers.add(new Header("X-Forwarded-For", clientForwardedFor));
        }
        via.add(viewerProtocolVersion(viewer.httpVersion()) + " " + viaHost + " (CloudFront)");
        headers.add(new Header("Via", String.join(", ", via)));
        headers.add(new Header("X-Amz-Cf-Id", requestId()));

        return new OriginRequest(List.copyOf(headers),
                forwardedQuery(viewer.rawQuery(), selections.queryStrings()));
    }

    private static Selections policySelections(Map<?, ?> cachePolicyParameters,
                                               Map<?, ?> originRequestPolicy) {
        List<Selection> headers = new ArrayList<>();
        List<Selection> cookies = new ArrayList<>();
        List<Selection> queryStrings = new ArrayList<>();
        for (Map<?, ?> source : new Map<?, ?>[] {cachePolicyParameters, originRequestPolicy}) {
            if (source == null) {
                continue;
            }
            headers.add(Selection.of(source.get("HeadersConfig"), "HeaderBehavior", "Headers", true));
            cookies.add(Selection.of(source.get("CookiesConfig"), "CookieBehavior", "Cookies", false));
            queryStrings.add(Selection.of(
                    source.get("QueryStringsConfig"), "QueryStringBehavior", "QueryStrings", false));
        }
        boolean gzip = cachePolicyParameters != null
                && isTrue(cachePolicyParameters.get("EnableAcceptEncodingGzip"));
        boolean brotli = cachePolicyParameters != null
                && isTrue(cachePolicyParameters.get("EnableAcceptEncodingBrotli"));
        return new Selections(false, headers, false, cookies, queryStrings, gzip, brotli,
                gzip || brotli);
    }

    /**
     * Legacy {@code ForwardedValues}: headers are forwarded by default apart from the ones CloudFront
     * removes, {@code Headers} adds names back (or everything with {@code *}), cookie names may use
     * {@code *} and {@code ?} wildcards, and {@code QueryString=true} forwards every query string
     * ({@code QueryStringCacheKeys} only narrows the cache key).
     */
    private static Selections legacySelections(Map<?, ?> forwardedValues) {
        List<String> headerNames = stringList(forwardedValues, "Headers");
        boolean allHeaders = headerNames.contains("*");
        Selection headers = allHeaders
                ? new Selection("all", List.of(), true, false)
                : new Selection("whitelist", headerNames, true, false);

        String cookiesForward = forwardedValues != null && forwardedValues.get("CookiesForward") != null
                ? forwardedValues.get("CookiesForward").toString() : "none";
        Selection cookies = new Selection(cookiesForward, stringList(forwardedValues, "CookieNames"),
                false, true);

        boolean queryString = forwardedValues != null && isTrue(forwardedValues.get("QueryString"));
        Selection queryStrings = new Selection(queryString ? "all" : "none", List.of(), false, false);

        return new Selections(true, List.of(headers), !allHeaders, List.of(cookies),
                List.of(queryStrings), true, true, !headers.includes("accept-encoding"));
    }

    private static Set<String> connectionTokens(List<Header> headers) {
        Set<String> tokens = new HashSet<>();
        for (Header header : headers) {
            if ("connection".equals(normalize(header.name())) && header.value() != null) {
                for (String token : header.value().split(",")) {
                    String normalized = normalize(token);
                    if (!normalized.isEmpty()) {
                        tokens.add(normalized);
                    }
                }
            }
        }
        return tokens;
    }

    /**
     * Accept-Encoding normalization: the formats caching is enabled for that the viewer accepts, as
     * {@code br,gzip}, {@code gzip} or {@code br}. A cache policy sends {@code identity} when the
     * viewer accepts none of them; legacy settings drop the header instead.
     */
    private static String normalizedAcceptEncoding(List<String> values, Selections selections) {
        Set<String> accepted = new HashSet<>();
        for (String value : values) {
            for (String coding : value.split(",")) {
                int parameters = coding.indexOf(';');
                String name = normalize(parameters >= 0 ? coding.substring(0, parameters) : coding);
                if (!name.isEmpty()) {
                    accepted.add(name);
                }
            }
        }
        boolean brotli = selections.brotli() && accepted.contains("br");
        boolean gzip = selections.gzip() && accepted.contains("gzip");
        if (brotli && gzip) {
            return "br,gzip";
        }
        if (brotli) {
            return "br";
        }
        if (gzip) {
            return "gzip";
        }
        return selections.legacy() ? null : "identity";
    }

    /**
     * The viewer cookies the behavior forwards, sorted by name as CloudFront sorts them. Pairs that are
     * not {@code name=value}, and names starting with {@code $}, are dropped.
     */
    private static String forwardedCookies(List<String> cookieHeaders, List<Selection> selections) {
        List<String[]> cookies = new ArrayList<>();
        for (String cookieHeader : cookieHeaders) {
            if (cookieHeader == null) {
                continue;
            }
            for (String pair : cookieHeader.split(";")) {
                int eq = pair.indexOf('=');
                String name = eq > 0 ? pair.substring(0, eq).trim() : "";
                if (name.isEmpty() || name.startsWith("$")) {
                    continue;
                }
                if (selections.stream().anyMatch(selection -> selection.includes(name))) {
                    cookies.add(new String[] {name, pair.substring(eq + 1).trim()});
                }
            }
        }
        if (cookies.isEmpty()) {
            return null;
        }
        cookies.sort(Comparator.comparing(cookie -> cookie[0]));
        List<String> pairs = new ArrayList<>(cookies.size());
        for (String[] cookie : cookies) {
            pairs.add(cookie[0] + "=" + cookie[1]);
        }
        return String.join("; ", pairs);
    }

    /** The raw query pairs the behavior forwards, in viewer order and with their original encoding. */
    private static String forwardedQuery(String rawQuery, List<Selection> selections) {
        if (rawQuery == null || rawQuery.isEmpty()) {
            return null;
        }
        if (selections.stream().anyMatch(selection -> "all".equals(selection.behavior()))) {
            return rawQuery;
        }
        List<String> kept = new ArrayList<>();
        for (String pair : rawQuery.split("&")) {
            if (pair.isEmpty()) {
                continue;
            }
            int eq = pair.indexOf('=');
            String name = decodeQueryName(eq >= 0 ? pair.substring(0, eq) : pair);
            if (selections.stream().anyMatch(selection -> selection.includes(name))) {
                kept.add(pair);
            }
        }
        return kept.isEmpty() ? null : String.join("&", kept);
    }

    private static String decodeQueryName(String rawName) {
        try {
            return URLDecoder.decode(rawName, StandardCharsets.UTF_8);
        } catch (IllegalArgumentException malformed) {
            // A name that is not valid percent-encoding can only match a policy entry literally.
            return rawName;
        }
    }

    private static String generatedValue(String header, ViewerRequest viewer) {
        return switch (header) {
            case FORWARDED_PROTO -> viewer.scheme() != null
                    ? viewer.scheme().toLowerCase(Locale.ROOT) : null;
            case VIEWER_ADDRESS -> viewer.clientAddress() != null && viewer.clientPort() > 0
                    ? viewer.clientAddress() + ":" + viewer.clientPort() : null;
            case VIEWER_HTTP_VERSION -> viewer.httpVersion();
            default -> null;
        };
    }

    /** CloudFront appends the viewer's address to any {@code X-Forwarded-For} the viewer sent. */
    private static String forwardedFor(List<String> viewerValues, String clientAddress) {
        List<String> values = new ArrayList<>(viewerValues);
        if (clientAddress != null && !clientAddress.isBlank()) {
            values.add(clientAddress);
        }
        return values.isEmpty() ? null : String.join(",", values);
    }

    private static String viewerProtocolVersion(String httpVersion) {
        return httpVersion == null || httpVersion.isBlank() ? "1.1" : httpVersion;
    }

    /** A request identifier shaped like CloudFront's: 40 random bytes, URL-safe Base64. */
    private static String requestId() {
        byte[] bytes = new byte[40];
        ThreadLocalRandom.current().nextBytes(bytes);
        return Base64.getUrlEncoder().encodeToString(bytes);
    }

    private static boolean isTrue(Object value) {
        return value instanceof Boolean bool ? bool : value != null && "true".equalsIgnoreCase(value.toString());
    }

    private static List<String> stringList(Map<?, ?> map, String key) {
        Object value = map != null ? map.get(key) : null;
        if (!(value instanceof List<?> list)) {
            return List.of();
        }
        List<String> strings = new ArrayList<>(list.size());
        for (Object item : list) {
            if (item != null) {
                strings.add(item.toString());
            }
        }
        return strings;
    }

    private static String normalize(String name) {
        return name == null ? "" : name.trim().toLowerCase(Locale.ROOT);
    }

    /** The resolved selections of one request, unioned across the policies that apply. */
    private record Selections(boolean legacy, List<Selection> headers, boolean legacyDefaults,
                              List<Selection> cookies, List<Selection> queryStrings,
                              boolean gzip, boolean brotli, boolean normalizesAcceptEncoding) {

        /**
         * Whether a viewer header is forwarded: a policy selects it, or legacy settings forward it
         * by default.
         */
        boolean forwardsHeader(String normalizedName) {
            if (headers.stream().anyMatch(selection -> selection.includes(normalizedName))) {
                return true;
            }
            return legacyDefaults && !LEGACY_REMOVED.contains(normalizedName);
        }

        /**
         * Whether CloudFront adds one of its own request headers: a whitelist or
         * {@code allViewerAndWhitelistCloudFront} list names it, or {@code allExcept} does not
         * exclude it (the managed AllViewerExceptHostHeader policy is documented to add them).
         */
        boolean generatesHeader(String normalizedName) {
            for (Selection selection : headers) {
                boolean generated = switch (selection.behavior()) {
                    case "whitelist", "allViewerAndWhitelistCloudFront" -> selection.lists(normalizedName);
                    case "allExcept" -> !selection.lists(normalizedName);
                    default -> false;
                };
                if (generated) {
                    return true;
                }
            }
            return false;
        }
    }

    /**
     * One policy's choice of names of one kind: headers, cookies or query strings. Header names match
     * case-insensitively, cookie and query string names case-sensitively. Legacy cookie allowlists may
     * use {@code *} and {@code ?} wildcards.
     */
    private record Selection(String behavior, List<String> names, boolean ignoreCase,
                             boolean wildcards) {

        static Selection of(Object block, String behaviorKey, String listKey, boolean ignoreCase) {
            if (!(block instanceof Map<?, ?> map) || map.get(behaviorKey) == null) {
                return new Selection("none", List.of(), ignoreCase, false);
            }
            return new Selection(map.get(behaviorKey).toString(), stringList(map, listKey),
                    ignoreCase, false);
        }

        boolean includes(String name) {
            return switch (behavior) {
                case "whitelist" -> lists(name);
                case "all", "allViewer", "allViewerAndWhitelistCloudFront" -> true;
                case "allExcept" -> !lists(name);
                default -> false;
            };
        }

        boolean lists(String name) {
            for (String candidate : names) {
                boolean matches = wildcards
                        ? wildcardMatches(candidate, name)
                        : ignoreCase ? candidate.equalsIgnoreCase(name) : candidate.equals(name);
                if (matches) {
                    return true;
                }
            }
            return false;
        }

        private static boolean wildcardMatches(String pattern, String name) {
            return wildcardMatches(pattern, 0, name, 0);
        }

        private static boolean wildcardMatches(String pattern, int p, String name, int n) {
            if (p == pattern.length()) {
                return n == name.length();
            }
            char c = pattern.charAt(p);
            if (c == '*') {
                for (int i = n; i <= name.length(); i++) {
                    if (wildcardMatches(pattern, p + 1, name, i)) {
                        return true;
                    }
                }
                return false;
            }
            return n < name.length() && (c == '?' || c == name.charAt(n))
                    && wildcardMatches(pattern, p + 1, name, n + 1);
        }
    }
}
