package io.floci.aws.services.redshift.proxy;

import io.floci.aws.core.common.PostgresWire;

import java.io.ByteArrayOutputStream;
import java.io.OutputStream;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.function.ToLongFunction;

/** Extended-query backend for COPY tests, including catalog discovery and transaction recovery. */
final class FakeCopyBackend {
    private final Socket socket;
    private Function<String, List<List<String>>> rows = sql -> List.of();
    private Predicate<String> rejectRow = row -> false;
    private ToLongFunction<String> rowCount = row -> 1;
    private int copyCommands;
    private int rowSavepoints;
    private int reportedErrorLine;

    void reportedErrorLine(int line) {
        reportedErrorLine = line;
    }

    void countRowsWith(ToLongFunction<String> counter) {
        rowCount = counter;
    }

    int copyCommands() {
        return copyCommands;
    }

    int rowSavepoints() {
        return rowSavepoints;
    }

    void rejectRows(Predicate<String> predicate) {
        rejectRow = predicate;
    }

    FakeCopyBackend(Socket socket) {
        this.socket = socket;
    }

    void serveUntilLoadComplete(OutputStream copied) throws Exception {
        serve(copied, true, false, sql -> { });
    }

    void serveUntilSync(boolean failCommit, StatementObserver observer) throws Exception {
        serve(OutputStream.nullOutputStream(), false, failCommit, observer);
    }

    void serveUntilSync(OutputStream copied, StatementObserver observer,
                        Function<String, List<List<String>>> rows) throws Exception {
        this.rows = rows;
        serve(copied, false, false, observer);
    }

    private void serve(OutputStream copied, boolean stopAfterLoad, boolean failCommit,
                       StatementObserver observer) throws Exception {
        socket.setTcpNoDelay(true);
        PostgresWireDecoder decoder = new PostgresWireDecoder(socket.getInputStream());
        OutputStream out = socket.getOutputStream();
        Map<String, String> statements = new HashMap<>();
        Map<String, String> portals = new HashMap<>();
        boolean transaction = false;
        boolean failed = false;
        boolean copyCompleted = false;
        boolean finishing = false;
        int closed = 0;
        boolean csvCopy = false;
        ByteArrayOutputStream copyRecord = new ByteArrayOutputStream();
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
                    if (sql.startsWith("SAVEPOINT floci_row_")) {
                        rowSavepoints++;
                    }
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
                        copyCommands++;
                        csvCopy = sql.contains("FORMAT csv");
                        PostgresWire.write(out, 'G', new byte[]{0, 0, 0});
                    } else {
                        List<List<String>> queryRows = rows.apply(sql);
                        for (List<String> row : queryRows) {
                            PostgresWire.write(out, 'D', dataRow(row));
                        }
                        if (sql.startsWith("SELECT a.attname") && queryRows.isEmpty()) {
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
                case 'd' -> copyRecord.write(message.body());
                case 'c' -> {
                    String content = copyRecord.toString(StandardCharsets.UTF_8);
                    List<String> records = content.lines().toList();
                    int rejected = -1;
                    for (int index = 0; index < records.size(); index++) {
                        if (rejectRow.test(records.get(index))) {
                            rejected = index;
                            break;
                        }
                    }
                    if (rejected >= 0) {
                        int errorLine = rejected + 1;
                        if (!csvCopy) {
                            for (int index = 0; index < rejected; index++) {
                                String prior = records.get(index);
                                int slashes = 0;
                                for (int offset = prior.length() - 1; offset >= 0 && prior.charAt(offset) == '\\'; offset--) {
                                    slashes++;
                                }
                                if ((slashes % 2) != 0) {
                                    errorLine--;
                                }
                            }
                        }
                        recordError(out, reportedErrorLine > 0 ? reportedErrorLine : errorLine);
                        failed = true;
                    } else {
                        copied.write(copyRecord.toByteArray());
                        PostgresWire.write(out, 'C', PostgresWire.cStringBytes("COPY " + rowCount.applyAsLong(content)));
                    }
                    copyRecord.reset();
                    copyCompleted = true;
                }
                case 'f' -> {
                    if (copyRecord.toString(StandardCharsets.UTF_8).lines().anyMatch(rejectRow)) {
                        recordError(out, 1);
                    } else {
                        PostgresWire.write(out, 'E', S3CopySimulator.errorBody("57014", "COPY input failed"));
                    }
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

    private static void recordError(OutputStream out, int line) throws Exception {
        byte[] error = S3CopySimulator.errorBody("22P02", "invalid integer");
        PostgresWire.write(out, 'E', Arrays.copyOf(error, error.length - 1),
                new byte[]{'W'}, PostgresWire.cStringBytes("COPY events, line " + line
                        + ", column id: \"bad\""), new byte[1]);
    }

    private static byte[] dataRow(List<String> row) throws Exception {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        bytes.write(new byte[]{(byte) (row.size() >> 8), (byte) row.size()});
        for (String value : row) {
            if (value == null) {
                bytes.write(PostgresWire.int32(-1));
                continue;
            }
            byte[] field = value.getBytes(StandardCharsets.UTF_8);
            bytes.write(PostgresWire.int32(field.length));
            bytes.write(field);
        }
        return bytes.toByteArray();
    }
}
