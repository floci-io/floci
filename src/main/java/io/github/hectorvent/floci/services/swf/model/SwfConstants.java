package io.github.hectorvent.floci.services.swf.model;

import java.util.Set;

/**
 * Wire-level constants shared by the SWF service and handler.
 *
 * <p>SWF reports faults with a namespaced {@code __type} of
 * {@code com.amazonaws.swf.base.model#<Fault>} and HTTP 400, verified against
 * the live service. The AWS SDKs match on the suffix after {@code #}, so the
 * namespace has to be present for a fault to deserialize as its typed
 * exception rather than a generic {@code SwfException}.
 */
public final class SwfConstants {

    public static final String FAULT_NAMESPACE = "com.amazonaws.swf.base.model#";
    public static final String VALIDATION_NAMESPACE = "com.amazon.coral.validate#";

    /**
     * Decision types that close the execution. SWF requires one of these to be the last
     * decision in a batch and rejects the batch outright otherwise.
     */
    public static final Set<String> CLOSING_DECISIONS = Set.of(
            "CompleteWorkflowExecution",
            "FailWorkflowExecution",
            "CancelWorkflowExecution",
            "ContinueAsNewWorkflowExecution");

    /**
     * Every decision type the service accepts. A batch containing anything else is rejected
     * before any of it is applied, so this is the single source of truth for both the check
     * and the enum list the fault message quotes.
     */
    public static final Set<String> DECISION_TYPES = Set.of(
            "ScheduleActivityTask",
            "RequestCancelActivityTask",
            "CompleteWorkflowExecution",
            "FailWorkflowExecution",
            "CancelWorkflowExecution",
            "ContinueAsNewWorkflowExecution",
            "RecordMarker",
            "StartTimer",
            "CancelTimer",
            "SignalExternalWorkflowExecution",
            "RequestCancelExternalWorkflowExecution",
            "StartChildWorkflowExecution",
            "ScheduleLambdaFunction");

    /**
     * The enum list exactly as the live service renders it in a ValidationException.
     *
     * <p>This order is the service's, not the API model's — the two differ, and the service's
     * was stable across repeated calls, so it is what a byte-comparing client sees.
     */
    public static final String DECISION_TYPE_SET_MESSAGE =
            "[CompleteWorkflowExecution, StartTimer, RequestCancelExternalWorkflowExecution, "
                    + "SignalExternalWorkflowExecution, CancelTimer, RecordMarker, ScheduleActivityTask, "
                    + "ContinueAsNewWorkflowExecution, ScheduleLambdaFunction, FailWorkflowExecution, "
                    + "RequestCancelActivityTask, StartChildWorkflowExecution, CancelWorkflowExecution]";

    public static final String UNKNOWN_RESOURCE = "UnknownResourceFault";
    public static final String DOMAIN_ALREADY_EXISTS = "DomainAlreadyExistsFault";
    public static final String DOMAIN_DEPRECATED = "DomainDeprecatedFault";
    public static final String TYPE_ALREADY_EXISTS = "TypeAlreadyExistsFault";
    public static final String TYPE_DEPRECATED = "TypeDeprecatedFault";
    public static final String TYPE_NOT_DEPRECATED = "TypeNotDeprecatedFault";
    public static final String WORKFLOW_EXECUTION_ALREADY_STARTED = "WorkflowExecutionAlreadyStartedFault";
    public static final String DEFAULT_UNDEFINED = "DefaultUndefinedFault";
    public static final String LIMIT_EXCEEDED = "LimitExceededFault";
    public static final String OPERATION_NOT_PERMITTED = "OperationNotPermittedFault";
    public static final String TOO_MANY_TAGS = "TooManyTagsFault";
    public static final String VALIDATION = "ValidationException";

    public static final String STATUS_REGISTERED = "REGISTERED";
    public static final String STATUS_DEPRECATED = "DEPRECATED";

    public static final String EXECUTION_STATUS_OPEN = "OPEN";
    public static final String EXECUTION_STATUS_CLOSED = "CLOSED";

    public static final String CLOSE_STATUS_COMPLETED = "COMPLETED";
    public static final String CLOSE_STATUS_FAILED = "FAILED";
    public static final String CLOSE_STATUS_CANCELED = "CANCELED";
    public static final String CLOSE_STATUS_TERMINATED = "TERMINATED";
    public static final String CLOSE_STATUS_CONTINUED_AS_NEW = "CONTINUED_AS_NEW";
    public static final String CLOSE_STATUS_TIMED_OUT = "TIMED_OUT";

    public static final String CHILD_POLICY_TERMINATE = "TERMINATE";
    public static final String CHILD_POLICY_REQUEST_CANCEL = "REQUEST_CANCEL";
    public static final String CHILD_POLICY_ABANDON = "ABANDON";

    public static final String TIMEOUT_NONE = "NONE";

    private SwfConstants() {
    }
}
