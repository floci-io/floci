package io.github.hectorvent.floci.services.eks;

import io.github.hectorvent.floci.config.EmulatorConfig;
import io.github.hectorvent.floci.core.common.AwsArnUtils;
import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.core.common.RegionResolver;
import io.github.hectorvent.floci.core.common.RequestScopes;
import io.github.hectorvent.floci.core.common.TagHandler;
import io.github.hectorvent.floci.core.common.docker.UserDataPipeline;
import io.github.hectorvent.floci.core.storage.AccountAwareStorageBackend;
import io.github.hectorvent.floci.core.storage.StorageBackend;
import io.github.hectorvent.floci.core.storage.StorageFactory;
import io.github.hectorvent.floci.services.ec2.Ec2Service;
import io.github.hectorvent.floci.services.ec2.Ipv4Cidrs;
import io.github.hectorvent.floci.services.ec2.SecurityGroupPolicy;
import io.github.hectorvent.floci.services.ec2.model.IpPermission;
import io.github.hectorvent.floci.services.ec2.model.LaunchTemplateData;
import io.github.hectorvent.floci.services.ec2.model.SecurityGroup;
import io.github.hectorvent.floci.services.ec2.model.Tag;
import io.github.hectorvent.floci.services.ec2.model.UserIdGroupPair;
import io.github.hectorvent.floci.services.ec2.model.Vpc;
import io.github.hectorvent.floci.services.eks.model.CertificateAuthority;
import io.github.hectorvent.floci.services.eks.model.Cluster;
import io.github.hectorvent.floci.services.eks.model.AccessConfig;
import io.github.hectorvent.floci.services.eks.model.ClusterIdentity;
import io.github.hectorvent.floci.services.eks.model.ClusterStatus;
import io.github.hectorvent.floci.services.eks.model.CreateClusterRequest;
import io.github.hectorvent.floci.services.eks.model.CreateFargateProfileRequest;
import io.github.hectorvent.floci.services.eks.model.CreateNodeGroupRequest;
import io.github.hectorvent.floci.services.eks.model.EncryptionConfig;
import io.github.hectorvent.floci.services.eks.model.FargateProfile;
import io.github.hectorvent.floci.services.eks.model.FargateProfileStatus;
import io.github.hectorvent.floci.services.eks.model.KubernetesNetworkConfig;
import io.github.hectorvent.floci.services.eks.model.LogSetup;
import io.github.hectorvent.floci.services.eks.model.Logging;
import io.github.hectorvent.floci.services.eks.model.Nodegroup;
import io.github.hectorvent.floci.services.eks.model.NodegroupScalingConfig;
import io.github.hectorvent.floci.services.eks.model.NodegroupStatus;
import io.github.hectorvent.floci.services.eks.model.OidcIdentity;
import io.github.hectorvent.floci.services.eks.model.Provider;
import io.github.hectorvent.floci.services.eks.model.ResourcesVpcConfig;
import com.fasterxml.jackson.core.type.TypeReference;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import io.github.hectorvent.floci.core.resource.ExplorerResource;
import io.github.hectorvent.floci.core.resource.ResourceProvider;
import io.github.hectorvent.floci.core.resource.SupportedResourceType;

@ApplicationScoped
public class EksService implements TagHandler, ResourceProvider {

    private static final Logger LOG = Logger.getLogger(EksService.class);

    private record UserDataClaim(String userData, CompletableFuture<UserDataPipeline.ExecutionResult> future) {}

    private static final List<String> ALL_LOG_TYPES = List.of(
            "api", "audit", "authenticator", "controllerManager", "scheduler"
    );

    /** The AWS charset for EKS cluster names. It admits no dot, which the Docker-name account
     *  qualifier relies on — see EksClusterManager#accountQualifiedName. */
    static final String CLUSTER_NAME_REGEX = "[0-9A-Za-z][A-Za-z0-9\\-_]*";

    private static final String CLUSTER_SG_DESCRIPTION =
            "EKS created security group applied to ENI that is attached to EKS Control Plane master nodes, as well as any managed workloads.";

    private final StorageBackend<String, Cluster> storage;
    private final StorageBackend<String, Nodegroup> nodeGroupStorage;
    private final StorageBackend<String, FargateProfile> fargateProfileStorage;
    private final EmulatorConfig config;
    private final RegionResolver regionResolver;
    private final EksClusterManager clusterManager;
    private final Ec2Service ec2Service;
    private final EksOidcService oidcService;
    private final EksAccessEntryService accessEntries;
    private final EksPodIdentityAssociationService podIdentityAssociations;
    private final EksAddonService addons;
    private final ScheduledExecutorService poller = Executors.newSingleThreadScheduledExecutor();
    private final Map<String, UserDataClaim> appliedClusterUserData = new ConcurrentHashMap<>();

    @Inject
    public EksService(StorageFactory storageFactory, EmulatorConfig config,
            RegionResolver regionResolver, EksClusterManager clusterManager, Ec2Service ec2Service,
            EksOidcService oidcService, EksAccessEntryService accessEntries,
            EksPodIdentityAssociationService podIdentityAssociations,
            EksAddonService addons) {
        this.storage = storageFactory.create("eks", "eks-clusters.json",
                new TypeReference<Map<String, Cluster>>() {
                });
        this.nodeGroupStorage = storageFactory.create("eks", "eks-nodegroups.json",
                new TypeReference<Map<String, Nodegroup>>() {
                });
        this.fargateProfileStorage = storageFactory.create("eks", "eks-fargate-profiles.json",
                new TypeReference<Map<String, FargateProfile>>() {
                });
        this.config = config;
        this.regionResolver = regionResolver;
        this.clusterManager = clusterManager;
        this.ec2Service = ec2Service;
        this.oidcService = oidcService;
        this.accessEntries = accessEntries;
        this.podIdentityAssociations = podIdentityAssociations;
        this.addons = addons;
    }

    public EksService(StorageFactory storageFactory, EmulatorConfig config,
            RegionResolver regionResolver, EksClusterManager clusterManager, Ec2Service ec2Service,
            EksOidcService oidcService, EksAccessEntryService accessEntries,
            EksPodIdentityAssociationService podIdentityAssociations) {
        this(storageFactory, config, regionResolver, clusterManager, ec2Service,
                oidcService, accessEntries, podIdentityAssociations, null);
    }

    public EksService(StorageFactory storageFactory, EmulatorConfig config,
            RegionResolver regionResolver, EksClusterManager clusterManager, Ec2Service ec2Service,
            EksOidcService oidcService, EksAccessEntryService accessEntries) {
        this(storageFactory, config, regionResolver, clusterManager, ec2Service,
                oidcService, accessEntries, null, null);
    }

    @PostConstruct
    public void init() {
        backfillOidcIdentities();
        backfillClusterSecurityGroups();
        backfillLogging();
        if (clusterManager != null && ec2Service != null) {
            clusterManager.addNodeRegistrationListener(nodeInst -> {
                String reg = nodeInst.getRegion() != null ? nodeInst.getRegion() : regionResolver.getRegion();
                ec2Service.restoreAttachedVolumesForInstance(reg, nodeInst);
            });
        }
        if (!config.services().eks().mock()) {
            restorePersistedClusters();
            startReadinessPoller();
        }
    }

    /**
     * Re-latches persisted clusters onto their k3s containers after a restart (#2609). Without
     * this, a cluster restored from {@code eks-clusters.json} reported ACTIVE but its container
     * was never restarted after a Docker daemon reboot, so every kubectl/deploy against it failed.
     * A surviving container is adopted (and started if stopped); a missing one is recreated
     * against the cluster's retained data volume. Restored clusters go back to CREATING so the
     * readiness poller re-verifies the API server and re-extracts the certificate authority
     * before marking them ACTIVE again.
     */
    private void restorePersistedClusters() {
        for (AccountAwareStorageBackend.AccountEntry<Cluster> entry : allClusterEntries()) {
            Cluster cluster = entry.value();
            if (cluster.getContainerId() != null
                    || (cluster.getStatus() != ClusterStatus.ACTIVE
                            && cluster.getStatus() != ClusterStatus.CREATING)) {
                continue;
            }
            // Cluster.accountId is @JsonIgnore, so a reloaded record carries none — the owning
            // account must come from the storage key, or a non-default account's cluster would be
            // written back under the default account (stale owner record + duplicate). Rehydrate
            // it on the record too, so the readiness poller's later put lands under the owner.
            if (cluster.getAccountId() == null) {
                cluster.setAccountId(entry.accountId());
            }
            // A persisted name that predates create-time validation may violate the AWS charset —
            // in particular contain a dot, which could spell out another account's qualified
            // Docker name and cross-bind its container. Such a record is never restored; the
            // cluster must be deleted and recreated under a valid name.
            if (cluster.getName() == null || !cluster.getName().matches(CLUSTER_NAME_REGEX)) {
                LOG.errorv("Not restoring EKS cluster \"{0}\" (account {1}): its persisted name "
                        + "violates the AWS charset and could alias another account''s Docker "
                        + "resources. Delete it and recreate it under a valid name.",
                        cluster.getName(), entry.accountId());
                cluster.setStatus(ClusterStatus.FAILED);
                putClusterForAccount(entry.accountId(), cluster);
                continue;
            }
            try {
                LOG.infov("Restoring k3s container for persisted EKS cluster {0}", cluster.getName());
                cluster.setStatus(ClusterStatus.CREATING);
                cluster.setPodCidr(EksClusterManager.DEFAULT_POD_CIDR);
                clusterManager.restoreCluster(cluster);
            } catch (Exception e) {
                if (!clusterManager.isDockerReachable()) {
                    // Same degradation as create: a restored cluster is metadata that stands on
                    // its own, and FAILED is reserved for errors AWS would also report.
                    LOG.warnv("No Docker daemon is reachable from Floci; restored EKS cluster {0} "
                            + "comes back as metadata only.", cluster.getName());
                    markMetadataOnlyActive(cluster);
                } else {
                    LOG.errorv("Failed to restore k3s container for EKS cluster {0}: {1}",
                            cluster.getName(), e.getMessage());
                    cluster.setStatus(ClusterStatus.FAILED);
                }
            }
            putClusterForAccount(entry.accountId(), cluster);
        }
    }

    private List<AccountAwareStorageBackend.AccountEntry<Cluster>> allClusterEntries() {
        if (storage instanceof AccountAwareStorageBackend<Cluster> aware) {
            return aware.scanAllAccountEntries(k -> true);
        }
        return storage.scan(k -> true).stream()
                .map(cluster -> new AccountAwareStorageBackend.AccountEntry<>(
                        cluster.getAccountId() != null ? cluster.getAccountId() : regionResolver.getAccountId(),
                        cluster.getName(), cluster))
                .toList();
    }

    /**
     * Gives clusters persisted before IRSA support an OIDC issuer and signing key. Without this,
     * a cluster restored from {@code eks-clusters.json} would report no
     * {@code identity.oidc.issuer}, and token minting and the JWKS routes would fail for it until
     * it was recreated.
     */
    private void backfillOidcIdentities() {
        for (AccountAwareStorageBackend.AccountEntry<Cluster> entry : allClusterEntries()) {
            Cluster cluster = entry.value();
            // Runs at startup with no request context, and Cluster.accountId is @JsonIgnore so a
            // reloaded record carries none — the owning account comes from the storage key and is
            // passed explicitly, or the account-scoped put()/get() would resolve to the default
            // account and strand a cluster (and its signing key) owned by any other one.
            String accountId = entry.accountId();
            if (cluster.getAccountId() == null) {
                cluster.setAccountId(accountId);
            }

            if (cluster.getIdentity() != null && cluster.getIdentity().getOidc() != null
                    && cluster.getIdentity().getOidc().getIssuer() != null) {
                oidcService.ensureKeyForAccount(accountId, cluster.getName(),
                        cluster.getIdentity().getOidc().getIssuer());
                continue;
            }
            String issuer = oidcService.newIssuerUrl(config.defaultRegion());
            cluster.setIdentity(new ClusterIdentity(new OidcIdentity(issuer)));
            oidcService.ensureKeyForAccount(accountId, cluster.getName(), issuer);
            putClusterForAccount(accountId, cluster);
            LOG.infov("Backfilled IRSA OIDC issuer for existing EKS cluster {0} in account {1}",
                    cluster.getName(), accountId);
        }
    }

    void putClusterForAccount(String accountId, Cluster cluster) {
        if (storage instanceof AccountAwareStorageBackend<Cluster> aware) {
            aware.putForAccount(accountId, cluster.getName(), cluster);
            return;
        }
        storage.put(cluster.getName(), cluster);
    }

    void deleteClusterForAccount(String accountId, String clusterName) {
        Cluster cluster = (storage instanceof AccountAwareStorageBackend<Cluster> aware)
                ? aware.getForAccount(accountId, clusterName).orElse(null)
                : storage.get(clusterName).orElse(null);
        clearAppliedClusterUserData(cluster, clusterName);
        if (cluster == null || cluster.getArn() == null) {
            String fallbackArn = AwsArnUtils.Arn.of("eks", config.defaultRegion(), accountId,
                    "cluster/" + clusterName).toString();
            appliedClusterUserData.remove(fallbackArn);
        }
        if (storage instanceof AccountAwareStorageBackend<Cluster> aware) {
            aware.deleteForAccount(accountId, clusterName);
            return;
        }
        storage.delete(clusterName);
    }

    private void clearAppliedClusterUserData(Cluster cluster, String fallbackName) {
        if (cluster != null) {
            if (cluster.getArn() != null) {
                appliedClusterUserData.remove(cluster.getArn());
            }
            if (cluster.getName() != null) {
                appliedClusterUserData.remove(cluster.getName());
            }
        }
        if (fallbackName != null) {
            appliedClusterUserData.remove(fallbackName);
        }
    }

    private SecurityGroup createClusterSecurityGroup(String region, String clusterName, String vpcId) {
        String suffix = randomHex(8);
        String groupName = "eks-cluster-sg-" + clusterName + "-" + suffix;
        SecurityGroup sg = ec2Service.createSecurityGroup(region, groupName, CLUSTER_SG_DESCRIPTION, vpcId);
        try {
            List<Tag> tags = List.of(
                    new Tag("Name", groupName),
                    new Tag("kubernetes.io/cluster/" + clusterName, "owned"),
                    new Tag("aws:eks:cluster-name", clusterName)
            );
            ec2Service.createTags(region, List.of(sg.getGroupId()), tags);

            UserIdGroupPair selfPair = new UserIdGroupPair();
            selfPair.setGroupId(sg.getGroupId());

            IpPermission selfIngress = new IpPermission();
            selfIngress.setIpProtocol("-1");
            selfIngress.setUserIdGroupPairs(List.of(selfPair));
            ec2Service.authorizeSecurityGroupIngress(region, sg.getGroupId(), List.of(selfIngress));

            IpPermission selfEgress = new IpPermission();
            selfEgress.setIpProtocol("-1");
            selfEgress.setUserIdGroupPairs(List.of(selfPair));
            ec2Service.authorizeSecurityGroupEgress(region, sg.getGroupId(), List.of(selfEgress));

            return sg;
        } catch (RuntimeException e) {
            try {
                ec2Service.deleteSecurityGroup(region, sg.getGroupId());
            } catch (Exception cleanupEx) {
                LOG.warnv("Failed to clean up cluster security group {0} after configuration failure: {1}",
                        sg.getGroupId(), cleanupEx.getMessage());
            }
            throw e;
        }
    }

    private void deleteClusterSecurityGroup(Cluster cluster) {
        if (ec2Service == null || cluster.getResourcesVpcConfig() == null) {
            return;
        }
        String sgId = cluster.getResourcesVpcConfig().getClusterSecurityGroupId();
        if (sgId == null || sgId.isBlank()) {
            return;
        }
        String region = resolveClusterRegion(cluster);
        try {
            ec2Service.deleteSecurityGroup(region, sgId);
        } catch (AwsException e) {
            if ("InvalidGroup.NotFound".equals(e.getErrorCode())) {
                LOG.debugv("Cluster security group {0} already gone, treating as deleted", sgId);
            } else {
                throw e;
            }
        }
    }

    void backfillClusterSecurityGroups() {
        if (ec2Service == null) {
            return;
        }
        for (AccountAwareStorageBackend.AccountEntry<Cluster> entry : allClusterEntries()) {
            Cluster cluster = entry.value();
            String accountId = entry.accountId();
            if (cluster.getAccountId() == null) {
                cluster.setAccountId(accountId);
            }

            ResourcesVpcConfig vpcConfig = cluster.getResourcesVpcConfig();
            if (vpcConfig == null) {
                continue;
            }
            if (vpcConfig.getClusterSecurityGroupId() != null && !vpcConfig.getClusterSecurityGroupId().isBlank()) {
                continue;
            }
            if (vpcConfig.getVpcId() == null || vpcConfig.getVpcId().isBlank()) {
                continue;
            }

            try {
                RequestScopes.runAs(accountId, () -> {
                    String region = resolveClusterRegion(cluster);
                    SecurityGroup sg = createClusterSecurityGroup(region, cluster.getName(), vpcConfig.getVpcId());
                    vpcConfig.setClusterSecurityGroupId(sg.getGroupId());
                    putClusterForAccount(accountId, cluster);
                    LOG.infov("Backfilled cluster security group {0} for existing EKS cluster {1} in account {2}",
                            sg.getGroupId(), cluster.getName(), accountId);
                });
            } catch (Exception e) {
                LOG.warnv("Could not backfill cluster security group for existing EKS cluster {0} in account {1}: {2}",
                        cluster.getName(), accountId, e.getMessage());
            }
        }
    }

    void backfillLogging() {
        for (AccountAwareStorageBackend.AccountEntry<Cluster> entry : allClusterEntries()) {
            Cluster cluster = entry.value();
            if (cluster.getLogging() != null) {
                continue;
            }
            String accountId = entry.accountId();
            if (cluster.getAccountId() == null) {
                cluster.setAccountId(accountId);
            }
            cluster.setLogging(defaultLogging());
            putClusterForAccount(accountId, cluster);
            LOG.infov("Backfilled default logging for existing EKS cluster {0} in account {1}",
                    cluster.getName(), accountId);
        }
    }

    private String resolveClusterRegion(Cluster cluster) {
        if (cluster.getArn() != null && !cluster.getArn().isBlank()) {
            try {
                return AwsArnUtils.parse(cluster.getArn()).region();
            } catch (IllegalArgumentException ignored) {
                // Not a valid ARN, fall back to configured region
            }
        }
        return config != null ? config.defaultRegion() : regionResolver.getRegion();
    }

    private static String randomHex(int len) {
        byte[] bytes = new byte[(len + 1) / 2];
        ThreadLocalRandom.current().nextBytes(bytes);
        return HexFormat.of().formatHex(bytes).substring(0, len);
    }

    @PreDestroy
    public void shutdown() {
        poller.shutdownNow();
        if (!config.services().eks().mock()) {
            boolean keepRunning = config.services().eks().keepRunningOnShutdown();
            for (Cluster cluster : allClusters()) {
                if (keepRunning) {
                    clusterManager.detachCluster(cluster);
                } else {
                    clusterManager.stopCluster(cluster);
                }
            }
        }
    }

    public Cluster createCluster(CreateClusterRequest request) {
        String name = request.getName();
        if (name == null || name.isBlank()) {
            throw new AwsException("InvalidParameterException", "Cluster name is required", 400);
        }
        // The AWS constraint on EKS cluster names. Enforcing it also guarantees no name can
        // contain the dot EksClusterManager uses to account-qualify Docker names, so a
        // default-account cluster name can never spell out another account's qualified name
        // and collide with its container or data volume.
        if (name.length() > 100 || !name.matches(CLUSTER_NAME_REGEX)) {
            throw new AwsException("InvalidParameterException",
                    "Value '" + name + "' at 'name' failed to satisfy constraint: Member must "
                            + "satisfy regular expression pattern: ^" + CLUSTER_NAME_REGEX + "$",
                    400);
        }
        if (storage.get(name).isPresent()) {
            throw new AwsException("ResourceInUseException",
                    "Cluster already exists: " + name, 409);
        }

        AccessConfig requestedAccess = request.getAccessConfig();
        String authenticationMode = requestedAccess == null || requestedAccess.authenticationMode() == null
                ? "CONFIG_MAP" : requestedAccess.authenticationMode();
        if (!Set.of("CONFIG_MAP", "API_AND_CONFIG_MAP", "API").contains(authenticationMode)) {
            throw new AwsException("InvalidParameterException", "Invalid authenticationMode", 400);
        }
        AccessConfig accessConfig = new AccessConfig(authenticationMode,
                requestedAccess == null || requestedAccess.bootstrapClusterCreatorAdminPermissions() == null
                        || requestedAccess.bootstrapClusterCreatorAdminPermissions());
        String region = regionResolver.getRegion();
        String resolvedVpcId = validateSubnetsAndResolveVpcId(region, request.getResourcesVpcConfig());
        String accountId = regionResolver.getAccountId();
        String arn = AwsArnUtils.Arn.of("eks", region, accountId, "cluster/" + name).toString();

        Cluster cluster = new Cluster();
        cluster.setName(name);
        cluster.setAccessConfig(accessConfig);
        cluster.setArn(arn);
        cluster.setAccountId(accountId);
        cluster.setCreatedAt(Instant.now());

        if (request.getVersion() != null && !request.getVersion().isBlank()) {
            String requestedVersion = request.getVersion().trim();
            Matcher matcher = K8S_VERSION_PATTERN.matcher(requestedVersion);
            if (!matcher.matches()) {
                throw new AwsException("InvalidParameterException",
                        "The specified parameter version is not valid: " + requestedVersion, 400);
            }
            int minor = Integer.parseInt(matcher.group(1));
            if (minor < MIN_SUPPORTED_K8S_MINOR) {
                throw new AwsException("InvalidParameterException",
                        "Unsupported Kubernetes version '" + requestedVersion + "'. Supported versions are 1."
                                + MIN_SUPPORTED_K8S_MINOR + " and above.", 400);
            }
            cluster.setVersion(requestedVersion);
            cluster.setExplicitVersion(true);
        } else {
            cluster.setVersion(DEFAULT_K8S_VERSION);
            cluster.setExplicitVersion(false);
        }

        cluster.setRoleArn(request.getRoleArn());
        ResourcesVpcConfig vpcConfig = buildVpcConfigResponse(request.getResourcesVpcConfig(), resolvedVpcId);
        SecurityGroup clusterSg = null;
        if (ec2Service != null && !vpcConfig.getVpcId().isBlank()) {
            try {
                clusterSg = createClusterSecurityGroup(region, name, vpcConfig.getVpcId());
                vpcConfig.setClusterSecurityGroupId(clusterSg.getGroupId());
            } catch (AwsException e) {
                if ("InvalidVpcID.NotFound".equals(e.getErrorCode())) {
                    throw new AwsException("InvalidParameterException",
                            "VPC '" + vpcConfig.getVpcId() + "' does not exist", 400);
                }
                throw e;
            }
        }
        cluster.setResourcesVpcConfig(vpcConfig);
        String vpcCidr = resolveClusterVpcCidr(region, vpcConfig.getVpcId());
        cluster.setKubernetesNetworkConfig(buildNetworkConfig(request.getKubernetesNetworkConfig(), vpcCidr));
        cluster.setPodCidr(EksClusterManager.DEFAULT_POD_CIDR);
        cluster.setLogging(buildLogging(request.getLogging()));
        cluster.setEncryptionConfig(buildEncryptionConfig(request.getEncryptionConfig()));
        cluster.setStatus(ClusterStatus.CREATING);
        cluster.setTags(request.getTags() != null ? new HashMap<>(request.getTags()) : new HashMap<>());
        cluster.setPlatformVersion("eks.1");
        cluster.setCertificateAuthority(new CertificateAuthority(""));

        try {
            String issuer = oidcService.newIssuerUrl(region);
            cluster.setIdentity(new ClusterIdentity(new OidcIdentity(issuer)));
            oidcService.ensureKey(name, issuer);

            if (config.services().eks().mock()) {
                markMetadataOnlyActive(cluster);
            } else {
                try {
                    if (!clusterManager.tryStartCluster(cluster)) {
                        // No Docker daemon: the k3s control plane cannot run, but the cluster record is
                        // metadata that stands on its own. FAILED is reserved for provisioning errors
                        // AWS would also report, and would strand every IaC apply that polls for ACTIVE.
                        markMetadataOnlyActive(cluster);
                    }
                } catch (Exception e) {
                    LOG.errorv("Failed to start k3s container for cluster {0}: {1}", name, e.getMessage());
                    cluster.setStatus(ClusterStatus.FAILED);
                }
            }

            storage.put(name, cluster);
        } catch (RuntimeException e) {
            if (clusterSg != null) {
                try {
                    ec2Service.deleteSecurityGroup(region, clusterSg.getGroupId());
                } catch (Exception cleanupEx) {
                    LOG.warnv("Failed to clean up cluster security group {0} after cluster creation failure: {1}",
                            clusterSg.getGroupId(), cleanupEx.getMessage());
                }
            }
            throw e;
        }

        return cluster;
    }

    /**
     * Marks a cluster ACTIVE with no Kubernetes API server behind it, the shape used by mock mode
     * and by a Floci that cannot reach a Docker daemon. Every EKS API Floci implements is control
     * plane (clusters, nodegroups, Fargate profiles, tags) and keeps working; the empty
     * certificateAuthority is what tells a caller no real cluster is listening on the endpoint.
     */
    private void markMetadataOnlyActive(Cluster cluster) {
        cluster.setStatus(ClusterStatus.ACTIVE);
        cluster.setEndpoint("https://localhost:" + config.services().eks().apiServerBasePort());
    }

    public Optional<Cluster> findAuthenticationCluster(String accountId, String name) {
        return storage instanceof AccountAwareStorageBackend<Cluster> aware
                ? aware.getForAccount(accountId, name) : storage.get(name);
    }

    public Cluster describeCluster(String name) {
        Cluster cluster = storage.get(name)
                .orElseThrow(() -> new AwsException("ResourceNotFoundException",
                        "No cluster found for name: " + name, 404));
        if (cluster.getLogging() == null) {
            cluster.setLogging(defaultLogging());
        }
        return cluster;
    }

    public List<String> listClusters() {
        return storage.scan(k -> true).stream()
                .map(Cluster::getName)
                .collect(Collectors.toList());
    }

    public Cluster deleteCluster(String name) {
        Cluster cluster = storage.get(name)
                .orElseThrow(() -> new AwsException("ResourceNotFoundException",
                        "No cluster found for name: " + name, 404));

        cluster.setStatus(ClusterStatus.DELETING);
        if (!config.services().eks().mock()) {
            clusterManager.stopCluster(cluster);
        }
        deleteClusterSecurityGroup(cluster);
        accessEntries.deleteClusterEntries(cluster);
        if (podIdentityAssociations != null) {
            podIdentityAssociations.deleteClusterAssociations(cluster);
        }
        if (addons != null) {
            addons.deleteClusterAddons(cluster);
        }
        storage.delete(name);
        clearAppliedClusterUserData(cluster, name);
        oidcService.deleteKey(name);
        return cluster;
    }

    public Nodegroup createNodeGroup(String clusterName, CreateNodeGroupRequest request) {
        Nodegroup nodegroup = new Nodegroup();
        nodegroup.setNodegroupName(request.getNodegroupName());
        nodegroup.setVersion(request.getVersion());
        nodegroup.setReleaseVersion(request.getReleaseVersion());
        nodegroup.setSubnets(request.getSubnets());
        nodegroup.setNodeRole(request.getNodeRole());
        nodegroup.setAmiType(request.getAmiType());
        nodegroup.setCapacityType(request.getCapacityType());
        nodegroup.setDiskSize(request.getDiskSize());
        nodegroup.setInstanceTypes(request.getInstanceTypes());
        nodegroup.setScalingConfig(request.getScalingConfig());
        nodegroup.setUpdateConfig(request.getUpdateConfig());
        nodegroup.setRemoteAccess(request.getRemoteAccess());
        nodegroup.setTaints(request.getTaints());
        nodegroup.setLaunchTemplate(request.getLaunchTemplate());
        nodegroup.setNodeRepairConfig(request.getNodeRepairConfig());
        nodegroup.setWarmPoolConfig(request.getWarmPoolConfig());
        nodegroup.setLabels(request.getLabels());
        nodegroup.setTags(request.getTags());
        nodegroup.setClientRequestToken(request.getClientRequestToken());
        return createNodeGroup(clusterName, nodegroup);
    }

    public Nodegroup createNodeGroup(String clusterName, Nodegroup request) {
        Cluster cluster = describeCluster(clusterName);

        String nodegroupName = request.getNodegroupName();
        if (nodegroupName == null || nodegroupName.isBlank()) {
            throw new AwsException("InvalidParameterException", "Nodegroup name is required", 400);
        }
        if (request.getNodeRole() == null || request.getNodeRole().isBlank()) {
            throw new AwsException("InvalidParameterException", "nodeRole is required", 400);
        }
        if (request.getSubnets() == null || request.getSubnets().isEmpty()) {
            throw new AwsException("InvalidParameterException", "subnets are required", 400);
        }

        String storageKey = nodeGroupKey(clusterName, nodegroupName);
        if (nodeGroupStorage.get(storageKey).isPresent()) {
            throw new AwsException("ResourceInUseException",
                    "Nodegroup already exists: " + nodegroupName, 409);
        }

        String region = resolveClusterRegion(cluster);
        LaunchTemplateData launchTemplateData = validateLaunchTemplate(region, request.getLaunchTemplate());

        String accountId = regionResolver.getAccountId();
        String id = UUID.randomUUID().toString();
        String arn = AwsArnUtils.Arn.of("eks", region, accountId,
                "nodegroup/" + clusterName + "/" + nodegroupName + "/" + id).toString();

        Instant now = Instant.now();
        Nodegroup nodeGroup = new Nodegroup();
        nodeGroup.setNodegroupName(nodegroupName);
        nodeGroup.setNodegroupArn(arn);
        nodeGroup.setClusterName(clusterName);
        nodeGroup.setAccountId(accountId);
        nodeGroup.setCreatedAt(now);
        nodeGroup.setModifiedAt(now);
        String resolvedVersion = request.getVersion() != null ? request.getVersion() : cluster.getVersion();
        nodeGroup.setVersion(resolvedVersion);
        nodeGroup.setReleaseVersion(request.getReleaseVersion() != null
                ? request.getReleaseVersion() : resolvedVersion + "-eks-1");
        nodeGroup.setStatus(NodegroupStatus.ACTIVE);
        nodeGroup.setCapacityType(request.getCapacityType() != null ? request.getCapacityType() : "ON_DEMAND");
        nodeGroup.setScalingConfig(request.getScalingConfig() != null ? request.getScalingConfig() : defaultScalingConfig());
        nodeGroup.setInstanceTypes(request.getInstanceTypes() != null ? request.getInstanceTypes() : List.of("t3.medium"));
        nodeGroup.setSubnets(request.getSubnets() != null ? request.getSubnets() : List.of());
        nodeGroup.setAmiType(request.getAmiType() != null ? request.getAmiType() : "AL2_x86_64");
        nodeGroup.setNodeRole(request.getNodeRole());
        nodeGroup.setDiskSize(request.getDiskSize() != null ? request.getDiskSize() : 20);
        nodeGroup.setResources(defaultNodeGroupResources(nodegroupName));
        nodeGroup.setHealth(defaultNodeGroupHealth());
        nodeGroup.setUpdateConfig(request.getUpdateConfig() != null ? request.getUpdateConfig() : defaultUpdateConfig());
        // Echoed back verbatim, and left unset when absent: EKS omits these rather than returning
        // an explicit null, and a null is drift to a caller diffing against its declared config.
        nodeGroup.setRemoteAccess(request.getRemoteAccess());
        nodeGroup.setTaints(request.getTaints());
        nodeGroup.setLaunchTemplate(request.getLaunchTemplate());
        nodeGroup.setNodeRepairConfig(request.getNodeRepairConfig());
        nodeGroup.setWarmPoolConfig(request.getWarmPoolConfig());
        nodeGroup.setLabels(request.getLabels() != null ? new HashMap<>(request.getLabels()) : null);
        nodeGroup.setTags(request.getTags() != null ? new HashMap<>(request.getTags()) : new HashMap<>());

        if (launchTemplateData != null && launchTemplateData.getUserData() != null
                && !launchTemplateData.getUserData().isBlank()) {
            applyNodeGroupUserData(cluster, nodegroupName, launchTemplateData.getUserData(), nodeGroup);
        }

        nodeGroupStorage.put(storageKey, nodeGroup);
        return nodeGroup;
    }

    private LaunchTemplateData validateLaunchTemplate(String region, Object launchTemplateObj) {
        if (!(launchTemplateObj instanceof Map<?, ?> map)) {
            return null;
        }

        String id = asNonBlankString(map.get("id"));
        String name = asNonBlankString(map.get("name"));
        String version = asNonBlankString(map.get("version"));

        if ((id != null && name != null) || (id == null && name == null)) {
            throw new AwsException("InvalidParameterException",
                    "You must specify either the launch template ID or the launch template name in the request, but not both.",
                    400);
        }

        try {
            return ec2Service.resolveLaunchTemplateData(region, id, name, version);
        } catch (AwsException e) {
            switch (e.getErrorCode()) {
                case "InvalidLaunchTemplateId.NotFound", "InvalidLaunchTemplateName.NotFoundException" ->
                    throw new AwsException("InvalidParameterException",
                            "Launch template could not be found : " + e.getMessage(), 400);
                case "InvalidLaunchTemplateVersion.NotFound", "InvalidLaunchTemplateVersion.Malformed" ->
                    throw new AwsException("InvalidParameterException", e.getMessage(), 400);
                default -> throw e;
            }
        }
    }

    private static String asNonBlankString(Object val) {
        if (val == null) {
            return null;
        }
        String s = val.toString().trim();
        return s.isEmpty() ? null : s;
    }

    public Nodegroup describeNodeGroup(String clusterName, String nodegroupName) {
        describeCluster(clusterName);
        return nodeGroupStorage.get(nodeGroupKey(clusterName, nodegroupName))
                .orElseThrow(() -> new AwsException("ResourceNotFoundException",
                        "No nodegroup found for name: " + nodegroupName, 404));
    }

    public List<String> listNodeGroups(String clusterName) {
        describeCluster(clusterName);
        String prefix = clusterName + "/";
        return nodeGroupStorage.scan(key -> key.startsWith(prefix)).stream()
                .map(Nodegroup::getNodegroupName)
                .collect(Collectors.toList());
    }

    public Nodegroup deleteNodeGroup(String clusterName, String nodegroupName) {
        Nodegroup nodeGroup = describeNodeGroup(clusterName, nodegroupName);
        nodeGroup.setStatus(NodegroupStatus.DELETING);
        nodeGroup.setModifiedAt(Instant.now());
        nodeGroupStorage.delete(nodeGroupKey(clusterName, nodegroupName));
        return nodeGroup;
    }

    public FargateProfile createFargateProfile(String clusterName, CreateFargateProfileRequest request) {
        describeCluster(clusterName);

        String fargateProfileName = request.getFargateProfileName();
        if (fargateProfileName == null || fargateProfileName.isBlank()) {
            throw new AwsException("InvalidParameterException", "Fargate profile name is required", 400);
        }
        if (request.getPodExecutionRoleArn() == null || request.getPodExecutionRoleArn().isBlank()) {
            throw new AwsException("InvalidParameterException", "podExecutionRoleArn is required", 400);
        }

        String storageKey = fargateProfileKey(clusterName, fargateProfileName);
        if (fargateProfileStorage.get(storageKey).isPresent()) {
            throw new AwsException("ResourceInUseException",
                    "Fargate profile already exists: " + fargateProfileName, 409);
        }

        String region = config.defaultRegion();
        String accountId = regionResolver.getAccountId();
        String id = UUID.randomUUID().toString();
        String arn = AwsArnUtils.Arn.of("eks", region, accountId,
                "fargateprofile/" + clusterName + "/" + fargateProfileName + "/" + id).toString();

        FargateProfile profile = new FargateProfile();
        profile.setFargateProfileName(fargateProfileName);
        profile.setFargateProfileArn(arn);
        profile.setClusterName(clusterName);
        profile.setAccountId(accountId);
        profile.setCreatedAt(Instant.now());
        profile.setPodExecutionRoleArn(request.getPodExecutionRoleArn());
        profile.setSubnets(request.getSubnets() != null ? request.getSubnets() : List.of());
        profile.setSelectors(request.getSelectors() != null ? request.getSelectors() : List.of());
        profile.setStatus(FargateProfileStatus.ACTIVE);
        profile.setHealth(defaultFargateProfileHealth());
        profile.setTags(request.getTags() != null ? new HashMap<>(request.getTags()) : new HashMap<>());

        fargateProfileStorage.put(storageKey, profile);
        return profile;
    }

    public FargateProfile describeFargateProfile(String clusterName, String fargateProfileName) {
        describeCluster(clusterName);
        return fargateProfileStorage.get(fargateProfileKey(clusterName, fargateProfileName))
                .orElseThrow(() -> new AwsException("ResourceNotFoundException",
                        "No fargate profile found for name: " + fargateProfileName, 404));
    }

    public List<String> listFargateProfiles(String clusterName) {
        describeCluster(clusterName);
        String prefix = clusterName + "/";
        return fargateProfileStorage.scan(key -> key.startsWith(prefix)).stream()
                .map(FargateProfile::getFargateProfileName)
                .collect(Collectors.toList());
    }

    public FargateProfile deleteFargateProfile(String clusterName, String fargateProfileName) {
        FargateProfile profile = describeFargateProfile(clusterName, fargateProfileName);
        profile.setStatus(FargateProfileStatus.DELETING);
        fargateProfileStorage.delete(fargateProfileKey(clusterName, fargateProfileName));
        return profile;
    }

    @Override
    public String serviceKey() {
        return "eks";
    }

    @Override
    public void tagResource(String region, String resourceArn, Map<String, String> tags) {
        String clusterName = extractClusterName(resourceArn);
        Cluster cluster = storage.get(clusterName)
                .orElseThrow(() -> new AwsException("ResourceNotFoundException",
                        "Resource not found: " + resourceArn, 404));

        if (cluster.getTags() == null) {
            cluster.setTags(new HashMap<>());
        }
        cluster.getTags().putAll(tags);
        storage.put(clusterName, cluster);
    }

    @Override
    public void untagResource(String region, String resourceArn, List<String> tagKeys) {
        String clusterName = extractClusterName(resourceArn);
        Cluster cluster = storage.get(clusterName)
                .orElseThrow(() -> new AwsException("ResourceNotFoundException",
                        "Resource not found: " + resourceArn, 404));

        if (cluster.getTags() != null && tagKeys != null) {
            tagKeys.forEach(cluster.getTags()::remove);
        }
        storage.put(clusterName, cluster);
    }

    @Override
    public Map<String, String> listTags(String region, String resourceArn) {
        String clusterName = extractClusterName(resourceArn);
        Cluster cluster = storage.get(clusterName)
                .orElseThrow(() -> new AwsException("ResourceNotFoundException",
                        "Resource not found: " + resourceArn, 404));

        return cluster.getTags() != null ? cluster.getTags() : Map.of();
    }

    public void tagResource(String resourceArn, Map<String, String> tags) {
        tagResource(null, resourceArn, tags);
    }

    public void untagResource(String resourceArn, List<String> tagKeys) {
        untagResource(null, resourceArn, tagKeys);
    }

    public Map<String, String> listTagsForResource(String resourceArn) {
        return listTags(null, resourceArn);
    }

    private String extractClusterName(String resourceArn) {
        // arn:aws:eks:us-east-1:000000000000:cluster/my-cluster
        int idx = resourceArn.lastIndexOf('/');
        if (idx < 0 || idx == resourceArn.length() - 1) {
            throw new AwsException("InvalidParameterException",
                    "Invalid resource ARN: " + resourceArn, 400);
        }
        return resourceArn.substring(idx + 1);
    }

    /**
     * Validates every requested subnet and returns the VPC they belong to.
     *
     * CreateCluster carries no vpcId — real EKS derives it from the subnets, and
     * #1942 reported resourcesVpcConfig.vpcId coming back blank because the
     * Subnet that requireSubnet already resolves was discarded here.
     *
     * @return the vpcId of the requested subnets, or null when none were given
     */
    private String validateSubnetsAndResolveVpcId(String region, ResourcesVpcConfig vpcConfig) {
        if (vpcConfig == null || vpcConfig.getSubnetIds() == null) {
            return null;
        }
        String vpcId = null;
        for (String subnetId : vpcConfig.getSubnetIds()) {
            try {
                vpcId = ec2Service.requireSubnet(region, subnetId).getVpcId();
            } catch (AwsException e) {
                throw new AwsException("InvalidParameterException",
                        "Subnet ID '" + subnetId + "' does not exist", 400);
            }
        }
        return vpcId;
    }

    private ResourcesVpcConfig buildVpcConfigResponse(ResourcesVpcConfig request, String resolvedVpcId) {
        ResourcesVpcConfig response = new ResourcesVpcConfig();
        if (request != null) {
            response.setSubnetIds(request.getSubnetIds() != null ? request.getSubnetIds() : List.of());
            response.setSecurityGroupIds(request.getSecurityGroupIds() != null ? request.getSecurityGroupIds() : List.of());
            // A caller-supplied vpcId still wins; otherwise fall back to the one
            // the subnets resolved to, and only then to empty.
            String vpcId = request.getVpcId() != null && !request.getVpcId().isBlank()
                    ? request.getVpcId()
                    : (resolvedVpcId != null ? resolvedVpcId : "");
            response.setVpcId(vpcId);
            response.setEndpointPublicAccess(
                    request.getEndpointPublicAccess() != null ? request.getEndpointPublicAccess() : Boolean.TRUE);
            response.setEndpointPrivateAccess(
                    request.getEndpointPrivateAccess() != null ? request.getEndpointPrivateAccess() : Boolean.FALSE);
            response.setPublicAccessCidrs(
                    request.getPublicAccessCidrs() != null ? request.getPublicAccessCidrs() : List.of("0.0.0.0/0"));
        } else {
            response.setSubnetIds(List.of());
            response.setSecurityGroupIds(List.of());
            response.setVpcId("");
            response.setEndpointPublicAccess(Boolean.TRUE);
            response.setEndpointPrivateAccess(Boolean.FALSE);
            response.setPublicAccessCidrs(List.of("0.0.0.0/0"));
        }
        return response;
    }

    public static final String DEFAULT_SERVICE_IPV4_CIDR = "10.100.0.0/16";
    public static final String ALTERNATIVE_SERVICE_IPV4_CIDR = "172.20.0.0/16";
    public static final String DEFAULT_K8S_VERSION = "1.29";
    public static final Pattern K8S_VERSION_PATTERN = Pattern.compile("^1\\.(\\d+)$");
    public static final int MIN_SUPPORTED_K8S_MINOR = 28;

    static void validateServiceIpv4Cidr(String cidr) {
        if (cidr == null || !SecurityGroupPolicy.validCidr(cidr)) {
            throw new AwsException("InvalidParameterException",
                    "The specified parameter kubernetesNetworkConfig.serviceIpv4Cidr is not valid: " + cidr, 400);
        }
        int slash = cidr.indexOf('/');
        int prefix;
        try {
            prefix = Integer.parseInt(cidr.substring(slash + 1));
        } catch (NumberFormatException e) {
            throw new AwsException("InvalidParameterException",
                    "The specified parameter kubernetesNetworkConfig.serviceIpv4Cidr is not valid: " + cidr, 400);
        }
        if (prefix < 12 || prefix > 24) {
            throw new AwsException("InvalidParameterException",
                    "The specified parameter kubernetesNetworkConfig.serviceIpv4Cidr must have a prefix between /12 and /24: " + cidr, 400);
        }
        if (!Ipv4Cidrs.contains("10.0.0.0/8", cidr)
                && !Ipv4Cidrs.contains("172.16.0.0/12", cidr)
                && !Ipv4Cidrs.contains("192.168.0.0/16", cidr)) {
            throw new AwsException("InvalidParameterException",
                    "The specified parameter kubernetesNetworkConfig.serviceIpv4Cidr must fall within RFC 1918 private address ranges: " + cidr, 400);
        }
    }

    String resolveClusterVpcCidr(String region, String vpcId) {
        if (ec2Service != null && vpcId != null && !vpcId.isBlank()) {
            try {
                Vpc vpc = ec2Service.requireVpc(region, vpcId);
                if (vpc.getCidrBlock() != null && !vpc.getCidrBlock().isBlank()) {
                    return vpc.getCidrBlock();
                }
            } catch (Exception e) {
                LOG.debugv("Could not resolve VPC CIDR for vpc {0}: {1}", vpcId, e.getMessage());
            }
        }
        return null;
    }

    private KubernetesNetworkConfig buildNetworkConfig(KubernetesNetworkConfig request, String vpcCidr) {
        KubernetesNetworkConfig config = new KubernetesNetworkConfig();
        String requestedCidr = request != null ? request.getServiceIpv4Cidr() : null;
        String resolvedCidr;
        if (requestedCidr != null && !requestedCidr.isBlank()) {
            validateServiceIpv4Cidr(requestedCidr);
            if (vpcCidr != null && Ipv4Cidrs.overlaps(requestedCidr, vpcCidr)) {
                throw new AwsException("InvalidParameterException",
                        "The specified service IPv4 CIDR " + requestedCidr + " overlaps with the VPC CIDR " + vpcCidr, 400);
            }
            resolvedCidr = requestedCidr;
        } else {
            if (vpcCidr != null && Ipv4Cidrs.overlaps(DEFAULT_SERVICE_IPV4_CIDR, vpcCidr)) {
                if (Ipv4Cidrs.overlaps(ALTERNATIVE_SERVICE_IPV4_CIDR, vpcCidr)) {
                    throw new AwsException("InvalidParameterException",
                            "Default service IPv4 CIDR blocks (10.100.0.0/16 and 172.20.0.0/16) overlap with the VPC CIDR "
                                    + vpcCidr + ". Please specify a non-overlapping serviceIpv4Cidr.", 400);
                }
                resolvedCidr = ALTERNATIVE_SERVICE_IPV4_CIDR;
            } else {
                resolvedCidr = DEFAULT_SERVICE_IPV4_CIDR;
            }
        }
        config.setServiceIpv4Cidr(resolvedCidr);
        config.setIpFamily(request != null && request.getIpFamily() != null ? request.getIpFamily() : "ipv4");
        return config;
    }

    static Logging defaultLogging() {
        return new Logging(List.of(new LogSetup(new ArrayList<>(ALL_LOG_TYPES), false)));
    }

    private Logging buildLogging(Logging requestedLogging) {
        if (requestedLogging == null || requestedLogging.getClusterLogging() == null
                || requestedLogging.getClusterLogging().isEmpty()) {
            return defaultLogging();
        }

        Set<String> enabledTypes = new LinkedHashSet<>();
        Set<String> specifiedTypes = new HashSet<>();

        for (LogSetup setup : requestedLogging.getClusterLogging()) {
            if (setup == null || setup.getTypes() == null) {
                continue;
            }
            for (String type : setup.getTypes()) {
                if (!ALL_LOG_TYPES.contains(type)) {
                    throw new AwsException("InvalidParameterException",
                            "'" + type + "' is not a valid log type", 400);
                }
                specifiedTypes.add(type);
                if (Boolean.TRUE.equals(setup.getEnabled())) {
                    enabledTypes.add(type);
                } else {
                    enabledTypes.remove(type);
                }
            }
        }

        if (specifiedTypes.isEmpty()) {
            return defaultLogging();
        }

        List<String> enabledList = ALL_LOG_TYPES.stream()
                .filter(enabledTypes::contains)
                .collect(Collectors.toList());
        List<String> disabledList = ALL_LOG_TYPES.stream()
                .filter(t -> !enabledTypes.contains(t))
                .collect(Collectors.toList());

        List<LogSetup> entries = new ArrayList<>();
        if (!enabledList.isEmpty()) {
            entries.add(new LogSetup(enabledList, true));
        }
        if (!disabledList.isEmpty()) {
            entries.add(new LogSetup(disabledList, false));
        }

        return new Logging(entries);
    }

    private List<EncryptionConfig> buildEncryptionConfig(List<EncryptionConfig> requestedConfigs) {
        if (requestedConfigs == null || requestedConfigs.isEmpty()) {
            return null;
        }
        if (requestedConfigs.size() > 1) {
            throw new AwsException("InvalidParameterException",
                    "Only one encryption configuration is allowed", 400);
        }
        EncryptionConfig config = requestedConfigs.getFirst();
        if (config == null || config.getResources() == null || config.getResources().isEmpty()
                || !List.of("secrets").equals(config.getResources())) {
            throw new AwsException("InvalidParameterException",
                    "Invalid k8s resource and provider for encryption", 400);
        }
        if (config.getProvider() == null || config.getProvider().getKeyArn() == null
                || config.getProvider().getKeyArn().isBlank()) {
            throw new AwsException("InvalidParameterException",
                    "Invalid k8s resource and provider for encryption", 400);
        }
        return List.of(new EncryptionConfig(List.of("secrets"), new Provider(config.getProvider().getKeyArn())));
    }

    private String nodeGroupKey(String clusterName, String nodegroupName) {
        return clusterName + "/" + nodegroupName;
    }

    private String fargateProfileKey(String clusterName, String fargateProfileName) {
        return clusterName + "/" + fargateProfileName;
    }

    private NodegroupScalingConfig defaultScalingConfig() {
        NodegroupScalingConfig scalingConfig = new NodegroupScalingConfig();
        scalingConfig.setMinSize(1);
        scalingConfig.setMaxSize(1);
        scalingConfig.setDesiredSize(1);
        return scalingConfig;
    }

    private Map<String, Integer> defaultUpdateConfig() {
        return Map.of("maxUnavailable", 1);
    }

    private Map<String, Object> defaultNodeGroupResources(String nodegroupName) {
        Map<String, Object> resources = new LinkedHashMap<>();
        Map<String, Object> autoScalingGroup = new LinkedHashMap<>();
        autoScalingGroup.put("name", "eks-" + nodegroupName + "-" + UUID.randomUUID().toString().substring(0, 8));
        resources.put("autoScalingGroups", List.of(autoScalingGroup));
        return resources;
    }

    private Map<String, List<Object>> defaultNodeGroupHealth() {
        Map<String, List<Object>> health = new LinkedHashMap<>();
        health.put("issues", new ArrayList<>());
        return health;
    }

    private Map<String, List<Object>> failedNodeGroupHealth(String nodegroupName, String message) {
        Map<String, List<Object>> health = new LinkedHashMap<>();
        Map<String, Object> issue = new LinkedHashMap<>();
        issue.put("code", "NodeCreationFailure");
        issue.put("message", message);
        issue.put("resourceIds", List.of(nodegroupName));
        health.put("issues", List.of(issue));
        return health;
    }

    static volatile Runnable userDataClaimWaitingHook;

    private void applyNodeGroupUserData(Cluster cluster, String nodegroupName, String userData, Nodegroup nodeGroup) {
        String clusterKey = cluster.getArn() != null ? cluster.getArn() : cluster.getName();
        CompletableFuture<UserDataPipeline.ExecutionResult> future = new CompletableFuture<>();
        UserDataClaim claim = new UserDataClaim(userData, future);

        // putIfAbsent claims the slot atomically. If another caller is already executing, we wait
        // for their execution to complete so that failure in the winner propagates to all racing
        // nodegroups rather than letting them proceed ACTIVE on a failed bootstrap.
        UserDataClaim existing = appliedClusterUserData.putIfAbsent(clusterKey, claim);
        if (existing != null) {
            Runnable hook = userDataClaimWaitingHook;
            if (hook != null) {
                hook.run();
            }

            UserDataPipeline.ExecutionResult winnerResult;
            try {
                winnerResult = existing.future().join();
            } catch (Throwable t) {
                Throwable cause = t.getCause() != null ? t.getCause() : t;
                winnerResult = UserDataPipeline.ExecutionResult.failed(
                        -1L, 1, 1, "UserData execution failed: " + cause.getMessage());
            }

            if (winnerResult != null && !winnerResult.isSuccess()) {
                failNodeGroupUserData(nodeGroup, nodegroupName, cluster, winnerResult);
                return;
            }

            if (existing.userData().equals(userData)) {
                LOG.infov("Nodegroup {0} specifies identical launch template user data already applied to cluster {1}; skipping",
                        nodegroupName, cluster.getName());
            } else {
                LOG.warnv("Nodegroup {0} specifies launch template user data that differs from previously applied user data for cluster {1}; skipping execution because Floci runs a single shared container per cluster",
                        nodegroupName, cluster.getName());
            }
            return;
        }

        if (clusterManager == null) {
            claim.future().complete(UserDataPipeline.ExecutionResult.skipped("No cluster manager"));
            return;
        }

        UserDataPipeline.ExecutionResult result = null;
        try {
            result = clusterManager.executeUserData(cluster, nodegroupName, userData);
            if (result == null) {
                result = UserDataPipeline.ExecutionResult.skipped("UserData execution returned no result");
            }
            claim.future().complete(result);
        } catch (Throwable t) {
            claim.future().completeExceptionally(t);
            appliedClusterUserData.remove(clusterKey, claim);
            throw t;
        } finally {
            if (result != null && !result.isSuccess()) {
                // Release the claim so a later node group can retry the bootstrap. The conditional
                // remove only drops our own claim, not one a concurrent delete/recreate or a newer
                // caller has since put in its place.
                appliedClusterUserData.remove(clusterKey, claim);
            }
        }

        if (result != null && !result.isSuccess()) {
            failNodeGroupUserData(nodeGroup, nodegroupName, cluster, result);
        }
    }

    private void failNodeGroupUserData(Nodegroup nodeGroup, String nodegroupName, Cluster cluster,
            UserDataPipeline.ExecutionResult result) {
        nodeGroup.setStatus(NodegroupStatus.CREATE_FAILED);
        String failureDetail = result != null && result.getFailureMessage() != null
                ? result.getFailureMessage()
                : "UserData execution failed for EKS cluster " + cluster.getName();
        nodeGroup.setHealth(failedNodeGroupHealth(nodegroupName, failureDetail));
        LOG.warnv("Nodegroup {0} failed to execute launch template user data: {1}",
                nodegroupName, failureDetail);
    }

    private FargateProfile.Health defaultFargateProfileHealth() {
        FargateProfile.Health health = new FargateProfile.Health();
        health.setIssues(List.of());
        return health;
    }

    private void startReadinessPoller() {
        poller.scheduleAtFixedRate(() -> {
            try {
                for (Cluster cluster : allClusters()) {
                    if (cluster.getStatus() == ClusterStatus.CREATING) {
                        if (clusterManager.isReady(cluster)) {
                            LOG.infov("EKS cluster {0} is now ACTIVE", cluster.getName());
                            clusterManager.finalizeCluster(cluster);
                            cluster.setStatus(ClusterStatus.ACTIVE);
                            putCluster(cluster);
                        }
                    }
                }
            } catch (Exception e) {
                LOG.error("Error in EKS readiness poller", e);
            }
        }, 2, 3, TimeUnit.SECONDS);
    }

    public Optional<Cluster> findClusterByIssuer(String issuer) {
        if (issuer == null || issuer.isBlank()) {
            return Optional.empty();
        }
        return allClusters().stream()
                .filter(c -> c.getIdentity() != null && c.getIdentity().getOidc() != null
                        && issuer.equals(c.getIdentity().getOidc().getIssuer()))
                .findFirst();
    }

    private List<Cluster> allClusters() {
        if (storage instanceof AccountAwareStorageBackend<Cluster> aware) {
            return aware.scanAllAccounts();
        }
        return storage.scan(k -> true);
    }

    private void putCluster(Cluster cluster) {
        if (cluster.getAccountId() != null && storage instanceof AccountAwareStorageBackend<Cluster> aware) {
            aware.putForAccount(cluster.getAccountId(), cluster.getName(), cluster);
        } else {
            storage.put(cluster.getName(), cluster);
        }
    }

    // ─── Resource Explorer 2 ───────────────────────────────────────────────────

    @Override
    public List<ExplorerResource> getResources() {
        List<ExplorerResource> resources = new ArrayList<>();
        for (Cluster cluster : storage.scan(k -> true)) {
            String arn = cluster.getArn();
            if (arn == null) {
                continue;
            }
            AwsArnUtils.Arn parsed = AwsArnUtils.parse(arn);
            resources.add(new ExplorerResource(
                    arn, "eks:cluster", "eks",
                    parsed.region(), parsed.accountId(),
                    cluster.getCreatedAt() != null ? cluster.getCreatedAt() : Instant.now(),
                    cluster.getTags() != null ? cluster.getTags() : Map.of()));
        }
        return resources;
    }

    @Override
    public Set<SupportedResourceType> getSupportedResourceTypes() {
        return Set.of(new SupportedResourceType("eks:cluster", "eks", true));
    }
}
