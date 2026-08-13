package io.github.hectorvent.floci.services.swf;

import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.services.swf.model.SwfConstants;

/**
 * Factory for SWF faults.
 *
 * <p>SWF answers every modeled fault with HTTP 400 and a namespaced {@code __type}
 * of {@code com.amazonaws.swf.base.model#<Fault>} (validation failures use the
 * Coral namespace instead). The AWS SDKs key on the part after {@code #}, so the
 * namespace must be present for the response to deserialize into the typed
 * exception the caller catches.
 */
final class SwfFaults {

    private SwfFaults() {
    }

    static AwsException unknownDomain(String domain) {
        return fault(SwfConstants.UNKNOWN_RESOURCE, "Unknown domain: " + domain);
    }

    static AwsException unknownExecution(String workflowId, String runId) {
        return fault(SwfConstants.UNKNOWN_RESOURCE,
                "Unknown execution, workflowId = " + workflowId + ", runId = " + runId);
    }

    static AwsException unknownExecution(String runId) {
        return fault(SwfConstants.UNKNOWN_RESOURCE, "Unknown execution: WorkflowExecution=[runId=" + runId + "]");
    }

    static AwsException unknownWorkflowType(String name, String version) {
        return fault(SwfConstants.UNKNOWN_RESOURCE,
                "Unknown type: WorkflowType=[name=" + name + ", version=" + version + "]");
    }

    static AwsException unknownActivityType(String name, String version) {
        return fault(SwfConstants.UNKNOWN_RESOURCE,
                "Unknown type: ActivityType=[name=" + name + ", version=" + version + "]");
    }

    static AwsException unknownTaskToken() {
        return fault(SwfConstants.UNKNOWN_RESOURCE, "Unknown or expired task token");
    }

    /**
     * Raised when an activity task token resolves but the task is no longer open (completed,
     * failed, canceled, or timed out). The live service names the scheduled event rather
     * than reporting a bad token, since the token itself was valid.
     */
    static AwsException unknownActivity(long scheduledEventId) {
        return fault(SwfConstants.UNKNOWN_RESOURCE,
                "Unknown activity, scheduledEventId = " + scheduledEventId);
    }

    static AwsException domainAlreadyExists(String domain) {
        return fault(SwfConstants.DOMAIN_ALREADY_EXISTS, domain);
    }

    static AwsException domainDeprecated(String domain) {
        return fault(SwfConstants.DOMAIN_DEPRECATED, domain);
    }

    static AwsException workflowTypeAlreadyExists(String name, String version) {
        return fault(SwfConstants.TYPE_ALREADY_EXISTS,
                "WorkflowType=[name=" + name + ", version=" + version + "]");
    }

    static AwsException activityTypeAlreadyExists(String name, String version) {
        return fault(SwfConstants.TYPE_ALREADY_EXISTS,
                "ActivityType=[name=" + name + ", version=" + version + "]");
    }

    static AwsException workflowTypeDeprecated(String name, String version) {
        return fault(SwfConstants.TYPE_DEPRECATED,
                "WorkflowType=[name=" + name + ", version=" + version + "]");
    }

    static AwsException activityTypeDeprecated(String name, String version) {
        return fault(SwfConstants.TYPE_DEPRECATED,
                "ActivityType=[name=" + name + ", version=" + version + "]");
    }

    /**
     * Raised by DeleteWorkflowType/DeleteActivityType before the type is deprecated. Unlike
     * the other type faults, the live service answers with prose rather than the type
     * descriptor, and uses the same wording for both workflow and activity types.
     */
    static AwsException typeNotDeprecated() {
        return fault(SwfConstants.TYPE_NOT_DEPRECATED,
                "The type is currently registered and cannot be deleted in its current state");
    }

    /**
     * The live service returns this fault with an empty message; the runId of the
     * already-open execution is not disclosed.
     */
    static AwsException executionAlreadyStarted() {
        return fault(SwfConstants.WORKFLOW_EXECUTION_ALREADY_STARTED, "");
    }

    static AwsException defaultUndefined(String field) {
        return fault(SwfConstants.DEFAULT_UNDEFINED, field);
    }

    /**
     * The live service rejects a corrupt {@code nextPageToken} with this exact message rather
     * than returning the first page.
     */
    static AwsException invalidPageToken() {
        return validation("Invalid token");
    }

    static AwsException validation(String message) {
        return new SwfException(SwfConstants.VALIDATION,
                SwfConstants.VALIDATION_NAMESPACE + SwfConstants.VALIDATION, message, 400);
    }

    static AwsException validationConstraint(String value, String field, String constraint) {
        return validation("1 validation error detected: Value '" + value + "' at '" + field
                + "' failed to satisfy constraint: " + constraint);
    }

    static AwsException missingRequired(String field) {
        return validation("1 validation error detected: Value null at '" + field
                + "' failed to satisfy constraint: Member must not be null");
    }

    static AwsException fault(String code, String message) {
        return new SwfException(code, SwfConstants.FAULT_NAMESPACE + code, message, 400);
    }
}
