package io.github.hectorvent.floci.services.apigatewayv2.proxy;

import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Substitutes {paramName} and {paramName+} placeholders in URI templates
 * against a map of captured path parameters. Used by HttpProxyInvoker to
 * resolve the target URL from an integration's IntegrationUri (e.g.
 * {@code http://backend/{proxy}} or {@code http://backend/{proxy+}}) and by
 * RequestParameterMapper to apply overwrite:path values.
 *
 * <p>Both {@code {name}} and {@code {name+}} resolve to the same parameter
 * keyed by {@code name} — the trailing {@code +} is AWS's "greedy" marker
 * used in route keys (e.g. {@code "ANY /wallet/{proxy+}"}) to capture
 * multi-segment suffixes, and it's accepted in IntegrationUri templates
 * too so the URI can mirror the route shape verbatim.
 *
 * <p>Missing parameters are replaced with the empty string, mirroring AWS
 * API Gateway's behavior for unresolved placeholders.
 */
public final class PathTemplateResolver {
    private static final Pattern PLACEHOLDER = Pattern.compile("\\{([a-zA-Z_][a-zA-Z0-9_]*)\\+?\\}");

    private PathTemplateResolver() {}

    public static String resolve(String template, Map<String, String> pathParams) {
        if (template == null) return null;
        Matcher m = PLACEHOLDER.matcher(template);
        StringBuilder out = new StringBuilder();
        while (m.find()) {
            String key = m.group(1);
            String value = pathParams == null ? "" : pathParams.getOrDefault(key, "");
            m.appendReplacement(out, Matcher.quoteReplacement(value));
        }
        m.appendTail(out);
        return out.toString();
    }
}
