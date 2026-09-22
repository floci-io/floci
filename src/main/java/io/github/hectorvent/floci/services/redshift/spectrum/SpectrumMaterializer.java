package io.github.hectorvent.floci.services.redshift.spectrum;

import jakarta.enterprise.context.ApplicationScoped;

import io.github.hectorvent.floci.services.redshift.proxy.PostgresWireDecoder;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.HexFormat;
import java.util.Iterator;
import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.Stream;

@ApplicationScoped
public final class SpectrumMaterializer {

    private static final SecureRandom RANDOM = new SecureRandom();
    private static final String SQLSTATE_DATA = "22000";

    public Materialization materialize(Socket backend, SpectrumExternalTable table,
                                       SpectrumExternalSchema schema, SpectrumS3Reader reader) {
        return materialize(backend, table, schema, reader, nextIdentifier());
    }

    public Materialization materialize(Socket backend, SpectrumExternalTable table,
                                       SpectrumExternalSchema schema, SpectrumS3Reader reader, String identifier) {
        Materialization materialization = new Materialization(identifier, table.columns());
        OutputStream output = null;
        boolean tableCreated = false;
        boolean copyInProgress = false;
        try {
            output = backend.getOutputStream();
            sendQuery(output, createTableSql(identifier, table));
            awaitReady(backend);
            tableCreated = true;
            sendQuery(output, copySql(identifier, table));
            awaitCopyIn(backend);
            copyInProgress = true;
            try (Stream<SpectrumRow> rows = reader.read(schema, table)) {
                Iterator<SpectrumRow> iterator = rows.iterator();
                while (iterator.hasNext()) {
                    writeCopyData(output, encodeRow(iterator.next()));
                }
            }
            writeCopyDone(output);
            copyInProgress = false;
            awaitReady(backend);
            return materialization;
        } catch (SpectrumReadException exception) {
            recoverFromFailure(backend, materialization, output, tableCreated, copyInProgress, exception);
            throw exception;
        } catch (IOException exception) {
            SpectrumReadException readException = new SpectrumReadException(
                    SQLSTATE_DATA, "Unable to materialize Spectrum rows", exception);
            recoverFromFailure(backend, materialization, output, tableCreated, copyInProgress, readException);
            throw readException;
        }
    }

    public String nextIdentifier() {
        return "spectrum_tmp_" + HexFormat.of().formatHex(randomBytes(12));
    }

    public void cleanup(Socket backend, Materialization materialization) {
        try {
            OutputStream output = backend.getOutputStream();
            sendQuery(output, "DROP TABLE IF EXISTS \"" + quoteIdentifier(materialization.identifier()) + "\"");
            awaitReady(backend);
        } catch (IOException exception) {
            throw new SpectrumReadException(SQLSTATE_DATA, "Unable to clean up Spectrum materialization", exception);
        }
    }

    private static String createTableSql(String identifier, SpectrumExternalTable table) {
        StringBuilder sql = new StringBuilder("CREATE TEMP TABLE \"")
                .append(quoteIdentifier(identifier)).append("\" (");
        for (int i = 0; i < table.columns().size(); i++) {
            if (i > 0) {
                sql.append(", ");
            }
            SpectrumColumn column = table.columns().get(i);
            sql.append('"').append(quoteIdentifier(column.name())).append("\" ").append(column.type().postgresType());
        }
        return sql.append(')').toString();
    }

    private static String copySql(String identifier, SpectrumExternalTable table) {
        return "COPY \"" + quoteIdentifier(identifier) + "\" ("
                + table.columns().stream().map(column -> "\"" + quoteIdentifier(column.name()) + "\"")
                        .collect(Collectors.joining(", "))
                + ") FROM STDIN";
    }

    private static byte[] encodeRow(SpectrumRow row) {
        StringBuilder line = new StringBuilder();
        for (int i = 0; i < row.values().size(); i++) {
            if (i > 0) {
                line.append('\t');
            }
            String value = row.values().get(i);
            if (value == null) {
                line.append("\\N");
            } else {
                line.append(value.replace("\\", "\\\\").replace("\t", "\\t")
                        .replace("\n", "\\n").replace("\r", "\\r"));
            }
        }
        line.append('\n');
        return line.toString().getBytes(StandardCharsets.UTF_8);
    }

    private static void sendQuery(OutputStream output, String sql) throws IOException {
        output.write(PostgresWireDecoder.encodeQuery(sql));
        output.flush();
    }

    private static void writeCopyData(OutputStream output, byte[] data) throws IOException {
        output.write('d');
        writeInt32(output, data.length + 4);
        output.write(data);
    }

    private static void writeCopyDone(OutputStream output) throws IOException {
        output.write('c');
        writeInt32(output, 4);
        output.flush();
    }

    private static void writeCopyFail(OutputStream output, String reason) throws IOException {
        byte[] body = (reason == null ? "Spectrum row conversion failed" : reason).getBytes(StandardCharsets.UTF_8);
        output.write('f');
        writeInt32(output, body.length + 5);
        output.write(body);
        output.write(0);
        output.flush();
    }

    private static void writeInt32(OutputStream output, int value) throws IOException {
        output.write((value >>> 24) & 0xFF);
        output.write((value >>> 16) & 0xFF);
        output.write((value >>> 8) & 0xFF);
        output.write(value & 0xFF);
    }

    private static void awaitCopyIn(Socket backend) throws IOException {
        PostgresWireDecoder decoder = new PostgresWireDecoder(backend.getInputStream());
        PostgresWireDecoder.FrontendMessage message;
        SpectrumReadException backendFailure = null;
        while ((message = decoder.nextMessage()) != null) {
            if (message.type() == 'G') {
                return;
            }
            if (message.type() == 'E') {
                backendFailure = new SpectrumReadException(SQLSTATE_DATA, "PostgreSQL rejected Spectrum COPY");
            }
            if (message.type() == 'Z' && backendFailure != null) {
                throw backendFailure;
            }
        }
        throw new IOException("PostgreSQL closed before CopyInResponse");
    }

    private static void awaitReady(Socket backend) throws IOException {
        PostgresWireDecoder decoder = new PostgresWireDecoder(backend.getInputStream());
        PostgresWireDecoder.FrontendMessage message;
        SpectrumReadException backendFailure = null;
        while ((message = decoder.nextMessage()) != null) {
            if (message.type() == 'Z') {
                if (backendFailure != null) {
                    throw backendFailure;
                }
                return;
            }
            if (message.type() == 'E') {
                backendFailure = new SpectrumReadException(SQLSTATE_DATA, "PostgreSQL rejected Spectrum statement");
            }
        }
        throw new IOException("PostgreSQL closed before ReadyForQuery");
    }

    private void recoverFromFailure(Socket backend, Materialization materialization, OutputStream output,
                                    boolean tableCreated, boolean copyInProgress,
                                    SpectrumReadException original) {
        if (copyInProgress && output != null) {
            try {
                writeCopyFail(output, original.getMessage());
                awaitReady(backend);
            } catch (IOException | SpectrumReadException cleanupFailure) {
                original.addSuppressed(cleanupFailure);
            }
        }
        if (tableCreated) {
            try {
                cleanup(backend, materialization);
            } catch (RuntimeException cleanupFailure) {
                original.addSuppressed(cleanupFailure);
            }
        }
    }

    private static String quoteIdentifier(String value) {
        return value.replace("\"", "\"\"");
    }

    private static byte[] randomBytes(int length) {
        byte[] bytes = new byte[length];
        RANDOM.nextBytes(bytes);
        return bytes;
    }

    public record Materialization(String identifier, List<SpectrumColumn> columns) {
        public Materialization {
            columns = List.copyOf(columns);
        }
    }
}
