package io.github.hectorvent.floci.services.apigatewayv2.proxy;

import java.net.URI;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Applies HTTP API v2 RequestParameters to an outgoing {@link ProxyRequestBuilder}.
 *
 * <p>Each entry in the requestParameters map has the form
 * {@code (action):(target).(name) -> sourceValue}, where:
 * <ul>
 *   <li>{@code action} ∈ {overwrite, append, remove}</li>
 *   <li>{@code target} ∈ {path, header, querystring}</li>
 *   <li>{@code name} is the header or querystring parameter name (omitted for path)</li>
 *   <li>{@code sourceValue} is a literal or expression resolved by {@link ContextValueResolver}</li>
 * </ul>
 *
 * <p>For {@code overwrite:path}, the source value may contain embedded
 * {@code $request.path.X} / {@code $context.X} substrings which are resolved in-place.
 *
 * <p>When a source resolves to {@code null} (missing claim, missing header, etc.),
 * overwrite/append actions are silently skipped to match AWS behavior. {@code remove}
 * is unconditional.
 */
public class RequestParameterMapper {
    private static final Pattern EMBEDDED_VAR =
            Pattern.compile("\\$[a-zA-Z]+(\\.[a-zA-Z][a-zA-Z0-9_-]*)+");

    private final ContextValueResolver resolver;

    public RequestParameterMapper(ContextValueResolver resolver) {
        this.resolver = resolver;
    }

    public void apply(Map<String, String> requestParameters, ProxyRequestBuilder builder, RequestContext ctx) {
        if (requestParameters == null || requestParameters.isEmpty()) return;
        for (Map.Entry<String, String> entry : requestParameters.entrySet()) {
            String key = entry.getKey();
            String source = entry.getValue();

            int colonIdx = key.indexOf(':');
            if (colonIdx < 0) continue;
            String action = key.substring(0, colonIdx);
            String fullTarget = key.substring(colonIdx + 1);

            String resolvedValue;
            if (action.equals("remove")) {
                resolvedValue = null;
            } else if (fullTarget.equals("path")) {
                // path source may contain embedded variables that must be substituted in-place
                resolvedValue = substituteEmbeddedVars(source, ctx);
            } else {
                resolvedValue = resolver.resolve(source, ctx);
            }

            if (fullTarget.equals("path")) {
                if (action.equals("overwrite") && resolvedValue != null) {
                    URI current = URI.create(builder.url());
                    String newUrl = current.getScheme() + "://" + current.getRawAuthority() + resolvedValue;
                    if (current.getRawQuery() != null) newUrl += "?" + current.getRawQuery();
                    builder.setUrl(newUrl);
                }
            } else if (fullTarget.startsWith("header.")) {
                String headerName = fullTarget.substring("header.".length());
                switch (action) {
                    case "overwrite" -> { if (resolvedValue != null) builder.overwriteHeader(headerName, resolvedValue); }
                    case "append" -> { if (resolvedValue != null) builder.appendHeader(headerName, resolvedValue); }
                    case "remove" -> builder.removeHeader(headerName);
                }
            } else if (fullTarget.startsWith("querystring.")) {
                String paramName = fullTarget.substring("querystring.".length());
                switch (action) {
                    case "overwrite" -> { if (resolvedValue != null) builder.overwriteQuery(paramName, resolvedValue); }
                    case "append" -> { if (resolvedValue != null) builder.appendQuery(paramName, resolvedValue); }
                    case "remove" -> builder.removeQuery(paramName);
                }
            }
        }
    }

    private String substituteEmbeddedVars(String template, RequestContext ctx) {
        if (template == null) return null;
        if (!template.contains("$")) return template;
        Matcher m = EMBEDDED_VAR.matcher(template);
        StringBuilder out = new StringBuilder();
        while (m.find()) {
            String resolved = resolver.resolve(m.group(), ctx);
            m.appendReplacement(out, Matcher.quoteReplacement(resolved == null ? "" : resolved));
        }
        m.appendTail(out);
        return out.toString();
    }
}
