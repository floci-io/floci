package io.github.hectorvent.floci.services.lambdaweb.model;

import io.quarkus.runtime.annotations.RegisterForReflection;

import java.time.Instant;
import java.util.Map;

/** A snapshot of a Web Function's code and configuration. The wire nesting is rebuilt when rendered. */
@RegisterForReflection
public final class WebFunctionRevision {

    public String revisionId;
    public String revisionArn;
    public String functionArn;
    public String description;
    public String kmsKeyArn;
    public BuildConfig buildConfig = new BuildConfig();
    public ServiceConfig serviceConfig = new ServiceConfig();
    public Instant createdAt;
    /** The stateReason of a Failed revision. */
    public String failureReason;
    /** The object version the code was read from, also when the request named none. */
    public String codeVersionId;

    @RegisterForReflection
    public static final class BuildConfig {
        public String bucket;
        public String key;
        public String versionId;
        public String runtime;
    }

    @RegisterForReflection
    public static final class ServiceConfig {
        public String executionRoleArn;
        public int timeoutSeconds = 30;
        public String egressNetworkConnectorArn;
        public int maxConcurrencyPerEnvironment = 64;
        public Map<String, String> environmentVariables;
        public String logGroup;
        public String applicationLogLevel = "INFO";
        public String systemLogLevel = "INFO";
    }
}
