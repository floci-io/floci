package io.floci.aws.services.cloudformation;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.jboss.logging.Logger;

import java.io.InputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Registry of schema-defined createOnlyProperties for CloudFormation resource types.
 */
public final class CfnCreateOnlyProperties {

    private static final Logger LOG = Logger.getLogger(CfnCreateOnlyProperties.class);
    private static final String RESOURCE_NAME = "cloudformation/create-only-properties.json";
    private static final String CONDITIONAL_RESOURCE_NAME =
            "cloudformation/conditional-create-only-properties.json";
    private static final Map<String, Set<String>> PROPERTIES = load(RESOURCE_NAME);
    private static final Map<String, Set<String>> CONDITIONAL_PROPERTIES = load(CONDITIONAL_RESOURCE_NAME);

    private CfnCreateOnlyProperties() {
    }

    private static Map<String, Set<String>> load(String resourceName) {
        try (InputStream in = CfnCreateOnlyProperties.class.getClassLoader().getResourceAsStream(resourceName)) {
            if (in == null) {
                LOG.warnv("Resource {0} not found on classpath", resourceName);
                return Collections.emptyMap();
            }
            ObjectMapper mapper = new ObjectMapper();
            return mapper.readValue(in, new TypeReference<Map<String, Set<String>>>() {});
        } catch (Exception e) {
            LOG.warnv(e, "Failed to load {0}", resourceName);
            return Collections.emptyMap();
        }
    }

    public static boolean isCreateOnly(String resourceType, String propertyName) {
        Set<String> createOnly = PROPERTIES.get(resourceType);
        if (createOnly != null) {
            // The schema list is authoritative for this resource type, including when it is empty.
            if (createOnly.contains(propertyName)) {
                return true;
            }
            for (String prop : createOnly) {
                if (prop.startsWith(propertyName + "/")) {
                    return true;
                }
            }
            return false;
        }
        if ("Name".equals(propertyName)
                || propertyName.endsWith("Identifier")
                || "FifoQueue".equals(propertyName)
                || "FifoTopic".equals(propertyName)) {
            return true;
        }
        String typeSuffix = resourceType.contains("::")
                ? resourceType.substring(resourceType.lastIndexOf("::") + 2)
                : resourceType;
        return propertyName.equals(typeSuffix + "Name");
    }

    public static Set<String> getCreateOnlyProperties(String resourceType) {
        return PROPERTIES.getOrDefault(resourceType, Collections.emptySet());
    }

    /** Schema pointers that require replacement, including conditionally create-only paths. */
    static Set<String> replacementPointers(String resourceType) {
        Set<String> schemaPointers = getCreateOnlyProperties(resourceType);
        Set<String> conditionalPointers = CONDITIONAL_PROPERTIES.get(resourceType);
        if (conditionalPointers == null || conditionalPointers.isEmpty()) {
            return schemaPointers;
        }
        Set<String> pointers = new HashSet<>(schemaPointers);
        pointers.addAll(conditionalPointers);
        return pointers;
    }

    /** True when the registry table has an entry for the type, so its pointers are authoritative. */
    public static boolean hasSchema(String resourceType) {
        return PROPERTIES.containsKey(resourceType);
    }

    /**
     * True when the value at any create-only pointer of the type differs between the old and the new
     * properties. A pointer compares the values at that path, a top-level name included: a value added, removed or
     * changed is a change, absent on both sides is not. A {@code *} segment compares every array
     * element's subtree.
     */
    public static boolean createOnlyValueChanged(String resourceType, JsonNode oldProps, JsonNode newProps) {
        return createOnlyValueChanged(resourceType, oldProps, newProps, null);
    }

    /** As above, only the pointers at and under {@code property}; null means every property. */
    public static boolean createOnlyValueChanged(String resourceType, JsonNode oldProps, JsonNode newProps,
                                                 String property) {
        for (String pointer : replacementPointers(resourceType)) {
            if (isUnder(pointer, property) && createOnlyPointerChanged(pointer, oldProps, newProps)) {
                return true;
            }
        }
        return false;
    }

    /** {@link #createOnlyValueChanged} for one pointer of the type's table. */
    public static boolean createOnlyPointerChanged(String pointer, JsonNode oldProps, JsonNode newProps) {
        String[] segments = pointer.split("/");
        return pointerChanged(oldProps, newProps, segments, 0);
    }

    /** The subtrees of the properties that sit under one create-only pointer. */
    public static List<JsonNode> subtreesAt(String pointer, JsonNode props) {
        List<JsonNode> out = new ArrayList<>();
        collect(props, pointer.split("/"), 0, out);
        return out;
    }

    /** The subtrees of the new properties that sit under a create-only pointer of the type. */
    public static List<JsonNode> createOnlySubtrees(String resourceType, JsonNode props) {
        return createOnlySubtrees(resourceType, props, null);
    }

    /** As above, only the pointers at and under {@code property}; null means every property. */
    public static List<JsonNode> createOnlySubtrees(String resourceType, JsonNode props, String property) {
        List<JsonNode> out = new ArrayList<>();
        for (String pointer : replacementPointers(resourceType)) {
            if (isUnder(pointer, property)) {
                collect(props, pointer.split("/"), 0, out);
            }
        }
        return out;
    }

    /** Whether the pointer is at or under the top-level property; a null property matches every pointer. */
    static boolean isUnder(String pointer, String property) {
        return property == null || pointer.equals(property) || pointer.startsWith(property + "/");
    }

    private static boolean pointerChanged(JsonNode oldNode, JsonNode newNode, String[] segments, int index) {
        if (index == segments.length) {
            return oldNode == null ? newNode != null : !oldNode.equals(newNode);
        }
        if (oldNode == null && newNode == null) {
            return false;
        }
        if (index > 0 && (isIntrinsic(oldNode) || isIntrinsic(newNode))) {
            // A Ref or Fn:: node stands for a value this walk cannot see into, so compare it whole.
            return oldNode == null ? newNode != null : !oldNode.equals(newNode);
        }
        if ("*".equals(segments[index])) {
            int size = Math.max(oldNode != null && oldNode.isArray() ? oldNode.size() : 0,
                    newNode != null && newNode.isArray() ? newNode.size() : 0);
            if (size == 0 && (oldNode != null && !oldNode.isArray() || newNode != null && !newNode.isArray())) {
                return oldNode == null ? newNode != null : !oldNode.equals(newNode);
            }
            for (int i = 0; i < size; i++) {
                JsonNode o = oldNode != null && oldNode.isArray() ? oldNode.get(i) : null;
                JsonNode n = newNode != null && newNode.isArray() ? newNode.get(i) : null;
                if (pointerChanged(o, n, segments, index + 1)) {
                    return true;
                }
            }
            return false;
        }
        JsonNode o = oldNode != null && oldNode.isObject() ? oldNode.get(segments[index]) : null;
        JsonNode n = newNode != null && newNode.isObject() ? newNode.get(segments[index]) : null;
        return pointerChanged(o, n, segments, index + 1);
    }

    private static void collect(JsonNode node, String[] segments, int index, List<JsonNode> out) {
        if (node == null) {
            return;
        }
        if (index == segments.length || (index > 0 && isIntrinsic(node))) {
            out.add(node);
            return;
        }
        if ("*".equals(segments[index])) {
            if (node.isArray()) {
                for (JsonNode element : node) {
                    collect(element, segments, index + 1, out);
                }
            }
            return;
        }
        if (node.isObject()) {
            collect(node.get(segments[index]), segments, index + 1, out);
        }
    }

    private static boolean isIntrinsic(JsonNode node) {
        if (node == null || !node.isObject() || node.size() != 1) {
            return false;
        }
        String key = node.fieldNames().next();
        return "Ref".equals(key) || key.startsWith("Fn::");
    }
}
