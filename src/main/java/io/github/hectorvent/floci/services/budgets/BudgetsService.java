package io.github.hectorvent.floci.services.budgets;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.core.common.PaginatedResult;
import io.github.hectorvent.floci.core.common.Pagination;
import io.github.hectorvent.floci.core.common.Resettable;
import io.github.hectorvent.floci.core.storage.AccountAwareStorageBackend;
import io.github.hectorvent.floci.core.storage.StorageFactory;
import io.github.hectorvent.floci.services.budgets.model.BudgetActionRecord;
import io.github.hectorvent.floci.services.budgets.model.BudgetNotification;
import io.github.hectorvent.floci.services.budgets.model.BudgetRecord;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;

@ApplicationScoped
public class BudgetsService implements Resettable {
    private static final Pattern ACCOUNT_ID = Pattern.compile("\\d{12}");
    private static final Pattern ACTION_ID = Pattern.compile("^[a-fA-F0-9]{8}-[a-fA-F0-9]{4}-[a-fA-F0-9]{4}-[a-fA-F0-9]{4}-[a-fA-F0-9]{12}$");
    private static final Pattern IAM_POLICY_ARN = Pattern.compile("^arn:aws(?:-eusc|-cn|-us-gov|-iso|-iso-[a-z])?:iam::(?:\\d{12}|aws):policy/.+$");
    private static final Pattern SCP_POLICY_ID = Pattern.compile("^p-[0-9a-zA-Z_]{8,128}$");
    private static final Pattern SCP_TARGET_ID = Pattern.compile("^(?:ou-[0-9a-z]{4,32}-[a-z0-9]{8,32}|\\d{12})$");
    private static final Pattern SSM_INSTANCE_ID = Pattern.compile("^(?:i-(?:\\w{8}|\\w{17})|[a-zA-Z](?:[\\w-]{0,61}\\w)?)$");
    private static final Pattern AWS_REGION = Pattern.compile("^\\w{2,4}-\\w+(?:-\\w+)?-\\d$");
    private static final Pattern BUDGET_NAME = Pattern.compile("^(?![^:\\\\]*/action/|(?i).*<script>.*</script>.*)[^:\\\\]+$");
    private static final Set<String> BUDGET_TYPES = Set.of("USAGE", "COST", "RI_UTILIZATION", "RI_COVERAGE",
            "SAVINGS_PLANS_UTILIZATION", "SAVINGS_PLANS_COVERAGE");
    private static final Set<String> TIME_UNITS = Set.of("DAILY", "MONTHLY", "QUARTERLY", "ANNUALLY", "CUSTOM");
    private static final Set<String> COMPARISONS = Set.of("GREATER_THAN", "LESS_THAN", "EQUAL_TO");
    private static final Set<String> NOTIFICATION_TYPES = Set.of("ACTUAL", "FORECASTED");
    private static final Set<String> THRESHOLD_TYPES = Set.of("PERCENTAGE", "ABSOLUTE_VALUE");
    private static final Set<String> ACTION_TYPES = Set.of("APPLY_IAM_POLICY", "APPLY_SCP_POLICY", "RUN_SSM_DOCUMENTS");
    private static final Set<String> APPROVAL_MODELS = Set.of("AUTOMATIC", "MANUAL");
    private static final Set<String> ACTION_THRESHOLD_TYPES = Set.of("PERCENTAGE", "ABSOLUTE_VALUE");
    private static final int MAX_NOTIFICATIONS = 10;
    private static final int MAX_EMAIL_SUBSCRIBERS = 10;

    private final AccountAwareStorageBackend<BudgetRecord> budgets;
    private final AccountAwareStorageBackend<BudgetActionRecord> actions;
    private final ObjectMapper objectMapper;

    @Inject
    public BudgetsService(StorageFactory storageFactory, ObjectMapper objectMapper) {
        this(
                storageFactory.create("budgets", "budgets.json", new TypeReference<Map<String, BudgetRecord>>() {}),
                storageFactory.create("budget-actions", "budget-actions.json", new TypeReference<Map<String, BudgetActionRecord>>() {}),
                objectMapper);
    }

    BudgetsService(AccountAwareStorageBackend<BudgetRecord> budgets,
                   AccountAwareStorageBackend<BudgetActionRecord> actions,
                   ObjectMapper objectMapper) {
        this.budgets = budgets;
        this.actions = actions;
        this.objectMapper = objectMapper;
    }


    public void validateCallerScope(JsonNode request, String callerAccountId) {
        if (callerAccountId == null || !ACCOUNT_ID.matcher(callerAccountId).matches()) {
            throw new AwsException("AccessDeniedException",
                    "You are not authorized to use this operation with the given parameters.", 400);
        }
        if (request != null && request.has("AccountId")) {
            String requestedAccountId = requireAccount(request);
            if (!callerAccountId.equals(requestedAccountId)) {
                throw new AwsException("AccessDeniedException",
                        "You are not authorized to use this operation with the given parameters.", 400);
            }
        }
        if (request != null && request.has("ResourceARN")) {
            String arn = text(request, "ResourceARN");
            if (arn == null) {
                throw invalid("ResourceARN is required.");
            }
            ResourceRef ref = parseResourceArn(arn);
            if (!callerAccountId.equals(ref.accountId())) {
                throw new AwsException("AccessDeniedException",
                        "You are not authorized to use this operation with the given parameters.", 400);
            }
        }
    }

    public BudgetRecord describeBudget(JsonNode request) {
        return requireBudget(requireAccount(request), requireBudgetName(request));
    }

    public PaginatedResult<JsonNode> describeBudgets(JsonNode request) {
        String accountId = requireAccount(request);
        List<JsonNode> items = budgets.scanForAccount(accountId, key -> true).stream()
                .map(BudgetRecord::getBudget)
                .filter(node -> node != null)
                .sorted(Comparator.comparing(node -> node.path("BudgetName").asText()))
                .toList();
        return Pagination.paginate(items, node -> node.path("BudgetName").asText(), readMaxResults(request, 1000, 1000),
                text(request, "NextToken"), 1000, "InvalidNextTokenException");
    }

    public synchronized void createBudget(JsonNode request) {
        String accountId = requireAccount(request);
        JsonNode budget = request.get("Budget");
        if (budget == null || !budget.isObject()) {
            throw invalid("Budget is required.");
        }
        String name = requireBudgetName(budget, "BudgetName");
        validateBudget(budget);
        String key = key(accountId, name);
        if (budgets.getForAccount(accountId, key).isPresent()) {
            throw new AwsException("DuplicateRecordException", "A budget with this name already exists.", 400);
        }
        JsonNode resourceTags = request.get("ResourceTags");
        validateTags(resourceTags);
        List<BudgetNotification> notifications = parseNotifications(request.get("NotificationsWithSubscribers"));
        BudgetRecord record = new BudgetRecord();
        record.setAccountId(accountId);
        record.setBudget(withBudgetResponseDefaults(budget));
        record.setResourceTags(resourceTags == null ? null : resourceTags.deepCopy());
        record.setNotifications(notifications);
        budgets.putForAccount(accountId, key, record);
    }

    public synchronized void updateBudget(JsonNode request) {
        String accountId = requireAccount(request);
        JsonNode newBudget = request.get("NewBudget");
        if (newBudget == null || !newBudget.isObject()) {
            throw invalid("NewBudget is required.");
        }
        String name = requireBudgetName(newBudget, "BudgetName");
        validateBudget(newBudget);
        BudgetRecord record = requireBudget(accountId, name);
        record.setBudget(withBudgetResponseDefaults(newBudget));
        budgets.putForAccount(accountId, key(accountId, name), record);
    }

    public synchronized void deleteBudget(JsonNode request) {
        String accountId = requireAccount(request);
        String name = requireBudgetName(request);
        requireBudget(accountId, name);
        budgets.deleteForAccount(accountId, key(accountId, name));
        actions.keysForAccount(accountId).stream()
                .filter(actionKey -> actionKey.startsWith(name + "::"))
                .toList()
                .forEach(actionKey -> actions.deleteForAccount(accountId, actionKey));
    }

    public PaginatedResult<JsonNode> describeNotifications(JsonNode request) {
        BudgetRecord record = requireBudget(requireAccount(request), requireBudgetName(request));
        List<JsonNode> notifications = record.getNotifications().stream().map(BudgetNotification::getNotification).toList();
        return Pagination.paginate(notifications, this::notificationCursor, readMaxResults(request, 100, 100),
                text(request, "NextToken"), 100, "InvalidNextTokenException");
    }

    public PaginatedResult<JsonNode> describeSubscribers(JsonNode request) {
        BudgetRecord record = requireBudget(requireAccount(request), requireBudgetName(request));
        JsonNode notification = requireNotification(request.get("Notification"));
        BudgetNotification found = findNotification(record, notification);
        if (found == null) {
            throw notFound("The specified notification was not found.");
        }
        return Pagination.paginate(found.getSubscribers(), this::subscriberCursor, readMaxResults(request, 100, 100),
                text(request, "NextToken"), 100, "InvalidNextTokenException");
    }

    public PaginatedResult<JsonNode> describeBudgetNotificationsForAccount(JsonNode request) {
        String accountId = requireAccount(request);
        List<JsonNode> items = new ArrayList<>();
        for (BudgetRecord record : budgets.scanForAccount(accountId, key -> true)) {
            String budgetName = record.getBudget().path("BudgetName").asText();
            for (BudgetNotification notification : record.getNotifications()) {
                ObjectNode item = objectMapper.createObjectNode();
                item.put("BudgetName", budgetName);
                item.set("Notification", notification.getNotification());
                items.add(item);
            }
        }
        items.sort(Comparator.comparing(node -> node.path("BudgetName").asText() + "::" + notificationCursor(node.path("Notification"))));
        return Pagination.paginate(items,
                node -> node.path("BudgetName").asText() + "::" + notificationCursor(node.path("Notification")),
                readMaxResults(request, 50, 1000), text(request, "NextToken"), 1000, "InvalidNextTokenException");
    }

    public synchronized void createNotification(JsonNode request) {
        String accountId = requireAccount(request);
        String name = requireBudgetName(request);
        BudgetRecord record = requireBudget(accountId, name);
        JsonNode notification = requireNotification(request.get("Notification"));
        if (findNotification(record, notification) != null) {
            throw new AwsException("DuplicateRecordException", "The notification already exists.", 400);
        }
        if (record.getNotifications().size() >= MAX_NOTIFICATIONS) {
            throw new AwsException("CreationLimitExceededException", "A budget can have at most 10 notifications.", 400);
        }
        List<JsonNode> subscribers = parseSubscribers(request.get("Subscribers"));
        record.getNotifications().add(new BudgetNotification(notification.deepCopy(), subscribers));
        budgets.putForAccount(accountId, key(accountId, name), record);
    }

    public synchronized void updateNotification(JsonNode request) {
        String accountId = requireAccount(request);
        String name = requireBudgetName(request);
        BudgetRecord record = requireBudget(accountId, name);
        JsonNode oldNotification = requireNotification(request.get("OldNotification"));
        JsonNode newNotification = requireNotification(request.get("NewNotification"));
        BudgetNotification found = findNotification(record, oldNotification);
        if (found == null) {
            throw notFound("The specified notification was not found.");
        }
        BudgetNotification duplicate = findNotification(record, newNotification);
        if (duplicate != null && duplicate != found) {
            throw new AwsException("DuplicateRecordException", "The notification already exists.", 400);
        }
        found.setNotification(newNotification.deepCopy());
        budgets.putForAccount(accountId, key(accountId, name), record);
    }

    public synchronized void deleteNotification(JsonNode request) {
        String accountId = requireAccount(request);
        String name = requireBudgetName(request);
        BudgetRecord record = requireBudget(accountId, name);
        JsonNode notification = requireNotification(request.get("Notification"));
        BudgetNotification found = findNotification(record, notification);
        if (found == null) {
            throw notFound("The specified notification was not found.");
        }
        record.getNotifications().remove(found);
        budgets.putForAccount(accountId, key(accountId, name), record);
    }

    public synchronized void createSubscriber(JsonNode request) {
        mutateSubscriber(request, null, request.get("Subscriber"), SubscriberMutation.CREATE);
    }

    public synchronized void updateSubscriber(JsonNode request) {
        mutateSubscriber(request, request.get("OldSubscriber"), request.get("NewSubscriber"), SubscriberMutation.UPDATE);
    }

    public synchronized void deleteSubscriber(JsonNode request) {
        mutateSubscriber(request, request.get("Subscriber"), null, SubscriberMutation.DELETE);
    }

    public JsonNode listTagsForResource(JsonNode request) {
        ResourceRef ref = parseResourceArn(requireText(request, "ResourceARN"));
        JsonNode tags = tagsFor(ref);
        return tags == null ? objectMapper.createArrayNode() : tags.deepCopy();
    }

    public synchronized void tagResource(JsonNode request) {
        ResourceRef ref = parseResourceArn(requireText(request, "ResourceARN"));
        JsonNode incoming = request.get("ResourceTags");
        validateTags(incoming);
        ArrayNode merged = objectMapper.createArrayNode();
        Map<String, String> tags = new java.util.LinkedHashMap<>();
        JsonNode current = tagsFor(ref);
        if (current != null && current.isArray()) {
            current.forEach(tag -> tags.put(text(tag, "Key"), text(tag, "Value")));
        }
        if (incoming != null && incoming.isArray()) {
            incoming.forEach(tag -> tags.put(text(tag, "Key"), text(tag, "Value")));
        }
        if (tags.size() > 200) {
            throw new AwsException("ServiceQuotaExceededException", "Tag limit exceeded.", 400);
        }
        tags.forEach((key, value) -> {
            ObjectNode tag = merged.addObject();
            tag.put("Key", key);
            tag.put("Value", value);
        });
        setTags(ref, merged);
    }

    public synchronized void untagResource(JsonNode request) {
        ResourceRef ref = parseResourceArn(requireText(request, "ResourceARN"));
        JsonNode keys = request.get("ResourceTagKeys");
        if (keys == null || !keys.isArray() || keys.isEmpty()) {
            throw invalid("ResourceTagKeys must contain at least one key.");
        }
        Set<String> remove = new HashSet<>();
        keys.forEach(node -> {
            if (!node.isTextual() || node.textValue().isBlank()) {
                throw invalid("ResourceTagKeys contains an invalid key.");
            }
            remove.add(node.textValue());
        });
        ArrayNode remaining = objectMapper.createArrayNode();
        JsonNode current = tagsFor(ref);
        if (current != null && current.isArray()) {
            current.forEach(tag -> {
                if (!remove.contains(text(tag, "Key"))) {
                    remaining.add(tag.deepCopy());
                }
            });
        }
        setTags(ref, remaining);
    }

    public ObjectNode describeBudgetPerformanceHistory(JsonNode request) {
        BudgetRecord record = requireBudget(requireAccount(request), requireBudgetName(request));
        JsonNode budget = record.getBudget();
        String timeUnit = text(budget, "TimeUnit");
        if ("ANNUALLY".equals(timeUnit)) {
            throw invalid("Budget performance history is not available for annual budgets.");
        }
        readMaxResults(request, 100, 100);
        if (text(request, "NextToken") != null) {
            throw new AwsException("InvalidNextTokenException", "The pagination token is invalid.", 400);
        }
        ObjectNode history = objectMapper.createObjectNode();
        copyIfPresent(budget, history, "BudgetName", "BudgetType", "CostFilters", "CostTypes", "FilterExpression", "Metrics", "TimeUnit", "BillingViewArn");
        history.putArray("BudgetedAndActualAmountsList");
        return history;
    }

    public synchronized BudgetActionRecord createBudgetAction(JsonNode request) {
        String accountId = requireAccount(request);
        String budgetName = requireBudgetName(request);
        requireBudget(accountId, budgetName);
        validateActionRequest(request, true, text(request, "ActionType"));
        String actionId = UUID.randomUUID().toString();
        ObjectNode action = actionFromRequest(request, actionId, "STANDBY");
        BudgetActionRecord record = new BudgetActionRecord();
        record.setAccountId(accountId);
        record.setBudgetName(budgetName);
        record.setActionId(actionId);
        record.setAction(action);
        JsonNode tags = request.get("ResourceTags");
        validateTags(tags);
        record.setResourceTags(tags == null ? null : tags.deepCopy());
        actions.putForAccount(accountId, actionKey(budgetName, actionId), record);
        return record;
    }

    public BudgetActionRecord describeBudgetAction(JsonNode request) {
        return requireAction(requireAccount(request), requireBudgetName(request), requireActionId(request));
    }

    public PaginatedResult<JsonNode> describeBudgetActionsForAccount(JsonNode request) {
        String accountId = requireAccount(request);
        List<JsonNode> items = actions.scanForAccount(accountId, key -> true).stream()
                .map(BudgetActionRecord::getAction)
                .sorted(Comparator.comparing(node -> node.path("ActionId").asText()))
                .toList();
        return Pagination.paginate(items, node -> node.path("ActionId").asText(), readMaxResults(request, 100, 100),
                text(request, "NextToken"), 100, "InvalidNextTokenException");
    }

    public PaginatedResult<JsonNode> describeBudgetActionsForBudget(JsonNode request) {
        String accountId = requireAccount(request);
        String budgetName = requireBudgetName(request);
        requireBudget(accountId, budgetName);
        List<JsonNode> items = actions.scanForAccount(accountId, key -> key.startsWith(budgetName + "::")).stream()
                .map(BudgetActionRecord::getAction)
                .sorted(Comparator.comparing(node -> node.path("ActionId").asText()))
                .toList();
        return Pagination.paginate(items, node -> node.path("ActionId").asText(), readMaxResults(request, 100, 100),
                text(request, "NextToken"), 100, "InvalidNextTokenException");
    }

    public PaginatedResult<JsonNode> describeBudgetActionHistories(JsonNode request) {
        BudgetActionRecord record = requireAction(requireAccount(request), requireBudgetName(request), requireActionId(request));
        List<JsonNode> histories = record.getHistories();
        return Pagination.paginate(histories, JsonNode::toString, readMaxResults(request, 100, 100),
                text(request, "NextToken"), 100, "InvalidNextTokenException");
    }

    public synchronized ObjectNode updateBudgetAction(JsonNode request) {
        String accountId = requireAccount(request);
        String budgetName = requireBudgetName(request);
        String actionId = requireActionId(request);
        BudgetActionRecord record = requireAction(accountId, budgetName, actionId);
        validateActionRequest(request, false, record.getAction().path("ActionType").asText());
        ObjectNode oldAction = record.getAction().deepCopy();
        ObjectNode updated = oldAction.deepCopy();
        for (String field : List.of("ActionThreshold", "ApprovalModel", "Definition", "ExecutionRoleArn", "NotificationType", "Subscribers")) {
            if (request.has(field)) {
                updated.set(field, request.get(field).deepCopy());
            }
        }
        record.setAction(updated);
        addActionHistory(record, "UPDATE_ACTION", updated.path("Status").asText(), "Budget action updated.", updated);
        actions.putForAccount(accountId, actionKey(budgetName, actionId), record);
        ObjectNode result = objectMapper.createObjectNode();
        result.put("AccountId", accountId);
        result.put("BudgetName", budgetName);
        result.set("OldAction", oldAction);
        result.set("NewAction", updated);
        return result;
    }

    public synchronized BudgetActionRecord deleteBudgetAction(JsonNode request) {
        String accountId = requireAccount(request);
        String budgetName = requireBudgetName(request);
        String actionId = requireActionId(request);
        BudgetActionRecord record = requireAction(accountId, budgetName, actionId);
        actions.deleteForAccount(accountId, actionKey(budgetName, actionId));
        return record;
    }

    public synchronized BudgetActionRecord executeBudgetAction(JsonNode request) {
        String accountId = requireAccount(request);
        String budgetName = requireBudgetName(request);
        String actionId = requireActionId(request);
        BudgetActionRecord record = requireAction(accountId, budgetName, actionId);
        String executionType = requireText(request, "ExecutionType");
        if (!Set.of("APPROVE_BUDGET_ACTION", "RETRY_BUDGET_ACTION", "REVERSE_BUDGET_ACTION", "RESET_BUDGET_ACTION").contains(executionType)) {
            throw invalid("ExecutionType is invalid.");
        }
        ObjectNode action = record.getAction().deepCopy();
        String status = switch (executionType) {
            case "REVERSE_BUDGET_ACTION" -> "REVERSE_SUCCESS";
            case "RESET_BUDGET_ACTION" -> "STANDBY";
            default -> "EXECUTION_SUCCESS";
        };
        action.put("Status", status);
        record.setAction(action);
        addActionHistory(record, "EXECUTE_ACTION", status, "Budget action execution completed.", action);
        actions.putForAccount(accountId, actionKey(budgetName, actionId), record);
        return record;
    }

    @Override
    public synchronized void clear() {
        budgets.clear();
        actions.clear();
    }

    private void mutateSubscriber(JsonNode request, JsonNode oldNode, JsonNode newNode, SubscriberMutation mutation) {
        String accountId = requireAccount(request);
        String budgetName = requireBudgetName(request);
        BudgetRecord record = requireBudget(accountId, budgetName);
        JsonNode notification = requireNotification(request.get("Notification"));
        BudgetNotification found = findNotification(record, notification);
        if (found == null) {
            throw notFound("The specified notification was not found.");
        }
        JsonNode oldSubscriber = oldNode == null ? null : requireSubscriber(oldNode);
        JsonNode newSubscriber = newNode == null ? null : requireSubscriber(newNode);
        if (mutation == SubscriberMutation.CREATE) {
            if (findSubscriber(found, newSubscriber) != null) {
                throw new AwsException("DuplicateRecordException", "The subscriber already exists.", 400);
            }
            List<JsonNode> copy = new ArrayList<>(found.getSubscribers());
            copy.add(newSubscriber);
            found.setSubscribers(parseSubscribers(objectMapper.valueToTree(copy)));
        } else {
            JsonNode existing = findSubscriber(found, oldSubscriber);
            if (existing == null) {
                throw notFound("The specified subscriber was not found.");
            }
            if (mutation == SubscriberMutation.DELETE) {
                found.getSubscribers().remove(existing);
            } else {
                JsonNode duplicate = findSubscriber(found, newSubscriber);
                if (duplicate != null && duplicate != existing) {
                    throw new AwsException("DuplicateRecordException", "The subscriber already exists.", 400);
                }
                int index = found.getSubscribers().indexOf(existing);
                found.getSubscribers().set(index, newSubscriber.deepCopy());
                found.setSubscribers(parseSubscribers(objectMapper.valueToTree(found.getSubscribers())));
            }
        }
        budgets.putForAccount(accountId, key(accountId, budgetName), record);
    }

    private BudgetRecord requireBudget(String accountId, String name) {
        return budgets.getForAccount(accountId, key(accountId, name))
                .orElseThrow(() -> notFound("The specified budget was not found."));
    }

    private BudgetActionRecord requireAction(String accountId, String budgetName, String actionId) {
        return actions.getForAccount(accountId, actionKey(budgetName, actionId))
                .orElseThrow(() -> notFound("The specified budget action was not found."));
    }

    private List<BudgetNotification> parseNotifications(JsonNode node) {
        if (node == null || node.isNull()) {
            return new ArrayList<>();
        }
        if (!node.isArray() || node.size() > MAX_NOTIFICATIONS) {
            throw new AwsException("CreationLimitExceededException", "A budget can have at most 10 notifications.", 400);
        }
        List<BudgetNotification> result = new ArrayList<>();
        for (JsonNode item : node) {
            if (!item.isObject()) {
                throw invalid("NotificationsWithSubscribers contains an invalid entry.");
            }
            JsonNode notification = requireNotification(item.get("Notification"));
            if (result.stream().anyMatch(existing -> existing.getNotification().equals(notification))) {
                throw new AwsException("DuplicateRecordException", "Duplicate notification.", 400);
            }
            result.add(new BudgetNotification(notification.deepCopy(), parseSubscribers(item.get("Subscribers"))));
        }
        return result;
    }

    private List<JsonNode> parseSubscribers(JsonNode node) {
        if (node == null || !node.isArray() || node.isEmpty()) {
            throw invalid("Subscribers must contain at least one subscriber.");
        }
        if (node.size() > 11) {
            throw new AwsException("CreationLimitExceededException", "A notification can have at most 11 subscribers.", 400);
        }
        int sns = 0;
        int email = 0;
        Set<String> unique = new HashSet<>();
        List<JsonNode> result = new ArrayList<>();
        for (JsonNode subscriber : node) {
            JsonNode validated = requireSubscriber(subscriber);
            String type = text(validated, "SubscriptionType");
            String address = text(validated, "Address");
            if ("SNS".equals(type)) {
                sns++;
            } else {
                email++;
            }
            if (sns > 1 || email > MAX_EMAIL_SUBSCRIBERS) {
                throw new AwsException("CreationLimitExceededException", "Subscriber limit exceeded.", 400);
            }
            if (!unique.add(type + "::" + address)) {
                throw new AwsException("DuplicateRecordException", "Duplicate subscriber.", 400);
            }
            result.add(validated.deepCopy());
        }
        return result;
    }

    private JsonNode requireSubscriber(JsonNode node) {
        if (node == null || !node.isObject()) {
            throw invalid("Subscriber is required.");
        }
        String type = text(node, "SubscriptionType");
        String address = text(node, "Address");
        if (!Set.of("SNS", "EMAIL").contains(type) || address == null || address.isBlank()
                || address.contains("\n") || address.contains("\r")) {
            throw invalid("Subscriber is invalid.");
        }
        return node;
    }

    private JsonNode requireNotification(JsonNode node) {
        if (node == null || !node.isObject()) {
            throw invalid("Notification is required.");
        }
        String comparison = text(node, "ComparisonOperator");
        String type = text(node, "NotificationType");
        String thresholdType = text(node, "ThresholdType");
        JsonNode threshold = node.get("Threshold");
        if (!COMPARISONS.contains(comparison) || !NOTIFICATION_TYPES.contains(type)
                || (thresholdType != null && !THRESHOLD_TYPES.contains(thresholdType))
                || threshold == null || !threshold.isNumber() || !Double.isFinite(threshold.asDouble())
                || threshold.asDouble() < 0 || threshold.asDouble() > 10_000_000) {
            throw invalid("Notification contains invalid values.");
        }
        return node;
    }

    private void validateBudget(JsonNode budget) {
        String type = text(budget, "BudgetType");
        String unit = text(budget, "TimeUnit");
        if (type == null || !BUDGET_TYPES.contains(type)) {
            throw invalid("BudgetType is invalid.");
        }
        if (unit == null || !TIME_UNITS.contains(unit)) {
            throw invalid("TimeUnit is invalid.");
        }
        if (budget.has("BudgetLimit") && budget.has("PlannedBudgetLimits")) {
            throw invalid("Only one of BudgetLimit or PlannedBudgetLimits may be specified.");
        }
        boolean newFilters = budget.has("FilterExpression") || budget.has("Metrics");
        boolean legacyFilters = budget.has("CostFilters") || budget.has("CostTypes");
        if (newFilters && legacyFilters) {
            throw invalid("FilterExpression/Metrics and CostFilters/CostTypes cannot be combined.");
        }
        JsonNode period = budget.get("TimePeriod");
        if (period != null && period.isObject() && period.has("Start") && period.has("End")
                && period.path("Start").asDouble() >= period.path("End").asDouble()) {
            throw invalid("TimePeriod Start must be before End.");
        }
    }

    private ObjectNode withBudgetResponseDefaults(JsonNode input) {
        ObjectNode result = input.deepCopy();
        result.put("LastUpdatedTime", Instant.now().getEpochSecond());
        if (!result.has("CalculatedSpend")) {
            ObjectNode spend = result.putObject("CalculatedSpend");
            ObjectNode actual = spend.putObject("ActualSpend");
            actual.put("Amount", "0");
            actual.put("Unit", budgetUnit(result));
        }
        return result;
    }

    private String budgetUnit(JsonNode budget) {
        JsonNode limit = budget.get("BudgetLimit");
        if (limit != null && limit.isObject() && text(limit, "Unit") != null) {
            return text(limit, "Unit");
        }
        return "USD";
    }

    private void validateTags(JsonNode tags) {
        if (tags == null || tags.isNull()) {
            return;
        }
        if (!tags.isArray() || tags.size() > 200) {
            throw invalid("ResourceTags must contain at most 200 tags.");
        }
        Set<String> keys = new HashSet<>();
        for (JsonNode tag : tags) {
            String key = text(tag, "Key");
            String value = text(tag, "Value");
            if (key == null || key.isBlank() || key.length() > 128 || key.startsWith("aws:")
                    || value == null || value.length() > 256) {
                throw invalid("ResourceTags contains an invalid tag.");
            }
            if (!keys.add(key)) {
                throw invalid("ResourceTags contains duplicate keys.");
            }
        }
    }

    private void validateActionRequest(JsonNode request, boolean create, String effectiveActionType) {
        if (create || request.has("NotificationType")) {
            if (!NOTIFICATION_TYPES.contains(text(request, "NotificationType"))) {
                throw invalid("NotificationType is invalid.");
            }
        }
        if (create || request.has("ActionType")) {
            if (!ACTION_TYPES.contains(text(request, "ActionType"))) {
                throw invalid("ActionType is invalid.");
            }
        }
        if (effectiveActionType == null || !ACTION_TYPES.contains(effectiveActionType)) {
            throw invalid("ActionType is invalid.");
        }
        if (create || request.has("ApprovalModel")) {
            if (!APPROVAL_MODELS.contains(text(request, "ApprovalModel"))) {
                throw invalid("ApprovalModel is invalid.");
            }
        }
        if (create || request.has("ActionThreshold")) {
            JsonNode threshold = request.get("ActionThreshold");
            double value = threshold == null ? Double.NaN : threshold.path("ActionThresholdValue").asDouble(Double.NaN);
            if (threshold == null || !threshold.isObject()
                    || !ACTION_THRESHOLD_TYPES.contains(text(threshold, "ActionThresholdType"))
                    || !threshold.path("ActionThresholdValue").isNumber()
                    || !Double.isFinite(value) || value < 0 || value > 15_000_000_000_000D) {
                throw invalid("ActionThreshold is invalid.");
            }
        }
        if (create || request.has("Definition")) {
            validateActionDefinition(request.get("Definition"), effectiveActionType);
        }
        if (create || request.has("ExecutionRoleArn")) {
            String arn = text(request, "ExecutionRoleArn");
            String account = requireAccount(request);
            if (arn == null || !arn.matches("^arn:aws(?:-eusc|-cn|-us-gov|-iso|-iso-[a-z])?:iam::" + account + ":role/.+$")) {
                throw invalid("ExecutionRoleArn is invalid or belongs to another account.");
            }
        }
        if (create || request.has("Subscribers")) {
            parseSubscribers(request.get("Subscribers"));
        }
    }

    private void validateActionDefinition(JsonNode definition, String actionType) {
        if (definition == null || !definition.isObject()) {
            throw invalid("Definition is required.");
        }
        int variants = (definition.has("IamActionDefinition") ? 1 : 0)
                + (definition.has("ScpActionDefinition") ? 1 : 0)
                + (definition.has("SsmActionDefinition") ? 1 : 0);
        if (variants != 1) {
            throw invalid("Definition must contain exactly one action definition.");
        }
        switch (actionType) {
            case "APPLY_IAM_POLICY" -> validateIamActionDefinition(definition.get("IamActionDefinition"));
            case "APPLY_SCP_POLICY" -> validateScpActionDefinition(definition.get("ScpActionDefinition"));
            case "RUN_SSM_DOCUMENTS" -> validateSsmActionDefinition(definition.get("SsmActionDefinition"));
            default -> throw invalid("ActionType is invalid.");
        }
    }

    private void validateIamActionDefinition(JsonNode iam) {
        if (iam == null || !iam.isObject()) {
            throw invalid("Definition does not match ActionType.");
        }
        String policyArn = text(iam, "PolicyArn");
        if (policyArn == null || policyArn.length() < 25 || policyArn.length() > 684
                || !IAM_POLICY_ARN.matcher(policyArn).matches()) {
            throw invalid("IamActionDefinition.PolicyArn is invalid.");
        }
        int targets = validateStringArray(iam, "Groups", 100, 640)
                + validateStringArray(iam, "Roles", 100, 576)
                + validateStringArray(iam, "Users", 100, 576);
        if (targets == 0) {
            throw invalid("IamActionDefinition must target at least one group, role, or user.");
        }
    }

    private void validateScpActionDefinition(JsonNode scp) {
        if (scp == null || !scp.isObject()) {
            throw invalid("Definition does not match ActionType.");
        }
        String policyId = text(scp, "PolicyId");
        if (policyId == null || !SCP_POLICY_ID.matcher(policyId).matches()) {
            throw invalid("ScpActionDefinition.PolicyId is invalid.");
        }
        JsonNode targets = scp.get("TargetIds");
        if (targets == null || !targets.isArray() || targets.isEmpty() || targets.size() > 100) {
            throw invalid("ScpActionDefinition.TargetIds is invalid.");
        }
        for (JsonNode target : targets) {
            if (!target.isTextual() || !SCP_TARGET_ID.matcher(target.textValue()).matches()) {
                throw invalid("ScpActionDefinition.TargetIds is invalid.");
            }
        }
    }

    private void validateSsmActionDefinition(JsonNode ssm) {
        if (ssm == null || !ssm.isObject()) {
            throw invalid("Definition does not match ActionType.");
        }
        if (!Set.of("STOP_EC2_INSTANCES", "STOP_RDS_INSTANCES").contains(text(ssm, "ActionSubType"))) {
            throw invalid("SsmActionDefinition.ActionSubType is invalid.");
        }
        String region = text(ssm, "Region");
        if (region == null || !AWS_REGION.matcher(region).matches()) {
            throw invalid("SsmActionDefinition.Region is invalid.");
        }
        JsonNode instances = ssm.get("InstanceIds");
        if (instances == null || !instances.isArray() || instances.isEmpty() || instances.size() > 100) {
            throw invalid("SsmActionDefinition.InstanceIds is invalid.");
        }
        for (JsonNode instance : instances) {
            if (!instance.isTextual() || !SSM_INSTANCE_ID.matcher(instance.textValue()).matches()) {
                throw invalid("SsmActionDefinition.InstanceIds is invalid.");
            }
        }
    }

    private int validateStringArray(JsonNode node, String field, int maximumItems, int maximumLength) {
        JsonNode values = node.get(field);
        if (values == null || values.isNull()) {
            return 0;
        }
        if (!values.isArray() || values.isEmpty() || values.size() > maximumItems) {
            throw invalid(field + " is invalid.");
        }
        for (JsonNode value : values) {
            if (!value.isTextual() || value.textValue().isBlank() || value.textValue().length() > maximumLength) {
                throw invalid(field + " is invalid.");
            }
        }
        return values.size();
    }

    private ObjectNode actionFromRequest(JsonNode request, String actionId, String status) {
        ObjectNode action = objectMapper.createObjectNode();
        action.put("ActionId", actionId);
        action.put("BudgetName", requireBudgetName(request));
        for (String field : List.of("NotificationType", "ActionType", "ActionThreshold", "Definition", "ExecutionRoleArn", "ApprovalModel", "Subscribers")) {
            action.set(field, request.get(field).deepCopy());
        }
        action.put("Status", status);
        return action;
    }

    private void addActionHistory(BudgetActionRecord record, String eventType, String status, String message, JsonNode action) {
        ObjectNode history = objectMapper.createObjectNode();
        history.put("Timestamp", Instant.now().getEpochSecond());
        history.put("Status", status);
        history.put("EventType", eventType);
        ObjectNode details = history.putObject("ActionHistoryDetails");
        details.set("Action", action.deepCopy());
        details.put("Message", message);
        record.getHistories().add(history);
    }

    private JsonNode tagsFor(ResourceRef ref) {
        if (ref.actionId() == null) {
            return requireBudget(ref.accountId(), ref.budgetName()).getResourceTags();
        }
        return requireAction(ref.accountId(), ref.budgetName(), ref.actionId()).getResourceTags();
    }

    private void setTags(ResourceRef ref, JsonNode tags) {
        if (ref.actionId() == null) {
            BudgetRecord record = requireBudget(ref.accountId(), ref.budgetName());
            record.setResourceTags(tags.deepCopy());
            budgets.putForAccount(ref.accountId(), key(ref.accountId(), ref.budgetName()), record);
        } else {
            BudgetActionRecord record = requireAction(ref.accountId(), ref.budgetName(), ref.actionId());
            record.setResourceTags(tags.deepCopy());
            actions.putForAccount(ref.accountId(), actionKey(ref.budgetName(), ref.actionId()), record);
        }
    }

    private ResourceRef parseResourceArn(String arn) {
        String marker = ":budgets::";
        int service = arn.indexOf(marker);
        if (!arn.startsWith("arn:") || service < 0) {
            throw invalid("ResourceARN is not a valid Budgets ARN.");
        }
        int resourceStart = service + marker.length();
        int separator = arn.indexOf(':', resourceStart);
        if (separator < 0) {
            throw invalid("ResourceARN is not a valid Budgets ARN.");
        }
        String account = arn.substring(resourceStart, separator);
        String resource = arn.substring(separator + 1);
        if (!ACCOUNT_ID.matcher(account).matches() || !resource.startsWith("budget/")) {
            throw invalid("ResourceARN is not a valid Budgets ARN.");
        }
        String value = resource.substring("budget/".length());
        int actionMarker = value.indexOf("/action/");
        if (actionMarker < 0) {
            return new ResourceRef(account, requireBudgetNameValue(value), null);
        }
        String budgetName = requireBudgetNameValue(value.substring(0, actionMarker));
        String actionId = value.substring(actionMarker + "/action/".length());
        if (!ACTION_ID.matcher(actionId).matches()) {
            throw invalid("ResourceARN contains an invalid action ID.");
        }
        return new ResourceRef(account, budgetName, actionId);
    }

    private static int readMaxResults(JsonNode request, int defaultValue, int maximum) {
        JsonNode node = request == null ? null : request.get("MaxResults");
        if (node == null || node.isNull()) {
            return defaultValue;
        }
        if (!node.canConvertToInt() || node.asInt() < 1 || node.asInt() > maximum) {
            throw invalid("MaxResults must be between 1 and " + maximum + ".");
        }
        return node.asInt();
    }

    private static String requireAccount(JsonNode request) {
        String account = text(request, "AccountId");
        if (account == null || !ACCOUNT_ID.matcher(account).matches()) {
            throw invalid("AccountId must be a 12 digit account ID.");
        }
        return account;
    }

    private static String requireActionId(JsonNode request) {
        String actionId = text(request, "ActionId");
        if (actionId == null || !ACTION_ID.matcher(actionId).matches()) {
            throw invalid("ActionId is invalid.");
        }
        return actionId;
    }

    private static String requireBudgetName(JsonNode request) {
        return requireBudgetName(request, "BudgetName");
    }

    private static String requireBudgetName(JsonNode request, String field) {
        return requireBudgetNameValue(text(request, field));
    }

    private static String requireBudgetNameValue(String name) {
        if (name == null || name.length() < 1 || name.length() > 100 || !BUDGET_NAME.matcher(name).matches()) {
            throw invalid("BudgetName is invalid.");
        }
        return name;
    }

    private static String requireText(JsonNode node, String field) {
        String value = text(node, field);
        if (value == null || value.isBlank()) {
            throw invalid(field + " is required.");
        }
        return value;
    }

    private static String text(JsonNode node, String field) {
        JsonNode value = node == null ? null : node.get(field);
        return value != null && value.isTextual() ? value.textValue() : null;
    }

    private static String key(String accountId, String name) {
        return accountId + "::" + name;
    }

    private static String actionKey(String budgetName, String actionId) {
        return budgetName + "::" + actionId;
    }

    private String notificationCursor(JsonNode node) {
        return node.toString();
    }

    private String subscriberCursor(JsonNode node) {
        return node.path("SubscriptionType").asText() + "::" + node.path("Address").asText();
    }

    private static BudgetNotification findNotification(BudgetRecord record, JsonNode notification) {
        return record.getNotifications().stream()
                .filter(candidate -> candidate.getNotification().equals(notification))
                .findFirst()
                .orElse(null);
    }

    private static JsonNode findSubscriber(BudgetNotification notification, JsonNode subscriber) {
        if (subscriber == null) {
            return null;
        }
        return notification.getSubscribers().stream().filter(subscriber::equals).findFirst().orElse(null);
    }

    private static void copyIfPresent(JsonNode source, ObjectNode target, String... fields) {
        for (String field : fields) {
            if (source.has(field)) {
                target.set(field, source.get(field).deepCopy());
            }
        }
    }

    private static AwsException invalid(String message) {
        return new AwsException("InvalidParameterException", message, 400);
    }

    private static AwsException notFound(String message) {
        return new AwsException("NotFoundException", message, 400);
    }

    private enum SubscriberMutation { CREATE, UPDATE, DELETE }
    private record ResourceRef(String accountId, String budgetName, String actionId) {}
}
