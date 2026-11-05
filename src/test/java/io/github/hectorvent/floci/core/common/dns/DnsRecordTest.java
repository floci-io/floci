package io.github.hectorvent.floci.core.common.dns;

import org.junit.jupiter.api.Test;

import java.nio.ByteBuffer;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

class DnsRecordTest {
    @Test
    void ipv6MappedIpv4StillEncodesSixteenBytes() {
        byte[] bytes = new DnsRecord.Address(28, "::ffff:192.0.2.1").data();
        assertEquals(16, bytes.length);
        assertArrayEquals(new byte[]{(byte) 192, 0, 2, 1}, new byte[]{bytes[12], bytes[13], bytes[14], bytes[15]});
    }

    @Test
    void addressTypeMustMatchItsValue() {
        assertThrows(IllegalArgumentException.class, () -> new DnsRecord.Address(1, "::1"));
        assertThrows(IllegalArgumentException.class, () -> new DnsRecord.Address(28, "192.0.2.1"));
    }

    @Test
    void srvPortIsAnUnsignedSixteenBitValue() {
        byte[] bytes = new DnsRecord.Srv(65535, "task.api.internal").data();
        assertEquals(65535, Short.toUnsignedInt(ByteBuffer.wrap(bytes).getShort(4)));
        assertThrows(IllegalArgumentException.class, () -> new DnsRecord.Srv(-1, "task.api.internal"));
        assertThrows(IllegalArgumentException.class, () -> new DnsRecord.Srv(65536, "task.api.internal"));
    }

    @Test
    void srvPreservesUnsignedFieldsAndCloudMapDefaults() {
        for (int value : List.of(0, 100, 65535)) {
            ByteBuffer data = ByteBuffer.wrap(new DnsRecord.Srv(value, value, value, "task.example.").data());
            assertEquals(value, Short.toUnsignedInt(data.getShort()));
            assertEquals(value, Short.toUnsignedInt(data.getShort()));
            assertEquals(value, Short.toUnsignedInt(data.getShort()));
            byte[] target = new byte[data.remaining()];
            data.get(target);
            assertArrayEquals(new byte[]{4, 't', 'a', 's', 'k', 7, 'e', 'x', 'a', 'm', 'p', 'l', 'e', 0}, target);
        }
        assertArrayEquals(new DnsRecord.Srv(1, 1, 8080, "task.example").data(),
                new DnsRecord.Srv(8080, "task.example").data());
        assertArrayEquals(new byte[7], new DnsRecord.Srv(0, 0, 0, ".").data());
    }

    @Test
    void srvRejectsOutOfRangePriorityWeightAndPort() {
        for (int value : List.of(-1, 65536)) {
            assertThrows(IllegalArgumentException.class, () -> new DnsRecord.Srv(value, 0, 0, "task.example"));
            assertThrows(IllegalArgumentException.class, () -> new DnsRecord.Srv(0, value, 0, "task.example"));
            assertThrows(IllegalArgumentException.class, () -> new DnsRecord.Srv(0, 0, value, "task.example"));
        }
    }

    @Test
    void namesEncodeLabelsAndIgnoreOneTrailingDot() {
        assertArrayEquals(new byte[]{1, 'a', 1, 'b', 0}, DnsRecord.encodeName("a.b."));
        assertThrows(IllegalArgumentException.class, () -> DnsRecord.encodeName("a..b"));
        assertThrows(IllegalArgumentException.class, () -> DnsRecord.encodeName("a".repeat(64) + ".b"));
        assertThrows(IllegalArgumentException.class, () -> DnsRecord.encodeName("nonascii.\u00e9"));
    }

    @Test
    void ipv4ProjectionDoesNotIncludeNamesOrIpv6Values() {
        DnsAnswer answer = DnsAnswer.typedRecords(List.of(new DnsRecord.Address(28, "::1")), 15);
        assertEquals(List.of(), answer.addresses());
        assertEquals(1, answer.records().size());
        assertFalse(answer.isEmpty());
    }

    @Test
    void txtPreservesQuotedSegmentsEscapesAndEmptyStrings() {
        assertArrayEquals(new byte[]{3, 'a', ' ', 'b', 2, '"', '\\', 1, (byte) 255, 0},
                new DnsRecord.Txt("\"a b\" \"\\\"\\\\\" \"\\377\" \"\"").data());
        assertEquals(256, new DnsRecord.Txt("\"" + "x".repeat(255) + "\"").data().length);
        assertThrows(IllegalArgumentException.class,
                () -> new DnsRecord.Txt("\"" + "x".repeat(256) + "\"").data());
        assertThrows(IllegalArgumentException.class, () -> new DnsRecord.Txt("\"\\400\"").data());
        assertThrows(IllegalArgumentException.class, () -> new DnsRecord.Txt("\"unclosed").data());
    }

    @Test
    void soaEncodesNamesAndUnsignedFields() {
        byte[] bytes = new DnsRecord.Soa("ns.example. host.example. 4294967295 7200 900 1209600 30").data();
        ByteBuffer packet = ByteBuffer.wrap(bytes);
        assertEquals("ns.example", EmbeddedDnsServer.readName(packet, bytes));
        assertEquals("host.example", EmbeddedDnsServer.readName(packet, bytes));
        assertEquals(4294967295L, Integer.toUnsignedLong(packet.getInt()));
        assertEquals(7200, packet.getInt());
        assertEquals(900, packet.getInt());
        assertEquals(1209600, packet.getInt());
        assertEquals(30, packet.getInt());
        assertArrayEquals(DnsRecord.encodeName("ns.example."), new DnsRecord.Ns("ns.example.").data());
    }
}
