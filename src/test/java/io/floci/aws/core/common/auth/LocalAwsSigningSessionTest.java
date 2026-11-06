package io.floci.aws.core.common.auth;

import io.floci.aws.core.common.Digests;
import io.floci.aws.services.iam.IamService;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.net.URI;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

class LocalAwsSigningSessionTest {

    private static final String ACCOUNT = "111111111111";
    private static final String ROLE = "arn:aws:iam::" + ACCOUNT + ":role/test";
    private final IamService iam = mock(IamService.class);

    @Test
    void queryAndMultipleHeaderValuesUseTheNativeCanonicalForm() throws Exception {
        ArgumentCaptor<String> key = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<String> secret = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<String> token = ArgumentCaptor.forClass(String.class);
        try (LocalAwsSigningSession session = LocalAwsSigningSession.forAccount(iam, ACCOUNT, ROLE)) {
            verify(iam).registerSessionForAccount(eq(ACCOUNT), key.capture(), secret.capture(), token.capture(),
                    eq(ROLE), any(Instant.class), isNull());
            URI uri = URI.create("http://localhost:4566/schedules/space%20name?groupName=a%20b&clientToken=c%2Bd");
            byte[] payload = new byte[0];
            Map<String, List<String>> headers = session.sign("GET", uri, payload, "us-west-2", "scheduler",
                    Map.of("x-values", List.of(" a ", " b   c ")));
            SigV4AuthorizationHeader auth = SigV4AuthorizationHeader.parse(headers.get("authorization").getFirst());
            CredentialScope scope = CredentialScope.parse(auth.credential());
            assertEquals(key.getValue(), scope.accessKeyId());
            assertEquals("us-west-2", scope.region());
            assertEquals("scheduler", scope.service());
            String stamp = headers.get("x-amz-date").getFirst();
            String canonicalHeaders = "host:localhost:4566\nx-amz-date:" + stamp
                    + "\nx-amz-security-token:" + token.getValue() + "\nx-values:a,b c\n";
            assertTrue(SigV4Canonicalization.signatureMatches("GET", "/schedules/space%20name",
                    "clientToken=c%2Bd&groupName=a%20b", canonicalHeaders, auth.signedHeaders(),
                    Digests.sha256Hex(payload), stamp, scope, secret.getValue(), auth.signature()));
        }
        verify(iam).unregisterSession(ACCOUNT, key.getValue());
    }

    @Test
    void assumedRoleRegistrationKeepsItsIdentityAndLifetime() {
        ArgumentCaptor<String> key = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<Instant> expiration = ArgumentCaptor.forClass(Instant.class);
        Instant before = Instant.now();
        try (LocalAwsSigningSession session = LocalAwsSigningSession.assumedRole(iam, ACCOUNT, ROLE,
                "service-session", "AROAID:service-session")) {
            verify(iam).registerSession(key.capture(), anyString(), anyString(), eq(ROLE), expiration.capture(),
                    isNull(), eq(ACCOUNT), eq("service-session"), eq("AROAID:service-session"));
            assertTrue(key.getValue().matches("ASIA[A-Z0-9]{16}"));
            assertTrue(!expiration.getValue().isBefore(before.plusSeconds(120))
                    && !expiration.getValue().isAfter(Instant.now().plusSeconds(120)));
        }
        verify(iam).unregisterSession(ACCOUNT, key.getValue());
    }

    @Test
    void registrationFailureRevokesTheKeyAndPreservesTheOriginalFailure() {
        RuntimeException registration = new IllegalStateException("registration failed");
        RuntimeException cleanup = new IllegalStateException("cleanup failed");
        doThrow(registration).when(iam).registerSessionForAccount(eq(ACCOUNT), anyString(), anyString(), anyString(),
                eq(ROLE), any(Instant.class), isNull());
        doThrow(cleanup).when(iam).unregisterSession(eq(ACCOUNT), anyString());
        assertSame(registration, assertThrows(IllegalStateException.class,
                () -> LocalAwsSigningSession.forAccount(iam, ACCOUNT, ROLE)));
        assertEquals(List.of(cleanup), List.of(registration.getSuppressed()));
        ArgumentCaptor<String> key = ArgumentCaptor.forClass(String.class);
        verify(iam).registerSessionForAccount(eq(ACCOUNT), key.capture(), anyString(), anyString(), eq(ROLE), any(), isNull());
        verify(iam).unregisterSession(ACCOUNT, key.getValue());
    }
}
