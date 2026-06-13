package io.github.hectorvent.floci.services.batch;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.hectorvent.floci.config.EmulatorConfig;
import io.github.hectorvent.floci.core.common.AwsArnUtils;
import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.core.common.RegionResolver;
import io.github.hectorvent.floci.core.storage.AccountAwareStorageBackend;
import io.github.hectorvent.floci.core.storage.StorageBackend;
import io.github.hectorvent.floci.core.storage.StorageFactory;
import io.github.hectorvent.floci.services.batch.model.BatchAttempt;
import io.github.hectorvent.floci.services.batch.model.BatchAttemptContainer;
import io.github.hectorvent.floci.services.batch.model.BatchComputeEnvironment;
import io.github.hectorvent.floci.services.batch.model.BatchComputeEnvironmentOrder;
import io.github.hectorvent.floci.services.batch.model.BatchContainerOverrides;
import io.github.hectorvent.floci.services.batch.model.BatchContainerProperties;
import io.github.hectorvent.floci.services.batch.model.BatchJob;
import io.github.hectorvent.floci.services.batch.model.BatchJobDefinition;
import io.github.hectorvent.floci.services.batch.model.BatchJobQueue;
import io.github.hectorvent.floci.services.batch.model.BatchKeyValue;
import io.github.hectorvent.floci.services.batch.model.BatchResourceRequirement;
import io.github.hectorvent.floci.services.batch.model.BatchRetryStrategy;
import io.github.hectorvent.floci.services.batch.model.BatchRunResult;
import io.github.hectorvent.floci.services.batch.model.BatchStatus;
import io.github.hectorvent.floci.services.batch.model.BatchTimeout;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;

@ApplicationScoped
public class BatchService {

    private static final Logger LOG = Logger.getLogger(BatchService.class);
    private static final int DESCRIBE_JOBS_LIMIT = 100;
    private static final int DEFAULT_MAX_RESULTS = 1000;
    private static final int FILTERED_MAX_RESULTS = 100;
    private static final int MAX_TAGS = 50;
    private static final Pattern JOB_NAME_PATTERN = Pattern.compile("[A-Za-z0-9][A-Za-z0-9_-]{0,127}");
    private static final Set<String> SUPPORTED_JOB_DEFINITION_TYPES = Set.of("container");
    private static final Set<String> SUPPORTED_COMPUTE_ENVIRONMENT_TYPES = Set.of("MANAGED", "UNMANAGED");
    private static final Set<String> SUPPORTED_LIST_FILTERS = Set.of(
            "JOB_NAME", "JOB_DEFINITION", "BEFORE_CREATED_AT", "AFTER_CREATED_AT", "SHARE_IDENTIFIER");

    private final StorageBackend<String, BatchJobDefinition> jobDefinitionStore;
    private final StorageBackend<String, BatchJobQueue> jobQueueStore;
    private final StorageBackend<String, BatchComputeEnvironment> computeEnvironmentStore;
    private final StorageBackend<String, BatchJob> jobStore;
    private final RegionResolver regionResolver;
    private final EmulatorConfig config;
    private final ObjectMapper objectMapper;
    private final BatchDockerRunner dockerRunner;

    @Inject
    public BatchService(StorageFactory storageFactory,
                        RegionResolver regionResolver,
                        EmulatorConfig config,
                        ObjectMapper objectMapper,
                        BatchDockerRunner dockerRunner) {
        this(
                storageFactory.create("batch", "batch-job-definitions.json",
                        new TypeReference<Map<String, BatchJobDefinition>>() {}),
                storageFactory.create("batch", "batch-job-queues.json",
                        new TypeReference<Map<String, BatchJobQueue>>() {}),
                storageFactory.create("batch", "batch-compute-environments.json",
                        new TypeReference<Map<String, BatchComputeEnvironment>>() {}),
                storageFactory.create("batch", "batch-jobs.json",
                        new TypeReference<Map<String, BatchJob>>() {}),
                regionResolver,
                config,
                objectMapper,
                dockerRunner
        );
    }

    BatchService(StorageBackend<String, BatchJobDefinition> jobDefinitionStore,
                 StorageBackend<String, BatchJobQueue> jobQueueStore,
                 StorageBackend<String, BatchComputeEnvironment> computeEnvironmentStore,
                 StorageBackend<String, BatchJob> jobStore,
                 RegionResolver regionResolver,
                 EmulatorConfig config,
                 ObjectMapper objectMapper,
                 BatchDockerRunner dockerRunner) {
        this.jobDefinitionStore = jobDefinitionStore;
        this.jobQueueStore = jobQueueStore;
        this.computeEnvironmentStore = computeEnvironmentStore;
        this.jobStore = jobStore;
        this.regionResolver = regionResolver;
        this.config = config;
        this.objectMapper = objectMapper;
        this.dockerRunner = dockerRunner;
    }

    public synchronized ObjectNode createComputeEnvironment(JsonNode request, String region) {
        String name = requiredText(request, "computeEnvironmentName");
        String type = requiredText(request, "type");
        validateComputeEnvironmentType(type);
        if (resolveComputeEnvironmentOptional(name).isPresent()) {
            throw client("Compute environment already exists: " + name);
        }
        BatchComputeEnvironment env = new BatchComputeEnvironment();
        env.setComputeEnvironmentName(name);
        env.setComputeEnvironmentArn(arn(region, "compute-environment/" + name));
        env.setType(type);
        env.setState(text(request, "state", "ENABLED"));
        env.setStatus("VALID");
        env.setStatusReason("Compute environment is available");
        env.setComputeResources(map(request.path("computeResources")));
        env.setServiceRole(textOrNull(request, "serviceRole"));
        env.setTags(stringMap(request.path("tags")));
        validateTags(env.getTags());
        env.setCreatedAt(now());
        env.setRegion(region);
        env.setAccountId(regionResolver.getAccountId());
        computeEnvironmentStore.put(env.getComputeEnvironmentArn(), env);

        ObjectNode out = objectMapper.createObjectNode();
        out.put("computeEnvironmentName", env.getComputeEnvironmentName());
        out.put("computeEnvironmentArn", env.getComputeEnvironmentArn());
        return out;
    }

    public ObjectNode describeComputeEnvironments(JsonNode request) {
        List<String> refs = stringList(request.path("computeEnvironments"));
        if (refs.size() > DESCRIBE_JOBS_LIMIT) {
            throw client("DescribeComputeEnvironments accepts at most 100 compute environments");
        }
        int maxResults = describeMaxResults(request);
        int offset = parseNextToken(textOrNull(request, "nextToken"));
        List<BatchComputeEnvironment> envs;
        if (refs.isEmpty()) {
            envs = computeEnvironmentStore.scan(k -> true).stream()
                    .sorted(Comparator.comparing(BatchComputeEnvironment::getComputeEnvironmentName))
                    .toList();
        } else {
            envs = refs.stream()
                    .map(this::resolveComputeEnvironmentOptional)
                    .flatMap(Optional::stream)
                    .toList();
        }
        ArrayNode list = objectMapper.createArrayNode();
        int end = Math.min(envs.size(), offset + maxResults);
        for (int i = offset; i < end; i++) {
            list.add(computeEnvironmentDetail(envs.get(i)));
        }
        ObjectNode out = objectMapper.createObjectNode();
        out.set("computeEnvironments", list);
        if (end < envs.size()) {
            out.put("nextToken", String.valueOf(end));
        }
        return out;
    }

    public synchronized ObjectNode createJobQueue(JsonNode request, String region) {
        String name = requiredText(request, "jobQueueName");
        if (resolveJobQueueOptional(name).isPresent()) {
            throw client("Job queue already exists: " + name);
        }
        if (!request.hasNonNull("priority")) {
            throw client("priority is required");
        }
        List<BatchComputeEnvironmentOrder> orders = computeEnvironmentOrder(request.path("computeEnvironmentOrder"));
        for (BatchComputeEnvironmentOrder order : orders) {
            BatchComputeEnvironment env = resolveComputeEnvironment(order.getComputeEnvironment());
            if (!"VALID".equals(env.getStatus())) {
                throw client("Compute environment is not VALID: " + order.getComputeEnvironment());
            }
        }

        BatchJobQueue queue = new BatchJobQueue();
        queue.setJobQueueName(name);
        queue.setJobQueueArn(arn(region, "job-queue/" + name));
        queue.setState(text(request, "state", "ENABLED"));
        queue.setStatus("VALID");
        queue.setStatusReason("Job queue is available");
        queue.setPriority(request.path("priority").asInt());
        queue.setJobQueueType(text(request, "jobQueueType", "ECS"));
        queue.setComputeEnvironmentOrder(orders);
        queue.setTags(stringMap(request.path("tags")));
        validateTags(queue.getTags());
        queue.setCreatedAt(now());
        queue.setRegion(region);
        queue.setAccountId(regionResolver.getAccountId());
        jobQueueStore.put(queue.getJobQueueArn(), queue);

        ObjectNode out = objectMapper.createObjectNode();
        out.put("jobQueueName", queue.getJobQueueName());
        out.put("jobQueueArn", queue.getJobQueueArn());
        return out;
    }

    public ObjectNode describeJobQueues(JsonNode request) {
        List<String> refs = stringList(request.path("jobQueues"));
        if (refs.size() > DESCRIBE_JOBS_LIMIT) {
            throw client("DescribeJobQueues accepts at most 100 job queues");
        }
        int maxResults = describeMaxResults(request);
        int offset = parseNextToken(textOrNull(request, "nextToken"));
        List<BatchJobQueue> queues;
        if (refs.isEmpty()) {
            queues = jobQueueStore.scan(k -> true).stream()
                    .sorted(Comparator.comparing(BatchJobQueue::getJobQueueName))
                    .toList();
        } else {
            queues = refs.stream()
                    .map(this::resolveJobQueueOptional)
                    .flatMap(Optional::stream)
                    .toList();
        }
        ArrayNode list = objectMapper.createArrayNode();
        int end = Math.min(queues.size(), offset + maxResults);
        for (int i = offset; i < end; i++) {
            list.add(jobQueueDetail(queues.get(i)));
        }
        ObjectNode out = objectMapper.createObjectNode();
        out.set("jobQueues", list);
        if (end < queues.size()) {
            out.put("nextToken", String.valueOf(end));
        }
        return out;
    }

    public synchronized ObjectNode registerJobDefinition(JsonNode request, String region) {
        String name = requiredText(request, "jobDefinitionName");
        String type = requiredText(request, "type");
        validateJobDefinitionType(type);
        BatchContainerProperties container = tree(request.path("containerProperties"), BatchContainerProperties.class);
        if (container == null) {
            throw client("containerProperties is required for container job definitions");
        }
        validateEnvironment(container.getEnvironment());

        int revision = nextRevision(name);
        BatchJobDefinition def = new BatchJobDefinition();
        def.setJobDefinitionName(name);
        def.setRevision(revision);
        def.setJobDefinitionArn(arn(region, "job-definition/" + name + ":" + revision));
        def.setStatus("ACTIVE");
        def.setType(type);
        def.setContainerProperties(container);
        def.setParameters(stringMap(request.path("parameters")));
        def.setRetryStrategy(tree(request.path("retryStrategy"), BatchRetryStrategy.class));
        validateRetryStrategy(def.getRetryStrategy());
        def.setTimeout(tree(request.path("timeout"), BatchTimeout.class));
        validateTimeout(def.getTimeout());
        def.setPlatformCapabilities(stringList(request.path("platformCapabilities")));
        def.setTags(stringMap(request.path("tags")));
        validateTags(def.getTags());
        def.setCreatedAt(now());
        def.setRegion(region);
        def.setAccountId(regionResolver.getAccountId());
        jobDefinitionStore.put(def.getJobDefinitionArn(), def);

        ObjectNode out = objectMapper.createObjectNode();
        out.put("jobDefinitionName", def.getJobDefinitionName());
        out.put("jobDefinitionArn", def.getJobDefinitionArn());
        out.put("revision", def.getRevision());
        return out;
    }

    public synchronized ObjectNode deregisterJobDefinition(JsonNode request) {
        String ref = requiredText(request, "jobDefinition");
        validateDeregisterJobDefinitionRef(ref);
        BatchJobDefinition def = resolveJobDefinition(ref, true);
        def.setStatus("INACTIVE");
        putJobDefinition(def);
        return objectMapper.createObjectNode();
    }

    public ObjectNode describeJobDefinitions(JsonNode request) {
        String status = textOrNull(request, "status");
        String name = textOrNull(request, "jobDefinitionName");
        List<String> refs = stringList(request.path("jobDefinitions"));
        if (!refs.isEmpty() && (status != null || name != null)) {
            throw client("jobDefinitions can't be used with other parameters");
        }
        if (refs.size() > DESCRIBE_JOBS_LIMIT) {
            throw client("DescribeJobDefinitions accepts at most 100 job definitions");
        }
        int maxResults = describeMaxResults(request);
        int offset = parseNextToken(textOrNull(request, "nextToken"));
        List<BatchJobDefinition> definitions = jobDefinitionStore.scan(k -> true).stream()
                .filter(def -> status == null || status.equals(def.getStatus()))
                .filter(def -> name == null || name.equals(def.getJobDefinitionName()))
                .filter(def -> refs.isEmpty() || refs.stream().anyMatch(ref -> matchesJobDefinition(def, ref)))
                .sorted(Comparator.comparing(BatchJobDefinition::getJobDefinitionName)
                        .thenComparing(BatchJobDefinition::getRevision))
                .toList();
        ArrayNode list = objectMapper.createArrayNode();
        int end = Math.min(definitions.size(), offset + maxResults);
        for (int i = offset; i < end; i++) {
            list.add(jobDefinitionDetail(definitions.get(i)));
        }
        ObjectNode out = objectMapper.createObjectNode();
        out.set("jobDefinitions", list);
        if (end < definitions.size()) {
            out.put("nextToken", String.valueOf(end));
        }
        return out;
    }

    public ObjectNode submitJob(JsonNode request, String region) {
        rejectUnsupportedSubmitFields(request);
        String jobName = requiredText(request, "jobName");
        validateJobName(jobName);
        BatchJobQueue queue = resolveJobQueue(requiredText(request, "jobQueue"));
        if (!"ENABLED".equals(queue.getState()) || !"VALID".equals(queue.getStatus())) {
            throw client("Job queue is not enabled and valid: " + queue.getJobQueueName());
        }
        BatchJobDefinition def = resolveJobDefinition(requiredText(request, "jobDefinition"), false);
        if (!"ACTIVE".equals(def.getStatus())) {
            throw client("Job definition is not ACTIVE: " + def.getJobDefinitionArn());
        }

        BatchContainerOverrides overrides = tree(request.path("containerOverrides"), BatchContainerOverrides.class);
        if (overrides == null) {
            overrides = new BatchContainerOverrides();
        }
        validateEnvironment(overrides.getEnvironment());

        Map<String, String> parameters = new LinkedHashMap<>(def.getParameters());
        parameters.putAll(stringMap(request.path("parameters")));

        BatchContainerProperties container = def.getContainerProperties() != null
                ? def.getContainerProperties() : new BatchContainerProperties();
        List<String> command = overrides.getCommand() != null && !overrides.getCommand().isEmpty()
                ? overrides.getCommand()
                : container.getCommand();

        BatchJob job = new BatchJob();
        job.setJobId(UUID.randomUUID().toString());
        job.setJobArn(arn(region, "job/" + job.getJobId()));
        job.setJobName(jobName);
        job.setJobQueue(queue.getJobQueueArn());
        job.setJobQueueName(queue.getJobQueueName());
        job.setJobDefinition(def.getJobDefinitionArn());
        job.setJobDefinitionName(def.getJobDefinitionName());
        job.setJobDefinitionRevision(def.getRevision());
        job.setStatus(BatchStatus.SUBMITTED.name());
        job.setCreatedAt(now());
        job.setParameters(parameters);
        job.setContainerOverrides(overrides);
        job.setResolvedCommand(resolveCommand(command, parameters));
        job.setResolvedEnvironment(resolveEnvironment(container.getEnvironment(), overrides.getEnvironment()));
        job.setResourceRequirements(resolveResourceRequirements(container, overrides));
        job.setRetryStrategy(treeOrDefault(request.path("retryStrategy"), def.getRetryStrategy(), BatchRetryStrategy.class));
        validateRetryStrategy(job.getRetryStrategy());
        job.setTimeout(treeOrDefault(request.path("timeout"), def.getTimeout(), BatchTimeout.class));
        validateTimeout(job.getTimeout());
        job.setTags(stringMap(request.path("tags")));
        validateTags(job.getTags());
        job.setRegion(region);
        job.setAccountId(regionResolver.getAccountId());
        job.setContainerImage(container.getImage());
        putJob(job);
        if (shouldRunDocker()) {
            Thread.startVirtualThread(() -> runJob(job.getAccountId(), job.getJobId()));
        } else {
            runJob(job.getAccountId(), job.getJobId());
        }

        ObjectNode out = objectMapper.createObjectNode();
        out.put("jobArn", job.getJobArn());
        out.put("jobId", job.getJobId());
        out.put("jobName", job.getJobName());
        return out;
    }

    public synchronized ObjectNode describeJobs(JsonNode request) {
        List<String> ids = stringList(request.path("jobs"));
        if (ids.isEmpty()) {
            throw client("jobs is required");
        }
        if (ids.size() > DESCRIBE_JOBS_LIMIT) {
            throw client("DescribeJobs accepts at most 100 job IDs");
        }
        ArrayNode list = objectMapper.createArrayNode();
        for (String id : ids) {
            getJob(id).ifPresent(job -> list.add(jobDetail(job)));
        }
        ObjectNode out = objectMapper.createObjectNode();
        out.set("jobs", list);
        return out;
    }

    public synchronized ObjectNode listJobs(JsonNode request) {
        if (!request.hasNonNull("jobQueue")) {
            throw client("jobQueue is required");
        }
        if (request.hasNonNull("arrayJobId") || request.hasNonNull("multiNodeJobId")) {
            throw client("arrayJobId and multiNodeJobId are not supported");
        }
        BatchJobQueue queue = resolveJobQueue(requiredText(request, "jobQueue"));
        String status = textOrNull(request, "jobStatus");
        ListFilter filter = listFilter(request);
        String effectiveStatus = filter == null && status == null ? BatchStatus.RUNNING.name() : status;
        int maxResults = request.hasNonNull("maxResults")
                ? Math.max(1, Math.min(filter == null ? DEFAULT_MAX_RESULTS : FILTERED_MAX_RESULTS,
                        request.path("maxResults").asInt()))
                : filter == null ? DEFAULT_MAX_RESULTS : FILTERED_MAX_RESULTS;
        int offset = parseNextToken(textOrNull(request, "nextToken"));

        List<BatchJob> jobs = jobStore.scan(k -> true).stream()
                .filter(job -> queue.getJobQueueArn().equals(job.getJobQueue()))
                .filter(job -> (filter != null && !"SHARE_IDENTIFIER".equals(filter.name()))
                        || effectiveStatus == null
                        || effectiveStatus.equals(job.getStatus()))
                .filter(job -> matchesListFilter(job, filter))
                .sorted(Comparator.comparingLong(BatchJob::getCreatedAt)
                        .reversed()
                        .thenComparing(BatchJob::getJobId))
                .toList();

        ArrayNode summaries = objectMapper.createArrayNode();
        int end = Math.min(jobs.size(), offset + maxResults);
        for (int i = offset; i < end; i++) {
            summaries.add(jobSummary(jobs.get(i)));
        }
        ObjectNode out = objectMapper.createObjectNode();
        out.set("jobSummaryList", summaries);
        if (end < jobs.size()) {
            out.put("nextToken", String.valueOf(end));
        }
        return out;
    }

    public void submitFromEventBridge(String jobQueueArn, String jobDefinition, String jobName,
                                      Map<String, String> parameters, JsonNode retryStrategy, String region) {
        ObjectNode request = objectMapper.createObjectNode();
        request.put("jobQueue", jobQueueArn);
        request.put("jobDefinition", jobDefinition);
        request.put("jobName", jobName);
        ObjectNode params = request.putObject("parameters");
        parameters.forEach(params::put);
        ObjectNode retry = eventBridgeRetryStrategy(retryStrategy);
        if (!retry.isEmpty()) {
            request.set("retryStrategy", retry);
        }
        submitJob(request, region);
    }

    private ObjectNode eventBridgeRetryStrategy(JsonNode retryStrategy) {
        ObjectNode retry = objectMapper.createObjectNode();
        if (retryStrategy == null || !retryStrategy.isObject()) {
            return retry;
        }
        if (retryStrategy.has("Attempts")) {
            retry.set("attempts", retryStrategy.get("Attempts"));
        }
        return retry;
    }

    private void runJob(String accountId, String jobId) {
        try {
            BatchJob job = getJobForAccount(accountId, jobId).orElse(null);
            if (job == null) {
                return;
            }
            int maxAttempts = maxAttempts(job);
            for (int attemptNumber = 1; attemptNumber <= maxAttempts; attemptNumber++) {
                transition(accountId, jobId, BatchStatus.PENDING, null, false);
                sleepQuietly();
                transition(accountId, jobId, BatchStatus.RUNNABLE, null, false);
                sleepQuietly();
                transition(accountId, jobId, BatchStatus.STARTING, null, false);
                sleepQuietly();
                transition(accountId, jobId, BatchStatus.RUNNING, null, true);
                BatchJob attemptJob = getJobForAccount(accountId, jobId).orElse(null);
                if (attemptJob == null) {
                    return;
                }
                BatchRunResult result = shouldRunDocker()
                        ? dockerRunner.run(attemptJob, attemptNumber)
                        : immediateSuccess(attemptJob);
                if (finishAttempt(accountId, jobId, result, attemptNumber >= maxAttempts)) {
                    return;
                }
            }
        } catch (Exception e) {
            LOG.warnv("Batch job {0} failed while running: {1}", jobId, e.getMessage());
            failJob(accountId, jobId, e.getMessage());
        }
    }

    private boolean shouldRunDocker() {
        return "docker".equalsIgnoreCase(config.services().batch().runnerMode());
    }

    private BatchRunResult immediateSuccess(BatchJob job) {
        long started = job.getStartedAt() != null ? job.getStartedAt() : now();
        sleepQuietly();
        return new BatchRunResult(0, null, job.getJobDefinitionName() + "/default/" + job.getJobId(),
                started, now(), false);
    }

    private synchronized boolean finishAttempt(String accountId, String jobId, BatchRunResult result, boolean finalAttempt) {
        BatchJob job = getJobForAccount(accountId, jobId).orElse(null);
        if (job == null) {
            return true;
        }
        BatchAttemptContainer container = new BatchAttemptContainer();
        container.setExitCode(result.exitCode());
        container.setReason(result.reason());
        container.setLogStreamName(result.logStreamName());

        BatchAttempt attempt = new BatchAttempt();
        attempt.setStartedAt(result.startedAt());
        attempt.setStoppedAt(result.stoppedAt());
        attempt.setStatusReason(result.reason());
        attempt.setContainer(container);

        job.getAttempts().add(attempt);
        job.setContainer(container);
        if (result.exitCode() == 0) {
            job.setStoppedAt(result.stoppedAt());
            job.setStatus(BatchStatus.SUCCEEDED.name());
            job.setStatusReason("Job completed successfully");
            putJobForAccount(accountId, job);
            return true;
        }
        if (result.timedOut() || finalAttempt) {
            job.setStoppedAt(result.stoppedAt());
            job.setStatus(BatchStatus.FAILED.name());
            job.setStatusReason(result.reason());
            putJobForAccount(accountId, job);
            return true;
        }
        job.setStatus(BatchStatus.RUNNABLE.name());
        job.setStatusReason("Attempt failed; retrying");
        putJobForAccount(accountId, job);
        return false;
    }

    private int maxAttempts(BatchJob job) {
        if (job.getRetryStrategy() == null || job.getRetryStrategy().getAttempts() == null) {
            return 1;
        }
        return job.getRetryStrategy().getAttempts();
    }

    private synchronized void failJob(String accountId, String jobId, String reason) {
        BatchJob job = getJobForAccount(accountId, jobId).orElse(null);
        if (job == null) {
            return;
        }
        job.setStatus(BatchStatus.FAILED.name());
        job.setStatusReason(reason);
        job.setStoppedAt(now());
        putJobForAccount(accountId, job);
    }

    private synchronized void transition(String accountId, String jobId, BatchStatus status, String reason, boolean started) {
        BatchJob job = getJobForAccount(accountId, jobId).orElse(null);
        if (job == null) {
            return;
        }
        job.setStatus(status.name());
        if (reason != null) {
            job.setStatusReason(reason);
        }
        if (started && job.getStartedAt() == null) {
            job.setStartedAt(now());
        }
        putJobForAccount(accountId, job);
    }

    private void sleepQuietly() {
        try {
            Thread.sleep(10);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private BatchJobQueue resolveJobQueue(String ref) {
        return resolveJobQueueOptional(ref).orElseThrow(() -> client("Job queue not found: " + ref));
    }

    private Optional<BatchJobQueue> resolveJobQueueOptional(String ref) {
        if (ref == null || ref.isBlank()) {
            return Optional.empty();
        }
        Optional<BatchJobQueue> byArn = jobQueueStore.get(ref);
        if (byArn.isPresent()) {
            return byArn;
        }
        return jobQueueStore.scan(k -> true).stream()
                .filter(q -> ref.equals(q.getJobQueueName()) || ref.equals(q.getJobQueueArn()))
                .findFirst();
    }

    private BatchComputeEnvironment resolveComputeEnvironment(String ref) {
        return resolveComputeEnvironmentOptional(ref)
                .orElseThrow(() -> client("Compute environment not found: " + ref));
    }

    private Optional<BatchComputeEnvironment> resolveComputeEnvironmentOptional(String ref) {
        if (ref == null || ref.isBlank()) {
            return Optional.empty();
        }
        Optional<BatchComputeEnvironment> byArn = computeEnvironmentStore.get(ref);
        if (byArn.isPresent()) {
            return byArn;
        }
        return computeEnvironmentStore.scan(k -> true).stream()
                .filter(env -> ref.equals(env.getComputeEnvironmentName()) || ref.equals(env.getComputeEnvironmentArn()))
                .findFirst();
    }

    private BatchJobDefinition resolveJobDefinition(String ref, boolean includeInactive) {
        return resolveJobDefinitionOptional(ref, includeInactive)
                .orElseThrow(() -> client("Job definition not found: " + ref));
    }

    private Optional<BatchJobDefinition> resolveJobDefinitionOptional(String ref, boolean includeInactive) {
        if (ref == null || ref.isBlank()) {
            return Optional.empty();
        }
        Optional<BatchJobDefinition> byArn = jobDefinitionStore.get(ref);
        if (byArn.isPresent() && (includeInactive || "ACTIVE".equals(byArn.get().getStatus()))) {
            return byArn;
        }
        String logical = ref;
        if (ref.startsWith("arn:")) {
            try {
                String resource = AwsArnUtils.parse(ref).resource();
                if (resource.startsWith("job-definition/")) {
                    logical = resource.substring("job-definition/".length());
                }
            } catch (IllegalArgumentException ignored) {
                return Optional.empty();
            }
        }
        String name = logical;
        Integer revision = null;
        int colon = logical.lastIndexOf(':');
        if (colon > 0) {
            name = logical.substring(0, colon);
            try {
                revision = Integer.parseInt(logical.substring(colon + 1));
            } catch (NumberFormatException e) {
                return Optional.empty();
            }
        }
        String finalName = name;
        Integer finalRevision = revision;
        return jobDefinitionStore.scan(k -> true).stream()
                .filter(def -> finalName.equals(def.getJobDefinitionName()))
                .filter(def -> finalRevision == null || finalRevision == def.getRevision())
                .filter(def -> includeInactive || "ACTIVE".equals(def.getStatus()))
                .max(Comparator.comparingInt(BatchJobDefinition::getRevision));
    }

    private boolean matchesJobDefinition(BatchJobDefinition def, String ref) {
        if (ref == null || ref.isBlank()) {
            return false;
        }
        if (ref.equals(def.getJobDefinitionArn())) {
            return true;
        }
        String logical = ref;
        if (ref.startsWith("arn:")) {
            try {
                String resource = AwsArnUtils.parse(ref).resource();
                if (!resource.startsWith("job-definition/")) {
                    return false;
                }
                logical = resource.substring("job-definition/".length());
            } catch (IllegalArgumentException ignored) {
                return false;
            }
        }
        int colon = logical.lastIndexOf(':');
        if (colon > 0) {
            String name = logical.substring(0, colon);
            try {
                int revision = Integer.parseInt(logical.substring(colon + 1));
                return name.equals(def.getJobDefinitionName()) && revision == def.getRevision();
            } catch (NumberFormatException e) {
                return false;
            }
        }
        return logical.equals(def.getJobDefinitionName());
    }

    private int nextRevision(String name) {
        return jobDefinitionStore.scan(k -> true).stream()
                .filter(def -> name.equals(def.getJobDefinitionName()))
                .mapToInt(BatchJobDefinition::getRevision)
                .max()
                .orElse(0) + 1;
    }

    private List<String> resolveCommand(List<String> command, Map<String, String> parameters) {
        if (command == null) {
            return List.of();
        }
        List<String> resolved = new ArrayList<>();
        for (String token : command) {
            if (token != null && token.startsWith("Ref::")) {
                resolved.add(parameters.getOrDefault(token.substring("Ref::".length()), token));
            } else {
                resolved.add(token);
            }
        }
        return resolved;
    }

    private List<BatchKeyValue> resolveEnvironment(List<BatchKeyValue> defaults, List<BatchKeyValue> overrides) {
        Map<String, String> merged = new LinkedHashMap<>();
        if (defaults != null) {
            defaults.forEach(kv -> merged.put(kv.getName(), kv.getValue()));
        }
        if (overrides != null) {
            overrides.forEach(kv -> merged.put(kv.getName(), kv.getValue()));
        }
        return merged.entrySet().stream().map(e -> new BatchKeyValue(e.getKey(), e.getValue())).toList();
    }

    private List<BatchResourceRequirement> resolveResourceRequirements(BatchContainerProperties container,
                                                                       BatchContainerOverrides overrides) {
        if (overrides.getResourceRequirements() != null && !overrides.getResourceRequirements().isEmpty()) {
            return overrides.getResourceRequirements();
        }
        return container.getResourceRequirements();
    }

    private void validateJobDefinitionType(String type) {
        if (!SUPPORTED_JOB_DEFINITION_TYPES.contains(type)) {
            throw client("Only container job definitions are supported");
        }
    }

    private void validateComputeEnvironmentType(String type) {
        if (!SUPPORTED_COMPUTE_ENVIRONMENT_TYPES.contains(type)) {
            throw client("type must be MANAGED or UNMANAGED");
        }
    }

    private void validateDeregisterJobDefinitionRef(String ref) {
        String logical = ref;
        if (ref.startsWith("arn:")) {
            try {
                String resource = AwsArnUtils.parse(ref).resource();
                if (!resource.startsWith("job-definition/")) {
                    throw client("jobDefinition must be a job definition ARN or name:revision");
                }
                logical = resource.substring("job-definition/".length());
            } catch (IllegalArgumentException e) {
                throw client("jobDefinition must be a job definition ARN or name:revision");
            }
        }
        int colon = logical.lastIndexOf(':');
        if (colon <= 0 || colon == logical.length() - 1) {
            throw client("jobDefinition must include a revision");
        }
        try {
            Integer.parseInt(logical.substring(colon + 1));
        } catch (NumberFormatException e) {
            throw client("jobDefinition must include a numeric revision");
        }
    }

    private void validateJobName(String jobName) {
        if (!JOB_NAME_PATTERN.matcher(jobName).matches()) {
            throw client("jobName must be 1-128 characters and contain only letters, numbers, hyphens, and underscores");
        }
    }

    private void validateTags(Map<String, String> tags) {
        if (tags == null || tags.isEmpty()) {
            return;
        }
        if (tags.size() > MAX_TAGS) {
            throw client("tags can contain at most 50 entries");
        }
        tags.forEach((key, value) -> {
            if (key == null || key.isBlank() || key.length() > 128) {
                throw client("tag keys must be 1-128 characters");
            }
            if (key.toLowerCase().startsWith("aws:")) {
                throw client("tag keys must not start with aws:");
            }
            if (value != null && value.length() > 256) {
                throw client("tag values must be at most 256 characters");
            }
        });
    }

    private void validateEnvironment(List<BatchKeyValue> env) {
        if (env == null) {
            return;
        }
        for (BatchKeyValue kv : env) {
            if (kv.getName() != null && kv.getName().startsWith("AWS_BATCH")) {
                throw client("Environment variables must not start with AWS_BATCH");
            }
        }
    }

    private void validateTimeout(BatchTimeout timeout) {
        if (timeout != null && timeout.getAttemptDurationSeconds() != null
                && timeout.getAttemptDurationSeconds() < 60) {
            throw client("timeout.attemptDurationSeconds must be at least 60");
        }
    }

    private void validateRetryStrategy(BatchRetryStrategy retryStrategy) {
        if (retryStrategy != null && retryStrategy.getAttempts() != null
                && (retryStrategy.getAttempts() < 1 || retryStrategy.getAttempts() > 10)) {
            throw client("retryStrategy.attempts must be between 1 and 10");
        }
    }

    private void rejectUnsupportedSubmitFields(JsonNode request) {
        if (request.hasNonNull("arrayProperties") || request.hasNonNull("dependsOn")
                || request.hasNonNull("nodeOverrides") || request.hasNonNull("nodeProperties")) {
            throw client("Array, dependency, and multi-node jobs are not supported");
        }
    }

    private ObjectNode jobSummary(BatchJob job) {
        ObjectNode summary = objectMapper.createObjectNode();
        summary.put("jobArn", job.getJobArn());
        summary.put("jobId", job.getJobId());
        summary.put("jobName", job.getJobName());
        summary.put("jobDefinition", job.getJobDefinition());
        summary.put("status", job.getStatus());
        summary.put("createdAt", job.getCreatedAt());
        if (job.getStartedAt() != null) {
            summary.put("startedAt", job.getStartedAt());
        }
        if (job.getStoppedAt() != null) {
            summary.put("stoppedAt", job.getStoppedAt());
        }
        if (job.getStatusReason() != null) {
            summary.put("statusReason", job.getStatusReason());
        }
        if (job.getContainer() != null) {
            ObjectNode container = objectMapper.createObjectNode();
            if (job.getContainer().getExitCode() != null) {
                container.put("exitCode", job.getContainer().getExitCode());
            }
            if (job.getContainer().getReason() != null) {
                container.put("reason", job.getContainer().getReason());
            }
            if (!container.isEmpty()) {
                summary.set("container", container);
            }
        }
        return summary;
    }

    private ObjectNode computeEnvironmentDetail(BatchComputeEnvironment env) {
        ObjectNode detail = objectMapper.createObjectNode();
        detail.put("computeEnvironmentName", env.getComputeEnvironmentName());
        detail.put("computeEnvironmentArn", env.getComputeEnvironmentArn());
        detail.put("type", env.getType());
        detail.put("state", env.getState());
        detail.put("status", env.getStatus());
        detail.put("statusReason", env.getStatusReason());
        if (env.getComputeResources() != null && !env.getComputeResources().isEmpty()) {
            detail.set("computeResources", objectMapper.valueToTree(env.getComputeResources()));
        }
        if (env.getServiceRole() != null) {
            detail.put("serviceRole", env.getServiceRole());
        }
        if (env.getTags() != null && !env.getTags().isEmpty()) {
            detail.set("tags", objectMapper.valueToTree(env.getTags()));
        }
        return detail;
    }

    private ObjectNode jobQueueDetail(BatchJobQueue queue) {
        ObjectNode detail = objectMapper.createObjectNode();
        detail.put("jobQueueName", queue.getJobQueueName());
        detail.put("jobQueueArn", queue.getJobQueueArn());
        detail.put("state", queue.getState());
        detail.put("status", queue.getStatus());
        detail.put("statusReason", queue.getStatusReason());
        detail.put("priority", queue.getPriority());
        if (queue.getJobQueueType() != null) {
            detail.put("jobQueueType", queue.getJobQueueType());
        }
        detail.set("computeEnvironmentOrder", objectMapper.valueToTree(queue.getComputeEnvironmentOrder()));
        if (queue.getTags() != null && !queue.getTags().isEmpty()) {
            detail.set("tags", objectMapper.valueToTree(queue.getTags()));
        }
        return detail;
    }

    private ObjectNode jobDefinitionDetail(BatchJobDefinition def) {
        ObjectNode detail = objectMapper.createObjectNode();
        detail.put("jobDefinitionName", def.getJobDefinitionName());
        detail.put("jobDefinitionArn", def.getJobDefinitionArn());
        detail.put("revision", def.getRevision());
        detail.put("status", def.getStatus());
        detail.put("type", def.getType());
        if (def.getContainerProperties() != null) {
            detail.set("containerProperties", objectMapper.valueToTree(def.getContainerProperties()));
        }
        if (def.getParameters() != null && !def.getParameters().isEmpty()) {
            detail.set("parameters", objectMapper.valueToTree(def.getParameters()));
        }
        if (def.getPlatformCapabilities() != null && !def.getPlatformCapabilities().isEmpty()) {
            detail.set("platformCapabilities", objectMapper.valueToTree(def.getPlatformCapabilities()));
        }
        if (def.getRetryStrategy() != null) {
            detail.set("retryStrategy", objectMapper.valueToTree(def.getRetryStrategy()));
        }
        if (def.getTimeout() != null) {
            detail.set("timeout", objectMapper.valueToTree(def.getTimeout()));
        }
        if (def.getTags() != null && !def.getTags().isEmpty()) {
            detail.set("tags", objectMapper.valueToTree(def.getTags()));
        }
        return detail;
    }

    private ObjectNode jobDetail(BatchJob job) {
        ObjectNode detail = objectMapper.createObjectNode();
        detail.put("jobArn", job.getJobArn());
        detail.put("jobId", job.getJobId());
        detail.put("jobName", job.getJobName());
        detail.put("jobQueue", job.getJobQueue());
        detail.put("jobDefinition", job.getJobDefinition());
        detail.put("status", job.getStatus());
        detail.put("createdAt", job.getCreatedAt());
        if (job.getStatusReason() != null) {
            detail.put("statusReason", job.getStatusReason());
        }
        if (job.getStartedAt() != null) {
            detail.put("startedAt", job.getStartedAt());
        }
        if (job.getStoppedAt() != null) {
            detail.put("stoppedAt", job.getStoppedAt());
        }
        detail.set("parameters", objectMapper.valueToTree(job.getParameters()));
        detail.set("attempts", objectMapper.valueToTree(job.getAttempts()));
        if (job.getRetryStrategy() != null) {
            detail.set("retryStrategy", objectMapper.valueToTree(job.getRetryStrategy()));
        }
        if (job.getTimeout() != null) {
            detail.set("timeout", objectMapper.valueToTree(job.getTimeout()));
        }
        if (job.getTags() != null && !job.getTags().isEmpty()) {
            detail.set("tags", objectMapper.valueToTree(job.getTags()));
        }

        ObjectNode container = detail.putObject("container");
        if (job.getContainerImage() != null) {
            container.put("image", job.getContainerImage());
        }
        container.set("command", objectMapper.valueToTree(job.getResolvedCommand()));
        container.set("environment", objectMapper.valueToTree(job.getResolvedEnvironment()));
        if (job.getContainer() != null) {
            if (job.getContainer().getExitCode() != null) {
                container.put("exitCode", job.getContainer().getExitCode());
            }
            if (job.getContainer().getReason() != null) {
                container.put("reason", job.getContainer().getReason());
            }
            if (job.getContainer().getLogStreamName() != null) {
                container.put("logStreamName", job.getContainer().getLogStreamName());
            }
        }
        return detail;
    }

    private List<BatchComputeEnvironmentOrder> computeEnvironmentOrder(JsonNode node) {
        if (node == null || !node.isArray()) {
            return List.of();
        }
        List<BatchComputeEnvironmentOrder> orders = new ArrayList<>();
        for (JsonNode item : node) {
            BatchComputeEnvironmentOrder order = new BatchComputeEnvironmentOrder();
            order.setOrder(item.path("order").asInt());
            order.setComputeEnvironment(item.path("computeEnvironment").asText(null));
            orders.add(order);
        }
        return orders;
    }

    private int parseNextToken(String token) {
        if (token == null || token.isBlank()) {
            return 0;
        }
        try {
            int value = Integer.parseInt(token);
            if (value < 0) {
                throw client("Invalid nextToken");
            }
            return value;
        } catch (NumberFormatException e) {
            throw client("Invalid nextToken");
        }
    }

    private ListFilter listFilter(JsonNode request) {
        JsonNode filters = request.path("filters");
        if (!filters.isArray() || filters.isEmpty()) {
            return null;
        }
        if (filters.size() > 1) {
            throw client("Only one filter can be used at a time");
        }
        JsonNode filter = filters.get(0);
        String name = textOrNull(filter, "name");
        if (name == null || !SUPPORTED_LIST_FILTERS.contains(name)) {
            throw client("Unsupported ListJobs filter: " + name);
        }
        return new ListFilter(name, stringList(filter.path("values")));
    }

    private boolean matchesListFilter(BatchJob job, ListFilter filter) {
        if (filter == null || filter.values().isEmpty()) {
            return true;
        }
        return switch (filter.name()) {
            case "JOB_NAME" -> filter.values().stream().anyMatch(value -> matchesJobNameFilter(job, value));
            case "JOB_DEFINITION" -> filter.values().stream().anyMatch(value -> matchesJobDefinitionFilter(job, value));
            case "BEFORE_CREATED_AT" -> filter.values().stream()
                    .anyMatch(value -> job.getCreatedAt() < parseLong(value, "Invalid BEFORE_CREATED_AT filter value"));
            case "AFTER_CREATED_AT" -> filter.values().stream()
                    .anyMatch(value -> job.getCreatedAt() > parseLong(value, "Invalid AFTER_CREATED_AT filter value"));
            case "SHARE_IDENTIFIER" -> false;
            default -> true;
        };
    }

    private boolean matchesJobNameFilter(BatchJob job, String value) {
        if (job.getJobName() == null || value == null) {
            return false;
        }
        String expected = value.endsWith("*") ? value.substring(0, value.length() - 1) : value;
        if (value.endsWith("*")) {
            return job.getJobName().regionMatches(true, 0, expected, 0, expected.length());
        }
        return job.getJobName().equalsIgnoreCase(expected);
    }

    private boolean matchesJobDefinitionFilter(BatchJob job, String value) {
        if (value == null || value.isBlank()) {
            return false;
        }
        if (value.startsWith("arn:")) {
            return value.equals(job.getJobDefinition());
        }
        if (value.endsWith("*")) {
            return job.getJobDefinitionName() != null
                    && job.getJobDefinitionName().startsWith(value.substring(0, value.length() - 1));
        }
        int colon = value.lastIndexOf(':');
        if (colon > 0) {
            String name = value.substring(0, colon);
            try {
                int revision = Integer.parseInt(value.substring(colon + 1));
                return name.equals(job.getJobDefinitionName()) && revision == job.getJobDefinitionRevision();
            } catch (NumberFormatException e) {
                return false;
            }
        }
        return value.equals(job.getJobDefinitionName());
    }

    private long parseLong(String value, String message) {
        try {
            return Long.parseLong(value);
        } catch (NumberFormatException e) {
            throw client(message);
        }
    }

    private int describeMaxResults(JsonNode request) {
        if (!request.hasNonNull("maxResults")) {
            return DEFAULT_MAX_RESULTS;
        }
        return Math.max(1, Math.min(DESCRIBE_JOBS_LIMIT, request.path("maxResults").asInt()));
    }

    private Optional<BatchJob> getJob(String jobId) {
        return jobStore.get(jobId);
    }

    @SuppressWarnings("unchecked")
    private Optional<BatchJob> getJobForAccount(String accountId, String jobId) {
        if (jobStore instanceof AccountAwareStorageBackend<?> aware) {
            return ((AccountAwareStorageBackend<BatchJob>) aware).getForAccount(accountId, jobId);
        }
        return jobStore.get(jobId);
    }

    private void putJob(BatchJob job) {
        jobStore.put(job.getJobId(), job);
    }

    @SuppressWarnings("unchecked")
    private void putJobForAccount(String accountId, BatchJob job) {
        if (jobStore instanceof AccountAwareStorageBackend<?> aware) {
            ((AccountAwareStorageBackend<BatchJob>) aware).putForAccount(accountId, job.getJobId(), job);
        } else {
            jobStore.put(job.getJobId(), job);
        }
    }

    private void putJobDefinition(BatchJobDefinition def) {
        jobDefinitionStore.put(def.getJobDefinitionArn(), def);
    }

    private String requiredText(JsonNode node, String field) {
        String value = textOrNull(node, field);
        if (value == null || value.isBlank()) {
            throw client(field + " is required");
        }
        return value;
    }

    private String text(JsonNode node, String field, String defaultValue) {
        String value = textOrNull(node, field);
        return value != null ? value : defaultValue;
    }

    private String textOrNull(JsonNode node, String field) {
        return node != null && node.hasNonNull(field) ? node.path(field).asText() : null;
    }

    private List<String> stringList(JsonNode node) {
        if (node == null || !node.isArray()) {
            return List.of();
        }
        List<String> out = new ArrayList<>();
        node.forEach(v -> out.add(v.asText()));
        return out;
    }

    private Map<String, String> stringMap(JsonNode node) {
        Map<String, String> out = new LinkedHashMap<>();
        if (node != null && node.isObject()) {
            node.fields().forEachRemaining(e -> out.put(e.getKey(), e.getValue().asText()));
        }
        return out;
    }

    private Map<String, Object> map(JsonNode node) {
        if (node == null || !node.isObject()) {
            return new HashMap<>();
        }
        return objectMapper.convertValue(node, new TypeReference<Map<String, Object>>() {});
    }

    private <T> T tree(JsonNode node, Class<T> type) {
        if (node == null || node.isMissingNode() || node.isNull()) {
            return null;
        }
        return objectMapper.convertValue(node, type);
    }

    private <T> T treeOrDefault(JsonNode node, T defaultValue, Class<T> type) {
        T value = tree(node, type);
        return value != null ? value : defaultValue;
    }

    private String arn(String region, String resource) {
        return AwsArnUtils.Arn.of("batch", region, regionResolver.getAccountId(), resource).toString();
    }

    private long now() {
        return System.currentTimeMillis();
    }

    private AwsException client(String message) {
        return new AwsException("ClientException", message, 400);
    }

    private record ListFilter(String name, List<String> values) {
    }
}
