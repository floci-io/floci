package io.github.hectorvent.floci.services.apigatewayv2.proxy;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * The forwarding headers an HTTP API sends an {@code HTTP_PROXY} backend: the caller's chain moves to an
 * RFC 7239 {@code Forwarded} header and {@code X-Forwarded-For} names the gateway itself.
 *
 * <p>The API Gateway important notes say HTTP APIs translate {@code X-Forwarded-*} into {@code Forwarded}
 * and append the egress IP, Host and protocol. They do not give the syntax or the {@code X-Forwarded-For}
 * value, which follow third-party descriptions of observed behavior. Only {@code X-Forwarded-For} is
 * translated here.
 */
public final class HttpApiForwardedHeaders {

    private static final String FORWARDED = "Forwarded";
    private static final String FORWARDED_FOR = "X-Forwarded-For";
    private static final Pattern TOKEN = Pattern.compile("[A-Za-z0-9._-]+");

    private HttpApiForwardedHeaders() {
    }

    /** Returns a copy of {@code headers} with both headers replaced. */
    public static Map<String, List<String>> apply(Map<String, List<String>> headers, String clientIp,
                                                  String gatewayIp, String host, String proto) {
        Map<String, List<String>> result = new LinkedHashMap<>();
        List<String> chain = new ArrayList<>();
        for (Map.Entry<String, List<String>> header : headers.entrySet()) {
            if (FORWARDED_FOR.equalsIgnoreCase(header.getKey())) {
                for (String line : header.getValue()) {
                    for (String entry : line.split(",")) {
                        if (!entry.isBlank()) {
                            chain.add(entry.trim());
                        }
                    }
                }
            } else if (!FORWARDED.equalsIgnoreCase(header.getKey())) {
                result.put(header.getKey(), header.getValue());
            }
        }
        chain.add(clientIp);

        List<String> forElements = new ArrayList<>();
        for (String node : chain) {
            forElements.add("for=" + parameter(node(node)));
        }
        StringBuilder forwarded = new StringBuilder(String.join(",", forElements))
                .append(";by=").append(parameter(node(gatewayIp)));
        if (host != null && !host.isBlank()) {
            forwarded.append(";host=").append(parameter(host));
        }
        forwarded.append(";proto=").append(proto);

        result.put(FORWARDED, List.of(forwarded.toString()));
        result.put(FORWARDED_FOR, List.of(gatewayIp));
        return result;
    }

    private static String node(String address) {
        boolean ipv6 = address.indexOf(':') != address.lastIndexOf(':') && !address.startsWith("[");
        return ipv6 ? "[" + address + "]" : address;
    }

    /** RFC 7239 parameter values are tokens, so anything else, such as a port or an IPv6 node, is quoted. */
    private static String parameter(String value) {
        if (TOKEN.matcher(value).matches()) {
            return value;
        }
        return "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"") + "\"";
    }
}
