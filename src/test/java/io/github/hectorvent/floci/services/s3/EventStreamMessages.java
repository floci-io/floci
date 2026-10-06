package io.github.hectorvent.floci.services.s3;

import java.io.ByteArrayInputStream;
import java.io.DataInputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.CRC32;

/** Splits an AWS event stream into its messages, checking the prelude and message CRCs of each. */
final class EventStreamMessages {

    private EventStreamMessages() {
    }

    record Message(Map<String, String> headers, byte[] payload) {

        /** The event type of an event message, or the message type of any other. */
        String type() {
            return headers.getOrDefault(":event-type", headers.get(":message-type"));
        }

        String text() {
            return new String(payload, StandardCharsets.UTF_8);
        }
    }

    static List<Message> decode(byte[] stream) {
        List<Message> messages = new ArrayList<>();
        int offset = 0;
        try {
            while (offset < stream.length) {
                DataInputStream in = new DataInputStream(new ByteArrayInputStream(stream, offset, stream.length - offset));
                int totalLength = in.readInt();
                int headersLength = in.readInt();
                int preludeCrc = in.readInt();
                requireCrc(stream, offset, 8, preludeCrc, "prelude");
                Map<String, String> headers = new LinkedHashMap<>();
                int headersRead = 0;
                while (headersRead < headersLength) {
                    int nameLength = in.readUnsignedByte();
                    String name = new String(in.readNBytes(nameLength), StandardCharsets.UTF_8);
                    int valueType = in.readUnsignedByte();
                    if (valueType != 7) {
                        throw new IllegalStateException("header " + name + " is not a string: type " + valueType);
                    }
                    int valueLength = in.readUnsignedShort();
                    headers.put(name, new String(in.readNBytes(valueLength), StandardCharsets.UTF_8));
                    headersRead += 1 + nameLength + 1 + 2 + valueLength;
                }
                byte[] payload = in.readNBytes(totalLength - 12 - headersLength - 4);
                int messageCrc = in.readInt();
                requireCrc(stream, offset, totalLength - 4, messageCrc, "message");
                messages.add(new Message(headers, payload));
                offset += totalLength;
            }
        } catch (IOException e) {
            throw new UncheckedIOException("Truncated event stream at byte " + offset, e);
        }
        return messages;
    }

    private static void requireCrc(byte[] stream, int offset, int length, int expected, String part) {
        CRC32 crc = new CRC32();
        crc.update(Arrays.copyOfRange(stream, offset, offset + length));
        if ((int) crc.getValue() != expected) {
            throw new IllegalStateException("Bad " + part + " CRC in the message at byte " + offset);
        }
    }
}
