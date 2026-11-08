package io.floci.aws.services.lambda.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import io.quarkus.runtime.annotations.RegisterForReflection;

/**
 * AWS Lambda Event Source Mapping scaling configuration.
 *
 * <p>Currently carries only {@code MaximumConcurrency}, the SQS-only cap on
 * how many Lambda invocations an ESM may run in parallel. The rest of the
 * AWS schema (none today) can extend this class as needed.
 *
 * <p>Wire shape:
 * <pre>{@code
 * "ScalingConfig": { "MaximumConcurrency": 5 }
 * }</pre>
 */
@RegisterForReflection
@JsonIgnoreProperties(ignoreUnknown = true)
public class ScalingConfig {

    private Integer maximumConcurrency;

    public ScalingConfig() {
    }

    public ScalingConfig(Integer maximumConcurrency) {
        this.maximumConcurrency = maximumConcurrency;
    }

    public Integer getMaximumConcurrency() {
        return maximumConcurrency;
    }

    public void setMaximumConcurrency(Integer maximumConcurrency) {
        this.maximumConcurrency = maximumConcurrency;
    }
}
