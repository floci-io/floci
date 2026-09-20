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
import io.github.hectorvent.floci.services.ec2.model.Tag;
import io.github.hectorvent.floci.services.ec2.model.UserIdGroupPair;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
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
    // Internal, like the other __Floci attributes: the inline rule keys this resource last
    // authorized, so a later update can revoke the ones it dropped without touching rules a
    // standalone SecurityGroupIngress/Egress resource owns.
    static final String INGRESS_RULES_ATTR = "__FlociSgIngressRules";
    static final String EGRESS_RULES_ATTR = "__FlociSgEgressRules";

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

        // Inline rule properties, shared with the standalone SecurityGroupIngress/Egress types in
        // Ec2SecurityGroupRuleCfnProvisioner. Reconcile in both directions: authorize what the
        // group does not already carry, and revoke a rule this resource authorized on a prior run
        // that the template no longer declares. Ownership is scoped by the keys recorded in the
        // internal attributes, so a rule owned by a standalone resource is never revoked here.
        UnaryOperator<String> peerGroupId = peerGroupIdResolver(region, sg.getVpcId());
        reconcileRules(props, "SecurityGroupIngress", sg.getIpPermissions(), engine, peerGroupId, r,
                INGRESS_RULES_ATTR,
                perms -> ec2Service.authorizeSecurityGroupIngress(region, sg.getGroupId(), perms),
                perms -> ec2Service.revokeSecurityGroupIngress(region, sg.getGroupId(), perms));
        reconcileRules(props, "SecurityGroupEgress", sg.getIpPermissionsEgress(), engine, peerGroupId, r,
                EGRESS_RULES_ATTR,
                perms -> ec2Service.authorizeSecurityGroupEgress(region, sg.getGroupId(), perms),
                perms -> ec2Service.revokeSecurityGroupEgress(region, sg.getGroupId(), perms));
        reconcileTags(props, sg.getGroupId(), region, ctx);
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
     * Reconciles one direction's inline rules: authorizes each declared rule the group does not
     * already carry (one call per rule so a reject cannot take its siblings down), and revokes a
     * rule this resource authorized on a prior run that the template no longer declares. The keys
     * this resource declared are recorded in {@code attrKey}, so only its own rules are revoked;
     * a rule a standalone SecurityGroupIngress/Egress resource added is never in that set.
     */
    private void reconcileRules(JsonNode props, String property, List<IpPermission> existing,
                                CloudFormationTemplateEngine engine, UnaryOperator<String> peerGroupId,
                                StackResource r, String attrKey,
                                Consumer<List<IpPermission>> authorize,
                                Consumer<List<IpPermission>> revoke) {
        List<IpPermission> declared = new ArrayList<>();
        if (props != null && props.has(property)) {
            for (JsonNode rule : props.get(property)) {
                declared.add(Ec2SecurityGroupRuleCfnProvisioner.toIpPermission(rule, engine));
            }
        }
        Set<String> declaredKeys = new LinkedHashSet<>();
        for (IpPermission perm : declared) {
            declaredKeys.add(permissionKey(perm, peerGroupId));
        }
        Set<String> present = existing.stream()
                .map(p -> permissionKey(p, peerGroupId))
                .collect(Collectors.toSet());
        for (IpPermission perm : declared) {
            if (present.add(permissionKey(perm, peerGroupId))) {
                authorize.accept(List.of(perm));
            }
        }
        Set<String> priorKeys = parseRuleKeys(r.getAttributes().get(attrKey));
        for (IpPermission existingPermission : existing) {
            String key = permissionKey(existingPermission, peerGroupId);
            if (priorKeys.contains(key) && !declaredKeys.contains(key)) {
                revoke.accept(List.of(existingPermission));
            }
        }
        if (declaredKeys.isEmpty()) {
            r.getAttributes().remove(attrKey);
        } else {
            r.getAttributes().put(attrKey, String.join("\n", declaredKeys));
        }
    }

    private static Set<String> parseRuleKeys(String stored) {
        if (stored == null || stored.isEmpty()) {
            return Set.of();
        }
        return new LinkedHashSet<>(Arrays.asList(stored.split("\n", -1)));
    }

    /**
     * Reconciles the group's tags to the template on create and update: adds or overwrites the
     * declared tags and removes any the template dropped. AWS treats SecurityGroup Tags as an
     * in-place (no interruption) update.
     */
    private void reconcileTags(JsonNode props, String groupId, String region, ProvisionContext ctx) {
        Map<String, String> desired = ctx.resolveTags(props, "Tags");
        Map<String, String> current = new LinkedHashMap<>();
        for (Map<String, String> entry : ec2Service.describeTags(region, Map.of("resource-id", List.of(groupId)))) {
            current.put(entry.get("key"), entry.get("value"));
        }
        List<String> stale = ProvisionContext.staleTagKeys(current, desired);
        if (!stale.isEmpty()) {
            List<Tag> remove = new ArrayList<>();
            for (String key : stale) {
                remove.add(new Tag(key, current.get(key)));
            }
            ec2Service.deleteTags(region, List.of(groupId), remove);
        }
        if (!desired.isEmpty()) {
            List<Tag> add = new ArrayList<>();
            for (Map.Entry<String, String> entry : desired.entrySet()) {
                add.add(new Tag(entry.getKey(), entry.getValue()));
            }
            ec2Service.createTags(region, List.of(groupId), add);
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
