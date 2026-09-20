package io.github.hectorvent.floci.services.cloudfront;

import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.core.common.XmlBuilder;
import io.github.hectorvent.floci.core.common.XmlParser;
import io.github.hectorvent.floci.core.common.XmlParser.XmlElement;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Parses and serializes the nested configuration of CloudFront cache forwarding policies. */
final class CloudFrontPolicyConfigCodec {

    private CloudFrontPolicyConfigCodec() {
    }

    static Map<String, Object> parseCachePolicy(String body) {
        XmlElement root = requireRoot(body, "CachePolicyConfig");
        Map<String, Object> config = new LinkedHashMap<>();
        copyScalar(root, config, "MinTTL");
        copyScalar(root, config, "DefaultTTL");
        copyScalar(root, config, "MaxTTL");

        XmlElement parameters = root.child("ParametersInCacheKeyAndForwardedToOrigin");
        if (parameters != null) {
            Map<String, Object> values = new LinkedHashMap<>();
            copyScalar(parameters, values, "EnableAcceptEncodingGzip");
            copyScalar(parameters, values, "EnableAcceptEncodingBrotli");
            copySelection(parameters, values, "HeadersConfig", "HeaderBehavior", "Headers");
            copySelection(parameters, values, "CookiesConfig", "CookieBehavior", "Cookies");
            copySelection(parameters, values, "QueryStringsConfig", "QueryStringBehavior", "QueryStrings");
            config.put("ParametersInCacheKeyAndForwardedToOrigin", values);
        }
        return config;
    }

    static Map<String, Object> parseOriginRequestPolicy(String body) {
        XmlElement root = requireRoot(body, "OriginRequestPolicyConfig");
        Map<String, Object> config = new LinkedHashMap<>();
        copySelection(root, config, "HeadersConfig", "HeaderBehavior", "Headers");
        copySelection(root, config, "CookiesConfig", "CookieBehavior", "Cookies");
        copySelection(root, config, "QueryStringsConfig", "QueryStringBehavior", "QueryStrings");
        return config;
    }

    static void serializeCachePolicy(XmlBuilder xml, Map<String, Object> config) {
        if (config == null) {
            return;
        }
        serializeScalar(xml, config, "MinTTL");
        serializeScalar(xml, config, "DefaultTTL");
        serializeScalar(xml, config, "MaxTTL");

        Map<?, ?> parameters = asMap(config.get("ParametersInCacheKeyAndForwardedToOrigin"));
        if (parameters != null) {
            xml.start("ParametersInCacheKeyAndForwardedToOrigin");
            serializeScalar(xml, parameters, "EnableAcceptEncodingGzip");
            serializeScalar(xml, parameters, "EnableAcceptEncodingBrotli");
            serializeSelection(xml, parameters, "HeadersConfig", "HeaderBehavior", "Headers");
            serializeSelection(xml, parameters, "CookiesConfig", "CookieBehavior", "Cookies");
            serializeSelection(xml, parameters, "QueryStringsConfig", "QueryStringBehavior", "QueryStrings");
            xml.end("ParametersInCacheKeyAndForwardedToOrigin");
        }
    }

    static void serializeOriginRequestPolicy(XmlBuilder xml, Map<String, Object> config) {
        if (config == null) {
            return;
        }
        serializeSelection(xml, config, "HeadersConfig", "HeaderBehavior", "Headers");
        serializeSelection(xml, config, "CookiesConfig", "CookieBehavior", "Cookies");
        serializeSelection(xml, config, "QueryStringsConfig", "QueryStringBehavior", "QueryStrings");
    }

    private static XmlElement requireRoot(String body, String name) {
        XmlElement root = XmlParser.extractElementTree(body, name);
        if (root == null) {
            throw new AwsException("InvalidArgument",
                    "The request body must contain a valid " + name + " element.", 400);
        }
        return root;
    }

    private static void copyScalar(XmlElement parent, Map<String, Object> target, String name) {
        XmlElement child = parent.child(name);
        if (child != null) {
            target.put(name, child.text());
        }
    }

    private static void copySelection(XmlElement parent, Map<String, Object> target,
                                      String blockName, String behaviorName, String listName) {
        XmlElement block = parent.child(blockName);
        if (block == null) {
            return;
        }
        Map<String, Object> values = new LinkedHashMap<>();
        copyScalar(block, values, behaviorName);
        XmlElement names = block.child(listName);
        if (names != null) {
            List<String> items = new ArrayList<>();
            XmlElement itemContainer = names.child("Items");
            if (itemContainer != null) {
                for (XmlElement item : itemContainer.children()) {
                    items.add(item.text());
                }
            }
            values.put(listName, items);
        }
        target.put(blockName, values);
    }

    private static void serializeSelection(XmlBuilder xml, Map<?, ?> parent,
                                           String blockName, String behaviorName, String listName) {
        Map<?, ?> block = asMap(parent.get(blockName));
        if (block == null) {
            return;
        }
        xml.start(blockName);
        serializeScalar(xml, block, behaviorName);
        if (block.containsKey(listName)) {
            List<String> items = stringList(block.get(listName));
            xml.start(listName).elem("Quantity", items.size());
            if (!items.isEmpty()) {
                xml.start("Items");
                for (String item : items) {
                    xml.elem("Name", item);
                }
                xml.end("Items");
            }
            xml.end(listName);
        }
        xml.end(blockName);
    }

    private static void serializeScalar(XmlBuilder xml, Map<?, ?> values, String name) {
        Object value = values.get(name);
        if (value != null) {
            xml.elem(name, value.toString());
        }
    }

    private static Map<?, ?> asMap(Object value) {
        return value instanceof Map<?, ?> map ? map : null;
    }

    private static List<String> stringList(Object value) {
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
}
