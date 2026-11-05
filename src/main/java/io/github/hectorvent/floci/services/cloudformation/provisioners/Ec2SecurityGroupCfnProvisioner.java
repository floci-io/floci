package io.github.hectorvent.floci.services.cloudformation.provisioners;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
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

import java.util.ArrayList;
import java.util.Arrays;
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
 *
 * <p>An update that keeps the group records what it is about to change (the rules it authorizes
 * and revokes, the tag keys it touches, and the ownership records it replaces) in
 * {@link #UPDATE_SNAPSHOT_ATTR}, so a stack update that fails on a later resource puts the group
 * back. A replacement is not rolled back here, so the stack reports it as not rolled back.
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
    /** The tag keys the last deployment wrote, as {@link Ec2Tags#recordKeys} records them. */
    static final String TAG_KEYS_ATTR = "__FlociSgTagKeys";
    /** What an update that kept the group changed on it, for {@link #rollbackUpdate} to undo. */
    static final String UPDATE_SNAPSHOT_ATTR = "__FlociSgUpdateSnapshot";
    private static final ObjectMapper MAPPER = new ObjectMapper();

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
        Map<String, String> desiredTags = ctx.resolveTags(props, "Tags");
        Ec2Tags.validatedTags(desiredTags);
        CloudFormationTemplateEngine engine = ctx.engine();
        String region = ctx.region();
        String groupName = ctx.resolveOptional(props, "GroupName");
        if (groupName == null || groupName.isBlank()) {
            groupName = null;
        }
        String description = ctx.resolveOptional(props, "GroupDescription");
        if (description == null || description.isBlank()) {
            description = "Managed by CloudFormation";
        }
        String vpcId = ctx.resolveOptional(props, "VpcId");
        // provision() re-runs for every resource on every update. Re-creating an unchanged group
        // would mint a new group id (and collide on the name whenever the VPC id is stable), so
        // reuse the group this resource already points at.
        // An omitted name keeps the generated name, unless this update removed an explicit one.
        SecurityGroup reconciled = groupName == null && ctx.previouslyDeclared("GroupName") ? null
                : existingSecurityGroupToReconcile(ctx.priorPhysicalId(), groupName, description, vpcId, region);
        if (reconciled == null && groupName == null) {
            groupName = ctx.generatePhysicalName(r.getLogicalId(), 255, false);
        }
        final SecurityGroup sg = reconciled != null
                ? reconciled
                : ec2Service.createSecurityGroup(region, groupName, description, vpcId);
        // Ref on AWS::EC2::SecurityGroup returns the group id for VPC security groups.
        r.setPhysicalId(sg.getGroupId());
        r.getAttributes().put("GroupId", sg.getGroupId());
        // Id is the schema's primary identifier and equals the group id, the same value as GroupId.
        r.getAttributes().put("Id", sg.getGroupId());
        if (sg.getVpcId() != null) {
            r.getAttributes().put("VpcId", sg.getVpcId());
        }

        // Inline rule properties, shared with the standalone SecurityGroupIngress/Egress types in
        // Ec2SecurityGroupRuleCfnProvisioner. Reconcile in both directions: authorize what the
        // group does not already carry, and revoke a rule this resource authorized on a prior run
        // that the template no longer declares. Ownership is scoped by the keys recorded in the
        // internal attributes, so a rule owned by a standalone resource is never revoked here.
        UnaryOperator<String> peerGroupId = peerGroupIdResolver(region, sg.getVpcId());
        RulePlan ingress = planRules(props, "SecurityGroupIngress", sg.getIpPermissions(), engine, peerGroupId,
                r.getAttributes().get(INGRESS_RULES_ATTR));
        RulePlan egress = planRules(props, "SecurityGroupEgress", sg.getIpPermissionsEgress(), engine, peerGroupId,
                r.getAttributes().get(EGRESS_RULES_ATTR));
        // AWS updates Tags in place and keeps a tag added outside the stack, so a reused group has
        // only the keys the last deployment wrote removed; a new group carries no tags yet.
        boolean reused = reconciled != null;
        Set<String> ownedTags = reused ? Ec2Tags.ownedKeys(r, TAG_KEYS_ATTR, ctx) : Set.of();
        String groupId = sg.getGroupId();
        CfnRollback.guarded(r, ctx, !reused, () -> delete(SECURITY_GROUP, groupId, region), () -> undoInPlace(r), () -> {
            if (reused && ctx.isUpdate()) {
                snapshotInPlace(r, region, groupId, ingress, egress, ownedTags, desiredTags);
            }
            for (IpPermission perm : ingress.authorize()) {
                ec2Service.authorizeSecurityGroupIngress(region, groupId, List.of(perm));
            }
            for (IpPermission perm : ingress.revoke()) {
                ec2Service.revokeSecurityGroupIngress(region, groupId, List.of(perm));
            }
            for (IpPermission perm : egress.authorize()) {
                ec2Service.authorizeSecurityGroupEgress(region, groupId, List.of(perm));
            }
            for (IpPermission perm : egress.revoke()) {
                ec2Service.revokeSecurityGroupEgress(region, groupId, List.of(perm));
            }
            Ec2Tags.reconcileOwned(ec2Service, region, groupId, ownedTags, desiredTags);
        });
        putRuleKeys(r, INGRESS_RULES_ATTR, ingress.declaredKeys());
        putRuleKeys(r, EGRESS_RULES_ATTR, egress.declaredKeys());
        Ec2Tags.recordKeys(r, TAG_KEYS_ATTR, desiredTags);
    }

    @Override
    public UpdateCleanupResult completeUpdate(StackResource resource) {
        resource.getAttributes().remove(UPDATE_SNAPSHOT_ATTR);
        return UpdateCleanupResult.notApplicable();
    }

    @Override
    public void clearUpdate(StackResource resource) {
        resource.getAttributes().remove(UPDATE_SNAPSHOT_ATTR);
    }

    /**
     * Undoes an update that kept the group, from the snapshot it recorded. Without one the update
     * replaced the group, which is not rolled back here.
     */
    @Override
    public boolean rollbackUpdate(StackResource resource) {
        if (resource.getAttributes().get(UPDATE_SNAPSHOT_ATTR) == null) {
            return false;
        }
        undoInPlace(resource);
        return true;
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

    /** What one direction's reconcile authorizes and revokes, and the rule keys it then records. */
    private record RulePlan(List<IpPermission> authorize, List<IpPermission> revoke, Set<String> declaredKeys) {
    }

    /**
     * Plans one direction's inline rules: authorizes each declared rule whose source the group does
     * not already carry (one call per rule so a reject cannot take its siblings down), and revokes a rule this
     * resource authorized on a prior run that the template no longer declares. The keys this
     * resource declared were recorded in {@code storedKeys}, so only its own rules are revoked; a
     * rule a standalone SecurityGroupIngress/Egress resource added is never in that set.
     */
    private static RulePlan planRules(JsonNode props, String property, List<IpPermission> existing,
                                      CloudFormationTemplateEngine engine, UnaryOperator<String> peerGroupId,
                                      String storedKeys) {
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
        // Compared source by source: a declared rule names one source, while a stored permission can
        // hold several, and a revoke removes its source from every permission that carries it. A
        // source the group already holds is left alone, so neither a later revoke nor a rollback
        // of this update can take it from the permission that held it first.
        Set<String> present = sourceKeys(existing, peerGroupId);
        List<IpPermission> authorize = new ArrayList<>();
        for (IpPermission perm : declared) {
            if (present.add(permissionKey(perm, peerGroupId))) {
                authorize.add(perm);
            }
        }
        Set<String> priorKeys = parseRuleKeys(storedKeys);
        List<IpPermission> revoke = new ArrayList<>();
        for (IpPermission existingPermission : existing) {
            String key = permissionKey(existingPermission, peerGroupId);
            if (priorKeys.contains(key) && !declaredKeys.contains(key)) {
                revoke.add(existingPermission);
            }
        }
        return new RulePlan(authorize, revoke, declaredKeys);
    }

    private static void putRuleKeys(StackResource r, String attrKey, Set<String> keys) {
        if (keys.isEmpty()) {
            r.getAttributes().remove(attrKey);
        } else {
            r.getAttributes().put(attrKey, String.join("\n", keys));
        }
    }

    /**
     * Records what an update is about to change on a reused group before it changes it: each
     * direction's rules to authorize and revoke with the rule-key record it replaces, and the tag
     * keys it touches with their values and the tag-key record. Written on every update that keeps
     * the group, so a rollback can tell an in-place update from a replacement.
     */
    private void snapshotInPlace(StackResource r, String region, String groupId, RulePlan ingress, RulePlan egress,
                                 Set<String> ownedTags, Map<String, String> desiredTags) {
        ObjectNode prior = MAPPER.createObjectNode();
        prior.put("region", region);
        prior.put("groupId", groupId);
        snapshotRules(prior.putObject("ingress"), ingress, r.getAttributes().get(INGRESS_RULES_ATTR));
        snapshotRules(prior.putObject("egress"), egress, r.getAttributes().get(EGRESS_RULES_ATTR));
        Ec2Tags.snapshotOwned(prior, ownedTags, desiredTags, Ec2Tags.current(ec2Service, region, groupId),
                r.getAttributes().get(TAG_KEYS_ATTR));
        r.getAttributes().put(UPDATE_SNAPSHOT_ATTR, prior.toString());
    }

    private static void snapshotRules(ObjectNode direction, RulePlan plan, String storedKeys) {
        direction.put("ruleKeys", storedKeys);
        ArrayNode authorized = direction.putArray("authorized");
        plan.authorize().forEach(perm -> authorized.add(MAPPER.valueToTree(perm)));
        ArrayNode revoked = direction.putArray("revoked");
        plan.revoke().forEach(perm -> revoked.add(MAPPER.valueToTree(perm)));
    }

    /**
     * Puts a reused group back from its snapshot: revokes the rules the update authorized,
     * authorizes again the ones it revoked that the group no longer carries (EC2 here does not
     * reject a duplicate), and restores the tag keys it touched and every ownership record. The
     * snapshot is removed only after the restore succeeds, so a failed one keeps it for a
     * ContinueUpdateRollback retry, which repeats it safely.
     */
    private void undoInPlace(StackResource r) {
        JsonNode prior = CfnRollback.updateSnapshot(r, UPDATE_SNAPSHOT_ATTR);
        if (prior == null) {
            return;
        }
        String region = prior.path("region").asText();
        String groupId = prior.path("groupId").asText();
        SecurityGroup current = ec2Service.describeSecurityGroups(region, List.of(groupId), List.of(), Map.of())
                .getFirst();
        UnaryOperator<String> peerGroupId = peerGroupIdResolver(region, current.getVpcId());
        JsonNode ingress = prior.path("ingress");
        for (IpPermission perm : permissions(ingress.path("authorized"))) {
            ec2Service.revokeSecurityGroupIngress(region, groupId, List.of(perm));
        }
        for (IpPermission perm : missing(permissions(ingress.path("revoked")), current.getIpPermissions(), peerGroupId)) {
            ec2Service.authorizeSecurityGroupIngress(region, groupId, List.of(perm));
        }
        JsonNode egress = prior.path("egress");
        for (IpPermission perm : permissions(egress.path("authorized"))) {
            ec2Service.revokeSecurityGroupEgress(region, groupId, List.of(perm));
        }
        for (IpPermission perm : missing(permissions(egress.path("revoked")), current.getIpPermissionsEgress(),
                peerGroupId)) {
            ec2Service.authorizeSecurityGroupEgress(region, groupId, List.of(perm));
        }
        restoreRuleKeys(r, INGRESS_RULES_ATTR, ingress);
        restoreRuleKeys(r, EGRESS_RULES_ATTR, egress);
        Ec2Tags.restoreOwned(ec2Service, region, groupId, r, TAG_KEYS_ATTR, prior);
        r.getAttributes().remove(UPDATE_SNAPSHOT_ATTR);
    }

    private static List<IpPermission> permissions(JsonNode saved) {
        List<IpPermission> permissions = new ArrayList<>();
        try {
            for (JsonNode perm : saved) {
                permissions.add(MAPPER.treeToValue(perm, IpPermission.class));
            }
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Unreadable security group rule in the update snapshot", e);
        }
        return permissions;
    }

    /**
     * The saved rules with a source the group does not carry now, so restoring them never adds a
     * duplicate source.
     */
    private static List<IpPermission> missing(List<IpPermission> saved, List<IpPermission> current,
                                              UnaryOperator<String> peerGroupId) {
        Set<String> present = sourceKeys(current, peerGroupId);
        return saved.stream()
                .filter(perm -> !present.containsAll(sourceKeys(List.of(perm), peerGroupId)))
                .toList();
    }

    /**
     * One {@link #permissionKey} per source the permissions hold, each as if its source stood alone
     * in its own permission, the shape an inline rule has. A permission with no source keeps its own
     * key.
     */
    private static Set<String> sourceKeys(List<IpPermission> permissions, UnaryOperator<String> peerGroupId) {
        Set<String> keys = new LinkedHashSet<>();
        for (IpPermission permission : permissions) {
            List<IpPermission> sources = new ArrayList<>();
            permission.getIpRanges().forEach(range -> sources.add(withSource(permission, single -> single
                    .setIpRanges(List.of(range)))));
            permission.getIpv6Ranges().forEach(range -> sources.add(withSource(permission, single -> single
                    .setIpv6Ranges(List.of(range)))));
            permission.getUserIdGroupPairs().forEach(pair -> sources.add(withSource(permission, single -> single
                    .setUserIdGroupPairs(List.of(pair)))));
            permission.getPrefixListIds().forEach(list -> sources.add(withSource(permission, single -> single
                    .setPrefixListIds(List.of(list)))));
            if (sources.isEmpty()) {
                sources.add(permission);
            }
            sources.forEach(single -> keys.add(permissionKey(single, peerGroupId)));
        }
        return keys;
    }

    private static IpPermission withSource(IpPermission permission, Consumer<IpPermission> source) {
        IpPermission single = new IpPermission();
        single.setIpProtocol(permission.getIpProtocol());
        single.setFromPort(permission.getFromPort());
        single.setToPort(permission.getToPort());
        source.accept(single);
        return single;
    }

    private static void restoreRuleKeys(StackResource r, String attrKey, JsonNode direction) {
        String keys = CfnRollback.snapshotText(direction, "ruleKeys");
        if (keys == null) {
            r.getAttributes().remove(attrKey);
        } else {
            r.getAttributes().put(attrKey, keys);
        }
    }

    private static Set<String> parseRuleKeys(String stored) {
        if (stored == null || stored.isEmpty()) {
            return Set.of();
        }
        return new LinkedHashSet<>(Arrays.asList(stored.split("\n", -1)));
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
