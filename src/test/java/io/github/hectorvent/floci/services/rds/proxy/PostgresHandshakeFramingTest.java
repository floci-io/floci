package io.github.hectorvent.floci.services.rds.proxy;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.Socket;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PostgresHandshakeFramingTest {

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void clientLoginResponsesAreBufferedUntilTheCompleteResponseIsReady(boolean rejected) throws Exception {
        RecordingOutputStream output = new RecordingOutputStream();
        Socket client = new MemorySocket(startupAndPassword(), output);
        Socket backend = new MemorySocket(new byte[] {
                'R', 0, 0, 0, 8, 0, 0, 0, 0,
                'Z', 0, 0, 0, 5, 'I'
        }, new RecordingOutputStream());
        AtomicInteger backendConnections = new AtomicInteger();
        try (client; backend) {
            PostgresProtocolHandler.AuthenticatedSession session = PostgresProtocolHandler.authenticate(
                    client, () -> {
                        backendConnections.incrementAndGet();
                        return backend;
                    }, "master", "password", "appdb", false, null, null, null,
                    (user, password) -> rejected ? PasswordValidator.AuthResult.REJECT
                            : PasswordValidator.AuthResult.MASTER_EQUIVALENT, 5000);

            assertArrayEquals(new byte[] {'R', 0, 0, 0, 8, 0, 0, 0, 3}, output.writes.getFirst());
            assertEquals(2, output.writes.size());
            assertEquals(2, output.flushes);
            if (rejected) {
                assertNull(session);
                assertEquals(0, backendConnections.get());
                byte[] error = output.writes.get(1);
                assertEquals('E', error[0]);
                assertEquals(error.length - 1, ByteBuffer.wrap(error, 1, 4).getInt());
                assertArrayEquals(("SFATAL\0C28P01\0Mpassword authentication failed for user \"master\"\0\0")
                        .getBytes(StandardCharsets.UTF_8), Arrays.copyOfRange(error, 5, error.length));
            } else {
                assertNotNull(session);
                assertEquals(1, backendConnections.get());
                assertArrayEquals(new byte[] {
                        'R', 0, 0, 0, 8, 0, 0, 0, 0,
                        'Z', 0, 0, 0, 5, 'I'
                }, output.writes.get(1));
            }
        }
    }

    @Test
    void cancelRequestIsForwardedToTheBackendUnchanged() throws Exception {
        byte[] cancel = cancelRequest(16, 4242, 0x5eed);
        RecordingOutputStream clientOutput = new RecordingOutputStream();
        RecordingOutputStream backendOutput = new RecordingOutputStream();
        Socket client = new MemorySocket(cancel, clientOutput);
        Socket backend = new MemorySocket(new byte[0], backendOutput);
        AtomicInteger backendConnections = new AtomicInteger();
        try (client; backend) {
            PostgresProtocolHandler.AuthenticatedSession session = authenticate(client, () -> {
                backendConnections.incrementAndGet();
                return backend;
            });

            assertNull(session);
            assertEquals(1, backendConnections.get());
            assertArrayEquals(cancel, backendOutput.toByteArray());
            assertEquals(0, clientOutput.size());
            assertTrue(backend.isClosed());
        }
    }

    @Test
    void cancelRequestToADbProxyEndpointReachesNoBackend() throws Exception {
        Socket client = new MemorySocket(cancelRequest(16, 4242, 0x5eed), new RecordingOutputStream());
        AtomicInteger backendConnections = new AtomicInteger();
        try (client) {
            assertNull(PostgresProtocolHandler.authenticate(client, () -> {
                backendConnections.incrementAndGet();
                return new MemorySocket(new byte[0], new RecordingOutputStream());
            }, "master", "password", "appdb", false, null, null, null,
                    (user, password) -> PasswordValidator.AuthResult.MASTER_EQUIVALENT, 5000, true, false));
            assertEquals(0, backendConnections.get());
        }
    }

    @Test
    void cancelRequestOfTheWrongLengthReachesNoBackend() throws Exception {
        Socket client = new MemorySocket(cancelRequest(20, 4242, 0x5eed), new RecordingOutputStream());
        AtomicInteger backendConnections = new AtomicInteger();
        try (client) {
            assertNull(authenticate(client, () -> {
                backendConnections.incrementAndGet();
                return new MemorySocket(new byte[0], new RecordingOutputStream());
            }));
            assertEquals(0, backendConnections.get());
        }
    }

    @Test
    void truncatedCancelRequestReachesNoBackend() throws Exception {
        Socket client = new MemorySocket(Arrays.copyOf(cancelRequest(16, 4242, 0x5eed), 12),
                new RecordingOutputStream());
        AtomicInteger backendConnections = new AtomicInteger();
        try (client) {
            assertThrows(IOException.class, () -> authenticate(client, () -> {
                backendConnections.incrementAndGet();
                return new MemorySocket(new byte[0], new RecordingOutputStream());
            }));
            assertEquals(0, backendConnections.get());
        }
    }

    private static PostgresProtocolHandler.AuthenticatedSession authenticate(
            Socket client, PostgresProtocolHandler.BackendConnector backend) throws IOException {
        return PostgresProtocolHandler.authenticate(client, backend, "master", "password", "appdb",
                false, null, null, null, (user, password) -> PasswordValidator.AuthResult.MASTER_EQUIVALENT,
                5000);
    }

    private static byte[] cancelRequest(int length, int processId, int secretKey) {
        return ByteBuffer.allocate(length).putInt(length).putInt(80877102)
                .putInt(processId).putInt(secretKey).array();
    }

    private static byte[] startupAndPassword() throws IOException {
        ByteArrayOutputStream input = new ByteArrayOutputStream();
        DataOutputStream out = new DataOutputStream(input);
        byte[] parameters = "user\0master\0database\0appdb\0\0".getBytes(StandardCharsets.UTF_8);
        out.writeInt(8 + parameters.length);
        out.writeInt(196608);
        out.write(parameters);
        byte[] password = "password\0".getBytes(StandardCharsets.UTF_8);
        out.writeByte('p');
        out.writeInt(4 + password.length);
        out.write(password);
        return input.toByteArray();
    }

    private static final class RecordingOutputStream extends ByteArrayOutputStream {
        private final List<byte[]> writes = new ArrayList<>();
        private int flushes;

        @Override
        public void write(int value) {
            writes.add(new byte[] {(byte) value});
            super.write(value);
        }

        @Override
        public void write(byte[] bytes, int offset, int length) {
            writes.add(Arrays.copyOfRange(bytes, offset, offset + length));
            super.write(bytes, offset, length);
        }

        @Override
        public void flush() {
            flushes++;
        }
    }

    private static final class MemorySocket extends Socket {
        private final InputStream input;
        private final RecordingOutputStream output;

        private MemorySocket(byte[] input, RecordingOutputStream output) {
            this.input = new ByteArrayInputStream(input);
            this.output = output;
        }

        @Override
        public InputStream getInputStream() {
            return input;
        }

        @Override
        public RecordingOutputStream getOutputStream() {
            return output;
        }
    }
}
