package io.github.hectorvent.floci.services.cloudformation.provisioners;

import com.fasterxml.jackson.databind.JsonNode;
import io.github.hectorvent.floci.services.cloudformation.model.StackResource;
import io.github.hectorvent.floci.services.route53.Route53Service;
import io.github.hectorvent.floci.services.route53.model.HostedZoneVpc;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

@ApplicationScoped
public class Route53CfnProvisioner implements CfnResourceProvisioner {
    private final Route53Service route53Service;

    @Inject
    public Route53CfnProvisioner(Route53Service route53Service) {
        this.route53Service = route53Service;
    }

    @Override
    public Set<String> resourceTypes() {
        return Set.of("AWS::Route53::HostedZone");
    }

    @Override
    public void provision(StackResource resource, JsonNode props, ProvisionContext ctx) {
        String name = ctx.resolveOptional(props, "Name");
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("AWS::Route53::HostedZone requires Name");
        }

        JsonNode resolved = ctx.engine().resolveNode(props);
        String comment = resolved.path("HostedZoneConfig").path("Comment").asText(null);
        List<HostedZoneVpc> vpcs = parseVpcs(resolved.path("VPCs"));
        String callerReference = ctx.stackName() + "/" + resource.getLogicalId();
        String id;
        if (resource.getPhysicalId() == null) {
            Route53Service.CreateZoneResult created = route53Service.createHostedZone(
                    name, callerReference, comment, !vpcs.isEmpty(), vpcs);
            id = created.zone().getId();
        } else {
            id = resource.getPhysicalId();
            route53Service.ensureHostedZone(
                    id, name, callerReference, comment, !vpcs.isEmpty(), vpcs);
        }

        resource.setPhysicalId(id);
        resource.getAttributes().put("Id", id);
        resource.getAttributes().put("NameServers", String.join(",", route53Service.getNameServers()));

        List<Map<String, String>> tags = parseTags(resolved.path("HostedZoneTags"));
        if (!tags.isEmpty()) {
            route53Service.changeTagsForResource("hostedzone", id, tags, List.of());
        }
    }

    @Override
    public void delete(String resourceType, String physicalId, String region, String accountId) {
        route53Service.deleteHostedZone(physicalId);
    }

    private List<HostedZoneVpc> parseVpcs(JsonNode node) {
        List<HostedZoneVpc> vpcs = new ArrayList<>();
        if (node.isArray()) {
            for (JsonNode item : node) {
                String id = item.path("VPCId").asText(null);
                String region = item.path("VPCRegion").asText(null);
                if (id != null && region != null) {
                    vpcs.add(new HostedZoneVpc(id, region));
                }
            }
        }
        return vpcs;
    }

    private List<Map<String, String>> parseTags(JsonNode node) {
        List<Map<String, String>> tags = new ArrayList<>();
        if (node.isArray()) {
            for (JsonNode item : node) {
                String key = item.path("Key").asText(null);
                if (key != null) {
                    tags.add(Map.of("Key", key, "Value", item.path("Value").asText("")));
                }
            }
        }
        return tags;
    }
}
