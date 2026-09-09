package io.github.hectorvent.floci.services.swf;

import io.github.hectorvent.floci.core.common.AwsException;

/**
 * An SWF fault, which carries two different spellings of its name.
 *
 * <p>The response body's {@code __type} is namespaced
 * ({@code com.amazonaws.swf.base.model#UnknownResourceFault}), while the
 * {@code x-amzn-query-error} header carries the bare fault name. botocore prefers
 * the header when both are present, so a namespaced value there makes the AWS CLI
 * print {@code com.amazonaws.swf.base.model#UnknownResourceFault} as the error code
 * where real SWF prints {@code UnknownResourceFault}. Keeping {@link #getErrorCode()}
 * bare and overriding {@link #jsonType()} to add the namespace reproduces the live
 * service on both surfaces.
 */
class SwfException extends AwsException {

    private final String jsonType;

    SwfException(String errorCode, String jsonType, String message, int httpStatus) {
        super(errorCode, message, httpStatus);
        this.jsonType = jsonType;
    }

    @Override
    public String jsonType() {
        return jsonType;
    }
}
