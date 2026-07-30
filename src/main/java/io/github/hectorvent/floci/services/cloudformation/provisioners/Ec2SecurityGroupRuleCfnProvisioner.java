package io.github.hectorvent.floci.services.cloudformation.provisioners;

import com.fasterxml.jackson.databind.JsonNode;
import io.github.hectorvent.floci.services.cloudformation.CloudFormationTemplateEngine;
import io.github.hectorvent.floci.services.cloudformation.model.StackResource;
import io.github.hectorvent.floci.services.ec2.Ec2Service;
import io.github.hectorvent.floci.services.ec2.model.IpPermission;
import io.github.hectorvent.floci.services.ec2.model.IpRange;
import io.github.hectorvent.floci.services.ec2.model.Ipv6Range;
import io.github.hectorvent.floci.services.ec2.model.SecurityGroup;
import io.github.hectorvent.floci.services.ec2.model.SecurityGroupRule;
import io.github.hectorvent.floci.services.ec2.model.UserIdGroupPair;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * CloudFormation provisioning for the standalone security-group rule resources:
 * {@code AWS::EC2::SecurityGroupIngress} and {@code AWS::EC2::SecurityGroupEgress} (issue #1992).
 *
 * <p>The rule-object to {@link IpPermission} mapping lives here as
 * {@link #toIpPermission(JsonNode, CloudFormationTemplateEngine)} because the inline
 * {@code SecurityGroupIngress}/{@code SecurityGroupEgress} properties of
 * {@code AWS::EC2::SecurityGroup} take the same shape. That arm is still in
 * {@code CloudFormationResourceProvisioner} and calls this method, so there is one mapping rather
 * than two; it moves here when the security-group arm itself is extracted.
 */
@ApplicationScoped
public class Ec2SecurityGroupRuleCfnProvisioner implements CfnResourceProvisioner {

    private static final Logger LOG = Logger.getLogger(Ec2SecurityGroupRuleCfnProvisioner.class);

    private static final String INGRESS = "AWS::EC2::SecurityGroupIngress";
    private static final String EGRESS = "AWS::EC2::SecurityGroupEgress";

    private final Ec2Service ec2Service;

    @Inject
    public Ec2SecurityGroupRuleCfnProvisioner(Ec2Service ec2Service) {
        this.ec2Service = ec2Service;
    }

    @Override
    public Set<String> resourceTypes() {
        return Set.of(INGRESS, EGRESS);
    }

    @Override
    public void provision(StackResource r, JsonNode props, ProvisionContext ctx) {
        boolean ingress = switch (r.getResourceType()) {
            case INGRESS -> true;
            case EGRESS -> false;
            default -> throw new IllegalStateException(
                    "Ec2SecurityGroupRuleCfnProvisioner cannot handle " + r.getResourceType());
        };

        String groupId = resolveGroupId(props, ctx);
        IpPermission perm = toIpPermission(props, ctx.engine());

        // UpdateStack re-executes every resource with the physical id it got at create time, and the
        // authorize APIs are append-only. Drop the rule the previous execution created first:
        // otherwise an unchanged rule doubles up on the group, and a changed one leaves its old
        // permission behind. These resources are immutable in CloudFormation — every property change
        // is a replacement — so revoke-then-authorize is the update semantics.
        String previousRuleId = r.getPhysicalId();
        if (previousRuleId != null && previousRuleId.startsWith("sgr-")) {
            ec2Service.deleteSecurityGroupRule(ctx.region(), previousRuleId);
        }

        List<SecurityGroupRule> rules = ingress
                ? ec2Service.authorizeSecurityGroupIngress(ctx.region(), groupId, List.of(perm))
                : ec2Service.authorizeSecurityGroupEgress(ctx.region(), groupId, List.of(perm));
        // Ref and Fn::GetAtt Id both return the rule id, as they do in CloudFormation. The logical
        // id is only a fallback for the (unreachable in practice) case of no rule being recorded —
        // a null physical id would make the stack skip this resource on delete.
        String ruleId = !rules.isEmpty() && rules.get(0).getSecurityGroupRuleId() != null
                ? rules.get(0).getSecurityGroupRuleId()
                : r.getLogicalId();
        r.setPhysicalId(ruleId);
        r.getAttributes().put("Id", ruleId);
    }

    @Override
    public void delete(String resourceType, String physicalId, String region) {
        if (physicalId == null || !physicalId.startsWith("sgr-")) {
            return;
        }
        // A standalone rule can target a group that outlives the stack, so the permission has to be
        // revoked explicitly. Failures are swallowed: on a whole-stack delete the group is usually
        // gone already, and AWS does not fail a stack deletion over an already-absent rule.
        try {
            ec2Service.deleteSecurityGroupRule(region, physicalId);
        } catch (Exception e) {
            LOG.debugv("Error deleting security group rule {0}: {1}", physicalId, e.getMessage());
        }
    }

    /**
     * Maps a CloudFormation rule object — the properties of a standalone
     * SecurityGroupIngress/Egress resource, or one entry of a security group's inline
     * {@code SecurityGroupIngress}/{@code SecurityGroupEgress} list — onto the EC2 model.
     */
    public static IpPermission toIpPermission(JsonNode rule, CloudFormationTemplateEngine engine) {
        IpPermission perm = new IpPermission();
        String protocol = resolve(rule, "IpProtocol", engine);
        perm.setIpProtocol(protocol != null ? protocol : "-1");
        if (rule != null && rule.hasNonNull("FromPort")) {
            perm.setFromPort(rule.get("FromPort").asInt());
        }
        if (rule != null && rule.hasNonNull("ToPort")) {
            perm.setToPort(rule.get("ToPort").asInt());
        }
        // One Description property covers whichever peer the rule names, matching CloudFormation.
        // It reaches the ipv4 and ipv6 ranges; UserIdGroupPair has no description field to put it on.
        String description = resolve(rule, "Description", engine);
        String cidr = resolve(rule, "CidrIp", engine);
        if (cidr != null && !cidr.isBlank()) {
            IpRange range = new IpRange();
            range.setCidrIp(cidr);
            range.setDescription(description);
            perm.getIpRanges().add(range);
        }
        String cidr6 = resolve(rule, "CidrIpv6", engine);
        if (cidr6 != null && !cidr6.isBlank()) {
            Ipv6Range range6 = new Ipv6Range();
            range6.setCidrIpv6(cidr6);
            range6.setDescription(description);
            perm.getIpv6Ranges().add(range6);
        }
        String peerGroup = resolve(rule, "SourceSecurityGroupId", engine);
        if (peerGroup == null || peerGroup.isBlank()) {
            peerGroup = resolve(rule, "DestinationSecurityGroupId", engine);
        }
        if (peerGroup != null && !peerGroup.isBlank()) {
            UserIdGroupPair pair = new UserIdGroupPair();
            pair.setGroupId(peerGroup);
            perm.getUserIdGroupPairs().add(pair);
        }
        return perm;
    }

    /**
     * GroupId is the VPC form. GroupName is the EC2-Classic/default-VPC form: look the group up by
     * name so the rule lands on it, falling back to the raw name so a miss reports what was asked
     * for rather than {@code null}.
     */
    private String resolveGroupId(JsonNode props, ProvisionContext ctx) {
        String groupId = ctx.resolveOptional(props, "GroupId");
        if (groupId != null && !groupId.isBlank()) {
            return groupId;
        }
        String groupName = ctx.resolveOptional(props, "GroupName");
        if (groupName == null || groupName.isBlank()) {
            return null;
        }
        return ec2Service.describeSecurityGroups(ctx.region(), List.of(), List.of(groupName), Map.of())
                .stream()
                .map(SecurityGroup::getGroupId)
                .findFirst()
                .orElse(groupName);
    }

    private static String resolve(JsonNode props, String name, CloudFormationTemplateEngine engine) {
        if (props == null || !props.has(name) || props.get(name).isNull()) {
            return null;
        }
        return engine.resolve(props.get(name));
    }
}
