package io.github.hectorvent.floci.core.common;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class AwsQueryServiceResolverTest {

    private ResolvedServiceCatalog catalog;
    private AwsQueryServiceResolver resolver;

    @BeforeEach
    void setUp() {
        catalog = mock(ResolvedServiceCatalog.class);
        resolver = new AwsQueryServiceResolver(catalog);
    }

    @Test
    void unsupportedSignedScopeFallsBackToActionDispatch() {
        when(catalog.byCredentialScope("lambda")).thenReturn(Optional.of(
                descriptor("lambda", ServiceProtocol.REST_JSON)));

        assertEquals("iam", resolver.resolve(authorization("lambda"), "CreateUser"));
    }

    @Test
    void queryCapableSignedScopeKeepsControllerDispatch() {
        when(catalog.byCredentialScope("sqs")).thenReturn(Optional.of(
                descriptor("sqs", ServiceProtocol.QUERY)));

        assertEquals("sqs", resolver.resolve(authorization("sqs"), "CreateUser"));
    }

    @Test
    void matchingQueryScopeResolvesNormally() {
        when(catalog.byCredentialScope("iam")).thenReturn(Optional.of(
                descriptor("iam", ServiceProtocol.QUERY)));

        assertEquals("iam", resolver.resolve(authorization("iam"), "CreateUser"));
    }

    @Test
    void missingAuthorizationKeepsActionFallback() {
        assertEquals("sqs", resolver.resolve(null, "UnknownQueryAction"));
    }

    private static String authorization(String scope) {
        return "AWS4-HMAC-SHA256 Credential=AKIA/20260925/us-east-1/" + scope
                + "/aws4_request, SignedHeaders=host, Signature=abc";
    }

    private static ServiceDescriptor descriptor(String service, ServiceProtocol protocol) {
        return new ServiceDescriptor(service, service, true, true, service, "memory", 0L,
                null, protocol, Set.of(protocol), Set.of(), Set.of(service), Set.of(), Set.of());
    }
}
