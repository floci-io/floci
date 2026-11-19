package io.floci.aws.core.common.docker;

import com.github.dockerjava.transport.DockerHttpClient;

import java.io.IOException;

/**
 * Puts {@link DockerCircuitBreaker} in front of a Docker transport: while the breaker is open a call
 * fails at once with {@link DockerUnavailableException}; otherwise the call runs, an HTTP response of
 * any status tells the breaker the daemon is there, and a transport failure counts toward opening it.
 * It sits under {@link RetryingDockerHttpClient}, which never replays the fail-fast exception.
 */
final class CircuitBreakingDockerHttpClient implements DockerHttpClient {

    private final DockerHttpClient delegate;
    private final DockerCircuitBreaker breaker;

    CircuitBreakingDockerHttpClient(DockerHttpClient delegate, DockerCircuitBreaker breaker) {
        this.delegate = delegate;
        this.breaker = breaker;
    }

    @Override
    public Response execute(Request request) {
        if (breaker.isOpen()) {
            throw new RuntimeException(breaker.unavailable());
        }
        Response response;
        try {
            response = delegate.execute(request);
        } catch (RuntimeException e) {
            if (DockerRetry.isDaemonFailure(e)) {
                breaker.recordFailure(e);
            }
            throw e;
        }
        breaker.recordResponse();
        return response;
    }

    @Override
    public void close() throws IOException {
        delegate.close();
    }
}
