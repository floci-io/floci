package io.floci.aws.core.common.docker;

import io.floci.aws.testing.ParallelLocks;
import io.floci.aws.testing.ThreadStates;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.ResourceAccessMode;
import org.junit.jupiter.api.parallel.ResourceLock;

import java.io.IOException;
import java.io.InputStream;
import java.net.SocketException;
import java.net.SocketTimeoutException;
import java.net.URISyntaxException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.EnumSet;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

import static org.awaitility.Awaitility.await;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.is;
import static org.junit.jupiter.api.Assertions.assertThrows;

@ResourceLock(value = ParallelLocks.PORTS, mode = ResourceAccessMode.READ)
class SshStdioSocketTest {

    private static final Duration AWAIT_TIMEOUT = Duration.ofSeconds(10);

    private static List<String> javaMain(Class<?> main) {
        String java = Path.of(System.getProperty("java.home"), "bin", "java").toString();
        return List.of(java, "-cp", testClasses(main), main.getName());
    }

    /** Only the test classes, since the whole test class path is too long for a Windows command line. */
    private static String testClasses(Class<?> main) {
        try {
            return Path.of(main.getProtectionDomain().getCodeSource().getLocation().toURI()).toString();
        } catch (URISyntaxException e) {
            throw new IllegalStateException(e);
        }
    }

    @Test
    void bytesWrittenComeBackThroughTheProcess() throws IOException {
        try (SshStdioSocket socket = SshStdioSocket.open(javaMain(Echo.class))) {
            socket.setSoTimeout(30_000);
            socket.getOutputStream().write("GET /_ping HTTP/1.1\r\n".getBytes(StandardCharsets.UTF_8));

            byte[] echoed = socket.getInputStream().readNBytes(21);

            assertThat(new String(echoed, StandardCharsets.UTF_8), equalTo("GET /_ping HTTP/1.1\r\n"));
        }
    }

    @Test
    void aReadWithNoDataFailsAfterTheSoTimeout() throws IOException {
        AtomicLong clock = new AtomicLong();
        try (SshStdioSocket socket = SshStdioSocket.open(
                javaMain(Echo.class), () -> clock.addAndGet(Duration.ofSeconds(1).toNanos()))) {
            socket.setSoTimeout(200);

            assertThrows(SocketTimeoutException.class, () -> socket.getInputStream().read());
            assertThat(socket.isClosed(), is(false));
        }
    }

    @Test
    void aSessionThatFailsToConnectReportsItsErrorOutput() throws IOException {
        try (SshStdioSocket socket = SshStdioSocket.open(javaMain(FailToConnect.class))) {
            socket.setSoTimeout(30_000);
            InputStream in = socket.getInputStream();

            SocketException failure = assertThrows(SocketException.class, in::read);

            assertThat(failure.getMessage(), containsString("exited with status 255"));
            assertThat(failure.getMessage(), containsString("Connection refused"));
        }
    }

    @Test
    void closingTheSocketEndsAWaitingRead() throws Exception {
        SshStdioSocket socket = SshStdioSocket.open(javaMain(Echo.class));
        Thread reader = Thread.currentThread();
        Thread closer = Thread.ofVirtual().start(() -> {
            ThreadStates.awaitState(reader, EnumSet.of(Thread.State.WAITING, Thread.State.TIMED_WAITING),
                    AWAIT_TIMEOUT, "reader waiting on queue");
            socket.close();
        });

        assertThrows(SocketException.class, () -> socket.getInputStream().read());
        closer.join();
        assertThat(socket.isClosed(), is(true));
    }

    @Test
    void shutdownOutputEndsTheSessionInput() throws IOException {
        try (SshStdioSocket socket = SshStdioSocket.open(javaMain(Echo.class))) {
            socket.setSoTimeout(30_000);
            socket.getOutputStream().write('x');
            socket.shutdownOutput();
            InputStream in = socket.getInputStream();

            assertThat(in.read(), equalTo((int) 'x'));
            assertThat(in.read(), equalTo(-1));
        }
    }

    @Test
    void aSlowReaderGetsEveryByteOfASessionThatAlreadyEnded() throws Exception {
        try (SshStdioSocket socket = SshStdioSocket.open(javaMain(WriteThenExit.class))) {
            socket.setSoTimeout(30_000);
            InputStream in = socket.getInputStream();
            in.read();
            assertThat(socket.awaitProcessExit(AWAIT_TIMEOUT), is(true));
            socket.awaitOutputPumpExit(AWAIT_TIMEOUT);

            byte[] rest = in.readAllBytes();

            assertThat(1 + rest.length, equalTo(WriteThenExit.BYTES));
        }
    }

    @Test
    void aSessionThatNeverAnswersFailsAtTheConnectTimeoutEvenWithoutAReadTimeout() throws IOException {
        AtomicLong clock = new AtomicLong();
        try (SshStdioSocket socket = SshStdioSocket.open(javaMain(Echo.class), clock::get)) {
            socket.connect(null, 500);
            socket.setSoTimeout(0);
            clock.addAndGet(Duration.ofSeconds(1).toNanos());

            SocketTimeoutException failure =
                    assertThrows(SocketTimeoutException.class, () -> socket.getInputStream().read());

            assertThat(failure.getMessage(), containsString("did not log in within the connect timeout"));
        }
    }

    @Test
    void theConnectTimeoutNoLongerAppliesOnceTheSessionAnswered() throws Exception {
        AtomicLong clock = new AtomicLong();
        try (SshStdioSocket socket = SshStdioSocket.open(javaMain(Echo.class), clock::get)) {
            socket.connect(null, 300);
            socket.setSoTimeout(30_000);
            socket.getOutputStream().write('a');
            InputStream in = socket.getInputStream();
            assertThat(in.read(), equalTo((int) 'a'));
            clock.addAndGet(Duration.ofSeconds(1).toNanos());
            socket.getOutputStream().write('b');

            assertThat(in.read(), equalTo((int) 'b'));
        }
    }

    @Test
    void anAnswerAlreadyQueuedIsReadEvenAfterTheConnectDeadlinePassed() throws Exception {
        AtomicLong clock = new AtomicLong();
        try (SshStdioSocket socket = SshStdioSocket.open(javaMain(Echo.class), clock::get)) {
            socket.connect(null, 300);
            socket.setSoTimeout(30_000);
            socket.getOutputStream().write('a');
            await().atMost(AWAIT_TIMEOUT).until(socket::hasQueuedChunks);
            clock.addAndGet(Duration.ofSeconds(1).toNanos());

            assertThat(socket.getInputStream().read(), equalTo((int) 'a'));
        }
    }

    @Test
    void aSlowUploadAfterLoginIsNotCutShortByTheConnectTimeout() throws Exception {
        AtomicLong clock = new AtomicLong();
        try (SshStdioSocket socket = SshStdioSocket.open(javaMain(LoggedInEcho.class), clock::get)) {
            socket.connect(null, 300);
            socket.setSoTimeout(30_000);
            await().atMost(AWAIT_TIMEOUT).until(socket::isEstablished);
            // The request body is still being sent well past the connect timeout.
            for (int i = 0; i < 4; i++) {
                clock.addAndGet(Duration.ofMillis(250).toNanos());
                socket.getOutputStream().write('u');
            }

            byte[] answer = socket.getInputStream().readNBytes(4);

            assertThat(new String(answer, StandardCharsets.UTF_8), equalTo("uuuu"));
        }
    }

    @Test
    void closingReleasesTheOutputPumpBlockedOnAFullQueue() throws Exception {
        SshStdioSocket socket = SshStdioSocket.open(javaMain(WriteManyThenExit.class));
        await().atMost(AWAIT_TIMEOUT).until(socket::isOutputPumpBlockedOnQueue);
        assertThat(socket.isOutputPumpAlive(), is(true));

        socket.close();

        socket.awaitOutputPumpExit(AWAIT_TIMEOUT);
        assertThat(socket.isOutputPumpAlive(), is(false));
    }

    /** Copies its input to its output, as {@code docker system dial-stdio} relays the daemon's socket. */
    public static final class Echo {
        public static void main(String[] args) throws IOException {
            byte[] buffer = new byte[1024];
            int read;
            while ((read = System.in.read(buffer)) >= 0) {
                System.out.write(buffer, 0, read);
                System.out.flush();
            }
        }
    }

    /** Reports a finished login as {@code ssh} at {@code LogLevel=VERBOSE} does, then relays like {@link Echo}. */
    public static final class LoggedInEcho {
        public static void main(String[] args) throws IOException {
            System.err.println(SshStdioSocket.AUTHENTICATED + "remote-docker ([10.0.0.5]:22) using \"publickey\".");
            System.err.flush();
            Echo.main(args);
        }
    }

    /** Writes multiple chunks that fit in the queue unread, then exits. */
    public static final class WriteThenExit {
        static final int BYTES = 64 * 1024;

        public static void main(String[] args) throws IOException {
            System.out.write(new byte[BYTES]);
            System.out.flush();
        }
    }

    /** Writes more than the socket queues unread, then exits. */
    public static final class WriteManyThenExit {
        static final int BYTES = 4 * 1024 * 1024;

        public static void main(String[] args) throws IOException {
            byte[] chunk = new byte[64 * 1024];
            for (int written = 0; written < BYTES; written += chunk.length) {
                System.out.write(chunk);
            }
            System.out.flush();
        }
    }

    /** Exits as {@code ssh} does when it cannot reach the host. */
    public static final class FailToConnect {
        public static void main(String[] args) {
            System.err.println("ssh: connect to host remote-docker port 22: Connection refused");
            System.exit(255);
        }
    }
}
