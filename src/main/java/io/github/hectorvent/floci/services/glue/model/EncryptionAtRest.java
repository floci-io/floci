package io.github.hectorvent.floci.services.glue.model;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import io.quarkus.runtime.annotations.RegisterForReflection;

/** The {@code EncryptionAtRest} block of the Data Catalog encryption settings. */
@RegisterForReflection
@JsonInclude(JsonInclude.Include.NON_NULL)
public class EncryptionAtRest {
    @JsonProperty("CatalogEncryptionMode")
    private String catalogEncryptionMode;

    @JsonProperty("SseAwsKmsKeyId")
    private String sseAwsKmsKeyId;

    @JsonProperty("CatalogEncryptionServiceRole")
    private String catalogEncryptionServiceRole;

    public String getCatalogEncryptionMode() { return catalogEncryptionMode; }
    public void setCatalogEncryptionMode(String catalogEncryptionMode) { this.catalogEncryptionMode = catalogEncryptionMode; }

    public String getSseAwsKmsKeyId() { return sseAwsKmsKeyId; }
    public void setSseAwsKmsKeyId(String sseAwsKmsKeyId) { this.sseAwsKmsKeyId = sseAwsKmsKeyId; }

    public String getCatalogEncryptionServiceRole() { return catalogEncryptionServiceRole; }
    public void setCatalogEncryptionServiceRole(String catalogEncryptionServiceRole) {
        this.catalogEncryptionServiceRole = catalogEncryptionServiceRole;
    }
}
