package io.github.hectorvent.floci.services.bedrock.model;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import io.quarkus.runtime.annotations.RegisterForReflection;

import java.util.List;

@RegisterForReflection
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ListModelInvocationJobsResponse(
        @JsonProperty("invocationJobSummaries") List<ModelInvocationJobSummary> invocationJobSummaries,
        @JsonProperty("nextToken") String nextToken
) {}
