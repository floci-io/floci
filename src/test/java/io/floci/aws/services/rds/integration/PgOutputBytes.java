package io.floci.aws.services.rds.integration;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;

/** Writes one pgoutput message in the plugin's big-endian layout, as protocol version 1 has it. */
final class PgOutputBytes {
    private final ByteArrayOutputStream bytes = new ByteArrayOutputStream();
    private final DataOutputStream out = new DataOutputStream(bytes);

    PgOutputBytes type(char type) {
        return byte1(type);
    }

    PgOutputBytes byte1(int value) {
        try {
            out.writeByte(value);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return this;
    }

    PgOutputBytes int16(int value) {
        try {
            out.writeShort(value);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return this;
    }

    PgOutputBytes int32(int value) {
        try {
            out.writeInt(value);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return this;
    }

    PgOutputBytes int64(long value) {
        try {
            out.writeLong(value);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return this;
    }

    PgOutputBytes string(String value) {
        byte[] text = value.getBytes(StandardCharsets.UTF_8);
        bytes.writeBytes(text);
        return byte1(0);
    }

    PgOutputBytes text(String value) {
        byte[] text = value.getBytes(StandardCharsets.UTF_8);
        byte1('t').int32(text.length);
        bytes.writeBytes(text);
        return this;
    }

    byte[] build() {
        return bytes.toByteArray();
    }
}
