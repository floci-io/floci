package io.github.hectorvent.floci.services.iot;

import io.vertx.core.buffer.Buffer;

import java.util.ArrayList;
import java.util.List;

/**
 * Reassembles complete MQTT control packets from an arbitrarily chunked byte stream.
 *
 * <p>WebSocket clients fragment the MQTT stream across many small frames; this accumulator
 * buffers incoming bytes and emits each whole MQTT packet (fixed header + variable-length
 * "remaining length" + payload) once fully available. Not thread-safe — drive it from a single
 * event loop per connection.
 */
final class MqttPacketAssembler {

    private Buffer pending = Buffer.buffer();

    /**
     * Appends bytes and returns any complete MQTT packets now available, in order.
     */
    List<Buffer> append(Buffer data) {
        pending.appendBuffer(data);
        List<Buffer> packets = new ArrayList<>();
        int packetLength;
        while ((packetLength = nextPacketLength()) > 0) {
            packets.add(pending.getBuffer(0, packetLength));
            pending = pending.getBuffer(packetLength, pending.length());
        }
        return packets;
    }

    /**
     * Returns the byte length of the leading complete packet in {@code pending}, or -1 if a full
     * packet is not yet buffered.
     */
    private int nextPacketLength() {
        int len = pending.length();
        if (len < 2) {
            return -1;
        }
        int multiplier = 1;
        int remaining = 0;
        int pos = 1;
        int digit;
        do {
            if (pos >= len) {
                return -1; // remaining-length field not fully received yet
            }
            digit = pending.getByte(pos) & 0xff;
            remaining += (digit & 0x7f) * multiplier;
            multiplier *= 128;
            pos++;
        } while ((digit & 0x80) != 0 && pos <= 4);

        if ((digit & 0x80) != 0) {
            // Malformed remaining length (> 4 bytes); drop the buffer to resync.
            pending = Buffer.buffer();
            return -1;
        }
        int total = pos + remaining;
        return pending.length() >= total ? total : -1;
    }
}
