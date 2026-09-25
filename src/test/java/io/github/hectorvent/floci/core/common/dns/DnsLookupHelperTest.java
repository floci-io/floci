package io.github.hectorvent.floci.core.common.dns;

import org.junit.jupiter.api.Test;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DnsLookupHelperTest {

    @Test
    void parseARecordsRejectsMismatchedTxId() {
        byte[] resp = buildSyntheticDnsResponse((short) 100, "example.com", "93.184.216.34");
        List<String> result = DnsLookupHelper.parseARecordsFromDnsResponse(
                resp, resp.length, (short) 200, "example.com");
        assertTrue(result.isEmpty());
    }

    @Test
    void parseARecordsRejectsMismatchedQuestionName() {
        byte[] resp = buildSyntheticDnsResponse((short) 100, "other.com", "93.184.216.34");
        List<String> result = DnsLookupHelper.parseARecordsFromDnsResponse(
                resp, resp.length, (short) 100, "example.com");
        assertTrue(result.isEmpty());
    }

    @Test
    void parseARecordsAcceptsValidMatchingResponse() {
        byte[] resp = buildSyntheticDnsResponse((short) 100, "example.com", "93.184.216.34");
        List<String> result = DnsLookupHelper.parseARecordsFromDnsResponse(
                resp, resp.length, (short) 100, "example.com");
        assertEquals(List.of("93.184.216.34"), result);
    }

    @Test
    void parseARecordsAcceptsCnameTargetBeforeCnameRecord() {
        ByteBuffer buf = ByteBuffer.allocate(512);
        short txId = 100;
        buf.putShort(txId);
        buf.putShort((short) 0x8180);
        buf.putShort((short) 1);
        buf.putShort((short) 2);
        buf.putShort((short) 0);
        buf.putShort((short) 0);

        writeDnsName(buf, "alias.com");
        buf.putShort((short) 1);
        buf.putShort((short) 1);

        writeDnsName(buf, "target.com");
        buf.putShort((short) 1);
        buf.putShort((short) 1);
        buf.putInt(300);
        buf.putShort((short) 4);
        buf.put((byte) 93);
        buf.put((byte) 184);
        buf.put((byte) 216);
        buf.put((byte) 34);

        writeDnsName(buf, "alias.com");
        buf.putShort((short) 5);
        buf.putShort((short) 1);
        buf.putInt(300);
        int rdLengthPos = buf.position();
        buf.putShort((short) 0);
        int rdataStart = buf.position();
        writeDnsName(buf, "target.com");
        int rdataEnd = buf.position();
        buf.putShort(rdLengthPos, (short) (rdataEnd - rdataStart));

        byte[] resp = new byte[buf.position()];
        System.arraycopy(buf.array(), 0, resp, 0, resp.length);

        List<String> result = DnsLookupHelper.parseARecordsFromDnsResponse(
                resp, resp.length, txId, "alias.com");
        assertEquals(List.of("93.184.216.34"), result);
    }

    @Test
    void parseARecordsHandlesCyclicCompressionPointersWithoutStackOverflow() {
        ByteBuffer buf = ByteBuffer.allocate(512);
        short txId = 100;
        buf.putShort(txId);
        buf.putShort((short) 0x8180);
        buf.putShort((short) 1);
        buf.putShort((short) 0);
        buf.putShort((short) 0);
        buf.putShort((short) 0);

        int cycleOffset = buf.position();
        buf.putShort((short) (0xC000 | cycleOffset));
        buf.putShort((short) 1);
        buf.putShort((short) 1);

        byte[] resp = new byte[buf.position()];
        System.arraycopy(buf.array(), 0, resp, 0, resp.length);

        List<String> result = DnsLookupHelper.parseARecordsFromDnsResponse(
                resp, resp.length, txId, "example.com");
        assertTrue(result.isEmpty());
    }

    private void writeDnsName(ByteBuffer buf, String name) {
        for (String label : name.split("\\.")) {
            byte[] bytes = label.getBytes(StandardCharsets.US_ASCII);
            buf.put((byte) bytes.length);
            buf.put(bytes);
        }
        buf.put((byte) 0);
    }

    private byte[] buildSyntheticDnsResponse(short txId, String questionName, String ip) {
        ByteBuffer buf = ByteBuffer.allocate(512);
        buf.putShort(txId);
        buf.putShort((short) 0x8180);
        buf.putShort((short) 1);
        buf.putShort((short) 1);
        buf.putShort((short) 0);
        buf.putShort((short) 0);

        writeDnsName(buf, questionName);
        buf.putShort((short) 1);
        buf.putShort((short) 1);

        writeDnsName(buf, questionName);
        buf.putShort((short) 1);
        buf.putShort((short) 1);
        buf.putInt(300);
        buf.putShort((short) 4);
        for (String octet : ip.split("\\.")) {
            buf.put((byte) Integer.parseInt(octet));
        }

        byte[] result = new byte[buf.position()];
        System.arraycopy(buf.array(), 0, result, 0, result.length);
        return result;
    }
}
