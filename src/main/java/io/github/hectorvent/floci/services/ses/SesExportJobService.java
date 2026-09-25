package io.github.hectorvent.floci.services.ses;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.hectorvent.floci.config.EmulatorConfig;
import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.core.common.RequestScopes;
import io.github.hectorvent.floci.core.common.Resettable;
import io.github.hectorvent.floci.core.storage.AccountAwareStorageBackend;
import io.github.hectorvent.floci.core.storage.StorageBackend;
import io.github.hectorvent.floci.core.storage.StorageFactory;
import io.github.hectorvent.floci.services.s3.PreSignedUrlGenerator;
import io.github.hectorvent.floci.services.s3.S3Service;
import io.github.hectorvent.floci.services.ses.model.ExportJob;
import io.github.hectorvent.floci.services.ses.model.SentEmail;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicInteger;

import static io.github.hectorvent.floci.services.ses.SesV2Json.intMemberOrAbsent;
import static io.github.hectorvent.floci.services.ses.SesV2Json.stringMemberOrAbsent;

/**
 * SES V2 export jobs. A job reads the timelines already stored at send time, renders them through
 * {@link SesExportPayloads} and writes one object, which {@code GetExportJob} hands back as a
 * presigned URL.
 *
 * <p>Probe-confirmed against real SES (2026-09-23, us-east-1): the caller cannot choose the
 * destination, a request carrying {@code S3Url} is refused before anything else is checked, and
 * exactly one data source must be present. The metrics source insists on whole UTC days where the
 * message-insights source does not. A job is already {@code PROCESSING} on the first read, so
 * {@code CREATED} is never observed from outside.
 *
 * <p>Deviations, all documented in {@code docs/services/ses.md}: real SES writes into a bucket it
 * owns, while Floci writes into its own S3 emulation so the presigned URL resolves; the metrics
 * export lists only the dimension values Floci has seen rather than AWS's fixed ISP catalogue; and
 * the concurrent-job ceiling is the published quota of 20, since the probe hit AWS's own
 * limit before it could measure one.
 */
@ApplicationScoped
public class SesExportJobService implements Resettable {

    private static final Logger LOG = Logger.getLogger(SesExportJobService.class);

    // The published quota, the same figure SES gives import jobs:
    // https://docs.aws.amazon.com/ses/latest/dg/quotas.html
    static final int MAX_CONCURRENT_JOBS = 20;
    static final int MAX_RESULTS = 10_000;
    static final long RESET_DRAIN_TIMEOUT_MILLIS = 5_000L;
    private static final long RESET_ADMISSION_WAIT_MILLIS = 30_000L;
    static final String RESTART_FAILURE_MESSAGE = "The emulator restarted before the export finished";
    static final String EXPORT_BUCKET_SUFFIX = "-ses-export-files";

    private static final Set<String> DATA_FORMATS = Set.of(ExportJob.FORMAT_CSV, ExportJob.FORMAT_JSON);
    private static final Set<String> SOURCE_TYPES =
            Set.of(ExportJob.SOURCE_METRICS, ExportJob.SOURCE_MESSAGE_INSIGHTS);
    private static final Set<String> JOB_STATUSES = Set.of(ExportJob.STATUS_CREATED,
            ExportJob.STATUS_PROCESSING, ExportJob.STATUS_COMPLETED, ExportJob.STATUS_FAILED,
            ExportJob.STATUS_CANCELLED);
    private static final DateTimeFormatter OBJECT_STAMP =
            DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss", Locale.ROOT).withZone(ZoneOffset.UTC);
    private static final int PRESIGNED_URL_EXPIRY_SECONDS = 3600;

    private final StorageBackend<String, ExportJob> exportJobStore;
    private final SesSentEmailService sentEmailService;
    private final S3Service s3Service;
    private final PreSignedUrlGenerator preSignedUrlGenerator;
    private final ObjectMapper objectMapper;
    private final Clock clock;
    private final Executor workers;
    private final String baseUrl;
    private final Object createLock = new Object();
    // Reset support, following SesImportJobService: beforeReset bumps the generation so a running
    // worker stops before it writes its object, then waits for the workers already inside one. The
    // wait is bounded, so a reset still returns when a worker is wedged.
    private final AtomicInteger generation = new AtomicInteger();
    private boolean resetting;
    private final Object workerMonitor = new Object();
    private int activeWorkers;

    @Inject
    public SesExportJobService(StorageFactory storageFactory, SesSentEmailService sentEmailService,
                               S3Service s3Service, PreSignedUrlGenerator preSignedUrlGenerator,
                               ObjectMapper objectMapper, Clock clock, EmulatorConfig config) {
        this(storageFactory.create("ses", "ses-export-jobs.json",
                        new TypeReference<Map<String, ExportJob>>() {}),
                sentEmailService, s3Service, preSignedUrlGenerator, objectMapper, clock,
                task -> Thread.ofVirtual().start(task), config.effectiveBaseUrl());
    }

    SesExportJobService(StorageBackend<String, ExportJob> exportJobStore,
                        SesSentEmailService sentEmailService, S3Service s3Service,
                        PreSignedUrlGenerator preSignedUrlGenerator, ObjectMapper objectMapper,
                        Clock clock, Executor workers, String baseUrl) {
        this.exportJobStore = exportJobStore;
        this.sentEmailService = sentEmailService;
        this.s3Service = s3Service;
        this.preSignedUrlGenerator = preSignedUrlGenerator;
        this.objectMapper = objectMapper;
        this.clock = clock;
        this.workers = workers;
        this.baseUrl = baseUrl;
        recoverInterruptedJobs();
    }

    /**
     * The request's already-parsed {@code ExportDataSource} and {@code ExportDestination}. The
     * controller keeps the JSON shape checks; the service owns the order the service-level rules
     * fire in.
     */
    public ExportJob createExportJob(String region, String accountId, JsonNode exportDataSource,
                                     JsonNode exportDestination, Runnable vdmGate) {
        JsonNode metrics = objectMemberOrAbsent(exportDataSource, "MetricsDataSource");
        JsonNode insights = objectMemberOrAbsent(exportDataSource, "MessageInsightsDataSource");
        if (metrics.isObject() == insights.isObject()) {
            throw new AwsException("BadRequestException", "One data source must be provided", 400);
        }
        if (!exportDestination.isObject()) {
            throw notNull("exportDestination");
        }
        String dataFormat = stringMemberOrAbsent(exportDestination, "DataFormat");
        if (dataFormat == null) {
            throw notNull("exportDestination.dataFormat");
        }
        if (!DATA_FORMATS.contains(dataFormat)) {
            throw new AwsException("BadRequestException",
                    "1 validation error detected: Value at 'exportDestination.dataFormat' failed to "
                            + "satisfy constraint: Member must satisfy enum value set: [CSV, JSON]", 400);
        }
        // Probe-confirmed order: the data source and the destination's own constraints are checked
        // first, then the refusal of a caller-chosen destination, then the VDM gate.
        if (exportDestination.hasNonNull("S3Url")) {
            throw new AwsException("BadRequestException",
                    "Providing a custom S3 URL is not supported", 400);
        }
        vdmGate.run();
        if (metrics.isObject()) {
            SesExportMetrics.validate(metrics);
        } else {
            SesExportFilters.validate(insights);
            requireMaxResults(insights);
        }

        JsonNode source = metrics.isObject() ? metrics : insights;
        Instant start = requireDate(source, "StartDate");
        Instant end = requireDate(source, "EndDate");
        if (!start.isBefore(end)) {
            throw new AwsException("BadRequestException", "Invalid date range", 400);
        }
        if (metrics.isObject() && (!isMidnight(start) || !isMidnight(end))) {
            throw new AwsException("BadRequestException",
                    "To get daily aggregated data you must not specify partial-day timestamps. "
                            + "Please make your interval go from midnight to midnight UTC.", 400);
        }

        ExportJob job = new ExportJob();
        job.setJobId(UUID.randomUUID().toString());
        job.setRegion(region);
        job.setAccountId(accountId);
        job.setExportSourceType(metrics.isObject()
                ? ExportJob.SOURCE_METRICS : ExportJob.SOURCE_MESSAGE_INSIGHTS);
        job.setDataFormat(dataFormat);
        job.setDataSource(exportDataSource.toString());
        job.setJobStatus(ExportJob.STATUS_PROCESSING);
        job.setCreatedTimestamp(Instant.now(clock));
        return start(job, accountId);
    }

    private ExportJob start(ExportJob job, String accountId) {
        synchronized (createLock) {
            awaitResetFinished();
            long active = exportJobStore.scan(k -> k.startsWith(keyPrefix(job.getRegion()))).stream()
                    .filter(candidate -> !candidate.isTerminal())
                    .count();
            if (active >= MAX_CONCURRENT_JOBS) {
                throw new AwsException("LimitExceededException",
                        "There are too many <" + job.getExportSourceType() + "> export jobs running"
                                + " - either cancel one of the jobs or wait for it to complete"
                                + " before resubmitting.", 400);
            }
            exportJobStore.put(key(job.getRegion(), job.getJobId()), job);
            // Persist and dispatch under the lock beforeReset takes, so a job is either admitted
            // before a reset and then abandoned by its stale generation, or admitted after it.
            int startedIn = generation.get();
            // The worker runs on a fresh thread, where account-aware storage would otherwise fall
            // back to the default account and never find this job again.
            workers.execute(() -> RequestScopes.runAs(accountId,
                    () -> run(job.getRegion(), job.getJobId(), startedIn)));
        }
        return job;
    }

    // Holds createLock. A create arriving while a reset drains would capture the already-bumped
    // generation, so its worker would write an object after the wipe. It waits for afterReset.
    private void awaitResetFinished() {
        long deadline = System.currentTimeMillis() + RESET_ADMISSION_WAIT_MILLIS;
        while (resetting) {
            long remaining = deadline - System.currentTimeMillis();
            if (remaining <= 0) {
                throw resettingNow();
            }
            try {
                createLock.wait(remaining);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw resettingNow();
            }
        }
    }

    private static AwsException resettingNow() {
        return new AwsException("TooManyRequestsException",
                "Export jobs are not accepted while the emulator is resetting.", 429);
    }

    private void run(String region, String jobId, int startedIn) {
        synchronized (workerMonitor) {
            activeWorkers++;
        }
        try {
            runJob(region, jobId, startedIn);
        } finally {
            synchronized (workerMonitor) {
                activeWorkers--;
                workerMonitor.notifyAll();
            }
        }
    }

    private void runJob(String region, String jobId, int startedIn) {
        try {
            Optional<ExportJob> current = exportJobStore.get(key(region, jobId));
            if (current.isEmpty() || current.get().isTerminal()) {
                // Cancelled between the create and the worker starting.
                return;
            }
            ExportJob job = current.get();
            long processed = processedRecords(job);
            byte[] payload = render(job);
            if (generation.get() != startedIn) {
                // A reset wiped the stores while this job was rendering. Writing the object now
                // would put a bucket back that the reset removed.
                LOG.infov("SES export job {0} abandoned by an emulator reset", jobId);
                return;
            }
            String bucket = ensureExportBucket(job);
            String objectKey = objectKey(job);
            s3Service.putObject(bucket, objectKey, payload, contentType(job.getDataFormat()),
                    Map.of());
            if (!finish(region, jobId, ExportJob.STATUS_COMPLETED, objectKey, null, processed)) {
                // Cancelled while the object was being written. The job will never point at it,
                // so the object goes too rather than sitting in the bucket unreachable.
                s3Service.deleteObject(bucket, objectKey);
            }
        } catch (RuntimeException e) {
            LOG.warnv("SES export job {0} failed: {1}", jobId, e.getMessage());
            finish(region, jobId, ExportJob.STATUS_FAILED, null, e.getMessage(), null);
        }
    }

    /** False when the job already reached a terminal state, which means a cancel won the race. */
    private boolean finish(String region, String jobId, String status, String objectKey,
                           String error, Long processedRecords) {
        synchronized (createLock) {
            Optional<ExportJob> current = exportJobStore.get(key(region, jobId));
            if (current.isEmpty() || current.get().isTerminal()) {
                return false;
            }
            ExportJob job = current.get();
            job.setJobStatus(status);
            job.setObjectKey(objectKey);
            job.setErrorMessage(error);
            job.setProcessedRecordsCount(processedRecords);
            job.setCompletedTimestamp(Instant.now(clock));
            exportJobStore.put(key(region, jobId), job);
            return true;
        }
    }

    /**
     * Probe-confirmed: a metrics job and a message-insights job over one window report the same
     * ProcessedRecordsCount, so the number counts the stored rows the job examined rather than the
     * rows it wrote.
     */
    private long processedRecords(ExportJob job) {
        JsonNode source = readDataSource(job);
        boolean metrics = ExportJob.SOURCE_METRICS.equals(job.getExportSourceType());
        JsonNode window = metrics
                ? source.path("MetricsDataSource") : source.path("MessageInsightsDataSource");
        return SesExportPayloads.insightsRows(inWindow(sentEmailService.listInRegion(job.getRegion()),
                date(window, "StartDate"), date(window, "EndDate"), !metrics)).size();
    }

    private byte[] render(ExportJob job) {
        JsonNode source = readDataSource(job);
        if (ExportJob.SOURCE_MESSAGE_INSIGHTS.equals(job.getExportSourceType())) {
            JsonNode insights = source.path("MessageInsightsDataSource");
            List<SentEmail> emails = inWindow(sentEmailService.listInRegion(job.getRegion()),
                    date(insights, "StartDate"), date(insights, "EndDate"), true);
            List<SesExportPayloads.InsightsRow> rows =
                    SesExportFilters.apply(SesExportPayloads.insightsRows(emails), insights);
            return ExportJob.FORMAT_CSV.equals(job.getDataFormat())
                    ? SesExportPayloads.insightsCsv(rows)
                    : SesExportPayloads.insightsJson(rows);
        }
        JsonNode metrics = source.path("MetricsDataSource");
        // Windowed here so a dimension value seen only outside the range cannot produce a row.
        List<SentEmail> emails = inWindow(sentEmailService.listInRegion(job.getRegion()),
                date(metrics, "StartDate"), date(metrics, "EndDate"), false);
        return SesExportMetrics.render(emails, metrics, job.getDataFormat());
    }

    private JsonNode readDataSource(ExportJob job) {
        try {
            return objectMapper.readTree(job.getDataSource());
        } catch (JsonProcessingException e) {
            throw new AwsException("BadRequestException", e.getMessage(), 400);
        }
    }

    /**
     * The model documents the message-insights end date as inclusive, while the metrics source
     * aggregates whole UTC days and ends exclusively, as the {@code BatchGetMetricData} probe
     * showed. A send with no timestamp is kept, as the batch query keeps it.
     */
    private static List<SentEmail> inWindow(List<SentEmail> emails, Instant start, Instant end,
                                            boolean endInclusive) {
        List<SentEmail> kept = new ArrayList<>();
        for (SentEmail email : emails) {
            Instant sentAt = email.getSentAt();
            if (sentAt == null) {
                kept.add(email);
                continue;
            }
            boolean beforeEnd = endInclusive ? !sentAt.isAfter(end) : sentAt.isBefore(end);
            if (!sentAt.isBefore(start) && beforeEnd) {
                kept.add(email);
            }
        }
        return kept;
    }

    public ExportJob getExportJob(String region, String jobId) {
        return exportJobStore.get(key(region, jobId))
                .orElseThrow(() -> new AwsException("BadRequestException", "Invalid JobId", 400));
    }

    /**
     * The presigned URL a finished job's {@code ExportDestination} carries. Empty while the job is
     * still running or once it failed, since there is no object to point at.
     */
    public Optional<String> presignedUrl(ExportJob job) {
        if (job.getObjectKey() == null) {
            return Optional.empty();
        }
        return Optional.of(preSignedUrlGenerator.generatePresignedUrl(baseUrl,
                exportBucket(job.getAccountId(), job.getRegion()), job.getObjectKey(), "GET",
                PRESIGNED_URL_EXPIRY_SECONDS));
    }

    public List<ExportJob> listExportJobs(String region, String sourceType, String jobStatus) {
        if (sourceType != null && !SOURCE_TYPES.contains(sourceType)) {
            throw new AwsException("BadRequestException",
                    "1 validation error detected: Value at 'exportSourceType' failed to satisfy "
                            + "constraint: Member must satisfy enum value set: "
                            + "[METRICS_DATA, MESSAGE_INSIGHTS]", 400);
        }
        if (jobStatus != null && !JOB_STATUSES.contains(jobStatus)) {
            throw new AwsException("BadRequestException",
                    "1 validation error detected: Value at 'jobStatus' failed to satisfy "
                            + "constraint: Member must satisfy enum value set: "
                            + "[CREATED, PROCESSING, COMPLETED, FAILED, CANCELLED]", 400);
        }
        return exportJobStore.scan(k -> k.startsWith(keyPrefix(region))).stream()
                .filter(job -> sourceType == null || sourceType.equals(job.getExportSourceType()))
                .filter(job -> jobStatus == null || jobStatus.equals(job.getJobStatus()))
                // Newest first, as AWS returns them.
                .sorted(Comparator.comparing(ExportJob::getCreatedTimestamp).reversed()
                        .thenComparing(ExportJob::getJobId))
                .toList();
    }

    public void cancelExportJob(String region, String jobId) {
        synchronized (createLock) {
            ExportJob job = getExportJob(region, jobId);
            if (job.isTerminal()) {
                throw new AwsException("BadRequestException",
                        "Cannot cancel an export job that has a <" + job.getJobStatus()
                                + "> status.", 400);
            }
            job.setJobStatus(ExportJob.STATUS_CANCELLED);
            job.setCompletedTimestamp(Instant.now(clock));
            exportJobStore.put(key(region, jobId), job);
        }
    }

    /**
     * Runs from the constructor, where no request scope is active, so the store has to be read
     * across accounts: a plain scan would only ever sweep the default account's jobs.
     */
    private void recoverInterruptedJobs() {
        if (!(exportJobStore instanceof AccountAwareStorageBackend<ExportJob> jobs)) {
            return;
        }
        for (AccountAwareStorageBackend.AccountEntry<ExportJob> entry
                : jobs.scanAllAccountEntries(k -> k.startsWith("export-job::"))) {
            ExportJob job = entry.value();
            if (job.isTerminal()) {
                continue;
            }
            job.setJobStatus(ExportJob.STATUS_FAILED);
            job.setErrorMessage(RESTART_FAILURE_MESSAGE);
            job.setCompletedTimestamp(Instant.now(clock));
            jobs.putForAccount(entry.accountId(), entry.key(), job);
            LOG.warnv("SES export job {0} was interrupted by a restart and is now FAILED",
                    job.getJobId());
        }
    }

    /**
     * Stops the workers before the stores are wiped. Without this a worker outlived the reset and
     * recreated the export bucket and object the wipe had just removed.
     */
    @Override
    public void beforeReset() {
        synchronized (createLock) {
            resetting = true;
            generation.incrementAndGet();
        }
        boolean interrupted = false;
        long deadline = System.currentTimeMillis() + RESET_DRAIN_TIMEOUT_MILLIS;
        synchronized (workerMonitor) {
            while (activeWorkers > 0) {
                long remaining = deadline - System.currentTimeMillis();
                if (remaining <= 0) {
                    LOG.warnv("{0} SES export worker(s) still running after {1}ms; one object may "
                            + "be written after the reset", activeWorkers, RESET_DRAIN_TIMEOUT_MILLIS);
                    break;
                }
                try {
                    workerMonitor.wait(remaining);
                } catch (InterruptedException e) {
                    // Keep draining to the deadline; the interrupt is re-asserted on the way out.
                    interrupted = true;
                }
            }
        }
        if (interrupted) {
            Thread.currentThread().interrupt();
        }
    }

    @Override
    public void afterReset() {
        synchronized (createLock) {
            resetting = false;
            createLock.notifyAll();
        }
    }

    @Override
    public void clear() {
        exportJobStore.clear();
        LOG.info("Cleared all SES export jobs");
    }

    /**
     * Two workers can reach an empty region together, and outside us-east-1 the second
     * {@code createBucket} answers {@code BucketAlreadyOwnedByYou}, which would fail that export.
     */
    private String ensureExportBucket(ExportJob job) {
        String bucket = exportBucket(job.getAccountId(), job.getRegion());
        if (s3Service.bucketExists(bucket)) {
            return bucket;
        }
        try {
            s3Service.createBucket(bucket, job.getRegion());
        } catch (AwsException e) {
            if (!"BucketAlreadyOwnedByYou".equals(e.getErrorCode())) {
                throw e;
            }
            LOG.debugv("Export bucket {0} was created by a concurrent export job", bucket);
        }
        return bucket;
    }

    /**
     * The account is part of the name because an object write resolves its bucket across accounts
     * when {@code global-bucket-namespace} is on, and would then land one account's export in
     * another account's partition, where its owner could read it.
     */
    static String exportBucket(String accountId, String region) {
        return "floci-" + accountId + "-" + region + EXPORT_BUCKET_SUFFIX;
    }

    /**
     * AWS names the two payloads differently, and the shape of each name is probe-confirmed.
     */
    private String objectKey(ExportJob job) {
        String stamp = OBJECT_STAMP.format(Instant.now(clock));
        String extension = ExportJob.FORMAT_CSV.equals(job.getDataFormat()) ? "csv" : "json";
        if (ExportJob.SOURCE_METRICS.equals(job.getExportSourceType())) {
            return "ses-metrics-" + stamp + "-" + job.getJobId() + "." + extension;
        }
        return stamp + "_" + job.getJobId().replace("-", "").substring(0, 10) + "." + extension;
    }

    private static String contentType(String dataFormat) {
        return ExportJob.FORMAT_CSV.equals(dataFormat) ? "text/csv" : "application/json";
    }

    private static AwsException notNull(String member) {
        return new AwsException("BadRequestException",
                "1 validation error detected: Value at '" + member
                        + "' failed to satisfy constraint: Member must not be null", 400);
    }

    /**
     * A union member of the wrong JSON type read as absent, so a request carrying a valid source
     * plus a malformed one passed the exactly-one check and silently exported the valid half.
     */
    private static JsonNode objectMemberOrAbsent(JsonNode parent, String field) {
        JsonNode member = parent.path(field);
        if (!member.isMissingNode() && !member.isNull() && !member.isObject()) {
            throw new AwsException("SerializationException", null, 400);
        }
        return member;
    }

    /**
     * The model bounds {@code MaxResults} at 1 to 10000. An unchecked negative value reached
     * {@code subList} inside the filter and surfaced as a 500.
     */
    private static void requireMaxResults(JsonNode insights) {
        // intMemberOrAbsent answers SerializationException for a value that is not an integer, so
        // a wrong type is not reported as a value out of range.
        Integer maxResults = intMemberOrAbsent(insights, "MaxResults");
        if (maxResults == null) {
            return;
        }
        if (maxResults < 1 || maxResults > MAX_RESULTS) {
            throw new AwsException("BadRequestException",
                    "1 validation error detected: Value at "
                            + "'exportDataSource.messageInsightsDataSource.maxResults' failed to "
                            + "satisfy constraint: Member must be between 1 and " + MAX_RESULTS
                            + ", inclusive", 400);
        }
    }

    private static Instant requireDate(JsonNode source, String field) {
        Instant value = date(source, field);
        if (value == null) {
            throw new AwsException("BadRequestException",
                    "1 validation error detected: Value at '" + field.substring(0, 1).toLowerCase(Locale.ROOT)
                            + field.substring(1) + "' failed to satisfy constraint: "
                            + "Member must not be null", 400);
        }
        return value;
    }

    private static Instant date(JsonNode source, String field) {
        JsonNode node = source.path(field);
        if (node.isNumber()) {
            return Instant.ofEpochMilli(Math.round(node.doubleValue() * 1000.0));
        }
        if (node.isTextual()) {
            try {
                return Instant.parse(node.asText());
            } catch (DateTimeParseException e) {
                throw new AwsException("SerializationException", e.getMessage(), 400);
            }
        }
        return null;
    }

    private static boolean isMidnight(Instant instant) {
        return instant.equals(instant.truncatedTo(ChronoUnit.DAYS));
    }

    private static String keyPrefix(String region) {
        return "export-job::" + region + "::";
    }

    private static String key(String region, String jobId) {
        return keyPrefix(region) + jobId;
    }

}
