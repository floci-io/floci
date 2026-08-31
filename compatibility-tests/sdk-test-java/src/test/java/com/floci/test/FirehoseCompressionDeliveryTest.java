package com.floci.test;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.xerial.snappy.Snappy;
import org.xerial.snappy.SnappyInputStream;
import software.amazon.awssdk.core.ResponseInputStream;
import software.amazon.awssdk.services.firehose.FirehoseClient;
import software.amazon.awssdk.services.firehose.model.BufferingHints;
import software.amazon.awssdk.services.firehose.model.CompressionFormat;
import software.amazon.awssdk.services.firehose.model.CreateDeliveryStreamRequest;
import software.amazon.awssdk.services.firehose.model.DeleteDeliveryStreamRequest;
import software.amazon.awssdk.services.firehose.model.DeliveryStreamType;
import software.amazon.awssdk.services.firehose.model.ExtendedS3DestinationConfiguration;
import software.amazon.awssdk.services.firehose.model.PutRecordBatchRequest;
import software.amazon.awssdk.services.firehose.model.Record;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.CreateBucketRequest;
import software.amazon.awssdk.services.s3.model.DeleteObjectRequest;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.GetObjectResponse;
import software.amazon.awssdk.services.s3.model.ListObjectsV2Request;
import software.amazon.awssdk.services.s3.model.S3Object;
import software.amazon.awssdk.core.SdkBytes;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.stream.Stream;
import java.util.zip.GZIPInputStream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Issue #2328 — the delivered S3 object must actually be compressed the way the
 * stream's CompressionFormat says, with the key extension and Content-Encoding
 * real AWS uses. This is the only Firehose coverage that reads the delivered
 * bytes back through an SDK client, and in CI it runs against the GraalVM native
 * image, where the Snappy formats depend on a bundled JNI library.
 */
@DisplayName("Firehose S3 delivery compression — issue #2328")
class FirehoseCompressionDeliveryTest {

    private static final String RECORD = "{\"seq\":1}\n";
    private static final int RECORD_COUNT = 5;
    private static final String EXPECTED_PAYLOAD = RECORD.repeat(RECORD_COUNT);
    /**
     * The buffering interval has to elapse for the delivery to happen, so it is
     * kept short. AWS accepts anything in 0..900 (verified against the service, not
     * just its model), so this is still a contract-faithful stream; against Floci
     * the flush then lands on the next tick of its background flusher.
     */
    private static final int BUFFERING_INTERVAL_SECONDS = 5;
    private static final long POLL_TIMEOUT_MILLIS = 240_000L;

    private static FirehoseClient firehose;
    private static S3Client s3;
    private static String bucket;
    private static String streamPrefix;

    /**
     * HADOOP_SNAPPY shares the .snappy extension with Snappy and differs only in
     * framing and Content-Encoding: the S3 object name documentation tables
     * .hsnappy, but the service delivers .snappy (verified against real AWS).
     */
    private record Format(CompressionFormat compressionFormat, String extension, String contentEncoding) {
        String slug() {
            return compressionFormat.toString().toLowerCase(Locale.ROOT).replace('_', '-');
        }

        @Override
        public String toString() {
            return compressionFormat.toString();
        }
    }

    private static Stream<Format> formats() {
        return Stream.of(
                new Format(CompressionFormat.UNCOMPRESSED, "", null),
                new Format(CompressionFormat.GZIP, ".gz", "gzip"),
                new Format(CompressionFormat.ZIP, ".zip", "zip"),
                new Format(CompressionFormat.SNAPPY, ".snappy", "snappy-java"),
                new Format(CompressionFormat.HADOOP_SNAPPY, ".snappy", "hadoop-snappy"));
    }

    /**
     * Every stream is created and filled up front so the single buffering interval
     * is shared by all of them instead of being waited out once per format.
     */
    @BeforeAll
    static void setup() {
        String roleArn = TestFixtures.isRealAws()
                ? System.getenv("FIREHOSE_ROLE_ARN")
                : "arn:aws:iam::000000000000:role/firehose-role";
        if (TestFixtures.isRealAws() && roleArn == null) {
            Assumptions.abort("FIREHOSE_ROLE_ARN not set");
        }

        firehose = TestFixtures.firehoseClient();
        s3 = TestFixtures.s3Client();
        bucket = TestFixtures.uniqueName("firehose-compression");
        streamPrefix = TestFixtures.uniqueName("sdk-compression");

        s3.createBucket(CreateBucketRequest.builder().bucket(bucket).build());

        List<Format> formats = formats().toList();
        for (Format format : formats) {
            firehose.createDeliveryStream(CreateDeliveryStreamRequest.builder()
                    .deliveryStreamName(streamName(format))
                    .deliveryStreamType(DeliveryStreamType.DIRECT_PUT)
                    .extendedS3DestinationConfiguration(ExtendedS3DestinationConfiguration.builder()
                            .roleARN(roleArn)
                            .bucketARN("arn:aws:s3:::" + bucket)
                            .prefix(format.slug() + "/")
                            .compressionFormat(format.compressionFormat())
                            .bufferingHints(BufferingHints.builder()
                                    .intervalInSeconds(BUFFERING_INTERVAL_SECONDS)
                                    .sizeInMBs(1)
                                    .build())
                            .build())
                    .build());
        }
        for (Format format : formats) {
            Record entry = Record.builder()
                    .data(SdkBytes.fromString(RECORD, StandardCharsets.UTF_8))
                    .build();
            assertThat(firehose.putRecordBatch(PutRecordBatchRequest.builder()
                    .deliveryStreamName(streamName(format))
                    .records(Collections.nCopies(RECORD_COUNT, entry))
                    .build()).failedPutCount()).isZero();
        }
    }

    @AfterAll
    static void cleanup() {
        if (firehose != null) {
            formats().forEach(format -> {
                try {
                    firehose.deleteDeliveryStream(DeleteDeliveryStreamRequest.builder()
                            .deliveryStreamName(streamName(format)).build());
                } catch (Exception ignored) {
                    // Best effort: a stream that never got created must not fail the run.
                }
            });
            firehose.close();
        }
        if (s3 != null) {
            try {
                s3.listObjectsV2(ListObjectsV2Request.builder().bucket(bucket).build()).contents()
                        .forEach(object -> s3.deleteObject(DeleteObjectRequest.builder()
                                .bucket(bucket).key(object.key()).build()));
                s3.deleteBucket(builder -> builder.bucket(bucket));
            } catch (Exception ignored) {
                // Best effort cleanup of the probe bucket.
            }
            s3.close();
        }
    }

    private static String streamName(Format format) {
        return streamPrefix + "-" + format.slug();
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("formats")
    @DisplayName("Delivered object carries the format's framing, extension and Content-Encoding")
    void deliveredObjectMatchesTheCompressionFormat(Format format) throws Exception {
        String key = waitForDeliveredKey(format.slug() + "/");

        assertThat(key).endsWith(format.extension());
        if (format.extension().isEmpty()) {
            assertThat(key).doesNotEndWith(".gz").doesNotEndWith(".zip").doesNotEndWith(".snappy");
        }

        try (ResponseInputStream<GetObjectResponse> object = s3.getObject(
                GetObjectRequest.builder().bucket(bucket).key(key).build())) {
            GetObjectResponse response = object.response();
            assertThat(response.contentType()).isEqualTo("application/octet-stream");
            assertThat(response.contentEncoding()).isEqualTo(format.contentEncoding());
            assertThat(decompress(format, object.readAllBytes()))
                    .as("delivered body must decode to the records that were put")
                    .isEqualTo(EXPECTED_PAYLOAD);
        }
    }

    private String waitForDeliveredKey(String prefix) throws InterruptedException {
        long deadline = System.currentTimeMillis() + POLL_TIMEOUT_MILLIS;
        while (System.currentTimeMillis() < deadline) {
            List<S3Object> contents = s3.listObjectsV2(ListObjectsV2Request.builder()
                    .bucket(bucket).prefix(prefix).build()).contents();
            if (!contents.isEmpty()) {
                return contents.get(0).key();
            }
            Thread.sleep(5_000L);
        }
        throw new AssertionError("no object delivered under " + prefix + " within "
                + POLL_TIMEOUT_MILLIS / 1000 + "s");
    }

    private static String decompress(Format format, byte[] body) throws IOException {
        byte[] plain = switch (format.compressionFormat()) {
            case UNCOMPRESSED -> body;
            case GZIP -> new GZIPInputStream(new ByteArrayInputStream(body)).readAllBytes();
            case ZIP -> singleZipEntry(body);
            case SNAPPY -> new SnappyInputStream(new ByteArrayInputStream(body)).readAllBytes();
            case HADOOP_SNAPPY -> hadoopSnappy(body);
            default -> throw new IllegalArgumentException("unhandled format " + format);
        };
        return new String(plain, StandardCharsets.UTF_8);
    }

    /** AWS writes exactly one entry, named with a UUID unrelated to the object key. */
    private static byte[] singleZipEntry(byte[] body) throws IOException {
        try (ZipInputStream zip = new ZipInputStream(new ByteArrayInputStream(body))) {
            ZipEntry entry = zip.getNextEntry();
            assertThat(entry).as("zip must hold an entry").isNotNull();
            byte[] content = zip.readAllBytes();
            assertThat(zip.getNextEntry()).as("zip must hold exactly one entry").isNull();
            return content;
        }
    }

    /**
     * Hadoop's block framing, which snappy-java writes but cannot read back: per
     * block, a big-endian uncompressed block length followed by one or more chunks
     * of a big-endian compressed length and a raw Snappy block. The leading length
     * belongs to the first block, not to the whole payload, so anything above the
     * 32 KiB block size carries several blocks and must be looped over.
     */
    private static byte[] hadoopSnappy(byte[] body) throws IOException {
        ByteBuffer in = ByteBuffer.wrap(body);
        ByteArrayOutputStream out = new ByteArrayOutputStream(body.length);
        while (in.remaining() > 0) {
            int blockLength = in.getInt();
            int produced = 0;
            while (produced < blockLength) {
                byte[] chunk = new byte[in.getInt()];
                in.get(chunk);
                byte[] plain = Snappy.uncompress(chunk);
                out.write(plain);
                produced += plain.length;
            }
            assertThat(produced).isEqualTo(blockLength);
        }
        return out.toByteArray();
    }
}
