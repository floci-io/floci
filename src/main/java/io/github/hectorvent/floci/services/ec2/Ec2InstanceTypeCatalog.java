package io.github.hectorvent.floci.services.ec2;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import io.quarkus.runtime.annotations.RegisterForReflection;
import jakarta.enterprise.context.ApplicationScoped;

import java.io.IOException;
import java.io.InputStream;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

@ApplicationScoped
public class Ec2InstanceTypeCatalog {

    private static final String CATALOG_RESOURCE_NAME = "ec2/instance-type-catalog.yaml";
    private static final ObjectMapper YAML_MAPPER = new ObjectMapper(new YAMLFactory());

    private volatile Loaded loaded;

    public Ec2InstanceTypeCatalog() {
        // Load lazily so tests and mock-mode paths that do not need instance type
        // metadata are not coupled to resource loading during bean construction.
    }

    Ec2InstanceTypeCatalog(Catalog catalog) {
        this.loaded = new Loaded(catalog);
    }

    public List<CatalogInstanceType> instanceTypes() {
        return loaded().instanceTypes;
    }

    public Optional<CatalogInstanceType> find(String instanceType) {
        if (instanceType == null || instanceType.isBlank()) {
            return Optional.empty();
        }
        return Optional.ofNullable(loaded().instanceTypesByName.get(instanceType));
    }

    private Loaded loaded() {
        Loaded result = loaded;
        if (result == null) {
            synchronized (this) {
                result = loaded;
                if (result == null) {
                    result = new Loaded(readResource(CATALOG_RESOURCE_NAME, Catalog.class));
                    loaded = result;
                }
            }
        }
        return result;
    }

    private static <T> T readResource(String resourceName, Class<T> type) {
        ClassLoader loader = Thread.currentThread().getContextClassLoader();
        try (InputStream input = loader.getResourceAsStream(resourceName)) {
            if (input == null) {
                throw new IllegalStateException("Missing EC2 instance type catalog resource: " + resourceName);
            }
            return YAML_MAPPER.readValue(input, type);
        } catch (IOException e) {
            throw new IllegalStateException("Failed to load EC2 instance type catalog resource: " + resourceName, e);
        }
    }

    private static final class Loaded {
        private final List<CatalogInstanceType> instanceTypes;
        private final Map<String, CatalogInstanceType> instanceTypesByName;

        Loaded(Catalog catalog) {
            this.instanceTypes = List.copyOf(catalog.instanceTypes == null ? List.of() : catalog.instanceTypes);
            if (this.instanceTypes.isEmpty()) {
                throw new IllegalStateException("EC2 instance type catalog has no instance types: " + CATALOG_RESOURCE_NAME);
            }
            this.instanceTypesByName = indexInstanceTypes(this.instanceTypes);
        }
    }

    private static Map<String, CatalogInstanceType> indexInstanceTypes(List<CatalogInstanceType> instanceTypes) {
        Map<String, CatalogInstanceType> index = new LinkedHashMap<>();
        for (CatalogInstanceType instanceType : instanceTypes) {
            String name = require(instanceType.instanceType, "instanceType");
            if (instanceType.vcpu <= 0) {
                throw new IllegalStateException("EC2 instance type catalog entry has invalid vcpu: " + name);
            }
            if (instanceType.memoryMib <= 0) {
                throw new IllegalStateException("EC2 instance type catalog entry has invalid memoryMib: " + name);
            }
            if (instanceType.supportedArchitectures == null || instanceType.supportedArchitectures.isEmpty()) {
                throw new IllegalStateException("EC2 instance type catalog entry is missing supportedArchitectures: " + name);
            }
            CatalogInstanceType previous = index.putIfAbsent(name, instanceType);
            if (previous != null) {
                throw new IllegalStateException("Duplicate EC2 instance type catalog entry: " + name);
            }
        }
        return Map.copyOf(index);
    }

    private static String require(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalStateException("EC2 instance type catalog entry is missing required field: " + field);
        }
        return value;
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    @RegisterForReflection
    public static final class Catalog {
        public List<CatalogInstanceType> instanceTypes = List.of();
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    @RegisterForReflection
    public static final class CatalogInstanceType {
        public String instanceType;
        public int vcpu;
        public int memoryMib;
        public List<String> supportedArchitectures = List.of();
        public Boolean currentGeneration;

        public Map<String, Object> toResponseMap() {
            Map<String, Object> type = new LinkedHashMap<>();
            type.put("instanceType", instanceType);
            type.put("vcpu", vcpu);
            type.put("memoryMib", memoryMib);
            type.put("supportedArchitectures", List.copyOf(supportedArchitectures));
            type.put("currentGeneration", currentGeneration == null || currentGeneration);
            return type;
        }
    }
}
