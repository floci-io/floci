package io.github.hectorvent.floci.services.cloudwatch.logs.filter;

/** A filter pattern that does not follow the CloudWatch Logs syntax; the message says where. */
public class FilterPatternException extends IllegalArgumentException {

    public FilterPatternException(String message) {
        super(message);
    }
}
