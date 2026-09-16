package io.github.hectorvent.floci.services.ssoadmin.model;

import com.fasterxml.jackson.databind.JsonNode;
import io.quarkus.runtime.annotations.RegisterForReflection;

@RegisterForReflection
public record ApplicationGrant(String applicationArn, String grantType, JsonNode grant) {}
