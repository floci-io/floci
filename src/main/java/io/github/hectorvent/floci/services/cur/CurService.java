package io.github.hectorvent.floci.services.cur;

import com.fasterxml.jackson.core.type.TypeReference;
import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.core.common.RegionResolver;
import io.github.hectorvent.floci.core.storage.StorageBackend;
import io.github.hectorvent.floci.core.storage.StorageFactory;
import io.github.hectorvent.floci.services.cur.model.ReportDefinition;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * AWS CUR (Cost and Usage Report) management plane.
 * <p>
 * Persists {@link ReportDefinition} records keyed by
 * {@code <accountId>::<region>::<reportName>} so the same report name is
 * scoped per account and per region (matching AWS, where CUR is regional and
 * report names are unique within an account).
 *
 * <p>This class is management-plane only — no Parquet emission. Emission lands
 * in a follow-up step on the same branch.
 *
 * @see <a href="https://docs.aws.amazon.com/aws-cost-management/latest/APIReference/API_Operations_AWS_Cost_and_Usage_Report_Service.html">CUR API</a>
 */
@ApplicationScoped
public class CurService {

    private static final Logger LOG = Logger.getLogger(CurService.class);

    /** AWS hard limit per account: 5 report definitions. */
    private static final int MAX_REPORTS_PER_ACCOUNT = 5;
    /** AWS allows the report name to contain alphanumerics, hyphens, and underscores. */
    private static final java.util.regex.Pattern REPORT_NAME_PATTERN =
            java.util.regex.Pattern.compile("[A-Za-z0-9_-]+");

    private static final Set<String> ALLOWED_TIME_UNITS = Set.of("HOURLY", "DAILY", "MONTHLY");
    /**
     * Floci only emits Parquet at the moment. Real CUR also accepts
     * {@code textORcsv} but Floci's emission engine writes Parquet
     * unconditionally, so accepting {@code textORcsv} would let a definition
     * persist that no consumer can read. Reject it explicitly until CSV
     * support lands in a follow-up PR.
     */
    private static final Set<String> ALLOWED_FORMATS = Set.of("Parquet");
    private static final Set<String> ALLOWED_COMPRESSIONS = Set.of("Parquet");
    private static final Set<String> ALLOWED_VERSIONING = Set.of("CREATE_NEW_REPORT", "OVERWRITE_REPORT");
    private static final Set<String> ALLOWED_ARTIFACTS = Set.of("REDSHIFT", "QUICKSIGHT", "ATHENA");
    private static final Set<String> ALLOWED_SCHEMA_ELEMENTS = Set.of("RESOURCES", "SPLIT_COST_ALLOCATION_DATA", "MANUAL_DISCOUNT_COMPATIBILITY");

    private final StorageBackend<String, ReportDefinition> store;
    private final RegionResolver regionResolver;

    @Inject
    public CurService(StorageFactory storageFactory, RegionResolver regionResolver) {
        this(storageFactory.create("cur", "cur-report-definitions.json",
                        new TypeReference<Map<String, ReportDefinition>>() {}),
                regionResolver);
    }

    CurService(StorageBackend<String, ReportDefinition> store, RegionResolver regionResolver) {
        this.store = store;
        this.regionResolver = regionResolver;
    }

    /** {@code PutReportDefinition} — creates a new report. Fails if one already exists. */
    public ReportDefinition putReportDefinition(ReportDefinition definition, String region) {
        validateReportDefinition(definition);
        String key = compositeKey(region, definition.getReportName());

        if (store.get(key).isPresent()) {
            throw new AwsException("DuplicateReportNameException",
                    "A report with the name " + definition.getReportName() + " already exists.", 400);
        }
        if (countForCurrentAccount() >= MAX_REPORTS_PER_ACCOUNT) {
            throw new AwsException("ReportLimitReachedException",
                    "This account has the maximum number of report definitions ("
                            + MAX_REPORTS_PER_ACCOUNT + ").", 400);
        }

        Instant now = Instant.now();
        definition.setCreatedDate(now);
        definition.setLastUpdatedDate(now);
        definition.setReportStatus("PENDING");
        definition.setOwnerAccountId(regionResolver.getAccountId());
        store.put(key, definition);
        LOG.infov("Created CUR report: {0} (region={1})",
                definition.getReportName(), region);
        return definition;
    }

    /** {@code ModifyReportDefinition} — replaces an existing report's definition. */
    public ReportDefinition modifyReportDefinition(String reportName, ReportDefinition incoming, String region) {
        requireNonEmpty(reportName, "ReportName");
        if (!reportName.equals(incoming.getReportName())) {
            throw new AwsException("ValidationException",
                    "ReportName in the body must match the ReportName parameter.", 400);
        }
        validateReportDefinition(incoming);

        String key = compositeKey(region, reportName);
        ReportDefinition existing = store.get(key).orElseThrow(() -> new AwsException(
                "ReportNotFoundException",
                "Report " + reportName + " not found.", 400));

        incoming.setCreatedDate(existing.getCreatedDate());
        incoming.setLastUpdatedDate(Instant.now());
        incoming.setReportStatus(existing.getReportStatus() == null ? "PENDING" : existing.getReportStatus());
        incoming.setOwnerAccountId(existing.getOwnerAccountId() != null
                ? existing.getOwnerAccountId() : regionResolver.getAccountId());
        store.put(key, incoming);
        LOG.infov("Modified CUR report: {0}", reportName);
        return incoming;
    }

    /** {@code DescribeReportDefinitions} — returns all reports for the calling account. */
    public List<ReportDefinition> describeReportDefinitions() {
        return new ArrayList<>(store.scan(key -> true));
    }

    /**
     * Returns every report definition stored across every account, keyed by
     * {@code ownerAccountId}. Used by the background emission scheduler that
     * runs outside a request scope (where the storage backend would otherwise
     * default to the single configured account and miss reports created under
     * other access keys).
     *
     * <p>Account attribution comes from the {@code ownerAccountId} field stamped
     * at {@code PutReportDefinition} time, not from the storage prefix, so this
     * also works on the in-memory test backend that has no account awareness.
     */
    public Map<String, List<ReportDefinition>> listAllReportsByAccount() {
        Collection<ReportDefinition> all;
        if (store instanceof io.github.hectorvent.floci.core.storage.AccountAwareStorageBackend<?> aware) {
            @SuppressWarnings("unchecked")
            io.github.hectorvent.floci.core.storage.AccountAwareStorageBackend<ReportDefinition> typed =
                    (io.github.hectorvent.floci.core.storage.AccountAwareStorageBackend<ReportDefinition>) aware;
            all = typed.scanAllAccounts();
        } else {
            all = store.scan(key -> true);
        }
        Map<String, List<ReportDefinition>> result = new java.util.LinkedHashMap<>();
        for (ReportDefinition def : all) {
            String accountId = def.getOwnerAccountId();
            if (accountId == null || accountId.isEmpty()) {
                accountId = regionResolver.getAccountId();
            }
            result.computeIfAbsent(accountId, a -> new ArrayList<>()).add(def);
        }
        return result;
    }

    /**
     * Returns the freshest stored copy of {@code definition}, or the input
     * itself when the report has been deleted between the original mutation
     * and this read. Lets handlers serialize the post-emission ReportStatus
     * without re-fetching by name.
     */
    public ReportDefinition getReportDefinitionOrSelf(ReportDefinition definition, String region) {
        return store.get(compositeKey(region, definition.getReportName()))
                .orElse(definition);
    }

    /**
     * Updates the {@code ReportStatus.LastStatus} field on a stored definition.
     * Called by {@code CurEmissionScheduler} after an emission run to expose
     * success/error to {@code DescribeReportDefinitions} consumers. Silent
     * no-op if the report has been deleted in the meantime.
     */
    public void markReportStatus(String reportName, String region, String status) {
        if (reportName == null || region == null || status == null) {
            return;
        }
        String key = compositeKey(region, reportName);
        store.get(key).ifPresent(def -> {
            def.setReportStatus(status);
            def.setLastUpdatedDate(Instant.now());
            store.put(key, def);
        });
    }

    /**
     * Variant of {@link #markReportStatus} for background workers that aren't
     * inside a request scope. Writes through the account-aware storage backend
     * directly under {@code accountId} rather than relying on the request
     * context.
     */
    public void markReportStatusForAccount(String accountId, String reportName, String region, String status) {
        if (accountId == null || reportName == null || region == null || status == null) {
            return;
        }
        if (!(store instanceof io.github.hectorvent.floci.core.storage.AccountAwareStorageBackend<?> aware)) {
            markReportStatus(reportName, region, status);
            return;
        }
        @SuppressWarnings("unchecked")
        io.github.hectorvent.floci.core.storage.AccountAwareStorageBackend<ReportDefinition> typed =
                (io.github.hectorvent.floci.core.storage.AccountAwareStorageBackend<ReportDefinition>) aware;
        String key = compositeKey(region, reportName);
        typed.getForAccount(accountId, key).ifPresent(def -> {
            def.setReportStatus(status);
            def.setLastUpdatedDate(Instant.now());
            typed.putForAccount(accountId, key, def);
        });
    }

    /** {@code DeleteReportDefinition} — removes a report; idempotent (returns null when absent). */
    public ReportDefinition deleteReportDefinition(String reportName, String region) {
        requireNonEmpty(reportName, "ReportName");
        String key = compositeKey(region, reportName);
        ReportDefinition existing = store.get(key).orElse(null);
        if (existing != null) {
            store.delete(key);
            LOG.infov("Deleted CUR report: {0}", reportName);
        }
        return existing;
    }

    private long countForCurrentAccount() {
        return store.keys().size();
    }

    private void validateReportDefinition(ReportDefinition d) {
        if (d == null) {
            throw new AwsException("ValidationException", "ReportDefinition is required.", 400);
        }
        requireNonEmpty(d.getReportName(), "ReportName");
        if (!REPORT_NAME_PATTERN.matcher(d.getReportName()).matches()) {
            throw new AwsException("ValidationException",
                    "ReportName may only contain alphanumeric characters, hyphens, and underscores.", 400);
        }
        if (d.getReportName().length() > 256) {
            throw new AwsException("ValidationException",
                    "ReportName must be at most 256 characters long.", 400);
        }
        requireOneOf(d.getTimeUnit(), ALLOWED_TIME_UNITS, "TimeUnit");
        requireOneOf(d.getFormat(), ALLOWED_FORMATS, "Format");
        requireOneOf(d.getCompression(), ALLOWED_COMPRESSIONS, "Compression");
        requireNonEmpty(d.getS3Bucket(), "S3Bucket");
        requireValidBucketName(d.getS3Bucket(), "S3Bucket");
        if (d.getS3Prefix() != null) {
            requireSafeKeySegment(d.getS3Prefix(), "S3Prefix");
        }
        if (d.getS3Prefix() == null) {
            d.setS3Prefix("");
        }
        requireNonEmpty(d.getS3Region(), "S3Region");
        if (d.getReportVersioning() != null && !ALLOWED_VERSIONING.contains(d.getReportVersioning())) {
            throw new AwsException("ValidationException",
                    "ReportVersioning must be one of " + ALLOWED_VERSIONING, 400);
        }
        if (d.getAdditionalArtifacts() != null) {
            for (String artifact : d.getAdditionalArtifacts()) {
                if (!ALLOWED_ARTIFACTS.contains(artifact)) {
                    throw new AwsException("ValidationException",
                            "AdditionalArtifacts must be one of " + ALLOWED_ARTIFACTS, 400);
                }
            }
        }
        if (d.getAdditionalSchemaElements() != null) {
            for (String element : d.getAdditionalSchemaElements()) {
                if (!ALLOWED_SCHEMA_ELEMENTS.contains(element)) {
                    throw new AwsException("ValidationException",
                            "AdditionalSchemaElements must be one of " + ALLOWED_SCHEMA_ELEMENTS, 400);
                }
            }
        }
    }

    private static String compositeKey(String region, String reportName) {
        return region + "::" + reportName;
    }

    private static void requireNonEmpty(String value, String field) {
        if (value == null || value.isEmpty()) {
            throw new AwsException("ValidationException",
                    "1 validation error detected: Value at '" + field + "' failed to satisfy constraint: Member must not be null.", 400);
        }
    }

    private static void requireOneOf(String value, Set<String> allowed, String field) {
        requireNonEmpty(value, field);
        if (!allowed.contains(value)) {
            throw new AwsException("ValidationException",
                    field + " must be one of " + allowed, 400);
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
