package io.github.hectorvent.floci.services.cognito;

import io.github.hectorvent.floci.config.TlsCertificateManager;
import io.github.hectorvent.floci.core.common.RegionResolver;
import io.github.hectorvent.floci.core.storage.InMemoryStorage;
import io.github.hectorvent.floci.services.acm.AcmService;
import io.github.hectorvent.floci.services.cognito.model.CognitoUser;
import io.github.hectorvent.floci.services.cognito.model.UserPool;
import io.github.hectorvent.floci.services.cognito.verification.CognitoMessageDispatcher;
import io.github.hectorvent.floci.services.cognito.verification.VerificationCode;
import io.github.hectorvent.floci.services.cognito.verification.VerificationCodeService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;
import java.util.Map;

import static io.github.hectorvent.floci.testing.InlineMocks.inlineMock;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class CognitoAdminAttributeVerificationTest {

    private CognitoService service;
    private VerificationCodeService codes;
    private CognitoMessageDispatcher dispatcher;
    private UserPool pool;

    @BeforeEach
    void setUp() {
        codes = inlineMock(VerificationCodeService.class);
        dispatcher = inlineMock(CognitoMessageDispatcher.class);
        when(codes.issueTracked(anyString(), anyString(), any(), any(Duration.class)))
                .thenReturn(new VerificationCodeService.IssuedCode("123456", "test-hash"));
        service = new CognitoService(
                new InMemoryStorage<>(), new InMemoryStorage<>(), new InMemoryStorage<>(),
                new InMemoryStorage<>(), new InMemoryStorage<>(), new InMemoryStorage<>(),
                new InMemoryStorage<>(), new InMemoryStorage<>(),
                "http://localhost:4566", new RegionResolver("us-east-1", "000000000000"),
                null, mock(AcmService.class), codes, dispatcher, mock(TlsCertificateManager.class));
        pool = service.createUserPool(Map.of("PoolName", "AdminAttributeVerification",
                "AutoVerifiedAttributes", List.of("email")), "us-east-1");
        service.adminCreateUser(pool.getId(), "alice", Map.of(
                "email", "old@example.com", "email_verified", "true"), "TempPass1!");
        clearInvocations(codes, dispatcher);
    }

    @Test
    void changingEmailClearsItsVerifiedFlag() {
        service.adminUpdateUserAttributes(pool.getId(), "alice", Map.of("email", "new@example.com"));

        CognitoUser updated = service.adminGetUser(pool.getId(), "alice");
        assertEquals("new@example.com", updated.getAttributes().get("email"));
        assertEquals("false", updated.getAttributes().get("email_verified"));
    }

    @Test
    void changingEmailSendsItsVerificationCode() {
        service.adminUpdateUserAttributes(pool.getId(), "alice", Map.of("email", "new@example.com"));

        verify(codes).issueTracked(eq(pool.getId()), eq("alice"),
                eq(VerificationCode.Purpose.EMAIL_ATTRIBUTE_VERIFICATION), any(Duration.class));
        verify(dispatcher).dispatch(eq(pool), any(), eq(VerificationCode.Purpose.EMAIL_ATTRIBUTE_VERIFICATION),
                eq("123456"), eq(List.of("EMAIL")), any());
    }

    @Test
    void explicitlyVerifiedEmailSkipsTheVerificationMessage() {
        service.adminUpdateUserAttributes(pool.getId(), "alice", Map.of(
                "email", "trusted@example.com", "email_verified", "true"));

        CognitoUser updated = service.adminGetUser(pool.getId(), "alice");
        assertEquals("trusted@example.com", updated.getAttributes().get("email"));
        assertEquals("true", updated.getAttributes().get("email_verified"));
        verifyNoInteractions(dispatcher);
    }

    @Test
    void requiringVerificationBeforeUpdatePreservesCurrentEmailUntilVerified() {
        UserPool pendingPool = service.createUserPool(Map.of(
                "PoolName", "AdminPendingAttributeVerification",
                "AutoVerifiedAttributes", List.of("email"),
                "UserAttributeUpdateSettings", Map.of(
                        "AttributesRequireVerificationBeforeUpdate", List.of("email"))), "us-east-1");
        service.adminCreateUser(pendingPool.getId(), "bob", Map.of(
                "email", "old@example.com", "email_verified", "true"), "TempPass1!");
        clearInvocations(codes, dispatcher);

        service.adminUpdateUserAttributes(pendingPool.getId(), "bob", Map.of("email", "new@example.com"));

        CognitoUser updated = service.adminGetUser(pendingPool.getId(), "bob");
        assertEquals("old@example.com", updated.getAttributes().get("email"));
        assertEquals("true", updated.getAttributes().get("email_verified"));
        assertEquals("new@example.com", updated.getPendingAttributes().get("email"));
        verify(codes).issueTracked(eq(pendingPool.getId()), eq("bob"),
                eq(VerificationCode.Purpose.EMAIL_ATTRIBUTE_VERIFICATION), any(Duration.class));
        verify(dispatcher).dispatch(eq(pendingPool), any(),
                eq(VerificationCode.Purpose.EMAIL_ATTRIBUTE_VERIFICATION), eq("123456"),
                eq(List.of("EMAIL")), any());
    }
}
