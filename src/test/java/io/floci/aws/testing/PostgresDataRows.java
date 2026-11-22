package io.floci.aws.testing;

import io.floci.aws.core.common.PostgresWire;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.List;

/** Builds the body of a PostgreSQL {@code DataRow} message for tests that fake a backend. */
public final class PostgresDataRows {

    private PostgresDataRows() {
    }

    /** One text column per value; a null value is an SQL NULL. */
    public static byte[] dataRow(List<String> row) {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try {
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
        } catch (IOException e) {
            // a ByteArrayOutputStream does not fail, so this cannot happen
            throw new UncheckedIOException(e);
        }
        return bytes.toByteArray();
    }
}
