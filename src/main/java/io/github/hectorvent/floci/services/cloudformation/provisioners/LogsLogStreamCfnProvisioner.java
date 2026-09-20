package io.github.hectorvent.floci.services.cloudformation.provisioners;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.MissingNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.services.cloudformation.model.StackResource;
import io.github.hectorvent.floci.services.cloudwatch.logs.CloudWatchLogsService;
import jakarta.enterprise.context.ApplicationScoped;

import java.util.Set;

/** Log streams have a name-only Ref, but their lifecycle identity also includes the log group. */
@ApplicationScoped
public class LogsLogStreamCfnProvisioner implements CfnResourceProvisioner {

    private static final String TYPE = "AWS::Logs::LogStream";
    private static final String GROUP_ATTR = "__FlociLogStreamGroup";
    private static final String NAME_MODE_ATTR = "__FlociLogStreamNameMode";
    private static final String UPDATE_ATTR = "__FlociLogStreamUpdate";
    private static final String CLEANUP_ATTR = "__FlociLogStreamCleanup";
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final CloudWatchLogsService logs;

    public LogsLogStreamCfnProvisioner(CloudWatchLogsService logs) {
        this.logs = logs;
    }

    @Override
    public Set<String> resourceTypes() {
        return Set.of(TYPE);
    }

    @Override
    public void provision(StackResource resource, JsonNode properties, ProvisionContext ctx) {
        JsonNode resolved = ctx.engine().resolveNode(markNoValue(properties));
        String group = property(resolved, "LogGroupName", true);
        String explicitName = property(resolved, "LogStreamName", false);
        if (!group.matches("[.\\-_/#A-Za-z0-9]+")) {
            throw invalid("LogGroupName contains invalid characters");
        }
        if (explicitName != null && (explicitName.contains(":") || explicitName.contains("*"))) {
            throw invalid("LogStreamName cannot contain ':' or '*'");
        }

        // An earlier failed cleanup must not be forgotten when this update records a new one.
        deletePending(resource);
        Identity prior = ctx.isUpdate() ? identity(resource, ctx.priorPhysicalId()) : null;
        String priorMode = resource.getAttributes().get(NAME_MODE_ATTR);
        String name = explicitName != null ? explicitName
                : prior != null && "generated".equals(priorMode) ? prior.name()
                : ctx.generatePhysicalName(resource.getLogicalId(), 512, false);
        Identity target = new Identity(group, name);
        boolean replacement = prior != null && !prior.equals(target);

        // Set ownership only after creation succeeds. A duplicate belongs to another resource.
        if (prior == null || replacement) {
            logs.createLogStream(group, name, ctx.region());
        }
        if (prior != null) {
            ObjectNode snapshot = address(prior, ctx.region());
            snapshot.put("nameMode", priorMode);
            snapshot.put("replacement", replacement);
            resource.getAttributes().put(UPDATE_ATTR, snapshot.toString());
            if (replacement) {
                setCleanup(resource, prior, ctx.region(), true);
            }
        }
        setIdentity(resource, target, explicitName == null ? "generated" : "explicit");
        resource.getAttributes().put(CfnRollback.ROLLBACK_OWNED_ATTR, "true");
    }

    // Keep conditional omission distinct from an explicitly empty name during intrinsic resolution.
    private static JsonNode markNoValue(JsonNode node) {
        if (node == null) {
            return null;
        }
        if (node.isObject()) {
            if ("AWS::NoValue".equals(node.path("Ref").asText())) {
                return MissingNode.getInstance();
            }
            ObjectNode marked = MAPPER.createObjectNode();
            node.fields().forEachRemaining(entry -> marked.set(entry.getKey(), markNoValue(entry.getValue())));
            return marked;
        }
        if (node.isArray()) {
            ArrayNode marked = MAPPER.createArrayNode();
            node.forEach(item -> marked.add(markNoValue(item)));
            return marked;
        }
        return node;
    }

    private static String property(JsonNode properties, String key, boolean required) {
        JsonNode value = properties == null ? null : properties.get(key);
        if (value == null || value.isNull() || value.isMissingNode()) {
            if (required) {
                throw invalid(key + " is required");
            }
            return null;
        }
        if (!value.isTextual() || value.textValue().isEmpty() || value.textValue().length() > 512) {
            throw invalid(key + " must be a string between 1 and 512 characters");
        }
        return value.textValue();
    }

    private static AwsException invalid(String message) {
        return new AwsException("ValidationError", message, 400);
    }

    private record Identity(String group, String name) {}

    private static Identity identity(StackResource resource, String name) {
        String group = resource.getAttributes().get(GROUP_ATTR);
        if (group == null || name == null) {
            throw new IllegalStateException("Missing log stream identity for " + resource.getLogicalId());
        }
        return new Identity(group, name);
    }

    private static Identity identity(JsonNode address) {
        return new Identity(address.path("group").asText(), address.path("name").asText());
    }

    private static void setIdentity(StackResource resource, Identity identity, String mode) {
        resource.setPhysicalId(identity.name());
        resource.getAttributes().put(GROUP_ATTR, identity.group());
        resource.getAttributes().put(NAME_MODE_ATTR, mode);
    }

    private static ObjectNode address(Identity identity, String region) {
        return MAPPER.createObjectNode().put("group", identity.group()).put("name", identity.name())
                .put("region", region);
    }

    private static void setCleanup(StackResource resource, Identity identity, String region, boolean retainable) {
        ObjectNode cleanup = address(identity, region).put("retainable", retainable).put("attempts", 0);
        resource.getAttributes().put(CLEANUP_ATTR, cleanup.toString());
    }

    @Override
    public void delete(StackResource resource, String region) {
        deletePending(resource);
        if (resource.getPhysicalId() != null) {
            delete(identity(resource, resource.getPhysicalId()), region);
        }
        resource.getAttributes().remove(UPDATE_ATTR);
        resource.getAttributes().remove(CfnRollback.ROLLBACK_OWNED_ATTR);
    }

    private void delete(Identity identity, String region) {
        CfnDeletes.safeDelete("Log stream", identity.name(),
                () -> logs.deleteLogStream(identity.group(), identity.name(), region),
                "ResourceNotFoundException");
    }

    private void deletePending(StackResource resource) {
        ObjectNode cleanup = read(resource, CLEANUP_ATTR);
        if (cleanup != null) {
            delete(identity(cleanup), cleanup.path("region").asText());
            resource.getAttributes().remove(CLEANUP_ATTR);
        }
    }

    @Override
    public boolean rollbackUpdate(StackResource resource) {
        ObjectNode snapshot = read(resource, UPDATE_ATTR);
        if (snapshot == null) {
            return false;
        }
        Identity replacement = identity(resource, resource.getPhysicalId());
        Identity prior = identity(snapshot);
        boolean replaced = snapshot.path("replacement").asBoolean();
        // Restore the old address before a delete can fail; retain the new address for a retry.
        setIdentity(resource, prior, snapshot.path("nameMode").asText());
        resource.getAttributes().remove(UPDATE_ATTR);
        resource.getAttributes().remove(CLEANUP_ATTR);
        if (replaced) {
            setCleanup(resource, replacement, snapshot.path("region").asText(), false);
            deletePending(resource);
        }
        return true;
    }

    @Override
    public boolean hasPendingRollbackCleanup(StackResource resource) {
        return "true".equals(resource.getAttributes().get(CfnRollback.ROLLBACK_OWNED_ATTR))
                || resource.getAttributes().containsKey(CLEANUP_ATTR);
    }

    @Override
    public boolean hasReplacementUpdate(StackResource resource) {
        return resource.getAttributes().containsKey(CLEANUP_ATTR);
    }

    @Override
    public String updateCleanupPhysicalId(StackResource resource) {
        ObjectNode cleanup = read(resource, CLEANUP_ATTR);
        return cleanup == null || retained(resource, cleanup) ? null : cleanup.path("name").asText();
    }

    @Override
    public UpdateCleanupResult completeUpdate(StackResource resource) {
        ObjectNode cleanup = read(resource, CLEANUP_ATTR);
        if (cleanup == null) {
            return resource.getAttributes().containsKey(UPDATE_ATTR)
                    ? new UpdateCleanupResult(true, true, null, 0, null)
                    : UpdateCleanupResult.notApplicable();
        }
        String name = cleanup.path("name").asText();
        if (retained(resource, cleanup)) {
            resource.getAttributes().remove(CLEANUP_ATTR);
            return new UpdateCleanupResult(true, true, name, 0, null);
        }
        try {
            deletePending(resource);
            return new UpdateCleanupResult(true, true, name, 0, null);
        } catch (RuntimeException failure) {
            int attempts = cleanup.path("attempts").asInt() + 1;
            cleanup.put("attempts", attempts);
            resource.getAttributes().put(CLEANUP_ATTR, cleanup.toString());
            return new UpdateCleanupResult(true, false, name, attempts, failure.getMessage());
        }
    }

    private static boolean retained(StackResource resource, JsonNode cleanup) {
        return cleanup.path("retainable").asBoolean() && "Retain".equals(resource.getUpdateReplacePolicy());
    }

    @Override
    public void clearUpdate(StackResource resource) {
        resource.getAttributes().remove(UPDATE_ATTR);
        // Failed deletes stay addressable by a later update or DeleteStack.
    }

    private static ObjectNode read(StackResource resource, String attribute) {
        String raw = resource.getAttributes().get(attribute);
        if (raw == null) {
            return null;
        }
        try {
            JsonNode record = MAPPER.readTree(raw);
            if (record.isObject()) {
                return (ObjectNode) record;
            }
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Invalid log stream lifecycle record for " + resource.getLogicalId(), e);
        }
        throw new IllegalStateException("Invalid log stream lifecycle record for " + resource.getLogicalId());
    }
}
