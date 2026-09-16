package io.github.hectorvent.floci.services.elasticache.container;

import java.io.IOException;
import java.io.InputStream;

/**
 * Reads a single CRLF-terminated ASCII line off a RESP connection, used while probing a
 * backend container for readiness. Reused by {@code MemoryDbContainerManager} because
 * MemoryDB speaks the same Redis wire protocol as ElastiCache, the same reasoning
 * {@code AbstractRedisAuthProxy} follows for the auth-proxy logic.
 */
public final class RespLineReader {

    private RespLineReader() {
    }

    public static String readAsciiLineCrLf(InputStream in) throws IOException {
        StringBuilder sb = new StringBuilder();
        int b;
        while ((b = in.read()) != -1) {
            if (b == '\r') {
                int next = in.read();
                if (next != '\n') {
                    throw new IOException("Expected \\n after \\r in RESP line");
                }
                break;
            }
            sb.append((char) b);
        }
        return sb.toString();
    }
}
