package io.floci.aws.services.s3;

import io.floci.aws.core.common.AwsException;

final class S3PreconditionFailedException extends AwsException {
    static final String MESSAGE = "At least one of the pre-conditions you specified did not hold";

    private final String condition;

    S3PreconditionFailedException(String condition) {
        super("PreconditionFailed", MESSAGE, 412);
        this.condition = condition;
    }

    String condition() {
        return condition;
    }
}
