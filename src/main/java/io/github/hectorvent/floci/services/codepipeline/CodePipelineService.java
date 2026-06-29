package io.github.hectorvent.floci.services.codepipeline;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.hectorvent.floci.core.common.AwsArnUtils;
import io.github.hectorvent.floci.core.common.AwsException;
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
import jakarta.annotation.PreDestroy;
import jakarta.annotation.PostConstruct;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

@ApplicationScoped
public class CodePipelineService {
    private static final Logger LOG = Logger.getLogger(CodePipelineService.class);
    private static final String DEFAULT_EXECUTION_MODE = "SUPERSEDED";
    private static final String DEFAULT_PIPELINE_TYPE = "V1";
    private static final long POLL_INTERVAL_MS = 100L;

    private final AccountAwareStorageBackend<CodePipelinePipeline> pipelineStore;
    private final AccountAwareStorageBackend<CodePipelineExecution> executionStore;
    private final AccountAwareStorageBackend<CodePipelineStoredItem> itemStore;
    private final ObjectMapper mapper;
    private final CodeBuildService codeBuildService;
    private final CodeDeployService codeDeployService;
    private final LambdaService lambdaService;
    private final S3Service s3Service;
    private final ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();
    private final Map<String, Object> pipelineLocks = new ConcurrentHashMap<>();
    private final Map<String, byte[]> runtimeArtifacts = new ConcurrentHashMap<>();

    @Inject
    @SuppressWarnings("unchecked")
    public CodePipelineService(StorageFactory storageFactory, ObjectMapper mapper,
                               CodeBuildService codeBuildService, CodeDeployService codeDeployService,
                               LambdaService lambdaService, S3Service s3Service) {
        this.pipelineStore = (AccountAwareStorageBackend<CodePipelinePipeline>) storageFactory.create(
                "codepipeline", "codepipeline-pipelines.json", new TypeReference<Map<String, CodePipelinePipeline>>() {});
        this.executionStore = (AccountAwareStorageBackend<CodePipelineExecution>) storageFactory.create(
                "codepipeline", "codepipeline-executions.json", new TypeReference<Map<String, CodePipelineExecution>>() {});
        this.itemStore = (AccountAwareStorageBackend<CodePipelineStoredItem>) storageFactory.create(
                "codepipeline", "codepipeline-items.json", new TypeReference<Map<String, CodePipelineStoredItem>>() {});
        this.mapper = mapper;
        this.codeBuildService = codeBuildService;
        this.codeDeployService = codeDeployService;
        this.lambdaService = lambdaService;
        this.s3Service = s3Service;
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
            case "ListRuleTypes" -> emptyPage("ruleTypes");
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
                        putExecution(execution);
                        executor.submit(() -> runExecution(pipeline, execution));
                    }, () -> {
                        execution.setStatus("Failed");
                        execution.setStatusSummary("Pipeline definition was not found after restart.");
                        execution.setLastUpdateTime(now());
                        putExecution(execution);
                    });
        }
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
        putPipeline(pipeline);
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
        putPipeline(pipeline);
        ObjectNode response = mapper.createObjectNode();
        response.set("pipeline", pipeline.getDeclaration());
        return response;
    }

    private ObjectNode getPipelineResponse(JsonNode request, String region, String account) {
        CodePipelinePipeline pipeline = requirePipeline(account, region, text(request, "name"));
        int version = request.path("version").asInt(pipeline.getVersion());
        if (version != pipeline.getVersion()) {
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
        pipelineStore.deleteForAccount(account, pipelineKey(region, name));
        for (String key : executionStore.keysForAccount(account)) {
            if (key.startsWith(region + ":" + name + ":")) {
                executionStore.deleteForAccount(account, key);
            }
        }
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
        execution.setSourceRevisions(objectList(request.path("sourceRevisions")));
        execution.setVariables(variableList(request.path("variables")));
        Map<String, String> trigger = new LinkedHashMap<>();
        trigger.put("triggerType", "StartPipelineExecution");
        trigger.put("triggerDetail", "manual");
        if (clientToken != null) {
            trigger.put("clientRequestToken", clientToken);
        }
        execution.setTrigger(trigger);
        putExecution(execution);
        applyExecutionMode(execution);
        executor.submit(() -> runExecution(pipeline, execution));
        return mapper.createObjectNode().put("pipelineExecutionId", execution.getPipelineExecutionId());
    }

    private ObjectNode stopPipelineExecution(JsonNode request, String region, String account) {
        CodePipelineExecution execution = requireExecution(
                account, region, text(request, "pipelineName"), text(request, "pipelineExecutionId"));
        if (isTerminal(execution.getStatus())) {
            throw new AwsException("PipelineExecutionNotStoppableException",
                    "Pipeline execution is already in a terminal state", 400);
        }
        execution.setStopRequested(true);
        execution.setAbandon(request.path("abandon").asBoolean(false));
        execution.setStatus("Stopping");
        execution.setStatusSummary(request.path("reason").asText("Stop requested."));
        execution.setLastUpdateTime(now());
        if (execution.isAbandon()) {
            execution.getActionExecutions().stream()
                    .filter(a -> "InProgress".equals(a.getStatus()))
                    .forEach(a -> {
                        a.setStatus("Abandoned");
                        a.setLastUpdateTime(now());
                    });
        }
        putExecution(execution);
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
        JsonNode filter = request.path("filter");
        if (filter.hasNonNull("succeededInStage")) {
            String stage = filter.path("succeededInStage").asText();
            executions = executions.stream().filter(e -> actionExecutionsForStage(e, stage).stream()
                    .allMatch(a -> "Succeeded".equals(a.getStatus()))).toList();
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
        CodePipelineExecution latest = executions(account, region, pipeline.getName()).stream().findFirst().orElse(null);
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
                    latest.getActionExecutions().stream()
                            .filter(a -> stageName.equals(a.getStageName()) && actionName.equals(a.getActionName()))
                            .findFirst()
                            .ifPresent(a -> actionState.set("latestExecution", actionStateNode(a)));
                }
            }
            if (latest != null && stageName.equals(latest.getCurrentStage())) {
                ObjectNode latestExecution = state.putObject("latestExecution");
                latestExecution.put("pipelineExecutionId", latest.getPipelineExecutionId());
                latestExecution.put("status", latest.getStatus());
                latestExecution.put("type", latest.getExecutionType());
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
        String status = request.path("result").path("status").asText();
        CodePipelineExecution execution = executions(account, region, pipelineName).stream()
                .filter(e -> e.getActionExecutions().stream()
                        .anyMatch(a -> stageName.equals(a.getStageName())
                                && actionName.equals(a.getActionName())
                                && token.equals(a.getToken())
                                && "InProgress".equals(a.getStatus())))
                .findFirst()
                .orElseThrow(() -> new AwsException(
                        "InvalidApprovalTokenException", "Approval token is invalid", 400));
        ActionExecution approval = execution.getActionExecutions().stream()
                .filter(a -> stageName.equals(a.getStageName()) && actionName.equals(a.getActionName()))
                .filter(a -> token.equals(a.getToken()) && "InProgress".equals(a.getStatus()))
                .findFirst()
                .orElseThrow();
        approval.setStatus("Approved".equals(status) ? "Succeeded" : "Failed");
        approval.setSummary(request.path("result").path("summary").asText(status));
        approval.setLastUpdateTime(now());
        putExecution(execution);
        return mapper.createObjectNode().put("approvedAt", now());
    }

    private ObjectNode retryStageExecution(JsonNode request, String region, String account) {
        String pipelineName = text(request, "pipelineName");
        CodePipelineExecution source = requireExecution(
                account, region, pipelineName, text(request, "pipelineExecutionId"));
        ObjectNode start = mapper.createObjectNode();
        start.put("name", pipelineName);
        start.set("sourceRevisions", mapper.valueToTree(source.getSourceRevisions()));
        ArrayNode vars = start.putArray("variables");
        source.getVariables().forEach(v -> vars.addObject()
                .put("name", v.get("name"))
                .put("value", v.get("resolvedValue")));
        return startPipelineExecution(start, region, account);
    }

    private ObjectNode rollbackStage(JsonNode request, String region, String account) {
        CodePipelineExecution target = requireExecution(
                account, region, text(request, "pipelineName"), text(request, "targetPipelineExecutionId"));
        ObjectNode started = retryStageExecution(mapper.createObjectNode()
                .put("pipelineName", target.getPipelineName())
                .put("pipelineExecutionId", target.getPipelineExecutionId()), region, account);
        CodePipelineExecution rollback = requireExecution(
                account, region, target.getPipelineName(), started.path("pipelineExecutionId").asText());
        rollback.setExecutionType("ROLLBACK");
        rollback.setRollbackTargetPipelineExecutionId(target.getPipelineExecutionId());
        putExecution(rollback);
        return started;
    }

    private ObjectNode overrideStageCondition(JsonNode request, String region, String account) {
        requireExecution(account, region, text(request, "pipelineName"), text(request, "pipelineExecutionId"));
        return mapper.createObjectNode();
    }

    private ObjectNode listRuleExecutions(JsonNode request, String region, String account) {
        requirePipeline(account, region, text(request, "pipelineName"));
        return emptyPage("ruleExecutionDetails");
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

    private void runExecution(CodePipelinePipeline pipeline, CodePipelineExecution execution) {
        Runnable work = () -> {
            try {
                for (JsonNode stage : pipeline.getDeclaration().path("stages")) {
                    waitForTransition(pipeline, execution, stage.path("name").asText());
                    if (finishIfStopped(execution)) {
                        return;
                    }
                    execution.setCurrentStage(stage.path("name").asText());
                    execution.setLastUpdateTime(now());
                    putExecution(execution);
                    runStage(pipeline, execution, stage);
                    if ("Failed".equals(execution.getStatus()) || finishIfStopped(execution)) {
                        return;
                    }
                }
                execution.setStatus("Succeeded");
                execution.setStatusSummary("Pipeline execution succeeded.");
            } catch (Exception e) {
                execution.setStatus("Failed");
                execution.setStatusSummary(e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage());
                LOG.errorf(e, "CodePipeline execution %s failed", execution.getPipelineExecutionId());
            } finally {
                execution.setCurrentStage(null);
                execution.setLastUpdateTime(now());
                putExecution(execution);
                clearRuntimeArtifacts(execution);
            }
        };
        if ("QUEUED".equals(execution.getExecutionMode())) {
            synchronized (pipelineLocks.computeIfAbsent(lockKey(execution), ignored -> new Object())) {
                work.run();
            }
        } else {
            work.run();
        }
    }

    private void runStage(CodePipelinePipeline pipeline, CodePipelineExecution execution, JsonNode stage) {
        Map<Integer, List<JsonNode>> groups = new LinkedHashMap<>();
        for (JsonNode action : stage.path("actions")) {
            groups.computeIfAbsent(action.path("runOrder").asInt(1), ignored -> new ArrayList<>()).add(action);
        }
        groups.entrySet().stream().sorted(Map.Entry.comparingByKey()).forEach(entry -> {
            if ("Failed".equals(execution.getStatus()) || execution.isStopRequested()) {
                return;
            }
            List<CompletableFuture<Void>> futures = entry.getValue().stream()
                    .map(action -> CompletableFuture.runAsync(
                            () -> runAction(pipeline, execution, stage.path("name").asText(), action), executor))
                    .toList();
            CompletableFuture.allOf(futures.toArray(CompletableFuture[]::new)).join();
            if (futures.stream().anyMatch(CompletableFuture::isCompletedExceptionally)) {
                execution.setStatus("Failed");
            }
        });
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
        }
    }

    private void executeProvider(CodePipelinePipeline pipeline, CodePipelineExecution execution,
                                 JsonNode action, ActionExecution state) throws InterruptedException {
        String category = state.getCategory();
        String owner = state.getOwner();
        String provider = state.getProvider();
        if ("Approval".equals(category) && "Manual".equals(provider)) {
            waitForApproval(execution, state);
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
            String key = config.path("S3ObjectKey").asText(config.path("ObjectKey").asText(null));
            S3Object object = s3Service.getObject(bucket, key);
            for (JsonNode artifact : action.path("outputArtifacts")) {
                runtimeArtifacts.put(artifactKey(execution, artifact.path("name").asText()), object.getData());
            }
            Map<String, Object> revision = new LinkedHashMap<>();
            revision.put("name", state.getActionName());
            revision.put("revisionId", object.getVersionId() != null ? object.getVersionId() : object.getETag());
            revision.put("revisionChangeIdentifier", object.getETag());
            revision.put("revisionSummary", bucket + "/" + key);
            revision.put("revisionUrl", "s3://" + bucket + "/" + key);
            revision.put("created", object.getLastModified().toEpochMilli() / 1000.0);
            execution.getArtifactRevisions().add(revision);
            state.setExternalExecutionId(revision.get("revisionId").toString());
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

    private void executeCodeBuild(CodePipelineExecution execution, JsonNode action,
                                  ActionExecution state) throws InterruptedException {
        String projectName = action.path("configuration").path("ProjectName").asText(null);
        Build build = codeBuildService.startBuild(execution.getRegion(), execution.getAccountId(), projectName,
                null, null, null, null, null, null, null);
        state.setExternalExecutionId(build.getId());
        while (!Boolean.TRUE.equals(build.getBuildComplete())) {
            if (execution.isStopRequested()) {
                codeBuildService.stopBuild(execution.getRegion(), build.getId());
                break;
            }
            TimeUnit.MILLISECONDS.sleep(POLL_INTERVAL_MS);
            build = codeBuildService.getBuild(execution.getRegion(), build.getId());
        }
        if (!"SUCCEEDED".equals(build.getBuildStatus())) {
            throw new AwsException("ActionExecutionFailed",
                    "CodeBuild build " + build.getId() + " finished with " + build.getBuildStatus(), 400);
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
            if (execution.isStopRequested()) {
                codeDeployService.stopDeployment(execution.getRegion(), deploymentId);
            }
            TimeUnit.MILLISECONDS.sleep(POLL_INTERVAL_MS);
            deployment = codeDeployService.getDeployment(execution.getRegion(), deploymentId);
        }
        if (!"Succeeded".equals(deployment.getStatus())) {
            throw new AwsException("ActionExecutionFailed",
                    "CodeDeploy deployment finished with " + deployment.getStatus(), 400);
        }
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

    private void waitForApproval(CodePipelineExecution execution, ActionExecution state) throws InterruptedException {
        state.setToken(UUID.randomUUID().toString());
        state.setSummary("Waiting for approval.");
        putExecution(execution);
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
        while (!execution.isStopRequested()) {
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
                throw new AwsException("ActionExecutionFailed",
                        current.getData().path("result").path("failureDetails").path("message")
                                .asText("Custom action failed"), 400);
            }
            TimeUnit.MILLISECONDS.sleep(POLL_INTERVAL_MS);
        }
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
        executionStore.putForAccount(execution.getAccountId(),
                executionKey(execution.getRegion(), execution.getPipelineName(),
                        execution.getPipelineExecutionId()), execution);
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

    private void requireStage(CodePipelinePipeline pipeline, String stageName) {
        for (JsonNode stage : pipeline.getDeclaration().path("stages")) {
            if (stageName.equals(stage.path("name").asText())) {
                return;
            }
        }
        throw new AwsException("StageNotFoundException", "Stage not found: " + stageName, 400);
    }

    private ObjectNode executionNode(CodePipelineExecution execution) {
        ObjectNode node = mapper.valueToTree(execution);
        node.remove(List.of("accountId", "region", "startTime", "lastUpdateTime",
                "sourceRevisions", "actionExecutions", "currentStage", "stopRequested", "abandon",
                "rollbackTargetPipelineExecutionId"));
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
        node.set("sourceRevisions", mapper.valueToTree(execution.getSourceRevisions()));
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

    private List<Map<String, Object>> objectList(JsonNode node) {
        if (node == null || !node.isArray()) {
            return new ArrayList<>();
        }
        return mapper.convertValue(node, new TypeReference<List<Map<String, Object>>>() {});
    }

    private List<Map<String, String>> variableList(JsonNode node) {
        List<Map<String, String>> result = new ArrayList<>();
        if (node != null && node.isArray()) {
            for (JsonNode variable : node) {
                result.add(Map.of(
                        "name", variable.path("name").asText(),
                        "resolvedValue", variable.path("value").asText()));
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
        return execution.getAccountId() + ":" + execution.getRegion() + ":" + execution.getPipelineName();
    }

    private static String artifactKey(CodePipelineExecution execution, String artifactName) {
        return execution.getAccountId() + ":" + execution.getPipelineExecutionId() + ":" + artifactName;
    }

    private static String artifactBucket(JsonNode action) {
        return action.path("configuration").path("BucketName").asText("codepipeline-artifacts");
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
        executor.shutdownNow();
    }

    private record Page(int start, int end) {
    }
}
