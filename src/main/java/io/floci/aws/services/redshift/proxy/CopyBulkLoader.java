package io.floci.aws.services.redshift.proxy;

import io.floci.aws.services.redshift.proxy.S3CopySimulator.ColumnInfo;
import io.floci.aws.services.redshift.proxy.S3CopySimulator.CopyInput;
import io.floci.aws.services.redshift.proxy.S3CopySimulator.S3TransferException;
import io.floci.aws.services.s3.model.S3Object;
import org.jboss.logging.Logger;

import java.io.BufferedOutputStream;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Streams ordinary loads with one backend COPY per object, retaining record-sized transformation limits. */
final class CopyBulkLoader {
    private static final Logger LOG = Logger.getLogger(CopyBulkLoader.class);
    private static final Pattern BACKEND_LINE = Pattern.compile("(?:^|\\n)COPY [^\\n]*?, line ([0-9]+)(?:,|\\n|$)");

    private CopyBulkLoader() {
    }

    static long load(CopyBackendSession backend, CopyInput input, List<String> discovered, List<Integer> widths,
                      List<CopyRecordTransformer.ColumnKind> kinds, List<Integer> scales,
                      List<ColumnInfo> catalog, CopyLoadError.Context context) throws IOException {
        CopyStatementParser.S3CopyFrom spec = input.spec();
        List<String> jsonColumns = S3CopySimulator.jsonColumns(spec, discovered);
        String sql = S3CopySimulator.copyBackendSql(spec, discovered);
        long accepted = 0;
        for (String key : input.keys()) {
            S3CopySimulator.authorizeCopyObject(spec, input.s3(), input.iamService(), input.roleSession(), key);
            S3Object object = input.readObject(key);
            byte[] data = object != null && object.getData() != null ? object.getData() : new byte[0];
            try (Cursor cursor = new Cursor(spec, data, jsonColumns, widths, kinds, scales)) {
                try {
                    accepted += backend.copy(sql, output -> {
                        // Buffer COPY frames too: a short record must not require its own socket write.
                        OutputStream buffered = new BufferedOutputStream(output, 8192);
                        try {
                            byte[] record;
                            while ((record = cursor.next()) != null) {
                                writeRecord(buffered, record);
                            }
                        } catch (IOException | RuntimeException exception) {
                            try {
                                // Finish any buffered frame before CopyBackendSession sends CopyFail.
                                buffered.flush();
                            } catch (IOException flushFailure) {
                                exception.addSuppressed(flushFailure);
                            }
                            throw exception;
                        }
                        buffered.flush();
                    });
                } catch (S3TransferException exception) {
                    if (context != null && CopyEtlLoader.isRecordError(exception)) {
                        long backendLine = backendLine(exception);
                        if (backendLine >= 1) {
                            cursor.close();
                            diagnose(backend, context, spec, key, data, jsonColumns, widths, kinds, scales,
                                    catalog, exception, backendLine);
                        } else {
                            CopyEtlLoader.recordError(backend, context, spec, key, cursor.line(), cursor.raw(), catalog, exception);
                        }
                    }
                    throw exception;
                }
            }
        }
        return accepted;
    }

    static void writeRecord(OutputStream output, byte[] record) throws IOException {
        for (int offset = 0; offset < record.length; offset += 8192) {
            int length = Math.min(8192, record.length - offset);
            byte[] chunk = new byte[length];
            System.arraycopy(record, offset, chunk, 0, length);
            S3CopySimulator.writeCopyData(output, chunk, length);
        }
    }

    private static void diagnose(CopyBackendSession backend, CopyLoadError.Context context,
                                  CopyStatementParser.S3CopyFrom spec, String key, byte[] data,
                                  List<String> jsonColumns, List<Integer> widths,
                                  List<CopyRecordTransformer.ColumnKind> kinds, List<Integer> scales,
                                  List<ColumnInfo> catalog, S3TransferException failure, long targetLine) {
        // Replay only the failed object's immutable input bytes, never re-read a possibly replaced S3 object.
        try (Cursor cursor = new Cursor(spec, data, jsonColumns, widths, kinds, scales)) {
            BackendLines lines = new BackendLines(spec.csv() || spec.jsonAuto());
            byte[] record;
            while ((record = cursor.next()) != null) {
                if (lines.advance(record) >= targetLine) {
                    CopyEtlLoader.recordError(backend, context, spec, key, cursor.line(), cursor.raw(), catalog, failure);
                    return;
                }
            }
        } catch (IOException | RuntimeException exception) {
            LOG.debugv(exception, "Unable to locate COPY diagnostic in {0}, backend line {1}", key, targetLine);
        }
        CopyEtlLoader.recordError(backend, context, spec, key, -1, new byte[0], catalog, failure);
    }

    private static long backendLine(S3TransferException failure) {
        Matcher match = BACKEND_LINE.matcher(failure.fields().getOrDefault('W', ""));
        if (match.find()) {
            try {
                return Long.parseLong(match.group(1));
            } catch (NumberFormatException ignored) {
                // An unrepresentable backend line cannot be mapped reliably to the input object.
            }
        }
        return -1;
    }

    private static final class BackendLines {
        private final boolean csv;
        private long line;
        private boolean endingKnown;
        private boolean lineFeedEnding;

        BackendLines(boolean csv) {
            this.csv = csv;
        }

        long advance(byte[] record) {
            line++;
            if (!csv) {
                return line;
            }
            boolean quoted = false;
            for (byte value : record) {
                if (value == '"') {
                    quoted = !quoted;
                }
                if (quoted) {
                    // PostgreSQL counts CR until the first record establishes its line-ending convention.
                    if (value == (lineFeedEnding ? '\n' : '\r')) {
                        line++;
                    }
                } else if (value == '\r' || value == '\n') {
                    if (!endingKnown) {
                        endingKnown = true;
                        lineFeedEnding = value == '\n';
                    }
                    break;
                }
            }
            return line;
        }
    }

    static final class Cursor implements AutoCloseable {
        private final CopyStatementParser.S3CopyFrom spec;
        private final InputStream source;
        private final CopyRecordReader records;
        private final List<String> jsonColumns;
        private final List<Integer> widths;
        private final List<CopyRecordTransformer.ColumnKind> kinds;
        private final List<Integer> scales;
        private final int[] heldMib = new int[]{0};
        private byte[] raw = new byte[0];
        private long line;

        Cursor(CopyStatementParser.S3CopyFrom spec, byte[] data, List<String> jsonColumns,
                List<Integer> widths, List<CopyRecordTransformer.ColumnKind> kinds, List<Integer> scales) throws IOException {
            this.spec = spec;
            this.jsonColumns = jsonColumns;
            this.widths = widths;
            this.kinds = kinds;
            this.scales = scales;
            source = CopySourceInputStream.open(new ByteArrayInputStream(data), spec.gzip());
            try {
                S3CopySimulator.skipLines(source, spec.headerLines());
            } catch (IOException | RuntimeException exception) {
                source.close();
                throw exception;
            }
            records = new CopyRecordReader(source, spec, !spec.transforms().toleratesErrors());
        }

        byte[] next() throws IOException {
            while (true) {
                releaseHeap();
                try {
                    raw = records.next(wanted -> CopyEtlLoader.acquireTransformHeap(heldMib, wanted));
                } catch (S3TransferException exception) {
                    releaseHeap();
                    raw = records.lastRecord();
                    line = records.recordLine() + spec.headerLines();
                    throw exception;
                }
                line = records.recordLine() + spec.headerLines();
                if (raw == null) {
                    raw = new byte[0];
                    return null;
                }
                if (spec.transforms().ignoreBlankLines() && CopyEtlLoader.isBlankLine(raw)) {
                    continue;
                }
                try {
                    CopyEtlLoader.acquireTransformHeap(heldMib, (int) ((raw.length * 2L) / (1024 * 1024)) + 1);
                    return CopyEtlLoader.transform(spec, raw, jsonColumns, widths, kinds, scales, heldMib);
                } catch (RuntimeException exception) {
                    releaseHeap();
                    throw exception;
                }
            }
        }

        byte[] raw() {
            return raw;
        }

        long line() {
            return line;
        }

        @Override
        public void close() throws IOException {
            releaseHeap();
            source.close();
        }

        private void releaseHeap() {
            if (heldMib[0] > 0) {
                S3CopySimulator.COPY_TRANSFORM_HEAP_MIB.release(heldMib[0]);
                heldMib[0] = 0;
            }
        }
    }
}
