package io.github.hectorvent.floci.services.codepipeline;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.hectorvent.floci.config.EmulatorConfig;
import io.github.hectorvent.floci.core.common.AwsArnUtils;
import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.core.common.DeadlineCharSequence;
import io.github.hectorvent.floci.core.storage.AccountAwareStorageBackend;
import io.github.hectorvent.floci.core.storage.StorageBackend;
import io.github.hectorvent.floci.core.storage.StorageFactory;
import io.github.hectorvent.floci.services.codebuild.CodeBuildService;
import io.github.hectorvent.floci.services.codebuild.model.Build;
import io.github.hectorvent.floci.services.codedeploy.CodeDeployService;
import io.github.hectorvent.floci.services.codedeploy.model.Deployment;
import io.github.hectorvent.floci.services.codepipeline.model.CodePipelineExecution;
import io.github.hectorvent.floci.services.codepipeline.model.CodePipelineExecution.ActionExecution;
import io.github.hectorvent.floci.services.codepipeline.model.CodePipelinePipeline;
import io.github.hectorvent.floci.services.codepipeline.model.CodePipelinePipeline.TransitionState;
import io.github.hectorvent.floci.services.codepipeline.model.CodePipelineStoredItem;
import io.github.hectorvent.floci.services.lambda.LambdaService;
import io.github.hectorvent.floci.services.lambda.model.InvocationType;
import io.github.hectorvent.floci.services.lambda.model.InvokeResult;
import io.github.hectorvent.floci.services.s3.S3Service;
import io.github.hectorvent.floci.services.s3.model.S3Object;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.apache.commons.compress.archivers.zip.ZipArchiveEntry;
import org.apache.commons.compress.archivers.zip.ZipArchiveOutputStream;
import org.apache.commons.compress.archivers.zip.ZipFile;
import org.apache.commons.compress.utils.SeekableInMemoryByteChannel;
import org.jboss.logging.Logger;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Enumeration;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;
import java.util.zip.CRC32;
import java.util.zip.Inflater;
import java.util.zip.InflaterInputStream;
import java.util.zip.ZipEntry;

@ApplicationScoped
public class CodePipelineService {
    private static final Logger LOG = Logger.getLogger(CodePipelineService.class);
    private static final String DEFAULT_EXECUTION_MODE = "SUPERSEDED";
    private static final String DEFAULT_PIPELINE_TYPE = "V1";
    private static final int MAX_ACTIVE_EXECUTIONS = 50;
    private static final long POLL_INTERVAL_MS = 100L;
    private static final String SOURCE_POLL_TYPE = "source-poll";
    private static final String MISSING_SOURCE_REVISION = "missing";
    private static final long MAX_ARCHIVE_BYTES = 128L << 20;
    private static final long MAX_UNCOMPRESSED_BYTES = 128L << 20;
    private static final int MAX_ARCHIVE_ENTRIES = 100_000;

    // Overridable accessors so tests can use tiny caps and a local server instead of 1 GiB archives.
    protected long maxArchiveBytes() {
        return MAX_ARCHIVE_BYTES;
    }

    protected long maxUncompressedBytes() {
        return MAX_UNCOMPRESSED_BYTES;
    }

    protected int maxEntries() {
        return MAX_ARCHIVE_ENTRIES;
    }

    protected String githubArchiveBaseUrl() {
        return "https://codeload.github.com";
    }

    private final AccountAwareStorageBackend<CodePipelinePipeline> pipelineStore;
    private final AccountAwareStorageBackend<CodePipelineExecution> executionStore;
    private final AccountAwareStorageBackend<CodePipelineStoredItem> itemStore;
    private final ObjectMapper mapper;
    private final CodeBuildService codeBuildService;
    private final CodeDeployService codeDeployService;
    private final LambdaService lambdaService;
    private final S3Service s3Service;
    private final CodePipelineEventPublisher eventPublisher;
    private final ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();
    private final ScheduledExecutorService sourcePoller = Executors.newSingleThreadScheduledExecutor();
    private final KeyedLockPool pipelineLocks = new KeyedLockPool();
    private final KeyedLockPool sourcePollLocks = new KeyedLockPool();
    private static final String MANUAL_TRIGGER_DETAIL = "manual";
    // Admission is serialized per pipeline on its own lock. A QUEUED worker holds the pipelineLocks
    // monitor for its whole run, so counting under that monitor would block StartPipelineExecution
    // until the running execution finished.
    private final KeyedLockPool startLocks = new KeyedLockPool();
    private final Map<String, byte[]> runtimeArtifacts = new ConcurrentHashMap<>();
    // An execution's status turns Failed as soon as one action fails, while its runner is still waiting
    // on sibling actions. Retries check this set so they never overlap a runner that has not finished.
    final Set<String> activeRuns = ConcurrentHashMap.newKeySet();
    private final long sourcePollIntervalMs;
    // Test hooks, set only through the package-private constructor: a shortened wait, and a probe
    // for the gap before the override timeout cleanup.
    private final long runFinishWaitNanos;
    private final Runnable beforeOverrideTimeoutCleanup;

    /** Internal override type: a rollback pins a GitHub source action to the target's commit. */
    private static final String GITHUB_COMMIT_ID = "GITHUB_COMMIT_ID";

    private static final long DEFAULT_RUN_FINISH_WAIT_NANOS = TimeUnit.SECONDS.toNanos(5);

    /** Matches the {@code source-poll-interval-ms} default in application.yml. */
    private static final long DEFAULT_SOURCE_POLL_INTERVAL_MS = 500L;

    /**
     * Package-private constructor for the tests that build the service without CDI. The source
     * poll interval falls back to the configured default.
     */
    CodePipelineService(StorageFactory storageFactory, ObjectMapper mapper,
                        CodeBuildService codeBuildService, CodeDeployService codeDeployService,
                        LambdaService lambdaService, S3Service s3Service) {
        this(storageFactory, mapper, codeBuildService, codeDeployService, lambdaService, s3Service,
                detachedEventPublisher(mapper), DEFAULT_SOURCE_POLL_INTERVAL_MS,
                DEFAULT_RUN_FINISH_WAIT_NANOS, () -> { });
    }

    public CodePipelineService(StorageFactory storageFactory, ObjectMapper mapper,
                               CodeBuildService codeBuildService, CodeDeployService codeDeployService,
                               LambdaService lambdaService, S3Service s3Service,
                               EmulatorConfig config) {
        this(storageFactory, mapper, codeBuildService, codeDeployService, lambdaService, s3Service,
                config, detachedEventPublisher(mapper));
    }

    @Inject
    public CodePipelineService(StorageFactory storageFactory, ObjectMapper mapper,
                               CodeBuildService codeBuildService, CodeDeployService codeDeployService,
                               LambdaService lambdaService, S3Service s3Service,
                               EmulatorConfig config, CodePipelineEventPublisher eventPublisher) {
        this(storageFactory, mapper, codeBuildService, codeDeployService, lambdaService, s3Service,
                eventPublisher,
                resolveSourcePollInterval(config.services().codepipeline().sourcePollIntervalMs()),
                DEFAULT_RUN_FINISH_WAIT_NANOS, () -> { });
    }

    /**
     * A publisher with no EventBridge or SNS behind it, for the constructors built without CDI.
     * Publishing is best-effort, so each event is logged as undeliverable and dropped.
     */
    private static CodePipelineEventPublisher detachedEventPublisher(ObjectMapper mapper) {
        return new CodePipelineEventPublisher(null, null, mapper);
    }

    /**
     * A zero or negative interval would make {@code scheduleWithFixedDelay} throw from the startup
     * observer and stop the whole emulator becoming ready. A misconfigured value is not worth
     * failing every other service for, so it falls back with a warning.
     */
    private static long resolveSourcePollInterval(long configured) {
        if (configured > 0) {
            return configured;
        }
        LOG.warnv("Ignoring CodePipeline source poll interval {0}ms: must be a positive number of "
                + "milliseconds, falling back to {1}ms", configured, DEFAULT_SOURCE_POLL_INTERVAL_MS);
        return DEFAULT_SOURCE_POLL_INTERVAL_MS;
    }

    @SuppressWarnings("unchecked")
    CodePipelineService(StorageFactory storageFactory, ObjectMapper mapper,
                        CodeBuildService codeBuildService, CodeDeployService codeDeployService,
                        LambdaService lambdaService, S3Service s3Service,
                        CodePipelineEventPublisher eventPublisher, long sourcePollIntervalMs,
                        long runFinishWaitNanos, Runnable beforeOverrideTimeoutCleanup) {
        this.pipelineStore = storageFactory.create(
                "codepipeline", "codepipeline-pipelines.json", new TypeReference<Map<String, CodePipelinePipeline>>() {});
        this.executionStore = storageFactory.create(
                "codepipeline", "codepipeline-executions.json", new TypeReference<Map<String, CodePipelineExecution>>() {});
        this.itemStore = storageFactory.create(
                "codepipeline", "codepipeline-items.json", new TypeReference<Map<String, CodePipelineStoredItem>>() {});
        this.mapper = mapper;
        this.codeBuildService = codeBuildService;
        this.codeDeployService = codeDeployService;
        this.lambdaService = lambdaService;
        this.s3Service = s3Service;
        this.eventPublisher = eventPublisher;
        this.sourcePollIntervalMs = sourcePollIntervalMs;
        this.runFinishWaitNanos = runFinishWaitNanos;
        this.beforeOverrideTimeoutCleanup = beforeOverrideTimeoutCleanup;
    }

    public JsonNode handle(String action, JsonNode request, String region, String account) {
        return switch (action) {
            case "CreatePipeline" -> createPipeline(request, region, account);
            case "UpdatePipeline" -> updatePipeline(request, region, account);
            case "GetPipeline" -> getPipelineResponse(request, region, account);
            case "DeletePipeline" -> deletePipeline(request, region, account);
            case "ListPipelines" -> listPipelines(request, region, account);
            case "GetPipelineState" -> getPipelineState(request, region, account);
            case "StartPipelineExecution" -> startPipelineExecution(request, region, account);
            case "StopPipelineExecution" -> stopPipelineExecution(request, region, account);
            case "GetPipelineExecution" -> getPipelineExecutionResponse(request, region, account);
            case "ListPipelineExecutions" -> listPipelineExecutions(request, region, account);
            case "ListActionExecutions" -> listActionExecutions(request, region, account);
            case "ListRuleExecutions" -> listRuleExecutions(request, region, account);
            case "ListDeployActionExecutionTargets" -> emptyPage("targets");
            case "DisableStageTransition" -> setStageTransition(request, region, account, false);
            case "EnableStageTransition" -> setStageTransition(request, region, account, true);
            case "PutApprovalResult" -> putApprovalResult(request, region, account);
            case "RetryStageExecution" -> retryStageExecution(request, region, account);
            case "RollbackStage" -> rollbackStage(request, region, account);
            case "OverrideStageCondition" -> overrideStageCondition(request, region, account);
            case "CreateCustomActionType" -> createCustomActionType(request, region, account);
            case "UpdateActionType" -> updateActionType(request, region, account);
            case "GetActionType" -> getActionType(request, region, account);
            case "DeleteCustomActionType" -> deleteCustomActionType(request, region, account);
            case "ListActionTypes" -> listActionTypes(request, region, account);
            case "ListRuleTypes" -> listRuleTypes();
            case "PollForJobs" -> pollForJobs(request, region, account, false);
            case "PollForThirdPartyJobs" -> pollForJobs(request, region, account, true);
            case "AcknowledgeJob", "AcknowledgeThirdPartyJob" -> acknowledgeJob(request, region, account);
            case "GetJobDetails", "GetThirdPartyJobDetails" -> getJobDetails(request, region, account);
            case "PutJobSuccessResult", "PutThirdPartyJobSuccessResult" ->
                    completeJob(request, region, account, true);
            case "PutJobFailureResult", "PutThirdPartyJobFailureResult" ->
                    completeJob(request, region, account, false);
            case "PutActionRevision" -> putActionRevision(request);
            case "PutWebhook" -> putWebhook(request, region, account);
            case "DeleteWebhook" -> deleteWebhook(request, region, account);
            case "ListWebhooks" -> listWebhooks(request, region, account);
            case "RegisterWebhookWithThirdParty" -> setWebhookRegistration(request, region, account, true);
            case "DeregisterWebhookWithThirdParty" -> setWebhookRegistration(request, region, account, false);
            case "TagResource" -> tagResource(request, region, account);
            case "UntagResource" -> untagResource(request, region, account);
            case "ListTagsForResource" -> listTagsForResource(request, region, account);
            default -> throw new AwsException("InvalidAction", "Action " + action + " is not supported", 400);
        };
    }

    @PostConstruct
    void resumePersistedExecutions() {
        for (CodePipelineExecution execution : executionStore.scanAllAccounts()) {
            if (!"InProgress".equals(execution.getStatus()) && !"Stopping".equals(execution.getStatus())) {
                continue;
            }
            pipelineStore.getForAccount(
                    execution.getAccountId(), pipelineKey(execution.getRegion(), execution.getPipelineName()))
                    .ifPresentOrElse(pipeline -> {
                        execution.setStatus("InProgress");
                        execution.setStatusSummary("Pipeline execution resumed after restart.");
                        execution.setStopRequested(false);
                        execution.setAbandon(false);
                        execution.setActionExecutions(new ArrayList<>());
                        execution.setStageExecutionStatuses(new LinkedHashMap<>());
                        putExecution(execution);
                        eventPublisher.pipelineStateChange(execution, "RESUMED");
                        executor.submit(() -> runExecution(pipeline, execution));
                    }, () -> {
                        execution.setStatus("Failed");
                        execution.setStatusSummary("Pipeline definition was not found after restart.");
                        execution.setLastUpdateTime(now());
                        putExecution(execution);
                    });
        }
        initializePersistedSourcePollingBaselines();
        sourcePoller.scheduleWithFixedDelay(
                this::pollS3SourcesSafely, sourcePollIntervalMs, sourcePollIntervalMs, TimeUnit.MILLISECONDS);
    }

    private void initializePersistedSourcePollingBaselines() {
        for (CodePipelinePipeline pipeline : pipelineStore.scanAllAccounts()) {
            ensureSourcePollingBaselines(pipeline);
        }
    }

    private void resetSourcePollingBaselines(CodePipelinePipeline pipeline) {
        deleteSourcePollingBaselines(pipeline.getAccountId(), pipeline.getRegion(), pipeline.getName());
        ensureSourcePollingBaselines(pipeline);
    }

    private void ensureSourcePollingBaselines(CodePipelinePipeline pipeline) {
        forEachPolledS3Source(pipeline, (stageName, action) -> {
            String cursorId = sourcePollCursorId(pipeline.getName(), stageName, action.path("name").asText());
            String key = itemKey(pipeline.getRegion(), SOURCE_POLL_TYPE, cursorId);
            if (itemStore.getForAccount(pipeline.getAccountId(), key).isPresent()) {
                return;
            }
            String revision = observeS3SourceRevision(action);
            storeSourcePollCursor(pipeline, cursorId, revision);
        });
    }

    private void deleteSourcePollingBaselines(String account, String region, String pipelineName) {
        String prefix = itemKey(region, SOURCE_POLL_TYPE, pipelineName + "::");
        for (String key : itemStore.keysForAccount(account)) {
            if (key.startsWith(prefix)) {
                itemStore.deleteForAccount(account, key);
            }
        }
    }

    private void pollS3SourcesSafely() {
        try {
            for (CodePipelinePipeline pipeline : pipelineStore.scanAllAccounts()) {
                pollS3Sources(pipeline);
            }
        } catch (RuntimeException e) {
            LOG.warn("CodePipeline S3 source polling cycle failed", e);
        }
    }

    private void pollS3Sources(CodePipelinePipeline pipeline) {
        String pollLockKey = pipelineLockKey(
                pipeline.getAccountId(), pipeline.getRegion(), pipeline.getName());
        sourcePollLocks.withLock(pollLockKey, () -> {
            Optional<CodePipelinePipeline> currentPipeline = pipelineStore.getForAccount(
                    pipeline.getAccountId(), pipelineKey(pipeline.getRegion(), pipeline.getName()));
            if (currentPipeline.isEmpty()) {
                return;
            }
            pollS3SourcesLocked(currentPipeline.get());
        });
    }

    private void pollS3SourcesLocked(CodePipelinePipeline pipeline) {
        forEachPolledS3Source(pipeline, (stageName, action) -> {
            String actionName = action.path("name").asText();
            String cursorId = sourcePollCursorId(pipeline.getName(), stageName, actionName);
            String key = itemKey(pipeline.getRegion(), SOURCE_POLL_TYPE, cursorId);
            Optional<CodePipelineStoredItem> existing = itemStore.getForAccount(pipeline.getAccountId(), key);
            if (existing.isEmpty()) {
                storeSourcePollCursor(pipeline, cursorId, observeS3SourceRevision(action));
                return;
            }

            String previous = existing.get().getData().path("revision").asText(MISSING_SOURCE_REVISION);
            String current;
            try {
                current = observeS3SourceRevision(action);
            } catch (RuntimeException e) {
                LOG.debugf(e, "Unable to poll CodePipeline S3 source %s/%s", pipeline.getName(), actionName);
                return;
            }
            if (Objects.equals(previous, current)) {
                return;
            }

            if (MISSING_SOURCE_REVISION.equals(current)) {
                updateSourcePollCursor(existing.get(), current);
                return;
            }
            JsonNode config = action.path("configuration");
            String bucket = config.path("S3Bucket").asText(config.path("BucketName").asText(null));
            String objectKey = config.path("S3ObjectKey").asText(config.path("ObjectKey").asText(null));
            ObjectNode request = mapper.createObjectNode().put("name", pipeline.getName());
            startPipelineExecution(request, pipeline.getRegion(), pipeline.getAccountId(),
                    "PollForSourceChanges", "s3://" + bucket + "/" + objectKey);
            updateSourcePollCursor(existing.get(), current);
        });
    }

    private void forEachPolledS3Source(CodePipelinePipeline pipeline, S3SourceConsumer consumer) {
        for (JsonNode stage : pipeline.getDeclaration().path("stages")) {
            String stageName = stage.path("name").asText();
            for (JsonNode action : stage.path("actions")) {
                JsonNode type = action.path("actionTypeId");
                if (!"Source".equals(type.path("category").asText())
                        || !"AWS".equals(type.path("owner").asText())
                        || !"S3".equals(type.path("provider").asText())) {
                    continue;
                }
                JsonNode config = action.path("configuration");
                String bucket = config.path("S3Bucket").asText(config.path("BucketName").asText(null));
                String objectKey = config.path("S3ObjectKey").asText(config.path("ObjectKey").asText(null));
                if (bucket == null || bucket.isBlank() || objectKey == null || objectKey.isBlank()) {
                    continue;
                }
                if (!Boolean.parseBoolean(config.path("PollForSourceChanges").asText("true"))) {
                    continue;
                }
                consumer.accept(stageName, action);
            }
        }
    }

    private String observeS3SourceRevision(JsonNode action) {
        JsonNode config = action.path("configuration");
        String bucket = config.path("S3Bucket").asText(config.path("BucketName").asText(null));
        String key = config.path("S3ObjectKey").asText(config.path("ObjectKey").asText(null));
        try {
            S3Object object = s3Service.headObject(bucket, key);
            return object.getVersionId() != null
                    ? "version:" + object.getVersionId() : "etag:" + unquoteETag(object.getETag());
        } catch (AwsException e) {
            if ("NoSuchBucket".equals(e.getErrorCode()) || "NoSuchKey".equals(e.getErrorCode())) {
                return MISSING_SOURCE_REVISION;
            }
            throw e;
        }
    }

    private void storeSourcePollCursor(CodePipelinePipeline pipeline, String cursorId, String revision) {
        ObjectNode data = mapper.createObjectNode().put("revision", revision);
        storeItem(pipeline.getAccountId(), pipeline.getRegion(), SOURCE_POLL_TYPE, cursorId, "ACTIVE", data);
    }

    private void updateSourcePollCursor(CodePipelineStoredItem item, String revision) {
        item.setData(mapper.createObjectNode().put("revision", revision));
        item.setUpdated(now());
        putItem(item);
    }

    private static String sourcePollCursorId(String pipelineName, String stageName, String actionName) {
        return pipelineName + "::" + stageName + "::" + actionName;
    }

    private static String unquoteETag(String eTag) {
        return eTag == null ? null : eTag.replace("\"", "");
    }

    @FunctionalInterface
    private interface S3SourceConsumer {
        void accept(String stageName, JsonNode action);
    }

    private ObjectNode createPipeline(JsonNode request, String region, String account) {
        JsonNode declaration = request.get("pipeline");
        String name = validatePipelineDeclaration(declaration);
        if (pipelineStore.getForAccount(account, pipelineKey(region, name)).isPresent()) {
            throw new AwsException("PipelineNameInUseException", "Pipeline name already exists: " + name, 400);
        }
        double now = now();
        CodePipelinePipeline pipeline = new CodePipelinePipeline();
        pipeline.setAccountId(account);
        pipeline.setRegion(region);
        pipeline.setName(name);
        pipeline.setArn(AwsArnUtils.Arn.of("codepipeline", region, account, name).toString());
        pipeline.setVersion(1);
        pipeline.setCreated(now);
        pipeline.setUpdated(now);
        pipeline.setDeclaration(normalizeDeclaration(declaration, 1));
        pipeline.setTags(parseTags(request.path("tags")));
        initializeTransitions(pipeline);
        String pollLockKey = pipelineLockKey(account, region, name);
        sourcePollLocks.withLock(pollLockKey, () -> {
            putPipeline(pipeline);
            resetSourcePollingBaselines(pipeline);
        });
        ObjectNode response = mapper.createObjectNode();
        response.set("pipeline", pipeline.getDeclaration());
        if (!pipeline.getTags().isEmpty()) {
            response.set("tags", tagsNode(pipeline.getTags()));
        }
        return response;
    }

    private ObjectNode updatePipeline(JsonNode request, String region, String account) {
        JsonNode declaration = request.get("pipeline");
        String name = validatePipelineDeclaration(declaration);
        CodePipelinePipeline pipeline = requirePipeline(account, region, name);
        int version = pipeline.getVersion() == null ? 1 : pipeline.getVersion() + 1;
        pipeline.setVersion(version);
        pipeline.setUpdated(now());
        pipeline.setDeclaration(normalizeDeclaration(declaration, version));
        initializeTransitions(pipeline);
        String pollLockKey = pipelineLockKey(account, region, name);
        sourcePollLocks.withLock(pollLockKey, () -> {
            putPipeline(pipeline);
            resetSourcePollingBaselines(pipeline);
        });
        ObjectNode response = mapper.createObjectNode();
        response.set("pipeline", pipeline.getDeclaration());
        return response;
    }

    private ObjectNode getPipelineResponse(JsonNode request, String region, String account) {
        CodePipelinePipeline pipeline = requirePipeline(account, region, text(request, "name"));
        int currentVersion = pipeline.getVersion() == null ? 1 : pipeline.getVersion();
        int version = request.path("version").asInt(currentVersion);
        if (version != currentVersion) {
            throw new AwsException("PipelineVersionNotFoundException",
                    "Pipeline version not found: " + version, 400);
        }
        ObjectNode response = mapper.createObjectNode();
        response.set("pipeline", pipeline.getDeclaration());
        ObjectNode metadata = response.putObject("metadata");
        metadata.put("pipelineArn", pipeline.getArn());
        metadata.put("created", pipeline.getCreated());
        metadata.put("updated", pipeline.getUpdated());
        return response;
    }

    private ObjectNode deletePipeline(JsonNode request, String region, String account) {
        String name = text(request, "name");
        validatePipelineName(name);
        String pollLockKey = pipelineLockKey(account, region, name);
        sourcePollLocks.withLock(pollLockKey, () -> {
            pipelineStore.deleteForAccount(account, pipelineKey(region, name));
            for (String key : executionStore.keysForAccount(account)) {
                if (key.startsWith(region + ":" + name + ":")) {
                    executionStore.getForAccount(account, key).ifPresent(this::clearRuntimeArtifacts);
                    executionStore.deleteForAccount(account, key);
                }
            }
            deleteSourcePollingBaselines(account, region, name);
        });
        return mapper.createObjectNode();
    }

    private ObjectNode listPipelines(JsonNode request, String region, String account) {
        List<CodePipelinePipeline> pipelines = pipelineStore.scanForAccount(
                account, key -> key.startsWith(region + ":")).stream()
                .sorted(Comparator.comparing(CodePipelinePipeline::getName))
                .toList();
        Page page = page(request, pipelines.size(), 1000);
        ObjectNode response = mapper.createObjectNode();
        ArrayNode summaries = response.putArray("pipelines");
        for (CodePipelinePipeline pipeline : pipelines.subList(page.start(), page.end())) {
            ObjectNode summary = summaries.addObject();
            summary.put("name", pipeline.getName());
            summary.put("version", pipeline.getVersion());
            summary.put("created", pipeline.getCreated());
            summary.put("updated", pipeline.getUpdated());
            summary.put("executionMode", pipeline.getDeclaration().path("executionMode").asText(DEFAULT_EXECUTION_MODE));
            summary.put("pipelineType", pipeline.getDeclaration().path("pipelineType").asText(DEFAULT_PIPELINE_TYPE));
        }
        addNextToken(response, page, pipelines.size());
        return response;
    }

    private ObjectNode startPipelineExecution(JsonNode request, String region, String account) {
        return startPipelineExecution(request, region, account, "StartPipelineExecution", MANUAL_TRIGGER_DETAIL);
    }

    private ObjectNode startPipelineExecution(JsonNode request, String region, String account,
                                              String triggerType, String triggerDetail) {
        CodePipelinePipeline pipeline = requirePipeline(account, region, text(request, "name"));
        String clientToken = request.path("clientRequestToken").asText(null);
        if (clientToken != null) {
            Optional<CodePipelineExecution> existing = executions(account, region, pipeline.getName()).stream()
                    .filter(e -> clientToken.equals(e.getTrigger().get("clientRequestToken")))
                    .findFirst();
            if (existing.isPresent()) {
                return mapper.createObjectNode().put("pipelineExecutionId", existing.get().getPipelineExecutionId());
            }
        }

        CodePipelineExecution execution = new CodePipelineExecution();
        execution.setAccountId(account);
        execution.setRegion(region);
        execution.setPipelineExecutionId(UUID.randomUUID().toString());
        execution.setPipelineName(pipeline.getName());
        execution.setPipelineVersion(pipeline.getVersion());
        execution.setExecutionMode(pipeline.getDeclaration().path("executionMode").asText(DEFAULT_EXECUTION_MODE));
        execution.setExecutionType("STANDARD");
        execution.setStatus("InProgress");
        execution.setStatusSummary("Pipeline execution started.");
        execution.setStartTime(now());
        execution.setLastUpdateTime(execution.getStartTime());
        execution.setSourceRevisions(new ArrayList<>());
        execution.setSourceRevisionOverrides(sourceRevisionOverrides(request, pipeline));
        execution.setVariables(variableList(request.path("variables"), pipeline.getDeclaration().path("variables")));
        Map<String, String> trigger = new LinkedHashMap<>();
        trigger.put("triggerType", triggerType);
        trigger.put("triggerDetail", triggerDetail);
        if (clientToken != null) {
            trigger.put("clientRequestToken", clientToken);
        }
        execution.setTrigger(trigger);
        if (!persistExecutionIfSlotAvailable(execution)) {
            throw new AwsException("ConcurrentPipelineExecutionsLimitExceededException",
                    "The pipeline has reached the limit for concurrent pipeline executions", 400);
        }
        applyExecutionMode(execution);
        eventPublisher.pipelineStateChange(execution, "STARTED");
        try {
            executor.submit(() -> runExecution(pipeline, execution));
        } catch (RejectedExecutionException exception) {
            execution.setStatus("Failed");
            execution.setStatusSummary("Pipeline execution could not be scheduled.");
            execution.setLastUpdateTime(now());
            putExecution(execution);
            eventPublisher.pipelineStateChange(execution, "FAILED");
            throw pipelineBusy();
        }
        return mapper.createObjectNode().put("pipelineExecutionId", execution.getPipelineExecutionId());
    }

    private boolean persistExecutionIfSlotAvailable(CodePipelineExecution execution) {
        if (!List.of("QUEUED", "PARALLEL").contains(execution.getExecutionMode())) {
            putExecution(execution);
            return true;
        }
        return startLocks.withLock(lockKey(execution), () -> {
            if (activeExecutionCount(execution) >= MAX_ACTIVE_EXECUTIONS) {
                return false;
            }
            putExecution(execution);
            return true;
        });
    }

    private long activeExecutionCount(CodePipelineExecution execution) {
        return executions(execution.getAccountId(), execution.getRegion(), execution.getPipelineName()).stream()
                .filter(candidate -> "InProgress".equals(candidate.getStatus())
                        || "Stopping".equals(candidate.getStatus()))
                .count();
    }

    private ObjectNode stopPipelineExecution(JsonNode request, String region, String account) {
        CodePipelineExecution execution = requireExecution(
                account, region, text(request, "pipelineName"), text(request, "pipelineExecutionId"));
        if (isTerminal(execution.getStatus())) {
            throw new AwsException("PipelineExecutionNotStoppableException",
                    "Pipeline execution is already in a terminal state", 400);
        }
        // Publish the stop mode before the stop signal. The provider polling loop reads
        // stopRequested first, so observing it also observes the matching abandon value.
        execution.setAbandon(request.path("abandon").asBoolean(false));
        execution.setStopRequested(true);
        execution.setStatus("Stopping");
        execution.setStatusSummary(request.path("reason").asText("Stop requested."));
        execution.setLastUpdateTime(now());
        if (execution.getCurrentStage() != null) {
            execution.getStageExecutionStatuses().put(execution.getCurrentStage(), "Stopping");
        }
        if (execution.isAbandon()) {
            execution.getActionExecutions().stream()
                    .filter(a -> "InProgress".equals(a.getStatus()))
                    .forEach(a -> {
                        a.setStatus("Abandoned");
                        a.setLastUpdateTime(now());
                    });
        }
        putExecution(execution);
        eventPublisher.pipelineStateChange(execution, "STOPPING");
        return mapper.createObjectNode().put("pipelineExecutionId", execution.getPipelineExecutionId());
    }

    private ObjectNode getPipelineExecutionResponse(JsonNode request, String region, String account) {
        CodePipelineExecution execution = requireExecution(
                account, region, text(request, "pipelineName"), text(request, "pipelineExecutionId"));
        ObjectNode response = mapper.createObjectNode();
        response.set("pipelineExecution", executionNode(execution));
        return response;
    }

    private ObjectNode listPipelineExecutions(JsonNode request, String region, String account) {
        String pipelineName = text(request, "pipelineName");
        requirePipeline(account, region, pipelineName);
        List<CodePipelineExecution> executions = executions(account, region, pipelineName);
        JsonNode filter = request.get("filter");
        if (filter != null && !filter.isNull() && !filter.isObject()) {
            throw new AwsException("ValidationException", "filter must be an object", 400);
        }
        if (filter != null && filter.isObject()) {
            requireOnlyMembers(filter, "filter", Set.of("succeededInStage"));
        }
        JsonNode succeededInStage = filter == null ? null : filter.get("succeededInStage");
        if (succeededInStage != null && !succeededInStage.isNull()) {
            if (!succeededInStage.isObject()) {
                throw new AwsException("ValidationException",
                        "succeededInStage requires stageName", 400);
            }
            requireOnlyMembers(succeededInStage, "succeededInStage", Set.of("stageName"));
            JsonNode stageNameNode = succeededInStage.get("stageName");
            if (stageNameNode == null || stageNameNode.isNull() || !stageNameNode.isTextual()
                    || stageNameNode.asText().isBlank()) {
                throw new AwsException("ValidationException",
                        "succeededInStage requires stageName", 400);
            }
            String stage = stageNameNode.asText();
            executions = executions.stream()
                    .filter(e -> "Succeeded".equals(stageExecutionStatus(e, stage)))
                    .toList();
        }
        if (request.hasNonNull("maxResults")) {
            JsonNode maxResultsNode = request.get("maxResults");
            if (!maxResultsNode.isIntegralNumber()) {
                throw new AwsException("ValidationException", "maxResults must be an integer", 400);
            }
            int maxResults = maxResultsNode.asInt();
            if (maxResults < 1 || maxResults > 100) {
                throw new AwsException("ValidationException", "maxResults must be between 1 and 100", 400);
            }
        }
        Page page = page(request, executions.size(), 100);
        ObjectNode response = mapper.createObjectNode();
        ArrayNode summaries = response.putArray("pipelineExecutionSummaries");
        executions.subList(page.start(), page.end()).forEach(e -> summaries.add(executionSummaryNode(e)));
        addNextToken(response, page, executions.size());
        return response;
    }

    private ObjectNode listActionExecutions(JsonNode request, String region, String account) {
        String pipelineName = text(request, "pipelineName");
        requirePipeline(account, region, pipelineName);
        String executionId = request.path("filter").path("pipelineExecutionId").asText(null);
        List<ActionExecution> actions = executions(account, region, pipelineName).stream()
                .filter(e -> executionId == null || executionId.equals(e.getPipelineExecutionId()))
                .flatMap(e -> e.getActionExecutions().stream())
                .sorted(Comparator.comparing(ActionExecution::getStartTime,
                        Comparator.nullsLast(Comparator.reverseOrder())))
                .toList();
        Page page = page(request, actions.size(), 100);
        ObjectNode response = mapper.createObjectNode();
        ArrayNode details = response.putArray("actionExecutionDetails");
        actions.subList(page.start(), page.end()).forEach(a -> details.add(actionDetailNode(a)));
        addNextToken(response, page, actions.size());
        return response;
    }

    private ObjectNode getPipelineState(JsonNode request, String region, String account) {
        CodePipelinePipeline pipeline = requirePipeline(account, region, text(request, "name"));
        List<CodePipelineExecution> pipelineExecutions = executions(account, region, pipeline.getName());
        CodePipelineExecution latest = pipelineExecutions.stream().findFirst().orElse(null);
        ObjectNode response = mapper.createObjectNode();
        response.put("pipelineName", pipeline.getName());
        response.put("pipelineVersion", pipeline.getVersion());
        response.put("created", pipeline.getCreated());
        response.put("updated", pipeline.getUpdated());
        ArrayNode stageStates = response.putArray("stageStates");
        for (JsonNode stage : pipeline.getDeclaration().path("stages")) {
            String stageName = stage.path("name").asText();
            ObjectNode state = stageStates.addObject();
            state.put("stageName", stageName);
            TransitionState transition = pipeline.getTransitions().getOrDefault(stageName, enabledTransition());
            ObjectNode transitionNode = state.putObject("inboundTransitionState");
            transitionNode.put("enabled", transition.isEnabled());
            putIfNotNull(transitionNode, "lastChangedAt", transition.getLastChangedAt());
            putIfNotNull(transitionNode, "lastChangedBy", transition.getLastChangedBy());
            putIfNotNull(transitionNode, "disabledReason", transition.getReason());
            ArrayNode actionStates = state.putArray("actionStates");
            for (JsonNode action : stage.path("actions")) {
                ObjectNode actionState = actionStates.addObject();
                String actionName = action.path("name").asText();
                actionState.put("actionName", actionName);
                if (latest != null) {
                    ActionExecution latestAction = latestActionExecution(latest, stageName, actionName);
                    if (latestAction != null) {
                        actionState.set("latestExecution", actionStateNode(latestAction));
                    }
                }
            }
            CodePipelineExecution latestStageExecution = pipelineExecutions.stream()
                    .filter(execution -> hasStageExecution(execution, stageName))
                    .findFirst()
                    .orElse(null);
            if (latestStageExecution != null) {
                ObjectNode latestExecution = state.putObject("latestExecution");
                latestExecution.put("pipelineExecutionId", latestStageExecution.getPipelineExecutionId());
                latestExecution.put("status", stageExecutionStatus(latestStageExecution, stageName));
                latestExecution.put("type", latestStageExecution.getExecutionType());
            }
        }
        return response;
    }

    private ObjectNode setStageTransition(JsonNode request, String region, String account, boolean enabled) {
        CodePipelinePipeline pipeline = requirePipeline(account, region, text(request, "pipelineName"));
        String stageName = text(request, "stageName");
        requireStage(pipeline, stageName);
        TransitionState transition = pipeline.getTransitions().computeIfAbsent(stageName, ignored -> enabledTransition());
        transition.setEnabled(enabled);
        transition.setReason(enabled ? null : request.path("reason").asText("Disabled"));
        transition.setLastChangedAt(now());
        transition.setLastChangedBy(account);
        pipeline.setUpdated(now());
        putPipeline(pipeline);
        return mapper.createObjectNode();
    }

    private ObjectNode putApprovalResult(JsonNode request, String region, String account) {
        String pipelineName = text(request, "pipelineName");
        String stageName = text(request, "stageName");
        String actionName = text(request, "actionName");
        String token = text(request, "token");
        JsonNode resultNode = request.get("result");
        if (resultNode == null || resultNode.isNull()) {
            throw new AwsException("ValidationException", "result is required", 400);
        }
        if (!resultNode.isObject()) {
            throw new AwsException("ValidationException", "result must be an object", 400);
        }
        String status = text(resultNode, "status");
        if (!List.of("Approved", "Rejected").contains(status)) {
            throw new AwsException("ValidationException",
                    "result.status must be one of [Approved, Rejected]", 400);
        }
        JsonNode summaryNode = resultNode.get("summary");
        String summary = "";
        if (summaryNode != null && !summaryNode.isNull()) {
            if (!summaryNode.isTextual()) {
                throw new AwsException("ValidationException", "result.summary must be a string", 400);
            }
            summary = summaryNode.asText();
        }
        if (summary.length() > 512) {
            throw new AwsException("ValidationException",
                    "result.summary must not exceed 512 characters", 400);
        }

        CodePipelinePipeline pipeline = requirePipeline(account, region, pipelineName);

        CodePipelineExecution execution = executions(account, region, pipelineName).stream()
                .filter(e -> e.getActionExecutions().stream()
                        .anyMatch(a -> stageName.equals(a.getStageName())
                                && actionName.equals(a.getActionName())
                                && token.equals(a.getToken())))
                .findFirst()
                .orElse(null);

        ActionExecution approval = execution == null ? null : execution.getActionExecutions().stream()
                .filter(a -> stageName.equals(a.getStageName()) && actionName.equals(a.getActionName()))
                .filter(a -> token.equals(a.getToken()))
                .findFirst()
                .orElse(null);

        if (approval == null) {
            requireStage(pipeline, stageName);
            requireAction(pipeline, stageName, actionName);
            throw new AwsException("InvalidApprovalTokenException", "Approval token is invalid", 400);
        }

        if (!"InProgress".equals(approval.getStatus())) {
            throw new AwsException("ApprovalAlreadyCompletedException",
                    "The approval action has already been approved or rejected.", 400);
        }

        boolean approved = "Approved".equals(status);
        approval.setStatus(approved ? "Succeeded" : "Failed");
        approval.setSummary(summary);
        approval.setLastUpdateTime(now());
        if (!approved) {
            execution.setStatus("Failed");
            execution.setStatusSummary("Action " + actionName + " failed: " + summary);
            execution.getStageExecutionStatuses().put(stageName, "Failed");
            execution.setLastUpdateTime(now());
        }
        putExecution(execution);
        return mapper.createObjectNode().put("approvedAt", now());
    }

    private ObjectNode retryStageExecution(JsonNode request, String region, String account) {
        String pipelineName = text(request, "pipelineName");
        String stageName = text(request, "stageName");
        String executionId = text(request, "pipelineExecutionId");
        String retryMode = text(request, "retryMode");
        if (!List.of("FAILED_ACTIONS", "ALL_ACTIONS").contains(retryMode)) {
            throw new AwsException("ValidationException",
                    "retryMode must be one of [FAILED_ACTIONS, ALL_ACTIONS]", 400);
        }

        CodePipelinePipeline pipeline = requirePipeline(account, region, pipelineName);
        JsonNode stage = stageByName(pipeline, stageName);
        CodePipelineExecution execution = requireExecution(account, region, pipelineName, executionId);
        Map<String, String> latestStatuses = startLocks.withLock(lockKey(execution),
                () -> admitRetry(pipeline, execution, stageName, retryMode));
        try {
            executor.submit(() -> runRetriedStage(pipeline, execution, stage, retryMode, latestStatuses));
        } catch (RejectedExecutionException exception) {
            activeRuns.remove(runKey(execution));
            execution.setStatus("Failed");
            execution.setStatusSummary("Stage retry could not be scheduled.");
            execution.setLastUpdateTime(now());
            putExecution(execution);
            throw pipelineBusy();
        }
        return mapper.createObjectNode().put("pipelineExecutionId", executionId);
    }

    private static AwsException pipelineBusy() {
        return new AwsException("ConflictException",
                "Your request cannot be handled because the pipeline is busy handling ongoing activities. "
                        + "Try again later.", 400);
    }

    // Runs under startLocks so two concurrent retries cannot both pass the status checks,
    // and so a retry counts against the same active-execution cap StartPipelineExecution enforces.
    private Map<String, String> admitRetry(CodePipelinePipeline pipeline, CodePipelineExecution execution,
                                           String stageName, String retryMode) {
        String executionId = execution.getPipelineExecutionId();
        boolean laterFailureInStage = executions(execution.getAccountId(), execution.getRegion(),
                execution.getPipelineName()).stream()
                .takeWhile(candidate -> !executionId.equals(candidate.getPipelineExecutionId()))
                .map(candidate -> latestActionStatuses(candidate, stageName))
                .anyMatch(statuses -> statuses.values().stream().anyMatch("Failed"::equals));
        if (laterFailureInStage) {
            throw new AwsException("NotLatestPipelineExecutionException",
                    "A later pipeline execution has failed in stage " + stageName, 400);
        }
        if (!Objects.equals(execution.getPipelineVersion(), pipeline.getVersion())) {
            throw new AwsException("StageNotRetryableException",
                    "The pipeline structure changed after this execution started", 400);
        }
        if ("InProgress".equals(execution.getStatus()) || "Stopping".equals(execution.getStatus())
                || activeRuns.contains(runKey(execution))) {
            throw new AwsException("ConflictException", "Pipeline execution is still in progress", 400);
        }
        Map<String, String> latestStatuses = latestActionStatuses(execution, stageName);
        boolean hasFailedAction = latestStatuses.values().stream().anyMatch("Failed"::equals);
        boolean stoppedStageRetry = "Stopped".equals(execution.getStatus())
                && "ALL_ACTIONS".equals(retryMode)
                && "Stopped".equals(execution.getStageExecutionStatuses().get(stageName));
        if (!hasFailedAction && !stoppedStageRetry) {
            throw new AwsException("StageNotRetryableException",
                    "The stage contains no retryable actions", 400);
        }
        if (execution.isArtifactsReleased()) {
            throw new AwsException("StageNotRetryableException",
                    "The artifacts this stage consumes are no longer retained for this execution", 400);
        }
        if (List.of("QUEUED", "PARALLEL").contains(execution.getExecutionMode())
                && activeExecutionCount(execution) >= MAX_ACTIVE_EXECUTIONS) {
            throw new AwsException("ConcurrentPipelineExecutionsLimitExceededException",
                    "The pipeline has reached the limit for concurrent pipeline executions", 400);
        }

        execution.setStatus("InProgress");
        execution.setStatusSummary("Retrying stage " + stageName + ".");
        execution.setStopRequested(false);
        execution.setAbandon(false);
        execution.setLastUpdateTime(now());
        putExecution(execution);
        activeRuns.add(runKey(execution));
        return latestStatuses;
    }

    private ObjectNode rollbackStage(JsonNode request, String region, String account) {
        String pipelineName = text(request, "pipelineName");
        String stageName = text(request, "stageName");
        CodePipelinePipeline pipeline = requirePipeline(account, region, pipelineName);
        // CodePipeline User Guide, execution modes: stage rollback is not available on PARALLEL pipelines.
        if ("PARALLEL".equals(pipeline.getDeclaration().path("executionMode").asText(DEFAULT_EXECUTION_MODE))) {
            throw new AwsException("ValidationException",
                    "Stage rollback is not supported on PARALLEL pipelines.", 400);
        }
        JsonNode stage = stageByName(pipeline, stageName);
        // CodePipeline User Guide, "Considerations for rollbacks": a source stage cannot be rolled back.
        for (JsonNode action : stage.path("actions")) {
            if (isSourceAction(action)) {
                throw new AwsException("UnableToRollbackStageException",
                        "A source stage cannot be rolled back.", 400);
            }
        }
        CodePipelineExecution target = requireExecution(
                account, region, pipelineName, text(request, "targetPipelineExecutionId"));
        if ("ROLLBACK".equals(target.getExecutionType())) {
            throw new AwsException("UnableToRollbackStageException",
                    "The target execution is a rollback execution.", 400);
        }
        if (!Objects.equals(target.getPipelineVersion(), pipeline.getVersion())) {
            throw new AwsException("UnableToRollbackStageException",
                    "The pipeline structure changed after the target execution ran.", 400);
        }
        // The stage status, not the action statuses: a stage whose onSuccess condition failed has
        // only Succeeded actions but is itself Failed.
        boolean stageSucceededInTarget = !latestActionStatuses(target, stageName).isEmpty()
                && "Succeeded".equals(stageExecutionStatus(target, stageName));
        if (!stageSucceededInTarget) {
            throw new AwsException("UnableToRollbackStageException",
                    "The stage did not complete successfully in the target execution.", 400);
        }
        // Checked and registered under the start lock so two concurrent rollbacks of one stage cannot both pass.
        CodePipelineExecution rollback = startLocks.withLock(pipelineLockKey(account, region, pipelineName), () -> {
            if (stageRunning(account, region, pipelineName, stageName)) {
                throw new AwsException("UnableToRollbackStageException",
                        "The stage is currently running.", 400);
            }
            return startRollback(pipeline, account, region, stageName, target);
        });
        return mapper.createObjectNode().put("pipelineExecutionId", rollback.getPipelineExecutionId());
    }

    // A rollback still fetching its sources has no stage status yet, so it is matched by its target stage.
    private boolean stageRunning(String account, String region, String pipelineName, String stageName) {
        return executions(account, region, pipelineName).stream()
                .anyMatch(candidate -> "InProgress".equals(candidate.getStatus())
                        && ("InProgress".equals(candidate.getStageExecutionStatuses().get(stageName))
                        || ("ROLLBACK".equals(candidate.getExecutionType())
                        && stageName.equals(candidate.getRollbackStageName()))));
    }

    private static boolean isSourceAction(JsonNode action) {
        return "Source".equals(action.path("actionTypeId").path("category").asText());
    }

    /**
     * Starts a ROLLBACK execution for one stage. Source-only stages before the target stage re-run
     * to seed the input artifacts, pinned to the target execution's source revisions, then only
     * the target stage itself runs; intermediate build/deploy stages are skipped.
     */
    private CodePipelineExecution startRollback(CodePipelinePipeline pipeline, String account,
                                                String region, String stageName,
                                                CodePipelineExecution target) {
        CodePipelineExecution rollback = new CodePipelineExecution();
        rollback.setAccountId(account);
        rollback.setRegion(region);
        rollback.setPipelineExecutionId(UUID.randomUUID().toString());
        rollback.setPipelineName(pipeline.getName());
        rollback.setPipelineVersion(pipeline.getVersion());
        rollback.setExecutionMode(pipeline.getDeclaration().path("executionMode").asText(DEFAULT_EXECUTION_MODE));
        rollback.setStatus("InProgress");
        rollback.setStatusSummary("Rolling back stage " + stageName + " to execution "
                + target.getPipelineExecutionId() + ".");
        rollback.setExecutionType("ROLLBACK");
        rollback.setRollbackTargetPipelineExecutionId(target.getPipelineExecutionId());
        rollback.setRollbackStageName(stageName);
        rollback.setStartTime(now());
        rollback.setLastUpdateTime(rollback.getStartTime());
        rollback.setSourceRevisions(new ArrayList<>());
        synchronized (target) {
            // The target pinned the S3 object version each source action resolved, so re-running
            // the sources with these overrides reproduces the target's artifacts.
            rollback.setSourceRevisionOverrides(new ArrayList<>(target.getSourceRevisionOverrides()));
        }
        pinGitHubCommits(pipeline, rollback, target);
        rollback.setVariables(new ArrayList<>(target.getVariables()));
        Map<String, String> trigger = new LinkedHashMap<>();
        trigger.put("triggerType", "ManualRollback");
        trigger.put("triggerDetail", MANUAL_TRIGGER_DETAIL);
        rollback.setTrigger(trigger);
        if (!persistExecutionIfSlotAvailable(rollback)) {
            throw pipelineBusy();
        }
        applyExecutionMode(rollback);
        eventPublisher.pipelineStateChange(rollback, "STARTED");
        try {
            executor.submit(() -> runExecution(pipeline, rollback));
        } catch (RejectedExecutionException exception) {
            rollback.setStatus("Failed");
            rollback.setStatusSummary("Pipeline execution could not be scheduled.");
            rollback.setLastUpdateTime(now());
            putExecution(rollback);
            eventPublisher.pipelineStateChange(rollback, "FAILED");
            throw pipelineBusy();
        }
        return rollback;
    }

    /**
     * Pins each GitHub source action of a rollback to the commit the target execution resolved, so
     * the source re-run reproduces the target's artifact instead of the branch head.
     */
    private void pinGitHubCommits(CodePipelinePipeline pipeline, CodePipelineExecution rollback,
                                  CodePipelineExecution target) {
        Set<String> githubActions = new HashSet<>();
        for (JsonNode stage : pipeline.getDeclaration().path("stages")) {
            for (JsonNode action : stage.path("actions")) {
                JsonNode type = action.path("actionTypeId");
                if ("Source".equals(type.path("category").asText())
                        && "GitHub".equals(type.path("provider").asText())) {
                    githubActions.add(action.path("name").asText());
                }
            }
        }
        synchronized (target) {
            for (Map<String, Object> revision : target.getSourceRevisions()) {
                Object actionName = revision.get("actionName");
                Object commit = revision.get("revisionId");
                if (actionName == null || commit == null || !githubActions.contains(actionName.toString())) {
                    continue;
                }
                Map<String, Object> pin = new LinkedHashMap<>();
                pin.put("actionName", actionName);
                pin.put("revisionType", GITHUB_COMMIT_ID);
                pin.put("revisionValue", commit);
                rollback.getSourceRevisionOverrides().add(pin);
            }
        }
    }

    private ObjectNode overrideStageCondition(JsonNode request, String region, String account) {
        String pipelineName = text(request, "pipelineName");
        String stageName = text(request, "stageName");
        String conditionType = text(request, "conditionType");
        if (!"BEFORE_ENTRY".equals(conditionType) && !"ON_SUCCESS".equals(conditionType)) {
            throw new AwsException("ValidationException",
                    "conditionType must be BEFORE_ENTRY or ON_SUCCESS", 400);
        }
        CodePipelinePipeline pipeline = requirePipeline(account, region, pipelineName);
        JsonNode stage = stageByName(pipeline, stageName);
        CodePipelineExecution execution = requireExecution(
                account, region, pipelineName, text(request, "pipelineExecutionId"));
        if (!Objects.equals(execution.getPipelineVersion(), pipeline.getVersion())) {
            throw new AwsException("ConditionNotOverridableException",
                    "The pipeline structure changed after this execution started", 400);
        }
        // A run that failed on this very condition resumes from the overridden stage, skipping the
        // stage's already-succeeded actions; admission shares the retry path's lock and run tracking.
        // The run marks itself Failed slightly before it leaves activeRuns; an override landing in that
        // window waits (outside the lock, which finishExecutionRun needs) for the run to finish, so it
        // resumes instead of storing a flag nothing will read.
        String conditionFailedSummary = "Condition " + conditionType + " failed in stage " + stageName + ".";
        boolean[] runStillFinishing = new boolean[1];
        Map<String, String> resumed = null;
        long finishDeadline = System.nanoTime() + runFinishWaitNanos;
        do {
            runStillFinishing[0] = false;
            resumed = startLocks.withLock(lockKey(execution), () -> {
                synchronized (execution) {
                    execution.getConditionOverrides().put(stageName + "/" + conditionType, true);
                }
                boolean failedOnThisCondition = "Failed".equals(execution.getStatus())
                        && conditionFailedSummary.equals(execution.getStatusSummary());
                if (failedOnThisCondition && activeRuns.contains(runKey(execution))) {
                    runStillFinishing[0] = true;
                    return null;
                }
                if (!failedOnThisCondition) {
                    putExecution(execution);
                    return null;
                }
                execution.setStatus("InProgress");
                execution.setStatusSummary("Condition " + conditionType + " overridden in stage "
                        + stageName + ".");
                execution.setStopRequested(false);
                execution.setAbandon(false);
                execution.setLastUpdateTime(now());
                putExecution(execution);
                activeRuns.add(runKey(execution));
                return latestActionStatuses(execution, stageName);
            });
            if (runStillFinishing[0]) {
                try {
                    TimeUnit.MILLISECONDS.sleep(10);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
        } while (runStillFinishing[0] && System.nanoTime() - finishDeadline < 0);
        if (runStillFinishing[0]) {
            beforeOverrideTimeoutCleanup.run();
            // Another override may have resumed the run since this waiter's last check; clear the flag
            // only under the resume lock and only while the run is still failed on this condition.
            startLocks.withLock(lockKey(execution), () -> {
                synchronized (execution) {
                    if ("Failed".equals(execution.getStatus())
                            && conditionFailedSummary.equals(execution.getStatusSummary())) {
                        execution.getConditionOverrides().remove(stageName + "/" + conditionType);
                        putExecution(execution);
                    }
                }
                return null;
            });
            throw pipelineBusy();
        }
        Map<String, String> resumeStatuses = resumed;
        if (resumeStatuses != null) {
            try {
                executor.submit(() -> runRetriedStage(pipeline, execution, stage, "FAILED_ACTIONS",
                        resumeStatuses));
            } catch (RejectedExecutionException exception) {
                activeRuns.remove(runKey(execution));
                execution.setStatus("Failed");
                execution.setStatusSummary("Stage resume could not be scheduled.");
                execution.setLastUpdateTime(now());
                putExecution(execution);
                throw pipelineBusy();
            }
        }
        return mapper.createObjectNode();
    }

    private ObjectNode listRuleExecutions(JsonNode request, String region, String account) {
        String pipelineName = text(request, "pipelineName");
        requirePipeline(account, region, pipelineName);
        String executionFilter = request.path("filter").path("pipelineExecutionId").asText(null);
        List<ObjectNode> details = new ArrayList<>();
        for (CodePipelineExecution execution : executions(account, region, pipelineName)) {
            if (executionFilter != null
                    && !executionFilter.equals(execution.getPipelineExecutionId())) {
                continue;
            }
            List<Map<String, Object>> ruleSnapshot = new ArrayList<>();
            synchronized (execution) {
                for (Map<String, Object> rule : execution.getRuleExecutions()) {
                    ruleSnapshot.add(new LinkedHashMap<>(rule));
                }
            }
            for (Map<String, Object> rule : ruleSnapshot) {
                ObjectNode detail = mapper.createObjectNode();
                detail.put("pipelineExecutionId", execution.getPipelineExecutionId());
                detail.put("ruleExecutionId", Objects.toString(rule.get("ruleExecutionId"), null));
                if (execution.getPipelineVersion() != null) {
                    detail.put("pipelineVersion", execution.getPipelineVersion());
                }
                detail.put("stageName", Objects.toString(rule.get("stageName"), null));
                detail.put("ruleName", Objects.toString(rule.get("ruleName"), null));
                detail.put("status", Objects.toString(rule.get("status"), null));
                if (rule.get("startTime") instanceof Number start) {
                    detail.put("startTime", start.doubleValue());
                }
                if (rule.get("lastUpdateTime") instanceof Number updated) {
                    detail.put("lastUpdateTime", updated.doubleValue());
                }
                ObjectNode input = detail.putObject("input");
                input.putObject("ruleTypeId")
                        .put("category", "Rule")
                        .put("owner", "AWS")
                        .put("provider", Objects.toString(rule.get("ruleProvider"), ""))
                        .put("version", "1");
                detail.putObject("output").putObject("executionResult")
                        .put("externalExecutionSummary", Objects.toString(rule.get("summary"), ""));
                details.add(detail);
            }
        }
        Page page = page(request, details.size(), 100);
        ObjectNode response = mapper.createObjectNode();
        ArrayNode array = response.putArray("ruleExecutionDetails");
        for (int i = page.start(); i < page.end(); i++) {
            array.add(details.get(i));
        }
        addNextToken(response, page, details.size());
        return response;
    }

    /** The AWS-owned rule types available for V2 stage conditions. */
    private ObjectNode listRuleTypes() {
        ObjectNode response = mapper.createObjectNode();
        ArrayNode ruleTypes = response.putArray("ruleTypes");
        ruleTypes.add(ruleTypeNode("LambdaInvoke",
                "Invokes a Lambda function and passes when the invocation succeeds",
                List.of("FunctionName")));
        ruleTypes.add(ruleTypeNode("VariableCheck",
                "Compares a pipeline variable against a value with EQ, NE, CONTAINS or MATCHES",
                List.of("Variable", "Value", "Operator")));
        ruleTypes.add(ruleTypeNode("Commands",
                "Runs shell commands (accepted but not evaluated by the emulator)",
                List.of("Commands")));
        ruleTypes.add(ruleTypeNode("DeploymentWindow",
                "Restricts deployments to a time window (accepted but not evaluated by the emulator)",
                List.of("Cron", "TimeZone")));
        ruleTypes.add(ruleTypeNode("CloudWatchAlarm",
                "Passes when a CloudWatch alarm is in the expected state (accepted but not evaluated by the emulator)",
                List.of("AlarmName", "AlarmStates")));
        return response;
    }

    private ObjectNode ruleTypeNode(String provider, String description, List<String> properties) {
        ObjectNode node = mapper.createObjectNode();
        node.putObject("id")
                .put("category", "Rule")
                .put("owner", "AWS")
                .put("provider", provider)
                .put("version", "1");
        ObjectNode settings = node.putObject("settings");
        settings.put("thirdPartyConfigurationUrl", "");
        ArrayNode props = node.putArray("ruleConfigurationProperties");
        for (String property : properties) {
            props.addObject()
                    .put("name", property)
                    .put("required", true)
                    .put("key", true)
                    .put("secret", false)
                    .put("description", description);
        }
        node.putObject("inputArtifactDetails")
                .put("minimumCount", 0)
                .put("maximumCount", 1);
        return node;
    }

    private ObjectNode createCustomActionType(JsonNode request, String region, String account) {
        String id = actionTypeId(request.path("category").asText(), "Custom",
                request.path("provider").asText(), request.path("version").asText());
        if (itemStore.getForAccount(account, itemKey(region, "action", id)).isPresent()) {
            throw new AwsException("ActionTypeAlreadyExistsException", "Action type already exists", 400);
        }
        ObjectNode actionType = mapper.createObjectNode();
        actionType.setAll((ObjectNode) request.deepCopy());
        actionType.putObject("id")
                .put("category", request.path("category").asText())
                .put("owner", "Custom")
                .put("provider", request.path("provider").asText())
                .put("version", request.path("version").asText());
        storeItem(account, region, "action", id, "Active", actionType);
        return mapper.createObjectNode().set("actionType", actionType);
    }

    private ObjectNode updateActionType(JsonNode request, String region, String account) {
        JsonNode identifier = request.path("actionType");
        String id = actionTypeId(identifier.path("category").asText(), identifier.path("owner").asText(),
                identifier.path("provider").asText(), identifier.path("version").asText());
        CodePipelineStoredItem existing = requireItem(account, region, "action", id, "ActionTypeNotFoundException");
        existing.setData(request.deepCopy());
        existing.setUpdated(now());
        putItem(existing);
        return mapper.createObjectNode();
    }

    private ObjectNode getActionType(JsonNode request, String region, String account) {
        JsonNode identifier = request.has("actionType") ? request.path("actionType") : request;
        String id = actionTypeId(identifier.path("category").asText(), identifier.path("owner").asText(),
                identifier.path("provider").asText(), identifier.path("version").asText());
        CodePipelineStoredItem item = requireItem(account, region, "action", id, "ActionTypeNotFoundException");
        return mapper.createObjectNode().set("actionType", item.getData());
    }

    private ObjectNode deleteCustomActionType(JsonNode request, String region, String account) {
        String id = actionTypeId(request.path("category").asText(), "Custom",
                request.path("provider").asText(), request.path("version").asText());
        itemStore.deleteForAccount(account, itemKey(region, "action", id));
        return mapper.createObjectNode();
    }

    private ObjectNode listActionTypes(JsonNode request, String region, String account) {
        List<CodePipelineStoredItem> items = items(account, region, "action");
        String ownerFilter = request.path("actionOwnerFilter").asText(null);
        if (ownerFilter != null) {
            items = items.stream()
                    .filter(i -> ownerFilter.equals(i.getData().path("id").path("owner").asText("Custom")))
                    .toList();
        }
        Page page = page(request, items.size(), 100);
        ObjectNode response = mapper.createObjectNode();
        ArrayNode actionTypes = response.putArray("actionTypes");
        items.subList(page.start(), page.end()).forEach(i -> actionTypes.add(i.getData()));
        addNextToken(response, page, items.size());
        return response;
    }

    private ObjectNode pollForJobs(JsonNode request, String region, String account, boolean thirdParty) {
        JsonNode actionTypeId = request.path("actionTypeId");
        String owner = actionTypeId.path("owner").asText();
        if ((!thirdParty && !"Custom".equals(owner)) || (thirdParty && !"ThirdParty".equals(owner))) {
            throw new AwsException("ValidationException",
                    thirdParty ? "PollForThirdPartyJobs requires owner ThirdParty"
                            : "PollForJobs requires owner Custom", 400);
        }
        String requested = actionTypeId(actionTypeId.path("category").asText(),
                owner, actionTypeId.path("provider").asText(),
                actionTypeId.path("version").asText());
        ArrayNode jobs = mapper.createArrayNode();
        int maximum = Math.max(1, request.path("maxBatchSize").asInt(1));
        for (CodePipelineStoredItem item : items(account, region, "job")) {
            if (jobs.size() >= maximum) {
                break;
            }
            if (!"Created".equals(item.getStatus())) {
                continue;
            }
            JsonNode data = item.getData();
            if (requested.equals(data.path("actionTypeKey").asText())
                    && thirdParty == data.path("thirdParty").asBoolean(false)) {
                jobs.add(data.path("job"));
            }
        }
        ObjectNode response = mapper.createObjectNode();
        response.set("jobs", jobs);
        return response;
    }

    private ObjectNode acknowledgeJob(JsonNode request, String region, String account) {
        CodePipelineStoredItem job = requireItem(
                account, region, "job", text(request, "jobId"), "JobNotFoundException");
        String nonce = text(request, "nonce");
        if (!nonce.equals(job.getData().path("nonce").asText())) {
            throw new AwsException("InvalidNonceException", "Invalid job nonce", 400);
        }
        if (!"Created".equals(job.getStatus())) {
            throw new AwsException("InvalidJobStateException", "Job has already been acknowledged", 400);
        }
        job.setStatus("InProgress");
        job.setUpdated(now());
        putItem(job);
        return mapper.createObjectNode().put("status", "InProgress");
    }

    private ObjectNode getJobDetails(JsonNode request, String region, String account) {
        CodePipelineStoredItem job = requireItem(
                account, region, "job", text(request, "jobId"), "JobNotFoundException");
        return mapper.createObjectNode().set("jobDetails", job.getData().path("job"));
    }

    private ObjectNode completeJob(JsonNode request, String region, String account, boolean success) {
        CodePipelineStoredItem job = requireItem(
                account, region, "job", text(request, "jobId"), "JobNotFoundException");
        if (!List.of("Created", "InProgress").contains(job.getStatus())) {
            throw new AwsException("InvalidJobStateException", "Job is already complete", 400);
        }
        job.setStatus(success ? "Succeeded" : "Failed");
        job.setUpdated(now());
        ObjectNode data = (ObjectNode) job.getData();
        data.set("result", request.deepCopy());
        putItem(job);
        return mapper.createObjectNode();
    }

    private ObjectNode putActionRevision(JsonNode request) {
        ObjectNode response = mapper.createObjectNode();
        response.put("newRevision", true);
        response.put("pipelineExecutionId", UUID.randomUUID().toString());
        return response;
    }

    private ObjectNode putWebhook(JsonNode request, String region, String account) {
        JsonNode webhook = request.path("webhook");
        String name = webhook.path("name").asText(null);
        if (name == null || name.isBlank()) {
            throw new AwsException("ValidationException", "webhook.name is required", 400);
        }
        ObjectNode item = mapper.createObjectNode();
        item.set("definition", webhook.deepCopy());
        item.put("url", "http://localhost:4566/codepipeline/webhooks/" + name);
        item.put("arn", AwsArnUtils.Arn.of("codepipeline", region, account, "webhook/" + name).toString());
        if (request.path("tags").isArray() && !request.path("tags").isEmpty()) {
            item.set("tags", request.path("tags").deepCopy());
        }
        storeItem(account, region, "webhook", name, "DEREGISTERED", item);
        return mapper.createObjectNode().set("webhook", item);
    }

    private ObjectNode deleteWebhook(JsonNode request, String region, String account) {
        String name = text(request, "name");
        itemStore.deleteForAccount(account, itemKey(region, "webhook", name));
        return mapper.createObjectNode();
    }

    private ObjectNode listWebhooks(JsonNode request, String region, String account) {
        List<CodePipelineStoredItem> webhooks = items(account, region, "webhook");
        Page page = page(request, webhooks.size(), 100);
        ObjectNode response = mapper.createObjectNode();
        ArrayNode list = response.putArray("webhooks");
        webhooks.subList(page.start(), page.end()).forEach(w -> list.add(w.getData()));
        addNextToken(response, page, webhooks.size());
        return response;
    }

    private ObjectNode setWebhookRegistration(JsonNode request, String region, String account, boolean registered) {
        CodePipelineStoredItem webhook = requireItem(
                account, region, "webhook", text(request, "webhookName"), "WebhookNotFoundException");
        webhook.setStatus(registered ? "REGISTERED" : "DEREGISTERED");
        webhook.setUpdated(now());
        putItem(webhook);
        return mapper.createObjectNode();
    }

    private ObjectNode tagResource(JsonNode request, String region, String account) {
        CodePipelinePipeline pipeline = requirePipelineByArn(account, region, text(request, "resourceArn"));
        pipeline.getTags().putAll(parseTags(request.path("tags")));
        putPipeline(pipeline);
        return mapper.createObjectNode();
    }

    private ObjectNode untagResource(JsonNode request, String region, String account) {
        CodePipelinePipeline pipeline = requirePipelineByArn(account, region, text(request, "resourceArn"));
        request.path("tagKeys").forEach(k -> pipeline.getTags().remove(k.asText()));
        putPipeline(pipeline);
        return mapper.createObjectNode();
    }

    private ObjectNode listTagsForResource(JsonNode request, String region, String account) {
        CodePipelinePipeline pipeline = requirePipelineByArn(account, region, text(request, "resourceArn"));
        return mapper.createObjectNode().set("tags", tagsNode(pipeline.getTags()));
    }

    /**
     * The stage names a run may execute, or {@code null} for all stages. A ROLLBACK execution runs
     * only the source stages before the rolled-back stage plus that stage. Derived from persisted
     * state so start, restart-resume and retry all agree.
     */
    private Set<String> includeStagesFor(CodePipelinePipeline pipeline, CodePipelineExecution execution) {
        if (!"ROLLBACK".equals(execution.getExecutionType())) {
            return null;
        }
        String stageName = execution.getRollbackStageName();
        Set<String> include = new LinkedHashSet<>();
        boolean targetFound = false;
        for (JsonNode stage : pipeline.getDeclaration().path("stages")) {
            String name = stage.path("name").asText();
            if (name.equals(stageName)) {
                include.add(name);
                targetFound = true;
                break;
            }
            boolean sourceOnly = stage.path("actions").size() > 0;
            for (JsonNode action : stage.path("actions")) {
                if (!isSourceAction(action)) {
                    sourceOnly = false;
                    break;
                }
            }
            if (sourceOnly) {
                include.add(name);
            }
        }
        if (!targetFound) {
            // Running only the sources would end the rollback Succeeded without rolling anything back.
            throw new AwsException("StageNotFoundException",
                    "Rollback target stage " + stageName + " is not in the pipeline.", 400);
        }
        return include;
    }

    private void runExecution(CodePipelinePipeline pipeline, CodePipelineExecution execution) {
        activeRuns.add(runKey(execution));
        Runnable work = () -> {
            try {
                runStagesFrom(pipeline, execution, 0, includeStagesFor(pipeline, execution));
            } catch (Exception e) {
                if (execution.getCurrentStage() != null) {
                    execution.getStageExecutionStatuses().put(execution.getCurrentStage(), "Failed");
                }
                execution.setStatus("Failed");
                execution.setStatusSummary(e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage());
                LOG.errorf(e, "CodePipeline execution %s failed", execution.getPipelineExecutionId());
            } finally {
                finishExecutionRun(execution);
            }
        };
        runInExecutionMode(execution, work);
    }

    private void runInExecutionMode(CodePipelineExecution execution, Runnable work) {
        if ("QUEUED".equals(execution.getExecutionMode())) {
            pipelineLocks.withLock(lockKey(execution), work);
        } else {
            work.run();
        }
    }

    private void runRetriedStage(CodePipelinePipeline pipeline, CodePipelineExecution execution, JsonNode stage,
                                 String retryMode, Map<String, String> previousStatuses) {
        runInExecutionMode(execution, () -> runRetriedStageNow(pipeline, execution, stage, retryMode,
                previousStatuses));
    }

    private void runRetriedStageNow(CodePipelinePipeline pipeline, CodePipelineExecution execution, JsonNode stage,
                                    String retryMode, Map<String, String> previousStatuses) {
        String stageName = stage.path("name").asText();
        try {
            int stageIndex = stageIndex(pipeline, stageName);
            if (!runStageInRun(pipeline, execution, stage, retryMode, previousStatuses)) {
                return;
            }
            runStagesFrom(pipeline, execution, stageIndex + 1, includeStagesFor(pipeline, execution));
        } catch (Exception e) {
            if (execution.getCurrentStage() != null) {
                execution.getStageExecutionStatuses().put(execution.getCurrentStage(), "Failed");
            }
            execution.setStatus("Failed");
            execution.setStatusSummary(e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage());
            LOG.errorf(e, "CodePipeline retry for execution %s failed", execution.getPipelineExecutionId());
        } finally {
            finishExecutionRun(execution);
        }
    }

    private void runStagesFrom(CodePipelinePipeline pipeline, CodePipelineExecution execution, int startIndex,
                               Set<String> includeStages) throws InterruptedException {
        JsonNode stages = pipeline.getDeclaration().path("stages");
        for (int i = startIndex; i < stages.size(); i++) {
            JsonNode stage = stages.get(i);
            String stageName = stage.path("name").asText();
            if (includeStages != null && !includeStages.contains(stageName)) {
                continue;
            }
            waitForTransition(pipeline, execution, stageName);
            if (finishIfStopped(execution)) {
                return;
            }
            if (!runStageInRun(pipeline, execution, stage, "ALL_ACTIONS", Map.of())) {
                return;
            }
        }
        execution.setStatus("Succeeded");
        execution.setStatusSummary("Pipeline execution succeeded.");
    }

    /**
     * Runs one stage of an execution, between its V2 {@code beforeEntry} and {@code onSuccess}
     * conditions.
     *
     * @return {@code false} when the execution must not continue to the next stage
     */
    private boolean runStageInRun(CodePipelinePipeline pipeline, CodePipelineExecution execution, JsonNode stage,
                                  String retryMode, Map<String, String> previousStatuses) {
        String stageName = stage.path("name").asText();
        execution.setCurrentStage(stageName);
        execution.getStageExecutionStatuses().put(stageName, "InProgress");
        execution.setLastUpdateTime(now());
        putExecution(execution);

        // Evaluated before STARTED is published: AWS documents no skipped-stage event, so a stage
        // skipped by its beforeEntry condition emits no stage events at all.
        ConditionOutcome entry = evaluateConditions(execution, stage, "BEFORE_ENTRY");
        if (entry == ConditionOutcome.SKIP_STAGE) {
            execution.getStageExecutionStatuses().put(stageName, "Skipped");
            execution.setCurrentStage(null);
            execution.setLastUpdateTime(now());
            putExecution(execution);
            return true;
        }
        eventPublisher.stageStateChange(execution, stageName, "STARTED");
        if (entry == ConditionOutcome.FAIL) {
            failForCondition(execution, stageName, "BEFORE_ENTRY");
            execution.getStageExecutionStatuses().put(stageName, "Failed");
            eventPublisher.stageStateChange(execution, stageName, "FAILED");
            return false;
        }

        runStage(pipeline, execution, stage, retryMode, previousStatuses);
        if ("Failed".equals(execution.getStatus())) {
            execution.getStageExecutionStatuses().put(stageName, "Failed");
            eventPublisher.stageStateChange(execution, stageName, "FAILED");
            return false;
        }
        if (finishIfStopped(execution)) {
            eventPublisher.stageStateChange(execution, stageName, "STOPPED");
            return false;
        }

        // A SKIP result only makes sense before entry; after the stage ran it cannot be skipped, so a
        // failed onSuccess condition fails the stage whatever its declared result.
        if (evaluateConditions(execution, stage, "ON_SUCCESS") != ConditionOutcome.PASS) {
            failForCondition(execution, stageName, "ON_SUCCESS");
            execution.getStageExecutionStatuses().put(stageName, "Failed");
            eventPublisher.stageStateChange(execution, stageName, "FAILED");
            return false;
        }
        execution.getStageExecutionStatuses().put(stageName, "Succeeded");
        execution.setCurrentStage(null);
        execution.setLastUpdateTime(now());
        putExecution(execution);
        eventPublisher.stageStateChange(execution, stageName, "SUCCEEDED");
        return true;
    }

    // ---------------------------------------------------------------- V2 stage conditions

    private enum ConditionOutcome { PASS, FAIL, SKIP_STAGE }

    /**
     * Evaluates the stage's {@code beforeEntry}/{@code onSuccess} condition block. Rules run
     * through the AWS rule providers we emulate: {@code LambdaInvoke} (real invocation via the
     * Lambda service) and {@code VariableCheck} (pipeline-variable comparison); other providers
     * pass permissively. A condition overridden via OverrideStageCondition always passes.
     */
    private ConditionOutcome evaluateConditions(CodePipelineExecution execution, JsonNode stage,
                                                String conditionType) {
        JsonNode block = stage.path(conditionBlockField(conditionType));
        if (block.isMissingNode() || !block.has("conditions")) {
            return ConditionOutcome.PASS;
        }
        if (Boolean.TRUE.equals(execution.getConditionOverrides()
                .get(stage.path("name").asText() + "/" + conditionType))) {
            return ConditionOutcome.PASS;
        }
        for (JsonNode condition : block.path("conditions")) {
            boolean passed = true;
            for (JsonNode rule : condition.path("rules")) {
                if (!evaluateRule(execution, stage.path("name").asText(), conditionType, rule)) {
                    passed = false;
                }
            }
            if (!passed) {
                return "SKIP".equals(condition.path("result").asText("FAIL")) && "BEFORE_ENTRY".equals(conditionType)
                        ? ConditionOutcome.SKIP_STAGE : ConditionOutcome.FAIL;
            }
        }
        return ConditionOutcome.PASS;
    }

    private static String conditionBlockField(String conditionType) {
        return switch (conditionType) {
            case "BEFORE_ENTRY" -> "beforeEntry";
            case "ON_SUCCESS" -> "onSuccess";
            case "ON_FAILURE" -> "onFailure";
            default -> throw new AwsException("ValidationException",
                    "Unknown condition type " + conditionType, 400);
        };
    }

    private boolean evaluateRule(CodePipelineExecution execution, String stageName,
                                 String conditionType, JsonNode rule) {
        Map<String, Object> record = new LinkedHashMap<>();
        record.put("ruleExecutionId", UUID.randomUUID().toString());
        record.put("ruleName", rule.path("name").asText());
        record.put("stageName", stageName);
        record.put("conditionType", conditionType);
        record.put("ruleProvider", rule.path("ruleTypeId").path("provider").asText());
        record.put("startTime", now());
        record.put("status", "InProgress");
        synchronized (execution) {
            execution.getRuleExecutions().add(record);
            putExecution(execution);
        }
        boolean passed;
        String summary;
        try {
            String provider = rule.path("ruleTypeId").path("provider").asText();
            passed = switch (provider) {
                case "LambdaInvoke" -> lambdaRulePasses(execution, rule);
                case "VariableCheck" -> variableCheckPasses(execution, rule);
                // Commands / DeploymentWindow / CloudWatchAlarm and unknown providers pass permissively.
                default -> true;
            };
            summary = passed ? "Rule passed." : "Rule condition was not met.";
        } catch (Exception e) {
            passed = false;
            summary = Objects.toString(e.getMessage(), "Rule evaluation failed");
        }
        synchronized (execution) {
            record.put("status", passed ? "Succeeded" : "Failed");
            record.put("summary", summary);
            record.put("lastUpdateTime", now());
            putExecution(execution);
        }
        return passed;
    }

    private boolean lambdaRulePasses(CodePipelineExecution execution, JsonNode rule) {
        String functionName = rule.path("configuration").path("FunctionName").asText(null);
        if (functionName == null) {
            throw new AwsException("ValidationException",
                    "LambdaInvoke rules require configuration.FunctionName", 400);
        }
        ObjectNode event = mapper.createObjectNode();
        event.put("ruleName", rule.path("name").asText());
        event.put("pipelineName", execution.getPipelineName());
        event.put("pipelineExecutionId", execution.getPipelineExecutionId());
        InvokeResult result = lambdaService.invoke(execution.getRegion(), functionName,
                event.toString().getBytes(StandardCharsets.UTF_8), InvocationType.RequestResponse);
        return result.getFunctionError() == null && result.getStatusCode() < 400;
    }

    /** Above this length, a MATCHES pattern or subject is rejected rather than risking
     * catastrophic regex backtracking on pipeline-declared input. */
    private static final int MAX_VARIABLE_CHECK_MATCH_LENGTH = 256;

    private static final long MATCHES_TIMEOUT_NANOS = TimeUnit.SECONDS.toNanos(1);

    private boolean variableCheckPasses(CodePipelineExecution execution, JsonNode rule) {
        JsonNode config = rule.path("configuration");
        String variable = resolveVariableReference(execution, config.path("Variable").asText(""));
        String value = config.path("Value").asText("");
        return switch (config.path("Operator").asText("EQ")) {
            case "EQ" -> variable.equals(value);
            case "NE" -> !variable.equals(value);
            case "CONTAINS" -> variable.contains(value);
            case "MATCHES" -> {
                if (value.length() > MAX_VARIABLE_CHECK_MATCH_LENGTH
                        || variable.length() > MAX_VARIABLE_CHECK_MATCH_LENGTH) {
                    throw new AwsException("ValidationException",
                            "VariableCheck MATCHES pattern or value exceeds "
                                    + MAX_VARIABLE_CHECK_MATCH_LENGTH + " characters", 400);
                }
                yield Pattern.compile(value).matcher(new DeadlineCharSequence(variable,
                        System.nanoTime() + MATCHES_TIMEOUT_NANOS, "MATCHES pattern evaluation timed out")).matches();
            }
            default -> throw new AwsException("ValidationException",
                    "Unknown VariableCheck operator", 400);
        };
    }

    /** Resolves {@code #{variables.name}} references against the execution's variables. */
    private String resolveVariableReference(CodePipelineExecution execution, String reference) {
        String name = reference;
        if (reference.startsWith("#{") && reference.endsWith("}")) {
            name = reference.substring(2, reference.length() - 1);
            if (name.startsWith("variables.")) {
                name = name.substring("variables.".length());
            }
        }
        for (Map<String, String> variable : execution.getVariables()) {
            if (variable.get("name").equals(name)) {
                return variable.getOrDefault("resolvedValue", "");
            }
        }
        // An unresolved reference must fail the rule, not compare as its own placeholder text.
        throw new AwsException("ValidationException",
                "VariableCheck variable " + reference + " could not be resolved", 400);
    }

    private void failForCondition(CodePipelineExecution execution, String stageName, String conditionType) {
        execution.setStatus("Failed");
        execution.setStatusSummary(
                "Condition " + conditionType + " failed in stage " + stageName + ".");
        putExecution(execution);
    }

    private void finishExecutionRun(CodePipelineExecution execution) {
        startLocks.withLock(lockKey(execution), () -> {
            try {
                execution.setCurrentStage(null);
                execution.setLastUpdateTime(now());
                putExecution(execution);
                boolean pipelineDeleted = pipelineStore.getForAccount(execution.getAccountId(),
                        pipelineKey(execution.getRegion(), execution.getPipelineName())).isEmpty();
                if (pipelineDeleted) {
                    clearRuntimeArtifacts(execution);
                } else if ("Failed".equals(execution.getStatus()) || "Stopped".equals(execution.getStatus())) {
                    if ("Failed".equals(execution.getStatus())) {
                        releaseArtifactsOutdatedBy(execution);
                    }
                    releaseArtifactsIfAlreadyOutdated(execution);
                } else {
                    clearRuntimeArtifacts(execution);
                }
            } finally {
                activeRuns.remove(runKey(execution));
            }
        });
        String terminalState = switch (execution.getStatus()) {
            case "Succeeded" -> "SUCCEEDED";
            case "Failed" -> "FAILED";
            case "Stopped" -> "STOPPED";
            default -> null;
        };
        if (terminalState != null) {
            eventPublisher.pipelineStateChange(execution, terminalState);
        }
    }

    // Failed and stopped executions keep their artifacts so a stage retry can reuse them. Once a newer
    // execution fails the same stage, the older one can no longer be retried there, so its artifacts go.
    // An older execution whose runner is still finishing is skipped here and releases itself when it ends.
    private void releaseArtifactsOutdatedBy(CodePipelineExecution failed) {
        executions(failed.getAccountId(), failed.getRegion(), failed.getPipelineName()).stream()
                .dropWhile(candidate -> !failed.getPipelineExecutionId().equals(candidate.getPipelineExecutionId()))
                .skip(1)
                .filter(older -> "Failed".equals(older.getStatus()) || "Stopped".equals(older.getStatus()))
                .filter(older -> !older.isArtifactsReleased() && !activeRuns.contains(runKey(older)))
                .filter(older -> outdatedBy(older, failed))
                .forEach(this::releaseArtifacts);
    }

    private void releaseArtifactsIfAlreadyOutdated(CodePipelineExecution execution) {
        boolean outdated = executions(execution.getAccountId(), execution.getRegion(), execution.getPipelineName())
                .stream()
                .takeWhile(candidate -> !execution.getPipelineExecutionId().equals(candidate.getPipelineExecutionId()))
                .anyMatch(newer -> outdatedBy(execution, newer));
        if (outdated) {
            releaseArtifacts(execution);
        }
    }

    private boolean outdatedBy(CodePipelineExecution older, CodePipelineExecution newer) {
        return older.getStageExecutionStatuses().entrySet().stream()
                .filter(entry -> "Failed".equals(entry.getValue()) || "Stopped".equals(entry.getValue()))
                .anyMatch(entry -> latestActionStatuses(newer, entry.getKey()).containsValue("Failed"));
    }

    private void releaseArtifacts(CodePipelineExecution execution) {
        clearRuntimeArtifacts(execution);
        execution.setArtifactsReleased(true);
        putExecution(execution);
    }

    private void runStage(CodePipelinePipeline pipeline, CodePipelineExecution execution, JsonNode stage) {
        runStage(pipeline, execution, stage, "ALL_ACTIONS", Map.of());
    }

    private void runStage(CodePipelinePipeline pipeline, CodePipelineExecution execution, JsonNode stage,
                          String retryMode, Map<String, String> previousStatuses) {
        Map<Integer, List<JsonNode>> groups = new LinkedHashMap<>();
        for (JsonNode action : stage.path("actions")) {
            groups.computeIfAbsent(action.path("runOrder").asInt(1), ignored -> new ArrayList<>()).add(action);
        }
        groups.entrySet().stream().sorted(Map.Entry.comparingByKey()).forEach(entry -> {
            if ("Failed".equals(execution.getStatus()) || execution.isStopRequested()) {
                return;
            }
            List<JsonNode> actions = entry.getValue().stream()
                    .filter(action -> shouldRunRetriedAction(action, retryMode, previousStatuses))
                    .toList();
            List<CompletableFuture<Void>> futures = actions.stream()
                    .map(action -> CompletableFuture.runAsync(
                            () -> runAction(pipeline, execution, stage.path("name").asText(), action), executor))
                    .toList();
            CompletableFuture.allOf(futures.toArray(CompletableFuture[]::new)).join();
            if (futures.stream().anyMatch(CompletableFuture::isCompletedExceptionally)) {
                execution.setStatus("Failed");
            }
        });
    }

    private boolean shouldRunRetriedAction(JsonNode action, String retryMode, Map<String, String> previousStatuses) {
        if ("ALL_ACTIONS".equals(retryMode)) {
            return true;
        }
        return !"Succeeded".equals(previousStatuses.get(action.path("name").asText()));
    }

    private void runAction(CodePipelinePipeline pipeline, CodePipelineExecution execution,
                           String stageName, JsonNode action) {
        ActionExecution state = new ActionExecution();
        state.setActionExecutionId(UUID.randomUUID().toString());
        state.setStageName(stageName);
        state.setActionName(action.path("name").asText());
        JsonNode type = action.path("actionTypeId");
        state.setCategory(type.path("category").asText());
        state.setOwner(type.path("owner").asText());
        state.setProvider(type.path("provider").asText());
        state.setRunOrder(action.path("runOrder").asInt(1));
        state.setStatus("InProgress");
        state.setStartTime(now());
        state.setLastUpdateTime(state.getStartTime());
        synchronized (execution) {
            execution.getActionExecutions().add(state);
            putExecution(execution);
        }
        eventPublisher.actionStateChange(execution, state, "STARTED");
        try {
            executeProvider(pipeline, execution, action, state);
            if ("InProgress".equals(state.getStatus())) {
                state.setStatus("Succeeded");
            }
            if (state.getSummary() == null) {
                state.setSummary("Action completed.");
            }
        } catch (Exception e) {
            state.setStatus("Failed");
            state.setSummary(e.getMessage());
            state.setErrorDetails(Map.of(
                    "code", e instanceof AwsException aws ? aws.getErrorCode() : "ActionExecutionFailed",
                    "message", Objects.toString(e.getMessage(), "Action failed")));
            execution.setStatus("Failed");
            execution.setStatusSummary("Action " + state.getActionName() + " failed: " + state.getSummary());
        } finally {
            state.setLastUpdateTime(now());
            putExecution(execution);
            String terminalState = switch (state.getStatus()) {
                case "Succeeded" -> "SUCCEEDED";
                case "Failed" -> "FAILED";
                case "Abandoned" -> "ABANDONED";
                default -> null;
            };
            if (terminalState != null) {
                eventPublisher.actionStateChange(execution, state, terminalState);
            }
        }
    }

    private void executeProvider(CodePipelinePipeline pipeline, CodePipelineExecution execution,
                                 JsonNode action, ActionExecution state) throws InterruptedException {
        String category = state.getCategory();
        String owner = state.getOwner();
        String provider = state.getProvider();
        if ("Approval".equals(category) && "Manual".equals(provider)) {
            waitForApproval(execution, action, state);
            return;
        }
        if ("Source".equals(category) && "GitHub".equals(provider) && "ThirdParty".equals(owner)
                && "1".equals(action.path("actionTypeId").path("version").asText())) {
            executeGitHubSource(execution, action, state);
            return;
        }
        if (!"AWS".equals(owner)) {
            waitForCustomJob(pipeline, execution, action, state);
            return;
        }
        switch (provider) {
            case "S3" -> executeS3(execution, action, state);
            case "CodeBuild" -> executeCodeBuild(execution, action, state);
            case "CodeDeploy" -> executeCodeDeploy(execution, action, state);
            case "Lambda" -> executeLambda(execution, action, state);
            case "CodePipeline" -> executeNestedPipeline(execution, action, state);
            default -> throw new AwsException("InvalidActionDeclarationException",
                    "Provider " + provider + " is not available in Floci CodePipeline", 400);
        }
    }

    private void executeS3(CodePipelineExecution execution, JsonNode action, ActionExecution state) {
        JsonNode config = action.path("configuration");
        if ("Source".equals(state.getCategory())) {
            String bucket = config.path("S3Bucket").asText(config.path("BucketName").asText(null));
            String key = effectiveS3SourceKey(execution, state.getActionName(), config);
            String versionId = sourceRevisionOverride(
                    execution, state.getActionName(), "S3_OBJECT_VERSION_ID");
            S3Object object = s3Service.getObject(bucket, key, versionId);
            for (JsonNode artifact : action.path("outputArtifacts")) {
                runtimeArtifacts.put(artifactKey(execution, artifact.path("name").asText()), object.getData());
            }
            String revisionId = object.getVersionId() != null
                    ? object.getVersionId() : unquoteETag(object.getETag());
            synchronized (execution) {
                Map<String, Object> revision = new LinkedHashMap<>();
                revision.put("name", state.getActionName());
                revision.put("revisionId", revisionId);
                revision.put("revisionChangeIdentifier", unquoteETag(object.getETag()));
                revision.put("revisionSummary", bucket + "/" + key);
                revision.put("revisionUrl", "s3://" + bucket + "/" + key);
                revision.put("created", object.getLastModified().toEpochMilli() / 1000.0);
                execution.getArtifactRevisions().add(revision);
                upsertSourceRevision(execution, state.getActionName(), revisionId, bucket, key, object);
                pinResolvedS3Version(execution, state.getActionName(), object.getVersionId());
            }
            state.setExternalExecutionId(revisionId);
            return;
        }
        String bucket = config.path("BucketName").asText(config.path("S3Bucket").asText(null));
        String objectKey = config.path("ObjectKey").asText(state.getActionName() + ".zip");
        JsonNode input = action.path("inputArtifacts").path(0);
        byte[] data = runtimeArtifacts.get(artifactKey(execution, input.path("name").asText()));
        if (data == null) {
            throw new AwsException("InvalidJobStateException", "Input artifact is not available", 400);
        }
        s3Service.putObject(bucket, objectKey, data, "application/zip", Map.of());
        state.setExternalExecutionId("s3://" + bucket + "/" + objectKey);
    }

    /**
     * The ThirdParty GitHub (version 1) source action: fetches the branch archive from
     * github.com and publishes it as the output artifact with the repo contents at the
     * artifact root, the layout the real action produces.
     */
    private void executeGitHubSource(CodePipelineExecution execution, JsonNode action,
                                     ActionExecution state) {
        JsonNode config = action.path("configuration");
        String repoOwner = config.path("Owner").asText(null);
        String repo = config.path("Repo").asText(null);
        String branch = config.path("Branch").asText("main");
        if (repoOwner == null || repo == null) {
            throw new AwsException("InvalidActionDeclarationException",
                    "GitHub source actions require Owner and Repo", 400);
        }
        if (!isValidGitHubPathSegment(repoOwner) || !isValidGitHubPathSegment(repo)
                || !isValidGitHubRef(branch)) {
            throw new AwsException("InvalidActionDeclarationException",
                    "GitHub source Owner and Repo must be non-empty and contain no '/' or '..', "
                            + "and Branch must be non-empty with no leading '/', '..' or whitespace", 400);
        }
        String pinnedCommit = sourceRevisionOverride(execution, state.getActionName(), GITHUB_COMMIT_ID);
        String archiveRef = pinnedCommit != null
                ? pinnedCommit : "refs/heads/" + encodeRefPath(branch);
        byte[] archive = fetchGitHubArchive(URI.create(
                githubArchiveBaseUrl() + "/" + repoOwner + "/" + repo + "/zip/" + archiveRef));
        if (archive.length > maxArchiveBytes()) {
            throw archiveDownloadTooLarge();
        }
        String commitSha = archiveCommitSha(archive);
        byte[] artifact = stripTopLevelDirectory(archive);
        for (JsonNode output : action.path("outputArtifacts")) {
            runtimeArtifacts.put(artifactKey(execution, output.path("name").asText()), artifact);
        }
        Map<String, Object> revision = new LinkedHashMap<>();
        revision.put("name", state.getActionName());
        String summary = "github.com/" + repoOwner + "/" + repo + "@" + branch;
        String commitUrl = "https://github.com/" + repoOwner + "/" + repo + "/commit/" + commitSha;
        revision.put("revisionId", commitSha);
        revision.put("revisionChangeIdentifier", commitSha);
        revision.put("revisionSummary", summary);
        revision.put("revisionUrl", commitUrl);
        revision.put("created", now());
        Map<String, Object> sourceRevision = new LinkedHashMap<>();
        sourceRevision.put("actionName", state.getActionName());
        sourceRevision.put("revisionId", commitSha);
        sourceRevision.put("revisionSummary", summary);
        sourceRevision.put("revisionUrl", commitUrl);
        synchronized (execution) {
            execution.getArtifactRevisions().add(revision);
            execution.getSourceRevisions().removeIf(
                    existing -> state.getActionName().equals(existing.get("actionName")));
            execution.getSourceRevisions().add(sourceRevision);
        }
        state.setExternalExecutionId(commitSha);
        state.setExternalExecutionUrl("https://github.com/" + repoOwner + "/" + repo);
    }

    /** Percent-encodes each '/'-separated ref segment so '#', '%' and the like stay in the path. */
    private static String encodeRefPath(String ref) {
        List<String> segments = new ArrayList<>();
        for (String segment : ref.split("/", -1)) {
            segments.add(URLEncoder.encode(segment, StandardCharsets.UTF_8).replace("+", "%20"));
        }
        return String.join("/", segments);
    }

    /**
     * codeload writes the commit SHA into the ZIP end-of-central-directory comment; that is the
     * revision a GitHub source artifact reports. Anything else is not a codeload archive.
     */
    private static String archiveCommitSha(byte[] zip) {
        int min = Math.max(0, zip.length - 22 - 0xFFFF);
        for (int i = zip.length - 22; i >= min; i--) {
            if (zip[i] == 0x50 && zip[i + 1] == 0x4b && zip[i + 2] == 0x05 && zip[i + 3] == 0x06) {
                int len = (zip[i + 20] & 0xFF) | ((zip[i + 21] & 0xFF) << 8);
                if (i + 22 + len <= zip.length) {
                    String comment = new String(zip, i + 22, len, StandardCharsets.US_ASCII).trim();
                    if (comment.matches("[0-9a-fA-F]{40}")) {
                        return comment.toLowerCase(Locale.ROOT);
                    }
                }
                break;
            }
        }
        throw new AwsException("ActionExecutionFailed",
                "GitHub source archive does not carry a commit SHA in its ZIP comment", 400);
    }

    /** repoOwner/repo path segments: no '/', no '..', non-empty. */
    private static boolean isValidGitHubPathSegment(String segment) {
        return !segment.isEmpty() && !segment.contains("/") && !segment.contains("..");
    }

    /** Branch ref: slashes are legal (e.g. {@code release/v1.16.0}), but no '..' traversal,
     * leading slash, or whitespace/control characters that could reshape the request path. */
    private static boolean isValidGitHubRef(String ref) {
        return !ref.isEmpty() && !ref.startsWith("/") && !ref.contains("..")
                && ref.chars().noneMatch(Character::isWhitespace);
    }

    /** Overridable seam for tests; production goes to github.com with the JVM's proxy settings. */
    byte[] fetchGitHubArchive(URI uri) {
        try {
            HttpClient client = HttpClient.newBuilder()
                    .connectTimeout(Duration.ofSeconds(10))
                    .followRedirects(HttpClient.Redirect.NORMAL)
                    .build();
            HttpResponse<InputStream> response = client.send(
                    HttpRequest.newBuilder(uri)
                            .timeout(Duration.ofSeconds(30))
                            .GET().build(),
                    HttpResponse.BodyHandlers.ofInputStream());
            try (InputStream body = response.body()) {
                if (response.statusCode() != 200) {
                    throw new AwsException("ActionExecutionFailed",
                            "GitHub source download returned HTTP " + response.statusCode()
                                    + " for " + uri, 400);
                }
                if (response.headers().firstValueAsLong("Content-Length").orElse(0L) > maxArchiveBytes()) {
                    throw archiveDownloadTooLarge();
                }
                ByteArrayOutputStream buffer = new ByteArrayOutputStream();
                byte[] chunk = new byte[8192];
                long total = 0;
                int n;
                while ((n = body.read(chunk)) >= 0) {
                    total += n;
                    if (total > maxArchiveBytes()) {
                        throw archiveDownloadTooLarge();
                    }
                    buffer.write(chunk, 0, n);
                }
                return buffer.toByteArray();
            }
        } catch (AwsException e) {
            throw e;
        } catch (Exception e) {
            throw new AwsException("ActionExecutionFailed",
                    "GitHub source download failed: " + e.getMessage(), 400);
        }
    }

    private AwsException archiveDownloadTooLarge() {
        return new AwsException("ActionExecutionFailed",
                "GitHub source archive exceeds the maximum archive download size of "
                        + maxArchiveBytes() + " bytes", 400);
    }

    /**
     * GitHub's codeload archives wrap the repo in a {@code <repo>-<branch>/} directory;
     * the real source artifact has the repo contents at the root.
     */
    private byte[] stripTopLevelDirectory(byte[] zip) {
        try {
            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            try (ZipFile zipFile = ZipFile.builder()
                    .setSeekableByteChannel(new SeekableInMemoryByteChannel(zip)).get();
                 ZipArchiveOutputStream zos = new ZipArchiveOutputStream(baos)) {
                Enumeration<ZipArchiveEntry> entries = zipFile.getEntriesInPhysicalOrder();
                int entryCount = 0;
                long uncompressedTotal = 0;
                while (entries.hasMoreElements()) {
                    ZipArchiveEntry entry = entries.nextElement();
                    if (++entryCount > maxEntries()) {
                        throw new AwsException("ActionExecutionFailed",
                                "GitHub source archive has more than the maximum of "
                                        + maxEntries() + " entries", 400);
                    }
                    if (entry.getName().startsWith("/")) {
                        throw new AwsException("ActionExecutionFailed",
                                "GitHub source archive contains an escaping entry: " + entry.getName(), 400);
                    }
                    int slash = entry.getName().indexOf('/');
                    if (slash < 0 || slash == entry.getName().length() - 1) {
                        continue;
                    }
                    String stripped = entry.getName().substring(slash + 1);
                    if (isEscapingPath(stripped)) {
                        throw new AwsException("ActionExecutionFailed",
                                "GitHub source archive contains an escaping entry: " + entry.getName(), 400);
                    }
                    if (entry.isDirectory()) {
                        continue;
                    }
                    // Symlink entries (unix mode S_IFLNK, content = link target) are not
                    // directories, so they flow through this copy path: the unix mode and
                    // the content are kept as-is in the repackaged artifact. This only
                    // preserves the entries; the current CodeBuild extractZip writes every
                    // entry as a plain file and does not recreate symlinks.
                    ZipArchiveEntry copy = new ZipArchiveEntry(stripped);
                    if (entry.getUnixMode() != 0) {
                        copy.setUnixMode(entry.getUnixMode());
                    }
                    zos.putArchiveEntry(copy);
                    boolean symlink = (entry.getUnixMode() & 0xF000) == 0xA000;
                    ByteArrayOutputStream linkTarget = new ByteArrayOutputStream();
                    CRC32 crc = new CRC32();
                    try (InputStream in = openEntryStream(zipFile, entry)) {
                        byte[] chunk = new byte[8192];
                        int n;
                        while ((n = in.read(chunk)) >= 0) {
                            uncompressedTotal += n;
                            if (uncompressedTotal > maxUncompressedBytes()) {
                                throw new AwsException("ActionExecutionFailed",
                                        "GitHub source archive exceeds the maximum uncompressed size of "
                                                + maxUncompressedBytes() + " bytes", 400);
                            }
                            crc.update(chunk, 0, n);
                            if (symlink) {
                                linkTarget.write(chunk, 0, n);
                            }
                            zos.write(chunk, 0, n);
                        }
                    }
                    if (entry.getCrc() != -1 && crc.getValue() != entry.getCrc()) {
                        throw new AwsException("ActionExecutionFailed",
                                "GitHub source archive entry fails its checksum: " + entry.getName(), 400);
                    }
                    if (symlink && isEscapingSymlink(stripped, linkTarget.toString(StandardCharsets.UTF_8))) {
                        throw new AwsException("ActionExecutionFailed",
                                "GitHub source archive contains an escaping symlink: " + entry.getName(), 400);
                    }
                    zos.closeArchiveEntry();
                }
            }
            return baos.toByteArray();
        } catch (AwsException e) {
            throw e;
        } catch (Exception e) {
            throw new AwsException("ActionExecutionFailed",
                    "Could not repackage GitHub archive: " + e.getMessage(), 400);
        }
    }

    private static boolean isEscapingPath(String name) {
        if (name.startsWith("/") || name.startsWith("\\")) {
            return true;
        }
        for (String segment : name.split("[/\\\\]")) {
            if (segment.equals("..")) {
                return true;
            }
        }
        return false;
    }

    /** A symlink target is escaping if absolute or if, resolved against the link's directory, it leaves the root. */
    private static boolean isEscapingSymlink(String linkName, String target) {
        if (target.startsWith("/") || target.startsWith("\\")) {
            return true;
        }
        Path root = Path.of("/root");
        Path linkDir = root.resolve(linkName).getParent();
        return !linkDir.resolve(target).normalize().startsWith(root);
    }

    /** Ends the Inflater when the stream closes; InflaterInputStream leaves a caller-supplied one open. */
    private static final class EndingInflaterInputStream extends InflaterInputStream {
        private EndingInflaterInputStream(InputStream in) {
            super(in, new Inflater(true));
        }

        @Override
        public void close() throws IOException {
            try {
                super.close();
            } finally {
                inf.end();
            }
        }
    }

    // ZipFile's decoding stream accessor dispatches on entry.getMethod() through a switch that also
    // covers BZIP2, DEFLATE64, XZ and ZSTD, instantiating the matching CompressorInputStream
    // inline; GraalVM's static analysis resolves every branch of that switch because the method
    // itself is reachable, not just the branch a given entry happens to take, and XZ/ZSTD need
    // org.tukaani:xz / com.github.luben:zstd-jni on the classpath at native link time to do so.
    // GitHub archives are ordinary zips (STORED or DEFLATED only), so decoding just those two
    // methods off the entry's raw bytes avoids the dispatch method and the optional codecs.
    private static InputStream openEntryStream(ZipFile zipFile, ZipArchiveEntry entry) throws IOException {
        InputStream raw = zipFile.getRawInputStream(entry);
        return switch (entry.getMethod()) {
            case ZipEntry.STORED -> raw;
            case ZipEntry.DEFLATED -> new EndingInflaterInputStream(raw);
            default -> throw new IOException(
                    "Unsupported zip compression method " + entry.getMethod() + " for entry " + entry.getName());
        };
    }

    private void executeCodeBuild(CodePipelineExecution execution, JsonNode action,
                                  ActionExecution state) throws InterruptedException {
        String projectName = action.path("configuration").path("ProjectName").asText(null);
        Build build = codeBuildService.startBuild(execution.getRegion(), execution.getAccountId(), projectName,
                null, null, null, null, null, null, null);
        state.setExternalExecutionId(build.getId());
        while (!Boolean.TRUE.equals(build.getBuildComplete())) {
            if (abandonExternalActionIfRequested(execution, state)) {
                return;
            }
            TimeUnit.MILLISECONDS.sleep(POLL_INTERVAL_MS);
            build = codeBuildService.getBuild(execution.getRegion(), execution.getAccountId(), build.getId());
        }
        if (abandonExternalActionIfRequested(execution, state)) {
            return;
        }
        if (!"SUCCEEDED".equals(build.getBuildStatus())) {
            String message = "CodeBuild build " + build.getId() + " finished with " + build.getBuildStatus();
            if (recordExternalActionFailureWhileStopping(execution, state, message)) {
                return;
            }
            throw new AwsException("ActionExecutionFailed", message, 400);
        }
    }

    private void executeCodeDeploy(CodePipelineExecution execution, JsonNode action,
                                   ActionExecution state) throws InterruptedException {
        JsonNode config = action.path("configuration");
        Map<String, Object> revision = null;
        JsonNode input = action.path("inputArtifacts").path(0);
        if (!input.isMissingNode()) {
            revision = Map.of("revisionType", "S3",
                    "s3Location", Map.of("bucket", artifactBucket(action), "key", input.path("name").asText()));
        }
        String deploymentId = codeDeployService.createDeployment(
                execution.getRegion(), config.path("ApplicationName").asText(),
                config.path("DeploymentGroupName").asText(), null, revision, "CodePipeline execution");
        state.setExternalExecutionId(deploymentId);
        Deployment deployment = codeDeployService.getDeployment(execution.getRegion(), deploymentId);
        while (!List.of("Succeeded", "Failed", "Stopped").contains(deployment.getStatus())) {
            if (abandonExternalActionIfRequested(execution, state)) {
                return;
            }
            TimeUnit.MILLISECONDS.sleep(POLL_INTERVAL_MS);
            deployment = codeDeployService.getDeployment(execution.getRegion(), deploymentId);
        }
        if (abandonExternalActionIfRequested(execution, state)) {
            return;
        }
        if (!"Succeeded".equals(deployment.getStatus())) {
            String message = "CodeDeploy deployment finished with " + deployment.getStatus();
            if (recordExternalActionFailureWhileStopping(execution, state, message)) {
                return;
            }
            throw new AwsException("ActionExecutionFailed", message, 400);
        }
    }

    private boolean abandonExternalActionIfRequested(CodePipelineExecution execution, ActionExecution state) {
        if (!execution.isStopRequested() || !execution.isAbandon()) {
            return false;
        }
        state.setStatus("Abandoned");
        state.setSummary("Action abandoned.");
        return true;
    }

    private boolean recordExternalActionFailureWhileStopping(CodePipelineExecution execution,
                                                              ActionExecution state, String message) {
        if (!execution.isStopRequested()) {
            return false;
        }
        state.setStatus("Failed");
        state.setSummary(message);
        state.setErrorDetails(Map.of("code", "ActionExecutionFailed", "message", message));
        return true;
    }

    private void executeLambda(CodePipelineExecution execution, JsonNode action, ActionExecution state) {
        String functionName = action.path("configuration").path("FunctionName").asText(null);
        ObjectNode event = mapper.createObjectNode();
        ObjectNode cpJob = event.putObject("CodePipeline.job");
        cpJob.put("id", state.getActionExecutionId());
        cpJob.set("data", action.deepCopy());
        InvokeResult result = lambdaService.invoke(execution.getRegion(), functionName,
                event.toString().getBytes(StandardCharsets.UTF_8), InvocationType.RequestResponse);
        state.setExternalExecutionId(result.getRequestId());
        if (result.getFunctionError() != null || result.getStatusCode() >= 400) {
            throw new AwsException("ActionExecutionFailed",
                    "Lambda invocation failed: " + result.getFunctionError(), 400);
        }
    }

    private void executeNestedPipeline(CodePipelineExecution execution, JsonNode action, ActionExecution state) {
        String pipelineName = action.path("configuration").path("PipelineName").asText(null);
        ObjectNode request = mapper.createObjectNode().put("name", pipelineName);
        ObjectNode result = startPipelineExecution(request, execution.getRegion(), execution.getAccountId());
        state.setExternalExecutionId(result.path("pipelineExecutionId").asText());
    }

    private void waitForApproval(CodePipelineExecution execution, JsonNode action,
                                 ActionExecution state) throws InterruptedException {
        state.setToken(UUID.randomUUID().toString());
        state.setSummary("Waiting for approval.");
        putExecution(execution);
        String notificationArn = action.path("configuration").path("NotificationArn").asText(null);
        if (notificationArn != null && !notificationArn.isBlank()) {
            eventPublisher.approvalNeeded(execution, state, notificationArn,
                    action.path("configuration").path("CustomData").asText(null),
                    action.path("configuration").path("ExternalEntityLink").asText(null),
                    now() + TimeUnit.DAYS.toSeconds(7));
        }
        while ("InProgress".equals(state.getStatus()) && !execution.isStopRequested()) {
            TimeUnit.MILLISECONDS.sleep(POLL_INTERVAL_MS);
        }
        if (execution.isStopRequested()) {
            state.setStatus(execution.isAbandon() ? "Abandoned" : "Stopped");
        }
        if ("Failed".equals(state.getStatus())) {
            throw new AwsException("ActionExecutionFailed", state.getSummary(), 400);
        }
    }

    private void waitForCustomJob(CodePipelinePipeline pipeline, CodePipelineExecution execution,
                                  JsonNode action, ActionExecution state) throws InterruptedException {
        String jobId = UUID.randomUUID().toString();
        String nonce = UUID.randomUUID().toString();
        ObjectNode job = mapper.createObjectNode();
        job.put("id", jobId);
        job.put("nonce", nonce);
        ObjectNode data = job.putObject("data");
        data.set("actionConfiguration", mapper.createObjectNode().set("configuration", action.path("configuration")));
        data.set("pipelineContext", mapper.createObjectNode()
                .put("pipelineName", pipeline.getName())
                .put("pipelineExecutionId", execution.getPipelineExecutionId())
                .put("stageName", state.getStageName())
                .put("actionName", state.getActionName()));
        ObjectNode stored = mapper.createObjectNode();
        stored.put("actionTypeKey", actionTypeId(
                state.getCategory(), state.getOwner(), state.getProvider(),
                action.path("actionTypeId").path("version").asText()));
        stored.put("thirdParty", "ThirdParty".equals(state.getOwner()));
        stored.put("nonce", nonce);
        stored.set("job", job);
        storeItem(execution.getAccountId(), execution.getRegion(), "job", jobId, "Created", stored);
        state.setExternalExecutionId(jobId);
        while (!(execution.isStopRequested() && execution.isAbandon())) {
            CodePipelineStoredItem current = requireItem(
                    execution.getAccountId(), execution.getRegion(), "job", jobId, "JobNotFoundException");
            if ("Succeeded".equals(current.getStatus())) {
                JsonNode result = current.getData().path("result");
                if (result.has("outputVariables")) {
                    state.setOutputVariables(mapper.convertValue(
                            result.path("outputVariables"), new TypeReference<Map<String, String>>() {}));
                }
                return;
            }
            if ("Failed".equals(current.getStatus())) {
                String message = current.getData().path("result").path("failureDetails").path("message")
                        .asText("Custom action failed");
                if (execution.isStopRequested()) {
                    state.setStatus("Failed");
                    state.setSummary(message);
                    state.setErrorDetails(Map.of("code", "ActionExecutionFailed", "message", message));
                    return;
                }
                throw new AwsException("ActionExecutionFailed", message, 400);
            }
            TimeUnit.MILLISECONDS.sleep(POLL_INTERVAL_MS);
        }
        state.setStatus("Abandoned");
        state.setSummary("Action abandoned.");
    }

    private void applyExecutionMode(CodePipelineExecution execution) {
        if (!"SUPERSEDED".equals(execution.getExecutionMode())) {
            return;
        }
        executions(execution.getAccountId(), execution.getRegion(), execution.getPipelineName()).stream()
                .filter(e -> !e.getPipelineExecutionId().equals(execution.getPipelineExecutionId()))
                .filter(e -> "InProgress".equals(e.getStatus()) && e.getCurrentStage() == null)
                .forEach(e -> {
                    e.setStatus("Superseded");
                    e.setStatusSummary("Superseded by " + execution.getPipelineExecutionId());
                    e.setLastUpdateTime(now());
                    putExecution(e);
                });
    }

    private void waitForTransition(CodePipelinePipeline pipeline, CodePipelineExecution execution,
                                   String stageName) throws InterruptedException {
        while (!pipeline.getTransitions().getOrDefault(stageName, enabledTransition()).isEnabled()) {
            if (execution.isStopRequested()) {
                return;
            }
            TimeUnit.MILLISECONDS.sleep(POLL_INTERVAL_MS);
            pipeline = requirePipeline(execution.getAccountId(), execution.getRegion(), execution.getPipelineName());
        }
    }

    private boolean finishIfStopped(CodePipelineExecution execution) {
        if (!execution.isStopRequested()) {
            return false;
        }
        boolean anyRunning = execution.getActionExecutions().stream()
                .anyMatch(a -> "InProgress".equals(a.getStatus()));
        if (!execution.isAbandon() && anyRunning) {
            return false;
        }
        execution.setStatus("Stopped");
        execution.setStatusSummary("Pipeline execution stopped.");
        execution.setLastUpdateTime(now());
        if (execution.getCurrentStage() != null) {
            execution.getStageExecutionStatuses().put(execution.getCurrentStage(), "Stopped");
        }
        putExecution(execution);
        return true;
    }

    private void initializeTransitions(CodePipelinePipeline pipeline) {
        Map<String, TransitionState> transitions = pipeline.getTransitions() == null
                ? new LinkedHashMap<>() : pipeline.getTransitions();
        for (JsonNode stage : pipeline.getDeclaration().path("stages")) {
            transitions.computeIfAbsent(stage.path("name").asText(), ignored -> enabledTransition());
        }
        pipeline.setTransitions(transitions);
    }

    private CodePipelinePipeline requirePipeline(String account, String region, String name) {
        validatePipelineName(name);
        return pipelineStore.getForAccount(account, pipelineKey(region, name))
                .orElseThrow(() -> new AwsException("PipelineNotFoundException",
                        "Pipeline not found: " + name, 400));
    }

    private CodePipelinePipeline requirePipelineByArn(String account, String region, String arn) {
        return pipelineStore.scanForAccount(account, key -> key.startsWith(region + ":")).stream()
                .filter(p -> arn.equals(p.getArn()))
                .findFirst()
                .orElseThrow(() -> new AwsException("ResourceNotFoundException",
                        "Resource not found: " + arn, 400));
    }

    private CodePipelineExecution requireExecution(String account, String region,
                                                   String pipelineName, String executionId) {
        requirePipeline(account, region, pipelineName);
        return executionStore.getForAccount(account, executionKey(region, pipelineName, executionId))
                .orElseThrow(() -> new AwsException("PipelineExecutionNotFoundException",
                        "Pipeline execution does not exist: " + executionId, 400));
    }

    private List<CodePipelineExecution> executions(String account, String region, String pipelineName) {
        return executionStore.scanForAccount(
                account, key -> key.startsWith(region + ":" + pipelineName + ":")).stream()
                .sorted(Comparator.comparing(CodePipelineExecution::getStartTime,
                        Comparator.nullsLast(Comparator.reverseOrder())))
                .toList();
    }

    private void putPipeline(CodePipelinePipeline pipeline) {
        pipelineStore.putForAccount(
                pipeline.getAccountId(), pipelineKey(pipeline.getRegion(), pipeline.getName()), pipeline);
    }

    private void putExecution(CodePipelineExecution execution) {
        // Serializing the execution walks its rule records, which evaluateRule mutates under this lock.
        synchronized (execution) {
            executionStore.putForAccount(execution.getAccountId(),
                    executionKey(execution.getRegion(), execution.getPipelineName(),
                            execution.getPipelineExecutionId()), execution);
        }
    }

    private void storeItem(String account, String region, String type, String id,
                           String status, JsonNode data) {
        CodePipelineStoredItem item = new CodePipelineStoredItem();
        item.setAccountId(account);
        item.setRegion(region);
        item.setId(id);
        item.setType(type);
        item.setStatus(status);
        item.setCreated(now());
        item.setUpdated(item.getCreated());
        item.setData(data.deepCopy());
        putItem(item);
    }

    private void putItem(CodePipelineStoredItem item) {
        itemStore.putForAccount(item.getAccountId(),
                itemKey(item.getRegion(), item.getType(), item.getId()), item);
    }

    private CodePipelineStoredItem requireItem(String account, String region, String type,
                                               String id, String errorCode) {
        return itemStore.getForAccount(account, itemKey(region, type, id))
                .orElseThrow(() -> new AwsException(errorCode, type + " not found: " + id, 400));
    }

    private List<CodePipelineStoredItem> items(String account, String region, String type) {
        return itemStore.scanForAccount(account, key -> key.startsWith(region + ":" + type + ":")).stream()
                .sorted(Comparator.comparing(CodePipelineStoredItem::getCreated,
                        Comparator.nullsLast(Comparator.reverseOrder())))
                .toList();
    }

    private String validatePipelineDeclaration(JsonNode declaration) {
        if (declaration == null || !declaration.isObject()) {
            throw new AwsException("ValidationException", "pipeline must be an object", 400);
        }
        String name = declaration.path("name").asText(null);
        validatePipelineName(name);
        if (!declaration.hasNonNull("roleArn")) {
            throw new AwsException("ValidationException", "pipeline.roleArn is required", 400);
        }
        if (!declaration.path("stages").isArray() || declaration.path("stages").size() < 2) {
            throw new AwsException("InvalidStructureException", "A pipeline must contain at least two stages", 400);
        }
        boolean hasArtifactStore = declaration.hasNonNull("artifactStore");
        boolean hasArtifactStores = declaration.hasNonNull("artifactStores");
        if (hasArtifactStore == hasArtifactStores) {
            throw new AwsException("ValidationException",
                    "pipeline must include exactly one of artifactStore or artifactStores", 400);
        }
        for (JsonNode stage : declaration.path("stages")) {
            if (!stage.hasNonNull("name") || !stage.path("actions").isArray()
                    || stage.path("actions").isEmpty()) {
                throw new AwsException("InvalidStageDeclarationException",
                        "Each stage must have a name and at least one action", 400);
            }
        }
        String mode = declaration.path("executionMode").asText(DEFAULT_EXECUTION_MODE);
        String type = declaration.path("pipelineType").asText(DEFAULT_PIPELINE_TYPE);
        if (!List.of("SUPERSEDED", "QUEUED", "PARALLEL").contains(mode)) {
            throw new AwsException("ValidationException", "Invalid executionMode: " + mode, 400);
        }
        if (!"SUPERSEDED".equals(mode) && !"V2".equals(type)) {
            throw new AwsException("ValidationException", mode + " requires pipelineType V2", 400);
        }
        return name;
    }

    private JsonNode normalizeDeclaration(JsonNode declaration, int version) {
        ObjectNode copy = declaration.deepCopy();
        if (!copy.hasNonNull("executionMode")) {
            copy.put("executionMode", DEFAULT_EXECUTION_MODE);
        }
        if (!copy.hasNonNull("pipelineType")) {
            copy.put("pipelineType", DEFAULT_PIPELINE_TYPE);
        }
        copy.put("version", version);
        return copy;
    }

    private void validatePipelineName(String name) {
        if (name == null || !name.matches("[A-Za-z0-9.@_-]{1,100}")) {
            throw new AwsException("ValidationException", "pipeline name has invalid format", 400);
        }
    }

    private static void requireOnlyMembers(JsonNode node, String label, Set<String> allowed) {
        node.fieldNames().forEachRemaining(field -> {
            if (!allowed.contains(field)) {
                throw new AwsException("ValidationException", "Unknown " + label + " member: " + field, 400);
            }
        });
    }

    private JsonNode stageByName(CodePipelinePipeline pipeline, String stageName) {
        for (JsonNode stage : pipeline.getDeclaration().path("stages")) {
            if (stageName.equals(stage.path("name").asText())) {
                return stage;
            }
        }
        throw new AwsException("StageNotFoundException", "Stage not found: " + stageName, 400);
    }

    private int stageIndex(CodePipelinePipeline pipeline, String stageName) {
        JsonNode stages = pipeline.getDeclaration().path("stages");
        for (int i = 0; i < stages.size(); i++) {
            if (stageName.equals(stages.get(i).path("name").asText())) {
                return i;
            }
        }
        throw new AwsException("StageNotFoundException", "Stage not found: " + stageName, 400);
    }

    private Map<String, String> latestActionStatuses(CodePipelineExecution execution, String stageName) {
        Map<String, String> statuses = new LinkedHashMap<>();
        synchronized (execution) {
            for (ActionExecution action : execution.getActionExecutions()) {
                if (stageName.equals(action.getStageName())) {
                    statuses.put(action.getActionName(), action.getStatus());
                }
            }
        }
        return statuses;
    }

    private ActionExecution latestActionExecution(CodePipelineExecution execution, String stageName,
                                                  String actionName) {
        ActionExecution latest = null;
        synchronized (execution) {
            for (ActionExecution action : execution.getActionExecutions()) {
                if (stageName.equals(action.getStageName()) && actionName.equals(action.getActionName())) {
                    latest = action;
                }
            }
        }
        return latest;
    }

    private void requireStage(CodePipelinePipeline pipeline, String stageName) {
        stageByName(pipeline, stageName);
    }

    private void requireAction(CodePipelinePipeline pipeline, String stageName, String actionName) {
        for (JsonNode stage : pipeline.getDeclaration().path("stages")) {
            if (stageName.equals(stage.path("name").asText())) {
                for (JsonNode action : stage.path("actions")) {
                    if (actionName.equals(action.path("name").asText())) {
                        return;
                    }
                }
                throw new AwsException("ActionNotFoundException", "Action not found: " + actionName, 400);
            }
        }
        throw new AwsException("StageNotFoundException", "Stage not found: " + stageName, 400);
    }

    private ObjectNode executionNode(CodePipelineExecution execution) {
        ObjectNode node;
        synchronized (execution) {
            node = mapper.valueToTree(execution);
        }
        node.remove(List.of("accountId", "region", "startTime", "lastUpdateTime",
                "sourceRevisions", "sourceRevisionOverrides", "actionExecutions", "currentStage",
                "stopRequested", "abandon", "rollbackTargetPipelineExecutionId", "rollbackStageName",
                "stageExecutionStatuses", "artifactsReleased", "ruleExecutions", "conditionOverrides"));
        if (execution.getRollbackTargetPipelineExecutionId() != null) {
            node.putObject("rollbackMetadata").put(
                    "rollbackTargetPipelineExecutionId", execution.getRollbackTargetPipelineExecutionId());
        }
        return node;
    }

    private ObjectNode executionSummaryNode(CodePipelineExecution execution) {
        ObjectNode node = mapper.createObjectNode();
        node.put("pipelineExecutionId", execution.getPipelineExecutionId());
        node.put("status", execution.getStatus());
        putIfNotNull(node, "statusSummary", execution.getStatusSummary());
        putIfNotNull(node, "startTime", execution.getStartTime());
        putIfNotNull(node, "lastUpdateTime", execution.getLastUpdateTime());
        node.put("executionMode", execution.getExecutionMode());
        node.put("executionType", execution.getExecutionType());
        synchronized (execution) {
            node.set("sourceRevisions", mapper.valueToTree(execution.getSourceRevisions()));
        }
        node.set("trigger", mapper.valueToTree(execution.getTrigger()));
        if (execution.getRollbackTargetPipelineExecutionId() != null) {
            node.putObject("rollbackMetadata").put(
                    "rollbackTargetPipelineExecutionId", execution.getRollbackTargetPipelineExecutionId());
        }
        return node;
    }

    private ObjectNode actionDetailNode(ActionExecution action) {
        ObjectNode detail = mapper.createObjectNode();
        detail.put("actionExecutionId", action.getActionExecutionId());
        detail.put("pipelineExecutionId", pipelineExecutionId(action));
        detail.put("stageName", action.getStageName());
        detail.put("actionName", action.getActionName());
        detail.put("status", action.getStatus());
        putIfNotNull(detail, "startTime", action.getStartTime());
        putIfNotNull(detail, "lastUpdateTime", action.getLastUpdateTime());
        ObjectNode input = detail.putObject("input");
        input.putObject("actionTypeId")
                .put("category", action.getCategory())
                .put("owner", action.getOwner())
                .put("provider", action.getProvider())
                .put("version", "1");
        ObjectNode output = detail.putObject("output");
        ObjectNode result = output.putObject("executionResult");
        putIfNotNull(result, "externalExecutionId", action.getExternalExecutionId());
        putIfNotNull(result, "externalExecutionSummary", action.getSummary());
        putIfNotNull(result, "externalExecutionUrl", action.getExternalExecutionUrl());
        output.set("outputVariables", mapper.valueToTree(action.getOutputVariables()));
        if (action.getErrorDetails() != null) {
            result.set("errorDetails", mapper.valueToTree(action.getErrorDetails()));
        }
        return detail;
    }

    private ObjectNode actionStateNode(ActionExecution action) {
        ObjectNode node = mapper.createObjectNode();
        node.put("actionExecutionId", action.getActionExecutionId());
        node.put("status", action.getStatus());
        putIfNotNull(node, "lastStatusChange", action.getLastUpdateTime());
        putIfNotNull(node, "summary", action.getSummary());
        putIfNotNull(node, "externalExecutionId", action.getExternalExecutionId());
        putIfNotNull(node, "externalExecutionUrl", action.getExternalExecutionUrl());
        if ("InProgress".equals(action.getStatus())) {
            node.put("percentComplete", 50);
            putIfNotNull(node, "token", action.getToken());
        } else {
            node.put("percentComplete", 100);
        }
        return node;
    }

    private String pipelineExecutionId(ActionExecution action) {
        return executionStore.scanAllAccounts().stream()
                .filter(e -> e.getActionExecutions().stream()
                        .anyMatch(a -> action.getActionExecutionId().equals(a.getActionExecutionId())))
                .map(CodePipelineExecution::getPipelineExecutionId)
                .findFirst().orElse("");
    }

    private List<ActionExecution> actionExecutionsForStage(CodePipelineExecution execution, String stage) {
        return execution.getActionExecutions().stream()
                .filter(a -> stage.equals(a.getStageName()))
                .toList();
    }

    private boolean hasStageExecution(CodePipelineExecution execution, String stage) {
        return execution.getStageExecutionStatuses().containsKey(stage)
                || !actionExecutionsForStage(execution, stage).isEmpty();
    }

    private String stageExecutionStatus(CodePipelineExecution execution, String stage) {
        List<ActionExecution> actions = actionExecutionsForStage(execution, stage);
        if (latestActionStatuses(execution, stage).containsValue("Failed")) {
            return "Failed";
        }
        String trackedStatus = execution.getStageExecutionStatuses().get(stage);
        if (trackedStatus != null) {
            return trackedStatus;
        }
        if (actions.stream().anyMatch(action -> "InProgress".equals(action.getStatus()))) {
            return "InProgress";
        }
        if (actions.stream().anyMatch(action -> "Abandoned".equals(action.getStatus()))) {
            return "Stopped";
        }
        if (actions.stream().allMatch(action -> "Succeeded".equals(action.getStatus()))) {
            return "Succeeded";
        }
        return "Failed";
    }

    private List<Map<String, Object>> sourceRevisionOverrides(JsonNode request, CodePipelinePipeline pipeline) {
        JsonNode overridesNode = request.get("sourceRevisions");
        if (overridesNode == null || overridesNode.isNull()) {
            return new ArrayList<>();
        }
        if (!overridesNode.isArray()) {
            throw new AwsException("ValidationException", "sourceRevisions must be an array", 400);
        }
        List<Map<String, Object>> overrides = new ArrayList<>();
        Set<String> seenOverrides = new HashSet<>();
        for (JsonNode override : overridesNode) {
            if (!override.isObject()) {
                throw new AwsException("ValidationException", "sourceRevisions entries must be objects", 400);
            }
            requireOnlyMembers(override, "sourceRevisions entry",
                    Set.of("actionName", "revisionType", "revisionValue"));
            String actionName = text(override, "actionName");
            String revisionType = text(override, "revisionType");
            String revisionValue = text(override, "revisionValue");
            if (!seenOverrides.add(actionName + "\0" + revisionType)) {
                throw new AwsException("ValidationException",
                        "Duplicate " + revisionType + " override for source action " + actionName, 400);
            }
            JsonNode action = sourceActionByName(pipeline, actionName);
            String provider = action.path("actionTypeId").path("provider").asText();
            if (!"S3".equals(provider)) {
                throw new AwsException("ValidationException",
                        "Source revision overrides are not supported for provider " + provider, 400);
            }
            if (!List.of("S3_OBJECT_KEY", "S3_OBJECT_VERSION_ID").contains(revisionType)) {
                throw new AwsException("ValidationException",
                        "Invalid S3 source revision type: " + revisionType, 400);
            }
            if ("S3_OBJECT_KEY".equals(revisionType)
                    && !Boolean.parseBoolean(action.path("configuration")
                            .path("AllowOverrideForS3ObjectKey").asText("false"))) {
                throw new AwsException("ValidationException",
                        "S3_OBJECT_KEY override requires AllowOverrideForS3ObjectKey=true", 400);
            }
            Map<String, Object> value = new LinkedHashMap<>();
            value.put("actionName", actionName);
            value.put("revisionType", revisionType);
            value.put("revisionValue", revisionValue);
            overrides.add(value);
        }
        return overrides;
    }

    private JsonNode sourceActionByName(CodePipelinePipeline pipeline, String actionName) {
        for (JsonNode stage : pipeline.getDeclaration().path("stages")) {
            for (JsonNode action : stage.path("actions")) {
                if ("Source".equals(action.path("actionTypeId").path("category").asText())
                        && actionName.equals(action.path("name").asText())) {
                    return action;
                }
            }
        }
        throw new AwsException("ValidationException", "Source action not found: " + actionName, 400);
    }

    private String effectiveS3SourceKey(CodePipelineExecution execution, String actionName, JsonNode config) {
        String override = sourceRevisionOverride(execution, actionName, "S3_OBJECT_KEY");
        return override != null
                ? override : config.path("S3ObjectKey").asText(config.path("ObjectKey").asText(null));
    }

    private String sourceRevisionOverride(CodePipelineExecution execution, String actionName, String revisionType) {
        synchronized (execution) {
            for (Map<String, Object> override : execution.getSourceRevisionOverrides()) {
                if (actionName.equals(override.get("actionName"))
                        && revisionType.equals(override.get("revisionType"))) {
                    return Objects.toString(override.get("revisionValue"), null);
                }
            }
            return null;
        }
    }

    private void pinResolvedS3Version(CodePipelineExecution execution, String actionName, String versionId) {
        if (versionId == null || sourceRevisionOverride(execution, actionName, "S3_OBJECT_VERSION_ID") != null) {
            return;
        }
        Map<String, Object> pin = new LinkedHashMap<>();
        pin.put("actionName", actionName);
        pin.put("revisionType", "S3_OBJECT_VERSION_ID");
        pin.put("revisionValue", versionId);
        execution.getSourceRevisionOverrides().add(pin);
    }

    private void upsertSourceRevision(CodePipelineExecution execution, String actionName, String revisionId,
                                      String bucket, String key, S3Object object) {
        Map<String, Object> sourceRevision = new LinkedHashMap<>();
        sourceRevision.put("actionName", actionName);
        sourceRevision.put("revisionId", revisionId);
        String summary = object.getMetadata().get("codepipeline-artifact-revision-summary");
        if (summary != null && !summary.isBlank()) {
            sourceRevision.put("revisionSummary", summary);
        }
        sourceRevision.put("revisionUrl", "s3://" + bucket + "/" + key);
        execution.getSourceRevisions().removeIf(existing -> actionName.equals(existing.get("actionName")));
        execution.getSourceRevisions().add(sourceRevision);
    }

    /** The request's variables, plus the declared default of every pipeline variable it omits. */
    private List<Map<String, String>> variableList(JsonNode node, JsonNode declared) {
        List<Map<String, String>> result = new ArrayList<>();
        Set<String> supplied = new HashSet<>();
        if (node != null && node.isArray()) {
            for (JsonNode variable : node) {
                supplied.add(variable.path("name").asText());
                result.add(Map.of(
                        "name", variable.path("name").asText(),
                        "resolvedValue", variable.path("value").asText()));
            }
        }
        if (declared != null && declared.isArray()) {
            for (JsonNode variable : declared) {
                String name = variable.path("name").asText();
                if (!supplied.contains(name) && variable.hasNonNull("defaultValue")) {
                    result.add(Map.of("name", name, "resolvedValue", variable.path("defaultValue").asText()));
                }
            }
        }
        return result;
    }

    private Map<String, String> parseTags(JsonNode node) {
        Map<String, String> tags = new LinkedHashMap<>();
        if (node != null && node.isArray()) {
            for (JsonNode tag : node) {
                tags.put(tag.path("key").asText(), tag.path("value").asText(""));
            }
        }
        if (tags.size() > 50) {
            throw new AwsException("TooManyTagsException", "A resource can have at most 50 tags", 400);
        }
        return tags;
    }

    private ArrayNode tagsNode(Map<String, String> tags) {
        ArrayNode node = mapper.createArrayNode();
        tags.forEach((key, value) -> node.addObject().put("key", key).put("value", value));
        return node;
    }

    private Page page(JsonNode request, int size, int maximum) {
        int start = 0;
        if (request.hasNonNull("nextToken")) {
            try {
                start = Integer.parseInt(request.path("nextToken").asText());
            } catch (NumberFormatException e) {
                throw new AwsException("InvalidNextTokenException", "Invalid nextToken", 400);
            }
        }
        int requested = request.hasNonNull("maxResults") ? request.path("maxResults").asInt(maximum) : maximum;
        if (requested < 1 || start < 0 || start > size) {
            throw new AwsException("InvalidNextTokenException", "Invalid pagination parameters", 400);
        }
        return new Page(start, Math.min(size, start + Math.min(requested, maximum)));
    }

    private void addNextToken(ObjectNode response, Page page, int size) {
        if (page.end() < size) {
            response.put("nextToken", Integer.toString(page.end()));
        }
    }

    private ObjectNode emptyPage(String field) {
        ObjectNode response = mapper.createObjectNode();
        response.putArray(field);
        return response;
    }

    private static String text(JsonNode request, String field) {
        String value = request.path(field).asText(null);
        if (value == null || value.isBlank()) {
            throw new AwsException("ValidationException", field + " is required", 400);
        }
        return value;
    }

    private static String actionTypeId(String category, String owner, String provider, String version) {
        return String.join(":", category, owner, provider, version);
    }

    private static String itemKey(String region, String type, String id) {
        return region + ":" + type + ":" + id;
    }

    private static String pipelineKey(String region, String name) {
        return region + ":" + name;
    }

    private static String executionKey(String region, String pipelineName, String executionId) {
        return region + ":" + pipelineName + ":" + executionId;
    }

    private static String lockKey(CodePipelineExecution execution) {
        return pipelineLockKey(execution.getAccountId(), execution.getRegion(), execution.getPipelineName());
    }

    private static String pipelineLockKey(String account, String region, String pipelineName) {
        return account + ":" + region + ":" + pipelineName;
    }

    private static String artifactKey(CodePipelineExecution execution, String artifactName) {
        return execution.getAccountId() + ":" + execution.getPipelineExecutionId() + ":" + artifactName;
    }

    private static String artifactBucket(JsonNode action) {
        return action.path("configuration").path("BucketName").asText("codepipeline-artifacts");
    }

    private static String runKey(CodePipelineExecution execution) {
        return execution.getAccountId() + ":" + execution.getPipelineExecutionId();
    }

    private void clearRuntimeArtifacts(CodePipelineExecution execution) {
        String prefix = execution.getAccountId() + ":" + execution.getPipelineExecutionId() + ":";
        runtimeArtifacts.keySet().removeIf(key -> key.startsWith(prefix));
    }

    private static boolean isTerminal(String status) {
        return List.of("Succeeded", "Failed", "Stopped", "Superseded", "Cancelled").contains(status);
    }

    private static TransitionState enabledTransition() {
        TransitionState state = new TransitionState();
        state.setEnabled(true);
        return state;
    }

    private static double now() {
        return Instant.now().toEpochMilli() / 1000.0;
    }

    private static void putIfNotNull(ObjectNode node, String field, Object value) {
        if (value == null) {
            return;
        }
        if (value instanceof String string) {
            node.put(field, string);
        } else if (value instanceof Double number) {
            node.put(field, number);
        } else if (value instanceof Integer number) {
            node.put(field, number);
        } else if (value instanceof JsonNode jsonNode) {
            node.set(field, jsonNode);
        }
    }

    @PreDestroy
    void shutdown() {
        sourcePoller.shutdownNow();
        executor.shutdownNow();
        try {
            if (!executor.awaitTermination(5, TimeUnit.SECONDS)) {
                LOG.warn("CodePipeline execution workers did not stop within the shutdown timeout");
            }
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            LOG.warn("Interrupted while stopping CodePipeline execution workers", exception);
        }
    }

    private record Page(int start, int end) {
    }
}
