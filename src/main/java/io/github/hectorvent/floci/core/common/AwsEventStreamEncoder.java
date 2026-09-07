package io.github.hectorvent.floci.core.common;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.zip.CRC32;

public class AwsEventStreamEncoder {

    public static byte[] encodeMessage(LinkedHashMap<String, String> headers, byte[] payload) throws IOException {
        byte[] headersBytes = encodeHeaders(headers);
        int totalLen = 4 + 4 + 4 + headersBytes.length + payload.length + 4;

        ByteArrayOutputStream buf = new ByteArrayOutputStream(totalLen);
        DataOutputStream dos = new DataOutputStream(buf);

        dos.writeInt(totalLen);
        dos.writeInt(headersBytes.length);

        CRC32 preludeCrc = new CRC32();
        preludeCrc.update(buf.toByteArray());
        dos.writeInt((int) preludeCrc.getValue());

        dos.write(headersBytes);
        dos.write(payload);
        dos.flush();

        CRC32 msgCrc = new CRC32();
        msgCrc.update(buf.toByteArray());
        dos.writeInt((int) msgCrc.getValue());
        dos.flush();

        return buf.toByteArray();
    }

    private static byte[] encodeHeaders(LinkedHashMap<String, String> headers) throws IOException {
        ByteArrayOutputStream h = new ByteArrayOutputStream();
        for (Map.Entry<String, String> e : headers.entrySet()) {
            byte[] name = e.getKey().getBytes(StandardCharsets.UTF_8);
            byte[] value = e.getValue().getBytes(StandardCharsets.UTF_8);
            h.write(name.length & 0xFF);
            h.write(name);
            h.write(7);
            h.write((value.length >> 8) & 0xFF);
            h.write(value.length & 0xFF);
            h.write(value);
        }
        return h.toByteArray();
    }
}