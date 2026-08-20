package io.github.hectorvent.floci.services.msk;

import io.github.hectorvent.floci.config.EmulatorConfig;
import io.github.hectorvent.floci.core.common.AwsArnUtils;
import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.core.common.Pagination;
import io.github.hectorvent.floci.core.common.PaginatedResult;
import io.github.hectorvent.floci.core.common.RegionResolver;
import io.github.hectorvent.floci.core.storage.AccountAwareStorageBackend;
import io.github.hectorvent.floci.core.storage.StorageBackend;
import io.github.hectorvent.floci.core.storage.StorageFactory;
import io.github.hectorvent.floci.services.msk.model.ClusterState;
import io.github.hectorvent.floci.services.msk.model.ConfigurationRevision;
import io.github.hectorvent.floci.services.msk.model.ConfigurationRevisionDetail;
import io.github.hectorvent.floci.services.msk.model.ConfigurationState;
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
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

@ApplicationScoped
public class MskService {

    private static final Logger LOG = Logger.getLogger(MskService.class);
    private static final String DEFAULT_KAFKA_VERSION = "3.6.0";
    private static final int MAX_PAGE = 100;
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
        if (storage.scan(k -> true).stream().anyMatch(c -> c.getClusterName().equals(clusterName))) {
            throw new AwsException("ConflictException", "Cluster already exists: " + clusterName, 409);
        }

        String accountId = regionResolver.getAccountId();
        String clusterArn = AwsArnUtils.Arn.of("kafka", config.defaultRegion(), accountId, "cluster/" + clusterName + "/" + java.util.UUID.randomUUID()).toString();

        String resolvedKafkaVersion = (kafkaVersion == null || kafkaVersion.isBlank()) ? DEFAULT_KAFKA_VERSION : kafkaVersion;
        MskCluster cluster = new MskCluster(clusterArn, clusterName, resolvedKafkaVersion);
        cluster.setAccountId(accountId);
        cluster.setVolumeId(String.format("%06x", new SecureRandom().nextInt(0xFFFFFF)));

        if (config.services().msk().mock()) {
            cluster.setState(ClusterState.ACTIVE);
            cluster.setBootstrapBrokers("localhost:9092");
        } else {
            redpandaManager.startContainer(cluster);
        }

        storage.put(clusterArn, cluster);
        return cluster;
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
        if (serverProperties == null || serverProperties.isBlank()) {
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
        return configurationStorage.get(arn)
                .orElseThrow(() -> new AwsException("NotFoundException", "Configuration not found: " + arn, 404));
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
        if (serverProperties == null || serverProperties.isBlank()) {
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
}
