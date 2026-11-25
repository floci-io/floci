package io.floci.aws.services.eks;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.dockerjava.api.DockerClient;
import com.github.dockerjava.api.command.ExecCreateCmdResponse;
import com.github.dockerjava.api.command.InspectContainerResponse;
import com.github.dockerjava.api.model.Container;
import com.github.dockerjava.api.model.ContainerNetwork;
import com.github.dockerjava.api.model.Info;
import io.floci.aws.config.ContainerCaBundle;
import io.floci.aws.config.EmulatorConfig;
import io.floci.aws.config.FlociCertificateAuthority;
import io.floci.aws.core.common.AwsArnUtils;
import io.floci.aws.core.common.AwsException;
import io.floci.aws.core.common.AwsRegions;
import io.floci.aws.core.common.Digests;
import io.floci.aws.core.common.RegionResolver;
import io.floci.aws.core.common.StripedLocks;
import io.floci.aws.core.common.dns.DnsAnswer;
import io.floci.aws.core.common.dns.DnsClientVpcSource;
import io.floci.aws.core.common.dns.DnsClientVpcSource.ClientVpc;
import io.floci.aws.core.common.dns.DnsForwardingRule;
import io.floci.aws.core.common.dns.DnsForwardingRuleSource;
import io.floci.aws.core.common.dns.DnsRecordSource;
import io.floci.aws.core.common.docker.ContainerBuilder;
import io.floci.aws.core.common.docker.ContainerDetector;
import io.floci.aws.core.common.docker.ContainerExec;
import io.floci.aws.core.common.docker.ContainerLifecycleManager;
import io.floci.aws.core.common.docker.ContainerLifecycleManager.ContainerInfo;
import io.floci.aws.core.common.docker.ContainerLogStreamer;
import io.floci.aws.core.common.docker.ContainerNetworks;
import io.floci.aws.core.common.docker.ContainerReachableEndpoint;
import io.floci.aws.core.common.docker.ContainerSpec;
import io.floci.aws.core.common.docker.ContainerStorageHelper;
import io.floci.aws.core.common.docker.DockerHostResolver;
import io.floci.aws.core.common.docker.PortAllocator;
import io.floci.aws.core.common.docker.RetryingTarCopier;
import io.floci.aws.core.common.docker.UserDataPipeline;
import io.floci.aws.core.storage.AccountAwareStorageBackend;
import io.floci.aws.core.storage.StorageBackend;
import io.floci.aws.core.storage.StorageFactory;
import io.floci.aws.services.ec2.ClusterNodeInstanceProvider;
import io.floci.aws.services.ec2.Ec2ImageCatalog;
import io.floci.aws.services.ec2.Ec2InstanceTypeCatalog;
import io.floci.aws.services.ec2.Ec2InstanceTypeCatalog.CatalogInstanceType;
import io.floci.aws.services.ec2.Ec2MetadataProxy;
import io.floci.aws.services.ec2.Ec2MetadataServer;
import io.floci.aws.services.ec2.Ec2Service;
import io.floci.aws.services.ec2.VpcRouteTableListener;
import io.floci.aws.services.ec2.model.GroupIdentifier;
import io.floci.aws.services.ec2.model.Instance;
import io.floci.aws.services.ec2.model.InstanceNetworkInterface;
import io.floci.aws.services.ec2.model.InstanceState;
import io.floci.aws.services.ec2.model.LaunchTemplateData;
import io.floci.aws.services.ec2.model.Placement;
import io.floci.aws.services.ec2.model.RouteTable;
import io.floci.aws.services.ec2.model.Tag;
import io.floci.aws.services.ec2.net.Cidr4;
import io.floci.aws.services.ecr.registry.EcrRegistryManager;
import io.floci.aws.services.eks.model.CertificateAuthority;
import io.floci.aws.services.eks.model.Cluster;
import io.floci.aws.services.eks.model.ClusterIdentity;
import io.floci.aws.services.eks.model.ClusterOidcKey;
import io.floci.aws.services.eks.model.ElasticLoadBalancing;
import io.floci.aws.services.eks.model.LogSetup;
import io.floci.aws.services.eks.model.Nodegroup;
import io.floci.aws.services.eks.model.NodegroupStatus;
import io.floci.aws.services.eks.model.OidcIdentity;
import io.floci.aws.services.eks.model.ResourcesVpcConfig;
import io.floci.aws.services.eks.model.StorageConfig;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.apache.commons.compress.archivers.tar.TarArchiveEntry;
import org.apache.commons.compress.archivers.tar.TarArchiveOutputStream;
import org.jboss.logging.Logger;

import java.io.ByteArrayOutputStream;
import java.io.Closeable;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.net.HttpURLConnection;
import java.net.Inet4Address;
import java.net.Inet6Address;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.attribute.PosixFileAttributeView;
import java.nio.file.attribute.PosixFilePermission;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.BiFunction;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.function.Supplier;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * Manages the Docker lifecycle of k3s containers for real-mode EKS clusters.
 * Not used when {@code floci.services.eks.mock=true}.
 */
@ApplicationScoped
public class EksClusterManager
        implements ClusterNodeInstanceProvider, VpcRouteTableListener, DnsClientVpcSource,
        DnsRecordSource, DnsForwardingRuleSource {

    private static final Logger LOG = Logger.getLogger(EksClusterManager.class);
    private static final ObjectMapper NODE_MAPPER = new ObjectMapper();
    private static final int K3S_API_SERVER_PORT = 6443;
    static final String DEFAULT_NODE_INSTANCE_TYPE = "m5.large";
    /** The tag naming the cluster, on the cluster's node and on its node groups' Auto Scaling groups. */
    static final String CLUSTER_NAME_TAG = "eks:cluster-name";
    private static final String NODE_CAPACITY_LABEL = "io.floci.eks.node-capacity";
    private static final String DEFAULT_STORAGE_CLASS_LABEL = "io.floci.eks.default-storage-class";
    private static final String ENDPOINT_HOST_LABEL = "io.floci.eks.endpoint-host";
    /** Marks a node started behind {@link #RSHARE_ENTRYPOINT}, whose mounts are shared. */
    private static final String SHARED_MOUNTS_LABEL = "io.floci.eks.shared-mounts";
    /** The pod network a node was started with: {@link #FLANNEL_POD_NETWORK}, or {@code vpc:} and its prefixes. */
    private static final String POD_NETWORK_LABEL = "io.floci.eks.pod-network";
    /** The digest of the registries.yaml a node was started with; a node without one has none, or predates the label. */
    private static final String REGISTRY_MIRROR_LABEL = "io.floci.eks.registry-mirror";
    private static final String FLANNEL_POD_NETWORK = "flannel";
    private static final String VPC_POD_NETWORK_LABEL_PREFIX = "vpc:";
    /** The k3s arguments that leave pod addresses to the VPC pod network's bridge configuration. */
    static final List<String> VPC_POD_NETWORK_SERVER_ARGS = List.of("--flannel-backend=none",
            "--disable-network-policy", "--kube-controller-manager-arg=allocate-node-cidrs=false");
    /** Whether the node runs k3s's ServiceLB, which an EKS Auto Mode node with load balancing on runs without. */
    static final String SERVICE_LB_LABEL = "io.floci.eks.service-lb";
    private static final Pattern HOSTNAME = Pattern.compile(
            "[a-z0-9]([a-z0-9-]{0,61}[a-z0-9])?(\\.[a-z0-9]([a-z0-9-]{0,61}[a-z0-9])?)*");

    private static final String WEBHOOK_CONFIG_DIR = "/etc";
    private static final String WEBHOOK_CONFIG_FILE = "token-webhook.yaml";
    private static final String WEBHOOK_CONFIG_PATH = WEBHOOK_CONFIG_DIR + "/" + WEBHOOK_CONFIG_FILE;
    // Tar entry extracted at /etc; the archive path creates /etc/rancher/k3s, which does not
    // exist yet in a created-but-not-started k3s container.
    private static final String REGISTRIES_TAR_ENTRY = "rancher/k3s/registries.yaml";
    // The resolv.conf k3s hands its pods (--resolv-conf), extracted at /etc as registries.yaml is.
    static final String POD_RESOLV_CONF_TAR_ENTRY = "rancher/k3s/floci-resolv.conf";
    static final String POD_RESOLV_CONF_PATH = "/etc/" + POD_RESOLV_CONF_TAR_ENTRY;
    // k3s applies every manifest in its server manifests directory at startup, and again whenever
    // one changes on disk, so dropping the file in before the container starts is enough to get the
    // MutatingWebhookConfiguration registered. The directory sits under the cluster's named data
    // volume; the Docker copy resolves through the container's mounts, so the file lands there.
    static final String K3S_DATA_DIR = "/var/lib/rancher/k3s";
    static final String CONTAINERD_CERTS_DIR = "agent/etc/containerd/certs.d";
    static final String CONTAINERD_CERTS_TARGET = K3S_DATA_DIR + "/" + CONTAINERD_CERTS_DIR;
    static final String CONTAINERD_CERTS_LINK = "etc/containerd/certs.d";
    static final String POD_IDENTITY_MANIFEST_FILE = "floci-eks-pod-identity.yaml";
    static final String POD_IDENTITY_MANIFEST_TAR_ENTRY = "server/manifests/" + POD_IDENTITY_MANIFEST_FILE;
    // The manifest while Floci registers no pod identity webhook: no objects, so k3s deletes what it held before.
    static final String POD_IDENTITY_MANIFEST_REMOVED = """
            # Floci registers no EKS Pod Identity webhook for this cluster now; its log says why.
            # k3s deletes the MutatingWebhookConfiguration an earlier version of this file created.
            """;
    static final String BLOCK_STORAGE_MANIFEST_FILE = "floci-eks-auto-mode-block-storage.yaml";
    static final String BLOCK_STORAGE_MANIFEST_TAR_ENTRY = "server/manifests/" + BLOCK_STORAGE_MANIFEST_FILE;
    // The manifest once block storage is off: no objects, so k3s deletes the provisioner it held before.
    static final String BLOCK_STORAGE_MANIFEST_REMOVED = """
            # EKS Auto Mode block storage is off for this cluster.
            # k3s deletes the provisioner an earlier version of this file created.
            """;
    static final String COMPUTE_MANIFEST_FILE = "floci-eks-auto-mode-compute.yaml";
    static final String COMPUTE_MANIFEST_TAR_ENTRY = "server/manifests/" + COMPUTE_MANIFEST_FILE;
    // The manifest once compute is off: no objects, so k3s deletes the node pools, node class and their kinds.
    static final String COMPUTE_MANIFEST_REMOVED = """
            # EKS Auto Mode compute is off for this cluster.
            # k3s deletes the node pools, node class and kinds an earlier version of this file created.
            """;
    /** The comment in the compute manifest that starts the {@code default} NodeClass. */
    private static final String DEFAULT_NODE_CLASS_MARKER = "# The default NodeClass";
    /** The label EKS Auto Mode's nodes carry, given to the cluster's node while compute is on. */
    static final String AUTO_COMPUTE_LABEL_KEY = "eks.amazonaws.com/compute-type"; // partition-literal: a label
    static final String AUTO_COMPUTE_LABEL = AUTO_COMPUTE_LABEL_KEY + "=auto";
    private static final String ENDPOINT_MODE_NETWORK = "network";
    public static final String DEFAULT_POD_CIDR = "10.42.0.0/16";

    public static final Map<String, String> SUPPORTED_K8S_VERSIONS = Map.of(
            "1.28", "rancher/k3s:v1.28.15-k3s1",
            "1.29", "rancher/k3s:v1.29.14-k3s1",
            "1.30", "rancher/k3s:v1.30.10-k3s1",
            "1.31", "rancher/k3s:v1.31.5-k3s1",
            "1.32", "rancher/k3s:v1.32.2-k3s1",
            "1.33", "rancher/k3s:v1.33.1-k3s1",
            "1.34", "rancher/k3s:v1.34.1-k3s1",
            "1.35", "rancher/k3s:v1.35.0-k3s1",
            "1.36", "rancher/k3s:v1.36.0-k3s1"
    );

    static final String SA_SIGNING_KEY_FILE = "sa-signing-key.pem";
    static final String SA_PUBLIC_KEY_FILE = "sa-public-key.pem";
    static final String SA_SIGNING_KEY_CONTAINER_PATH = WEBHOOK_CONFIG_DIR + "/" + SA_SIGNING_KEY_FILE;
    static final String SA_PUBLIC_KEY_CONTAINER_PATH = WEBHOOK_CONFIG_DIR + "/" + SA_PUBLIC_KEY_FILE;
    static final String KUBERNETES_DEFAULT_ISSUER = "https://kubernetes.default.svc.cluster.local";

    static final String AUDIT_POLICY_FILE = "audit-policy.yaml";
    static final String AUDIT_POLICY_DIR = "/etc";
    static final String AUDIT_POLICY_CONTAINER_PATH = AUDIT_POLICY_DIR + "/" + AUDIT_POLICY_FILE;
    static final String AUDIT_LOG_CONTAINER_PATH = "/var/log/audit.log";
    static final String AUDIT_LOG_MAXAGE = "30";
    static final String AUDIT_LOG_MAXBACKUP = "10";
    static final String AUDIT_LOG_MAXSIZE = "100";

    private final ContainerBuilder containerBuilder;
    private final ContainerLifecycleManager lifecycleManager;
    private final long archiveRetryBackoffMillis;
    private final ContainerDetector containerDetector;
    private final PortAllocator portAllocator;
    private final DockerHostResolver dockerHostResolver;
    private final EcrRegistryManager ecrRegistryManager;
    private final EmulatorConfig config;
    private final RegionResolver regionResolver;
    private final Ec2MetadataServer metadataServer;
    private final EksOidcService oidcService;
    private final FlociCertificateAuthority certificateAuthority;
    private final ContainerReachableEndpoint reachableEndpoint;
    private final ContainerLogStreamer logStreamer;
    private final Ec2ImageCatalog imageCatalog;
    private final Ec2InstanceTypeCatalog instanceTypeCatalog = new Ec2InstanceTypeCatalog();
    private final Map<String, ClusterNodeRecord> clusterNodeInstances = new ConcurrentHashMap<>();
    private final Map<String, Map<String, Instance>> joinedClusterInstances = new ConcurrentHashMap<>();
    private final Map<String, Closeable> clusterLogHandles = new ConcurrentHashMap<>();
    private final List<Consumer<Instance>> nodeRegistrationListeners = new CopyOnWriteArrayList<>();
    private final Map<String, Cluster> activeClusters = new ConcurrentHashMap<>();

    @Inject
    jakarta.enterprise.inject.Instance<Ec2Service> ec2ServiceInstance;
    private Ec2Service ec2Service;
    /** Looked up on use, as {@link #ec2ServiceInstance} is, since the add-ons are read only then. */
    @Inject
    jakarta.enterprise.inject.Instance<EksAddonService> addonServiceInstance;
    private EksAddonService addonService;
    private final StorageBackend<String, Nodegroup> nodeGroupStorage;
    private final Map<String, Set<String>> programmedClusterRoutes = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, Object> clusterRouteLocks = new ConcurrentHashMap<>();
    private final StripedLocks podIdentityForwardLocks = new StripedLocks(256);
    private record VpcLease(String accountId, String region, String vpcId, String subnetId, String address) {}
    private record ClusterVpcSubnet(String vpcId, String subnetId) {}
    private final Map<String, VpcLease> clusterVpcLeases = new ConcurrentHashMap<>();
    /** The IPv4 /28 prefixes leased for each cluster node's pods, by cluster resource name. */
    private final Map<String, List<String>> clusterPodPrefixes = new ConcurrentHashMap<>();
    /**
     * The k3s containers whose guest setup waits for their API server to answer, by container ID. On a cgroup v2
     * host, k3s moves the root cgroup's processes into {@code /init} and then enables the cgroup controllers, which
     * the kernel allows only while the root cgroup is empty, and k3s does not retry. A {@code docker exec} landing in
     * between keeps the root cgroup busy, and kubelet exits with {@code cgroup ["kubepods"] has some missing
     * controllers}. k3s serves its API only after that step, so nothing is executed in a started container until the
     * API has answered.
     */
    private final Map<String, GuestSetup> pendingGuestSetups = new ConcurrentHashMap<>();
    /** How long node group user data waits for its node's guest setup before the node group fails. */
    private Duration guestSetupTimeout = Duration.ofMinutes(10);
    /**
     * Runs each node's guest setup off the readiness poller, which checks every cluster from one thread: a node whose
     * setup is slow must not hold back the others' readiness.
     */
    private final ExecutorService guestSetupExecutor = Executors.newVirtualThreadPerTaskExecutor();

    /**
     * The setup a k3s container waits for, run once its API server answers. Its monitor is held for the whole setup,
     * so a delete abandoning it waits for one already running and then finds everything it attached.
     */
    private static final class GuestSetup {
        private final CompletableFuture<Void> done = new CompletableFuture<>();
        private final boolean logsFromNow;
        /** Set once the API server has answered, from when the container may be executed in. */
        private volatile boolean apiAnswered;
        /** Whether the setup was handed to {@link #guestSetupExecutor}; guarded by the monitor. */
        private boolean started;

        private GuestSetup(boolean logsFromNow) {
            this.logsFromNow = logsFromNow;
        }
    }

    /** The container a node runs in once it is set up, or why user data cannot run in it. */
    record SetUpNode(String containerId, UserDataPipeline.ExecutionResult failure) {}

    private Object routeLockFor(String clusterKey) {
        return clusterRouteLocks.computeIfAbsent(clusterKey, k -> new Object());
    }

    public void setEc2Service(Ec2Service ec2Service) {
        this.ec2Service = ec2Service;
    }

    private Ec2Service ec2Service() {
        if (ec2Service != null) {
            return ec2Service;
        }
        if (ec2ServiceInstance != null && !ec2ServiceInstance.isUnsatisfied()) {
            return ec2ServiceInstance.get();
        }
        return null;
    }

    private static ClusterVpcSubnet resolveClusterSubnet(Cluster cluster) {
        ResourcesVpcConfig vpcConfig = cluster != null ? cluster.getResourcesVpcConfig() : null;
        if (vpcConfig == null) {
            return null;
        }
        String vpcId = vpcConfig.getVpcId();
        List<String> subnetIds = vpcConfig.getSubnetIds();
        String subnetId = (subnetIds != null && !subnetIds.isEmpty()) ? subnetIds.getFirst() : null;
        if (vpcId == null || vpcId.isBlank() || subnetId == null || subnetId.isBlank()) {
            return null;
        }
        return new ClusterVpcSubnet(vpcId, subnetId);
    }

    private VpcLease resolveClusterVpcLease(Cluster cluster) {
        if (cluster == null) {
            return null;
        }
        VpcLease existing = clusterVpcLeases.get(clusterResourceName(cluster));
        if (existing != null) {
            return existing;
        }
        String persistedIp = cluster.getNodePrivateIp();
        ClusterVpcSubnet subnet = resolveClusterSubnet(cluster);
        if (persistedIp == null || persistedIp.isBlank() || subnet == null) {
            return null;
        }
        return new VpcLease(resolveClusterAccountId(cluster), clusterRegion(cluster), subnet.vpcId(), subnet.subnetId(),
                persistedIp);
    }

    // Issue #5501: Lease or re-reserve a stable VPC subnet IP for the k3s node container across restarts.
    private VpcLease acquireClusterVpcLease(Cluster cluster, String accountId, String region) {
        return acquireClusterVpcLease(cluster, accountId, region, true);
    }

    private VpcLease acquireClusterVpcLease(Cluster cluster, String accountId, String region,
                                            boolean allocateWhenUnreserved) {
        VpcLease lease = acquireNodeAddressLease(cluster, accountId, region, allocateWhenUnreserved);
        if (lease != null) {
            leasePodPrefixes(cluster, lease, allocateWhenUnreserved);
        }
        return lease;
    }

    /**
     * With {@code vpc-pod-network}, leases the node's IPv4 /28 prefixes in its subnet: those it held before a restart,
     * when they are still free, and new ones up to {@code vpc-pod-prefixes} when {@code allocate}. An adopted container
     * does not allocate, as it keeps handing out the prefixes it was started with; a container started later on the
     * same lease, one replacing it among them, tops the list up. Without the setting the node holds none.
     */
    private void leasePodPrefixes(Cluster cluster, VpcLease lease, boolean allocate) {
        String resourceName = clusterResourceName(cluster);
        Ec2Service ec2 = ec2Service();
        if (!config.services().eks().vpcPodNetwork() || ec2 == null) {
            cluster.setNodePodPrefixes(null);
            return;
        }
        List<String> prefixes = clusterPodPrefixes.get(resourceName);
        if (prefixes == null) {
            List<String> held = cluster.getNodePodPrefixes();
            prefixes = new ArrayList<>(held == null || held.isEmpty() ? List.of()
                    : ec2.reserveVpcPrefixes(lease.accountId(), lease.region(), lease.subnetId(), held));
            clusterPodPrefixes.put(resourceName, prefixes);
        }
        int wanted = config.services().eks().vpcPodPrefixes();
        if (allocate && prefixes.size() < wanted) {
            prefixes.addAll(ec2.allocateVpcPrefixes(lease.accountId(), lease.region(), lease.subnetId(),
                    wanted - prefixes.size()));
            if (prefixes.size() < wanted) {
                LOG.warnv("EKS cluster {0} holds {1} of its {2} pod prefixes: subnet {3} has no other free /28",
                        cluster.getName(), prefixes.size(), wanted, lease.subnetId());
            }
        }
        cluster.setNodePodPrefixes(prefixes.isEmpty() ? null : new ArrayList<>(prefixes));
    }

    private VpcLease acquireNodeAddressLease(Cluster cluster, String accountId, String region,
                                             boolean allocateWhenUnreserved) {
        ClusterVpcSubnet subnet = resolveClusterSubnet(cluster);
        if (subnet == null) {
            return null;
        }
        String vpcId = subnet.vpcId();
        String subnetId = subnet.subnetId();
        Ec2Service ec2 = ec2Service();
        if (ec2 == null) {
            return null;
        }
        String resourceName = clusterResourceName(cluster);
        VpcLease existing = clusterVpcLeases.get(resourceName);
        if (existing != null) {
            if (cluster.getNodePrivateIp() == null || cluster.getNodePrivateIp().isBlank()) {
                cluster.setNodePrivateIp(existing.address());
            }
            return existing;
        }
        String persistedIp = cluster.getNodePrivateIp();
        if (persistedIp != null && !persistedIp.isBlank()) {
            boolean reserved = ec2.reserveVpcPrivateIp(accountId, region, subnetId, persistedIp);
            if (reserved) {
                VpcLease lease = new VpcLease(accountId, region, vpcId, subnetId, persistedIp);
                clusterVpcLeases.put(resourceName, lease);
                return lease;
            }
            cluster.setNodePrivateIp(null);
            if (!allocateWhenUnreserved) {
                return null;
            }
        }
        if (!allocateWhenUnreserved) {
            return null;
        }
        Optional<String> allocated = ec2.allocateVpcPrivateIp(accountId, region, subnetId);
        if (allocated == null || allocated.isEmpty()) {
            return null;
        }
        String address = allocated.get();
        cluster.setNodePrivateIp(address);
        VpcLease lease = new VpcLease(accountId, region, vpcId, subnetId, address);
        clusterVpcLeases.put(resourceName, lease);
        return lease;
    }

    private void releaseClusterVpcLease(Cluster cluster, String resourceName) {
        VpcLease lease = clusterVpcLeases.remove(resourceName);
        List<String> prefixes = clusterPodPrefixes.remove(resourceName);
        Ec2Service ec2 = ec2Service();
        if (lease != null && ec2 != null) {
            ec2.releaseVpcPrivateIp(lease.accountId(), lease.region(), lease.subnetId(), lease.address());
            if (prefixes != null) {
                prefixes.forEach(prefix -> ec2.releaseVpcPrefix(lease.accountId(), lease.region(), lease.subnetId(),
                        prefix));
            }
        }
    }

    void setAddonService(EksAddonService addonService) {
        this.addonService = addonService;
    }

    private EksAddonService addonService() {
        if (addonService != null) {
            return addonService;
        }
        if (addonServiceInstance != null && !addonServiceInstance.isUnsatisfied()) {
            return addonServiceInstance.get();
        }
        return null;
    }

    public void addNodeRegistrationListener(Consumer<Instance> listener) {
        this.nodeRegistrationListeners.add(listener);
    }

    record ClusterNodeRecord(String accountId, String region, Instance instance) {}

    /** Which cluster owns a Docker address its node container answers on, and where that cluster sits. */
    record ClusterNodeVpc(String clusterResourceName, ClientVpc clientVpc) {}

    private final Map<String, ClusterNodeVpc> clusterNodeVpcs = new ConcurrentHashMap<>();

    /**
     * A DNS query from a cluster container originates in the account, region and VPC the cluster was
     * created with, which is what a Route 53 Resolver rule has to belong to for the query to follow
     * it. A cluster with no resolvable VPC id claims no address: its queries resolve as they do
     * without any rule rather than picking up another cluster's.
     */
    @Override
    public Optional<ClientVpc> vpcForClient(String clientAddress) {
        if (clientAddress == null || clientAddress.isBlank()) {
            return Optional.empty();
        }
        return Optional.ofNullable(clusterNodeVpcs.get(clientAddress.trim()))
                .map(ClusterNodeVpc::clientVpc);
    }

    @Override
    public Optional<DnsAnswer> resolveIpv4(String queryName) {
        if (queryName == null || queryName.isBlank()) {
            return Optional.empty();
        }
        String name = queryName.toLowerCase(Locale.ROOT);
        if (name.endsWith(".")) {
            name = name.substring(0, name.length() - 1);
        }
        for (ClusterNodeRecord record : clusterNodeInstances.values()) {
            Instance inst = record.instance();
            if (inst != null && inst.getPrivateDnsName() != null && inst.getPrivateIpAddress() != null
                    && !inst.getPrivateIpAddress().isBlank()) {
                String nodeDnsName = inst.getPrivateDnsName().toLowerCase(Locale.ROOT);
                if (name.equals(nodeDnsName)) {
                    return Optional.of(DnsAnswer.records(List.of(inst.getPrivateIpAddress()), DnsAnswer.DEFAULT_TTL_SECONDS));
                }
            }
        }
        for (Map<String, Instance> map : joinedClusterInstances.values()) {
            for (Instance inst : map.values()) {
                if (inst != null && inst.getPrivateIpAddress() != null && !inst.getPrivateIpAddress().isBlank()) {
                    String nodeName = deriveInstanceNodeName(inst, inst.getRegion()).toLowerCase(Locale.ROOT);
                    if (name.equals(nodeName)) {
                        return Optional.of(DnsAnswer.records(List.of(inst.getPrivateIpAddress()), DnsAnswer.DEFAULT_TTL_SECONDS));
                    }
                }
            }
        }
        return Optional.empty();
    }

    @Override
    public List<DnsForwardingRule> rulesFor(String accountId, String region, String vpcId) {
        if (vpcId == null || vpcId.isBlank()) {
            return List.of();
        }
        List<DnsForwardingRule> rules = new ArrayList<>();
        for (ClusterNodeRecord record : clusterNodeInstances.values()) {
            if (accountId != null && !accountId.isBlank() && !accountId.equals(record.accountId())) {
                continue;
            }
            if (region != null && !region.isBlank() && !region.equals(record.region())) {
                continue;
            }
            Instance inst = record.instance();
            if (inst != null && inst.getPrivateDnsName() != null && !inst.getPrivateDnsName().isBlank()) {
                if (vpcId.equals(inst.getVpcId())) {
                    rules.add(DnsForwardingRule.system(inst.getPrivateDnsName()));
                }
            }
        }
        for (Map<String, Instance> map : joinedClusterInstances.values()) {
            for (Instance inst : map.values()) {
                if (inst != null && vpcId.equals(inst.getVpcId())) {
                    rules.add(DnsForwardingRule.system(deriveInstanceNodeName(inst, inst.getRegion())));
                }
            }
        }
        return List.copyOf(rules);
    }

    private void registerClusterNodeVpc(Cluster cluster, String accountId, String region,
                                        Set<String> addresses) {
        String clusterKey = clusterResourceName(cluster);
        forgetClusterNodeVpcs(clusterKey);
        String vpcId = cluster.getResourcesVpcConfig() != null
                ? cluster.getResourcesVpcConfig().getVpcId() : null;
        if (vpcId == null || vpcId.isBlank()) {
            return;
        }
        Set<String> usable = new LinkedHashSet<>();
        for (String address : addresses) {
            if (address != null && !address.isBlank()) {
                usable.add(address);
            }
        }
        if (usable.isEmpty()) {
            // Without the container's own addresses there is nothing to recognise its queries by, so
            // resolver rules cannot apply to this cluster. Said out loud rather than left as silence:
            // the cluster runs fine and only rule-steered names behave as though no rule existed.
            LOG.warnv("Resolver rules will not apply to EKS cluster {0} in {1}: its container"
                    + " addresses could not be determined, so its DNS queries cannot be attributed"
                    + " to the VPC. Restart the cluster to retry.", cluster.getName(), vpcId);
            return;
        }
        ClientVpc clientVpc = new ClientVpc(accountId, region, vpcId);
        for (String address : usable) {
            clusterNodeVpcs.put(address, new ClusterNodeVpc(clusterKey, clientVpc));
        }
    }

    private void forgetClusterNodeVpcs(String clusterResourceName) {
        clusterNodeVpcs.entrySet().removeIf(
                entry -> clusterResourceName.equals(entry.getValue().clusterResourceName()));
    }

    EksClusterManager(ContainerBuilder containerBuilder,
                      ContainerLifecycleManager lifecycleManager,
                      ContainerDetector containerDetector,
                      PortAllocator portAllocator,
                      DockerHostResolver dockerHostResolver,
                      EcrRegistryManager ecrRegistryManager,
                      EmulatorConfig config,
                      RegionResolver regionResolver,
                      Ec2MetadataServer metadataServer,
                      EksOidcService oidcService,
                      FlociCertificateAuthority certificateAuthority,
                      ContainerLogStreamer logStreamer,
                      StorageBackend<String, Nodegroup> nodeGroupStorage,
                      long archiveRetryBackoffMillis,
                      Ec2ImageCatalog imageCatalog,
                      ContainerReachableEndpoint reachableEndpoint) {
        this.containerBuilder = containerBuilder;
        this.lifecycleManager = lifecycleManager;
        this.containerDetector = containerDetector;
        this.portAllocator = portAllocator;
        this.dockerHostResolver = dockerHostResolver;
        this.ecrRegistryManager = ecrRegistryManager;
        this.config = config;
        this.regionResolver = regionResolver;
        this.metadataServer = metadataServer;
        this.oidcService = oidcService;
        this.certificateAuthority = certificateAuthority;
        this.logStreamer = logStreamer;
        this.nodeGroupStorage = nodeGroupStorage;
        this.archiveRetryBackoffMillis = archiveRetryBackoffMillis;
        this.imageCatalog = imageCatalog;
        this.reachableEndpoint = reachableEndpoint;
    }

    @Inject
    public EksClusterManager(ContainerBuilder containerBuilder,
                             ContainerLifecycleManager lifecycleManager,
                             ContainerDetector containerDetector,
                             PortAllocator portAllocator,
                             DockerHostResolver dockerHostResolver,
                             EcrRegistryManager ecrRegistryManager,
                             EmulatorConfig config,
                             RegionResolver regionResolver,
                             Ec2MetadataServer metadataServer,
                             EksOidcService oidcService,
                             FlociCertificateAuthority certificateAuthority,
                             ContainerLogStreamer logStreamer,
                             StorageFactory storageFactory,
                             Ec2ImageCatalog imageCatalog,
                             ContainerReachableEndpoint reachableEndpoint) {
        this(containerBuilder, lifecycleManager, containerDetector, portAllocator,
                dockerHostResolver, ecrRegistryManager, config, regionResolver, metadataServer,
                oidcService, certificateAuthority, logStreamer,
                storageFactory != null
                        ? storageFactory.create("eks", "eks-nodegroups.json",
                                new TypeReference<Map<String, Nodegroup>>() {})
                        : null, RetryingTarCopier.DEFAULT_BACKOFF_MS, imageCatalog, reachableEndpoint);
    }

    /**
     * Attempts {@link #startCluster} and reports the k3s backend as unavailable instead of
     * propagating the failure, when the cause is that no Docker daemon is reachable from Floci:
     * Floci running inside Docker without a mounted socket, or a stopped daemon on the host. A
     * failure raised while the daemon <em>is</em> reachable is a genuine provisioning error and
     * still propagates, so a cluster only reaches FAILED for a reason AWS would also fail on.
     *
     * @return true when the k3s container was created and started
     */
    public boolean tryStartCluster(Cluster cluster) {
        try {
            startCluster(cluster);
            return true;
        } catch (RuntimeException e) {
            if (isDockerReachable()) {
                throw e;
            }
            LOG.warnv("No Docker daemon is reachable from Floci ({0}). EKS cluster {1} is created as "
                    + "metadata only: describe, list, tag, nodegroups, Fargate profiles and delete "
                    + "work, but the cluster has no Kubernetes API server and kubectl cannot connect "
                    + "to the endpoint it reports.", e.getMessage(), cluster.getName());
            return false;
        }
    }

    /**
     * Probes the configured Docker endpoint, which is how a missing daemon is told apart from a
     * k3s container that failed for its own reasons.
     */
    public boolean isDockerReachable() {
        return lifecycleManager.isDockerReachable();
    }

    /**
     * Starts a k3s container for the given cluster. Updates the cluster with
     * the container ID and host port. The cluster status remains CREATING until
     * {@link #isReady(Cluster)} returns true and {@link #finalizeCluster(Cluster)} is called.
     */
    public void startCluster(Cluster cluster) {
        startCluster(cluster, null);
    }

    private void startCluster(Cluster cluster, Integer retainedPort) {
        String image = resolveClusterImage(cluster);
        if (cluster.getDockerName() == null) {
            cluster.setDockerName(accountQualifiedName(cluster));
        }
        String containerName = cluster.getDockerName();

        LOG.infov("Starting k3s container for EKS cluster: {0} using image {1}",
                cluster.getName(), image);

        // Allocate host port for the k3s API server
        int hostPort = retainedPort != null ? retainedPort : allocateApiServerPort(cluster);

        cluster.setHostPort(hostPort);
        try {
            launchCluster(cluster, image, containerName, hostPort);
        } catch (RuntimeException e) {
            // A retained port still belongs to the surviving container the caller restores.
            if (retainedPort == null) {
                portAllocator.release(hostPort);
                cluster.setHostPort(0);
            }
            throw e;
        }
    }

    /**
     * Allocates the k3s API server's host port. A cluster recreated on restore asks first for the
     * port its persisted endpoint names, since an EKS endpoint never changes and every kubeconfig
     * written for the cluster names that port. Only when that port is taken does the cluster get the
     * first free one, which changes its endpoint, and a warning says so. A port outside the
     * configured range is not asked for, and neither is the port of a network-mode endpoint: that
     * endpoint names the container's own API server port, whatever host port the container gets.
     */
    private int allocateApiServerPort(Cluster cluster) {
        int basePort = config.services().eks().apiServerBasePort();
        int maxPort = config.services().eks().apiServerMaxPort();
        int previousPort = networkEndpointMode() ? -1 : endpointPort(cluster.getEndpoint());
        if (previousPort >= basePort && previousPort <= maxPort) {
            OptionalInt kept = portAllocator.allocateIfFree(previousPort);
            if (kept.isPresent()) {
                return kept.getAsInt();
            }
            LOG.warnv("API server port {0} of EKS cluster {1} is taken; moving the cluster to another port "
                    + "changes its endpoint", String.valueOf(previousPort), cluster.getName());
        }
        return portAllocator.allocate(basePort, maxPort);
    }

    /** The port an endpoint URL names, or -1 when it names none. */
    private static int endpointPort(String endpoint) {
        if (endpoint == null) {
            return -1;
        }
        try {
            return URI.create(endpoint).getPort();
        } catch (IllegalArgumentException e) {
            return -1;
        }
    }

    private void launchCluster(Cluster cluster, String image, String containerName, int hostPort) {
        // Remove any stale container
        ContainerStorageHelper.removeStaleContainer(config, lifecycleManager, containerName);

        // k3s v1.34+ removed support for --kube-apiserver-arg=storage-backend and
        // --kube-apiserver-arg=etcd-servers. k3s now manages kine (embedded SQLite)
        // internally without those flags.
        //
        // A named Docker volume is used for the k3s data directory instead of a host
        // bind mount. Bind-mounting to a macOS host path causes kine to create its Unix
        // socket (kine.sock) on macOS APFS, which returns EINVAL on chmod — crashing
        // k3s before it can start. Named volumes live in the Docker VM's Linux
        // filesystem, so chmod works correctly and data persists across container restarts.
        String volumeName = cluster.getDockerName();

        String serviceCidr = cluster.getKubernetesNetworkConfig() != null
                && cluster.getKubernetesNetworkConfig().getServiceIpv4Cidr() != null
                ? cluster.getKubernetesNetworkConfig().getServiceIpv4Cidr()
                : EksService.DEFAULT_SERVICE_IPV4_CIDR;
        String clusterCidr = cluster.getPodCidr() != null && !cluster.getPodCidr().isBlank()
                ? cluster.getPodCidr()
                : DEFAULT_POD_CIDR;

        boolean defaultStorageClass = cluster != null && cluster.getDefaultStorageClass() != null
                ? cluster.getDefaultStorageClass()
                : config.services().eks().defaultStorageClass();
        if (cluster != null) {
            cluster.setDefaultStorageClass(defaultStorageClass);
        }

        // The account label comes from the cluster record when set (restore runs with no request
        // context); regionResolver is the fallback for the create path.
        String labelAccountId = resolveClusterAccountId(cluster);
        String clusterRegion = clusterRegion(cluster);
        String resourceName = clusterResourceName(cluster);
        boolean hadLeaseBeforeLaunch = clusterVpcLeases.containsKey(resourceName);
        String previousNodeIp = cluster != null ? cluster.getNodePrivateIp() : null;
        List<String> previousPodPrefixes = cluster != null ? cluster.getNodePodPrefixes() : null;
        VpcLease vpcLease = acquireClusterVpcLease(cluster, labelAccountId, clusterRegion);
        String nodeIp = vpcLease != null ? vpcLease.address() : null;
        VpcPodNetwork podNetwork = vpcPodNetwork(cluster, vpcLease, true);
        String restoredNodeIp = (previousNodeIp != null && !previousNodeIp.isBlank()
                && previousNodeIp.equals(nodeIp)) ? previousNodeIp : null;

        List<String> serverArgs;
        String endpointHost;
        boolean serviceLb = serviceLb(cluster);
        ContainerBuilder.Builder specBuilder;
        String webhookLocalFile = null;
        SigningKeyFiles signingKeyFiles = null;
        String auditPolicyLocalFile = null;
        String podResolvConf = null;
        String registries = null;
        ContainerSpec spec;
        String containerId;
        try {
            serverArgs = buildServerArgs(
                    config.services().eks().disableCni(),
                    defaultStorageClass,
                    serviceCidr,
                    podNetwork != null ? podNetwork.vpcCidr() : clusterCidr,
                    nodeIp);
            if (podNetwork != null) {
                serverArgs.addAll(VPC_POD_NETWORK_SERVER_ARGS);
            }
            endpointHost = endpointHost();
            if (!"localhost".equals(endpointHost)) {
                serverArgs.add("--tls-san=" + endpointHost);
            }
            if (!serviceLb) {
                serverArgs.add("--disable=servicelb");
            }
            if (computeOn(cluster)) {
                // Auto Mode's nodes carry this label, which workloads select them by.
                serverArgs.add("--node-label=" + AUTO_COMPUTE_LABEL);
            }

            EksNodeCapacity.Limits nodeLimits = resolveNodeCapacity(cluster);
            if (nodeLimits != null) {
                nodeLimits.addKubeletArgs(serverArgs);
            }

            try {
                String nodeName = deriveClusterNodePrivateDnsName(cluster);
                serverArgs.add("--node-name=" + nodeName);
            } catch (Exception e) {
                String clusterName = cluster != null ? cluster.getName() : "unknown";
                LOG.warnv("EKS node name injection disabled for cluster {0}: could not derive node name: {1}",
                        clusterName, e.getMessage());
            }

            try {
                String providerId = deriveClusterNodeProviderId(cluster);
                serverArgs.add("--kubelet-arg=provider-id=" + providerId);
            } catch (Exception e) {
                String clusterName = cluster != null ? cluster.getName() : "unknown";
                LOG.warnv("EKS node provider ID injection disabled for cluster {0}: could not derive provider ID: {1}",
                        clusterName, e.getMessage());
            }

            try {
                String region = clusterRegion(cluster);
                String az = deriveClusterNodeAvailabilityZone(cluster, region);
                Nodegroup nodegroup = selectFirstNodegroup(cluster);
                String nodeLabels = buildNodeLabels(cluster, nodegroup, az, region);
                if (nodeLabels != null && !nodeLabels.isBlank()) {
                    serverArgs.add("--kubelet-arg=node-labels=" + nodeLabels);
                }
                String taints = buildRegisterWithTaints(nodegroup);
                if (taints != null && !taints.isBlank()) {
                    serverArgs.add("--kubelet-arg=register-with-taints=" + taints);
                }
            } catch (Exception e) {
                String clusterName = cluster != null ? cluster.getName() : "unknown";
                LOG.warnv("EKS node topology labels injection disabled for cluster {0}: could not derive topology labels: {1}",
                        clusterName, e.getMessage());
            }

            Map<String, String> labels = new LinkedHashMap<>(ContainerStorageHelper.resourceIdentityLabels(
                    "eks", cluster.getName(), labelAccountId, clusterRegion));
            labels.put(NODE_CAPACITY_LABEL, capacityLabel(cluster, nodeLimits));
            labels.put(DEFAULT_STORAGE_CLASS_LABEL, String.valueOf(defaultStorageClass));
            labels.put(SHARED_MOUNTS_LABEL, "true");
            labels.put(POD_NETWORK_LABEL, podNetworkLabel(podNetwork));
            registries = startingRegistriesYaml(cluster);
            if (registries != null) {
                labels.put(REGISTRY_MIRROR_LABEL, registryMirrorLabel(registries));
            }
            labels.put(SERVICE_LB_LABEL, String.valueOf(serviceLb));
            if (!"localhost".equals(endpointHost)) {
                labels.put(ENDPOINT_HOST_LABEL, endpointHost);
            }
            specBuilder = containerBuilder.newContainer(image)
                    .withName(containerName)
                    .withEnv("K3S_KUBECONFIG_MODE", "644")
                    .withPortBinding(K3S_API_SERVER_PORT, hostPort)
                    .withNamedVolume(volumeName, K3S_DATA_DIR)
                    .withDockerNetwork(config.services().eks().dockerNetwork())
                    .withPrivileged(true)
                    .withLogRotation()
                    .withLabels(labels);

            if (nodeLimits != null) {
                if (nodeLimits.memoryBytes() > 0) {
                    specBuilder.withMemoryBytes(nodeLimits.memoryBytes());
                }
                if (nodeLimits.vcpus() > 0) {
                    specBuilder.withCpuUnits(nodeLimits.vcpus() * 1024);
                }
            }

            if (config.services().eks().embeddedDns()) {
                specBuilder.withEmbeddedDns();
            }

            // The IMDS forward resolves Floci's host in the node too (configureLinkLocalMetadataEndpoint).
            if ((config.services().eks().ecrRegistryMirror() && config.services().ecr().enabled())
                    || config.services().eks().imds()) {
                specBuilder.withHostDockerInternalOnLinux();
            }

            // Wire a token-authentication webhook so `aws eks get-token` bearer tokens are validated by
            // Floci and mapped to their Kubernetes identity. The k3s API server POSTs a TokenReview to Floci's
            // _floci/eks/clusters/<cluster-name>/token-webhook endpoint. The kubeconfig is copied into
            // the container via the Docker API after create and before start (below), not bind-mounted,
            // so it works the same natively and in Docker-in-Docker, with no host-path /
            // host-persistent-path requirement.
            if (config.services().eks().iamAuthWebhook()) {
                webhookLocalFile = writeWebhookKubeconfig(cluster);
                if (webhookLocalFile != null) {
                    specBuilder.withHostDockerInternalOnLinux();
                    serverArgs.add("--kube-apiserver-arg=authentication-token-webhook-config-file="
                            + WEBHOOK_CONFIG_PATH);
                    serverArgs.add("--kube-apiserver-arg=authentication-token-webhook-version=v1");
                    serverArgs.add("--kube-apiserver-arg=authentication-token-webhook-cache-ttl=30s");
                }
            }

            if (config.services().eks().irsaSigningKey() && oidcService != null) {
                try {
                    String accountId = resolveClusterAccountId(cluster);
                    String issuer = resolveClusterIssuer(cluster);
                    ClusterOidcKey oidcKey = oidcService.ensureKeyForAccount(accountId, oidcKeyName(cluster), issuer);
                    signingKeyFiles = writeSigningKeyFiles(cluster, oidcKey);
                    if (signingKeyFiles != null) {
                        serverArgs.addAll(buildIrsaServerArgs(oidcKey.getIssuer()));
                    }
                } catch (Exception e) {
                    LOG.warnv("EKS IRSA signing key injection disabled for cluster {0}: could not prepare keys: {1}",
                            cluster.getName(), e.getMessage());
                }
            }

            if (hasLoggingEnabled(cluster, "audit")) {
                auditPolicyLocalFile = writeAuditPolicyFile(cluster);
                if (auditPolicyLocalFile != null) {
                    serverArgs.add("--kube-apiserver-arg=audit-policy-file=" + AUDIT_POLICY_CONTAINER_PATH);
                    serverArgs.add("--kube-apiserver-arg=audit-log-path=" + AUDIT_LOG_CONTAINER_PATH);
                    serverArgs.add("--kube-apiserver-arg=audit-log-maxage=" + AUDIT_LOG_MAXAGE);
                    serverArgs.add("--kube-apiserver-arg=audit-log-maxbackup=" + AUDIT_LOG_MAXBACKUP);
                    serverArgs.add("--kube-apiserver-arg=audit-log-maxsize=" + AUDIT_LOG_MAXSIZE);
                }
            }

            List<String> callerArgs = resolveCallerArgs(cluster);
            podResolvConf = podResolvConf(callerArgs);
            if (podResolvConf != null) {
                serverArgs.add("--resolv-conf=" + POD_RESOLV_CONF_PATH);
            }
            if (!callerArgs.isEmpty()) {
                serverArgs.addAll(callerArgs);
            }

            // A container's mounts default to private propagation, which a pod asking for mount
            // propagation cannot start on: a CSI node plugin mounts its volumes under
            // /var/lib/kubelet/pods with Bidirectional propagation ("is not a shared mount"), a node
            // exporter reads / with HostToContainer, and Cilium mounts its BPF filesystem on /sys
            // ("is not a shared or slave mount"). An EKS node is a VM, whose mounts are shared.
            // `mount --make-rshared /` before k3s starts gives the node the same; kind's own node
            // image runs the same fix in its entrypoint. RSHARE_ENTRYPOINT is POSIX sh-compatible
            // (no bashisms): the k3s image has no bash, only busybox sh.
            if (podNetwork != null) {
                podNetwork.env(nodeIp).forEach(specBuilder::withEnv);
            }
            specBuilder.withEntrypoint(podNetwork != null ? VPC_POD_NETWORK_ENTRYPOINT : RSHARE_ENTRYPOINT);
            specBuilder.withCmd(buildRshareWrappedCmd(serverArgs));
            spec = specBuilder.build();

            // create -> attach VPC network -> inject webhook kubeconfig -> start, so the interface
            // and files exist before the k3s API server / network policy controller boots.
            containerId = lifecycleManager.create(spec);
        } catch (RuntimeException e) {
            if (!hadLeaseBeforeLaunch) {
                releaseClusterVpcLease(cluster, resourceName);
                if (cluster != null) {
                    cluster.setNodePrivateIp(restoredNodeIp);
                    cluster.setNodePodPrefixes(restoredNodeIp != null ? previousPodPrefixes : null);
                }
            }
            throw e;
        }
        cluster.setContainerId(containerId);
        if (vpcLease != null) {
            Ec2Service ec2 = ec2Service();
            Optional<String> attachedNetwork = ec2 != null
                    ? ec2.attachContainerToVpc(vpcLease.accountId(),
                            vpcLease.region(), vpcLease.vpcId(), vpcLease.subnetId(),
                            containerId, vpcLease.address())
                    : Optional.empty();
            if (attachedNetwork == null || attachedNetwork.isEmpty()) {
                if (hadLeaseBeforeLaunch || (previousNodeIp != null && !previousNodeIp.isBlank())) {
                    // Issue #5501: A restored cluster's k3s data already holds its node IP; fail
                    // the start and retain the address rather than booting on the bridge without it.
                    lifecycleManager.removeIfExistsStrict(containerName, true);
                    cluster.setContainerId(null);
                    throw new IllegalStateException("Failed to attach restored EKS container "
                            + containerName + " to VPC " + vpcLease.vpcId());
                }
                if (podNetwork != null) {
                    LOG.warnv("EKS cluster {0} runs its pods on k3s''s own network: its node could not join VPC {1}",
                            cluster.getName(), vpcLease.vpcId());
                    dropVpcPodNetwork(specBuilder, serverArgs, podNetwork, clusterCidr, nodeIp);
                }
                releaseClusterVpcLease(cluster, resourceName);
                if (cluster != null) {
                    cluster.setNodePrivateIp(null);
                    cluster.setNodePodPrefixes(null);
                }
                vpcLease = null;
                lifecycleManager.removeIfExistsStrict(containerName, true);
                serverArgs.remove("--node-ip=" + nodeIp);
                specBuilder.withCmd(buildRshareWrappedCmd(serverArgs));
                spec = specBuilder.build();
                containerId = lifecycleManager.create(spec);
                cluster.setContainerId(containerId);
            }
        }
        if (webhookLocalFile != null) {
            copyWebhookIntoContainer(containerId, webhookLocalFile, cluster.getName());
        }
        if (auditPolicyLocalFile != null) {
            copyAuditPolicyIntoContainer(containerId, auditPolicyLocalFile, cluster.getName());
        }
        injectEcrRegistryMirror(containerId, cluster, registries);
        linkContainerdCertsDir(containerId, cluster.getName());
        registerPodIdentityWebhook(containerId, cluster);
        applyBlockStorage(containerId, cluster);
        applyCompute(containerId, cluster);
        if (signingKeyFiles != null) {
            copySigningKeysIntoContainer(containerId, signingKeyFiles, cluster.getName());
        }
        // Registered before the container starts, so no readiness check can see it started without its setup.
        deferGuestSetup(containerId, false);
        ContainerInfo info;
        try {
            if (podResolvConf != null) {
                // Part of the start: k3s would hand its pods a resolver file that is not there, and no pod could start.
                RetryingTarCopier.copyBytes(lifecycleManager.getDockerClient(), containerId, "/etc",
                        POD_RESOLV_CONF_TAR_ENTRY, podResolvConf.getBytes(StandardCharsets.UTF_8), 0644,
                        RetryingTarCopier.DEFAULT_MAX_ATTEMPTS, archiveRetryBackoffMillis);
            }
            info = lifecycleManager.startCreated(containerId, spec);
        } catch (Exception e) {
            abandonGuestSetup(containerId, "its container could not be started");
            try {
                lifecycleManager.removeIfExistsStrict(containerName, true);
            } catch (RuntimeException cleanup) {
                e.addSuppressed(cleanup);
                LOG.warnv("Could not remove failed EKS container {0}; retaining it for cleanup retry: {1}",
                        containerName, cleanup.getMessage());
                if (vpcLease != null) {
                    Ec2Service ec2 = ec2Service();
                    if (ec2 != null) {
                        ec2.detachContainerFromVpc(vpcLease.accountId(), vpcLease.region(), vpcLease.vpcId(),
                                containerId);
                    }
                }
            }
            if (!hadLeaseBeforeLaunch) {
                releaseClusterVpcLease(cluster, resourceName);
                if (cluster != null) {
                    cluster.setNodePrivateIp(restoredNodeIp);
                    cluster.setNodePodPrefixes(restoredNodeIp != null ? previousPodPrefixes : null);
                }
            }
            throw e;
        }

        applyEndpoints(cluster, containerName, hostPort, info, endpointHost);
        recordClusterNodeInstance(cluster, containerId);
        activeClusters.put(clusterResourceName(cluster), cluster);
        attachClusterLogs(cluster, false, true, false);

        LOG.infov("k3s container {0} started for cluster {1} on port {2} (internal: {3})",
                containerId, cluster.getName(), String.valueOf(hostPort), cluster.getInternalEndpoint());
    }

    /**
     * Re-latches a persisted cluster onto its k3s container after a Floci restart. A surviving
     * container - running, or stopped by a Docker daemon reboot - is adopted (started if needed),
     * keeping its published API server port and data volume, so the cluster's workloads come back
     * as they were. A survivor whose node capacity changed, or that is not on the Docker network
     * Floci now uses, is replaced on the same volume. When the container is gone, the cluster is
     * recreated via {@link #startCluster}, on the API server port its endpoint names if that port is
     * still free; the named k3s data volume is reused if it survived.
     * Callers should put the cluster back into CREATING so the readiness poller re-verifies the API
     * server and re-extracts the certificate authority before marking it ACTIVE again.
     */
    public void restoreCluster(Cluster cluster) {
        String endpointHost = endpointHost();
        if (cluster.getDockerName() == null) {
            cluster.setDockerName(resolveRestoredDockerName(cluster));
        }
        String containerName = cluster.getDockerName();
        var existing = lifecycleManager.findByName(containerName);
        if (existing.isEmpty()) {
            LOG.infov("No surviving k3s container for EKS cluster {0}; recreating it "
                    + "(a surviving data volume is reused)", cluster.getName());
            if (cluster.getDefaultStorageClass() == null) {
                boolean targetSetting = config.services().eks().defaultStorageClass();
                LOG.infov("Recreating EKS cluster {0} with defaultStorageClass={1} "
                        + "(set floci.services.eks.default-storage-class: true to retain bundled local-path)",
                        cluster.getName(), String.valueOf(targetSetting));
                cluster.setDefaultStorageClass(targetSetting);
            }
            startCluster(cluster);
            return;
        }

        String desiredCapacity = capacityLabel(cluster, resolveNodeCapacity(cluster));
        Map<String, String> existingLabels = existing.get().getLabels();
        boolean capacityChanged = existingLabels == null
                || !desiredCapacity.equals(existingLabels.get(NODE_CAPACITY_LABEL));
        if (existingLabels != null && existingLabels.containsKey(DEFAULT_STORAGE_CLASS_LABEL)) {
            cluster.setDefaultStorageClass(Boolean.parseBoolean(existingLabels.get(DEFAULT_STORAGE_CLASS_LABEL)));
        } else if (cluster.getDefaultStorageClass() == null) {
            // Surviving container was created before defaultStorageClass was configurable:
            // preserve prior behavior with local-storage enabled.
            cluster.setDefaultStorageClass(true);
            LOG.infov("Adopting pre-upgrade EKS cluster {0} with local-storage enabled", cluster.getName());
        }
        if (!adoptSurvivingCluster(cluster, existing.get().getId(), certificateHost(existingLabels))) {
            // The survivor is replaced, and it may still publish the port the endpoint names: it goes before a
            // port is chosen, so the new container can take that port and the endpoint stays.
            lifecycleManager.removeIfExistsStrict(containerName);
            startCluster(cluster);
            return;
        }
        // A node started before its mounts were shared keeps private mounts, on which a CSI node plugin's
        // Bidirectional mounts fail: it is replaced on the same data volume, as one with another capacity is.
        boolean privateMounts = existingLabels == null || !"true".equals(existingLabels.get(SHARED_MOUNTS_LABEL));
        // One started with another pod network or registry mirror is replaced too. ServiceLB is a k3s server flag: a
        // node started with it on while load balancing is on, or the reverse, is replaced too. A node from before the
        // label ran ServiceLB.
        boolean serviceLbChanged = serviceLb(cluster) != (existingLabels == null
                || !"false".equals(existingLabels.get(SERVICE_LB_LABEL)));
        if (capacityChanged || privateMounts || serviceLbChanged || onAnotherNetwork(existing.get())
                || !endpointHost.equals(certificateHost(existingLabels)) || podNetworkChanged(cluster, existingLabels)
                || registryMirrorChanged(cluster, existingLabels)) {
            replaceClusterContainer(cluster, null);
        }
    }

    // Floci in a container reaches k3s over the Docker network; on the host it uses the published port.
    private boolean onAnotherNetwork(Container survivor) {
        return containerDetector.isRunningInContainer() && ContainerNetworks.onAnotherNetwork(
                survivor, containerBuilder.resolveDockerNetwork(config.services().eks().dockerNetwork()).orElse(null));
    }

    /** An adopted container keeps advertising the host its certificate was created for. */
    private boolean adoptSurvivingCluster(Cluster cluster, String containerId, String certificateHost) {
        if (config.services().eks().irsaSigningKey() && oidcService != null) {
            reinjectSigningKeys(containerId, cluster);
        }

        // adopt() starts a container a Docker daemon reboot stopped, which boots k3s again, cgroup step included.
        deferGuestSetup(containerId, true);
        ContainerInfo info;
        try {
            info = lifecycleManager.adopt(containerId, List.of(K3S_API_SERVER_PORT),
                    containerBuilder.resolveDockerNetwork(config.services().eks().dockerNetwork()));
        } catch (Exception e) {
            abandonGuestSetup(containerId, "its container could not be adopted");
            LOG.warnv("Could not adopt surviving k3s container {0} for EKS cluster {1} ({2}); recreating it",
                    cluster.getDockerName(), cluster.getName(), e.getMessage());
            return false;
        }

        var publishedPort = info.publishedHostPort(K3S_API_SERVER_PORT);
        if (publishedPort.isEmpty()) {
            abandonGuestSetup(containerId, "its container publishes no API server port");
            LOG.warnv("Surviving k3s container {0} publishes no API server port; recreating it",
                    cluster.getDockerName());
            return false;
        }

        int hostPort = publishedPort.getAsInt();
        // Keep the allocator away from a port Docker already holds for this cluster.
        portAllocator.markReserved(hostPort);
        cluster.setContainerId(info.containerId());
        cluster.setHostPort(hostPort);
        applyEndpoints(cluster, cluster.getDockerName(), hostPort, info, certificateHost);
        if (cluster.getNodePrivateIp() != null && !cluster.getNodePrivateIp().isBlank()) {
            acquireClusterVpcLease(cluster, resolveClusterAccountId(cluster), clusterRegion(cluster), false);
        }
        recordClusterNodeInstance(cluster, info.containerId());
        // The manifest written at creation names the address and certificate Floci had then.
        registerPodIdentityWebhook(info.containerId(), cluster,
                () -> survivingNodeResolvesThroughFloci(info.containerId()));
        applyBlockStorage(info.containerId(), cluster);
        applyCompute(info.containerId(), cluster);
        applyComputeLabel(cluster, info.containerId());
        activeClusters.put(clusterResourceName(cluster), cluster);
        attachClusterLogs(cluster, true, true, false);

        LOG.infov("Adopted surviving k3s container {0} for EKS cluster {1} on port {2} (internal: {3})",
                info.containerId(), cluster.getName(), String.valueOf(hostPort), cluster.getInternalEndpoint());
        return true;
    }

    /** Keep the old container stopped but recoverable until its replacement has started. */
    private boolean replaceClusterContainer(Cluster cluster, String previousType) {
        String oldId = cluster.getContainerId();
        int oldPort = cluster.getHostPort();
        String containerName = cluster.getDockerName();
        String backupName = capacityBackupName(cluster);
        Map<String, String> oldLabels = lifecycleManager.findByName(containerName).map(Container::getLabels).orElse(null);
        String oldCertificateHost = certificateHost(oldLabels);
        List<String> oldPodPrefixes = labelPodPrefixes(startedPodNetwork(oldLabels));
        DockerClient docker = lifecycleManager.getDockerClient();
        try {
            lifecycleManager.removeIfExistsStrict(backupName, true);
        } catch (RuntimeException cleanup) {
            LOG.warnv("Could not clear prior EKS capacity backup for cluster {0}: {1}; keeping current node",
                    cluster.getName(), cleanup.getMessage());
            restorePreviousNodeType(cluster, previousType);
            return false;
        }
        boolean renamed = false;
        VpcLease lease = resolveClusterVpcLease(cluster);
        Ec2Service ec2 = ec2Service();
        try {
            docker.renameContainerCmd(oldId).withName(backupName).exec();
            renamed = true;
            docker.stopContainerCmd(oldId).exec();
            if (lease != null && ec2 != null) {
                ec2.detachContainerFromVpc(lease.accountId(), lease.region(), lease.vpcId(), oldId);
            }
            unregisterMetadataEndpoint(cluster);
            closeQuietly(clusterLogHandles.remove(clusterResourceName(cluster)));
            abandonGuestSetup(oldId, "its container was replaced");
            cluster.setContainerId(null);
            startCluster(cluster, oldPort);
            LOG.infov("Replaced the k3s container of EKS cluster {0}", cluster.getName());
        } catch (RuntimeException replacement) {
            LOG.warnv("Could not replace the k3s container of EKS cluster {0}: {1}; restoring surviving node",
                    cluster.getName(), replacement.getMessage());
            restorePreviousNodeType(cluster, previousType);
            retainPodPrefixes(cluster, oldPodPrefixes);
            if (!renamed) {
                return false;
            }
            try {
                lifecycleManager.removeIfExistsStrict(containerName, true);
                docker.renameContainerCmd(oldId).withName(containerName).exec();
                if (lease != null && ec2 != null) {
                    Optional<String> reattached = ec2.attachContainerToVpc(lease.accountId(),
                            lease.region(), lease.vpcId(), lease.subnetId(), oldId, lease.address());
                    if (reattached == null || reattached.isEmpty()) {
                        throw new IllegalStateException("Could not reattach previous EKS container " + oldId);
                    }
                }
                if (!adoptSurvivingCluster(cluster, oldId, oldCertificateHost)) {
                    throw new IllegalStateException("Could not adopt previous EKS container " + oldId);
                }
            } catch (RuntimeException rollback) {
                throw new IllegalStateException("Could not restore EKS cluster " + cluster.getName()
                        + " after its k3s container replacement failed", rollback);
            }
            return false;
        }
        try {
            lifecycleManager.removeIfExistsStrict(backupName, true);
        } catch (RuntimeException cleanup) {
            LOG.warnv("EKS cluster {0} replacement is running, but its stopped capacity backup"
                    + " could not be removed: {1}; cluster deletion will retry",
                    cluster.getName(), cleanup.getMessage());
        }
        return true;
    }

    private static void restorePreviousNodeType(Cluster cluster, String previousType) {
        if (previousType != null) {
            cluster.setNodeInstanceType(previousType);
        }
    }

    private String capacityBackupName(Cluster cluster) {
        // The marker must precede the account qualifier: no account ID can be capacity-backup,
        // and no cluster name can contain a dot. A suffix can collide with another account's node.
        return ContainerStorageHelper.resourceName(config, "eks", null,
                "capacity-backup." + accountQualifiedClusterName(cluster));
    }

    /**
     * Sets the cluster's public and internal endpoints for a started or adopted container.
     * Public endpoint: see floci.services.eks.endpoint-mode. `host` (default) is the host-reachable
     * published port on floci.services.eks.endpoint-host, or localhost (the k3s cert carries a SAN for
     * either, so it verifies against the CA that describe-cluster returns); `network` is the container
     * DNS name (pre-#1118 behaviour).
     * The internal endpoint uses the resolved container IP so the readiness poller works from inside
     * the Docker network (where localhost:<hostPort> would not reach the k3s container).
     */
    private void applyEndpoints(Cluster cluster, String containerName, int hostPort, ContainerInfo info,
                                String endpointHost) {
        cluster.setEndpoint(resolvePublicEndpoint(
                containerDetector.isRunningInContainer(), config.services().eks().endpointMode(),
                containerName, endpointHost, hostPort));

        if (containerDetector.isRunningInContainer()) {
            ContainerLifecycleManager.EndpointInfo ep = info.getEndpoint(K3S_API_SERVER_PORT);
            cluster.setInternalEndpoint(ep != null
                    ? "https://" + ep.host() + ":" + ep.port()
                    : "https://localhost:" + hostPort);
        } else {
            cluster.setInternalEndpoint("https://localhost:" + hostPort);
        }
    }

    /**
     * Checks whether the k3s API server is ready: it answers on {@code /livez} and k3s has deployed its addons. The
     * first time the API answers after the container started, this also runs the guest setup the start deferred
     * (see {@link #pendingGuestSetups}), so a cluster is only reported ready once its node is set up.
     */
    public boolean isReady(Cluster cluster) {
        // Prefer internalEndpoint (IP-based) for connectivity — works on both user-defined
        // networks and the default bridge where container-name DNS is unavailable.
        String endpoint = cluster.getInternalEndpoint() != null
                ? cluster.getInternalEndpoint()
                : cluster.getEndpoint();
        // Read once: a node replaced while the API is probed must not have the old node's answer acted on.
        String containerId = cluster.getContainerId();
        if (endpoint == null || containerId == null) {
            return false;
        }
        // Nothing is executed in the container until its API answers: see pendingGuestSetups.
        if (!apiAnswers(endpoint)) {
            return false;
        }
        GuestSetup setup = pendingGuestSetups.get(containerId);
        if (setup != null) {
            startGuestSetup(cluster, containerId, setup);
            return false;
        }
        return addonsDeployed(containerId);
    }

    /** Whether the k3s API server answers on {@code /livez}, which is usually unauthenticated. */
    boolean apiAnswers(String endpoint) {
        try {
            HttpURLConnection conn = (HttpURLConnection) URI.create(endpoint + "/livez").toURL().openConnection();
            conn.setRequestMethod("GET");
            conn.setConnectTimeout(2000);
            conn.setReadTimeout(2000);
            // k3s uses self-signed TLS — disable verification
            if (conn instanceof javax.net.ssl.HttpsURLConnection https) {
                disableSslVerification(https);
            }
            int code = conn.getResponseCode();
            return code == 200 || code == 401 || code == 403;
        } catch (Exception e) {
            return false;
        }
    }

    /** Holds a container's guest setup until its API server answers, which {@link #isReady} checks. */
    void deferGuestSetup(String containerId, boolean logsFromNow) {
        GuestSetup previous = pendingGuestSetups.put(containerId, new GuestSetup(logsFromNow));
        if (previous != null) {
            abandon(previous, "its container was started again");
        }
    }

    void abandonGuestSetup(String containerId, String reason) {
        GuestSetup setup = containerId == null ? null : pendingGuestSetups.remove(containerId);
        if (setup != null) {
            abandon(setup, reason);
        }
    }

    /** Waits for a setup already running, so what it attached is there for the caller to release. */
    private static void abandon(GuestSetup setup, String reason) {
        synchronized (setup) {
            setup.done.completeExceptionally(new IllegalStateException(reason));
        }
    }

    /** Whether the container may be executed in: its API server has answered since it started. */
    private boolean guestReachable(String containerId) {
        GuestSetup setup = pendingGuestSetups.get(containerId);
        return setup == null || setup.apiAnswered;
    }

    /** For tests: whether the container's guest setup still waits. */
    boolean awaitingGuestSetup(String containerId) {
        return pendingGuestSetups.containsKey(containerId);
    }

    private void startGuestSetup(Cluster cluster, String containerId, GuestSetup setup) {
        synchronized (setup) {
            if (setup.started || setup.done.isDone()) {
                return;
            }
            setup.started = true;
            setup.apiAnswered = true;
        }
        guestSetupExecutor.execute(() -> runGuestSetup(cluster, containerId, setup));
    }

    /** Runs the node's guest setup on the calling thread, as once its API has answered; for tests. */
    void setUpGuest(Cluster cluster) {
        String containerId = cluster.getContainerId();
        GuestSetup setup = containerId == null ? null : pendingGuestSetups.get(containerId);
        if (setup != null) {
            runGuestSetup(cluster, containerId, setup);
        }
    }

    /**
     * Runs the setup a started container waited for, once its API server has answered: the link-local metadata
     * endpoint, the Pod Identity forward, the VPC routes, the audit log follower, and the node registration listeners
     * (attaching a volume makes its device node in the container), all of which execute in it.
     */
    private void runGuestSetup(Cluster cluster, String containerId, GuestSetup setup) {
        synchronized (setup) {
            if (setup.done.isDone()) {
                return;
            }
            setup.apiAnswered = true;
            try {
                if (containerId.equals(cluster.getContainerId())) {
                    configureLinkLocalMetadataEndpoint(cluster, containerId);
                    configurePodIdentityForward(cluster, containerId);
                    configureVpcRoutes(cluster, containerId);
                    attachClusterLogs(cluster, setup.logsFromNow, false, true);
                    notifyNodeRegistrationListeners(cluster);
                    LOG.infov("Set up the node of EKS cluster {0} once its API server answered", cluster.getName());
                }
            } catch (RuntimeException e) {
                LOG.warnv("Could not set up the node of EKS cluster {0}: {1}", cluster.getName(), e.getMessage());
            } finally {
                pendingGuestSetups.remove(containerId, setup);
                setup.done.complete(null);
            }
        }
    }

    /** For tests: how long node group user data waits for its node's guest setup. */
    void guestSetupTimeout(Duration timeout) {
        this.guestSetupTimeout = timeout;
    }

    /**
     * k3s creates its Addon objects only after its embedded CRDs are established. A k3s stopped
     * before that leaves a CRD unestablished for good, and every later start waits on it forever.
     */
    private boolean addonsDeployed(String containerId) {
        ContainerExec.Result addons = execInContainerForResult(containerId,
                new String[]{"kubectl", "--request-timeout=2s", "get", "addons.k3s.cattle.io",
                        "-n", "kube-system", "-o", "name"}, 10);
        return addons.exitCode() == 0 && addons.stdout() != null
                && addons.stdout().contains("addon.k3s.cattle.io/");
    }

    /**
     * Extracts the kubeconfig from the running k3s container, rewrites the server URL,
     * and sets the certificate authority data on the cluster.
     */
    public void finalizeCluster(Cluster cluster) {
        String containerId = cluster.getContainerId();
        if (containerId == null) {
            return;
        }

        try {
            String kubeconfigYaml = execInContainer(containerId,
                    new String[]{"cat", "/etc/rancher/k3s/k3s.yaml"});

            // Extract CA data
            String caData = extractYamlField(kubeconfigYaml, "certificate-authority-data");
            if (caData != null) {
                cluster.setCertificateAuthority(new CertificateAuthority(caData.trim()));
            }

            pruneLegacyClusterNodes(cluster, containerId);

            registerAllJoinedNodes(cluster, containerId);

            applyComputeLabel(cluster, containerId);

            LOG.infov("Finalized EKS cluster {0} with CA data extracted", cluster.getName());
        } catch (Exception e) {
            LOG.warnv("Could not extract kubeconfig for cluster {0}: {1}",
                    cluster.getName(), e.getMessage());
        }
    }

    private void pruneLegacyClusterNodes(Cluster cluster, String containerId) {
        try {
            String expectedNodeName = deriveClusterNodePrivateDnsName(cluster);
            ContainerExec.Result nodeResult = execInContainerForResult(containerId,
                    new String[]{"kubectl", "get", "nodes", "-o",
                            "jsonpath={range .items[*]}{.metadata.name}{\" \"}{range .status.conditions[?(@.type==\"Ready\")]}{.status}{end}{\"\\n\"}{end}"}, 10);
            if (nodeResult.exitCode() == 0 && nodeResult.stdout() != null && !nodeResult.stdout().isBlank()) {
                for (String line : nodeResult.stdout().split("\\r?\\n")) {
                    String[] parts = line.trim().split("\\s+");
                    if (parts.length >= 1 && !parts[0].isBlank()) {
                        String node = parts[0];
                        String readyStatus = parts.length > 1 ? parts[1] : "";
                        if (!node.equals(expectedNodeName) && !isJoinedNode(cluster, node) && !"True".equalsIgnoreCase(readyStatus)) {
                            execInContainerForResult(containerId,
                                    new String[]{"kubectl", "delete", "node", node}, 10);
                            LOG.infov("Removed stale legacy node {0} from EKS cluster {1}", node, cluster.getName());
                        }
                    }
                }
            }
        } catch (Exception e) {
            LOG.warnv("Could not prune legacy nodes for cluster {0}: {1}", cluster.getName(), e.getMessage());
        }
    }

    /**
     * Stops and removes the k3s container for the given cluster. The k3s data volume follows the
     * storage prune policy ({@link ContainerStorageHelper#removeNamedVolume}): it is only removed
     * in {@code memory} storage mode or when {@code prune-volumes-on-delete} is set, so a persisted
     * cluster's workloads survive a Floci restart and are re-latched by {@link #restoreCluster}.
     */
    public void stopCluster(Cluster cluster) {
        String resourceName = clusterResourceName(cluster);
        abandonGuestSetup(cluster.getContainerId(), "its cluster was deleted");
        if (cluster.getContainerId() == null) {
            releaseClusterVpcLease(cluster, resourceName);
            unregisterMetadataEndpoint(cluster);
            closeQuietly(clusterLogHandles.remove(resourceName));
            return;
        }
        Closeable logStream = clusterLogHandles.get(resourceName);
        // Strict: a container Docker could not remove may still be running and publishing the
        // port, so the delete fails and keeps the cluster record, its metadata endpoint, log
        // handle and port reservation; a retried delete releases them once the container is gone.
        lifecycleManager.stopAndRemoveStrict(cluster.getContainerId(), logStream, 5, true);
        releaseClusterVpcLease(cluster, resourceName);
        unregisterMetadataEndpoint(cluster);
        // Removed by value so an overlapping delete cannot drop a handle it does not own.
        if (logStream != null) {
            clusterLogHandles.remove(resourceName, logStream);
        }
        int hostPort;
        // Read and cleared together so overlapping deletes, or a delete retried after a failed
        // backup cleanup, cannot release a port that has since been reused.
        synchronized (cluster) {
            hostPort = cluster.getHostPort();
            cluster.setHostPort(0);
        }
        if (hostPort > 0) {
            portAllocator.release(hostPort);
        }
        if (cluster.getDockerName() != null) {
            // A failed backup cleanup must not leave the live node running. Keep the cluster
            // record so explicit deletion can be retried once Docker accepts the removal.
            lifecycleManager.removeIfExistsStrict(capacityBackupName(cluster), true);
        }
        ContainerStorageHelper.removeNamedVolume(config, lifecycleManager, clusterResourceName(cluster));
        LOG.infov("Stopped k3s container for cluster {0}", cluster.getName());
    }

    String selectNodeInstanceType(String requestedType) {
        if (instanceTypeCatalog.find(requestedType).isPresent()) {
            return requestedType;
        }
        LOG.warnv("EKS node instance type {0} is absent from the EC2 catalog; using {1}",
                requestedType, DEFAULT_NODE_INSTANCE_TYPE);
        return DEFAULT_NODE_INSTANCE_TYPE;
    }

    private EksNodeCapacity.Limits resolveNodeCapacity(Cluster cluster) {
        try {
            Info host = lifecycleManager.getDockerClient().infoCmd().exec();
            CatalogInstanceType type = instanceTypeCatalog.find(sizingInstanceType(cluster)).orElseThrow();
            EksNodeCapacity.Limits limits = EksNodeCapacity.calculate(type, host.getMemTotal(), host.getNCPU(),
                    config.services().eks().maxMemoryMib(), config.services().eks().maxVcpus());
            if (limits == null) {
                EksNodeCapacity.Limits configured = explicitCeilingWithoutHostInfo(cluster);
                if (configured != null) {
                    return configured;
                }
                LOG.warnv("EKS cluster {0} cannot fit node type {1} and its kubelet reservations"
                        + " within the Docker host; starting without resource limits",
                        cluster.getName(), type.instanceType);
            } else if (limits.reducedReservations()) {
                LOG.warnv("EKS cluster {0} memory ceiling reduces kubelet memory reservations below"
                        + " the EKS AMI defaults", cluster.getName());
            }
            return limits;
        } catch (Exception e) {
            LOG.warnv("EKS cluster {0} Docker host capacity unavailable: {1}",
                    cluster.getName(), e.getMessage());
            return explicitCeilingWithoutHostInfo(cluster);
        }
    }

    private EksNodeCapacity.Limits explicitCeilingWithoutHostInfo(Cluster cluster) {
        int maxMemoryMib = config.services().eks().maxMemoryMib();
        int maxVcpus = config.services().eks().maxVcpus();
        if (maxMemoryMib <= 0 && maxVcpus <= 0) {
            return null;
        }
        int vcpus = instanceTypeCatalog.find(sizingInstanceType(cluster))
                .map(type -> maxVcpus > 0 ? Math.min(type.vcpu, maxVcpus) : 0)
                .orElse(maxVcpus);
        LOG.warnv("Applying explicit EKS cluster {0} ceiling without kubelet reservations"
                + " because Docker host capacity is unavailable", cluster.getName());
        return EksNodeCapacity.explicitCeilingWithoutHostInfo(maxMemoryMib, vcpus);
    }

    /**
     * Carries the sizing type only when it is configured, so a survivor created without the
     * setting keeps its label and is not recreated after an upgrade. Changing the setting changes
     * the label, which recreates the node on the next start with its data volume.
     */
    private String capacityLabel(Cluster cluster, EksNodeCapacity.Limits limits) {
        String types = nodeInstanceType(cluster) + configuredSizingInstanceType().map(type -> "@" + type).orElse("");
        if (limits == null) {
            return types + ":unbounded";
        }
        return types + ":" + limits.memoryBytes() + ":" + limits.vcpus()
                + ":" + limits.systemMemoryMib() + ":" + limits.kubeMemoryMib()
                + ":" + limits.evictionMemoryMib() + ":" + limits.systemCpuMilli()
                + ":" + limits.kubeCpuMilli() + ":" + limits.reducedReservations()
                + ":" + limits.kubeletArgsEnabled();
    }

    /**
     * The catalog type the node container is sized from: {@code floci.services.eks.node-instance-type}
     * when set, otherwise the node group's. Only the container's memory, CPU and kubelet reservations
     * follow it; the node group, the node's instance-type label and the synthesized EC2 instance keep
     * {@link #nodeInstanceType(Cluster)}.
     */
    String sizingInstanceType(Cluster cluster) {
        return configuredSizingInstanceType().orElseGet(() -> nodeInstanceType(cluster));
    }

    private Optional<String> configuredSizingInstanceType() {
        Optional<String> configured = config.services().eks().nodeInstanceType()
                .map(String::trim)
                .filter(type -> !type.isEmpty());
        if (configured.isEmpty() || instanceTypeCatalog.find(configured.get()).isPresent()) {
            return configured;
        }
        LOG.warnv("floci.services.eks.node-instance-type {0} is absent from the EC2 catalog; sizing EKS nodes "
                + "from their node group''s instance type", configured.get());
        return Optional.empty();
    }

    static String nodeInstanceType(Cluster cluster) {
        return cluster != null && cluster.getNodeInstanceType() != null
                ? cluster.getNodeInstanceType() : DEFAULT_NODE_INSTANCE_TYPE;
    }

    /**
     * Whether the cluster's node gets k3s's ServiceLB: not while EKS Auto Mode load balancing is on, which serves
     * the LoadBalancer Services of no class itself, as {@code eks.amazonaws.com/nlb} is then the cluster default.
     */
    static boolean serviceLb(Cluster cluster) {
        return cluster == null || cluster.getKubernetesNetworkConfig() == null
                || !ElasticLoadBalancing.loadBalancingOn(cluster.getKubernetesNetworkConfig().getElasticLoadBalancing());
    }

    /**
     * Whether the cluster's running node was started without ServiceLB, leaving the LoadBalancer Services of no
     * class to EKS Auto Mode. A node whose load balancing was turned on after it started keeps ServiceLB, and with
     * it those Services, until it is next replaced.
     */
    public boolean runsWithoutServiceLb(Cluster cluster) {
        return cluster.getDockerName() != null && lifecycleManager.findByName(cluster.getDockerName())
                .map(Container::getLabels)
                .map(labels -> "false".equals(labels.get(SERVICE_LB_LABEL)))
                .orElse(false);
    }

    /**
     * Replaces the cluster's node on its data volume when it runs ServiceLB and load balancing is on, or runs
     * without it and load balancing is off, so it starts as {@link #serviceLb} says; a node that already agrees is
     * kept. Answers false when the replacement failed and the old node was restored.
     */
    boolean replaceNodeForServiceLb(Cluster cluster) {
        if (cluster.getContainerId() == null || runsWithoutServiceLb(cluster) != serviceLb(cluster)) {
            return true;
        }
        return replaceClusterContainer(cluster, null);
    }

    /** Recreate the shared node with its named data volume intact, restoring its old type on failure. */
    boolean restartForNodeCapacity(Cluster cluster, String previousType) {
        if (cluster.getContainerId() == null) {
            return true;
        }
        return replaceClusterContainer(cluster, previousType);
    }

    /**
     * Releases Floci's hold on the given cluster (its metadata endpoint and log stream) and leaves
     * the k3s container running, so a later Floci start can re-latch it through
     * {@link #restoreCluster}. Used on shutdown when keep-running-on-shutdown is enabled.
     */
    public void detachCluster(Cluster cluster) {
        unregisterMetadataEndpoint(cluster);
        closeQuietly(clusterLogHandles.remove(clusterResourceName(cluster)));
        if (cluster.getContainerId() != null) {
            LOG.infov("Leaving k3s container for cluster {0} running", cluster.getName());
        }
    }

    private static void closeQuietly(Closeable closeable) {
        if (closeable != null) {
            try {
                closeable.close();
            } catch (Exception ignored) {
                // Swallowing is safe because closing an already closed or failed log stream during shutdown is best-effort.
            }
        }
    }

    /**
     * Checks whether the cluster has control plane logging enabled for either api or audit.
     */
    public static boolean hasLoggingEnabled(Cluster cluster) {
        return hasLoggingEnabled(cluster, "api") || hasLoggingEnabled(cluster, "audit");
    }

    /**
     * Checks whether the cluster has the specified control plane log type enabled.
     */
    public static boolean hasLoggingEnabled(Cluster cluster, String logType) {
        if (cluster == null || cluster.getLogging() == null || logType == null) {
            return false;
        }
        List<LogSetup> clusterLogging = cluster.getLogging().getClusterLogging();
        if (clusterLogging == null || clusterLogging.isEmpty()) {
            return false;
        }
        for (LogSetup setup : clusterLogging) {
            if (Boolean.TRUE.equals(setup.getEnabled()) && setup.getTypes() != null
                    && setup.getTypes().contains(logType)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Attaches CloudWatch Logs delivery for the cluster container if logging is enabled.
     */
    public void attachClusterLogs(Cluster cluster) {
        attachClusterLogs(cluster, false);
    }

    /**
     * Attaches CloudWatch Logs delivery for an adopted cluster container, only forwarding lines
     * emitted from now on.
     */
    public void attachClusterLogsFromNow(Cluster cluster) {
        attachClusterLogs(cluster, true);
    }

    private void attachClusterLogs(Cluster cluster, boolean fromNow) {
        attachClusterLogs(cluster, fromNow, true, true);
    }

    /**
     * Attaches the control plane log stream, read through Docker's log API, and the audit log follower, a
     * {@code tail -F} executed in the container, either or both. Attaching the stream replaces whatever was attached;
     * the follower alone is added to it, as a started node gets its stream at once and its follower once its API has
     * answered (see {@link #pendingGuestSetups}).
     */
    private void attachClusterLogs(Cluster cluster, boolean fromNow, boolean api, boolean audit) {
        if (logStreamer == null || cluster == null || !hasLoggingEnabled(cluster)) {
            return;
        }
        String containerId = cluster.getContainerId();
        if (containerId == null || containerId.isBlank()) {
            return;
        }
        String resourceName = clusterResourceName(cluster);
        if (api) {
            // Logs are attached only for a container that was just started or adopted, so a handle
            // still registered under this name follows an earlier container: close it rather than
            // leave the new cluster without control-plane logs.
            closeQuietly(clusterLogHandles.remove(resourceName));
        }
        String logGroup = "/aws/eks/" + cluster.getName() + "/cluster";
        String hash = containerId.length() >= 32 ? containerId.substring(0, 32) : containerId;
        String region = clusterRegion(cluster);
        String accountId = resolveClusterAccountId(cluster);

        List<Closeable> handles = new ArrayList<>();
        if (api && hasLoggingEnabled(cluster, "api")) {
            try {
                String logStream = "kube-apiserver-" + hash;
                Closeable handle = fromNow
                        ? logStreamer.attachPolledFromNowForAccount(
                                accountId, containerId, logGroup, logStream, region, "eks:" + cluster.getName())
                        : logStreamer.attachPolledForAccount(
                                accountId, containerId, logGroup, logStream, region, "eks:" + cluster.getName());
                if (handle != null) {
                    handles.add(handle);
                }
            } catch (Exception e) {
                LOG.warnv("Could not attach control plane log stream for EKS cluster {0}: {1}",
                        cluster.getName(), e.getMessage());
            }
        }

        if (audit && hasLoggingEnabled(cluster, "audit")) {
            try {
                Closeable auditHandle = attachAuditLogFollower(
                        containerId, accountId, logGroup, hash, region, cluster.getName(), fromNow);
                if (auditHandle != null) {
                    handles.add(auditHandle);
                }
            } catch (Exception e) {
                LOG.warnv("Could not attach audit log follower for EKS cluster {0}: {1}",
                        cluster.getName(), e.getMessage());
            }
        }

        if (handles.isEmpty()) {
            return;
        }
        Closeable attached = handles.size() == 1 ? handles.getFirst() : closingAll(handles);
        if (api) {
            closeQuietly(clusterLogHandles.put(resourceName, attached));
        } else {
            clusterLogHandles.merge(resourceName, attached,
                    (stream, follower) -> closingAll(List.of(stream, follower)));
        }
    }

    private static Closeable closingAll(List<Closeable> handles) {
        return () -> {
            for (Closeable h : handles) {
                closeQuietly(h);
            }
        };
    }

    private Closeable attachAuditLogFollower(String containerId, String accountId, String logGroup,
                                             String hash, String region, String clusterName, boolean fromNow) {
        DockerClient dockerClient = lifecycleManager.getDockerClient();
        if (dockerClient == null) {
            return null;
        }
        String logStream = "kube-apiserver-audit-" + hash;
        logStreamer.ensureLogGroupAndStreamForAccount(accountId, logGroup, logStream, region);
        String tailLineArg = fromNow ? "0" : "+1";
        String[] cmd = new String[] {
                "sh", "-c", "touch " + AUDIT_LOG_CONTAINER_PATH + " && exec tail -n " + tailLineArg + " -F " + AUDIT_LOG_CONTAINER_PATH
        };
        ExecCreateCmdResponse execCreate = dockerClient
                .execCreateCmd(containerId)
                .withCmd(cmd)
                .withAttachStdout(true)
                .withAttachStderr(false)
                .exec();
        return lifecycleManager.getStreamingDockerClient()
                .execStartCmd(execCreate.getId())
                .exec(logStreamer.execLogCallbackForAccount(
                        accountId, logGroup, logStream, region, "eks-audit:" + clusterName, false));
    }

    Closeable getLogHandle(Cluster cluster) {
        return clusterLogHandles.get(clusterResourceName(cluster));
    }

    /**
     * Docker container/volume name for a cluster: the name already resolved for this record
     * ({@link Cluster#getDockerName()}, set by startCluster/restoreCluster), or the
     * account-qualified name for a record no container operation has touched yet.
     */
    String clusterResourceName(Cluster cluster) {
        return cluster.getDockerName() != null ? cluster.getDockerName() : accountQualifiedName(cluster);
    }

    /**
     * Account-qualified Docker name for a cluster. Cluster names are unique only within a Region
     * of an account, so a non-default account's cluster is qualified with its account ID (and,
     * where needed, its Region; see {@link #accountQualifiedClusterName}) — otherwise
     * two accounts' same-named clusters would resolve to the same container and data volume,
     * letting one account reach (or, via startCluster's stale-container removal, destroy) the
     * other's workloads. The default account keeps the historical unqualified name so existing
     * containers, volumes, and {@code endpoint-mode=network} DNS names keep working.
     *
     * <p>The qualifier separator is a dot: EksService validates cluster names against the AWS
     * charset ({@code [0-9A-Za-z][A-Za-z0-9\-_]*}), which admits no dot, so no default-account
     * cluster name can spell out {@code <accountId>.<name>} and collide with another account's
     * qualified name — a dash separator would (cluster "999999999999-demo" vs account
     * 999999999999's "demo"). Dots are valid in Docker container and volume names.
     *
     * <p>The record's accountId is set before every call path reaches here (createCluster on
     * create; the startup account rehydration on restore/stop); a null falls back to the
     * default-account name.
     */
    private String accountQualifiedName(Cluster cluster) {
        return ContainerStorageHelper.resourceName(config, "eks", null, accountQualifiedClusterName(cluster));
    }

    /**
     * The cluster's name qualified by its account and, when {@link Cluster#getDockerNameRegion()}
     * is set, by its Region, which leads: {@code [<region>.][<accountId>.]<name>}. Cluster names
     * are unique only within a Region of an account, so same-named clusters in two Regions need
     * distinct containers and volumes. The forms cannot collide: a name holds no dot, an account
     * id is all digits, and a Region never is.
     */
    private String accountQualifiedClusterName(Cluster cluster) {
        String accountId = cluster.getAccountId();
        boolean defaultAccount = accountId == null || accountId.equals(config.defaultAccountId());
        String qualified = defaultAccount ? cluster.getName() : accountId + "." + cluster.getName();
        String region = cluster.getDockerNameRegion();
        return region == null || region.isBlank() ? qualified : region + "." + qualified;
    }

    /**
     * Resolves which Docker name a restored record's resources actually live under. Clusters
     * created before account-qualified naming used the account-independent legacy name
     * {@code floci-eks-<name>} for every account — a non-default account's cluster must keep
     * that name when its own container survived there, or the upgrade would recreate the
     * cluster under the qualified name and orphan the historical workloads. The legacy
     * container is claimed only when its {@code io.floci.account} label matches the owning
     * account; another account's container — or one with no verifiable owner — is left
     * untouched and the cluster starts fresh under the qualified name. A surviving legacy
     * volume without its container carries no ownership label and is deliberately not claimed —
     * it is reported via {@link #warnUnclaimedLegacyState} so the operator can migrate the data
     * by hand. The result is deterministic across restarts for a given Docker state.
     */
    private String resolveRestoredDockerName(Cluster cluster) {
        String qualified = accountQualifiedName(cluster);
        if (cluster.getDockerNameRegion() != null) {
            // Region qualification postdates both the account qualifier and the floci-aws- rename,
            // so no older name can hold this cluster's resources.
            return qualified;
        }

        // A cluster created after account qualification but before the floci-aws- rename lives
        // under the qualified name with the old prefix. Nothing else about it changed, so adopt
        // it outright rather than putting it through the ownership check below.
        String prefixLegacyQualified = ContainerStorageHelper.legacyDockerName(config, qualified);
        if (!prefixLegacyQualified.equals(qualified)
                && lifecycleManager.findByName(prefixLegacyQualified).isPresent()) {
            LOG.infov("EKS cluster {0} keeps its pre-rename Docker name {1}",
                    cluster.getName(), prefixLegacyQualified);
            return prefixLegacyQualified;
        }

        // Clusters predating account qualification used the unqualified name, which also predates
        // the rename, so it is resolved with the frozen legacy prefix.
        String legacy = ContainerStorageHelper.legacyResourceName(config, "eks", null, cluster.getName());
        if (legacy.equals(ContainerStorageHelper.legacyDockerName(config, qualified))) {
            return qualified; // default account: the names never diverged
        }
        var legacySurvivor = lifecycleManager.findByName(legacy);
        if (legacySurvivor.isPresent()) {
            var labels = legacySurvivor.get().getLabels();
            String owner = labels != null ? labels.get("io.floci.account") : null;
            if (cluster.getAccountId() != null && cluster.getAccountId().equals(owner)) {
                LOG.infov("EKS cluster {0} (account {1}) keeps its pre-upgrade Docker name {2}",
                        cluster.getName(), cluster.getAccountId(), legacy);
                return legacy;
            }
            if (owner == null) {
                warnUnclaimedLegacyState(cluster, legacy, "container");
            }
            // A container labeled with another account is simply not this cluster's — no warning.
        } else if (volumeExists(legacy)) {
            warnUnclaimedLegacyState(cluster, legacy, "data volume");
        }
        return qualified;
    }

    /**
     * A legacy-named container or volume whose owning account cannot be verified is never claimed
     * for a non-default account — handing it over on a guess would expose another account's data,
     * the very cross-bind the qualified names exist to prevent. It is reported instead of being
     * silently orphaned, so an operator who knows the data belongs to this cluster can migrate it
     * into the qualified volume by hand.
     */
    private void warnUnclaimedLegacyState(Cluster cluster, String legacy, String kind) {
        LOG.warnv("EKS cluster {0} (account {1}) starts under its account-qualified Docker name; "
                + "a pre-upgrade {2} named {3} survives but carries no verifiable owning account, "
                + "so it is NOT adopted. If its data belongs to this cluster, copy it into the "
                + "cluster''s qualified volume manually (docker volume inspect {3}).",
                cluster.getName(), cluster.getAccountId(), kind, legacy);
    }

    /** Whether a Docker volume with this exact name exists. Any lookup failure counts as absent. */
    private boolean volumeExists(String volumeName) {
        try {
            lifecycleManager.getDockerClient().inspectVolumeCmd(volumeName).exec();
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * Resolves the k3s container image for a cluster.
     * Uses imageTemplate if configured, otherwise maps explicitly requested Kubernetes versions
     * to stable k3s images, falling back to the configured defaultImage when no version was requested.
     */
    String resolveClusterImage(Cluster cluster) {
        String configuredDefault = config.services().eks().defaultImage();
        boolean hasExplicitVersion = cluster != null
                && (cluster.isExplicitVersion()
                        || (cluster.getVersion() != null && !EksService.DEFAULT_K8S_VERSION.equals(cluster.getVersion())));
        if (hasExplicitVersion) {
            String version = cluster.getVersion();
            if (config.services().eks().imageTemplate().isPresent()) {
                String template = config.services().eks().imageTemplate().get();
                return template.contains("%s") ? String.format(template, version) : template;
            }
            String mapped = SUPPORTED_K8S_VERSIONS.get(version);
            if (mapped != null) {
                return mapped;
            }
            return "rancher/k3s:v" + version + ".0-k3s1";
        }
        return configuredDefault != null && !configuredDefault.isBlank()
                ? configuredDefault
                : "rancher/k3s:latest";
    }

    /**
     * Builds the k3s {@code server} command-line args. When {@code defaultStorageClass} is false,
     * k3s's bundled local-path provisioner is disabled so clusters match AWS EKS by starting with
     * no default StorageClass. When {@code disableCni} is true, flannel, k3s's default network
     * policy controller, and kube-proxy are all disabled up front: see the {@code disableCni}
     * config javadoc for why this must happen at startup, not after the fact.
     */
    static List<String> buildServerArgs(boolean disableCni, boolean defaultStorageClass,
            String serviceCidr, String clusterCidr, String nodeIp) {
        List<String> serverArgs = new ArrayList<>(List.of("server",
                "--disable=traefik",
                "--tls-san=localhost"));
        if (!defaultStorageClass) {
            serverArgs.add("--disable=local-storage");
        }
        if (disableCni) {
            serverArgs.add("--flannel-backend=none");
            serverArgs.add("--disable-network-policy");
            serverArgs.add("--disable-kube-proxy");
        }
        if (serviceCidr != null && !serviceCidr.isBlank()) {
            serverArgs.add("--service-cidr=" + serviceCidr);
        }
        if (clusterCidr != null && !clusterCidr.isBlank()) {
            serverArgs.add("--cluster-cidr=" + clusterCidr);
        }
        if (nodeIp != null && !nodeIp.isBlank()) {
            serverArgs.add("--node-ip=" + nodeIp);
        }
        return serverArgs;
    }

    static List<String> buildServerArgs(boolean disableCni, boolean defaultStorageClass,
            String serviceCidr, String clusterCidr) {
        return buildServerArgs(disableCni, defaultStorageClass, serviceCidr, clusterCidr, null);
    }

    static List<String> buildServerArgs(boolean disableCni, String serviceCidr, String clusterCidr) {
        return buildServerArgs(disableCni, false, serviceCidr, clusterCidr);
    }

    static List<String> buildServerArgs(boolean disableCni) {
        return buildServerArgs(disableCni, false, null, null);
    }

    /**
     * Overrides the image's default {@code ["/bin/k3s"]} entrypoint so a {@code mount
     * --make-rshared /} can run immediately before k3s starts (see {@link #startCluster} for
     * why). POSIX sh-compatible: the k3s image has no bash. A failed mount is logged to stderr
     * rather than silently ignored, since it means a pod asking for mount propagation, a CSI
     * node plugin or an external CNI, will fail later in a much more confusing way.
     */
    static final List<String> RSHARE_ENTRYPOINT = List.of("sh", "-c",
            "mount --make-rshared / || echo 'floci: WARN: mount --make-rshared / failed; "
                    + "CSI drivers and an external CNI may not work' >&2; exec /bin/k3s \"$@\"");

    /**
     * {@link #RSHARE_ENTRYPOINT}, preceded by the node's side of the VPC pod network, from what
     * {@link VpcPodNetwork#env} sets. With flannel off, k3s leaves containerd's CNI paths at their defaults,
     * {@code /opt/cni/bin} and {@code /etc/cni/net.d}, so the plugins the image ships in its {@code /bin/cni} go there
     * and the bridge configuration over the node's prefixes beside them. The bridge, {@code cni0}, is created here with
     * each prefix's gateway address, as the bridge plugin gives a bridge the gateway of one subnet only. The interface
     * holding the node's VPC address
     * answers ARP for the pods' addresses, as the VPC delivers a prefix's traffic to the interface it is delegated to,
     * and traffic from a prefix leaving the VPC takes the node's address, as the VPC CNI's SNAT gives it. It runs on
     * every start of the container, whose network namespace a restart rebuilds.
     */
    static final List<String> VPC_POD_NETWORK_ENTRYPOINT = List.of("sh", "-c", String.join("\n",
            "mkdir -p /opt/cni/bin /etc/cni/net.d",
            "for plugin in bridge host-local loopback portmap; do ln -sf /bin/cni /opt/cni/bin/$plugin; done",
            "printf '%s' \"$FLOCI_POD_CNI_CONFIG\" > /etc/cni/net.d/10-floci-vpc.conflist",
            "ip link add cni0 type bridge 2>/dev/null || true",
            "for gateway in $FLOCI_POD_GATEWAYS; do ip addr add \"$gateway\" dev cni0 2>/dev/null || true; done",
            "ip link set cni0 up",
            "iface=$(ip -o -4 addr show | awk -v ip=\"$FLOCI_NODE_IP\""
                    + " '{ split($4, a, \"/\"); if (a[1] == ip) { print $2; exit } }')",
            "if [ -n \"$iface\" ]; then echo 1 > \"/proc/sys/net/ipv4/conf/$iface/proxy_arp\"; else"
                    + " echo \"floci: WARN: no interface holds $FLOCI_NODE_IP, so pod addresses do not answer on the VPC\""
                    + " >&2; fi",
            "for prefix in $FLOCI_POD_PREFIXES; do",
            "  iptables -t nat -C POSTROUTING -s \"$prefix\" ! -d \"$FLOCI_VPC_CIDR\" -j MASQUERADE 2>/dev/null"
                    + " || iptables -t nat -A POSTROUTING -s \"$prefix\" ! -d \"$FLOCI_VPC_CIDR\" -j MASQUERADE",
            "done",
            RSHARE_ENTRYPOINT.get(2)));

    /** The VPC pod network a node runs: its VPC's range on the Docker network, and the prefixes its pods use. */
    record VpcPodNetwork(String vpcCidr, List<String> prefixes) {

        /** What {@link #VPC_POD_NETWORK_ENTRYPOINT} reads, for a node at {@code nodeIp}. */
        Map<String, String> env(String nodeIp) {
            Map<String, String> env = new LinkedHashMap<>();
            env.put("FLOCI_NODE_IP", nodeIp);
            env.put("FLOCI_VPC_CIDR", vpcCidr);
            env.put("FLOCI_POD_PREFIXES", String.join(" ", prefixes));
            env.put("FLOCI_POD_GATEWAYS", prefixes.stream().map(prefix -> podGateway(prefix) + "/" + prefixLength(prefix))
                    .collect(Collectors.joining(" ")));
            env.put("FLOCI_POD_CNI_CONFIG", podCniConfig(prefixes));
            return env;
        }

        String label() {
            return VPC_POD_NETWORK_LABEL_PREFIX + String.join(",", prefixes);
        }
    }

    /**
     * The bridge CNI configuration over {@code prefixes}: one bridge, {@code cni0}, holding in each prefix the address
     * its pods route through, which the node gives it, host-local handing out the rest of the prefixes in order, and no
     * masquerade, which the node applies to traffic leaving the VPC only.
     */
    static String podCniConfig(List<String> prefixes) {
        String ranges = prefixes.stream()
                .map(prefix -> "{\"subnet\": \"" + prefix + "\", \"gateway\": \"" + podGateway(prefix) + "\"}")
                .collect(Collectors.joining(", "));
        return "{\"cniVersion\": \"1.0.0\", \"name\": \"floci-vpc\", \"plugins\": ["
                + "{\"type\": \"bridge\", \"bridge\": \"cni0\", \"isGateway\": false, \"ipMasq\": false,"
                + " \"hairpinMode\": true, \"ipam\": {\"type\": \"host-local\", \"ranges\": [[" + ranges + "]],"
                + " \"routes\": [{\"dst\": \"0.0.0.0/0\"}]}},"
                + " {\"type\": \"portmap\", \"capabilities\": {\"portMappings\": true}}]}";
    }

    /** The address the pods of {@code prefix} route through: its first host address, which the node holds. */
    static String podGateway(String prefix) {
        return Cidr4.format(podPrefix(prefix).network() + 1);
    }

    private static int prefixLength(String prefix) {
        return podPrefix(prefix).prefix();
    }

    private static Cidr4 podPrefix(String prefix) {
        return Cidr4.parse(prefix).orElseThrow(() -> new IllegalArgumentException("not a CIDR: " + prefix));
    }

    /**
     * The VPC pod network a node holding {@code lease} runs, or null when it runs k3s's flannel network: the setting is
     * off, {@code disable-cni} leaves the pod network to an external CNI, the node holds no address or prefix in a
     * Docker-backed VPC, or the VPC has no Docker network range. {@code warn} logs why a node that asks for it does
     * not get it.
     */
    private VpcPodNetwork vpcPodNetwork(Cluster cluster, VpcLease lease, boolean warn) {
        EmulatorConfig.EksServiceConfig eks = config.services().eks();
        if (!eks.vpcPodNetwork() || lease == null) {
            return null;
        }
        String why = null;
        Optional<String> vpcCidr = Optional.empty();
        if (eks.disableCni()) {
            why = "disable-cni leaves the pod network to an external CNI";
        } else if (cluster.getNodePodPrefixes() == null || cluster.getNodePodPrefixes().isEmpty()) {
            why = "its node holds no VPC prefix";
        } else {
            Ec2Service ec2 = ec2Service();
            vpcCidr = ec2 == null ? Optional.empty() : ec2.effectiveVpcCidr(lease.accountId(), lease.region(), lease.vpcId());
            if (vpcCidr.isEmpty()) {
                why = "VPC " + lease.vpcId() + " has no Docker network range";
            }
        }
        if (why != null) {
            if (warn) {
                LOG.warnv("EKS cluster {0} runs its pods on k3s''s own network: {1}", cluster.getName(), why);
            }
            return null;
        }
        return new VpcPodNetwork(vpcCidr.get(), List.copyOf(cluster.getNodePodPrefixes()));
    }

    private static String podNetworkLabel(VpcPodNetwork podNetwork) {
        return podNetwork == null ? FLANNEL_POD_NETWORK : podNetwork.label();
    }

    /**
     * Whether a surviving node runs another pod network than it should: k3s's flannel network while the VPC pod network
     * is on, the VPC while it is off, or prefixes it no longer holds all of. A replacement leases the prefixes a node
     * lacks. A node on k3s's network is replaced only when its replacement would run the VPC's: it leases the prefixes
     * for it here, and gives them back when it cannot, so a node that falls back to k3s's network is not replaced again
     * on every start.
     */
    private boolean podNetworkChanged(Cluster cluster, Map<String, String> existingLabels) {
        String existing = startedPodNetwork(existingLabels);
        VpcLease lease = resolveClusterVpcLease(cluster);
        EmulatorConfig.EksServiceConfig eks = config.services().eks();
        if (!eks.vpcPodNetwork() || eks.disableCni() || lease == null) {
            return !FLANNEL_POD_NETWORK.equals(existing);
        }
        VpcPodNetwork current = vpcPodNetwork(cluster, lease, false);
        if (current == null && FLANNEL_POD_NETWORK.equals(existing)) {
            leasePodPrefixes(cluster, lease, true);
            if (vpcPodNetwork(cluster, lease, false) != null) {
                return true;
            }
            retainPodPrefixes(cluster, List.of());
            return false;
        }
        return current == null || !current.label().equals(existing);
    }

    /** The pod network a node was started with, by its label: k3s's own when it has none, as before the label. */
    private static String startedPodNetwork(Map<String, String> labels) {
        return labels == null ? FLANNEL_POD_NETWORK : labels.getOrDefault(POD_NETWORK_LABEL, FLANNEL_POD_NETWORK);
    }

    /** The prefixes a node started with the pod network {@code label} hands out: none on k3s's own network. */
    private static List<String> labelPodPrefixes(String label) {
        return label.startsWith(VPC_POD_NETWORK_LABEL_PREFIX)
                ? List.of(label.substring(VPC_POD_NETWORK_LABEL_PREFIX.length()).split(",")) : List.of();
    }

    /**
     * Gives back the prefixes the node holds other than {@code kept}: a node that keeps running after its replacement
     * failed hands out only the prefixes it was started with, not those leased for the replacement.
     */
    private void retainPodPrefixes(Cluster cluster, List<String> kept) {
        String resourceName = clusterResourceName(cluster);
        List<String> held = clusterPodPrefixes.get(resourceName);
        VpcLease lease = clusterVpcLeases.get(resourceName);
        Ec2Service ec2 = ec2Service();
        if (held == null || lease == null || ec2 == null) {
            return;
        }
        List<String> released = held.stream().filter(prefix -> !kept.contains(prefix)).toList();
        released.forEach(prefix -> ec2.releaseVpcPrefix(lease.accountId(), lease.region(), lease.subnetId(), prefix));
        held.removeAll(released);
        cluster.setNodePodPrefixes(held.isEmpty() ? null : new ArrayList<>(held));
    }

    /** Takes the VPC pod network off the spec of a node falling back to the bridge: it starts on k3s's own network. */
    private static void dropVpcPodNetwork(ContainerBuilder.Builder spec, List<String> serverArgs,
                                          VpcPodNetwork podNetwork, String clusterCidr, String nodeIp) {
        VPC_POD_NETWORK_SERVER_ARGS.forEach(serverArgs::remove);
        int cidrAt = serverArgs.indexOf("--cluster-cidr=" + podNetwork.vpcCidr());
        if (cidrAt >= 0 && clusterCidr != null && !clusterCidr.isBlank()) {
            serverArgs.set(cidrAt, "--cluster-cidr=" + clusterCidr);
        } else if (cidrAt >= 0) {
            serverArgs.remove(cidrAt);
        }
        podNetwork.env(nodeIp).keySet().forEach(spec::withoutEnv);
        spec.withEntrypoint(RSHARE_ENTRYPOINT);
        spec.withLabels(Map.of(POD_NETWORK_LABEL, FLANNEL_POD_NETWORK));
    }

    /** Where pod traffic reaches the node from: its VPC prefixes on the VPC pod network, else k3s's pod CIDR. */
    private String podSourceCidrs(Cluster cluster) {
        VpcPodNetwork podNetwork = vpcPodNetwork(cluster, resolveClusterVpcLease(cluster), false);
        return podNetwork == null ? cluster.getPodCidr() : String.join(",", podNetwork.prefixes());
    }

    /**
     * Builds the CMD to pair with {@link #RSHARE_ENTRYPOINT}: an unused $0 placeholder
     * followed by the real k3s server args, so the entrypoint's "$@" expands to exactly
     * {@code serverArgs}, the args the image's own {@code /bin/k3s} entrypoint would take.
     */
    static List<String> buildRshareWrappedCmd(List<String> serverArgs) {
        List<String> wrappedCmd = new ArrayList<>();
        wrappedCmd.add("floci-k3s");
        wrappedCmd.addAll(serverArgs);
        return wrappedCmd;
    }

    /**
     * The resolv.conf k3s hands its pods, naming only Floci's embedded DNS server, or null when k3s keeps
     * its own choice: embedded DNS is off, not running or not reachable from the cluster's Docker network,
     * or the caller set the kubelet's resolv-conf.
     * k3s otherwise reads the node's /etc/resolv.conf, which on a user-defined Docker network names only
     * Docker's loopback resolver 127.0.0.11. k3s refuses a loopback-only file and writes one naming a
     * public resolver instead, so CoreDNS never asks Floci and names only Floci holds do not resolve.
     * Floci's public fallback resolvers stay out of the file: CoreDNS picks among its upstreams at random,
     * so one of them would answer some lookups of Floci's names. Floci forwards every other name itself.
     */
    private String podResolvConf(List<String> callerArgs) {
        if (EksClusterArgs.setsKubeletResolvConf(callerArgs)) {
            return null;
        }
        return nodeDnsServerIp().map(dnsServerIp -> "nameserver " + dnsServerIp + "\n").orElse(null);
    }

    /**
     * Floci's embedded DNS server, when the cluster's node resolves through it: embedded DNS is on for EKS, so
     * the node gets it as its resolver, and the server is running and reachable from the cluster's Docker network.
     */
    private Optional<String> nodeDnsServerIp() {
        if (!config.services().eks().embeddedDns()) {
            return Optional.empty();
        }
        return containerBuilder.embeddedDnsServerIpReachableOn(config.services().eks().dockerNetwork());
    }

    /**
     * Resolves caller-supplied k3s arguments configured on the cluster or in its creation tags.
     */
    List<String> resolveCallerArgs(Cluster cluster) {
        if (cluster == null) {
            return List.of();
        }
        List<String> result = new ArrayList<>();
        if (cluster.getClusterArgs() != null && !cluster.getClusterArgs().isEmpty()) {
            EksClusterArgs.validateClusterArgs(cluster.getClusterArgs());
            result.addAll(cluster.getClusterArgs());
        }
        if (cluster.getTags() != null && !cluster.getTags().isEmpty()) {
            List<String> fromTags = EksClusterArgs.parseAndValidateClusterArgs(cluster.getTags(), cluster.getName());
            for (String arg : fromTags) {
                if (!result.contains(arg)) {
                    result.add(arg);
                }
            }
        }
        return result;
    }

    /**
     * Resolves the public {@code describe-cluster} endpoint. Returns the container DNS name only when
     * Floci runs in a container and {@code endpoint-mode=network}; otherwise the published port on
     * {@code endpointHost} (the default, and the only usable value in native mode).
     */
    static String resolvePublicEndpoint(boolean inContainer, String endpointMode,
                                        String containerName, String endpointHost, int hostPort) {
        if (inContainer && ENDPOINT_MODE_NETWORK.equalsIgnoreCase(endpointMode)) {
            return "https://" + containerName + ":" + K3S_API_SERVER_PORT;
        }
        String host = endpointHost.indexOf(':') >= 0 ? "[" + endpointHost + "]" : endpointHost;
        return "https://" + host + ":" + hostPort;
    }

    /**
     * The host a container's certificate is made for and host-mode endpoints advertise. Network mode
     * advertises the container name instead, so there the setting is ignored and this is localhost.
     * {@link EksStartupValidator} has already refused an invalid value at boot.
     */
    private String endpointHost() {
        if (networkEndpointMode()) {
            return "localhost";
        }
        return endpointHostFrom(config.services().eks().endpointHost());
    }

    /** Whether endpoints name the k3s container rather than a host port: network mode, with Floci in a container. */
    private boolean networkEndpointMode() {
        return containerDetector.isRunningInContainer()
                && ENDPOINT_MODE_NETWORK.equalsIgnoreCase(config.services().eks().endpointMode());
    }

    /**
     * The configured endpoint host as a bare hostname or IP address, the form a {@code --tls-san} takes.
     * A bracketed IPv6 literal is unbracketed; a scheme, port or path is refused, since the endpoint
     * adds its own.
     */
    static String endpointHostFrom(Optional<String> configured) {
        String host = configured.map(String::strip).filter(value -> !value.isEmpty()).orElse("localhost")
                .toLowerCase(Locale.ROOT);
        if (host.startsWith("[") && host.endsWith("]")) {
            host = host.substring(1, host.length() - 1);
        }
        boolean numeric = host.chars().allMatch(c -> c == '.' || Character.isDigit(c));
        if (numeric ? isIpv4Literal(host) : host.length() <= 253 && HOSTNAME.matcher(host).matches()
                || isIpv6Literal(host)) {
            return host;
        }
        throw new IllegalArgumentException("floci.services.eks.endpoint-host must be a hostname or IP address"
                + " without scheme, port or path, got: " + configured.orElse(""));
    }

    /** The endpoint host a container's certificate carries, from the label {@link #startCluster} sets. */
    private static String certificateHost(Map<String, String> labels) {
        return labels == null ? "localhost" : labels.getOrDefault(ENDPOINT_HOST_LABEL, "localhost");
    }

    static boolean isIpv4Literal(String host) {
        // ofLiteral also takes BSD shorthand such as 1.2.3, which no URL or certificate means.
        if (host.split("\\.", -1).length != 4) {
            return false;
        }
        try {
            Inet4Address.ofLiteral(host);
            return true;
        } catch (IllegalArgumentException e) {
            return false;
        }
    }

    private static boolean isIpv6Literal(String host) {
        if (host.indexOf(':') < 0 || host.indexOf('%') >= 0 || host.indexOf('[') >= 0 || host.indexOf(']') >= 0) {
            return false;
        }
        try {
            Inet6Address.ofLiteral(host);
            return true;
        } catch (IllegalArgumentException e) {
            return false;
        }
    }

    /**
     * Writes the token-webhook kubeconfig for the given cluster to Floci's local filesystem and
     * returns its path (basename {@value #WEBHOOK_CONFIG_FILE}), or {@code null} if it could not be
     * written (in which case the caller skips the webhook so cluster creation still succeeds). The
     * file is later streamed into the container via the Docker API, so no host path is involved.
     */
    private String writeWebhookKubeconfig(Cluster cluster) {
        String clusterName = cluster.getName();
        Path localFile = localClusterFile(cluster, "webhook", WEBHOOK_CONFIG_FILE);
        try {
            Files.createDirectories(localFile.getParent());
            Files.writeString(localFile, buildWebhookKubeconfig("http://" + dockerHostResolver.resolve() + ":"
                    + config.port() + webhookPath(cluster, clusterRegion(cluster))));
        } catch (IOException e) {
            LOG.warnv("EKS token-webhook disabled for cluster {0}: could not write kubeconfig: {1}",
                    clusterName, e.getMessage());
            return null;
        }
        return localFile.toString();
    }

    /**
     * Streams the webhook kubeconfig from Floci's filesystem into the (created, not-yet-started)
     * k3s container at {@value #WEBHOOK_CONFIG_PATH}, using the Docker API. Reading the file
     * client-side avoids any host bind-mount, so this works in native and Docker-in-Docker modes
     * alike. A failure here disables the webhook for the cluster but does not abort its startup.
     */
    private void copyWebhookIntoContainer(String containerId, String localFile, String clusterName) {
        try {
            RetryingTarCopier.copyHostResource(lifecycleManager.getDockerClient(), containerId,
                    WEBHOOK_CONFIG_DIR, localFile,
                    RetryingTarCopier.DEFAULT_MAX_ATTEMPTS, archiveRetryBackoffMillis);
        } catch (Exception e) {
            LOG.warnv("EKS token-webhook may not authenticate for cluster {0}: could not copy kubeconfig "
                    + "into the k3s container: {1}", clusterName, e.getMessage());
        }
    }

    /**
     * Writes the official Amazon EKS audit policy YAML to Floci's local data directory and
     * returns its path, or {@code null} if writing failed.
     */
    String writeAuditPolicyFile(Cluster cluster) {
        String clusterName = cluster.getName();
        try {
            String dataPath = config.services().eks().dataPath();
            if (dataPath == null || dataPath.isBlank()) {
                return null;
            }
            Path localFile = localClusterFile(cluster, "audit", AUDIT_POLICY_FILE);
            Files.createDirectories(localFile.getParent());
            Files.writeString(localFile, buildAuditPolicy());
            return localFile.toString();
        } catch (Exception e) {
            LOG.warnv("EKS audit logging disabled for cluster {0}: could not write audit policy file: {1}",
                    clusterName, e.getMessage());
            return null;
        }
    }

    /**
     * Streams the audit policy from Floci's filesystem into the (created, not-yet-started)
     * k3s container at {@value #AUDIT_POLICY_CONTAINER_PATH}, using the Docker API.
     */
    void copyAuditPolicyIntoContainer(String containerId, String localFile, String clusterName) {
        try {
            DockerClient dockerClient = lifecycleManager.getDockerClient();
            if (dockerClient != null) {
                RetryingTarCopier.copyHostResource(dockerClient, containerId, AUDIT_POLICY_DIR, localFile,
                        RetryingTarCopier.DEFAULT_MAX_ATTEMPTS, archiveRetryBackoffMillis);
                LOG.debugv("Injected audit policy file into k3s container {0} for cluster {1}",
                        containerId, clusterName);
            }
        } catch (Exception e) {
            LOG.warnv("EKS audit logs may not be emitted for cluster {0}: could not copy "
                    + "audit policy into the k3s container: {1}", clusterName, e.getMessage());
        }
    }

    /**
     * Official Amazon EKS control plane audit policy documented in the Amazon EKS Best Practices Guide.
     */
    public static String buildAuditPolicy() {
        return """
                apiVersion: audit.k8s.io/v1
                kind: Policy
                rules:
                  # Log full request and response for changes to aws-auth ConfigMap in kube-system namespace
                  - level: RequestResponse
                    namespaces: ["kube-system"]
                    verbs: ["update", "patch", "delete"]
                    resources:
                      - group: ""
                        resources: ["configmaps"]
                        resourceNames: ["aws-auth"]
                    omitStages:
                      - "RequestReceived"

                  # Do not log watch operations performed by kube-proxy on endpoints and services
                  - level: None
                    users: ["system:kube-proxy"]
                    verbs: ["watch"]
                    resources:
                      - group: ""
                        resources: ["endpoints", "services", "services/status"]

                  # Do not log get operations performed by kubelet on nodes and their statuses
                  - level: None
                    users: ["kubelet"]
                    verbs: ["get"]
                    resources:
                      - group: ""
                        resources: ["nodes", "nodes/status"]

                  # Do not log get operations performed by the system:nodes group on nodes and their statuses
                  - level: None
                    userGroups: ["system:nodes"]
                    verbs: ["get"]
                    resources:
                      - group: ""
                        resources: ["nodes", "nodes/status"]

                  # Do not log get and update operations performed by controller manager, scheduler, and endpoint-controller on endpoints in kube-system namespace
                  - level: None
                    users:
                      - system:kube-controller-manager
                      - system:kube-scheduler
                      - system:serviceaccount:kube-system:endpoint-controller
                    verbs: ["get", "update"]
                    namespaces: ["kube-system"]
                    resources:
                      - group: ""
                        resources: ["endpoints"]

                  # Do not log get operations performed by apiserver on namespaces and their statuses/finalizations
                  - level: None
                    users: ["system:apiserver"]
                    verbs: ["get"]
                    resources:
                      - group: ""
                        resources: ["namespaces", "namespaces/status", "namespaces/finalize"]

                  # Do not log get and list operations performed by controller manager on metrics.k8s.io resources
                  - level: None
                    users:
                      - system:kube-controller-manager
                    verbs: ["get", "list"]
                    resources:
                      - group: "metrics.k8s.io"

                  # Do not log access to health, version, and swagger non-resource URLs
                  - level: None
                    nonResourceURLs:
                      - /healthz*
                      - /version
                      - /swagger*

                  # Do not log events resources
                  - level: None
                    resources:
                      - group: ""
                        resources: ["events"]

                  # Log request for updates/patches to nodes and pods statuses by kubelet and node problem detector
                  - level: Request
                    users: ["kubelet", "system:node-problem-detector", "system:serviceaccount:kube-system:node-problem-detector"]
                    verbs: ["update", "patch"]
                    resources:
                      - group: ""
                        resources: ["nodes/status", "pods/status"]
                    omitStages:
                      - "RequestReceived"

                  # Log request for updates/patches to nodes and pods statuses by system:nodes group
                  - level: Request
                    userGroups: ["system:nodes"]
                    verbs: ["update", "patch"]
                    resources:
                      - group: ""
                        resources: ["nodes/status", "pods/status"]
                    omitStages:
                      - "RequestReceived"

                  # Log delete collection requests by namespace-controller in kube-system namespace
                  - level: Request
                    users: ["system:serviceaccount:kube-system:namespace-controller"]
                    verbs: ["deletecollection"]
                    omitStages:
                      - "RequestReceived"

                  # Log metadata for secrets, configmaps, and tokenreviews to protect sensitive data
                  - level: Metadata
                    resources:
                      - group: ""
                        resources: ["secrets", "configmaps"]
                      - group: authentication.k8s.io
                        resources: ["tokenreviews"]
                    omitStages:
                      - "RequestReceived"

                  # Log requests for serviceaccounts/token resources
                  - level: Request
                    resources:
                      - group: ""
                        resources: ["serviceaccounts/token"]

                  # Log get, list, and watch requests for various resource groups
                  - level: Request
                    verbs: ["get", "list", "watch"]
                    resources:
                      - group: ""
                      - group: "admissionregistration.k8s.io"
                      - group: "apiextensions.k8s.io"
                      - group: "apiregistration.k8s.io"
                      - group: "apps"
                      - group: "authentication.k8s.io"
                      - group: "authorization.k8s.io"
                      - group: "autoscaling"
                      - group: "batch"
                      - group: "certificates.k8s.io"
                      - group: "extensions"
                      - group: "metrics.k8s.io"
                      - group: "networking.k8s.io"
                      - group: "policy"
                      - group: "rbac.authorization.k8s.io"
                      - group: "scheduling.k8s.io"
                      - group: "settings.k8s.io"
                      - group: "storage.k8s.io"
                    omitStages:
                      - "RequestReceived"

                  # Default logging level for known APIs to log request and response
                  - level: RequestResponse
                    resources:
                      - group: ""
                      - group: "admissionregistration.k8s.io"
                      - group: "apiextensions.k8s.io"
                      - group: "apiregistration.k8s.io"
                      - group: "apps"
                      - group: "authentication.k8s.io"
                      - group: "authorization.k8s.io"
                      - group: "autoscaling"
                      - group: "batch"
                      - group: "certificates.k8s.io"
                      - group: "extensions"
                      - group: "metrics.k8s.io"
                      - group: "networking.k8s.io"
                      - group: "policy"
                      - group: "rbac.authorization.k8s.io"
                      - group: "scheduling.k8s.io"
                      - group: "settings.k8s.io"
                      - group: "storage.k8s.io"
                    omitStages:
                      - "RequestReceived"

                  # Default logging level for all other requests to log metadata only
                  - level: Metadata
                    omitStages:
                      - "RequestReceived"
                """;
    }

    /**
     * Builds the API server arguments configuring k3s to sign service account tokens with the
     * cluster's OIDC keypair and advertise the cluster's OIDC issuer URL. api-audiences includes
     * both the standard Kubernetes in-cluster audience and STS_AUDIENCE.
     */
    static List<String> buildIrsaServerArgs(String issuerUrl) {
        if (issuerUrl == null || issuerUrl.isBlank()) {
            throw new IllegalArgumentException("issuerUrl is required");
        }
        return List.of(
                "--kube-apiserver-arg=service-account-signing-key-file=" + SA_SIGNING_KEY_CONTAINER_PATH,
                "--kube-apiserver-arg=service-account-key-file=" + SA_PUBLIC_KEY_CONTAINER_PATH,
                "--kube-apiserver-arg=service-account-issuer=" + issuerUrl,
                "--kube-apiserver-arg=service-account-issuer=" + KUBERNETES_DEFAULT_ISSUER,
                "--kube-apiserver-arg=api-audiences=" + KUBERNETES_DEFAULT_ISSUER + "," + EksOidcService.STS_AUDIENCE
        );
    }

    String resolveClusterIssuer(Cluster cluster) {
        if (cluster.getIdentity() != null && cluster.getIdentity().getOidc() != null
                && cluster.getIdentity().getOidc().getIssuer() != null
                && !cluster.getIdentity().getOidc().getIssuer().isBlank()) {
            return cluster.getIdentity().getOidc().getIssuer();
        }
        String region = clusterRegion(cluster);
        String issuer = oidcService.newIssuerUrl(region);
        cluster.setIdentity(new ClusterIdentity(new OidcIdentity(issuer)));
        return issuer;
    }

    record SigningKeyFiles(Path signingKeyPath, Path publicKeyPath) {}

    String resolveClusterAccountId(Cluster cluster) {
        if (cluster != null) {
            if (cluster.getAccountId() != null && !cluster.getAccountId().isBlank()) {
                return cluster.getAccountId();
            }
            String accountId = AwsArnUtils.accountOrDefault(cluster.getArn(), null);
            if (accountId != null && !accountId.isBlank()) {
                return accountId;
            }
        }
        if (regionResolver != null && regionResolver.getAccountId() != null && !regionResolver.getAccountId().isBlank()) {
            return regionResolver.getAccountId();
        }
        if (config != null && config.defaultAccountId() != null && !config.defaultAccountId().isBlank()) {
            return config.defaultAccountId();
        }
        return "000000000000";
    }

    /**
     * The key of {@code cluster}'s OIDC signing key in {@link EksOidcService}: the cluster's storage
     * key, which carries its Region, as {@link EksService} creates, publishes and migrates it.
     */
    private String oidcKeyName(Cluster cluster) {
        return EksService.clusterKey(clusterRegion(cluster), cluster.getName());
    }

    /**
     * A file Floci writes for {@code cluster} under {@code <dataPath>/<kind>/<account>/<region>/<name>/},
     * the layout of {@link #resolveKeysDir}. Cluster names are unique only within a Region of an
     * account, so a shorter path would let same-named clusters overwrite each other's files.
     */
    Path localClusterFile(Cluster cluster, String kind, String fileName) {
        return Paths.get(config.services().eks().dataPath(), kind, resolveClusterAccountId(cluster),
                clusterRegion(cluster), cluster.getName(), fileName).toAbsolutePath().normalize();
    }

    Path resolveKeysDir(Cluster cluster) {
        String accountId = resolveClusterAccountId(cluster);
        String region = clusterRegion(cluster);
        return Paths.get(config.services().eks().dataPath(), "keys", accountId, region, cluster.getName())
                .toAbsolutePath().normalize();
    }

    /**
     * Writes the cluster's RSA signing key and public key PEM files to Floci's local filesystem
     * under the account- and region-qualified EKS data path with restrictive permissions (0600 for
     * files, 0700 for directories). Files are created with owner-only permissions atomically from
     * creation, avoiding any window with default umask permissions. Returns the paths or null if
     * writing failed.
     */
    SigningKeyFiles writeSigningKeyFiles(Cluster cluster, ClusterOidcKey oidcKey) {
        Path keysDir = resolveKeysDir(cluster);
        try {
            if (!Files.isDirectory(keysDir)) {
                if (Files.getFileAttributeView(keysDir.getParent(), PosixFileAttributeView.class) != null) {
                    Files.createDirectories(keysDir, PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rwx------")));
                } else {
                    Files.createDirectories(keysDir);
                }
            }
            setRestrictivePermissions(keysDir, true);

            Path signingKeyPath = keysDir.resolve(SA_SIGNING_KEY_FILE);
            writeSecureFile(signingKeyPath, oidcService.exportSigningKeyPem(oidcKey), "rw-------");

            Path publicKeyPath = keysDir.resolve(SA_PUBLIC_KEY_FILE);
            writeSecureFile(publicKeyPath, oidcService.exportPublicKeyPem(oidcKey), "rw-------");

            return new SigningKeyFiles(signingKeyPath, publicKeyPath);
        } catch (IOException e) {
            LOG.warnv("EKS IRSA signing key disabled for cluster {0}: could not write key files: {1}",
                    cluster.getName(), e.getMessage());
            return null;
        }
    }

    private static void writeSecureFile(Path path, String content, String posixPerms) throws IOException {
        Files.deleteIfExists(path);
        if (Files.getFileAttributeView(path.getParent(), PosixFileAttributeView.class) != null) {
            Files.createFile(path, PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString(posixPerms)));
        } else {
            Files.createFile(path);
        }
        Files.writeString(path, content);
        setRestrictivePermissions(path, false);
    }

    private static void setRestrictivePermissions(Path path, boolean isDirectory) {
        try {
            Set<PosixFilePermission> perms = isDirectory
                    ? PosixFilePermissions.fromString("rwx------")
                    : PosixFilePermissions.fromString("rw-------");
            Files.setPosixFilePermissions(path, perms);
        } catch (UnsupportedOperationException | IOException ignored) {
            File file = path.toFile();
            file.setReadable(false, false);
            file.setReadable(true, true);
            file.setWritable(false, false);
            file.setWritable(true, true);
            if (isDirectory) {
                file.setExecutable(false, false);
                file.setExecutable(true, true);
            } else {
                file.setExecutable(false, false);
            }
        }
    }

    /**
     * Streams the signing key and public key PEM files into the k3s container at /etc using the Docker API.
     * A failure logs a warning and lets cluster startup continue.
     */
    void copySigningKeysIntoContainer(String containerId, SigningKeyFiles keyFiles, String clusterName) {
        try {
            RetryingTarCopier.copyHostResource(lifecycleManager.getDockerClient(), containerId,
                    WEBHOOK_CONFIG_DIR, keyFiles.signingKeyPath().toString(),
                    RetryingTarCopier.DEFAULT_MAX_ATTEMPTS, archiveRetryBackoffMillis);
            RetryingTarCopier.copyHostResource(lifecycleManager.getDockerClient(), containerId,
                    WEBHOOK_CONFIG_DIR, keyFiles.publicKeyPath().toString(),
                    RetryingTarCopier.DEFAULT_MAX_ATTEMPTS, archiveRetryBackoffMillis);
            LOG.debugv("Injected IRSA OIDC signing keypair into k3s container {0} for cluster {1}",
                    containerId, clusterName);
        } catch (Exception e) {
            LOG.warnv("EKS IRSA service account tokens may not verify for cluster {0}: could not copy "
                    + "key files into the k3s container: {1}", clusterName, e.getMessage());
        }
    }

    void reinjectSigningKeys(String containerId, Cluster cluster) {
        try {
            String accountId = resolveClusterAccountId(cluster);
            String issuer = resolveClusterIssuer(cluster);
            ClusterOidcKey oidcKey = oidcService.ensureKeyForAccount(accountId, oidcKeyName(cluster), issuer);
            SigningKeyFiles keyFiles = writeSigningKeyFiles(cluster, oidcKey);
            if (keyFiles != null) {
                copySigningKeysIntoContainer(containerId, keyFiles, cluster.getName());
            }
        } catch (Exception e) {
            LOG.warnv("Could not re-inject IRSA signing keys for surviving EKS cluster {0}: {1}",
                    cluster.getName(), e.getMessage());
        }
    }

    /**
     * The {@code /etc/rancher/k3s/registries.yaml} Floci gives {@code cluster}'s k3s container so its containerd can pull
     * images pushed to the Floci ECR registry, or null when it gives none: the mirror or ECR is off. It mirrors every
     * repository hostname the cluster can pull from Floci: its own account and the default account, across every region
     * of the cluster's partition, plus the path-style {@code localhost:<port>} form, including {@code localhost.floci.io}
     * aliases when TLS registry URIs are enabled, to Floci's in-network data plane. The same two accounts' registry hosts
     * as AWS names them are mirrored as well. Public registries and other accounts' are never matched.
     */
    String registriesYaml(Cluster cluster) {
        if (!config.services().eks().ecrRegistryMirror() || !config.services().ecr().enabled()) {
            return null;
        }
        String region = regionResolver.regionOf(cluster.getArn());
        String endpoint = "http://" + dockerHostResolver.resolve() + ":" + config.port();
        boolean tlsUri = config.services().ecr().tlsUri() && config.tls().enabled();
        return buildRegistriesYaml(mirrorAccounts(resolveClusterAccountId(cluster), config.defaultAccountId()),
                mirrorRegions(region), ecrRegistryManager.advertisedPort(), endpoint, tlsUri);
    }

    /**
     * The registries.yaml a k3s container created now gets: {@link #registriesYaml}, or none when the registry is
     * unavailable, matching the webhook contract: the cluster starts without a mirror.
     */
    String startingRegistriesYaml(Cluster cluster) {
        String content = registriesYaml(cluster);
        if (content == null) {
            return null;
        }
        try {
            ecrRegistryManager.ensureStarted();
        } catch (Exception e) {
            LOG.warnv("EKS cluster {0} gets no ECR registry mirror: registry unavailable: {1}",
                    cluster.getName(), e.getMessage());
            return null;
        }
        return content;
    }

    /**
     * Injects {@code content}, from {@link #startingRegistriesYaml}, into the created, not yet started k3s container. A
     * failure leaves this cluster without the mirror but does not abort its startup.
     */
    void injectEcrRegistryMirror(String containerId, Cluster cluster, String content) {
        if (content == null) {
            return;
        }
        String clusterName = cluster.getName();
        writeLocalCopy(localClusterFile(cluster, "registries", "registries.yaml"), content, clusterName);
        try {
            RetryingTarCopier.copyBytes(lifecycleManager.getDockerClient(), containerId, "/etc",
                    REGISTRIES_TAR_ENTRY, content.getBytes(StandardCharsets.UTF_8), 0644,
                    RetryingTarCopier.DEFAULT_MAX_ATTEMPTS, archiveRetryBackoffMillis);
            LOG.infov("Injected ECR registry mirror into k3s cluster {0}", clusterName);
        } catch (Exception e) {
            LOG.warnv("EKS cluster {0} gets no ECR registry mirror: could not copy registries.yaml "
                    + "into the k3s container: {1}", clusterName, e.getMessage());
        }
    }

    /**
     * Whether a surviving node runs another registries.yaml than the one Floci gives it now, such as one written before
     * the mirror set grew, or none: it is replaced then, on its data volume, and its replacement gets the current one.
     * It is not replaced while the registry is unavailable, as the replacement would get no mirror either, nor to take
     * a mirror away when the setting is off.
     */
    boolean registryMirrorChanged(Cluster cluster, Map<String, String> existingLabels) {
        String wanted = registriesYaml(cluster);
        if (wanted == null || existingLabels != null
                && registryMirrorLabel(wanted).equals(existingLabels.get(REGISTRY_MIRROR_LABEL))) {
            return false;
        }
        return startingRegistriesYaml(cluster) != null;
    }

    static String registryMirrorLabel(String registriesYaml) {
        return Digests.sha256Hex(registriesYaml);
    }

    /**
     * Symlinks {@code /etc/containerd/certs.d} to k3s containerd's certs directory
     * ({@code /var/lib/rancher/k3s/agent/etc/containerd/certs.d}) inside the container before start.
     * EKS node group launch template user data writes registry host configurations (including
     * pull-through caches and custom headers) to {@code /etc/containerd/certs.d}; the symlink
     * routes those writes directly into k3s containerd's certs directory and preserves them across
     * cluster container restarts in the cluster's named data volume.
     */
    void linkContainerdCertsDir(String containerId, String clusterName) {
        try {
            byte[] tar = buildContainerdCertsLinkTar();
            RetryingTarCopier.copyStreamed(lifecycleManager.getDockerClient(), containerId, "/",
                    "containerd-certs-link", out -> out.write(tar),
                    RetryingTarCopier.DEFAULT_MAX_ATTEMPTS, archiveRetryBackoffMillis);
        } catch (Exception e) {
            LOG.warnv("EKS cluster {0}: could not link containerd certs.d directory: {1}",
                    clusterName, e.getMessage());
        }
    }

    static byte[] buildContainerdCertsLinkTar() {
        try {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            try (TarArchiveOutputStream tar = new TarArchiveOutputStream(out)) {
                TarArchiveEntry targetDir = new TarArchiveEntry(CONTAINERD_CERTS_TARGET.substring(1) + "/");
                targetDir.setMode(0755);
                tar.putArchiveEntry(targetDir);
                tar.closeArchiveEntry();

                TarArchiveEntry containerdDir = new TarArchiveEntry("etc/containerd/");
                containerdDir.setMode(0755);
                tar.putArchiveEntry(containerdDir);
                tar.closeArchiveEntry();

                TarArchiveEntry symlink = new TarArchiveEntry(CONTAINERD_CERTS_LINK, TarArchiveEntry.LF_SYMLINK);
                symlink.setLinkName(CONTAINERD_CERTS_TARGET);
                symlink.setMode(0777);
                tar.putArchiveEntry(symlink);
                tar.closeArchiveEntry();
            }
            return out.toByteArray();
        } catch (IOException e) {
            throw new IllegalStateException("Could not build in-memory tar for containerd certs.d link", e);
        }
    }

    /**
     * Drops the cluster's {@code MutatingWebhookConfiguration} into the k3s server manifests
     * directory of the (created, not-yet-started) container, so the API server registers it as it
     * comes up and starts sending pod CREATE admission reviews to Floci.
     *
     * <p>Kubernetes requires an {@code https} {@code clientConfig.url} and a {@code caBundle} it
     * trusts, neither of which Floci can offer with TLS off, so the webhook is skipped with a
     * warning in that case. The API server also checks that Floci's certificate names the URL's
     * host, so the URL uses Floci's hostname when the node resolves through Floci's DNS, and the
     * webhook is skipped with a warning when the certificate does not name the host. A skipped
     * webhook still replaces the manifest, through {@link #removePodIdentityWebhook}. A failure
     * here leaves the cluster running without pod identity injection, matching the token webhook
     * and the ECR mirror.
     *
     * <p>Also called when a surviving container is adopted: the manifest written at creation names
     * Floci's address and certificate as they were then, and k3s applies the rewritten file.
     */
    void registerPodIdentityWebhook(String containerId, Cluster cluster) {
        registerPodIdentityWebhook(containerId, cluster, () -> nodeDnsServerIp().isPresent());
    }

    /**
     * {@code nodeResolvesThroughFloci} answers whether the node finds Floci's hostname through Floci's DNS
     * server. It is asked only once the webhook can be registered: an adopted node answers it with a Docker
     * inspect.
     */
    private void registerPodIdentityWebhook(String containerId, Cluster cluster,
                                            BooleanSupplier nodeResolvesThroughFloci) {
        if (!config.services().eks().podIdentityWebhook()) {
            return;
        }
        String clusterName = cluster.getName();
        if (!config.tls().enabled()) {
            LOG.warnv("EKS Pod Identity injection is off for cluster {0}: Kubernetes only accepts an "
                    + "https admission webhook URL, and Floci serves HTTP with floci.tls.enabled=false. "
                    + "Set FLOCI_TLS_ENABLED=true to have pods mutated. Pods still start, without the "
                    + "pod identity token or credentials environment variables.", clusterName);
            removePodIdentityWebhook(containerId, cluster);
            return;
        }
        if (certificateAuthority == null) {
            LOG.warnv("EKS Pod Identity injection is off for cluster {0}: no local CA is available to "
                    + "put in the webhook caBundle", clusterName);
            return;
        }
        boolean nodeUsesEmbeddedDns = nodeResolvesThroughFloci.getAsBoolean();
        Optional<String> baseUrl = reachableEndpoint.httpsBaseUrl(nodeUsesEmbeddedDns);
        if (baseUrl.isEmpty()) {
            LOG.warnv("EKS Pod Identity injection is off for cluster {0}: the certificate Floci serves does not "
                    + "name the host of {1}, where the cluster''s API server would call the webhook, so it would "
                    + "refuse the handshake. Pods still start, without the pod identity token or credentials "
                    + "environment variables. To have them mutated, serve a certificate that names that host, or "
                    + "set FLOCI_HOSTNAME to a name the certificate covers while the cluster resolves through "
                    + "Floci''s embedded DNS (floci.services.eks.embedded-dns): the API server then calls Floci "
                    + "by that name.", clusterName, reachableEndpoint.baseUrl(nodeUsesEmbeddedDns));
            removePodIdentityWebhook(containerId, cluster);
            return;
        }
        String url = baseUrl.get()
                + podIdentityWebhookPath(clusterName, resolveClusterAccountId(cluster), clusterRegion(cluster));
        try {
            installPodIdentityManifest(containerId, cluster, buildPodIdentityWebhookConfiguration(url,
                    ContainerCaBundle.endpointTrustPem(config, certificateAuthority.caPem())));
            LOG.infov("Registered the EKS Pod Identity mutating webhook ({0}) for cluster {1}", url, clusterName);
        } catch (Exception e) {
            LOG.warnv("EKS cluster {0} gets no pod identity injection: could not install {1} into the k3s "
                    + "container: {2}", clusterName, POD_IDENTITY_MANIFEST_FILE, e.getMessage());
        }
    }

    /**
     * Replaces the manifest with {@link #POD_IDENTITY_MANIFEST_REMOVED}. The manifest, and the webhook
     * configuration k3s created from it, live on the cluster's data volume, which outlives a restart and a
     * replaced container, so a webhook registered under an earlier address, certificate or TLS setting would
     * otherwise keep being called, each pod creation waiting out its timeout. k3s applies a changed manifest
     * by pruning what the earlier version created, among the kinds it held, so a file with no objects deletes
     * that configuration.
     */
    private void removePodIdentityWebhook(String containerId, Cluster cluster) {
        try {
            installPodIdentityManifest(containerId, cluster, POD_IDENTITY_MANIFEST_REMOVED);
        } catch (Exception e) {
            LOG.warnv("EKS cluster {0} may keep a pod identity webhook registered before: could not replace {1} "
                    + "in the k3s container: {2}", cluster.getName(), POD_IDENTITY_MANIFEST_FILE, e.getMessage());
        }
    }

    private void installPodIdentityManifest(String containerId, Cluster cluster, String manifest) {
        installManifest(containerId, cluster, "webhook", POD_IDENTITY_MANIFEST_FILE, POD_IDENTITY_MANIFEST_TAR_ENTRY,
                manifest);
    }

    /**
     * Writes {@code manifest} into the k3s container's {@code server/manifests}, which k3s applies, and keeps a
     * local copy under {@code <dataPath>/<kind>/...} for {@link #localClusterFile}.
     */
    private void installManifest(String containerId, Cluster cluster, String kind, String fileName, String tarEntry,
                                 String manifest) {
        writeLocalCopy(localClusterFile(cluster, kind, fileName), manifest, cluster.getName());
        byte[] tar = tarSingleFile(tarEntry, manifest);
        RetryingTarCopier.copyStreamed(lifecycleManager.getDockerClient(), containerId, K3S_DATA_DIR,
                fileName, out -> out.write(tar),
                RetryingTarCopier.DEFAULT_MAX_ATTEMPTS, archiveRetryBackoffMillis);
    }

    /**
     * Installs the EKS Auto Mode block storage provisioner while the cluster's {@code storageConfig.blockStorage} is
     * enabled: claims of a StorageClass naming {@code ebs.csi.eks.amazonaws.com} are then provisioned from the
     * node's disk. Otherwise, if it was installed before, replaces it with {@link #BLOCK_STORAGE_MANIFEST_REMOVED}:
     * the manifest lives on the cluster's data volume, which outlives a restart, and k3s prunes what it created.
     */
    void applyBlockStorage(String containerId, Cluster cluster) {
        applyAutoModeManifest(containerId, cluster, StorageConfig.blockStorageOn(cluster.getStorageConfig()),
                BLOCK_STORAGE_MANIFEST_FILE, BLOCK_STORAGE_MANIFEST_TAR_ENTRY, this::blockStorageManifest,
                BLOCK_STORAGE_MANIFEST_REMOVED, "block storage provisioner");
    }

    /**
     * Installs an EKS Auto Mode capability's manifest while it is {@code enabled}, or, if it was installed before,
     * replaces it with {@code removed}, which holds no objects: the manifest lives on the cluster's data volume,
     * which outlives a restart, and k3s prunes what it created.
     */
    private void applyAutoModeManifest(String containerId, Cluster cluster, boolean enabled, String fileName,
                                       String tarEntry, Supplier<String> manifest, String removed, String what) {
        try {
            if (!enabled && !Files.exists(localClusterFile(cluster, "auto-mode", fileName))) {
                return;
            }
            installManifest(containerId, cluster, "auto-mode", fileName, tarEntry, enabled ? manifest.get() : removed);
            if (enabled) {
                LOG.infov("Installed the EKS Auto Mode {0} for cluster {1}", what, cluster.getName());
            }
        } catch (Exception e) {
            LOG.warnv("EKS cluster {0} gets no Auto Mode {1}: could not install {2} into the k3s container: {3}",
                    cluster.getName(), what, fileName, e.getMessage());
        }
    }

    /**
     * Installs, while the cluster's EKS Auto Mode compute is on, the NodePool and NodeClass kinds, the {@code default}
     * NodeClass and the built-in node pools its {@code computeConfig.nodePools} names, as Auto Mode does (measured), and
     * removes what it no longer names; otherwise removes them all. Floci launches no nodes for them.
     */
    void applyCompute(String containerId, Cluster cluster) {
        applyAutoModeManifest(containerId, cluster, computeOn(cluster), COMPUTE_MANIFEST_FILE, COMPUTE_MANIFEST_TAR_ENTRY,
                () -> computeManifest(cluster), COMPUTE_MANIFEST_REMOVED, "node pools");
    }

    /**
     * Gives the cluster's running node the label EKS Auto Mode's nodes carry while compute is on, and takes it off
     * once compute is off. k3s's {@code --node-label} adds a label when the node starts but never removes one, and a
     * node started before Floci set it has none until it restarts. A cluster that never had Auto Mode compute is left
     * alone.
     */
    void applyComputeLabel(Cluster cluster, String containerId) {
        if (cluster.getComputeConfig() == null || containerId == null) {
            return;
        }
        String label = computeOn(cluster) ? AUTO_COMPUTE_LABEL : AUTO_COMPUTE_LABEL_KEY + "-";
        try {
            ContainerExec.Result result = execInContainerForResult(containerId, new String[]{"kubectl", "label",
                    "node", deriveClusterNodePrivateDnsName(cluster), "--overwrite", label}, 15);
            if (result.exitCode() != 0) {
                LOG.warnv("Could not set the Auto Mode label of EKS cluster {0}''s node: {1}", cluster.getName(),
                        result.summary());
            }
        } catch (Exception e) {
            LOG.warnv("Could not set the Auto Mode label of EKS cluster {0}''s node: {1}", cluster.getName(),
                    e.getMessage());
        }
    }

    static boolean computeOn(Cluster cluster) {
        return cluster != null && cluster.getComputeConfig() != null
                && Boolean.TRUE.equals(cluster.getComputeConfig().enabled());
    }

    /**
     * The kinds and the {@code default} NodeClass, with the node role's name, the cluster's subnets and its security
     * group as Auto Mode sets them, then the pools the cluster names. Without a node role there are no pools, and no
     * NodeClass is written.
     */
    String computeManifest(Cluster cluster) {
        String manifest = resource(EksClusterManager.class.getResourceAsStream("/eks/auto-mode-compute.yaml"),
                "/eks/auto-mode-compute.yaml");
        String roleArn = cluster.getComputeConfig().nodeRoleArn();
        if (roleArn == null || roleArn.isBlank()) {
            return manifest.substring(0, manifest.indexOf(DEFAULT_NODE_CLASS_MARKER));
        }
        ResourcesVpcConfig vpc = cluster.getResourcesVpcConfig();
        List<String> subnets = vpc == null || vpc.getSubnetIds() == null ? List.of() : vpc.getSubnetIds();
        String securityGroup = vpc == null ? null : vpc.getClusterSecurityGroupId();
        StringBuilder out = new StringBuilder(manifest
                .replace("%ROLE%", roleArn.substring(roleArn.lastIndexOf('/') + 1))
                .replace("%SUBNET_TERMS%", selectorTerms(subnets))
                .replace("%SECURITY_GROUP_TERMS%", selectorTerms(securityGroup == null ? List.of() : List.of(securityGroup))));
        List<String> pools = cluster.getComputeConfig().nodePools() == null ? List.of()
                : cluster.getComputeConfig().nodePools();
        if (pools.contains("general-purpose")) {
            out.append(resource(EksClusterManager.class.getResourceAsStream("/eks/auto-mode-nodepool-general-purpose.yaml"),
                    "/eks/auto-mode-nodepool-general-purpose.yaml"));
        }
        if (pools.contains("system")) {
            out.append(resource(EksClusterManager.class.getResourceAsStream("/eks/auto-mode-nodepool-system.yaml"),
                    "/eks/auto-mode-nodepool-system.yaml"));
        }
        return out.toString();
    }

    /** The rest of a selector terms key's line: {@code []} for none, else one {@code - id:} term a line. */
    private static String selectorTerms(List<String> ids) {
        if (ids.isEmpty()) {
            return " []";
        }
        StringBuilder terms = new StringBuilder();
        for (String id : ids) {
            terms.append("\n  - id: ").append(id);
        }
        return terms.toString();
    }

    /** A packaged resource the caller opened with an inline literal, which the native image build finds. */
    private static String resource(InputStream in, String path) {
        try (in) {
            if (in == null) {
                throw new IllegalStateException("missing resource " + path);
            }
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** The block storage manifest with the configured provisioner and helper images. */
    String blockStorageManifest() {
        // An inline literal, which the native image build finds and packages.
        try (InputStream in = EksClusterManager.class.getResourceAsStream("/eks/auto-mode-block-storage.yaml")) {
            if (in == null) {
                throw new IllegalStateException("missing resource /eks/auto-mode-block-storage.yaml");
            }
            return new String(in.readAllBytes(), StandardCharsets.UTF_8)
                    .replace("%PROVISIONER_IMAGE%", config.services().eks().blockStorageProvisionerImage())
                    .replace("%HELPER_IMAGE%", config.services().eks().blockStorageHelperImage());
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /**
     * Whether an adopted node resolves through Floci's DNS server. A container keeps the resolvers it was
     * created with, so a node created while that server was off or out of its reach cannot find Floci's
     * hostname now, nor can one created when the server had another address.
     */
    private boolean survivingNodeResolvesThroughFloci(String containerId) {
        Optional<String> dnsServerIp = nodeDnsServerIp();
        if (dnsServerIp.isEmpty()) {
            return false;
        }
        try {
            InspectContainerResponse inspect = lifecycleManager.getDockerClient().inspectContainerCmd(containerId).exec();
            String[] resolvers = inspect.getHostConfig() == null ? null : inspect.getHostConfig().getDns();
            return resolvers != null && Arrays.asList(resolvers).contains(dnsServerIp.get());
        } catch (Exception e) {
            LOG.warnv("Could not read the resolvers of k3s container {0}, so its pod identity webhook is called "
                    + "at the Docker host: {1}", containerId, e.getMessage());
            return false;
        }
    }

    /**
     * The Floci pod identity admission route. The account is in the path for the same reason the
     * token webhook puts its scope there: the API server calls Floci with no AWS credentials, so a
     * cluster owned by a non-default account would otherwise never be found.
     */
    static String podIdentityWebhookPath(String clusterName, String accountId, String region) {
        return "/_floci/eks/clusters/" + clusterName + "/pod-identity-webhook/scope/" + accountId + "/" + region;
    }

    /**
     * Builds the {@code MutatingWebhookConfiguration} k3s auto-applies. Scoped to pod {@code CREATE}
     * alone, and {@code failurePolicy: Ignore} so an unreachable or failing Floci never blocks a pod
     * from being created. The {@code caBundle} is what {@code GET /_floci/ca.pem} serves, which
     * trusts Floci's HTTPS endpoint, base64 of the PEM as Kubernetes expects.
     *
     * <p>{@code timeoutSeconds} is 3, not the Kubernetes default of 10: Floci is a local process, so
     * a healthy call takes milliseconds, and the timeout only ever runs down when Floci is
     * unreachable. Every pod creation in the cluster pays it in that case, so it is kept short.
     */
    static String buildPodIdentityWebhookConfiguration(String url, String caPem) {
        return """
                apiVersion: admissionregistration.k8s.io/v1
                kind: MutatingWebhookConfiguration
                metadata:
                  name: floci-eks-pod-identity
                webhooks:
                  - name: pod-identity.eks.floci.io
                    admissionReviewVersions: ["v1"]
                    sideEffects: None
                    failurePolicy: Ignore
                    reinvocationPolicy: Never
                    timeoutSeconds: 3
                    clientConfig:
                      url: "%s"
                      caBundle: "%s"
                    rules:
                      - operations: ["CREATE"]
                        apiGroups: [""]
                        apiVersions: ["v1"]
                        resources: ["pods"]
                        scope: "*"
                """.formatted(url, Base64.getEncoder().encodeToString(caPem.getBytes(StandardCharsets.UTF_8)));
    }

    /** Best-effort local copy for inspection/debugging; the container copy streams from memory. */
    private void writeLocalCopy(Path file, String content, String clusterName) {
        Path localFile = file.toAbsolutePath().normalize();
        try {
            Files.createDirectories(localFile.getParent());
            Files.writeString(localFile, content);
        } catch (IOException e) {
            LOG.debugv("Could not write local {0} copy for cluster {1}: {2}",
                    localFile.getFileName(), clusterName, e.getMessage());
        }
    }

    private static byte[] tarSingleFile(String entryName, String content) {
        try {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] data = content.getBytes(StandardCharsets.UTF_8);
            try (TarArchiveOutputStream tar = new TarArchiveOutputStream(out)) {
                TarArchiveEntry entry = new TarArchiveEntry(entryName);
                entry.setSize(data.length);
                entry.setMode(0644);
                tar.putArchiveEntry(entry);
                tar.write(data);
                tar.closeArchiveEntry();
            }
            return out.toByteArray();
        } catch (IOException e) {
            throw new IllegalStateException("Could not build in-memory tar for " + entryName, e);
        }
    }

    /** Every region of the cluster region's partition, and the cluster region itself if it is unpublished. */
    static List<String> mirrorRegions(String clusterRegion) {
        List<String> regions = new ArrayList<>(AwsRegions.advertised(AwsRegions.partitionFor(clusterRegion)));
        if (!regions.contains(clusterRegion)) {
            regions.add(clusterRegion);
        }
        return regions;
    }

    /** The cluster's account first, then the default account when it differs. */
    static List<String> mirrorAccounts(String clusterAccountId, String defaultAccountId) {
        Set<String> accounts = new LinkedHashSet<>();
        accounts.add(clusterAccountId);
        if (defaultAccountId != null && !defaultAccountId.isBlank()) {
            accounts.add(defaultAccountId);
        }
        return List.copyOf(accounts);
    }

    /**
     * Builds the k3s registries.yaml content. One mirror entry per hostname-style repository URI
     * ({@code <account>.dkr.ecr.<region>.localhost:<port>}) plus one for the path-style form
     * ({@code localhost:<port>}), all pointing at Floci's in-network data plane. The port is the
     * one the repository URIs advertise, which differs from the endpoint's port when Floci's
     * container publishes its port on another host port. The TLS URI mode adds the corresponding
     * {@code localhost.floci.io} aliases. Each account's registry host as AWS names it,
     * {@code <account>.dkr.ecr.<region>.<dnsSuffix>}, is mirrored too, so an image a chart names
     * that way is pulled from Floci; containerd still tries AWS last, for an image Floci does not
     * hold. k3s supports no partial wildcards and a {@code "*"} catch-all would also intercept
     * public registries and other accounts' registries, so the hostnames are enumerated explicitly.
     */
    static String buildRegistriesYaml(String accountId, List<String> regions, int advertisedPort, String endpoint) {
        return buildRegistriesYaml(accountId, regions, advertisedPort, endpoint, false);
    }

    static String buildRegistriesYaml(String accountId, List<String> regions, int advertisedPort,
                                     String endpoint, boolean tlsUri) {
        return buildRegistriesYaml(List.of(accountId), regions, advertisedPort, endpoint, tlsUri);
    }

    static String buildRegistriesYaml(List<String> accountIds, List<String> regions, int advertisedPort,
                                     String endpoint, boolean tlsUri) {
        StringBuilder yaml = new StringBuilder("mirrors:\n");
        for (String accountId : accountIds) {
            for (String region : regions) {
                appendMirror(yaml, accountId + ".dkr.ecr." + region + ".localhost:" + advertisedPort, endpoint);
                if (tlsUri) {
                    appendMirror(yaml, accountId + ".dkr.ecr." + region + ".localhost.floci.io:" + advertisedPort,
                            endpoint);
                }
                appendMirror(yaml, accountId + ".dkr.ecr." + region + "." + AwsRegions.dnsSuffixFor(region), endpoint);
            }
        }
        appendMirror(yaml, "localhost:" + advertisedPort, endpoint);
        if (tlsUri) {
            appendMirror(yaml, "localhost.floci.io:" + advertisedPort, endpoint);
        }
        return yaml.toString();
    }

    private static void appendMirror(StringBuilder yaml, String host, String endpoint) {
        yaml.append("  \"").append(host).append("\":\n")
                .append("    endpoint:\n")
                .append("      - \"").append(endpoint).append("\"\n");
    }

    /** The Floci token-webhook URL as reachable from inside the k3s container. */
    String webhookUrl(String clusterName) {
        return "http://" + dockerHostResolver.resolve() + ":" + config.port() + webhookPath(clusterName);
    }

    /** The cluster's ARN names its region; {@code defaultRegion} answers for a cluster without one. */
    static String webhookPath(Cluster cluster, String defaultRegion) {
        // client-go replaces a server URL query when constructing its TokenReview request.
        String accountId = cluster.getAccountId() != null && !cluster.getAccountId().isBlank()
                ? cluster.getAccountId()
                : AwsArnUtils.accountOrDefault(cluster.getArn(), "000000000000");
        String region = AwsArnUtils.regionOrDefault(cluster.getArn(), defaultRegion);
        Instant createdAt = cluster.getCreatedAt() != null ? cluster.getCreatedAt() : Instant.EPOCH;
        return webhookPath(cluster.getName()) + "/scope/" + accountId
                + "/" + region + "/" + createdAt;
    }

    static String webhookPath(String clusterName) {
        return "/_floci/eks/clusters/" + clusterName + "/token-webhook";
    }

    /**
     * Builds a minimal kubeconfig that points the k3s API server's token-authentication webhook
     * at Floci. The webhook server uses anonymous access (no client credentials needed).
     */
    static String buildWebhookKubeconfig(String serverUrl) {
        return """
                apiVersion: v1
                kind: Config
                clusters:
                - name: floci-token-webhook
                  cluster:
                    server: %s
                users:
                - name: floci-token-webhook
                contexts:
                - name: floci-token-webhook
                  context:
                    cluster: floci-token-webhook
                    user: floci-token-webhook
                current-context: floci-token-webhook
                """.formatted(serverUrl);
    }

    void registerClusterNodeInstance(Cluster cluster, String containerId) {
        recordClusterNodeInstance(cluster, containerId);
        notifyNodeRegistrationListeners(cluster);
    }

    /** Records the cluster's node as an EC2 instance, without telling the listeners, which may execute in it. */
    private void recordClusterNodeInstance(Cluster cluster, String containerId) {
        try {
            String accountId = resolveClusterAccountId(cluster);
            String region = clusterRegion(cluster);
            ContainerIps containerIps = resolveContainerIps(containerId);
            Instance nodeInstance = synthesizeClusterNodeInstance(cluster, containerIps.primaryIp(), region, accountId);
            recordNodeContainer(nodeInstance, containerId, containerIps);
            clusterNodeInstances.put(clusterResourceName(cluster), new ClusterNodeRecord(accountId, region, nodeInstance));
            registerClusterNodeVpc(cluster, accountId, region, containerIps.allIps());
        } catch (Exception e) {
            LOG.warnv("Could not register cluster node instance for EKS cluster {0}: {1}",
                    cluster.getName(), e.getMessage());
        }
    }

    private void notifyNodeRegistrationListeners(Cluster cluster) {
        ClusterNodeRecord node = clusterNodeInstances.get(clusterResourceName(cluster));
        if (node == null) {
            return;
        }
        for (Consumer<Instance> listener : nodeRegistrationListeners) {
            try {
                listener.accept(node.instance());
            } catch (Exception e) {
                LOG.warnv("Node registration listener failed for cluster {0}: {1}",
                        cluster.getName(), e.getMessage());
            }
        }
    }

    /**
     * With {@code imds}, registers the cluster's node with the metadata server and answers IMDS in the node: NAT rules
     * send the node's requests, and with {@code imds-pod-network} its pods' too, straight to Floci's metadata service
     * (see {@link EksPodNetworkRouting#buildImdsForwardCommand}). No relay process runs in the node, so nothing has
     * to be installed there, which a stock {@code rancher/k3s} image could not do.
     */
    void configureLinkLocalMetadataEndpoint(Cluster cluster, String containerId) {
        if (!config.services().eks().imds()) {
            return;
        }
        try {
            ClusterNodeRecord record = clusterNodeInstances.get(clusterResourceName(cluster));
            Instance nodeInstance = record != null ? record.instance() : null;
            ContainerIps containerIps = resolveContainerIps(containerId);
            if (nodeInstance == null) {
                String accountId = resolveClusterAccountId(cluster);
                String region = clusterRegion(cluster);
                nodeInstance = synthesizeClusterNodeInstance(cluster, containerIps.primaryIp(), region, accountId);
                recordNodeContainer(nodeInstance, containerId, containerIps);
                clusterNodeInstances.put(clusterResourceName(cluster), new ClusterNodeRecord(accountId, region, nodeInstance));
            } else if (nodeInstance.getDockerContainerId() == null) {
                recordNodeContainer(nodeInstance, containerId, containerIps);
            }

            if (metadataServer != null) {
                metadataServer.reconcileClusterNodeAddresses(containerIps.allIps(), nodeInstance);
            }

            ContainerExec.Result forward = execInContainerForResult(containerId,
                    EksPodNetworkRouting.buildImdsForwardCommand(podSourceCidrs(cluster),
                            config.services().eks().imdsPodNetwork(), dockerHostResolver.resolve(),
                            config.services().ec2().imdsPort()), 30);
            if (forward.exitCode() != 0) {
                LOG.warnv("Could not forward the link-local IMDS address for EKS cluster {0} to Floci: {1}",
                        cluster.getName(), forward.summary());
                return;
            }

            LOG.infov("Configured link-local IMDS endpoint for EKS cluster {0}", cluster.getName());
        } catch (Exception e) {
            LOG.warnv("Could not configure link-local IMDS endpoint for EKS cluster {0}: {1}",
                    cluster.getName(), e.getMessage());
        }
    }

    /**
     * Makes the EKS Pod Identity address, {@code 169.254.170.23:80}, answer in the node while the
     * {@code eks-pod-identity-agent} add-on is stored, as the agent's DaemonSet answers it on EKS
     * while the add-on is installed, and removes the forward otherwise, including one an earlier
     * Floci left. Runs whenever the node starts or is adopted, so a restart forwards only while the
     * add-on is stored: one deleted with {@code preserve}, whose agent EKS would leave running,
     * keeps its forward until then.
     *
     * <p>NAT rules send pod and node requests for the address straight to Floci's credentials
     * endpoint (see {@link EksPodNetworkRouting#buildPodIdentityForwardCommand}). No relay process
     * runs in the node, so nothing has to be installed there, which a stock {@code rancher/k3s}
     * image could not do.
     */
    void configurePodIdentityForward(Cluster cluster, String containerId) {
        if (podIdentityForwardEnabled()) {
            reconcilePodIdentityForward(cluster, containerId);
        }
    }

    /**
     * CreateAddon stored, or DeleteAddon without {@code preserve} removed, the
     * {@code eks-pod-identity-agent} add-on of {@code cluster}: its node answers on the Pod Identity
     * address while the add-on is stored. A cluster without a node, as when EKS is mocked, is left
     * alone.
     */
    public void podIdentityAgentChanged(Cluster cluster) {
        // A node whose API has not answered yet reads the add-on store in its guest setup instead: Terraform sends
        // this add-on seconds after CreateCluster.
        if (podIdentityForwardEnabled() && cluster.getContainerId() != null
                && guestReachable(cluster.getContainerId())) {
            reconcilePodIdentityForward(cluster, cluster.getContainerId());
        }
    }

    /**
     * Forwards the address while the add-on is stored and removes the forward otherwise, reading the
     * store under the cluster's lock: of a CreateAddon and a DeleteAddon racing, or of either and a
     * restart's adoption, the later waits for the earlier's exec and then reads the store, so the node
     * ends as the store does. A store that can't be read leaves the node as it is.
     */
    private void reconcilePodIdentityForward(Cluster cluster, String containerId) {
        synchronized (podIdentityForwardLocks.lockFor(clusterResourceName(cluster))) {
            boolean installed;
            try {
                installed = podIdentityAgentInstalled(cluster);
            } catch (RuntimeException e) {
                LOG.warnv("Could not read the {0} add-on of EKS cluster {1}, leaving its Pod Identity address as it is: {2}",
                        EksPodIdentityWebhook.POD_IDENTITY_ADDON, cluster.getName(), e.getMessage());
                return;
            }
            if (installed) {
                forwardPodIdentityAddress(cluster, containerId);
            } else {
                removePodIdentityForward(cluster, containerId);
            }
        }
    }

    /** Pods only get the Pod Identity credentials URL through the webhook, which needs TLS. */
    private boolean podIdentityForwardEnabled() {
        return config.services().eks().podIdentityWebhook() && config.tls().enabled();
    }

    private boolean podIdentityAgentInstalled(Cluster cluster) {
        EksAddonService addons = addonService();
        return addons != null && addons.isInstalled(cluster, EksPodIdentityWebhook.POD_IDENTITY_ADDON);
    }

    private void forwardPodIdentityAddress(Cluster cluster, String containerId) {
        try {
            ContainerExec.Result forward = execInContainerForResult(containerId,
                    EksPodNetworkRouting.buildPodIdentityForwardCommand(podSourceCidrs(cluster),
                            dockerHostResolver.resolve(), config.port()), 30);
            if (forward.exitCode() != 0) {
                LOG.warnv("Could not forward the Pod Identity address for EKS cluster {0} to Floci: {1}",
                        cluster.getName(), forward.summary());
                return;
            }
            LOG.infov("Forwarding the Pod Identity address {0}:{1,number,#} to Floci for EKS cluster {2}",
                    EksPodNetworkRouting.POD_IDENTITY_ENDPOINT.ip(), EksPodNetworkRouting.POD_IDENTITY_ENDPOINT.port(),
                    cluster.getName());
        } catch (Exception e) {
            LOG.warnv("Could not forward the Pod Identity address for EKS cluster {0} to Floci: {1}",
                    cluster.getName(), e.getMessage());
        }
    }

    private void removePodIdentityForward(Cluster cluster, String containerId) {
        try {
            ContainerExec.Result removal = execInContainerForResult(containerId,
                    EksPodNetworkRouting.buildPodIdentityRemoveCommand(), 15);
            if (removal.exitCode() != 0) {
                LOG.warnv("Could not remove the Pod Identity forward for EKS cluster {0}: {1}",
                        cluster.getName(), removal.summary());
            }
        } catch (Exception e) {
            LOG.warnv("Could not remove the Pod Identity forward for EKS cluster {0}: {1}",
                    cluster.getName(), e.getMessage());
        }
    }

    void configureVpcRoutes(Cluster cluster, String containerId) {
        if (!config.services().eks().vpcRouteProgramming() || config.services().eks().mock()) {
            return;
        }
        if (cluster == null || containerId == null || cluster.getResourcesVpcConfig() == null) {
            return;
        }
        Ec2Service ec2 = ec2Service();
        if (ec2 == null) {
            return;
        }
        String clusterKey = clusterResourceName(cluster);
        synchronized (routeLockFor(clusterKey)) {
            if (!activeClusters.containsKey(clusterKey)) {
                return;
            }
            try {
                ResourcesVpcConfig vpcConfig = cluster.getResourcesVpcConfig();
                String vpcId = vpcConfig.getVpcId();
                if (vpcId == null || vpcId.isBlank()) {
                    return;
                }
                String region = clusterRegion(cluster);
                String accountId = resolveClusterAccountId(cluster);
                ec2.attachContainerToVpc(accountId, region, vpcId, containerId);

                List<RouteTable> vpcRouteTables = ec2.describeRouteTables(accountId, region, List.of(), Map.of("vpc-id", List.of(vpcId)));
                List<RouteTable> applicable = EksVpcRouteProgramming.findApplicableRouteTables(
                        vpcId, vpcConfig.getSubnetIds(), vpcRouteTables);
                List<EksVpcRouteProgramming.VpcRouteEntry> entries = applicable.isEmpty()
                        ? List.of()
                        : EksVpcRouteProgramming.resolveProgrammableRoutes(cluster, region, applicable, ec2);

                Set<String> desiredDests = entries.stream()
                        .map(EksVpcRouteProgramming.VpcRouteEntry::destinationCidrBlock)
                        .collect(Collectors.toSet());
                Set<String> previousDests = programmedClusterRoutes.get(clusterKey);
                if (previousDests == null) {
                    previousDests = inspectProgrammedRoutes(containerId);
                }
                Set<String> toDelete = new LinkedHashSet<>(previousDests);
                toDelete.removeAll(desiredDests);

                Optional<String[]> cmd = EksVpcRouteProgramming.buildRoutingCommand(entries, toDelete);
                if (cmd.isEmpty()) {
                    programmedClusterRoutes.put(clusterKey, desiredDests);
                    return;
                }
                ContainerExec.Result result = execInContainerForResult(containerId, cmd.get(), 30);
                if (result.exitCode() != 0) {
                    LOG.warnv("Could not program VPC routes for EKS cluster {0}: {1}",
                            cluster.getName(), result.summary());
                } else {
                    programmedClusterRoutes.put(clusterKey, desiredDests);
                    LOG.infov("Configured VPC routes for EKS cluster {0}: {1} programmed, {2} deleted",
                            cluster.getName(), entries.size(), toDelete.size());
                }
            } catch (Exception e) {
                LOG.warnv("Could not program VPC routes for EKS cluster {0}: {1}",
                        cluster.getName(), e.getMessage());
            }
        }
    }

    private Set<String> inspectProgrammedRoutes(String containerId) {
        try {
            String output = execInContainer(containerId,
                    new String[]{"sh", "-c", "cat /run/floci-vpc-routes.txt 2>/dev/null || true"});
            if (output == null || output.isBlank()) {
                return Set.of();
            }
            Set<String> routes = new LinkedHashSet<>();
            for (String line : output.split("\\r?\\n")) {
                String trimmed = line.trim();
                if (EksVpcRouteProgramming.isValidIpv4Cidr(trimmed)) {
                    routes.add(trimmed);
                }
            }
            return routes;
        } catch (Exception e) {
            LOG.debugv("Could not inspect programmed routes for container {0}: {1}", containerId, e.getMessage());
            return Set.of();
        }
    }

    @Override
    public void onRouteTableUpdated(String region, RouteTable routeTable) {
        if (!config.services().eks().vpcRouteProgramming() || config.services().eks().mock()) {
            return;
        }
        if (routeTable == null || routeTable.getVpcId() == null) {
            return;
        }
        for (Cluster cluster : activeClusters.values()) {
            // A node whose API has not answered yet gets every route table's routes from its guest setup.
            if (cluster.getResourcesVpcConfig() != null
                    && routeTable.getVpcId().equals(cluster.getResourcesVpcConfig().getVpcId())
                    && cluster.getContainerId() != null
                    && guestReachable(cluster.getContainerId())) {
                String clusterAccount = resolveClusterAccountId(cluster);
                if (routeTable.getOwnerId() != null && clusterAccount != null
                        && !routeTable.getOwnerId().equals(clusterAccount)) {
                    continue;
                }
                try {
                    configureVpcRoutes(cluster, cluster.getContainerId());
                } catch (Exception e) {
                    LOG.warnv("Could not update VPC routes for EKS cluster {0}: {1}",
                            cluster.getName(), e.getMessage());
                }
            }
        }
    }

    void unregisterMetadataEndpoint(Cluster cluster) {
        String clusterKey = clusterResourceName(cluster);
        Object lock = clusterRouteLocks.get(clusterKey);
        if (lock != null) {
            synchronized (lock) {
                activeClusters.remove(clusterKey);
                programmedClusterRoutes.remove(clusterKey);
                clusterRouteLocks.remove(clusterKey, lock);
            }
        } else {
            activeClusters.remove(clusterKey);
            programmedClusterRoutes.remove(clusterKey);
        }
        forgetClusterNodeVpcs(clusterKey);
        joinedClusterInstances.remove(clusterKey);
        ClusterNodeRecord record = clusterNodeInstances.remove(clusterKey);
        Instance nodeInstance = record != null ? record.instance() : null;
        if (metadataServer != null && nodeInstance != null) {
            metadataServer.unregisterInstance(nodeInstance);
        }
    }

    /**
     * What a read of a service account found: the service account as {@code kubectl get --output json} prints it,
     * null for one that does not exist; or that the read failed, which says nothing about the service account.
     */
    record ServiceAccountRead(boolean succeeded, String json) {

        static ServiceAccountRead found(String json) {
            return new ServiceAccountRead(true, json);
        }

        static ServiceAccountRead absent() {
            return new ServiceAccountRead(true, null);
        }

        static ServiceAccountRead failed() {
            return new ServiceAccountRead(false, null);
        }
    }

    /**
     * Reads the service account {@code name} in {@code namespace} with kubectl in the cluster's server container,
     * for the pod identity webhook, which is handed only the pod. A cluster with no container has none to read. A
     * failed read is logged as a warning, and a service account that does not exist at debug level.
     */
    ServiceAccountRead serviceAccount(Cluster cluster, String namespace, String name, int timeoutSeconds) {
        String containerId = cluster.getContainerId();
        if (containerId == null) {
            return ServiceAccountRead.absent();
        }
        try {
            ContainerExec.Result result = execInContainerSeparatingStderr(containerId,
                    new String[]{"kubectl", "--request-timeout=" + timeoutSeconds + "s", "get", "serviceaccount", name,
                            "--namespace", namespace, "--output", "json"}, timeoutSeconds);
            if (!result.timedOut() && result.exitCode() == 0) {
                return ServiceAccountRead.found(result.stdout());
            }
            if (!result.timedOut() && result.stderr() != null && result.stderr().contains("(NotFound)")) {
                LOG.debugv("Service account {0}/{1} does not exist in cluster {2}", namespace, name,
                        cluster.getName());
                return ServiceAccountRead.absent();
            }
            LOG.warnv("Could not read service account {0}/{1} in cluster {2}, so its pods get no IAM role "
                    + "injected: {3}", namespace, name, cluster.getName(),
                    result.timedOut() ? "kubectl did not answer in " + timeoutSeconds + "s" : result.summary());
            return ServiceAccountRead.failed();
        } catch (RuntimeException e) {
            LOG.warnv(e, "Could not read service account {0}/{1} in cluster {2}, so its pods get no IAM role "
                    + "injected", namespace, name, cluster.getName());
            return ServiceAccountRead.failed();
        }
    }

    String clusterRegion(Cluster cluster) {
        String region = cluster == null ? null : AwsArnUtils.regionOrDefault(cluster.getArn(), null);
        if (region != null && !region.isBlank()) {
            return region;
        }
        if (regionResolver != null && regionResolver.getDefaultRegion() != null && !regionResolver.getDefaultRegion().isBlank()) {
            return regionResolver.getDefaultRegion();
        }
        if (config != null && config.defaultRegion() != null && !config.defaultRegion().isBlank()) {
            return config.defaultRegion();
        }
        // Reached only by a test constructor that wires neither the resolver nor the config.
        return "us-east-1"; // partition-literal: unreachable under CDI, where the resolver or config answers
    }

    List<Nodegroup> listActiveNodegroups(Cluster cluster) {
        if (nodeGroupStorage == null || cluster == null || cluster.getName() == null) {
            return List.of();
        }
        String prefix = EksService.clusterKey(clusterRegion(cluster), cluster.getName()) + "/";
        String accountId = resolveClusterAccountId(cluster);
        List<Nodegroup> groups;
        if (nodeGroupStorage instanceof AccountAwareStorageBackend<Nodegroup> aware) {
            groups = aware.scanAllAccountEntries(key -> key.startsWith(prefix)).stream()
                    .filter(entry -> accountId == null || accountId.equals(entry.accountId()))
                    .map(AccountAwareStorageBackend.AccountEntry::value)
                    .toList();
        } else {
            groups = nodeGroupStorage.scan(key -> key.startsWith(prefix));
        }
        return groups.stream()
                .filter(g -> g.getStatus() == null || g.getStatus() == NodegroupStatus.ACTIVE)
                .sorted(Comparator.comparing(Nodegroup::getCreatedAt,
                                Comparator.nullsLast(Comparator.naturalOrder()))
                        .thenComparing(Nodegroup::getNodegroupName,
                                Comparator.nullsLast(Comparator.naturalOrder())))
                .toList();
    }

    Nodegroup selectFirstNodegroup(Cluster cluster) {
        List<Nodegroup> active = listActiveNodegroups(cluster);
        if (active.isEmpty()) {
            return null;
        }
        Nodegroup first = active.getFirst();
        if (active.size() > 1) {
            String clusterName = cluster != null ? cluster.getName() : "unknown";
            for (int i = 1; i < active.size(); i++) {
                Nodegroup later = active.get(i);
                LOG.warnv(
                        "EKS cluster {0} has one shared node; nodegroup {1} metadata (labels/taints) is not applied to the node (already represented by nodegroup {2})",
                        clusterName, later.getNodegroupName(), first.getNodegroupName());
            }
        }
        return first;
    }

    String buildNodeLabels(Cluster cluster, Nodegroup nodegroup, String az, String region) {
        Map<String, String> labels = new LinkedHashMap<>();
        if (az != null && !az.isBlank()) {
            labels.put("topology.kubernetes.io/zone", az);
        }
        if (region != null && !region.isBlank()) {
            labels.put("topology.kubernetes.io/region", region);
        }

        if (nodegroup != null) {
            String capacityType = nodegroup.getCapacityType() != null && !nodegroup.getCapacityType().isBlank()
                    ? nodegroup.getCapacityType() : "ON_DEMAND";
            labels.put("eks.amazonaws.com/capacityType", capacityType); // partition-literal: Kubernetes node label key is partition-invariant

            if (nodegroup.getNodegroupName() != null && !nodegroup.getNodegroupName().isBlank()) {
                labels.put("eks.amazonaws.com/nodegroup", nodegroup.getNodegroupName()); // partition-literal: Kubernetes node label key is partition-invariant
            }

            String imageId = resolveNodegroupImageId(nodegroup, region);
            if (imageId != null && !imageId.isBlank()) {
                labels.put("eks.amazonaws.com/nodegroup-image", imageId); // partition-literal: Kubernetes node label key is partition-invariant
            }

            String instanceType = resolveNodegroupInstanceType(cluster, nodegroup);
            if (instanceType != null && !instanceType.isBlank()) {
                labels.put("node.kubernetes.io/instance-type", instanceType);
            }

            if (nodegroup.getLabels() != null) {
                for (Map.Entry<String, String> entry : nodegroup.getLabels().entrySet()) {
                    String k = entry.getKey();
                    String v = entry.getValue();
                    if (k != null && !k.isBlank() && v != null && !isReservedNodeLabelKey(k)) {
                        labels.put(k, v);
                    }
                }
            }
        }

        if (labels.isEmpty()) {
            return null;
        }
        StringBuilder sb = new StringBuilder();
        for (Map.Entry<String, String> entry : labels.entrySet()) {
            if (!sb.isEmpty()) {
                sb.append(",");
            }
            sb.append(entry.getKey()).append("=").append(entry.getValue());
        }
        return sb.toString();
    }

    private static boolean isReservedNodeLabelKey(String key) {
        return key.startsWith("topology.kubernetes.io/")
                || key.startsWith("eks.amazonaws.com/") // partition-literal: Kubernetes label prefix is partition-invariant
                || "node.kubernetes.io/instance-type".equals(key);
    }

    private String resolveNodegroupInstanceType(Cluster cluster, Nodegroup nodegroup) {
        if (nodegroup != null && nodegroup.getInstanceTypes() != null && !nodegroup.getInstanceTypes().isEmpty()) {
            String first = nodegroup.getInstanceTypes().getFirst();
            if (first != null && !first.isBlank()) {
                return selectNodeInstanceType(first.trim());
            }
        }
        return nodeInstanceType(cluster);
    }

    private String resolveNodegroupImageId(Nodegroup nodegroup, String region) {
        if (nodegroup != null) {
            if (nodegroup.getLaunchTemplate() instanceof Map<?, ?> map) {
                Object directImage = map.get("imageId");
                if (directImage != null && !directImage.toString().isBlank()) {
                    return directImage.toString().trim();
                }
                Ec2Service ec2 = ec2Service();
                if (ec2 != null) {
                    String id = map.get("id") != null ? map.get("id").toString().trim() : null;
                    String name = map.get("name") != null ? map.get("name").toString().trim() : null;
                    String version = map.get("version") != null ? map.get("version").toString().trim() : null;
                    if ((id != null && !id.isBlank()) || (name != null && !name.isBlank())) {
                        try {
                            LaunchTemplateData data = ec2.resolveLaunchTemplateData(region, id, name, version);
                            if (data != null && data.getImageId() != null && !data.getImageId().isBlank()) {
                                return data.getImageId().trim();
                            }
                        } catch (Exception ignored) {
                            // Swallowing is safe because falling back to default image id is standard
                        }
                    }
                }
            }
            // The release version names the AMI's release, not the AMI: the label carries the recommended AMI's id.
            return EksAmiReleaseVersions.recommendedImage(imageCatalog, nodegroup.getVersion(), nodegroup.getAmiType())
                    .map(image -> image.imageId).orElse("ami-eks-k3s");
        }
        return "ami-eks-k3s";
    }

    Ec2ImageCatalog imageCatalog() {
        return imageCatalog;
    }

    String buildRegisterWithTaints(Nodegroup nodegroup) {
        if (nodegroup == null || nodegroup.getTaints() == null || nodegroup.getTaints().isEmpty()) {
            return null;
        }
        List<String> taints = new ArrayList<>();
        for (Object taintObj : nodegroup.getTaints()) {
            String formatted = formatTaint(taintObj);
            if (formatted != null && !formatted.isBlank()) {
                taints.add(formatted);
            }
        }
        if (taints.isEmpty()) {
            return null;
        }
        return String.join(",", taints);
    }

    static String formatTaint(Object taintObj) {
        if (taintObj == null) {
            return null;
        }
        if (taintObj instanceof Map<?, ?> map) {
            Object k = map.get("key");
            if (k == null || k.toString().isBlank()) {
                return null;
            }
            String key = k.toString().trim();
            Object v = map.get("value");
            String val = (v != null) ? v.toString().trim() : null;
            Object eff = map.get("effect");
            String effect = mapTaintEffect(eff != null ? eff.toString() : null);
            if (val != null && !val.isEmpty()) {
                return key + "=" + val + ":" + effect;
            } else {
                return key + ":" + effect;
            }
        } else if (taintObj instanceof String str && !str.isBlank()) {
            return str.trim();
        }
        return null;
    }

    static String mapTaintEffect(String awsEffect) {
        if (awsEffect == null || awsEffect.isBlank()) {
            return "NoSchedule";
        }
        String normalized = awsEffect.trim().toUpperCase(Locale.ROOT);
        return switch (normalized) {
            case "NO_SCHEDULE", "NOSCHEDULE" -> "NoSchedule";
            case "NO_EXECUTE", "NOEXECUTE" -> "NoExecute";
            case "PREFER_NO_SCHEDULE", "PREFERNOSCHEDULE" -> "PreferNoSchedule";
            default -> awsEffect.trim();
        };
    }

    String deriveClusterNodeAvailabilityZone(Cluster cluster, String region) {
        String safeRegion = (region != null && !region.isBlank()) ? region : clusterRegion(cluster);
        return safeRegion + "a";
    }

    String deriveClusterNodeAvailabilityZone(Cluster cluster) {
        return deriveClusterNodeAvailabilityZone(cluster, clusterRegion(cluster));
    }

    String deriveClusterNodeInstanceId(Cluster cluster, String region, String accountId) {
        return "i-" + clusterNodeHex(cluster, region, accountId, "");
    }

    String deriveClusterNodeNetworkInterfaceId(Cluster cluster, String region, String accountId) {
        return "eni-" + clusterNodeHex(cluster, region, accountId, "-eni");
    }

    private String deriveClusterNodeInterfaceAttachmentId(Cluster cluster, String region, String accountId) {
        return "eni-attach-" + clusterNodeHex(cluster, region, accountId, "-eni-attach");
    }

    /** 17 hex digits derived from the cluster's identity and {@code purpose}, so the node's ids survive a restart. */
    private String clusterNodeHex(Cluster cluster, String region, String accountId, String purpose) {
        String safeClusterName = (cluster != null && cluster.getName() != null && !cluster.getName().isBlank())
                ? cluster.getName()
                : "eks-cluster";
        String safeAccountId = (accountId != null && !accountId.isBlank())
                ? accountId
                : resolveClusterAccountId(cluster);
        String safeRegion = (region != null && !region.isBlank())
                ? region
                : clusterRegion(cluster);

        String seed = safeClusterName + "-" + safeAccountId + "-" + safeRegion + purpose;
        String hex = UUID.nameUUIDFromBytes(seed.getBytes(StandardCharsets.UTF_8)).toString().replace("-", "");
        return hex.substring(0, 17);
    }

    String deriveClusterNodeInstanceId(Cluster cluster) {
        String accountId = resolveClusterAccountId(cluster);
        String region = clusterRegion(cluster);
        return deriveClusterNodeInstanceId(cluster, region, accountId);
    }

    String deriveClusterNodeProviderId(Cluster cluster, String region, String accountId) {
        String az = deriveClusterNodeAvailabilityZone(cluster, region);
        String instanceId = deriveClusterNodeInstanceId(cluster, region, accountId);
        return "aws:///" + az + "/" + instanceId;
    }

    String deriveClusterNodeProviderId(Cluster cluster) {
        String accountId = resolveClusterAccountId(cluster);
        String region = clusterRegion(cluster);
        return deriveClusterNodeProviderId(cluster, region, accountId);
    }

    String deriveClusterNodePrivateDnsDomain(String region) {
        return AwsRegions.ec2PrivateDnsDomain(region);
    }

    String deriveClusterNodePrivateDnsName(Cluster cluster, String region, String accountId) {
        String safeRegion = (region != null && !region.isBlank())
                ? region
                : clusterRegion(cluster);
        String instanceId = deriveClusterNodeInstanceId(cluster, safeRegion, accountId);
        return instanceId + "." + deriveClusterNodePrivateDnsDomain(safeRegion);
    }

    String deriveClusterNodePrivateDnsName(Cluster cluster) {
        String accountId = resolveClusterAccountId(cluster);
        String region = clusterRegion(cluster);
        return deriveClusterNodePrivateDnsName(cluster, region, accountId);
    }

    Instance synthesizeClusterNodeInstance(Cluster cluster, String containerIp, String region, String accountId) {
        Instance inst = new Instance();
        String safeClusterName = (cluster != null && cluster.getName() != null && !cluster.getName().isBlank())
                ? cluster.getName()
                : "eks-cluster";
        String safeAccountId = (accountId != null && !accountId.isBlank())
                ? accountId
                : resolveClusterAccountId(cluster);
        String safeRegion = (region != null && !region.isBlank())
                ? region
                : clusterRegion(cluster);

        String instanceId = deriveClusterNodeInstanceId(cluster, safeRegion, safeAccountId);
        String az = deriveClusterNodeAvailabilityZone(cluster, safeRegion);
        String privateDnsName = deriveClusterNodePrivateDnsName(cluster, safeRegion, safeAccountId);

        inst.setInstanceId(instanceId);
        inst.setImageId("ami-eks-k3s");
        inst.setInstanceType(nodeInstanceType(cluster));
        inst.setPlacement(new Placement(az));
        inst.setRegion(safeRegion);
        inst.setState(InstanceState.running());

        String ip = (cluster != null && cluster.getNodePrivateIp() != null && !cluster.getNodePrivateIp().isBlank())
                ? cluster.getNodePrivateIp()
                : ((containerIp != null && !containerIp.isBlank()) ? containerIp : "10.0.0.1");
        inst.setPrivateIpAddress(ip);
        inst.setPrivateDnsName(privateDnsName);

        // AWS EKS nodes receive credentials from a node IAM role through an EC2 instance profile,
        // never from the cluster control-plane role (cluster.getRoleArn()). Synthesize a distinct
        // node instance profile identity so /latest/meta-data/iam/info returns a valid profile ARN.
        String nodeProfileName = safeClusterName + "-node-profile";
        inst.setIamInstanceProfileArn(regionResolver.buildGlobalArn("iam", safeAccountId, "instance-profile/" + nodeProfileName));
        // IAM holds no such profile, so the node carries its own stable id, shaped like IAM's.
        inst.setIamInstanceProfileId("AIPA" + instanceId.substring(2).toUpperCase(Locale.ROOT));

        if (cluster.getResourcesVpcConfig() != null) {
            inst.setVpcId(cluster.getResourcesVpcConfig().getVpcId());
            if (cluster.getResourcesVpcConfig().getSubnetIds() != null && !cluster.getResourcesVpcConfig().getSubnetIds().isEmpty()) {
                inst.setSubnetId(cluster.getResourcesVpcConfig().getSubnetIds().getFirst());
            }
        }
        inst.setLaunchTime(cluster.getCreatedAt() != null ? cluster.getCreatedAt() : Instant.now());
        List<Tag> tags = new ArrayList<>();
        tags.add(new Tag("Name", safeClusterName + "-node"));
        tags.add(new Tag("kubernetes.io/cluster/" + safeClusterName, "owned"));
        tags.add(new Tag(CLUSTER_NAME_TAG, safeClusterName));
        inst.setTags(tags);
        addPrimaryNetworkInterface(inst, cluster, safeRegion, safeAccountId);
        return inst;
    }

    /**
     * The node's primary network interface, in the node's subnet at the address leased for it there. An EKS node's
     * interface carries the cluster security group, where the AWS Load Balancer Controller opens a target's ports, so
     * the node instance and its interface hold it. A node outside a subnet has no interface, and neither has one
     * without a subnet address, whose own address is then its container's on a network that is not the subnet's.
     */
    private void addPrimaryNetworkInterface(Instance inst, Cluster cluster, String region, String accountId) {
        String leased = cluster.getNodePrivateIp();
        if (inst.getVpcId() == null || inst.getSubnetId() == null || leased == null || leased.isBlank()) {
            return;
        }
        List<GroupIdentifier> groups = clusterSecurityGroup(cluster, region, accountId).map(List::of).orElse(List.of());
        inst.setSecurityGroups(new ArrayList<>(groups));
        InstanceNetworkInterface eni = new InstanceNetworkInterface();
        eni.setNetworkInterfaceId(deriveClusterNodeNetworkInterfaceId(cluster, region, accountId));
        eni.setSubnetId(inst.getSubnetId());
        eni.setVpcId(inst.getVpcId());
        eni.setOwnerId(accountId);
        eni.setPrivateIpAddress(inst.getPrivateIpAddress());
        eni.setPrivateDnsName(inst.getPrivateDnsName());
        eni.setGroups(new ArrayList<>(groups));
        if (cluster.getNodePodPrefixes() != null) {
            eni.setIpv4Prefixes(new ArrayList<>(cluster.getNodePodPrefixes()));
        }
        eni.setAttachmentId(deriveClusterNodeInterfaceAttachmentId(cluster, region, accountId));
        eni.setDeviceIndex(0);
        inst.getNetworkInterfaces().add(eni);
    }

    /** The cluster security group, named as EC2 holds it, or empty when the cluster has none EC2 holds. */
    private Optional<GroupIdentifier> clusterSecurityGroup(Cluster cluster, String region, String accountId) {
        String groupId = cluster.getResourcesVpcConfig() == null ? null
                : cluster.getResourcesVpcConfig().getClusterSecurityGroupId();
        Ec2Service ec2 = ec2Service();
        if (groupId == null || groupId.isBlank() || ec2 == null) {
            return Optional.empty();
        }
        return ec2.securityGroupName(accountId, region, groupId).map(name -> new GroupIdentifier(groupId, name));
    }

    @Override
    public Optional<Instance> findInstance(String accountId, String region, String instanceId) {
        if (accountId == null || accountId.isBlank() || instanceId == null || instanceId.isBlank()) {
            return Optional.empty();
        }
        return clusterNodeInstances.values().stream()
                .filter(rec -> accountId.equals(rec.accountId()))
                .filter(rec -> region == null || region.equals(rec.region()))
                .map(ClusterNodeRecord::instance)
                .filter(i -> instanceId.equals(i.getInstanceId()))
                .findFirst();
    }

    @Override
    public List<Instance> listInstances(String accountId, String region) {
        if (accountId == null || accountId.isBlank()) {
            return List.of();
        }
        return clusterNodeInstances.values().stream()
                .filter(rec -> accountId.equals(rec.accountId()))
                .filter(rec -> region == null || region.equals(rec.region()))
                .map(ClusterNodeRecord::instance)
                .toList();
    }

    private String resolveInstanceRegion(Instance instance, String region) {
        if (instance != null && instance.getRegion() != null && !instance.getRegion().isBlank()) {
            return instance.getRegion();
        }
        return (region != null && !region.isBlank()) ? region
                : (regionResolver != null && regionResolver.getDefaultRegion() != null
                ? regionResolver.getDefaultRegion() : "us-east-1"); // partition-literal: fallback for instance region
    }

    String deriveInstanceNodeProviderId(Instance instance, String region) {
        String safeRegion = resolveInstanceRegion(instance, region);
        String az = (instance != null && instance.getPlacement() != null
                && instance.getPlacement().getAvailabilityZone() != null)
                ? instance.getPlacement().getAvailabilityZone() : safeRegion + "a";
        return "aws:///" + az + "/" + (instance != null && instance.getInstanceId() != null ? instance.getInstanceId() : "i-00000000000000000");
    }

    String deriveInstanceNodeName(Instance instance, String region) {
        if (instance != null && instance.getPrivateDnsName() != null && !instance.getPrivateDnsName().isBlank()) {
            return instance.getPrivateDnsName();
        }
        String id = instance != null && instance.getInstanceId() != null ? instance.getInstanceId() : "i-00000000000000000";
        return id + "." + deriveClusterNodePrivateDnsDomain(resolveInstanceRegion(instance, region));
    }

    void joinInstance(Cluster cluster, Instance instance) {
        if (cluster == null || instance == null) {
            return;
        }
        String clusterKey = clusterResourceName(cluster);
        synchronized (joinedClusterInstances) {
            for (Map.Entry<String, Map<String, Instance>> entry : joinedClusterInstances.entrySet()) {
                if (!entry.getKey().equals(clusterKey) && entry.getValue().containsKey(instance.getInstanceId())) {
                    throw new AwsException("InvalidParameterException", "Instance " + instance.getInstanceId()
                            + " is already joined to cluster " + entry.getKey(), 400);
                }
            }
            Map<String, Instance> current = joinedClusterInstances.get(clusterKey);
            if (current != null && current.containsKey(instance.getInstanceId())) {
                return;
            }
        }

        String containerId = cluster.getContainerId();
        // A node whose API has not answered yet is registered by finalizeCluster, as one with no container is.
        if (containerId != null && !containerId.isBlank() && guestReachable(containerId)) {
            try {
                registerNodeInCluster(cluster, containerId, instance);
            } catch (Exception e) {
                String region = instance.getRegion() != null ? instance.getRegion() : clusterRegion(cluster);
                String nodeName = deriveInstanceNodeName(instance, region);
                try {
                    execInContainerForResult(containerId,
                            new String[]{"kubectl", "delete", "node", nodeName, "--ignore-not-found=true"}, 15);
                } catch (Exception ignored) {
                    // Node cleanup best effort on join failure
                }
                throw e;
            }
        }

        synchronized (joinedClusterInstances) {
            joinedClusterInstances
                    .computeIfAbsent(clusterKey, k -> new ConcurrentHashMap<>())
                    .put(instance.getInstanceId(), instance);
        }
        recordJoinedInstanceId(cluster, instance);

        for (Consumer<Instance> listener : nodeRegistrationListeners) {
            try {
                listener.accept(instance);
            } catch (Exception e) {
                LOG.warnv("Node registration listener failed for joined instance {0} on cluster {1}: {2}",
                        instance.getInstanceId(), cluster.getName(), e.getMessage());
            }
        }
    }

    /**
     * Takes a joined instance out of the cluster before it is terminated: its node is deleted and it is no longer
     * joined. The cluster's record of it is pruned by the next restore, which finds it terminated. A node that
     * can't be deleted throws and leaves the instance joined, so the caller keeps it and can try again.
     */
    void removeJoinedNode(Cluster cluster, Instance instance) {
        String region = instance.getRegion() != null ? instance.getRegion() : clusterRegion(cluster);
        String containerId = cluster.getContainerId();
        if (containerId != null && !containerId.isBlank()) {
            String nodeName = deriveInstanceNodeName(instance, region);
            ContainerExec.Result result = execInContainerForResult(containerId, new String[]{"kubectl", "delete",
                    "node", nodeName, "--ignore-not-found=true", "--wait=false"}, 15);
            if (result.exitCode() != 0) {
                throw new IllegalStateException("kubectl delete node " + nodeName + " failed: " + result.summary());
            }
        }
        Map<String, Instance> joined = joinedClusterInstances.get(clusterResourceName(cluster));
        if (joined != null) {
            joined.remove(instance.getInstanceId());
        }
    }

    void recordJoinedInstanceId(Cluster cluster, Instance instance) {
        if (cluster == null || instance == null) {
            return;
        }
        cluster.recordJoinedInstance(instance.getInstanceId(),
                instance.getRegion() != null ? instance.getRegion() : clusterRegion(cluster));
    }

    List<Instance> getJoinedInstances(Cluster cluster) {
        if (cluster == null) {
            return List.of();
        }
        Map<String, Instance> map = joinedClusterInstances.get(clusterResourceName(cluster));
        return map != null ? new ArrayList<>(map.values()) : List.of();
    }

    boolean isInstanceJoined(Cluster cluster, String instanceId) {
        if (cluster == null || instanceId == null) {
            return false;
        }
        Map<String, Instance> map = joinedClusterInstances.get(clusterResourceName(cluster));
        return map != null && map.containsKey(instanceId);
    }

    boolean isJoinedNode(Cluster cluster, String nodeName) {
        if (cluster == null || nodeName == null) {
            return false;
        }
        Map<String, Instance> map = joinedClusterInstances.get(clusterResourceName(cluster));
        if (map == null) {
            return false;
        }
        for (Instance inst : map.values()) {
            if (nodeName.equals(deriveInstanceNodeName(inst, inst.getRegion()))) {
                return true;
            }
        }
        return false;
    }

    /**
     * Registers every joined instance as a node. One that fails is dropped from the joined set, so the readiness
     * poller finds it still to join and joins it again, and the others are registered regardless.
     */
    void registerAllJoinedNodes(Cluster cluster, String containerId) {
        Map<String, Instance> map = joinedClusterInstances.get(clusterResourceName(cluster));
        if (map == null) {
            return;
        }
        for (Instance inst : List.copyOf(map.values())) {
            try {
                registerNodeInCluster(cluster, containerId, inst);
            } catch (RuntimeException e) {
                map.remove(inst.getInstanceId(), inst);
                LOG.warnv("Could not register joined instance {0} as a node of EKS cluster {1}, "
                        + "joining it again later: {2}", inst.getInstanceId(), cluster.getName(), e.getMessage());
            }
        }
    }

    private void registerNodeInCluster(Cluster cluster, String containerId, Instance instance) {
        if (imageCatalog.findByIdOrAlias(instance.getImageId())
                .map(image -> Boolean.TRUE.equals(image.kubeletRegistersNode)).orElse(false)) {
            // Do not race the guest kubelet with a status-less Node or synthetic Ready status.
            return;
        }
        String region = instance.getRegion() != null ? instance.getRegion() : clusterRegion(cluster);
        String nodeName = deriveInstanceNodeName(instance, region);
        try {
            String az = instance.getPlacement() != null && instance.getPlacement().getAvailabilityZone() != null
                    ? instance.getPlacement().getAvailabilityZone() : region + "a";
            String providerId = deriveInstanceNodeProviderId(instance, region);
            String instanceType = instance.getInstanceType() != null ? instance.getInstanceType() : DEFAULT_NODE_INSTANCE_TYPE;
            String privateIp = instance.getPrivateIpAddress() != null ? instance.getPrivateIpAddress() : "10.0.0.1";

            CatalogInstanceType catType = instanceTypeCatalog.find(instanceType).orElse(null);
            String cpu = catType != null ? String.valueOf(catType.vcpu) : "2";
            String memory = catType != null ? catType.memoryMib + "Mi" : "4Gi";

            String rawArch = instance.getArchitecture();
            String k8sArch = (rawArch != null && (rawArch.toLowerCase(Locale.ROOT).contains("arm64")
                    || rawArch.toLowerCase(Locale.ROOT).contains("aarch64"))) ? "arm64" : "amd64";

            // As a kubelet registers: the labels and taints its NodeConfig's flags name, under the node's own labels.
            Map<String, String> labels = new LinkedHashMap<>(EksBootstrapUserData.nodeLabels(instance.getUserData()));
            labels.put("topology.kubernetes.io/zone", az);
            labels.put("topology.kubernetes.io/region", region);
            labels.put("node.kubernetes.io/instance-type", instanceType);
            labels.put("kubernetes.io/hostname", nodeName);
            labels.put("kubernetes.io/os", "linux");
            labels.put("kubernetes.io/arch", k8sArch);
            Map<String, Object> spec = new LinkedHashMap<>();
            spec.put("providerID", providerId);
            List<Map<String, String>> taints = EksBootstrapUserData.registerWithTaints(instance.getUserData());
            if (!taints.isEmpty()) {
                spec.put("taints", taints);
            }
            String nodeJson = NODE_MAPPER.writeValueAsString(Map.of("apiVersion", "v1", "kind", "Node",
                    "metadata", Map.of("name", nodeName, "labels", labels), "spec", spec));

            ContainerExec.Result applyRes = execInContainerForResult(containerId,
                    new String[]{"sh", "-c", "echo '" + nodeJson.replace("'", "'\\''") + "' | kubectl apply -f -"}, 15);
            if (applyRes.exitCode() != 0) {
                throw new IllegalStateException("Failed to apply node " + nodeName + " to cluster "
                        + cluster.getName() + ": " + applyRes.stderr());
            }

            String statusPatch = String.format(Locale.ROOT,
                    "{\"status\":{\"conditions\":[{\"type\":\"Ready\",\"status\":\"True\","
                    + "\"reason\":\"KubeletReady\",\"message\":\"kubelet is posting ready status\"}],"
                    + "\"addresses\":[{\"type\":\"InternalIP\",\"address\":\"%s\"},{\"type\":\"Hostname\",\"address\":\"%s\"}],"
                    + "\"capacity\":{\"cpu\":\"%s\",\"memory\":\"%s\",\"pods\":\"110\"},"
                    + "\"allocatable\":{\"cpu\":\"%s\",\"memory\":\"%s\",\"pods\":\"110\"}}}",
                    privateIp, nodeName, cpu, memory, cpu, memory);

            ContainerExec.Result patchRes = execInContainerForResult(containerId,
                    new String[]{"kubectl", "patch", "node", nodeName, "--subresource=status", "--type=merge",
                            "-p", statusPatch}, 15);
            if (patchRes.exitCode() != 0) {
                throw new IllegalStateException("Failed to patch status for node " + nodeName + " on cluster "
                        + cluster.getName() + ": " + patchRes.stderr());
            }

            LOG.infov("Registered joined instance node {0} ({1}) in cluster {2}",
                    nodeName, providerId, cluster.getName());
        } catch (Exception e) {
            LOG.warnv("Failed to register node for instance {0} in cluster {1}: {2}",
                    instance.getInstanceId(), cluster.getName(), e.getMessage());
            if (e instanceof RuntimeException re) {
                throw re;
            }
            throw new RuntimeException(e);
        }
    }

    /**
     * A node container's addresses: {@code primaryIp} is the one EC2 would pick for a container it runs
     * ({@link Ec2MetadataProxy#preferredMetadataSourceIp}), or null when inspecting the container found none.
     */
    record ContainerIps(String primaryIp, Set<String> allIps) {}

    /**
     * Records the container behind a cluster's node on the node's instance, as EC2 does for the containers it runs:
     * the container, and the address Floci reaches it at, which an ELB instance target or health check naming the
     * node connects to. That address is the node's private IP when its container holds it, since AWS sends an
     * instance target's traffic to the instance's primary private IP, and otherwise the container's primary
     * address. No address is recorded when inspecting the container found none.
     */
    static void recordNodeContainer(Instance nodeInstance, String containerId, ContainerIps containerIps) {
        nodeInstance.setDockerContainerId(containerId);
        String privateIp = nodeInstance.getPrivateIpAddress();
        nodeInstance.setContainerBridgeIp(containerIps.allIps().contains(privateIp)
                ? privateIp : containerIps.primaryIp());
    }

    ContainerIps resolveContainerIps(String containerId) {
        Set<String> ips = new LinkedHashSet<>();
        String preferred = null;
        try {
            InspectContainerResponse inspect = lifecycleManager.getDockerClient().inspectContainerCmd(containerId).exec();
            if (inspect.getNetworkSettings() != null) {
                Map<String, ContainerNetwork> networks = inspect.getNetworkSettings().getNetworks();
                if (networks != null) {
                    preferred = Ec2MetadataProxy.preferredMetadataSourceIp(networks).orElse(null);
                    for (ContainerNetwork network : networks.values()) {
                        if (network != null && network.getIpAddress() != null && !network.getIpAddress().isBlank()) {
                            ips.add(network.getIpAddress());
                        }
                    }
                }
                String ip = inspect.getNetworkSettings().getIpAddress();
                if (ip != null && !ip.isBlank()) {
                    ips.add(ip);
                    if (preferred == null) {
                        preferred = ip;
                    }
                }
            }
        } catch (Exception e) {
            LOG.warnv("Could not inspect container {0} for IPs: {1}", containerId, e.getMessage());
        }
        return new ContainerIps(preferred, ips);
    }

    Instance getRegisteredClusterNodeInstance(Cluster cluster) {
        ClusterNodeRecord record = clusterNodeInstances.get(clusterResourceName(cluster));
        return record != null ? record.instance() : null;
    }

    ContainerExec.Result execInContainerForResult(String containerId, String[] cmd, int timeoutSeconds) {
        return ContainerExec.runMerged(lifecycleManager.getDockerClient(), containerId, cmd, timeoutSeconds);
    }

    /** {@link #execInContainerForResult} keeping stderr out of stdout, for output that is parsed. */
    ContainerExec.Result execInContainerSeparatingStderr(String containerId, String[] cmd, int timeoutSeconds) {
        return ContainerExec.run(lifecycleManager.getDockerClient(), containerId, cmd, timeoutSeconds);
    }

    /**
     * Executes launch template UserData script(s) inside the running cluster container.
     *
     * @param cluster the cluster whose container will execute the user data
     * @param nodegroupName the name of the nodegroup requesting execution
     * @param userData raw UserData payload from the launch template
     * @return result indicating success, failure, or skipped
     */
    public UserDataPipeline.ExecutionResult executeUserData(
            Cluster cluster,
            String nodegroupName,
            String userData) {
        if (cluster == null || cluster.getContainerId() == null || cluster.getContainerId().isBlank()) {
            return UserDataPipeline.ExecutionResult.skipped("Cluster has no running container");
        }
        DockerClient dockerClient = lifecycleManager.getDockerClient();
        if (dockerClient == null) {
            return UserDataPipeline.ExecutionResult.skipped("No Docker daemon reachable");
        }
        String context = "EKS cluster " + cluster.getName() + " (nodegroup " + nodegroupName + ")";
        SetUpNode node = awaitSetUpNode(cluster);
        if (node.failure() != null) {
            return node.failure();
        }
        return UserDataPipeline.executeUserData(
                dockerClient,
                lifecycleManager.getStreamingDockerClient(),
                node.containerId(),
                context,
                userData,
                Duration.ofMinutes(30),
                null,
                null
        );
    }


    /**
     * Waits for the node's guest setup before its user data runs: user data executes in the container, and it may
     * read the metadata endpoint the setup configures. A node replaced meanwhile, as a node capacity swap does, is
     * waited for in turn, so the user data runs in the node that is there once it is set up.
     */
    SetUpNode awaitSetUpNode(Cluster cluster) {
        long deadline = System.nanoTime() + guestSetupTimeout.toNanos();
        while (true) {
            String containerId = cluster.getContainerId();
            if (containerId == null || containerId.isBlank()) {
                return new SetUpNode(null, UserDataPipeline.ExecutionResult.error("The node of EKS cluster "
                        + cluster.getName() + " is gone, so the user data did not run"));
            }
            GuestSetup setup = pendingGuestSetups.get(containerId);
            if (setup != null) {
                try {
                    setup.done.get(Math.max(0, deadline - System.nanoTime()), TimeUnit.NANOSECONDS);
                } catch (TimeoutException e) {
                    return new SetUpNode(null, UserDataPipeline.ExecutionResult.error("The Kubernetes API server of "
                            + "EKS cluster " + cluster.getName() + " did not answer within "
                            + guestSetupTimeout.toMinutes() + " minutes, so its node was not set up and the user data"
                            + " did not run"));
                } catch (ExecutionException e) {
                    // Abandoned for a replacement, or for this container's own restart: wait for that one instead.
                    if (containerId.equals(cluster.getContainerId()) && !pendingGuestSetups.containsKey(containerId)) {
                        return new SetUpNode(null, UserDataPipeline.ExecutionResult.error("The node of EKS cluster "
                                + cluster.getName() + " was not set up because " + e.getCause().getMessage()
                                + ", so the user data did not run"));
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return new SetUpNode(null, UserDataPipeline.ExecutionResult.error("Interrupted while waiting for "
                            + "the node of EKS cluster " + cluster.getName() + " to be set up"));
                }
            }
            if (containerId.equals(cluster.getContainerId()) && !pendingGuestSetups.containsKey(containerId)) {
                return new SetUpNode(containerId, null);
            }
        }
    }

    private String execInContainer(String containerId, String[] cmd) throws Exception {
        return execInContainerForResult(containerId, cmd, 10).throwIfTimedOut(containerId).stdout();
    }

    private String extractYamlField(String yaml, String fieldName) {
        for (String line : yaml.split("\n")) {
            String trimmed = line.trim();
            if (trimmed.startsWith(fieldName + ":")) {
                return trimmed.substring(fieldName.length() + 1).trim();
            }
        }
        return null;
    }

    @SuppressWarnings("java:S4830")
    private void disableSslVerification(javax.net.ssl.HttpsURLConnection conn) {
        try {
            javax.net.ssl.TrustManager[] trustAll = new javax.net.ssl.TrustManager[]{
                new javax.net.ssl.X509TrustManager() {
                    public java.security.cert.X509Certificate[] getAcceptedIssuers() { return null; }
                    public void checkClientTrusted(java.security.cert.X509Certificate[] c, String a) {}
                    public void checkServerTrusted(java.security.cert.X509Certificate[] c, String a) {}
                }
            };
            javax.net.ssl.SSLContext sc = javax.net.ssl.SSLContext.getInstance("TLS");
            sc.init(null, trustAll, new java.security.SecureRandom());
            conn.setSSLSocketFactory(sc.getSocketFactory());
            conn.setHostnameVerifier((h, s) -> true);
        } catch (Exception e) {
            LOG.debugv("Could not disable SSL verification: {0}", e.getMessage());
        }
    }
}
