package io.github.hectorvent.floci.services.cloudfront;

import io.github.hectorvent.floci.config.EmulatorConfig;
import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.core.storage.InMemoryStorage;
import io.github.hectorvent.floci.core.storage.StorageFactory;
import io.github.hectorvent.floci.services.cloudfront.model.CacheBehavior;
import io.github.hectorvent.floci.services.cloudfront.model.DefaultCacheBehavior;
import io.github.hectorvent.floci.services.cloudfront.model.Distribution;
import io.github.hectorvent.floci.services.cloudfront.model.DistributionConfig;
import io.github.hectorvent.floci.services.cloudfront.model.ResponseHeadersPolicy;
import io.github.hectorvent.floci.services.cloudfront.model.StreamingDistribution;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
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
        cfg.setAliases(List.of("console.example.test"));
        Distribution dist = new Distribution();
        dist.setConfig(cfg);
        dist = service.createDistribution(dist, Map.of());

        // Matches the assigned CloudFront domain name, with or without a port.
        assertEquals(dist.getId(), service.findByHost(dist.getDomainName()).getId());
        assertEquals(dist.getId(), service.findByHost(dist.getDomainName() + ":4566").getId());
        // Matches a configured alias, case-insensitively and ignoring the port.
        assertEquals(dist.getId(), service.findByHost("console.example.test").getId());
        assertEquals(dist.getId(), service.findByHost("CONSOLE.EXAMPLE.TEST:8443").getId());
        // No match for an unrelated host.
        assertNull(service.findByHost("unrelated.example.test"));
        assertNull(service.findByHost(null));
    }

    @Test
    void exposesCanonicalManagedResponseHeadersPolicies() {
        CloudFrontService service = serviceWithDomainSuffix("cloudfront.net");

        ResponseHeadersPolicy simple = service.getResponseHeadersPolicy(
                CloudFrontService.MANAGED_SIMPLE_CORS_POLICY_ID);
        assertEquals("Managed-SimpleCORS", simple.getName());
        assertEquals("Allows all origins for simple CORS requests", simple.getComment());
        assertEquals(Instant.EPOCH, simple.getLastModifiedTime());
        assertEquals("E23ZP02F085DFQ", simple.getEtag());
        Map<String, String> simpleHeaders = policyHeaders(simple, false);
        assertEquals(Map.of("Access-Control-Allow-Origin", "*"), simpleHeaders);

        ResponseHeadersPolicy preflight = service.getResponseHeadersPolicy(
                CloudFrontService.MANAGED_CORS_PREFLIGHT_POLICY_ID);
        Map<String, String> preflightHeaders = policyHeaders(preflight, true);
        assertEquals("GET, HEAD, PUT, POST, PATCH, DELETE, OPTIONS",
                preflightHeaders.get("Access-Control-Allow-Methods"));
        assertEquals("*", preflightHeaders.get("Access-Control-Expose-Headers"));
        assertFalse(preflightHeaders.containsKey("Access-Control-Allow-Headers"));
        assertFalse(preflightHeaders.containsKey("Access-Control-Max-Age"));

        assertEquals(5, service.listResponseHeadersPolicies(null, 100, "managed").size());
        assertTrue(service.listResponseHeadersPolicies(null, 100, "custom").isEmpty());
        assertAws("InvalidArgument",
                () -> service.listResponseHeadersPolicies(null, 100, "unsupported"));
    }

    @Test
    void validatesResponseHeadersPolicyReferencesBeforeDistributionMutation() {
        CloudFrontService service = serviceWithDomainSuffix("cloudfront.net");
        Distribution invalidDefault = distributionWithPolicy("missing-default-policy");

        assertAws("NoSuchResponseHeadersPolicy",
                () -> service.createDistribution(invalidDefault, Map.of()));
        assertNull(invalidDefault.getId());
        assertTrue(service.listDistributions(null, 100).isEmpty());

        Distribution valid = distributionWithPolicy(CloudFrontService.MANAGED_SIMPLE_CORS_POLICY_ID);
        valid = service.createDistribution(valid, Map.of());
        Distribution invalidUpdate = distributionWithPolicy(null);
        CacheBehavior ordered = new CacheBehavior();
        ordered.setResponseHeadersPolicyId("missing-ordered-policy");
        invalidUpdate.getConfig().setCacheBehaviors(List.of(ordered));
        Distribution stored = valid;

        assertAws("NoSuchResponseHeadersPolicy",
                () -> service.updateDistribution(stored.getId(), stored.getEtag(), invalidUpdate));
        assertEquals(CloudFrontService.MANAGED_SIMPLE_CORS_POLICY_ID,
                service.getDistribution(stored.getId()).getConfig()
                        .getDefaultCacheBehavior().getResponseHeadersPolicyId());
    }

    @Test
    void enforcesPolicyNameUniquenessAndManagedPolicyImmutability() {
        CloudFrontService service = serviceWithDomainSuffix("cloudfront.net");
        ResponseHeadersPolicy first = service.createResponseHeadersPolicy(customPolicy("unique-name"));

        assertAws("ResponseHeadersPolicyAlreadyExists",
                () -> service.createResponseHeadersPolicy(customPolicy("unique-name")));
        assertAws("ResponseHeadersPolicyAlreadyExists",
                () -> service.createResponseHeadersPolicy(customPolicy("Managed-SimpleCORS")));

        ResponseHeadersPolicy managed = service.getResponseHeadersPolicy(
                CloudFrontService.MANAGED_SIMPLE_CORS_POLICY_ID);
        assertAws("IllegalUpdate", () -> service.updateResponseHeadersPolicy(
                managed.getId(), managed.getEtag(), customPolicy("replacement")));
        assertAws("IllegalDelete", () -> service.deleteResponseHeadersPolicy(
                managed.getId(), managed.getEtag()));

        ResponseHeadersPolicy second = service.createResponseHeadersPolicy(customPolicy("second-name"));
        assertAws("ResponseHeadersPolicyAlreadyExists", () -> service.updateResponseHeadersPolicy(
                second.getId(), second.getEtag(), customPolicy(first.getName())));
    }

    @Test
    void preventsDeletingAReferencedResponseHeadersPolicy() {
        CloudFrontService service = serviceWithDomainSuffix("cloudfront.net");
        ResponseHeadersPolicy policy = service.createResponseHeadersPolicy(customPolicy("in-use-policy"));
        Distribution distribution = service.createDistribution(
                distributionWithPolicy(policy.getId()), Map.of());

        assertAws("ResponseHeadersPolicyInUse",
                () -> service.deleteResponseHeadersPolicy(policy.getId(), policy.getEtag()));

        Distribution detached = distributionWithPolicy(null);
        service.updateDistribution(distribution.getId(), distribution.getEtag(), detached);
        service.deleteResponseHeadersPolicy(policy.getId(), policy.getEtag());
        assertAws("NoSuchResponseHeadersPolicy",
                () -> service.getResponseHeadersPolicy(policy.getId()));
    }

    @Test
    void enforcesCustomPolicyQuotaAndStaleEtagPreconditions() {
        CloudFrontService service = serviceWithDomainSuffix("cloudfront.net");
        for (int index = 0; index < 20; index++) {
            service.createResponseHeadersPolicy(customPolicy("quota-policy-" + index));
        }
        assertAws("TooManyResponseHeadersPolicies",
                () -> service.createResponseHeadersPolicy(customPolicy("quota-policy-overflow")));

        CloudFrontService etagService = serviceWithDomainSuffix("cloudfront.net");
        ResponseHeadersPolicy policy = etagService.createResponseHeadersPolicy(
                customPolicy("etag-policy"));
        AwsException update = assertAws("PreconditionFailed",
                () -> etagService.updateResponseHeadersPolicy(
                        policy.getId(), "stale-etag", customPolicy("etag-policy")));
        assertEquals(412, update.getHttpStatus());
        AwsException delete = assertAws("PreconditionFailed",
                () -> etagService.deleteResponseHeadersPolicy(policy.getId(), "stale-etag"));
        assertEquals(412, delete.getHttpStatus());
    }

    private static Distribution distributionWithPolicy(String policyId) {
        DefaultCacheBehavior behavior = new DefaultCacheBehavior();
        behavior.setResponseHeadersPolicyId(policyId);
        DistributionConfig config = new DistributionConfig();
        config.setDefaultCacheBehavior(behavior);
        Distribution distribution = new Distribution();
        distribution.setConfig(config);
        return distribution;
    }

    private static ResponseHeadersPolicy customPolicy(String name) {
        ResponseHeadersPolicy policy = new ResponseHeadersPolicy();
        policy.setName(name);
        policy.setConfig(Map.of());
        return policy;
    }

    private static Map<String, String> policyHeaders(
            ResponseHeadersPolicy policy, boolean preflight) {
        return ResponseHeadersPolicyConfigCodec.directives(
                        policy.getConfig(), "https://viewer.example", preflight).add().stream()
                .collect(Collectors.toMap(
                        ResponseHeadersPolicyConfigCodec.PolicyHeader::name,
                        ResponseHeadersPolicyConfigCodec.PolicyHeader::value,
                        (left, right) -> right,
                        LinkedHashMap::new));
    }

    private static AwsException assertAws(
            String code, org.junit.jupiter.api.function.Executable action) {
        AwsException error = assertThrows(AwsException.class, action);
        assertEquals(code, error.getErrorCode());
        return error;
    }
}
