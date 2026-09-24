package io.github.hectorvent.floci.services.redshift.spectrum;

import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Arrays;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class SpectrumMaterializerTest {

    @Test
    void materializesThroughBackendSqlWithoutBufferingRowsInTheCaller() throws Exception {
        SpectrumS3Reader reader = mock(SpectrumS3Reader.class);
        when(reader.read(org.mockito.ArgumentMatchers.any(SpectrumSession.class),
                org.mockito.ArgumentMatchers.any(SpectrumExternalSchema.class),
                org.mockito.ArgumentMatchers.any(SpectrumExternalTable.class)))
                .thenReturn(Stream.of(new SpectrumRow(List.of("1", "Alice\nSmith")),
                        new SpectrumRow(Arrays.asList("2", null))));
        SpectrumExternalTable table = new SpectrumExternalTable("000000000000", "dev", "analytics", "events",
                List.of(new SpectrumColumn("id", SpectrumColumn.Type.INTEGER),
                        new SpectrumColumn("name", SpectrumColumn.Type.VARCHAR)),
                "s3://warehouse/events/", ',', '"', '\\', "\\N", 0);
        CapturingBackendSql backend = new CapturingBackendSql();

        SpectrumMaterializer.Materialization materialized = new SpectrumMaterializer().materialize(
                backend, new SpectrumSession("000000000000", "cluster-1", "dev", List.of(), false),
                schema(), table, reader, "spectrum_tmp_test");

        assertEquals("spectrum_tmp_test", materialized.identifier());
        assertEquals(List.of(
                "CREATE TEMP TABLE \"spectrum_tmp_test\" (\"id\" INTEGER, \"name\" VARCHAR)",
                "COPY \"spectrum_tmp_test\" (\"id\", \"name\") FROM STDIN"), backend.commands);
        assertEquals("1\tAlice\\nSmith\n2\t\\N\n",
                new String(backend.copiedInput, StandardCharsets.UTF_8));
    }

    @Test
    void copyFailureDropsTemporaryTableAndPreservesBackendSqlState() {
        SpectrumS3Reader reader = mock(SpectrumS3Reader.class);
        when(reader.read(org.mockito.ArgumentMatchers.any(SpectrumSession.class),
                org.mockito.ArgumentMatchers.any(SpectrumExternalSchema.class),
                org.mockito.ArgumentMatchers.any(SpectrumExternalTable.class)))
                .thenReturn(Stream.of(new SpectrumRow(List.of("1"))));
        SpectrumReadException backendFailure = new SpectrumReadException("42501", "COPY denied");
        CapturingBackendSql backend = new CapturingBackendSql(backendFailure);

        SpectrumReadException thrown = assertThrows(SpectrumReadException.class,
                () -> new SpectrumMaterializer().materialize(backend, session(), schema(), oneColumnTable(), reader,
                        "spectrum_tmp_test"));

        assertSame(backendFailure, thrown);
        assertEquals(List.of(
                "CREATE TEMP TABLE \"spectrum_tmp_test\" (\"id\" INTEGER)",
                "COPY \"spectrum_tmp_test\" (\"id\") FROM STDIN",
                "DROP TABLE IF EXISTS \"spectrum_tmp_test\""), backend.commands);
    }

    @Test
    void rowReadFailurePreservesSqlStateAndDropsTemporaryTable() {
        SpectrumS3Reader reader = mock(SpectrumS3Reader.class);
        SpectrumReadException rowFailure = new SpectrumReadException("22000", "bad CSV row");
        when(reader.read(org.mockito.ArgumentMatchers.any(SpectrumSession.class),
                org.mockito.ArgumentMatchers.any(SpectrumExternalSchema.class),
                org.mockito.ArgumentMatchers.any(SpectrumExternalTable.class)))
                .thenReturn(Stream.generate(() -> {
                    throw rowFailure;
                }));
        CapturingBackendSql backend = new CapturingBackendSql();

        SpectrumReadException thrown = assertThrows(SpectrumReadException.class,
                () -> new SpectrumMaterializer().materialize(backend, session(), schema(), oneColumnTable(), reader,
                        "spectrum_tmp_test"));

        assertSame(rowFailure, thrown);
        assertEquals(List.of(
                "CREATE TEMP TABLE \"spectrum_tmp_test\" (\"id\" INTEGER)",
                "COPY \"spectrum_tmp_test\" (\"id\") FROM STDIN",
                "DROP TABLE IF EXISTS \"spectrum_tmp_test\""), backend.commands);
    }

    @Test
    void materializesRowsWithGeneratedIdentifierAndCopyFraming() throws Exception {
        try (ServerSocket listener = new ServerSocket(0); Socket backend = new Socket("localhost", listener.getLocalPort())) {
            ExecutorService executor = Executors.newSingleThreadExecutor();
            Future<byte[]> captured = executor.submit(() -> serve(listener));
            SpectrumS3Reader reader = mock(SpectrumS3Reader.class);
            when(reader.read(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any()))
                    .thenReturn(Stream.of(new SpectrumRow(Arrays.asList("1", null)), new SpectrumRow(List.of("two", "x"))));
            SpectrumExternalTable table = new SpectrumExternalTable("000000000000", "dev", "analytics", "events",
                    List.of(new SpectrumColumn("id", SpectrumColumn.Type.INTEGER),
                            new SpectrumColumn("value", SpectrumColumn.Type.VARCHAR)),
                    "s3://warehouse/events/", ',', '"', '\\', "\\N", 0);

            SpectrumMaterializer.Materialization materialization = new SpectrumMaterializer()
                    .materialize(backend, table, null, reader);
            new SpectrumMaterializer().cleanup(backend, materialization);

            byte[] copy = captured.get();
            assertTrue(materialization.identifier().startsWith("spectrum_tmp_"));
            assertFalse(materialization.identifier().contains("events"));
            assertTrue(new String(copy, StandardCharsets.UTF_8).contains("1\t\\N\ntwo\tx\n"));
            executor.shutdownNow();
        }
    }

    private static byte[] serve(ServerSocket listener) throws IOException {
        try (Socket socket = listener.accept()) {
            InputStream input = socket.getInputStream();
            OutputStream output = socket.getOutputStream();
            ByteArrayOutputStream copyRows = new ByteArrayOutputStream();
            readQuery(input);
            send(output, 'C', "CREATE 0\0".getBytes(StandardCharsets.UTF_8));
            send(output, 'Z', new byte[]{'I'});
            readQuery(input);
            send(output, 'G', new byte[]{0, 0, 0});
            while (true) {
                Frame frame = readFrame(input);
                if (frame.type() == 'c') {
                    break;
                }
                if (frame.type() == 'd') {
                    copyRows.write(frame.body());
                }
            }
            send(output, 'C', "COPY 2\0".getBytes(StandardCharsets.UTF_8));
            send(output, 'Z', new byte[]{'I'});
            readQuery(input);
            send(output, 'C', "DROP TABLE\0".getBytes(StandardCharsets.UTF_8));
            send(output, 'Z', new byte[]{'I'});
            return copyRows.toByteArray();
        }
    }

    private static void readQuery(InputStream input) throws IOException {
        Frame frame = readFrame(input);
        if (frame.type() != 'Q') {
            throw new IOException("expected query");
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

    private static SpectrumExternalSchema schema() {
        return new SpectrumExternalSchema("000000000000", "dev", "analytics", "s3://warehouse/root/", null);
    }

    private static SpectrumSession session() {
        return new SpectrumSession("000000000000", "cluster-1", "dev", List.of(), false);
    }

    private static SpectrumExternalTable oneColumnTable() {
        return new SpectrumExternalTable("000000000000", "dev", "analytics", "events",
                List.of(new SpectrumColumn("id", SpectrumColumn.Type.INTEGER)),
                "s3://warehouse/events/", ',', '"', '\\', "\\N", 0);
    }

    private static final class CapturingBackendSql implements BackendSql {
        private final ArrayList<String> commands = new ArrayList<>();
        private final SpectrumReadException copyFailure;
        private byte[] copiedInput;

        private CapturingBackendSql() {
            this(null);
        }

        private CapturingBackendSql(SpectrumReadException copyFailure) {
            this.copyFailure = copyFailure;
        }

        @Override
        public void execute(String sql) {
            commands.add(sql);
        }

        @Override
        public long copyIn(String copySql, InputStream data) {
            commands.add(copySql);
            if (copyFailure != null) {
                throw copyFailure;
            }
            try {
                copiedInput = data.readAllBytes();
            } catch (IOException exception) {
                throw new SpectrumReadException("22000", "Unable to capture Spectrum rows", exception);
            }
            return 2;
        }
    }
}
