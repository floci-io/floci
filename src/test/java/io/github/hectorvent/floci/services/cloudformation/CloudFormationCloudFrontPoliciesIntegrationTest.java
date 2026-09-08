package io.github.hectorvent.floci.services.cloudformation;

import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.services.cloudfront.CloudFrontService;
import io.github.hectorvent.floci.services.cloudfront.model.CachePolicy;
import io.github.hectorvent.floci.services.cloudfront.model.Distribution;
import io.github.hectorvent.floci.services.cloudfront.model.OriginAccessControl;
import io.github.hectorvent.floci.services.cloudfront.model.OriginRequestPolicy;
import io.github.hectorvent.floci.services.cloudfront.model.ResponseHeadersPolicy;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.containsString;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * The CloudFront configuration types through the stack lifecycle: the reproduction from issue #2441
 * (a response headers policy referenced by a distribution in the same stack), then cache policy,
 * origin request policy and origin access control with their exact {@code Fn::GetAtt} keys.
 */
@QuarkusTest
class CloudFormationCloudFrontPoliciesIntegrationTest {

    private static final String CFN_AUTH =
            "AWS4-HMAC-SHA256 Credential=test/20260205/us-east-1/cloudformation/aws4_request";

    @Inject
    CloudFrontService cloudFrontService;

    @Test
    void distributionRefToAResponseHeadersPolicyInTheSameStackResolves() {
        String suffix = Long.toString(System.nanoTime(), 36);
        String stackName = "floci-response-policy-ref-repro-" + suffix;
        String policyName = "floci-response-policy-ref-repro-" + suffix;

        createStack(stackName, issueTemplate(policyName, "Minimal response headers policy reference reproduction"));

        String describe = describeStacks(stackName);
        assertTrue(describe.contains("<StackStatus>CREATE_COMPLETE</StackStatus>"), describe);
        String policyId = output(describe, "PolicyRef");
        assertEquals(policyId, output(describe, "PolicyId"));
        assertFalse(policyId.startsWith("ResponseHeadersPolicy"), "Ref must be the real id, not a stub: " + policyId);
        ResponseHeadersPolicy policy = cloudFrontService.getResponseHeadersPolicy(policyId);
        assertEquals(policyName, policy.getName());
        assertEquals(policy.getLastModifiedTime().toString(), output(describe, "PolicyModified"));
        assertEquals(List.of(Map.of("Header", "X-Floci-Repro", "Value", "enabled", "Override", "true")),
                policy.getConfig().get("CustomHeadersConfig"));

        String distributionId = output(describe, "DistributionId");
        Distribution distribution = cloudFrontService.getDistribution(distributionId);
        assertEquals(policyId, distribution.getConfig().getDefaultCacheBehavior().getResponseHeadersPolicyId());

        String firstEtag = policy.getEtag();
        updateStack(stackName, issueTemplate(policyName, "second revision"));
        String updated = describeStacks(stackName);
        assertTrue(updated.contains("<StackStatus>UPDATE_COMPLETE</StackStatus>"), updated);
        assertEquals(policyId, output(updated, "PolicyRef"));
        ResponseHeadersPolicy revised = cloudFrontService.getResponseHeadersPolicy(policyId);
        assertEquals("second revision", revised.getComment());
        assertNotEquals(firstEtag, revised.getEtag());

        deleteStack(stackName);
        assertThrows(AwsException.class, () -> cloudFrontService.getDistribution(distributionId));
        AwsException gone = assertThrows(AwsException.class, () -> cloudFrontService.getResponseHeadersPolicy(policyId));
        assertEquals("NoSuchResponseHeadersPolicy", gone.getErrorCode());
    }

    @Test
    void cachePolicyOriginRequestPolicyAndOriginAccessControlExposeTheirAttributes() {
        String suffix = Long.toString(System.nanoTime(), 36);
        String stackName = "cfn-cloudfront-policies-" + suffix;

        createStack(stackName, """
                {
                  "Resources": {
                    "Cache": {
                      "Type": "AWS::CloudFront::CachePolicy",
                      "Properties": {
                        "CachePolicyConfig": {
                          "Name": "cfn-cache-%1$s",
                          "DefaultTTL": 86400, "MaxTTL": 31536000, "MinTTL": 1,
                          "ParametersInCacheKeyAndForwardedToOrigin": {
                            "EnableAcceptEncodingGzip": true,
                            "CookiesConfig": {"CookieBehavior": "none"},
                            "HeadersConfig": {"HeaderBehavior": "none"},
                            "QueryStringsConfig": {"QueryStringBehavior": "none"}
                          }
                        }
                      }
                    },
                    "OriginRequest": {
                      "Type": "AWS::CloudFront::OriginRequestPolicy",
                      "Properties": {
                        "OriginRequestPolicyConfig": {
                          "Name": "cfn-origin-request-%1$s",
                          "CookiesConfig": {"CookieBehavior": "all"},
                          "HeadersConfig": {"HeaderBehavior": "allViewer"},
                          "QueryStringsConfig": {"QueryStringBehavior": "all"}
                        }
                      }
                    },
                    "Oac": {
                      "Type": "AWS::CloudFront::OriginAccessControl",
                      "Properties": {
                        "OriginAccessControlConfig": {
                          "Name": "cfn-oac-%1$s",
                          "Description": "bucket access",
                          "SigningBehavior": "always",
                          "SigningProtocol": "sigv4",
                          "OriginAccessControlOriginType": "s3"
                        }
                      }
                    },
                    "Dist": {
                      "Type": "AWS::CloudFront::Distribution",
                      "Properties": {
                        "DistributionConfig": {
                          "Enabled": true,
                          "Origins": [{
                            "Id": "s3-origin",
                            "DomainName": "cfn-%1$s.s3.us-east-1.amazonaws.com",
                            "OriginAccessControlId": {"Ref": "Oac"},
                            "S3OriginConfig": {"OriginAccessIdentity": ""}
                          }],
                          "DefaultCacheBehavior": {
                            "TargetOriginId": "s3-origin",
                            "ViewerProtocolPolicy": "redirect-to-https",
                            "CachePolicyId": {"Ref": "Cache"},
                            "OriginRequestPolicyId": {"Ref": "OriginRequest"}
                          }
                        }
                      }
                    }
                  },
                  "Outputs": {
                    "CacheRef": {"Value": {"Ref": "Cache"}},
                    "CacheId": {"Value": {"Fn::GetAtt": ["Cache", "Id"]}},
                    "CacheModified": {"Value": {"Fn::GetAtt": ["Cache", "LastModifiedTime"]}},
                    "OriginRequestRef": {"Value": {"Ref": "OriginRequest"}},
                    "OriginRequestId": {"Value": {"Fn::GetAtt": ["OriginRequest", "Id"]}},
                    "OriginRequestModified": {"Value": {"Fn::GetAtt": ["OriginRequest", "LastModifiedTime"]}},
                    "OacRef": {"Value": {"Ref": "Oac"}},
                    "OacId": {"Value": {"Fn::GetAtt": ["Oac", "Id"]}},
                    "DistributionId": {"Value": {"Ref": "Dist"}}
                  }
                }
                """.formatted(suffix));

        String describe = describeStacks(stackName);
        assertTrue(describe.contains("<StackStatus>CREATE_COMPLETE</StackStatus>"), describe);

        String cacheId = output(describe, "CacheRef");
        assertEquals(cacheId, output(describe, "CacheId"));
        CachePolicy cache = cloudFrontService.getCachePolicy(cacheId);
        assertEquals("cfn-cache-" + suffix, cache.getName());
        assertEquals(cache.getLastModifiedTime().toString(), output(describe, "CacheModified"));
        assertEquals("86400", cache.getConfig().get("DefaultTTL"));

        String originRequestId = output(describe, "OriginRequestRef");
        assertEquals(originRequestId, output(describe, "OriginRequestId"));
        OriginRequestPolicy originRequest = cloudFrontService.getOriginRequestPolicy(originRequestId);
        assertEquals("cfn-origin-request-" + suffix, originRequest.getName());
        assertEquals(originRequest.getLastModifiedTime().toString(), output(describe, "OriginRequestModified"));

        String oacId = output(describe, "OacRef");
        assertEquals(oacId, output(describe, "OacId"));
        OriginAccessControl oac = cloudFrontService.getOriginAccessControl(oacId);
        assertEquals("cfn-oac-" + suffix, oac.getName());
        assertEquals("always", oac.getSigningBehavior());
        assertEquals("s3", oac.getOriginAccessControlOriginType());

        Distribution distribution = cloudFrontService.getDistribution(output(describe, "DistributionId"));
        assertEquals(oacId, distribution.getConfig().getOrigins().getFirst().getOriginAccessControlId());

        deleteStack(stackName);
        assertEquals("NoSuchCachePolicy",
                assertThrows(AwsException.class, () -> cloudFrontService.getCachePolicy(cacheId)).getErrorCode());
        assertEquals("NoSuchOriginRequestPolicy",
                assertThrows(AwsException.class, () -> cloudFrontService.getOriginRequestPolicy(originRequestId)).getErrorCode());
        assertEquals("NoSuchOriginAccessControl",
                assertThrows(AwsException.class, () -> cloudFrontService.getOriginAccessControl(oacId)).getErrorCode());
    }

    /** The template from issue #2441, with a unique policy name and the outputs the assertions read. */
    private static String issueTemplate(String policyName, String comment) {
        return """
                AWSTemplateFormatVersion: "2010-09-09"
                Resources:
                  ResponseHeadersPolicy:
                    Type: AWS::CloudFront::ResponseHeadersPolicy
                    Properties:
                      ResponseHeadersPolicyConfig:
                        Name: %s
                        Comment: %s
                        CustomHeadersConfig:
                          Items:
                            - Header: X-Floci-Repro
                              Value: enabled
                              Override: true

                  Distribution:
                    Type: AWS::CloudFront::Distribution
                    Properties:
                      DistributionConfig:
                        Enabled: true
                        Origins:
                          - Id: origin
                            DomainName: example-bucket.s3.amazonaws.com
                            S3OriginConfig: {}
                        DefaultCacheBehavior:
                          TargetOriginId: origin
                          ViewerProtocolPolicy: redirect-to-https
                          ForwardedValues:
                            QueryString: false
                          ResponseHeadersPolicyId: !Ref ResponseHeadersPolicy
                Outputs:
                  PolicyRef:
                    Value: !Ref ResponseHeadersPolicy
                  PolicyId:
                    Value: !GetAtt ResponseHeadersPolicy.Id
                  PolicyModified:
                    Value: !GetAtt ResponseHeadersPolicy.LastModifiedTime
                  DistributionId:
                    Value: !Ref Distribution
                """.formatted(policyName, comment);
    }

    private static void createStack(String stackName, String template) {
        stackCall("CreateStack", stackName, template);
    }

    private static void updateStack(String stackName, String template) {
        stackCall("UpdateStack", stackName, template);
    }

    private static void stackCall(String action, String stackName, String template) {
        given()
            .contentType("application/x-www-form-urlencoded")
            .header("Authorization", CFN_AUTH)
            .formParam("Action", action)
            .formParam("StackName", stackName)
            .formParam("TemplateBody", template)
        .when()
            .post("/")
        .then()
            .statusCode(200);
    }

    private static void deleteStack(String stackName) {
        given()
            .contentType("application/x-www-form-urlencoded")
            .header("Authorization", CFN_AUTH)
            .formParam("Action", "DeleteStack")
            .formParam("StackName", stackName)
        .when()
            .post("/")
        .then()
            .statusCode(200);
        given()
            .contentType("application/x-www-form-urlencoded")
            .header("Authorization", CFN_AUTH)
            .formParam("Action", "DescribeStacks")
            .formParam("StackName", stackName)
        .when()
            .post("/")
        .then()
            .statusCode(400)
            .body(containsString("Stack with id " + stackName + " does not exist"));
    }

    private static String describeStacks(String stackName) {
        return given()
            .contentType("application/x-www-form-urlencoded")
            .header("Authorization", CFN_AUTH)
            .formParam("Action", "DescribeStacks")
            .formParam("StackName", stackName)
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .extract().asString();
    }

    private static String output(String describeStacks, String key) {
        Matcher member = Pattern.compile("<member>(.*?)</member>", Pattern.DOTALL).matcher(describeStacks);
        while (member.find()) {
            String block = member.group(1);
            if (block.contains("<OutputKey>" + key + "</OutputKey>")) {
                Matcher value = Pattern.compile("<OutputValue>(.*?)</OutputValue>", Pattern.DOTALL).matcher(block);
                if (value.find()) {
                    return value.group(1);
                }
            }
        }
        return fail("Output " + key + " missing from: " + describeStacks);
    }
}
