package io.github.hectorvent.floci.services.msk.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import io.quarkus.runtime.annotations.RegisterForReflection;

@RegisterForReflection
@JsonInclude(JsonInclude.Include.NON_NULL)
@JsonIgnoreProperties(ignoreUnknown = true)
public class StorageInfo {

    @JsonProperty("ebsStorageInfo")
    private EbsStorageInfo ebsStorageInfo;

    public StorageInfo() {}

    public EbsStorageInfo getEbsStorageInfo() { return ebsStorageInfo; }
    public void setEbsStorageInfo(EbsStorageInfo ebsStorageInfo) { this.ebsStorageInfo = ebsStorageInfo; }
}