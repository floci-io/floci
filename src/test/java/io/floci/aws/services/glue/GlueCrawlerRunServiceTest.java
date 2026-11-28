package io.floci.aws.services.glue;

import io.floci.aws.core.common.AwsException;
import io.floci.aws.core.common.RegionResolver;
import io.floci.aws.core.storage.InMemoryStorage;
import io.floci.aws.core.storage.StorageFactory;
import io.floci.aws.services.glue.model.Crawler;
import io.floci.aws.services.glue.model.CrawlerRunRecord;
import io.floci.aws.services.glue.model.CrawlerTargets;
import io.floci.aws.services.glue.model.LastCrawlInfo;
import io.floci.aws.services.glue.model.S3Target;
import io.floci.aws.services.glue.model.Schedule;
import io.floci.aws.services.glue.schemaregistry.GlueSchemaRegistryService;
import io.floci.aws.services.kms.KmsService;
import io.floci.aws.services.resourcegroupstagging.ResourceGroupsTaggingService;
import io.floci.aws.testing.InMemoryStorageFactory;
import io.floci.aws.testing.MutableClock;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GlueCrawlerRunServiceTest {

    private static final String REGION = "us-east-1";
    private static final String ACCOUNT_ID = "000000000000";
    private static final String ROLE = "arn:aws:iam::000000000000:role/glue";
    private static final long CLOCK_TICKS_MILLIS = 100;

    private GlueService glueService;
    private InMemoryStorage<String, CrawlerRunRecord> recordStore;
    private MutableClock clock;

    @BeforeEach
    void setUp() {
        RegionResolver regionResolver = new RegionResolver(REGION, ACCOUNT_ID);
        StorageFactory storageFactory = new InMemoryStorageFactory(ACCOUNT_ID);
        glueService = new GlueService(storageFactory, new GlueSchemaRegistryService(storageFactory, regionResolver),
                regionResolver, new ResourceGroupsTaggingService(storageFactory),
                new KmsService(storageFactory, regionResolver));
        recordStore = new InMemoryStorage<>();
        clock = new MutableClock();
    }

    /**
     * The shared test clock moves 1 ms on every read, so a stop "now" lands a few milliseconds after
     * the whole seconds the test advanced.
     */
    private static void assertSeconds(double expected, Object actual) {
        assertEquals(expected, (Double) actual, CLOCK_TICKS_MILLIS / 1000.0);
    }

    private GlueCrawlerRunService service(int runDurationSeconds) {
        return new GlueCrawlerRunService(recordStore, new InMemoryStorage<>(), glueService, runDurationSeconds, clock);
    }

    private GlueCrawlerRunService service(CrawlRunner runner) {
        return new GlueCrawlerRunService(recordStore, new InMemoryStorage<>(), glueService, 0, clock, runner);
    }

    @Test
    void aCrawlThatFailsReportsFailedWithItsErrorMessageAndNoTableChanges() {
        createCrawler("raw", null);
        GlueCrawlerRunService service = service(name -> CrawlOutcome.failed("Connection missing not found."));

        service.startCrawler("raw");

        Crawler crawler = service.withRunState(glueService.getCrawler("raw"));
        assertEquals("READY", crawler.getState());
        assertEquals("FAILED", crawler.getLastCrawl().getStatus());
        assertEquals("Connection missing not found.", crawler.getLastCrawl().getErrorMessage());
        Map<String, Object> metrics = metrics(service, "raw");
        assertEquals(0, metrics.get("TablesCreated"));
        assertEquals(0, metrics.get("TablesUpdated"));
    }

    @Test
    void aRunnerThatThrowsEndsTheCrawlAsFailedInsteadOfLeavingItActive() {
        createCrawler("raw", null);
        AtomicInteger calls = new AtomicInteger();
        GlueCrawlerRunService service = service(name -> {
            calls.incrementAndGet();
            throw new IllegalStateException("backend exploded");
        });

        service.startCrawler("raw", null, "wfr_1");

        assertFalse(service.isRunning("raw"));
        assertEquals(1, calls.get());
        LastCrawlInfo lastCrawl = service.withRunState(glueService.getCrawler("raw")).getLastCrawl();
        assertEquals("FAILED", lastCrawl.getStatus());
        assertEquals("backend exploded", lastCrawl.getErrorMessage());
        assertEquals("FAILED", service.crawlsInWorkflowRun("wfr_1").get(0).state());
        assertEquals("backend exploded", service.crawlsInWorkflowRun("wfr_1").get(0).errorMessage());
        service.startCrawler("raw");
        assertEquals(2, calls.get());
    }

    @Test
    void metricsReportTheTableChangesOfTheLastCrawl() {
        createCrawler("raw", null);
        List<CrawlOutcome> outcomes = List.of(CrawlOutcome.succeeded(3, 0, 0), CrawlOutcome.succeeded(1, 2, 0));
        int[] next = {0};
        GlueCrawlerRunService service = service(name -> outcomes.get(next[0]++));

        service.startCrawler("raw");
        service.startCrawler("raw");

        Map<String, Object> metrics = metrics(service, "raw");
        assertEquals(1, metrics.get("TablesCreated"));
        assertEquals(2, metrics.get("TablesUpdated"));
        assertEquals(0, metrics.get("TablesDeleted"));
        assertEquals("SUCCEEDED", service.withRunState(glueService.getCrawler("raw")).getLastCrawl().getStatus());
    }

    @Test
    void aLaterSuccessfulCrawlClearsTheErrorMessageOfAFailedOne() {
        createCrawler("raw", null);
        List<CrawlOutcome> outcomes = List.of(CrawlOutcome.failed("boom"), CrawlOutcome.succeeded());
        int[] next = {0};
        GlueCrawlerRunService service = service(name -> outcomes.get(next[0]++));

        service.startCrawler("raw");
        service.startCrawler("raw");

        LastCrawlInfo lastCrawl = service.withRunState(glueService.getCrawler("raw")).getLastCrawl();
        assertEquals("SUCCEEDED", lastCrawl.getStatus());
        assertNull(lastCrawl.getErrorMessage());
    }

    /** A runner that reads a data store: each read and each write is counted, and the write reports {@code outcome}. */
    private static class DataStoreRunner implements CrawlRunner {
        private final AtomicInteger reads = new AtomicInteger();
        private final AtomicInteger writes = new AtomicInteger();
        private RuntimeException readFailure;
        private CrawlOutcome outcome = CrawlOutcome.succeeded(2, 0, 0);

        @Override
        public CrawlOutcome run(String crawlerName) {
            throw new AssertionError("a crawl that reads a data store must not run inline");
        }

        @Override
        public boolean readsDataStore(String crawlerName) {
            return true;
        }

        @Override
        public CatalogWrite read(String crawlerName) {
            reads.incrementAndGet();
            if (readFailure != null) {
                throw readFailure;
            }
            return () -> {
                writes.incrementAndGet();
                return outcome;
            };
        }
    }

    private final List<Runnable> backgroundTasks = new ArrayList<>();

    private GlueCrawlerRunService backgroundService(int runDurationSeconds, CrawlRunner runner) {
        return new GlueCrawlerRunService(recordStore, new InMemoryStorage<>(), glueService, runDurationSeconds, clock,
                runner, backgroundTasks::add);
    }

    private void runBackgroundTasks() {
        List<Runnable> tasks = new ArrayList<>(backgroundTasks);
        backgroundTasks.clear();
        for (Runnable task : tasks) {
            task.run();
        }
    }

    @Test
    void aCrawlThatReadsADataStoreRunsUntilTheReadFinishesThenWritesWhatItFound() {
        createCrawler("jdbc", null);
        DataStoreRunner runner = new DataStoreRunner();
        GlueCrawlerRunService service = backgroundService(0, runner);

        service.startCrawler("jdbc");

        assertTrue(service.isRunning("jdbc"));
        assertEquals(0, runner.reads.get());
        runBackgroundTasks();
        assertFalse(service.isRunning("jdbc"));
        assertEquals(1, runner.writes.get());
        assertEquals("SUCCEEDED", read(service, "jdbc").getLastCrawl().getStatus());
        assertEquals(2, metrics(service, "jdbc").get("TablesCreated"));
    }

    @Test
    void stoppingACrawlWhileItReadsDiscardsWhatTheReadFound() {
        createCrawler("jdbc", null);
        DataStoreRunner runner = new DataStoreRunner();
        GlueCrawlerRunService service = backgroundService(0, runner);
        service.startCrawler("jdbc");

        service.stopCrawler("jdbc");
        runBackgroundTasks();

        assertEquals(0, runner.writes.get());
        assertFalse(service.isRunning("jdbc"));
        assertEquals("CANCELLED", read(service, "jdbc").getLastCrawl().getStatus());
        assertEquals(0, metrics(service, "jdbc").get("TablesCreated"));
    }

    @Test
    void aReadThatThrowsEndsTheCrawlAsFailedWithoutWriting() {
        createCrawler("jdbc", null);
        DataStoreRunner runner = new DataStoreRunner();
        runner.readFailure = new IllegalStateException("password authentication failed");
        GlueCrawlerRunService service = backgroundService(0, runner);
        service.startCrawler("jdbc", null, "wfr_1");

        runBackgroundTasks();

        LastCrawlInfo lastCrawl = read(service, "jdbc").getLastCrawl();
        assertEquals("FAILED", lastCrawl.getStatus());
        assertEquals("password authentication failed", lastCrawl.getErrorMessage());
        assertEquals("password authentication failed", service.crawlsInWorkflowRun("wfr_1").get(0).errorMessage());
        assertEquals(0, runner.writes.get());
    }

    @Test
    void aWriteThatThrowsEndsTheCrawlAsFailed() {
        createCrawler("jdbc", null);
        CrawlRunner runner = new DataStoreRunner() {
            @Override
            public CatalogWrite read(String crawlerName) {
                return () -> {
                    throw new AwsException("EntityNotFoundException", "Database lake not found.", 400);
                };
            }
        };
        GlueCrawlerRunService service = backgroundService(0, runner);
        service.startCrawler("jdbc");

        runBackgroundTasks();

        LastCrawlInfo lastCrawl = read(service, "jdbc").getLastCrawl();
        assertEquals("FAILED", lastCrawl.getStatus());
        assertEquals("Database lake not found.", lastCrawl.getErrorMessage());
    }

    @Test
    void aReadThatFinishesEarlyWaitsForTheRunDurationBeforeWriting() {
        createCrawler("jdbc", null);
        DataStoreRunner runner = new DataStoreRunner();
        GlueCrawlerRunService service = backgroundService(30, runner);
        service.startCrawler("jdbc");

        runBackgroundTasks();
        assertTrue(service.isRunning("jdbc"));
        assertEquals(0, runner.writes.get());

        clock.advance(Duration.ofSeconds(30));
        assertFalse(service.isRunning("jdbc"));
        assertEquals(1, runner.writes.get());
    }

    @Test
    void aCrawlStillReadingWhenFlociStoppedReadsAgain() {
        createCrawler("jdbc", null);
        DataStoreRunner runner = new DataStoreRunner();
        backgroundService(0, runner).startCrawler("jdbc");
        backgroundTasks.clear();

        GlueCrawlerRunService restarted = backgroundService(0, runner);
        assertTrue(restarted.isRunning("jdbc"));
        runBackgroundTasks();

        assertFalse(restarted.isRunning("jdbc"));
        assertEquals(1, runner.reads.get());
        assertEquals(1, runner.writes.get());
        assertEquals("SUCCEEDED", read(restarted, "jdbc").getLastCrawl().getStatus());
    }

    private void createCrawler(String name, String scheduleExpression) {
        S3Target target = new S3Target();
        target.setPath("s3://raw/" + name);
        CrawlerTargets targets = new CrawlerTargets();
        targets.setS3Targets(List.of(target));
        Crawler crawler = new Crawler();
        crawler.setName(name);
        crawler.setRole(ROLE);
        crawler.setTargets(targets);
        if (scheduleExpression != null) {
            Schedule schedule = new Schedule();
            schedule.setScheduleExpression(scheduleExpression);
            schedule.setState("SCHEDULED");
            crawler.setSchedule(schedule);
        }
        glueService.createCrawler(crawler, null, REGION);
    }

    private Crawler read(GlueCrawlerRunService service, String name) {
        return service.withRunState(glueService.getCrawler(name));
    }

    private Map<String, Object> metrics(GlueCrawlerRunService service, String name) {
        return service.getCrawlerMetrics(List.of(name), null, null).items().getFirst();
    }

    @Test
    void neverStartedCrawlerIsReadyWithNoLastCrawl() {
        createCrawler("raw", null);

        Crawler crawler = read(service(0), "raw");

        assertEquals("READY", crawler.getState());
        assertNull(crawler.getLastCrawl());
        assertNull(crawler.getCrawlElapsedTime());
    }

    @Test
    void crawlFinishesAsSoonAsItStartsByDefault() {
        createCrawler("raw", null);
        GlueCrawlerRunService service = service(0);

        service.startCrawler("raw");
        Crawler crawler = read(service, "raw");

        assertEquals("READY", crawler.getState());
        assertEquals("SUCCEEDED", crawler.getLastCrawl().getStatus());
        assertNotNull(crawler.getLastCrawl().getStartTime());
        assertEquals("/aws-glue/crawlers", crawler.getLastCrawl().getLogGroup());
        assertEquals("raw", crawler.getLastCrawl().getLogStream());
        assertEquals(0.0, metrics(service, "raw").get("LastRuntimeSeconds"));
    }

    @Test
    void crawlerStaysRunningUntilTheConfiguredDurationHasPassed() {
        createCrawler("raw", null);
        GlueCrawlerRunService service = service(60);
        service.startCrawler("raw");

        clock.advance(Duration.ofSeconds(30));
        Crawler running = read(service, "raw");
        assertEquals("RUNNING", running.getState());
        assertEquals(30_000L, running.getCrawlElapsedTime(), CLOCK_TICKS_MILLIS);
        assertNull(running.getLastCrawl());
        assertSeconds(30.0, metrics(service, "raw").get("TimeLeftSeconds"));

        clock.advance(Duration.ofSeconds(45));
        Crawler finished = read(service, "raw");
        assertEquals("READY", finished.getState());
        assertEquals("SUCCEEDED", finished.getLastCrawl().getStatus());
        assertEquals(60.0, metrics(service, "raw").get("LastRuntimeSeconds"));
        assertEquals(0.0, metrics(service, "raw").get("TimeLeftSeconds"));
    }

    @Test
    void startWhileRunningAndStopWhileIdleAreRefused() {
        createCrawler("raw", null);
        GlueCrawlerRunService service = service(60);

        assertEquals("CrawlerNotRunningException",
                assertThrows(AwsException.class, () -> service.stopCrawler("raw")).getErrorCode());
        service.startCrawler("raw");
        assertEquals("CrawlerRunningException",
                assertThrows(AwsException.class, () -> service.startCrawler("raw")).getErrorCode());
        assertEquals("EntityNotFoundException",
                assertThrows(AwsException.class, () -> service.startCrawler("missing")).getErrorCode());
    }

    @Test
    void stoppingACrawlRecordsItAsCancelled() {
        createCrawler("raw", null);
        GlueCrawlerRunService service = service(60);
        service.startCrawler("raw");

        clock.advance(Duration.ofSeconds(10));
        service.stopCrawler("raw");
        Crawler crawler = read(service, "raw");

        assertEquals("READY", crawler.getState());
        assertEquals("CANCELLED", crawler.getLastCrawl().getStatus());
        assertSeconds(10.0, metrics(service, "raw").get("LastRuntimeSeconds"));
    }

    @Test
    void metricsReportTheLastAndMedianRuntime() {
        createCrawler("raw", null);
        GlueCrawlerRunService service = service(600);
        for (int seconds : new int[] {10, 30, 20}) {
            service.startCrawler("raw");
            clock.advance(Duration.ofSeconds(seconds));
            service.stopCrawler("raw");
            clock.advance(Duration.ofSeconds(1));
        }

        Map<String, Object> metrics = metrics(service, "raw");

        assertEquals("raw", metrics.get("CrawlerName"));
        assertSeconds(20.0, metrics.get("LastRuntimeSeconds"));
        assertSeconds(20.0, metrics.get("MedianRuntimeSeconds"));
        assertEquals(false, metrics.get("StillEstimating"));
        assertEquals(0, metrics.get("TablesCreated"));
    }

    @Test
    void metricsCoverEveryCrawlerWhenNoNamesAreGivenAndSkipUnknownNames() {
        createCrawler("b", null);
        createCrawler("a", null);
        GlueCrawlerRunService service = service(0);

        List<Map<String, Object>> all = service.getCrawlerMetrics(null, null, null).items();
        assertEquals(List.of("a", "b"), all.stream().map(m -> m.get("CrawlerName")).toList());

        List<Map<String, Object>> named = service.getCrawlerMetrics(List.of("b", "missing"), null, null).items();
        assertEquals(List.of("b"), named.stream().map(m -> m.get("CrawlerName")).toList());
    }

    @Test
    void runningCrawlerCannotBeUpdatedOrDeleted() {
        createCrawler("raw", null);
        GlueCrawlerRunService service = service(60);
        service.startCrawler("raw");
        Crawler update = new Crawler();
        update.setName("raw");
        update.setDescription("changed");

        assertEquals("CrawlerRunningException",
                assertThrows(AwsException.class, () -> service.updateCrawler(update)).getErrorCode());
        assertEquals("CrawlerRunningException",
                assertThrows(AwsException.class, () -> service.deleteCrawler("raw", REGION)).getErrorCode());

        clock.advance(Duration.ofSeconds(60));
        service.updateCrawler(update);
        assertEquals("changed", glueService.getCrawler("raw").getDescription());
    }

    @Test
    void deletingACrawlerRemovesItsCrawlHistory() {
        createCrawler("raw", null);
        GlueCrawlerRunService service = service(0);
        service.startCrawler("raw");

        service.deleteCrawler("raw", REGION);
        createCrawler("raw", null);

        assertTrue(recordStore.scan(key -> true).isEmpty());
        assertNull(read(service, "raw").getLastCrawl());
    }

    @Test
    void scheduleCanBeStoppedStartedAndReplaced() {
        createCrawler("nightly", "cron(0 2 * * ? *)");
        createCrawler("adhoc", null);
        GlueCrawlerRunService service = service(0);

        service.stopCrawlerSchedule("nightly");
        assertEquals("NOT_SCHEDULED", glueService.getCrawler("nightly").getSchedule().getState());
        assertEquals("SchedulerNotRunningException",
                assertThrows(AwsException.class, () -> service.stopCrawlerSchedule("nightly")).getErrorCode());

        service.startCrawlerSchedule("nightly");
        assertEquals("SCHEDULED", glueService.getCrawler("nightly").getSchedule().getState());
        assertEquals("SchedulerRunningException",
                assertThrows(AwsException.class, () -> service.startCrawlerSchedule("nightly")).getErrorCode());

        service.updateCrawlerSchedule("nightly", "cron(30 3 * * ? *)");
        assertEquals("cron(30 3 * * ? *)", glueService.getCrawler("nightly").getSchedule().getScheduleExpression());
        assertEquals("InvalidInputException", assertThrows(AwsException.class,
                () -> service.updateCrawlerSchedule("nightly", "every day")).getErrorCode());

        assertEquals("NoScheduleException",
                assertThrows(AwsException.class, () -> service.startCrawlerSchedule("adhoc")).getErrorCode());
    }

    @Test
    void scheduleMustBeAWellFormedAwsCronExpression() {
        createCrawler("nightly", "cron(0 2 * * ? *)");
        GlueCrawlerRunService service = service(0);

        for (String malformed : List.of("cron(foo)", "cron(0 2 * * *)", "cron(0 2 * * * *)",
                "cron(0 2 ? * ? *)", "cron(0 2 * * ? *) ", "rate(1 day)", "cron(0 2 * * ? * $)")) {
            assertEquals("InvalidInputException", assertThrows(AwsException.class,
                    () -> service.updateCrawlerSchedule("nightly", malformed)).getErrorCode(), malformed);
        }
        for (String valid : List.of("cron(15 12 * * ? *)", "cron(0/5 8-17 ? * MON-FRI *)", "cron(0 0 L * ? 2026)")) {
            service.updateCrawlerSchedule("nightly", valid);
            assertEquals(valid, glueService.getCrawler("nightly").getSchedule().getScheduleExpression());
        }
    }

    @Test
    void concurrentStartsLetExactlyOneCrawlRun() throws Exception {
        createCrawler("raw", null);
        GlueCrawlerRunService service = service(60);
        int threads = 16;
        CountDownLatch go = new CountDownLatch(1);
        AtomicInteger started = new AtomicInteger();
        AtomicInteger refused = new AtomicInteger();
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        try {
            List<Future<?>> futures = new ArrayList<>();
            for (int i = 0; i < threads; i++) {
                futures.add(pool.submit(() -> {
                    go.await();
                    try {
                        service.startCrawler("raw");
                        started.incrementAndGet();
                    } catch (AwsException expected) {
                        // CrawlerRunningException is the outcome under test for all but one start.
                        refused.incrementAndGet();
                    }
                    return null;
                }));
            }
            go.countDown();
            for (Future<?> future : futures) {
                future.get(10, TimeUnit.SECONDS);
            }
        } finally {
            pool.shutdownNow();
        }

        assertEquals(1, started.get());
        assertEquals(threads - 1, refused.get());
    }

    @Test
    void negativeRunDurationIsRefusedAtConstruction() {
        assertThrows(IllegalArgumentException.class, () -> service(-1));
    }
}
