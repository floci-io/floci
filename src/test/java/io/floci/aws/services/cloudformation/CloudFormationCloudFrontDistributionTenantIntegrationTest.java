package io.floci.aws.services.cloudformation;

import io.floci.aws.core.common.AwsException;
import io.floci.aws.services.cloudfront.CloudFrontService;
import io.floci.aws.services.cloudfront.model.DistributionTenant;
import io.floci.aws.testing.CfnRequests;
import io.floci.aws.testing.CfnStackWaits;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * AWS::CloudFront::DistributionTenant through the stack lifecycle: a tenant-only distribution and
 * its tenant, the exact {@code Ref} and {@code Fn::GetAtt} values from the registry schema's
 * read-only members, an in-place update with its declarative tags and parameters, a replacement
 * when the create-only {@code Name} changes, and DeleteStack of a tenant that is still enabled.
 */
@QuarkusTest
class CloudFormationCloudFrontDistributionTenantIntegrationTest {

    private static final String CFN_AUTH =
            "AWS4-HMAC-SHA256 Credential=test/20260205/us-east-1/cloudformation/aws4_request";

    @Inject
    CloudFrontService cloudFrontService;

    @Test
    void tenantFollowsTheStackLifecycle() {
        String suffix = Long.toString(System.nanoTime(), 36);
        String stackName = "cfn-cloudfront-tenant-" + suffix;

        CfnRequests.deploy("CreateStack", stackName, template(suffix, "acme-" + suffix, true,
                "[{\"Key\": \"Env\", \"Value\": \"test\"}]", "[{\"Name\": \"origin_path\", \"Value\": \"/a\"}]"),
                CFN_AUTH, "CREATE_COMPLETE");

        String tenantId = output(stackName, "TenantRef");
        assertEquals(tenantId, output(stackName, "TenantId"));
        DistributionTenant tenant = cloudFrontService.getDistributionTenant(tenantId);
        assertEquals("cfn-tenant-" + suffix, tenant.getName());
        assertEquals(List.of("acme-" + suffix + ".example.com"), tenant.getDomains());
        assertTrue(tenant.getEnabled());
        assertEquals(output(stackName, "DistributionId"), tenant.getDistributionId());
        assertTrue(tenant.getArn().endsWith(":distribution-tenant/" + tenantId));
        assertEquals(tenant.getArn(), output(stackName, "TenantArn"));
        assertEquals(tenant.getEtag(), output(stackName, "TenantEtag"));
        assertEquals(tenant.getStatus(), output(stackName, "TenantStatus"));
        assertEquals(tenant.getCreatedTime().toString(), output(stackName, "TenantCreatedTime"));
        assertEquals(tenant.getLastModifiedTime().toString(), output(stackName, "TenantLastModifiedTime"));
        assertEquals(Map.of("Env", "test"), cloudFrontService.listTagsForResource(tenant.getArn()));
        assertEquals(Map.of("origin_path", "/a"), tenant.getParameters());

        CfnRequests.deploy("UpdateStack", stackName, template(suffix, "other-" + suffix, false,
                "[{\"Key\": \"Team\", \"Value\": \"edge\"}]", "[]"), CFN_AUTH, "UPDATE_COMPLETE");
        DistributionTenant updated = cloudFrontService.getDistributionTenant(tenantId);
        assertEquals(tenantId, output(stackName, "TenantRef"), "an update keeps the tenant");
        assertFalse(updated.getEnabled());
        assertEquals(List.of("other-" + suffix + ".example.com"), updated.getDomains());
        assertNull(updated.getParameters(), "a template that empties Parameters clears them");
        assertEquals(Map.of("Team", "edge"), cloudFrontService.listTagsForResource(updated.getArn()));

        deleteStack(stackName);
        assertEquals("EntityNotFound",
                assertThrows(AwsException.class, () -> cloudFrontService.getDistributionTenant(tenantId))
                        .getErrorCode());
    }

    @Test
    void aChangedNameReplacesTheTenant() {
        String suffix = Long.toString(System.nanoTime(), 36);
        String stackName = "cfn-cloudfront-tenant-rename-" + suffix;

        CfnRequests.deploy("CreateStack", stackName, template(suffix, "acme-" + suffix, true, "[]", "[]"),
                CFN_AUTH, "CREATE_COMPLETE");
        String firstId = output(stackName, "TenantRef");

        CfnRequests.deploy("UpdateStack", stackName,
                template(suffix + "r", "renamed-" + suffix, true, "[]", "[]"), CFN_AUTH, "UPDATE_COMPLETE");
        String secondId = output(stackName, "TenantRef");
        assertEquals("cfn-tenant-" + suffix + "r", cloudFrontService.getDistributionTenant(secondId).getName());
        assertEquals("EntityNotFound",
                assertThrows(AwsException.class, () -> cloudFrontService.getDistributionTenant(firstId))
                        .getErrorCode(),
                "the displaced tenant is deleted once the update commits");

        deleteStack(stackName);
    }

    @Test
    void aStackWithAnEnabledTenantDeletesIt() {
        String suffix = Long.toString(System.nanoTime(), 36);
        String stackName = "cfn-cloudfront-tenant-enabled-" + suffix;

        CfnRequests.deploy("CreateStack", stackName, template(suffix, "acme-" + suffix, true, "[]", "[]"),
                CFN_AUTH, "CREATE_COMPLETE");
        String tenantId = output(stackName, "TenantRef");
        assertTrue(cloudFrontService.getDistributionTenant(tenantId).getEnabled());

        deleteStack(stackName);
        assertEquals("EntityNotFound",
                assertThrows(AwsException.class, () -> cloudFrontService.getDistributionTenant(tenantId))
                        .getErrorCode());
    }

    private static String output(String stackName, String key) {
        return CfnRequests.output(CfnRequests.describe(stackName, CFN_AUTH), key);
    }

    private static String template(String suffix, String domainLabel, boolean enabled, String tagsJson,
                                   String parametersJson) {
        return """
                {
                  "Resources": {
                    "Dist": {
                      "Type": "AWS::CloudFront::Distribution",
                      "Properties": {
                        "DistributionConfig": {
                          "Enabled": true,
                          "ConnectionMode": "tenant-only",
                          "Origins": [{"Id": "origin1", "DomainName": "example-bucket.s3.us-east-1.amazonaws.com",
                                       "S3OriginConfig": {"OriginAccessIdentity": ""}}],
                          "DefaultCacheBehavior": {"TargetOriginId": "origin1",
                                                   "ViewerProtocolPolicy": "redirect-to-https",
                                                   "CachePolicyId": "658327ea-f89d-4fab-a63d-7e88639e58f6"}
                        }
                      }
                    },
                    "Tenant": {
                      "Type": "AWS::CloudFront::DistributionTenant",
                      "Properties": {
                        "DistributionId": {"Ref": "Dist"},
                        "Name": {"Fn::Sub": ["cfn-tenant-${Suffix}", {"Suffix": "%s"}]},
                        "Domains": ["%s.example.com"],
                        "Enabled": %s,
                        "Tags": %s,
                        "Parameters": %s
                      }
                    }
                  },
                  "Outputs": {
                    "DistributionId": {"Value": {"Ref": "Dist"}},
                    "TenantRef": {"Value": {"Ref": "Tenant"}},
                    "TenantId": {"Value": {"Fn::GetAtt": "Tenant.Id"}},
                    "TenantArn": {"Value": {"Fn::GetAtt": "Tenant.Arn"}},
                    "TenantEtag": {"Value": {"Fn::GetAtt": "Tenant.ETag"}},
                    "TenantStatus": {"Value": {"Fn::GetAtt": "Tenant.Status"}},
                    "TenantCreatedTime": {"Value": {"Fn::GetAtt": "Tenant.CreatedTime"}},
                    "TenantLastModifiedTime": {"Value": {"Fn::GetAtt": "Tenant.LastModifiedTime"}}
                  }
                }
                """.formatted(suffix, domainLabel, enabled, tagsJson, parametersJson);
    }

    private static void deleteStack(String stackName) {
        CfnStackWaits.deleteStack(stackName, CFN_AUTH);
    }
}
