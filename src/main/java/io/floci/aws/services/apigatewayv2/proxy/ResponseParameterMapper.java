package io.floci.aws.services.apigatewayv2.proxy;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Applies HTTP API v2 integration ResponseParameters to a backend response.
 *
 * <p>The mappings applied are the ones keyed by the backend's status code. Each has the form
 * {@code overwrite:statuscode -> sourceValue} or {@code (action):header.(name) -> sourceValue}
 * with {@code action} ∈ {overwrite, append, remove}. Header names match case-insensitively.
 *
 * <p>Source values read the backend response as it arrived, before any mapping:
 * <ul>
 *   <li>{@code $response.header.<name>}: the header's values joined with commas</li>
 *   <li>{@code $response.body}: the body as text, null when it is empty</li>
 *   <li>{@code $response.body.<JSONPath>}: a value selected from a JSON body</li>
 *   <li>anything else, such as {@code $context.<name>}, {@code $stageVariables.<name>} or a
 *       literal, as {@link ContextValueResolver} resolves it</li>
 * </ul>
 *
 * <p>As for RequestParameters, an overwrite or append whose source resolves to {@code null} is
 * skipped, and so is an {@code overwrite:statuscode} whose value is not a status code from 100 to
 * 599.
 */
public class ResponseParameterMapper {
    private static final String RESPONSE_HEADER = "$response.header.";
    private static final String RESPONSE_BODY = "$response.body";

    private final ContextValueResolver resolver;

    public ResponseParameterMapper(ContextValueResolver resolver) {
        this.resolver = resolver;
    }

    public ProxyResult apply(Map<String, Map<String, String>> responseParameters, ProxyResult result,
                             RequestContext ctx) {
        Map<String, String> mappings = responseParameters == null ? null
                : responseParameters.get(String.valueOf(result.statusCode()));
        if (mappings == null || mappings.isEmpty()) {
            return result;
        }
        int statusCode = result.statusCode();
        Map<String, List<String>> headers = new LinkedHashMap<>();
        if (result.headers() != null) {
            result.headers().forEach((name, values) -> headers.put(name, new ArrayList<>(values)));
        }
        for (Map.Entry<String, String> mapping : mappings.entrySet()) {
            String key = mapping.getKey();
            if (key.equals("overwrite:statuscode")) {
                Integer status = statusCode(resolve(mapping.getValue(), result, ctx));
                if (status != null) statusCode = status;
                continue;
            }
            int colonIdx = key.indexOf(':');
            String action = key.substring(0, colonIdx);
            String headerName = key.substring(colonIdx + 1 + "header.".length());
            if (action.equals("remove")) {
                removeHeader(headers, headerName);
                continue;
            }
            String value = resolve(mapping.getValue(), result, ctx);
            if (value == null) continue;
            if (action.equals("overwrite")) {
                removeHeader(headers, headerName);
            }
            headers.computeIfAbsent(existingName(headers, headerName), k -> new ArrayList<>()).add(value);
        }
        return new ProxyResult(statusCode, headers, result.body());
    }

    private String resolve(String source, ProxyResult result, RequestContext ctx) {
        if (source == null) return null;
        if (source.startsWith(RESPONSE_HEADER)) {
            String name = source.substring(RESPONSE_HEADER.length());
            List<String> values = result.headers() == null ? null
                    : result.headers().get(existingName(result.headers(), name));
            return values == null || values.isEmpty() ? null : String.join(",", values);
        }
        if (source.equals(RESPONSE_BODY)) {
            return bodyText(result);
        }
        if (source.startsWith(RESPONSE_BODY + ".")) {
            return resolver.jsonPath(bodyText(result), source.substring(RESPONSE_BODY.length() + 1));
        }
        return resolver.resolve(source, ctx);
    }

    private static String bodyText(ProxyResult result) {
        return result.body() == null || result.body().length == 0
                ? null : new String(result.body(), StandardCharsets.UTF_8);
    }

    private static Integer statusCode(String value) {
        if (value == null || !value.matches("[0-9]{3}")) return null;
        int status = Integer.parseInt(value);
        return status >= 100 && status <= 599 ? status : null;
    }

    /** The name the header is stored under, matched case-insensitively, or {@code name} when absent. */
    private static String existingName(Map<String, List<String>> headers, String name) {
        for (String existing : headers.keySet()) {
            if (existing.equalsIgnoreCase(name)) return existing;
        }
        return name;
    }

    private static void removeHeader(Map<String, List<String>> headers, String name) {
        headers.keySet().removeIf(existing -> existing.equalsIgnoreCase(name));
    }
}
