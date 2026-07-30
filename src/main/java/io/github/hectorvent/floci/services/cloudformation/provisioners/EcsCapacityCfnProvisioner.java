package io.github.hectorvent.floci.services.cloudformation.provisioners;

import com.fasterxml.jackson.databind.JsonNode;
import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.services.cloudformation.model.StackResource;
import io.github.hectorvent.floci.services.ecs.EcsService;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * CloudFormation provisioning for ECS capacity: {@code AWS::ECS::CapacityProvider} and
 * {@code AWS::ECS::ClusterCapacityProviderAssociations}. Injects only {@link EcsService}, per the
 * per-service provisioner decomposition of {@code CloudFormationResourceProvisioner}.
 *
 * <p>CloudFormation spells these properties in PascalCase while {@code EcsService} stores the ECS
 * JSON API's camelCase shape (that is what {@code CreateCapacityProvider} puts there). The whole
 * {@code AutoScalingGroupProvider} sub-tree is therefore carried across with its keys
 * decapitalized, so a capacity provider created by a stack holds the same state as one created
 * through the API — including {@code ManagedScaling}, {@code ManagedTerminationProtection} and
 * {@code ManagedDraining}.
 */
@ApplicationScoped
public class EcsCapacityCfnProvisioner implements CfnResourceProvisioner {

    private static final Logger LOG = Logger.getLogger(EcsCapacityCfnProvisioner.class);

    private static final String CAPACITY_PROVIDER = "AWS::ECS::CapacityProvider";
    private static final String ASSOCIATIONS = "AWS::ECS::ClusterCapacityProviderAssociations";

    private final EcsService ecsService;

    @Inject
    public EcsCapacityCfnProvisioner(EcsService ecsService) {
        this.ecsService = ecsService;
    }

    @Override
    public Set<String> resourceTypes() {
        return Set.of(CAPACITY_PROVIDER, ASSOCIATIONS);
    }

    @Override
    public void provision(StackResource r, JsonNode props, ProvisionContext ctx) {
        switch (r.getResourceType()) {
            case CAPACITY_PROVIDER -> provisionCapacityProvider(r, props, ctx);
            case ASSOCIATIONS -> provisionAssociations(r, props, ctx);
            default -> throw new IllegalStateException(
                    "EcsCapacityCfnProvisioner cannot handle " + r.getResourceType());
        }
    }

    @Override
    public void delete(String resourceType, String physicalId, String region) {
        if (physicalId == null || physicalId.isBlank()) {
            return;
        }
        switch (resourceType) {
            case CAPACITY_PROVIDER -> deleteCapacityProvider(physicalId);
            // Deleting the association resource leaves the cluster in place with no capacity
            // providers attached, which is what AWS does: the associations are the resource.
            case ASSOCIATIONS -> clearAssociations(physicalId, region);
            default -> { }
        }
    }

    private void provisionCapacityProvider(StackResource r, JsonNode props, ProvisionContext ctx) {
        String name = ctx.resolveOptional(props, "Name");
        if (name == null || name.isBlank()) {
            name = ctx.generatePhysicalName(r.getLogicalId(), 255, false);
        }
        Map<String, Object> asgProvider = asgProvider(props, ctx);
        Map<String, String> tags = tags(props, ctx);
        ecsService.createCapacityProvider(name, asgProvider, tags, ctx.region());
        // Ref returns the capacity provider name; AWS exposes no Fn::GetAtt attributes here.
        r.setPhysicalId(name);
    }

    private void provisionAssociations(StackResource r, JsonNode props, ProvisionContext ctx) {
        String cluster = ctx.resolveOptional(props, "Cluster");
        List<String> providers = new ArrayList<>();
        if (props != null && props.has("CapacityProviders")) {
            for (JsonNode entry : props.get("CapacityProviders")) {
                String id = ctx.engine().resolve(entry);
                if (id != null && !id.isBlank()) {
                    providers.add(id);
                }
            }
        }
        List<Map<String, Object>> strategy = new ArrayList<>();
        if (props != null && props.has("DefaultCapacityProviderStrategy")) {
            for (JsonNode entry : props.get("DefaultCapacityProviderStrategy")) {
                Map<String, Object> item = new HashMap<>();
                String provider = ctx.engine().resolve(entry.path("CapacityProvider"));
                if (provider != null) {
                    item.put("capacityProvider", provider);
                }
                if (entry.hasNonNull("Weight")) {
                    item.put("weight", entry.get("Weight").asInt());
                }
                if (entry.hasNonNull("Base")) {
                    item.put("base", entry.get("Base").asInt());
                }
                strategy.add(item);
            }
        }
        ecsService.putClusterCapacityProviders(cluster, providers, strategy, ctx.region());
        r.setPhysicalId(cluster);
    }

    /**
     * Carries {@code AutoScalingGroupProvider} across verbatim, resolving intrinsics and
     * decapitalizing keys so the stored shape matches {@code CreateCapacityProvider}'s.
     */
    private Map<String, Object> asgProvider(JsonNode props, ProvisionContext ctx) {
        JsonNode asg = props != null ? props.path("AutoScalingGroupProvider") : null;
        if (asg == null || !asg.isObject()) {
            return new LinkedHashMap<>();
        }
        Object converted = toApiShape(ctx.engine().resolveNode(asg));
        if (converted instanceof Map<?, ?> map) {
            @SuppressWarnings("unchecked")
            Map<String, Object> typed = (Map<String, Object>) map;
            return typed;
        }
        return new LinkedHashMap<>();
    }

    private Map<String, String> tags(JsonNode props, ProvisionContext ctx) {
        Map<String, String> out = new HashMap<>();
        JsonNode tagsNode = props != null ? props.get("Tags") : null;
        if (tagsNode == null || !tagsNode.isArray()) {
            return out;
        }
        for (JsonNode entry : tagsNode) {
            JsonNode resolved = ctx.engine().resolveNode(entry);
            String key = resolved.path("Key").asText(null);
            if (key != null) {
                out.put(key, resolved.path("Value").asText(""));
            }
        }
        return out;
    }

    /** Recursively converts a resolved CloudFormation node to the ECS API's camelCase Java shape. */
    private Object toApiShape(JsonNode node) {
        if (node == null || node.isNull() || node.isMissingNode()) {
            return null;
        }
        if (node.isObject()) {
            Map<String, Object> out = new LinkedHashMap<>();
            node.fields().forEachRemaining(field -> {
                Object value = toApiShape(field.getValue());
                if (value != null) {
                    out.put(decapitalize(field.getKey()), value);
                }
            });
            return out;
        }
        if (node.isArray()) {
            List<Object> out = new ArrayList<>();
            for (JsonNode item : node) {
                Object value = toApiShape(item);
                if (value != null) {
                    out.add(value);
                }
            }
            return out;
        }
        if (node.isBoolean()) {
            return node.booleanValue();
        }
        if (node.isIntegralNumber()) {
            return node.asInt();
        }
        if (node.isNumber()) {
            return node.asDouble();
        }
        String text = node.asText();
        return text.isEmpty() ? null : text;
    }

    private String decapitalize(String key) {
        if (key == null || key.isEmpty()) {
            return key;
        }
        return Character.toLowerCase(key.charAt(0)) + key.substring(1);
    }

    private void deleteCapacityProvider(String physicalId) {
        // Idempotent: a provider that never got created (rollback) or was already removed is
        // delete-complete. describeCapacityProviders resolves both names and ARNs.
        if (ecsService.describeCapacityProviders(List.of(physicalId)).isEmpty()) {
            LOG.debugv("ECS capacity provider {0} already gone, treating delete as complete", physicalId);
            return;
        }
        ecsService.deleteCapacityProvider(physicalId);
    }

    private void clearAssociations(String clusterRef, String region) {
        try {
            ecsService.putClusterCapacityProviders(clusterRef, List.of(), List.of(), region);
        } catch (AwsException e) {
            // The cluster itself is deleted right after this resource; if it is already gone there
            // is nothing left to detach. Any other failure must still fail the stack delete.
            if (!"ClusterNotFoundException".equals(e.getErrorCode())) {
                throw e;
            }
            LOG.debugv("ECS cluster {0} already gone, treating association delete as complete: {1}",
                    clusterRef, e.getMessage());
        }
    }
}
