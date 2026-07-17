package io.github.hectorvent.floci.services.eks;

import io.github.hectorvent.floci.config.EmulatorConfig;
import io.github.hectorvent.floci.core.common.AwsArnUtils;
import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.core.common.RegionResolver;
import io.github.hectorvent.floci.core.common.TagHandler;
import io.github.hectorvent.floci.core.storage.AccountAwareStorageBackend;
import io.github.hectorvent.floci.core.storage.StorageBackend;
import io.github.hectorvent.floci.core.storage.StorageFactory;
import io.github.hectorvent.floci.services.eks.model.CertificateAuthority;
import io.github.hectorvent.floci.services.eks.model.Cluster;
import io.github.hectorvent.floci.services.eks.model.ClusterStatus;
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

@ApplicationScoped
public class EksService implements TagHandler {

    private static final Logger LOG = Logger.getLogger(EksService.class);

    private final StorageBackend<String, Cluster> storage;
    private final StorageBackend<String, Nodegroup> nodeGroupStorage;
    private final StorageBackend<String, FargateProfile> fargateProfileStorage;
    private final EmulatorConfig config;
    private final RegionResolver regionResolver;
    private final EksClusterManager clusterManager;
    private final ScheduledExecutorService poller = Executors.newSingleThreadScheduledExecutor();

    @Inject
    public EksService(StorageFactory storageFactory, EmulatorConfig config,
            RegionResolver regionResolver, EksClusterManager clusterManager) {
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
    }

    @PostConstruct
    public void init() {
        if (!config.services().eks().mock()) {
            startReadinessPoller();
        }
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
        if (storage.get(name).isPresent()) {
            throw new AwsException("ResourceInUseException",
                    "Cluster already exists: " + name, 409);
        }

        String region = config.defaultRegion();
        String accountId = regionResolver.getAccountId();
        String arn = AwsArnUtils.Arn.of("eks", region, accountId, "cluster/" + name).toString();

        Cluster cluster = new Cluster();
        cluster.setName(name);
        cluster.setArn(arn);
        cluster.setAccountId(accountId);
        cluster.setCreatedAt(Instant.now());
        cluster.setVersion(request.getVersion() != null ? request.getVersion() : "1.29");
        cluster.setRoleArn(request.getRoleArn());
        cluster.setResourcesVpcConfig(buildVpcConfigResponse(request.getResourcesVpcConfig()));
        cluster.setKubernetesNetworkConfig(buildNetworkConfig(request.getKubernetesNetworkConfig()));
        cluster.setStatus(ClusterStatus.CREATING);
        cluster.setTags(request.getTags() != null ? new HashMap<>(request.getTags()) : new HashMap<>());
        cluster.setPlatformVersion("eks.1");
        cluster.setCertificateAuthority(new CertificateAuthority(""));

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

    private ResourcesVpcConfig buildVpcConfigResponse(ResourcesVpcConfig request) {
        ResourcesVpcConfig response = new ResourcesVpcConfig();
        if (request != null) {
            response.setSubnetIds(request.getSubnetIds() != null ? request.getSubnetIds() : List.of());
            response.setSecurityGroupIds(request.getSecurityGroupIds() != null ? request.getSecurityGroupIds() : List.of());
            response.setVpcId(request.getVpcId() != null ? request.getVpcId() : "");
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
}
