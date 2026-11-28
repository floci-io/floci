package io.floci.aws.services.cloudfront;

import io.floci.aws.testing.AwsTestClients;
import io.quarkus.test.common.http.TestHTTPResource;
import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.Test;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.cloudfront.CloudFrontClient;
import software.amazon.awssdk.services.cloudfront.model.CannotDeleteEntityWhileInUseException;
import software.amazon.awssdk.services.cloudfront.model.Certificate;
import software.amazon.awssdk.services.cloudfront.model.CreateConnectionGroupResponse;
import software.amazon.awssdk.services.cloudfront.model.CreateDistributionResponse;
import software.amazon.awssdk.services.cloudfront.model.CreateDistributionTenantResponse;
import software.amazon.awssdk.services.cloudfront.model.Customizations;
import software.amazon.awssdk.services.cloudfront.model.DefaultCacheBehavior;
import software.amazon.awssdk.services.cloudfront.model.DistributionConfig;
import software.amazon.awssdk.services.cloudfront.model.DistributionTenant;
import software.amazon.awssdk.services.cloudfront.model.DistributionTenantSummary;
import software.amazon.awssdk.services.cloudfront.model.DomainItem;
import software.amazon.awssdk.services.cloudfront.model.EntityAlreadyExistsException;
import software.amazon.awssdk.services.cloudfront.model.EntityNotFoundException;
import software.amazon.awssdk.services.cloudfront.model.GeoRestrictionCustomization;
import software.amazon.awssdk.services.cloudfront.model.GetDistributionTenantByDomainResponse;
import software.amazon.awssdk.services.cloudfront.model.GetDistributionTenantResponse;
import software.amazon.awssdk.services.cloudfront.model.InvalidIfMatchVersionException;
import software.amazon.awssdk.services.cloudfront.model.ListDistributionTenantsResponse;
import software.amazon.awssdk.services.cloudfront.model.NoSuchDistributionException;
import software.amazon.awssdk.services.cloudfront.model.Origin;
import software.amazon.awssdk.services.cloudfront.model.Origins;
import software.amazon.awssdk.services.cloudfront.model.Parameter;
import software.amazon.awssdk.services.cloudfront.model.ParameterDefinition;
import software.amazon.awssdk.services.cloudfront.model.ParameterDefinitionSchema;
import software.amazon.awssdk.services.cloudfront.model.PreconditionFailedException;
import software.amazon.awssdk.services.cloudfront.model.ResourceNotDisabledException;
import software.amazon.awssdk.services.cloudfront.model.S3OriginConfig;
import software.amazon.awssdk.services.cloudfront.model.StringSchemaConfig;
import software.amazon.awssdk.services.cloudfront.model.Tag;
import software.amazon.awssdk.services.cloudfront.model.Tags;
import software.amazon.awssdk.services.cloudfront.model.TenantConfig;
import software.amazon.awssdk.services.cloudfront.model.UpdateDistributionTenantResponse;
import software.amazon.awssdk.services.cloudfront.model.ViewerCertificate;
import software.amazon.awssdk.services.cloudfront.model.WebAclCustomization;

import java.net.URI;
import java.util.List;

import static io.floci.aws.testing.AwsSdkErrors.assertAwsError;
import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.containsString;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The distribution tenant management plane, validated with the real CloudFront SDK: every
 * operation, the identifier forms Get accepts, the If-Match guarded writes and the modeled errors.
 */
@QuarkusTest
class CloudFrontDistributionTenantSdkIntegrationTest {

    private static final String CLOUDFRONT_NS = "http://cloudfront.amazonaws.com/doc/2020-05-31/";

    @TestHTTPResource("/")
    URI endpoint;

    @Test
    void aTenantRoundTripsEveryMemberAndIsReadableByIdNameArnAndDomain() {
        try (CloudFrontClient client = client()) {
            String distributionId = createDistribution(client, "tenant-only");
            String stamp = stamp();
            String domain = "acme-" + stamp + ".example.com";
            CreateDistributionTenantResponse created = client.createDistributionTenant(request -> request
                    .distributionId(distributionId)
                    .name("tenant-" + stamp)
                    .domains(DomainItem.builder().domain(domain).build())
                    .tags(Tags.builder().items(Tag.builder().key("env").value("test").build()).build())
                    .customizations(Customizations.builder()
                            .webAcl(WebAclCustomization.builder().action("override")
                                    .arn("arn:aws:wafv2:us-east-1:000000000000:global/webacl/x/y").build())
                            .certificate(Certificate.builder()
                                    .arn("arn:aws:acm:us-east-1:000000000000:certificate/abc").build())
                            .geoRestrictions(GeoRestrictionCustomization.builder()
                                    .restrictionType("whitelist").locations("US", "CA").build())
                            .build())
                    .parameters(Parameter.builder().name("origin_path").value("/acme").build()));
            DistributionTenant tenant = created.distributionTenant();
            try {
                assertEquals(201, created.sdkHttpResponse().statusCode());
                assertTrue(tenant.id().startsWith("dt_"));
                assertTrue(tenant.arn().endsWith(":distribution-tenant/" + tenant.id()));
                assertEquals(distributionId, tenant.distributionId());
                assertTrue(tenant.enabled());
                assertEquals("Deployed", tenant.status());
                assertNotNull(tenant.connectionGroupId());
                assertNotNull(tenant.createdTime());
                assertNotNull(tenant.lastModifiedTime());
                assertEquals(1, tenant.domains().size());
                assertEquals(domain, tenant.domains().getFirst().domain());
                assertEquals("active", tenant.domains().getFirst().statusAsString());
                assertEquals("env", tenant.tags().items().getFirst().key());
                assertEquals("override", tenant.customizations().webAcl().actionAsString());
                assertEquals("arn:aws:acm:us-east-1:000000000000:certificate/abc",
                        tenant.customizations().certificate().arn());
                assertEquals("whitelist", tenant.customizations().geoRestrictions().restrictionTypeAsString());
                assertEquals(List.of("US", "CA"), tenant.customizations().geoRestrictions().locations());
                assertEquals("origin_path", tenant.parameters().getFirst().name());
                assertEquals("/acme", tenant.parameters().getFirst().value());

                GetDistributionTenantResponse byId =
                        client.getDistributionTenant(request -> request.identifier(tenant.id()));
                assertEquals(tenant.id(), byId.distributionTenant().id());
                assertEquals(created.eTag(), byId.eTag());
                assertEquals(tenant.id(), client.getDistributionTenant(
                        request -> request.identifier(tenant.name())).distributionTenant().id());
                assertEquals(tenant.id(), client.getDistributionTenant(
                        request -> request.identifier(tenant.arn())).distributionTenant().id());
                GetDistributionTenantByDomainResponse byDomain = client.getDistributionTenantByDomain(
                        request -> request.domain(domain));
                assertEquals(tenant.id(), byDomain.distributionTenant().id());
                assertEquals(created.eTag(), byDomain.eTag());

                assertAwsError("EntityNotFound", 404,
                        () -> client.getDistributionTenant(request -> request.identifier("dt_doesnotexist")));
                assertAwsError("EntityNotFound", 404,
                        () -> client.getDistributionTenantByDomain(request -> request.domain("none.example.com")));
            } finally {
                disableAndDelete(client, tenant.id());
                disableAndDeleteDistribution(client, distributionId);
            }
        }
    }

    @Test
    void createRefusalsCarryTheirModeledErrors() {
        try (CloudFrontClient client = client()) {
            String tenantOnly = createDistribution(client, "tenant-only");
            String direct = createDistribution(client, "direct");
            String stamp = stamp();
            String name = "tenant-" + stamp;
            String domain = "dup-" + stamp + ".example.com";
            CreateDistributionTenantResponse created = client.createDistributionTenant(request -> request
                    .distributionId(tenantOnly).name(name).domains(DomainItem.builder().domain(domain).build()));
            try {
                assertAwsError("EntityNotFound", 404, () -> client.createDistributionTenant(request -> request
                        .distributionId("EMISSING0000").name("tenant-m-" + stamp)
                        .domains(DomainItem.builder().domain("m-" + stamp + ".example.com").build())));
                assertAwsError("InvalidArgument", 400, () -> client.createDistributionTenant(request -> request
                        .distributionId(direct).name("tenant-d-" + stamp)
                        .domains(DomainItem.builder().domain("d-" + stamp + ".example.com").build())));
                assertAwsError("EntityNotFound", 404, () -> client.createDistributionTenant(request -> request
                        .distributionId(tenantOnly).name("tenant-g-" + stamp).connectionGroupId("cg_missing")
                        .domains(DomainItem.builder().domain("g-" + stamp + ".example.com").build())));
                assertAwsError("EntityAlreadyExists", 409, () -> client.createDistributionTenant(request -> request
                        .distributionId(tenantOnly).name(name)
                        .domains(DomainItem.builder().domain("n-" + stamp + ".example.com").build())));
                assertAwsError("CNAMEAlreadyExists", 409, () -> client.createDistributionTenant(request -> request
                        .distributionId(tenantOnly).name("tenant-c-" + stamp)
                        .domains(DomainItem.builder().domain(domain).build())));
                assertAwsError("InvalidArgument", 400, () -> client.createDistributionTenant(request -> request
                        .distributionId(tenantOnly).name("tenant-e-" + stamp)));
            } finally {
                disableAndDelete(client, created.distributionTenant().id());
                disableAndDeleteDistribution(client, tenantOnly);
                disableAndDeleteDistribution(client, direct);
            }
        }
    }

    @Test
    void ifMatchGuardedWritesRotateTheEtagAndDeleteNeedsADisabledTenant() {
        try (CloudFrontClient client = client()) {
            String distributionId = createDistribution(client, "tenant-only");
            String stamp = stamp();
            CreateDistributionTenantResponse created = client.createDistributionTenant(request -> request
                    .distributionId(distributionId).name("tenant-" + stamp)
                    .domains(DomainItem.builder().domain("w-" + stamp + ".example.com").build()));
            String id = created.distributionTenant().id();
            try {
                assertAwsError("InvalidIfMatchVersion", 400,
                        () -> client.updateDistributionTenant(request -> request.id(id).enabled(false)));
                assertAwsError("PreconditionFailed", 412, () -> client.updateDistributionTenant(request -> request
                        .id(id).ifMatch("E000000000000").enabled(false)));
                assertAwsError("InvalidIfMatchVersion", 400,
                        () -> client.deleteDistributionTenant(request -> request.id(id)));
                assertAwsError("ResourceNotDisabled", 409, () -> client.deleteDistributionTenant(request -> request
                        .id(id).ifMatch(created.eTag())));

                String newDomain = "w2-" + stamp + ".example.com";
                UpdateDistributionTenantResponse updated = client.updateDistributionTenant(request -> request.id(id).ifMatch(created.eTag())
                        .enabled(false)
                        .domains(DomainItem.builder().domain(newDomain).build())
                        .parameters(Parameter.builder().name("k").value("v").build()));
                assertFalse(updated.distributionTenant().enabled());
                assertEquals(newDomain, updated.distributionTenant().domains().getFirst().domain());
                assertEquals("v", updated.distributionTenant().parameters().getFirst().value());
                assertNotEquals(created.eTag(), updated.eTag());
                assertAwsError("PreconditionFailed", 412, () -> client.updateDistributionTenant(request -> request
                        .id(id).ifMatch(created.eTag()).enabled(true)));

                client.deleteDistributionTenant(request -> request.id(id).ifMatch(updated.eTag()));
                assertAwsError("EntityNotFound", 404,
                        () -> client.getDistributionTenant(request -> request.identifier(id)));
                assertAwsError("EntityNotFound", 404, () -> client.deleteDistributionTenant(request -> request
                        .id(id).ifMatch(updated.eTag())));
            } finally {
                disableAndDelete(client, id);
                disableAndDeleteDistribution(client, distributionId);
            }
        }
    }

    @Test
    void listingFiltersByAssociationAndCustomizationAndPages() {
        try (CloudFrontClient client = client()) {
            String first = createDistribution(client, "tenant-only");
            String second = createDistribution(client, "tenant-only");
            String stamp = stamp();
            String aclArn = "arn:aws:wafv2:us-east-1:000000000000:global/webacl/" + stamp + "/y";
            String certArn = "arn:aws:acm:us-east-1:000000000000:certificate/" + stamp;
            CreateDistributionTenantResponse a = client.createDistributionTenant(request -> request
                    .distributionId(first).name("list-a-" + stamp)
                    .domains(DomainItem.builder().domain("la-" + stamp + ".example.com").build())
                    .customizations(Customizations.builder().webAcl(
                            WebAclCustomization.builder().action("override").arn(aclArn).build()).build()));
            CreateDistributionTenantResponse b = client.createDistributionTenant(request -> request
                    .distributionId(first).name("list-b-" + stamp)
                    .domains(DomainItem.builder().domain("lb-" + stamp + ".example.com").build())
                    .customizations(Customizations.builder().certificate(
                            Certificate.builder().arn(certArn).build()).build()));
            CreateDistributionTenantResponse c = client.createDistributionTenant(request -> request
                    .distributionId(second).name("list-c-" + stamp)
                    .domains(DomainItem.builder().domain("lc-" + stamp + ".example.com").build()));
            try {
                List<String> onFirst = client.listDistributionTenants(request -> request
                                .associationFilter(filter -> filter.distributionId(first)))
                        .distributionTenantList().stream().map(DistributionTenantSummary::name).toList();
                assertEquals(List.of("list-a-" + stamp, "list-b-" + stamp), onFirst);

                String groupId = c.distributionTenant().connectionGroupId();
                assertTrue(client.listDistributionTenants(request -> request
                                .associationFilter(filter -> filter.connectionGroupId(groupId)))
                        .distributionTenantList().stream().anyMatch(t -> t.id().equals(c.distributionTenant().id())));

                ListDistributionTenantsResponse page = client.listDistributionTenants(request -> request
                        .associationFilter(filter -> filter.distributionId(first)).maxItems(1));
                assertEquals(1, page.distributionTenantList().size());
                assertEquals("list-a-" + stamp, page.nextMarker());
                DistributionTenantSummary summary = page.distributionTenantList().getFirst();
                assertEquals(a.eTag(), summary.eTag());
                assertEquals("override", summary.customizations().webAcl().actionAsString());
                assertEquals("active", summary.domains().getFirst().statusAsString());

                assertEquals(List.of("list-a-" + stamp), client.listDistributionTenantsByCustomization(
                                request -> request.webACLArn(aclArn))
                        .distributionTenantList().stream().map(DistributionTenantSummary::name).toList());
                assertEquals(List.of("list-b-" + stamp), client.listDistributionTenantsByCustomization(
                                request -> request.certificateArn(certArn))
                        .distributionTenantList().stream().map(DistributionTenantSummary::name).toList());
                assertAwsError("InvalidArgument", 400,
                        () -> client.listDistributionTenantsByCustomization(request -> request.maxItems(5)));
            } finally {
                disableAndDelete(client, a.distributionTenant().id());
                disableAndDelete(client, b.distributionTenant().id());
                disableAndDelete(client, c.distributionTenant().id());
                disableAndDeleteDistribution(client, first);
                disableAndDeleteDistribution(client, second);
            }
        }
    }

    @Test
    void aConnectionGroupWithATenantCannotBeDeleted() {
        try (CloudFrontClient client = client()) {
            String distributionId = createDistribution(client, "tenant-only");
            String stamp = stamp();
            CreateConnectionGroupResponse group = client.createConnectionGroup(request -> request.name("cg-tenant-" + stamp).enabled(false));
            String groupId = group.connectionGroup().id();
            String tenantId = client.createDistributionTenant(request -> request
                            .distributionId(distributionId).name("tenant-" + stamp).connectionGroupId(groupId)
                            .domains(DomainItem.builder().domain("cg-" + stamp + ".example.com").build()))
                    .distributionTenant().id();
            try {
                assertAwsError("CannotDeleteEntityWhileInUse", 409, () -> client.deleteConnectionGroup(
                        request -> request.id(groupId).ifMatch(group.eTag())));
                disableAndDelete(client, tenantId);
                client.deleteConnectionGroup(request -> request.id(groupId).ifMatch(group.eTag()));
            } finally {
                disableAndDelete(client, tenantId);
                disableAndDeleteDistribution(client, distributionId);
            }
        }
    }

    @Test
    void aTenantArnNamesTheAccountOfTheRequest() {
        try (CloudFrontClient client = AwsTestClients.create(CloudFrontClient.builder(), "111122223333",
                Region.US_EAST_1, endpoint)) {
            String distributionId = createDistribution(client, "tenant-only");
            String stamp = stamp();
            DistributionTenant tenant = client.createDistributionTenant(request -> request
                    .distributionId(distributionId).name("acct-" + stamp)
                    .domains(DomainItem.builder().domain("acct-" + stamp + ".example.com").build()))
                    .distributionTenant();
            try {
                assertTrue(tenant.arn().contains("::111122223333:distribution-tenant/" + tenant.id()),
                        tenant.arn());
            } finally {
                disableAndDelete(client, tenant.id());
                disableAndDeleteDistribution(client, distributionId);
            }
        }
    }

    @Test
    void aParameterWithoutANameIsRefusedOnTheWire() {
        try (CloudFrontClient client = client()) {
            String distributionId = createDistribution(client, "tenant-only");
            String stamp = stamp();
            DistributionTenant tenant = client.createDistributionTenant(request -> request
                    .distributionId(distributionId).name("noname-" + stamp)
                    .domains(DomainItem.builder().domain("noname-" + stamp + ".example.com").build()))
                    .distributionTenant();
            try {
                String create = "<CreateDistributionTenantRequest xmlns=\"" + CLOUDFRONT_NS + "\">"
                        + "<DistributionId>" + distributionId + "</DistributionId><Name>raw-" + stamp + "</Name>"
                        + "<Domains><DomainItem><Domain>raw-" + stamp + ".example.com</Domain></DomainItem></Domains>"
                        + "<Parameters><Parameter><Value>x</Value></Parameter></Parameters>"
                        + "</CreateDistributionTenantRequest>";
                given().contentType("application/xml").body(create)
                        .when().post(endpoint + "2020-05-31/distribution-tenant")
                        .then().statusCode(400).body(containsString("InvalidArgument"));

                String update = "<UpdateDistributionTenantRequest xmlns=\"" + CLOUDFRONT_NS + "\">"
                        + "<Parameters><Parameter><Name></Name><Value>x</Value></Parameter></Parameters>"
                        + "</UpdateDistributionTenantRequest>";
                String etag = client.getDistributionTenant(request -> request.identifier(tenant.id())).eTag();
                given().contentType("application/xml").header("If-Match", etag).body(update)
                        .when().put(endpoint + "2020-05-31/distribution-tenant/" + tenant.id())
                        .then().statusCode(400).body(containsString("InvalidArgument"));
            } finally {
                disableAndDelete(client, tenant.id());
                disableAndDeleteDistribution(client, distributionId);
            }
        }
    }

    @Test
    void aDistributionPreservesTenantParameterDefinitionsOnCreateAndRead() {
        try (CloudFrontClient client = client()) {
            TenantConfig tenantConfig = TenantConfig.builder()
                    .parameterDefinitions(
                            ParameterDefinition.builder()
                                    .name("tenantDomain")
                                    .definition(ParameterDefinitionSchema.builder()
                                            .stringSchema(StringSchemaConfig.builder()
                                                    .required(true)
                                                    .build())
                                            .build())
                                    .build(),
                            ParameterDefinition.builder()
                                    .name("tier")
                                    .definition(ParameterDefinitionSchema.builder()
                                            .stringSchema(StringSchemaConfig.builder()
                                                    .required(false)
                                                    .defaultValue("standard")
                                                    .comment("Tenant service tier")
                                                    .build())
                                            .build())
                                    .build())
                    .build();
            CreateDistributionResponse created =
                    createDistribution(client, "tenant-only", tenantConfig);
            String distributionId = created.distribution().id();
            try {
                assertTenantParameterDefinitions(
                        created.distribution().distributionConfig().tenantConfig());
                assertTenantParameterDefinitions(client.getDistribution(
                        request -> request.id(distributionId)).distribution().distributionConfig().tenantConfig());
                assertTenantParameterDefinitions(client.getDistributionConfig(
                        request -> request.id(distributionId)).distributionConfig().tenantConfig());
            } finally {
                disableAndDeleteDistribution(client, distributionId);
            }
        }
    }

    @Test
    void tenantTagsFollowTheCloudFrontTagApi() {
        try (CloudFrontClient client = client()) {
            String distributionId = createDistribution(client, "tenant-only");
            String stamp = stamp();
            DistributionTenant tenant = client.createDistributionTenant(request -> request
                    .distributionId(distributionId).name("tag-" + stamp)
                    .domains(DomainItem.builder().domain("t-" + stamp + ".example.com").build())
                    .tags(Tags.builder().items(Tag.builder().key("a").value("1").build()).build()))
                    .distributionTenant();
            try {
                client.tagResource(request -> request.resource(tenant.arn())
                        .tags(Tags.builder().items(Tag.builder().key("b").value("2").build()).build()));
                assertEquals(2, client.listTagsForResource(request -> request.resource(tenant.arn()))
                        .tags().items().size());
                client.untagResource(request -> request.resource(tenant.arn())
                        .tagKeys(keys -> keys.items("a")));
                assertEquals("b", client.listTagsForResource(request -> request.resource(tenant.arn()))
                        .tags().items().getFirst().key());
                assertEquals("b", client.getDistributionTenant(request -> request.identifier(tenant.id()))
                        .distributionTenant().tags().items().getFirst().key());
            } finally {
                disableAndDelete(client, tenant.id());
                disableAndDeleteDistribution(client, distributionId);
            }
        }
    }

    private String createDistribution(CloudFrontClient client, String connectionMode) {
        return createDistribution(client, connectionMode, null).distribution().id();
    }

    private CreateDistributionResponse createDistribution(
            CloudFrontClient client, String connectionMode, TenantConfig tenantConfig) {
        DistributionConfig.Builder config = DistributionConfig.builder()
                .callerReference("tenant-config-" + stamp())
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
                .connectionMode(connectionMode);
        if (tenantConfig != null) {
            config.tenantConfig(tenantConfig);
        }
        return client.createDistribution(request -> request.distributionConfig(config.build()));
    }

    private void assertTenantParameterDefinitions(TenantConfig tenantConfig) {
        assertNotNull(tenantConfig);
        assertEquals(2, tenantConfig.parameterDefinitions().size());
        assertEquals("tenantDomain", tenantConfig.parameterDefinitions().get(0).name());
        assertTrue(tenantConfig.parameterDefinitions().get(0).definition()
                .stringSchema().required());
        assertEquals("tier", tenantConfig.parameterDefinitions().get(1).name());
        assertFalse(tenantConfig.parameterDefinitions().get(1).definition()
                .stringSchema().required());
        assertEquals("standard", tenantConfig.parameterDefinitions().get(1).definition()
                .stringSchema().defaultValue());
        assertEquals("Tenant service tier", tenantConfig.parameterDefinitions().get(1).definition()
                .stringSchema().comment());
    }

    private void disableAndDeleteDistribution(CloudFrontClient client, String distributionId) {
        try {
            String etag = client.getDistribution(request -> request.id(distributionId)).eTag();
            client.updateDistribution(request -> request.id(distributionId).ifMatch(etag)
                    .distributionConfig(client.getDistributionConfig(byId -> byId.id(distributionId))
                            .distributionConfig().toBuilder().enabled(false).build()));
            String freshEtag = client.getDistribution(request -> request.id(distributionId)).eTag();
            client.deleteDistribution(request -> request.id(distributionId).ifMatch(freshEtag));
        } catch (NoSuchDistributionException | InvalidIfMatchVersionException | PreconditionFailedException
                 | ResourceNotDisabledException | CannotDeleteEntityWhileInUseException ignored) {
            // teardown only: the emulator state is per-test, so a partially cleaned distribution cannot leak into another test
        }
    }

    private void disableAndDelete(CloudFrontClient client, String id) {
        try {
            String etag = client.getDistributionTenant(request -> request.identifier(id)).eTag();
            client.updateDistributionTenant(request -> request.id(id).ifMatch(etag).enabled(false));
            String freshEtag = client.getDistributionTenant(request -> request.identifier(id)).eTag();
            client.deleteDistributionTenant(request -> request.id(id).ifMatch(freshEtag));
        } catch (EntityNotFoundException | InvalidIfMatchVersionException | PreconditionFailedException
                 | ResourceNotDisabledException | EntityAlreadyExistsException ignored) {
            // teardown only: the emulator state is per-test, so a partially cleaned tenant cannot leak into another test
        }
    }

    private static String stamp() {
        return Long.toString(System.nanoTime(), 36);
    }

    private CloudFrontClient client() {
        return AwsTestClients.create(CloudFrontClient.builder(), "000000000000", Region.US_EAST_1, endpoint);
    }
}
