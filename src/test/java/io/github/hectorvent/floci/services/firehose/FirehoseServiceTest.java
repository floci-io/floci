package io.github.hectorvent.floci.services.firehose;

import io.github.hectorvent.floci.config.EmulatorConfig;
import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.core.common.RegionResolver;
import io.github.hectorvent.floci.core.storage.AccountAwareStorageBackend;
import io.github.hectorvent.floci.core.storage.StorageFactory;
import io.github.hectorvent.floci.services.firehose.model.DeliveryStreamDescription;
import io.github.hectorvent.floci.services.firehose.model.DeliveryStreamDescription.S3Destination;
import io.github.hectorvent.floci.services.firehose.model.Record;
import io.github.hectorvent.floci.services.kinesis.KinesisService;
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
import java.time.Duration;
import java.time.Instant;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class FirehoseServiceTest {

    private static final String UUID_REGEX = "[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}";
    /** AWS delivers every format as application/octet-stream, compressed or not. */
    private static final String OCTET_STREAM = "application/octet-stream";
    private static final String FIVE_RECORDS = "{\"n\":0}\n{\"n\":1}\n{\"n\":2}\n{\"n\":3}\n{\"n\":4}\n";

    private FirehoseService firehoseService;
    private KinesisService kinesisService;
    private StorageFactory storageFactory;
    private Map<String, AccountAwareStorageBackend<?>> backends;
    private S3Service s3Service;
    private MutableClock clock;

    @BeforeEach
    void setUp() {
        storageFactory = Mockito.mock(StorageFactory.class);
        // A backend per store, keyed by file name, exactly as the real StorageFactory
        // reuses backends by path. One shared instance would put KinesisStream values in
        // the map FirehoseService.scan() reads as delivery streams; a fresh instance per
        // create() call would make a second service over "the same storage" impossible
        // to build, which is what the restart test needs.
        backends = new HashMap<>();
        when(storageFactory.create(anyString(), anyString(), any()))
                .thenAnswer(invocation -> backends.computeIfAbsent(
                        invocation.getArgument(0) + "/" + invocation.getArgument(1),
                        k -> AccountAwareStorageBackend.inMemory("000000000000")));
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

        RegionResolver regionResolver = new RegionResolver("us-east-1", "000000000000");
        // A real KinesisService, not a mock: the point of the source poller is that it
        // sees what a GetRecords consumer sees, and a stubbed one could not show that.
        kinesisService = new KinesisService(storageFactory, regionResolver);
        return new FirehoseService(storageFactory, s3Service, kinesisService,
                regionResolver, clock, config, mock(FirehoseParquetConverter.class),
                mock(FirehoseLambdaTransformer.class));
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
        return delivered(expectedBucket, 1);
    }

    /** The most recent of exactly {@code attempts} S3 writes. */
    private Delivered delivered(String expectedBucket, int attempts) {
        ArgumentCaptor<String> bucket = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<String> key = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<byte[]> body = ArgumentCaptor.forClass(byte[].class);
        ArgumentCaptor<String> contentType = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<PutObjectOptions> options = ArgumentCaptor.forClass(PutObjectOptions.class);
        verify(s3Service, times(attempts)).putObject(bucket.capture(), key.capture(), body.capture(),
                contentType.capture(), anyMap(), options.capture());
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
    void migratesLegacyAccountAndNameKeysOnlyForMatchingOwner() {
        DeliveryStreamDescription legacy = new DeliveryStreamDescription(
                "legacy", "arn:aws:firehose:us-east-1:111111111111:deliverystream/legacy", null, null);
        legacy.setAccountId("111111111111");
        AccountAwareStorageBackend<DeliveryStreamDescription> streams =
                (AccountAwareStorageBackend<DeliveryStreamDescription>) backends.get("firehose/streams.json");
        streams.putForAccount("111111111111", "legacy", legacy);

        assertEquals("legacy", firehoseService.describeDeliveryStream("111111111111", "us-east-1", "legacy")
                .getDeliveryStreamName());
        assertFalse(streams.getForAccount("111111111111", "legacy").isPresent());
        assertTrue(streams.getForAccount("111111111111", "us-east-1/legacy").isPresent());

        DeliveryStreamDescription wrongRegion = new DeliveryStreamDescription(
                "wrong-region", "arn:aws:firehose:eu-west-1:111111111111:deliverystream/wrong-region", null, null);
        wrongRegion.setAccountId("111111111111");
        streams.putForAccount("111111111111", "wrong-region", wrongRegion);
        assertThrows(AwsException.class,
                () -> firehoseService.describeDeliveryStream("111111111111", "us-east-1", "wrong-region"),
                "legacy entries from another region must not be adopted");
    }

    @Test
    void isolatesSameStreamNameAcrossAccountsAndRegionsIncludingFlushAndDelete() {
        String firstArn = firehoseService.createDeliveryStream("us-east-1", "111111111111", "shared", null,
                List.of(), null, null);
        String secondArn = firehoseService.createDeliveryStream("eu-west-1", "222222222222", "shared", null,
                List.of(), null, null);

        assertTrue(firstArn.contains(":us-east-1:111111111111:deliverystream/shared"));
        assertTrue(secondArn.contains(":eu-west-1:222222222222:deliverystream/shared"));

        firehoseService.putRecord("111111111111", "us-east-1", "shared",
                new Record("first".getBytes(StandardCharsets.UTF_8)));
        firehoseService.putRecord("222222222222", "eu-west-1", "shared",
                new Record("second".getBytes(StandardCharsets.UTF_8)));
        firehoseService.flush("111111111111", "us-east-1", "shared");

        verify(s3Service).putObject(eq("floci-firehose-results"), anyString(),
                argThat(body -> new String(body, StandardCharsets.UTF_8).equals("first\n")),
                eq(OCTET_STREAM), anyMap(), any(PutObjectOptions.class));
        verify(s3Service, times(1)).putObject(anyString(), anyString(), any(byte[].class), anyString(),
                anyMap(), any(PutObjectOptions.class));
        assertEquals("shared", firehoseService.describeDeliveryStream("222222222222", "eu-west-1", "shared")
                .getDeliveryStreamName());

        firehoseService.deleteDeliveryStream("111111111111", "us-east-1", "shared");
        assertEquals("shared", firehoseService.describeDeliveryStream("222222222222", "eu-west-1", "shared")
                .getDeliveryStreamName());
    }

    @Test
    void sameNameIsIndependentForEachAccountRegionDimension() {
        List<String[]> owners = List.of(
                new String[] {"111111111111", "us-east-1"},
                new String[] {"111111111111", "eu-west-1"},
                new String[] {"222222222222", "us-east-1"},
                new String[] {"222222222222", "eu-west-1"});
        for (int i = 0; i < owners.size(); i++) {
            firehoseService.createDeliveryStream(owners.get(i)[1], owners.get(i)[0], "matrix", null,
                    List.of(), null, null);
            firehoseService.putRecord(owners.get(i)[0], owners.get(i)[1], "matrix",
                    new Record(("owner-" + i).getBytes(StandardCharsets.UTF_8)));
        }

        firehoseService.flush("111111111111", "us-east-1", "matrix");

        verify(s3Service).putObject(eq("floci-firehose-results"), anyString(),
                argThat(body -> new String(body, StandardCharsets.UTF_8).equals("owner-0\n")),
                eq(OCTET_STREAM), anyMap(), any(PutObjectOptions.class));
        verify(s3Service, times(1)).putObject(anyString(), anyString(), any(byte[].class), anyString(),
                anyMap(), any(PutObjectOptions.class));
        for (String[] owner : owners) {
            assertEquals("matrix", firehoseService.describeDeliveryStream(owner[0], owner[1], "matrix")
                    .getDeliveryStreamName());
        }
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


    // --- Kinesis stream as source -------------------------------------------------
    //
    // A KinesisStreamAsSource delivery stream is never given records by PutRecord: on
    // real AWS the API rejects that, and everything it delivers comes from Firehose
    // reading the source stream. Recording the source and not reading it makes such a
    // stream a silent black hole -- create succeeds, describe looks healthy, nothing is
    // ever delivered.

    private static DeliveryStreamDescription.KinesisStreamSource kinesisSource(String streamName) {
        DeliveryStreamDescription.KinesisStreamSource source =
                new DeliveryStreamDescription.KinesisStreamSource();
        source.setKinesisStreamArn("arn:aws:kinesis:us-east-1:000000000000:stream/" + streamName);
        source.setRoleArn("arn:aws:iam::000000000000:role/firehose");
        return source;
    }

    private void createSourcedDeliveryStream(String deliveryStream, String sourceStream) {
        kinesisService.createStream(sourceStream, 1, "us-east-1");
        firehoseService.createDeliveryStream(deliveryStream, destination("arn:aws:s3:::sink", null),
                java.util.List.of(), "KinesisStreamAsSource", kinesisSource(sourceStream));
    }

    @Test
    void kinesisSourceRecordsAreDelivered() {
        createSourcedDeliveryStream("sourced-stream", "src-stream");
        kinesisService.putRecord("src-stream", "hello".getBytes(StandardCharsets.UTF_8), "pk", "us-east-1");

        firehoseService.pollKinesisSources();
        firehoseService.flush("sourced-stream");

        assertEquals("hello\n", delivered("sink").text());
    }

    @Test
    void kinesisSourceRecordsAreDeliveredExactlyOnce() {
        createSourcedDeliveryStream("sourced-stream", "src-stream");
        kinesisService.putRecord("src-stream", "hello".getBytes(StandardCharsets.UTF_8), "pk", "us-east-1");

        // The iterator is the checkpoint, so a second poll before the flush must not
        // re-read what the first one already buffered.
        firehoseService.pollKinesisSources();
        firehoseService.pollKinesisSources();
        firehoseService.flush("sourced-stream");

        assertEquals("hello\n", delivered("sink").text());
    }

    @Test
    void kinesisSourcePollingResumesAfterAFlush() {
        createSourcedDeliveryStream("sourced-stream", "src-stream");
        kinesisService.putRecord("src-stream", "first".getBytes(StandardCharsets.UTF_8), "pk", "us-east-1");
        firehoseService.pollKinesisSources();
        firehoseService.flush("sourced-stream");

        kinesisService.putRecord("src-stream", "second".getBytes(StandardCharsets.UTF_8), "pk", "us-east-1");
        firehoseService.pollKinesisSources();
        firehoseService.flush("sourced-stream");

        ArgumentCaptor<byte[]> body = ArgumentCaptor.forClass(byte[].class);
        verify(s3Service, Mockito.times(2)).putObject(anyString(), anyString(), body.capture(), anyString(),
                anyMap(), any(PutObjectOptions.class));
        assertEquals(java.util.List.of("first\n", "second\n"),
                body.getAllValues().stream().map(b -> new String(b, StandardCharsets.UTF_8)).toList());
    }

    @Test
    void kinesisSourceDoesNotBackfillRecordsFromBeforeDeliveryStart() {
        kinesisService.createStream("src-stream", 1, "us-east-1");
        kinesisService.putRecord("src-stream", "old".getBytes(StandardCharsets.UTF_8), "pk", "us-east-1");
        DeliveryStreamDescription.KinesisStreamSource source = kinesisSource("src-stream");
        // Attaching Firehose to a stream that already holds records does not replay them;
        // delivery starts at DeliveryStartTimestamp.
        source.setDeliveryStartTimestamp(Instant.now().plusSeconds(3600));
        firehoseService.createDeliveryStream("sourced-stream", destination("arn:aws:s3:::sink", null),
                java.util.List.of(), "KinesisStreamAsSource", source);

        firehoseService.pollKinesisSources();
        firehoseService.flush("sourced-stream");

        verifyNothingDelivered();
    }

    @Test
    void deliveryStreamWithNoKinesisSourceIsUnaffectedByPolling() {
        firehoseService.createDeliveryStream("plain-stream", destination("arn:aws:s3:::sink", null));

        firehoseService.pollKinesisSources();
        firehoseService.flush("plain-stream");

        verifyNothingDelivered();
    }

    @Test
    void kinesisSourceCheckpointSurvivesARestart() {
        createSourcedDeliveryStream("sourced-stream", "src-stream");
        kinesisService.putRecord("src-stream", "hello".getBytes(StandardCharsets.UTF_8), "pk", "us-east-1");
        firehoseService.pollKinesisSources();
        firehoseService.flush("sourced-stream");

        // Restart: a fresh service over the same storage, the way the emulator comes back
        // up against its persisted state. The delivery stream and the source stream's
        // records both survive, so an in-memory-only checkpoint would rebuild the iterator
        // from DeliveryStartTimestamp and deliver "hello" to S3 a second time.
        firehoseService = newService(0);
        firehoseService.pollKinesisSources();
        firehoseService.flush("sourced-stream");

        assertEquals("hello\n", delivered("sink").text());
    }

    @Test
    void kinesisSourceRecordsBufferedButNeverFlushedSurviveARestart() {
        createSourcedDeliveryStream("sourced-stream", "src-stream");
        kinesisService.putRecord("src-stream", "hello".getBytes(StandardCharsets.UTF_8), "pk", "us-east-1");

        // Poll, then crash. The record is in the in-memory buffer and no flush has run,
        // so nothing reached S3 and nothing in memory survives.
        firehoseService.pollKinesisSources();
        verifyNothingDelivered();

        // Restart. A checkpoint persisted at poll time would already have recorded the
        // record as consumed, and the restored poller would skip past it forever --
        // silent, permanent loss. Committing the checkpoint only on a successful flush
        // means the restored poller reads it again and delivers it.
        firehoseService = newService(0);
        firehoseService.pollKinesisSources();
        firehoseService.flush("sourced-stream");

        assertEquals("hello\n", delivered("sink").text());
    }

    @Test
    void aFailedDeliveryLeavesTheSourceCheckpointUncommitted() {
        createSourcedDeliveryStream("sourced-stream", "src-stream");
        kinesisService.putRecord("src-stream", "hello".getBytes(StandardCharsets.UTF_8), "pk", "us-east-1");
        when(s3Service.putObject(anyString(), anyString(), any(byte[].class), anyString(),
                anyMap(), any(PutObjectOptions.class))).thenThrow(new RuntimeException("s3 unavailable"));

        firehoseService.pollKinesisSources();
        firehoseService.flush("sourced-stream");

        // The write failed, so the record is not durable and the checkpoint must not have
        // moved: once S3 is back, the next poll has to read it again.
        Mockito.reset(s3Service);
        firehoseService.pollKinesisSources();
        firehoseService.flush("sourced-stream");

        assertEquals("hello\n", delivered("sink").text());
    }

    @Test
    void aFailedDeliveryRestoresTheBatchAheadOfRecordsAddedDuringDelivery() {
        firehoseService.createDeliveryStream("retry-stream", destination("arn:aws:s3:::sink", null));
        firehoseService.putRecord("retry-stream", new Record("first".getBytes(StandardCharsets.UTF_8)));
        firehoseService.putRecord("retry-stream", new Record("second".getBytes(StandardCharsets.UTF_8)));
        AtomicBoolean s3Down = new AtomicBoolean(true);
        when(s3Service.putObject(anyString(), anyString(), any(byte[].class), anyString(),
                anyMap(), any(PutObjectOptions.class))).thenAnswer(invocation -> {
            if (s3Down.getAndSet(false)) {
                firehoseService.putRecord("retry-stream", new Record("third".getBytes(StandardCharsets.UTF_8)));
                throw new RuntimeException("s3 unavailable");
            }
            return null;
        });

        firehoseService.flush("retry-stream");
        firehoseService.flush("retry-stream");

        assertEquals("first\nsecond\nthird\n", delivered("sink", 2).text());
    }

    @Test
    void aRestoredBatchIsRetriedOnTheNextScheduledFlush() {
        firehoseService.createDeliveryStream("retry-stream", destination("arn:aws:s3:::sink", null));
        firehoseService.putRecord("retry-stream", new Record("first".getBytes(StandardCharsets.UTF_8)));
        AtomicBoolean s3Down = new AtomicBoolean(true);
        when(s3Service.putObject(anyString(), anyString(), any(byte[].class), anyString(),
                anyMap(), any(PutObjectOptions.class))).thenAnswer(invocation -> {
            if (s3Down.getAndSet(false)) {
                // A record arrives while the write is in flight; it must not push the
                // restored batch's buffering start forward.
                clock.advance(Duration.ofSeconds(1));
                firehoseService.putRecord("retry-stream", new Record("second".getBytes(StandardCharsets.UTF_8)));
                throw new RuntimeException("s3 unavailable");
            }
            return null;
        });
        clock.advance(Duration.ofSeconds(300));
        firehoseService.flushDueBuffers(clock.instant());
        verify(s3Service, times(1)).putObject(anyString(), anyString(), any(byte[].class), anyString(),
                anyMap(), any(PutObjectOptions.class));

        clock.advance(Duration.ofSeconds(10));
        firehoseService.flushDueBuffers(clock.instant());

        assertEquals("first\nsecond\n", delivered("sink", 2).text());
    }

    @Test
    void aSuccessfulDeliveryClearsOnlyTheRecordsItWrote() {
        firehoseService.createDeliveryStream("keep-stream", destination("arn:aws:s3:::sink", null));
        firehoseService.putRecord("keep-stream", new Record("first".getBytes(StandardCharsets.UTF_8)));
        AtomicBoolean firstWrite = new AtomicBoolean(true);
        when(s3Service.putObject(anyString(), anyString(), any(byte[].class), anyString(),
                anyMap(), any(PutObjectOptions.class))).thenAnswer(invocation -> {
            if (firstWrite.getAndSet(false)) {
                firehoseService.putRecord("keep-stream", new Record("second".getBytes(StandardCharsets.UTF_8)));
            }
            return null;
        });

        firehoseService.flush("keep-stream");
        assertEquals("first\n", delivered("sink").text());

        firehoseService.flush("keep-stream");
        assertEquals("second\n", delivered("sink", 2).text());
    }

    @Test
    void aMissingSourceStreamDoesNotStopOtherStreamsFromPolling() {
        // No createStream for "gone-stream": describeStream throws ResourceNotFound.
        firehoseService.createDeliveryStream("broken-stream", destination("arn:aws:s3:::sink", null),
                java.util.List.of(), "KinesisStreamAsSource", kinesisSource("gone-stream"));
        createSourcedDeliveryStream("sourced-stream", "src-stream");
        kinesisService.putRecord("src-stream", "hello".getBytes(StandardCharsets.UTF_8), "pk", "us-east-1");

        firehoseService.pollKinesisSources();
        firehoseService.flush("sourced-stream");

        assertEquals("hello\n", delivered("sink").text());
    }
}
