package io.github.hectorvent.floci.services.eks;

import io.github.hectorvent.floci.config.EmulatorConfig;
import io.github.hectorvent.floci.core.common.AwsArnUtils;
import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.core.common.RegionResolver;
import io.github.hectorvent.floci.core.common.TagHandler;
import io.github.hectorvent.floci.core.storage.AccountAwareStorageBackend;
import io.github.hectorvent.floci.core.storage.StorageBackend;
import io.github.hectorvent.floci.core.storage.StorageFactory;
import io.github.hectorvent.floci.services.ec2.Ec2Service;
import io.github.hectorvent.floci.services.eks.model.CertificateAuthority;
import io.github.hectorvent.floci.services.eks.model.Cluster;
import io.github.hectorvent.floci.services.eks.model.ClusterIdentity;
import io.github.hectorvent.floci.services.eks.model.ClusterStatus;
import io.github.hectorvent.floci.services.eks.model.OidcIdentity;
import io.github.hectorvent.floci.services.eks.model.CreateClusterRequest;
import io.github.hectorvent.floci.services.eks.model.CreateFargateProfileRequest;
import io.github.hectorvent.floci.services.eks.model.CreateNodeGroupRequest;
import io.github.hectorvent.floci.services.eks.model.FargateProfile;
import io.github.hectorvent.floci.services.eks.model.FargateProfileStatus;
import io.github.hectorvent.floci.services.eks.model.KubernetesNetworkConfig;
import io.github.hectorvent.floci.services.eks.model.Nodegroup;
import io.github.hectorvent.floci.services.eks.model.NodegroupScalingConfig;
import io.github.hectorvent.floci.services.eks.model.NodegroupStatus;
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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;
import io.github.hectorvent.floci.core.resource.ExplorerResource;
import io.github.hectorvent.floci.core.resource.ResourceProvider;
import io.github.hectorvent.floci.core.resource.SupportedResourceType;
import java.util.Set;

@ApplicationScoped
public class EksService implements TagHandler, ResourceProvider {

    private static final Logger LOG = Logger.getLogger(EksService.class);

    /** The AWS charset for EKS cluster names. It admits no dot, which the Docker-name account
     *  qualifier relies on — see EksClusterManager#accountQualifiedName. */
    static final String CLUSTER_NAME_REGEX = "[0-9A-Za-z][A-Za-z0-9\\-_]*";

    private final StorageBackend<String, Cluster> storage;
    private final StorageBackend<String, Nodegroup> nodeGroupStorage;
    private final StorageBackend<String, FargateProfile> fargateProfileStorage;
    private final EmulatorConfig config;
    private final RegionResolver regionResolver;
    private final EksClusterManager clusterManager;
    private final Ec2Service ec2Service;
    private final EksOidcService oidcService;
    private final ScheduledExecutorService poller = Executors.newSingleThreadScheduledExecutor();

    @Inject
    public EksService(StorageFactory storageFactory, EmulatorConfig config,
            RegionResolver regionResolver, EksClusterManager clusterManager, Ec2Service ec2Service,
            EksOidcService oidcService) {
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
    }

    @PostConstruct
    public void init() {
        backfillOidcIdentities();
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
                clusterManager.restoreCluster(cluster);
            } catch (Exception e) {
                LOG.errorv("Failed to restore k3s container for EKS cluster {0}: {1}",
                        cluster.getName(), e.getMessage());
                cluster.setStatus(ClusterStatus.FAILED);
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

    private void putClusterForAccount(String accountId, Cluster cluster) {
        if (storage instanceof AccountAwareStorageBackend<Cluster> aware) {
            aware.putForAccount(accountId, cluster.getName(), cluster);
            return;
        }
        storage.put(cluster.getName(), cluster);
    }

    @PreDestroy
    public void shutdown() {
        poller.shutdownNow();
        if (!config.services().eks().mock()) {
            for (Cluster cluster : allClusters()) {
                clusterManager.stopCluster(cluster);
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

        String region = regionResolver.getRegion();
        String resolvedVpcId = validateSubnetsAndResolveVpcId(region, request.getResourcesVpcConfig());
        String accountId = regionResolver.getAccountId();
        String arn = AwsArnUtils.Arn.of("eks", region, accountId, "cluster/" + name).toString();

        Cluster cluster = new Cluster();
        cluster.setName(name);
        cluster.setArn(arn);
        cluster.setAccountId(accountId);
        cluster.setCreatedAt(Instant.now());
        cluster.setVersion(request.getVersion() != null ? request.getVersion() : "1.29");
        cluster.setRoleArn(request.getRoleArn());
        cluster.setResourcesVpcConfig(buildVpcConfigResponse(request.getResourcesVpcConfig(), resolvedVpcId));
        cluster.setKubernetesNetworkConfig(buildNetworkConfig(request.getKubernetesNetworkConfig()));
        cluster.setStatus(ClusterStatus.CREATING);
        cluster.setTags(request.getTags() != null ? new HashMap<>(request.getTags()) : new HashMap<>());
        cluster.setPlatformVersion("eks.1");
        cluster.setCertificateAuthority(new CertificateAuthority(""));

        String issuer = oidcService.newIssuerUrl(region);
        cluster.setIdentity(new ClusterIdentity(new OidcIdentity(issuer)));
        oidcService.ensureKey(name, issuer);

        if (config.services().eks().mock()) {
            cluster.setStatus(ClusterStatus.ACTIVE);
            cluster.setEndpoint("https://localhost:" + config.services().eks().apiServerBasePort());
        } else {
            try {
                clusterManager.startCluster(cluster);
            } catch (Exception e) {
                LOG.errorv("Failed to start k3s container for cluster {0}: {1}", name, e.getMessage());
                cluster.setStatus(ClusterStatus.FAILED);
            }
        }

        storage.put(name, cluster);
        return cluster;
    }

    public Cluster describeCluster(String name) {
        return storage.get(name)
                .orElseThrow(() -> new AwsException("ResourceNotFoundException",
                        "No cluster found for name: " + name, 404));
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
        storage.delete(name);
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

        String region = config.defaultRegion();
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
        nodeGroup.setLabels(request.getLabels() != null ? new HashMap<>(request.getLabels()) : null);
        nodeGroup.setTags(request.getTags() != null ? new HashMap<>(request.getTags()) : new HashMap<>());

        nodeGroupStorage.put(storageKey, nodeGroup);
        return nodeGroup;
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

    private KubernetesNetworkConfig buildNetworkConfig(KubernetesNetworkConfig request) {
        KubernetesNetworkConfig config = new KubernetesNetworkConfig();
        if (request != null) {
            config.setServiceIpv4Cidr(request.getServiceIpv4Cidr() != null ? request.getServiceIpv4Cidr() : "10.100.0.0/16");
            config.setIpFamily(request.getIpFamily() != null ? request.getIpFamily() : "ipv4");
        } else {
            config.setServiceIpv4Cidr("10.100.0.0/16");
            config.setIpFamily("ipv4");
        }
        return config;
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
