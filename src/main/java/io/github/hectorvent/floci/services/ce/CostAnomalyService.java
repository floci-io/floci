package io.github.hectorvent.floci.services.ce;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.hectorvent.floci.core.common.AwsArnUtils;
import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.core.common.PaginatedResult;
import io.github.hectorvent.floci.core.common.Pagination;
import io.github.hectorvent.floci.core.common.Resettable;
import io.github.hectorvent.floci.core.storage.AccountAwareStorageBackend;
import io.github.hectorvent.floci.core.storage.StorageFactory;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;

/** Stores Cost Anomaly Detection configuration without generating anomalies or notifications. */
@ApplicationScoped
public class CostAnomalyService implements Resettable {

    private static final BigDecimal MAX_THRESHOLD = new BigDecimal("10000000000");
    private static final Pattern TAG_TEXT = Pattern.compile("[\\p{L}\\p{Z}\\p{N}_.:/=+\\-@]*");
    private static final Pattern EMAIL = Pattern.compile(
            "^[a-zA-Z0-9.!#$%&'*+=?^_`{|}~-]+@[a-zA-Z0-9_-]+(\\.[a-zA-Z0-9_-]+)+$");
    private static final Pattern SNS = Pattern.compile(
            "^arn:" + AwsArnUtils.PARTITION_REGEX + ":sns:[a-zA-Z0-9-]+:[0-9]{12}:[a-zA-Z0-9_-]+(\\.fifo)?$");

    private final AccountAwareStorageBackend<ObjectNode> monitors;
    private final AccountAwareStorageBackend<ObjectNode> subscriptions;
    private final ObjectMapper mapper;

    @Inject
    public CostAnomalyService(StorageFactory storageFactory, ObjectMapper mapper) {
        this(storageFactory.create("ce", "anomaly-monitors.json", new TypeReference<Map<String, ObjectNode>>() {}),
                storageFactory.create("ce", "anomaly-subscriptions.json", new TypeReference<Map<String, ObjectNode>>() {}),
                mapper);
    }

    CostAnomalyService(AccountAwareStorageBackend<ObjectNode> monitors,
                       AccountAwareStorageBackend<ObjectNode> subscriptions, ObjectMapper mapper) {
        this.monitors = monitors;
        this.subscriptions = subscriptions;
        this.mapper = mapper;
    }

    public synchronized ObjectNode createMonitor(JsonNode request) {
        ObjectNode input = object(request, "AnomalyMonitor");
        validateMonitor(input);
        ObjectNode tags = tags(request, false);
        String account = monitors.accountId();
        String type = input.path("MonitorType").textValue();
        String dimension = input.path("MonitorDimension").asText();
        long count = monitors.scanForAccount(account, key -> true).stream()
                .map(value -> value.path("definition"))
                .filter(value -> type.equals(value.path("MonitorType").asText()))
                .filter(value -> !"DIMENSIONAL".equals(type)
                        || "SERVICE".equals(dimension) == "SERVICE".equals(value.path("MonitorDimension").asText()))
                .count();
        if (count >= ("CUSTOM".equals(type) ? 500 : 1)) {
            throw new AwsException("LimitExceededException", "The account's monitor limit has been reached.", 400);
        }
        ObjectNode definition = fields(input, "MonitorName", "MonitorType", "MonitorDimension", "MonitorSpecification");
        String arn = arn(account, "anomalymonitor");
        String now = Instant.now().truncatedTo(ChronoUnit.SECONDS).toString();
        definition.put("MonitorArn", arn).put("CreationDate", now).put("LastUpdatedDate", now);
        monitors.putForAccount(account, arn, resource(definition, tags));
        return mapper.createObjectNode().put("MonitorArn", arn);
    }

    public synchronized ObjectNode getMonitors(JsonNode request) {
        List<ObjectNode> definitions = definitions(monitors, request, "MonitorArnList", "UnknownMonitorException");
        return page(definitions, request, "AnomalyMonitors", "MonitorArn");
    }

    public synchronized ObjectNode updateMonitor(JsonNode request) {
        String arn = text(request, "MonitorArn", 1024);
        String account = monitors.accountId();
        ObjectNode resource = existing(monitors, account, arn, "UnknownMonitorException").deepCopy();
        ObjectNode definition = (ObjectNode) resource.get("definition");
        if (present(request, "MonitorName")) {
            definition.put("MonitorName", text(request, "MonitorName", 1024));
        }
        definition.put("LastUpdatedDate", Instant.now().truncatedTo(ChronoUnit.SECONDS).toString());
        monitors.putForAccount(account, arn, resource);
        return mapper.createObjectNode().put("MonitorArn", arn);
    }

    public synchronized void deleteMonitor(JsonNode request) {
        String arn = text(request, "MonitorArn", 1024);
        String account = monitors.accountId();
        existing(monitors, account, arn, "UnknownMonitorException");
        monitors.deleteForAccount(account, arn);
    }

    public synchronized ObjectNode createSubscription(JsonNode request) {
        ObjectNode input = object(request, "AnomalySubscription");
        String account = monitors.accountId();
        text(input, "SubscriptionName", 1024);
        validateMonitorReferences(input, account);
        validateSubscribers(input);
        ObjectNode definition = fields(input, "SubscriptionName", "Frequency", "MonitorArnList", "Subscribers");
        definition.set("ThresholdExpression", threshold(input));
        ObjectNode tags = tags(request, false);
        if (subscriptions.scanForAccount(account, key -> true).size() >= 100) {
            throw new AwsException("LimitExceededException", "The account's subscription limit has been reached.", 400);
        }
        String arn = arn(account, "anomalysubscription");
        definition.put("SubscriptionArn", arn).put("AccountId", account);
        subscriptions.putForAccount(account, arn, resource(definition, tags));
        return mapper.createObjectNode().put("SubscriptionArn", arn);
    }

    public synchronized ObjectNode getSubscriptions(JsonNode request) {
        List<ObjectNode> definitions = definitions(subscriptions, request,
                "SubscriptionArnList", "UnknownSubscriptionException");
        if (present(request, "MonitorArn")) {
            String monitorArn = text(request, "MonitorArn", 1024);
            definitions = definitions.stream()
                    .filter(value -> contains(value.path("MonitorArnList"), monitorArn)).toList();
        }
        return page(definitions, request, "AnomalySubscriptions", "SubscriptionArn");
    }

    public synchronized ObjectNode updateSubscription(JsonNode request) {
        String arn = text(request, "SubscriptionArn", 1024);
        String account = monitors.accountId();
        ObjectNode resource = existing(subscriptions, account, arn, "UnknownSubscriptionException").deepCopy();
        ObjectNode definition = (ObjectNode) resource.get("definition");
        if (present(request, "SubscriptionName")) {
            definition.put("SubscriptionName", text(request, "SubscriptionName", 1024));
        }
        if (present(request, "MonitorArnList")) {
            validateMonitorReferences(request, account);
            definition.set("MonitorArnList", request.get("MonitorArnList").deepCopy());
        }
        for (String field : List.of("Frequency", "Subscribers")) {
            if (present(request, field)) {
                definition.set(field, request.get(field).deepCopy());
            }
        }
        validateSubscribers(definition);
        if (present(request, "Threshold") || present(request, "ThresholdExpression")) {
            definition.set("ThresholdExpression", threshold(request));
            definition.remove("Threshold");
        }
        subscriptions.putForAccount(account, arn, resource);
        return mapper.createObjectNode().put("SubscriptionArn", arn);
    }

    public synchronized void deleteSubscription(JsonNode request) {
        String arn = text(request, "SubscriptionArn", 1024);
        String account = monitors.accountId();
        existing(subscriptions, account, arn, "UnknownSubscriptionException");
        subscriptions.deleteForAccount(account, arn);
    }

    public synchronized ObjectNode listTags(JsonNode request) {
        String arn = text(request, "ResourceArn", 2048);
        AccountAwareStorageBackend<ObjectNode> storage = taggedStorage(arn);
        JsonNode resourceTags = existing(storage, monitors.accountId(), arn, "ResourceNotFoundException").path("tags");
        ObjectNode response = mapper.createObjectNode();
        ArrayNode output = response.putArray("ResourceTags");
        resourceTags.properties().forEach(entry -> output.addObject()
                .put("Key", entry.getKey()).put("Value", entry.getValue().textValue()));
        return response;
    }

    public synchronized void tagResource(JsonNode request) {
        String arn = text(request, "ResourceArn", 2048);
        AccountAwareStorageBackend<ObjectNode> storage = taggedStorage(arn);
        String account = monitors.accountId();
        ObjectNode resource = existing(storage, account, arn, "ResourceNotFoundException").deepCopy();
        ObjectNode updatedTags = (ObjectNode) resource.get("tags");
        updatedTags.setAll(tags(request, true));
        checkTagCount(updatedTags);
        storage.putForAccount(account, arn, resource);
    }

    public synchronized void untagResource(JsonNode request) {
        String arn = text(request, "ResourceArn", 2048);
        AccountAwareStorageBackend<ObjectNode> storage = taggedStorage(arn);
        String account = monitors.accountId();
        ObjectNode resource = existing(storage, account, arn, "ResourceNotFoundException").deepCopy();
        ObjectNode updatedTags = (ObjectNode) resource.get("tags");
        for (String key : strings(request, "ResourceTagKeys", 200, 128)) {
            updatedTags.remove(tagText(key, true));
        }
        storage.putForAccount(account, arn, resource);
    }

    @Override
    public synchronized void clear() {
        monitors.clear();
        subscriptions.clear();
    }

    private List<ObjectNode> definitions(AccountAwareStorageBackend<ObjectNode> storage, JsonNode request,
                                         String filter, String errorCode) {
        String account = monitors.accountId();
        Set<String> arns = present(request, filter)
                ? new LinkedHashSet<>(strings(request, filter, Integer.MAX_VALUE, 1024)) : Set.of();
        List<ObjectNode> result = new ArrayList<>();
        if (arns.isEmpty()) {
            for (ObjectNode resource : storage.scanForAccount(account, key -> true)) {
                result.add(object(resource, "definition").deepCopy());
            }
        } else {
            for (String arn : arns) {
                result.add(object(existing(storage, account, arn, errorCode), "definition").deepCopy());
            }
        }
        return result;
    }

    private ObjectNode page(List<ObjectNode> definitions, JsonNode request, String responseKey, String arnKey) {
        int maxResults = 100;
        if (present(request, "MaxResults")) {
            JsonNode value = request.get("MaxResults");
            if (!value.isIntegralNumber() || !value.canConvertToInt() || value.intValue() < 1) {
                throw invalid("MaxResults must be a positive integer.");
            }
            maxResults = value.intValue();
        }
        String token = present(request, "NextPageToken") ? text(request, "NextPageToken", 8192) : null;
        int pageSize = Math.min(maxResults, Math.max(definitions.size(), 1));
        PaginatedResult<ObjectNode> page = Pagination.paginate(definitions,
                value -> value.path(arnKey).asText(), pageSize, token, Integer.MAX_VALUE, "InvalidNextTokenException");
        ObjectNode response = mapper.createObjectNode();
        response.putArray(responseKey).addAll(page.items());
        if (page.nextToken() != null) {
            response.put("NextPageToken", page.nextToken());
        }
        return response;
    }

    private void validateMonitor(JsonNode input) {
        text(input, "MonitorName", 1024);
        String type = text(input, "MonitorType", 1024);
        if ("CUSTOM".equals(type)) {
            if (present(input, "MonitorDimension")) {
                throw invalid("CUSTOM monitors must not specify MonitorDimension.");
            }
            validateSpecification(object(input, "MonitorSpecification"), null);
            return;
        }
        if (!"DIMENSIONAL".equals(type)) {
            throw invalid("MonitorType must be DIMENSIONAL or CUSTOM.");
        }
        String dimension = text(input, "MonitorDimension", 1024);
        switch (dimension) {
            case "SERVICE", "LINKED_ACCOUNT" -> {
                if (present(input, "MonitorSpecification")) {
                    throw invalid("MonitorSpecification is not supported for this monitor dimension.");
                }
            }
            case "TAG", "COST_CATEGORY" -> validateSpecification(object(input, "MonitorSpecification"), dimension);
            default -> throw invalid("MonitorDimension must be SERVICE, LINKED_ACCOUNT, TAG, or COST_CATEGORY.");
        }
    }

    private void validateSpecification(ObjectNode specification, String dimension) {
        String root = singleRoot(specification, Set.of("Dimensions", "Tags", "CostCategories"));
        if (dimension != null && !("TAG".equals(dimension) ? "Tags" : "CostCategories").equals(root)) {
            throw invalid("MonitorSpecification must match MonitorDimension.");
        }
        ObjectNode values = object(specification, root);
        String key = text(values, "Key", "CostCategories".equals(root) ? 50 : 1024);
        if (key.isEmpty() || ("Dimensions".equals(root) && !"LINKED_ACCOUNT".equals(key))) {
            throw invalid("MonitorSpecification must specify a linked account, tag key, or cost category key.");
        }
        if (dimension != null) {
            if (present(values, "Values")) {
                throw invalid("AWS managed tag and cost category monitors specify a key without values.");
            }
        } else {
            List<String> selected = strings(values, "Values", "CostCategories".equals(root) ? 1 : 10, 1024);
            if (selected.isEmpty()) {
                throw invalid("CUSTOM monitors must specify at least one value.");
            }
        }
    }

    private void validateMonitorReferences(JsonNode input, String account) {
        for (String monitorArn : strings(input, "MonitorArnList", 502, 2048)) {
            existing(monitors, account, monitorArn, "UnknownMonitorException");
        }
    }

    private void validateSubscribers(JsonNode input) {
        String frequency = text(input, "Frequency", 1024);
        if (!Set.of("DAILY", "WEEKLY", "IMMEDIATE").contains(frequency)) {
            throw invalid("Frequency must be DAILY, WEEKLY, or IMMEDIATE.");
        }
        JsonNode recipients = input.path("Subscribers");
        int limit = "IMMEDIATE".equals(frequency) ? 1 : 10;
        if (!recipients.isArray() || recipients.isEmpty() || recipients.size() > limit) {
            throw invalid("Subscribers must contain between 1 and " + limit + " recipients for this frequency.");
        }
        for (JsonNode recipient : recipients) {
            String type = text(recipient, "Type", 1024);
            String expected = "IMMEDIATE".equals(frequency) ? "SNS" : "EMAIL";
            if (!expected.equals(type)) {
                throw invalid("Subscribers must use " + expected + " for this frequency.");
            }
            String address = text(recipient, "Address", 302);
            if (address.length() < 6 || !("SNS".equals(type) ? SNS : EMAIL).matcher(address).matches()) {
                throw invalid("Subscriber Address must be an email address or SNS topic ARN matching its Type.");
            }
            if (present(recipient, "Status")
                    && !Set.of("CONFIRMED", "DECLINED").contains(text(recipient, "Status", 1024))) {
                throw invalid("Subscriber Status must be CONFIRMED or DECLINED.");
            }
        }
    }

    private ObjectNode threshold(JsonNode input) {
        boolean legacy = present(input, "Threshold");
        boolean expression = present(input, "ThresholdExpression");
        if (legacy == expression) {
            throw invalid("Specify exactly one of Threshold or ThresholdExpression.");
        }
        if (expression) {
            ObjectNode value = object(input, "ThresholdExpression");
            validateThresholdExpression(value);
            return value.deepCopy();
        }
        JsonNode value = input.get("Threshold");
        if (!value.isNumber()) {
            throw invalid("Threshold must be a nonnegative number.");
        }
        BigDecimal amount = thresholdValue(value.asText());
        ObjectNode result = mapper.createObjectNode();
        ObjectNode dimensions = result.putObject("Dimensions");
        dimensions.put("Key", "ANOMALY_TOTAL_IMPACT_ABSOLUTE");
        dimensions.putArray("MatchOptions").add("GREATER_THAN_OR_EQUAL");
        dimensions.putArray("Values").add(amount.stripTrailingZeros().toPlainString());
        return result;
    }

    private void validateThresholdExpression(ObjectNode expression) {
        String root = singleRoot(expression, Set.of("And", "Or", "Dimensions"));
        if (!"Dimensions".equals(root)) {
            JsonNode children = expression.get(root);
            if (!children.isArray() || children.isEmpty()) {
                throw invalid(root + " must contain at least one expression.");
            }
            for (JsonNode child : children) {
                if (!(child instanceof ObjectNode object)) {
                    throw invalid("ThresholdExpression must contain expression objects.");
                }
                validateThresholdExpression(object);
            }
            return;
        }
        ObjectNode dimensions = object(expression, "Dimensions");
        if (!Set.of("ANOMALY_TOTAL_IMPACT_ABSOLUTE", "ANOMALY_TOTAL_IMPACT_PERCENTAGE")
                .contains(text(dimensions, "Key", 1024))) {
            throw invalid("ThresholdExpression must use an anomaly impact dimension.");
        }
        List<String> matchOptions = strings(dimensions, "MatchOptions", 1, 1024);
        if (!matchOptions.equals(List.of("GREATER_THAN_OR_EQUAL"))) {
            throw invalid("ThresholdExpression requires GREATER_THAN_OR_EQUAL.");
        }
        List<String> values = strings(dimensions, "Values", Integer.MAX_VALUE, 1024);
        if (values.isEmpty()) {
            throw invalid("ThresholdExpression requires a numeric value.");
        }
        for (String value : values) {
            thresholdValue(value);
        }
    }

    private BigDecimal thresholdValue(String value) {
        try {
            BigDecimal amount = new BigDecimal(value);
            if (amount.signum() < 0 || amount.compareTo(MAX_THRESHOLD) > 0) {
                throw invalid("Threshold values must be between 0 and 10000000000.");
            }
            return amount;
        } catch (NumberFormatException e) {
            throw invalid("Threshold values must be numeric.");
        }
    }

    private ObjectNode tags(JsonNode request, boolean required) {
        ObjectNode tags = mapper.createObjectNode();
        if (!required && !present(request, "ResourceTags")) {
            return tags;
        }
        JsonNode input = request.path("ResourceTags");
        if (!input.isArray() || input.size() > 200) {
            throw invalid("ResourceTags must be an array of at most 200 tags.");
        }
        for (JsonNode tag : input) {
            String key = tagText(text(tag, "Key", 128), true);
            String value = tagText(text(tag, "Value", 256), false);
            if (tags.has(key)) {
                throw invalid("Each resource tag key must be unique.");
            }
            tags.put(key, value);
        }
        checkTagCount(tags);
        return tags;
    }

    private String tagText(String value, boolean key) {
        String trimmed = value.strip();
        if (!TAG_TEXT.matcher(trimmed).matches()
                || (key && (trimmed.isEmpty() || trimmed.regionMatches(true, 0, "aws:", 0, 4)))) {
            throw invalid("Resource tags must use valid characters and must not use reserved AWS keys.");
        }
        return trimmed;
    }

    private void checkTagCount(ObjectNode tags) {
        if (tags.size() > 50) {
            throw new AwsException("TooManyTagsException", "A resource may have at most 50 user tags.", 400);
        }
    }

    private AccountAwareStorageBackend<ObjectNode> taggedStorage(String arn) {
        String account = monitors.accountId();
        if (monitors.getForAccount(account, arn).isPresent()) {
            return monitors;
        }
        if (subscriptions.getForAccount(account, arn).isPresent()) {
            return subscriptions;
        }
        throw new AwsException("ResourceNotFoundException", "The specified resource does not exist for this account.", 400);
    }

    private ObjectNode existing(AccountAwareStorageBackend<ObjectNode> storage, String account,
                                String arn, String errorCode) {
        return storage.getForAccount(account, arn)
                .orElseThrow(() -> new AwsException(errorCode, "The specified resource does not exist for this account.", 400));
    }

    private ObjectNode resource(ObjectNode definition, ObjectNode tags) {
        ObjectNode resource = mapper.createObjectNode();
        resource.set("definition", definition);
        resource.set("tags", tags);
        return resource;
    }

    private ObjectNode fields(ObjectNode input, String... fields) {
        ObjectNode result = mapper.createObjectNode();
        for (String field : fields) {
            if (present(input, field)) {
                result.set(field, input.get(field).deepCopy());
            }
        }
        return result;
    }

    private String arn(String account, String type) {
        return AwsArnUtils.Arn.of("ce", "", account, type + "/" + UUID.randomUUID()).toString();
    }

    private boolean contains(JsonNode values, String value) {
        for (JsonNode item : values) {
            if (value.equals(item.textValue())) {
                return true;
            }
        }
        return false;
    }

    private static ObjectNode object(JsonNode parent, String name) {
        if (parent != null && parent.get(name) instanceof ObjectNode object) {
            return object;
        }
        throw invalid(name + " must be an object.");
    }

    private static String text(JsonNode parent, String name, int maximum) {
        JsonNode value = parent == null ? null : parent.get(name);
        if (value == null || !value.isTextual() || value.textValue().length() > maximum) {
            throw invalid(name + " must be a string of at most " + maximum + " characters.");
        }
        return value.textValue();
    }

    private static List<String> strings(JsonNode parent, String name, int maximum, int stringMaximum) {
        JsonNode value = parent.path(name);
        if (!value.isArray() || value.size() > maximum) {
            throw invalid(name + " must be an array of at most " + maximum + " strings.");
        }
        List<String> result = new ArrayList<>();
        for (JsonNode element : value) {
            if (!element.isTextual() || element.textValue().length() > stringMaximum) {
                throw invalid(name + " must contain strings of at most " + stringMaximum + " characters.");
            }
            result.add(element.textValue());
        }
        return result;
    }

    private static String singleRoot(ObjectNode expression, Set<String> supported) {
        Iterator<String> fields = expression.fieldNames();
        if (expression.size() != 1 || !fields.hasNext()) {
            throw invalid("An expression must contain exactly one root operator.");
        }
        String root = fields.next();
        if (!supported.contains(root)) {
            throw invalid("Unsupported expression operator: " + root);
        }
        return root;
    }

    private static boolean present(JsonNode node, String field) {
        return node != null && node.hasNonNull(field);
    }

    private static AwsException invalid(String message) {
        return new AwsException("ValidationException", message, 400);
    }
}
