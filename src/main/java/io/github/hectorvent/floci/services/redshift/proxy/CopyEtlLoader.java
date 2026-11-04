package io.github.hectorvent.floci.services.redshift.proxy;

import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.core.common.PostgresWire;
import io.github.hectorvent.floci.core.common.RequestScopes;
import io.github.hectorvent.floci.services.iam.IamService;
import io.github.hectorvent.floci.services.redshift.proxy.S3CopySimulator.ColumnInfo;
import io.github.hectorvent.floci.services.redshift.proxy.S3CopySimulator.CopyInput;
import io.github.hectorvent.floci.services.redshift.proxy.S3CopySimulator.S3TransferException;
import io.github.hectorvent.floci.services.s3.S3Service;
import io.github.hectorvent.floci.services.s3.model.S3Object;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.Socket;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import java.util.function.IntConsumer;

/** Executes COPY transformations and error tolerance on the caller's PostgreSQL 15 session. */
final class CopyEtlLoader {
    private static final Set<String> RECORD_ERRORS = Set.of(
            "22P02", "22003", "22007", "22008", "22001", "22021", "22P04", "23502");

    private CopyEtlLoader() {
    }

    static boolean runSimple(Socket client, Socket socket, CopyStatementParser.S3CopyFrom spec,
                              S3Service s3, IamService iam, String account, List<String> roles,
                              char status, IntConsumer onStatusChange) throws IOException {
        CopyBackendSession backend = new CopyBackendSession(socket, response -> {
            client.getOutputStream().write(response.toPacketBytes());
            client.getOutputStream().flush();
        });
        long accepted = 0;
        S3TransferException failure = null;
        try {
            accepted = execute(backend, spec, s3, iam, account, roles, status != 'I');
        } catch (S3TransferException exception) {
            failure = exception;
        } catch (AwsException exception) {
            failure = new S3TransferException("42501", "S3 COPY access denied", exception);
        } catch (RuntimeException exception) {
            failure = new S3TransferException("XX000", "S3 COPY failed", exception);
        }
        backend.recover();
        try {
            backend.finish(failure != null);
        } catch (S3TransferException e) {
            backend.recover();
            if (failure == null) {
                failure = e;
            }
        }
        backend.synchronize();
        if (failure == null) {
            client.getOutputStream().write(PostgresWire.frame('C',
                    PostgresWire.cStringBytes("COPY " + accepted)));
        } else {
            client.getOutputStream().write(PostgresWire.frame('E',
                    S3CopySimulator.errorBody(failure.sqlState(), failure.getMessage())));
        }
        client.getOutputStream().write(PostgresWire.frame('Z', new byte[]{(byte) backend.readyStatus()}));
        client.getOutputStream().flush();
        if (onStatusChange != null) {
            onStatusChange.accept(backend.readyStatus());
        }
        return true;
    }

    static long execute(CopyBackendSession backend, CopyStatementParser.S3CopyFrom spec,
                        S3Service s3, IamService iam, String account, List<String> roles,
                        boolean callerTransaction) throws IOException {
        backend.begin(callerTransaction);
        String savepoint = "floci_load_" + UUID.randomUUID().toString().replace("-", "");
        backend.execute("SAVEPOINT " + savepoint);
        try {
            long rows = load(backend, spec, s3, iam, account, roles);
            backend.execute("RELEASE SAVEPOINT " + savepoint);
            return rows;
        } catch (IOException | RuntimeException exception) {
            try {
                backend.recover();
                backend.execute("ROLLBACK TO SAVEPOINT " + savepoint);
                backend.execute("RELEASE SAVEPOINT " + savepoint);
                backend.failTransaction();
            } catch (IOException | RuntimeException recoveryFailure) {
                IOException failure = new IOException("Unable to recover failed COPY", recoveryFailure);
                failure.addSuppressed(exception);
                throw failure;
            }
            throw exception;
        }
    }

    private static long load(CopyBackendSession backend, CopyStatementParser.S3CopyFrom spec,
                             S3Service s3, IamService iam, String account, List<String> roles) throws IOException {
        List<ColumnInfo> catalog = columns(backend, spec);
        List<String> discovered = spec.jsonAuto() && (spec.columns() == null || spec.columns().isEmpty())
                ? catalog.stream().map(ColumnInfo::name).toList() : null;
        List<Integer> widths = spec.transforms().truncateColumns() ? S3CopySimulator.alignMaxBytes(spec, catalog) : null;
        List<CopyRecordTransformer.ColumnKind> kinds = spec.transforms().needsColumnTypes()
                ? S3CopySimulator.alignKinds(spec, catalog) : null;
        CopyInput input = S3CopySimulator.prepareCopy(spec, s3, iam, account, roles);
        try {
            if (!spec.transforms().toleratesErrors()) {
                return backend.copy(S3CopySimulator.copyBackendSql(spec, discovered),
                        output -> S3CopySimulator.streamCopyInput(input, discovered, widths, kinds, output));
            }
            try {
                return RequestScopes.callAsChecked(S3CopySimulator.keyAccount(spec, s3, iam),
                        () -> loadRecords(backend, input, discovered, widths, kinds));
            } catch (IOException | RuntimeException exception) {
                throw exception;
            } catch (Exception exception) {
                throw new IOException("Unable to read COPY input", exception);
            }
        } finally {
            S3CopySimulator.releaseCopySession(input);
        }
    }

    private static List<ColumnInfo> columns(CopyBackendSession backend, CopyStatementParser.S3CopyFrom spec)
            throws IOException {
        String table = "E'" + spec.targetTable().replace("\\", "\\\\").replace("'", "''") + "'";
        String query = "SELECT a.attname, CASE WHEN a.atttypid IN (1042, 1043) AND a.atttypmod > 4 "
                + "THEN a.atttypmod - 4 END, a.atttypid FROM pg_catalog.pg_attribute a "
                + "WHERE a.attrelid = to_regclass(" + table + ") AND a.attnum > 0 "
                + "AND NOT a.attisdropped AND a.attgenerated = '' ORDER BY a.attnum";
        List<ColumnInfo> catalog = new ArrayList<>();
        for (List<String> row : backend.query(query)) {
            catalog.add(new ColumnInfo(row.get(0), row.get(1) == null ? null : Integer.valueOf(row.get(1)),
                    S3CopySimulator.kindOf(row.get(2))));
        }
        if (catalog.isEmpty()) {
            throw new S3TransferException("42P01", "COPY target has no loadable columns", null);
        }
        return catalog;
    }

    private static void acquireTransformHeap(int[] heldMib, int wantedMib) {
        while (heldMib[0] < wantedMib) {
            if (!S3CopySimulator.COPY_TRANSFORM_HEAP_MIB.tryAcquire(1)) {
                throw new S3TransferException("53400", "COPY memory budget exhausted; retry shortly", null);
            }
            heldMib[0]++;
        }
    }

    private static long loadRecords(CopyBackendSession backend, CopyInput input, List<String> discovered,
                                     List<Integer> widths, List<CopyRecordTransformer.ColumnKind> kinds)
            throws IOException {
        CopyStatementParser.S3CopyFrom spec = input.spec();
        List<String> jsonColumns = discovered != null ? discovered : jsonColumns(spec);
        String sql = S3CopySimulator.copyBackendSql(spec, discovered);
        String rowSavepoint = "floci_row_" + UUID.randomUUID().toString().replace("-", "");
        long accepted = 0;
        int rejected = 0;
        for (String key : input.keys()) {
            authorize(input, key);
            S3Object object = input.s3().getObject(spec.bucket(), key);
            byte[] data = object != null && object.getData() != null ? object.getData() : new byte[0];
            try (InputStream raw = new ByteArrayInputStream(data);
                    InputStream source = CopySourceInputStream.open(raw, spec.gzip())) {
                S3CopySimulator.skipLines(source, spec.headerLines());
                CopyRecordReader records = new CopyRecordReader(source, spec);
                int[] heldMib = new int[]{0};
                try {
                    byte[] record;
                    while ((record = records.next(wantedMib -> acquireTransformHeap(heldMib, wantedMib))) != null) {
                        backend.execute("SAVEPOINT " + rowSavepoint);
                        try {
                            acquireTransformHeap(heldMib, (int) ((record.length * 2L) / (1024 * 1024)) + 1);
                            byte[] transformed = transform(spec, record, jsonColumns, widths, kinds, heldMib);
                            accepted += backend.copy(sql, output -> {
                                for (int offset = 0; offset < transformed.length; offset += 8192) {
                                    int length = Math.min(8192, transformed.length - offset);
                                    byte[] chunk = new byte[length];
                                    System.arraycopy(transformed, offset, chunk, 0, length);
                                    S3CopySimulator.writeCopyData(output, chunk, length);
                                }
                            });
                        } catch (S3TransferException exception) {
                            backend.recover();
                            backend.execute("ROLLBACK TO SAVEPOINT " + rowSavepoint);
                            if (!RECORD_ERRORS.contains(exception.sqlState()) || ++rejected >= spec.transforms().maxError()) {
                                throw exception;
                            }
                        } finally {
                            if (heldMib[0] > 0) {
                                S3CopySimulator.COPY_TRANSFORM_HEAP_MIB.release(heldMib[0]);
                                heldMib[0] = 0;
                            }
                        }
                        backend.execute("RELEASE SAVEPOINT " + rowSavepoint);
                    }
                } finally {
                    if (heldMib[0] > 0) {
                        S3CopySimulator.COPY_TRANSFORM_HEAP_MIB.release(heldMib[0]);
                        heldMib[0] = 0;
                    }
                }
            }
        }
        return accepted;
    }

    private static byte[] transform(CopyStatementParser.S3CopyFrom spec, byte[] record, List<String> jsonColumns,
                                    List<Integer> widths, List<CopyRecordTransformer.ColumnKind> kinds,
                                    int[] heldMib) throws IOException {
        if (spec.jsonAuto()) {
            ByteArrayOutputStream csv = new ByteArrayOutputStream();
            OutputStream bounded = new OutputStream() {
                private long count = 0;

                @Override
                public void write(int b) throws IOException {
                    count++;
                    check();
                    csv.write(b);
                }

                @Override
                public void write(byte[] b, int off, int len) throws IOException {
                    count += len;
                    check();
                    csv.write(b, off, len);
                }

                private void check() {
                    if (count > S3CopySimulator.COPY_TRANSFORM_MAX_OBJECT_BYTES) {
                        throw new S3TransferException("54000", "COPY transformed record exceeds the size limit", null);
                    }
                    if (heldMib != null) {
                        acquireTransformHeap(heldMib, (int) ((record.length + 2L * count) / (1024 * 1024)) + 1);
                    }
                }
            };
            JsonLinesToCsvConverter.convert(new ByteArrayInputStream(record), jsonColumns, bounded, spec.jsonAutoIgnoreCase());
            return csv.toByteArray();
        }
        return new CopyRecordTransformer(spec, widths, kinds).apply(record, bytes -> {
            if (bytes > S3CopySimulator.COPY_TRANSFORM_MAX_OBJECT_BYTES) {
                throw new S3TransferException("54000", "COPY transformed record exceeds the size limit", null);
            }
            if (heldMib != null) {
                acquireTransformHeap(heldMib, (int) ((record.length + 2L * bytes) / (1024 * 1024)) + 1);
            }
        });
    }

    private static List<String> jsonColumns(CopyStatementParser.S3CopyFrom spec) {
        return spec.columns() == null ? List.of() : spec.columns().stream().map(column ->
                column.startsWith("\"") ? column.substring(1, column.length() - 1).replace("\"\"", "\"")
                        : column.toLowerCase(Locale.ROOT)).toList();
    }

    private static void authorize(CopyInput input, String key) {
        CopyStatementParser.S3CopyFrom spec = input.spec();
        if (input.roleSession() == null) {
            input.s3().authorizeAnonymousGetObject(spec.bucket(), key);
        } else {
            input.s3().authorizeSignedGetObject(input.roleSession().accessKeyId(), input.roleSession().sessionToken(),
                    spec.bucket(), key);
        }
    }
}
