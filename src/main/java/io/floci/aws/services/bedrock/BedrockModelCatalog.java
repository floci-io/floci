package io.floci.aws.services.bedrock;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import io.floci.aws.core.common.VendoredResources;
import io.quarkus.runtime.annotations.RegisterForReflection;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * The foundation models and system-defined inference profiles Bedrock lists, read once from
 * {@code bedrock/model-catalog.yaml}.
 *
 * <p>The YAML is read through a constant, which Quarkus's native resource analysis cannot follow,
 * so it is registered under {@code quarkus.native.resources.includes} in {@code application.yml}.
 */
final class BedrockModelCatalog {

    private static final String CATALOG_RESOURCE_NAME = "bedrock/model-catalog.yaml";

    record FoundationModel(String modelId, String modelName, String providerName, List<String> inputModalities,
                           List<String> outputModalities, boolean responseStreamingSupported,
                           String lifecycleStatus, Set<String> inRegion) {

        /** Whether the model is invoked on demand in the Region itself, rather than only through a profile. */
        boolean servedInRegion(String region) {
            return inRegion.contains(region);
        }
    }

    /**
     * @param routes the destination Regions each source Region routes to, in the order the model
     *               card lists them
     */
    record InferenceProfile(String inferenceProfileId, String inferenceProfileName, String modelId,
                            Instant createdAt, Map<String, List<String>> routes) {
    }

    static final List<FoundationModel> MODELS;
    static final List<InferenceProfile> PROFILES;

    static {
        Catalog catalog = load();
        MODELS = catalog.foundationModels.stream()
                .map(entry -> new FoundationModel(entry.modelId, entry.modelName, entry.providerName,
                        List.copyOf(entry.inputModalities), List.copyOf(entry.outputModalities),
                        entry.responseStreamingSupported, entry.lifecycleStatus, Set.copyOf(entry.inRegion)))
                .toList();
        PROFILES = catalog.inferenceProfiles.stream()
                .map(entry -> new InferenceProfile(entry.inferenceProfileId, entry.inferenceProfileName,
                        entry.modelId, Instant.parse(entry.createdAt), copyRoutes(entry.routes)))
                .toList();
    }

    private BedrockModelCatalog() {
    }

    /** The models callable from a Region, in their own Region or through one of their profiles. */
    static List<FoundationModel> modelsIn(String region) {
        return MODELS.stream()
                .filter(model -> model.servedInRegion(region) || PROFILES.stream()
                        .anyMatch(profile -> profile.modelId().equals(model.modelId())
                                && profile.routes().containsKey(region)))
                .toList();
    }

    /** The profiles a Region is a source Region of. */
    static List<InferenceProfile> profilesFrom(String region) {
        return PROFILES.stream().filter(profile -> profile.routes().containsKey(region)).toList();
    }

    private static Map<String, List<String>> copyRoutes(Map<String, List<String>> routes) {
        return routes.entrySet().stream()
                .collect(Collectors.toUnmodifiableMap(Map.Entry::getKey, entry -> List.copyOf(entry.getValue())));
    }

    private static Catalog load() {
        return VendoredResources.read(CATALOG_RESOURCE_NAME, "Bedrock model catalog",
                in -> new ObjectMapper(new YAMLFactory()).readValue(in, Catalog.class));
    }

    @RegisterForReflection
    @JsonIgnoreProperties(ignoreUnknown = true)
    static final class Catalog {
        public List<ModelEntry> foundationModels = List.of();
        public List<ProfileEntry> inferenceProfiles = List.of();
    }

    @RegisterForReflection
    @JsonIgnoreProperties(ignoreUnknown = true)
    static final class ModelEntry {
        public String modelId;
        public String modelName;
        public String providerName;
        public List<String> inputModalities = List.of();
        public List<String> outputModalities = List.of();
        public boolean responseStreamingSupported;
        public String lifecycleStatus;
        public List<String> inRegion = List.of();
    }

    @RegisterForReflection
    @JsonIgnoreProperties(ignoreUnknown = true)
    static final class ProfileEntry {
        public String inferenceProfileId;
        public String inferenceProfileName;
        public String modelId;
        public String createdAt;
        public Map<String, List<String>> routes = Map.of();
    }
}
