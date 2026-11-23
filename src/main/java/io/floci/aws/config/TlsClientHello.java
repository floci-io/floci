package io.floci.aws.config;

import io.vertx.core.buffer.Buffer;

import java.nio.BufferUnderflowException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.Locale;

/**
 * Reads the SNI server name from a TLS ClientHello without terminating TLS.
 *
 * <p>Only the first TLS record is read. A client may split its ClientHello across several records
 * at any size; when the server name extension is not in the first one, or the first record is
 * cut short, there is no name and the connection keeps its default route.
 */
final class TlsClientHello {

    private static final int RECORD_HEADER = 5;
    private static final int MAX_RECORD = 16384;
    private static final int HANDSHAKE_CLIENT_HELLO = 1;
    private static final int EXTENSION_SERVER_NAME = 0;
    private static final int NAME_TYPE_HOST = 0;

    private TlsClientHello() {
    }

    /** Whether {@code data} starts a handshake record that has not fully arrived yet. */
    static boolean needsMore(Buffer data) {
        if (data.length() == 0 || data.getByte(0) != TlsProxyServer.TLS_HANDSHAKE) {
            return false;
        }
        if (data.length() < RECORD_HEADER) {
            return true;
        }
        int length = data.getUnsignedShort(3);
        return length <= MAX_RECORD && data.length() < RECORD_HEADER + length;
    }

    /** The lower-cased host name the client asked for, or null when absent, incomplete or malformed. */
    static String serverName(Buffer data) {
        try {
            ByteBuffer in = ByteBuffer.wrap(data.getBytes());
            if (in.get() != TlsProxyServer.TLS_HANDSHAKE) {
                return null;
            }
            in.getShort();
            ByteBuffer record = slice(in, Short.toUnsignedInt(in.getShort()));
            if (record.get() != HANDSHAKE_CLIENT_HELLO) {
                return null;
            }
            ByteBuffer hello = slice(record, (Byte.toUnsignedInt(record.get()) << 16) | Short.toUnsignedInt(record.getShort()));
            skip(hello, 2 + 32);
            skip(hello, Byte.toUnsignedInt(hello.get()));
            skip(hello, Short.toUnsignedInt(hello.getShort()));
            skip(hello, Byte.toUnsignedInt(hello.get()));
            if (!hello.hasRemaining()) {
                return null;
            }
            ByteBuffer extensions = slice(hello, Short.toUnsignedInt(hello.getShort()));
            while (extensions.hasRemaining()) {
                int type = Short.toUnsignedInt(extensions.getShort());
                ByteBuffer extension = slice(extensions, Short.toUnsignedInt(extensions.getShort()));
                if (type == EXTENSION_SERVER_NAME) {
                    return hostName(slice(extension, Short.toUnsignedInt(extension.getShort())));
                }
            }
            return null;
        } catch (BufferUnderflowException | IllegalArgumentException malformed) {
            return null;
        }
    }

    private static String hostName(ByteBuffer names) {
        while (names.hasRemaining()) {
            int type = Byte.toUnsignedInt(names.get());
            ByteBuffer name = slice(names, Short.toUnsignedInt(names.getShort()));
            if (type == NAME_TYPE_HOST) {
                String host = StandardCharsets.US_ASCII.decode(name).toString().toLowerCase(Locale.ROOT);
                if (host.endsWith(".")) {
                    host = host.substring(0, host.length() - 1);
                }
                return !host.isEmpty() && host.chars().allMatch(c -> c == '.' || c == '-'
                        || (c >= 'a' && c <= 'z') || (c >= '0' && c <= '9')) ? host : null;
            }
        }
        return null;
    }

    private static ByteBuffer slice(ByteBuffer in, int length) {
        if (length > in.remaining()) {
            throw new BufferUnderflowException();
        }
        ByteBuffer part = in.slice(in.position(), length);
        in.position(in.position() + length);
        return part;
    }

    private static void skip(ByteBuffer in, int length) {
        slice(in, length);
    }
}
