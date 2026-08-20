package io.github.hectorvent.floci.services.cloudformation.provisioners;

import com.fasterxml.jackson.databind.JsonNode;
import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.services.cloudformation.model.StackResource;
import io.github.hectorvent.floci.services.ec2.Ec2Service;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * CloudFormation provisioning for the EC2 network ACL family: {@code AWS::EC2::NetworkAcl},
 * {@code AWS::EC2::NetworkAclEntry} and {@code AWS::EC2::SubnetNetworkAclAssociation}.
 * All scalar entry properties (including {@code RuleNumber}, {@code Egress} and the
 * {@code PortRange} bounds) resolve through the template engine, so intrinsics like
 * {@code Ref}/{@code Fn::Sub} in any of them work.
 */
@ApplicationScoped
public class Ec2NetworkAclCfnProvisioner implements CfnResourceProvisioner {

    private final Ec2Service ec2Service;

    @Inject
    public Ec2NetworkAclCfnProvisioner(Ec2Service ec2Service) {
        this.ec2Service = ec2Service;
    }

    @Override
    public Set<String> resourceTypes() {
        return Set.of("AWS::EC2::NetworkAcl", "AWS::EC2::NetworkAclEntry",
                "AWS::EC2::SubnetNetworkAclAssociation");
    }

    @Override
    public void provision(StackResource r, JsonNode props, ProvisionContext ctx) {
        switch (r.getResourceType()) {
            case "AWS::EC2::NetworkAcl" -> provisionNetworkAcl(r, props, ctx);
            case "AWS::EC2::NetworkAclEntry" -> provisionNetworkAclEntry(r, props, ctx);
            case "AWS::EC2::SubnetNetworkAclAssociation" -> provisionSubnetNetworkAclAssociation(r, props, ctx);
            default -> throw new IllegalStateException(
                    "Ec2NetworkAclCfnProvisioner cannot handle " + r.getResourceType());
        }
    }

    @Override
    public void delete(String resourceType, String physicalId, String region) {
        try {
            switch (resourceType) {
                case "AWS::EC2::NetworkAcl" -> ec2Service.deleteNetworkAcl(region, physicalId);
                case "AWS::EC2::NetworkAclEntry" -> deleteNetworkAclEntry(physicalId, region);
                case "AWS::EC2::SubnetNetworkAclAssociation" -> deleteSubnetNetworkAclAssociation(physicalId, region);
                default -> { }
            }
        } catch (AwsException e) {
            // Already gone (or already moved back): deleting a deleted resource is not an error.
            if (!e.getErrorCode().contains("NotFound")) {
                throw e;
            }
        }
    }

    private void provisionNetworkAcl(StackResource r, JsonNode props, ProvisionContext ctx) {
        String vpcId = ctx.resolveOptional(props, "VpcId");
        // An update re-invokes provision with the prior physical id. Creating unconditionally
        // would mint a second ACL and orphan the first permanently, so reuse the one this stack
        // already made; only create when it is absent, meaning a first execution or one removed
        // out of band.
        String existingId = r.getPhysicalId();
        var existing = existingId == null || existingId.isBlank() ? null
                : ec2Service.describeNetworkAcls(ctx.region(), List.of(existingId), Map.of())
                        .stream().findFirst().orElse(null);
        if (existing != null) {
            // VpcId is createOnly, so a change is a replacement. Report it rather than leave the
            // ACL silently attached to the original VPC, matching how EcsCapacityCfnProvisioner
            // treats Name.
            if (vpcId != null && !vpcId.equals(existing.getVpcId())) {
                throw new AwsException("ValidationError",
                        "Updating VpcId requires resource replacement, which is not supported.", 400);
            }
            r.getAttributes().put("Id", existingId);
            return;
        }
        var acl = ec2Service.createNetworkAcl(ctx.region(), vpcId);
        r.setPhysicalId(acl.getNetworkAclId());
        r.getAttributes().put("Id", acl.getNetworkAclId());
    }

    private void provisionNetworkAclEntry(StackResource r, JsonNode props, ProvisionContext ctx) {
        String aclId = ctx.resolveOptional(props, "NetworkAclId");
        String ruleNumberValue = ctx.resolveOptional(props, "RuleNumber");
        int ruleNumber = ruleNumberValue != null ? Integer.parseInt(ruleNumberValue) : 100;
        boolean egress = Boolean.parseBoolean(ctx.resolveOptional(props, "Egress"));
        JsonNode portRange = props != null && props.hasNonNull("PortRange") ? props.get("PortRange") : null;
        String fromValue = ctx.resolveOptional(portRange, "From");
        String toValue = ctx.resolveOptional(portRange, "To");
        Integer from = fromValue != null ? Integer.valueOf(fromValue) : null;
        Integer to = toValue != null ? Integer.valueOf(toValue) : null;
        String protocol = ctx.resolveOptional(props, "Protocol");
        ec2Service.createNetworkAclEntry(ctx.region(), aclId, ruleNumber,
                protocol != null ? protocol : "-1",
                ctx.resolveOptional(props, "RuleAction"),
                egress,
                ctx.resolveOptional(props, "CidrBlock"),
                // RuleNumber, Egress and NetworkAclId are the createOnly key, so re-provisioning
                // the same key is an update. replace=false would raise NetworkAclEntryAlreadyExists
                // on every stack update.
                from, to, true);
        r.setPhysicalId(aclId + "|" + ruleNumber + "|" + (egress ? "egress" : "ingress"));
    }

    private void provisionSubnetNetworkAclAssociation(StackResource r, JsonNode props, ProvisionContext ctx) {
        String subnetId = ctx.resolveOptional(props, "SubnetId");
        String aclId = ctx.resolveOptional(props, "NetworkAclId");
        // CFN semantics: move the subnet from its current (default) ACL onto the
        // given one. Find the subnet's live association, then replace it.
        String associationId = ec2Service.describeNetworkAcls(ctx.region(), List.of(), Map.of()).stream()
                .flatMap(acl -> acl.getAssociations().stream())
                .filter(a -> subnetId != null && subnetId.equals(a.getSubnetId()))
                .map(a -> a.getNetworkAclAssociationId())
                .findFirst()
                .orElse(null);
        if (associationId == null) {
            throw new IllegalStateException(
                    "No network ACL association found for subnet " + subnetId);
        }
        var assoc = ec2Service.replaceNetworkAclAssociation(ctx.region(), associationId, aclId);
        r.setPhysicalId(assoc.getNetworkAclAssociationId());
        r.getAttributes().put("AssociationId", assoc.getNetworkAclAssociationId());
    }

    /** Physical id format: {@code <aclId>|<ruleNumber>|<egress|ingress>} (set in provision). */
    private void deleteNetworkAclEntry(String physicalId, String region) {
        String[] parts = physicalId != null ? physicalId.split("\\|") : new String[0];
        if (parts.length != 3) {
            return;
        }
        ec2Service.deleteNetworkAclEntry(region, parts[0], Integer.parseInt(parts[1]),
                "egress".equals(parts[2]));
    }

    /**
     * CFN semantics for deleting a SubnetNetworkAclAssociation: the subnet reverts to its
     * VPC's default network ACL. Locate the ACL currently holding the association, then
     * replace the association with the default ACL of the same VPC.
     */
    private void deleteSubnetNetworkAclAssociation(String physicalId, String region) {
        var holder = ec2Service.describeNetworkAcls(region, List.of(),
                        Map.of("association.network-acl-association-id", List.of(physicalId)))
                .stream().findFirst().orElse(null);
        if (holder == null) {
            return; // association already gone
        }
        if (holder.isDefault()) {
            return; // already on the default ACL, nothing to revert
        }
        var defaultAcl = ec2Service.describeNetworkAcls(region, List.of(),
                        Map.of("vpc-id", List.of(holder.getVpcId()), "default", List.of("true")))
                .stream().findFirst().orElse(null);
        if (defaultAcl == null) {
            return;
        }
        ec2Service.replaceNetworkAclAssociation(region, physicalId, defaultAcl.getNetworkAclId());
    }
}
