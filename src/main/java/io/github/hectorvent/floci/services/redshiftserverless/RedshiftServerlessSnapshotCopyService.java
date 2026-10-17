package io.github.hectorvent.floci.services.redshiftserverless;

import com.fasterxml.jackson.core.type.TypeReference;
import io.github.hectorvent.floci.core.common.PaginatedResult;
import io.github.hectorvent.floci.core.common.Pagination;
import io.github.hectorvent.floci.core.common.RegionResolver;
import io.github.hectorvent.floci.core.common.Resettable;
import io.github.hectorvent.floci.core.storage.AccountAwareStorageBackend;
import io.github.hectorvent.floci.core.storage.StorageFactory;
import io.github.hectorvent.floci.services.redshiftserverless.model.Namespace;
import io.github.hectorvent.floci.services.redshiftserverless.model.SnapshotCopyConfiguration;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * Redshift Serverless snapshot copy configurations. They are stored and echoed, not run: Floci
 * copies no snapshot to the destination Region.
 *
 * <p>A configuration belongs to a namespace but lives in its own store, so deleting the namespace
 * needs no hook here. Each read checks that the namespace is still there and drops the
 * configuration otherwise.
 */
@ApplicationScoped
public class RedshiftServerlessSnapshotCopyService implements Resettable {

    /** The model gives {@code destinationRegion} no pattern, so the shape of a Region name is checked here. */
    private static final Pattern REGION_NAME = Pattern.compile("^[a-z0-9-]+$");
    private static final int MAX_RETENTION_DAYS = 3653;

    private final AccountAwareStorageBackend<SnapshotCopyConfiguration> configurations;
    private final RegionResolver regionResolver;
    private final RedshiftServerlessService serverless;

    @Inject
    public RedshiftServerlessSnapshotCopyService(StorageFactory storageFactory, RegionResolver regionResolver,
                                                 RedshiftServerlessService serverless) {
        this.configurations = storageFactory.create("redshiftserverless",
                "redshiftserverless-snapshotcopyconfigurations.json",
                new TypeReference<Map<String, SnapshotCopyConfiguration>>() {});
        this.regionResolver = regionResolver;
        this.serverless = serverless;
    }

    public synchronized SnapshotCopyConfiguration createSnapshotCopyConfiguration(
            String namespaceName, String destinationRegion, String destinationKmsKeyId,
            Integer snapshotRetentionPeriod, String region) {
        if (!REGION_NAME.matcher(destinationRegion).matches()) {
            throw ServerlessSupport.validation("destinationRegion must be lower-case letters, digits and hyphens.");
        }
        if (destinationRegion.equals(region)) {
            throw ServerlessSupport.validation("destinationRegion must differ from the namespace's Region.");
        }
        validateRetention(snapshotRetentionPeriod);
        Namespace namespace = serverless.findNamespace(namespaceName, region)
                .orElseThrow(() -> ServerlessSupport.notFound("The namespace " + namespaceName + " was not found."));
        boolean duplicate = live(region).stream().anyMatch(existing ->
                existing.getNamespaceName().equals(namespaceName));
        if (duplicate) {
            throw ServerlessSupport.conflict("The namespace " + namespaceName
                    + " already has a snapshot copy configuration.");
        }

        SnapshotCopyConfiguration configuration = new SnapshotCopyConfiguration();
        configuration.setSnapshotCopyConfigurationId(UUID.randomUUID().toString());
        configuration.setSnapshotCopyConfigurationArn(regionResolver.buildArn("redshift-serverless", region,
                "snapshotcopyconfiguration/" + configuration.getSnapshotCopyConfigurationId()));
        configuration.setNamespaceName(namespaceName);
        configuration.setOwnerId(namespace.getNamespaceId());
        configuration.setDestinationRegion(destinationRegion);
        configuration.setDestinationKmsKeyId(destinationKmsKeyId);
        configuration.setSnapshotRetentionPeriod(snapshotRetentionPeriod);
        configurations.put(storageKey(region, configuration.getSnapshotCopyConfigurationId()), configuration);
        return new SnapshotCopyConfiguration(configuration);
    }

    public synchronized PaginatedResult<SnapshotCopyConfiguration> listSnapshotCopyConfigurations(
            String namespaceName, String region, Integer maxResults, String nextToken) {
        int limit = ServerlessSupport.pageSize(maxResults);
        List<SnapshotCopyConfiguration> matching = live(region).stream()
                .filter(configuration -> namespaceName == null
                        || namespaceName.equals(configuration.getNamespaceName()))
                .sorted(Comparator.comparing(SnapshotCopyConfiguration::getSnapshotCopyConfigurationId))
                .map(SnapshotCopyConfiguration::new)
                .toList();
        return Pagination.paginate(matching, SnapshotCopyConfiguration::getSnapshotCopyConfigurationId,
                limit, nextToken, "snapshot-copy",
                token -> ServerlessSupport.invalidPagination("Invalid nextToken."));
    }

    /** Changes the retention of copied snapshots, the only member AWS lets an update change. */
    public synchronized SnapshotCopyConfiguration updateSnapshotCopyConfiguration(
            String snapshotCopyConfigurationId, Integer snapshotRetentionPeriod, String region) {
        SnapshotCopyConfiguration updated = new SnapshotCopyConfiguration(require(snapshotCopyConfigurationId, region));
        if (snapshotRetentionPeriod != null) {
            validateRetention(snapshotRetentionPeriod);
            updated.setSnapshotRetentionPeriod(snapshotRetentionPeriod);
            configurations.put(storageKey(region, snapshotCopyConfigurationId), updated);
        }
        return new SnapshotCopyConfiguration(updated);
    }

    public synchronized SnapshotCopyConfiguration deleteSnapshotCopyConfiguration(
            String snapshotCopyConfigurationId, String region) {
        SnapshotCopyConfiguration existing = new SnapshotCopyConfiguration(
                require(snapshotCopyConfigurationId, region));
        configurations.delete(storageKey(region, snapshotCopyConfigurationId));
        return existing;
    }

    @Override
    public void clear() {
        configurations.clear();
    }

    private SnapshotCopyConfiguration require(String id, String region) {
        SnapshotCopyConfiguration configuration = configurations.get(storageKey(region, id)).orElse(null);
        if (configuration != null && !isOnSameNamespace(configuration, region)) {
            configurations.delete(storageKey(region, id));
            configuration = null;
        }
        if (configuration == null) {
            throw ServerlessSupport.notFound("The snapshot copy configuration " + id + " was not found.");
        }
        return configuration;
    }

    /** The region's configurations whose namespace is still there; any other is deleted as it is found. */
    private List<SnapshotCopyConfiguration> live(String region) {
        return ServerlessSupport.live(configurations, region,
                configuration -> isOnSameNamespace(configuration, region),
                configuration -> storageKey(region, configuration.getSnapshotCopyConfigurationId()));
    }

    /** A namespace recreated under the same name has a new id, so it never inherits its predecessor's configurations. */
    private boolean isOnSameNamespace(SnapshotCopyConfiguration configuration, String region) {
        return serverless.findNamespace(configuration.getNamespaceName(), region)
                .map(namespace -> Objects.equals(namespace.getNamespaceId(), configuration.getOwnerId()))
                .orElse(false);
    }

    private static void validateRetention(Integer retentionPeriod) {
        if (retentionPeriod != null && retentionPeriod != -1
                && (retentionPeriod < 1 || retentionPeriod > MAX_RETENTION_DAYS)) {
            throw ServerlessSupport.validation(
                    "snapshotRetentionPeriod must be -1 (indefinite) or between 1 and " + MAX_RETENTION_DAYS + ".");
        }
    }

    private static String storageKey(String region, String id) {
        return region + "::" + id;
    }
}
