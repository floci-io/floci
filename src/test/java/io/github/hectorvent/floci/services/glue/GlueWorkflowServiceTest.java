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
import io.github.hectorvent.floci.services.glue.model.Workflow;
import io.github.hectorvent.floci.services.glue.model.WorkflowRun;
import io.github.hectorvent.floci.services.glue.schemaregistry.GlueSchemaRegistryService;
import io.github.hectorvent.floci.services.kms.KmsService;
import io.github.hectorvent.floci.services.resourcegroupstagging.ResourceGroupsTaggingService;
import io.github.hectorvent.floci.testing.MutableClock;
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
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GlueWorkflowServiceTest {

    private static final String REGION = "us-east-1";
    private static final String ACCOUNT_ID = "000000000000";
    private static final String ROLE = "arn:aws:iam::000000000000:role/glue";

    private GlueService glueService;
    private GlueJobRunService jobRuns;
    private GlueCrawlerRunService crawls;
    private GlueTriggerService workflows;
    private MutableClock clock;

    @BeforeEach
    void setUp() {
        RegionResolver regionResolver = new RegionResolver(REGION, ACCOUNT_ID);
        StorageFactory storageFactory = new InMemoryStorageFactory();
        glueService = new GlueService(storageFactory, new GlueSchemaRegistryService(storageFactory, regionResolver),
                regionResolver, new ResourceGroupsTaggingService(storageFactory),
                new KmsService(storageFactory, regionResolver));
        clock = new MutableClock();
        useRunDuration(0);
        for (String job : List.of("extract", "load")) {
            Job definition = new Job();
            definition.setName(job);
            definition.setRole(ROLE);
            definition.setCommand(new JobCommand());
            ExecutionProperty property = new ExecutionProperty();
            property.setMaxConcurrentRuns(10);
            definition.setExecutionProperty(property);
            glueService.createJob(definition, null, REGION);
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

    private void useRunDuration(int seconds) {
        useDurations(seconds, seconds);
    }

    private void useDurations(int jobSeconds, int crawlSeconds) {
        jobRuns = new GlueJobRunService(new InMemoryStorage<>(), new InMemoryStorage<>(), glueService, jobSeconds, clock);
        crawls = new GlueCrawlerRunService(new InMemoryStorage<>(), new InMemoryStorage<>(), glueService, crawlSeconds,
                clock);
        workflows = new GlueTriggerService(new InMemoryStorage<>(), new InMemoryStorage<>(), glueService, jobRuns, crawls);
    }

    private void createWorkflow(String name, Integer maxConcurrentRuns, Map<String, String> defaults) {
        Workflow workflow = new Workflow();
        workflow.setName(name);
        workflow.setMaxConcurrentRuns(maxConcurrentRuns);
        workflow.setDefaultRunProperties(defaults);
        glueService.createWorkflow(workflow, null, REGION);
    }

    private static TriggerAction startJob(String job) {
        TriggerAction action = new TriggerAction();
        action.setJobName(job);
        return action;
    }

    private static TriggerAction startCrawler(String crawler) {
        TriggerAction action = new TriggerAction();
        action.setCrawlerName(crawler);
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

    private void onDemand(String name, String workflow, TriggerAction... actions) {
        Trigger trigger = new Trigger();
        trigger.setName(name);
        trigger.setType("ON_DEMAND");
        trigger.setWorkflowName(workflow);
        trigger.setActions(List.of(actions));
        workflows.createTrigger(trigger, false, null, REGION);
    }

    private void conditional(String name, String workflow, String logical, List<TriggerCondition> conditions,
                             String startsJob) {
        Trigger trigger = new Trigger();
        trigger.setName(name);
        trigger.setType("CONDITIONAL");
        trigger.setWorkflowName(workflow);
        trigger.setActions(List.of(startJob(startsJob)));
        Predicate predicate = new Predicate();
        predicate.setLogical(logical);
        predicate.setConditions(conditions);
        trigger.setPredicate(predicate);
        workflows.createTrigger(trigger, true, null, REGION);
    }

    /** extract, then load when extract succeeds: the smallest two-step workflow. */
    private void twoStepPipeline(String workflow) {
        onDemand("start", workflow, startJob("extract"));
        conditional("then-load", workflow, null, List.of(jobIs("extract", "SUCCEEDED")), "load");
    }

    private List<JobRun> runsOf(String job) {
        return jobRuns.getJobRuns(job, null, null).items();
    }

    @Test
    void definitionsFollowTheApiRules() {
        createWorkflow("etl", null, null);
        assertEquals("AlreadyExistsException", assertThrows(AwsException.class,
                () -> createWorkflow("etl", null, null)).getErrorCode());
        assertEquals("InvalidInputException", assertThrows(AwsException.class,
                () -> createWorkflow("zero", 0, null)).getErrorCode());

        glueService.updateWorkflow("etl", "nightly", null, 2);
        Workflow updated = glueService.getWorkflow("etl");
        assertEquals("nightly", updated.getDescription());
        assertEquals(2, updated.getMaxConcurrentRuns());

        assertEquals("EntityNotFoundException", assertThrows(AwsException.class,
                () -> onDemand("orphan", "missing", startJob("extract"))).getErrorCode());
        Trigger event = new Trigger();
        event.setName("on-event");
        event.setType("EVENT");
        event.setActions(List.of(startJob("extract")));
        assertEquals("InvalidInputException", assertThrows(AwsException.class,
                () -> workflows.createTrigger(event, false, null, REGION)).getErrorCode());
        event.setWorkflowName("etl");
        workflows.createTrigger(event, false, null, REGION);
        assertEquals("etl", glueService.getTrigger("on-event").getWorkflowName());

        workflows.deleteWorkflow("etl", REGION);
        workflows.deleteWorkflow("etl", REGION);
        assertEquals("EntityNotFoundException",
                assertThrows(AwsException.class, () -> glueService.getWorkflow("etl")).getErrorCode());
    }

    @Test
    void aWorkflowRunRunsItsPipelineAndCompletes() {
        createWorkflow("etl", null, Map.of("env", "dev", "day", "default"));
        twoStepPipeline("etl");

        String runId = workflows.startWorkflowRun("etl", Map.of("day", "2026-09-26"));

        WorkflowRun run = workflows.getWorkflowRun("etl", runId, false);
        assertTrue(runId.matches("wr_[0-9a-f]{64}"), runId);
        assertEquals("COMPLETED", run.getStatus());
        assertNotNull(run.getCompletedOn());
        assertEquals(Map.of("env", "dev", "day", "2026-09-26"), run.getWorkflowRunProperties());
        assertEquals(2, run.getStatistics().get("TotalActions"));
        assertEquals(2, run.getStatistics().get("SucceededActions"));
        assertEquals(0, run.getStatistics().get("RunningActions"));
        JobRun load = runsOf("load").getFirst();
        assertEquals("then-load", load.getTriggerName());
        assertEquals("etl", load.getArguments().get("--WORKFLOW_NAME"));
        assertEquals(runId, load.getArguments().get("--WORKFLOW_RUN_ID"));
    }

    @Test
    void workflowTriggersIgnoreRunsOutsideTheirWorkflow() {
        createWorkflow("etl", null, null);
        twoStepPipeline("etl");

        jobRuns.startJobRun("extract", null, new JobRun());
        workflows.fireConditionalTriggers();

        assertTrue(runsOf("load").isEmpty());
    }

    @Test
    void andIsMetWithinOneWorkflowRunOnly() {
        createWorkflow("etl", null, null);
        onDemand("start", "etl", startJob("extract"));
        conditional("both", "etl", "AND", List.of(jobIs("extract", "SUCCEEDED"), crawlIs("raw", "SUCCEEDED")), "load");
        crawls.startCrawler("raw");

        String runId = workflows.startWorkflowRun("etl", null);

        assertTrue(runsOf("load").isEmpty());
        assertEquals("COMPLETED", workflows.getWorkflowRun("etl", runId, false).getStatus());

        onDemand("start-both", "etl", startCrawler("raw"));
        workflows.startWorkflowRun("etl", null);
        assertEquals(1, runsOf("load").size());
    }

    @Test
    void maxConcurrentRunsRefusesAnotherRunWhileOneIsRunning() {
        useRunDuration(60);
        createWorkflow("etl", 1, null);
        twoStepPipeline("etl");

        String first = workflows.startWorkflowRun("etl", null);
        assertEquals("ConcurrentRunsExceededException", assertThrows(AwsException.class,
                () -> workflows.startWorkflowRun("etl", null)).getErrorCode());
        assertEquals("RUNNING", workflows.getWorkflowRun("etl", first, false).getStatus());

        clock.advance(Duration.ofSeconds(61));
        assertEquals("RUNNING", workflows.getWorkflowRun("etl", first, false).getStatus());
        clock.advance(Duration.ofSeconds(61));
        assertEquals("COMPLETED", workflows.getWorkflowRun("etl", first, false).getStatus());
        assertNotNull(workflows.startWorkflowRun("etl", null));
    }

    @Test
    void stoppingARunStopsWhatIsRunningInIt() {
        useRunDuration(60);
        createWorkflow("etl", null, null);
        twoStepPipeline("etl");
        String runId = workflows.startWorkflowRun("etl", null);

        workflows.stopWorkflowRun("etl", runId);

        WorkflowRun run = workflows.getWorkflowRun("etl", runId, false);
        assertEquals("STOPPED", run.getStatus());
        assertEquals(1, run.getStatistics().get("StoppedActions"));
        assertEquals("STOPPED", runsOf("extract").getFirst().getJobRunState());
        assertEquals("IllegalWorkflowStateException", assertThrows(AwsException.class,
                () -> workflows.stopWorkflowRun("etl", runId)).getErrorCode());
        clock.advance(Duration.ofSeconds(120));
        workflows.fireConditionalTriggers();
        assertTrue(runsOf("load").isEmpty());
    }

    @Test
    void resumingRestartsTheChosenNodesAndWhatFollowsInANewRun() {
        createWorkflow("etl", null, Map.of("env", "dev"));
        twoStepPipeline("etl");
        String first = workflows.startWorkflowRun("etl", null);
        Map<String, Object> graph = workflows.workflowView(glueService.getWorkflow("etl"), true).getGraph();
        String extractNode = nodeId(graph, "JOB", "extract");
        String triggerNode = nodeId(graph, "TRIGGER", "start");

        String resumed = workflows.resumeWorkflowRun("etl", first, List.of(extractNode)).runId();

        WorkflowRun run = workflows.getWorkflowRun("etl", resumed, false);
        assertEquals(first, run.getPreviousRunId());
        assertEquals("COMPLETED", run.getStatus());
        assertEquals(Map.of("env", "dev"), run.getWorkflowRunProperties());
        assertEquals(2, run.getStatistics().get("TotalActions"));
        assertEquals(2, runsOf("load").size());
        assertEquals("InvalidInputException", assertThrows(AwsException.class,
                () -> workflows.resumeWorkflowRun("etl", first, List.of(triggerNode))).getErrorCode());
    }

    @Test
    void aNodeNamedTwiceIsResumedOnce() {
        useDurations(0, 60);
        createWorkflow("etl", null, null);
        onDemand("start", "etl", startCrawler("raw"));
        String first = workflows.startWorkflowRun("etl", null);
        clock.advance(Duration.ofSeconds(61));
        Map<String, Object> graph = workflows.workflowView(glueService.getWorkflow("etl"), true).getGraph();
        String raw = nodeId(graph, "CRAWLER", "raw");

        GlueTriggerService.ResumedRun resumed = workflows.resumeWorkflowRun("etl", first, List.of(raw, raw));

        assertEquals(List.of(raw), resumed.nodeIds());
        assertEquals("RUNNING", workflows.getWorkflowRun("etl", resumed.runId(), false).getStatus());
        assertNull(workflows.getWorkflowRun("etl", resumed.runId(), false).getErrorMessage());
    }

    @Test
    void aRunningWorkflowRunCannotBeResumed() {
        useRunDuration(60);
        createWorkflow("etl", null, null);
        twoStepPipeline("etl");
        String runId = workflows.startWorkflowRun("etl", null);

        assertEquals("IllegalWorkflowStateException", assertThrows(AwsException.class,
                () -> workflows.resumeWorkflowRun("etl", runId, List.of("wnode_any"))).getErrorCode());
    }

    @Test
    @SuppressWarnings("unchecked")
    void theGraphLinksTriggersToTheJobsTheyWatchAndStart() {
        createWorkflow("etl", null, null);
        twoStepPipeline("etl");
        String runId = workflows.startWorkflowRun("etl", null);

        Map<String, Object> graph = workflows.workflowView(glueService.getWorkflow("etl"), true).getGraph();
        List<Map<String, Object>> nodes = (List<Map<String, Object>>) graph.get("Nodes");
        List<Map<String, Object>> edges = (List<Map<String, Object>>) graph.get("Edges");
        assertEquals(4, nodes.size());
        assertEquals(3, edges.size());
        assertTrue(edges.contains(Map.of("SourceId", nodeId(graph, "TRIGGER", "start"),
                "DestinationId", nodeId(graph, "JOB", "extract"))));
        assertTrue(edges.contains(Map.of("SourceId", nodeId(graph, "JOB", "extract"),
                "DestinationId", nodeId(graph, "TRIGGER", "then-load"))));

        Map<String, Object> runGraph = workflows.getWorkflowRun("etl", runId, true).getGraph();
        Map<String, Object> loadNode = ((List<Map<String, Object>>) runGraph.get("Nodes")).stream()
                .filter(node -> "load".equals(node.get("Name"))).findFirst().orElseThrow();
        assertEquals(1, ((List<JobRun>) ((Map<String, Object>) loadNode.get("JobDetails")).get("JobRuns")).size());
        assertNull(workflows.getWorkflowRun("etl", runId, false).getGraph());
    }

    @Test
    void runPropertiesAreMergedAndLastRunIsReported() {
        createWorkflow("etl", null, Map.of("env", "dev"));
        twoStepPipeline("etl");
        String runId = workflows.startWorkflowRun("etl", null);

        workflows.putWorkflowRunProperties("etl", runId, Map.of("rows", "42", "env", "prod"));

        assertEquals(Map.of("env", "prod", "rows", "42"), workflows.getWorkflowRunProperties("etl", runId));
        Workflow workflow = workflows.workflowView(glueService.getWorkflow("etl"), false);
        assertEquals(runId, workflow.getLastRun().getWorkflowRunId());
        assertNull(workflow.getGraph());
        assertEquals("EntityNotFoundException", assertThrows(AwsException.class,
                () -> workflows.getWorkflowRunProperties("etl", "wr_missing")).getErrorCode());
    }

    @Test
    void deletingAWorkflowRemovesItsRunsButKeepsItsTriggers() {
        createWorkflow("etl", null, null);
        twoStepPipeline("etl");
        workflows.startWorkflowRun("etl", null);

        workflows.deleteWorkflow("etl", REGION);
        createWorkflow("etl", null, null);

        assertTrue(workflows.getWorkflowRuns("etl", false, null, null).items().isEmpty());
        assertEquals("etl", glueService.getTrigger("start").getWorkflowName());
    }

    @Test
    void concurrentStartsRespectMaxConcurrentRuns() throws Exception {
        useRunDuration(60);
        createWorkflow("etl", 1, null);
        twoStepPipeline("etl");
        int threads = 8;
        CountDownLatch go = new CountDownLatch(1);
        AtomicInteger started = new AtomicInteger();
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        try {
            List<Future<?>> futures = new ArrayList<>();
            for (int i = 0; i < threads; i++) {
                futures.add(pool.submit(() -> {
                    go.await();
                    try {
                        workflows.startWorkflowRun("etl", null);
                        started.incrementAndGet();
                    } catch (AwsException expected) {
                        // ConcurrentRunsExceededException is the outcome under test for all but one start.
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
        assertEquals(1, workflows.getWorkflowRuns("etl", false, null, null).items().size());
    }

    @Test
    void aRunWhoseStartingActionsCannotLaunchEndsInError() {
        useRunDuration(60);
        createWorkflow("etl", null, null);
        onDemand("start", "etl", startCrawler("raw"));
        crawls.startCrawler("raw");

        String runId = workflows.startWorkflowRun("etl", null);

        WorkflowRun run = workflows.getWorkflowRun("etl", runId, false);
        assertEquals("ERROR", run.getStatus());
        assertTrue(run.getErrorMessage().contains("could not start raw"), run.getErrorMessage());
        assertNotNull(run.getCompletedOn());
    }

    @Test
    void aLaunchFailureBesideAStartedActionIsReportedOnTheRun() {
        useRunDuration(60);
        createWorkflow("etl", null, null);
        onDemand("start", "etl", startCrawler("raw"), startJob("extract"));
        crawls.startCrawler("raw");

        String runId = workflows.startWorkflowRun("etl", null);

        WorkflowRun run = workflows.getWorkflowRun("etl", runId, false);
        assertEquals("RUNNING", run.getStatus());
        assertTrue(run.getErrorMessage().contains("could not start raw"), run.getErrorMessage());
    }

    @Test
    void aResumeThatCannotLaunchANodeLeavesNoRunBehind() {
        useDurations(0, 60);
        createWorkflow("etl", null, null);
        onDemand("start", "etl", startJob("extract"), startCrawler("raw"));
        String first = workflows.startWorkflowRun("etl", null);
        clock.advance(Duration.ofSeconds(61));
        assertEquals("COMPLETED", workflows.getWorkflowRun("etl", first, false).getStatus());
        Map<String, Object> graph = workflows.workflowView(glueService.getWorkflow("etl"), true).getGraph();
        crawls.startCrawler("raw");

        assertEquals("CrawlerRunningException", assertThrows(AwsException.class, () -> workflows.resumeWorkflowRun(
                "etl", first, List.of(nodeId(graph, "JOB", "extract"), nodeId(graph, "CRAWLER", "raw")))).getErrorCode());

        assertEquals(1, workflows.getWorkflowRuns("etl", false, null, null).items().size());
        assertEquals(1, runsOf("extract").size());
    }

    @Test
    void deletingAWorkflowWhileItsCrawlRunsLeavesNoCrawlHistoryBehind() {
        useDurations(0, 60);
        createWorkflow("etl", null, null);
        onDemand("start", "etl", startCrawler("raw"));
        String runId = workflows.startWorkflowRun("etl", null);

        workflows.deleteWorkflow("etl", REGION);
        clock.advance(Duration.ofSeconds(61));
        crawls.startCrawler("raw");

        assertTrue(crawls.crawlsInWorkflowRun(runId).isEmpty());
    }

    @Test
    void aResumedJobRunsWithItsOriginalArguments() {
        createWorkflow("etl", null, null);
        TriggerAction extract = startJob("extract");
        extract.setArguments(Map.of("--mode", "full"));
        onDemand("start", "etl", extract);
        String first = workflows.startWorkflowRun("etl", null);
        Map<String, Object> graph = workflows.workflowView(glueService.getWorkflow("etl"), true).getGraph();

        String resumed = workflows.resumeWorkflowRun("etl", first, List.of(nodeId(graph, "JOB", "extract"))).runId();

        JobRun rerun = runsOf("extract").getFirst();
        assertEquals("full", rerun.getArguments().get("--mode"));
        assertEquals(resumed, rerun.getArguments().get("--WORKFLOW_RUN_ID"));
        assertEquals("start", rerun.getTriggerName());
    }

    /** A workflow run keeps its crawls however many newer crawls the crawler has run since. */
    @Test
    void anOlderRunKeepsItsCrawlsAfterTheCrawlerHistoryMovesOn() {
        createWorkflow("etl", null, null);
        onDemand("start", "etl", startCrawler("raw"));
        String runId = workflows.startWorkflowRun("etl", null);
        for (int i = 0; i < 110; i++) {
            crawls.startCrawler("raw");
        }
        Map<String, Object> graph = workflows.workflowView(glueService.getWorkflow("etl"), true).getGraph();

        assertEquals(1, workflows.getWorkflowRun("etl", runId, false).getStatistics().get("TotalActions"));
        assertNotNull(workflows.resumeWorkflowRun("etl", runId, List.of(nodeId(graph, "CRAWLER", "raw"))).runId());
    }

    @Test
    void aRunThatHasFinishedCannotBeStopped() {
        useRunDuration(60);
        createWorkflow("etl", null, null);
        onDemand("start", "etl", startJob("extract"));
        String runId = workflows.startWorkflowRun("etl", null);
        clock.advance(Duration.ofSeconds(61));

        assertEquals("IllegalWorkflowStateException", assertThrows(AwsException.class,
                () -> workflows.stopWorkflowRun("etl", runId)).getErrorCode());
        assertEquals("COMPLETED", workflows.getWorkflowRun("etl", runId, false).getStatus());
    }

    @SuppressWarnings("unchecked")
    private static String nodeId(Map<String, Object> graph, String type, String name) {
        return ((List<Map<String, Object>>) graph.get("Nodes")).stream()
                .filter(node -> type.equals(node.get("Type")) && name.equals(node.get("Name")))
                .map(node -> (String) node.get("UniqueId"))
                .findFirst().orElseThrow();
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
