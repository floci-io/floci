package io.floci.aws.core.common;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

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

        assertEquals("iam", resolver.resolve(authorization("lambda"), "CreateUser", null));
    }

    @Test
    void queryCapableSignedScopeKeepsControllerDispatch() {
        when(catalog.byCredentialScope("sqs")).thenReturn(Optional.of(
                descriptor("sqs", ServiceProtocol.QUERY)));

        assertEquals("sqs", resolver.resolve(authorization("sqs"), "CreateUser", null));
    }

    @Test
    void matchingQueryScopeResolvesNormally() {
        when(catalog.byCredentialScope("iam")).thenReturn(Optional.of(
                descriptor("iam", ServiceProtocol.QUERY)));

        assertEquals("iam", resolver.resolve(authorization("iam"), "CreateUser", null));
    }

    @Test
    void missingAuthorizationKeepsActionFallback() {
        assertEquals("sqs", resolver.resolve(null, "UnknownQueryAction", null));
    }

    /**
     * A name two services define goes to the one whose {@code Version} the request declares. With
     * no version, or one that does not settle it, the routing order decides as it always has.
     */
    @ParameterizedTest
    @CsvSource(nullValues = "none", value = {
            "CreateUser, 2015-02-02, elasticache",
            "CreateUser, 2010-05-08, iam",
            "CreateUser, none, iam",
            "AddPermission, 2012-11-05, sqs",
            "AddPermission, 2010-03-31, sns",
            "AddPermission, none, sns",
            "ListTagsForResource, 2014-10-31, rds",
            "ListTagsForResource, none, sns",
            "DeleteTags, 2012-12-01, redshift",
            "DeleteTags, 2011-01-01, autoscaling",
            "DeleteTags, none, ec2",
            "DescribeTags, 2012-06-01, elasticloadbalancing",
            "GetTemplate, 2010-12-01, email",
            "GetTemplate, none, cloudformation",
            "DescribeEnvironments, 2010-12-01, elasticbeanstalk",
    })
    void versionPicksAmongTheServicesThatShareAName(String action, String version, String expected) {
        assertEquals(expected, resolver.resolve(null, action, version));
    }

    @Test
    void versionOfAServiceWithoutTheActionLeavesTheOrderToDecide() {
        assertEquals("sns", resolver.resolve(null, "Publish", "2010-05-08"));
    }

    @Test
    void unrecognisedVersionLeavesTheOrderToDecide() {
        assertEquals("iam", resolver.resolve(null, "CreateUser", "2099-01-01"));
    }

    @Test
    void queryCapableSignedScopeOutranksVersion() {
        when(catalog.byCredentialScope("iam")).thenReturn(Optional.of(
                descriptor("iam", ServiceProtocol.QUERY)));

        assertEquals("iam", resolver.resolve(authorization("iam"), "CreateUser", "2015-02-02"));
    }

    @Test
    void unsupportedSignedScopeFallsBackToVersion() {
        when(catalog.byCredentialScope("lambda")).thenReturn(Optional.of(
                descriptor("lambda", ServiceProtocol.REST_JSON)));

        assertEquals("elasticache", resolver.resolve(authorization("lambda"), "CreateUser", "2015-02-02"));
    }

    private static String authorization(String scope) {
        return "AWS4-HMAC-SHA256 Credential=AKIA/20260925/us-east-1/" + scope
                + "/aws4_request, SignedHeaders=host, Signature=abc";
    }

    private static ServiceDescriptor descriptor(String service, ServiceProtocol protocol) {
        return new ServiceDescriptor(service, service, true, true, service, "memory", 0L,
                null, protocol, Set.of(protocol), Set.of(), Set.of(service), Set.of(), Set.of(), Set.of());
    }
}
