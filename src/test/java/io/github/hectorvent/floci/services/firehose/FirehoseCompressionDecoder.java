package io.github.hectorvent.floci.services.firehose;

import org.xerial.snappy.Snappy;
import org.xerial.snappy.SnappyInputStream;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.util.List;
import java.util.zip.GZIPInputStream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * Reads back what Firehose delivered, so tests assert on the decoded payload
 * rather than on opaque compressed bytes. Each format is decoded by the reader a
 * real consumer would use, which is what makes the assertions meaningful:
 * {@code Snappy} only decodes with snappy-java's own stream reader, and
 * {@code HADOOP_SNAPPY} has no reader at all in the library, so its block
 * framing is unpacked here the way Hadoop's {@code SnappyCodec} does.
 */
final class FirehoseCompressionDecoder {

    private FirehoseCompressionDecoder() {}

    static byte[] decompress(FirehoseCompression format, byte[] body) throws IOException {
        return switch (format) {
            case UNCOMPRESSED -> body;
            case GZIP -> readAll(new GZIPInputStream(new ByteArrayInputStream(body)));
            case ZIP -> singleZipEntry(body).content();
            case SNAPPY -> readAll(new SnappyInputStream(new ByteArrayInputStream(body)));
            case HADOOP_SNAPPY -> hadoopSnappy(body);
        };
    }

    record ZipEntryContent(String name, byte[] content) {}

    /** Fails when the archive does not hold exactly one entry, which is what AWS writes. */
    static ZipEntryContent singleZipEntry(byte[] body) throws IOException {
        List<ZipEntryContent> entries = new java.util.ArrayList<>();
        try (ZipInputStream zip = new ZipInputStream(new ByteArrayInputStream(body))) {
            ZipEntry entry;
            while ((entry = zip.getNextEntry()) != null) {
                entries.add(new ZipEntryContent(entry.getName(), zip.readAllBytes()));
            }
        }
        if (entries.size() != 1) {
            throw new IOException("expected exactly one zip entry, found " + entries.size());
        }
        return entries.get(0);
    }

    /**
     * Hadoop's block framing, which snappy-java writes but cannot read back: per
     * block, a big-endian uncompressed block length followed by one or more chunks
     * of a big-endian compressed length and a raw Snappy block. The leading length
     * belongs to the first block, not to the whole payload, so anything above the
     * 32 KiB block size carries several blocks and must be looped over.
     */
    static byte[] hadoopSnappy(byte[] body) throws IOException {
        ByteBuffer in = ByteBuffer.wrap(body);
        ByteArrayOutputStream out = new ByteArrayOutputStream(body.length);
        while (in.remaining() > 0) {
            int blockLength = in.getInt();
            int produced = 0;
            while (produced < blockLength) {
                byte[] chunk = new byte[in.getInt()];
                in.get(chunk);
                byte[] plain = Snappy.uncompress(chunk);
                out.writeBytes(plain);
                produced += plain.length;
            }
            if (produced != blockLength) {
                throw new IOException("block declared " + blockLength + " bytes, decoded " + produced);
            }
        }
        return out.toByteArray();
    }

    private static byte[] readAll(InputStream in) throws IOException {
        try (in) {
            return in.readAllBytes();
        }
    }
}
