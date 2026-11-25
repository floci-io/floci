package io.floci.aws.services.cloudfront;

import io.floci.aws.config.EmulatorConfig;
import io.floci.aws.core.common.AwsException;
import io.floci.aws.core.common.RegionResolver;
import io.floci.aws.core.storage.AccountAwareStorageBackend;
import io.floci.aws.core.storage.StorageFactory;
import io.floci.aws.services.cloudfront.model.ConnectionGroup;
import io.floci.aws.services.cloudfront.model.Distribution;
import io.floci.aws.services.cloudfront.model.DistributionConfig;
import io.floci.aws.services.cloudfront.model.DistributionTenant;
import io.floci.aws.services.cloudfront.model.TenantCustomizations;
import io.floci.aws.testing.ParallelLocks;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.ResourceAccessMode;
import org.junit.jupiter.api.parallel.ResourceLock;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Distribution tenants in isolation: one account, one mocked storage factory. A tenant hangs off a
 * {@code tenant-only} distribution and a connection group, so most cases start from the
 * distribution that the CloudFront API requires to exist first.
 */
@ResourceLock(value = ParallelLocks.PORTS, mode = ResourceAccessMode.READ)
class CloudFrontDistributionTenantServiceTest {

    private static final String ACCOUNT = "000000000000";

    private final Map<String, AccountAwareStorageBackend<?>> stores = new HashMap<>();

    private CloudFrontService service() {
        StorageFactory storageFactory = mock(StorageFactory.class);
        when(storageFactory.create(anyString(), anyString(), any()))
                .thenAnswer(invocation -> stores.computeIfAbsent(invocation.getArgument(1, String.class),
                        ignored -> AccountAwareStorageBackend.inMemory("000000000000")));
        EmulatorConfig config = mock(EmulatorConfig.class);
        EmulatorConfig.DnsConfig dnsConfig = mock(EmulatorConfig.DnsConfig.class);
        EmulatorConfig.ServicesConfig servicesConfig = mock(EmulatorConfig.ServicesConfig.class);
        EmulatorConfig.CloudFrontServiceConfig cloudFrontConfig = mock(EmulatorConfig.CloudFrontServiceConfig.class);

        when(config.defaultAccountId()).thenReturn(ACCOUNT);
        when(config.hostname()).thenReturn(Optional.empty());
        when(config.effectiveBaseUrl()).thenReturn("http://localhost:4566");
        when(config.dns()).thenReturn(dnsConfig);
        when(dnsConfig.extraSuffixes()).thenReturn(Optional.empty());
        when(config.services()).thenReturn(servicesConfig);
        when(servicesConfig.cloudfront()).thenReturn(cloudFrontConfig);
        when(cloudFrontConfig.domainSuffix()).thenReturn(Optional.empty());

        return new CloudFrontService(storageFactory, config, new RegionResolver("us-east-1", ACCOUNT));
    }

    private static Distribution distribution(String connectionMode, String... aliases) {
        DistributionConfig config = new DistributionConfig();
        config.setCallerReference("tenant-test-" + System.nanoTime());
        config.setConnectionMode(connectionMode);
        if (aliases.length > 0) {
            config.setAliases(List.of(aliases));
        }
        Distribution dist = new Distribution();
        dist.setConfig(config);
        return dist;
    }

    private static String tenantOnly(CloudFrontService service) {
        return service.createDistribution(distribution("tenant-only"), Map.of()).getId();
    }

    private static DistributionTenant create(CloudFrontService service, String distributionId, String name,
                                             String... domains) {
        return service.createDistributionTenant(distributionId, name, List.of(domains), Map.of(), null,
                null, null, null);
    }

    private static String errorCode(Runnable call) {
        return assertThrows(AwsException.class, call::run).getErrorCode();
    }

    private static String errorMessage(Runnable call) {
        return assertThrows(AwsException.class, call::run).getMessage();
    }

    @Test
    void aTenantStartsEnabledOnTheDefaultConnectionGroupWithItsDomainsActive() {
        CloudFrontService service = service();
        String distributionId = tenantOnly(service);

        DistributionTenant tenant = create(service, distributionId, "acme", "acme.example.com");

        assertTrue(tenant.getId().startsWith("dt_"));
        assertTrue(tenant.getArn().startsWith("arn:aws:cloudfront::" + ACCOUNT + ":distribution-tenant/"));
        assertTrue(tenant.getArn().endsWith("/" + tenant.getId()));
        assertEquals(distributionId, tenant.getDistributionId());
        assertEquals("acme", tenant.getName());
        assertTrue(tenant.getEnabled());
        assertEquals("Deployed", tenant.getStatus());
        assertEquals(List.of("acme.example.com"), tenant.getDomains());
        assertNotNull(tenant.getEtag());
        assertNotNull(tenant.getCreatedTime());
        ConnectionGroup defaultGroup = service.ensureDefaultConnectionGroup();
        assertEquals(defaultGroup.getId(), tenant.getConnectionGroupId());
    }

    @Test
    void aConnectionGroupStillReferencedByATenantCannotBeDeleted() {
        CloudFrontService service = service();
        String distributionId = tenantOnly(service);
        ConnectionGroup group = service.createConnectionGroup("edge", null, false, true, null, null);
        DistributionTenant tenant = service.createDistributionTenant(distributionId, "acme",
                List.of("acme.example.com"), Map.of(), null, null, group.getId(), false);

        assertEquals("CannotDeleteEntityWhileInUse",
                errorCode(() -> service.deleteConnectionGroup(group.getId(), group.getEtag())));

        service.deleteDistributionTenant(tenant.getId(), tenant.getEtag());
        service.deleteConnectionGroup(group.getId(), group.getEtag());
        assertEquals("EntityNotFound", errorCode(() -> service.getConnectionGroup(group.getId())));
    }

    @Test
    void aNamedConnectionGroupMustExistAndIsResolvedToItsId() {
        CloudFrontService service = service();
        String distributionId = tenantOnly(service);
        ConnectionGroup group = service.createConnectionGroup("edge", null, true, true, null, null);

        DistributionTenant tenant = service.createDistributionTenant(distributionId, "acme",
                List.of("acme.example.com"), Map.of(), null, null, group.getId(), false);
        assertEquals(group.getId(), tenant.getConnectionGroupId());
        assertFalse(tenant.getEnabled());

        assertEquals("EntityNotFound", errorCode(() -> service.createDistributionTenant(distributionId, "other",
                List.of("other.example.com"), Map.of(), null, null, "cg_missing", null)));
    }

    @Test
    void theDistributionMustExistAndBeTenantOnly() {
        CloudFrontService service = service();
        String direct = service.createDistribution(distribution("direct"), Map.of()).getId();
        String unset = service.createDistribution(distribution(null), Map.of()).getId();

        assertEquals("EntityNotFound", errorCode(() -> create(service, "EMISSING", "aaa1", "a.example.com")));
        assertEquals("InvalidArgument", errorCode(() -> create(service, direct, "aaa2", "a.example.com")));
        assertEquals("InvalidArgument", errorCode(() -> create(service, unset, "aaa3", "a.example.com")));
    }

    @Test
    void nameAndDomainsAreValidated() {
        CloudFrontService service = service();
        String distributionId = tenantOnly(service);

        assertEquals("InvalidArgument", errorCode(() -> create(service, distributionId, "", "a.example.com")));
        assertEquals("InvalidArgument", errorCode(() -> create(service, distributionId, "ab", "a.example.com")));
        assertEquals("InvalidArgument", errorCode(() -> create(service, distributionId, "-bad-", "a.example.com")));
        assertEquals("InvalidArgument", errorCode(() -> create(service, distributionId, "good-name")));
        assertEquals("InvalidArgument", errorCode(() -> create(service, distributionId, "good-name",
                "a.example.com", "A.EXAMPLE.COM")));
        assertEquals("EntityLimitExceeded", errorCode(() -> create(service, distributionId, "good-name",
                "1.example.com", "2.example.com", "3.example.com", "4.example.com", "5.example.com",
                "6.example.com")));
    }

    @Test
    void aNameIsUniquePerAccount() {
        CloudFrontService service = service();
        String distributionId = tenantOnly(service);
        create(service, distributionId, "acme", "acme.example.com");

        assertEquals("EntityAlreadyExists", errorCode(() -> create(service, distributionId, "acme",
                "other.example.com")));
    }

    @Test
    void aDomainIsUniqueAcrossTenantsAndDistributionAliases() {
        CloudFrontService service = service();
        String distributionId = tenantOnly(service);
        service.createDistribution(distribution("direct", "www.taken.example.com"), Map.of());
        create(service, distributionId, "acme", "acme.example.com");

        assertEquals("CNAMEAlreadyExists", errorCode(() -> create(service, distributionId, "beta",
                "ACME.example.com")));
        assertEquals("CNAMEAlreadyExists", errorCode(() -> create(service, distributionId, "gamma",
                "www.taken.example.com")));
        assertEquals("CNAMEAlreadyExists", errorCode(() -> service.createDistribution(
                distribution("direct", "acme.example.com"), Map.of())));
    }

    @Test
    void parametersAndCustomizationsAreStoredAndValidated() {
        CloudFrontService service = service();
        String distributionId = tenantOnly(service);
        TenantCustomizations customizations = new TenantCustomizations();
        customizations.setWebAclAction("override");
        customizations.setWebAclArn("arn:aws:wafv2:us-east-1:000000000000:global/webacl/x/y");
        customizations.setGeoRestrictionType("whitelist");
        customizations.setGeoLocations(List.of("US", "CA"));
        Map<String, String> parameters = new LinkedHashMap<>();
        parameters.put("origin_path", "/acme");

        DistributionTenant tenant = service.createDistributionTenant(distributionId, "acme",
                List.of("acme.example.com"), Map.of(), customizations, parameters, null, null);
        assertEquals("override", tenant.getCustomizations().getWebAclAction());
        assertEquals(List.of("US", "CA"), tenant.getCustomizations().getGeoLocations());
        assertEquals(Map.of("origin_path", "/acme"), tenant.getParameters());

        TenantCustomizations badAction = new TenantCustomizations();
        badAction.setWebAclAction("bogus");
        assertEquals("InvalidArgument", errorCode(() -> service.createDistributionTenant(distributionId, "bad1",
                List.of("b1.example.com"), Map.of(), badAction, null, null, null)));
        TenantCustomizations badGeo = new TenantCustomizations();
        badGeo.setGeoRestrictionType("bogus");
        assertEquals("InvalidArgument", errorCode(() -> service.createDistributionTenant(distributionId, "bad2",
                List.of("b2.example.com"), Map.of(), badGeo, null, null, null)));
        Map<String, String> badParameter = Map.of("not valid!", "x");
        assertEquals("InvalidArgument", errorCode(() -> service.createDistributionTenant(distributionId, "bad3",
                List.of("b3.example.com"), Map.of(), null, badParameter, null, null)));
    }

    @Test
    void aTenantIsReadByIdNameArnAndDomain() {
        CloudFrontService service = service();
        String distributionId = tenantOnly(service);
        DistributionTenant tenant = create(service, distributionId, "acme", "acme.example.com");

        assertEquals(tenant.getId(), service.getDistributionTenant(tenant.getId()).getId());
        assertEquals(tenant.getId(), service.getDistributionTenant("acme").getId());
        assertEquals(tenant.getId(), service.getDistributionTenant(tenant.getArn()).getId());
        assertEquals(tenant.getId(), service.getDistributionTenantByDomain("ACME.example.com").getId());

        assertEquals("EntityNotFound", errorCode(() -> service.getDistributionTenant("dt_missing")));
        assertEquals("EntityNotFound", errorCode(() -> service.getDistributionTenant(
                "arn:aws:cloudfront::000000000000:distribution-tenant/dt_missing")));
        assertEquals("EntityNotFound", errorCode(() -> service.getDistributionTenantByDomain("no.example.com")));
    }

    @Test
    void writesRequireTheCurrentEtagAndRotateIt() {
        CloudFrontService service = service();
        String distributionId = tenantOnly(service);
        DistributionTenant tenant = create(service, distributionId, "acme", "acme.example.com");
        String id = tenant.getId();
        String first = tenant.getEtag();

        assertEquals("InvalidIfMatchVersion", errorCode(() -> service.updateDistributionTenant(id, null,
                null, null, null, null, null, false)));
        assertEquals("PreconditionFailed", errorCode(() -> service.updateDistributionTenant(id, "stale",
                null, null, null, null, null, false)));
        assertEquals("EntityNotFound", errorCode(() -> service.updateDistributionTenant("dt_missing", first,
                null, null, null, null, null, false)));

        DistributionTenant updated = service.updateDistributionTenant(id, first, null,
                List.of("new.example.com"), null, Map.of("k", "v"), null, false);
        assertNotEquals(first, updated.getEtag());
        assertFalse(updated.getEnabled());
        assertEquals(List.of("new.example.com"), updated.getDomains());
        assertEquals(Map.of("k", "v"), updated.getParameters());
        assertEquals("PreconditionFailed", errorCode(() -> service.updateDistributionTenant(id, first,
                null, null, null, null, null, true)));
    }

    @Test
    void anUpdateKeepsWhatItOmitsAndChecksDomainsAgainstOtherTenants() {
        CloudFrontService service = service();
        String distributionId = tenantOnly(service);
        DistributionTenant acme = create(service, distributionId, "acme", "acme.example.com");
        create(service, distributionId, "beta", "beta.example.com");

        DistributionTenant touched = service.updateDistributionTenant(acme.getId(), acme.getEtag(), null,
                null, null, null, null, null);
        assertEquals(List.of("acme.example.com"), touched.getDomains());
        assertTrue(touched.getEnabled());

        assertEquals("CNAMEAlreadyExists", errorCode(() -> service.updateDistributionTenant(acme.getId(),
                touched.getEtag(), null, List.of("beta.example.com"), null, null, null, null)));
        DistributionTenant same = service.updateDistributionTenant(acme.getId(), touched.getEtag(), null,
                List.of("acme.example.com", "extra.example.com"), null, null, null, null);
        assertEquals(2, same.getDomains().size());
    }

    @Test
    void anUpdateCanMoveTheTenantToAnotherTenantOnlyDistributionOrGroup() {
        CloudFrontService service = service();
        String first = tenantOnly(service);
        String second = tenantOnly(service);
        String direct = service.createDistribution(distribution("direct"), Map.of()).getId();
        ConnectionGroup group = service.createConnectionGroup("edge", null, true, true, null, null);
        DistributionTenant tenant = create(service, first, "acme", "acme.example.com");

        DistributionTenant moved = service.updateDistributionTenant(tenant.getId(), tenant.getEtag(), second,
                null, null, null, group.getId(), null);
        assertEquals(second, moved.getDistributionId());
        assertEquals(group.getId(), moved.getConnectionGroupId());

        assertEquals("InvalidArgument", errorCode(() -> service.updateDistributionTenant(tenant.getId(),
                moved.getEtag(), direct, null, null, null, null, null)));
        assertEquals("EntityNotFound", errorCode(() -> service.updateDistributionTenant(tenant.getId(),
                moved.getEtag(), "EMISSING", null, null, null, null, null)));
        assertEquals("EntityNotFound", errorCode(() -> service.updateDistributionTenant(tenant.getId(),
                moved.getEtag(), null, null, null, null, "cg_missing", null)));
    }

    @Test
    void aDeleteNeedsAnIfMatchADisabledTenantAndRemovesItsTags() {
        CloudFrontService service = service();
        String distributionId = tenantOnly(service);
        DistributionTenant tenant = service.createDistributionTenant(distributionId, "acme",
                List.of("acme.example.com"), Map.of("env", "test"), null, null, null, null);
        assertEquals(Map.of("env", "test"), service.listTagsForResource(tenant.getArn()));
        String id = tenant.getId();

        assertEquals("InvalidIfMatchVersion", errorCode(() -> service.deleteDistributionTenant(id, null)));
        assertEquals("PreconditionFailed", errorCode(() -> service.deleteDistributionTenant(id, "stale")));
        assertEquals("ResourceNotDisabled", errorCode(() -> service.deleteDistributionTenant(id,
                tenant.getEtag())));

        DistributionTenant disabled = service.updateDistributionTenant(id, tenant.getEtag(), null, null, null,
                null, null, false);
        service.deleteDistributionTenant(id, disabled.getEtag());

        assertEquals("EntityNotFound", errorCode(() -> service.getDistributionTenant(id)));
        assertEquals("EntityNotFound", errorCode(() -> service.deleteDistributionTenant(id, disabled.getEtag())));
        assertTrue(service.listTagsForResource(tenant.getArn()).isEmpty());
        create(service, distributionId, "acme", "acme.example.com");
    }

    @Test
    void tagResourceWorksOnATenantArn() {
        CloudFrontService service = service();
        String distributionId = tenantOnly(service);
        DistributionTenant tenant = create(service, distributionId, "acme", "acme.example.com");

        service.tagResource(tenant.getArn(), Map.of("team", "edge"));
        assertEquals(Map.of("team", "edge"), service.listTagsForResource(tenant.getArn()));
        service.untagResource(tenant.getArn(), List.of("team"));
        assertTrue(service.listTagsForResource(tenant.getArn()).isEmpty());
    }

    @Test
    void listingFiltersByDistributionAndConnectionGroupAndPages() {
        CloudFrontService service = service();
        String first = tenantOnly(service);
        String second = tenantOnly(service);
        ConnectionGroup group = service.createConnectionGroup("edge", null, true, true, null, null);
        create(service, first, "alpha", "alpha.example.com");
        create(service, first, "beta", "beta.example.com");
        service.createDistributionTenant(second, "gamma", List.of("gamma.example.com"), Map.of(), null, null,
                group.getId(), null);

        assertEquals(List.of("alpha", "beta", "gamma"), names(service.listDistributionTenants(null, 100, null, null)));
        assertEquals(List.of("alpha", "beta"), names(service.listDistributionTenants(null, 100, first, null)));
        assertEquals(List.of("gamma"), names(service.listDistributionTenants(null, 100, null, group.getId())));
        assertEquals(List.of("gamma"), names(service.listDistributionTenants(null, 100, second, group.getId())));
        assertEquals(List.of("beta", "gamma"), names(service.listDistributionTenants("alpha", 100, null, null)));
        assertEquals(List.of("alpha", "beta"), names(service.listDistributionTenants(null, 2, null, null)));
    }

    @Test
    void listingByCustomizationNeedsExactlyOneFilter() {
        CloudFrontService service = service();
        String distributionId = tenantOnly(service);
        TenantCustomizations withAcl = new TenantCustomizations();
        withAcl.setWebAclAction("override");
        withAcl.setWebAclArn("arn:aws:wafv2:us-east-1:000000000000:global/webacl/x/y");
        TenantCustomizations withCert = new TenantCustomizations();
        withCert.setCertificateArn("arn:aws:acm:us-east-1:000000000000:certificate/abc");
        service.createDistributionTenant(distributionId, "acl", List.of("acl.example.com"), Map.of(), withAcl,
                null, null, null);
        service.createDistributionTenant(distributionId, "cert", List.of("cert.example.com"), Map.of(),
                withCert, null, null, null);
        create(service, distributionId, "plain", "plain.example.com");

        assertEquals(List.of("acl"), names(service.listDistributionTenantsByCustomization(null, 100,
                withAcl.getWebAclArn(), null)));
        assertEquals(List.of("cert"), names(service.listDistributionTenantsByCustomization(null, 100, null,
                withCert.getCertificateArn())));
        assertEquals("InvalidArgument", errorCode(() -> service.listDistributionTenantsByCustomization(null,
                100, null, null)));
        assertEquals("InvalidArgument", errorCode(() -> service.listDistributionTenantsByCustomization(null,
                100, "a", "b")));
    }

    @Test
    void aTenantDomainCannotBeClaimedThroughAssociateAlias() {
        CloudFrontService service = service();
        String distributionId = tenantOnly(service);
        create(service, distributionId, "acme", "acme.example.com");
        Distribution direct = service.createDistribution(distribution("direct"), Map.of());

        assertEquals("CNAMEAlreadyExists",
                errorCode(() -> service.associateAlias(direct.getId(), "ACME.example.com")));

        List<String> aliases = service.getDistribution(direct.getId()).getConfig().getAliases();
        assertTrue(aliases == null || aliases.isEmpty(), "a refused alias must leave the target untouched");
    }

    @Test
    void aParameterWithoutNameOrValueIsInvalidArgumentOnCreateAndUpdate() {
        CloudFrontService service = service();
        String distributionId = tenantOnly(service);
        DistributionTenant tenant = create(service, distributionId, "acme", "acme.example.com");
        Map<String, String> noName = new LinkedHashMap<>();
        noName.put(null, "x");
        Map<String, String> noValue = new LinkedHashMap<>();
        noValue.put("origin_path", null);

        for (Map<String, String> parameters : List.of(noName, noValue)) {
            assertEquals("InvalidArgument", errorCode(() -> service.createDistributionTenant(distributionId,
                    "bad", List.of("bad.example.com"), Map.of(), null, parameters, null, null)));
            assertEquals("InvalidArgument", errorCode(() -> service.updateDistributionTenant(tenant.getId(),
                    tenant.getEtag(), null, null, null, parameters, null, null)));
        }
    }

    @Test
    void aWebAclWithoutAnActionIsInvalidArgumentOnCreateAndUpdate() {
        CloudFrontService service = service();
        String distributionId = tenantOnly(service);
        DistributionTenant tenant = create(service, distributionId, "acme", "acme.example.com");
        TenantCustomizations arnOnly = new TenantCustomizations();
        arnOnly.setWebAclArn("arn:aws:wafv2:us-east-1:000000000000:global/webacl/x/y");

        assertEquals("InvalidArgument", errorCode(() -> service.createDistributionTenant(distributionId,
                "bad", List.of("bad.example.com"), Map.of(), arnOnly, null, null, null)));
        assertEquals("InvalidArgument", errorCode(() -> service.updateDistributionTenant(tenant.getId(),
                tenant.getEtag(), null, null, arnOnly, null, null, null)));
    }

    private static List<String> names(List<DistributionTenant> tenants) {
        return tenants.stream().map(DistributionTenant::getName).toList();
    }

    @Test
    void tenantErrorsUseTheSentencesAwsReturns() {
        CloudFrontService service = service();
        String distributionId = tenantOnly(service);
        service.createDistribution(distribution("direct", "www.taken.example.com"), Map.of());
        DistributionTenant tenant = create(service, distributionId, "acme", "acme.example.com");
        String id = tenant.getId();
        String missingTenant = "The specified distribution tenant doesn't exist.";
        String taken = "One or more of the CNAMEs you provided are already associated with a different resource.";

        assertEquals(missingTenant, errorMessage(() -> service.getDistributionTenant("dt_missing")));
        assertEquals(missingTenant, errorMessage(() -> service.getDistributionTenantByDomain("ghost.example.com")));
        assertEquals("The specified distribution does not exist.",
                errorMessage(() -> create(service, "EMISSING", "beta", "beta.example.com")));
        assertEquals("You must disable the distribution tenant before you can delete it.",
                errorMessage(() -> service.deleteDistributionTenant(id, tenant.getEtag())));
        assertEquals("Your request is missing requirements for this operation. Update your request and try again.",
                errorMessage(() -> service.deleteDistributionTenant(id, "stale")));
        assertEquals(taken, errorMessage(() -> create(service, distributionId, "beta", "ACME.example.com")));
        assertEquals(taken, errorMessage(() -> create(service, distributionId, "gamma", "www.taken.example.com")));
        assertEquals(taken, errorMessage(() -> service.createDistribution(
                distribution("direct", "WWW.taken.example.com"), Map.of())));
        assertEquals(taken, errorMessage(() -> service.createDistribution(
                distribution("direct", "acme.example.com"), Map.of())));
    }
}
