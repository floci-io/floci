package io.floci.aws.services.redshift.proxy;

import io.floci.aws.core.common.AwsException;
import io.floci.aws.core.common.PostgresWire;
import io.floci.aws.core.common.RequestScopes;
import io.floci.aws.services.iam.IamService;
import io.floci.aws.services.redshift.proxy.CopyBulkLoader.Cursor;
import io.floci.aws.services.redshift.proxy.S3CopySimulator.ColumnInfo;
import io.floci.aws.services.redshift.proxy.S3CopySimulator.CopyInput;
import io.floci.aws.services.redshift.proxy.S3CopySimulator.S3TransferException;
import io.floci.aws.services.s3.S3Service;
import io.floci.aws.services.s3.model.S3Object;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.Socket;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.function.Consumer;
import java.util.function.IntConsumer;

/** Executes COPY transformations and error tolerance on the caller's PostgreSQL 15 session. */
final class CopyEtlLoader {
    private static final Set<String> RECORD_ERRORS = Set.of(
            "22P02", "22003", "22007", "22008", "22001", "22021", "22P04", "23502");

    private CopyEtlLoader() {
    }

    static boolean runSimple(Socket client, Socket socket, CopyStatementParser.S3Statement spec,
                              S3Service s3, IamService iam, String account, List<String> roles,
                              char status, IntConsumer onStatusChange) throws IOException {
        return runSimple(client, socket, spec, s3, iam, account, roles, status, onStatusChange, null);
    }

    static boolean runSimple(Socket client, Socket socket, CopyStatementParser.S3Statement spec,
                              S3Service s3, IamService iam, String account, List<String> roles,
                              char status, IntConsumer onStatusChange, Consumer<CopyLoadError> errorSink) throws IOException {
        CopyBackendSession backend = new CopyBackendSession(socket, response -> {
            client.getOutputStream().write(response.toPacketBytes());
            client.getOutputStream().flush();
        });
        backend.loadErrorSink(errorSink);
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

    static long execute(CopyBackendSession backend, CopyStatementParser.S3Statement spec,
                        S3Service s3, IamService iam, String account, List<String> roles,
                        boolean callerTransaction) throws IOException {
        backend.begin(callerTransaction);
        String savepoint = "floci_load_" + UUID.randomUUID().toString().replace("-", "");
        backend.execute("SAVEPOINT " + savepoint);
        try {
            long rows = switch (spec) {
                case CopyStatementParser.S3CopyFrom copy -> load(backend, copy, null, null, s3, iam, account, roles, false);
                case CopyStatementParser.S3CopyJobRun run -> CopyJobRunner.load(backend, run.name(), s3, iam, account, roles);
                default -> throw new IllegalArgumentException("Expected COPY statement");
            };
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

    static long executeObject(CopyBackendSession backend, CopyStatementParser.S3CopyFrom spec,
                              String expectedETag, String versionId,
                              S3Service s3, IamService iam, String account, List<String> roles) throws IOException {
        return load(backend, spec, expectedETag, versionId, s3, iam, account, roles, true);
    }

    private static long load(CopyBackendSession backend, CopyStatementParser.S3CopyFrom spec,
                             String expectedETag, String versionId,
                             S3Service s3, IamService iam, String account, List<String> roles,
                             boolean exactObject) throws IOException {
        List<ColumnInfo> catalog = columns(backend, spec);
        List<String> discovered = spec.jsonAuto() && (spec.columns() == null || spec.columns().isEmpty())
                ? catalog.stream().map(ColumnInfo::name).toList() : null;
        List<Integer> widths = spec.transforms().truncateColumns() ? S3CopySimulator.alignMaxBytes(spec, catalog) : null;
        List<CopyRecordTransformer.ColumnKind> kinds = spec.transforms().needsColumnTypes()
                ? S3CopySimulator.alignKinds(spec, catalog) : null;
        List<Integer> scales = S3CopySimulator.alignScales(spec, catalog);
        CopyLoadError.Context context = backend.loadErrorSink() == null ? null : CopyLoadError.Context.create(
                backend.query("SELECT (SELECT oid FROM pg_roles WHERE rolname = session_user), pg_backend_pid(), "
                        + "to_regclass(" + PostgresWire.quoteLiteral(spec.targetTable()) + ")::oid"));
        CopyInput input = exactObject
                ? S3CopySimulator.prepareObjectCopy(spec, s3, iam, account, roles, expectedETag, versionId)
                : S3CopySimulator.prepareCopy(spec, s3, iam, account, roles);
        try {
            try {
                return RequestScopes.callAsChecked(S3CopySimulator.keyAccount(spec, s3, iam),
                        () -> spec.transforms().toleratesErrors()
                                ? loadRecords(backend, input, discovered, widths, kinds, scales, catalog, context)
                                : CopyBulkLoader.load(backend, input, discovered, widths, kinds, scales, catalog, context));
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
        List<ColumnInfo> catalog = new ArrayList<>();
        for (List<String> row : backend.query(S3CopySimulator.columnInfoQuery(spec))) {
            catalog.add(S3CopySimulator.columnInfo(row));
        }
        if (catalog.isEmpty()) {
            throw new S3TransferException("42P01", "COPY target has no loadable columns", null);
        }
        return catalog;
    }

    static void acquireTransformHeap(int[] heldMib, int wantedMib) {
        while (heldMib[0] < wantedMib) {
            if (!S3CopySimulator.COPY_TRANSFORM_HEAP_MIB.tryAcquire(1)) {
                throw new S3TransferException("53400", "COPY memory budget exhausted; retry shortly", null);
            }
            heldMib[0]++;
        }
    }

    private static long loadRecords(CopyBackendSession backend, CopyInput input, List<String> discovered,
                                     List<Integer> widths, List<CopyRecordTransformer.ColumnKind> kinds,
                                     List<Integer> scales, List<ColumnInfo> catalog, CopyLoadError.Context context)
            throws IOException {
        CopyStatementParser.S3CopyFrom spec = input.spec();
        List<String> jsonColumns = S3CopySimulator.jsonColumns(spec, discovered);
        String sql = S3CopySimulator.copyBackendSql(spec, discovered);
        String rowSavepoint = "floci_row_" + UUID.randomUUID().toString().replace("-", "");
        long accepted = 0;
        int rejected = 0;
        for (String key : input.keys()) {
            S3CopySimulator.authorizeCopyObject(spec, input.s3(), input.iamService(), input.roleSession(), key);
            S3Object object = input.readObject(key);
            byte[] data = object != null && object.getData() != null ? object.getData() : new byte[0];
            try (Cursor cursor = new Cursor(spec, data, jsonColumns, widths, kinds, scales)) {
                while (true) {
                    byte[] record;
                    try {
                        record = cursor.next();
                    } catch (S3TransferException exception) {
                        recordError(backend, context, spec, key, cursor.line(), cursor.raw(), catalog, exception);
                        if (isRecordError(exception) && (spec.transforms().ignoreAllErrors()
                                || ++rejected < spec.transforms().maxError())) {
                            continue;
                        }
                        throw exception;
                    }
                    if (record == null) {
                        break;
                    }
                    backend.execute("SAVEPOINT " + rowSavepoint);
                    try {
                        accepted += backend.copy(sql, output -> CopyBulkLoader.writeRecord(output, record));
                    } catch (S3TransferException exception) {
                        backend.recover();
                        backend.execute("ROLLBACK TO SAVEPOINT " + rowSavepoint);
                        recordError(backend, context, spec, key, cursor.line(), cursor.raw(), catalog, exception);
                        if (!isRecordError(exception) || (!spec.transforms().ignoreAllErrors()
                                && ++rejected >= spec.transforms().maxError())) {
                            throw exception;
                        }
                    }
                    backend.execute("RELEASE SAVEPOINT " + rowSavepoint);
                }
            }
        }
        return accepted;
    }

    static byte[] transform(CopyStatementParser.S3CopyFrom spec, byte[] record, List<String> jsonColumns,
                                    List<Integer> widths, List<CopyRecordTransformer.ColumnKind> kinds,
                                    List<Integer> scales, int[] heldMib) throws IOException {
        if (!spec.jsonAuto() && isBlankLine(record)) {
            throw new S3TransferException("22P04", "COPY input line contained no data", null);
        }
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
            CopyStatementParser.S3CopyFrom csvSpec = new CopyStatementParser.S3CopyFrom(spec.targetTable(),
                    spec.columns(), spec.bucket(), spec.keyOrPrefix(), ",", 0, false, true, null, null,
                    false, false, false, spec.transforms());
            return new CopyRecordTransformer(csvSpec, widths, kinds, scales).apply(csv.toByteArray(), bytes -> {
                checkOutputBudget(bytes, record.length + 2L * csv.size(), heldMib);
            });
        }
        return new CopyRecordTransformer(spec, widths, kinds, scales).apply(record, bytes -> {
            checkOutputBudget(bytes, record.length, heldMib);
        });
    }

    private static void checkOutputBudget(int bytes, long inputBytes, int[] heldMib) {
        if (bytes > S3CopySimulator.COPY_TRANSFORM_MAX_OBJECT_BYTES) {
            throw new S3TransferException("54000", "COPY transformed record exceeds the size limit", null);
        }
        if (heldMib != null) {
            acquireTransformHeap(heldMib, (int) ((inputBytes + 2L * bytes) / (1024 * 1024)) + 1);
        }
    }

    static boolean isBlankLine(byte[] record) {
        return record.length == 1 && (record[0] == '\n' || record[0] == '\r')
                || record.length == 2 && record[0] == '\r' && record[1] == '\n';
    }

    static boolean isRecordError(S3TransferException exception) {
        return RECORD_ERRORS.contains(exception.sqlState());
    }

    static void recordError(CopyBackendSession backend, CopyLoadError.Context context,
                                     CopyStatementParser.S3CopyFrom spec, String key, long line, byte[] record,
                                     List<ColumnInfo> catalog, S3TransferException exception) {
        if (context != null && RECORD_ERRORS.contains(exception.sqlState())) {
            backend.loadErrorSink().accept(CopyLoadError.from(context, spec, key, line, record, catalog, exception));
        }
    }

}
