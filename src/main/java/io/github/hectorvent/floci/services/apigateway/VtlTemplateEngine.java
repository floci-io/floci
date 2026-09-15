package io.github.hectorvent.floci.services.apigateway;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.hectorvent.floci.core.common.vtl.VtlUtilFunctions;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import io.quarkus.runtime.annotations.RegisterForReflection;
import org.apache.velocity.VelocityContext;
import org.apache.velocity.app.VelocityEngine;
import org.apache.velocity.runtime.ParserPoolImpl;
import org.apache.velocity.runtime.RuntimeConstants;
import org.apache.velocity.runtime.directive.Break;
import org.apache.velocity.runtime.directive.Define;
import org.apache.velocity.runtime.directive.Evaluate;
import org.apache.velocity.runtime.directive.Foreach;
import org.apache.velocity.runtime.directive.Include;
import org.apache.velocity.runtime.directive.Macro;
import org.apache.velocity.runtime.directive.Parse;
import org.apache.velocity.runtime.directive.Stop;
import org.apache.velocity.runtime.parser.StandardParser;
import org.apache.velocity.runtime.resource.ResourceCacheImpl;
import org.apache.velocity.runtime.resource.ResourceManagerImpl;
import org.apache.velocity.runtime.resource.loader.FileResourceLoader;
import org.apache.velocity.runtime.resource.loader.StringResourceLoader;
import org.apache.velocity.runtime.resource.util.StringResourceRepositoryImpl;
import org.apache.velocity.util.introspection.TypeConversionHandlerImpl;
import org.apache.velocity.util.introspection.UberspectImpl;

import java.io.StringWriter;
import java.util.AbstractMap;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;

/**
 * Evaluates AWS API Gateway VTL (Velocity Template Language) mapping templates.
 *
 * <p>Provides the standard API Gateway context variables:
 * {@code $input}, {@code $util}, {@code $context}, {@code $stageVariables}.
 */
@ApplicationScoped
@RegisterForReflection(targets = {
        VtlTemplateEngine.InputVariable.class,
        VtlTemplateEngine.ParameterMap.class,
        VtlTemplateEngine.UtilVariable.class,
        VtlTemplateEngine.ResponseOverride.class,
        UberspectImpl.class,
        TypeConversionHandlerImpl.class,
        ResourceManagerImpl.class,
        ResourceCacheImpl.class,
        ParserPoolImpl.class,
        FileResourceLoader.class,
        StringResourceLoader.class,
        StringResourceRepositoryImpl.class,
        Foreach.class,
        Include.class,
        Parse.class,
        Macro.class,
        Evaluate.class,
        Break.class,
        Define.class,
        Stop.class,
        StandardParser.class,
})
public class VtlTemplateEngine {

    private final VelocityEngine engine;
    private final ObjectMapper objectMapper;

    @Inject
    public VtlTemplateEngine(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
        this.engine = new VelocityEngine();
        engine.setProperty(RuntimeConstants.INPUT_ENCODING, "UTF-8");
        engine.setProperty(RuntimeConstants.RUNTIME_LOG_NAME, "io.github.hectorvent.floci.vtl");
        engine.setProperty(RuntimeConstants.RESOURCE_LOADERS, "string");
        engine.setProperty("resource.loader.string.class",
                "org.apache.velocity.runtime.resource.loader.StringResourceLoader");
        engine.init();
    }

    /**
     * Result returned by {@link #evaluate(String, VtlContext)} that carries both the
     * rendered template body and any {@code $context.responseOverride} values the template
     * set during evaluation.
     */
    public record EvaluateResult(
            String body,
            Integer statusOverride,
            Map<String, String> headerOverrides
    ) {}

    /**
     * Evaluates a VTL template with the given request context.
     *
     * @param template the VTL template string
     * @param ctx      the request context
     * @return result containing the rendered body and any {@code $context.responseOverride} assignments
     */
    public EvaluateResult evaluate(String template, VtlContext ctx) {
        ResponseOverride override = new ResponseOverride();
        if (template == null || template.isEmpty()) {
            return new EvaluateResult(ctx.body() != null ? ctx.body() : "", null, Map.of());
        }

        VelocityContext vc = new VelocityContext();
        vc.put("input", new InputVariable(ctx, objectMapper));
        vc.put("util", new UtilVariable(objectMapper));
        vc.put("context", buildContextMap(ctx, override));
        vc.put("stageVariables", ctx.stageVariables() != null ? ctx.stageVariables() : Map.of());

        StringWriter writer = new StringWriter();
        engine.evaluate(vc, writer, "apigw-template", template);
        return new EvaluateResult(
                writer.toString(),
                override.getStatus(),
                override.getHeader().isEmpty() ? Map.of() : Map.copyOf(override.getHeader()));
    }

    private Map<String, Object> buildContextMap(VtlContext ctx, ResponseOverride responseOverride) {
        Map<String, Object> map = new HashMap<>();
        map.put("requestId", ctx.requestId());
        map.put("stage", ctx.stage());
        map.put("httpMethod", ctx.httpMethod());
        map.put("resourcePath", ctx.resourcePath());
        map.put("accountId", ctx.accountId());

        Map<String, String> identity = new HashMap<>();
        identity.put("sourceIp", "127.0.0.1");
        map.put("identity", identity);

        if (ctx.authorizer() != null) {
            map.put("authorizer", ctx.authorizer());
        }

        if (ctx.gatewayResponseContext() != null) {
            // A gateway response sees the full request context plus $context.error.
            map.putAll(ctx.gatewayResponseContext());
        }

        map.put("responseOverride", responseOverride);

        return map;
    }

    /**
     * Mutable holder for {@code $context.responseOverride} assignments made inside VTL templates.
     *
     * <p>Velocity calls the JavaBean setters when a template contains:
     * <pre>{@code
     * #set($context.responseOverride.status = 500)
     * #set($context.responseOverride.header["Content-Type"] = "application/problem+json")
     * }</pre>
     *
     * <p>The first form calls {@link #setStatus(Integer)}.
     * The second form calls {@link #getHeader()} (which returns a mutable Map) followed by
     * {@code map.put("Content-Type", "application/problem+json")}.
     */
    public static class ResponseOverride {
        private Integer status;
        private final Map<String, String> header = new HashMap<>();

        public Integer getStatus() {
            return status;
        }

        public void setStatus(Integer status) {
            this.status = status;
        }

        public Map<String, String> getHeader() {
            return header;
        }
    }

    // ────────── Context variable classes ──────────

    /**
     * Request context for VTL evaluation. {@code gatewayResponseContext} is only set while a
     * gateway response template renders: its entries are merged into {@code $context}, which is
     * how {@code $context.error} ({@code message}, {@code messageString}, {@code responseType},
     * {@code validationErrorString}), {@code $context.path} and the other request fields reach it.
     */
    public record VtlContext(
            String body,
            Map<String, String> headers,
            Map<String, String> queryParams,
            Map<String, String> pathParams,
            String stage,
            String httpMethod,
            String resourcePath,
            String requestId,
            String accountId,
            Map<String, String> stageVariables,
            Map<String, Object> authorizer,
            Map<String, Object> gatewayResponseContext
    ) {
        public VtlContext(String body,
                          Map<String, String> headers,
                          Map<String, String> queryParams,
                          Map<String, String> pathParams,
                          String stage,
                          String httpMethod,
                          String resourcePath,
                          String requestId,
                          String accountId,
                          Map<String, String> stageVariables,
                          Map<String, Object> authorizer) {
            this(body, headers, queryParams, pathParams, stage, httpMethod, resourcePath, requestId, accountId,
                    stageVariables, authorizer, null);
        }
    }

    /**
     * The {@code $input} variable available in API Gateway VTL templates.
     */
    public static class InputVariable {

        private final VtlContext ctx;
        private final ObjectMapper objectMapper;

        public InputVariable(VtlContext ctx, ObjectMapper objectMapper) {
            this.ctx = ctx;
            this.objectMapper = objectMapper;
        }

        /** Returns the raw request body. */
        public String body() {
            return ctx.body() != null ? ctx.body() : "";
        }

        /**
         * Evaluates a simple JSON path against the request body and returns the result as a JSON string.
         * Supports {@code '$'} (whole body) and dot-notation paths like {@code '$.foo.bar'}.
         */
        public String json(String path) {
            if (ctx.body() == null || ctx.body().isEmpty()) {
                return "{}";
            }
            try {
                JsonNode root = objectMapper.readTree(ctx.body());
                JsonNode target = resolvePath(root, path);
                return objectMapper.writeValueAsString(target);
            } catch (Exception e) {
                return ctx.body();
            }
        }

        /**
         * Evaluates a simple JSON path and returns the result as an object navigable in VTL.
         */
        public Object path(String path) {
            if (ctx.body() == null || ctx.body().isEmpty()) {
                return Map.of();
            }
            try {
                JsonNode root = objectMapper.readTree(ctx.body());
                JsonNode target = resolvePath(root, path);
                return objectMapper.convertValue(target, Object.class);
            } catch (Exception e) {
                return Map.of();
            }
        }

        /** Searches all parameter types for the given name (path, querystring, header). */
        public String params(String paramName) {
            if (ctx.pathParams() != null && ctx.pathParams().containsKey(paramName)) {
                return ctx.pathParams().get(paramName);
            }
            if (ctx.queryParams() != null && ctx.queryParams().containsKey(paramName)) {
                return ctx.queryParams().get(paramName);
            }
            if (ctx.headers() != null && ctx.headers().containsKey(paramName)) {
                return ctx.headers().get(paramName);
            }
            return "";
        }

        /** Returns request parameters organized by type. */
        public Map<String, Map<String, String>> params() {
            Map<String, Map<String, String>> params = new HashMap<>();
            params.put("querystring", new ParameterMap(ctx.queryParams()));
            params.put("path", new ParameterMap(ctx.pathParams()));
            params.put("header", new ParameterMap(ctx.headers()));
            return params;
        }

        static JsonNode resolvePath(JsonNode root, String path) {
            if (path == null || "$".equals(path)) {
                return root;
            }
            String normalized = path.startsWith("$.") ? path.substring(2) : path;
            JsonNode current = root;
            for (String segment : normalized.split("\\.")) {
                if (current == null || current.isMissingNode()) break;
                // Handle array indexing: "items[0]" or "[0]"
                int bracketIdx = segment.indexOf('[');
                if (bracketIdx >= 0) {
                    if (bracketIdx > 0) {
                        current = current.path(segment.substring(0, bracketIdx));
                    }
                    // Extract all indices: [0][1] etc.
                    String rest = segment.substring(bracketIdx);
                    while (rest.startsWith("[")) {
                        int close = rest.indexOf(']');
                        if (close < 0) break;
                        int index = Integer.parseInt(rest.substring(1, close));
                        current = current.path(index);
                        rest = rest.substring(close + 1);
                    }
                } else {
                    current = current.path(segment);
                }
            }
            return current;
        }
    }

    /** Reflection-safe map exposed to Velocity templates in native images. */
    public static class ParameterMap extends AbstractMap<String, String> {

        private final Map<String, String> values;

        public ParameterMap(Map<String, String> values) {
            this.values = values != null ? values : Map.of();
        }

        @Override
        public String get(Object key) {
            return values.get(key);
        }

        @Override
        public Set<String> keySet() {
            return values.keySet();
        }

        @Override
        public Set<Entry<String, String>> entrySet() {
            return values.entrySet();
        }
    }

    /**
     * The {@code $util} variable available in API Gateway VTL templates.
     */
    public static class UtilVariable {

        private final ObjectMapper objectMapper;

        public UtilVariable(ObjectMapper objectMapper) {
            this.objectMapper = objectMapper;
        }

        /**
         * Escapes a string using EcmaScript/JavaScript string rules.
         * Matches AWS API Gateway behavior (Apache Commons Lang escapeEcmaScript).
         */
        public String escapeJavaScript(String s) {
            return VtlUtilFunctions.escapeJavaScript(s);
        }

        /** URL-encodes a string. */
        public String urlEncode(String s) {
            return VtlUtilFunctions.urlEncode(s);
        }

        /** URL-decodes a string. */
        public String urlDecode(String s) {
            return VtlUtilFunctions.urlDecode(s);
        }

        /** Base64-encodes a string. */
        public String base64Encode(String s) {
            return VtlUtilFunctions.base64Encode(s);
        }

        /** Base64-decodes a string. */
        public String base64Decode(String s) {
            return VtlUtilFunctions.base64Decode(s);
        }

        /** Parses a JSON string into a Map/List structure navigable in VTL. */
        public Object parseJson(String s) {
            return VtlUtilFunctions.parseJson(objectMapper, s);
        }
    }
}
