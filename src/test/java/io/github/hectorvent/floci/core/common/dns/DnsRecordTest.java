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
}
