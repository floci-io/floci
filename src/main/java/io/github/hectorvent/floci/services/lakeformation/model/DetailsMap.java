package io.github.hectorvent.floci.services.lakeformation.model;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;
import io.quarkus.runtime.annotations.RegisterForReflection;

import java.util.List;
@RegisterForReflection
@JsonNaming(PropertyNamingStrategies.UpperCamelCaseStrategy.class)
@JsonInclude(JsonInclude.Include.NON_NULL)
public class DetailsMap {
    private List<String> resourceShare;

    public List<String> getResourceShare() {
        return resourceShare;
    }

    public void setResourceShare(List<String> resourceShare) {
        this.resourceShare = resourceShare;
    }
}
