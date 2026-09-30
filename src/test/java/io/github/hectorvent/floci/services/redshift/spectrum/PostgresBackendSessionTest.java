package io.github.hectorvent.floci.services.redshift.spectrum;

import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

class PostgresBackendSessionTest {

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
}
