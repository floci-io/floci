package io.floci.aws.core.common.dns;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;

final class DnsTestQuery {

    private DnsTestQuery() {
    }

    static byte[] query(String name, int type, int transactionId) {
        ByteBuffer query = ByteBuffer.allocate(512);
        query.putShort((short) transactionId).putShort((short) 0x0100).putShort((short) 1);
        query.putShort((short) 0).putShort((short) 0).putShort((short) 0);
        for (String label : name.split("\\.")) {
            byte[] encoded = label.getBytes(StandardCharsets.US_ASCII);
            query.put((byte) encoded.length).put(encoded);
        }
        query.put((byte) 0).putShort((short) type).putShort((short) 1);
        return Arrays.copyOf(query.array(), query.position());
    }
}
