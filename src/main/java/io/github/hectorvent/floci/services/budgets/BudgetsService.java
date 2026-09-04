package io.github.hectorvent.floci.services.budgets;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.core.common.PaginatedResult;
import io.github.hectorvent.floci.core.common.Pagination;
import io.github.hectorvent.floci.core.storage.AccountAwareStorageBackend;
import io.github.hectorvent.floci.core.storage.StorageFactory;
import io.github.hectorvent.floci.services.budgets.model.BudgetNotification;
import io.github.hectorvent.floci.services.budgets.model.BudgetRecord;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

@ApplicationScoped
public class BudgetsService {
    private static final Pattern ACCOUNT_ID = Pattern.compile("\\d{12}");
    private static final Pattern BUDGET_NAME = Pattern.compile("^(?![^:\\\\]*/action/|(?i).*<script>.*</script>.*)[^:\\\\]+$");
    private static final Set<String> BUDGET_TYPES = Set.of("USAGE", "COST", "RI_UTILIZATION", "RI_COVERAGE",
            "SAVINGS_PLANS_UTILIZATION", "SAVINGS_PLANS_COVERAGE");
    private static final Set<String> TIME_UNITS = Set.of("DAILY", "MONTHLY", "QUARTERLY", "ANNUALLY", "CUSTOM");
    private static final Set<String> COMPARISONS = Set.of("GREATER_THAN", "LESS_THAN", "EQUAL_TO");
    private static final Set<String> NOTIFICATION_TYPES = Set.of("ACTUAL", "FORECASTED");
    private static final Set<String> THRESHOLD_TYPES = Set.of("PERCENTAGE", "ABSOLUTE_VALUE");
    private static final int MAX_NOTIFICATIONS = 10;
    private static final int MAX_EMAIL_SUBSCRIBERS = 10;
    private final AccountAwareStorageBackend<BudgetRecord> budgets;

    @Inject
    public BudgetsService(StorageFactory storageFactory) {
        budgets = storageFactory.create("budgets", "budgets.json", new TypeReference<Map<String, BudgetRecord>>() {});
    }

    public BudgetRecord describeBudget(JsonNode request) {
        return requireBudget(requireAccount(request), requireBudgetName(request));
    }

    public synchronized void createBudget(JsonNode request) {
        String accountId = requireAccount(request);
        JsonNode budget = request.get("Budget");
        if (budget == null || !budget.isObject()) throw invalid("Budget is required.");
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
        record.setBudget(budget.deepCopy());
        record.setResourceTags(resourceTags == null ? null : resourceTags.deepCopy());
        record.setNotifications(notifications);
        budgets.putForAccount(accountId, key, record);
    }

    public synchronized void updateBudget(JsonNode request) {
        String accountId = requireAccount(request);
        JsonNode newBudget = request.get("NewBudget");
        if (newBudget == null || !newBudget.isObject()) throw invalid("NewBudget is required.");
        String name = requireBudgetName(newBudget, "BudgetName");
        validateBudget(newBudget);
        BudgetRecord record = requireBudget(accountId, name);
        record.setBudget(newBudget.deepCopy());
        budgets.putForAccount(accountId, key(accountId, name), record);
    }

    public synchronized void deleteBudget(JsonNode request) {
        String accountId = requireAccount(request);
        String name = requireBudgetName(request);
        requireBudget(accountId, name);
        budgets.deleteForAccount(accountId, key(accountId, name));
    }

    public JsonNode listTagsForResource(JsonNode request) {
        String arn = text(request, "ResourceARN");
        if (arn == null || arn.isBlank()) throw invalid("ResourceARN is required.");
        ArnBudget ref = parseArn(arn);
        BudgetRecord record = requireBudget(ref.accountId(), ref.name());
        return record.getResourceTags();
    }

    public PaginatedResult<JsonNode> describeNotifications(JsonNode request) {
        BudgetRecord record = requireBudget(requireAccount(request), requireBudgetName(request));
        List<JsonNode> notifications = record.getNotifications().stream().map(BudgetNotification::getNotification).toList();
        return Pagination.paginate(notifications, this::notificationCursor, readMaxResults(request), text(request, "NextToken"),
                100, "InvalidNextTokenException");
    }

    public PaginatedResult<JsonNode> describeSubscribers(JsonNode request) {
        BudgetRecord record = requireBudget(requireAccount(request), requireBudgetName(request));
        JsonNode notification = requireNotification(request.get("Notification"));
        BudgetNotification found = findNotification(record, notification);
        if (found == null) throw notFound("The specified notification was not found.");
        return Pagination.paginate(found.getSubscribers(), this::subscriberCursor, readMaxResults(request), text(request, "NextToken"),
                100, "InvalidNextTokenException");
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

    public synchronized void deleteNotification(JsonNode request) {
        String accountId = requireAccount(request);
        String name = requireBudgetName(request);
        BudgetRecord record = requireBudget(accountId, name);
        JsonNode notification = requireNotification(request.get("Notification"));
        BudgetNotification found = findNotification(record, notification);
        if (found == null) throw notFound("The specified notification was not found.");
        record.getNotifications().remove(found);
        budgets.putForAccount(accountId, key(accountId, name), record);
    }

    private BudgetRecord requireBudget(String accountId, String name) {
        return budgets.getForAccount(accountId, key(accountId, name))
                .orElseThrow(() -> notFound("The specified budget was not found."));
    }

    private List<BudgetNotification> parseNotifications(JsonNode node) {
        if (node == null || node.isNull()) return new ArrayList<>();
        if (!node.isArray() || node.size() > MAX_NOTIFICATIONS) {
            throw new AwsException("CreationLimitExceededException", "A budget can have at most 10 notifications.", 400);
        }
        List<BudgetNotification> result = new ArrayList<>();
        for (JsonNode item : node) {
            JsonNode notification = requireNotification(item.get("Notification"));
            if (result.stream().anyMatch(existing -> existing.getNotification().equals(notification))) {
                throw new AwsException("DuplicateRecordException", "Duplicate notification.", 400);
            }
            result.add(new BudgetNotification(notification.deepCopy(), parseSubscribers(item.get("Subscribers"))));
        }
        return result;
    }

    private List<JsonNode> parseSubscribers(JsonNode node) {
        if (node == null || !node.isArray() || node.isEmpty()) throw invalid("Subscribers must contain at least one subscriber.");
        if (node.size() > 11) throw new AwsException("CreationLimitExceededException", "A notification can have at most 11 subscribers.", 400);
        int sns = 0; int email = 0; Set<String> unique = new HashSet<>(); List<JsonNode> result = new ArrayList<>();
        for (JsonNode subscriber : node) {
            String type = text(subscriber, "SubscriptionType");
            String address = text(subscriber, "Address");
            if (!Set.of("SNS", "EMAIL").contains(type) || address == null || address.isBlank() || address.contains("\n") || address.contains("\r")) {
                throw invalid("Subscriber is invalid.");
            }
            if ("SNS".equals(type)) sns++; else email++;
            if (sns > 1 || email > MAX_EMAIL_SUBSCRIBERS) throw new AwsException("CreationLimitExceededException", "Subscriber limit exceeded.", 400);
            if (!unique.add(type + "::" + address)) throw new AwsException("DuplicateRecordException", "Duplicate subscriber.", 400);
            result.add(subscriber.deepCopy());
        }
        return result;
    }

    private JsonNode requireNotification(JsonNode node) {
        if (node == null || !node.isObject()) throw invalid("Notification is required.");
        String comparison = text(node, "ComparisonOperator");
        String type = text(node, "NotificationType");
        String thresholdType = text(node, "ThresholdType");
        JsonNode threshold = node.get("Threshold");
        if (!COMPARISONS.contains(comparison) || !NOTIFICATION_TYPES.contains(type)
                || (thresholdType != null && !THRESHOLD_TYPES.contains(thresholdType))
                || threshold == null || !threshold.isNumber() || !Double.isFinite(threshold.asDouble()) || threshold.asDouble() < 0) {
            throw invalid("Notification contains invalid values.");
        }
        return node;
    }

    private void validateBudget(JsonNode budget) {
        String type = text(budget, "BudgetType");
        String unit = text(budget, "TimeUnit");
        if (type == null || !BUDGET_TYPES.contains(type)) throw invalid("BudgetType is invalid.");
        if (unit == null || !TIME_UNITS.contains(unit)) throw invalid("TimeUnit is invalid.");
        if (budget.has("BudgetLimit") && budget.has("PlannedBudgetLimits")) throw invalid("Only one of BudgetLimit or PlannedBudgetLimits may be specified.");
        boolean newFilters = budget.has("FilterExpression") || budget.has("Metrics");
        boolean legacyFilters = budget.has("CostFilters") || budget.has("CostTypes");
        if (newFilters && legacyFilters) throw invalid("FilterExpression/Metrics and CostFilters/CostTypes cannot be combined.");
    }

    private void validateTags(JsonNode tags) {
        if (tags == null || tags.isNull()) return;
        if (!tags.isArray() || tags.size() > 200) throw invalid("ResourceTags must contain at most 200 tags.");
        Set<String> keys = new HashSet<>();
        for (JsonNode tag : tags) {
            String key = text(tag, "Key"); String value = text(tag, "Value");
            if (key == null || key.isBlank() || key.length() > 128 || key.startsWith("aws:") || value == null || value.length() > 256) throw invalid("ResourceTags contains an invalid tag.");
            if (!keys.add(key)) throw invalid("ResourceTags contains duplicate keys.");
        }
    }

    private static int readMaxResults(JsonNode request) {
        JsonNode node = request == null ? null : request.get("MaxResults");
        if (node == null || node.isNull()) return 100;
        if (!node.canConvertToInt() || node.asInt() < 1 || node.asInt() > 100) throw invalid("MaxResults must be between 1 and 100.");
        return node.asInt();
    }

    private static String requireAccount(JsonNode request) {
        String account = text(request, "AccountId");
        if (account == null || !ACCOUNT_ID.matcher(account).matches()) throw invalid("AccountId must be a 12 digit account ID.");
        return account;
    }
    private static String requireBudgetName(JsonNode request) { return requireBudgetName(request, "BudgetName"); }
    private static String requireBudgetName(JsonNode request, String field) {
        String name = text(request, field);
        if (name == null || name.length() < 1 || name.length() > 100 || !BUDGET_NAME.matcher(name).matches()) throw invalid(field + " is invalid.");
        return name;
    }
    private static String text(JsonNode node, String field) { JsonNode value=node==null?null:node.get(field); return value!=null&&value.isTextual()?value.textValue():null; }
    private static String key(String accountId, String name) { return accountId + "::" + name; }
    private String notificationCursor(JsonNode node) { return node.toString(); }
    private String subscriberCursor(JsonNode node) { return node.path("SubscriptionType").asText() + "::" + node.path("Address").asText(); }
    private static BudgetNotification findNotification(BudgetRecord record, JsonNode notification) { return record.getNotifications().stream().filter(n -> n.getNotification().equals(notification)).findFirst().orElse(null); }
    private static ArnBudget parseArn(String arn) {
        String prefix="arn:aws:budgets::"; int marker=arn.indexOf(":budget/");
        if(!arn.startsWith(prefix)||marker<0)throw invalid("ResourceARN is not a valid budget ARN.");
        String account=arn.substring(prefix.length(),marker); String name=arn.substring(marker+8);
        if(!ACCOUNT_ID.matcher(account).matches()||name.isBlank())throw invalid("ResourceARN is not a valid budget ARN.");
        return new ArnBudget(account,name);
    }
    private static AwsException invalid(String message) { return new AwsException("InvalidParameterException", message, 400); }
    private static AwsException notFound(String message) { return new AwsException("NotFoundException", message, 400); }
    private record ArnBudget(String accountId,String name) {}
}
