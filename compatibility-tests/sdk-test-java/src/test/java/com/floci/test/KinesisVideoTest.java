package com.floci.test;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import software.amazon.awssdk.services.kinesisvideo.KinesisVideoClient;
import software.amazon.awssdk.services.kinesisvideo.model.ComparisonOperator;
import software.amazon.awssdk.services.kinesisvideo.model.ResourceInUseException;
import software.amazon.awssdk.services.kinesisvideo.model.ResourceNotFoundException;
import software.amazon.awssdk.services.kinesisvideo.model.Status;
import software.amazon.awssdk.services.kinesisvideo.model.StreamInfo;
import software.amazon.awssdk.services.kinesisvideo.model.VersionMismatchException;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("Kinesis Video Streams")
class KinesisVideoTest {

    @Test
    @DisplayName("creates, describes, lists and deletes a stream through the AWS SDK")
    void streamLifecycle() {
        String name = TestFixtures.uniqueName("kvs");
        try (KinesisVideoClient kvs = TestFixtures.kinesisVideoClient()) {
            String arn = kvs.createStream(request -> request
                    .streamName(name)
                    .mediaType("video/h264")
                    .dataRetentionInHours(24)
                    .tags(Map.of("env", "compat"))).streamARN();
            assertThat(arn).startsWith(TestFixtures.arn("kinesisvideo", "000000000000", "stream/" + name + "/"));

            StreamInfo info = kvs.describeStream(request -> request.streamName(name)).streamInfo();
            assertThat(info.streamARN()).isEqualTo(arn);
            assertThat(info.status()).isEqualTo(Status.ACTIVE);
            assertThat(info.mediaType()).isEqualTo("video/h264");
            assertThat(info.dataRetentionInHours()).isEqualTo(24);
            assertThat(info.version()).isNotBlank();
            assertThat(info.kmsKeyId()).isNotBlank();
            assertThat(info.creationTime()).isBetween(Instant.now().minus(Duration.ofMinutes(5)), Instant.now());

            assertThat(kvs.describeStream(request -> request.streamARN(arn)).streamInfo().streamName())
                    .isEqualTo(name);

            List<StreamInfo> listed = kvs.listStreams(request -> request.streamNameCondition(condition -> condition
                    .comparisonOperator(ComparisonOperator.BEGINS_WITH)
                    .comparisonValue(name))).streamInfoList();
            assertThat(listed).extracting(StreamInfo::streamName).containsExactly(name);

            assertThatThrownBy(() -> kvs.createStream(request -> request.streamName(name)))
                    .isInstanceOf(ResourceInUseException.class);
            assertThatThrownBy(() -> kvs.deleteStream(request -> request.streamARN(arn).currentVersion("stale1")))
                    .isInstanceOf(VersionMismatchException.class);

            kvs.deleteStream(request -> request.streamARN(arn).currentVersion(info.version()));

            assertThatThrownBy(() -> kvs.describeStream(request -> request.streamName(name)))
                    .isInstanceOf(ResourceNotFoundException.class);
            assertThatThrownBy(() -> kvs.deleteStream(request -> request.streamARN(arn)))
                    .isInstanceOf(ResourceNotFoundException.class);
        }
    }
}
