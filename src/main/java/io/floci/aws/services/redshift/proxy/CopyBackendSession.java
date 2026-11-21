package io.floci.aws.services.redshift.proxy;

import io.floci.aws.core.common.PostgresWire;
import io.floci.aws.services.redshift.RedshiftAuditSql;
import io.floci.aws.services.redshift.proxy.S3CopySimulator.S3TransferException;

import java.io.IOException;
import java.io.OutputStream;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Consumer;

/** Internal COPY commands use named extended statements so client statements and portals survive. */
final class CopyBackendSession {
    private final Socket backend;
    private final FrameSink asynchronous;
    private final String prefix = "floci_copy_" + UUID.randomUUID().toString().replace("-", "");
    private long sequence;
    private volatile boolean ownsTransaction;
    private boolean failed;
    private String activeStatement;
    private boolean parsed;
    private char readyStatus = 'I';
    private Consumer<CopyLoadError> loadErrorSink;

    void loadErrorSink(Consumer<CopyLoadError> sink) {
        loadErrorSink = sink;
    }

    Consumer<CopyLoadError> loadErrorSink() {
        return loadErrorSink;
    }

    CopyBackendSession(Socket backend, FrameSink asynchronous) {
        this.backend = backend;
        this.asynchronous = asynchronous;
    }

    char readyStatus() {
        return readyStatus;
    }

    boolean ownsTransaction() {
        return ownsTransaction;
    }

    boolean requiresCompletion() {
        return ownsTransaction || (failed && activeStatement != null);
    }

    void begin(boolean callerTransaction) throws IOException {
        if (!callerTransaction && !ownsTransaction) {
            execute("BEGIN");
            ownsTransaction = true;
        }
    }

    void onClientCommand(String sql, String tag) {
        boolean fullRollback = tag.equals("ROLLBACK") && RedshiftAuditSql.savepoint(sql) == null;
        if (tag.equals("BEGIN") || tag.equals("COMMIT") || fullRollback) {
            ownsTransaction = false;
        }
    }

    void onBackendError() {
        failed = true;
    }

    void onClientReady(char status) {
        failed = false;
        activeStatement = null;
        parsed = false;
        if (status == 'I') {
            ownsTransaction = false;
        }
    }

    void finish(boolean cycleFailed) throws IOException {
        recover();
        if (!ownsTransaction) {
            return;
        }
        try {
            execute(cycleFailed ? "ROLLBACK" : "COMMIT");
        } finally {
            ownsTransaction = false;
        }
    }

    void recover() throws IOException {
        if (!failed) {
            return;
        }
        synchronize();
        if (parsed && activeStatement != null) {
            write('C', new byte[]{'S'}, PostgresWire.cStringBytes(activeStatement));
            flush();
            while (read().type() != '3') {
                // A failed command's prepared statement survives transaction rollback.
            }
        }
        activeStatement = null;
        parsed = false;
    }

    void synchronize() throws IOException {
        write('S', new byte[0]);
        backend.getOutputStream().flush();
        while (true) {
            PostgresWireDecoder.FrontendMessage response = read();
            if (response.type() == 'Z') {
                readyStatus = (char) response.body()[0];
                break;
            }
        }
        failed = false;
    }

    void failTransaction() throws IOException {
        try {
            execute("SELECT 1 / 0");
        } catch (S3TransferException expected) {
            // Preserve COPY's original error while leaving an explicit transaction failed.
            if (!"22012".equals(expected.sqlState()) && !"25P02".equals(expected.sqlState())) {
                throw expected;
            }
        }
    }

    void execute(String sql) throws IOException {
        query(sql);
    }

    List<List<String>> query(String sql) throws IOException {
        String name = start(sql);
        List<List<String>> rows = new ArrayList<>();
        while (true) {
            PostgresWireDecoder.FrontendMessage response = read();
            if (response.type() == 'D') {
                rows.add(S3CopySimulator.parseDataRow(response.body()));
            } else if (response.type() == 'C' || response.type() == 'I') {
                close(name);
                return rows;
            }
        }
    }

    long copy(String sql, CopyStream stream) throws IOException {
        String name = start(sql);
        while (read().type() != 'G') {
            // ParseComplete and BindComplete precede CopyInResponse.
        }
        try {
            stream.write(backend.getOutputStream());
        } catch (IOException | RuntimeException exception) {
            write('f', PostgresWire.cStringBytes("S3 COPY input failed"));
            backend.getOutputStream().flush();
            try {
                while (read().type() != 'E') {
                    // CopyFail must be consumed before recovery can issue an internal Sync.
                }
            } catch (S3TransferException expected) {
                if (!"57014".equals(expected.sqlState())) {
                    // A server failure may already be queued before the producer fails on a later record.
                    expected.addSuppressed(exception);
                    throw expected;
                }
                exception.addSuppressed(expected);
            }
            throw exception;
        }
        write('c', new byte[0]);
        flush();
        while (true) {
            PostgresWireDecoder.FrontendMessage response = read();
            if (response.type() == 'C') {
                String tag = new String(response.body(), StandardCharsets.UTF_8).replace("\0", "");
                close(name);
                return Long.parseLong(tag.substring(tag.lastIndexOf(' ') + 1));
            }
        }
    }

    private String start(String sql) throws IOException {
        String name = prefix + "_" + sequence++;
        activeStatement = name;
        parsed = false;
        write('P', PostgresWire.cStringBytes(name), PostgresWire.cStringBytes(sql), new byte[2]);
        write('B', PostgresWire.cStringBytes(name), PostgresWire.cStringBytes(name), new byte[6]);
        write('E', PostgresWire.cStringBytes(name), new byte[4]);
        flush();
        return name;
    }

    private void close(String name) throws IOException {
        write('C', new byte[]{'P'}, PostgresWire.cStringBytes(name));
        write('C', new byte[]{'S'}, PostgresWire.cStringBytes(name));
        flush();
        for (int closed = 0; closed < 2;) {
            if (read().type() == '3') {
                closed++;
            }
        }
        activeStatement = null;
        parsed = false;
    }

    private PostgresWireDecoder.FrontendMessage read() throws IOException {
        PostgresWireDecoder decoder = new PostgresWireDecoder(backend.getInputStream());
        while (true) {
            PostgresWireDecoder.FrontendMessage response = decoder.nextMessage();
            if (response == null) {
                throw new IOException("Backend closed during COPY execution");
            }
            if (response.type() == 'N' || response.type() == 'A' || response.type() == 'S') {
                asynchronous.accept(response);
                continue;
            }
            if (response.type() == 'E') {
                failed = true;
                Map<Character, String> fields = PostgresWire.parseFields(response.body(), 0);
                throw new S3TransferException(fields.getOrDefault('C', "XX000"),
                        fields.getOrDefault('M', "COPY backend command failed"), null, fields);
            }
            if (response.type() == '1') {
                parsed = true;
            }
            return response;
        }
    }

    private void flush() throws IOException {
        write('H', new byte[0]);
        backend.getOutputStream().flush();
    }

    private void write(char type, byte[]... parts) throws IOException {
        PostgresWire.write(backend.getOutputStream(), type, parts);
    }

    @FunctionalInterface
    interface CopyStream {
        void write(OutputStream output) throws IOException;
    }

    @FunctionalInterface
    interface FrameSink {
        void accept(PostgresWireDecoder.FrontendMessage response) throws IOException;
    }
}
