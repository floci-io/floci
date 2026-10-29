package io.github.hectorvent.floci.services.redshift;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.hectorvent.floci.config.EmulatorConfig;
import io.github.hectorvent.floci.core.common.AwsArnUtils;
import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.core.common.AwsRegions;
import io.github.hectorvent.floci.core.common.PaginatedResult;
import io.github.hectorvent.floci.core.common.Pagination;
import io.github.hectorvent.floci.core.common.RegionResolver;
import io.github.hectorvent.floci.core.common.docker.DockerHostResolver;
import io.github.hectorvent.floci.core.storage.AccountAwareStorageBackend;
import io.github.hectorvent.floci.core.storage.StorageFactory;
import io.github.hectorvent.floci.services.dynamodb.backend.DynamoDbStreamReader;
import io.github.hectorvent.floci.services.rds.proxy.PasswordValidator;
import io.github.hectorvent.floci.services.redshift.RedshiftEventCatalog.EventInfo;
import io.github.hectorvent.floci.services.redshift.container.RedshiftContainerHandle;
import io.github.hectorvent.floci.services.redshift.container.RedshiftContainerManager;
import io.github.hectorvent.floci.services.redshift.metrics.RedshiftMetricTarget;
import io.github.hectorvent.floci.services.redshift.model.AutomatedSnapshotState;
import io.github.hectorvent.floci.services.redshift.model.Cluster;
import io.github.hectorvent.floci.services.redshift.model.ClusterParameterGroup;
import io.github.hectorvent.floci.services.redshift.model.ClusterSubnetGroup;
import io.github.hectorvent.floci.services.redshift.model.Endpoint;
import io.github.hectorvent.floci.services.redshift.model.EventSubscription;
import io.github.hectorvent.floci.services.redshift.model.Integration;
import io.github.hectorvent.floci.services.redshift.model.Parameter;
import io.github.hectorvent.floci.services.redshift.model.RedshiftEvent;
import io.github.hectorvent.floci.services.redshift.model.Snapshot;
import io.github.hectorvent.floci.services.redshift.model.SnapshotCopyGrant;
import io.github.hectorvent.floci.services.redshift.model.SnapshotSchedule;
import io.github.hectorvent.floci.services.redshift.proxy.RedshiftProxyManager;
import io.github.hectorvent.floci.services.secretsmanager.ManagedSecretRotator;
import io.github.hectorvent.floci.services.secretsmanager.RandomPasswordGenerator;
import io.github.hectorvent.floci.services.secretsmanager.SecretsManagerService;
import io.github.hectorvent.floci.services.secretsmanager.model.Secret;
import io.quarkus.runtime.StartupEvent;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Consumer;
import java.util.function.Supplier;
import java.util.regex.Pattern;

@ApplicationScoped
public class RedshiftService implements ManagedSecretRotator {
    private static final Logger LOG = Logger.getLogger(RedshiftService.class);

    // The group every cluster starts on until one is named.
    static final String DEFAULT_PARAMETER_GROUP_NAME = "default.redshift-1.0";
    private static final int MAX_RESET_PARAMETERS = 20;

    private final AccountAwareStorageBackend<Cluster> clusters;
    private final AccountAwareStorageBackend<Snapshot> snapshots;
    private final AccountAwareStorageBackend<ClusterParameterGroup> parameterGroups;
    private final AccountAwareStorageBackend<ClusterSubnetGroup> subnetGroups;
    private static final int MIN_INTEGRATION_RECORDS = 20;
    private static final int MAX_INTEGRATION_RECORDS = 100;
    private static final int MAX_INTEGRATION_DESCRIPTION = 1000;
    private static final int MAX_INTEGRATION_NAME = 63;
    // Modelled constraint on CreateIntegration.IntegrationName: a letter first, then alphanumeric
    // groups joined by single hyphens. No leading digit, underscore, or trailing hyphen.
    private static final String INTEGRATION_NAME_PATTERN = "^[a-zA-Z][a-zA-Z0-9]*(-[a-zA-Z0-9]+)*$";
    private static final Pattern INTEGRATION_NAME = Pattern.compile(INTEGRATION_NAME_PATTERN);

    private final AccountAwareStorageBackend<Integration> integrations;
    private final AccountAwareStorageBackend<SnapshotCopyGrant> snapshotCopyGrants;
    private final AccountAwareStorageBackend<SnapshotSchedule> snapshotSchedules;
    private final AccountAwareStorageBackend<AutomatedSnapshotState> automatedSnapshotStates;
    private final RedshiftContainerManager containerManager;
    private final EmulatorConfig config;
    private final RegionResolver regionResolver;
    private final RedshiftProxyManager proxyManager;
    private final DockerHostResolver dockerHostResolver;
    private final RedshiftCredentialBroker credentialBroker;
    private final SecretsManagerService secretsManagerService;
    private final ObjectMapper objectMapper;
    private final DynamoDbStreamReader streamReader;
    private final RedshiftAuditLogPublisher auditLogPublisher;
    private final RedshiftEventService events;
    private final RedshiftIntegrationEventPublisher integrationEvents;
    // Proxy ports currently handed out, so allocateProxyPort never double-assigns within this JVM.
    private final Set<Integer> usedPorts = ConcurrentHashMap.newKeySet();
    private final ConcurrentHashMap<ResourceKey, ResourceLock> resourceLocks = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, ResourceKey> managedRotationReservations = new ConcurrentHashMap<>();

    private record ResourceKey(String accountId, String kind, String identifier) { }

    private static final class ResourceLock {
        private final ReentrantLock lock = new ReentrantLock();
        private int users;
    }

    private <T> T withResourceLock(String kind, String identifier, Supplier<T> work) {
        return withResourceLock(clusters.accountId(), kind, identifier, work);
    }

    private <T> T withResourceLock(String accountId, String kind, String identifier, Supplier<T> work) {
        ResourceKey key = new ResourceKey(accountId, kind, identifier);
        ResourceLock scoped = resourceLocks.compute(key, (ignored, existing) -> {
            ResourceLock selected = existing == null ? new ResourceLock() : existing;
            selected.users++;
            return selected;
        });
        scoped.lock.lock();
        try {
            return work.get();
        } finally {
            scoped.lock.unlock();
            resourceLocks.compute(key, (ignored, existing) -> --scoped.users == 0 ? null : scoped);
        }
    }

    // Acquire resource locks before the service monitor; snapshot I/O never takes the monitor.
    private <T> T mutateCluster(String identifier, Supplier<T> mutation) {
        return withResourceLock("cluster", identifier, () -> {
            ensureNoManagedRotation(clusters.accountId(), identifier);
            return mutate(mutation);
        });
    }

    private void ensureNoManagedRotation(String accountId, String identifier) {
        ResourceKey key = new ResourceKey(accountId, "cluster", identifier);
        if (managedRotationReservations.containsValue(key)) {
            throw new AwsException("InvalidRequestException",
                    "The cluster's managed master password is being rotated. Try again after rotation completes.", 400);
        }
    }

    private synchronized <T> T mutate(Supplier<T> mutation) {
        return mutation.get();
    }

    static final DateTimeFormatter EVENT_MESSAGE_TIME =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm").withZone(ZoneOffset.UTC);

    static String eventTime(Instant at) {
        return EVENT_MESSAGE_TIME.format(at) + " UTC";
    }

    private static String eventTime() {
        return eventTime(Instant.now());
    }

    private void emitEvent(EventInfo info, String sourceIdentifier, String message) {
        if (events != null) {
            events.record(info, sourceIdentifier, message);
        }
    }

    private void emitSnapshotEvent(String accountId, String region, EventInfo info, String sourceIdentifier, String message) {
        if (events != null) {
            events.recordForAccount(accountId, region, info, sourceIdentifier, message);
        }
    }

    @Inject
    public RedshiftService(StorageFactory storageFactory, RedshiftContainerManager containerManager,
                            EmulatorConfig config, RegionResolver regionResolver,
                            RedshiftProxyManager proxyManager, DockerHostResolver dockerHostResolver,
                            RedshiftCredentialBroker credentialBroker,
                            SecretsManagerService secretsManagerService, ObjectMapper objectMapper,
                            DynamoDbStreamReader streamReader, RedshiftAuditLogPublisher auditLogPublisher,
                            RedshiftEventService events, RedshiftIntegrationEventPublisher integrationEvents) {
        this.clusters = storageFactory.create("redshift", "redshift-clusters.json", new TypeReference<Map<String, Cluster>>() {});
        this.snapshots = storageFactory.create("redshift", "redshift-snapshots.json", new TypeReference<Map<String, Snapshot>>() {});
        this.parameterGroups = storageFactory.create("redshift", "redshift-parameter-groups.json", new TypeReference<Map<String, ClusterParameterGroup>>() {});
        this.subnetGroups = storageFactory.create("redshift", "redshift-subnet-groups.json", new TypeReference<Map<String, ClusterSubnetGroup>>() {});
        this.integrations = storageFactory.create("redshift", "redshift-integrations.json", new TypeReference<Map<String, Integration>>() {});
        this.snapshotCopyGrants = storageFactory.create("redshift", "redshift-snapshot-copy-grants.json", new TypeReference<Map<String, SnapshotCopyGrant>>() {});
        this.snapshotSchedules = storageFactory.create("redshift", "redshift-snapshot-schedules.json", new TypeReference<Map<String, SnapshotSchedule>>() {});
        this.automatedSnapshotStates = storageFactory.create("redshift", "redshift-automated-snapshot-state.json",
                new TypeReference<Map<String, AutomatedSnapshotState>>() {});
        this.containerManager = containerManager;
        this.config = config;
        this.regionResolver = regionResolver;
        this.proxyManager = proxyManager;
        this.dockerHostResolver = dockerHostResolver;
        this.credentialBroker = credentialBroker;
        this.secretsManagerService = secretsManagerService;
        this.objectMapper = objectMapper;
        this.streamReader = streamReader;
        this.auditLogPublisher = auditLogPublisher;
        this.events = events;
        this.integrationEvents = integrationEvents;
    }

    RedshiftService(StorageFactory storageFactory, RedshiftContainerManager containerManager,
                    EmulatorConfig config, RegionResolver regionResolver, RedshiftProxyManager proxyManager,
                    DockerHostResolver dockerHostResolver, RedshiftCredentialBroker credentialBroker,
                    SecretsManagerService secretsManagerService, ObjectMapper objectMapper,
                    DynamoDbStreamReader streamReader, RedshiftAuditLogPublisher auditLogPublisher,
                    RedshiftEventService events) {
        this(storageFactory, containerManager, config, regionResolver, proxyManager, dockerHostResolver,
                credentialBroker, secretsManagerService, objectMapper, streamReader, auditLogPublisher, events,
                null);
    }

    RedshiftService(StorageFactory storageFactory, RedshiftContainerManager containerManager,
                    EmulatorConfig config, RegionResolver regionResolver, RedshiftProxyManager proxyManager,
                    DockerHostResolver dockerHostResolver, RedshiftCredentialBroker credentialBroker,
                    SecretsManagerService secretsManagerService, ObjectMapper objectMapper,
                    DynamoDbStreamReader streamReader, RedshiftAuditLogPublisher auditLogPublisher) {
        this(storageFactory, containerManager, config, regionResolver, proxyManager, dockerHostResolver,
                credentialBroker, secretsManagerService, objectMapper, streamReader, auditLogPublisher, null);
    }

    RedshiftService(StorageFactory storageFactory, RedshiftContainerManager containerManager,
                    EmulatorConfig config, RegionResolver regionResolver, RedshiftProxyManager proxyManager,
                    DockerHostResolver dockerHostResolver, RedshiftCredentialBroker credentialBroker,
                    SecretsManagerService secretsManagerService, ObjectMapper objectMapper,
                    DynamoDbStreamReader streamReader) {
        this(storageFactory, containerManager, config, regionResolver, proxyManager, dockerHostResolver,
                credentialBroker, secretsManagerService, objectMapper, streamReader, null);
    }

    RedshiftService(StorageFactory storageFactory, RedshiftContainerManager containerManager,
                    EmulatorConfig config, RegionResolver regionResolver,
                    RedshiftProxyManager proxyManager, DockerHostResolver dockerHostResolver,
                    RedshiftCredentialBroker credentialBroker) {
        this(storageFactory, containerManager, config, regionResolver, proxyManager, dockerHostResolver,
                credentialBroker, null, new ObjectMapper(), null);
    }

    // Recreate Docker containers for persisted clusters on app restart (across every account, not just default)
    void onStart(@Observes StartupEvent event) {
        List<AccountAwareStorageBackend.AccountEntry<Cluster>> persistedClusters =
                clusters.scanAllAccountEntries(k -> true);
        for (AccountAwareStorageBackend.AccountEntry<Cluster> entry : persistedClusters) {
            if (entry.value().getProxyPort() > 0) {
                usedPorts.add(entry.value().getProxyPort());
            }
        }
        List<AccountAwareStorageBackend.AccountEntry<Cluster>> availableClusters =
                persistedClusters.stream()
                        .filter(entry -> "available".equals(entry.value().getClusterStatus()))
                        .toList();
        for (AccountAwareStorageBackend.AccountEntry<Cluster> entry : availableClusters) {
            Cluster cluster = entry.value();
            registerAuditLogging(entry.accountId(), cluster);
            if (containerManager.getContainer(entry.accountId(), cluster.getClusterIdentifier()).isPresent()) {
                continue;
            }
            String password = cluster.getMasterPassword() != null ? cluster.getMasterPassword() : "admin";
            try {
                LOG.infov("Recovering container for persisted cluster: {0}", cluster.getClusterIdentifier());
                RedshiftContainerHandle handle = containerManager.adoptOrStart(
                        entry.accountId(), cluster.getClusterIdentifier(), cluster.getMasterUsername(), password,
                        CLUSTER_DB_NAME, cluster.getNodeType());

                // A cluster persisted before the auth proxy existed has proxyPort == 0. Allocate one
                // now; its endpoint changes exactly once after this upgrade. Existing clusters keep
                // their stored port so the endpoint is stable across restarts.
                int proxyPort = cluster.getProxyPort() > 0 ? cluster.getProxyPort() : allocateProxyPort();
                usedPorts.add(proxyPort);
                Endpoint endpoint = proxyEndpoint(proxyPort);
                cluster.setProxyPort(proxyPort);
                proxyManager.startProxy(
                        relayKey(entry.accountId(), cluster.getClusterIdentifier()), proxyPort,
                        handle.getHost(), handle.getPort(), endpoint.getAddress(),
                        cluster.getMasterUsername(), password, CLUSTER_DB_NAME,
                        passwordValidatorFor(entry.accountId(), cluster.getClusterIdentifier()),
                        cluster.getIamRoleArns(), cluster.getDefaultIamRoleArn());
                cluster.setContainerHost(handle.getHost());
                cluster.setContainerPort(handle.getPort());
                cluster.setEndpoint(endpoint);
                clusters.putForAccount(entry.accountId(), entry.key(), cluster);
            } catch (Exception e) {
                LOG.warnv(e, "Failed to recover container for cluster {0}, marking as unavailable",
                        cluster.getClusterIdentifier());
                try {
                    proxyManager.stopProxy(relayKey(entry.accountId(), cluster.getClusterIdentifier()));
                } catch (Exception ex) {
                    LOG.warnv(ex, "Failed to stop proxy during recovery rollback for cluster {0}", cluster.getClusterIdentifier());
                }
                try {
                    containerManager.rollbackStartup(entry.accountId(), cluster.getClusterIdentifier());
                } catch (Exception ex) {
                    LOG.warnv(ex, "Failed to stop container during recovery rollback for cluster {0}", cluster.getClusterIdentifier());
                }
                cluster.setClusterStatus("unavailable");
                clusters.putForAccount(entry.accountId(), entry.key(), cluster);
            }
        }
        clusters.flush();
    }

    public Cluster createCluster(String identifier, String nodeType, String username, String password) {
        return createCluster(identifier, nodeType, username, password, null, List.of(), List.of());
    }

    // Cluster lifecycle mutations take the scoped resource lock before the service monitor.
    public Cluster createCluster(String identifier, String nodeType, String username, String password,
                                  String clusterSubnetGroupName, List<String> vpcSecurityGroupIds) {
        return createCluster(identifier, nodeType, username, password, clusterSubnetGroupName,
                vpcSecurityGroupIds, List.of());
    }

    public Cluster createCluster(String identifier, String nodeType, String username, String password,
                                               String clusterSubnetGroupName, List<String> vpcSecurityGroupIds,
                                               List<String> iamRoleArns) {
        return createCluster(identifier, nodeType, username, password, clusterSubnetGroupName,
                vpcSecurityGroupIds, iamRoleArns, null);
    }

    public Cluster createCluster(String identifier, String nodeType, String username, String password,
                                               String clusterSubnetGroupName, List<String> vpcSecurityGroupIds,
                                               List<String> iamRoleArns, String defaultIamRoleArn) {
        return createCluster(identifier, nodeType, username, password, clusterSubnetGroupName,
                vpcSecurityGroupIds, iamRoleArns, defaultIamRoleArn, null);
    }

    public Cluster createCluster(String identifier, String nodeType, String username, String password,
                                 String clusterSubnetGroupName, List<String> vpcSecurityGroupIds,
                                 List<String> iamRoleArns, String defaultIamRoleArn, Integer automatedRetention) {
        validateAutomatedRetention(nodeType, automatedRetention == null ? 1 : automatedRetention);
        // The event is recorded under the cluster lock so a queued delete or reboot cannot log first.
        return mutateCluster(identifier, () -> {
            Cluster cluster = createClusterLocked(identifier, nodeType, username, password,
                    clusterSubnetGroupName, vpcSecurityGroupIds, iamRoleArns, defaultIamRoleArn, automatedRetention);
            emitClusterCreated(identifier);
            return cluster;
        });
    }

    private void emitClusterCreated(String identifier) {
        emitEvent(RedshiftEventCatalog.CLUSTER_CREATED, identifier, "Amazon Redshift cluster '" + identifier
                + "' has been created at " + eventTime() + " and is ready for use.");
    }

    private Cluster createClusterLocked(String identifier, String nodeType, String username, String password,
                                               String clusterSubnetGroupName, List<String> vpcSecurityGroupIds,
                                               List<String> iamRoleArns, String defaultIamRoleArn,
                                               Integer automatedRetention) {
        if (clusters.get(identifier).isPresent()) {
            throw new AwsException("ClusterAlreadyExists", "Cluster " + identifier + " already exists", 400);
        }
        List<String> roles = iamRoleArns == null ? List.of() : List.copyOf(iamRoleArns);
        String defaultRole = validateDefaultIamRole(defaultIamRoleArn, roles);
        // A previous cluster with this identifier may have been deleted without its temp
        // credentials being cleared; drop them so the new cluster starts with none.
        credentialBroker.revokeCluster(clusters.accountId(), identifier);

        Cluster cluster = new Cluster();
        cluster.setClusterIdentifier(identifier);
        cluster.setNodeType(nodeType);
        cluster.setAutomatedSnapshotRetentionPeriod(automatedRetention);
        cluster.setAutomatedSnapshotAnchor(Instant.now());
        cluster.setSnapshotGeneration(UUID.randomUUID().toString());
        cluster.setSnapshotRegion(regionResolver.getRegion());
        cluster.setMasterUsername(username);
        cluster.setMasterPassword(password);
        cluster.setClusterSubnetGroupName(clusterSubnetGroupName);
        cluster.setVpcSecurityGroupIds(vpcSecurityGroupIds != null ? vpcSecurityGroupIds : List.of());
        cluster.setIamRoleArns(roles);
        cluster.setDefaultIamRoleArn(defaultRole);
        // A group named at create time is applied afterwards via modifyCluster.
        cluster.setClusterParameterGroupName(DEFAULT_PARAMETER_GROUP_NAME);
        cluster.setUserActivityLoggingEnabled(userActivityLoggingEnabled(DEFAULT_PARAMETER_GROUP_NAME));
        cluster.setClusterStatus("creating");
        clusters.put(identifier, cluster);
        clusters.flush();

        // Start container, then front it with an auth proxy so the advertised endpoint is
        // reachable from outside the Docker network.
        // Hoisted out of the try so a failure after allocateProxyPort() still returns the port.
        int proxyPort = -1;
        try {
            String accountId = clusters.accountId();
            RedshiftContainerHandle handle = containerManager.start(
                    accountId, identifier, username, password, CLUSTER_DB_NAME, cluster.getNodeType());
            proxyPort = allocateProxyPort();
            Endpoint endpoint = proxyEndpoint(proxyPort);
            cluster.setProxyPort(proxyPort);
            proxyManager.startProxy(relayKey(accountId, identifier), proxyPort,
                    handle.getHost(), handle.getPort(), endpoint.getAddress(),
                    username, password, CLUSTER_DB_NAME,
                    passwordValidatorFor(accountId, identifier), cluster.getIamRoleArns(), cluster.getDefaultIamRoleArn());
            cluster.setContainerHost(handle.getHost());
            cluster.setContainerPort(handle.getPort());
            cluster.setEndpoint(endpoint);
            cluster.setClusterStatus("available");
        } catch (AwsException e) {
            boolean proxyStopped = stopProxyAndReleasePortSafely(identifier, proxyPort);
            try { containerManager.stop(clusters.accountId(), identifier); } catch (Exception ex) { LOG.warnv(ex, "Failed to stop container during rollback of cluster {0}", identifier); }
            if (proxyStopped) {
                clusters.delete(identifier);
                credentialBroker.revokeCluster(clusters.accountId(), identifier);
            } else {
                cluster.setClusterStatus("failed");
                clusters.put(identifier, cluster);
            }
            clusters.flush();
            throw e;
        } catch (Exception e) {
            boolean proxyStopped = stopProxyAndReleasePortSafely(identifier, proxyPort);
            try { containerManager.stop(clusters.accountId(), identifier); } catch (Exception ex) { LOG.warnv(ex, "Failed to stop container during rollback of cluster {0}", identifier); }
            if (proxyStopped) {
                clusters.delete(identifier);
                credentialBroker.revokeCluster(clusters.accountId(), identifier);
            } else {
                cluster.setClusterStatus("failed");
                clusters.put(identifier, cluster);
            }
            clusters.flush();
            throw new AwsException("InternalFailure", "Failed to start container: " + e.getMessage(), 500);
        }

        clusters.put(identifier, cluster);
        clusters.flush();
        return cluster;
    }

    public Cluster createClusterWithManagedMasterPassword(
            String identifier, String nodeType, String username, String clusterSubnetGroupName,
            List<String> vpcSecurityGroupIds, List<String> iamRoleArns,
            String kmsKeyId, String region) {
        return createClusterWithManagedMasterPassword(identifier, nodeType, username, clusterSubnetGroupName,
                vpcSecurityGroupIds, iamRoleArns, kmsKeyId, region, null);
    }

    public Cluster createClusterWithManagedMasterPassword(
            String identifier, String nodeType, String username, String clusterSubnetGroupName,
            List<String> vpcSecurityGroupIds, List<String> iamRoleArns,
            String kmsKeyId, String region, String defaultIamRoleArn) {
        return createClusterWithManagedMasterPassword(identifier, nodeType, username, clusterSubnetGroupName,
                vpcSecurityGroupIds, iamRoleArns, kmsKeyId, region, defaultIamRoleArn, null);
    }

    public Cluster createClusterWithManagedMasterPassword(
            String identifier, String nodeType, String username, String clusterSubnetGroupName,
            List<String> vpcSecurityGroupIds, List<String> iamRoleArns,
            String kmsKeyId, String region, String defaultIamRoleArn, Integer automatedRetention) {
        validateAutomatedRetention(nodeType, automatedRetention == null ? 1 : automatedRetention);
        return mutateCluster(identifier, () -> createClusterWithManagedMasterPasswordLocked(identifier,
                nodeType, username, clusterSubnetGroupName, vpcSecurityGroupIds, iamRoleArns, kmsKeyId, region,
                defaultIamRoleArn, automatedRetention));
    }

    private Cluster createClusterWithManagedMasterPasswordLocked(
            String identifier, String nodeType, String username, String clusterSubnetGroupName,
            List<String> vpcSecurityGroupIds, List<String> iamRoleArns,
            String kmsKeyId, String region, String defaultIamRoleArn, Integer automatedRetention) {
        requireSecretsManager();
        String password = RandomPasswordGenerator.generate(objectMapper.createObjectNode());
        // Runs under the cluster lock taken by the caller, which also covers the event recorded below.
        Cluster cluster = createClusterLocked(identifier, nodeType, username, password,
                clusterSubnetGroupName, vpcSecurityGroupIds, iamRoleArns, defaultIamRoleArn, automatedRetention);
        if (region != null) {
            cluster.setSnapshotRegion(region);
        }
        try {
            Cluster created = createManagedMasterSecret(cluster, kmsKeyId, region);
            emitClusterCreated(identifier);
            return created;
        } catch (RuntimeException e) {
            rollbackManagedMasterPasswordCluster(cluster);
            throw e;
        }
    }

    private void requireSecretsManager() {
        if (secretsManagerService == null) {
            throw new AwsException("InternalFailure", "Secrets Manager is unavailable", 500);
        }
    }

    private void storeCluster(Cluster cluster) {
        mutate(() -> {
            clusters.put(cluster.getClusterIdentifier(), cluster);
            clusters.flush();
            return null;
        });
    }

    private Cluster createManagedMasterSecret(Cluster cluster, String kmsKeyId, String region) {
        String identifier = cluster.getClusterIdentifier();
        String secretName = "redshift/" + identifier;
        String secretString = managedMasterSecret(cluster, cluster.getMasterPassword());
        Secret secret = secretsManagerService.createSecret(secretName, secretString, null,
                "Managed master user secret for Redshift cluster " + identifier,
                kmsKeyId, List.of(), "redshift", region);
        cluster.setMasterPasswordSecretArn(secret.getArn());
        cluster.setMasterPasswordSecretKmsKeyId(kmsKeyId);
        storeCluster(cluster);
        return cluster;
    }

    private void rollbackManagedMasterPasswordCluster(Cluster cluster) {
        boolean proxyStopped = stopProxyAndReleasePortSafely(
                cluster.getClusterIdentifier(), cluster.getProxyPort());
        try {
            containerManager.stop(clusters.accountId(), cluster.getClusterIdentifier());
        } catch (Exception e) {
            LOG.warnv(e, "Failed to stop managed-password cluster container {0} during rollback",
                    cluster.getClusterIdentifier());
        }
        if (proxyStopped) {
            clusters.delete(cluster.getClusterIdentifier());
            credentialBroker.revokeCluster(clusters.accountId(), cluster.getClusterIdentifier());
        } else {
            cluster.setClusterStatus("failed");
            clusters.put(cluster.getClusterIdentifier(), cluster);
        }
        clusters.flush();
    }

    // ── Zero-ETL integrations ────────────────────────────────────
    //
    // Metadata only: no data is replicated from the source. The shape and the lower case status
    // were captured from a live integration in us-west-2.

    public Integration createIntegration(String integrationName, String sourceArn, String targetArn,
                                         String kmsKeyId, String description,
                                         Map<String, String> additionalEncryptionContext,
                                         Map<String, String> tags, String region) {
        Integration integration = createIntegrationLocked(integrationName, sourceArn, targetArn, kmsKeyId,
                description, additionalEncryptionContext, tags, region);
        publishIntegrationEvents();
        return integration;
    }

    private synchronized Integration createIntegrationLocked(String integrationName, String sourceArn,
                                                             String targetArn, String kmsKeyId, String description,
                                                             Map<String, String> additionalEncryptionContext,
                                                             Map<String, String> tags, String region) {
        if (integrationName == null || integrationName.isBlank()) {
            throw new AwsException("InvalidParameterValue", "IntegrationName is required.", 400);
        }
        if (integrationName.length() > MAX_INTEGRATION_NAME
                || !INTEGRATION_NAME.matcher(integrationName).matches()) {
            throw new AwsException("InvalidParameterValue",
                    "IntegrationName must match " + INTEGRATION_NAME_PATTERN
                            + " and be at most " + MAX_INTEGRATION_NAME + " characters.", 400);
        }
        if (sourceArn == null || sourceArn.isBlank()) {
            throw new AwsException("InvalidParameterValue", "SourceArn is required.", 400);
        }
        if (targetArn == null || targetArn.isBlank()) {
            throw new AwsException("InvalidParameterValue", "TargetArn is required.", 400);
        }
        AwsArnUtils.Arn source = parseZeroEtlArn(sourceArn, "DynamoDB stream");
        if (!"dynamodb".equals(source.service()) || !source.resource().startsWith("table/")
                || !source.resource().contains("/stream/")) {
            throw new AwsException("InvalidParameterValue",
                    "SourceArn must identify a DynamoDB stream.", 400);
        }
        if (streamReader == null) {
            throw new AwsException("InternalFailure", "DynamoDB stream service is unavailable.", 500);
        }
        streamReader.describeStream(DynamoDbStreamReader.Stream.of(sourceArn), null, null);
        AwsArnUtils.Arn target = parseZeroEtlArn(targetArn, "Redshift cluster");
        if (!"redshift".equals(target.service()) || !target.resource().startsWith("cluster:")) {
            throw new AwsException("InvalidParameterValue",
                    "TargetArn must identify a provisioned Redshift cluster.", 400);
        }
        String clusterIdentifier = target.resource().substring("cluster:".length());
        if (clusterIdentifier.isBlank()) {
            throw new AwsException("InvalidParameterValue", "TargetArn must identify a Redshift cluster.", 400);
        }
        describeClusters(clusterIdentifier);
        if (description != null && description.length() > MAX_INTEGRATION_DESCRIPTION) {
            throw new AwsException("InvalidParameterValue",
                    "Description must be at most " + MAX_INTEGRATION_DESCRIPTION + " characters.", 400);
        }
        // AdditionalEncryptionContext only means anything alongside a customer managed key.
        if (additionalEncryptionContext != null && !additionalEncryptionContext.isEmpty()
                && (kmsKeyId == null || kmsKeyId.isBlank())) {
            throw new AwsException("InvalidParameterValue",
                    "AdditionalEncryptionContext is only valid when KMSKeyId is supplied.", 400);
        }

        boolean nameTaken = integrations.scan(k -> true).stream()
                .anyMatch(existing -> integrationName.equals(existing.getIntegrationName()));
        if (nameTaken) {
            throw new AwsException("IntegrationAlreadyExistsFault",
                    "The integration " + integrationName + " already exists.", 400);
        }

        String integrationId = UUID.randomUUID().toString();
        Integration integration = new Integration();
        integration.setAccountId(integrations.accountId());
        integration.setIntegrationArn(AwsArnUtils.Arn.of("redshift", region, regionResolver.getAccountId(),
                "integration:" + integrationId).toString());
        integration.setIntegrationName(integrationName);
        integration.setSourceArn(sourceArn);
        integration.setTargetArn(targetArn);
        integration.setSourceStreamArn(sourceArn);
        integration.setTargetClusterIdentifier(clusterIdentifier);
        integration.setLandingTableName("floci_zetl_" + integrationId.replace('-', '_'));
        integration.setRetryCount(0);
        integration.setLastError(null);
        integration.setPollingEnabled(true);
        // Floci approximation: report `syncing` while the backfill scan runs, then `active`.
        integration.setStatus("syncing");
        integration.setKmsKeyId(kmsKeyId);
        integration.setCreateTime(DateTimeFormatter.ISO_INSTANT.format(Instant.now()));
        integration.setDescription(description);
        integration.setAdditionalEncryptionContext(additionalEncryptionContext);
        integration.setTags(tags);
        integrations.put(integrationId, integration);
        LOG.infov("Created Redshift zero-ETL integration: {0}", integration.getIntegrationArn());
        queueIntegrationEvent(RedshiftIntegrationEventPublisher.IntegrationEvent.SYNCHRONIZING, integration);
        return integration;
    }

    private static AwsArnUtils.Arn parseZeroEtlArn(String arn, String resourceType) {
        try {
            return AwsArnUtils.parse(arn);
        } catch (IllegalArgumentException e) {
            throw new AwsException("InvalidParameterValue", resourceType + " ARN is invalid.", 400);
        }
    }

    /**
     * Lists integrations with the documented filters and marker pagination.
     *
     * <p>An unknown {@code IntegrationArn} is {@code IntegrationNotFoundFault}, measured against
     * real Redshift. An account with no integrations at all is an empty list rather than an error,
     * and no {@code Marker} is emitted on the terminal page.
     */
    public IntegrationPage describeIntegrations(String integrationArn, Integer maxRecords, String marker,
                                                List<IntegrationFilter> filters) {
        List<Integration> all = integrations.scan(k -> true).stream()
                .sorted(Comparator.comparing(Integration::getIntegrationArn))
                .toList();

        if (integrationArn != null && !integrationArn.isBlank()) {
            Integration match = all.stream()
                    .filter(integration -> integrationArn.equals(integration.getIntegrationArn()))
                    .findFirst()
                    .orElseThrow(() -> new AwsException("IntegrationNotFoundFault",
                            "The requested integration doesn't exist.", 404));
            all = List.of(match);
        }

        for (IntegrationFilter filter : filters == null ? List.<IntegrationFilter>of() : filters) {
            all = all.stream().filter(integration -> matchesFilter(integration, filter)).toList();
        }

        int pageSize = resolveMaxRecords(maxRecords);
        int from = 0;
        if (marker != null && !marker.isBlank()) {
            int previous = -1;
            for (int i = 0; i < all.size(); i++) {
                if (marker.equals(all.get(i).getIntegrationArn())) {
                    previous = i;
                    break;
                }
            }
            if (previous < 0) {
                throw new AwsException("InvalidParameterValue", "Invalid Marker specified.", 400);
            }
            from = previous + 1;
        }

        List<Integration> page = all.subList(Math.min(from, all.size()), Math.min(from + pageSize, all.size()));
        boolean more = from + pageSize < all.size();
        // The marker is the last ARN already returned, so a page resumes after a known record
        // rather than at an offset a concurrent create could shift.
        String next = more && !page.isEmpty() ? page.get(page.size() - 1).getIntegrationArn() : null;
        return new IntegrationPage(List.copyOf(page), next);
    }

    public List<Integration> listDynamoDbZeroEtlIntegrations() {
        return integrations.scanAllAccountEntries(key -> true).stream()
                .filter(entry -> entry.value().getSourceStreamArn() != null
                        && !entry.value().getSourceStreamArn().isBlank())
                .map(entry -> {
                    Integration integration = entry.value();
                    if (integration.getAccountId() == null) {
                        integration.setAccountId(entry.accountId());
                    }
                    return integration;
                })
                .toList();
    }

    public void updateIntegrationRuntime(String accountId, String integrationArn,
                                         Map<String, String> shardSequenceNumbers,
                                         boolean successful, String error) {
        updateIntegrationRuntimeLocked(accountId, integrationArn, shardSequenceNumbers, successful, error, false);
        publishIntegrationEvents();
    }

    /** Recovers idle polling health without replacing checkpoints or persisting unchanged state. */
    public void recoverIntegrationRuntime(String accountId, String integrationArn) {
        updateIntegrationRuntimeLocked(accountId, integrationArn, null, true, null, true);
        publishIntegrationEvents();
    }

    private synchronized void updateIntegrationRuntimeLocked(String accountId, String integrationArn,
                                                              Map<String, String> shardSequenceNumbers,
                                                              boolean successful, String error, boolean recoveryOnly) {
        for (String key : integrations.keysForAccount(accountId)) {
            Optional<Integration> stored = integrations.getForAccount(accountId, key);
            if (stored.isEmpty() || !integrationArn.equals(stored.get().getIntegrationArn())) {
                continue;
            }
            Integration integration = stored.get();
            String targetIdentifier = integration.getTargetClusterIdentifier();
            if (!successful && targetIdentifier != null && clusters.getForAccount(accountId, targetIdentifier)
                    .filter(cluster -> "paused".equals(cluster.getClusterStatus())).isPresent()) {
                return;
            }
            String previousStatus = integration.getStatus();
            if (recoveryOnly && "active".equals(previousStatus)
                    && integration.getRetryCount() == 0 && integration.getLastError() == null) {
                return;
            }
            if (successful) {
                if (!recoveryOnly) {
                    integration.setShardSequenceNumbers(shardSequenceNumbers);
                }
                integration.setRetryCount(0);
                integration.setLastError(null);
                integration.setStatus("active");
            } else {
                integration.setRetryCount(integration.getRetryCount() + 1);
                integration.setLastError(error);
                integration.setStatus("failed");
            }
            integrations.putForAccount(accountId, key, integration);
            queueStatusChange(integration, previousStatus);
            return;
        }
        throw new AwsException("IntegrationNotFoundFault", "The requested integration doesn't exist.", 404);
    }

    public void updateIntegrationBackfillProgress(String accountId, String integrationArn,
                                                  String backfillLastEvaluatedKey, boolean backfillCompleted) {
        updateIntegrationBackfillProgressLocked(accountId, integrationArn, backfillLastEvaluatedKey,
                backfillCompleted);
        publishIntegrationEvents();
    }

    private synchronized void updateIntegrationBackfillProgressLocked(String accountId, String integrationArn,
                                                                       String backfillLastEvaluatedKey,
                                                                       boolean backfillCompleted) {
        for (String key : integrations.keysForAccount(accountId)) {
            Optional<Integration> stored = integrations.getForAccount(accountId, key);
            if (stored.isEmpty() || !integrationArn.equals(stored.get().getIntegrationArn())) {
                continue;
            }
            Integration integration = stored.get();
            String previousStatus = integration.getStatus();
            boolean backfillJustCompleted = !integration.isBackfillCompleted() && backfillCompleted;
            integration.setBackfillLastEvaluatedKey(backfillLastEvaluatedKey);
            integration.setBackfillCompleted(backfillCompleted);
            integration.setRetryCount(0);
            integration.setLastError(null);
            integration.setStatus(backfillCompleted ? "active" : "syncing");
            integrations.putForAccount(accountId, key, integration);
            if (backfillJustCompleted) {
                queueIntegrationEvent(RedshiftIntegrationEventPublisher.IntegrationEvent.CREATED_ACTIVE, integration);
            } else {
                queueStatusChange(integration, previousStatus);
            }
            return;
        }
        throw new AwsException("IntegrationNotFoundFault", "The requested integration doesn't exist.", 404);
    }

    /** One page of integrations plus the marker to continue with, or {@code null} at the end. */
    public record IntegrationPage(List<Integration> integrations, String marker) {}

    /** One {@code Filters.DescribeIntegrationsFilter.N} entry. */
    public record IntegrationFilter(String name, List<String> values) {}

    private static boolean matchesFilter(Integration integration, IntegrationFilter filter) {
        String name = filter.name() == null ? "" : filter.name();
        List<String> values = filter.values() == null ? List.of() : filter.values();
        String actual = switch (name) {
            case "integration-arn" -> integration.getIntegrationArn();
            case "source-arn" -> integration.getSourceArn();
            case "status" -> integration.getStatus();
            // source-types filters on the source's AWS service, which the ARN's third field names.
            case "source-types" -> sourceType(integration.getSourceArn());
            default -> throw new AwsException("InvalidParameterValue",
                    "Unrecognized filter name: " + name, 400);
        };
        return actual != null && values.contains(actual);
    }

    private static String sourceType(String sourceArn) {
        return AwsArnUtils.isArn(sourceArn) ? AwsArnUtils.parse(sourceArn).service() : null;
    }

    private static int resolveMaxRecords(Integer maxRecords) {
        if (maxRecords == null) {
            return MAX_INTEGRATION_RECORDS;
        }
        if (maxRecords < MIN_INTEGRATION_RECORDS || maxRecords > MAX_INTEGRATION_RECORDS) {
            throw new AwsException("InvalidParameterValue",
                    "MaxRecords must be between " + MIN_INTEGRATION_RECORDS + " and "
                            + MAX_INTEGRATION_RECORDS + ".", 400);
        }
        return maxRecords;
    }

    public Integration deleteIntegration(String integrationArn) {
        Integration deleted = deleteIntegrationLocked(integrationArn);
        publishIntegrationEvents();
        return deleted;
    }

    private synchronized Integration deleteIntegrationLocked(String integrationArn) {
        if (integrationArn == null || integrationArn.isBlank()) {
            throw new AwsException("InvalidParameterValue", "IntegrationArn is required.", 400);
        }
        for (String key : integrations.keys()) {
            Optional<Integration> stored = integrations.get(key);
            if (stored.isPresent() && integrationArn.equals(stored.get().getIntegrationArn())) {
                integrations.delete(key);
                LOG.infov("Deleted Redshift zero-ETL integration: {0}", integrationArn);
                // Deletion is immediate here, so both the start and the end of it are reported.
                queueIntegrationEvent(RedshiftIntegrationEventPublisher.IntegrationEvent.DELETION_INITIATED,
                        stored.get());
                queueIntegrationEvent(RedshiftIntegrationEventPublisher.IntegrationEvent.DELETED, stored.get());
                return stored.get();
            }
        }
        throw new AwsException("IntegrationNotFoundFault", "The requested integration doesn't exist.", 404);
    }

    private void queueIntegrationEvent(RedshiftIntegrationEventPublisher.IntegrationEvent event,
                                       Integration integration) {
        if (integrationEvents != null) {
            integrationEvents.queue(event, integration, AwsArnUtils.parse(integration.getIntegrationArn()).region(),
                    Instant.now());
        }
    }

    /** Queues the event for a move into syncing, active or failed; any other change is silent. */
    private void queueStatusChange(Integration integration, String previousStatus) {
        String status = integration.getStatus();
        if (status == null || status.equals(previousStatus)) {
            return;
        }
        switch (status) {
            case "syncing" -> queueIntegrationEvent(
                    RedshiftIntegrationEventPublisher.IntegrationEvent.SYNCHRONIZING, integration);
            case "active" -> queueIntegrationEvent(
                    RedshiftIntegrationEventPublisher.IntegrationEvent.ACTIVE, integration);
            case "failed" -> queueIntegrationEvent(
                    RedshiftIntegrationEventPublisher.IntegrationEvent.FAILED, integration);
            default -> { }
        }
    }

    private void publishIntegrationEvents() {
        if (integrationEvents != null) {
            integrationEvents.publishQueued();
        }
    }

    public List<Cluster> describeClusters(String identifier) {
        if (identifier != null) {
            Optional<Cluster> cluster = clusters.get(identifier);
            if (cluster.isEmpty()) {
                throw new AwsException("ClusterNotFound", "Cluster " + identifier + " not found", 404);
            }
            return List.of(cluster.get());
        }
        return clusters.scan(k -> true);
    }

    public List<Cluster> describeClustersForAccount(String accountId, String identifier) {
        if (identifier != null) {
            Optional<Cluster> cluster = clusters.getForAccount(accountId, identifier);
            if (cluster.isEmpty()) {
                throw new AwsException("ClusterNotFound", "Cluster " + identifier + " not found", 404);
            }
            return List.of(cluster.get());
        }
        return clusters.scanForAccount(accountId, k -> true);
    }

    /** The available clusters of every account, for the AWS/Redshift metrics publisher. */
    public List<RedshiftMetricTarget> availableMetricTargets() {
        return clusters.scanAllAccountEntries(k -> true).stream()
                .filter(entry -> "available".equals(entry.value().getClusterStatus()))
                .map(entry -> new RedshiftMetricTarget(entry.accountId(), snapshotRegion(entry.value()),
                        entry.value(), relayKey(entry.accountId(), entry.value().getClusterIdentifier())))
                .toList();
    }

    /** Internal deletion used by provisioner cleanup, which explicitly skips the final snapshot. */
    public Cluster deleteCluster(String identifier) {
        return deleteCluster(identifier, true, null, null);
    }

    public Cluster deleteCluster(String identifier, Boolean skipFinalClusterSnapshot,
                                               String finalSnapshotIdentifier, Integer finalSnapshotRetentionPeriod) {
        return withResourceLock("cluster", identifier, () -> {
            ensureNoManagedRotation(clusters.accountId(), identifier);
            Supplier<Cluster> deletion = () -> deleteClusterWithFinalSnapshot(identifier, skipFinalClusterSnapshot,
                    finalSnapshotIdentifier, finalSnapshotRetentionPeriod);
            return Boolean.TRUE.equals(skipFinalClusterSnapshot) ? deletion.get()
                    : withResourceLock("snapshot", finalSnapshotIdentifier, deletion);
        });
    }

    private Cluster deleteClusterWithFinalSnapshot(String identifier, Boolean skipFinalClusterSnapshot,
                                                   String finalSnapshotIdentifier, Integer finalSnapshotRetentionPeriod) {
        boolean skipSnapshot = Boolean.TRUE.equals(skipFinalClusterSnapshot);
        if (skipSnapshot && finalSnapshotIdentifier != null) {
            throw new AwsException("InvalidParameterCombination",
                    "FinalClusterSnapshotIdentifier cannot be supplied when SkipFinalClusterSnapshot is true", 400);
        }
        if (!skipSnapshot && (finalSnapshotIdentifier == null || finalSnapshotIdentifier.isBlank())) {
            throw new AwsException("InvalidParameterCombination",
                    "FinalClusterSnapshotIdentifier is required unless SkipFinalClusterSnapshot is true", 400);
        }
        int retentionPeriod = finalSnapshotRetentionPeriod == null ? -1 : finalSnapshotRetentionPeriod;
        if (retentionPeriod != -1 && (retentionPeriod < 1 || retentionPeriod > 3653)) {
            throw new AwsException("InvalidRetentionPeriodFault",
                    "FinalClusterSnapshotRetentionPeriod must be -1 or between 1 and 3653", 400);
        }
        Optional<Cluster> clusterOpt = clusters.get(identifier);
        if (clusterOpt.isEmpty()) {
            throw new AwsException("ClusterNotFound", "Cluster " + identifier + " not found", 404);
        }
        Cluster cluster = clusterOpt.get();

        if (!skipSnapshot) {
            if (!"available".equals(cluster.getClusterStatus())) {
                throw new AwsException("InvalidClusterState", "Cluster must be available to create a final snapshot", 400);
            }
            createSnapshot(finalSnapshotIdentifier, identifier, retentionPeriod);
        }

        Cluster removed = mutate(() -> removeCluster(identifier, cluster));
        emitEvent(RedshiftEventCatalog.CLUSTER_DELETED, identifier, "Amazon Redshift cluster '" + identifier
                + "' has been deleted at " + eventTime() + ". A final snapshot was "
                + (skipSnapshot ? "not " : "") + "saved.");
        return removed;
    }

    private Cluster removeCluster(String identifier, Cluster cluster) {
        // Tear down the auth proxy and return its port before stopping the container.
        // If it fails, abort deletion so the metadata remains and the user can retry.
        if (!stopProxyAndReleasePortSafely(identifier, cluster.getProxyPort())) {
            throw new AwsException("InternalFailure",
                    "Failed to stop auth proxy; cluster " + identifier + " was not deleted", 500);
        }

        containerManager.stop(clusters.accountId(), identifier);
        proxyManager.forgetSpectrumRuntime(relayKey(clusters.accountId(), identifier));
        clusters.delete(identifier);
        clusters.flush();
        if (auditLogPublisher != null) {
            auditLogPublisher.unregister(clusters.accountId(), identifier);
        }
        // Invalidate any GetClusterCredentials passwords so a cluster later recreated with this
        // identifier does not accept them as master-equivalent.
        credentialBroker.revokeCluster(clusters.accountId(), identifier);

        if (cluster.getMasterPasswordSecretArn() != null && secretsManagerService != null) {
            try {
                secretsManagerService.deleteSecret(cluster.getMasterPasswordSecretArn(), null, true,
                        regionResolver.getRegion());
            } catch (AwsException e) {
                LOG.warnv(e, "Failed to remove managed master secret for cluster {0}", identifier);
            }
        }

        cluster.setClusterStatus("deleting");
        return cluster;
    }

    public Cluster modifyCluster(String clusterIdentifier, String nodeType, Integer numberOfNodes,
                                 String masterUserPassword, String clusterParameterGroupName,
                                 List<String> vpcSecurityGroupIds) {
        return modifyCluster(clusterIdentifier, nodeType, numberOfNodes, masterUserPassword,
                clusterParameterGroupName, vpcSecurityGroupIds, null);
    }

    public Cluster modifyCluster(String clusterIdentifier, String nodeType, Integer numberOfNodes,
                                               String masterUserPassword, String clusterParameterGroupName,
                                               List<String> vpcSecurityGroupIds, Boolean multiAZ) {
        return modifyCluster(clusterIdentifier, nodeType, numberOfNodes, masterUserPassword, clusterParameterGroupName,
                vpcSecurityGroupIds, multiAZ, null);
    }

    public Cluster modifyCluster(String clusterIdentifier, String nodeType, Integer numberOfNodes,
                                 String masterUserPassword, String clusterParameterGroupName,
                                 List<String> vpcSecurityGroupIds, Boolean multiAZ, Integer automatedRetention) {
        return withResourceLock("cluster", clusterIdentifier, () -> {
            Cluster cluster = mutate(() -> modifyClusterLocked(clusterIdentifier, nodeType,
                    numberOfNodes, masterUserPassword, clusterParameterGroupName, vpcSecurityGroupIds, multiAZ,
                    automatedRetention));
            if (automatedRetention != null) {
                expireAutomatedSnapshots(clusters.accountId(), clusterIdentifier, cluster, Instant.now());
            }
            return cluster;
        });
    }

    private Cluster modifyClusterLocked(String clusterIdentifier, String nodeType, Integer numberOfNodes,
                                               String masterUserPassword, String clusterParameterGroupName,
                                               List<String> vpcSecurityGroupIds, Boolean multiAZ,
                                               Integer automatedRetention) {
        Cluster cluster = clusters.get(clusterIdentifier)
                .orElseThrow(() -> new AwsException("ClusterNotFound", "Cluster " + clusterIdentifier + " not found", 404));
        rejectPausedCluster(cluster);
        validateAutomatedRetention(nodeType == null ? cluster.getNodeType() : nodeType,
                automatedRetention == null ? cluster.getAutomatedSnapshotRetentionPeriod() : automatedRetention);

        boolean passwordChanged = masterUserPassword != null && !masterUserPassword.isBlank();
        boolean parameterGroupChanged = clusterParameterGroupName != null && !clusterParameterGroupName.isBlank();
        boolean securityGroupsChanged = vpcSecurityGroupIds != null && !vpcSecurityGroupIds.isEmpty();
        // alterUserPassword runs before any mutation of the cluster object (a live reference
        // from HybridStorage, not a copy); if it throws, no metadata has been changed yet.
        if (passwordChanged) {
            containerManager.alterUserPassword(clusters.accountId(), clusterIdentifier,
                    cluster.getMasterUsername(), masterUserPassword);
            cluster.setMasterPassword(masterUserPassword);
            // Keep the proxy's password check in sync so new connections use the new secret.
            proxyManager.updateMasterPassword(
                    relayKey(clusters.accountId(), clusterIdentifier), masterUserPassword);
            updateManagedMasterSecret(cluster, masterUserPassword);
        }

        // NodeType only updates metadata: it does not resize the underlying Postgres container
        // (Redshift node-count has no equivalent here). NumberOfNodes is accepted for API-shape
        // compatibility but is not modelled or stored anywhere: known gap, see plan Task 9.
        if (nodeType != null && !nodeType.isBlank()) {
            cluster.setNodeType(nodeType);
        }
        if (clusterParameterGroupName != null && !clusterParameterGroupName.isBlank()) {
            cluster.setClusterParameterGroupName(clusterParameterGroupName);
            cluster.setUserActivityLoggingEnabled(userActivityLoggingEnabled(clusterParameterGroupName));
        }
        if (vpcSecurityGroupIds != null && !vpcSecurityGroupIds.isEmpty()) {
            cluster.setVpcSecurityGroupIds(vpcSecurityGroupIds);
        }
        if (multiAZ != null) {
            cluster.setMultiAZ(multiAZ);
        }
        if (automatedRetention != null) {
            if (cluster.getAutomatedSnapshotRetentionPeriod() == 0 && automatedRetention > 0) {
                cluster.setAutomatedSnapshotAnchor(Instant.now());
            }
            cluster.setAutomatedSnapshotRetentionPeriod(automatedRetention);
        }

        clusters.put(clusterIdentifier, cluster);
        clusters.flush();
        registerAuditLogging(clusters.accountId(), cluster);
        if (passwordChanged) {
            emitEvent(RedshiftEventCatalog.ADMIN_CREDENTIALS_UPDATED, clusterIdentifier,
                    "Your admin credentials for your Amazon Redshift cluster: " + clusterIdentifier
                            + " were updated at " + eventTime() + ".");
        }
        if (parameterGroupChanged) {
            emitEvent(RedshiftEventCatalog.CLUSTER_PARAMETER_GROUP_CHANGED, clusterIdentifier,
                    "Your Amazon Redshift cluster " + clusterIdentifier + " was modified to use parameter group "
                            + clusterParameterGroupName + " at " + eventTime() + ".");
        }
        if (securityGroupsChanged) {
            emitEvent(RedshiftEventCatalog.CLUSTER_SECURITY_GROUPS_UPDATED, clusterIdentifier,
                    "VPC security groups for cluster " + clusterIdentifier + " updated at " + eventTime() + ".");
        }
        return cluster;
    }

    public Cluster modifyClusterIamRoles(String clusterIdentifier, List<String> addIamRoles,
                                                      List<String> removeIamRoles) {
        return modifyClusterIamRoles(clusterIdentifier, addIamRoles, removeIamRoles, null);
    }

    public Cluster modifyClusterIamRoles(String clusterIdentifier, List<String> addIamRoles,
                                                      List<String> removeIamRoles, String defaultIamRoleArn) {
        return mutateCluster(clusterIdentifier, () -> modifyClusterIamRolesLocked(clusterIdentifier,
                addIamRoles, removeIamRoles, defaultIamRoleArn));
    }

    private Cluster modifyClusterIamRolesLocked(String clusterIdentifier, List<String> addIamRoles,
                                                      List<String> removeIamRoles, String defaultIamRoleArn) {
        Cluster cluster = clusters.get(clusterIdentifier)
                .orElseThrow(() -> new AwsException("ClusterNotFound", "Cluster " + clusterIdentifier + " not found", 404));

        // Validate every ARN before mutating: the cluster object is a live reference from HybridStorage.
        addIamRoles.forEach(RedshiftService::requireIamRoleArn);
        removeIamRoles.forEach(RedshiftService::requireIamRoleArn);

        Set<String> roles = new LinkedHashSet<>(cluster.getIamRoleArns());
        roles.addAll(addIamRoles);
        roles.removeAll(removeIamRoles);
        String defaultRole = defaultIamRoleArn == null ? cluster.getDefaultIamRoleArn() : defaultIamRoleArn;
        if (defaultIamRoleArn == null && !roles.contains(defaultRole)) {
            defaultRole = null;
        }
        defaultRole = validateDefaultIamRole(defaultRole, List.copyOf(roles));
        cluster.setIamRoleArns(List.copyOf(roles));
        cluster.setDefaultIamRoleArn(defaultRole);

        proxyManager.updateIamRoles(relayKey(clusters.accountId(), clusterIdentifier), cluster.getIamRoleArns(), defaultRole);
        clusters.put(clusterIdentifier, cluster);
        clusters.flush();
        registerAuditLogging(clusters.accountId(), cluster);
        return cluster;
    }

    private static String validateDefaultIamRole(String defaultIamRoleArn, List<String> iamRoleArns) {
        if (defaultIamRoleArn == null || defaultIamRoleArn.isBlank()) {
            return null;
        }
        requireIamRoleArn(defaultIamRoleArn);
        if (!iamRoleArns.contains(defaultIamRoleArn)) {
            throw new AwsException("InvalidParameterValue",
                    "Default IAM role must be associated with the cluster: " + defaultIamRoleArn, 400);
        }
        return defaultIamRoleArn;
    }

    private static void requireIamRoleArn(String arn) {
        AwsArnUtils.Arn parsed;
        try {
            parsed = AwsArnUtils.parse(arn);
        } catch (IllegalArgumentException e) {
            throw new AwsException("InvalidParameterValue", "Invalid IAM role ARN: " + arn, 400);
        }
        if (!"iam".equals(parsed.service()) || !parsed.resource().startsWith("role/")) {
            throw new AwsException("InvalidParameterValue", "Not an IAM role ARN: " + arn, 400);
        }
    }

    private void updateManagedMasterSecret(Cluster cluster, String password) {
        if (cluster.getMasterPasswordSecretArn() == null || secretsManagerService == null) {
            return;
        }
        secretsManagerService.putSecretValue(cluster.getMasterPasswordSecretArn(),
                        managedMasterSecret(cluster, password), null, null, regionResolver.getRegion(),
                        List.of("AWSCURRENT"));
    }

    private String managedMasterSecret(Cluster cluster, String password) {
        try {
            return objectMapper.createObjectNode()
                    .put("engine", "redshift")
                    .put("username", cluster.getMasterUsername())
                    .put("password", password)
                    .put("host", cluster.getEndpoint() == null ? "" : cluster.getEndpoint().getAddress())
                    .put("port", cluster.getEndpoint() == null ? 0 : cluster.getEndpoint().getPort())
                    .put("dbname", CLUSTER_DB_NAME)
                    .toString();
        } catch (RuntimeException e) {
            throw new AwsException("InternalFailure", "Failed to encode managed master secret", 500);
        }
    }

    @Override
    public boolean supports(String secretArn, String region) {
        if (!AwsArnUtils.isArn(secretArn) || !region.equals(AwsArnUtils.parse(secretArn).region())) {
            return false;
        }
        return clusters.scanAllAccountEntries(key -> true).stream()
                .anyMatch(entry -> secretArn.equals(entry.value().getMasterPasswordSecretArn()));
    }

    @Override
    public void rotate(String secretArn, String region, ManagedSecretRotator.Rotation rotation) {
        AccountAwareStorageBackend.AccountEntry<Cluster> entry = clusters.scanAllAccountEntries(key -> true).stream()
                .filter(candidate -> secretArn.equals(candidate.value().getMasterPasswordSecretArn()))
                .findFirst()
                .orElseThrow(() -> new AwsException("ResourceNotFoundException",
                        "The Redshift cluster for this managed secret no longer exists.", 400));
        withResourceLock(entry.accountId(), "cluster", entry.key(), () -> mutate(() -> {
            Cluster cluster = clusters.getForAccount(entry.accountId(), entry.key())
                    .orElseThrow(() -> new AwsException("ResourceNotFoundException",
                            "The Redshift cluster for this managed secret no longer exists.", 400));
            if (!secretArn.equals(cluster.getMasterPasswordSecretArn())) {
                throw new AwsException("ResourceNotFoundException",
                        "The Redshift cluster for this managed secret no longer exists.", 400);
            }
            if (!"available".equals(cluster.getClusterStatus())) {
                throw new AwsException("InvalidRequestException",
                        "The Redshift cluster must be available to rotate its master password.", 400);
            }
            String oldPassword = cluster.getMasterPassword();
            String newPassword = RandomPasswordGenerator.generate(objectMapper.createObjectNode()
                    .put("ExcludeCharacters", "'\"/@\\"));
            rotation.commit(() -> {
                containerManager.alterUserPassword(entry.accountId(), cluster.getClusterIdentifier(),
                        cluster.getMasterUsername(), newPassword);
                proxyManager.updateMasterPassword(
                        relayKey(entry.accountId(), cluster.getClusterIdentifier()), newPassword);
                cluster.setMasterPassword(newPassword);
                clusters.putForAccount(entry.accountId(), cluster.getClusterIdentifier(), cluster);
                clusters.flush();
                return managedMasterSecret(cluster, newPassword);
            }, () -> {
                containerManager.alterUserPassword(entry.accountId(), cluster.getClusterIdentifier(),
                        cluster.getMasterUsername(), oldPassword);
                proxyManager.updateMasterPassword(
                        relayKey(entry.accountId(), cluster.getClusterIdentifier()), oldPassword);
                cluster.setMasterPassword(oldPassword);
                clusters.putForAccount(entry.accountId(), cluster.getClusterIdentifier(), cluster);
                clusters.flush();
            });
            return null;
        }));
    }

    @Override
    public void reserve(String secretArn, String region) {
        AccountAwareStorageBackend.AccountEntry<Cluster> entry = clusters.scanAllAccountEntries(key -> true).stream()
                .filter(candidate -> secretArn.equals(candidate.value().getMasterPasswordSecretArn()))
                .findFirst()
                .orElseThrow(() -> new AwsException("ResourceNotFoundException",
                        "The Redshift cluster for this managed secret no longer exists.", 400));
        ResourceKey key = new ResourceKey(entry.accountId(), "cluster", entry.key());
        withResourceLock(entry.accountId(), "cluster", entry.key(), () -> {
            Cluster cluster = clusters.getForAccount(entry.accountId(), entry.key())
                    .orElseThrow(() -> new AwsException("ResourceNotFoundException",
                            "The Redshift cluster for this managed secret no longer exists.", 400));
            if (!secretArn.equals(cluster.getMasterPasswordSecretArn())) {
                throw new AwsException("ResourceNotFoundException",
                        "The Redshift cluster for this managed secret no longer exists.", 400);
            }
            if (!"available".equals(cluster.getClusterStatus())) {
                throw new AwsException("InvalidRequestException",
                        "The Redshift cluster must be available to rotate its master password.", 400);
            }
            if (managedRotationReservations.putIfAbsent(secretArn, key) != null) {
                throw new AwsException("InvalidRequestException",
                        "The cluster's managed master password is already being rotated.", 400);
            }
            return null;
        });
    }

    @Override
    public void release(String secretArn, String region) {
        ResourceKey key = managedRotationReservations.get(secretArn);
        if (key != null) {
            withResourceLock(key.accountId(), key.kind(), key.identifier(), () -> {
                managedRotationReservations.remove(secretArn, key);
                return null;
            });
        }
    }

    public Cluster describeLoggingStatus(String clusterIdentifier) {
        return clusters.get(clusterIdentifier)
                .orElseThrow(() -> new AwsException("ClusterNotFound", "Cluster " + clusterIdentifier + " not found", 404));
    }

    public Cluster enableLogging(String clusterIdentifier, String bucketName, String s3KeyPrefix,
                                              String logDestinationType, List<String> logExports) {
        return enableLogging(clusterIdentifier, bucketName, s3KeyPrefix, logDestinationType, logExports, null, null);
    }

    public Cluster enableLogging(String clusterIdentifier, String bucketName, String s3KeyPrefix,
                                              String logDestinationType, List<String> logExports,
                                              String s3TableKmsKeyId, String s3TableGranularity) {
        return mutateCluster(clusterIdentifier, () -> enableLoggingLocked(clusterIdentifier, bucketName,
                s3KeyPrefix, logDestinationType, logExports, s3TableKmsKeyId, s3TableGranularity));
    }

    private Cluster enableLoggingLocked(String clusterIdentifier, String bucketName, String s3KeyPrefix,
                                              String logDestinationType, List<String> logExports,
                                              String s3TableKmsKeyId, String s3TableGranularity) {
        Cluster cluster = clusters.get(clusterIdentifier)
                .orElseThrow(() -> new AwsException("ClusterNotFound", "Cluster " + clusterIdentifier + " not found", 404));
        boolean cloudWatch = "cloudwatch".equalsIgnoreCase(logDestinationType);
        boolean s3Table = "s3table".equalsIgnoreCase(logDestinationType);
        if (s3Table && s3TableGranularity != null
                && !List.of("cluster", "account").contains(s3TableGranularity)) {
            throw new AwsException("InvalidParameterValue",
                    "S3TableGranularity must be cluster or account", 400);
        }
        if (!s3Table && (s3TableKmsKeyId != null || s3TableGranularity != null)) {
            throw new AwsException("InvalidParameterCombination",
                    "S3-table logging settings are valid only when LogDestinationType is s3table", 400);
        }
        if (!cloudWatch && !s3Table && (bucketName == null || bucketName.isBlank())) {
            throw new AwsException("InvalidParameterValue", "BucketName is required for an S3 log destination", 400);
        }
        cluster.setLoggingEnabled(true);
        cluster.setLoggingBucketName(bucketName);
        cluster.setLoggingDestinationType(logDestinationType);
        cluster.setLoggingRegion(regionResolver.getRegion());
        cluster.setLoggingExports(logExports == null || logExports.isEmpty() ? null : List.copyOf(logExports));
        cluster.setLoggingS3KeyPrefix(s3KeyPrefix);
        cluster.setLoggingS3TableKmsKeyId(s3TableKmsKeyId);
        cluster.setLoggingS3TableGranularity(s3TableGranularity);
        clusters.put(clusterIdentifier, cluster);
        clusters.flush();
        registerAuditLogging(clusters.accountId(), cluster);
        return cluster;
    }

    public Cluster disableLogging(String clusterIdentifier) {
        return mutateCluster(clusterIdentifier, () -> disableLoggingLocked(clusterIdentifier));
    }

    private Cluster disableLoggingLocked(String clusterIdentifier) {
        Cluster cluster = clusters.get(clusterIdentifier)
                .orElseThrow(() -> new AwsException("ClusterNotFound", "Cluster " + clusterIdentifier + " not found", 404));
        cluster.setLoggingEnabled(false);
        cluster.setLoggingBucketName(null);
        cluster.setLoggingS3KeyPrefix(null);
        cluster.setLoggingDestinationType(null);
        cluster.setLoggingRegion(null);
        cluster.setLoggingExports(null);
        cluster.setLoggingS3TableKmsKeyId(null);
        cluster.setLoggingS3TableGranularity(null);
        clusters.put(clusterIdentifier, cluster);
        clusters.flush();
        registerAuditLogging(clusters.accountId(), cluster);
        return cluster;
    }

    public Cluster pauseCluster(String clusterIdentifier) {
        return mutateCluster(clusterIdentifier, () -> {
            Cluster cluster = clusterInState(clusterIdentifier, "available");
            if (cluster.getAutomatedSnapshotRetentionPeriod() == 0) {
                throw new AwsException("InvalidClusterState",
                        "Clusters with automated snapshots disabled cannot be paused", 400);
            }
            String accountId = clusters.accountId();
            try {
                proxyManager.stopProxy(relayKey(accountId, clusterIdentifier));
            } catch (RuntimeException failure) {
                LOG.warnv(failure, "Failed to stop auth proxy while pausing cluster {0}", clusterIdentifier);
                persistClusterWithoutBackend(cluster, "unavailable");
                throw new AwsException("InternalFailure", "Failed to stop auth proxy for cluster "
                        + clusterIdentifier, 500);
            }
            try {
                containerManager.pause(accountId, clusterIdentifier);
                persistClusterWithoutBackend(cluster, "paused");
                return cluster;
            } catch (RuntimeException failure) {
                boolean restored = false;
                try {
                    RedshiftContainerHandle handle = containerManager.resume(accountId, clusterIdentifier,
                            cluster.getMasterUsername(), CLUSTER_DB_NAME);
                    startResumedProxy(accountId, cluster, handle);
                    cluster.setClusterStatus("available");
                    restored = true;
                } catch (RuntimeException rollbackFailure) {
                    LOG.warnv(rollbackFailure, "Failed to restore cluster {0} after unsuccessful pause", clusterIdentifier);
                }
                if (restored) {
                    clusters.put(clusterIdentifier, cluster);
                    clusters.flush();
                } else {
                    persistClusterWithoutBackend(cluster, "unavailable");
                }
                LOG.warnv(failure, "Failed to pause Redshift cluster {0}", clusterIdentifier);
                throw new AwsException("InternalFailure", "Failed to pause cluster " + clusterIdentifier, 500);
            }
        });
    }

    public Cluster resumeCluster(String clusterIdentifier) {
        return mutateCluster(clusterIdentifier, () -> {
            Cluster cluster = clusterInState(clusterIdentifier, "paused");
            String accountId = clusters.accountId();
            try {
                RedshiftContainerHandle handle = containerManager.resume(accountId, clusterIdentifier,
                        cluster.getMasterUsername(), CLUSTER_DB_NAME);
                startResumedProxy(accountId, cluster, handle);
                cluster.setClusterStatus("available");
                clusters.put(clusterIdentifier, cluster);
                clusters.flush();
                registerAuditLogging(accountId, cluster);
                return cluster;
            } catch (RuntimeException failure) {
                cluster.setClusterStatus("paused");
                try {
                    proxyManager.stopProxy(relayKey(accountId, clusterIdentifier));
                } catch (RuntimeException rollbackFailure) {
                    LOG.warnv(rollbackFailure, "Failed to stop proxy during resume rollback for cluster {0}", clusterIdentifier);
                    cluster.setClusterStatus("unavailable");
                }
                try {
                    containerManager.pause(accountId, clusterIdentifier);
                } catch (RuntimeException rollbackFailure) {
                    LOG.warnv(rollbackFailure, "Failed to return cluster {0} to paused state", clusterIdentifier);
                    cluster.setClusterStatus("unavailable");
                }
                persistClusterWithoutBackend(cluster, cluster.getClusterStatus());
                if (failure instanceof AwsException awsFailure) {
                    throw awsFailure;
                }
                LOG.warnv(failure, "Failed to resume Redshift cluster {0}", clusterIdentifier);
                throw new AwsException("InternalFailure", "Failed to resume cluster " + clusterIdentifier, 500);
            }
        });
    }

    private void persistClusterWithoutBackend(Cluster cluster, String status) {
        cluster.setClusterStatus(status);
        cluster.setContainerHost(null);
        cluster.setContainerPort(0);
        clusters.put(cluster.getClusterIdentifier(), cluster);
        clusters.flush();
    }

    private Cluster clusterInState(String identifier, String requiredState) {
        Cluster cluster = clusters.get(identifier).orElseThrow(() ->
                new AwsException("ClusterNotFound", "Cluster " + identifier + " not found", 404));
        if (!requiredState.equals(cluster.getClusterStatus())) {
            throw new AwsException("InvalidClusterState", "Cluster " + identifier
                    + " must be " + requiredState + " for this operation", 400);
        }
        return cluster;
    }

    private static void rejectPausedCluster(Cluster cluster) {
        if ("paused".equals(cluster.getClusterStatus())) {
            throw new AwsException("InvalidClusterState", "Cluster " + cluster.getClusterIdentifier()
                    + " must be resumed before this operation", 400);
        }
    }

    private void startResumedProxy(String accountId, Cluster cluster, RedshiftContainerHandle handle) {
        int port = cluster.getProxyPort() > 0 ? cluster.getProxyPort() : allocateProxyPort();
        usedPorts.add(port);
        cluster.setProxyPort(port);
        Endpoint endpoint = cluster.getEndpoint() != null ? cluster.getEndpoint() : proxyEndpoint(port);
        String password = cluster.getMasterPassword() != null ? cluster.getMasterPassword() : "admin";
        proxyManager.startProxy(relayKey(accountId, cluster.getClusterIdentifier()), port,
                handle.getHost(), handle.getPort(), endpoint.getAddress(), cluster.getMasterUsername(), password,
                CLUSTER_DB_NAME, passwordValidatorFor(accountId, cluster.getClusterIdentifier()),
                cluster.getIamRoleArns(), cluster.getDefaultIamRoleArn());
        cluster.setContainerHost(handle.getHost());
        cluster.setContainerPort(handle.getPort());
        cluster.setEndpoint(endpoint);
    }

    public Cluster rebootCluster(String clusterIdentifier) {
        return mutateCluster(clusterIdentifier, () -> rebootClusterLocked(clusterIdentifier));
    }

    private Cluster rebootClusterLocked(String clusterIdentifier) {
        Cluster cluster = clusters.get(clusterIdentifier)
                .orElseThrow(() -> new AwsException("ClusterNotFound", "Cluster " + clusterIdentifier + " not found", 404));
        rejectPausedCluster(cluster);

        // Replacement containers do not reattach the old anonymous volume, so a plain stop+recreate
        // would silently drop the cluster's data. Dump before stopping and
        // restore immediately after starting, using a throwaway temp file, so no Snapshot resource is
        // created or exposed to the caller.
        Path tempDump;
        try {
            tempDump = Files.createTempFile("redshift-reboot-" + clusterIdentifier, ".sql");
        } catch (IOException e) {
            throw new AwsException("InternalFailure", "Failed to prepare reboot dump file: " + e.getMessage(), 500);
        }

        // Hoisted so a failure after the proxy is (re)started still tears it down and
        // returns the port, matching createCluster/restoreFromClusterSnapshot rollback.
        int proxyPort = cluster.getProxyPort() > 0 ? cluster.getProxyPort() : -1;
        boolean teardownStarted = false;
        boolean containerTeardownStarted = false;
        boolean rebooted = false;
        try {
            String accountId = clusters.accountId();
            String key = relayKey(accountId, clusterIdentifier);

            containerManager.takeSnapshot(accountId, clusterIdentifier, cluster.getMasterUsername(), tempDump);
            teardownStarted = true;
            proxyManager.stopProxy(key);
            containerTeardownStarted = true;
            containerManager.stop(accountId, clusterIdentifier);

            String password = cluster.getMasterPassword() != null ? cluster.getMasterPassword() : "admin";
            RedshiftContainerHandle handle = containerManager.start(
                    accountId, clusterIdentifier, cluster.getMasterUsername(), password,
                    CLUSTER_DB_NAME, cluster.getNodeType());

            // Reuse the stored proxy port so the advertised endpoint is unchanged by a reboot.
            if (proxyPort < 0) {
                proxyPort = allocateProxyPort();
            }
            usedPorts.add(proxyPort);
            Endpoint endpoint = proxyEndpoint(proxyPort);
            cluster.setProxyPort(proxyPort);
            proxyManager.startProxy(key, proxyPort, handle.getHost(), handle.getPort(),
                    endpoint.getAddress(), cluster.getMasterUsername(), password, CLUSTER_DB_NAME,
                    passwordValidatorFor(accountId, clusterIdentifier), cluster.getIamRoleArns(), cluster.getDefaultIamRoleArn());
            cluster.setContainerHost(handle.getHost());
            cluster.setContainerPort(handle.getPort());
            cluster.setEndpoint(endpoint);

            containerManager.restoreSnapshot(accountId, clusterIdentifier, cluster.getMasterUsername(), tempDump);
            cluster.setClusterStatus("available");
            rebooted = true;
        } catch (AwsException e) {
            rollbackReboot(clusterIdentifier, containerTeardownStarted);
            if (teardownStarted) {
                cluster.setClusterStatus("failed");
            }
            clusters.flush();
            throw e;
        } catch (Exception e) {
            rollbackReboot(clusterIdentifier, containerTeardownStarted);
            if (teardownStarted) {
                cluster.setClusterStatus("failed");
            }
            clusters.flush();
            throw new AwsException("InternalFailure", "Failed to reboot cluster " + clusterIdentifier + ": " + e.getMessage(), 500);
        } finally {
            if (rebooted) {
                try {
                    Files.deleteIfExists(tempDump);
                } catch (IOException ex) {
                    LOG.warnv(ex, "Failed to clean up temporary dump file {0} after rebooting cluster {1}", tempDump, clusterIdentifier);
                }
            } else {
                if (teardownStarted) {
                    // Teardown may have removed the original container before failing. Keep the
                    // dump for manual recovery even when removal or log closure did not finish.
                    LOG.warnv("Reboot of cluster {0} did not complete; retained pre-reboot data dump at {1}",
                            clusterIdentifier, tempDump);
                } else {
                    try {
                        Files.deleteIfExists(tempDump);
                    } catch (IOException ex) {
                        LOG.warnv(ex, "Failed to clean up temporary dump file {0} after aborted reboot of cluster {1}", tempDump, clusterIdentifier);
                    }
                }
            }
        }

        clusters.put(clusterIdentifier, cluster);
        clusters.flush();
        emitEvent(RedshiftEventCatalog.CLUSTER_REBOOTED, clusterIdentifier, "Your Amazon Redshift cluster "
                + clusterIdentifier + " was rebooted at " + eventTime() + ".");
        return cluster;
    }

    // ── Snapshot Operations ──────────────────────────────────────────────────

    // AWS constrains a snapshot identifier to 1-255 characters, first a letter, then
    // letters, digits or single hyphens (no trailing or doubled hyphen). Enforcing that
    // here also keeps the value safe to splice into the dump file path below: '.', '/'
    // and '\' can never appear, so it cannot escape the account's dump directory.
    private static void validateSnapshotIdentifier(String id) {
        if (id == null || !id.matches("[a-zA-Z][a-zA-Z0-9-]{0,254}")
                || id.contains("--") || id.endsWith("-")) {
            throw new AwsException("InvalidParameterValue",
                    "SnapshotIdentifier must be 1-255 characters, start with a letter, and contain "
                    + "only letters, digits and non-consecutive hyphens", 400);
        }
    }

    /** Absolute, normalised {@code <persistentPath>/redshift-dumps/<accountId>} for this request's account. */
    private Path accountDumpDir() {
        return accountDumpDir(clusters.accountId());
    }

    private Path accountDumpDir(String accountId) {
        return Paths.get(config.storage().persistentPath())
                .resolve("redshift-dumps")
                .resolve(accountId)
                .toAbsolutePath()
                .normalize();
    }

    /**
     * A stored {@code sqlDump} path is trusted only if it still resolves inside this account's
     * dump directory. Guards restore/delete against a path persisted by an older, unvalidated
     * createSnapshot (or by a different account).
     */
    private boolean isTrustedDumpPath(String storedPath) {
        if (storedPath == null || storedPath.isBlank()) {
            return false;
        }
        Path dir = accountDumpDir();
        return Paths.get(storedPath).toAbsolutePath().normalize().startsWith(dir);
    }

    public Snapshot createSnapshot(String snapshotIdentifier, String clusterIdentifier) {
        return withResourceLock("cluster", clusterIdentifier, () -> withResourceLock("snapshot", snapshotIdentifier,
                () -> createSnapshot(snapshotIdentifier, clusterIdentifier, null)));
    }

    private Snapshot createSnapshot(String snapshotIdentifier, String clusterIdentifier, Integer retentionPeriod) {
        validateSnapshotIdentifier(snapshotIdentifier);
        Optional<Cluster> clusterOpt = clusters.get(clusterIdentifier);
        if (clusterOpt.isEmpty()) {
            throw new AwsException("ClusterNotFound", "Cluster " + clusterIdentifier + " not found", 404);
        }
        return createSnapshot(clusters.accountId(), snapshotIdentifier, clusterOpt.get(), retentionPeriod,
                Instant.now(), null);
    }

    private Snapshot createSnapshot(String accountId, String snapshotIdentifier, Cluster cluster,
                                     Integer retentionPeriod, Instant now, Instant scheduledTime) {
        rejectPausedCluster(cluster);
        validateSnapshotIdentifier(snapshotIdentifier);
        String clusterIdentifier = cluster.getClusterIdentifier();
        if ((scheduledTime == null ? snapshots.get(snapshotIdentifier)
                : snapshots.getForAccount(accountId, snapshotIdentifier)).isPresent()) {
            throw new AwsException("ClusterSnapshotAlreadyExists", "Snapshot " + snapshotIdentifier + " already exists", 400);
        }

        Snapshot snapshot = new Snapshot();
        snapshot.setSnapshotIdentifier(snapshotIdentifier);
        snapshot.setClusterIdentifier(clusterIdentifier);
        snapshot.setStatus("available");
        snapshot.setMasterUsername(cluster.getMasterUsername());
        snapshot.setMasterPassword(cluster.getMasterPassword());
        snapshot.setSnapshotArn(scheduledTime == null ? snapshotArn(clusterIdentifier, snapshotIdentifier)
                : AwsArnUtils.Arn.of("redshift", snapshotRegion(cluster), accountId,
                        "snapshot:" + clusterIdentifier + "/" + snapshotIdentifier).toString());
        snapshot.setSnapshotCreateTime(now);
        snapshot.setManualSnapshotRetentionPeriod(retentionPeriod);
        snapshot.setSnapshotType(scheduledTime == null ? "manual" : "automated");
        snapshot.setScheduledTime(scheduledTime);
        snapshot.setClusterGeneration(cluster.getSnapshotGeneration());
        if (scheduledTime != null) {
            snapshot.setAutomatedSnapshotRetentionPeriod(cluster.getAutomatedSnapshotRetentionPeriod());
        }
        if (cluster.getEndpoint() != null) {
            snapshot.setPort(cluster.getEndpoint().getPort());
        } else {
            snapshot.setPort(5439);
        }

        Path dumpDir = accountDumpDir(accountId);
        // Defence in depth: validateSnapshotIdentifier already rejects path separators,
        // but keep the containment check so the dump can never land outside the account dir.
        Path dumpFile = dumpDir.resolve(snapshotIdentifier + ".sql").normalize();
        if (!dumpFile.startsWith(dumpDir)) {
            throw new AwsException("InvalidParameterValue", "Invalid snapshot identifier", 400);
        }
        boolean finalSnapshot = retentionPeriod != null;
        String eventRegion = scheduledTime == null ? regionResolver.getRegion() : snapshotRegion(cluster);
        emitSnapshotEvent(accountId, eventRegion,
                finalSnapshot ? RedshiftEventCatalog.FINAL_SNAPSHOT_STARTED : RedshiftEventCatalog.SNAPSHOT_STARTED,
                snapshotIdentifier, (finalSnapshot ? "The final snapshot '" : "A user snapshot '") + snapshotIdentifier
                        + "' for Amazon Redshift cluster '" + clusterIdentifier + "' started at " + eventTime() + ".");
        boolean committed = false;
        try {
            Files.createDirectories(dumpDir);
            containerManager.takeSnapshot(accountId, clusterIdentifier, cluster.getMasterUsername(), dumpFile);
            snapshot.setSqlDump(dumpFile.toString());
            if (scheduledTime == null) {
                snapshots.putAllAtomically(Map.of(snapshotIdentifier, snapshot));
            } else {
                snapshots.putAllAtomicallyForAccount(accountId, Map.of(snapshotIdentifier, snapshot));
            }
            committed = true;
            snapshots.flush();
        } catch (Exception e) {
            if (!committed) {
                try {
                    Files.deleteIfExists(dumpFile);
                } catch (IOException cleanupFailure) {
                    e.addSuppressed(cleanupFailure);
                }
            }
            emitSnapshotEvent(accountId, eventRegion,
                    finalSnapshot ? RedshiftEventCatalog.FINAL_SNAPSHOT_FAILED : RedshiftEventCatalog.SNAPSHOT_FAILED,
                    snapshotIdentifier, (finalSnapshot ? "The final snapshot '" : "The user snapshot '")
                            + snapshotIdentifier + "' for your Amazon Redshift cluster '" + clusterIdentifier
                            + "' failed at " + eventTime() + ".");
            if (e instanceof AwsException awsFailure) {
                throw awsFailure;
            }
            AwsException failure = new AwsException("InternalFailure",
                    "Failed to take snapshot for cluster " + clusterIdentifier + ": " + e.getMessage(), 500);
            failure.initCause(e);
            throw failure;
        }

        emitSnapshotEvent(accountId, eventRegion,
                finalSnapshot ? RedshiftEventCatalog.FINAL_SNAPSHOT_COMPLETED : RedshiftEventCatalog.SNAPSHOT_COMPLETED,
                snapshotIdentifier, (finalSnapshot ? "The final snapshot '" : "The user snapshot '")
                        + snapshotIdentifier + "' for your Amazon Redshift cluster '" + clusterIdentifier
                        + "' completed successfully at " + eventTime() + ".");
        return snapshot;
    }

    public List<Snapshot> describeSnapshots(String snapshotIdentifier, String clusterIdentifier) {
        if (snapshotIdentifier != null && !snapshotIdentifier.isBlank()) {
            Optional<Snapshot> snapshot = snapshots.get(snapshotIdentifier);
            if (snapshot.isEmpty()) {
                throw new AwsException("ClusterSnapshotNotFound", "Snapshot " + snapshotIdentifier + " not found", 404);
            }
            return List.of(snapshot.get());
        }
        if (clusterIdentifier != null && !clusterIdentifier.isBlank()) {
            return snapshots.scan(k -> true).stream()
                    .filter(s -> clusterIdentifier.equals(s.getClusterIdentifier()))
                    .toList();
        }
        return snapshots.scan(k -> true);
    }

    public List<Snapshot> describeSnapshots(String snapshotIdentifier) {
        return describeSnapshots(snapshotIdentifier, null);
    }

    public Optional<Snapshot> getSnapshot(String snapshotIdentifier) {
        return snapshots.get(snapshotIdentifier);
    }

    public Snapshot deleteSnapshot(String snapshotIdentifier) {
        return withResourceLock("snapshot", snapshotIdentifier, () -> mutate(() -> removeSnapshot(snapshotIdentifier)));
    }

    private Snapshot removeSnapshot(String snapshotIdentifier) {
        Optional<Snapshot> snapshotOpt = snapshots.get(snapshotIdentifier);
        if (snapshotOpt.isEmpty()) {
            throw new AwsException("ClusterSnapshotNotFound", "Snapshot " + snapshotIdentifier + " not found", 404);
        }
        Snapshot snapshot = snapshotOpt.get();
        if ("automated".equals(snapshot.getSnapshotType())) {
            throw new AwsException("InvalidClusterSnapshotState", "Automated snapshots cannot be deleted manually", 400);
        }
        snapshots.delete(snapshotIdentifier);
        snapshots.flush();
        if (isTrustedDumpPath(snapshot.getSqlDump())) {
            try {
                Files.deleteIfExists(Paths.get(snapshot.getSqlDump()));
            } catch (IOException e) {
                LOG.warnv(e, "Could not delete the dump file of snapshot {0}", snapshotIdentifier);
            }
        }
        snapshot.setStatus("deleted");
        emitEvent(RedshiftEventCatalog.SNAPSHOT_DELETED, snapshotIdentifier, "The cluster snapshot '"
                + snapshotIdentifier + "' for Amazon Redshift cluster '" + snapshot.getClusterIdentifier()
                + "' was deleted at " + eventTime() + ".");
        return snapshot;
    }

    public Cluster restoreFromClusterSnapshot(String clusterIdentifier, String snapshotIdentifier) {
        return restoreFromClusterSnapshot(clusterIdentifier, snapshotIdentifier, null);
    }

    public Cluster restoreFromClusterSnapshot(String clusterIdentifier, String snapshotIdentifier, String nodeType) {
        return withResourceLock("cluster", clusterIdentifier, () -> {
            ensureNoManagedRotation(clusters.accountId(), clusterIdentifier);
            return withResourceLock("snapshot", snapshotIdentifier,
                    () -> mutate(() -> restoreSnapshot(clusterIdentifier, snapshotIdentifier, nodeType)));
        });
    }

    public Cluster restoreFromClusterSnapshot(String clusterIdentifier, String snapshotIdentifier, String nodeType,
                                             String clusterSubnetGroupName, boolean manageMasterPassword,
                                             String kmsKeyId, String region) {
        if (manageMasterPassword) {
            requireSecretsManager();
        }
        return withResourceLock("cluster", clusterIdentifier, () -> withResourceLock("snapshot", snapshotIdentifier,
                () -> {
                    ensureNoManagedRotation(clusters.accountId(), clusterIdentifier);
                    Cluster cluster = restoreSnapshot(clusterIdentifier, snapshotIdentifier, nodeType);
                    try {
                        cluster.setClusterSubnetGroupName(clusterSubnetGroupName);
                        if (manageMasterPassword) {
                            String password = RandomPasswordGenerator.generate(objectMapper.createObjectNode());
                            modifyCluster(clusterIdentifier, null, null, password, null, List.of());
                            return createManagedMasterSecret(cluster, kmsKeyId, region);
                        }
                        storeCluster(cluster);
                        return cluster;
                    } catch (RuntimeException failure) {
                        try {
                            deleteCluster(clusterIdentifier);
                        } catch (RuntimeException cleanupFailure) {
                            failure.addSuppressed(cleanupFailure);
                            LOG.warnv(cleanupFailure, "Failed to clean up restored cluster {0}", clusterIdentifier);
                        }
                        throw failure;
                    }
                }));
    }

    private void rollbackSnapshotRestore(Cluster cluster, int proxyPort) {
        String identifier = cluster.getClusterIdentifier();
        boolean proxyStopped = stopProxyAndReleasePortSafely(identifier, proxyPort);
        try {
            containerManager.stop(clusters.accountId(), identifier);
        } catch (Exception cleanupFailure) {
            LOG.warnv(cleanupFailure, "Failed to stop container during rollback of cluster {0}", identifier);
        }
        mutate(() -> {
            if (proxyStopped) {
                clusters.delete(identifier);
            } else {
                cluster.setClusterStatus("failed");
                clusters.put(identifier, cluster);
            }
            clusters.flush();
            return null;
        });
    }

    private Cluster restoreSnapshot(String clusterIdentifier, String snapshotIdentifier, String nodeType) {
        if (clusters.get(clusterIdentifier).isPresent()) {
            throw new AwsException("ClusterAlreadyExists", "Cluster " + clusterIdentifier + " already exists", 400);
        }

        Optional<Snapshot> snapshotOpt = snapshots.get(snapshotIdentifier);
        if (snapshotOpt.isEmpty()) {
            throw new AwsException("ClusterSnapshotNotFound", "Snapshot " + snapshotIdentifier + " not found", 404);
        }

        Snapshot snapshot = snapshotOpt.get();

        // Validate the stored dump location up front, before any provisioning. A snapshot whose
        // sqlDump was written by pre-validation code could point outside this account's dump dir;
        // rejecting it here (rather than mid-restore) avoids leaving a half-created cluster record
        // and an orphaned container behind.
        String sqlDump = snapshot.getSqlDump();
        boolean hasDump = sqlDump != null && !sqlDump.isBlank();
        if (hasDump && !isTrustedDumpPath(sqlDump)) {
            throw new AwsException("InvalidParameterValue",
                    "Snapshot " + snapshotIdentifier + " has an unusable dump location", 400);
        }

        String effectiveNodeType = (nodeType != null && !nodeType.isBlank()) ? nodeType : "dc2.large";
        String username = snapshot.getMasterUsername() != null ? snapshot.getMasterUsername() : "admin";
        String sourceCluster = snapshot.getClusterIdentifier();
        String password = Optional.ofNullable(snapshot.getMasterPassword())
                .filter(p -> !p.isBlank())
                .or(() -> clusters.get(sourceCluster).map(Cluster::getMasterPassword))
                .filter(p -> p != null && !p.isBlank())
                .orElse("admin");

        Cluster cluster = new Cluster();
        cluster.setClusterIdentifier(clusterIdentifier);
        cluster.setNodeType(effectiveNodeType);
        cluster.setAutomatedSnapshotAnchor(Instant.now());
        cluster.setSnapshotGeneration(UUID.randomUUID().toString());
        cluster.setSnapshotRegion(regionResolver.getRegion());
        cluster.setMasterUsername(username);
        cluster.setMasterPassword(password);
        cluster.setClusterParameterGroupName(DEFAULT_PARAMETER_GROUP_NAME);
        cluster.setUserActivityLoggingEnabled(userActivityLoggingEnabled(DEFAULT_PARAMETER_GROUP_NAME));
        cluster.setClusterStatus("creating");
        storeCluster(cluster);
        emitEvent(RedshiftEventCatalog.CLUSTER_RESTORE_STARTED, clusterIdentifier,
                "Your restore operation to create a new Amazon Redshift cluster '" + clusterIdentifier
                        + "' snapshot '" + snapshotIdentifier + "' was started at " + eventTime() + ".");

        // Hoisted out of the try so a failure after allocateProxyPort() still returns the port.
        int proxyPort = -1;
        try {
            String accountId = clusters.accountId();
            RedshiftContainerHandle handle = containerManager.start(
                    accountId, clusterIdentifier, username, password, CLUSTER_DB_NAME, cluster.getNodeType());
            proxyPort = allocateProxyPort();
            Endpoint endpoint = proxyEndpoint(proxyPort);
            cluster.setProxyPort(proxyPort);
            proxyManager.startProxy(relayKey(accountId, clusterIdentifier), proxyPort,
                    handle.getHost(), handle.getPort(), endpoint.getAddress(),
                    username, password, CLUSTER_DB_NAME,
                    passwordValidatorFor(accountId, clusterIdentifier), cluster.getIamRoleArns(), cluster.getDefaultIamRoleArn());
            cluster.setContainerHost(handle.getHost());
            cluster.setContainerPort(handle.getPort());
            cluster.setEndpoint(endpoint);

            if (hasDump) {
                containerManager.restoreSnapshot(clusters.accountId(), clusterIdentifier, username, Paths.get(sqlDump));
            }

            cluster.setClusterStatus("available");
        } catch (AwsException e) {
            rollbackSnapshotRestore(cluster, proxyPort);
            emitClusterRestoreFailure(clusterIdentifier, snapshotIdentifier);
            throw e;
        } catch (Exception e) {
            rollbackSnapshotRestore(cluster, proxyPort);
            emitClusterRestoreFailure(clusterIdentifier, snapshotIdentifier);
            throw new AwsException("InternalFailure", "Failed to restore cluster from snapshot: " + e.getMessage(), 500);
        }

        storeCluster(cluster);
        emitEvent(RedshiftEventCatalog.CLUSTER_RESTORED, clusterIdentifier, "Amazon Redshift cluster '"
                + clusterIdentifier + "' was successfully created from snapshot '" + snapshotIdentifier
                + "' and is available for use.");
        return cluster;
    }

    // ── Parameter Group Operations ───────────────────────────────────────────

    public ClusterParameterGroup createClusterParameterGroup(String parameterGroupName, String parameterGroupFamily, String description) {
        if (DEFAULT_PARAMETER_GROUP_NAME.equals(parameterGroupName)) {
            throw new AwsException("InvalidParameterValue", "The default parameter group name is reserved.", 400);
        }
        if (parameterGroups.get(parameterGroupName).isPresent()) {
            throw new AwsException("ClusterParameterGroupAlreadyExists", "Cluster parameter group " + parameterGroupName + " already exists", 400);
        }

        ClusterParameterGroup group = new ClusterParameterGroup(parameterGroupName, parameterGroupFamily, description);
        parameterGroups.put(parameterGroupName, group);
        parameterGroups.flush();
        emitEvent(RedshiftEventCatalog.PARAMETER_GROUP_CREATED, parameterGroupName,
                "Cluster parameter group '" + parameterGroupName + "' was created.");
        return group;
    }

    public List<ClusterParameterGroup> describeClusterParameterGroups(String parameterGroupName) {
        if (parameterGroupName != null && !parameterGroupName.isBlank()) {
            Optional<ClusterParameterGroup> group = parameterGroups.get(parameterGroupName);
            if (group.isEmpty() && DEFAULT_PARAMETER_GROUP_NAME.equals(parameterGroupName)) {
                return List.of(defaultParameterGroup());
            }
            if (group.isEmpty()) {
                throw new AwsException("ClusterParameterGroupNotFound", "Cluster parameter group " + parameterGroupName + " not found", 404);
            }
            return List.of(group.get());
        }
        List<ClusterParameterGroup> all = new ArrayList<>(parameterGroups.scan(k -> true));
        if (all.stream().noneMatch(g -> DEFAULT_PARAMETER_GROUP_NAME.equals(g.getParameterGroupName()))) {
            all.add(0, defaultParameterGroup());
        }
        return all;
    }

    // The implicit group every cluster references; not persisted, so it can't be deleted or drift.
    private static ClusterParameterGroup defaultParameterGroup() {
        return new ClusterParameterGroup(DEFAULT_PARAMETER_GROUP_NAME, "redshift-1.0",
                "Default parameter group for redshift-1.0");
    }

    public Optional<ClusterParameterGroup> getClusterParameterGroup(String parameterGroupName) {
        return parameterGroups.get(parameterGroupName);
    }

    public List<Parameter> describeClusterParameters(String parameterGroupName) {
        ClusterParameterGroup group = parameterGroups.get(parameterGroupName)
                .or(() -> DEFAULT_PARAMETER_GROUP_NAME.equals(parameterGroupName)
                        ? Optional.of(defaultParameterGroup()) : Optional.empty())
                .orElseThrow(() -> new AwsException("ClusterParameterGroupNotFound",
                        "Cluster parameter group " + parameterGroupName + " not found", 404));
        return group.getParameters().stream().map(RedshiftService::copyParameterWithSource).toList();
    }

    public synchronized ClusterParameterGroup modifyClusterParameterGroup(
            String parameterGroupName, List<Parameter> updates) {
        requireMutableParameterGroup(parameterGroupName);
        ClusterParameterGroup group = parameterGroups.get(parameterGroupName)
                .orElseThrow(() -> new AwsException("ClusterParameterGroupNotFound",
                        "Cluster parameter group " + parameterGroupName + " not found", 404));

        List<Parameter> current = new ArrayList<>(group.getParameters().stream()
                .map(RedshiftService::copyParameterWithSource).toList());
        for (Parameter update : updates) {
            boolean matched = false;
            for (int i = 0; i < current.size(); i++) {
                Parameter existing = current.get(i);
                if (existing.getParameterName().equals(update.getParameterName())) {
                    // Preserve metadata (description, dataType) from existing parameter if not provided in update
                    existing.setParameterValue(update.getParameterValue());
                    existing.setSource("user");
                    if (update.getDescription() != null) {
                        existing.setDescription(update.getDescription());
                    }
                    if (update.getDataType() != null) {
                        existing.setDataType(update.getDataType());
                    }
                    matched = true;
                    break;
                }
            }
            if (!matched) {
                Parameter added = copyParameterWithSource(update);
                added.setSource("user");
                current.add(added);
            }
        }
        group.setParameters(current);
        parameterGroups.put(parameterGroupName, group);
        parameterGroups.flush();
        refreshClustersForActivityParameter(parameterGroupName, group);
        emitParameterGroupUpdated(parameterGroupName);
        return group;
    }

    private void emitParameterGroupUpdated(String parameterGroupName) {
        emitEvent(RedshiftEventCatalog.PARAMETER_GROUP_UPDATED, parameterGroupName, "Cluster parameter group '"
                + parameterGroupName + "' was updated at " + eventTime() + ".");
    }

    private boolean userActivityLoggingEnabled(String parameterGroupName) {
        ClusterParameterGroup group = parameterGroups.get(parameterGroupName)
                .or(() -> DEFAULT_PARAMETER_GROUP_NAME.equals(parameterGroupName)
                        ? Optional.of(defaultParameterGroup()) : Optional.empty())
                .orElse(null);
        return group != null && userActivityLoggingEnabled(group);
    }

    private static boolean userActivityLoggingEnabled(ClusterParameterGroup group) {
        return group.getParameters().stream()
                .filter(parameter -> "enable_user_activity_logging".equalsIgnoreCase(parameter.getParameterName()))
                .map(Parameter::getParameterValue)
                .anyMatch(value -> Boolean.parseBoolean(value));
    }

    public synchronized void resetClusterParameterGroup(String parameterGroupName, boolean resetAllParameters,
                                                        List<String> parameterNames) {
        requireMutableParameterGroup(parameterGroupName);
        if (resetAllParameters && !parameterNames.isEmpty()) {
            throw new AwsException("InvalidParameterCombination",
                    "Parameters cannot be specified when ResetAllParameters is true.", 400);
        }
        if (!resetAllParameters && parameterNames.isEmpty()) {
            throw new AwsException("InvalidParameterCombination",
                    "At least one parameter is required when ResetAllParameters is false.", 400);
        }
        if (parameterNames.size() > MAX_RESET_PARAMETERS) {
            throw new AwsException("InvalidParameterValue", "A maximum of 20 parameters can be reset.", 400);
        }
        ClusterParameterGroup group = parameterGroups.get(parameterGroupName)
                .orElseThrow(() -> new AwsException("ClusterParameterGroupNotFound",
                        "Cluster parameter group " + parameterGroupName + " not found", 404));
        Set<String> names = new LinkedHashSet<>();
        for (String name : parameterNames) {
            if (name == null || name.isBlank() || group.getParameters().stream()
                    .noneMatch(parameter -> name.equals(parameter.getParameterName()))) {
                throw new AwsException("InvalidParameterValue", "Unknown parameter: " + name, 400);
            }
            names.add(name);
        }
        List<Parameter> defaults = ClusterParameterGroup.defaultParameters();
        List<Parameter> reset = defaults;
        if (!resetAllParameters) {
            reset = new ArrayList<>();
            for (Parameter parameter : group.getParameters()) {
                if (names.contains(parameter.getParameterName())) {
                    defaults.stream().filter(candidate -> candidate.getParameterName().equals(parameter.getParameterName()))
                            .findFirst().ifPresent(reset::add);
                } else {
                    reset.add(copyParameterWithSource(parameter));
                }
            }
        }
        group.setParameters(reset);
        parameterGroups.put(parameterGroupName, group);
        parameterGroups.flush();
        refreshClustersForActivityParameter(parameterGroupName, group);
        emitParameterGroupUpdated(parameterGroupName);
    }

    private void refreshClustersForActivityParameter(String parameterGroupName, ClusterParameterGroup group) {
        boolean enabled = userActivityLoggingEnabled(group);
        String accountId = clusters.accountId();
        List<Cluster> affected = clusters.scan(key -> true).stream()
                .filter(cluster -> parameterGroupName.equals(cluster.getClusterParameterGroupName())).toList();
        for (Cluster cluster : affected) {
            cluster.setUserActivityLoggingEnabled(enabled);
            clusters.putForAccount(accountId, cluster.getClusterIdentifier(), cluster);
            registerAuditLogging(accountId, cluster);
        }
        clusters.flush();
    }

    private void registerAuditLogging(String accountId, Cluster cluster) {
        if (auditLogPublisher != null) {
            String region = cluster.getLoggingRegion() != null ? cluster.getLoggingRegion() : regionResolver.getRegion();
            auditLogPublisher.register(accountId, region, cluster);
        }
    }

    private void requireMutableParameterGroup(String name) {
        if (name == null || name.isBlank()) {
            throw new AwsException("InvalidParameterValue", "ParameterGroupName is required", 400);
        }
        if (DEFAULT_PARAMETER_GROUP_NAME.equals(name) && parameterGroups.get(name).isEmpty()) {
            throw new AwsException("InvalidParameterValue", "The default parameter group cannot be modified.", 400);
        }
    }

    private static Parameter copyParameterWithSource(Parameter parameter) {
        Parameter copy = new Parameter(parameter.getParameterName(), parameter.getParameterValue(),
                parameter.getDescription(), parameter.getDataType());
        String source = parameter.getSource();
        if (source == null) {
            // Older persisted groups do not record whether a value was explicitly set to its default.
            boolean engineDefault = ClusterParameterGroup.defaultParameters().stream().anyMatch(candidate ->
                    candidate.getParameterName().equals(parameter.getParameterName())
                            && candidate.getParameterValue().equals(parameter.getParameterValue()));
            source = engineDefault ? "engine-default" : "user";
        }
        copy.setSource(source);
        return copy;
    }

    public ClusterParameterGroup deleteClusterParameterGroup(String parameterGroupName) {
        Optional<ClusterParameterGroup> groupOpt = parameterGroups.get(parameterGroupName);
        if (groupOpt.isEmpty()) {
            throw new AwsException("ClusterParameterGroupNotFound", "Cluster parameter group " + parameterGroupName + " not found", 404);
        }
        ClusterParameterGroup group = groupOpt.get();
        parameterGroups.delete(parameterGroupName);
        parameterGroups.flush();
        emitEvent(RedshiftEventCatalog.PARAMETER_GROUP_DELETED, parameterGroupName,
                "Cluster parameter group '" + parameterGroupName + "' was deleted at " + eventTime() + ".");
        return group;
    }

    // ── Event Operations ─────────────────────────────────────────────────────

    private RedshiftEventService eventService() {
        if (events == null) {
            throw new IllegalStateException("Redshift events are not available in this service instance");
        }
        return events;
    }

    private void emitClusterRestoreFailure(String clusterIdentifier, String snapshotIdentifier) {
        emitEvent(RedshiftEventCatalog.CLUSTER_RESTORE_FAILED, clusterIdentifier,
                "The restore operation to create Amazon Redshift cluster '" + clusterIdentifier
                        + "' from snapshot '" + snapshotIdentifier + "' failed at " + eventTime() + ".");
    }

    /** Whether the named source exists; Floci does not model cluster security groups or scheduled actions. */
    private boolean eventSourceExists(String sourceType, String sourceIdentifier) {
        return switch (sourceType) {
            case RedshiftEventCatalog.CLUSTER -> clusters.get(sourceIdentifier).isPresent();
            case RedshiftEventCatalog.PARAMETER_GROUP -> DEFAULT_PARAMETER_GROUP_NAME.equals(sourceIdentifier)
                    || parameterGroups.get(sourceIdentifier).isPresent();
            case RedshiftEventCatalog.SNAPSHOT -> snapshots.get(sourceIdentifier).isPresent();
            default -> false;
        };
    }

    public PaginatedResult<RedshiftEvent> describeEvents(String sourceIdentifier, String sourceType,
                                                         Instant startTime, Instant endTime, Integer duration,
                                                         Integer maxRecords, String marker) {
        return eventService().describeEvents(sourceIdentifier, sourceType, startTime, endTime, duration,
                maxRecords, marker);
    }

    public Map<String, List<EventInfo>> describeEventCategories(String sourceType) {
        return eventService().describeEventCategories(sourceType);
    }

    public EventSubscription createEventSubscription(String name, String snsTopicArn, String sourceType,
                                                     List<String> sourceIds, List<String> categories,
                                                     String severity, Boolean enabled, Map<String, String> tags) {
        return eventService().createSubscription(name, snsTopicArn, sourceType, sourceIds, categories, severity,
                enabled, tags, this::eventSourceExists);
    }

    public EventSubscription modifyEventSubscription(String name, String snsTopicArn, String sourceType,
                                                     List<String> sourceIds, List<String> categories,
                                                     String severity, Boolean enabled) {
        return eventService().modifySubscription(name, snsTopicArn, sourceType, sourceIds, categories, severity,
                enabled, this::eventSourceExists);
    }

    public void deleteEventSubscription(String name) {
        eventService().deleteSubscription(name);
    }

    public EventSubscription reconcileEventSubscription(String name, String snsTopicArn, String sourceType,
                                                         List<String> sourceIds, List<String> categories,
                                                         String severity, Boolean enabled, Map<String, String> tags) {
        return eventService().reconcileSubscription(name, snsTopicArn, sourceType, sourceIds, categories,
                severity, enabled, tags, this::eventSourceExists);
    }

    public EventSubscription restoreEventSubscription(String name, String snsTopicArn, String sourceType,
                                                       List<String> sourceIds, List<String> categories,
                                                       String severity, Boolean enabled, Map<String, String> tags,
                                                       String status) {
        return eventService().restoreSubscription(name, snsTopicArn, sourceType, sourceIds, categories,
                severity, enabled, tags, status);
    }

    public PaginatedResult<EventSubscription> describeEventSubscriptions(String name, List<String> tagKeys,
                                                                         List<String> tagValues, Integer maxRecords,
                                                                         String marker) {
        return eventService().describeSubscriptions(name, tagKeys, tagValues, maxRecords, marker);
    }

    // ── Cluster Subnet Group Operations ──────────────────────────────────────

    public ClusterSubnetGroup createClusterSubnetGroup(String name, String description, String vpcId, List<String> subnetIds) {
        if (subnetGroups.get(name).isPresent()) {
            throw new AwsException("ClusterSubnetGroupAlreadyExists", "Cluster subnet group " + name + " already exists", 400);
        }
        ClusterSubnetGroup group = new ClusterSubnetGroup(name, description, vpcId, subnetIds);
        subnetGroups.put(name, group);
        subnetGroups.flush();
        return group;
    }

    public List<ClusterSubnetGroup> describeClusterSubnetGroups(String name) {
        if (name != null && !name.isBlank()) {
            // The Terraform AWS provider matches this exact "...Fault" code to detect a missing group.
            ClusterSubnetGroup group = subnetGroups.get(name)
                    .orElseThrow(() -> new AwsException("ClusterSubnetGroupNotFoundFault", "Cluster subnet group " + name + " not found", 400));
            return List.of(group);
        }
        return subnetGroups.scan(k -> true);
    }

    public synchronized ClusterSubnetGroup modifyClusterSubnetGroup(String name, String description, List<String> subnetIds) {
        ClusterSubnetGroup group = subnetGroups.get(name)
                .orElseThrow(() -> new AwsException("ClusterSubnetGroupNotFoundFault", "Cluster subnet group " + name + " not found", 400));
        if (description != null) {
            group.setDescription(description);
        }
        if (subnetIds != null && !subnetIds.isEmpty()) {
            group.setSubnetIds(subnetIds);
        }
        subnetGroups.put(name, group);
        subnetGroups.flush();
        return group;
    }

    public ClusterSubnetGroup deleteClusterSubnetGroup(String name) {
        ClusterSubnetGroup group = subnetGroups.get(name)
                .orElseThrow(() -> new AwsException("ClusterSubnetGroupNotFoundFault", "Cluster subnet group " + name + " not found", 400));
        subnetGroups.delete(name);
        subnetGroups.flush();
        return group;
    }

    // ── Snapshot Copy Grant Operations ───────────────────────────────────────

    /**
     * AWS-managed Redshift key an account gets when CreateSnapshotCopyGrant omits KmsKeyId.
     * Floci has no per-account default key, so the alias ARN stands in for it: the value only
     * has to round-trip through Describe, which is what Terraform reads back.
     */
    private String defaultSnapshotCopyGrantKey() {
        return regionResolver.buildArn("kms", regionResolver.getRegion(), "alias/aws/redshift");
    }

    /**
     * AWS constrains a snapshot copy grant name to 1-63 characters, first a lowercase letter,
     * then lowercase letters, digits or single hyphens (no trailing or doubled hyphen). Names
     * Redshift rejects must not create here either, or Terraform sees a grant that cannot
     * exist upstream.
     */
    private static void validateSnapshotCopyGrantName(String name) {
        if (name == null || !name.matches("[a-z][a-z0-9-]{0,62}")
                || name.contains("--") || name.endsWith("-")) {
            throw new AwsException("InvalidParameterValue",
                    "SnapshotCopyGrantName must be 1-63 characters, start with a lowercase letter, "
                    + "and contain only lowercase letters, digits and non-consecutive hyphens", 400);
        }
    }

    // synchronized like createCluster: the free-name check and the write must not interleave,
    // or two concurrent creates of the same name both succeed and the second overwrites the first.
    public synchronized SnapshotCopyGrant createSnapshotCopyGrant(String name, String kmsKeyId, Map<String, String> tags) {
        validateSnapshotCopyGrantName(name);
        if (snapshotCopyGrants.get(name).isPresent()) {
            throw new AwsException("SnapshotCopyGrantAlreadyExistsFault",
                    "Snapshot copy grant " + name + " already exists", 400);
        }
        String effectiveKey = (kmsKeyId != null && !kmsKeyId.isBlank()) ? kmsKeyId : defaultSnapshotCopyGrantKey();
        SnapshotCopyGrant grant = new SnapshotCopyGrant(name, effectiveKey);
        if (tags != null && !tags.isEmpty()) {
            grant.setTags(new LinkedHashMap<>(tags));
        }
        snapshotCopyGrants.put(name, grant);
        snapshotCopyGrants.flush();
        return grant;
    }

    /** Default and maximum page size AWS documents for DescribeSnapshotCopyGrants. */
    private static final int SNAPSHOT_COPY_GRANT_PAGE_DEFAULT = 100;
    private static final int SNAPSHOT_COPY_GRANT_PAGE_MAX = 100;
    private static final int SNAPSHOT_COPY_GRANT_PAGE_MIN = 20;

    /**
     * Pages grants by name, which is their primary key, so the order is stable across calls
     * and a marker stays resumable when grants are created or deleted between pages.
     *
     * <p>AWS documents SnapshotCopyGrantName and Marker as mutually exclusive, but models no
     * error for sending both, so this filters first and then paginates rather than rejecting
     * the combination: a name matches at most one grant, which fits in any page.
     */
    public PaginatedResult<SnapshotCopyGrant> describeSnapshotCopyGrants(String name, Integer maxRecords, String marker) {
        if (maxRecords != null
                && (maxRecords < SNAPSHOT_COPY_GRANT_PAGE_MIN || maxRecords > SNAPSHOT_COPY_GRANT_PAGE_MAX)) {
            throw new AwsException("InvalidParameterValue",
                    "MaxRecords must be between " + SNAPSHOT_COPY_GRANT_PAGE_MIN
                            + " and " + SNAPSHOT_COPY_GRANT_PAGE_MAX + ".", 400);
        }

        List<SnapshotCopyGrant> matching;
        if (name != null && !name.isBlank()) {
            SnapshotCopyGrant grant = snapshotCopyGrants.get(name)
                    .orElseThrow(() -> new AwsException("SnapshotCopyGrantNotFoundFault",
                            "Snapshot copy grant " + name + " not found", 400));
            matching = List.of(grant);
        } else {
            matching = snapshotCopyGrants.scan(k -> true);
        }

        return Pagination.paginate(matching, SnapshotCopyGrant::getSnapshotCopyGrantName,
                maxRecords, marker, SNAPSHOT_COPY_GRANT_PAGE_DEFAULT, SNAPSHOT_COPY_GRANT_PAGE_MAX,
                "InvalidParameterValue", "Marker");
    }

    public synchronized SnapshotCopyGrant deleteSnapshotCopyGrant(String name) {
        SnapshotCopyGrant grant = snapshotCopyGrants.get(name)
                .orElseThrow(() -> new AwsException("SnapshotCopyGrantNotFoundFault",
                        "Snapshot copy grant " + name + " not found", 400));
        boolean inUse = clusters.scan(k -> true).stream()
                .anyMatch(c -> name.equals(c.getSnapshotCopyGrantName()));
        if (inUse) {
            throw new AwsException("InvalidSnapshotCopyGrantStateFault",
                    "Snapshot copy grant " + name + " is used by one or more clusters", 400);
        }
        snapshotCopyGrants.delete(name);
        snapshotCopyGrants.flush();
        return grant;
    }

    // ── Snapshot Copy Operations ─────────────────────────────────────────────

    private static final int DEFAULT_SNAPSHOT_COPY_RETENTION_DAYS = 7;
    // AWS reports -1 ("retain indefinitely") when ManualSnapshotRetentionPeriod is not given.
    private static final int RETAIN_MANUAL_SNAPSHOTS_INDEFINITELY = -1;

    private Cluster requireCluster(String clusterIdentifier) {
        return clusters.get(clusterIdentifier)
                .orElseThrow(() -> new AwsException("ClusterNotFound", "Cluster " + clusterIdentifier + " not found", 404));
    }

    // Automated snapshots: 1 to 35 days.
    private static void validateAutomatedRetention(int days) {
        if (days < 1 || days > 35) {
            throw new AwsException("InvalidRetentionPeriodFault",
                    "RetentionPeriod must be between 1 and 35 days.", 400);
        }
    }

    // Manual snapshots: -1 (indefinitely) or 1 to 3653 days.
    private static void validateManualRetention(int days) {
        if (days != RETAIN_MANUAL_SNAPSHOTS_INDEFINITELY && (days < 1 || days > 3653)) {
            throw new AwsException("InvalidRetentionPeriodFault",
                    "ManualSnapshotRetentionPeriod must be -1 or between 1 and 3653 days.", 400);
        }
    }

    /** Copy is recorded on the cluster only; no snapshot is actually copied to another region. */
    public Cluster enableSnapshotCopy(String clusterIdentifier, String destinationRegion,
                                                   Integer retentionPeriod, Integer manualRetentionPeriod,
                                                   String snapshotCopyGrantName) {
        return mutateCluster(clusterIdentifier, () -> enableSnapshotCopyLocked(clusterIdentifier,
                destinationRegion, retentionPeriod, manualRetentionPeriod, snapshotCopyGrantName));
    }

    private Cluster enableSnapshotCopyLocked(String clusterIdentifier, String destinationRegion,
                                                   Integer retentionPeriod, Integer manualRetentionPeriod,
                                                   String snapshotCopyGrantName) {
        Cluster cluster = requireCluster(clusterIdentifier);
        if (cluster.getSnapshotCopyDestinationRegion() != null) {
            throw new AwsException("SnapshotCopyAlreadyEnabledFault",
                    "Snapshot copy is already enabled on cluster " + clusterIdentifier + ".", 400);
        }
        if (!AwsRegions.isRegionId(destinationRegion)) {
            throw new AwsException("UnknownSnapshotCopyRegionFault",
                    "Unknown destination region: " + destinationRegion, 404);
        }
        int retention = retentionPeriod == null ? DEFAULT_SNAPSHOT_COPY_RETENTION_DAYS : retentionPeriod;
        int manualRetention = manualRetentionPeriod == null
                ? RETAIN_MANUAL_SNAPSHOTS_INDEFINITELY : manualRetentionPeriod;
        validateAutomatedRetention(retention);
        validateManualRetention(manualRetention);
        boolean hasGrant = snapshotCopyGrantName != null && !snapshotCopyGrantName.isBlank();
        if (hasGrant && snapshotCopyGrants.get(snapshotCopyGrantName).isEmpty()) {
            throw new AwsException("SnapshotCopyGrantNotFoundFault",
                    "Snapshot copy grant " + snapshotCopyGrantName + " not found", 400);
        }
        cluster.setSnapshotCopyDestinationRegion(destinationRegion);
        cluster.setSnapshotCopyRetentionPeriod(retention);
        cluster.setSnapshotCopyManualRetentionPeriod(manualRetention);
        cluster.setSnapshotCopyGrantName(hasGrant ? snapshotCopyGrantName : null);
        clusters.put(clusterIdentifier, cluster);
        clusters.flush();
        return cluster;
    }

    public Cluster disableSnapshotCopy(String clusterIdentifier) {
        return mutateCluster(clusterIdentifier, () -> disableSnapshotCopyLocked(clusterIdentifier));
    }

    private Cluster disableSnapshotCopyLocked(String clusterIdentifier) {
        Cluster cluster = requireCluster(clusterIdentifier);
        if (cluster.getSnapshotCopyDestinationRegion() == null) {
            throw new AwsException("SnapshotCopyAlreadyDisabledFault",
                    "Snapshot copy is not enabled on cluster " + clusterIdentifier + ".", 400);
        }
        cluster.setSnapshotCopyDestinationRegion(null);
        cluster.setSnapshotCopyRetentionPeriod(null);
        cluster.setSnapshotCopyManualRetentionPeriod(null);
        cluster.setSnapshotCopyGrantName(null);
        clusters.put(clusterIdentifier, cluster);
        clusters.flush();
        return cluster;
    }

    public Cluster modifySnapshotCopyRetentionPeriod(String clusterIdentifier, int retentionPeriod,
                                                                  boolean manual) {
        return mutateCluster(clusterIdentifier,
                () -> modifySnapshotCopyRetentionPeriodLocked(clusterIdentifier, retentionPeriod, manual));
    }

    private Cluster modifySnapshotCopyRetentionPeriodLocked(String clusterIdentifier, int retentionPeriod,
                                                                  boolean manual) {
        Cluster cluster = requireCluster(clusterIdentifier);
        // SnapshotCopyDisabledFault, not ...AlreadyDisabledFault: the model lists only this one here.
        if (cluster.getSnapshotCopyDestinationRegion() == null) {
            throw new AwsException("SnapshotCopyDisabledFault",
                    "Snapshot copy is not enabled on cluster " + clusterIdentifier + ".", 400);
        }
        if (manual) {
            validateManualRetention(retentionPeriod);
            cluster.setSnapshotCopyManualRetentionPeriod(retentionPeriod);
        } else {
            validateAutomatedRetention(retentionPeriod);
            cluster.setSnapshotCopyRetentionPeriod(retentionPeriod);
        }
        clusters.put(clusterIdentifier, cluster);
        clusters.flush();
        return cluster;
    }

    // ── Snapshot Schedule Operations ─────────────────────────────────────────

    private static final int SNAPSHOT_SCHEDULE_PAGE_MAX = 100;

    private static void validateAutomatedRetention(String nodeType, int retention) {
        boolean required = nodeType != null && (nodeType.startsWith("ra3.") || nodeType.startsWith("rg."));
        if (retention < 0 || retention > 35 || required && retention == 0) {
            throw new AwsException("InvalidParameterValue", "AutomatedSnapshotRetentionPeriod must be "
                    + (required ? "1" : "0") + " to 35 days", 400);
        }
    }

    /** A deterministic sweep; the lifecycle scheduler supplies now, tests supply a fixed instant. */
    void runSnapshotSchedules(Instant now) {
        for (AccountAwareStorageBackend.AccountEntry<Cluster> entry : clusters.scanAllAccountEntries(key -> true)) {
            if (Thread.currentThread().isInterrupted()) {
                return;
            }
            try {
                withResourceLock(entry.accountId(), "cluster", entry.key(), () -> {
                    Cluster cluster = clusters.getForAccount(entry.accountId(), entry.key()).orElse(null);
                    if (cluster != null) {
                        try {
                            runSnapshotSchedule(entry.accountId(), cluster, now);
                        } finally {
                            // Retention must not depend on the next dump succeeding.
                            expireAutomatedSnapshots(entry.accountId(), cluster.getClusterIdentifier(), cluster, now);
                        }
                    }
                    return null;
                });
            } catch (RuntimeException e) {
                LOG.warnv(e, "Could not process automated snapshots for account {0}, cluster {1}",
                        entry.accountId(), entry.key());
            }
        }
        for (AccountAwareStorageBackend.AccountEntry<Snapshot> entry : snapshots.scanAllAccountEntries(key -> true)) {
            if (!"automated".equals(entry.value().getSnapshotType())) {
                continue;
            }
            String clusterId = entry.value().getClusterIdentifier();
            try {
                withResourceLock(entry.accountId(), "cluster", clusterId, () -> {
                    if (clusters.getForAccount(entry.accountId(), clusterId).isEmpty()) {
                        removeAutomatedSnapshot(entry.accountId(), entry.key());
                    }
                    return null;
                });
            } catch (RuntimeException e) {
                LOG.warnv(e, "Could not clean up automated snapshot {0} in account {1}", entry.key(), entry.accountId());
            }
        }
        for (AccountAwareStorageBackend.AccountEntry<AutomatedSnapshotState> entry
                : automatedSnapshotStates.scanAllAccountEntries(key -> true)) {
            try {
                withResourceLock(entry.accountId(), "cluster", entry.key(), () -> {
                    if (clusters.getForAccount(entry.accountId(), entry.key()).isEmpty()) {
                        automatedSnapshotStates.deleteForAccount(entry.accountId(), entry.key());
                    }
                    return null;
                });
            } catch (RuntimeException e) {
                LOG.warnv(e, "Could not remove the automated snapshot checkpoint of cluster {0} in account {1}",
                        entry.key(), entry.accountId());
            }
        }
    }

    private void runSnapshotSchedule(String accountId, Cluster cluster, Instant now) {
        if (!"available".equals(cluster.getClusterStatus()) || cluster.getAutomatedSnapshotRetentionPeriod() == 0) {
            return;
        }
        SnapshotSchedule schedule = cluster.getSnapshotScheduleIdentifier() == null ? null
                : snapshotSchedules.getForAccount(accountId, cluster.getSnapshotScheduleIdentifier()).orElseThrow(
                        () -> new AwsException("SnapshotScheduleNotFound", "The cluster's snapshot schedule is missing", 400));
        List<String> definitions = schedule == null ? List.of("rate(8 hours)")
                : List.copyOf(schedule.getScheduleDefinitions());
        Instant activation = cluster.getAutomatedSnapshotAnchor();
        if (schedule != null && schedule.getUpdatedAt() != null
                && (activation == null || schedule.getUpdatedAt().isAfter(activation))) {
            activation = schedule.getUpdatedAt();
        }
        String scheduleKey = cluster.getSnapshotGeneration() + "|" + cluster.getSnapshotScheduleIdentifier()
                + "|" + definitions + "|" + activation;
        String clusterId = cluster.getClusterIdentifier();
        AutomatedSnapshotState state = automatedSnapshotStates.getForAccount(accountId, clusterId).orElse(null);
        if (state == null || !scheduleKey.equals(state.scheduleKey())) {
            state = new AutomatedSnapshotState(scheduleKey, activation == null ? now : activation, null);
            automatedSnapshotStates.putAllAtomicallyForAccount(accountId, Map.of(clusterId, state));
        }
        Optional<Instant> due = RedshiftSnapshotSchedule.latestDue(definitions, state.anchor(), now);
        if (due.isEmpty()) {
            return;
        }
        Instant scheduled = due.get();
        String identity = clusterId + "|" + state.anchor() + "|" + scheduled;
        String snapshotId = "rs-auto-" + UUID.nameUUIDFromBytes(identity.getBytes(StandardCharsets.UTF_8));
        Instant last = state.lastScheduledTime();
        // The checkpoint lives in a separate store, so it can outlive a snapshot whose metadata never reached disk.
        if (last != null && (scheduled.isBefore(last)
                || scheduled.equals(last) && snapshots.getForAccount(accountId, snapshotId).isPresent())) {
            return;
        }
        boolean tooSoon = snapshots.scanForAccount(accountId, key -> true).stream()
                .filter(snapshot -> "automated".equals(snapshot.getSnapshotType()))
                .filter(snapshot -> clusterId.equals(snapshot.getClusterIdentifier()))
                .map(Snapshot::getSnapshotCreateTime).filter(time -> time != null)
                .anyMatch(time -> time.plus(Duration.ofHours(1)).isAfter(now));
        boolean completed = withResourceLock(accountId, "snapshot", snapshotId, () -> {
            Snapshot existing = snapshots.getForAccount(accountId, snapshotId).orElse(null);
            if (existing == null) {
                if (tooSoon) {
                    return false;
                }
                createSnapshot(accountId, snapshotId, cluster, null, now, scheduled);
            } else if (!"automated".equals(existing.getSnapshotType())
                    || !clusterId.equals(existing.getClusterIdentifier()) || !scheduled.equals(existing.getScheduledTime())) {
                throw new AwsException("ClusterSnapshotAlreadyExists", "Snapshot identifier is already in use", 400);
            }
            return true;
        });
        if (!completed) {
            return;
        }
        automatedSnapshotStates.putAllAtomicallyForAccount(accountId, Map.of(clusterId,
                new AutomatedSnapshotState(scheduleKey, state.anchor(), scheduled)));
    }

    private String snapshotRegion(Cluster cluster) {
        return cluster.getSnapshotRegion() == null ? regionResolver.getDefaultRegion() : cluster.getSnapshotRegion();
    }

    private void expireAutomatedSnapshots(String accountId, String clusterId, Cluster cluster, Instant now) {
        List<Snapshot> automated = snapshots.scanForAccount(accountId, key -> true).stream()
                .filter(snapshot -> "automated".equals(snapshot.getSnapshotType()))
                .filter(snapshot -> clusterId.equals(snapshot.getClusterIdentifier()))
                .filter(snapshot -> snapshot.getSnapshotCreateTime() != null).toList();
        Snapshot latest = automated.stream().max(Comparator.comparing(Snapshot::getSnapshotCreateTime)
                .thenComparing(Snapshot::getSnapshotIdentifier)).orElse(null);
        for (Snapshot snapshot : automated) {
            boolean replacedCluster = cluster.getSnapshotGeneration() != null
                    && !Objects.equals(cluster.getSnapshotGeneration(), snapshot.getClusterGeneration());
            if (replacedCluster || cluster.getAutomatedSnapshotRetentionPeriod() == 0 || snapshot != latest
                    && !snapshot.getSnapshotCreateTime().plus(Duration.ofDays(cluster.getAutomatedSnapshotRetentionPeriod()))
                            .isAfter(now)) {
                removeAutomatedSnapshot(accountId, snapshot.getSnapshotIdentifier());
            }
        }
    }

    private void removeAutomatedSnapshot(String accountId, String snapshotId) {
        withResourceLock(accountId, "snapshot", snapshotId, () -> mutate(() -> {
            Snapshot current = snapshots.getForAccount(accountId, snapshotId).orElse(null);
            if (current == null || !"automated".equals(current.getSnapshotType())) {
                return null;
            }
            snapshots.deleteAllAtomicallyForAccounts(Map.of(accountId, Set.of(snapshotId)));
            // Hybrid storage flushes lazily; a crash after the dump is gone would resurrect a dump-less snapshot.
            snapshots.flush();
            if (current.getSqlDump() != null) {
                Path file = Paths.get(current.getSqlDump()).toAbsolutePath().normalize();
                if (file.startsWith(accountDumpDir(accountId))) {
                    try {
                        Files.deleteIfExists(file);
                    } catch (IOException e) {
                        LOG.warnv(e, "Could not delete the dump of automated snapshot {0}", snapshotId);
                    }
                }
            }
            return null;
        }));
    }

    // The API reference allows only alphanumerics; the management guide's own example uses a hyphen.
    private static final Pattern SCHEDULE_IDENTIFIER = Pattern.compile("[A-Za-z0-9-]+");

    private static void validateScheduleDefinitions(List<String> definitions) {
        RedshiftSnapshotSchedule.validate(definitions);
    }

    private SnapshotSchedule requireSnapshotSchedule(String scheduleIdentifier) {
        return snapshotSchedules.get(scheduleIdentifier)
                .orElseThrow(() -> new AwsException("SnapshotScheduleNotFound",
                        "Snapshot schedule " + scheduleIdentifier + " not found", 400));
    }

    public synchronized SnapshotSchedule createSnapshotSchedule(String scheduleIdentifier, String description,
                                                                List<String> definitions, Map<String, String> tags) {
        String id = (scheduleIdentifier == null || scheduleIdentifier.isBlank())
                ? UUID.randomUUID().toString().replace("-", "")
                : scheduleIdentifier;
        if (!SCHEDULE_IDENTIFIER.matcher(id).matches()) {
            throw new AwsException("InvalidParameterValue",
                    "ScheduleIdentifier may contain only letters, digits and hyphens: " + id, 400);
        }
        if (snapshotSchedules.get(id).isPresent()) {
            throw new AwsException("SnapshotScheduleAlreadyExists", "Snapshot schedule " + id + " already exists", 400);
        }
        validateScheduleDefinitions(definitions);
        SnapshotSchedule schedule = new SnapshotSchedule(id, description, definitions);
        schedule.setUpdatedAt(Instant.now());
        if (tags != null && !tags.isEmpty()) {
            schedule.setTags(new LinkedHashMap<>(tags));
        }
        snapshotSchedules.put(id, schedule);
        snapshotSchedules.flush();
        return schedule;
    }

    /**
     * Every filter given must match. An unknown schedule or cluster yields an empty list rather
     * than a fault: the model lists no errors for this operation.
     */
    public PaginatedResult<SnapshotSchedule> describeSnapshotSchedules(String scheduleIdentifier,
                                                                       String clusterIdentifier,
                                                                       List<String> tagKeys, List<String> tagValues,
                                                                       Integer maxRecords, String marker) {
        String clusterSchedule = null;
        if (clusterIdentifier != null && !clusterIdentifier.isBlank()) {
            clusterSchedule = clusters.get(clusterIdentifier).map(Cluster::getSnapshotScheduleIdentifier).orElse(null);
            if (clusterSchedule == null) {
                return new PaginatedResult<>(List.of(), null);
            }
        }
        String byCluster = clusterSchedule;
        List<SnapshotSchedule> matching = snapshotSchedules.scan(k -> true).stream()
                .filter(s -> scheduleIdentifier == null || scheduleIdentifier.isBlank()
                        || scheduleIdentifier.equals(s.getScheduleIdentifier()))
                .filter(s -> byCluster == null || byCluster.equals(s.getScheduleIdentifier()))
                .filter(s -> tagKeys == null || tagKeys.isEmpty()
                        || tagKeys.stream().anyMatch(s.getTags()::containsKey))
                .filter(s -> tagValues == null || tagValues.isEmpty()
                        || tagValues.stream().anyMatch(s.getTags()::containsValue))
                .toList();
        return Pagination.paginate(matching, SnapshotSchedule::getScheduleIdentifier, maxRecords, marker,
                SNAPSHOT_SCHEDULE_PAGE_MAX, SNAPSHOT_SCHEDULE_PAGE_MAX, "InvalidParameterValue", "Marker");
    }

    /** Identifiers of the clusters currently using the schedule, sorted for a stable response. */
    public List<String> clustersUsingSnapshotSchedule(String scheduleIdentifier) {
        return clusters.scan(k -> true).stream()
                .filter(c -> scheduleIdentifier.equals(c.getSnapshotScheduleIdentifier()))
                .map(Cluster::getClusterIdentifier)
                .sorted()
                .toList();
    }

    public synchronized SnapshotSchedule modifySnapshotSchedule(String scheduleIdentifier, List<String> definitions) {
        SnapshotSchedule current = requireSnapshotSchedule(scheduleIdentifier);
        validateScheduleDefinitions(definitions);
        SnapshotSchedule schedule = new SnapshotSchedule(scheduleIdentifier, current.getScheduleDescription(), definitions);
        schedule.setTags(new LinkedHashMap<>(current.getTags()));
        schedule.setUpdatedAt(Instant.now());
        snapshotSchedules.put(scheduleIdentifier, schedule);
        snapshotSchedules.flush();
        return schedule;
    }

    public synchronized void deleteSnapshotSchedule(String scheduleIdentifier) {
        requireSnapshotSchedule(scheduleIdentifier);
        if (!clustersUsingSnapshotSchedule(scheduleIdentifier).isEmpty()) {
            throw new AwsException("InvalidClusterSnapshotScheduleState",
                    "Snapshot schedule " + scheduleIdentifier + " is associated with one or more clusters.", 400);
        }
        snapshotSchedules.delete(scheduleIdentifier);
        snapshotSchedules.flush();
    }

    public Cluster modifyClusterSnapshotSchedule(String clusterIdentifier, String scheduleIdentifier,
                                                              boolean disassociate) {
        return mutateCluster(clusterIdentifier, () -> modifyClusterSnapshotScheduleLocked(clusterIdentifier,
                scheduleIdentifier, disassociate));
    }

    private Cluster modifyClusterSnapshotScheduleLocked(String clusterIdentifier, String scheduleIdentifier,
                                                              boolean disassociate) {
        Cluster cluster = requireCluster(clusterIdentifier);
        if (disassociate) {
            cluster.setSnapshotScheduleIdentifier(null);
        } else {
            if (scheduleIdentifier == null || scheduleIdentifier.isBlank()) {
                throw new AwsException("InvalidParameterValue",
                        "ScheduleIdentifier is required unless DisassociateSchedule is true", 400);
            }
            requireSnapshotSchedule(scheduleIdentifier);
            cluster.setSnapshotScheduleIdentifier(scheduleIdentifier);
        }
        cluster.setAutomatedSnapshotAnchor(Instant.now());
        clusters.put(clusterIdentifier, cluster);
        clusters.flush();
        return cluster;
    }

    // ── Tagging Operations ───────────────────────────────────────────────────

    /** A resolved tag target: its current tags plus a sink that persists an updated map. */
    private record TagHandle(Map<String, String> tags, Consumer<Map<String, String>> save) {}

    public record TaggedResource(String resourceName, String resourceType, String tagKey, String tagValue) {}

    public Map<String, String> listTagsForResource(String resourceName) {
        return Map.copyOf(resolveTagHandle(resourceName).tags());
    }

    public synchronized void createTags(String resourceName, Map<String, String> tags) {
        String subscriptionName = eventSubscriptionTagName(resourceName);
        if (subscriptionName != null) {
            eventService().addSubscriptionTags(subscriptionName, tags);
            return;
        }
        TagHandle handle = resolveTagHandle(resourceName);
        Map<String, String> updated = new LinkedHashMap<>(handle.tags());
        updated.putAll(tags);
        handle.save().accept(updated);
    }

    public synchronized void deleteTags(String resourceName, Collection<String> tagKeys) {
        String subscriptionName = eventSubscriptionTagName(resourceName);
        if (subscriptionName != null) {
            eventService().deleteSubscriptionTags(subscriptionName, tagKeys);
            return;
        }
        TagHandle handle = resolveTagHandle(resourceName);
        Map<String, String> updated = new LinkedHashMap<>(handle.tags());
        tagKeys.forEach(updated::remove);
        handle.save().accept(updated);
    }

    public List<TaggedResource> describeTags(String resourceName, String resourceType, List<String> tagKeysFilter) {
        List<TaggedResource> result = new ArrayList<>();
        if (resourceName != null && !resourceName.isBlank()) {
            TagHandle handle = resolveTagHandle(resourceName);
            String type = arnResourceType(resourceName);
            addTaggedResources(result, resourceName, type, handle.tags(), tagKeysFilter);
            return result;
        }
        if (resourceType == null || "cluster".equalsIgnoreCase(resourceType)) {
            for (Cluster c : clusters.scan(k -> true)) {
                addTaggedResources(result, clusterArn(c.getClusterIdentifier()), "cluster", c.getTags(), tagKeysFilter);
            }
        }
        if (resourceType == null || "snapshot".equalsIgnoreCase(resourceType)) {
            for (Snapshot s : snapshots.scan(k -> true)) {
                addTaggedResources(result, snapshotArn(s.getClusterIdentifier(), s.getSnapshotIdentifier()),
                        "snapshot", s.getTags(), tagKeysFilter);
            }
        }
        if (resourceType == null || "parametergroup".equalsIgnoreCase(resourceType)) {
            for (ClusterParameterGroup g : parameterGroups.scan(k -> true)) {
                addTaggedResources(result, parameterGroupArn(g.getParameterGroupName()),
                        "parametergroup", g.getTags(), tagKeysFilter);
            }
        }
        if (resourceType == null || "subnetgroup".equalsIgnoreCase(resourceType)) {
            for (ClusterSubnetGroup g : subnetGroups.scan(k -> true)) {
                addTaggedResources(result, subnetGroupArn(g.getClusterSubnetGroupName()),
                        "subnetgroup", g.getTags(), tagKeysFilter);
            }
        }
        if (resourceType == null || "snapshotcopygrant".equalsIgnoreCase(resourceType)) {
            for (SnapshotCopyGrant g : snapshotCopyGrants.scan(k -> true)) {
                addTaggedResources(result, snapshotCopyGrantArn(g.getSnapshotCopyGrantName()),
                        "snapshotcopygrant", g.getTags(), tagKeysFilter);
            }
        }
        if (resourceType == null || "snapshotschedule".equalsIgnoreCase(resourceType)) {
            for (SnapshotSchedule s : snapshotSchedules.scan(k -> true)) {
                addTaggedResources(result, snapshotScheduleArn(s.getScheduleIdentifier()),
                        "snapshotschedule", s.getTags(), tagKeysFilter);
            }
        }
        if (events != null && (resourceType == null || "eventsubscription".equalsIgnoreCase(resourceType))) {
            for (EventSubscription subscription : events.describeSubscriptions(null, null, null, null, null).items()) {
                addTaggedResources(result, regionResolver.buildArn("redshift", regionResolver.getRegion(),
                                "eventsubscription:" + subscription.getCustSubscriptionId()),
                        "eventsubscription", subscription.getTags(), tagKeysFilter);
            }
        }
        return result;
    }

    private void addTaggedResources(List<TaggedResource> out, String arn, String type,
                                     Map<String, String> tags, List<String> tagKeysFilter) {
        for (Map.Entry<String, String> e : tags.entrySet()) {
            if (tagKeysFilter != null && !tagKeysFilter.isEmpty() && !tagKeysFilter.contains(e.getKey())) {
                continue;
            }
            out.add(new TaggedResource(arn, type, e.getKey(), e.getValue()));
        }
    }

    private String arnResourceType(String resourceName) {
        AwsArnUtils.Arn arn = AwsArnUtils.parse(resourceName);
        String resource = arn.resource();
        int sep = resource.indexOf(':');
        return resource.substring(0, sep);
    }

    private String clusterArn(String clusterIdentifier) {
        return regionResolver.buildArn("redshift", regionResolver.getRegion(), "cluster:" + clusterIdentifier);
    }

    private String snapshotArn(String clusterIdentifier, String snapshotIdentifier) {
        return regionResolver.buildArn("redshift", regionResolver.getRegion(),
                "snapshot:" + clusterIdentifier + "/" + snapshotIdentifier);
    }

    private String parameterGroupArn(String parameterGroupName) {
        return regionResolver.buildArn("redshift", regionResolver.getRegion(), "parametergroup:" + parameterGroupName);
    }

    private String subnetGroupArn(String name) {
        return regionResolver.buildArn("redshift", regionResolver.getRegion(), "subnetgroup:" + name);
    }

    private String snapshotCopyGrantArn(String name) {
        return regionResolver.buildArn("redshift", regionResolver.getRegion(), "snapshotcopygrant:" + name);
    }

    private String snapshotScheduleArn(String scheduleIdentifier) {
        return regionResolver.buildArn("redshift", regionResolver.getRegion(), "snapshotschedule:" + scheduleIdentifier);
    }

    /**
     * Resolves a tagging ResourceName to its backing resource.
     *
     * Redshift ARNs have the shape {@code arn:aws:redshift:<region>:<account>:<type>:<id>},
     * where {@code <type>} is one of {@code cluster}, {@code snapshot} (id shape
     * {@code <clusterId>/<snapshotId>}), {@code parametergroup}, {@code subnetgroup} or
     * {@code snapshotcopygrant} or {@code snapshotschedule}. Unlike RDS's tag
     * resolution, there is no bare-name fallback: Redshift tagging is new, so there is no
     * existing caller to stay backward compatible with.
     */
    private AwsArnUtils.Arn requireTagArn(String resourceName) {
        if (resourceName == null || resourceName.isBlank()) {
            throw new AwsException("InvalidParameterValue", "ResourceName is required.", 400);
        }
        if (!AwsArnUtils.looksLikeArn(resourceName)) {
            throw new AwsException("InvalidParameterValue", "ResourceName must be a Redshift ARN: " + resourceName, 400);
        }
        AwsArnUtils.Arn arn;
        try {
            arn = AwsArnUtils.parse(resourceName);
        } catch (IllegalArgumentException malformed) {
            throw new AwsException("InvalidParameterValue", "Invalid resource name: " + resourceName, 400);
        }
        if (!"redshift".equals(arn.service())) {
            throw new AwsException("InvalidParameterValue", "Invalid resource name: " + resourceName, 400);
        }
        // Resources here are stored per caller account and their ARNs minted in the caller's
        // partition and region, so an ARN naming another partition, account or region cannot be
        // a local resource. ResourceNotFoundFault (404) is the not-found fault
        // CreateTags/DeleteTags/DescribeTags all model; resolving by id alone would tag the
        // local resource of the same name.
        if (AwsArnUtils.isForeignPartition(arn, regionResolver.getPartition())
                || AwsArnUtils.isForeignAccount(arn, regionResolver.getAccountId())
                || !regionResolver.getRegion().equals(arn.region())) {
            throw new AwsException("ResourceNotFoundFault", "Resource " + resourceName + " not found", 404);
        }
        return arn;
    }

    private String eventSubscriptionTagName(String resourceName) {
        String resource = requireTagArn(resourceName).resource();
        String prefix = "eventsubscription:";
        return resource.startsWith(prefix) ? resource.substring(prefix.length()) : null;
    }

    private TagHandle resolveTagHandle(String resourceName) {
        AwsArnUtils.Arn arn = requireTagArn(resourceName);
        String resource = arn.resource();
        int sep = resource.indexOf(':');
        if (sep < 0) {
            throw new AwsException("InvalidParameterValue", "Invalid resource name: " + resourceName, 400);
        }
        String type = resource.substring(0, sep);
        String id = resource.substring(sep + 1);

        return switch (type) {
            case "eventsubscription" -> {
                if (id.isBlank()) {
                    throw new AwsException("InvalidParameterValue", "Invalid resource name: " + resourceName, 400);
                }
                EventSubscription subscription = eventService().describeSubscriptions(id, null, null, null, null)
                        .items().getFirst();
                yield new TagHandle(subscription.getTags(), updated -> eventService().replaceSubscriptionTags(id, updated));
            }
            case "cluster" -> {
                Cluster cluster = clusters.get(id)
                        .orElseThrow(() -> new AwsException("ClusterNotFound", "Cluster " + id + " not found", 404));
                yield new TagHandle(cluster.getTags(), updated -> {
                    cluster.setTags(updated);
                    clusters.put(id, cluster);
                    clusters.flush();
                });
            }
            case "snapshot" -> {
                String snapshotId = id.contains("/") ? id.substring(id.lastIndexOf('/') + 1) : id;
                Snapshot snapshot = snapshots.get(snapshotId)
                        .orElseThrow(() -> new AwsException("ClusterSnapshotNotFound", "Snapshot " + snapshotId + " not found", 404));
                yield new TagHandle(snapshot.getTags(), updated -> {
                    snapshot.setTags(updated);
                    snapshots.put(snapshotId, snapshot);
                    snapshots.flush();
                });
            }
            case "parametergroup" -> {
                ClusterParameterGroup group = parameterGroups.get(id)
                        .orElseThrow(() -> new AwsException("ClusterParameterGroupNotFound", "Cluster parameter group " + id + " not found", 404));
                yield new TagHandle(group.getTags(), updated -> {
                    group.setTags(updated);
                    parameterGroups.put(id, group);
                    parameterGroups.flush();
                });
            }
            case "subnetgroup" -> {
                ClusterSubnetGroup group = subnetGroups.get(id)
                        .orElseThrow(() -> new AwsException("ClusterSubnetGroupNotFoundFault", "Cluster subnet group " + id + " not found", 400));
                yield new TagHandle(group.getTags(), updated -> {
                    group.setTags(updated);
                    subnetGroups.put(id, group);
                    subnetGroups.flush();
                });
            }
            case "snapshotcopygrant" -> {
                // ResourceNotFoundFault, not SnapshotCopyGrantNotFoundFault: this path is only
                // reached from CreateTags/DeleteTags/DescribeTags, and those three list
                // ResourceNotFoundFault (404) for a missing resource and do not list the
                // grant-specific fault at all. The three sibling cases above are consistent
                // for the same reason -- ClusterNotFound, ClusterSnapshotNotFound and
                // ClusterParameterGroupNotFound are each modelled at 404. The grant fault is
                // modelled at 400, so emitting it here would pair a code with a status the
                // model never gives it.
                SnapshotCopyGrant grant = snapshotCopyGrants.get(id)
                        .orElseThrow(() -> new AwsException("ResourceNotFoundFault", "Snapshot copy grant " + id + " not found", 404));
                yield new TagHandle(grant.getTags(), updated -> {
                    grant.setTags(updated);
                    snapshotCopyGrants.put(id, grant);
                    snapshotCopyGrants.flush();
                });
            }
            case "snapshotschedule" -> {
                // ResourceNotFoundFault for the same reason as snapshotcopygrant above.
                SnapshotSchedule schedule = snapshotSchedules.get(id)
                        .orElseThrow(() -> new AwsException("ResourceNotFoundFault", "Snapshot schedule " + id + " not found", 404));
                yield new TagHandle(schedule.getTags(), updated -> {
                    schedule.setTags(updated);
                    snapshotSchedules.put(id, schedule);
                    snapshotSchedules.flush();
                });
            }
            default -> throw new AwsException("InvalidParameterValue",
                    "Tagging for resource type '" + type + "' is not supported: " + resourceName, 400);
        };
    }

    // ── Proxy Helpers (shared with modify/reboot/restore) ────────────────────

    private static final String CLUSTER_DB_NAME = "dev";

    private int allocateProxyPort() {
        int base = config.services().redshift().proxyBasePort();
        int max = config.services().redshift().proxyMaxPort();
        for (int port = base; port <= max; port++) {
            if (usedPorts.add(port)) {
                return port;
            }
        }
        throw new AwsException("InsufficientClusterCapacity",
                "No available Redshift proxy ports in range " + base + "-" + max, 503);
    }

    private boolean stopProxyAndReleasePortSafely(String identifier, int proxyPort) {
        boolean proxyStopped = false;
        try {
            proxyManager.stopProxy(relayKey(clusters.accountId(), identifier));
            proxyStopped = true;
        } catch (Exception ex) {
            LOG.warnv(ex, "Failed to stop proxy for cluster {0}; leaking proxy port {1} to prevent reallocation", identifier, proxyPort);
        }
        if (proxyStopped) {
            releaseProxyPort(proxyPort);
        }
        return proxyStopped;
    }

    /**
     * Undo a failed reboot once container teardown has started. Before that point the
     * original data-bearing container must be preserved, even if proxy shutdown failed.
     * Teardown may be partial: stop the proxy and remove the container tracked by the
     * manager, including a replacement retained after a failed readiness check. The
     * caller keeps the pre-reboot data dump.
     */
    private void rollbackReboot(String identifier, boolean containerTeardownStarted) {
        if (!containerTeardownStarted) {
            return;
        }
        try {
            proxyManager.stopProxy(relayKey(clusters.accountId(), identifier));
        } catch (Exception ex) {
            LOG.warnv(ex, "Failed to stop proxy during reboot rollback for cluster {0}", identifier);
        }
        try {
            containerManager.stop(clusters.accountId(), identifier);
        } catch (Exception ex) {
            LOG.warnv(ex, "Failed to stop replacement container during rollback of reboot for cluster {0}", identifier);
        }
    }

    public void releaseProxyPort(int port) {
        if (port > 0) {
            usedPorts.remove(port);
        }
    }

    /**
     * Hands out a proxy port from the shared pool. Redshift Serverless workgroups draw from the same
     * range as provisioned clusters, so both must reserve through this service or they could be given
     * the same port.
     */
    public int reserveProxyPort() {
        return allocateProxyPort();
    }

    /** Reserves a specific port, e.g. one persisted before a restart. Returns false if it is taken. */
    public boolean reserveProxyPort(int port) {
        return port > 0 && usedPorts.add(port);
    }

    /** The address and port clients use to reach a proxy bound to {@code proxyPort}. */
    public Endpoint advertisedEndpoint(int proxyPort) {
        return proxyEndpoint(proxyPort);
    }

    private Endpoint proxyEndpoint(int proxyPort) {
        String host = config.services().redshift().endpointHost()
                .filter(h -> !h.isBlank())
                .orElseGet(dockerHostResolver::resolve);
        return new Endpoint(host, proxyPort);
    }

    private String relayKey(String accountId, String clusterIdentifier) {
        return accountId + ":" + clusterIdentifier;
    }

    // Classifies a proxy login against current cluster state: the master pair and any live
    // GetClusterCredentials credential both run the backend leg as the cluster master, a known
    // broker user with a stale password is rejected, everyone else passes through to the backend.
    // Reading cluster state per call means a ModifyCluster password change takes effect for new
    // connections without a proxy restart.
    private PasswordValidator passwordValidatorFor(String accountId, String clusterIdentifier) {
        return (user, password) -> {
            Optional<Cluster> cluster = clusters.getForAccount(accountId, clusterIdentifier);
            if (cluster.isEmpty()) {
                // No cluster row to validate against: vouch for nothing. Falling through to the
                // broker would classify an unknown user as PASSTHROUGH, and the wire proxy reads
                // isMaster from its own start-time config, so a PASSTHROUGH there still opens the
                // backend as master, authenticating any password for the master username.
                return PasswordValidator.AuthResult.REJECT;
            }
            Cluster c = cluster.get();
            if (user.equals(c.getMasterUsername())) {
                // The master username is authoritative here: a wrong password must be rejected,
                // never handed to the broker (which only knows minted DbUsers) and never passed
                // through as if the user were unknown.
                return password.equals(c.getMasterPassword())
                        ? PasswordValidator.AuthResult.MASTER_EQUIVALENT
                        : PasswordValidator.AuthResult.REJECT;
            }
            return switch (credentialBroker.classify(accountId, clusterIdentifier, user, password)) {
                case MASTER_EQUIVALENT -> PasswordValidator.AuthResult.MASTER_EQUIVALENT;
                case REJECT -> PasswordValidator.AuthResult.REJECT;
                case PASSTHROUGH -> PasswordValidator.AuthResult.PASSTHROUGH;
            };
        };
    }

    // Package-private hook for tests: passwordValidatorFor is otherwise private.
    PasswordValidator passwordValidatorForTesting(String accountId, String clusterIdentifier) {
        return passwordValidatorFor(accountId, clusterIdentifier);
    }
}
