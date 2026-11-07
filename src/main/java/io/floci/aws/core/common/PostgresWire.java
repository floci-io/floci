package io.floci.aws.core.common;

import java.io.ByteArrayOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * PostgreSQL's frontend/backend protocol, version 3, as every PostgreSQL endpoint in Floci reads
 * and writes it (the RDS proxy, Redshift's bridge, Aurora DSQL's endpoint): a message's framing,
 * the startup packet and its parameters, and the fields of an ErrorResponse or NoticeResponse.
 */
public final class PostgresWire {

    public static final int PROTOCOL_3 = 196608;
    public static final int SSL_REQUEST = 80877103;
    public static final int GSSENC_REQUEST = 80877104;
    public static final int CANCEL_REQUEST = 80877102;

    private PostgresWire() {
    }

    /**
     * An escape-string literal, so it reads the same whatever standard_conforming_strings says.
     * Apostrophes are doubled rather than backslash-escaped, which backslash_quote can forbid.
     */
    public static String quoteLiteral(String value) {
        return "E'" + value.replace("\\", "\\\\").replace("'", "''") + "'";
    }

    /** A message after the startup: its type byte and its body, without the length. */
    public record Message(byte type, byte[] body) {
    }

    /** A startup-phase packet, which has no type byte: its code and the rest of its body. */
    public record Startup(int code, byte[] body) {
    }

    /** The NUL-terminated string at an offset, and where the next field starts. */
    public record CString(String value, int next) {
    }

    /**
     * A packet whose length is outside what its reader takes. Nothing after the length was read, so
     * a server can still answer the way PostgreSQL does before it closes the connection.
     */
    public static final class InvalidLengthException extends IOException {
        InvalidLengthException(String message) {
            super(message);
        }
    }

    // ── Framing ───────────────────────────────────────────────────────────────

    /** Reads a startup-phase packet: its length, refused outside 8 to {@code maxLength}, code and body. */
    public static Startup readStartup(InputStream in, int maxLength, String packetName) throws IOException {
        int length = checkedLength(readInt32(in), 8, maxLength, packetName);
        int code = readInt32(in);
        byte[] body = new byte[length - 8];
        readFully(in, body);
        return new Startup(code, body);
    }

    /** Reads one message, or throws {@link EOFException} at the end of the stream. */
    public static Message read(InputStream in, int maxLength, String packetName) throws IOException {
        int type = in.read();
        if (type < 0) {
            throw new EOFException("Connection closed before a " + packetName);
        }
        return new Message((byte) type, readBody(in, 4, maxLength, packetName));
    }

    /** The body of a message whose type byte was read already: its length, checked, then the rest. */
    public static byte[] readBody(InputStream in, int minLength, int maxLength, String packetName) throws IOException {
        byte[] body = new byte[checkedLength(readInt32(in), minLength, maxLength, packetName) - 4];
        readFully(in, body);
        return body;
    }

    /** Writes a message of {@code type} whose body is {@code parts} one after the other. */
    public static void write(OutputStream out, int type, byte[]... parts) throws IOException {
        int length = 4;
        for (byte[] part : parts) {
            length += part.length;
        }
        out.write(type);
        writeInt32(out, length);
        for (byte[] part : parts) {
            out.write(part);
        }
    }

    public static void write(OutputStream out, Message message) throws IOException {
        write(out, message.type(), message.body());
    }

    /** A whole message, its type, length and body, as bytes. */
    public static byte[] frame(int type, byte[] body) {
        ByteArrayOutputStream out = new ByteArrayOutputStream(body.length + 5);
        out.write(type);
        out.writeBytes(int32(body.length + 4));
        out.writeBytes(body);
        return out.toByteArray();
    }

    /** {@code length}, refused with an {@link InvalidLengthException} when below {@code minimum} or above {@code maximum}. */
    public static int checkedLength(int length, int minimum, int maximum, String packetName) throws IOException {
        if (length < minimum) {
            throw new InvalidLengthException("PostgreSQL " + packetName + " length is below the "
                    + minimum + " byte minimum: " + length);
        }
        if (length > maximum) {
            throw new InvalidLengthException("PostgreSQL " + packetName + " length exceeds the "
                    + maximum + " byte limit: " + length);
        }
        return length;
    }

    public static int readInt32(InputStream in) throws IOException {
        int b0 = in.read();
        int b1 = in.read();
        int b2 = in.read();
        int b3 = in.read();
        if ((b0 | b1 | b2 | b3) < 0) {
            throw new EOFException("Connection closed while reading Int32");
        }
        return (b0 << 24) | (b1 << 16) | (b2 << 8) | b3;
    }

    public static void readFully(InputStream in, byte[] buffer) throws IOException {
        int offset = 0;
        while (offset < buffer.length) {
            int read = in.read(buffer, offset, buffer.length - offset);
            if (read < 0) {
                throw new EOFException("Connection closed while reading " + buffer.length + " bytes");
            }
            offset += read;
        }
    }

    public static void writeInt32(OutputStream out, int value) throws IOException {
        out.write(int32(value));
    }

    public static byte[] int32(int value) {
        return new byte[]{(byte) (value >> 24), (byte) (value >> 16), (byte) (value >> 8), (byte) value};
    }

    public static int int32(byte[] bytes, int offset) {
        return ((bytes[offset] & 0xFF) << 24) | ((bytes[offset + 1] & 0xFF) << 16)
                | ((bytes[offset + 2] & 0xFF) << 8) | (bytes[offset + 3] & 0xFF);
    }

    // ── Strings ───────────────────────────────────────────────────────────────

    public static CString cString(byte[] body, int offset) {
        int end = offset;
        while (end < body.length && body[end] != 0) {
            end++;
        }
        return new CString(new String(body, offset, end - offset, StandardCharsets.UTF_8), end + 1);
    }

    public static byte[] cStringBytes(String value) {
        byte[] text = value.getBytes(StandardCharsets.UTF_8);
        byte[] bytes = new byte[text.length + 1];
        System.arraycopy(text, 0, bytes, 0, text.length);
        return bytes;
    }

    // ── Startup ───────────────────────────────────────────────────────────────

    /** The {@code name=value} pairs of a StartupMessage body, in the order the client sent them. */
    public static Map<String, String> startupParameters(byte[] body) {
        Map<String, String> parameters = new LinkedHashMap<>();
        int offset = 0;
        while (offset < body.length) {
            CString name = cString(body, offset);
            if (name.value().isEmpty() || name.next() > body.length) {
                break;
            }
            CString value = cString(body, name.next());
            parameters.put(name.value(), value.value());
            offset = value.next();
        }
        return parameters;
    }

    /** A whole StartupMessage for protocol 3 carrying {@code parameters} in their order. */
    public static byte[] startupMessage(Map<String, String> parameters) {
        ByteArrayOutputStream body = new ByteArrayOutputStream();
        body.writeBytes(int32(PROTOCOL_3));
        parameters.forEach((name, value) -> {
            body.writeBytes(cStringBytes(name));
            body.writeBytes(cStringBytes(value));
        });
        body.write(0);
        ByteArrayOutputStream packet = new ByteArrayOutputStream(body.size() + 4);
        packet.writeBytes(int32(body.size() + 4));
        packet.writeBytes(body.toByteArray());
        return packet.toByteArray();
    }

    // ── ErrorResponse and NoticeResponse ──────────────────────────────────────

    /** The body of an ErrorResponse ({@code E}) or NoticeResponse ({@code N}) from its fields, in order. */
    public static byte[] fields(Map<Character, String> fields) {
        ByteArrayOutputStream body = new ByteArrayOutputStream();
        fields.forEach((code, value) -> {
            body.write(code);
            body.writeBytes(cStringBytes(value));
        });
        body.write(0);
        return body.toByteArray();
    }

    /** The fields of an ErrorResponse or NoticeResponse whose first field starts at {@code offset}. */
    public static Map<Character, String> parseFields(byte[] bytes, int offset) {
        Map<Character, String> fields = new LinkedHashMap<>();
        int i = offset;
        while (i < bytes.length && bytes[i] != 0) {
            char code = (char) bytes[i];
            CString value = cString(bytes, i + 1);
            fields.put(code, value.value());
            i = value.next();
        }
        return fields;
    }
}
