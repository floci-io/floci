package io.github.hectorvent.floci.services.glue.model;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import io.quarkus.runtime.annotations.RegisterForReflection;

@RegisterForReflection
@JsonInclude(JsonInclude.Include.NON_NULL)
public class EventBatchingCondition {
    @JsonProperty("BatchSize")
    private Integer batchSize;

    @JsonProperty("BatchWindow")
    private Integer batchWindow;

    public EventBatchingCondition() {}

    public Integer getBatchSize() { return batchSize; }
    public void setBatchSize(Integer batchSize) { this.batchSize = batchSize; }

    public Integer getBatchWindow() { return batchWindow; }
    public void setBatchWindow(Integer batchWindow) { this.batchWindow = batchWindow; }
}
