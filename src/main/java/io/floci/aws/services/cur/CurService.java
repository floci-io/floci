package io.floci.aws.services.cur;

import com.fasterxml.jackson.core.type.TypeReference;
import io.floci.aws.core.common.AwsException;
import io.floci.aws.core.common.RegionResolver;
import io.floci.aws.core.common.S3DestinationValidation;
import io.floci.aws.core.common.SmithyValidationErrors;
import io.floci.aws.core.storage.AccountAwareStorageBackend;
import io.floci.aws.core.storage.StorageBackend;
import io.floci.aws.core.storage.StorageFactory;
import io.floci.aws.services.cur.model.ReportDefinition;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
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
    private static final int MAX_TAGS_PER_REPORT = 50;
    /**
     * Floci only emits Parquet at the moment. Real CUR also accepts
     * {@code textORcsv} but Floci's emission engine writes Parquet
     * unconditionally, so accepting {@code textORcsv} would let a definition
     * persist that no consumer can read. Reject it explicitly until CSV
     * support lands in a follow-up PR.
     */
    private static final Set<String> ALLOWED_FORMATS = Set.of("Parquet");
    private static final Set<String> ALLOWED_COMPRESSIONS = Set.of("Parquet");

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
    public synchronized ReportDefinition putReportDefinition(ReportDefinition definition, String region) {
        validateReportDefinition(definition);
        requireTagLimit(definition.getTags());
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

    /** {@code ModifyReportDefinition}: replaces an existing report's definition, keeping its tags. */
    public synchronized ReportDefinition modifyReportDefinition(String reportName, ReportDefinition incoming, String region) {
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
        incoming.setTags(existing.getTags());
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
        if (store instanceof AccountAwareStorageBackend<?> aware) {
            @SuppressWarnings("unchecked")
            AccountAwareStorageBackend<ReportDefinition> typed =
                    (AccountAwareStorageBackend<ReportDefinition>) aware;
            all = typed.scanAllAccounts();
        } else {
            all = store.scan(key -> true);
        }
        Map<String, List<ReportDefinition>> result = new LinkedHashMap<>();
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
    public synchronized void markReportStatus(String reportName, String region, String status) {
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
    public synchronized void markReportStatusForAccount(String accountId, String reportName, String region, String status) {
        if (accountId == null || reportName == null || region == null || status == null) {
            return;
        }
        if (!(store instanceof AccountAwareStorageBackend<?> aware)) {
            markReportStatus(reportName, region, status);
            return;
        }
        @SuppressWarnings("unchecked")
        AccountAwareStorageBackend<ReportDefinition> typed =
                (AccountAwareStorageBackend<ReportDefinition>) aware;
        String key = compositeKey(region, reportName);
        typed.getForAccount(accountId, key).ifPresent(def -> {
            def.setReportStatus(status);
            def.setLastUpdatedDate(Instant.now());
            typed.putForAccount(accountId, key, def);
        });
    }

    /** {@code ListTagsForResource}: the tags of the named report definition. */
    public Map<String, String> listTags(String reportName, String region) {
        return existingReport(reportName, region).getTags();
    }

    /** {@code TagResource}: adds the tags, replacing the value of a key the report already has. */
    public synchronized void tagResource(String reportName, Map<String, String> tags, String region) {
        ReportDefinition report = existingReport(reportName, region);
        Map<String, String> merged = new LinkedHashMap<>(report.getTags());
        merged.putAll(tags);
        requireTagLimit(merged);
        report.setTags(merged);
        store.put(compositeKey(region, reportName), report);
    }

    /** {@code UntagResource}: removes the keys; a key the report does not have is ignored. */
    public synchronized void untagResource(String reportName, List<String> tagKeys, String region) {
        ReportDefinition report = existingReport(reportName, region);
        Map<String, String> remaining = new LinkedHashMap<>(report.getTags());
        tagKeys.forEach(remaining::remove);
        report.setTags(remaining);
        store.put(compositeKey(region, reportName), report);
    }

    private static void requireTagLimit(Map<String, String> tags) {
        if (tags.size() > MAX_TAGS_PER_REPORT) {
            throw new AwsException("ValidationException",
                    "A report definition can have up to " + MAX_TAGS_PER_REPORT + " tags.", 400);
        }
    }

    private ReportDefinition existingReport(String reportName, String region) {
        requireNonEmpty(reportName, "ReportName");
        return store.get(compositeKey(region, reportName)).orElseThrow(() -> new AwsException(
                "ResourceNotFoundException",
                "The specified report (" + reportName + ") in the request doesn't exist.", 400));
    }

    /** {@code DeleteReportDefinition} — removes a report; idempotent (returns null when absent). */
    public synchronized ReportDefinition deleteReportDefinition(String reportName, String region) {
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

    /**
     * The checks the model leaves to Floci: its types, required members, patterns, lengths and enums are checked in
     * {@link CurJsonHandler#handle} from {@code cur-request-shapes.json}.
     */
    private void validateReportDefinition(ReportDefinition d) {
        requireOneOf(d.getFormat(), ALLOWED_FORMATS, "Format");
        requireOneOf(d.getCompression(), ALLOWED_COMPRESSIONS, "Compression");
        // The report name is a segment of the key ParquetEmitter writes, which takes fewer characters than the model.
        S3DestinationValidation.requireSafeKeySegment(d.getReportName(), "ReportName");
        S3DestinationValidation.requireValidBucketName(d.getS3Bucket(), "S3Bucket");
        S3DestinationValidation.requireSafeKeySegment(d.getS3Prefix(), "S3Prefix");
    }

    private static String compositeKey(String region, String reportName) {
        return region + "::" + reportName;
    }

    private static void requireNonEmpty(String value, String field) {
        if (value == null || value.isEmpty()) {
            throw SmithyValidationErrors.validationException(
                    "Value at '" + field + "' failed to satisfy constraint: Member must not be null.");
        }
    }

    private static void requireOneOf(String value, Set<String> allowed, String field) {
        if (!allowed.contains(value)) {
            throw new AwsException("ValidationException",
                    field + " must be one of " + allowed, 400);
        }
    }

}
