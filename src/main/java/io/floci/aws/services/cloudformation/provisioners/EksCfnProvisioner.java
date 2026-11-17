package io.floci.aws.services.cloudformation.provisioners;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.floci.aws.core.common.AwsException;
import io.floci.aws.core.common.ReservedTags;
import io.floci.aws.services.cloudformation.model.StackResource;
import io.floci.aws.services.eks.EksService;
import io.floci.aws.services.eks.model.Cluster;
import io.floci.aws.services.eks.model.CreateClusterRequest;
import io.floci.aws.services.eks.model.Nodegroup;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.function.Supplier;

/**
 * CloudFormation provisioning for the EKS control-plane types. {@code AWS::EKS::Cluster} and
 * {@code AWS::EKS::Nodegroup} share one provisioner over {@link EksService} because a nodegroup
 * belongs to a cluster and its delete needs the cluster name, which is stored as a create-time
 * attribute. {@code Ref} returns the cluster name for a Cluster and the nodegroup name for a
 * Nodegroup, matching what the legacy switch published.
 */
@ApplicationScoped
public class EksCfnProvisioner implements CfnResourceProvisioner {

    private static final Logger LOG = Logger.getLogger(EksCfnProvisioner.class);
    private static final String CLUSTER = "AWS::EKS::Cluster";
    private static final String NODEGROUP = "AWS::EKS::Nodegroup";

    /** Every attribute {@code publish} writes for a cluster. */
    private static final List<String> CLUSTER_ATTRIBUTES =
            List.of("Arn", "Endpoint", "CertificateAuthorityData", "OpenIdConnectIssuerUrl");
    private static final ObjectMapper MAPPER = new ObjectMapper();
    /** Every attribute {@code publishNodegroup} writes for a nodegroup. */
    private static final List<String> NODEGROUP_ATTRIBUTES = List.of("Id", "ClusterName", "NodegroupName", "Arn");
    /**
     * Nodegroup properties EksService cannot update (it has no UpdateNodegroupConfig or
     * UpdateNodegroupVersion), so a change to one is accepted with a warning and not applied.
     */
    private static final List<String> UNSUPPORTED_NODEGROUP_UPDATES = List.of("ScalingConfig", "Labels", "Taints",
            "UpdateConfig", "Version", "ReleaseVersion", "ForceUpdateEnabled", "LaunchTemplate",
            "NodeRepairConfig", "WarmPoolConfig");
    private static final String SNAPSHOT_ATTR = CfnRollback.EKS_CLUSTER_UPDATE_SNAPSHOT_ATTR;
    private static final String NODEGROUP_SNAPSHOT_ATTR = CfnRollback.EKS_NODEGROUP_UPDATE_SNAPSHOT_ATTR;
    // The tag keys the last deployment resolved, reserved ones included, as Ec2Tags.recordKeys records
    // them, so an update removes only those and keeps a tag added outside the stack.
    private static final String TAG_KEYS_ATTR = "__FlociEksClusterTagKeys";
    private static final String NODEGROUP_TAG_KEYS_ATTR = "__FlociEksNodegroupTagKeys";

    private final EksService eksService;

    @Inject
    public EksCfnProvisioner(EksService eksService) {
        this.eksService = eksService;
    }

    @Override
    public Set<String> resourceTypes() {
        return Set.of(CLUSTER, NODEGROUP);
    }

    @Override
    public void provision(StackResource r, JsonNode props, ProvisionContext ctx) {
        switch (r.getResourceType()) {
            case CLUSTER -> provisionCluster(r, props, ctx);
            case NODEGROUP -> provisionNodegroup(r, props, ctx);
            default -> throw new IllegalStateException("Unhandled type: " + r.getResourceType());
        }
    }

    private void provisionCluster(StackResource r, JsonNode props, ProvisionContext ctx) {
        // A snapshot describes the update in flight; one an earlier update left behind is stale.
        r.getAttributes().remove(SNAPSHOT_ATTR);
        if (ctx.isUpdate()) {
            // Rolling this update back undoes it alone; only ContinueUpdateRollback retries the
            // delete the last rollback could not make.
            ReplacementCleanup.endRollbackRetry(r);
        }
        Map<String, String> attributesBefore = new HashMap<>(r.getAttributes());
        // A stack from before this type had a provisioner holds the dispatcher's stub, which names
        // no cluster, so the cluster is created as on a first deploy.
        boolean updates = ctx.isUpdate() && !CfnResourceDispatcher.isStub(ctx.priorPhysicalId(), r.getAttributes());
        // Name, RoleArn, EncryptionConfig, BootstrapSelfManagedAddons, OutpostConfig, the network
        // family and CIDR and the bootstrap admin permission are create-only in the registry: a change
        // to one replaces the cluster.
        boolean replaces = updates && ctx.changes(CLUSTER, props).requiresReplacement();
        String name = ctx.resolveOptional(props, "Name");
        if (name == null || name.isBlank()) {
            // The name is the physical id, so an update that replaces nothing keeps it rather than
            // creating a second cluster and orphaning the first.
            name = updates && !replaces ? ctx.priorPhysicalId()
                    : ctx.generatePhysicalName(r.getLogicalId(), 100, false);
        }
        if (replaces && ctx.reusesPriorEntity(name)) {
            throw CfnExceptions.customNamedReplacement(name);
        }
        Cluster live = updates && !replaces ? liveCluster(ctx.priorPhysicalId()) : null;
        if (live != null) {
            updateInPlace(r, props, ctx, live);
            return;
        }
        CreateClusterRequest request = new CreateClusterRequest();
        request.setName(name);
        request.setVersion(ctx.resolveOptional(props, "Version"));
        request.setRoleArn(ctx.resolveOptional(props, "RoleArn"));
        // Unstripped: createCluster reads the reserved cluster-args tag, then drops it.
        Map<String, String> templateTags = ctx.resolveTags(props, "Tags");
        request.setTags(templateTags);
        Cluster created = eksService.createCluster(request);
        // The cluster this resource named before is gone or replaced; publish writes only what the
        // new one has, so its endpoint and certificate must not outlive it.
        CLUSTER_ATTRIBUTES.forEach(r.getAttributes()::remove);
        publish(r, created);
        Ec2Tags.recordKeys(r, TAG_KEYS_ATTR, templateTags);
        if (updates && !replaces) {
            // The cluster the update started from is gone, so this one is the update's to undo.
            CfnRollback.recordCreatedByUpdate(r, ctx, SNAPSHOT_ATTR);
        }
        if (ctx.isUpdate()) {
            // The cluster the prior physical id named is deleted once the update commits, and the
            // new one is removed if the update rolls back.
            ReplacementCleanup.record(r, ctx, attributesBefore, replaces);
        }
    }

    private void publish(StackResource r, Cluster cluster) {
        r.setPhysicalId(cluster.getName());
        r.getAttributes().put("Arn", cluster.getArn());
        if (cluster.getEndpoint() != null) {
            r.getAttributes().put("Endpoint", cluster.getEndpoint());
        }
        if (cluster.getCertificateAuthority() != null && cluster.getCertificateAuthority().getData() != null) {
            r.getAttributes().put("CertificateAuthorityData", cluster.getCertificateAuthority().getData());
        }
        if (cluster.getIdentity() != null && cluster.getIdentity().getOidc() != null
                && cluster.getIdentity().getOidc().getIssuer() != null) {
            r.getAttributes().put("OpenIdConnectIssuerUrl", cluster.getIdentity().getOidc().getIssuer());
        }
    }

    /** The cluster an update starts from, or null when it is gone and the update creates one. */
    private Cluster liveCluster(String name) {
        return orNullWhenGone(() -> eksService.describeCluster(name));
    }

    /** What {@code lookup} returns, or null when it reports the entity gone; any other failure is thrown. */
    private static <T> T orNullWhenGone(Supplier<T> lookup) {
        try {
            return lookup.get();
        } catch (AwsException e) {
            if ("ResourceNotFoundException".equals(e.getErrorCode())) {
                return null;
            }
            throw e;
        }
    }

    /**
     * The update AWS makes for the properties it does not replace on: the cluster keeps its name.
     * EksService has no UpdateClusterVersion or UpdateClusterConfig, so the one mutable property
     * floci honors is Tags, and a changed Version is accepted with a warning and not applied.
     */
    private void updateInPlace(StackResource r, JsonNode props, ProvisionContext ctx, Cluster live) {
        JsonNode priorProps = ctx.priorProperties();
        String version = ctx.resolveOptional(props, "Version");
        String priorVersion = ctx.resolveOptional(priorProps, "Version");
        boolean versionChanged = version != null && (priorVersion == null || !version.trim().equals(priorVersion.trim()));
        if (version != null && !version.isBlank() && versionChanged) {
            LOG.warnv("EKS cluster {0}: in-place modification is not supported, the change to Version is ignored and"
                    + " the cluster keeps version {1}", live.getName(), live.getVersion());
        }
        updateTagsInPlace(r, props, ctx, live.getArn(), live.getTags(), live.getName(), TAG_KEYS_ATTR, SNAPSHOT_ATTR,
                () -> publish(r, live), true);
    }

    /**
     * The tag half of an in-place update, shared by cluster and nodegroup: drives the tags the stack
     * owns to the template's, snapshots them for the rollback and publishes the live entity.
     * {@code warnReserved} reports a reserved tag change that is ignored, for a type that reads one at create.
     */
    private void updateTagsInPlace(StackResource r, JsonNode props, ProvisionContext ctx, String arn,
                                   Map<String, String> currentTags, String name, String keysAttr,
                                   String snapshotAttr, Runnable publish, boolean warnReserved) {
        Map<String, String> templateTags = ctx.resolveTags(props, "Tags");
        Map<String, String> desired = ReservedTags.stripReservedTags(templateTags);
        Map<String, String> liveTags = currentTags == null ? Map.of() : currentTags;
        // The keys the last deployment wrote, as it resolved them: the previous template read with
        // this update's parameters would name the key a parameter has since changed.
        Set<String> recorded = Ec2Tags.ownedKeys(r, keysAttr, ctx);
        Set<String> owned = userKeys(recorded);
        if (warnReserved) {
            warnReservedTagChanges(name, templateTags, desired, recorded, owned);
        }
        ObjectNode snapshot = MAPPER.createObjectNode();
        snapshot.put("arn", arn);
        Ec2Tags.snapshotOwned(snapshot, owned, desired, liveTags, r.getAttributes().get(keysAttr));
        publish.run();
        r.getAttributes().put(snapshotAttr, snapshot.toString());
        CfnRollback.guarded(r, ctx, false, () -> { }, () -> restore(r, snapshot, keysAttr), () -> {
            converge(arn, desired, Ec2Tags.touched(owned, desired));
            Ec2Tags.recordKeys(r, keysAttr, templateTags);
        });
    }

    /** Only a reserved key added or removed is seen: the record holds keys, not values. */
    private static void warnReservedTagChanges(String name, Map<String, String> templateTags,
                                               Map<String, String> desired, Set<String> recorded, Set<String> owned) {
        Set<String> reservedNow = new TreeSet<>(templateTags.keySet());
        reservedNow.removeAll(desired.keySet());
        Set<String> reservedBefore = new TreeSet<>(recorded);
        reservedBefore.removeAll(owned);
        Set<String> reservedChanged = new TreeSet<>(reservedNow);
        reservedChanged.addAll(reservedBefore);
        reservedChanged.removeIf(key -> reservedNow.contains(key) && reservedBefore.contains(key));
        reservedChanged.forEach(key -> LOG.warnv(
                "EKS cluster {0}: the reserved tag {1} is read at create only, the change on update is ignored",
                name, key));
    }

    /**
     * Drives the tags of a cluster or nodegroup to {@code tags}: the one path an update and its rollback take.
     */
    private void converge(String arn, Map<String, String> tags, Collection<String> managedKeys) {
        CfnRollback.reconcileTags(managedKeys, tags, dropped -> eksService.untagResource(arn, dropped),
                added -> eksService.tagResource(arn, added));
    }

    private static Set<String> userKeys(Set<String> keys) {
        Map<String, String> byKey = new HashMap<>();
        keys.forEach(key -> byKey.put(key, ""));
        return new TreeSet<>(ReservedTags.stripReservedTags(byKey).keySet());
    }

    private void restore(StackResource r, JsonNode snapshot, String keysAttr) {
        List<String> touched = new ArrayList<>();
        snapshot.path("touched").forEach(key -> touched.add(key.asText()));
        converge(CfnRollback.snapshotText(snapshot, "arn"), CfnRollback.snapshotTags(snapshot), touched);
        Ec2Tags.restoreRecord(r, keysAttr, snapshot);
    }

    private static String snapshotAttr(StackResource resource) {
        return NODEGROUP.equals(resource.getResourceType()) ? NODEGROUP_SNAPSHOT_ATTR : SNAPSHOT_ATTR;
    }

    @Override
    public boolean rollbackUpdate(StackResource resource) {
        boolean nodegroup = NODEGROUP.equals(resource.getResourceType());
        String snapshotAttr = snapshotAttr(resource);
        JsonNode snapshot = CfnRollback.updateSnapshot(resource, snapshotAttr);
        if (snapshot == null) {
            return ReplacementCleanup.rollback(resource, this::delete);
        }
        // An entity the update created because the prior one was gone is deleted instead.
        if (CfnRollback.undoCreatedByUpdate(resource, snapshot, snapshotAttr, null,
                (createdId, region) -> delete(resource.getResourceType(), createdId, region))) {
            // The prior entity is gone and these attributes described the one just deleted.
            (nodegroup ? NODEGROUP_ATTRIBUTES : CLUSTER_ATTRIBUTES).forEach(resource.getAttributes()::remove);
            return true;
        }
        // Spent only once the restore succeeded: one that throws leaves it for the next attempt.
        restore(resource, snapshot, nodegroup ? NODEGROUP_TAG_KEYS_ATTR : TAG_KEYS_ATTR);
        resource.getAttributes().remove(snapshotAttr);
        return true;
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
        resource.getAttributes().remove(snapshotAttr(resource));
        return ReplacementCleanup.complete(resource, this::delete);
    }

    @Override
    public void clearUpdate(StackResource resource) {
        resource.getAttributes().remove(snapshotAttr(resource));
        ReplacementCleanup.clear(resource);
    }

    @Override
    public void delete(String resourceType, String physicalId, String region) {
        if (NODEGROUP.equals(resourceType)) {
            deleteNodegroup(physicalId, Map.of());
            return;
        }
        if (!CLUSTER.equals(resourceType)) {
            throw new IllegalArgumentException("Unsupported resource type: " + resourceType);
        }
        CfnDeletes.safeDelete("EKS cluster", physicalId, () -> eksService.deleteCluster(physicalId),
                "ResourceNotFoundException");
    }

    private void provisionNodegroup(StackResource r, JsonNode props, ProvisionContext ctx) {
        // A snapshot describes the update in flight; one an earlier update left behind is stale.
        r.getAttributes().remove(NODEGROUP_SNAPSHOT_ATTR);
        if (ctx.isUpdate()) {
            ReplacementCleanup.endRollbackRetry(r);
        }
        Map<String, String> attributesBefore = new HashMap<>(r.getAttributes());
        boolean updates = ctx.isUpdate() && !CfnResourceDispatcher.isStub(ctx.priorPhysicalId(), r.getAttributes());
        // CapacityType, NodegroupName, RemoteAccess, NodeRole, ClusterName, InstanceTypes, DiskSize,
        // AmiType and Subnets are create-only in the registry: a change to one replaces the nodegroup.
        PropertyChanges changes = updates ? ctx.changes(NODEGROUP, props) : null;
        boolean replaces = changes != null && changes.requiresReplacement();
        String clusterName = ctx.resolveOptional(props, "ClusterName");
        String[] prior = updates ? nodegroupNames(ctx.priorPhysicalId(), r.getAttributes()) : null;
        if (updates && prior == null) {
            LOG.warnv("EKS nodegroup {0}: the prior physical id names no nodegroup, treated as gone",
                    ctx.priorPhysicalId());
        }
        String nodegroupName = ctx.resolveOptional(props, "NodegroupName");
        if (nodegroupName == null || nodegroupName.isBlank()) {
            // The name is part of the physical id, so an update that replaces nothing keeps it rather
            // than creating a second nodegroup and orphaning the first.
            nodegroupName = prior != null && !replaces ? prior[1]
                    : ctx.generatePhysicalName(r.getLogicalId(), 100, false);
        }
        if (replaces && ctx.reusesPriorEntity(clusterName + "/" + nodegroupName)) {
            throw CfnExceptions.customNamedReplacement(nodegroupName);
        }
        Nodegroup live = prior != null && !replaces ? liveNodegroup(prior[0], prior[1]) : null;
        if (live != null) {
            updateNodegroupInPlace(r, props, ctx, live, changes);
            return;
        }
        Nodegroup request = new Nodegroup();
        request.setNodegroupName(nodegroupName);
        request.setNodeRole(ctx.resolveOptional(props, "NodeRole"));
        request.setSubnets(ctx.resolveStringList(props, "Subnets"));
        Map<String, String> templateTags = ctx.resolveTags(props, "Tags");
        request.setTags(ReservedTags.stripReservedTags(templateTags));

        Nodegroup nodegroup = eksService.createNodeGroup(clusterName, request);
        // The nodegroup this resource named before is gone or replaced; its attributes must not outlive it.
        NODEGROUP_ATTRIBUTES.forEach(r.getAttributes()::remove);
        publishNodegroup(r, nodegroup);
        Ec2Tags.recordKeys(r, NODEGROUP_TAG_KEYS_ATTR, templateTags);
        if (updates && !replaces) {
            // The nodegroup the update started from is gone, so this one is the update's to undo.
            CfnRollback.recordCreatedByUpdate(r, ctx, NODEGROUP_SNAPSHOT_ATTR);
        }
        if (ctx.isUpdate()) {
            ReplacementCleanup.record(r, ctx, attributesBefore, replaces);
        }
    }

    private void publishNodegroup(StackResource r, Nodegroup nodegroup) {
        // Ref (and the readOnly Id) is the composite the registry primary identifier declares,
        // clusterName/nodegroupName, matching AWS. The plain names stay as their own attributes:
        // ClusterName and NodegroupName back delete() and Fn::GetAtt.
        String id = nodegroup.getClusterName() + "/" + nodegroup.getNodegroupName();
        r.setPhysicalId(id);
        r.getAttributes().put("Id", id);
        r.getAttributes().put("ClusterName", nodegroup.getClusterName());
        r.getAttributes().put("NodegroupName", nodegroup.getNodegroupName());
        if (nodegroup.getNodegroupArn() != null) {
            r.getAttributes().put("Arn", nodegroup.getNodegroupArn());
        }
    }

    /**
     * {clusterName, nodegroupName} of a nodegroup: from the composite physical id, or, for a plain
     * name a stack from before the composite id holds, from the {@code ClusterName} and
     * {@code NodegroupName} attributes. Null when neither names it.
     */
    private static String[] nodegroupNames(String physicalId, Map<String, String> attributes) {
        int slash = physicalId == null ? -1 : physicalId.indexOf('/');
        if (slash > 0 && slash < physicalId.length() - 1) {
            return new String[] {physicalId.substring(0, slash), physicalId.substring(slash + 1)};
        }
        String cluster = attributes.get("ClusterName");
        String nodegroup = attributes.get("NodegroupName");
        if (cluster != null && !cluster.isBlank() && nodegroup != null && !nodegroup.isBlank()) {
            return new String[] {cluster, nodegroup};
        }
        return null;
    }

    private void deleteNodegroup(String physicalId, Map<String, String> attributes) {
        String[] names = nodegroupNames(physicalId, attributes);
        if (names == null) {
            LOG.warnv("EKS nodegroup {0}: no cluster and nodegroup name to delete it by, treated as gone", physicalId);
            return;
        }
        CfnDeletes.safeDelete("EKS nodegroup", physicalId, () -> eksService.deleteNodeGroup(names[0], names[1]),
                "ResourceNotFoundException");
    }

    /** The nodegroup an update starts from, or null when it or its cluster is gone and the update creates one. */
    private Nodegroup liveNodegroup(String clusterName, String nodegroupName) {
        return orNullWhenGone(() -> eksService.describeNodeGroup(clusterName, nodegroupName));
    }

    /**
     * The update AWS makes for the properties it does not replace on: the nodegroup keeps its name.
     * EksService has no UpdateNodegroupConfig or UpdateNodegroupVersion, so the one mutable property
     * floci honors is Tags; a change to any other updatable property is accepted with a warning.
     */
    private void updateNodegroupInPlace(StackResource r, JsonNode props, ProvisionContext ctx, Nodegroup live,
                                        PropertyChanges changes) {
        List<String> changed = new ArrayList<>();
        for (String property : UNSUPPORTED_NODEGROUP_UPDATES) {
            // The engine's own comparison, which resolves parameters and Refs on both sides.
            PropertyChanges.Kind kind = changes.kindOf(property);
            if (kind != null && kind != PropertyChanges.Kind.UNCHANGED) {
                changed.add(property);
            }
        }
        if (!changed.isEmpty()) {
            LOG.warnv("EKS nodegroup {0}: in-place modification is not supported, changes to {1} are ignored",
                    live.getNodegroupName(), String.join(", ", changed));
        }
        updateTagsInPlace(r, props, ctx, live.getNodegroupArn(), live.getTags(), live.getNodegroupName(),
                NODEGROUP_TAG_KEYS_ATTR, NODEGROUP_SNAPSHOT_ATTR, () -> publishNodegroup(r, live), false);
    }

    @Override
    public void delete(StackResource resource, String region) {
        switch (resource.getResourceType()) {
            case CLUSTER -> delete(CLUSTER, resource.getPhysicalId(), region);
            case NODEGROUP -> deleteNodegroup(resource.getPhysicalId(), resource.getAttributes());
            default -> {
                // No other type reaches this provisioner's delete.
            }
        }
    }
}
