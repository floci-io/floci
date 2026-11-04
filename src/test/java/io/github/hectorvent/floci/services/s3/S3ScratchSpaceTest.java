package io.github.hectorvent.floci.services.s3;

import io.github.hectorvent.floci.core.common.AwsException;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import java.io.IOException;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.awaitility.Awaitility.await;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class S3ScratchSpaceTest {
    @ParameterizedTest
    @EnumSource(S3ScratchSpace.class)
    void createsTagsReusesAndRejectsUntaggedBuckets(S3ScratchSpace space) throws Exception {
        S3Service s3 = mock(S3Service.class);
        space.ensureBucket(s3, "us-east-1");
        AtomicReference<Map<String, String>> tags = new AtomicReference<>();
        verify(s3).putBucketTagging(eq(space.bucketName()), argThat(value -> {
            tags.set(value);
            return true;
        }));
        when(s3.createInternalBucket(anyString(), anyString())).thenThrow(
                new AwsException("BucketAlreadyOwnedByYou", "exists", 409));
        when(s3.getBucketTagging(space.bucketName())).thenReturn(tags.get());
        space.ensureBucket(s3, "us-east-1");
        verify(s3, times(1)).putBucketTagging(anyString(), any());
        when(s3.getBucketTagging(space.bucketName())).thenReturn(Map.of());
        assertThrows(IOException.class, () -> space.ensureBucket(s3, "us-east-1"));
        verify(s3, times(1)).putBucketTagging(anyString(), any());
    }

    @ParameterizedTest
    @EnumSource(S3ScratchSpace.class)
    void concurrentInitializationWaitsForTheInternalTag(S3ScratchSpace space) throws Exception {
        S3Service s3 = mock(S3Service.class);
        CountDownLatch tagging = new CountDownLatch(1);
        CountDownLatch releaseTag = new CountDownLatch(1);
        AtomicReference<Map<String, String>> tags = new AtomicReference<>(Map.of());
        when(s3.createInternalBucket(anyString(), anyString())).thenReturn(null).thenThrow(
                new AwsException("BucketAlreadyOwnedByYou", "exists", 409));
        doAnswer(invocation -> {
            tagging.countDown();
            assertTrue(releaseTag.await(10, TimeUnit.SECONDS));
            tags.set(invocation.getArgument(1));
            return null;
        }).when(s3).putBucketTagging(anyString(), any());
        when(s3.getBucketTagging(anyString())).thenAnswer(invocation -> tags.get());
        try (ExecutorService executor = Executors.newFixedThreadPool(2)) {
            Future<?> first = executor.submit(() -> { space.ensureBucket(s3, "us-east-1"); return null; });
            assertTrue(tagging.await(10, TimeUnit.SECONDS));
            AtomicReference<Thread> secondThread = new AtomicReference<>();
            Future<?> second = executor.submit(() -> {
                secondThread.set(Thread.currentThread());
                space.ensureBucket(s3, "us-east-1");
                return null;
            });
            try {
                await().atMost(Duration.ofSeconds(5)).until(() -> secondThread.get() != null
                        && secondThread.get().getState() == Thread.State.BLOCKED);
                verify(s3, times(1)).createInternalBucket(anyString(), anyString());
            } finally {
                releaseTag.countDown();
            }
            first.get(10, TimeUnit.SECONDS);
            second.get(10, TimeUnit.SECONDS);
        }
    }

}
