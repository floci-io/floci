package io.github.hectorvent.floci.services.firehose;

import org.jboss.logging.Logger;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.util.Optional;
import java.util.UUID;
import java.util.zip.GZIPOutputStream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import org.xerial.snappy.SnappyHadoopCompatibleOutputStream;
import org.xerial.snappy.SnappyOutputStream;

/**
 * The {@code CompressionFormat} values Firehose applies when it delivers to S3,
 * each paired with the object-key extension and {@code Content-Encoding} AWS
 * uses for it (verified against objects delivered by real AWS, see
 * https://github.com/floci-io/floci/issues/2328). The two Snappy variants come
 * out byte-identical to AWS's; gzip and zip match in container format but not
 * byte for byte, since deflate output and the zip entry name are not reproducible.
 *
 * Both Snappy variants are container formats around the same Snappy algorithm:
 * {@code Snappy} is the xerial snappy-java stream format (magic
 * {@code 0x82 "SNAPPY" 0x00}), <em>not</em> the official framing format, and
 * {@code HADOOP_SNAPPY} is Hadoop's block framing. snappy-java is the only Java
 * library that writes the former, which is why it is a direct dependency.
 *
 * {@code HADOOP_SNAPPY} deliberately carries {@code .snappy} even though the S3
 * object name documentation tables it as {@code .hsnappy}: the service delivers
 * {@code .snappy}, reproduced on two separate dates, and only the
 * {@code Content-Encoding} tells the two variants apart. Follow the service.
 */
enum FirehoseCompression {

    UNCOMPRESSED("UNCOMPRESSED", "", null),
    GZIP("GZIP", ".gz", "gzip"),
    ZIP("ZIP", ".zip", "zip"),
    SNAPPY("Snappy", ".snappy", "snappy-java"),
    HADOOP_SNAPPY("HADOOP_SNAPPY", ".snappy", "hadoop-snappy");

    private static final Logger LOG = Logger.getLogger(FirehoseCompression.class);

    private final String wireValue;
    private final String extension;
    private final String contentEncoding;

    FirehoseCompression(String wireValue, String extension, String contentEncoding) {
        this.wireValue = wireValue;
        this.extension = extension;
        this.contentEncoding = contentEncoding;
    }

    String wireValue() {
        return wireValue;
    }

    /** Key suffix AWS appends for this format, empty for {@code UNCOMPRESSED}. */
    String extension() {
        return extension;
    }

    /** {@code Content-Encoding} AWS sets on the delivered object, null for {@code UNCOMPRESSED}. */
    String contentEncoding() {
        return contentEncoding;
    }

    /** Exact-case lookup, the way AWS matches the enum ({@code SNAPPY} is not {@code Snappy}). */
    static Optional<FirehoseCompression> fromWireValue(String value) {
        for (FirehoseCompression format : values()) {
            if (format.wireValue.equals(value)) {
                return Optional.of(format);
            }
        }
        return Optional.empty();
    }

    /**
     * Resolves the format for a delivery, never failing: a stream persisted
     * before this validation existed can still hold a value create-time
     * validation would reject today, and dropping records over it would be
     * worse than delivering them uncompressed.
     */
    static FirehoseCompression forDelivery(String value) {
        if (value == null) {
            return UNCOMPRESSED;
        }
        return fromWireValue(value).orElseGet(() -> {
            LOG.warnv("Unsupported Firehose CompressionFormat {0}, delivering uncompressed", value);
            return UNCOMPRESSED;
        });
    }

    byte[] compress(byte[] payload) throws IOException {
        return switch (this) {
            case UNCOMPRESSED -> payload;
            case GZIP -> encode(payload, GZIPOutputStream::new);
            case ZIP -> zip(payload);
            case SNAPPY -> encode(payload, SnappyOutputStream::new);
            case HADOOP_SNAPPY -> encode(payload, SnappyHadoopCompatibleOutputStream::new);
        };
    }

    private static byte[] encode(byte[] payload, StreamFactory factory) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream(estimatedSize(payload));
        try (OutputStream compressor = factory.wrap(out)) {
            compressor.write(payload);
        }
        return out.toByteArray();
    }

    /** AWS delivers a single deflated entry whose name is a UUID unrelated to the object key. */
    private static byte[] zip(byte[] payload) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream(estimatedSize(payload));
        try (ZipOutputStream zip = new ZipOutputStream(out)) {
            zip.putNextEntry(new ZipEntry(UUID.randomUUID().toString()));
            zip.write(payload);
            zip.closeEntry();
        }
        return out.toByteArray();
    }

    private static int estimatedSize(byte[] payload) {
        return Math.max(64, payload.length / 4);
    }

    @FunctionalInterface
    private interface StreamFactory {
        OutputStream wrap(OutputStream out) throws IOException;
    }
}
