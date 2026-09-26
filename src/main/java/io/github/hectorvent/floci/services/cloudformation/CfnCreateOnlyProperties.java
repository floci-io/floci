package io.github.hectorvent.floci.services.cloudformation;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

import org.jboss.logging.Logger;

import java.io.InputStream;
import java.util.Collections;
import java.util.Map;
import java.util.Set;

/**
 * Registry of schema-defined createOnlyProperties for CloudFormation resource types.
 */
public final class CfnCreateOnlyProperties {

    private static final Logger LOG = Logger.getLogger(CfnCreateOnlyProperties.class);
    private static final String RESOURCE_NAME = "cloudformation/create-only-properties.json";
    private static final Map<String, Set<String>> PROPERTIES = load();

    private CfnCreateOnlyProperties() {
    }

    private static Map<String, Set<String>> load() {
        try (InputStream in = CfnCreateOnlyProperties.class.getClassLoader().getResourceAsStream(RESOURCE_NAME)) {
            if (in == null) {
                LOG.warnv("Resource {0} not found on classpath", RESOURCE_NAME);
                return Collections.emptyMap();
            }
            ObjectMapper mapper = new ObjectMapper();
            return mapper.readValue(in, new TypeReference<Map<String, Set<String>>>() {});
        } catch (Exception e) {
            LOG.warnv(e, "Failed to load {0}", RESOURCE_NAME);
            return Collections.emptyMap();
        }
    }

    public static boolean isCreateOnly(String resourceType, String propertyName) {
        Set<String> createOnly = PROPERTIES.get(resourceType);
        if (createOnly != null && createOnly.contains(propertyName)) {
            return true;
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
}
