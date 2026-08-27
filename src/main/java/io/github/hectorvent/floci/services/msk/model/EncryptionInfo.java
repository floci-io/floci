package io.github.hectorvent.floci.services.msk.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import io.quarkus.runtime.annotations.RegisterForReflection;

import java.util.List;

@RegisterForReflection
@JsonInclude(JsonInclude.Include.NON_NULL)
@JsonIgnoreProperties(ignoreUnknown = true)
public class EncryptionInfo {

    @JsonProperty("encryptionAtRest")
    private EncryptionAtRest encryptionAtRest;

    @JsonProperty("encryptionInTransit")
    private EncryptionInTransit encryptionInTransit;

    public EncryptionInfo() {}

    public EncryptionAtRest getEncryptionAtRest() { return encryptionAtRest; }
    public void setEncryptionAtRest(EncryptionAtRest encryptionAtRest) { this.encryptionAtRest = encryptionAtRest; }

    public EncryptionInTransit getEncryptionInTransit() { return encryptionInTransit; }
    public void setEncryptionInTransit(EncryptionInTransit encryptionInTransit) { this.encryptionInTransit = encryptionInTransit; }
}