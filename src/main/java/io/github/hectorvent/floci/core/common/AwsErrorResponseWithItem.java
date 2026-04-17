package io.github.hectorvent.floci.core.common;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.JsonNode;
import io.quarkus.runtime.annotations.RegisterForReflection;

@RegisterForReflection
public record AwsErrorResponseWithItem(@JsonProperty("__type") String type, @JsonProperty("message") String message, @JsonProperty("Item") JsonNode item) {
}