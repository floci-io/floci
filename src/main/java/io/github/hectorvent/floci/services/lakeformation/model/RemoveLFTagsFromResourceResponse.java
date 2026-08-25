package io.github.hectorvent.floci.services.lakeformation.model;

import com.fasterxml.jackson.annotation.JsonInclude;
import io.quarkus.runtime.annotations.RegisterForReflection;
import java.util.List;

import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;
@RegisterForReflection
@JsonNaming(PropertyNamingStrategies.UpperCamelCaseStrategy.class)
@JsonInclude(JsonInclude.Include.NON_NULL)
public class RemoveLFTagsFromResourceResponse {
    private List<LFTagError> failures;

    public List<LFTagError> getFailures() {
        return failures;
    }

    public void setFailures(List<LFTagError> failures) {
        this.failures = failures;
    }
}
