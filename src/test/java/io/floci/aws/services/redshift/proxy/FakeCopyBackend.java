package io.floci.aws.services.redshift.proxy;

import io.floci.aws.core.common.PostgresWire;

import java.io.OutputStream;
import java.net.Socket;
import java.util.HashMap;
import java.util.Map;

/** Extended-query backend for COPY tests, including catalog discovery and transaction recovery. */
final class FakeCopyBackend {
    private final Socket socket;

    FakeCopyBackend(Socket socket) {
        this.socket = socket;
    }

    void serveUntilLoadComplete(OutputStream copied) throws Exception {
        serve(copied, true, false, sql -> { });
    }

    void serveUntilSync(boolean failCommit, StatementObserver observer) throws Exception {
        serve(OutputStream.nullOutputStream(), false, failCommit, observer);
    }

    private void serve(OutputStream copied, boolean stopAfterLoad, boolean failCommit,
                       StatementObserver observer) throws Exception {
        PostgresWireDecoder decoder = new PostgresWireDecoder(socket.getInputStream());
        OutputStream out = socket.getOutputStream();
        Map<String, String> statements = new HashMap<>();
        Map<String, String> portals = new HashMap<>();
        boolean transaction = false;
        boolean failed = false;
        boolean copyCompleted = false;
        boolean finishing = false;
        int closed = 0;
        PostgresWireDecoder.FrontendMessage message;
        while ((message = decoder.nextMessage()) != null) {
            if (failed && message.type() != 'S') {
                continue;
            }
            switch (message.type()) {
                case 'P' -> {
                    PostgresWireDecoder.ParseMessage parsed = decoder.decodeParse(message);
                    statements.put(parsed.statementName(), parsed.sql());
                    PostgresWire.write(out, '1');
                }
                case 'B' -> {
                    PostgresWireDecoder.BindMessage bound = decoder.decodeBind(message);
                    portals.put(bound.portalName(), statements.get(bound.statementName()));
                    PostgresWire.write(out, '2');
                }
                case 'E' -> {
                    String sql = portals.get(decoder.decodeExecute(message).portalName());
                    if (sql.equals("BEGIN")) {
                        transaction = true;
                    } else if (sql.equals("COMMIT") || sql.equals("ROLLBACK")) {
                        transaction = false;
                    }
                    if (failCommit && sql.equals("COMMIT")) {
                        PostgresWire.write(out, 'E', S3CopySimulator.errorBody("23505", "deferred constraint violation"));
                        failed = true;
                    } else if (sql.startsWith("SAVEPOINT ") && !transaction) {
                        PostgresWire.write(out, 'E', S3CopySimulator.errorBody("25P01", "SAVEPOINT requires a transaction"));
                        failed = true;
                    } else if (sql.equals("SELECT 1 / 0")) {
                        PostgresWire.write(out, 'E', S3CopySimulator.errorBody("22012", "division by zero"));
                        failed = true;
                    } else if (sql.startsWith("COPY ")) {
                        PostgresWire.write(out, 'G', new byte[]{0, 0, 0});
                    } else {
                        if (sql.startsWith("SELECT a.attname")) {
                            PostgresWire.write(out, 'D', new byte[]{0, 3}, PostgresWire.int32(2), new byte[]{'i', 'd'},
                                    PostgresWire.int32(-1), PostgresWire.int32(2), new byte[]{'2', '3'});
                        }
                        finishing = stopAfterLoad && copyCompleted && sql.startsWith("RELEASE SAVEPOINT floci_load_");
                        String tag = sql.startsWith("ROLLBACK") ? "ROLLBACK" : sql.split(" ")[0];
                        PostgresWire.write(out, 'C', PostgresWire.cStringBytes(tag));
                        out.flush();
                        observer.executed(sql);
                    }
                }
                case 'd' -> copied.write(message.body());
                case 'c' -> {
                    PostgresWire.write(out, 'C', PostgresWire.cStringBytes("COPY 1"));
                    copyCompleted = true;
                }
                case 'f' -> {
                    PostgresWire.write(out, 'E', S3CopySimulator.errorBody("57014", "COPY input failed"));
                    failed = true;
                }
                case 'C' -> {
                    PostgresWire.write(out, '3');
                    if (finishing) {
                        closed++;
                    }
                }
                case 'S' -> {
                    PostgresWire.write(out, 'Z', new byte[]{(byte) (transaction ? 'T' : 'I')});
                    out.flush();
                    if (!failed) {
                        return;
                    }
                    failed = false;
                }
                case 'H' -> { }
                default -> throw new AssertionError("Unexpected wire message " + message.type());
            }
            out.flush();
            if (stopAfterLoad && (failed || message.type() == 'H' && finishing && closed == 2)) {
                return;
            }
        }
    }

    @FunctionalInterface
    interface StatementObserver {
        void executed(String sql) throws Exception;
    }
}
