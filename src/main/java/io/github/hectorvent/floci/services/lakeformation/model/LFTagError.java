package io.github.hectorvent.floci.services.lakeformation.model;

import com.fasterxml.jackson.annotation.JsonInclude;
import io.quarkus.runtime.annotations.RegisterForReflection;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;
@RegisterForReflection
@JsonNaming(PropertyNamingStrategies.UpperCamelCaseStrategy.class)
@JsonInclude(JsonInclude.Include.NON_NULL)
public class LFTagError {
    private ErrorDetail error;
    @JsonProperty("LFTag")
    private LFTagPair lfTag;

    public ErrorDetail getError() {
        return error;
    }

    public void setError(ErrorDetail error) {
        this.error = error;
    }

    public LFTagPair getLfTag() {
        return lfTag;
    }

    public void setLfTag(LFTagPair lfTag) {
        this.lfTag = lfTag;
    }
}
