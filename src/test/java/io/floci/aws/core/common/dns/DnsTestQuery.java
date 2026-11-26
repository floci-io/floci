package io.floci.aws.core.common.dns;

import com.github.dockerjava.api.DockerClient;
import io.floci.aws.core.common.docker.ContainerExec;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.HexFormat;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

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

    static byte[] queryGuest(DockerClient docker, String containerId, String name, String address, int port)
            throws Exception {
        byte[] packet = DnsTestQuery.query(name, 1, 0x1234);
        ContainerExec.Result result = ContainerExec.run(docker, containerId,
                new String[] {"python", "-c",
                        "import socket,sys; s=socket.socket(socket.AF_INET,socket.SOCK_DGRAM); s.settimeout(3); "
                                + "s.sendto(bytes.fromhex(sys.argv[1]),(sys.argv[2],int(sys.argv[3]))); "
                                + "print(s.recvfrom(4096)[0].hex()); s.close()",
                        HexFormat.of().formatHex(packet), address, Integer.toString(port)}, 10);
        assertEquals(0, result.exitCode(), result.summary());
        byte[] response = HexFormat.of().parseHex(result.stdout().replaceAll("\\s", ""));
        assertTrue(response.length >= 12, "Guest must receive an actual DNS packet: " + result.summary());
        assertEquals(0x1234, ByteBuffer.wrap(response).getShort(0) & 65535);
        return response;
    }
}
