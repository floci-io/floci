package io.floci.aws.core.common.dns;

import io.netty.util.NetUtil;

import java.io.ByteArrayOutputStream;
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

    record Ns(String target) implements DnsRecord {
        @Override
        public int type() {
            return 2;
        }

        @Override
        public byte[] data() {
            return encodeName(target);
        }
    }

    /** Route 53 TXT values use DNS presentation syntax, including multiple quoted strings. */
    record Txt(String value) implements DnsRecord {
        @Override
        public int type() {
            return 16;
        }

        @Override
        public byte[] data() {
            ByteArrayOutputStream result = new ByteArrayOutputStream();
            int index = 0;
            while (index < value.length()) {
                if (Character.isWhitespace(value.charAt(index))) {
                    index++;
                    continue;
                }
                if (value.charAt(index++) != '"') {
                    throw new IllegalArgumentException("TXT strings must be quoted");
                }
                ByteArrayOutputStream text = new ByteArrayOutputStream();
                boolean closed = false;
                while (index < value.length()) {
                    char character = value.charAt(index++);
                    if (character == '"') {
                        closed = true;
                        break;
                    }
                    if (character == '\\') {
                        if (index == value.length()) {
                            throw new IllegalArgumentException("Incomplete TXT escape");
                        }
                        character = value.charAt(index++);
                        if (character >= '0' && character <= '9') {
                            if (character > '7' || index + 1 >= value.length()
                                    || value.charAt(index) < '0' || value.charAt(index) > '7'
                                    || value.charAt(index + 1) < '0' || value.charAt(index + 1) > '7') {
                                throw new IllegalArgumentException("TXT octal escapes require three octal digits");
                            }
                            int octet = (character - '0') * 64 + (value.charAt(index) - '0') * 8
                                    + value.charAt(index + 1) - '0';
                            if (octet > 255) {
                                throw new IllegalArgumentException("TXT escape exceeds one byte");
                            }
                            text.write(octet);
                            index += 2;
                            continue;
                        }
                    }
                    int codePoint = character;
                    if (Character.isHighSurrogate(character) && index < value.length()
                            && Character.isLowSurrogate(value.charAt(index))) {
                        codePoint = Character.toCodePoint(character, value.charAt(index++));
                    }
                    text.writeBytes(new String(Character.toChars(codePoint)).getBytes(StandardCharsets.UTF_8));
                }
                if (!closed || text.size() > 255) {
                    throw new IllegalArgumentException("TXT character strings must close and contain at most 255 bytes");
                }
                result.write(text.size());
                result.writeBytes(text.toByteArray());
            }
            if (result.size() == 0) {
                throw new IllegalArgumentException("TXT requires at least one character string");
            }
            return result.toByteArray();
        }
    }

    record Soa(String value) implements DnsRecord {
        @Override
        public int type() {
            return 6;
        }

        private String[] fields() {
            String[] fields = value.trim().split("\\s+");
            if (fields.length != 7) {
                throw new IllegalArgumentException("SOA requires two names and five unsigned integers");
            }
            return fields;
        }

        public long minimum() {
            return unsignedInteger(fields()[6]);
        }

        @Override
        public byte[] data() {
            String[] fields = fields();
            byte[] primary = encodeName(fields[0]);
            byte[] mailbox = encodeName(fields[1]);
            ByteBuffer result = ByteBuffer.allocate(primary.length + mailbox.length + 20);
            result.put(primary).put(mailbox);
            for (int i = 2; i < fields.length; i++) {
                result.putInt((int) unsignedInteger(fields[i]));
            }
            return result.array();
        }

        private static long unsignedInteger(String value) {
            long number = Long.parseLong(value);
            if (number < 0 || number > 0xFFFFFFFFL) {
                throw new IllegalArgumentException("SOA integer must fit an unsigned 32-bit field");
            }
            return number;
        }
    }

    record Srv(int priority, int weight, int port, String target) implements DnsRecord {
        public Srv(int port, String target) {
            this(1, 1, port, target);
        }

        public Srv {
            if (priority < 0 || priority > 65535 || weight < 0 || weight > 65535) {
                throw new IllegalArgumentException("SRV priority and weight must fit unsigned 16-bit fields");
            }
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
            byte[] name = ".".equals(target) ? new byte[]{0} : encodeName(target);
            return ByteBuffer.allocate(6 + name.length).putShort((short) priority).putShort((short) weight)
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
