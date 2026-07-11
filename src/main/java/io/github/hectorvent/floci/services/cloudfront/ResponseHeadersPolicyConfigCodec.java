package io.github.hectorvent.floci.services.cloudfront;

import io.github.hectorvent.floci.core.common.XmlBuilder;
import org.jboss.logging.Logger;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;
import org.xml.sax.InputSource;

import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import java.io.StringReader;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

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

    private static final Logger LOG = Logger.getLogger(ResponseHeadersPolicyConfigCodec.class);

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
        Element root = configRoot(body);
        if (root == null) {
            return config;
        }
        Element security = child(root, "SecurityHeadersConfig");
        if (security != null) {
            Map<String, Object> sec = new LinkedHashMap<>();
            for (String block : List.of("XSSProtection", "FrameOptions", "ReferrerPolicy",
                    "ContentSecurityPolicy", "ContentTypeOptions", "StrictTransportSecurity")) {
                Element el = child(security, block);
                if (el != null) {
                    sec.put(block, scalarChildren(el));
                }
            }
            config.put("SecurityHeadersConfig", sec);
        }
        Element cors = child(root, "CorsConfig");
        if (cors != null) {
            config.put("CorsConfig", parseCors(cors));
        }
        Element custom = child(root, "CustomHeadersConfig");
        if (custom != null) {
            config.put("CustomHeadersConfig", itemsAsMaps(custom));
        }
        Element remove = child(root, "RemoveHeadersConfig");
        if (remove != null) {
            config.put("RemoveHeadersConfig", itemLeafValues(remove, "Header"));
        }
        Element serverTiming = child(root, "ServerTimingHeadersConfig");
        if (serverTiming != null) {
            config.put("ServerTimingHeadersConfig", scalarChildren(serverTiming));
        }
        return config;
    }

    private static Map<String, Object> parseCors(Element cors) {
        Map<String, Object> map = new LinkedHashMap<>();
        for (String list : List.of("AccessControlAllowOrigins", "AccessControlAllowHeaders",
                "AccessControlAllowMethods", "AccessControlExposeHeaders")) {
            Element el = child(cors, list);
            if (el != null) {
                map.put(list, itemLeafValues(el, null));
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
    @SuppressWarnings("unchecked")
    static Directives directives(Map<String, Object> config) {
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
            corsHeaders(cors, add);
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
    private static void corsHeaders(Map<String, Object> cors, List<PolicyHeader> add) {
        boolean override = Boolean.parseBoolean(String.valueOf(cors.get("OriginOverride")));
        // Access-Control-Allow-Origin must be a single origin (or "*"); a comma-joined list is not a
        // valid value. CloudFront echoes the matching request origin when several are configured; with
        // no request context here, emit "*" when allowed, otherwise the first configured origin.
        List<String> origins = (List<String>) cors.get("AccessControlAllowOrigins");
        if (origins != null && !origins.isEmpty()) {
            add.add(new PolicyHeader("Access-Control-Allow-Origin",
                    origins.contains("*") ? "*" : origins.get(0), override));
        }
        corsListHeader(cors, "AccessControlAllowHeaders", "Access-Control-Allow-Headers", override, add);
        corsListHeader(cors, "AccessControlAllowMethods", "Access-Control-Allow-Methods", override, add);
        corsListHeader(cors, "AccessControlExposeHeaders", "Access-Control-Expose-Headers", override, add);
        if (Boolean.parseBoolean(String.valueOf(cors.get("AccessControlAllowCredentials")))) {
            add.add(new PolicyHeader("Access-Control-Allow-Credentials", "true", override));
        }
        Object maxAge = cors.get("AccessControlMaxAgeSec");
        if (maxAge != null) {
            add.add(new PolicyHeader("Access-Control-Max-Age", maxAge.toString(), override));
        }
    }

    @SuppressWarnings("unchecked")
    private static void corsListHeader(Map<String, Object> cors, String key, String header,
                                       boolean override, List<PolicyHeader> add) {
        List<String> values = (List<String>) cors.get(key);
        if (values == null || values.isEmpty()) {
            return;
        }
        String value = values.contains("*") ? "*" : String.join(", ", values);
        add.add(new PolicyHeader(header, value, override));
    }

    private static boolean override(Map<String, String> block) {
        return Boolean.parseBoolean(block.getOrDefault("Override", "false"));
    }

    // ── DOM helpers ──────────────────────────────────────────────────────────────

    private static Element configRoot(String body) {
        if (body == null || body.isBlank()) {
            return null;
        }
        try {
            DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
            factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
            factory.setNamespaceAware(false);
            DocumentBuilder builder = factory.newDocumentBuilder();
            Document doc = builder.parse(new InputSource(new StringReader(body)));
            Element root = doc.getDocumentElement();
            if (root == null) {
                return null;
            }
            if ("ResponseHeadersPolicyConfig".equals(root.getLocalName())
                    || "ResponseHeadersPolicyConfig".equals(stripPrefix(root.getNodeName()))) {
                return root;
            }
            return firstDescendant(root, "ResponseHeadersPolicyConfig");
        } catch (Exception e) {
            // A malformed policy body yields an empty config (no headers applied) rather than failing
            // the create call; log it so the cause is diagnosable.
            LOG.debugv("Ignoring unparseable ResponseHeadersPolicyConfig: {0}", e.getMessage());
            return null;
        }
    }

    private static Element firstDescendant(Element parent, String name) {
        NodeList nodes = parent.getElementsByTagName("*");
        for (int i = 0; i < nodes.getLength(); i++) {
            Node n = nodes.item(i);
            if (n instanceof Element el && name.equals(stripPrefix(el.getNodeName()))) {
                return el;
            }
        }
        return null;
    }

    private static Element child(Element parent, String name) {
        NodeList children = parent.getChildNodes();
        for (int i = 0; i < children.getLength(); i++) {
            Node n = children.item(i);
            if (n instanceof Element el && name.equals(stripPrefix(el.getNodeName()))) {
                return el;
            }
        }
        return null;
    }

    private static String childText(Element parent, String name) {
        Element el = child(parent, name);
        return el == null ? null : el.getTextContent().trim();
    }

    /** Every direct leaf child of {@code parent}, as an ordered {name -> text} map. */
    private static Map<String, String> scalarChildren(Element parent) {
        Map<String, String> map = new LinkedHashMap<>();
        NodeList children = parent.getChildNodes();
        for (int i = 0; i < children.getLength(); i++) {
            if (children.item(i) instanceof Element el && !hasElementChild(el)) {
                map.put(stripPrefix(el.getNodeName()), el.getTextContent().trim());
            }
        }
        return map;
    }

    /** Text of every leaf under a container's {@code Items}; {@code leafName} filters when non-null. */
    private static List<String> itemLeafValues(Element container, String leafName) {
        List<String> values = new ArrayList<>();
        Element items = child(container, "Items");
        if (items == null) {
            return values;
        }
        NodeList children = items.getChildNodes();
        for (int i = 0; i < children.getLength(); i++) {
            if (!(children.item(i) instanceof Element item)) {
                continue;
            }
            if (leafName == null) {
                values.add(item.getTextContent().trim());
            } else {
                String text = childText(item, leafName);
                if (text != null) {
                    values.add(text);
                }
            }
        }
        return values;
    }

    /** Each {@code Items} child element mapped to its own leaf children. */
    private static List<Map<String, String>> itemsAsMaps(Element container) {
        List<Map<String, String>> list = new ArrayList<>();
        Element items = child(container, "Items");
        if (items == null) {
            return list;
        }
        NodeList children = items.getChildNodes();
        for (int i = 0; i < children.getLength(); i++) {
            if (children.item(i) instanceof Element item) {
                list.add(scalarChildren(item));
            }
        }
        return list;
    }

    private static boolean hasElementChild(Element el) {
        NodeList children = el.getChildNodes();
        for (int i = 0; i < children.getLength(); i++) {
            if (children.item(i) instanceof Element) {
                return true;
            }
        }
        return false;
    }

    private static String stripPrefix(String qName) {
        int colon = qName.indexOf(':');
        return colon < 0 ? qName : qName.substring(colon + 1);
    }
}
