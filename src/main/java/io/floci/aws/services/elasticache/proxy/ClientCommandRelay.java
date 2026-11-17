package io.floci.aws.services.elasticache.proxy;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * Relays a signed-in client's commands to its backend one command at a time, so the proxy can answer
 * for the credentials a later {@code AUTH} or {@code HELLO} carries, as it does at sign-in. Commands
 * come as RESP arrays or as inline lines, which are split into arguments as Valkey splits them. Every
 * command the proxy does not ask for is passed on byte for byte, the values of an array streamed
 * rather than held. A wanted command too large to hold is dropped for one the proxy supplies. After a
 * malformed array, which the backend cannot read, the rest of the connection is a plain relay, as the
 * backend then reports the protocol error and closes it.
 */
final class ClientCommandRelay {

    /** Longest command name worth reading: anything longer is no command the proxy looks at. */
    private static final int NAME_LIMIT = 64;
    /**
     * Most bytes of one command the proxy holds to look at its arguments, which is also the longest
     * inline line Valkey reads.
     */
    private static final int HELD_LIMIT = 64 * 1024;
    /** Longest array or bulk string header line, such as {@code $536870912}. */
    private static final int HEADER_LIMIT = 32;
    private static final long MALFORMED = Long.MIN_VALUE;

    /** What the proxy does with the commands passing through. */
    interface Commands {
        /** Whether the proxy wants commands named {@code name} handed to {@link #rewrite} whole. */
        boolean wants(String name);

        /**
         * The command to pass on in place of {@code command}, or {@code command} itself to pass on the
         * bytes the client sent.
         */
        List<String> rewrite(List<String> command);

        /** The command to pass on in place of a wanted one too large to hold, whose bytes are dropped. */
        List<String> inPlaceOfTooLarge(String name);
    }

    private final InputStream in;
    private final OutputStream out;
    private final Commands commands;
    private final byte[] buffer = new byte[8192];
    private int position;
    private int limit;

    ClientCommandRelay(InputStream in, OutputStream out, Commands commands) {
        this.in = in;
        this.out = out;
        this.commands = commands;
    }

    /** Relays until the client closes its side. */
    void run() throws IOException {
        while (fill()) {
            if (buffer[position] != '*') {
                relayInline();
            } else if (!relayArray()) {
                copyRest();
                return;
            }
        }
        out.flush();
    }

    /** Relays one array; false when it was malformed, its bytes so far having been passed on. */
    private boolean relayArray() throws IOException {
        ByteArrayOutputStream held = new ByteArrayOutputStream();
        long count = header(held, '*');
        if (count == MALFORMED) {
            held.writeTo(out);
            return false;
        }
        if (count <= 0) {
            held.writeTo(out);
            return true;
        }
        long nameLength = header(held, '$');
        if (nameLength == MALFORMED) {
            held.writeTo(out);
            return false;
        }
        if (nameLength > NAME_LIMIT) {
            held.writeTo(out);
            copy(nameLength + 2);
            return streamArguments(count - 1);
        }
        byte[] name = read((int) nameLength + 2, held);
        if (name == null) {
            held.writeTo(out);
            return false;
        }
        String commandName = new String(name, 0, name.length - 2, StandardCharsets.UTF_8);
        if (!commands.wants(commandName)) {
            held.writeTo(out);
            return streamArguments(count - 1);
        }
        List<String> command = new ArrayList<>();
        command.add(commandName);
        for (long index = 1; index < count; index++) {
            long length = header(held, '$');
            if (length == MALFORMED) {
                held.writeTo(out);
                return false;
            }
            if (held.size() + length + 2 > HELD_LIMIT) {
                // Dropped unread, so credentials too large to check never reach the backend.
                skip(length + 2);
                if (!skipArguments(count - index - 1)) {
                    return false;
                }
                out.write(encode(commands.inPlaceOfTooLarge(commandName)));
                return true;
            }
            byte[] value = read((int) length + 2, held);
            if (value == null) {
                held.writeTo(out);
                return false;
            }
            command.add(new String(value, 0, value.length - 2, StandardCharsets.UTF_8));
        }
        relay(command, held);
        return true;
    }

    /** Relays one inline command and its line end. */
    private void relayInline() throws IOException {
        ByteArrayOutputStream held = new ByteArrayOutputStream();
        boolean complete = false;
        while (!complete && held.size() <= HELD_LIMIT) {
            if (!fill()) {
                held.writeTo(out);
                return;
            }
            int end = position;
            while (end < limit && buffer[end] != '\n') {
                end++;
            }
            complete = end < limit;
            int stop = complete ? end + 1 : end;
            held.write(buffer, position, stop - position);
            position = stop;
        }
        if (held.size() > HELD_LIMIT) {
            relayLongLine(held, complete);
            return;
        }
        byte[] line = held.toByteArray();
        int length = line.length - 1;
        if (length > 0 && line[length - 1] == '\r') {
            length--;
        }
        List<String> command = splitInline(line, length);
        if (command == null || command.isEmpty() || !commands.wants(command.get(0))) {
            held.writeTo(out);
        } else {
            relay(command, held);
        }
    }

    /**
     * An inline line too long to hold, named by its first word: a wanted command is dropped unread for the
     * proxy's replacement, and any other is passed on.
     */
    private void relayLongLine(ByteArrayOutputStream held, boolean complete) throws IOException {
        String name = firstWord(held.toByteArray());
        if (!name.isEmpty() && commands.wants(name)) {
            if (!complete) {
                skipLine();
            }
            out.write(encode(commands.inPlaceOfTooLarge(name)));
        } else {
            held.writeTo(out);
            if (!complete) {
                copyLine();
            }
        }
    }

    private static String firstWord(byte[] line) {
        int start = 0;
        while (start < line.length && isSpace(line[start] & 0xff)) {
            start++;
        }
        int end = start;
        while (end < line.length && !isSpace(line[end] & 0xff)) {
            end++;
        }
        return new String(line, start, end - start, StandardCharsets.UTF_8);
    }

    private void relay(List<String> command, ByteArrayOutputStream held) throws IOException {
        List<String> relayed = commands.rewrite(command);
        if (relayed == command) {
            held.writeTo(out);
        } else {
            out.write(encode(relayed));
        }
    }

    /** Drops the remaining bulk strings of an array; false when they are malformed. */
    private boolean skipArguments(long remaining) throws IOException {
        for (long index = 0; index < remaining; index++) {
            long length = header(new ByteArrayOutputStream(), '$');
            if (length == MALFORMED) {
                return false;
            }
            skip(length + 2);
        }
        return true;
    }

    /** Passes on the remaining bulk strings of an array the proxy does not look into. */
    private boolean streamArguments(long remaining) throws IOException {
        for (long index = 0; index < remaining; index++) {
            ByteArrayOutputStream header = new ByteArrayOutputStream();
            long length = header(header, '$');
            header.writeTo(out);
            if (length == MALFORMED) {
                return false;
            }
            copy(length + 2);
        }
        return true;
    }

    /**
     * Reads one header line such as {@code *3} or {@code $5}, keeping its bytes in {@code held}, and
     * returns its length, or {@link #MALFORMED}, as a bulk string's negative length also is.
     */
    private long header(ByteArrayOutputStream held, char type) throws IOException {
        StringBuilder line = new StringBuilder();
        while (line.length() <= HEADER_LIMIT) {
            if (!fill()) {
                return MALFORMED;
            }
            byte value = buffer[position++];
            held.write(value);
            if (value == '\n') {
                return parseHeader(line, type);
            }
            line.append((char) value);
        }
        return MALFORMED;
    }

    private static long parseHeader(StringBuilder line, char type) {
        if (line.length() < 3 || line.charAt(0) != type || line.charAt(line.length() - 1) != '\r') {
            return MALFORMED;
        }
        try {
            long length = Long.parseLong(line, 1, line.length() - 1, 10);
            return type == '$' && length < 0 ? MALFORMED : length;
        } catch (NumberFormatException e) {
            return MALFORMED;
        }
    }

    /** Reads {@code length} bytes that end with CRLF, keeping them in {@code held}; null if they do not. */
    private byte[] read(int length, ByteArrayOutputStream held) throws IOException {
        byte[] value = new byte[length];
        int read = 0;
        while (read < length) {
            if (!fill()) {
                held.write(value, 0, read);
                return null;
            }
            int chunk = Math.min(length - read, limit - position);
            System.arraycopy(buffer, position, value, read, chunk);
            position += chunk;
            read += chunk;
        }
        held.write(value, 0, length);
        return value[length - 2] == '\r' && value[length - 1] == '\n' ? value : null;
    }

    /** Passes on the next {@code length} bytes as they arrive. */
    private void copy(long length) throws IOException {
        long remaining = length;
        while (remaining > 0 && fill()) {
            int chunk = (int) Math.min(remaining, limit - position);
            out.write(buffer, position, chunk);
            position += chunk;
            remaining -= chunk;
        }
    }

    /** Passes on the rest of an inline line, up to and including its line feed. */
    private void copyLine() throws IOException {
        while (fill()) {
            int end = position;
            while (end < limit && buffer[end] != '\n') {
                end++;
            }
            boolean complete = end < limit;
            int stop = complete ? end + 1 : end;
            out.write(buffer, position, stop - position);
            position = stop;
            if (complete) {
                return;
            }
        }
    }

    private void skipLine() throws IOException {
        while (fill()) {
            int end = position;
            while (end < limit && buffer[end] != '\n') {
                end++;
            }
            boolean complete = end < limit;
            position = complete ? end + 1 : end;
            if (complete) {
                return;
            }
        }
    }

    private void skip(long length) throws IOException {
        long remaining = length;
        while (remaining > 0 && fill()) {
            int chunk = (int) Math.min(remaining, limit - position);
            position += chunk;
            remaining -= chunk;
        }
    }

    /**
     * Splits an inline command into its arguments as Valkey does: on blanks, with double quotes taking
     * backslash escapes and single quotes taking only an escaped single quote. Null for unbalanced
     * quotes, which the backend refuses as a protocol error.
     */
    static List<String> splitInline(byte[] line, int length) {
        List<String> arguments = new ArrayList<>();
        int index = 0;
        while (true) {
            while (byteAt(line, length, index) != 0 && isSpace(byteAt(line, length, index))) {
                index++;
            }
            if (byteAt(line, length, index) == 0) {
                return arguments;
            }
            ByteArrayOutputStream argument = new ByteArrayOutputStream();
            boolean doubleQuoted = false;
            boolean singleQuoted = false;
            boolean done = false;
            while (!done) {
                int current = byteAt(line, length, index);
                int next = byteAt(line, length, index + 1);
                if (doubleQuoted) {
                    if (current == '\\' && next == 'x' && isHex(byteAt(line, length, index + 2))
                            && isHex(byteAt(line, length, index + 3))) {
                        argument.write(Character.digit(byteAt(line, length, index + 2), 16) * 16
                                + Character.digit(byteAt(line, length, index + 3), 16));
                        index += 3;
                    } else if (current == '\\' && next != 0) {
                        index++;
                        argument.write(unescape(next));
                    } else if (current == '"') {
                        if (next != 0 && !isSpace(next)) {
                            return null;
                        }
                        done = true;
                    } else if (current == 0) {
                        return null;
                    } else {
                        argument.write(current);
                    }
                } else if (singleQuoted) {
                    if (current == '\\' && next == '\'') {
                        index++;
                        argument.write('\'');
                    } else if (current == '\'') {
                        if (next != 0 && !isSpace(next)) {
                            return null;
                        }
                        done = true;
                    } else if (current == 0) {
                        return null;
                    } else {
                        argument.write(current);
                    }
                } else {
                    switch (current) {
                        case ' ', '\n', '\r', '\t', 0 -> done = true;
                        case '"' -> doubleQuoted = true;
                        case '\'' -> singleQuoted = true;
                        default -> argument.write(current);
                    }
                }
                if (current != 0) {
                    index++;
                }
            }
            arguments.add(argument.toString(StandardCharsets.UTF_8));
        }
    }

    /** The byte at {@code index}, or 0 past the end, which ends the line as it does in Valkey. */
    private static int byteAt(byte[] line, int length, int index) {
        return index < length ? line[index] & 0xff : 0;
    }

    private static boolean isSpace(int value) {
        return value == ' ' || value == '\t' || value == '\n' || value == 0x0b || value == '\f' || value == '\r';
    }

    private static boolean isHex(int value) {
        return Character.digit(value, 16) >= 0;
    }

    private static int unescape(int value) {
        return switch (value) {
            case 'n' -> '\n';
            case 'r' -> '\r';
            case 't' -> '\t';
            case 'b' -> '\b';
            case 'a' -> 0x07;
            default -> value;
        };
    }

    private void copyRest() throws IOException {
        out.write(buffer, position, limit - position);
        position = limit;
        out.flush();
        int read;
        while ((read = in.read(buffer)) != -1) {
            out.write(buffer, 0, read);
            out.flush();
        }
    }

    /**
     * Makes sure unread bytes are buffered, first passing on everything written so far, since reading
     * may block until the client sends more. False once the client has closed its side.
     */
    private boolean fill() throws IOException {
        if (position < limit) {
            return true;
        }
        out.flush();
        int read = in.read(buffer);
        if (read == -1) {
            return false;
        }
        position = 0;
        limit = read;
        return true;
    }

    static byte[] encode(List<String> arguments) {
        ByteArrayOutputStream encoded = new ByteArrayOutputStream();
        encoded.writeBytes(("*" + arguments.size() + "\r\n").getBytes(StandardCharsets.US_ASCII));
        for (String argument : arguments) {
            byte[] bytes = argument.getBytes(StandardCharsets.UTF_8);
            encoded.writeBytes(("$" + bytes.length + "\r\n").getBytes(StandardCharsets.US_ASCII));
            encoded.writeBytes(bytes);
            encoded.writeBytes("\r\n".getBytes(StandardCharsets.US_ASCII));
        }
        return encoded.toByteArray();
    }
}
