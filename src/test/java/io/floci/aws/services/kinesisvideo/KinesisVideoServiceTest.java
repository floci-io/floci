package io.floci.aws.services.kinesisvideo;

import io.floci.aws.core.common.AwsException;
import io.floci.aws.core.storage.AccountAwareStorageBackend;
import io.floci.aws.services.kinesisvideo.model.KinesisVideoStream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class KinesisVideoServiceTest {
    private static final String REGION = "us-east-1";
    private static final String ACCOUNT = "111111111111";
    private static final String OTHER_ACCOUNT = "222222222222";

    private KinesisVideoService service;

    @BeforeEach
    void setUp() {
        service = new KinesisVideoService(AccountAwareStorageBackend.inMemory(ACCOUNT));
    }

    @Test
    void createStream_minimalRequest_returnsActiveStreamWithDefaults() {
        KinesisVideoStream stream = service.createStream(REGION, ACCOUNT, request("camera-1"));

        assertTrue(stream.streamArn().matches(
                "arn:aws:kinesisvideo:us-east-1:111111111111:stream/camera-1/[0-9]+"));
        assertEquals("ACTIVE", stream.status());
        assertEquals(0, stream.dataRetentionInHours());
        assertEquals("arn:aws:kms:us-east-1:111111111111:alias/aws/kinesisvideo", stream.kmsKeyId());
        assertTrue(stream.version().matches("[a-zA-Z0-9]{1,64}"));
        assertNull(stream.mediaType());
        assertNull(stream.deviceName());
    }

    @Test
    void createStream_fullRequest_keepsEveryField() {
        KinesisVideoStream stream = service.createStream(REGION, ACCOUNT, new KinesisVideoService.CreateStreamRequest(
                "camera-1", "device-1", "video/h264", "my-key", 24, Map.of("env", "dev")));

        KinesisVideoStream described = service.describeStream(REGION, ACCOUNT, "camera-1", null);
        assertEquals(stream, described);
        assertEquals("device-1", described.deviceName());
        assertEquals("video/h264", described.mediaType());
        assertEquals("my-key", described.kmsKeyId());
        assertEquals(24, described.dataRetentionInHours());
        assertEquals(Map.of("env", "dev"), described.tags());
    }

    @Test
    void createStream_duplicateName_throwsResourceInUse() {
        service.createStream(REGION, ACCOUNT, request("camera-1"));

        AwsException error = assertThrows(AwsException.class,
                () -> service.createStream(REGION, ACCOUNT, request("camera-1")));
        assertEquals("ResourceInUseException", error.getErrorCode());
        assertEquals(400, error.getHttpStatus());
    }

    @Test
    void createStream_sameNameInOtherRegionOrAccount_isIndependent() {
        service.createStream(REGION, ACCOUNT, request("camera-1"));
        service.createStream("eu-west-1", ACCOUNT, request("camera-1"));
        service.createStream(REGION, OTHER_ACCOUNT, request("camera-1"));

        assertEquals(1, service.listStreams(REGION, ACCOUNT, null, null, null).streams().size());
        assertEquals(1, service.listStreams("eu-west-1", ACCOUNT, null, null, null).streams().size());
        assertEquals(1, service.listStreams(REGION, OTHER_ACCOUNT, null, null, null).streams().size());
    }

    @Test
    void describeStream_byArn_returnsStream() {
        KinesisVideoStream stream = service.createStream(REGION, ACCOUNT, request("camera-1"));

        assertEquals(stream, service.describeStream(REGION, ACCOUNT, null, stream.streamArn()));
    }

    @Test
    void describeStream_unknownStream_throwsResourceNotFound() {
        assertNotFound(() -> service.describeStream(REGION, ACCOUNT, "missing", null));
        assertNotFound(() -> service.describeStream(REGION, ACCOUNT, null,
                "arn:aws:kinesisvideo:us-east-1:111111111111:stream/missing/1"));
    }

    @Test
    void recreatedStream_getsNewArn_andOldArnNoLongerResolves() {
        for (int i = 0; i < 100; i++) {
            KinesisVideoStream first = service.createStream(REGION, ACCOUNT, request("camera-1"));
            service.deleteStream(REGION, ACCOUNT, first.streamArn(), null);
            KinesisVideoStream second = service.createStream(REGION, ACCOUNT, request("camera-1"));

            assertNotEquals(first.streamArn(), second.streamArn());
            assertNotFound(() -> service.describeStream(REGION, ACCOUNT, null, first.streamArn()));
            assertNotFound(() -> service.deleteStream(REGION, ACCOUNT, first.streamArn(), null));
            assertEquals(second, service.describeStream(REGION, ACCOUNT, "camera-1", null));
            service.deleteStream(REGION, ACCOUNT, second.streamArn(), null);
        }
    }

    @Test
    void describeStream_withoutNameOrArn_throwsInvalidArgument() {
        assertInvalidArgument(() -> service.describeStream(REGION, ACCOUNT, null, null));
        assertInvalidArgument(() -> service.describeStream(REGION, ACCOUNT, null, "not-an-arn"));
        assertInvalidArgument(() -> service.describeStream(REGION, ACCOUNT, null,
                "arn:aws:kinesisvideo:us-east-1:111111111111:channel/camera-1/1"));
    }

    @Test
    void deleteStream_removesStream() {
        KinesisVideoStream stream = service.createStream(REGION, ACCOUNT, request("camera-1"));

        service.deleteStream(REGION, ACCOUNT, stream.streamArn(), null);

        assertNotFound(() -> service.describeStream(REGION, ACCOUNT, "camera-1", null));
        assertTrue(service.listStreams(REGION, ACCOUNT, null, null, null).streams().isEmpty());
    }

    @Test
    void deleteStream_matchingCurrentVersion_removesStream() {
        KinesisVideoStream stream = service.createStream(REGION, ACCOUNT, request("camera-1"));

        service.deleteStream(REGION, ACCOUNT, stream.streamArn(), stream.version());

        assertNotFound(() -> service.describeStream(REGION, ACCOUNT, "camera-1", null));
    }

    @Test
    void deleteStream_staleCurrentVersion_throwsVersionMismatchAndKeepsStream() {
        KinesisVideoStream stream = service.createStream(REGION, ACCOUNT, request("camera-1"));

        AwsException error = assertThrows(AwsException.class,
                () -> service.deleteStream(REGION, ACCOUNT, stream.streamArn(), "stale1"));
        assertEquals("VersionMismatchException", error.getErrorCode());
        assertEquals(stream, service.describeStream(REGION, ACCOUNT, "camera-1", null));
    }

    @Test
    void deleteStream_unknownArn_throwsResourceNotFound() {
        assertNotFound(() -> service.deleteStream(REGION, ACCOUNT,
                "arn:aws:kinesisvideo:us-east-1:111111111111:stream/missing/1", null));
    }

    @Test
    void listStreams_filtersByPrefixAndPaginates() {
        service.createStream(REGION, ACCOUNT, request("camera-b"));
        service.createStream(REGION, ACCOUNT, request("camera-a"));
        service.createStream(REGION, ACCOUNT, request("doorbell"));

        KinesisVideoService.Page first = service.listStreams(REGION, ACCOUNT, 1, null, "camera");
        assertEquals(List.of("camera-a"), names(first));
        assertTrue(first.nextToken().matches("[a-zA-Z0-9+/=]*"));

        KinesisVideoService.Page second = service.listStreams(REGION, ACCOUNT, 1, first.nextToken(), "camera");
        assertEquals(List.of("camera-b"), names(second));
        assertNull(second.nextToken());

        assertEquals(List.of("camera-a", "camera-b", "doorbell"),
                names(service.listStreams(REGION, ACCOUNT, null, null, null)));
    }

    @Test
    void listStreams_streamDeletedBetweenPages_doesNotSkipRemainingStreams() {
        KinesisVideoStream a = service.createStream(REGION, ACCOUNT, request("a"));
        service.createStream(REGION, ACCOUNT, request("b"));
        service.createStream(REGION, ACCOUNT, request("c"));

        KinesisVideoService.Page first = service.listStreams(REGION, ACCOUNT, 1, null, null);
        assertEquals(List.of("a"), names(first));
        service.deleteStream(REGION, ACCOUNT, a.streamArn(), null);

        KinesisVideoService.Page second = service.listStreams(REGION, ACCOUNT, 1, first.nextToken(), null);
        assertEquals(List.of("b"), names(second));
        KinesisVideoService.Page third = service.listStreams(REGION, ACCOUNT, 1, second.nextToken(), null);
        assertEquals(List.of("c"), names(third));
        assertNull(third.nextToken());
    }

    @Test
    void listStreams_invalidNextToken_throwsInvalidArgument() {
        assertInvalidArgument(() -> service.listStreams(REGION, ACCOUNT, null, "not a token", null));
        assertInvalidArgument(() -> service.listStreams(REGION, ACCOUNT, null, "IA==", null));
    }

    private static KinesisVideoService.CreateStreamRequest request(String streamName) {
        return new KinesisVideoService.CreateStreamRequest(streamName, null, null, null, null, null);
    }

    private static List<String> names(KinesisVideoService.Page page) {
        return page.streams().stream().map(KinesisVideoStream::streamName).toList();
    }

    private static void assertInvalidArgument(Runnable call) {
        AwsException error = assertThrows(AwsException.class, call::run);
        assertEquals("InvalidArgumentException", error.getErrorCode());
        assertEquals(400, error.getHttpStatus());
    }

    private static void assertNotFound(Runnable call) {
        AwsException error = assertThrows(AwsException.class, call::run);
        assertEquals("ResourceNotFoundException", error.getErrorCode());
        assertEquals(404, error.getHttpStatus());
    }
}
