package io.github.hectorvent.floci.services.bedrock.model;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import io.quarkus.runtime.annotations.RegisterForReflection;

import java.util.List;

@RegisterForReflection
@JsonInclude(JsonInclude.Include.NON_NULL)
public record VpcConfig(
        @JsonProperty("securityGroupIds") List<String> securityGroupIds,
        @JsonProperty("subnetIds") List<String> subnetIds
) {}
