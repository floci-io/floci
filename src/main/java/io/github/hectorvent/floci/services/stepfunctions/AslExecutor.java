package io.github.hectorvent.floci.services.stepfunctions;

import io.github.hectorvent.floci.core.common.AwsArnUtils;
import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.core.common.AwsErrorResponse;
import io.github.hectorvent.floci.core.common.RequestContext;
import io.github.hectorvent.floci.core.common.XmlParser;
import io.github.hectorvent.floci.services.cloudformation.CloudFormationQueryHandler;
import io.github.hectorvent.floci.services.dynamodb.DynamoDbJsonHandler;
import io.github.hectorvent.floci.services.dynamodb.DynamoDbService;
import io.github.hectorvent.floci.services.ec2.Ec2Service;
import io.github.hectorvent.floci.services.ecs.EcsJsonHandler;
import io.github.hectorvent.floci.services.ecs.EcsService;
import io.github.hectorvent.floci.services.ecs.model.ContainerDefinition;
import io.github.hectorvent.floci.services.ecs.model.ContainerOverride;
import io.github.hectorvent.floci.services.ecs.model.EcsTask;
import io.github.hectorvent.floci.services.ecs.model.NetworkConfiguration;
import io.github.hectorvent.floci.services.ecs.model.TaskDefinition;
import io.github.hectorvent.floci.services.ecs.model.LaunchType;
import io.github.hectorvent.floci.services.s3.S3Service;
import io.github.hectorvent.floci.services.lambda.LambdaExecutorService;
import io.github.hectorvent.floci.services.lambda.LambdaFunctionStore;
import io.github.hectorvent.floci.services.lambda.model.InvocationType;
import io.github.hectorvent.floci.services.lambda.model.InvokeResult;
import io.github.hectorvent.floci.services.lambda.model.LambdaFunction;
import io.github.hectorvent.floci.services.s3.S3Service;
import io.github.hectorvent.floci.services.s3.model.S3Object;
import io.github.hectorvent.floci.services.sqs.SqsJsonHandler;
import io.github.hectorvent.floci.services.stepfunctions.model.Execution;
import io.github.hectorvent.floci.services.stepfunctions.model.HistoryEvent;
import io.github.hectorvent.floci.services.stepfunctions.model.StateMachine;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.MissingNode;
import com.fasterxml.jackson.databind.node.NullNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.quarkus.arc.Arc;
import io.quarkus.arc.ArcContainer;
import io.quarkus.arc.ManagedContext;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;
import jakarta.ws.rs.core.MultivaluedHashMap;
import jakarta.ws.rs.core.MultivaluedMap;
import org.jboss.logging.Logger;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.BiConsumer;

@ApplicationScoped
public class AslExecutor {

    private enum MapItemsSource {
        DEFAULT,
        ITEM_READER_ARRAY,
        ITEM_READER_OBJECT
    }

    private record ResolvedMapItems(JsonNode items, MapItemsSource source) {
    }

    private static final Logger LOG = Logger.getLogger(AslExecutor.class);
    private static final int MAX_WAIT_SECONDS = 30;

    // ecs:runTask.sync polling — wait up to ~60s for the task to reach STOPPED.
    private static final int ECS_SYNC_POLL_ATTEMPTS = 600;
    private static final long ECS_SYNC_POLL_INTERVAL_MS = 100;

    private static final String QUERY_LANGUAGE_JSONATA = "JSONata";

    private final LambdaExecutorService lambdaExecutor;
    private final LambdaFunctionStore functionStore;
    private final DynamoDbService dynamoDbService;
    private final DynamoDbJsonHandler dynamoDbJsonHandler;
    private final SqsJsonHandler sqsJsonHandler;
    private final CloudFormationQueryHandler cloudFormationHandler;
    private final Ec2Service ec2Service;
    private final S3Service s3Service;
    private final EcsService ecsService;
    private final EcsJsonHandler ecsJsonHandler;
    private final ObjectMapper objectMapper;
    private final JsonataEvaluator jsonataEvaluator;
    private final Instance<StepFunctionsService> sfnService;
    private final ExecutorService executor = Executors.newCachedThreadPool(r -> {
        Thread t = new Thread(r, "sfn-executor");
        t.setDaemon(true);
        return t;
    });

    @Inject
    public AslExecutor(LambdaExecutorService lambdaExecutor, LambdaFunctionStore functionStore,
                       DynamoDbService dynamoDbService, DynamoDbJsonHandler dynamoDbJsonHandler,
                       SqsJsonHandler sqsJsonHandler, CloudFormationQueryHandler cloudFormationHandler,
                       Ec2Service ec2Service, S3Service s3Service,
                       EcsService ecsService, EcsJsonHandler ecsJsonHandler,
                       ObjectMapper objectMapper, JsonataEvaluator jsonataEvaluator,
                       Instance<StepFunctionsService> sfnService) {
        this.lambdaExecutor = lambdaExecutor;
        this.functionStore = functionStore;
        this.dynamoDbService = dynamoDbService;
        this.dynamoDbJsonHandler = dynamoDbJsonHandler;
        this.sqsJsonHandler = sqsJsonHandler;
        this.cloudFormationHandler = cloudFormationHandler;
        this.ec2Service = ec2Service;
        this.s3Service = s3Service;
        this.ecsService = ecsService;
        this.ecsJsonHandler = ecsJsonHandler;
        this.objectMapper = objectMapper;
        this.jsonataEvaluator = jsonataEvaluator;
        this.sfnService = sfnService;
    }

    /**
     * Launches execution asynchronously. Calls onUpdate when execution status changes.
     */
    public void executeAsync(StateMachine sm, Execution exec, List<HistoryEvent> history,
                             BiConsumer<Execution, List<HistoryEvent>> onUpdate) {
        executor.submit(() -> runUnderExecutionAccount(sm, () -> doExecute(sm, exec, history, onUpdate)));
    }

    /**
     * Runs execution synchronously on the calling thread. Blocks until the execution completes.
     */
    public void executeSync(StateMachine sm, Execution exec, List<HistoryEvent> history,
                            BiConsumer<Execution, List<HistoryEvent>> onUpdate) {
        try {
            Future<?> f = executor.submit(() ->
                    runUnderExecutionAccount(sm, () -> doExecute(sm, exec, history, onUpdate)));
            f.get(300, TimeUnit.SECONDS);
        } catch (java.util.concurrent.TimeoutException e) {
            exec.setStatus("TIMED_OUT");
            exec.setStopDate(System.currentTimeMillis() / 1000.0);
            onUpdate.accept(exec, history);
        } catch (Exception e) {
            LOG.warnv("Sync execution wait failed for {0}: {1}", exec.getExecutionArn(), e.getMessage());
        }
    }

    /**
     * Runs {@code body} on this worker thread under a CDI request scope whose account is
     * the one encoded in the state machine ARN, so service integrations (Lambda, DynamoDB,
     * SQS, ECS, …) and the execution-store writes resolve to the execution's account rather
     * than the configured default. Without this, an execution started under account B would
     * have its integrations run against account A's resources.
     *
     * <p>Mirrors {@code CurEmissionScheduler#runUnderAccount}. Falls back to running the body
     * directly when Arc is not running (e.g. plain unit tests that construct AslExecutor
     * without a CDI container).
     */
    private void runUnderExecutionAccount(StateMachine sm, Runnable body) {
        try {
            callUnderExecutionAccount(sm, () -> {
                body.run();
                return null;
            });
        } catch (RuntimeException | Error e) {
            throw e;
        } catch (Exception e) {
            // A Runnable cannot throw a checked exception, so this is unreachable in practice;
            // wrap defensively to preserve the void signature.
            throw new RuntimeException(e);
        }
    }

    /**
     * Callable variant of {@link #runUnderExecutionAccount} that returns the body's result. Used to
     * run Parallel branches on their own worker threads under the execution's account: the request
     * scope (and thus {@link RequestContext}) is thread-bound, so a branch submitted to the executor
     * pool would otherwise run with no active scope and resolve its Task integrations against the
     * default account instead of the execution's. Each branch thread therefore activates its own
     * scope here, mirroring how {@link #executeAsync}/{@link #executeSync} wrap {@code doExecute}.
     */
    private <T> T callUnderExecutionAccount(StateMachine sm, Callable<T> body) throws Exception {
        String accountId = AwsArnUtils.accountOrDefault(sm.getStateMachineArn(), null);
        ArcContainer container = Arc.container();
        if (accountId == null || accountId.isBlank() || container == null || !container.isRunning()) {
            return body.call();
        }
        ManagedContext requestContext = container.requestContext();
        boolean alreadyActive = requestContext.isActive();
        if (!alreadyActive) {
            requestContext.activate();
        }
        // Execution runs on a background worker that normally has no active scope. If it did run
        // inside an already-active scope, restore its previous account afterwards so we don't leave
        // the execution's account behind on a reused thread.
        RequestContext ctx = container.instance(RequestContext.class).get();
        String previousAccountId = alreadyActive ? ctx.getAccountId() : null;
        try {
            ctx.setAccountId(accountId);
            return body.call();
        } finally {
            if (!alreadyActive) {
                requestContext.terminate();
            } else {
                ctx.setAccountId(previousAccountId);
            }
        }
    }

    private void doExecute(StateMachine sm, Execution exec, List<HistoryEvent> history,
                           BiConsumer<Execution, List<HistoryEvent>> onUpdate) {
        try {
            AtomicLong eventId = new AtomicLong(history.size());
            JsonNode definition = objectMapper.readTree(sm.getDefinition());
            JsonNode states = definition.path("States");
            String startAt = definition.path("StartAt").asText();
            String topLevelQueryLanguage = definition.path("QueryLanguage").asText("JSONPath");
            JsonNode currentInput = parseInput(exec.getInput());
            JsonNode execContext = buildContext(exec, sm);

            String currentStateName = startAt;
            while (currentStateName != null) {
                JsonNode stateDef = states.path(currentStateName);
                if (stateDef.isMissingNode()) {
                    throw new RuntimeException("State not found: " + currentStateName);
                }

                String type = stateDef.path("Type").asText();
                addEvent(history, eventId, stateEnteredEventType(type), null,
                        Map.of("name", currentStateName, "input", currentInput.toString()));

                // Update per-state context fields
                updateStateContext(execContext, currentStateName);

                try {
                    boolean jsonata = isJsonata(stateDef, topLevelQueryLanguage);
                    StateResult stateResult = executeState(currentStateName, type, stateDef, currentInput,
                            history, eventId, sm, jsonata, topLevelQueryLanguage, execContext);
                    addEvent(history, eventId, stateExitedEventType(type), eventId.get() - 1,
                            Map.of("name", currentStateName, "output", stateResult.output().toString()));

                    currentInput = stateResult.output();
                    currentStateName = stateResult.nextState();

                    if ("Succeed".equals(type) || stateDef.path("End").asBoolean(false)) {
                        currentStateName = null;
                    }
                } catch (FailStateException e) {
                    StateResult caught = handleCatch(stateDef, currentInput, e);
                    if (caught != null) {
                        addEvent(history, eventId, stateExitedEventType(type), eventId.get() - 1,
                                Map.of("name", currentStateName, "output", caught.output().toString()));
                        currentInput = caught.output();
                        currentStateName = caught.nextState();
                        continue;
                    }
                    failExecution(exec, history, eventId, e);
                    onUpdate.accept(exec, history);
                    return;
                } catch (Exception e) {
                    exec.setStatus("FAILED");
                    exec.setStopDate(System.currentTimeMillis() / 1000.0);
                    String runtimeError = "States.Runtime";
                    String runtimeCause = e.getMessage() != null ? e.getMessage() : "Unknown error";
                    exec.setError(runtimeError);
                    exec.setCause(runtimeCause);
                    addEvent(history, eventId, "ExecutionFailed", null,
                            Map.of("error", runtimeError, "cause", runtimeCause));
                    onUpdate.accept(exec, history);
                    return;
                }
            }

            exec.setStatus("SUCCEEDED");
            exec.setOutput(currentInput.toString());
            exec.setStopDate(System.currentTimeMillis() / 1000.0);
            addEvent(history, eventId, "ExecutionSucceeded", null,
                    Map.of("output", currentInput.toString()));
            onUpdate.accept(exec, history);

        } catch (Exception e) {
            LOG.warnv("ASL execution failed for {0}: {1}", exec.getExecutionArn(), e.getMessage());
            exec.setStatus("FAILED");
            exec.setStopDate(System.currentTimeMillis() / 1000.0);
            onUpdate.accept(exec, history);
        }
    }

    private StateResult executeState(String name, String type, JsonNode stateDef, JsonNode input,
                                     List<HistoryEvent> history, AtomicLong eventId, StateMachine sm,
                                     boolean jsonata, String topLevelQueryLanguage, JsonNode context) throws Exception {
        return switch (type) {
            case "Pass" -> executePassState(stateDef, input, jsonata, context);
            case "Task" -> executeTaskState(name, stateDef, input, history, eventId, sm, jsonata, context);
            case "Choice" -> executeChoiceState(stateDef, input, jsonata, context);
            case "Wait" -> executeWaitState(stateDef, input, jsonata, context);
            case "Succeed" -> executeSucceedState(stateDef, input, jsonata, context);
            case "Fail" -> executeFail(stateDef, input, jsonata, context);
            case "Parallel" -> executeParallelState(name, stateDef, input, sm, jsonata, topLevelQueryLanguage, context);
            case "Map" -> executeMapState(name, stateDef, input, sm, jsonata, topLevelQueryLanguage, context);
            default -> new StateResult(input, stateDef.path("Next").asText(null));
        };
    }

    private StateResult executePassState(JsonNode stateDef, JsonNode input, boolean jsonata, JsonNode context) throws Exception {
        if (jsonata) {
            JsonNode result = stateDef.has("Result") ? stateDef.get("Result") : input;
            JsonNode output = applyJsonataOutput(stateDef, input, result, context);
            return new StateResult(output, stateDef.path("Next").asText(null));
        }

        JsonNode effectiveInput = applyInputPath(stateDef, input);

        // Pass states transform their input through Parameters (with intrinsics), then a static
        // Result overrides if present.
        JsonNode result = effectiveInput;
        if (stateDef.has("Parameters")) {
            result = resolveParameters(stateDef.get("Parameters"), effectiveInput, context);
        }
        if (stateDef.has("Result")) {
            result = stateDef.get("Result");
        }

        JsonNode output = mergeResult(stateDef, input, result);
        output = applyOutputPath(stateDef, input, output);
        return new StateResult(output, stateDef.path("Next").asText(null));
    }

    private StateResult executeTaskState(String stateName, JsonNode stateDef, JsonNode input,
                                         List<HistoryEvent> history, AtomicLong eventId, StateMachine sm,
                                         boolean jsonata, JsonNode context) throws Exception {
        String resource = stateDef.path("Resource").asText();
        boolean isWaitForToken = resource.endsWith(".waitForTaskToken");
        String effectiveResource = isWaitForToken
                ? resource.substring(0, resource.length() - ".waitForTaskToken".length())
                : resource;
        boolean isActivity = isActivityArn(effectiveResource);
        boolean needsToken = isWaitForToken || isActivity;

        String taskToken = null;
        CompletableFuture<JsonNode> tokenFuture = null;
        if (needsToken) {
            taskToken = UUID.randomUUID().toString();
            ((ObjectNode) context.get("Task")).put("Token", taskToken);
            tokenFuture = sfnService.get().registerPendingToken(taskToken);
        }

        JsonNode taskResult;
        if (jsonata) {
            JsonNode effectiveInput = input;
            if (stateDef.has("Arguments")) {
                JsonNode statesVar = buildStatesVar(input, null, context);
                effectiveInput = jsonataEvaluator.resolveTemplate(stateDef.get("Arguments"), statesVar);
            }
            taskResult = invokeResource(effectiveResource, effectiveInput, sm, taskToken);
        } else {
            JsonNode effectiveInput = applyInputPath(stateDef, input);
            if (stateDef.has("Parameters")) {
                effectiveInput = resolveParameters(stateDef.get("Parameters"), effectiveInput, context);
            }
            taskResult = invokeResource(effectiveResource, effectiveInput, sm, taskToken);
        }

        if (tokenFuture != null) {
            taskResult = awaitToken(tokenFuture, stateDef);
        }

        if (jsonata) {
            JsonNode output = applyJsonataOutput(stateDef, input, taskResult, context);
            return new StateResult(output, stateDef.path("Next").asText(null));
        } else {
            // ResultSelector transforms the raw result before ResultPath merges it into the state input.
            if (stateDef.has("ResultSelector")) {
                taskResult = resolveParameters(stateDef.get("ResultSelector"), taskResult, context);
            }
            JsonNode output = mergeResult(stateDef, input, taskResult);
            output = applyOutputPath(stateDef, input, output);
            return new StateResult(output, stateDef.path("Next").asText(null));
        }
    }

    private JsonNode awaitToken(CompletableFuture<JsonNode> future, JsonNode stateDef) throws Exception {
        int timeout = stateDef.path("HeartbeatSeconds").asInt(0);
        if (timeout <= 0) {
            timeout = 300;
        }
        try {
            return future.get(timeout, TimeUnit.SECONDS);
        } catch (java.util.concurrent.TimeoutException e) {
            future.cancel(true);
            throw new FailStateException("States.HeartbeatTimeout",
                    "Task timed out after " + timeout + " seconds");
        } catch (ExecutionException e) {
            Throwable cause = e.getCause();
            if (cause instanceof FailStateException fse) {
                throw fse;
            }
            throw new FailStateException("States.TaskFailed",
                    cause != null ? cause.getMessage() : "Task failed");
        }
    }

    /**
     * Extracts the Lambda function name from a reference that may be a bare name, a name with a
     * version/alias qualifier (e.g. "name:$LATEST"), or a full/partial function ARN
     * (e.g. "arn:aws:lambda:region:acct:function:name[:qualifier]"). The qualifier is dropped
     * because the function store is keyed by name. Taking the last ':'-segment is wrong for a
     * qualified ARN — it yields the qualifier (e.g. "$LATEST") instead of the function name.
     */
    static String extractLambdaFunctionName(String ref) {
        if (ref == null) {
            return null;
        }
        String fn = ref;
        int fi = ref.indexOf(":function:");
        if (fi >= 0) {
            fn = ref.substring(fi + ":function:".length());
        }
        // Drop an optional trailing version/alias qualifier (e.g. ":$LATEST", ":1", ":prod").
        int colon = fn.indexOf(':');
        if (colon >= 0) {
            fn = fn.substring(0, colon);
        }
        return fn;
    }

    private JsonNode invokeResource(String resource, JsonNode input, StateMachine sm, String taskToken) throws Exception {
        // Support Lambda resources: direct ARN or optimized integration
        String functionName = null;
        JsonNode lambdaPayload = input;

        if (resource.contains(":lambda:") && resource.contains(":function:")) {
            // Direct Lambda ARN: arn:aws:lambda:region:account:function:name[:qualifier]
            functionName = extractLambdaFunctionName(resource);
        } else if (resource.equals("arn:aws:states:::lambda:invoke")) {
            // Optimized Lambda integration — function name and payload come from resolved input
            String fnRef = input.path("FunctionName").asText(null);
            if (fnRef != null) {
                functionName = extractLambdaFunctionName(fnRef);
            }
            JsonNode payload = input.path("Payload");
            if (!payload.isMissingNode()) {
                lambdaPayload = payload;
            }
        }

        if (functionName != null) {
            // Extract region from the state machine ARN: arn:aws:states:REGION:...
            String region = extractRegionFromArn(sm.getStateMachineArn());
            LambdaFunction fn = functionStore.get(region, functionName).orElse(null);
            if (fn == null) {
                throw new RuntimeException("Lambda function not found: " + functionName);
            }

            String payloadStr = objectMapper.writeValueAsString(lambdaPayload);
            InvokeResult result = lambdaExecutor.invoke(fn, payloadStr.getBytes(), InvocationType.RequestResponse);

            if (result.getFunctionError() != null) {
                throw new FailStateException("Lambda.AWSLambdaException", result.getFunctionError());
            }

            byte[] responseBytes = result.getPayload();
            if (responseBytes != null && responseBytes.length > 0) {
                return objectMapper.readTree(responseBytes);
            }
            return NullNode.getInstance();
        }

        // DynamoDB optimized integrations (4 actions)
        if (resource.startsWith("arn:aws:states:::dynamodb:")) {
            String operation = resource.substring("arn:aws:states:::dynamodb:".length());
            String region = extractRegionFromArn(sm.getStateMachineArn());
            try {
                return invokeDynamoDb(operation, input, region);
            } catch (AwsException e) {
                throw new FailStateException("DynamoDB." + e.getErrorCode(), e.getMessage());
            }
        }

        // AWS SDK service integrations: DynamoDB
        if (resource.startsWith("arn:aws:states:::aws-sdk:dynamodb:")) {
            String camelCaseAction = resource.substring("arn:aws:states:::aws-sdk:dynamodb:".length());
            String region = extractRegionFromArn(sm.getStateMachineArn());
            return invokeAwsSdkDynamoDb(camelCaseAction, input, region);
        }

        // SQS optimized integration
        if (resource.equals("arn:aws:states:::sqs:sendMessage")) {
            String region = extractRegionFromArn(sm.getStateMachineArn());
            return invokeOptimizedSqsSendMessage(input, region);
        }

        // AWS SDK service integration: SQS SendMessage
        if (resource.equals("arn:aws:states:::aws-sdk:sqs:sendMessage")) {
            String region = extractRegionFromArn(sm.getStateMachineArn());
            return invokeAwsSdkSqsSendMessage(input, region);
        }

        // AWS SDK service integration: CloudFormation (query protocol → JSON)
        if (resource.startsWith("arn:aws:states:::aws-sdk:cloudformation:")) {
            String action = resource.substring("arn:aws:states:::aws-sdk:cloudformation:".length());
            String region = extractRegionFromArn(sm.getStateMachineArn());
            return invokeAwsSdkCloudFormation(action, input, region);
        }

        // AWS SDK service integration: EC2 DescribeRegions
        if (resource.equals("arn:aws:states:::aws-sdk:ec2:describeRegions")) {
            return invokeAwsSdkEc2DescribeRegions();
        }

        // S3 PutObject — optimized and aws-sdk integrations
        if (resource.equals("arn:aws:states:::s3:putObject")
                || resource.equals("arn:aws:states:::aws-sdk:s3:putObject")) {
            return invokeS3PutObject(input);
        }

        // ECS optimized integration: arn:aws:states:::ecs:runTask (request-response, .sync, .waitForTaskToken).
        // The .waitForTaskToken suffix is already stripped by executeTaskState, so a waitForTaskToken
        // variant arrives here as the bare runTask resource and simply launches the task while the token
        // future blocks for SendTaskSuccess.
        if (resource.startsWith("arn:aws:states:::ecs:runTask")) {
            // A non-null taskToken means the original resource ended with .waitForTaskToken (stripped
            // upstream). Its failure semantics match .sync — a task placement failure fails the state —
            // whereas request-response returns the {Tasks,Failures} envelope without failing the state.
            String mode = taskToken != null
                    ? ".waitForTaskToken"
                    : resource.substring("arn:aws:states:::ecs:runTask".length());
            String region = extractRegionFromArn(sm.getStateMachineArn());
            return invokeEcsRunTask(mode, input, region);
        }

        // Nested state machine integration
        if (resource.startsWith("arn:aws:states:::states:startExecution")) {
            String mode = resource.substring("arn:aws:states:::states:startExecution".length());
            String region = extractRegionFromArn(sm.getStateMachineArn());
            return invokeNestedStateMachine(mode, input, region);
        }

        // Activity resource: arn:aws:states:{region}:{account}:activity:{name}
        if (isActivityArn(resource)) {
            if (taskToken == null) {
                throw new FailStateException("States.TaskFailed",
                        "Activity resource requires waitForTaskToken: " + resource);
            }
            String inputStr = objectMapper.writeValueAsString(input);
            sfnService.get().enqueueActivityTask(resource, taskToken, inputStr);
            return NullNode.getInstance(); // caller blocks via token future
        }

        throw new FailStateException("States.TaskFailed",
                "Unsupported resource: " + resource);
    }

    /**
     * AWS SDK integration for CloudFormation (a Query-protocol service): flattens the task input to
     * Query parameters, dispatches to the CloudFormation handler, and converts the XML response back
     * to the JSON shape the {@code aws-sdk:*} integration returns.
     */
    private JsonNode invokeAwsSdkCloudFormation(String camelAction, JsonNode input, String region) {
        String pascalAction = capitalizeFirst(camelAction);
        MultivaluedMap<String, String> params = new MultivaluedHashMap<>();
        flattenQueryParams(input, "", params);

        jakarta.ws.rs.core.Response response;
        try {
            response = cloudFormationHandler.handle(pascalAction, params, region);
        } catch (AwsException e) {
            throw new FailStateException("CloudFormation." + e.getErrorCode(), e.getMessage());
        } catch (Exception e) {
            throw new FailStateException("CloudFormation.InternalFailure",
                    e.getMessage() != null ? e.getMessage() : "CloudFormation error");
        }

        String xml = response.getEntity() == null ? "" : response.getEntity().toString();
        if (response.getStatus() >= 400) {
            String code = XmlParser.extractFirst(xml, "Code", "ServiceException");
            String message = XmlParser.extractFirst(xml, "Message", "CloudFormation request failed");
            throw new FailStateException("CloudFormation." + code, message);
        }
        try {
            return QueryXmlToJson.convert(xml, pascalAction + "Result", objectMapper);
        } catch (Exception e) {
            throw new FailStateException("CloudFormation.InternalFailure",
                    "Failed to parse CloudFormation response: " + e.getMessage());
        }
    }

    private JsonNode invokeAwsSdkEc2DescribeRegions() {
        ObjectNode result = objectMapper.createObjectNode();
        ArrayNode regions = objectMapper.createArrayNode();
        for (String name : ec2Service.describeRegions()) {
            ObjectNode region = objectMapper.createObjectNode();
            region.put("RegionName", name);
            region.put("Endpoint", "ec2." + name + ".amazonaws.com");
            region.put("OptInStatus", "opt-in-not-required");
            regions.add(region);
        }
        result.set("Regions", regions);
        return result;
    }

    private JsonNode invokeS3PutObject(JsonNode input) {
        String bucket = input.path("Bucket").asText(null);
        String key = input.path("Key").asText(null);
        if (bucket == null || key == null) {
            throw new FailStateException("S3.InvalidRequest", "Bucket and Key are required");
        }
        JsonNode body = input.path("Body");
        byte[] data;
        if (body.isMissingNode() || body.isNull()) {
            data = new byte[0];
        } else if (body.isValueNode()) {
            data = body.asText().getBytes(StandardCharsets.UTF_8);
        } else {
            data = body.toString().getBytes(StandardCharsets.UTF_8);
        }
        try {
            var stored = s3Service.putObject(bucket, key, data, "application/octet-stream", new HashMap<>());
            ObjectNode result = objectMapper.createObjectNode();
            if (stored != null && stored.getETag() != null) {
                result.put("ETag", stored.getETag());
            }
            return result;
        } catch (AwsException e) {
            throw new FailStateException("S3." + e.getErrorCode(), e.getMessage());
        }
    }

    /** Flattens a JSON object into AWS Query parameters (lists → {@code key.member.N}). */
    private void flattenQueryParams(JsonNode node, String prefix, MultivaluedMap<String, String> out) {
        if (node == null || node.isNull() || node.isMissingNode()) {
            return;
        }
        if (node.isObject()) {
            Iterator<Map.Entry<String, JsonNode>> fields = node.fields();
            while (fields.hasNext()) {
                Map.Entry<String, JsonNode> entry = fields.next();
                String key = prefix.isEmpty() ? entry.getKey() : prefix + "." + entry.getKey();
                flattenQueryParams(entry.getValue(), key, out);
            }
        } else if (node.isArray()) {
            int i = 1;
            for (JsonNode item : node) {
                flattenQueryParams(item, prefix + ".member." + i, out);
                i++;
            }
        } else {
            out.add(prefix, node.asText());
        }
    }

    private static String capitalizeFirst(String s) {
        return s.isEmpty() ? s : Character.toUpperCase(s.charAt(0)) + s.substring(1);
    }

    private JsonNode invokeNestedStateMachine(String mode, JsonNode input, String region) throws Exception {
        String smArn = input.path("StateMachineArn").asText(null);
        if (smArn == null || smArn.isBlank()) {
            throw new FailStateException("States.TaskFailed",
                    "StateMachineArn is required for nested state machine execution");
        }
        JsonNode inputNode = input.path("Input");
        String childInput = inputNode.isMissingNode() ? "{}" : objectMapper.writeValueAsString(inputNode);

        io.github.hectorvent.floci.services.stepfunctions.model.Execution exec =
                sfnService.get().startExecution(smArn, null, childInput, region);
        String execArn = exec.getExecutionArn();

        if ("".equals(mode)) {
            // Fire-and-forget: return { executionArn, startDate }
            ObjectNode result = objectMapper.createObjectNode();
            result.put("executionArn", execArn);
            result.put("startDate", exec.getStartDate());
            return result;
        }

        // .sync or .sync:2 — poll until terminal
        for (int i = 0; i < 600; i++) {
            Thread.sleep(100);
            io.github.hectorvent.floci.services.stepfunctions.model.Execution current =
                    sfnService.get().describeExecution(execArn);
            String status = current.getStatus();
            if ("RUNNING".equals(status)) {
                continue;
            }
            if ("SUCCEEDED".equals(status)) {
                if (".sync:2".equals(mode)) {
                    String out = current.getOutput();
                    return objectMapper.readTree(out != null ? out : "null");
                }
                // .sync — full execution envelope; output field is a JSON string
                ObjectNode envelope = objectMapper.createObjectNode();
                envelope.put("executionArn", current.getExecutionArn());
                envelope.put("stateMachineArn", current.getStateMachineArn());
                envelope.put("name", current.getName());
                envelope.put("status", current.getStatus());
                envelope.put("startDate", current.getStartDate());
                if (current.getStopDate() != null) {
                    envelope.put("stopDate", current.getStopDate());
                }
                if (current.getInput() != null) {
                    envelope.put("input", current.getInput());
                }
                if (current.getOutput() != null) {
                    envelope.put("output", current.getOutput());
                }
                return envelope;
            }
            throw new FailStateException(
                    current.getError() != null ? current.getError() : "States.TaskFailed",
                    current.getCause() != null ? current.getCause()
                            : "Nested execution ended with status: " + status);
        }
        throw new FailStateException("States.TaskFailed",
                "Nested execution timed out: " + execArn);
    }

    /**
     * Optimized ECS RunTask integration. Step Functions passes PascalCase parameters
     * ({@code Cluster}, {@code TaskDefinition}, {@code Overrides.ContainerOverrides}, …)
     * and expects PascalCase results, whereas Floci's ECS handlers use the lowerCamelCase
     * of the ECS data-plane API — {@link #recaseKeys} bridges the two ends.
     *
     * @param mode "" for request-response (returns the RunTask {@code {Tasks,Failures}} response
     *             without failing on a placement failure), ".sync" to block until the task reaches
     *             STOPPED, or ".waitForTaskToken" to launch and let the token future carry the result
     *             (both ".sync" and ".waitForTaskToken" fail the state on a placement failure).
     */
    private JsonNode invokeEcsRunTask(String mode, JsonNode input, String region) throws Exception {
        String taskDefinition = input.path("TaskDefinition").asText(null);
        if (taskDefinition == null || taskDefinition.isBlank()) {
            throw new FailStateException("States.TaskFailed",
                    "TaskDefinition is required for the ecs:runTask integration");
        }
        String cluster = input.hasNonNull("Cluster") ? input.path("Cluster").asText() : null;
        int count = input.path("Count").asInt(1);

        LaunchType launchType = null;
        String launchTypeRaw = input.path("LaunchType").asText(null);
        if (launchTypeRaw != null && !launchTypeRaw.isBlank()) {
            try {
                launchType = LaunchType.valueOf(launchTypeRaw);
            } catch (IllegalArgumentException e) {
                throw new FailStateException("States.TaskFailed", "Unsupported LaunchType: " + launchTypeRaw);
            }
        }
        String group = input.path("Group").asText(null);
        String startedBy = input.path("StartedBy").asText(null);

        // Parameters are PascalCase; the ECS handler's parsers expect the camelCase of the
        // data-plane API, so recase each sub-tree before reusing them.
        JsonNode overridesNode = recaseKeys(objectMapper,
                input.path("Overrides").path("ContainerOverrides"), false);
        List<ContainerOverride> overrides = ecsJsonHandler.parseContainerOverrides(overridesNode);

        // NetworkConfiguration (awsvpc) is threaded through so it is not dropped at the boundary;
        // awsvpc ENI attachments themselves are not emulated in the local mock profile.
        JsonNode networkConfigNode = recaseKeys(objectMapper, input.path("NetworkConfiguration"), false);
        NetworkConfiguration networkConfiguration = ecsJsonHandler.parseNetworkConfiguration(networkConfigNode);

        List<EcsTask> launched;
        try {
            launched = ecsService.runTask(cluster, taskDefinition, count, launchType, group, startedBy,
                    overrides, networkConfiguration, region);
        } catch (AwsException e) {
            throw new FailStateException("ECS." + e.getErrorCode(), e.getMessage());
        }
        // A task placement failure (no task launched) fails the state only for the .sync and
        // .waitForTaskToken patterns, and AWS surfaces it with the AmazonECS.Unknown error name.
        // Request-response never fails on a placement failure — it returns the { Tasks, Failures }
        // envelope (possibly with empty Tasks) so the caller can inspect Failures itself.
        boolean callbackOrSync = ".sync".equals(mode) || ".waitForTaskToken".equals(mode);
        if (launched.isEmpty() && callbackOrSync) {
            throw new FailStateException("AmazonECS.Unknown", "ecs:runTask launched no tasks");
        }

        if (mode.isEmpty() || ".waitForTaskToken".equals(mode)) {
            // Request-response: return the RunTask response shape { Tasks: [...], Failures: [] }.
            // The .waitForTaskToken launch phase lands here too — its return value is discarded once
            // the task token supplies the real result, so returning the envelope just completes the launch.
            ObjectNode resp = objectMapper.createObjectNode();
            ArrayNode tasks = resp.putArray("Tasks");
            for (EcsTask t : launched) {
                tasks.add(recaseKeys(objectMapper, ecsJsonHandler.taskNode(t), true));
            }
            resp.putArray("Failures");
            return resp;
        }

        if (!".sync".equals(mode)) {
            // Only request-response (""), .sync and .waitForTaskToken are valid; reject typos rather
            // than silently treating an unknown suffix as .sync.
            throw new FailStateException("States.TaskFailed", "Unsupported ecs:runTask mode: " + mode);
        }

        // .sync — wait until every launched task reaches STOPPED, then surface success or failure.
        // All tasks must be polled (not just the first): with Count > 1, a failure in any task must
        // fail the state, otherwise tasks beyond the first would run unmonitored.
        List<String> taskArns = launched.stream().map(EcsTask::getTaskArn).toList();
        for (int i = 0; i < ECS_SYNC_POLL_ATTEMPTS; i++) {
            Thread.sleep(ECS_SYNC_POLL_INTERVAL_MS);
            List<EcsTask> described = ecsService.describeTasks(cluster, taskArns, region);
            boolean allStopped = described.size() == taskArns.size()
                    && described.stream().allMatch(t -> "STOPPED".equals(t.getLastStatus()));
            if (!allStopped) {
                continue;
            }
            // All terminal. Like real Step Functions, fail the state if any task's essential
            // container exited non-zero or a task never ran a container (e.g. it failed to start).
            for (EcsTask task : described) {
                String cause = ecsTaskFailureCause(task, nonEssentialContainerNames(task, region));
                if (cause != null) {
                    throw new FailStateException("States.TaskFailed", cause);
                }
            }
            // Success: a single task returns its description; multiple tasks return the array.
            if (described.size() == 1) {
                return recaseKeys(objectMapper, ecsJsonHandler.taskNode(described.get(0)), true);
            }
            ArrayNode arr = objectMapper.createArrayNode();
            for (EcsTask task : described) {
                arr.add(recaseKeys(objectMapper, ecsJsonHandler.taskNode(task), true));
            }
            return arr;
        }
        throw new FailStateException("States.Timeout",
                "ecs:runTask.sync timed out waiting for tasks to stop: " + taskArns);
    }

    /** A failure cause if the ECS task did not complete cleanly (non-zero exit or no container ran), or null on success. */
    private static String ecsTaskFailureCause(EcsTask task, Set<String> nonEssentialNames) {
        boolean ranAContainer = task.getContainers() != null && !task.getContainers().isEmpty();
        Integer nonZeroExit = null;
        boolean hasNullExitCode = false;
        if (ranAContainer) {
            for (var c : task.getContainers()) {
                // Only essential containers decide the task outcome, like real Step Functions; a
                // non-essential sidecar (log shipper, metrics agent) exiting non-zero is ignored.
                // Anything not explicitly marked non-essential defaults to essential.
                if (nonEssentialNames.contains(c.getName())) {
                    continue;
                }
                if (c.getExitCode() == null) {
                    // A STOPPED container with no exit code never completed (OOM-killed, failed to
                    // start, force-stopped) — AWS treats that as a failure, not a clean exit.
                    hasNullExitCode = true;
                } else if (c.getExitCode() != 0) {
                    nonZeroExit = c.getExitCode();
                }
            }
        }
        if (nonZeroExit == null && !hasNullExitCode && ranAContainer) {
            return null;
        }
        if (task.getStoppedReason() != null) {
            return task.getStoppedReason();
        }
        if (nonZeroExit != null) {
            return "Essential container exited with code " + nonZeroExit;
        }
        if (hasNullExitCode) {
            return "Essential container stopped without an exit code";
        }
        return "Task stopped without running a container";
    }

    /**
     * Names of the task's containers that are explicitly {@code essential: false} in its task
     * definition. Their exit status does not fail the state. Falls back to an empty set (treat all
     * as essential) when the task definition can't be resolved, preserving the conservative default.
     */
    private Set<String> nonEssentialContainerNames(EcsTask task, String region) {
        try {
            TaskDefinition td = ecsService.describeTaskDefinition(task.getTaskDefinitionArn(), region);
            Set<String> names = new HashSet<>();
            if (td.getContainerDefinitions() != null) {
                for (ContainerDefinition cd : td.getContainerDefinitions()) {
                    if (!cd.isEssential()) {
                        names.add(cd.getName());
                    }
                }
            }
            return names;
        } catch (RuntimeException e) {
            // Tolerated: if the task definition can't be resolved we conservatively treat every
            // container as essential (empty non-essential set), but log it so the loss of the
            // essential/non-essential distinction is diagnosable.
            LOG.warnv("ecs:runTask: could not resolve task definition {0} to classify essential "
                    + "containers; treating all as essential ({1})", task.getTaskDefinitionArn(), e.getMessage());
            return Set.of();
        }
    }

    /**
     * Returns a deep copy of {@code node} with the first character of every object key
     * recased. Step Functions optimized service integrations use PascalCase member names
     * while Floci's ECS wire handlers use the lowerCamelCase of the data-plane API.
     *
     * @param upperFirst true to map lowerCamelCase → PascalCase (results handed back to the
     *                   state machine); false to map PascalCase → lowerCamelCase (parameters
     *                   handed to the ECS handlers).
     */
    static JsonNode recaseKeys(ObjectMapper mapper, JsonNode node, boolean upperFirst) {
        if (node == null) {
            return null;
        }
        if (node.isObject()) {
            ObjectNode out = mapper.createObjectNode();
            Iterator<Map.Entry<String, JsonNode>> fields = node.fields();
            while (fields.hasNext()) {
                Map.Entry<String, JsonNode> e = fields.next();
                out.set(recaseKey(e.getKey(), upperFirst), recaseKeys(mapper, e.getValue(), upperFirst));
            }
            return out;
        }
        if (node.isArray()) {
            ArrayNode out = mapper.createArrayNode();
            for (JsonNode item : node) {
                out.add(recaseKeys(mapper, item, upperFirst));
            }
            return out;
        }
        return node.deepCopy();
    }

    private static String recaseKey(String key, boolean upperFirst) {
        if (key == null || key.isEmpty()) {
            return key;
        }
        char first = key.charAt(0);
        char recased = upperFirst ? Character.toUpperCase(first) : Character.toLowerCase(first);
        return recased == first ? key : recased + key.substring(1);
    }

    private boolean isActivityArn(String resource) {
        // arn:aws:states:{region}:{account}:activity:{name}
        // Distinguish from integration ARNs like arn:aws:states:::lambda:invoke (empty region/account)
        String[] parts = resource.split(":");
        return parts.length >= 7
                && "arn".equals(parts[0])
                && "states".equals(parts[2])
                && "activity".equals(parts[5])
                && !parts[3].isEmpty()
                && !parts[4].isEmpty();
    }

    private JsonNode invokeDynamoDb(String operation, JsonNode input, String region) {
        String tableName = input.path("TableName").asText();
        switch (operation) {
            case "putItem" -> {
                JsonNode item = input.path("Item");
                String conditionExpr = input.has("ConditionExpression")
                        ? input.get("ConditionExpression").asText() : null;
                JsonNode exprAttrNames = input.has("ExpressionAttributeNames")
                        ? input.get("ExpressionAttributeNames") : null;
                JsonNode exprAttrValues = input.has("ExpressionAttributeValues")
                        ? input.get("ExpressionAttributeValues") : null;
                dynamoDbService.putItem(tableName, item, conditionExpr, exprAttrNames, exprAttrValues, region, "NONE");
                return objectMapper.createObjectNode();
            }
            case "getItem" -> {
                JsonNode key = input.path("Key");
                JsonNode item = dynamoDbService.getItem(tableName, key, region);
                ObjectNode result = objectMapper.createObjectNode();
                if (item != null) {
                    result.set("Item", item);
                }
                return result;
            }
            case "deleteItem" -> {
                JsonNode key = input.path("Key");
                String conditionExpr = input.has("ConditionExpression")
                        ? input.get("ConditionExpression").asText() : null;
                JsonNode exprAttrNames = input.has("ExpressionAttributeNames")
                        ? input.get("ExpressionAttributeNames") : null;
                JsonNode exprAttrValues = input.has("ExpressionAttributeValues")
                        ? input.get("ExpressionAttributeValues") : null;
                dynamoDbService.deleteItem(tableName, key, conditionExpr, exprAttrNames, exprAttrValues, region, "NONE");
                return objectMapper.createObjectNode();
            }
            case "scan" -> {
                String filterExpression = input.has("FilterExpression")
                        ? input.get("FilterExpression").asText() : null;
                JsonNode exprAttrNames = input.has("ExpressionAttributeNames")
                        ? input.get("ExpressionAttributeNames") : null;
                JsonNode exprAttrValues = input.has("ExpressionAttributeValues")
                        ? input.get("ExpressionAttributeValues") : null;
                Integer limit = input.has("Limit") ? input.get("Limit").asInt() : null;
                JsonNode scanFilter = input.has("ScanFilter") ? input.get("ScanFilter") : null;
                DynamoDbService.ScanResult scanResult = dynamoDbService.scan(
                        tableName, filterExpression, exprAttrNames, exprAttrValues, scanFilter, limit, null, null, region);
                ObjectNode response = objectMapper.createObjectNode();
                com.fasterxml.jackson.databind.node.ArrayNode items = objectMapper.createArrayNode();
                scanResult.items().forEach(items::add);
                response.set("Items", items);
                response.put("Count", scanResult.items().size());
                response.put("ScannedCount", scanResult.scannedCount());
                return response;
            }
            case "updateItem" -> {
                JsonNode key = input.path("Key");
                JsonNode attributeUpdates = input.has("AttributeUpdates")
                        ? input.get("AttributeUpdates") : null;
                String updateExpression = input.has("UpdateExpression")
                        ? input.get("UpdateExpression").asText() : null;
                JsonNode exprAttrNames = input.has("ExpressionAttributeNames")
                        ? input.get("ExpressionAttributeNames") : null;
                JsonNode exprAttrValues = input.has("ExpressionAttributeValues")
                        ? input.get("ExpressionAttributeValues") : null;
                String conditionExpression = input.has("ConditionExpression")
                        ? input.get("ConditionExpression").asText() : null;
                String returnValues = input.path("ReturnValues").asText("NONE");

                DynamoDbService.UpdateResult result = dynamoDbService.updateItem(
                        tableName, key, attributeUpdates, updateExpression,
                        exprAttrNames, exprAttrValues, returnValues,
                        conditionExpression, region, "NONE");

                ObjectNode response = objectMapper.createObjectNode();
                if ("ALL_NEW".equals(returnValues) && result.newItem() != null) {
                    response.set("Attributes", result.newItem());
                } else if ("ALL_OLD".equals(returnValues) && result.oldItem() != null) {
                    response.set("Attributes", result.oldItem());
                }
                return response;
            }
            default -> throw new FailStateException("States.TaskFailed",
                    "Unsupported DynamoDB operation: " + operation);
        }
    }

    private JsonNode invokeAwsSdkDynamoDb(String camelCaseAction, JsonNode input, String region) {
        // Convert camelCase to PascalCase (e.g., putItem → PutItem)
        String pascalAction = Character.toUpperCase(camelCaseAction.charAt(0)) + camelCaseAction.substring(1);

        jakarta.ws.rs.core.Response response;
        try {
            response = dynamoDbJsonHandler.handle(pascalAction, input, region);
        } catch (AwsException e) {
            throw new FailStateException("DynamoDb." + e.getErrorCode(), e.getMessage());
        } catch (Exception e) {
            throw new FailStateException("DynamoDb.InternalServerError",
                    e.getMessage() != null ? e.getMessage() : "DynamoDB error");
        }

        Object entity = response.getEntity();
        int status = response.getStatus();

        if (status >= 400) {
            if (entity instanceof AwsErrorResponse err) {
                throw new FailStateException("DynamoDb." + err.type(), err.message());
            }
            if (entity instanceof JsonNode errorNode) {
                String errorName = errorNode.path("__type").asText("UnknownError");
                String errorMessage = errorNode.path("message").asText(
                        errorNode.path("Message").asText("DynamoDB operation failed"));
                throw new FailStateException("DynamoDb." + errorName, errorMessage);
            }
            throw new FailStateException("DynamoDb.ServiceException", "DynamoDB operation failed");
        }

        if (entity instanceof JsonNode jsonNode) {
            return jsonNode;
        }
        return objectMapper.createObjectNode();
    }

    private JsonNode invokeOptimizedSqsSendMessage(JsonNode input, String region) {
        ObjectNode request = normalizeSqsSendMessageInput(input);
        return invokeSqsAction("SendMessage", request, region, "SQS.");
    }

    private JsonNode invokeAwsSdkSqsSendMessage(JsonNode input, String region) {
        return invokeSqsAction("SendMessage", normalizeSqsSendMessageInput(input), region, "Sqs.", true);
    }

    private ObjectNode normalizeSqsSendMessageInput(JsonNode input) {
        ObjectNode request = input != null && input.isObject()
                ? ((ObjectNode) input.deepCopy())
                : objectMapper.createObjectNode();

        JsonNode messageBody = request.get("MessageBody");
        if (messageBody != null && !messageBody.isTextual() && !messageBody.isNull()) {
            request.put("MessageBody", messageBody.toString());
        }
        return request;
    }

    private JsonNode invokeSqsAction(String action, JsonNode input, String region, String errorPrefix) {
        return invokeSqsAction(action, input, region, errorPrefix, false);
    }

    private JsonNode invokeSqsAction(String action, JsonNode input, String region, String errorPrefix, boolean awsSdkStyleErrors) {
        jakarta.ws.rs.core.Response response;
        try {
            response = sqsJsonHandler.handle(action, input, region);
        } catch (AwsException e) {
            throw new FailStateException(errorPrefix + normalizeSqsErrorCode(e.getErrorCode(), awsSdkStyleErrors), e.getMessage());
        } catch (Exception e) {
            throw new FailStateException(errorPrefix + "InternalServerError",
                    e.getMessage() != null ? e.getMessage() : "SQS error");
        }

        Object entity = response.getEntity();
        int status = response.getStatus();

        if (status >= 400) {
            if (entity instanceof AwsErrorResponse err) {
                throw new FailStateException(errorPrefix + normalizeSqsErrorCode(err.type(), awsSdkStyleErrors), err.message());
            }
            if (entity instanceof JsonNode errorNode) {
                String errorName = normalizeSqsErrorCode(errorNode.path("__type").asText("UnknownError"), awsSdkStyleErrors);
                String errorMessage = errorNode.path("message").asText(
                        errorNode.path("Message").asText("SQS operation failed"));
                throw new FailStateException(errorPrefix + errorName, errorMessage);
            }
            throw new FailStateException(errorPrefix + "ServiceException", "SQS operation failed");
        }

        if (entity instanceof JsonNode jsonNode) {
            return jsonNode;
        }
        return objectMapper.createObjectNode();
    }

    private String normalizeSqsErrorCode(String errorCode, boolean awsSdkStyleErrors) {
        if (!awsSdkStyleErrors || errorCode == null || errorCode.isBlank()) {
            return errorCode;
        }
        return switch (errorCode) {
            case "AWS.SimpleQueueService.NonExistentQueue" -> "QueueDoesNotExistException";
            case "UnsupportedOperation" -> "UnsupportedOperationException";
            case "ReceiptHandleIsInvalid" -> "ReceiptHandleIsInvalidException";
            case "QueueAlreadyExists" -> "QueueNameExistsException";
            case "InvalidAddress" -> "InvalidAddressException";
            case "InvalidSecurity" -> "InvalidSecurityException";
            case "InvalidMessageContents" -> "InvalidMessageContentsException";
            case "OverLimit" -> "OverLimitException";
            case "RequestThrottled" -> "RequestThrottledException";
            default -> errorCode;
        };
    }

    private StateResult executeChoiceState(JsonNode stateDef, JsonNode input, boolean jsonata, JsonNode context) throws Exception {
        if (jsonata) {
            JsonNode statesVar = buildStatesVar(input, null, context);
            JsonNode choices = stateDef.path("Choices");
            for (JsonNode choice : choices) {
                String condition = choice.path("Condition").asText(null);
                if (condition != null) {
                    JsonNode result = jsonataEvaluator.evaluate(condition, statesVar);
                    if (result.isBoolean() && result.asBoolean()) {
                        return new StateResult(input, choice.path("Next").asText());
                    }
                }
            }
            String defaultState = stateDef.path("Default").asText(null);
            if (defaultState != null) {
                return new StateResult(input, defaultState);
            }
            throw new FailStateException("States.NoChoiceMatched", "No choice rule matched and no default state");
        }

        JsonNode choices = stateDef.path("Choices");
        for (JsonNode choice : choices) {
            if (evaluateCondition(choice, input)) {
                return new StateResult(input, choice.path("Next").asText());
            }
        }
        // Default branch
        String defaultState = stateDef.path("Default").asText(null);
        if (defaultState != null) {
            return new StateResult(input, defaultState);
        }
        throw new FailStateException("States.NoChoiceMatched", "No choice rule matched and no default state");
    }

    private boolean evaluateCondition(JsonNode rule, JsonNode input) throws Exception {
        // Logical operators
        if (rule.has("And")) {
            for (JsonNode sub : rule.get("And")) {
                if (!evaluateCondition(sub, input)) return false;
            }
            return true;
        }
        if (rule.has("Or")) {
            for (JsonNode sub : rule.get("Or")) {
                if (evaluateCondition(sub, input)) return true;
            }
            return false;
        }
        if (rule.has("Not")) {
            return !evaluateCondition(rule.get("Not"), input);
        }

        String variable = rule.path("Variable").asText();
        JsonNode value = resolvePath(variable, input);

        if (rule.has("StringEquals")) {
            return value.asText().equals(rule.get("StringEquals").asText());
        }
        if (rule.has("StringEqualsPath")) {
            return value.asText().equals(resolvePath(rule.get("StringEqualsPath").asText(), input).asText());
        }
        if (rule.has("StringMatches")) {
            return value.asText().matches(globToRegex(rule.get("StringMatches").asText()));
        }
        if (rule.has("NumericEquals")) {
            return value.asDouble() == rule.get("NumericEquals").asDouble();
        }
        if (rule.has("NumericEqualsPath")) {
            return value.asDouble() == resolvePath(rule.get("NumericEqualsPath").asText(), input).asDouble();
        }
        if (rule.has("NumericLessThan")) {
            return value.asDouble() < rule.get("NumericLessThan").asDouble();
        }
        if (rule.has("NumericLessThanPath")) {
            return value.asDouble() < resolvePath(rule.get("NumericLessThanPath").asText(), input).asDouble();
        }
        if (rule.has("NumericGreaterThan")) {
            return value.asDouble() > rule.get("NumericGreaterThan").asDouble();
        }
        if (rule.has("NumericGreaterThanPath")) {
            return value.asDouble() > resolvePath(rule.get("NumericGreaterThanPath").asText(), input).asDouble();
        }
        if (rule.has("NumericLessThanEquals")) {
            return value.asDouble() <= rule.get("NumericLessThanEquals").asDouble();
        }
        if (rule.has("NumericGreaterThanEquals")) {
            return value.asDouble() >= rule.get("NumericGreaterThanEquals").asDouble();
        }
        if (rule.has("BooleanEquals")) {
            return value.asBoolean() == rule.get("BooleanEquals").asBoolean();
        }
        if (rule.has("BooleanEqualsPath")) {
            return value.asBoolean() == resolvePath(rule.get("BooleanEqualsPath").asText(), input).asBoolean();
        }
        if (rule.has("IsNull")) {
            boolean expectNull = rule.get("IsNull").asBoolean();
            return value.isNull() == expectNull;
        }
        if (rule.has("IsPresent")) {
            boolean expectPresent = rule.get("IsPresent").asBoolean();
            // A field that exists with an explicit null value still counts as present in AWS, so
            // resolve without collapsing missing into null: only a truly absent path is "not present".
            boolean present = !resolvePathNode(variable, input).isMissingNode();
            return present == expectPresent;
        }
        if (rule.has("IsString")) {
            return value.isTextual() == rule.get("IsString").asBoolean();
        }
        if (rule.has("IsNumeric")) {
            return value.isNumber() == rule.get("IsNumeric").asBoolean();
        }
        if (rule.has("IsBoolean")) {
            return value.isBoolean() == rule.get("IsBoolean").asBoolean();
        }

        return false;
    }

    private StateResult executeWaitState(JsonNode stateDef, JsonNode input, boolean jsonata, JsonNode context) throws InterruptedException {
        int seconds = 0;
        if (jsonata) {
            if (stateDef.has("Seconds")) {
                JsonNode secondsNode = stateDef.get("Seconds");
                if (secondsNode.isTextual() && JsonataEvaluator.isExpression(secondsNode.asText())) {
                    JsonNode statesVar = buildStatesVar(input, null, context);
                    JsonNode result = jsonataEvaluator.evaluate(secondsNode.asText(), statesVar);
                    seconds = Math.min(result.asInt(), MAX_WAIT_SECONDS);
                } else {
                    seconds = Math.min(secondsNode.asInt(), MAX_WAIT_SECONDS);
                }
            }
        } else {
            if (stateDef.has("Seconds")) {
                seconds = Math.min(stateDef.get("Seconds").asInt(), MAX_WAIT_SECONDS);
            } else if (stateDef.has("SecondsPath")) {
                JsonNode val = resolvePath(stateDef.get("SecondsPath").asText(), input);
                seconds = Math.min(val.asInt(), MAX_WAIT_SECONDS);
            }
        }
        // Timestamp and TimestampPath: wait until that time or now, whichever is sooner
        if (seconds > 0) {
            TimeUnit.SECONDS.sleep(seconds);
        }
        return new StateResult(input, stateDef.path("Next").asText(null));
    }

    private StateResult executeSucceedState(JsonNode stateDef, JsonNode input, boolean jsonata, JsonNode context) {
        if (jsonata) {
            JsonNode output = applyJsonataOutput(stateDef, input, input, context);
            return new StateResult(output, null);
        }
        return new StateResult(applyOutputPath(stateDef, input, input), null);
    }

    private StateResult executeFail(JsonNode stateDef, JsonNode input, boolean jsonata, JsonNode context) {
        String error = stateDef.path("Error").asText(null);
        String cause = stateDef.path("Cause").asText(null);
        if (jsonata) {
            JsonNode statesVar = buildStatesVar(input, null, context);
            if (error != null && JsonataEvaluator.isExpression(error)) {
                error = jsonataEvaluator.evaluate(error, statesVar).asText();
            }
            if (cause != null && JsonataEvaluator.isExpression(cause)) {
                cause = jsonataEvaluator.evaluate(cause, statesVar).asText();
            }
        }
        throw new FailStateException(error, cause);
    }

    private StateResult executeParallelState(String name, JsonNode stateDef, JsonNode input, StateMachine sm,
                                              boolean jsonata, String topLevelQueryLanguage, JsonNode context) throws Exception {
        JsonNode branches = stateDef.path("Branches");
        List<Future<JsonNode>> futures = new ArrayList<>();

        for (JsonNode branch : branches) {
            String startAt = branch.path("StartAt").asText();
            JsonNode branchStates = branch.path("States");
            JsonNode capturedInput = input;

            // Run each branch on its own worker thread under the execution's account: the request
            // scope is thread-bound, so without this a branch's Task integrations would resolve to
            // the default account rather than the execution's.
            futures.add(executor.submit(() -> callUnderExecutionAccount(sm,
                    () -> executeBranch(startAt, branchStates, capturedInput, sm, topLevelQueryLanguage, context))));
        }

        int timeoutSeconds = stateDef.path("TimeoutSeconds").asInt(0);
        long deadlineNanos = timeoutSeconds > 0
                ? System.nanoTime() + TimeUnit.SECONDS.toNanos(timeoutSeconds)
                : Long.MAX_VALUE;

        ArrayNode results = objectMapper.createArrayNode();
        for (Future<JsonNode> future : futures) {
            long remainingNanos = deadlineNanos - System.nanoTime();
            if (remainingNanos <= 0) {
                futures.forEach(f -> f.cancel(true));
                throw new FailStateException("States.Timeout",
                        "Parallel state timed out after " + timeoutSeconds + " seconds");
            }
            try {
                results.add(future.get(remainingNanos, TimeUnit.NANOSECONDS));
            } catch (java.util.concurrent.TimeoutException e) {
                futures.forEach(f -> f.cancel(true));
                throw new FailStateException("States.Timeout",
                        "Parallel state timed out after " + timeoutSeconds + " seconds");
            }
        }

        if (jsonata) {
            JsonNode output = applyJsonataOutput(stateDef, input, results, context);
            return new StateResult(output, stateDef.path("Next").asText(null));
        }

        // ResultSelector transforms the raw branch results before ResultPath merges them in.
        JsonNode selected = stateDef.has("ResultSelector")
                ? resolveParameters(stateDef.get("ResultSelector"), results, context)
                : results;
        JsonNode output = mergeResult(stateDef, input, selected);
        output = applyOutputPath(stateDef, input, output);
        return new StateResult(output, stateDef.path("Next").asText(null));
    }

    private StateResult executeMapState(String name, JsonNode stateDef, JsonNode input, StateMachine sm,
                                         boolean jsonata, String topLevelQueryLanguage, JsonNode context) throws Exception {
        if (stateDef.has("ItemReader")) {
            String mode = stateDef.path("ItemProcessor").path("ProcessorConfig").path("Mode").asText("INLINE");
            if (!"DISTRIBUTED".equals(mode)) {
                throw new FailStateException("States.Runtime",
                        "The ItemReader, ItemBatcher and ResultWriter fields are not supported for INLINE maps");
            }
        }
        ResolvedMapItems resolvedItems = resolveMapItems(stateDef, input, jsonata, context);
        JsonNode items = resolvedItems.items();

        if (!items.isArray()) {
            throw new FailStateException("States.Runtime", "Items must reference an array");
        }

        // Support both Iterator (legacy) and ItemProcessor (current AWS naming)
        JsonNode iterator = stateDef.has("ItemProcessor") ? stateDef.get("ItemProcessor") : stateDef.path("Iterator");
        String startAt = iterator.path("StartAt").asText();
        JsonNode iteratorStates = iterator.path("States");

        // Determine which transformation field is present (ItemSelector is current; Parameters is legacy)
        JsonNode itemTransform = stateDef.has("ItemSelector") ? stateDef.get("ItemSelector")
                : stateDef.has("Parameters") ? stateDef.get("Parameters") : null;

        // Resolve InputPath before iterating so $. in ItemSelector sees the Map state's effective input
        JsonNode mapInput = applyInputPath(stateDef, input);

        ArrayNode results = objectMapper.createArrayNode();
        int index = 0;
        for (JsonNode item : items) {
            ObjectNode iterContext = ((ObjectNode) context).deepCopy();
            ObjectNode mapCtx = objectMapper.createObjectNode();
            ObjectNode mapItem = objectMapper.createObjectNode();
            mapItem.put("Index", index);
            if (resolvedItems.source() == MapItemsSource.ITEM_READER_OBJECT) {
                mapItem.put("Key", item.path("Key").asText());
                mapItem.set("Value", item.get("Value"));
            } else {
                mapItem.set("Value", item);
            }
            mapCtx.set("Item", mapItem);
            iterContext.set("Map", mapCtx);

            JsonNode iterInput = item;
            if (itemTransform != null) {
                // $ in ItemSelector resolves against the Map state's effective input, not the item.
                iterInput = resolveParameters(itemTransform, mapInput, iterContext);
            }
            results.add(executeBranch(startAt, iteratorStates, iterInput, sm, topLevelQueryLanguage, iterContext));
            index++;
        }

        if (jsonata) {
            JsonNode output = applyJsonataOutput(stateDef, input, results, context);
            return new StateResult(output, stateDef.path("Next").asText(null));
        }

        // ResultSelector transforms the raw iteration results before ResultPath merges them in.
        JsonNode selected = stateDef.has("ResultSelector")
                ? resolveParameters(stateDef.get("ResultSelector"), results, context)
                : results;
        JsonNode output = mergeResult(stateDef, input, selected);
        output = applyOutputPath(stateDef, input, output);
        return new StateResult(output, stateDef.path("Next").asText(null));
    }

    private ResolvedMapItems resolveMapItems(JsonNode stateDef, JsonNode input,
                                             boolean jsonata, JsonNode context) throws Exception {
        if (jsonata && stateDef.has("Items")) {
            JsonNode itemsNode = stateDef.get("Items");
            if (itemsNode.isTextual() && JsonataEvaluator.isExpression(itemsNode.asText())) {
                JsonNode statesVar = buildStatesVar(input, null, context);
                return new ResolvedMapItems(jsonataEvaluator.evaluate(itemsNode.asText(), statesVar),
                        MapItemsSource.DEFAULT);
            }
            return new ResolvedMapItems(itemsNode, MapItemsSource.DEFAULT);
        }

        if (stateDef.has("ItemReader")) {
            return resolveItemReaderItems(stateDef.get("ItemReader"), input, context, jsonata);
        }

        JsonNode itemsPath = stateDef.path("ItemsPath");
        return new ResolvedMapItems(itemsPath.isMissingNode() ? input : resolvePath(itemsPath.asText("$"), input),
                MapItemsSource.DEFAULT);
    }

    private ResolvedMapItems resolveItemReaderItems(JsonNode itemReader, JsonNode input,
                                                    JsonNode context, boolean jsonata) throws Exception {
        String resource = itemReader.path("Resource").asText(null);
        if ("arn:aws:states:::s3:listObjectsV2".equals(resource)) {
            throw new FailStateException("States.ItemReaderFailed",
                    "ItemReader resource arn:aws:states:::s3:listObjectsV2 is not yet implemented by the emulator");
        }
        if (!"arn:aws:states:::s3:getObject".equals(resource)) {
            throw new FailStateException("States.Runtime", "Unsupported ItemReader resource: " + resource);
        }

        String inputType = itemReader.path("ReaderConfig").path("InputType").asText(null);
        if (!"JSON".equals(inputType)) {
            throw new FailStateException("States.ItemReaderFailed",
                    "ItemReader InputType " + inputType + " is not yet implemented by the emulator");
        }

        JsonNode resolvedParameters;
        if (jsonata && itemReader.has("Arguments")) {
            JsonNode statesVar = buildStatesVar(input, null, context);
            resolvedParameters = jsonataEvaluator.resolveTemplate(itemReader.get("Arguments"), statesVar);
        } else {
            JsonNode parameters = itemReader.path("Parameters");
            resolvedParameters = resolveParameters(parameters, input, context);
        }
        String bucket = resolvedParameters.path("Bucket").asText(null);
        String key = resolvedParameters.path("Key").asText(null);
        if (bucket == null || key == null) {
            throw new FailStateException("States.Runtime", "ItemReader Parameters must include Bucket and Key");
        }

        try {
            S3Object object = s3Service.getObject(bucket, key);
            JsonNode items = objectMapper.readTree(object.getData());
            items = applyItemsPointer(itemReader, items);
            if (items.isObject()) {
                return new ResolvedMapItems(applyMaxItems(itemReader, normalizeObjectItems(items)),
                        MapItemsSource.ITEM_READER_OBJECT);
            }
            if (!items.isArray()) {
                throw new FailStateException("States.ItemReaderFailed",
                        "Attempting to map over non-iterable node.");
            }
            return new ResolvedMapItems(applyMaxItems(itemReader, items), MapItemsSource.ITEM_READER_ARRAY);
        } catch (AwsException e) {
            throw new FailStateException("States.ItemReaderFailed", e.getMessage());
        } catch (FailStateException e) {
            throw e;
        } catch (Exception e) {
            throw new FailStateException("States.ItemReaderFailed",
                    e.getMessage() != null ? e.getMessage() : "Failed to parse ItemReader input");
        }
    }

    private ArrayNode normalizeObjectItems(JsonNode items) {
        ArrayNode normalized = objectMapper.createArrayNode();
        items.fields().forEachRemaining(entry -> {
            ObjectNode objectItem = objectMapper.createObjectNode();
            objectItem.put("Key", entry.getKey());
            objectItem.set("Value", entry.getValue());
            normalized.add(objectItem);
        });
        return normalized;
    }

    private JsonNode applyItemsPointer(JsonNode itemReader, JsonNode items) {
        String itemsPointer = itemReader.path("ReaderConfig").path("ItemsPointer").asText(null);
        if (itemsPointer == null || itemsPointer.isEmpty()) {
            return items;
        }

        JsonNode pointedItems = items.at(itemsPointer);
        if (pointedItems.isMissingNode()) {
            throw new FailStateException("States.ItemReaderFailed",
                    "The provided ReaderConfig.ItemsPointer does not match any valid path in the JSON structure.");
        }
        return pointedItems;
    }

    private JsonNode applyMaxItems(JsonNode itemReader, JsonNode items) {
        int maxItems = itemReader.path("ReaderConfig").path("MaxItems").asInt(0);
        if (maxItems <= 0 || !items.isArray() || items.size() <= maxItems) {
            return items;
        }

        ArrayNode limited = objectMapper.createArrayNode();
        for (int i = 0; i < maxItems; i++) {
            limited.add(items.get(i));
        }
        return limited;
    }

    private JsonNode executeBranch(String startAt, JsonNode states, JsonNode input, StateMachine sm,
                                    String topLevelQueryLanguage, JsonNode context) throws Exception {
        List<HistoryEvent> ignored = new ArrayList<>();
        AtomicLong eventId = new AtomicLong(0);
        JsonNode currentInput = input;
        String currentState = startAt;

        while (currentState != null) {
            JsonNode stateDef = states.path(currentState);
            if (stateDef.isMissingNode()) {
                throw new RuntimeException("State not found: " + currentState);
            }
            String type = stateDef.path("Type").asText();
            boolean stateJsonata = isJsonata(stateDef, topLevelQueryLanguage);
            StateResult result;
            try {
                result = executeState(currentState, type, stateDef, currentInput, ignored, eventId, sm,
                        stateJsonata, topLevelQueryLanguage, context);
            } catch (FailStateException e) {
                StateResult caught = handleCatch(stateDef, currentInput, e);
                if (caught == null) {
                    throw e;
                }
                result = caught;
            }
            currentInput = result.output();
            currentState = result.nextState();
            if ("Succeed".equals(type) || stateDef.path("End").asBoolean(false)) {
                currentState = null;
            }
        }
        return currentInput;
    }

    // ──────────────────────────── JSONata helpers ────────────────────────────

    private boolean isJsonata(JsonNode stateDef, String topLevelQueryLanguage) {
        String stateQL = stateDef.path("QueryLanguage").asText(null);
        return QUERY_LANGUAGE_JSONATA.equals(stateQL != null ? stateQL : topLevelQueryLanguage);
    }

    private JsonNode buildStatesVar(JsonNode input, JsonNode result) {
        return buildStatesVar(input, result, null);
    }

    private JsonNode buildStatesVar(JsonNode input, JsonNode result, JsonNode context) {
        ObjectNode states = objectMapper.createObjectNode();
        states.set("input", input);
        if (result != null) {
            states.set("result", result);
        }
        if (context != null) {
            states.set("context", context);
        }
        return states;
    }

    /**
     * Build the $states.context object for an execution.
     * Contains Execution metadata (Id, Input, Name, RoleArn, StartTime).
     */
    private JsonNode buildContext(Execution exec, StateMachine sm) {
        ObjectNode context = objectMapper.createObjectNode();
        ObjectNode execution = objectMapper.createObjectNode();
        execution.put("Id", exec.getExecutionArn());
        execution.put("Name", exec.getName());
        execution.put("RoleArn", sm.getRoleArn());
        execution.put("StartTime", java.time.Instant.ofEpochMilli((long) (exec.getStartDate() * 1000)).toString());
        if (exec.getInput() != null) {
            execution.set("Input", parseInput(exec.getInput()));
        }
        context.set("Execution", execution);
        ObjectNode stateMachine = objectMapper.createObjectNode();
        stateMachine.put("Id", sm.getStateMachineArn());
        stateMachine.put("Name", sm.getName());
        context.set("StateMachine", stateMachine);
        // Task node — Token is populated by executeTaskState when waitForTaskToken is active
        ObjectNode task = objectMapper.createObjectNode();
        task.putNull("Token");
        context.set("Task", task);
        return context;
    }

    private void updateStateContext(JsonNode execContext, String stateName) {
        ObjectNode context = (ObjectNode) execContext;
        ObjectNode state = objectMapper.createObjectNode();
        state.put("Name", stateName);
        state.put("EnteredTime", java.time.Instant.now().toString());
        state.put("RetryCount", 0);
        context.set("State", state);
    }

    /**
     * Apply JSONata Output field. If Output is present, resolve it as a template with $states bound.
     * If absent, use the result directly (or input if result is null).
     */
    private JsonNode applyJsonataOutput(JsonNode stateDef, JsonNode input, JsonNode result, JsonNode context) {
        if (!stateDef.has("Output")) {
            return result != null ? result : input;
        }
        JsonNode statesVar = buildStatesVar(input, result, context);
        return jsonataEvaluator.resolveTemplate(stateDef.get("Output"), statesVar);
    }

    // ──────────────────────────── Path resolution ────────────────────────────

    private JsonNode applyInputPath(JsonNode stateDef, JsonNode input) {
        if (!stateDef.has("InputPath")) {
            return input;
        }
        String path = stateDef.get("InputPath").asText();
        if (path == null || path.equals("null")) {
            return NullNode.getInstance();
        }
        return resolvePath(path, input);
    }

    private JsonNode mergeResult(JsonNode stateDef, JsonNode input, JsonNode result) throws Exception {
        if (!stateDef.has("ResultPath")) {
            return result;
        }
        String resultPath = stateDef.get("ResultPath").asText();
        if (resultPath == null || resultPath.equals("null")) {
            return input;
        }
        if ("$".equals(resultPath)) {
            return result;
        }
        // Merge result into input at the given path
        if (!input.isObject()) {
            return result;
        }
        ObjectNode merged = input.deepCopy();
        setPath(merged, resultPath, result);
        return merged;
    }

    private JsonNode applyOutputPath(JsonNode stateDef, JsonNode input, JsonNode output) {
        if (!stateDef.has("OutputPath")) {
            return output;
        }
        String path = stateDef.get("OutputPath").asText();
        if (path == null || path.equals("null")) {
            return NullNode.getInstance();
        }
        return resolvePath(path, output);
    }

    JsonNode resolveParameters(JsonNode parameters, JsonNode input, JsonNode context) throws Exception {
        if (parameters.isObject()) {
            ObjectNode resolved = objectMapper.createObjectNode();
            Iterator<Map.Entry<String, JsonNode>> fields = parameters.fields();
            while (fields.hasNext()) {
                Map.Entry<String, JsonNode> entry = fields.next();
                String key = entry.getKey();
                JsonNode val = entry.getValue();
                if (key.endsWith(".$")) {
                    String realKey = key.substring(0, key.length() - 2);
                    String path = val.asText();
                    if (path.startsWith("$$.")) {
                        // Context reference: $$. → resolve against context as $.
                        resolved.set(realKey, resolvePath("$." + path.substring(3), context));
                    } else if ("$$".equals(path)) {
                        resolved.set(realKey, context);
                    } else {
                        // Pass the Context Object through so a $$. reference nested inside an
                        // intrinsic (e.g. States.Format(..., $$.Map.Item.Value.x)) can resolve it;
                        // for a plain $. or States.* input reference, context is simply ignored.
                        resolved.set(realKey, resolvePath(path, input, context));
                    }
                } else if (val.isObject() || val.isArray()) {
                    resolved.set(key, resolveParameters(val, input, context));
                } else {
                    resolved.set(key, val);
                }
            }
            return resolved;
        }
        if (parameters.isArray()) {
            // Payload templates resolve .$ references at any depth, including inside arrays
            // (e.g. an ECS Overrides.ContainerOverrides[].Environment[].Value.$).
            ArrayNode resolvedArray = objectMapper.createArrayNode();
            for (JsonNode element : parameters) {
                resolvedArray.add(resolveParameters(element, input, context));
            }
            return resolvedArray;
        }
        return parameters;
    }

    JsonNode resolvePath(String path, JsonNode root) {
        return resolvePath(path, root, null);
    }

    /**
     * Resolve a JSONPath reference or {@code States.*} intrinsic. When {@code context} is
     * non-null it is the Context Object ({@code $$}), letting intrinsic arguments reference it
     * (e.g. a {@code $$.Map.Item.Value.x} argument nested inside {@code States.Format(...)}).
     * It is null for ordinary input-only resolution, which preserves existing behavior.
     *
     * <p>Most callers do not distinguish an absent path from an explicit null, so both collapse
     * to null; callers that care about presence (e.g. {@code IsPresent}) use {@link #resolvePathNode}.
     */
    JsonNode resolvePath(String path, JsonNode root, JsonNode context) {
        JsonNode node = resolvePathNode(path, root, context);
        return node.isMissingNode() ? NullNode.getInstance() : node;
    }

    JsonNode resolvePathNode(String path, JsonNode root) {
        return resolvePathNode(path, root, null);
    }

    /**
     * Resolves a reference path while preserving the distinction between an explicit null value
     * (returns a {@link NullNode}) and a missing/absent path (returns a {@link MissingNode}).
     * {@link #resolvePath} collapses both to null; only callers that care about presence
     * (e.g. {@code IsPresent}) should use this variant. When {@code context} is non-null it is the
     * Context Object ({@code $$}) available to {@code States.*} intrinsic arguments.
     */
    JsonNode resolvePathNode(String path, JsonNode root, JsonNode context) {
        if (path == null || "$".equals(path)) {
            return root;
        }
        if (path.startsWith("States.")) {
            return evaluateIntrinsic(path, root, context);
        }
        // Support dotted ($.a.b) and root-bracket ($[*], $[0]) forms; anything else is unsupported.
        if (!path.startsWith("$.") && !path.startsWith("$[")) {
            return MissingNode.getInstance();
        }
        return walkPath(splitPathSegments(path), 0, root);
    }

    /**
     * Splits a reference path into segments, normalizing bracket notation into dot segments so the
     * AWS bracket forms reduce to the same walk as the dot forms:
     * {@code $.Regions[*].RegionName} and {@code $.Regions.*.RegionName} both yield
     * {@code [Regions, *, RegionName]}, and {@code $[*][*]} yields {@code [*, *]}.
     *
     * <p>Limitation: every literal dot is treated as a segment separator, so a field name that
     * itself contains a dot is mis-split. AWS's bracket-quoted escape hatch ({@code $.a['b.c']})
     * is not supported; this matches the prior behavior and is rare in ASL reference paths.
     */
    private String[] splitPathSegments(String path) {
        String rest = path.substring(1);                  // drop leading '$'
        rest = rest.replaceAll("\\[(\\*|\\d+)]", ".$1");  // [*] -> .*, [0] -> .0
        rest = rest.replaceAll("\\.{2,}", ".");           // collapse ".[0]" -> "..0" -> ".0"
        if (rest.startsWith(".")) {
            rest = rest.substring(1);
        }
        return rest.isEmpty() ? new String[0] : rest.split("\\.");
    }

    /**
     * Walks the remaining path segments from {@code idx}. A {@code *} segment projects the rest of
     * the path over each element of the current array and collects the results into an array
     * (e.g. {@code $.Regions[*].RegionName}). When the projected suffix contains a further wildcard,
     * the nested projections are flattened one level so {@code $[*][*]} flattens an array of arrays.
     * A purely numeric segment indexes into an array (e.g. {@code $.items[0]}).
     */
    private JsonNode walkPath(String[] parts, int idx, JsonNode current) {
        for (int i = idx; i < parts.length; i++) {
            if (current == null || current.isMissingNode() || current.isNull()) {
                return MissingNode.getInstance();
            }
            String part = parts[i];
            if ("*".equals(part)) {
                if (!current.isArray()) {
                    return MissingNode.getInstance();
                }
                boolean flattenSub = false;
                for (int j = i + 1; j < parts.length; j++) {
                    if ("*".equals(parts[j])) {
                        flattenSub = true;
                        break;
                    }
                }
                ArrayNode projected = objectMapper.createArrayNode();
                for (JsonNode element : current) {
                    JsonNode value = walkPath(parts, i + 1, element);
                    // Only absent matches are skipped; an explicit null is a real value and is kept,
                    // so $[*].field over [{"field":null},{"field":"x"}] yields [null,"x"].
                    if (value == null || value.isMissingNode()) {
                        continue;
                    }
                    if (flattenSub && value.isArray()) {
                        value.forEach(projected::add);
                    } else {
                        projected.add(value);
                    }
                }
                return projected;
            }
            if (current.isArray() && isArrayIndex(part)) {
                current = current.path(Integer.parseInt(part));
            } else {
                current = current.path(part);
            }
        }
        return current;
    }

    private static boolean isArrayIndex(String segment) {
        if (segment.isEmpty()) {
            return false;
        }
        for (int i = 0; i < segment.length(); i++) {
            if (!Character.isDigit(segment.charAt(i))) {
                return false;
            }
        }
        return true;
    }

    /**
     * Evaluate a JSONPath-mode intrinsic function (States.*).
     * Supports: States.StringToJson, States.JsonToString, States.Format,
     *           States.Array, States.ArrayLength, States.ArrayContains, States.MathAdd, States.UUID.
     * Throws FailStateException("States.Runtime") for unrecognized functions.
     */
    private JsonNode evaluateIntrinsic(String expr, JsonNode root, JsonNode context) {
        int parenOpen = expr.indexOf('(');
        int parenClose = expr.lastIndexOf(')');
        if (parenOpen < 0 || parenClose < 0) {
            throw new FailStateException("States.Runtime", "Malformed intrinsic function: " + expr);
        }
        String fnName = expr.substring(0, parenOpen).trim();
        String argsStr = expr.substring(parenOpen + 1, parenClose).trim();

        return switch (fnName) {
            case "States.StringToJson" -> {
                JsonNode arg = resolveIntrinsicArg(argsStr, root, context);
                try {
                    yield objectMapper.readTree(arg.asText());
                } catch (Exception e) {
                    throw new FailStateException("States.Runtime",
                            "States.StringToJson could not parse: " + arg.asText());
                }
            }
            case "States.JsonToString" -> {
                JsonNode arg = resolveIntrinsicArg(argsStr, root, context);
                try {
                    yield objectMapper.getNodeFactory().textNode(objectMapper.writeValueAsString(arg));
                } catch (Exception e) {
                    throw new FailStateException("States.Runtime", "States.JsonToString failed: " + e.getMessage());
                }
            }
            case "States.Format" -> {
                List<String> parts = splitIntrinsicArgs(argsStr);
                if (parts.isEmpty()) {
                    throw new FailStateException("States.Runtime", "States.Format requires at least one argument");
                }
                String template = unquoteString(parts.get(0));
                StringBuilder sb = new StringBuilder();
                int argIdx = 1;
                for (int i = 0; i < template.length(); i++) {
                    if (i + 1 < template.length() && template.charAt(i) == '{' && template.charAt(i + 1) == '}') {
                        if (argIdx >= parts.size()) {
                            throw new FailStateException("States.Runtime", "States.Format: not enough arguments");
                        }
                        JsonNode argVal = resolveIntrinsicArg(parts.get(argIdx++).trim(), root, context);
                        sb.append(argVal.isTextual() ? argVal.asText() : argVal.toString());
                        i++; // skip '}'
                    } else {
                        sb.append(template.charAt(i));
                    }
                }
                yield objectMapper.getNodeFactory().textNode(sb.toString());
            }
            case "States.Array" -> {
                List<String> parts = splitIntrinsicArgs(argsStr);
                ArrayNode arr = objectMapper.createArrayNode();
                for (String part : parts) {
                    arr.add(resolveIntrinsicArg(part.trim(), root, context));
                }
                yield arr;
            }
            case "States.ArrayLength" -> {
                JsonNode arg = resolveIntrinsicArg(argsStr, root, context);
                if (!arg.isArray()) {
                    throw new FailStateException("States.Runtime", "States.ArrayLength requires an array");
                }
                yield objectMapper.getNodeFactory().numberNode(arg.size());
            }
            case "States.MathAdd" -> {
                List<String> parts = splitIntrinsicArgs(argsStr);
                if (parts.size() != 2) {
                    throw new FailStateException("States.Runtime", "States.MathAdd requires exactly 2 arguments");
                }
                JsonNode a = resolveIntrinsicArg(parts.get(0).trim(), root, context);
                JsonNode b = resolveIntrinsicArg(parts.get(1).trim(), root, context);
                yield objectMapper.getNodeFactory().numberNode(a.asLong() + b.asLong());
            }
            case "States.ArrayContains" -> {
                List<String> parts = splitIntrinsicArgs(argsStr);
                if (parts.size() != 2) {
                    throw new FailStateException("States.Runtime",
                            "States.ArrayContains requires exactly 2 arguments");
                }
                JsonNode array = resolveIntrinsicArg(parts.get(0).trim(), root, context);
                JsonNode value = resolveIntrinsicArg(parts.get(1).trim(), root, context);
                if (!array.isArray()) {
                    // AWS throws rather than silently returning false, matching States.ArrayLength.
                    throw new FailStateException("States.Runtime",
                            "States.ArrayContains: first argument must be an array");
                }
                boolean contains = false;
                for (JsonNode element : array) {
                    if (element.equals(value)) {
                        contains = true;
                        break;
                    }
                }
                yield objectMapper.getNodeFactory().booleanNode(contains);
            }
            case "States.UUID" -> {
                yield objectMapper.getNodeFactory().textNode(java.util.UUID.randomUUID().toString());
            }
            default -> throw new FailStateException("States.Runtime",
                    "Unsupported intrinsic function: " + fnName);
        };
    }

    /**
     * Resolve a single intrinsic argument: either a $.path reference, a quoted string literal,
     * or a numeric literal.
     */
    private JsonNode resolveIntrinsicArg(String arg, JsonNode root, JsonNode context) {
        arg = arg.trim();
        // A $$.-prefixed argument references the Context Object; resolve it against context
        // (as a $. path) so intrinsics can read e.g. $$.Map.Item.Value.x or $$.Execution.Id.
        // When context is null these fall through to the bare-path branch and a $$. arg formats
        // as the literal string "null". This is Floci-internal transitional behavior, not AWS
        // semantics: on real AWS the Context Object always exists. Every payload-template call
        // site already threads context; this fallback (and the noContext_* test pinning it) only
        // exists until context is also threaded into the remaining resolvePath callers.
        if (context != null && arg.startsWith("$$.")) {
            return resolvePath("$." + arg.substring(3), context);
        }
        if (context != null && "$$".equals(arg)) {
            return context;
        }
        if (arg.startsWith("$.") || "$".equals(arg)) {
            return resolvePath(arg, root, context);
        }
        if (arg.startsWith("'") && arg.endsWith("'")) {
            return objectMapper.getNodeFactory().textNode(arg.substring(1, arg.length() - 1));
        }
        if (arg.startsWith("\"") && arg.endsWith("\"")) {
            return objectMapper.getNodeFactory().textNode(arg.substring(1, arg.length() - 1));
        }
        try {
            return objectMapper.getNodeFactory().numberNode(Long.parseLong(arg));
        } catch (NumberFormatException e1) {
            try {
                return objectMapper.getNodeFactory().numberNode(Double.parseDouble(arg));
            } catch (NumberFormatException e2) {
                // fall through: treat as bare path (may itself be a nested States.* intrinsic)
                return resolvePath(arg, root, context);
            }
        }
    }

    /**
     * Split a comma-separated intrinsic args string, respecting nested parentheses and quoted strings.
     */
    private List<String> splitIntrinsicArgs(String argsStr) {
        List<String> result = new ArrayList<>();
        int depth = 0;
        boolean inSingleQuote = false;
        boolean inDoubleQuote = false;
        int start = 0;
        for (int i = 0; i < argsStr.length(); i++) {
            char c = argsStr.charAt(i);
            if (c == '\'' && !inDoubleQuote) inSingleQuote = !inSingleQuote;
            else if (c == '"' && !inSingleQuote) inDoubleQuote = !inDoubleQuote;
            else if (!inSingleQuote && !inDoubleQuote) {
                if (c == '(') depth++;
                else if (c == ')') depth--;
                else if (c == ',' && depth == 0) {
                    result.add(argsStr.substring(start, i).trim());
                    start = i + 1;
                }
            }
        }
        if (start < argsStr.length()) {
            result.add(argsStr.substring(start).trim());
        }
        return result;
    }

    private String unquoteString(String s) {
        s = s.trim();
        if ((s.startsWith("'") && s.endsWith("'")) || (s.startsWith("\"") && s.endsWith("\""))) {
            return s.substring(1, s.length() - 1);
        }
        return s;
    }

    private void setPath(ObjectNode root, String path, JsonNode value) {
        if (!path.startsWith("$.") && !"$".equals(path)) {
            return;
        }
        if ("$".equals(path)) {
            return;
        }
        String[] parts = path.substring(2).split("\\.");
        ObjectNode current = root;
        for (int i = 0; i < parts.length - 1; i++) {
            JsonNode next = current.path(parts[i]);
            if (!next.isObject()) {
                ObjectNode newNode = objectMapper.createObjectNode();
                current.set(parts[i], newNode);
                current = newNode;
            } else {
                current = (ObjectNode) next;
            }
        }
        current.set(parts[parts.length - 1], value);
    }

    private String globToRegex(String glob) {
        return "\\Q" + glob.replace("*", "\\E.*\\Q") + "\\E";
    }

    // ──────────────────────────── History helpers ────────────────────────────

    private void addEvent(List<HistoryEvent> history, AtomicLong counter, String type,
                          Long prevId, Map<String, Object> details) {
        HistoryEvent event = new HistoryEvent();
        event.setId(counter.incrementAndGet());
        event.setType(type);
        event.setPreviousEventId(prevId);
        event.setDetails(details);
        history.add(event);
    }

    private void failExecution(Execution exec, List<HistoryEvent> history, AtomicLong eventId, FailStateException e) {
        exec.setStatus("FAILED");
        exec.setStopDate(System.currentTimeMillis() / 1000.0);
        String failError = e.error != null ? e.error : "States.Runtime";
        String failCause = e.cause != null ? e.cause : "";
        exec.setError(failError);
        exec.setCause(failCause);
        addEvent(history, eventId, "ExecutionFailed", null,
                Map.of("error", failError, "cause", failCause));
    }

    private StateResult handleCatch(JsonNode stateDef, JsonNode input, FailStateException failure) throws Exception {
        JsonNode catchers = stateDef.path("Catch");
        if (!catchers.isArray()) {
            return null;
        }
        String error = failure.error != null ? failure.error : "States.Runtime";
        String cause = failure.cause != null ? failure.cause : "";
        for (JsonNode catcher : catchers) {
            if (!catchMatches(catcher, error)) {
                continue;
            }
            String next = catcher.path("Next").asText(null);
            if (next == null || next.isBlank()) {
                return null;
            }
            ObjectNode errorOutput = objectMapper.createObjectNode();
            errorOutput.put("Error", error);
            errorOutput.put("Cause", cause);
            return new StateResult(mergeResult(catcher, input, errorOutput), next);
        }
        return null;
    }

    private boolean catchMatches(JsonNode catcher, String error) {
        JsonNode errors = catcher.path("ErrorEquals");
        if (!errors.isArray()) {
            return false;
        }
        for (JsonNode candidate : errors) {
            String expected = candidate.asText();
            if (expected.equals(error)) {
                return true;
            }
            if ("States.TaskFailed".equals(expected)
                    && !"States.Timeout".equals(error)
                    && !"States.Runtime".equals(error)) {
                return true;
            }
            if ("States.ALL".equals(expected)
                    && !"States.Runtime".equals(error)
                    && !"States.DataLimitExceeded".equals(error)) {
                return true;
            }
        }
        return false;
    }

    private String stateEnteredEventType(String stateType) {
        return stateType + "StateEntered";
    }

    private String stateExitedEventType(String stateType) {
        return stateType + "StateExited";
    }

    private JsonNode parseInput(String input) {
        if (input == null || input.isBlank()) {
            return objectMapper.createObjectNode();
        }
        try {
            return objectMapper.readTree(input);
        } catch (Exception e) {
            return objectMapper.createObjectNode();
        }
    }

    private String extractRegionFromArn(String arn) {
        return AwsArnUtils.regionOrDefault(arn, "us-east-1");
    }

    record StateResult(JsonNode output, String nextState) {}

    static class FailStateException extends RuntimeException {
        final String error;
        final String cause;

        FailStateException(String error, String cause) {
            super(error + ": " + cause);
            this.error = error;
            this.cause = cause;
        }
    }
}
