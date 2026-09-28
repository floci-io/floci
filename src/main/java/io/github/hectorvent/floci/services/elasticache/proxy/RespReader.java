package io.github.hectorvent.floci.services.elasticache.proxy;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

/**
 * Minimal Redis RESP protocol parser for reading authentication-handshake commands from a
 * socket InputStream. After authentication, the connection is relayed verbatim.
 */
public class RespReader {

    private final InputStream in;

    public RespReader(InputStream in) {
        this.in = in;
    }

    /**
     * Reads a single RESP array command and returns its arguments as String[].
     * Expects the format: *N\r\n  $L\r\n  <bytes>\r\n  ...
     */
    public String[] readCommand() throws IOException {
        String firstLine = readLine();
        if (firstLine == null || firstLine.isEmpty() || firstLine.charAt(0) != '*') {
            throw new IOException("Expected RESP array, got: " + firstLine);
        }
        int argCount = Integer.parseInt(firstLine.substring(1));
        String[] args = new String[argCount];
        for (int i = 0; i < argCount; i++) {
            String bulkHeader = readLine();
            if (bulkHeader == null || bulkHeader.isEmpty() || bulkHeader.charAt(0) != '$') {
                throw new IOException("Expected RESP bulk string header, got: " + bulkHeader);
            }
            int length = Integer.parseInt(bulkHeader.substring(1));
            byte[] data = readExactly(length);
            readExactly(2); // consume \r\n
            args[i] = new String(data, StandardCharsets.UTF_8);
        }
        return args;
    }

    private String readLine() throws IOException {
        StringBuilder sb = new StringBuilder();
        int c;
        while ((c = in.read()) != -1) {
            if (c == '\r') {
                int next = in.read();
                if (next != '\n') {
                    throw new IOException("Expected \\n after \\r in RESP");
                }
                break;
            }
            sb.append((char) c);
        }
        return sb.toString();
    }

    private byte[] readExactly(int n) throws IOException {
        byte[] buf = new byte[n];
        int read = 0;
        while (read < n) {
            int r = in.read(buf, read, n - read);
            if (r == -1) {
                throw new IOException("Unexpected end of stream reading " + n + " bytes");
            }
            read += r;
        }
        return buf;
    }
}
