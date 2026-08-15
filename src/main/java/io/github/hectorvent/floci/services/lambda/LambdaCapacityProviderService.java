package io.github.hectorvent.floci.services.lambda;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.core.common.RegionResolver;
import io.github.hectorvent.floci.core.storage.StorageBackend;
import io.github.hectorvent.floci.core.storage.StorageFactory;
import io.github.hectorvent.floci.services.lambda.model.LambdaCapacityProvider;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Lambda capacity providers (API version 2025-11-30).
 *
 * <p>A capacity provider is a control-plane record over VPC subnets and an operator role;
 * floci does not launch the compute instances behind it. State is {@code Active} as soon as
 * a create returns so SDK and Terraform waiters complete on their first poll.
 */
@ApplicationScoped
public class LambdaCapacityProviderService {

    private static final Logger LOG = Logger.getLogger(LambdaCapacityProviderService.class);
    private static final String ARN_RESOURCE_TYPE = "capacity-provider";

    private final StorageBackend<String, LambdaCapacityProvider> providers;
    private final RegionResolver regionResolver;

    @Inject
    public LambdaCapacityProviderService(StorageFactory storageFactory, RegionResolver regionResolver) {
        this.providers = storageFactory.create("lambda", "lambda-capacity-providers.json",
                new TypeReference<Map<String, LambdaCapacityProvider>>() {});
        this.regionResolver = regionResolver;
    }

    /**
     * Whether an ARN addresses a capacity provider. The Lambda tag routes are shared across
     * every Lambda resource family, so the tag controller dispatches on this.
     */
    public static boolean ownsArn(String arn) {
        return arn != null && arn.contains(":lambda:") && arn.contains(":" + ARN_RESOURCE_TYPE + ":");
    }

    public LambdaCapacityProvider create(String region, JsonNode request) {
        String name = text(request, "CapacityProviderName");
        if (name == null || name.isBlank()) {
            throw new AwsException("InvalidParameterValueException",
                    "CapacityProviderName is required", 400);
        }
        JsonNode vpcConfig = request.get("VpcConfig");
        requireStringList(vpcConfig, "SubnetIds", "VpcConfig.SubnetIds");
        requireStringList(vpcConfig, "SecurityGroupIds", "VpcConfig.SecurityGroupIds");

        JsonNode permissionsConfig = request.get("PermissionsConfig");
        if (permissionsConfig == null || !permissionsConfig.isObject()
                || text(permissionsConfig, "CapacityProviderOperatorRoleArn") == null) {
            throw new AwsException("InvalidParameterValueException",
                    "PermissionsConfig.CapacityProviderOperatorRoleArn is required", 400);
        }

        if (providers.get(key(region, name)).isPresent()) {
            throw new AwsException("ResourceConflictException",
                    "Capacity provider already exists: " + name, 409);
        }

        LambdaCapacityProvider provider = new LambdaCapacityProvider();
        provider.setName(name);
        provider.setRegion(region);
        provider.setArn(regionResolver.buildArn("lambda", region, ARN_RESOURCE_TYPE + ":" + name));
        provider.setState("Active");
        provider.setVpcConfig(vpcConfig);
        provider.setPermissionsConfig(permissionsConfig);
        provider.setInstanceRequirements(nodeOrNull(request, "InstanceRequirements"));
        provider.setScalingConfig(nodeOrNull(request, "CapacityProviderScalingConfig"));
        provider.setKmsKeyArn(text(request, "KmsKeyArn"));
        provider.setLastModified(now());
        provider.setTags(readTags(request.get("Tags")));

        providers.put(key(region, name), provider);
        LOG.infov("Created Lambda capacity provider: {0}", provider.getArn());
        return provider;
    }

    public LambdaCapacityProvider get(String region, String nameOrArn) {
        String name = resolveName(nameOrArn);
        return providers.get(key(region, name))
                .orElseThrow(() -> new AwsException("ResourceNotFoundException",
                        "Capacity provider not found: " + nameOrArn, 404));
    }

    public LambdaCapacityProvider update(String region, String nameOrArn, JsonNode request) {
        LambdaCapacityProvider provider = get(region, nameOrArn);
        if (request != null && request.has("CapacityProviderScalingConfig")) {
            provider.setScalingConfig(nodeOrNull(request, "CapacityProviderScalingConfig"));
        }
        provider.setLastModified(now());
        providers.put(key(region, provider.getName()), provider);
        LOG.infov("Updated Lambda capacity provider: {0}", provider.getArn());
        return provider;
    }

    /**
     * Deletes the provider and returns the record as it stood, with the {@code Deleting}
     * state AWS reports on the delete call. A subsequent read raises
     * {@code ResourceNotFoundException}, which is what a delete waiter is looking for.
     */
    public LambdaCapacityProvider delete(String region, String nameOrArn) {
        LambdaCapacityProvider provider = get(region, nameOrArn);
        providers.delete(key(region, provider.getName()));
        provider.setState("Deleting");
        LOG.infov("Deleted Lambda capacity provider: {0}", provider.getArn());
        return provider;
    }

    public List<LambdaCapacityProvider> list(String region) {
        String prefix = "capacity-provider::" + region + "::";
        return providers.scan(k -> k.startsWith(prefix)).stream()
                .sorted(Comparator.comparing(LambdaCapacityProvider::getName))
                .toList();
    }

    public Map<String, String> listTags(String region, String arn) {
        return new HashMap<>(get(region, arn).getTags());
    }

    public void tagResource(String region, String arn, Map<String, String> tags) {
        LambdaCapacityProvider provider = get(region, arn);
        provider.getTags().putAll(tags);
        providers.put(key(region, provider.getName()), provider);
    }

    public void untagResource(String region, String arn, List<String> tagKeys) {
        LambdaCapacityProvider provider = get(region, arn);
        if (tagKeys != null) {
            tagKeys.forEach(provider.getTags()::remove);
        }
        providers.put(key(region, provider.getName()), provider);
    }

    /**
     * {@code CapacityProviderName} accepts either a bare name or a full ARN; the AWS pattern
     * for the member allows both.
     */
    private static String resolveName(String nameOrArn) {
        if (nameOrArn == null || nameOrArn.isBlank()) {
            throw new AwsException("InvalidParameterValueException",
                    "CapacityProviderName is required", 400);
        }
        if (!nameOrArn.startsWith("arn:")) {
            return nameOrArn;
        }
        int marker = nameOrArn.indexOf(":" + ARN_RESOURCE_TYPE + ":");
        if (marker < 0) {
            throw new AwsException("InvalidParameterValueException",
                    "Not a capacity provider ARN: " + nameOrArn, 400);
        }
        return nameOrArn.substring(marker + ARN_RESOURCE_TYPE.length() + 2);
    }

    private static String key(String region, String name) {
        return "capacity-provider::" + region + "::" + name;
    }

    private static String now() {
        return DateTimeFormatter.ISO_INSTANT.format(Instant.now().atOffset(ZoneOffset.UTC));
    }

    private static String text(JsonNode node, String field) {
        if (node == null) {
            return null;
        }
        JsonNode value = node.get(field);
        return value == null || value.isNull() ? null : value.asText();
    }

    private static JsonNode nodeOrNull(JsonNode node, String field) {
        JsonNode value = node == null ? null : node.get(field);
        return value == null || value.isNull() ? null : value;
    }

    private static void requireStringList(JsonNode parent, String field, String label) {
        JsonNode value = parent == null ? null : parent.get(field);
        if (value == null || !value.isArray() || value.isEmpty()) {
            throw new AwsException("InvalidParameterValueException", label + " is required", 400);
        }
    }

    private static Map<String, String> readTags(JsonNode tagsNode) {
        Map<String, String> tags = new HashMap<>();
        if (tagsNode != null && tagsNode.isObject()) {
            tagsNode.fields().forEachRemaining(e -> tags.put(e.getKey(), e.getValue().asText()));
        }
        return tags;
    }

    Optional<LambdaCapacityProvider> find(String region, String name) {
        return providers.get(key(region, name));
    }
}
