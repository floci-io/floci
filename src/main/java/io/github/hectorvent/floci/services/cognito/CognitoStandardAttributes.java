package io.github.hectorvent.floci.services.cognito;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

final class CognitoStandardAttributes {

    private CognitoStandardAttributes() {}

    static final List<Map<String, Object>> DEFAULTS = buildDefaults();

    private static List<Map<String, Object>> buildDefaults() {
        List<Map<String, Object>> attrs = new ArrayList<>();

        attrs.add(stringAttr("sub", "1", "2048", false, true));
        attrs.add(stringAttr("name", "0", "2048", true, false));
        attrs.add(stringAttr("given_name", "0", "2048", true, false));
        attrs.add(stringAttr("family_name", "0", "2048", true, false));
        attrs.add(stringAttr("middle_name", "0", "2048", true, false));
        attrs.add(stringAttr("nickname", "0", "2048", true, false));
        attrs.add(stringAttr("preferred_username", "0", "2048", true, false));
        attrs.add(stringAttr("profile", "0", "2048", true, false));
        attrs.add(stringAttr("picture", "0", "2048", true, false));
        attrs.add(stringAttr("website", "0", "2048", true, false));
        attrs.add(stringAttr("email", "0", "2048", true, false));
        attrs.add(booleanAttr("email_verified"));
        attrs.add(stringAttr("gender", "0", "2048", true, false));
        attrs.add(stringAttr("birthdate", "10", "10", true, false));
        attrs.add(stringAttr("zoneinfo", "0", "2048", true, false));
        attrs.add(stringAttr("locale", "0", "2048", true, false));
        attrs.add(stringAttr("phone_number", "0", "2048", true, false));
        attrs.add(booleanAttr("phone_number_verified"));
        attrs.add(stringAttr("address", "0", "2048", true, false));
        attrs.add(numberAttr("updated_at"));

        return List.copyOf(attrs);
    }

    private static Map<String, Object> stringAttr(String name, String minLength, String maxLength,
                                                    boolean mutable, boolean required) {
        Map<String, Object> attr = new LinkedHashMap<>();
        attr.put("Name", name);
        attr.put("AttributeDataType", "String");
        attr.put("DeveloperOnlyAttribute", false);
        attr.put("Mutable", mutable);
        attr.put("Required", required);
        attr.put("StringAttributeConstraints", Map.of("MinLength", minLength, "MaxLength", maxLength));
        return attr;
    }

    private static Map<String, Object> booleanAttr(String name) {
        Map<String, Object> attr = new LinkedHashMap<>();
        attr.put("Name", name);
        attr.put("AttributeDataType", "Boolean");
        attr.put("DeveloperOnlyAttribute", false);
        attr.put("Mutable", true);
        attr.put("Required", false);
        return attr;
    }

    private static Map<String, Object> numberAttr(String name) {
        Map<String, Object> attr = new LinkedHashMap<>();
        attr.put("Name", name);
        attr.put("AttributeDataType", "Number");
        attr.put("DeveloperOnlyAttribute", false);
        attr.put("Mutable", true);
        attr.put("Required", false);
        attr.put("NumberAttributeConstraints", Map.of("MinValue", "0"));
        return attr;
    }

    private static final Set<String> DEFAULT_NAMES = DEFAULTS.stream()
            .map(attr -> (String) attr.get("Name"))
            .collect(Collectors.toUnmodifiableSet());

    /** True when {@code name} is one of the standard OIDC attributes every pool carries. */
    static boolean isStandard(String name) {
        return name != null && DEFAULT_NAMES.contains(name);
    }

    /**
     * Merges standard attributes with any pool-defined schema.
     * Custom attributes (name starts with "custom:") are appended after standard ones.
     * Standard attributes explicitly included in the schema override the defaults.
     */
    static List<Map<String, Object>> merge(List<Map<String, Object>> poolSchema) {
        if (poolSchema == null || poolSchema.isEmpty()) {
            return DEFAULTS;
        }

        Map<String, Map<String, Object>> byName = new LinkedHashMap<>();
        for (Map<String, Object> attr : DEFAULTS) {
            byName.put((String) attr.get("Name"), attr);
        }

        List<Map<String, Object>> custom = new ArrayList<>();
        for (Map<String, Object> attr : poolSchema) {
            String name = (String) attr.get("Name");
            if (name != null && name.startsWith("custom:")) {
                custom.add(attr);
            } else if (name != null && byName.containsKey(name)) {
                byName.put(name, attr);
            } else if (name != null) {
                custom.add(attr);
            }
        }

        List<Map<String, Object>> result = new ArrayList<>(byName.values());
        result.addAll(custom);
        return result;
    }
}
