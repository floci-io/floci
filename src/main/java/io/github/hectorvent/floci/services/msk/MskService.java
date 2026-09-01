package io.github.hectorvent.floci.services.msk;

import io.github.hectorvent.floci.config.EmulatorConfig;
import io.github.hectorvent.floci.core.common.AwsArnUtils;
import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.core.common.Pagination;
import io.github.hectorvent.floci.core.common.PaginatedResult;
import io.github.hectorvent.floci.core.common.RegionResolver;
import io.github.hectorvent.floci.core.resource.ExplorerResource;
import io.github.hectorvent.floci.core.resource.ResourceProvider;
import io.github.hectorvent.floci.core.resource.SupportedResourceType;
import io.github.hectorvent.floci.core.storage.AccountAwareStorageBackend;
import io.github.hectorvent.floci.core.storage.StorageBackend;
import io.github.hectorvent.floci.core.storage.StorageFactory;
import io.github.hectorvent.floci.services.msk.model.BrokerNodeGroupInfo;
import io.github.hectorvent.floci.services.msk.model.ClusterState;
import io.github.hectorvent.floci.services.msk.model.ConfigurationRevision;
import io.github.hectorvent.floci.services.msk.model.ConfigurationRevisionDetail;
import io.github.hectorvent.floci.services.msk.model.ConfigurationInfo;
import io.github.hectorvent.floci.services.msk.model.ConfigurationState;
import io.github.hectorvent.floci.services.msk.model.CreateClusterRequest;
import io.github.hectorvent.floci.services.msk.model.CreateClusterV2Request;
import io.github.hectorvent.floci.services.msk.model.EncryptionInTransit;
import io.github.hectorvent.floci.services.msk.model.EncryptionInfo;
import io.github.hectorvent.floci.services.msk.model.MskCluster;
import io.github.hectorvent.floci.services.msk.model.MskConfiguration;
import com.fasterxml.jackson.core.type.TypeReference;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.security.SecureRandom;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

@ApplicationScoped
public class MskService implements ResourceProvider {

    private static final Logger LOG = Logger.getLogger(MskService.class);
    private static final String DEFAULT_KAFKA_VERSION = "3.6.0";
    private static final String DEFAULT_ENHANCED_MONITORING = "DEFAULT";
    private static final String DEFAULT_CLIENT_BROKER_ENCRYPTION = "TLS_PLAINTEXT";
    private static final String PROVISIONED_CLUSTER_TYPE = "PROVISIONED";
    private static final String SERVERLESS_CLUSTER_TYPE = "SERVERLESS";
    private static final int MAX_PAGE = 100;
    private static final int MAX_CLUSTER_NAME_LENGTH = 64;
    // Floor only, deliberately no ceiling. The SDK/CLI reference carries an __integerMin1Max15
    // shape for numberOfBrokerNodes, but the REST API reference documents no minimum or maximum
    // for it - on a page that does give ranges for its neighbours (volumeSize 1-16384,
    // revision min 1) - and the quota page allows 30 brokers per ZooKeeper cluster and 60 per
    // KRaft cluster, both adjustable upward. Capping at 15 would reject clusters real MSK
    // accepts and turn a converging-on-the-wrong-number bug into an outright apply failure for
    // every count from 16 up. An emulator that accepts a little more than AWS costs nobody
    // anything; one that rejects a valid request blocks real work.
    private static final int MIN_BROKER_NODES = 1;
    private static final int MIN_EBS_VOLUME_SIZE = 1;
    private static final int MAX_EBS_VOLUME_SIZE = 16384;
    private static final Set<String> ENHANCED_MONITORING_VALUES =
            Set.of("DEFAULT", "PER_BROKER", "PER_TOPIC_PER_BROKER", "PER_TOPIC_PER_PARTITION");
    private static final Set<String> STORAGE_MODE_VALUES = Set.of("LOCAL", "TIERED");
    private static final Set<String> CLIENT_BROKER_VALUES = Set.of("TLS", "TLS_PLAINTEXT", "PLAINTEXT");
    private static final Set<String> REBALANCING_STATUS_VALUES = Set.of("PAUSED", "ACTIVE");
    private final StorageBackend<String, MskCluster> storage;
    private final StorageBackend<String, MskConfiguration> configurationStorage;
    private final EmulatorConfig config;
    private final RegionResolver regionResolver;
    private final RedpandaManager redpandaManager;
    private final ScheduledExecutorService poller = Executors.newSingleThreadScheduledExecutor();
    private final Object configurationUpdateLock = new Object();

    @Inject
    public MskService(StorageFactory storageFactory, EmulatorConfig config,
                      RegionResolver regionResolver, RedpandaManager redpandaManager) {
        this.storage = storageFactory.create("msk", "msk-clusters.json", new TypeReference<Map<String, MskCluster>>() {});
        this.configurationStorage = storageFactory.create("msk", "msk-configurations.json",
                new TypeReference<Map<String, MskConfiguration>>() {});
        this.config = config;
        this.regionResolver = regionResolver;
        this.redpandaManager = redpandaManager;
    }

    @PostConstruct
    public void init() {
        startReadinessPoller();
    }

    @PreDestroy
    public void shutdown() {
        poller.shutdown();
        if (!config.services().msk().mock()) {
            for (MskCluster cluster : allClusters()) {
                redpandaManager.stopContainer(cluster);
            }
        }
    }

    public MskCluster createCluster(String clusterName) {
        return createCluster(clusterName, DEFAULT_KAFKA_VERSION);
    }

    public MskCluster createCluster(String clusterName, String kafkaVersion) {
        CreateClusterRequest request = new CreateClusterRequest();
        request.setClusterName(clusterName);
        request.setKafkaVersion(kafkaVersion);
        return createCluster(request);
    }

    public MskCluster createCluster(CreateClusterRequest request) {
        validateCreateRequest(request);
        String clusterName = request.getClusterName();
        if (storage.scan(k -> true).stream().anyMatch(c -> c.getClusterName().equals(clusterName))) {
            throw new AwsException("ConflictException", "Cluster already exists: " + clusterName, 409);
        }

        String accountId = regionResolver.getAccountId();
        String clusterArn = AwsArnUtils.Arn.of("kafka", config.defaultRegion(), accountId, "cluster/" + clusterName + "/" + java.util.UUID.randomUUID()).toString();

        String kafkaVersion = request.getKafkaVersion();
        String resolvedKafkaVersion = (kafkaVersion == null || kafkaVersion.isBlank()) ? DEFAULT_KAFKA_VERSION : kafkaVersion;
        MskCluster cluster = new MskCluster(clusterArn, clusterName, resolvedKafkaVersion);
        cluster.setClusterType(PROVISIONED_CLUSTER_TYPE);
        cluster.setAccountId(accountId);
        cluster.setVolumeId(String.format("%06x", new SecureRandom().nextInt(0xFFFFFF)));

        if (request.getNumberOfBrokerNodes() != null) {
            cluster.setNumberOfBrokerNodes(request.getNumberOfBrokerNodes());
        }
        cluster.setTags(request.getTags());
        cluster.setBrokerNodeGroupInfo(request.getBrokerNodeGroupInfo());
        cluster.setClientAuthentication(request.getClientAuthentication());
        cluster.setLoggingInfo(request.getLoggingInfo());
        cluster.setOpenMonitoring(request.getOpenMonitoring());
        cluster.setStorageMode(request.getStorageMode());
        cluster.setRebalancing(request.getRebalancing());

        // AWS applies these server-side when the member is absent, and echoes the resolved
        // value back on Describe. Leaving them unset instead is what keeps a terraform plan
        // permanently dirty: the provider's schema defaults them, so a null in the response
        // reads as a drift against config the user never wrote.
        cluster.setEnhancedMonitoring(request.getEnhancedMonitoring() != null
                ? request.getEnhancedMonitoring() : DEFAULT_ENHANCED_MONITORING);
        cluster.setEncryptionInfo(withEncryptionDefaults(request.getEncryptionInfo()));

        // configurationInfo is a request-only member: the response carries the configuration
        // on currentBrokerSoftwareInfo instead (see BrokerSoftwareInfo).
        ConfigurationInfo configurationInfo = request.getConfigurationInfo();
        if (configurationInfo != null) {
            cluster.getCurrentBrokerSoftwareInfo().setConfigurationArn(configurationInfo.getArn());
            cluster.getCurrentBrokerSoftwareInfo().setConfigurationRevision(configurationInfo.getRevision());
        }

        if (config.services().msk().mock()) {
            cluster.setBootstrapBrokers("localhost:9092");
        } else if (!redpandaManager.tryStartContainer(cluster)) {
            LOG.warnv("MSK cluster {0} created without a backing Kafka broker: no Docker daemon "
                    + "is reachable. Metadata operations work; producing and consuming do not "
                    + "until a daemon appears.", clusterName);
            cluster.setBootstrapBrokers("localhost:9092");
        }
        // A cluster record is metadata, and SDK/Terraform waiters poll DescribeCluster for
        // ACTIVE, so the cluster reports its terminal state from the first read (same pattern
        // as MediaLive multiplexes) instead of gating on backing-broker readiness.
        cluster.setState(ClusterState.ACTIVE);

        storage.put(clusterArn, cluster);
        return cluster;
    }

    public MskCluster createCluster(CreateClusterV2Request request) {
        // A CreateClusterV2 request carries exactly one of provisioned/serverless. A serverless
        // request used to be flattened into a provisioned cluster, which then reported itself as
        // provisioned and echoed broker metadata the caller never asked for.
        if (request.getServerless() != null) {
            if (request.getProvisioned() != null) {
                throw badRequest("serverless",
                        "Exactly one of provisioned and serverless must be specified.");
            }
            return createServerlessCluster(request);
        }

        CreateClusterRequest merged = new CreateClusterRequest();
        merged.setClusterName(request.getClusterName());
        merged.setTags(request.getTags());
        if (request.getProvisioned() != null) {
            merged.setKafkaVersion(request.getProvisioned().getKafkaVersion());
            // A malformed numberOfBrokerNodes carries no int value to copy (see BrokerCount),
            // so its "malformed" state has to be propagated explicitly rather than lost when
            // getNumberOfBrokerNodes() returns null the same way "absent" would.
            if (request.getProvisioned().isNumberOfBrokerNodesMalformed()) {
                merged.markNumberOfBrokerNodesMalformed();
            } else {
                merged.setNumberOfBrokerNodes(request.getProvisioned().getNumberOfBrokerNodes());
            }
            merged.setBrokerNodeGroupInfo(request.getProvisioned().getBrokerNodeGroupInfo());
            merged.setEncryptionInfo(request.getProvisioned().getEncryptionInfo());
            merged.setClientAuthentication(request.getProvisioned().getClientAuthentication());
            merged.setEnhancedMonitoring(request.getProvisioned().getEnhancedMonitoring());
            merged.setLoggingInfo(request.getProvisioned().getLoggingInfo());
            merged.setConfigurationInfo(request.getProvisioned().getConfigurationInfo());
            merged.setOpenMonitoring(request.getProvisioned().getOpenMonitoring());
            merged.setStorageMode(request.getProvisioned().getStorageMode());
            merged.setRebalancing(request.getProvisioned().getRebalancing());
        }
        return createCluster(merged);
    }

    /**
     * Rejects the CreateCluster input AWS rejects.
     *
     * <p>Scoped to the members AWS documents an actual constraint for, plus {@code clusterName},
     * whose absence used to build an ARN containing {@code cluster/null/<uuid>} and store a
     * cluster that could never be addressed by name.
     *
     * <p>Deliberately NOT enforced: the *presence* of {@code kafkaVersion},
     * {@code numberOfBrokerNodes} and {@code brokerNodeGroupInfo}, which real MSK requires.
     * The emulator has always defaulted those, and tightening it would reject minimal fixtures
     * across the suite for no emulation benefit - a separate, deliberate break rather than
     * something to slip into a metadata round-trip fix.
     */
    private void validateCreateRequest(CreateClusterRequest request) {
        String clusterName = request.getClusterName();
        if (clusterName == null || clusterName.isBlank()) {
            throw badRequest("clusterName", "clusterName is required.");
        }
        if (clusterName.length() > MAX_CLUSTER_NAME_LENGTH) {
            throw badRequest("clusterName",
                    "clusterName must be between 1 and " + MAX_CLUSTER_NAME_LENGTH + " characters.");
        }

        // A fractional or otherwise non-integral numberOfBrokerNodes (e.g. 2.7, or a literal
        // like 1.0000000000000001 that only looks whole once collapsed into a double) is
        // malformed rather than something to round - see BrokerCountDeserializer/BrokerCount.
        if (request.isNumberOfBrokerNodesMalformed()) {
            throw badRequest("numberOfBrokerNodes", "numberOfBrokerNodes must be a whole number.");
        }

        Integer brokerNodes = request.getNumberOfBrokerNodes();
        if (brokerNodes != null && brokerNodes < MIN_BROKER_NODES) {
            throw badRequest("numberOfBrokerNodes",
                    "numberOfBrokerNodes must be at least " + MIN_BROKER_NODES + ".");
        }

        BrokerNodeGroupInfo nodeGroup = request.getBrokerNodeGroupInfo();
        if (nodeGroup != null && nodeGroup.getStorageInfo() != null
                && nodeGroup.getStorageInfo().getEbsStorageInfo() != null) {
            Integer volumeSize = nodeGroup.getStorageInfo().getEbsStorageInfo().getVolumeSize();
            if (volumeSize != null && (volumeSize < MIN_EBS_VOLUME_SIZE || volumeSize > MAX_EBS_VOLUME_SIZE)) {
                throw badRequest("volumeSize",
                        "volumeSize must be between " + MIN_EBS_VOLUME_SIZE + " and " + MAX_EBS_VOLUME_SIZE + ".");
            }
        }

        ConfigurationInfo configurationInfo = request.getConfigurationInfo();
        if (configurationInfo != null && configurationInfo.getRevision() != null
                && configurationInfo.getRevision() < 1) {
            throw badRequest("configurationInfo.revision", "configurationInfo.revision must be at least 1.");
        }

        validateEnum("enhancedMonitoring", request.getEnhancedMonitoring(), ENHANCED_MONITORING_VALUES);
        validateEnum("storageMode", request.getStorageMode(), STORAGE_MODE_VALUES);
        if (request.getEncryptionInfo() != null && request.getEncryptionInfo().getEncryptionInTransit() != null) {
            validateEnum("encryptionInfo.encryptionInTransit.clientBroker",
                    request.getEncryptionInfo().getEncryptionInTransit().getClientBroker(), CLIENT_BROKER_VALUES);
        }
        if (request.getRebalancing() != null) {
            validateEnum("rebalancing.status", request.getRebalancing().getStatus(), REBALANCING_STATUS_VALUES);
        }
    }

    private void validateEnum(String field, String value, Set<String> allowed) {
        if (value != null && !allowed.contains(value)) {
            throw badRequest(field,
                    field + " must be one of " + String.join(", ", new java.util.TreeSet<>(allowed)) + ".");
        }
    }

    // MSK's Error schema is two members - message and invalidParameter, "the parameter that
    // caused the error" - so a validation failure names the member it rejected.
    private AwsException badRequest(String invalidParameter, String message) {
        return new AwsException("BadRequestException", message, 400,
                Map.of("invalidParameter", invalidParameter));
    }

    /**
     * Fills in the encryption-in-transit defaults AWS applies when CreateCluster omits them:
     * {@code clientBroker} defaults to TLS_PLAINTEXT and {@code inCluster} to true. An
     * explicitly supplied value is never overwritten. {@code encryptionAtRest} is left alone -
     * real MSK creates a KMS key for you there, which the emulator has no equivalent of, so
     * inventing an ARN would be worse than omitting the member.
     */
    private EncryptionInfo withEncryptionDefaults(EncryptionInfo requested) {
        EncryptionInfo encryptionInfo = requested != null ? requested : new EncryptionInfo();
        EncryptionInTransit inTransit = encryptionInfo.getEncryptionInTransit();
        if (inTransit == null) {
            inTransit = new EncryptionInTransit();
            encryptionInfo.setEncryptionInTransit(inTransit);
        }
        if (inTransit.getClientBroker() == null) {
            inTransit.setClientBroker(DEFAULT_CLIENT_BROKER_ENCRYPTION);
        }
        if (inTransit.getInCluster() == null) {
            inTransit.setInCluster(true);
        }
        return encryptionInfo;
    }

    /**
     * Serverless clusters have no broker node group, instance type or broker count to speak of -
     * AWS manages that - so none of the provisioned metadata is stored or echoed for them. The
     * emulated Kafka endpoint behind the cluster is the same either way.
     */
    private MskCluster createServerlessCluster(CreateClusterV2Request request) {
        String clusterName = request.getClusterName();
        if (clusterName == null || clusterName.isBlank()) {
            throw badRequest("clusterName", "clusterName is required.");
        }
        if (clusterName.length() > MAX_CLUSTER_NAME_LENGTH) {
            throw badRequest("clusterName",
                    "clusterName must be between 1 and " + MAX_CLUSTER_NAME_LENGTH + " characters.");
        }
        if (storage.scan(k -> true).stream().anyMatch(c -> c.getClusterName().equals(clusterName))) {
            throw new AwsException("ConflictException", "Cluster already exists: " + clusterName, 409);
        }

        String accountId = regionResolver.getAccountId();
        String clusterArn = AwsArnUtils.Arn.of("kafka", config.defaultRegion(), accountId,
                "cluster/" + clusterName + "/" + UUID.randomUUID()).toString();

        MskCluster cluster = new MskCluster(clusterArn, clusterName, DEFAULT_KAFKA_VERSION);
        cluster.setClusterType(SERVERLESS_CLUSTER_TYPE);
        cluster.setServerless(request.getServerless());
        cluster.setTags(request.getTags());
        cluster.setAccountId(accountId);
        cluster.setVolumeId(String.format("%06x", new SecureRandom().nextInt(0xFFFFFF)));

        // Provisioned-only members must not surface on a serverless cluster.
        cluster.setNumberOfBrokerNodes(0);
        cluster.setZookeeperConnectString(null);
        cluster.setCurrentBrokerSoftwareInfo(null);

        if (config.services().msk().mock()) {
            cluster.setState(ClusterState.ACTIVE);
            cluster.setBootstrapBrokers("localhost:9092");
        } else {
            redpandaManager.startContainer(cluster);
        }

        storage.put(clusterArn, cluster);
        return cluster;
    }

    public boolean isServerless(MskCluster cluster) {
        return SERVERLESS_CLUSTER_TYPE.equals(cluster.getClusterType());
    }

    /**
     * DescribeCluster for the v1 API, which predates serverless: its ClusterInfo has no way to
     * represent one, and real MSK answers a v1 describe of a serverless cluster with a
     * BadRequestException pointing the caller at DescribeClusterV2.
     */
    public MskCluster describeClusterV1(String clusterArn) {
        MskCluster cluster = describeCluster(clusterArn);
        if (isServerless(cluster)) {
            throw new AwsException("BadRequestException",
                    "This operation cannot be performed on serverless clusters. Use DescribeClusterV2 instead.", 400);
        }
        return cluster;
    }

    /** ListClusters for the v1 API, which likewise cannot represent serverless clusters. */
    public List<MskCluster> listProvisionedClusters() {
        return storage.scan(k -> true).stream().filter(c -> !isServerless(c)).toList();
    }

    public MskCluster describeCluster(String clusterArn) {
        return storage.get(clusterArn)
                .orElseThrow(() -> new AwsException("NotFoundException", "Cluster not found: " + clusterArn, 404));
    }

    public List<MskCluster> listClusters() {
        return storage.scan(k -> true);
    }

    public void deleteCluster(String clusterArn) {
        MskCluster cluster = storage.get(clusterArn)
                .orElseThrow(() -> new AwsException("NotFoundException", "Cluster not found: " + clusterArn, 404));

        cluster.setState(ClusterState.DELETING);
        if (!config.services().msk().mock()) {
            redpandaManager.stopContainer(cluster);
            redpandaManager.removeClusterStorage(cluster);
        }
        storage.delete(clusterArn);
    }

    public String getBootstrapBrokers(String clusterArn) {
        MskCluster cluster = describeCluster(clusterArn);
        return cluster.getBootstrapBrokers();
    }

    public MskConfiguration createConfiguration(String name, String description,
                                                 List<String> kafkaVersions, String serverProperties) {
        if (name == null || name.isBlank()) {
            throw new AwsException("BadRequestException", "name is required.", 400);
        }
        if (!name.matches("[0-9A-Za-z][0-9A-Za-z-]*")) {
            throw new AwsException("BadRequestException",
                    "Configuration name must match the pattern \"^[0-9A-Za-z][0-9A-Za-z-]{0,}$\".", 400);
        }
        // Only an absent member (null) is rejected. A zero-length blob is a legitimate value
        // meaning "no property overrides": Terraform modules that build serverProperties by
        // joining a map defaulting to {} send exactly that, and real MSK accepts it.
        if (serverProperties == null) {
            throw new AwsException("BadRequestException", "serverProperties is required.", 400);
        }
        if (configurationStorage.scan(k -> true).stream().anyMatch(c -> c.getName().equals(name))) {
            throw new AwsException("ConflictException", "Configuration already exists: " + name, 409);
        }

        String accountId = regionResolver.getAccountId();
        String arn = AwsArnUtils.Arn.of("kafka", config.defaultRegion(), accountId,
                "configuration/" + name + "/" + UUID.randomUUID()).toString();

        MskConfiguration configuration = new MskConfiguration(arn, name, description, kafkaVersions, serverProperties);
        configuration.setAccountId(accountId);

        configurationStorage.put(arn, configuration);
        LOG.infov("Created MSK configuration: {0}", name);
        return configuration;
    }

    public MskConfiguration describeConfiguration(String arn) {
        // Real MSK reports an unknown configuration ARN as BadRequestException (400), not
        // NotFoundException. The message prefix is a compatibility contract: terraform-provider-aws
        // (and the Pulumi provider bridging it) substring-matches "Configuration ARN does not exist"
        // to detect that a configuration is gone, so its post-delete waiter can complete.
        return configurationStorage.get(arn)
                .orElseThrow(() -> new AwsException("BadRequestException",
                        "Configuration ARN does not exist: " + arn, 400));
    }

    public PaginatedResult<MskConfiguration> listConfigurations(Integer maxResults, String nextToken) {
        List<MskConfiguration> all = configurationStorage.scan(k -> true);
        return Pagination.paginate(all, MskConfiguration::getArn, maxResults, nextToken, MAX_PAGE, "BadRequestException");
    }

    public MskConfiguration deleteConfiguration(String arn) {
        // Shares updateConfiguration's lock so the two can't interleave: without it, a delete
        // could remove the entry while an in-flight update still holds its pre-delete read,
        // then that update's own put(arn, ...) at the end of its critical section would put the
        // "deleted" configuration right back, resurrecting it.
        synchronized (configurationUpdateLock) {
            MskConfiguration configuration = describeConfiguration(arn);
            configuration.setState(ConfigurationState.DELETING);
            configurationStorage.delete(arn);
            LOG.infov("Deleted MSK configuration: {0}", configuration.getName());
            return configuration;
        }
    }

    public MskConfiguration updateConfiguration(String arn, String description, String serverProperties) {
        // Same absent-vs-empty distinction as createConfiguration: "" is a valid revision
        // body that clears every override, only a missing member is an error.
        if (serverProperties == null) {
            throw new AwsException("BadRequestException", "serverProperties is required.", 400);
        }

        // Read-modify-write on the shared configuration: two concurrent updates for the same
        // ARN could otherwise derive the same "next revision" number before either persists,
        // silently overwriting one request's serverProperties under the other's revision key.
        synchronized (configurationUpdateLock) {
            MskConfiguration configuration = describeConfiguration(arn);
            if (configuration.getState() != ConfigurationState.ACTIVE) {
                throw new AwsException("BadRequestException",
                        "Configuration must be ACTIVE to update: " + arn, 400);
            }
            ConfigurationRevision latestRevision = configuration.getLatestRevision();
            if (latestRevision == null) {
                // MskConfiguration now maps a pre-revision-history entry onto revision 1 as
                // it loads, so this no longer catches every configuration written before the
                // schema changed - only one with no revision data at all to build on (a
                // hand-edited store, or an entry whose latestRevision was explicitly null).
                throw new AwsException("BadRequestException",
                        "Configuration has no revision history and cannot be updated: " + arn, 400);
            }

            long newRevisionNumber = latestRevision.getRevision() + 1;
            configuration.addRevision(new ConfigurationRevision(newRevisionNumber, Instant.now(), description), serverProperties);

            configurationStorage.put(arn, configuration);
            LOG.infov("Updated MSK configuration {0} to revision {1}", configuration.getName(), newRevisionNumber);
            return configuration;
        }
    }

    public PaginatedResult<ConfigurationRevision> listConfigurationRevisions(String arn, Integer maxResults, String nextToken) {
        // Snapshot revisions under the same lock updateConfiguration writes under. Now that
        // MskConfiguration's collections are concurrent-safe this is no longer what prevents a
        // torn read - getRevisions() copies a CopyOnWriteArrayList, which cannot tear on its
        // own. It is kept so every reader of revision state serializes against an in-flight
        // update the same way, rather than leaving describeConfigurationRevision - which reads
        // both collections and does need them to agree - as the only one holding the lock.
        List<ConfigurationRevision> revisions;
        synchronized (configurationUpdateLock) {
            revisions = describeConfiguration(arn).getRevisions();
        }
        return Pagination.paginate(revisions,
                revision -> String.format("%019d", revision.getRevision()),
                maxResults, nextToken, MAX_PAGE, "BadRequestException");
    }

    public ConfigurationRevisionDetail describeConfigurationRevision(String arn, long revision) {
        ConfigurationRevision found;
        String serverProperties;
        synchronized (configurationUpdateLock) {
            MskConfiguration configuration = describeConfiguration(arn);
            found = configuration.getRevisions().stream()
                    .filter(r -> r.getRevision() == revision)
                    .findFirst()
                    .orElseThrow(() -> new AwsException("NotFoundException",
                            "Revision not found: " + revision + " for configuration " + arn, 404));
            serverProperties = configuration.getServerPropertiesByRevision().get(revision);
        }
        return new ConfigurationRevisionDetail(arn, found.getCreationTime(), found.getDescription(),
                revision, serverProperties);
    }

    // ── Tags (ListTagsForResource / TagResource / UntagResource on /v1/tags/{arn}) ──────────
    //
    // MSK tags both clusters and configurations, and the tag path carries only an ARN, so the
    // resource type is resolved by looking the ARN up in each store rather than by parsing it.
    // An unknown ARN is a 404 here, which is what the tags path documents - unlike
    // DescribeConfiguration, whose 400 is a deliberate terraform-provider contract.

    public Map<String, String> listTagsForResource(String arn) {
        MskCluster cluster = storage.get(arn).orElse(null);
        if (cluster != null) {
            return cluster.getTags() != null ? cluster.getTags() : Map.of();
        }
        MskConfiguration configuration = configurationStorage.get(arn).orElse(null);
        if (configuration != null) {
            return configuration.getTags() != null ? configuration.getTags() : Map.of();
        }
        throw taggedResourceNotFound(arn);
    }

    public void tagResource(String arn, Map<String, String> tags) {
        MskCluster cluster = storage.get(arn).orElse(null);
        if (cluster != null) {
            cluster.setTags(merged(cluster.getTags(), tags));
            storage.put(arn, cluster);
            return;
        }
        MskConfiguration configuration = configurationStorage.get(arn).orElse(null);
        if (configuration != null) {
            configuration.setTags(merged(configuration.getTags(), tags));
            configurationStorage.put(arn, configuration);
            return;
        }
        throw taggedResourceNotFound(arn);
    }

    public void untagResource(String arn, List<String> tagKeys) {
        MskCluster cluster = storage.get(arn).orElse(null);
        if (cluster != null) {
            cluster.setTags(without(cluster.getTags(), tagKeys));
            storage.put(arn, cluster);
            return;
        }
        MskConfiguration configuration = configurationStorage.get(arn).orElse(null);
        if (configuration != null) {
            configuration.setTags(without(configuration.getTags(), tagKeys));
            configurationStorage.put(arn, configuration);
            return;
        }
        throw taggedResourceNotFound(arn);
    }

    // Copies rather than mutating in place: a tag map that arrived through CreateCluster can be
    // an immutable Map (Map.of, or whatever Jackson handed us), which putAll/remove would
    // reject at runtime.
    private Map<String, String> merged(Map<String, String> existing, Map<String, String> added) {
        Map<String, String> result = new HashMap<>(existing != null ? existing : Map.of());
        if (added != null) {
            result.putAll(added);
        }
        return result;
    }

    private Map<String, String> without(Map<String, String> existing, List<String> tagKeys) {
        Map<String, String> result = new HashMap<>(existing != null ? existing : Map.of());
        if (tagKeys != null) {
            tagKeys.forEach(result::remove);
        }
        return result;
    }

    private AwsException taggedResourceNotFound(String arn) {
        return new AwsException("NotFoundException", "Resource " + arn + " does not exist.", 404);
    }

    private void startReadinessPoller() {
        poller.scheduleAtFixedRate(() -> {
            try {
                for (MskCluster cluster : allClusters()) {
                    if (cluster.getState() == ClusterState.CREATING && !config.services().msk().mock()) {
                        if (redpandaManager.isReady(cluster)) {
                            LOG.infov("MSK Cluster {0} is now ACTIVE", cluster.getClusterName());
                            cluster.setState(ClusterState.ACTIVE);
                            putCluster(cluster);
                        }
                    }
                }
            } catch (Exception e) {
                LOG.error("Error in MSK readiness poller", e);
            }
        }, 1, 2, TimeUnit.SECONDS);
    }

    private List<MskCluster> allClusters() {
        if (storage instanceof AccountAwareStorageBackend<MskCluster> aware) {
            return aware.scanAllAccounts();
        }
        return storage.scan(k -> true);
    }

    private void putCluster(MskCluster cluster) {
        if (cluster.getAccountId() != null && storage instanceof AccountAwareStorageBackend<MskCluster> aware) {
            aware.putForAccount(cluster.getAccountId(), cluster.getClusterArn(), cluster);
        } else {
            storage.put(cluster.getClusterArn(), cluster);
        }
    }

    @Override
    public List<ExplorerResource> getResources() {
        List<ExplorerResource> resources = new ArrayList<>();
        for (MskCluster cluster : storage.scan(k -> true)) {
            String arn = cluster.getClusterArn();
            if (arn == null) {
                continue;
            }
            AwsArnUtils.Arn parsed = AwsArnUtils.parse(arn);
            resources.add(new ExplorerResource(
                    arn, "kafka:cluster", "kafka",
                    parsed.region(), parsed.accountId(),
                    cluster.getCreationTime() != null ? cluster.getCreationTime() : Instant.now(),
                    cluster.getTags() != null ? cluster.getTags() : Map.of()));
        }
        return resources;
    }

    @Override
    public Set<SupportedResourceType> getSupportedResourceTypes() {
        return Set.of(new SupportedResourceType("kafka:cluster", "kafka", true));
    }
}
