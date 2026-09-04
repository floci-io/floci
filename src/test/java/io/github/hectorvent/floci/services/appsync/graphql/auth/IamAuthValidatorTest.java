package io.github.hectorvent.floci.services.appsync.graphql.auth;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.hectorvent.floci.core.common.AccountResolver;
import io.github.hectorvent.floci.services.appsync.graphql.AppSyncTransportException;
import io.github.hectorvent.floci.services.iam.IamPolicyEvaluator;
import io.github.hectorvent.floci.services.iam.IamService;
import io.github.hectorvent.floci.services.iam.model.CallerContext;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class IamAuthValidatorTest {

    private static final String ALLOW = """
            {"Version":"2012-10-17","Statement":[{"Effect":"Allow","Action":"appsync:GraphQL","Resource":"*"}]}
            """;
    private static final String DENY = """
            {"Version":"2012-10-17","Statement":[{"Effect":"Deny","Action":"appsync:GraphQL","Resource":"*"}]}
            """;
    @Mock
    IamService iamService;

    private IamAuthValidator validator;
    private AuthRequestInfo info;

    @BeforeEach
    void setUp() {
        validator = new IamAuthValidator(
                new AccountResolver("000000000000"),
                iamService,
                new IamPolicyEvaluator(new ObjectMapper()));
        info = new AuthRequestInfo("{ hello }", null, Map.of(), List.of("10.0.0.1"),
                "req-1", "000000000000", "us-east-1", Map.of());
    }

    @Test
    void knownAllowBuildsIdentity() {
        when(iamService.resolveCallerContext("AKIAGOOD")).thenReturn(CallerContext.of(List.of(ALLOW)));
        when(iamService.resolveCallerArn("AKIAGOOD"))
                .thenReturn(Optional.of("arn:aws:iam::000000000000:user/alice"));

        Map<String, Object> identity = validator.validateRequest(
                "AWS4-HMAC-SHA256 Credential=AKIAGOOD/20260205/us-east-1/appsync/aws4_request",
                "api-1", info);

        assertEquals("AKIAGOOD", identity.get("user"));
        assertEquals("alice", identity.get("username"));
        assertInstanceOf(List.class, identity.get("sourceIp"));
        assertEquals(List.of("10.0.0.1"), identity.get("sourceIp"));
    }

    @Test
    void knownRequestDenyThrows401() {
        when(iamService.resolveCallerContext("AKIDDENY")).thenReturn(CallerContext.of(List.of(DENY)));

        AppSyncTransportException ex = assertThrows(AppSyncTransportException.class,
                () -> validator.validateRequest(
                        "AWS4-HMAC-SHA256 Credential=AKIDDENY/20260205/us-east-1/appsync/aws4_request",
                        "api-1", info));
        assertEquals(401, ex.getHttpStatus());
    }

    @Test
    void unknownTestKeyIsAllowed() {
        Map<String, Object> identity = validator.validateRequest(
                "AWS4-HMAC-SHA256 Credential=test/20260205/us-east-1/appsync/aws4_request",
                "api-1", info);
        assertEquals("test", identity.get("user"));
    }

    /**
     * Field-level IAM authorization itself now happens in the floci-app-sync sidecar's
     * {@code IamFieldAuthorizer} (issue #2917) — this only resolves the {@link CallerContext}
     * that gets forwarded to it, so the sidecar can evaluate per-field ARNs without a
     * callback into Floci.
     */
    @Test
    void resolveCallerContextForSidecarReturnsResolvedContextForRealCaller() {
        CallerContext expected = CallerContext.of(List.of(ALLOW));
        when(iamService.resolveCallerContext("AKIAGOOD")).thenReturn(expected);

        assertEquals(expected, validator.resolveCallerContextForSidecar("AKIAGOOD"));
    }

    @Test
    void resolveCallerContextForSidecarReturnsNullForEmulatorAllowSentinel() {
        assertEquals(null, validator.resolveCallerContextForSidecar("test"));
    }
}
