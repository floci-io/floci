package io.floci.aws.services.cloudformation.provisioners;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.floci.aws.core.common.AwsException;
import io.floci.aws.core.common.ReservedTags;
import io.floci.aws.services.cloudformation.model.StackResource;
import io.floci.aws.services.eks.EksService;
import io.floci.aws.services.eks.model.AccessConfig;
import io.floci.aws.services.eks.model.BlockStorage;
import io.floci.aws.services.eks.model.Cluster;
import io.floci.aws.services.eks.model.ComputeConfig;
import io.floci.aws.services.eks.model.CreateClusterRequest;
import io.floci.aws.services.eks.model.ElasticLoadBalancing;
import io.floci.aws.services.eks.model.KubernetesNetworkConfig;
import io.floci.aws.services.eks.model.LogSetup;
import io.floci.aws.services.eks.model.Logging;
import io.floci.aws.services.eks.model.Nodegroup;
import io.floci.aws.services.eks.model.ResourcesVpcConfig;
import io.floci.aws.services.eks.model.StorageConfig;
import io.floci.aws.services.eks.model.UpdateClusterConfigRequest;
import io.floci.aws.services.eks.model.UpgradePolicy;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;
import java.util.function.Consumer;
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
            List.of("Arn", "Endpoint", "CertificateAuthorityData", "OpenIdConnectIssuerUrl",
                    "ClusterSecurityGroupId");
    private static final ObjectMapper MAPPER = new ObjectMapper();
    /** Every attribute {@code publishNodegroup} writes for a nodegroup. */
    private static final List<String> NODEGROUP_ATTRIBUTES = List.of("Id", "ClusterName", "NodegroupName", "Arn");
    private static final String SNAPSHOT_ATTR = CfnRollback.EKS_CLUSTER_UPDATE_SNAPSHOT_ATTR;
    /** The cluster properties updateInPlace applies through UpdateClusterConfig, besides Tags. */
    private static final Set<String> CLUSTER_APPLIED =
            Set.of("ResourcesVpcConfig", "Logging", "AccessConfig", "UpgradePolicy", "ComputeConfig", "StorageConfig",
                    "KubernetesNetworkConfig");
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
        PropertyChanges changes = updates ? ctx.changes(CLUSTER, props) : null;
        boolean replaces = changes != null && changes.requiresReplacement();
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
            updateInPlace(r, props, ctx, live, changes);
            return;
        }
        CreateClusterRequest request = new CreateClusterRequest();
        request.setName(name);
        request.setVersion(ctx.resolveOptional(props, "Version"));
        request.setRoleArn(ctx.resolveOptional(props, "RoleArn"));
        // Unstripped: createCluster reads the reserved cluster-args tag, then drops it.
        Map<String, String> templateTags = ctx.resolveTags(props, "Tags");
        request.setTags(templateTags);
        request.setEncryptionConfig(createSetting(props, "EncryptionConfig", ctx, new TypeReference<>() { }));
        request.setKubernetesNetworkConfig(createSetting(props, "KubernetesNetworkConfig", ctx, new TypeReference<>() { }));
        request.setComputeConfig(createSetting(props, "ComputeConfig", ctx, new TypeReference<>() { }));
        request.setStorageConfig(createSetting(props, "StorageConfig", ctx, new TypeReference<>() { }));
        request.setResourcesVpcConfig(createSetting(props, "ResourcesVpcConfig", ctx, new TypeReference<>() { }));
        request.setAccessConfig(createSetting(props, "AccessConfig", ctx, new TypeReference<>() { }));
        request.setLogging(logging(props, ctx));
        request.setUpgradePolicy(createSetting(props, "UpgradePolicy", ctx, new TypeReference<>() { }));
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

    /**
     * A property in its API request shape, or null when the template leaves it out. The
     * template value is read against the type the resource schema declares, so a value of another type
     * fails the resource with CloudFormation's wording. The request model has no ServiceIpv6Cidr member
     * of KubernetesNetworkConfig and no ControlPlaneEgressMode member of ResourcesVpcConfig, so those
     * are not read.
     */
    private static <T> T createSetting(JsonNode props, String name, ProvisionContext ctx, TypeReference<T> type) {
        JsonNode resolved = readSetting(props, name, ctx);
        if (resolved == null) {
            return null;
        }
        try {
            return MAPPER.convertValue(CfnApiShapeConverter.convert(resolved, CfnApiShapeConverter.OMIT_NULL_PROPERTIES),
                    type);
        } catch (IllegalArgumentException e) {
            throw new AwsException("ValidationError", "AWS::EKS::Cluster " + name + " is not valid: "
                    + e.getMessage(), 400);
        }
    }

    private static JsonNode readSetting(JsonNode props, String name, ProvisionContext ctx) {
        CfnPropertyTypes.Reader types = new CfnPropertyTypes.Reader(ctx.engine());
        JsonNode resolved = types.readProperty(CLUSTER, props, name);
        types.finish();
        return resolved;
    }

    /**
     * Logging in the API shape. The template lists the enabled types as
     * {@code ClusterLogging.EnabledTypes[].Type}; the service disables every type not listed.
     */
    private static Logging logging(JsonNode props, ProvisionContext ctx) {
        JsonNode resolved = readSetting(props, "Logging", ctx);
        if (resolved == null) {
            return null;
        }
        List<String> enabled = new ArrayList<>();
        JsonNode types = resolved.path("ClusterLogging").path("EnabledTypes");
        types.forEach(entry -> {
            String type = entry.path("Type").asText("");
            if (type.isBlank()) {
                throw new AwsException("ValidationError",
                        "AWS::EKS::Cluster Logging.ClusterLogging.EnabledTypes entry has no Type", 400);
            }
            enabled.add(type);
        });
        return new Logging(List.of(new LogSetup(enabled, true)));
    }

    private void publish(StackResource r, Cluster cluster) {
        r.setPhysicalId(cluster.getName());
        r.getAttributes().put("Arn", cluster.getArn());
        if (cluster.getEndpoint() != null) {
            r.getAttributes().put("Endpoint", cluster.getEndpoint());
        }
        if (cluster.getResourcesVpcConfig() != null
                && cluster.getResourcesVpcConfig().getClusterSecurityGroupId() != null) {
            r.getAttributes().put("ClusterSecurityGroupId", cluster.getResourcesVpcConfig().getClusterSecurityGroupId());
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
     * Tags, Logging, AccessConfig.AuthenticationMode, UpgradePolicy and the endpoint access members of
     * ResourcesVpcConfig are applied, and only when the template's value differs from the live cluster's.
     * EksService has no UpdateClusterVersion, so a changed Version is accepted with a warning and not applied. AWS lets
     * UpdateClusterConfig choose different subnets and security groups, but floci does not apply that, so a
     * changed or dropped subnet or security group list fails the resource. The snapshot holds, and the
     * rollback restores, only the config families the update changes, taken from the same read the request is
     * computed from.
     */
    private void updateInPlace(StackResource r, JsonNode props, ProvisionContext ctx, Cluster live,
                               PropertyChanges changes) {
        warnIgnoredChanges("cluster", live.getName(), changes, CLUSTER_APPLIED);
        // One read of the cluster: the request, the snapshot and the apply all start from it.
        Cluster current = currentOr(live);
        UpdateClusterConfigRequest configRequest = configRequest(props, ctx, changes, current);
        UpgradePolicy unstoredPolicy = unstoredPolicy(props, ctx, changes, current);
        updateTagsInPlace(r, props, ctx, live.getArn(), live.getTags(), live.getName(), TAG_KEYS_ATTR, SNAPSHOT_ATTR,
                () -> publish(r, live), true, snapshot -> {
                    snapshot.put("name", live.getName());
                    snapshotConfig(snapshot, current, configRequest);
                }, () -> {
                    if (configRequest != null) {
                        eksService.updateClusterConfig(live.getName(), configRequest);
                    }
                    if (unstoredPolicy != null) {
                        eksService.restoreClusterConfig(live.getName(), null, null, null, unstoredPolicy);
                    }
                });
    }

    /**
     * The policy an unchanged template declares for a cluster created before Floci kept the policy, whose create
     * therefore never stored it, or null. It is written as that create would have written it, without
     * UpdateClusterConfig's rules, and stays when the update rolls back. A changed policy goes through
     * UpdateClusterConfig instead, and a cluster that holds a policy keeps it, as one changed outside the stack does.
     */
    private static UpgradePolicy unstoredPolicy(JsonNode props, ProvisionContext ctx, PropertyChanges changes,
                                                Cluster live) {
        if (live.hasStoredUpgradePolicy() || changed(changes, "UpgradePolicy")) {
            return null;
        }
        UpgradePolicy declared = createSetting(props, "UpgradePolicy", ctx, new TypeReference<>() { });
        return declared == null || !UpgradePolicy.SUPPORT_TYPES.contains(declared.supportType())
                || declared.supportType().equals(live.getUpgradePolicy().supportType()) ? null : declared;
    }

    /**
     * Nothing create-only changed on the in-place path, so every other property the engine reports
     * as added, changed or dropped is one EksService has no call to apply: it is accepted with a
     * warning. Tags are applied by {@link #updateTagsInPlace}, and {@code applied} names the others the caller applies.
     */
    private static void warnIgnoredChanges(String kind, String name, PropertyChanges changes, Set<String> applied) {
        List<String> ignored = new ArrayList<>();
        for (PropertyChanges.PropertyChange change : changes.byName().values()) {
            if (change.kind() != PropertyChanges.Kind.UNCHANGED && !"Tags".equals(change.name())
                    && !applied.contains(change.name())) {
                ignored.add(change.name());
            }
        }
        if (!ignored.isEmpty()) {
            LOG.warnv("EKS {0} {1}: in-place modification is not supported, changes to {2} are ignored",
                    kind, name, String.join(", ", ignored));
        }
    }

    /**
     * The tag half of an in-place update, shared by cluster and nodegroup: drives the tags the stack
     * owns to the template's, snapshots them for the rollback and publishes the live entity.
     * {@code warnReserved} reports a reserved tag change that is ignored, for a type that reads one at create.
     */
    private void updateTagsInPlace(StackResource r, JsonNode props, ProvisionContext ctx, String arn,
                                   Map<String, String> currentTags, String name, String keysAttr,
                                   String snapshotAttr, Runnable publish, boolean warnReserved) {
        updateTagsInPlace(r, props, ctx, arn, currentTags, name, keysAttr, snapshotAttr, publish, warnReserved,
                snapshot -> { }, () -> { });
    }

    /**
     * As above, with {@code snapshotExtra} adding what the rollback restores and {@code applyExtra} running
     * with the tag convergence when the update is applied.
     */
    private void updateTagsInPlace(StackResource r, JsonNode props, ProvisionContext ctx, String arn,
                                   Map<String, String> currentTags, String name, String keysAttr,
                                   String snapshotAttr, Runnable publish, boolean warnReserved,
                                   Consumer<ObjectNode> snapshotExtra, Runnable applyExtra) {
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
        snapshotExtra.accept(snapshot);
        Ec2Tags.snapshotOwned(snapshot, owned, desired, liveTags, r.getAttributes().get(keysAttr));
        publish.run();
        r.getAttributes().put(snapshotAttr, snapshot.toString());
        CfnRollback.guarded(r, ctx, false, () -> { }, () -> restore(r, snapshot, keysAttr), () -> {
            applyExtra.run();
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
     * The UpdateClusterConfig request for the families whose changed template value differs from the live
     * cluster, or null when none does. A member the template dropped is read as its AWS default: public access
     * on, private access off, every address allowed, no log type exported and extended support; floci applies that
     * default itself.
     * A dropped AuthenticationMode asks for nothing, as a mode cannot be moved back. The subnet and security
     * group lists go along as the template has them, a dropped list counting as an empty one, so the service
     * refuses one that differs from the live cluster's.
     */
    private UpdateClusterConfigRequest configRequest(JsonNode props, ProvisionContext ctx, PropertyChanges changes,
                                                     Cluster live) {
        ResourcesVpcConfig vpc = null;
        Logging logging = null;
        AccessConfig access = null;
        UpgradePolicy upgradePolicy = null;
        ResourcesVpcConfig current = live.getResourcesVpcConfig();
        if (changed(changes, "ResourcesVpcConfig") && current != null) {
            ResourcesVpcConfig desired = createSetting(props, "ResourcesVpcConfig", ctx, new TypeReference<>() { });
            boolean publicAccess = desired == null || desired.getEndpointPublicAccess() == null
                    || desired.getEndpointPublicAccess();
            boolean privateAccess = desired != null && Boolean.TRUE.equals(desired.getEndpointPrivateAccess());
            List<String> cidrs = desired == null || desired.getPublicAccessCidrs() == null
                    ? List.of("0.0.0.0/0") : desired.getPublicAccessCidrs();
            List<String> subnets = desired == null ? null
                    : desired.getSubnetIds() == null ? List.of() : desired.getSubnetIds();
            List<String> securityGroups = desired == null ? null
                    : desired.getSecurityGroupIds() == null ? List.of() : desired.getSecurityGroupIds();
            boolean listsDiffer = desired != null && (differs(subnets, current.getSubnetIds())
                    || differs(securityGroups, current.getSecurityGroupIds()));
            if (listsDiffer || publicAccess != Boolean.TRUE.equals(current.getEndpointPublicAccess())
                    || privateAccess != Boolean.TRUE.equals(current.getEndpointPrivateAccess())
                    || differs(cidrs, current.getPublicAccessCidrs())) {
                vpc = new ResourcesVpcConfig();
                vpc.setEndpointPublicAccess(publicAccess);
                vpc.setEndpointPrivateAccess(privateAccess);
                vpc.setPublicAccessCidrs(cidrs);
                vpc.setSubnetIds(subnets);
                vpc.setSecurityGroupIds(securityGroups);
            }
        }
        if (changed(changes, "Logging")) {
            Logging enabled = logging(props, ctx);
            List<String> on = enabled == null ? List.of() : enabled.getClusterLogging().get(0).getTypes();
            if (!new HashSet<>(on).equals(EksService.enabledLogTypes(live.getLogging()))) {
                List<String> off = new ArrayList<>(EksService.ALL_LOG_TYPES);
                off.removeAll(on);
                List<LogSetup> entries = new ArrayList<>();
                if (!on.isEmpty()) {
                    entries.add(new LogSetup(on, true));
                }
                if (!off.isEmpty()) {
                    entries.add(new LogSetup(off, false));
                }
                logging = new Logging(entries);
            }
        }
        if (changed(changes, "AccessConfig")) {
            AccessConfig desired = createSetting(props, "AccessConfig", ctx, new TypeReference<>() { });
            AccessConfig liveAccess = live.getAccessConfig();
            if (desired != null && desired.authenticationMode() != null && (liveAccess == null
                    || !desired.authenticationMode().equals(liveAccess.authenticationMode()))) {
                access = new AccessConfig(desired.authenticationMode(), null);
            }
        }
        if (changed(changes, "UpgradePolicy")) {
            UpgradePolicy desired = createSetting(props, "UpgradePolicy", ctx, new TypeReference<>() { });
            String supportType = desired == null || desired.supportType() == null
                    ? UpgradePolicy.EXTENDED : desired.supportType();
            if (!supportType.equals(live.getUpgradePolicy().supportType())) {
                upgradePolicy = new UpgradePolicy(supportType);
            }
        }
        AutoModeChange autoMode = autoModeChange(props, ctx, changes, live);
        if (vpc == null && logging == null && access == null && upgradePolicy == null && autoMode == null) {
            return null;
        }
        return autoMode == null ? new UpdateClusterConfigRequest(vpc, logging, access, upgradePolicy, null)
                : new UpdateClusterConfigRequest(vpc, logging, access, upgradePolicy, null,
                        autoMode.compute(), autoMode.storage(), autoMode.network());
    }

    /** The EKS Auto Mode members of an UpdateClusterConfig request. */
    private record AutoModeChange(ComputeConfig compute, StorageConfig storage, KubernetesNetworkConfig network) { }

    /**
     * The EKS Auto Mode change the template asks for, or null when it asks for none. ComputeConfig, StorageConfig
     * and KubernetesNetworkConfig.ElasticLoadBalancing are updated in place, not create-only, and go to
     * UpdateClusterConfig together, as its Auto Mode members: one the template drops is read as disabled. The
     * request is sent only when what the template declares differs from the live cluster: whether each capability
     * is on, and with compute on, its node pools and node role. A role the template leaves out is the live one,
     * which UpdateClusterConfig keeps, so that is no change either.
     */
    private static AutoModeChange autoModeChange(JsonNode props, ProvisionContext ctx, PropertyChanges changes,
                                                 Cluster live) {
        if (!changed(changes, "ComputeConfig") && !changed(changes, "StorageConfig")
                && !loadBalancingChanged(changes)) {
            return null;
        }
        ComputeConfig compute = createSetting(props, "ComputeConfig", ctx, new TypeReference<>() { });
        StorageConfig storage = createSetting(props, "StorageConfig", ctx, new TypeReference<>() { });
        KubernetesNetworkConfig declaredNetwork =
                createSetting(props, "KubernetesNetworkConfig", ctx, new TypeReference<>() { });
        ElasticLoadBalancing loadBalancing = declaredNetwork == null || declaredNetwork.getElasticLoadBalancing() == null
                ? new ElasticLoadBalancing(false) : declaredNetwork.getElasticLoadBalancing();
        compute = compute == null ? new ComputeConfig(false, null, null) : compute;
        ComputeConfig liveCompute = live.getComputeConfig();
        if (Boolean.TRUE.equals(compute.enabled()) && liveCompute != null && Boolean.TRUE.equals(liveCompute.enabled())
                && (compute.nodeRoleArn() == null || compute.nodeRoleArn().isEmpty())) {
            // The role can't change while compute stays on, so a template that leaves it out keeps the live one, and
            // names it: node pools are refused without one.
            compute = new ComputeConfig(true, compute.nodePools(), liveCompute.nodeRoleArn());
        }
        storage = storage == null || storage.blockStorage() == null ? new StorageConfig(new BlockStorage(false)) : storage;
        KubernetesNetworkConfig liveNetwork = live.getKubernetesNetworkConfig();
        if (autoModeOn(compute, storage, loadBalancing).equals(autoModeOn(live.getComputeConfig(), live.getStorageConfig(),
                liveNetwork == null ? null : liveNetwork.getElasticLoadBalancing()))
                && (!Boolean.TRUE.equals(compute.enabled()) || sameCompute(compute, live.getComputeConfig()))) {
            return null;
        }
        KubernetesNetworkConfig network = new KubernetesNetworkConfig();
        network.setElasticLoadBalancing(loadBalancing);
        return new AutoModeChange(compute, storage, network);
    }

    /**
     * Whether KubernetesNetworkConfig's ElasticLoadBalancing is what changed. Its other members are create-only
     * (IpFamily, ServiceIpv4Cidr) or not applied (ServiceIpv6Cidr), so a change to one of them asks for no Auto Mode
     * change, and doesn't turn off Auto Mode a cluster got outside the stack.
     */
    private static boolean loadBalancingChanged(PropertyChanges changes) {
        if (!changed(changes, "KubernetesNetworkConfig")) {
            return false;
        }
        PropertyChanges.PropertyChange change = changes.byName().get("KubernetesNetworkConfig");
        JsonNode prior = change.prior() == null ? null : change.prior().get("ElasticLoadBalancing");
        JsonNode current = change.current() == null ? null : change.current().get("ElasticLoadBalancing");
        return !Objects.equals(prior, current);
    }

    private static List<Boolean> autoModeOn(ComputeConfig compute, StorageConfig storage,
                                            ElasticLoadBalancing loadBalancing) {
        return List.of(compute != null && Boolean.TRUE.equals(compute.enabled()),
                storage != null && storage.blockStorage() != null && Boolean.TRUE.equals(storage.blockStorage().enabled()),
                loadBalancing != null && Boolean.TRUE.equals(loadBalancing.enabled()));
    }

    /** With compute on in both: the same node pools, and the same role unless the template leaves it out. */
    private static boolean sameCompute(ComputeConfig declared, ComputeConfig live) {
        if (live == null) {
            return false;
        }
        Set<String> declaredPools = declared.nodePools() == null ? Set.of() : new HashSet<>(declared.nodePools());
        Set<String> livePools = live.nodePools() == null ? Set.of() : new HashSet<>(live.nodePools());
        boolean roleKept = declared.nodeRoleArn() == null || declared.nodeRoleArn().isEmpty()
                || declared.nodeRoleArn().equals(live.nodeRoleArn());
        return declaredPools.equals(livePools) && roleKept;
    }

    private static boolean changed(PropertyChanges changes, String name) {
        return !changes.unchanged().contains(name) && changes.kindOf(name) != null;
    }

    private static boolean differs(List<String> desired, List<String> live) {
        return desired != null && !new HashSet<>(desired).equals(new HashSet<>(live == null ? List.of() : live));
    }

    /** The cluster as it is now, or {@code live} when it cannot be read again. */
    private Cluster currentOr(Cluster live) {
        Cluster current = liveCluster(live.getName());
        return current == null ? live : current;
    }

    /**
     * Saves the live value of each family {@code request} changes, and only those, so the rollback restores
     * only what this update wrote and leaves a family another writer changed since untouched. A null request
     * (a tag-only update) saves no config.
     */
    private static void snapshotConfig(ObjectNode snapshot, Cluster live, UpdateClusterConfigRequest request) {
        if (request == null) {
            return;
        }
        ResourcesVpcConfig vpc = live.getResourcesVpcConfig();
        if (request.resourcesVpcConfig() != null && vpc != null) {
            snapshot.set("endpoint", MAPPER.valueToTree(vpc));
        }
        if (request.logging() != null && live.getLogging() != null) {
            snapshot.set("logging", MAPPER.valueToTree(live.getLogging()));
        }
        if (request.accessConfig() != null && live.getAccessConfig() != null
                && live.getAccessConfig().authenticationMode() != null) {
            snapshot.put("authenticationMode", live.getAccessConfig().authenticationMode());
        }
        if (request.upgradePolicy() != null) {
            snapshot.set("upgradePolicy", MAPPER.valueToTree(live.getUpgradePolicy()));
        }
        if (request.changesAutoMode()) {
            ObjectNode autoMode = snapshot.putObject("autoMode");
            autoMode.set("compute", MAPPER.valueToTree(live.getComputeConfig()));
            autoMode.set("storage", MAPPER.valueToTree(live.getStorageConfig()));
            KubernetesNetworkConfig network = live.getKubernetesNetworkConfig();
            autoMode.set("loadBalancing", MAPPER.valueToTree(network == null ? null : network.getElasticLoadBalancing()));
        }
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
        String name = CfnRollback.snapshotText(snapshot, "name");
        if (name != null && (snapshot.has("endpoint") || snapshot.has("logging") || snapshot.has("authenticationMode")
                || snapshot.has("upgradePolicy"))) {
            eksService.restoreClusterConfig(name,
                    snapshot.has("endpoint") ? MAPPER.convertValue(snapshot.get("endpoint"), ResourcesVpcConfig.class) : null,
                    snapshot.has("logging") ? MAPPER.convertValue(snapshot.get("logging"), Logging.class) : null,
                    snapshot.has("authenticationMode") ? snapshot.get("authenticationMode").asText() : null,
                    snapshot.has("upgradePolicy")
                            ? MAPPER.convertValue(snapshot.get("upgradePolicy"), UpgradePolicy.class) : null);
        }
        JsonNode autoMode = snapshot.get("autoMode");
        if (name != null && autoMode != null) {
            eksService.restoreAutoMode(name, MAPPER.convertValue(autoMode.get("compute"), ComputeConfig.class),
                    MAPPER.convertValue(autoMode.get("storage"), StorageConfig.class),
                    MAPPER.convertValue(autoMode.get("loadBalancing"), ElasticLoadBalancing.class));
        }
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
        warnIgnoredChanges("nodegroup", live.getNodegroupName(), changes, Set.of());
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
