package io.github.hectorvent.floci.services.s3;

import io.github.hectorvent.floci.services.iam.IamService;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
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
                start.plus(Duration.ofDays(7)).plusSeconds(2));

        Map<String, String> secrets = new ConcurrentHashMap<>();
        IamService iamService = mock(IamService.class);
        doAnswer(invocation -> {
            secrets.put(invocation.getArgument(1), invocation.getArgument(2));
            return null;
        }).when(iamService).registerPresignedUrlSession(anyString(), anyString(), anyString(),
                anyString(), any(Instant.class), anyString(), anyString(), anyString());
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
        assertEquals(2, secrets.size(), "Unexpired object-scoped sessions remain available");
        verify(iamService, times(1)).sweepExpiredSessions(start);
    }

    @Test
    void credentialsExpireWithTheirUrlsAndAreScopedToAnObjectAndMethod() {
        Instant start = Instant.parse("2026-09-27T00:00:00Z");
        Clock clock = Clock.fixed(start, ZoneOffset.UTC);
        IamService iamService = mock(IamService.class);
        PreSignedUrlGenerator generator = new PreSignedUrlGenerator(
                "secret", 3600, true, "us-east-1", "000000000000",
                iamService, null, new SecureRandom(), clock);

        String first = generator.generatePresignedUrl("http://localhost:4566", "bucket", "a", "GET", 60);
        String second = generator.generatePresignedUrl("http://localhost:4566", "bucket", "b", "GET", 60);
        String third = generator.generatePresignedUrl("http://localhost:4566", "bucket", "a", "PUT", 60);
        assertNotEquals(accessKey(first), accessKey(second));
        assertNotEquals(accessKey(first), accessKey(third));

        ArgumentCaptor<Instant> expirations = ArgumentCaptor.forClass(Instant.class);
        ArgumentCaptor<String> policies = ArgumentCaptor.forClass(String.class);
        verify(iamService, times(3)).registerPresignedUrlSession(anyString(), anyString(), anyString(),
                anyString(), expirations.capture(), policies.capture(), anyString(), anyString());
        assertEquals(start.plusSeconds(61), expirations.getAllValues().getFirst());
        assertTrue(policies.getAllValues().get(0).contains("\"Action\":\"s3:GetObject\""));
        assertTrue(policies.getAllValues().get(0).contains("arn:aws:s3:::bucket/a"));
        assertTrue(policies.getAllValues().get(1).contains("arn:aws:s3:::bucket/b"));
        assertTrue(policies.getAllValues().get(2).contains("\"Action\":[\"s3:PutObject\",\"s3:GetObject\"]"));
        assertThrows(IllegalArgumentException.class, () -> generator.generatePresignedUrl(
                "http://localhost:4566", "bucket", "a", "GET", 604801));
    }

    private static String accessKey(String url) {
        String credential = url.split("X-Amz-Credential=", 2)[1].split("&", 2)[0];
        return credential.split("%2F", 2)[0];
    }
}
