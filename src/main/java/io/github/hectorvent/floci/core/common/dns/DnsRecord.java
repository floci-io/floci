package io.github.hectorvent.floci.core.common.dns;

import io.netty.util.NetUtil;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;

/** A DNS resource record's wire type and uncompressed rdata. */
public sealed interface DnsRecord {
    int type();
    byte[] data();

    record Address(int type, String value) implements DnsRecord {
        public Address {
            if (!(type == 1 && NetUtil.isValidIpV4Address(value))
                    && !(type == 28 && NetUtil.isValidIpV6Address(value))) {
                throw new IllegalArgumentException("Invalid DNS address: " + value);
            }
        }

        @Override
        public byte[] data() {
            return NetUtil.createByteArrayFromIpAddressString(value);
        }
    }

    record Cname(String target) implements DnsRecord {
        @Override
        public int type() {
            return 5;
        }

        @Override
        public byte[] data() {
            return encodeName(target);
        }
    }

    record Srv(int port, String target) implements DnsRecord {
        public Srv {
            if (port < 0 || port > 65535) {
                throw new IllegalArgumentException("Invalid SRV port: " + port);
            }
        }

        @Override
        public int type() {
            return 33;
        }

        @Override
        public byte[] data() {
            byte[] name = encodeName(target);
            return ByteBuffer.allocate(6 + name.length).putShort((short) 1).putShort((short) 1)
                    .putShort((short) port).put(name).array();
        }
    }

    static byte[] encodeName(String name) {
        String normalized = name.endsWith(".") ? name.substring(0, name.length() - 1) : name;
        if (normalized.length() > 253 || normalized.isEmpty()) {
            throw new IllegalArgumentException("Invalid DNS name: " + name);
        }
        ByteBuffer buffer = ByteBuffer.allocate(normalized.length() + 2);
        for (String label : normalized.split("\\.", -1)) {
            if (label.isEmpty() || label.length() > 63 || !StandardCharsets.US_ASCII.newEncoder().canEncode(label)) {
                throw new IllegalArgumentException("Invalid DNS label: " + label);
            }
            buffer.put((byte) label.length()).put(label.getBytes(StandardCharsets.US_ASCII));
        }
        return buffer.put((byte) 0).array();
    }
}
