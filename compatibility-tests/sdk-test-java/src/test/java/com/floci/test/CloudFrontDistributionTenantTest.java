package com.floci.test;

import org.junit.jupiter.api.Test;
import software.amazon.awssdk.services.cloudfront.CloudFrontClient;
import software.amazon.awssdk.services.cloudfront.model.CreateDistributionTenantResponse;
import software.amazon.awssdk.services.cloudfront.model.DefaultCacheBehavior;
import software.amazon.awssdk.services.cloudfront.model.DistributionConfig;
import software.amazon.awssdk.services.cloudfront.model.DistributionTenantSummary;
import software.amazon.awssdk.services.cloudfront.model.DomainItem;
import software.amazon.awssdk.services.cloudfront.model.EntityAlreadyExistsException;
import software.amazon.awssdk.services.cloudfront.model.EntityNotFoundException;
import software.amazon.awssdk.services.cloudfront.model.GetDistributionTenantByDomainResponse;
import software.amazon.awssdk.services.cloudfront.model.GetDistributionTenantResponse;
import software.amazon.awssdk.services.cloudfront.model.InvalidIfMatchVersionException;
import software.amazon.awssdk.services.cloudfront.model.NoSuchDistributionException;
import software.amazon.awssdk.services.cloudfront.model.Origin;
import software.amazon.awssdk.services.cloudfront.model.Origins;
import software.amazon.awssdk.services.cloudfront.model.Parameter;
import software.amazon.awssdk.services.cloudfront.model.PreconditionFailedException;
import software.amazon.awssdk.services.cloudfront.model.ResourceNotDisabledException;
import software.amazon.awssdk.services.cloudfront.model.S3OriginConfig;
import software.amazon.awssdk.services.cloudfront.model.ViewerCertificate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CloudFrontDistributionTenantTest {

    @Test
    void roundTripsDistributionTenantsWithTheAwsSdk() {
        String unique = TestFixtures.uniqueName();
        String name = "compat-tenant-" + unique;
        String domain = "compat-" + unique + ".example.com";

        try (CloudFrontClient cloudFront = TestFixtures.cloudFrontClient()) {
            String distributionId = cloudFront.createDistribution(request -> request
                            .distributionConfig(tenantOnlyConfig(unique)))
                    .distribution().id();
            String tenantId = null;
            try {
                CreateDistributionTenantResponse created = cloudFront.createDistributionTenant(request -> request
                        .distributionId(distributionId)
                        .name(name)
                        .domains(DomainItem.builder().domain(domain).build())
                        .parameters(Parameter.builder().name("origin_path").value("/compat").build()));
                tenantId = created.distributionTenant().id();
                String id = tenantId;
                assertTrue(id.startsWith("dt_"));
                assertTrue(created.distributionTenant().arn().endsWith(":distribution-tenant/" + id));
                assertTrue(created.distributionTenant().enabled());
                assertNotNull(created.distributionTenant().connectionGroupId());
                assertEquals("active", created.distributionTenant().domains().get(0).statusAsString());

                GetDistributionTenantResponse byId =
                        cloudFront.getDistributionTenant(request -> request.identifier(id));
                assertEquals(created.eTag(), byId.eTag());
                assertEquals(id, cloudFront.getDistributionTenant(request -> request.identifier(name))
                        .distributionTenant().id());
                assertEquals(id, cloudFront.getDistributionTenant(
                        request -> request.identifier(created.distributionTenant().arn()))
                        .distributionTenant().id());
                GetDistributionTenantByDomainResponse byDomain =
                        cloudFront.getDistributionTenantByDomain(request -> request.domain(domain));
                assertEquals(id, byDomain.distributionTenant().id());

                DistributionTenantSummary listed = cloudFront.listDistributionTenants(request -> request
                                .associationFilter(filter -> filter.distributionId(distributionId)))
                        .distributionTenantList().get(0);
                assertEquals(id, listed.id());
                assertEquals(created.eTag(), listed.eTag());

                assertThrows(EntityAlreadyExistsException.class, () -> cloudFront.createDistributionTenant(
                        request -> request.distributionId(distributionId).name(name)
                                .domains(DomainItem.builder().domain("other-" + unique + ".example.com").build())));
                assertThrows(InvalidIfMatchVersionException.class,
                        () -> cloudFront.updateDistributionTenant(request -> request.id(id).enabled(false)));
                assertThrows(PreconditionFailedException.class,
                        () -> cloudFront.updateDistributionTenant(request -> request
                                .id(id).ifMatch("E000000000000").enabled(false)));
                assertThrows(ResourceNotDisabledException.class,
                        () -> cloudFront.deleteDistributionTenant(request -> request
                                .id(id).ifMatch(created.eTag())));

                cloudFront.updateDistributionTenant(request -> request
                        .id(id).ifMatch(created.eTag()).enabled(false));
                GetDistributionTenantResponse updated =
                        cloudFront.getDistributionTenant(request -> request.identifier(id));
                assertFalse(updated.distributionTenant().enabled());

                cloudFront.deleteDistributionTenant(request -> request.id(id).ifMatch(updated.eTag()));
                tenantId = null;
                assertThrows(EntityNotFoundException.class,
                        () -> cloudFront.getDistributionTenant(request -> request.identifier(id)));
            } finally {
                disableAndDelete(cloudFront, tenantId);
                disableAndDeleteDistribution(cloudFront, distributionId);
            }
        }
    }

    private static DistributionConfig tenantOnlyConfig(String unique) {
        return DistributionConfig.builder()
                .callerReference("compat-tenant-config-" + unique)
                .comment("multi-tenant")
                .enabled(true)
                .origins(Origins.builder().quantity(1).items(Origin.builder()
                        .id("origin1")
                        .domainName("example-bucket.s3.us-east-1.amazonaws.com")
                        .s3OriginConfig(S3OriginConfig.builder().originAccessIdentity("").build())
                        .build()).build())
                .defaultCacheBehavior(DefaultCacheBehavior.builder()
                        .targetOriginId("origin1")
                        .viewerProtocolPolicy("redirect-to-https")
                        .build())
                .viewerCertificate(ViewerCertificate.builder().cloudFrontDefaultCertificate(true).build())
                .connectionMode("tenant-only")
                .build();
    }

    private static void disableAndDelete(CloudFrontClient cloudFront, String tenantId) {
        if (tenantId == null) {
            return;
        }
        try {
            String etag = cloudFront.getDistributionTenant(request -> request.identifier(tenantId)).eTag();
            cloudFront.updateDistributionTenant(request -> request.id(tenantId).ifMatch(etag).enabled(false));
            String freshEtag = cloudFront.getDistributionTenant(request -> request.identifier(tenantId)).eTag();
            cloudFront.deleteDistributionTenant(request -> request.id(tenantId).ifMatch(freshEtag));
        } catch (EntityNotFoundException ignored) {
            // teardown only: the emulator state is per-test, so a partially cleaned tenant cannot leak into another test
        }
    }

    private static void disableAndDeleteDistribution(CloudFrontClient cloudFront, String distributionId) {
        try {
            String etag = cloudFront.getDistribution(request -> request.id(distributionId)).eTag();
            cloudFront.updateDistribution(request -> request.id(distributionId).ifMatch(etag)
                    .distributionConfig(cloudFront.getDistributionConfig(byId -> byId.id(distributionId))
                            .distributionConfig().toBuilder().enabled(false).build()));
            String freshEtag = cloudFront.getDistribution(request -> request.id(distributionId)).eTag();
            cloudFront.deleteDistribution(request -> request.id(distributionId).ifMatch(freshEtag));
        } catch (NoSuchDistributionException ignored) {
            // teardown only: a distribution that is already gone has nothing left to clean up
        }
    }
}
