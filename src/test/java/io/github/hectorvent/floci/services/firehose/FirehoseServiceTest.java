package io.github.hectorvent.floci.services.firehose;

import io.github.hectorvent.floci.config.EmulatorConfig;
import io.github.hectorvent.floci.core.common.RegionResolver;
import io.github.hectorvent.floci.core.storage.AccountAwareStorageBackend;
import io.github.hectorvent.floci.core.storage.StorageFactory;
import io.github.hectorvent.floci.services.firehose.model.DeliveryStreamDescription;
import io.github.hectorvent.floci.services.firehose.model.DeliveryStreamDescription.S3Destination;
import io.github.hectorvent.floci.services.firehose.model.Record;
import io.github.hectorvent.floci.services.s3.S3Service;
import io.github.hectorvent.floci.testing.MutableClock;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;

import java.nio.charset.StandardCharsets;
import java.time.Instant;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class FirehoseServiceTest {

    private static final String UUID_REGEX = "[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}";

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

    private String deliveredKey(String expectedBucket) {
        ArgumentCaptor<String> bucket = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<String> key = ArgumentCaptor.forClass(String.class);
        verify(s3Service).putObject(bucket.capture(), key.capture(), any(byte[].class), anyString(), anyMap());
        assertEquals(expectedBucket, bucket.getValue());
        return key.getValue();
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

        verify(s3Service, never()).putObject(anyString(), anyString(), any(byte[].class), anyString(), anyMap());
    }

    @Test
    void timeBasedFlushDeliversBufferedRecordsAfterDefaultInterval() {
        firehoseService.createDeliveryStream("idle-stream", null);
        putRecords("idle-stream", 2);

        firehoseService.flushDueBuffers(clock.instant().plusSeconds(300));

        ArgumentCaptor<byte[]> body = ArgumentCaptor.forClass(byte[].class);
        verify(s3Service).putObject(eq("floci-firehose-results"), anyString(), body.capture(), anyString(), anyMap());
        assertEquals("{\"n\":0}\n{\"n\":1}\n", new String(body.getValue(), StandardCharsets.UTF_8));
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
        verify(s3Service, never()).putObject(anyString(), anyString(), any(byte[].class), anyString(), anyMap());

        firehoseService.flushDueBuffers(clock.instant().plusSeconds(60));
        ArgumentCaptor<byte[]> body = ArgumentCaptor.forClass(byte[].class);
        verify(s3Service).putObject(eq("custom-bucket"), anyString(), body.capture(), anyString(), anyMap());
        assertEquals("{\"n\":0}\n", new String(body.getValue(), StandardCharsets.UTF_8));
    }

    /** Matches real AWS: the volume trigger is bytes vs SizeInMBs, never a record count. */
    @Test
    void smallRecordsDoNotTriggerSizeBasedFlushRegardlessOfCount() {
        firehoseService.createDeliveryStream("trickle-stream", null);
        putRecords("trickle-stream", 20);

        verify(s3Service, never()).putObject(anyString(), anyString(), any(byte[].class), anyString(), anyMap());
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
        verify(s3Service, never()).putObject(anyString(), anyString(), any(byte[].class), anyString(), anyMap());

        firehoseService.putRecord("bulky-stream", new Record(new byte[512 * 1024]));
        ArgumentCaptor<byte[]> body = ArgumentCaptor.forClass(byte[].class);
        verify(s3Service).putObject(eq("custom-bucket"), anyString(), body.capture(), anyString(), anyMap());
        // Both 512 KiB records, each followed by the newline the flush appends.
        assertEquals(2 * 512 * 1024 + 2, body.getValue().length);
    }

    /** Emulator-only opt-in: flush-record-count=1 restores LocalStack-style record-at-a-time delivery. */
    @Test
    void flushRecordCountOfOneDeliversEachRecordImmediately() {
        firehoseService = newService(1);
        firehoseService.createDeliveryStream("eager-stream", null);
        firehoseService.putRecord("eager-stream", new Record("{\"n\":0}".getBytes(StandardCharsets.UTF_8)));

        ArgumentCaptor<byte[]> body = ArgumentCaptor.forClass(byte[].class);
        verify(s3Service).putObject(eq("floci-firehose-results"), anyString(), body.capture(), anyString(), anyMap());
        assertEquals("{\"n\":0}\n", new String(body.getValue(), StandardCharsets.UTF_8));
    }

    @Test
    void flushRecordCountThresholdDeliversTheWholeBuffer() {
        firehoseService = newService(3);
        firehoseService.createDeliveryStream("counted-stream", null);
        putRecords("counted-stream", 2);
        verify(s3Service, never()).putObject(anyString(), anyString(), any(byte[].class), anyString(), anyMap());

        firehoseService.putRecord("counted-stream", new Record("{\"n\":2}".getBytes(StandardCharsets.UTF_8)));

        ArgumentCaptor<byte[]> body = ArgumentCaptor.forClass(byte[].class);
        verify(s3Service).putObject(eq("floci-firehose-results"), anyString(), body.capture(), anyString(), anyMap());
        assertEquals("{\"n\":0}\n{\"n\":1}\n{\"n\":2}\n", new String(body.getValue(), StandardCharsets.UTF_8));
    }

    /** Matches real AWS: DeleteDeliveryStream discards undelivered records instead of flushing them. */
    @Test
    void deleteDeliveryStreamDiscardsBufferedRecords() {
        firehoseService.createDeliveryStream("doomed-stream", null);
        putRecords("doomed-stream", 2);

        firehoseService.deleteDeliveryStream("doomed-stream");
        firehoseService.flushDueBuffers(clock.instant().plusSeconds(301));

        verify(s3Service, never()).putObject(anyString(), anyString(), any(byte[].class), anyString(), anyMap());
    }

    @Test
    void timeBasedFlushDeliversEachBatchOnlyOnce() {
        firehoseService.createDeliveryStream("idle-stream", null);
        putRecords("idle-stream", 2);

        Instant afterInterval = clock.instant().plusSeconds(301);
        firehoseService.flushDueBuffers(afterInterval);
        firehoseService.flushDueBuffers(afterInterval.plusSeconds(301));

        verify(s3Service).putObject(anyString(), anyString(), any(byte[].class), anyString(), anyMap());
    }
}
