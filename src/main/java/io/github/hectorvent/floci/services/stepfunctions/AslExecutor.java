package io.github.hectorvent.floci.services.stepfunctions;

import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.core.common.AwsErrorResponse;
import io.github.hectorvent.floci.services.dynamodb.DynamoDbJsonHandler;
import io.github.hectorvent.floci.services.dynamodb.DynamoDbService;
import io.github.hectorvent.floci.services.lambda.LambdaExecutorService;
import io.github.hectorvent.floci.services.lambda.LambdaFunctionStore;
import io.github.hectorvent.floci.services.lambda.model.InvocationType;
import io.github.hectorvent.floci.services.lambda.model.InvokeResult;
import io.github.hectorvent.floci.services.lambda.model.LambdaFunction;
import io.github.hectorvent.floci.services.sqs.SqsJsonHandler;
import io.github.hectorvent.floci.services.stepfunctions.model.Execution;
import io.github.hectorvent.floci.services.stepfunctions.model.HistoryEvent;
import io.github.hectorvent.floci.services.stepfunctions.model.StateMachine;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.NullNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.UUID;
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

    private static final Logger LOG = Logger.getLogger(AslExecutor.class);
    private static final int MAX_WAIT_SECONDS = 30;

    private static final String QUERY_LANGUAGE_JSONATA = "JSONata";

    private final LambdaExecutorService lambdaExecutor;
    private final LambdaFunctionStore functionStore;
    private final DynamoDbService dynamoDbService;
    private final DynamoDbJsonHandler dynamoDbJsonHandler;
    private final SqsJsonHandler sqsJsonHandler;
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
                       SqsJsonHandler sqsJsonHandler,
                       ObjectMapper objectMapper, JsonataEvaluator jsonataEvaluator,
                       Instance<StepFunctionsService> sfnService) {
        this.lambdaExecutor = lambdaExecutor;
        this.functionStore = functionStore;
        this.dynamoDbService = dynamoDbService;
        this.dynamoDbJsonHandler = dynamoDbJsonHandler;
        this.sqsJsonHandler = sqsJsonHandler;
        this.objectMapper = objectMapper;
        this.jsonataEvaluator = jsonataEvaluator;
        this.sfnService = sfnService;
    }

    /**
     * Launches execution asynchronously. Calls onUpdate when execution status changes.
     */
    public void executeAsync(StateMachine sm, Execution exec, List<HistoryEvent> history,
                             BiConsumer<Execution, List<HistoryEvent>> onUpdate) {
        executor.submit(() -> doExecute(sm, exec, history, onUpdate));
    }

    /**
     * Runs execution synchronously on the calling thread. Blocks until the execution completes.
     */
    public void executeSync(StateMachine sm, Execution exec, List<HistoryEvent> history,
                            BiConsumer<Execution, List<HistoryEvent>> onUpdate) {
        try {
            Future<?> f = executor.submit(() -> doExecute(sm, exec, history, onUpdate));
            f.get(300, TimeUnit.SECONDS);
        } catch (java.util.concurrent.TimeoutException e) {
            exec.setStatus("TIMED_OUT");
            exec.setStopDate(System.currentTimeMillis() / 1000.0);
            onUpdate.accept(exec, history);
        } catch (Exception e) {
            LOG.warnv("Sync execution wait failed for {0}: {1}", exec.getExecutionArn(), e.getMessage());
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
                    exec.setStatus("FAILED");
                    exec.setStopDate(System.currentTimeMillis() / 1000.0);
                    String failError = e.error != null ? e.error : "States.Runtime";
                    String failCause = e.cause != null ? e.cause : "";
                    exec.setError(failError);
                    exec.setCause(failCause);
                    addEvent(history, eventId, "ExecutionFailed", null,
                            Map.of("error", failError, "cause", failCause));
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

        JsonNode result;
        if (stateDef.has("Result")) {
            result = stateDef.get("Result");
        } else {
            result = effectiveInput;
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

    private JsonNode invokeResource(String resource, JsonNode input, StateMachine sm, String taskToken) throws Exception {
        // Support Lambda resources: direct ARN or optimized integration
        String functionName = null;
        JsonNode lambdaPayload = input;

        if (resource.contains(":lambda:") && resource.contains(":function:")) {
            // Direct Lambda ARN: arn:aws:lambda:region:account:function:name
            functionName = resource.substring(resource.lastIndexOf(':') + 1);
        } else if (resource.equals("arn:aws:states:::lambda:invoke")) {
            // Optimized Lambda integration — function name and payload come from resolved input
            String fnRef = input.path("FunctionName").asText(null);
            if (fnRef != null) {
                functionName = fnRef.contains(":") ? fnRef.substring(fnRef.lastIndexOf(':') + 1) : fnRef;
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
                        tableName, filterExpression, exprAttrNames, exprAttrValues, scanFilter, limit, null, region);
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
                        conditionExpression, region);

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
            return !value.isMissingNode() == expectPresent;
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

            futures.add(executor.submit(() -> executeBranch(startAt, branchStates, capturedInput, sm, topLevelQueryLanguage, context)));
        }

        ArrayNode results = objectMapper.createArrayNode();
        for (Future<JsonNode> future : futures) {
            results.add(future.get(60, TimeUnit.SECONDS));
        }

        if (jsonata) {
            JsonNode output = applyJsonataOutput(stateDef, input, results, context);
            return new StateResult(output, stateDef.path("Next").asText(null));
        }

        JsonNode output = mergeResult(stateDef, input, results);
        output = applyOutputPath(stateDef, input, output);
        return new StateResult(output, stateDef.path("Next").asText(null));
    }

    private StateResult executeMapState(String name, JsonNode stateDef, JsonNode input, StateMachine sm,
                                         boolean jsonata, String topLevelQueryLanguage, JsonNode context) throws Exception {
        JsonNode items;
        if (jsonata && stateDef.has("Items")) {
            JsonNode itemsNode = stateDef.get("Items");
            if (itemsNode.isTextual() && JsonataEvaluator.isExpression(itemsNode.asText())) {
                JsonNode statesVar = buildStatesVar(input, null, context);
                items = jsonataEvaluator.evaluate(itemsNode.asText(), statesVar);
            } else {
                items = itemsNode;
            }
        } else {
            JsonNode itemsPath = stateDef.path("ItemsPath");
            items = itemsPath.isMissingNode() ? input : resolvePath(itemsPath.asText("$"), input);
        }

        if (!items.isArray()) {
            throw new FailStateException("States.Runtime", "Items must reference an array");
        }

        // Support both Iterator (legacy) and ItemProcessor (current AWS naming)
        JsonNode iterator = stateDef.has("ItemProcessor") ? stateDef.get("ItemProcessor") : stateDef.path("Iterator");
        String startAt = iterator.path("StartAt").asText();
        JsonNode iteratorStates = iterator.path("States");

        ArrayNode results = objectMapper.createArrayNode();
        for (JsonNode item : items) {
            results.add(executeBranch(startAt, iteratorStates, item, sm, topLevelQueryLanguage, context));
        }

        if (jsonata) {
            JsonNode output = applyJsonataOutput(stateDef, input, results, context);
            return new StateResult(output, stateDef.path("Next").asText(null));
        }

        JsonNode output = mergeResult(stateDef, input, results);
        output = applyOutputPath(stateDef, input, output);
        return new StateResult(output, stateDef.path("Next").asText(null));
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
            StateResult result = executeState(currentState, type, stateDef, currentInput, ignored, eventId, sm,
                    stateJsonata, topLevelQueryLanguage, context);
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

    private JsonNode resolveParameters(JsonNode parameters, JsonNode input, JsonNode context) throws Exception {
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
                        resolved.set(realKey, resolvePath(path, input));
                    }
                } else if (val.isObject()) {
                    resolved.set(key, resolveParameters(val, input, context));
                } else {
                    resolved.set(key, val);
                }
            }
            return resolved;
        }
        return parameters;
    }

    JsonNode resolvePath(String path, JsonNode root) {
        if (path == null || "$".equals(path)) {
            return root;
        }
        if (path.startsWith("States.")) {
            return evaluateIntrinsic(path, root);
        }
        if (!path.startsWith("$.")) {
            return NullNode.getInstance();
        }
        String[] parts = path.substring(2).split("\\.");
        JsonNode current = root;
        for (String part : parts) {
            if (current == null || current.isMissingNode()) {
                return NullNode.getInstance();
            }
            // Handle array index notation like field[0]
            if (part.contains("[")) {
                int bracketOpen = part.indexOf('[');
                int bracketClose = part.indexOf(']');
                String fieldName = part.substring(0, bracketOpen);
                int index = Integer.parseInt(part.substring(bracketOpen + 1, bracketClose));
                current = current.path(fieldName).path(index);
            } else {
                current = current.path(part);
            }
        }
        return current.isMissingNode() ? NullNode.getInstance() : current;
    }

    /**
     * Evaluate a JSONPath-mode intrinsic function (States.*).
     * Supports: States.StringToJson, States.JsonToString, States.Format,
     *           States.Array, States.ArrayLength, States.MathAdd, States.UUID.
     * Throws FailStateException("States.Runtime") for unrecognized functions.
     */
    private JsonNode evaluateIntrinsic(String expr, JsonNode root) {
        int parenOpen = expr.indexOf('(');
        int parenClose = expr.lastIndexOf(')');
        if (parenOpen < 0 || parenClose < 0) {
            throw new FailStateException("States.Runtime", "Malformed intrinsic function: " + expr);
        }
        String fnName = expr.substring(0, parenOpen).trim();
        String argsStr = expr.substring(parenOpen + 1, parenClose).trim();

        return switch (fnName) {
            case "States.StringToJson" -> {
                JsonNode arg = resolveIntrinsicArg(argsStr, root);
                try {
                    yield objectMapper.readTree(arg.asText());
                } catch (Exception e) {
                    throw new FailStateException("States.Runtime",
                            "States.StringToJson could not parse: " + arg.asText());
                }
            }
            case "States.JsonToString" -> {
                JsonNode arg = resolveIntrinsicArg(argsStr, root);
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
                        JsonNode argVal = resolveIntrinsicArg(parts.get(argIdx++).trim(), root);
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
                    arr.add(resolveIntrinsicArg(part.trim(), root));
                }
                yield arr;
            }
            case "States.ArrayLength" -> {
                JsonNode arg = resolveIntrinsicArg(argsStr, root);
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
                JsonNode a = resolveIntrinsicArg(parts.get(0).trim(), root);
                JsonNode b = resolveIntrinsicArg(parts.get(1).trim(), root);
                yield objectMapper.getNodeFactory().numberNode(a.asLong() + b.asLong());
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
    private JsonNode resolveIntrinsicArg(String arg, JsonNode root) {
        arg = arg.trim();
        if (arg.startsWith("$.") || "$".equals(arg)) {
            return resolvePath(arg, root);
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
                // fall through: treat as bare path
                return resolvePath(arg, root);
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
        String[] parts = arn.split(":");
        return parts.length > 3 ? parts[3] : "us-east-1";
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
