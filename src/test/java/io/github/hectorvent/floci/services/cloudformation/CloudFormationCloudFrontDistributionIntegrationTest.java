package io.github.hectorvent.floci.services.cloudformation;

import io.github.hectorvent.floci.services.cloudfront.CloudFrontService;
import io.github.hectorvent.floci.services.cloudfront.model.Distribution;
import io.github.hectorvent.floci.services.s3.S3Service;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * End-to-end check that CloudFormation provisions an {@code AWS::CloudFront::Distribution} for real
 * (into {@link io.github.hectorvent.floci.services.cloudfront.CloudFrontService}) rather than stubbing
 * it: {@code Fn::GetAtt DomainName} resolves to the assigned {@code *.cloudfront.net} domain (closes
 * #1147, where it previously returned the raw {@code LogicalId.DomainName} token), and the provisioned
 * distribution actually serves its S3 origin.
 */
@QuarkusTest
class CloudFormationCloudFrontDistributionIntegrationTest {

    private static final String CFN_AUTH =
            "AWS4-HMAC-SHA256 Credential=test/20260205/us-east-1/cloudformation/aws4_request";

    @Inject
    S3Service s3Service;

    @Inject
    CloudFrontService cloudFrontService;

    @Test
    void createStackProvisionsBrowsableDistributionWithResolvedDomainName() {
        String suffix = Long.toString(System.nanoTime(), 36);
        String bucket = "cfn-cf-content-" + suffix;
        String alias = "cfn-console-" + suffix + ".example.test";
        String stackName = "cfn-cloudfront-stack-" + suffix;

        s3Service.createBucket(bucket, "us-east-1");
        s3Service.putObject(bucket, "index.html",
                ("CFN-INDEX-" + suffix).getBytes(StandardCharsets.UTF_8), "text/html", Map.of());

        String template = """
                {
                  "Resources": {
                    "Dist": {
                      "Type": "AWS::CloudFront::Distribution",
                      "Properties": {
                        "DistributionConfig": {
                          "Enabled": true,
                          "DefaultRootObject": "index.html",
                          "Aliases": ["%s"],
                          "Origins": [
                            {
                              "Id": "s3-origin",
                              "DomainName": "%s.s3.us-east-1.amazonaws.com",
                              "S3OriginConfig": { "OriginAccessIdentity": "" }
                            }
                          ],
                          "DefaultCacheBehavior": {
                            "TargetOriginId": "s3-origin",
                            "ViewerProtocolPolicy": "allow-all"
                          }
                        }
                      }
                    }
                  },
                  "Outputs": {
                    "DistDomain": {"Value": {"Fn::GetAtt": ["Dist", "DomainName"]}},
                    "DistId": {"Value": {"Ref": "Dist"}}
                  }
                }
                """.formatted(alias, bucket);

        given()
            .contentType("application/x-www-form-urlencoded")
            .header("Authorization", CFN_AUTH)
            .formParam("Action", "CreateStack")
            .formParam("StackName", stackName)
            .formParam("TemplateBody", template)
        .when()
            .post("/")
        .then()
            .statusCode(200);

        // Stack completes and Fn::GetAtt(Dist, DomainName) resolves to a real *.cloudfront.net domain
        // (not the unresolved "Dist.DomainName" token).
        given()
            .contentType("application/x-www-form-urlencoded")
            .header("Authorization", CFN_AUTH)
            .formParam("Action", "DescribeStacks")
            .formParam("StackName", stackName)
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body(containsString("<StackStatus>CREATE_COMPLETE</StackStatus>"))
            .body(containsString(".cloudfront.net"))
            .body(not(containsString("Dist.DomainName")));

        // The provisioned distribution is browsable: a request to its alias serves the S3 origin's
        // default root object.
        given()
            .header("Host", alias)
        .when()
            .get("/")
        .then()
            .statusCode(200)
            .body(containsString("CFN-INDEX-" + suffix));
    }

    @Test
    void preservesTrustedKeyGroupsOnDefaultAndOrderedBehaviors() {
        String suffix = Long.toString(System.nanoTime(), 36);
        String alias = "cfn-private-" + suffix + ".example.test";
        String template = """
                {
                  "Resources": {
                    "Dist": {
                      "Type": "AWS::CloudFront::Distribution",
                      "Properties": {
                        "DistributionConfig": {
                          "Enabled": true,
                          "Aliases": ["%s"],
                          "Origins": [{
                            "Id": "s3-origin",
                            "DomainName": "private-content.s3.us-east-1.amazonaws.com",
                            "S3OriginConfig": {"OriginAccessIdentity": ""}
                          }],
                          "DefaultCacheBehavior": {
                            "TargetOriginId": "s3-origin",
                            "ViewerProtocolPolicy": "allow-all",
                            "TrustedKeyGroups": ["kg-default"]
                          },
                          "CacheBehaviors": [{
                            "PathPattern": "/private/*",
                            "TargetOriginId": "s3-origin",
                            "ViewerProtocolPolicy": "allow-all",
                            "TrustedKeyGroups": ["kg-ordered-a", "kg-ordered-b"]
                          }]
                        }
                      }
                    }
                  }
                }
                """.formatted(alias);

        given()
            .contentType("application/x-www-form-urlencoded")
            .header("Authorization", CFN_AUTH)
            .formParam("Action", "CreateStack")
            .formParam("StackName", "cfn-cloudfront-private-" + suffix)
            .formParam("TemplateBody", template)
        .when()
            .post("/")
        .then()
            .statusCode(200);

        Distribution distribution = cloudFrontService.listDistributions(null, 0).stream()
                .filter(item -> item.getConfig().getAliases() != null
                        && item.getConfig().getAliases().contains(alias))
                .findFirst()
                .orElseThrow();
        assertEquals(List.of("kg-default"),
                distribution.getConfig().getDefaultCacheBehavior().getTrustedKeyGroups());
        assertEquals(List.of("kg-ordered-a", "kg-ordered-b"),
                distribution.getConfig().getCacheBehaviors().getFirst().getTrustedKeyGroups());
    }
}
