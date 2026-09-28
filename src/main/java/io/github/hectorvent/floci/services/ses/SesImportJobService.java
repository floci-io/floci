package io.github.hectorvent.floci.services.ses;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.core.common.RequestScopes;
import io.github.hectorvent.floci.core.common.Resettable;
import io.github.hectorvent.floci.core.storage.AccountAwareStorageBackend;
import io.github.hectorvent.floci.core.storage.StorageBackend;
import io.github.hectorvent.floci.core.storage.StorageFactory;
import io.github.hectorvent.floci.services.s3.S3Service;
import io.github.hectorvent.floci.services.ses.imports.ImportRecord;
import io.github.hectorvent.floci.services.ses.imports.RecordReader;
import io.github.hectorvent.floci.services.ses.imports.RecordReaderFactory;
import io.github.hectorvent.floci.services.ses.model.ImportJob;
import io.quarkus.runtime.StartupEvent;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * SES V2 import jobs: bulk-loads a CSV or newline-delimited JSON object from S3 into the account
 * suppression list or a contact list. A job is stored as CREATED and returned immediately; a
 * worker then flips it to PROCESSING and applies the records one by one through
 * {@link SesSuppressionService} / {@link SesContactService}, so the imported data lives in those
 * services' stores and only the job record lives here. A record that fails validation is counted
 * in {@code FailedRecordsCount} and the job still completes; the job itself fails when the source
 * cannot be read or the contact list is gone.
 */
@ApplicationScoped
public class SesImportJobService implements Resettable {

    private static final Logger LOG = Logger.getLogger(SesImportJobService.class);

    static final int MAX_CONCURRENT_JOBS = 20;
    // AWS reports an import's counters as it runs, so the worker republishes them as it goes.
    // Every record would mean serializing the job per row, so progress lands in batches.
    static final int PROGRESS_RECORD_INTERVAL = 100;
    static final String RESTART_FAILURE_MESSAGE = "The emulator restarted before the import finished";
    private static final long RESET_ADMISSION_WAIT_MILLIS = 30_000L;
    // The drain only ever waits for the record being applied right now plus the read of the next
    // one, which is a store get/put and a line off a local file, so this deadline is a safety net
    // that a healthy import never reaches. 5s is what every other worker wait in Floci uses
    // (WalStorage, HybridStorage, CloudWatchLogsMetricFilterService, CodePipelineService). Raising
    // it is the wrong direction: a reset walks every Resettable service in turn, so per-service
    // deadlines add up.
    static final long RESET_DRAIN_TIMEOUT_MILLIS = 5_000L;

    private static final Pattern S3_URL = Pattern.compile("^s3://([^/]+)/(.*?([^/]+)/?)$");
    private static final Set<String> DATA_FORMATS = Set.of(ImportJob.FORMAT_CSV, ImportJob.FORMAT_JSON);
    private static final Set<String> IMPORT_ACTIONS = Set.of(ImportJob.ACTION_PUT, ImportJob.ACTION_DELETE);
    private static final Set<String> DESTINATION_TYPES =
            Set.of(ImportJob.DESTINATION_SUPPRESSION_LIST, ImportJob.DESTINATION_CONTACT_LIST);

    private final StorageBackend<String, ImportJob> importJobStore;
    private final S3Service s3Service;
    private final SesSuppressionService suppressionService;
    private final SesContactService contactService;
    private final ObjectMapper objectMapper;
    private final RecordReaderFactory readerFactory;
    private final Clock clock;
    private final Executor workers;
    private final long resetDrainTimeoutMillis;
    // Admission lock: serializes the concurrent-job count against the store write (so two creates
    // cannot both pass the limit check) and against reset, which flips {@code resetting} under it.
    private final Object createLock = new Object();
    private boolean resetting;
    // Reset support: beforeReset bumps the generation so every running worker stops before its
    // next write, then waits on workerMonitor for the workers still inside one. That wait is
    // bounded (see RESET_DRAIN_TIMEOUT_MILLIS), so a worker wedged past the deadline can still
    // land the record it is applying right now; it is logged when that happens.
    private final AtomicInteger generation = new AtomicInteger();
    private final Object workerMonitor = new Object();
    private int activeWorkers;

    @Inject
    public SesImportJobService(StorageFactory storageFactory, S3Service s3Service,
                               SesSuppressionService suppressionService, SesContactService contactService,
                               ObjectMapper objectMapper, Clock clock) {
        this(storageFactory.create("ses", "ses-import-jobs.json",
                        new TypeReference<Map<String, ImportJob>>() {}),
                s3Service, suppressionService, contactService, objectMapper, clock,
                task -> Thread.ofVirtual().start(task));
    }

    SesImportJobService(StorageBackend<String, ImportJob> importJobStore, S3Service s3Service,
                        SesSuppressionService suppressionService, SesContactService contactService,
                        ObjectMapper objectMapper, Clock clock, Executor workers) {
        this(importJobStore, s3Service, suppressionService, contactService, objectMapper, clock, workers,
                RESET_DRAIN_TIMEOUT_MILLIS);
    }

    SesImportJobService(StorageBackend<String, ImportJob> importJobStore, S3Service s3Service,
                        SesSuppressionService suppressionService, SesContactService contactService,
                        ObjectMapper objectMapper, Clock clock, Executor workers,
                        long resetDrainTimeoutMillis) {
        this.resetDrainTimeoutMillis = resetDrainTimeoutMillis;
        this.importJobStore = importJobStore;
        this.s3Service = s3Service;
        this.suppressionService = suppressionService;
        this.contactService = contactService;
        this.objectMapper = objectMapper;
        this.readerFactory = new RecordReaderFactory(objectMapper);
        this.clock = clock;
        this.workers = workers;
        recoverInterruptedJobs();
    }

    public ImportJob createSuppressionListImportJob(String region, String accountId, String s3Url,
                                                    String dataFormat, String importAction) {
        ImportJob job = newJob(region, s3Url, dataFormat, importAction);
        job.setDestinationType(ImportJob.DESTINATION_SUPPRESSION_LIST);
        return start(job, accountId);
    }

    public ImportJob createContactListImportJob(String region, String accountId, String s3Url,
                                                String dataFormat, String contactListName,
                                                String importAction) {
        ImportJob job = newJob(region, s3Url, dataFormat, importAction);
        job.setDestinationType(ImportJob.DESTINATION_CONTACT_LIST);
        job.setContactListName(contactListName);
        requireContactList(job);
        return start(job, accountId);
    }

    /**
     * Probed against real AWS: an import job checks the contact list when it is created, answering
     * NotFoundException even though the model lists only BadRequest / LimitExceeded /
     * TooManyRequests for CreateImportJob. This path also words it differently from the contact-list
     * APIs ("ContactList <name> doesn't exist" rather than "List with name: <name> doesn't exist."),
     * so the message is rewritten here and the contact service keeps its own.
     */
    private void requireContactList(ImportJob job) {
        try {
            contactService.getContactList(job.getContactListName(), job.getRegion());
        } catch (AwsException e) {
            if (!"NotFoundException".equals(e.getErrorCode())) {
                throw e;
            }
            throw new AwsException("NotFoundException",
                    "ContactList <" + job.getContactListName() + "> doesn't exist", 404);
        }
    }

    private ImportJob newJob(String region, String s3Url, String dataFormat, String importAction) {
        if (s3Url == null || !S3_URL.matcher(s3Url).matches()) {
            throw validationError("importDataSource.s3Url",
                    "Member must satisfy regular expression pattern: " + S3_URL.pattern());
        }
        if (dataFormat == null || !DATA_FORMATS.contains(dataFormat)) {
            throw validationError("importDataSource.dataFormat",
                    "Member must satisfy enum value set: [CSV, JSON]");
        }
        if (importAction == null || !IMPORT_ACTIONS.contains(importAction)) {
            throw validationError("importDestination", "Member must satisfy enum value set: [DELETE, PUT]");
        }
        ImportJob job = new ImportJob();
        job.setJobId(UUID.randomUUID().toString());
        job.setRegion(region);
        job.setS3Url(s3Url);
        job.setDataFormat(dataFormat);
        job.setImportAction(importAction);
        job.setJobStatus(ImportJob.STATUS_CREATED);
        job.setCreatedTimestamp(Instant.now(clock));
        return job;
    }

    private ImportJob start(ImportJob job, String accountId) {
        // The worker gets its own copy so the record serialized into the Create response never
        // changes underneath the handler, and runs as the request account so its store writes
        // land in that account's partition.
        ImportJob worker = objectMapper.convertValue(job, ImportJob.class);
        synchronized (createLock) {
            awaitResetFinished();
            long active = importJobStore.scan(k -> k.startsWith(keyPrefix(job.getRegion()))).stream()
                    .filter(j -> !j.isTerminal())
                    .count();
            if (active >= MAX_CONCURRENT_JOBS) {
                throw new AwsException("LimitExceededException",
                        "The maximum number of concurrent import jobs (" + MAX_CONCURRENT_JOBS
                                + ") has been reached.", 400);
            }
            publish(key(job.getRegion(), job.getJobId()), job);
            // Persist, capture the generation and dispatch under the same lock beforeReset takes,
            // so a job is either fully admitted before a reset (and then abandoned by its stale
            // generation) or admitted only after the reset has finished.
            int startedIn = generation.get();
            workers.execute(() -> RequestScopes.runAs(accountId, () -> runImport(worker, startedIn)));
        }
        LOG.infov("Created SES import job {0} in region {1}: {2} {3} from {4}", job.getJobId(),
                job.getRegion(), job.getImportAction(), job.getDestinationType(), job.getS3Url());
        return job;
    }

    // Holds createLock. A create that arrives while a reset is in progress waits for afterReset
    // rather than persisting a job that the wipe would then race.
    private void awaitResetFinished() {
        long deadline = System.currentTimeMillis() + RESET_ADMISSION_WAIT_MILLIS;
        while (resetting) {
            long remaining = deadline - System.currentTimeMillis();
            if (remaining <= 0) {
                throw new AwsException("TooManyRequestsException",
                        "Import jobs are not accepted while the emulator is resetting.", 429);
            }
            try {
                createLock.wait(remaining);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new AwsException("TooManyRequestsException",
                        "Import jobs are not accepted while the emulator is resetting.", 429);
            }
        }
    }

    private void runImport(ImportJob job, int startedIn) {
        synchronized (workerMonitor) {
            activeWorkers++;
        }
        try {
            runImportInGeneration(job, startedIn);
        } finally {
            synchronized (workerMonitor) {
                activeWorkers--;
                workerMonitor.notifyAll();
            }
        }
    }

    private void runImportInGeneration(ImportJob job, int startedIn) {
        String storeKey = key(job.getRegion(), job.getJobId());
        if (generation.get() != startedIn) {
            return;
        }
        job.setJobStatus(ImportJob.STATUS_PROCESSING);
        publish(storeKey, job);
        try {
            Matcher url = S3_URL.matcher(job.getS3Url());
            if (!url.matches()) {
                throw new AwsException("BadRequestException", "Invalid S3 URL: " + job.getS3Url(), 400);
            }
            if (ImportJob.DESTINATION_CONTACT_LIST.equals(job.getDestinationType())) {
                requireContactList(job);
            }
            // Streamed and applied one record at a time, so an import never holds more than the
            // current record in memory whatever the object's size.
            try (InputStream raw = s3Service.openObjectStream(url.group(1), url.group(2), null);
                 BufferedReader source = new BufferedReader(new InputStreamReader(raw, StandardCharsets.UTF_8))) {
                applyRecords(job, storeKey, startedIn, readerFactory.forJob(job, source));
            }
            job.setJobStatus(ImportJob.STATUS_COMPLETED);
        } catch (AwsException e) {
            job.setJobStatus(ImportJob.STATUS_FAILED);
            job.setErrorMessage(e.getMessage());
        } catch (IOException | RuntimeException e) {
            LOG.errorv(e, "SES import job {0} failed", job.getJobId());
            job.setJobStatus(ImportJob.STATUS_FAILED);
            job.setErrorMessage(e.getMessage());
        }
        job.setCompletedTimestamp(Instant.now(clock));
        if (generation.get() != startedIn) {
            LOG.infov("SES import job {0} abandoned by an emulator reset", job.getJobId());
            return;
        }
        publish(storeKey, job);
        LOG.infov("SES import job {0} {1}: processed={2}, failed={3}", job.getJobId(), job.getJobStatus(),
                job.getProcessedRecordsCount(), job.getFailedRecordsCount());
    }

    // The worker goes on mutating its own ImportJob after each write, so every store write publishes
    // a copy: a backend that hands back the stored instance must never let a poll observe a
    // half-applied transition, such as COMPLETED before the completion timestamp is set.
    private void publish(String storeKey, ImportJob job) {
        importJobStore.put(storeKey, objectMapper.convertValue(job, ImportJob.class));
    }

    private void applyRecords(ImportJob job, String storeKey, int startedIn,
                              RecordReader reader)
            throws IOException {
        for (ImportRecord record = reader.next(); record != null; record = reader.next()) {
            if (generation.get() != startedIn) {
                LOG.infov("SES import job {0} abandoned by an emulator reset", job.getJobId());
                return;
            }
            job.setProcessedRecordsCount(job.getProcessedRecordsCount() + 1);
            applyRecord(job, record);
            if (job.getProcessedRecordsCount() % PROGRESS_RECORD_INTERVAL == 0) {
                publish(storeKey, job);
            }
        }
    }

    private void applyRecord(ImportJob job, ImportRecord record) {
        if (record.error() != null) {
            failRecord(job, record.line(), record.error());
            return;
        }
        try {
            apply(job, record);
        } catch (AwsException e) {
            // A NotFound for a contact-list job is a missing contact (a failed record) unless the
            // list itself has gone, which fails the whole job like the pre-run check.
            if (ImportJob.DESTINATION_CONTACT_LIST.equals(job.getDestinationType())
                    && "NotFoundException".equals(e.getErrorCode())) {
                requireContactList(job);
            }
            failRecord(job, record.line(), e.getMessage());
        }
    }

    private static void failRecord(ImportJob job, int line, String reason) {
        job.setFailedRecordsCount(job.getFailedRecordsCount() + 1);
        LOG.debugv("SES import job {0} skipped record at line {1}: {2}", job.getJobId(), line, reason);
    }

    private void apply(ImportJob job, ImportRecord record) {
        boolean put = ImportJob.ACTION_PUT.equals(job.getImportAction());
        if (ImportJob.DESTINATION_SUPPRESSION_LIST.equals(job.getDestinationType())) {
            if (put) {
                suppressionService.putSuppressedDestination(job.getRegion(), record.emailAddress(), record.reason());
            } else {
                suppressionService.deleteSuppressedDestination(job.getRegion(), record.emailAddress());
            }
            return;
        }
        String list = job.getContactListName();
        if (!put) {
            contactService.deleteContact(list, record.emailAddress(), job.getRegion());
            return;
        }
        // PUT over an existing contact replaces it outright rather than merging: probed against real
        // AWS, an import record that omits a topic, the attributes, or the unsubscribe flag clears
        // them. That is not UpdateContact's contract, so it has its own service path.
        try {
            contactService.createContact(list, record.emailAddress(), record.topicPreferences(),
                    record.unsubscribeAll(), record.attributesData(), job.getRegion());
        } catch (AwsException e) {
            if (!"AlreadyExistsException".equals(e.getErrorCode())) {
                throw e;
            }
            contactService.replaceContact(list, record.emailAddress(), record.topicPreferences(),
                    record.unsubscribeAll(), record.attributesData(), job.getRegion());
        }
    }

    public ImportJob getImportJob(String region, String jobId) {
        return importJobStore.get(key(region, jobId))
                .orElseThrow(() -> new AwsException("NotFoundException",
                        "Import job " + jobId + " does not exist.", 404));
    }

    public List<ImportJob> listImportJobs(String region, String destinationType, Integer pageSize,
                                          String nextToken) {
        if (destinationType != null && !DESTINATION_TYPES.contains(destinationType)) {
            throw validationError("importDestinationType",
                    "Member must satisfy enum value set: [SUPPRESSION_LIST, CONTACT_LIST]");
        }
        SesTenantService.validateListPaging(pageSize, nextToken);
        return importJobStore.scan(k -> k.startsWith(keyPrefix(region))).stream()
                .filter(j -> destinationType == null || destinationType.equals(j.getDestinationType()))
                .sorted(Comparator.comparing(ImportJob::getCreatedTimestamp)
                        .thenComparing(ImportJob::getJobId))
                .toList();
    }

    // ──────────────────────────── Lifecycle ────────────────────────────

    // Recovery happens in the constructor, and a normal-scoped bean is only built on first use, so
    // without this the jobs left non-terminal by a restart would not be failed until an import
    // endpoint was called. Observing startup makes "marked FAILED on the next start" true of the
    // boot itself, which is what the docs promise.
    void onStart(@Observes StartupEvent ignored) {
        LOG.debugv("SES import job service ready");
    }

    @Override
    public void beforeReset() {
        synchronized (createLock) {
            resetting = true;
            generation.incrementAndGet();
        }
        // Drain, but bounded: a reset has to return even when a worker is wedged in a slow read.
        // Every worker re-checks the generation before its next write, so the wait normally covers
        // only the record being applied right now. If the deadline passes anyway that one record
        // can still land after the wipe, which the warning below is there to explain.
        boolean interrupted = false;
        long deadline = System.currentTimeMillis() + resetDrainTimeoutMillis;
        synchronized (workerMonitor) {
            while (activeWorkers > 0) {
                long remaining = deadline - System.currentTimeMillis();
                if (remaining <= 0) {
                    LOG.warnv("{0} SES import worker(s) still running after {1}ms; a record in flight may "
                            + "write after the reset", activeWorkers, resetDrainTimeoutMillis);
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
        // The job store is wiped by StorageFactory; the imported rows belong to the suppression and
        // contact services, which clear their own stores.
    }

    // A job interrupted by a restart would otherwise stay CREATED/PROCESSING forever, since no
    // worker survives the process.
    private void recoverInterruptedJobs() {
        if (!(importJobStore instanceof AccountAwareStorageBackend<ImportJob> jobs)) {
            return;
        }
        Instant now = Instant.now(clock);
        for (AccountAwareStorageBackend.AccountEntry<ImportJob> entry : jobs.scanAllAccountEntries(k -> true)) {
            ImportJob job = entry.value();
            if (job.isTerminal()) {
                continue;
            }
            job.setJobStatus(ImportJob.STATUS_FAILED);
            job.setErrorMessage(RESTART_FAILURE_MESSAGE);
            job.setCompletedTimestamp(now);
            jobs.putForAccount(entry.accountId(), entry.key(), job);
            LOG.warnv("SES import job {0} was interrupted by a restart and is now FAILED", job.getJobId());
        }
    }

    private static AwsException validationError(String path, String constraint) {
        return new AwsException("BadRequestException",
                "1 validation error detected: Value at '" + path + "' failed to satisfy constraint: "
                        + constraint, 400);
    }

    static String key(String region, String jobId) {
        return keyPrefix(region) + jobId;
    }

    private static String keyPrefix(String region) {
        return "importJob::" + region + "::";
    }
}
