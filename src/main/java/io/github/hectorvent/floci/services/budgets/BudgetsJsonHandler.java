package io.github.hectorvent.floci.services.budgets;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.core.common.PaginatedResult;
import io.github.hectorvent.floci.services.budgets.model.BudgetActionRecord;
import io.github.hectorvent.floci.services.budgets.model.BudgetRecord;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.core.Response;

@ApplicationScoped
public class BudgetsJsonHandler {
    private final BudgetsService budgetsService;
    private final ObjectMapper objectMapper;

    @Inject
    public BudgetsJsonHandler(BudgetsService budgetsService, ObjectMapper objectMapper) {
        this.budgetsService = budgetsService;
        this.objectMapper = objectMapper;
    }

    public Response handle(String action, JsonNode request, String callerAccountId) {
        budgetsService.validateCallerScope(request, callerAccountId);
        return switch (action) {
            case "CreateBudget" -> emptyAfter(() -> budgetsService.createBudget(request));
            case "CreateBudgetAction" -> createBudgetAction(request);
            case "CreateNotification" -> emptyAfter(() -> budgetsService.createNotification(request));
            case "CreateSubscriber" -> emptyAfter(() -> budgetsService.createSubscriber(request));
            case "DeleteBudget" -> emptyAfter(() -> budgetsService.deleteBudget(request));
            case "DeleteBudgetAction" -> deleteBudgetAction(request);
            case "DeleteNotification" -> emptyAfter(() -> budgetsService.deleteNotification(request));
            case "DeleteSubscriber" -> emptyAfter(() -> budgetsService.deleteSubscriber(request));
            case "DescribeBudget" -> describeBudget(request);
            case "DescribeBudgetAction" -> describeBudgetAction(request);
            case "DescribeBudgetActionHistories" -> page("ActionHistories", budgetsService.describeBudgetActionHistories(request));
            case "DescribeBudgetActionsForAccount" -> page("Actions", budgetsService.describeBudgetActionsForAccount(request));
            case "DescribeBudgetActionsForBudget" -> page("Actions", budgetsService.describeBudgetActionsForBudget(request));
            case "DescribeBudgetNotificationsForAccount" -> page("BudgetNotificationsForAccount", budgetsService.describeBudgetNotificationsForAccount(request));
            case "DescribeBudgetPerformanceHistory" -> describeBudgetPerformanceHistory(request);
            case "DescribeBudgets" -> page("Budgets", budgetsService.describeBudgets(request));
            case "DescribeNotificationsForBudget" -> page("Notifications", budgetsService.describeNotifications(request));
            case "DescribeSubscribersForNotification" -> page("Subscribers", budgetsService.describeSubscribers(request));
            case "ExecuteBudgetAction" -> executeBudgetAction(request);
            case "ListTagsForResource" -> listTagsForResource(request);
            case "TagResource" -> emptyAfter(() -> budgetsService.tagResource(request));
            case "UntagResource" -> emptyAfter(() -> budgetsService.untagResource(request));
            case "UpdateBudget" -> emptyAfter(() -> budgetsService.updateBudget(request));
            case "UpdateBudgetAction" -> Response.ok(budgetsService.updateBudgetAction(request)).build();
            case "UpdateNotification" -> emptyAfter(() -> budgetsService.updateNotification(request));
            case "UpdateSubscriber" -> emptyAfter(() -> budgetsService.updateSubscriber(request));
            default -> throw new AwsException("UnknownOperationException", "Operation " + action + " is not supported.", 400);
        };
    }

    private Response describeBudget(JsonNode request) {
        BudgetRecord record = budgetsService.describeBudget(request);
        ObjectNode response = objectMapper.createObjectNode();
        response.set("Budget", record.getBudget());
        return Response.ok(response).build();
    }

    private Response createBudgetAction(JsonNode request) {
        BudgetActionRecord record = budgetsService.createBudgetAction(request);
        ObjectNode response = objectMapper.createObjectNode();
        response.put("AccountId", record.getAccountId());
        response.put("BudgetName", record.getBudgetName());
        response.put("ActionId", record.getActionId());
        return Response.ok(response).build();
    }


    private Response deleteBudgetAction(JsonNode request) {
        BudgetActionRecord record = budgetsService.deleteBudgetAction(request);
        ObjectNode response = objectMapper.createObjectNode();
        response.put("AccountId", record.getAccountId());
        response.put("BudgetName", record.getBudgetName());
        response.set("Action", record.getAction());
        return Response.ok(response).build();
    }

    private Response describeBudgetAction(JsonNode request) {
        BudgetActionRecord record = budgetsService.describeBudgetAction(request);
        ObjectNode response = objectMapper.createObjectNode();
        response.put("AccountId", record.getAccountId());
        response.put("BudgetName", record.getBudgetName());
        response.set("Action", record.getAction());
        return Response.ok(response).build();
    }

    private Response executeBudgetAction(JsonNode request) {
        BudgetActionRecord record = budgetsService.executeBudgetAction(request);
        ObjectNode response = objectMapper.createObjectNode();
        response.put("AccountId", record.getAccountId());
        response.put("BudgetName", record.getBudgetName());
        response.put("ActionId", record.getActionId());
        response.put("ExecutionType", request.path("ExecutionType").asText());
        return Response.ok(response).build();
    }

    private Response describeBudgetPerformanceHistory(JsonNode request) {
        ObjectNode response = objectMapper.createObjectNode();
        response.set("BudgetPerformanceHistory", budgetsService.describeBudgetPerformanceHistory(request));
        return Response.ok(response).build();
    }

    private Response listTagsForResource(JsonNode request) {
        ObjectNode response = objectMapper.createObjectNode();
        response.set("ResourceTags", budgetsService.listTagsForResource(request));
        return Response.ok(response).build();
    }

    private Response page(String field, PaginatedResult<JsonNode> page) {
        ObjectNode response = objectMapper.createObjectNode();
        ArrayNode items = response.putArray(field);
        page.items().forEach(items::add);
        if (page.nextToken() != null) {
            response.put("NextToken", page.nextToken());
        }
        return Response.ok(response).build();
    }

    private Response emptyAfter(Runnable action) {
        action.run();
        return Response.ok(objectMapper.createObjectNode()).build();
    }
}
