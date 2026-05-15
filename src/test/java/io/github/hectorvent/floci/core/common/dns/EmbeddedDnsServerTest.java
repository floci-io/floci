package io.github.hectorvent.floci.core.common.dns;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.nio.ByteBuffer;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class EmbeddedDnsServerTest {

    private EmbeddedDnsServer dns;

    @BeforeEach
    void setUp() {
        dns = new EmbeddedDnsServer(List.of("localhost.floci.io"));
    }

    // ── matchesSuffix — configured suffix ────────────────────────────────────

    @Test
    void matchesSuffix_exactMatch() {
        assertTrue(dns.matchesSuffix("localhost.floci.io"));
    }

    @Test
    void matchesSuffix_singleSubdomain() {
        assertTrue(dns.matchesSuffix("my-bucket.localhost.floci.io"));
    }

    @Test
    void matchesSuffix_deeplyNested() {
        assertTrue(dns.matchesSuffix("deeply.nested.bucket.localhost.floci.io"));
    }

    @Test
    void matchesSuffix_caseInsensitive() {
        assertTrue(dns.matchesSuffix("My-Bucket.Localhost.Floci.IO"));
    }

    @Test
    void matchesSuffix_noMatch() {
        assertFalse(dns.matchesSuffix("my-bucket.s3.amazonaws.com"));
    }

    @Test
    void matchesSuffix_partialSuffixNoMatch() {
        assertFalse(dns.matchesSuffix("floci.io"));
    }

    // bare *.floci.io (without localhost.) must NOT match — only *.localhost.floci.io is registered
    @Test
    void matchesSuffix_bareFlociIoSubdomainNoMatch() {
        assertFalse(dns.matchesSuffix("my-bucket.floci.io"));
    }

    @Test
    void matchesSuffix_bareS3FlociIoNoMatch() {
        assertFalse(dns.matchesSuffix("s3.floci.io"));
    }

    // bare *.localstack.cloud (without localhost.) must NOT match either
    @Test
    void matchesSuffix_bareLocalstackCloudNoMatch() {
        assertFalse(dns.matchesSuffix("my-bucket.localstack.cloud"));
    }

    @Test
    void matchesSuffix_nullAndEmpty() {
        assertFalse(dns.matchesSuffix(null));
        assertFalse(dns.matchesSuffix(""));
    }

    // ── matchesSuffix — built-in emulator domains ─────────────────────────────

    @Test
    void matchesSuffix_localhostFlociIo_exact() {
        assertTrue(dns.matchesSuffix("localhost.floci.io"));
    }

    @Test
    void matchesSuffix_localhostFlociIo_subdomain() {
        assertTrue(dns.matchesSuffix("my-bucket.localhost.floci.io"));
    }

    @Test
    void matchesSuffix_s3LocalhostFlociIo() {
        assertTrue(dns.matchesSuffix("s3.localhost.floci.io"));
    }

    @Test
    void matchesSuffix_bucketS3LocalhostFlociIo() {
        assertTrue(dns.matchesSuffix("my-bucket.s3.localhost.floci.io"));
    }

    @Test
    void matchesSuffix_localhostLocalstackCloud_exact() {
        assertTrue(dns.matchesSuffix("localhost.localstack.cloud"));
    }

    @Test
    void matchesSuffix_localhostLocalstackCloud_subdomain() {
        assertTrue(dns.matchesSuffix("my-bucket.localhost.localstack.cloud"));
    }

    @Test
    void matchesSuffix_s3LocalhostLocalstackCloud() {
        assertTrue(dns.matchesSuffix("s3.localhost.localstack.cloud"));
    }

    @Test
    void matchesSuffix_bucketS3LocalhostLocalstackCloud() {
        assertTrue(dns.matchesSuffix("my-bucket.s3.localhost.localstack.cloud"));
    }

    @Test
    void matchesSuffix_bucketS3RegionLocalstackCloud() {
        assertTrue(dns.matchesSuffix("my-bucket.s3.us-east-1.localhost.localstack.cloud"));
    }

    // ── readName ──────────────────────────────────────────────────────────────

    @Test
    void readName_simple() {
        // my-bucket.localhost.floci.io encoded as DNS labels
        byte[] encoded = encodeName("my-bucket.localhost.floci.io");
        ByteBuffer buf = ByteBuffer.wrap(encoded);
        assertEquals("my-bucket.localhost.floci.io", dns.readName(buf, encoded));
    }

    @Test
    void readName_singleLabel() {
        byte[] encoded = encodeName("floci");
        ByteBuffer buf = ByteBuffer.wrap(encoded);
        assertEquals("floci", dns.readName(buf, encoded));
    }

    @Test
    void readName_withCompressionPointer() {
        // Build a buffer where the name at offset 12 is "floci.io" and
        // a pointer at offset 0 points to it.
        byte[] data = new byte[20];
        // pointer at offset 0 → offset 4
        data[0] = (byte) 0xC0;
        data[1] = 0x04;
        // "floci.io" at offset 4
        byte[] name = encodeName("floci.io");
        System.arraycopy(name, 0, data, 4, name.length);

        ByteBuffer buf = ByteBuffer.wrap(data);
        assertEquals("floci.io", dns.readName(buf, data));
    }

    // ── buildAResponse ────────────────────────────────────────────────────────

    @Test
    void buildAResponse_hasCorrectTransactionId() {
        byte[] query = buildQuery("my-bucket.localhost.floci.io", (short) 0x1234);
        byte[] response = dns.buildAResponse(query, (short) 0x1234, 12, query.length, "172.19.0.2");
        short txId = ByteBuffer.wrap(response).getShort(0);
        assertEquals((short) 0x1234, txId);
    }

    @Test
    void buildAResponse_flagsIndicateResponse() {
        byte[] query = buildQuery("bucket.localhost.floci.io", (short) 1);
        byte[] response = dns.buildAResponse(query, (short) 1, 12, query.length, "10.0.0.1");
        short flags = ByteBuffer.wrap(response).getShort(2);
        assertTrue((flags & 0x8000) != 0, "QR bit must be set");
    }

    @Test
    void buildAResponse_answerCountIsOne() {
        byte[] query = buildQuery("bucket.localhost.floci.io", (short) 2);
        byte[] response = dns.buildAResponse(query, (short) 2, 12, query.length, "10.0.0.1");
        short ancount = ByteBuffer.wrap(response).getShort(6);
        assertEquals(1, ancount);
    }

    @Test
    void buildAResponse_ipAddressIsCorrect() {
        byte[] query = buildQuery("bucket.localhost.floci.io", (short) 3);
        byte[] response = dns.buildAResponse(query, (short) 3, 12, query.length, "172.19.0.42");
        // IP starts at offset: 12 (header) + questionLength + 2+2+2+4+2 = questionLength + 24
        int questionLength = query.length - 12;
        ByteBuffer resp = ByteBuffer.wrap(response);
        resp.position(12 + questionLength + 10); // skip header + question + name-ptr(2) + type(2) + class(2) + ttl(4)
        short rdlen = resp.getShort();
        assertEquals(4, rdlen);
        assertEquals((byte) 172, resp.get());
        assertEquals((byte) 19, resp.get());
        assertEquals((byte) 0, resp.get());
        assertEquals((byte) 42, resp.get());
    }

    // ── helpers ───────────────────────────────────────────────────────────────

    private byte[] encodeName(String name) {
        String[] labels = name.split("\\.");
        int len = 1; // trailing zero
        for (String l : labels) len += 1 + l.length();
        byte[] buf = new byte[len];
        int pos = 0;
        for (String label : labels) {
            buf[pos++] = (byte) label.length();
            for (char c : label.toCharArray()) buf[pos++] = (byte) c;
        }
        buf[pos] = 0;
        return buf;
    }

    private byte[] buildQuery(String name, short txId) {
        byte[] encodedName = encodeName(name);
        // header(12) + name + type(2) + class(2)
        ByteBuffer buf = ByteBuffer.allocate(12 + encodedName.length + 4);
        buf.putShort(txId);
        buf.putShort((short) 0x0100); // standard query, RD=1
        buf.putShort((short) 1);       // qdcount
        buf.putShort((short) 0);
        buf.putShort((short) 0);
        buf.putShort((short) 0);
        buf.put(encodedName);
        buf.putShort((short) 1); // type A
        buf.putShort((short) 1); // class IN
        return buf.array();
    }
}
