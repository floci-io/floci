package io.github.hectorvent.floci.core.common.docker;

import com.github.dockerjava.transport.NamedPipeSocket;

import java.io.IOException;
import java.net.SocketAddress;
import java.nio.channels.AsynchronousFileChannel;
import java.nio.file.AccessDeniedException;
import java.nio.file.FileSystemException;
import java.nio.file.NoSuchFileException;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;

/**
 * A docker-java {@link NamedPipeSocket} that fails at once when the pipe does not exist.
 *
 * <p>docker-java's {@code connect} retries the open on every {@link FileSystemException} until the
 * connect timeout passes, waiting for a free pipe instance in between. That wait is right for a busy
 * pipe ({@code ERROR_PIPE_BUSY}), but a missing pipe, Docker not running, is never going to appear
 * within the timeout: on a Windows host without Docker every Docker call spent the full 30 seconds in
 * that loop, and startup never got as far as answering {@code /_floci/health}. This socket opens the
 * pipe once first. A missing pipe, or one this process may not open, fails with that exception, which
 * {@link DockerRetry} does not retry; any other failure, a busy pipe, falls through to docker-java's
 * wait loop. When the probe opens the pipe it is closed again and docker-java opens its own channel,
 * since that channel is private to {@code NamedPipeSocket}.
 */
final class FailFastNamedPipeSocket extends NamedPipeSocket {

    private final String pipeName;

    FailFastNamedPipeSocket(String pipeName) {
        super(pipeName);
        this.pipeName = pipeName;
    }

    @Override
    public void connect(SocketAddress endpoint, int timeout) throws IOException {
        try (AsynchronousFileChannel probe = AsynchronousFileChannel.open(
                Paths.get(pipeName), StandardOpenOption.READ, StandardOpenOption.WRITE)) {
            // The pipe exists and had a free instance; docker-java reopens it below.
        } catch (NoSuchFileException | AccessDeniedException e) {
            throw e;
        } catch (FileSystemException expected) {
            // A busy pipe, no free instance yet: docker-java's loop below waits for one, and fails
            // with this same exception if none frees up within the timeout.
        }
        super.connect(endpoint, timeout);
    }
}
