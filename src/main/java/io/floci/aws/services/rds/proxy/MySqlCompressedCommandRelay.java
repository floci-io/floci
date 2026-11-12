package io.floci.aws.services.rds.proxy;

import io.airlift.compress.MalformedInputException;
import io.airlift.compress.zstd.ZstdInputStream;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.zip.InflaterInputStream;

/** Rewrites IAM user DDL without making compressed MySQL clients wait for the next command. */
final class MySqlCompressedCommandRelay {

    private static final int MAX_PACKET_PAYLOAD = 0xFFFFFF;
    private static final int MAX_PENDING_FRAMES = 65536;
    private static final int MAX_INSPECTION_BYTES = 1 << 20;
    private static final int MAX_UNRESOLVED_PREFIX_BYTES = 1 << 12;
    private static final int COM_QUERY = 0x03;

    private MySqlCompressedCommandRelay() {}

    static void relay(InputStream from, OutputStream to, boolean zstd) throws IOException {
        relay(from, to, zstd, false);
    }

    static void relay(InputStream from, OutputStream to, boolean zstd, boolean queryAttributes)
            throws IOException {
        PacketBuffer pending = new PacketBuffer();
        List<byte[]> frames = new ArrayList<>();
        boolean forwardedTail = false;
        boolean continuingPacket = false;
        boolean confirmedUserDdl = false;
        long bufferedEncodedBytes = 0;
        int passthroughRemaining = 0;
        byte[] frame;
        while ((frame = readFrame(from)) != null) {
            int declaredLength = int24(frame, 4);
            int frameLength = declaredLength == 0 ? frame.length - 7 : declaredLength;
            if (passthroughRemaining >= frameLength) {
                passthroughRemaining -= frameLength;
                to.write(frame);
                to.flush();
                continue;
            }
            int bypassPrefix = passthroughRemaining;
            passthroughRemaining = 0;
            if (bypassPrefix == 0 && pending.size() == 0 && frameLength >= 4) {
                int firstPacketLength = firstInnerPacketLength(frame, zstd);
                if (firstPacketLength > MAX_INSPECTION_BYTES && firstPacketLength + 4 >= frameLength) {
                    passthroughRemaining = firstPacketLength + 4 - frameLength;
                    continuingPacket = firstPacketLength == MAX_PACKET_PAYLOAD;
                    to.write(frame);
                    to.flush();
                    continue;
                }
            }
            int previouslyForwarded = forwardedTail ? pending.size() : 0;
            frames.add(frame);
            bufferedEncodedBytes += frame.length;
            if (frames.size() > MAX_PENDING_FRAMES) {
                throw new IOException("Too many compressed MySQL frames for one command");
            }
            pending.writeBytes(decode(frame, zstd));
            byte[] input = pending.bytes();
            int available = pending.size();
            int offset = bypassPrefix;
            ByteArrayOutputStream output = new ByteArrayOutputStream();
            if (bypassPrefix > 0) {
                output.write(input, 0, bypassPrefix);
            }
            boolean changed = false;
            while (available - offset >= 4) {
                int length = int24(input, offset);
                if (available - offset < 4 + length) {
                    break;
                }
                byte[] raw = Arrays.copyOfRange(input, offset, offset + 4 + length);
                byte[] replacement = (forwardedTail && offset < previouslyForwarded)
                        || continuingPacket || length > MAX_INSPECTION_BYTES
                        || length == 0 || (raw[4] & 0xFF) != COM_QUERY || !isUserDdl(raw, queryAttributes)
                        ? null : MySqlProtocolHandler.rewriteIamAuthPlugin(
                                Arrays.copyOfRange(raw, 4, raw.length));
                continuingPacket = length == MAX_PACKET_PAYLOAD;
                if (replacement != null && replacement.length < MAX_PACKET_PAYLOAD) {
                    writeInnerPacket(output, raw[3] & 0xFF, replacement);
                    changed = true;
                } else {
                    output.writeBytes(raw);
                }
                offset += raw.length;
            }
            if (offset == bypassPrefix) {
                if (bypassPrefix > 0) {
                    to.write(frame);
                    to.flush();
                    pending = new PacketBuffer();
                    pending.write(input, bypassPrefix, available - bypassPrefix);
                    frames.clear();
                    bufferedEncodedBytes = 0;
                    confirmedUserDdl = false;
                    forwardedTail = pending.size() != 0;
                    continue;
                }
                if (available > MAX_PACKET_PAYLOAD + 4) {
                    throw new IOException("Incomplete compressed MySQL packet exceeds protocol limit");
                }
                if (available >= 4) {
                    PrefixStatus prefix = confirmedUserDdl ? PrefixStatus.USER_DDL
                            : inspectUserDdlPrefix(input, available, queryAttributes);
                    confirmedUserDdl = prefix == PrefixStatus.USER_DDL;
                    if (forwardedTail || available > MAX_INSPECTION_BYTES
                            || bufferedEncodedBytes > MAX_INSPECTION_BYTES
                            || (prefix == PrefixStatus.POSSIBLE && available > MAX_UNRESOLVED_PREFIX_BYTES)
                            || prefix == PrefixStatus.OTHER) {
                        int length = int24(input, 0);
                        passthroughRemaining = length + 4 - available;
                        continuingPacket = length == MAX_PACKET_PAYLOAD;
                        for (byte[] original : frames) {
                            to.write(original);
                        }
                        to.flush();
                        pending = new PacketBuffer();
                        frames.clear();
                        bufferedEncodedBytes = 0;
                        confirmedUserDdl = false;
                        forwardedTail = false;
                        continue;
                    }
                } else if (forwardedTail || bufferedEncodedBytes > MAX_INSPECTION_BYTES) {
                    for (byte[] original : frames) {
                        to.write(original);
                    }
                    to.flush();
                    frames.clear();
                    bufferedEncodedBytes = 0;
                    forwardedTail = available != 0;
                    continue;
                }
                continue;
            }
            byte[] tail = Arrays.copyOfRange(input, offset, available);
            // A partial next packet sent to the backend must pass through until its end.
            if (!changed) {
                for (byte[] original : frames) {
                    to.write(original);
                }
            } else {
                byte[] completed = output.toByteArray();
                byte[] replacement = Arrays.copyOfRange(completed, previouslyForwarded,
                        completed.length + tail.length);
                System.arraycopy(tail, 0, replacement, completed.length - previouslyForwarded, tail.length);
                if (replacement.length >= frames.size()
                        && (long) replacement.length <= (long) frames.size() * MAX_PACKET_PAYLOAD) {
                    writeOutputFrames(to, replacement, frames);
                } else {
                    for (byte[] original : frames) {
                        to.write(original);
                    }
                }
            }
            to.flush();
            pending = new PacketBuffer();
            pending.writeBytes(tail);
            frames.clear();
            bufferedEncodedBytes = 0;
            confirmedUserDdl = false;
            forwardedTail = tail.length != 0;
            if (forwardedTail && tail.length >= 4) {
                int length = int24(tail, 0);
                passthroughRemaining = length + 4 - tail.length;
                continuingPacket = length == MAX_PACKET_PAYLOAD;
                pending = new PacketBuffer();
                forwardedTail = false;
            }
        }
    }

    private static int firstInnerPacketLength(byte[] frame, boolean zstd) throws IOException {
        if (int24(frame, 4) == 0) {
            return int24(frame, 7);
        }
        try (InputStream decoder = zstd
                ? new ZstdInputStream(new ByteArrayInputStream(frame, 7, frame.length - 7))
                : new InflaterInputStream(new ByteArrayInputStream(frame, 7, frame.length - 7))) {
            byte[] header = decoder.readNBytes(4);
            return header.length == 4 ? int24(header, 0) : -1;
        }
    }

    private static PrefixStatus inspectUserDdlPrefix(byte[] bytes, int available, boolean queryAttributes) {
        if (available < 5) {
            return PrefixStatus.POSSIBLE;
        }
        if ((bytes[4] & 0xFF) != COM_QUERY) {
            return PrefixStatus.OTHER;
        }
        int offset = 5;
        if (queryAttributes) {
            if (available - offset < 2) {
                return PrefixStatus.POSSIBLE;
            }
            if (bytes[offset] != 0 || bytes[offset + 1] != 1) {
                return PrefixStatus.OTHER;
            }
            offset += 2;
        }
        offset = skipIgnored(bytes, offset, available);
        if (offset == available || couldStartComment(bytes, offset, available)) {
            return PrefixStatus.POSSIBLE;
        }
        String verb = matchesKeywordPrefix(bytes, offset, available, "CREATE") ? "CREATE"
                : matchesKeywordPrefix(bytes, offset, available, "ALTER") ? "ALTER" : null;
        if (verb == null) {
            return PrefixStatus.OTHER;
        }
        if (available - offset < verb.length()) {
            return PrefixStatus.POSSIBLE;
        }
        int afterVerb = offset + verb.length();
        if (afterVerb == available) {
            return PrefixStatus.POSSIBLE;
        }
        offset = skipIgnored(bytes, afterVerb, available);
        if (offset == afterVerb) {
            return couldStartComment(bytes, offset, available) ? PrefixStatus.POSSIBLE : PrefixStatus.OTHER;
        }
        if (offset == available || couldStartComment(bytes, offset, available)) {
            return PrefixStatus.POSSIBLE;
        }
        if (!matchesKeywordPrefix(bytes, offset, available, "USER")) {
            return PrefixStatus.OTHER;
        }
        if (available - offset < 4) {
            return PrefixStatus.POSSIBLE;
        }
        int afterUser = offset + 4;
        if (afterUser == available) {
            return PrefixStatus.POSSIBLE;
        }
        return skipIgnored(bytes, afterUser, available) > afterUser ? PrefixStatus.USER_DDL
                : couldStartComment(bytes, afterUser, available) ? PrefixStatus.POSSIBLE : PrefixStatus.OTHER;
    }

    private static boolean couldStartComment(byte[] bytes, int offset, int available) {
        return offset + 1 == available && (bytes[offset] == '/' || bytes[offset] == '-')
                || offset + 2 == available && bytes[offset] == '-' && bytes[offset + 1] == '-';
    }

    private static boolean matchesKeywordPrefix(byte[] bytes, int offset, int available, String keyword) {
        int length = Math.min(available - offset, keyword.length());
        for (int i = 0; i < length; i++) {
            if (Character.toUpperCase((char) (bytes[offset + i] & 0xFF)) != keyword.charAt(i)) {
                return false;
            }
        }
        return true;
    }

    private static boolean isUserDdl(byte[] packet, boolean queryAttributes) {
        int offset = 5;
        if (queryAttributes) {
            // Nonempty attributes have typed values; relay them unchanged without guessing the SQL offset.
            if (packet.length - offset < 2 || packet[offset] != 0 || packet[offset + 1] != 1) {
                return false;
            }
            offset += 2;
        }
        offset = skipIgnored(packet, offset);
        int verbLength;
        if (matchesKeyword(packet, offset, "CREATE")) {
            verbLength = 6;
        } else if (matchesKeyword(packet, offset, "ALTER")) {
            verbLength = 5;
        } else {
            return false;
        }
        int afterVerb = offset + verbLength;
        offset = skipIgnored(packet, afterVerb);
        if (offset <= afterVerb) {
            return false;
        }
        return matchesKeyword(packet, offset, "USER")
                && (offset + 4 == packet.length || skipIgnored(packet, offset + 4) > offset + 4);
    }

    private static int skipIgnored(byte[] bytes, int offset) {
        return skipIgnored(bytes, offset, bytes.length);
    }

    private static int skipIgnored(byte[] bytes, int offset, int limit) {
        while (offset < limit) {
            if (isSqlSpace(bytes[offset])) {
                offset++;
            } else if (bytes[offset] == '/' && offset + 1 < limit && bytes[offset + 1] == '*') {
                offset += 2;
                while (offset + 1 < limit && !(bytes[offset] == '*' && bytes[offset + 1] == '/')) {
                    offset++;
                }
                if (offset + 1 >= limit) {
                    return limit;
                }
                offset += 2;
            } else if (bytes[offset] == '#' || (bytes[offset] == '-' && offset + 2 < limit
                    && bytes[offset + 1] == '-' && isSqlSpace(bytes[offset + 2]))) {
                while (offset < limit && bytes[offset] != '\n' && bytes[offset] != '\r') {
                    offset++;
                }
            } else {
                break;
            }
        }
        return offset;
    }

    private static boolean isSqlSpace(byte value) {
        return value == ' ' || value == '\t' || value == '\r' || value == '\n' || value == '\f';
    }

    private static boolean matchesKeyword(byte[] bytes, int offset, String keyword) {
        if (bytes.length - offset < keyword.length()) {
            return false;
        }
        for (int i = 0; i < keyword.length(); i++) {
            if (Character.toUpperCase((char) (bytes[offset + i] & 0xFF)) != keyword.charAt(i)) {
                return false;
            }
        }
        return true;
    }

    private static byte[] readFrame(InputStream from) throws IOException {
        int first = from.read();
        if (first < 0) {
            return null;
        }
        byte[] header = from.readNBytes(6);
        if (header.length != 6) {
            throw new EOFException("Incomplete compressed MySQL header");
        }
        int length = first | ((header[0] & 0xFF) << 8) | ((header[1] & 0xFF) << 16);
        byte[] payload = from.readNBytes(length);
        if (payload.length != length) {
            throw new EOFException("Incomplete compressed MySQL payload");
        }
        byte[] frame = new byte[7 + length];
        frame[0] = (byte) first;
        System.arraycopy(header, 0, frame, 1, 6);
        System.arraycopy(payload, 0, frame, 7, length);
        return frame;
    }

    private static byte[] decode(byte[] frame, boolean zstd) throws IOException {
        int uncompressedLength = int24(frame, 4);
        byte[] payload = Arrays.copyOfRange(frame, 7, frame.length);
        if (uncompressedLength == 0) {
            return payload;
        }
        try (InputStream decoder = zstd
                ? new ZstdInputStream(new ByteArrayInputStream(payload))
                : new InflaterInputStream(new ByteArrayInputStream(payload))) {
            byte[] decoded = decoder.readNBytes(uncompressedLength + 1);
            if (decoded.length != uncompressedLength) {
                throw new IOException("Compressed MySQL payload length mismatch");
            }
            return decoded;
        } catch (MalformedInputException e) {
            throw new IOException("Invalid compressed MySQL zstd payload", e);
        }
    }

    private static void writeInnerPacket(OutputStream to, int sequence, byte[] payload) throws IOException {
        int length = payload.length;
        to.write(length & 0xFF);
        to.write((length >> 8) & 0xFF);
        to.write((length >> 16) & 0xFF);
        to.write(sequence);
        to.write(payload);
    }

    private static void writeOutputFrames(OutputStream to, byte[] payload, List<byte[]> originals) throws IOException {
        if ((long) payload.length > (long) originals.size() * MAX_PACKET_PAYLOAD) {
            throw new IOException("Rewritten MySQL command cannot preserve compressed frame sequence");
        }
        int offset = 0;
        for (int i = 0; i < originals.size(); i++) {
            int remainingFrames = originals.size() - i;
            int length = (payload.length - offset + remainingFrames - 1) / remainingFrames;
            to.write(length & 0xFF);
            to.write((length >> 8) & 0xFF);
            to.write((length >> 16) & 0xFF);
            to.write(originals.get(i)[3]);
            to.write(0);
            to.write(0);
            to.write(0);
            to.write(payload, offset, length);
            offset += length;
        }
    }

    private static int int24(byte[] bytes, int offset) {
        return (bytes[offset] & 0xFF) | ((bytes[offset + 1] & 0xFF) << 8)
                | ((bytes[offset + 2] & 0xFF) << 16);
    }

    private static final class PacketBuffer extends ByteArrayOutputStream {
        byte[] bytes() {
            return buf;
        }
    }

    private enum PrefixStatus {
        OTHER, POSSIBLE, USER_DDL
    }
}
