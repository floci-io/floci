package io.github.hectorvent.floci.services.swf;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.hectorvent.floci.core.common.RegionResolver;
import io.github.hectorvent.floci.services.swf.SwfService.Decision;
import io.github.hectorvent.floci.services.swf.SwfService.ExecutionFilter;
import io.github.hectorvent.floci.services.swf.SwfService.StartWorkflowExecutionRequest;
import io.github.hectorvent.floci.services.swf.model.SwfActivityTask;
import io.github.hectorvent.floci.services.swf.model.SwfActivityType;
import io.github.hectorvent.floci.services.swf.model.SwfDecisionTask;
import io.github.hectorvent.floci.services.swf.model.SwfDomain;
import io.github.hectorvent.floci.services.swf.model.SwfHistoryEvent;
import io.github.hectorvent.floci.services.swf.model.SwfWorkflowExecution;
import io.github.hectorvent.floci.services.swf.model.SwfWorkflowType;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.core.Response;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.function.Function;
import java.util.Map;
import java.util.Optional;

/**
 * Translates the SWF JSON 1.0 wire protocol to and from {@link SwfService}.
 *
 * <p>Two wire details this class is responsible for:
 * <ul>
 *   <li>Timestamps are epoch-second numbers, not ISO strings — the AWS SDKs parse
 *       {@code 1.786560427599E9} and reject a formatted date.</li>
 *   <li>Operations SWF models with no output shape must answer HTTP 200 with an
 *       empty body, not {@code {}} — {@code Content-Length: 0} is what the SDKs
 *       expect for a void operation.</li>
 * </ul>
 */
@ApplicationScoped
public class SwfJsonHandler {

    private final SwfService service;
    private final ObjectMapper objectMapper;
    private final RegionResolver regionResolver;

    @Inject
    public SwfJsonHandler(SwfService service, ObjectMapper objectMapper, RegionResolver regionResolver) {
        this.service = service;
        this.objectMapper = objectMapper;
        this.regionResolver = regionResolver;
    }

    public Response handle(String action, JsonNode request, String region) {
        return switch (action) {
            case "RegisterDomain" -> registerDomain(request, region);
            case "DescribeDomain" -> describeDomain(request, region);
            case "ListDomains" -> listDomains(request, region);
            case "DeprecateDomain" -> voidResponse(() -> service.deprecateDomain(region, text(request, "name")));
            case "UndeprecateDomain" -> voidResponse(() -> service.undeprecateDomain(region, text(request, "name")));

            case "RegisterWorkflowType" -> registerWorkflowType(request, region);
            case "DescribeWorkflowType" -> describeWorkflowType(request, region);
            case "ListWorkflowTypes" -> listWorkflowTypes(request, region);
            case "DeprecateWorkflowType" -> voidResponse(() -> service.deprecateWorkflowType(region,
                    text(request, "domain"), nested(request, "workflowType", "name"),
                    nested(request, "workflowType", "version")));
            case "UndeprecateWorkflowType" -> voidResponse(() -> service.undeprecateWorkflowType(region,
                    text(request, "domain"), nested(request, "workflowType", "name"),
                    nested(request, "workflowType", "version")));
            case "DeleteWorkflowType" -> voidResponse(() -> service.deleteWorkflowType(region,
                    text(request, "domain"), nested(request, "workflowType", "name"),
                    nested(request, "workflowType", "version")));

            case "RegisterActivityType" -> registerActivityType(request, region);
            case "DescribeActivityType" -> describeActivityType(request, region);
            case "ListActivityTypes" -> listActivityTypes(request, region);
            case "DeprecateActivityType" -> voidResponse(() -> service.deprecateActivityType(region,
                    text(request, "domain"), nested(request, "activityType", "name"),
                    nested(request, "activityType", "version")));
            case "UndeprecateActivityType" -> voidResponse(() -> service.undeprecateActivityType(region,
                    text(request, "domain"), nested(request, "activityType", "name"),
                    nested(request, "activityType", "version")));
            case "DeleteActivityType" -> voidResponse(() -> service.deleteActivityType(region,
                    text(request, "domain"), nested(request, "activityType", "name"),
                    nested(request, "activityType", "version")));

            case "StartWorkflowExecution" -> startWorkflowExecution(request, region);
            case "DescribeWorkflowExecution" -> describeWorkflowExecution(request, region);
            case "GetWorkflowExecutionHistory" -> getWorkflowExecutionHistory(request, region);
            case "ListOpenWorkflowExecutions" -> listExecutions(request, region, false);
            case "ListClosedWorkflowExecutions" -> listExecutions(request, region, true);
            case "CountOpenWorkflowExecutions" -> countExecutions(request, region, false);
            case "CountClosedWorkflowExecutions" -> countExecutions(request, region, true);
            case "CountPendingActivityTasks" -> count(service.countPendingActivityTasks(region,
                    text(request, "domain"), nested(request, "taskList", "name")));
            case "CountPendingDecisionTasks" -> count(service.countPendingDecisionTasks(region,
                    text(request, "domain"), nested(request, "taskList", "name")));

            case "PollForDecisionTask" -> pollForDecisionTask(request, region);
            case "RespondDecisionTaskCompleted" -> respondDecisionTaskCompleted(request);
            case "PollForActivityTask" -> pollForActivityTask(request, region);
            case "RecordActivityTaskHeartbeat" -> recordActivityTaskHeartbeat(request);
            case "RespondActivityTaskCompleted" -> voidResponse(() -> service.respondActivityTaskCompleted(
                    text(request, "taskToken"), text(request, "result")));
            case "RespondActivityTaskFailed" -> voidResponse(() -> service.respondActivityTaskFailed(
                    text(request, "taskToken"), text(request, "reason"), text(request, "details")));
            case "RespondActivityTaskCanceled" -> voidResponse(() -> service.respondActivityTaskCanceled(
                    text(request, "taskToken"), text(request, "details")));

            case "SignalWorkflowExecution" -> voidResponse(() -> service.signalWorkflowExecution(region,
                    text(request, "domain"), text(request, "workflowId"), text(request, "runId"),
                    text(request, "signalName"), text(request, "input")));
            case "RequestCancelWorkflowExecution" -> voidResponse(() -> service.requestCancelWorkflowExecution(region,
                    text(request, "domain"), text(request, "workflowId"), text(request, "runId")));
            case "TerminateWorkflowExecution" -> voidResponse(() -> service.terminateWorkflowExecution(region,
                    text(request, "domain"), text(request, "workflowId"), text(request, "runId"),
                    text(request, "reason"), text(request, "details"), text(request, "childPolicy")));

            case "ListTagsForResource" -> listTagsForResource(request);
            case "TagResource" -> voidResponse(() -> service.tagResource(
                    text(request, "resourceArn"), parseTags(request.path("tags"))));
            case "UntagResource" -> voidResponse(() -> service.untagResource(
                    text(request, "resourceArn"), parseStringList(request.path("tagKeys"))));

            default -> null;
        };
    }

    // ──────────────────────────────── Domains ────────────────────────────────

    private Response registerDomain(JsonNode request, String region) {
        service.registerDomain(
                text(request, "name"),
                text(request, "description"),
                text(request, "workflowExecutionRetentionPeriodInDays"),
                parseTags(request.path("tags")),
                region);
        return empty();
    }

    private Response describeDomain(JsonNode request, String region) {
        SwfDomain domain = service.describeDomain(region, text(request, "name"));
        ObjectNode response = objectMapper.createObjectNode();
        response.set("domainInfo", domainInfo(domain, region));
        response.putObject("configuration")
                .put("workflowExecutionRetentionPeriodInDays", domain.getWorkflowExecutionRetentionPeriodInDays());
        return Response.ok(response).build();
    }

    private Response listDomains(JsonNode request, String region) {
        List<SwfDomain> domains = service.listDomains(region, text(request, "registrationStatus"));
        if (request.path("reverseOrder").asBoolean(false)) {
            domains = new ArrayList<>(domains);
            java.util.Collections.reverse(domains);
        }
        return pagedList(request, "domainInfos", domains, domain -> domainInfo(domain, region));
    }

    private ObjectNode domainInfo(SwfDomain domain, String region) {
        ObjectNode info = objectMapper.createObjectNode();
        info.put("name", domain.getName());
        info.put("status", domain.getStatus());
        putIfPresent(info, "description", domain.getDescription());
        info.put("arn", service.domainArnFor(domain, region));
        return info;
    }

    // ───────────────────────────── Workflow types ────────────────────────────

    private Response registerWorkflowType(JsonNode request, String region) {
        SwfWorkflowType type = new SwfWorkflowType();
        type.setName(text(request, "name"));
        type.setVersion(text(request, "version"));
        type.setDescription(text(request, "description"));
        type.setDefaultTaskStartToCloseTimeout(text(request, "defaultTaskStartToCloseTimeout"));
        type.setDefaultExecutionStartToCloseTimeout(text(request, "defaultExecutionStartToCloseTimeout"));
        type.setDefaultTaskList(nested(request, "defaultTaskList", "name"));
        type.setDefaultTaskPriority(text(request, "defaultTaskPriority"));
        type.setDefaultChildPolicy(text(request, "defaultChildPolicy"));
        type.setDefaultLambdaRole(text(request, "defaultLambdaRole"));
        service.registerWorkflowType(region, text(request, "domain"), type);
        return empty();
    }

    private Response describeWorkflowType(JsonNode request, String region) {
        SwfWorkflowType type = service.describeWorkflowType(region, text(request, "domain"),
                nested(request, "workflowType", "name"), nested(request, "workflowType", "version"));
        ObjectNode response = objectMapper.createObjectNode();
        response.set("typeInfo", workflowTypeInfo(type));

        ObjectNode configuration = response.putObject("configuration");
        putIfPresent(configuration, "defaultTaskStartToCloseTimeout", type.getDefaultTaskStartToCloseTimeout());
        putIfPresent(configuration, "defaultExecutionStartToCloseTimeout",
                type.getDefaultExecutionStartToCloseTimeout());
        putTaskList(configuration, "defaultTaskList", type.getDefaultTaskList());
        putIfPresent(configuration, "defaultTaskPriority", type.getDefaultTaskPriority());
        putIfPresent(configuration, "defaultChildPolicy", type.getDefaultChildPolicy());
        putIfPresent(configuration, "defaultLambdaRole", type.getDefaultLambdaRole());
        return Response.ok(response).build();
    }

    private Response listWorkflowTypes(JsonNode request, String region) {
        List<SwfWorkflowType> types = service.listWorkflowTypes(region, text(request, "domain"), text(request, "name"),
                text(request, "registrationStatus"), request.path("reverseOrder").asBoolean(false));
        return pagedList(request, "typeInfos", types, this::workflowTypeInfo);
    }

    private ObjectNode workflowTypeInfo(SwfWorkflowType type) {
        ObjectNode info = objectMapper.createObjectNode();
        info.set("workflowType", typeNode(type.getName(), type.getVersion()));
        info.put("status", type.getStatus());
        putIfPresent(info, "description", type.getDescription());
        info.put("creationDate", type.getCreationDate());
        if (type.getDeprecationDate() != null) {
            info.put("deprecationDate", type.getDeprecationDate());
        }
        return info;
    }

    // ───────────────────────────── Activity types ────────────────────────────

    private Response registerActivityType(JsonNode request, String region) {
        SwfActivityType type = new SwfActivityType();
        type.setName(text(request, "name"));
        type.setVersion(text(request, "version"));
        type.setDescription(text(request, "description"));
        type.setDefaultTaskStartToCloseTimeout(text(request, "defaultTaskStartToCloseTimeout"));
        type.setDefaultTaskHeartbeatTimeout(text(request, "defaultTaskHeartbeatTimeout"));
        type.setDefaultTaskList(nested(request, "defaultTaskList", "name"));
        type.setDefaultTaskPriority(text(request, "defaultTaskPriority"));
        type.setDefaultTaskScheduleToStartTimeout(text(request, "defaultTaskScheduleToStartTimeout"));
        type.setDefaultTaskScheduleToCloseTimeout(text(request, "defaultTaskScheduleToCloseTimeout"));
        service.registerActivityType(region, text(request, "domain"), type);
        return empty();
    }

    private Response describeActivityType(JsonNode request, String region) {
        SwfActivityType type = service.describeActivityType(region, text(request, "domain"),
                nested(request, "activityType", "name"), nested(request, "activityType", "version"));
        ObjectNode response = objectMapper.createObjectNode();
        response.set("typeInfo", activityTypeInfo(type));

        ObjectNode configuration = response.putObject("configuration");
        putIfPresent(configuration, "defaultTaskStartToCloseTimeout", type.getDefaultTaskStartToCloseTimeout());
        putIfPresent(configuration, "defaultTaskHeartbeatTimeout", type.getDefaultTaskHeartbeatTimeout());
        putTaskList(configuration, "defaultTaskList", type.getDefaultTaskList());
        putIfPresent(configuration, "defaultTaskPriority", type.getDefaultTaskPriority());
        putIfPresent(configuration, "defaultTaskScheduleToStartTimeout",
                type.getDefaultTaskScheduleToStartTimeout());
        putIfPresent(configuration, "defaultTaskScheduleToCloseTimeout",
                type.getDefaultTaskScheduleToCloseTimeout());
        return Response.ok(response).build();
    }

    private Response listActivityTypes(JsonNode request, String region) {
        List<SwfActivityType> types = service.listActivityTypes(region, text(request, "domain"), text(request, "name"),
                text(request, "registrationStatus"), request.path("reverseOrder").asBoolean(false));
        return pagedList(request, "typeInfos", types, this::activityTypeInfo);
    }

    private ObjectNode activityTypeInfo(SwfActivityType type) {
        ObjectNode info = objectMapper.createObjectNode();
        info.set("activityType", typeNode(type.getName(), type.getVersion()));
        info.put("status", type.getStatus());
        putIfPresent(info, "description", type.getDescription());
        info.put("creationDate", type.getCreationDate());
        if (type.getDeprecationDate() != null) {
            info.put("deprecationDate", type.getDeprecationDate());
        }
        return info;
    }

    // ─────────────────────────────── Executions ──────────────────────────────

    private Response startWorkflowExecution(JsonNode request, String region) {
        String runId = service.startWorkflowExecution(new StartWorkflowExecutionRequest(
                region,
                text(request, "domain"),
                text(request, "workflowId"),
                nested(request, "workflowType", "name"),
                nested(request, "workflowType", "version"),
                nested(request, "taskList", "name"),
                text(request, "taskPriority"),
                text(request, "input"),
                text(request, "executionStartToCloseTimeout"),
                text(request, "taskStartToCloseTimeout"),
                text(request, "childPolicy"),
                parseStringList(request.path("tagList")),
                text(request, "lambdaRole")));
        ObjectNode response = objectMapper.createObjectNode();
        response.put("runId", runId);
        return Response.ok(response).build();
    }

    private Response describeWorkflowExecution(JsonNode request, String region) {
        SwfWorkflowExecution execution = service.describeWorkflowExecution(region, text(request, "domain"),
                nested(request, "execution", "workflowId"), nested(request, "execution", "runId"));

        ObjectNode response = objectMapper.createObjectNode();
        response.set("executionInfo", executionInfo(execution));

        ObjectNode configuration = response.putObject("executionConfiguration");
        configuration.put("taskStartToCloseTimeout", execution.getTaskStartToCloseTimeout());
        configuration.put("executionStartToCloseTimeout", execution.getExecutionStartToCloseTimeout());
        putTaskList(configuration, "taskList", execution.getTaskList());
        putIfPresent(configuration, "taskPriority", execution.getTaskPriority());
        configuration.put("childPolicy", execution.getChildPolicy());
        putIfPresent(configuration, "lambdaRole", execution.getLambdaRole());

        ObjectNode openCounts = response.putObject("openCounts");
        openCounts.put("openActivityTasks", service.openActivityCount(execution));
        openCounts.put("openDecisionTasks", service.openDecisionTaskCount(execution));
        openCounts.put("openTimers", service.openTimerCount(execution));
        openCounts.put("openChildWorkflowExecutions", service.openChildCount(execution));
        openCounts.put("openLambdaFunctions", 0);

        if (execution.getLatestActivityTaskTimestamp() != null) {
            response.put("latestActivityTaskTimestamp", execution.getLatestActivityTaskTimestamp());
        }
        putIfPresent(response, "latestExecutionContext", execution.getLatestExecutionContext());
        return Response.ok(response).build();
    }

    /**
     * Applies {@code maximumPageSize} and {@code nextPageToken} to an already-ordered list,
     * appending each item on the page via {@code render} and setting {@code nextPageToken}
     * only when more items remain.
     *
     * <p>Measured against the live service: the token is absent on the final page even when
     * that page is exactly full; a {@code maximumPageSize} of 0 (or absent) means "no caller
     * limit"; above 1000 is a ValidationException; and an unparseable token is rejected rather
     * than silently restarting from the beginning.
     */
    private <T> Response pagedList(JsonNode request, String itemsField, List<T> items,
                                   Function<T, ObjectNode> render) {
        int pageSize = service.pageSize(optionalInt(request, "maximumPageSize"));
        int from = Math.min(decodePageToken(text(request, "nextPageToken")).offset(), items.size());
        int to = Math.min(from + pageSize, items.size());

        ObjectNode response = objectMapper.createObjectNode();
        ArrayNode array = response.putArray(itemsField);
        for (T item : items.subList(from, to)) {
            array.add(render.apply(item));
        }
        if (to < items.size()) {
            response.put("nextPageToken", encodePageToken(to, null));
        }
        return Response.ok(response).build();
    }

    private Response getWorkflowExecutionHistory(JsonNode request, String region) {
        boolean reverseOrder = request.path("reverseOrder").asBoolean(false);
        List<SwfHistoryEvent> events = service.getWorkflowExecutionHistory(region, text(request, "domain"),
                nested(request, "execution", "workflowId"), nested(request, "execution", "runId"), reverseOrder);

        return pagedList(request, "events", events, this::historyEvent);
    }

    private Response listExecutions(JsonNode request, String region, boolean closed) {
        List<SwfWorkflowExecution> executions = service.listExecutions(region, text(request, "domain"),
                buildFilter(request, closed), closed);
        if (request.path("reverseOrder").asBoolean(false)) {
            executions = new ArrayList<>(executions);
            java.util.Collections.reverse(executions);
        }

        return pagedList(request, "executionInfos", executions, this::executionInfo);
    }

    private Response countExecutions(JsonNode request, String region, boolean closed) {
        List<SwfWorkflowExecution> executions = service.listExecutions(region, text(request, "domain"),
                buildFilter(request, closed), closed);
        return count(executions.size());
    }

    private Response count(int value) {
        ObjectNode response = objectMapper.createObjectNode();
        response.put("count", value);
        response.put("truncated", false);
        return Response.ok(response).build();
    }

    private ObjectNode executionInfo(SwfWorkflowExecution execution) {
        ObjectNode info = objectMapper.createObjectNode();
        info.set("execution", executionNode(execution.getWorkflowId(), execution.getRunId()));
        info.set("workflowType", typeNode(execution.getWorkflowTypeName(), execution.getWorkflowTypeVersion()));
        info.put("startTimestamp", execution.getStartTimestamp());
        if (execution.getCloseTimestamp() != null) {
            info.put("closeTimestamp", execution.getCloseTimestamp());
        }
        info.put("executionStatus", execution.getExecutionStatus());
        putIfPresent(info, "closeStatus", execution.getCloseStatus());
        if (execution.getParentWorkflowId() != null) {
            info.set("parent", executionNode(execution.getParentWorkflowId(), execution.getParentRunId()));
        }
        if (!execution.getTagList().isEmpty()) {
            ArrayNode tags = info.putArray("tagList");
            execution.getTagList().forEach(tags::add);
        }
        info.put("cancelRequested", execution.isCancelRequested());
        return info;
    }

    /**
     * Builds the filter from whichever of the mutually exclusive filter members is present.
     * SWF applies at most one of executionFilter/typeFilter/tagFilter, plus the time filter
     * that matches the list flavour (startTimeFilter for open, either for closed).
     */
    private ExecutionFilter buildFilter(JsonNode request, boolean closed) {
        ExecutionFilter filter = ExecutionFilter.all();

        JsonNode executionFilter = request.path("executionFilter");
        if (executionFilter.hasNonNull("workflowId")) {
            String workflowId = executionFilter.path("workflowId").asText();
            filter = filter.and(execution -> workflowId.equals(execution.getWorkflowId()));
        }

        JsonNode typeFilter = request.path("typeFilter");
        if (typeFilter.hasNonNull("name")) {
            String name = typeFilter.path("name").asText();
            String version = typeFilter.path("version").asText(null);
            filter = filter.and(execution -> name.equals(execution.getWorkflowTypeName())
                    && (version == null || version.isEmpty() || version.equals(execution.getWorkflowTypeVersion())));
        }

        JsonNode tagFilter = request.path("tagFilter");
        if (tagFilter.hasNonNull("tag")) {
            String tag = tagFilter.path("tag").asText();
            filter = filter.and(execution -> execution.getTagList().contains(tag));
        }

        if (closed) {
            JsonNode closeStatusFilter = request.path("closeStatusFilter");
            if (closeStatusFilter.hasNonNull("status")) {
                String status = closeStatusFilter.path("status").asText();
                filter = filter.and(execution -> status.equals(execution.getCloseStatus()));
            }
            filter = filter.and(timeFilter(request.path("closeTimeFilter"),
                    execution -> execution.getCloseTimestamp() != null ? execution.getCloseTimestamp() : 0.0));
        }
        return filter.and(timeFilter(request.path("startTimeFilter"),
                SwfWorkflowExecution::getStartTimestamp));
    }

    private ExecutionFilter timeFilter(JsonNode filterNode,
                                       java.util.function.ToDoubleFunction<SwfWorkflowExecution> extractor) {
        if (filterNode == null || filterNode.isMissingNode() || filterNode.isNull()) {
            return ExecutionFilter.all();
        }
        Double oldest = filterNode.hasNonNull("oldestDate") ? filterNode.path("oldestDate").asDouble() : null;
        Double latest = filterNode.hasNonNull("latestDate") ? filterNode.path("latestDate").asDouble() : null;
        if (oldest == null && latest == null) {
            return ExecutionFilter.all();
        }
        return execution -> {
            double value = extractor.applyAsDouble(execution);
            return (oldest == null || value >= oldest) && (latest == null || value <= latest);
        };
    }

    // ─────────────────────────────── Task polling ────────────────────────────

    /**
     * {@code PollForDecisionTask}.
     *
     * <p>A request carrying {@code nextPageToken} continues an earlier poll rather than starting
     * a new one — the live service documents that "calling PollForDecisionTask with a
     * nextPageToken doesn't return a new decision task", and answers with the same task's next
     * history page, same {@code startedEventId} and {@code workflowExecution}. The token
     * therefore names the task it belongs to, and a continuation resolves that task instead of
     * claiming another.
     */
    private Response pollForDecisionTask(JsonNode request, String region) {
        String pageToken = text(request, "nextPageToken");
        SwfDecisionTask task;
        int offset;
        if (pageToken != null && !pageToken.isEmpty()) {
            Page page = decodePageToken(pageToken);
            if (page.taskToken() == null) {
                throw SwfFaults.invalidPageToken();
            }
            task = service.decisionTaskForToken(page.taskToken());
            offset = page.offset();
        } else {
            Optional<SwfDecisionTask> maybeTask = service.pollForDecisionTask(region,
                    text(request, "domain"), nested(request, "taskList", "name"),
                    text(request, "identity"));
            if (maybeTask.isEmpty()) {
                return Response.ok(emptyPollResponse()).build();
            }
            task = maybeTask.get();
            offset = 0;
        }

        boolean reverseOrder = request.path("reverseOrder").asBoolean(false);
        List<SwfHistoryEvent> events = service.getWorkflowExecutionHistory(region, task.getDomain(),
                task.getWorkflowId(), task.getRunId(), reverseOrder);

        // startAtPreviousStartedEvent trims the history to what the decider has not seen,
        // starting *at* previousStartedEventId — inclusive, measured against the live service.
        long from = task.getPreviousStartedEventId();
        if (request.path("startAtPreviousStartedEvent").asBoolean(false) && from > 0) {
            events = events.stream().filter(e -> e.getEventId() >= from).toList();
        }

        int pageSize = service.pageSize(optionalInt(request, "maximumPageSize"));
        int end = Math.min(offset + pageSize, events.size());

        ObjectNode response = objectMapper.createObjectNode();
        response.put("taskToken", task.getTaskToken());
        response.put("startedEventId", task.getStartedEventId());
        response.set("workflowExecution", executionNode(task.getWorkflowId(), task.getRunId()));
        ArrayNode array = response.putArray("events");
        for (SwfHistoryEvent event : events.subList(Math.min(offset, events.size()), end)) {
            array.add(historyEvent(event));
        }
        response.put("previousStartedEventId", task.getPreviousStartedEventId());
        if (end < events.size()) {
            response.put("nextPageToken", encodePageToken(end, task.getTaskToken()));
        }

        SwfWorkflowExecution execution = service.describeWorkflowExecution(region, task.getDomain(),
                task.getWorkflowId(), task.getRunId());
        response.set("workflowType", typeNode(execution.getWorkflowTypeName(),
                execution.getWorkflowTypeVersion()));
        return Response.ok(response).build();
    }

    /**
     * An empty poll answers 200 with {@code taskToken: ""}. The SDKs treat the empty
     * token as "no task"; omitting the member makes them raise on a missing required field.
     */
    private ObjectNode emptyPollResponse() {
        ObjectNode response = objectMapper.createObjectNode();
        response.put("taskToken", "");
        response.put("startedEventId", 0);
        response.put("previousStartedEventId", 0);
        return response;
    }

    private Response pollForActivityTask(JsonNode request, String region) {
        Optional<SwfActivityTask> maybeTask = service.pollForActivityTask(region, text(request, "domain"),
                nested(request, "taskList", "name"), text(request, "identity"));
        if (maybeTask.isEmpty()) {
            ObjectNode response = objectMapper.createObjectNode();
            response.put("taskToken", "");
            response.put("activityId", "");
            response.put("startedEventId", 0);
            return Response.ok(response).build();
        }

        SwfActivityTask task = maybeTask.get();
        ObjectNode response = objectMapper.createObjectNode();
        response.put("taskToken", task.getTaskToken());
        response.put("activityId", task.getActivityId());
        response.put("startedEventId", task.getStartedEventId());
        response.set("workflowExecution", executionNode(task.getWorkflowId(), task.getRunId()));
        response.set("activityType", typeNode(task.getActivityTypeName(), task.getActivityTypeVersion()));
        putIfPresent(response, "input", task.getInput());
        return Response.ok(response).build();
    }

    private Response recordActivityTaskHeartbeat(JsonNode request) {
        boolean cancelRequested = service.recordActivityTaskHeartbeat(text(request, "taskToken"),
                text(request, "details"));
        ObjectNode response = objectMapper.createObjectNode();
        response.put("cancelRequested", cancelRequested);
        return Response.ok(response).build();
    }

    private Response respondDecisionTaskCompleted(JsonNode request) {
        service.respondDecisionTaskCompleted(text(request, "taskToken"),
                parseDecisions(request.path("decisions")), text(request, "executionContext"));
        return empty();
    }

    /**
     * Unwraps each decision from its {@code <type>DecisionAttributes} envelope. The
     * attribute member name is the decision type with a lower-cased first letter, which is
     * how the model names every one of the thirteen decision types.
     */
    private List<Decision> parseDecisions(JsonNode decisionsNode) {
        List<Decision> decisions = new ArrayList<>();
        if (!decisionsNode.isArray()) {
            return decisions;
        }
        for (JsonNode node : decisionsNode) {
            String type = node.path("decisionType").asText(null);
            if (type == null || type.isEmpty()) {
                throw SwfFaults.missingRequired("decisions.1.member.decisionType");
            }
            String attributesField = Character.toLowerCase(type.charAt(0)) + type.substring(1)
                    + "DecisionAttributes";
            decisions.add(new Decision(type, toMap(node.path(attributesField))));
        }
        return decisions;
    }

    private Response listTagsForResource(JsonNode request) {
        Map<String, String> tags = service.listTagsForResource(text(request, "resourceArn"));
        ObjectNode response = objectMapper.createObjectNode();
        ArrayNode array = response.putArray("tags");
        tags.forEach((key, value) -> {
            ObjectNode entry = array.addObject();
            entry.put("key", key);
            if (value != null) {
                entry.put("value", value);
            }
        });
        return Response.ok(response).build();
    }

    // ──────────────────────────── Wire serialization ─────────────────────────

    private ObjectNode historyEvent(SwfHistoryEvent event) {
        ObjectNode node = objectMapper.createObjectNode();
        node.put("eventTimestamp", event.getEventTimestamp());
        node.put("eventType", event.getEventType());
        node.put("eventId", event.getEventId());
        if (!event.getAttributes().isEmpty()) {
            node.set(event.attributesFieldName(), objectMapper.valueToTree(event.getAttributes()));
        }
        return node;
    }

    private ObjectNode typeNode(String name, String version) {
        ObjectNode node = objectMapper.createObjectNode();
        node.put("name", name);
        node.put("version", version);
        return node;
    }

    private ObjectNode executionNode(String workflowId, String runId) {
        ObjectNode node = objectMapper.createObjectNode();
        node.put("workflowId", workflowId);
        node.put("runId", runId);
        return node;
    }

    private void putTaskList(ObjectNode parent, String field, String taskList) {
        if (taskList != null && !taskList.isEmpty()) {
            parent.putObject(field).put("name", taskList);
        }
    }

    private static void putIfPresent(ObjectNode node, String field, String value) {
        if (value != null && !value.isEmpty()) {
            node.put(field, value);
        }
    }

    /**
     * Operations SWF models without an output shape answer 200 with no body. Returning
     * {@code {}} instead makes strict SDK response handlers log a shape mismatch.
     */
    private Response empty() {
        return Response.ok().build();
    }

    private Response voidResponse(Runnable action) {
        action.run();
        return empty();
    }

    private static String text(JsonNode request, String field) {
        JsonNode node = request.get(field);
        return node != null && !node.isNull() ? node.asText() : null;
    }

    private static String nested(JsonNode request, String field, String key) {
        JsonNode parent = request.get(field);
        if (parent == null || parent.isNull()) {
            return null;
        }
        JsonNode node = parent.get(key);
        return node != null && !node.isNull() ? node.asText() : null;
    }

    /**
     * Reads an optional integer, rejecting wrong types rather than letting
     * {@code asInt()} coerce a string or fraction to 0 and silently mean "use the default".
     */
    private static Integer optionalInt(JsonNode request, String field) {
        JsonNode node = request.get(field);
        if (node == null || node.isNull()) {
            return null;
        }
        if (!node.isIntegralNumber()) {
            throw SwfFaults.validationConstraint(node.toString(), field, "Member must be an integer.");
        }
        return node.intValue();
    }

    private static Map<String, String> parseTags(JsonNode tagsNode) {
        Map<String, String> tags = new LinkedHashMap<>();
        if (tagsNode != null && tagsNode.isArray()) {
            for (JsonNode entry : tagsNode) {
                String key = entry.path("key").asText(null);
                if (key != null) {
                    tags.put(key, entry.path("value").asText(""));
                }
            }
        }
        return tags;
    }

    private static List<String> parseStringList(JsonNode arrayNode) {
        if (arrayNode == null || !arrayNode.isArray()) {
            return null;
        }
        List<String> values = new ArrayList<>();
        for (JsonNode node : arrayNode) {
            values.add(node.asText());
        }
        return values;
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> toMap(JsonNode node) {
        if (node == null || !node.isObject()) {
            return Map.of();
        }
        return objectMapper.convertValue(node, Map.class);
    }

    /**
     * A decoded continuation token: how far the caller has read, and — for
     * {@code PollForDecisionTask} — which decision task the page belongs to.
     *
     * <p>SWF tokens are opaque blobs, so the encoding is ours to choose; a corrupt one is
     * rejected with {@code ValidationException: Invalid token} rather than restarting at the
     * first page, which would turn a caller's paging loop into an infinite one.
     */
    private record Page(int offset, String taskToken) {

        static final Page FIRST = new Page(0, null);
    }

    private static String encodePageToken(int offset, String taskToken) {
        String body = "offset=" + offset + (taskToken == null ? "" : "\ntask=" + taskToken);
        return java.util.Base64.getEncoder()
                .encodeToString(body.getBytes(java.nio.charset.StandardCharsets.UTF_8));
    }

    private static Page decodePageToken(String token) {
        if (token == null || token.isEmpty()) {
            return Page.FIRST;
        }
        String decoded;
        try {
            decoded = new String(java.util.Base64.getDecoder().decode(token),
                    java.nio.charset.StandardCharsets.UTF_8);
        } catch (IllegalArgumentException e) {
            throw SwfFaults.invalidPageToken();
        }
        if (!decoded.startsWith("offset=")) {
            throw SwfFaults.invalidPageToken();
        }
        int split = decoded.indexOf("\ntask=");
        String offsetPart = split < 0 ? decoded.substring("offset=".length())
                : decoded.substring("offset=".length(), split);
        String taskToken = split < 0 ? null : decoded.substring(split + "\ntask=".length());
        try {
            int offset = Integer.parseInt(offsetPart);
            if (offset < 0 || (taskToken != null && taskToken.isEmpty())) {
                throw SwfFaults.invalidPageToken();
            }
            return new Page(offset, taskToken);
        } catch (NumberFormatException e) {
            throw SwfFaults.invalidPageToken();
        }
    }
}
