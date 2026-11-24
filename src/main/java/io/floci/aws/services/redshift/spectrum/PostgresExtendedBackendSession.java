package io.floci.aws.services.redshift.spectrum;

import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
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

    @Override
    public boolean usesSubtransactionCollect() {
        return true;
    }

    @Override
    public List<Integer> resolveParameterTypeOids(String sql, List<Integer> declaredTypeOids, int parameterCount) {
        if (declaredTypeOids != null && declaredTypeOids.size() == parameterCount && !declaredTypeOids.contains(0)) {
            return declaredTypeOids;
        }
        try {
            if (failedUntilSync) {
                throw new SpectrumReadException("25P02", "Preparation is waiting for Sync after a backend error");
            }
            name = "floci_prepare_" + UUID.randomUUID().toString().replace("-", "");
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            DataOutputStream body = new DataOutputStream(bytes);
            cstring(body, name);
            cstring(body, sql);
            List<Integer> declared = declaredTypeOids == null ? List.of() : declaredTypeOids;
            body.writeShort(declared.size());
            for (Integer oid : declared) {
                body.writeInt(oid == null ? 0 : oid);
            }
            frame('P', bytes.toByteArray());
            frame('D', ("S" + name + "\0").getBytes(StandardCharsets.UTF_8));
            frame('C', ("S" + name + "\0").getBytes(StandardCharsets.UTF_8));
            frame('H', new byte[0]);
            backend.getOutputStream().flush();
            return awaitParameterDescriptionClosed();
        } catch (IOException exception) {
            PostgresBackendSession.abandon(backend, exception);
            throw failure(exception);
        }
    }

    @Override
    public Optional<String> queryString(String sql, RawParameters parameters) {
        if (parameters == null || parameters.count() == 0) {
            return queryString(sql);
        }
        try {
            startRaw(sql, parameters);
            closeAndFlush();
            return awaitSingleStringClosed();
        } catch (IOException exception) {
            PostgresBackendSession.abandon(backend, exception);
            throw failure(exception);
        }
    }

    @Override
    public Object connectionKey() {
        return backend;
    }

    /** The client's own pipelined statement failed; the backend discards everything until Sync. */
    public void onBackendError() {
        failedUntilSync = true;
    }

    public void onSync() {
        failedUntilSync = false;
    }

    @Override
    public Optional<String> queryString(String sql) {
        try {
            start(sql, List.of());
            closeAndFlush();
            return awaitSingleStringClosed();
        } catch (IOException exception) {
            PostgresBackendSession.abandon(backend, exception);
            throw failure(exception);
        }
    }

    @Override
    public List<List<String>> queryRows(String sql) {
        try {
            start(sql, List.of());
            closeAndFlush();
            return awaitRowsClosed();
        } catch (IOException exception) {
            PostgresBackendSession.abandon(backend, exception);
            throw failure(exception);
        }
    }

    @Override
    public void execute(String sql) {
        try {
            start(atomicCommand(sql), List.of());
            closeAndFlush();
            awaitClosed();
        } catch (IOException exception) {
            throw failure(exception);
        }
    }

    @Override
    public void execute(String sql, List<String> parameters) {
        if (parameters == null || parameters.isEmpty()) {
            execute(sql);
            return;
        }
        try {
            start(sql, parameters);
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
            start(sql, List.of());
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

    private void start(String sql, List<String> parameters) throws IOException {
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
        body.writeShort(parameters.size());
        for (String value : parameters) {
            if (value == null) {
                body.writeInt(-1);
            } else {
                byte[] utf8 = value.getBytes(StandardCharsets.UTF_8);
                body.writeInt(utf8.length);
                body.write(utf8);
            }
        }
        body.writeShort(0);
        frame('B', bytes.toByteArray());
        bytes.reset();
        cstring(body, name);
        body.writeInt(0);
        frame('E', bytes.toByteArray());
    }

    private void startRaw(String sql, RawParameters parameters) throws IOException {
        if (failedUntilSync) {
            throw new SpectrumReadException("25P02", "Preparation is waiting for Sync after a backend error");
        }
        name = "floci_prepare_" + UUID.randomUUID().toString().replace("-", "");
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        DataOutputStream body = new DataOutputStream(bytes);
        cstring(body, name);
        cstring(body, sql);
        List<Integer> typeOids = parameters.typeOids();
        body.writeShort(typeOids.size());
        for (Integer oid : typeOids) {
            body.writeInt(oid == null ? 0 : oid);
        }
        frame('P', bytes.toByteArray());
        bytes.reset();
        cstring(body, name);
        cstring(body, name);
        body.write(parameters.bindSection());
        body.writeShort(0);
        frame('B', bytes.toByteArray());
        bytes.reset();
        cstring(body, name);
        body.writeInt(0);
        frame('E', bytes.toByteArray());
    }

    private List<Integer> awaitParameterDescriptionClosed() throws IOException {
        List<Integer> oids = List.of();
        while (true) {
            Response response = read();
            if (response.type() == 't') {
                oids = decodeParameterDescription(response.body());
            } else if (response.type() == '3') {
                return oids;
            }
        }
    }

    private static List<Integer> decodeParameterDescription(byte[] body) throws IOException {
        if (body == null || body.length < 2) {
            throw new IOException("Invalid ParameterDescription response");
        }
        int count = ((body[0] & 0xff) << 8) | (body[1] & 0xff);
        if (body.length < 2 + count * 4) {
            throw new IOException("Truncated ParameterDescription response");
        }
        List<Integer> oids = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            int offset = 2 + i * 4;
            int oid = ((body[offset] & 0xff) << 24)
                    | ((body[offset + 1] & 0xff) << 16)
                    | ((body[offset + 2] & 0xff) << 8)
                    | (body[offset + 3] & 0xff);
            oids.add(oid);
        }
        return List.copyOf(oids);
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

    private Optional<String> awaitSingleStringClosed() throws IOException {
        String value = null;
        boolean seenRow = false;
        for (int closed = 0; closed < 2;) {
            Response response = read();
            if (response.type() == 'D' && !seenRow) {
                seenRow = true;
                value = PostgresBackendSession.firstColumn(response.body());
            } else if (response.type() == '3') {
                closed++;
            }
        }
        return Optional.ofNullable(value);
    }

    private List<List<String>> awaitRowsClosed() throws IOException {
        List<List<String>> rows = new ArrayList<>();
        for (int closed = 0; closed < 2;) {
            Response response = read();
            if (response.type() == 'D') {
                rows.add(PostgresBackendSession.decodeRow(response.body()));
            } else if (response.type() == '3') {
                closed++;
            }
        }
        return rows;
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
