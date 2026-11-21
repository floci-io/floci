package io.floci.aws.services.redshift;

import com.fasterxml.jackson.core.type.TypeReference;
import io.floci.aws.core.common.AwsException;
import io.floci.aws.core.common.PaginatedResult;
import io.floci.aws.core.common.Pagination;
import io.floci.aws.core.common.RegionResolver;
import io.floci.aws.core.common.Resettable;
import io.floci.aws.core.storage.AccountAwareStorageBackend;
import io.floci.aws.core.storage.StorageFactory;
import io.floci.aws.services.redshift.RedshiftService.TableRestoreSource;
import io.floci.aws.services.redshift.RedshiftTableRestorer.TableNames;
import io.floci.aws.services.redshift.model.TableRestoreStatus;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.time.Clock;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

/**
 * Restores one table of a provisioned cluster's snapshot into the cluster under a new name, and keeps the
 * status of each request. The restore runs inside the request, so a request that was accepted has already
 * ended in {@code SUCCEEDED} or {@code FAILED} when it is next described; the response to the request itself
 * shows it as {@code PENDING}, as AWS does.
 */
@ApplicationScoped
public class RedshiftTableRestoreService implements Resettable {

    private static final int MIN_MAX_RECORDS = 20;
    private static final int MAX_MAX_RECORDS = 100;

    private final AccountAwareStorageBackend<TableRestoreStatus> restores;
    private final RedshiftService redshift;
    private final RedshiftTableRestorer restorer;
    private final RegionResolver regionResolver;
    private final Clock clock;

    @Inject
    public RedshiftTableRestoreService(StorageFactory storageFactory, RedshiftService redshift,
                                       RedshiftTableRestorer restorer, RegionResolver regionResolver, Clock clock) {
        this.restores = storageFactory.create("redshift", "redshift-table-restores.json",
                new TypeReference<Map<String, TableRestoreStatus>>() {});
        this.redshift = redshift;
        this.restorer = restorer;
        this.regionResolver = regionResolver;
        this.clock = clock;
    }

    /** The members a {@code RestoreTableFromClusterSnapshot} request carries. */
    public record Request(String clusterIdentifier, String snapshotIdentifier, String sourceDatabaseName,
                          String sourceSchemaName, String sourceTableName, String targetDatabaseName,
                          String targetSchemaName, String newTableName, boolean caseSensitive) {
    }

    public TableRestoreStatus restoreTable(Request request) {
        return redshift.withResourceLock(regionResolver.getAccountId(), "cluster", request.clusterIdentifier(),
                () -> restore(request));
    }

    private TableRestoreStatus restore(Request request) {
        TableRestoreSource source = redshift.tableRestoreSource(request.clusterIdentifier(),
                request.snapshotIdentifier());
        TableNames names = new TableNames(request.sourceDatabaseName(), request.sourceSchemaName(),
                request.sourceTableName(), request.targetDatabaseName(), request.targetSchemaName(),
                request.newTableName(), request.caseSensitive());
        return restorer.restore(names, source.dump(), new RedshiftTableRestorer.Backend(regionResolver.getAccountId(),
                request.clusterIdentifier(), source.cluster().getMasterUsername(), source.database()),
                clock.instant(), RedshiftTableRestoreService::invalidArgument, status -> {
                    status.setClusterIdentifier(request.clusterIdentifier());
                    status.setSnapshotIdentifier(request.snapshotIdentifier());
                }, status -> restores.put(status.getTableRestoreRequestId(), status));
    }

    /**
     * The status of the cluster's restore requests in the order they were made, or of the one request given.
     * Without a cluster every cluster of the account is listed.
     */
    public PaginatedResult<TableRestoreStatus> describeTableRestoreStatus(
            String clusterIdentifier, String tableRestoreRequestId, Integer maxRecords, String marker) {
        int limit = maxRecords == null ? MAX_MAX_RECORDS : maxRecords;
        if (limit < MIN_MAX_RECORDS || limit > MAX_MAX_RECORDS) {
            throw new AwsException("InvalidParameterValue",
                    "MaxRecords must be between " + MIN_MAX_RECORDS + " and " + MAX_MAX_RECORDS + ".", 400);
        }
        if (clusterIdentifier != null) {
            redshift.describeClusters(clusterIdentifier);
        }
        List<TableRestoreStatus> matching = liveRestores().stream()
                .filter(restore -> clusterIdentifier == null || clusterIdentifier.equals(restore.getClusterIdentifier()))
                .filter(restore -> tableRestoreRequestId == null
                        || tableRestoreRequestId.equals(restore.getTableRestoreRequestId()))
                .sorted(Comparator.comparing(TableRestoreStatus::getRequestTime)
                        .thenComparing(TableRestoreStatus::getTableRestoreRequestId))
                .map(TableRestoreStatus::new)
                .toList();
        if (tableRestoreRequestId != null && matching.isEmpty()) {
            throw new AwsException("TableRestoreNotFoundFault",
                    "The table restore request " + tableRestoreRequestId + " was not found.", 400);
        }
        return Pagination.paginate(matching, TableRestoreStatus::getTableRestoreRequestId, limit, marker,
                "redshift-table-restore", token -> new AwsException("InvalidParameterValue", "Invalid Marker.", 400));
    }

    /** The account's restore requests whose cluster is still there; those of a deleted one are dropped as found. */
    private List<TableRestoreStatus> liveRestores() {
        return restores.scan(key -> true).stream()
                .filter(restore -> {
                    if (redshift.hasCluster(restore.getClusterIdentifier())) {
                        return true;
                    }
                    restores.delete(restore.getTableRestoreRequestId());
                    return false;
                })
                .toList();
    }

    @Override
    public void clear() {
        restores.clear();
    }

    private static AwsException invalidArgument(String message) {
        return new AwsException("InvalidTableRestoreArgument", message, 400);
    }
}
