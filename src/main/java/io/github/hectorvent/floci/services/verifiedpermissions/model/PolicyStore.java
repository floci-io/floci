package io.github.hectorvent.floci.services.verifiedpermissions.model;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

public record PolicyStore(
        String policyStoreId,
        String arn,
        String validationMode,
        String deletionProtection,
        String description,
        String encryptionKeyArn,
        Map<String, String> encryptionContext,
        Map<String, String> tags,
        String schema,
        Instant schemaCreatedDate,
        Instant schemaLastUpdatedDate,
        Instant createdDate,
        Instant lastUpdatedDate) {

    public PolicyStore {
        encryptionContext = encryptionContext == null ? new LinkedHashMap<>() : new LinkedHashMap<>(encryptionContext);
        tags = tags == null ? new LinkedHashMap<>() : new LinkedHashMap<>(tags);
    }

    public PolicyStore withSchema(String nextSchema, Instant created, Instant updated) {
        return new PolicyStore(policyStoreId, arn, validationMode, deletionProtection, description,
                encryptionKeyArn, encryptionContext, tags, nextSchema, created, updated, createdDate, updated);
    }

    public PolicyStore withSettings(String mode, String deletion, String nextDescription, Instant updated) {
        return new PolicyStore(policyStoreId, arn, mode, deletion, nextDescription, encryptionKeyArn, encryptionContext, tags,
                schema, schemaCreatedDate, schemaLastUpdatedDate, createdDate, updated);
    }

    public PolicyStore withTags(Map<String, String> nextTags, Instant updated) {
        return new PolicyStore(policyStoreId, arn, validationMode, deletionProtection, description,
                encryptionKeyArn, encryptionContext, nextTags, schema, schemaCreatedDate, schemaLastUpdatedDate, createdDate, updated);
    }
}
