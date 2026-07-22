package io.github.hectorvent.floci.services.cloudfront;

import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.core.common.XmlBuilder;
import io.github.hectorvent.floci.core.common.XmlParser;
import io.github.hectorvent.floci.core.common.XmlParser.XmlElement;

import java.net.URI;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * Parses, re-serializes and applies a CloudFront {@code ResponseHeadersPolicyConfig}.
 *
 * <p>CloudFront lets a response-headers policy attach three kinds of headers to the responses it
 * returns to viewers: a {@code SecurityHeadersConfig} (HSTS, {@code X-Content-Type-Options},
 * {@code X-Frame-Options}, {@code Referrer-Policy}, {@code X-XSS-Protection},
 * {@code Content-Security-Policy}), a {@code CorsConfig} (the {@code Access-Control-*} headers) and a
 * free-form {@code CustomHeadersConfig}. It can also strip headers via {@code RemoveHeadersConfig}.
 * Each header carries an {@code Override} flag deciding whether it replaces one an origin already set.
 *
 * <p>The parsed shape is kept in the policy's generic {@code config} map (nested maps/lists mirroring
 * the wire), so it round-trips through describe without a bespoke model, and the serving path reads
 * the same shape to compute the headers to apply. {@code ServerTimingHeadersConfig} is round-tripped
 * but not applied — it is sampling based and carries no meaning in the emulator.
 */
final class ResponseHeadersPolicyConfigCodec {

    private ResponseHeadersPolicyConfigCodec() {
    }

    /** One header the policy contributes to a response, with its override-the-origin flag. */
    record PolicyHeader(String name, String value, boolean override) {
    }

    /** The headers a policy adds and the header names it removes, for the serving path to apply. */
    record Directives(List<PolicyHeader> add, List<String> remove) {
    }

    // ── Parse ──────────────────────────────────────────────────────────────────

    /** Parses a {@code ResponseHeadersPolicyConfig} body into the nested {@code config} shape. */
    static Map<String, Object> parse(String body) {
        Map<String, Object> config = new LinkedHashMap<>();
        XmlElement root = configRoot(body);
        if (root == null) {
            throw new AwsException("InvalidArgument",
                    "The request body must contain a valid ResponseHeadersPolicyConfig element.", 400);
        }
        XmlElement security = child(root, "SecurityHeadersConfig");
        if (security != null) {
            Map<String, Object> sec = new LinkedHashMap<>();
            for (String block : List.of("XSSProtection", "FrameOptions", "ReferrerPolicy",
                    "ContentSecurityPolicy", "ContentTypeOptions", "StrictTransportSecurity")) {
                XmlElement el = child(security, block);
                if (el != null) {
                    sec.put(block, scalarChildren(el));
                }
            }
            config.put("SecurityHeadersConfig", sec);
        }
        XmlElement cors = child(root, "CorsConfig");
        if (cors != null) {
            config.put("CorsConfig", parseCors(cors));
        }
        XmlElement custom = child(root, "CustomHeadersConfig");
        if (custom != null) {
            List<Map<String, String>> items = itemsAsMaps(custom);
            validateDeclaredQuantity(custom, items.size(), "CustomHeadersConfig");
            config.put("CustomHeadersConfig", items);
        }
        XmlElement remove = child(root, "RemoveHeadersConfig");
        if (remove != null) {
            List<String> items = itemLeafValues(remove, "Header");
            validateDeclaredQuantity(remove, items.size(), "RemoveHeadersConfig");
            config.put("RemoveHeadersConfig", items);
        }
        XmlElement serverTiming = child(root, "ServerTimingHeadersConfig");
        if (serverTiming != null) {
            config.put("ServerTimingHeadersConfig", scalarChildren(serverTiming));
        }
        return config;
    }

    private static Map<String, Object> parseCors(XmlElement cors) {
        Map<String, Object> map = new LinkedHashMap<>();
        for (String list : List.of("AccessControlAllowOrigins", "AccessControlAllowHeaders",
                "AccessControlAllowMethods", "AccessControlExposeHeaders")) {
            XmlElement el = child(cors, list);
            if (el != null) {
                List<String> items = itemLeafValues(el, null);
                validateDeclaredQuantity(el, items.size(), list);
                map.put(list, items);
            }
        }
        for (String scalar : List.of("AccessControlAllowCredentials", "AccessControlMaxAgeSec",
                "OriginOverride")) {
            String value = childText(cors, scalar);
            if (value != null) {
                map.put(scalar, value);
            }
        }
        return map;
    }

    // ── Serialize ──────────────────────────────────────────────────────────────

    /** Emits the parsed config back as the inner XML of {@code ResponseHeadersPolicyConfig}. */
    @SuppressWarnings("unchecked")
    static void serialize(XmlBuilder xml, Map<String, Object> config) {
        if (config == null) {
            return;
        }
        Map<String, Object> security = (Map<String, Object>) config.get("SecurityHeadersConfig");
        if (security != null) {
            xml.start("SecurityHeadersConfig");
            for (Map.Entry<String, Object> block : security.entrySet()) {
                xml.start(block.getKey());
                for (Map.Entry<String, String> field : ((Map<String, String>) block.getValue()).entrySet()) {
                    xml.elem(field.getKey(), field.getValue());
                }
                xml.end(block.getKey());
            }
            xml.end("SecurityHeadersConfig");
        }
        Map<String, Object> cors = (Map<String, Object>) config.get("CorsConfig");
        if (cors != null) {
            xml.start("CorsConfig");
            serializeCorsList(xml, cors, "AccessControlAllowOrigins", "Origin");
            serializeCorsList(xml, cors, "AccessControlAllowHeaders", "Header");
            serializeCorsList(xml, cors, "AccessControlAllowMethods", "Method");
            serializeCorsList(xml, cors, "AccessControlExposeHeaders", "Header");
            for (String scalar : List.of("AccessControlAllowCredentials", "AccessControlMaxAgeSec",
                    "OriginOverride")) {
                if (cors.get(scalar) != null) {
                    xml.elem(scalar, cors.get(scalar).toString());
                }
            }
            xml.end("CorsConfig");
        }
        List<Map<String, String>> custom = (List<Map<String, String>>) config.get("CustomHeadersConfig");
        if (custom != null) {
            xml.start("CustomHeadersConfig").elem("Quantity", custom.size()).start("Items");
            for (Map<String, String> header : custom) {
                xml.start("ResponseHeadersPolicyCustomHeader")
                        .elem("Header", header.getOrDefault("Header", ""))
                        .elem("Value", header.getOrDefault("Value", ""))
                        .elem("Override", header.getOrDefault("Override", "false"))
                        .end("ResponseHeadersPolicyCustomHeader");
            }
            xml.end("Items").end("CustomHeadersConfig");
        }
        List<String> remove = (List<String>) config.get("RemoveHeadersConfig");
        if (remove != null) {
            xml.start("RemoveHeadersConfig").elem("Quantity", remove.size()).start("Items");
            for (String name : remove) {
                xml.start("ResponseHeadersPolicyRemoveHeader").elem("Header", name)
                        .end("ResponseHeadersPolicyRemoveHeader");
            }
            xml.end("Items").end("RemoveHeadersConfig");
        }
        Map<String, Object> serverTiming = (Map<String, Object>) config.get("ServerTimingHeadersConfig");
        if (serverTiming != null) {
            xml.start("ServerTimingHeadersConfig");
            for (Map.Entry<String, Object> e : serverTiming.entrySet()) {
                xml.elem(e.getKey(), String.valueOf(e.getValue()));
            }
            xml.end("ServerTimingHeadersConfig");
        }
    }

    @SuppressWarnings("unchecked")
    private static void serializeCorsList(XmlBuilder xml, Map<String, Object> cors, String key, String itemName) {
        List<String> values = (List<String>) cors.get(key);
        if (values == null) {
            return;
        }
        xml.start(key).elem("Quantity", values.size()).start("Items");
        for (String v : values) {
            xml.elem(itemName, v);
        }
        xml.end("Items").end(key);
    }

    // ── Apply ──────────────────────────────────────────────────────────────────

    /** Computes the headers this policy contributes to a response and the headers it removes. */
    static Directives directives(Map<String, Object> config) {
        return directives(config, null);
    }

    /** Computes policy directives for a viewer request, including request-origin-aware CORS. */
    static Directives directives(Map<String, Object> config, String viewerOrigin) {
        return directives(config, viewerOrigin, false);
    }

    /** Computes policy directives, including the preflight-only CORS response fields when requested. */
    @SuppressWarnings("unchecked")
    static Directives directives(Map<String, Object> config, String viewerOrigin,
                                 boolean preflightRequest) {
        List<PolicyHeader> add = new ArrayList<>();
        List<String> remove = new ArrayList<>();
        if (config == null) {
            return new Directives(add, remove);
        }

        Map<String, Object> security = (Map<String, Object>) config.get("SecurityHeadersConfig");
        if (security != null) {
            securityHeaders(security, add);
        }

        List<Map<String, String>> custom = (List<Map<String, String>>) config.get("CustomHeadersConfig");
        if (custom != null) {
            for (Map<String, String> h : custom) {
                String name = h.get("Header");
                if (name != null) {
                    add.add(new PolicyHeader(name, h.getOrDefault("Value", ""),
                            Boolean.parseBoolean(h.getOrDefault("Override", "false"))));
                }
            }
        }

        Map<String, Object> cors = (Map<String, Object>) config.get("CorsConfig");
        if (cors != null) {
            corsHeaders(cors, viewerOrigin, preflightRequest, add);
        }

        List<String> removeCfg = (List<String>) config.get("RemoveHeadersConfig");
        if (removeCfg != null) {
            remove.addAll(removeCfg);
        }
        return new Directives(add, remove);
    }

    @SuppressWarnings("unchecked")
    private static void securityHeaders(Map<String, Object> security, List<PolicyHeader> add) {
        Map<String, String> hsts = (Map<String, String>) security.get("StrictTransportSecurity");
        if (hsts != null) {
            StringBuilder v = new StringBuilder("max-age=").append(hsts.getOrDefault("AccessControlMaxAgeSec", "0"));
            if (Boolean.parseBoolean(hsts.get("IncludeSubdomains"))) {
                v.append("; includeSubDomains");
            }
            if (Boolean.parseBoolean(hsts.get("Preload"))) {
                v.append("; preload");
            }
            add.add(new PolicyHeader("Strict-Transport-Security", v.toString(), override(hsts)));
        }
        Map<String, String> cto = (Map<String, String>) security.get("ContentTypeOptions");
        if (cto != null) {
            add.add(new PolicyHeader("X-Content-Type-Options", "nosniff", override(cto)));
        }
        Map<String, String> frame = (Map<String, String>) security.get("FrameOptions");
        if (frame != null && frame.get("FrameOption") != null) {
            add.add(new PolicyHeader("X-Frame-Options", frame.get("FrameOption"), override(frame)));
        }
        Map<String, String> referrer = (Map<String, String>) security.get("ReferrerPolicy");
        if (referrer != null && referrer.get("ReferrerPolicy") != null) {
            add.add(new PolicyHeader("Referrer-Policy", referrer.get("ReferrerPolicy"), override(referrer)));
        }
        Map<String, String> xss = (Map<String, String>) security.get("XSSProtection");
        if (xss != null) {
            String value;
            if (!Boolean.parseBoolean(xss.getOrDefault("Protection", "false"))) {
                value = "0";
            } else {
                StringBuilder v = new StringBuilder("1");
                if (Boolean.parseBoolean(xss.get("ModeBlock"))) {
                    v.append("; mode=block");
                }
                if (xss.get("ReportUri") != null && !xss.get("ReportUri").isBlank()) {
                    v.append("; report=").append(xss.get("ReportUri"));
                }
                value = v.toString();
            }
            add.add(new PolicyHeader("X-XSS-Protection", value, override(xss)));
        }
        Map<String, String> csp = (Map<String, String>) security.get("ContentSecurityPolicy");
        if (csp != null && csp.get("ContentSecurityPolicy") != null) {
            add.add(new PolicyHeader("Content-Security-Policy", csp.get("ContentSecurityPolicy"), override(csp)));
        }
    }

    @SuppressWarnings("unchecked")
    private static void corsHeaders(Map<String, Object> cors, String viewerOrigin,
                                    boolean preflightRequest,
                                    List<PolicyHeader> add) {
        boolean override = Boolean.parseBoolean(String.valueOf(cors.get("OriginOverride")));
        List<String> origins = (List<String>) cors.get("AccessControlAllowOrigins");
        String allowOrigin = selectAllowOrigin(origins, viewerOrigin);
        if (allowOrigin == null) {
            // CloudFront emits CORS policy headers only for a request whose Origin matches the policy.
            return;
        }
        add.add(new PolicyHeader("Access-Control-Allow-Origin", allowOrigin, override));
        corsListHeader(cors, "AccessControlExposeHeaders", "Access-Control-Expose-Headers", override, add);
        if (Boolean.parseBoolean(String.valueOf(cors.get("AccessControlAllowCredentials")))) {
            add.add(new PolicyHeader("Access-Control-Allow-Credentials", "true", override));
        }
        if (preflightRequest) {
            corsListHeader(cors, "AccessControlAllowHeaders", "Access-Control-Allow-Headers", override, add);
            corsMethodsHeader(cors, override, add);
            Object maxAge = cors.get("AccessControlMaxAgeSec");
            if (maxAge != null) {
                add.add(new PolicyHeader("Access-Control-Max-Age", maxAge.toString(), override));
            }
        }
    }

    private static String selectAllowOrigin(List<String> allowedOrigins, String viewerOrigin) {
        if (allowedOrigins == null || allowedOrigins.isEmpty()
                || viewerOrigin == null || viewerOrigin.isBlank()) {
            return null;
        }
        String origin = viewerOrigin.trim();
        for (String allowed : allowedOrigins) {
            if ("*".equals(allowed)) {
                return "*";
            }
            if (originMatches(allowed, origin)) {
                return origin;
            }
        }
        return null;
    }

    /** Implements CloudFront's documented scheme, subdomain, and port wildcard origin matching. */
    static boolean originMatches(String allowedOrigin, String viewerOrigin) {
        if (allowedOrigin == null || allowedOrigin.isBlank()
                || viewerOrigin == null || viewerOrigin.isBlank()) {
            return false;
        }
        try {
            URI viewer = URI.create(viewerOrigin.trim());
            if (viewer.getScheme() == null || viewer.getHost() == null
                    || viewer.getRawUserInfo() != null
                    || (viewer.getRawPath() != null && !viewer.getRawPath().isEmpty())
                    || viewer.getRawQuery() != null || viewer.getRawFragment() != null) {
                return false;
            }

            String pattern = allowedOrigin.trim();
            String allowedScheme = null;
            int schemeEnd = pattern.indexOf("://");
            if (schemeEnd >= 0) {
                allowedScheme = pattern.substring(0, schemeEnd);
                pattern = pattern.substring(schemeEnd + 3);
            }
            if (allowedScheme != null && !allowedScheme.equalsIgnoreCase(viewer.getScheme())) {
                return false;
            }
            if (pattern.indexOf('/') >= 0 || pattern.indexOf('?') >= 0 || pattern.indexOf('#') >= 0) {
                return false;
            }

            String hostPattern = pattern;
            String portPattern = null;
            int colon = pattern.lastIndexOf(':');
            if (colon >= 0 && pattern.indexOf(':') == colon) {
                hostPattern = pattern.substring(0, colon);
                portPattern = pattern.substring(colon + 1);
            }
            String viewerHost = viewer.getHost().toLowerCase(Locale.ROOT);
            String allowedHost = hostPattern.toLowerCase(Locale.ROOT);
            boolean hostMatches;
            if (allowedHost.startsWith("*.") && allowedHost.indexOf('*', 1) < 0) {
                hostMatches = viewerHost.endsWith(allowedHost.substring(1));
            } else {
                hostMatches = allowedHost.indexOf('*') < 0 && viewerHost.equals(allowedHost);
            }
            if (!hostMatches) {
                return false;
            }

            if (portPattern == null) {
                return viewer.getPort() < 0;
            }
            if ("*".equals(portPattern)) {
                return true;
            }
            if (viewer.getPort() < 0 || !portPattern.matches("[0-9*]+")) {
                return false;
            }
            String portRegex = "\\Q" + portPattern.replace("*", "\\E.*\\Q") + "\\E";
            return Pattern.matches(portRegex, Integer.toString(viewer.getPort()));
        } catch (IllegalArgumentException e) {
            return false;
        }
    }

    @SuppressWarnings("unchecked")
    private static void corsListHeader(Map<String, Object> cors, String key, String header,
                                       boolean override, List<PolicyHeader> add) {
        List<String> values = (List<String>) cors.get(key);
        if (values == null || values.isEmpty()) {
            return;
        }
        add.add(new PolicyHeader(header, String.join(", ", values), override));
    }

    @SuppressWarnings("unchecked")
    private static void corsMethodsHeader(Map<String, Object> cors, boolean override,
                                          List<PolicyHeader> add) {
        List<String> configured = (List<String>) cors.get("AccessControlAllowMethods");
        if (configured == null || configured.isEmpty()) {
            return;
        }
        List<String> methods = configured.stream().anyMatch("ALL"::equalsIgnoreCase)
                ? List.of("DELETE", "GET", "HEAD", "OPTIONS", "PATCH", "POST", "PUT")
                : configured;
        add.add(new PolicyHeader("Access-Control-Allow-Methods",
                String.join(", ", methods), override));
    }

    private static boolean override(Map<String, String> block) {
        return Boolean.parseBoolean(block.getOrDefault("Override", "false"));
    }

    // ── Structured XML helpers ───────────────────────────────────────────────────

    private static XmlElement configRoot(String body) {
        return XmlParser.extractElementTree(body, "ResponseHeadersPolicyConfig");
    }

    private static XmlElement child(XmlElement parent, String name) {
        return parent.child(name);
    }

    private static String childText(XmlElement parent, String name) {
        XmlElement element = child(parent, name);
        return element == null ? null : element.text();
    }

    /** Every direct leaf child of {@code parent}, as an ordered {name -> text} map. */
    private static Map<String, String> scalarChildren(XmlElement parent) {
        Map<String, String> map = new LinkedHashMap<>();
        for (XmlElement element : parent.children()) {
            if (element.children().isEmpty()) {
                map.put(element.name(), element.text());
            }
        }
        return map;
    }

    /** Text of every leaf under a container's {@code Items}; {@code leafName} filters when non-null. */
    private static List<String> itemLeafValues(XmlElement container, String leafName) {
        List<String> values = new ArrayList<>();
        XmlElement items = child(container, "Items");
        if (items == null) {
            return values;
        }
        for (XmlElement item : items.children()) {
            if (leafName == null) {
                values.add(item.text());
            } else {
                String text = childText(item, leafName);
                values.add(text == null ? "" : text);
            }
        }
        return values;
    }

    /** Each {@code Items} child element mapped to its own leaf children. */
    private static List<Map<String, String>> itemsAsMaps(XmlElement container) {
        List<Map<String, String>> list = new ArrayList<>();
        XmlElement items = child(container, "Items");
        if (items == null) {
            return list;
        }
        for (XmlElement item : items.children()) {
            list.add(scalarChildren(item));
        }
        return list;
    }

    private static void validateDeclaredQuantity(XmlElement container, int actual, String field) {
        String rawQuantity = childText(container, "Quantity");
        if (rawQuantity == null) {
            throw new AwsException("InvalidArgument", field + " Quantity is required.", 400);
        }
        final int declared;
        try {
            declared = Integer.parseInt(rawQuantity);
        } catch (NumberFormatException e) {
            throw new AwsException("InvalidArgument",
                    field + " Quantity must be a non-negative integer.", 400);
        }
        if (declared < 0) {
            throw new AwsException("InvalidArgument",
                    field + " Quantity must be a non-negative integer.", 400);
        }
        if (declared != actual) {
            throw new AwsException("InconsistentQuantities",
                    field + " Quantity does not match the number of Items.", 400);
        }
    }
}
