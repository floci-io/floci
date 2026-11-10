package io.floci.aws.services.s3;

import io.floci.aws.core.common.AwsException;
import io.floci.aws.core.storage.InMemoryStorage;
import io.floci.aws.services.s3.model.S3Object;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * CompleteMultipartUpload's If-Match / If-None-Match must be judged atomically with the write, as
 * PutObject's are, so concurrent conditional completions on one key cannot all succeed.
 */
class S3MultipartConditionalCompleteTest {

    private static final String BUCKET = "conditional-complete";
    private static final String KEY = "object";

    @TempDir
    Path tempDir;

    private S3Service newService(boolean inMemory) {
        S3Service service = new S3Service(new InMemoryStorage<>(), new InMemoryStorage<>(),
                tempDir.resolve(inMemory ? "memory" : "disk"), inMemory);
        service.createBucket(BUCKET, "us-east-1");
        return service;
    }

    private static String uploadOnePart(S3Service service, byte[] data) {
        String uploadId = service.initiateMultipartUpload(BUCKET, KEY, "application/octet-stream").getUploadId();
        service.uploadPart(BUCKET, KEY, uploadId, 1, data);
        return uploadId;
    }

    private static S3Object complete(S3Service service, String uploadId, String ifMatch, String ifNoneMatch) {
        return service.completeMultipartUpload(BUCKET, KEY, uploadId, List.of(1), Map.of(), Map.of(),
                null, null, null, ifMatch, ifNoneMatch);
    }

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void ifNoneMatchStarFailsWhenTheKeyExistsAndKeepsTheObjectAndUpload(boolean inMemory) {
        S3Service service = newService(inMemory);
        byte[] original = "original".getBytes(StandardCharsets.UTF_8);
        service.putObject(BUCKET, KEY, original, "application/octet-stream", Map.of());
        String uploadId = uploadOnePart(service, "replacement".getBytes(StandardCharsets.UTF_8));

        AwsException failed = assertThrows(AwsException.class, () -> complete(service, uploadId, null, "*"));

        assertEquals(412, failed.getHttpStatus());
        assertEquals("PreconditionFailed", failed.getErrorCode());
        assertArrayEquals(original, service.getObject(BUCKET, KEY).getData());
        assertEquals(uploadId, service.getMultipartUpload(BUCKET, KEY, uploadId).getUploadId());
    }

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void ifMatchFailsWhenTheETagDiffers(boolean inMemory) {
        S3Service service = newService(inMemory);
        service.putObject(BUCKET, KEY, "original".getBytes(StandardCharsets.UTF_8),
                "application/octet-stream", Map.of());
        String uploadId = uploadOnePart(service, "replacement".getBytes(StandardCharsets.UTF_8));

        AwsException failed = assertThrows(AwsException.class,
                () -> complete(service, uploadId, "\"0123456789abcdef0123456789abcdef\"", null));

        assertEquals(412, failed.getHttpStatus());
    }

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void concurrentIfNoneMatchStarCompletionsHaveExactlyOneWinner(boolean inMemory) throws Exception {
        S3Service service = newService(inMemory);
        int writers = 16;
        List<String> uploadIds = new ArrayList<>();
        for (int i = 0; i < writers; i++) {
            uploadIds.add(uploadOnePart(service, ("writer-" + i).getBytes(StandardCharsets.UTF_8)));
        }

        CyclicBarrier start = new CyclicBarrier(writers);
        ExecutorService pool = Executors.newFixedThreadPool(writers);
        List<Future<byte[]>> results = new ArrayList<>();
        try {
            for (int i = 0; i < writers; i++) {
                String uploadId = uploadIds.get(i);
                byte[] body = ("writer-" + i).getBytes(StandardCharsets.UTF_8);
                results.add(pool.submit(() -> {
                    start.await(5, TimeUnit.SECONDS);
                    try {
                        complete(service, uploadId, null, "*");
                        return body;
                    } catch (AwsException e) {
                        assertEquals(412, e.getHttpStatus());
                        return null;
                    }
                }));
            }
            List<byte[]> winners = new ArrayList<>();
            for (Future<byte[]> result : results) {
                byte[] won = result.get(10, TimeUnit.SECONDS);
                if (won != null) {
                    winners.add(won);
                }
            }

            assertEquals(1, winners.size(), "exactly one conditional completion may create the key");
            assertArrayEquals(winners.get(0), service.getObject(BUCKET, KEY).getData());
        } finally {
            pool.shutdownNow();
            assertTrue(pool.awaitTermination(5, TimeUnit.SECONDS));
        }
    }
}
