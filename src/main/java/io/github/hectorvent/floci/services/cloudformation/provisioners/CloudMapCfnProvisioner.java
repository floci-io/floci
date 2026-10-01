package io.github.hectorvent.floci.services.cloudformation.provisioners;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.MissingNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.hectorvent.floci.services.cloudformation.model.StackResource;
import io.github.hectorvent.floci.services.cloudmap.CloudMapService;
import io.github.hectorvent.floci.services.cloudmap.model.Namespace;
import io.github.hectorvent.floci.services.cloudmap.model.Operation;
import io.github.hectorvent.floci.services.cloudmap.model.Service;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.math.BigInteger;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * CloudFormation provisioning for the three Cloud Map namespace types
 * ({@code AWS::ServiceDiscovery::PrivateDnsNamespace}, {@code PublicDnsNamespace} and
 * {@code HttpNamespace}) and {@code AWS::ServiceDiscovery::Service}. {@code Ref} returns the id;
 * {@code Fn::GetAtt} exposes {@code Id} and {@code Arn}, plus {@code HostedZoneId} on a DNS
 * namespace and {@code Name} on a service.
 * Description, DnsConfig, HealthCheckConfig and Tags update in place, keeping the prior values to put
 * back when a later resource fails the update; a createOnly change replaces the resource through
 * {@link ReplacementCleanup}. A {@code Ref} to a Number parameter resolves to text, so the numeric
 * DnsConfig and health check fields are coerced back to JSON numbers.
 */
@ApplicationScoped
public class CloudMapCfnProvisioner implements CfnResourceProvisioner {

    private static final String PRIVATE_DNS_NAMESPACE = "AWS::ServiceDiscovery::PrivateDnsNamespace";
    private static final String PUBLIC_DNS_NAMESPACE = "AWS::ServiceDiscovery::PublicDnsNamespace";
    private static final String HTTP_NAMESPACE = "AWS::ServiceDiscovery::HttpNamespace";
    private static final String SERVICE = "AWS::ServiceDiscovery::Service";
    private static final String CREATE_ONLY_ATTR = "__FlociCreateOnly";
    private static final String CREATE_ONLY_PRIOR_ATTR = "__FlociCreateOnlyPrior";
    private static final String SNAPSHOT_ATTR = "__FlociCloudMapUpdateSnapshot";
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final CloudMapService cloudMapService;

    @Inject
    public CloudMapCfnProvisioner(CloudMapService cloudMapService) {
        this.cloudMapService = cloudMapService;
    }

    @Override
    public Set<String> resourceTypes() {
        return Set.of(PRIVATE_DNS_NAMESPACE, PUBLIC_DNS_NAMESPACE, HTTP_NAMESPACE, SERVICE);
    }

    @Override
    public void provision(StackResource r, JsonNode props, ProvisionContext ctx) {
        Map<String, String> attributesBefore = new HashMap<>(r.getAttributes());
        switch (r.getResourceType()) {
            case PRIVATE_DNS_NAMESPACE, PUBLIC_DNS_NAMESPACE, HTTP_NAMESPACE -> provisionNamespace(r, props, ctx);
            case SERVICE -> provisionService(r, props, ctx);
        }
        ReplacementCleanup.record(r, ctx, attributesBefore);
        if (ctx.isUpdate() && !ctx.priorPhysicalId().equals(r.getPhysicalId())) {
            r.getAttributes().put(CREATE_ONLY_PRIOR_ATTR, attributesBefore.getOrDefault(CREATE_ONLY_ATTR, ""));
        }
    }

    private void provisionNamespace(StackResource r, JsonNode props, ProvisionContext ctx) {
        String name = ctx.resolveOptional(props, "Name");
        String vpc = ctx.resolveOptional(props, "Vpc");
        String description = ctx.resolveOptional(props, "Description");
        Map<String, String> tags = ctx.resolveTags(props, "Tags");
        String createOnly = Arrays.asList(name, vpc).toString();

        if (ctx.isUpdate() && createOnly.equals(r.getAttributes().get(CREATE_ONLY_ATTR))) {
            Namespace current = cloudMapService.getNamespace(ctx.priorPhysicalId());
            snapshot(r, current.getDescription(), null, null, current.getTags());
            cloudMapService.updateNamespace(ctx.priorPhysicalId(), description);
            reconcileTags(r.getAttributes().get("Arn"), tags);
            return;
        }

        Operation op = switch (r.getResourceType()) {
            case HTTP_NAMESPACE -> cloudMapService.createHttpNamespace(name, null, description, tags, ctx.region());
            case PUBLIC_DNS_NAMESPACE ->
                    cloudMapService.createPublicDnsNamespace(name, null, description, tags, ctx.region());
            default -> cloudMapService.createPrivateDnsNamespace(name, vpc, null, description, tags, ctx.region());
        };
        Namespace ns = cloudMapService.getNamespace(op.getTargets().get("NAMESPACE"));
        r.setPhysicalId(ns.getId());
        r.getAttributes().put("Id", ns.getId());
        r.getAttributes().put("Arn", ns.getArn());
        if (ns.getHostedZoneId() != null) {
            r.getAttributes().put("HostedZoneId", ns.getHostedZoneId());
        }
        r.getAttributes().put(CREATE_ONLY_ATTR, createOnly);
    }

    private void provisionService(StackResource r, JsonNode props, ProvisionContext ctx) {
        JsonNode dnsConfig = resolveJson(props, "DnsConfig", ctx);
        for (JsonNode record : dnsConfig.path("DnsRecords")) {
            toNumber(record, "TTL");
        }
        JsonNode healthCheckConfig = resolveJson(props, "HealthCheckConfig", ctx);
        toNumber(healthCheckConfig, "FailureThreshold");
        JsonNode customConfig = resolveJson(props, "HealthCheckCustomConfig", ctx);
        toNumber(customConfig, "FailureThreshold");

        String namespaceId = ctx.resolveOptional(props, "NamespaceId");
        if (namespaceId == null) {
            namespaceId = dnsConfig.path("NamespaceId").textValue();
        }
        String name = ctx.resolveOptional(props, "Name");
        String type = ctx.resolveOptional(props, "Type");
        String description = ctx.resolveOptional(props, "Description");
        Map<String, String> tags = ctx.resolveTags(props, "Tags");
        String createOnly = Arrays.asList(name, namespaceId, type, json(customConfig)).toString();

        if (ctx.isUpdate() && createOnly.equals(r.getAttributes().get(CREATE_ONLY_ATTR))) {
            Service current = cloudMapService.getService(ctx.priorPhysicalId());
            snapshot(r, current.getDescription(), current.getDnsConfig(), current.getHealthCheckConfig(),
                    current.getTags());
            cloudMapService.updateService(ctx.priorPhysicalId(), description, json(dnsConfig),
                    json(healthCheckConfig));
            reconcileTags(r.getAttributes().get("Arn"), tags);
            return;
        }

        String serviceName = name == null || name.isBlank() ? generatedName(r.getLogicalId()) : name;
        Service service = cloudMapService.createService(serviceName, namespaceId, null, description,
                json(dnsConfig), json(healthCheckConfig), json(customConfig), type, tags, ctx.region());
        r.setPhysicalId(service.getId());
        r.getAttributes().put("Id", service.getId());
        r.getAttributes().put("Arn", service.getArn());
        r.getAttributes().put("Name", service.getName());
        r.getAttributes().put(CREATE_ONLY_ATTR, createOnly);
    }

    /** Keeps what an in-place update overwrites, for {@link #rollbackUpdate} to put back. */
    private static void snapshot(StackResource r, String description, String dnsConfig,
                                 String healthCheckConfig, Map<String, String> tags) {
        ObjectNode snapshot = MAPPER.createObjectNode();
        snapshot.put("Description", description);
        snapshot.put("DnsConfig", dnsConfig);
        snapshot.put("HealthCheckConfig", healthCheckConfig);
        snapshot.set("Tags", MAPPER.valueToTree(tags));
        r.getAttributes().put(SNAPSHOT_ATTR, snapshot.toString());
    }

    /** Resolves an object property to a fresh node, safe to mutate, or a MissingNode when absent. */
    private static JsonNode resolveJson(JsonNode props, String name, ProvisionContext ctx) {
        return props == null ? MissingNode.getInstance() : ctx.engine().resolveNode(props.path(name));
    }

    private static String json(JsonNode node) {
        return node.isObject() ? node.toString() : null;
    }

    private static void toNumber(JsonNode parent, String field) {
        if (parent instanceof ObjectNode object && object.path(field).isTextual()) {
            String text = object.path(field).textValue();
            if (text.matches("\\d+")) {
                object.put(field, new BigInteger(text));
            }
        }
    }

    /** CloudFormation names an unnamed service {@code <LogicalId>-<12 chars>}, with no stack prefix. */
    private static String generatedName(String logicalId) {
        String prefix = logicalId.length() > 50 ? logicalId.substring(0, 50) : logicalId;
        return prefix + "-" + UUID.randomUUID().toString().replace("-", "").substring(0, 12);
    }

    private void reconcileTags(String arn, Map<String, String> desired) {
        cloudMapService.untagResource(arn,
                ProvisionContext.staleTagKeys(cloudMapService.listTagsForResource(arn), desired));
        cloudMapService.tagResource(arn, desired);
    }

    @Override
    public void delete(String resourceType, String physicalId, String region) {
        if (SERVICE.equals(resourceType)) {
            CfnDeletes.safeDelete("Cloud Map service", physicalId,
                    () -> cloudMapService.deleteService(physicalId), "ServiceNotFound");
            return;
        }
        CfnDeletes.safeDelete("Cloud Map namespace", physicalId,
                () -> cloudMapService.deleteNamespace(physicalId, region), "NamespaceNotFound");
    }

    @Override
    public boolean hasReplacementUpdate(StackResource resource) {
        return ReplacementCleanup.hasReplacement(resource);
    }

    @Override
    public String updateCleanupPhysicalId(StackResource resource) {
        return ReplacementCleanup.cleanupPhysicalId(resource);
    }

    @Override
    public UpdateCleanupResult completeUpdate(StackResource resource) {
        resource.getAttributes().remove(CREATE_ONLY_PRIOR_ATTR);
        resource.getAttributes().remove(SNAPSHOT_ATTR);
        return ReplacementCleanup.complete(resource, this::delete);
    }

    @Override
    public void clearUpdate(StackResource resource) {
        resource.getAttributes().remove(CREATE_ONLY_PRIOR_ATTR);
        ReplacementCleanup.clear(resource);
    }

    /**
     * A replacement is undone through the cleanup record, an in-place update from the snapshot taken
     * before it. With neither, the provision failed before changing anything (every in-place
     * mutation is preceded by the snapshot, every replacement followed by the record), so there is
     * nothing to undo. {@link ReplacementCleanup} leaves {@code __Floci} attributes alone, so the
     * createOnly record of the prior entity is put back here. A restore that throws leaves the
     * snapshot for the next attempt.
     */
    @Override
    public boolean rollbackUpdate(StackResource resource) {
        boolean replaced = ReplacementCleanup.rollback(resource, this::delete);
        String prior = resource.getAttributes().remove(CREATE_ONLY_PRIOR_ATTR);
        if (prior != null) {
            if (prior.isEmpty()) {
                resource.getAttributes().remove(CREATE_ONLY_ATTR);
            } else {
                resource.getAttributes().put(CREATE_ONLY_ATTR, prior);
            }
        }
        String snapshot = resource.getAttributes().get(SNAPSHOT_ATTR);
        if (!replaced && snapshot != null) {
            restore(resource, snapshot);
        }
        resource.getAttributes().remove(SNAPSHOT_ATTR);
        return true;
    }

    private void restore(StackResource resource, String raw) {
        JsonNode snapshot;
        try {
            snapshot = MAPPER.readTree(raw);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Could not read the Cloud Map update snapshot for "
                    + resource.getLogicalId(), e);
        }
        String description = snapshot.path("Description").textValue();
        if (SERVICE.equals(resource.getResourceType())) {
            cloudMapService.updateService(resource.getPhysicalId(), description,
                    snapshot.path("DnsConfig").textValue(), snapshot.path("HealthCheckConfig").textValue());
        } else {
            cloudMapService.updateNamespace(resource.getPhysicalId(), description);
        }
        Map<String, String> tags = new HashMap<>();
        snapshot.path("Tags").fields().forEachRemaining(tag -> tags.put(tag.getKey(), tag.getValue().asText()));
        reconcileTags(resource.getAttributes().get("Arn"), tags);
    }
}
