package io.github.hectorvent.floci.services.cloudformation.provisioners;

import com.fasterxml.jackson.databind.JsonNode;
import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.services.cloudformation.model.StackResource;
import io.github.hectorvent.floci.services.route53.Route53Service;
import io.github.hectorvent.floci.services.route53.model.VpcAssociation;
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
        List<VpcAssociation> vpcs = parseVpcs(resolved.path("VPCs"));
        String callerReference = ctx.stackName() + "/" + resource.getLogicalId();
        boolean isTrackedZoneMissing = resource.getPhysicalId() != null
                && !zoneExists(resource.getPhysicalId());
        if (isTrackedZoneMissing) {
            // The tracked physical ID may be a leftover from the retired monolith's HostedZone
            // stub, which minted a random Z-id with zero Route53Service backing (see commit
            // 76228741d). There is no real zone behind it to migrate, so recreate rather than
            // fail the update and roll back the stack.
            resource.setPhysicalId(null);
        }

        String id;
        if (resource.getPhysicalId() == null) {
            // CreateHostedZone only accepts a single VPC (a private zone becomes
            // resolvable from that VPC immediately); any further VPCs in the CFN
            // template's list are wired in afterward via AssociateVPCWithHostedZone,
            // matching how a real CloudFormation update converges an existing zone.
            VpcAssociation firstVpc = vpcs.isEmpty() ? null : vpcs.get(0);
            Route53Service.CreateZoneResult created = route53Service.createHostedZone(
                    name, callerReference, comment, firstVpc);
            id = created.zone().getId();
            // Record the physical ID before any follow-up call that can fail: once the
            // zone exists, the stack engine must be able to track and clean it up even
            // if a later VPC association or tag write throws. Marking it rollback-owned
            // too means a CREATE_FAILED status from that later failure still gets cleaned
            // up during stack-create rollback, which keys off that attribute rather than
            // physicalId alone (see CfnRollback.ROLLBACK_OWNED_ATTR).
            resource.setPhysicalId(id);
            resource.getAttributes().put(CfnRollback.ROLLBACK_OWNED_ATTR, "true");
            try {
                for (int i = 1; i < vpcs.size(); i++) {
                    route53Service.associateVpcWithHostedZone(id, vpcs.get(i), comment);
                }
            } catch (RuntimeException e) {
                // An update that recreates a missing legacy zone (isTrackedZoneMissing above)
                // fails through CloudFormationService's generic update-rollback path, which
                // restores the stack's previous StackResource wholesale and never learns this
                // zone's ID - orphaning it, so the next retry reuses the same caller reference
                // and fails with HostedZoneAlreadyExists. Since we own this zone (we just
                // created it), clean it up ourselves rather than depend on that path.
                // Once cleanup succeeds, physical state matches what it was before this update
                // started (tracked zone missing), so tell the generic rollback walker this
                // resource is restored - otherwise it falls through to "rollback is not
                // implemented" and strands the stack in UPDATE_ROLLBACK_FAILED for a resource
                // there is nothing left to reconcile.
                route53Service.deleteHostedZone(id);
                resource.getAttributes().put(CfnRollback.UPDATE_ROLLBACK_RESTORED_ATTR, "true");
                throw e;
            }
        } else {
            id = resource.getPhysicalId();
            for (VpcAssociation vpc : vpcs) {
                route53Service.associateVpcWithHostedZone(id, vpc, comment);
            }
        }

        resource.setPhysicalId(id);
        resource.getAttributes().put("Id", id);
        resource.getAttributes().put("NameServers", String.join(",", route53Service.getNameServers()));

        List<Map<String, String>> tags = parseTags(resolved.path("HostedZoneTags"));
        if (!tags.isEmpty()) {
            try {
                route53Service.changeTagsForResource("hostedzone", id, tags, List.of());
            } catch (RuntimeException e) {
                if ("true".equals(resource.getAttributes().get(CfnRollback.ROLLBACK_OWNED_ATTR))) {
                    route53Service.deleteHostedZone(id);
                    resource.getAttributes().put(CfnRollback.UPDATE_ROLLBACK_RESTORED_ATTR, "true");
                }
                throw e;
            }
        }
        resource.getAttributes().remove(CfnRollback.ROLLBACK_OWNED_ATTR);
    }

    private boolean zoneExists(String id) {
        try {
            route53Service.getHostedZone(id);
            return true;
        } catch (AwsException e) {
            if (!"NoSuchHostedZone".equals(e.getErrorCode())) {
                throw e;
            }
            return false;
        }
    }

    @Override
    public void delete(String resourceType, String physicalId, String region) {
        route53Service.deleteHostedZone(physicalId);
    }

    private List<VpcAssociation> parseVpcs(JsonNode node) {
        List<VpcAssociation> vpcs = new ArrayList<>();
        if (node.isArray()) {
            for (JsonNode item : node) {
                String id = item.path("VPCId").asText(null);
                String region = item.path("VPCRegion").asText(null);
                if (id == null || id.isBlank() || region == null || region.isBlank()) {
                    // AWS requires both fields on every VPCs entry; silently dropping an
                    // incomplete one would create an unassociated public zone while the
                    // stack still reports CREATE_COMPLETE.
                    throw new IllegalArgumentException(
                            "AWS::Route53::HostedZone VPCs entries require both VPCId and VPCRegion");
                }
                vpcs.add(new VpcAssociation(id, region));
            }
        }
        return vpcs;
    }

    private List<Map<String, String>> parseTags(JsonNode node) {
        List<Map<String, String>> tags = new ArrayList<>();
        if (node.isArray()) {
            for (JsonNode item : node) {
                String key = item.path("Key").asText(null);
                // Matches ProvisionContext.resolveTags: a blank key is skipped rather than
                // persisted, since Route53Service.changeTagsForResource would otherwise write
                // a tag AWS itself would reject for having an empty key.
                if (key != null && !key.isBlank()) {
                    tags.add(Map.of("Key", key, "Value", item.path("Value").asText("")));
                }
            }
        }
        return tags;
    }
}
