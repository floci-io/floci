package io.github.hectorvent.floci.services.lambdaweb.model;

import io.quarkus.runtime.annotations.RegisterForReflection;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** A network entry point of a Web Function: a domain name and the revisions it routes to. */
@RegisterForReflection
public final class WebFunctionEndpoint {

    public String endpointName;
    public String endpointArn;
    public String functionArn;
    public String description;
    public String endpointType;
    public String domainName;
    public String authType;
    public String autoDeploymentMode;
    public List<RevisionWeight> revisionWeights = new ArrayList<>();
    public List<String> regions = new ArrayList<>();
    /** Clearing a config on AWS leaves an empty object, which the wire shows as {@code {}}. */
    public ScalingConfig scalingConfig;
    public ThrottleConfig throttleConfig;
    public String updateStatus;
    public Map<String, String> regionalDomainNames = new LinkedHashMap<>();
    public Instant createdAt;
    public Instant updatedAt;

    @RegisterForReflection
    public static final class RevisionWeight {
        public String revisionId;
        public int weight;
    }

    @RegisterForReflection
    public static final class ScalingConfig {
        public Integer maxEnvironments;
    }

    @RegisterForReflection
    public static final class ThrottleConfig {
        public Integer rateLimit;
    }
}
