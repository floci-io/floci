package io.github.hectorvent.floci.services.ec2;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import io.quarkus.runtime.annotations.RegisterForReflection;
import jakarta.enterprise.context.ApplicationScoped;

import java.io.IOException;
import java.io.InputStream;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

@ApplicationScoped
public class Ec2InstanceTypeCatalog {

    private static final String CATALOG_RESOURCE_NAME = "ec2/instance-type-catalog.yaml";
    private static final ObjectMapper YAML_MAPPER = new ObjectMapper(new YAMLFactory());

    /**
     * The burstable performance families and the credit option each one launches with when the
     * request names none. AWS documents t2 as standard and t3, t3a and t4g as unlimited; every
     * other family, t1 included, has no credit model at all. The EC2 model's own InstanceType
     * enum lists t1, t2, t3, t3a and t4g as the only T families, so this table is complete.
     *
     * <p>Family derivation rather than a per-type catalog entry, because the catalog holds only
     * the sizes Floci ships metadata for while a launch may name any size of a burstable family.
     */
    private static final Map<String, String> DEFAULT_CPU_CREDITS_BY_FAMILY = Map.of(
            "t2", "standard",
            "t3", "unlimited",
            "t3a", "unlimited",
            "t4g", "unlimited");

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

    /**
     * The credit option a launch of this instance type gets when it names no CreditSpecification,
     * or empty when the type has no burstable credit model.
     */
    public static Optional<String> defaultCpuCredits(String instanceType) {
        return Optional.ofNullable(DEFAULT_CPU_CREDITS_BY_FAMILY.get(familyOf(instanceType)));
    }

    public static boolean isBurstablePerformanceType(String instanceType) {
        return DEFAULT_CPU_CREDITS_BY_FAMILY.containsKey(familyOf(instanceType));
    }

    private static String familyOf(String instanceType) {
        if (instanceType == null) {
            return "";
        }
        int separator = instanceType.indexOf('.');
        return separator < 0 ? instanceType : instanceType.substring(0, separator);
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
            if (instanceType.supportedUsageClasses == null || instanceType.supportedUsageClasses.isEmpty()) {
                throw new IllegalStateException("EC2 instance type catalog entry is missing supportedUsageClasses: " + name);
            }
            if (instanceType.encryptionInTransitSupported == null) {
                throw new IllegalStateException(
                        "EC2 instance type catalog entry is missing encryptionInTransitSupported: " + name);
            }
            validateNetworkInfo(instanceType, name);
            CatalogInstanceType previous = index.putIfAbsent(name, instanceType);
            if (previous != null) {
                throw new IllegalStateException("Duplicate EC2 instance type catalog entry: " + name);
            }
        }
        return Map.copyOf(index);
    }

    private static void validateNetworkInfo(CatalogInstanceType instanceType, String name) {
        if (instanceType.defaultNetworkCardIndex == null || instanceType.defaultNetworkCardIndex < 0) {
            throw new IllegalStateException("EC2 instance type catalog entry has invalid defaultNetworkCardIndex: " + name);
        }
        if (instanceType.ipv4AddressesPerInterface == null || instanceType.ipv4AddressesPerInterface <= 0) {
            throw new IllegalStateException("EC2 instance type catalog entry has invalid ipv4AddressesPerInterface: " + name);
        }
        if (instanceType.networkCards == null || instanceType.networkCards.isEmpty()) {
            throw new IllegalStateException("EC2 instance type catalog entry is missing networkCards: " + name);
        }
        if (instanceType.defaultNetworkCardIndex >= instanceType.networkCards.size()) {
            throw new IllegalStateException("EC2 instance type catalog entry has defaultNetworkCardIndex outside networkCards: " + name);
        }
        Set<Integer> cardIndexes = new HashSet<>();
        for (int position = 0; position < instanceType.networkCards.size(); position++) {
            CatalogNetworkCard card = instanceType.networkCards.get(position);
            if (card == null || card.networkCardIndex == null || card.networkCardIndex != position
                    || !cardIndexes.add(card.networkCardIndex)) {
                throw new IllegalStateException("EC2 instance type catalog entry has invalid networkCardIndex: " + name);
            }
            if (card.maximumNetworkInterfaces == null || card.maximumNetworkInterfaces <= 0) {
                throw new IllegalStateException(
                        "EC2 instance type catalog entry has invalid maximumNetworkInterfaces: " + name);
            }
        }
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
        public int localStorageGiB;
        public List<String> supportedArchitectures = List.of();
        public List<String> supportedUsageClasses = List.of("on-demand", "spot");
        public Boolean currentGeneration;
        public Boolean encryptionInTransitSupported;
        public Integer defaultNetworkCardIndex;
        public Integer ipv4AddressesPerInterface;
        public List<CatalogNetworkCard> networkCards = List.of();

        public Map<String, Object> toResponseMap() {
            Map<String, Object> type = new LinkedHashMap<>();
            type.put("instanceType", instanceType);
            type.put("vcpu", vcpu);
            type.put("memoryMib", memoryMib);
            type.put("instanceStorageSupported", localStorageGiB > 0);
            type.put("localStorageGiB", localStorageGiB);
            type.put("supportedArchitectures", List.copyOf(supportedArchitectures));
            type.put("supportedUsageClasses", List.copyOf(supportedUsageClasses));
            type.put("currentGeneration", currentGeneration == null || currentGeneration);
            type.put("burstablePerformanceSupported", isBurstablePerformanceType(instanceType));
            Map<String, Object> networkInfo = new LinkedHashMap<>();
            networkInfo.put("encryptionInTransitSupported", encryptionInTransitSupported);
            networkInfo.put("defaultNetworkCardIndex", defaultNetworkCardIndex);
            networkInfo.put("ipv4AddressesPerInterface", ipv4AddressesPerInterface);
            networkInfo.put("networkCards", networkCards.stream()
                    .map(CatalogNetworkCard::toResponseMap)
                    .toList());
            type.put("networkInfo", networkInfo);
            return type;
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    @RegisterForReflection
    public static final class CatalogNetworkCard {
        public Integer networkCardIndex;
        public Integer maximumNetworkInterfaces;

        public Map<String, Object> toResponseMap() {
            Map<String, Object> card = new LinkedHashMap<>();
            card.put("networkCardIndex", networkCardIndex);
            card.put("maximumNetworkInterfaces", maximumNetworkInterfaces);
            return card;
        }
    }
}
