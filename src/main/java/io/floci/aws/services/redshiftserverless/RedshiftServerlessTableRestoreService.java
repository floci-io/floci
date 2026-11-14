package io.floci.aws.services.redshiftserverless;

import com.fasterxml.jackson.core.type.TypeReference;
import io.floci.aws.core.common.AwsException;
import io.floci.aws.core.common.PaginatedResult;
import io.floci.aws.core.common.Pagination;
import io.floci.aws.core.common.RegionResolver;
import io.floci.aws.core.common.Resettable;
import io.floci.aws.core.storage.AccountAwareStorageBackend;
import io.floci.aws.core.storage.StorageFactory;
import io.floci.aws.services.redshift.RedshiftSnapshotTableExtractor;
import io.floci.aws.services.redshift.RedshiftTableRestorer;
import io.floci.aws.services.redshift.RedshiftTableRestorer.TableNames;
import io.floci.aws.services.redshift.model.TableRestoreStatus;
import io.floci.aws.services.redshiftserverless.RedshiftServerlessService.TableRestoreSource;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.time.Clock;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Restores one table of a Redshift Serverless snapshot into a workgroup under a new name, and keeps the status of
 * each request. The restore runs inside the request, so a request that was accepted has already ended in
 * {@code SUCCEEDED} or {@code FAILED} when it is next read; the response to the request itself shows it as
 * {@code PENDING}, as AWS does.
 */
@ApplicationScoped
public class RedshiftServerlessTableRestoreService implements Resettable {

    private static final Logger LOG = Logger.getLogger(RedshiftServerlessTableRestoreService.class);

    private final AccountAwareStorageBackend<TableRestoreStatus> restores;
    private final RedshiftServerlessService serverless;
    private final RedshiftTableRestorer restorer;
    private final RegionResolver regionResolver;
    private final Clock clock;

    @Inject
    public RedshiftServerlessTableRestoreService(StorageFactory storageFactory, RedshiftServerlessService serverless,
                                                 RedshiftTableRestorer restorer, RegionResolver regionResolver,
                                                 Clock clock) {
        this.restores = storageFactory.create("redshiftserverless", "redshiftserverless-table-restores.json",
                new TypeReference<Map<String, TableRestoreStatus>>() {});
        this.serverless = serverless;
        this.restorer = restorer;
        this.regionResolver = regionResolver;
        this.clock = clock;
    }

    /** The members a {@code RestoreTableFromSnapshot} request carries. */
    public record Request(String namespaceName, String workgroupName, String snapshotName, String sourceDatabaseName,
                          String sourceSchemaName, String sourceTableName, String targetDatabaseName,
                          String targetSchemaName, String newTableName, boolean caseSensitive) {
    }

    public TableRestoreStatus restoreTable(Request request, String region) {
        ServerlessSupport.requireText(request.namespaceName(), "namespaceName");
        ServerlessSupport.requireText(request.workgroupName(), "workgroupName");
        ServerlessSupport.requireText(request.snapshotName(), "snapshotName");
        ServerlessSupport.requireText(request.sourceDatabaseName(), "sourceDatabaseName");
        ServerlessSupport.requireText(request.sourceTableName(), "sourceTableName");
        ServerlessSupport.requireText(request.newTableName(), "newTableName");
        TableRestoreSource source = serverless.tableRestoreSource(request.namespaceName(), request.workgroupName(),
                request.snapshotName(), region);
        TableNames names = new TableNames(request.sourceDatabaseName(), request.sourceSchemaName(),
                request.sourceTableName(), request.targetDatabaseName(), request.targetSchemaName(),
                request.newTableName(), request.caseSensitive());
        if (!names.sourceDatabaseIs(source.namespace().getDbName())) {
            throw ServerlessSupport.validation("The database " + request.sourceDatabaseName()
                    + " does not exist in the snapshot.");
        }
        // A workgroup holds one database; psql would also read an odd name as a connection string.
        if (!names.targetDatabaseIs(source.namespace().getDbName())) {
            throw ServerlessSupport.validation("The database " + request.targetDatabaseName() + " does not exist.");
        }
        String script;
        try {
            script = restorer.script(source.dump(), names.sourceSchema(), names.sourceTable(), names.targetSchema(),
                    names.newTable(), request.caseSensitive()).orElseThrow(() -> ServerlessSupport.validation(
                            "The table " + names.sourceSchema() + "." + names.sourceTable()
                                    + " does not exist in the database " + names.sourceDatabase() + " of the snapshot."));
        } catch (RedshiftSnapshotTableExtractor.UnsupportedTableException e) {
            throw ServerlessSupport.validation(e.getMessage());
        }

        TableRestoreStatus status = new TableRestoreStatus();
        status.setTableRestoreRequestId(UUID.randomUUID().toString());
        status.setRequestTime(clock.instant());
        status.setNamespaceName(request.namespaceName());
        status.setWorkgroupName(request.workgroupName());
        status.setSnapshotIdentifier(request.snapshotName());
        names.applyTo(status);
        status.setTotalDataInMegaBytes(RedshiftTableRestorer.megabytes(script));
        status.setProgressInMegaBytes(0L);
        TableRestoreStatus accepted = new TableRestoreStatus(status);
        accepted.setStatus(TableRestoreStatus.PENDING);

        String key = storageKey(region, status.getTableRestoreRequestId());
        status.setStatus(TableRestoreStatus.IN_PROGRESS);
        restores.put(key, status);
        try {
            restorer.run(regionResolver.getAccountId(),
                    RedshiftServerlessRuntime.backendId(region, request.workgroupName()),
                    source.workgroup().getMasterUsername(), names.targetDatabase(), script);
            status.setStatus(TableRestoreStatus.SUCCEEDED);
            status.setProgressInMegaBytes(status.getTotalDataInMegaBytes());
        } catch (AwsException e) {
            LOG.warnv(e, "Table restore {0} into workgroup {1} failed", status.getTableRestoreRequestId(),
                    request.workgroupName());
            status.setStatus(TableRestoreStatus.FAILED);
            status.setMessage(e.getMessage());
        }
        restores.put(key, status);
        return accepted;
    }

    public TableRestoreStatus getTableRestoreStatus(String tableRestoreRequestId, String region) {
        ServerlessSupport.requireText(tableRestoreRequestId, "tableRestoreRequestId");
        return restores.get(storageKey(region, tableRestoreRequestId))
                .filter(restore -> hasWorkgroup(restore, region))
                .map(TableRestoreStatus::new)
                .orElseThrow(() -> ServerlessSupport.notFound(
                        "The table restore request " + tableRestoreRequestId + " was not found."));
    }

    /** The Region's restore requests in the order they were made, narrowed by namespace and workgroup. */
    public PaginatedResult<TableRestoreStatus> listTableRestoreStatus(
            String namespaceName, String workgroupName, Integer maxResults, String nextToken, String region) {
        int limit = ServerlessSupport.pageSize(maxResults);
        List<TableRestoreStatus> matching = ServerlessSupport.live(restores, region, restore -> hasWorkgroup(restore, region),
                restore -> storageKey(region, restore.getTableRestoreRequestId())).stream()
                .filter(restore -> namespaceName == null || namespaceName.equals(restore.getNamespaceName()))
                .filter(restore -> workgroupName == null || workgroupName.equals(restore.getWorkgroupName()))
                .sorted(Comparator.comparing(TableRestoreStatus::getRequestTime)
                        .thenComparing(TableRestoreStatus::getTableRestoreRequestId))
                .map(TableRestoreStatus::new)
                .toList();
        return Pagination.paginate(matching, TableRestoreStatus::getTableRestoreRequestId, limit, nextToken,
                "redshift-serverless-table-restore",
                token -> ServerlessSupport.validation("Invalid nextToken."));
    }

    @Override
    public void clear() {
        restores.clear();
    }

    private boolean hasWorkgroup(TableRestoreStatus restore, String region) {
        return serverless.findWorkgroup(restore.getWorkgroupName(), region).isPresent();
    }

    private static String storageKey(String region, String requestId) {
        return region + "::" + requestId;
    }
}
