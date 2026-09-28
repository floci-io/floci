package io.github.hectorvent.floci.services.ce;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.core.storage.AccountAwareStorageBackend;
import io.github.hectorvent.floci.core.storage.PersistentStorage;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.function.Executable;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CostAnomalyServiceTest {

    private static final String ACCOUNT = "111111111111";
    private final ObjectMapper mapper = new ObjectMapper();
    private CostAnomalyService service;

    @BeforeEach
    void setUp() {
        service = new CostAnomalyService(AccountAwareStorageBackend.inMemory(ACCOUNT),
                AccountAwareStorageBackend.inMemory(ACCOUNT), mapper);
    }

    @Test
    void definitionsAreCopiedAndCustomMonitorNamesDoNotActAsIdempotencyKeys() {
        ObjectNode request = customMonitor("same-name");
        String first = service.createMonitor(request).path("MonitorArn").asText();
        ((ObjectNode) request.get("AnomalyMonitor")).put("MonitorName", "changed-input");
        String second = service.createMonitor(customMonitor("same-name")).path("MonitorArn").asText();
        assertNotEquals(first, second);
        assertEquals("same-name", monitor(first).path("MonitorName").asText());
        assertFalse(monitor(first).has("LastEvaluatedDate"));

        ObjectNode returned = (ObjectNode) monitor(first);
        returned.put("MonitorName", "changed-output");
        assertEquals("same-name", monitor(first).path("MonitorName").asText());
    }

    @Test
    void dimensionalMonitorQuotaDoesNotPreventCustomMonitors() {
        ObjectNode request = mapper.valueToTree(Map.of("AnomalyMonitor", Map.of(
                "MonitorName", "services", "MonitorType", "DIMENSIONAL", "MonitorDimension", "SERVICE")));
        service.createMonitor(request);
        assertError("LimitExceededException", () -> service.createMonitor(request));
        service.createMonitor(customMonitor("custom"));
        assertEquals(2, service.getMonitors(mapper.createObjectNode()).path("AnomalyMonitors").size());
    }

    @Test
    void tagDimensionAcceptsItsKeySpecificationAndRejectsMissingSpecification() {
        ObjectNode request = mapper.valueToTree(Map.of("AnomalyMonitor", Map.of(
                "MonitorName", "tag-costs", "MonitorType", "DIMENSIONAL", "MonitorDimension", "TAG",
                "MonitorSpecification", Map.of("Tags", Map.of("Key", "Environment")))));
        String arn = service.createMonitor(request).path("MonitorArn").asText();
        assertEquals("Environment", monitor(arn).path("MonitorSpecification").path("Tags").path("Key").asText());

        ((ObjectNode) request.get("AnomalyMonitor")).remove("MonitorSpecification");
        assertError("ValidationException", () -> service.createMonitor(request));
    }

    @Test
    void legacyThresholdIsNormalizedAndExpressionUpdatesReplaceIt() {
        String monitorArn = service.createMonitor(customMonitor("legacy-threshold")).path("MonitorArn").asText();
        ObjectNode request = subscriptionRequest(monitorArn);
        ObjectNode definition = (ObjectNode) request.get("AnomalySubscription");
        definition.remove("ThresholdExpression");
        definition.put("Threshold", 12.5);
        String arn = service.createSubscription(request).path("SubscriptionArn").asText();
        JsonNode stored = subscription(arn);
        assertEquals("12.5", stored.path("ThresholdExpression").path("Dimensions").path("Values").get(0).asText());
        assertEquals(ACCOUNT, stored.path("AccountId").asText());
        assertFalse(stored.has("Threshold"));
        assertFalse(stored.path("Subscribers").get(0).has("Status"));

        ObjectNode update = mapper.createObjectNode().put("SubscriptionArn", arn);
        update.set("ThresholdExpression", expression("ANOMALY_TOTAL_IMPACT_PERCENTAGE", "25"));
        service.updateSubscription(update);
        JsonNode changed = subscription(arn);
        assertEquals("ANOMALY_TOTAL_IMPACT_PERCENTAGE",
                changed.path("ThresholdExpression").path("Dimensions").path("Key").asText());
        assertFalse(changed.has("Threshold"));
        assertEquals("DAILY", changed.path("Frequency").asText());
    }

    @Test
    void invalidPartialSubscriptionUpdatesAreAtomic() {
        String monitorArn = service.createMonitor(customMonitor("atomic")).path("MonitorArn").asText();
        String arn = service.createSubscription(subscriptionRequest(monitorArn)).path("SubscriptionArn").asText();
        JsonNode before = subscription(arn).deepCopy();
        ObjectNode frequencyUpdate = mapper.createObjectNode()
                .put("SubscriptionArn", arn).put("SubscriptionName", "must-not-stick").put("Frequency", "IMMEDIATE");
        assertError("ValidationException", () -> service.updateSubscription(frequencyUpdate));
        assertEquals(before, subscription(arn));

        ObjectNode conflictingThresholds = mapper.createObjectNode().put("SubscriptionArn", arn).put("Threshold", 100);
        conflictingThresholds.set("ThresholdExpression", expression("ANOMALY_TOTAL_IMPACT_ABSOLUTE", "100"));
        assertError("ValidationException", () -> service.updateSubscription(conflictingThresholds));
        assertEquals(before, subscription(arn));
    }

    @Test
    void invalidThresholdExpressionsDoNotCreateSubscriptions() {
        String monitorArn = service.createMonitor(customMonitor("invalid-expression")).path("MonitorArn").asText();
        for (String value : List.of("-1", "10000000001", "NaN")) {
            ObjectNode request = subscriptionRequest(monitorArn);
            ((ObjectNode) request.get("AnomalySubscription"))
                    .set("ThresholdExpression", expression("ANOMALY_TOTAL_IMPACT_ABSOLUTE", value));
            assertError("ValidationException", () -> service.createSubscription(request));
        }
        ObjectNode request = subscriptionRequest(monitorArn);
        ObjectNode invalidExpression = expression("ANOMALY_TOTAL_IMPACT_ABSOLUTE", "10");
        invalidExpression.putArray("Or").add(expression("ANOMALY_TOTAL_IMPACT_PERCENTAGE", "10"));
        ((ObjectNode) request.get("AnomalySubscription")).set("ThresholdExpression", invalidExpression);
        assertError("ValidationException", () -> service.createSubscription(request));
        assertEquals(0, service.getSubscriptions(mapper.createObjectNode()).path("AnomalySubscriptions").size());
    }

    @Test
    void monitorDeletionDoesNotCascadeToSubscriptionOrPreventPartialUpdates() {
        String monitorArn = service.createMonitor(customMonitor("delete-first")).path("MonitorArn").asText();
        String arn = service.createSubscription(subscriptionRequest(monitorArn)).path("SubscriptionArn").asText();
        service.deleteMonitor(mapper.createObjectNode().put("MonitorArn", monitorArn));
        service.updateSubscription(mapper.createObjectNode()
                .put("SubscriptionArn", arn).put("SubscriptionName", "retained"));
        assertEquals("retained", subscription(arn).path("SubscriptionName").asText());
        service.deleteSubscription(mapper.createObjectNode().put("SubscriptionArn", arn));
        assertEquals(0, service.getSubscriptions(mapper.createObjectNode()).path("AnomalySubscriptions").size());
    }

    @Test
    void tagQuotaFailurePreservesExistingTagsAndKeepsThemOutOfDefinitions() {
        ObjectNode request = customMonitor("tags");
        ArrayNode tags = request.putArray("ResourceTags");
        for (int index = 0; index < 50; index++) {
            tags.addObject().put("Key", "key-" + index).put("Value", "value");
        }
        String arn = service.createMonitor(request).path("MonitorArn").asText();
        ObjectNode resource = mapper.createObjectNode().put("ResourceArn", arn);
        ObjectNode update = resource.deepCopy();
        update.putArray("ResourceTags").addObject().put("Key", "extra").put("Value", "not-stored");
        assertError("TooManyTagsException", () -> service.tagResource(update));
        assertEquals(50, service.listTags(resource).path("ResourceTags").size());
        assertFalse(monitor(arn).has("ResourceTags"));
        assertFalse(monitor(arn).has("tags"));

        ObjectNode overwrite = resource.deepCopy();
        overwrite.putArray("ResourceTags").addObject().put("Key", "key-0").put("Value", "changed");
        service.tagResource(overwrite);
        assertEquals(50, service.listTags(resource).path("ResourceTags").size());
        service.deleteMonitor(mapper.createObjectNode().put("MonitorArn", arn));
        assertError("ResourceNotFoundException", () -> service.listTags(resource));
    }

    @Test
    void paginationHandlesLargePageSizesAndRejectsInvalidInputs() {
        for (int index = 0; index < 3; index++) {
            service.createMonitor(customMonitor("page-" + index));
        }
        ObjectNode first = service.getMonitors(mapper.createObjectNode().put("MaxResults", 1));
        ObjectNode rest = service.getMonitors(mapper.createObjectNode().put("MaxResults", Integer.MAX_VALUE)
                .put("NextPageToken", first.path("NextPageToken").asText()));
        assertEquals(2, rest.path("AnomalyMonitors").size());
        assertFalse(rest.has("NextPageToken"));
        assertError("ValidationException",
                () -> service.getMonitors(mapper.createObjectNode().put("MaxResults", 0)));
        assertError("InvalidNextTokenException",
                () -> service.getMonitors(mapper.createObjectNode().put("NextPageToken", "!not-base64")));
    }

    @Test
    void monitorsSubscriptionsAndTagsSurviveRestartAndReset(@TempDir Path directory) {
        CostAnomalyService original = persistentService(directory);
        ObjectNode request = customMonitor("persistent");
        request.putArray("ResourceTags").addObject().put("Key", "Owner").put("Value", "billing");
        String monitorArn = original.createMonitor(request).path("MonitorArn").asText();
        String subscriptionArn = original.createSubscription(subscriptionRequest(monitorArn))
                .path("SubscriptionArn").asText();
        original.updateMonitor(mapper.createObjectNode().put("MonitorArn", monitorArn).put("MonitorName", "saved"));

        CostAnomalyService restored = persistentService(directory);
        assertEquals("saved", restored.getMonitors(mapper.createObjectNode())
                .path("AnomalyMonitors").get(0).path("MonitorName").asText());
        assertEquals(subscriptionArn, restored.getSubscriptions(mapper.createObjectNode())
                .path("AnomalySubscriptions").get(0).path("SubscriptionArn").asText());
        assertEquals("billing", restored.listTags(mapper.createObjectNode().put("ResourceArn", monitorArn))
                .path("ResourceTags").get(0).path("Value").asText());
        restored.clear();
        CostAnomalyService afterReset = persistentService(directory);
        assertTrue(afterReset.getMonitors(mapper.createObjectNode()).path("AnomalyMonitors").isEmpty());
        assertTrue(afterReset.getSubscriptions(mapper.createObjectNode()).path("AnomalySubscriptions").isEmpty());
    }

    private CostAnomalyService persistentService(Path directory) {
        PersistentStorage<String, ObjectNode> monitors = new PersistentStorage<>(directory.resolve("monitors.json"),
                new TypeReference<Map<String, ObjectNode>>() {});
        PersistentStorage<String, ObjectNode> subscriptions = new PersistentStorage<>(
                directory.resolve("subscriptions.json"), new TypeReference<Map<String, ObjectNode>>() {});
        monitors.load();
        subscriptions.load();
        return new CostAnomalyService(new AccountAwareStorageBackend<>(monitors, null, ACCOUNT),
                new AccountAwareStorageBackend<>(subscriptions, null, ACCOUNT), mapper);
    }

    private ObjectNode customMonitor(String name) {
        return mapper.valueToTree(Map.of("AnomalyMonitor", Map.of("MonitorName", name, "MonitorType", "CUSTOM",
                "MonitorSpecification", Map.of("Dimensions", Map.of("Key", "LINKED_ACCOUNT",
                        "Values", List.of("222222222222"))))));
    }

    private ObjectNode subscriptionRequest(String monitorArn) {
        return mapper.valueToTree(Map.of("AnomalySubscription", Map.of(
                "SubscriptionName", "alerts", "Frequency", "DAILY", "MonitorArnList", List.of(monitorArn),
                "Subscribers", List.of(Map.of("Type", "EMAIL", "Address", "alerts@example.com")),
                "ThresholdExpression", expression("ANOMALY_TOTAL_IMPACT_ABSOLUTE", "100"))));
    }

    private ObjectNode expression(String dimension, String value) {
        return mapper.valueToTree(Map.of("Dimensions", Map.of(
                "Key", dimension, "Values", List.of(value), "MatchOptions", List.of("GREATER_THAN_OR_EQUAL"))));
    }

    private JsonNode monitor(String arn) {
        ObjectNode request = mapper.createObjectNode();
        request.putArray("MonitorArnList").add(arn);
        return service.getMonitors(request).path("AnomalyMonitors").get(0);
    }

    private JsonNode subscription(String arn) {
        ObjectNode request = mapper.createObjectNode();
        request.putArray("SubscriptionArnList").add(arn);
        return service.getSubscriptions(request).path("AnomalySubscriptions").get(0);
    }

    private static void assertError(String code, Executable operation) {
        AwsException exception = assertThrows(AwsException.class, operation);
        assertEquals(code, exception.getErrorCode());
        assertEquals(400, exception.getHttpStatus());
    }
}
