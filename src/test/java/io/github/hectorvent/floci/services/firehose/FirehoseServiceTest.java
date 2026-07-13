package io.github.hectorvent.floci.services.firehose;

import io.github.hectorvent.floci.core.common.RegionResolver;
import io.github.hectorvent.floci.core.storage.InMemoryStorage;
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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class FirehoseServiceTest {

    private static final String UUID_REGEX = "[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}";

    private FirehoseService firehoseService;
    private S3Service s3Service;

    @BeforeEach
    void setUp() {
        StorageFactory storageFactory = Mockito.mock(StorageFactory.class);
        when(storageFactory.create(anyString(), anyString(), any()))
                .thenReturn(new InMemoryStorage<>());
        s3Service = Mockito.mock(S3Service.class);
        firehoseService = new FirehoseService(storageFactory, s3Service,
                new RegionResolver("us-east-1", "000000000000"), new MutableClock());
    }

    private void putRecordsUntilFlush(String streamName) {
        for (int i = 0; i < 5; i++) {
            Record record = new Record(("{\"n\":" + i + "}").getBytes(StandardCharsets.UTF_8));
            firehoseService.putRecord(streamName, record);
        }
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
        putRecordsUntilFlush("my-stream");

        String key = deliveredKey("floci-firehose-results");
        assertTrue(key.matches("2026/01/01/00/my-stream-1-2026-01-01-00-00-00-" + UUID_REGEX), key);
    }

    @Test
    void staticPrefixGetsDefaultTimePrefixAppended() {
        S3Destination s3 = new S3Destination();
        s3.setBucketArn("arn:aws:s3:::custom-bucket");
        s3.setPrefix("events/data/");
        firehoseService.createDeliveryStream("my-stream", s3);
        putRecordsUntilFlush("my-stream");

        String key = deliveredKey("custom-bucket");
        assertTrue(key.matches("events/data/2026/01/01/00/my-stream-1-2026-01-01-00-00-00-" + UUID_REGEX), key);
    }

    @Test
    void customTimeZoneShiftsPrefixAndSuffix() {
        S3Destination s3 = new S3Destination();
        s3.setBucketArn("arn:aws:s3:::custom-bucket");
        s3.setCustomTimeZone("Europe/Madrid");
        firehoseService.createDeliveryStream("my-stream", s3);
        putRecordsUntilFlush("my-stream");

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

        putRecordsUntilFlush("my-stream");
        String key = deliveredKey("custom-bucket");
        assertTrue(key.matches("events/2026/01/01/09/my-stream-3-2026-01-01-09-00-00-" + UUID_REGEX), key);
    }
}
