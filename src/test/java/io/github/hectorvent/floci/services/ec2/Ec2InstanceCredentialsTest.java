package io.github.hectorvent.floci.services.ec2;

import io.github.hectorvent.floci.services.ec2.model.Instance;
import io.github.hectorvent.floci.services.iam.IamService;
import io.github.hectorvent.floci.services.iam.model.IamRole;
import io.github.hectorvent.floci.services.iam.model.InstanceProfile;
import io.github.hectorvent.floci.services.iam.model.SessionCredential;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.Callable;
import java.util.concurrent.Executors;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.stream.IntStream;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class Ec2InstanceCredentialsTest {
    private static final String ACCOUNT = "123456789012";
    private static final String PROFILE = "arn:aws:iam::123456789012:instance-profile/path/profile";
    private static final Instant NOW = Instant.parse("2026-09-16T10:00:00Z");

    @Test
    void credentialsAreRegisteredStableAndRotateWithOverlap() {
        Fixture fixture = fixture();
        SessionCredential first = fixture.get(NOW);
        assertTrue(first.getAccessKeyId().matches("ASIA[A-F0-9]{16}"));
        assertEquals(40, first.getSecretAccessKey().length());
        assertEquals(ACCOUNT, first.getOriginAccountId());
        assertEquals("i-first", first.getEc2InstanceId());
        assertEquals(NOW.plusSeconds(3600), first.getExpiration());
        verify(fixture.iam).registerEc2InstanceSession(first);
        assertSame(first, fixture.get(NOW.plusSeconds(3299)));
        SessionCredential second = fixture.get(NOW.plusSeconds(3300));
        assertNotEquals(first.getAccessKeyId(), second.getAccessKeyId());
        assertNotEquals(first.getSessionToken(), second.getSessionToken());
        verify(fixture.iam, never()).unregisterSession(ACCOUNT, first.getAccessKeyId());
        fixture.get(NOW.plusSeconds(3600));
        verify(fixture.iam).unregisterSession(ACCOUNT, first.getAccessKeyId());
    }

    @Test
    void unregisterRevokesEveryGenerationAndPreventsLateIssuance() {
        Fixture fixture = fixture();
        SessionCredential first = fixture.get(NOW);
        SessionCredential second = fixture.get(NOW.plusSeconds(3300));
        fixture.instance.setIamInstanceProfileArn(null);
        fixture.credentials.unregister(fixture.instance);
        verify(fixture.iam).unregisterSession(ACCOUNT, first.getAccessKeyId());
        verify(fixture.iam).unregisterSession(ACCOUNT, second.getAccessKeyId());
        fixture.instance.setIamInstanceProfileArn(PROFILE);
        assertTrue(fixture.credentials.get(fixture.instance, "worker", NOW).isEmpty());
    }

    @Test
    void roleRemovalAndRecreationDoNotReuseOldCredentials() {
        Fixture fixture = fixture();
        SessionCredential first = fixture.get(NOW);
        fixture.role.setRoleId("AROA-recreated");
        SessionCredential second = fixture.get(NOW.plusSeconds(1));
        assertNotEquals(first.getAccessKeyId(), second.getAccessKeyId());
        verify(fixture.iam).unregisterSession(ACCOUNT, first.getAccessKeyId());
        when(fixture.iam.findInstanceProfile(ACCOUNT, "profile")).thenReturn(Optional.empty());
        assertTrue(fixture.credentials.get(fixture.instance, "worker", NOW.plusSeconds(2)).isEmpty());
        verify(fixture.iam).unregisterSession(ACCOUNT, second.getAccessKeyId());
    }

    @Test
    void wrongRoleMissingProfileAndMismatchedProfileArnDoNotIssueCredentials() {
        Fixture fixture = fixture();
        assertTrue(fixture.credentials.get(fixture.instance, "other", NOW).isEmpty());
        fixture.instance.setIamInstanceProfileArn(PROFILE.replace("path/", "other/"));
        assertTrue(fixture.credentials.get(fixture.instance, "worker", NOW).isEmpty());
        fixture.instance.setIamInstanceProfileArn(null);
        assertTrue(fixture.credentials.get(fixture.instance, "worker", NOW).isEmpty());
        verify(fixture.iam, never()).registerEc2InstanceSession(any());
    }

    @Test
    void concurrentRequestsShareOneSessionAndInstancesRemainIndependent() throws Exception {
        Fixture fixture = fixture();
        try (ExecutorService executor = Executors.newFixedThreadPool(8)) {
            List<Callable<SessionCredential>> requests = IntStream.range(0, 32)
                    .mapToObj(index -> (Callable<SessionCredential>) () -> fixture.get(NOW)).toList();
            List<Future<SessionCredential>> results = executor.invokeAll(requests);
            SessionCredential first = results.getFirst().get();
            for (Future<SessionCredential> result : results) {
                assertSame(first, result.get());
            }
            verify(fixture.iam, times(1)).registerEc2InstanceSession(any());
            Instance other = new Instance();
            other.setInstanceId("i-second");
            other.setIamInstanceProfileArn(PROFILE);
            fixture.credentials.register(other);
            SessionCredential second = fixture.credentials.get(other, "worker", NOW).orElseThrow();
            assertNotEquals(first.getAccessKeyId(), second.getAccessKeyId());
            fixture.credentials.unregister(fixture.instance);
            assertSame(second, fixture.credentials.get(other, "worker", NOW).orElseThrow());
            fixture.credentials.clear();
            verify(fixture.iam).unregisterSession(ACCOUNT, second.getAccessKeyId());
        }
    }

    private static Fixture fixture() {
        IamService iam = mock(IamService.class);
        InstanceProfile profile = new InstanceProfile();
        profile.setArn(PROFILE);
        profile.setRoleNames(List.of("worker"));
        IamRole role = new IamRole("AROA-first", "worker", "/nodes/",
                "arn:aws:iam::123456789012:role/nodes/worker", "{}");
        when(iam.findInstanceProfile(ACCOUNT, "profile")).thenReturn(Optional.of(profile));
        when(iam.findRole(ACCOUNT, "worker")).thenReturn(Optional.of(role));
        Instance instance = new Instance();
        instance.setInstanceId("i-first");
        instance.setIamInstanceProfileArn(PROFILE);
        Ec2InstanceCredentials credentials = new Ec2InstanceCredentials(iam);
        credentials.register(instance);
        return new Fixture(iam, role, instance, credentials);
    }

    private record Fixture(IamService iam, IamRole role, Instance instance, Ec2InstanceCredentials credentials) {
        SessionCredential get(Instant now) {
            return credentials.get(instance, "worker", now).orElseThrow();
        }
    }
}
