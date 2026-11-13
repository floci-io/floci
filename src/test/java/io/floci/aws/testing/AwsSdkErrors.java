package io.floci.aws.testing;

import org.junit.jupiter.api.function.Executable;
import software.amazon.awssdk.awscore.exception.AwsServiceException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/** Assertions on the errors an AWS SDK client throws. */
public final class AwsSdkErrors {

    private AwsSdkErrors() {
    }

    /** Asserts that {@code call} fails with the AWS error {@code code} at HTTP {@code status}, and returns it. */
    public static AwsServiceException assertAwsError(String code, int status, Executable call) {
        AwsServiceException error = assertThrows(AwsServiceException.class, call);
        assertEquals(code, error.awsErrorDetails().errorCode());
        assertEquals(status, error.statusCode());
        return error;
    }
}
