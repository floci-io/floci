package io.floci.aws.services.cloudformation;

import io.floci.aws.core.common.AwsException;
import io.floci.aws.services.cloudfront.CloudFrontService;
import io.floci.aws.services.cloudfront.model.ConnectionGroup;
import io.floci.aws.services.cloudfront.model.Distribution;
import io.floci.aws.services.cloudfront.model.DistributionConfig;
import io.floci.aws.testing.CfnRequests;
import io.floci.aws.testing.CfnStackWaits;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * AWS::CloudFront::ConnectionGroup through the stack lifecycle: the exact {@code Ref} and
 * {@code Fn::GetAtt} values from the registry schema's read-only members, an in-place update with
 * its tags and {@code ViewerFallback} handling, a replacement when the create-only {@code Name}
 * changes, and DeleteStack of a group that is still enabled.
 */
@QuarkusTest
class CloudFormationCloudFrontConnectionGroupIntegrationTest {

    private static final String CFN_AUTH =
            "AWS4-HMAC-SHA256 Credential=test/20260205/us-east-1/cloudformation/aws4_request";

    @Inject
    CloudFrontService cloudFrontService;

    @Test
    void connectionGroupFollowsTheStackLifecycle() {
        String suffix = Long.toString(System.nanoTime(), 36);
        String stackName = "cfn-cloudfront-connection-group-" + suffix;

        CfnRequests.deploy("CreateStack", stackName, template(subName(suffix), true,
                "[{\"Key\": \"Env\", \"Value\": \"test\"}]", null), CFN_AUTH, "CREATE_COMPLETE");

        String groupId = output(stackName, "GroupRef");
        assertEquals(groupId, output(stackName, "GroupId"));
        ConnectionGroup group = cloudFrontService.getConnectionGroup(groupId);
        assertEquals("cfn-connection-group-" + suffix, group.getName());
        assertTrue(group.getEnabled());
        assertTrue(group.getIpv6Enabled());
        assertTrue(group.getArn().endsWith(":connection-group/" + groupId));
        assertEquals(group.getArn(), output(stackName, "GroupArn"));
        assertEquals(group.getRoutingEndpoint(), output(stackName, "GroupRoutingEndpoint"));
        assertEquals(group.getStatus(), output(stackName, "GroupStatus"));
        assertEquals(String.valueOf(group.getIsDefault()), output(stackName, "GroupIsDefault"));
        assertEquals(group.getEtag(), output(stackName, "GroupEtag"));
        assertEquals(group.getCreatedTime().toString(), output(stackName, "GroupCreatedTime"));
        assertEquals(group.getLastModifiedTime().toString(), output(stackName, "GroupLastModifiedTime"));
        assertEquals(Map.of("Env", "test"), cloudFrontService.listTagsForResource(group.getArn()));

        CfnRequests.deploy("UpdateStack", stackName, template(subName(suffix), false,
                "[{\"Key\": \"Team\", \"Value\": \"edge\"}]", null), CFN_AUTH, "UPDATE_COMPLETE");
        ConnectionGroup updated = cloudFrontService.getConnectionGroup(groupId);
        assertEquals(groupId, output(stackName, "GroupRef"), "an update keeps the group");
        assertFalse(updated.getEnabled());
        assertTrue(updated.getIpv6Enabled());
        assertEquals(Map.of("Team", "edge"), cloudFrontService.listTagsForResource(updated.getArn()));
        assertEquals(Map.of("Team", "edge"), updated.getTags());

        deleteStack(stackName);
        assertEquals("EntityNotFound",
                assertThrows(AwsException.class, () -> cloudFrontService.getConnectionGroup(groupId)).getErrorCode());
    }

    @Test
    void viewerFallbackPresenceOnTheTemplateIsAuthoritative() {
        String suffix = Long.toString(System.nanoTime(), 36);
        String stackName = "cfn-cloudfront-connection-group-fallback-" + suffix;
        String distributionId = createdDistributionId(suffix);
        try {
            CfnRequests.deploy("CreateStack", stackName, template(subName(suffix), false, "[]", null),
                    CFN_AUTH, "CREATE_COMPLETE");
            String groupId = output(stackName, "GroupRef");

            CfnRequests.deploy("UpdateStack", stackName, template(subName(suffix), false, "[]",
                    "{\"DistributionId\": \"" + distributionId + "\"}"), CFN_AUTH, "UPDATE_COMPLETE");
            assertEquals(distributionId,
                    cloudFrontService.getConnectionGroup(groupId).getViewerFallbackDistributionId());

            CfnRequests.deploy("UpdateStack", stackName, template(subName(suffix), false, "[]", null),
                    CFN_AUTH, "UPDATE_COMPLETE");
            assertEquals(distributionId,
                    cloudFrontService.getConnectionGroup(groupId).getViewerFallbackDistributionId(),
                    "an update that drops ViewerFallback keeps the fallback");

            CfnRequests.deploy("UpdateStack", stackName, template(subName(suffix), false, "[]",
                    "{}"), CFN_AUTH, "UPDATE_COMPLETE");
            assertNull(cloudFrontService.getConnectionGroup(groupId).getViewerFallbackDistributionId(),
                    "a ViewerFallback the template still declares is authoritative");

            deleteStack(stackName);
        } finally {
            cloudFrontService.removeDistribution(distributionId);
        }
    }

    @Test
    void aChangedNameReplacesTheGroup() {
        String suffix = Long.toString(System.nanoTime(), 36);
        String stackName = "cfn-cloudfront-connection-group-rename-" + suffix;

        CfnRequests.deploy("CreateStack", stackName, template(subName(suffix), true, "[]", null),
                CFN_AUTH, "CREATE_COMPLETE");
        String firstId = output(stackName, "GroupRef");

        String renamed = "renamed-" + suffix;
        CfnRequests.deploy("UpdateStack", stackName, template(subName(renamed), true, "[]", null),
                CFN_AUTH, "UPDATE_COMPLETE");
        String secondId = output(stackName, "GroupRef");
        assertEquals("cfn-connection-group-" + renamed, cloudFrontService.getConnectionGroup(secondId).getName());
        assertEquals("EntityNotFound",
                assertThrows(AwsException.class,
                        () -> cloudFrontService.getConnectionGroup(firstId)).getErrorCode(),
                "the displaced group is deleted once the update commits");

        deleteStack(stackName);
    }

    @Test
    void aStackWithAnEnabledGroupDeletesIt() {
        String suffix = Long.toString(System.nanoTime(), 36);
        String stackName = "cfn-cloudfront-connection-group-enabled-" + suffix;

        CfnRequests.deploy("CreateStack", stackName, template(subName(suffix), true, "[]", null),
                CFN_AUTH, "CREATE_COMPLETE");
        String groupId = output(stackName, "GroupRef");
        assertTrue(cloudFrontService.getConnectionGroup(groupId).getEnabled());

        deleteStack(stackName);
        assertEquals("EntityNotFound",
                assertThrows(AwsException.class, () -> cloudFrontService.getConnectionGroup(groupId)).getErrorCode());
    }

    /** The name routes through an intrinsic, as a parameterized template would. */
    private static String subName(String suffix) {
        return "{\"Fn::Sub\": [\"cfn-connection-group-${Suffix}\", {\"Suffix\": \"" + suffix + "\"}]}";
    }

    private String createdDistributionId(String suffix) {
        DistributionConfig config = new DistributionConfig();
        config.setCallerReference("cfn-fallback-" + suffix);
        Distribution dist = new Distribution();
        dist.setConfig(config);
        return cloudFrontService.createDistribution(dist, Map.of()).getId();
    }

    private static String output(String stackName, String key) {
        return CfnRequests.output(CfnRequests.describe(stackName, CFN_AUTH), key);
    }

    private static String template(String nameJson, boolean enabled, String tagsJson,
                                   String viewerFallbackJson) {
        String viewerFallback = viewerFallbackJson == null ? ""
                : "\"ViewerFallback\": " + viewerFallbackJson + ",";
        return """
                {
                  "Resources": {
                    "Group": {
                      "Type": "AWS::CloudFront::ConnectionGroup",
                      "Properties": {
                        "Name": %s,
                        "Enabled": %s,
                        "Ipv6Enabled": true,
                        %s
                        "Tags": %s
                      }
                    }
                  },
                  "Outputs": {
                    "GroupRef": {"Value": {"Ref": "Group"}},
                    "GroupId": {"Value": {"Fn::GetAtt": "Group.Id"}},
                    "GroupArn": {"Value": {"Fn::GetAtt": "Group.Arn"}},
                    "GroupRoutingEndpoint": {"Value": {"Fn::GetAtt": "Group.RoutingEndpoint"}},
                    "GroupStatus": {"Value": {"Fn::GetAtt": "Group.Status"}},
                    "GroupIsDefault": {"Value": {"Fn::GetAtt": "Group.IsDefault"}},
                    "GroupEtag": {"Value": {"Fn::GetAtt": "Group.ETag"}},
                    "GroupCreatedTime": {"Value": {"Fn::GetAtt": "Group.CreatedTime"}},
                    "GroupLastModifiedTime": {"Value": {"Fn::GetAtt": "Group.LastModifiedTime"}}
                  }
                }
                """.formatted(nameJson, enabled, viewerFallback, tagsJson);
    }

    private static void deleteStack(String stackName) {
        CfnStackWaits.deleteStack(stackName, CFN_AUTH);
    }
}
