package io.github.hectorvent.floci.services.msk.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import io.quarkus.runtime.annotations.RegisterForReflection;

@RegisterForReflection
@JsonInclude(JsonInclude.Include.NON_NULL)
@JsonIgnoreProperties(ignoreUnknown = true)
public class EncryptionAtRest {

    @JsonProperty("dataVolumeKMSKeyId")
    private String dataVolumeKMSKeyId;

    public EncryptionAtRest() {}

    public String getDataVolumeKMSKeyId() { return dataVolumeKMSKeyId; }
    public void setDataVolumeKMSKeyId(String dataVolumeKMSKeyId) { this.dataVolumeKMSKeyId = dataVolumeKMSKeyId; }
}