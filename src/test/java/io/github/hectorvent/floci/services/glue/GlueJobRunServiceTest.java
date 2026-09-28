package io.github.hectorvent.floci.services.glue;

import com.fasterxml.jackson.core.type.TypeReference;
import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.core.common.RegionResolver;
import io.github.hectorvent.floci.core.storage.AccountAwareStorageBackend;
import io.github.hectorvent.floci.core.storage.InMemoryStorage;
import io.github.hectorvent.floci.core.storage.StorageBackend;
import io.github.hectorvent.floci.core.storage.StorageFactory;
import io.github.hectorvent.floci.services.glue.model.ExecutionProperty;
import io.github.hectorvent.floci.services.glue.model.Job;
import io.github.hectorvent.floci.services.glue.model.JobCommand;
import io.github.hectorvent.floci.services.glue.model.JobRun;
import io.github.hectorvent.floci.services.glue.schemaregistry.GlueSchemaRegistryService;
import io.github.hectorvent.floci.services.kms.KmsService;
import io.github.hectorvent.floci.services.resourcegroupstagging.ResourceGroupsTaggingService;
import io.github.hectorvent.floci.testing.MutableClock;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Predicate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GlueJobRunServiceTest {

    private static final String REGION = "us-east-1";
    private static final String ACCOUNT_ID = "000000000000";
    private static final String ROLE = "arn:aws:iam::000000000000:role/glue";

    private GlueService glueService;
    private InMemoryStorage<String, JobRun> runStore;
    private MutableClock clock;

    @BeforeEach
    void setUp() {
        RegionResolver regionResolver = new RegionResolver(REGION, ACCOUNT_ID);
        StorageFactory storageFactory = new InMemoryStorageFactory();
        glueService = new GlueService(storageFactory, new GlueSchemaRegistryService(storageFactory, regionResolver),
                regionResolver, new ResourceGroupsTaggingService(storageFactory),
                new KmsService(storageFactory, regionResolver));
        runStore = new InMemoryStorage<>();
        clock = new MutableClock();
    }

    private GlueJobRunService service(int runDurationSeconds) {
        return new GlueJobRunService(runStore, new InMemoryStorage<>(), glueService, runDurationSeconds, clock);
    }

    private Job createJob(String name, Integer maxConcurrentRuns, Integer timeoutMinutes) {
        Job job = new Job();
        job.setName(name);
        job.setRole(ROLE);
        job.setCommand(new JobCommand());
        job.setWorkerType("G.1X");
        job.setNumberOfWorkers(2);
        job.setTimeout(timeoutMinutes);
        if (maxConcurrentRuns != null) {
            ExecutionProperty property = new ExecutionProperty();
            property.setMaxConcurrentRuns(maxConcurrentRuns);
            job.setExecutionProperty(property);
        }
        glueService.createJob(job, null, REGION);
        return job;
    }

    @Test
    void runSucceedsAsSoonAsItStartsByDefault() {
        createJob("etl", null, null);

        Instant before = clock.instant();
        JobRun run = service(0).startJobRun("etl", null, new JobRun());
        Instant after = clock.instant();

        assertTrue(run.getId().matches("jr_[0-9a-f]{64}"), run.getId());
        assertEquals("SUCCEEDED", run.getJobRunState());
        assertTrue(run.getStartedOn().isAfter(before) && run.getStartedOn().isBefore(after),
                "StartedOn " + run.getStartedOn() + " should be read from the clock during StartJobRun");
        assertEquals(run.getStartedOn(), run.getCompletedOn());
        assertEquals(0, run.getExecutionTime());
        assertEquals(0, run.getAttempt());
        assertEquals("G.1X", run.getWorkerType());
        assertEquals(2, run.getNumberOfWorkers());
        assertEquals(480, run.getTimeout());
        assertEquals("5.1", run.getGlueVersion());
        assertEquals("/aws-glue/jobs", run.getLogGroupName());
    }

    @Test
    void requestFieldsOverrideTheJobDefinitionForThatRunOnly() {
        createJob("etl", null, null);
        JobRun overrides = new JobRun();
        overrides.setArguments(Map.of("--day", "2026-09-25"));
        overrides.setWorkerType("G.2X");
        overrides.setNumberOfWorkers(10);
        overrides.setTimeout(30);

        JobRun run = service(0).startJobRun("etl", null, overrides);

        assertEquals(Map.of("--day", "2026-09-25"), run.getArguments());
        assertEquals("G.2X", run.getWorkerType());
        assertEquals(10, run.getNumberOfWorkers());
        assertEquals(30, run.getTimeout());
        assertEquals("G.1X", glueService.getJob("etl").getWorkerType());
    }

    @Test
    void runStaysRunningUntilTheConfiguredDurationHasPassed() {
        createJob("etl", null, null);
        GlueJobRunService service = service(60);
        JobRun started = service.startJobRun("etl", null, new JobRun());

        clock.advance(Duration.ofSeconds(59));
        JobRun running = service.getJobRun("etl", started.getId());
        assertEquals("RUNNING", running.getJobRunState());
        assertNull(running.getCompletedOn());

        clock.advance(Duration.ofSeconds(31));
        JobRun finished = service.getJobRun("etl", started.getId());
        assertEquals("SUCCEEDED", finished.getJobRunState());
        assertEquals(started.getStartedOn().plusSeconds(60), finished.getCompletedOn());
        assertEquals(60, finished.getExecutionTime());
    }

    @Test
    void runLongerThanTheJobTimeoutEndsInTimeout() {
        createJob("etl", null, 1);
        GlueJobRunService service = service(120);
        JobRun started = service.startJobRun("etl", null, new JobRun());

        clock.advance(Duration.ofSeconds(61));
        JobRun run = service.getJobRun("etl", started.getId());

        assertEquals("TIMEOUT", run.getJobRunState());
        assertEquals(started.getStartedOn().plusSeconds(60), run.getCompletedOn());
        assertEquals(60, run.getExecutionTime());
    }

    @Test
    void secondRunIsRejectedWhileMaxConcurrentRunsDefaultsToOne() {
        createJob("etl", null, null);
        GlueJobRunService service = service(60);
        service.startJobRun("etl", null, new JobRun());

        AwsException error = assertThrows(AwsException.class,
                () -> service.startJobRun("etl", null, new JobRun()));
        assertEquals("ConcurrentRunsExceededException", error.getErrorCode());

        clock.advance(Duration.ofSeconds(60));
        assertEquals("RUNNING", service.startJobRun("etl", null, new JobRun()).getJobRunState());
    }

    @Test
    void maxConcurrentRunsAndQueuingBothAdmitAnotherRun() {
        createJob("wide", 2, null);
        createJob("queued", null, null);
        GlueJobRunService service = service(60);

        service.startJobRun("wide", null, new JobRun());
        service.startJobRun("wide", null, new JobRun());
        assertThrows(AwsException.class, () -> service.startJobRun("wide", null, new JobRun()));

        JobRun queuing = new JobRun();
        queuing.setJobRunQueuingEnabled(true);
        service.startJobRun("queued", null, new JobRun());
        assertTrue(service.startJobRun("queued", null, queuing).getJobRunQueuingEnabled());
    }

    @Test
    void batchStopStopsARunningRunAndReportsTheRest() {
        createJob("etl", 5, null);
        GlueJobRunService service = service(60);
        String finished = service.startJobRun("etl", null, new JobRun()).getId();
        clock.advance(Duration.ofSeconds(60));
        String stillRunning = service.startJobRun("etl", null, new JobRun()).getId();
        clock.advance(Duration.ofSeconds(10));

        GlueJobRunService.StopResult result =
                service.batchStopJobRun("etl", List.of(stillRunning, finished, "jr_missing"));

        assertEquals(List.of(new GlueJobRunService.StoppedRun("etl", stillRunning)), result.stopped());
        assertEquals(2, result.errors().size());
        assertEquals("InvalidInputException", result.errors().get(0).errorCode());
        assertEquals("EntityNotFoundException", result.errors().get(1).errorCode());
        JobRun stopped = service.getJobRun("etl", stillRunning);
        assertEquals("STOPPED", stopped.getJobRunState());
        assertEquals(10, stopped.getExecutionTime());
        assertEquals("SUCCEEDED", service.getJobRun("etl", finished).getJobRunState());
    }

    @Test
    void getJobRunsListsNewestFirstAndPages() {
        createJob("etl", 10, null);
        GlueJobRunService service = service(0);
        String first = service.startJobRun("etl", null, new JobRun()).getId();
        clock.advance(Duration.ofSeconds(1));
        String second = service.startJobRun("etl", null, new JobRun()).getId();

        GlueService.Page<JobRun> page = service.getJobRuns("etl", 1, null);
        assertEquals(second, page.items().getFirst().getId());
        GlueService.Page<JobRun> next = service.getJobRuns("etl", 1, page.nextToken());
        assertEquals(first, next.items().getFirst().getId());
        assertNull(next.nextToken());

        AwsException tooMany = assertThrows(AwsException.class, () -> service.getJobRuns("etl", 201, null));
        assertEquals("InvalidInputException", tooMany.getErrorCode());
    }

    @Test
    void runsAreScopedToTheirJob() {
        createJob("a", null, null);
        createJob("b", null, null);
        GlueJobRunService service = service(0);
        String runOfA = service.startJobRun("a", null, new JobRun()).getId();

        AwsException error = assertThrows(AwsException.class, () -> service.getJobRun("b", runOfA));
        assertEquals("EntityNotFoundException", error.getErrorCode());
        assertTrue(service.getJobRuns("b", null, null).items().isEmpty());
    }

    @Test
    void unknownJobOrRetriedRunIsEntityNotFound() {
        createJob("etl", null, null);
        GlueJobRunService service = service(0);

        assertEquals("EntityNotFoundException", assertThrows(AwsException.class,
                () -> service.startJobRun("missing", null, new JobRun())).getErrorCode());
        assertEquals("EntityNotFoundException", assertThrows(AwsException.class,
                () -> service.startJobRun("etl", "jr_missing", new JobRun())).getErrorCode());

        String first = service.startJobRun("etl", null, new JobRun()).getId();
        assertEquals(first, service.startJobRun("etl", first, new JobRun()).getPreviousRunId());
    }

    @Test
    void deletingTheJobRemovesItsRuns() {
        createJob("etl", null, null);
        GlueJobRunService service = service(0);
        service.startJobRun("etl", null, new JobRun());

        glueService.deleteJob("etl", REGION);
        service.deleteRuns("etl");
        createJob("etl", null, null);

        assertTrue(service.getJobRuns("etl", null, null).items().isEmpty());
        assertTrue(runStore.scan(key -> true).isEmpty());
    }

    @Test
    void concurrentStartsAdmitNoMoreThanMaxConcurrentRuns() throws Exception {
        createJob("etl", null, null);
        GlueJobRunService service = service(60);
        int threads = 16;
        CountDownLatch go = new CountDownLatch(1);
        AtomicInteger admitted = new AtomicInteger();
        AtomicInteger rejected = new AtomicInteger();
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        try {
            List<Future<?>> futures = new ArrayList<>();
            for (int i = 0; i < threads; i++) {
                futures.add(pool.submit(() -> {
                    go.await();
                    try {
                        service.startJobRun("etl", null, new JobRun());
                        admitted.incrementAndGet();
                    } catch (AwsException expected) {
                        // ConcurrentRunsExceededException is the outcome under test for all but one start.
                        rejected.incrementAndGet();
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

        assertEquals(1, admitted.get());
        assertEquals(threads - 1, rejected.get());
        assertEquals(1, runStore.scan(key -> true).size());
    }

    /**
     * Holds the run write inside StartJobRun open while DeleteJob runs. Without the service lock,
     * deleteRuns scans before the run is stored and the run outlives its job; with it, deleteRuns
     * waits for the write and removes the run.
     */
    @Test
    void startRacingDeleteNeverLeavesARunBehind() throws Exception {
        createJob("etl", null, null);
        PausingStore store = new PausingStore(runStore);
        GlueJobRunService service = new GlueJobRunService(store, new InMemoryStorage<>(), glueService, 60, clock);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            Future<?> start = pool.submit(() -> service.startJobRun("etl", null, new JobRun()));
            assertTrue(store.putStarted.await(10, TimeUnit.SECONDS));

            Future<?> delete = pool.submit(() -> {
                glueService.deleteJob("etl", REGION);
                service.deleteRuns("etl");
                return null;
            });
            try {
                delete.get(500, TimeUnit.MILLISECONDS);
            } catch (TimeoutException expected) {
                // With the lock held by the paused start, the delete is still waiting here.
            }
            store.release.countDown();
            start.get(10, TimeUnit.SECONDS);
            delete.get(10, TimeUnit.SECONDS);
        } finally {
            pool.shutdownNow();
        }

        assertTrue(runStore.scan(key -> true).isEmpty());
    }

    @Test
    void timeoutBelowOneMinuteIsRejected() {
        createJob("etl", null, null);
        JobRun overrides = new JobRun();
        overrides.setTimeout(-5);

        AwsException error = assertThrows(AwsException.class,
                () -> service(0).startJobRun("etl", null, overrides));

        assertEquals("InvalidInputException", error.getErrorCode());
        assertTrue(runStore.scan(key -> true).isEmpty());
    }

    @Test
    void zeroTimeoutInheritedFromTheJobIsRejected() {
        createJob("etl", null, 0);

        AwsException error = assertThrows(AwsException.class,
                () -> service(0).startJobRun("etl", null, new JobRun()));

        assertEquals("InvalidInputException", error.getErrorCode());
        assertTrue(runStore.scan(key -> true).isEmpty());
    }

    @Test
    void negativeRunDurationIsRefusedAtConstruction() {
        assertThrows(IllegalArgumentException.class, () -> service(-1));
    }

    /** Delegates to a store, pausing the first put until {@code release} opens. */
    private static final class PausingStore implements StorageBackend<String, JobRun> {
        private final StorageBackend<String, JobRun> delegate;
        private final CountDownLatch putStarted = new CountDownLatch(1);
        private final CountDownLatch release = new CountDownLatch(1);

        private PausingStore(StorageBackend<String, JobRun> delegate) {
            this.delegate = delegate;
        }

        @Override
        public void put(String key, JobRun value) {
            if (putStarted.getCount() > 0) {
                putStarted.countDown();
                try {
                    release.await(10, TimeUnit.SECONDS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException("interrupted while paused", e);
                }
            }
            delegate.put(key, value);
        }

        @Override
        public Optional<JobRun> get(String key) {
            return delegate.get(key);
        }

        @Override
        public void delete(String key) {
            delegate.delete(key);
        }

        @Override
        public List<JobRun> scan(Predicate<String> keyFilter) {
            return delegate.scan(keyFilter);
        }

        @Override
        public Set<String> keys() {
            return delegate.keys();
        }

        @Override
        public void flush() {
            delegate.flush();
        }

        @Override
        public void load() {
            delegate.load();
        }

        @Override
        public void clear() {
            delegate.clear();
        }
    }

    private static final class InMemoryStorageFactory extends StorageFactory {
        private InMemoryStorageFactory() {
            super(null, null);
        }

        @Override
        public <V> AccountAwareStorageBackend<V> create(String serviceName,
                                                     String fileName,
                                                     TypeReference<Map<String, V>> typeReference) {
            return AccountAwareStorageBackend.inMemory(ACCOUNT_ID);
        }
    }
}
