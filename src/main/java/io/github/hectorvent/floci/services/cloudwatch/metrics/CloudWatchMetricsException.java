package io.github.hectorvent.floci.services.cloudwatch.metrics;

import io.github.hectorvent.floci.core.common.AwsException;

/**
 * A CloudWatch error whose Query code and JSON shape name differ.
 *
 * <p>CloudWatch is awsQueryCompatible: the Query {@code <Code>} and the
 * {@code x-amzn-query-error} header carry the {@code awsQueryError} code
 * ({@code MissingParameter}), while a JSON 1.0 or CBOR body's {@code __type} names the
 * model shape ({@code MissingRequiredParameterException}). The AWS SDK v2 picks the typed
 * exception from {@code __type}, so the code alone yields a generic {@code CloudWatchException}.
 * Keeping {@link #getErrorCode()} as the Query code and overriding {@link #jsonType()}
 * reproduces both surfaces.
 */
class CloudWatchMetricsException extends AwsException {

    private final String jsonType;

    private CloudWatchMetricsException(String errorCode, String jsonType, String message, int httpStatus) {
        super(errorCode, message, httpStatus);
        this.jsonType = jsonType;
    }

    static CloudWatchMetricsException missingParameter(String parameter) {
        return new CloudWatchMetricsException("MissingParameter", "MissingRequiredParameterException",
                "The parameter " + parameter + " is required.", 400);
    }

    static CloudWatchMetricsException invalidParameterValue(String message) {
        return new CloudWatchMetricsException("InvalidParameterValue", "InvalidParameterValueException", message, 400);
    }

    @Override
    public String jsonType() {
        return jsonType;
    }
}
