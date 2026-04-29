package io.github.hectorvent.floci.services.glue.schemaregistry.model;

import com.fasterxml.jackson.annotation.JsonFormat;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import io.quarkus.runtime.annotations.RegisterForReflection;

import java.time.Instant;
import java.util.List;

@RegisterForReflection
@JsonInclude(JsonInclude.Include.NON_NULL)
public class MetadataInfo {

    @JsonProperty("MetadataValue")
    private String metadataValue;

    @JsonProperty("CreatedTime")
    @JsonFormat(shape = JsonFormat.Shape.NUMBER)
    private Instant createdTime;

    @JsonProperty("OtherMetadataValueList")
    private List<OtherMetadataValueListItem> otherMetadataValueList;

    public MetadataInfo() {}

    public MetadataInfo(String metadataValue, Instant createdTime) {
        this.metadataValue = metadataValue;
        this.createdTime = createdTime;
    }

    public String getMetadataValue() { return metadataValue; }
    public void setMetadataValue(String metadataValue) { this.metadataValue = metadataValue; }

    public Instant getCreatedTime() { return createdTime; }
    public void setCreatedTime(Instant createdTime) { this.createdTime = createdTime; }

    public List<OtherMetadataValueListItem> getOtherMetadataValueList() { return otherMetadataValueList; }
    public void setOtherMetadataValueList(List<OtherMetadataValueListItem> otherMetadataValueList) {
        this.otherMetadataValueList = otherMetadataValueList;
    }

    @RegisterForReflection
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class OtherMetadataValueListItem {
        @JsonProperty("MetadataValue")
        private String metadataValue;

        @JsonProperty("CreatedTime")
        @JsonFormat(shape = JsonFormat.Shape.NUMBER)
        private Instant createdTime;

        public OtherMetadataValueListItem() {}

        public OtherMetadataValueListItem(String metadataValue, Instant createdTime) {
            this.metadataValue = metadataValue;
            this.createdTime = createdTime;
        }

        public String getMetadataValue() { return metadataValue; }
        public void setMetadataValue(String metadataValue) { this.metadataValue = metadataValue; }

        public Instant getCreatedTime() { return createdTime; }
        public void setCreatedTime(Instant createdTime) { this.createdTime = createdTime; }
    }
}
