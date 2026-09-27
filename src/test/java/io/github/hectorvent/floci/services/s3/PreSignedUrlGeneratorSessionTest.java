package io.github.hectorvent.floci.services.s3;

import io.github.hectorvent.floci.services.iam.IamService;
import org.junit.jupiter.api.Test;

import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class PreSignedUrlGeneratorSessionTest {

    @Test
    void renewingCredentialsKeepsOldUrlsUsableUntilSessionExpiry() {
        Instant start = Instant.parse("2026-09-27T00:00:00Z");
        Clock clock = mock(Clock.class);
        when(clock.instant()).thenReturn(start, start.plus(Duration.ofDays(1)),
                start.plus(Duration.ofDays(7)).plusSeconds(1));

        Map<String, String> secrets = new ConcurrentHashMap<>();
        IamService iamService = mock(IamService.class);
        doAnswer(invocation -> {
            secrets.put(invocation.getArgument(1), invocation.getArgument(2));
            return null;
        }).when(iamService).registerSessionForAccount(anyString(), anyString(), anyString(),
                anyString(), isNull(), any(Instant.class), isNull());
        when(iamService.findSecretKey(anyString(), anyString())).thenAnswer(invocation ->
                Optional.ofNullable(secrets.get(invocation.getArgument(0))));
        doAnswer(invocation -> {
            secrets.remove(invocation.getArgument(1));
            return null;
        }).when(iamService).unregisterSession(anyString(), anyString());

        PreSignedUrlGenerator generator = new PreSignedUrlGenerator(
                "secret", 3600, true, "us-east-1", "000000000000",
                iamService, null, new SecureRandom(), clock);
        String first = generator.generatePresignedUrl(
                "http://localhost:4566", "bucket", "one", "GET", 604800);
        String firstKey = accessKey(first);
        String second = generator.generatePresignedUrl(
                "http://localhost:4566", "bucket", "two", "GET", 604800);
        String secondKey = accessKey(second);

        assertNotEquals(firstKey, secondKey);
        assertTrue(secrets.containsKey(firstKey), "The original URL is still valid after renewal");
        assertTrue(secrets.containsKey(secondKey));
        assertEquals(2, secrets.size());

        generator.generatePresignedUrl("http://localhost:4566", "bucket", "three", "GET", 3600);
        assertFalse(secrets.containsKey(firstKey), "Expired superseded sessions are removed");
        assertTrue(secrets.containsKey(secondKey));
        assertEquals(1, secrets.size());
        verify(iamService, times(1)).sweepExpiredSessions(start);
    }

    private static String accessKey(String url) {
        String credential = url.split("X-Amz-Credential=", 2)[1].split("&", 2)[0];
        return credential.split("%2F", 2)[0];
    }
}
