package io.floci.aws.services.cloudfront;

import io.floci.aws.core.common.XmlParser;
import io.floci.aws.testing.AwsTestClients;
import io.quarkus.test.common.http.TestHTTPResource;
import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.Test;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.cloudfront.CloudFrontClient;
import software.amazon.awssdk.services.cloudfront.model.CannotDeleteEntityWhileInUseException;
import software.amazon.awssdk.services.cloudfront.model.ConnectionGroup;
import software.amazon.awssdk.services.cloudfront.model.ConnectionGroupSummary;
import software.amazon.awssdk.services.cloudfront.model.CreateConnectionGroupResponse;
import software.amazon.awssdk.services.cloudfront.model.CreateDistributionResponse;
import software.amazon.awssdk.services.cloudfront.model.DefaultCacheBehavior;
import software.amazon.awssdk.services.cloudfront.model.DistributionConfig;
import software.amazon.awssdk.services.cloudfront.model.DistributionSummary;
import software.amazon.awssdk.services.cloudfront.model.EntityAlreadyExistsException;
import software.amazon.awssdk.services.cloudfront.model.EntityNotFoundException;
import software.amazon.awssdk.services.cloudfront.model.GetConnectionGroupByRoutingEndpointResponse;
import software.amazon.awssdk.services.cloudfront.model.GetConnectionGroupResponse;
import software.amazon.awssdk.services.cloudfront.model.InvalidIfMatchVersionException;
import software.amazon.awssdk.services.cloudfront.model.NoSuchDistributionException;
import software.amazon.awssdk.services.cloudfront.model.Origin;
import software.amazon.awssdk.services.cloudfront.model.Origins;
import software.amazon.awssdk.services.cloudfront.model.PreconditionFailedException;
import software.amazon.awssdk.services.cloudfront.model.ResourceNotDisabledException;
import software.amazon.awssdk.services.cloudfront.model.S3OriginConfig;
import software.amazon.awssdk.services.cloudfront.model.ViewerCertificate;

import java.net.URI;
import java.util.List;

import static io.floci.aws.testing.AwsSdkErrors.assertAwsError;
import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.xml.HasXPath.hasXPath;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The multi-tenant connection-group management plane, validated with the real CloudFront SDK:
 * a group is readable by id, name, ARN and routing endpoint, the account's default group is
 * minted with the first {@code tenant-only} distribution, and the If-Match guarded writes carry
 * their modeled errors.
 */
@QuarkusTest
class CloudFrontConnectionGroupSdkIntegrationTest {

    private static final String API = "/2020-05-31/";

    @TestHTTPResource("/")
    URI endpoint;

    @Test
    void aGroupIsReadableByIdNameArnAndRoutingEndpoint() {
        try (CloudFrontClient client = client()) {
            CreateConnectionGroupResponse created = client.createConnectionGroup(request -> request
                    .name("cg-sdk-read-" + stamp())
                    .enabled(true)
                    .ipv6Enabled(false));
            ConnectionGroup group = created.connectionGroup();
            try {
                assertEquals(201, created.sdkHttpResponse().statusCode());
                assertTrue(group.id().startsWith("cg_"));
                assertTrue(group.arn().endsWith(":connection-group/" + group.id()));
                assertTrue(group.routingEndpoint().endsWith(".cloudfront.net"));
                assertFalse(group.isDefault());
                assertNotNull(group.createdTime());

                GetConnectionGroupResponse byId =
                        client.getConnectionGroup(request -> request.identifier(group.id()));
                assertEquals(group.id(), byId.connectionGroup().id());
                assertEquals(created.eTag(), byId.eTag());

                GetConnectionGroupResponse byName =
                        client.getConnectionGroup(request -> request.identifier(group.name()));
                assertEquals(group.id(), byName.connectionGroup().id());

                GetConnectionGroupResponse byArn =
                        client.getConnectionGroup(request -> request.identifier(group.arn()));
                assertEquals(group.id(), byArn.connectionGroup().id());

                GetConnectionGroupByRoutingEndpointResponse byEndpoint =
                        client.getConnectionGroupByRoutingEndpoint(
                                request -> request.routingEndpoint(group.routingEndpoint()));
                assertEquals(group.id(), byEndpoint.connectionGroup().id());

                assertAwsError("EntityNotFound", 404,
                        () -> client.getConnectionGroup(request -> request.identifier("cg_doesnotexist")));
                assertAwsError("EntityNotFound", 404,
                        () -> client.getConnectionGroupByRoutingEndpoint(
                                request -> request.routingEndpoint("d0000000000000.cloudfront.net")));
            } finally {
                disableAndDeleteIfPresent(client, group.id(), created.eTag());
            }
        }
    }

    @Test
    void duplicateNamesAndIfMatchGuardedWritesCarryTheirModeledErrors() {
        try (CloudFrontClient client = client()) {
            String name = "cg-sdk-guard-" + stamp();
            CreateConnectionGroupResponse created =
                    client.createConnectionGroup(request -> request.name(name).enabled(true));
            String id = created.connectionGroup().id();
            try {
                assertAwsError("EntityAlreadyExists", 409,
                        () -> client.createConnectionGroup(request -> request.name(name)));

                assertAwsError("InvalidIfMatchVersion", 400,
                        () -> client.updateConnectionGroup(request -> request.id(id).enabled(false)));
                assertAwsError("PreconditionFailed", 412,
                        () -> client.updateConnectionGroup(request -> request.id(id)
                                .ifMatch("E000000000000").enabled(false)));
                assertAwsError("ResourceNotDisabled", 409,
                        () -> client.deleteConnectionGroup(request -> request.id(id).ifMatch(created.eTag())));

                client.updateConnectionGroup(request -> request.id(id)
                        .ifMatch(created.eTag()).enabled(false).ipv6Enabled(false));
                GetConnectionGroupResponse updated =
                        client.getConnectionGroup(request -> request.identifier(id));
                assertFalse(updated.connectionGroup().enabled());
                assertTrue(updated.eTag() != null && !updated.eTag().equals(created.eTag()));

                client.deleteConnectionGroup(request -> request.id(id).ifMatch(updated.eTag()));
                assertAwsError("EntityNotFound", 404,
                        () -> client.getConnectionGroup(request -> request.identifier(id)));
            } finally {
                disableAndDeleteIfPresent(client, id, created.eTag());
            }
        }
    }

    @Test
    void aTenantOnlyDistributionMintsTheDefaultGroupWhichCannotBeDeleted() {
        try (CloudFrontClient client = client()) {
            String distributionId = client.createDistribution(request -> request
                            .distributionConfig(tenantOnlyConfig()))
                    .distribution().id();
            try {
                List<ConnectionGroupSummary> after = client.listConnectionGroups(
                        request -> request.maxItems(100)).connectionGroups();
                ConnectionGroupSummary defaultGroup = after.stream()
                        .filter(ConnectionGroupSummary::isDefault)
                        .findFirst().orElseThrow(() -> new AssertionError(
                                "the tenant-only distribution must mint the account's default group"));
                assertTrue(defaultGroup.enabled());
                assertEquals("Deployed", defaultGroup.status());
                assertNotNull(defaultGroup.eTag());

                assertAwsError("CannotDeleteEntityWhileInUse", 409,
                        () -> client.deleteConnectionGroup(request -> request
                                .id(defaultGroup.id()).ifMatch(defaultGroup.eTag())));

                List<String> tenantOnlyIds = client.listDistributionsByConnectionMode(
                                request -> request.connectionMode("tenant-only"))
                        .distributionList().items().stream()
                        .map(summary -> summary.id()).toList();
                assertTrue(tenantOnlyIds.contains(distributionId));
                assertTrue(client.listDistributionsByConnectionMode(
                                request -> request.connectionMode("direct"))
                        .distributionList().items().stream()
                        .map(summary -> summary.id()).noneMatch(distributionId::equals));
            } finally {
                disableAndDeleteDistribution(client, distributionId);
            }
        }
    }

    @Test
    void anUnknownConnectionModeIsRefusedAndAnOmittedModeListsUnderDirect() {
        try (CloudFrontClient client = client()) {
            assertAwsError("InvalidArgument", 400,
                    () -> client.listDistributionsByConnectionMode(
                            request -> request.connectionMode("bogus")));

            String distributionId = client.createDistribution(request -> request
                            .distributionConfig(distributionConfig(null)))
                    .distribution().id();
            try {
                List<String> directIds = client.listDistributionsByConnectionMode(
                                request -> request.connectionMode("direct"))
                        .distributionList().items().stream()
                        .map(DistributionSummary::id).toList();
                assertTrue(directIds.contains(distributionId));
                assertTrue(client.listDistributionsByConnectionMode(
                                request -> request.connectionMode("tenant-only"))
                        .distributionList().items().stream()
                        .map(DistributionSummary::id).noneMatch(distributionId::equals));
            } finally {
                disableAndDeleteDistribution(client, distributionId);
            }
        }
    }

    @Test
    void deletingAFallbackDistributionClearsTheGroupsViewerFallback() {
        try (CloudFrontClient client = client()) {
            String distributionId = client.createDistribution(request -> request
                            .distributionConfig(distributionConfig(null)))
                    .distribution().id();
            try {
                String body = """
                        <CreateConnectionGroupRequest xmlns="http://cloudfront.amazonaws.com/doc/2020-05-31/">
                          <Name>cg-sdk-fallback-%s</Name>
                          <Enabled>false</Enabled>
                          <ViewerFallback><DistributionId>%s</DistributionId></ViewerFallback>
                        </CreateConnectionGroupRequest>
                        """.formatted(stamp(), distributionId);
                String groupId = XmlParser.extractFirst(given().contentType("application/xml").body(body)
                        .when().post(API + "connection-group").then().statusCode(201)
                        .extract().asString(), "Id", null);

                given()
                        .when().get(API + "connection-group/" + groupId)
                        .then().statusCode(200)
                        .body(hasXPath("//*[local-name()='ViewerFallback']/*[local-name()='DistributionId']/text()",
                                equalTo(distributionId)));

                disableAndDeleteDistribution(client, distributionId);

                given()
                        .when().get(API + "connection-group/" + groupId)
                        .then().statusCode(200)
                        .body(not(hasXPath("//*[local-name()='ViewerFallback']")));
            } finally {
                disableAndDeleteDistribution(client, distributionId);
            }
        }
    }

    @Test
    void aGroupCreatedUnderAnotherAccountCarriesThatAccountInItsArn() {
        try (CloudFrontClient client = AwsTestClients.create(
                CloudFrontClient.builder(), "111111111111", Region.US_EAST_1, endpoint)) {
            CreateConnectionGroupResponse created = client.createConnectionGroup(request -> request
                    .name("cg-sdk-account-" + stamp())
                    .enabled(false));
            String id = created.connectionGroup().id();
            try {
                assertTrue(created.connectionGroup().arn()
                        .startsWith("arn:aws:cloudfront::111111111111:connection-group/" + id));
                GetConnectionGroupResponse fetched = client.getConnectionGroup(
                        request -> request.identifier(created.connectionGroup().arn()));
                assertEquals(created.connectionGroup().arn(), fetched.connectionGroup().arn());
                assertTrue(client.listConnectionGroups(request -> request.maxItems(100))
                        .connectionGroups().stream()
                        .allMatch(group -> group.arn().startsWith("arn:aws:cloudfront::111111111111:")));
            } finally {
                disableAndDeleteIfPresent(client, id, created.eTag());
            }
        }
    }

    private DistributionConfig distributionConfig(String connectionMode) {
        DistributionConfig.Builder config = DistributionConfig.builder()
                .callerReference("saas-config-" + stamp())
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
                .viewerCertificate(ViewerCertificate.builder()
                        .cloudFrontDefaultCertificate(true)
                        .build());
        if (connectionMode != null) {
            config.connectionMode(connectionMode);
        }
        return config.build();
    }

    private DistributionConfig tenantOnlyConfig() {
        return distributionConfig("tenant-only");
    }

    private void disableAndDeleteDistribution(CloudFrontClient client, String distributionId) {
        if (distributionId == null) {
            return;
        }
        try {
            String etag = client.getDistribution(request -> request.id(distributionId)).eTag();
            client.updateDistribution(request -> request.id(distributionId).ifMatch(etag)
                    .distributionConfig(client.getDistributionConfig(byId -> byId.id(distributionId))
                            .distributionConfig().toBuilder().enabled(false).build()));
            String freshEtag = client.getDistribution(request -> request.id(distributionId)).eTag();
            client.deleteDistribution(request -> request.id(distributionId).ifMatch(freshEtag));
        } catch (NoSuchDistributionException | EntityNotFoundException | InvalidIfMatchVersionException
                 | PreconditionFailedException | ResourceNotDisabledException
                 | CannotDeleteEntityWhileInUseException | EntityAlreadyExistsException ignored) {
        }
    }

    private void disableAndDeleteIfPresent(CloudFrontClient client, String id, String etag) {
        if (id == null) {
            return;
        }
        try {
            client.updateConnectionGroup(request -> request.id(id).ifMatch(etag).enabled(false));
            String freshEtag = client.getConnectionGroup(request -> request.identifier(id)).eTag();
            client.deleteConnectionGroup(request -> request.id(id).ifMatch(freshEtag));
        } catch (EntityNotFoundException | InvalidIfMatchVersionException | PreconditionFailedException
                 | ResourceNotDisabledException | CannotDeleteEntityWhileInUseException
                 | EntityAlreadyExistsException ignored) {
        }
    }

    private static String stamp() {
        return Long.toString(System.nanoTime(), 36);
    }

    private CloudFrontClient client() {
        return AwsTestClients.create(CloudFrontClient.builder(), "000000000000", Region.US_EAST_1, endpoint);
    }
}
