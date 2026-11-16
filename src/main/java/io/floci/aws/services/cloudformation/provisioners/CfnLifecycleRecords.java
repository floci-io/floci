package io.floci.aws.services.cloudformation.provisioners;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.floci.aws.services.cloudformation.model.StackResource;

import java.util.function.Consumer;

/** Cleanup records shared by resources whose physical name is only part of their deletion address. */
final class CfnLifecycleRecords {
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private CfnLifecycleRecords() {
    }

    static ObjectNode read(StackResource resource, String attribute, String invalid, String wrongShape) {
        String raw = resource.getAttributes().get(attribute);
        if (raw == null) {
            return null;
        }
        try {
            if (MAPPER.readTree(raw) instanceof ObjectNode object) {
                return object;
            }
            throw new IllegalStateException(wrongShape);
        } catch (JsonProcessingException failure) {
            throw new IllegalStateException(invalid, failure);
        }
    }

    static void setCleanup(StackResource resource, String attribute, ObjectNode address, boolean retainable) {
        resource.getAttributes().put(attribute,
                address.put("retainable", retainable).put("attempts", 0).toString());
    }

    static boolean retained(StackResource resource, JsonNode pending) {
        return pending.path("retainable").asBoolean() && "Retain".equals(resource.getUpdateReplacePolicy());
    }

    static void deletePending(StackResource resource, String attribute, ObjectNode pending,
                              boolean honorRetain, Consumer<ObjectNode> delete) {
        if (pending == null) {
            return;
        }
        if (!honorRetain || !retained(resource, pending)) {
            delete.accept(pending);
        }
        resource.getAttributes().remove(attribute);
    }

    static String cleanupPhysicalId(StackResource resource, ObjectNode pending) {
        return pending == null || retained(resource, pending) ? null : pending.path("name").asText();
    }

    static UpdateCleanupResult completePending(StackResource resource, String cleanupAttribute,
                                               String updateAttribute, ObjectNode pending,
                                               boolean honorRetain, Consumer<ObjectNode> delete) {
        if (pending == null) {
            return resource.getAttributes().containsKey(updateAttribute)
                    ? new UpdateCleanupResult(true, true, null, 0, null)
                    : UpdateCleanupResult.notApplicable();
        }
        String name = pending.path("name").asText();
        try {
            deletePending(resource, cleanupAttribute, pending, honorRetain, delete);
            return new UpdateCleanupResult(true, true, name, 0, null);
        } catch (RuntimeException failure) {
            int attempts = pending.path("attempts").asInt() + 1;
            pending.put("attempts", attempts);
            resource.getAttributes().put(cleanupAttribute, pending.toString());
            return new UpdateCleanupResult(true, false, name, attempts, failure.getMessage());
        }
    }
}
