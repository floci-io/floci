package io.github.hectorvent.floci.services.bcmdataexports;

import com.fasterxml.jackson.core.type.TypeReference;
import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.core.common.RegionResolver;
import io.github.hectorvent.floci.core.storage.StorageBackend;
import io.github.hectorvent.floci.core.storage.StorageFactory;
import io.github.hectorvent.floci.services.bcmdataexports.model.DataQuery;
import io.github.hectorvent.floci.services.bcmdataexports.model.DestinationConfiguration;
import io.github.hectorvent.floci.services.bcmdataexports.model.Export;
import io.github.hectorvent.floci.services.bcmdataexports.model.ExportExecution;
import io.github.hectorvent.floci.services.bcmdataexports.model.RefreshCadence;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * AWS BCM Data Exports management plane.
 * <p>
 * Persists {@link Export} records (keyed by {@code <accountId>::<exportArn>})
 * and {@link ExportExecution} records (keyed by
 * {@code <accountId>::<exportArn>::<executionId>}). Account-scoping mirrors
 * AWS behavior where exports are private to the owning account.
 *
 * <p>This class is management-plane only — Parquet emission lands in a
 * follow-up step on the same branch.
 *
 * @see <a href="https://docs.aws.amazon.com/aws-cost-management/latest/APIReference/API_Operations_AWS_Billing_and_Cost_Management_Data_Exports.html">BCM Data Exports API</a>
 */
@ApplicationScoped
public class BcmDataExportsService {

    private static final Logger LOG = Logger.getLogger(BcmDataExportsService.class);

    private static final Pattern EXPORT_NAME_PATTERN = Pattern.compile("[A-Za-z0-9_-]+");
    private static final Set<String> ALLOWED_FREQUENCY = Set.of("SYNCHRONOUS");
    /**
     * Floci only emits Parquet at the moment. Real AWS also accepts
     * {@code TEXT_OR_CSV} / {@code GZIP} but Floci's emission engine writes
     * Parquet unconditionally; accepting other formats would let a definition
     * persist that no consumer can read. Restrict until CSV/GZIP support lands.
     */
    private static final Set<String> ALLOWED_FORMAT = Set.of("PARQUET");
    private static final Set<String> ALLOWED_COMPRESSION = Set.of("PARQUET");
    private static final Set<String> ALLOWED_OVERWRITE = Set.of("CREATE_NEW_REPORT", "OVERWRITE_REPORT");
    private static final Set<String> ALLOWED_OUTPUT_TYPE = Set.of("CUSTOM");

    private final StorageBackend<String, Export> exportStore;
    private final StorageBackend<String, ExportExecution> executionStore;
    private final RegionResolver regionResolver;

    @Inject
    public BcmDataExportsService(StorageFactory storageFactory, RegionResolver regionResolver) {
        this(storageFactory.create("bcmdataexports", "bcm-exports.json",
                        new TypeReference<Map<String, Export>>() {}),
                storageFactory.create("bcmdataexports", "bcm-executions.json",
                        new TypeReference<Map<String, ExportExecution>>() {}),
                regionResolver);
    }

    BcmDataExportsService(StorageBackend<String, Export> exportStore,
                          StorageBackend<String, ExportExecution> executionStore,
                          RegionResolver regionResolver) {
        this.exportStore = exportStore;
        this.executionStore = executionStore;
        this.regionResolver = regionResolver;
    }

    /** {@code CreateExport} — creates and persists a new {@link Export}. */
    public Export createExport(Export incoming, Map<String, String> resourceTags, String region) {
        validateExport(incoming);
        String accountId = regionResolver.getAccountId();

        if (findByName(incoming.getName()) != null) {
            throw new AwsException("ValidationException",
                    "An export with the name " + incoming.getName() + " already exists.", 400);
        }

        String exportArn = buildArn(region, accountId, incoming.getName());
        long now = System.currentTimeMillis();
        incoming.setExportArn(exportArn);
        incoming.setCreatedAt(now);
        incoming.setLastUpdatedAt(now);
        incoming.setExportStatus("HEALTHY");
        incoming.setOwnerAccountId(accountId);
        if (resourceTags != null) {
            incoming.setResourceTags(new HashMap<>(resourceTags));
        }

        exportStore.put(exportKey(exportArn), incoming);
        LOG.infov("Created BCM export: {0} (arn={1})", incoming.getName(), exportArn);
        return incoming;
    }

    /** {@code GetExport} — returns the {@link Export} matching {@code exportArn}. */
    public Export getExport(String exportArn) {
        requireNonEmpty(exportArn, "ExportArn");
        return exportStore.get(exportKey(exportArn))
                .orElseThrow(() -> new AwsException("ResourceNotFoundException",
                        "Export " + exportArn + " not found.", 400));
    }

    /** {@code ListExports} — returns every export owned by the calling account. */
    public List<Export> listExports() {
        return new ArrayList<>(exportStore.scan(key -> true));
    }

    /**
     * Returns every export across every account, grouped by
     * {@code ownerAccountId}. Used by the background emission scheduler to
     * iterate exports outside a request scope.
     */
    public Map<String, List<Export>> listAllExportsByAccount() {
        Collection<Export> all;
        if (exportStore instanceof io.github.hectorvent.floci.core.storage.AccountAwareStorageBackend<?> aware) {
            @SuppressWarnings("unchecked")
            io.github.hectorvent.floci.core.storage.AccountAwareStorageBackend<Export> typed =
                    (io.github.hectorvent.floci.core.storage.AccountAwareStorageBackend<Export>) aware;
            all = typed.scanAllAccounts();
        } else {
            all = exportStore.scan(key -> true);
        }
        Map<String, List<Export>> result = new java.util.LinkedHashMap<>();
        for (Export export : all) {
            String account = export.getOwnerAccountId();
            if (account == null || account.isEmpty()) {
                account = regionResolver.getAccountId();
            }
            result.computeIfAbsent(account, a -> new ArrayList<>()).add(export);
        }
        return result;
    }

    /** {@code UpdateExport} — replaces an export's mutable fields. */
    public Export updateExport(String exportArn, Export incoming) {
        requireNonEmpty(exportArn, "ExportArn");
        validateExport(incoming);
        String key = exportKey(exportArn);
        Export existing = exportStore.get(key).orElseThrow(() -> new AwsException(
                "ResourceNotFoundException",
                "Export " + exportArn + " not found.", 400));

        incoming.setExportArn(exportArn);
        incoming.setCreatedAt(existing.getCreatedAt());
        incoming.setLastUpdatedAt(System.currentTimeMillis());
        incoming.setExportStatus(existing.getExportStatus() == null ? "HEALTHY" : existing.getExportStatus());
        incoming.setOwnerAccountId(existing.getOwnerAccountId() != null
                ? existing.getOwnerAccountId() : regionResolver.getAccountId());
        if (incoming.getResourceTags() == null || incoming.getResourceTags().isEmpty()) {
            incoming.setResourceTags(existing.getResourceTags());
        }
        exportStore.put(key, incoming);
        LOG.infov("Updated BCM export: {0}", exportArn);
        return incoming;
    }

    /** {@code DeleteExport} — removes an export. Idempotent. */
    public Export deleteExport(String exportArn) {
        requireNonEmpty(exportArn, "ExportArn");
        String key = exportKey(exportArn);
        Export existing = exportStore.get(key).orElse(null);
        if (existing == null) {
            return null;
        }
        exportStore.delete(key);
        // Also remove any executions tied to this export to avoid orphan records.
        String execPrefix = exportArn + "::";
        for (String execKey : new ArrayList<>(executionStore.keys())) {
            if (execKey.startsWith(execPrefix)) {
                executionStore.delete(execKey);
            }
        }
        LOG.infov("Deleted BCM export: {0}", exportArn);
        return existing;
    }

    /** {@code ListExecutions} — returns every recorded execution for the export. */
    public List<ExportExecution> listExecutions(String exportArn) {
        requireNonEmpty(exportArn, "ExportArn");
        if (exportStore.get(exportKey(exportArn)).isEmpty()) {
            throw new AwsException("ResourceNotFoundException",
                    "Export " + exportArn + " not found.", 400);
        }
        String prefix = exportArn + "::";
        return new ArrayList<>(executionStore.scan(key -> key.startsWith(prefix)));
    }

    /** {@code GetExecution} — returns one execution record. */
    public ExportExecution getExecution(String exportArn, String executionId) {
        requireNonEmpty(exportArn, "ExportArn");
        requireNonEmpty(executionId, "ExecutionId");
        return executionStore.get(executionKey(exportArn, executionId))
                .orElseThrow(() -> new AwsException("ResourceNotFoundException",
                        "Execution " + executionId + " not found.", 400));
    }

    // ── Internal helpers exposed package-private for the emitter (next step) ──

    /**
     * Records a fresh execution under {@code accountId}. Used by the emission
     * scheduler. Writes through {@link io.github.hectorvent.floci.core.storage.AccountAwareStorageBackend#putForAccount}
     * when available so background calls outside a request scope still land in
     * the correct account partition.
     */
    public ExportExecution recordExecution(String accountId, String exportArn, String createdBy) {
        ExportExecution exec = new ExportExecution();
        exec.setExecutionId(UUID.randomUUID().toString());
        exec.setExportArn(exportArn);
        exec.setExportStatus("INITIATION_IN_PROCESS");
        exec.setCreatedBy(createdBy);
        exec.setCreatedAt(System.currentTimeMillis());
        String key = executionKey(exportArn, exec.getExecutionId());
        if (executionStore instanceof io.github.hectorvent.floci.core.storage.AccountAwareStorageBackend<?> aware) {
            @SuppressWarnings("unchecked")
            io.github.hectorvent.floci.core.storage.AccountAwareStorageBackend<ExportExecution> typed =
                    (io.github.hectorvent.floci.core.storage.AccountAwareStorageBackend<ExportExecution>) aware;
            typed.putForAccount(accountId, key, exec);
        } else {
            executionStore.put(key, exec);
        }
        return exec;
    }

    /**
     * Extracts the account-id segment from a BCM export ARN. Returns the default
     * account when the ARN doesn't carry one — matches Floci's single-account
     * behavior for callers that don't pass a 12-digit access key.
     */
    public String accountIdFromArn(String arn) {
        if (arn == null) {
            return regionResolver.getAccountId();
        }
        // arn:aws:bcm-data-exports:<region>:<account>:export/<name>
        String[] parts = arn.split(":", 6);
        if (parts.length >= 5 && !parts[4].isEmpty()) {
            return parts[4];
        }
        return regionResolver.getAccountId();
    }

    public void completeExecution(String accountId, ExportExecution exec, boolean success, String reason) {
        exec.setExportStatus(success ? "DELIVERY_SUCCESS" : "DELIVERY_FAILURE");
        exec.setCompletedAt(System.currentTimeMillis());
        if (reason != null) {
            exec.setStatusReason(reason);
        }
        String key = executionKey(exec.getExportArn(), exec.getExecutionId());
        if (executionStore instanceof io.github.hectorvent.floci.core.storage.AccountAwareStorageBackend<?> aware) {
            @SuppressWarnings("unchecked")
            io.github.hectorvent.floci.core.storage.AccountAwareStorageBackend<ExportExecution> typed =
                    (io.github.hectorvent.floci.core.storage.AccountAwareStorageBackend<ExportExecution>) aware;
            typed.putForAccount(accountId, key, exec);
        } else {
            executionStore.put(key, exec);
        }
    }

    private Export findByName(String name) {
        for (Export candidate : exportStore.scan(key -> true)) {
            if (name.equals(candidate.getName())) {
                return candidate;
            }
        }
        return null;
    }

    private void validateExport(Export e) {
        if (e == null) {
            throw new AwsException("ValidationException", "Export is required.", 400);
        }
        requireNonEmpty(e.getName(), "Export.Name");
        if (!EXPORT_NAME_PATTERN.matcher(e.getName()).matches()) {
            throw new AwsException("ValidationException",
                    "Export.Name may only contain alphanumeric characters, hyphens, and underscores.", 400);
        }
        if (e.getName().length() > 128) {
            throw new AwsException("ValidationException",
                    "Export.Name must be at most 128 characters long.", 400);
        }
        DataQuery dq = e.getDataQuery();
        if (dq == null || dq.getQueryStatement() == null || dq.getQueryStatement().isEmpty()) {
            throw new AwsException("ValidationException",
                    "Export.DataQuery.QueryStatement is required.", 400);
        }
        validateDestination(e.getDestinationConfigurations());
        validateRefreshCadence(e.getRefreshCadence());
    }

    private void validateDestination(DestinationConfiguration dest) {
        if (dest == null || dest.getS3Destination() == null) {
            throw new AwsException("ValidationException",
                    "Export.DestinationConfigurations.S3Destination is required.", 400);
        }
        DestinationConfiguration.S3Destination s3 = dest.getS3Destination();
        requireNonEmpty(s3.getS3Bucket(), "S3Destination.S3Bucket");
        requireValidBucketName(s3.getS3Bucket(), "S3Destination.S3Bucket");
        requireNonEmpty(s3.getS3Region(), "S3Destination.S3Region");
        if (s3.getS3Prefix() == null) {
            s3.setS3Prefix("");
        } else if (!s3.getS3Prefix().isEmpty()) {
            requireSafeKeySegment(s3.getS3Prefix(), "S3Destination.S3Prefix");
        }
        DestinationConfiguration.S3OutputConfigurations out = s3.getS3OutputConfigurations();
        if (out != null) {
            if (out.getFormat() != null && !ALLOWED_FORMAT.contains(out.getFormat())) {
                throw new AwsException("ValidationException",
                        "S3OutputConfigurations.Format must be one of " + ALLOWED_FORMAT, 400);
            }
            if (out.getCompression() != null && !ALLOWED_COMPRESSION.contains(out.getCompression())) {
                throw new AwsException("ValidationException",
                        "S3OutputConfigurations.Compression must be one of " + ALLOWED_COMPRESSION, 400);
            }
            if (out.getOverwrite() != null && !ALLOWED_OVERWRITE.contains(out.getOverwrite())) {
                throw new AwsException("ValidationException",
                        "S3OutputConfigurations.Overwrite must be one of " + ALLOWED_OVERWRITE, 400);
            }
            if (out.getOutputType() != null && !ALLOWED_OUTPUT_TYPE.contains(out.getOutputType())) {
                throw new AwsException("ValidationException",
                        "S3OutputConfigurations.OutputType must be one of " + ALLOWED_OUTPUT_TYPE, 400);
            }
        }
    }

    private void validateRefreshCadence(RefreshCadence cadence) {
        if (cadence == null) {
            throw new AwsException("ValidationException",
                    "Export.RefreshCadence is required.", 400);
        }
        if (!ALLOWED_FREQUENCY.contains(cadence.getFrequency())) {
            throw new AwsException("ValidationException",
                    "RefreshCadence.Frequency must be one of " + ALLOWED_FREQUENCY, 400);
        }
    }

    private String buildArn(String region, String accountId, String exportName) {
        return regionResolver.buildArn("bcm-data-exports", region, "export/" + exportName);
    }

    private static String exportKey(String exportArn) {
        return exportArn;
    }

    private static String executionKey(String exportArn, String executionId) {
        return exportArn + "::" + executionId;
    }

    private static void requireNonEmpty(String value, String field) {
        if (value == null || value.isEmpty()) {
            throw new AwsException("ValidationException",
                    "1 validation error detected: Value at '" + field + "' failed to satisfy constraint: Member must not be null.", 400);
        }
    }

    private static void requireValidBucketName(String bucket, String field) {
        if (bucket.length() < 3 || bucket.length() > 63) {
            throw new AwsException("ValidationException",
                    field + " must be between 3 and 63 characters.", 400);
        }
        for (int i = 0; i < bucket.length(); i++) {
            char c = bucket.charAt(i);
            boolean valid = (c >= 'a' && c <= 'z')
                    || (c >= '0' && c <= '9')
                    || c == '-' || c == '.';
            if (!valid) {
                throw new AwsException("ValidationException",
                        field + " contains invalid characters.", 400);
            }
        }
        if (bucket.startsWith("-") || bucket.endsWith("-")
                || bucket.startsWith(".") || bucket.endsWith(".")
                || bucket.contains("..")) {
            throw new AwsException("ValidationException",
                    field + " is not a valid S3 bucket name.", 400);
        }
    }

    private static void requireSafeKeySegment(String value, String field) {
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            boolean ok = (c >= 'A' && c <= 'Z')
                    || (c >= 'a' && c <= 'z')
                    || (c >= '0' && c <= '9')
                    || c == '-' || c == '_' || c == '.' || c == '/';
            if (!ok) {
                throw new AwsException("ValidationException",
                        field + " contains characters not permitted in an S3 key segment.", 400);
            }
        }
    }
}
