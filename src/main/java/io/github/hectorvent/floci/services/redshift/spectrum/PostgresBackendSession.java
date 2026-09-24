package io.github.hectorvent.floci.services.redshift.spectrum;

import io.github.hectorvent.floci.services.redshift.proxy.PostgresWireDecoder;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.Socket;
import java.nio.charset.StandardCharsets;

public final class PostgresBackendSession implements BackendSql {
    private static final String SQLSTATE_IO = "58030";
    private final Socket backend;

    public PostgresBackendSession(Socket backend) {
        this.backend = backend;
    }

    @Override
    public void execute(String sql) {
        try {
            OutputStream output = backend.getOutputStream();
            output.write(PostgresWireDecoder.encodeQuery(sql));
            output.flush();
            awaitReady(new PostgresWireDecoder(backend.getInputStream()));
        } catch (IOException exception) {
            throw new SpectrumReadException(SQLSTATE_IO, "Unable to run a statement on the Redshift backend", exception);
        }
    }

    @Override
    public long copyIn(String copySql, InputStream data) {
        try {
            OutputStream output = backend.getOutputStream();
            output.write(PostgresWireDecoder.encodeQuery(copySql));
            output.flush();
            PostgresWireDecoder decoder = new PostgresWireDecoder(backend.getInputStream());
            awaitCopyIn(decoder);
            byte[] chunk = new byte[8192];
            try {
                int read = data.read(chunk);
                while (read > 0) {
                    output.write('d');
                    writeInt32(output, read + 4);
                    output.write(chunk, 0, read);
                    read = data.read(chunk);
                }
            } catch (IOException exception) {
                writeCopyFail(output, exception.getMessage());
                awaitReady(decoder);
                throw exception;
            }
            output.write('c');
            writeInt32(output, 4);
            output.flush();
            return rowCount(awaitReady(decoder));
        } catch (IOException exception) {
            throw new SpectrumReadException(SQLSTATE_IO, "Unable to load external table data", exception);
        }
    }

    private static void awaitCopyIn(PostgresWireDecoder decoder) throws IOException {
        SpectrumReadException failure = null;
        PostgresWireDecoder.FrontendMessage message = decoder.nextMessage();
        while (message != null) {
            if (message.type() == 'G') {
                if (failure != null) {
                    throw failure;
                }
                return;
            }
            if (message.type() == 'E') {
                failure = toException(message.body());
            } else if (message.type() == 'Z') {
                if (failure != null) {
                    throw failure;
                }
                throw new IOException("PostgreSQL did not enter CopyIn mode");
            }
            message = decoder.nextMessage();
        }
        throw new IOException("PostgreSQL closed before CopyInResponse");
    }

    private static String awaitReady(PostgresWireDecoder decoder) throws IOException {
        SpectrumReadException failure = null;
        String tag = "";
        PostgresWireDecoder.FrontendMessage message = decoder.nextMessage();
        while (message != null) {
            if (message.type() == 'C') {
                tag = new String(message.body(), StandardCharsets.UTF_8).trim();
            } else if (message.type() == 'E') {
                failure = toException(message.body());
            } else if (message.type() == 'Z') {
                if (failure != null) {
                    throw failure;
                }
                return tag;
            }
            message = decoder.nextMessage();
        }
        throw new IOException("PostgreSQL closed before ReadyForQuery");
    }

    private static long rowCount(String tag) {
        int space = tag.lastIndexOf(' ');
        if (space < 0) {
            return 0;
        }
        try {
            return Long.parseLong(tag.substring(space + 1).trim());
        } catch (NumberFormatException exception) {
            throw new SpectrumReadException(SQLSTATE_IO, "Unexpected COPY command tag: " + tag, exception);
        }
    }

    private static SpectrumReadException toException(byte[] body) {
        String state = SQLSTATE_IO;
        String message = "PostgreSQL rejected the statement";
        int offset = 0;
        while (body != null && offset < body.length && body[offset] != 0) {
            char code = (char) body[offset++];
            int end = offset;
            while (end < body.length && body[end] != 0) {
                end++;
            }
            String value = new String(body, offset, end - offset, StandardCharsets.UTF_8);
            if (code == 'C') {
                state = value;
            } else if (code == 'M') {
                message = value;
            }
            offset = end + 1;
        }
        return new SpectrumReadException(state, message);
    }

    private static void writeInt32(OutputStream output, int value) throws IOException {
        output.write((value >>> 24) & 0xff);
        output.write((value >>> 16) & 0xff);
        output.write((value >>> 8) & 0xff);
        output.write(value & 0xff);
    }

    private static void writeCopyFail(OutputStream output, String message) throws IOException {
        byte[] detail = (message == null ? "Unable to stream COPY input" : message)
                .getBytes(StandardCharsets.UTF_8);
        output.write('f');
        writeInt32(output, detail.length + 5);
        output.write(detail);
        output.write(0);
        output.flush();
    }
}
