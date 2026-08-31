package io.github.hectorvent.floci.services.swf;

/**
 * A Lambda invocation's outcome, reduced to what SWF records in history.
 *
 * @param payload       the function's response body, recorded as the LambdaFunctionCompleted
 *                      {@code result} or, on a handler error, the Failed {@code details}
 * @param functionError the Lambda {@code FunctionError} header ({@code Handled}/{@code Unhandled}),
 *                      or {@code null} when the invocation succeeded
 */
record LambdaInvocationResult(String payload, String functionError) {
}
