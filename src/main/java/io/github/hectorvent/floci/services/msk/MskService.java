package io.github.hectorvent.floci.services.msk;

import io.github.hectorvent.floci.config.EmulatorConfig;
import io.github.hectorvent.floci.core.common.AwsArnUtils;
import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.core.common.RegionResolver;
import io.github.hectorvent.floci.core.storage.AccountAwareStorageBackend;
import io.github.hectorvent.floci.core.storage.StorageBackend;
import io.github.hectorvent.floci.core.storage.StorageFactory;
import io.github.hectorvent.floci.services.msk.model.ClusterState;
import io.github.hectorvent.floci.services.msk.model.MskCluster;
import com.fasterxml.jackson.core.type.TypeReference;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.security.SecureRandom;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

@ApplicationScoped
public class MskService {

    private static final Logger LOG = Logger.getLogger(MskService.class);
    private static final String DEFAULT_KAFKA_VERSION = "3.6.0";
    private final StorageBackend<String, MskCluster> storage;
    private final EmulatorConfig config;
    private final RegionResolver regionResolver;
    private final RedpandaManager redpandaManager;
    private final ScheduledExecutorService poller = Executors.newSingleThreadScheduledExecutor();

    @Inject
    public MskService(StorageFactory storageFactory, EmulatorConfig config,
                      RegionResolver regionResolver, RedpandaManager redpandaManager) {
        this.storage = storageFactory.create("msk", "msk-clusters.json", new TypeReference<Map<String, MskCluster>>() {});
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
