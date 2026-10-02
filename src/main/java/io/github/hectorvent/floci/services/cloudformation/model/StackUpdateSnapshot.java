package io.github.hectorvent.floci.services.cloudformation.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import io.quarkus.runtime.annotations.RegisterForReflection;

import java.util.Map;

/**
 * A stack's state from before an update, which the rollback of that update restores. A rollback
 * that fails keeps it on the stack, so ContinueUpdateRollback can run the rollback again after the
 * update that took it has returned, or after a restart.
 */
@RegisterForReflection
@JsonIgnoreProperties(ignoreUnknown = true)
public record StackUpdateSnapshot(
        String templateBody,
        String originalTemplateBody,
        Map<String, String> parameters,
        Map<String, String> resolvedParameters,
        Map<String, String> outputs,
        Map<String, String> exports,
        Map<String, String> outputExportNames,
        Map<String, StackResource> resources) {
}
