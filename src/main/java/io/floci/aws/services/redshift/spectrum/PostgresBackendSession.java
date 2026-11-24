package io.floci.aws.services.redshift.spectrum;

import io.floci.aws.services.redshift.proxy.PostgresWireDecoder;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

public final class PostgresBackendSession implements BackendSql {
    private static final String SQLSTATE_IO = "58030";
    private final Socket backend;

    public PostgresBackendSession(Socket backend) {
        this.backend = backend;
    }

    @Override
    public Object connectionKey() {
        return backend;
    }

    @Override
    public Optional<String> queryString(String sql) {
        try {
            OutputStream output = backend.getOutputStream();
            output.write(PostgresWireDecoder.encodeQuery(sql));
            output.flush();
            return awaitSingleString(new PostgresWireDecoder(backend.getInputStream()));
        } catch (IOException exception) {
            abandon(backend, exception);
            throw new SpectrumReadException(SQLSTATE_IO, "Unable to run a statement on the Redshift backend", exception);
        }
    }

    @Override
    public List<List<String>> queryRows(String sql) {
        try {
            OutputStream output = backend.getOutputStream();
            output.write(PostgresWireDecoder.encodeQuery(sql));
            output.flush();
            return awaitRows(new PostgresWireDecoder(backend.getInputStream()));
        } catch (IOException exception) {
            abandon(backend, exception);
            throw new SpectrumReadException(SQLSTATE_IO, "Unable to run a statement on the Redshift backend", exception);
        }
    }

    /**
     * Closes a backend whose private exchange did not finish. Its replies may still arrive, and the response pump
     * would forward them to the client as if the client had asked; a closed connection is the one state where
     * nothing can be misattributed.
     */
    static void abandon(Socket backend, IOException cause) {
        try {
            backend.close();
        } catch (IOException closeFailure) {
            cause.addSuppressed(closeFailure);
        }
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
            int read = readChunk(output, decoder, data, chunk);
            while (read > 0) {
                output.write('d');
                writeInt32(output, read + 4);
                output.write(chunk, 0, read);
                read = readChunk(output, decoder, data, chunk);
            }
            output.write('c');
            writeInt32(output, 4);
            output.flush();
            return rowCount(awaitReady(decoder));
        } catch (IOException exception) {
            throw new SpectrumReadException(SQLSTATE_IO, "Unable to load external table data", exception);
        }
    }

    /** A failure reading the source aborts the COPY on the backend; a failure writing to the backend socket does not. */
    private static int readChunk(OutputStream output, PostgresWireDecoder decoder, InputStream data, byte[] chunk)
            throws IOException {
        try {
            int read = data.read(chunk);
            if (read != 0) {
                return read;
            }
            int nextByte = data.read();
            if (nextByte < 0) {
                return -1;
            }
            chunk[0] = (byte) nextByte;
            return 1;
        } catch (IOException exception) {
            writeCopyFail(output, exception.getMessage());
            try {
                awaitReady(decoder);
            } catch (IOException | SpectrumReadException backendFailure) {
                exception.addSuppressed(backendFailure);
            }
            throw exception;
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

    private static Optional<String> awaitSingleString(PostgresWireDecoder decoder) throws IOException {
        SpectrumReadException failure = null;
        String value = null;
        boolean seenRow = false;
        PostgresWireDecoder.FrontendMessage message = decoder.nextMessage();
        while (message != null) {
            if (message.type() == 'D' && !seenRow) {
                seenRow = true;
                value = firstColumn(message.body());
            } else if (message.type() == 'E') {
                failure = toException(message.body());
            } else if (message.type() == 'Z') {
                if (failure != null) {
                    throw failure;
                }
                return Optional.ofNullable(value);
            }
            message = decoder.nextMessage();
        }
        throw new IOException("PostgreSQL closed before ReadyForQuery");
    }

    private static List<List<String>> awaitRows(PostgresWireDecoder decoder) throws IOException {
        SpectrumReadException failure = null;
        List<List<String>> rows = new ArrayList<>();
        PostgresWireDecoder.FrontendMessage message = decoder.nextMessage();
        while (message != null) {
            if (message.type() == 'D') {
                rows.add(decodeRow(message.body()));
            } else if (message.type() == 'E') {
                failure = toException(message.body());
            } else if (message.type() == 'Z') {
                if (failure != null) {
                    throw failure;
                }
                return rows;
            }
            message = decoder.nextMessage();
        }
        throw new IOException("PostgreSQL closed before ReadyForQuery");
    }

    static List<String> decodeRow(byte[] body) {
        if (body == null || body.length < 2) {
            return List.of();
        }
        int columns = ((body[0] & 0xff) << 8) | (body[1] & 0xff);
        List<String> row = new ArrayList<>(columns);
        int offset = 2;
        for (int i = 0; i < columns; i++) {
            if (offset + 4 > body.length) {
                break;
            }
            int length = ((body[offset] & 0xff) << 24) | ((body[offset + 1] & 0xff) << 16)
                    | ((body[offset + 2] & 0xff) << 8) | (body[offset + 3] & 0xff);
            offset += 4;
            if (length < 0) {
                row.add(null);
            } else if (offset + length <= body.length) {
                row.add(new String(body, offset, length, StandardCharsets.UTF_8));
                offset += length;
            } else {
                row.add(null);
                break;
            }
        }
        return row;
    }

    static String firstColumn(byte[] body) {
        if (body == null || body.length < 6) {
            return null;
        }
        int columns = ((body[0] & 0xff) << 8) | (body[1] & 0xff);
        if (columns < 1) {
            return null;
        }
        int length = ((body[2] & 0xff) << 24) | ((body[3] & 0xff) << 16)
                | ((body[4] & 0xff) << 8) | (body[5] & 0xff);
        if (length < 0 || body.length < 6 + length) {
            return null;
        }
        return new String(body, 6, length, StandardCharsets.UTF_8);
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
