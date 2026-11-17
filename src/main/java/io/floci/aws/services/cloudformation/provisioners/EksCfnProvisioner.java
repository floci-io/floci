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
    private static final String SNAPSHOT_ATTR = CfnRollback.EKS_CLUSTER_UPDATE_SNAPSHOT_ATTR;
    // The tag keys the last deployment resolved, reserved ones included, as Ec2Tags.recordKeys records
    // them, so an update removes only those and keeps a tag added outside the stack.
    private static final String TAG_KEYS_ATTR = "__FlociEksClusterTagKeys";

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
        try {
            return eksService.describeCluster(name);
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
        Map<String, String> templateTags = ctx.resolveTags(props, "Tags");
        Map<String, String> desired = ReservedTags.stripReservedTags(templateTags);
        Map<String, String> liveTags = live.getTags() == null ? Map.of() : live.getTags();
        // The keys the last deployment wrote, as it resolved them: the previous template read with
        // this update's parameters would name the key a parameter has since changed.
        Set<String> recorded = Ec2Tags.ownedKeys(r, TAG_KEYS_ATTR, ctx);
        Set<String> owned = userKeys(recorded);
        // Only a reserved key added or removed is seen: the record holds keys, not values.
        Set<String> reservedNow = new TreeSet<>(templateTags.keySet());
        reservedNow.removeAll(desired.keySet());
        Set<String> reservedBefore = new TreeSet<>(recorded);
        reservedBefore.removeAll(owned);
        Set<String> reservedChanged = new TreeSet<>(reservedNow);
        reservedChanged.addAll(reservedBefore);
        reservedChanged.removeIf(key -> reservedNow.contains(key) && reservedBefore.contains(key));
        reservedChanged.forEach(key -> LOG.warnv(
                "EKS cluster {0}: the reserved tag {1} is read at create only, the change on update is ignored",
                live.getName(), key));
        ObjectNode snapshot = MAPPER.createObjectNode();
        snapshot.put("arn", live.getArn());
        Ec2Tags.snapshotOwned(snapshot, owned, desired, liveTags, r.getAttributes().get(TAG_KEYS_ATTR));
        publish(r, live);
        r.getAttributes().put(SNAPSHOT_ATTR, snapshot.toString());
        CfnRollback.guarded(r, ctx, false, () -> { }, () -> restore(r, snapshot), () -> {
            converge(live.getArn(), desired, Ec2Tags.touched(owned, desired));
            Ec2Tags.recordKeys(r, TAG_KEYS_ATTR, templateTags);
        });
    }

    /**
     * Drives the cluster's tags to {@code tags}: the one path an update and its rollback take.
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

    private void restore(StackResource r, JsonNode snapshot) {
        List<String> touched = new ArrayList<>();
        snapshot.path("touched").forEach(key -> touched.add(key.asText()));
        converge(CfnRollback.snapshotText(snapshot, "arn"), CfnRollback.snapshotTags(snapshot), touched);
        Ec2Tags.restoreRecord(r, TAG_KEYS_ATTR, snapshot);
    }

    @Override
    public boolean rollbackUpdate(StackResource resource) {
        JsonNode snapshot = CfnRollback.updateSnapshot(resource, SNAPSHOT_ATTR);
        if (snapshot == null) {
            return ReplacementCleanup.rollback(resource, this::delete);
        }
        // A cluster the update created because the prior one was gone is deleted instead.
        if (CfnRollback.undoCreatedByUpdate(resource, snapshot, SNAPSHOT_ATTR, null,
                (createdId, region) -> delete(CLUSTER, createdId, region))) {
            // The prior cluster is gone and these attributes described the one just deleted.
            CLUSTER_ATTRIBUTES.forEach(resource.getAttributes()::remove);
            return true;
        }
        // Spent only once the restore succeeded: one that throws leaves it for the next attempt.
        restore(resource, snapshot);
        resource.getAttributes().remove(SNAPSHOT_ATTR);
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
        resource.getAttributes().remove(SNAPSHOT_ATTR);
        return ReplacementCleanup.complete(resource, this::delete);
    }

    @Override
    public void clearUpdate(StackResource resource) {
        resource.getAttributes().remove(SNAPSHOT_ATTR);
        ReplacementCleanup.clear(resource);
    }

    @Override
    public void delete(String resourceType, String physicalId, String region) {
        if (!CLUSTER.equals(resourceType)) {
            throw new IllegalArgumentException("Unsupported resource type: " + resourceType);
        }
        CfnDeletes.safeDelete("EKS cluster", physicalId, () -> eksService.deleteCluster(physicalId),
                "ResourceNotFoundException");
    }

    private void provisionNodegroup(StackResource r, JsonNode props, ProvisionContext ctx) {
        String clusterName = ctx.resolveOptional(props, "ClusterName");
        Nodegroup request = new Nodegroup();
        String nodegroupName = ctx.resolveOptional(props, "NodegroupName");
        if (nodegroupName == null || nodegroupName.isBlank()) {
            nodegroupName = ctx.generatePhysicalName(r.getLogicalId(), 100, false);
        }
        request.setNodegroupName(nodegroupName);
        request.setNodeRole(ctx.resolveOptional(props, "NodeRole"));
        request.setSubnets(ctx.resolveStringList(props, "Subnets"));

        Nodegroup nodegroup = eksService.createNodeGroup(clusterName, request);
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

    @Override
    public void delete(StackResource resource, String region) {
        switch (resource.getResourceType()) {
            case CLUSTER -> delete(CLUSTER, resource.getPhysicalId(), region);
            case NODEGROUP -> {
                // Deleting a nodegroup needs the cluster name and the nodegroup name, both stored as
                // create-time attributes; the physical id is now the composite clusterName/nodegroupName,
                // so it cannot be passed as the nodegroup name.
                String clusterName = resource.getAttributes().get("ClusterName");
                String nodegroupName = resource.getAttributes().get("NodegroupName");
                if (clusterName != null && !clusterName.isBlank()
                        && nodegroupName != null && !nodegroupName.isBlank()) {
                    CfnDeletes.safeDelete("EKS nodegroup", resource.getPhysicalId(),
                            () -> eksService.deleteNodeGroup(clusterName, nodegroupName),
                            "ResourceNotFoundException");
                }
            }
            default -> {
                // No other type reaches this provisioner's delete.
            }
        }
    }
}
