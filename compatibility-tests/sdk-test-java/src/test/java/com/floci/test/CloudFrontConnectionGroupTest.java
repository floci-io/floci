package com.floci.test;

import org.junit.jupiter.api.Test;
import software.amazon.awssdk.services.cloudfront.CloudFrontClient;
import software.amazon.awssdk.services.cloudfront.model.ConnectionGroupSummary;
import software.amazon.awssdk.services.cloudfront.model.CreateConnectionGroupResponse;
import software.amazon.awssdk.services.cloudfront.model.EntityAlreadyExistsException;
import software.amazon.awssdk.services.cloudfront.model.EntityNotFoundException;
import software.amazon.awssdk.services.cloudfront.model.GetConnectionGroupByRoutingEndpointResponse;
import software.amazon.awssdk.services.cloudfront.model.GetConnectionGroupResponse;
import software.amazon.awssdk.services.cloudfront.model.InvalidIfMatchVersionException;
import software.amazon.awssdk.services.cloudfront.model.PreconditionFailedException;
import software.amazon.awssdk.services.cloudfront.model.ResourceNotDisabledException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CloudFrontConnectionGroupTest {

    @Test
    void roundTripsConnectionGroupsWithTheAwsSdk() {
        String name = "compat-connection-group-" + TestFixtures.uniqueName();

        try (CloudFrontClient cloudFront = TestFixtures.cloudFrontClient()) {
            CreateConnectionGroupResponse created =
                    cloudFront.createConnectionGroup(request -> request
                            .name(name)
                            .enabled(true)
                            .ipv6Enabled(false));
            String groupId = created.connectionGroup().id();
            String etag = created.eTag();
            try {
                assertNotNull(groupId);
                assertTrue(groupId.startsWith("cg_"));
                assertTrue(created.connectionGroup().arn().endsWith(":connection-group/" + groupId));
                // CloudFront in China names its edge hosts under cloudfront.cn.
                String edgeSuffix = "aws-cn".equals(TestFixtures.partition()) ? ".cloudfront.cn" : ".cloudfront.net";
                String routingEndpoint = created.connectionGroup().routingEndpoint();
                assertTrue(routingEndpoint.endsWith(edgeSuffix), routingEndpoint);
                assertFalse(created.connectionGroup().isDefault());
                assertNotNull(created.connectionGroup().createdTime());
                assertFalse(created.connectionGroup().ipv6Enabled());

                GetConnectionGroupResponse byId =
                        cloudFront.getConnectionGroup(request -> request.identifier(groupId));
                assertEquals(groupId, byId.connectionGroup().id());
                assertEquals(etag, byId.eTag());

                GetConnectionGroupResponse byName =
                        cloudFront.getConnectionGroup(request -> request.identifier(name));
                assertEquals(groupId, byName.connectionGroup().id());

                GetConnectionGroupResponse byArn =
                        cloudFront.getConnectionGroup(request -> request.identifier(created.connectionGroup().arn()));
                assertEquals(groupId, byArn.connectionGroup().id());

                GetConnectionGroupByRoutingEndpointResponse byEndpoint =
                        cloudFront.getConnectionGroupByRoutingEndpoint(request -> request
                                .routingEndpoint(created.connectionGroup().routingEndpoint()));
                assertEquals(groupId, byEndpoint.connectionGroup().id());

                ConnectionGroupSummary listed = cloudFront.listConnectionGroups(
                                request -> request.maxItems(100))
                        .connectionGroups().stream()
                        .filter(group -> groupId.equals(group.id()))
                        .findFirst()
                        .orElseThrow();
                assertEquals(name, listed.name());
                assertEquals(etag, listed.eTag());
            } finally {
                disableAndDelete(cloudFront, groupId, etag);
            }
        }
    }

    @Test
    void guardedWritesCarryTheirModeledErrors() {
        String name = "compat-connection-group-" + TestFixtures.uniqueName();

        try (CloudFrontClient cloudFront = TestFixtures.cloudFrontClient()) {
            CreateConnectionGroupResponse created =
                    cloudFront.createConnectionGroup(request -> request.name(name).enabled(true));
            String groupId = created.connectionGroup().id();
            String etag = created.eTag();
            try {
                assertThrows(EntityAlreadyExistsException.class,
                        () -> cloudFront.createConnectionGroup(request -> request.name(name)));

                assertThrows(InvalidIfMatchVersionException.class,
                        () -> cloudFront.updateConnectionGroup(request -> request
                                .id(groupId).enabled(false)));
                assertThrows(PreconditionFailedException.class,
                        () -> cloudFront.updateConnectionGroup(request -> request
                                .id(groupId).ifMatch("E000000000000").enabled(false)));
                assertThrows(ResourceNotDisabledException.class,
                        () -> cloudFront.deleteConnectionGroup(request -> request
                                .id(groupId).ifMatch(etag)));

                cloudFront.updateConnectionGroup(request -> request
                        .id(groupId).ifMatch(etag).enabled(false).ipv6Enabled(false));
                GetConnectionGroupResponse updated =
                        cloudFront.getConnectionGroup(request -> request.identifier(groupId));
                assertFalse(updated.connectionGroup().enabled());
                assertFalse(updated.connectionGroup().ipv6Enabled());

                cloudFront.deleteConnectionGroup(request -> request.id(groupId).ifMatch(updated.eTag()));
                assertThrows(EntityNotFoundException.class,
                        () -> cloudFront.getConnectionGroup(request -> request.identifier(groupId)));
            } finally {
                disableAndDelete(cloudFront, groupId, updatedEtagFor(cloudFront, groupId));
            }
        }
    }

    private static String updatedEtagFor(CloudFrontClient cloudFront, String groupId) {
        try {
            return cloudFront.getConnectionGroup(request -> request.identifier(groupId)).eTag();
        } catch (EntityNotFoundException e) {
            return null;
        }
    }

    private static void disableAndDelete(CloudFrontClient cloudFront, String groupId, String etag) {
        if (groupId == null || etag == null) {
            return;
        }
        try {
            cloudFront.updateConnectionGroup(request -> request.id(groupId).ifMatch(etag).enabled(false));
            String freshEtag = cloudFront.getConnectionGroup(request -> request.identifier(groupId)).eTag();
            cloudFront.deleteConnectionGroup(request -> request.id(groupId).ifMatch(freshEtag));
        } catch (EntityNotFoundException ignored) {
            // teardown only: the emulator state is per-test, so a partially cleaned connection group cannot leak into another test
        }
    }
}
