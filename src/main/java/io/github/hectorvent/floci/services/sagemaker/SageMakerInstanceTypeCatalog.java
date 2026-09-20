package io.github.hectorvent.floci.services.sagemaker;

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
import java.util.Set;

/**
 * How many accelerators each SageMaker {@code ml.*} instance type carries.
 *
 * <p>This is shipped data rather than configuration for the same reason
 * {@code Ec2InstanceTypeCatalog} is: the GPU count of an {@code ml.g5.xlarge} is a fact
 * about AWS, identical for everyone, and not something an operator should have to
 * restate. What the local machine is willing to hand over is the separate, genuinely
 * local decision, and lives under {@code floci.services.sagemaker.gpu}.
 *
 * <p>Deliberately not merged into the EC2 catalog: {@code ml.*} is its own namespace and
 * these types must never appear in {@code DescribeInstanceTypes}.
 */
@ApplicationScoped
public class SageMakerInstanceTypeCatalog {

    private static final String CATALOG_RESOURCE_NAME = "sagemaker/instance-type-catalog.yaml";
    private static final ObjectMapper YAML_MAPPER = new ObjectMapper(new YAMLFactory());

    /**
     * Families whose members carry accelerators. Used only to decide whether an unknown
     * type should be refused rather than run on CPU: a caller naming an unlisted
     * {@code ml.g*} clearly wanted a GPU, and silently giving them CPU would produce a
     * job that reports Completed with a legitimate-looking artifact.
     *
     * <p>Never used to infer a count. Counts vary within a family, which is why the
     * catalog is a table.
     */
    private static final Set<String> ACCELERATOR_FAMILY_PREFIXES =
            Set.of("ml.g", "ml.p", "ml.inf", "ml.trn");

    private volatile Loaded loaded;

    public SageMakerInstanceTypeCatalog() {
        // Load lazily, matching Ec2InstanceTypeCatalog: a deployment that never touches
        // SageMaker should not pay for resource loading at bean construction.
    }

    SageMakerInstanceTypeCatalog(Catalog catalog) {
        this.loaded = new Loaded(catalog);
    }

    /**
     * The accelerator count for a type, or empty when the catalog does not describe it.
     * An empty result is not "no GPU": see {@link #isAcceleratorFamily(String)}.
     */
    public Optional<Integer> gpuCount(String instanceType) {
        if (instanceType == null || instanceType.isBlank()) {
            return Optional.empty();
        }
        CatalogInstanceType found = loaded().byName.get(instanceType.trim());
        return found == null ? Optional.empty() : Optional.of(found.gpuCount);
    }

    /**
     * Whether the type belongs to a family that carries accelerators on AWS, regardless of
     * whether this catalog lists the specific size.
     *
     * <p>Case sensitive, like {@link #gpuCount(String)} and like EC2's {@code find} and
     * {@code familyOf}: AWS instance types are exact tokens, so folding case here would make
     * this disagree with the catalog lookup and answer "GPU family" for a spelling the lookup
     * then reports as missing from the catalog.
     */
    public static boolean isAcceleratorFamily(String instanceType) {
        if (instanceType == null || instanceType.isBlank()) {
            return false;
        }
        String normalized = instanceType.trim();
        return ACCELERATOR_FAMILY_PREFIXES.stream().anyMatch(normalized::startsWith);
    }

    private Loaded loaded() {
        Loaded current = loaded;
        if (current == null) {
            synchronized (this) {
                current = loaded;
                if (current == null) {
                    current = new Loaded(readCatalog());
                    loaded = current;
                }
            }
        }
        return current;
    }

    private static Catalog readCatalog() {
        try (InputStream in = Thread.currentThread().getContextClassLoader()
                .getResourceAsStream(CATALOG_RESOURCE_NAME)) {
            if (in == null) {
                throw new IllegalStateException("Missing catalog resource " + CATALOG_RESOURCE_NAME);
            }
            return YAML_MAPPER.readValue(in, Catalog.class);
        } catch (IOException e) {
            throw new IllegalStateException("Could not read " + CATALOG_RESOURCE_NAME, e);
        }
    }

    private static final class Loaded {
        private final Map<String, CatalogInstanceType> byName;

        private Loaded(Catalog catalog) {
            Map<String, CatalogInstanceType> index = new LinkedHashMap<>();
            for (CatalogInstanceType type : catalog.instanceTypes) {
                index.put(type.instanceType, type);
            }
            this.byName = Map.copyOf(index);
        }
    }

    @RegisterForReflection
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static final class Catalog {
        public List<CatalogInstanceType> instanceTypes = List.of();
    }

    @RegisterForReflection
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static final class CatalogInstanceType {
        public String instanceType;
        public int gpuCount;
    }
}
