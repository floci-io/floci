package io.floci.aws.core.common.docker;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.net.SocketException;
import java.net.SocketTimeoutException;
import java.net.URISyntaxException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.is;
import static org.junit.jupiter.api.Assertions.assertThrows;

class SshStdioSocketTest {

    private static List<String> javaMain(Class<?> main) {
        String java = Path.of(System.getProperty("java.home"), "bin", "java").toString();
        return List.of(java, "-cp", testClasses(main), main.getName());
    }

    /** Only the test classes, since the whole test class path is too long for a Windows command line. */
    static String testClasses(Class<?> main) {
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
        try (SshStdioSocket socket = SshStdioSocket.open(javaMain(Echo.class))) {
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
        Thread closer = Thread.ofVirtual().start(() -> {
            try {
                Thread.sleep(300);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
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

    /** Exits as {@code ssh} does when it cannot reach the host. */
    public static final class FailToConnect {
        public static void main(String[] args) {
            System.err.println("ssh: connect to host remote-docker port 22: Connection refused");
            System.exit(255);
        }
    }
}
