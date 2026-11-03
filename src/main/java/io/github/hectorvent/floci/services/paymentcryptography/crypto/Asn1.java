package io.github.hectorvent.floci.services.paymentcryptography.crypto;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.security.cert.X509Certificate;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/**
 * A minimal DER parser and encoder.
 *
 * <p>Uses the same (tag, constructed, payload) node shape as the AWS Payment
 * Cryptography TR-34 reference: a primitive node carries raw content bytes, a
 * constructed node carries child nodes. That symmetry is the point - the
 * emulator parses a TR-34 payload and a test builds one with an identical
 * structure, so a round-trip proves both directions.
 */
public final class Asn1 {

    private Asn1() {
    }

    /** One DER element. */
    public static final class Node {
        public final int tag;
        public final boolean constructed;
        /** Raw content bytes when primitive, child nodes when constructed. */
        public final Object payload;

        public Node(int tag, boolean constructed, Object payload) {
            this.tag = tag;
            this.constructed = constructed;
            this.payload = payload;
        }

        public static Node primitive(int tag, byte[] content) {
            return new Node(tag, false, content);
        }

        @SuppressWarnings("unchecked")
        public List<Node> children() {
            if (!constructed) {
                throw new IllegalStateException(
                        "tag 0x" + Integer.toHexString(tag) + " is primitive and has no children");
            }
            return (List<Node>) payload;
        }

        public byte[] content() {
            if (constructed) {
                throw new IllegalStateException(
                        "tag 0x" + Integer.toHexString(tag) + " is constructed; encode its children");
            }
            return (byte[]) payload;
        }

        @Override
        public boolean equals(Object o) {
            if (!(o instanceof Node n)) {
                return false;
            }
            return tag == n.tag && constructed == n.constructed && payloadEquals(payload, n.payload);
        }

        private static boolean payloadEquals(Object a, Object b) {
            if (a instanceof byte[] first && b instanceof byte[] second) {
                return Arrays.equals(first, second);
            }
            return a == null ? b == null : a.equals(b);
        }

        @Override
        public int hashCode() {
            int h = tag * 31 + (constructed ? 1 : 0);
            if (payload instanceof byte[] bytes) {
                h = h * 31 + Arrays.hashCode(bytes);
            } else if (payload != null) {
                h = h * 31 + payload.hashCode();
            }
            return h;
        }
    }

    // ---- parsing -------------------------------------------------------------

    /** Parse DER bytes into a list of nodes. */
    public static List<Node> parse(byte[] data) {
        return parse(data, 0, data.length);
    }

    /** Parse DER bytes from {@code offset} to {@code limit}. */
    public static List<Node> parse(byte[] data, int offset, int limit) {
        List<Node> items = new ArrayList<Node>();
        int cur = offset;
        while (cur < limit) {
            Header h = parseHeader(data, cur);
            if (!h.constructed) {
                items.add(Node.primitive(h.tag, slice(data, h.payloadOffset, h.payloadOffset + h.length)));
            } else {
                items.add(new Node(h.tag, true,
                        parse(data, h.payloadOffset, h.payloadOffset + h.length)));
            }
            cur += h.total;
        }
        return items;
    }

    private static final class Header {
        final int tag;
        final boolean constructed;
        final int payloadOffset;
        final int length;
        final int total;

        Header(int tag, boolean constructed, int payloadOffset, int length, int total) {
            this.tag = tag;
            this.constructed = constructed;
            this.payloadOffset = payloadOffset;
            this.length = length;
            this.total = total;
        }
    }

    private static Header parseHeader(byte[] data, int offset) {
        if (data.length < offset + 2) {
            throw new IllegalArgumentException("ASN.1 header too short");
        }
        int tag = data[offset] & 0xff;
        boolean constructed = (tag & 0x20) != 0;
        int length = data[offset + 1] & 0xff;
        int lenOfLen = 0;
        if ((length & 0x80) != 0) {
            lenOfLen = length & 0x7f;
            if (data.length < offset + 2 + lenOfLen) {
                throw new IllegalArgumentException("ASN.1 length truncated");
            }
            length = 0;
            for (int i = 0; i < lenOfLen; i++) {
                length = (length << 8) | (data[offset + 2 + i] & 0xff);
            }
        }
        int payloadOffset = offset + 2 + lenOfLen;
        if (data.length < payloadOffset + length) {
            throw new IllegalArgumentException("ASN.1 payload truncated");
        }
        return new Header(tag, constructed, payloadOffset, length, 2 + lenOfLen + length);
    }

    // ---- encoding ------------------------------------------------------------

    /** Encode a list of nodes to DER bytes. */
    public static byte[] encode(List<Node> nodes) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        for (Node n : nodes) {
            byte[] content = n.constructed ? encode(n.children()) : n.content();
            out.write(n.tag);
            writeLength(out, content.length);
            out.write(content, 0, content.length);
        }
        return out.toByteArray();
    }

    /** Encode a single node to DER bytes. */
    public static byte[] encodeOne(Node node) {
        return encode(Collections.singletonList(node));
    }

    /**
     * Wrap a complete, already-encoded DER element (tag, length, content) back
     * into a node, so a pre-encoded structure can be embedded in a larger one.
     *
     * <p>The result is always a primitive node: re-encoding reproduces the
     * original bytes exactly, which is what embedding a SubjectPublicKeyInfo or
     * a pre-built TBS certificate requires. The length may be in long form.
     */
    public static Node fromTlv(byte[] tlv) {
        if (tlv == null || tlv.length < 2) {
            throw new IllegalArgumentException("a DER element needs at least a tag and a length");
        }
        Header h = parseHeader(tlv, 0);
        if (h.total != tlv.length) {
            throw new IllegalArgumentException(
                    "DER element is " + tlv.length + " bytes but its header describes " + h.total);
        }
        return Node.primitive(h.tag, slice(tlv, h.payloadOffset, h.payloadOffset + h.length));
    }

    private static void writeLength(ByteArrayOutputStream out, int n) {
        if (n <= 0x7f) {
            out.write(n);
            return;
        }
        List<Integer> bytes = new ArrayList<Integer>();
        int v = n;
        while (v > 0) {
            bytes.add(0, v & 0xff);
            v >>>= 8;
        }
        out.write(0x80 | bytes.size());
        for (int b : bytes) {
            out.write(b);
        }
    }

    // ---- convenience constructors -------------------------------------------

    public static Node seq(Node... children) {
        return new Node(0x30, true, new ArrayList<Node>(Arrays.asList(children)));
    }

    public static Node set(Node... children) {
        return new Node(0x31, true, new ArrayList<Node>(Arrays.asList(children)));
    }

    /** A context-specific constructed node, {@code [n] EXPLICIT}. */
    public static Node context(int n, Node... children) {
        return new Node(0xa0 | n, true, new ArrayList<Node>(Arrays.asList(children)));
    }

    public static Node octetString(byte[] content) {
        return Node.primitive(0x04, content);
    }

    public static Node bitString(byte[] content) {
        byte[] withUnused = new byte[content.length + 1];
        withUnused[0] = 0x00;
        System.arraycopy(content, 0, withUnused, 1, content.length);
        return Node.primitive(0x03, withUnused);
    }

    public static Node utf8String(String s) {
        return Node.primitive(0x0c, s.getBytes(StandardCharsets.UTF_8));
    }

    private static byte[] slice(byte[] b, int from, int to) {
        byte[] out = new byte[to - from];
        System.arraycopy(b, from, out, 0, to - from);
        return out;
    }
}
