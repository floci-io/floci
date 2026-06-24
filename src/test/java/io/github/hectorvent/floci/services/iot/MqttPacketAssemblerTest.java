package io.github.hectorvent.floci.services.iot;

import io.vertx.core.buffer.Buffer;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class MqttPacketAssemblerTest {

    // A small CONNECT-shaped packet: header 0x10, remaining length 3, then 3 payload bytes.
    private static Buffer packet(int header, byte... payload) {
        Buffer b = Buffer.buffer();
        b.appendByte((byte) header);
        b.appendByte((byte) payload.length);
        b.appendBytes(payload);
        return b;
    }

    @Test
    void emitsWholePacketDeliveredAtOnce() {
        MqttPacketAssembler a = new MqttPacketAssembler();
        Buffer p = packet(0x10, (byte) 1, (byte) 2, (byte) 3);
        List<Buffer> out = a.append(p);
        assertEquals(1, out.size());
        assertEquals(p, out.get(0));
    }

    @Test
    void reassemblesPacketFragmentedByteByByte() {
        MqttPacketAssembler a = new MqttPacketAssembler();
        Buffer p = packet(0x82, (byte) 0xaa, (byte) 0xbb, (byte) 0xcc, (byte) 0xdd);
        List<Buffer> collected = new java.util.ArrayList<>();
        for (int i = 0; i < p.length(); i++) {
            collected.addAll(a.append(Buffer.buffer().appendByte(p.getByte(i))));
        }
        assertEquals(1, collected.size());
        assertEquals(p, collected.get(0));
    }

    @Test
    void splitsMultiplePacketsInOneChunk() {
        MqttPacketAssembler a = new MqttPacketAssembler();
        Buffer p1 = packet(0x10, (byte) 1, (byte) 2);
        Buffer p2 = packet(0x82, (byte) 3, (byte) 4, (byte) 5);
        List<Buffer> out = a.append(Buffer.buffer().appendBuffer(p1).appendBuffer(p2));
        assertEquals(2, out.size());
        assertEquals(p1, out.get(0));
        assertEquals(p2, out.get(1));
    }

    @Test
    void handlesMultiByteRemainingLength() {
        // remaining length 200 -> encoded as two bytes: 0xC8 0x01
        Buffer b = Buffer.buffer();
        b.appendByte((byte) 0x30);
        b.appendByte((byte) 0xC8);
        b.appendByte((byte) 0x01);
        byte[] payload = new byte[200];
        b.appendBytes(payload);

        MqttPacketAssembler a = new MqttPacketAssembler();
        // feed in two chunks split inside the payload
        List<Buffer> out = a.append(b.getBuffer(0, 50));
        assertTrue(out.isEmpty());
        out = a.append(b.getBuffer(50, b.length()));
        assertEquals(1, out.size());
        assertEquals(203, out.get(0).length());
    }

    @Test
    void waitsForIncompletePacket() {
        MqttPacketAssembler a = new MqttPacketAssembler();
        Buffer header = Buffer.buffer().appendByte((byte) 0x10).appendByte((byte) 5).appendByte((byte) 1);
        assertTrue(a.append(header).isEmpty());
        List<Buffer> out = a.append(Buffer.buffer().appendBytes(new byte[]{2, 3, 4, 5}));
        assertEquals(1, out.size());
        assertEquals(7, out.get(0).length());
    }
}
