package io.github.hectorvent.floci.services.lambdaweb.model;

import io.quarkus.runtime.annotations.RegisterForReflection;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

/** A Lambda Web Function with its revisions and endpoints. The function is the stored aggregate. */
@RegisterForReflection
public final class WebFunction {

    public String functionName;
    public String functionArn;
    public Instant createdAt;
    public Instant updatedAt;
    public Map<String, String> tags = new LinkedHashMap<>();
    public String policy;
    public String policyRevisionId;
    public Map<String, WebFunctionRevision> revisions = new LinkedHashMap<>();
    public Map<String, WebFunctionEndpoint> endpoints = new LinkedHashMap<>();
}
