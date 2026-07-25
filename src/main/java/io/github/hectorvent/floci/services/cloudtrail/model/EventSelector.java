package io.github.hectorvent.floci.services.cloudtrail.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import io.quarkus.runtime.annotations.RegisterForReflection;

import java.util.List;

@RegisterForReflection
@JsonInclude(JsonInclude.Include.NON_NULL)
@JsonIgnoreProperties(ignoreUnknown = true)
public record EventSelector(
        @JsonProperty("ReadWriteType") String readWriteType,
        @JsonProperty("IncludeManagementEvents") Boolean includeManagementEvents,
        @JsonProperty("DataResources") List<DataResource> dataResources,
        @JsonProperty("ExcludeManagementEventSources") List<String> excludeManagementEventSources) {
}
