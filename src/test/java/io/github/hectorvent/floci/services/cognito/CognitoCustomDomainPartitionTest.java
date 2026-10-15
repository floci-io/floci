package io.github.hectorvent.floci.services.cognito;

import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.core.common.AwsRegions;
import io.github.hectorvent.floci.core.common.RegionResolver;
import io.github.hectorvent.floci.core.storage.InMemoryStorage;
import io.github.hectorvent.floci.services.acm.AcmService;
import io.github.hectorvent.floci.services.cognito.model.UserPoolDomain;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.Map;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;

/**
 * User pool custom domains are not available in AWS GovCloud (US) or in the AWS European Sovereign
 * Cloud, so one cannot be created or configured there, while a prefix domain works in every
 * partition that has user pools.
 */
class CognitoCustomDomainPartitionTest {

    private static final String ACCOUNT = "000000000000";
    private static final String CUSTOM_DOMAIN = "auth.example.com";
    private static final Map<String, Object> CUSTOM =
            Map.of("CertificateArn", "arn:aws:acm:us-east-1:" + ACCOUNT + ":certificate/abc");

    private final AcmService acmService = mock(AcmService.class);

    private CognitoService serviceIn(String region) {
        CognitoCertificateStubs.everyCertificateIsIssued(acmService);
        return new CognitoService(
                new InMemoryStorage<>(), new InMemoryStorage<>(), new InMemoryStorage<>(), new InMemoryStorage<>(),
                new InMemoryStorage<>(), new InMemoryStorage<>(), new InMemoryStorage<>(), new InMemoryStorage<>(),
                "http://localhost:4566", new RegionResolver(region, ACCOUNT), null, acmService, null, null, null);
    }

    private static String createPool(CognitoService service, String region) {
        return service.createUserPool(Map.of("PoolName", "domain-pool"), region).getId();
    }

    private static void assertRefused(AwsException error, String region) {
        assertEquals("InvalidParameterException", error.getErrorCode());
        assertEquals(400, error.getHttpStatus());
        assertThat(error.getMessage(),
                containsString("not available in partition " + AwsRegions.partitionFor(region)));
    }

    @ParameterizedTest
    @ValueSource(strings = {"us-gov-west-1", "us-gov-east-1", "eusc-de-east-1"})
    void aCustomDomainIsRefusedInGovCloudAndEusc(String region) {
        CognitoService service = serviceIn(region);
        String poolId = createPool(service, region);

        AwsException error = assertThrows(AwsException.class,
                () -> service.createUserPoolDomain(CUSTOM_DOMAIN, poolId, CUSTOM, null));

        assertRefused(error, region);
        assertTrue(service.findCustomDomain(CUSTOM_DOMAIN).isEmpty());
        // Refused before the certificate is looked at.
        verifyNoInteractions(acmService);
    }

    @ParameterizedTest
    @ValueSource(strings = {"us-gov-west-1", "us-gov-east-1", "eusc-de-east-1"})
    void aPrefixDomainCannotBeGivenACustomDomainConfigInGovCloudOrEusc(String region) {
        CognitoService service = serviceIn(region);
        String poolId = createPool(service, region);
        service.createUserPoolDomain("only-prefix", poolId, null, null);

        AwsException error = assertThrows(AwsException.class,
                () -> service.updateUserPoolDomain("only-prefix", poolId, CUSTOM, null));

        assertRefused(error, region);
        verifyNoInteractions(acmService);
    }

    @ParameterizedTest
    @ValueSource(strings = {"us-east-1", "us-gov-west-1", "us-gov-east-1", "eusc-de-east-1"})
    void aPrefixDomainIsAcceptedAndUpdatedInEveryPartition(String region) {
        CognitoService service = serviceIn(region);
        String poolId = createPool(service, region);

        UserPoolDomain created = service.createUserPoolDomain("my-prefix", poolId, null, 1);
        assertFalse(created.isCustomDomain());

        UserPoolDomain updated = service.updateUserPoolDomain("my-prefix", poolId, null, 2);
        assertEquals(2, updated.getManagedLoginVersion());
    }

    @Test
    void aCustomDomainIsAcceptedInTheCommercialPartition() {
        CognitoService service = serviceIn("us-east-1");
        String poolId = createPool(service, "us-east-1");

        UserPoolDomain created = service.createUserPoolDomain(CUSTOM_DOMAIN, poolId, CUSTOM, null);
        assertTrue(created.isCustomDomain());

        Map<String, Object> replacement =
                Map.of("CertificateArn", "arn:aws:acm:us-east-1:" + ACCOUNT + ":certificate/def");
        UserPoolDomain updated = service.updateUserPoolDomain(CUSTOM_DOMAIN, poolId, replacement, null);
        assertEquals(replacement.get("CertificateArn"), updated.getCertificateArn());
    }
}
