package io.github.hectorvent.floci.services.glue;

import com.fasterxml.jackson.core.type.TypeReference;
import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.core.common.RegionResolver;
import io.github.hectorvent.floci.core.storage.AccountAwareStorageBackend;
import io.github.hectorvent.floci.core.storage.InMemoryStorage;
import io.github.hectorvent.floci.core.storage.StorageFactory;
import io.github.hectorvent.floci.services.glue.model.Crawler;
import io.github.hectorvent.floci.services.glue.model.CrawlerTargets;
import io.github.hectorvent.floci.services.glue.model.ExecutionProperty;
import io.github.hectorvent.floci.services.glue.model.Job;
import io.github.hectorvent.floci.services.glue.model.JobCommand;
import io.github.hectorvent.floci.services.glue.model.JobRun;
import io.github.hectorvent.floci.services.glue.model.Predicate;
import io.github.hectorvent.floci.services.glue.model.S3Target;
import io.github.hectorvent.floci.services.glue.model.Trigger;
import io.github.hectorvent.floci.services.glue.model.TriggerAction;
import io.github.hectorvent.floci.services.glue.model.TriggerCondition;
import io.github.hectorvent.floci.services.glue.schemaregistry.GlueSchemaRegistryService;
import io.github.hectorvent.floci.services.kms.KmsService;
import io.github.hectorvent.floci.services.resourcegroupstagging.ResourceGroupsTaggingService;
import io.github.hectorvent.floci.testing.MutableClock;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GlueTriggerServiceTest {

    private static final String REGION = "us-east-1";
    private static final String ACCOUNT_ID = "000000000000";
    private static final String ROLE = "arn:aws:iam::000000000000:role/glue";

    private GlueService glueService;
    private GlueJobRunService jobRuns;
    private GlueCrawlerRunService crawls;
    private GlueTriggerService triggers;

    @BeforeEach
    void setUp() {
        RegionResolver regionResolver = new RegionResolver(REGION, ACCOUNT_ID);
        StorageFactory storageFactory = new InMemoryStorageFactory();
        glueService = new GlueService(storageFactory, new GlueSchemaRegistryService(storageFactory, regionResolver),
                regionResolver, new ResourceGroupsTaggingService(storageFactory),
                new KmsService(storageFactory, regionResolver));
        jobRuns = new GlueJobRunService(new InMemoryStorage<>(), new InMemoryStorage<>(), glueService, 0, Clock.systemUTC());
        crawls = new GlueCrawlerRunService(new InMemoryStorage<>(), new InMemoryStorage<>(), glueService, 0, Clock.systemUTC());
        triggers = new GlueTriggerService(new InMemoryStorage<>(), new InMemoryStorage<>(), glueService, jobRuns, crawls);
        for (String job : List.of("extract", "load", "report")) {
            createJob(job);
        }
        Crawler crawler = new Crawler();
        crawler.setName("raw");
        crawler.setRole(ROLE);
        S3Target target = new S3Target();
        target.setPath("s3://raw/data");
        CrawlerTargets targets = new CrawlerTargets();
        targets.setS3Targets(List.of(target));
        crawler.setTargets(targets);
        glueService.createCrawler(crawler, null, REGION);
    }

    private void createJob(String name) {
        Job job = new Job();
        job.setName(name);
        job.setRole(ROLE);
        job.setCommand(new JobCommand());
        ExecutionProperty property = new ExecutionProperty();
        property.setMaxConcurrentRuns(100);
        job.setExecutionProperty(property);
        glueService.createJob(job, null, REGION);
    }

    private static TriggerAction startJob(String job) {
        TriggerAction action = new TriggerAction();
        action.setJobName(job);
        return action;
    }

    private static TriggerCondition jobIs(String job, String state) {
        TriggerCondition condition = new TriggerCondition();
        condition.setLogicalOperator("EQUALS");
        condition.setJobName(job);
        condition.setState(state);
        return condition;
    }

    private static TriggerCondition crawlIs(String crawler, String state) {
        TriggerCondition condition = new TriggerCondition();
        condition.setLogicalOperator("EQUALS");
        condition.setCrawlerName(crawler);
        condition.setCrawlState(state);
        return condition;
    }

    private static Trigger trigger(String name, String type, List<TriggerAction> actions) {
        Trigger trigger = new Trigger();
        trigger.setName(name);
        trigger.setType(type);
        trigger.setActions(actions);
        return trigger;
    }

    private static Trigger conditional(String name, String logical, List<TriggerCondition> conditions,
                                       String startsJob) {
        Trigger trigger = trigger(name, "CONDITIONAL", List.of(startJob(startsJob)));
        Predicate predicate = new Predicate();
        predicate.setLogical(logical);
        predicate.setConditions(conditions);
        trigger.setPredicate(predicate);
        return trigger;
    }

    private List<JobRun> runsOf(String job) {
        return jobRuns.getJobRuns(job, 200, null).items();
    }

    /** Keeps evaluating, as later run-related requests would, until nothing more fires. */
    private void settleEverything() {
        for (int request = 0; request < 20; request++) {
            triggers.fireConditionalTriggers();
        }
    }

    private String startAndSettle(String job) {
        String runId = jobRuns.startJobRun(job, null, new JobRun()).getId();
        triggers.fireConditionalTriggers();
        return runId;
    }

    @Test
    void definitionsAreValidatedAgainstTheApiRules() {
        assertEquals("InvalidInputException", assertThrows(AwsException.class, () ->
                triggers.createTrigger(trigger("t", "ON_DEMAND", List.of()), false, null, REGION)).getErrorCode());
        assertEquals("EntityNotFoundException", assertThrows(AwsException.class, () -> triggers.createTrigger(
                trigger("t", "ON_DEMAND", List.of(startJob("missing"))), false, null, REGION)).getErrorCode());
        TriggerAction both = startJob("load");
        both.setCrawlerName("raw");
        assertEquals("InvalidInputException", assertThrows(AwsException.class, () ->
                triggers.createTrigger(trigger("t", "ON_DEMAND", List.of(both)), false, null, REGION)).getErrorCode());
        assertEquals("InvalidInputException", assertThrows(AwsException.class, () -> triggers.createTrigger(
                trigger("t", "SCHEDULED", List.of(startJob("load"))), false, null, REGION)).getErrorCode());
        assertEquals("InvalidInputException", assertThrows(AwsException.class, () -> triggers.createTrigger(
                trigger("t", "CONDITIONAL", List.of(startJob("load"))), false, null, REGION)).getErrorCode());
        assertEquals("InvalidInputException", assertThrows(AwsException.class, () -> triggers.createTrigger(
                conditional("t", null, List.of(jobIs("extract", "RUNNING")), "load"), false, null, REGION))
                .getErrorCode());
        assertEquals("InvalidInputException", assertThrows(AwsException.class, () -> triggers.createTrigger(
                trigger("t", "ON_DEMAND", List.of(startJob("load"))), true, null, REGION)).getErrorCode());
        Trigger inWorkflow = trigger("t", "ON_DEMAND", List.of(startJob("load")));
        inWorkflow.setWorkflowName("pipeline");
        assertEquals("EntityNotFoundException", assertThrows(AwsException.class, () ->
                triggers.createTrigger(inWorkflow, false, null, REGION)).getErrorCode());

        triggers.createTrigger(trigger("t", "ON_DEMAND", List.of(startJob("load"))), false, null, REGION);
        assertEquals("AlreadyExistsException", assertThrows(AwsException.class, () -> triggers.createTrigger(
                trigger("t", "ON_DEMAND", List.of(startJob("load"))), false, null, REGION)).getErrorCode());
    }

    @Test
    void stateFollowsCreationStartAndStop() {
        Trigger nightly = trigger("nightly", "SCHEDULED", List.of(startJob("load")));
        nightly.setSchedule("cron(0 2 * * ? *)");
        triggers.createTrigger(nightly, false, null, REGION);
        assertEquals("CREATED", glueService.getTrigger("nightly").getState());

        triggers.startTrigger("nightly");
        assertEquals("ACTIVATED", glueService.getTrigger("nightly").getState());
        triggers.stopTrigger("nightly");
        assertEquals("DEACTIVATED", glueService.getTrigger("nightly").getState());

        triggers.createTrigger(conditional("after", null, List.of(jobIs("extract", "SUCCEEDED")), "load"),
                true, null, REGION);
        assertEquals("ACTIVATED", glueService.getTrigger("after").getState());

        triggers.createTrigger(trigger("manual", "ON_DEMAND", List.of(startJob("load"))), false, null, REGION);
        assertEquals("InvalidInputException",
                assertThrows(AwsException.class, () -> triggers.stopTrigger("manual")).getErrorCode());
    }

    @Test
    void startingAnOnDemandTriggerRunsItsActions() {
        TriggerAction load = startJob("load");
        load.setArguments(Map.of("--mode", "full"));
        TriggerAction crawl = new TriggerAction();
        crawl.setCrawlerName("raw");
        triggers.createTrigger(trigger("manual", "ON_DEMAND", List.of(load, crawl)), false, null, REGION);

        triggers.startTrigger("manual");

        JobRun run = runsOf("load").getFirst();
        assertEquals("manual", run.getTriggerName());
        assertEquals(Map.of("--mode", "full"), run.getArguments());
        assertEquals("SUCCEEDED", crawls.completionsAfter("raw", null).getLast().state());
        assertEquals("CREATED", glueService.getTrigger("manual").getState());
    }

    @Test
    void conditionalTriggerFiresOnceWhenTheWatchedJobSucceeds() {
        triggers.createTrigger(conditional("after", null, List.of(jobIs("extract", "SUCCEEDED")), "load"),
                true, null, REGION);

        startAndSettle("extract");
        triggers.fireConditionalTriggers();

        assertEquals(1, runsOf("load").size());
        assertEquals("after", runsOf("load").getFirst().getTriggerName());
    }

    @Test
    void runsFinishedBeforeActivationDoNotFire() {
        startAndSettle("extract");
        triggers.createTrigger(conditional("after", null, List.of(jobIs("extract", "SUCCEEDED")), "load"),
                false, null, REGION);

        triggers.startTrigger("after");
        triggers.fireConditionalTriggers();

        assertTrue(runsOf("load").isEmpty());
    }

    @Test
    void conditionOnAnotherStateDoesNotFire() {
        triggers.createTrigger(conditional("onFailure", null, List.of(jobIs("extract", "FAILED")), "report"),
                true, null, REGION);

        startAndSettle("extract");

        assertTrue(runsOf("report").isEmpty());
    }

    @Test
    void andWaitsForEveryConditionWhileAnyFiresOnTheFirst() {
        List<TriggerCondition> conditions = List.of(jobIs("extract", "SUCCEEDED"), crawlIs("raw", "SUCCEEDED"));
        triggers.createTrigger(conditional("both", "AND", conditions, "load"), true, null, REGION);
        triggers.createTrigger(conditional("either", "ANY", conditions, "report"), true, null, REGION);

        startAndSettle("extract");
        assertTrue(runsOf("load").isEmpty());
        assertEquals(1, runsOf("report").size());

        crawls.startCrawler("raw");
        triggers.fireConditionalTriggers();
        assertEquals(1, runsOf("load").size());
        assertEquals(2, runsOf("report").size());
    }

    @Test
    void chainedTriggersRunTheWholePipelineInOneRequest() {
        triggers.createTrigger(conditional("step1", null, List.of(jobIs("extract", "SUCCEEDED")), "load"),
                true, null, REGION);
        triggers.createTrigger(conditional("step2", null, List.of(jobIs("load", "SUCCEEDED")), "report"),
                true, null, REGION);

        startAndSettle("extract");

        assertEquals(1, runsOf("load").size());
        assertEquals(1, runsOf("report").size());
        assertEquals("step2", runsOf("report").getFirst().getTriggerName());
    }

    @Test
    void aTriggerThatRestartsItsOwnJobStopsAtTheRunBudget() {
        triggers.createTrigger(conditional("loop", null, List.of(jobIs("extract", "SUCCEEDED")), "extract"),
                true, null, REGION);

        startAndSettle("extract");
        assertEquals(26, runsOf("extract").size());
        settleEverything();

        assertEquals(1 + GlueTriggerService.MAX_TRIGGERED_RUNS_PER_ORIGIN, runsOf("extract").size());
        assertEquals("ACTIVATED", glueService.getTrigger("loop").getState());
    }

    @Test
    void aTwoTriggerCycleStopsAtTheRunBudget() {
        triggers.createTrigger(conditional("forth", null, List.of(jobIs("extract", "SUCCEEDED")), "load"),
                true, null, REGION);
        triggers.createTrigger(conditional("back", null, List.of(jobIs("load", "SUCCEEDED")), "extract"),
                true, null, REGION);

        startAndSettle("extract");
        settleEverything();

        assertEquals(1 + GlueTriggerService.MAX_TRIGGERED_RUNS_PER_ORIGIN, runsOf("extract").size() + runsOf("load").size());
        assertEquals("ACTIVATED", glueService.getTrigger("forth").getState());
        assertEquals("ACTIVATED", glueService.getTrigger("back").getState());
    }

    /** Two actions restarting the watched job double the runs each hop; the budget bounds runs, not hops. */
    @Test
    void aBranchingLoopStopsAtTheRunBudget() {
        Trigger branching = conditional("branch", null, List.of(jobIs("extract", "SUCCEEDED")), "extract");
        branching.setActions(List.of(startJob("extract"), startJob("extract")));
        triggers.createTrigger(branching, true, null, REGION);

        startAndSettle("extract");
        settleEverything();

        assertEquals(1 + GlueTriggerService.MAX_TRIGGERED_RUNS_PER_ORIGIN, runsOf("extract").size());
    }

    /** Every crawl has its own id, so a crawler deleted and created again never inherits old bookkeeping. */
    @Test
    void aRecreatedCrawlerStartsANewChainWithItsOwnBudget() {
        triggers.createTrigger(conditional("each", "ANY", List.of(crawlIs("raw", "SUCCEEDED")), "load"),
                true, null, REGION);
        String first = crawls.startCrawler("raw");
        triggers.fireConditionalTriggers();

        triggers.deleteTrigger("each", REGION);
        crawls.deleteCrawler("raw", REGION);
        Crawler again = new Crawler();
        again.setName("raw");
        again.setRole(ROLE);
        S3Target target = new S3Target();
        target.setPath("s3://raw/data");
        CrawlerTargets targets = new CrawlerTargets();
        targets.setS3Targets(List.of(target));
        again.setTargets(targets);
        glueService.createCrawler(again, null, REGION);
        triggers.createTrigger(conditional("each", "ANY", List.of(crawlIs("raw", "SUCCEEDED")), "load"),
                true, null, REGION);
        String second = crawls.startCrawler("raw");
        triggers.fireConditionalTriggers();

        assertTrue(!first.equals(second), first + " was reused");
        assertEquals(2, runsOf("load").size());
    }

    /**
     * A ring longer than the rounds one request runs: names sort against the ring order so each round
     * advances one link, and the loop still has to stop across requests.
     */
    @Test
    void aTriggerRingLongerThanOneRequestStopsAtTheRunBudget() {
        int size = 26;
        for (int i = 0; i < size; i++) {
            createJob("ring" + i);
        }
        for (int i = 0; i < size; i++) {
            triggers.createTrigger(conditional("hop" + (100 - i), null, List.of(jobIs("ring" + i, "SUCCEEDED")),
                    "ring" + ((i + 1) % size)), true, null, REGION);
        }

        startAndSettle("ring0");
        settleEverything();

        int total = 0;
        for (int i = 0; i < size; i++) {
            total += runsOf("ring" + i).size();
        }
        assertEquals(1 + GlueTriggerService.MAX_TRIGGERED_RUNS_PER_ORIGIN, total);
    }

    /**
     * Trigger names sorting against the chain order make every round advance one link only; a chain
     * longer than the round limit must keep its triggers and finish on the next evaluation.
     */
    @Test
    void aChainLongerThanTheRoundLimitIsNotDeactivated() {
        int links = 30;
        for (int i = 1; i <= links; i++) {
            createJob("step" + i);
        }
        for (int i = 0; i < links; i++) {
            String watched = i == 0 ? "extract" : "step" + i;
            triggers.createTrigger(conditional("link" + (100 - i), null, List.of(jobIs(watched, "SUCCEEDED")),
                    "step" + (i + 1)), true, null, REGION);
        }

        startAndSettle("extract");
        assertTrue(runsOf("step" + links).isEmpty());
        triggers.fireConditionalTriggers();

        assertEquals(1, runsOf("step" + links).size());
        for (Trigger trigger : glueService.allTriggers()) {
            assertEquals("ACTIVATED", trigger.getState(), trigger.getName());
        }
    }

    /** A trigger fed by two branches fires in two rounds without being part of any cycle. */
    @Test
    void aFanInTriggerFiringInSeveralRoundsOfALongPipelineStaysActive() {
        int links = 30;
        for (int i = 1; i <= links; i++) {
            createJob("step" + i);
        }
        for (int i = 0; i < links; i++) {
            String watched = i == 0 ? "extract" : "step" + i;
            triggers.createTrigger(conditional("link" + (100 - i), null, List.of(jobIs(watched, "SUCCEEDED")),
                    "step" + (i + 1)), true, null, REGION);
        }
        triggers.createTrigger(conditional("sink", "ANY",
                List.of(jobIs("step2", "SUCCEEDED"), jobIs("step20", "SUCCEEDED")), "report"), true, null, REGION);

        startAndSettle("extract");
        triggers.fireConditionalTriggers();

        assertEquals("ACTIVATED", glueService.getTrigger("sink").getState());
        assertEquals(2, runsOf("report").size());
        assertEquals(1, runsOf("step" + links).size());
    }

    /**
     * forward watches step5 succeed and starts phase; back watches phase fail and starts step5. The
     * definitions form a loop, but phase runs succeed, so back never fires and nothing repeats: forward
     * must stay active even when a long pipeline reaches the round limit.
     */
    @Test
    void aCycleThatNeverRunsDeactivatesNothing() {
        int links = 30;
        for (int i = 1; i <= links; i++) {
            createJob("step" + i);
        }
        createJob("phase");
        for (int i = 0; i < links; i++) {
            String watched = i == 0 ? "extract" : "step" + i;
            triggers.createTrigger(conditional("link" + (100 - i), null, List.of(jobIs(watched, "SUCCEEDED")),
                    "step" + (i + 1)), true, null, REGION);
        }
        triggers.createTrigger(conditional("forward", null, List.of(jobIs("step5", "SUCCEEDED")), "phase"),
                true, null, REGION);
        triggers.createTrigger(conditional("back", null, List.of(jobIs("phase", "FAILED")), "step5"),
                true, null, REGION);

        startAndSettle("extract");
        triggers.fireConditionalTriggers();

        assertEquals(1, runsOf("step" + links).size());
        assertEquals(1, runsOf("phase").size());
        assertEquals("ACTIVATED", glueService.getTrigger("forward").getState());
        assertEquals("ACTIVATED", glueService.getTrigger("back").getState());
    }

    @Test
    void updatingOrRestartingAnActiveTriggerKeepsCompletionsItHasNotEvaluated() {
        triggers.createTrigger(conditional("after", "ANY", List.of(crawlIs("raw", "SUCCEEDED")), "load"),
                true, null, REGION);
        crawls.startCrawler("raw");
        Trigger update = new Trigger();
        update.setDescription("changed");
        triggers.updateTrigger("after", update);
        triggers.fireConditionalTriggers();
        assertEquals(1, runsOf("load").size());

        crawls.startCrawler("raw");
        triggers.startTrigger("after");
        triggers.fireConditionalTriggers();
        assertEquals(2, runsOf("load").size());
    }

    @Test
    void aRejectedUpdateLeavesTheStoredTriggerUnchanged() {
        triggers.createTrigger(trigger("manual", "ON_DEMAND", List.of(startJob("load"))), false, null, REGION);
        Trigger update = new Trigger();
        update.setDescription("changed");
        update.setActions(List.of(startJob("missing")));

        assertThrows(AwsException.class, () -> triggers.updateTrigger("manual", update));

        Trigger stored = glueService.getTrigger("manual");
        assertNull(stored.getDescription());
        assertEquals("load", stored.getActions().getFirst().getJobName());
    }

    @Test
    void conditionWithoutAStateIsAnInputError() {
        TriggerCondition noState = jobIs("extract", null);
        TriggerCondition noCrawlState = crawlIs("raw", null);

        for (TriggerCondition condition : List.of(noState, noCrawlState)) {
            assertEquals("InvalidInputException", assertThrows(AwsException.class, () -> triggers.createTrigger(
                    conditional("t", null, List.of(condition), "load"), false, null, REGION)).getErrorCode());
        }
    }

    @Test
    void aSuccessIsNotLostWhenALaterRunOfTheSameJobIsStoppedFirst() {
        MutableClock clock = new MutableClock();
        GlueJobRunService slowRuns = new GlueJobRunService(new InMemoryStorage<>(), new InMemoryStorage<>(), glueService, 60, clock);
        GlueTriggerService slowTriggers = new GlueTriggerService(new InMemoryStorage<>(), new InMemoryStorage<>(), glueService, slowRuns, crawls);
        slowTriggers.createTrigger(conditional("after", null, List.of(jobIs("extract", "SUCCEEDED")), "load"),
                true, null, REGION);
        slowRuns.startJobRun("extract", null, new JobRun());
        clock.advance(Duration.ofSeconds(10));
        String second = slowRuns.startJobRun("extract", null, new JobRun()).getId();

        clock.advance(Duration.ofSeconds(55));
        slowRuns.batchStopJobRun("extract", List.of(second));
        slowTriggers.fireConditionalTriggers();

        assertEquals(1, slowRuns.getJobRuns("load", null, null).items().size());
    }

    @Test
    void jobRunsFinishingInTheSameClockTickEachCount() {
        Clock frozen = Clock.fixed(Instant.parse("2026-09-25T12:00:00Z"), ZoneOffset.UTC);
        GlueJobRunService frozenRuns =
                new GlueJobRunService(new InMemoryStorage<>(), new InMemoryStorage<>(), glueService, 0, frozen);
        GlueTriggerService frozenTriggers = new GlueTriggerService(new InMemoryStorage<>(), new InMemoryStorage<>(), glueService, frozenRuns, crawls);
        frozenTriggers.createTrigger(conditional("each", "ANY", List.of(jobIs("extract", "SUCCEEDED")), "load"),
                true, null, REGION);

        for (int i = 0; i < 5; i++) {
            frozenRuns.startJobRun("extract", null, new JobRun());
            frozenTriggers.fireConditionalTriggers();
        }

        assertEquals(5, frozenRuns.getJobRuns("load", null, null).items().size());
    }

    @Test
    void crawlsFinishingInTheSameClockTickEachCount() {
        Clock frozen = Clock.fixed(Instant.parse("2026-09-25T12:00:00Z"), ZoneOffset.UTC);
        GlueCrawlerRunService frozenCrawls = new GlueCrawlerRunService(new InMemoryStorage<>(), new InMemoryStorage<>(), glueService, 0, frozen);
        GlueTriggerService frozenTriggers =
                new GlueTriggerService(new InMemoryStorage<>(), new InMemoryStorage<>(), glueService, jobRuns, frozenCrawls);
        frozenTriggers.createTrigger(conditional("each", "ANY", List.of(crawlIs("raw", "SUCCEEDED")), "load"),
                true, null, REGION);

        frozenCrawls.startCrawler("raw");
        frozenCrawls.startCrawler("raw");
        frozenTriggers.fireConditionalTriggers();

        assertEquals(2, runsOf("load").size());
    }

    @Test
    void deactivatedTriggersDoNotFire() {
        triggers.createTrigger(conditional("after", null, List.of(jobIs("extract", "SUCCEEDED")), "load"),
                true, null, REGION);
        triggers.stopTrigger("after");

        startAndSettle("extract");

        assertTrue(runsOf("load").isEmpty());
    }

    @Test
    void updateMergesTheGivenMembersAndDeleteIsIdempotent() {
        triggers.createTrigger(conditional("after", null, List.of(jobIs("extract", "SUCCEEDED")), "load"),
                true, null, REGION);
        Trigger update = new Trigger();
        update.setDescription("nightly load");
        update.setActions(List.of(startJob("report")));

        Trigger updated = triggers.updateTrigger("after", update);

        assertEquals("nightly load", updated.getDescription());
        assertEquals("report", updated.getActions().getFirst().getJobName());
        assertEquals("ACTIVATED", updated.getState());
        assertEquals("extract", updated.getPredicate().getConditions().getFirst().getJobName());

        triggers.deleteTrigger("after", REGION);
        triggers.deleteTrigger("after", REGION);
        assertEquals("EntityNotFoundException",
                assertThrows(AwsException.class, () -> glueService.getTrigger("after")).getErrorCode());
    }

    @Test
    void dependentJobNameReturnsTheTriggersThatStartTheJobOrElseAll() {
        triggers.createTrigger(trigger("loads", "ON_DEMAND", List.of(startJob("load"))), false, null, REGION);
        triggers.createTrigger(trigger("reports", "ON_DEMAND", List.of(startJob("report"))), false, null, REGION);

        assertEquals(List.of("loads"), glueService.getTriggers("load", null, null).items().stream()
                .map(Trigger::getName).toList());
        assertEquals(2, glueService.getTriggers("extract", null, null).items().size());
        assertEquals("EntityNotFoundException", assertThrows(AwsException.class,
                () -> glueService.getTriggers("missing", null, null)).getErrorCode());
    }

    @Test
    void concurrentEvaluationsFireATriggerOncePerCompletion() throws Exception {
        triggers.createTrigger(conditional("after", null, List.of(jobIs("extract", "SUCCEEDED")), "load"),
                true, null, REGION);
        jobRuns.startJobRun("extract", null, new JobRun());
        int threads = 8;
        CountDownLatch go = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        try {
            List<Future<?>> futures = new ArrayList<>();
            for (int i = 0; i < threads; i++) {
                futures.add(pool.submit(() -> {
                    go.await();
                    triggers.fireConditionalTriggers();
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

        assertEquals(1, runsOf("load").size());
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
