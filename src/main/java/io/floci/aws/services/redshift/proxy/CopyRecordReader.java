package io.floci.aws.services.redshift.proxy;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.PushbackInputStream;
import java.nio.charset.StandardCharsets;
import java.util.function.IntConsumer;

/** Reads one logical COPY record, retaining CSV newlines inside quoted fields. */
final class CopyRecordReader {
    private final PushbackInputStream input;
    private final boolean csv;
    private final boolean removeQuotes;
    private final boolean json;
    private final int delimiter;

    CopyRecordReader(InputStream input, CopyStatementParser.S3CopyFrom spec) {
        this.input = new PushbackInputStream(input, 1);
        csv = spec.csv();
        json = spec.jsonAuto();
        removeQuotes = spec.transforms().removeQuotes();
        String separator = spec.delimiter() != null ? spec.delimiter() : (csv ? "," : "|");
        delimiter = separator.getBytes(StandardCharsets.UTF_8)[0] & 0xff;
    }

    byte[] next() throws IOException {
        return next(null);
    }

    byte[] next(IntConsumer memoryTracker) throws IOException {
        ByteArrayOutputStream record = new ByteArrayOutputStream();
        int quote = 0;
        boolean fieldStart = true;
        int value;
        while ((value = input.read()) != -1) {
            append(record, value, memoryTracker);
            if (json) {
                if (value == '\n') {
                    return record.toByteArray();
                }
                continue;
            }
            if (!csv && value == '\\') {
                int escaped = input.read();
                if (escaped != -1) {
                    append(record, escaped, memoryTracker);
                }
                fieldStart = false;
                continue;
            }
            if (quote != 0) {
                if (value == quote) {
                    int following = input.read();
                    if (csv && following == quote) {
                        append(record, following, memoryTracker);
                    } else {
                        if (csv || following == -1 || following == delimiter || following == '\n' || following == '\r') {
                            quote = 0;
                        }
                        if (following != -1) {
                            input.unread(following);
                        }
                    }
                }
            } else if ((csv && value == '"')
                    || (fieldStart && removeQuotes && (value == '"' || value == '\''))) {
                quote = value;
                fieldStart = false;
            } else if (value == '\n' || value == '\r') {
                if (value == '\r') {
                    int following = input.read();
                    if (following == '\n') {
                        append(record, following, memoryTracker);
                    } else if (following != -1) {
                        input.unread(following);
                    }
                }
                return record.toByteArray();
            } else {
                fieldStart = value == delimiter;
            }
        }
        if (quote != 0) {
            throw new S3CopySimulator.S3TransferException("22P04", "COPY quoted field has no closing quote", null);
        }
        if (record.size() == 0) {
            return null;
        }
        append(record, '\n', memoryTracker);
        return record.toByteArray();
    }

    private static void append(ByteArrayOutputStream record, int value, IntConsumer memoryTracker) {
        if (record.size() >= S3CopySimulator.COPY_TRANSFORM_MAX_OBJECT_BYTES) {
            throw new S3CopySimulator.S3TransferException("54000", "COPY record exceeds the transform size limit", null);
        }
        if (memoryTracker != null && (record.size() % (1024 * 1024) == 0)) {
            memoryTracker.accept((record.size() / (1024 * 1024)) + 1);
        }
        record.write(value);
    }
}
