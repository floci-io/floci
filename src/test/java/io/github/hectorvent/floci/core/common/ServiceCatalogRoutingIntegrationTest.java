package io.github.hectorvent.floci.core.common;

import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

@QuarkusTest
class ServiceCatalogRoutingIntegrationTest {

    @Inject
    ResolvedServiceCatalog catalog;

    @Test
    void targetResolutionExtractsMatchingPrefixAndAction() {
        ServiceCatalog.TargetMatch match = catalog.matchTarget("AWSEvents.PutEvents").orElseThrow();

        assertEquals("events", match.descriptor().externalKey());
        assertEquals("AWSEvents.", match.prefix());
        assertEquals("PutEvents", match.action());
    }

    @Test
    void dynamodbStreamsTargetUsesStreamsPrefix() {
        ServiceCatalog.TargetMatch match = catalog.matchTarget("DynamoDBStreams_20120810.DescribeStream").orElseThrow();

        assertEquals("dynamodb", match.descriptor().externalKey());
        assertEquals("DynamoDBStreams_20120810.", match.prefix());
        assertEquals("DescribeStream", match.action());
    }

    @Test
    void cborSdkServiceIdsResolveThroughCatalog() {
        assertEquals("states", catalog.byCborSdkServiceId("SFN").orElseThrow().externalKey());
        assertEquals("monitoring", catalog.byCborSdkServiceId("GraniteServiceVersion20100801").orElseThrow().externalKey());
    }

    @Test
    void queryProtocolAliasesAreDeclaredOnDescriptors() {
        assertTrue(catalog.byCredentialScope("sesv2").orElseThrow().supportsProtocol(ServiceProtocol.QUERY));
        assertTrue(catalog.byCredentialScope("cognito-idp").orElseThrow().supportsProtocol(ServiceProtocol.QUERY));
    }

    @Test
    void unknownTargetsRemainUnresolved() {
        assertTrue(catalog.matchTarget("UnknownService.DoThing").isEmpty());
    }
}
