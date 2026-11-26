package io.floci.aws.core.common.docker;

import org.jboss.logging.Logger;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.Socket;
import java.net.SocketAddress;
import java.net.SocketException;
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.TimeUnit;

/**
 * A {@link Socket} whose bytes are the standard input and output of one {@code ssh <host> docker system dial-stdio}
 * process: the way the Docker CLI reaches a daemon named by an {@code ssh://} endpoint. Each connection runs its own
 * SSH session, so no local port or socket file is opened that another local user could connect to, and a connection
 * opened after the network drops starts a fresh session.
 *
 * <p>A process stream has no read timeout, so a virtual thread copies the process's output into a bounded queue and
 * a read waits on that queue for {@link #setSoTimeout(int)}, failing with {@link SocketTimeoutException} as a socket
 * does. httpclient5's stale-connection check, a read with a 1 ms timeout, works the same way.
 *
 * <p>Starting the process is not yet a connection: the SSH handshake and login still lie ahead, and a request that
 * disables its response timeout, such as a log follow or a container wait, would wait on a stalled handshake forever.
 * The session runs with {@code LogLevel=VERBOSE}, so {@code ssh} reports a finished login on its error output
 * ({@link #AUTHENTICATED}). Until then, or until the daemon's first byte, a read waits no longer than the connect
 * timeout. Once the session is up, only the read timeout applies, so an upload that outlasts the connect timeout is
 * not cut short.
 */
final class SshStdioSocket extends Socket {

    private static final Logger LOG = Logger.getLogger(SshStdioSocket.class);

    private static final byte[] END_OF_STREAM = new byte[0];
    private static final int CHUNK_SIZE = 16 * 1024;
    private static final int MAX_STDERR_CHARS = 2048;
    static final int DEFAULT_CONNECT_TIMEOUT_MILLIS = 30_000;
    /** What {@code ssh} at {@code LogLevel=VERBOSE} writes once the login succeeded. */
    static final String AUTHENTICATED = "Authenticated to ";
    private static final long CONNECTING_POLL_NANOS = TimeUnit.MILLISECONDS.toNanos(100);

    private final Process process;
    private final BlockingQueue<byte[]> chunks = new ArrayBlockingQueue<>(64);
    private final StringBuilder stderr = new StringBuilder();
    private final InputStream input = new QueueInputStream();
    private final OutputStream output;
    private final Thread outputPump;
    private volatile int soTimeoutMillis;
    private volatile long connectDeadlineNanos;
    private volatile boolean established;
    private volatile boolean closed;
    private volatile boolean inputShutdown;
    private volatile boolean outputShutdown;

    private SshStdioSocket(Process process) {
        this.process = process;
        this.output = new ProcessOutputStream(process.getOutputStream());
        this.connectDeadlineNanos = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(DEFAULT_CONNECT_TIMEOUT_MILLIS);
        this.outputPump = Thread.ofVirtual().name("docker-ssh-stdout").start(this::pumpOutput);
        Thread.ofVirtual().name("docker-ssh-stderr").start(this::pumpErrors);
    }

    /** Starts {@code command}, which must relay the daemon's API over its standard input and output. */
    static SshStdioSocket open(List<String> command) throws IOException {
        Process process;
        try {
            process = new ProcessBuilder(command).start();
        } catch (IOException e) {
            throw new IOException("Could not run the ssh client to reach the Docker daemon; is OpenSSH on the PATH? "
                    + e.getMessage(), e);
        }
        return new SshStdioSocket(process);
    }

    private void pumpOutput() {
        try {
            try (InputStream stdout = process.getInputStream()) {
                byte[] buffer = new byte[CHUNK_SIZE];
                int read;
                while ((read = stdout.read(buffer)) >= 0) {
                    if (read > 0) {
                        chunks.put(Arrays.copyOf(buffer, read));
                    }
                }
            } catch (IOException e) {
                LOG.debugv("Stopped reading the Docker SSH session''s output: {0}", e.getMessage());
            }
            // Waits for room like the output before it, so a slow reader still gets every byte before the end.
            chunks.put(END_OF_STREAM);
        } catch (InterruptedException e) {
            // close() interrupts the pump so it never stays parked on a full queue nobody reads any more.
            Thread.currentThread().interrupt();
        }
    }

    private void pumpErrors() {
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(process.getErrorStream(), StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                LOG.debugv("ssh: {0}", line);
                if (line.startsWith(AUTHENTICATED)) {
                    established = true;
                }
                synchronized (stderr) {
                    if (stderr.length() < MAX_STDERR_CHARS) {
                        stderr.append(stderr.isEmpty() ? "" : " | ").append(line);
                    }
                }
            }
        } catch (IOException e) {
            LOG.debugv("Stopped reading the Docker SSH session''s errors: {0}", e.getMessage());
        }
    }

    /**
     * The failure to report when the stream ends: an SSH session that exited with an error before the daemon sent
     * anything failed to connect, and its error output says why.
     */
    private IOException sessionFailure() {
        try {
            if (!process.waitFor(1, TimeUnit.SECONDS) || process.exitValue() == 0) {
                return null;
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return null;
        }
        synchronized (stderr) {
            return new SocketException("SSH session to the Docker daemon exited with status " + process.exitValue()
                    + ": " + (stderr.isEmpty() ? "no error output" : stderr));
        }
    }

    @Override
    public void connect(SocketAddress endpoint) {
        // Connected by open(): httpclient5 calls this on the socket its factory returned.
    }

    /** The process is already started; {@code timeout} bounds the SSH login that follows. */
    @Override
    public void connect(SocketAddress endpoint, int timeout) {
        if (timeout > 0) {
            connectDeadlineNanos = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeout);
        }
    }

    @Override
    public InputStream getInputStream() throws IOException {
        ensureOpen();
        if (inputShutdown) {
            throw new SocketException("Socket input is shutdown");
        }
        return input;
    }

    @Override
    public OutputStream getOutputStream() throws IOException {
        ensureOpen();
        if (outputShutdown) {
            throw new SocketException("Socket output is shutdown");
        }
        return output;
    }

    @Override
    public void setSoTimeout(int timeout) {
        if (timeout < 0) {
            throw new IllegalArgumentException("timeout can't be negative");
        }
        soTimeoutMillis = timeout;
    }

    @Override
    public int getSoTimeout() {
        return soTimeoutMillis;
    }

    @Override
    public void shutdownInput() throws IOException {
        ensureOpen();
        inputShutdown = true;
    }

    /** Closes the session's standard input, which {@code dial-stdio} passes on to the daemon as a half-close. */
    @Override
    public void shutdownOutput() throws IOException {
        ensureOpen();
        outputShutdown = true;
        process.getOutputStream().close();
    }

    @Override
    public void close() {
        if (closed) {
            return;
        }
        closed = true;
        process.destroy();
        outputPump.interrupt();
        // Unread output is of no use once closed; the end marker wakes a read still waiting on the queue.
        chunks.clear();
        chunks.offer(END_OF_STREAM);
    }

    @Override
    public boolean isConnected() {
        return true;
    }

    @Override
    public boolean isBound() {
        return true;
    }

    @Override
    public boolean isClosed() {
        return closed;
    }

    @Override
    public boolean isInputShutdown() {
        return inputShutdown;
    }

    @Override
    public boolean isOutputShutdown() {
        return outputShutdown;
    }

    @Override
    public SocketAddress getLocalSocketAddress() {
        return null;
    }

    @Override
    public SocketAddress getRemoteSocketAddress() {
        return null;
    }

    @Override
    public InetAddress getInetAddress() {
        return null;
    }

    @Override
    public InetAddress getLocalAddress() {
        return null;
    }

    /** Whether the thread copying the session's output is still running. */
    boolean isOutputPumpAlive() {
        return outputPump.isAlive();
    }

    private void ensureOpen() throws SocketException {
        if (closed) {
            throw new SocketException("Socket is closed");
        }
    }

    private final class QueueInputStream extends InputStream {

        private byte[] current;
        private int position;
        private boolean anyRead;
        private boolean ended;

        @Override
        public int read() throws IOException {
            byte[] single = new byte[1];
            int read = read(single, 0, 1);
            return read < 0 ? -1 : single[0] & 0xFF;
        }

        @Override
        public synchronized int read(byte[] bytes, int offset, int length) throws IOException {
            if (length == 0) {
                return 0;
            }
            if (current == null || position == current.length) {
                if (ended) {
                    return -1;
                }
                byte[] next = take();
                if (next == END_OF_STREAM) {
                    ended = true;
                    ensureOpen();
                    IOException failure = anyRead ? null : sessionFailure();
                    if (failure != null) {
                        throw failure;
                    }
                    return -1;
                }
                current = next;
                position = 0;
                anyRead = true;
            }
            int count = Math.min(length, current.length - position);
            System.arraycopy(current, position, bytes, offset, count);
            position += count;
            return count;
        }

        private byte[] take() throws IOException {
            ensureOpen();
            long readTimeoutNanos = TimeUnit.MILLISECONDS.toNanos(soTimeoutMillis);
            long started = System.nanoTime();
            try {
                while (isConnecting()) {
                    long waited = System.nanoTime() - started;
                    if (readTimeoutNanos > 0 && waited >= readTimeoutNanos) {
                        throw readTimeout();
                    }
                    long wait = readTimeoutNanos > 0
                            ? Math.min(CONNECTING_POLL_NANOS, readTimeoutNanos - waited) : CONNECTING_POLL_NANOS;
                    // Output already queued wins over an expired deadline.
                    byte[] next = chunks.poll(wait, TimeUnit.NANOSECONDS);
                    if (next != null) {
                        return next;
                    }
                    if (isConnecting() && System.nanoTime() - connectDeadlineNanos >= 0) {
                        throw connectTimeout();
                    }
                }
                if (readTimeoutNanos == 0) {
                    return chunks.take();
                }
                long left = readTimeoutNanos - (System.nanoTime() - started);
                byte[] next = left > 0 ? chunks.poll(left, TimeUnit.NANOSECONDS) : chunks.poll();
                if (next == null) {
                    throw readTimeout();
                }
                return next;
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                close();
                throw new SocketException("Interrupted while reading from the Docker SSH session");
            }
        }

        private boolean isConnecting() {
            return !anyRead && !established;
        }

        private SocketTimeoutException readTimeout() {
            return new SocketTimeoutException("Read timed out after " + soTimeoutMillis + " ms");
        }

        private SocketTimeoutException connectTimeout() {
            synchronized (stderr) {
                return new SocketTimeoutException("SSH session to the Docker daemon did not log in within the connect"
                        + " timeout" + (stderr.isEmpty() ? "" : ": " + stderr));
            }
        }

        @Override
        public int available() {
            return current == null ? 0 : current.length - position;
        }
    }

    /** Flushes each write, since the process's standard input is buffered and a request must reach the daemon whole. */
    private final class ProcessOutputStream extends OutputStream {

        private final OutputStream stdin;

        ProcessOutputStream(OutputStream stdin) {
            this.stdin = stdin;
        }

        @Override
        public void write(int value) throws IOException {
            write(new byte[] {(byte) value}, 0, 1);
        }

        @Override
        public void write(byte[] bytes, int offset, int length) throws IOException {
            ensureOpen();
            stdin.write(bytes, offset, length);
            stdin.flush();
        }

        @Override
        public void flush() throws IOException {
            stdin.flush();
        }

        @Override
        public void close() {
            SshStdioSocket.this.close();
        }
    }
}
