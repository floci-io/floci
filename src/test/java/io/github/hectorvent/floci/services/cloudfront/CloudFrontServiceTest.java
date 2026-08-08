package io.github.hectorvent.floci.services.cloudfront;

import io.github.hectorvent.floci.config.EmulatorConfig;
import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.core.storage.InMemoryStorage;
import io.github.hectorvent.floci.core.storage.StorageFactory;
import io.github.hectorvent.floci.services.cloudfront.model.CloudFrontOriginAccessIdentity;
import io.github.hectorvent.floci.services.cloudfront.model.Distribution;
import io.github.hectorvent.floci.services.cloudfront.model.DistributionConfig;
import io.github.hectorvent.floci.services.cloudfront.model.Origin;
import io.github.hectorvent.floci.services.cloudfront.model.OriginAccessControl;
import io.github.hectorvent.floci.services.cloudfront.model.StreamingDistribution;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.when;

class CloudFrontServiceTest {

    private static final String ACCOUNT = "000000000000";

    private CloudFrontService serviceWithDomainSuffix(String domainSuffix) {
        StorageFactory storageFactory = Mockito.mock(StorageFactory.class);
        when(storageFactory.create(Mockito.anyString(), Mockito.anyString(), Mockito.any()))
                .thenAnswer(invocation -> new InMemoryStorage<>());

        EmulatorConfig config = Mockito.mock(EmulatorConfig.class);
        var servicesConfig = Mockito.mock(EmulatorConfig.ServicesConfig.class);
        var cloudFrontConfig = Mockito.mock(EmulatorConfig.CloudFrontServiceConfig.class);

        when(config.defaultAccountId()).thenReturn(ACCOUNT);
        when(config.services()).thenReturn(servicesConfig);
        when(servicesConfig.cloudfront()).thenReturn(cloudFrontConfig);
        when(cloudFrontConfig.domainSuffix()).thenReturn(domainSuffix);

        return new CloudFrontService(storageFactory, config);
    }

    @Test
    void createDistributionUsesDefaultDomainSuffix() {
        CloudFrontService service = serviceWithDomainSuffix("cloudfront.net");

        Distribution dist = service.createDistribution(new Distribution(), Map.of());

        assertTrue(dist.getDomainName().endsWith(".cloudfront.net"),
                "Expected default suffix, got: " + dist.getDomainName());
    }

    @Test
    void createDistributionHonorsConfiguredDomainSuffix() {
        CloudFrontService service = serviceWithDomainSuffix("cloudfront.local");

        Distribution dist = service.createDistribution(new Distribution(), Map.of());

        assertTrue(dist.getDomainName().endsWith(".cloudfront.local"),
                "Expected configured suffix, got: " + dist.getDomainName());
    }

    @Test
    void createStreamingDistributionHonorsConfiguredDomainSuffix() {
        CloudFrontService service = serviceWithDomainSuffix("cloudfront.local");

        StreamingDistribution sd = service.createStreamingDistribution(new StreamingDistribution());

        assertTrue(sd.getDomainName().endsWith(".cloudfront.local"),
                "Expected configured suffix, got: " + sd.getDomainName());
    }

    @Test
    void findByHostMatchesDomainNameAndAliasIgnoringPort() {
        CloudFrontService service = serviceWithDomainSuffix("cloudfront.net");

        DistributionConfig cfg = new DistributionConfig();
        cfg.setEnabled(true);
        cfg.setAliases(List.of("viewer.example.test"));
        Distribution dist = new Distribution();
        dist.setConfig(cfg);
        dist = service.createDistribution(dist, Map.of());

        // Matches the assigned CloudFront domain name, with or without a port.
        assertEquals(dist.getId(), service.findByHost(dist.getDomainName()).getId());
        assertEquals(dist.getId(), service.findByHost(dist.getDomainName() + ":4566").getId());
        // Matches a configured alias, case-insensitively and ignoring the port.
        assertEquals(dist.getId(), service.findByHost("viewer.example.test").getId());
        assertEquals(dist.getId(), service.findByHost("VIEWER.EXAMPLE.TEST:8443").getId());
        // No match for an unrelated host.
        assertNull(service.findByHost("unrelated.example.test"));
        assertNull(service.findByHost(null));
    }

    @Test
    void findByHostRetainsOwnershipForDisabledDistribution() {
        CloudFrontService service = serviceWithDomainSuffix("cloudfront.net");

        DistributionConfig cfg = new DistributionConfig();
        cfg.setEnabled(false);
        cfg.setAliases(List.of("disabled.example.test"));
        Distribution dist = new Distribution();
        dist.setConfig(cfg);
        dist = service.createDistribution(dist, Map.of());

        assertEquals(dist.getId(), service.findByHost(dist.getDomainName()).getId());
        assertEquals(dist.getId(), service.findByHost("disabled.example.test").getId());
    }

    @Test
    void rejectsDuplicateAliasOnCreateAndUpdate() {
        CloudFrontService service = serviceWithDomainSuffix("cloudfront.net");
        Distribution owner = service.createDistribution(
                distribution(true, List.of("shared.example.test")), Map.of());

        AwsException createError = assertThrows(AwsException.class, () -> service.createDistribution(
                distribution(true, List.of("SHARED.EXAMPLE.TEST")), Map.of()));
        assertEquals("CNAMEAlreadyExists", createError.getErrorCode());

        Distribution other = service.createDistribution(
                distribution(true, List.of("other.example.test")), Map.of());
        AwsException updateError = assertThrows(AwsException.class, () -> service.updateDistribution(
                other.getId(), other.getEtag(), distribution(true, List.of("shared.example.test"))));
        assertEquals("CNAMEAlreadyExists", updateError.getErrorCode());
        assertEquals(owner.getId(), service.findByHost("shared.example.test").getId());
        assertEquals(other.getId(), service.findByHost("other.example.test").getId());
    }

    @Test
    void associateAliasTransfersOwnershipAtomically() {
        CloudFrontService service = serviceWithDomainSuffix("cloudfront.net");
        Distribution owner = service.createDistribution(
                distribution(false, List.of("move.example.test")), Map.of());
        Distribution target = service.createDistribution(distribution(true, List.of()), Map.of());

        service.associateAlias(target.getId(), "MOVE.EXAMPLE.TEST");

        assertTrue(service.getDistribution(owner.getId()).getConfig().getAliases().isEmpty());
        assertEquals(target.getId(), service.findByHost("move.example.test").getId());
    }

    @Test
    void wildcardAliasesMatchSubdomainsAndPreferMoreSpecificNames() {
        CloudFrontService service = serviceWithDomainSuffix("cloudfront.net");
        Distribution wildcard = service.createDistribution(
                distribution(true, List.of("*.example.test")), Map.of());
        Distribution specificWildcard = service.createDistribution(
                distribution(true, List.of("*.shop.example.test")), Map.of());
        Distribution exact = service.createDistribution(
                distribution(true, List.of("marketing.example.test")), Map.of());

        assertEquals(wildcard.getId(), service.findByHost("www.example.test").getId());
        assertEquals(specificWildcard.getId(), service.findByHost("item.shop.example.test").getId());
        assertEquals(exact.getId(), service.findByHost("marketing.example.test").getId());
        // A wildcard replaces exactly one label, so it covers neither a higher nor a lower level:
        // "*.example.test" matches www.example.test but not a.b.example.test, and not the apex.
        // Per AWS: "you can't add alternate domain names that are at levels higher or lower than
        // the wildcard. For example, you can't add ... example.com or marketing.product.example.com."
        assertNull(service.findByHost("a.b.example.test"));
        assertNull(service.findByHost("example.test"));
        // ...and the deeper wildcard is likewise single-level.
        assertNull(service.findByHost("a.b.shop.example.test"));
    }

    @Test
    void validatesRequiredOriginAccessControlConfiguration() {
        CloudFrontService service = serviceWithDomainSuffix("cloudfront.net");

        OriginAccessControl missingName = originAccessControl();
        missingName.setName(null);
        assertInvalidOriginAccessControl(service, missingName);

        OriginAccessControl longName = originAccessControl();
        longName.setName("x".repeat(65));
        assertInvalidOriginAccessControl(service, longName);

        OriginAccessControl invalidProtocol = originAccessControl();
        invalidProtocol.setSigningProtocol("sigv2");
        assertInvalidOriginAccessControl(service, invalidProtocol);

        OriginAccessControl invalidBehavior = originAccessControl();
        invalidBehavior.setSigningBehavior("sometimes");
        assertInvalidOriginAccessControl(service, invalidBehavior);

        OriginAccessControl invalidOriginType = originAccessControl();
        invalidOriginType.setOriginAccessControlOriginType("custom");
        assertInvalidOriginAccessControl(service, invalidOriginType);
    }

    @Test
    void validatesOriginAccessControlConfigurationOnUpdate() {
        CloudFrontService service = serviceWithDomainSuffix("cloudfront.net");
        OriginAccessControl existing =
                service.createOriginAccessControl(originAccessControl());
        OriginAccessControl invalid = originAccessControl();
        invalid.setSigningBehavior(null);

        AwsException error = assertThrows(AwsException.class,
                () -> service.updateOriginAccessControl(
                        existing.getId(), existing.getEtag(), invalid));

        assertEquals("InvalidArgument", error.getErrorCode());
        assertEquals("test-oac", service.getOriginAccessControl(existing.getId()).getName());
    }

    @Test
    void rejectsDeletingOriginAccessControlUsedByDistribution() {
        CloudFrontService service = serviceWithDomainSuffix("cloudfront.net");
        OriginAccessControl oac = service.createOriginAccessControl(originAccessControl());
        Origin origin = new Origin();
        origin.setOriginAccessControlId(oac.getId());
        Distribution distribution = distribution(false, List.of());
        distribution.getConfig().setOrigins(List.of(origin));
        service.createDistribution(distribution, Map.of());

        AwsException error = assertThrows(AwsException.class,
                () -> service.deleteOriginAccessControl(oac.getId(), oac.getEtag()));

        assertEquals("OriginAccessControlInUse", error.getErrorCode());
        assertEquals(409, error.getHttpStatus());
        assertEquals(oac.getId(), service.getOriginAccessControl(oac.getId()).getId());
    }

    @Test
    void rejectsDeletingOriginAccessIdentityUsedByDistribution() {
        CloudFrontService service = serviceWithDomainSuffix("cloudfront.net");
        CloudFrontOriginAccessIdentity oai = originAccessIdentity(service);
        Origin origin = new Origin();
        origin.setS3OriginConfig(Map.of(
                "OriginAccessIdentity",
                "origin-access-identity/cloudfront/" + oai.getId()));
        Distribution distribution = distribution(false, List.of());
        distribution.getConfig().setOrigins(List.of(origin));
        service.createDistribution(distribution, Map.of());

        AwsException error = assertThrows(AwsException.class,
                () -> service.deleteCloudFrontOriginAccessIdentity(
                        oai.getId(), oai.getEtag()));

        assertEquals("CloudFrontOriginAccessIdentityInUse", error.getErrorCode());
        assertEquals(409, error.getHttpStatus());
        assertEquals(oai.getId(),
                service.getCloudFrontOriginAccessIdentity(oai.getId()).getId());
    }

    @Test
    void rejectsDeletingOriginAccessIdentityUsedByStreamingDistribution() {
        CloudFrontService service = serviceWithDomainSuffix("cloudfront.net");
        CloudFrontOriginAccessIdentity oai = originAccessIdentity(service);
        StreamingDistribution distribution = new StreamingDistribution();
        distribution.setS3OriginAccessIdentity(
                "/origin-access-identity/cloudfront/" + oai.getId());
        service.createStreamingDistribution(distribution);

        AwsException error = assertThrows(AwsException.class,
                () -> service.deleteCloudFrontOriginAccessIdentity(
                        oai.getId(), oai.getEtag()));

        assertEquals("CloudFrontOriginAccessIdentityInUse", error.getErrorCode());
        assertEquals(409, error.getHttpStatus());
    }

    private static Distribution distribution(boolean enabled, List<String> aliases) {
        DistributionConfig config = new DistributionConfig();
        config.setEnabled(enabled);
        config.setAliases(aliases);
        Distribution distribution = new Distribution();
        distribution.setConfig(config);
        return distribution;
    }

    private static OriginAccessControl originAccessControl() {
        OriginAccessControl oac = new OriginAccessControl();
        oac.setName("test-oac");
        oac.setSigningProtocol("sigv4");
        oac.setSigningBehavior("always");
        oac.setOriginAccessControlOriginType("s3");
        return oac;
    }

    private static CloudFrontOriginAccessIdentity originAccessIdentity(
            CloudFrontService service) {
        CloudFrontOriginAccessIdentity oai = new CloudFrontOriginAccessIdentity();
        oai.setCallerReference("test-reference");
        return service.createCloudFrontOriginAccessIdentity(oai);
    }

    private static void assertInvalidOriginAccessControl(
            CloudFrontService service, OriginAccessControl oac) {
        AwsException error = assertThrows(AwsException.class,
                () -> service.createOriginAccessControl(oac));
        assertEquals("InvalidArgument", error.getErrorCode());
        assertEquals(400, error.getHttpStatus());
    }
}
