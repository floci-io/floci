package io.github.hectorvent.floci.services.iot;

import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;

/**
 * Parses AWS IoT custom-authorizer parameters out of the MQTT CONNECT username.
 *
 * <p>The AWS IoT Device SDK v2 (and clients like mqtt.js configured the same way) encode the
 * authorizer name, token, and token signature into the username as a query string appended to
 * the real username, for example:
 *
 * <pre>
 *   myuser?x-amz-customauthorizer-name=MyAuthorizer&amp;x-amz-customauthorizer-signature=SIG&amp;MyTokenKey=TOKEN
 * </pre>
 *
 * @param baseUsername the username portion before the {@code ?} (may be empty)
 * @param params       decoded query parameters (authorizer name, signature, and the token key)
 */
public record CustomAuthParams(String baseUsername, Map<String, String> params) {

    public static final String AUTHORIZER_NAME_KEY = "x-amz-customauthorizer-name";
    public static final String SIGNATURE_KEY = "x-amz-customauthorizer-signature";

    public static CustomAuthParams parse(String username) {
        if (username == null) {
            return new CustomAuthParams("", Map.of());
        }
        int q = username.indexOf('?');
        if (q < 0) {
            return new CustomAuthParams(username, Map.of());
        }
        String base = username.substring(0, q);
        String query = username.substring(q + 1);
        Map<String, String> params = new HashMap<>();
        for (String pair : query.split("&")) {
            if (pair.isEmpty()) {
                continue;
            }
            int eq = pair.indexOf('=');
            if (eq < 0) {
                params.put(decode(pair), "");
            } else {
                params.put(decode(pair.substring(0, eq)), decode(pair.substring(eq + 1)));
            }
        }
        return new CustomAuthParams(base, params);
    }

    public String authorizerName() {
        return params.get(AUTHORIZER_NAME_KEY);
    }

    public String signature() {
        return params.get(SIGNATURE_KEY);
    }

    public String token(String tokenKeyName) {
        return tokenKeyName == null ? null : params.get(tokenKeyName);
    }

    private static String decode(String value) {
        return URLDecoder.decode(value, StandardCharsets.UTF_8);
    }
}
