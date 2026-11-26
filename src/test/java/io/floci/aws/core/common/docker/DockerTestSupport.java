package io.floci.aws.core.common.docker;

final class DockerTestSupport {

    private DockerTestSupport() {
    }

    static Throwable rootCause(Throwable error) {
        Throwable cause = error;
        while (cause.getCause() != null && cause.getCause() != cause) {
            cause = cause.getCause();
        }
        return cause;
    }

}
