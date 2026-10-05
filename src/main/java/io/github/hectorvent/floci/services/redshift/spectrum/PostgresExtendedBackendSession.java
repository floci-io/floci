package io.github.hectorvent.floci.services.redshift.spectrum;

import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.UUID;

/** Runs preparation without ending the client's transaction or destroying its portals. */
public final class PostgresExtendedBackendSession implements BackendSql {
    private final Socket backend;
    private String name;
    private volatile boolean failedUntilSync;

    public PostgresExtendedBackendSession(Socket backend) {
        this.backend = backend;
    }

    @Override
    public boolean permitsCachePublication() {
        return false;
    }

    /** The client's own pipelined statement failed; the backend discards everything until Sync. */
    public void onBackendError() {
        failedUntilSync = true;
    }

    public void onSync() {
        failedUntilSync = false;
    }

    @Override
    public void execute(String sql) {
        try {
            start(atomicCommand(sql));
            closeAndFlush();
            awaitClosed();
        } catch (IOException exception) {
            throw failure(exception);
        }
    }

    public static String atomicCommand(String sql) {
        if (!sql.startsWith("CREATE ") && !sql.startsWith("DROP ")) {
            return sql;
        }
        String delimiter = "$floci_" + UUID.randomUUID().toString().replace("-", "") + "$";
        return "DO " + delimiter + " BEGIN " + sql + "; END " + delimiter;
    }

    @Override
    public long copyIn(String sql, InputStream data) {
        try {
            start(sql);
            frame('H', new byte[0]);
            backend.getOutputStream().flush();
            readUntil('G');
            byte[] chunk = new byte[8192];
            try {
                int count;
                while ((count = data.read(chunk)) >= 0) {
                    if (count > 0) {
                        frame('d', chunk, count);
                    }
                }
            } catch (IOException exception) {
                frame('f', "Unable to read external data\0".getBytes(StandardCharsets.UTF_8));
                backend.getOutputStream().flush();
                try {
                    readUntil('C');
                } catch (SpectrumReadException expected) {
                    // COPY failure leaves recovery to the client's Sync.
                    exception.addSuppressed(expected);
                }
                throw exception;
            }
            frame('c', new byte[0]);
            closeAndFlush();
            String tag = awaitClosed();
            return Long.parseLong(tag.substring(tag.lastIndexOf(' ') + 1).trim());
        } catch (IOException exception) {
            throw failure(exception);
        }
    }

    private void start(String sql) throws IOException {
        if (failedUntilSync) {
            throw new SpectrumReadException("25P02", "Preparation is waiting for Sync after a backend error");
        }
        name = "floci_prepare_" + UUID.randomUUID().toString().replace("-", "");
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        DataOutputStream body = new DataOutputStream(bytes);
        cstring(body, name);
        cstring(body, sql);
        body.writeShort(0);
        frame('P', bytes.toByteArray());
        bytes.reset();
        cstring(body, name);
        cstring(body, name);
        body.writeShort(0);
        body.writeShort(0);
        body.writeShort(0);
        frame('B', bytes.toByteArray());
        bytes.reset();
        cstring(body, name);
        body.writeInt(0);
        frame('E', bytes.toByteArray());
    }

    private void closeAndFlush() throws IOException {
        frame('C', ("P" + name + "\0").getBytes(StandardCharsets.UTF_8));
        frame('C', ("S" + name + "\0").getBytes(StandardCharsets.UTF_8));
        frame('H', new byte[0]);
        backend.getOutputStream().flush();
    }

    private String awaitClosed() throws IOException {
        String tag = "";
        for (int closed = 0; closed < 2;) {
            Response response = read();
            if (response.type() == '3') {
                closed++;
            } else if (response.type() == 'C') {
                tag = new String(response.body(), StandardCharsets.UTF_8).replace("\0", "");
            }
        }
        return tag;
    }

    private void readUntil(char type) throws IOException {
        Response response;
        do {
            // ParseComplete, BindComplete and asynchronous messages precede the response.
            response = read();
            if (response.type() == 'Z') {
                throw new IOException("Backend ended preparation before sending " + type);
            }
        } while (response.type() != type);
    }

    private Response read() throws IOException {
        DataInputStream input = new DataInputStream(backend.getInputStream());
        char type = (char) input.readUnsignedByte();
        int length = input.readInt();
        if (length < 4 || length > 64 * 1024 * 1024) {
            throw new IOException("Invalid preparation response length");
        }
        byte[] body = input.readNBytes(length - 4);
        if (body.length != length - 4) {
            throw new IOException("Backend closed during preparation");
        }
        if (type == 'E') {
            failedUntilSync = true;
            String state = "58030";
            String message = "External query preparation failed";
            for (int offset = 0; offset < body.length && body[offset] != 0;) {
                char field = (char) body[offset++];
                int start = offset;
                while (offset < body.length && body[offset] != 0) {
                    offset++;
                }
                String value = new String(body, start, offset - start, StandardCharsets.UTF_8);
                if (field == 'C') {
                    state = value;
                } else if (field == 'M') {
                    message = value;
                }
                offset++;
            }
            throw new SpectrumReadException(state, message);
        }
        return new Response(type, body);
    }

    private void frame(char type, byte[] body) throws IOException {
        frame(type, body, body.length);
    }

    /** Writes the frame in one call; a split write would put a header packet on the wire of its own. */
    private void frame(char type, byte[] body, int length) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream(length + 5);
        DataOutputStream output = new DataOutputStream(bytes);
        output.writeByte(type);
        output.writeInt(length + 4);
        output.write(body, 0, length);
        backend.getOutputStream().write(bytes.toByteArray());
    }

    private static void cstring(DataOutputStream output, String text) throws IOException {
        output.write(text.getBytes(StandardCharsets.UTF_8));
        output.writeByte(0);
    }

    private static SpectrumReadException failure(IOException exception) {
        return new SpectrumReadException("58030", "Unable to prepare external query", exception);
    }

    private record Response(char type, byte[] body) { }
}
