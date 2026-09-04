package io.github.hectorvent.floci.services.budgets;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.core.common.PaginatedResult;
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

    public Response handle(String action, JsonNode request) {
        return switch (action) {
            case "DescribeBudget" -> describeBudget(request);
            case "CreateBudget" -> emptyAfter(() -> budgetsService.createBudget(request));
            case "UpdateBudget" -> emptyAfter(() -> budgetsService.updateBudget(request));
            case "DeleteBudget" -> emptyAfter(() -> budgetsService.deleteBudget(request));
            case "ListTagsForResource" -> listTagsForResource(request);
            case "DescribeNotificationsForBudget" -> describeNotifications(request);
            case "DescribeSubscribersForNotification" -> describeSubscribers(request);
            case "CreateNotification" -> emptyAfter(() -> budgetsService.createNotification(request));
            case "DeleteNotification" -> emptyAfter(() -> budgetsService.deleteNotification(request));
            default -> throw new AwsException("UnknownOperationException",
                    "Operation " + action + " is not supported.", 400);
        };
    }

    private Response describeBudget(JsonNode request) {
        BudgetRecord record = budgetsService.describeBudget(request);
        ObjectNode response = objectMapper.createObjectNode();
        response.set("Budget", record.getBudget());
        return Response.ok(response).build();
    }

    private Response listTagsForResource(JsonNode request) {
        ObjectNode response = objectMapper.createObjectNode();
        JsonNode tags = budgetsService.listTagsForResource(request);
        response.set("ResourceTags", tags == null ? objectMapper.createArrayNode() : tags);
        return Response.ok(response).build();
    }

    private Response describeNotifications(JsonNode request) {
        PaginatedResult<JsonNode> page = budgetsService.describeNotifications(request);
        ObjectNode response = objectMapper.createObjectNode();
        ArrayNode items = response.putArray("Notifications");
        page.items().forEach(items::add);
        if (page.nextToken() != null) response.put("NextToken", page.nextToken());
        return Response.ok(response).build();
    }

    private Response describeSubscribers(JsonNode request) {
        PaginatedResult<JsonNode> page = budgetsService.describeSubscribers(request);
        ObjectNode response = objectMapper.createObjectNode();
        ArrayNode items = response.putArray("Subscribers");
        page.items().forEach(items::add);
        if (page.nextToken() != null) response.put("NextToken", page.nextToken());
        return Response.ok(response).build();
    }

    private Response emptyAfter(Runnable action) {
        action.run();
        return Response.ok(objectMapper.createObjectNode()).build();
    }
}
