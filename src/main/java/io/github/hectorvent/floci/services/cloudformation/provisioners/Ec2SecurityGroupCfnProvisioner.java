package io.github.hectorvent.floci.services.cloudformation.provisioners;

import com.fasterxml.jackson.databind.JsonNode;
import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.services.cloudformation.CloudFormationTemplateEngine;
import io.github.hectorvent.floci.services.cloudformation.model.StackResource;
import io.github.hectorvent.floci.services.ec2.Ec2Service;
import io.github.hectorvent.floci.services.ec2.model.IpPermission;
import io.github.hectorvent.floci.services.ec2.model.IpRange;
import io.github.hectorvent.floci.services.ec2.model.Ipv6Range;
import io.github.hectorvent.floci.services.ec2.model.PrefixListId;
import io.github.hectorvent.floci.services.ec2.model.SecurityGroup;
import io.github.hectorvent.floci.services.ec2.model.UserIdGroupPair;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Consumer;
import java.util.function.UnaryOperator;
import java.util.stream.Collectors;

/**
 * CloudFormation provisioning for {@code AWS::EC2::SecurityGroup}. It delegates to
 * {@link Ec2Service} so the group really exists (describe-security-groups, ELBv2 and instance
 * launches resolve it), and sets the physical id to the real group id so Ref and exports resolve to
 * a real {@code sg-} id rather than a stub.
 *
 * <p>The inline {@code SecurityGroupIngress}/{@code SecurityGroupEgress} properties share their
 * rule-object mapping with the standalone rule resources, through
 * {@link Ec2SecurityGroupRuleCfnProvisioner#toIpPermission}.
 */
@ApplicationScoped
public class Ec2SecurityGroupCfnProvisioner implements CfnResourceProvisioner {

    private static final Logger LOG = Logger.getLogger(Ec2SecurityGroupCfnProvisioner.class);

    private static final String SECURITY_GROUP = "AWS::EC2::SecurityGroup";

    private final Ec2Service ec2Service;

    @Inject
    public Ec2SecurityGroupCfnProvisioner(Ec2Service ec2Service) {
        this.ec2Service = ec2Service;
    }

    @Override
    public Set<String> resourceTypes() {
        return Set.of(SECURITY_GROUP);
    }

    @Override
    public void provision(StackResource r, JsonNode props, ProvisionContext ctx) {
        CloudFormationTemplateEngine engine = ctx.engine();
        String region = ctx.region();
        String groupName = ctx.resolveOptional(props, "GroupName");
        if (groupName == null || groupName.isBlank()) {
            groupName = ctx.generatePhysicalName(r.getLogicalId(), 255, false);
        }
        String description = ctx.resolveOptional(props, "GroupDescription");
        if (description == null || description.isBlank()) {
            description = "Managed by CloudFormation";
        }
        String vpcId = ctx.resolveOptional(props, "VpcId");
        // provision() re-runs for every resource on every update. Re-creating an unchanged group
        // would mint a new group id (and collide on the name whenever the VPC id is stable), so
        // reuse the group this resource already points at.
        SecurityGroup reconciled = existingSecurityGroupToReconcile(ctx.priorPhysicalId(), groupName, description,
                vpcId, region);
        final SecurityGroup sg = reconciled != null
                ? reconciled
                : ec2Service.createSecurityGroup(region, groupName, description, vpcId);
        // Ref on AWS::EC2::SecurityGroup returns the group id for VPC security groups.
        r.setPhysicalId(sg.getGroupId());
        r.getAttributes().put("GroupId", sg.getGroupId());
        if (sg.getVpcId() != null) {
            r.getAttributes().put("VpcId", sg.getVpcId());
        }

        // Inline rule properties: previously dropped, leaving the group empty. The mapping is
        // shared with the standalone SecurityGroupIngress/Egress resource types, which live in
        // Ec2SecurityGroupRuleCfnProvisioner.
        // Authorize appends without a duplicate check, so re-running this on a reused group would
        // stack another copy of every inline rule on each update. Only authorize what the group
        // does not already carry.
        //
        // Deliberately additive: a rule dropped from the template is not revoked here. Revoking
        // the difference would mean revoking permissions this resource cannot prove it owns - a
        // group can also carry rules from standalone AWS::EC2::SecurityGroupIngress/Egress
        // resources, and clearing them on an unrelated update would close ports another stack
        // resource is responsible for. Removing a rule the template no longer declares needs the
        // provisioner to record what it authorized; noted as a follow-up.
        UnaryOperator<String> peerGroupId = peerGroupIdResolver(region, sg.getVpcId());
        if (props != null && props.has("SecurityGroupIngress")) {
            authorizeMissing(props.get("SecurityGroupIngress"), sg.getIpPermissions(), engine, peerGroupId,
                    perms -> ec2Service.authorizeSecurityGroupIngress(region, sg.getGroupId(), perms));
        }
        if (props != null && props.has("SecurityGroupEgress")) {
            authorizeMissing(props.get("SecurityGroupEgress"), sg.getIpPermissionsEgress(), engine, peerGroupId,
                    perms -> ec2Service.authorizeSecurityGroupEgress(region, sg.getGroupId(), perms));
        }
    }

    @Override
    public void delete(String resourceType, String physicalId, String region) {
        // Tolerate a group already deleted out of band, so DeleteStack does not fail on it.
        CfnDeletes.safeDelete("security group", physicalId,
                () -> ec2Service.deleteSecurityGroup(region, physicalId),
                "InvalidGroup.NotFound");
    }

    /** The VPC a security group would land in for this template value: the default when omitted. */
    private String effectiveVpcId(String vpcId, String region) {
        return vpcId != null && !vpcId.isEmpty() ? vpcId : String.valueOf(ec2Service.resolveDefaultVpcId(region));
    }

    /**
     * Authorizes each declared rule that the group does not already carry, one call per rule so a
     * rejected rule cannot take its siblings down with it.
     */
    private void authorizeMissing(JsonNode declared, List<IpPermission> existing,
                                  CloudFormationTemplateEngine engine,
                                  UnaryOperator<String> peerGroupId,
                                  Consumer<List<IpPermission>> authorize) {
        Set<String> present = existing.stream()
                .map(p -> permissionKey(p, peerGroupId))
                .collect(Collectors.toSet());
        for (JsonNode rule : declared) {
            IpPermission perm = Ec2SecurityGroupRuleCfnProvisioner.toIpPermission(rule, engine);
            if (present.add(permissionKey(perm, peerGroupId))) {
                authorize.accept(List.of(perm));
            }
        }
    }

    /**
     * Resolves a peer group's name to its id, the same lookup {@code Ec2Service} performs when it
     * stores an authorized rule. Group names are unique per VPC rather than per region, so the
     * search is confined to the group being authorized. A name matching nothing there stays a
     * name, which is also what the service does.
     */
    private UnaryOperator<String> peerGroupIdResolver(String region, String vpcId) {
        return groupName -> ec2Service.describeSecurityGroups(region, List.of(), List.of(groupName), Map.of())
                .stream()
                .filter(peer -> Objects.equals(vpcId, peer.getVpcId()))
                .map(SecurityGroup::getGroupId)
                .filter(Objects::nonNull)
                .findFirst()
                .orElse(groupName);
    }

    /**
     * Identity of a permission for duplicate detection. {@link IpPermission} and the range types
     * it holds define no {@code equals}, so compare a canonical rendering instead. Descriptions
     * are left out: AWS treats a rule differing only by description as the same rule.
     */
    private static String permissionKey(IpPermission p, UnaryOperator<String> peerGroupId) {
        return String.join("|",
                String.valueOf(p.getIpProtocol()),
                String.valueOf(p.getFromPort()),
                String.valueOf(p.getToPort()),
                p.getIpRanges().stream().map(IpRange::getCidrIp).filter(Objects::nonNull).sorted()
                        .collect(Collectors.joining(",")),
                p.getIpv6Ranges().stream().map(Ipv6Range::getCidrIpv6).filter(Objects::nonNull).sorted()
                        .collect(Collectors.joining(",")),
                p.getUserIdGroupPairs().stream()
                        .map(g -> peerIdentity(g, peerGroupId))
                        .filter(Objects::nonNull).sorted()
                        .collect(Collectors.joining(",")),
                p.getPrefixListIds().stream().map(PrefixListId::getPrefixListId)
                        .filter(Objects::nonNull).sorted()
                        .collect(Collectors.joining(",")));
    }

    /**
     * How a peer group is identified when two permissions are compared: its id whenever one can be
     * had. A stored pair already carries one, because authorize resolves the name as it records the
     * rule, while a pair straight from the template carries only the name it was declared with.
     * Keying a resolved id against an unresolved name never matches, which re-authorized a rule
     * naming its peer through {@code SourceSecurityGroupName} on every single update.
     */
    private static String peerIdentity(UserIdGroupPair pair, UnaryOperator<String> peerGroupId) {
        if (pair.getGroupId() != null) {
            return pair.getGroupId();
        }
        return pair.getGroupName() == null ? null : peerGroupId.apply(pair.getGroupName());
    }

    /**
     * The security group this stack resource already points at, when an UpdateStack re-invocation
     * left it unchanged. Unlike most resources the physical id here is the group <em>id</em>, not
     * the name, so the rename check compares the stored group's name against the template's.
     *
     * <p>Returns {@code null} for a fresh create, a group deleted out of band, or any change AWS
     * treats as a replacement: GroupName, GroupDescription and VpcId are all immutable on a
     * security group, so a template that changes one wants a new group, not an edit to this one.
     * The caller then creates.
     */
    private SecurityGroup existingSecurityGroupToReconcile(String priorPhysicalId, String groupName,
                                                           String description, String vpcId, String region) {
        if (priorPhysicalId == null || priorPhysicalId.isBlank()) {
            return null;
        }
        try {
            return ec2Service.describeSecurityGroups(region, List.of(priorPhysicalId), List.of(), Map.of())
                    .stream()
                    .filter(existing -> groupName == null || groupName.equals(existing.getGroupName()))
                    .filter(existing -> description == null || description.equals(existing.getDescription()))
                    // Compare the VpcId the template would actually get, not the raw property.
                    // createSecurityGroup resolves an omitted VpcId to the region's default VPC,
                    // so the stored group always has one: comparing against a null property would
                    // either force a replacement on every update, or - the bug - let a template
                    // that drops VpcId keep a group sitting in the explicit VPC it named before.
                    .filter(existing -> effectiveVpcId(vpcId, region).equals(existing.getVpcId()))
                    .findFirst()
                    .orElse(null);
        } catch (AwsException notFound) {
            // Expected when the group was deleted out of band since the prior update.
            LOG.debugv(notFound, "No existing security group {0} found on file, falling back to create",
                    priorPhysicalId);
            return null;
        }
    }
}
