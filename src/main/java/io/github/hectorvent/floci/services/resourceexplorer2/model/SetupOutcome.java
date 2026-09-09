package io.github.hectorvent.floci.services.resourceexplorer2.model;

import io.quarkus.runtime.annotations.RegisterForReflection;

/**
 * The result of one step (index or view) of a Resource Explorer setup task in one AWS Region.
 *
 * <p>floci runs a setup task synchronously, so a step is finished the moment
 * {@code CreateResourceExplorerSetup} or {@code DeleteResourceExplorerSetup} returns and the
 * status is terminal — {@code SUCCEEDED} or {@code FAILED}. On a failure the AWS
 * {@code ErrorDetails} fields carry the same code and message the equivalent single-Region call
 * would have thrown, which is why one Region failing does not abandon the rest of the task.
 *
 * @param status      {@code SUCCEEDED} or {@code FAILED}
 * @param arn         the index or view ARN the step produced; null for a delete or a failure
 * @param errorCode   AWS error code when {@code status} is {@code FAILED}, else null
 * @param errorMessage AWS error message when {@code status} is {@code FAILED}, else null
 * @see <a href="https://docs.aws.amazon.com/resource-explorer/latest/apireference/API_GetResourceExplorerSetup.html">
 *     AWS API: GetResourceExplorerSetup</a>
 */
@RegisterForReflection
public record SetupOutcome(String status, String arn, String errorCode, String errorMessage) {

    public static final String SUCCEEDED = "SUCCEEDED";
    public static final String FAILED = "FAILED";

    public static SetupOutcome succeeded(String arn) {
        return new SetupOutcome(SUCCEEDED, arn, null, null);
    }

    public static SetupOutcome failed(String errorCode, String errorMessage) {
        return new SetupOutcome(FAILED, null, errorCode, errorMessage);
    }
}
