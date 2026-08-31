package io.github.hectorvent.floci.services.bedrock.model;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import io.quarkus.runtime.annotations.RegisterForReflection;

@RegisterForReflection
@JsonInclude(JsonInclude.Include.NON_NULL)
public record BatchManifest(
        @JsonProperty("totalRecordCount") long totalRecordCount,
        @JsonProperty("processedRecordCount") long processedRecordCount,
        @JsonProperty("successRecordCount") long successRecordCount,
        @JsonProperty("errorRecordCount") long errorRecordCount,
        @JsonProperty("inputTokenCount") Long inputTokenCount,
        @JsonProperty("outputTokenCount") Long outputTokenCount
) {
    public BatchManifest(long totalRecordCount,
                         long processedRecordCount,
                         long successRecordCount,
                         long errorRecordCount) {
        this(totalRecordCount, processedRecordCount, successRecordCount, errorRecordCount, null, null);
    }
}
