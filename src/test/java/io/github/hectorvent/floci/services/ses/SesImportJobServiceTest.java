package io.github.hectorvent.floci.services.ses;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.core.storage.AccountAwareStorageBackend;
import io.github.hectorvent.floci.core.storage.InMemoryStorage;
import io.github.hectorvent.floci.core.storage.StorageBackend;
import io.github.hectorvent.floci.services.s3.S3Service;
import io.github.hectorvent.floci.services.ses.model.Contact;
import io.github.hectorvent.floci.services.ses.model.ImportJob;
import io.github.hectorvent.floci.services.ses.model.Topic;
import io.github.hectorvent.floci.services.ses.model.TopicPreference;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Unit test for the import-job domain: request validation, the CSV / newline-delimited JSON record
 * formats, per-record failure counting versus whole-job failure, and the concurrency limit. The
 * worker executor runs inline so a job is terminal by the time create returns; S3 is a mock, the
 * suppression and contact services are real ones over in-memory stores so the imported rows can be
 * read back.
 */
class SesImportJobServiceTest {

    private static final String REGION = "eu-west-3";
    private static final String ACCOUNT = "000000000000";
    private static final String BUCKET = "imports";
    private static final String LIST = "newsletter";

    private S3Service s3Service;
    private SesSuppressionService suppressionService;
    private SesContactService contactService;
    private InMemoryStorage<String, ImportJob> jobStore;
    private SesImportJobService service;

    @BeforeEach
    void setUp() {
        s3Service = mock(S3Service.class);
        Clock clock = Clock.fixed(Instant.parse("2026-09-18T00:00:00Z"), ZoneOffset.UTC);
        suppressionService = new SesSuppressionService(new InMemoryStorage<>(), new InMemoryStorage<>(),
                new InMemoryStorage<>());
        contactService = new SesContactService(new InMemoryStorage<>(), new InMemoryStorage<>(), clock);
        contactService.createContactList(LIST, null,
                List.of(new Topic("Sports", "Sports", "OPT_OUT", null),
                        new Topic("Cycling", "Cycling", "OPT_IN", null)),
                null, REGION);
        jobStore = new InMemoryStorage<>();
        service = newService(Runnable::run);
    }

    private SesImportJobService newService(Executor workers) {
        return new SesImportJobService(jobStore, s3Service, suppressionService, contactService,
                new ObjectMapper().findAndRegisterModules(), Clock.systemUTC(), workers);
    }

    private void stubObject(String key, String content) {
        when(s3Service.openObjectStream(BUCKET, key, null)).thenAnswer(
                invocation -> new ByteArrayInputStream(content.getBytes(StandardCharsets.UTF_8)));
    }

    private static String url(String key) {
        return "s3://" + BUCKET + "/" + key;
    }

    // ──────────────────────────── Suppression list ────────────────────────────

    @Test
    void suppressionCsvPut_addsEveryRowAndCompletes() {
        stubObject("add.csv", "alice@example.com,BOUNCE\nbob@example.com,COMPLAINT\n");

        ImportJob job = service.createSuppressionListImportJob(REGION, ACCOUNT, url("add.csv"), "CSV", "PUT");

        ImportJob stored = service.getImportJob(REGION, job.getJobId());
        assertEquals(ImportJob.STATUS_COMPLETED, stored.getJobStatus());
        assertEquals(2, stored.getProcessedRecordsCount());
        assertEquals(0, stored.getFailedRecordsCount());
        assertNotNull(stored.getCompletedTimestamp());
        assertNull(stored.getErrorMessage());
        assertEquals("BOUNCE", suppressionService.getSuppressedDestination(REGION, "alice@example.com").getReason());
        assertEquals("COMPLAINT", suppressionService.getSuppressedDestination(REGION, "bob@example.com").getReason());
    }

    @Test
    void suppressionJsonPut_countsInvalidRowsAsFailedButCompletes() {
        stubObject("add.json", """
                {"emailAddress":"alice@example.com","reason":"BOUNCE"}

                {"emailAddress":"bob@example.com","reason":"SPAM"}
                not json at all
                {"emailAddress":"carol@example.com","reason":"COMPLAINT"}
                """);

        ImportJob job = service.createSuppressionListImportJob(REGION, ACCOUNT, url("add.json"), "JSON", "PUT");

        ImportJob stored = service.getImportJob(REGION, job.getJobId());
        assertEquals(ImportJob.STATUS_COMPLETED, stored.getJobStatus());
        assertEquals(4, stored.getProcessedRecordsCount());
        assertEquals(2, stored.getFailedRecordsCount());
        assertEquals(2, suppressionService.listSuppressedDestinations(REGION, null).size());
    }

    @Test
    void suppressionCsvDelete_removesListedAddresses_missingOnesFail() {
        suppressionService.putSuppressedDestination(REGION, "alice@example.com", "BOUNCE");
        stubObject("remove.csv", "alice@example.com\nnobody@example.com\n");

        ImportJob job = service.createSuppressionListImportJob(REGION, ACCOUNT, url("remove.csv"), "CSV", "DELETE");

        ImportJob stored = service.getImportJob(REGION, job.getJobId());
        assertEquals(ImportJob.STATUS_COMPLETED, stored.getJobStatus());
        assertEquals(2, stored.getProcessedRecordsCount());
        assertEquals(1, stored.getFailedRecordsCount());
        assertTrue(suppressionService.listSuppressedDestinations(REGION, null).isEmpty());
    }

    // ──────────────────────────── Contact list ────────────────────────────

    @Test
    void contactCsvPut_readsHeaderTopicColumnsAndQuotedAttributes() {
        stubObject("contacts.csv", """
                emailAddress,unsubscribeAll,attributesData,topicPreferences.Sports,topicPreferences.Cycling
                alice@example.com,false,"{""Name"":""Alice"",""City"":""Paris""}",OPT_IN,OPT_OUT
                bob@example.com,true,,OPT_OUT,
                """);

        ImportJob job = service.createContactListImportJob(REGION, ACCOUNT, url("contacts.csv"), "CSV", LIST, "PUT");

        ImportJob stored = service.getImportJob(REGION, job.getJobId());
        assertEquals(ImportJob.STATUS_COMPLETED, stored.getJobStatus());
        assertEquals(2, stored.getProcessedRecordsCount());
        assertEquals(0, stored.getFailedRecordsCount());
        Contact alice = contactService.getContact(LIST, "alice@example.com", REGION).contact();
        assertEquals("{\"Name\":\"Alice\",\"City\":\"Paris\"}", alice.getAttributesData());
        assertEquals(Map.of("Sports", "OPT_IN", "Cycling", "OPT_OUT"), preferences(alice));
        Contact bob = contactService.getContact(LIST, "bob@example.com", REGION).contact();
        assertTrue(bob.isUnsubscribeAll());
        assertNull(bob.getAttributesData());
        assertEquals(Map.of("Sports", "OPT_OUT"), preferences(bob));
    }

    @Test
    void contactJsonPut_overridesExistingContact() {
        contactService.createContact(LIST, "alice@example.com",
                List.of(new TopicPreference("Sports", "OPT_OUT")), false, "{\"old\":true}", REGION);
        stubObject("contacts.json", """
                {"emailAddress":"alice@example.com","unsubscribeAll":true,"attributesData":"{\\"new\\":true}",
                 "topicPreferences":[{"topicName":"Cycling","subscriptionStatus":"OPT_IN"}]}
                """.replace("\n", ""));

        ImportJob job = service.createContactListImportJob(REGION, ACCOUNT, url("contacts.json"), "JSON", LIST, "PUT");

        ImportJob stored = service.getImportJob(REGION, job.getJobId());
        assertEquals(ImportJob.STATUS_COMPLETED, stored.getJobStatus());
        assertEquals(0, stored.getFailedRecordsCount());
        Contact alice = contactService.getContact(LIST, "alice@example.com", REGION).contact();
        assertTrue(alice.isUnsubscribeAll());
        assertEquals("{\"new\":true}", alice.getAttributesData());
        // Probed against real AWS: a PUT import replaces the contact outright, so the Sports
        // preference this record omits is cleared rather than merged.
        assertEquals(Map.of("Cycling", "OPT_IN"), preferences(alice));

        // A record carrying only the address clears the rest for the same reason.
        stubObject("bare.json", "{\"emailAddress\":\"alice@example.com\"}\n");
        service.createContactListImportJob(REGION, ACCOUNT, url("bare.json"), "JSON", LIST, "PUT");

        Contact cleared = contactService.getContact(LIST, "alice@example.com", REGION).contact();
        assertEquals(Map.of(), preferences(cleared));
        assertFalse(cleared.isUnsubscribeAll());
        assertNull(cleared.getAttributesData());
    }

    @Test
    void contactCsv_unknownTopicAndBadBooleanAreFailedRecords() {
        stubObject("contacts.csv", """
                emailAddress,unsubscribeAll,topicPreferences.Chess
                alice@example.com,false,OPT_IN
                bob@example.com,maybe,
                carol@example.com,,
                """);

        ImportJob job = service.createContactListImportJob(REGION, ACCOUNT, url("contacts.csv"), "CSV", LIST, "PUT");

        ImportJob stored = service.getImportJob(REGION, job.getJobId());
        assertEquals(ImportJob.STATUS_COMPLETED, stored.getJobStatus());
        assertEquals(3, stored.getProcessedRecordsCount());
        assertEquals(2, stored.getFailedRecordsCount());
        assertEquals(1, contactService.listContacts(LIST, REGION).contacts().size());
    }

    @Test
    void contactJsonDelete_removesContacts() {
        contactService.createContact(LIST, "alice@example.com", null, false, null, REGION);
        stubObject("remove.json", "{\"emailAddress\":\"alice@example.com\"}\n{\"emailAddress\":\"ghost@example.com\"}\n");

        ImportJob job = service.createContactListImportJob(REGION, ACCOUNT, url("remove.json"), "JSON", LIST, "DELETE");

        ImportJob stored = service.getImportJob(REGION, job.getJobId());
        assertEquals(ImportJob.STATUS_COMPLETED, stored.getJobStatus());
        assertEquals(1, stored.getFailedRecordsCount());
        assertTrue(contactService.listContacts(LIST, REGION).contacts().isEmpty());
    }

    @Test
    void contactCsv_withoutEmailAddressHeader_failsTheJob() {
        stubObject("contacts.csv", "email,unsubscribeAll\nalice@example.com,false\n");

        ImportJob job = service.createContactListImportJob(REGION, ACCOUNT, url("contacts.csv"), "CSV", LIST, "PUT");

        ImportJob stored = service.getImportJob(REGION, job.getJobId());
        assertEquals(ImportJob.STATUS_FAILED, stored.getJobStatus());
        assertTrue(stored.getErrorMessage().contains("emailAddress"));
        assertNotNull(stored.getCompletedTimestamp());
    }

    // ──────────────────────────── Whole-job failure ────────────────────────────

    @Test
    void missingObject_failsTheJobWithTheS3Message() {
        when(s3Service.openObjectStream(eq(BUCKET), anyString(), eq(null))).thenThrow(
                new AwsException("NoSuchKey", "The specified key does not exist.", 404));

        ImportJob job = service.createSuppressionListImportJob(REGION, ACCOUNT, url("missing.csv"), "CSV", "PUT");

        ImportJob stored = service.getImportJob(REGION, job.getJobId());
        assertEquals(ImportJob.STATUS_FAILED, stored.getJobStatus());
        assertEquals("The specified key does not exist.", stored.getErrorMessage());
        assertEquals(0, stored.getProcessedRecordsCount());
    }

    @Test
    void contactListDeletedBeforeTheWorkerRuns_failsTheJob() {
        stubObject("contacts.csv", "emailAddress\nalice@example.com\n");
        // Hold the worker so the list can be deleted between create and run.
        List<Runnable> queued = new ArrayList<>();
        SesImportJobService deferred = newService(queued::add);

        ImportJob job = deferred.createContactListImportJob(REGION, ACCOUNT, url("contacts.csv"), "CSV", LIST, "PUT");
        assertEquals(ImportJob.STATUS_CREATED, deferred.getImportJob(REGION, job.getJobId()).getJobStatus());
        contactService.deleteContactList(LIST, REGION);
        queued.forEach(Runnable::run);

        ImportJob stored = deferred.getImportJob(REGION, job.getJobId());
        assertEquals(ImportJob.STATUS_FAILED, stored.getJobStatus());
        assertNotNull(stored.getErrorMessage());
    }

    // ──────────────────────────── Validation ────────────────────────────

    @Test
    void create_rejectsBadUrlFormatAndAction() {
        assertBadRequest(() -> service.createSuppressionListImportJob(REGION, ACCOUNT, "https://x/y", "CSV", "PUT"));
        assertBadRequest(() -> service.createSuppressionListImportJob(REGION, ACCOUNT, "s3://bucket", "CSV", "PUT"));
        assertBadRequest(() -> service.createSuppressionListImportJob(REGION, ACCOUNT, url("a.csv"), "XML", "PUT"));
        assertBadRequest(() -> service.createSuppressionListImportJob(REGION, ACCOUNT, url("a.csv"), "CSV", "UPSERT"));
        assertTrue(service.listImportJobs(REGION, null, null, null).isEmpty());
    }

    @Test
    void create_unknownContactList_isNotFound() {
        AwsException e = assertThrows(AwsException.class,
                () -> service.createContactListImportJob(REGION, ACCOUNT, url("a.csv"), "CSV", "missing", "PUT"));
        assertEquals("NotFoundException", e.getErrorCode());
        // Probed against real AWS: this path words it differently from the contact-list APIs.
        assertEquals("ContactList <missing> doesn't exist", e.getMessage());
        assertTrue(service.listImportJobs(REGION, null, null, null).isEmpty());
    }

    @Test
    void create_rejectsMoreThanTwentyInProgressJobs() {
        stubObject("a.csv", "alice@example.com,BOUNCE\n");
        SesImportJobService parked = newService(task -> { });
        for (int i = 0; i < SesImportJobService.MAX_CONCURRENT_JOBS; i++) {
            parked.createSuppressionListImportJob(REGION, ACCOUNT, url("a.csv"), "CSV", "PUT");
        }

        AwsException e = assertThrows(AwsException.class,
                () -> parked.createSuppressionListImportJob(REGION, ACCOUNT, url("a.csv"), "CSV", "PUT"));
        assertEquals("LimitExceededException", e.getErrorCode());
        // Jobs in another region do not count against this region's limit.
        assertEquals(ImportJob.STATUS_CREATED,
                parked.createSuppressionListImportJob("us-east-1", ACCOUNT, url("a.csv"), "CSV", "PUT").getJobStatus());
    }

    @Test
    void getImportJob_unknownId_isNotFound() {
        AwsException e = assertThrows(AwsException.class, () -> service.getImportJob(REGION, "nope"));
        assertEquals("NotFoundException", e.getErrorCode());
        assertEquals(404, e.getHttpStatus());
    }

    @Test
    void listImportJobs_filtersByDestinationTypeAndRejectsBadPaging() {
        stubObject("a.csv", "alice@example.com,BOUNCE\n");
        stubObject("c.csv", "emailAddress\ncarol@example.com\n");
        service.createSuppressionListImportJob(REGION, ACCOUNT, url("a.csv"), "CSV", "PUT");
        service.createContactListImportJob(REGION, ACCOUNT, url("c.csv"), "CSV", LIST, "PUT");

        assertEquals(2, service.listImportJobs(REGION, null, null, null).size());
        assertEquals(1, service.listImportJobs(REGION, "CONTACT_LIST", 10, null).size());
        assertEquals(1, service.listImportJobs(REGION, "SUPPRESSION_LIST", null, null).size());
        assertTrue(service.listImportJobs("us-east-1", null, null, null).isEmpty());
        assertBadRequest(() -> service.listImportJobs(REGION, "EXPORT", null, null));
        assertBadRequest(() -> service.listImportJobs(REGION, null, 0, null));
        assertBadRequest(() -> service.listImportJobs(REGION, null, null, "token"));
    }

    @Test
    void jsonLine_withTrailingTokensOrWrongMemberTypes_isAFailedRecord() {
        stubObject("contacts.json", """
                {"emailAddress":"alice@example.com"} garbage
                {"emailAddress":"bob@example.com","attributesData":123}
                {"emailAddress":"carol@example.com","topicPreferences":[{"topicName":"Sports","subscriptionStatus":true}]}
                {"emailAddress":"dave@example.com","topicPreferences":["Sports"]}
                {"emailAddress":"erin@example.com"}
                """);

        ImportJob job = service.createContactListImportJob(REGION, ACCOUNT, url("contacts.json"), "JSON", LIST, "PUT");

        ImportJob stored = service.getImportJob(REGION, job.getJobId());
        assertEquals(ImportJob.STATUS_COMPLETED, stored.getJobStatus());
        assertEquals(5, stored.getProcessedRecordsCount());
        assertEquals(4, stored.getFailedRecordsCount());
        assertEquals(1, contactService.listContacts(LIST, REGION).contacts().size());
    }

    @Test
    void contactCsv_quotedFieldMayContainLineBreaks() {
        stubObject("contacts.csv", "emailAddress,attributesData\r\n"
                + "alice@example.com,\"{\r\n  \"\"Name\"\": \"\"Alice\"\"\r\n}\"\r\n"
                + "bob@example.com,\r\n");

        ImportJob job = service.createContactListImportJob(REGION, ACCOUNT, url("contacts.csv"), "CSV", LIST, "PUT");

        ImportJob stored = service.getImportJob(REGION, job.getJobId());
        assertEquals(ImportJob.STATUS_COMPLETED, stored.getJobStatus());
        assertEquals(2, stored.getProcessedRecordsCount());
        assertEquals(0, stored.getFailedRecordsCount());
        assertEquals("{\r\n  \"Name\": \"Alice\"\r\n}",
                contactService.getContact(LIST, "alice@example.com", REGION).contact().getAttributesData());
    }

    @Test
    void create_isSerializedAgainstTheConcurrencyLimit() throws InterruptedException {
        stubObject("a.csv", "alice@example.com,BOUNCE\n");
        SesImportJobService parked = newService(task -> { });
        int attempts = SesImportJobService.MAX_CONCURRENT_JOBS * 2;
        CountDownLatch start = new CountDownLatch(1);
        AtomicInteger rejected = new AtomicInteger();
        List<Thread> threads = new ArrayList<>();
        for (int i = 0; i < attempts; i++) {
            Thread t = new Thread(() -> {
                try {
                    start.await();
                    parked.createSuppressionListImportJob(REGION, ACCOUNT, url("a.csv"), "CSV", "PUT");
                } catch (AwsException e) {
                    rejected.incrementAndGet();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            });
            threads.add(t);
            t.start();
        }
        start.countDown();
        for (Thread t : threads) {
            t.join();
        }

        assertEquals(SesImportJobService.MAX_CONCURRENT_JOBS, rejected.get());
        assertEquals(SesImportJobService.MAX_CONCURRENT_JOBS, parked.listImportJobs(REGION, null, null, null).size());
    }

    @Test
    void beforeReset_stopsAQueuedWorkerBeforeItWrites() {
        stubObject("a.csv", "alice@example.com,BOUNCE\n");
        List<Runnable> queued = new ArrayList<>();
        SesImportJobService deferred = newService(queued::add);
        ImportJob job = deferred.createSuppressionListImportJob(REGION, ACCOUNT, url("a.csv"), "CSV", "PUT");

        deferred.beforeReset();
        queued.forEach(Runnable::run);
        deferred.afterReset();

        assertEquals(ImportJob.STATUS_CREATED, deferred.getImportJob(REGION, job.getJobId()).getJobStatus());
        assertTrue(suppressionService.listSuppressedDestinations(REGION, null).isEmpty());
    }

    @Test
    void beforeReset_waitsForARunningWorkerAndBlocksCreatesUntilAfterReset() throws Exception {
        CountDownLatch s3Blocked = new CountDownLatch(1);
        CountDownLatch releaseS3 = new CountDownLatch(1);
        when(s3Service.openObjectStream(BUCKET, "slow.csv", null)).thenAnswer(invocation -> {
            s3Blocked.countDown();
            releaseS3.await();
            return new ByteArrayInputStream("alice@example.com,BOUNCE\n".getBytes(StandardCharsets.UTF_8));
        });
        stubObject("a.csv", "bob@example.com,BOUNCE\n");
        SesImportJobService threaded = newService(task -> new Thread(task).start());
        ImportJob slow = threaded.createSuppressionListImportJob(REGION, ACCOUNT, url("slow.csv"), "CSV", "PUT");
        assertTrue(s3Blocked.await(5, TimeUnit.SECONDS));

        Thread reset = new Thread(threaded::beforeReset);
        reset.start();
        reset.join(300);
        assertTrue(reset.isAlive(), "beforeReset must wait for the running worker");
        // The create has to race the drain, not follow it: a job admitted between beforeReset and
        // the storage wipe is exactly what the admission gate exists to stop.
        AtomicInteger created = new AtomicInteger();
        Thread create = new Thread(() -> {
            threaded.createSuppressionListImportJob(REGION, ACCOUNT, url("a.csv"), "CSV", "PUT");
            created.incrementAndGet();
        });
        create.start();
        create.join(300);
        assertEquals(0, created.get(), "a create arriving while the drain runs waits");

        releaseS3.countDown();
        reset.join(5_000);
        assertFalse(reset.isAlive(), "beforeReset returns once the worker has left");
        create.join(300);
        assertEquals(0, created.get(), "and keeps waiting until the reset finishes");

        threaded.afterReset();
        create.join(5_000);
        assertEquals(1, created.get());
        // The slow worker was abandoned: no write after the reset started.
        assertEquals(ImportJob.STATUS_PROCESSING, threaded.getImportJob(REGION, slow.getJobId()).getJobStatus());
        assertTrue(suppressionService.findSuppressedDestination(REGION, "alice@example.com").isEmpty());
    }

    @Test
    void beforeReset_givesUpOnAWedgedWorkerInsteadOfHangingTheReset() throws Exception {
        CountDownLatch blocked = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        when(s3Service.openObjectStream(BUCKET, "wedged.csv", null)).thenAnswer(invocation -> {
            blocked.countDown();
            release.await();
            return new ByteArrayInputStream("alice@example.com,BOUNCE\n".getBytes(StandardCharsets.UTF_8));
        });
        SesImportJobService threaded = new SesImportJobService(jobStore, s3Service, suppressionService,
                contactService, new ObjectMapper().findAndRegisterModules(), Clock.systemUTC(),
                task -> new Thread(task).start(), 200);
        threaded.createSuppressionListImportJob(REGION, ACCOUNT, url("wedged.csv"), "CSV", "PUT");
        assertTrue(blocked.await(5, TimeUnit.SECONDS));

        // The worker never finishes, so the drain has to time out rather than block the reset.
        long started = System.currentTimeMillis();
        threaded.beforeReset();
        long waited = System.currentTimeMillis() - started;

        assertTrue(waited >= 200, "the drain should wait for its deadline, waited " + waited + "ms");
        assertTrue(waited < 5_000, "beforeReset must return on the deadline, waited " + waited + "ms");
        release.countDown();
        threaded.afterReset();
    }

    @Test
    void storedJobIsASnapshot_soAnEarlierPollIsNeverMutatedUnderTheReader() throws Exception {
        CountDownLatch blocked = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        when(s3Service.openObjectStream(BUCKET, "slow.csv", null)).thenAnswer(invocation -> {
            blocked.countDown();
            release.await();
            return new ByteArrayInputStream("alice@example.com,BOUNCE\n".getBytes(StandardCharsets.UTF_8));
        });
        SesImportJobService threaded = newService(task -> new Thread(task).start());
        ImportJob job = threaded.createSuppressionListImportJob(REGION, ACCOUNT, url("slow.csv"), "CSV", "PUT");
        assertTrue(blocked.await(5, TimeUnit.SECONDS));

        ImportJob midRun = threaded.getImportJob(REGION, job.getJobId());
        assertEquals(ImportJob.STATUS_PROCESSING, midRun.getJobStatus());
        release.countDown();
        ImportJob finished = awaitTerminal(threaded, job.getJobId());

        assertEquals(ImportJob.STATUS_COMPLETED, finished.getJobStatus());
        assertNotNull(finished.getCompletedTimestamp());
        assertEquals(1, finished.getProcessedRecordsCount());
        // The worker keeps mutating its own copy, so the record the earlier poll returned is frozen:
        // a poll can never catch a transition half applied (COMPLETED with no CompletedTimestamp).
        assertEquals(ImportJob.STATUS_PROCESSING, midRun.getJobStatus());
        assertEquals(0, midRun.getProcessedRecordsCount());
        assertNull(midRun.getCompletedTimestamp());
    }

    private static ImportJob awaitTerminal(SesImportJobService service, String jobId) throws InterruptedException {
        for (int i = 0; i < 250; i++) {
            ImportJob job = service.getImportJob(REGION, jobId);
            if (job.isTerminal()) {
                return job;
            }
            Thread.sleep(20);
        }
        throw new AssertionError("import job " + jobId + " never reached a terminal status");
    }

    @Test
    void longImportRepublishesItsCountersAsItRuns() {
        int records = SesImportJobService.PROGRESS_RECORD_INTERVAL * 2 + 5;
        StringBuilder csv = new StringBuilder();
        for (int i = 0; i < records; i++) {
            csv.append("user").append(i).append("@example.com,BOUNCE\n");
        }
        stubObject("bulk.csv", csv.toString());
        List<Long> publishedCounts = new ArrayList<>();
        StorageBackend<String, ImportJob> recording = new InMemoryStorage<>() {
            @Override
            public void put(String key, ImportJob value) {
                publishedCounts.add(value.getProcessedRecordsCount());
                super.put(key, value);
            }
        };
        SesImportJobService service = new SesImportJobService(recording, s3Service, suppressionService,
                contactService, new ObjectMapper().findAndRegisterModules(), Clock.systemUTC(), Runnable::run);

        ImportJob job = service.createSuppressionListImportJob(REGION, ACCOUNT, url("bulk.csv"), "CSV", "PUT");

        assertEquals(records, service.getImportJob(REGION, job.getJobId()).getProcessedRecordsCount());
        // A poll partway through sees real progress rather than 0 until the job ends.
        assertEquals(List.of(0L, 0L, 100L, 200L, (long) records), publishedCounts);
    }

    @Test
    void suppressionCsv_wrongColumnCountIsAFailedRecord() {
        stubObject("mixed.csv", "alice@example.com,BOUNCE,extra\nbob@example.com\ncarol@example.com,COMPLAINT\n");

        ImportJob job = service.createSuppressionListImportJob(REGION, ACCOUNT, url("mixed.csv"), "CSV", "PUT");

        ImportJob stored = service.getImportJob(REGION, job.getJobId());
        assertEquals(3, stored.getProcessedRecordsCount());
        assertEquals(2, stored.getFailedRecordsCount());
        assertEquals(1, suppressionService.listSuppressedDestinations(REGION, null).size());
    }

    @Test
    void csv_unterminatedQuote_failsTheJob() {
        stubObject("broken.csv", "emailAddress,attributesData\nalice@example.com,\"{unterminated\n");

        ImportJob job = service.createContactListImportJob(REGION, ACCOUNT, url("broken.csv"), "CSV", LIST, "PUT");

        ImportJob stored = service.getImportJob(REGION, job.getJobId());
        assertEquals(ImportJob.STATUS_FAILED, stored.getJobStatus());
        assertTrue(stored.getErrorMessage().contains("unterminated"));
        assertTrue(contactService.listContacts(LIST, REGION).contacts().isEmpty());
    }

    @Test
    void contactListDeletedDuringTheRun_failsTheJob() {
        stubObject("contacts.csv", "emailAddress\nalice@example.com\nbob@example.com\n");
        SesContactService deletingService = mock(SesContactService.class);
        when(deletingService.getContactList(LIST, REGION)).thenReturn(contactService.getContactList(LIST, REGION));
        when(deletingService.createContact(eq(LIST), anyString(), any(),
                any(), any(), eq(REGION)))
                .thenAnswer(invocation -> {
                    when(deletingService.getContactList(LIST, REGION))
                            .thenThrow(new AwsException("NotFoundException", "List " + LIST + " not found.", 404));
                    throw new AwsException("NotFoundException", "List " + LIST + " not found.", 404);
                });
        SesImportJobService racing = new SesImportJobService(jobStore, s3Service, suppressionService,
                deletingService, new ObjectMapper().findAndRegisterModules(), Clock.systemUTC(), Runnable::run);

        ImportJob job = racing.createContactListImportJob(REGION, ACCOUNT, url("contacts.csv"), "CSV", LIST, "PUT");

        ImportJob stored = racing.getImportJob(REGION, job.getJobId());
        assertEquals(ImportJob.STATUS_FAILED, stored.getJobStatus());
        assertEquals("ContactList <" + LIST + "> doesn't exist", stored.getErrorMessage());
        assertEquals(1, stored.getProcessedRecordsCount());
    }

    @Test
    void contactCsv_wrongRowWidthOrBadQuotingIsAFailedRecord() {
        stubObject("contacts.csv", """
                emailAddress,attributesData
                alice@example.com,"{}",extra
                bob@example.com,"x"junk
                carol@example.com,ab"c
                dave@example.com,
                """);

        ImportJob job = service.createContactListImportJob(REGION, ACCOUNT, url("contacts.csv"), "CSV", LIST, "PUT");

        ImportJob stored = service.getImportJob(REGION, job.getJobId());
        assertEquals(ImportJob.STATUS_COMPLETED, stored.getJobStatus());
        assertEquals(4, stored.getProcessedRecordsCount());
        // The wrong-width row and the one with data after a closing quote fail; carol's bare quote
        // is data, as the developer guide's own unquoted attributesData shows.
        assertEquals(2, stored.getFailedRecordsCount());
        assertEquals(2, contactService.listContacts(LIST, REGION).contacts().size());
    }

    @Test
    void recoverInterruptedJobs_failsNonTerminalJobsLeftInAnAccountAwareStore() {
        AccountAwareStorageBackend<ImportJob> persisted =
                new AccountAwareStorageBackend<>(new InMemoryStorage<>(), null, ACCOUNT);
        ImportJob stuck = new ImportJob();
        stuck.setJobId("stuck");
        stuck.setRegion(REGION);
        stuck.setDestinationType(ImportJob.DESTINATION_SUPPRESSION_LIST);
        stuck.setImportAction("PUT");
        stuck.setJobStatus(ImportJob.STATUS_PROCESSING);
        stuck.setCreatedTimestamp(Instant.now());
        persisted.putForAccount(ACCOUNT, SesImportJobService.key(REGION, "stuck"), stuck);
        ImportJob done = new ImportJob();
        done.setJobId("done");
        done.setRegion(REGION);
        done.setDestinationType(ImportJob.DESTINATION_SUPPRESSION_LIST);
        done.setImportAction("PUT");
        done.setJobStatus(ImportJob.STATUS_COMPLETED);
        done.setCreatedTimestamp(Instant.now());
        persisted.putForAccount(ACCOUNT, SesImportJobService.key(REGION, "done"), done);

        SesImportJobService restarted = new SesImportJobService(persisted, s3Service, suppressionService,
                contactService, new ObjectMapper().findAndRegisterModules(), Clock.systemUTC(), Runnable::run);

        ImportJob recovered = restarted.getImportJob(REGION, "stuck");
        assertEquals(ImportJob.STATUS_FAILED, recovered.getJobStatus());
        assertEquals(SesImportJobService.RESTART_FAILURE_MESSAGE, recovered.getErrorMessage());
        assertNotNull(recovered.getCompletedTimestamp());
        assertEquals(ImportJob.STATUS_COMPLETED, restarted.getImportJob(REGION, "done").getJobStatus());
    }

    private static void assertBadRequest(Runnable call) {
        AwsException e = assertThrows(AwsException.class, call::run);
        assertEquals("BadRequestException", e.getErrorCode());
    }

    private static Map<String, String> preferences(Contact contact) {
        return contact.getTopicPreferences().stream()
                .collect(Collectors.toMap(TopicPreference::getTopicName, TopicPreference::getSubscriptionStatus));
    }
}
