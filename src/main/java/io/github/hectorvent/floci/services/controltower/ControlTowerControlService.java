package io.github.hectorvent.floci.services.controltower;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.core.storage.StorageBackend;
import io.github.hectorvent.floci.core.storage.StorageFactory;
import io.github.hectorvent.floci.services.controltower.model.EnabledControl;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

@ApplicationScoped
public class ControlTowerControlService {
    private static final String SUCCEEDED = "SUCCEEDED";
    private static final String IN_SYNC = "IN_SYNC";
    private static final int MAX_OPERATIONS_PER_SCOPE = 250;
    private static final Set<String> SCP_CONTROLS = Set.of(
            "AWS-GR_RESTRICT_ROOT_USER_ACCESS_KEYS",
            "AWS-GR_RESTRICT_ROOT_USER");

    private final StorageBackend<String, EnabledControl> controls;
    private final LinkedHashMap<String, ControlOperation> operations = new LinkedHashMap<>();

    @Inject
    public ControlTowerControlService(StorageFactory storageFactory) {
        this(storageFactory.create("controltower", "controltower-enabled-controls.json",
                new TypeReference<Map<String, EnabledControl>>() {}));
    }

    ControlTowerControlService(StorageBackend<String, EnabledControl> controls) {
        this.controls = controls;
    }

    public synchronized EnableResult enable(String accountId, String region, JsonNode request) {
        requireObject(request);
        String controlIdentifier = requireArn(request, "controlIdentifier");
        String targetIdentifier = requireArn(request, "targetIdentifier");
        JsonNode parameters = validateParameters(request.get("parameters"), false);
        Map<String, String> tags = validateTags(request.get("tags"));
        String key = key(region, targetIdentifier, controlIdentifier);
        if (controls.get(key).isPresent()) {
            throw new AwsException("ConflictException", "The control is already enabled on the specified target.", 409);
        }

        String operationId = UUID.randomUUID().toString();
        String arn = "arn:aws:controltower:" + region + ":" + accountId + ":enabledcontrol/" + shortId();
        EnabledControl control = new EnabledControl(arn, controlIdentifier, targetIdentifier,
                SUCCEEDED, IN_SYNC, operationId, parameters, tags);
        controls.put(key, control);
        record(accountId, region, operationId, "ENABLE_CONTROL", control);
        return new EnableResult(arn, operationId);
    }

    public synchronized ListResult list(String region, JsonNode request) {
        requireObject(request);
        String targetIdentifier = optionalArn(request, "targetIdentifier");
        int maxResults = maxResults(request);
        int offset = nextToken(request);
        List<EnabledControl> result = new ArrayList<>(controls.scan(key -> key.startsWith(region + "::")));
        if (targetIdentifier != null) {
            result.removeIf(control -> !targetIdentifier.equals(control.getTargetIdentifier()));
        }
        JsonNode filter = request.get("filter");
        if (filter != null && !filter.isNull()) {
            if (!filter.isObject()) throw validation("filter must be a JSON object.");
            Set<String> identifiers = stringSet(filter.get("controlIdentifiers"));
            Set<String> drift = stringSet(filter.get("driftStatuses"));
            if (!drift.isEmpty() && drift.stream().anyMatch(v -> !Set.of("IN_SYNC", "DRIFTED", "NOT_CHECKING", "UNKNOWN").contains(v))) {
                throw validation("driftStatuses contains an invalid value.");
            }
            result.removeIf(control -> (!identifiers.isEmpty() && !identifiers.contains(control.getControlIdentifier()))
                    || (!drift.isEmpty() && !drift.contains(control.getDriftStatus())));
        }
        result.sort(Comparator.comparing(EnabledControl::getArn));
        if (offset > result.size()) throw validation("nextToken is invalid.");
        int end = Math.min(result.size(), offset + maxResults);
        return new ListResult(new ArrayList<>(result.subList(offset, end)), end < result.size() ? String.valueOf(end) : null);
    }

    public synchronized EnabledControl get(String region, String identifier) {
        requireArnValue(identifier, "enabledControlIdentifier");
        return controls.scan(key -> key.startsWith(region + "::")).stream()
                .filter(control -> identifier.equals(control.getArn()))
                .findFirst()
                .orElseThrow(() -> notFound("The request references an enabled control that does not exist."));
    }

    public synchronized String update(String accountId, String region, JsonNode request) {
        requireObject(request);
        String identifier = requireArn(request, "enabledControlIdentifier");
        JsonNode parameters = validateParameters(request.get("parameters"), true);
        EnabledControl control = get(region, identifier);
        if (Objects.equals(control.getParameters(), parameters)) {
            throw validation("parameters must differ from the currently configured parameters.");
        }
        if ("DRIFTED".equals(control.getDriftStatus())) {
            throw new AwsException("ConflictException", "A drifted control must be reset instead of updated.", 409);
        }
        control.setParameters(parameters);
        String operationId = UUID.randomUUID().toString();
        control.setLastOperationIdentifier(operationId);
        controls.put(key(region, control.getTargetIdentifier(), control.getControlIdentifier()), control);
        record(accountId, region, operationId, "UPDATE_ENABLED_CONTROL", control);
        return operationId;
    }

    public synchronized String reset(String accountId, String region, String identifier) {
        EnabledControl control = get(region, identifier);
        if (isScp(control.getControlIdentifier())) {
            throw validation("ResetEnabledControl does not support controls implemented with service control policies.");
        }
        control.setDriftStatus(IN_SYNC);
        String operationId = UUID.randomUUID().toString();
        control.setLastOperationIdentifier(operationId);
        controls.put(key(region, control.getTargetIdentifier(), control.getControlIdentifier()), control);
        record(accountId, region, operationId, "RESET_ENABLED_CONTROL", control);
        return operationId;
    }

    public synchronized ControlOperation operation(String accountId, String region, String operationIdentifier) {
        if (operationIdentifier == null || !operationIdentifier.matches("[a-f0-9]{8}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{12}")) {
            throw validation("operationIdentifier must be a UUID.");
        }
        ControlOperation operation = operations.get(operationKey(accountId, region, operationIdentifier));
        if (operation == null) throw notFound("The control operation does not exist or is no longer available.");
        return operation;
    }

    private void record(String accountId, String region, String operationId, String type, EnabledControl control) {
        String scopePrefix = accountId + "::" + region + "::";
        operations.put(operationKey(accountId, region, operationId), new ControlOperation(
                operationId, type, SUCCEEDED, control.getControlIdentifier(), control.getArn(), control.getTargetIdentifier()));
        long inScope = operations.keySet().stream().filter(key -> key.startsWith(scopePrefix)).count();
        if (inScope > MAX_OPERATIONS_PER_SCOPE) {
            var iterator = operations.keySet().iterator();
            while (iterator.hasNext()) {
                if (iterator.next().startsWith(scopePrefix)) {
                    iterator.remove();
                    break;
                }
            }
        }
    }

    private static String key(String region, String target, String control) {
        return region + "::" + target + "::" + control;
    }

    private static String operationKey(String accountId, String region, String operationId) {
        return accountId + "::" + region + "::" + operationId;
    }

    private static String shortId() {
        return UUID.randomUUID().toString().replace("-", "").substring(0, 16);
    }

    private static boolean isScp(String controlIdentifier) {
        String id = controlIdentifier.substring(controlIdentifier.lastIndexOf('/') + 1);
        return SCP_CONTROLS.contains(id);
    }

    private static void requireObject(JsonNode request) {
        if (request == null || !request.isObject()) throw validation("Request body must be a JSON object.");
    }

    private static String requireArn(JsonNode request, String field) {
        JsonNode value = request.get(field);
        if (value == null || !value.isTextual()) throw validation(field + " must be a string.");
        return requireArnValue(value.textValue(), field);
    }

    private static String optionalArn(JsonNode request, String field) {
        JsonNode value = request.get(field);
        if (value == null || value.isNull()) return null;
        if (!value.isTextual()) throw validation(field + " must be a string.");
        return requireArnValue(value.textValue(), field);
    }

    private static String requireArnValue(String value, String field) {
        if (value == null || value.length() < 20 || value.length() > 2048 || !value.matches("^arn:aws[0-9a-zA-Z_\\-:\\/]+$")) {
            throw validation(field + " must be a valid ARN.");
        }
        return value;
    }

    private static JsonNode validateParameters(JsonNode parameters, boolean required) {
        if (parameters == null || parameters.isNull()) {
            if (required) throw validation("parameters is required.");
            return null;
        }
        if (!parameters.isArray()) throw validation("parameters must be an array.");
        for (JsonNode parameter : parameters) {
            if (!parameter.isObject() || !parameter.path("key").isTextual() || parameter.path("key").asText().isBlank()
                    || !parameter.has("value")) {
                throw validation("each parameter must contain a non-empty key and value.");
            }
        }
        return parameters.deepCopy();
    }

    private static Map<String, String> validateTags(JsonNode tags) {
        if (tags == null || tags.isNull()) return Map.of();
        if (!tags.isObject() || tags.size() > 200) throw validation("tags must be an object with at most 200 entries.");
        Map<String, String> result = new LinkedHashMap<>();
        tags.fields().forEachRemaining(entry -> {
            if (entry.getKey().isBlank() || entry.getKey().length() > 128 || !entry.getValue().isTextual()
                    || entry.getValue().textValue().length() > 256) {
                throw validation("tags contain an invalid key or value.");
            }
            result.put(entry.getKey(), entry.getValue().textValue());
        });
        return Map.copyOf(result);
    }

    private static Set<String> stringSet(JsonNode node) {
        if (node == null || node.isNull()) return Set.of();
        if (!node.isArray() || node.size() == 0) throw validation("filter values must be non-empty arrays.");
        Set<String> result = new java.util.HashSet<>();
        for (JsonNode item : node) {
            if (!item.isTextual() || item.asText().isBlank()) throw validation("filter values must be strings.");
            result.add(item.asText());
        }
        return result;
    }

    private static int maxResults(JsonNode request) {
        JsonNode value = request.get("maxResults");
        if (value == null || value.isNull()) return 100;
        if (!value.isInt() || value.intValue() < 1 || value.intValue() > 100) throw validation("maxResults must be between 1 and 100.");
        return value.intValue();
    }

    private static int nextToken(JsonNode request) {
        JsonNode value = request.get("nextToken");
        if (value == null || value.isNull()) return 0;
        if (!value.isTextual() || value.asText().isBlank()) throw validation("nextToken must be a non-empty string.");
        try {
            int offset = Integer.parseInt(value.asText());
            if (offset < 0) {
                throw validation("nextToken is invalid.");
            }
            return offset;
        } catch (NumberFormatException e) {
            throw validation("nextToken is invalid.");
        }
    }

    private static AwsException validation(String message) {
        return new AwsException("ValidationException", message, 400);
    }

    private static AwsException notFound(String message) {
        return new AwsException("ResourceNotFoundException", message, 404);
    }

    public record EnableResult(String arn, String operationIdentifier) {}
    public record ListResult(List<EnabledControl> controls, String nextToken) {}
    public record ControlOperation(String operationIdentifier, String operationType, String status,
                                   String controlIdentifier, String enabledControlIdentifier,
                                   String targetIdentifier) {}
}
