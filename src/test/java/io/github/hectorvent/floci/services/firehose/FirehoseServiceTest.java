package io.github.hectorvent.floci.services.firehose;

import io.github.hectorvent.floci.config.EmulatorConfig;
import io.github.hectorvent.floci.core.common.RegionResolver;
import io.github.hectorvent.floci.core.storage.AccountAwareStorageBackend;
import io.github.hectorvent.floci.core.storage.StorageFactory;
import io.github.hectorvent.floci.services.firehose.model.DeliveryStreamDescription;
import io.github.hectorvent.floci.services.firehose.model.DeliveryStreamDescription.S3Destination;
import io.github.hectorvent.floci.services.firehose.model.Record;
import io.github.hectorvent.floci.services.s3.S3Service;
import io.github.hectorvent.floci.services.s3.model.PutObjectOptions;
import io.github.hectorvent.floci.testing.MutableClock;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class FirehoseServiceTest {

    private static final String UUID_REGEX = "[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}";
    /** AWS delivers every format as application/octet-stream, compressed or not. */
    private static final String OCTET_STREAM = "application/octet-stream";
    private static final String FIVE_RECORDS = "{\"n\":0}\n{\"n\":1}\n{\"n\":2}\n{\"n\":3}\n{\"n\":4}\n";

    private FirehoseService firehoseService;
    private StorageFactory storageFactory;
    private S3Service s3Service;
    private MutableClock clock;

    @BeforeEach
    void setUp() {
        storageFactory = Mockito.mock(StorageFactory.class);
        when(storageFactory.create(anyString(), anyString(), any()))
                .thenReturn(AccountAwareStorageBackend.inMemory("000000000000"));
        s3Service = Mockito.mock(S3Service.class);
        clock = new MutableClock();
        firehoseService = newService(0);
    }

    private FirehoseService newService(int flushRecordCount) {
        EmulatorConfig.FirehoseServiceConfig firehoseCfg = mock(EmulatorConfig.FirehoseServiceConfig.class);
        when(firehoseCfg.enabled()).thenReturn(true);
        when(firehoseCfg.tickIntervalSeconds()).thenReturn(10L);
        when(firehoseCfg.flushRecordCount()).thenReturn(flushRecordCount);
        EmulatorConfig.ServicesConfig servicesCfg = mock(EmulatorConfig.ServicesConfig.class);
        when(servicesCfg.firehose()).thenReturn(firehoseCfg);
        EmulatorConfig config = mock(EmulatorConfig.class);
        when(config.services()).thenReturn(servicesCfg);

        return new FirehoseService(storageFactory, s3Service,
                new RegionResolver("us-east-1", "000000000000"), clock, config);
    }

    private static S3Destination destination(String bucketArn, String compressionFormat) {
        S3Destination s3 = new S3Destination();
        s3.setBucketArn(bucketArn);
        s3.setCompressionFormat(compressionFormat);
        return s3;
    }

    private void putRecords(String streamName, int count) {
        for (int i = 0; i < count; i++) {
            firehoseService.putRecord(streamName, new Record(("{\"n\":" + i + "}").getBytes(StandardCharsets.UTF_8)));
        }
    }

    /** Puts a few small records and forces delivery, the way the interval trigger eventually would. */
    private void putRecordsAndFlush(String streamName) {
        putRecords(streamName, 5);
        firehoseService.flush(streamName);
    }

    private record Delivered(String key, byte[] body, String contentType, String contentEncoding) {
        String text() {
            return new String(body, StandardCharsets.UTF_8);
        }

        String decoded(FirehoseCompression format) throws IOException {
            return new String(FirehoseCompressionDecoder.decompress(format, body), StandardCharsets.UTF_8);
        }
    }

    private Delivered delivered(String expectedBucket) {
        ArgumentCaptor<String> bucket = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<String> key = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<byte[]> body = ArgumentCaptor.forClass(byte[].class);
        ArgumentCaptor<String> contentType = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<PutObjectOptions> options = ArgumentCaptor.forClass(PutObjectOptions.class);
        verify(s3Service).putObject(bucket.capture(), key.capture(), body.capture(), contentType.capture(),
                anyMap(), options.capture());
        assertEquals(expectedBucket, bucket.getValue());
        return new Delivered(key.getValue(), body.getValue(), contentType.getValue(),
                options.getValue().getContentEncoding());
    }

    private String deliveredKey(String expectedBucket) {
        return delivered(expectedBucket).key();
    }

    private void verifyNothingDelivered() {
        verify(s3Service, never()).putObject(anyString(), anyString(), any(byte[].class), anyString(),
                anyMap(), any(PutObjectOptions.class));
    }

    @Test
    void deliversToDefaultBucketWithAwsShapedKey() {
        firehoseService.createDeliveryStream("my-stream", null);
        putRecordsAndFlush("my-stream");

        String key = deliveredKey("floci-firehose-results");
        assertTrue(key.matches("2026/01/01/00/my-stream-1-2026-01-01-00-00-00-" + UUID_REGEX), key);
    }

    @Test
    void staticPrefixGetsDefaultTimePrefixAppended() {
        S3Destination s3 = new S3Destination();
        s3.setBucketArn("arn:aws:s3:::custom-bucket");
        s3.setPrefix("events/data/");
        firehoseService.createDeliveryStream("my-stream", s3);
        putRecordsAndFlush("my-stream");

        String key = deliveredKey("custom-bucket");
        assertTrue(key.matches("events/data/2026/01/01/00/my-stream-1-2026-01-01-00-00-00-" + UUID_REGEX), key);
    }

    @Test
    void customTimeZoneShiftsPrefixAndSuffix() {
        S3Destination s3 = new S3Destination();
        s3.setBucketArn("arn:aws:s3:::custom-bucket");
        s3.setCustomTimeZone("Europe/Madrid");
        firehoseService.createDeliveryStream("my-stream", s3);
        putRecordsAndFlush("my-stream");

        String key = deliveredKey("custom-bucket");
        assertTrue(key.matches("2026/01/01/01/my-stream-1-2026-01-01-01-00-00-" + UUID_REGEX), key);
    }

    @Test
    void updateDestinationMergesCustomTimeZoneAndBumpsKeyVersion() {
        S3Destination s3 = new S3Destination();
        s3.setBucketArn("arn:aws:s3:::custom-bucket");
        s3.setCustomTimeZone("Europe/Madrid");
        firehoseService.createDeliveryStream("my-stream", s3);

        S3Destination prefixOnly = new S3Destination();
        prefixOnly.setPrefix("events/");
        firehoseService.updateDestination("my-stream", "1", "destinationId-000000000001", prefixOnly);

        DeliveryStreamDescription described = firehoseService.describeDeliveryStream("my-stream");
        assertEquals("Europe/Madrid", described.s3Destination().getCustomTimeZone());
        assertEquals("events/", described.s3Destination().getPrefix());

        S3Destination timeZoneOnly = new S3Destination();
        timeZoneOnly.setCustomTimeZone("Asia/Tokyo");
        firehoseService.updateDestination("my-stream", "2", "destinationId-000000000001", timeZoneOnly);
        assertEquals("Asia/Tokyo",
                firehoseService.describeDeliveryStream("my-stream").s3Destination().getCustomTimeZone());

        putRecordsAndFlush("my-stream");
        String key = deliveredKey("custom-bucket");
        assertTrue(key.matches("events/2026/01/01/09/my-stream-3-2026-01-01-09-00-00-" + UUID_REGEX), key);
    }

    @Test
    void timeBasedFlushKeepsBufferWhileDefaultIntervalHasNotElapsed() {
        firehoseService.createDeliveryStream("idle-stream", null);
        putRecords("idle-stream", 2);

        firehoseService.flushDueBuffers(clock.instant().plusSeconds(299));

        verifyNothingDelivered();
    }

    @Test
    void timeBasedFlushDeliversBufferedRecordsAfterDefaultInterval() {
        firehoseService.createDeliveryStream("idle-stream", null);
        putRecords("idle-stream", 2);

        firehoseService.flushDueBuffers(clock.instant().plusSeconds(300));

        assertEquals("{\"n\":0}\n{\"n\":1}\n", delivered("floci-firehose-results").text());
    }

    @Test
    void timeBasedFlushHonorsStreamBufferingIntervalHint() {
        S3Destination s3 = new S3Destination();
        s3.setBucketArn("arn:aws:s3:::custom-bucket");
        DeliveryStreamDescription.BufferingHints hints = new DeliveryStreamDescription.BufferingHints();
        hints.setSizeInMBs(5);
        hints.setIntervalInSeconds(60);
        s3.setBufferingHints(hints);
        firehoseService.createDeliveryStream("hinted-stream", s3);
        putRecords("hinted-stream", 1);

        firehoseService.flushDueBuffers(clock.instant().plusSeconds(59));
        verifyNothingDelivered();

        firehoseService.flushDueBuffers(clock.instant().plusSeconds(60));
        assertEquals("{\"n\":0}\n", delivered("custom-bucket").text());
    }

    /** Matches real AWS: the volume trigger is bytes vs SizeInMBs, never a record count. */
    @Test
    void smallRecordsDoNotTriggerSizeBasedFlushRegardlessOfCount() {
        firehoseService.createDeliveryStream("trickle-stream", null);
        putRecords("trickle-stream", 20);

        verifyNothingDelivered();
    }

    @Test
    void sizeBasedFlushDeliversWhenBufferedBytesReachSizeHint() {
        S3Destination s3 = new S3Destination();
        s3.setBucketArn("arn:aws:s3:::custom-bucket");
        DeliveryStreamDescription.BufferingHints hints = new DeliveryStreamDescription.BufferingHints();
        hints.setSizeInMBs(1);
        hints.setIntervalInSeconds(300);
        s3.setBufferingHints(hints);
        firehoseService.createDeliveryStream("bulky-stream", s3);

        firehoseService.putRecord("bulky-stream", new Record(new byte[512 * 1024]));
        verifyNothingDelivered();

        firehoseService.putRecord("bulky-stream", new Record(new byte[512 * 1024]));
        // Both 512 KiB records, each followed by the newline the flush appends.
        assertEquals(2 * 512 * 1024 + 2, delivered("custom-bucket").body().length);
    }

    /** Emulator-only opt-in: flush-record-count=1 restores LocalStack-style record-at-a-time delivery. */
    @Test
    void flushRecordCountOfOneDeliversEachRecordImmediately() {
        firehoseService = newService(1);
        firehoseService.createDeliveryStream("eager-stream", null);
        firehoseService.putRecord("eager-stream", new Record("{\"n\":0}".getBytes(StandardCharsets.UTF_8)));

        assertEquals("{\"n\":0}\n", delivered("floci-firehose-results").text());
    }

    @Test
    void flushRecordCountThresholdDeliversTheWholeBuffer() {
        firehoseService = newService(3);
        firehoseService.createDeliveryStream("counted-stream", null);
        putRecords("counted-stream", 2);
        verifyNothingDelivered();

        firehoseService.putRecord("counted-stream", new Record("{\"n\":2}".getBytes(StandardCharsets.UTF_8)));

        assertEquals("{\"n\":0}\n{\"n\":1}\n{\"n\":2}\n", delivered("floci-firehose-results").text());
    }

    /** Matches real AWS: DeleteDeliveryStream discards undelivered records instead of flushing them. */
    @Test
    void deleteDeliveryStreamDiscardsBufferedRecords() {
        firehoseService.createDeliveryStream("doomed-stream", null);
        putRecords("doomed-stream", 2);

        firehoseService.deleteDeliveryStream("doomed-stream");
        firehoseService.flushDueBuffers(clock.instant().plusSeconds(301));

        verifyNothingDelivered();
    }

    @Test
    void timeBasedFlushDeliversEachBatchOnlyOnce() {
        firehoseService.createDeliveryStream("idle-stream", null);
        putRecords("idle-stream", 2);

        Instant afterInterval = clock.instant().plusSeconds(301);
        firehoseService.flushDueBuffers(afterInterval);
        firehoseService.flushDueBuffers(afterInterval.plusSeconds(301));

        verify(s3Service).putObject(anyString(), anyString(), any(byte[].class), anyString(), anyMap(),
                any(PutObjectOptions.class));
    }

    @Test
    void uncompressedDeliveryCarriesRawBytesWithNoExtensionOrContentEncoding() {
        firehoseService.createDeliveryStream("plain-stream", destination("arn:aws:s3:::custom-bucket",
                "UNCOMPRESSED"));
        putRecordsAndFlush("plain-stream");

        Delivered delivered = delivered("custom-bucket");
        // Ending in the UUID means nothing was appended to the object name.
        assertTrue(delivered.key().matches(".*-" + UUID_REGEX), delivered.key());
        assertEquals(OCTET_STREAM, delivered.contentType());
        assertNull(delivered.contentEncoding());
        assertEquals(FIVE_RECORDS, delivered.text());
    }

    @ParameterizedTest
    @EnumSource(value = FirehoseCompression.class, names = "UNCOMPRESSED", mode = EnumSource.Mode.EXCLUDE)
    void compressedFormatsDeliverTheirOwnFramingExtensionAndContentEncoding(FirehoseCompression format)
            throws IOException {
        firehoseService.createDeliveryStream("compressed-stream",
                destination("arn:aws:s3:::custom-bucket", format.wireValue()));
        putRecordsAndFlush("compressed-stream");

        Delivered delivered = delivered("custom-bucket");
        assertTrue(delivered.key().endsWith(format.extension()), delivered.key());
        assertEquals(OCTET_STREAM, delivered.contentType());
        assertEquals(format.contentEncoding(), delivered.contentEncoding());
        assertFalse(Arrays.equals(FIVE_RECORDS.getBytes(StandardCharsets.UTF_8), delivered.body()),
                "body should not be the plain payload");
        assertEquals(FIVE_RECORDS, delivered.decoded(format));
    }

    /**
     * Verified against real AWS: FileExtension replaces the extension the
     * compression format contributes rather than being appended to it, and leaves
     * the body and its Content-Encoding alone.
     */
    @Test
    void fileExtensionReplacesTheCompressionExtension() throws IOException {
        S3Destination s3 = destination("arn:aws:s3:::custom-bucket", "GZIP");
        s3.setFileExtension(".custom.log");
        firehoseService.createDeliveryStream("ext-stream", s3);
        putRecordsAndFlush("ext-stream");

        Delivered delivered = delivered("custom-bucket");
        assertTrue(delivered.key().endsWith(".custom.log"), delivered.key());
        assertFalse(delivered.key().contains(".gz"), delivered.key());
        assertEquals("gzip", delivered.contentEncoding());
        assertEquals(FIVE_RECORDS, delivered.decoded(FirehoseCompression.GZIP));
    }

    @Test
    void fileExtensionIsAddedWhenTheStreamIsUncompressed() {
        S3Destination s3 = destination("arn:aws:s3:::custom-bucket", "UNCOMPRESSED");
        s3.setFileExtension(".custom.log");
        firehoseService.createDeliveryStream("ext-stream", s3);
        putRecordsAndFlush("ext-stream");

        Delivered delivered = delivered("custom-bucket");
        assertTrue(delivered.key().endsWith(".custom.log"), delivered.key());
        assertNull(delivered.contentEncoding());
        assertEquals(FIVE_RECORDS, delivered.text());
    }

    /** The empty string is a valid FileExtension AWS treats as "not specified". */
    @Test
    void emptyFileExtensionFallsBackToTheCompressionExtension() {
        S3Destination s3 = destination("arn:aws:s3:::custom-bucket", "GZIP");
        s3.setFileExtension("");
        firehoseService.createDeliveryStream("ext-stream", s3);
        putRecordsAndFlush("ext-stream");

        assertTrue(deliveredKey("custom-bucket").endsWith(".gz"), "expected the GZIP extension back");
    }

    /**
     * Verified against real AWS: a stream updated while records are still buffered
     * delivers them with the format in effect at delivery rather than at ingest,
     * and the key carries the version resulting from the update. AWS does not flush
     * the pending buffer early either, it keeps to the original interval.
     */
    @Test
    void compressionChangedWhileRecordsAreBufferedAppliesAtDeliveryTime() {
        firehoseService.createDeliveryStream("mid-stream", destination("arn:aws:s3:::custom-bucket", "GZIP"));
        putRecords("mid-stream", 5);

        S3Destination update = new S3Destination();
        update.setCompressionFormat("UNCOMPRESSED");
        firehoseService.updateDestination("mid-stream", "1", "destinationId-000000000001", update);
        verifyNothingDelivered();

        firehoseService.flush("mid-stream");

        Delivered delivered = delivered("custom-bucket");
        assertNull(delivered.contentEncoding());
        assertEquals(FIVE_RECORDS, delivered.text());
        assertTrue(delivered.key().contains("mid-stream-2-"), delivered.key());
    }

    @Test
    void updateDestinationMergesFileExtension() {
        firehoseService.createDeliveryStream("ext-stream", destination("arn:aws:s3:::custom-bucket", "GZIP"));

        S3Destination update = new S3Destination();
        update.setFileExtension(".custom.log");
        firehoseService.updateDestination("ext-stream", "1", "destinationId-000000000001", update);

        S3Destination described = firehoseService.describeDeliveryStream("ext-stream").s3Destination();
        assertEquals(".custom.log", described.getFileExtension());
        assertEquals("GZIP", described.getCompressionFormat());
    }

    /**
     * Record payloads are arbitrary bytes, so a delivery must not round-trip them
     * through a String: that replaces every byte sequence that is not valid UTF-8.
     */
    @Test
    void recordsThatAreNotValidUtf8AreDeliveredByteForByte() {
        byte[] binary = {(byte) 0xff, (byte) 0xfe, 0x00, (byte) 0x80};
        firehoseService.createDeliveryStream("binary-stream", null);
        firehoseService.putRecord("binary-stream", new Record(binary));
        firehoseService.flush("binary-stream");

        assertArrayEquals(new byte[]{(byte) 0xff, (byte) 0xfe, 0x00, (byte) 0x80, '\n'},
                delivered("floci-firehose-results").body());
    }

}
