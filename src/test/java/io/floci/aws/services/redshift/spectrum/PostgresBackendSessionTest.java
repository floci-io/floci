package io.floci.aws.services.redshift.spectrum;

import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static io.floci.aws.testing.PostgresDataRows.dataRow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PostgresBackendSessionTest {

    @Test
    void extendedFailureDoesNotWaitForIgnoredCleanupBeforeClientSync() throws Exception {
        try (ServerSocket listener = new ServerSocket(0);
             Socket backend = new Socket("localhost", listener.getLocalPort())) {
            ExecutorService executor = Executors.newSingleThreadExecutor();
            Future<String> names = executor.submit(() -> {
                try (Socket socket = listener.accept()) {
                    String first = "";
                    Frame frame;
                    do {
                        frame = readFrame(socket.getInputStream());
                        if (frame.type() == 'P') {
                            first = new String(frame.body(), StandardCharsets.UTF_8).split("\0", 2)[0];
                        }
                    } while (frame.type() != 'H');
                    send(socket.getOutputStream(), 'E', new byte[]{'C', '4', '2', 'P', '0', '1', 0,
                        'M', 'm', 'i', 's', 's', 'i', 'n', 'g', 0, 0});
                    String second = "";
                    do {
                        frame = readFrame(socket.getInputStream());
                        if (frame.type() == 'P') {
                            second = new String(frame.body(), StandardCharsets.UTF_8).split("\0", 2)[0];
                        }
                    } while (frame.type() != 'H');
                    send(socket.getOutputStream(), 'C', "SELECT 1\0".getBytes(StandardCharsets.UTF_8));
                    send(socket.getOutputStream(), '3', new byte[0]);
                    send(socket.getOutputStream(), '3', new byte[0]);
                    return first.equals(second) ? "reused" : "distinct";
                }
            });
            PostgresExtendedBackendSession sql = new PostgresExtendedBackendSession(backend);
            assertThrows(SpectrumReadException.class, () -> sql.execute("SELECT missing"));
            SpectrumReadException cleanup = assertThrows(SpectrumReadException.class,
                    () -> sql.execute("DROP TABLE t"));
            assertEquals("25P02", cleanup.sqlState());
            sql.onSync();
            sql.execute("SELECT 1");
            assertEquals("distinct", names.get());
            executor.shutdownNow();
        }
    }

    @Test
    void extendedPreparationClosesOnlyItsNamedObjectsWithoutSyncOrSimpleQuery() throws Exception {
        try (ServerSocket listener = new ServerSocket(0);
             Socket backend = new Socket("localhost", listener.getLocalPort())) {
            ExecutorService executor = Executors.newSingleThreadExecutor();
            Future<String> messages = executor.submit(() -> {
                try (Socket socket = listener.accept()) {
                    StringBuilder types = new StringBuilder();
                    Frame frame;
                    do {
                        frame = readFrame(socket.getInputStream());
                        types.append(frame.type());
                        if (frame.type() == 'P') {
                            assertEquals('f', (char) frame.body()[0]);
                        }
                    } while (frame.type() != 'H');
                    send(socket.getOutputStream(), '1', new byte[0]);
                    send(socket.getOutputStream(), '2', new byte[0]);
                    send(socket.getOutputStream(), 'C', "DO\0".getBytes(StandardCharsets.UTF_8));
                    send(socket.getOutputStream(), '3', new byte[0]);
                    send(socket.getOutputStream(), '3', new byte[0]);
                    return types.toString();
                }
            });
            new PostgresExtendedBackendSession(backend).execute("CREATE TABLE t(id integer); INSERT INTO t VALUES (1)");
            assertEquals("PBECCH", messages.get());
            executor.shutdownNow();
        }
    }

    @Test
    void copyReadFailureDrainsCopyFailResponseWithoutReplacingOriginalCause() throws Exception {
        try (ServerSocket listener = new ServerSocket(0);
             Socket backend = new Socket("localhost", listener.getLocalPort())) {
            ExecutorService executor = Executors.newSingleThreadExecutor();
            Future<Character> copyFailMessage = executor.submit(() -> serveCopyFailure(listener));
            SpectrumReadException rowFailure = new SpectrumReadException("22000", "bad CSV row");
            InputStream failingInput = new InputStream() {
                @Override
                public int read() throws IOException {
                    throw new IOException("reader failed", rowFailure);
                }
            };

            SpectrumReadException thrown = assertThrows(SpectrumReadException.class,
                    () -> new PostgresBackendSession(backend).copyIn("COPY \"t\" FROM STDIN", failingInput));

            assertEquals("58030", thrown.sqlState());
            assertSame(rowFailure, thrown.getCause().getCause());
            assertEquals('f', copyFailMessage.get());
            executor.shutdownNow();
        }
    }

    @Test
    void executeSurfacesBackendErrorWithItsSqlState() throws Exception {
        try (ServerSocket listener = new ServerSocket(0);
             Socket backend = new Socket("localhost", listener.getLocalPort())) {
            ExecutorService executor = Executors.newSingleThreadExecutor();
            Future<Character> queryMessage = executor.submit(() -> {
                try (Socket socket = listener.accept()) {
                    Frame query = readFrame(socket.getInputStream());
                    send(socket.getOutputStream(), 'E', new byte[]{'C', '4', '2', 'P', '0', '1', 0,
                        'M', 'n', 'o', ' ', 't', 'a', 'b', 'l', 'e', 0, 0});
                    send(socket.getOutputStream(), 'Z', new byte[]{'I'});
                    return query.type();
                }
            });

            SpectrumReadException thrown = assertThrows(SpectrumReadException.class,
                    () -> new PostgresBackendSession(backend).execute("SELECT 1"));

            assertEquals("42P01", thrown.sqlState());
            assertEquals('Q', queryMessage.get());
            executor.shutdownNow();
        }
    }

    @Test
    void copyInStreamsDataAndReturnsTheRowCountFromTheCommandTag() throws Exception {
        try (ServerSocket listener = new ServerSocket(0);
             Socket backend = new Socket("localhost", listener.getLocalPort())) {
            ExecutorService executor = Executors.newSingleThreadExecutor();
            Future<String> received = executor.submit(() -> {
                try (Socket socket = listener.accept()) {
                    InputStream input = socket.getInputStream();
                    OutputStream output = socket.getOutputStream();
                    readFrame(input);
                    send(output, 'G', new byte[]{0, 0, 0});
                    StringBuilder data = new StringBuilder();
                    Frame frame = readFrame(input);
                    while (frame.type() == 'd') {
                        data.append(new String(frame.body(), StandardCharsets.UTF_8));
                        frame = readFrame(input);
                    }
                    send(output, 'C', "COPY 3\0".getBytes(StandardCharsets.UTF_8));
                    send(output, 'Z', new byte[]{'I'});
                    return data + "|" + frame.type();
                }
            });

            long rows = new PostgresBackendSession(backend).copyIn("COPY \"t\" FROM STDIN",
                    new ByteArrayInputStream("a\nb\nc\n".getBytes(StandardCharsets.UTF_8)));

            assertEquals(3, rows);
            assertEquals("a\nb\nc\n|c", received.get());
            executor.shutdownNow();
        }
    }

    @Test
    void copyInContinuesAfterAZeroByteRead() throws Exception {
        try (ServerSocket listener = new ServerSocket(0);
             Socket backend = new Socket("localhost", listener.getLocalPort())) {
            ExecutorService executor = Executors.newSingleThreadExecutor();
            Future<String> received = executor.submit(() -> {
                try (Socket socket = listener.accept()) {
                    InputStream input = socket.getInputStream();
                    OutputStream output = socket.getOutputStream();
                    readFrame(input);
                    send(output, 'G', new byte[]{0, 0, 0});
                    StringBuilder data = new StringBuilder();
                    Frame frame = readFrame(input);
                    while (frame.type() == 'd') {
                        data.append(new String(frame.body(), StandardCharsets.UTF_8));
                        frame = readFrame(input);
                    }
                    send(output, 'C', "COPY 1\0".getBytes(StandardCharsets.UTF_8));
                    send(output, 'Z', new byte[]{'I'});
                    return data + "|" + frame.type();
                }
            });
            ByteArrayInputStream source = new ByteArrayInputStream("row\n".getBytes(StandardCharsets.UTF_8));
            InputStream zeroByteReadInput = new InputStream() {
                private boolean returnZero = true;

                @Override
                public int read() {
                    return source.read();
                }

                @Override
                public int read(byte[] bytes, int offset, int length) {
                    if (returnZero) {
                        returnZero = false;
                        return 0;
                    }
                    return source.read(bytes, offset, length);
                }
            };

            long rows = new PostgresBackendSession(backend).copyIn("COPY \"t\" FROM STDIN", zeroByteReadInput);

            assertEquals(1, rows);
            assertEquals("row\n|c", received.get());
            executor.shutdownNow();
        }
    }


    private static Character serveCopyFailure(ServerSocket listener) throws IOException {
        try (Socket socket = listener.accept()) {
            InputStream input = socket.getInputStream();
            OutputStream output = socket.getOutputStream();
            readFrame(input);
            send(output, 'G', new byte[]{0, 0, 0});
            Frame copyFail = readFrame(input);
            byte[] error = new byte[]{'C', '5', '7', '0', '1', '4', 0, 'M', 'C', 'O', 'P', 'Y', ' ', 'f',
                'a', 'i', 'l', 'e', 'd', 0, 0};
            send(output, 'E', error);
            send(output, 'Z', new byte[]{'I'});
            return copyFail.type();
        }
    }

    private static Frame readFrame(InputStream input) throws IOException {
        int type = input.read();
        if (type < 0) {
            throw new IOException("unexpected EOF");
        }
        byte[] length = input.readNBytes(4);
        int size = ((length[0] & 0xff) << 24) | ((length[1] & 0xff) << 16)
                | ((length[2] & 0xff) << 8) | (length[3] & 0xff);
        return new Frame((char) type, input.readNBytes(size - 4));
    }

    private static void send(OutputStream output, char type, byte[] body) throws IOException {
        int size = body.length + 4;
        output.write(type);
        output.write((size >>> 24) & 0xff);
        output.write((size >>> 16) & 0xff);
        output.write((size >>> 8) & 0xff);
        output.write(size & 0xff);
        output.write(body);
        output.flush();
    }

    private record Frame(char type, byte[] body) {
    }

    @Test
    void preparationFailsFastAfterTheClientsOwnBackendError() {
        PostgresExtendedBackendSession session = new PostgresExtendedBackendSession(null);
        session.onBackendError();
        SpectrumReadException error = assertThrows(SpectrumReadException.class, () -> session.execute("CREATE TABLE t(id INT)"));
        assertEquals("25P02", error.sqlState());
        session.onSync();
    }

    @Test
    void queryStringReadsFirstColumnOfFirstRowAndHandlesNullAndEmpty() throws Exception {
        try (ServerSocket listener = new ServerSocket(0);
             Socket backend = new Socket("localhost", listener.getLocalPort())) {
            ExecutorService executor = Executors.newSingleThreadExecutor();
            Future<Void> server = executor.submit(() -> {
                try (Socket socket = listener.accept()) {
                    InputStream input = socket.getInputStream();
                    OutputStream output = socket.getOutputStream();
                    // First query returns "analytics".
                    readFrame(input);
                    send(output, 'D', dataRow(List.of("analytics")));
                    send(output, 'C', "SELECT 1\0".getBytes(StandardCharsets.UTF_8));
                    send(output, 'Z', new byte[]{'I'});
                    // Second query returns SQL NULL.
                    readFrame(input);
                    send(output, 'D', dataRow(Collections.singletonList(null)));
                    send(output, 'C', "SELECT 1\0".getBytes(StandardCharsets.UTF_8));
                    send(output, 'Z', new byte[]{'I'});
                    // Third query returns no rows.
                    readFrame(input);
                    send(output, 'C', "SELECT 0\0".getBytes(StandardCharsets.UTF_8));
                    send(output, 'Z', new byte[]{'I'});
                    return null;
                }
            });
            PostgresBackendSession session = new PostgresBackendSession(backend);
            assertEquals(Optional.of("analytics"), session.queryString("SELECT pg_catalog.current_schema()"));
            assertEquals(Optional.empty(), session.queryString("SELECT pg_catalog.current_schema()"));
            assertEquals(Optional.empty(), session.queryString("SELECT n.nspname FROM pg_proc WHERE false"));
            server.get();
            executor.shutdownNow();
        }
    }

    @Test
    void extendedQueryStringReadsFirstColumnWithoutSync() throws Exception {
        try (ServerSocket listener = new ServerSocket(0);
             Socket backend = new Socket("localhost", listener.getLocalPort())) {
            ExecutorService executor = Executors.newSingleThreadExecutor();
            Future<String> messages = executor.submit(() -> {
                try (Socket socket = listener.accept()) {
                    StringBuilder types = new StringBuilder();
                    Frame frame;
                    do {
                        frame = readFrame(socket.getInputStream());
                        types.append(frame.type());
                    } while (frame.type() != 'H');
                    send(socket.getOutputStream(), '1', new byte[0]);
                    send(socket.getOutputStream(), '2', new byte[0]);
                    send(socket.getOutputStream(), 'D', dataRow(List.of("analytics")));
                    send(socket.getOutputStream(), 'C', "SELECT 1\0".getBytes(StandardCharsets.UTF_8));
                    send(socket.getOutputStream(), '3', new byte[0]);
                    send(socket.getOutputStream(), '3', new byte[0]);
                    return types.toString();
                }
            });
            Optional<String> schema = new PostgresExtendedBackendSession(backend)
                    .queryString("SELECT pg_catalog.current_schema()");
            assertEquals(Optional.of("analytics"), schema);
            assertEquals("PBECCH", messages.get());
            executor.shutdownNow();
        }
    }

    @Test
    void aSimpleLookupThatTimesOutClosesTheBackendSoLateRepliesCannotReachTheClient() throws Exception {
        try (ServerSocket listener = new ServerSocket(0);
             Socket backend = new Socket("localhost", listener.getLocalPort())) {
            backend.setSoTimeout(200);
            ExecutorService executor = Executors.newSingleThreadExecutor();
            Future<Void> server = executor.submit(() -> {
                try (Socket socket = listener.accept()) {
                    readFrame(socket.getInputStream());
                    // never replies: the lookup times out while its answer is still to come
                    assertEquals(-1, socket.getInputStream().read());
                    return null;
                }
            });

            SpectrumReadException error = assertThrows(SpectrumReadException.class,
                    () -> new PostgresBackendSession(backend).queryString("SELECT pg_catalog.current_schema()"));

            assertEquals("58030", error.sqlState());
            assertTrue(backend.isClosed());
            server.get();
            executor.shutdownNow();
        }
    }

    @Test
    void anExtendedLookupThatTimesOutClosesTheBackendSoLateRepliesCannotReachTheClient() throws Exception {
        try (ServerSocket listener = new ServerSocket(0);
             Socket backend = new Socket("localhost", listener.getLocalPort())) {
            backend.setSoTimeout(200);
            ExecutorService executor = Executors.newSingleThreadExecutor();
            Future<Void> server = executor.submit(() -> {
                try (Socket socket = listener.accept()) {
                    Frame frame;
                    do {
                        frame = readFrame(socket.getInputStream());
                    } while (frame.type() != 'H');
                    // ParseComplete only: the rest of the exchange is still to come when the lookup gives up
                    send(socket.getOutputStream(), '1', new byte[0]);
                    assertEquals(-1, socket.getInputStream().read());
                    return null;
                }
            });

            SpectrumReadException error = assertThrows(SpectrumReadException.class,
                    () -> new PostgresExtendedBackendSession(backend).queryString("SELECT pg_catalog.current_schema()"));

            assertEquals("58030", error.sqlState());
            assertTrue(backend.isClosed());
            server.get();
            executor.shutdownNow();
        }
    }
}
